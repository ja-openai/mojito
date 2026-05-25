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
    Ok(format_unlocalized_decimal(
        value,
        sign_display_always(call.function())?,
        minimum_fraction_digits(&call)?,
    ))
}

fn select_number(call: FunctionMatch<'_>) -> Result<Option<i32>, Diagnostic> {
    if invalid_numeric_selector(call.function(), call.inherited_source())? {
        return Err(bad_selector("Number selector cannot match this operand."));
    }
    let value = parse_match_decimal(&call)
        .map_err(|_| bad_selector("Number selector requires a numeric operand."))?;
    let Ok(key) = parse_decimal_number(call.key()) else {
        return Ok(None);
    };
    Ok((value == key).then_some(1))
}

fn format_unlocalized_percent(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let value = parse_call_decimal(&call)
        .map_err(|_| bad_operand("Percent function requires a numeric operand."))?;
    Ok(format_unlocalized_percent_number(
        value,
        sign_display_always(call.function())?,
        minimum_fraction_digits(&call)?,
        maximum_fraction_digits(&call)?,
    ))
}

fn select_percent(call: FunctionMatch<'_>) -> Result<Option<i32>, Diagnostic> {
    if invalid_numeric_selector(call.function(), call.inherited_source())? {
        return Err(bad_selector("Percent selector cannot match this operand."));
    }
    let value = parse_match_decimal(&call)
        .map_err(|_| bad_selector("Percent selector requires a numeric operand."))?
        * 100.0;
    let Ok(key) = parse_decimal_number(call.key()) else {
        return Ok(None);
    };
    Ok((value == key).then_some(1))
}

fn format_unlocalized_integer(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let value = parse_call_decimal(&call)
        .map_err(|_| bad_operand("Integer function requires a numeric operand."))?;
    Ok(format_integer_number(
        value.trunc() as i64,
        sign_display_always(call.function())?,
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
    Ok((value.trunc() as i64 == key).then_some(1))
}

fn format_offset(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let value = parse_offset_number(call.value())
        .map_err(|_| bad_operand("Offset function requires a numeric operand."))?;
    let offset = offset_delta(&call)?;
    let result = value
        .checked_add(offset)
        .ok_or_else(|| bad_operand("Offset result is outside the supported integer range."))?;
    Ok(format_integer_number(
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
    Ok((value == key).then_some(1))
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
            .map(|value| -value)
            .map_err(|_| bad_option("Offset subtract option must be an integer.")),
    }
}

fn parse_offset_number(value: &str) -> Result<i64, std::num::ParseIntError> {
    value.parse::<i64>()
}

fn parse_call_decimal(call: &FunctionCall<'_>) -> Result<f64, ()> {
    parse_decimal_number(call.value()).or_else(|_| parse_source_decimal(call.inherited_source()))
}

fn parse_match_decimal(call: &FunctionMatch<'_>) -> Result<f64, ()> {
    parse_decimal_number(call.value()).or_else(|_| parse_source_decimal(call.inherited_source()))
}

fn parse_source_decimal(source: Option<FunctionSourceRef<'_>>) -> Result<f64, ()> {
    let Some(source) = source else {
        return Err(());
    };
    if is_decimal_source_function(source.function()) {
        parse_decimal_number(source.value())
    } else {
        parse_source_decimal(source.inherited_source())
    }
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

fn format_unlocalized_decimal(
    value: f64,
    sign_display_always: bool,
    minimum_fraction_digits: usize,
) -> String {
    let mut formatted = value.to_string();
    if sign_display_always && value >= 0.0 {
        formatted.insert(0, '+');
    }
    append_minimum_fraction_digits(&mut formatted, minimum_fraction_digits);
    formatted
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
    if value.is_empty() || !value.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err(bad_option(
            "minimumFractionDigits option must be a non-negative integer.",
        ));
    }
    value.parse::<usize>().map_err(|_| {
        bad_option("minimumFractionDigits option is outside the supported integer range.")
    })
}

fn maximum_fraction_digits(call: &FunctionCall<'_>) -> Result<Option<usize>, Diagnostic> {
    let Some(value) = call.option_value("maximumFractionDigits")? else {
        return Ok(None);
    };
    if value.is_empty() || !value.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err(bad_option(
            "maximumFractionDigits option must be a non-negative integer.",
        ));
    }
    value.parse::<usize>().map(Some).map_err(|_| {
        bad_option("maximumFractionDigits option is outside the supported integer range.")
    })
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
