use std::collections::BTreeMap;
use std::env;
use std::fs;
use std::hint::black_box;
use std::path::Path;
use std::process;
use std::time::Instant;

use mf2_prototype::{format_model_with_locale, parse_to_model, MessageModel};
use serde::Deserialize;

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
struct FormatCase {
    #[serde(default = "default_locale")]
    locale: String,
    arguments: BTreeMap<String, serde_json::Value>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SourceOnlyFixture {
    source: String,
}

fn main() {
    let mut args = env::args().skip(1);
    let Some(command) = args.next() else {
        usage_and_exit();
    };
    let Some(path) = args.next() else {
        usage_and_exit();
    };

    match command.as_str() {
        "compile" => compile(&read_to_string(&path)),
        "format-first-case" => format_first_case(&read_to_string(&path)),
        "bench" => bench(&path, args.next(), args.next()),
        "bench-parse" => bench_parse(&path, args.next(), args.next()),
        _ => usage_and_exit(),
    }
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
        .into_iter()
        .map(|path| {
            let content = read_to_string(path.to_str().unwrap());
            serde_json::from_str::<SourceOnlyFixture>(&content)
                .map(|fixture| fixture.source)
                .unwrap_or_else(|error| {
                    eprintln!("Failed to parse source from {}: {error}", path.display());
                    process::exit(2);
                })
        })
        .collect()
}

fn read_fixture_dir(dir: &Path) -> Vec<SourceFixture> {
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
        .into_iter()
        .map(|path| {
            serde_json::from_str::<SourceFixture>(&read_to_string(path.to_str().unwrap()))
                .unwrap_or_else(|error| {
                    eprintln!("Failed to parse {}: {error}", path.display());
                    process::exit(2);
                })
        })
        .collect()
}

fn read_to_string(path: &str) -> String {
    fs::read_to_string(path).unwrap_or_else(|error| {
        eprintln!("Failed to read {path}: {error}");
        process::exit(2);
    })
}

fn usage_and_exit() -> ! {
    eprintln!(
        "Usage:\n  mf2-prototype compile <source-or-fixture.json>\n  mf2-prototype format-first-case <fixture.json>\n  mf2-prototype bench <fixture-dir> [iterations] [warmup-iterations]\n  mf2-prototype bench-parse <fixture-dir> [iterations] [warmup-iterations]"
    );
    process::exit(2);
}
