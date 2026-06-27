#![cfg(feature = "icu4x")]

use mojito_mf2::{
    format_message_with_options, parse_to_model, Arguments, FormatOptions, FunctionRegistry,
};

#[test]
fn icu4x_registry_formats_numbers_and_dates_across_locales() {
    let registry = FunctionRegistry::icu4x();

    let fr_number = format_with(
        "{$value :number minimumFractionDigits=2}",
        "fr",
        Arguments::new().with("value", 12345.5),
        &registry,
    );
    assert!(fr_number.contains("12"));
    assert!(fr_number.contains(','));

    let ar_number = format_with(
        "{$value :number maximumFractionDigits=1}",
        "ar",
        Arguments::new().with("value", 12345.5),
        &registry,
    );
    assert!(!ar_number.is_empty());

    let ja_date = format_with(
        "{$value :date dateStyle=long timeZone=UTC}",
        "ja",
        Arguments::new().with("value", "2026-05-21"),
        &registry,
    );
    assert!(ja_date.contains("2026"));

    let en_time = format_with(
        "{$value :time timeStyle=medium timeZone=UTC}",
        "en",
        Arguments::new().with("value", "2026-05-21T14:30:15Z"),
        &registry,
    );
    assert!(en_time.contains("14") || en_time.contains("2"));

    let fr_datetime = format_with(
        "{$value :datetime dateStyle=medium timeStyle=medium timeZone=UTC}",
        "fr",
        Arguments::new().with("value", "2026-05-21T14:30:15Z"),
        &registry,
    );
    assert!(fr_datetime.contains("2026"));
}

#[test]
fn icu4x_registry_is_explicit_and_does_not_fake_unsupported_functions() {
    let registry = FunctionRegistry::icu4x();

    let default_date = format_with_default_registry(
        "{$value :date}",
        Arguments::new().with("value", "2026-05-21"),
    );
    assert_eq!(default_date.errors[0].code, "unknown-function");

    let currency = format_result_with(
        "{$value :currency currency=EUR}",
        "en",
        Arguments::new().with("value", 9876),
        &registry,
    );
    assert!(currency.has_errors());
    assert_eq!(currency.errors[0].code, "unknown-function");

    let relative_time = format_result_with(
        "{$value :relativeTime unit=day}",
        "en",
        Arguments::new().with("value", -1),
        &registry,
    );
    assert!(relative_time.has_errors());
    assert_eq!(relative_time.errors[0].code, "unknown-function");
}

#[test]
fn icu4x_registry_rejects_oversized_time_zone_options() {
    let registry = FunctionRegistry::icu4x();
    let source = "{$value :datetime dateStyle=medium timeStyle=medium timeZone=".to_string()
        + &"A".repeat(257)
        + "}";
    let result = format_result_with(
        &source,
        "en",
        Arguments::new().with("value", "2020-01-02T03:04:05Z"),
        &registry,
    );
    assert!(result.has_errors());
    assert_eq!(result.errors[0].code, "bad-option");
}

#[test]
fn icu4x_registry_rejects_oversized_locales() {
    let registry = FunctionRegistry::icu4x();
    let result = format_result_with(
        "{$value :number}",
        &"a".repeat(257),
        Arguments::new().with("value", 1),
        &registry,
    );
    assert!(result.has_errors());
    assert_eq!(result.errors[0].code, "bad-option");
}

fn format_with(
    source: &str,
    locale: &str,
    arguments: Arguments,
    registry: &FunctionRegistry,
) -> String {
    let result = format_result_with(source, locale, arguments, registry);
    assert!(
        result.is_ok(),
        "expected no formatter errors, got {:?}",
        result.errors
    );
    result.value
}

fn format_result_with(
    source: &str,
    locale: &str,
    arguments: Arguments,
    registry: &FunctionRegistry,
) -> mojito_mf2::FormatResult {
    let parsed = parse_to_model(source);
    assert!(parsed.diagnostics.is_empty(), "{:?}", parsed.diagnostics);
    let model = parsed.model.expect("model");
    let options = FormatOptions::new(locale).with_functions(registry);
    format_message_with_options(&model, arguments, &options).expect("format")
}

fn format_with_default_registry(source: &str, arguments: Arguments) -> mojito_mf2::FormatResult {
    let parsed = parse_to_model(source);
    assert!(parsed.diagnostics.is_empty(), "{:?}", parsed.diagnostics);
    let model = parsed.model.expect("model");
    mojito_mf2::format_message(&model, arguments).expect("format")
}
