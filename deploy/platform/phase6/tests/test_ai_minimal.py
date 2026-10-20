import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import shlex
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
        "format": "harness-ai-minimal-inputs-v2", "baseComposeSha256": digest,
        "runtimeDirectory": "/opt/harness/phase6",
        "network": {"name": "harness-runtime", "subnet": "172.30.82.0/24",
                    "edgeAddress": "172.30.82.10", "observedSubnets": ["172.18.0.0/16"]},
        "vectorMode": "preserve", "trustStoreType": "JKS",
        "images": {key: "sha256:" + "1" * 64 for key in prepare.IMAGE_KEYS},
        "configurationReview": {
            "baseComposeSha256": digest, "evidenceSha256": "2" * 64,
            "effectiveVectorEnabled": vector,
            "services": {name: {
                "sourcesReviewed": True, "launchOptionsReviewed": True,
                "dynamicConfiguration": "disabled", "launchProfile": "direct",
            } for name in prepare.JAVA},
        },
    }
    return base, spec, digest


def reviewed_wrapper(base, spec, name):
    service = base["services"][name]
    service["entrypoint"] = ["/bin/sh", prepare.OTEL_WRAPPER]
    service["environment"]["JDK_JAVA_OPTIONS"] = "-javaagent:" + prepare.OTEL_AGENT
    service.setdefault("volumes", []).extend([
        {"type": "bind", "source": "/opt/opsagent/deploy/public/config/otel/java-entrypoint.sh",
         "target": prepare.OTEL_WRAPPER, "read_only": True},
        {"type": "bind", "source": "/opt/opsagent/deploy/public/config/otel/opentelemetry-javaagent.jar",
         "target": prepare.OTEL_AGENT, "read_only": True},
    ])
    spec["configurationReview"]["services"][name].update(
        launchProfile="reviewed-otel-wrapper", wrapperSha256=prepare.OTEL_WRAPPER_SHA256,
        launchEvidenceSha256="3" * 64)


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
        for bad in (None, "${OPS_VECTOR_ENABLED}", "", "true", 1):
            with self.subTest(bad=bad):
                base, spec, digest = fixture()
                spec["configurationReview"]["effectiveVectorEnabled"] = bad
                with self.assertRaises(prepare.InvalidPlan):
                    prepare.render(base, spec, digest)

    def test_vector_selection_uses_reviewed_effective_value_not_alias(self):
        for vector in (True, False):
            with self.subTest(vector=vector):
                base, spec, digest = fixture(vector)
                base["services"]["ops-knowledge-app"]["environment"]["OPS_VECTOR_ENABLED"] = str(not vector).lower()
                result, review = prepare.render(base, spec, digest)
                self.assertEqual(vector, "elasticsearch" in result["services"])
                self.assertEqual(vector, review["oldVectorEnabled"])
                self.assertIn("-Dops.knowledge.vector.enabled=" + str(vector).lower(),
                              result["services"]["ops-knowledge-app"]["environment"]["JAVA_TOOL_OPTIONS"])

    def test_configuration_evidence_and_each_service_review_are_required(self):
        mutations = (
            lambda value: value.update(baseComposeSha256="0" * 64),
            lambda value: value.update(evidenceSha256="unverified"),
            lambda value: value["services"].pop("ops-rag-app"),
            lambda value: value["services"]["ops-rag-app"].update(sourcesReviewed=False),
            lambda value: value["services"]["ops-rag-app"].update(launchOptionsReviewed=False),
            lambda value: value["services"]["ops-rag-app"].update(dynamicConfiguration="enabled"),
            lambda value: value["services"]["ops-rag-app"].update(dynamicConfiguration="unreviewed"),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                base, spec, digest = fixture()
                mutate(spec["configurationReview"])
                with self.assertRaises(prepare.InvalidPlan):
                    prepare.render(base, spec, digest)
        base, spec, digest = fixture()
        del spec["configurationReview"]
        with self.assertRaises(prepare.InvalidPlan):
            prepare.render(base, spec, digest)

    def test_exact_fixed_properties_keep_memory_options_and_secret_env_ownership(self):
        base, spec, digest = fixture()
        original = "-XX:ActiveProcessorCount=2 -Xms64m -Xmx320m -Dfile.encoding=UTF-8"
        for name in prepare.JAVA:
            base["services"][name]["environment"]["JAVA_TOOL_OPTIONS"] = original
        result, review = prepare.render(base, spec, digest)
        self.assertEqual("REVIEWED_DECLARATIONS_NOT_AUTOMATIC_TARGET_INSPECTION",
                         review["configurationReviewNature"])
        self.assertEqual(spec["configurationReview"], review["configurationReview"])
        for name in prepare.JAVA:
            options = result["services"][name]["environment"]["JAVA_TOOL_OPTIONS"]
            self.assertTrue(options.startswith(original + " "))
            for key, value in review["fixedJvmProperties"][name].items():
                self.assertIn(f"-D{key}={value}", shlex.split(options))
            self.assertNotIn("application-credential", options)
            self.assertNotIn("stale-fixture", options)
        self.assertEqual("LEGACY", review["fixedJvmProperties"]["ops-rag-app"]["ops.harness.answer.new-owner"])
        self.assertEqual("false", review["fixedJvmProperties"]["ops-rag-app"]["ops.rag.rerank-enabled"])
        self.assertEqual("false", review["fixedJvmProperties"]["ops-auth-app"]["spring.cloud.sentinel.enabled"])

    def test_high_priority_launch_channels_and_unresolved_env_files_are_rejected(self):
        for key in ("SPRING_APPLICATION_JSON", "spring.application.json", "JDK_JAVA_OPTIONS",
                    "_JAVA_OPTIONS", "JAVA_OPTS", "JAVA_OPTIONS", "JAVA_ARGS"):
            with self.subTest(key=key):
                base, spec, digest = fixture()
                base["services"]["ops-rag-app"]["environment"][key] = "SYNTHETIC_PRIVATE"
                with self.assertRaises(prepare.InvalidPlan) as failure:
                    prepare.render(base, spec, digest)
                self.assertNotIn("SYNTHETIC_PRIVATE", str(failure.exception))
        for key, value in (
            ("command", ["--ops.rag.rerank-enabled=true"]),
            ("command", "sh -c 'java -jar /app.jar'"),
            ("entrypoint", ["java", "-Dops.harness.answer.new-owner=HARNESS"]),
            ("env_file", [{"path": "/private/unresolved.env"}]),
        ):
            with self.subTest(key=key, value=value):
                base, spec, digest = fixture()
                base["services"]["ops-rag-app"][key] = value
                with self.assertRaises(prepare.InvalidPlan):
                    prepare.render(base, spec, digest)

    def test_conflicting_or_duplicate_jvm_properties_and_argfiles_are_rejected(self):
        for options in (
            "-Xmx320m -Dops.rag.rerank-enabled=true",
            "-DOPS_HARNESS_ANSWER_NEWOWNER=HARNESS",
            "-Dspring.application.json={}",
            "-Dops.harness.application-credential=SYNTHETIC_PRIVATE",
            "-Duser.timezone=UTC -Duser.timezone=UTC",
            "-Dcustom.key=1 -Dcustom-key=2",
            "@unreviewed-options.txt", "--ops.rag.rerank-enabled=true",
            '-Xmx320m "-Dunterminated',
        ):
            with self.subTest(options=options):
                base, spec, digest = fixture()
                base["services"]["ops-rag-app"]["environment"]["JAVA_TOOL_OPTIONS"] = options
                with self.assertRaises(prepare.InvalidPlan) as failure:
                    prepare.render(base, spec, digest)
                self.assertNotIn("SYNTHETIC_PRIVATE", str(failure.exception))

    def test_reviewed_wrapper_keeps_identity_and_mounts_but_disables_only_known_otel_agent(self):
        base, spec, digest = fixture()
        for name in prepare.OTEL_WRAPPER_SERVICES:
            reviewed_wrapper(base, spec, name)
        snapshot = copy.deepcopy(base)
        result, review = prepare.render(base, spec, digest)
        self.assertEqual(snapshot, base)
        self.assertTrue(review["optionalOtelAgentDisabled"])
        self.assertEqual(sorted(prepare.OTEL_WRAPPER_SERVICES), review["optionalOtelAgentDisabledServices"])
        for name in prepare.OTEL_WRAPPER_SERVICES:
            self.assertEqual(base["services"][name]["entrypoint"], result["services"][name]["entrypoint"])
            self.assertEqual("", result["services"][name]["environment"]["JDK_JAVA_OPTIONS"])
            self.assertEqual("true", result["services"][name]["environment"]["OTEL_SDK_DISABLED"])
            for original in base["services"][name]["volumes"]:
                self.assertIn(original, result["services"][name]["volumes"])

    def test_wrapper_exception_rejects_unknown_sources_options_and_unapproved_services(self):
        changes = (
            lambda service, review: review.update(wrapperSha256="0" * 64),
            lambda service, review: review.update(launchEvidenceSha256="UNREVIEWED"),
            lambda service, review: service.update(entrypoint=["/bin/sh", "/unknown.sh"]),
            lambda service, review: service.update(command=["--spring.application.json={}"]),
            lambda service, review: service["environment"].update(
                JDK_JAVA_OPTIONS="-javaagent:" + prepare.OTEL_AGENT + " -Dops.rag.rerank-enabled=true"),
            lambda service, review: service["environment"].update(
                JDK_JAVA_OPTIONS="-javaagent:" + prepare.OTEL_AGENT + " -javaagent:/another.jar"),
            lambda service, review: service["environment"].update(
                JDK_JAVA_OPTIONS="-javaagent:/unknown.jar"),
            lambda service, review: service["environment"].update(JAVA_OPTS="-Xmx128m"),
            lambda service, review: service["environment"].update(
                JAVA_TOOL_OPTIONS="-Xmx320m -javaagent:" + prepare.OTEL_AGENT),
            lambda service, review: service["environment"].update(
                JAVA_TOOL_OPTIONS="-Xmx320m -agentpath:/unknown.so"),
            lambda service, review: service["environment"].update(
                JAVA_TOOL_OPTIONS="-Xmx320m -agentlib:unknown"),
            lambda service, review: service["volumes"].pop(),
            lambda service, review: service["volumes"][-1].update(read_only=False),
            lambda service, review: service["volumes"][-2].update(source="relative-wrapper.sh"),
            lambda service, review: service["volumes"][-1].update(source="/opt/../unreviewed.jar"),
        )
        for index, change in enumerate(changes):
            with self.subTest(index=index):
                base, spec, digest = fixture()
                reviewed_wrapper(base, spec, "ops-rag-app")
                change(base["services"]["ops-rag-app"], spec["configurationReview"]["services"]["ops-rag-app"])
                with self.assertRaises(prepare.InvalidPlan):
                    prepare.render(base, spec, digest)
        base, spec, digest = fixture()
        reviewed_wrapper(base, spec, "ops-auth-app")
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
        base["services"]["ops-rag-app"]["environment"].update({
            "OPS_HARNESS_APPLICATIONCREDENTIAL": "stale-alternate",
            "ops.harness.application-credential": "stale-direct",
        })
        result, _ = prepare.render(base, spec, digest)
        rag = result["services"]["ops-rag-app"]
        self.assertNotIn("OPS_HARNESS_APPLICATION_CREDENTIAL", rag["environment"])
        self.assertNotIn("OPS_HARNESS_APPLICATIONCREDENTIAL", rag["environment"])
        self.assertNotIn("ops.harness.application-credential", rag["environment"])
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
            spec["configurationReview"]["baseComposeSha256"] = spec["baseComposeSha256"]
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


@unittest.skipUnless(os.environ.get("HARNESS_SPRING_BINDING_CLASSPATH"),
                     "Set HARNESS_SPRING_BINDING_CLASSPATH to reviewed existing Spring/Boot/YAML jars")
class SpringBindingTests(unittest.TestCase):
    def test_generated_jvm_properties_override_mounted_yaml_and_conflicting_env_spellings(self):
        java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) \
            if os.environ.get("JAVA_HOME") else shutil.which("java")
        self.assertTrue(java, "Java 17+ is required for the explicit offline Spring binding check")
        source = """
import java.nio.file.*;
import java.util.*;
import org.springframework.core.env.*;
import org.springframework.core.io.*;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.bind.Binder;
class FixedConfigurationCheck {
    public static void main(String[] args) throws Exception {
        var expected = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[0]))) { expected.load(reader); }
        var variables = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[1]))) { variables.load(reader); }
        var syntheticEnv = new HashMap<String,Object>();
        for (String key : variables.stringPropertyNames()) syntheticEnv.put(key, variables.getProperty(key));
        for (boolean originAware : new boolean[]{false, true}) {
            var environment = new StandardEnvironment();
            PropertySource<?> envSource = new SystemEnvironmentPropertySource("systemEnvironment", syntheticEnv);
            if (originAware) {
                var type = Class.forName("org.springframework.boot.env.SystemEnvironmentPropertySourceEnvironmentPostProcessor$OriginAwareSystemEnvironmentPropertySource");
                var ctor = type.getDeclaredConstructor(String.class, Map.class, String.class);
                ctor.setAccessible(true);
                envSource = (PropertySource<?>) ctor.newInstance("systemEnvironment", syntheticEnv, null);
            }
            environment.getPropertySources().replace("systemEnvironment", envSource);
            for (var value : new YamlPropertySourceLoader().load("mounted-managed", new FileSystemResource(args[2])))
                environment.getPropertySources().addLast(value);
            ConfigurationPropertySources.attach(environment);
            for (String key : expected.stringPropertyNames()) {
                String wanted = expected.getProperty(key);
                if (!wanted.equals(environment.getProperty(key))
                    || !wanted.equals(Binder.get(environment).bind(key, String.class).orElse("MISSING"))
                    || !wanted.equals(environment.resolveRequiredPlaceholders("${" + key + "}")))
                    throw new AssertionError("FIXED_PROPERTY_BINDING_FAILED:" + key);
            }
        }
        System.out.println("FIXED_PROPERTY_BINDING_PASSED");
    }
}
"""
        base, spec, digest = fixture(False)
        result, review = prepare.render(base, spec, digest)
        with tempfile.TemporaryDirectory() as name:
            temporary = Path(name)
            java_file = temporary / "FixedConfigurationCheck.java"
            java_file.write_text(source, encoding="utf-8")
            for service in prepare.JAVA:
                with self.subTest(service=service):
                    expected = dict(review["fixedJvmProperties"][service])
                    variables = {key: value for key, value in result["services"][service]["environment"].items()
                                 if key not in ("JAVA_TOOL_OPTIONS", "FIXTURE_PRIVATE_VALUE")}
                    if service == "ops-rag-app":
                        variables.update(OPS_HARNESS_ANSWER_NEWOWNER="HARNESS",
                                         OPS_RAG_SCHEMAAUTOINITIALIZE="true",
                                         OPS_HARNESS_APPLICATION_CREDENTIAL="synthetic-private-credential",
                                         OPS_HARNESS_APPLICATION_ID="synthetic-application",
                                         OPS_HARNESS_ANSWER_RELEASE_ID="synthetic-release",
                                         OPS_HARNESS_ANSWER_RELEASE_DIGEST="synthetic-digest")
                        expected.update({
                            "ops.harness.application-credential": "synthetic-private-credential",
                            "ops.harness.application-id": "synthetic-application",
                            "ops.harness.answer.release-id": "synthetic-release",
                            "ops.harness.answer.release-digest": "synthetic-digest",
                        })
                    managed = {key: ("false" if value == "true" else "true" if value == "false"
                                     else "HARNESS" if value == "LEGACY" else "SYNTHETIC_STALE")
                               for key, value in expected.items()}
                    expected_file, env_file, managed_file = [temporary / suffix for suffix in
                                                            ("expected.properties", "env.properties", "managed.yml")]
                    expected_file.write_text("".join(f"{key}={value}\n" for key, value in expected.items()),
                                             encoding="utf-8")
                    env_file.write_text("".join(f"{key}={value}\n" for key, value in variables.items()), encoding="utf-8")
                    managed_file.write_text("".join(f"{key}: {json.dumps(value)}\n"
                                                    for key, value in managed.items()), encoding="utf-8")
                    options = shlex.split(result["services"][service]["environment"]["JAVA_TOOL_OPTIONS"])
                    # Retain the exact generated -D tokens, including the blank dashboard.
                    process = subprocess.run(
                        [java, "--class-path", os.environ["HARNESS_SPRING_BINDING_CLASSPATH"],
                         *options, str(java_file), str(expected_file), str(env_file), str(managed_file)],
                        capture_output=True, text=True, timeout=30,
                        env={key: value for key, value in os.environ.items()
                             if key not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")},
                    )
                    self.assertEqual(0, process.returncode, process.stderr)
                    self.assertEqual("FIXED_PROPERTY_BINDING_PASSED", process.stdout.strip())


if __name__ == "__main__":
    unittest.main()
