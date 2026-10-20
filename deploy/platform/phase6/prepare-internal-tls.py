"""Prepare fresh private TLS material and an augmented real JRE truststore, offline."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import ssl
import subprocess


GATEWAY_NAMES = (
    "harness-identity.internal", "harness-rag.internal", "harness-platform.internal",
)
MYSQL_NAMES = ("mysql", "harness-mysql")
CERTIFICATE = re.compile(
    r"-----BEGIN CERTIFICATE-----\s+.*?-----END CERTIFICATE-----", re.DOTALL
)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def write_new(path, content, mode=0o600):
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    descriptor = os.open(path, flags, mode)
    with os.fdopen(descriptor, "wb") as output:
        output.write(content if isinstance(content, bytes) else content.encode("utf-8"))


def write_json(path, value):
    write_new(path, json.dumps(value, indent=2, sort_keys=True) + "\n")


def executable(value):
    found = shutil.which(str(value))
    if not found:
        raise ValueError("Required executable is unavailable: " + str(value))
    return str(Path(found).resolve(strict=True))


def run(command, operation):
    result = subprocess.run(command, capture_output=True, timeout=120)
    if result.returncode:
        # Commands can refer to private files. Never echo command, output or password.
        raise ValueError(f"{operation} failed (exit {result.returncode})")
    return result.stdout.decode("utf-8", errors="replace")


def password_arguments(password_file):
    if password_file is None:
        # The standard cacerts integrity password is public; this stores certificates only.
        return ["-storepass", "changeit"]
    path = Path(password_file).resolve(strict=True)
    if not path.is_file() or path.stat().st_size > 4096:
        raise ValueError("Truststore password file must be a small regular UTF-8 file")
    password = path.read_text(encoding="utf-8").removesuffix("\n").removesuffix("\r")
    if len(password) < 6 or "\n" in password or "\r" in password or "\x00" in password:
        raise ValueError("Truststore password file must contain one password of at least 6 characters")
    return ["-storepass:file", str(path)]


def store_inventory(keytool, path, password):
    output = run(
        [keytool, "-J-Duser.language=en", "-J-Duser.country=US",
         "-list", "-rfc", "-keystore", str(path), *password],
        "Truststore inspection",
    )
    match = re.search(r"^Keystore type:\s*(JKS|PKCS12)\s*$", output, re.MULTILINE)
    certificates = Counter(
        sha256(ssl.PEM_cert_to_DER_cert(value)) for value in CERTIFICATE.findall(output)
    )
    if not match or not certificates or "PrivateKeyEntry" in output or "SecretKeyEntry" in output:
        raise ValueError("Source must be a nonempty certificate-only JKS or PKCS12 JRE truststore")
    return match.group(1), certificates


def new_output_directory(path):
    candidate = Path(os.path.abspath(path))
    for ancestor in (candidate, *candidate.parents):
        if ancestor.is_symlink() or getattr(ancestor, "is_junction", lambda: False)():
            raise ValueError("Output symlinks and junctions are forbidden")
    if not candidate.parent.is_dir():
        raise ValueError("Output parent must already exist")
    # exist_ok=False deliberately rejects even an empty directory or dangling link.
    candidate.mkdir(mode=0o700)
    return candidate.resolve(strict=True)


def prepare(output_dir, jre_cacerts, openssl="openssl", keytool="keytool",
            password_file=None, ca_days=3650, leaf_days=397):
    if not 1 <= leaf_days < ca_days <= 36500:
        raise ValueError("Require 1 <= leaf days < CA days <= 36500")
    openssl = executable(openssl)
    keytool = executable(keytool)
    source = Path(jre_cacerts).resolve(strict=True)
    if not source.is_file():
        raise ValueError("Specify the actual runtime JRE cacerts file")
    password = password_arguments(password_file)
    source_digest = sha256(source.read_bytes())
    source_type, original_roots = store_inventory(keytool, source, password)
    old_umask = os.umask(0o077)
    output = None
    try:
        output = new_output_directory(output_dir)
        write_json(output / "started.json", {
            "status": "GENERATION_STARTED_NOT_DEPLOYED",
            "startedAt": datetime.now(timezone.utc).isoformat(),
            "sourceCacertsSha256": source_digest,
        })
        for directory in (
            "ca", "ca/public", "ca/private", "internal-tls", "mysql-tls", "trust", "requests",
        ):
            (output / directory).mkdir(mode=0o700)
        ca_key = output / "ca/private/ca.key"
        ca_cert = output / "ca/public/ca.crt"
        ca_config = output / "requests/ca.cnf"
        request_config = output / "requests/request.cnf"
        write_new(ca_config, (
            "[req]\ndistinguished_name=dn\nprompt=no\nx509_extensions=ca_extensions\n"
            "[dn]\nCN=Harness Internal Root CA\n"
            "[ca_extensions]\nbasicConstraints=critical,CA:TRUE,pathlen:0\n"
            "keyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\n"
        ))
        write_new(request_config, "[req]\ndistinguished_name=dn\nprompt=no\n[dn]\nCN=unused\n")
        run([
            openssl, "req", "-x509", "-newkey", "rsa:3072", "-nodes", "-sha256",
            "-days", str(ca_days), "-config", str(ca_config),
            "-keyout", str(ca_key), "-out", str(ca_cert),
        ], "Internal CA generation")
        for label, names, key_relative, cert_relative in (
            ("gateway", GATEWAY_NAMES, "internal-tls/server-key.pem",
             "requests/gateway-leaf.pem"),
            ("mysql", MYSQL_NAMES, "mysql-tls/server-key.pem",
             "mysql-tls/server-cert.pem"),
        ):
            key = output / key_relative
            cert = output / cert_relative
            csr = output / f"requests/{label}.csr"
            extensions = output / f"requests/{label}.ext"
            write_new(extensions, (
                "basicConstraints=critical,CA:FALSE\n"
                "keyUsage=critical,digitalSignature,keyEncipherment\n"
                "extendedKeyUsage=serverAuth\n"
                "subjectKeyIdentifier=hash\n"
                "authorityKeyIdentifier=keyid,issuer\n"
                "subjectAltName=" + ",".join("DNS:" + name for name in names) + "\n"
            ))
            run([
                openssl, "req", "-new", "-newkey", "rsa:3072", "-nodes", "-sha256",
                "-config", str(request_config),
                "-subj", "/CN=" + names[0], "-keyout", str(key), "-out", str(csr),
            ], label + " key and request generation")
            run([
                openssl, "x509", "-req", "-in", str(csr), "-CA", str(ca_cert),
                "-CAkey", str(ca_key), "-set_serial", "0x" + secrets.token_hex(19),
                "-days", str(leaf_days), "-sha256", "-extfile", str(extensions),
                "-out", str(cert),
            ], label + " certificate signing")
            for name in names:
                run([
                    openssl, "verify", "-purpose", "sslserver", "-verify_hostname", name,
                    "-CAfile", str(ca_cert), str(cert),
                ], label + " hostname and chain verification")
        write_new(
            output / "internal-tls/server-chain.pem",
            (output / "requests/gateway-leaf.pem").read_bytes() + ca_cert.read_bytes(),
        )
        write_new(output / "mysql-tls/ca.pem", ca_cert.read_bytes())
        truststore = output / "trust/cacerts"
        write_new(truststore, source.read_bytes())
        if sha256(truststore.read_bytes()) != source_digest:
            raise ValueError("Source cacerts changed during generation; preserve output and start anew")
        ca_fingerprint = sha256(ssl.PEM_cert_to_DER_cert(ca_cert.read_text(encoding="ascii")))
        run([
            keytool, "-importcert", "-noprompt", "-alias", "harness-internal-ca-" + ca_fingerprint[:16],
            "-file", str(ca_cert), "-keystore", str(truststore), "-storetype", source_type, *password,
        ], "Internal CA import into copied truststore")
        result_type, resulting_roots = store_inventory(keytool, truststore, password)
        if result_type != source_type or resulting_roots != original_roots + Counter({ca_fingerprint: 1}):
            raise ValueError("Truststore did not preserve every original certificate exactly once")
        if sha256(source.read_bytes()) != source_digest:
            raise ValueError("Original JRE cacerts changed; generated output is not accepted")
        for relative in ("ca/private/ca.key", "internal-tls/server-key.pem", "mysql-tls/server-key.pem"):
            os.chmod(output / relative, 0o600)
        # Public-only directories can be traversed after selective service mounts.
        for relative in ("internal-tls", "mysql-tls", "trust"):
            os.chmod(output / relative, 0o755)
        public_files = (
            "ca/public/ca.crt", "internal-tls/server-chain.pem",
            "mysql-tls/server-cert.pem", "mysql-tls/ca.pem", "trust/cacerts",
        )
        for relative in public_files:
            os.chmod(output / relative, 0o644)
        manifest = {
            "status": "PREPARED_NOT_DEPLOYED",
            "preparedAt": datetime.now(timezone.utc).isoformat(),
            "gatewayDnsSans": list(GATEWAY_NAMES),
            "mysqlDnsSans": list(MYSQL_NAMES),
            "caValidityDays": ca_days, "leafValidityDays": leaf_days,
            "caCertificateSha256": ca_fingerprint,
            "sourceCacertsSha256": source_digest,
            "trustStoreType": result_type,
            "originalTrustedCertificateCount": sum(original_roots.values()),
            "resultTrustedCertificateCount": sum(resulting_roots.values()),
            "originalCertificatesPreserved": True,
            "originalCertificateFingerprints": sorted(original_roots.elements()),
            "publicFilesSha256": {
                relative: sha256((output / relative).read_bytes()) for relative in public_files
            },
            "serviceMountDirectories": ["internal-tls", "mysql-tls", "trust"],
            "offlineCaPrivateKey": "ca/private/ca.key",
            "mysqlKeyOwnership": "INSTALLER_MUST_ASSIGN_TARGET_MYSQL_UID",
            "deploymentAcceptance": False,
        }
        write_json(output / "manifest.json", manifest)
        return manifest
    except Exception:
        if output is not None:
            write_json(output / "failed.json", {
                "status": "FAILED_DO_NOT_DEPLOY",
                "failedAt": datetime.now(timezone.utc).isoformat(),
                "action": "Preserve this directory; investigate and choose a new output directory.",
            })
        raise
    finally:
        os.umask(old_umask)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--jre-cacerts", type=Path, required=True)
    parser.add_argument("--openssl", default="openssl")
    parser.add_argument("--keytool", default="keytool")
    parser.add_argument("--truststore-password-file", type=Path)
    parser.add_argument("--ca-days", type=int, default=3650)
    parser.add_argument("--leaf-days", type=int, default=397)
    args = parser.parse_args()
    try:
        result = prepare(
            args.output_dir, args.jre_cacerts, args.openssl, args.keytool,
            args.truststore_password_file, args.ca_days, args.leaf_days,
        )
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        # Do not expose a tool's command/password parameters in timeout exceptions.
        message = "External tool timed out; incomplete output retained" if isinstance(
            error, subprocess.TimeoutExpired
        ) else str(error)
        parser.exit(1, "TLS preparation failed: " + message + "\n")
    print(f"Prepared offline TLS material; {result['originalTrustedCertificateCount']} original "
          f"JRE roots preserved in {result['trustStoreType']}. No services changed.")


if __name__ == "__main__":
    main()
