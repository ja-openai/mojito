#!/usr/bin/env python3
"""Run the shared contract, native platform oracles, and Java/Rust implementations."""

from __future__ import annotations

import argparse
import importlib.util
import os
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONFORMANCE = ROOT / "file-formats" / "conformance"
RUST = ROOT / "file-formats" / "rust" / "mojito-file-formats"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="run Cargo without network access")
    parser.add_argument("--skip-native", action="store_true", help="skip executable platform oracles")
    parser.add_argument("--skip-java", action="store_true", help="skip integrated Maven Java tests")
    parser.add_argument("--skip-rust", action="store_true", help="skip independent Cargo Rust tests")
    parser.add_argument(
        "--download-aapt2",
        action="store_true",
        help="download and SHA-256 verify the official Google AAPT2 compiler",
    )
    args = parser.parse_args()

    steps: list[tuple[str, list[str], Path]] = [
        ("Shared versioned fixture contract", [sys.executable, str(CONFORMANCE / "verify.py")], ROOT)
    ]
    if not args.skip_native:
        aapt2 = [sys.executable, str(CONFORMANCE / "android_aapt2_oracle.py")]
        if args.download_aapt2:
            aapt2.append("--download")
        steps.append(("Official Android AAPT2 compiler", aapt2, ROOT))
        optional_oracle(
            steps,
            "Foundation Apple strings parser",
            "plutil",
            "apple_plutil_oracle.py",
        )
        xcode_tool = Path("/Applications/Xcode.app/Contents/Developer/usr/bin/xcstringstool")
        if shutil.which("xcstringstool") or xcode_tool.is_file():
            steps.append(
                (
                    "Official Xcode String Catalog compiler",
                    [sys.executable, str(CONFORMANCE / "apple_xcstringstool_oracle.py")],
                    ROOT,
                )
            )
        else:
            print("SKIP official Xcode String Catalog compiler: xcstringstool unavailable", flush=True)
        optional_oracle(steps, "GNU gettext MO compiler", "msgfmt", "gettext_msgfmt_oracle.py")
        if shutil.which("javac") and shutil.which("java"):
            steps.append(
                (
                    "JDK java.util.Properties parser",
                    [sys.executable, str(CONFORMANCE / "java_properties_oracle.py")],
                    ROOT,
                )
            )
        else:
            print("SKIP JDK java.util.Properties parser: java/javac unavailable", flush=True)
        if importlib.util.find_spec("yaml") is not None:
            steps.append(
                (
                    "PyYAML localized scalar parser",
                    [sys.executable, str(CONFORMANCE / "yaml_runtime_oracle.py")],
                    ROOT,
                )
            )
        else:
            print("SKIP PyYAML localized scalar parser: PyYAML unavailable", flush=True)
        if shutil.which("node"):
            steps.append(
                (
                    "Node JavaScript source runtime",
                    ["node", str(CONFORMANCE / "javascript_runtime_oracle.mjs")],
                    ROOT,
                )
            )
        else:
            print("SKIP Node JavaScript source runtime: node unavailable", flush=True)
        modules = formatjs_node_modules()
        if shutil.which("node") and modules is not None:
            steps.append(
                (
                    "Real FormatJS ICU message runtime",
                    ["node", str(CONFORMANCE / "formatjs_runtime_oracle.mjs"), str(modules)],
                    ROOT,
                )
            )
        else:
            print("SKIP real FormatJS ICU runtime: frontend dependencies unavailable", flush=True)

    if not args.skip_java:
        steps.append(
            (
                "Integrated Java conformance suite",
                [
                    "mvn",
                    "-q",
                    "-pl",
                    "common",
                    "-Dtest=LocalizationFileConvertersConformanceTest,OkapiLocalizationFileConvertersConformanceTest",
                    "test",
                ],
                ROOT,
            )
        )
    if not args.skip_rust:
        cargo = ["cargo", "test"]
        if args.offline:
            cargo.append("--offline")
        steps.append(("Independent Rust conformance suite", cargo, RUST))

    for label, command, directory in steps:
        print(f"\n== {label} ==", flush=True)
        result = subprocess.run(command, cwd=directory, env=os.environ.copy())
        if result.returncode:
            return result.returncode
    print(f"\nAll {len(steps)} portable localization conformance steps passed.")
    return 0


def optional_oracle(
    steps: list[tuple[str, list[str], Path]], label: str, executable: str, script: str
) -> None:
    if shutil.which(executable):
        steps.append((label, [sys.executable, str(CONFORMANCE / script)], ROOT))
    else:
        print(f"SKIP {label}: {executable} unavailable", flush=True)


def formatjs_node_modules() -> Path | None:
    candidates = [ROOT / "webapp" / "frontend" / "node_modules"]
    result = subprocess.run(
        ["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    if result.returncode == 0:
        candidates.append(Path(result.stdout.strip()).parent / "webapp" / "frontend" / "node_modules")
    for candidate in candidates:
        if (candidate / "intl-messageformat" / "index.js").is_file() and (
            candidate / "@formatjs" / "icu-messageformat-parser" / "index.js"
        ).is_file():
            return candidate
    return None


if __name__ == "__main__":
    raise SystemExit(main())
