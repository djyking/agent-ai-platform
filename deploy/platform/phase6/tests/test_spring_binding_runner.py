from contextlib import redirect_stderr
import io
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

import run_spring_binding as runner


def archive(path, additions=(), missing=()):
    with warnings.catch_warnings(), zipfile.ZipFile(path, "w") as output:
        warnings.simplefilter("ignore", UserWarning)
        for name in runner.ARTIFACTS:
            if name not in missing:
                output.writestr(f"BOOT-INF/lib/{name}-1.0.jar", b"synthetic-jar")
        output.writestr("BOOT-INF/classes/DoNotExtract.class", b"not-loaded")
        for name, data in additions:
            if isinstance(name, str):
                raw = zipfile.ZipInfo(name)
                # Windows ZipInfo normally normalizes backslashes on construction.
                raw.filename = name
                name = raw
            output.writestr(name, data)


class SpringBindingRunnerTests(unittest.TestCase):
    def test_extracts_only_required_libraries_into_flat_new_files(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            jar = root / "application.jar"
            archive(jar, [("BOOT-INF/lib/unrelated-9.0.jar", b"not-extracted")])
            destination = root / "dependencies"
            destination.mkdir()
            extracted = runner.extract_dependencies(jar, destination)
            self.assertEqual(len(runner.ARTIFACTS), len(extracted))
            self.assertEqual(set(extracted), set(destination.iterdir()))
            self.assertTrue(all(path.parent == destination for path in extracted))
            self.assertTrue(all(path.read_bytes() == b"synthetic-jar" for path in extracted))
            with self.assertRaises(FileExistsError):
                runner.extract_dependencies(jar, destination)

    def test_missing_required_and_multiple_versions_are_rejected_before_extraction(self):
        cases = [
            ([], ["spring-boot"]),
            ([("BOOT-INF/lib/spring-boot-2.0.jar", b"duplicate-artifact")], []),
        ]
        for additions, missing in cases:
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                jar = root / "application.jar"
                archive(jar, additions, missing)
                destination = root / "dependencies"
                destination.mkdir()
                with self.assertRaises(runner.InvalidArtifact):
                    runner.extract_dependencies(jar, destination)
                self.assertEqual([], list(destination.iterdir()))

    def test_duplicate_or_unsafe_paths_anywhere_in_archive_are_rejected(self):
        for path in ("BOOT-INF/lib/spring-boot-1.0.jar", "../outside", "/absolute",
                     "BOOT-INF/../escape", "BOOT-INF//extra", r"BOOT-INF\lib\extra.jar",
                     "C:/absolute", "BOOT-INF/./extra"):
            with self.subTest(path=path), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                jar = root / "application.jar"
                archive(jar, [(path, b"never-written")])
                destination = root / "dependencies"
                destination.mkdir()
                with self.assertRaises(runner.InvalidArtifact):
                    runner.extract_dependencies(jar, destination)
                self.assertEqual([], list(destination.iterdir()))

    def test_symlinks_and_oversized_selected_libraries_are_rejected(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            jar = root / "application.jar"
            link = zipfile.ZipInfo("unrelated-link")
            link.create_system = 3
            link.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive(jar, [(link, b"outside")])
            with self.assertRaises(runner.InvalidArtifact):
                runner.extract_dependencies(jar, root)
            archive(jar)
            with patch.object(runner, "MAX_LIBRARY_BYTES", 4), self.assertRaises(runner.InvalidArtifact):
                runner.extract_dependencies(jar, root)

    def test_missing_or_invalid_artifact_cannot_start_binding_or_echo_raw_error(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            broken = root / "SYNTHETIC_PRIVATE.jar"
            broken.write_bytes(b"SYNTHETIC_PRIVATE_INVALID_ZIP")
            for path in (root / "missing.jar", broken):
                with self.subTest(path=path), patch.object(runner, "execute_binding") as execute, \
                        redirect_stderr(io.StringIO()) as stderr:
                    result = runner.main(["--jar", str(path)])
                    self.assertEqual(2, result)
                    execute.assert_not_called()
                    self.assertNotIn("SYNTHETIC_PRIVATE", stderr.getvalue())

    def test_exactly_one_success_with_no_skip_is_required(self):
        for tests, skipped, failures, errors, expected in (
            (1, [], [], [], True), (0, [], [], [], False),
            (2, [], [], [], False), (1, [("test", "no jars")], [], [], False),
            (1, [], [("test", "failed")], [], False),
            (1, [], [], [("test", "error")], False),
        ):
            with self.subTest(tests=tests, skipped=skipped, failures=failures, errors=errors):
                result = unittest.TestResult()
                result.testsRun = tests
                result.skipped, result.failures, result.errors = skipped, failures, errors
                self.assertEqual(expected, runner.accepted_result(result))


if __name__ == "__main__":
    unittest.main()
