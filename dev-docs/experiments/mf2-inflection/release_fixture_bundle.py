#!/usr/bin/env python3
"""Materialize selected V0 MF2 inflection release fixture artifacts.

The inventory is fixed to checked Java/common V0 fixture slices. It is not
complete locale or grammar coverage and does not publish package-local runtime
APIs.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

from release_validation import (
    COMPILED_TERM_PACK_JSON,
    COMPILED_TERM_PACK_M2IF,
    HINDI_PRONOUN_AGREEMENT_PACK_JSON,
    MANIFEST_SCHEMA,
    validate_manifest,
)


ROOT = Path(__file__).resolve().parent
REPO_ROOT = ROOT.parents[2]
RESOURCE_ROOT = REPO_ROOT / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection"
EXPECTED_ARTIFACT_COUNT = 35


@dataclass(frozen=True)
class ArtifactSpec:
    artifact_id: str
    kind: str
    source: str
    path: str


ARTIFACTS = [
    ArtifactSpec(
        "ar-approved-json",
        COMPILED_TERM_PACK_JSON,
        "ar_compiled_approved_explicit_form_pack_fixture.json",
        "artifacts/ar-approved.json",
    ),
    ArtifactSpec(
        "ar-approved-m2if",
        COMPILED_TERM_PACK_M2IF,
        "ar_compiled_approved_explicit_form_pack_fixture.m2if.hex",
        "artifacts/ar-approved.m2if",
    ),
    ArtifactSpec(
        "ar-review-required-json",
        COMPILED_TERM_PACK_JSON,
        "ar_compiled_explicit_form_pack_fixture.json",
        "artifacts/ar-review-required.json",
    ),
    ArtifactSpec(
        "ar-review-required-m2if",
        COMPILED_TERM_PACK_M2IF,
        "ar_compiled_explicit_form_pack_fixture.m2if.hex",
        "artifacts/ar-review-required.m2if",
    ),
    ArtifactSpec(
        "da-genitive-definiteness-json",
        COMPILED_TERM_PACK_JSON,
        "da_compiled_genitive_definiteness_pack_fixture.json",
        "artifacts/da-genitive-definiteness.json",
    ),
    ArtifactSpec(
        "da-genitive-definiteness-m2if",
        COMPILED_TERM_PACK_M2IF,
        "da_compiled_genitive_definiteness_pack_fixture.m2if.hex",
        "artifacts/da-genitive-definiteness.m2if",
    ),
    ArtifactSpec(
        "de-article-case-json",
        COMPILED_TERM_PACK_JSON,
        "de_compiled_article_case_pack_fixture.json",
        "artifacts/de-article-case.json",
    ),
    ArtifactSpec(
        "de-article-case-m2if",
        COMPILED_TERM_PACK_M2IF,
        "de_compiled_article_case_pack_fixture.m2if.hex",
        "artifacts/de-article-case.m2if",
    ),
    ArtifactSpec(
        "es-article-json",
        COMPILED_TERM_PACK_JSON,
        "es_compiled_article_pack_fixture.json",
        "artifacts/es-article.json",
    ),
    ArtifactSpec(
        "es-article-m2if",
        COMPILED_TERM_PACK_M2IF,
        "es_compiled_article_pack_fixture.m2if.hex",
        "artifacts/es-article.m2if",
    ),
    ArtifactSpec(
        "he-construct-json",
        COMPILED_TERM_PACK_JSON,
        "he_compiled_construct_form_pack_fixture.json",
        "artifacts/he-construct.json",
    ),
    ArtifactSpec(
        "he-construct-m2if",
        COMPILED_TERM_PACK_M2IF,
        "he_compiled_construct_form_pack_fixture.m2if.hex",
        "artifacts/he-construct.m2if",
    ),
    ArtifactSpec(
        "hi-case-form-json",
        COMPILED_TERM_PACK_JSON,
        "hi_compiled_case_form_pack_fixture.json",
        "artifacts/hi-case-form.json",
    ),
    ArtifactSpec(
        "hi-case-form-m2if",
        COMPILED_TERM_PACK_M2IF,
        "hi_compiled_case_form_pack_fixture.m2if.hex",
        "artifacts/hi-case-form.m2if",
    ),
    ArtifactSpec(
        "hi-pronouns-json",
        HINDI_PRONOUN_AGREEMENT_PACK_JSON,
        "hi_pronoun_agreement_pack_fixture.json",
        "artifacts/hi-pronouns.json",
    ),
    ArtifactSpec(
        "it-article-json",
        COMPILED_TERM_PACK_JSON,
        "it_compiled_article_pack_fixture.json",
        "artifacts/it-article.json",
    ),
    ArtifactSpec(
        "it-article-m2if",
        COMPILED_TERM_PACK_M2IF,
        "it_compiled_article_pack_fixture.m2if.hex",
        "artifacts/it-article.m2if",
    ),
    ArtifactSpec(
        "ml-approved-case-form-json",
        COMPILED_TERM_PACK_JSON,
        "ml_compiled_approved_case_form_pack_fixture.json",
        "artifacts/ml-approved-case-form.json",
    ),
    ArtifactSpec(
        "ml-approved-case-form-m2if",
        COMPILED_TERM_PACK_M2IF,
        "ml_compiled_approved_case_form_pack_fixture.m2if.hex",
        "artifacts/ml-approved-case-form.m2if",
    ),
    ArtifactSpec(
        "ml-case-form-json",
        COMPILED_TERM_PACK_JSON,
        "ml_compiled_case_form_pack_fixture.json",
        "artifacts/ml-case-form.json",
    ),
    ArtifactSpec(
        "ml-case-form-m2if",
        COMPILED_TERM_PACK_M2IF,
        "ml_compiled_case_form_pack_fixture.m2if.hex",
        "artifacts/ml-case-form.m2if",
    ),
    ArtifactSpec(
        "pt-agreement-json",
        COMPILED_TERM_PACK_JSON,
        "pt_compiled_agreement_pack_fixture.json",
        "artifacts/pt-agreement.json",
    ),
    ArtifactSpec(
        "pt-agreement-m2if",
        COMPILED_TERM_PACK_M2IF,
        "pt_compiled_agreement_pack_fixture.m2if.hex",
        "artifacts/pt-agreement.m2if",
    ),
    ArtifactSpec(
        "ru-case-form-json",
        COMPILED_TERM_PACK_JSON,
        "ru_compiled_case_form_pack_fixture.json",
        "artifacts/ru-case-form.json",
    ),
    ArtifactSpec(
        "ru-case-form-m2if",
        COMPILED_TERM_PACK_M2IF,
        "ru_compiled_case_form_pack_fixture.m2if.hex",
        "artifacts/ru-case-form.m2if",
    ),
    ArtifactSpec(
        "sr-case-form-json",
        COMPILED_TERM_PACK_JSON,
        "sr_compiled_case_form_pack_fixture.json",
        "artifacts/sr-case-form.json",
    ),
    ArtifactSpec(
        "sr-case-form-m2if",
        COMPILED_TERM_PACK_M2IF,
        "sr_compiled_case_form_pack_fixture.m2if.hex",
        "artifacts/sr-case-form.m2if",
    ),
    ArtifactSpec(
        "sv-genitive-definiteness-json",
        COMPILED_TERM_PACK_JSON,
        "sv_compiled_genitive_definiteness_pack_fixture.json",
        "artifacts/sv-genitive-definiteness.json",
    ),
    ArtifactSpec(
        "sv-genitive-definiteness-m2if",
        COMPILED_TERM_PACK_M2IF,
        "sv_compiled_genitive_definiteness_pack_fixture.m2if.hex",
        "artifacts/sv-genitive-definiteness.m2if",
    ),
    ArtifactSpec(
        "tr-explicit-template-auto-json",
        COMPILED_TERM_PACK_JSON,
        "tr_compiled_explicit_template_auto_pack_fixture.json",
        "artifacts/tr-explicit-template-auto.json",
    ),
    ArtifactSpec(
        "tr-explicit-template-auto-m2if",
        COMPILED_TERM_PACK_M2IF,
        "tr_compiled_explicit_template_auto_pack_fixture.m2if.hex",
        "artifacts/tr-explicit-template-auto.m2if",
    ),
    ArtifactSpec(
        "tr-explicit-template-json",
        COMPILED_TERM_PACK_JSON,
        "tr_compiled_explicit_template_pack_fixture.json",
        "artifacts/tr-explicit-template.json",
    ),
    ArtifactSpec(
        "tr-explicit-template-m2if",
        COMPILED_TERM_PACK_M2IF,
        "tr_compiled_explicit_template_pack_fixture.m2if.hex",
        "artifacts/tr-explicit-template.m2if",
    ),
    ArtifactSpec(
        "tr-suffix-json",
        COMPILED_TERM_PACK_JSON,
        "tr_compiled_suffix_pack_fixture.json",
        "artifacts/tr-suffix.json",
    ),
    ArtifactSpec(
        "tr-suffix-m2if",
        COMPILED_TERM_PACK_M2IF,
        "tr_compiled_suffix_pack_fixture.m2if.hex",
        "artifacts/tr-suffix.m2if",
    ),
]


def require_relative_path(value: str, label: str) -> Path:
    path = Path(value)
    if not value or path.is_absolute() or ".." in path.parts:
        raise ValueError(f"{label} must be a relative path without parent traversal: {value}")
    return path


def artifact_source_path(artifact: ArtifactSpec) -> Path:
    source_path = require_relative_path(artifact.source, "Release fixture source path")
    if len(source_path.parts) != 1:
        raise ValueError(
            f"Release fixture source must name a checked fixture file: {artifact.source}"
        )
    resource_root = RESOURCE_ROOT.resolve()
    source = (RESOURCE_ROOT / source_path).resolve()
    if not source.is_relative_to(resource_root):
        raise ValueError(
            f"Release fixture source path must stay under resource root: {artifact.source}"
        )
    if not source.is_file():
        raise FileNotFoundError(f"Missing release fixture source: {source}")
    return source


def artifact_target_path(out_dir: Path, artifact: ArtifactSpec) -> Path:
    target_path = require_relative_path(artifact.path, "Release fixture target path")
    out_root = out_dir.resolve()
    target = out_dir / target_path
    target.parent.mkdir(parents=True, exist_ok=True)
    target_parent = target.parent.resolve()
    if not target_parent.is_relative_to(out_root):
        raise ValueError(
            f"Release fixture target path must stay under output directory: {artifact.path}"
        )
    resolved_target = target.resolve() if target.exists() else target_parent / target.name
    if not resolved_target.is_relative_to(out_root):
        raise ValueError(
            f"Release fixture target path must stay under output directory: {artifact.path}"
        )
    return resolved_target


def validate_artifact_specs() -> None:
    if len(ARTIFACTS) != EXPECTED_ARTIFACT_COUNT:
        raise ValueError(
            f"Expected {EXPECTED_ARTIFACT_COUNT} release artifacts, got {len(ARTIFACTS)}"
        )
    artifact_ids = [artifact.artifact_id for artifact in ARTIFACTS]
    if len(artifact_ids) != len(set(artifact_ids)):
        raise ValueError("Release fixture artifact IDs must be unique")
    if artifact_ids != sorted(artifact_ids):
        raise ValueError("Release fixture artifacts must be sorted by artifact ID")
    artifact_paths = [artifact.path for artifact in ARTIFACTS]
    if len(artifact_paths) != len(set(artifact_paths)):
        raise ValueError("Release fixture artifact paths must be unique")
    for artifact in ARTIFACTS:
        artifact_source_path(artifact)
        require_relative_path(artifact.path, "Release fixture target path")
        if artifact.kind == COMPILED_TERM_PACK_M2IF:
            if not artifact.source.endswith(".m2if.hex") or not artifact.path.endswith(
                ".m2if"
            ):
                raise ValueError(
                    f"Unexpected M2IF release fixture shape: {artifact.artifact_id}"
                )
        elif artifact.kind in {COMPILED_TERM_PACK_JSON, HINDI_PRONOUN_AGREEMENT_PACK_JSON}:
            if not artifact.source.endswith(".json") or not artifact.path.endswith(
                ".json"
            ):
                raise ValueError(
                    f"Unexpected JSON release fixture shape: {artifact.artifact_id}"
                )
        else:
            raise ValueError(f"Unexpected release artifact kind: {artifact.kind}")


def materialize_artifact(out_dir: Path, artifact: ArtifactSpec) -> None:
    source = artifact_source_path(artifact)
    target = artifact_target_path(out_dir, artifact)
    if artifact.source.endswith(".m2if.hex"):
        target.write_bytes(bytes.fromhex("".join(source.read_text(encoding="utf-8").split())))
    else:
        target.write_text(source.read_text(encoding="utf-8"), encoding="utf-8")


def write_manifest(out_dir: Path) -> Path:
    validate_artifact_specs()
    for artifact in ARTIFACTS:
        materialize_artifact(out_dir, artifact)
    manifest = {
        "schema": MANIFEST_SCHEMA,
        "artifacts": [
            {"artifactId": artifact.artifact_id, "kind": artifact.kind, "path": artifact.path}
            for artifact in ARTIFACTS
        ],
    }
    manifest_path = out_dir / "release-validation-manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return manifest_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out-dir", required=True, type=Path)
    parser.add_argument(
        "--validate",
        action="store_true",
        help="Run release_validation.py-compatible validation after materializing the bundle.",
    )
    args = parser.parse_args()

    args.out_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = write_manifest(args.out_dir)
    result = {"artifacts": len(ARTIFACTS), "manifest": str(manifest_path)}

    if args.validate:
        report = validate_manifest(manifest_path, args.out_dir)
        report_path = args.out_dir / "release-validation-report.json"
        report_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        result["report"] = str(report_path)
        result["failed"] = report["summary"]["failed"]

    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 1 if result.get("failed") else 0


if __name__ == "__main__":
    raise SystemExit(main())
