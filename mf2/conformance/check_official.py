#!/usr/bin/env python3
"""Check every upstream assertion through a production runtime JSON-lines bridge.

Unlike aggregate pass counts, a disposition identifies one assertion and pins its
test content and observed result. Changed failures and unexpected passes fail the
gate so a reviewed limitation cannot hide a new regression.
"""

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys

from official_inventory import ASSERTIONS, DEFAULT_TEST_ROOT, VENDOR_ROOT, assertion_counts, read_tests, validate_inventory

ROOT = Path(__file__).resolve().parents[1]
DATA_ERRORS = {
    "variant-key-count-mismatch", "missing-fallback-variant", "missing-selector-annotation",
    "duplicate-declaration", "duplicate-option-name", "duplicate-variant",
}


def digest(value):
    encoded = json.dumps(value, sort_keys=True, ensure_ascii=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode()).hexdigest()


def normalized_parts(parts):
    """Rename equivalent generic string/direction fields; preserve every value.

    No options are resolved and no numeric subparts or isolation parts are
    fabricated. Such API differences must have an explicit disposition.
    """
    result = []
    for part in parts:
        part = dict(part)
        if part.get("type") == "expression":
            part["type"] = "string"
        if "direction" in part:
            part["dir"] = part.pop("direction")
        # MF2 attributes are annotations that the Mojito API retains as metadata.
        # The upstream parts schema deliberately has no attributes property.
        part.pop("attributes", None)
        result.append(part)
    return result


def run_bridge(command, requests, timeout=120):
    payload = "\n".join(json.dumps(request, ensure_ascii=True) for request in requests) + "\n"
    # Isolate wrappers and their children so a timed-out parser cannot keep
    # running after the gate has failed (or keep its inherited pipes open).
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               text=True, start_new_session=os.name == "posix")
    try:
        stdout, stderr = process.communicate(payload, timeout=timeout)
    except subprocess.TimeoutExpired:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGKILL)
        else:
            process.kill()
        process.communicate()
        raise
    if process.returncode:
        raise RuntimeError(f"Bridge failed ({process.returncode}): {stderr[-8000:]}")
    lines = stdout.splitlines()
    if len(lines) != len(requests):
        raise RuntimeError(f"Bridge returned {len(lines)} lines for {len(requests)} requests: {stdout[:1000]}")
    responses = [json.loads(line) for line in lines]
    for index, response in enumerate(responses):
        if not isinstance(response, dict):
            raise RuntimeError(f"Bridge request {index + 1}: response must be an object")
        if "transportError" in response:
            raise RuntimeError(f"Bridge request {index + 1}: {response['transportError']}")
        for field in ("diagnostics", "errors"):
            if field not in response and field == "errors" and response.get("diagnostics"):
                continue  # Parsing failed before formatting could run.
            if not isinstance(response.get(field), list) or not all(isinstance(code, str) for code in response[field]):
                raise RuntimeError(f"Bridge request {index + 1}: {field} must be a list of diagnostic codes")
    return responses


def check_case(test, actual):
    expected_errors = ["variant-key-count-mismatch" if item["type"] == "variant-key-mismatch" else item["type"] for item in test.get("expErrors", [])]
    diagnostics = actual.get("diagnostics", [])
    errors = ([code if code in DATA_ERRORS else "syntax-error" for code in diagnostics]
              if diagnostics else actual.get("errors", []))
    if diagnostics and errors and all(code == "syntax-error" for code in errors):
        errors = ["syntax-error"]
    checks = {"errors": (sorted(expected_errors), sorted(errors))}
    if "exp" in test:
        checks["output"] = (test["exp"], actual.get("value"))
    if "expParts" in test:
        checks["parts"] = (test["expParts"], normalized_parts(actual.get("parts", [])))
        # An omitted response field is an assertion failure, never a skipped
        # check. A broken bridge must not make its own denominator smaller.
        parts_errors = actual.get("partsErrors")
        checks["partsErrors"] = (sorted(expected_errors), sorted(parts_errors) if isinstance(parts_errors, list) else None)
    return checks


def run(args):
    custom_root = args.test_root.resolve() != DEFAULT_TEST_ROOT.resolve()
    if custom_root and not args.allow_custom_test_root:
        raise ValueError("A custom --test-root requires --allow-custom-test-root; normal gates verify the pinned vendored corpus")
    if not custom_root and args.allow_custom_test_root:
        raise ValueError("--allow-custom-test-root cannot disable the vendored corpus inventory")
    inventory = ({"verified": False, "reason": "explicit custom test root"} if custom_root
                 else validate_inventory(VENDOR_ROOT))
    tests = read_tests(args.test_root)
    expected_counts = assertion_counts(test for _, test in tests)
    if inventory["verified"] and (len(tests) != inventory["totals"]["tests"]
                                  or expected_counts != inventory["totals"]["assertions"]):
        raise ValueError("Loaded official test/assertion denominator differs from the pinned inventory")
    requests = [{"source": test["src"], "arguments": {param["name"]: param["value"] for param in test.get("params", [])},
                 "locale": test.get("locale", "en"), "bidiIsolation": test.get("bidiIsolation", "none"), "registry": args.registry}
                for _, test in tests]
    responses = run_bridge(args.command, requests, args.timeout)
    dispositions = json.loads(args.dispositions.read_text()) if args.dispositions else {}
    counts = Counter()
    differences, unused = [], set(dispositions)
    for (case_id, test), actual in zip(tests, responses):
        for assertion, (expected, observed) in check_case(test, actual).items():
            counts[f"{assertion}_checked"] += 1
            key = f"{case_id}/{assertion}"
            if expected == observed:
                counts["passed"] += 1
                continue
            difference = {"id": key, "testSha256": digest(test), "expected": expected, "actual": observed}
            disposition = dispositions.get(key)
            if disposition and disposition.get("reason") and disposition.get("testSha256") == difference["testSha256"] and disposition.get("actual") == observed:
                unused.discard(key)
                counts["knownDifference"] += 1
                difference["reason"] = disposition["reason"]
            else:
                counts["failed"] += 1
            differences.append(difference)
    observed_counts = {name: 0 for name in ASSERTIONS}
    observed_counts.update({name.removesuffix("_checked"): value for name, value in counts.items() if name.endswith("_checked")})
    if observed_counts != expected_counts:
        raise ValueError(f"Official assertion coverage mismatch: expected={expected_counts}, observed={observed_counts}")
    report = {"runtime": args.runtime, "registry": args.registry, "tests": len(tests), "assertions": dict(counts),
              "inventory": inventory,
              "differences": differences, "staleDispositions": sorted(unused)}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=True, indent=2) + "\n")
    print(f"{args.runtime}/{args.registry}: tests={len(tests)} assertions={dict(counts)} stale_dispositions={len(unused)}")
    for difference in differences[:8]:
        print(f"  {difference['id']}: expected={str(difference['expected'])[:100]!r} actual={str(difference['actual'])[:100]!r}")
    return int(bool(counts["failed"] or unused))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", required=True)
    parser.add_argument("--registry", choices=["portable", "platform"], default="portable")
    parser.add_argument("--test-root", type=Path, default=DEFAULT_TEST_ROOT)
    parser.add_argument("--allow-custom-test-root", action="store_true",
                        help="Explicit developer mode for a synthetic/external corpus; report is not inventory-verified")
    parser.add_argument("--dispositions", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.command[:1] == ["--"]:
        args.command.pop(0)
    if not args.command:
        parser.error("Pass a bridge command after --")
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
