use std::collections::BTreeMap;
use std::fs;
use std::path::Path;

use mojito_mf2::{
    format_message_with_options, parse_to_model, ArgumentValue, Arguments, BidiIsolation,
    FormatOptions, FunctionRegistry,
};
use sample_catalog_functions::sample_catalog_registry;
use serde::Deserialize;

mod sample_catalog_functions;

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
    let options = FormatOptions::new(locale)
        .with_functions(functions)
        .with_bidi_isolation(bidi_isolation);
    let arguments = arguments_from_json(&arguments);
    let formatted = format_message_with_options(&model, &arguments, &options)
        .unwrap_or_else(|diagnostic| panic!("format failed: {diagnostic:?}"));
    if let Some(diagnostic) = formatted.errors.first() {
        panic!("format recovered with error: {diagnostic:?}");
    }
    formatted.value
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

fn main() {
    let demo = load_demo(Path::new("../../examples/inline-source-demo.json"));
    let functions = sample_catalog_registry();
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
