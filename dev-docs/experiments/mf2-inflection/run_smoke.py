#!/usr/bin/env python3
"""Run the MF2 inflection experiment smoke pipeline."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
CATALOG = ROOT / "fr_term_usage_example.json"
TERM_PACK = ROOT / "fr_term_pack_example.json"
GERMAN_CATALOG = ROOT / "de_term_usage_example.json"
GERMAN_TERM_PACK = ROOT / "de_term_pack_example.json"
SPANISH_CATALOG = ROOT / "es_term_usage_example.json"
SPANISH_TERM_PACK = ROOT / "es_term_pack_example.json"
ITALIAN_CATALOG = ROOT / "it_term_usage_example.json"
ITALIAN_TERM_PACK = ROOT / "it_term_pack_example.json"
PORTUGUESE_CATALOG = ROOT / "pt_term_usage_example.json"
PORTUGUESE_TERM_PACK = ROOT / "pt_term_pack_example.json"
TURKISH_CATALOG = ROOT / "tr_term_usage_example.json"
TURKISH_TERM_PACK = ROOT / "tr_term_pack_example.json"
SPANISH_COMPILED_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/es_compiled_article_pack_fixture.json"
)
ITALIAN_COMPILED_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/it_compiled_article_pack_fixture.json"
)
PORTUGUESE_COMPILED_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/pt_compiled_agreement_pack_fixture.json"
)
TURKISH_COMPILED_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/tr_compiled_suffix_pack_fixture.json"
)
RUSSIAN_CASE_FORM_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ru_case_form_pack_fixture.json"
)
RUSSIAN_CASE_AUDIT_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ru_case_pack_audit_fixture.json"
)
RUSSIAN_COMPILED_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ru_compiled_case_form_pack_fixture.json"
)
TURKISH_SUFFIX_SURVEY_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/tr_suffix_pack_survey_fixture.json"
)
TURKISH_EXPLICIT_TEMPLATE_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/tr_compiled_explicit_template_pack_fixture.json"
)
TURKISH_EXPLICIT_TEMPLATE_AUTO_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/tr_compiled_explicit_template_auto_pack_fixture.json"
)
HINDI_PACK_SURVEY_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/hi_pack_survey_fixture.json"
)
HINDI_COMPILED_CASE_FORM_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/hi_compiled_case_form_pack_fixture.json"
)
HINDI_PRONOUN_AGREEMENT_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/hi_pronoun_agreement_pack_fixture.json"
)
ARABIC_COMPILED_EXPLICIT_FORM_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ar_compiled_explicit_form_pack_fixture.json"
)
ARABIC_COMPILED_APPROVED_EXPLICIT_FORM_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ar_compiled_approved_explicit_form_pack_fixture.json"
)
ARABIC_PACK_AUDIT_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ar_pack_audit_fixture.json"
)
HEBREW_PACK_AUDIT_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/he_pack_audit_fixture.json"
)
MALAYALAM_PACK_AUDIT_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ml_pack_audit_fixture.json"
)
MALAYALAM_COMPILED_CASE_FORM_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ml_compiled_case_form_pack_fixture.json"
)
MALAYALAM_COMPILED_APPROVED_CASE_FORM_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ml_compiled_approved_case_form_pack_fixture.json"
)
GERMANIC_NORDIC_PACK_AUDIT_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/germanic_nordic_pack_audit_fixture.json"
)
SWEDISH_COMPILED_GENITIVE_DEFINITENESS_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/sv_compiled_genitive_definiteness_pack_fixture.json"
)
GERMAN_ARTICLE_CASE_REPORT_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/de_article_case_report_fixture.json"
)
DANISH_COMPILED_GENITIVE_DEFINITENESS_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/da_compiled_genitive_definiteness_pack_fixture.json"
)
NORWEGIAN_BOKMAL_NOUN_METADATA_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/nb_noun_metadata_pack_fixture.json"
)
DUTCH_NOUN_METADATA_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/nl_noun_metadata_pack_fixture.json"
)
LOW_INFLECTION_LOCALE_AUDIT_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/low_inflection_locale_audit_fixture.json"
)
PRONOUN_PROFILE_PACK_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/pronoun_profile_pack_fixture.json"
)
LOCALE_DATA_SURVEY_FIXTURE = ROOT / "locale_data_survey_fixture.json"
TERM_USAGE_OPTION_CONTRACT_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/term_usage_option_contract_fixture.json"
)
SPANISH_NOUN_PACK_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/es_noun_pack_report_fixture.json"
)
ITALIAN_NOUN_PACK_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/it_noun_pack_report_fixture.json"
)
PORTUGUESE_NOUN_PACK_FIXTURE = (
    ROOT.parents[2]
    / "common/src/test/resources/com/box/l10n/mojito/mf2/inflection/pt_noun_pack_report_fixture.json"
)
SUPPLEMENTAL_DICTIONARY = Path(
    "/Users/ja/code/inflection/inflection/resources/org/unicode/inflection/dictionary/supplemental_fr.lst"
)
SERBIAN_DICTIONARY = Path(
    "/Users/ja/code/inflection/inflection/resources/org/unicode/inflection/dictionary/dictionary_sr.lst"
)
SERBIAN_INFLECTIONAL = Path(
    "/Users/ja/code/inflection/inflection/resources/org/unicode/inflection/dictionary/inflectional_sr.xml"
)
GERMAN_DICTIONARY = Path("/Users/ja/.cache/mf2-inflection-data/dictionary_de.lst")
GERMAN_INFLECTIONAL = Path("/Users/ja/.cache/mf2-inflection-data/inflectional_de.xml")
SPANISH_DICTIONARY = Path("/Users/ja/.cache/mf2-inflection-data/dictionary_es.lst")
SPANISH_INFLECTIONAL = Path("/Users/ja/.cache/mf2-inflection-data/inflectional_es.xml")
ITALIAN_DICTIONARY = Path("/Users/ja/.cache/mf2-inflection-data/dictionary_it.lst")
ITALIAN_INFLECTIONAL = Path("/Users/ja/.cache/mf2-inflection-data/inflectional_it.xml")
PORTUGUESE_DICTIONARY = Path("/Users/ja/.cache/mf2-inflection-data/dictionary_pt.lst")
PORTUGUESE_INFLECTIONAL = Path("/Users/ja/.cache/mf2-inflection-data/inflectional_pt.xml")
RUSSIAN_DICTIONARY = Path("/Users/ja/.cache/mf2-inflection-data/dictionary_ru.lst")
RUSSIAN_INFLECTIONAL = Path("/Users/ja/.cache/mf2-inflection-data/inflectional_ru.xml")
TURKISH_DICTIONARY = Path("/Users/ja/.cache/mf2-inflection-data/dictionary_tr.lst")
TURKISH_INFLECTIONAL = Path("/Users/ja/.cache/mf2-inflection-data/inflectional_tr.xml")
TURKISH_SUPPLEMENTAL = Path(
    "/Users/ja/code/inflection/inflection/resources/org/unicode/inflection/dictionary/supplemental_tr.lst"
)
HINDI_DICTIONARY = Path("/Users/ja/.cache/mf2-inflection-data/dictionary_hi.lst")
HINDI_INFLECTIONAL = Path("/Users/ja/.cache/mf2-inflection-data/inflectional_hi.xml")
HINDI_PRONOUNS = Path(
    "/Users/ja/code/inflection/inflection/resources/org/unicode/inflection/inflection/pronoun_hi.csv"
)
ARABIC_DICTIONARY = Path("/Users/ja/.cache/mf2-inflection-data/dictionary_ar.lst")
ARABIC_INFLECTIONAL = Path("/Users/ja/.cache/mf2-inflection-data/inflectional_ar.xml")
ARABIC_PRONOUNS = Path(
    "/Users/ja/code/inflection/inflection/resources/org/unicode/inflection/inflection/pronoun_ar.csv"
)
HEBREW_DICTIONARY = Path("/Users/ja/.cache/mf2-inflection-data/dictionary_he.lst")
HEBREW_INFLECTIONAL = Path("/Users/ja/.cache/mf2-inflection-data/inflectional_he.xml")
HEBREW_PRONOUNS = Path(
    "/Users/ja/code/inflection/inflection/resources/org/unicode/inflection/inflection/pronoun_he.csv"
)
MALAYALAM_DICTIONARY = Path("/Users/ja/.cache/mf2-inflection-data/dictionary_ml.lst")
MALAYALAM_INFLECTIONAL = Path("/Users/ja/.cache/mf2-inflection-data/inflectional_ml.xml")
MALAYALAM_PRONOUNS = Path(
    "/Users/ja/code/inflection/inflection/resources/org/unicode/inflection/inflection/pronoun_ml.csv"
)
SWEDISH_DICTIONARY = Path("/Users/ja/.cache/mf2-inflection-data/dictionary_sv.lst")
DANISH_DICTIONARY = Path("/Users/ja/.cache/mf2-inflection-data/dictionary_da.lst")
NORWEGIAN_BOKMAL_DICTIONARY = Path("/Users/ja/.cache/mf2-inflection-data/dictionary_nb.lst")
DUTCH_DICTIONARY = Path("/Users/ja/.cache/mf2-inflection-data/dictionary_nl.lst")


def run(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, check=True, cwd=ROOT.parents[2], text=True, capture_output=True)


def render(
    compiled_pack: Path,
    message_id: str,
    term_id: str,
    count: str | None = None,
    catalog: Path = CATALOG,
) -> str:
    args = [
        sys.executable,
        str(ROOT / "mf2_term_pack_render.py"),
        "--catalog",
        str(catalog),
        "--compiled-pack",
        str(compiled_pack),
        "--message",
        message_id,
        "--term",
        f"item={term_id}",
    ]
    if count is not None:
        args.extend(["--arg", f"count={count}"])
    return run(args).stdout.strip()


def assert_equal(actual: str, expected: str) -> None:
    if actual != expected:
        raise AssertionError(f"Expected {expected!r}, got {actual!r}")


def assert_command_fails(args: list[str], expected_stderr: str) -> None:
    result = subprocess.run(
        args,
        cwd=ROOT.parents[2],
        text=True,
        capture_output=True,
    )
    if result.returncode == 0:
        raise AssertionError(f"Expected command to fail: {args!r}")
    if expected_stderr not in result.stderr:
        raise AssertionError(result.stderr)


def assert_invalid_term_option_contract(tmp_path: Path) -> None:
    cases = [
        ("{$item :term role=source}.", "Unsupported term option: role"),
        ("{$item :term article=partitive}.", "Unsupported article option: partitive"),
        ("{$item :term case=ergative}.", "Unsupported case option: ergative"),
        ("{$item :term preposition=sobre article=definite}.", "Unsupported preposition option: sobre"),
        ("{$item :term preposition=de}.", "Preposition option requires article option"),
        ("{$item :term preposition=de article=definite case=dative}.", "Preposition option cannot be combined with case option"),
        ("{$item :term preposition=por article=indefinite}.", "Unsupported preposition/article combination: por + indefinite"),
        ("{$item :term count=2}.", "Count option must reference a variable: 2"),
        ("{$item :term definiteness=absolute}.", "Unsupported definiteness option: absolute"),
        ("{$item :term number=paucal}.", "Unsupported number option: paucal"),
        ("{$item :term definiteness=construct article=definite}.", "Definiteness option cannot be combined with article option"),
        ("{$item :term number=dual count=$count}.", "Number option cannot be combined with count option"),
        ("{$item :term article=}.", "Term option value must not be blank: article"),
        ("{$item :term article=definite article=indefinite}.", "Duplicate term option: article"),
    ]

    for index, (message, expected_stderr) in enumerate(cases):
        catalog = tmp_path / f"fr-invalid-term-option-{index}.json"
        catalog.write_text(
            json.dumps(
                {
                    "locale": "fr",
                    "messages": {"inventory.invalid": message},
                    "argumentTerms": {"inventory.invalid": {"item": ["item.iron_sword"]}},
                }
            ),
            encoding="utf-8",
        )
        assert_command_fails(
            [
                sys.executable,
                str(ROOT / "mf2_term_requirements.py"),
                "--catalog",
                str(catalog),
                "--term-pack",
                str(TERM_PACK),
            ],
            expected_stderr,
        )


def assert_term_usage_option_contract() -> None:
    import mf2_term_requirements

    payload = json.loads(TERM_USAGE_OPTION_CONTRACT_FIXTURE.read_text(encoding="utf-8"))
    assert_equal(payload["schema"], "mojito-mf2-inflection/term-usage-option-contract/v0")
    assert_equal(payload["options"], sorted(mf2_term_requirements.SUPPORTED_OPTIONS))
    assert_equal(payload["articles"], sorted(mf2_term_requirements.SUPPORTED_ARTICLES))
    assert_equal(payload["cases"], sorted(mf2_term_requirements.SUPPORTED_CASES))
    assert_equal(payload["definiteness"], sorted(mf2_term_requirements.SUPPORTED_DEFINITENESS))
    assert_equal(payload["numbers"], sorted(mf2_term_requirements.SUPPORTED_NUMBERS))
    assert_equal(payload["prepositions"], sorted(mf2_term_requirements.SUPPORTED_PREPOSITIONS))

    combinations = payload["prepositionArticleCombinations"]
    for article in payload["articles"]:
        assert_equal(
            mf2_term_requirements.is_portuguese_preposition_article_combination(None, article),
            article in combinations["articleOnly"],
        )
    for preposition in payload["prepositions"]:
        for article in payload["articles"]:
            assert_equal(
                mf2_term_requirements.is_portuguese_preposition_article_combination(
                    preposition, article
                ),
                article in combinations[preposition],
            )


def assert_closed_world_unbound_term_shape(tmp_path: Path) -> None:
    catalog = tmp_path / "fr-unbound-term-usage.json"
    closed_report = tmp_path / "fr-unbound-closed-world-report.json"
    open_report = tmp_path / "fr-unbound-open-world-report.json"
    catalog.write_text(
        json.dumps(
            {
                "locale": "fr",
                "messages": {"inventory.deleted": "{$item :term article=definite}."},
                "argumentTerms": {},
            }
        ),
        encoding="utf-8",
    )

    run(
        [
            sys.executable,
            str(ROOT / "mf2_term_requirements.py"),
            "--catalog",
            str(catalog),
            "--term-pack",
            str(TERM_PACK),
            "--out",
            str(closed_report),
        ]
    )
    closed_payload = json.loads(closed_report.read_text(encoding="utf-8"))
    assert_equal(closed_payload["diagnostics"][0]["missing"][0], "missing-argument-terms")
    assert_equal(closed_payload["diagnostics"][0]["span"], [0, 30])
    if closed_payload["diagnostics"][0]["termId"] is not None:
        raise AssertionError("Expected closed-world unbound term diagnostic termId to be null")

    run(
        [
            sys.executable,
            str(ROOT / "mf2_term_requirements.py"),
            "--catalog",
            str(catalog),
            "--term-pack",
            str(TERM_PACK),
            "--mode",
            "open-world",
            "--out",
            str(open_report),
        ]
    )
    open_payload = json.loads(open_report.read_text(encoding="utf-8"))
    assert_equal(open_payload["diagnostics"], [])


def assert_compiled_pack_with_diagnostics_fails(tmp_path: Path) -> None:
    catalog = tmp_path / "fr-diagnostic-render-catalog.json"
    compiled_pack = tmp_path / "fr-diagnostic-compiled-pack.json"
    catalog.write_text(
        json.dumps(
            {
                "messages": {
                    "inventory.deleted": "{$item :term article=definite}."
                }
            }
        ),
        encoding="utf-8",
    )
    compiled_pack.write_text(
        json.dumps(
            {
                "strings": ["definite.singular", "le livre", "concept.book"],
                "formSets": [
                    {
                        "term": 2,
                        "forms": [
                            {
                                "key": 0,
                                "value": 1,
                                "kind": "literal",
                            }
                        ],
                    }
                ],
                "diagnostics": [
                    {
                        "termId": "concept.book",
                        "code": "missing-form",
                        "form": "definite.plural",
                    }
                ],
            }
        ),
        encoding="utf-8",
    )

    assert_command_fails(
        [
            sys.executable,
            str(ROOT / "mf2_term_pack_render.py"),
            "--catalog",
            str(catalog),
            "--compiled-pack",
            str(compiled_pack),
            "--message",
            "inventory.deleted",
            "--term",
            "item=concept.book",
        ],
        "Compiled term pack contains diagnostics",
    )


def assert_missing_render_count_fails(compiled_pack: Path) -> None:
    assert_command_fails(
        [
            sys.executable,
            str(ROOT / "mf2_term_pack_render.py"),
            "--catalog",
            str(CATALOG),
            "--compiled-pack",
            str(compiled_pack),
            "--message",
            "inventory.deleted",
            "--term",
            "item=item.iron_sword",
        ],
        "Missing count variable: count",
    )


def assert_serbian_case_pack_report(
    report_path: Path,
    case_form_pack_path: Path,
    compiled_case_form_pack_path: Path,
) -> None:
    payload = json.loads(report_path.read_text(encoding="utf-8"))
    assert_equal(payload["locale"], "sr")
    if payload["counts"]["supportedEntries"] <= 0:
        raise AssertionError("Expected Serbian report to include supported noun entries")
    if payload["counts"]["missingInflectionPatterns"] != 0:
        raise AssertionError(payload["samples"]["missingInflectionPatterns"])
    if payload["features"]["case"]["nominative"] <= 0:
        raise AssertionError("Expected Serbian report to include nominative case forms")
    if payload["sizeEstimates"]["simpleCasePackBytes"] <= 0:
        raise AssertionError("Expected Serbian report to include a non-empty size estimate")
    review_policy = payload["reviewPolicy"]
    assert_equal(review_policy["runtimeExport"], "closed-world-case-forms")
    assert_equal(
        review_policy["automaticExportTerms"] + review_policy["blockedDictionaryEntries"],
        payload["counts"]["dictionaryEntries"],
    )
    assert_equal(review_policy["automaticExportTerms"], 159)
    assert_equal(review_policy["reviewRequiredSurfaces"], payload["counts"]["ambiguousSupportedSurfaces"])
    assert_equal(review_policy["blockedDictionaryEntries"], 981)
    assert_equal(review_policy["blockedReasons"]["not-nominative"], 755)
    assert_equal(review_policy["reviewRequiredReasons"], payload["features"]["ambiguityReasons"])

    case_form_payload = json.loads(case_form_pack_path.read_text(encoding="utf-8"))
    assert_equal(case_form_payload["locale"], "sr")
    if case_form_payload["summary"]["candidateTerms"] <= 0:
        raise AssertionError("Expected Serbian case-form pack to include candidate terms")
    if case_form_payload["summary"]["exportedTerms"] <= 0:
        raise AssertionError("Expected Serbian case-form pack to export terms")
    if case_form_payload["summary"]["formRows"] <= 0:
        raise AssertionError("Expected Serbian case-form pack to include form rows")
    strings = case_form_payload["strings"]
    exported_term_ids = {strings[term["id"]] for term in case_form_payload["terms"]}
    if "sr.case.mačka" not in exported_term_ids:
        raise AssertionError("Expected Serbian case-form pack to export mačka")

    compiled_payload = json.loads(compiled_case_form_pack_path.read_text(encoding="utf-8"))
    assert_equal(compiled_payload["locale"], "sr")
    assert_equal(compiled_payload["schema"], "mojito-mf2-inflection/compiled-term-pack/v0")
    if compiled_payload["diagnostics"]:
        raise AssertionError("Expected Serbian compiled case-form pack to be renderable")
    if len(compiled_payload["terms"]) != case_form_payload["summary"]["exportedTerms"]:
        raise AssertionError("Compiled Serbian term count does not match generated case-form pack")
    if len(compiled_payload["formSets"]) != case_form_payload["summary"]["exportedTerms"]:
        raise AssertionError("Compiled Serbian form-set count does not match generated case-form pack")


def assert_german_article_case_report(report_path: Path) -> None:
    payload = json.loads(report_path.read_text(encoding="utf-8"))
    fixture_payload = json.loads(GERMAN_ARTICLE_CASE_REPORT_FIXTURE.read_text(encoding="utf-8"))
    if payload != fixture_payload:
        raise AssertionError("Generated German article/case report does not match checked-in fixture")

    assert_equal(payload["locale"], "de")
    assert_equal(payload["schema"], "mojito-mf2-inflection/de-article-case-pack-report/v0")
    if payload["counts"]["supportedEntries"] <= 0:
        raise AssertionError("Expected German report to include supported noun entries")
    if payload["counts"]["missingInflectionPatterns"] != 0:
        raise AssertionError(payload["samples"]["missingInflectionPatterns"])
    if payload["counts"]["articleCaseCandidateTerms"] <= 0:
        raise AssertionError("Expected German report to include article/case candidate terms")
    if payload["features"]["case"]["dative"] <= 0:
        raise AssertionError("Expected German report to include dative case forms")
    if payload["features"]["gender"]["neuter"] <= 0:
        raise AssertionError("Expected German report to include neuter nouns")
    if payload["sizeEstimates"]["binaryLowerBoundBytes"] <= 0:
        raise AssertionError("Expected German report to include a non-empty size estimate")
    review_policy = payload["reviewPolicy"]
    assert_equal(review_policy["runtimeExport"], "closed-world-article-case-forms")
    assert_equal(review_policy["automaticExportTerms"], payload["counts"]["articleCaseCandidateTerms"])
    assert_equal(review_policy["reviewRequiredSurfaces"], payload["counts"]["ambiguousSupportedSurfaces"])
    assert_equal(
        review_policy["blockedDictionaryEntries"] + review_policy["automaticExportTerms"],
        payload["counts"]["dictionaryEntries"],
    )
    assert_equal(review_policy["reviewRequiredReasons"], payload["features"]["ambiguityReasons"])
    assert_equal(review_policy["blockedReasons"], payload["features"]["candidateSkipReasons"])
    assert_equal(review_policy["automaticExportTerms"], 41363)
    assert_equal(review_policy["reviewRequiredSurfaces"], 104487)
    assert_equal(review_policy["blockedDictionaryEntries"], 165056)
    assert_equal(review_policy["reviewRequiredReasons"]["multiple-cases"], 104251)
    assert_equal(review_policy["blockedReasons"]["not-nominative"], 55676)

    article_forms = {
        (row["article"], row["gender"], row["number"], row["case"]): row["form"]
        for row in payload["articleForms"]
    }
    assert_equal(article_forms[("definite", "masculine", "singular", "accusative")], "den")
    assert_equal(article_forms[("indefinite", "*", "plural", "nominative")], "")
    first_sample = payload["samples"]["articleCaseCandidates"][0]
    if "definite.accusative.singular" not in first_sample["phraseForms"]:
        raise AssertionError("Expected German article/case sample to include accusative phrase form")


def assert_spanish_noun_pack_report(report_path: Path) -> None:
    payload = json.loads(report_path.read_text(encoding="utf-8"))
    fixture_payload = json.loads(SPANISH_NOUN_PACK_FIXTURE.read_text(encoding="utf-8"))
    if payload != fixture_payload:
        raise AssertionError("Generated Spanish noun-pack report does not match checked-in fixture")

    assert_equal(payload["locale"], "es")
    assert_equal(payload["schema"], "mojito-mf2-inflection/es-noun-pack-report/v0")
    if payload["counts"]["supportedEntries"] <= 0:
        raise AssertionError("Expected Spanish report to include supported noun entries")
    if payload["counts"]["missingInflectionPatterns"] != 0:
        raise AssertionError(payload["samples"]["missingInflectionPatterns"])
    if payload["counts"]["genderNumberCandidateSurfaces"] <= 0:
        raise AssertionError("Expected Spanish report to include gender/number candidates")
    if payload["features"]["gender"]["masculine"] <= 0:
        raise AssertionError("Expected Spanish report to include masculine nouns")
    if payload["features"]["gender"]["feminine"] <= 0:
        raise AssertionError("Expected Spanish report to include feminine nouns")
    if payload["features"]["patternPartOfSpeech"]["noun"] <= 0:
        raise AssertionError("Expected Spanish report to include noun patterns")

    estimate = payload["sizeEstimates"]["genderNumberMetadataPack"]
    if estimate["rowBytes"] != payload["counts"]["genderNumberCandidateSurfaces"] * 8:
        raise AssertionError("Spanish row-byte estimate does not match candidates")
    if estimate["binaryLowerBoundBytes"] != estimate["stringPoolBytes"] + estimate["rowBytes"]:
        raise AssertionError("Spanish binary estimate does not match parts")

    article_strategy = payload["articleStrategy"]
    if article_strategy["counts"]["articleCandidateSurfaces"] != payload["counts"]["genderNumberCandidateSurfaces"]:
        raise AssertionError("Spanish article candidates must match gender/number candidates")
    if article_strategy["counts"]["stressedFeminineSingularOverrides"] <= 0:
        raise AssertionError("Expected Spanish report to include stressed feminine article overrides")
    article_estimate = article_strategy["sizeEstimates"]["eagerPhrasePack"]
    if article_estimate["phraseRows"] != article_strategy["counts"]["articleCandidateSurfaces"] * 2:
        raise AssertionError("Spanish article phrase-row count does not match articles")
    if article_estimate["binaryLowerBoundBytes"] != article_estimate["stringPoolBytes"] + article_estimate["phraseRowBytes"]:
        raise AssertionError("Spanish article binary estimate does not match parts")
    review_policy = payload["reviewPolicy"]
    assert_equal(review_policy["compactRuntime"], "article-shell-composition")
    assert_equal(review_policy["automaticExportSurfaces"], payload["counts"]["exactGenderNumberSurfaces"])
    assert_equal(
        review_policy["automaticExportSurfaces"] + review_policy["reviewRequiredSurfaces"],
        payload["counts"]["genderNumberCandidateSurfaces"],
    )
    assert_equal(
        review_policy["blockedSurfaces"],
        payload["counts"]["uniqueSupportedSurfaces"] - payload["counts"]["genderNumberCandidateSurfaces"],
    )
    assert_equal(review_policy["automaticExportSurfaces"], 52190)
    assert_equal(review_policy["reviewRequiredSurfaces"], 11508)
    assert_equal(review_policy["blockedSurfaces"], 5545)
    assert_equal(review_policy["reviewRequiredReasons"]["multiple-inflections"], 11442)
    assert_equal(review_policy["blockedReasons"]["multiple-genders"], 3276)
    override_phrases = {
        row["surface"]: row["phraseForms"]
        for row in article_strategy["samples"]["stressedFeminineSingularOverrides"]
    }
    if override_phrases.get("agua", {}).get("definite") != "el agua":
        raise AssertionError("Expected Spanish article override sample for agua")


def assert_italian_noun_pack_report(report_path: Path) -> None:
    payload = json.loads(report_path.read_text(encoding="utf-8"))
    fixture_payload = json.loads(ITALIAN_NOUN_PACK_FIXTURE.read_text(encoding="utf-8"))
    if payload != fixture_payload:
        raise AssertionError("Generated Italian noun-pack report does not match checked-in fixture")

    assert_equal(payload["locale"], "it")
    assert_equal(payload["schema"], "mojito-mf2-inflection/it-noun-pack-report/v0")
    assert_equal(payload["counts"]["dictionaryEntries"], 412265)
    assert_equal(payload["counts"]["supportedEntries"], 63567)
    assert_equal(payload["counts"]["genderNumberCandidateSurfaces"], 52542)
    if payload["counts"]["missingInflectionPatterns"] != 0:
        raise AssertionError(payload["samples"]["missingInflectionPatterns"])

    estimate = payload["sizeEstimates"]["genderNumberMetadataPack"]
    if estimate["rowBytes"] != payload["counts"]["genderNumberCandidateSurfaces"] * 8:
        raise AssertionError("Italian row-byte estimate does not match candidates")
    if estimate["binaryLowerBoundBytes"] != estimate["stringPoolBytes"] + estimate["rowBytes"]:
        raise AssertionError("Italian binary estimate does not match parts")

    article_strategy = payload["articleStrategy"]
    if article_strategy["counts"]["articleCandidateSurfaces"] != payload["counts"]["genderNumberCandidateSurfaces"]:
        raise AssertionError("Italian article candidates must match gender/number candidates")
    if article_strategy["articleClassCounts"]["lo"] <= 0:
        raise AssertionError("Expected Italian report to include lo/gli article-class candidates")
    if article_strategy["articleClassCounts"]["elision"] <= 0:
        raise AssertionError("Expected Italian report to include elision article-class candidates")
    article_estimate = article_strategy["sizeEstimates"]["eagerPhrasePack"]
    if article_estimate["phraseRows"] != article_strategy["counts"]["articleCandidateSurfaces"] * 2:
        raise AssertionError("Italian article phrase-row count does not match articles")
    if article_estimate["binaryLowerBoundBytes"] != article_estimate["stringPoolBytes"] + article_estimate["phraseRowBytes"]:
        raise AssertionError("Italian article binary estimate does not match parts")
    review_policy = payload["reviewPolicy"]
    assert_equal(review_policy["compactRuntime"], "article-shell-composition")
    assert_equal(review_policy["automaticExportSurfaces"], payload["counts"]["exactGenderNumberSurfaces"])
    assert_equal(
        review_policy["automaticExportSurfaces"] + review_policy["reviewRequiredSurfaces"],
        payload["counts"]["genderNumberCandidateSurfaces"],
    )
    assert_equal(
        review_policy["blockedSurfaces"],
        payload["counts"]["uniqueSupportedSurfaces"] - payload["counts"]["genderNumberCandidateSurfaces"],
    )
    assert_equal(review_policy["automaticExportSurfaces"], 45823)
    assert_equal(review_policy["reviewRequiredSurfaces"], 6719)
    assert_equal(review_policy["blockedSurfaces"], 10987)
    assert_equal(review_policy["reviewRequiredReasons"]["multiple-inflections"], 6671)
    assert_equal(review_policy["blockedReasons"]["multiple-numbers"], 8227)

    targeted = {
        row["surface"]: row["phraseForms"]
        for row in article_strategy["samples"]["targetedArticleCandidates"]
    }
    expected_phrases = {
        "gnomo": {"definite": "lo gnomo", "indefinite": "uno gnomo"},
        "gnomi": {"definite": "gli gnomi", "indefinite": "degli gnomi"},
        "libro": {"definite": "il libro", "indefinite": "un libro"},
        "cani": {"definite": "i cani", "indefinite": "dei cani"},
        "acqua": {"definite": "l'acqua", "indefinite": "un'acqua"},
        "ape": {"definite": "l'ape", "indefinite": "un'ape"},
    }
    if targeted != expected_phrases:
        raise AssertionError(targeted)


def assert_portuguese_noun_pack_report(report_path: Path) -> None:
    payload = json.loads(report_path.read_text(encoding="utf-8"))
    fixture_payload = json.loads(PORTUGUESE_NOUN_PACK_FIXTURE.read_text(encoding="utf-8"))
    if payload != fixture_payload:
        raise AssertionError("Generated Portuguese noun-pack report does not match checked-in fixture")

    assert_equal(payload["locale"], "pt")
    assert_equal(payload["schema"], "mojito-mf2-inflection/pt-noun-pack-report/v0")
    assert_equal(payload["counts"]["dictionaryEntries"], 35188)
    assert_equal(payload["counts"]["supportedEntries"], 6628)
    assert_equal(payload["counts"]["genderNumberCandidateSurfaces"], 6424)
    if payload["counts"]["missingInflectionPatterns"] != 0:
        raise AssertionError(payload["samples"]["missingInflectionPatterns"])

    review_policy = payload["reviewPolicy"]
    assert_equal(review_policy["compactRuntime"], "agreement-shell-composition")
    assert_equal(review_policy["automaticExportSurfaces"], payload["counts"]["exactGenderNumberSurfaces"])
    assert_equal(
        review_policy["automaticExportSurfaces"] + review_policy["reviewRequiredSurfaces"],
        payload["counts"]["genderNumberCandidateSurfaces"],
    )
    assert_equal(
        review_policy["blockedSurfaces"],
        payload["counts"]["uniqueSupportedSurfaces"] - payload["counts"]["genderNumberCandidateSurfaces"],
    )
    assert_equal(review_policy["automaticExportSurfaces"], 6116)
    assert_equal(review_policy["reviewRequiredSurfaces"], 308)
    assert_equal(review_policy["blockedSurfaces"], 190)
    assert_equal(review_policy["reviewRequiredReasons"]["multiple-inflections"], 273)
    assert_equal(review_policy["blockedReasons"]["multiple-genders"], 86)

    estimate = payload["sizeEstimates"]["genderNumberMetadataPack"]
    if estimate["rowBytes"] != payload["counts"]["genderNumberCandidateSurfaces"] * 8:
        raise AssertionError("Portuguese row-byte estimate does not match candidates")
    if estimate["binaryLowerBoundBytes"] != estimate["stringPoolBytes"] + estimate["rowBytes"]:
        raise AssertionError("Portuguese binary estimate does not match parts")

    agreement_strategy = payload["agreementStrategy"]
    if agreement_strategy["counts"]["agreementCandidateSurfaces"] != payload["counts"]["genderNumberCandidateSurfaces"]:
        raise AssertionError("Portuguese agreement candidates must match gender/number candidates")
    if agreement_strategy["counts"]["agreementFormCategories"] != 16:
        raise AssertionError("Expected Portuguese report to include sixteen agreement form categories")
    agreement_estimate = agreement_strategy["sizeEstimates"]["eagerPhrasePack"]
    expected_phrase_rows = (
        agreement_strategy["counts"]["agreementCandidateSurfaces"]
        * agreement_strategy["counts"]["agreementFormCategories"]
    )
    if agreement_estimate["phraseRows"] != expected_phrase_rows:
        raise AssertionError("Portuguese agreement phrase-row count does not match categories")
    if agreement_estimate["binaryLowerBoundBytes"] != agreement_estimate["stringPoolBytes"] + agreement_estimate["phraseRowBytes"]:
        raise AssertionError("Portuguese agreement binary estimate does not match parts")

    targeted = {
        row["surface"]: row["phraseForms"]
        for row in agreement_strategy["samples"]["targetedAgreementCandidates"]
    }
    expected_samples = {
        "campo": {
            "definiteArticle": "o campo",
            "indefiniteArticle": "um campo",
            "deDefiniteArticle": "do campo",
            "emDefiniteArticle": "no campo",
            "porDefiniteArticle": "pelo campo",
            "possessiveArticle": "seu campo",
        },
        "casa": {
            "definiteArticle": "a casa",
            "indefiniteArticle": "uma casa",
            "deDefiniteArticle": "da casa",
            "emDefiniteArticle": "na casa",
            "porDefiniteArticle": "pela casa",
            "possessiveArticle": "sua casa",
        },
        "campos": {
            "definiteArticle": "os campos",
            "indefiniteArticle": "uns campos",
            "deDefiniteArticle": "dos campos",
            "emDefiniteArticle": "nos campos",
            "porDefiniteArticle": "pelos campos",
            "possessiveArticle": "seus campos",
        },
        "casas": {
            "definiteArticle": "as casas",
            "indefiniteArticle": "umas casas",
            "deDefiniteArticle": "das casas",
            "emDefiniteArticle": "nas casas",
            "porDefiniteArticle": "pelas casas",
            "possessiveArticle": "suas casas",
        },
    }
    for surface, phrase_forms in expected_samples.items():
        for category, expected in phrase_forms.items():
            assert_equal(targeted[surface][category], expected)


def assert_serbian_case_rendering(tmp_path: Path) -> None:
    catalog = tmp_path / "sr-term-usage-example.json"
    term_pack = tmp_path / "sr-term-pack-example.json"
    requirements_report = tmp_path / "sr-term-requirements-report.json"
    compiled_pack = tmp_path / "sr-compiled-term-pack.json"

    catalog.write_text(
        json.dumps(
            {
                "locale": "sr",
                "messages": {
                    "inventory.deleted": "Obrisano je {$item :term case=accusative count=$count}.",
                    "inventory.gifted": "Daj {$item :term case=dative}.",
                },
                "argumentTerms": {
                    "inventory.deleted": {"item": ["animal.cat"]},
                    "inventory.gifted": {"item": ["animal.cat"]},
                },
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    term_pack.write_text(
        json.dumps(
            {
                "locale": "sr",
                "terms": {
                    "animal.cat": {
                        "text": "mačka",
                        "morphology": {
                            "partOfSpeech": "noun",
                            "gender": "feminine",
                            "number": "singular",
                            "sense": "cat",
                        },
                        "forms": {
                            "accusative.singular": "mačku",
                            "accusative.plural": "mačke",
                            "dative.singular": "mački",
                            "dative.plural": "mačkama",
                            "count.one": "1 mačka",
                            "count.other": "{$count} mačke",
                        },
                    }
                },
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    run(
        [
            sys.executable,
            str(ROOT / "mf2_term_requirements.py"),
            "--catalog",
            str(catalog),
            "--term-pack",
            str(term_pack),
            "--out",
            str(requirements_report),
        ]
    )
    run(
        [
            sys.executable,
            str(ROOT / "mf2_term_pack_compile.py"),
            "--term-pack",
            str(term_pack),
            "--requirements-report",
            str(requirements_report),
            "--out",
            str(compiled_pack),
        ]
    )
    assert_equal(
        render(compiled_pack, "inventory.deleted", "animal.cat", "1", catalog),
        "Obrisano je mačku.",
    )
    assert_equal(
        render(compiled_pack, "inventory.deleted", "animal.cat", "2", catalog),
        "Obrisano je mačke.",
    )
    assert_equal(
        render(compiled_pack, "inventory.gifted", "animal.cat", None, catalog),
        "Daj mački.",
    )


def assert_german_article_case_rendering(tmp_path: Path) -> None:
    requirements_report = tmp_path / "de-term-requirements-report.json"
    compiled_pack = tmp_path / "de-compiled-term-pack.json"

    run(
        [
            sys.executable,
            str(ROOT / "mf2_term_requirements.py"),
            "--catalog",
            str(GERMAN_CATALOG),
            "--term-pack",
            str(GERMAN_TERM_PACK),
            "--out",
            str(requirements_report),
        ]
    )
    run(
        [
            sys.executable,
            str(ROOT / "mf2_term_pack_compile.py"),
            "--term-pack",
            str(GERMAN_TERM_PACK),
            "--requirements-report",
            str(requirements_report),
            "--out",
            str(compiled_pack),
        ]
    )

    payload = json.loads(compiled_pack.read_text(encoding="utf-8"))
    assert_equal(payload["locale"], "de")
    assert_equal(payload["schema"], "mojito-mf2-inflection/compiled-term-pack/v0")
    if payload["diagnostics"]:
        raise AssertionError("Expected German closed-world pack to compile without diagnostics")
    if len(payload["terms"]) != 3:
        raise AssertionError("Expected German closed-world pack to contain three terms")
    if sum(len(form_set["forms"]) for form_set in payload["formSets"]) != 24:
        raise AssertionError("Expected German closed-world pack to contain 24 form rows")
    if payload["sizeEstimates"]["binaryLowerBoundBytes"]["bindingReferenceBytes"] != 0:
        raise AssertionError("Expected mmap lower-bound estimate to exclude JSON binding metadata")

    assert_equal(
        render(compiled_pack, "inventory.deleted", "de.article_case.katze", "1", GERMAN_CATALOG),
        "Gelöscht: die Katze.",
    )
    assert_equal(
        render(compiled_pack, "inventory.deleted", "de.article_case.katze", "2", GERMAN_CATALOG),
        "Gelöscht: die Katzen.",
    )
    assert_equal(
        render(compiled_pack, "inventory.with", "de.article_case.maedchen", "2", GERMAN_CATALOG),
        "Mit den Mädchen.",
    )
    assert_equal(
        render(
            compiled_pack,
            "inventory.created",
            "de.article_case.1_euro_job",
            "1",
            GERMAN_CATALOG,
        ),
        "Erstellt: ein 1-Euro-Job.",
    )
    assert_equal(
        render(
            compiled_pack,
            "inventory.created",
            "de.article_case.maedchen",
            "2",
            GERMAN_CATALOG,
        ),
        "Erstellt: Mädchen.",
    )


def assert_spanish_article_rendering(tmp_path: Path) -> None:
    requirements_report = tmp_path / "es-term-requirements-report.json"
    compiled_pack = tmp_path / "es-compiled-term-pack.json"

    run(
        [
            sys.executable,
            str(ROOT / "mf2_term_requirements.py"),
            "--catalog",
            str(SPANISH_CATALOG),
            "--term-pack",
            str(SPANISH_TERM_PACK),
            "--out",
            str(requirements_report),
        ]
    )
    run(
        [
            sys.executable,
            str(ROOT / "mf2_term_pack_compile.py"),
            "--term-pack",
            str(SPANISH_TERM_PACK),
            "--requirements-report",
            str(requirements_report),
            "--out",
            str(compiled_pack),
        ]
    )

    requirements = json.loads(requirements_report.read_text(encoding="utf-8"))
    usage_requirements = requirements["messages"]["inventory.deleted"]["termUsages"][0]["requirements"]
    assert_equal(usage_requirements[3], "stress")
    if "forms.definite.singular" in usage_requirements or "forms.count.one" in usage_requirements:
        raise AssertionError("Spanish article composition should not require eager article/count forms")

    payload = json.loads(compiled_pack.read_text(encoding="utf-8"))
    fixture_payload = json.loads(SPANISH_COMPILED_FIXTURE.read_text(encoding="utf-8"))
    if payload != fixture_payload:
        raise AssertionError("Generated Spanish compiled fixture does not match checked-in fixture")
    if payload["diagnostics"]:
        raise AssertionError("Expected Spanish composed article pack to compile without diagnostics")
    if sum(len(form_set["forms"]) for form_set in payload["formSets"]) != 6:
        raise AssertionError("Expected Spanish composed article pack to contain only bare forms")
    strings = payload["strings"]
    water_term = next(term for term in payload["terms"] if strings[term["id"]] == "item.water")
    if not water_term["featureBits"] & (1 << 14):
        raise AssertionError("Expected Spanish stressed metadata bit for agua")

    assert_equal(
        render(compiled_pack, "inventory.deleted", "item.water", "1", SPANISH_CATALOG),
        "Has eliminado el agua.",
    )
    assert_equal(
        render(compiled_pack, "inventory.deleted", "item.water", "2", SPANISH_CATALOG),
        "Has eliminado las aguas.",
    )
    assert_equal(
        render(compiled_pack, "inventory.found", "item.water", "1", SPANISH_CATALOG),
        "Has encontrado un agua.",
    )
    assert_equal(
        render(compiled_pack, "inventory.deleted", "item.bee", "1", SPANISH_CATALOG),
        "Has eliminado la abeja.",
    )
    assert_equal(
        render(compiled_pack, "inventory.found", "item.bee", "2", SPANISH_CATALOG),
        "Has encontrado unas abejas.",
    )
    assert_equal(
        render(compiled_pack, "inventory.found", "item.poppy", "2", SPANISH_CATALOG),
        "Has encontrado unos ababoles.",
    )


def assert_italian_article_rendering(tmp_path: Path) -> None:
    requirements_report = tmp_path / "it-term-requirements-report.json"
    compiled_pack = tmp_path / "it-compiled-term-pack.json"

    run(
        [
            sys.executable,
            str(ROOT / "mf2_term_requirements.py"),
            "--catalog",
            str(ITALIAN_CATALOG),
            "--term-pack",
            str(ITALIAN_TERM_PACK),
            "--out",
            str(requirements_report),
        ]
    )
    run(
        [
            sys.executable,
            str(ROOT / "mf2_term_pack_compile.py"),
            "--term-pack",
            str(ITALIAN_TERM_PACK),
            "--requirements-report",
            str(requirements_report),
            "--out",
            str(compiled_pack),
        ]
    )

    requirements = json.loads(requirements_report.read_text(encoding="utf-8"))
    usage_requirements = requirements["messages"]["inventory.deleted"]["termUsages"][0]["requirements"]
    assert_equal(usage_requirements[3], "articleClass")
    if "forms.definite.singular" in usage_requirements or "forms.count.one" in usage_requirements:
        raise AssertionError("Italian article composition should not require eager article/count forms")

    payload = json.loads(compiled_pack.read_text(encoding="utf-8"))
    fixture_payload = json.loads(ITALIAN_COMPILED_FIXTURE.read_text(encoding="utf-8"))
    if payload != fixture_payload:
        raise AssertionError("Generated Italian compiled fixture does not match checked-in fixture")
    if payload["diagnostics"]:
        raise AssertionError("Expected Italian composed article pack to compile without diagnostics")
    if sum(len(form_set["forms"]) for form_set in payload["formSets"]) != 8:
        raise AssertionError("Expected Italian composed article pack to contain only bare forms")

    assert_equal(
        render(compiled_pack, "inventory.deleted", "item.gnome", "1", ITALIAN_CATALOG),
        "Hai eliminato lo gnomo.",
    )
    assert_equal(
        render(compiled_pack, "inventory.deleted", "item.gnome", "2", ITALIAN_CATALOG),
        "Hai eliminato gli gnomi.",
    )
    assert_equal(
        render(compiled_pack, "inventory.deleted", "item.book", "1", ITALIAN_CATALOG),
        "Hai eliminato il libro.",
    )
    assert_equal(
        render(compiled_pack, "inventory.found", "item.water", "1", ITALIAN_CATALOG),
        "Hai trovato un'acqua.",
    )
    assert_equal(
        render(compiled_pack, "inventory.found", "item.bee", "1", ITALIAN_CATALOG),
        "Hai trovato un'ape.",
    )


def assert_portuguese_article_rendering(tmp_path: Path) -> None:
    requirements_report = tmp_path / "pt-term-requirements-report.json"
    compiled_pack = tmp_path / "pt-compiled-term-pack.json"

    run(
        [
            sys.executable,
            str(ROOT / "mf2_term_requirements.py"),
            "--catalog",
            str(PORTUGUESE_CATALOG),
            "--term-pack",
            str(PORTUGUESE_TERM_PACK),
            "--out",
            str(requirements_report),
        ]
    )
    run(
        [
            sys.executable,
            str(ROOT / "mf2_term_pack_compile.py"),
            "--term-pack",
            str(PORTUGUESE_TERM_PACK),
            "--requirements-report",
            str(requirements_report),
            "--out",
            str(compiled_pack),
        ]
    )

    requirements = json.loads(requirements_report.read_text(encoding="utf-8"))
    deleted_requirements = requirements["messages"]["inventory.deleted"]["termUsages"][0]["requirements"]
    from_requirements = requirements["messages"]["inventory.from"]["termUsages"][0]["requirements"]
    expected_requirements = [
        "partOfSpeech=noun",
        "gender",
        "number",
        "forms.bare.singular",
        "forms.bare.plural",
    ]
    if deleted_requirements != expected_requirements or from_requirements != expected_requirements:
        raise AssertionError("Portuguese article composition should require only metadata plus bare forms")
    if "forms.definite.singular" in deleted_requirements or "forms.count.one" in deleted_requirements:
        raise AssertionError("Portuguese article composition should not require eager article/count forms")

    payload = json.loads(compiled_pack.read_text(encoding="utf-8"))
    fixture_payload = json.loads(PORTUGUESE_COMPILED_FIXTURE.read_text(encoding="utf-8"))
    if payload != fixture_payload:
        raise AssertionError("Generated Portuguese compiled fixture does not match checked-in fixture")
    if payload["diagnostics"]:
        raise AssertionError("Expected Portuguese composed article pack to compile without diagnostics")
    if sum(len(form_set["forms"]) for form_set in payload["formSets"]) != 4:
        raise AssertionError("Expected Portuguese composed article pack to contain only bare forms")

    assert_equal(
        render(compiled_pack, "inventory.deleted", "item.field", "1", PORTUGUESE_CATALOG),
        "Removido o campo.",
    )
    assert_equal(
        render(compiled_pack, "inventory.deleted", "item.field", "2", PORTUGUESE_CATALOG),
        "Removido os campos.",
    )
    assert_equal(
        render(compiled_pack, "inventory.deleted", "item.house", "1", PORTUGUESE_CATALOG),
        "Removido a casa.",
    )
    assert_equal(
        render(compiled_pack, "inventory.found", "item.field", "1", PORTUGUESE_CATALOG),
        "Encontrado um campo.",
    )
    assert_equal(
        render(compiled_pack, "inventory.found", "item.house", "2", PORTUGUESE_CATALOG),
        "Encontrado umas casas.",
    )
    assert_equal(
        render(compiled_pack, "inventory.from", "item.field", "1", PORTUGUESE_CATALOG),
        "Removido do campo.",
    )
    assert_equal(
        render(compiled_pack, "inventory.from", "item.house", "2", PORTUGUESE_CATALOG),
        "Removido das casas.",
    )
    assert_equal(
        render(compiled_pack, "inventory.in", "item.field", "1", PORTUGUESE_CATALOG),
        "Disponível no campo.",
    )
    assert_equal(
        render(compiled_pack, "inventory.in", "item.house", "2", PORTUGUESE_CATALOG),
        "Disponível nas casas.",
    )
    assert_equal(
        render(compiled_pack, "inventory.in_one", "item.field", "1", PORTUGUESE_CATALOG),
        "Disponível num campo.",
    )
    assert_equal(
        render(compiled_pack, "inventory.in_one", "item.house", "2", PORTUGUESE_CATALOG),
        "Disponível numas casas.",
    )
    assert_equal(
        render(compiled_pack, "inventory.by", "item.field", "2", PORTUGUESE_CATALOG),
        "Filtrado pelos campos.",
    )
    assert_equal(
        render(compiled_pack, "inventory.by", "item.house", "1", PORTUGUESE_CATALOG),
        "Filtrado pela casa.",
    )


def assert_m2if_binary_fixture() -> None:
    result = run(
        [
            sys.executable,
            str(ROOT / "m2if_decode_fixture.py"),
            "--fixture-kind",
            "all",
        ]
    )
    payload = json.loads(result.stdout)
    assert_equal(payload["fixtureCount"], 17)

    fixtures = payload["fixtures"]
    assert_equal(
        sorted(fixtures.keys()),
        [
            "ar-approved",
            "ar-review-required",
            "da-export-policy",
            "de-article-case",
            "es-article",
            "he-review-required",
            "hi-case-form",
            "it-article",
            "ml-approved",
            "ml-review-required",
            "pt-agreement",
            "ru-case-form",
            "sr-case-form",
            "sv-export-policy",
            "tr-explicit-template",
            "tr-explicit-template-auto",
            "tr-suffix",
        ],
    )

    portuguese = fixtures["pt-agreement"]
    assert_equal(portuguese["bytes"], 925)
    assert_equal(portuguese["exportPolicyPresent"], False)
    assert_equal(portuguese["locale"], "pt")
    assert_equal(portuguese["rendered"], "Disponível nas casas.")
    assert_equal(
        portuguese["sha256"],
        "87e6c93ca75f3a8b0bfb75c974a581c375a997ed00483a6affddbe148577a5b0",
    )
    assert_equal(portuguese["sections"]["strings"], [88, 87])
    assert_equal(portuguese["sections"]["metadata"], [336, 589])

    spanish = fixtures["es-article"]
    assert_equal(spanish["bytes"], 1027)
    assert_equal(spanish["exportPolicyPresent"], False)
    assert_equal(spanish["locale"], "es")
    assert_equal(spanish["rendered"], "Has eliminado el agua.")
    assert_equal(spanish["renderedPlural"], "Has encontrado las aguas.")
    assert_equal(
        spanish["sha256"],
        "e1005077d10897b432bd11cca24b8fae5532119b2c0d0415dc2b07c2d353fbdc",
    )
    assert_equal(spanish["sections"]["strings"], [88, 116])
    assert_equal(spanish["sections"]["metadata"], [436, 591])

    italian = fixtures["it-article"]
    assert_equal(italian["bytes"], 1119)
    assert_equal(italian["exportPolicyPresent"], False)
    assert_equal(italian["locale"], "it")
    assert_equal(italian["rendered"], "Hai eliminato lo gnomo.")
    assert_equal(italian["renderedPlural"], "Hai eliminato gli gnomi.")
    assert_equal(italian["renderedElision"], "Hai trovato un'ape.")
    assert_equal(
        italian["sha256"],
        "75c95f06aef86b51b39ec9fc38a2540169bee20f5b43613a52bcf01c8a8806b9",
    )
    assert_equal(italian["sections"]["strings"], [88, 135])
    assert_equal(italian["sections"]["metadata"], [528, 591])

    danish = fixtures["da-export-policy"]
    assert_equal(danish["bytes"], 1881)
    assert_equal(danish["exportPolicyPresent"], True)
    assert_equal(danish["locale"], "da")
    assert_equal(danish["rendered"], "Ejer: franskmændenes.")
    assert_equal(danish["renderedDefinite"], "Valgt barnebarnet.")
    assert_equal(danish["reviewRequiredReasons"], {})
    assert_equal(
        danish["runtimeExport"],
        "closed-world-genitive-definiteness-explicit-forms",
    )
    assert_equal(
        danish["sha256"],
        "2c4149893f8a04ebe36d47a7ac78d46d6bac567b7a3e4ccaec29400e9103d10a",
    )
    assert_equal(danish["sections"]["strings"], [88, 544])
    assert_equal(danish["sections"]["metadata"], [1064, 817])

    swedish = fixtures["sv-export-policy"]
    assert_equal(swedish["bytes"], 1821)
    assert_equal(swedish["exportPolicyPresent"], True)
    assert_equal(swedish["locale"], "sv")
    assert_equal(swedish["rendered"], "Ägare: bostädernas.")
    assert_equal(
        swedish["runtimeExport"],
        "closed-world-genitive-definiteness-explicit-forms",
    )
    assert_equal(
        swedish["sha256"],
        "766fcf012af7386e09c723002022746594ecf2e195c51b32497f01136e737a7d",
    )
    assert_equal(swedish["sections"]["strings"], [88, 483])
    assert_equal(swedish["sections"]["metadata"], [1004, 817])

    arabic = fixtures["ar-review-required"]
    assert_equal(arabic["bytes"], 1823)
    assert_equal(arabic["exportPolicyPresent"], True)
    assert_equal(arabic["locale"], "ar")
    assert_equal(arabic["rendered"], "مع أُمِّ.")
    assert_equal(arabic["reviewRequiredReasons"], {"missing-form-cell": 1})
    assert_equal(arabic["runtimeExport"], "closed-world-explicit-forms")
    assert_equal(
        arabic["sha256"],
        "1306a96f4d5fc2b6aefa4a4155f99ba07925b72004f4e9f6c38c9effeea668b9",
    )
    assert_equal(arabic["sections"]["strings"], [88, 656])
    assert_equal(arabic["sections"]["metadata"], [1076, 747])

    arabic_approved = fixtures["ar-approved"]
    assert_equal(arabic_approved["bytes"], 1794)
    assert_equal(arabic_approved["exportPolicyPresent"], True)
    assert_equal(arabic_approved["locale"], "ar")
    assert_equal(arabic_approved["rendered"], "اختيرت رسالتي.")
    assert_equal(arabic_approved["renderedPlural"], "حُذفت رسائل.")
    assert_equal(arabic_approved["runtimeExport"], "closed-world-explicit-forms")
    assert_equal(
        arabic_approved["sha256"],
        "cabd16085dc4b121afd4a0ab7a596990bb8d4f98d3a34b095fa247f802955e36",
    )
    assert_equal(arabic_approved["sections"]["strings"], [88, 617])
    assert_equal(arabic_approved["sections"]["metadata"], [1068, 726])

    hebrew = fixtures["he-review-required"]
    assert_equal(hebrew["bytes"], 1101)
    assert_equal(hebrew["exportPolicyPresent"], True)
    assert_equal(hebrew["locale"], "he")
    assert_equal(hebrew["rendered"], "נבחרו בתי.")
    assert_equal(hebrew["reviewRequiredReasons"], {"missing-form-cell": 1})
    assert_equal(
        hebrew["runtimeExport"],
        "closed-world-construct-state-explicit-forms",
    )
    assert_equal(
        hebrew["sha256"],
        "d9d980a9b6fefbed71cf520c4e2d399278bae693ef14e60db2de5a7688c2fa51",
    )
    assert_equal(hebrew["sections"]["strings"], [88, 121])
    assert_equal(hebrew["sections"]["metadata"], [336, 765])

    malayalam = fixtures["ml-review-required"]
    assert_equal(malayalam["bytes"], 1892)
    assert_equal(malayalam["exportPolicyPresent"], True)
    assert_equal(malayalam["locale"], "ml")
    assert_equal(malayalam["rendered"], "തിരഞ്ഞെടുത്തത് ശിഷ്യന്റെ.")
    assert_equal(malayalam["reviewRequiredReasons"], {"missing-form-cell": 1})
    assert_equal(malayalam["runtimeExport"], "closed-world-multi-case-explicit-forms")
    assert_equal(
        malayalam["sha256"],
        "c3796eb005a9bbecc73709a506b68d69aa9c4d3156327c30a7287381370e7aaa",
    )
    assert_equal(malayalam["sections"]["strings"], [88, 718])
    assert_equal(malayalam["sections"]["metadata"], [1136, 756])

    malayalam_approved = fixtures["ml-approved"]
    assert_equal(malayalam_approved["bytes"], 2015)
    assert_equal(malayalam_approved["exportPolicyPresent"], True)
    assert_equal(malayalam_approved["locale"], "ml")
    assert_equal(malayalam_approved["rendered"], "വിളിച്ചത് പിതാവേ.")
    assert_equal(malayalam_approved["renderedPlural"], "കൂടെ പിതാക്കന്മാരോട്.")
    assert_equal(
        malayalam_approved["runtimeExport"], "closed-world-multi-case-explicit-forms"
    )
    assert_equal(
        malayalam_approved["sha256"],
        "56f3c79cf45c7a0aff0d5ecef82d035d94d11cce552b0f30c66a525b2124b0fe",
    )
    assert_equal(malayalam_approved["sections"]["strings"], [88, 823])
    assert_equal(malayalam_approved["sections"]["metadata"], [1280, 735])

    german = fixtures["de-article-case"]
    assert_equal(german["bytes"], 1811)
    assert_equal(german["exportPolicyPresent"], False)
    assert_equal(german["locale"], "de")
    assert_equal(german["rendered"], "Mit den Katzen.")
    assert_equal(
        german["sha256"],
        "f7ad4866b16605ae3a5af0efcb0a3280543e5363f53c6d77aad28faf6d91cdef",
    )
    assert_equal(german["sections"]["strings"], [88, 590])
    assert_equal(german["sections"]["metadata"], [1220, 591])

    hindi = fixtures["hi-case-form"]
    assert_equal(hindi["bytes"], 1599)
    assert_equal(hindi["exportPolicyPresent"], False)
    assert_equal(hindi["locale"], "hi")
    assert_equal(hindi["rendered"], "में आँखों.")
    assert_equal(
        hindi["sha256"],
        "99b2392f63f18508daeabc133dc64389176d678b6350fd955186e809680a03b8",
    )
    assert_equal(hindi["sections"]["strings"], [88, 353])
    assert_equal(hindi["sections"]["metadata"], [844, 755])

    russian = fixtures["ru-case-form"]
    assert_equal(russian["bytes"], 2248)
    assert_equal(russian["exportPolicyPresent"], False)
    assert_equal(russian["locale"], "ru")
    assert_equal(russian["rendered"], "Удалено кошек.")
    assert_equal(
        russian["sha256"],
        "c98e970f2636d387501cf00e6e4303a4b56a3e3ddd5cf05aa462bdb6c630898a",
    )
    assert_equal(russian["sections"]["strings"], [88, 795])
    assert_equal(russian["sections"]["metadata"], [1596, 652])

    serbian = fixtures["sr-case-form"]
    assert_equal(serbian["bytes"], 4217)
    assert_equal(serbian["exportPolicyPresent"], False)
    assert_equal(serbian["locale"], "sr")
    assert_equal(serbian["rendered"], "Obrisano je mačku.")
    assert_equal(serbian["renderedPlural"], "Dodato je izuzecima.")
    assert_equal(
        serbian["sha256"],
        "44d3b8f2441fc1f883118ec4230ce8b0e10c223392c08a3fb3addfb8a070b9e6",
    )
    assert_equal(serbian["sections"]["strings"], [88, 1402])
    assert_equal(serbian["sections"]["metadata"], [3568, 649])

    turkish = fixtures["tr-suffix"]
    assert_equal(turkish["bytes"], 1269)
    assert_equal(turkish["exportPolicyPresent"], False)
    assert_equal(turkish["locale"], "tr")
    assert_equal(turkish["rendered"], "Silindi evleri.")
    assert_equal(
        turkish["sha256"],
        "71e1c3ab6d03392448dc0baf81b9e816b6ab420c39161a8d57be639f0aa9b785",
    )
    assert_equal(turkish["sections"]["strings"], [88, 115])
    assert_equal(turkish["sections"]["metadata"], [492, 777])

    turkish_explicit = fixtures["tr-explicit-template"]
    assert_equal(turkish_explicit["bytes"], 1714)
    assert_equal(turkish_explicit["exportPolicyPresent"], False)
    assert_equal(turkish_explicit["locale"], "tr")
    assert_equal(turkish_explicit["rendered"], "Silindi çakmağı.")
    assert_equal(turkish_explicit["renderedPlural"], "Listelendi aʼmal.")
    assert_equal(
        turkish_explicit["sha256"],
        "53f2d51cf36aedeea768cdb55acbc1864900ffe2c892e9b6b3b2af66ec3f0f2a",
    )
    assert_equal(turkish_explicit["sections"]["strings"], [88, 354])
    assert_equal(turkish_explicit["sections"]["metadata"], [908, 806])

    turkish_explicit_auto = fixtures["tr-explicit-template-auto"]
    assert_equal(turkish_explicit_auto["bytes"], 2134)
    assert_equal(turkish_explicit_auto["exportPolicyPresent"], False)
    assert_equal(turkish_explicit_auto["locale"], "tr")
    assert_equal(turkish_explicit_auto["rendered"], "Silindi baklavayı.")
    assert_equal(turkish_explicit_auto["renderedPlural"], "Listelendi cedâvil.")
    assert_equal(
        turkish_explicit_auto["sha256"],
        "652079f23fd47c6202f7e6de86748fd44c4f28dbc5b7e91ab88e468b4baf2840",
    )
    assert_equal(turkish_explicit_auto["sections"]["strings"], [88, 454])
    assert_equal(turkish_explicit_auto["sections"]["metadata"], [1328, 806])


def assert_release_validation_manifest(tmp_path: Path) -> None:
    release_dir = tmp_path / "release-fixture-bundle"
    bundle_result = run(
        [
            sys.executable,
            str(ROOT / "release_fixture_bundle.py"),
            "--out-dir",
            str(release_dir),
            "--validate",
        ]
    )
    bundle_payload = json.loads(bundle_result.stdout)
    assert_equal(bundle_payload["artifacts"], 35)
    assert_equal(bundle_payload["failed"], 0)

    payload = json.loads(Path(bundle_payload["report"]).read_text(encoding="utf-8"))
    assert_equal(payload["schema"], "mojito-mf2-inflection/release-validation-report/v0")
    assert_equal(payload["summary"]["artifacts"], 35)
    assert_equal(payload["summary"]["passed"], 35)
    assert_equal(payload["summary"]["failed"], 0)

    invalid_manifest = release_dir / "invalid-manifest.json"
    invalid_diagnostics = release_dir / "invalid-diagnostics.json"
    invalid_export_policy = release_dir / "invalid-export-policy.json"
    invalid_generation_summary = release_dir / "invalid-generation-summary.json"
    invalid_provenance = release_dir / "invalid-provenance.json"
    invalid_hindi_provenance = release_dir / "invalid-hindi-provenance.json"
    invalid_m2if = release_dir / "invalid.m2if"
    invalid_diagnostics.write_text(
        json.dumps(
            {
                "schema": "mojito-mf2-inflection/compiled-term-pack/v0",
                "strings": [],
                "terms": [],
                "formSets": [],
                "diagnostics": {},
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    invalid_export_policy.write_text(
        json.dumps(
            {
                "schema": "mojito-mf2-inflection/compiled-term-pack/v0",
                "strings": [],
                "terms": [],
                "formSets": [],
                "generationSummary": {
                    "exportPolicy": {
                        "runtimeExport": "closed-world-explicit-forms",
                        "compositionMode": "explicit-form-rows-v0",
                        "deferredComposition": [],
                        "automaticExportTerms": 0,
                        "reviewRequiredTerms": 1,
                        "blockedTerms": 0,
                        "reviewRequiredReasons": {"missing-form-cell": 0},
                        "blockedReasons": {},
                    }
                },
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    invalid_generation_summary.write_text(
        json.dumps(
            {
                "schema": "mojito-mf2-inflection/compiled-term-pack/v0",
                "strings": [],
                "terms": [],
                "formSets": [],
                "generationSummary": {
                    "candidateTerms": 1,
                    "exportedTerms": 1,
                    "formRows": 0,
                },
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    invalid_provenance.write_text(
        json.dumps(
            {
                "schema": "mojito-mf2-inflection/compiled-term-pack/v0",
                "strings": [],
                "terms": [],
                "formSets": [],
                "provenance": {
                    "license": "Unicode-3.0",
                    "generator": "test",
                    "sourceLabels": ["dictionary_xx.lst"],
                    "sources": [
                        {
                            "byteSize": 1,
                            "gitLfsPointer": False,
                            "path": "dictionary_xx.lst",
                            "sha256": "not-a-sha",
                        }
                    ],
                },
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    invalid_hindi_provenance.write_text(
        json.dumps(
            {
                "schema": "mojito-mf2-inflection/hi-pronoun-agreement-pack/v0",
                "locale": "hi",
                "packShape": "dependency-pronoun-agreement-rows-v0",
                "provenance": {
                    "license": "Unicode-3.0",
                    "generator": "test",
                    "sourceLabels": ["pronoun_hi.csv"],
                    "sources": [],
                },
                "summary": {
                    "binaryLowerBoundBytes": {
                        "rowBytes": 0,
                        "stringPoolBytes": 0,
                        "totalBytes": 0,
                    },
                    "dependencyRows": 0,
                    "genitiveRows": 0,
                    "invariantNumberRows": 0,
                    "rows": 0,
                    "uniqueValues": 0,
                },
                "rows": [],
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    invalid_m2if.write_bytes(b"not-m2if")
    invalid_manifest.write_text(
        json.dumps(
            {
                "schema": "mojito-mf2-inflection/release-validation-manifest/v0",
                "artifacts": [
                    {
                        "artifactId": "missing-json",
                        "kind": "compiled-term-pack-json",
                        "path": "missing.json",
                    },
                    {
                        "artifactId": "escaped-json",
                        "kind": "compiled-term-pack-json",
                        "path": "../outside.json",
                    },
                    {
                        "artifactId": "zero-reason-count-json",
                        "kind": "compiled-term-pack-json",
                        "path": "invalid-export-policy.json",
                    },
                    {
                        "artifactId": "invalid-diagnostics-json",
                        "kind": "compiled-term-pack-json",
                        "path": "invalid-diagnostics.json",
                    },
                    {
                        "artifactId": "invalid-generation-summary-json",
                        "kind": "compiled-term-pack-json",
                        "path": "invalid-generation-summary.json",
                    },
                    {
                        "artifactId": "invalid-provenance-json",
                        "kind": "compiled-term-pack-json",
                        "path": "invalid-provenance.json",
                    },
                    {
                        "artifactId": "invalid-hindi-provenance-json",
                        "kind": "hindi-pronoun-agreement-pack-json",
                        "path": "invalid-hindi-provenance.json",
                    },
                    {
                        "artifactId": "invalid-m2if",
                        "kind": "compiled-term-pack-m2if",
                        "path": "invalid.m2if",
                    },
                ],
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    invalid_report = tmp_path / "invalid-release-validation-report.json"
    run(
        [
            sys.executable,
            str(ROOT / "release_validation.py"),
            "--manifest",
            str(invalid_manifest),
            "--base-dir",
            str(release_dir),
            "--out",
            str(invalid_report),
            "--allow-failures",
        ]
    )
    invalid_payload = json.loads(invalid_report.read_text(encoding="utf-8"))
    assert_equal(invalid_payload["summary"]["artifacts"], 8)
    assert_equal(invalid_payload["summary"]["passed"], 0)
    assert_equal(invalid_payload["summary"]["failed"], 8)
    assert_equal(
        [
            {
                "artifactId": artifact["artifactId"],
                "code": artifact["code"],
                "kind": artifact["kind"],
                "status": artifact["status"],
            }
            for artifact in invalid_payload["artifacts"]
        ],
        [
            {
                "artifactId": "escaped-json",
                "code": "invalid-release-artifact-path",
                "kind": "compiled-term-pack-json",
                "status": "failed",
            },
            {
                "artifactId": "invalid-diagnostics-json",
                "code": "invalid-compiled-term-pack-json",
                "kind": "compiled-term-pack-json",
                "status": "failed",
            },
            {
                "artifactId": "invalid-generation-summary-json",
                "code": "invalid-compiled-term-pack-json",
                "kind": "compiled-term-pack-json",
                "status": "failed",
            },
            {
                "artifactId": "invalid-hindi-provenance-json",
                "code": "invalid-hindi-pronoun-agreement-pack-json",
                "kind": "hindi-pronoun-agreement-pack-json",
                "status": "failed",
            },
            {
                "artifactId": "invalid-m2if",
                "code": "invalid-compiled-term-pack-m2if",
                "kind": "compiled-term-pack-m2if",
                "status": "failed",
            },
            {
                "artifactId": "invalid-provenance-json",
                "code": "invalid-compiled-term-pack-json",
                "kind": "compiled-term-pack-json",
                "status": "failed",
            },
            {
                "artifactId": "missing-json",
                "code": "unreadable-release-artifact",
                "kind": "compiled-term-pack-json",
                "status": "failed",
            },
            {
                "artifactId": "zero-reason-count-json",
                "code": "invalid-compiled-term-pack-json",
                "kind": "compiled-term-pack-json",
                "status": "failed",
            },
        ],
    )
    assert_command_fails(
        [
            sys.executable,
            str(ROOT / "release_validation.py"),
            "--manifest",
            str(invalid_manifest),
            "--base-dir",
            str(release_dir),
        ],
        "Release validation failed for 8 artifact(s)",
    )


def assert_hindi_pronoun_agreement_parity() -> None:
    result = run(
        [
            sys.executable,
            str(ROOT / "hi_pronoun_agreement_parity.py"),
        ]
    )
    payload = json.loads(result.stdout)
    assert_equal(payload["bytes"], 9927)
    assert_equal(payload["locale"], "hi")
    assert_equal(payload["schema"], "mojito-mf2-inflection/hi-pronoun-agreement-pack/v0")
    assert_equal(payload["totalBytes"], 730)
    assert_equal(
        payload["sha256"],
        "585a4213c32b9f44efee42a0946b9333fee610e7c5d305b469ad946c55491a59",
    )
    assert_equal(
        payload["summary"],
        {
            "dependencyRows": 20,
            "genitiveRows": 20,
            "invariantNumberRows": 14,
            "rows": 38,
            "uniqueValues": 30,
        },
    )
    assert_equal(payload["rendered"]["first.singular.direct"], "मैं")
    assert_equal(payload["rendered"]["first.plural.genitive.masculine.plural"], "हमारे")
    assert_equal(payload["rendered"]["second.plural.genitive.formal.masculine.plural"], "आपके")


def assert_turkish_suffix_rendering(tmp_path: Path) -> None:
    requirements_report = tmp_path / "tr-term-requirements-report.json"
    compiled_pack = tmp_path / "tr-compiled-term-pack.json"

    run(
        [
            sys.executable,
            str(ROOT / "mf2_term_requirements.py"),
            "--catalog",
            str(TURKISH_CATALOG),
            "--term-pack",
            str(TURKISH_TERM_PACK),
            "--out",
            str(requirements_report),
        ]
    )
    run(
        [
            sys.executable,
            str(ROOT / "mf2_term_pack_compile.py"),
            "--term-pack",
            str(TURKISH_TERM_PACK),
            "--requirements-report",
            str(requirements_report),
            "--out",
            str(compiled_pack),
        ]
    )

    requirements = json.loads(requirements_report.read_text(encoding="utf-8"))
    usage_requirements = requirements["messages"]["inventory.deleted"]["termUsages"][0]["requirements"]
    expected_requirements = [
        "partOfSpeech=noun",
        "number",
        "turkishSuffix.vowelEnd",
        "turkishSuffix.frontVowel",
        "turkishSuffix.roundedVowel",
        "turkishSuffix.hardConsonant",
        "forms.bare.singular",
    ]
    if usage_requirements != expected_requirements:
        raise AssertionError("Turkish suffix composition should require only compact suffix metadata")
    if "gender" in usage_requirements or "forms.accusative.singular" in usage_requirements:
        raise AssertionError("Turkish suffix composition should not require gender or eager case forms")

    payload = json.loads(compiled_pack.read_text(encoding="utf-8"))
    fixture_payload = json.loads(TURKISH_COMPILED_FIXTURE.read_text(encoding="utf-8"))
    if payload != fixture_payload:
        raise AssertionError("Generated Turkish compiled fixture does not match checked-in fixture")
    if payload["diagnostics"]:
        raise AssertionError("Expected Turkish suffix pack to compile without diagnostics")
    if sum(len(form_set["forms"]) for form_set in payload["formSets"]) != 5:
        raise AssertionError("Expected Turkish suffix pack to contain only bare singular forms")

    assert_equal(
        render(compiled_pack, "inventory.deleted", "item.house", "1", TURKISH_CATALOG),
        "Silindi evi.",
    )
    assert_equal(
        render(compiled_pack, "inventory.deleted", "item.school", "1", TURKISH_CATALOG),
        "Silindi okulu.",
    )
    assert_equal(
        render(compiled_pack, "inventory.deleted", "item.car", "1", TURKISH_CATALOG),
        "Silindi arabayı.",
    )
    assert_equal(
        render(compiled_pack, "inventory.deleted", "item.rose", "1", TURKISH_CATALOG),
        "Silindi gülü.",
    )
    assert_equal(
        render(compiled_pack, "inventory.to", "item.car", "1", TURKISH_CATALOG),
        "Gönderildi arabaya.",
    )
    assert_equal(
        render(compiled_pack, "inventory.in", "item.park", "1", TURKISH_CATALOG),
        "Bulundu parkta.",
    )
    assert_equal(
        render(compiled_pack, "inventory.from", "item.park", "1", TURKISH_CATALOG),
        "Alındı parktan.",
    )
    assert_equal(
        render(compiled_pack, "inventory.deleted", "item.house", "2", TURKISH_CATALOG),
        "Silindi evleri.",
    )
    assert_equal(
        render(compiled_pack, "inventory.in", "item.school", "2", TURKISH_CATALOG),
        "Bulundu okullarda.",
    )
    assert_equal(
        render(compiled_pack, "inventory.listed", "item.rose", "2", TURKISH_CATALOG),
        "Listelendi güller.",
    )


def assert_russian_case_rendering(tmp_path: Path) -> None:
    catalog = tmp_path / "ru-term-usage-example.json"
    case_audit = tmp_path / "ru-case-pack-audit.json"
    case_form_pack = tmp_path / "ru-case-form-pack.json"
    compiled_pack = tmp_path / "ru-compiled-case-form-pack.json"

    catalog.write_text(
        json.dumps(
            {
                "locale": "ru",
                "messages": {
                    "inventory.deleted": "Удалено {$item :term case=accusative count=$count}.",
                    "inventory.none": "Нет {$item :term case=genitive count=$count}.",
                    "inventory.in": "В {$item :term case=prepositional count=$count}.",
                },
                "argumentTerms": {
                    "inventory.deleted": {
                        "item": ["ru.case.кошка", "ru.case.ресторан"]
                    },
                    "inventory.none": {
                        "item": ["ru.case.ресторан"]
                    },
                    "inventory.in": {
                        "item": ["ru.case.аббатство"]
                    },
                },
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    run(
        [
            sys.executable,
            str(ROOT / "ru_case_pack_audit.py"),
            "--dictionary",
            str(RUSSIAN_DICTIONARY),
            "--inflectional",
            str(RUSSIAN_INFLECTIONAL),
            "--out",
            str(case_audit),
            "--case-form-pack-out",
            str(case_form_pack),
            "--compiled-case-form-pack-out",
            str(compiled_pack),
            "--case-form-pack-limit",
            "3",
            "--case-form-surface",
            "кошка",
            "--case-form-surface",
            "ресторан",
            "--case-form-surface",
            "аббатство",
            "--max-samples",
            "8",
        ]
    )

    audit_payload = json.loads(case_audit.read_text(encoding="utf-8"))
    assert_equal(audit_payload["locale"], "ru")
    assert_equal(audit_payload["schema"], "mojito-mf2-inflection/ru-case-pack-audit/v0")
    assert_equal(audit_payload["counts"]["missingInflectionPatterns"], 0)
    assert_equal(audit_payload["counts"]["completeCaseFormCandidates"], 68171)
    review_policy = audit_payload["reviewPolicy"]
    assert_equal(review_policy["runtimeExport"], "closed-world-case-forms")
    assert_equal(review_policy["automaticExportTerms"], 68169)
    assert_equal(review_policy["blockedDictionaryEntries"], 846521)
    assert_equal(review_policy["automaticExportTerms"] + review_policy["blockedDictionaryEntries"], 914690)
    assert_equal(review_policy["blockedReasons"]["duplicate-term-id"], 2)
    if audit_payload["features"]["caseFormCandidateSkipReasons"]["conflicting-form-key"] != 26995:
        raise AssertionError("Expected Russian audit to preserve conflicting-form-key count")
    if audit_payload["features"]["conflictingFormKeys"]["instrumental.singular"] != 26981:
        raise AssertionError("Expected Russian audit to preserve instrumental conflict count")
    fixture_audit_payload = json.loads(RUSSIAN_CASE_AUDIT_FIXTURE.read_text(encoding="utf-8"))
    if audit_payload != fixture_audit_payload:
        raise AssertionError("Generated Russian audit fixture does not match checked-in fixture")

    case_form_payload = json.loads(case_form_pack.read_text(encoding="utf-8"))
    fixture_case_form_payload = json.loads(RUSSIAN_CASE_FORM_FIXTURE.read_text(encoding="utf-8"))
    if case_form_payload != fixture_case_form_payload:
        raise AssertionError("Generated Russian case-form fixture does not match checked-in fixture")
    assert_equal(case_form_payload["summary"]["candidateTerms"], 68169)
    assert_equal(case_form_payload["summary"]["exportedTerms"], 3)
    assert_equal(case_form_payload["summary"]["formRows"], 36)

    payload = json.loads(compiled_pack.read_text(encoding="utf-8"))
    fixture_payload = json.loads(RUSSIAN_COMPILED_FIXTURE.read_text(encoding="utf-8"))
    if payload != fixture_payload:
        raise AssertionError("Generated Russian compiled fixture does not match checked-in fixture")
    if payload["diagnostics"]:
        raise AssertionError("Expected Russian unambiguous case-form pack to compile without diagnostics")
    if sum(len(form_set["forms"]) for form_set in payload["formSets"]) != 36:
        raise AssertionError("Expected Russian compiled case-form pack to contain 36 form rows")

    assert_equal(
        render(compiled_pack, "inventory.deleted", "ru.case.кошка", "1", catalog),
        "Удалено кошку.",
    )
    assert_equal(
        render(compiled_pack, "inventory.deleted", "ru.case.кошка", "2", catalog),
        "Удалено кошек.",
    )
    assert_equal(
        render(compiled_pack, "inventory.deleted", "ru.case.ресторан", "2", catalog),
        "Удалено рестораны.",
    )
    assert_equal(
        render(compiled_pack, "inventory.none", "ru.case.ресторан", "2", catalog),
        "Нет ресторанов.",
    )
    assert_equal(
        render(compiled_pack, "inventory.in", "ru.case.аббатство", "1", catalog),
        "В аббатстве.",
    )
    assert_equal(
        render(compiled_pack, "inventory.in", "ru.case.аббатство", "2", catalog),
        "В аббатствах.",
    )


def assert_turkish_suffix_survey(tmp_path: Path) -> None:
    survey = tmp_path / "tr-suffix-pack-survey.json"
    explicit_template_pack = tmp_path / "tr-compiled-explicit-template-pack.json"
    auto_explicit_template_pack = tmp_path / "tr-compiled-explicit-template-auto-pack.json"
    run(
        [
            sys.executable,
            str(ROOT / "tr_suffix_pack_survey.py"),
            "--dictionary",
            str(TURKISH_DICTIONARY),
            "--inflectional",
            str(TURKISH_INFLECTIONAL),
            "--supplemental",
            str(TURKISH_SUPPLEMENTAL),
            "--out",
            str(survey),
            "--explicit-template-pack-out",
            str(explicit_template_pack),
            "--explicit-template-surface",
            "çakmak",
            "--explicit-template-surface",
            "gök",
            "--explicit-template-surface",
            "amel",
            "--explicit-template-surface",
            "anahtar",
            "--max-samples",
            "8",
        ]
    )
    run(
        [
            sys.executable,
            str(ROOT / "tr_suffix_pack_survey.py"),
            "--dictionary",
            str(TURKISH_DICTIONARY),
            "--inflectional",
            str(TURKISH_INFLECTIONAL),
            "--supplemental",
            str(TURKISH_SUPPLEMENTAL),
            "--explicit-template-pack-out",
            str(auto_explicit_template_pack),
            "--explicit-template-pack-limit",
            "8",
        ]
    )

    payload = json.loads(survey.read_text(encoding="utf-8"))
    assert_equal(payload["locale"], "tr")
    assert_equal(payload["schema"], "mojito-mf2-inflection/tr-suffix-pack-survey/v0")
    assert_equal(payload["counts"]["dictionaryEntries"], 2117)
    assert_equal(payload["counts"]["supportedEntries"], 1384)
    assert_equal(payload["counts"]["defaultInflectionEntries"], 1091)
    assert_equal(payload["counts"]["missingInflectionPatterns"], 0)
    assert_equal(payload["counts"]["supplementalEntries"], 970)
    assert_equal(payload["packShape"]["recommendation"], "rule-plus-exception suffix pack")
    if payload["packShape"]["supplementalMetadataLowerBoundBytes"] != 14355:
        raise AssertionError("Expected stable Turkish supplemental metadata estimate")
    assert_equal(payload["compositionPolicy"]["ruleSafeInflection"], "1")
    assert_equal(payload["compositionPolicy"]["mutationStrategy"], "explicit-template-forms")
    assert_equal(payload["compositionPolicy"]["supplementalRowsRequiringExplicitReview"], 968)
    assert_equal(payload["compositionPolicy"]["caseTemplateRows"], 60)
    assert_equal(payload["compositionPolicy"]["caseTemplateRowBytes"], 720)
    assert_equal(payload["compositionPolicy"]["consonantMutationTemplateRows"], 9)
    assert_equal(payload["compositionPolicy"]["consonantMutationTemplateRowBytes"], 108)
    assert_equal(payload["packShape"]["explicitTemplateBaseCandidateTerms"], 71)
    assert_equal(payload["packShape"]["explicitTemplateCompiledFormRows"], 279)
    assert_equal(payload["packShape"]["explicitTemplateCompiledStringPoolBytes"], 3431)
    assert_equal(payload["packShape"]["explicitTemplateCompiledTermRowBytes"], 1420)
    assert_equal(payload["packShape"]["explicitTemplateCompiledFormRowBytes"], 3348)
    assert_equal(payload["packShape"]["explicitTemplateCompiledLowerBoundBytes"], 8199)
    assert_equal(payload["packShape"]["explicitTemplateCompiledJsonBytes"], 44990)
    if payload["compositionPolicy"]["requiresExplicitFormFlags"] != ["exception", "foreign", "soft-consonant"]:
        raise AssertionError("Expected Turkish explicit-review flags to stay stable")
    if not payload["samples"]["consonantMutationTemplates"]:
        raise AssertionError("Expected Turkish mutation policy samples")
    fixture_payload = json.loads(TURKISH_SUFFIX_SURVEY_FIXTURE.read_text(encoding="utf-8"))
    if payload != fixture_payload:
        raise AssertionError("Generated Turkish suffix survey does not match checked-in fixture")

    explicit_payload = json.loads(explicit_template_pack.read_text(encoding="utf-8"))
    explicit_fixture_payload = json.loads(TURKISH_EXPLICIT_TEMPLATE_FIXTURE.read_text(encoding="utf-8"))
    if explicit_payload != explicit_fixture_payload:
        raise AssertionError("Generated Turkish explicit-template fixture does not match checked-in fixture")
    assert_equal(explicit_payload["locale"], "tr")
    assert_equal(explicit_payload["generationSummary"]["candidateTerms"], 4)
    assert_equal(explicit_payload["generationSummary"]["exportedTerms"], 4)
    assert_equal(explicit_payload["generationSummary"]["formRows"], 18)
    assert_equal(explicit_payload["sizeEstimates"]["binaryLowerBoundBytes"]["totalBytes"], 647)

    auto_explicit_payload = json.loads(auto_explicit_template_pack.read_text(encoding="utf-8"))
    auto_explicit_fixture_payload = json.loads(TURKISH_EXPLICIT_TEMPLATE_AUTO_FIXTURE.read_text(encoding="utf-8"))
    if auto_explicit_payload != auto_explicit_fixture_payload:
        raise AssertionError("Generated Turkish automatic explicit-template fixture does not match checked-in fixture")
    assert_equal(auto_explicit_payload["generationSummary"]["candidateTerms"], 71)
    assert_equal(auto_explicit_payload["generationSummary"]["exportedTerms"], 8)
    assert_equal(auto_explicit_payload["generationSummary"]["formRows"], 31)
    assert_equal(auto_explicit_payload["generationSummary"]["skippedTerms"]["non-singular-dictionary-surface"], 71)
    assert_equal(auto_explicit_payload["generationSummary"]["skippedTerms"]["non-base-case-dictionary-surface"], 108)
    assert_equal(auto_explicit_payload["sizeEstimates"]["binaryLowerBoundBytes"]["totalBytes"], 983)

    catalog = tmp_path / "tr-explicit-template-usage.json"
    catalog.write_text(
        json.dumps(
            {
                "locale": "tr",
                "messages": {
                    "inventory.deleted": "Silindi {$item :term case=accusative}.",
                    "inventory.to": "Gönderildi {$item :term case=dative}.",
                    "inventory.in": "Bulundu {$item :term case=locative}.",
                    "inventory.listed": "Listelendi {$item :term count=$count}.",
                },
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    assert_equal(
        render(explicit_template_pack, "inventory.deleted", "tr.explicit.çakmak", None, catalog),
        "Silindi çakmağı.",
    )
    assert_equal(
        render(explicit_template_pack, "inventory.to", "tr.explicit.gök", None, catalog),
        "Gönderildi göğe.",
    )
    assert_equal(
        render(explicit_template_pack, "inventory.in", "tr.explicit.çakmak", None, catalog),
        "Bulundu çakmakta.",
    )
    assert_equal(
        render(explicit_template_pack, "inventory.listed", "tr.explicit.amel", "2", catalog),
        "Listelendi aʼmal.",
    )
    assert_equal(
        render(explicit_template_pack, "inventory.listed", "tr.explicit.anahtar", "2", catalog),
        "Listelendi anahtarlar.",
    )
    assert_equal(
        render(auto_explicit_template_pack, "inventory.deleted", "tr.explicit.baklava", None, catalog),
        "Silindi baklavayı.",
    )
    assert_equal(
        render(auto_explicit_template_pack, "inventory.listed", "tr.explicit.bahar", "2", catalog),
        "Listelendi bahâran.",
    )
    assert_equal(
        render(auto_explicit_template_pack, "inventory.listed", "tr.explicit.cetvel", "2", catalog),
        "Listelendi cedâvil.",
    )


def assert_hindi_pack_survey(tmp_path: Path) -> None:
    survey = tmp_path / "hi-pack-survey.json"
    compiled_pack = tmp_path / "hi-compiled-case-form-pack.json"
    pronoun_pack = tmp_path / "hi-pronoun-agreement-pack.json"
    catalog = tmp_path / "hi-term-usage-example.json"
    catalog.write_text(
        json.dumps(
            {
                "locale": "hi",
                "messages": {
                    "inventory.deleted": "हटा दिया {$item :term case=direct count=$count}.",
                    "inventory.in": "में {$item :term case=oblique count=$count}.",
                    "inventory.hey": "हे {$item :term case=vocative count=$count}.",
                },
                "argumentTerms": {
                    "inventory.deleted": {"item": ["hi.case.अंगारा"]},
                    "inventory.in": {"item": ["hi.case.आँख"]},
                    "inventory.hey": {"item": ["hi.case.आदमी"]},
                },
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    run(
        [
            sys.executable,
            str(ROOT / "hi_pack_survey.py"),
            "--dictionary",
            str(HINDI_DICTIONARY),
            "--inflectional",
            str(HINDI_INFLECTIONAL),
            "--pronouns",
            str(HINDI_PRONOUNS),
            "--out",
            str(survey),
            "--compiled-case-form-pack-out",
            str(compiled_pack),
            "--pronoun-agreement-pack-out",
            str(pronoun_pack),
            "--case-form-pack-limit",
            "3",
            "--case-form-surface",
            "अंगारा",
            "--case-form-surface",
            "आँख",
            "--case-form-surface",
            "आदमी",
            "--max-samples",
            "8",
        ]
    )

    payload = json.loads(survey.read_text(encoding="utf-8"))
    fixture_payload = json.loads(HINDI_PACK_SURVEY_FIXTURE.read_text(encoding="utf-8"))
    if payload != fixture_payload:
        raise AssertionError("Generated Hindi pack survey does not match checked-in fixture")
    assert_equal(payload["schema"], "mojito-mf2-inflection/hi-pack-survey/v0")
    assert_equal(payload["locale"], "hi")
    assert_equal(
        payload["sources"][0]["sha256"],
        "c5b051852706d4c73188d532a072b4f6642b43ddd2efd506394f234428d93398",
    )
    assert_equal(
        payload["sources"][1]["sha256"],
        "20b67cea017333ce11a041c9150268bb14643fb5185a7a4228f4e1079f1d4f0b",
    )
    assert_equal(payload["counts"]["dictionaryEntries"], 7533)
    assert_equal(payload["counts"]["termEntries"], 2936)
    assert_equal(payload["counts"]["agreementEntries"], 3485)
    assert_equal(payload["counts"]["ambiguousAgreementSurfaces"], 1165)
    assert_equal(payload["counts"]["pronounRows"], 38)
    assert_equal(payload["counts"]["missingInflectionPatterns"], 0)
    assert_equal(payload["packShape"]["recommendation"], "case-form rows plus pronoun agreement table")
    assert_equal(payload["packShape"]["caseFormPack"]["candidateTerms"], 1187)
    assert_equal(payload["packShape"]["caseFormPack"]["formRows"], 3898)
    assert_equal(payload["packShape"]["caseFormPack"]["binaryLowerBoundBytes"]["totalBytes"], 146458)
    assert_equal(payload["packShape"]["pronounTableRows"], 38)
    assert_equal(payload["features"]["pronounDependencyFeatures"]["feminine"], 10)
    assert_equal(payload["features"]["pronounDependencyFeatures"]["masculine"], 10)
    assert_equal(payload["features"]["termCase"]["oblique"], 1856)
    assert_equal(payload["samples"]["caseFormTerms"][1]["forms"]["oblique.plural"], "अँगीठों")

    compiled_payload = json.loads(compiled_pack.read_text(encoding="utf-8"))
    fixture_compiled_payload = json.loads(HINDI_COMPILED_CASE_FORM_FIXTURE.read_text(encoding="utf-8"))
    if compiled_payload != fixture_compiled_payload:
        raise AssertionError("Generated Hindi compiled case-form fixture does not match checked-in fixture")
    assert_equal(compiled_payload["schema"], "mojito-mf2-inflection/compiled-term-pack/v0")
    assert_equal(compiled_payload["locale"], "hi")
    assert_equal(compiled_payload["sizeEstimates"]["compactJsonBytes"], 3888)
    assert_equal(compiled_payload["sizeEstimates"]["binaryLowerBoundBytes"]["totalBytes"], 626)
    assert_equal(len(compiled_payload["terms"]), 3)
    assert_equal(sum(len(form_set["forms"]) for form_set in compiled_payload["formSets"]), 18)
    assert_equal(
        render(compiled_pack, "inventory.deleted", "hi.case.अंगारा", "2", catalog),
        "हटा दिया अंगारे.",
    )
    assert_equal(
        render(compiled_pack, "inventory.in", "hi.case.आँख", "2", catalog),
        "में आँखों.",
    )
    assert_equal(
        render(compiled_pack, "inventory.hey", "hi.case.आदमी", "2", catalog),
        "हे आदमियो.",
    )

    pronoun_payload = json.loads(pronoun_pack.read_text(encoding="utf-8"))
    fixture_pronoun_payload = json.loads(HINDI_PRONOUN_AGREEMENT_FIXTURE.read_text(encoding="utf-8"))
    if pronoun_payload != fixture_pronoun_payload:
        raise AssertionError("Generated Hindi pronoun agreement fixture does not match checked-in fixture")
    assert_equal(pronoun_payload["schema"], "mojito-mf2-inflection/hi-pronoun-agreement-pack/v0")
    assert_equal(pronoun_payload["summary"]["rows"], 38)
    assert_equal(pronoun_payload["summary"]["uniqueValues"], 30)
    assert_equal(pronoun_payload["summary"]["dependencyRows"], 20)
    assert_equal(pronoun_payload["summary"]["binaryLowerBoundBytes"]["totalBytes"], 730)

    def pronoun(
        person: str,
        number: str,
        grammatical_case: str,
        register: str | None = None,
        dependency_gender: str | None = None,
        dependency_number: str | None = None,
    ) -> str:
        matches = [
            row
            for row in pronoun_payload["rows"]
            if row["person"] == person
            and row["case"] == grammatical_case
            and row["number"] in {number, "any"}
            and row["register"] == register
            and row["dependencyGender"] == dependency_gender
            and row["dependencyNumber"] == dependency_number
        ]
        if len(matches) != 1:
            raise AssertionError(f"Expected one Hindi pronoun match, got {len(matches)}")
        return matches[0]["value"]

    assert_equal(pronoun("first", "singular", "direct"), "मैं")
    assert_equal(pronoun("first", "plural", "genitive", dependency_gender="masculine", dependency_number="plural"), "हमारे")
    assert_equal(pronoun("second", "singular", "direct", register="informal"), "तुम")
    assert_equal(
        pronoun(
            "second",
            "plural",
            "genitive",
            register="formal",
            dependency_gender="masculine",
            dependency_number="plural",
        ),
        "आपके",
    )


def assert_arabic_pack_audit(tmp_path: Path) -> None:
    audit = tmp_path / "ar-pack-audit.json"
    run(
        [
            sys.executable,
            str(ROOT / "ar_pack_audit.py"),
            "--dictionary",
            str(ARABIC_DICTIONARY),
            "--inflectional",
            str(ARABIC_INFLECTIONAL),
            "--pronouns",
            str(ARABIC_PRONOUNS),
            "--out",
            str(audit),
            "--max-samples",
            "8",
        ]
    )

    payload = json.loads(audit.read_text(encoding="utf-8"))
    fixture_payload = json.loads(ARABIC_PACK_AUDIT_FIXTURE.read_text(encoding="utf-8"))
    if payload != fixture_payload:
        raise AssertionError("Generated Arabic audit fixture does not match checked-in fixture")
    assert_equal(payload["packPolicy"]["recommendation"], "closed-world explicit-form pack")
    assert_equal(payload["packPolicy"]["explicitFormCandidateRows"], 3841)
    assert_equal(payload["packPolicy"]["reviewRequiredEvidence"]["ambiguous-surface"], 1222)
    assert_equal(payload["packPolicy"]["reviewRequiredEvidence"]["dual-number"], 1601)
    assert_equal(payload["packPolicy"]["reviewRequiredEvidence"]["missing-or-ambiguous-gender"], 891)
    assert_equal(payload["packPolicy"]["reviewRequiredEvidence"]["pronoun-attachment"], 52)


def assert_hebrew_pack_audit(tmp_path: Path) -> None:
    audit = tmp_path / "he-pack-audit.json"
    run(
        [
            sys.executable,
            str(ROOT / "he_pack_audit.py"),
            "--dictionary",
            str(HEBREW_DICTIONARY),
            "--inflectional",
            str(HEBREW_INFLECTIONAL),
            "--pronouns",
            str(HEBREW_PRONOUNS),
            "--out",
            str(audit),
            "--max-samples",
            "8",
        ]
    )

    payload = json.loads(audit.read_text(encoding="utf-8"))
    fixture_payload = json.loads(HEBREW_PACK_AUDIT_FIXTURE.read_text(encoding="utf-8"))
    if payload != fixture_payload:
        raise AssertionError("Generated Hebrew audit fixture does not match checked-in fixture")
    assert_equal(
        payload["packPolicy"]["recommendation"],
        "closed-world construct-state explicit-form pack",
    )
    assert_equal(payload["packPolicy"]["caseMode"], "unsupported-for-nouns-v0")
    assert_equal(payload["packPolicy"]["runtimeOptions"], ["number", "definiteness"])
    assert_equal(payload["counts"]["constructAgreementEntries"], 35005)
    assert_equal(payload["counts"]["dualAgreementEntries"], 19)
    assert_equal(payload["counts"]["caseTaggedAgreementEntries"], 3)
    candidate_search = payload["approvedFixtureCandidateSearch"]
    assert_equal(
        candidate_search["requiredFormKeys"],
        [
            "bare.singular",
            "bare.plural",
            "construct.singular",
            "construct.plural",
            "construct.dual",
        ],
    )
    assert_equal(candidate_search["completeCleanGroups"], 0)
    assert_equal(candidate_search["constructDualCleanGroups"], 1)
    assert_equal(candidate_search["nearCompleteCleanGroups"], 181)
    assert_equal(candidate_search["maxObservedCleanFormKeys"], 4)
    assert_equal(payload["pronouns"]["rows"], 32)


def assert_malayalam_pack_audit(tmp_path: Path) -> None:
    audit = tmp_path / "ml-pack-audit.json"
    run(
        [
            sys.executable,
            str(ROOT / "ml_pack_audit.py"),
            "--dictionary",
            str(MALAYALAM_DICTIONARY),
            "--inflectional",
            str(MALAYALAM_INFLECTIONAL),
            "--pronouns",
            str(MALAYALAM_PRONOUNS),
            "--out",
            str(audit),
            "--max-samples",
            "8",
        ]
    )

    payload = json.loads(audit.read_text(encoding="utf-8"))
    fixture_payload = json.loads(MALAYALAM_PACK_AUDIT_FIXTURE.read_text(encoding="utf-8"))
    if payload != fixture_payload:
        raise AssertionError("Generated Malayalam audit fixture does not match checked-in fixture")
    assert_equal(
        payload["packPolicy"]["recommendation"],
        "closed-world multi-case explicit-form pack first",
    )
    assert_equal(payload["packPolicy"]["caseMode"], "explicit-form-key")
    assert_equal(payload["packPolicy"]["runtimeOptions"], ["case", "number"])
    assert_equal(payload["counts"]["caseTaggedAgreementEntries"], 721993)
    assert_equal(payload["counts"]["genderTaggedAgreementEntries"], 2262)
    assert_equal(payload["features"]["case"]["sociative"], 100049)
    assert_equal(payload["features"]["case"]["vocative"], 7788)
    assert_equal(payload["pronouns"]["rows"], 75)


def assert_malayalam_case_form_rendering(tmp_path: Path) -> None:
    review_required_pack = tmp_path / "ml-compiled-case-form-pack.json"
    approved_pack = tmp_path / "ml-compiled-approved-case-form-pack.json"
    catalog = tmp_path / "ml-term-usage.json"
    catalog.write_text(
        json.dumps(
            {
                "locale": "ml",
                "messages": {
                    "inventory.review_genitive": "തിരഞ്ഞെടുത്തത് {$item :term case=genitive}.",
                    "inventory.review_sociative": "കൂടെ {$item :term case=sociative number=plural}.",
                    "inventory.approved_vocative": "വിളിച്ചത് {$item :term case=vocative}.",
                    "inventory.approved_dative": "തിരഞ്ഞെടുത്തത് {$item :term case=dative number=plural}.",
                },
                "argumentTerms": {
                    "inventory.review_genitive": {"item": ["ml.case.disciple"]},
                    "inventory.review_sociative": {"item": ["ml.case.disciple"]},
                    "inventory.approved_vocative": {"item": ["ml.case.father"]},
                    "inventory.approved_dative": {"item": ["ml.case.father"]},
                },
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    run(
        [
            sys.executable,
            str(ROOT / "ml_case_form_pack.py"),
            "--dictionary",
            str(MALAYALAM_DICTIONARY),
            "--out",
            str(review_required_pack),
        ]
    )
    run(
        [
            sys.executable,
            str(ROOT / "ml_case_form_pack.py"),
            "--dictionary",
            str(MALAYALAM_DICTIONARY),
            "--fixture",
            "approved",
            "--out",
            str(approved_pack),
        ]
    )

    review_required_payload = json.loads(review_required_pack.read_text(encoding="utf-8"))
    review_required_fixture = json.loads(MALAYALAM_COMPILED_CASE_FORM_FIXTURE.read_text(encoding="utf-8"))
    if review_required_payload != review_required_fixture:
        raise AssertionError("Generated Malayalam review-required fixture does not match checked-in fixture")
    approved_payload = json.loads(approved_pack.read_text(encoding="utf-8"))
    approved_fixture = json.loads(
        MALAYALAM_COMPILED_APPROVED_CASE_FORM_FIXTURE.read_text(encoding="utf-8")
    )
    if approved_payload != approved_fixture:
        raise AssertionError("Generated Malayalam approved fixture does not match checked-in fixture")

    assert_equal(review_required_payload["generationSummary"]["requiredFormRows"], 16)
    assert_equal(review_required_payload["generationSummary"]["formRows"], 14)
    assert_equal(len(review_required_payload["generationSummary"]["missingFormKeys"]), 2)
    assert_equal(
        review_required_payload["generationSummary"]["exportPolicy"]["reviewRequiredTerms"], 1
    )
    assert_equal(approved_payload["generationSummary"]["requiredFormRows"], 16)
    assert_equal(approved_payload["generationSummary"]["formRows"], 16)
    assert_equal(approved_payload["generationSummary"]["missingFormKeys"], [])
    assert_equal(approved_payload["generationSummary"]["exportPolicy"]["automaticExportTerms"], 1)
    assert_equal(approved_payload["generationSummary"]["exportPolicy"]["reviewRequiredTerms"], 0)
    assert_equal(approved_payload["sizeEstimates"]["binaryLowerBoundBytes"]["totalBytes"], 1032)
    assert_equal(
        render(review_required_pack, "inventory.review_genitive", "ml.case.disciple", catalog=catalog),
        "തിരഞ്ഞെടുത്തത് ശിഷ്യന്റെ.",
    )
    assert_equal(
        render(review_required_pack, "inventory.review_sociative", "ml.case.disciple", catalog=catalog),
        "കൂടെ ശിഷ്യന്മാരോട്.",
    )
    assert_equal(
        render(approved_pack, "inventory.approved_vocative", "ml.case.father", catalog=catalog),
        "വിളിച്ചത് പിതാവേ.",
    )
    assert_equal(
        render(approved_pack, "inventory.approved_dative", "ml.case.father", catalog=catalog),
        "തിരഞ്ഞെടുത്തത് പിതാവുകൾക്കു്.",
    )


def assert_germanic_nordic_pack_audit(tmp_path: Path) -> None:
    audit = tmp_path / "germanic-nordic-pack-audit.json"
    run(
        [
            sys.executable,
            str(ROOT / "germanic_nordic_pack_audit.py"),
            "--out",
            str(audit),
            "--max-samples",
            "8",
        ]
    )

    payload = json.loads(audit.read_text(encoding="utf-8"))
    fixture_payload = json.loads(GERMANIC_NORDIC_PACK_AUDIT_FIXTURE.read_text(encoding="utf-8"))
    if payload != fixture_payload:
        raise AssertionError("Generated Germanic/Nordic audit fixture does not match checked-in fixture")
    assert_equal(payload["schema"], "mojito-mf2-inflection/germanic-nordic-pack-audit/v0")
    assert_equal(payload["summary"]["locales"], ["da", "nb", "nl", "sv"])
    assert_equal(payload["summary"]["caseRuntimeCandidateLocales"], ["da", "sv"])
    assert_equal(payload["summary"]["metadataFirstLocales"], ["nb", "nl"])

    locales = {report["locale"]: report for report in payload["locales"]}
    assert_equal(locales["da"]["packPolicy"]["caseMode"], "nominative-genitive-explicit-form-key")
    assert_equal(locales["sv"]["packPolicy"]["caseMode"], "nominative-genitive-explicit-form-key")
    assert_equal(locales["nb"]["packPolicy"]["caseMode"], "unsupported-for-nouns-v0")
    assert_equal(locales["nl"]["packPolicy"]["caseMode"], "inventory-only-v0")
    assert_equal(locales["da"]["counts"]["caseTaggedAgreementEntries"], 478561)
    assert_equal(locales["sv"]["counts"]["caseTaggedAgreementEntries"], 240031)
    assert_equal(locales["nb"]["counts"]["caseTaggedAgreementEntries"], 5)
    assert_equal(locales["nl"]["counts"]["diminutiveEntries"], 2783)


def assert_arabic_explicit_form_rendering(tmp_path: Path) -> None:
    compiled_pack = tmp_path / "ar-compiled-explicit-form-pack.json"
    approved_pack = tmp_path / "ar-compiled-approved-explicit-form-pack.json"
    catalog = tmp_path / "ar-term-usage.json"
    catalog.write_text(
        json.dumps(
            {
                "locale": "ar",
                "messages": {
                    "inventory.singular": "حُذفت {$item :term definiteness=indefinite case=nominative}.",
                    "inventory.dual": "حُذفت {$item :term definiteness=indefinite case=nominative number=dual}.",
                    "inventory.construct": "مع {$item :term definiteness=construct case=genitive}.",
                    "inventory.construct_dual": "اختيرت {$item :term definiteness=construct case=nominative number=dual}.",
                    "inventory.approved_dual_genitive": "اختيرت {$item :term definiteness=construct case=genitive number=dual}.",
                    "inventory.approved_plural_genitive": "حُذفت {$item :term definiteness=indefinite case=genitive number=plural}.",
                },
                "argumentTerms": {
                    "inventory.singular": {"item": ["ar.explicit.mother"]},
                    "inventory.dual": {"item": ["ar.explicit.mother"]},
                    "inventory.construct": {"item": ["ar.explicit.mother"]},
                    "inventory.construct_dual": {"item": ["ar.explicit.mother"]},
                    "inventory.approved_dual_genitive": {"item": ["ar.explicit.message"]},
                    "inventory.approved_plural_genitive": {"item": ["ar.explicit.message"]},
                },
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    run(
        [
            sys.executable,
            str(ROOT / "ar_explicit_form_pack.py"),
            "--dictionary",
            str(ARABIC_DICTIONARY),
            "--out",
            str(compiled_pack),
        ]
    )
    run(
        [
            sys.executable,
            str(ROOT / "ar_explicit_form_pack.py"),
            "--dictionary",
            str(ARABIC_DICTIONARY),
            "--fixture",
            "approved",
            "--out",
            str(approved_pack),
        ]
    )
    compiled_payload = json.loads(compiled_pack.read_text(encoding="utf-8"))
    fixture_payload = json.loads(ARABIC_COMPILED_EXPLICIT_FORM_FIXTURE.read_text(encoding="utf-8"))
    if compiled_payload != fixture_payload:
        raise AssertionError("Generated Arabic explicit-form fixture does not match checked-in fixture")
    approved_payload = json.loads(approved_pack.read_text(encoding="utf-8"))
    approved_fixture = json.loads(
        ARABIC_COMPILED_APPROVED_EXPLICIT_FORM_FIXTURE.read_text(encoding="utf-8")
    )
    if approved_payload != approved_fixture:
        raise AssertionError("Generated Arabic approved explicit-form fixture does not match checked-in fixture")
    assert_equal(compiled_payload["schema"], "mojito-mf2-inflection/compiled-term-pack/v0")
    assert_equal(compiled_payload["locale"], "ar")
    assert_equal(compiled_payload["generationSummary"]["requiredFormRows"], 18)
    assert_equal(compiled_payload["generationSummary"]["formRows"], 14)
    assert_equal(len(compiled_payload["generationSummary"]["missingFormKeys"]), 4)
    assert_equal(len(compiled_payload["generationSummary"]["reviewDiagnostics"]), 4)
    assert_equal(compiled_payload["sizeEstimates"]["binaryLowerBoundBytes"]["totalBytes"], 841)
    assert_equal(approved_payload["generationSummary"]["requiredFormRows"], 18)
    assert_equal(approved_payload["generationSummary"]["formRows"], 18)
    assert_equal(approved_payload["generationSummary"]["missingFormKeys"], [])
    assert_equal(approved_payload["generationSummary"]["exportPolicy"]["automaticExportTerms"], 1)
    assert_equal(approved_payload["generationSummary"]["exportPolicy"]["reviewRequiredTerms"], 0)
    assert_equal(approved_payload["sizeEstimates"]["binaryLowerBoundBytes"]["totalBytes"], 850)
    assert_equal(
        render(compiled_pack, "inventory.singular", "ar.explicit.mother", catalog=catalog),
        "حُذفت أُمٌّ.",
    )
    assert_equal(
        render(compiled_pack, "inventory.dual", "ar.explicit.mother", catalog=catalog),
        "حُذفت أُمَّانِ.",
    )
    assert_equal(
        render(compiled_pack, "inventory.construct", "ar.explicit.mother", catalog=catalog),
        "مع أُمِّ.",
    )
    assert_equal(
        render(compiled_pack, "inventory.construct_dual", "ar.explicit.mother", catalog=catalog),
        "اختيرت أُمَّا.",
    )
    assert_equal(
        render(approved_pack, "inventory.approved_dual_genitive", "ar.explicit.message", catalog=catalog),
        "اختيرت رسالتي.",
    )
    assert_equal(
        render(approved_pack, "inventory.approved_plural_genitive", "ar.explicit.message", catalog=catalog),
        "حُذفت رسائل.",
    )


def assert_swedish_genitive_definiteness_rendering(tmp_path: Path) -> None:
    compiled_pack = tmp_path / "sv-compiled-genitive-definiteness-pack.json"
    catalog = tmp_path / "sv-term-usage.json"
    catalog.write_text(
        json.dumps(
            {
                "locale": "sv",
                "messages": {
                    "inventory.bare_plural": "Valda {$item :term number=plural}.",
                    "inventory.definite": "Valt {$item :term definiteness=definite case=nominative}.",
                    "inventory.genitive": "Ägare: {$item :term definiteness=definite case=genitive number=plural}.",
                },
                "argumentTerms": {
                    "inventory.bare_plural": {"item": ["sv.definiteness.bostad"]},
                    "inventory.definite": {"item": ["sv.definiteness.chassi"]},
                    "inventory.genitive": {"item": ["sv.definiteness.bostad"]},
                },
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    run(
        [
            sys.executable,
            str(ROOT / "sv_genitive_definiteness_pack.py"),
            "--dictionary",
            str(SWEDISH_DICTIONARY),
            "--out",
            str(compiled_pack),
        ]
    )
    compiled_payload = json.loads(compiled_pack.read_text(encoding="utf-8"))
    fixture_payload = json.loads(
        SWEDISH_COMPILED_GENITIVE_DEFINITENESS_FIXTURE.read_text(encoding="utf-8")
    )
    if compiled_payload != fixture_payload:
        raise AssertionError(
            "Generated Swedish genitive/definiteness fixture does not match checked-in fixture"
        )
    assert_equal(compiled_payload["schema"], "mojito-mf2-inflection/compiled-term-pack/v0")
    assert_equal(compiled_payload["locale"], "sv")
    assert_equal(compiled_payload["generationSummary"]["exportedTerms"], 2)
    assert_equal(compiled_payload["generationSummary"]["formRows"], 20)
    assert_equal(compiled_payload["generationSummary"]["missingFormKeys"], [])
    export_policy = compiled_payload["generationSummary"]["exportPolicy"]
    assert_equal(
        export_policy["runtimeExport"],
        "closed-world-genitive-definiteness-explicit-forms",
    )
    assert_equal(export_policy["compositionMode"], "explicit-form-rows-v0")
    assert_equal(export_policy["automaticExportTerms"], 2)
    assert_equal(export_policy["reviewRequiredTerms"], 0)
    assert_equal(export_policy["blockedTerms"], 0)
    assert_equal(
        export_policy["deferredComposition"],
        ["article-selection", "definiteness-suffix", "genitive-suffix"],
    )
    assert_equal(compiled_payload["sizeEstimates"]["binaryLowerBoundBytes"]["totalBytes"], 760)
    assert_equal(
        render(compiled_pack, "inventory.bare_plural", "sv.definiteness.bostad", catalog=catalog),
        "Valda bostäder.",
    )
    assert_equal(
        render(compiled_pack, "inventory.definite", "sv.definiteness.chassi", catalog=catalog),
        "Valt chassit.",
    )
    assert_equal(
        render(compiled_pack, "inventory.genitive", "sv.definiteness.bostad", catalog=catalog),
        "Ägare: bostädernas.",
    )


def assert_danish_genitive_definiteness_rendering(tmp_path: Path) -> None:
    compiled_pack = tmp_path / "da-compiled-genitive-definiteness-pack.json"
    catalog = tmp_path / "da-term-usage.json"
    catalog.write_text(
        json.dumps(
            {
                "locale": "da",
                "messages": {
                    "inventory.bare_plural": "Valgte {$item :term number=plural}.",
                    "inventory.definite": "Valgt {$item :term definiteness=definite case=nominative}.",
                    "inventory.genitive": "Ejer: {$item :term definiteness=definite case=genitive number=plural}.",
                },
                "argumentTerms": {
                    "inventory.bare_plural": {"item": ["da.definiteness.franskmand"]},
                    "inventory.definite": {"item": ["da.definiteness.barnebarn"]},
                    "inventory.genitive": {"item": ["da.definiteness.franskmand"]},
                },
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    run(
        [
            sys.executable,
            str(ROOT / "da_genitive_definiteness_pack.py"),
            "--dictionary",
            str(DANISH_DICTIONARY),
            "--out",
            str(compiled_pack),
        ]
    )
    compiled_payload = json.loads(compiled_pack.read_text(encoding="utf-8"))
    fixture_payload = json.loads(
        DANISH_COMPILED_GENITIVE_DEFINITENESS_FIXTURE.read_text(encoding="utf-8")
    )
    if compiled_payload != fixture_payload:
        raise AssertionError(
            "Generated Danish genitive/definiteness fixture does not match checked-in fixture"
        )
    assert_equal(compiled_payload["schema"], "mojito-mf2-inflection/compiled-term-pack/v0")
    assert_equal(compiled_payload["locale"], "da")
    assert_equal(compiled_payload["generationSummary"]["exportedTerms"], 2)
    assert_equal(compiled_payload["generationSummary"]["formRows"], 20)
    assert_equal(compiled_payload["generationSummary"]["missingFormKeys"], [])
    export_policy = compiled_payload["generationSummary"]["exportPolicy"]
    assert_equal(
        export_policy["runtimeExport"],
        "closed-world-genitive-definiteness-explicit-forms",
    )
    assert_equal(export_policy["compositionMode"], "explicit-form-rows-v0")
    assert_equal(export_policy["automaticExportTerms"], 2)
    assert_equal(export_policy["reviewRequiredTerms"], 0)
    assert_equal(export_policy["blockedTerms"], 0)
    assert_equal(
        export_policy["deferredComposition"],
        ["article-selection", "definiteness-suffix", "genitive-suffix"],
    )
    assert_equal(compiled_payload["sizeEstimates"]["binaryLowerBoundBytes"]["totalBytes"], 821)
    assert_equal(
        render(compiled_pack, "inventory.bare_plural", "da.definiteness.franskmand", catalog=catalog),
        "Valgte franskmænd.",
    )
    assert_equal(
        render(compiled_pack, "inventory.definite", "da.definiteness.barnebarn", catalog=catalog),
        "Valgt barnebarnet.",
    )
    assert_equal(
        render(compiled_pack, "inventory.genitive", "da.definiteness.franskmand", catalog=catalog),
        "Ejer: franskmændenes.",
    )


def assert_norwegian_bokmal_noun_metadata(tmp_path: Path) -> None:
    metadata_pack = tmp_path / "nb-noun-metadata-pack.json"

    run(
        [
            sys.executable,
            str(ROOT / "nb_noun_metadata_pack.py"),
            "--dictionary",
            str(NORWEGIAN_BOKMAL_DICTIONARY),
            "--out",
            str(metadata_pack),
        ]
    )
    generated_payload = json.loads(metadata_pack.read_text(encoding="utf-8"))
    fixture_payload = json.loads(
        NORWEGIAN_BOKMAL_NOUN_METADATA_FIXTURE.read_text(encoding="utf-8")
    )
    if generated_payload != fixture_payload:
        raise AssertionError(
            "Generated Norwegian Bokmål noun metadata fixture does not match checked-in fixture"
        )
    assert_equal(generated_payload["schema"], "mojito-mf2-inflection/nb-noun-metadata-pack/v0")
    assert_equal(generated_payload["locale"], "nb")
    assert_equal(generated_payload["generationSummary"]["metadataCandidateRows"], 114535)
    assert_equal(generated_payload["generationSummary"]["metadataCandidateSurfaces"], 114089)
    assert_equal(generated_payload["generationSummary"]["exportedRows"], 12)
    assert_equal(generated_payload["generationSummary"]["reviewDiagnosticRows"], 2)
    assert_equal(generated_payload["generationSummary"]["caseTaggedNounRows"], 1)
    assert_equal(generated_payload["sizeEstimates"]["sampleMetadataPack"]["jsonBytes"], 6404)
    assert_equal(
        generated_payload["sizeEstimates"]["fullMetadataPack"]["binaryLowerBoundBytes"],
        2696106,
    )
    strings = generated_payload["strings"]
    rows = {strings[row["surface"]]: row for row in generated_payload["rows"]}
    assert_equal(rows["hund"]["gender"][0], "masculine")
    assert_equal(rows["jente"]["gender"][0], "feminine")
    assert_equal(rows["barn"]["gender"][0], "neuter")
    assert_equal(rows["barn"]["reviewDiagnostics"][0], "multiple-numbers")
    assert_equal(rows["bøkene"]["reviewDiagnostics"][0], "multiple-genders")


def assert_dutch_noun_metadata(tmp_path: Path) -> None:
    metadata_pack = tmp_path / "nl-noun-metadata-pack.json"

    run(
        [
            sys.executable,
            str(ROOT / "nl_noun_metadata_pack.py"),
            "--dictionary",
            str(DUTCH_DICTIONARY),
            "--out",
            str(metadata_pack),
        ]
    )
    generated_payload = json.loads(metadata_pack.read_text(encoding="utf-8"))
    fixture_payload = json.loads(DUTCH_NOUN_METADATA_FIXTURE.read_text(encoding="utf-8"))
    if generated_payload != fixture_payload:
        raise AssertionError("Generated Dutch noun metadata fixture does not match checked-in fixture")
    assert_equal(generated_payload["schema"], "mojito-mf2-inflection/nl-noun-metadata-pack/v0")
    assert_equal(generated_payload["locale"], "nl")
    assert_equal(generated_payload["generationSummary"]["metadataCandidateRows"], 5312)
    assert_equal(generated_payload["generationSummary"]["metadataCandidateSurfaces"], 5308)
    assert_equal(generated_payload["generationSummary"]["diminutiveRows"], 2782)
    assert_equal(generated_payload["generationSummary"]["metadataDiminutiveRows"], 2412)
    assert_equal(generated_payload["generationSummary"]["exportedRows"], 20)
    assert_equal(generated_payload["generationSummary"]["exportedDiminutiveRows"], 10)
    assert_equal(generated_payload["generationSummary"]["definitenessTaggedTermRows"], 12)
    assert_equal(generated_payload["sizeEstimates"]["sampleMetadataPack"]["jsonBytes"], 8797)
    assert_equal(
        generated_payload["sizeEstimates"]["fullMetadataPack"]["binaryLowerBoundBytes"],
        114496,
    )
    strings = generated_payload["strings"]
    rows = {strings[row["surface"]]: row for row in generated_payload["rows"]}
    assert_equal(rows["boek"]["diminutive"], False)
    assert_equal(rows["boekje"]["diminutive"], True)
    assert_equal(rows["man"]["gender"][0], "masculine")
    assert_equal(rows["mannetje"]["gender"][0], "neuter")
    assert_equal(rows["vrouwtjes"]["gender"][0], "feminine")


def assert_low_inflection_locale_audit(tmp_path: Path) -> None:
    audit_report = tmp_path / "low-inflection-locale-audit.json"

    run(
        [
            sys.executable,
            str(ROOT / "low_inflection_locale_audit.py"),
            "--out",
            str(audit_report),
        ]
    )
    generated_payload = json.loads(audit_report.read_text(encoding="utf-8"))
    fixture_payload = json.loads(LOW_INFLECTION_LOCALE_AUDIT_FIXTURE.read_text(encoding="utf-8"))
    if generated_payload != fixture_payload:
        raise AssertionError(
            "Generated low-inflection locale audit fixture does not match checked-in fixture"
        )
    assert_equal(
        generated_payload["schema"], "mojito-mf2-inflection/low-inflection-locale-audit/v0"
    )
    assert_equal(generated_payload["summary"]["localeCount"], 9)
    assert_equal(generated_payload["summary"]["modeCounts"]["data-materialization-required"], 2)
    assert_equal(generated_payload["summary"]["modeCounts"]["profile-only-noop"], 7)
    assert_equal(generated_payload["summary"]["dataMaterializationRequiredLocales"], ["en", "ko"])
    assert_equal(
        generated_payload["summary"]["profileOnlyNoopLocales"],
        ["id", "ja", "ms", "th", "vi", "zh", "yue"],
    )
    reports = {report["locale"]: report for report in generated_payload["locales"]}
    assert_equal(reports["en"]["dataState"]["dictionary"], "git-lfs-pointer")
    assert_equal(reports["en"]["pronouns"]["rows"], 38)
    assert_equal(reports["ko"]["dataState"]["dictionary"], "git-lfs-pointer")
    assert_equal(reports["ko"]["pronouns"]["rows"], 48)
    assert_equal(reports["zh"]["dataState"]["dictionary"], "empty-placeholder")
    assert_equal(reports["yue"]["dataState"]["dictionary"], "missing")


def assert_pronoun_profile_pack(tmp_path: Path) -> None:
    pack = tmp_path / "pronoun-profile-pack.json"

    run(
        [
            sys.executable,
            str(ROOT / "pronoun_profile_pack.py"),
            "--out",
            str(pack),
        ]
    )
    generated_payload = json.loads(pack.read_text(encoding="utf-8"))
    fixture_payload = json.loads(PRONOUN_PROFILE_PACK_FIXTURE.read_text(encoding="utf-8"))
    if generated_payload != fixture_payload:
        raise AssertionError(
            "Generated pronoun profile pack fixture does not match checked-in fixture"
        )
    assert_equal(generated_payload["schema"], "mojito-mf2-inflection/pronoun-profile-pack/v0")
    assert_equal(generated_payload["summary"]["localeCount"], 9)
    assert_equal(generated_payload["summary"]["totalPronounRows"], 162)
    assert_equal(generated_payload["summary"]["totalUniquePronounValues"], 137)
    assert_equal(generated_payload["summary"]["runtimeTermInflection"], False)
    assert_equal(generated_payload["summary"]["dataMaterializationRequiredLocales"], ["en", "ko"])
    assert_equal(
        generated_payload["summary"]["profileOnlyNoopLocales"],
        ["id", "ja", "ms", "th", "vi", "zh", "yue"],
    )
    profiles = {profile["locale"]: profile for profile in generated_payload["locales"]}
    assert_equal(profiles["en"]["mode"], "data-materialization-required")
    assert_equal(profiles["en"]["pronouns"]["rows"], 38)
    assert_equal(profiles["ko"]["pronouns"]["registers"]["formal"], 27)
    assert_equal(profiles["zh"]["mode"], "profile-only-noop")
    assert_equal(profiles["yue"]["pronouns"]["uniqueValues"], 3)


def assert_locale_data_survey(tmp_path: Path) -> None:
    survey = tmp_path / "locale-data-survey.json"

    run(
        [
            sys.executable,
            str(ROOT / "locale_data_survey.py"),
            "--out",
            str(survey),
        ]
    )
    generated_payload = json.loads(survey.read_text(encoding="utf-8"))
    fixture_payload = json.loads(LOCALE_DATA_SURVEY_FIXTURE.read_text(encoding="utf-8"))
    if generated_payload != fixture_payload:
        raise AssertionError(
            "Generated locale data survey fixture does not match checked-in fixture"
        )
    assert_equal(generated_payload["schema"], "mojito-mf2-inflection/locale-data-survey/v0")
    assert_equal(generated_payload["summary"]["localeGroupCount"], 25)
    assert_equal(generated_payload["summary"]["runtimePrototypeLocaleCount"], 14)
    assert_equal(generated_payload["summary"]["blockedSourceDataLocaleCount"], 1)

    blocked = {entry["localeGroup"]: entry for entry in generated_payload["blockedSourceData"]}
    assert_equal(blocked["pl"]["status"], "source-data-acquisition-required")
    assert_equal(blocked["pl"]["supportedLocaleGroup"], False)
    assert_equal(blocked["pl"]["expected"]["dictionary"]["exists"], False)
    assert_equal(blocked["pl"]["expected"]["inflectional"]["exists"], False)
    assert_equal(blocked["pl"]["expected"]["pronounCsv"]["exists"], False)
    assert_equal(blocked["pl"]["cache"]["dictionary"]["exists"], False)
    assert_equal(blocked["pl"]["cache"]["inflectional"]["exists"], False)


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="mf2-inflection-smoke-") as tmp:
        tmp_path = Path(tmp)
        dictionary_report = tmp_path / "fr-dictionary-report.json"
        noun_report = tmp_path / "fr-noun-pack-report.json"
        noun_sample_pack = tmp_path / "fr-noun-metadata-sample-pack.json"
        suffix_rule_pack = tmp_path / "fr-gender-suffix-rule-pack.json"
        requirements_report = tmp_path / "fr-term-requirements-report.json"
        compiled_pack = tmp_path / "fr-compiled-term-pack.json"
        serbian_case_report = tmp_path / "sr-case-pack-report.json"
        serbian_case_form_pack = tmp_path / "sr-case-form-pack.json"
        serbian_compiled_case_form_pack = tmp_path / "sr-compiled-case-form-pack.json"
        german_article_case_report = tmp_path / "de-article-case-report.json"
        spanish_noun_pack_report = tmp_path / "es-noun-pack-report.json"
        italian_noun_pack_report = tmp_path / "it-noun-pack-report.json"
        portuguese_noun_pack_report = tmp_path / "pt-noun-pack-report.json"

        run(
            [
                sys.executable,
                str(ROOT / "fr_dictionary_report.py"),
                "--dictionary",
                str(SUPPLEMENTAL_DICTIONARY),
                "--out",
                str(dictionary_report),
            ]
        )
        run(
            [
                sys.executable,
                str(ROOT / "fr_noun_pack_report.py"),
                "--dictionary",
                str(SUPPLEMENTAL_DICTIONARY),
                "--out",
                str(noun_report),
                "--sample-pack-out",
                str(noun_sample_pack),
                "--sample-pack-limit",
                "20",
                "--suffix-rule-pack-out",
                str(suffix_rule_pack),
                "--suffix-rule-limit",
                "20",
            ]
        )
        run(
            [
                sys.executable,
                str(ROOT / "mf2_term_requirements.py"),
                "--catalog",
                str(CATALOG),
                "--term-pack",
                str(TERM_PACK),
                "--out",
                str(requirements_report),
            ]
        )
        run(
            [
                sys.executable,
                str(ROOT / "mf2_term_pack_compile.py"),
                "--term-pack",
                str(TERM_PACK),
                "--requirements-report",
                str(requirements_report),
                "--out",
                str(compiled_pack),
            ]
        )

        assert_equal(render(compiled_pack, "inventory.deleted", "item.iron_sword", "1"), "Vous avez supprim\u00e9 l'\u00e9p\u00e9e de fer.")
        assert_equal(render(compiled_pack, "inventory.deleted", "item.iron_sword", "2"), "Vous avez supprim\u00e9 les \u00e9p\u00e9es de fer.")
        assert_equal(render(compiled_pack, "inventory.deleted", "concept.book", "1"), "Vous avez supprim\u00e9 le livre.")
        assert_equal(render(compiled_pack, "inventory.deleted", "unit.pound", "1"), "Vous avez supprim\u00e9 la livre.")
        assert_equal(render(compiled_pack, "inventory.weighed", "unit.pound", "2"), "Le poids est de 2 livres.")
        assert_term_usage_option_contract()
        assert_invalid_term_option_contract(tmp_path)
        assert_closed_world_unbound_term_shape(tmp_path)
        assert_missing_render_count_fails(compiled_pack)
        assert_compiled_pack_with_diagnostics_fails(tmp_path)
        assert_serbian_case_rendering(tmp_path)
        assert_german_article_case_rendering(tmp_path)
        assert_spanish_article_rendering(tmp_path)
        assert_italian_article_rendering(tmp_path)
        assert_portuguese_article_rendering(tmp_path)
        assert_m2if_binary_fixture()
        assert_release_validation_manifest(tmp_path)
        assert_hindi_pronoun_agreement_parity()
        assert_turkish_suffix_rendering(tmp_path)
        assert_russian_case_rendering(tmp_path)
        assert_turkish_suffix_survey(tmp_path)
        assert_hindi_pack_survey(tmp_path)
        assert_arabic_pack_audit(tmp_path)
        assert_hebrew_pack_audit(tmp_path)
        assert_malayalam_pack_audit(tmp_path)
        assert_malayalam_case_form_rendering(tmp_path)
        assert_germanic_nordic_pack_audit(tmp_path)
        assert_arabic_explicit_form_rendering(tmp_path)
        assert_swedish_genitive_definiteness_rendering(tmp_path)
        assert_danish_genitive_definiteness_rendering(tmp_path)
        assert_norwegian_bokmal_noun_metadata(tmp_path)
        assert_dutch_noun_metadata(tmp_path)
        assert_low_inflection_locale_audit(tmp_path)
        assert_pronoun_profile_pack(tmp_path)
        assert_locale_data_survey(tmp_path)

        run(
            [
                sys.executable,
                str(ROOT / "sr_case_pack_report.py"),
                "--dictionary",
                str(SERBIAN_DICTIONARY),
                "--inflectional",
                str(SERBIAN_INFLECTIONAL),
                "--out",
                str(serbian_case_report),
                "--case-form-pack-out",
                str(serbian_case_form_pack),
                "--compiled-case-form-pack-out",
                str(serbian_compiled_case_form_pack),
                "--case-form-pack-limit",
                "12",
                "--max-samples",
                "10",
            ]
        )
        assert_serbian_case_pack_report(
            serbian_case_report, serbian_case_form_pack, serbian_compiled_case_form_pack
        )
        run(
            [
                sys.executable,
                str(ROOT / "de_article_case_report.py"),
                "--dictionary",
                str(GERMAN_DICTIONARY),
                "--inflectional",
                str(GERMAN_INFLECTIONAL),
                "--out",
                str(german_article_case_report),
                "--max-samples",
                "8",
            ]
        )
        assert_german_article_case_report(german_article_case_report)
        run(
            [
                sys.executable,
                str(ROOT / "es_noun_pack_report.py"),
                "--dictionary",
                str(SPANISH_DICTIONARY),
                "--inflectional",
                str(SPANISH_INFLECTIONAL),
                "--out",
                str(spanish_noun_pack_report),
                "--max-samples",
                "8",
            ]
        )
        assert_spanish_noun_pack_report(spanish_noun_pack_report)
        run(
            [
                sys.executable,
                str(ROOT / "it_noun_pack_report.py"),
                "--dictionary",
                str(ITALIAN_DICTIONARY),
                "--inflectional",
                str(ITALIAN_INFLECTIONAL),
                "--out",
                str(italian_noun_pack_report),
                "--max-samples",
                "10",
            ]
        )
        assert_italian_noun_pack_report(italian_noun_pack_report)
        run(
            [
                sys.executable,
                str(ROOT / "pt_noun_pack_report.py"),
                "--dictionary",
                str(PORTUGUESE_DICTIONARY),
                "--inflectional",
                str(PORTUGUESE_INFLECTIONAL),
                "--out",
                str(portuguese_noun_pack_report),
                "--max-samples",
                "10",
            ]
        )
        assert_portuguese_noun_pack_report(portuguese_noun_pack_report)

    print("MF2 inflection smoke pipeline passed.")


if __name__ == "__main__":
    main()
