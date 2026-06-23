from __future__ import annotations

import json
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

from mojito_mf2._locale_key import canonical_locale_key, feature_lookup_chain, locale_lookup_chain
from mojito_mf2 import (
    MF2Error,
    format_message_to_parts,
    format_message,
)
from mojito_mf2.date_time_core import (
    date_time_core_function_registry,
    format_date_core,
    format_date_time_core,
    format_time_core,
)
from mojito_mf2.number_core import format_number_core, number_core_function_registry
from mojito_mf2.parser import parse_to_model


class ConformanceFailure(Exception):
    pass


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if args and args[0] == "--number-core":
        return _check_number_core(args[1:])
    if args and args[0] == "--date-time-core":
        return _check_date_time_core(args[1:])
    if args and args[0] == "--number-core-bench":
        return _benchmark_number_core(args[1:])
    if args and args[0] == "--date-time-core-bench":
        return _benchmark_date_time_core(args[1:])

    fixture_dir = (
        Path(args[0])
        if args
        else Path(__file__).resolve().parents[2] / "conformance" / "fixtures" / "source-to-model"
    )

    checked_cases = 0
    checked_source_cases = 0
    checked_parts_cases = 0
    checked_fallback_cases = 0
    checked_fallback_parts_cases = 0
    for fixture_path in sorted(fixture_dir.glob("*.json")):
        fixture = _read_json(fixture_path)
        source_result = parse_to_model(fixture["source"])
        if source_result.model != fixture["expectedModel"]:
            print(
                f"{fixture_path.name}: expected parsed model {fixture['expectedModel']!r}, "
                f"got {source_result.model!r}; diagnostics={source_result.diagnostics!r}",
                file=sys.stderr,
            )
            return 1
        checked_source_cases += 1
        for format_case in fixture.get("formatCases", []):
            actual = format_message(
                fixture["expectedModel"],
                format_case.get("arguments", {}),
                format_case.get("locale", "en"),
                bidi_isolation=format_case.get("bidiIsolation", "none"),
            )
            expected = format_case["expected"]
            if actual.value != expected or actual.errors:
                print(
                    f"{fixture_path.name}: expected {expected!r}, got {actual.value!r}; "
                    f"errors={actual.errors!r}",
                    file=sys.stderr,
                )
                return 1
            checked_cases += 1
        for parts_case in fixture.get("partsCases", []):
            actual = format_message_to_parts(
                fixture["expectedModel"],
                parts_case.get("arguments", {}),
                parts_case.get("locale", "en"),
            )
            expected = parts_case["expected"]
            if actual.parts != expected or actual.errors:
                print(
                    f"{fixture_path.name}: expected parts {expected!r}, got {actual.parts!r}; "
                    f"errors={actual.errors!r}",
                    file=sys.stderr,
                )
                return 1
            checked_parts_cases += 1
        for fallback_case in fixture.get("fallbackCases", []):
            actual = format_message(
                fixture["expectedModel"],
                fallback_case.get("arguments", {}),
                fallback_case.get("locale", "en"),
                bidi_isolation=fallback_case.get("bidiIsolation", "none"),
            )
            expected = fallback_case["expected"]
            if actual.value != expected:
                print(
                    f"{fixture_path.name}: expected fallback {expected!r}, got {actual.value!r}",
                    file=sys.stderr,
                )
                return 1
            _assert_error_codes(fixture_path, "fallback errors", actual.errors, fallback_case)
            checked_fallback_cases += 1
        for parts_case in fixture.get("fallbackPartsCases", []):
            actual = format_message_to_parts(
                fixture["expectedModel"],
                parts_case.get("arguments", {}),
                parts_case.get("locale", "en"),
            )
            expected = parts_case["expected"]
            if actual.parts != expected:
                print(
                    f"{fixture_path.name}: expected fallback parts {expected!r}, got {actual.parts!r}",
                    file=sys.stderr,
                )
                return 1
            _assert_error_codes(fixture_path, "fallback parts errors", actual.errors, parts_case)
            checked_fallback_parts_cases += 1

    try:
        checked_invalid_source_cases = _check_invalid_source_fixtures(fixture_dir.parent)
        checked_error_cases = _check_format_error_fixtures(fixture_dir.parent)
        checked_locale_key_cases = _check_locale_key_fixtures(fixture_dir.parent)
    except ConformanceFailure as error:
        print(str(error), file=sys.stderr)
        return 1
    print(
        "Python MF2 conformance runner passed "
        f"{checked_source_cases} source models, "
        f"{checked_cases} format cases, {checked_parts_cases} parts cases, "
        f"{checked_fallback_cases} fallback cases, "
        f"{checked_fallback_parts_cases} fallback parts cases, "
        f"{checked_invalid_source_cases} invalid source cases, "
        f"{checked_error_cases} format error cases, "
        f"and {checked_locale_key_cases} locale key cases."
    )
    return 0


def _check_number_core(args: list[str]) -> int:
    fixture_path = _number_core_fixture(args)
    fixture = _read_json(fixture_path)

    try:
        checked_format_cases = 0
        for item in fixture.get("formatCases", []):
            actual = format_number_core(
                item["value"],
                locale=item["locale"],
                **item.get("options", {}),
            )
            if actual != item["expected"]:
                raise ConformanceFailure(
                    f"{item['name']}: expected {item['expected']!r}, got {actual!r}"
                )
            checked_format_cases += 1

        reference_cases = fixture.get("intlReferenceCases", [])
        reference_outputs = _node_intl_number_outputs(reference_cases)
        checked_reference_cases = 0
        for item, expected in zip(reference_cases, reference_outputs):
            actual = format_number_core(
                item["value"],
                locale=item["locale"],
                **item.get("options", {}),
            )
            if actual != expected:
                raise ConformanceFailure(
                    f"Intl number reference {item['locale']} {item.get('options', {})}: "
                    f"expected {expected!r}, got {actual!r}"
                )
            checked_reference_cases += 1

        checked_error_cases = 0
        for item in fixture.get("errorCases", []):
            try:
                format_number_core(
                    item["value"],
                    locale=item["locale"],
                    **item.get("options", {}),
                )
            except MF2Error as error:
                if error.code != item["expectedError"]:
                    raise ConformanceFailure(
                        f"{item['name']}: expected error {item['expectedError']!r}, got {error.code!r}"
                    ) from error
            else:
                raise ConformanceFailure(f"{item['name']}: expected format error, got success")
            checked_error_cases += 1

        checked_registry_cases = _check_number_core_registry(
            fixture.get("registryCases", [])
        )
        checked_registry_error_cases = _check_number_core_registry_errors(
            fixture.get("registryErrorCases", [])
        )
    except ConformanceFailure as error:
        print(str(error), file=sys.stderr)
        return 1

    print(
        "Python number core test passed "
        f"{checked_format_cases} format cases, "
        f"{checked_reference_cases} Intl reference cases, "
        f"{checked_error_cases} error cases, "
        f"{checked_registry_cases} registry cases, and "
        f"{checked_registry_error_cases} registry error cases."
    )
    return 0


def _check_date_time_core(args: list[str]) -> int:
    fixture_path = _date_time_core_fixture(args)
    fixture = _read_json(fixture_path)

    try:
        checked_format_cases = 0
        for item in fixture.get("formatCases", []):
            actual = _format_date_time_core_item(item)
            if actual != item["expected"]:
                raise ConformanceFailure(
                    f"{item['name']}: expected {item['expected']!r}, got {actual!r}"
                )
            checked_format_cases += 1

        checked_numeric_timestamp_cases = 0
        for item in fixture.get("numericTimestampCases", []):
            actual = _format_date_time_core_item(item)
            if actual != item["expected"]:
                raise ConformanceFailure(
                    f"{item['name']}: expected {item['expected']!r}, got {actual!r}"
                )
            checked_numeric_timestamp_cases += 1

        reference_cases = fixture.get("intlReferenceCases", [])
        reference_outputs = _node_intl_date_time_outputs(reference_cases)
        checked_reference_cases = 0
        for item, expected in zip(reference_cases, reference_outputs):
            actual = _format_date_time_core_item(item)
            if actual != expected:
                raise ConformanceFailure(
                    f"Intl date/time reference {item['kind']} {item['locale']} {item.get('options', {})}: "
                    f"expected {expected!r}, got {actual!r}"
                )
            checked_reference_cases += 1

        semantic_reference_cases = fixture.get("semanticStyleReferenceCases", [])
        semantic_reference_outputs = _node_intl_date_time_outputs(
            [_date_time_reference_item(item) for item in semantic_reference_cases]
        )
        checked_semantic_reference_cases = 0
        for item, expected in zip(semantic_reference_cases, semantic_reference_outputs):
            actual = _format_date_time_core_item(item)
            if actual != expected:
                raise ConformanceFailure(
                    f"Semantic style reference {item['name']}: expected {expected!r}, got {actual!r}"
                )
            checked_semantic_reference_cases += 1

        checked_error_cases = 0
        for item in fixture.get("errorCases", []):
            try:
                _format_date_time_core_item(item)
            except MF2Error as error:
                if error.code != item["expectedError"]:
                    raise ConformanceFailure(
                        f"{item['name']}: expected error {item['expectedError']!r}, got {error.code!r}"
                    ) from error
            else:
                raise ConformanceFailure(f"{item['name']}: expected format error, got success")
            checked_error_cases += 1

        checked_registry_cases = _check_date_time_core_registry_cases(
            fixture.get("registryFormatCases", [])
        )
        checked_registry_error_cases = _check_date_time_core_registry_error_cases(
            fixture.get("registryErrorCases", [])
        )
        _check_date_time_core_registry()
    except ConformanceFailure as error:
        print(str(error), file=sys.stderr)
        return 1

    print(
        "Python date/time core test passed "
        f"{checked_format_cases} format cases, "
        f"{checked_numeric_timestamp_cases} numeric timestamp cases, "
        f"{checked_reference_cases} Intl reference cases, "
        f"{checked_semantic_reference_cases} semantic style reference cases, "
        f"{checked_error_cases} error cases, "
        f"{checked_registry_cases} registry format cases, "
        f"{checked_registry_error_cases} registry error cases, and registry integration."
    )
    return 0


def _benchmark_number_core(args: list[str]) -> int:
    fixture_path = _number_core_fixture(args)
    iterations, warmup_iterations = _benchmark_iterations(args)
    fixture = _read_json(fixture_path)
    cases = [
        {
            "value": item["value"],
            "locale": item["locale"],
            "options": item.get("options", {}),
        }
        for item in fixture.get("formatCases", [])
    ]
    _run_string_benchmark(
        "python-number-core-format",
        cases,
        iterations,
        warmup_iterations,
        lambda item: format_number_core(item["value"], locale=item["locale"], **item["options"]),
    )
    return 0


def _benchmark_date_time_core(args: list[str]) -> int:
    fixture_path = _date_time_core_fixture(args)
    iterations, warmup_iterations = _benchmark_iterations(args)
    fixture = _read_json(fixture_path)
    cases = list(fixture.get("formatCases", []))
    _run_string_benchmark(
        "python-datetime-core-format",
        cases,
        iterations,
        warmup_iterations,
        _format_date_time_core_item,
    )
    return 0


def _run_string_benchmark(
    label: str,
    cases: list[Any],
    iterations: int,
    warmup_iterations: int,
    formatter: Any,
) -> None:
    if not cases:
        raise SystemExit(f"{label}: no benchmark cases")
    for index in range(warmup_iterations):
        formatter(cases[index % len(cases)])

    started = time.perf_counter()
    byte_count = 0
    for index in range(iterations):
        byte_count += len(formatter(cases[index % len(cases)]).encode("utf-8"))
    seconds = time.perf_counter() - started
    print(
        f"{label} iterations={iterations} warmup={warmup_iterations} "
        f"cases={len(cases)} seconds={seconds:.6f} "
        f"ns_per_op={(seconds * 1_000_000_000 / iterations):.1f} "
        f"ops_per_second={(iterations / seconds):.0f} bytes={byte_count}"
    )


def _check_number_core_registry(registry_cases: list[dict[str, Any]]) -> int:
    checked = 0
    currency_model = parse_to_model("Total: {$amount :currency currency=USD}").model
    if currency_model is None:
        raise ConformanceFailure("number-core registry fixture failed to parse")
    currency_result = format_message(
        currency_model,
        {"amount": 1234.5},
        locale="en-US",
        functions=number_core_function_registry(),
    )
    if currency_result.value != "Total: $1,234.50" or currency_result.errors:
        raise ConformanceFailure(
            "number-core registry currency: "
            f"expected 'Total: $1,234.50', got {currency_result.value!r}; "
            f"errors={currency_result.errors!r}"
        )
    checked += 1

    selector_model = parse_to_model(
        ".input {$count :number}\n.match $count\none {{one}}\n* {{other}}"
    ).model
    if selector_model is None:
        raise ConformanceFailure("number-core selector fixture failed to parse")
    selector_result = format_message(
        selector_model,
        {"count": 1},
        locale="en",
        functions=number_core_function_registry(),
    )
    if selector_result.value != "one" or selector_result.errors:
        raise ConformanceFailure(
            "number-core registry selector: "
            f"expected 'one', got {selector_result.value!r}; "
            f"errors={selector_result.errors!r}"
        )
    checked += 1

    for item in registry_cases:
        parse_result = parse_to_model(item["source"])
        if parse_result.model is None or parse_result.has_diagnostics:
            raise ConformanceFailure(
                f"{item['name']}: parse diagnostics {parse_result.diagnostics!r}"
            )
        result = format_message(
            parse_result.model,
            item.get("arguments", {}),
            locale=item.get("locale", "en"),
            functions=number_core_function_registry(),
        )
        if result.value != item["expected"] or result.errors:
            raise ConformanceFailure(
                f"{item['name']}: expected {item['expected']!r}, got {result.value!r}; "
                f"errors={result.errors!r}"
            )
        checked += 1
    return checked


def _check_number_core_registry_errors(registry_error_cases: list[dict[str, Any]]) -> int:
    checked = 0
    registry = number_core_function_registry()
    for item in registry_error_cases:
        parse_result = parse_to_model(item["source"])
        if parse_result.model is None or parse_result.has_diagnostics:
            raise ConformanceFailure(
                f"{item['name']}: parse diagnostics {parse_result.diagnostics!r}"
            )
        result = format_message(
            parse_result.model,
            item.get("arguments", {}),
            locale=item.get("locale", "en"),
            functions=registry,
        )
        actual = [error.code for error in result.errors]
        if actual != item["expectedErrors"]:
            raise ConformanceFailure(
                f"{item['name']}: expected errors {item['expectedErrors']!r}, "
                f"got {actual!r}; value={result.value!r}"
            )
        checked += 1
    return checked


def _check_date_time_core_registry() -> None:
    date_model = parse_to_model(
        "At {$instant :datetime dateStyle=full timeStyle=medium timeZone=UTC}"
    ).model
    if date_model is None:
        raise ConformanceFailure("date-time-core registry fixture failed to parse")
    date_result = format_message(
        date_model,
        {"instant": "2026-05-21T14:30:15Z"},
        locale="de-DE",
        functions=date_time_core_function_registry(),
    )
    if date_result.value != "At Donnerstag, 21. Mai 2026 um 14:30:15" or date_result.errors:
        raise ConformanceFailure(
            "date-time-core registry datetime: "
            f"expected 'At Donnerstag, 21. Mai 2026 um 14:30:15', got {date_result.value!r}; "
            f"errors={date_result.errors!r}"
        )

    string_model = parse_to_model("Hello {$name :string}").model
    if string_model is None:
        raise ConformanceFailure("date-time-core string fixture failed to parse")
    string_result = format_message(
        string_model,
        {"name": "Mojito"},
        functions=date_time_core_function_registry(),
    )
    if string_result.value != "Hello Mojito" or string_result.errors:
        raise ConformanceFailure(
            "date-time-core registry string: "
            f"expected 'Hello Mojito', got {string_result.value!r}; "
            f"errors={string_result.errors!r}"
        )


def _check_date_time_core_registry_cases(cases: list[dict[str, Any]]) -> int:
    checked = 0
    registry = date_time_core_function_registry()
    for item in cases:
        parsed = parse_to_model(item["source"])
        if parsed.model is None or parsed.diagnostics:
            raise ConformanceFailure(
                f"{item['name']}: date-time-core registry fixture failed to parse: "
                f"{parsed.diagnostics!r}"
            )
        result = format_message(
            parsed.model,
            item.get("arguments", {}),
            locale=item["locale"],
            functions=registry,
        )
        if result.value != item["expected"] or result.errors:
            raise ConformanceFailure(
                f"{item['name']}: expected {item['expected']!r}, got {result.value!r}; "
                f"errors={result.errors!r}"
            )
        checked += 1
    return checked


def _check_date_time_core_registry_error_cases(cases: list[dict[str, Any]]) -> int:
    checked = 0
    registry = date_time_core_function_registry()
    for item in cases:
        parsed = parse_to_model(item["source"])
        if parsed.model is None or parsed.diagnostics:
            raise ConformanceFailure(
                f"{item['name']}: date-time-core registry error fixture failed to parse: "
                f"{parsed.diagnostics!r}"
            )
        result = format_message(
            parsed.model,
            item.get("arguments", {}),
            locale=item["locale"],
            functions=registry,
        )
        actual_codes = [error.code for error in result.errors]
        if actual_codes != item["expectedErrors"]:
            raise ConformanceFailure(
                f"{item['name']}: expected registry errors {item['expectedErrors']!r}, "
                f"got {actual_codes!r}; value={result.value!r}"
            )
        checked += 1
    return checked


def _format_date_time_core_item(item: dict[str, Any]) -> str:
    options = {"locale": item["locale"], **item.get("options", {})}
    if item["kind"] == "date":
        return format_date_core(item["value"], **options)
    if item["kind"] == "time":
        return format_time_core(item["value"], **options)
    if item["kind"] == "datetime":
        return format_date_time_core(item["value"], **options)
    raise ConformanceFailure(f"Unsupported date/time core fixture kind: {item['kind']!r}")


def _date_time_reference_item(item: dict[str, Any]) -> dict[str, Any]:
    return {**item, "options": item["referenceOptions"]}


def _node_intl_number_outputs(cases: list[dict[str, Any]]) -> list[str]:
    script = r"""
const fs = require("fs");
const cases = JSON.parse(fs.readFileSync(0, "utf8"));
function intlOptions(options) {
  if (options.style === "number") return {};
  if (options.style === "percent") return { style: "percent" };
  if (options.style === "currency") return { style: "currency", currency: options.currency };
  throw new Error(`Unsupported Intl reference style: ${options.style}`);
}
process.stdout.write(JSON.stringify(cases.map((item) =>
  new Intl.NumberFormat(item.locale, intlOptions(item.options || {})).format(item.value)
)));
"""
    return _node_json_outputs(cases, script)


def _node_intl_date_time_outputs(cases: list[dict[str, Any]]) -> list[str]:
    script = r"""
const fs = require("fs");
const cases = JSON.parse(fs.readFileSync(0, "utf8"));
process.stdout.write(JSON.stringify(cases.map((item) =>
  new Intl.DateTimeFormat(item.locale, { timeZone: "UTC", ...(item.options || {}) }).format(new Date(item.value))
)));
"""
    return _node_json_outputs(cases, script)


def _node_json_outputs(cases: list[dict[str, Any]], script: str) -> list[str]:
    node = shutil.which("node")
    if node is None:
        raise ConformanceFailure("Node.js is required for Python core Intl reference comparisons.")
    completed = subprocess.run(
        [node, "-e", script],
        input=json.dumps(cases),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        raise ConformanceFailure(
            "Node.js Intl reference comparison failed: " + completed.stderr.strip()
        )
    outputs = json.loads(completed.stdout)
    if not isinstance(outputs, list) or not all(isinstance(item, str) for item in outputs):
        raise ConformanceFailure("Node.js Intl reference comparison returned invalid output.")
    return outputs


def _number_core_fixture(args: list[str]) -> Path:
    if args:
        return Path(args[0])
    return Path(__file__).resolve().parents[2] / "conformance" / "fixtures" / "number-core" / "cases.json"


def _date_time_core_fixture(args: list[str]) -> Path:
    if args:
        return Path(args[0])
    return Path(__file__).resolve().parents[2] / "conformance" / "fixtures" / "date-time-core" / "cases.json"


def _benchmark_iterations(args: list[str]) -> tuple[int, int]:
    return (
        int(args[1]) if len(args) > 1 else 100000,
        int(args[2]) if len(args) > 2 else 10000,
    )


def _check_format_error_fixtures(fixture_root: Path) -> int:
    fixture_dir = fixture_root / "format-errors"
    if not fixture_dir.exists():
        return 0

    checked_cases = 0
    for fixture_path in sorted(fixture_dir.glob("*.json")):
        fixture = _read_json(fixture_path)
        try:
            actual = format_message(
                fixture["model"],
                fixture.get("arguments", {}),
                fixture.get("locale", "en"),
            )
        except MF2Error as error:
            actual_codes = [error.code]
        else:
            actual_codes = [error.code for error in actual.errors]
        expected_code = fixture["expectedError"]["code"]
        if actual_codes[:1] != [expected_code]:
            raise ConformanceFailure(
                f"{fixture_path.name}: expected error {expected_code!r}, "
                f"got {actual_codes!r}"
            )
        checked_cases += 1

    return checked_cases


def _check_invalid_source_fixtures(fixture_root: Path) -> int:
    fixture_dir = fixture_root / "invalid-source"
    if not fixture_dir.exists():
        return 0

    checked_cases = 0
    for fixture_path in sorted(fixture_dir.glob("*.json")):
        fixture = _read_json(fixture_path)
        result = parse_to_model(fixture["source"])
        actual_codes = [diagnostic.code for diagnostic in result.diagnostics]
        expected_codes = [diagnostic["code"] for diagnostic in fixture.get("expectedDiagnostics", [])]
        if actual_codes != expected_codes:
            raise ConformanceFailure(
                f"{fixture_path.name}: expected diagnostics {expected_codes!r}, got {actual_codes!r}"
            )
        checked_cases += 1

    return checked_cases


def _check_locale_key_fixtures(fixture_root: Path) -> int:
    fixture_path = fixture_root / "locale-key" / "cases.json"
    if not fixture_path.exists():
        return 0

    checked_cases = 0
    fixture = _read_json(fixture_path)
    for item in fixture.get("canonical", []):
        actual = canonical_locale_key(item["source"])
        if actual != item["expected"]:
            raise ConformanceFailure(
                f"{fixture_path.name}: expected canonical {item['expected']!r}, got {actual!r}"
            )
        checked_cases += 1

    for item in fixture.get("lookupChains", []):
        actual = locale_lookup_chain(item["source"])
        if actual != item["expected"]:
            raise ConformanceFailure(
                f"{fixture_path.name}: expected lookup chain {item['expected']!r}, got {actual!r}"
            )
        checked_cases += 1

    for item in fixture.get("featureLookupChains", []):
        actual = feature_lookup_chain(item["source"], item.get("parents", {}))
        if actual != item["expected"]:
            raise ConformanceFailure(
                f"{fixture_path.name}: expected feature lookup chain {item['expected']!r}, got {actual!r}"
            )
        checked_cases += 1

    return checked_cases


def _assert_error_codes(
    fixture_path: Path,
    label: str,
    actual_errors: list[MF2Error],
    item: dict[str, Any],
) -> None:
    actual_codes = [error.code for error in actual_errors]
    expected_codes = [error["code"] for error in item.get("expectedErrors", [])]
    if actual_codes != expected_codes:
        raise ConformanceFailure(
            f"{fixture_path.name}: expected {label} {expected_codes!r}, got {actual_codes!r}"
        )


def _read_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as file:
        return json.load(file)


if __name__ == "__main__":
    raise SystemExit(main())
