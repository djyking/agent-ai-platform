"""Exercise provenance, archive integrity, privacy and honest acceptance boundaries."""
import hashlib
import json
import subprocess
import tempfile
import unittest
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

import phase6_bundle as bundle


class Phase6BundleTest(unittest.TestCase):
    def entries(self):
        names = {bundle.previous.DEPLOY_PREFIX + name for name in bundle.previous.DEPLOY_ALLOWLIST}
        names |= set(bundle.previous.REQUIRED_DOCS) | set(bundle.PHASE6_DOCS)
        names |= {bundle.PHASE6_PREFIX + name for name in bundle.PHASE6_DEPLOY}
        return [(name, "100644", "fixture-object") for name in sorted(names)]

    def jar(self, root, role, private=False, old_schema=False):
        path = root / bundle.ARTIFACTS[role][1] / "target/fixture.jar"
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w") as archive:
            for name in bundle.REQUIRED_CLASSES[role]:
                content = b"synthetic-public-class"
                if name.endswith("StudioRepository.class"):
                    content += b"VALUES('studio',1)" if old_schema else b"VALUES('studio',2)"
                if name.endswith("OnboardingRepository.class"):
                    content += b"VALUES('onboarding',2)"
                archive.writestr(name, content)
            if private:
                archive.writestr("BOOT-INF/classes/private/runtime.env", "synthetic-private")
        return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def observation(self, directory, status="RUNNING", age_seconds=0):
        value = {
            "status": status, "observationId": "soak-" + "a" * 32,
            "updatedAt": (datetime.now(timezone.utc) - timedelta(seconds=age_seconds)).isoformat(),
            "elapsedMonotonicSeconds": 61, "completed24Hours": False,
            "continuous": True, "requestedSeconds": 86400,
            "privatePath": "D:/not-for-export/private/session.json",
        }
        path = directory / "observation.json"
        path.write_text(json.dumps(value))
        return path

    def git(self, root, *arguments):
        subprocess.run(["git", "-C", str(root), *arguments], check=True, capture_output=True)

    def test_phase6_allowlist_rejects_missing_files_private_files_and_symlinks(self):
        selected = bundle.platform_files(self.entries())
        self.assertIn("docs/phase6-onboarding.md", selected)
        self.assertNotIn("docs/validation/acceptance.json", selected)
        for name, mode in [
            (bundle.PHASE6_PREFIX + "private/runtime.env", "100644"),
            (bundle.PHASE6_PREFIX + "unreviewed.sh", "100644"),
            (bundle.PHASE6_PREFIX + "support.service", "120000"),
        ]:
            with self.assertRaises(ValueError):
                bundle.platform_files(self.entries() + [(name, mode, "fixture")])
        with self.assertRaises(ValueError):
            bundle.platform_files([e for e in self.entries() if not e[0].endswith("phase6-onboarding.md")])
        with self.assertRaises(ValueError):
            bundle.ops_files([])

    def test_every_repository_requires_clean_develop_commit(self):
        with patch.object(bundle.previous, "git", return_value=b" M dirty.java\n"):
            with self.assertRaisesRegex(ValueError, "clean committed"):
                bundle.previous.clean_commit(Path("."))
        with patch.object(bundle.previous, "git", side_effect=[b"", b"a" * 40, b"main"]):
            with self.assertRaisesRegex(ValueError, "develop/"):
                bundle.previous.clean_commit(Path("."))

    def test_role_sha_required_classes_schema2_and_resource_privacy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            spec = self.jar(root, "platform")
            self.assertEqual(spec["sha256"], bundle.sha(bundle.checked_jar(root, "platform", spec)))
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                bundle.checked_jar(root, "platform", {**spec, "sha256": "0" * 64})
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                bundle.checked_jar(root, "platform", {**spec, "sha256": None})
            with self.assertRaisesRegex(ValueError, "matching module"):
                bundle.checked_jar(root, "support", spec)
            with self.assertRaisesRegex(ValueError, "schema 2"):
                bundle.checked_jar(root, "platform", self.jar(root, "platform", old_schema=True))
            with self.assertRaisesRegex(ValueError, "Private resource"):
                bundle.checked_jar(root, "platform", self.jar(root, "platform", private=True))

    def test_frontend_tree_is_complete_hashed_and_has_no_extra_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            dist = root / "ops-web/dist"
            (dist / "assets").mkdir(parents=True)
            (dist / "index.html").write_text("<html>synthetic</html>")
            (dist / "assets/app-123.js").write_text("/* synthetic */")
            first = bundle.frontend_digest(bundle.frontend_files(root, dist))
            (dist / "assets/app-123.js").write_text("/* changed */")
            self.assertNotEqual(first, bundle.frontend_digest(bundle.frontend_files(root, dist)))
            (dist / ".env").write_text("SYNTHETIC=1")
            with self.assertRaisesRegex(ValueError, "Unreviewed or private"):
                bundle.frontend_files(root, dist)

    def test_short_run_cannot_claim_24hours_and_stale_running_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = self.observation(root)
            summary = bundle.observation_summary(path)
            self.assertEqual("RUNNING", summary["status"])
            self.assertNotIn("privatePath", summary)
            with self.assertRaisesRegex(ValueError, "Stale"):
                bundle.observation_summary(self.observation(root, age_seconds=181))
            with self.assertRaisesRegex(ValueError, "actual continuous"):
                bundle.observation_summary(self.observation(root, status="PASSED"))
            path = self.observation(root, status="SHORT_RUN_PASSED")
            self.assertFalse(bundle.observation_summary(path)["completed24Hours"])
            value = json.loads(path.read_text())
            value["completed24Hours"] = True
            path.write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError, "Incomplete"):
                bundle.observation_summary(path)

    def test_complete_observation_requires_all_evidence_flags(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.observation(Path(directory), status="PASSED")
            report = json.loads(path.read_text())
            report.update(elapsedMonotonicSeconds=86401, completed24Hours=True,
                          completedWindows=1440, qualityPassed=True, requests={"errors": 0})
            path.write_text(json.dumps(report))
            self.assertEqual("PASSED", bundle.observation_summary(path)["status"])
            report["continuous"] = False
            path.write_text(json.dumps(report))
            with self.assertRaises(ValueError):
                bundle.observation_summary(path)

    def test_readback_rejects_tampering_unlisted_files_and_traversal(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "archive.zip"
            data = b"synthetic"
            entry = bundle.previous.JAR_ENTRY
            manifest = {
                "artifacts": {"platform": {"entry": entry, "sha256": bundle.sha(data)}},
                "files": {entry: {"sha256": bundle.sha(data), "bytes": len(data)}},
            }
            for payload, additional, error in [
                (b"changed", None, "read-back"),
                (data, "unlisted.txt", "contents differ"),
                (data, "../outside", "Unsafe"),
            ]:
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr(entry, payload)
                    archive.writestr(bundle.MANIFEST_ENTRY, json.dumps(manifest))
                    if additional:
                        archive.writestr(additional, "synthetic")
                with self.assertRaisesRegex(ValueError, error):
                    bundle.verify_bundle(path)

    def test_real_two_clean_git_trees_build_verified_bundle_without_private_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            ops = root / ".work/opsagent-phase6"
            ops.mkdir(parents=True)
            for repo, entries in [(root, self.entries()),
                                  (ops, [(name, "", "") for name in bundle.OPS_FILES])]:
                self.git(repo, "init", "-b", "develop/bundle-fixture")
                (repo / ".gitignore").write_text(".work/\n**/target/\nops-web/dist/\n")
                for name, _, _ in entries:
                    target = repo / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text("public synthetic deployment fixture\n")
                self.git(repo, "add", ".")
                self.git(repo, "-c", "user.name=Bundle Test", "-c", "user.email=bundle@example.invalid",
                         "commit", "-m", "Synthetic public bundle fixture")
            specs = {role: self.jar(root if repo_name == "platform" else ops, role)
                     for role, (repo_name, _, _) in bundle.ARTIFACTS.items()}
            dist = ops / "ops-web/dist"
            (dist / "assets").mkdir(parents=True)
            (dist / "index.html").write_text("<html>public synthetic</html>")
            (dist / "assets/app-1.js").write_text("/* public synthetic */")
            specs["ops-web"] = {"path": str(dist), "sha256": bundle.frontend_digest(bundle.frontend_files(ops, dist))}
            manifest_path = root / ".work/artifacts.json"
            manifest_path.write_text(json.dumps(specs))
            observation = self.observation(root / ".work")
            result = bundle.build(root, ops, manifest_path, observation)
            self.assertTrue(result["readBackVerified"])
            built = bundle.verify_bundle(Path(result["bundle"]))
            self.assertEqual({"platform", "opsagent"}, set(built["repositories"]))
            self.assertEqual("RUNNING", built["local24HourObservation"]["status"])
            self.assertEqual("PENDING_TARGET_ENVIRONMENT", built["linux7DayObservation"])
            self.assertFalse(built["productionAcceptance"])
            with zipfile.ZipFile(result["bundle"]) as archive:
                self.assertTrue(all(".work/" not in name and "observation.json" not in name
                                    for name in archive.namelist()))
            with self.assertRaisesRegex(ValueError, "cannot be overwritten"):
                bundle.build(root, ops, manifest_path, observation)


if __name__ == "__main__":
    unittest.main()
