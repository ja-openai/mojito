#!/usr/bin/env python3
"""Check original Apple strings fixtures against Foundation's actual plist parser."""

from __future__ import annotations

import base64
import datetime
import json
import plistlib
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def main() -> int:
    executable = shutil.which("plutil")
    if executable is None:
        raise SystemExit("Apple plutil is unavailable; run this oracle on macOS")

    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    cases = [
        case
        for case in manifest["cases"]
        if case["format"] in {"apple_strings", "apple_stringsdict"}
    ]
    accepted = rejected = snapshots = normalized = skipped = 0
    skeleton_count = 0
    workflow_count = 0
    runtime_samples: list[dict[str, object]] = []

    with tempfile.TemporaryDirectory(prefix="mojito-apple-plutil-") as directory:
        for case in cases:
            if case.get("appleOracle") == "skip":
                skipped += 1
                continue
            source = (ROOT / case["input"]).read_text(encoding="utf-8")
            if case.get("lineEndings") == "CR":
                source = source.replace("\r\n", "\n").replace("\n", "\r")
            elif case.get("lineEndings") == "CRLF":
                source = source.replace("\r\n", "\n").replace("\n", "\r\n")
            suffix = ".strings" if case["format"] == "apple_strings" else ".stringsdict"
            destination = Path(directory) / f"{case['id']}{suffix}"
            native_bytes = (
                bytes.fromhex(
                    (ROOT / case["binaryFixture"]).read_text(encoding="ascii")
                )
                if "binaryFixture" in case
                else encode(source, case.get("encoding"))
            )
            if "binaryPaddingBytes" in case:
                native_bytes += bytes(case["binaryPaddingBytes"])
            destination.write_bytes(native_bytes)
            typed = case.get("appleTypedPlist", False)
            native_format = "binary1" if typed else "json"
            native_output = destination.with_suffix(".foundation.plist")
            result = subprocess.run(
                [
                    executable,
                    "-convert",
                    native_format,
                    "-o",
                    str(native_output) if typed else "-",
                    str(destination),
                ],
                capture_output=True,
                text=True,
            )
            policy = case.get("appleOracle")
            should_accept = policy == "accept" or (
                policy != "reject" and "expected" in case
            )
            if (result.returncode == 0) != should_accept:
                expectation = "accept" if should_accept else "reject"
                print(
                    f"{case['id']}: Foundation should {expectation} this fixture "
                    f"but exited {result.returncode}\n{result.stdout}{result.stderr}",
                    file=sys.stderr,
                )
                return 1

            if result.returncode:
                rejected += 1
                continue
            accepted += 1
            if case.get("appleBundleOracle") == "reject":
                bundle = Path(directory) / f"{case['id']}-rejected.bundle"
                resources = bundle / "en.lproj"
                resources.mkdir(parents=True)
                shutil.copyfile(destination, resources / "Localizable.strings")
                runtime_samples.append(
                    {
                        "bundle": str(bundle),
                        "fixture": case["id"],
                        "message": case["appleBundleMessage"],
                        "arguments": [],
                        "expected": "__MOJITO_FOUNDATION_FALLBACK__",
                        "fallback": True,
                    }
                )
            if "appleCompiled" not in case:
                continue
            expected = json.loads(
                (ROOT / case["appleCompiled"]).read_text(encoding="utf-8")
            )
            actual = native_snapshot(result.stdout, native_output, typed)
            if actual != expected:
                print(
                    f"{case['id']}: Foundation property-list snapshot mismatch\n"
                    f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                    f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}",
                    file=sys.stderr,
                )
                return 1
            snapshots += 1
            normalized_fixture = case.get("appleNormalized") or case.get(
                "appleStringsdictNormalized"
            )
            if normalized_fixture is not None:
                repeated = Path(directory) / f"{case['id']}-normalized{suffix}"
                repeated.write_bytes((ROOT / normalized_fixture).read_bytes())
                result = subprocess.run(
                    [
                        executable,
                        "-convert",
                        native_format,
                        "-o",
                        str(native_output) if typed else "-",
                        str(repeated),
                    ],
                    capture_output=True,
                    text=True,
                )
                if result.returncode:
                    print(
                        f"{case['id']}: normalized Apple strings failed Foundation parsing\n"
                        f"{result.stdout}{result.stderr}",
                        file=sys.stderr,
                    )
                    return 1
                actual = native_snapshot(result.stdout, native_output, typed)
                if actual != expected:
                    print(
                        f"{case['id']}: normalized Foundation output changed native semantics\n"
                        f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                        f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}",
                        file=sys.stderr,
                    )
                    return 1
                normalized += 1

            samples = case.get("appleStringsRuntimeSamples", []) + case.get(
                "appleStringsdictRuntimeSamples", []
            )
            if samples:
                catalog = json.loads(
                    (ROOT / case["expected"]).read_text(encoding="utf-8")
                )
                for variant, native_source in (
                    ("source", destination),
                    ("normalized", repeated),
                ):
                    bundle = Path(directory) / f"{case['id']}-{variant}.bundle"
                    resources = bundle / "en.lproj"
                    resources.mkdir(parents=True)
                    resource_name = (
                        "Localizable.strings"
                        if case["format"] == "apple_strings"
                        else "Localizable.stringsdict"
                    )
                    shutil.copyfile(native_source, resources / resource_name)
                    for sample in samples:
                        descriptor = catalog["messages"][sample["message"]]
                        positions = {}
                        for placeholder in descriptor.get("placeholders", []):
                            position = placeholder["position"]
                            value = sample["values"][placeholder["name"]]
                            if position in positions and positions[position] != value:
                                raise AssertionError(
                                    f"{case['id']}/{sample['message']}: "
                                    f"native argument position {position} has conflicting values"
                                )
                            positions[position] = value
                        for conversion in descriptor.get("metadata", {}).get(
                            "appleDisabledPrintfConversions", []
                        ):
                            if "argumentPosition" in conversion:
                                positions.setdefault(conversion["argumentPosition"], 0)
                        for categories in (
                            descriptor.get("metadata", {})
                            .get("applePluralDisabledPrintfConversions", {})
                            .values()
                        ):
                            for conversions in categories.values():
                                for conversion in conversions:
                                    if "argumentPosition" in conversion:
                                        positions.setdefault(
                                            conversion["argumentPosition"], 0
                                        )
                        expected_positions = set(
                            range(1, max(positions, default=0) + 1)
                        )
                        if set(positions) != expected_positions:
                            raise AssertionError(
                                f"{case['id']}/{sample['message']}: Foundation arguments must "
                                "cover every native positional slot"
                            )
                        runtime_samples.append(
                            {
                                "bundle": str(bundle),
                                "fixture": f"{case['id']}/{variant}",
                                "message": sample["message"],
                                "arguments": [
                                    positions[position]
                                    for position in sorted(positions)
                                ],
                                "expected": sample["expected"],
                            }
                        )

        skeleton_count = verify_source_skeletons(
            executable, manifest, Path(directory), runtime_samples
        )
        workflow_count = verify_workflow_outputs(executable, manifest, Path(directory))

        if runtime_samples:
            swift = shutil.which("swift")
            if swift is None:
                raise SystemExit("Apple Swift Foundation runtime is unavailable")
            payload = Path(directory) / "foundation-runtime-samples.json"
            payload.write_text(
                json.dumps(runtime_samples, ensure_ascii=False), encoding="utf-8"
            )
            runtime = subprocess.run(
                [
                    swift,
                    "-module-cache-path",
                    str(Path(directory) / "swift-module-cache"),
                    str(ROOT / "apple_foundation_runtime_oracle.swift"),
                    str(payload),
                ],
                capture_output=True,
                text=True,
            )
            if runtime.returncode:
                print(
                    f"Apple Foundation runtime rejected a native localized-string selection\n"
                    f"{runtime.stdout}{runtime.stderr}",
                    file=sys.stderr,
                )
                return 1

    print(
        f"Foundation verified {accepted + rejected} original Apple resource fixtures "
        f"({accepted} accepted, {rejected} rejected, {snapshots} parsed-value snapshots, "
        f"{normalized} normalized writer round trips, {len(runtime_samples)} real Foundation "
        f"localized-string selections, {skipped} security-policy skips)."
    )
    print(
        f"Foundation verified {skeleton_count} source-preserving Apple strings/stringsdict "
        "skeletons "
        "against original and translated property-list snapshots."
    )
    print(
        f"Foundation verified {workflow_count} configured Apple strings/stringsdict workflow "
        "source and localized dictionaries."
    )
    return 0


def verify_workflow_outputs(
    executable: str, manifest: dict[str, object], directory: Path
) -> int:
    """Validate translated workflow dictionaries against actual Foundation parsing."""
    cases = [
        case
        for case in manifest.get("workflowCases", [])
        if case["format"] in {"apple_strings", "apple_stringsdict"}
        and case.get("localized")
        and (case["format"] == "apple_strings" or case.get("removeUntranslated"))
    ]
    for case in cases:
        for kind in ("input", "localized"):
            extension = (
                ".stringsdict" if case["format"] == "apple_stringsdict" else ".strings"
            )
            resource = directory / "workflows" / case["id"] / f"{kind}{extension}"
            resource.parent.mkdir(parents=True, exist_ok=True)
            resource.write_bytes((ROOT / case[kind]).read_bytes())
            result = subprocess.run(
                [executable, "-convert", "json", "-o", "-", str(resource)],
                capture_output=True,
                text=True,
            )
            if result.returncode:
                raise SystemExit(
                    f"{case['id']}: configured Apple {kind} failed Foundation parsing\n"
                    f"{result.stdout}{result.stderr}"
                )
            actual = json.loads(result.stdout)
            if kind == "localized":
                expected = (
                    sorted({key.split("#", 1)[0] for key in case["translations"]})
                    if case["format"] == "apple_stringsdict"
                    else case["translations"]
                )
                actual_values = (
                    sorted(actual) if case["format"] == "apple_stringsdict" else actual
                )
                if actual_values != expected:
                    raise SystemExit(
                        f"{case['id']}: configured Apple output changed translated values\n"
                        f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                        f"actual: {json.dumps(actual_values, ensure_ascii=False, indent=2)}"
                    )
    return len(cases)


def verify_source_skeletons(
    executable: str,
    manifest: dict[str, object],
    directory: Path,
    runtime_samples: list[dict[str, object]],
) -> int:
    cases = [
        case
        for case in manifest.get("sourceSkeletons", [])
        if case["format"] in {"apple_strings", "apple_stringsdict"}
    ]
    cases.extend(manifest.get("appleBinarySourceSkeletons", []))
    for case in cases:
        for kind, path, snapshot in [
            ("original", case["input"], case["appleCompiled"]),
            ("localized", case["localized"], case["appleLocalizedCompiled"]),
        ]:
            suffix = (
                ".stringsdict" if case["format"] == "apple_stringsdict" else ".strings"
            )
            resource = directory / f"{case['id']}-{kind}{suffix}"
            if case.get("encoding") == "BINARY_PLIST":
                resource.write_bytes((ROOT / path).read_bytes())
            else:
                source = (ROOT / path).read_text(encoding="utf-8")
                if case.get("lineEndings") == "CRLF":
                    source = source.replace("\r\n", "\n").replace("\n", "\r\n")
                resource.write_bytes(encode(source, case.get("encoding")))
            typed = case.get("appleTypedPlist", False)
            native_output = resource.with_suffix(".foundation.plist")
            result = subprocess.run(
                [
                    executable,
                    "-convert",
                    "binary1" if typed else "json",
                    "-o",
                    str(native_output) if typed else "-",
                    str(resource),
                ],
                capture_output=True,
                text=True,
            )
            if result.returncode:
                raise SystemExit(
                    f"{case['id']}: {kind} source-preserving Apple strings "
                    f"failed Foundation parsing\n{result.stdout}{result.stderr}"
                )
            actual = native_snapshot(result.stdout, native_output, typed)
            expected = json.loads((ROOT / snapshot).read_text(encoding="utf-8"))
            if actual != expected:
                raise SystemExit(
                    f"{case['id']}: {kind} source-preserving Foundation snapshot mismatch\n"
                    f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                    f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}"
                )
            sample_field = (
                "appleOriginalRuntimeSamples"
                if kind == "original"
                else "appleLocalizedRuntimeSamples"
            )
            if samples := case.get(sample_field):
                bundle = directory / f"{case['id']}-{kind}.bundle"
                resources = bundle / "en.lproj"
                resources.mkdir(parents=True)
                shutil.copyfile(resource, resources / f"Localizable{suffix}")
                for sample in samples:
                    runtime_sample = {
                        "bundle": str(bundle),
                        "fixture": f"{case['id']}/{kind}",
                        "message": sample["message"],
                        "arguments": sample["arguments"],
                        "expected": sample["expected"],
                    }
                    if "presentationWidth" in sample:
                        runtime_sample["presentationWidth"] = sample[
                            "presentationWidth"
                        ]
                    runtime_samples.append(runtime_sample)
    return len(cases)


def native_snapshot(source: str, native_output: Path, typed: bool) -> object:
    if not typed:
        return json.loads(source)
    return typed_plist_value(plistlib.loads(native_output.read_bytes()))


def typed_plist_value(value: object) -> object:
    if isinstance(value, dict):
        converted = {
            key: typed_plist_value(item) for key, item in sorted(value.items())
        }
        if "$applePlistType" not in converted:
            return converted
        return {
            "$applePlistType": "dictionary",
            "entries": [{"key": key, "value": item} for key, item in converted.items()],
        }
    if isinstance(value, list):
        return [typed_plist_value(item) for item in value]
    if isinstance(value, bytes):
        return {
            "$applePlistType": "data",
            "base64": base64.b64encode(value).decode("ascii"),
        }
    if isinstance(value, datetime.datetime):
        return {
            "$applePlistType": "date",
            "value": value.strftime("%Y-%m-%dT%H:%M:%SZ"),
        }
    if isinstance(value, float):
        return {
            "$applePlistType": "real",
            "bits": struct.pack(">d", value).hex(),
        }
    return value


def encode(source: str, encoding: str | None) -> bytes:
    if encoding == "INVALID_UTF8":
        return b"\xc3("
    if encoding == "ODD_UTF16LE_BOM":
        return b"\xff\xfeA"
    if encoding == "UNPAIRED_UTF16LE_BOM":
        return b"\xff\xfe\x3d\xd8"
    if encoding == "UTF-8-BOM":
        return b"\xef\xbb\xbf" + source.encode("utf-8")
    if encoding == "UTF-16LE-BOM":
        return b"\xff\xfe" + source.encode("utf-16-le")
    if encoding == "UTF-16LE":
        return source.encode("utf-16-le")
    if encoding == "UTF-16BE-BOM":
        return b"\xfe\xff" + source.encode("utf-16-be")
    if encoding == "UTF-16BE":
        return source.encode("utf-16-be")
    if encoding == "ISO-8859-1":
        return source.encode("iso-8859-1")
    return source.encode("utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
