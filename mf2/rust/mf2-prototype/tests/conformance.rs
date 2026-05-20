use std::collections::BTreeMap;
use std::fs;
use std::path::Path;

use mf2_prototype::{
    canonical_locale_key, format_model_to_parts_with_locale, format_model_with_locale,
    format_model_with_locale_and_bidi, locale_lookup_chain, parse_to_model, BidiIsolation,
    Diagnostic, FormattedPart, MessageModel,
};
use serde::Deserialize;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SourceToModelFixture {
    source: String,
    expected_model: MessageModel,
    #[serde(default)]
    format_cases: Vec<FormatCase>,
    #[serde(default)]
    parts_cases: Vec<PartsCase>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InvalidSourceFixture {
    source: String,
    expected_diagnostics: Vec<ExpectedDiagnostic>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FormatErrorFixture {
    model: MessageModel,
    #[serde(default = "default_locale")]
    locale: String,
    arguments: BTreeMap<String, serde_json::Value>,
    expected_error: ExpectedDiagnostic,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LocaleKeyFixture {
    canonical: Vec<LocaleCanonicalCase>,
    lookup_chains: Vec<LocaleLookupChainCase>,
}

#[derive(Debug, Deserialize)]
struct LocaleCanonicalCase {
    source: String,
    expected: String,
}

#[derive(Debug, Deserialize)]
struct LocaleLookupChainCase {
    source: String,
    expected: Vec<String>,
}

#[derive(Debug, Deserialize)]
struct ExpectedDiagnostic {
    code: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FormatCase {
    #[serde(default = "default_locale")]
    locale: String,
    #[serde(default)]
    bidi_isolation: Option<String>,
    arguments: BTreeMap<String, serde_json::Value>,
    expected: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartsCase {
    #[serde(default = "default_locale")]
    locale: String,
    arguments: BTreeMap<String, serde_json::Value>,
    expected: Vec<FormattedPart>,
}

#[test]
fn source_to_model_fixtures_pass() {
    let fixture_dir = conformance_dir().join("source-to-model");
    for fixture_path in fixture_paths(&fixture_dir) {
        let fixture: SourceToModelFixture = read_fixture(&fixture_path);
        let result = parse_to_model(&fixture.source);
        assert_diagnostics_empty(&fixture_path, &result.diagnostics);
        assert_eq!(
            result.model.as_ref(),
            Some(&fixture.expected_model),
            "model mismatch for {}",
            fixture_path.display()
        );

        for format_case in fixture.format_cases {
            let formatted = format_model_with_locale_and_bidi(
                result.model.as_ref().expect("model exists"),
                &format_case.arguments,
                &format_case.locale,
                BidiIsolation::from_name(format_case.bidi_isolation.as_deref()),
            )
            .unwrap_or_else(|diagnostic| {
                panic!(
                    "format failed for {}: {:?}",
                    fixture_path.display(),
                    diagnostic
                )
            });
            assert_eq!(
                formatted,
                format_case.expected,
                "format mismatch for {}",
                fixture_path.display()
            );
        }
        for parts_case in fixture.parts_cases {
            let actual = format_model_to_parts_with_locale(
                result.model.as_ref().expect("model exists"),
                &parts_case.arguments,
                &parts_case.locale,
            )
            .unwrap_or_else(|diagnostic| {
                panic!(
                    "format parts failed for {}: {:?}",
                    fixture_path.display(),
                    diagnostic
                )
            });
            assert_eq!(
                actual,
                parts_case.expected,
                "format parts mismatch for {}",
                fixture_path.display()
            );
        }
    }
}

#[test]
fn format_error_fixtures_return_expected_diagnostics() {
    let fixture_dir = conformance_dir().join("format-errors");
    for fixture_path in fixture_paths(&fixture_dir) {
        let fixture: FormatErrorFixture = read_fixture(&fixture_path);
        let diagnostic =
            format_model_with_locale(&fixture.model, &fixture.arguments, &fixture.locale)
                .expect_err("format fixture should fail");
        assert_eq!(
            diagnostic.code,
            fixture.expected_error.code,
            "format diagnostic mismatch for {}",
            fixture_path.display()
        );
    }
}

#[test]
fn invalid_source_fixtures_emit_expected_diagnostics() {
    let fixture_dir = conformance_dir().join("invalid-source");
    for fixture_path in fixture_paths(&fixture_dir) {
        let fixture: InvalidSourceFixture = read_fixture(&fixture_path);
        let result = parse_to_model(&fixture.source);
        let actual_codes: Vec<_> = result
            .diagnostics
            .iter()
            .map(|diagnostic| diagnostic.code.as_str())
            .collect();
        let expected_codes: Vec<_> = fixture
            .expected_diagnostics
            .iter()
            .map(|diagnostic| diagnostic.code.as_str())
            .collect();
        assert_eq!(
            actual_codes,
            expected_codes,
            "diagnostic mismatch for {}",
            fixture_path.display()
        );
        assert!(
            result.model.is_none(),
            "invalid fixture produced a model: {}",
            fixture_path.display()
        );
    }
}

#[test]
fn locale_key_fixtures_pass() {
    let fixture: LocaleKeyFixture = read_fixture(&conformance_dir().join("locale-key/cases.json"));
    for item in fixture.canonical {
        assert_eq!(canonical_locale_key(&item.source), item.expected);
    }
    for item in fixture.lookup_chains {
        assert_eq!(locale_lookup_chain(&item.source), item.expected);
    }
}

fn conformance_dir() -> std::path::PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../conformance/fixtures")
        .canonicalize()
        .expect("conformance fixture directory exists")
}

fn fixture_paths(dir: &Path) -> Vec<std::path::PathBuf> {
    let mut paths: Vec<_> = fs::read_dir(dir)
        .unwrap_or_else(|error| panic!("failed to read {}: {error}", dir.display()))
        .map(|entry| entry.expect("fixture entry").path())
        .filter(|path| {
            path.extension()
                .is_some_and(|extension| extension == "json")
        })
        .collect();
    paths.sort();
    paths
}

fn read_fixture<T: for<'de> Deserialize<'de>>(path: &Path) -> T {
    let contents = fs::read_to_string(path)
        .unwrap_or_else(|error| panic!("failed to read {}: {error}", path.display()));
    serde_json::from_str(&contents)
        .unwrap_or_else(|error| panic!("failed to parse {}: {error}", path.display()))
}

fn assert_diagnostics_empty(path: &Path, diagnostics: &[Diagnostic]) {
    assert!(
        diagnostics.is_empty(),
        "unexpected diagnostics for {}: {:?}",
        path.display(),
        diagnostics
    );
}

fn default_locale() -> String {
    "en".to_string()
}
