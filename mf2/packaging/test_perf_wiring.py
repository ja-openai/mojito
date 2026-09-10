"""Exercise production shell drivers with recording tools, without timing builds."""

import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

# The fake toolchain records actual driver arguments and creates runnable stubs
# for build outputs. No benchmark values from these tools are used as evidence.
TOOL = r'''
import json, os, pathlib, shutil, sys
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
if name == 'record': name, args = args[0], args[1:]
root = pathlib.Path(os.environ['MF2_TEST_ROOT'])
with open(os.environ['MF2_TOOL_LOG'], 'a') as log:
    log.write(json.dumps({'tool': name, 'args': args, 'cwd': os.getcwd(),
      'fixtures': os.environ.get('MF2_BENCH_FIXTURES'), 'warmup': os.environ.get('MF2_BENCH_WARMUP')}) + '\n')
def executable(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(sys.argv[0], path)
    path.chmod(0o755)
if name == 'cargo' and args[0] == 'build':
    executable(root / 'rust/mojito-mf2/target/release/mojito-mf2')
elif name == 'cargo' and args[0] == 'metadata':
    print(json.dumps({'target_directory': str(root / 'rust/mojito-mf2/target')}))
elif name == 'swift' and args[0] == 'build':
    if '--show-bin-path' in args: print(root / 'swift/MessageFormat2/.build/release')
    else: executable(root / 'swift/MessageFormat2/.build/release/MessageFormat2Conformance')
elif name == 'go' and '-c' in args:
    executable(pathlib.Path(args[args.index('-o') + 1]))
'''


class PerfDriverTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="mf2 perf wiring ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        (self.root / "perf").mkdir()
        for name in ("profile.sh", "compare_parse.sh", "corpus_manifest.py"):
            shutil.copyfile(ROOT / "perf" / name, self.root / "perf" / name)
        self.fixtures = self.root / "custom fixtures"
        self.fixtures.mkdir()
        (self.fixtures / "one.json").write_text('{"source":"ok","formatCases":[{"expected":"ok"}]}')
        self.invalid = self.root / "custom invalid"
        self.invalid.mkdir()
        (self.invalid / "bad.json").write_text('{"source":"{","expectedError":{"code":"syntax-error"}}')
        self.bin = self.root / "tools"
        self.bin.mkdir()
        tool = "#!" + sys.executable + "\n" + TOOL
        for name in ("cargo", "swift", "go", "node", "php", "record", "npm"):
            path = self.bin / name
            path.write_text(tool)
            path.chmod(0o755)
        for name in ("rust/mojito-mf2", "swift/MessageFormat2", "go", "php", "javascript/tools", "java", "kotlin", "python", "reference/icu4j", "reference/icu4cxx"):
            directory = self.root / name
            directory.mkdir(parents=True)
            (directory / "run.sh").write_text("#!/bin/sh\nexec record " + shlex.quote(name) + ' "$@"\n')
        self.log = self.root / "tools.jsonl"
        self.env = {**os.environ, "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
                    "MF2_TEST_ROOT": str(self.root), "MF2_TOOL_LOG": str(self.log)}

    def run_driver(self, name, *args):
        result = subprocess.run(["sh", str(self.root / "perf" / name), *map(str, args)],
                                env=self.env, text=True, capture_output=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        events = [json.loads(line) for line in self.log.read_text().splitlines()]
        manifests = [json.loads(line) for line in result.stdout.splitlines() if line.startswith('{"fixtureRoot"')]
        return events, manifests

    def test_rss_prepares_before_sampling_and_forwards_custom_corpus(self):
        events, manifests = self.run_driver("profile.sh", "rss", self.fixtures, 3, 2)
        self.assertEqual(str(self.fixtures), manifests[0]["fixtureRoot"])
        first_run = next(index for index, event in enumerate(events) if event["tool"] == "mojito-mf2")
        for index, event in enumerate(events):
            if "--prepare-only" in event["args"] or event["args"][:1] == ["build"] or "-c" in event["args"]:
                self.assertLess(index, first_run, event)
        cpp = next(event for event in events if event["tool"] == "reference/icu4cxx" and "--prepare-only" in event["args"])
        self.assertEqual(["--prepare-only", str(self.fixtures)], cpp["args"])
        cpp_run = next(event for event in events if event["tool"] == "reference/icu4cxx" and "--no-prepare" in event["args"])
        self.assertEqual(["--no-prepare", "bench", "3", "2"], cpp_run["args"])
        go = next(event for event in events if event["tool"] == "go.test")
        self.assertEqual(str(self.fixtures), go["fixtures"])
        self.assertEqual("2", go["warmup"])
        self.assertIn("3x", go["args"])
        for name in ("mojito-mf2", "MessageFormat2Conformance", "python", "node", "java", "kotlin", "php", "reference/icu4j"):
            calls = [event for event in events if event["tool"] == name and "--prepare-only" not in event["args"]]
            self.assertTrue(calls, name)
            self.assertTrue(any(str(self.fixtures) in event["args"] for event in calls), name)

    def test_python_cpu_profile_has_its_own_corpus_manifest(self):
        events, manifests = self.run_driver("profile.sh", "python-cpu", self.fixtures, 3, 2)
        self.assertEqual(1, len(manifests))
        self.assertEqual("python-cpu", manifests[0]["mode"])
        self.assertEqual(["profile", str(self.fixtures), "3", "2"], events[0]["args"])

    def test_parse_comparison_identifies_and_forwards_both_corpora(self):
        events, manifests = self.run_driver("compare_parse.sh", self.fixtures, self.invalid, 3, 2)
        self.assertEqual([str(self.fixtures), str(self.invalid)], [manifest["fixtureRoot"] for manifest in manifests])
        self.assertNotEqual(manifests[0]["corpusSha256"], manifests[1]["corpusSha256"])
        go = [event for event in events if event["tool"] == "go"]
        self.assertEqual([str(self.fixtures), str(self.invalid)], [event["fixtures"] for event in go])

    def test_zero_work_cannot_be_reported_as_a_profile(self):
        for iterations, warmup in ((0, 0), (-1, 0), (1, -1)):
            result = subprocess.run(["sh", str(self.root / "perf/profile.sh"), "python-cpu", str(self.fixtures),
                                     str(iterations), str(warmup)], env=self.env, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Iterations must be positive", result.stderr)
        self.assertFalse(self.log.exists())


if __name__ == "__main__":
    unittest.main()
