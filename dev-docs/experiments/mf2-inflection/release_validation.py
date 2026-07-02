#!/usr/bin/env python3
"""Validate MF2 inflection release artifacts from a manifest without Java."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

from m2if_decode_fixture import decode_m2if


MANIFEST_SCHEMA = "mojito-mf2-inflection/release-validation-manifest/v0"
REPORT_SCHEMA = "mojito-mf2-inflection/release-validation-report/v0"
COMPILED_TERM_PACK_SCHEMA = "mojito-mf2-inflection/compiled-term-pack/v0"
HINDI_PRONOUN_AGREEMENT_SCHEMA = "mojito-mf2-inflection/hi-pronoun-agreement-pack/v0"
HINDI_PRONOUN_AGREEMENT_PACK_SHAPE = "dependency-pronoun-agreement-rows-v0"

COMPILED_TERM_PACK_JSON = "compiled-term-pack-json"
COMPILED_TERM_PACK_M2IF = "compiled-term-pack-m2if"
HINDI_PRONOUN_AGREEMENT_PACK_JSON = "hindi-pronoun-agreement-pack-json"
MISSING_FORM_CELL_REASON = "missing-form-cell"
SHA256_HEX_RE = re.compile(r"^[0-9a-f]{64}$")
HINDI_PRONOUN_ROW_BYTES = 8
HINDI_PERSON_VALUES = {"first", "second", "third"}
HINDI_ROW_NUMBER_VALUES = {"any", "plural", "singular"}
HINDI_REQUEST_NUMBER_VALUES = {"plural", "singular"}
HINDI_CASE_VALUES = {"accusative", "direct", "ergative", "genitive"}
HINDI_REGISTER_VALUES = {"formal", "informal", "intimate"}
HINDI_GENDER_VALUES = {"feminine", "masculine"}

INVALID_ARTIFACT_CODES = {
    COMPILED_TERM_PACK_JSON: "invalid-compiled-term-pack-json",
    COMPILED_TERM_PACK_M2IF: "invalid-compiled-term-pack-m2if",
    HINDI_PRONOUN_AGREEMENT_PACK_JSON: "invalid-hindi-pronoun-agreement-pack-json",
}


class ReleaseValidationError(ValueError):
    pass


class InvalidReleaseArtifactPath(ReleaseValidationError):
    pass


def require_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ReleaseValidationError(f"Expected object: {label}")
    return value


def require_array(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise ReleaseValidationError(f"Expected array: {label}")
    return value


def require_text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ReleaseValidationError(f"Expected nonblank text: {label}")
    return value


def require_boolean(value: Any, label: str) -> bool:
    if not isinstance(value, bool):
        raise ReleaseValidationError(f"Expected boolean: {label}")
    return value


def load_json(path: Path) -> dict[str, Any]:
    return require_object(json.loads(path.read_text(encoding="utf-8")), str(path))


def resolve_artifact_path(base_dir: Path, manifest_path: str) -> Path:
    path = Path(manifest_path)
    if path.is_absolute():
        raise InvalidReleaseArtifactPath(f"Release artifact path must be relative: {manifest_path}")
    base = base_dir.resolve()
    resolved = (base / path).resolve()
    if not resolved.is_relative_to(base):
        raise InvalidReleaseArtifactPath(
            f"Release artifact path must stay under baseDirectory: {manifest_path}"
        )
    return resolved


def artifact_failure(artifact: dict[str, Any], code: str, message: str) -> dict[str, str]:
    return {
        "artifactId": artifact["artifactId"],
        "kind": artifact["kind"],
        "status": "failed",
        "code": code,
        "message": message,
    }


def artifact_passed(artifact: dict[str, Any]) -> dict[str, str]:
    return {
        "artifactId": artifact["artifactId"],
        "kind": artifact["kind"],
        "status": "passed",
    }


def validate_compiled_term_pack_json(path: Path) -> None:
    payload = load_json(path)
    schema = payload.get("schema", COMPILED_TERM_PACK_SCHEMA)
    if schema != COMPILED_TERM_PACK_SCHEMA:
        raise ReleaseValidationError(f"Unexpected compiled term-pack schema: {schema!r}")
    validate_source_backed_provenance(payload.get("provenance"), "provenance")
    strings = require_string_table(payload.get("strings"), "strings")
    terms = require_term_rows(payload.get("terms"), strings)
    form_sets = require_form_sets(payload.get("formSets"), strings)
    if payload.get("generationSummary") is None:
        validate_export_policy(payload)
    else:
        validate_generation_summary(payload, strings, terms, form_sets)
    validate_release_diagnostics(payload)


def validate_release_diagnostics(payload: dict[str, Any]) -> None:
    diagnostics = payload.get("diagnostics")
    if diagnostics is None:
        return
    items = require_array(diagnostics, "diagnostics")
    if items:
        raise ReleaseValidationError("Compiled term-pack release artifact must not carry diagnostics")


def validate_source_backed_provenance(
    value: Any,
    label: str,
    *,
    labels_must_match_paths: bool = False,
) -> None:
    provenance = require_object(value, label)
    require_text(provenance.get("license"), f"{label}.license")
    require_text(provenance.get("generator"), f"{label}.generator")
    source_labels = require_text_array(provenance.get("sourceLabels"), f"{label}.sourceLabels")
    sources = require_array(provenance.get("sources"), f"{label}.sources")
    if not sources:
        raise ReleaseValidationError(f"{label}.sources must not be empty")
    if len(source_labels) != len(sources):
        raise ReleaseValidationError(f"{label} source label count does not match sources")

    source_paths = []
    for index, item in enumerate(sources):
        source = require_object(item, f"{label}.sources[{index}]")
        source_path = require_text(source.get("path"), f"{label}.sources[{index}].path")
        source_paths.append(source_path)
        require_non_negative_int(source.get("byteSize"), f"{label}.sources[{index}].byteSize")
        sha256 = require_text(source.get("sha256"), f"{label}.sources[{index}].sha256")
        if not SHA256_HEX_RE.fullmatch(sha256):
            raise ReleaseValidationError(
                f"Expected lowercase sha256 hex: {label}.sources[{index}].sha256"
            )
        require_boolean(source.get("gitLfsPointer"), f"{label}.sources[{index}].gitLfsPointer")

    if labels_must_match_paths and source_labels != source_paths:
        raise ReleaseValidationError(f"{label} source labels must match source paths")


def require_string_table(value: Any, label: str) -> list[str]:
    items = require_array(value, label)
    result = []
    for index, item in enumerate(items):
        result.append(require_text(item, f"{label}[{index}]"))
    return result


def require_string_index(value: Any, label: str, strings: list[str]) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0 or value >= len(strings):
        raise ReleaseValidationError(f"Expected string index: {label}")
    return value


def require_term_rows(value: Any, strings: list[str]) -> list[dict[str, str]]:
    terms = []
    for index, item in enumerate(require_array(value, "terms")):
        term = require_object(item, f"terms[{index}]")
        term_id = strings[require_string_index(term.get("id"), f"terms[{index}].id", strings)]
        terms.append({"termId": term_id})
    return terms


def require_form_sets(value: Any, strings: list[str]) -> list[list[str]]:
    form_sets = []
    for form_set_index, item in enumerate(require_array(value, "formSets")):
        form_set = require_object(item, f"formSets[{form_set_index}]")
        form_keys = []
        for form_index, form_item in enumerate(
            require_array(form_set.get("forms"), f"formSets[{form_set_index}].forms")
        ):
            form = require_object(form_item, f"formSets[{form_set_index}].forms[{form_index}]")
            form_keys.append(
                strings[
                    require_string_index(
                        form.get("key"),
                        f"formSets[{form_set_index}].forms[{form_index}].key",
                        strings,
                    )
                ]
            )
        form_sets.append(form_keys)
    return form_sets


def validate_generation_summary(
    payload: dict[str, Any],
    strings: list[str],
    terms: list[dict[str, str]],
    form_sets: list[list[str]],
) -> None:
    del strings
    summary = require_object(payload.get("generationSummary"), "generationSummary")
    form_row_count = sum(len(form_keys) for form_keys in form_sets)
    candidate_terms = require_non_negative_int(
        summary.get("candidateTerms"), "generationSummary.candidateTerms"
    )
    exported_terms = require_non_negative_int(
        summary.get("exportedTerms"), "generationSummary.exportedTerms"
    )
    form_rows = require_non_negative_int(summary.get("formRows"), "generationSummary.formRows")
    if exported_terms > candidate_terms:
        raise ReleaseValidationError("Compiled generation summary exported terms exceed candidates")
    if exported_terms != len(terms):
        raise ReleaseValidationError("Compiled generation summary exported terms mismatch")
    if form_rows != form_row_count:
        raise ReleaseValidationError("Compiled generation summary form row count mismatch")

    review_required_reasons = validate_export_policy(
        summary,
        candidate_terms=candidate_terms,
        exported_terms=exported_terms,
    )

    exported_form_keys_node = summary.get("exportedFormKeys")
    if exported_form_keys_node is None:
        return

    exported_form_keys = require_text_array(
        exported_form_keys_node, "generationSummary.exportedFormKeys"
    )
    require_text(summary.get("policy"), "generationSummary.policy")
    missing_form_keys = require_text_array(
        summary.get("missingFormKeys"), "generationSummary.missingFormKeys"
    )
    review_diagnostics = require_array(
        summary.get("reviewDiagnostics"), "generationSummary.reviewDiagnostics"
    )
    required_form_rows = optional_non_negative_int(
        summary.get("requiredFormRows"), "generationSummary.requiredFormRows"
    )
    required_form_rows_per_term = optional_non_negative_int(
        summary.get("requiredFormRowsPerTerm"), "generationSummary.requiredFormRowsPerTerm"
    )
    if required_form_rows is not None and required_form_rows_per_term is not None:
        raise ReleaseValidationError(
            "Compiled generation summary must not mix total and per-term required form rows"
        )

    if required_form_rows is not None:
        validate_flat_generation_summary(
            summary,
            form_sets,
            exported_form_keys,
            missing_form_keys,
            review_diagnostics,
            review_required_reasons,
            terms,
            required_form_rows,
            form_rows,
        )
    elif required_form_rows_per_term is not None:
        validate_per_term_generation_summary(
            summary,
            form_sets,
            exported_form_keys,
            missing_form_keys,
            review_diagnostics,
            review_required_reasons,
            terms,
            required_form_rows_per_term,
            form_rows,
            exported_terms,
        )


def validate_export_policy(
    payload: dict[str, Any],
    candidate_terms: int | None = None,
    exported_terms: int | None = None,
) -> dict[str, int] | None:
    export_policy = payload.get("exportPolicy")
    generation_summary = payload.get("generationSummary")
    if export_policy is None and isinstance(generation_summary, dict):
        export_policy = generation_summary.get("exportPolicy")
    if export_policy is None:
        return None

    policy = require_object(export_policy, "exportPolicy")
    require_text(policy.get("runtimeExport"), "exportPolicy.runtimeExport")
    require_text(policy.get("compositionMode"), "exportPolicy.compositionMode")
    require_text_array(policy.get("deferredComposition"), "exportPolicy.deferredComposition")
    review_required_terms = require_non_negative_int(
        policy.get("reviewRequiredTerms"), "exportPolicy.reviewRequiredTerms"
    )
    blocked_terms = require_non_negative_int(policy.get("blockedTerms"), "exportPolicy.blockedTerms")
    automatic_export_terms = require_non_negative_int(
        policy.get("automaticExportTerms"), "exportPolicy.automaticExportTerms"
    )
    if (
        exported_terms is not None
        and automatic_export_terms + review_required_terms != exported_terms
    ):
        raise ReleaseValidationError("Compiled export policy exported terms mismatch")
    if (
        candidate_terms is not None
        and automatic_export_terms + review_required_terms + blocked_terms != candidate_terms
    ):
        raise ReleaseValidationError("Compiled export policy term counts do not match candidates")
    review_required_reasons = require_positive_reason_counts(
        policy.get("reviewRequiredReasons"), "exportPolicy.reviewRequiredReasons"
    )
    blocked_reasons = require_positive_reason_counts(
        policy.get("blockedReasons"), "exportPolicy.blockedReasons"
    )
    validate_reason_sum(
        review_required_reasons,
        review_required_terms,
        "Compiled export policy review reason counts do not match review-required terms",
    )
    validate_reason_sum(
        blocked_reasons,
        blocked_terms,
        "Compiled export policy blocked reason counts do not match blocked terms",
    )
    return review_required_reasons


def validate_flat_generation_summary(
    summary: dict[str, Any],
    form_sets: list[list[str]],
    exported_form_keys: list[str],
    missing_form_keys: list[str],
    review_diagnostics: list[Any],
    review_required_reasons: dict[str, int] | None,
    terms: list[dict[str, str]],
    required_form_rows: int,
    form_rows: int,
) -> None:
    if required_form_rows < form_rows or required_form_rows - form_rows != len(missing_form_keys):
        raise ReleaseValidationError("Compiled generation summary missing form count mismatch")
    if len(exported_form_keys) != form_rows:
        raise ReleaseValidationError("Compiled generation summary exported form key count mismatch")
    compiled_form_keys = [form_key for form_set in form_sets for form_key in form_set]
    if compiled_form_keys != exported_form_keys:
        raise ReleaseValidationError(
            "Compiled generation summary exported form keys do not match compiled form rows"
        )
    validate_source_rows(summary.get("sourceRows"), exported_form_keys, form_rows)
    validate_source_rows_by_form_key(summary.get("sourceRowsByFormKey"), exported_form_keys)
    diagnosed_terms = validate_flat_review_diagnostics(review_diagnostics, terms, missing_form_keys)
    validate_missing_form_review_reason_count(review_required_reasons, len(diagnosed_terms))


def validate_per_term_generation_summary(
    summary: dict[str, Any],
    form_sets: list[list[str]],
    exported_form_keys: list[str],
    missing_form_keys: list[str],
    review_diagnostics: list[Any],
    review_required_reasons: dict[str, int] | None,
    terms: list[dict[str, str]],
    required_form_rows_per_term: int,
    form_rows: int,
    exported_terms: int,
) -> None:
    expected_missing_form_cells = {
        (term["termId"], missing_form_key)
        for term in terms
        for missing_form_key in missing_form_keys
    }
    required_total = required_form_rows_per_term * exported_terms
    if required_total < form_rows or required_total - form_rows != len(expected_missing_form_cells):
        raise ReleaseValidationError(
            "Compiled generation summary per-term missing form count mismatch"
        )
    if (
        required_form_rows_per_term < len(exported_form_keys)
        or len(exported_form_keys) * exported_terms != form_rows
    ):
        raise ReleaseValidationError(
            "Compiled generation summary per-term exported form key count mismatch"
        )
    expected_form_key_set = set(exported_form_keys)
    for form_set in form_sets:
        if set(form_set) != expected_form_key_set:
            raise ReleaseValidationError(
                "Compiled generation summary per-term exported form keys do not match compiled form rows"
            )
    validate_per_term_summaries(
        summary.get("terms"),
        terms,
        exported_form_keys,
        required_form_rows_per_term,
    )
    diagnosed_terms = validate_per_term_review_diagnostics(
        review_diagnostics,
        terms,
        expected_missing_form_cells,
    )
    validate_missing_form_review_reason_count(review_required_reasons, len(diagnosed_terms))


def validate_source_rows(value: Any, exported_form_keys: list[str], form_rows: int) -> None:
    source_rows = require_array(value, "generationSummary.sourceRows")
    if len(source_rows) != form_rows or len(source_rows) != len(exported_form_keys):
        raise ReleaseValidationError("Compiled generation summary source row count mismatch")
    for index, source_row in enumerate(source_rows):
        require_non_negative_int(source_row, f"generationSummary.sourceRows[{index}]")


def validate_source_rows_by_form_key(value: Any, exported_form_keys: list[str]) -> None:
    source_rows_by_form_key = require_object(value, "generationSummary.sourceRowsByFormKey")
    if set(source_rows_by_form_key.keys()) != set(exported_form_keys):
        raise ReleaseValidationError(
            "Compiled generation summary source rows do not match exported form keys"
        )
    for form_key, source_row in source_rows_by_form_key.items():
        require_non_negative_int(source_row, f"generationSummary.sourceRowsByFormKey.{form_key}")


def validate_per_term_summaries(
    value: Any,
    terms: list[dict[str, str]],
    exported_form_keys: list[str],
    required_form_rows_per_term: int,
) -> None:
    term_summaries = require_array(value, "generationSummary.terms")
    if len(term_summaries) != len(terms):
        raise ReleaseValidationError("Compiled generation summary term count mismatch")
    known_term_ids = {term["termId"] for term in terms}
    seen_term_ids = set()
    for index, item in enumerate(term_summaries):
        term_summary = require_object(item, f"generationSummary.terms[{index}]")
        term_id = require_text(term_summary.get("termId"), "generationSummary.terms.termId")
        if term_id not in known_term_ids:
            raise ReleaseValidationError(f"Unknown compiled generation summary term: {term_id}")
        if term_id in seen_term_ids:
            raise ReleaseValidationError(f"Duplicate compiled generation summary term: {term_id}")
        seen_term_ids.add(term_id)
        source_rows = require_array(
            term_summary.get("sourceRows"), "generationSummary.terms.sourceRows"
        )
        if (
            len(source_rows) != len(exported_form_keys)
            or len(source_rows) > required_form_rows_per_term
        ):
            raise ReleaseValidationError(
                "Compiled generation summary per-term source row count mismatch"
            )
        for source_row_index, source_row in enumerate(source_rows):
            require_non_negative_int(
                source_row,
                f"generationSummary.terms.sourceRows[{source_row_index}]",
            )
        validate_source_rows_by_form_key(
            term_summary.get("sourceRowsByFormKey"), exported_form_keys
        )


def validate_flat_review_diagnostics(
    review_diagnostics: list[Any],
    terms: list[dict[str, str]],
    missing_form_keys: list[str],
) -> set[str]:
    if len(review_diagnostics) != len(missing_form_keys):
        raise ReleaseValidationError("Compiled generation summary review diagnostics count mismatch")
    known_term_ids = {term["termId"] for term in terms}
    expected_missing_form_keys = set(missing_form_keys)
    diagnosed_missing_form_keys = set()
    diagnosed_term_ids = set()
    for diagnostic in review_diagnostics:
        diagnostic_node = require_object(diagnostic, "generationSummary.reviewDiagnostics[]")
        term_id = require_text(diagnostic_node.get("termId"), "reviewDiagnostics.termId")
        if term_id not in known_term_ids:
            raise ReleaseValidationError(
                f"Unknown compiled generation summary diagnostic term: {term_id}"
            )
        diagnosed_term_ids.add(term_id)
        reason = require_text(diagnostic_node.get("reason"), "reviewDiagnostics.reason")
        if reason != MISSING_FORM_CELL_REASON:
            raise ReleaseValidationError(
                f"Unsupported compiled generation summary review reason: {reason}"
            )
        form_key = require_text(diagnostic_node.get("formKey"), "reviewDiagnostics.formKey")
        if form_key not in expected_missing_form_keys:
            raise ReleaseValidationError(
                f"Compiled generation summary diagnostic does not match missing form: {form_key}"
            )
        if form_key in diagnosed_missing_form_keys:
            raise ReleaseValidationError(
                f"Duplicate compiled generation summary diagnostic form: {form_key}"
            )
        diagnosed_missing_form_keys.add(form_key)
    if diagnosed_missing_form_keys != expected_missing_form_keys:
        raise ReleaseValidationError("Compiled generation summary diagnostics do not cover missing forms")
    return diagnosed_term_ids


def validate_per_term_review_diagnostics(
    review_diagnostics: list[Any],
    terms: list[dict[str, str]],
    expected_missing_form_cells: set[tuple[str, str]],
) -> set[str]:
    if len(review_diagnostics) != len(expected_missing_form_cells):
        raise ReleaseValidationError("Compiled generation summary review diagnostics count mismatch")
    known_term_ids = {term["termId"] for term in terms}
    diagnosed_missing_form_cells = set()
    diagnosed_term_ids = set()
    for diagnostic in review_diagnostics:
        diagnostic_node = require_object(diagnostic, "generationSummary.reviewDiagnostics[]")
        term_id = require_text(diagnostic_node.get("termId"), "reviewDiagnostics.termId")
        if term_id not in known_term_ids:
            raise ReleaseValidationError(
                f"Unknown compiled generation summary diagnostic term: {term_id}"
            )
        diagnosed_term_ids.add(term_id)
        reason = require_text(diagnostic_node.get("reason"), "reviewDiagnostics.reason")
        if reason != MISSING_FORM_CELL_REASON:
            raise ReleaseValidationError(
                f"Unsupported compiled generation summary review reason: {reason}"
            )
        form_key = require_text(diagnostic_node.get("formKey"), "reviewDiagnostics.formKey")
        missing_form_cell = (term_id, form_key)
        if missing_form_cell not in expected_missing_form_cells:
            raise ReleaseValidationError(
                "Compiled generation summary diagnostic does not match missing form: "
                f"{term_id}/{form_key}"
            )
        if missing_form_cell in diagnosed_missing_form_cells:
            raise ReleaseValidationError(
                f"Duplicate compiled generation summary diagnostic cell: {term_id}/{form_key}"
            )
        diagnosed_missing_form_cells.add(missing_form_cell)
    if diagnosed_missing_form_cells != expected_missing_form_cells:
        raise ReleaseValidationError("Compiled generation summary diagnostics do not cover missing forms")
    return diagnosed_term_ids


def validate_missing_form_review_reason_count(
    review_required_reasons: dict[str, int] | None,
    expected_terms: int,
) -> None:
    if review_required_reasons is None:
        return
    if review_required_reasons.get(MISSING_FORM_CELL_REASON, 0) != expected_terms:
        raise ReleaseValidationError(
            "Compiled export policy missing-form-cell review reason count does not match review diagnostics"
        )


def require_text_array(value: Any, label: str) -> list[str]:
    items = require_array(value, label)
    result = []
    for index, item in enumerate(items):
        result.append(require_text(item, f"{label}[{index}]"))
    if len(set(result)) != len(result):
        raise ReleaseValidationError(f"Duplicate text value: {label}")
    return result


def optional_non_negative_int(value: Any, label: str) -> int | None:
    if value is None:
        return None
    return require_non_negative_int(value, label)


def require_non_negative_int(value: Any, label: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise ReleaseValidationError(f"Expected non-negative integer: {label}")
    return value


def require_positive_reason_counts(value: Any, label: str) -> dict[str, int]:
    reason_counts = require_object(value, label)
    result = {}
    for reason, count in reason_counts.items():
        reason_key = require_text(reason, f"{label} key")
        if not isinstance(count, int) or isinstance(count, bool) or count <= 0:
            raise ReleaseValidationError(f"Expected positive reason count: {label}.{reason_key}")
        result[reason_key] = count
    return result


def validate_reason_sum(reason_counts: dict[str, int], expected: int, message: str) -> None:
    if sum(reason_counts.values()) != expected:
        raise ReleaseValidationError(message)


def validate_hindi_pronoun_agreement_json(path: Path) -> None:
    payload = load_json(path)
    schema = require_text(payload.get("schema"), "schema")
    if schema != HINDI_PRONOUN_AGREEMENT_SCHEMA:
        raise ReleaseValidationError(f"Unexpected Hindi pronoun agreement schema: {schema!r}")
    if require_text(payload.get("locale"), "locale") != "hi":
        raise ReleaseValidationError("Hindi pronoun agreement pack locale must be hi")
    if require_text(payload.get("packShape"), "packShape") != HINDI_PRONOUN_AGREEMENT_PACK_SHAPE:
        raise ReleaseValidationError("Unexpected Hindi pronoun agreement pack shape")
    validate_source_backed_provenance(
        payload.get("provenance"),
        "provenance",
        labels_must_match_paths=True,
    )
    rows = validate_hindi_pronoun_rows(payload.get("rows"))
    validate_hindi_pronoun_summary(payload.get("summary"), rows)


def validate_hindi_pronoun_rows(value: Any) -> list[dict[str, Any]]:
    rows = []
    selectors = set()
    for index, item in enumerate(require_array(value, "rows")):
        row = require_object(item, f"rows[{index}]")
        value_text = require_text(row.get("value"), f"rows[{index}].value")
        line = require_positive_int(row.get("line"), f"rows[{index}].line")
        person = require_allowed_text(
            row.get("person"), f"rows[{index}].person", HINDI_PERSON_VALUES
        )
        number = require_allowed_text(
            row.get("number"), f"rows[{index}].number", HINDI_ROW_NUMBER_VALUES
        )
        grammatical_case = require_allowed_text(
            row.get("case"), f"rows[{index}].case", HINDI_CASE_VALUES
        )
        register = optional_allowed_text(
            row.get("register"), f"rows[{index}].register", HINDI_REGISTER_VALUES
        )
        dependency_gender = optional_allowed_text(
            row.get("dependencyGender"),
            f"rows[{index}].dependencyGender",
            HINDI_GENDER_VALUES,
        )
        dependency_number = optional_allowed_text(
            row.get("dependencyNumber"),
            f"rows[{index}].dependencyNumber",
            HINDI_REQUEST_NUMBER_VALUES,
        )

        if grammatical_case == "genitive":
            if dependency_gender is None or dependency_number is None:
                raise ReleaseValidationError("Hindi genitive pronoun row requires dependency")
        elif dependency_gender is not None or dependency_number is not None:
            raise ReleaseValidationError("Hindi non-genitive pronoun row cannot include dependency")
        if person == "second":
            if register is None:
                raise ReleaseValidationError("Hindi second-person pronoun row requires register")
        elif register is not None:
            raise ReleaseValidationError("Hindi non-second pronoun row cannot include register")

        expanded_numbers = ["plural", "singular"] if number == "any" else [number]
        for expanded_number in expanded_numbers:
            selector = (
                person,
                expanded_number,
                grammatical_case,
                register,
                dependency_gender,
                dependency_number,
            )
            if selector in selectors:
                raise ReleaseValidationError(f"Ambiguous Hindi pronoun selector: {selector}")
            selectors.add(selector)

        rows.append(
            {
                "case": grammatical_case,
                "dependencyGender": dependency_gender,
                "dependencyNumber": dependency_number,
                "line": line,
                "number": number,
                "person": person,
                "register": register,
                "value": value_text,
            }
        )
    return rows


def validate_hindi_pronoun_summary(value: Any, rows: list[dict[str, Any]]) -> None:
    summary = require_object(value, "summary")
    if require_non_negative_int(summary.get("rows"), "summary.rows") != len(rows):
        raise ReleaseValidationError("Hindi pronoun summary row count mismatch")
    unique_values = len({row["value"] for row in rows})
    if (
        require_non_negative_int(summary.get("uniqueValues"), "summary.uniqueValues")
        != unique_values
    ):
        raise ReleaseValidationError("Hindi pronoun unique value count mismatch")
    genitive_rows = sum(1 for row in rows if row["case"] == "genitive")
    if (
        require_non_negative_int(summary.get("genitiveRows"), "summary.genitiveRows")
        != genitive_rows
    ):
        raise ReleaseValidationError("Hindi pronoun genitive row count mismatch")
    dependency_rows = sum(
        1
        for row in rows
        if row["dependencyGender"] is not None or row["dependencyNumber"] is not None
    )
    if (
        require_non_negative_int(summary.get("dependencyRows"), "summary.dependencyRows")
        != dependency_rows
    ):
        raise ReleaseValidationError("Hindi pronoun dependency row count mismatch")
    invariant_number_rows = sum(1 for row in rows if row["number"] == "any")
    if (
        require_non_negative_int(summary.get("invariantNumberRows"), "summary.invariantNumberRows")
        != invariant_number_rows
    ):
        raise ReleaseValidationError("Hindi pronoun invariant number row count mismatch")

    estimate = require_object(summary.get("binaryLowerBoundBytes"), "summary.binaryLowerBoundBytes")
    row_bytes = len(rows) * HINDI_PRONOUN_ROW_BYTES
    if (
        require_non_negative_int(
            estimate.get("rowBytes"),
            "summary.binaryLowerBoundBytes.rowBytes",
        )
        != row_bytes
    ):
        raise ReleaseValidationError("Hindi pronoun row byte estimate is incoherent")
    string_pool_bytes = sum(
        len(value.encode("utf-8")) + 1 for value in {row["value"] for row in rows}
    )
    if (
        require_non_negative_int(
            estimate.get("stringPoolBytes"),
            "summary.binaryLowerBoundBytes.stringPoolBytes",
        )
        != string_pool_bytes
    ):
        raise ReleaseValidationError("Hindi pronoun string-pool byte estimate is incoherent")
    if (
        require_non_negative_int(
            estimate.get("totalBytes"),
            "summary.binaryLowerBoundBytes.totalBytes",
        )
        != row_bytes + string_pool_bytes
    ):
        raise ReleaseValidationError("Hindi pronoun binary lower-bound estimate is incoherent")


def require_positive_int(value: Any, label: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise ReleaseValidationError(f"Expected positive integer: {label}")
    return value


def require_allowed_text(value: Any, label: str, allowed_values: set[str]) -> str:
    text = require_text(value, label)
    if text not in allowed_values:
        raise ReleaseValidationError(f"Unsupported value for {label}: {text}")
    return text


def optional_allowed_text(value: Any, label: str, allowed_values: set[str]) -> str | None:
    if value is None:
        return None
    return require_allowed_text(value, label, allowed_values)


def validate_m2if(path: Path) -> None:
    pack, _sections = decode_m2if(path.read_bytes())
    validate_source_backed_provenance(pack.get("provenance"), "provenance")


def validate_artifact(artifact: dict[str, Any], base_dir: Path) -> dict[str, str]:
    try:
        path = resolve_artifact_path(base_dir, artifact["path"])
    except InvalidReleaseArtifactPath as error:
        return artifact_failure(artifact, "invalid-release-artifact-path", str(error))

    try:
        kind = artifact["kind"]
        if kind == COMPILED_TERM_PACK_JSON:
            validate_compiled_term_pack_json(path)
        elif kind == COMPILED_TERM_PACK_M2IF:
            validate_m2if(path)
        elif kind == HINDI_PRONOUN_AGREEMENT_PACK_JSON:
            validate_hindi_pronoun_agreement_json(path)
        else:
            raise ReleaseValidationError(f"Unsupported release artifact kind: {kind}")
        return artifact_passed(artifact)
    except OSError as error:
        return artifact_failure(artifact, "unreadable-release-artifact", str(error))
    except Exception as error:
        return artifact_failure(artifact, INVALID_ARTIFACT_CODES[artifact["kind"]], str(error))


def load_manifest(path: Path) -> dict[str, Any]:
    manifest = load_json(path)
    schema = require_text(manifest.get("schema"), "schema")
    if schema != MANIFEST_SCHEMA:
        raise ReleaseValidationError(f"Unsupported release validation manifest schema: {schema}")
    artifacts = []
    for artifact in require_array(manifest.get("artifacts"), "artifacts"):
        item = require_object(artifact, "artifacts[]")
        artifact_id = require_text(item.get("artifactId"), "artifactId")
        kind = require_text(item.get("kind"), "kind")
        if kind not in INVALID_ARTIFACT_CODES:
            raise ReleaseValidationError(f"Unsupported release artifact kind: {kind}")
        artifacts.append(
            {
                "artifactId": artifact_id,
                "kind": kind,
                "path": require_text(item.get("path"), "path"),
            }
        )
    return {"schema": schema, "artifacts": artifacts}


def validate_manifest(manifest_path: Path, base_dir: Path) -> dict[str, Any]:
    manifest = load_manifest(manifest_path)
    artifacts = [validate_artifact(artifact, base_dir) for artifact in manifest["artifacts"]]
    sorted_artifacts = sorted(artifacts, key=lambda item: (item["artifactId"], item["kind"]))
    passed = sum(1 for artifact in sorted_artifacts if artifact["status"] == "passed")
    failed = len(sorted_artifacts) - passed
    return {
        "artifacts": sorted_artifacts,
        "schema": REPORT_SCHEMA,
        "summary": {
            "artifacts": len(sorted_artifacts),
            "failed": failed,
            "passed": passed,
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument(
        "--base-dir",
        type=Path,
        help="Directory release artifact paths resolve under. Defaults to the manifest directory.",
    )
    parser.add_argument("--out", type=Path, help="Write the validation report to this path.")
    parser.add_argument(
        "--allow-failures",
        action="store_true",
        help="Exit zero even when the validation report contains failed artifacts.",
    )
    args = parser.parse_args()

    report = validate_manifest(args.manifest, args.base_dir or args.manifest.parent)
    payload = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")

    if report["summary"]["failed"] and not args.allow_failures:
        print(
            f"Release validation failed for {report['summary']['failed']} artifact(s)",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
