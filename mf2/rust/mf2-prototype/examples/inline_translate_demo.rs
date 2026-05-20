use std::collections::BTreeMap;
use std::fs;
use std::path::Path;

use demo_functions::demo_function_registry;
use mf2_prototype::{format_model_with_locale_and_functions, parse_to_model, FunctionRegistry};
use serde::Deserialize;

mod demo_functions;

#[derive(Debug, Deserialize)]
struct Demo {
    cases: Vec<DemoCase>,
}

#[derive(Debug, Deserialize)]
struct DemoCase {
    label: String,
    source: String,
    locale: String,
    arguments: BTreeMap<String, serde_json::Value>,
    expected: String,
}

fn translate(
    source: &str,
    locale: &str,
    arguments: BTreeMap<String, serde_json::Value>,
    functions: &FunctionRegistry,
) -> String {
    let parsed = parse_to_model(source);
    if !parsed.diagnostics.is_empty() {
        panic!("parse failed: {:?}", parsed.diagnostics);
    }
    let model = parsed.model.expect("parser returned model");
    format_model_with_locale_and_functions(&model, &arguments, locale, functions)
        .unwrap_or_else(|diagnostic| panic!("format failed: {diagnostic:?}"))
}

fn main() {
    let demo = load_demo(Path::new("../../examples/inline-source-demo.json"));
    let functions = demo_function_registry();
    for case in demo.cases {
        let actual = translate(&case.source, &case.locale, case.arguments, &functions);
        assert_eq!(actual, case.expected, "{}/{}", case.label, case.locale);
        println!("{}[{}] -> \"{actual}\"", case.label, case.locale);
    }
}

fn load_demo(path: &Path) -> Demo {
    let contents = fs::read_to_string(path)
        .unwrap_or_else(|error| panic!("failed to read {}: {error}", path.display()));
    serde_json::from_str(&contents)
        .unwrap_or_else(|error| panic!("failed to parse {}: {error}", path.display()))
}
