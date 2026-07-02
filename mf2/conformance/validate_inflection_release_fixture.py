#!/usr/bin/env python3
"""Validate the MF2 inflection release fixture bundle from the shared gate.

This pins artifact shape and source fixture provenance for selected V0 grammar
slices; it does not certify complete locale or grammar coverage and does not
publish package-local runtime APIs.
"""

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path
from typing import Any


CONFORMANCE_ROOT = Path(__file__).resolve().parent
MF2_ROOT = CONFORMANCE_ROOT.parent
REPO_ROOT = MF2_ROOT.parent
BUNDLE_SCRIPT = REPO_ROOT / "dev-docs/experiments/mf2-inflection/release_fixture_bundle.py"

EXPECTED_MANIFEST_SCHEMA = "mojito-mf2-inflection/release-validation-manifest/v0"
EXPECTED_REPORT_SCHEMA = "mojito-mf2-inflection/release-validation-report/v0"
MANIFEST_FILE = "release-validation-manifest.json"
REPORT_FILE = "release-validation-report.json"
COMPILED_TERM_PACK_JSON = "compiled-term-pack-json"
COMPILED_TERM_PACK_M2IF = "compiled-term-pack-m2if"
HINDI_PRONOUN_AGREEMENT_PACK_JSON = "hindi-pronoun-agreement-pack-json"
EXPECTED_MANIFEST_KEYS = frozenset({"schema", "artifacts"})
EXPECTED_REPORT_KEYS = frozenset({"schema", "summary", "artifacts"})
EXPECTED_REPORT_SUMMARY_KEYS = frozenset({"artifacts", "passed", "failed"})
EXPECTED_ARTIFACTS_BY_ID = {
    "ar-approved-json": (COMPILED_TERM_PACK_JSON, "artifacts/ar-approved.json"),
    "ar-approved-m2if": (COMPILED_TERM_PACK_M2IF, "artifacts/ar-approved.m2if"),
    "ar-review-required-json": (COMPILED_TERM_PACK_JSON, "artifacts/ar-review-required.json"),
    "ar-review-required-m2if": (COMPILED_TERM_PACK_M2IF, "artifacts/ar-review-required.m2if"),
    "da-genitive-definiteness-json": (
        COMPILED_TERM_PACK_JSON,
        "artifacts/da-genitive-definiteness.json",
    ),
    "da-genitive-definiteness-m2if": (
        COMPILED_TERM_PACK_M2IF,
        "artifacts/da-genitive-definiteness.m2if",
    ),
    "de-article-case-json": (COMPILED_TERM_PACK_JSON, "artifacts/de-article-case.json"),
    "de-article-case-m2if": (COMPILED_TERM_PACK_M2IF, "artifacts/de-article-case.m2if"),
    "es-article-json": (COMPILED_TERM_PACK_JSON, "artifacts/es-article.json"),
    "es-article-m2if": (COMPILED_TERM_PACK_M2IF, "artifacts/es-article.m2if"),
    "he-construct-json": (COMPILED_TERM_PACK_JSON, "artifacts/he-construct.json"),
    "he-construct-m2if": (COMPILED_TERM_PACK_M2IF, "artifacts/he-construct.m2if"),
    "hi-case-form-json": (COMPILED_TERM_PACK_JSON, "artifacts/hi-case-form.json"),
    "hi-case-form-m2if": (COMPILED_TERM_PACK_M2IF, "artifacts/hi-case-form.m2if"),
    "hi-pronouns-json": (
        HINDI_PRONOUN_AGREEMENT_PACK_JSON,
        "artifacts/hi-pronouns.json",
    ),
    "it-article-json": (COMPILED_TERM_PACK_JSON, "artifacts/it-article.json"),
    "it-article-m2if": (COMPILED_TERM_PACK_M2IF, "artifacts/it-article.m2if"),
    "ml-approved-case-form-json": (
        COMPILED_TERM_PACK_JSON,
        "artifacts/ml-approved-case-form.json",
    ),
    "ml-approved-case-form-m2if": (
        COMPILED_TERM_PACK_M2IF,
        "artifacts/ml-approved-case-form.m2if",
    ),
    "ml-case-form-json": (COMPILED_TERM_PACK_JSON, "artifacts/ml-case-form.json"),
    "ml-case-form-m2if": (COMPILED_TERM_PACK_M2IF, "artifacts/ml-case-form.m2if"),
    "pt-agreement-json": (COMPILED_TERM_PACK_JSON, "artifacts/pt-agreement.json"),
    "pt-agreement-m2if": (COMPILED_TERM_PACK_M2IF, "artifacts/pt-agreement.m2if"),
    "ru-case-form-json": (COMPILED_TERM_PACK_JSON, "artifacts/ru-case-form.json"),
    "ru-case-form-m2if": (COMPILED_TERM_PACK_M2IF, "artifacts/ru-case-form.m2if"),
    "sr-case-form-json": (COMPILED_TERM_PACK_JSON, "artifacts/sr-case-form.json"),
    "sr-case-form-m2if": (COMPILED_TERM_PACK_M2IF, "artifacts/sr-case-form.m2if"),
    "sv-genitive-definiteness-json": (
        COMPILED_TERM_PACK_JSON,
        "artifacts/sv-genitive-definiteness.json",
    ),
    "sv-genitive-definiteness-m2if": (
        COMPILED_TERM_PACK_M2IF,
        "artifacts/sv-genitive-definiteness.m2if",
    ),
    "tr-explicit-template-auto-json": (
        COMPILED_TERM_PACK_JSON,
        "artifacts/tr-explicit-template-auto.json",
    ),
    "tr-explicit-template-auto-m2if": (
        COMPILED_TERM_PACK_M2IF,
        "artifacts/tr-explicit-template-auto.m2if",
    ),
    "tr-explicit-template-json": (
        COMPILED_TERM_PACK_JSON,
        "artifacts/tr-explicit-template.json",
    ),
    "tr-explicit-template-m2if": (
        COMPILED_TERM_PACK_M2IF,
        "artifacts/tr-explicit-template.m2if",
    ),
    "tr-suffix-json": (COMPILED_TERM_PACK_JSON, "artifacts/tr-suffix.json"),
    "tr-suffix-m2if": (COMPILED_TERM_PACK_M2IF, "artifacts/tr-suffix.m2if"),
}
EXPECTED_ARTIFACT_IDS = tuple(sorted(EXPECTED_ARTIFACTS_BY_ID))
EXPECTED_ARTIFACTS = len(EXPECTED_ARTIFACT_IDS)
EXPECTED_SELECTED_V0_RELEASE_ARTIFACT_LOCALES = frozenset(
    {"ar", "da", "de", "es", "he", "hi", "it", "ml", "pt", "ru", "sr", "sv", "tr"}
)
EXCLUDED_RELEASE_ARTIFACT_LOCALES = frozenset(
    {"en", "id", "ja", "ko", "ms", "nb", "nl", "pl", "th", "vi", "yue", "zh"}
)
EXPECTED_BUNDLE_ARTIFACT_KEYS = frozenset({"artifact_id", "kind", "source", "path"})
EXPECTED_MANIFEST_ARTIFACT_KEYS = frozenset({"artifactId", "kind", "path"})
EXPECTED_PASSED_REPORT_ARTIFACT_KEYS = frozenset({"artifactId", "kind", "status"})
EXPECTED_ARTIFACT_SOURCES_BY_ID = {
    "ar-approved-json": "ar_compiled_approved_explicit_form_pack_fixture.json",
    "ar-approved-m2if": "ar_compiled_approved_explicit_form_pack_fixture.m2if.hex",
    "ar-review-required-json": "ar_compiled_explicit_form_pack_fixture.json",
    "ar-review-required-m2if": "ar_compiled_explicit_form_pack_fixture.m2if.hex",
    "da-genitive-definiteness-json": "da_compiled_genitive_definiteness_pack_fixture.json",
    "da-genitive-definiteness-m2if": "da_compiled_genitive_definiteness_pack_fixture.m2if.hex",
    "de-article-case-json": "de_compiled_article_case_pack_fixture.json",
    "de-article-case-m2if": "de_compiled_article_case_pack_fixture.m2if.hex",
    "es-article-json": "es_compiled_article_pack_fixture.json",
    "es-article-m2if": "es_compiled_article_pack_fixture.m2if.hex",
    "he-construct-json": "he_compiled_construct_form_pack_fixture.json",
    "he-construct-m2if": "he_compiled_construct_form_pack_fixture.m2if.hex",
    "hi-case-form-json": "hi_compiled_case_form_pack_fixture.json",
    "hi-case-form-m2if": "hi_compiled_case_form_pack_fixture.m2if.hex",
    "hi-pronouns-json": "hi_pronoun_agreement_pack_fixture.json",
    "it-article-json": "it_compiled_article_pack_fixture.json",
    "it-article-m2if": "it_compiled_article_pack_fixture.m2if.hex",
    "ml-approved-case-form-json": "ml_compiled_approved_case_form_pack_fixture.json",
    "ml-approved-case-form-m2if": "ml_compiled_approved_case_form_pack_fixture.m2if.hex",
    "ml-case-form-json": "ml_compiled_case_form_pack_fixture.json",
    "ml-case-form-m2if": "ml_compiled_case_form_pack_fixture.m2if.hex",
    "pt-agreement-json": "pt_compiled_agreement_pack_fixture.json",
    "pt-agreement-m2if": "pt_compiled_agreement_pack_fixture.m2if.hex",
    "ru-case-form-json": "ru_compiled_case_form_pack_fixture.json",
    "ru-case-form-m2if": "ru_compiled_case_form_pack_fixture.m2if.hex",
    "sr-case-form-json": "sr_compiled_case_form_pack_fixture.json",
    "sr-case-form-m2if": "sr_compiled_case_form_pack_fixture.m2if.hex",
    "sv-genitive-definiteness-json": "sv_compiled_genitive_definiteness_pack_fixture.json",
    "sv-genitive-definiteness-m2if": "sv_compiled_genitive_definiteness_pack_fixture.m2if.hex",
    "tr-explicit-template-auto-json": "tr_compiled_explicit_template_auto_pack_fixture.json",
    "tr-explicit-template-auto-m2if": "tr_compiled_explicit_template_auto_pack_fixture.m2if.hex",
    "tr-explicit-template-json": "tr_compiled_explicit_template_pack_fixture.json",
    "tr-explicit-template-m2if": "tr_compiled_explicit_template_pack_fixture.m2if.hex",
    "tr-suffix-json": "tr_compiled_suffix_pack_fixture.json",
    "tr-suffix-m2if": "tr_compiled_suffix_pack_fixture.m2if.hex",
}


def fail(message: str) -> int:
    print(f"Inflection release fixture validation failed: {message}", file=sys.stderr)
    return 1


def require_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"Expected object: {label}")
    return value


def require_array(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise ValueError(f"Expected array: {label}")
    return value


def require_text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"Expected non-empty text: {label}")
    return value


def reject_unexpected_keys(item: dict[str, Any], expected_keys: frozenset[str], label: str) -> None:
    unexpected_keys = sorted(set(item) - expected_keys)
    if unexpected_keys:
        raise ValueError(f"Unexpected keys in {label}: {unexpected_keys!r}")


def artifact_row_label(base_label: str, artifact_id: Any) -> str:
    if isinstance(artifact_id, str) and artifact_id:
        return f"{base_label}[{artifact_id}]"
    return f"{base_label}[]"


def require_bundle_artifact_fields(artifact: Any) -> dict[str, Any]:
    try:
        return vars(artifact)
    except TypeError as error:
        raise ValueError("Expected object: bundle ARTIFACTS[]") from error


def load_json(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise FileNotFoundError(f"Missing required release fixture file: {path.name}")
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError as error:
        raise ValueError(
            f"Invalid UTF-8 in release fixture file {path.name}: "
            f"{error.reason} at byte {error.start}"
        ) from error
    try:
        payload = json.loads(text)
    except json.JSONDecodeError as error:
        raise ValueError(
            f"Invalid JSON in release fixture file {path.name}: "
            f"{error.msg} at line {error.lineno} column {error.colno}"
        ) from error
    return require_object(payload, path.name)


def artifact_id_drift_message(label: str, actual_ids: tuple[str, ...]) -> str:
    actual_counts = Counter(actual_ids)
    expected_counts = Counter(EXPECTED_ARTIFACT_IDS)
    missing = sorted((expected_counts - actual_counts).elements())
    unexpected = sorted(
        artifact_id
        for artifact_id, count in actual_counts.items()
        if artifact_id not in expected_counts
        for _ in range(count)
    )
    duplicates = sorted(
        artifact_id
        for artifact_id, count in actual_counts.items()
        if count > 1
    )
    return (
        f"Unexpected {label} artifact IDs: "
        f"missing={missing!r} unexpected={unexpected!r} duplicates={duplicates!r}"
    )


def artifact_order_drift_message(label: str, actual_ids: tuple[str, ...]) -> str:
    return (
        f"Unexpected {label} artifact order: "
        f"expected={list(EXPECTED_ARTIFACT_IDS)!r} actual={list(actual_ids)!r}"
    )


def artifact_locale(artifact_id: str) -> str:
    return artifact_id.split("-", 1)[0]


def validate_selected_v0_release_artifact_scope(artifact_ids: tuple[str, ...], label: str) -> None:
    locales = frozenset(artifact_locale(artifact_id) for artifact_id in artifact_ids)
    if locales != EXPECTED_SELECTED_V0_RELEASE_ARTIFACT_LOCALES:
        missing = sorted(EXPECTED_SELECTED_V0_RELEASE_ARTIFACT_LOCALES - locales)
        unexpected = sorted(locales - EXPECTED_SELECTED_V0_RELEASE_ARTIFACT_LOCALES)
        raise ValueError(
            f"Unexpected selected V0 release artifact locale scope in {label}: "
            f"missing={missing!r} unexpected={unexpected!r}"
        )
    excluded = sorted(locales & EXCLUDED_RELEASE_ARTIFACT_LOCALES)
    if excluded:
        raise ValueError(
            f"{label} includes metadata-only or unavailable locale artifacts: {excluded!r}"
        )


def run_bundle(out_dir: Path) -> None:
    env = dict(os.environ)
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    command = [
        sys.executable,
        str(BUNDLE_SCRIPT),
        "--out-dir",
        str(out_dir),
        "--validate",
    ]
    result = subprocess.run(
        command,
        cwd=REPO_ROOT,
        text=True,
        capture_output=True,
        check=False,
        env=env,
    )
    if result.returncode:
        if result.stdout:
            print(result.stdout, file=sys.stderr, end="")
        if result.stderr:
            print(result.stderr, file=sys.stderr, end="")
        raise RuntimeError(f"release_fixture_bundle.py exited {result.returncode}")


def load_bundle_script_artifacts() -> Any:
    spec = importlib.util.spec_from_file_location(
        "_mojito_mf2_release_fixture_bundle",
        BUNDLE_SCRIPT,
    )
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load bundle script: {BUNDLE_SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    previous_module = sys.modules.get(spec.name)
    previous_dont_write_bytecode = sys.dont_write_bytecode
    sys.modules[spec.name] = module
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(BUNDLE_SCRIPT.parent))
    try:
        spec.loader.exec_module(module)
    finally:
        sys.path.pop(0)
        sys.dont_write_bytecode = previous_dont_write_bytecode
        if previous_module is None:
            del sys.modules[spec.name]
        else:
            sys.modules[spec.name] = previous_module
    return getattr(module, "ARTIFACTS", None)


def validate_bundle_artifact_specs(artifacts: Any) -> None:
    artifact_rows = require_array(artifacts, "bundle ARTIFACTS")
    actual_specs: dict[str, tuple[str, str, str]] = {}
    actual_ids_in_order = []
    duplicate_ids = []
    for artifact in artifact_rows:
        artifact_fields = require_bundle_artifact_fields(artifact)
        artifact_id = require_text(
            artifact_fields.get("artifact_id"),
            "bundle ARTIFACTS[].artifact_id",
        )
        reject_unexpected_keys(
            artifact_fields,
            EXPECTED_BUNDLE_ARTIFACT_KEYS,
            artifact_row_label("bundle ARTIFACTS", artifact_id),
        )
        actual_ids_in_order.append(artifact_id)
        artifact_kind = require_text(
            artifact_fields.get("kind"),
            f"bundle ARTIFACTS[{artifact_id}].kind",
        )
        artifact_path = require_text(
            artifact_fields.get("path"),
            f"bundle ARTIFACTS[{artifact_id}].path",
        )
        artifact_source = require_text(
            artifact_fields.get("source"),
            f"bundle ARTIFACTS[{artifact_id}].source",
        )
        if artifact_id in actual_specs:
            duplicate_ids.append(artifact_id)
        actual_specs[artifact_id] = (artifact_kind, artifact_path, artifact_source)
    if duplicate_ids:
        raise ValueError(f"Duplicate bundle artifact IDs: {sorted(duplicate_ids)!r}")

    actual_ids = tuple(sorted(actual_specs))
    validate_selected_v0_release_artifact_scope(actual_ids, "bundle source")
    if actual_ids != EXPECTED_ARTIFACT_IDS:
        raise ValueError(artifact_id_drift_message("bundle source", actual_ids))
    if tuple(actual_ids_in_order) != EXPECTED_ARTIFACT_IDS:
        raise ValueError(artifact_order_drift_message("bundle source", tuple(actual_ids_in_order)))

    for artifact_id in EXPECTED_ARTIFACT_IDS:
        expected_kind, expected_path = EXPECTED_ARTIFACTS_BY_ID[artifact_id]
        expected_source = EXPECTED_ARTIFACT_SOURCES_BY_ID[artifact_id]
        artifact_kind, artifact_path, artifact_source = actual_specs[artifact_id]
        if artifact_kind != expected_kind:
            raise ValueError(
                f"Unexpected bundle artifact kind for {artifact_id}: "
                f"expected {expected_kind!r}, got {artifact_kind!r}"
            )
        if artifact_path != expected_path:
            raise ValueError(
                f"Unexpected bundle artifact path for {artifact_id}: "
                f"expected {expected_path!r}, got {artifact_path!r}"
            )
        if artifact_source != expected_source:
            raise ValueError(
                f"Unexpected release fixture source for {artifact_id}: "
                f"expected {expected_source!r}, got {artifact_source!r}"
            )


def validate_bundle_script_artifact_specs() -> None:
    validate_bundle_artifact_specs(load_bundle_script_artifacts())


def validate_materialized_bundle(out_dir: Path) -> dict[str, Any]:
    manifest = load_json(out_dir / MANIFEST_FILE)
    reject_unexpected_keys(manifest, EXPECTED_MANIFEST_KEYS, MANIFEST_FILE)
    if manifest.get("schema") != EXPECTED_MANIFEST_SCHEMA:
        raise ValueError(
            f"Unexpected {MANIFEST_FILE} schema: {manifest.get('schema')!r}"
        )

    manifest_artifacts = require_array(manifest.get("artifacts"), f"{MANIFEST_FILE}.artifacts")
    if len(manifest_artifacts) != EXPECTED_ARTIFACTS:
        raise ValueError(
            f"Expected {EXPECTED_ARTIFACTS} {MANIFEST_FILE} artifacts, "
            f"got {len(manifest_artifacts)}"
        )
    manifest_artifact_ids_in_order = tuple(
        require_text(
            require_object(artifact, f"{MANIFEST_FILE}.artifacts[]").get("artifactId"),
            f"{MANIFEST_FILE}.artifacts[].artifactId",
        )
        for artifact in manifest_artifacts
    )
    manifest_artifact_ids = tuple(sorted(manifest_artifact_ids_in_order))
    validate_selected_v0_release_artifact_scope(manifest_artifact_ids, MANIFEST_FILE)
    if manifest_artifact_ids != EXPECTED_ARTIFACT_IDS:
        raise ValueError(
            artifact_id_drift_message(MANIFEST_FILE, manifest_artifact_ids)
        )
    if manifest_artifact_ids_in_order != EXPECTED_ARTIFACT_IDS:
        raise ValueError(
            artifact_order_drift_message(MANIFEST_FILE, manifest_artifact_ids_in_order)
        )

    for artifact in manifest_artifacts:
        item = require_object(artifact, f"{MANIFEST_FILE}.artifacts[]")
        artifact_id = require_text(
            item.get("artifactId"),
            f"{MANIFEST_FILE}.artifacts[].artifactId",
        )
        reject_unexpected_keys(
            item,
            EXPECTED_MANIFEST_ARTIFACT_KEYS,
            artifact_row_label(f"{MANIFEST_FILE}.artifacts", artifact_id),
        )
        expected_kind, expected_path = EXPECTED_ARTIFACTS_BY_ID[artifact_id]
        artifact_kind = require_text(
            item.get("kind"),
            f"{MANIFEST_FILE}.artifacts[{artifact_id}].kind",
        )
        if artifact_kind != expected_kind:
            raise ValueError(
                f"Unexpected {MANIFEST_FILE} artifact kind for {artifact_id}: "
                f"expected {expected_kind!r}, got {artifact_kind!r}"
            )
        artifact_path = require_text(
            item.get("path"),
            f"{MANIFEST_FILE}.artifacts[{artifact_id}].path",
        )
        if artifact_path != expected_path:
            raise ValueError(
                f"Unexpected {MANIFEST_FILE} artifact path for {artifact_id}: "
                f"expected {expected_path!r}, got {artifact_path!r}"
            )
        resolved = (out_dir / artifact_path).resolve()
        if not resolved.is_relative_to(out_dir.resolve()):
            raise ValueError(
                f"{MANIFEST_FILE} artifact escapes bundle directory: {artifact_path}"
            )
        if not resolved.is_file():
            raise ValueError(
                f"Missing {MANIFEST_FILE} materialized artifact: {artifact_path}"
            )

    report = load_json(out_dir / REPORT_FILE)
    reject_unexpected_keys(report, EXPECTED_REPORT_KEYS, REPORT_FILE)
    if report.get("schema") != EXPECTED_REPORT_SCHEMA:
        raise ValueError(
            f"Unexpected {REPORT_FILE} schema: {report.get('schema')!r}"
        )

    summary = require_object(report.get("summary"), f"{REPORT_FILE}.summary")
    reject_unexpected_keys(
        summary,
        EXPECTED_REPORT_SUMMARY_KEYS,
        f"{REPORT_FILE}.summary",
    )
    if summary.get("artifacts") != EXPECTED_ARTIFACTS:
        raise ValueError(
            f"Expected {EXPECTED_ARTIFACTS} {REPORT_FILE} summary artifacts, "
            f"got {summary.get('artifacts')!r}"
        )
    if summary.get("passed") != EXPECTED_ARTIFACTS or summary.get("failed") != 0:
        raise ValueError(
            f"Expected all {REPORT_FILE} artifacts to pass, got {summary!r}"
        )

    report_artifacts = require_array(
        report.get("artifacts"),
        f"{REPORT_FILE}.artifacts",
    )
    if len(report_artifacts) != EXPECTED_ARTIFACTS:
        raise ValueError(
            f"Expected {EXPECTED_ARTIFACTS} {REPORT_FILE} artifact rows, "
            f"got {len(report_artifacts)}"
        )
    report_artifact_ids_in_order = tuple(
        require_text(
            require_object(artifact, f"{REPORT_FILE}.artifacts[]").get("artifactId"),
            f"{REPORT_FILE}.artifacts[].artifactId",
        )
        for artifact in report_artifacts
    )
    report_artifact_ids = tuple(sorted(report_artifact_ids_in_order))
    validate_selected_v0_release_artifact_scope(report_artifact_ids, REPORT_FILE)
    if report_artifact_ids != EXPECTED_ARTIFACT_IDS:
        raise ValueError(artifact_id_drift_message(REPORT_FILE, report_artifact_ids))
    if report_artifact_ids_in_order != EXPECTED_ARTIFACT_IDS:
        raise ValueError(
            artifact_order_drift_message(REPORT_FILE, report_artifact_ids_in_order)
        )
    for artifact in report_artifacts:
        item = require_object(artifact, f"{REPORT_FILE}.artifacts[]")
        artifact_id = require_text(
            item.get("artifactId"),
            f"{REPORT_FILE}.artifacts[].artifactId",
        )
        unexpected_error_fields = sorted(set(item).intersection({"code", "message"}))
        if unexpected_error_fields:
            raise ValueError(
                f"Passed {REPORT_FILE} artifact {artifact_id} "
                "must not carry error fields: "
                f"{unexpected_error_fields!r}"
            )
        reject_unexpected_keys(
            item,
            EXPECTED_PASSED_REPORT_ARTIFACT_KEYS,
            artifact_row_label(f"{REPORT_FILE}.artifacts", artifact_id),
        )
        expected_kind, _expected_path = EXPECTED_ARTIFACTS_BY_ID[artifact_id]
        artifact_kind = require_text(
            item.get("kind"),
            f"{REPORT_FILE}.artifacts[{artifact_id}].kind",
        )
        if artifact_kind != expected_kind:
            raise ValueError(
                f"Unexpected {REPORT_FILE} artifact kind for {artifact_id}: "
                f"expected {expected_kind!r}, got {artifact_kind!r}"
            )
        status = require_text(
            item.get("status"),
            f"{REPORT_FILE}.artifacts[{artifact_id}].status",
        )
        if status != "passed":
            raise ValueError(
                f"Expected passed {REPORT_FILE} artifact for {artifact_id}, got {status!r}"
            )

    return report


def main() -> int:
    if not BUNDLE_SCRIPT.is_file():
        return fail(f"missing bundle script: {BUNDLE_SCRIPT}")

    try:
        with tempfile.TemporaryDirectory(prefix="mojito-mf2-inflection-release-") as tmp:
            out_dir = Path(tmp)
            run_bundle(out_dir)
            validate_bundle_script_artifact_specs()
            report = validate_materialized_bundle(out_dir)
    except Exception as error:
        return fail(str(error))

    summary = require_object(report["summary"], f"{REPORT_FILE}.summary")
    print(
        "Inflection release fixture validation passed: "
        f"artifacts={summary['artifacts']} failed={summary['failed']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
