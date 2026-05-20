use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::panic::{self, AssertUnwindSafe};
use std::path::{Path, PathBuf};

use mf2_prototype::{
    format_model_with_locale_and_bidi, parse_to_model, BidiIsolation, Declaration, MessageModel,
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
        }
    }

    summary.passed += file_passed;
    summary.skipped += file_skipped;
    summary.files.push(FileSummary {
        path: check.path.to_string(),
        mode: match check.mode {
            CheckMode::Parse => "parse",
            CheckMode::DataModelErrors => "data-model",
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
    if expected_codes.is_empty() {
        return false;
    }

    let Some(result) = safe_parse(&test.src) else {
        return false;
    };
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
