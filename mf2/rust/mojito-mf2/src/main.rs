use std::collections::BTreeMap;
use std::env;
use std::fs;
use std::hint::black_box;
use std::path::{Path, PathBuf};
use std::process;
use std::sync::OnceLock;
use std::time::Instant;

use mojito_mf2::{
    format_message_to_parts_with_options, format_message_with_options, parse_to_model,
    ArgumentValue, Arguments, BidiIsolation, Diagnostic, FormatOptions, FormatResult,
    FormattedPart, FunctionRegistry, MessageModel, PartsResult,
};
use serde::{Deserialize, Serialize};

mod unicode_tests;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SourceFixture {
    source: String,
    expected_model: Option<MessageModel>,
    #[serde(default)]
    format_cases: Vec<FormatCase>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SourceConformanceFixture {
    source: String,
    expected_model: MessageModel,
    #[serde(default)]
    format_cases: Vec<ExpectedFormatCase>,
    #[serde(default)]
    parts_cases: Vec<PartsCase>,
    #[serde(default)]
    fallback_cases: Vec<FallbackCase>,
    #[serde(default)]
    fallback_parts_cases: Vec<FallbackPartsCase>,
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
    arguments: BTreeMap<String, serde_json::Value>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ExpectedFormatCase {
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

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FallbackCase {
    #[serde(default = "default_locale")]
    locale: String,
    #[serde(default)]
    bidi_isolation: Option<String>,
    arguments: BTreeMap<String, serde_json::Value>,
    expected: String,
    expected_errors: Vec<ExpectedDiagnostic>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct FallbackPartsCase {
    #[serde(default = "default_locale")]
    locale: String,
    arguments: BTreeMap<String, serde_json::Value>,
    expected: Vec<FormattedPart>,
    expected_errors: Vec<ExpectedDiagnostic>,
}

#[derive(Debug, Deserialize)]
struct LocaleKeyFixture {
    canonical: Vec<LocaleCanonicalCase>,
    #[serde(rename = "lookupChains")]
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
#[serde(rename_all = "camelCase")]
struct SourceOnlyFixture {
    source: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct EditorRequest {
    source: String,
    #[serde(default = "default_locale")]
    locale: String,
    #[serde(default)]
    bidi_isolation: Option<String>,
    #[serde(default)]
    arguments: BTreeMap<String, serde_json::Value>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct EditorResponse {
    model: Option<MessageModel>,
    diagnostics: Vec<Diagnostic>,
    output: Option<String>,
    parts: Vec<FormattedPart>,
    format_errors: Vec<Diagnostic>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct PluralMetadataResponse {
    locale: String,
    select: String,
    categories: Vec<PluralCategoryMetadata>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct PluralCategoryMetadata {
    category: String,
    examples: Vec<String>,
}

fn main() {
    let mut args = env::args().skip(1);
    let Some(command) = args.next() else {
        usage_and_exit();
    };

    match command.as_str() {
        "compile" => {
            let path = next_required_arg(&mut args);
            compile(&read_to_string(&path));
        }
        "format-first-case" => {
            let path = next_required_arg(&mut args);
            format_first_case(&read_to_string(&path));
        }
        "editor-json" => {
            let path = next_required_arg(&mut args);
            editor_json(&read_to_string(&path));
        }
        "plural-json" => {
            let locale = next_required_arg(&mut args);
            plural_json(&locale);
        }
        "conformance" => {
            let path = args
                .next()
                .unwrap_or_else(|| "../../conformance/fixtures/source-to-model".to_string());
            conformance(Path::new(&path));
        }
        "bench" => {
            let path = next_required_arg(&mut args);
            bench(&path, args.next(), args.next());
        }
        "bench-parse" => {
            let path = next_required_arg(&mut args);
            bench_parse(&path, args.next(), args.next());
        }
        "unicode-tests" => {
            let path = args
                .next()
                .unwrap_or_else(|| "../../third_party/message-format-wg/test".to_string());
            let baseline = args
                .next()
                .unwrap_or_else(|| "../../conformance/unicode-official-baseline.json".to_string());
            unicode_tests::run(Path::new(&path), Path::new(&baseline));
        }
        _ => usage_and_exit(),
    }
}

fn next_required_arg(args: &mut impl Iterator<Item = String>) -> String {
    args.next().unwrap_or_else(|| usage_and_exit())
}

fn compile(source: &str) {
    let fixture = serde_json::from_str::<SourceFixture>(source);
    let source = fixture
        .as_ref()
        .map_or(source, |fixture| fixture.source.as_str());
    let result = parse_to_model(source);
    if !result.diagnostics.is_empty() {
        println!(
            "{}",
            serde_json::to_string_pretty(&result.diagnostics).expect("diagnostics serialize")
        );
        process::exit(1);
    }
    println!(
        "{}",
        serde_json::to_string_pretty(&result.model.expect("model exists"))
            .expect("model serialize")
    );
}

fn format_first_case(source: &str) {
    let fixture = serde_json::from_str::<SourceFixture>(source).unwrap_or_else(|error| {
        eprintln!("format-first-case expects a source-to-model fixture: {error}");
        process::exit(2);
    });
    let result = parse_to_model(&fixture.source);
    if !result.diagnostics.is_empty() {
        println!(
            "{}",
            serde_json::to_string_pretty(&result.diagnostics).expect("diagnostics serialize")
        );
        process::exit(1);
    }
    let Some(format_case) = fixture.format_cases.first() else {
        eprintln!("Fixture has no formatCases.");
        process::exit(2);
    };
    let output = format_message_for_locale(
        &result.model.expect("model exists"),
        &format_case.arguments,
        &format_case.locale,
        BidiIsolation::None,
    )
    .and_then(value_or_first_error)
    .unwrap_or_else(|diagnostic| {
        eprintln!("{}", serde_json::to_string_pretty(&diagnostic).unwrap());
        process::exit(1);
    });
    println!("{output}");
}

fn editor_json(source: &str) {
    let request = serde_json::from_str::<EditorRequest>(source).unwrap_or_else(|error| {
        eprintln!("editor-json expects an editor request JSON file: {error}");
        process::exit(2);
    });
    let parsed = parse_to_model(&request.source);
    let mut response = EditorResponse {
        model: parsed.model.clone(),
        diagnostics: parsed.diagnostics,
        output: None,
        parts: Vec::new(),
        format_errors: Vec::new(),
    };

    if response.diagnostics.is_empty() {
        if let Some(model) = response.model.as_ref() {
            let functions = FunctionRegistry::default();
            match format_parts_with_registry(model, &request.arguments, &request.locale, &functions)
            {
                Ok(parts_result) => {
                    response.parts = parts_result.parts;
                    for diagnostic in parts_result.errors {
                        push_unique_diagnostic(&mut response.format_errors, diagnostic);
                    }
                }
                Err(diagnostic) => push_unique_diagnostic(&mut response.format_errors, diagnostic),
            }
            match format_message_with_registry(
                model,
                &request.arguments,
                &request.locale,
                &functions,
                BidiIsolation::from_name(request.bidi_isolation.as_deref()),
            ) {
                Ok(format_result) => {
                    response.output = Some(format_result.value);
                    for diagnostic in format_result.errors {
                        push_unique_diagnostic(&mut response.format_errors, diagnostic);
                    }
                }
                Err(diagnostic) => push_unique_diagnostic(&mut response.format_errors, diagnostic),
            }
        }
    }

    println!(
        "{}",
        serde_json::to_string_pretty(&response).expect("editor response serializes")
    );
}

fn push_unique_diagnostic(diagnostics: &mut Vec<Diagnostic>, diagnostic: Diagnostic) {
    if diagnostics.iter().any(|existing| {
        existing.code == diagnostic.code
            && existing.message == diagnostic.message
            && existing.start == diagnostic.start
            && existing.end == diagnostic.end
    }) {
        return;
    }
    diagnostics.push(diagnostic);
}

fn plural_json(locale: &str) {
    let response = PluralMetadataResponse {
        locale: locale.to_string(),
        select: "cardinal".to_string(),
        categories: cardinal_plural_metadata(locale),
    };
    println!(
        "{}",
        serde_json::to_string_pretty(&response).expect("plural metadata serializes")
    );
}

fn cardinal_plural_metadata(locale: &str) -> Vec<PluralCategoryMetadata> {
    const CATEGORY_ORDER: [&str; 6] = ["zero", "one", "two", "few", "many", "other"];
    const SAMPLE_VALUES: [&str; 33] = [
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16",
        "17", "18", "19", "20", "21", "22", "23", "24", "25", "31", "100", "101", "102", "1.0",
        "1.5", "2.5",
    ];

    let mut examples_by_category: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for sample in SAMPLE_VALUES {
        let Some(category) = cardinal_plural_category(locale, sample) else {
            continue;
        };
        examples_by_category
            .entry(category)
            .or_default()
            .push(sample.to_string());
    }

    CATEGORY_ORDER
        .iter()
        .filter_map(|category| {
            examples_by_category
                .remove(*category)
                .map(|examples| PluralCategoryMetadata {
                    category: (*category).to_string(),
                    examples: examples.into_iter().take(4).collect(),
                })
        })
        .collect()
}

fn cardinal_plural_category(locale: &str, value: &str) -> Option<String> {
    static MODEL: OnceLock<MessageModel> = OnceLock::new();
    let model = MODEL.get_or_init(|| {
        parse_to_model(
            ".input {$count :number}\n.match $count\nzero {{zero}}\none {{one}}\ntwo {{two}}\nfew {{few}}\nmany {{many}}\nother {{other}}\n* {{other}}",
        )
        .model
        .expect("plural category helper source parses")
    });
    format_message_with_options(
        model,
        Arguments::new().with("count", ArgumentValue::number(value)),
        &FormatOptions::new(locale),
    )
    .ok()
    .map(|result| result.value)
}

fn conformance(fixture_dir: &Path) {
    let mut checked_models = 0usize;
    let mut checked_format_cases = 0usize;
    let mut checked_parts_cases = 0usize;
    let mut checked_fallback_cases = 0usize;
    let mut checked_fallback_parts_cases = 0usize;

    for fixture_path in json_fixture_paths(fixture_dir) {
        let fixture: SourceConformanceFixture = read_json_fixture(&fixture_path);
        let result = parse_to_model(&fixture.source);
        if !result.diagnostics.is_empty() {
            fail(format!(
                "{}: expected parse success, got diagnostics {:?}",
                fixture_path.display(),
                result.diagnostics
            ));
        }
        let Some(model) = result.model.as_ref() else {
            fail(format!("{}: expected parsed model", fixture_path.display()));
        };
        if model != &fixture.expected_model {
            fail(format!(
                "{}: parsed model did not match expected model",
                fixture_path.display()
            ));
        }
        checked_models += 1;

        for format_case in fixture.format_cases {
            let actual = format_message_for_locale(
                model,
                &format_case.arguments,
                &format_case.locale,
                BidiIsolation::from_name(format_case.bidi_isolation.as_deref()),
            )
            .and_then(value_or_first_error)
            .unwrap_or_else(|diagnostic| {
                fail(format!(
                    "{}: format failed with {}",
                    fixture_path.display(),
                    diagnostic.code
                ));
            });
            if actual != format_case.expected {
                fail(format!(
                    "{}: expected {:?}, got {:?}",
                    fixture_path.display(),
                    format_case.expected,
                    actual
                ));
            }
            checked_format_cases += 1;
        }

        for parts_case in fixture.parts_cases {
            let actual = format_parts_for_locale(model, &parts_case.arguments, &parts_case.locale)
                .and_then(parts_or_first_error)
                .unwrap_or_else(|diagnostic| {
                    fail(format!(
                        "{}: format parts failed with {}",
                        fixture_path.display(),
                        diagnostic.code
                    ));
                });
            if actual != parts_case.expected {
                fail(format!(
                    "{}: expected parts {:?}, got {:?}",
                    fixture_path.display(),
                    parts_case.expected,
                    actual
                ));
            }
            checked_parts_cases += 1;
        }

        for fallback_case in fixture.fallback_cases {
            let actual = format_message_for_locale(
                model,
                &fallback_case.arguments,
                &fallback_case.locale,
                BidiIsolation::from_name(fallback_case.bidi_isolation.as_deref()),
            )
            .unwrap_or_else(|diagnostic| {
                fail(format!(
                    "{}: fallback format failed with {}",
                    fixture_path.display(),
                    diagnostic.code
                ));
            });
            if actual.value != fallback_case.expected {
                fail(format!(
                    "{}: expected fallback {:?}, got {:?}",
                    fixture_path.display(),
                    fallback_case.expected,
                    actual.value
                ));
            }
            assert_diagnostic_codes(
                &fixture_path,
                "fallback errors",
                &actual.errors,
                &fallback_case.expected_errors,
            );
            checked_fallback_cases += 1;
        }

        for parts_case in fixture.fallback_parts_cases {
            let actual = format_parts_for_locale(model, &parts_case.arguments, &parts_case.locale)
                .unwrap_or_else(|diagnostic| {
                    fail(format!(
                        "{}: fallback parts failed with {}",
                        fixture_path.display(),
                        diagnostic.code
                    ));
                });
            if actual.parts != parts_case.expected {
                fail(format!(
                    "{}: expected fallback parts {:?}, got {:?}",
                    fixture_path.display(),
                    parts_case.expected,
                    actual.parts
                ));
            }
            assert_diagnostic_codes(
                &fixture_path,
                "fallback parts errors",
                &actual.errors,
                &parts_case.expected_errors,
            );
            checked_fallback_parts_cases += 1;
        }
    }

    let fixture_root = fixture_dir.parent().unwrap_or_else(|| Path::new("."));
    let checked_invalid_sources = check_invalid_source_fixtures(fixture_root);
    let checked_format_error_cases = check_format_error_fixtures(fixture_root);
    let checked_locale_key_cases = check_locale_key_fixtures(fixture_root);

    println!(
        "Rust MF2 conformance runner passed {checked_models} source models, \
         {checked_format_cases} format cases, {checked_parts_cases} parts cases, \
         {checked_fallback_cases} fallback cases, {checked_fallback_parts_cases} fallback parts cases, \
         {checked_invalid_sources} invalid source cases, {checked_format_error_cases} format error cases, \
         and {checked_locale_key_cases} locale key cases."
    );
}

fn assert_diagnostic_codes(
    fixture_path: &Path,
    label: &str,
    actual: &[mojito_mf2::Diagnostic],
    expected: &[ExpectedDiagnostic],
) {
    let actual_codes: Vec<_> = actual
        .iter()
        .map(|diagnostic| diagnostic.code.as_str())
        .collect();
    let expected_codes: Vec<_> = expected
        .iter()
        .map(|diagnostic| diagnostic.code.as_str())
        .collect();
    if actual_codes != expected_codes {
        fail(format!(
            "{}: expected {label} {:?}, got {:?}",
            fixture_path.display(),
            expected_codes,
            actual_codes
        ));
    }
}

fn check_invalid_source_fixtures(fixture_root: &Path) -> usize {
    let fixture_dir = fixture_root.join("invalid-source");
    if !fixture_dir.is_dir() {
        return 0;
    }

    let mut checked_cases = 0usize;
    for fixture_path in json_fixture_paths(&fixture_dir) {
        let fixture: InvalidSourceFixture = read_json_fixture(&fixture_path);
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
        if actual_codes != expected_codes {
            fail(format!(
                "{}: expected diagnostics {:?}, got {:?}",
                fixture_path.display(),
                expected_codes,
                actual_codes
            ));
        }
        if result.model.is_some() {
            fail(format!(
                "{}: invalid fixture produced a model",
                fixture_path.display()
            ));
        }
        checked_cases += 1;
    }
    checked_cases
}

fn check_format_error_fixtures(fixture_root: &Path) -> usize {
    let fixture_dir = fixture_root.join("format-errors");
    if !fixture_dir.is_dir() {
        return 0;
    }

    let mut checked_cases = 0usize;
    for fixture_path in json_fixture_paths(&fixture_dir) {
        let fixture: FormatErrorFixture = read_json_fixture(&fixture_path);
        let actual_code = match format_message_for_locale(
            &fixture.model,
            &fixture.arguments,
            &fixture.locale,
            BidiIsolation::None,
        ) {
            Ok(result) => result
                .errors
                .first()
                .map(|diagnostic| diagnostic.code.clone()),
            Err(diagnostic) => Some(diagnostic.code),
        };
        if actual_code.as_deref() != Some(fixture.expected_error.code.as_str()) {
            fail(format!(
                "{}: expected error {}, got {:?}",
                fixture_path.display(),
                fixture.expected_error.code,
                actual_code
            ));
        }
        checked_cases += 1;
    }
    checked_cases
}

fn check_locale_key_fixtures(fixture_root: &Path) -> usize {
    let fixture_path = fixture_root.join("locale-key").join("cases.json");
    if !fixture_path.is_file() {
        return 0;
    }

    let fixture: LocaleKeyFixture = read_json_fixture(&fixture_path);
    let mut checked_cases = 0usize;
    for item in fixture.canonical {
        let actual = canonical_locale_key(&item.source);
        if actual != item.expected {
            fail(format!(
                "{}: expected canonical {}, got {}",
                fixture_path.display(),
                item.expected,
                actual
            ));
        }
        checked_cases += 1;
    }
    for item in fixture.lookup_chains {
        let actual = locale_lookup_chain(&item.source);
        if actual != item.expected {
            fail(format!(
                "{}: expected lookup chain {:?}, got {:?}",
                fixture_path.display(),
                item.expected,
                actual
            ));
        }
        checked_cases += 1;
    }
    checked_cases
}

fn canonical_locale_key(locale: &str) -> String {
    locale_parts(locale).join("-")
}

fn locale_lookup_chain(locale: &str) -> Vec<String> {
    let parts: Vec<_> = canonical_locale_key(locale)
        .split('-')
        .filter(|part| !part.is_empty())
        .map(ToString::to_string)
        .collect();
    (1..=parts.len())
        .rev()
        .map(|length| parts[..length].join("-"))
        .collect()
}

fn locale_parts(locale: &str) -> Vec<String> {
    locale
        .trim()
        .replace('_', "-")
        .split('-')
        .filter(|part| !part.is_empty())
        .enumerate()
        .take_while(|(_, part)| part.len() != 1)
        .map(|(index, part)| canonical_subtag(index, part))
        .collect()
}

fn canonical_subtag(index: usize, part: &str) -> String {
    if index == 0 {
        return part.to_ascii_lowercase();
    }
    if part.len() == 4 && part.chars().all(|ch| ch.is_ascii_alphabetic()) {
        let mut chars = part.chars();
        let first = chars
            .next()
            .map(|ch| ch.to_ascii_uppercase())
            .unwrap_or_default();
        return format!("{first}{}", chars.as_str().to_ascii_lowercase());
    }
    if (part.len() == 2 && part.chars().all(|ch| ch.is_ascii_alphabetic()))
        || (part.len() == 3 && part.chars().all(|ch| ch.is_ascii_digit()))
    {
        return part.to_ascii_uppercase();
    }
    part.to_ascii_lowercase()
}

fn bench_parse(path: &str, iterations_arg: Option<String>, warmup_arg: Option<String>) {
    let iterations = parse_iterations(iterations_arg.as_deref().unwrap_or("100000"), "iteration");
    let warmup_iterations = parse_iterations(warmup_arg.as_deref().unwrap_or("10000"), "warmup");
    let sources = read_sources(Path::new(path));

    if sources.is_empty() {
        eprintln!("No source fixtures found.");
        process::exit(2);
    }

    for index in 0..warmup_iterations {
        let result = parse_to_model(&sources[index % sources.len()]);
        black_box(result);
    }

    let started = Instant::now();
    let mut bytes = 0usize;
    let mut diagnostics = 0usize;
    let mut models = 0usize;
    for index in 0..iterations {
        let source = &sources[index % sources.len()];
        let result = parse_to_model(source);
        bytes += black_box(source.len());
        diagnostics += black_box(result.diagnostics.len());
        models += usize::from(result.model.is_some());
        black_box(result);
    }
    let seconds = started.elapsed().as_secs_f64();
    println!(
        "rust parse iterations={iterations} warmup={warmup_iterations} sources={} seconds={seconds:.6} ops_per_second={:.0} bytes={bytes} diagnostics={diagnostics} models={models}",
        sources.len(),
        iterations as f64 / seconds
    );
}

fn default_locale() -> String {
    "en".to_string()
}

fn format_message_for_locale(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
    bidi_isolation: BidiIsolation,
) -> Result<FormatResult, Diagnostic> {
    let arguments = arguments_from_json(arguments);
    let options = FormatOptions::new(locale).with_bidi_isolation(bidi_isolation);
    format_message_with_options(model, &arguments, &options)
}

fn format_message_with_registry(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
    functions: &FunctionRegistry,
    bidi_isolation: BidiIsolation,
) -> Result<FormatResult, Diagnostic> {
    let arguments = arguments_from_json(arguments);
    let options = FormatOptions::new(locale)
        .with_functions(functions)
        .with_bidi_isolation(bidi_isolation);
    format_message_with_options(model, &arguments, &options)
}

fn format_parts_for_locale(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
) -> Result<PartsResult, Diagnostic> {
    let arguments = arguments_from_json(arguments);
    let options = FormatOptions::new(locale);
    format_message_to_parts_with_options(model, &arguments, &options)
}

fn format_parts_with_registry(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
    functions: &FunctionRegistry,
) -> Result<PartsResult, Diagnostic> {
    let arguments = arguments_from_json(arguments);
    let options = FormatOptions::new(locale).with_functions(functions);
    format_message_to_parts_with_options(model, &arguments, &options)
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

fn value_or_first_error(result: FormatResult) -> Result<String, Diagnostic> {
    if let Some(diagnostic) = result.errors.first() {
        Err(diagnostic.clone())
    } else {
        Ok(result.value)
    }
}

fn parts_or_first_error(result: PartsResult) -> Result<Vec<FormattedPart>, Diagnostic> {
    if let Some(diagnostic) = result.errors.first() {
        Err(diagnostic.clone())
    } else {
        Ok(result.parts)
    }
}

fn bench(path: &str, iterations_arg: Option<String>, warmup_arg: Option<String>) {
    let iterations = parse_iterations(iterations_arg.as_deref().unwrap_or("100000"), "iteration");
    let warmup_iterations = parse_iterations(warmup_arg.as_deref().unwrap_or("10000"), "warmup");
    let fixtures = read_fixture_dir(Path::new(path));
    let cases: Vec<_> = fixtures
        .iter()
        .flat_map(|fixture| {
            let model = fixture
                .expected_model
                .clone()
                .unwrap_or_else(|| parse_to_model(&fixture.source).model.expect("model exists"));
            fixture
                .format_cases
                .iter()
                .map(move |case| (model.clone(), case.locale.clone(), case.arguments.clone()))
        })
        .collect();

    if cases.is_empty() {
        eprintln!("No format cases found.");
        process::exit(2);
    }

    for index in 0..warmup_iterations {
        let (model, locale, arguments) = &cases[index % cases.len()];
        let output = value_or_first_error(
            format_message_for_locale(model, arguments, locale, BidiIsolation::None)
                .expect("format succeeds"),
        )
        .expect("format has no recoverable errors");
        black_box(output);
    }

    let started = Instant::now();
    let mut bytes = 0usize;
    for index in 0..iterations {
        let (model, locale, arguments) = &cases[index % cases.len()];
        let output = value_or_first_error(
            format_message_for_locale(model, arguments, locale, BidiIsolation::None)
                .expect("format succeeds"),
        )
        .expect("format has no recoverable errors");
        bytes += black_box(output.len());
    }
    let seconds = started.elapsed().as_secs_f64();
    println!(
        "rust format iterations={iterations} warmup={warmup_iterations} cases={} seconds={seconds:.6} ops_per_second={:.0} bytes={bytes}",
        cases.len(),
        iterations as f64 / seconds
    );
}

fn parse_iterations(value: &str, label: &str) -> usize {
    value.parse::<usize>().unwrap_or_else(|error| {
        eprintln!("Invalid {label} count: {error}");
        process::exit(2);
    })
}

fn read_sources(dir: &Path) -> Vec<String> {
    json_fixture_paths(dir)
        .into_iter()
        .map(|path| {
            let fixture: SourceOnlyFixture = read_json_fixture(&path);
            fixture.source
        })
        .collect()
}

fn read_fixture_dir(dir: &Path) -> Vec<SourceFixture> {
    json_fixture_paths(dir)
        .into_iter()
        .map(|path| read_json_fixture(&path))
        .collect()
}

fn json_fixture_paths(dir: &Path) -> Vec<PathBuf> {
    let mut paths: Vec<_> = fs::read_dir(dir)
        .unwrap_or_else(|error| {
            eprintln!("Failed to read {}: {error}", dir.display());
            process::exit(2);
        })
        .map(|entry| entry.expect("fixture entry").path())
        .filter(|path| {
            path.extension()
                .is_some_and(|extension| extension == "json")
        })
        .collect();
    paths.sort();
    paths
}

fn read_json_fixture<T: for<'de> Deserialize<'de>>(path: &Path) -> T {
    let content = fs::read_to_string(path).unwrap_or_else(|error| {
        eprintln!("Failed to read {}: {error}", path.display());
        process::exit(2);
    });
    serde_json::from_str(&content).unwrap_or_else(|error| {
        eprintln!("Failed to parse {}: {error}", path.display());
        process::exit(2);
    })
}

fn read_to_string(path: &str) -> String {
    fs::read_to_string(path).unwrap_or_else(|error| {
        eprintln!("Failed to read {path}: {error}");
        process::exit(2);
    })
}

fn fail(message: impl std::fmt::Display) -> ! {
    eprintln!("{message}");
    process::exit(1);
}

fn usage_and_exit() -> ! {
    eprintln!(
        "Usage:\n  mojito-mf2 compile <source-or-fixture.json>\n  mojito-mf2 format-first-case <fixture.json>\n  mojito-mf2 editor-json <request.json>\n  mojito-mf2 plural-json <locale>\n  mojito-mf2 conformance [source-fixture-dir]\n  mojito-mf2 unicode-tests [unicode-test-dir] [baseline-json]\n  mojito-mf2 bench <fixture-dir> [iterations] [warmup-iterations]\n  mojito-mf2 bench-parse <fixture-dir> [iterations] [warmup-iterations]"
    );
    process::exit(2);
}
