#!/usr/bin/env python3
"""Check original neutral Android fixtures against Google's real AAPT2 compiler."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import unicodedata
import urllib.request
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
VERSION = "9.3.1-15703166"
MAVEN = "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2"
MAC_SHA256 = "1e35bc2ce18c3aae840be2a29659ce50d6043e907a44d98ee1cf375d044fa29c"


def encode_resource(source: str, encoding: str | None) -> bytes:
    if encoding == "INVALID_UTF8":
        return b"\xc3("
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
    if encoding == "ODD_UTF16LE":
        return source.encode("utf-16-le") + b"\x41"
    if encoding == "ODD_UTF16BE":
        return source.encode("utf-16-be") + b"\x41"
    if encoding == "UNPAIRED_UTF16LE":
        return source.encode("utf-16-le") + b"\x00\xd8"
    if encoding == "UNPAIRED_UTF16BE":
        return source.encode("utf-16-be") + b"\xd8\x00"
    if encoding == "ISO-8859-1":
        return source.encode("iso-8859-1")
    return source.encode("utf-8")


ANDROID_COLORS = {
    "black": 0xFF000000,
    "darkgray": 0xFF444444,
    "gray": 0xFF888888,
    "lightgray": 0xFFCCCCCC,
    "white": 0xFFFFFFFF,
    "red": 0xFFFF0000,
    "green": 0xFF00FF00,
    "blue": 0xFF0000FF,
    "yellow": 0xFFFFFF00,
    "cyan": 0xFF00FFFF,
    "magenta": 0xFFFF00FF,
    "aqua": 0xFF00FFFF,
    "fuchsia": 0xFFFF00FF,
    "darkgrey": 0xFF444444,
    "grey": 0xFF888888,
    "lightgrey": 0xFFCCCCCC,
    "lime": 0xFF00FF00,
    "maroon": 0xFF800000,
    "navy": 0xFF000080,
    "olive": 0xFF808000,
    "purple": 0xFF800080,
    "silver": 0xFFC0C0C0,
    "teal": 0xFF008080,
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--download",
        action="store_true",
        help="download the pinned official Google Maven compiler when unavailable",
    )
    parser.add_argument(
        "--dump", metavar="CASE", help="print the compiled APC for one fixture"
    )
    args = parser.parse_args()

    executable = locate_aapt2(args.download)
    version = subprocess.run(
        [str(executable), "version"], check=True, capture_output=True, text=True
    )
    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    android_cases = [case for case in manifest["cases"] if case["format"] == "android"]
    accepted = rejected = skipped = snapshots = normalized = pseudolocalized = (
        linked_cases
    ) = 0
    overlay_counts: tuple[int, int, int, int] | None = None
    skeleton_count = 0
    overlay_skeleton_count = 0

    with tempfile.TemporaryDirectory(prefix="mojito-aapt2-oracle-") as directory:
        workspace = Path(directory)
        for case in android_cases:
            policy = case.get("androidOracle")
            if policy == "skip":
                skipped += 1
                continue

            relative = Path(case.get("resourcePath", "res/values/strings.xml"))
            if relative.is_absolute() or ".." in relative.parts:
                raise SystemExit(f"{case['id']}: unsafe native Android resource path")
            resource = workspace / case["id"] / relative
            resource.parent.mkdir(parents=True)
            source = encode_resource(
                (ROOT / case["input"]).read_text(encoding="utf-8"),
                case.get("encoding"),
            )
            resource.write_bytes(source)
            output = workspace / case["id"] / "compiled"
            output.mkdir()
            result = subprocess.run(
                [
                    str(executable),
                    "compile",
                    *feature_flag_options(case),
                    str(resource),
                    "-o",
                    str(output),
                ],
                capture_output=True,
                text=True,
            )
            should_accept = policy == "accept" or (
                policy != "reject" and "expected" in case
            )
            if (result.returncode == 0) != should_accept:
                expectation = "accept" if should_accept else "reject"
                print(
                    f"{case['id']}: AAPT2 should {expectation} this fixture "
                    f"but exited {result.returncode}\n{result.stderr}",
                    file=sys.stderr,
                )
                return 1
            if result.returncode and case.get("androidErrorContains") not in (None, ""):
                diagnostic = str(case["androidErrorContains"])
                if diagnostic not in result.stderr:
                    print(
                        f"{case['id']}: AAPT2 rejected the resource for the wrong reason\n"
                        f"expected diagnostic: {diagnostic}\nactual: {result.stderr}",
                        file=sys.stderr,
                    )
                    return 1
            if result.returncode == 0:
                for warning in case.get("androidWarningContains", []):
                    if warning not in result.stderr:
                        print(
                            f"{case['id']}: expected native Android compiler warning is absent\n"
                            f"expected warning: {warning}\nactual: {result.stderr}",
                            file=sys.stderr,
                        )
                        return 1

            if result.returncode == 0:
                if "androidFlatName" in case:
                    compiled = next(output.glob("*.arsc.flat"))
                    if compiled.name != case["androidFlatName"]:
                        raise SystemExit(
                            f"{case['id']}: AAPT2 intermediate filename mismatch: "
                            f"{compiled.name!r} != {case['androidFlatName']!r}"
                        )
                if (
                    "androidLinkErrorContains" in case
                    or "androidLinkCrashSignal" in case
                ):
                    compiled = next(output.glob("*.arsc.flat"))
                    verify_rejected_linked_android_resource(
                        executable,
                        compiled,
                        case,
                        workspace / f"{case['id']}-linked-rejected",
                    )
                    rejected += 1
                    continue
                accepted += 1
                if "androidPseudolocalized" in case:
                    verify_pseudolocalized_resources(
                        executable,
                        resource,
                        workspace / f"{case['id']}-pseudolocalized",
                        ROOT / case["androidPseudolocalized"],
                        case["id"],
                    )
                    pseudolocalized += 1
                if args.dump == case["id"] or "androidCompiled" in case:
                    compiled = next(output.glob("*.arsc.flat"))
                    dump = subprocess.run(
                        [str(executable), "dump", "apc", str(compiled)],
                        check=True,
                        capture_output=True,
                    )
                    dumped = dump.stdout.decode("utf-8")
                    if args.dump == case["id"]:
                        print(dumped)
                    if "androidCompiled" in case:
                        expected = json.loads(
                            (ROOT / case["androidCompiled"]).read_text(encoding="utf-8")
                        )
                        actual = compiled_catalog(
                            dumped,
                            include_spans=case.get("androidStyledSpans", False),
                            include_span_runtime=case.get("androidSpanRuntime", False),
                            include_configuration=case.get(
                                "androidConfiguration", False
                            ),
                            include_primitives=case.get("androidPrimitives", False),
                            include_attributes=case.get("androidAttributes", False),
                            include_styleables=case.get("androidStyleables", False),
                            include_attribute_configurations=case.get(
                                "androidAttributeConfigurations", False
                            ),
                        )
                        if actual != expected:
                            print(
                                f"{case['id']}: AAPT2 compiled-resource snapshot mismatch\n"
                                f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                                f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}",
                                file=sys.stderr,
                            )
                            return 1
                        snapshots += 1
                if "androidLinked" in case:
                    compiled = next(output.glob("*.arsc.flat"))
                    verify_linked_android_resource(
                        executable,
                        compiled,
                        case,
                        workspace / f"{case['id']}-linked",
                        ROOT / case["androidLinked"],
                        case["id"],
                    )
                    linked_cases += 1
                if "androidNormalized" in case:
                    normalized_resource = (
                        workspace / f"{case['id']}-normalized" / relative
                    )
                    normalized_resource.parent.mkdir(parents=True)
                    normalized_resource.write_bytes(
                        (ROOT / case["androidNormalized"]).read_bytes()
                    )
                    normalized_output = (
                        workspace / f"{case['id']}-normalized" / "compiled"
                    )
                    normalized_output.mkdir()
                    repeated = subprocess.run(
                        [
                            str(executable),
                            "compile",
                            *feature_flag_options(case),
                            str(normalized_resource),
                            "-o",
                            str(normalized_output),
                        ],
                        capture_output=True,
                        text=True,
                    )
                    if repeated.returncode:
                        print(
                            f"{case['id']}: normalized Android XML failed AAPT2 compilation\n"
                            f"{repeated.stderr}",
                            file=sys.stderr,
                        )
                        return 1
                    compiled = next(normalized_output.glob("*.arsc.flat"))
                    if (
                        "androidFlatName" in case
                        and compiled.name != case["androidFlatName"]
                    ):
                        raise SystemExit(
                            f"{case['id']}: normalized AAPT2 intermediate filename mismatch: "
                            f"{compiled.name!r} != {case['androidFlatName']!r}"
                        )
                    dump = subprocess.run(
                        [str(executable), "dump", "apc", str(compiled)],
                        check=True,
                        capture_output=True,
                    )
                    expected = json.loads(
                        (
                            ROOT
                            / case.get(
                                "androidNormalizedCompiled", case["androidCompiled"]
                            )
                        ).read_text(encoding="utf-8")
                    )
                    actual = compiled_catalog(
                        dump.stdout.decode("utf-8"),
                        include_spans=case.get("androidStyledSpans", False),
                        include_span_runtime=case.get("androidSpanRuntime", False),
                        include_configuration=case.get("androidConfiguration", False),
                        include_primitives=case.get("androidPrimitives", False),
                        include_attributes=case.get("androidAttributes", False),
                        include_styleables=case.get("androidStyleables", False),
                        include_attribute_configurations=case.get(
                            "androidAttributeConfigurations", False
                        ),
                    )
                    if actual != expected:
                        print(
                            f"{case['id']}: normalized AAPT2 output changed native semantics\n"
                            f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                            f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}",
                            file=sys.stderr,
                        )
                        return 1
                    normalized += 1
                    if "androidLinked" in case:
                        verify_linked_android_resource(
                            executable,
                            compiled,
                            case,
                            workspace / f"{case['id']}-normalized-linked",
                            ROOT / case["androidLinked"],
                            f"{case['id']}-normalized",
                        )
                    if "androidPseudolocalized" in case:
                        verify_pseudolocalized_resources(
                            executable,
                            normalized_resource,
                            workspace / f"{case['id']}-normalized-pseudolocalized",
                            ROOT / case["androidPseudolocalized"],
                            f"{case['id']}-normalized",
                        )
            else:
                rejected += 1

        overlay_counts = verify_android_overlays(executable, manifest, workspace)
        skeleton_count = verify_source_skeletons(executable, manifest, workspace)
        overlay_skeleton_count = verify_overlay_source_skeletons(
            executable, manifest, workspace
        )
        workflow_count = verify_workflow_outputs(executable, manifest, workspace)

    print(version.stdout.strip())
    print(
        f"AAPT2 verified {accepted + rejected} original Android fixtures "
        f"({accepted} accepted, {rejected} rejected, {snapshots} compiled-value snapshots, "
        f"{normalized} normalized writer round trips, {linked_cases} linked build configurations, "
        f"{pseudolocalized} pseudolocale snapshots, "
        f"{skipped} security-policy skips)."
    )
    overlay_accepted, overlay_rejected, overlay_snapshots, overlay_skipped = (
        overlay_counts
    )
    print(
        f"AAPT2 linked {overlay_accepted + overlay_rejected} Android resource overlays "
        f"({overlay_accepted} accepted, {overlay_rejected} rejected, "
        f"{overlay_snapshots} linked-value snapshots, {overlay_skipped} policy skips)."
    )
    print(
        f"AAPT2 verified {skeleton_count} source-preserving skeletons "
        f"against original and localized native resource snapshots."
    )
    print(
        f"AAPT2 verified {workflow_count} configured Android workflow "
        "source and localized resources."
    )
    print(
        f"AAPT2 linked {overlay_skeleton_count} multi-file source-preserving "
        "Android overlays for original and localized products."
    )
    return 0


def verify_workflow_outputs(
    executable: Path, manifest: dict[str, object], workspace: Path
) -> int:
    """Keep configured Android cleanup output valid for the actual platform."""
    cases = [
        case
        for case in manifest.get("workflowCases", [])
        if case["format"] == "android" and case.get("localized")
    ]
    for case in cases:
        for kind in ("input", "localized"):
            directory = workspace / "workflows" / case["id"] / kind
            resource = directory / "res" / "values" / "strings.xml"
            resource.parent.mkdir(parents=True)
            resource.write_bytes((ROOT / case[kind]).read_bytes())
            output = directory / "compiled"
            output.mkdir()
            result = subprocess.run(
                [str(executable), "compile", str(resource), "-o", str(output)],
                capture_output=True,
                text=True,
            )
            if result.returncode:
                raise SystemExit(
                    f"{case['id']}: configured Android {kind} failed "
                    f"AAPT2 compilation\n{result.stderr}"
                )
    return len(cases)


def verify_source_skeletons(
    executable: Path, manifest: dict[str, object], workspace: Path
) -> int:
    cases = [
        case
        for case in manifest.get("sourceSkeletons", [])
        if case["format"] == "android"
    ]
    for case in cases:
        encoding = case.get("encoding") or "UTF-8"
        for kind, path, expected_path in [
            ("original", case["input"], case["androidCompiled"]),
            ("localized", case["localized"], case["androidLocalizedCompiled"]),
        ]:
            source = (ROOT / path).read_text(encoding="utf-8")
            if case.get("lineEndings") == "CRLF":
                source = source.replace("\r\n", "\n").replace("\n", "\r\n")
            directory = workspace / "skeletons" / case["id"] / kind
            relative = Path(case.get("resourcePath", "res/values/strings.xml"))
            if relative.is_absolute() or ".." in relative.parts:
                raise SystemExit(f"{case['id']}: unsafe original Android source path")
            resource = directory / relative
            resource.parent.mkdir(parents=True, exist_ok=True)
            resource.write_bytes(encode_resource(source, encoding))
            output = directory / "compiled"
            output.mkdir()
            result = subprocess.run(
                [
                    str(executable),
                    "compile",
                    *feature_flag_options(case),
                    str(resource),
                    "-o",
                    str(output),
                ],
                capture_output=True,
                text=True,
            )
            if result.returncode:
                raise SystemExit(
                    f"{case['id']}: {kind} source-preserving Android XML "
                    f"failed AAPT2 compilation\n{result.stderr}"
                )
            compiled = next(output.glob("*.arsc.flat"))
            if "androidFlatName" in case and compiled.name != case["androidFlatName"]:
                raise SystemExit(
                    f"{case['id']}: {kind} AAPT2 source intermediate filename mismatch: "
                    f"{compiled.name!r} != {case['androidFlatName']!r}"
                )
            dump = subprocess.run(
                [str(executable), "dump", "apc", str(compiled)],
                check=True,
                capture_output=True,
            )
            actual = compiled_catalog(
                dump.stdout.decode("utf-8"),
                include_spans=True,
                include_span_runtime=case.get("androidSpanRuntime", False),
                include_configuration=case.get("androidConfiguration", False),
            )
            expected = json.loads((ROOT / expected_path).read_text(encoding="utf-8"))
            if actual != expected:
                raise SystemExit(
                    f"{case['id']}: {kind} source-preserving native snapshot mismatch\n"
                    f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                    f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}"
                )
            for product in case.get("androidProductLinks", []):
                selection = {
                    "androidSelectedProducts": [product["product"]],
                    "androidApplicationPackage": "neutral.product.template",
                }
                if "androidFeatureFlagDefinitions" in case:
                    selection["androidFeatureFlagDefinitions"] = case[
                        "androidFeatureFlagDefinitions"
                    ]
                verify_linked_android_resource(
                    executable,
                    compiled,
                    selection,
                    directory / f"linked-{product['product']}",
                    ROOT / product[kind],
                    f"{case['id']}/{kind}/{product['product']}",
                )
    return len(cases)


def feature_flag_options(resource: dict[str, object]) -> list[str]:
    definitions = resource.get("androidFeatureFlagDefinitions")
    if definitions:
        values = []
        for definition in definitions:
            mode = "ro" if definition["mode"] == "read_only" else "READ_WRITE"
            value = definition["value"]
            encoded = "" if value is None else "true" if value else "false"
            values.append(f"{definition['name']}:{mode}={encoded}")
        return ["--feature-flags", ",".join(values)]
    flags = resource.get("androidFeatureFlags")
    if not flags:
        return []
    return [
        "--feature-flags",
        ",".join(
            f"{name}:ro={'true' if value else 'false'}"
            for name, value in sorted(flags.items())
        ),
    ]


def verify_linked_android_resource(
    executable: Path,
    compiled: Path,
    case: dict[str, object],
    directory: Path,
    expected_path: Path,
    label: str,
) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    manifest = directory / "AndroidManifest.xml"
    package_name = case.get("androidApplicationPackage", "neutral.feature.flags")
    manifest.write_text(f'<manifest package="{package_name}"/>', encoding="utf-8")
    package = directory / "resources.apk"
    result = subprocess.run(
        linked_android_command(executable, compiled, case, manifest, package),
        capture_output=True,
        text=True,
    )
    if result.returncode:
        raise SystemExit(f"{label}: AAPT2 linked resource failed\n{result.stderr}")
    dumped = subprocess.run(
        [str(executable), "dump", "resources", str(package)],
        check=True,
        capture_output=True,
    )
    expected = json.loads(expected_path.read_text(encoding="utf-8"))
    actual = linked_catalog(
        dumped.stdout.decode("utf-8"),
        include_attributes=case.get("androidAttributes", False),
    )
    if actual != expected:
        raise SystemExit(
            f"{label}: AAPT2 linked-resource snapshot mismatch\n"
            f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
            f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}"
        )


def linked_android_command(
    executable: Path,
    compiled: Path,
    case: dict[str, object],
    manifest: Path,
    package: Path,
) -> list[str]:
    command = [
        str(executable),
        "link",
        *feature_flag_options(case),
        "--manifest",
        str(manifest),
        "--no-resource-removal",
        "-o",
        str(package),
    ]
    if "androidSelectedProducts" in case:
        command.extend(["--product", ",".join(case["androidSelectedProducts"])])
    command.append(str(compiled))
    return command


def verify_rejected_linked_android_resource(
    executable: Path,
    compiled: Path,
    case: dict[str, object],
    directory: Path,
) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    manifest = directory / "AndroidManifest.xml"
    package_name = case.get("androidApplicationPackage", "neutral.product.flags")
    manifest.write_text(f'<manifest package="{package_name}"/>', encoding="utf-8")
    result = subprocess.run(
        linked_android_command(
            executable, compiled, case, manifest, directory / "resources.apk"
        ),
        capture_output=True,
        text=True,
    )
    if "androidLinkCrashSignal" in case:
        expected = -int(case["androidLinkCrashSignal"])
        diagnostic = str(case.get("androidLinkAbortContains", ""))
        if result.returncode != expected or diagnostic not in result.stderr:
            raise SystemExit(
                f"{case['id']}: AAPT2 linker crash changed unexpectedly\n"
                f"expected signal: {-expected}\n"
                f"expected diagnostic: {diagnostic}\n"
                f"actual status: {result.returncode}\nactual: {result.stderr}"
            )
        return
    if (
        result.returncode == 0
        or str(case["androidLinkErrorContains"]) not in result.stderr
    ):
        raise SystemExit(
            f"{case['id']}: AAPT2 rejected the selected products for the wrong reason\n"
            f"expected diagnostic: {case['androidLinkErrorContains']}\nactual: {result.stderr}"
        )


def verify_pseudolocalized_resources(
    executable: Path,
    resource: Path,
    output: Path,
    expected_snapshot: Path,
    case_id: str,
) -> None:
    output.mkdir()
    compiled = subprocess.run(
        [
            str(executable),
            "compile",
            "--pseudo-localize",
            str(resource),
            "-o",
            str(output),
        ],
        capture_output=True,
        text=True,
    )
    if compiled.returncode:
        raise SystemExit(
            f"{case_id}: AAPT2 pseudolocalization failed\n{compiled.stderr}"
        )
    flat = next(output.glob("*.arsc.flat"))
    dump = subprocess.run(
        [str(executable), "dump", "apc", str(flat)],
        check=True,
        capture_output=True,
        text=True,
    )
    expected = json.loads(expected_snapshot.read_text(encoding="utf-8"))
    actual = pseudolocalized_catalog(dump.stdout)
    if actual != expected:
        raise SystemExit(
            f"{case_id}: AAPT2 pseudolocalization snapshot mismatch\n"
            f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
            f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}"
        )


def pseudolocalized_catalog(output: str) -> dict[str, object]:
    native_resources = re.compile(
        r"^      resource 0x[0-9a-fA-F]+ (?P<kind>[^/\s]+)/(?P<name>[^\s]+)\n"
        r"(?P<body>.*?)(?=^      resource |^    type |\Z)",
        re.MULTILINE | re.DOTALL,
    )
    generated = {}
    for resource in native_resources.finditer(output):
        configurations = sorted(
            set(
                match.group("configuration")
                for match in re.finditer(
                    r"^        \((?P<configuration>en-rXA|ar-rXB)\)",
                    resource.group("body"),
                    re.MULTILINE,
                )
            )
        )
        if configurations:
            generated[resource.group("name")] = configurations
    return {"resources": generated}


def verify_overlay_source_skeletons(
    executable: Path, manifest: dict[str, object], workspace: Path
) -> int:
    cases = manifest.get("androidOverlaySourceSkeletons", [])
    for case in cases:
        localized = {
            **case,
            "id": f"{case['id']}-localized",
            "inputs": [
                {**resource, "input": resource["localized"]}
                for resource in case["inputs"]
            ],
        }
        for kind, overlay, snapshot in (
            ("original", case, case["androidLinked"]),
            ("localized", localized, case["androidLocalizedLinked"]),
        ):
            status, actual, stderr = compile_android_overlay(
                executable, overlay, workspace
            )
            if status:
                raise SystemExit(
                    f"{case['id']}: {kind} multi-file Android source template "
                    f"failed AAPT2 linking\n{stderr}"
                )
            expected = json.loads((ROOT / snapshot).read_text(encoding="utf-8"))
            if actual != expected:
                raise SystemExit(
                    f"{case['id']}: {kind} multi-file Android source snapshot mismatch\n"
                    f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                    f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}"
                )
    return len(cases)


def verify_android_overlays(
    executable: Path, manifest: dict[str, object], workspace: Path
) -> tuple[int, int, int, int]:
    accepted = rejected = snapshots = skipped = 0
    for overlay in manifest.get("androidOverlays", []):
        if overlay.get("androidOverlayOracle") == "skip":
            skipped += 1
            continue
        result, actual, stderr = compile_android_overlay(executable, overlay, workspace)
        if "error" in overlay:
            if result == 0:
                raise SystemExit(
                    f"{overlay['id']}: AAPT2 accepted a same-priority resource conflict"
                )
            if (
                overlay.get("androidOverlayErrorContains") is not None
                and str(overlay["androidOverlayErrorContains"]) not in stderr
            ):
                raise SystemExit(
                    f"{overlay['id']}: AAPT2 rejected the overlay for the wrong reason\n"
                    f"expected diagnostic: {overlay['androidOverlayErrorContains']}\nactual: {stderr}"
                )
            rejected += 1
            continue
        if result != 0:
            raise SystemExit(f"{overlay['id']}: AAPT2 overlay linking failed\n{stderr}")
        expected = json.loads(
            (ROOT / overlay["androidLinked"]).read_text(encoding="utf-8")
        )
        if actual != expected:
            raise SystemExit(
                f"{overlay['id']}: AAPT2 linked-resource snapshot mismatch\n"
                f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}"
            )
        accepted += 1
        snapshots += 1
    return accepted, rejected, snapshots, skipped


def compile_android_overlay(
    executable: Path, overlay: dict[str, object], workspace: Path
) -> tuple[int, dict[str, object] | None, str]:
    priority = {
        "library": 0,
        "main": 1,
        "product_flavor": 2,
        "build_type": 3,
        "build_variant": 4,
    }
    directory = workspace / "overlays" / str(overlay["id"])
    directory.mkdir(parents=True, exist_ok=True)
    compiled = []
    for index, resource in enumerate(overlay["inputs"]):
        relative = Path(resource["resourcePath"])
        if relative.is_absolute() or ".." in relative.parts:
            raise SystemExit(
                f"{overlay['id']}: unsafe native Android overlay resource path"
            )
        source = directory / f"source-{index}" / relative
        source.parent.mkdir(parents=True)
        original = ROOT / resource["input"]
        source.write_bytes(
            encode_resource(original.read_text(encoding="utf-8"), resource["encoding"])
            if "encoding" in resource
            else original.read_bytes()
        )
        output = directory / f"compiled-{index}"
        output.mkdir()
        result = subprocess.run(
            [
                str(executable),
                "compile",
                *feature_flag_options(overlay),
                str(source),
                "-o",
                str(output),
            ],
            capture_output=True,
            text=True,
        )
        if result.returncode:
            return result.returncode, None, result.stderr
        compiled.append(
            (priority[resource["sourceSet"]], next(output.glob("*.arsc.flat")))
        )
    compiled.sort(key=lambda entry: entry[0])

    android_manifest = directory / "AndroidManifest.xml"
    package_name = overlay.get("androidApplicationPackage", "neutral.overlay")
    android_manifest.write_text(
        f'<manifest package="{package_name}"/>', encoding="utf-8"
    )
    products = (
        [",".join(overlay["androidSelectedProducts"])]
        if "androidSelectedProducts" in overlay
        else overlay.get("androidProducts", [None])
    )
    results = {}
    for product in products:
        name = "default" if product is None else str(product)
        package = directory / f"{name}.apk"
        command = [
            str(executable),
            "link",
            *feature_flag_options(overlay),
            "--manifest",
            str(android_manifest),
            "--auto-add-overlay",
            "--no-resource-removal",
            "-o",
            str(package),
        ]
        if product is not None:
            command.extend(["--product", str(product)])
        previous_priority = None
        for resource_priority, resource in compiled:
            if previous_priority is not None and resource_priority != previous_priority:
                command.append("-R")
            command.append(str(resource))
            previous_priority = resource_priority
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode:
            return result.returncode, None, result.stderr
        dumped = subprocess.run(
            [str(executable), "dump", "resources", str(package)],
            check=True,
            capture_output=True,
            text=True,
        )
        results[name] = linked_catalog(
            dumped.stdout, include_attributes=overlay.get("androidAttributes", False)
        )
    if "androidProducts" in overlay:
        return 0, {"products": results}, ""
    if "androidSelectedProducts" in overlay:
        return 0, results[",".join(overlay["androidSelectedProducts"])], ""
    return 0, results["default"], ""


def linked_catalog(
    output: str, *, include_attributes: bool = False
) -> dict[str, object]:
    resources = re.compile(
        r"^    resource 0x[0-9a-fA-F]+ (?P<kind>[^/\s]+)/(?P<name>[^\s]+)\n"
        r"(?P<body>.*?)(?=^    resource |^  type |\Z)",
        re.MULTILINE | re.DOTALL,
    )
    result: dict[str, object] = {}
    for resource in resources.finditer(output):
        kind = resource.group("kind")
        name = resource.group("name")
        body = resource.group("body")
        regular, separator, runtime = body.partition(
            "\n      Read/write flag values:\n"
        )
        conditional = runtime if separator else regular
        if kind == "string":
            alternatives = list(
                re.finditer(
                    r"^      \((?P<configuration>[^)]*)\) "
                    r"\(featureFlag=(?P<condition>[^)]*)\) "
                    r'(?P<styled>\(styled string\) )?(?P<value>".*?)(?=^      \(|\Z)',
                    conditional,
                    re.MULTILINE | re.DOTALL,
                )
            )
            if alternatives:
                values = [
                    {
                        "condition": alternative.group("condition"),
                        "value": native_string_parts(
                            alternative.group("value").replace("\n      ", "\n"),
                            styled=alternative.group("styled") is not None,
                        )[0],
                    }
                    for alternative in alternatives
                ]
                result.setdefault("conditionalStrings", {})[name] = (
                    values[0] if len(values) == 1 else values
                )
            match = re.search(
                r"^      \((?P<configuration>[^)]*)\) "
                r'(?P<styled>\(styled string\) )?(?P<value>".*?)(?=^      \(|\Z)',
                regular,
                re.MULTILINE | re.DOTALL,
            )
            if match is not None:
                value, encoded_spans = native_string_parts(
                    match.group("value").replace("\n      ", "\n"),
                    styled=match.group("styled") is not None,
                )
                result.setdefault("strings", {})[name] = value
                spans = parse_styled_spans(encoded_spans)
                if spans:
                    result.setdefault("styledSpans", {}).setdefault("strings", {})[
                        name
                    ] = spans
                if match.group("configuration"):
                    result["configuration"] = match.group("configuration")
        elif kind == "plurals":
            plural_sections = native_bag_sections(regular, "plurals")
            ordinary = next(
                (section for section in plural_sections if section[0] is None), None
            )
            if ordinary is not None:
                values = plural_values(ordinary[1])
                result.setdefault("plurals", {})[name] = values
            alternatives = [
                {"condition": condition, "variants": plural_values(section)}
                for condition, section in native_bag_sections(conditional, "plurals")
                if condition is not None
            ]
            if alternatives:
                result.setdefault("conditionalPlurals", {})[name] = (
                    alternatives[0] if len(alternatives) == 1 else alternatives
                )
            for reference in re.finditer(
                r"^        (?P<quantity>zero|one|two|few|many|other)=(?P<value>[@?]\S+)$",
                ordinary[1] if ordinary is not None else "",
                re.MULTILINE,
            ):
                result.setdefault("pluralReferences", {}).setdefault(name, {})[
                    reference.group("quantity")
                ] = reference.group("value")
        elif kind == "array":
            array_sections = native_bag_sections(regular, "array")
            ordinary = next(
                (section for section in array_sections if section[0] is None), None
            )
            if ordinary is not None:
                size = native_array_size(regular)
                contents, values = linked_array_values(ordinary[1], size)
                result.setdefault("arrays", {})[name] = values
                for index, entry in enumerate(array_entries(contents, size)):
                    if entry.get("reference") is not None:
                        result.setdefault("arrayReferences", {}).setdefault(name, {})[
                            str(index)
                        ] = entry["reference"]
                    elif entry.get("primitive") is not None:
                        result.setdefault("arrayPrimitiveValues", {}).setdefault(
                            name, {}
                        )[str(index)] = entry["primitive"]
            alternatives = [
                {
                    "condition": condition,
                    "values": linked_array_values(
                        section, native_array_size(conditional, condition)
                    )[1],
                }
                for condition, section in native_bag_sections(conditional, "array")
                if condition is not None
            ]
            if alternatives:
                result.setdefault("conditionalArrays", {})[name] = (
                    alternatives[0] if len(alternatives) == 1 else alternatives
                )
        elif kind == "attr" and include_attributes:
            attribute = native_attribute(body)
            if attribute is not None:
                result.setdefault("attributes", {})[name] = attribute
    return result


def native_bag_sections(body: str, kind: str) -> list[tuple[str | None, str]]:
    return [
        (match.group("condition"), match.group("values"))
        for match in re.finditer(
            rf"^      \([^)]*\) (?:\(featureFlag=(?P<condition>[^)]*)\) )?"
            rf"\({kind}\) size=\d+\n(?P<values>.*?)(?=^      \(|\Z)",
            body,
            re.MULTILINE | re.DOTALL,
        )
    ]


def plural_values(body: str) -> dict[str, str]:
    return {
        match.group("quantity"): native_string_parts(
            match.group("value"), styled=match.group("styled") is not None
        )[0]
        for match in re.finditer(
            r"^        (?P<quantity>zero|one|two|few|many|other)="
            r'(?P<styled>\(styled string\) )?(?P<value>".*)\s*$',
            body,
            re.MULTILINE,
        )
    }


def native_array_size(body: str, condition: str | None = None) -> int:
    conditional = (
        rf"\(featureFlag={re.escape(condition)}\) "
        if condition is not None
        else r"(?:\(featureFlag=\) )?"
    )
    match = re.search(rf"{conditional}\(array\) size=(?P<size>\d+)", body)
    if match is None:
        raise ValueError("Compiled Android array has no native entry count")
    return int(match.group("size"))


def linked_array_values(body: str, size: int) -> tuple[str, list[str]]:
    match = re.search(
        r"^        \[(?P<values>.*?)\]\s*$", body, re.MULTILINE | re.DOTALL
    )
    if match is None:
        return "", []
    contents = re.sub(r"\n\s+", " ", match.group("values"))
    return contents, [entry["value"] for entry in native_array_entries(contents, size)]


def native_string_parts(source: str, *, styled: bool) -> tuple[str, str]:
    source = source.rstrip()
    if not source.startswith('"'):
        raise ValueError("Compiled Android string is missing its opening delimiter")
    if not styled:
        if not source.endswith('"'):
            raise ValueError(
                f"Compiled Android string is missing its closing delimiter: {source!r}"
            )
        return source[1:-1], ""

    spans = re.compile(
        r"(?:\s+[A-Za-z_][A-Za-z0-9_.-]*(?:;.*?)?:\d+,\d+)+",
        re.DOTALL,
    )
    for index, character in enumerate(source[1:], start=1):
        if character == '"' and spans.fullmatch(source[index + 1 :]):
            return source[1:index], source[index + 1 :]
    if source.endswith('"'):
        return source[1:-1], ""
    raise ValueError("Compiled styled Android string has no unambiguous span suffix")


def compiled_array_contents(source: str) -> str:
    result = []
    quoted = False
    index = 0
    while index < len(source):
        character = source[index]
        if character == '"':
            quoted = not quoted
        if character == "\n":
            previous = source[:index].rstrip()
            result.append("\n" if quoted or not previous.endswith(",") else " ")
            index += 1
            while index < len(source) and source[index] == " ":
                index += 1
            continue
        result.append(character)
        index += 1
    return "".join(result)


def compiled_catalog(
    output: str,
    *,
    include_spans: bool = False,
    include_span_runtime: bool = False,
    include_configuration: bool = False,
    include_primitives: bool = False,
    include_attributes: bool = False,
    include_styleables: bool = False,
    include_attribute_configurations: bool = False,
) -> dict[str, object]:
    resources = re.compile(
        r"^      resource 0x[0-9a-fA-F]+ (?P<kind>[^/\s]+)/(?P<name>[^\s]+)(?: PUBLIC)?\n"
        r"(?P<body>.*?)(?=^      resource |^    type |\Z)",
        re.MULTILINE | re.DOTALL,
    )
    result: dict[str, object] = {}
    if include_configuration:
        configuration = re.search(
            r"^        \((?P<configuration>[^)]*)\)", output, re.MULTILINE
        )
        if configuration is None:
            raise ValueError("Compiled Android resources have no configuration")
        result["configuration"] = configuration.group("configuration")
    for resource in resources.finditer(output):
        kind = resource.group("kind")
        name = resource.group("name")
        body = resource.group("body").split("\n        Read/write flag values:", 1)[0]
        if kind == "macro":
            result.setdefault("macros", []).append(name)
        elif kind == "string":
            matches = list(
                re.finditer(
                    r"^        \([^)]*\) (?:\(featureFlag=[^)]*\) )?"
                    r'(?P<styled>\(styled string\) )?(?P<value>".*?) src=',
                    body,
                    re.MULTILINE | re.DOTALL,
                )
            )
            parsed = [
                native_string_parts(
                    match.group("value").replace("\n        ", "\n"),
                    styled=match.group("styled") is not None,
                )
                for match in matches
            ]
            values = [value for value, _ in parsed]
            if len(values) > 1:
                result.setdefault("productVariants", {})[name] = values
                continue
            if values:
                result.setdefault("strings", {})[name] = values[0]
                if include_spans:
                    spans = parse_styled_spans(
                        parsed[0][1],
                        include_runtime=include_span_runtime,
                        visible_text=values[0],
                    )
                    if spans:
                        result.setdefault("styledSpans", {}).setdefault("strings", {})[
                            name
                        ] = spans
                continue
            reference = re.search(
                r"^        \(\) (?P<value>[@?].*?) src=",
                body,
                re.MULTILINE | re.DOTALL,
            )
            if reference is not None:
                result.setdefault("references", {})[name] = reference.group(
                    "value"
                ).replace("\n        ", "\n")
                continue
            if include_primitives:
                primitive = re.search(
                    r"^        \([^)]*\) (?P<value>\S+) src=", body, re.MULTILINE
                )
                if primitive is not None:
                    result.setdefault("primitiveValues", {})[name] = primitive.group(
                        "value"
                    )
        elif kind == "plurals":
            variants = {}
            for match in re.finditer(
                r"^          (?P<quantity>zero|one|two|few|many|other)="
                r'(?P<styled>\(styled string\) )?(?P<value>".*?)'
                r"(?=^          (?:zero|one|two|few|many|other)="
                r"|^        (?:\(|Flag disabled values:)|\Z)",
                body,
                re.MULTILINE | re.DOTALL,
            ):
                quantity = match.group("quantity")
                value, encoded_spans = native_string_parts(
                    match.group("value").replace("\n          ", "\n"),
                    styled=match.group("styled") is not None,
                )
                variants[quantity] = value
                if include_spans:
                    spans = parse_styled_spans(
                        encoded_spans,
                        include_runtime=include_span_runtime,
                        visible_text=variants[quantity],
                    )
                    if spans:
                        result.setdefault("styledSpans", {}).setdefault(
                            "plurals", {}
                        ).setdefault(name, {})[quantity] = spans
            result.setdefault("plurals", {})[name] = variants
            for reference in re.finditer(
                r"^          (?P<quantity>zero|one|two|few|many|other)="
                r"(?P<value>[@?].*?)"
                r"(?=^          (?:zero|one|two|few|many|other)="
                r"|^        (?:\(|Flag disabled values:)|\Z)",
                body,
                re.MULTILINE | re.DOTALL,
            ):
                result.setdefault("pluralReferences", {}).setdefault(name, {})[
                    reference.group("quantity")
                ] = (
                    reference.group("value")
                    .rstrip("\r\n")
                    .replace("\n          ", "\n")
                )
        elif kind == "array":
            values = re.search(
                r"^          \[(?P<values>.*?)\]$", body, re.MULTILINE | re.DOTALL
            )
            if values is not None:
                contents = compiled_array_contents(values.group("values"))
                entries = native_array_entries(contents, native_array_size(body))
                result.setdefault("arrays", {})[name] = [
                    entry["value"] for entry in entries
                ]
                if include_spans:
                    for index, entry in enumerate(entries):
                        if "spans" not in entry:
                            continue
                        spans = parse_styled_spans(
                            entry["spans"],
                            include_runtime=include_span_runtime,
                            visible_text=entry["value"],
                        )
                        if spans:
                            result.setdefault("styledSpans", {}).setdefault(
                                "arrays", {}
                            ).setdefault(name, {})[str(index)] = spans
                for index, entry in enumerate(entries):
                    if entry.get("reference") is not None:
                        result.setdefault("arrayReferences", {}).setdefault(name, {})[
                            str(index)
                        ] = entry["reference"]
                    elif include_primitives and entry.get("primitive") is not None:
                        result.setdefault("arrayPrimitiveValues", {}).setdefault(
                            name, {}
                        )[str(index)] = entry["primitive"]
        elif kind == "attr" and include_attributes:
            attribute = native_attribute(body)
            if attribute is not None:
                result.setdefault("attributes", {})[name] = attribute
                if include_attribute_configurations:
                    configuration = re.search(
                        r"^        \((?P<configuration>[^)]*)\)", body, re.MULTILINE
                    )
                    if configuration is None:
                        raise ValueError(
                            f"Android attribute {name!r} has no configuration"
                        )
                    result.setdefault("attributeConfigurations", {})[name] = (
                        configuration.group("configuration")
                    )
        elif kind == "styleable" and include_styleables:
            entries = [
                match.group("name")
                for match in re.finditer(
                    r"^\s+(?P<name>[A-Za-z_][A-Za-z0-9_.:-]*)\s*$",
                    body,
                    re.MULTILINE,
                )
            ]
            result.setdefault("styleables", {})[name] = entries
            if include_attribute_configurations:
                configuration = re.search(
                    r"^        \((?P<configuration>[^)]*)\)", body, re.MULTILINE
                )
                if configuration is None:
                    raise ValueError(f"Android styleable {name!r} has no configuration")
                result.setdefault("styleableConfigurations", {})[name] = (
                    configuration.group("configuration")
                )
    return result


def native_attribute(body: str) -> dict[str, object] | None:
    declaration = re.search(r"\(attr\) type=(?P<format>[^\s]+)", body)
    if declaration is None:
        return None
    attribute: dict[str, object] = {"format": declaration.group("format")}
    symbols = {
        match.group("name"): match.group("value")
        for match in re.finditer(
            r"^\s+(?P<name>[^\s=(]+)(?:\(0x[0-9a-fA-F]+\))?="
            r"(?P<value>0x[0-9a-fA-F]+)\s*$",
            body,
            re.MULTILINE,
        )
    }
    if symbols:
        attribute["symbols"] = symbols
    return attribute


def native_array_entries(contents: str, count: int) -> list[dict[str, str]]:
    boundaries = [match.start() for match in re.finditer(r",\s+", contents)]

    def entry(source: str) -> dict[str, str] | None:
        source = source.strip()
        if source.startswith('(styled string) "'):
            try:
                value, spans = native_string_parts(
                    source.removeprefix("(styled string) "), styled=True
                )
            except ValueError:
                return None
            return {"value": value, "spans": spans}
        if source.startswith('"'):
            if not source.endswith('"'):
                return None
            value, _ = native_string_parts(source, styled=False)
            return {"string": value, "value": value}
        if not source:
            return None
        if source.startswith(("@", "?")):
            return {"reference": source, "value": source}
        if re.search(r"[\s,]", source):
            return None
        return {
            "primitive": source,
            "value": source,
        }

    def parse(start: int, remaining: int) -> list[dict[str, str]] | None:
        if remaining == 1:
            last = entry(contents[start:])
            return None if last is None else [last]
        for boundary in boundaries:
            if boundary < start:
                continue
            current = entry(contents[start:boundary])
            if current is None:
                continue
            next_start = boundary + 1
            while next_start < len(contents) and contents[next_start].isspace():
                next_start += 1
            rest = parse(next_start, remaining - 1)
            if rest is not None:
                return [current, *rest]
        return None

    parsed = parse(0, count)
    if parsed is None:
        raise ValueError(f"Unable to parse {count} native Android array entries")
    return parsed


def array_entries(contents: str, count: int) -> list[dict[str, str]]:
    return native_array_entries(contents, count)


def parse_styled_spans(
    source: str, *, include_runtime: bool = False, visible_text: str | None = None
) -> list[dict[str, object]]:
    spans = []
    previous = 0
    for location in re.finditer(r":(?P<start>\d+),(?P<end>\d+)(?=\s|$)", source):
        descriptor = source[previous : location.start()].lstrip()
        previous = location.end()
        name = descriptor.split(";", 1)[0]
        span: dict[str, object] = {
            "name": name,
            "start": int(location.group("start")),
            "end": int(location.group("end")),
        }
        annotations = decode_span_attributes(descriptor)
        if annotations:
            span["attributes"] = {
                attribute["key"]: attribute["value"] for attribute in annotations
            }
        if include_runtime:
            span["encodedTag"] = descriptor
            if name == "annotation":
                span["runtimeAnnotations"] = annotations
            if name in {"font", "a"}:
                effects, error = decode_runtime_styles(name, descriptor)
                span["runtimeStyles"] = effects
                if error is not None:
                    span["runtimeError"] = error
            paragraph_kind = (
                "bullet"
                if descriptor == "li"
                else (
                    "height"
                    if name == "font"
                    and span.get("runtimeError") is None
                    and any(
                        effect["kind"] == "height"
                        for effect in span.get("runtimeStyles", [])
                    )
                    else None
                )
            )
            if paragraph_kind is not None and visible_text is not None:
                span["runtimeParagraph"] = runtime_paragraph(
                    visible_text,
                    int(span["start"]),
                    int(span["end"]) + 1,
                    paragraph_kind,
                )
        spans.append(span)
    return spans


def runtime_paragraph(
    text: str, source_start: int, source_end: int, kind: str
) -> dict[str, object]:
    encoded = text.encode("utf-16-le")
    units = [
        int.from_bytes(encoded[index : index + 2], "little")
        for index in range(0, len(encoded), 2)
    ]
    start = source_start
    end = source_end
    if start not in {0, len(units)} and units[start - 1] != ord("\n"):
        start -= 1
        while start > 0 and units[start - 1] != ord("\n"):
            start -= 1
    if end not in {0, len(units)} and units[end - 1] != ord("\n"):
        end += 1
        while end < len(units) and units[end - 1] != ord("\n"):
            end += 1
    return {
        "kind": kind,
        "sourceStart": source_start,
        "sourceEnd": source_end,
        "start": start,
        "end": end,
    }


def decode_runtime_styles(
    name: str, encoded: str
) -> tuple[list[dict[str, object]], str | None]:
    supported = (
        (("link", "href"),)
        if name == "a"
        else (
            ("height", "height"),
            ("size", "size"),
            ("foreground", "fgcolor"),
            ("foreground", "color"),
            ("background", "bgcolor"),
            ("face", "face"),
        )
    )
    effects = []
    for kind, attribute in supported:
        marker = f";{attribute}="
        start = encoded.find(marker)
        if start < 0:
            continue
        value = encoded[start + len(marker) :].split(";", 1)[0]
        if attribute in {"height", "size"} and not valid_runtime_integer(value):
            return effects, "NumberFormatException"
        effect: dict[str, object] = {
            "kind": kind,
            "attribute": attribute,
            "value": value,
        }
        if kind in {"foreground", "background"}:
            effect["color"] = runtime_color(value, kind == "foreground")
        effects.append(effect)
    return effects, None


def runtime_color(value: str, foreground: bool) -> dict[str, object]:
    if value.startswith("@"):
        package, separator, _ = value[1:].partition(":")
        if separator and package != "android":
            return {"mode": "fallback", "argb": "#ff000000"}
        return {
            "mode": "system",
            "reference": value,
            "fallbackArgb": "#ff000000",
            "stateful": foreground,
        }
    color = (
        color_hex(value) if value.startswith("#") else ANDROID_COLORS.get(value.lower())
    )
    if color is None:
        return {"mode": "fallback", "argb": "#ff000000"}
    return {"mode": "literal", "argb": f"#{color & 0xFFFFFFFF:08x}"}


def color_hex(value: str) -> int | None:
    if len(value) not in {7, 9}:
        return None
    source = value[1:]
    negative = source.startswith("-")
    source = source[1:] if source.startswith(("-", "+")) else source
    if not source:
        return None
    parsed = 0
    for character in source:
        digit = color_digit(character)
        if digit is None:
            return None
        parsed = parsed * 16 + digit
    if negative:
        parsed = -parsed
    if len(value) == 7:
        parsed |= 0xFF000000
    return parsed & 0xFFFFFFFF


def color_digit(character: str) -> int | None:
    if ord(character) > 0xFFFF:
        return None
    try:
        return unicodedata.decimal(character)
    except ValueError:
        if "A" <= character <= "F":
            return ord(character) - ord("A") + 10
        if "a" <= character <= "f":
            return ord(character) - ord("a") + 10
        if "Ａ" <= character <= "Ｆ":
            return ord(character) - ord("Ａ") + 10
        if "ａ" <= character <= "ｆ":
            return ord(character) - ord("ａ") + 10
        return None


def valid_runtime_integer(value: str) -> bool:
    negative = value.startswith("-")
    digits = value[1:] if value.startswith(("-", "+")) else value
    if not digits:
        return False
    limit = 2**31 if negative else 2**31 - 1
    result = 0
    for character in digits:
        if ord(character) > 0xFFFF:
            return False
        try:
            digit = unicodedata.decimal(character)
        except ValueError:
            return False
        result = result * 10 + digit
        if result > limit:
            return False
    return True


def decode_span_attributes(encoded: str) -> list[dict[str, str]]:
    annotations = []
    position = encoded.find(";")
    while position >= 0 and position < len(encoded):
        equals = encoded.find("=", position)
        if equals < 0:
            break
        next_position = encoded.find(";", equals)
        if next_position < 0:
            next_position = len(encoded)
        annotations.append(
            {
                "key": encoded[position + 1 : equals],
                "value": encoded[equals + 1 : next_position],
            }
        )
        position = next_position if next_position < len(encoded) else -1
    return annotations


def locate_aapt2(download: bool) -> Path:
    override = os.environ.get("MOJITO_AAPT2")
    if override:
        return Path(override)
    existing = shutil.which("aapt2")
    if existing:
        return Path(existing)

    system = platform.system().lower()
    classifier = {"darwin": "osx", "linux": "linux", "windows": "windows"}.get(system)
    if classifier is None:
        raise SystemExit(f"AAPT2 is unavailable for host platform {system!r}")

    directory = Path.home() / ".cache" / "mojito-file-formats" / "aapt2" / VERSION
    executable = directory / ("aapt2.exe" if classifier == "windows" else "aapt2")
    if executable.is_file():
        return executable
    if not download:
        raise SystemExit("AAPT2 not found; rerun with --download or set MOJITO_AAPT2")

    directory.mkdir(parents=True, exist_ok=True)
    name = f"aapt2-{VERSION}-{classifier}.jar"
    url = f"{MAVEN}/{VERSION}/{name}"
    archive = directory / name
    urllib.request.urlretrieve(url, archive)
    expected = (
        MAC_SHA256
        if classifier == "osx"
        else urllib.request.urlopen(f"{url}.sha256", timeout=20).read().decode().strip()
    )
    actual = hashlib.sha256(archive.read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f"AAPT2 SHA-256 mismatch: expected {expected}, got {actual}")
    with zipfile.ZipFile(archive) as packaged:
        executable.write_bytes(packaged.read(executable.name))
    executable.chmod(0o755)
    return executable


if __name__ == "__main__":
    raise SystemExit(main())
