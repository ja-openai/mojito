use std::collections::BTreeMap;
use std::fs;
use std::path::Path;

use mojito_mf2::{
    cardinal_plural_categories, format_message, format_message_to_parts,
    format_message_to_parts_with_options, format_message_with_options, parse_to_model,
    ArgumentValue, Arguments, BidiIsolation, Diagnostic, FormatOptions, FormattedPart,
    FunctionRegistry, MessageModel, PartsResult,
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
fn public_runtime_api_exposes_result_and_parts() {
    let result = parse_to_model("Hello {$name}");
    assert_diagnostics_empty(Path::new("public-api"), &result.diagnostics);
    let model = result.model.as_ref().expect("model exists");
    let arguments = Arguments::new().with("name", "Mojito");

    let formatted = format_message(model, &arguments).unwrap();
    assert_eq!(formatted.value, "Hello Mojito");
    assert!(formatted.is_ok());
    assert!(!formatted.has_errors());
    let parts = format_message_to_parts(model, &arguments).unwrap();
    assert_eq!(
        parts.parts,
        vec![
            FormattedPart::Text {
                value: "Hello ".to_string(),
            },
            FormattedPart::Expression {
                value: "Mojito".to_string(),
                attributes: None,
                dir: None,
            },
        ]
    );

    assert!(parts.is_ok());
    assert!(!parts.has_errors());
}

#[test]
fn numeric_exact_matches_outrank_plural_categories() {
    let result = format_source(
        ".input {$count :integer}\n.match $count\n\
         * {{fallback}}\none {{plural one}}\n1 {{exact one}}",
        "en",
        Arguments::new().with("count", 1),
    );

    assert_eq!(result.value, "exact one");
    assert!(result.errors.is_empty(), "{:?}", result.errors);
}

#[test]
fn number_exact_matches_use_canonical_integer_serialization() {
    let result = format_source(
        ".input {$value :number}\n.match $value\n\
         1.0 {{decimal-spelling}}\n1 {{integer-spelling}}\n* {{fallback}}",
        "en",
        Arguments::new().with("value", 1),
    );

    assert_eq!(result.value, "integer-spelling");
    assert!(result.errors.is_empty(), "{:?}", result.errors);
}

#[test]
fn number_reannotations_inherit_resolved_options() {
    let result = format_source(
        ".local $number = {1.2 :number minimumFractionDigits=2}\n\
         {{Value {$number :number}}}",
        "en",
        Arguments::new(),
    );

    assert_eq!(result.value, "Value 1.20");
    assert!(result.errors.is_empty(), "{:?}", result.errors);
}

#[test]
fn number_reannotations_use_semantic_operands_before_rounded_display() {
    let result = format_source(
        ".local $number = {1.29 :number maximumFractionDigits=1}\n\
         {{Value {$number :number maximumFractionDigits=2}}}",
        "en",
        Arguments::new(),
    );

    assert_eq!(result.value, "Value 1.29");
    assert!(result.errors.is_empty(), "{:?}", result.errors);
}

#[test]
fn exact_selection_inherits_options_from_resolved_number_chains() {
    let result = format_source(
        ".local $rounded = {1.29 :number maximumFractionDigits=1 signDisplay=always}\n\
         .local $selected = {$rounded :number}\n\
         .match $selected\n\
         1.3 {{rounded}}\n1.29 {{raw}}\n* {{fallback}}",
        "en",
        Arguments::new(),
    );

    assert_eq!(result.value, "rounded");
    assert!(result.errors.is_empty(), "{:?}", result.errors);
}

#[test]
fn offset_uses_the_semantic_decimal_operand_before_rounded_display() {
    let result = format_source(
        ".local $number = {-1.9 :number maximumFractionDigits=0}\n\
         .local $offset = {$number :offset add=1}\n\
         {{{$offset}}}",
        "en",
        Arguments::new(),
    );

    assert_eq!(result.value, "-0.9");
    assert!(result.errors.is_empty(), "{:?}", result.errors);

    let reannotated = format_source(
        ".local $number = {-1.9 :number maximumFractionDigits=0}\n\
         .local $offset = {$number :offset add=1}\n\
         .local $copy = {$offset :number maximumFractionDigits=1}\n\
         {{{$copy}}}",
        "en",
        Arguments::new(),
    );

    assert_eq!(reannotated.value, "-0.9");
    assert!(reannotated.errors.is_empty(), "{:?}", reannotated.errors);
}

#[test]
fn number_maximum_fraction_digits_rounds_display() {
    let result = format_source(
        "Value {1.29 :number maximumFractionDigits=1}",
        "en",
        Arguments::new(),
    );

    assert_eq!(result.value, "Value 1.3");
    assert!(result.errors.is_empty(), "{:?}", result.errors);
}

#[test]
fn excessive_maximum_fraction_digits_recovers_with_bad_option() {
    let result = format_source(
        "Value {1 :number maximumFractionDigits=65536}",
        "en",
        Arguments::new(),
    );

    assert_eq!(result.value, "Value {|1|}");
    assert_eq!(error_codes(&result.errors), vec!["bad-option"]);
}

#[test]
fn excessive_minimum_fraction_digits_recovers_with_bad_option() {
    let result = format_source(
        "Value {1 :number minimumFractionDigits=65536}",
        "en",
        Arguments::new(),
    );

    assert_eq!(result.value, "Value {|1|}");
    assert_eq!(error_codes(&result.errors), vec!["bad-option"]);
}

#[test]
fn inherited_excessive_fraction_digits_report_during_selection() {
    fn permissive_number_formatter(
        call: mojito_mf2::FunctionCall<'_>,
    ) -> Result<String, Diagnostic> {
        Ok(call.value().to_string())
    }

    let parsed = parse_to_model(
        ".local $source = {1 :number minimumFractionDigits=65536}\n\
         .local $selected = {$source :number}\n\
         .match $selected\n\
         one {{one}}\n* {{fallback}}",
    );
    assert!(parsed.diagnostics.is_empty(), "{:?}", parsed.diagnostics);
    let registry =
        FunctionRegistry::portable().with_function("number", permissive_number_formatter);
    let result = format_with_options(
        parsed.model.as_ref().expect("model exists"),
        &Arguments::new(),
        "en",
        &registry,
        BidiIsolation::None,
    )
    .expect("format succeeds");

    assert_eq!(result.value, "fallback");
    assert_eq!(
        error_codes(&result.errors),
        vec!["bad-option", "bad-selector"]
    );
}

#[test]
fn invalid_numeric_variant_keys_report_and_do_not_stop_selection() {
    let result = format_source(
        ".input {$value :number}\n.match $value\n\
         horse {{invalid}}\n1 {{valid-exact}}\n* {{fallback}}",
        "en",
        Arguments::new().with("value", 1),
    );

    assert_eq!(result.value, "valid-exact");
    assert_eq!(error_codes(&result.errors), vec!["bad-variant-key"]);
}

#[test]
fn cardinal_plural_categories_match_every_existing_generated_rule() {
    let path =
        Path::new(env!("CARGO_MANIFEST_DIR")).join("../../cldr/generated/all/plural_rules.json");
    let data: serde_json::Value = serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap();
    let cardinal = &data["cardinal"];
    let rules: BTreeMap<_, _> = cardinal["rules"]
        .as_array()
        .unwrap()
        .iter()
        .map(|rule| {
            let categories: Vec<_> = rule["categories"]
                .as_array()
                .unwrap()
                .iter()
                .map(|category| category["category"].as_str().unwrap())
                .collect();
            (rule["id"].as_str().unwrap(), categories)
        })
        .collect();

    for (locale, rule) in cardinal["locales"].as_object().unwrap() {
        assert_eq!(
            cardinal_plural_categories(locale),
            Some(rules[rule.as_str().unwrap()].as_slice()),
            "wrong cardinal categories for {locale}",
        );
    }
    assert_eq!(
        cardinal_plural_categories("sr-Latn"),
        Some(["one", "few", "other"].as_slice())
    );
    assert_eq!(cardinal_plural_categories("zz"), None);
}

#[test]
fn format_options_can_recover_missing_argument_values() {
    let result = parse_to_model("Hello {$name}");
    assert_diagnostics_empty(Path::new("public-api"), &result.diagnostics);
    let model = result.model.as_ref().expect("model exists");
    let recover_missing = |context: mojito_mf2::RecoveryContext<'_>| {
        Some(format!(
            "[missing {}]",
            context.variable_name.unwrap_or("value")
        ))
    };
    let options = FormatOptions::new("en").on_missing_argument(&recover_missing);

    let formatted = format_message_with_options(model, &Arguments::new(), &options).unwrap();
    assert_eq!(formatted.value, "Hello [missing name]");
    assert!(formatted.has_errors());
    let codes: Vec<&str> = formatted
        .errors
        .iter()
        .map(|error| error.code.as_str())
        .collect();
    assert_eq!(codes, vec!["unresolved-variable"]);
}

#[test]
fn recovery_callbacks_handle_empty_and_declined_values() {
    let result = parse_to_model("Hello {$name}");
    assert_diagnostics_empty(Path::new("public-api"), &result.diagnostics);
    let model = result.model.as_ref().expect("model exists");
    let empty_recovery = |_context: mojito_mf2::RecoveryContext<'_>| Some(String::new());
    let empty_options = FormatOptions::new("en").on_missing_argument(&empty_recovery);

    let empty_formatted =
        format_message_with_options(model, &Arguments::new(), &empty_options).unwrap();
    assert_eq!(empty_formatted.value, "Hello ");
    assert_eq!(
        error_codes(&empty_formatted.errors),
        vec!["unresolved-variable"]
    );

    let empty_parts =
        format_message_to_parts_with_options(model, &Arguments::new(), &empty_options).unwrap();
    assert_eq!(
        empty_parts.parts,
        vec![
            FormattedPart::Text {
                value: "Hello ".to_string(),
            },
            FormattedPart::Fallback {
                source: "$name".to_string(),
                value: Some(String::new()),
            },
        ]
    );

    let declined_recovery = |_context: mojito_mf2::RecoveryContext<'_>| None;
    let declined_options = FormatOptions::new("en").on_missing_argument(&declined_recovery);
    let declined_formatted =
        format_message_with_options(model, &Arguments::new(), &declined_options).unwrap();
    assert_eq!(declined_formatted.value, "Hello {$name}");

    let integer = parse_to_model("Hello {$name :integer}");
    assert_diagnostics_empty(Path::new("public-api"), &integer.diagnostics);
    let integer_model = integer.model.as_ref().expect("model exists");
    let empty_format_options = FormatOptions::new("en").on_format_error(&empty_recovery);
    let bad_arguments = Arguments::new().with("name", "abc");
    let empty_format_error =
        format_message_with_options(integer_model, &bad_arguments, &empty_format_options).unwrap();
    assert_eq!(empty_format_error.value, "Hello ");
    assert_eq!(error_codes(&empty_format_error.errors), vec!["bad-operand"]);

    let empty_format_parts =
        format_message_to_parts_with_options(integer_model, &bad_arguments, &empty_format_options)
            .unwrap();
    assert_eq!(
        empty_format_parts.parts,
        vec![
            FormattedPart::Text {
                value: "Hello ".to_string(),
            },
            FormattedPart::Fallback {
                source: "$name".to_string(),
                value: Some(String::new()),
            },
        ]
    );
}

#[test]
fn unsupported_default_function_recovers_with_diagnostic() {
    let result = parse_to_model("Total: {$amount :currency currency=USD}");
    assert_diagnostics_empty(Path::new("public-api"), &result.diagnostics);
    let model = result.model.as_ref().expect("model exists");
    let arguments = Arguments::new().with("amount", 42);

    let formatted = format_message(model, arguments).unwrap();

    assert_eq!(formatted.value, "Total: {$amount}");
    assert!(formatted.has_errors());
    let codes: Vec<&str> = formatted
        .errors
        .iter()
        .map(|error| error.code.as_str())
        .collect();
    assert_eq!(codes, vec!["unknown-function"]);
}

fn error_codes(errors: &[Diagnostic]) -> Vec<&str> {
    errors.iter().map(|error| error.code.as_str()).collect()
}

fn format_source(source: &str, locale: &str, arguments: Arguments) -> mojito_mf2::FormatResult {
    let parsed = parse_to_model(source);
    assert!(parsed.diagnostics.is_empty(), "{:?}", parsed.diagnostics);
    let model = parsed.model.as_ref().expect("model exists");
    let options = FormatOptions::new(locale);
    format_message_with_options(model, arguments, &options).expect("format succeeds")
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
            let formatted = format_with_options(
                result.model.as_ref().expect("model exists"),
                &arguments_from_json(&format_case.arguments),
                &format_case.locale,
                &FunctionRegistry::default(),
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
                formatted.value,
                format_case.expected,
                "format mismatch for {}",
                fixture_path.display()
            );
            assert!(
                formatted.errors.is_empty(),
                "format errors for {}: {:?}",
                fixture_path.display(),
                formatted.errors
            );
        }
        for parts_case in fixture.parts_cases {
            let actual = format_parts_with_options(
                result.model.as_ref().expect("model exists"),
                &arguments_from_json(&parts_case.arguments),
                &parts_case.locale,
                &FunctionRegistry::default(),
            )
            .unwrap_or_else(|diagnostic| {
                panic!(
                    "format parts failed for {}: {:?}",
                    fixture_path.display(),
                    diagnostic
                )
            });
            assert_eq!(
                actual.parts,
                parts_case.expected,
                "format parts mismatch for {}",
                fixture_path.display()
            );
            assert!(
                actual.errors.is_empty(),
                "parts errors for {}: {:?}",
                fixture_path.display(),
                actual.errors
            );
        }
    }
}

#[test]
fn format_error_fixtures_return_expected_diagnostics() {
    let fixture_dir = conformance_dir().join("format-errors");
    for fixture_path in fixture_paths(&fixture_dir) {
        let fixture: FormatErrorFixture = read_fixture(&fixture_path);
        let actual = format_with_options(
            &fixture.model,
            &arguments_from_json(&fixture.arguments),
            &fixture.locale,
            &FunctionRegistry::default(),
            BidiIsolation::None,
        );
        let actual_code = match actual {
            Ok(result) => result.errors.first().map(|error| error.code.clone()),
            Err(diagnostic) => Some(diagnostic.code),
        };
        assert_eq!(
            actual_code.as_deref(),
            Some(fixture.expected_error.code.as_str()),
            "format diagnostic mismatch for {}",
            fixture_path.display()
        );
    }
}

fn format_with_options(
    model: &MessageModel,
    arguments: &Arguments,
    locale: &str,
    functions: &FunctionRegistry,
    bidi_isolation: BidiIsolation,
) -> Result<mojito_mf2::FormatResult, Diagnostic> {
    let options = FormatOptions::new(locale)
        .with_functions(functions)
        .with_bidi_isolation(bidi_isolation);
    format_message_with_options(model, arguments, &options)
}

fn format_parts_with_options(
    model: &MessageModel,
    arguments: &Arguments,
    locale: &str,
    functions: &FunctionRegistry,
) -> Result<PartsResult, Diagnostic> {
    let options = FormatOptions::new(locale).with_functions(functions);
    mojito_mf2::format_message_to_parts_with_options(model, arguments, &options)
}

fn arguments_from_json(values: &BTreeMap<String, serde_json::Value>) -> Arguments {
    values
        .iter()
        .map(|(name, value)| (name.clone(), argument_from_json(value)))
        .collect()
}

fn argument_from_json(value: &serde_json::Value) -> ArgumentValue {
    match value {
        serde_json::Value::String(value) => ArgumentValue::from(value.clone()),
        serde_json::Value::Number(value) => ArgumentValue::number(value),
        serde_json::Value::Bool(value) => ArgumentValue::from(*value),
        serde_json::Value::Null => ArgumentValue::Null,
        other => ArgumentValue::from(other.to_string()),
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
