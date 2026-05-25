use std::collections::BTreeMap;
use std::fs;
use std::path::Path;

use mojito_mf2::{
    format_message_with_options, Arguments, BidiIsolation, FormatOptions, FunctionRegistry,
    MessageModel,
};
use sample_catalog_functions::sample_catalog_registry;
use serde::Deserialize;

mod sample_catalog_functions;

#[derive(Debug, Deserialize)]
struct Catalog {
    messages: BTreeMap<String, BTreeMap<String, MessageModel>>,
}

impl Catalog {
    fn load(path: &Path) -> Self {
        let contents = fs::read_to_string(path)
            .unwrap_or_else(|error| panic!("failed to read {}: {error}", path.display()));
        serde_json::from_str(&contents)
            .unwrap_or_else(|error| panic!("failed to parse {}: {error}", path.display()))
    }

    fn translate(
        &self,
        message_id: &str,
        locale: &str,
        arguments: Arguments,
        functions: &FunctionRegistry,
        bidi_isolation: BidiIsolation,
    ) -> String {
        let model = self.model(message_id, locale);
        let options = FormatOptions::new(locale)
            .with_functions(functions)
            .with_bidi_isolation(bidi_isolation);
        let formatted = format_message_with_options(model, &arguments, &options)
            .unwrap_or_else(|diagnostic| panic!("format failed: {diagnostic:?}"));
        if let Some(diagnostic) = formatted.errors.first() {
            panic!("format recovered with error: {diagnostic:?}");
        }
        formatted.value
    }

    fn model(&self, message_id: &str, locale: &str) -> &MessageModel {
        let localized = self
            .messages
            .get(message_id)
            .unwrap_or_else(|| panic!("missing message id: {message_id}"));
        lookup_locale(localized, locale, "en")
            .unwrap_or_else(|| panic!("missing locale {locale} and fallback en for {message_id}"))
    }
}

fn lookup_locale<'a, T>(
    values: &'a BTreeMap<String, T>,
    locale: &str,
    fallback: &str,
) -> Option<&'a T> {
    for candidate in locale_lookup_chain(locale) {
        if let Some(value) = lookup_canonical_key(values, &candidate) {
            return Some(value);
        }
    }
    lookup_canonical_key(values, &canonical_locale_key(fallback))
}

fn lookup_canonical_key<'a, T>(
    values: &'a BTreeMap<String, T>,
    canonical_key: &str,
) -> Option<&'a T> {
    values
        .iter()
        .find(|(key, _)| canonical_locale_key(key) == canonical_key)
        .map(|(_, value)| value)
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

fn canonical_locale_key(locale: &str) -> String {
    locale_parts(locale).join("-")
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

fn main() {
    let catalog = Catalog::load(Path::new("../../examples/catalog.json"));
    let functions = sample_catalog_registry();
    let examples = [
        (
            "welcome",
            "fr",
            Arguments::new().with("name", "Mojito"),
            BidiIsolation::None,
            "Bienvenue, Mojito !",
        ),
        (
            "welcome",
            "fr-CA",
            Arguments::new().with("name", "Mojito"),
            BidiIsolation::None,
            "Bienvenue, Mojito !",
        ),
        (
            "checkout.total",
            "en",
            Arguments::new().with("amount", 1234.5),
            BidiIsolation::None,
            "Total: $1,234.50",
        ),
        (
            "checkout.total",
            "fr",
            Arguments::new().with("amount", 1234.5),
            BidiIsolation::None,
            "Total : 1\u{202f}234,50 €",
        ),
        (
            "debug.raw",
            "en",
            Arguments::new().with("value", 1234.5),
            BidiIsolation::None,
            "Raw: number=1234.5",
        ),
        (
            "file.saved",
            "en",
            Arguments::new().with("fileName", "שלום.txt"),
            BidiIsolation::Default,
            "File \u{2068}שלום.txt\u{2069} saved.",
        ),
        (
            "cart.items",
            "en",
            Arguments::new().with("count", 1),
            BidiIsolation::None,
            "1 item",
        ),
        (
            "cart.items",
            "en",
            Arguments::new().with("count", 5),
            BidiIsolation::None,
            "5 items",
        ),
        (
            "cart.items",
            "ru",
            Arguments::new().with("count", 2),
            BidiIsolation::None,
            "2 предмета",
        ),
        (
            "cart.items",
            "ru",
            Arguments::new().with("count", 5),
            BidiIsolation::None,
            "5 предметов",
        ),
        (
            "assignee.files",
            "en",
            Arguments::new().with("gender", "male").with("count", 1),
            BidiIsolation::None,
            "He reviewed 1 file",
        ),
        (
            "assignee.files",
            "en",
            Arguments::new().with("gender", "female").with("count", 3),
            BidiIsolation::None,
            "She reviewed 3 files",
        ),
        (
            "assignee.files",
            "en",
            Arguments::new().with("gender", "unknown").with("count", 2),
            BidiIsolation::None,
            "They reviewed 2 files",
        ),
    ];

    for (message_id, locale, arguments, bidi_isolation, expected) in examples {
        let actual = catalog.translate(message_id, locale, arguments, &functions, bidi_isolation);
        assert_eq!(actual, expected, "{message_id}/{locale}");
        println!("{message_id}[{locale}] -> \"{actual}\"");
        if bidi_isolation != BidiIsolation::None {
            println!(
                "{message_id}[{locale}].escaped -> \"{}\"",
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
