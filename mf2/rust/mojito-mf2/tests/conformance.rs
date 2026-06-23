use std::collections::BTreeMap;
use std::fs;
use std::io::Write;
use std::path::Path;
use std::process::{Command, Stdio};

use mojito_mf2::{
    date_time_core_function_registry, format_date_core, format_date_core_to_parts,
    format_date_time_core, format_date_time_core_to_parts, format_message, format_message_to_parts,
    format_message_to_parts_with_options, format_message_with_options, format_number_core,
    format_number_core_to_parts, format_relative_time_core, format_relative_time_core_to_parts,
    format_time_core, format_time_core_to_parts, number_core_function_registry, parse_to_model,
    relative_time_core_function_registry, ArgumentValue, Arguments, BidiIsolation,
    DateTimeCoreOptions, Diagnostic, FormatOptions, FormattedPart, FunctionRegistry, MessageModel,
    NumberCoreCurrencyDisplay, NumberCoreOptions, NumberCoreSignDisplay, NumberCoreStyle,
    PartsResult, RelativeTimeCoreData, RelativeTimeCoreFormatter, RelativeTimeCoreNumeric,
    RelativeTimeCoreOptions, RelativeTimeCorePolicy, RelativeTimeCoreStyle, RelativeTimeCoreUnit,
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

fn arguments_from_json_value(value: &serde_json::Value) -> Arguments {
    value
        .as_object()
        .expect("arguments object")
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

#[test]
fn number_core_fixtures_pass() {
    let fixture: serde_json::Value =
        read_fixture(&conformance_dir().join("number-core").join("cases.json"));
    for item in fixture["formatCases"].as_array().expect("format cases") {
        let actual = format_number_core(
            argument_from_json(&item["value"]),
            &number_core_options_from_fixture(item),
        )
        .unwrap_or_else(|error| panic!("{}: {error:?}", item["name"]));
        assert_eq!(
            actual,
            item["expected"].as_str().expect("expected string"),
            "{}",
            item["name"].as_str().unwrap_or("number-core case")
        );
    }

    let reference_cases = fixture["intlReferenceCases"]
        .as_array()
        .expect("reference cases");
    let references = node_intl_number_outputs(reference_cases);
    for (index, item) in reference_cases.iter().enumerate() {
        let actual = format_number_core(
            argument_from_json(&item["value"]),
            &number_core_options_from_fixture(item),
        )
        .unwrap_or_else(|error| panic!("Intl reference {index}: {error:?}"));
        assert_eq!(actual, references[index], "Intl number reference {index}");
    }

    for item in fixture["errorCases"].as_array().expect("error cases") {
        let error = format_number_core(
            argument_from_json(&item["value"]),
            &number_core_options_from_fixture(item),
        )
        .expect_err("number-core error case should fail");
        assert_eq!(
            error.code,
            item["expectedError"].as_str().expect("expected error"),
            "{}",
            item["name"].as_str().unwrap_or("number-core error")
        );
    }

    let direct_parts_options = NumberCoreOptions {
        locale: "en-US".to_string(),
        ..NumberCoreOptions::default()
    };
    let direct_parts_value = 1234.5;
    let direct_formatted = format_number_core(direct_parts_value, &direct_parts_options)
        .expect("number-core direct parts string formats");
    let direct_parts = format_number_core_to_parts(direct_parts_value, &direct_parts_options)
        .expect("number-core direct parts formats");
    assert_eq!(
        direct_parts,
        vec![FormattedPart::Text {
            value: direct_formatted,
        }]
    );

    let registry = number_core_function_registry();
    let currency = parse_to_model("Total: {$amount :currency currency=USD}");
    assert_diagnostics_empty(Path::new("number-core registry"), &currency.diagnostics);
    let currency_result = format_with_options(
        currency.model.as_ref().expect("model exists"),
        &Arguments::new().with("amount", 1234.5),
        "en-US",
        &registry,
        BidiIsolation::None,
    )
    .expect("currency registry formats");
    assert_eq!(currency_result.value, "Total: $1,234.50");
    assert!(currency_result.errors.is_empty());

    let selector =
        parse_to_model(".input {$count :number}\n.match $count\none {{one}}\n* {{other}}");
    assert_diagnostics_empty(Path::new("number-core selector"), &selector.diagnostics);
    let selector_result = format_with_options(
        selector.model.as_ref().expect("model exists"),
        &Arguments::new().with("count", 1),
        "en",
        &registry,
        BidiIsolation::None,
    )
    .expect("number-core selectors stay portable");
    assert_eq!(selector_result.value, "one");
    assert!(selector_result.errors.is_empty());

    for item in fixture["registryCases"].as_array().expect("registry cases") {
        let parsed = parse_to_model(item["source"].as_str().expect("source"));
        assert_diagnostics_empty(
            Path::new(item["name"].as_str().unwrap_or("registry case")),
            &parsed.diagnostics,
        );
        let result = format_with_options(
            parsed.model.as_ref().expect("model exists"),
            &arguments_from_json_value(&item["arguments"]),
            item["locale"].as_str().unwrap_or("en"),
            &registry,
            BidiIsolation::None,
        )
        .unwrap_or_else(|error| {
            panic!(
                "{}: unexpected error {error:?}",
                item["name"].as_str().unwrap_or("registry case")
            )
        });
        assert_eq!(
            result.value,
            item["expected"].as_str().expect("expected"),
            "{}",
            item["name"].as_str().unwrap_or("registry case")
        );
        assert!(result.errors.is_empty());
    }

    for item in fixture["registryErrorCases"]
        .as_array()
        .expect("registry error cases")
    {
        let parsed = parse_to_model(item["source"].as_str().expect("source"));
        assert_diagnostics_empty(
            Path::new(item["name"].as_str().unwrap_or("registry error case")),
            &parsed.diagnostics,
        );
        let result = format_with_options(
            parsed.model.as_ref().expect("model exists"),
            &arguments_from_json_value(&item["arguments"]),
            item["locale"].as_str().unwrap_or("en"),
            &registry,
            BidiIsolation::None,
        )
        .unwrap_or_else(|error| {
            panic!(
                "{}: unexpected error {error:?}",
                item["name"].as_str().unwrap_or("registry error case")
            )
        });
        let actual: Vec<_> = result
            .errors
            .iter()
            .map(|error| error.code.as_str())
            .collect();
        let expected: Vec<_> = item["expectedErrors"]
            .as_array()
            .expect("expected errors")
            .iter()
            .map(|error| error.as_str().expect("expected error"))
            .collect();
        assert_eq!(
            actual,
            expected,
            "{}",
            item["name"].as_str().unwrap_or("registry error case")
        );
    }
}

#[test]
fn date_time_core_fixtures_pass() {
    let fixture: serde_json::Value =
        read_fixture(&conformance_dir().join("date-time-core").join("cases.json"));
    for item in fixture["formatCases"].as_array().expect("format cases") {
        let actual = format_date_time_core_fixture_item(item)
            .unwrap_or_else(|error| panic!("{}: {error:?}", item["name"]));
        assert_eq!(
            actual,
            item["expected"].as_str().expect("expected string"),
            "{}",
            item["name"].as_str().unwrap_or("date-time-core case")
        );
    }

    let reference_cases = fixture["intlReferenceCases"]
        .as_array()
        .expect("reference cases");
    let references = node_intl_date_time_outputs(reference_cases);
    for (index, item) in reference_cases.iter().enumerate() {
        let actual = format_date_time_core_fixture_item(item)
            .unwrap_or_else(|error| panic!("Intl reference {index}: {error:?}"));
        assert_eq!(
            actual, references[index],
            "Intl date/time reference {index}"
        );
    }

    let empty_semantic_reference_cases = Vec::new();
    let semantic_reference_cases = fixture["semanticStyleReferenceCases"]
        .as_array()
        .unwrap_or(&empty_semantic_reference_cases);
    let semantic_reference_items: Vec<_> = semantic_reference_cases
        .iter()
        .map(date_time_core_reference_item)
        .collect();
    let semantic_references = node_intl_date_time_outputs(&semantic_reference_items);
    for (index, item) in semantic_reference_cases.iter().enumerate() {
        let actual = format_date_time_core_fixture_item(item)
            .unwrap_or_else(|error| panic!("semantic style reference {index}: {error:?}"));
        assert_eq!(
            actual,
            semantic_references[index],
            "{}",
            item["name"].as_str().unwrap_or("semantic style reference")
        );
    }

    for item in fixture["errorCases"].as_array().expect("error cases") {
        let error = format_date_time_core_fixture_item(item)
            .expect_err("date-time-core error case should fail");
        assert_eq!(
            error.code,
            item["expectedError"].as_str().expect("expected error"),
            "{}",
            item["name"].as_str().unwrap_or("date-time-core error")
        );
    }

    let direct_parts_value = "2026-05-21T14:30:15Z".to_string();
    let direct_date_options = DateTimeCoreOptions {
        locale: "en-US".to_string(),
        date_style: Some("short".to_string()),
        time_zone: "UTC".to_string(),
        ..DateTimeCoreOptions::default()
    };
    let direct_time_options = DateTimeCoreOptions {
        locale: "en-US".to_string(),
        time_style: Some("short".to_string()),
        time_zone: "UTC".to_string(),
        ..DateTimeCoreOptions::default()
    };
    let direct_date_time_options = DateTimeCoreOptions {
        locale: "en-US".to_string(),
        date_style: Some("short".to_string()),
        time_style: Some("short".to_string()),
        time_zone: "UTC".to_string(),
        ..DateTimeCoreOptions::default()
    };
    assert_eq!(
        format_date_core_to_parts(&direct_parts_value, &direct_date_options)
            .expect("date direct parts"),
        vec![FormattedPart::Text {
            value: format_date_core(&direct_parts_value, &direct_date_options)
                .expect("date direct string"),
        }]
    );
    assert_eq!(
        format_time_core_to_parts(&direct_parts_value, &direct_time_options)
            .expect("time direct parts"),
        vec![FormattedPart::Text {
            value: format_time_core(&direct_parts_value, &direct_time_options)
                .expect("time direct string"),
        }]
    );
    assert_eq!(
        format_date_time_core_to_parts(&direct_parts_value, &direct_date_time_options)
            .expect("datetime direct parts"),
        vec![FormattedPart::Text {
            value: format_date_time_core(&direct_parts_value, &direct_date_time_options)
                .expect("datetime direct string"),
        }]
    );

    let registry = date_time_core_function_registry();
    let empty_registry_cases = Vec::new();
    for item in fixture["registryFormatCases"]
        .as_array()
        .unwrap_or(&empty_registry_cases)
    {
        let parsed = parse_to_model(item["source"].as_str().expect("source"));
        assert_diagnostics_empty(
            Path::new(item["name"].as_str().unwrap_or("date-time-core registry")),
            &parsed.diagnostics,
        );
        let result = format_with_options(
            parsed.model.as_ref().expect("model exists"),
            &arguments_from_json_value(&item["arguments"]),
            item["locale"].as_str().unwrap_or("en"),
            &registry,
            BidiIsolation::None,
        )
        .expect("date-time registry formats fixture");
        assert_eq!(
            result.value,
            item["expected"].as_str().expect("expected string"),
            "{}",
            item["name"].as_str().unwrap_or("date-time-core registry")
        );
        assert!(result.errors.is_empty());
    }
    for item in fixture["registryErrorCases"]
        .as_array()
        .unwrap_or(&empty_registry_cases)
    {
        let parsed = parse_to_model(item["source"].as_str().expect("source"));
        assert_diagnostics_empty(
            Path::new(
                item["name"]
                    .as_str()
                    .unwrap_or("date-time-core registry error"),
            ),
            &parsed.diagnostics,
        );
        let result = format_with_options(
            parsed.model.as_ref().expect("model exists"),
            &arguments_from_json_value(&item["arguments"]),
            item["locale"].as_str().unwrap_or("en"),
            &registry,
            BidiIsolation::None,
        )
        .expect("date-time registry errors are recoverable");
        let expected: Vec<&str> = item["expectedErrors"]
            .as_array()
            .expect("expected registry errors")
            .iter()
            .map(|value| value.as_str().expect("expected error code"))
            .collect();
        assert_eq!(
            error_codes(&result.errors),
            expected,
            "{}",
            item["name"]
                .as_str()
                .unwrap_or("date-time-core registry error")
        );
    }
    let message =
        parse_to_model("At {$instant :datetime dateStyle=full timeStyle=medium timeZone=UTC}");
    assert_diagnostics_empty(Path::new("date-time-core registry"), &message.diagnostics);
    let result = format_with_options(
        message.model.as_ref().expect("model exists"),
        &Arguments::new().with("instant", "2026-05-21T14:30:15Z"),
        "de-DE",
        &registry,
        BidiIsolation::None,
    )
    .expect("date-time registry formats");
    assert_eq!(result.value, "At Donnerstag, 21. Mai 2026 um 14:30:15");
    assert!(result.errors.is_empty());

    let string_message = parse_to_model("Hello {$name :string}");
    assert_diagnostics_empty(
        Path::new("date-time-core string"),
        &string_message.diagnostics,
    );
    let string_result = format_with_options(
        string_message.model.as_ref().expect("model exists"),
        &Arguments::new().with("name", "Mojito"),
        "en",
        &registry,
        BidiIsolation::None,
    )
    .expect("date-time registry keeps portable functions");
    assert_eq!(string_result.value, "Hello Mojito");
    assert!(string_result.errors.is_empty());
}

#[test]
fn relative_time_core_fixtures_pass() {
    let data: RelativeTimeCoreData = read_fixture(&relative_time_data_path());
    let formatter = RelativeTimeCoreFormatter::new(data.clone()).expect("relative-time data");
    let registry = relative_time_core_function_registry(data.clone()).expect("relative registry");
    let fixture: serde_json::Value = read_fixture(
        &conformance_dir()
            .join("functions")
            .join("relative-time-duration-v0.json"),
    );

    for item in fixture["cases"].as_array().expect("format cases") {
        let parsed = parse_to_model(item["source"].as_str().expect("source"));
        assert_diagnostics_empty(Path::new("relative-time fixture"), &parsed.diagnostics);
        let result = format_with_options(
            parsed.model.as_ref().expect("model exists"),
            &arguments_from_json_value(&item["arguments"]),
            item["locale"].as_str().unwrap_or("en"),
            &registry,
            BidiIsolation::None,
        )
        .unwrap_or_else(|error| panic!("{}: {error:?}", item["label"]));
        assert_eq!(
            result.value,
            item["expected"].as_str().expect("expected"),
            "{}",
            item["label"].as_str().unwrap_or("relative-time case")
        );
        assert!(result.errors.is_empty());
    }

    for item in fixture["errorCases"].as_array().expect("error cases") {
        let parsed = parse_to_model(item["source"].as_str().expect("source"));
        assert_diagnostics_empty(Path::new("relative-time error"), &parsed.diagnostics);
        let result = format_with_options(
            parsed.model.as_ref().expect("model exists"),
            &arguments_from_json_value(&item["arguments"]),
            item["locale"].as_str().unwrap_or("en"),
            &registry,
            BidiIsolation::None,
        )
        .expect("relative-time errors are recoverable");
        assert_eq!(
            error_codes(&result.errors),
            vec![item["expectedError"]["code"].as_str().expect("error code")]
        );
    }

    let direct = format_relative_time_core(
        3_600,
        &data,
        &RelativeTimeCoreOptions {
            locale: "en".to_string(),
            style: RelativeTimeCoreStyle::Narrow,
            numeric: RelativeTimeCoreNumeric::Always,
            policy: RelativeTimeCorePolicy::Precise,
            unit: RelativeTimeCoreUnit::Auto,
        },
    )
    .expect("direct relative-time formats");
    assert_eq!(direct, "in 1h");

    let negative_zero = format_relative_time_core(
        -0.0_f64,
        &data,
        &RelativeTimeCoreOptions {
            locale: "en".to_string(),
            style: RelativeTimeCoreStyle::Long,
            numeric: RelativeTimeCoreNumeric::Always,
            policy: RelativeTimeCorePolicy::Precise,
            unit: RelativeTimeCoreUnit::Second,
        },
    )
    .expect("direct relative-time negative zero formats");
    assert_eq!(negative_zero, "0 seconds ago");

    let after_tomorrow = format_relative_time_core(
        172_800,
        &data,
        &RelativeTimeCoreOptions {
            locale: "fr".to_string(),
            style: RelativeTimeCoreStyle::Long,
            numeric: RelativeTimeCoreNumeric::Auto,
            policy: RelativeTimeCorePolicy::Precise,
            unit: RelativeTimeCoreUnit::Day,
        },
    )
    .expect("direct relative-time after tomorrow formats");
    assert_eq!(after_tomorrow, "après-demain");

    let parts = format_relative_time_core_to_parts(
        -86_400,
        &data,
        &RelativeTimeCoreOptions {
            locale: "en".to_string(),
            style: RelativeTimeCoreStyle::Long,
            numeric: RelativeTimeCoreNumeric::Auto,
            policy: RelativeTimeCorePolicy::Precise,
            unit: RelativeTimeCoreUnit::Day,
        },
    )
    .expect("relative-time parts format");
    assert_eq!(
        parts,
        vec![FormattedPart::Text {
            value: "yesterday".to_string(),
        }]
    );
    let huge_error = format_relative_time_core(
        "1e30",
        &data,
        &RelativeTimeCoreOptions {
            locale: "en".to_string(),
            style: RelativeTimeCoreStyle::Narrow,
            numeric: RelativeTimeCoreNumeric::Always,
            policy: RelativeTimeCorePolicy::Precise,
            unit: RelativeTimeCoreUnit::Auto,
        },
    )
    .expect_err("huge relative-time quantity should fail");
    assert_eq!(huge_error.code, "bad-operand");

    let reference_cases = vec![
        serde_json::json!({"locale":"en","style":"long","numeric":"auto","unit":"day","value":-1,"seconds":-86_400}),
        serde_json::json!({"locale":"en","style":"long","numeric":"always","unit":"day","value":1,"seconds":86_400}),
        serde_json::json!({"locale":"ja","style":"narrow","numeric":"always","unit":"minute","value":3,"seconds":180}),
        serde_json::json!({"locale":"en","style":"narrow","numeric":"always","unit":"minute","value":-1,"seconds":-60}),
        serde_json::json!({"locale":"fr","style":"long","numeric":"auto","unit":"day","value":2,"seconds":172_800}),
    ];
    let references = node_intl_relative_time_outputs(&reference_cases);
    for (index, item) in reference_cases.iter().enumerate() {
        let actual = formatter
            .format(
                item["seconds"].as_i64().expect("seconds"),
                &RelativeTimeCoreOptions {
                    locale: item["locale"].as_str().expect("locale").to_string(),
                    style: RelativeTimeCoreStyle::from_name(item["style"].as_str().expect("style"))
                        .expect("style"),
                    numeric: RelativeTimeCoreNumeric::from_name(
                        item["numeric"].as_str().expect("numeric"),
                    )
                    .expect("numeric"),
                    policy: RelativeTimeCorePolicy::Precise,
                    unit: RelativeTimeCoreUnit::from_name(item["unit"].as_str().expect("unit"))
                        .expect("unit"),
                },
            )
            .unwrap_or_else(|error| panic!("Intl relative-time reference {index}: {error:?}"));
        assert_eq!(
            actual, references[index],
            "Intl relative-time reference {index}"
        );
    }
}

#[test]
fn relative_time_core_rejects_unusable_pattern_sets() {
    let empty_data: RelativeTimeCoreData = serde_json::from_value(serde_json::json!({
        "localeMap": { "en": "rt" },
        "patternSets": [{ "id": "rt", "data": {} }]
    }))
    .expect("relative-time data shape");
    let error = RelativeTimeCoreFormatter::new(empty_data).expect_err("empty pattern data");
    assert_eq!(error.code, "missing-locale-data");

    let empty_id: RelativeTimeCoreData = serde_json::from_value(serde_json::json!({
        "localeMap": { "en": "rt" },
        "patternSets": [{
            "id": "",
            "data": { "short": { "second": { "future": { "other": "in {0} sec." } } } }
        }]
    }))
    .expect("relative-time data shape");
    let error = RelativeTimeCoreFormatter::new(empty_id).expect_err("empty pattern id");
    assert_eq!(error.code, "missing-locale-data");
}

fn conformance_dir() -> std::path::PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../conformance/fixtures")
        .canonicalize()
        .expect("conformance fixture directory exists")
}

fn relative_time_data_path() -> std::path::PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../cldr/generated/relative-time/all/relative_time.json")
        .canonicalize()
        .expect("relative-time data exists")
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

fn number_core_options_from_fixture(item: &serde_json::Value) -> NumberCoreOptions {
    let raw_options = item["options"].as_object();
    let mut options = NumberCoreOptions {
        locale: item["locale"].as_str().unwrap_or("en-US").to_string(),
        ..NumberCoreOptions::default()
    };
    if let Some(style) = raw_options
        .and_then(|options| options.get("style"))
        .and_then(serde_json::Value::as_str)
    {
        options.style = match style {
            "number" => NumberCoreStyle::Number,
            "integer" => NumberCoreStyle::Integer,
            "percent" => NumberCoreStyle::Percent,
            "currency" => NumberCoreStyle::Currency,
            _ => panic!("unsupported number-core style {style}"),
        };
    }
    options.currency = raw_options
        .and_then(|options| options.get("currency"))
        .and_then(serde_json::Value::as_str)
        .map(ToString::to_string);
    if let Some(display) = raw_options
        .and_then(|options| options.get("currencyDisplay"))
        .and_then(serde_json::Value::as_str)
    {
        options.currency_display = match display {
            "symbol" => NumberCoreCurrencyDisplay::Symbol,
            "narrowSymbol" => NumberCoreCurrencyDisplay::NarrowSymbol,
            "code" => NumberCoreCurrencyDisplay::Code,
            _ => panic!("unsupported currencyDisplay {display}"),
        };
    }
    if let Some(sign_display) = raw_options
        .and_then(|options| options.get("signDisplay"))
        .and_then(serde_json::Value::as_str)
    {
        options.sign_display = match sign_display {
            "auto" => NumberCoreSignDisplay::Auto,
            "always" => NumberCoreSignDisplay::Always,
            "never" => NumberCoreSignDisplay::Never,
            _ => panic!("unsupported signDisplay {sign_display}"),
        };
    }
    if let Some(use_grouping) = raw_options
        .and_then(|options| options.get("useGrouping"))
        .and_then(serde_json::Value::as_bool)
    {
        options.use_grouping = use_grouping;
    }
    options.minimum_fraction_digits = raw_options
        .and_then(|options| options.get("minimumFractionDigits"))
        .and_then(serde_json::Value::as_u64)
        .map(|value| value as usize);
    options.maximum_fraction_digits = raw_options
        .and_then(|options| options.get("maximumFractionDigits"))
        .and_then(serde_json::Value::as_u64)
        .map(|value| value as usize);
    options
}

fn date_time_core_options_from_fixture(item: &serde_json::Value) -> DateTimeCoreOptions {
    let raw_options = item["options"].as_object();
    DateTimeCoreOptions {
        locale: item["locale"].as_str().unwrap_or("en-US").to_string(),
        style: date_time_style(raw_options, "style").unwrap_or_else(|| "medium".to_string()),
        date_style: date_time_style(raw_options, "dateStyle"),
        time_style: date_time_style(raw_options, "timeStyle"),
        length: date_time_style(raw_options, "length"),
        precision: date_time_style(raw_options, "precision"),
        date_length: date_time_style(raw_options, "dateLength"),
        time_precision: date_time_style(raw_options, "timePrecision"),
        skeleton: date_time_style(raw_options, "skeleton"),
        hour_cycle: date_time_style(raw_options, "hourCycle"),
        time_zone: raw_options
            .and_then(|options| options.get("timeZone"))
            .and_then(serde_json::Value::as_str)
            .unwrap_or("UTC")
            .to_string(),
        calendar: raw_options
            .and_then(|options| options.get("calendar"))
            .and_then(serde_json::Value::as_str)
            .unwrap_or("")
            .to_string(),
    }
}

fn date_time_style(
    raw_options: Option<&serde_json::Map<String, serde_json::Value>>,
    name: &str,
) -> Option<String> {
    raw_options
        .and_then(|options| options.get(name))
        .and_then(serde_json::Value::as_str)
        .map(ToString::to_string)
}

fn format_date_time_core_fixture_item(item: &serde_json::Value) -> Result<String, Diagnostic> {
    let options = date_time_core_options_from_fixture(item);
    let value = date_time_core_fixture_value(&item["value"]);
    match item["kind"].as_str().expect("date/time kind") {
        "date" => format_date_core(&value, &options),
        "time" => format_time_core(&value, &options),
        "datetime" => format_date_time_core(&value, &options),
        kind => panic!("unsupported date/time kind {kind}"),
    }
}

fn date_time_core_fixture_value(value: &serde_json::Value) -> String {
    value
        .as_str()
        .map(ToString::to_string)
        .unwrap_or_else(|| value.to_string())
}

fn date_time_core_reference_item(item: &serde_json::Value) -> serde_json::Value {
    serde_json::json!({
        "kind": item["kind"],
        "locale": item["locale"],
        "value": item["value"],
        "options": item["referenceOptions"],
    })
}

fn node_intl_number_outputs(cases: &[serde_json::Value]) -> Vec<String> {
    node_json_outputs(
        cases,
        r#"
const fs = require("fs");
const cases = JSON.parse(fs.readFileSync(0, "utf8"));
function intlOptions(options) {
  if (options.style === "number") return {};
  if (options.style === "percent") return { style: "percent" };
  if (options.style === "currency") return { style: "currency", currency: options.currency };
  throw new Error("Unsupported Intl reference style: " + options.style);
}
process.stdout.write(JSON.stringify(cases.map((item) =>
  new Intl.NumberFormat(item.locale, intlOptions(item.options || {})).format(item.value)
)));
"#,
    )
}

fn node_intl_date_time_outputs(cases: &[serde_json::Value]) -> Vec<String> {
    node_json_outputs(
        cases,
        r#"
const fs = require("fs");
const cases = JSON.parse(fs.readFileSync(0, "utf8"));
process.stdout.write(JSON.stringify(cases.map((item) =>
  new Intl.DateTimeFormat(item.locale, { timeZone: "UTC", ...(item.options || {}) }).format(new Date(item.value))
)));
"#,
    )
}

fn node_intl_relative_time_outputs(cases: &[serde_json::Value]) -> Vec<String> {
    node_json_outputs(
        cases,
        r#"
const fs = require("fs");
const cases = JSON.parse(fs.readFileSync(0, "utf8"));
process.stdout.write(JSON.stringify(cases.map((item) =>
  new Intl.RelativeTimeFormat(item.locale, { style: item.style, numeric: item.numeric }).format(item.value, item.unit)
)));
"#,
    )
}

fn node_json_outputs(cases: &[serde_json::Value], script: &str) -> Vec<String> {
    let mut child = Command::new("node")
        .arg("-e")
        .arg(script)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("node starts for Intl reference");
    child
        .stdin
        .as_mut()
        .expect("node stdin")
        .write_all(
            serde_json::to_string(cases)
                .expect("cases serialize")
                .as_bytes(),
        )
        .expect("write node input");
    let output = child.wait_with_output().expect("node exits");
    assert!(
        output.status.success(),
        "node Intl reference failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    serde_json::from_slice(&output.stdout).expect("node output parses")
}
