"""Regression checks for failing language tools propagating through shell gates."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which("php"), "PHP is required to exercise its real syntax checker")
class PhpSyntaxGateTest(unittest.TestCase):
    def test_checks_every_file_and_rejects_syntax_failure(self):
        with tempfile.TemporaryDirectory(prefix="mf2-php-syntax-") as temporary:
            root = Path(temporary) / "package with spaces"
            for directory in ("src", "tests", "examples"):
                (root / directory).mkdir(parents=True)
                (root / directory / "valid.php").write_text("<?php function valid_" + directory + "() {}\n")
            command = ["sh", str(ROOT / "check_php_syntax.sh"), str(root)]
            valid = subprocess.run(command, text=True, capture_output=True, timeout=10)
            self.assertEqual(0, valid.returncode, valid.stderr)
            self.assertEqual(3, valid.stdout.count("No syntax errors detected"))
            (root / "examples" / "broken example.php").write_text("<?php function broken( {\n")
            invalid = subprocess.run(command, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, invalid.returncode)
            self.assertIn("broken example.php", invalid.stdout + invalid.stderr)


if __name__ == "__main__":
    unittest.main()
