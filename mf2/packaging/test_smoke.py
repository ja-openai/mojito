"""Verify clean source staging and fail-closed archive notice validation."""

from pathlib import Path
import io
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from smoke import NOTICES, PYTHON_LICENSE_EXPRESSION, Smoke


class ArtifactNoticeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="mf2-package-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        for name in NOTICES:
            (self.source / name).write_text("expected " + name)
        self.smoke = object.__new__(Smoke)
        self.smoke.source = self.source
        self.smoke.results = []

    def archive(self, kind, *, missing=None, changed=None, extra_entries=None):
        extension = {"zip": ".zip", "wheel": ".whl", "tar": ".tar.gz"}[kind]
        path = self.root / ("package" + extension)
        values = {
            "package/" + name: (
                "changed" if name == changed else "expected " + name
            ).encode()
            for name in NOTICES
            if name != missing
        }
        values.update(extra_entries or {})
        if kind in ("zip", "wheel"):
            with zipfile.ZipFile(path, "w") as archive:
                for name, data in values.items():
                    archive.writestr(name, data)
        else:
            with tarfile.open(path, "w:gz") as archive:
                for name, data in values.items():
                    entry = tarfile.TarInfo(name)
                    entry.size = len(data)
                    archive.addfile(entry, io.BytesIO(data))
        return path

    def test_accepts_exact_notices_and_records_hash(self):
        for kind in ("zip", "tar"):
            with self.subTest(kind=kind):
                self.smoke.artifact(self.archive(kind))
                self.assertEqual(len(self.smoke.results[-1]["sha256"]), 64)
                self.assertGreater(self.smoke.results[-1]["bytes"], 0)

    def test_rejects_each_missing_notice(self):
        for kind in ("zip", "tar"):
            for notice in NOTICES:
                with self.subTest(kind=kind, notice=notice), self.assertRaisesRegex(
                    ValueError, notice
                ):
                    self.smoke.artifact(self.archive(kind, missing=notice))

    def test_rejects_modified_notice(self):
        for kind in ("zip", "tar"):
            with self.subTest(kind=kind), self.assertRaisesRegex(
                ValueError, "UNICODE-LICENSE"
            ):
                self.smoke.artifact(self.archive(kind, changed="UNICODE-LICENSE.txt"))

    def test_accepts_python_distribution_license_expression(self):
        for kind, metadata_path in (
            ("wheel", "mojito_mf2-0.1.0.dist-info/METADATA"),
            ("tar", "mojito_mf2-0.1.0/PKG-INFO"),
        ):
            with self.subTest(kind=kind):
                path = self.archive(
                    kind,
                    extra_entries={
                        metadata_path: (
                            f"License-Expression: {PYTHON_LICENSE_EXPRESSION}\n".encode()
                        )
                    },
                )
                self.smoke.artifact(
                    path, python_license_expression=PYTHON_LICENSE_EXPRESSION
                )

    def test_rejects_missing_or_incorrect_python_distribution_expression(self):
        for kind, metadata_path in (
            ("wheel", "mojito_mf2-0.1.0.dist-info/METADATA"),
            ("tar", "mojito_mf2-0.1.0/PKG-INFO"),
        ):
            for metadata in (
                None,
                b"Name: mojito-mf2\n",
                b"License-Expression: Apache-2.0\n",
            ):
                with self.subTest(kind=kind, metadata=metadata), self.assertRaisesRegex(
                    ValueError, "License-Expression"
                ):
                    path = self.archive(
                        kind,
                        extra_entries=(
                            {} if metadata is None else {metadata_path: metadata}
                        ),
                    )
                    self.smoke.artifact(
                        path, python_license_expression=PYTHON_LICENSE_EXPRESSION
                    )


class SourceStagingTest(unittest.TestCase):
    def test_mypy_cache_is_not_staged_or_hashed(self):
        with tempfile.TemporaryDirectory(prefix="mf2-source-staging-") as temp:
            root = Path(temp)
            package = root / "repo/mf2/python"
            package.mkdir(parents=True)
            (root / "repo/LICENSE").write_text("Apache license fixture")
            (package / "LICENSE").write_text("Apache license fixture")
            (package / "NOTICE").write_text("Includes Unicode data")
            (package / "UNICODE-LICENSE.txt").write_text("UNICODE LICENSE V3")
            module = package / "library.py"
            module.write_text("value = 1\n")
            with patch("smoke.ROOT", root / "repo/mf2"):
                clean = Smoke("python", root / "clean")
                cache = package / ".mypy_cache/3.12/cache.3.db"
                cache.parent.mkdir(parents=True)
                cache.write_bytes(b"first generated cache")
                cached = Smoke("python", root / "cached")
                self.assertFalse((cached.source / ".mypy_cache").exists())
                self.assertEqual(clean.source_sha256, cached.source_sha256)
                cache.write_bytes(b"updated generated cache")
                refreshed = Smoke("python", root / "refreshed")
                self.assertFalse((refreshed.source / ".mypy_cache").exists())
                self.assertEqual(clean.source_sha256, refreshed.source_sha256)
                module.write_text("value = 2\n")
                changed = Smoke("python", root / "changed")
                self.assertNotEqual(clean.source_sha256, changed.source_sha256)


if __name__ == "__main__":
    unittest.main()
