use crate::cldr_number_data::{
    CldrNumberLocaleData, CLDR_NUMBER_CURRENCY_FRACTIONS, CLDR_NUMBER_LOCALES,
};
use crate::diagnostic::Diagnostic;
use crate::formatter::{ArgumentValue, FormattedPart, FunctionCall, FunctionRegistry};
use crate::locale_key::locale_lookup_chain;

const DEFAULT_LOCALE: &str = "en-US";
const MAX_LOCALE_LENGTH: usize = 256;
const MAX_OPTION_LENGTH: usize = 256;
const MAX_OPERAND_LENGTH: usize = 256;
const MAX_FRACTION_DIGITS: usize = 100;
const MAX_ABSOLUTE_FORMAT_VALUE: f64 = 1e21;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NumberCoreStyle {
    Number,
    Integer,
    Percent,
    Currency,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NumberCoreCurrencyDisplay {
    Symbol,
    NarrowSymbol,
    Code,
}

impl NumberCoreCurrencyDisplay {
    fn from_name(value: &str) -> Result<Self, Diagnostic> {
        match option_name(value, "currencyDisplay")? {
            "symbol" => Ok(Self::Symbol),
            "narrowSymbol" => Ok(Self::NarrowSymbol),
            "code" => Ok(Self::Code),
            _ => Err(bad_option(
                "currencyDisplay must be one of symbol, narrowSymbol, code.",
            )),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NumberCoreSignDisplay {
    Auto,
    Always,
    Never,
}

impl NumberCoreSignDisplay {
    fn from_name(value: &str) -> Result<Self, Diagnostic> {
        match option_name(value, "signDisplay")? {
            "auto" => Ok(Self::Auto),
            "always" => Ok(Self::Always),
            "never" => Ok(Self::Never),
            _ => Err(bad_option(
                "signDisplay must be one of auto, always, never.",
            )),
        }
    }
}

#[derive(Debug, Clone)]
pub struct NumberCoreOptions {
    pub locale: String,
    pub style: NumberCoreStyle,
    pub currency: Option<String>,
    pub currency_display: NumberCoreCurrencyDisplay,
    pub use_grouping: bool,
    pub minimum_fraction_digits: Option<usize>,
    pub maximum_fraction_digits: Option<usize>,
    pub sign_display: NumberCoreSignDisplay,
}

impl Default for NumberCoreOptions {
    fn default() -> Self {
        Self {
            locale: DEFAULT_LOCALE.to_string(),
            style: NumberCoreStyle::Number,
            currency: None,
            currency_display: NumberCoreCurrencyDisplay::Symbol,
            use_grouping: true,
            minimum_fraction_digits: None,
            maximum_fraction_digits: None,
            sign_display: NumberCoreSignDisplay::Auto,
        }
    }
}

pub fn number_core_function_registry() -> FunctionRegistry {
    FunctionRegistry::portable()
        .with_function("number", format_call_number)
        .with_function("integer", format_call_integer)
        .with_function("percent", format_call_percent)
        .with_function("currency", format_call_currency)
}

pub fn format_number_core(
    value: impl Into<ArgumentValue>,
    options: &NumberCoreOptions,
) -> Result<String, Diagnostic> {
    let value = value.into();
    format_number_core_argument(&value, options)
}

pub fn format_number_core_to_parts(
    value: impl Into<ArgumentValue>,
    options: &NumberCoreOptions,
) -> Result<Vec<FormattedPart>, Diagnostic> {
    Ok(vec![FormattedPart::Text {
        value: format_number_core(value, options)?,
    }])
}

fn format_call_number(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    format_call_with_style(call, NumberCoreStyle::Number)
}

fn format_call_integer(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    format_call_with_style(call, NumberCoreStyle::Integer)
}

fn format_call_percent(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    format_call_with_style(call, NumberCoreStyle::Percent)
}

fn format_call_currency(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    format_call_with_style(call, NumberCoreStyle::Currency)
}

fn format_call_with_style(
    call: FunctionCall<'_>,
    style: NumberCoreStyle,
) -> Result<String, Diagnostic> {
    let mut options = NumberCoreOptions {
        locale: call.locale().to_string(),
        style,
        ..NumberCoreOptions::default()
    };
    options.currency = if style == NumberCoreStyle::Currency {
        inherited_option_value(&call, "currency")?
    } else {
        call.option_value("currency")?
    };
    if style == NumberCoreStyle::Currency && options.currency.is_none() {
        return Err(bad_operand("Currency function requires a currency option."));
    }
    if let Some(value) = call.option_value("currencyDisplay")? {
        options.currency_display = NumberCoreCurrencyDisplay::from_name(&value)?;
    }
    options.minimum_fraction_digits = non_negative_option(
        call.option_value("minimumFractionDigits")?,
        "minimumFractionDigits",
    )?;
    options.maximum_fraction_digits = non_negative_option(
        call.option_value("maximumFractionDigits")?,
        "maximumFractionDigits",
    )?;
    if let Some(value) = call.option_value("signDisplay")? {
        options.sign_display = NumberCoreSignDisplay::from_name(&value)?;
    }
    if let Some(value) = call.option_value("useGrouping")? {
        options.use_grouping = boolean_option(&value, "useGrouping")?;
    }
    let parsed = parse_call_finite_decimal(&call, style)
        .ok_or_else(|| bad_operand("Number core requires a finite numeric value."))?;
    format_number_core_parsed(parsed, &options)
}

fn format_number_core_argument(
    value: &ArgumentValue,
    options: &NumberCoreOptions,
) -> Result<String, Diagnostic> {
    let parsed = parse_finite_decimal(value)
        .ok_or_else(|| bad_operand("Number core requires a finite numeric value."))?;
    format_number_core_parsed(parsed, options)
}

fn format_number_core_parsed(
    parsed: f64,
    options: &NumberCoreOptions,
) -> Result<String, Diagnostic> {
    let style = options.style;
    let locale_data = resolve_locale_data(locale_option(&options.locale)?);
    let currency = if style == NumberCoreStyle::Currency {
        Some(parse_currency(options.currency.as_deref())?)
    } else {
        None
    };
    let pattern = pattern_for_style(locale_data, style);
    let fraction = fraction_options(
        style,
        currency.as_deref(),
        options.minimum_fraction_digits,
        options.maximum_fraction_digits,
        pattern,
    )?;
    let normalized = if style == NumberCoreStyle::Integer {
        parsed.trunc()
    } else {
        parsed
    };
    let scaled = if style == NumberCoreStyle::Percent {
        normalized * 100.0
    } else {
        normalized
    };
    if !is_supported_magnitude(scaled) {
        return Err(bad_operand(
            "Number core numeric value is outside the supported magnitude.",
        ));
    }
    let formatted = format_decimal(
        scaled.abs(),
        locale_data,
        pattern,
        fraction,
        options.use_grouping,
    );
    match style {
        NumberCoreStyle::Percent => Ok(apply_signed_pattern(
            pattern,
            &formatted,
            scaled,
            locale_data.symbols,
            options.sign_display,
            Some(symbol(locale_data.symbols, "percentSign", "%")),
            None,
        )),
        NumberCoreStyle::Currency => Ok(apply_signed_pattern(
            pattern,
            &formatted,
            scaled,
            locale_data.symbols,
            options.sign_display,
            None,
            Some(&currency_display(
                locale_data,
                currency.as_deref().unwrap_or_default(),
                options.currency_display,
            )),
        )),
        NumberCoreStyle::Number | NumberCoreStyle::Integer => Ok(apply_sign(
            formatted,
            scaled,
            locale_data.symbols,
            options.sign_display,
        )),
    }
}

fn locale_option(locale: &str) -> Result<&str, Diagnostic> {
    let value = if locale.is_empty() {
        DEFAULT_LOCALE
    } else {
        locale
    };
    if value.chars().count() > MAX_LOCALE_LENGTH {
        return Err(bad_option("locale must not exceed 256 characters."));
    }
    Ok(value)
}

fn resolve_locale_data(locale: &str) -> &'static CldrNumberLocaleData {
    for candidate in locale_lookup_chain(if locale.is_empty() {
        DEFAULT_LOCALE
    } else {
        locale
    }) {
        if let Some((_, data)) = CLDR_NUMBER_LOCALES
            .iter()
            .find(|(key, _)| *key == candidate)
        {
            return data;
        }
        if let Some((_, data)) = CLDR_NUMBER_LOCALES
            .iter()
            .find(|(_, data)| data.numbers_source_locale == candidate)
        {
            return data;
        }
    }
    &CLDR_NUMBER_LOCALES
        .iter()
        .find(|(key, _)| *key == DEFAULT_LOCALE)
        .expect("default number locale is generated")
        .1
}

fn pattern_for_style(locale_data: &CldrNumberLocaleData, style: NumberCoreStyle) -> &'static str {
    match style {
        NumberCoreStyle::Percent => locale_data.percent_pattern,
        NumberCoreStyle::Currency => locale_data.currency_pattern,
        NumberCoreStyle::Number | NumberCoreStyle::Integer => locale_data.decimal_pattern,
    }
}

#[derive(Clone, Copy)]
struct FractionOptions {
    minimum: usize,
    maximum: usize,
}

fn fraction_options(
    style: NumberCoreStyle,
    currency: Option<&str>,
    minimum_fraction_digits: Option<usize>,
    maximum_fraction_digits: Option<usize>,
    pattern: &str,
) -> Result<FractionOptions, Diagnostic> {
    let mut options = fraction_defaults_from_pattern(pattern);
    if style == NumberCoreStyle::Integer {
        options = FractionOptions {
            minimum: 0,
            maximum: 0,
        };
    } else if style == NumberCoreStyle::Currency {
        let defaults = CLDR_NUMBER_CURRENCY_FRACTIONS
            .iter()
            .find(|(key, _)| Some(*key) == currency)
            .or_else(|| {
                CLDR_NUMBER_CURRENCY_FRACTIONS
                    .iter()
                    .find(|(key, _)| *key == "DEFAULT")
            })
            .map(|(_, value)| *value)
            .expect("default currency fraction is generated");
        options = FractionOptions {
            minimum: defaults.digits,
            maximum: defaults.digits,
        };
    }
    let minimum_was_set = minimum_fraction_digits.is_some();
    let maximum_was_set = maximum_fraction_digits.is_some();
    if let Some(minimum) = minimum_fraction_digits {
        options.minimum = minimum;
    }
    if let Some(maximum) = maximum_fraction_digits {
        options.maximum = maximum;
    }
    if minimum_was_set && !maximum_was_set && options.maximum < options.minimum {
        options.maximum = options.minimum;
    }
    if maximum_was_set && !minimum_was_set && options.maximum < options.minimum {
        options.minimum = options.maximum;
    }
    if options.minimum > MAX_FRACTION_DIGITS {
        return Err(bad_option(
            "minimumFractionDigits must be a non-negative integer.",
        ));
    }
    if options.maximum > MAX_FRACTION_DIGITS {
        return Err(bad_option(
            "maximumFractionDigits must be a non-negative integer.",
        ));
    }
    if options.maximum < options.minimum {
        return Err(bad_option(
            "maximumFractionDigits must be greater than or equal to minimumFractionDigits.",
        ));
    }
    Ok(options)
}

fn parse_call_finite_decimal(call: &FunctionCall<'_>, style: NumberCoreStyle) -> Option<f64> {
    if let Some(source) = call.inherited_source() {
        if style == NumberCoreStyle::Number && source.function().name == "integer" {
            if let Some(parsed) = parse_finite_decimal_text(source.value()) {
                return Some(parsed.trunc());
            }
        }
        return parse_finite_decimal_text(source.value());
    }
    parse_finite_decimal(call.raw_value())
}

fn fraction_defaults_from_pattern(pattern: &str) -> FractionOptions {
    let number_pattern = number_pattern(pattern);
    let Some((_, fraction)) = number_pattern.split_once('.') else {
        return FractionOptions {
            minimum: 0,
            maximum: 0,
        };
    };
    FractionOptions {
        minimum: fraction.chars().filter(|ch| *ch == '0').count(),
        maximum: fraction.len(),
    }
}

fn format_decimal(
    value: f64,
    locale_data: &CldrNumberLocaleData,
    pattern: &str,
    fraction: FractionOptions,
    use_grouping: bool,
) -> String {
    let rounded = round_fixed(value, fraction.maximum);
    let (mut integer, mut decimal) = rounded
        .split_once('.')
        .map(|(integer, decimal)| (integer.to_string(), decimal.to_string()))
        .unwrap_or_else(|| (rounded, String::new()));
    while decimal.len() > fraction.minimum && decimal.ends_with('0') {
        decimal.pop();
    }
    while decimal.len() < fraction.minimum {
        decimal.push('0');
    }
    let grouping = grouping_info(pattern);
    if use_grouping
        && grouping.primary > 0
        && integer.len() >= grouping.primary + locale_data.minimum_grouping_digits
    {
        integer = group_integer(
            &integer,
            grouping,
            symbol(locale_data.symbols, "group", ","),
        );
    }
    let integer = localize_digits(&integer, locale_data.numbering_system_digits);
    if decimal.is_empty() {
        integer
    } else {
        format!(
            "{}{}{}",
            integer,
            symbol(locale_data.symbols, "decimal", "."),
            localize_digits(&decimal, locale_data.numbering_system_digits)
        )
    }
}

fn round_fixed(value: f64, maximum_fraction_digits: usize) -> String {
    let (integer, fraction) = decimal_parts(&value.to_string());
    let dropped = fraction.len() as isize - maximum_fraction_digits as isize;
    let mut round_digit = b'0';
    let mut units = if dropped <= 0 {
        format!("{}{}{}", integer, fraction, "0".repeat((-dropped) as usize))
    } else {
        round_digit = fraction.as_bytes()[maximum_fraction_digits];
        format!("{}{}", integer, &fraction[..maximum_fraction_digits])
    };
    units = trim_leading_zero_digits(&units);
    if round_digit >= b'5' {
        units = increment_digits(&units);
    }
    if maximum_fraction_digits == 0 {
        return units;
    }
    if units.len() <= maximum_fraction_digits {
        units = format!(
            "{}{}",
            "0".repeat(maximum_fraction_digits - units.len() + 1),
            units
        );
    }
    let split = units.len() - maximum_fraction_digits;
    format!("{}.{}", &units[..split], &units[split..])
}

fn decimal_parts(text: &str) -> (String, String) {
    let (mantissa, exponent) = text
        .split_once(['e', 'E'])
        .map(|(mantissa, exponent)| (mantissa, exponent.parse::<isize>().unwrap_or(0)))
        .unwrap_or((text, 0));
    let mantissa = mantissa.trim_start_matches(['+', '-']);
    let (mut integer, fraction) = mantissa
        .split_once('.')
        .map(|(integer, fraction)| (integer, fraction))
        .unwrap_or((mantissa, ""));
    if integer.is_empty() {
        integer = "0";
    }
    let digits = format!("{}{}", integer, fraction);
    let point = integer.len() as isize + exponent;
    if point <= 0 {
        return (
            "0".to_string(),
            trim_trailing_zero_digits(&format!("{}{}", "0".repeat((-point) as usize), digits)),
        );
    }
    if point as usize >= digits.len() {
        return (
            trim_leading_zero_digits(&format!(
                "{}{}",
                digits,
                "0".repeat(point as usize - digits.len())
            )),
            String::new(),
        );
    }
    (
        trim_leading_zero_digits(&digits[..point as usize]),
        trim_trailing_zero_digits(&digits[point as usize..]),
    )
}

fn trim_leading_zero_digits(value: &str) -> String {
    let trimmed = value.trim_start_matches('0');
    if trimmed.is_empty() {
        "0".to_string()
    } else {
        trimmed.to_string()
    }
}

fn trim_trailing_zero_digits(value: &str) -> String {
    value.trim_end_matches('0').to_string()
}

fn increment_digits(value: &str) -> String {
    let mut digits = value.as_bytes().to_vec();
    for index in (0..digits.len()).rev() {
        if digits[index] != b'9' {
            digits[index] += 1;
            return String::from_utf8(digits).expect("ASCII digits");
        }
        digits[index] = b'0';
    }
    format!(
        "1{}",
        String::from_utf8(digits).expect("ASCII digits after carry")
    )
}

#[derive(Clone, Copy)]
struct GroupingInfo {
    primary: usize,
    secondary: usize,
}

fn grouping_info(pattern: &str) -> GroupingInfo {
    let integer_pattern = number_pattern(pattern)
        .split_once('.')
        .map(|(integer, _)| integer)
        .unwrap_or_else(|| number_pattern(pattern));
    let groups: Vec<_> = integer_pattern.split(',').collect();
    if groups.len() == 1 {
        return GroupingInfo {
            primary: 0,
            secondary: 0,
        };
    }
    let primary = placeholder_count(groups[groups.len() - 1]);
    let secondary = if groups.len() > 2 {
        placeholder_count(groups[groups.len() - 2])
    } else {
        primary
    };
    GroupingInfo { primary, secondary }
}

fn group_integer(integer: &str, grouping: GroupingInfo, separator: &str) -> String {
    let mut groups = Vec::new();
    let mut end = integer.len();
    let mut size = grouping.primary;
    while end > 0 {
        let start = end.saturating_sub(size);
        groups.insert(0, &integer[start..end]);
        end = start;
        size = if grouping.secondary == 0 {
            grouping.primary
        } else {
            grouping.secondary
        };
    }
    groups.join(separator)
}

fn apply_sign(
    formatted: String,
    value: f64,
    symbols: &[(&str, &str)],
    sign_display: NumberCoreSignDisplay,
) -> String {
    if sign_display == NumberCoreSignDisplay::Never {
        return formatted;
    }
    if value.is_sign_negative() {
        return format!("{}{}", symbol(symbols, "minusSign", "-"), formatted);
    }
    if sign_display == NumberCoreSignDisplay::Always {
        return format!("{}{}", symbol(symbols, "plusSign", "+"), formatted);
    }
    formatted
}

fn apply_pattern(
    pattern: &str,
    formatted: &str,
    percent_sign: Option<&str>,
    currency: Option<&str>,
) -> String {
    let mut output = if let Some((start, end)) = number_pattern_range(pattern) {
        format!("{}{}{}", &pattern[..start], formatted, &pattern[end..])
    } else {
        pattern.to_string()
    };
    if let Some(percent_sign) = percent_sign {
        output = output.replace('%', percent_sign);
    }
    if let Some(currency) = currency {
        output = output.replace('¤', currency);
    }
    output
}

fn apply_signed_pattern(
    pattern: &str,
    formatted: &str,
    value: f64,
    symbols: &[(&str, &str)],
    sign_display: NumberCoreSignDisplay,
    percent_sign: Option<&str>,
    currency: Option<&str>,
) -> String {
    let (positive_pattern, negative_pattern) = pattern
        .split_once(';')
        .map_or((pattern, None), |(positive, negative)| {
            (positive, Some(negative))
        });
    if value.is_sign_negative() && sign_display != NumberCoreSignDisplay::Never {
        if let Some(negative_pattern) = negative_pattern {
            return apply_pattern(negative_pattern, formatted, percent_sign, currency);
        }
        return format!(
            "{}{}",
            symbol(symbols, "minusSign", "-"),
            apply_pattern(positive_pattern, formatted, percent_sign, currency)
        );
    }
    let output = apply_pattern(positive_pattern, formatted, percent_sign, currency);
    if sign_display == NumberCoreSignDisplay::Always {
        return format!("{}{}", symbol(symbols, "plusSign", "+"), output);
    }
    output
}

fn currency_display(
    locale_data: &CldrNumberLocaleData,
    currency: &str,
    display: NumberCoreCurrencyDisplay,
) -> String {
    if display == NumberCoreCurrencyDisplay::Code {
        return currency_code_display(locale_data, currency);
    }
    let Some((_, data)) = locale_data
        .currencies
        .iter()
        .find(|(key, _)| *key == currency)
    else {
        return currency.to_string();
    };
    if display == NumberCoreCurrencyDisplay::NarrowSymbol {
        if let Some(narrow) = data.narrow_symbol {
            return narrow.to_string();
        }
    }
    if !data.symbol.is_empty() {
        return data.symbol.to_string();
    }
    currency.to_string()
}

fn currency_code_display(locale_data: &CldrNumberLocaleData, currency: &str) -> String {
    let positive_pattern = locale_data
        .currency_pattern
        .split(';')
        .next()
        .unwrap_or_default();
    let before = if positive_pattern.contains("#\u{a4}") || positive_pattern.contains("0\u{a4}") {
        locale_data.currency_spacing.before_currency
    } else {
        ""
    };
    let after = if positive_pattern.contains("\u{a4}#") || positive_pattern.contains("\u{a4}0") {
        locale_data.currency_spacing.after_currency
    } else {
        ""
    };
    format!("{}{}{}", before, currency, after)
}

fn parse_finite_decimal(value: &ArgumentValue) -> Option<f64> {
    match value {
        ArgumentValue::Number(value) | ArgumentValue::String(value) => {
            parse_finite_decimal_text(value)
        }
        ArgumentValue::Bool(_) | ArgumentValue::Null => None,
    }
}

fn parse_finite_decimal_text(value: &str) -> Option<f64> {
    let text = value.trim();
    if text.chars().count() > MAX_OPERAND_LENGTH {
        return None;
    }
    if !is_decimal_literal(text) {
        return None;
    }
    let parsed = text.parse::<f64>().ok()?;
    parsed.is_finite().then_some(parsed)
}

fn is_supported_magnitude(value: f64) -> bool {
    value.is_finite() && value.abs() < MAX_ABSOLUTE_FORMAT_VALUE
}

fn is_decimal_literal(value: &str) -> bool {
    let bytes = value.as_bytes();
    let mut index = 0usize;
    if matches!(bytes.get(index), Some(b'-')) {
        index += 1;
    }

    match bytes.get(index) {
        Some(b'0') => {
            index += 1;
            if matches!(bytes.get(index), Some(b'0'..=b'9')) {
                return false;
            }
        }
        Some(b'1'..=b'9') => {
            index += 1;
            while matches!(bytes.get(index), Some(b'0'..=b'9')) {
                index += 1;
            }
        }
        _ => return false,
    }

    if bytes.get(index) == Some(&b'.') {
        index += 1;
        let fraction_start = index;
        while matches!(bytes.get(index), Some(b'0'..=b'9')) {
            index += 1;
        }
        if index == fraction_start {
            return false;
        }
    }
    if matches!(bytes.get(index), Some(b'e' | b'E')) {
        index += 1;
        if matches!(bytes.get(index), Some(b'+' | b'-')) {
            index += 1;
        }
        let exponent_start = index;
        while matches!(bytes.get(index), Some(b'0'..=b'9')) {
            index += 1;
        }
        if index == exponent_start {
            return false;
        }
    }
    index == bytes.len()
}

fn parse_currency(value: Option<&str>) -> Result<String, Diagnostic> {
    let Some(value) = value else {
        return Err(bad_option("currency must be a three-letter ISO 4217 code."));
    };
    let value = option_name(value, "currency")?;
    if value.len() != 3 || !value.bytes().all(|byte| byte.is_ascii_alphabetic()) {
        return Err(bad_option("currency must be a three-letter ISO 4217 code."));
    }
    Ok(value.to_ascii_uppercase())
}

fn inherited_option_value(
    call: &FunctionCall<'_>,
    name: &str,
) -> Result<Option<String>, Diagnostic> {
    if let Some(value) = call.option_value(name)? {
        return Ok(Some(value));
    }
    let mut source = call.inherited_source();
    while let Some(current) = source {
        if let Some(value) = current.option_value(name)? {
            return Ok(Some(value));
        }
        source = current.inherited_source();
    }
    Ok(None)
}

fn non_negative_option(value: Option<String>, name: &str) -> Result<Option<usize>, Diagnostic> {
    let Some(value) = value else {
        return Ok(None);
    };
    if value.is_empty() || !value.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err(bad_option(format!(
            "{name} must be a non-negative integer."
        )));
    }
    value
        .parse::<usize>()
        .map(Some)
        .map_err(|_| bad_option(format!("{name} is outside the supported integer range.")))
}

fn boolean_option(value: &str, name: &str) -> Result<bool, Diagnostic> {
    match option_name(value, name)? {
        "true" => Ok(true),
        "false" => Ok(false),
        _ => Err(bad_option(format!("{name} must be true or false."))),
    }
}

fn option_name<'a>(value: &'a str, name: &str) -> Result<&'a str, Diagnostic> {
    if value.chars().count() > MAX_OPTION_LENGTH {
        return Err(bad_option(format!(
            "{name} must not exceed 256 characters."
        )));
    }
    Ok(value)
}

fn number_pattern(pattern: &str) -> &str {
    number_pattern_range(pattern)
        .map(|(start, end)| &pattern[start..end])
        .unwrap_or("")
}

fn number_pattern_range(pattern: &str) -> Option<(usize, usize)> {
    let mut start = None;
    let mut end = 0usize;
    for (index, ch) in pattern.char_indices() {
        if matches!(ch, '#' | '0' | ',' | '.') {
            start.get_or_insert(index);
            end = index + ch.len_utf8();
        } else if start.is_some() {
            break;
        }
    }
    start.map(|start| (start, end))
}

fn placeholder_count(pattern: &str) -> usize {
    pattern
        .chars()
        .filter(|ch| *ch == '#' || *ch == '0')
        .count()
}

fn symbol<'a>(symbols: &'a [(&str, &str)], name: &str, fallback: &'a str) -> &'a str {
    symbols
        .iter()
        .find(|(key, _)| *key == name)
        .map(|(_, value)| *value)
        .unwrap_or(fallback)
}

fn localize_digits(value: &str, digits: Option<&str>) -> String {
    let Some(digits) = digits else {
        return value.to_string();
    };
    if digits == "0123456789" {
        return value.to_string();
    }
    let digit_chars: Vec<_> = digits.chars().collect();
    if digit_chars.len() < 10 {
        return value.to_string();
    }
    value
        .chars()
        .map(|ch| {
            if ch.is_ascii_digit() {
                digit_chars[(ch as u8 - b'0') as usize]
            } else {
                ch
            }
        })
        .collect()
}

fn bad_operand(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-operand", message, 0, 0)
}

fn bad_option(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-option", message, 0, 0)
}
