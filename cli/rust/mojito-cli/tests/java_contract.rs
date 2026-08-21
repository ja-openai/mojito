use regex::Regex;
use std::collections::{BTreeMap, BTreeSet};

const PARAMS: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../src/main/java/com/box/l10n/mojito/cli/command/param/Param.java"
));
const PULL: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../src/main/java/com/box/l10n/mojito/cli/command/PullCommand.java"
));
const PUSH: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../src/main/java/com/box/l10n/mojito/cli/command/PushCommand.java"
));
const IMPORT: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../src/main/java/com/box/l10n/mojito/cli/command/ImportLocalizedAssetCommand.java"
));
const RUST_ARGUMENTS: &str = include_str!("../src/args.rs");

#[test]
fn every_existing_java_workflow_flag_is_declared_by_the_native_cli() {
    let constant_pattern =
        Regex::new(r#"public static final String ([A-Z_]+)\s*=\s*"([^"]+)""#).unwrap();
    let constants: BTreeMap<&str, &str> = constant_pattern
        .captures_iter(PARAMS)
        .filter_map(|captures| {
            let value = captures.get(2)?.as_str();
            value
                .starts_with('-')
                .then_some((captures.get(1)?.as_str(), value))
        })
        .collect();
    let parameter_pattern = Regex::new(r"(?s)@Parameter\s*\((.*?)\)").unwrap();
    let reference_pattern = Regex::new(r"Param\.([A-Z_]+)").unwrap();
    let literal_pattern = Regex::new(r#""(-{1,2}[A-Za-z][A-Za-z0-9-]*)""#).unwrap();

    let mut java_flags = BTreeSet::new();
    for command in [PULL, PUSH, IMPORT] {
        for parameter in parameter_pattern.captures_iter(command) {
            let block = parameter.get(1).unwrap().as_str();
            for reference in reference_pattern.captures_iter(block) {
                if let Some(value) = constants.get(reference.get(1).unwrap().as_str()) {
                    java_flags.insert((*value).to_owned());
                }
            }
            for literal in literal_pattern.captures_iter(block) {
                java_flags.insert(literal.get(1).unwrap().as_str().to_owned());
            }
        }
    }
    let rust_flags: BTreeSet<String> = literal_pattern
        .captures_iter(RUST_ARGUMENTS)
        .map(|literal| literal.get(1).unwrap().as_str().to_owned())
        .collect();

    assert!(
        java_flags.len() >= 40,
        "Java option discovery unexpectedly found only {} flags",
        java_flags.len()
    );
    let missing: Vec<_> = java_flags.difference(&rust_flags).collect();
    assert!(
        missing.is_empty(),
        "native CLI is missing Java workflow flags: {missing:?}"
    );
}
