"""Official ACP SDK interoperability against an explicitly configured local deployment.

The caller publishes synthetic text Agents and supplies private application/user token files.
No automatic retries, auto-approval, external URLs, or provider fallbacks are performed.
Install acp-sdk==1.0.3 in an isolated environment before running this acceptance.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import importlib.metadata
import json
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

from acp_sdk.client import Client
from acp_sdk.models import ACPError, Message, MessagePart, MessageAwaitResume


def require(value, code):
    if not value:
        raise RuntimeError(code)


def read_secret(path):
    value = Path(path).read_text(encoding="utf-8-sig").strip()
    require(value and "\n" not in value and "\r" not in value, "INVALID_PRIVATE_TOKEN_FILE")
    return value


async def run(config_path, report_path):
    config = json.loads(Path(config_path).read_text(encoding="utf-8-sig"))
    origin = config["origin"].rstrip("/")
    uri = urlsplit(origin)
    require(uri.scheme == "http" and uri.hostname == "127.0.0.1"
            and uri.path in ("", "/") and not uri.username and not uri.query
            and not uri.fragment, "EXPLICIT_LOOPBACK_ORIGIN_REQUIRED")
    project = config["project"]
    require(project.replace("-", "").replace("_", "").isalnum(), "INVALID_PROJECT")
    base = origin + "/acp/projects/" + project
    secret = read_secret(config["applicationCredentialFile"])
    token = read_secret(config["userTokenFile"])
    report = {
        "format": "harness-acp-acceptance-v1", "startedAt": datetime.now(timezone.utc).isoformat(),
        "sdk": "acp-sdk", "sdkVersion": importlib.metadata.version("acp-sdk"),
        "protocol": "ACP 0.2.0 text/JSON authenticated profile",
        "sourceCommit": "e5265ca9fa06c55cd011b1e81ee927f6d80af8f6",
        "status": "RUNNING", "checks": [], "runIds": [],
        "boundaries": {"sessionSupport": False, "multimodalSupport": False, "a2aSupport": False,
                       "syntheticInputsOnly": True, "automaticRetries": False},
    }
    path = Path(report_path)
    require(not path.exists(), "REPORT_ALREADY_EXISTS")

    def save():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    def client(user_token=token, key=None):
        headers = {"Authorization": "Bearer " + secret, "X-Harness-User-Token": user_token,
                   "Content-Type": "application/json"}
        if key:
            headers["Idempotency-Key"] = key
        return Client(base_url=base, headers=headers, timeout=75, trust_env=False)

    def message(text):
        return [Message(role="user", parts=[MessagePart(content=text, content_type="text/plain")])]

    def text(run):
        require(run.status.value == "completed", "RUN_NOT_COMPLETED")
        value = "".join(part.content or "" for msg in run.output for part in msg.parts)
        require(value and len(value) < 20000, "OUTPUT_UNAVAILABLE_OR_OVERSIZED")
        return value

    try:
        require(report["sdkVersion"] == "1.0.3", "FROZEN_SDK_VERSION_REQUIRED")
        save()
        async with client() as sdk:
            await sdk.ping()
            agents = [agent async for agent in sdk.agents()]
            names = {a.name for a in agents}
            for key in ("agentA", "agentB", "humanAgent"):
                # Read by name too: list defaults to the protocol's ten-item page.
                manifest = await sdk.agent(name=config[key])
                require(manifest.name == config[key], "DISCOVERY_BINDING_MISMATCH")
            report["checks"].append({"name": "official-sdk-discovery", "passed": True,
                                     "firstPageCount": len(names)})
        idempotency = "acp-chain-a-" + uuid.uuid4().hex
        question = "Synthetic acceptance: reply with one short sentence confirming an example support request."
        async with client(key=idempotency) as sdk:
            first = await sdk.run_sync(input=message(question), agent=config["agentA"])
            first_text = text(first)
            repeated = await sdk.run_sync(input=message(question), agent=config["agentA"])
            require(first.run_id == repeated.run_id, "DUPLICATE_RUN_CREATED")
            require(first_text == text(repeated), "IDEMPOTENT_RESULT_CHANGED")
            events = [event async for event in sdk.run_events(run_id=first.run_id)]
            require(any(event.type == "run.completed" for event in events), "COMPLETION_EVENT_MISSING")
            report["runIds"].append(str(first.run_id))
        # A separate client acts as the second Agent's caller and passes only the first output.
        async with client(key="acp-chain-b-" + uuid.uuid4().hex) as sdk:
            second = await sdk.run_sync(input=message("Summarize this synthetic handoff: " + first_text),
                                       agent=config["agentB"])
            second_text = text(second)
            require(second.run_id != first.run_id, "AGENTS_SHARE_RUN_ID")
            report["runIds"].append(str(second.run_id))
            report["checks"].append({"name": "two-agent-sdk-handoff-and-idempotency", "passed": True,
                                     "inputHandoffSha256": hashlib.sha256(first_text.encode()).hexdigest(),
                                     "finalOutputSha256": hashlib.sha256(second_text.encode()).hexdigest()})
        async with client(key="acp-stream-" + uuid.uuid4().hex) as sdk:
            types = []
            async for event in sdk.run_stream(input=message(question), agent=config["agentA"]):
                types.append(event.type)
            require("run.completed" in types and "error" not in types, "STREAM_NOT_COMPLETED")
            report["checks"].append({"name": "official-sdk-sse", "passed": True, "eventTypes": types})
        other = read_secret(config["otherUserTokenFile"])
        async with client(user_token=other) as sdk:
            try:
                await sdk.run_status(run_id=first.run_id)
                raise RuntimeError("OTHER_USER_READ_RUN")
            except ACPError as failure:
                require(getattr(failure.error.code, "value", failure.error.code) == "not_found",
                        "WRONG_ISOLATION_ERROR")
        report["checks"].append({"name": "cross-user-run-isolation", "passed": True})
        reviewer = read_secret(config["reviewerUserTokenFile"])
        async with client(user_token=reviewer, key="acp-human-" + uuid.uuid4().hex) as sdk:
            human = await sdk.run_sync(input=message("Synthetic review payload"), agent=config["humanAgent"])
            require(human.status.value == "awaiting", "HUMAN_GATE_NOT_WAITING")
            detail = json.loads(human.await_request.message.parts[0].content)
            require(detail["operation"] == "approval" and detail["approval"]["reviewComplete"],
                    "HUMAN_REVIEW_NOT_AVAILABLE")
            # Approval is the explicitly configured synthetic acceptance only.
            decision = {"operation": "approval", "etag": detail["etag"],
                        "approvalId": detail["approval"]["id"],
                        "decision": {"decision": "APPROVE", "digest": detail["approval"]["digest"],
                                     "input": "Reviewed synthetic ACP acceptance", "reason": "Synthetic local test"}}
            sdk.client.headers["Idempotency-Key"] = "acp-human-decision-" + uuid.uuid4().hex
            completed = await sdk.run_resume_sync(run_id=human.run_id, await_resume=MessageAwaitResume(
                message=Message(role="user", parts=[MessagePart(
                    content_type="application/json", content=json.dumps(decision))])))
            text(completed)
            report["runIds"].append(str(human.run_id))
            report["checks"].append({"name": "sdk-human-reviewed-resume", "passed": True})
        async with client(user_token=reviewer, key="acp-cancel-create-" + uuid.uuid4().hex) as sdk:
            pending = await sdk.run_sync(input=message("Synthetic cancellation payload"), agent=config["humanAgent"])
            require(pending.status.value == "awaiting", "CANCEL_TARGET_NOT_WAITING")
            sdk.client.headers["Idempotency-Key"] = "acp-cancel-" + uuid.uuid4().hex
            stopped = await sdk.run_cancel(run_id=pending.run_id)
            until = time.monotonic() + 15
            while stopped.status.value not in ("cancelled", "failed") and time.monotonic() < until:
                await asyncio.sleep(.2)
                stopped = await sdk.run_status(run_id=pending.run_id)
            require(stopped.status.value == "cancelled", "CANCEL_NOT_SETTLED")
            report["checks"].append({"name": "sdk-durable-cancellation", "passed": True})
        report["status"] = "PASSED"
    except Exception as failure:
        report["status"] = "FAILED"
        # Exception strings from external SDKs can contain response bodies. Never publish them.
        report["failureType"] = type(failure).__name__
        if isinstance(failure, RuntimeError) and str(failure).replace("_", "").isupper():
            report["failureCode"] = str(failure)
        raise
    finally:
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        save()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    try:
        asyncio.run(run(args.config, args.report))
    except Exception as failure:
        print("ACP acceptance failed; inspect private local diagnostics. Type: " + type(failure).__name__)
        raise SystemExit(1)
