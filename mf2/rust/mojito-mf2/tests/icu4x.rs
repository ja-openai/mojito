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
fn icu4x_registry_keeps_plural_operands_separate_from_display() {
    let registry = FunctionRegistry::icu4x();
    let cases = [
        (":number", "fr", "1000000", "many"),
        (":number minimumFractionDigits=1", "ru", "1", "other"),
        (":integer", "fr", "1000000.9", "many"),
        (":percent", "fr", "10000", "many"),
    ];

    for (function, locale, value, expected) in cases {
        let source = format!(
            ".input {{$value {function}}}\n.match $value\n\
             zero {{{{zero}}}}\none {{{{one}}}}\ntwo {{{{two}}}}\n\
             few {{{{few}}}}\nmany {{{{many}}}}\nother {{{{other}}}}\n* {{{{other}}}}"
        );
        assert_eq!(
            format_with(
                &source,
                locale,
                Arguments::new().with("value", value),
                &registry,
            ),
            expected,
            "{function} {locale} {value}"
        );
    }

    let offset_source = ".input {$value :integer}\n\
                         .local $adjusted = {$value :offset subtract=1}\n\
                         .match $adjusted\n\
                         zero {{zero}}\none {{one}}\ntwo {{two}}\nfew {{few}}\n\
                         many {{many}}\nother {{other}}\n* {{other}}";
    assert_eq!(
        format_with(
            offset_source,
            "fr",
            Arguments::new().with("value", "1000001"),
            &registry,
        ),
        "many"
    );
}

#[test]
fn icu4x_registry_reannotations_use_original_operands() {
    let registry = FunctionRegistry::icu4x();

    let result = format_result_with(
        ".local $localized = {1000000 :number}\n\
         {{Value {$localized :number maximumFractionDigits=0}}}",
        "fr",
        Arguments::new(),
        &registry,
    );

    assert!(result.is_ok(), "{:?}", result.errors);
    let digits: String = result.value.chars().filter(char::is_ascii_digit).collect();
    assert_eq!(digits, "1000000", "{}", result.value);

    let datetime = format_result_with(
        ".local $datetime = {|2026-05-21T14:30:15Z| :datetime dateStyle=long timeStyle=long timeZone=UTC}\n\
         {{Value {$datetime :datetime dateStyle=short timeStyle=short timeZone=UTC}}}",
        "en",
        Arguments::new(),
        &registry,
    );

    assert!(datetime.is_ok(), "{:?}", datetime.errors);
    assert!(datetime.value.contains("5/21/26"), "{}", datetime.value);
    assert!(datetime.value.contains("2:30"), "{}", datetime.value);
}

#[test]
fn icu4x_numeric_chains_preserve_semantic_operands_and_options() {
    let registry = FunctionRegistry::icu4x();
    let reannotated = format_result_with(
        ".local $number = {1.29 :number maximumFractionDigits=1}\n\
         {{Value {$number :number maximumFractionDigits=2}}}",
        "en",
        Arguments::new(),
        &registry,
    );

    assert!(reannotated.is_ok(), "{:?}", reannotated.errors);
    assert_eq!(reannotated.value, "Value 1.29");

    let selected = format_result_with(
        ".local $rounded = {1.29 :number maximumFractionDigits=1}\n\
         .local $selected = {$rounded :number}\n\
         .match $selected\n\
         1.3 {{rounded}}\n1.29 {{raw}}\n* {{fallback}}",
        "fr",
        Arguments::new(),
        &registry,
    );

    assert!(selected.is_ok(), "{:?}", selected.errors);
    assert_eq!(selected.value, "rounded");

    let offset = format_result_with(
        ".local $number = {-1.9 :number maximumFractionDigits=0}\n\
         .local $offset = {$number :offset add=1}\n\
         .local $copy = {$offset :number maximumFractionDigits=1}\n\
         {{{$copy}}}",
        "en",
        Arguments::new(),
        &registry,
    );

    assert!(offset.is_ok(), "{:?}", offset.errors);
    assert_eq!(offset.value, "-0.9");
}

#[test]
fn icu4x_temporal_functions_treat_date_only_operands_as_midnight() {
    let registry = FunctionRegistry::icu4x();
    let datetime = format_result_with(
        "{$value :datetime dateStyle=short timeStyle=short timeZone=UTC}",
        "en",
        Arguments::new().with("value", "2026-05-21"),
        &registry,
    );

    assert!(datetime.is_ok(), "{:?}", datetime.errors);
    assert!(datetime.value.contains("5/21/26"), "{}", datetime.value);
    assert!(datetime.value.contains("12:00"), "{}", datetime.value);

    let time = format_result_with(
        "{$value :time timeStyle=short timeZone=UTC}",
        "en",
        Arguments::new().with("value", "2026-05-21"),
        &registry,
    );

    assert!(time.is_ok(), "{:?}", time.errors);
    assert!(time.value.contains("12:00"), "{}", time.value);
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
