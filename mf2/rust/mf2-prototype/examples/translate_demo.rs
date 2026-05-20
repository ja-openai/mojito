use std::collections::BTreeMap;
use std::fs;
use std::path::Path;

use demo_functions::demo_function_registry;
use mf2_prototype::{
    format_model_with_locale_and_functions_and_bidi, lookup_locale, BidiIsolation,
    FunctionRegistry, MessageModel,
};
use serde::Deserialize;

mod demo_functions;

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
        functions: &FunctionRegistry,
        bidi_isolation: BidiIsolation,
    ) -> String {
        let model = self.model(message_id, locale);
        format_model_with_locale_and_functions_and_bidi(
            model,
            &arguments,
            locale,
            functions,
            bidi_isolation,
        )
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
    let functions = demo_function_registry();
    let examples = [
        (
            "welcome",
            "fr",
            args([("name", serde_json::Value::String("Mojito".to_string()))]),
            BidiIsolation::None,
            "Bienvenue, Mojito !",
        ),
        (
            "welcome",
            "fr-CA",
            args([("name", serde_json::Value::String("Mojito".to_string()))]),
            BidiIsolation::None,
            "Bienvenue, Mojito !",
        ),
        (
            "checkout.total",
            "en",
            args([("amount", serde_json::Value::from(1234.5))]),
            BidiIsolation::None,
            "Total: $1,234.50",
        ),
        (
            "checkout.total",
            "fr",
            args([("amount", serde_json::Value::from(1234.5))]),
            BidiIsolation::None,
            "Total : 1\u{202f}234,50 €",
        ),
        (
            "file.saved",
            "en",
            args([(
                "fileName",
                serde_json::Value::String("שלום.txt".to_string()),
            )]),
            BidiIsolation::Default,
            "File \u{2068}שלום.txt\u{2069} saved.",
        ),
        (
            "cart.items",
            "en",
            args([("count", 1.into())]),
            BidiIsolation::None,
            "1 item",
        ),
        (
            "cart.items",
            "en",
            args([("count", 5.into())]),
            BidiIsolation::None,
            "5 items",
        ),
        (
            "cart.items",
            "ru",
            args([("count", 2.into())]),
            BidiIsolation::None,
            "2 предмета",
        ),
        (
            "cart.items",
            "ru",
            args([("count", 5.into())]),
            BidiIsolation::None,
            "5 предметов",
        ),
        (
            "assignee.files",
            "en",
            args([
                ("gender", serde_json::Value::String("male".to_string())),
                ("count", 1.into()),
            ]),
            BidiIsolation::None,
            "He reviewed 1 file",
        ),
        (
            "assignee.files",
            "en",
            args([
                ("gender", serde_json::Value::String("female".to_string())),
                ("count", 3.into()),
            ]),
            BidiIsolation::None,
            "She reviewed 3 files",
        ),
        (
            "assignee.files",
            "en",
            args([
                ("gender", serde_json::Value::String("unknown".to_string())),
                ("count", 2.into()),
            ]),
            BidiIsolation::None,
            "They reviewed 2 files",
        ),
    ];

    for (message_id, locale, arguments, bidi_isolation, expected) in examples {
        let actual = catalog.translate(message_id, locale, arguments, &functions, bidi_isolation);
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
