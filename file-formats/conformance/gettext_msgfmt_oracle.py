#!/usr/bin/env python3
"""Compile original gettext fixtures with GNU msgfmt and inspect the real MO catalog."""

from __future__ import annotations

import gettext
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
HEADER = 'msgid ""\nmsgstr ""\n'
CHARSET = '"Content-Type: text/plain; charset=UTF-8\\n"\n'


def main() -> int:
    executable = shutil.which("msgfmt")
    if executable is None:
        raise SystemExit(
            "GNU msgfmt is unavailable; install gettext to run this oracle"
        )

    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    cases = [case for case in manifest["cases"] if case["format"] == "gettext_po"]
    accepted = rejected = snapshots = normalized = lossy = domain_snapshots = 0
    domain_catalogs = domain_selections = skeletons = 0
    domain_skeletons = domain_skeleton_selections = 0
    workflows = 0

    with tempfile.TemporaryDirectory(prefix="mojito-gettext-msgfmt-") as directory:
        for case in cases:
            source = (ROOT / case["input"]).read_text(encoding="utf-8")
            case_directory = Path(directory) / case["id"]
            case_directory.mkdir()
            destination = case_directory / "source.po"
            configured = ensure_charset_header(source)
            if case.get("lineEndings") == "CRLF":
                configured = configured.replace("\r\n", "\n").replace("\n", "\r\n")
            elif case.get("lineEndings") == "CR":
                configured = configured.replace("\r\n", "\n").replace("\n", "\r")
            destination.write_bytes(encode(configured, case.get("encoding")))
            binary = destination.with_suffix(".mo")
            command = [executable, "--use-fuzzy", "--check-format"]
            if "Plural-Forms:" in source:
                command.append("--check-header")
            if "gettextDomainCompiled" not in case:
                command.extend(["-o", str(binary)])
            command.append(str(destination))
            result = subprocess.run(
                command,
                capture_output=True,
                text=True,
                errors="replace",
                cwd=case_directory,
            )
            policy = case.get("gettextOracle")
            should_accept = policy == "accept" or (
                policy != "reject" and "expected" in case
            )
            if (result.returncode == 0) != should_accept:
                expectation = "accept" if should_accept else "reject"
                print(
                    f"{case['id']}: GNU msgfmt should {expectation} this fixture "
                    f"but exited {result.returncode}\n{result.stdout}{result.stderr}",
                    file=sys.stderr,
                )
                return 1

            if result.returncode:
                rejected += 1
                continue
            accepted += 1
            if "gettextLossyCompiled" in case:
                expected = json.loads(
                    (ROOT / case["gettextLossyCompiled"]).read_text(encoding="utf-8")
                )
                with binary.open("rb") as stream:
                    actual = compiled_catalog(gettext.GNUTranslations(stream))
                if actual != expected:
                    print(
                        f"{case['id']}: GNU lossy MO snapshot mismatch\n"
                        f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                        f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}",
                        file=sys.stderr,
                    )
                    return 1
                lossy += 1
            if "gettextNativeDomains" in case:
                expected_domains = case["gettextNativeDomains"]["source"]
                actual_domains = native_domains(destination)
                if actual_domains != expected_domains:
                    print(
                        f"{case['id']}: GNU native source domains differ\n"
                        f"expected: {expected_domains}\nactual: {actual_domains}",
                        file=sys.stderr,
                    )
                    return 1
                domain_snapshots += 1
            if "gettextDomainCompiled" in case:
                expected = json.loads(
                    (ROOT / case["gettextDomainCompiled"]).read_text(encoding="utf-8")
                )
                actual = compiled_domains(case_directory)
                if actual != expected:
                    print(
                        f"{case['id']}: GNU domain MO snapshot mismatch\n"
                        f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                        f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}",
                        file=sys.stderr,
                    )
                    return 1
                domain_catalogs += 1
                for sample in case.get("gettextDomainRuntimeSamples", []):
                    verify_domain_selection(case, case_directory, sample)
                    domain_selections += 1
                if case.get("gettextSingleOutput") == "reject":
                    single = case_directory / "combined-output.mo"
                    combined = subprocess.run(
                        [*command[:-1], "-o", str(single), str(destination)],
                        capture_output=True,
                        text=True,
                        errors="replace",
                        cwd=case_directory,
                    )
                    if combined.returncode == 0:
                        print(
                            f"{case['id']}: GNU single-output mode unexpectedly merged domains",
                            file=sys.stderr,
                        )
                        return 1
                if "gettextNormalized" in case:
                    normalized_source = ROOT / case["gettextNormalized"]
                    normalized_directory = case_directory / "normalized"
                    normalized_directory.mkdir()
                    normalized_command = [executable, "--use-fuzzy", "--check-format"]
                    if "Plural-Forms:" in normalized_source.read_text(encoding="utf-8"):
                        normalized_command.append("--check-header")
                    normalized_command.append(str(normalized_source))
                    repeated = subprocess.run(
                        normalized_command,
                        capture_output=True,
                        text=True,
                        errors="replace",
                        cwd=normalized_directory,
                    )
                    if repeated.returncode:
                        print(
                            f"{case['id']}: GNU rejected normalized multidomain PO\n"
                            f"{repeated.stdout}{repeated.stderr}",
                            file=sys.stderr,
                        )
                        return 1
                    actual = compiled_domains(normalized_directory)
                    if actual != expected:
                        print(
                            f"{case['id']}: normalized GNU domain MO catalogs differ\n"
                            f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                            f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}",
                            file=sys.stderr,
                        )
                        return 1
                    for sample in case.get("gettextDomainRuntimeSamples", []):
                        verify_domain_selection(case, normalized_directory, sample)
                    if "gettextNativeDomains" in case:
                        expected_domains = case["gettextNativeDomains"]["normalized"]
                        actual_domains = native_domains(normalized_source)
                        if actual_domains != expected_domains:
                            print(
                                f"{case['id']}: GNU normalized domains differ\n"
                                f"expected: {expected_domains}\nactual: {actual_domains}",
                                file=sys.stderr,
                            )
                            return 1
                    normalized += 1
                continue
            if "gettextCompiled" not in case:
                continue
            expected = json.loads(
                (ROOT / case["gettextCompiled"]).read_text(encoding="utf-8")
            )
            with binary.open("rb") as stream:
                actual = compiled_catalog(gettext.GNUTranslations(stream))
            if actual != expected:
                print(
                    f"{case['id']}: GNU MO catalog snapshot mismatch\n"
                    f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                    f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}",
                    file=sys.stderr,
                )
                return 1
            snapshots += 1
            if "gettextNormalized" in case:
                normalized_source = ROOT / case["gettextNormalized"]
                normalized_binary = destination.with_name(f"{case['id']}.normalized.mo")
                normalized_command = [executable, "--use-fuzzy", "--check-format"]
                if "Plural-Forms:" in normalized_source.read_text(encoding="utf-8"):
                    normalized_command.append("--check-header")
                normalized_command.extend(
                    ["-o", str(normalized_binary), str(normalized_source)]
                )
                repeated = subprocess.run(
                    normalized_command, capture_output=True, text=True
                )
                if repeated.returncode:
                    print(
                        f"{case['id']}: GNU msgfmt rejected normalized gettext PO\n"
                        f"{repeated.stdout}{repeated.stderr}",
                        file=sys.stderr,
                    )
                    return 1
                with normalized_binary.open("rb") as stream:
                    repeated_catalog = compiled_catalog(gettext.GNUTranslations(stream))
                if repeated_catalog != expected:
                    print(
                        f"{case['id']}: normalized GNU MO catalog differs from the original\n"
                        f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                        f"actual: {json.dumps(repeated_catalog, ensure_ascii=False, indent=2)}",
                        file=sys.stderr,
                    )
                    return 1
                if "gettextNativeDomains" in case:
                    expected_domains = case["gettextNativeDomains"]["normalized"]
                    actual_domains = native_domains(normalized_source)
                    if actual_domains != expected_domains:
                        print(
                            f"{case['id']}: GNU normalized domains differ\n"
                            f"expected: {expected_domains}\nactual: {actual_domains}",
                            file=sys.stderr,
                        )
                        return 1
                normalized += 1

        for case in manifest.get("sourceSkeletons", []):
            if case["format"] != "gettext_po":
                continue
            case_directory = Path(directory) / case["id"]
            case_directory.mkdir()
            encoding = case.get("encoding")
            multidomain = "gettextDomainCompiled" in case
            for resource, snapshot in (
                (
                    "input",
                    "gettextDomainCompiled" if multidomain else "gettextCompiled",
                ),
                (
                    "localized",
                    "gettextLocalizedDomainCompiled"
                    if multidomain
                    else "gettextLocalizedCompiled",
                ),
            ):
                source = (ROOT / case[resource]).read_text(encoding="utf-8")
                if case.get("lineEndings") == "CRLF":
                    source = source.replace("\r\n", "\n").replace("\n", "\r\n")
                elif case.get("lineEndings") == "CR":
                    source = source.replace("\r\n", "\n").replace("\n", "\r")
                output_directory = (
                    case_directory / resource if multidomain else case_directory
                )
                if multidomain:
                    output_directory.mkdir()
                destination = output_directory / f"{resource}.po"
                binary = destination.with_suffix(".mo")
                destination.write_bytes(encode(source, encoding))
                command = [executable, "--use-fuzzy", "--check-format"]
                if not multidomain:
                    command.extend(["-o", str(binary)])
                command.append(str(destination))
                result = subprocess.run(
                    command,
                    capture_output=True,
                    text=True,
                    errors="replace",
                    cwd=output_directory,
                )
                if result.returncode:
                    print(
                        f"{case['id']}: GNU msgfmt rejected source-preserving {resource} PO\n"
                        f"{result.stdout}{result.stderr}",
                        file=sys.stderr,
                    )
                    return 1
                expected = json.loads(
                    (ROOT / case[snapshot]).read_text(encoding="utf-8")
                )
                if multidomain:
                    actual = compiled_domains(output_directory)
                else:
                    with binary.open("rb") as stream:
                        actual = compiled_catalog(gettext.GNUTranslations(stream))
                if actual != expected:
                    print(
                        f"{case['id']}: source-preserving {resource} "
                        f"{'split-domain ' if multidomain else ''}MO snapshot mismatch\n"
                        f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                        f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}",
                        file=sys.stderr,
                    )
                    return 1
                if multidomain:
                    state = "source" if resource == "input" else "localized"
                    expected_domains = case["gettextNativeDomains"][state]
                    actual_domains = native_domains(destination)
                    if actual_domains != expected_domains:
                        print(
                            f"{case['id']}: GNU source-preserving {state} domains differ\n"
                            f"expected: {expected_domains}\nactual: {actual_domains}",
                            file=sys.stderr,
                        )
                        return 1
                    samples = case.get(
                        "gettextOriginalRuntimeSamples"
                        if resource == "input"
                        else "gettextLocalizedRuntimeSamples",
                        [],
                    )
                    for sample in samples:
                        verify_domain_selection(case, output_directory, sample)
                        domain_skeleton_selections += 1
                    if case.get("gettextSingleOutput") == "reject":
                        combined = subprocess.run(
                            [
                                *command[:-1],
                                "-o",
                                str(output_directory / "combined-output.mo"),
                                str(destination),
                            ],
                            capture_output=True,
                            text=True,
                            errors="replace",
                            cwd=output_directory,
                        )
                        if combined.returncode == 0:
                            print(
                                f"{case['id']}: GNU source-preserving single-output mode "
                                "unexpectedly merged independent domains",
                                file=sys.stderr,
                            )
                            return 1
            if multidomain:
                domain_skeletons += 1
            skeletons += 1

        workflows = verify_workflow_outputs(executable, manifest, Path(directory))

    print(
        f"GNU gettext verified {accepted + rejected} original PO fixtures "
        f"({accepted} accepted, {rejected} rejected, {snapshots} compiled-MO snapshots, "
        f"{normalized} normalized writer round trips, "
        f"{lossy} native lossy-output snapshots, "
        f"{domain_snapshots} native domain snapshots, "
        f"{domain_catalogs} split-domain MO catalogs, "
        f"{domain_selections} native domain plural selections) and "
        f"{skeletons} source-preserving original/localized MO round trips "
        f"({domain_skeletons} source-owned split-domain templates, "
        f"{domain_skeleton_selections} native source-domain plural selections)."
    )
    print(
        f"GNU gettext verified {workflows} configured PO workflow source and localized MO catalogs."
    )
    return 0


def verify_workflow_outputs(
    executable: str, manifest: dict[str, object], directory: Path
) -> int:
    """Compile configured workflow inputs and verify exact localized gettext entries."""
    cases = [
        case
        for case in manifest.get("workflowCases", [])
        if case["format"] == "gettext_po" and case.get("localized")
    ]
    for case in cases:
        for kind in ("input", "localized"):
            resource = directory / "workflows" / case["id"] / f"{kind}.po"
            resource.parent.mkdir(parents=True, exist_ok=True)
            resource.write_bytes((ROOT / case[kind]).read_bytes())
            binary = resource.with_suffix(".mo")
            result = subprocess.run(
                [executable, "--use-fuzzy", "--check-format", "-o", str(binary), str(resource)],
                capture_output=True,
                text=True,
            )
            if result.returncode:
                raise SystemExit(
                    f"{case['id']}: GNU msgfmt rejected configured gettext {kind}\n"
                    f"{result.stdout}{result.stderr}"
                )
            if kind == "localized":
                with binary.open("rb") as stream:
                    actual = compiled_catalog(gettext.GNUTranslations(stream))
                expected = {"entries": case["translations"]}
                if actual != expected:
                    raise SystemExit(
                        f"{case['id']}: configured GNU MO translations differ\n"
                        f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                        f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}"
                    )
    return len(cases)


def ensure_charset_header(source: str) -> str:
    if re.search(r"Content-Type:[^\r\n]*?charset=", source, re.IGNORECASE):
        return source
    if HEADER in source:
        return source.replace(HEADER, HEADER + CHARSET, 1)
    return HEADER + CHARSET + "\n" + source


def native_domains(source: Path) -> list[str]:
    executable = shutil.which("msgcat")
    if executable is None:
        raise SystemExit(
            "GNU msgcat is unavailable; install gettext to inspect native domains"
        )
    result = subprocess.run(
        [executable, "--use-first", "--no-location", "--no-wrap", str(source)],
        capture_output=True,
        text=True,
        check=True,
    )
    return [
        json.loads(match.group(1))
        for match in re.finditer(
            r"^domain\s+(\"(?:[^\"\\]|\\.)*\")$", result.stdout, re.MULTILINE
        )
    ]


def compiled_domains(directory: Path) -> dict[str, dict[str, object]]:
    domains: dict[str, dict[str, object]] = {}
    for binary in sorted(directory.glob("*.mo")):
        with binary.open("rb") as stream:
            translation = gettext.GNUTranslations(stream)
        catalog = compiled_catalog(translation)
        header = translation._catalog.get("", "")
        if isinstance(header, str):
            info = translation.info()
            locale = info.get("language")
            forms = info.get("plural-forms")
            parsed: dict[str, object] = {}
            if locale:
                parsed["locale"] = locale.strip().replace("_", "-")
            if forms:
                count = re.search(r"(?:^|;)\s*nplurals\s*=\s*(\d+)", forms)
                expression = re.search(r"(?:^|;)\s*plural\s*=\s*([^;]+)", forms)
                if count and expression:
                    parsed["pluralForms"] = {
                        "nplurals": int(count.group(1)),
                        "expression": expression.group(1).strip(),
                    }
            fields = native_header_fields(header)
            if fields:
                parsed["fields"] = fields
            if parsed:
                catalog["header"] = parsed
        domains[binary.name[: -len(".mo")]] = catalog
    return domains


def native_header_fields(header: str) -> list[dict[str, str]]:
    fields: list[dict[str, str]] = []
    previous: dict[str, str] | None = None
    for line in header.splitlines():
        if not line.strip():
            continue
        if ":" not in line:
            if previous is not None:
                previous["value"] += "\n" + line.strip()
            continue
        name, value = line.split(":", 1)
        previous = None
        if name.lower() not in {"content-type", "language", "plural-forms"}:
            previous = {"name": name, "value": value.strip()}
            fields.append(previous)
    return fields


def verify_domain_selection(
    case: dict[str, object], directory: Path, sample: dict[str, object]
) -> None:
    binary = directory / f"{sample['domain']}.mo"
    with binary.open("rb") as stream:
        catalog = gettext.GNUTranslations(stream)
    selected = catalog.ngettext(
        str(sample["message"]), str(sample["plural"]), int(sample["value"])
    )
    actual = selected % int(sample["value"])
    if actual != sample["expected"]:
        raise SystemExit(
            f"{case['id']}/{sample['domain']}: n={sample['value']} "
            f"expected {sample['expected']!r}, got {actual!r}"
        )


def encode(source: str, encoding: str | None) -> bytes:
    if encoding == "ISO-8859-1":
        return source.encode("iso-8859-1")
    if encoding == "CP1252":
        return source.encode("cp1252")
    if encoding == "US-ASCII":
        return source.encode("ascii")
    if encoding == "UTF-8-BOM":
        return source.encode("utf-8-sig")
    if encoding == "UTF-16LE-BOM":
        return b"\xff\xfe" + source.encode("utf-16le")
    if encoding == "UTF-16BE-BOM":
        return b"\xfe\xff" + source.encode("utf-16be")
    return source.encode("utf-8")


def compiled_catalog(translations: gettext.GNUTranslations) -> dict[str, object]:
    result: dict[str, dict[str, object]] = {}
    for key, value in translations._catalog.items():
        if not key:
            continue
        if isinstance(key, tuple):
            message, index = key
            plurals = result.setdefault("plurals", {})
            variants = plurals.setdefault(message, {})
            assert isinstance(variants, dict)
            variants[str(index)] = value
        else:
            result.setdefault("entries", {})[key] = value
    return result


if __name__ == "__main__":
    raise SystemExit(main())
