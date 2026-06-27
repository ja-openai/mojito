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
const MAX_DECIMAL_OUTPUT_CHARS: i64 = 512;

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
    parsed: DecimalOperand,
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
        parsed.truncate_to_integer()
    } else {
        parsed
    };
    let scaled = if style == NumberCoreStyle::Percent {
        normalized.shift(2)
    } else {
        normalized
    };
    if !is_supported_magnitude(&scaled) {
        return Err(bad_operand(
            "Number core numeric value is outside the supported magnitude.",
        ));
    }
    let formatted = format_decimal(
        &scaled.abs(),
        locale_data,
        pattern,
        fraction,
        options.use_grouping,
    );
    match style {
        NumberCoreStyle::Percent => Ok(apply_signed_pattern(
            pattern,
            &formatted,
            &scaled,
            locale_data.symbols,
            options.sign_display,
            Some(symbol(locale_data.symbols, "percentSign", "%")),
            None,
        )),
        NumberCoreStyle::Currency => Ok(apply_signed_pattern(
            pattern,
            &formatted,
            &scaled,
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
            &scaled,
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

fn parse_call_finite_decimal(
    call: &FunctionCall<'_>,
    style: NumberCoreStyle,
) -> Option<DecimalOperand> {
    if let Some(source) = call.inherited_source() {
        if style == NumberCoreStyle::Number && source.function().name == "integer" {
            if let Some(parsed) = parse_finite_decimal_text(source.value()) {
                return Some(parsed.truncate_to_integer());
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
    value: &DecimalOperand,
    locale_data: &CldrNumberLocaleData,
    pattern: &str,
    fraction: FractionOptions,
    use_grouping: bool,
) -> String {
    let rounded = value.round_to_maximum_fraction_digits(fraction.maximum);
    let rounded = decimal_operand_to_string(&rounded);
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

fn trim_leading_zero_digits(value: &str) -> String {
    let trimmed = value.trim_start_matches('0');
    if trimmed.is_empty() {
        "0".to_string()
    } else {
        trimmed.to_string()
    }
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
    value: &DecimalOperand,
    symbols: &[(&str, &str)],
    sign_display: NumberCoreSignDisplay,
) -> String {
    if sign_display == NumberCoreSignDisplay::Never {
        return formatted;
    }
    if value.negative {
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
    value: &DecimalOperand,
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
    if value.negative && sign_display != NumberCoreSignDisplay::Never {
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

#[derive(Clone, Debug, PartialEq, Eq)]
struct DecimalOperand {
    negative: bool,
    digits: String,
    scale: i64,
}

impl DecimalOperand {
    fn shift(&self, places: i64) -> Self {
        normalize_decimal_operand_preserving_zero_sign(
            self.negative,
            self.digits.clone(),
            self.scale - places,
        )
    }

    fn truncate_to_integer(&self) -> Self {
        if self.scale <= 0 {
            return self.clone();
        }
        let keep = self.digits.len() as i64 - self.scale;
        if keep <= 0 {
            return DecimalOperand {
                negative: self.negative,
                digits: "0".to_string(),
                scale: 0,
            };
        }
        normalize_decimal_operand_preserving_zero_sign(
            self.negative,
            self.digits[..keep as usize].to_string(),
            0,
        )
    }

    fn round_to_maximum_fraction_digits(&self, maximum_fraction_digits: usize) -> Self {
        let maximum_fraction_digits = maximum_fraction_digits as i64;
        if self.scale <= maximum_fraction_digits {
            return self.clone();
        }
        let drop = self.scale - maximum_fraction_digits;
        let keep = self.digits.len() as i64 - drop;
        let (kept, remainder) = if keep > 0 {
            (
                self.digits[..keep as usize].to_string(),
                self.digits[keep as usize..].to_string(),
            )
        } else {
            ("0".to_string(), self.digits.clone())
        };
        let mut rounded = trim_leading_zero_digits(&kept);
        if compare_decimal_remainder_to_half(&remainder, drop) >= 0 {
            rounded = increment_decimal_string(&rounded);
        }
        normalize_decimal_operand_preserving_zero_sign(
            self.negative,
            rounded,
            maximum_fraction_digits,
        )
    }

    fn abs(&self) -> Self {
        Self {
            negative: false,
            digits: self.digits.clone(),
            scale: self.scale,
        }
    }
}

fn parse_finite_decimal(value: &ArgumentValue) -> Option<DecimalOperand> {
    match value {
        ArgumentValue::Number(value) | ArgumentValue::String(value) => {
            parse_finite_decimal_text(value)
        }
        ArgumentValue::Bool(_) | ArgumentValue::Null => None,
    }
}

fn parse_finite_decimal_text(value: &str) -> Option<DecimalOperand> {
    let text = value.trim();
    if text.chars().count() > MAX_OPERAND_LENGTH {
        return None;
    }
    if !is_decimal_literal(text) {
        return None;
    }
    parse_decimal_operand(text).ok()
}

fn parse_decimal_operand(value: &str) -> Result<DecimalOperand, ()> {
    let negative = value.starts_with('-');
    let body = if negative { &value[1..] } else { value };
    let (significand, exponent) = split_decimal_exponent(body);
    let exponent = parse_bounded_decimal_exponent(exponent).ok_or(())?;
    let (integer, fraction) = significand.split_once('.').unwrap_or((significand, ""));
    Ok(normalize_decimal_operand_preserving_zero_sign(
        negative,
        format!("{integer}{fraction}"),
        fraction.len() as i64 - exponent,
    ))
}

fn split_decimal_exponent(value: &str) -> (&str, &str) {
    match value.find(['e', 'E']) {
        Some(index) => (&value[..index], &value[index + 1..]),
        None => (value, ""),
    }
}

fn parse_bounded_decimal_exponent(value: &str) -> Option<i64> {
    if value.is_empty() {
        return Some(0);
    }
    let negative = value.starts_with('-');
    let unsigned = value.strip_prefix(['+', '-']).unwrap_or(value);
    let digits = unsigned.trim_start_matches('0');
    if digits.is_empty() {
        return Some(0);
    }
    if digits.len() > 7 {
        return None;
    }
    let parsed = digits.parse::<i64>().ok()?;
    if parsed > 1_000_000 {
        return None;
    }
    Some(if negative { -parsed } else { parsed })
}

fn normalize_decimal_operand_preserving_zero_sign(
    negative: bool,
    digits: String,
    mut scale: i64,
) -> DecimalOperand {
    let mut digits = digits.trim_start_matches('0').to_string();
    if digits.is_empty() {
        return DecimalOperand {
            negative,
            digits: "0".to_string(),
            scale: 0,
        };
    }
    while digits.ends_with('0') {
        digits.pop();
        scale -= 1;
    }
    DecimalOperand {
        negative,
        digits,
        scale,
    }
}

fn decimal_operand_to_string(value: &DecimalOperand) -> String {
    if value.scale <= 0 {
        return format!("{}{}", value.digits, "0".repeat((-value.scale) as usize));
    }
    if value.scale as usize >= value.digits.len() {
        return format!(
            "0.{}{}",
            "0".repeat(value.scale as usize - value.digits.len()),
            value.digits
        );
    }
    let integer_digits = value.digits.len() - value.scale as usize;
    format!(
        "{}.{}",
        &value.digits[..integer_digits],
        &value.digits[integer_digits..]
    )
}

fn compare_decimal_remainder_to_half(remainder: &str, dropped_digits: i64) -> i32 {
    if !remainder.bytes().any(|byte| byte != b'0') {
        return -1;
    }
    if (remainder.len() as i64) < dropped_digits {
        return -1;
    }
    match remainder.as_bytes()[0] {
        b'0'..=b'4' => -1,
        b'6'..=b'9' => 1,
        _ if remainder.as_bytes()[1..].iter().any(|byte| *byte != b'0') => 1,
        _ => 0,
    }
}

fn increment_decimal_string(value: &str) -> String {
    let mut digits = value.as_bytes().to_vec();
    for index in (0..digits.len()).rev() {
        if digits[index] != b'9' {
            digits[index] += 1;
            return String::from_utf8(digits).expect("decimal digits are utf-8");
        }
        digits[index] = b'0';
    }
    format!(
        "1{}",
        String::from_utf8(digits).expect("decimal digits are utf-8")
    )
}

fn estimated_decimal_output_chars(operand: &DecimalOperand) -> i64 {
    if operand.scale <= 0 {
        return operand.digits.len() as i64 - operand.scale;
    }
    let integer_digits = (operand.digits.len() as i64 - operand.scale).max(1);
    let fraction_digits = operand.scale;
    integer_digits + 1 + fraction_digits
}

fn is_supported_magnitude(value: &DecimalOperand) -> bool {
    estimated_decimal_output_chars(&value.abs()) <= MAX_DECIMAL_OUTPUT_CHARS
        && (value.digits.len() as i64 - value.scale) <= 21
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
    if value.chars().count() > MAX_OPTION_LENGTH {
        return Err(bad_option(format!(
            "{name} must not exceed 256 characters."
        )));
    }
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
