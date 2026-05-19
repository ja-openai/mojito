use std::collections::BTreeMap;

use mf2_prototype::{format_model_with_locale, parse_to_model};

fn translate(source: &str, locale: &str, arguments: BTreeMap<String, serde_json::Value>) -> String {
    let parsed = parse_to_model(source);
    if !parsed.diagnostics.is_empty() {
        panic!("parse failed: {:?}", parsed.diagnostics);
    }
    let model = parsed.model.expect("parser returned model");
    format_model_with_locale(&model, &arguments, locale)
        .unwrap_or_else(|diagnostic| panic!("format failed: {diagnostic:?}"))
}

fn main() {
    let welcome = "Welcome, {$name}!";
    let cart_items_en = r#".input {$count :number}
.match $count
one {{{$count} item}}
* {{{$count} items}}"#;
    let cart_items_ru = r#".input {$count :number}
.match $count
one {{{$count} предмет}}
few {{{$count} предмета}}
many {{{$count} предметов}}
* {{{$count} предмета}}"#;

    let examples = [
        (
            "welcome",
            "en",
            welcome,
            args([("name", serde_json::Value::String("Mojito".to_string()))]),
            "Welcome, Mojito!",
        ),
        (
            "cart.items",
            "en",
            cart_items_en,
            args([("count", 1.into())]),
            "1 item",
        ),
        (
            "cart.items",
            "en",
            cart_items_en,
            args([("count", 5.into())]),
            "5 items",
        ),
        (
            "cart.items",
            "ru",
            cart_items_ru,
            args([("count", 2.into())]),
            "2 предмета",
        ),
        (
            "cart.items",
            "ru",
            cart_items_ru,
            args([("count", 5.into())]),
            "5 предметов",
        ),
    ];

    for (label, locale, source, arguments, expected) in examples {
        let actual = translate(source, locale, arguments);
        assert_eq!(actual, expected, "{label}/{locale}");
        println!("{label}[{locale}] -> \"{actual}\"");
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
