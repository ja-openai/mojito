use std::collections::BTreeMap;
use std::fs;
use std::path::Path;

use demo_functions::demo_function_registry;
use mf2_prototype::{
    format_model_with_locale_and_functions_and_bidi, parse_to_model, BidiIsolation,
    FunctionRegistry,
};
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
    #[serde(rename = "bidiIsolation")]
    #[serde(default)]
    bidi_isolation: Option<String>,
    arguments: BTreeMap<String, serde_json::Value>,
    expected: String,
}

fn translate(
    source: &str,
    locale: &str,
    arguments: BTreeMap<String, serde_json::Value>,
    functions: &FunctionRegistry,
    bidi_isolation: BidiIsolation,
) -> String {
    let parsed = parse_to_model(source);
    if !parsed.diagnostics.is_empty() {
        panic!("parse failed: {:?}", parsed.diagnostics);
    }
    let model = parsed.model.expect("parser returned model");
    format_model_with_locale_and_functions_and_bidi(
        &model,
        &arguments,
        locale,
        functions,
        bidi_isolation,
    )
    .unwrap_or_else(|diagnostic| panic!("format failed: {diagnostic:?}"))
}

fn main() {
    let demo = load_demo(Path::new("../../examples/inline-source-demo.json"));
    let functions = demo_function_registry();
    for case in demo.cases {
        let bidi_isolation = BidiIsolation::from_name(case.bidi_isolation.as_deref());
        let actual = translate(
            &case.source,
            &case.locale,
            case.arguments,
            &functions,
            bidi_isolation,
        );
        assert_eq!(actual, case.expected, "{}/{}", case.label, case.locale);
        println!("{}[{}] -> \"{actual}\"", case.label, case.locale);
        if bidi_isolation != BidiIsolation::None {
            println!(
                "{}[{}].escaped -> \"{}\"",
                case.label,
                case.locale,
                escaped_non_ascii(&actual)
            );
        }
    }
}

fn escaped_non_ascii(value: &str) -> String {
    let mut output = String::new();
    for ch in value.chars() {
        if ch.is_ascii_graphic() || ch == ' ' {
            output.push(ch);
        } else {
            output.push_str(&format!("\\u{:04X}", ch as u32));
        }
    }
    output
}

fn load_demo(path: &Path) -> Demo {
    let contents = fs::read_to_string(path)
        .unwrap_or_else(|error| panic!("failed to read {}: {error}", path.display()));
    serde_json::from_str(&contents)
        .unwrap_or_else(|error| panic!("failed to parse {}: {error}", path.display()))
}
