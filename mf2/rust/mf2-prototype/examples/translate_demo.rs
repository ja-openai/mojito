use std::collections::BTreeMap;
use std::fs;
use std::path::Path;

use mf2_prototype::{format_model_with_locale, lookup_locale, MessageModel};
use serde::Deserialize;

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
        arguments: BTreeMap<String, serde_json::Value>,
    ) -> String {
        let model = self.model(message_id, locale);
        format_model_with_locale(model, &arguments, locale)
            .unwrap_or_else(|diagnostic| panic!("format failed: {diagnostic:?}"))
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

fn main() {
    let catalog = Catalog::load(Path::new("../../examples/catalog.json"));
    let examples = [
        (
            "welcome",
            "fr",
            args([("name", serde_json::Value::String("Mojito".to_string()))]),
            "Bienvenue, Mojito !",
        ),
        (
            "welcome",
            "fr-CA",
            args([("name", serde_json::Value::String("Mojito".to_string()))]),
            "Bienvenue, Mojito !",
        ),
        (
            "checkout.total",
            "en",
            args([("amount", serde_json::Value::from(1234.5))]),
            "Total: $1,234.50",
        ),
        (
            "checkout.total",
            "fr",
            args([("amount", serde_json::Value::from(1234.5))]),
            "Total : 1\u{202f}234,50 €",
        ),
        ("cart.items", "en", args([("count", 1.into())]), "1 item"),
        ("cart.items", "en", args([("count", 5.into())]), "5 items"),
        (
            "cart.items",
            "ru",
            args([("count", 2.into())]),
            "2 предмета",
        ),
        (
            "cart.items",
            "ru",
            args([("count", 5.into())]),
            "5 предметов",
        ),
    ];

    for (message_id, locale, arguments, expected) in examples {
        let actual = catalog.translate(message_id, locale, arguments);
        assert_eq!(actual, expected, "{message_id}/{locale}");
        println!("{message_id}[{locale}] -> \"{actual}\"");
    }
}

fn args<const N: usize>(
    values: [(&'static str, serde_json::Value); N],
) -> BTreeMap<String, serde_json::Value> {
    values
        .into_iter()
        .map(|(key, value)| (key.to_string(), value))
        .collect()
}
