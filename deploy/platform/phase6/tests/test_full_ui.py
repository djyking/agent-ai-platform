import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("full_ui_fixtures", ROOT / "tests/test_ai_minimal.py")
fixtures = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixtures)
prepare = fixtures.prepare


def fixture():
    base, spec, digest = fixtures.fixture()
    for name in (*prepare.FULL_UI_ADDITIONS, "operations-lab"):
        base["services"].setdefault(name, {
            "image": f"old/{name}:retained", "restart": "unless-stopped",
            "environment": {}, "networks": {"backplane": {}},
        })
    demo = base["services"]["ops-demo-order-app"]
    demo["environment"]["JAVA_TOOL_OPTIONS"] = "-Xms32m -Xmx192m"
    demo["environment"]["OPS_DEMO_CONTROL_TOKEN"] = "private-demo-token-not-for-review"
    demo["depends_on"] = prepare.dependency_map(("nacos", "demo-redis", "demo-rabbitmq"))
    demo["networks"]["demo-data"] = {}
    demo["networks"]["demo-metrics"] = {}
    for name in ("demo-redis", "demo-rabbitmq"):
        base["services"][name]["networks"] = {"demo-data": {}, "demo-metrics": {}}
    base["services"]["demo-rabbitmq"]["volumes"] = [{
        "type": "volume", "source": "demo-rabbitmq-data", "target": "/var/lib/rabbitmq"}]
    base["services"]["otel-collector"]["depends_on"] = {"tempo": {"condition": "service_started"}}
    base["services"]["grafana"]["depends_on"] = {"prometheus": {"condition": "service_started"}}
    base["networks"].update({name: {"name": "opsagent_" + name} for name in ("demo-data", "demo-metrics")})
    base["volumes"]["demo-rabbitmq-data"] = {"name": "opsagent_demo-rabbitmq-data"}
    spec["format"] = "harness-full-ui-inputs-v1"
    spec["images"]["ops-demo-order-app"] = "sha256:" + "4" * 64
    spec["configurationReview"]["services"]["ops-demo-order-app"] = {
        "sourcesReviewed": True, "launchOptionsReviewed": True,
        "dynamicConfiguration": "managed-demo-nacos", "launchProfile": "direct",
    }
    for name in prepare.FULL_UI_OTEL_WRAPPER_SERVICES:
        fixtures.reviewed_wrapper(base, spec, name)
    return base, spec, digest


class FullUiTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("docker"), "Docker CLI not installed")
    def test_generated_compose_parses_without_daemon_or_env_resolution(self):
        base, spec, digest = fixture()
        result, _ = prepare.render(base, spec, digest, "full-ui")
        # Fixtures provide no process healthchecks; Compose still validates every
        # retained service_healthy dependency against the target service schema.
        targets = {name for service in result["services"].values()
                   for name, dependency in service.get("depends_on", {}).items()
                   if dependency.get("condition") == "service_healthy"}
        for name in targets:
            result["services"][name]["healthcheck"] = {"test": ["CMD", "true"]}
        run = subprocess.run(
            ["docker", "compose", "-f", "-", "config", "--no-env-resolution", "--format", "json"],
            input=json.dumps(result), capture_output=True, text=True, timeout=15,
        )
        self.assertEqual(0, run.returncode, run.stderr)
        parsed = json.loads(run.stdout)
        self.assertEqual(set(result["services"]), set(parsed["services"]))
        self.assertEqual("no", parsed["services"]["ops-demo-order-app"]["restart"])

    def test_preserves_current_page_dependencies_and_isolation(self):
        base, spec, digest = fixture()
        original = copy.deepcopy(base)
        result, review = prepare.render(base, spec, digest, "full-ui")
        self.assertEqual(28, len(result["services"]))
        self.assertEqual(set(prepare.CORE) | {"elasticsearch", "qdrant"}
                         | set(prepare.FULL_UI_ADDITIONS) | set(prepare.NEW), set(result["services"]))
        self.assertNotIn("operations-lab", result["services"])
        self.assertNotIn("reranker", result["services"])
        self.assertEqual(original, base)
        for name, service in result["services"].items():
            self.assertEqual("no", service["restart"])
            self.assertTrue(set(service.get("depends_on", {})).issubset(result["services"]))
            if name != "ops-web-app":
                self.assertFalse(service.get("ports"))
        demo = result["services"]["ops-demo-order-app"]
        self.assertEqual(original["services"]["ops-demo-order-app"]["depends_on"], demo["depends_on"])
        self.assertEqual(original["services"]["ops-demo-order-app"]["networks"], demo["networks"])
        self.assertEqual(spec["images"]["ops-demo-order-app"], demo["image"])
        self.assertEqual(original["services"]["demo-rabbitmq"]["volumes"], result["services"]["demo-rabbitmq"]["volumes"])
        self.assertTrue(result["volumes"]["demo-rabbitmq-data"]["external"])
        self.assertNotIn("private-demo-token-not-for-review", json.dumps(review))

    def test_preserves_traces_and_enables_runtime_page_features(self):
        base, spec, digest = fixture()
        result, review = prepare.render(base, spec, digest, "full-ui")
        self.assertFalse(review["optionalOtelAgentDisabled"])
        self.assertEqual([], review["optionalOtelAgentDisabledServices"])
        for name in prepare.FULL_UI_OTEL_WRAPPER_SERVICES:
            actual = result["services"][name]
            self.assertEqual(base["services"][name]["entrypoint"], actual["entrypoint"])
            self.assertEqual("-javaagent:" + prepare.OTEL_AGENT, actual["environment"]["JDK_JAVA_OPTIONS"])
            for mount in base["services"][name]["volumes"]:
                self.assertIn(mount, actual["volumes"])
        for name in prepare.FULL_UI_JAVA:
            env = result["services"][name]["environment"]
            options = shlex.split(env["JAVA_TOOL_OPTIONS"])
            self.assertIn("-Dspring.cloud.sentinel.enabled=true", options)
            self.assertIn("-Dotel.sdk.disabled=false", options)
            self.assertNotIn("-Dotel.sdk.disabled=true", options)
            self.assertEqual("false", env["OTEL_SDK_DISABLED"])
        ticket = result["services"]["ops-ticket-app"]["environment"]
        platform = result["services"]["ops-platform-app"]["environment"]
        self.assertEqual("true", ticket["OPS_ALERTMANAGER_ENABLED"])
        self.assertIn("-Dops.alertmanager.enabled=true", shlex.split(ticket["JAVA_TOOL_OPTIONS"]))
        self.assertEqual("true", platform["OPS_OPERATIONS_INSPECTION_ENABLED"])
        self.assertIn("-Dops.operations.inspection-enabled=true", shlex.split(platform["JAVA_TOOL_OPTIONS"]))
        self.assertEqual("managed-demo-nacos", review["configurationReview"]["services"]["ops-demo-order-app"]["dynamicConfiguration"])

    def test_rejects_incomplete_full_ui_and_unsafe_launches(self):
        mutations = [
            lambda b, s: b["services"].pop("demo-rabbitmq"),
            lambda b, s: s["images"].pop("ops-demo-order-app"),
            lambda b, s: s["configurationReview"]["services"].pop("ops-demo-order-app"),
            lambda b, s: s["configurationReview"]["services"]["ops-demo-order-app"].update(dynamicConfiguration="disabled"),
            lambda b, s: s["configurationReview"]["services"]["ops-demo-order-app"].update(wrapperSha256="0" * 64),
            lambda b, s: b["services"]["ops-demo-order-app"]["environment"].update(JDK_JAVA_OPTIONS="-javaagent:/unknown.jar"),
            lambda b, s: b["services"]["tempo"].update(ports=[{"target": 3200, "published": "3200"}]),
            lambda b, s: b["services"]["otel-collector"]["depends_on"].update({"unreviewed": {"condition": "service_started"}}),
            lambda b, s: s.update(vectorMode="disable-approved"),
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                base, spec, digest = fixture()
                mutation(base, spec)
                with self.assertRaises(prepare.InvalidPlan):
                    prepare.render(base, spec, digest, "full-ui")

    def test_rejects_missing_trace_instrumentation(self):
        base, spec, digest = fixture()
        spec["configurationReview"]["services"]["ops-agent-app"] = {
            "sourcesReviewed": True, "launchOptionsReviewed": True,
            "dynamicConfiguration": "disabled", "launchProfile": "direct",
        }
        with self.assertRaises(prepare.InvalidPlan):
            prepare.render(base, spec, digest, "full-ui")

    def test_default_minimal_profile_contract_unchanged(self):
        base, spec, digest = fixtures.fixture()
        result, review = prepare.render(base, spec, digest)
        self.assertEqual(18, len(result["services"]))
        self.assertEqual("harness-ai-minimal-review-v2", review["format"])
        self.assertNotIn("profile", review)
        for name in prepare.JAVA:
            self.assertIn("-Dspring.cloud.sentinel.enabled=false", shlex.split(result["services"][name]["environment"]["JAVA_TOOL_OPTIONS"]))
        self.assertEqual("false", result["services"]["ops-ticket-app"]["environment"]["OPS_ALERTMANAGER_ENABLED"])
        full_base, full_spec, full_digest = fixture()
        with self.assertRaises(prepare.InvalidPlan):
            prepare.render(full_base, full_spec, full_digest)

    def test_full_ui_cli_is_explicit_and_refuses_overwrite(self):
        base, spec, _ = fixture()
        raw = json.dumps(base).encode()
        digest = hashlib.sha256(raw).hexdigest()
        spec["baseComposeSha256"] = spec["configurationReview"]["baseComposeSha256"] = digest
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            source = directory / "base.json"
            inputs = directory / "inputs.json"
            destination = directory / "rendered"
            source.write_bytes(raw)
            inputs.write_text(json.dumps(spec))
            command = [sys.executable, str(ROOT / "full-ui.prepare.py"), "--base-compose", str(source),
                       "--inputs", str(inputs), "--output-directory", str(destination)]
            first = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, first.returncode, first.stderr)
            self.assertTrue((destination / "compose.full-ui.private.json").is_file())
            self.assertFalse((destination / "compose.ai-minimal.private.json").exists())
            saved = (destination / "compose.full-ui.private.json").read_bytes()
            second = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(2, second.returncode)
            self.assertEqual(saved, (destination / "compose.full-ui.private.json").read_bytes())
            self.assertNotIn("private-demo-token-not-for-review", first.stdout + first.stderr + second.stdout + second.stderr)


@unittest.skipUnless(os.environ.get("HARNESS_SPRING_BINDING_CLASSPATH"),
                     "Set HARNESS_SPRING_BINDING_CLASSPATH to reviewed existing Spring/Boot/YAML jars")
class FullUiSpringBindingTests(unittest.TestCase):
    def test_page_feature_properties_override_conflicting_mounted_and_environment_values(self):
        base, spec, digest = fixture()
        fixtures.SpringBindingTests().check_rendered_properties(
            base, spec, digest, "full-ui", prepare.FULL_UI_JAVA)


if __name__ == "__main__":
    unittest.main()
