"""Fixture/security checks for the deployment package; no infrastructure is started."""
import copy
import io
import json
import sys
import tarfile
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / "deploy/platform/phase5"
sys.path.insert(0, str(PACKAGE))
import alert_sink
import backup
import db_accounts
import prepare
import restore


class DeploymentTests(unittest.TestCase):
    def test_proxy_has_fixed_trust_boundary_and_instance_roles(self):
        manifest = {"applicationSecrets": {"app": "env:APP_SECRET"}, "signingSecret": "env:SIGNING_KEY"}
        api, worker, nginx = prepare.render(manifest, "https://harness.example.test:8443")
        self.assertFalse(api["workerEnabled"])
        self.assertTrue(worker["workerEnabled"])
        self.assertNotIn("workerEnabled", manifest)
        self.assertIn('proxy_set_header Forwarded ""', nginx)
        self.assertIn("proxy_set_header X-Forwarded-For $remote_addr", nginx)
        self.assertIn("proxy_set_header X-Forwarded-Port 8443", nginx)
        self.assertIn("location /internal/ { return 404; }", nginx)
        for origin in ("http://host", "https://user:pass@host", "https://host/path", "https://host;injected"):
            with self.assertRaises(ValueError):
                prepare.render(manifest, origin)

    def test_separate_database_privileges_and_no_sql_injection(self):
        config = {"database": "harness_platform", "accountHost": "172.30.%"}
        for role in ("runtime", "migration", "backup"):
            config[role] = {"user": "harness_" + role, "password": "a" * 40}
        sql = db_accounts.account_sql(config)
        runtime = [line for line in sql.splitlines() if "GRANT" in line and "harness_runtime" in line]
        self.assertEqual(1, len(runtime))
        self.assertIn("GRANT SELECT, INSERT, UPDATE, DELETE ON", runtime[0])
        self.assertNotIn("CREATE,", runtime[0])
        self.assertNotIn("DROP", sql)
        config["runtime"]["password"] = "32_OR_MORE_RANDOM_BASE64URL_CHARACTERS"
        with self.assertRaisesRegex(ValueError, "Example account passwords"):
            db_accounts.account_sql(config)
        config["runtime"]["password"] = "a" * 40
        config["database"] = "bad`; DROP DATABASE mysql"
        with self.assertRaises(ValueError):
            db_accounts.account_sql(config)

    def test_deployment_templates_have_consistent_project_releases_and_explicit_governance(self):
        deployment = json.loads((PACKAGE / "deployment.example.json").read_text(encoding="utf-8"))
        governance = json.loads((PACKAGE / "governance.example.json").read_text(encoding="utf-8"))
        self.assertEqual(10, deployment["concurrency"])
        projects = {row["id"] for row in deployment["projects"]}
        self.assertEqual({"pilot-a", "pilot-b"}, projects)
        self.assertTrue(all(row["projectId"] in projects for row in deployment["releases"]))
        release_ids = [row["releaseId"] for row in deployment["releases"]]
        self.assertEqual(len(release_ids), len(set(release_ids)))
        self.assertTrue(all(row["secretRef"].startswith("env:") for row in deployment["models"]))
        self.assertEqual(5, governance["modelConcurrency"])
        self.assertEqual({}, governance["budgets"], "The template must not invent approved production budgets")

    def test_mysql_snapshot_coordinates_are_required_and_parsed(self):
        with tempfile.TemporaryDirectory() as directory:
            dump = Path(directory) / "dump.sql"
            dump.write_text("-- CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE='harness-bin.000123', SOURCE_LOG_POS=987;\n")
            self.assertEqual({"file": "harness-bin.000123", "position": 987}, backup.full_coordinates(dump))
            dump.write_text("-- no coordinates")
            with self.assertRaises(ValueError):
                backup.full_coordinates(dump)

    def test_ciphertext_receipt_and_private_state_are_separate_from_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = root / "state"
            state.mkdir()
            (state / "signing.key").write_text("PRIVATE_SENTINEL")
            dump = root / "database.sql"
            dump.write_text("SYNTHETIC_SQL")
            config = {"destination": str(root / "encrypted"), "ageRecipients": str(root / "recipients"),
                      "privateState": str(state)}
            metadata = {"kind": "full", "source": {"database": "test"}}
            commands = []

            def fake_age(command, **_):
                commands.append(command)
                with tarfile.open(command[-1], "r:gz") as archive:
                    self.assertEqual(b"PRIVATE_SENTINEL", archive.extractfile("private-state/signing.key").read())
                    self.assertEqual(b"SYNTHETIC_SQL", archive.extractfile("database.sql").read())
                Path(command[command.index("--output") + 1]).write_bytes(b"SYNTHETIC_CIPHERTEXT")

            with patch.object(backup, "checked", fake_age):
                receipt = backup.encrypt_bundle(config, root, metadata, [("database.sql", dump)], True)
            self.assertEqual("age", commands[0][0])
            self.assertNotIn("PRIVATE_SENTINEL", json.dumps(commands))
            self.assertNotIn("PRIVATE_SENTINEL", json.dumps(receipt))
            self.assertFalse(receipt["offsiteVerified"])
            self.assertEqual("DISABLED", receipt["recoveryExecution"])

    def test_failed_encryption_does_not_create_success_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dump = root / "database.sql"
            dump.write_text("fixture")
            config = {"destination": str(root / "encrypted"), "ageRecipients": str(root / "recipients")}
            with patch.object(backup, "checked", side_effect=RuntimeError("fixture failure")):
                with self.assertRaises(RuntimeError):
                    backup.encrypt_bundle(config, root, {"kind": "binlog", "source": {}}, [("database.sql", dump)])
            self.assertEqual([], list((root / "encrypted").glob("*.json")))

    def test_retention_is_preview_and_never_deletes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "full-old.tar.gz.age"
            archive.write_text("encrypted-fixture")
            row = {"file": archive.name, "createdAt": "2026-01-01T00:00:00+00:00", "kind": "full"}
            archive.with_suffix(archive.suffix + ".json").write_text(json.dumps(row))
            result = backup.retention_preview({"destination": directory, "retentionDays": 30},
                                                datetime(2026, 3, 1, tzinfo=timezone.utc))
            self.assertEqual("DRY_RUN_ONLY", result["mode"])
            self.assertEqual(0, result["deleteCount"])
            self.assertEqual(1, len(result["candidates"]))
            self.assertTrue(archive.exists())
            self.assertTrue(result["requiresChainReview"])

    def test_unsafe_offsite_destination_is_rejected_before_execution(self):
        for host in ("backup@host;cat secret", "localhost", "-oProxyCommand=bad"):
            with self.assertRaises(ValueError), patch.object(backup, "checked") as external:
                backup.copy_offsite({"offsite": {"host": host, "directory": "/backups"}}, {})
                external.assert_not_called()

    def test_offsite_freshness_requires_contiguous_chain_and_uses_archive_time(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = {"serverUuid": "server-A", "database": "harness_platform"}
            full = {"kind": "full", "source": source, "createdAt": "2026-09-20T01:00:00+00:00",
                    "coordinates": {"file": "harness-bin.000010", "position": 456}, "offsiteVerified": True}
            log = {"kind": "binlog", "source": source, "createdAt": "2026-09-20T01:10:00+00:00",
                   "logNames": ["harness-bin.000010", "harness-bin.000012"], "offsiteVerified": True}
            (root / "full.tar.gz.age.json").write_text(json.dumps(full))
            path = root / "log.tar.gz.age.json"
            path.write_text(json.dumps(log))
            with patch.object(backup, "copy_offsite", side_effect=lambda _, row, **kwargs: row), patch.object(backup, "metric") as metric:
                with self.assertRaisesRegex(ValueError, "incomplete"):
                    backup.sync_offsite({"destination": directory})
                metric.assert_not_called()
                log["logNames"].append("harness-bin.000011")
                path.write_text(json.dumps(log))
                result = backup.sync_offsite({"destination": directory})
                self.assertEqual(2, result["verifiedCiphertexts"])
                self.assertEqual(datetime(2026, 9, 20, 1, 10, tzinfo=timezone.utc).timestamp(), metric.call_args.args[2])

    def test_restore_chain_checks_source_gap_and_coverage(self):
        source = {"serverUuid": "server-A", "database": "harness_platform"}
        full = {"kind": "full", "source": source, "createdAt": "2026-09-20T01:00:00+00:00",
                "coordinates": {"file": "harness-bin.000010", "position": 456}}
        logs = {"kind": "binlog", "source": source, "coverageLowerBoundUtc": "2026-09-20T02:00:00Z",
                "payloads": [{"name": "binlogs/harness-bin.000010", "sha256": "a"},
                             {"name": "binlogs/harness-bin.000011", "sha256": "b"}]}
        plan = restore.recovery_plan(full, [logs], "2026-09-20T01:30:00Z")
        self.assertFalse(plan["workersEnabled"])
        self.assertEqual(456, plan["startPosition"])
        changed = copy.deepcopy(logs)
        changed["payloads"][1]["name"] = "binlogs/harness-bin.000012"
        with self.assertRaisesRegex(ValueError, "gaps"):
            restore.recovery_plan(full, [changed], "2026-09-20T01:30:00Z")
        changed = copy.deepcopy(logs)
        changed["source"]["serverUuid"] = "server-B"
        with self.assertRaises(ValueError):
            restore.recovery_plan(full, [changed], "2026-09-20T01:30:00Z")
        with self.assertRaisesRegex(ValueError, "coverage"):
            restore.recovery_plan(full, [logs], "2026-09-20T02:30:00Z")

    def test_restore_never_accepts_live_target_or_active_events(self):
        with tempfile.TemporaryDirectory() as directory:
            defaults = Path(directory) / "mysql.cnf"
            defaults.write_text("[client]\nhost=production\nport=3306\n")
            with self.assertRaises(ValueError), patch.object(restore, "mysql") as mysql:
                restore.isolated_target(defaults, "source")
                mysql.assert_not_called()
            defaults.write_text("[client]\nhost=127.0.0.1\nport=13306\n")
            for result in ("source\tOFF\t0", "new\tON\t0", "new\tOFF\t1"):
                with patch.object(restore, "mysql", return_value=result), self.assertRaises(ValueError):
                    restore.isolated_target(defaults, "source")
            with patch.object(restore, "mysql", return_value="new\tOFF\t0"):
                self.assertEqual("new", restore.isolated_target(defaults, "source")[1])

    def test_untrusted_tar_paths_links_and_hash_mismatch_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, name in enumerate(("../escape", "/absolute", "windows\\escape")):
                bundle = root / f"bad{index}.tar.gz"
                with tarfile.open(bundle, "w:gz") as archive:
                    item = tarfile.TarInfo(name)
                    item.size = 1
                    archive.addfile(item, io.BytesIO(b"x"))
                with self.assertRaises(ValueError):
                    restore.unpack(bundle, root / f"extracted{index}")
            bundle = root / "symlink.tar.gz"
            with tarfile.open(bundle, "w:gz") as archive:
                item = tarfile.TarInfo("linked")
                item.type = tarfile.SYMTYPE
                item.linkname = "/etc/passwd"
                archive.addfile(item)
            with self.assertRaises(ValueError):
                restore.unpack(bundle, root / "link")

    def test_decrypted_archive_payload_hash_is_verified_before_plan_or_apply(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dump = root / "database.sql"
            dump.write_text("SYNTHETIC_SQL")
            metadata = {"format": "harness-backup-v1", "kind": "full",
                        "payloads": [{"name": "database.sql", "sha256": backup.file_hash(dump)}]}
            for corrupt in (False, True):
                bundle = root / f"bundle-{corrupt}.tar.gz"
                with tarfile.open(bundle, "w:gz") as archive:
                    raw = json.dumps(metadata).encode()
                    info = tarfile.TarInfo("manifest.json")
                    info.size = len(raw)
                    archive.addfile(info, io.BytesIO(raw))
                    payload = b"CORRUPT_SQL" if corrupt else dump.read_bytes()
                    info = tarfile.TarInfo("database.sql")
                    info.size = len(payload)
                    archive.addfile(info, io.BytesIO(payload))
                target = root / f"extracted-{corrupt}"
                if corrupt:
                    with self.assertRaisesRegex(ValueError, "hash mismatch"):
                        restore.unpack(bundle, target)
                else:
                    self.assertEqual("full", restore.unpack(bundle, target)["kind"])

    def test_alert_http_firing_and_recovery_store_no_annotation_body(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "alerts.jsonl"
            receiver = alert_sink.server("127.0.0.1", 0, destination)
            thread = threading.Thread(target=receiver.serve_forever, daemon=True)
            thread.start()
            try:
                for status in ("firing", "resolved"):
                    event = {"status": status, "alerts": [{"status": status,
                             "labels": {"alertname": "FixtureAlert", "severity": "critical", "project": "test"},
                             "annotations": {"body": "PRIVATE_SENTINEL"}}]}
                    request = urllib.request.Request(f"http://127.0.0.1:{receiver.server_port}/alerts",
                                                     data=json.dumps(event).encode(),
                                                     headers={"Content-Type": "application/json"})
                    with urllib.request.urlopen(request, timeout=5) as response:
                        self.assertEqual(202, response.status)
            finally:
                receiver.shutdown()
                receiver.server_close()
                thread.join(timeout=5)
            text = destination.read_text()
            self.assertNotIn("PRIVATE_SENTINEL", text)
            self.assertEqual(["firing", "resolved"], [json.loads(line)["status"] for line in text.splitlines()])


if __name__ == "__main__":
    unittest.main(verbosity=2)
