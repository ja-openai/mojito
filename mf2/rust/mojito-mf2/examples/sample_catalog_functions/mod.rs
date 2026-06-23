use std::collections::BTreeMap;
use std::fs;
use std::path::Path;
use std::sync::OnceLock;

use mojito_mf2::{
    format_message_with_options, parse_to_model, ArgumentValue, Arguments, Diagnostic,
    FormatOptions, FunctionCall, FunctionRegistry, MessageModel,
};
use serde::Deserialize;

pub fn sample_catalog_registry() -> FunctionRegistry {
    FunctionRegistry::default()
        .with_function("currency", format_currency)
        .with_function("rawType", format_raw_type)
        .with_function("relativeTime", format_relative_time)
}

fn format_currency(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let currency = call
        .option_value("currency")?
        .unwrap_or_else(|| "USD".to_string());
    format_currency_value(call.value(), &currency, call.locale())
}

fn format_raw_type(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let kind = match call.raw_value() {
        ArgumentValue::Number(_) => "number",
        ArgumentValue::Bool(_) => "bool",
        ArgumentValue::Null => "null",
        _ => "string",
    };
    Ok(format!("{kind}={}", call.value()))
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

fn format_relative_time(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let style = validated_option(&call, "style", "short", &["long", "short", "narrow"])?;
    let numeric = validated_option(&call, "numeric", "always", &["always", "auto"])?;
    let policy = validated_option(&call, "policy", "precise", &["precise", "compact", "chat"])?;
    let unit = validated_option(
        &call,
        "unit",
        "auto",
        &[
            "auto", "second", "minute", "hour", "day", "week", "month", "quarter", "year",
        ],
    )?;
    let seconds = parse_finite_f64(call.value(), "Relative time value")?;
    let selected_unit = if unit == "auto" {
        select_relative_time_unit(seconds, &policy)
    } else {
        unit
    };
    let quantity = relative_time_quantity(seconds, &selected_unit);

    if use_relative_zero(&policy, &numeric, seconds) {
        if let Some(relative) = relative_term(call.locale(), &style, &selected_unit, "0")? {
            return Ok(relative);
        }
    }
    if numeric == "auto" {
        if let Some(offset) = relative_offset(seconds, quantity) {
            if let Some(relative) = relative_term(call.locale(), &style, &selected_unit, &offset)? {
                return Ok(relative);
            }
        }
    }

    let direction = if is_negative_relative_time(seconds) {
        "past"
    } else {
        "future"
    };
    let category = cardinal_plural_category(call.locale(), &quantity.to_string())?;
    let pattern =
        relative_time_pattern(call.locale(), &style, &selected_unit, direction, &category)?;
    Ok(pattern.replace("{0}", &quantity.to_string()))
}

fn cardinal_plural_category(locale: &str, value: &str) -> Result<String, Diagnostic> {
    static MODEL: OnceLock<MessageModel> = OnceLock::new();
    let model = MODEL.get_or_init(|| {
        parse_to_model(
            ".input {$count :number}\n.match $count\nzero {{zero}}\none {{one}}\ntwo {{two}}\nfew {{few}}\nmany {{many}}\nother {{other}}\n* {{other}}",
        )
        .model
        .expect("plural category helper source parses")
    });
    let result = format_message_with_options(
        model,
        Arguments::new().with("count", ArgumentValue::number(value)),
        &FormatOptions::new(locale),
    )?;
    result
        .errors
        .into_iter()
        .next()
        .map_or(Ok(result.value), Err)
}

fn validated_option(
    call: &FunctionCall<'_>,
    name: &str,
    fallback: &str,
    allowed: &[&str],
) -> Result<String, Diagnostic> {
    let value = call
        .option_value(name)?
        .unwrap_or_else(|| fallback.to_string());
    if allowed.iter().any(|candidate| *candidate == value) {
        return Ok(value);
    }
    Err(diagnostic(
        "bad-option",
        format!(
            "Unsupported :{} option {name}={value}.",
            call.function().name
        ),
    ))
}

fn parse_finite_f64(value: &str, label: &str) -> Result<f64, Diagnostic> {
    let parsed = value.parse::<f64>().map_err(|_| {
        diagnostic(
            "bad-operand",
            format!("{label} must be numeric, got {value}."),
        )
    })?;
    if !parsed.is_finite() {
        return Err(diagnostic(
            "bad-operand",
            format!("{label} must be finite."),
        ));
    }
    Ok(parsed)
}

fn select_relative_time_unit(seconds: f64, policy: &str) -> String {
    let absolute = seconds.abs();
    match policy {
        "compact" => {
            if absolute < 60.0 {
                "second"
            } else if absolute < 3_600.0 {
                "minute"
            } else if absolute < 86_400.0 {
                "hour"
            } else {
                "day"
            }
        }
        "chat" => {
            if absolute < 45.0 {
                "second"
            } else if absolute < 2_700.0 {
                "minute"
            } else if absolute < 79_200.0 {
                "hour"
            } else if absolute < 604_800.0 {
                "day"
            } else {
                "week"
            }
        }
        _ => {
            if absolute < 60.0 {
                "second"
            } else if absolute < 3_600.0 {
                "minute"
            } else if absolute < 86_400.0 {
                "hour"
            } else if absolute < 604_800.0 {
                "day"
            } else if absolute < 2_592_000.0 {
                "week"
            } else if absolute < 31_536_000.0 {
                "month"
            } else {
                "year"
            }
        }
    }
    .to_string()
}

fn relative_time_quantity(seconds: f64, unit: &str) -> i64 {
    let absolute = seconds.abs();
    if absolute == 0.0 {
        return 0;
    }
    ((absolute / relative_time_unit_seconds(unit) as f64) + 0.5)
        .floor()
        .max(1.0) as i64
}

fn relative_time_unit_seconds(unit: &str) -> i64 {
    match unit {
        "second" => 1,
        "minute" => 60,
        "hour" => 3_600,
        "day" => 86_400,
        "week" => 604_800,
        "month" => 2_592_000,
        "quarter" => 7_776_000,
        "year" => 31_536_000,
        _ => 1,
    }
}

fn use_relative_zero(policy: &str, numeric: &str, seconds: f64) -> bool {
    policy == "chat" && numeric == "auto" && seconds.abs() < 45.0
}

fn relative_offset(seconds: f64, quantity: i64) -> Option<String> {
    if quantity == 0 {
        return Some("0".to_string());
    }
    Some(if is_negative_relative_time(seconds) {
        format!("-{quantity}")
    } else {
        quantity.to_string()
    })
}

fn is_negative_relative_time(seconds: f64) -> bool {
    seconds.is_sign_negative()
}

fn relative_term(
    locale: &str,
    style: &str,
    unit: &str,
    offset: &str,
) -> Result<Option<String>, Diagnostic> {
    Ok(relative_unit_data(locale, style, unit)?
        .relative
        .as_ref()
        .and_then(|relative| relative.get(offset))
        .cloned())
}

fn relative_time_pattern(
    locale: &str,
    style: &str,
    unit: &str,
    direction: &str,
    category: &str,
) -> Result<String, Diagnostic> {
    let unit_data = relative_unit_data(locale, style, unit)?;
    let patterns = match direction {
        "past" => &unit_data.past,
        _ => &unit_data.future,
    };
    patterns
        .get(category)
        .or_else(|| patterns.get("other"))
        .cloned()
        .ok_or_else(|| {
            diagnostic(
                "missing-locale-data",
                format!("Missing relative-time pattern for {locale}/{style}/{unit}/{direction}."),
            )
        })
}

fn relative_unit_data(
    locale: &str,
    style: &str,
    unit: &str,
) -> Result<&'static RelativeUnitData, Diagnostic> {
    let data = relative_time_data()?;
    let pattern_set_id = lookup_locale(&data.locale_map, locale, "").ok_or_else(|| {
        diagnostic(
            "missing-locale-data",
            format!("Missing relative-time locale data for {locale}."),
        )
    })?;
    let pattern_set = data.pattern_sets.get(pattern_set_id).ok_or_else(|| {
        diagnostic(
            "missing-locale-data",
            format!("Missing relative-time pattern set {pattern_set_id}."),
        )
    })?;
    pattern_set
        .data
        .get(style)
        .and_then(|style_data| style_data.get(unit))
        .ok_or_else(|| {
            diagnostic(
                "missing-locale-data",
                format!("Missing relative-time unit data for {locale}/{style}/{unit}."),
            )
        })
}

fn relative_time_data() -> Result<&'static RelativeTimeData, Diagnostic> {
    static DATA: OnceLock<Result<RelativeTimeData, String>> = OnceLock::new();
    DATA.get_or_init(load_relative_time_data)
        .as_ref()
        .map_err(|message| diagnostic("missing-locale-data", message.clone()))
}

fn load_relative_time_data() -> Result<RelativeTimeData, String> {
    let path = Path::new("../../cldr/generated/relative-time/all/relative_time.json");
    let contents = fs::read_to_string(path)
        .map_err(|error| format!("Failed to read {}: {error}", path.display()))?;
    let raw: RawRelativeTimeData = serde_json::from_str(&contents)
        .map_err(|error| format!("Failed to parse {}: {error}", path.display()))?;
    let pattern_sets = raw
        .pattern_sets
        .into_iter()
        .map(|pattern_set| (pattern_set.id.clone(), pattern_set))
        .collect();
    Ok(RelativeTimeData {
        locale_map: raw.locale_map,
        pattern_sets,
    })
}

fn diagnostic(code: &str, message: impl Into<String>) -> Diagnostic {
    Diagnostic::new(code, message.into(), 0, 0)
}

#[derive(Debug)]
struct RelativeTimeData {
    locale_map: BTreeMap<String, String>,
    pattern_sets: BTreeMap<String, RelativePatternSet>,
}

#[derive(Debug, Deserialize)]
struct RawRelativeTimeData {
    #[serde(rename = "localeMap")]
    locale_map: BTreeMap<String, String>,
    #[serde(rename = "patternSets")]
    pattern_sets: Vec<RelativePatternSet>,
}

#[derive(Debug, Deserialize)]
struct RelativePatternSet {
    id: String,
    data: BTreeMap<String, BTreeMap<String, RelativeUnitData>>,
}

#[derive(Debug, Deserialize)]
struct RelativeUnitData {
    past: BTreeMap<String, String>,
    future: BTreeMap<String, String>,
    #[serde(default)]
    relative: Option<BTreeMap<String, String>>,
}
