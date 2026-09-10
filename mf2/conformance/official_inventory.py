"""Verify the complete, revision-pinned upstream corpus before running a bridge."""

from collections import Counter
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
VENDOR_ROOT = ROOT / "third_party/message-format-wg"
DEFAULT_TEST_ROOT = VENDOR_ROOT / "test/tests"
INVENTORY_PATH = Path(__file__).with_name("unicode-official-inventory.json")
UPSTREAM_REPOSITORY = "https://github.com/unicode-org/message-format-wg"
ASSERTIONS = ("errors", "output", "parts", "partsErrors")


def read_suite(path):
    suite = json.loads(path.read_text())
    if not isinstance(suite, dict) or not isinstance(suite.get("tests"), list):
        raise ValueError(f"Official suite must contain a tests array: {path}")
    defaults = suite.get("defaultTestProperties", {})
    if not isinstance(defaults, dict):
        raise ValueError(f"Invalid defaultTestProperties: {path}")
    tests = []
    for item in suite["tests"]:
        if not isinstance(item, dict):
            raise ValueError(f"Official test must be an object: {path}")
        test = {**defaults, **item}
        if not isinstance(test.get("src"), str):
            raise ValueError(f"Official test must contain a string src: {path}")
        tests.append(test)
    return tests


def assertion_counts(tests):
    counts = Counter({name: 0 for name in ASSERTIONS})
    for test in tests:
        counts["errors"] += 1
        counts["output"] += "exp" in test
        counts["parts"] += "expParts" in test
        counts["partsErrors"] += "expParts" in test
    return dict(counts)


def read_tests(test_root):
    tests = []
    for path in sorted(test_root.rglob("*.json")):
        tests.extend((f"{path.relative_to(test_root).as_posix()}#{index}", test)
                     for index, test in enumerate(read_suite(path), 1))
    if not tests:
        raise ValueError("No official tests found")
    return tests


def corpus_snapshot(vendor_root, revision):
    """Describe bytes and denominators; never update the committed inventory."""
    paths = [vendor_root / "LICENSE", *sorted((vendor_root / "test").rglob("*"))]
    files, tests = {}, []
    test_files = 0
    for path in paths:
        if path.is_symlink():
            raise ValueError(f"Upstream corpus path must not be a symlink: {path}")
        if not path.is_file():
            continue
        relative = path.relative_to(vendor_root).as_posix()
        file_tests = []
        if relative.startswith("test/tests/") and path.suffix == ".json":
            file_tests = read_suite(path)
            tests.extend(file_tests)
            test_files += 1
        files[relative] = {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                           "tests": len(file_tests), "assertions": assertion_counts(file_tests)}
    counts = assertion_counts(tests)
    return {"schemaVersion": 1,
            "upstream": {"repository": UPSTREAM_REPOSITORY, "revision": revision},
            "files": files,
            "totals": {"files": len(files), "testFiles": test_files, "tests": len(tests),
                       "assertions": counts, "assertionCount": sum(counts.values())}}


def validate_inventory(vendor_root=VENDOR_ROOT, inventory_path=INVENTORY_PATH):
    expected = json.loads(inventory_path.read_text())
    if expected.get("schemaVersion") != 1 or expected.get("upstream", {}).get("repository") != UPSTREAM_REPOSITORY:
        raise ValueError("Invalid official upstream inventory metadata")
    revision = expected["upstream"].get("revision", "")
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Official upstream inventory must pin a full Git revision")
    provenance = (vendor_root / "README.md").read_text()
    documented = re.search(r"Vendored commit: `([0-9a-f]{40})`", provenance)
    if not documented or documented.group(1) != revision:
        raise ValueError("Vendored upstream revision differs from the official inventory")
    if vendor_root.is_symlink() or (vendor_root / "test").is_symlink():
        raise ValueError("Vendored upstream root must not be a symlink")
    actual = corpus_snapshot(vendor_root, revision)
    expected_files, actual_files = expected["files"], actual["files"]
    missing = sorted(expected_files.keys() - actual_files.keys())
    extra = sorted(actual_files.keys() - expected_files.keys())
    changed = sorted(path for path in expected_files.keys() & actual_files.keys()
                     if expected_files[path] != actual_files[path])
    if missing or extra or changed:
        raise ValueError(f"Official upstream inventory mismatch: missing={missing}, extra={extra}, changed={changed}")
    if expected["totals"] != actual["totals"]:
        raise ValueError("Official upstream inventory test/assertion denominator mismatch")
    if not actual["totals"]["tests"]:
        raise ValueError("Official upstream inventory contains no tests")
    return {"verified": True, "upstream": expected["upstream"], "totals": expected["totals"],
            "manifestSha256": hashlib.sha256(inventory_path.read_bytes()).hexdigest()}
