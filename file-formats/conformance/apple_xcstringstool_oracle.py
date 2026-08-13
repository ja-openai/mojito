#!/usr/bin/env python3
"""Compile original and normalized String Catalogs with Apple's real Xcode tool."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
XCODE_TOOL = Path("/Applications/Xcode.app/Contents/Developer/usr/bin/xcstringstool")


def find_tool() -> str | None:
    found = shutil.which("xcstringstool")
    if found is not None:
        return found
    return str(XCODE_TOOL) if XCODE_TOOL.is_file() else None


def main() -> int:
    executable = find_tool()
    plutil = shutil.which("plutil")
    if executable is None or plutil is None:
        raise SystemExit("Xcode xcstringstool and Foundation plutil are required")

    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    cases = [case for case in manifest["cases"] if case["format"] == "apple_xcstrings"]
    accepted = rejected = snapshots = normalized = 0
    skeleton_count = 0
    runtime_samples: list[dict[str, object]] = []

    with tempfile.TemporaryDirectory(prefix="mojito-xcode-xcstrings-") as directory:
        workspace = Path(directory)
        for case in cases:
            output = workspace / f"{case['id']}-source"
            output.mkdir()
            source = ROOT / case["input"]
            result = subprocess.run(
                [executable, "compile", str(source), "--output-directory", str(output)],
                capture_output=True,
                text=True,
            )
            policy = case.get("xcstringsOracle")
            should_accept = policy == "accept" or (
                policy != "reject" and "expected" in case
            )
            if (result.returncode == 0) != should_accept:
                expectation = "accept" if should_accept else "reject"
                print(
                    f"{case['id']}: Xcode should {expectation} this String Catalog "
                    f"but exited {result.returncode}\n{result.stdout}{result.stderr}",
                    file=sys.stderr,
                )
                return 1
            if result.returncode:
                diagnostic = case.get("xcstringsDiagnostic")
                if diagnostic is not None and diagnostic not in result.stderr:
                    print(
                        f"{case['id']}: Xcode rejected this String Catalog for the wrong reason\n"
                        f"expected diagnostic: {diagnostic}\n{result.stdout}{result.stderr}",
                        file=sys.stderr,
                    )
                    return 1
                rejected += 1
                continue
            accepted += 1
            if "xcstringsCompiled" not in case:
                continue
            expected = json.loads(
                (ROOT / case["xcstringsCompiled"]).read_text(encoding="utf-8")
            )
            actual = compiled_resources(output, plutil)
            alternatives = (
                json.loads(
                    (ROOT / case["xcstringsCompiledAlternatives"]).read_text(
                        encoding="utf-8"
                    )
                )
                if "xcstringsCompiledAlternatives" in case
                else [expected]
            )
            if actual not in alternatives:
                print(
                    f"{case['id']}: native Xcode resource snapshot mismatch\n"
                    f"expected: {json.dumps(alternatives, ensure_ascii=False, indent=2)}\n"
                    f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}",
                    file=sys.stderr,
                )
                return 1
            snapshots += 1
            if "xcstringsNormalized" in case:
                repeated = workspace / f"{case['id']}-normalized"
                repeated.mkdir()
                normalized_source = ROOT / case["xcstringsNormalized"]
                result = subprocess.run(
                    [
                        executable,
                        "compile",
                        str(normalized_source),
                        "--output-directory",
                        str(repeated),
                    ],
                    capture_output=True,
                    text=True,
                )
                if result.returncode:
                    print(
                        f"{case['id']}: Xcode rejected normalized String Catalog\n"
                        f"{result.stdout}{result.stderr}",
                        file=sys.stderr,
                    )
                    return 1
                expected_normalized = (
                    json.loads(
                        (ROOT / case["xcstringsNormalizedCompiled"]).read_text(
                            encoding="utf-8"
                        )
                    )
                    if "xcstringsNormalizedCompiled" in case
                    else expected
                )
                actual = compiled_resources(repeated, plutil)
                if actual != expected_normalized:
                    print(
                        f"{case['id']}: normalized Xcode resource snapshot mismatch\n"
                        f"expected: {json.dumps(expected_normalized, ensure_ascii=False, indent=2)}\n"
                        f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}",
                        file=sys.stderr,
                    )
                    return 1
                normalized += 1

        skeleton_count = verify_source_skeletons(
            executable, plutil, manifest, workspace, runtime_samples
        )
        if runtime_samples:
            swift = shutil.which("swift")
            if swift is None:
                raise SystemExit("Apple Swift Foundation runtime is unavailable")
            payload = workspace / "xcode-foundation-runtime-samples.json"
            payload.write_text(
                json.dumps(runtime_samples, ensure_ascii=False), encoding="utf-8"
            )
            runtime = subprocess.run(
                [
                    swift,
                    "-module-cache-path",
                    str(workspace / "swift-module-cache"),
                    str(ROOT / "apple_foundation_runtime_oracle.swift"),
                    str(payload),
                ],
                capture_output=True,
                text=True,
            )
            if runtime.returncode:
                raise SystemExit(
                    "Apple Foundation runtime rejected an Xcode-generated localized string\n"
                    f"{runtime.stdout}{runtime.stderr}"
                )

    print(
        f"Xcode xcstringstool verified {accepted + rejected} original String Catalog fixtures "
        f"({accepted} accepted, {rejected} rejected, {snapshots} compiled-resource snapshots, "
        f"{normalized} normalized writer round trips)."
    )
    print(
        f"Xcode xcstringstool verified {skeleton_count} source-preserving String Catalog "
        f"skeletons against original and translated compiled resources plus "
        f"{len(runtime_samples)} real Foundation localized-string selections."
    )
    return 0


def verify_source_skeletons(
    executable: str,
    plutil: str,
    manifest: dict[str, object],
    directory: Path,
    runtime_samples: list[dict[str, object]],
) -> int:
    cases = [
        case
        for case in manifest.get("sourceSkeletons", [])
        if case["format"] == "apple_xcstrings"
    ]
    for case in cases:
        for kind, path, snapshot in [
            ("original", case["input"], case["xcstringsCompiled"]),
            ("localized", case["localized"], case["xcstringsLocalizedCompiled"]),
        ]:
            source = (ROOT / path).read_text(encoding="utf-8")
            if case.get("lineEndings") == "CRLF":
                source = source.replace("\r\n", "\n").replace("\n", "\r\n")
            resource = directory / f"{case['id']}-{kind}.xcstrings"
            resource.write_bytes(encode(source, case.get("encoding")))
            output = directory / f"{case['id']}-{kind}"
            output.mkdir()
            result = subprocess.run(
                [
                    executable,
                    "compile",
                    str(resource),
                    "--output-directory",
                    str(output),
                ],
                capture_output=True,
                text=True,
            )
            if result.returncode:
                raise SystemExit(
                    f"{case['id']}: {kind} source-preserving String Catalog failed compilation\n"
                    f"{result.stdout}{result.stderr}"
                )
            expected = json.loads((ROOT / snapshot).read_text(encoding="utf-8"))
            actual = compiled_resources(output, plutil)
            if actual != expected:
                raise SystemExit(
                    f"{case['id']}: {kind} source-preserving Xcode snapshot mismatch\n"
                    f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                    f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}"
                )
            sample_field = (
                "xcstringsOriginalRuntimeSamples"
                if kind == "original"
                else "xcstringsLocalizedRuntimeSamples"
            )
            if samples := case.get(sample_field):
                bundle = directory / f"{case['id']}-{kind}.bundle"
                resources = bundle / "en.lproj"
                resources.mkdir(parents=True)
                locale = case.get("xcstringsRuntimeLocale", "en")
                for resource in (output / f"{locale}.lproj").iterdir():
                    if resource.is_file():
                        shutil.copyfile(
                            resource, resources / f"Localizable{resource.suffix}"
                        )
                for sample in samples:
                    runtime_samples.append(
                        {
                            "bundle": str(bundle),
                            "fixture": f"{case['id']}/{kind}",
                            "message": sample["message"],
                            "arguments": sample["arguments"],
                            "expected": sample["expected"],
                            "locale": sample.get(
                                "locale", case.get("xcstringsFormattingLocale", "en")
                            ),
                            **({"fallback": True} if sample.get("fallback") else {}),
                        }
                    )
    return len(cases)


def encode(source: str, encoding: str | None) -> bytes:
    if encoding == "UTF-8-BOM":
        return b"\xef\xbb\xbf" + source.encode("utf-8")
    if encoding == "UTF-16LE-BOM":
        return b"\xff\xfe" + source.encode("utf-16-le")
    if encoding == "UTF-16BE-BOM":
        return b"\xfe\xff" + source.encode("utf-16-be")
    return source.encode("utf-8")


def compiled_resources(directory: Path, plutil: str) -> dict[str, object]:
    result: dict[str, object] = {}
    for resource in sorted(path for path in directory.rglob("*") if path.is_file()):
        parsed = subprocess.run(
            [plutil, "-convert", "json", "-o", "-", str(resource)],
            capture_output=True,
            text=True,
        )
        if parsed.returncode:
            raise RuntimeError(
                f"Foundation could not parse {resource}: {parsed.stderr}"
            )
        relative = resource.relative_to(directory)
        identifier = str(relative.parent / f"catalog{resource.suffix}")
        result[identifier] = json.loads(parsed.stdout)
    return result


if __name__ == "__main__":
    raise SystemExit(main())
