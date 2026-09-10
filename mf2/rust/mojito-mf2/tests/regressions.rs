use mojito_mf2::{
    format_message, format_message_with_options, parse_to_model, ArgumentValue, Arguments,
    FormatOptions, MessageModel,
};
use mojito_mf2::{AttributeValue, Expression, ExpressionArg, FunctionRef, PatternPart};
use std::collections::BTreeMap;

fn render(source: &str, value: &str, locale: &str) -> mojito_mf2::FormatResult {
    let parsed = parse_to_model(source);
    let model = parsed.model.expect("valid test message");
    let args: Arguments = [("x".to_string(), ArgumentValue::Number(value.to_string()))]
        .into_iter()
        .collect();
    format_message_with_options(&model, &args, &FormatOptions::new(locale)).unwrap()
}

#[test]
fn exact_decimal_arithmetic_and_large_values() {
    for (source, value, expected) in [
        ("{$x :offset add=0}", "0.29", "0.29"),
        ("{$x :offset add=0}", "-0.29", "-0.29"),
        ("{$x :percent}", "0.29", "29%"),
        ("{$x :percent}", "0.07", "7%"),
        ("{$x :number}", "9007199254740993", "9007199254740993"),
        ("{$x :integer}", "1e20", "100000000000000000000"),
        ("{$x :number maximumFractionDigits=2}", "1.235", "1.24"),
        ("{$x :number maximumFractionDigits=2}", "1.245", "1.24"),
        (
            "{$x :offset add=1}",
            "9007199254740993.1",
            "9007199254740994.1",
        ),
    ] {
        let result = render(source, value, "en");
        assert_eq!(result.value, expected);
        assert!(result.errors.is_empty(), "{:?}", result.errors);
    }
    let result = render(
        ".input {$x :number select=exact} .match $x 9007199254740993 {{exact}} * {{fallback}}",
        "9007199254740993",
        "en",
    );
    assert_eq!(result.value, "exact");
    assert!(result.errors.is_empty());
    assert_eq!(
        render(
            ".input {$x :percent} .match $x many {{many}} * {{fallback}}",
            "0.29",
            "ru"
        )
        .value,
        "many"
    );
}

#[test]
fn supported_numeric_bounds_report_errors() {
    for (source, value, code) in [
        ("{$x :number}", "1e4096", "bad-operand"),
        (
            "{$x :number minimumFractionDigits=3 maximumFractionDigits=2}",
            "1",
            "bad-option",
        ),
        ("{$x :number minimumFractionDigits=1001}", "1", "bad-option"),
        (
            ".input {$x :number} .match $x one {{one}} * {{fallback}}",
            "1e20",
            "bad-selector",
        ),
    ] {
        assert!(render(source, value, "en")
            .errors
            .iter()
            .any(|error| error.code == code));
    }
}

#[test]
fn quoted_braces_and_invalid_variant_keys() {
    assert_eq!(render("{{{|{|} {|}|}}}", "", "en").value, "{ }");
    let parsed = parse_to_model(".input {$x :string} .match $x * {{ok}} {x}");
    assert!(parsed
        .diagnostics
        .iter()
        .any(|error| error.code == "invalid-variant-key"));
}

#[test]
fn public_constructors_round_trip_and_validate() {
    let expression =
        Expression::variable("x").with_function(FunctionRef::new("number", BTreeMap::new()));
    let model = MessageModel::Message {
        declarations: vec![],
        pattern: vec![PatternPart::Expression(expression)],
    };
    assert_eq!(
        serde_json::from_str::<MessageModel>(&serde_json::to_string(&model).unwrap()).unwrap(),
        model
    );
    for attribute in [
        AttributeValue::Present(false),
        AttributeValue::Literal(ExpressionArg::Variable { name: "x".into() }),
    ] {
        let expression = Expression::literal("x")
            .with_attributes([("a".into(), attribute)].into_iter().collect());
        let model = MessageModel::Message {
            declarations: vec![],
            pattern: vec![PatternPart::Expression(expression)],
        };
        assert_eq!(
            format_message(&model, &Arguments::new()).unwrap_err().code,
            "invalid-model"
        );
    }
}

#[test]
fn default_numeric_isolation_uses_production_metadata_and_locale_direction() {
    use mojito_mf2::{BidiIsolation, FunctionRegistry};
    let model = parse_to_model("{$x :number}").model.unwrap();
    let args: Arguments = [("x", 1)].into();
    for (locale, expected) in [
        ("en", "1"),
        ("fr", "1"),
        ("az-IR", "\u{2068}1\u{2069}"),
        ("az-Latn-IR", "1"),
        ("sd-IN", "1"),
        ("und", "\u{2068}1\u{2069}"),
        ("xx-XXXX", "\u{2068}1\u{2069}"),
    ] {
        let output = format_message_with_options(
            &model,
            &args,
            &FormatOptions::new(locale).with_bidi_isolation(BidiIsolation::Default),
        )
        .unwrap();
        assert_eq!(output.value, expected, "{locale}");
    }
    let custom =
        FunctionRegistry::portable().with_function("number", |call| Ok(call.value().to_string()));
    let options = FormatOptions::new("en")
        .with_functions(&custom)
        .with_bidi_isolation(BidiIsolation::Default);
    assert_eq!(
        format_message_with_options(&model, &args, &options)
            .unwrap()
            .value,
        "\u{2068}1\u{2069}"
    );
    let explicit = parse_to_model(".input {$x :number u:dir=ltr} {{{$x}}}")
        .model
        .unwrap();
    assert_eq!(
        format_message_with_options(
            &explicit,
            &args,
            &FormatOptions::new("en").with_bidi_isolation(BidiIsolation::Default)
        )
        .unwrap()
        .value,
        "\u{2066}1\u{2069}"
    );
}

#[test]
fn unicode_direction_values_and_invalid_ignore_semantics() {
    use mojito_mf2::{BidiIsolation, FunctionRegistry};
    for (source, codes) in [
        ("{$x :number u:dir=invalid}", vec!["bad-option"]),
        (
            "{$x :number u:dir=$direction}",
            vec!["unresolved-variable", "bad-option"],
        ),
    ] {
        let result = render(source, "1", "en");
        assert_eq!(result.value, "1");
        assert_eq!(
            result
                .errors
                .iter()
                .map(|error| error.code.as_str())
                .collect::<Vec<_>>(),
            codes
        );
    }
    for (direction, expected) in [
        ("ltr", "\u{2066}1\u{2069}"),
        ("rtl", "\u{2067}1\u{2069}"),
        ("auto", "\u{2068}1\u{2069}"),
        ("inherit", "1"),
    ] {
        let model = parse_to_model("{$x :number u:dir=$direction}")
            .model
            .unwrap();
        let args = Arguments::new().with("x", 1).with("direction", direction);
        let result = format_message_with_options(
            &model,
            &args,
            &FormatOptions::new("en").with_bidi_isolation(BidiIsolation::Default),
        )
        .unwrap();
        assert_eq!(result.value, expected);
        assert!(result.errors.is_empty());
    }
    for annotation in [":string", ":string u:dir=inherit", ":string u:dir=invalid"] {
        let model =
            parse_to_model(&[".input {$x :number u:dir=ltr} {{{$x ", annotation, "}}}"].concat())
                .model
                .unwrap();
        let result = format_message_with_options(
            &model,
            Arguments::new().with("x", 1),
            &FormatOptions::new("en").with_bidi_isolation(BidiIsolation::Default),
        )
        .unwrap();
        assert_eq!(result.value, "1");
    }
    let functions = FunctionRegistry::portable().with_function("check", |call| {
        assert_eq!(call.option_value("u:dir")?, None);
        Ok(call.value().to_string())
    });
    let model = parse_to_model("{$x :check u:dir=rtl}").model.unwrap();
    let result = format_message_with_options(
        &model,
        Arguments::new().with("x", "ok"),
        &FormatOptions::new("en")
            .with_functions(&functions)
            .with_bidi_isolation(BidiIsolation::Default),
    )
    .unwrap();
    assert_eq!(result.value, "\u{2067}ok\u{2069}");
}

#[test]
fn long_declaration_chains_share_history_and_preserve_semantics() {
    for annotated in [false, true] {
        let mut source = String::from(".input {$x :number minimumFractionDigits=2 u:dir=ltr}\n");
        let mut previous = String::from("x");
        for index in 0..7000 {
            let name = format!("v{index}");
            let annotation = if annotated { " :offset add=1" } else { "" };
            source.push_str(&format!(".local ${name} = {{${previous}{annotation}}}\n"));
            previous = name;
        }
        if annotated {
            source.push_str(&format!("{{{{{{${previous} :number}}}}}}"));
            let result = render(&source, "1", "en");
            assert_eq!(result.value, "7001.00");
            assert!(result.errors.is_empty(), "{:?}", result.errors);
        } else {
            source.push_str(&format!(
                ".match ${previous}\none {{{{one}}}}\n* {{{{other}}}}"
            ));
            let result = render(&source, "1", "en");
            // Two displayed fraction digits retain the annotated plural operand.
            assert_eq!(result.value, "other");
            assert!(result.errors.is_empty(), "{:?}", result.errors);
        }
    }
}

#[test]
fn public_function_contexts_remain_send_and_sync() {
    fn assert_send_sync<T: Send + Sync>() {}
    assert_send_sync::<mojito_mf2::FunctionCall<'static>>();
    assert_send_sync::<mojito_mf2::FunctionMatch<'static>>();
    assert_send_sync::<mojito_mf2::FunctionSourceRef<'static>>();
}
