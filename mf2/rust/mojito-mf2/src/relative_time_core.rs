use std::collections::BTreeMap;
use std::sync::Arc;

use serde::Deserialize;

use crate::cldr_plural_rules::{select_cardinal, NumberOperands};
use crate::diagnostic::Diagnostic;
use crate::formatter::{ArgumentValue, FormattedPart, FunctionCall, FunctionRegistry};
use crate::locale_key::locale_lookup_chain;

const DEFAULT_LOCALE: &str = "en";
const MAX_LOCALE_LENGTH: usize = 256;
const MAX_OPTION_LENGTH: usize = 256;
const MAX_OPERAND_LENGTH: usize = 256;
const MAX_RELATIVE_TIME_QUANTITY: f64 = 1_000_000_000.0;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RelativeTimeCoreStyle {
    Long,
    Short,
    Narrow,
}

impl RelativeTimeCoreStyle {
    pub fn from_name(value: &str) -> Result<Self, Diagnostic> {
        match option_name(value, "style")? {
            "long" => Ok(Self::Long),
            "short" => Ok(Self::Short),
            "narrow" => Ok(Self::Narrow),
            _ => Err(bad_option("style must be one of long, short, narrow.")),
        }
    }

    fn key(self) -> &'static str {
        match self {
            Self::Long => "long",
            Self::Short => "short",
            Self::Narrow => "narrow",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RelativeTimeCoreNumeric {
    Always,
    Auto,
}

impl RelativeTimeCoreNumeric {
    pub fn from_name(value: &str) -> Result<Self, Diagnostic> {
        match option_name(value, "numeric")? {
            "always" => Ok(Self::Always),
            "auto" => Ok(Self::Auto),
            _ => Err(bad_option("numeric must be one of always, auto.")),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RelativeTimeCorePolicy {
    Precise,
    Compact,
    Chat,
}

impl RelativeTimeCorePolicy {
    pub fn from_name(value: &str) -> Result<Self, Diagnostic> {
        match option_name(value, "policy")? {
            "precise" => Ok(Self::Precise),
            "compact" => Ok(Self::Compact),
            "chat" => Ok(Self::Chat),
            _ => Err(bad_option("policy must be one of precise, compact, chat.")),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RelativeTimeCoreUnit {
    Auto,
    Second,
    Minute,
    Hour,
    Day,
    Week,
    Month,
    Quarter,
    Year,
}

impl RelativeTimeCoreUnit {
    pub fn from_name(value: &str) -> Result<Self, Diagnostic> {
        match option_name(value, "unit")? {
            "auto" => Ok(Self::Auto),
            "second" => Ok(Self::Second),
            "minute" => Ok(Self::Minute),
            "hour" => Ok(Self::Hour),
            "day" => Ok(Self::Day),
            "week" => Ok(Self::Week),
            "month" => Ok(Self::Month),
            "quarter" => Ok(Self::Quarter),
            "year" => Ok(Self::Year),
            _ => Err(bad_option(
                "unit must be one of auto, second, minute, hour, day, week, month, quarter, year.",
            )),
        }
    }

    fn key(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::Second => "second",
            Self::Minute => "minute",
            Self::Hour => "hour",
            Self::Day => "day",
            Self::Week => "week",
            Self::Month => "month",
            Self::Quarter => "quarter",
            Self::Year => "year",
        }
    }

    fn seconds(self) -> f64 {
        match self {
            Self::Auto => 1.0,
            Self::Second => 1.0,
            Self::Minute => 60.0,
            Self::Hour => 3_600.0,
            Self::Day => 86_400.0,
            Self::Week => 604_800.0,
            Self::Month => 2_592_000.0,
            Self::Quarter => 7_776_000.0,
            Self::Year => 31_536_000.0,
        }
    }
}

#[derive(Debug, Clone)]
pub struct RelativeTimeCoreOptions {
    pub locale: String,
    pub style: RelativeTimeCoreStyle,
    pub numeric: RelativeTimeCoreNumeric,
    pub policy: RelativeTimeCorePolicy,
    pub unit: RelativeTimeCoreUnit,
}

impl Default for RelativeTimeCoreOptions {
    fn default() -> Self {
        Self {
            locale: DEFAULT_LOCALE.to_string(),
            style: RelativeTimeCoreStyle::Short,
            numeric: RelativeTimeCoreNumeric::Always,
            policy: RelativeTimeCorePolicy::Precise,
            unit: RelativeTimeCoreUnit::Auto,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct RelativeTimeCoreData {
    #[serde(rename = "localeMap")]
    pub locale_map: BTreeMap<String, String>,
    #[serde(rename = "patternSets")]
    pub pattern_sets: Vec<RelativeTimeCorePatternSet>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct RelativeTimeCorePatternSet {
    pub id: String,
    pub data: BTreeMap<String, BTreeMap<String, RelativeTimeCoreUnitData>>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct RelativeTimeCoreUnitData {
    #[serde(default)]
    pub future: BTreeMap<String, String>,
    #[serde(default)]
    pub past: BTreeMap<String, String>,
    #[serde(default)]
    pub relative: BTreeMap<String, String>,
}

#[derive(Debug, Clone)]
pub struct RelativeTimeCoreFormatter {
    locale_map: BTreeMap<String, String>,
    pattern_sets: BTreeMap<String, RelativeTimeCorePatternSet>,
}

impl RelativeTimeCoreFormatter {
    pub fn new(data: RelativeTimeCoreData) -> Result<Self, Diagnostic> {
        if data.locale_map.is_empty() || data.pattern_sets.is_empty() {
            return Err(missing_locale_data(
                "Relative-time core data has an unsupported shape.",
            ));
        }
        let pattern_sets: BTreeMap<_, _> = data
            .pattern_sets
            .into_iter()
            .filter(|pattern_set| !pattern_set.id.is_empty() && !pattern_set.data.is_empty())
            .map(|pattern_set| (pattern_set.id.clone(), pattern_set))
            .collect();
        if pattern_sets.is_empty() {
            return Err(missing_locale_data(
                "Relative-time core data has an unsupported shape.",
            ));
        }
        Ok(Self {
            locale_map: data.locale_map,
            pattern_sets,
        })
    }

    pub fn format(
        &self,
        value: impl Into<ArgumentValue>,
        options: &RelativeTimeCoreOptions,
    ) -> Result<String, Diagnostic> {
        let seconds = parse_finite_number(&value.into())?;
        let unit = if options.unit == RelativeTimeCoreUnit::Auto {
            select_unit(seconds, options.policy)
        } else {
            options.unit
        };
        let quantity = quantity(seconds, unit)?;
        let locale = locale_option(&options.locale)?;

        if use_relative_zero(options.policy, options.numeric, seconds) {
            if let Some(relative) = self.relative_term(locale, options.style, unit, "0")? {
                return Ok(relative.to_string());
            }
        }
        if options.numeric == RelativeTimeCoreNumeric::Auto {
            if let Some(offset) = relative_offset(seconds, unit, quantity) {
                if let Some(relative) = self.relative_term(locale, options.style, unit, &offset)? {
                    return Ok(relative.to_string());
                }
            }
        }

        let direction = if is_negative_relative_time(seconds) {
            "past"
        } else {
            "future"
        };
        let category = select_cardinal(
            locale,
            NumberOperands::from_str(&quantity.to_string())
                .expect("integer quantity is plural operand"),
        );
        let pattern =
            self.relative_time_pattern(locale, options.style, unit, direction, category)?;
        Ok(pattern.replace("{0}", &quantity.to_string()))
    }

    pub fn format_to_parts(
        &self,
        value: impl Into<ArgumentValue>,
        options: &RelativeTimeCoreOptions,
    ) -> Result<Vec<FormattedPart>, Diagnostic> {
        Ok(vec![FormattedPart::Text {
            value: self.format(value, options)?,
        }])
    }

    pub fn into_function_registry(self) -> FunctionRegistry {
        let formatter = Arc::new(self);
        FunctionRegistry::portable()
            .with_function("relativeTime", move |call| formatter.format_call(call))
    }

    fn format_call(&self, call: FunctionCall<'_>) -> Result<String, Diagnostic> {
        let mut options = RelativeTimeCoreOptions {
            locale: call.locale().to_string(),
            ..RelativeTimeCoreOptions::default()
        };
        if let Some(value) = call.option_value("style")? {
            options.style = RelativeTimeCoreStyle::from_name(&value)?;
        }
        if let Some(value) = call.option_value("numeric")? {
            options.numeric = RelativeTimeCoreNumeric::from_name(&value)?;
        }
        if let Some(value) = call.option_value("policy")? {
            options.policy = RelativeTimeCorePolicy::from_name(&value)?;
        }
        if let Some(value) = call.option_value("unit")? {
            options.unit = RelativeTimeCoreUnit::from_name(&value)?;
        }
        self.format(call.raw_value().clone(), &options)
    }

    fn relative_term(
        &self,
        locale: &str,
        style: RelativeTimeCoreStyle,
        unit: RelativeTimeCoreUnit,
        offset: &str,
    ) -> Result<Option<&str>, Diagnostic> {
        Ok(self
            .unit_data(locale, style, unit)?
            .relative
            .get(offset)
            .map(String::as_str))
    }

    fn relative_time_pattern(
        &self,
        locale: &str,
        style: RelativeTimeCoreStyle,
        unit: RelativeTimeCoreUnit,
        direction: &str,
        category: &str,
    ) -> Result<&str, Diagnostic> {
        let unit_data = self.unit_data(locale, style, unit)?;
        let patterns = if direction == "past" {
            &unit_data.past
        } else {
            &unit_data.future
        };
        patterns
            .get(category)
            .or_else(|| patterns.get("other"))
            .map(String::as_str)
            .ok_or_else(|| {
                missing_locale_data(format!(
                    "Missing relative-time pattern for {locale}/{}/{}/{direction}.",
                    style.key(),
                    unit.key()
                ))
            })
    }

    fn unit_data(
        &self,
        locale: &str,
        style: RelativeTimeCoreStyle,
        unit: RelativeTimeCoreUnit,
    ) -> Result<&RelativeTimeCoreUnitData, Diagnostic> {
        let pattern_set = self.pattern_set_for(locale)?;
        pattern_set
            .data
            .get(style.key())
            .and_then(|style_data| style_data.get(unit.key()))
            .ok_or_else(|| {
                missing_locale_data(format!(
                    "Missing relative-time unit data for {locale}/{}/{}.",
                    style.key(),
                    unit.key()
                ))
            })
    }

    fn pattern_set_for(&self, locale: &str) -> Result<&RelativeTimeCorePatternSet, Diagnostic> {
        for candidate in locale_lookup_chain(locale) {
            let Some(set_id) = self.locale_map.get(&candidate) else {
                continue;
            };
            return self.pattern_sets.get(set_id).ok_or_else(|| {
                missing_locale_data(format!("Missing relative-time pattern set {set_id}."))
            });
        }
        Err(missing_locale_data(format!(
            "Missing relative-time locale data for {locale}."
        )))
    }
}

pub fn relative_time_core_function_registry(
    data: RelativeTimeCoreData,
) -> Result<FunctionRegistry, Diagnostic> {
    Ok(RelativeTimeCoreFormatter::new(data)?.into_function_registry())
}

pub fn format_relative_time_core(
    value: impl Into<ArgumentValue>,
    data: &RelativeTimeCoreData,
    options: &RelativeTimeCoreOptions,
) -> Result<String, Diagnostic> {
    RelativeTimeCoreFormatter::new(data.clone())?.format(value, options)
}

pub fn format_relative_time_core_to_parts(
    value: impl Into<ArgumentValue>,
    data: &RelativeTimeCoreData,
    options: &RelativeTimeCoreOptions,
) -> Result<Vec<FormattedPart>, Diagnostic> {
    RelativeTimeCoreFormatter::new(data.clone())?.format_to_parts(value, options)
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

fn option_name<'a>(value: &'a str, name: &str) -> Result<&'a str, Diagnostic> {
    if value.chars().count() > MAX_OPTION_LENGTH {
        return Err(bad_option(format!(
            "{name} must not exceed 256 characters."
        )));
    }
    Ok(value)
}

fn parse_finite_number(value: &ArgumentValue) -> Result<f64, Diagnostic> {
    let text = match value {
        ArgumentValue::Number(value) | ArgumentValue::String(value) => value.trim(),
        ArgumentValue::Bool(_) | ArgumentValue::Null => {
            return Err(bad_operand(
                "Relative-time core requires a finite numeric value.",
            ));
        }
    };
    if text.is_empty() {
        return Err(bad_operand(
            "Relative-time core requires a finite numeric value.",
        ));
    }
    if text.chars().count() > MAX_OPERAND_LENGTH {
        return Err(bad_operand(
            "Relative-time core requires a finite numeric value.",
        ));
    }
    if !is_decimal_number(text) {
        return Err(bad_operand(
            "Relative-time core requires a finite numeric value.",
        ));
    }
    let parsed = text
        .parse::<f64>()
        .map_err(|_| bad_operand("Relative-time core requires a finite numeric value."))?;
    if parsed.is_finite() {
        Ok(parsed)
    } else {
        Err(bad_operand(
            "Relative-time core requires a finite numeric value.",
        ))
    }
}

fn is_decimal_number(text: &str) -> bool {
    let bytes = text.as_bytes();
    let mut index = 0;
    if matches!(bytes.first(), Some(b'-')) {
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

    if index < bytes.len() && bytes[index] == b'.' {
        index += 1;
        let fraction_start = index;
        while index < bytes.len() && bytes[index].is_ascii_digit() {
            index += 1;
        }
        if index == fraction_start {
            return false;
        }
    }

    if index < bytes.len() && matches!(bytes[index], b'e' | b'E') {
        index += 1;
        if index < bytes.len() && matches!(bytes[index], b'+' | b'-') {
            index += 1;
        }
        let exponent_start = index;
        while index < bytes.len() && bytes[index].is_ascii_digit() {
            index += 1;
        }
        if index == exponent_start {
            return false;
        }
    }

    index == bytes.len()
}

fn select_unit(seconds: f64, policy: RelativeTimeCorePolicy) -> RelativeTimeCoreUnit {
    let absolute = seconds.abs();
    let steps: &[(f64, RelativeTimeCoreUnit)] = match policy {
        RelativeTimeCorePolicy::Precise => &[
            (60.0, RelativeTimeCoreUnit::Second),
            (3_600.0, RelativeTimeCoreUnit::Minute),
            (86_400.0, RelativeTimeCoreUnit::Hour),
            (604_800.0, RelativeTimeCoreUnit::Day),
            (2_592_000.0, RelativeTimeCoreUnit::Week),
            (31_536_000.0, RelativeTimeCoreUnit::Month),
            (f64::INFINITY, RelativeTimeCoreUnit::Year),
        ],
        RelativeTimeCorePolicy::Compact => &[
            (60.0, RelativeTimeCoreUnit::Second),
            (3_600.0, RelativeTimeCoreUnit::Minute),
            (86_400.0, RelativeTimeCoreUnit::Hour),
            (f64::INFINITY, RelativeTimeCoreUnit::Day),
        ],
        RelativeTimeCorePolicy::Chat => &[
            (45.0, RelativeTimeCoreUnit::Second),
            (2_700.0, RelativeTimeCoreUnit::Minute),
            (79_200.0, RelativeTimeCoreUnit::Hour),
            (604_800.0, RelativeTimeCoreUnit::Day),
            (f64::INFINITY, RelativeTimeCoreUnit::Week),
        ],
    };
    steps
        .iter()
        .find(|(upper, _)| absolute < *upper)
        .map(|(_, unit)| *unit)
        .unwrap_or(RelativeTimeCoreUnit::Year)
}

fn quantity(seconds: f64, unit: RelativeTimeCoreUnit) -> Result<i64, Diagnostic> {
    let absolute = seconds.abs();
    if absolute == 0.0 {
        return Ok(0);
    }
    let quantity = ((absolute / unit.seconds()) + 0.5).floor().max(1.0);
    if quantity > MAX_RELATIVE_TIME_QUANTITY {
        return Err(bad_operand(
            "Relative-time core quantity is outside the supported range.",
        ));
    }
    Ok(quantity as i64)
}

fn use_relative_zero(
    policy: RelativeTimeCorePolicy,
    numeric: RelativeTimeCoreNumeric,
    seconds: f64,
) -> bool {
    policy == RelativeTimeCorePolicy::Chat
        && numeric == RelativeTimeCoreNumeric::Auto
        && seconds.abs() < 45.0
}

fn relative_offset(seconds: f64, unit: RelativeTimeCoreUnit, quantity: i64) -> Option<String> {
    if quantity == 0 {
        return Some("0".to_string());
    }
    if seconds.abs() != quantity as f64 * unit.seconds() {
        return None;
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

fn bad_operand(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-operand", message.into(), 0, 0)
}

fn bad_option(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-option", message.into(), 0, 0)
}

fn missing_locale_data(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("missing-locale-data", message.into(), 0, 0)
}
