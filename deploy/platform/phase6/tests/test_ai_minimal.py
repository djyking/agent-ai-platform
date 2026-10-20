import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
MODULE_SPEC = importlib.util.spec_from_file_location("ai_minimal_prepare", ROOT / "ai-minimal.prepare.py")
prepare = importlib.util.module_from_spec(MODULE_SPEC)
MODULE_SPEC.loader.exec_module(prepare)


def fixture(vector=True):
    services = {}
    for name in (*prepare.CORE, "elasticsearch", "qdrant", "sentinel", "reranker", "grafana"):
        services[name] = {
            "image": f"old/{name}:retained", "restart": "no", "environment": {},
            "networks": {"backplane": None},
        }
    for name in prepare.JAVA:
        services[name]["depends_on"] = prepare.dependency_map(
            ("mysql", "redis", "rabbitmq", "nacos", "elasticsearch", "qdrant", "sentinel"))
        services[name]["environment"]["JAVA_TOOL_OPTIONS"] = "-Xmx320m"
    services["ops-knowledge-app"]["environment"]["OPS_VECTOR_ENABLED"] = str(vector).lower()
    services["ops-auth-app"]["environment"]["FIXTURE_PRIVATE_VALUE"] = "do-not-print-this-fixture"
    services["ops-rag-app"]["environment"]["OPS_HARNESS_APPLICATION_CREDENTIAL"] = "stale-fixture"
    services["ops-web-app"]["ports"] = [
        {"target": 80, "published": "80", "protocol": "tcp"},
        {"target": 443, "published": "443", "protocol": "tcp"},
    ]
    services["mysql"]["command"] = ["--max-connections=50"]
    services["mysql"]["volumes"] = [{"type": "volume", "source": "mysql-data", "target": "/var/lib/mysql"}]
    services["ops-knowledge-app"]["volumes"] = [
        {"type": "volume", "source": "knowledge-uploads", "target": "/app/data/uploads"}]
    base = {
        "name": "opsagent", "services": services,
        "networks": {"backplane": {"name": "opsagent_backplane"}},
        "volumes": {
            "mysql-data": {"name": "opsagent_mysql-data"},
            "knowledge-uploads": {"name": "opsagent_knowledge-uploads"},
            "unused-data": {"name": "opsagent_unused-data"},
        },
    }
    digest = hashlib.sha256(json.dumps(base).encode()).hexdigest()
    spec = {
        "format": "harness-ai-minimal-inputs-v1", "baseComposeSha256": digest,
        "runtimeDirectory": "/opt/harness/phase6",
        "network": {"name": "harness-runtime", "subnet": "172.30.82.0/24",
                    "edgeAddress": "172.30.82.10", "observedSubnets": ["172.18.0.0/16"]},
        "vectorMode": "preserve", "trustStoreType": "JKS",
        "images": {key: "sha256:" + "1" * 64 for key in prepare.IMAGE_KEYS},
    }
    return base, spec, digest


class SelectionTests(unittest.TestCase):
    def test_preserve_hybrid_without_optional_stack(self):
        base, spec, digest = fixture()
        snapshot = copy.deepcopy(base)
        result, review = prepare.render(base, spec, digest)
        self.assertEqual(18, len(result["services"]))
        self.assertTrue(review["vectorEnabled"])
        self.assertFalse(review["vectorDegraded"])
        self.assertEqual(snapshot, base)
        self.assertNotIn("sentinel", result["services"])
        self.assertNotIn("reranker", result["services"])
        self.assertNotIn("grafana", result["services"])
        for service in result["services"].values():
            self.assertEqual("no", service["restart"])
            self.assertTrue(set(service.get("depends_on", {})).issubset(result["services"]))
        self.assertEqual(spec["images"]["ops-agent-app"], result["services"]["ops-agent-app"]["image"])
        self.assertEqual("old/mysql:retained", result["services"]["mysql"]["image"])
        self.assertEqual({"external": True, "name": "opsagent_mysql-data"},
                         result["volumes"]["mysql-data"])
        self.assertEqual("opsagent_knowledge-uploads", result["volumes"]["knowledge-uploads"]["name"])
        self.assertEqual("opsagent_backplane", result["networks"]["backplane"]["name"])
        self.assertNotIn("do-not-print-this-fixture", json.dumps(review))

    def test_preserve_existing_sql_mode_uses_twelve_ops_services(self):
        base, spec, digest = fixture(False)
        result, review = prepare.render(base, spec, digest)
        self.assertEqual(16, len(result["services"]))
        self.assertEqual(12, review["selectedOpsCount"])
        self.assertNotIn("qdrant", result["services"])
        self.assertNotIn("elasticsearch", result["services"])
        self.assertFalse(review["vectorDegraded"])

    def test_explicit_vector_degradation_is_recorded(self):
        base, spec, digest = fixture()
        spec["vectorMode"] = "disable-approved"
        result, review = prepare.render(base, spec, digest)
        self.assertTrue(review["vectorDegraded"])
        self.assertFalse(review["vectorEnabled"])
        self.assertEqual("false", result["services"]["ops-knowledge-app"]["environment"]["OPS_VECTOR_ENABLED"])

    def test_ambiguous_vector_setting_is_not_guessed(self):
        for bad in (None, "${OPS_VECTOR_ENABLED}", ""):
            with self.subTest(bad=bad):
                base, spec, digest = fixture()
                base["services"]["ops-knowledge-app"]["environment"]["OPS_VECTOR_ENABLED"] = bad
                with self.assertRaises(prepare.InvalidPlan):
                    prepare.render(base, spec, digest)

    def test_hybrid_requires_both_index_services(self):
        base, spec, digest = fixture()
        del base["services"]["qdrant"]
        with self.assertRaises(prepare.InvalidPlan):
            prepare.render(base, spec, digest)

    def test_stale_input_and_unpinned_images_are_rejected(self):
        base, spec, digest = fixture()
        with self.assertRaises(prepare.InvalidPlan):
            prepare.render(base, spec, "0" * 64)
        spec["images"]["support"] = "example/support:latest"
        with self.assertRaises(prepare.InvalidPlan):
            prepare.render(base, spec, digest)

    def test_all_seven_java_and_web_images_require_full_snapshot_mapping(self):
        base, spec, digest = fixture()
        expected = set(prepare.JAVA) | {"ops-web-app"}
        self.assertEqual(expected, set(prepare.REPLACED))
        result, _ = prepare.render(base, spec, digest)
        for name in expected:
            self.assertEqual(spec["images"][name], result["services"][name]["image"])
        for missing in ("ops-agent-app", "ops-platform-app", "ops-gateway-app"):
            with self.subTest(missing=missing):
                incomplete = copy.deepcopy(spec)
                del incomplete["images"][missing]
                with self.assertRaises(prepare.InvalidPlan):
                    prepare.render(base, incomplete, digest)

    def test_only_edge_80_and_443_can_be_published(self):
        base, spec, digest = fixture()
        base["services"]["mysql"]["ports"] = [{"target": 3306, "published": "3306"}]
        with self.assertRaises(prepare.InvalidPlan):
            prepare.render(base, spec, digest)
        base, spec, digest = fixture()
        base["services"]["ops-web-app"]["ports"].append({"target": 8101, "published": "8101"})
        with self.assertRaises(prepare.InvalidPlan):
            prepare.render(base, spec, digest)

    def test_network_inventory_and_nonconflicting_exact_edge_are_required(self):
        for changed in ({"observedSubnets": []}, {"observedSubnets": ["172.30.0.0/16"]},
                        {"edgeAddress": "172.30.83.10"}, {"edgeAddress": "172.30.82.1"}):
            with self.subTest(changed=changed):
                base, spec, digest = fixture()
                spec["network"].update(changed)
                with self.assertRaises(prepare.InvalidPlan):
                    prepare.render(base, spec, digest)

    def test_gateway_cannot_impersonate_the_trusted_edge(self):
        base, spec, digest = fixture()
        result, _ = prepare.render(base, spec, digest)
        services = result["services"]
        self.assertEqual({"harness-runtime"}, set(services["harness-api"]["networks"]))
        self.assertEqual({"ipv4_address": "172.30.82.10"},
                         services["ops-web-app"]["networks"]["harness-runtime"])
        self.assertNotIn("ipv4_address", services["harness-internal-tls"]["networks"]["harness-runtime"])
        self.assertEqual(r"172\.30\.82\.10",
                         services["harness-api"]["environment"]["SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES"])
        self.assertEqual("none", services["harness-worker-1"]["environment"]["SERVER_FORWARD_HEADERS_STRATEGY"])
        for name in prepare.NEW:
            self.assertFalse(services[name].get("ports"))

    def test_tls_mounts_are_leaf_only_and_runtime_jvm_keeps_limits(self):
        base, spec, digest = fixture()
        result, _ = prepare.render(base, spec, digest)
        services = result["services"]
        for service in services.values():
            for mount in service.get("volumes", []):
                self.assertNotIn("/ca/private", mount.get("source", ""))
        rag = services["ops-rag-app"]
        self.assertIn("-Xmx320m", rag["environment"]["JAVA_TOOL_OPTIONS"])
        self.assertIn("/run/harness-trust/cacerts", rag["environment"]["JAVA_TOOL_OPTIONS"])
        self.assertEqual("--max-connections=50", services["mysql"]["command"][0])
        self.assertIn("--ssl-key=/run/harness-mysql-tls/server-key.pem", services["mysql"]["command"])

    def test_rag_stays_legacy_and_private_env_owns_new_credentials(self):
        base, spec, digest = fixture()
        result, _ = prepare.render(base, spec, digest)
        rag = result["services"]["ops-rag-app"]
        self.assertNotIn("OPS_HARNESS_APPLICATION_CREDENTIAL", rag["environment"])
        self.assertEqual("LEGACY", rag["environment"]["OPS_HARNESS_ANSWER_NEW_OWNER"])
        self.assertEqual("raw", rag["env_file"][-1]["format"])
        self.assertEqual("false", rag["environment"]["OPS_RERANK_ENABLED"])
        self.assertEqual("https://opsagent.cloud", result["services"]["support"]["environment"]["SUPPORT_PUBLIC_ORIGIN"])

    def test_existing_local_profile_truststore_or_mysql_tls_needs_review(self):
        for kind in ("profile", "trust", "mysql"):
            with self.subTest(kind=kind):
                base, spec, digest = fixture()
                if kind == "profile":
                    base["services"]["ops-rag-app"]["environment"]["SPRING_PROFILES_INCLUDE"] = "harness-local"
                elif kind == "trust":
                    base["services"]["ops-rag-app"]["environment"]["JAVA_TOOL_OPTIONS"] = "-Djavax.net.ssl.trustStore=/old"
                else:
                    base["services"]["mysql"]["command"] += ["--ssl-cert=/old"]
                with self.assertRaises(prepare.InvalidPlan):
                    prepare.render(base, spec, digest)

    def test_cli_is_private_non_overwriting_and_does_not_echo_config(self):
        with tempfile.TemporaryDirectory() as name:
            temporary = Path(name)
            base, spec, _ = fixture()
            raw = json.dumps(base).encode()
            spec["baseComposeSha256"] = hashlib.sha256(raw).hexdigest()
            (temporary / "base.json").write_bytes(raw)
            (temporary / "inputs.json").write_text(json.dumps(spec), encoding="utf-8")
            command = [sys.executable, str(ROOT / "ai-minimal.prepare.py"),
                       "--base-compose", str(temporary / "base.json"),
                       "--inputs", str(temporary / "inputs.json"),
                       "--output-directory", str(temporary / "candidate")]
            run = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertEqual(0, run.returncode, run.stderr)
            self.assertNotIn("do-not-print", run.stdout + run.stderr)
            path = temporary / "candidate" / "compose.ai-minimal.private.json"
            before = path.read_bytes()
            run = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertEqual(2, run.returncode)
            self.assertEqual(before, path.read_bytes())
            self.assertNotIn("do-not-print", run.stdout + run.stderr)


class ProxyContractTests(unittest.TestCase):
    def test_public_paths_preserve_ops_and_isolate_support(self):
        text = (ROOT / "ai-minimal.nginx.locations.conf").read_text()
        self.assertNotIn("location / {", text)
        self.assertNotIn("location /api/", text)
        self.assertIn("location = /support/health { return 404; }", text)
        self.assertIn("rewrite ^/support/(.*)$ /$1 break;", text)
        self.assertIn("proxy_cookie_path / /support/;", text)
        self.assertIn("return 308 /support/$is_args$args;", text)
        self.assertEqual(3, text.count("proxy_next_upstream off;"))
        for expected in ('proxy_set_header Forwarded "";', "proxy_set_header X-Forwarded-Proto https;",
                         "proxy_set_header X-Forwarded-Port 443;", "proxy_set_header Host opsagent.cloud;"):
            self.assertEqual(3, text.count(expected))

    def test_internal_gateway_rejects_unknown_hosts_and_restricts_paths(self):
        text = (ROOT / "internal-tls.nginx.conf").read_text()
        self.assertIn("ssl_reject_handshake on;", text)
        self.assertEqual(3, text.count("location / { return 404; }"))
        self.assertEqual(3, text.count("if ($ssl_server_name !="))
        self.assertEqual(3, text.count("if ($host !="))
        self.assertIn("/(?:token|output-token)$", text)
        self.assertIn("location = /internal/rag/output/validate-citations", text)
        self.assertIn("location = /internal/rag/harness/output-answer-access", text)
        self.assertIn("location ^~ /v1/projects/", text)
        self.assertIn("if ($request_method !~ ^(?:GET|POST)$)", text)
        self.assertNotIn("location /internal/", text)
        self.assertNotIn("location /api/", text)
        self.assertIn('proxy_set_header X-Forwarded-Proto "";', text)
        self.assertIn("proxy_next_upstream off;", text)
        for handler in ("client_body", "proxy", "fastcgi", "uwsgi", "scgi"):
            self.assertIn(handler + "_temp_path /tmp/", text)

    @unittest.skipUnless(shutil.which("docker"), "Docker CLI not installed")
    def test_generated_compose_parses_without_daemon_or_env_resolution(self):
        base, spec, digest = fixture()
        result, _ = prepare.render(base, spec, digest)
        # Compose validates conditional dependencies only when healthchecks exist.
        for name in ("mysql", "redis", "rabbitmq", "nacos", "elasticsearch", "qdrant", "ops-gateway-app"):
            result["services"][name]["healthcheck"] = {"test": ["CMD", "true"]}
        run = subprocess.run(
            ["docker", "compose", "-f", "-", "config", "--no-env-resolution", "--format", "json"],
            input=json.dumps(result), capture_output=True, text=True, timeout=15,
        )
        self.assertEqual(0, run.returncode, run.stderr)
        parsed = json.loads(run.stdout)
        self.assertEqual(set(result["services"]), set(parsed["services"]))
        self.assertEqual("no", parsed["services"]["mysql"]["restart"])


if __name__ == "__main__":
    unittest.main()
