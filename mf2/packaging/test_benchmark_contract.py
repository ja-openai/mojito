"""Exercise real benchmark drivers against options and corrupted expectations."""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class BenchmarkContractTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="mf2 benchmark contract ")
        self.addCleanup(self.temporary.cleanup)
        self.fixtures = Path(self.temporary.name)
        self.path = self.fixtures / "fixture.json"
        self.fixture = json.loads((ROOT / "conformance/fixtures/source-to-model/bidi-numeric-direction.json").read_text())
        self.env = {**os.environ, "PYTHONPATH": str(ROOT / "python/src"), "PYTHONDONTWRITEBYTECODE": "1"}

    def format_commands(self):
        commands = [[sys.executable, str(ROOT / "python/tools/benchmark.py")],
                    [sys.executable, str(ROOT / "python/tools/profiler.py")]]
        if node := shutil.which("node"):
            commands.append([node, str(ROOT / "javascript/tools/benchmark.js")])
        return commands

    def invoke(self, command, iterations=3):
        self.path.write_text(json.dumps(self.fixture))
        return subprocess.run([*command, str(self.fixtures), str(iterations), "0"],
                              env=self.env, text=True, capture_output=True, timeout=10)

    def test_options_and_utf8_checksum_are_used_by_actual_drivers(self):
        expected = sum(len(case["expected"].encode("utf-8")) for case in self.fixture["formatCases"][:3])
        for command in self.format_commands():
            with self.subTest(command=command):
                result = self.invoke(command)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                checksum = re.search(r"(?:bytes|checksum)=(\d+)", result.stdout)
                self.assertIsNotNone(checksum, result.stdout)
                self.assertEqual(expected, int(checksum.group(1)))

    def test_all_cases_are_checked_before_timing(self):
        # The corrupt case is beyond the single requested timed iteration.
        self.fixture["formatCases"][-1]["expected"] = "incorrect benchmark expectation"
        for command in self.format_commands():
            with self.subTest(command=command):
                result = self.invoke(command, iterations=1)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("preflight", result.stdout + result.stderr)
                self.assertNotIn("ops_per_second=", result.stdout)

    def test_parser_expectations_are_checked_before_timing(self):
        self.fixture["expectedDiagnostics"] = [{"code": "syntax-error"}]
        commands = [[sys.executable, str(ROOT / "python/tools/benchmark.py"), "--parse"]]
        if node := shutil.which("node"):
            commands.append([node, str(ROOT / "javascript/tools/parse-benchmark.js")])
        for command in commands:
            with self.subTest(command=command):
                result = self.invoke(command)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("preflight", result.stdout + result.stderr)
                self.assertNotIn("ops_per_second=", result.stdout)


if __name__ == "__main__":
    unittest.main()
