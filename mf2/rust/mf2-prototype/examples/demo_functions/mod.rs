use std::collections::BTreeMap;
use std::fs;
use std::path::Path;
use std::sync::OnceLock;

use mf2_prototype::{
    lookup_locale, select_cardinal_plural_category, Diagnostic, FunctionCall, FunctionRegistry,
    NumberOperands,
};
use serde::Deserialize;

pub fn demo_function_registry() -> FunctionRegistry {
    FunctionRegistry::default()
        .with_function("currency", format_currency)
        .with_function("relativeTime", format_relative_time)
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
            if let Some(relative) = relative_term(call.locale(), &style, &selected_unit, offset)? {
                return Ok(relative);
            }
        }
    }

    let direction = if seconds < 0.0 { "past" } else { "future" };
    let operands = NumberOperands::from_str(&quantity.to_string())
        .ok_or_else(|| diagnostic("bad-operand", "Relative time quantity must be numeric."))?;
    let category = select_cardinal_plural_category(call.locale(), operands);
    let pattern =
        relative_time_pattern(call.locale(), &style, &selected_unit, direction, category)?;
    Ok(pattern.replace("{0}", &quantity.to_string()))
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

fn relative_offset(seconds: f64, quantity: i64) -> Option<&'static str> {
    if seconds == 0.0 {
        return Some("0");
    }
    if quantity != 1 {
        return None;
    }
    Some(if seconds < 0.0 { "-1" } else { "1" })
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
