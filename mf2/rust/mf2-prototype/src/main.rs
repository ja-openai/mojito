use std::collections::BTreeMap;
use std::env;
use std::fs;
use std::hint::black_box;
use std::path::{Path, PathBuf};
use std::process;
use std::time::Instant;

use mf2_prototype::{
    canonical_locale_key, format_model_to_parts_with_locale,
    format_model_to_parts_with_locale_and_functions_and_fallback, format_model_with_locale,
    format_model_with_locale_and_bidi,
    format_model_with_locale_and_functions_and_bidi_and_fallback, locale_lookup_chain,
    parse_to_model, BidiIsolation, Diagnostic, FormattedPart, FunctionRegistry, MessageModel,
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
    let output = format_model_with_locale(
        &result.model.expect("model exists"),
        &format_case.arguments,
        &format_case.locale,
    )
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
            match format_model_to_parts_with_locale_and_functions_and_fallback(
                model,
                &request.arguments,
                &request.locale,
                &functions,
            ) {
                Ok(parts_result) => {
                    response.parts = parts_result.parts;
                    for diagnostic in parts_result.errors {
                        push_unique_diagnostic(&mut response.format_errors, diagnostic);
                    }
                }
                Err(diagnostic) => push_unique_diagnostic(&mut response.format_errors, diagnostic),
            }
            match format_model_with_locale_and_functions_and_bidi_and_fallback(
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
            let actual = format_model_with_locale_and_bidi(
                model,
                &format_case.arguments,
                &format_case.locale,
                BidiIsolation::from_name(format_case.bidi_isolation.as_deref()),
            )
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
            let actual =
                format_model_to_parts_with_locale(model, &parts_case.arguments, &parts_case.locale)
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
            let actual = format_model_with_locale_and_functions_and_bidi_and_fallback(
                model,
                &fallback_case.arguments,
                &fallback_case.locale,
                &FunctionRegistry::default(),
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
            let actual = format_model_to_parts_with_locale_and_functions_and_fallback(
                model,
                &parts_case.arguments,
                &parts_case.locale,
                &FunctionRegistry::default(),
            )
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
    actual: &[mf2_prototype::Diagnostic],
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
        match format_model_with_locale(&fixture.model, &fixture.arguments, &fixture.locale) {
            Ok(output) => fail(format!(
                "{}: expected format error, got {:?}",
                fixture_path.display(),
                output
            )),
            Err(diagnostic) if diagnostic.code == fixture.expected_error.code => {}
            Err(diagnostic) => fail(format!(
                "{}: expected error {}, got {}",
                fixture_path.display(),
                fixture.expected_error.code,
                diagnostic.code
            )),
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
        let output = format_model_with_locale(model, arguments, locale).expect("format succeeds");
        black_box(output);
    }

    let started = Instant::now();
    let mut bytes = 0usize;
    for index in 0..iterations {
        let (model, locale, arguments) = &cases[index % cases.len()];
        let output = format_model_with_locale(model, arguments, locale).expect("format succeeds");
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
        "Usage:\n  mf2-prototype compile <source-or-fixture.json>\n  mf2-prototype format-first-case <fixture.json>\n  mf2-prototype editor-json <request.json>\n  mf2-prototype conformance [source-fixture-dir]\n  mf2-prototype unicode-tests [unicode-test-dir] [baseline-json]\n  mf2-prototype bench <fixture-dir> [iterations] [warmup-iterations]\n  mf2-prototype bench-parse <fixture-dir> [iterations] [warmup-iterations]"
    );
    process::exit(2);
}
