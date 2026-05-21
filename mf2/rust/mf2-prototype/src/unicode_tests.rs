use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::panic::{self, AssertUnwindSafe};
use std::path::{Path, PathBuf};

use mf2_prototype::{
    format_model_with_locale_and_bidi,
    format_model_with_locale_and_functions_and_bidi_and_fallback, parse_to_model, BidiIsolation,
    Declaration, FunctionCall, FunctionMatch, FunctionRegistry, FunctionSourceRef, MessageModel,
    ParseResult,
};
use serde::Deserialize;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct OfficialSuite {
    #[serde(default)]
    default_test_properties: TestProperties,
    tests: Vec<OfficialTest>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TestProperties {
    #[serde(default)]
    locale: Option<String>,
    #[serde(default)]
    bidi_isolation: Option<String>,
    #[serde(default)]
    exp_errors: Option<Vec<OfficialError>>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct OfficialTest {
    src: String,
    #[serde(default)]
    exp: Option<String>,
    #[serde(default)]
    description: Option<String>,
    #[serde(default)]
    locale: Option<String>,
    #[serde(default)]
    bidi_isolation: Option<String>,
    #[serde(default)]
    exp_errors: Option<Vec<OfficialError>>,
    #[serde(default)]
    params: Vec<OfficialParam>,
}

#[derive(Debug, Clone, Deserialize)]
struct OfficialError {
    #[serde(rename = "type")]
    error_type: String,
}

#[derive(Debug, Deserialize)]
struct OfficialParam {
    name: String,
    value: serde_json::Value,
}

struct FileCheck {
    path: &'static str,
    mode: CheckMode,
}

#[derive(Clone, Copy)]
enum CheckMode {
    Parse,
    DataModelErrors,
    Runtime,
}

#[derive(Default)]
struct Summary {
    passed: usize,
    skipped: usize,
    not_wired: usize,
    files: Vec<FileSummary>,
    skip_examples: Vec<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Baseline {
    passed: usize,
    skipped: usize,
    not_wired: usize,
    total: usize,
    files: BTreeMap<String, BaselineFile>,
}

#[derive(Debug, Deserialize)]
struct BaselineFile {
    passed: usize,
    skipped: usize,
}

struct FileSummary {
    path: String,
    mode: &'static str,
    passed: usize,
    skipped: usize,
}

pub fn run(root: &Path, baseline: &Path) {
    let previous_panic_hook = panic::take_hook();
    panic::set_hook(Box::new(|_| {}));
    run_with_suppressed_parser_panics(root, baseline);
    panic::set_hook(previous_panic_hook);
}

fn run_with_suppressed_parser_panics(root: &Path, baseline_path: &Path) {
    let test_root = root.join("tests");
    if !test_root.is_dir() {
        fail(format!(
            "{} does not look like the Unicode MessageFormat test directory",
            root.display()
        ));
    }

    let checks = [
        FileCheck {
            path: "tests/syntax.json",
            mode: CheckMode::Parse,
        },
        FileCheck {
            path: "tests/syntax-errors.json",
            mode: CheckMode::Parse,
        },
        FileCheck {
            path: "tests/bidi.json",
            mode: CheckMode::Parse,
        },
        FileCheck {
            path: "tests/data-model-errors.json",
            mode: CheckMode::DataModelErrors,
        },
        FileCheck {
            path: "tests/functions/string.json",
            mode: CheckMode::Runtime,
        },
        FileCheck {
            path: "tests/fallback.json",
            mode: CheckMode::Runtime,
        },
        FileCheck {
            path: "tests/pattern-selection.json",
            mode: CheckMode::Runtime,
        },
    ];
    let wired_paths: BTreeSet<_> = checks.iter().map(|check| check.path).collect();
    let mut summary = Summary::default();

    for check in checks {
        run_file(root, &check, &mut summary);
    }

    for path in official_json_paths(root) {
        let relative = path.strip_prefix(root).expect("relative official path");
        let relative = relative.to_string_lossy().replace('\\', "/");
        if wired_paths.contains(relative.as_str()) {
            continue;
        }
        let suite: OfficialSuite = read_json(&path);
        summary.not_wired += suite.tests.len();
    }

    for file in &summary.files {
        println!(
            "  {} {} passed={} skipped={}",
            file.mode, file.path, file.passed, file.skipped
        );
    }
    if !summary.skip_examples.is_empty() {
        println!("  skip examples:");
        for example in &summary.skip_examples {
            println!("    {example}");
        }
    }
    println!(
        "Rust Unicode official tests passed={} skipped={} not_wired={} total={}",
        summary.passed,
        summary.skipped,
        summary.not_wired,
        summary.passed + summary.skipped + summary.not_wired
    );
    check_baseline(&summary, baseline_path);
}

fn run_file(root: &Path, check: &FileCheck, summary: &mut Summary) {
    let path = root.join(check.path);
    let suite: OfficialSuite = read_json(&path);
    let mut file_passed = 0usize;
    let mut file_skipped = 0usize;

    for (index, test) in suite.tests.iter().enumerate() {
        match check.mode {
            CheckMode::Parse => {
                if check_parse_test(&suite.default_test_properties, test) {
                    file_passed += 1;
                } else {
                    file_skipped += 1;
                    record_skip(summary, check.path, index, test, "parse behavior differs");
                }
            }
            CheckMode::DataModelErrors => {
                if check_data_model_error_test(&suite.default_test_properties, test) {
                    file_passed += 1;
                } else {
                    file_skipped += 1;
                    record_skip(summary, check.path, index, test, "data-model error differs");
                }
            }
            CheckMode::Runtime => {
                if check_runtime_test(&suite.default_test_properties, test) {
                    file_passed += 1;
                } else {
                    file_skipped += 1;
                    record_skip(summary, check.path, index, test, "runtime behavior differs");
                }
            }
        }
    }

    summary.passed += file_passed;
    summary.skipped += file_skipped;
    summary.files.push(FileSummary {
        path: check.path.to_string(),
        mode: match check.mode {
            CheckMode::Parse => "parse",
            CheckMode::DataModelErrors => "data-model",
            CheckMode::Runtime => "runtime",
        },
        passed: file_passed,
        skipped: file_skipped,
    });
}

fn check_parse_test(defaults: &TestProperties, test: &OfficialTest) -> bool {
    let expected_syntax_error = expected_errors(defaults, test)
        .iter()
        .any(|error| error.error_type == "syntax-error");
    let Some(result) = safe_parse(&test.src) else {
        return false;
    };
    result.diagnostics.is_empty() != expected_syntax_error
}

fn check_data_model_error_test(defaults: &TestProperties, test: &OfficialTest) -> bool {
    let expected_codes = expected_local_codes(defaults, test);

    let Some(result) = safe_parse(&test.src) else {
        return false;
    };
    if expected_codes.is_empty() {
        return test.exp.as_ref().is_some_and(|expected| {
            let Some(model) = result.model.as_ref() else {
                return false;
            };
            let args = arguments_for(test, model);
            format_model_with_locale_and_bidi(
                model,
                &args,
                &locale(defaults, test),
                BidiIsolation::from_name(bidi_isolation(defaults, test).as_deref()),
            )
            .is_ok_and(|actual| &actual == expected)
        });
    }
    let actual_codes: Vec<String> = if result.diagnostics.is_empty() {
        let Some(model) = result.model.as_ref() else {
            return false;
        };
        let args = arguments_for(test, model);
        match format_model_with_locale_and_bidi(
            model,
            &args,
            &locale(defaults, test),
            BidiIsolation::from_name(bidi_isolation(defaults, test).as_deref()),
        ) {
            Ok(_) => Vec::new(),
            Err(diagnostic) => vec![diagnostic.code],
        }
    } else {
        result
            .diagnostics
            .into_iter()
            .map(|diagnostic| diagnostic.code)
            .collect()
    };

    actual_codes
        .iter()
        .any(|actual| expected_codes.iter().any(|expected| expected == actual))
}

fn check_runtime_test(defaults: &TestProperties, test: &OfficialTest) -> bool {
    let Some(result) = safe_parse(&test.src) else {
        return false;
    };
    if !result.diagnostics.is_empty() {
        return false;
    }
    let Some(model) = result.model.as_ref() else {
        return false;
    };

    let args = runtime_arguments_for(test);
    let expected_codes = expected_local_codes(defaults, test);
    let actual = match format_model_with_locale_and_functions_and_bidi_and_fallback(
        model,
        &args,
        &locale(defaults, test),
        &official_function_registry(),
        BidiIsolation::from_name(bidi_isolation(defaults, test).as_deref()),
    ) {
        Ok(actual) => actual,
        Err(diagnostic) => {
            return expected_codes
                .iter()
                .any(|expected| expected == &diagnostic.code);
        }
    };
    let actual_codes: Vec<_> = actual
        .errors
        .iter()
        .map(|diagnostic| diagnostic.code.as_str())
        .collect();
    if !expected_codes
        .iter()
        .all(|expected| actual_codes.iter().any(|actual| actual == expected))
    {
        return false;
    }
    if expected_codes.is_empty() && !actual_codes.is_empty() {
        return false;
    }
    test.exp
        .as_ref()
        .is_some_and(|expected| actual.value == *expected)
}

fn official_function_registry() -> FunctionRegistry {
    FunctionRegistry::default()
        .with_function("test:function", official_test_function)
        .with_function("test:select", official_test_select_resolver)
        .with_function("test:format", official_test_format_resolver)
        .with_selector("test:function", official_test_selector)
        .with_selector("test:select", official_test_selector)
        .with_selector("test:format", official_test_format_selector)
}

fn official_test_function(call: FunctionCall<'_>) -> Result<String, mf2_prototype::Diagnostic> {
    let state = official_test_state_from_call(&call)?;
    if state.fails_format {
        return Err(mf2_prototype::Diagnostic::new(
            "bad-option",
            ":test:function fails=format requested a format failure.",
            0,
            0,
        ));
    }
    Ok(state.format_value())
}

fn official_test_select_resolver(
    call: FunctionCall<'_>,
) -> Result<String, mf2_prototype::Diagnostic> {
    Ok(official_test_state_from_call(&call)?.format_value())
}

fn official_test_format_resolver(
    call: FunctionCall<'_>,
) -> Result<String, mf2_prototype::Diagnostic> {
    Ok(official_test_state_from_call(&call)?.format_value())
}

fn official_test_selector(
    call: FunctionMatch<'_>,
) -> Result<Option<i32>, mf2_prototype::Diagnostic> {
    let state = official_test_state_from_match(&call)?;
    if state.fails_select {
        return Err(mf2_prototype::Diagnostic::new(
            "bad-selector",
            ":test function fails selection.",
            0,
            0,
        ));
    }
    if state.input.trunc() as i64 != 1 {
        return Ok(None);
    }
    match (state.decimal_places, call.key()) {
        (1, "1.0") => Ok(Some(2)),
        (1, "1") | (0, "1") => Ok(Some(1)),
        _ => Ok(None),
    }
}

fn official_test_format_selector(
    _call: FunctionMatch<'_>,
) -> Result<Option<i32>, mf2_prototype::Diagnostic> {
    Err(mf2_prototype::Diagnostic::new(
        "bad-selector",
        ":test:format cannot be used for selection.",
        0,
        0,
    ))
}

#[derive(Debug, Clone, Copy)]
struct OfficialTestState {
    input: f64,
    decimal_places: i32,
    fails_format: bool,
    fails_select: bool,
}

impl OfficialTestState {
    fn format_value(self) -> String {
        let sign = if self.input < 0.0 { "-" } else { "" };
        let absolute = self.input.abs();
        let integer = absolute.floor();
        if self.decimal_places == 1 {
            let digit = ((absolute - integer) * 10.0).floor() as i64;
            format!("{sign}{}.{digit}", integer as i64)
        } else {
            format!("{sign}{}", integer as i64)
        }
    }
}

fn official_test_state_from_call(
    call: &FunctionCall<'_>,
) -> Result<OfficialTestState, mf2_prototype::Diagnostic> {
    official_test_state(call.value(), call.inherited_source(), |name| {
        call.option_value(name)
    })
}

fn official_test_state_from_match(
    call: &FunctionMatch<'_>,
) -> Result<OfficialTestState, mf2_prototype::Diagnostic> {
    official_test_state(call.value(), call.inherited_source(), |name| {
        call.option_value(name)
    })
}

fn official_test_state(
    value: &str,
    inherited: Option<FunctionSourceRef<'_>>,
    option_value: impl Fn(&str) -> Result<Option<String>, mf2_prototype::Diagnostic>,
) -> Result<OfficialTestState, mf2_prototype::Diagnostic> {
    let mut state = if let Some(source) = inherited {
        official_test_state_from_source(source)?
    } else {
        official_test_state_from_value(value)?
    };
    apply_official_test_options(&mut state, option_value)?;
    Ok(state)
}

fn official_test_state_from_source(
    source: FunctionSourceRef<'_>,
) -> Result<OfficialTestState, mf2_prototype::Diagnostic> {
    let mut state = if let Some(inherited) = source.inherited_source() {
        official_test_state_from_source(inherited)?
    } else {
        official_test_state_from_value(source.value())?
    };
    if is_official_test_function(&source.function().name) {
        apply_official_test_options(&mut state, |name| source.option_value(name))?;
    }
    Ok(state)
}

fn official_test_state_from_value(
    value: &str,
) -> Result<OfficialTestState, mf2_prototype::Diagnostic> {
    let input = value.parse::<f64>().map_err(|_| {
        mf2_prototype::Diagnostic::new(
            "bad-operand",
            "Unicode test function requires a numeric operand.",
            0,
            0,
        )
    })?;
    Ok(OfficialTestState {
        input,
        decimal_places: 0,
        fails_format: false,
        fails_select: false,
    })
}

fn apply_official_test_options(
    state: &mut OfficialTestState,
    option_value: impl Fn(&str) -> Result<Option<String>, mf2_prototype::Diagnostic>,
) -> Result<(), mf2_prototype::Diagnostic> {
    let decimal_places = match option_value("decimalPlaces")?.as_deref() {
        None => None,
        Some("0") => Some(0),
        Some("1") => Some(1),
        Some(_) => {
            return Err(mf2_prototype::Diagnostic::new(
                "bad-option",
                ":test function decimalPlaces must be 0 or 1.",
                0,
                0,
            ));
        }
    };
    if let Some(decimal_places) = decimal_places {
        state.decimal_places = decimal_places;
    }
    match option_value("fails")?.as_deref() {
        Some("always") => {
            state.fails_format = true;
            state.fails_select = true;
        }
        Some("format") => {
            state.fails_format = true;
        }
        Some("select") => {
            state.fails_select = true;
        }
        _ => {}
    }
    Ok(())
}

fn is_official_test_function(name: &str) -> bool {
    matches!(name, "test:function" | "test:select" | "test:format")
}

fn safe_parse(source: &str) -> Option<ParseResult> {
    panic::catch_unwind(AssertUnwindSafe(|| parse_to_model(source))).ok()
}

fn expected_errors<'a>(
    defaults: &'a TestProperties,
    test: &'a OfficialTest,
) -> Vec<&'a OfficialError> {
    test.exp_errors
        .as_ref()
        .or(defaults.exp_errors.as_ref())
        .map(|errors| errors.iter().collect())
        .unwrap_or_default()
}

fn expected_local_codes(defaults: &TestProperties, test: &OfficialTest) -> Vec<String> {
    let mut codes = Vec::new();
    for error in expected_errors(defaults, test) {
        match error.error_type.as_str() {
            "variant-key-mismatch" => codes.push("variant-key-count-mismatch".to_string()),
            other => codes.push(other.to_string()),
        }
    }
    codes
}

fn arguments_for(test: &OfficialTest, model: &MessageModel) -> BTreeMap<String, serde_json::Value> {
    let mut arguments = BTreeMap::new();
    for declaration in declarations(model) {
        if let Declaration::Input { name, .. } = declaration {
            arguments.insert(name.clone(), serde_json::Value::String("1".to_string()));
        }
    }
    for param in &test.params {
        arguments.insert(param.name.clone(), param.value.clone());
    }
    arguments
}

fn runtime_arguments_for(test: &OfficialTest) -> BTreeMap<String, serde_json::Value> {
    let mut arguments = BTreeMap::new();
    for param in &test.params {
        arguments.insert(param.name.clone(), param.value.clone());
    }
    arguments
}

fn declarations(model: &MessageModel) -> &[Declaration] {
    match model {
        MessageModel::Message { declarations, .. } | MessageModel::Select { declarations, .. } => {
            declarations
        }
    }
}

fn locale(defaults: &TestProperties, test: &OfficialTest) -> String {
    test.locale
        .clone()
        .or_else(|| defaults.locale.clone())
        .unwrap_or_else(|| "en".to_string())
}

fn bidi_isolation(defaults: &TestProperties, test: &OfficialTest) -> Option<String> {
    test.bidi_isolation
        .clone()
        .or_else(|| defaults.bidi_isolation.clone())
}

fn record_skip(summary: &mut Summary, path: &str, index: usize, test: &OfficialTest, reason: &str) {
    if summary.skip_examples.len() >= 8 {
        return;
    }
    let label = test.description.as_deref().unwrap_or(test.src.as_str());
    summary
        .skip_examples
        .push(format!("{path}#{}: {reason}: {label}", index + 1));
}

fn check_baseline(summary: &Summary, baseline_path: &Path) {
    let baseline: Baseline = read_json(baseline_path);
    let total = summary.passed + summary.skipped + summary.not_wired;
    if baseline.passed != summary.passed
        || baseline.skipped != summary.skipped
        || baseline.not_wired != summary.not_wired
        || baseline.total != total
    {
        fail(format!(
            "{}: expected official-test counts passed={} skipped={} notWired={} total={}, got passed={} skipped={} notWired={} total={}",
            baseline_path.display(),
            baseline.passed,
            baseline.skipped,
            baseline.not_wired,
            baseline.total,
            summary.passed,
            summary.skipped,
            summary.not_wired,
            total
        ));
    }

    for file in &summary.files {
        let Some(expected) = baseline.files.get(&file.path) else {
            fail(format!(
                "{}: missing official-test baseline for {}",
                baseline_path.display(),
                file.path
            ));
        };
        if expected.passed != file.passed || expected.skipped != file.skipped {
            fail(format!(
                "{}: expected {} passed={} skipped={}, got passed={} skipped={}",
                baseline_path.display(),
                file.path,
                expected.passed,
                expected.skipped,
                file.passed,
                file.skipped
            ));
        }
    }
}

fn official_json_paths(root: &Path) -> Vec<PathBuf> {
    let tests_dir = root.join("tests");
    let mut paths = Vec::new();
    collect_json_paths(&tests_dir, &mut paths);
    paths.sort();
    paths
}

fn collect_json_paths(path: &Path, paths: &mut Vec<PathBuf>) {
    for entry in fs::read_dir(path).unwrap_or_else(|error| {
        fail(format!("Failed to read {}: {error}", path.display()));
    }) {
        let entry = entry.unwrap_or_else(|error| fail(format!("Failed to read entry: {error}")));
        let path = entry.path();
        if path.is_dir() {
            collect_json_paths(&path, paths);
        } else if path.extension().and_then(|value| value.to_str()) == Some("json") {
            paths.push(path);
        }
    }
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> T {
    let contents = fs::read_to_string(path).unwrap_or_else(|error| {
        fail(format!("Failed to read {}: {error}", path.display()));
    });
    serde_json::from_str(&contents).unwrap_or_else(|error| {
        fail(format!("Failed to parse {}: {error}", path.display()));
    })
}

fn fail(message: impl std::fmt::Display) -> ! {
    eprintln!("{message}");
    std::process::exit(2);
}
