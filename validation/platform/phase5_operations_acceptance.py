"""Operations/retention evidence against Runner's dedicated synthetic MySQL + JVM.

Only run_checks(runner) is public. It refuses an unexpected database/project,
backdates exactly one newly created fixture Run, and verifies the reviewed plan
cannot contain any historical rows. No actual user data or model calls are used.
"""
from __future__ import annotations

import time
import urllib.error
import urllib.request
import uuid

from process_acceptance import require


def run_checks(runner):
    require(runner.c["project"] == "phase5-execution", "OPERATIONS_PROJECT_MISMATCH")
    require(runner.c["mysql"]["database"] == "harness_phase5_execution", "OPERATIONS_DATABASE_MISMATCH")
    token = runner.env.get("HARNESS_METRICS_TOKEN", "")
    require(len(token) >= 32, "METRICS_TOKEN_REQUIRED_FOR_ACCEPTANCE")
    require(bool(runner.env.get("HARNESS_RETENTION_KEY")), "RETENTION_KEY_REQUIRED_FOR_ACCEPTANCE")
    client = runner.clients["A"]
    route = "/console/api/projects/phase5-execution/operations"

    def metric_request(secret=None):
        headers = {} if secret is None else {"Authorization": "Bearer " + secret}
        request = urllib.request.Request(client.origin + "/internal/metrics", headers=headers)
        try:
            response = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.read().decode("utf-8")

    status, _ = metric_request()
    require(status == 401, "UNAUTHENTICATED_METRICS_EXPOSED")
    status, _ = metric_request("synthetic-wrong-independent-metrics-token")
    require(status == 401, "WRONG_METRICS_TOKEN_ACCEPTED")
    status, metrics = metric_request(token)
    require(status == 200 and "harness_database_ready 1" in metrics, "AUTHORIZED_METRICS_FAILED")
    require(token not in metrics and 'project="phase5-execution"' in metrics, "METRICS_SCOPE_OR_SECRET_LEAK")

    overview_samples = []
    for _ in range(20):
        started = time.monotonic()
        status, overview, _ = client.request("GET", route)
        overview_samples.append((time.monotonic() - started) * 1000)
        require(status == 200, "OPERATIONS_OVERVIEW_READ_FAILED")
    overview_millis = runner.p95(overview_samples)
    runner.report["operationsLatencyEvidence"] = {
        "samples": len(overview_samples), "p95Millis": round(overview_millis, 3),
        "maxMillis": round(max(overview_samples), 3), "targetP95Millis": 500,
    }
    runner.save()
    require(overview_millis <= 500, "OPERATIONS_OVERVIEW_P95_ABOVE_500MS")
    require(status == 200 and overview.get("scope") == "PROJECT" and overview.get("capabilities", {}).get("admin"), "OPERATIONS_ADMIN_SCOPE_FAILED")
    require(overview["statusCounts"].get("COMPLETED", 0) >= 100000, "OPERATIONS_HISTORY_COUNT_MISMATCH")
    require(len(overview.get("workers", [])) >= 2, "OPERATIONS_WORKER_PROJECTION_MISSING")

    # Earlier killed-worker/uncertain-effect tests intentionally leave a real fixed-rule alert.
    alert = None
    until = time.monotonic() + 40
    while time.monotonic() < until:
        status, overview, _ = client.request("GET", route)
        require(status == 200, "OPERATIONS_ALERT_POLL_FAILED")
        alert = next((value for value in overview.get("alerts", []) if value.get("id") == "UNRESOLVED_EXECUTION" and value.get("state") == "FIRING"), None)
        if alert:
            break
        time.sleep(0.25)
    require(alert is not None, "DURABLE_UNKNOWN_ALERT_NOT_OBSERVED")
    acknowledgement_key = "phase5-ack-" + uuid.uuid4().hex
    status, acknowledged, _ = client.request("POST", route + "/alerts/" + alert["id"] + "/ack", {}, '"o' + str(alert["revision"]) + '"', acknowledgement_key)
    require(status == 200 and acknowledged.get("acknowledged") is True, "ALERT_ACKNOWLEDGEMENT_FAILED")
    status, receipt, _ = client.request("GET", route + "/commands/" + acknowledgement_key)
    require(status == 200 and receipt == acknowledged, "ALERT_EXACT_RECEIPT_FAILED")
    status, inbox, _ = client.request("GET", route + "/inbox?after=0&limit=200")
    require(status == 200 and any(event.get("transition") == "ACKNOWLEDGED" for event in inbox.get("items", [])), "LOCAL_INBOX_ACK_DELIVERY_MISSING")

    # Stop rather than silently include a previous or unrelated eligible payload.
    other = int(runner.sql("SELECT COUNT(*) FROM harness_run_index i JOIN platform_runs p ON p.run_id=i.run_id LEFT JOIN operations_retained_runs a ON a.run_id=i.run_id WHERE p.project_id='phase5-execution' AND i.terminal_at<UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000-30*86400000 AND a.run_id IS NULL"))
    require(other == 0, "UNRELATED_RETENTION_CANDIDATES_EXIST")
    creation_key = "platform-phase5-retention-" + uuid.uuid4().hex
    sentinel = creation_key + "-synthetic-body"
    body = {"releaseRef": runner.refs["read"], "inputs": {"caseKey": sentinel}, "clientReference": sentinel}
    status, accepted, _ = client.request("POST", runner.route, body, key=creation_key)
    require(status == 202, "RETENTION_FIXTURE_CREATE_FAILED")
    run_id = accepted["run"]["id"]
    uuid.UUID(run_id)  # only a validated UUID is interpolated into fixture SQL
    run, _ = runner.wait(run_id, {"COMPLETED"})
    require(len(runner.fixture.rows("dispatch", sentinel)) == 1, "RETENTION_FIXTURE_DISPATCH_MISMATCH")
    original_revision = run["revision"]
    original_usage = run["usage"]
    require(int(runner.sql("SELECT COUNT(*) FROM harness_runs WHERE run_id='" + run_id + "' AND LOCATE('" + sentinel + "',snapshot_json)>0")) == 1, "RETENTION_FIXTURE_BODY_NOT_PRESENT")
    runner.sql("START TRANSACTION; UPDATE harness_run_index SET terminal_at=UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000-31*86400000 WHERE run_id='" + run_id + "'; UPDATE harness_platform_trace SET started_at=UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000-31*86400000 WHERE run_id='" + run_id + "'; COMMIT;")
    status, plan, _ = client.request("POST", route + "/retention/preview", {"limit": 100})
    require(status == 200 and plan.get("archiveAvailable") is True, "RETENTION_PREVIEW_FAILED")
    require([candidate["runId"] for candidate in plan["candidates"]] == [run_id], "RETENTION_PLAN_INCLUDES_UNRELATED_RUNS")
    require(plan["candidates"][0]["action"] == "ARCHIVE_PAYLOAD", "RETENTION_PLAN_ACTION_MISMATCH")
    require(plan.get("traceCount", 0) > 0, "RETENTION_FIXTURE_TRACE_NOT_PRESENT")
    require(not any(plan.get(name) for name in ("expiredArchives", "expiredAudit", "expiredInbox")), "RETENTION_PLAN_INCLUDES_UNRELATED_HISTORY")
    apply_key = "phase5-retention-apply-" + uuid.uuid4().hex
    apply_body = {"planId": plan["planId"], "digest": plan["digest"]}
    status, applied, _ = client.request("POST", route + "/retention/apply", apply_body, key=apply_key)
    require(status == 200 and applied.get("archivedRuns") == 1, "RETENTION_APPLY_FAILED")
    require(applied.get("deletedArchives") == 0 and applied.get("deletedAuditRows") == 0, "RETENTION_UNEXPECTED_HISTORY_MUTATION")
    status, receipt, _ = client.request("GET", route + "/commands/" + apply_key)
    require(status == 200 and receipt == applied, "RETENTION_EXACT_RECEIPT_FAILED")
    status, replay_apply, _ = client.request("POST", route + "/retention/apply", apply_body, key=apply_key)
    require(status == 200 and replay_apply == applied, "RETENTION_APPLY_IDEMPOTENCY_FAILED")
    archived, _ = runner.get(run_id)
    require(archived.get("payloadArchived") is True and archived["output"]["visibility"] == "OMITTED", "ARCHIVED_OUTPUT_STILL_VISIBLE")
    require(archived["revision"] == original_revision and archived["usage"] == original_usage, "RETENTION_CHANGED_EXECUTION_LEDGER")
    values = runner.sql("SELECT COUNT(*),SUM(LOCATE('" + sentinel + "',ciphertext)=0),MIN(CHAR_LENGTH(ciphertext)) FROM operations_archives WHERE run_id='" + run_id + "'").split("\t")
    require(len(values) == 3 and values[0] == "1" and values[1] == "1" and int(values[2]) > 100, "ENCRYPTED_ARCHIVE_NOT_CONFIRMED")
    require(int(runner.sql("SELECT COUNT(*) FROM harness_runs WHERE run_id='" + run_id + "' AND LOCATE('" + sentinel + "',snapshot_json)=0")) == 1, "LIVE_PAYLOAD_NOT_REMOVED")
    status, replay, _ = client.request("POST", runner.route, body, key=creation_key)
    require(status == 202 and replay["run"]["id"] == run_id and replay.get("payloadArchived") is True, "ARCHIVED_CREATION_KEY_REEXECUTED")
    time.sleep(0.5)
    require(len(runner.fixture.rows("dispatch", sentinel)) == 1, "ARCHIVED_CALL_WAS_DISPATCHED_AGAIN")
    return {
        "metricsAuthentication": "missing/wrong rejected; dedicated token accepted",
        "overviewScope": "PROJECT",
        "overviewP95Millis": round(overview_millis, 3),
        "overviewSampleCount": len(overview_samples),
        "historicalCompletedRunsVisible": overview["statusCounts"]["COMPLETED"],
        "durableAlertAcknowledgementAndReceipt": True,
        "localInboxAcknowledgement": True,
        "syntheticArchivedRun": run_id,
        "backdatedSyntheticRunCount": 1,
        "planContainedOnlyTargetRun": True,
        "archivedRuns": 1,
        "deletedTraces": applied["deletedTraces"],
        "encryptedArchiveContainsNoPlaintextMarker": True,
        "executionRevisionAndUsagePreserved": True,
        "creationAndApplyIdempotencyPreserved": True,
        "totalFixtureDispatchesForArchivedRun": 1,
        "unrelatedHistoricalRowsCleaned": 0,
    }
