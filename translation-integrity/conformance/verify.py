"""Validate the language-neutral translation-integrity conformance corpus."""

from __future__ import annotations

import hashlib
import json
import re
from collections import Counter
from datetime import date
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent
MANIFEST_PATH = ROOT / "manifest.json"
SCHEMA_PATH = ROOT / "corpus.schema.json"
ANDROID_GENERATED_RESOURCES_PATH = ROOT / "android-generated-resources.json"

CASE_ID = re.compile(r"^[a-z0-9]+(?:[.-][a-z0-9]+)*$")
SEMVER = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
PRIVATE_USE_LOCALE = re.compile(r"^x-[a-z0-9-]+$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
EMAIL = re.compile(r"[A-Za-z0-9._%+\-]+@([^\s,;]+)")
URL_HOST = re.compile(r"https?://([^/\s]+)")
LEGACY_EMAIL_LITERAL = re.compile(
    r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"
)
LEGACY_URL_LITERAL = re.compile(
    r"https?://[a-zA-Z0-9.]+\.[a-zA-Z]{2,}[a-zA-Z0-9/_\-?#+%]*"
)
BANNED_MANIFEST_TERMS = ("openai", "chatgpt")

CASE_FIELDS = {
    "id",
    "description",
    "tier",
    "profile",
    "features",
    "rules",
    "source",
    "target",
    "policy",
    "expected",
}
MESSAGE_FIELDS = {"locale", "text"}
RANGE_FIELDS = {"start", "end"}
EXPECTED_FIELDS = {
    "diagnostics",
    "policyDiagnostics",
    "reviewDisposition",
    "disposition",
    "safeRepair",
}
SAFE_REPAIR_FIELDS = {
    "operations",
    "expectedTarget",
    "expectedDiagnostics",
    "expectedPolicyDiagnostics",
}
POLICY_FIELDS = {
    "waivers",
    "externalFindings",
    "argumentTypes",
    "targetPluralCategories",
    "boundaryWhitespaceInvariant",
    "apostropheRepairStrategy",
    "maxNestingDepth",
}
WAIVER_FIELDS = {
    "rule",
    "messageId",
    "profile",
    "targetLocale",
    "sourceSha256",
    "targetSha256",
    "reason",
    "owner",
    "expiresOn",
}
BATCH_FIELDS = {
    "id",
    "caseIds",
    "expectedVisitedCaseIds",
    "expectedRepairedCaseIds",
    "expectedRejectedTargetCaseIds",
    "expectedRejectedSourceCaseIds",
    "expectedReviewCaseIds",
    "expectedStatus",
}

DIAGNOSTIC_RULES = {
    "argument-type-changed": "argument-contract",
    "boundary-whitespace-mismatch": "boundary-whitespace",
    "check-waived": "waiver-policy",
    "empty-target": "nonempty-target",
    "exact-selector-extra": "select-contract",
    "exact-selector-missing": "select-contract",
    "immutable-email-extra": "email-literal-contract",
    "immutable-email-missing": "email-literal-contract",
    "immutable-url-extra": "url-literal-contract",
    "immutable-url-missing": "url-literal-contract",
    "rich-tag-extra": "rich-text-tag-contract",
    "rich-tag-misnested": "rich-text-tag-contract",
    "rich-tag-missing": "rich-text-tag-contract",
    "rich-tag-unbalanced": "rich-text-tag-contract",
    "select-argument-changed": "select-contract",
    "select-occurrence-extra": "select-contract",
    "select-occurrence-missing": "select-contract",
    "select-option-extra": "select-contract",
    "select-option-missing": "select-contract",
    "semantic-review-required": "external-review",
    "source-format-invalid": "message-syntax",
    "target-format-invalid": "message-syntax",
    "unrenderable-tag-apostrophe": "formatjs-apostrophe-before-tag",
    "variable-extra": "argument-contract",
    "variable-missing": "argument-contract",
    "waiver-expired": "waiver-policy",
    "waiver-scope-mismatch": "waiver-policy",
}
DIAGNOSTIC_METADATA = {
    "argument-type-changed": (
        "error",
        "target",
        ({"argument", "expectedType", "actualType"},),
    ),
    "boundary-whitespace-mismatch": (
        "error",
        "target",
        (
            {
                "expectedLeading",
                "expectedTrailing",
                "actualLeading",
                "actualTrailing",
            },
        ),
    ),
    "check-waived": ("info", "policy", ({"rule", "waiverCount"},)),
    "empty-target": ("error", "target", ({"sourceNonempty"},)),
    "exact-selector-extra": ("error", "target", ({"argument", "selectors"},)),
    "exact-selector-missing": ("error", "target", ({"argument", "selectors"},)),
    "immutable-email-extra": (
        "error",
        "target",
        ({"values"}, {"value", "expectedCount", "actualCount"}),
    ),
    "immutable-email-missing": (
        "error",
        "target",
        ({"values"}, {"value", "expectedCount", "actualCount"}),
    ),
    "immutable-url-extra": (
        "error",
        "target",
        ({"values"}, {"value", "expectedCount", "actualCount"}),
    ),
    "immutable-url-missing": (
        "error",
        "target",
        ({"values"}, {"value", "expectedCount", "actualCount"}),
    ),
    "rich-tag-extra": (
        "error",
        "target",
        ({"tags"}, {"tag", "expectedCount", "actualCount"}),
    ),
    "rich-tag-misnested": (
        "error",
        "target",
        ({"expectedTree", "actualTree"},),
    ),
    "rich-tag-missing": (
        "error",
        "target",
        ({"tags"}, {"tag", "expectedCount", "actualCount"}),
    ),
    "rich-tag-unbalanced": (
        "error",
        "target",
        (
            {
                "tag",
                "expectedOpenCount",
                "actualOpenCount",
                "expectedCloseCount",
                "actualCloseCount",
            },
        ),
    ),
    "select-argument-changed": (
        "error",
        "target",
        ({"argument", "expectedType", "actualType"},),
    ),
    "select-occurrence-extra": (
        "error",
        "target",
        ({"argument", "selectors", "expectedCount", "actualCount"},),
    ),
    "select-occurrence-missing": (
        "error",
        "target",
        ({"argument", "selectors", "expectedCount", "actualCount"},),
    ),
    "select-option-extra": ("error", "target", ({"argument", "options"},)),
    "select-option-missing": ("error", "target", ({"argument", "options"},)),
    "semantic-review-required": ("warning", "target", ({"finding"},)),
    "source-format-invalid": ("error", "source", ({"reason"},)),
    "target-format-invalid": ("error", "target", ({"reason"},)),
    "unrenderable-tag-apostrophe": (
        "error",
        "target",
        ({"tag", "occurrence"},),
    ),
    "variable-extra": ("error", "target", ({"names"},)),
    "variable-missing": ("error", "target", ({"names"},)),
    "waiver-expired": ("warning", "policy", ({"rule", "expiresOn"},)),
    "waiver-scope-mismatch": (
        "warning",
        "policy",
        ({"rule", "mismatches"},),
    ),
}
SYNTAX_REASONS = {
    "duplicate-selector",
    "invalid-argument-name",
    "invalid-argument-style",
    "invalid-argument-type",
    "invalid-placeholder",
    "invalid-plural-offset",
    "invalid-selector",
    "maximum-nesting-depth",
    "missing-argument-delimiter",
    "missing-argument-or-selector",
    "missing-other-branch",
    "missing-type-delimiter",
    "unbraced-selector-branch",
    "unclosed-argument",
    "unclosed-placeholder",
    "unclosed-selector",
    "unclosed-selector-branch",
    "unclosed-style-quote",
    "unclosed-typed-argument",
}
POLICY_DIAGNOSTIC_CODES = {
    "check-waived",
    "semantic-review-required",
    "waiver-expired",
    "waiver-scope-mismatch",
}
REPAIRABLE_CODES = {
    "boundary-whitespace-mismatch",
    "unrenderable-tag-apostrophe",
}
WAIVABLE_RULES = {"boundary-whitespace"}


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as reader:
        value = json.load(reader)
    assert isinstance(value, dict), f"{path}: root must be an object"
    return value


def assert_exact_fields(
    value: dict[str, Any],
    *,
    required: set[str],
    optional: set[str] | frozenset[str] = frozenset(),
    label: str,
) -> None:
    missing = required - value.keys()
    unknown = value.keys() - required - optional
    assert not missing, f"{label}: missing fields {sorted(missing)}"
    assert not unknown, f"{label}: unknown fields {sorted(unknown)}"


def parse_date(value: Any, label: str) -> date:
    assert isinstance(value, str), f"{label}: date must be a string"
    try:
        return date.fromisoformat(value)
    except ValueError as error:
        raise AssertionError(f"{label}: invalid ISO date {value!r}") from error


def validate_message(value: Any, label: str) -> dict[str, str]:
    assert isinstance(value, dict), f"{label}: message must be an object"
    assert_exact_fields(value, required=MESSAGE_FIELDS, label=label)
    locale = value["locale"]
    text = value["text"]
    assert isinstance(locale, str) and PRIVATE_USE_LOCALE.fullmatch(locale), (
        f"{label}: locale must be a private-use fixture tag"
    )
    assert isinstance(text, str), f"{label}: text must be a string"

    for match in EMAIL.finditer(text):
        domain = match.group(1).rstrip(".?!:)").casefold()
        assert domain.endswith(".invalid"), (
            f"{label}: email domains must use the reserved .invalid suffix"
        )
    for match in URL_HOST.finditer(text):
        host = match.group(1).split(":", 1)[0].rstrip(".?!:)").casefold()
        assert host.endswith(".invalid"), (
            f"{label}: URL hosts must use the reserved .invalid suffix"
        )
    return {"locale": locale, "text": text}


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def diagnostic_sort_key(value: dict[str, Any]) -> tuple[str, str, str, str, str]:
    return (
        value["code"],
        value["subject"],
        value["severity"],
        canonical_json(value["details"]),
        canonical_json(value.get("range")),
    )


def validate_diagnostics(
    values: Any,
    *,
    label: str,
    diagnostic_codes: set[str],
    source: dict[str, str],
    target: dict[str, str],
    policy_lane: bool,
) -> list[dict[str, Any]]:
    assert isinstance(values, list), f"{label}: diagnostics must be an array"
    diagnostics: list[dict[str, Any]] = []
    for index, value in enumerate(values):
        item_label = f"{label}[{index}]"
        assert isinstance(value, dict), f"{item_label}: diagnostic must be an object"
        assert_exact_fields(
            value,
            required={"code", "severity", "subject", "details"},
            optional={"range"},
            label=item_label,
        )
        code = value["code"]
        assert code in diagnostic_codes, (
            f"{item_label}: unknown diagnostic code {code!r}"
        )
        assert DIAGNOSTIC_RULES[code], f"{item_label}: diagnostic has no owning rule"
        if policy_lane:
            assert code in POLICY_DIAGNOSTIC_CODES, (
                f"{item_label}: detector diagnostic used in policy lane"
            )
            assert "range" not in value, (
                f"{item_label}: schema-v1 policy diagnostics cannot carry ranges"
            )
        else:
            assert code not in POLICY_DIAGNOSTIC_CODES, (
                f"{item_label}: policy diagnostic used in detector lane"
            )
        assert value["severity"] in {"error", "warning", "info"}, (
            f"{item_label}: invalid severity"
        )
        assert value["subject"] in {"source", "target", "policy"}, (
            f"{item_label}: invalid subject"
        )
        details = value["details"]
        assert isinstance(details, dict) and details, (
            f"{item_label}: structured details must be a nonempty object"
        )
        expected_severity, expected_subject, detail_shapes = DIAGNOSTIC_METADATA[code]
        assert value["severity"] == expected_severity, (
            f"{item_label}: {code} severity must be {expected_severity}"
        )
        assert value["subject"] == expected_subject, (
            f"{item_label}: {code} subject must be {expected_subject}"
        )
        assert set(details) in detail_shapes, (
            f"{item_label}: invalid detail fields for {code}: {sorted(details)}"
        )
        canonical_json(details)

        for field in ("names", "values", "tags", "selectors", "options", "mismatches"):
            if field not in details:
                continue
            members = details[field]
            assert isinstance(members, list) and members, (
                f"{item_label}: {field} must be a nonempty array"
            )
            assert all(isinstance(member, str) and member for member in members), (
                f"{item_label}: {field} must contain nonempty strings"
            )
            assert members == sorted(set(members)), (
                f"{item_label}: {field} must be sorted and unique"
            )

        if code in {"argument-type-changed", "select-argument-changed"}:
            assert details["expectedType"] != details["actualType"], (
                f"{item_label}: changed types must differ"
            )
        if code == "boundary-whitespace-mismatch":
            assert (
                details["expectedLeading"] != details["actualLeading"]
                or details["expectedTrailing"] != details["actualTrailing"]
            ), f"{item_label}: boundary whitespace details do not describe a mismatch"
        if code == "empty-target":
            assert details["sourceNonempty"] is True
        if {"expectedCount", "actualCount"} <= details.keys():
            expected_count = details["expectedCount"]
            actual_count = details["actualCount"]
            assert isinstance(expected_count, int) and not isinstance(
                expected_count, bool
            )
            assert isinstance(actual_count, int) and not isinstance(actual_count, bool)
            assert expected_count >= 0 and actual_count >= 0
            if code.endswith("-extra"):
                assert actual_count > expected_count, (
                    f"{item_label}: extra count must increase"
                )
            elif code.endswith("-missing"):
                assert actual_count < expected_count, (
                    f"{item_label}: missing count must decrease"
                )
        if code in {"source-format-invalid", "target-format-invalid"}:
            assert details["reason"] in SYNTAX_REASONS, (
                f"{item_label}: unknown normalized syntax reason"
            )
        if code == "unrenderable-tag-apostrophe":
            assert isinstance(details["occurrence"], int) and details["occurrence"] >= 1
        if code == "check-waived":
            assert details["rule"] in WAIVABLE_RULES
            assert (
                isinstance(details["waiverCount"], int) and details["waiverCount"] >= 1
            )
        if code == "waiver-expired":
            assert details["rule"] in WAIVABLE_RULES
            parse_date(details["expiresOn"], f"{item_label}.details.expiresOn")
        if code == "waiver-scope-mismatch":
            assert details["rule"] in WAIVABLE_RULES

        if "range" in value:
            subject = value["subject"]
            assert subject in {"source", "target"}, (
                f"{item_label}: only source or target diagnostics may have a range"
            )
            message = source if subject == "source" else target
            range_value = value["range"]
            assert isinstance(range_value, dict), (
                f"{item_label}: range must be an object"
            )
            assert_exact_fields(
                range_value, required=RANGE_FIELDS, label=f"{item_label}.range"
            )
            start = range_value["start"]
            end = range_value["end"]
            assert isinstance(start, int) and not isinstance(start, bool), (
                f"{item_label}: range start must be an integer"
            )
            assert isinstance(end, int) and not isinstance(end, bool), (
                f"{item_label}: range end must be an integer"
            )
            assert 0 <= start <= end <= len(message["text"]), (
                f"{item_label}: Unicode-code-point range is outside its {subject} text"
            )
        diagnostics.append(value)

    assert diagnostics == sorted(diagnostics, key=diagnostic_sort_key), (
        f"{label}: diagnostics must be in canonical order"
    )
    keys = [diagnostic_sort_key(item) for item in diagnostics]
    assert len(keys) == len(set(keys)), f"{label}: duplicate diagnostics"
    return diagnostics


def text_sha256(message: dict[str, str]) -> str:
    return hashlib.sha256(message["text"].encode("utf-8")).hexdigest()


def is_python_strip_whitespace(character: str) -> bool:
    code_point = ord(character)
    return (
        0x0009 <= code_point <= 0x000D
        or 0x001C <= code_point <= 0x001F
        or code_point == 0x0020
        or code_point == 0x0085
        or code_point == 0x00A0
        or code_point == 0x1680
        or 0x2000 <= code_point <= 0x200A
        or 0x2028 <= code_point <= 0x2029
        or code_point == 0x202F
        or code_point == 0x205F
        or code_point == 0x3000
    )


def whitespace_boundaries(value: str) -> tuple[str, str, str]:
    leading_end = 0
    while leading_end < len(value) and is_python_strip_whitespace(
        value[leading_end]
    ):
        leading_end += 1

    trailing_start = len(value)
    while trailing_start > 0 and is_python_strip_whitespace(
        value[trailing_start - 1]
    ):
        trailing_start -= 1

    core = value[leading_end:trailing_start] if leading_end <= trailing_start else ""
    return value[:leading_end], core, value[trailing_start:]


def boundary_whitespace_diagnostics(
    source: str, target: str
) -> list[dict[str, Any]]:
    source_leading, _, source_trailing = whitespace_boundaries(source)
    target_leading, _, target_trailing = whitespace_boundaries(target)
    if source_leading == target_leading and source_trailing == target_trailing:
        return []
    return [
        {
            "code": "boundary-whitespace-mismatch",
            "severity": "error",
            "subject": "target",
            "details": {
                "expectedLeading": source_leading,
                "expectedTrailing": source_trailing,
                "actualLeading": target_leading,
                "actualTrailing": target_trailing,
            },
        }
    ]


def extract_legacy_literals(
    value: str, marker: str, pattern: re.Pattern[str]
) -> list[str]:
    return sorted(pattern.findall(value)) if marker in value else []


def legacy_literal_diagnostics(
    source: str,
    target: str,
    *,
    marker: str,
    pattern: re.Pattern[str],
    kind: str,
) -> list[dict[str, Any]]:
    source_counts = Counter(extract_legacy_literals(source, marker, pattern))
    target_counts = Counter(extract_legacy_literals(target, marker, pattern))
    missing_values: list[str] = []
    extra_values: list[str] = []
    diagnostics: list[dict[str, Any]] = []
    for value in sorted(source_counts.keys() | target_counts.keys()):
        expected_count = source_counts[value]
        actual_count = target_counts[value]
        if expected_count == actual_count:
            continue
        direction = "missing" if expected_count > actual_count else "extra"
        if expected_count > 1 or actual_count > 1:
            diagnostics.append(
                {
                    "code": f"immutable-{kind}-{direction}",
                    "severity": "error",
                    "subject": "target",
                    "details": {
                        "value": value,
                        "expectedCount": expected_count,
                        "actualCount": actual_count,
                    },
                }
            )
        elif actual_count == 0:
            missing_values.append(value)
        else:
            extra_values.append(value)
    if missing_values:
        diagnostics.append(
            {
                "code": f"immutable-{kind}-missing",
                "severity": "error",
                "subject": "target",
                "details": {"values": missing_values},
            }
        )
    if extra_values:
        diagnostics.append(
            {
                "code": f"immutable-{kind}-extra",
                "severity": "error",
                "subject": "target",
                "details": {"values": extra_values},
            }
        )
    return sorted(diagnostics, key=diagnostic_sort_key)


def scan_formatjs_tag_token(value: str, index: int) -> tuple[int, str, bool] | None:
    """Return the end, token, and opening status of one complete tag token."""

    if index >= len(value) or value[index] != "<":
        return None
    position = index + 1
    is_opening = True
    if position < len(value) and value[position] == "/":
        is_opening = False
        position += 1
    if (
        position >= len(value)
        or not value[position].isascii()
        or not value[position].isalpha()
    ):
        return None
    position += 1
    while position < len(value):
        character = value[position]
        if character.isascii() and (character.isalnum() or character in "-.:"):
            position += 1
            continue
        break

    attribute_quote: str | None = None
    while position < len(value):
        character = value[position]
        if attribute_quote is not None:
            if character == attribute_quote:
                attribute_quote = None
        elif character in {"'", '"'}:
            attribute_quote = character
        elif character == ">":
            end = position + 1
            return end, value[index:end], is_opening
        position += 1
    return None


def find_formatjs_quote_close(value: str, opening_index: int) -> int | None:
    position = opening_index + 1
    while position < len(value):
        if value[position] != "'":
            position += 1
            continue
        if position + 1 < len(value) and value[position + 1] == "'":
            position += 2
            continue
        return position
    return None


class FormatjsTagApostropheAnalyzer:
    """Walk valid ICU syntax and find quotes that hide rich-text tag tokens."""

    _SELECTOR_TYPES = frozenset({"select", "plural", "selectordinal"})
    _PLURAL_TYPES = frozenset({"plural", "selectordinal"})

    def __init__(self, value: str) -> None:
        self.value = value
        self.findings: list[dict[str, Any]] = []

    def analyze(self) -> list[dict[str, Any]]:
        self._scan_message(0, stop_at_closing_brace=False, in_plural=False)
        return self.findings

    def _skip_whitespace(self, position: int) -> int:
        while position < len(self.value) and (
            self.value[position].isspace() or self.value[position] in "\u200e\u200f"
        ):
            position += 1
        return position

    def _read_token(self, position: int) -> tuple[str, int]:
        position = self._skip_whitespace(position)
        start = position
        while position < len(self.value):
            character = self.value[position]
            if character.isspace() or character in "\u200e\u200f" or character in "{},":
                break
            position += 1
        return self.value[start:position], position

    def _scan_apostrophe(self, position: int, *, in_plural: bool) -> int:
        if position + 1 >= len(self.value):
            return position + 1
        next_character = self.value[position + 1]
        if next_character == "'":
            return position + 2
        if next_character not in "{}<>" and not (next_character == "#" and in_plural):
            return position + 1

        closing_index = find_formatjs_quote_close(self.value, position)
        tag_token = scan_formatjs_tag_token(self.value, position + 1)
        if tag_token is not None:
            _, tag, _ = tag_token
            self.findings.append(
                {
                    "index": position,
                    "tag": tag,
                    "closingIndex": closing_index,
                }
            )
        return len(self.value) if closing_index is None else closing_index + 1

    def _scan_message(
        self,
        position: int,
        *,
        stop_at_closing_brace: bool,
        in_plural: bool,
    ) -> int:
        while position < len(self.value):
            character = self.value[position]
            if character == "'":
                position = self._scan_apostrophe(position, in_plural=in_plural)
            elif character == "<":
                tag_token = scan_formatjs_tag_token(self.value, position)
                position = tag_token[0] if tag_token is not None else position + 1
            elif character == "{":
                position = self._scan_argument(position, in_plural=in_plural)
            elif character == "}" and stop_at_closing_brace:
                return position + 1
            else:
                position += 1
        return position

    def _scan_argument(self, position: int, *, in_plural: bool) -> int:
        _, position = self._read_token(position + 1)
        position = self._skip_whitespace(position)
        if position >= len(self.value):
            return position
        if self.value[position] == "}":
            return position + 1
        if self.value[position] != ",":
            return position + 1

        argument_type, position = self._read_token(position + 1)
        position = self._skip_whitespace(position)
        if position >= len(self.value):
            return position
        if self.value[position] == "}":
            return position + 1
        if self.value[position] != ",":
            return position + 1
        position += 1

        if argument_type not in self._SELECTOR_TYPES:
            return self._skip_argument_style(position)

        branch_in_plural = argument_type in self._PLURAL_TYPES
        while position < len(self.value):
            position = self._skip_whitespace(position)
            if position >= len(self.value):
                return position
            if self.value[position] == "}":
                return position + 1
            _, position = self._read_token(position)
            position = self._skip_whitespace(position)
            if position < len(self.value) and self.value[position] == "{":
                position = self._scan_message(
                    position + 1,
                    stop_at_closing_brace=True,
                    in_plural=branch_in_plural,
                )
        return position

    def _skip_argument_style(self, position: int) -> int:
        nested_braces = 0
        while position < len(self.value):
            character = self.value[position]
            if character == "'":
                closing_index = find_formatjs_quote_close(self.value, position)
                if closing_index is None:
                    return len(self.value)
                position = closing_index + 1
            elif character == "{":
                nested_braces += 1
                position += 1
            elif character == "}":
                if nested_braces == 0:
                    return position + 1
                nested_braces -= 1
                position += 1
            else:
                position += 1
        return position


def analyze_formatjs_tag_apostrophes(value: str) -> list[dict[str, Any]]:
    return FormatjsTagApostropheAnalyzer(value).analyze()


def replace_at_indices(value: str, indices: list[int], replacement: str) -> str:
    parts: list[str] = []
    previous = 0
    for index in indices:
        parts.append(value[previous:index])
        parts.append(replacement)
        previous = index + 1
    parts.append(value[previous:])
    return "".join(parts)


def apply_repair_operations(
    operations: list[str],
    *,
    source: str,
    target: str,
) -> str:
    result = target
    for operation in operations:
        if operation == "COPY_SOURCE_BOUNDARY_WHITESPACE":
            source_leading, source_core, source_trailing = whitespace_boundaries(source)
            _, target_core, _ = whitespace_boundaries(result)
            assert source_core and target_core, (
                "boundary-whitespace auto-repair requires nonempty stripped cores"
            )
            result = (
                source_leading
                + target_core
                + source_trailing
            )
        elif operation == "DOUBLE_ASCII_APOSTROPHE_BEFORE_FORMATJS_TAG":
            findings = analyze_formatjs_tag_apostrophes(result)
            if findings:
                assert all(item["closingIndex"] is None for item in findings), (
                    "apostrophe auto-repair requires unambiguous quote-openers"
                )
                result = replace_at_indices(
                    result, [item["index"] for item in findings], "''"
                )
        elif operation == "REPLACE_ASCII_APOSTROPHE_BEFORE_FORMATJS_TAG_WITH_U2019":
            findings = analyze_formatjs_tag_apostrophes(result)
            if findings:
                assert all(item["closingIndex"] is None for item in findings), (
                    "apostrophe auto-repair requires unambiguous quote-openers"
                )
                result = replace_at_indices(
                    result, [item["index"] for item in findings], "’"
                )
        else:
            raise AssertionError(f"unknown safe-repair operation {operation!r}")
    return result


def validate_policy(
    value: Any,
    *,
    label: str,
    case_id: str,
    profile: str,
    rules: list[str],
    source: dict[str, str],
    target: dict[str, str],
    evaluation_date: date,
    diagnostic_codes: set[str],
    expected_policy_diagnostics: list[dict[str, Any]],
) -> set[str]:
    assert isinstance(value, dict) and value, f"{label}: policy must be nonempty"
    assert_exact_fields(value, required=set(), optional=POLICY_FIELDS, label=label)

    if "argumentTypes" in value:
        argument_types = value["argumentTypes"]
        assert isinstance(argument_types, dict) and argument_types, (
            f"{label}.argumentTypes: must be nonempty"
        )
        assert all(
            isinstance(name, str)
            and name
            and argument_type in {"string", "number", "date", "time"}
            for name, argument_type in argument_types.items()
        ), f"{label}.argumentTypes: invalid argument type declaration"

    if "targetPluralCategories" in value:
        categories = value["targetPluralCategories"]
        assert isinstance(categories, list) and categories, (
            f"{label}.targetPluralCategories: must be nonempty"
        )
        assert categories == sorted(set(categories)), (
            f"{label}.targetPluralCategories: must be sorted and unique"
        )
        assert set(categories) <= {"zero", "one", "two", "few", "many", "other"}

    if "boundaryWhitespaceInvariant" in value:
        assert value["boundaryWhitespaceInvariant"] is True

    if "apostropheRepairStrategy" in value:
        assert value["apostropheRepairStrategy"] in {
            "compatibility-u2019",
            "icu-double",
        }

    if "maxNestingDepth" in value:
        assert isinstance(value["maxNestingDepth"], int) and not isinstance(
            value["maxNestingDepth"], bool
        )
        assert value["maxNestingDepth"] >= 1

    if "externalFindings" in value:
        findings = validate_diagnostics(
            value["externalFindings"],
            label=f"{label}.externalFindings",
            diagnostic_codes=diagnostic_codes,
            source=source,
            target=target,
            policy_lane=True,
        )
        assert all(item["code"] == "semantic-review-required" for item in findings), (
            f"{label}: external findings must use semantic-review-required"
        )
    else:
        findings = []
    expected_external_findings = [
        item
        for item in expected_policy_diagnostics
        if item["code"] == "semantic-review-required"
    ]
    assert findings == expected_external_findings, (
        f"{label}: external findings and expected policy diagnostics must match exactly"
    )

    waived_rules: set[str] = set()
    waivers = value.get("waivers", [])
    assert isinstance(waivers, list), f"{label}.waivers: must be an array"
    if "waivers" in value:
        assert waivers, f"{label}.waivers: must not be empty"

    expected_waiver_codes: list[str] = []
    for index, waiver in enumerate(waivers):
        waiver_label = f"{label}.waivers[{index}]"
        assert isinstance(waiver, dict), f"{waiver_label}: must be an object"
        assert_exact_fields(waiver, required=WAIVER_FIELDS, label=waiver_label)
        assert waiver["rule"] in WAIVABLE_RULES, (
            f"{waiver_label}: rule is not waivable in schema version 1"
        )
        for field in ("sourceSha256", "targetSha256"):
            assert isinstance(waiver[field], str) and SHA256.fullmatch(waiver[field]), (
                f"{waiver_label}: {field} must be lowercase SHA-256"
            )
        for field in ("reason", "owner"):
            assert isinstance(waiver[field], str) and waiver[field], (
                f"{waiver_label}: {field} must be nonempty"
            )

        mismatches: list[str] = []
        if waiver["messageId"] != case_id:
            mismatches.append("messageId")
        if waiver["profile"] != profile:
            mismatches.append("profile")
        if waiver["targetLocale"] != target["locale"]:
            mismatches.append("targetLocale")
        if waiver["rule"] not in rules:
            mismatches.append("rule")
        if waiver["sourceSha256"] != text_sha256(source):
            mismatches.append("sourceSha256")
        if waiver["targetSha256"] != text_sha256(target):
            mismatches.append("targetSha256")

        expires_on = parse_date(waiver["expiresOn"], f"{waiver_label}.expiresOn")
        if mismatches:
            expected_waiver_codes.append("waiver-scope-mismatch")
            assert any(
                item["code"] == "waiver-scope-mismatch"
                and item["details"].get("rule") == waiver["rule"]
                and item["details"].get("mismatches") == sorted(mismatches)
                for item in expected_policy_diagnostics
            ), f"{waiver_label}: scope mismatch is not described exactly"
        elif expires_on <= evaluation_date:
            expected_waiver_codes.append("waiver-expired")
            assert any(
                item["code"] == "waiver-expired"
                and item["details"].get("rule") == waiver["rule"]
                and item["details"].get("expiresOn") == waiver["expiresOn"]
                for item in expected_policy_diagnostics
            ), f"{waiver_label}: expiry is not described exactly"
        else:
            expected_waiver_codes.append("check-waived")
            waived_rules.add(waiver["rule"])

    actual_waiver_codes = [
        item["code"]
        for item in expected_policy_diagnostics
        if item["code"] in {"check-waived", "waiver-expired", "waiver-scope-mismatch"}
    ]
    assert sorted(actual_waiver_codes) == sorted(expected_waiver_codes), (
        f"{label}: waiver diagnostics do not match waiver evaluation"
    )
    if expected_waiver_codes.count("check-waived"):
        assert any(
            item["code"] == "check-waived"
            and item["details"].get("waiverCount")
            == expected_waiver_codes.count("check-waived")
            for item in expected_policy_diagnostics
        ), f"{label}: matching waiver count is not described exactly"
    return waived_rules


def main() -> None:
    schema = load_json(SCHEMA_PATH)
    manifest = load_json(MANIFEST_PATH)
    android_corpus = load_json(ANDROID_GENERATED_RESOURCES_PATH)

    assert_exact_fields(
        android_corpus, required={"schemaVersion", "rules", "cases"}, label="android"
    )
    assert android_corpus["schemaVersion"] == 1
    android_rules = {
        "android-resource-syntax",
        "markdown-link-contract",
        "printf-placeholder-contract",
    }
    assert set(android_corpus["rules"]) == android_rules
    android_cases = android_corpus["cases"]
    assert isinstance(android_cases, list) and android_cases
    android_case_ids: list[str] = []
    for index, case in enumerate(android_cases):
        label = f"android.cases[{index}]"
        assert_exact_fields(
            case,
            required={"id", "locale", "source", "target", "expectedDiagnostics"},
            label=label,
        )
        android_case_ids.append(case["id"])
        assert isinstance(case["id"], str) and case["id"]
        assert PRIVATE_USE_LOCALE.fullmatch(case["locale"])
        assert isinstance(case["source"], str) and "<resources>" in case["source"]
        assert isinstance(case["target"], str) and "<resources>" in case["target"]
        for diagnostic in case["expectedDiagnostics"]:
            assert_exact_fields(
                diagnostic, required={"resource", "rule"}, label=label
            )
            assert isinstance(diagnostic["resource"], str) and diagnostic["resource"]
            assert diagnostic["rule"] in android_rules
    assert len(android_case_ids) == len(set(android_case_ids))
    serialized_android_corpus = json.dumps(
        android_corpus, ensure_ascii=False
    ).casefold()
    for term in BANNED_MANIFEST_TERMS:
        assert term not in serialized_android_corpus, (
            f"android: proprietary term {term!r} is not allowed in the neutral corpus"
        )

    assert schema.get("$schema") == "https://json-schema.org/draft/2020-12/schema"
    rule_names = set(schema["$defs"]["rule"]["enum"])
    waivable_rule_names = set(schema["$defs"]["waivableRule"]["enum"])
    feature_names = set(schema["$defs"]["feature"]["enum"])
    diagnostic_codes = set(schema["$defs"]["diagnosticCode"]["enum"])
    profiles = set(schema["$defs"]["case"]["properties"]["profile"]["enum"])
    schema_dispositions = set(
        schema["$defs"]["expected"]["properties"]["disposition"]["enum"]
    )
    assert diagnostic_codes == DIAGNOSTIC_RULES.keys(), (
        "schema diagnostic codes and verifier rule ownership differ"
    )
    assert diagnostic_codes == DIAGNOSTIC_METADATA.keys(), (
        "schema diagnostic codes and verifier metadata differ"
    )
    assert set(schema["$defs"]["syntaxReason"]["enum"]) == SYNTAX_REASONS, (
        "schema and verifier normalized syntax reasons differ"
    )
    assert waivable_rule_names == WAIVABLE_RULES, (
        "schema and verifier waivable rules differ"
    )
    dispositions = {
        "PASS",
        "AUTO_REPAIR_TARGET",
        "REJECT_TARGET",
        "REJECT_SOURCE",
        "EXEMPT",
    }
    assert schema_dispositions == dispositions, (
        "schema and verifier structural dispositions differ"
    )
    assert (
        schema["$defs"]["expected"]["properties"]["reviewDisposition"]["const"]
        == "REVIEW_REQUIRED"
    ), "schema and verifier review dispositions differ"

    assert_exact_fields(
        manifest,
        required={
            "schemaVersion",
            "corpusVersion",
            "name",
            "offsetEncoding",
            "policyEvaluationDate",
            "diagnosticRules",
            "cases",
            "batchScenarios",
        },
        label="manifest",
    )
    assert manifest["schemaVersion"] == 1, "manifest: unsupported schema version"
    assert isinstance(manifest["corpusVersion"], str) and SEMVER.fullmatch(
        manifest["corpusVersion"]
    ), "manifest: corpusVersion must be semantic major.minor.patch"
    assert manifest["name"] == "translation-integrity", "manifest: unexpected name"
    assert manifest["offsetEncoding"] == "unicode-code-point", (
        "manifest: unsupported offset encoding"
    )
    evaluation_date = parse_date(
        manifest["policyEvaluationDate"], "policyEvaluationDate"
    )
    assert manifest["diagnosticRules"] == DIAGNOSTIC_RULES, (
        "manifest diagnostic ownership differs from the normative verifier mapping"
    )
    cases = manifest["cases"]
    assert isinstance(cases, list) and cases, "manifest: cases must be a nonempty array"

    serialized_manifest = json.dumps(manifest, ensure_ascii=False).casefold()
    for term in BANNED_MANIFEST_TERMS:
        assert term not in serialized_manifest, (
            f"manifest: proprietary term {term!r} is not allowed in the neutral corpus"
        )

    case_ids: list[str] = []
    cases_by_id: dict[str, dict[str, Any]] = {}
    tier_counts: Counter[str] = Counter()
    profile_counts: Counter[str] = Counter()
    disposition_counts: Counter[str] = Counter()

    for index, case in enumerate(cases):
        label = f"cases[{index}]"
        assert isinstance(case, dict), f"{label}: case must be an object"
        assert_exact_fields(
            case,
            required=CASE_FIELDS - {"features", "policy"},
            optional={"features", "policy"},
            label=label,
        )
        case_id = case["id"]
        assert isinstance(case_id, str) and CASE_ID.fullmatch(case_id), (
            f"{label}: invalid case id"
        )
        case_ids.append(case_id)
        cases_by_id[case_id] = case
        label = case_id

        assert isinstance(case["description"], str) and case["description"], (
            f"{label}: description must be nonempty"
        )
        assert case["tier"] in {"cutover", "extended"}, f"{label}: invalid tier"
        assert case["profile"] in profiles, f"{label}: invalid profile"
        rules = case["rules"]
        assert isinstance(rules, list) and rules, f"{label}: rules must be nonempty"
        assert all(rule in rule_names for rule in rules), f"{label}: unknown rule"
        assert rules == sorted(set(rules)), f"{label}: rules must be sorted and unique"

        features = case.get("features", [])
        assert isinstance(features, list), f"{label}: features must be an array"
        assert all(feature in feature_names for feature in features), (
            f"{label}: unknown feature"
        )
        assert features == sorted(set(features)), (
            f"{label}: features must be sorted and unique"
        )
        if "rich-text-tag-contract" in rules:
            assert "rich-text-tags" in features, (
                f"{label}: rich-tag rule requires the rich-text-tags feature"
            )
        if "formatjs-apostrophe-before-tag" in rules:
            assert "formatjs-apostrophe-escaping" in features, (
                f"{label}: apostrophe rule requires its renderer capability"
            )
        if "formatjs-apostrophe-escaping" in features:
            assert "rich-text-tags" in features, (
                f"{label}: apostrophe capability requires rich-text tags"
            )

        source = validate_message(case["source"], f"{label}.source")
        target = validate_message(case["target"], f"{label}.target")

        expected = case["expected"]
        assert isinstance(expected, dict), f"{label}.expected: must be an object"
        assert_exact_fields(
            expected,
            required=EXPECTED_FIELDS
            - {"policyDiagnostics", "reviewDisposition", "safeRepair"},
            optional={"policyDiagnostics", "reviewDisposition", "safeRepair"},
            label=f"{label}.expected",
        )
        diagnostics = validate_diagnostics(
            expected["diagnostics"],
            label=f"{label}.expected.diagnostics",
            diagnostic_codes=diagnostic_codes,
            source=source,
            target=target,
            policy_lane=False,
        )
        policy_diagnostics = validate_diagnostics(
            expected.get("policyDiagnostics", []),
            label=f"{label}.expected.policyDiagnostics",
            diagnostic_codes=diagnostic_codes,
            source=source,
            target=target,
            policy_lane=True,
        )
        for diagnostic in diagnostics + policy_diagnostics:
            owning_rule = DIAGNOSTIC_RULES[diagnostic["code"]]
            assert owning_rule in rules, (
                f"{label}: diagnostic {diagnostic['code']!r} requires rule "
                f"{owning_rule!r}"
            )
        if any(
            item["details"].get("reason") == "maximum-nesting-depth"
            for item in diagnostics
        ):
            assert case.get("policy", {}).get("maxNestingDepth") == 100, (
                f"{label}: nesting-limit cases must declare the compatibility limit"
            )
        has_syntax_error = any(
            item["code"] in {"source-format-invalid", "target-format-invalid"}
            for item in diagnostics
        )
        if "boundary-whitespace" in rules and not has_syntax_error:
            expected_boundary_diagnostics = [
                item
                for item in diagnostics
                if item["code"] == "boundary-whitespace-mismatch"
            ]
            assert expected_boundary_diagnostics == boundary_whitespace_diagnostics(
                source["text"], target["text"]
            ), f"{label}: boundary diagnostics do not match the Python strip predicate"
        if case["tier"] == "cutover" and not has_syntax_error:
            if "email-literal-contract" in rules:
                expected_email_diagnostics = [
                    item
                    for item in diagnostics
                    if item["code"].startswith("immutable-email-")
                ]
                assert expected_email_diagnostics == legacy_literal_diagnostics(
                    source["text"],
                    target["text"],
                    marker="@",
                    pattern=LEGACY_EMAIL_LITERAL,
                    kind="email",
                ), (
                    f"{label}: email diagnostics do not match the legacy regex multiset"
                )
            if "url-literal-contract" in rules:
                expected_url_diagnostics = [
                    item
                    for item in diagnostics
                    if item["code"].startswith("immutable-url-")
                ]
                assert expected_url_diagnostics == legacy_literal_diagnostics(
                    source["text"],
                    target["text"],
                    marker="http",
                    pattern=LEGACY_URL_LITERAL,
                    kind="url",
                ), (
                    f"{label}: URL diagnostics do not match the legacy regex multiset"
                )
        if "formatjs-apostrophe-before-tag" in rules and not has_syntax_error:
            occurrences: Counter[str] = Counter()
            normalized_apostrophe_diagnostics: list[dict[str, Any]] = []
            for finding in analyze_formatjs_tag_apostrophes(target["text"]):
                occurrences[finding["tag"]] += 1
                normalized_apostrophe_diagnostics.append(
                    {
                        "code": "unrenderable-tag-apostrophe",
                        "severity": "error",
                        "subject": "target",
                        "details": {
                            "tag": finding["tag"],
                            "occurrence": occurrences[finding["tag"]],
                        },
                    }
                )
            expected_apostrophe_diagnostics = [
                item
                for item in diagnostics
                if item["code"] == "unrenderable-tag-apostrophe"
            ]
            assert expected_apostrophe_diagnostics == sorted(
                normalized_apostrophe_diagnostics, key=diagnostic_sort_key
            ), f"{label}: apostrophe diagnostics do not match ICU quote state"

        waived_rules: set[str] = set()
        if "policy" in case:
            waived_rules = validate_policy(
                case["policy"],
                label=f"{label}.policy",
                case_id=case_id,
                profile=case["profile"],
                rules=rules,
                source=source,
                target=target,
                evaluation_date=evaluation_date,
                diagnostic_codes=diagnostic_codes,
                expected_policy_diagnostics=policy_diagnostics,
            )
        else:
            assert not policy_diagnostics, (
                f"{label}: policy diagnostics require policy input"
            )

        errors = [item for item in diagnostics if item["severity"] == "error"]
        unwaived_errors = [
            item
            for item in errors
            if DIAGNOSTIC_RULES[item["code"]] not in waived_rules
        ]
        disposition = expected["disposition"]
        assert disposition in dispositions, f"{label}: invalid disposition"

        if disposition == "PASS":
            assert not errors, f"{label}: PASS cannot contain detector errors"
        elif disposition == "AUTO_REPAIR_TARGET":
            assert unwaived_errors and all(
                item["subject"] == "target" and item["code"] in REPAIRABLE_CODES
                for item in unwaived_errors
            ), f"{label}: automatic repair contains a nonrepairable finding"
            assert "safeRepair" in expected, (
                f"{label}: automatic repair lacks safeRepair"
            )
        elif disposition == "REJECT_TARGET":
            assert any(item["subject"] == "target" for item in unwaived_errors), (
                f"{label}: target rejection requires an unwaived target error"
            )
        elif disposition == "REJECT_SOURCE":
            assert any(item["subject"] == "source" for item in unwaived_errors), (
                f"{label}: source rejection requires an unwaived source error"
            )
        elif disposition == "EXEMPT":
            assert errors and not unwaived_errors and waived_rules, (
                f"{label}: exemption must cover every detector error"
            )
            assert any(item["code"] == "check-waived" for item in policy_diagnostics)

        semantic_findings = [
            item
            for item in policy_diagnostics
            if item["code"] == "semantic-review-required"
        ]
        review_disposition = expected.get("reviewDisposition")
        if semantic_findings:
            assert review_disposition == "REVIEW_REQUIRED", (
                f"{label}: semantic findings require REVIEW_REQUIRED"
            )
        else:
            assert review_disposition is None, (
                f"{label}: REVIEW_REQUIRED needs a semantic finding"
            )

        source_syntax_errors = [
            item for item in diagnostics if item["code"] == "source-format-invalid"
        ]
        target_syntax_errors = [
            item for item in diagnostics if item["code"] == "target-format-invalid"
        ]
        if source_syntax_errors:
            assert diagnostics == source_syntax_errors, (
                f"{label}: an invalid source must suppress all other detector findings"
            )
            assert disposition == "REJECT_SOURCE", (
                f"{label}: an invalid source must derive REJECT_SOURCE"
            )
        elif target_syntax_errors:
            assert diagnostics == target_syntax_errors, (
                f"{label}: an invalid target must suppress all other detector findings"
            )
            assert disposition == "REJECT_TARGET", (
                f"{label}: an invalid target must derive REJECT_TARGET"
            )
        if disposition == "REJECT_SOURCE":
            assert unwaived_errors and all(
                item["subject"] == "source" for item in unwaived_errors
            ), f"{label}: REJECT_SOURCE cannot contain target errors"
        elif disposition == "REJECT_TARGET":
            assert not any(item["subject"] == "source" for item in unwaived_errors), (
                f"{label}: source errors take precedence over target rejection"
            )

        if "safeRepair" in expected:
            assert disposition == "AUTO_REPAIR_TARGET", (
                f"{label}: only AUTO_REPAIR_TARGET may define safeRepair"
            )
            assert not case.get("policy", {}).get("waivers"), (
                f"{label}: schema-v1 safe repair cannot coexist with target-hash waivers"
            )
            repair = expected["safeRepair"]
            assert isinstance(repair, dict), f"{label}.safeRepair: must be an object"
            assert_exact_fields(
                repair,
                required=SAFE_REPAIR_FIELDS - {"expectedPolicyDiagnostics"},
                optional={"expectedPolicyDiagnostics"},
                label=f"{label}.safeRepair",
            )
            operations = repair["operations"]
            assert isinstance(operations, list) and operations, (
                f"{label}: repair operations must be nonempty"
            )
            assert operations == sorted(set(operations)), (
                f"{label}: repair operations must be sorted and unique"
            )
            if "COPY_SOURCE_BOUNDARY_WHITESPACE" in operations:
                assert (
                    case.get("policy", {}).get("boundaryWhitespaceInvariant") is True
                ), f"{label}: whitespace repair requires an explicit invariant"
            if "REPLACE_ASCII_APOSTROPHE_BEFORE_FORMATJS_TAG_WITH_U2019" in operations:
                assert "formatjs-apostrophe-escaping" in features, (
                    f"{label}: apostrophe repair requires its renderer capability"
                )
                assert (
                    case.get("policy", {}).get("apostropheRepairStrategy")
                    == "compatibility-u2019"
                ), f"{label}: U+2019 repair requires its explicit compatibility policy"
            if "DOUBLE_ASCII_APOSTROPHE_BEFORE_FORMATJS_TAG" in operations:
                assert "formatjs-apostrophe-escaping" in features, (
                    f"{label}: apostrophe repair requires its renderer capability"
                )
                assert (
                    case.get("policy", {}).get("apostropheRepairStrategy")
                    == "icu-double"
                ), (
                    f"{label}: doubled-apostrophe repair requires its explicit ICU policy"
                )
            computed = apply_repair_operations(
                operations,
                source=source["text"],
                target=target["text"],
            )
            assert repair["expectedTarget"] == computed, (
                f"{label}: expected repaired target does not match declared operations"
            )
            assert (
                apply_repair_operations(
                    operations,
                    source=source["text"],
                    target=computed,
                )
                == computed
            ), f"{label}: safe repair is not idempotent"
            post_repair = validate_diagnostics(
                repair["expectedDiagnostics"],
                label=f"{label}.safeRepair.expectedDiagnostics",
                diagnostic_codes=diagnostic_codes,
                source=source,
                target={"locale": target["locale"], "text": computed},
                policy_lane=False,
            )
            assert not post_repair, (
                f"{label}: schema-v1 safe repair must pass all declared rules"
            )
            if "boundary-whitespace" in rules:
                assert not boundary_whitespace_diagnostics(source["text"], computed), (
                    f"{label}: repaired target still violates boundary whitespace"
                )
            post_repair_policy = validate_diagnostics(
                repair.get("expectedPolicyDiagnostics", []),
                label=f"{label}.safeRepair.expectedPolicyDiagnostics",
                diagnostic_codes=diagnostic_codes,
                source=source,
                target={"locale": target["locale"], "text": computed},
                policy_lane=True,
            )
            assert post_repair_policy == policy_diagnostics, (
                f"{label}: safe repair must preserve policy diagnostics"
            )

        tier_counts[case["tier"]] += 1
        profile_counts[case["profile"]] += 1
        disposition_counts[disposition] += 1

    assert case_ids == sorted(case_ids), "manifest: cases must be sorted by id"
    assert len(case_ids) == len(set(case_ids)), "manifest: duplicate case ids"
    assert tier_counts["cutover"] > 0 and tier_counts["extended"] > 0

    batch_scenarios = manifest["batchScenarios"]
    assert isinstance(batch_scenarios, list) and batch_scenarios, (
        "manifest: batchScenarios must be a nonempty array"
    )
    batch_ids: list[str] = []
    for index, scenario in enumerate(batch_scenarios):
        label = f"batchScenarios[{index}]"
        assert isinstance(scenario, dict), f"{label}: scenario must be an object"
        assert_exact_fields(scenario, required=BATCH_FIELDS, label=label)
        scenario_id = scenario["id"]
        assert isinstance(scenario_id, str) and CASE_ID.fullmatch(scenario_id), (
            f"{label}: invalid scenario id"
        )
        batch_ids.append(scenario_id)
        case_id_list = scenario["caseIds"]
        visited = scenario["expectedVisitedCaseIds"]
        repaired = scenario["expectedRepairedCaseIds"]
        rejected_target = scenario["expectedRejectedTargetCaseIds"]
        rejected_source = scenario["expectedRejectedSourceCaseIds"]
        review = scenario["expectedReviewCaseIds"]
        status = scenario["expectedStatus"]
        assert isinstance(case_id_list, list) and len(case_id_list) >= 2
        assert len(case_id_list) == len(set(case_id_list)), (
            f"{scenario_id}: duplicate cases"
        )
        assert all(case_id in cases_by_id for case_id in case_id_list), (
            f"{scenario_id}: unknown case reference"
        )
        assert visited == case_id_list, (
            f"{scenario_id}: every case must be visited in declared order"
        )
        for field, values in (
            ("expectedRepairedCaseIds", repaired),
            ("expectedRejectedTargetCaseIds", rejected_target),
            ("expectedRejectedSourceCaseIds", rejected_source),
            ("expectedReviewCaseIds", review),
        ):
            assert isinstance(values, list) and len(values) == len(set(values)), (
                f"{scenario_id}: {field} must be a unique array"
            )
            assert set(values) <= set(case_id_list), (
                f"{scenario_id}: {field} contains a case outside the batch"
            )
            assert values == [
                case_id for case_id in case_id_list if case_id in values
            ], f"{scenario_id}: {field} must follow batch order"

        dispositions = {
            case_id: cases_by_id[case_id]["expected"]["disposition"]
            for case_id in case_id_list
        }
        assert repaired == [
            case_id
            for case_id in case_id_list
            if dispositions[case_id] == "AUTO_REPAIR_TARGET"
        ], f"{scenario_id}: repaired cases do not match their dispositions"
        assert rejected_target == [
            case_id
            for case_id in case_id_list
            if dispositions[case_id] == "REJECT_TARGET"
        ], f"{scenario_id}: rejected target cases do not match their dispositions"
        assert rejected_source == [
            case_id
            for case_id in case_id_list
            if dispositions[case_id] == "REJECT_SOURCE"
        ], f"{scenario_id}: rejected source cases do not match their dispositions"
        assert review == [
            case_id
            for case_id in case_id_list
            if cases_by_id[case_id]["expected"].get("reviewDisposition")
            == "REVIEW_REQUIRED"
        ], f"{scenario_id}: review cases do not match their review dispositions"
        expected_status = (
            "COMPLETED_WITH_REJECTIONS"
            if rejected_target or rejected_source
            else "COMPLETED"
        )
        assert status == expected_status, f"{scenario_id}: incorrect batch status"

    assert batch_ids == sorted(batch_ids), (
        "manifest: batch scenarios must be sorted by id"
    )
    assert len(batch_ids) == len(set(batch_ids)), (
        "manifest: duplicate batch scenario ids"
    )

    print(
        f"translation-integrity conformance: {len(case_ids)} cases and "
        f"{len(batch_ids)} batch scenarios plus {len(android_case_ids)} Android cases verified"
    )
    print(
        "tiers: "
        + ", ".join(f"{key}={tier_counts[key]}" for key in sorted(tier_counts))
    )
    print(
        "profiles: "
        + ", ".join(f"{key}={profile_counts[key]}" for key in sorted(profile_counts))
    )
    print(
        "dispositions: "
        + ", ".join(
            f"{key}={disposition_counts[key]}" for key in sorted(disposition_counts)
        )
    )


if __name__ == "__main__":
    main()
