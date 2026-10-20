"""Local formal-login/support flow with explicitly synthetic business inputs.

Credentials and fresh Ops captcha ID/code are read from ignored private JSON. Reports never
contain passwords, tokens, questions or answers. Unknown mutations stop for reconciliation.
"""
import argparse
import http.cookiejar
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Browser:
    def __init__(self, origin):
        parsed = urllib.parse.urlsplit(origin)
        if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.path:
            raise ValueError("Only a loopback HTTP support origin is permitted")
        self.origin = origin
        self.csrf = ""
        self.http = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), NoRedirect()
        )

    def call(self, path, body=None, expected=(200,), csrf=None):
        headers = {"Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers.update({
                "Content-Type": "application/json", "Origin": self.origin,
                "X-CSRF-Token": self.csrf if csrf is None else csrf,
            })
        request = urllib.request.Request(self.origin + path, data=data, headers=headers)
        try:
            response = self.http.open(request, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        except (urllib.error.URLError, TimeoutError):
            raise RuntimeError("Transport failure; reconcile unconfirmed mutations") from None
        with response:
            status = response.code
            payload = json.loads(response.read(1048577))
        if status not in expected:
            raise RuntimeError(f"Unexpected HTTP status {status} at {path.split('?')[0]}")
        return payload

    def login(self, credentials, role):
        if set(credentials) != {"username", "password", "captchaId", "captchaCode"}:
            raise ValueError("Each login requires username/password and fresh captcha ID/code")
        result = self.call("/api/session", {**credentials, "role": role})
        assert result["signedIn"] and result["role"] == role
        assert all(name not in result for name in ("accessToken", "refreshToken", "password", "userToken"))
        self.csrf = result["csrfToken"]
        return result


def run(config, checkpoint=lambda value: None):
    if config.get("syntheticData") is not True:
        raise ValueError("Explicit syntheticData=true is required for automated acceptance")
    origin = config["supportOrigin"].rstrip("/")
    operator, other, reviewer = Browser(origin), Browser(origin), Browser(origin)
    assert operator.call("/api/auth/config")["mode"] == "opsagent"
    first = operator.login(config["operatorLogin"], "operator")
    checkpoint({"stage": "OPERATOR_AUTHENTICATED"})
    second = other.login(config["secondUserLogin"], "operator")
    checkpoint({"stage": "SECOND_USER_AUTHENTICATED"})
    review_identity = reviewer.login(config["reviewerLogin"], "reviewer")
    checkpoint({"stage": "REVIEWER_AUTHENTICATED"})
    assert len({first["subject"], second["subject"], review_identity["subject"]}) == 3
    releases = operator.call("/api/agents")["body"]["items"]
    matches = [release for release in releases if release["agentId"] == config["agentId"]]
    if "releaseId" in config:
        matches = [release for release in matches if release["releaseRef"]["releaseId"] == config["releaseId"]]
    else:
        matches = [release for release in matches if release.get("isDefault")]
    if len(matches) != 1:
        raise ValueError("Select exactly one published/default support release")
    release = matches[0]
    command = {
        "agentId": release["agentId"], "releaseId": release["releaseRef"]["releaseId"],
        "releaseRef": release["releaseRef"],
        "inputs": config["inputs"], "requestKey": "support-local-" + str(uuid.uuid4()),
    }
    checkpoint({"stage": "CREATE_PREPARED", "createRequestKey": command["requestKey"],
                "agentId": release["agentId"], "releaseId": release["releaseRef"]["releaseId"]})
    operator.call("/api/runs", command, expected=(403,), csrf="invalid-csrf")
    reviewer.call("/api/runs", command, expected=(403,))
    created = operator.call("/api/runs", command, expected=(200, 201, 202))
    run_id = created["body"]["run"]["id"]
    checkpoint({"stage": "CREATE_ACKNOWLEDGED", "runId": run_id})
    # Deliberately replay an acknowledged command; never automatically retry a lost response.
    repeated = operator.call("/api/runs", command, expected=(200, 201, 202))
    assert repeated["body"]["run"]["id"] == run_id
    other.call("/api/runs/" + run_id, expected=(403, 404))
    other.call("/api/runs/" + run_id + "/events", expected=(403, 404))
    operator.call("/api/runs/" + run_id + "/approval", expected=(403,))
    approval_seen = False
    reviewed_answer = None
    deadline = time.monotonic() + min(int(config.get("timeoutSeconds", 180)), 600)
    while time.monotonic() < deadline:
        result = operator.call("/api/runs/" + run_id)["body"]
        status = result["status"]
        if status == "COMPLETED":
            break
        if status in {"FAILED", "CANCELLED", "EXPIRED", "BUDGET_EXCEEDED", "NEEDS_ATTENTION"}:
            raise RuntimeError("Run requires investigation: " + status)
        if status in {"WAITING_INPUT", "WAITING_APPROVAL"} and not approval_seen:
            approval = reviewer.call("/api/runs/" + run_id + "/approval")
            body = approval["body"]
            assert body["runId"] == run_id and body["status"] == "PENDING" and body["reviewComplete"]
            if config.get("requireReviewContext", True):
                fields = body["fields"]
                assert len(fields) == 1 and fields[0]["name"] == "answer" and not fields[0]["masked"]
                reviewed_answer = fields[0]["value"]
                for expected in config.get("expectedContains", []):
                    assert expected in reviewed_answer, "Generated review context did not match synthetic policy"
            decision = {
                "approvalId": body["id"], "digest": body["digest"], "decision": "APPROVE",
                "reason": "本机联调：已核对合成咨询及指定审核输入",
                "etag": approval["etag"], "requestKey": "support-review-" + str(uuid.uuid4()),
            }
            if body.get("inputRequired"):
                if "reviewInput" not in config:
                    raise ValueError("An explicit synthetic reviewInput is required")
                decision["input"] = config["reviewInput"]
            checkpoint({"stage": "DECISION_PREPARED", "decisionRequestKey": decision["requestKey"],
                        "approvalId": body["id"]})
            reviewer.call("/api/runs/" + run_id + "/decision", decision, expected=(200, 202))
            checkpoint({"stage": "DECISION_ACKNOWLEDGED"})
            approval_seen = True
        time.sleep(1)
    else:
        raise RuntimeError("Support Run did not complete before the acceptance deadline")
    if config.get("requireApproval", True) and not approval_seen:
        raise RuntimeError("The release did not exercise required human approval")
    assert result["output"]["visibility"] == "AVAILABLE"
    if config.get("requireReviewContext", True):
        assert result["output"].get("value") == reviewed_answer, "Approval changed the reviewed answer"
    output = json.dumps(result["output"].get("value"), ensure_ascii=False)
    for expected in config.get("expectedContains", []):
        assert expected in output, "Synthetic output assertion failed"
    operator.call("/api/logout", {})
    assert not operator.call("/api/session")["signedIn"]
    operator.call("/api/runs", expected=(401,))
    return {
        "status": "PASSED", "syntheticBusinessData": True, "authMode": "opsagent",
        "client": "real-http-cookie-session", "browserLoginVerified": False,
        "runId": run_id, "releaseId": release["releaseRef"]["releaseId"],
        "humanApproval": approval_seen, "separateUsers": 3, "crossUserReadDenied": True,
        "idempotentCreate": True, "csrfRejected": True, "reviewerCannotCreate": True,
        "generatedAnswerReviewed": reviewed_answer is not None,
        "approvedAnswerUnchanged": result["output"].get("value") == reviewed_answer,
        "logoutInvalidated": True, "productionAcceptance": False,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    config_path = args.config.resolve()
    if ".work" not in config_path.parts:
        raise SystemExit("Keep credentials in the repository's ignored .work directory")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    evidence = {"status": "RUNNING", "productionAcceptance": False}

    def checkpoint(value):
        evidence.update(value)
        # A report-write failure prevents the next mutation from being sent.
        args.report.write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    checkpoint({})
    try:
        completed = run(json.loads(config_path.read_text(encoding="utf-8-sig")), checkpoint)
        result = {**evidence, **completed, "stage": "COMPLETED"}
    except Exception as failure:
        result = {**evidence, "status": "FAILED", "errorType": type(failure).__name__, "productionAcceptance": False}
        if isinstance(failure, RuntimeError):
            result["failure"] = str(failure)
    args.report.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if result["status"] == "PASSED" else 1)
