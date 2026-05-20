use mf2_prototype::{Diagnostic, FunctionCall, FunctionRegistry};

pub fn demo_function_registry() -> FunctionRegistry {
    FunctionRegistry::default().with_function("currency", format_currency)
}

fn format_currency(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let currency = call
        .option_value("currency")?
        .unwrap_or_else(|| "USD".to_string());
    format_currency_value(call.value(), &currency, call.locale())
}

fn format_currency_value(value: &str, currency: &str, locale: &str) -> Result<String, Diagnostic> {
    let amount = value.parse::<f64>().map_err(|_| {
        Diagnostic::new(
            "bad-operand",
            format!("Currency value must be numeric, got {value}."),
            0,
            0,
        )
    })?;
    if !amount.is_finite() {
        return Err(Diagnostic::new(
            "bad-operand",
            "Currency value must be finite.",
            0,
            0,
        ));
    }

    let currency = currency.to_ascii_uppercase();
    let fraction_digits = currency_fraction_digits(&currency);
    let scale = 10_i64.pow(fraction_digits);
    let rounded = (amount.abs() * scale as f64).round() as i64;
    let major = rounded / scale;
    let fraction = rounded % scale;
    let is_french = canonical_locale_prefix(locale) == "fr";
    let grouped = group_digits(&major.to_string(), if is_french { "\u{202f}" } else { "," });
    let number = if fraction_digits == 0 {
        grouped
    } else {
        format!(
            "{grouped}{}{fraction:0width$}",
            if is_french { "," } else { "." },
            width = fraction_digits as usize
        )
    };
    let symbol = currency_symbol(&currency, is_french);
    let negative = amount.is_sign_negative();

    if is_french {
        Ok(format!(
            "{}{} {}",
            if negative { "-" } else { "" },
            number,
            symbol
        ))
    } else if symbol.len() == 3 {
        Ok(format!(
            "{}{} {}",
            if negative { "-" } else { "" },
            symbol,
            number
        ))
    } else {
        Ok(format!(
            "{}{}{}",
            if negative { "-" } else { "" },
            symbol,
            number
        ))
    }
}

fn currency_fraction_digits(currency: &str) -> u32 {
    match currency {
        "JPY" | "KRW" => 0,
        _ => 2,
    }
}

fn currency_symbol(currency: &str, french: bool) -> String {
    match currency {
        "USD" if french => "$US".to_string(),
        "USD" => "$".to_string(),
        "EUR" => "€".to_string(),
        "JPY" => "¥".to_string(),
        "GBP" => "£".to_string(),
        _ => currency.to_string(),
    }
}

fn canonical_locale_prefix(locale: &str) -> String {
    locale
        .split(['-', '_'])
        .next()
        .unwrap_or("en")
        .to_ascii_lowercase()
}

fn group_digits(digits: &str, separator: &str) -> String {
    let mut output = String::new();
    for (index, ch) in digits.chars().rev().enumerate() {
        if index > 0 && index % 3 == 0 {
            output.push_str(separator);
        }
        output.push(ch);
    }
    output.chars().rev().collect()
}
