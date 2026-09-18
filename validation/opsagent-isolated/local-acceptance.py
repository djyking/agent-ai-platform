"""Controlled local MySQL/HTTP acceptance; credentials stay in ignored private files."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import tempfile
import time
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from uuid import uuid4


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--private-config", type=Path, required=True)
    parser.add_argument("--platform-jar", type=Path, required=True)
    parser.add_argument("--platform-config", type=Path, required=True)
    parser.add_argument("--mysql", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    config = json.loads(args.private_config.read_text(encoding="utf-8-sig"))
    metadata_path = Path(config["metadataFile"])
    meta = json.loads(metadata_path.read_text(encoding="utf-8-sig"))
    platform = config["platformOrigin"].rstrip("/")
    sql_url = urlparse(config["jdbcUrl"].removeprefix("jdbc:"))
    if sql_url.hostname != "127.0.0.1" or not sql_url.path.startswith("/opsagent_harness_pilot"):
        raise ValueError("Dedicated local pilot database required")
    releases = json.loads(subprocess.run(
        ["java", "-jar", str(args.platform_jar), "releases", str(args.platform_config)],
        check=True, capture_output=True, text=True, encoding="utf-8").stdout)
    refs = {row["releaseRef"]["agentId"]: row["releaseRef"] for row in releases}
    ops_ref = refs["ops-readonly"]
    cases = []
    run_ids = []

    def record(name):
        cases.append({"name": name, "status": "passed"})
        print("PASS " + name, flush=True)

    def call(origin, path, body=None, user=20, identity=False, extra=None):
        headers = {"Accept": "application/json"}
        if identity or origin == platform:
            headers["Authorization"] = "Bearer " + meta["applicationCredential"]
            if origin == platform:
                headers["X-Harness-User-Token"] = meta[f"user{user}Token"]
        else:
            headers["Authorization"] = "Bearer " + meta[f"user{user}Token"]
        if extra:
            headers.update(extra)
        data = None if body is None else json.dumps(body).encode()
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = Request(origin.rstrip("/") + path, data=data, headers=headers)
        try:
            with urlopen(request, timeout=20) as response:
                raw = response.read(1024 * 1024)
                return response.status, json.loads(raw) if raw else {}, dict(response.headers)
        except HTTPError as error:
            raw = error.read(65536)
            return error.code, json.loads(raw) if raw else {}, dict(error.headers)

    def search(key, query="Redis"):
        return call(meta["ragOrigin"], "/api/rag/harness-search", {"requestId": key, "query": query, "topK": 3})

    def switch(owner, **changes):
        prior = json.loads(metadata_path.read_text())["ingressReloadedAt"] if "ingressReloadedAt" in json.loads(metadata_path.read_text()) else None
        config["newOwner"] = owner
        config.update(changes)
        args.private_config.write_text(json.dumps(config), encoding="utf-8")
        for _ in range(120):
            try:
                current = json.loads(metadata_path.read_text())
                if current.get("ingressReloadedAt") != prior and current.get("ingressOwner") == owner:
                    assert current["ragOrigin"] == meta["ragOrigin"]
                    return
            except (OSError, json.JSONDecodeError):
                pass
            time.sleep(.1)
        raise AssertionError("Rag configuration reload did not preserve endpoint")

    def poll(run_id):
        for _ in range(100):
            status, body, headers = call(platform, "/v1/projects/ops-dev/runs/" + run_id)
            assert status == 200, "Run retrieval rejected"
            if body["status"] in {"COMPLETED", "FAILED", "NEEDS_ATTENTION", "CANCELLED"}:
                return body, headers
            time.sleep(.2)
        raise AssertionError("Run did not reach terminal state")

    def sql(statement):
        def quoted(value):
            return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".private.cnf", dir=args.private_config.parent, delete=False) as options:
            options.write("[client]\nhost=127.0.0.1\nport=" + str(sql_url.port)
                          + "\nuser=" + quoted(config["jdbcUser"]) + "\npassword=" + quoted(config["jdbcPassword"])
                          + "\ndatabase=" + quoted(sql_url.path.lstrip("/")) + "\n")
            options_path = Path(options.name)
        try:
            result = subprocess.run([str(args.mysql), "--defaults-extra-file=" + str(options_path), "-N", "-B"],
                                    input=statement, capture_output=True, text=True, encoding="utf-8")
            if result.returncode:
                raise AssertionError("Dedicated pilot SQL operation failed")
            return result.stdout.strip()
        finally:
            options_path.unlink()

    try:
        for user in (10, 20):
            status, result, _ = call(meta["authOrigin"], "/internal/harness/introspect",
                                     {"projectId": "ops-dev", "userToken": meta[f"user{user}Token"]}, identity=True)
            assert status == 200 and result["data"]["subject"] == str(user)
        record("existing_signed_jwt_real_auth_mapper_mysql_identity")
        switch("LEGACY", platformOrigin=platform, releaseId=ops_ref["releaseId"], releaseDigest=ops_ref["digest"])
        legacy_key = str(uuid4())
        status, legacy, _ = search(legacy_key)
        assert status == 200 and legacy["data"]["owner"] == "LEGACY"
        assert {row["documentId"] for row in legacy["data"]["result"]["citations"]} == {1, 2}
        legacy_route = legacy["data"]["routeId"]
        record("public_business_ingress_legacy_real_authorized_retrieval")

        switch("HARNESS")
        harness_key = str(uuid4())
        status, created, _ = search(harness_key)
        assert status == 202, "Harness route creation rejected"
        assert created["data"]["owner"] == "HARNESS"
        run_id = created["data"]["run"]["run"]["id"]
        run_ids.append(run_id)
        completed, initial_headers = poll(run_id)
        assert completed["status"] == "COMPLETED", "Ops retrieval run failed"
        assert completed["output"]["visibility"] == "AVAILABLE"
        assert {row["documentId"] for row in completed["output"]["value"]["citations"]} == {1, 2}
        assert completed["usage"]["modelCalls"] == 0 and completed["usage"]["toolCalls"] == 1
        record("public_business_ingress_harness_real_sql_run_retrieval_and_protected_output")
        retry_status, retry, _ = search(harness_key)
        assert retry_status == 202 and retry["data"]["run"]["run"]["id"] == run_id
        assert search(harness_key, "Changed")[0] == 409
        assert call(platform, "/v1/projects/ops-dev/runs/" + run_id, user=10)[0] == 404
        record("same_business_request_one_run_input_conflict_and_cross_subject_404")

        sql("UPDATE knowledge_document SET visibility='PRIVATE' WHERE id=1;")
        try:
            extra = {"If-None-Match": initial_headers["ETag"]} if "ETag" in initial_headers else {}
            status, denied, revoked_headers = call(platform, "/v1/projects/ops-dev/runs/" + run_id, extra=extra)
            assert status == 200 and denied["output"] == {"visibility": "OMITTED"}
            assert revoked_headers.get("ETag") != initial_headers.get("ETag")
        finally:
            sql("UPDATE knowledge_document SET visibility='PUBLIC' WHERE id=1;")
        assert call(platform, "/v1/projects/ops-dev/runs/" + run_id)[1]["output"]["visibility"] == "AVAILABLE"
        record("current_domain_acl_revocation_hides_stored_output_and_changes_etag")

        sql("UPDATE knowledge_document SET version=2 WHERE id=2;")
        try:
            assert call(platform, "/v1/projects/ops-dev/runs/" + run_id)[1]["output"] == {"visibility": "OMITTED"}
        finally:
            sql("UPDATE knowledge_document SET version=1 WHERE id=2;")
        record("stored_citation_version_change_denies_entire_output")

        sql("UPDATE ops_harness_grant SET enabled=0 WHERE project_id='ops-dev' AND user_id=20;")
        try:
            assert call(platform, "/v1/projects/ops-dev/runs/" + run_id)[0] == 403
        finally:
            sql("UPDATE ops_harness_grant SET enabled=1 WHERE project_id='ops-dev' AND user_id=20;")
        record("current_project_grant_revocation_denies_api_identity")

        switch("LEGACY", releaseId=str(uuid4()), releaseDigest="sha256:" + "f" * 64)
        status, retry, _ = search(harness_key)
        assert status == 202 and retry["data"]["run"]["run"]["id"] == run_id
        status, old_legacy, _ = search(legacy_key)
        assert status == 200 and old_legacy["data"]["routeId"] == legacy_route
        assert search(str(uuid4()))[1]["data"]["owner"] == "LEGACY"
        record("sql_route_owner_and_original_release_survive_switch_and_rag_restart")

        switch("HARNESS", platformOrigin="http://127.0.0.1:1", releaseId=ops_ref["releaseId"], releaseDigest=ops_ref["digest"])
        before = sql("SELECT COUNT(*) FROM ops_harness_delegation WHERE run_id LIKE 'legacy-%';")
        assert search(str(uuid4()))[0] == 503
        after = sql("SELECT COUNT(*) FROM ops_harness_delegation WHERE run_id LIKE 'legacy-%';")
        assert before == after
        record("harness_dependency_failure_does_not_create_legacy_delegation_or_fallback")

        docs_key = str(uuid4())
        status, docs, _ = call(platform, "/v1/projects/ops-dev/runs",
                              {"releaseRef": refs["development-docs"], "inputs": {"query": "Harness"}},
                              extra={"Idempotency-Key": docs_key})
        assert status == 202
        docs_id = docs["run"]["id"]
        run_ids.append(docs_id)
        docs_completed, _ = poll(docs_id)
        assert docs_completed["status"] == "COMPLETED" and docs_completed["output"]["visibility"] == "AVAILABLE"
        record("second_development_docs_scenario_uses_same_platform_api")

        sql("UPDATE ops_harness_delegation SET expires_at=UNIX_TIMESTAMP()-1 WHERE run_id='" + run_id + "';")
        assert call(platform, "/v1/projects/ops-dev/runs/" + run_id)[1]["output"] == {"visibility": "OMITTED"}
        record("expired_run_delegation_keeps_historical_protected_output_omitted")
    finally:
        switch("LEGACY", platformOrigin=platform, releaseId=ops_ref["releaseId"], releaseDigest=ops_ref["digest"])
        report = {"schemaVersion": 1, "at": datetime.now(timezone.utc).isoformat(),
                  "validationKind": "local-mysql-platform-opsagent-http-acceptance", "synthetic": True,
                  "status": "PASSED" if len(cases) == 11 else "FAILED", "cases": cases, "runIds": run_ids,
                  "mysqlBacked": True, "realPlatformProcess": True, "realPublicBusinessIngress": True,
                  "realAuthMapperAndDomainAcl": True, "modelGeneration": False,
                  "originalProductionBootConfiguration": False,
                  "limitations": ["Selected original domain classes in manual local MVC hosts; original boot/Nacos/ES/MQ not loaded",
                                  "Synthetic SQL users/documents; model/limiter/index dependencies remain explicit unused or disabled mocks",
                                  "Development-docs tool is a configured deterministic fixture; production model quality/capacity not covered"],
                  "finalNewRequestOwner": "LEGACY"}
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    assert len(cases) == 11


if __name__ == "__main__":
    main()
