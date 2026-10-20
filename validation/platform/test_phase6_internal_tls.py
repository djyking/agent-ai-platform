"""Exercise real offline certificate generation, hostname checks and JRE-root preservation."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "deploy/platform/phase6/prepare-internal-tls.py"
SPEC = importlib.util.spec_from_file_location("phase6_internal_tls", SCRIPT)
tls = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tls)


def available_tool(name):
    explicit = os.environ.get("HARNESS_TEST_" + name.upper())
    found = explicit or shutil.which(name)
    if found:
        return str(Path(found).resolve())
    if name == "openssl":
        # Git for Windows bundles OpenSSL, but does not always add it to PATH.
        git = shutil.which("git")
        if git:
            for directory in (Path(git).parent.parent / "usr/bin",
                              Path(git).parent.parent / "mingw64/bin"):
                candidate = directory / "openssl.exe"
                if candidate.is_file():
                    return str(candidate)
    return None


class InternalTlsSafetyTest(unittest.TestCase):
    def test_exclusive_writer_does_not_replace_existing_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            tls.write_new(path, b"original evidence")
            with self.assertRaises(FileExistsError):
                tls.write_new(path, b"replacement")
            self.assertEqual(b"original evidence", path.read_bytes())

    def test_existing_directory_file_and_symlink_are_not_reused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(FileExistsError):
                tls.new_output_directory(root)
            evidence = root / "evidence"
            evidence.write_text("original")
            with self.assertRaises(FileExistsError):
                tls.new_output_directory(evidence)
            self.assertEqual("original", evidence.read_text())
            link = root / "link"
            try:
                link.symlink_to(root, target_is_directory=True)
            except OSError:
                return  # Windows commonly lacks symlink privilege; the real cases above still ran.
            with self.assertRaisesRegex(ValueError, "symlinks"):
                tls.new_output_directory(link / "new")
            self.assertFalse((root / "new").exists())

    def test_password_file_rejects_empty_multiple_lines_and_nul_without_echoing_it(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "password"
            for invalid in ("", "short", "secret-one\nsecret-two", "secret\x00suffix"):
                path.write_text(invalid)
                with self.assertRaises(ValueError) as error:
                    tls.password_arguments(path)
                self.assertNotIn("secret-one", str(error.exception))
                self.assertNotIn("secret-two", str(error.exception))
            path.write_text("synthetic-test-password\n")
            self.assertEqual(["-storepass:file", str(path.resolve())],
                             tls.password_arguments(path))

    def test_invalid_validity_rejected_before_tool_or_output_operations(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "not-created"
            for leaf, ca in ((0, 10), (10, 10), (11, 10), (1, 36501)):
                with self.assertRaisesRegex(ValueError, "leaf days"):
                    tls.prepare(path, "nonexistent-source", ca_days=ca, leaf_days=leaf)
            self.assertFalse(path.exists())


class InternalTlsRealToolsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.openssl = available_tool("openssl")
        cls.keytool = available_tool("keytool")
        if not cls.openssl or not cls.keytool:
            raise RuntimeError("TLS tests require OpenSSL and Java keytool; set HARNESS_TEST_* paths")
        specified = os.environ.get("HARNESS_TEST_JRE_CACERTS")
        cls.source = Path(specified) if specified else Path(cls.keytool).parent.parent / "lib/security/cacerts"
        if not cls.source.is_file():
            raise RuntimeError("TLS tests require actual JRE cacerts; set HARNESS_TEST_JRE_CACERTS")
        cls.temporary = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.directory = Path(cls.temporary.name)
        cls.source_hash = hashlib.sha256(cls.source.read_bytes()).hexdigest()
        cls.output = cls.directory / "generated"
        cls.manifest = tls.prepare(cls.output, cls.source, cls.openssl, cls.keytool)

    def verify(self, certificate, hostname, purpose="sslserver", ca=None):
        return subprocess.run([
            self.openssl, "verify", "-purpose", purpose, "-verify_hostname", hostname,
            "-CAfile", str(ca or self.output / "ca/public/ca.crt"), str(certificate),
        ], capture_output=True)

    def test_all_sans_work_wrong_names_and_wrong_purpose_fail(self):
        for relative, names in (
            ("internal-tls/server-chain.pem", tls.GATEWAY_NAMES),
            ("mysql-tls/server-cert.pem", tls.MYSQL_NAMES),
        ):
            for hostname in names:
                self.assertEqual(0, self.verify(self.output / relative, hostname).returncode)
            self.assertNotEqual(0, self.verify(self.output / relative, "untrusted.example").returncode)
            self.assertNotEqual(0, self.verify(self.output / relative, names[0], "sslclient").returncode)
        self.assertNotEqual(
            0, self.verify(self.output / "mysql-tls/server-cert.pem", tls.GATEWAY_NAMES[0]).returncode
        )
        self.assertNotEqual(
            0, self.verify(self.output / "internal-tls/server-chain.pem", "mysql").returncode
        )

    def test_service_keys_are_distinct_and_match_only_their_certificates(self):
        public_keys = []
        for key_name, cert_name in (
            ("ca/private/ca.key", "ca/public/ca.crt"),
            ("internal-tls/server-key.pem", "requests/gateway-leaf.pem"),
            ("mysql-tls/server-key.pem", "mysql-tls/server-cert.pem"),
        ):
            from_key = tls.run(
                [self.openssl, "pkey", "-in", str(self.output / key_name), "-pubout"], "test key"
            )
            from_cert = tls.run(
                [self.openssl, "x509", "-in", str(self.output / cert_name), "-pubkey", "-noout"],
                "test certificate",
            )
            self.assertEqual(from_key, from_cert)
            public_keys.append(from_key)
        self.assertEqual(3, len(set(public_keys)))

    def test_gateway_chain_and_ca_mount_separation(self):
        chain = (self.output / "internal-tls/server-chain.pem").read_text()
        self.assertEqual(2, len(tls.CERTIFICATE.findall(chain)))
        self.assertTrue(chain.endswith((self.output / "ca/public/ca.crt").read_text()))
        ca_key = (self.output / "ca/private/ca.key").read_bytes()
        self.assertEqual(["internal-tls", "mysql-tls", "trust"],
                         self.manifest["serviceMountDirectories"])
        for directory in self.manifest["serviceMountDirectories"]:
            for path in (self.output / directory).iterdir():
                self.assertNotEqual(ca_key, path.read_bytes())
        for key in ("ca/private/ca.key", "internal-tls/server-key.pem", "mysql-tls/server-key.pem"):
            if os.name != "nt":
                self.assertEqual(0o600, (self.output / key).stat().st_mode & 0o777)

    def test_actual_jre_all_public_roots_preserved_and_source_unchanged(self):
        original_type, before = tls.store_inventory(
            self.keytool, self.source, tls.password_arguments(None)
        )
        result_type, after = tls.store_inventory(
            self.keytool, self.output / "trust/cacerts", tls.password_arguments(None)
        )
        self.assertEqual(original_type, result_type)
        self.assertEqual(before, after - tls.Counter({self.manifest["caCertificateSha256"]: 1}))
        self.assertEqual(sum(before.values()) + 1, sum(after.values()))
        self.assertEqual(self.source_hash, hashlib.sha256(self.source.read_bytes()).hexdigest())
        self.assertEqual("PREPARED_NOT_DEPLOYED", self.manifest["status"])
        self.assertFalse(self.manifest["deploymentAcceptance"])
        for relative, digest in self.manifest["publicFilesSha256"].items():
            self.assertEqual(digest, hashlib.sha256((self.output / relative).read_bytes()).hexdigest())
        self.assertEqual(self.manifest, json.loads((self.output / "manifest.json").read_text()))

    def test_second_generation_cannot_change_existing_keys_certificates_or_evidence(self):
        before = {str(path.relative_to(self.output)): hashlib.sha256(path.read_bytes()).hexdigest()
                  for path in self.output.rglob("*") if path.is_file()}
        with self.assertRaises(FileExistsError):
            tls.prepare(self.output, self.source, self.openssl, self.keytool)
        after = {str(path.relative_to(self.output)): hashlib.sha256(path.read_bytes()).hexdigest()
                 for path in self.output.rglob("*") if path.is_file()}
        self.assertEqual(before, after)

    def test_pkcs12_source_with_password_file_keeps_all_imported_real_jre_roots(self):
        pkcs12 = self.directory / "actual-jre-roots.p12"
        password = self.directory / "password.txt"
        password.write_text("synthetic-test-password\n")
        tls.run([
            self.keytool, "-importkeystore", "-noprompt",
            "-srckeystore", str(self.source), "-srcstorepass", "changeit",
            "-destkeystore", str(pkcs12), "-deststoretype", "PKCS12",
            "-deststorepass:file", str(password),
        ], "Test JRE conversion")
        before_type, before = tls.store_inventory(
            self.keytool, pkcs12, tls.password_arguments(password)
        )
        manifest = tls.prepare(
            self.directory / "pkcs12-generated", pkcs12, self.openssl, self.keytool, password
        )
        self.assertEqual("PKCS12", before_type)
        self.assertEqual("PKCS12", manifest["trustStoreType"])
        self.assertEqual(sum(before.values()), manifest["originalTrustedCertificateCount"])
        self.assertNotIn(password.read_text().strip(), json.dumps(manifest))

    def test_failed_generation_is_retained_and_never_reused(self):
        failed_output = self.directory / "failed"
        original_run = tls.run

        def fail_generation(command, operation):
            if operation == "Internal CA generation":
                raise ValueError("Synthetic external tool failure")
            return original_run(command, operation)

        with patch.object(tls, "run", side_effect=fail_generation):
            with self.assertRaisesRegex(ValueError, "Synthetic"):
                tls.prepare(failed_output, self.source, self.openssl, self.keytool)
        self.assertEqual("FAILED_DO_NOT_DEPLOY",
                         json.loads((failed_output / "failed.json").read_text())["status"])
        self.assertTrue((failed_output / "started.json").exists())
        self.assertFalse((failed_output / "manifest.json").exists())
        with self.assertRaises(FileExistsError):
            tls.prepare(failed_output, self.source, self.openssl, self.keytool)

    def test_wrong_source_password_fails_without_creating_output_or_changing_jre(self):
        password = self.directory / "wrong-password.txt"
        password.write_text("deliberately-incorrect")
        output = self.directory / "wrong-password-generated"
        with self.assertRaisesRegex(ValueError, "Truststore inspection failed") as error:
            tls.prepare(output, self.source, self.openssl, self.keytool, password)
        self.assertNotIn(password.read_text(), str(error.exception))
        self.assertFalse(output.exists())
        self.assertEqual(self.source_hash, hashlib.sha256(self.source.read_bytes()).hexdigest())

    def test_source_with_private_key_is_rejected_before_creating_output(self):
        private_store = self.directory / "not-a-jre-truststore.p12"
        tls.run([
            self.openssl, "pkcs12", "-export",
            "-inkey", str(self.output / "mysql-tls/server-key.pem"),
            "-in", str(self.output / "mysql-tls/server-cert.pem"),
            "-out", str(private_store), "-passout", "pass:changeit",
        ], "Synthetic private-key store fixture")
        output = self.directory / "private-key-source-generated"
        with self.assertRaisesRegex(ValueError, "certificate-only"):
            tls.prepare(output, private_store, self.openssl, self.keytool)
        self.assertFalse(output.exists())

    def test_loss_of_an_original_public_root_prevents_success_manifest(self):
        inventory = tls.run([
            self.keytool, "-J-Duser.language=en", "-list", "-rfc",
            "-keystore", str(self.source), "-storepass", "changeit",
        ], "Test root alias lookup")
        alias = re.search(r"^Alias name: (.+)$", inventory, re.MULTILINE).group(1).strip()
        output = self.directory / "tampered-truststore"
        original_run = tls.run

        def remove_root_after_import(command, operation):
            result = original_run(command, operation)
            if operation == "Internal CA import into copied truststore":
                original_run([
                    self.keytool, "-delete", "-alias", alias,
                    "-keystore", str(output / "trust/cacerts"), "-storepass", "changeit",
                ], "Synthetic root-loss fixture")
            return result

        with patch.object(tls, "run", side_effect=remove_root_after_import):
            with self.assertRaisesRegex(ValueError, "preserve every original"):
                tls.prepare(output, self.source, self.openssl, self.keytool)
        self.assertFalse((output / "manifest.json").exists())
        self.assertTrue((output / "failed.json").exists())
        self.assertEqual(self.source_hash, hashlib.sha256(self.source.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
