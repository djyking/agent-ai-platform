"""Bundle rejection and artifact integrity tests; no deployment or provider access."""
import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
import phase5_bundle as bundle


class BundleTests(unittest.TestCase):
    def entries(self):
        names = [bundle.DEPLOY_PREFIX + name for name in bundle.DEPLOY_ALLOWLIST]
        names += list(bundle.REQUIRED_DOCS)
        return [(name, "100644", "synthetic-git-object") for name in names]

    def test_only_reviewed_tracked_files_are_selected_and_reports_are_excluded(self):
        entries = self.entries() + [("docs/validation/fixture-report.json", "100644", "excluded")]
        selected = bundle.selected_files(entries)
        self.assertNotIn("docs/validation/fixture-report.json", selected)
        for forbidden in ("private/runtime.env", ".env", "operator.password.txt", "new-unreviewed.py"):
            with self.assertRaises(ValueError):
                bundle.selected_files(self.entries() + [(bundle.DEPLOY_PREFIX + forbidden, "100644", "forbidden")])
        with self.assertRaises(ValueError):
            bundle.selected_files(self.entries() + [("docs/credentials.md", "100644", "forbidden")])
        changed = self.entries()
        changed[0] = (changed[0][0], "120000", "symlink")
        with self.assertRaises(ValueError):
            bundle.selected_files(changed)

    def test_dirty_tree_is_rejected_without_a_release_override(self):
        with patch.object(bundle, "git", return_value=b" M modified.java\n"):
            with self.assertRaisesRegex(ValueError, "clean committed worktree"):
                bundle.clean_commit(Path("."))

    def test_jar_digest_and_private_resources_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            path = root / "fixture.jar"
            with zipfile.ZipFile(path, "w") as archive:
                for name in bundle.REQUIRED_JAR_CLASSES:
                    archive.writestr("BOOT-INF/classes/io/github/djyking/harness/platform/" + name + ".class", b"synthetic")
                archive.writestr("BOOT-INF/classes/static/console/index.html", "synthetic")
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertEqual(digest, bundle.checked_jar(root, path, digest)[1])
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                bundle.checked_jar(root, path, "0" * 64)
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("BOOT-INF/classes/private/runtime.env", "private-fixture")
            with self.assertRaisesRegex(ValueError, "Private application resource"):
                bundle.checked_jar(root, path, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_old_service_jar_without_phase5_classes_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            path = root / "old-fixture.jar"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("BOOT-INF/classes/io/github/djyking/harness/platform/PlatformApplication.class", b"synthetic-old")
                archive.writestr("BOOT-INF/classes/static/console/index.html", "synthetic-old")
            with self.assertRaisesRegex(ValueError, "Phase 5 service classes"):
                bundle.checked_jar(root, path, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_bundle_read_back_detects_payload_mismatch_and_unlisted_files(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.zip"
            data = b"synthetic-jar"
            digest = hashlib.sha256(data).hexdigest()
            manifest = {"jarSha256": digest, "files": {bundle.JAR_ENTRY: {"sha256": digest, "bytes": len(data)}}}
            for corrupt in (False, True):
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr(bundle.JAR_ENTRY, data if not corrupt else b"tampered-jar")
                    archive.writestr(bundle.MANIFEST_ENTRY, json.dumps(manifest))
                if corrupt:
                    with self.assertRaisesRegex(ValueError, "verification failed"):
                        bundle.verify_bundle(path)
                else:
                    self.assertEqual(digest, bundle.verify_bundle(path)["jarSha256"])
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("unlisted.txt", "unexpected")
            with self.assertRaisesRegex(ValueError, "contents differ"):
                bundle.verify_bundle(path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
