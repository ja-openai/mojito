#[cfg(feature = "icu4x")]
use mojito_mf2::{
    format_message_with_options, parse_to_model, Arguments, FormatOptions, FunctionRegistry,
};

#[cfg(feature = "icu4x")]
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let registry = FunctionRegistry::icu4x();
    let examples = [
        (
            "fr number",
            "{$value :number minimumFractionDigits=2}",
            "fr",
            Arguments::new().with("value", 12345.5),
        ),
        (
            "ar number",
            "{$value :number maximumFractionDigits=1}",
            "ar",
            Arguments::new().with("value", 12345.5),
        ),
        (
            "ja date",
            "{$value :date dateStyle=long timeZone=UTC}",
            "ja",
            Arguments::new().with("value", "2026-05-21"),
        ),
        (
            "en time",
            "{$value :time timeStyle=medium timeZone=UTC}",
            "en",
            Arguments::new().with("value", "2026-05-21T14:30:15Z"),
        ),
        (
            "fr datetime",
            "{$value :datetime dateStyle=medium timeStyle=medium timeZone=UTC}",
            "fr",
            Arguments::new().with("value", "2026-05-21T14:30:15Z"),
        ),
    ];

    for (label, source, locale, arguments) in examples {
        let parsed = parse_to_model(source);
        if !parsed.diagnostics.is_empty() {
            return Err(format!("{label}: parse diagnostics {:?}", parsed.diagnostics).into());
        }
        let model = parsed.model.expect("model");
        let options = FormatOptions::new(locale).with_functions(&registry);
        let result = format_message_with_options(&model, arguments, &options)
            .map_err(|error| format!("{label}: format failed with {error:?}"))?;
        if result.has_errors() {
            return Err(format!("{label}: format errors {:?}", result.errors).into());
        }
        println!("{label} -> {}", result.value);
    }

    Ok(())
}

#[cfg(not(feature = "icu4x"))]
fn main() {
    eprintln!("Run with: cargo run --features icu4x --example icu4x_demo");
}
