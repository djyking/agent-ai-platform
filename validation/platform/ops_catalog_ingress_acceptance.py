"""Verify the local OpsAgent business ingress against a published catalog release.

The isolated host uses the legacy Agent ID ops-readonly. This runner publishes an
Agent with that ID from the exact immutable ops-knowledge-agent version accepted
by catalog_acceptance.py, reusing its resource references and frozen regression.
It reloads only the RAG ingress, preserves endpoint and identity host, and leaves
new requests routed to HARNESS. No model invocation or database reset occurs.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request
from uuid import uuid4

from catalog_acceptance import Acceptance, AcceptanceFailure, digest, encoded, read_json, require, utc


def local_origin(value):
    parsed = urlparse(value)
    require(parsed.scheme in {"http", "https"} and parsed.hostname in {"127.0.0.1", "localhost", "::1"}
            and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment
            and parsed.path in {"", "/"}, "LOCAL_INGRESS_REQUIRED")
    return value.rstrip("/")


class IngressAcceptance(Acceptance):
    def __init__(self, args):
        super().__init__(args)
        self.host = read_json(args.private_host_config)
        require(Path(self.host["metadataFile"]).resolve() == args.identity_metadata.resolve(),
                "HOST_METADATA_MISMATCH")
        jdbc = urlparse(self.host["jdbcUrl"].removeprefix("jdbc:"))
        require(jdbc.hostname == "127.0.0.1" and re.fullmatch(r"/opsagent_harness_pilot[a-z0-9_]*", jdbc.path),
                "ISOLATED_PILOT_DATABASE_REQUIRED")
        self.rag_origin = local_origin(self.metadata["ragOrigin"])
        self.report.update({"kind": "local-phase3-ops-catalog-business-ingress", "sourceCatalogReport": args.catalog_report.name})
        self.report["scope"].update({"liveModelRunCountPlanned": 0, "sameRagEndpointReload": True,
                                    "finalNewRequestOwner": "HARNESS", "identityHostRestarted": False})
        self.secrets += [value for value in self.host.values() if isinstance(value, str) and len(value) >= 8]

    def catalog_ingress_release(self):
        source_report = read_json(self.args.catalog_report)
        require(source_report.get("status") == "passed", "SOURCE_CATALOG_ACCEPTANCE_REQUIRED")
        source = source_report["releases"]["ops"]
        require(source["projectId"] == "ops-dev"
                and re.fullmatch(r"[a-z][a-z0-9-]{0,63}", source["agentId"])
                and isinstance(source["version"], int) and source["version"] > 0, "INVALID_SOURCE_AGENT")
        path = self.resource("ops-dev", "Agent", source["agentId"]) + "/versions/" + str(source["version"])
        version = self.call(self.publisher, "GET", path)[0]
        require(version["releaseRef"] == source["releaseRef"] and version["validation"]["passed"] is True,
                "SOURCE_IMMUTABLE_RELEASE_MISMATCH")
        agent = "ops-readonly"
        current, _, status = self.call(self.publisher, "GET", self.resource("ops-dev", "Agent", agent), expected=(200, 404))
        published = None
        if status == 200 and not current.get("disabled") and not current.get("revoked"):
            for summary in current["versions"]:
                if summary.get("disabled"):
                    continue
                existing = self.call(self.publisher, "GET", self.resource("ops-dev", "Agent", agent)
                                     + "/versions/" + str(summary["version"]))[0]
                if existing["spec"] == version["spec"] and existing["regressionCases"] == version["regressionCases"]:
                    published = existing
                    break
        if published is None:
            ref, result = self.publish("ops-dev", "Agent", agent, version["spec"], version["regressionCases"], existing=True)
            published = self.call(self.publisher, "GET", self.resource("ops-dev", "Agent", agent)
                                  + "/versions/" + str(ref["version"]))[0]
        self.command("ops-dev", "Agent", agent, "default", {"version": published["version"]})
        release = published["releaseRef"]
        require(release["agentId"] == agent and published["spec"] == version["spec"]
                and published["regressionCases"] == version["regressionCases"], "INGRESS_AGENT_COPY_MISMATCH")
        discovered = self.call(self.ops_owner, "GET", self.cat("ops-dev", "/agents/" + agent + "/default"))[0]
        require(discovered["releaseRef"] == release, "INGRESS_DEFAULT_MISMATCH")
        self.report["releases"] = {"source": source["releaseRef"], "ingress": release}
        self.record("business_agent_published_from_exact_catalog_source", sourceVersion=source["version"],
                    ingressVersion=published["version"], identicalSpec=True, identicalFrozenCases=True,
                    validationMode=published["validation"]["mode"], caseCount=published["validation"]["caseCount"])
        return release

    def switch(self, release):
        before = read_json(self.args.identity_metadata)
        config = read_json(self.args.private_host_config)
        config.update({"newOwner": "HARNESS", "platformOrigin": self.origin,
                       "releaseId": release["releaseId"], "releaseDigest": release["digest"]})
        if "agentId" in config:
            config["agentId"] = release["agentId"]
        path = self.args.private_host_config.resolve()
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, suffix=".private.json", delete=False) as handle:
            json.dump(config, handle)
            temporary = Path(handle.name)
        try:
            os.replace(temporary, path)
        finally:
            if temporary.exists():
                temporary.unlink()
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            try:
                after = read_json(self.args.identity_metadata)
                if after.get("ingressReloadedAt") != before.get("ingressReloadedAt") and after.get("ingressOwner") == "HARNESS":
                    require(all(after[name] == before[name] for name in
                                ("ragOrigin", "authOrigin", "knowledgeOrigin", "user10Token", "user20Token")),
                            "IDENTITY_OR_ENDPOINT_CHANGED")
                    self.record("same_endpoint_reload_to_catalog_release", newRequestOwner="HARNESS",
                                identityPreserved=True, endpointsPreserved=True)
                    return
            except (OSError, ValueError):
                pass
            time.sleep(.1)
        raise AcceptanceFailure("INGRESS_RELOAD_TIMEOUT")

    def search(self, business_id, query="Redis"):
        self.calls += 1
        request = Request(self.rag_origin + "/api/rag/harness-search",
                          data=encoded({"requestId": business_id, "query": query, "topK": 3}),
                          headers={"Authorization": "Bearer " + self.metadata["user10Token"],
                                   "Accept": "application/json", "Content-Type": "application/json"})
        try:
            response = self.opener.open(request, timeout=30)
        except HTTPError as error:
            response = error
        except (OSError, URLError):
            raise AcceptanceFailure("BUSINESS_INGRESS_UNAVAILABLE") from None
        with response:
            raw = response.read(2 * 1024 * 1024 + 1)
            require(len(raw) <= 2 * 1024 * 1024, "BUSINESS_RESPONSE_TOO_LARGE")
            try:
                return response.status, json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                raise AcceptanceFailure("BUSINESS_RESPONSE_NOT_JSON") from None

    def execute(self):
        try:
            self.current_step = "catalog_business_agent"
            release = self.catalog_ingress_release()
            self.current_step = "ingress_reload"
            self.switch(release)
            self.current_step = "business_request_completion"
            business_id = str(uuid4())
            status, created = self.search(business_id)
            require(status == 202 and created["data"]["owner"] == "HARNESS", "BUSINESS_RUN_CREATION_REJECTED")
            run_id = created["data"]["run"]["run"]["id"]
            self.report["runs"]["opsIngress"] = run_id
            completed, _ = self.await_status("ops-dev", self.ops_owner, run_id, {"COMPLETED"})
            require(completed["releaseRef"] == release, "BUSINESS_RUN_RELEASE_MISMATCH")
            require(completed["usage"]["toolCalls"] == 1 and completed["usage"]["modelCalls"] == 0,
                    "BUSINESS_RUN_CALL_COUNTS_CHANGED")
            require(completed["output"]["visibility"] == "AVAILABLE", "BUSINESS_OUTPUT_UNAVAILABLE")
            output = completed["output"]["value"]
            require(len(output["citations"]) == 2 and bool(output["evidence"]), "BUSINESS_CITATIONS_MISSING")
            self.record("business_ingress_completes_exact_catalog_release", runId=run_id, releaseRef=release,
                        runStatus=completed["status"], citationCount=2, usage=completed["usage"], outputDigest=digest(output))
            self.current_step = "business_request_deduplication"
            status, repeated = self.search(business_id)
            require(status == 202 and repeated["data"]["run"]["run"]["id"] == run_id, "BUSINESS_REQUEST_RETRY_CHANGED_RUN")
            changed_status, _ = self.search(business_id, "Changed Redis query")
            require(changed_status == 409, "BUSINESS_REQUEST_INPUT_CONFLICT_MISSING")
            self.record("business_request_id_pins_one_run_and_changed_body_conflicts", runId=run_id,
                        repeatedRequestStatus=202, changedBodyStatus=409)
            final_config = read_json(self.args.private_host_config)
            final_meta = read_json(self.args.identity_metadata)
            require(final_config["newOwner"] == final_meta["ingressOwner"] == "HARNESS"
                    and final_config["releaseId"] == release["releaseId"]
                    and final_config["releaseDigest"] == release["digest"], "FINAL_INGRESS_CONFIG_CHANGED")
            self.report.update({"status": "passed", "passed": len(self.report["cases"]), "completedAt": utc()})
            self.save_report()
            print("OPS_CATALOG_INGRESS_PASSED " + str(len(self.report["cases"])), flush=True)
            return 0
        except Exception as error:
            code = str(error) if isinstance(error, AcceptanceFailure) else "UNEXPECTED_ACCEPTANCE_FAILURE"
            self.report.update({"status": "failed", "failure": {"step": self.current_step, "code": code}})
            self.save_report()
            print("OPS_CATALOG_INGRESS_FAILED " + code, flush=True)
            return 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", default="http://127.0.0.1:8099")
    parser.add_argument("--credentials-file", type=Path, required=True)
    parser.add_argument("--identity-metadata", type=Path, required=True)
    parser.add_argument("--deployment-file", type=Path, required=True)
    parser.add_argument("--private-host-config", type=Path, required=True)
    parser.add_argument("--catalog-report", type=Path, required=True)
    parser.add_argument("--platform-jar", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--label")
    args = parser.parse_args()
    try:
        return IngressAcceptance(args).execute()
    except Exception as error:
        code = str(error) if isinstance(error, AcceptanceFailure) else "PRIVATE_CONFIGURATION_INVALID"
        print("OPS_CATALOG_INGRESS_FAILED " + code, flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main())
