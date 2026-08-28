use crate::diagnostic::Diagnostic;
use crate::model::{ExpressionArg, FunctionRef};

use super::{FunctionCall, FunctionMatch, FunctionRegistry, FunctionSourceRef};

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
    let value = parse_call_decimal(&call)
        .map_err(|_| bad_operand("Number function requires a numeric operand."))?;
    let mut formatted = format_unlocalized_decimal_with_maximum_fraction_digits(
        value,
        maximum_fraction_digits(&call)?,
    );
    if sign_display_always(&call)? && value >= 0.0 {
        formatted.insert(0, '+');
    }
    append_minimum_fraction_digits(&mut formatted, minimum_fraction_digits(&call)?);
    Ok(formatted)
}

fn select_number(call: FunctionMatch<'_>) -> Result<Option<i32>, Diagnostic> {
    if invalid_numeric_selector(call.function(), call.inherited_source())? {
        return Err(bad_selector("Number selector cannot match this operand."));
    }
    let value = parse_match_decimal(&call)
        .map_err(|_| bad_selector("Number selector requires a numeric operand."))?;
    let value = numeric_match_value(value, &call)?;
    let Ok(key) = parse_decimal_number(call.key()) else {
        return Ok(None);
    };
    Ok(exact_decimal_key_matches(value, key, &call)?.then_some(2))
}

fn format_unlocalized_percent(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let value = parse_call_decimal(&call)
        .map_err(|_| bad_operand("Percent function requires a numeric operand."))?;
    Ok(format_unlocalized_percent_number(
        value,
        sign_display_always(&call)?,
        minimum_fraction_digits(&call)?,
        maximum_fraction_digits(&call)?,
    ))
}

fn select_percent(call: FunctionMatch<'_>) -> Result<Option<i32>, Diagnostic> {
    if invalid_numeric_selector(call.function(), call.inherited_source())? {
        return Err(bad_selector("Percent selector cannot match this operand."));
    }
    let value = parse_match_decimal(&call)
        .map_err(|_| bad_selector("Percent selector requires a numeric operand."))?;
    let value = numeric_match_value(value, &call)?;
    let Ok(key) = parse_decimal_number(call.key()) else {
        return Ok(None);
    };
    Ok(exact_decimal_key_matches(value, key, &call)?.then_some(2))
}

fn format_unlocalized_integer(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let value = parse_call_decimal(&call)
        .map_err(|_| bad_operand("Integer function requires a numeric operand."))?;
    Ok(format_integer_number(
        value.trunc() as i64,
        sign_display_always(&call)?,
    ))
}

fn select_integer(call: FunctionMatch<'_>) -> Result<Option<i32>, Diagnostic> {
    if invalid_numeric_selector(call.function(), call.inherited_source())? {
        return Err(bad_selector("Integer selector cannot match this operand."));
    }
    let value = parse_match_decimal(&call)
        .map_err(|_| bad_selector("Integer selector requires a numeric operand."))?;
    let Ok(key) = parse_offset_number(call.key()) else {
        return Ok(None);
    };
    Ok((value.trunc() as i64 == key).then_some(2))
}

fn format_offset(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let operand = numeric_source_operand(call.inherited_source())
        .or_else(|_| direct_decimal_operand(call.value()))
        .map_err(|_| bad_operand("Offset function requires a numeric operand."))?;
    let offset = offset_delta(&call)?;
    let result = add_integer_offset(&operand, offset)
        .map_err(|_| bad_operand("Offset function requires a numeric operand."))?;
    let value = parse_decimal_number(&result)
        .map_err(|_| bad_operand("Offset result is outside the supported numeric range."))?;
    if sign_display_always(&call)? && value >= 0.0 {
        Ok(format!("+{result}"))
    } else {
        Ok(result)
    }
}

fn select_offset(call: FunctionMatch<'_>) -> Result<Option<i32>, Diagnostic> {
    let value = parse_match_decimal(&call)
        .map_err(|_| bad_selector("Offset selector requires a numeric operand."))?;
    let Ok(key) = parse_decimal_number(call.key()) else {
        return Ok(None);
    };
    Ok(exact_decimal_key_matches(value, key, &call)?.then_some(2))
}

fn exact_decimal_key_matches(
    value: f64,
    key: f64,
    call: &FunctionMatch<'_>,
) -> Result<bool, Diagnostic> {
    if value.fract() == 0.0
        && !has_numeric_option(call, "minimumFractionDigits")?
        && !has_numeric_option(call, "minimumIntegerDigits")?
        && !has_numeric_option(call, "minimumSignificantDigits")?
        && !has_numeric_option(call, "maximumSignificantDigits")?
    {
        let canonical = if value == 0.0 {
            "0".to_string()
        } else {
            value.to_string()
        };
        return Ok(call.key() == canonical);
    }
    Ok(value == key)
}

fn numeric_match_value(value: f64, call: &FunctionMatch<'_>) -> Result<f64, Diagnostic> {
    let minimum = match_fraction_digits(call, "minimumFractionDigits")?.unwrap_or(0);
    let maximum = match_fraction_digits(call, "maximumFractionDigits")?;
    let operand = numeric_selection_operand(value, &call.function().name, minimum, maximum)
        .ok_or_else(|| bad_option("Numeric fraction digits exceed the supported range."))?;
    parse_decimal_number(&operand)
        .map_err(|_| bad_selector("Numeric selector could not resolve its selection operand."))
}

fn match_fraction_digits(
    call: &FunctionMatch<'_>,
    name: &str,
) -> Result<Option<usize>, Diagnostic> {
    let Some(value) = match_numeric_option_value(call, name)? else {
        return Ok(None);
    };
    parse_fraction_digits(&value, name).map(Some)
}

fn offset_delta(call: &FunctionCall<'_>) -> Result<i64, Diagnostic> {
    let add = call.option_value("add")?;
    let subtract = call.option_value("subtract")?;
    match (add, subtract) {
        (Some(_), Some(_)) | (None, None) => Err(bad_option(
            "Offset function requires exactly one of add or subtract.",
        )),
        (Some(value), None) => parse_offset_number(&value)
            .map_err(|_| bad_option("Offset add option must be an integer.")),
        (None, Some(value)) => parse_offset_number(&value)
            .ok()
            .and_then(i64::checked_neg)
            .ok_or_else(|| bad_option("Offset subtract option must be an integer.")),
    }
}

fn parse_offset_number(value: &str) -> Result<i64, std::num::ParseIntError> {
    value.parse::<i64>()
}

fn direct_decimal_operand(value: &str) -> Result<String, ()> {
    if parse_decimal_number(value).is_ok() {
        Ok(value.to_string())
    } else {
        parse_offset_number(value)
            .map(|value| value.to_string())
            .map_err(|_| ())
    }
}

pub(super) fn numeric_source_operand(source: Option<FunctionSourceRef<'_>>) -> Result<String, ()> {
    let Some(source) = source else {
        return Err(());
    };
    if !is_decimal_source_function(source.function()) {
        return numeric_source_operand(source.inherited_source());
    }
    let operand = numeric_source_operand(source.inherited_source())
        .or_else(|_| direct_decimal_operand(source.value()))?;
    match source.function().name.as_str() {
        "integer" => truncate_decimal_operand(&operand),
        "offset" => add_integer_offset(&operand, source_offset_delta(source)?),
        _ => Ok(operand),
    }
}

fn source_offset_delta(source: FunctionSourceRef<'_>) -> Result<i64, ()> {
    let add = source.option_value("add").map_err(|_| ())?;
    let subtract = source.option_value("subtract").map_err(|_| ())?;
    match (add, subtract) {
        (Some(value), None) => parse_offset_number(&value).map_err(|_| ()),
        (None, Some(value)) => parse_offset_number(&value)
            .ok()
            .and_then(i64::checked_neg)
            .ok_or(()),
        _ => Err(()),
    }
}

const MAX_EXPANDED_DECIMAL_DIGITS: usize = 4096;
const MAX_FRACTION_DIGITS: usize = 1000;

struct ScaledDecimal {
    negative: bool,
    digits: Vec<u8>,
    scale: usize,
}

fn truncate_decimal_operand(value: &str) -> Result<String, ()> {
    let mut decimal = parse_scaled_decimal(value)?;
    if decimal.scale >= decimal.digits.len() {
        return Ok("0".to_string());
    }
    decimal
        .digits
        .truncate(decimal.digits.len() - decimal.scale);
    decimal.scale = 0;
    trim_leading_zeroes(&mut decimal.digits);
    Ok(format_scaled_decimal(decimal))
}

fn add_integer_offset(value: &str, delta: i64) -> Result<String, ()> {
    let mut decimal = parse_scaled_decimal(value)?;
    if delta == 0 {
        return Ok(format_scaled_decimal(decimal));
    }
    let mut delta_digits: Vec<u8> = delta
        .unsigned_abs()
        .to_string()
        .bytes()
        .map(|byte| byte - b'0')
        .collect();
    delta_digits.resize(delta_digits.len() + decimal.scale, 0);
    let delta_negative = delta < 0;

    if decimal.negative == delta_negative {
        decimal.digits = add_magnitudes(&decimal.digits, &delta_digits);
    } else {
        match compare_magnitudes(&decimal.digits, &delta_digits) {
            std::cmp::Ordering::Greater => {
                decimal.digits = subtract_magnitudes(&decimal.digits, &delta_digits);
            }
            std::cmp::Ordering::Less => {
                decimal.digits = subtract_magnitudes(&delta_digits, &decimal.digits);
                decimal.negative = delta_negative;
            }
            std::cmp::Ordering::Equal => {
                decimal.digits = vec![0];
                decimal.negative = false;
            }
        }
    }
    Ok(format_scaled_decimal(decimal))
}

fn parse_scaled_decimal(value: &str) -> Result<ScaledDecimal, ()> {
    direct_decimal_operand(value)?;
    let (negative, unsigned) = if let Some(unsigned) = value.strip_prefix('-') {
        (true, unsigned)
    } else {
        (false, value.strip_prefix('+').unwrap_or(value))
    };
    let (mantissa, exponent) = if let Some(index) = unsigned.find(['e', 'E']) {
        (
            &unsigned[..index],
            unsigned[index + 1..].parse::<i32>().map_err(|_| ())?,
        )
    } else {
        (unsigned, 0)
    };
    let (integer, fraction) = mantissa.split_once('.').unwrap_or((mantissa, ""));
    let mut digits: Vec<u8> = integer
        .bytes()
        .chain(fraction.bytes())
        .map(|byte| byte - b'0')
        .collect();
    if digits.iter().all(|digit| *digit == 0) {
        return Ok(ScaledDecimal {
            negative: false,
            digits: vec![0],
            scale: 0,
        });
    }

    let scale = i64::try_from(fraction.len()).map_err(|_| ())? - i64::from(exponent);
    let scale = if scale < 0 {
        let zeroes = usize::try_from(-scale).map_err(|_| ())?;
        if zeroes > MAX_EXPANDED_DECIMAL_DIGITS {
            return Err(());
        }
        digits.resize(digits.len() + zeroes, 0);
        0
    } else {
        let scale = usize::try_from(scale).map_err(|_| ())?;
        if scale > MAX_EXPANDED_DECIMAL_DIGITS {
            return Err(());
        }
        scale
    };
    trim_leading_zeroes(&mut digits);
    Ok(ScaledDecimal {
        negative,
        digits,
        scale,
    })
}

fn compare_magnitudes(left: &[u8], right: &[u8]) -> std::cmp::Ordering {
    left.len().cmp(&right.len()).then_with(|| left.cmp(right))
}

fn add_magnitudes(left: &[u8], right: &[u8]) -> Vec<u8> {
    let mut output = Vec::with_capacity(left.len().max(right.len()) + 1);
    let mut left_index = left.len();
    let mut right_index = right.len();
    let mut carry = 0u8;
    while left_index > 0 || right_index > 0 || carry > 0 {
        let left_digit = if left_index > 0 {
            left_index -= 1;
            left[left_index]
        } else {
            0
        };
        let right_digit = if right_index > 0 {
            right_index -= 1;
            right[right_index]
        } else {
            0
        };
        let sum = left_digit + right_digit + carry;
        output.push(sum % 10);
        carry = sum / 10;
    }
    output.reverse();
    output
}

fn subtract_magnitudes(left: &[u8], right: &[u8]) -> Vec<u8> {
    let mut output = Vec::with_capacity(left.len());
    let mut right_index = right.len();
    let mut borrow = 0i8;
    for left_digit in left.iter().rev() {
        let right_digit = if right_index > 0 {
            right_index -= 1;
            right[right_index] as i8
        } else {
            0
        };
        let mut difference = *left_digit as i8 - right_digit - borrow;
        if difference < 0 {
            difference += 10;
            borrow = 1;
        } else {
            borrow = 0;
        }
        output.push(difference as u8);
    }
    output.reverse();
    trim_leading_zeroes(&mut output);
    output
}

fn trim_leading_zeroes(digits: &mut Vec<u8>) {
    let first = digits
        .iter()
        .position(|digit| *digit != 0)
        .unwrap_or(digits.len().saturating_sub(1));
    if first > 0 {
        digits.drain(..first);
    }
}

fn format_scaled_decimal(decimal: ScaledDecimal) -> String {
    if decimal.digits.iter().all(|digit| *digit == 0) {
        return "0".to_string();
    }
    let mut digits: String = decimal
        .digits
        .iter()
        .map(|digit| char::from(b'0' + *digit))
        .collect();
    if decimal.scale > 0 {
        if digits.len() <= decimal.scale {
            digits.insert_str(0, &"0".repeat(decimal.scale + 1 - digits.len()));
        }
        let split = digits.len() - decimal.scale;
        digits.insert(split, '.');
        while digits.ends_with('0') {
            digits.pop();
        }
        if digits.ends_with('.') {
            digits.pop();
        }
    }
    if decimal.negative {
        digits.insert(0, '-');
    }
    digits
}

fn parse_call_decimal(call: &FunctionCall<'_>) -> Result<f64, ()> {
    parse_source_decimal(call.inherited_source()).or_else(|_| parse_decimal_number(call.value()))
}

fn parse_match_decimal(call: &FunctionMatch<'_>) -> Result<f64, ()> {
    parse_source_decimal(call.inherited_source()).or_else(|_| parse_decimal_number(call.value()))
}

fn parse_source_decimal(source: Option<FunctionSourceRef<'_>>) -> Result<f64, ()> {
    parse_decimal_number(&numeric_source_operand(source)?)
}

pub(super) fn parse_decimal_number(value: &str) -> Result<f64, ()> {
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

fn format_integer_number(value: i64, sign_display_always: bool) -> String {
    if sign_display_always && value >= 0 {
        format!("+{value}")
    } else {
        value.to_string()
    }
}

fn format_unlocalized_percent_number(
    value: f64,
    sign_display_always: bool,
    minimum_fraction_digits: usize,
    maximum_fraction_digits: Option<usize>,
) -> String {
    let mut formatted = format_unlocalized_decimal_with_maximum_fraction_digits(
        value * 100.0,
        maximum_fraction_digits,
    );
    if sign_display_always && value >= 0.0 {
        formatted.insert(0, '+');
    }
    append_minimum_fraction_digits(&mut formatted, minimum_fraction_digits);
    formatted.push('%');
    formatted
}

fn format_unlocalized_decimal_with_maximum_fraction_digits(
    value: f64,
    digits: Option<usize>,
) -> String {
    let Some(digits) = digits else {
        return value.to_string();
    };
    let mut formatted = format!("{:.*}", digits, value);
    if formatted.contains('.') {
        while formatted.ends_with('0') {
            formatted.pop();
        }
        if formatted.ends_with('.') {
            formatted.pop();
        }
    }
    formatted
}

pub(super) fn numeric_selection_operand(
    mut value: f64,
    function_name: &str,
    minimum_fraction_digits: usize,
    maximum_fraction_digits: Option<usize>,
) -> Option<String> {
    if minimum_fraction_digits > MAX_FRACTION_DIGITS
        || maximum_fraction_digits.is_some_and(|digits| digits > MAX_FRACTION_DIGITS)
    {
        return None;
    }
    if function_name == "integer" {
        return Some((value.trunc() as i64).to_string());
    }
    if function_name == "percent" {
        value *= 100.0;
    }
    if function_name == "number" || function_name == "percent" {
        let mut formatted =
            format_unlocalized_decimal_with_maximum_fraction_digits(value, maximum_fraction_digits);
        append_minimum_fraction_digits(&mut formatted, minimum_fraction_digits);
        return Some(formatted);
    }
    Some(value.to_string())
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
    let Some(value) = call_numeric_option_value(call, "minimumFractionDigits")? else {
        return Ok(0);
    };
    parse_fraction_digits(&value, "minimumFractionDigits")
}

fn maximum_fraction_digits(call: &FunctionCall<'_>) -> Result<Option<usize>, Diagnostic> {
    let Some(value) = call_numeric_option_value(call, "maximumFractionDigits")? else {
        return Ok(None);
    };
    parse_fraction_digits(&value, "maximumFractionDigits").map(Some)
}

fn parse_fraction_digits(value: &str, name: &str) -> Result<usize, Diagnostic> {
    if value.is_empty() || !value.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err(bad_option(format!(
            "{name} option must be a non-negative integer."
        )));
    }
    let parsed = value.parse::<usize>().map_err(|_| {
        bad_option(format!(
            "{name} option is outside the supported integer range."
        ))
    })?;
    if parsed > MAX_FRACTION_DIGITS {
        return Err(bad_option(format!(
            "{name} option exceeds the maximum supported value of {MAX_FRACTION_DIGITS}."
        )));
    }
    Ok(parsed)
}

fn sign_display_always(call: &FunctionCall<'_>) -> Result<bool, Diagnostic> {
    Ok(call_numeric_option_value(call, "signDisplay")?.as_deref() == Some("always"))
}

fn call_numeric_option_value(
    call: &FunctionCall<'_>,
    name: &str,
) -> Result<Option<String>, Diagnostic> {
    match call.option_value(name)? {
        Some(value) => Ok(Some(value)),
        None => {
            inherited_numeric_option_value(call.inherited_source(), name, &call.function().name)
        }
    }
}

fn has_numeric_option(call: &FunctionMatch<'_>, name: &str) -> Result<bool, Diagnostic> {
    Ok(match_numeric_option_value(call, name)?.is_some())
}

fn match_numeric_option_value(
    call: &FunctionMatch<'_>,
    name: &str,
) -> Result<Option<String>, Diagnostic> {
    match call.option_value(name)? {
        Some(value) => Ok(Some(value)),
        None => {
            inherited_numeric_option_value(call.inherited_source(), name, &call.function().name)
        }
    }
}

pub(super) fn inherited_numeric_option_value(
    source: Option<FunctionSourceRef<'_>>,
    name: &str,
    target_function: &str,
) -> Result<Option<String>, Diagnostic> {
    let Some(source) = source else {
        return Ok(None);
    };
    if numeric_option_is_discarded(target_function, name)
        || !is_numeric_function(source.function())
        || numeric_option_is_discarded(&source.function().name, name)
    {
        return Ok(None);
    }
    if let Some(value) = source.option_value(name)? {
        return Ok(Some(value));
    }
    inherited_numeric_option_value(source.inherited_source(), name, &source.function().name)
}

fn numeric_option_is_discarded(function_name: &str, option_name: &str) -> bool {
    match function_name {
        "integer" => [
            "minimumFractionDigits",
            "maximumFractionDigits",
            "minimumSignificantDigits",
        ]
        .contains(&option_name),
        "percent" => ["minimumIntegerDigits", "roundingIncrement", "select"].contains(&option_name),
        "offset" => ["add", "subtract"].contains(&option_name),
        _ => false,
    }
}

fn invalid_numeric_selector(
    function: &FunctionRef,
    source: Option<FunctionSourceRef<'_>>,
) -> Result<bool, Diagnostic> {
    Ok(numeric_select_uses_variable(function)
        || (function_option_literal(function, "select") != Some("exact")
            && inherited_exact_numeric_source(source, &function.name)?))
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
    target_function: &str,
) -> Result<bool, Diagnostic> {
    Ok(
        inherited_numeric_option_value(source, "select", target_function)?.as_deref()
            == Some("exact"),
    )
}

pub(super) fn is_numeric_function(function: &FunctionRef) -> bool {
    function.name == "number"
        || function.name == "integer"
        || function.name == "percent"
        || function.name == "offset"
}

fn is_decimal_source_function(function: &FunctionRef) -> bool {
    is_numeric_function(function) || function.name == "currency"
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
