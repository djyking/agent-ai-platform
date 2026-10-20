#!/usr/bin/env python3
"""Render a private, selected Compose document; never contact Docker or a server."""
from __future__ import annotations

import argparse
import copy
import hashlib
import ipaddress
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys

CORE = (
    "mysql", "redis", "rabbitmq", "nacos",
    "ops-auth-app", "ops-ticket-app", "ops-knowledge-app", "ops-rag-app",
    "ops-agent-app", "ops-platform-app", "ops-gateway-app", "ops-web-app",
)
JAVA = tuple(name for name in CORE if name.startswith("ops-") and name != "ops-web-app")
# A matching service source is insufficient: shared security classes can differ.
# All seven Java apps and the web app must come from the accepted full snapshot.
REPLACED = JAVA + ("ops-web-app",)
NEW = ("harness-api", "harness-worker-1", "support", "harness-internal-tls")
IMAGE_KEYS = set(REPLACED) | {"platform", "support", "internalTls"}
REQUIRED_INPUTS = {
    "format", "baseComposeSha256", "runtimeDirectory", "network",
    "vectorMode", "images", "trustStoreType",
}
PINNED_IMAGE = re.compile(r"(?:sha256:[a-f0-9]{64}|[A-Za-z0-9./:_-]+@sha256:[a-f0-9]{64})")
TRUST_MOUNT = "/run/harness-trust"


class InvalidPlan(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise InvalidPlan(message)


def mapping(value, label: str) -> dict:
    require(isinstance(value, dict), f"{label} must be an object")
    return value


def environment(service: dict, label: str) -> dict:
    return mapping(service.setdefault("environment", {}), f"{label}.environment")


def networks(service: dict) -> dict:
    value = service.get("networks", {})
    if isinstance(value, list):
        value = {name: {} for name in value}
    require(isinstance(value, dict), "Resolved service networks must be an object or array")
    service["networks"] = {name: settings or {} for name, settings in value.items()}
    return service["networks"]


def bind(source: str, target: str) -> dict:
    return {
        "type": "bind", "source": source, "target": target, "read_only": True,
        "bind": {"create_host_path": False},
    }


def add_bind(service: dict, source: str, target: str) -> None:
    existing = service.setdefault("volumes", [])
    require(isinstance(existing, list), "Resolved volumes must be an array")
    require(all(isinstance(v, dict) for v in existing), "Use normalized Compose JSON for volumes")
    require(not any(v.get("target") == target for v in existing), "Mount target already exists")
    existing.append(bind(source, target))


def append_trust(options: str, store_type: str) -> str:
    require(isinstance(options, str), "JAVA_TOOL_OPTIONS must be a string")
    require("javax.net.ssl.trustStore" not in options, "Existing truststore requires explicit review")
    return (
        options.strip()
        + f" -Djavax.net.ssl.trustStore={TRUST_MOUNT}/cacerts"
        + " -Djavax.net.ssl.trustStorePassword=changeit"
        + f" -Djavax.net.ssl.trustStoreType={store_type}"
    ).strip()


def dependency_map(names) -> dict:
    return {name: {"condition": "service_healthy"} for name in names}


def private_env(path: str) -> dict:
    # Compose >= 2.30: raw format prevents '$', quotes and backslashes in secrets
    # from undergoing Compose interpolation. No file contents are read or printed.
    return {"path": path, "required": True, "format": "raw"}


def render(base: dict, spec: dict, base_digest: str) -> tuple[dict, dict]:
    mapping(base, "base Compose")
    mapping(spec, "inputs")
    require(set(spec) == REQUIRED_INPUTS, "Unexpected or missing input keys")
    require(spec["format"] == "harness-ai-minimal-inputs-v1", "Unsupported input format")
    require(spec["baseComposeSha256"] == base_digest, "Base Compose digest mismatch")
    require(base.get("name") == "opsagent", "Target Compose project must remain opsagent")
    services = mapping(base.get("services"), "services")
    require(all(name in services for name in CORE), "A required Ops service is absent")
    require(not any(name in services for name in NEW), "New services already exist; review upgrade separately")
    images = mapping(spec["images"], "images")
    require(set(images) == IMAGE_KEYS, "Exactly the reviewed image mapping is required")
    require(all(isinstance(v, str) and PINNED_IMAGE.fullmatch(v) for v in images.values()),
            "Use immutable image digests or exact local sha256 image IDs")
    directory = spec["runtimeDirectory"]
    require(isinstance(directory, str) and re.fullmatch(r"/[A-Za-z0-9/_-]+", directory or ""),
            "Runtime directory must be a simple absolute Linux path")
    require(".." not in PurePosixPath(directory).parts and directory not in ("/", "/opt", "/etc"),
            "Runtime directory is too broad")
    directory = directory.rstrip("/")
    store_type = spec["trustStoreType"]
    require(store_type in ("JKS", "PKCS12"), "Explicit JDK truststore type is required")
    net = mapping(spec["network"], "network")
    require(set(net) == {"name", "subnet", "edgeAddress", "observedSubnets"},
            "Network inputs must include an observed subnet inventory")
    require(isinstance(net["name"], str) and re.fullmatch(r"harness-[a-z0-9-]{1,40}", net["name"]),
            "Use a dedicated harness network name")
    try:
        subnet = ipaddress.ip_network(net["subnet"], strict=True)
        edge = ipaddress.ip_address(net["edgeAddress"])
        require(subnet.version == 4 and 24 <= subnet.prefixlen <= 27,
                "Use an explicit IPv4 /24 to /27 runtime network")
        require(edge in subnet and int(subnet.network_address) + 2 < int(edge) < int(subnet.broadcast_address),
                "Edge address is outside the usable runtime range")
        require(isinstance(net["observedSubnets"], list) and net["observedSubnets"],
                "Observed target Docker subnets are required")
        for value in net["observedSubnets"]:
            other = ipaddress.ip_network(value, strict=True)
            require(not subnet.overlaps(other), "Runtime subnet overlaps an observed target network")
    except (ValueError, TypeError) as exc:
        if isinstance(exc, InvalidPlan):
            raise
        raise InvalidPlan("Invalid network input") from None

    old_vector = environment(copy.deepcopy(services["ops-knowledge-app"]), "knowledge").get("OPS_VECTOR_ENABLED")
    require(str(old_vector).lower() in ("true", "false"), "Target vector mode must be explicitly resolved")
    old_vector = str(old_vector).lower() == "true"
    require(spec["vectorMode"] in ("preserve", "disable-approved"), "Unknown vector mode")
    vector = old_vector and spec["vectorMode"] == "preserve"
    selected = list(CORE) + (["elasticsearch", "qdrant"] if vector else [])
    require(all(name in services for name in selected), "Enabled vector mode needs both ES and Qdrant")

    result = {"name": "opsagent", "services": {}}
    for name in selected:
        service = copy.deepcopy(mapping(services[name], name))
        require(not service.get("privileged"), "Privileged selected service requires separate review")
        require(service.get("network_mode") not in ("host", "service:ops-web-app"),
                "Host/shared network mode is outside this topology")
        require(not service.get("build"), "Target deployment must use prebuilt images")
        if name != "ops-web-app":
            require(not service.get("ports"), "Only the existing edge may publish host ports")
        else:
            ports = service.get("ports", [])
            require(isinstance(ports, list) and len(ports) == 2
                    and all(isinstance(p, dict) and p.get("protocol", "tcp") == "tcp"
                            and str(p.get("target")) == str(p.get("published"))
                            for p in ports)
                    and {str(p.get("published")) for p in ports} == {"80", "443"},
                    "The existing edge must publish only TCP 80 and 443")
        service["restart"] = "no"
        service.pop("profiles", None)
        service["image"] = images.get(name, service.get("image"))
        require(bool(service["image"]), "Selected service image missing")
        service["pull_policy"] = "never"
        for mount in service.get("volumes", []):
            require(isinstance(mount, dict), "Use normalized Compose JSON, not unresolved YAML")
            if mount.get("type") == "bind":
                require(str(mount.get("source", "")).startswith("/"), "Existing bind source must be target-absolute")
        if name in JAVA:
            service["depends_on"] = dependency_map(("mysql", "redis", "rabbitmq", "nacos"))
            env = environment(service, name)
            require("harness-local" not in str(env.get("SPRING_PROFILES_ACTIVE", ""))
                    and "harness-local" not in str(env.get("SPRING_PROFILES_INCLUDE", "")),
                    "harness-local cannot be deployed")
            env.update(OTEL_SDK_DISABLED="true", SENTINEL_EAGER="false", SENTINEL_DASHBOARD="")
        elif name != "ops-web-app":
            service.pop("depends_on", None)
        result["services"][name] = service

    out = result["services"]
    for name in ("ops-knowledge-app", "ops-rag-app"):
        if vector:
            out[name]["depends_on"].update(dependency_map(("elasticsearch", "qdrant")))
    environment(out["ops-knowledge-app"], "knowledge")["OPS_VECTOR_ENABLED"] = str(vector).lower()
    environment(out["ops-knowledge-app"], "knowledge")["OPS_TICKET_URL"] = "http://ops-ticket-app:8102"
    environment(out["ops-ticket-app"], "ticket")["OPS_ALERTMANAGER_ENABLED"] = "false"
    environment(out["ops-platform-app"], "platform")["OPS_OPERATIONS_INSPECTION_ENABLED"] = "false"
    environment(out["ops-auth-app"], "auth")["OPS_HARNESS_IDENTITY_ENABLED"] = "true"
    rag_env = environment(out["ops-rag-app"], "rag")
    # Private env_file must own these values rather than stale base environment.
    for key in tuple(rag_env):
        if key.startswith("OPS_HARNESS_"):
            del rag_env[key]
    out["ops-rag-app"].setdefault("env_file", []).append(private_env(directory + "/private/ops-rag.env"))
    rag_env.update({
        "OPS_HARNESS_AUTH_ORIGIN": "https://harness-identity.internal:8443",
        "OPS_HARNESS_PLATFORM_ORIGIN": "https://harness-platform.internal:8443",
        "OPS_HARNESS_ANSWER_ENABLED": "true",
        "OPS_HARNESS_ANSWER_NEW_OWNER": "LEGACY",
        "OPS_RAG_SCHEMA_AUTO_INITIALIZE": "false",
        "OPS_KNOWLEDGE_INTERNAL_URL": "http://ops-knowledge-app:8103",
        "OPS_AUTH_INTERNAL_URL": "http://ops-auth-app:8101",
        "OPS_RERANK_ENABLED": "false",
    })
    rag_env["JAVA_TOOL_OPTIONS"] = append_trust(rag_env.get("JAVA_TOOL_OPTIONS", ""), store_type)
    add_bind(out["ops-rag-app"], directory + "/private/trust", TRUST_MOUNT)

    runtime_key = "harness-runtime"
    require(runtime_key not in base.get("networks", {}), "Runtime network already exists in base")
    edge_networks = networks(out["ops-web-app"])
    edge_networks[runtime_key] = {"ipv4_address": str(edge)}
    mysql_networks = networks(out["mysql"])
    mysql_networks[runtime_key] = {"aliases": ["harness-mysql"]}
    add_bind(out["mysql"], directory + "/private/mysql-tls", "/run/harness-mysql-tls")
    mysql_command = out["mysql"].get("command", [])
    require(isinstance(mysql_command, list), "MySQL command must be normalized to an array")
    require(not any(str(x).startswith("--ssl") for x in mysql_command),
            "Existing explicit MySQL TLS requires separate preservation review")
    out["mysql"]["command"] = mysql_command + [
        "--ssl-ca=/run/harness-mysql-tls/ca.pem",
        "--ssl-cert=/run/harness-mysql-tls/server-cert.pem",
        "--ssl-key=/run/harness-mysql-tls/server-key.pem",
    ]
    add_bind(out["ops-web-app"], directory + "/nginx/ai-minimal.locations.conf",
             "/etc/nginx/harness-ai-minimal.locations.conf")
    out["ops-web-app"]["depends_on"] = dependency_map(("ops-gateway-app",))

    trust = bind(directory + "/private/trust", TRUST_MOUNT)
    config = bind(directory + "/private/config", "/run/harness")
    common = {
        "read_only": True, "user": "10001:10001",
        "tmpfs": ["/tmp:size=64m,mode=1777"],
        "security_opt": ["no-new-privileges:true"], "cap_drop": ["ALL"],
        "restart": "no", "pull_policy": "never", "stop_grace_period": "120s",
        "networks": {runtime_key: {}},
        "logging": {"driver": "json-file", "options": {"max-size": "10m", "max-file": "3"}},
    }
    for name, heap, cap, worker in (("harness-api", 192, 384, False), ("harness-worker-1", 256, 512, True)):
        service = copy.deepcopy(common)
        service.update(image=images["platform"], mem_limit=f"{cap}m", cpus=1.5)
        service["env_file"] = [private_env(directory + "/private/runtime.env")]
        service["volumes"] = [copy.deepcopy(trust), copy.deepcopy(config)]
        service["depends_on"] = {"mysql": {"condition": "service_healthy"},
                                "harness-internal-tls": {"condition": "service_started"}}
        service["environment"] = {
            "HARNESS_ENVIRONMENT": "production", "HARNESS_SCHEMA_MODE": "validate",
            "HARNESS_IDENTITY_PROVIDER": "opsagent", "HARNESS_PORT": "8097",
            "SERVER_ADDRESS": "0.0.0.0", "HARNESS_INSTANCE_ID": name,
            "HARNESS_CONFIG": f"/run/harness/{'worker' if worker else 'api'}.json",
            "HARNESS_GOVERNANCE_CONFIG": "/run/harness/governance.json",
            "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": "4",
            "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE": "1",
            "SERVER_FORWARD_HEADERS_STRATEGY": "none" if worker else "native",
            "JAVA_TOOL_OPTIONS": append_trust(
                f"-XX:ActiveProcessorCount=2 -XX:+UseSerialGC -Xms64m -Xmx{heap}m "
                "-XX:ReservedCodeCacheSize=32m -XX:MaxDirectMemorySize=32m "
                "-Dfile.encoding=UTF-8 -Duser.timezone=UTC", store_type),
        }
        if not worker:
            service["environment"].update({
                "SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES": str(edge).replace(".", r"\."),
                "SERVER_TOMCAT_REMOTEIP_PROTOCOL_HEADER": "X-Forwarded-Proto",
                "SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER": "X-Forwarded-For",
                "SERVER_TOMCAT_REMOTEIP_HOST_HEADER": "X-Forwarded-Host",
                "SERVER_TOMCAT_REMOTEIP_PORT_HEADER": "X-Forwarded-Port",
            })
        out[name] = service
    support = copy.deepcopy(common)
    support.update(image=images["support"], mem_limit="192m", cpus=0.5, stop_grace_period="30s")
    support["env_file"] = [private_env(directory + "/private/support.env")]
    support["volumes"] = [copy.deepcopy(trust)]
    support["depends_on"] = {"harness-api": {"condition": "service_started"},
                             "harness-internal-tls": {"condition": "service_started"}}
    support["environment"] = {
        "SUPPORT_BIND_ADDRESS": "0.0.0.0", "SUPPORT_PORT": "8098",
        "SUPPORT_AUTH_MODE": "opsagent", "SUPPORT_RUNTIME_MODE": "production",
        "SUPPORT_PUBLIC_ORIGIN": "https://opsagent.cloud",
        "SUPPORT_PLATFORM_ORIGIN": "https://harness-platform.internal:8443",
        "SUPPORT_IDENTITY_ORIGIN": "https://harness-identity.internal:8443",
        "JAVA_TOOL_OPTIONS": append_trust(
            "-XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Xms32m -Xmx96m "
            "-XX:ReservedCodeCacheSize=24m -XX:MaxDirectMemorySize=16m "
            "-Dfile.encoding=UTF-8 -Duser.timezone=UTC", store_type),
    }
    out["support"] = support
    aliases = ["harness-identity.internal", "harness-rag.internal", "harness-platform.internal"]
    require("backplane" in base.get("networks", {}), "Expected existing Ops backplane network")
    gateway = copy.deepcopy(common)
    gateway.update(image=images["internalTls"], user="101:101", mem_limit="64m", cpus=0.5,
                   entrypoint=["nginx"], command=["-g", "daemon off;"], stop_grace_period="30s")
    gateway["networks"] = {runtime_key: {"aliases": aliases}, "backplane": {"aliases": aliases}}
    gateway["volumes"] = [
        bind(directory + "/nginx/internal-tls.conf", "/etc/nginx/nginx.conf"),
        bind(directory + "/private/internal-tls", "/run/tls"),
    ]
    out["harness-internal-tls"] = gateway

    used_networks = set()
    used_volumes = set()
    for name, service in out.items():
        used_networks.update(networks(service))
        require(set(service.get("depends_on", {})).issubset(out), "Dependency outside selected services")
        for mount in service.get("volumes", []):
            if mount.get("type") == "volume":
                used_volumes.add(mount["source"])
        if name in NEW:
            require(not service.get("ports"), "New services must have no host ports")
    result["networks"] = {}
    for name in sorted(used_networks - {runtime_key}):
        existing = mapping(base.get("networks", {}).get(name), "Existing network definition")
        require(existing.get("name"), "Resolved existing network name is required")
        result["networks"][name] = {"external": True, "name": existing["name"]}
    result["networks"][runtime_key] = {
        "name": net["name"], "driver": "bridge",
        "ipam": {"config": [{"subnet": str(subnet)}]},
    }
    result["volumes"] = {}
    for name in sorted(used_volumes):
        existing = mapping(base.get("volumes", {}).get(name), "Existing volume definition")
        require(existing.get("name"), "Resolved existing volume name is required")
        result["volumes"][name] = {"external": True, "name": existing["name"]}
    for key in ("configs", "secrets"):
        if key in base:
            result[key] = copy.deepcopy(base[key])
    review = {
        "format": "harness-ai-minimal-review-v1", "status": "RENDERED_NOT_DEPLOYED",
        "baseComposeSha256": base_digest, "services": sorted(out),
        "selectedOpsCount": len(selected), "newServiceCount": len(NEW),
        "oldVectorEnabled": old_vector, "vectorEnabled": vector,
        "vectorDegraded": old_vector and not vector, "rerankerEnabled": False,
        "restartPolicy": "no", "newHostPorts": [],
        "privateOriginAliases": aliases, "trustedEdgeAddress": str(edge),
        "newServiceMemoryLimitMiB": 1152,
        "requiredBeforeStart": [
            "Verify immutable images correspond to final accepted artifacts and exact source commits.",
            "Prepare target-only raw env files, api/worker manifests, governance and application grants.",
            "Keep public JDK CA roots in the mounted truststore and add the private CA.",
            "Install private internal and MySQL certificates with matching SANs and correct UID permissions.",
            "Verify MySQL dedicated accounts REQUIRE SSL and JDBC sslMode=VERIFY_IDENTITY.",
            "Review live Docker network inventory, MySQL connection ceiling and measured memory headroom.",
            "Add the generated locations include only to the existing apex HTTPS server; preserve Ops routes.",
            "Validate Compose/nginx and local positive/negative TLS, proxy, cookie and identity checks.",
            "Keep historical data, unrelated projects, optional components and old restart policy intact.",
        ],
    }
    return result, review


def write_private(path: Path, data: str) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
        stream.write(data)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-compose", required=True, type=Path)
    parser.add_argument("--inputs", required=True, type=Path)
    parser.add_argument("--output-directory", required=True, type=Path)
    args = parser.parse_args()
    try:
        raw = args.base_compose.read_bytes()
        base = json.loads(raw)
        spec = json.loads(args.inputs.read_text(encoding="utf-8-sig"))
        result, review = render(base, spec, hashlib.sha256(raw).hexdigest())
        destination = args.output_directory
        require(not destination.exists() and not destination.is_symlink(), "Output directory must be new")
        destination.mkdir(parents=False, mode=0o700)
        write_private(destination / "compose.ai-minimal.private.json", json.dumps(result, indent=2) + "\n")
        write_private(destination / "review.json", json.dumps(review, indent=2) + "\n")
        source = Path(__file__).resolve().parent
        for origin, target in (
            ("ai-minimal.nginx.locations.conf", "ai-minimal.locations.conf"),
            ("internal-tls.nginx.conf", "internal-tls.conf"),
        ):
            write_private(destination / target, (source / origin).read_text(encoding="utf-8"))
        print("Rendered private deployment candidate; no deployment or validation has been performed.")
        return 0
    except (InvalidPlan, OSError, ValueError) as exc:
        # Never echo parsed config, environment values or exception payloads from JSON.
        detail = str(exc) if isinstance(exc, InvalidPlan) else type(exc).__name__
        print("Preparation rejected: " + detail, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
