use std::collections::{BTreeMap, BTreeSet};
use std::sync::OnceLock;

use crate::cldr::{
    select_cardinal_plural_category, select_ordinal_plural_category, NumberOperands,
};
use crate::diagnostic::Diagnostic;
use crate::model::{
    AttributeValue, Declaration, Expression, ExpressionArg, FunctionRef, Markup, MessageModel,
    PatternPart, VariableRef, Variant, VariantKey,
};
use serde::{Deserialize, Serialize};
use unicode_normalization::UnicodeNormalization;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum FormattedPart {
    #[serde(rename = "text")]
    Text { value: String },
    #[serde(rename = "fallback")]
    Fallback { source: String },
    #[serde(rename = "expression")]
    Expression {
        value: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        dir: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        attributes: Option<BTreeMap<String, AttributeValue>>,
    },
    #[serde(rename = "markup")]
    Markup {
        kind: String,
        name: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        options: Option<BTreeMap<String, ExpressionArg>>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        attributes: Option<BTreeMap<String, AttributeValue>>,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FallbackFormatResult {
    pub value: String,
    pub errors: Vec<Diagnostic>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FallbackPartsResult {
    pub parts: Vec<FormattedPart>,
    pub errors: Vec<Diagnostic>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BidiIsolation {
    None,
    Default,
}

impl BidiIsolation {
    pub fn from_name(value: Option<&str>) -> Self {
        match value {
            Some("default") => Self::Default,
            _ => Self::None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BidiDirection {
    Auto,
    Ltr,
    Rtl,
}

impl BidiDirection {
    fn marker(self) -> char {
        match self {
            Self::Auto => '\u{2068}',
            Self::Ltr => '\u{2066}',
            Self::Rtl => '\u{2067}',
        }
    }

    fn name(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::Ltr => "ltr",
            Self::Rtl => "rtl",
        }
    }
}

pub type FunctionFormatter = for<'a> fn(FunctionCall<'a>) -> Result<String, Diagnostic>;
pub type FunctionSelector = for<'a> fn(FunctionMatch<'a>) -> Result<Option<i32>, Diagnostic>;

#[derive(Clone)]
pub struct FunctionRegistry {
    formatters: BTreeMap<String, FunctionFormatter>,
    selectors: BTreeMap<String, FunctionSelector>,
}

impl FunctionRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn empty() -> Self {
        Self {
            formatters: BTreeMap::new(),
            selectors: BTreeMap::new(),
        }
    }

    pub fn with_function(mut self, name: impl Into<String>, formatter: FunctionFormatter) -> Self {
        self.formatters.insert(name.into(), formatter);
        self
    }

    pub fn with_selector(mut self, name: impl Into<String>, selector: FunctionSelector) -> Self {
        self.selectors.insert(name.into(), selector);
        self
    }

    pub fn register(&mut self, name: impl Into<String>, formatter: FunctionFormatter) {
        self.formatters.insert(name.into(), formatter);
    }

    pub fn register_selector(&mut self, name: impl Into<String>, selector: FunctionSelector) {
        self.selectors.insert(name.into(), selector);
    }

    fn has_selector(&self, function: &FunctionRef) -> bool {
        self.selectors.contains_key(&function.name)
    }

    fn has_formatter(&self, function: &FunctionRef) -> bool {
        self.formatters.contains_key(&function.name)
    }

    fn format(
        &self,
        value: &str,
        function: &FunctionRef,
        locale: &str,
        values: &BTreeMap<String, ResolvedValue>,
        source: Option<&ResolvedFunctionSource>,
    ) -> Result<String, Diagnostic> {
        let Some(formatter) = self.formatters.get(&function.name) else {
            return Err(Diagnostic::new(
                "unsupported-function",
                format!(
                    "Function :{} is not supported by this formatter registry.",
                    function.name
                ),
                0,
                0,
            ));
        };
        formatter(FunctionCall {
            value,
            function,
            locale,
            values,
            source: source.map(|source| FunctionSourceRef { source, values }),
        })
    }

    fn select(
        &self,
        value: &str,
        function: &FunctionRef,
        key: &str,
        locale: &str,
        values: &BTreeMap<String, ResolvedValue>,
        source: Option<&ResolvedFunctionSource>,
    ) -> Result<Option<i32>, Diagnostic> {
        let Some(selector) = self.selectors.get(&function.name) else {
            return Ok(None);
        };
        selector(FunctionMatch {
            value,
            function,
            key,
            locale,
            values,
            source: source.map(|source| FunctionSourceRef { source, values }),
        })
    }
}

impl Default for FunctionRegistry {
    fn default() -> Self {
        let mut registry = Self::empty();
        registry.register("string", passthrough_function);
        registry.register("number", number_function);
        registry.register_selector("number", number_selector);
        registry.register("percent", percent_function);
        registry.register_selector("percent", percent_selector);
        registry.register("currency", currency_function);
        registry.register_selector("currency", currency_selector);
        registry.register("integer", integer_function);
        registry.register_selector("integer", integer_selector);
        registry.register("datetime", datetime_function);
        registry.register("date", date_function);
        registry.register("time", time_function);
        registry.register("offset", offset_function);
        registry.register_selector("offset", offset_selector);
        registry
    }
}

pub struct FunctionCall<'a> {
    value: &'a str,
    function: &'a FunctionRef,
    locale: &'a str,
    values: &'a BTreeMap<String, ResolvedValue>,
    source: Option<FunctionSourceRef<'a>>,
}

pub struct FunctionMatch<'a> {
    value: &'a str,
    function: &'a FunctionRef,
    key: &'a str,
    locale: &'a str,
    values: &'a BTreeMap<String, ResolvedValue>,
    source: Option<FunctionSourceRef<'a>>,
}

#[derive(Clone, Copy)]
pub struct FunctionSourceRef<'a> {
    source: &'a ResolvedFunctionSource,
    values: &'a BTreeMap<String, ResolvedValue>,
}

impl<'a> FunctionCall<'a> {
    pub fn value(&self) -> &'a str {
        self.value
    }

    pub fn function(&self) -> &'a FunctionRef {
        self.function
    }

    pub fn locale(&self) -> &'a str {
        self.locale
    }

    pub fn option_value(&self, name: &str) -> Result<Option<String>, Diagnostic> {
        option_value(self.function, self.values, name)
    }

    pub fn inherited_source(&self) -> Option<FunctionSourceRef<'a>> {
        self.source
    }
}

impl<'a> FunctionMatch<'a> {
    pub fn value(&self) -> &'a str {
        self.value
    }

    pub fn function(&self) -> &'a FunctionRef {
        self.function
    }

    pub fn key(&self) -> &'a str {
        self.key
    }

    pub fn locale(&self) -> &'a str {
        self.locale
    }

    pub fn option_value(&self, name: &str) -> Result<Option<String>, Diagnostic> {
        option_value(self.function, self.values, name)
    }

    pub fn inherited_source(&self) -> Option<FunctionSourceRef<'a>> {
        self.source
    }
}

impl<'a> FunctionSourceRef<'a> {
    pub fn value(&self) -> &'a str {
        &self.source.value
    }

    pub fn function(&self) -> &'a FunctionRef {
        &self.source.function
    }

    pub fn option_value(&self, name: &str) -> Result<Option<String>, Diagnostic> {
        option_value(&self.source.function, self.values, name)
    }

    pub fn inherited_source(&self) -> Option<FunctionSourceRef<'a>> {
        self.source
            .inherited
            .as_deref()
            .map(|source| FunctionSourceRef {
                source,
                values: self.values,
            })
    }
}

fn option_value(
    function: &FunctionRef,
    values: &BTreeMap<String, ResolvedValue>,
    name: &str,
) -> Result<Option<String>, Diagnostic> {
    let Some(option) = function
        .options
        .as_ref()
        .and_then(|options| options.get(name))
    else {
        return Ok(None);
    };
    match option {
        ExpressionArg::Literal { value } => Ok(Some(value.clone())),
        ExpressionArg::Variable { name } => values
            .get(name)
            .map(ResolvedValue::rendered)
            .map(Some)
            .ok_or_else(|| missing_argument(name)),
    }
}

fn passthrough_function(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    Ok(call.value().to_string())
}

fn number_function(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let value = parse_call_decimal(&call)
        .map_err(|_| bad_operand("Number function requires a numeric operand."))?;
    Ok(format_decimal_number(
        value,
        sign_display_always(call.function())?,
        minimum_fraction_digits(&call)?,
    ))
}

fn number_selector(call: FunctionMatch<'_>) -> Result<Option<i32>, Diagnostic> {
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

fn percent_function(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let value = parse_call_decimal(&call)
        .map_err(|_| bad_operand("Percent function requires a numeric operand."))?;
    Ok(format_percent_number(
        value,
        sign_display_always(call.function())?,
        minimum_fraction_digits(&call)?,
        maximum_fraction_digits(&call)?,
    ))
}

fn percent_selector(call: FunctionMatch<'_>) -> Result<Option<i32>, Diagnostic> {
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

fn currency_function(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let value = parse_call_decimal(&call)
        .map_err(|_| bad_operand("Currency function requires a numeric operand."))?;
    let currency = currency_code(&call)?
        .ok_or_else(|| bad_operand("Currency function requires a currency option."))?;
    Ok(format_currency_number(
        value,
        &currency,
        currency_fraction_digits(&call)?,
    ))
}

fn currency_selector(_call: FunctionMatch<'_>) -> Result<Option<i32>, Diagnostic> {
    Err(bad_selector("Currency selector is not supported."))
}

fn integer_function(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let value = parse_call_decimal(&call)
        .map_err(|_| bad_operand("Integer function requires a numeric operand."))?;
    Ok(format_integer_number(
        value.trunc() as i64,
        sign_display_always(call.function())?,
    ))
}

fn integer_selector(call: FunctionMatch<'_>) -> Result<Option<i32>, Diagnostic> {
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

fn offset_function(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let value = parse_offset_number(call.value())
        .map_err(|_| bad_operand("Offset function requires a numeric operand."))?;
    let offset = offset_delta(&call)?;
    let result = value
        .checked_add(offset)
        .ok_or_else(|| bad_operand("Offset result is outside the supported integer range."))?;
    Ok(format_offset_number(
        result,
        inherited_sign_display_always(call.inherited_source())?,
    ))
}

fn offset_selector(call: FunctionMatch<'_>) -> Result<Option<i32>, Diagnostic> {
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

fn parse_decimal_number(value: &str) -> Result<f64, ()> {
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

fn format_offset_number(value: i64, sign_display_always: bool) -> String {
    format_integer_number(value, sign_display_always)
}

fn format_integer_number(value: i64, sign_display_always: bool) -> String {
    if sign_display_always && value >= 0 {
        format!("+{value}")
    } else {
        value.to_string()
    }
}

fn format_decimal_number(
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

fn format_percent_number(
    value: f64,
    sign_display_always: bool,
    minimum_fraction_digits: usize,
    maximum_fraction_digits: Option<usize>,
) -> String {
    let mut formatted =
        format_decimal_with_maximum_fraction_digits(value * 100.0, maximum_fraction_digits);
    if sign_display_always && value >= 0.0 {
        formatted.insert(0, '+');
    }
    append_minimum_fraction_digits(&mut formatted, minimum_fraction_digits);
    formatted.push('%');
    formatted
}

fn format_decimal_with_maximum_fraction_digits(value: f64, digits: Option<usize>) -> String {
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

fn format_currency_number(value: f64, currency: &str, fraction_digits: Option<usize>) -> String {
    let number = match fraction_digits {
        Some(digits) => format!("{:.*}", digits, value),
        None => value.to_string(),
    };
    format!("{currency} {number}")
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

fn currency_code(call: &FunctionCall<'_>) -> Result<Option<String>, Diagnostic> {
    if let Some(value) = call.option_value("currency")? {
        return Ok(Some(value));
    }
    inherited_currency_code(call.inherited_source())
}

fn inherited_currency_code(
    source: Option<FunctionSourceRef<'_>>,
) -> Result<Option<String>, Diagnostic> {
    let Some(source) = source else {
        return Ok(None);
    };
    if source.function().name == "currency" {
        if let Some(value) = source.option_value("currency")? {
            return Ok(Some(value));
        }
    }
    inherited_currency_code(source.inherited_source())
}

fn currency_fraction_digits(call: &FunctionCall<'_>) -> Result<Option<usize>, Diagnostic> {
    let Some(value) = call.option_value("fractionDigits")? else {
        return Ok(None);
    };
    if value == "auto" {
        return Ok(None);
    }
    if value.is_empty() || !value.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err(bad_option(
            "fractionDigits option must be auto or a non-negative integer.",
        ));
    }
    value
        .parse::<usize>()
        .map(Some)
        .map_err(|_| bad_option("fractionDigits option is outside the supported integer range."))
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

fn numeric_select_uses_variable(function: &FunctionRef) -> bool {
    matches!(
        function
            .options
            .as_ref()
            .and_then(|options| options.get("select")),
        Some(ExpressionArg::Variable { .. })
    )
}

fn inherited_exact_numeric_source(
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

fn is_numeric_function(function: &FunctionRef) -> bool {
    function.name == "number" || function.name == "integer" || function.name == "percent"
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

fn bidi_direction_for_function(
    function: &FunctionRef,
    source: Option<&ResolvedFunctionSource>,
) -> Result<Option<BidiDirection>, Diagnostic> {
    if let Some(value) = function_option_literal(function, "u:dir") {
        return parse_bidi_direction(value).map(Some);
    }
    bidi_direction_from_source(source)
}

fn bidi_direction_from_source(
    source: Option<&ResolvedFunctionSource>,
) -> Result<Option<BidiDirection>, Diagnostic> {
    let Some(source) = source else {
        return Ok(None);
    };
    if let Some(value) = function_option_literal(&source.function, "u:dir") {
        return parse_bidi_direction(value).map(Some);
    }
    bidi_direction_from_source(source.inherited.as_deref())
}

fn parse_bidi_direction(value: &str) -> Result<BidiDirection, Diagnostic> {
    match value {
        "auto" => Ok(BidiDirection::Auto),
        "ltr" => Ok(BidiDirection::Ltr),
        "rtl" => Ok(BidiDirection::Rtl),
        _ => Err(bad_option("u:dir option must be auto, ltr, or rtl.")),
    }
}

fn datetime_function(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let value = call.value();
    if is_iso_date(value) || is_iso_datetime(value) {
        return Ok(value.to_string());
    }
    Err(bad_operand(
        "Datetime function requires a date or datetime operand.",
    ))
}

fn time_function(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    require_datetime_operand(call.value())?;
    Ok(call.value().to_string())
}

fn date_function(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let value = call.value();
    if is_iso_date(value) || is_iso_datetime(value) {
        Ok(value.to_string())
    } else {
        Err(bad_operand(
            "Date function requires a date or datetime operand.",
        ))
    }
}

fn require_datetime_operand(value: &str) -> Result<(), Diagnostic> {
    if is_iso_datetime(value) {
        Ok(())
    } else {
        Err(bad_operand(
            "Datetime and time functions require a datetime operand.",
        ))
    }
}

fn is_iso_datetime(value: &str) -> bool {
    let Some((date, time)) = value.split_once('T') else {
        return false;
    };
    is_iso_date(date) && is_iso_time(time)
}

fn is_iso_date(value: &str) -> bool {
    let bytes = value.as_bytes();
    bytes.len() == 10
        && bytes[4] == b'-'
        && bytes[7] == b'-'
        && bytes[..4].iter().all(u8::is_ascii_digit)
        && bytes[5..7].iter().all(u8::is_ascii_digit)
        && bytes[8..].iter().all(u8::is_ascii_digit)
}

fn is_iso_time(value: &str) -> bool {
    let bytes = value.as_bytes();
    bytes.len() == 8
        && bytes[2] == b':'
        && bytes[5] == b':'
        && bytes[..2].iter().all(u8::is_ascii_digit)
        && bytes[3..5].iter().all(u8::is_ascii_digit)
        && bytes[6..].iter().all(u8::is_ascii_digit)
}

fn default_function_registry() -> &'static FunctionRegistry {
    static DEFAULT_FUNCTIONS: OnceLock<FunctionRegistry> = OnceLock::new();
    DEFAULT_FUNCTIONS.get_or_init(FunctionRegistry::default)
}

pub fn format_model(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
) -> Result<String, Diagnostic> {
    format_model_with_locale(model, arguments, "en")
}

pub fn format_model_with_locale(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
) -> Result<String, Diagnostic> {
    format_model_with_locale_and_functions(model, arguments, locale, default_function_registry())
}

pub fn format_model_with_locale_and_functions(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
    functions: &FunctionRegistry,
) -> Result<String, Diagnostic> {
    format_model_with_locale_and_functions_and_bidi(
        model,
        arguments,
        locale,
        functions,
        BidiIsolation::None,
    )
}

pub fn format_model_with_locale_and_bidi(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
    bidi_isolation: BidiIsolation,
) -> Result<String, Diagnostic> {
    format_model_with_locale_and_functions_and_bidi(
        model,
        arguments,
        locale,
        default_function_registry(),
        bidi_isolation,
    )
}

pub fn format_model_with_locale_and_functions_and_bidi(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
    functions: &FunctionRegistry,
    bidi_isolation: BidiIsolation,
) -> Result<String, Diagnostic> {
    Ok(parts_to_string(
        &format_model_to_parts_with_locale_and_functions(model, arguments, locale, functions)?,
        bidi_isolation,
    ))
}

pub fn format_model_with_fallback(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
) -> Result<FallbackFormatResult, Diagnostic> {
    format_model_with_locale_and_functions_and_bidi_and_fallback(
        model,
        arguments,
        "en",
        default_function_registry(),
        BidiIsolation::None,
    )
}

pub fn format_model_with_locale_and_functions_and_bidi_and_fallback(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
    functions: &FunctionRegistry,
    bidi_isolation: BidiIsolation,
) -> Result<FallbackFormatResult, Diagnostic> {
    let result = format_model_to_parts_with_locale_and_functions_and_fallback(
        model, arguments, locale, functions,
    )?;
    Ok(FallbackFormatResult {
        value: parts_to_string(&result.parts, bidi_isolation),
        errors: result.errors,
    })
}

pub fn format_model_to_parts_with_locale(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
) -> Result<Vec<FormattedPart>, Diagnostic> {
    format_model_to_parts_with_locale_and_functions(
        model,
        arguments,
        locale,
        default_function_registry(),
    )
}

pub fn format_model_to_parts_with_locale_and_functions(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
    functions: &FunctionRegistry,
) -> Result<Vec<FormattedPart>, Diagnostic> {
    validate_model(model)?;
    let mut context = FormatContext::new(arguments, locale, functions);
    context.apply_declarations(model.declarations())?;
    match model {
        MessageModel::Message { pattern, .. } => context.format_pattern_to_parts(pattern),
        MessageModel::Select {
            selectors,
            variants,
            ..
        } => context.format_select_to_parts(selectors, variants),
    }
}

pub fn format_model_to_parts_with_locale_and_functions_and_fallback(
    model: &MessageModel,
    arguments: &BTreeMap<String, serde_json::Value>,
    locale: &str,
    functions: &FunctionRegistry,
) -> Result<FallbackPartsResult, Diagnostic> {
    validate_model(model)?;
    let mut context = FormatContext::new(arguments, locale, functions).with_fallback();
    context.apply_declarations(model.declarations())?;
    let parts = match model {
        MessageModel::Message { pattern, .. } => context.format_pattern_to_parts(pattern)?,
        MessageModel::Select {
            selectors,
            variants,
            ..
        } => context.format_select_to_parts(selectors, variants)?,
    };
    Ok(FallbackPartsResult {
        parts,
        errors: context.errors,
    })
}

fn validate_model(model: &MessageModel) -> Result<(), Diagnostic> {
    validate_declarations(model.declarations())?;
    match model {
        MessageModel::Message { pattern, .. } => validate_pattern(pattern)?,
        MessageModel::Select {
            declarations,
            selectors,
            variants,
        } => {
            validate_selector_annotations(declarations, selectors)?;
            for variant in variants {
                validate_pattern(&variant.value)?;
            }
        }
    }
    Ok(())
}

fn validate_declarations(declarations: &[Declaration]) -> Result<(), Diagnostic> {
    let mut names = BTreeSet::new();
    for declaration in declarations {
        let name = match declaration {
            Declaration::Input { name, value } => {
                validate_input_declaration(name, value)?;
                name
            }
            Declaration::Local { name, .. } => name,
        };
        if !names.insert(name) {
            return Err(model_error(
                "duplicate-declaration",
                format!("Declaration ${name} is defined more than once."),
            ));
        }
    }
    validate_local_references(declarations)?;
    Ok(())
}

fn validate_local_references(declarations: &[Declaration]) -> Result<(), Diagnostic> {
    let mut forbidden = BTreeSet::new();
    for declaration in declarations.iter().rev() {
        let Declaration::Local { name, value } = declaration else {
            continue;
        };
        forbidden.insert(name.as_str());
        if expression_references_any(value, &forbidden) {
            return Err(model_error(
                "duplicate-declaration",
                format!(
                    "Local declaration ${name} must not reference itself or later local declarations."
                ),
            ));
        }
    }
    Ok(())
}

fn expression_references_any(expression: &Expression, names: &BTreeSet<&str>) -> bool {
    expression
        .arg
        .as_ref()
        .is_some_and(|arg| expression_arg_references_any(arg, names))
        || expression
            .function
            .as_ref()
            .is_some_and(|function| function_references_any(function, names))
}

fn function_references_any(function: &FunctionRef, names: &BTreeSet<&str>) -> bool {
    function.options.as_ref().is_some_and(|options| {
        options
            .values()
            .any(|arg| expression_arg_references_any(arg, names))
    })
}

fn expression_arg_references_any(arg: &ExpressionArg, names: &BTreeSet<&str>) -> bool {
    matches!(arg, ExpressionArg::Variable { name } if names.contains(name.as_str()))
}

fn validate_input_declaration(name: &str, value: &Expression) -> Result<(), Diagnostic> {
    match &value.arg {
        Some(ExpressionArg::Variable {
            name: variable_name,
        }) if variable_name == name => Ok(()),
        _ => Err(model_error(
            "invalid-input-declaration",
            format!("Input declaration ${name} must bind the same variable name."),
        )),
    }
}

fn validate_pattern(pattern: &[PatternPart]) -> Result<(), Diagnostic> {
    for part in pattern {
        match part {
            PatternPart::Text(text) if text.is_empty() => {
                return Err(model_error(
                    "invalid-pattern-text",
                    "Pattern text parts must be non-empty.",
                ));
            }
            PatternPart::Markup(markup) => validate_markup(markup)?,
            _ => {}
        }
    }
    Ok(())
}

fn validate_markup(markup: &Markup) -> Result<(), Diagnostic> {
    match markup.kind.as_str() {
        "open" | "standalone" | "close" => Ok(()),
        _ => Err(model_error(
            "invalid-markup-kind",
            "Markup kind must be open, standalone, or close.",
        )),
    }
}

fn validate_selector_annotations(
    declarations: &[Declaration],
    selectors: &[VariableRef],
) -> Result<(), Diagnostic> {
    let annotations = selector_annotations(declarations);
    for selector in selectors {
        if !annotations.contains_key(&selector.name) {
            return Err(model_error(
                "missing-selector-annotation",
                format!(
                    "Selector ${} must reference a declaration with a function.",
                    selector.name
                ),
            ));
        }
    }
    Ok(())
}

fn selector_annotations(declarations: &[Declaration]) -> BTreeMap<String, SelectorAnnotation> {
    let mut annotations = BTreeMap::new();
    for declaration in declarations {
        let (name, value) = declaration_name_value(declaration);
        if let Some(function) = &value.function {
            annotations.insert(
                name.to_string(),
                SelectorAnnotation::from_function(function),
            );
        }
    }

    let mut changed = true;
    while changed {
        changed = false;
        for declaration in declarations {
            let (name, value) = declaration_name_value(declaration);
            if annotations.contains_key(name) {
                continue;
            }
            let Some(ExpressionArg::Variable { name: source }) = &value.arg else {
                continue;
            };
            let Some(annotation) = annotations.get(source).cloned() else {
                continue;
            };
            annotations.insert(name.to_string(), annotation);
            changed = true;
        }
    }

    annotations
}

fn declaration_name_value(declaration: &Declaration) -> (&str, &Expression) {
    match declaration {
        Declaration::Input { name, value } | Declaration::Local { name, value } => (name, value),
    }
}

struct FormatContext<'a> {
    values: BTreeMap<String, ResolvedValue>,
    selector_annotations: BTreeMap<String, SelectorAnnotation>,
    failed_values: BTreeSet<String>,
    errors: Vec<Diagnostic>,
    locale: String,
    functions: &'a FunctionRegistry,
    fallback: bool,
}

impl<'a> FormatContext<'a> {
    fn new(
        arguments: &BTreeMap<String, serde_json::Value>,
        locale: &str,
        functions: &'a FunctionRegistry,
    ) -> Self {
        Self {
            values: arguments
                .iter()
                .map(|(name, value)| (name.clone(), ResolvedValue::from_json(value.clone())))
                .collect(),
            selector_annotations: BTreeMap::new(),
            failed_values: BTreeSet::new(),
            errors: Vec::new(),
            locale: locale.to_string(),
            functions,
            fallback: false,
        }
    }

    fn with_fallback(mut self) -> Self {
        self.fallback = true;
        self
    }

    fn apply_declarations(&mut self, declarations: &[Declaration]) -> Result<(), Diagnostic> {
        self.selector_annotations = selector_annotations(declarations);
        for declaration in declarations {
            match declaration {
                Declaration::Input { name, value } => {
                    self.apply_input_declaration(name, value)?;
                }
                Declaration::Local { name, value } => {
                    let rendered = self.format_expression_output(value)?;
                    if rendered.had_error {
                        self.failed_values.insert(name.clone());
                        self.values.remove(name);
                    } else {
                        self.values.insert(
                            name.clone(),
                            ResolvedValue::string(rendered.value, rendered.source),
                        );
                    }
                }
            }
        }
        Ok(())
    }

    fn apply_input_declaration(
        &mut self,
        name: &str,
        expression: &Expression,
    ) -> Result<(), Diagnostic> {
        let Some(function) = expression.function.as_ref() else {
            return Ok(());
        };
        if !self.functions.has_selector(function) || !self.functions.has_formatter(function) {
            return Ok(());
        }
        let Some(input) = self.values.get(name).cloned() else {
            if self.fallback {
                self.failed_values.insert(name.to_string());
                self.errors.push(unresolved_variable(name));
                self.errors
                    .push(bad_operand("Function operand is not available."));
                return Ok(());
            }
            return Err(missing_argument(name));
        };
        let value = input.rendered();
        self.record_function_resolution_errors(function, input.source.as_ref())?;
        match self.functions.format(
            &value,
            function,
            &self.locale,
            &self.values,
            input.source.as_ref(),
        ) {
            Ok(formatted) => {
                if let Some(slot) = self.values.get_mut(name) {
                    slot.value = serde_json::Value::String(formatted);
                    slot.source = Some(ResolvedFunctionSource::new(
                        input.source_value(&value),
                        function.clone(),
                        input.source,
                    ));
                }
                Ok(())
            }
            Err(error) if self.fallback => {
                self.errors.push(fallback_error(error));
                self.failed_values.insert(name.to_string());
                self.values.remove(name);
                Ok(())
            }
            Err(error) => Err(error),
        }
    }

    fn format_select_to_parts(
        &mut self,
        selectors: &[VariableRef],
        variants: &[Variant],
    ) -> Result<Vec<FormattedPart>, Diagnostic> {
        let selector_values = selectors
            .iter()
            .map(|selector| {
                let annotation = self.selector_annotations.get(&selector.name).cloned();
                let Some(value) = self.values.get(&selector.name) else {
                    if self.fallback {
                        let failed_value = self.failed_values.contains(&selector.name);
                        if !failed_value {
                            self.errors.push(unresolved_variable(&selector.name));
                        }
                        if annotation.as_ref().is_some_and(|annotation| {
                            self.functions.has_selector(&annotation.function)
                        }) {
                            if !failed_value {
                                self.errors
                                    .push(bad_operand("Selector operand is not available."));
                            }
                            self.errors
                                .push(bad_selector("Selector operand is not available."));
                        }
                        let string_select = annotation
                            .as_ref()
                            .is_some_and(|annotation| annotation.is_string());
                        return Ok(SelectorValue {
                            normalized_rendered: string_select.then(|| normalize_string_key("")),
                            rendered: String::new(),
                            exact_match: false,
                            selection_key: None,
                            function: annotation.map(|annotation| annotation.function),
                            source: None,
                        });
                    }
                    return Err(missing_argument(&selector.name));
                };
                let rendered = value.rendered();
                let string_select = annotation
                    .as_ref()
                    .is_some_and(|annotation| annotation.is_string());
                let exact_match = annotation
                    .as_ref()
                    .is_none_or(|annotation| annotation.exact_match());
                let selection_key = self.selection_key_for_selector(annotation.as_ref(), value);
                let source = value.source.clone();
                self.record_selector_resolution_errors(annotation.as_ref())?;
                Ok(SelectorValue {
                    normalized_rendered: string_select.then(|| normalize_string_key(&rendered)),
                    rendered,
                    exact_match,
                    selection_key,
                    function: annotation.map(|annotation| annotation.function),
                    source,
                })
            })
            .collect::<Result<Vec<_>, _>>()?;

        let mut signatures = BTreeSet::new();
        let mut fallback = None;
        let mut selected = None;
        for variant in variants {
            validate_variant(variant, &selector_values, &mut signatures)?;
            if fallback.is_none() && is_fallback_variant(variant) {
                fallback = Some(variant);
            }
            let Some(rank) = self.variant_match_rank(variant, &selector_values)? else {
                continue;
            };
            if selected
                .as_ref()
                .is_none_or(|(_, selected_rank): &(&Variant, Vec<i32>)| rank > *selected_rank)
            {
                selected = Some((variant, rank));
            }
        }
        let fallback = fallback.ok_or_else(|| {
            model_error(
                "missing-fallback-variant",
                "Select messages must include a catch-all fallback variant.",
            )
        })?;
        let selected = selected
            .map(|(variant, _)| variant)
            .or(Some(fallback))
            .ok_or_else(|| {
                Diagnostic::new(
                    "missing-select-variant",
                    "No select variant matched and no catch-all variant is present.",
                    0,
                    0,
                )
            })?;

        self.format_pattern_to_parts(&selected.value)
    }

    fn variant_match_rank(
        &mut self,
        variant: &Variant,
        selector_values: &[SelectorValue],
    ) -> Result<Option<Vec<i32>>, Diagnostic> {
        if variant.keys.len() != selector_values.len() {
            return Ok(None);
        }
        let mut ranks = Vec::with_capacity(variant.keys.len());
        for (key, selector) in variant.keys.iter().zip(selector_values) {
            let Some(rank) = self.key_match_rank(key, selector)? else {
                return Ok(None);
            };
            ranks.push(rank);
        }
        Ok(Some(ranks))
    }

    fn key_match_rank(
        &mut self,
        key: &VariantKey,
        selector: &SelectorValue,
    ) -> Result<Option<i32>, Diagnostic> {
        match key {
            VariantKey::CatchAll => Ok(Some(0)),
            VariantKey::Literal { value } => {
                if (selector.exact_match && literal_key_matches(value, selector))
                    || selector
                        .selection_key
                        .as_ref()
                        .is_some_and(|category| category == value)
                {
                    return Ok(Some(1));
                }
                let Some(function) = selector.function.as_ref() else {
                    return Ok(None);
                };
                match self.functions.select(
                    &selector.rendered,
                    function,
                    value,
                    &self.locale,
                    &self.values,
                    selector.source.as_ref(),
                ) {
                    Ok(rank) => Ok(rank),
                    Err(error) if self.fallback => {
                        self.errors.push(fallback_error(error));
                        self.errors.push(bad_selector("Selector failed to match."));
                        Ok(None)
                    }
                    Err(error) => Err(error),
                }
            }
        }
    }

    fn format_pattern_to_parts(
        &mut self,
        pattern: &[PatternPart],
    ) -> Result<Vec<FormattedPart>, Diagnostic> {
        let mut output = Vec::new();
        for part in pattern {
            match part {
                PatternPart::Text(text) => output.push(FormattedPart::Text {
                    value: text.clone(),
                }),
                PatternPart::Expression(expression) => {
                    let rendered = self.format_expression_output(expression)?;
                    if rendered.had_error {
                        output.push(FormattedPart::Fallback {
                            source: fallback_source(expression),
                        });
                        continue;
                    }
                    output.push(FormattedPart::Expression {
                        value: rendered.value,
                        dir: rendered.bidi_direction.map(|dir| dir.name().to_string()),
                        attributes: expression.attributes.clone(),
                    });
                }
                PatternPart::Markup(markup) => {
                    self.record_markup_resolution_errors(markup)?;
                    output.push(markup_to_part(markup));
                }
            }
        }
        Ok(output)
    }

    fn format_expression_output(
        &mut self,
        expression: &Expression,
    ) -> Result<ExpressionOutput, Diagnostic> {
        let mut had_error = false;
        let (value, source) = match &expression.arg {
            Some(ExpressionArg::Variable { name }) => {
                if let Some(value) = self.values.get(name) {
                    (value.rendered(), value.source.clone())
                } else if self.fallback {
                    had_error = true;
                    if !self.failed_values.contains(name) {
                        self.errors.push(unresolved_variable(name));
                    }
                    if expression.function.is_some() {
                        self.errors
                            .push(bad_operand("Function operand is not available."));
                    }
                    (fallback_source(expression), None)
                } else {
                    return Err(missing_argument(name));
                }
            }
            Some(ExpressionArg::Literal { value }) => (value.clone(), None),
            None => (String::new(), None),
        };

        if had_error {
            return Ok(ExpressionOutput {
                value,
                had_error: true,
                source: None,
                bidi_direction: None,
            });
        }

        let Some(function) = expression.function.as_ref() else {
            let bidi_direction = bidi_direction_from_source(source.as_ref())?;
            return Ok(ExpressionOutput {
                value,
                had_error,
                source,
                bidi_direction,
            });
        };
        self.record_function_resolution_errors(function, source.as_ref())?;
        let bidi_direction = bidi_direction_for_function(function, source.as_ref())?;

        let source_value = source
            .as_ref()
            .map(|source| source.value.clone())
            .unwrap_or_else(|| value.clone());
        match self.functions.format(
            &value,
            function,
            &self.locale,
            &self.values,
            source.as_ref(),
        ) {
            Ok(formatted) => Ok(ExpressionOutput {
                value: formatted,
                had_error: false,
                source: Some(ResolvedFunctionSource::new(
                    source_value,
                    function.clone(),
                    source,
                )),
                bidi_direction,
            }),
            Err(error) if self.fallback => {
                self.errors.push(fallback_error(error));
                Ok(ExpressionOutput {
                    value: fallback_source(expression),
                    had_error: true,
                    source: None,
                    bidi_direction: None,
                })
            }
            Err(error) => Err(error),
        }
    }

    fn record_function_resolution_errors(
        &mut self,
        function: &FunctionRef,
        source: Option<&ResolvedFunctionSource>,
    ) -> Result<(), Diagnostic> {
        if !is_numeric_function(function) {
            return Ok(());
        }
        if numeric_select_uses_variable(function)
            || inherited_exact_numeric_source(source.map(|source| FunctionSourceRef {
                source,
                values: &self.values,
            }))?
        {
            let error = bad_option("Numeric select option is not valid in this context.");
            if self.fallback {
                self.errors.push(error);
                Ok(())
            } else {
                Err(error)
            }
        } else {
            Ok(())
        }
    }

    fn record_selector_resolution_errors(
        &mut self,
        annotation: Option<&SelectorAnnotation>,
    ) -> Result<(), Diagnostic> {
        if !annotation.is_some_and(|annotation| annotation.function.name == "currency") {
            return Ok(());
        }
        let error = bad_selector("Currency selector is not supported.");
        if self.fallback {
            self.errors.push(error);
            Ok(())
        } else {
            Err(error)
        }
    }

    fn record_markup_resolution_errors(&mut self, markup: &Markup) -> Result<(), Diagnostic> {
        if !markup
            .options
            .as_ref()
            .is_some_and(|options| options.contains_key("u:dir"))
        {
            return Ok(());
        }
        let error = bad_option("u:dir is not valid on markup.");
        if self.fallback {
            self.errors.push(error);
            Ok(())
        } else {
            Err(error)
        }
    }

    fn selection_key_for_selector(
        &self,
        annotation: Option<&SelectorAnnotation>,
        value: &ResolvedValue,
    ) -> Option<String> {
        let annotation = annotation?;
        annotation.selection_key(&self.locale, value)
    }
}

#[derive(Debug, Clone)]
struct ResolvedValue {
    value: serde_json::Value,
    source: Option<ResolvedFunctionSource>,
}

impl ResolvedValue {
    fn from_json(value: serde_json::Value) -> Self {
        Self {
            value,
            source: None,
        }
    }

    fn string(value: String, source: Option<ResolvedFunctionSource>) -> Self {
        Self {
            value: serde_json::Value::String(value),
            source,
        }
    }

    fn rendered(&self) -> String {
        value_to_string(&self.value)
    }

    fn source_value(&self, fallback: &str) -> String {
        self.source
            .as_ref()
            .map(|source| source.value.clone())
            .unwrap_or_else(|| fallback.to_string())
    }
}

#[derive(Debug, Clone)]
struct ResolvedFunctionSource {
    value: String,
    function: FunctionRef,
    inherited: Option<Box<ResolvedFunctionSource>>,
}

impl ResolvedFunctionSource {
    fn new(
        value: String,
        function: FunctionRef,
        inherited: Option<ResolvedFunctionSource>,
    ) -> Self {
        Self {
            value,
            function,
            inherited: inherited.map(Box::new),
        }
    }
}

#[derive(Debug, Clone)]
struct ExpressionOutput {
    value: String,
    had_error: bool,
    source: Option<ResolvedFunctionSource>,
    bidi_direction: Option<BidiDirection>,
}

#[derive(Debug, Clone)]
struct SelectorValue {
    rendered: String,
    normalized_rendered: Option<String>,
    exact_match: bool,
    selection_key: Option<String>,
    function: Option<FunctionRef>,
    source: Option<ResolvedFunctionSource>,
}

#[derive(Debug, Clone)]
struct SelectorAnnotation {
    function: FunctionRef,
    number_select: NumberSelect,
}

impl SelectorAnnotation {
    fn from_function(function: &crate::model::FunctionRef) -> Self {
        Self {
            function: function.clone(),
            number_select: NumberSelect::from_options(function.options.as_ref()),
        }
    }

    fn exact_match(&self) -> bool {
        self.function.name == "string"
            || (self.is_numeric() && self.number_select == NumberSelect::Exact)
    }

    fn is_string(&self) -> bool {
        self.function.name == "string"
    }

    fn selection_key(&self, locale: &str, value: &ResolvedValue) -> Option<String> {
        if !self.is_numeric() {
            return None;
        }
        let operands = NumberOperands::from_str(&self.operand_for_selection(value)?)?;
        match self.number_select {
            NumberSelect::Plural => {
                Some(select_cardinal_plural_category(locale, operands).to_string())
            }
            NumberSelect::Ordinal => {
                Some(select_ordinal_plural_category(locale, operands).to_string())
            }
            NumberSelect::Exact => None,
        }
    }

    fn is_numeric(&self) -> bool {
        is_numeric_function(&self.function)
    }

    fn operand_for_selection(&self, value: &ResolvedValue) -> Option<String> {
        let rendered = value.rendered();
        if self.function.name == "percent" {
            if let Some(percent) = rendered.strip_suffix('%') {
                return Some(percent.to_string());
            }
            let value = parse_decimal_number(value.source_value(&rendered).as_str()).ok()?;
            return Some((value * 100.0).to_string());
        }
        Some(rendered)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum NumberSelect {
    Plural,
    Ordinal,
    Exact,
}

impl NumberSelect {
    fn from_options(options: Option<&BTreeMap<String, ExpressionArg>>) -> Self {
        let Some(ExpressionArg::Literal { value }) =
            options.and_then(|options| options.get("select"))
        else {
            return Self::Plural;
        };
        match value.as_str() {
            "ordinal" => Self::Ordinal,
            "exact" => Self::Exact,
            _ => Self::Plural,
        }
    }
}

fn validate_variant<'a>(
    variant: &'a Variant,
    selector_values: &[SelectorValue],
    signatures: &mut BTreeSet<Vec<VariantKeySignature>>,
) -> Result<(), Diagnostic> {
    if variant.keys.len() != selector_values.len() {
        return Err(model_error(
            "variant-key-count-mismatch",
            "Variant key count must match selector count.",
        ));
    }
    if !signatures.insert(variant_key_signature(&variant.keys, selector_values)) {
        return Err(model_error(
            "duplicate-variant",
            "Select variants must have unique key tuples.",
        ));
    }
    Ok(())
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
enum VariantKeySignature {
    CatchAll,
    Literal(String),
}

fn variant_key_signature(
    keys: &[VariantKey],
    selector_values: &[SelectorValue],
) -> Vec<VariantKeySignature> {
    keys.iter()
        .zip(selector_values)
        .map(|(key, selector)| match key {
            VariantKey::CatchAll => VariantKeySignature::CatchAll,
            VariantKey::Literal { value } => {
                let value = if selector.normalized_rendered.is_some() {
                    normalize_string_key(value)
                } else {
                    value.clone()
                };
                VariantKeySignature::Literal(value)
            }
        })
        .collect()
}

fn literal_key_matches(value: &str, selector: &SelectorValue) -> bool {
    if let Some(normalized_rendered) = &selector.normalized_rendered {
        normalize_string_key(value) == *normalized_rendered
    } else {
        value == selector.rendered
    }
}

fn normalize_string_key(value: &str) -> String {
    value.nfc().collect()
}

fn is_fallback_variant(variant: &Variant) -> bool {
    variant
        .keys
        .iter()
        .all(|key| matches!(key, VariantKey::CatchAll))
}

fn missing_argument(name: &str) -> Diagnostic {
    Diagnostic::new(
        "missing-argument",
        format!("Missing argument ${name}."),
        0,
        0,
    )
}

fn unresolved_variable(name: &str) -> Diagnostic {
    Diagnostic::new(
        "unresolved-variable",
        format!("Variable ${name} could not be resolved."),
        0,
        0,
    )
}

fn bad_operand(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-operand", message, 0, 0)
}

fn bad_selector(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-selector", message, 0, 0)
}

fn bad_option(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-option", message, 0, 0)
}

fn fallback_error(error: Diagnostic) -> Diagnostic {
    if error.code == "unsupported-function" {
        Diagnostic::new("unknown-function", error.message, error.start, error.end)
    } else {
        error
    }
}

fn model_error(code: impl Into<String>, message: impl Into<String>) -> Diagnostic {
    Diagnostic::new(code, message, 0, 0)
}

fn fallback_source(expression: &Expression) -> String {
    match (&expression.arg, &expression.function) {
        (Some(arg), _) => expression_arg_source(arg),
        (None, Some(function)) => function_source(function),
        (None, None) => String::new(),
    }
}

fn expression_arg_source(arg: &ExpressionArg) -> String {
    match arg {
        ExpressionArg::Literal { value } => quote_literal_source(value),
        ExpressionArg::Variable { name } => format!("${name}"),
    }
}

fn function_source(function: &FunctionRef) -> String {
    let mut source = format!(":{}", function.name);
    if let Some(options) = &function.options {
        for (name, value) in options {
            source.push(' ');
            source.push_str(name);
            source.push('=');
            source.push_str(&expression_arg_source(value));
        }
    }
    source
}

fn quote_literal_source(value: &str) -> String {
    let mut source = String::from("|");
    for ch in value.chars() {
        if ch == '\\' || ch == '|' {
            source.push('\\');
        }
        source.push(ch);
    }
    source.push('|');
    source
}

fn value_to_string(value: &serde_json::Value) -> String {
    match value {
        serde_json::Value::String(value) => value.clone(),
        serde_json::Value::Number(value) => value.to_string(),
        serde_json::Value::Bool(value) => value.to_string(),
        serde_json::Value::Null => String::new(),
        other => other.to_string(),
    }
}

fn markup_to_part(markup: &Markup) -> FormattedPart {
    FormattedPart::Markup {
        kind: markup.kind.clone(),
        name: markup.name.clone(),
        options: markup.options.clone(),
        attributes: markup.attributes.clone(),
    }
}

fn parts_to_string(parts: &[FormattedPart], bidi_isolation: BidiIsolation) -> String {
    let mut output = String::new();
    for part in parts {
        match part {
            FormattedPart::Text { value } => output.push_str(value),
            FormattedPart::Fallback { source } => {
                output.push('{');
                output.push_str(source);
                output.push('}');
            }
            FormattedPart::Expression { value, dir, .. } => {
                push_expression(
                    &mut output,
                    value,
                    bidi_isolation,
                    dir.as_deref().and_then(bidi_direction_from_name),
                );
            }
            FormattedPart::Markup { .. } => {}
        }
    }
    output
}

fn push_expression(
    output: &mut String,
    value: &str,
    bidi_isolation: BidiIsolation,
    direction: Option<BidiDirection>,
) {
    match bidi_isolation {
        BidiIsolation::None => output.push_str(value),
        BidiIsolation::Default => {
            output.push(direction.unwrap_or(BidiDirection::Auto).marker());
            output.push_str(value);
            output.push('\u{2069}');
        }
    }
}

fn bidi_direction_from_name(value: &str) -> Option<BidiDirection> {
    match value {
        "auto" => Some(BidiDirection::Auto),
        "ltr" => Some(BidiDirection::Ltr),
        "rtl" => Some(BidiDirection::Rtl),
        _ => None,
    }
}
