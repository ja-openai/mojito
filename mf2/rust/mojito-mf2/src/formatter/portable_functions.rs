use crate::diagnostic::Diagnostic;
use crate::model::{ExpressionArg, FunctionRef};

use super::{FunctionCall, FunctionMatch, FunctionRegistry, FunctionSourceRef};

const MAX_FRACTION_DIGITS: usize = 100;
const MAX_DECIMAL_OPERAND_LENGTH: usize = 256;
const MAX_DECIMAL_OUTPUT_CHARS: i64 = 1000;
const MAX_OFFSET_INTEGER: i128 = 1_000_000_000_000_000_000_000;

pub(super) fn register(registry: &mut FunctionRegistry) {
    registry.register_formatter("string", passthrough_function);
    registry.register_formatter("number", format_unlocalized_number);
    registry.register_selector("number", select_number);
    registry.register_formatter("percent", format_unlocalized_percent);
    registry.register_selector("percent", select_percent);
    registry.register_formatter("integer", format_unlocalized_integer);
    registry.register_selector("integer", select_integer);
    registry.register_formatter("offset", format_offset);
    registry.register_selector("offset", select_offset);
}

fn passthrough_function(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    Ok(call.value().to_string())
}

fn format_unlocalized_number(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let message = "Number function requires a numeric operand.";
    let value = parse_call_decimal_operand(&call).map_err(|_| bad_operand(message))?;
    let minimum_fraction_digits = minimum_fraction_digits(&call)?;
    let maximum_fraction_digits = maximum_fraction_digits(&call)?;
    validate_fraction_digits(minimum_fraction_digits, maximum_fraction_digits)?;
    let rounded = value.round_to_maximum_fraction_digits(maximum_fraction_digits);
    ensure_decimal_output_bounded(&rounded, minimum_fraction_digits, message)?;
    Ok(format_decimal_operand(
        &rounded,
        sign_display_always(call.function())?,
        minimum_fraction_digits,
    ))
}

fn select_number(call: FunctionMatch<'_>) -> Result<Option<i32>, Diagnostic> {
    if invalid_numeric_selector(call.function(), call.inherited_source())? {
        return Err(bad_selector("Number selector cannot match this operand."));
    }
    let value = parse_match_decimal_operand(&call)
        .map_err(|_| bad_selector("Number selector requires a numeric operand."))?;
    let Ok(key) = parse_decimal_operand(call.key()) else {
        return Ok(None);
    };
    Ok((value == key).then_some(2))
}

fn format_unlocalized_percent(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let message = "Percent function requires a numeric operand.";
    let value = parse_call_decimal_operand(&call).map_err(|_| bad_operand(message))?;
    let minimum_fraction_digits = minimum_fraction_digits(&call)?;
    let maximum_fraction_digits = maximum_fraction_digits(&call)?;
    validate_fraction_digits(minimum_fraction_digits, maximum_fraction_digits)?;
    let percent_value = value
        .shift(2)
        .round_to_maximum_fraction_digits(maximum_fraction_digits);
    ensure_decimal_output_bounded(&percent_value, minimum_fraction_digits, message)?;
    let mut formatted = format_decimal_operand(&percent_value, false, 0);
    if sign_display_always(call.function())? && !value.negative {
        formatted.insert(0, '+');
    }
    append_minimum_fraction_digits(&mut formatted, minimum_fraction_digits);
    formatted.push('%');
    Ok(formatted)
}

fn select_percent(call: FunctionMatch<'_>) -> Result<Option<i32>, Diagnostic> {
    if invalid_numeric_selector(call.function(), call.inherited_source())? {
        return Err(bad_selector("Percent selector cannot match this operand."));
    }
    let value = parse_match_decimal_operand(&call)
        .map_err(|_| bad_selector("Percent selector requires a numeric operand."))?
        .shift(2);
    let Ok(key) = parse_decimal_operand(call.key()) else {
        return Ok(None);
    };
    Ok((value == key).then_some(2))
}

fn format_unlocalized_integer(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let message = "Integer function requires a numeric operand.";
    let integer = parse_call_decimal_operand(&call)
        .map_err(|_| bad_operand(message))?
        .truncate_to_integer();
    ensure_decimal_output_bounded(&integer, 0, message)?;
    Ok(format_decimal_operand(
        &integer,
        sign_display_always(call.function())?,
        0,
    ))
}

fn select_integer(call: FunctionMatch<'_>) -> Result<Option<i32>, Diagnostic> {
    if invalid_numeric_selector(call.function(), call.inherited_source())? {
        return Err(bad_selector("Integer selector cannot match this operand."));
    }
    let value = parse_match_decimal_operand(&call)
        .map_err(|_| bad_selector("Integer selector requires a numeric operand."))?;
    let Ok(key) = parse_integer_operand(call.key()) else {
        return Ok(None);
    };
    Ok((value.truncate_to_integer() == key).then_some(2))
}

fn format_offset(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let value = parse_offset_number(call.value())
        .map_err(|_| bad_operand("Offset function requires a numeric operand."))?;
    let offset = offset_delta(&call)?;
    let result = value
        .checked_add(offset)
        .ok_or_else(|| bad_operand("Offset result is outside the supported integer range."))?;
    let absolute = result
        .checked_abs()
        .ok_or_else(|| bad_operand("Offset result is outside the supported integer range."))?;
    if absolute >= MAX_OFFSET_INTEGER {
        return Err(bad_operand(
            "Offset result is outside the supported integer range.",
        ));
    }
    Ok(format_offset_integer(
        result,
        inherited_sign_display_always(call.inherited_source())?,
    ))
}

fn select_offset(call: FunctionMatch<'_>) -> Result<Option<i32>, Diagnostic> {
    let value = parse_offset_number(call.value())
        .map_err(|_| bad_selector("Offset selector requires a numeric operand."))?;
    let Ok(key) = parse_offset_number(call.key()) else {
        return Ok(None);
    };
    Ok((value == key).then_some(2))
}

fn offset_delta(call: &FunctionCall<'_>) -> Result<i128, Diagnostic> {
    let add = call.option_value("add")?;
    let subtract = call.option_value("subtract")?;
    match (add, subtract) {
        (Some(_), Some(_)) | (None, None) => Err(bad_option(
            "Offset function requires exactly one of add or subtract.",
        )),
        (Some(value), None) => parse_offset_number(&value)
            .map_err(|_| bad_option("Offset add option must be an integer.")),
        (None, Some(value)) => parse_offset_number(&value)
            .map(|value| -value)
            .map_err(|_| bad_option("Offset subtract option must be an integer.")),
    }
}

fn parse_offset_number(value: &str) -> Result<i128, ()> {
    if !is_integer_literal(value) {
        return Err(());
    }
    let parsed = value.parse::<i128>().map_err(|_| ())?;
    let absolute = parsed.checked_abs().ok_or(())?;
    if absolute >= MAX_OFFSET_INTEGER {
        return Err(());
    }
    Ok(parsed)
}

fn is_integer_literal(value: &str) -> bool {
    let bytes = value.as_bytes();
    let mut index = if matches!(bytes.first(), Some(b'+' | b'-')) {
        1
    } else {
        0
    };
    let digit_start = index;
    while matches!(bytes.get(index), Some(b'0'..=b'9')) {
        index += 1;
    }
    index > digit_start && index == bytes.len()
}

fn parse_call_decimal_operand(call: &FunctionCall<'_>) -> Result<DecimalOperand, ()> {
    parse_decimal_operand(call.value())
        .or_else(|_| parse_source_decimal_operand(call.inherited_source()))
}

fn parse_match_decimal_operand(call: &FunctionMatch<'_>) -> Result<DecimalOperand, ()> {
    parse_source_decimal_operand(call.inherited_source())
        .or_else(|_| parse_decimal_operand(call.value()))
}

fn parse_source_decimal_operand(
    source: Option<FunctionSourceRef<'_>>,
) -> Result<DecimalOperand, ()> {
    let Some(source) = source else {
        return Err(());
    };
    if is_decimal_source_function(source.function()) {
        parse_decimal_operand(source.value())
    } else {
        parse_source_decimal_operand(source.inherited_source())
    }
}

pub(super) fn parse_decimal_number(value: &str) -> Result<f64, ()> {
    if value.len() > MAX_DECIMAL_OPERAND_LENGTH {
        return Err(());
    }
    if !is_well_formed_decimal_literal(value) {
        return Err(());
    }
    let parsed = value.parse::<f64>().map_err(|_| ())?;
    if parsed.is_finite() {
        Ok(parsed)
    } else {
        Err(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct DecimalOperand {
    negative: bool,
    digits: String,
    scale: i64,
}

impl DecimalOperand {
    fn shift(&self, places: i64) -> Self {
        normalize_decimal_operand(self.negative, self.digits.clone(), self.scale - places)
    }

    fn truncate_to_integer(&self) -> Self {
        if self.scale <= 0 {
            return self.clone();
        }
        let keep = self.digits.len() as i64 - self.scale;
        if keep <= 0 {
            return normalize_decimal_operand(false, "0".to_string(), 0);
        }
        normalize_decimal_operand(self.negative, self.digits[..keep as usize].to_string(), 0)
    }

    fn round_to_maximum_fraction_digits(&self, maximum_fraction_digits: Option<usize>) -> Self {
        let Some(maximum_fraction_digits) = maximum_fraction_digits else {
            return self.clone();
        };
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
        let mut rounded = kept.trim_start_matches('0').to_string();
        if rounded.is_empty() {
            rounded.push('0');
        }
        let comparison = compare_decimal_remainder_to_half(&remainder, drop);
        if comparison >= 0 {
            rounded = increment_decimal_string(&rounded);
        }
        normalize_decimal_operand(self.negative, rounded, maximum_fraction_digits)
    }
}

fn parse_decimal_operand(value: &str) -> Result<DecimalOperand, ()> {
    if value.len() > MAX_DECIMAL_OPERAND_LENGTH {
        return Err(());
    }
    if !is_well_formed_decimal_literal(value) {
        return Err(());
    }
    let negative = value.starts_with('-');
    let body = if negative { &value[1..] } else { value };
    let (significand, exponent) = split_decimal_exponent(body);
    let exponent = parse_bounded_decimal_exponent(exponent).ok_or(())?;
    let (integer, fraction) = significand.split_once('.').unwrap_or((significand, ""));
    Ok(normalize_decimal_operand(
        negative,
        format!("{integer}{fraction}"),
        fraction.len() as i64 - exponent,
    ))
}

pub(super) fn is_decimal_operand(value: &str) -> bool {
    parse_decimal_operand(value).is_ok()
}

fn parse_integer_operand(value: &str) -> Result<DecimalOperand, ()> {
    if value.len() > MAX_DECIMAL_OPERAND_LENGTH {
        return Err(());
    }
    let Some(rest) = value.strip_prefix('+').or_else(|| value.strip_prefix('-')) else {
        return if value.chars().all(|ch| ch.is_ascii_digit()) && !value.is_empty() {
            Ok(normalize_decimal_operand(false, value.to_string(), 0))
        } else {
            Err(())
        };
    };
    if rest.is_empty() || !rest.chars().all(|ch| ch.is_ascii_digit()) {
        return Err(());
    }
    Ok(normalize_decimal_operand(
        value.starts_with('-'),
        rest.to_string(),
        0,
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

fn normalize_decimal_operand(negative: bool, digits: String, mut scale: i64) -> DecimalOperand {
    let mut digits = digits.trim_start_matches('0').to_string();
    if digits.is_empty() {
        return DecimalOperand {
            negative: false,
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

fn is_well_formed_decimal_literal(value: &str) -> bool {
    let bytes = value.as_bytes();
    let mut index = 0usize;
    if bytes.get(index) == Some(&b'-') {
        index += 1;
    }

    match bytes.get(index) {
        Some(b'0') => index += 1,
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

fn format_offset_integer(value: i128, sign_display_always: bool) -> String {
    if sign_display_always && value >= 0 {
        format!("+{value}")
    } else {
        value.to_string()
    }
}

fn format_decimal_operand(
    value: &DecimalOperand,
    sign_display_always: bool,
    minimum_fraction_digits: usize,
) -> String {
    let mut formatted = decimal_operand_to_string(value);
    if sign_display_always && !value.negative {
        formatted.insert(0, '+');
    }
    append_minimum_fraction_digits(&mut formatted, minimum_fraction_digits);
    formatted
}

fn decimal_operand_to_string(value: &DecimalOperand) -> String {
    let sign = if value.negative { "-" } else { "" };
    if value.scale <= 0 {
        return format!(
            "{sign}{}{}",
            value.digits,
            "0".repeat((-value.scale) as usize)
        );
    }
    if value.scale as usize >= value.digits.len() {
        return format!(
            "{sign}0.{}{}",
            "0".repeat(value.scale as usize - value.digits.len()),
            value.digits
        );
    }
    let integer_digits = value.digits.len() - value.scale as usize;
    format!(
        "{sign}{}.{}",
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

fn ensure_decimal_output_bounded(
    operand: &DecimalOperand,
    minimum_fraction_digits: usize,
    message: &str,
) -> Result<(), Diagnostic> {
    if estimated_decimal_output_chars(operand, minimum_fraction_digits) > MAX_DECIMAL_OUTPUT_CHARS {
        return Err(bad_operand(message));
    }
    Ok(())
}

fn estimated_decimal_output_chars(operand: &DecimalOperand, minimum_fraction_digits: usize) -> i64 {
    let sign = i64::from(operand.negative);
    if operand.scale <= 0 {
        return sign + operand.digits.len() as i64 - operand.scale;
    }
    let integer_digits = (operand.digits.len() as i64 - operand.scale).max(1);
    let fraction_digits = operand.scale.max(minimum_fraction_digits as i64);
    sign + integer_digits
        + if fraction_digits > 0 {
            1 + fraction_digits
        } else {
            0
        }
}

fn append_minimum_fraction_digits(formatted: &mut String, minimum_fraction_digits: usize) {
    if minimum_fraction_digits == 0 {
        return;
    }
    let fraction_digits = formatted
        .split_once('.')
        .map(|(_, fraction)| fraction.len())
        .unwrap_or(0);
    if fraction_digits == 0 {
        formatted.push('.');
    }
    for _ in fraction_digits..minimum_fraction_digits {
        formatted.push('0');
    }
}

fn minimum_fraction_digits(call: &FunctionCall<'_>) -> Result<usize, Diagnostic> {
    let Some(value) = call.option_value("minimumFractionDigits")? else {
        return Ok(0);
    };
    if value.is_empty()
        || value.len() > MAX_DECIMAL_OPERAND_LENGTH
        || !value.bytes().all(|byte| byte.is_ascii_digit())
    {
        return Err(bad_option(
            "minimumFractionDigits option must be a non-negative integer.",
        ));
    }
    let parsed = value.parse::<usize>().map_err(|_| {
        bad_option("minimumFractionDigits option is outside the supported integer range.")
    })?;
    if parsed > MAX_FRACTION_DIGITS {
        return Err(bad_option(
            "minimumFractionDigits option must be a non-negative integer.",
        ));
    }
    Ok(parsed)
}

fn maximum_fraction_digits(call: &FunctionCall<'_>) -> Result<Option<usize>, Diagnostic> {
    let Some(value) = call.option_value("maximumFractionDigits")? else {
        return Ok(None);
    };
    if value.is_empty()
        || value.len() > MAX_DECIMAL_OPERAND_LENGTH
        || !value.bytes().all(|byte| byte.is_ascii_digit())
    {
        return Err(bad_option(
            "maximumFractionDigits option must be a non-negative integer.",
        ));
    }
    let parsed = value.parse::<usize>().map_err(|_| {
        bad_option("maximumFractionDigits option is outside the supported integer range.")
    })?;
    if parsed > MAX_FRACTION_DIGITS {
        return Err(bad_option(
            "maximumFractionDigits option must be a non-negative integer.",
        ));
    }
    Ok(Some(parsed))
}

fn validate_fraction_digits(
    minimum_fraction_digits: usize,
    maximum_fraction_digits: Option<usize>,
) -> Result<(), Diagnostic> {
    if let Some(maximum_fraction_digits) = maximum_fraction_digits {
        if minimum_fraction_digits > maximum_fraction_digits {
            return Err(bad_option(
                "maximumFractionDigits must be greater than or equal to minimumFractionDigits.",
            ));
        }
    }
    Ok(())
}

fn sign_display_always(function: &FunctionRef) -> Result<bool, Diagnostic> {
    Ok(function_option_literal(function, "signDisplay") == Some("always"))
}

fn inherited_sign_display_always(
    source: Option<FunctionSourceRef<'_>>,
) -> Result<bool, Diagnostic> {
    let Some(source) = source else {
        return Ok(false);
    };
    if source.function().name == "number" || source.function().name == "integer" {
        return Ok(source.option_value("signDisplay")?.as_deref() == Some("always"));
    }
    inherited_sign_display_always(source.inherited_source())
}

fn invalid_numeric_selector(
    function: &FunctionRef,
    source: Option<FunctionSourceRef<'_>>,
) -> Result<bool, Diagnostic> {
    Ok(numeric_select_uses_variable(function)
        || (function_option_literal(function, "select") != Some("exact")
            && inherited_exact_numeric_source(source)?))
}

pub(super) fn numeric_select_uses_variable(function: &FunctionRef) -> bool {
    matches!(
        function
            .options
            .as_ref()
            .and_then(|options| options.get("select")),
        Some(ExpressionArg::Variable { .. })
    )
}

pub(super) fn inherited_exact_numeric_source(
    source: Option<FunctionSourceRef<'_>>,
) -> Result<bool, Diagnostic> {
    let Some(source) = source else {
        return Ok(false);
    };
    if is_numeric_function(source.function())
        && source.option_value("select")?.as_deref() == Some("exact")
    {
        return Ok(true);
    }
    inherited_exact_numeric_source(source.inherited_source())
}

pub(super) fn is_numeric_function(function: &FunctionRef) -> bool {
    function.name == "number"
        || function.name == "integer"
        || function.name == "percent"
        || function.name == "offset"
}

fn is_decimal_source_function(function: &FunctionRef) -> bool {
    is_numeric_function(function) || function.name == "currency" || function.name == "relativeTime"
}

fn function_option_literal<'a>(function: &'a FunctionRef, name: &str) -> Option<&'a str> {
    let Some(ExpressionArg::Literal { value }) = function
        .options
        .as_ref()
        .and_then(|options| options.get(name))
    else {
        return None;
    };
    Some(value)
}

fn bad_operand(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-operand", message, 0, 0)
}

fn bad_option(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-option", message, 0, 0)
}

fn bad_selector(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-selector", message, 0, 0)
}
