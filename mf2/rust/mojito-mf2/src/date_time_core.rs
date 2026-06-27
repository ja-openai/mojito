use crate::cldr_date_time_data::{
    CldrDateTimeLocaleData, CldrDateTimeNestedMap2, CldrDateTimeNestedMap3, CldrDateTimeStringMap,
    CLDR_DATE_TIME_LOCALES,
};
use crate::diagnostic::Diagnostic;
use crate::formatter::{FormattedPart, FunctionCall, FunctionRegistry};
use crate::locale_key::locale_lookup_chain;

const DEFAULT_LOCALE: &str = "en-US";
const UTC: &str = "UTC";
const MAX_LOCALE_LENGTH: usize = 256;
const MAX_OPTION_LENGTH: usize = 256;
const MAX_OPERAND_LENGTH: usize = 256;
const MAX_SKELETON_FIELD_WIDTH: usize = 32;
const MAX_SKELETON_LENGTH: usize = 256;
const SEMANTIC_SKELETON_PREFIX: &str = "semantic:";
const SEMANTIC_FIELD_ORDER: [&str; 19] = [
    "era",
    "year",
    "quarter",
    "month",
    "weekofmonth",
    "day",
    "dayofyear",
    "dayofweekinmonth",
    "modifiedjulianday",
    "weekday",
    "weekofyear",
    "dayperiod",
    "hour",
    "minute",
    "second",
    "fractionalsecond",
    "millisecondsinday",
    "time",
    "zone",
];
const SEMANTIC_DATE_FIELD_ORDER: [&str; 11] = [
    "era",
    "year",
    "quarter",
    "month",
    "weekofmonth",
    "day",
    "dayofyear",
    "dayofweekinmonth",
    "modifiedjulianday",
    "weekday",
    "weekofyear",
];
const SEMANTIC_TIME_FIELD_ORDER: [&str; 5] = [
    "hour",
    "minute",
    "second",
    "fractionalsecond",
    "millisecondsinday",
];
const SEMANTIC_OPTION_KEYS: [&str; 18] = [
    "fields",
    "length",
    "alignment",
    "yearstyle",
    "erastyle",
    "monthstyle",
    "quarterstyle",
    "daystyle",
    "weekdaystyle",
    "dayperiodstyle",
    "hourstyle",
    "minutestyle",
    "secondstyle",
    "timeprecision",
    "timestyle",
    "fractionalsecond",
    "hourcycle",
    "zonestyle",
];
const SEMANTIC_DIRECT_STYLE_OPTION_KEYS: [&str; 3] = ["fields", "length", "timestyle"];
const SEMANTIC_STYLE_OPTION_KEYS: [&str; 10] = [
    "yearstyle",
    "erastyle",
    "monthstyle",
    "quarterstyle",
    "daystyle",
    "weekdaystyle",
    "dayperiodstyle",
    "hourstyle",
    "minutestyle",
    "secondstyle",
];
const SEMANTIC_DATE_STYLE_VALUES: [&str; 6] =
    ["auto", "numeric", "2-digit", "short", "long", "narrow"];
const SEMANTIC_NUMERIC_STYLE_VALUES: [&str; 3] = ["auto", "numeric", "2-digit"];
const SEMANTIC_TEXT_STYLE_VALUES: [&str; 4] = ["auto", "short", "long", "narrow"];
const SEMANTIC_DATE_FIELD_SETS: [&str; 9] = [
    "day",
    "weekday",
    "day,weekday",
    "month,day",
    "month,day,weekday",
    "era,year,month,day",
    "era,year,month,day,weekday",
    "year,month,day",
    "year,month,day,weekday",
];
const SEMANTIC_CALENDAR_PERIOD_FIELD_SETS: [&str; 17] = [
    "era",
    "year",
    "quarter",
    "month",
    "era,year",
    "era,year,quarter",
    "era,year,month",
    "era,year,weekofyear",
    "era,year,month,weekofmonth",
    "year,quarter",
    "year,month",
    "year,weekofyear",
    "month,weekofmonth",
    "year,month,weekofmonth",
    "dayofyear",
    "dayofweekinmonth",
    "modifiedjulianday",
];
const SEMANTIC_TIME_FIELD_SETS: [&str; 10] = [
    "hour",
    "minute",
    "second",
    "millisecondsinday",
    "hour,minute",
    "hour,minute,second",
    "hour,minute,second,fractionalsecond",
    "minute,second",
    "minute,second,fractionalsecond",
    "second,fractionalsecond",
];
const SKELETON_FIELD_ORDER: &str = "GyYuUrQqMLlwWdDFgEecabBhHkKJmsSAzZOvVXx";
const SKELETON_TIME_FIELDS: &str = "abBhHkKJmsSAzZOvVXx";
const SKELETON_HOUR_FIELDS: &str = "hHkK";
const WEEKDAY_KEYS: [&str; 7] = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DateTimeCoreStyle {
    Full,
    Long,
    Medium,
    Short,
}

impl DateTimeCoreStyle {
    fn from_name(value: &str, name: &str) -> Result<Self, Diagnostic> {
        if value.chars().count() > MAX_OPTION_LENGTH {
            return Err(bad_option(format!(
                "{name} must not exceed 256 characters."
            )));
        }
        match value {
            "full" => Ok(Self::Full),
            "long" => Ok(Self::Long),
            "medium" => Ok(Self::Medium),
            "short" => Ok(Self::Short),
            _ => Err(bad_option(format!(
                "{name} must be one of full, long, medium, short."
            ))),
        }
    }

    fn key(self) -> &'static str {
        match self {
            Self::Full => "full",
            Self::Long => "long",
            Self::Medium => "medium",
            Self::Short => "short",
        }
    }
}

#[derive(Debug, Clone)]
pub struct DateTimeCoreOptions {
    pub locale: String,
    pub style: String,
    pub date_style: Option<String>,
    pub time_style: Option<String>,
    pub length: Option<String>,
    pub precision: Option<String>,
    pub date_length: Option<String>,
    pub time_precision: Option<String>,
    pub skeleton: Option<String>,
    pub hour_cycle: Option<String>,
    pub time_zone: String,
    pub calendar: String,
}

impl Default for DateTimeCoreOptions {
    fn default() -> Self {
        Self {
            locale: DEFAULT_LOCALE.to_string(),
            style: "medium".to_string(),
            date_style: None,
            time_style: None,
            length: None,
            precision: None,
            date_length: None,
            time_precision: None,
            skeleton: None,
            hour_cycle: None,
            time_zone: UTC.to_string(),
            calendar: String::new(),
        }
    }
}

pub fn date_time_core_function_registry() -> FunctionRegistry {
    FunctionRegistry::portable()
        .with_function("date", format_call_date)
        .with_function("time", format_call_time)
        .with_function("datetime", format_call_date_time)
}

pub fn format_date_core(
    value: impl AsRef<str>,
    options: &DateTimeCoreOptions,
) -> Result<String, Diagnostic> {
    let locale = locale_option(&options.locale)?;
    let locale_calendar = locale_unicode_extension(locale, "ca");
    validate_options(options, locale_calendar.as_deref())?;
    let locale_data = resolve_numbering_system_data(*resolve_locale_data(locale), locale)?;
    let locale_hour_cycle = locale_unicode_extension(locale, "hc");
    let preserve_same_family_hour_cycle = options
        .hour_cycle
        .as_deref()
        .filter(|value| !value.is_empty())
        .is_some();
    let hour_cycle = validate_hour_cycle(
        options
            .hour_cycle
            .as_deref()
            .filter(|value| !value.is_empty())
            .or(locale_hour_cycle.as_deref()),
    )?;
    let date = apply_time_zone(
        parse_datetime(value.as_ref())?,
        parse_time_zone(&options.time_zone)?,
    );
    if let Some(skeleton) = options.skeleton.as_deref() {
        return format_skeleton(
            skeleton,
            date,
            &locale_data,
            hour_cycle,
            preserve_same_family_hour_cycle,
        );
    }
    let style = DateTimeCoreStyle::from_name(
        options
            .date_style
            .as_deref()
            .or(options.length.as_deref())
            .unwrap_or(&options.style),
        "dateStyle",
    )?;
    format_pattern(
        string_map_get(locale_data.date_formats, style.key()).unwrap_or(""),
        date,
        &locale_data,
    )
}

pub fn format_time_core(
    value: impl AsRef<str>,
    options: &DateTimeCoreOptions,
) -> Result<String, Diagnostic> {
    let locale = locale_option(&options.locale)?;
    let locale_calendar = locale_unicode_extension(locale, "ca");
    validate_options(options, locale_calendar.as_deref())?;
    let locale_data = resolve_numbering_system_data(*resolve_locale_data(locale), locale)?;
    let locale_hour_cycle = locale_unicode_extension(locale, "hc");
    let preserve_same_family_hour_cycle = options
        .hour_cycle
        .as_deref()
        .filter(|value| !value.is_empty())
        .is_some();
    let hour_cycle = validate_hour_cycle(
        options
            .hour_cycle
            .as_deref()
            .filter(|value| !value.is_empty())
            .or(locale_hour_cycle.as_deref()),
    )?;
    let date = apply_time_zone(
        parse_datetime(value.as_ref())?,
        parse_time_zone(&options.time_zone)?,
    );
    if let Some(skeleton) = options.skeleton.as_deref() {
        return format_skeleton(
            skeleton,
            date,
            &locale_data,
            hour_cycle,
            preserve_same_family_hour_cycle,
        );
    }
    let style = time_style_option(
        options.time_style.as_deref(),
        options.time_precision.as_deref(),
        options.precision.as_deref(),
        &options.style,
        "medium",
    )?;
    format_time_style_pattern(
        string_map_get(locale_data.time_formats, style.key()).unwrap_or(""),
        date,
        &locale_data,
        hour_cycle,
        preserve_same_family_hour_cycle,
    )
}

pub fn format_date_time_core(
    value: impl AsRef<str>,
    options: &DateTimeCoreOptions,
) -> Result<String, Diagnostic> {
    let locale = locale_option(&options.locale)?;
    let locale_calendar = locale_unicode_extension(locale, "ca");
    validate_options(options, locale_calendar.as_deref())?;
    let locale_data = resolve_numbering_system_data(*resolve_locale_data(locale), locale)?;
    let locale_hour_cycle = locale_unicode_extension(locale, "hc");
    let preserve_same_family_hour_cycle = options
        .hour_cycle
        .as_deref()
        .filter(|value| !value.is_empty())
        .is_some();
    let hour_cycle = validate_hour_cycle(
        options
            .hour_cycle
            .as_deref()
            .filter(|value| !value.is_empty())
            .or(locale_hour_cycle.as_deref()),
    )?;
    let date = apply_time_zone(
        parse_datetime(value.as_ref())?,
        parse_time_zone(&options.time_zone)?,
    );
    if let Some(skeleton) = options.skeleton.as_deref() {
        return format_skeleton(
            skeleton,
            date,
            &locale_data,
            hour_cycle,
            preserve_same_family_hour_cycle,
        );
    }
    let date_style = DateTimeCoreStyle::from_name(
        options
            .date_style
            .as_deref()
            .or(options.date_length.as_deref())
            .or(options.length.as_deref())
            .unwrap_or(&options.style),
        "dateStyle",
    )?;
    let time_style = time_style_option(
        options.time_style.as_deref(),
        options.time_precision.as_deref(),
        options.precision.as_deref(),
        &options.style,
        "medium",
    )?;
    let date_part = format_pattern(
        string_map_get(locale_data.date_formats, date_style.key()).unwrap_or(""),
        date,
        &locale_data,
    )?;
    let time_part = format_time_style_pattern(
        string_map_get(locale_data.time_formats, time_style.key()).unwrap_or(""),
        date,
        &locale_data,
        hour_cycle,
        preserve_same_family_hour_cycle,
    )?;
    Ok(date_time_style_join_pattern(&locale_data, date_style.key())
        .replace("{1}", &date_part)
        .replace("{0}", &time_part))
}

pub fn format_date_core_to_parts(
    value: impl AsRef<str>,
    options: &DateTimeCoreOptions,
) -> Result<Vec<FormattedPart>, Diagnostic> {
    Ok(vec![FormattedPart::Text {
        value: format_date_core(value, options)?,
    }])
}

pub fn format_time_core_to_parts(
    value: impl AsRef<str>,
    options: &DateTimeCoreOptions,
) -> Result<Vec<FormattedPart>, Diagnostic> {
    Ok(vec![FormattedPart::Text {
        value: format_time_core(value, options)?,
    }])
}

pub fn format_date_time_core_to_parts(
    value: impl AsRef<str>,
    options: &DateTimeCoreOptions,
) -> Result<Vec<FormattedPart>, Diagnostic> {
    Ok(vec![FormattedPart::Text {
        value: format_date_time_core(value, options)?,
    }])
}

fn format_call_date(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let mut options = DateTimeCoreOptions {
        locale: call.locale().to_string(),
        date_style: Some(call_style(&call, "dateStyle", "length", "medium", false)?),
        ..DateTimeCoreOptions::default()
    };
    options.time_zone =
        call_non_empty_option(&call, "timeZone")?.unwrap_or_else(|| UTC.to_string());
    options.calendar = call_non_empty_option(&call, "calendar")?.unwrap_or_default();
    options.skeleton = call_non_empty_option(&call, "skeleton")?;
    options.hour_cycle = call_non_empty_option(&call, "hourCycle")?;
    format_date_core(call_source_value(&call), &options)
}

fn format_call_time(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let mut options = DateTimeCoreOptions {
        locale: call.locale().to_string(),
        time_style: Some(call_style(&call, "timeStyle", "precision", "medium", true)?),
        ..DateTimeCoreOptions::default()
    };
    options.time_zone =
        call_non_empty_option(&call, "timeZone")?.unwrap_or_else(|| UTC.to_string());
    options.calendar = call_non_empty_option(&call, "calendar")?.unwrap_or_default();
    options.skeleton = call_non_empty_option(&call, "skeleton")?;
    options.hour_cycle = call_non_empty_option(&call, "hourCycle")?;
    format_time_core(call_source_value(&call), &options)
}

fn format_call_date_time(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let mut options = DateTimeCoreOptions {
        locale: call.locale().to_string(),
        date_style: Some(call_style(
            &call,
            "dateStyle",
            "dateLength",
            "medium",
            false,
        )?),
        time_style: Some(call_style(
            &call,
            "timeStyle",
            "timePrecision",
            "medium",
            true,
        )?),
        ..DateTimeCoreOptions::default()
    };
    options.time_zone =
        call_non_empty_option(&call, "timeZone")?.unwrap_or_else(|| UTC.to_string());
    options.calendar = call_non_empty_option(&call, "calendar")?.unwrap_or_default();
    options.skeleton = call_non_empty_option(&call, "skeleton")?;
    options.hour_cycle = call_non_empty_option(&call, "hourCycle")?;
    format_date_time_core(call_source_value(&call), &options)
}

fn resolve_locale_data(locale: &str) -> &'static CldrDateTimeLocaleData {
    for candidate in locale_lookup_chain(if locale.is_empty() {
        DEFAULT_LOCALE
    } else {
        locale
    }) {
        if let Some((_, data)) = CLDR_DATE_TIME_LOCALES
            .iter()
            .find(|(key, _)| *key == candidate)
        {
            return data;
        }
        if let Some((_, data)) = CLDR_DATE_TIME_LOCALES.iter().find(|(_, data)| {
            data.source_locale == candidate || data.numbers_source_locale == candidate
        }) {
            return data;
        }
    }
    &CLDR_DATE_TIME_LOCALES
        .iter()
        .find(|(key, _)| *key == DEFAULT_LOCALE)
        .expect("default date/time locale is generated")
        .1
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

fn locale_unicode_extension(locale: &str, key: &str) -> Option<String> {
    let parts: Vec<String> = locale
        .trim()
        .replace('_', "-")
        .split('-')
        .filter(|part| !part.is_empty())
        .map(|part| part.to_ascii_lowercase())
        .collect();
    let mut index = parts.iter().position(|part| part == "u")? + 1;
    while index < parts.len() {
        let part = &parts[index];
        if part.len() == 1 {
            return None;
        }
        if part.len() != 2 {
            index += 1;
            continue;
        }
        let mut end = index + 1;
        while end < parts.len() && parts[end].len() > 2 {
            end += 1;
        }
        if part == key {
            return (end > index + 1).then(|| parts[index + 1].clone());
        }
        index = end;
    }
    None
}

fn resolve_numbering_system_data(
    mut locale_data: CldrDateTimeLocaleData,
    locale: &str,
) -> Result<CldrDateTimeLocaleData, Diagnostic> {
    let Some(numbering_system) = locale_unicode_extension(locale, "nu") else {
        return Ok(locale_data);
    };
    if numbering_system.is_empty() {
        return Ok(locale_data);
    }
    let Some(digits) = numbering_system_digits(&numbering_system) else {
        return Err(bad_option(
            "Date/time core does not include data for the requested numbering system.",
        ));
    };
    locale_data.numbering_system_digits = Some(digits);
    Ok(locale_data)
}

fn numbering_system_digits(numbering_system: &str) -> Option<&'static str> {
    if numbering_system == "latn" {
        return Some("0123456789");
    }
    CLDR_DATE_TIME_LOCALES
        .iter()
        .find(|(_, data)| data.numbering_system == numbering_system)
        .and_then(|(_, data)| data.numbering_system_digits)
}

fn validate_options(
    options: &DateTimeCoreOptions,
    locale_calendar: Option<&str>,
) -> Result<(), Diagnostic> {
    let calendar = if options.calendar.is_empty() {
        locale_calendar.unwrap_or("")
    } else {
        &options.calendar
    };
    if calendar.chars().count() > MAX_OPTION_LENGTH {
        return Err(bad_option("calendar must not exceed 256 characters."));
    }
    if !calendar.is_empty() && calendar != "gregorian" && calendar != "gregory" {
        return Err(bad_option(
            "Date/time core currently supports only the gregorian/gregory calendar.",
        ));
    }
    Ok(())
}

fn validate_hour_cycle(value: Option<&str>) -> Result<Option<&str>, Diagnostic> {
    match value {
        None | Some("") => Ok(None),
        Some(value) if value.chars().count() > MAX_OPTION_LENGTH => {
            Err(bad_option("hourCycle must not exceed 256 characters."))
        }
        Some("h11" | "h12" | "h23" | "h24") => Ok(value),
        Some(_) => Err(bad_option("hourCycle must be one of h11, h12, h23, h24.")),
    }
}

fn parse_time_zone(value: &str) -> Result<i32, Diagnostic> {
    if !value.is_empty() && value.chars().count() > MAX_OPTION_LENGTH {
        return Err(bad_option("timeZone must not exceed 256 characters."));
    }
    let text = if value.trim().is_empty() {
        UTC
    } else {
        value.trim()
    };
    if text == UTC || text == "Etc/UTC" || text == "Z" || text == "GMT" || text == "Etc/GMT" {
        return Ok(0);
    }
    if let Some(offset_minutes) = parse_etc_gmt_offset_minutes(text) {
        return Ok(offset_minutes);
    }
    let offset_text = if (text.starts_with("UTC") || text.starts_with("GMT")) && text.len() > 3 {
        &text[3..]
    } else {
        text
    };
    parse_offset_minutes(offset_text)
        .ok_or_else(|| bad_option("Date/time core supports only UTC or fixed-offset time zones."))
}

fn parse_etc_gmt_offset_minutes(value: &str) -> Option<i32> {
    let prefix = "Etc/GMT";
    if !value.starts_with(prefix) || value.len() <= prefix.len() {
        return None;
    }
    let sign = value.as_bytes()[prefix.len()] as char;
    if sign != '+' && sign != '-' {
        return None;
    }
    let hour_text = &value[prefix.len() + 1..];
    if hour_text.is_empty() || hour_text.len() > 2 {
        return None;
    }
    let hours = hour_text.parse::<i32>().ok()?;
    if hours > 14 {
        return None;
    }
    let offset = hours * 60;
    Some(if sign == '+' { -offset } else { offset })
}

fn parse_offset_minutes(value: &str) -> Option<i32> {
    if value.len() < 2 {
        return None;
    }
    let sign = value.as_bytes()[0] as char;
    if sign != '+' && sign != '-' {
        return None;
    }
    let body = &value[1..];
    let (hour_text, minute_text) = if let Some((hour, minute)) = body.split_once(':') {
        (hour, minute)
    } else if body.len() > 2 {
        body.split_at(body.len() - 2)
    } else {
        (body, "00")
    };
    if hour_text.is_empty() || hour_text.len() > 2 || minute_text.len() != 2 {
        return None;
    }
    let hours = hour_text.parse::<i32>().ok()?;
    let minutes = minute_text.parse::<i32>().ok()?;
    if hours > 18 || minutes > 59 || (hours == 18 && minutes != 0) {
        return None;
    }
    let total = hours * 60 + minutes;
    Some(if sign == '-' { -total } else { total })
}

#[derive(Clone, Copy)]
struct DateTimeValue {
    year: i32,
    month: u8,
    day: u8,
    hour: u8,
    minute: u8,
    second: u8,
    millisecond: u16,
    offset_minutes: i32,
}

fn parse_datetime(value: &str) -> Result<DateTimeValue, Diagnostic> {
    let text = value.trim();
    if text.chars().count() > MAX_OPERAND_LENGTH {
        return Err(bad_operand(
            "Date/time core requires a valid host date/time value or ISO date string.",
        ));
    }
    let (text, source_offset_minutes) = split_datetime_source_offset(text)?;
    if let Some((date, time)) = text.split_once('T') {
        let (year, month, day) = parse_date_parts(date)?;
        let (hour, minute, second, millisecond) = parse_time_parts(time)?;
        return validate_datetime_value(normalize_source_offset(
            DateTimeValue {
                year,
                month,
                day,
                hour,
                minute,
                second,
                millisecond,
                offset_minutes: 0,
            },
            source_offset_minutes,
        ));
    }
    if source_offset_minutes != 0 {
        return Err(invalid_date_time_operand());
    }
    if text.contains(':') {
        let (hour, minute, second, millisecond) = parse_time_parts(text)?;
        return validate_datetime_value(DateTimeValue {
            year: 1970,
            month: 1,
            day: 1,
            hour,
            minute,
            second,
            millisecond,
            offset_minutes: 0,
        });
    }
    let (year, month, day) = parse_date_parts(text)?;
    validate_datetime_value(DateTimeValue {
        year,
        month,
        day,
        hour: 0,
        minute: 0,
        second: 0,
        millisecond: 0,
        offset_minutes: 0,
    })
}

fn validate_datetime_value(value: DateTimeValue) -> Result<DateTimeValue, Diagnostic> {
    if value.year < 1 || value.year > 9999 {
        return Err(invalid_date_time_operand());
    }
    Ok(value)
}

fn split_datetime_source_offset(value: &str) -> Result<(&str, i32), Diagnostic> {
    if let Some(stripped) = value.strip_suffix('Z') {
        return Ok((stripped, 0));
    }
    let Some(time_index) = value.find('T') else {
        return Ok((value, 0));
    };
    let time = &value[time_index + 1..];
    let Some(relative_sign_index) = time.rfind(['+', '-']) else {
        return Ok((value, 0));
    };
    let sign_index = time_index + 1 + relative_sign_index;
    let (date_time, offset) = value.split_at(sign_index);
    if offset.len() != 6 || offset.as_bytes().get(3) != Some(&b':') {
        return Err(invalid_date_time_operand());
    }
    let Some(offset_minutes) = parse_offset_minutes(offset) else {
        return Err(invalid_date_time_operand());
    };
    Ok((date_time, offset_minutes))
}

fn normalize_source_offset(value: DateTimeValue, source_offset_minutes: i32) -> DateTimeValue {
    if source_offset_minutes == 0 {
        value
    } else {
        DateTimeValue {
            offset_minutes: 0,
            ..apply_time_zone(value, -source_offset_minutes)
        }
    }
}

fn parse_date_parts(value: &str) -> Result<(i32, u8, u8), Diagnostic> {
    let mut parts = value.split('-');
    let year = parts
        .next()
        .filter(|part| part.len() == 4)
        .and_then(|part| part.parse::<i32>().ok());
    let month = parts
        .next()
        .filter(|part| part.len() == 2)
        .and_then(|part| part.parse::<u8>().ok());
    let day = parts
        .next()
        .filter(|part| part.len() == 2)
        .and_then(|part| part.parse::<u8>().ok());
    if parts.next().is_some() {
        return Err(invalid_date_time_operand());
    }
    let (Some(year), Some(month), Some(day)) = (year, month, day) else {
        return Err(invalid_date_time_operand());
    };
    if month == 0 || month > 12 || day == 0 || day > days_in_month(year, month) {
        return Err(invalid_date_time_operand());
    }
    Ok((year, month, day))
}

fn parse_time_parts(value: &str) -> Result<(u8, u8, u8, u16), Diagnostic> {
    if value.contains(['+', '-']) {
        return Err(invalid_date_time_operand());
    }
    let mut parts = value.split(':');
    let hour = parts
        .next()
        .filter(|part| part.len() == 2)
        .and_then(|part| part.parse::<u8>().ok());
    let minute = parts
        .next()
        .filter(|part| part.len() == 2)
        .and_then(|part| part.parse::<u8>().ok());
    let seconds_and_fraction = parts.next();
    if parts.next().is_some() {
        return Err(invalid_date_time_operand());
    }
    let (second, millisecond) = match seconds_and_fraction {
        Some(value) => {
            let (second, fraction) = value.split_once('.').unwrap_or((value, ""));
            if second.len() != 2 {
                return Err(invalid_date_time_operand());
            }
            let second = second
                .parse::<u8>()
                .map_err(|_| invalid_date_time_operand())?;
            let mut milliseconds = fraction.chars().take(3).collect::<String>();
            while milliseconds.len() < 3 {
                milliseconds.push('0');
            }
            let millisecond = if milliseconds.is_empty() {
                0
            } else {
                milliseconds
                    .parse::<u16>()
                    .map_err(|_| invalid_date_time_operand())?
            };
            (second, millisecond)
        }
        None => (0, 0),
    };
    let (Some(hour), Some(minute)) = (hour, minute) else {
        return Err(invalid_date_time_operand());
    };
    if hour > 23 || minute > 59 || second > 59 {
        return Err(invalid_date_time_operand());
    }
    Ok((hour, minute, second, millisecond))
}

fn apply_time_zone(value: DateTimeValue, offset_minutes: i32) -> DateTimeValue {
    if offset_minutes == 0 {
        return DateTimeValue {
            offset_minutes,
            ..value
        };
    }
    let millis_per_day = 86_400_000;
    let total_millis =
        (((value.hour as i32 * 60 + value.minute as i32) * 60 + value.second as i32) * 1_000
            + value.millisecond as i32)
            + offset_minutes * 60_000;
    let day_delta = total_millis.div_euclid(millis_per_day);
    let wall_millis = total_millis.rem_euclid(millis_per_day);
    let hour = (wall_millis / 3_600_000) as u8;
    let minute = ((wall_millis % 3_600_000) / 60_000) as u8;
    let second = ((wall_millis % 60_000) / 1_000) as u8;
    let millisecond = (wall_millis % 1_000) as u16;
    let (year, month, day) = add_days(value.year, value.month, value.day, day_delta);
    DateTimeValue {
        year,
        month,
        day,
        hour,
        minute,
        second,
        millisecond,
        offset_minutes,
    }
}

fn add_days(mut year: i32, mut month: u8, mut day: u8, mut delta: i32) -> (i32, u8, u8) {
    while delta > 0 {
        let month_days = days_in_month(year, month);
        if day < month_days {
            day += 1;
        } else {
            day = 1;
            if month == 12 {
                month = 1;
                year += 1;
            } else {
                month += 1;
            }
        }
        delta -= 1;
    }
    while delta < 0 {
        if day > 1 {
            day -= 1;
        } else {
            if month == 1 {
                month = 12;
                year -= 1;
            } else {
                month -= 1;
            }
            day = days_in_month(year, month);
        }
        delta += 1;
    }
    (year, month, day)
}

fn days_in_month(year: i32, month: u8) -> u8 {
    match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if is_leap_year(year) => 29,
        2 => 28,
        _ => 0,
    }
}

fn is_leap_year(year: i32) -> bool {
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
}

fn format_pattern(
    pattern: &str,
    value: DateTimeValue,
    locale_data: &CldrDateTimeLocaleData,
) -> Result<String, Diagnostic> {
    let chars: Vec<_> = pattern.chars().collect();
    let mut output = String::new();
    let mut index = 0usize;
    while index < chars.len() {
        let ch = chars[index];
        if ch == '\'' {
            let quoted = read_quoted_pattern(&chars, index);
            output.push_str(&quoted.value);
            index = quoted.next_index;
        } else if ch.is_ascii_alphabetic() {
            let mut end = index + 1;
            while end < chars.len() && chars[end] == ch {
                end += 1;
            }
            output.push_str(&format_field(ch, end - index, value, locale_data)?);
            index = end;
        } else {
            output.push(ch);
            index += 1;
        }
    }
    Ok(output)
}

struct QuotedPattern {
    value: String,
    next_index: usize,
}

fn read_quoted_pattern(pattern: &[char], start: usize) -> QuotedPattern {
    if pattern.get(start + 1) == Some(&'\'') {
        return QuotedPattern {
            value: "'".to_string(),
            next_index: start + 2,
        };
    }
    let mut output = String::new();
    let mut index = start + 1;
    while index < pattern.len() {
        if pattern[index] == '\'' {
            if pattern.get(index + 1) == Some(&'\'') {
                output.push('\'');
                index += 2;
            } else {
                return QuotedPattern {
                    value: output,
                    next_index: index + 1,
                };
            }
        } else {
            output.push(pattern[index]);
            index += 1;
        }
    }
    QuotedPattern {
        value: output,
        next_index: index,
    }
}

fn format_field(
    symbol: char,
    count: usize,
    value: DateTimeValue,
    locale_data: &CldrDateTimeLocaleData,
) -> Result<String, Diagnostic> {
    match symbol {
        'G' => Ok(era_name(value, locale_data, count)),
        'y' => Ok(year_value(value, locale_data, count)),
        'u' => Ok(extended_year_value(value, locale_data, count)),
        'r' => Ok(extended_year_value(value, locale_data, count)),
        'Y' => Ok(week_year_value(value, locale_data, count)),
        'Q' | 'q' => Ok(quarter_value(value, locale_data, count, symbol == 'q')),
        'M' | 'L' => Ok(month_value(value, locale_data, count, symbol == 'L')),
        'd' => Ok(integer_value(value.day as i32, locale_data, count)),
        'D' => Ok(integer_value(day_of_year(value), locale_data, count)),
        'F' => Ok(integer_value(
            day_of_week_in_month(value),
            locale_data,
            count,
        )),
        'g' => Ok(integer_value(
            modified_julian_day(value),
            locale_data,
            count,
        )),
        'w' => Ok(integer_value(
            week_of_year(value, locale_data),
            locale_data,
            count,
        )),
        'W' => Ok(integer_value(
            week_of_month(value, locale_data),
            locale_data,
            count,
        )),
        'E' => Ok(weekday_name(value, locale_data, count)),
        'e' => Ok(local_weekday_value(value, locale_data, count, false)),
        'c' => Ok(local_weekday_value(value, locale_data, count, true)),
        'a' | 'b' | 'B' => Ok(day_period_name(value, locale_data, count, symbol)),
        'H' => Ok(integer_value(value.hour as i32, locale_data, count)),
        'k' => Ok(integer_value(
            if value.hour == 0 {
                24
            } else {
                value.hour as i32
            },
            locale_data,
            count,
        )),
        'h' => Ok(integer_value(hour12(value) as i32, locale_data, count)),
        'K' => Ok(integer_value((value.hour % 12) as i32, locale_data, count)),
        'm' => Ok(integer_value(value.minute as i32, locale_data, count)),
        's' => Ok(integer_value(value.second as i32, locale_data, count)),
        'S' => Ok(fraction_value(value, locale_data, count)),
        'A' => Ok(integer_value(
            milliseconds_in_day(value),
            locale_data,
            count,
        )),
        'z' | 'Z' | 'O' | 'v' | 'V' | 'X' | 'x' => {
            Ok(time_zone_value(symbol, count, value, locale_data))
        }
        _ => Err(bad_option(format!(
            "Unsupported CLDR date/time pattern field: {symbol}."
        ))),
    }
}

fn time_zone_value(
    symbol: char,
    count: usize,
    value: DateTimeValue,
    locale_data: &CldrDateTimeLocaleData,
) -> String {
    if value.offset_minutes != 0 {
        return match symbol {
            'X' => iso_offset(value.offset_minutes, count, true),
            'x' => iso_offset(value.offset_minutes, count, false),
            'V' if count == 1 => "unk".to_string(),
            'V' if count == 2 => fixed_offset_gmt_id(locale_data, value.offset_minutes),
            'V' if count == 3 => "Unknown Location".to_string(),
            'Z' if count <= 3 => basic_offset(value.offset_minutes),
            'Z' if count == 5 => iso_offset(value.offset_minutes, 3, true),
            _ => localized_gmt_offset(locale_data, value.offset_minutes, count),
        };
    }
    match symbol {
        'z' if count >= 4 => string_map_get(locale_data.time_zone_names, "utcLong")
            .or_else(|| string_map_get(locale_data.time_zone_names, "utcShort"))
            .unwrap_or(UTC)
            .to_string(),
        'z' => string_map_get(locale_data.time_zone_names, "utcShort")
            .unwrap_or(UTC)
            .to_string(),
        'O' | 'v' => localized_gmt_zero(locale_data),
        'V' => localized_gmt_zero(locale_data),
        'Z' if count <= 3 => "+0000".to_string(),
        'Z' if count == 5 => "Z".to_string(),
        'Z' => localized_gmt_zero(locale_data),
        'X' => "Z".to_string(),
        'x' if count == 1 => "+00".to_string(),
        'x' if count == 2 || count == 4 => "+0000".to_string(),
        'x' => "+00:00".to_string(),
        _ => UTC.to_string(),
    }
}

fn localized_gmt_zero(locale_data: &CldrDateTimeLocaleData) -> String {
    if let Some(value) = string_map_get(locale_data.time_zone_names, "gmtZeroFormat") {
        return value.to_string();
    }
    string_map_get(locale_data.time_zone_names, "gmtFormat")
        .unwrap_or("GMT{0}")
        .replace("{0}", "")
}

fn localized_gmt_offset(
    locale_data: &CldrDateTimeLocaleData,
    offset_minutes: i32,
    count: usize,
) -> String {
    let formatted = if count >= 4 {
        extended_offset(offset_minutes, true)
    } else {
        short_offset(offset_minutes)
    };
    string_map_get(locale_data.time_zone_names, "gmtFormat")
        .unwrap_or("GMT{0}")
        .replace(
            "{0}",
            &localize_digits(&formatted, locale_data.numbering_system_digits),
        )
}

fn fixed_offset_gmt_id(locale_data: &CldrDateTimeLocaleData, offset_minutes: i32) -> String {
    format!(
        "GMT{}",
        localize_digits(
            &extended_offset(offset_minutes, true),
            locale_data.numbering_system_digits
        )
    )
}

fn iso_offset(offset_minutes: i32, count: usize, use_zero_z: bool) -> String {
    if offset_minutes == 0 && use_zero_z {
        return "Z".to_string();
    }
    if count == 1 {
        return short_iso_offset(offset_minutes);
    }
    if count == 2 || count == 4 {
        return basic_offset(offset_minutes);
    }
    extended_offset(offset_minutes, true)
}

fn short_iso_offset(offset_minutes: i32) -> String {
    let (sign, hours, minutes) = offset_parts(offset_minutes);
    if minutes == 0 {
        return format!("{sign}{hours:02}");
    }
    format!("{sign}{hours:02}{minutes:02}")
}

fn short_offset(offset_minutes: i32) -> String {
    let (sign, hours, minutes) = offset_parts(offset_minutes);
    if minutes == 0 {
        return format!("{sign}{hours}");
    }
    format!("{sign}{hours}:{minutes:02}")
}

fn basic_offset(offset_minutes: i32) -> String {
    let (sign, hours, minutes) = offset_parts(offset_minutes);
    format!("{sign}{hours:02}{minutes:02}")
}

fn extended_offset(offset_minutes: i32, padded_hour: bool) -> String {
    let (sign, hours, minutes) = offset_parts(offset_minutes);
    if padded_hour {
        return format!("{sign}{hours:02}:{minutes:02}");
    }
    format!("{sign}{hours}:{minutes:02}")
}

fn offset_parts(offset_minutes: i32) -> (&'static str, i32, i32) {
    let sign = if offset_minutes < 0 { "-" } else { "+" };
    let absolute = offset_minutes.abs();
    (sign, absolute / 60, absolute % 60)
}

fn era_name(value: DateTimeValue, locale_data: &CldrDateTimeLocaleData, count: usize) -> String {
    let era = if value.year <= 0 { "0" } else { "1" };
    name_by_width(locale_data.eras, width_for_text(count), era)
}

fn year_value(value: DateTimeValue, locale_data: &CldrDateTimeLocaleData, count: usize) -> String {
    let year_of_era = if value.year <= 0 {
        1 - value.year
    } else {
        value.year
    };
    if count == 2 {
        return integer_text(year_of_era % 100, locale_data, 2);
    }
    localize_digits(
        &year_of_era.to_string(),
        locale_data.numbering_system_digits,
    )
}

fn extended_year_value(
    value: DateTimeValue,
    locale_data: &CldrDateTimeLocaleData,
    count: usize,
) -> String {
    integer_value(value.year, locale_data, count)
}

fn week_year_value(
    value: DateTimeValue,
    locale_data: &CldrDateTimeLocaleData,
    count: usize,
) -> String {
    let (year, _) = week_year_info(value, locale_data);
    if count == 2 {
        return integer_text(year.rem_euclid(100), locale_data, 2);
    }
    localize_digits(&year.to_string(), locale_data.numbering_system_digits)
}

fn day_of_year(value: DateTimeValue) -> i32 {
    days_before_month(value.year, value.month) + i32::from(value.day)
}

fn day_of_week_in_month(value: DateTimeValue) -> i32 {
    ((i32::from(value.day) - 1) / 7) + 1
}

fn milliseconds_in_day(value: DateTimeValue) -> i32 {
    ((i32::from(value.hour) * 60 + i32::from(value.minute)) * 60 + i32::from(value.second)) * 1000
        + i32::from(value.millisecond)
}

fn modified_julian_day(value: DateTimeValue) -> i32 {
    ordinal_day(value.year, value.month, value.day) - ordinal_day(1858, 11, 17)
}

fn week_of_year(value: DateTimeValue, locale_data: &CldrDateTimeLocaleData) -> i32 {
    let (_, week) = week_year_info(value, locale_data);
    week
}

fn week_year_info(value: DateTimeValue, locale_data: &CldrDateTimeLocaleData) -> (i32, i32) {
    let ordinal = ordinal_day(value.year, value.month, value.day);
    let current_start = first_week_start_of_year(value.year, locale_data);
    if ordinal < current_start {
        let previous_year = value.year - 1;
        let previous_start = first_week_start_of_year(previous_year, locale_data);
        return (
            previous_year,
            ((ordinal - previous_start).div_euclid(7)) + 1,
        );
    }

    let next_start = first_week_start_of_year(value.year + 1, locale_data);
    if ordinal >= next_start {
        return (value.year + 1, ((ordinal - next_start).div_euclid(7)) + 1);
    }

    (value.year, ((ordinal - current_start).div_euclid(7)) + 1)
}

fn week_of_month(value: DateTimeValue, locale_data: &CldrDateTimeLocaleData) -> i32 {
    let ordinal = ordinal_day(value.year, value.month, value.day);
    let first_start = first_week_start(
        ordinal_day(value.year, value.month, 1),
        locale_data.first_day_of_week as i32,
        locale_data.min_days_in_first_week as i32,
    );
    ((ordinal - first_start).div_euclid(7)) + 1
}

fn first_week_start_of_year(year: i32, locale_data: &CldrDateTimeLocaleData) -> i32 {
    first_week_start(
        ordinal_day(year, 1, 1),
        locale_data.first_day_of_week as i32,
        locale_data.min_days_in_first_week as i32,
    )
}

fn first_week_start(period_start: i32, first_day: i32, min_days: i32) -> i32 {
    let week_start = start_of_week(period_start, first_day);
    if 7 - (period_start - week_start) >= min_days {
        week_start
    } else {
        week_start + 7
    }
}

fn start_of_week(ordinal: i32, first_day: i32) -> i32 {
    ordinal - (day_of_week(ordinal) - first_day).rem_euclid(7)
}

fn day_of_week(ordinal: i32) -> i32 {
    ordinal.rem_euclid(7)
}

fn ordinal_day(year: i32, month: u8, day: u8) -> i32 {
    days_before_year(year) + days_before_month(year, month) + day as i32
}

fn days_before_year(year: i32) -> i32 {
    let previous = year - 1;
    365 * previous + previous.div_euclid(4) - previous.div_euclid(100) + previous.div_euclid(400)
}

fn days_before_month(year: i32, month: u8) -> i32 {
    const OFFSETS: [i32; 12] = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334];
    let mut days = OFFSETS[(month - 1) as usize];
    if month > 2 && is_leap_year(year) {
        days += 1;
    }
    days
}

fn month_value(
    value: DateTimeValue,
    locale_data: &CldrDateTimeLocaleData,
    count: usize,
    stand_alone: bool,
) -> String {
    if count <= 2 {
        return integer_value(value.month as i32, locale_data, count);
    }
    contextual_name(
        locale_data.months,
        if stand_alone { "stand-alone" } else { "format" },
        width_for_text(count),
        &value.month.to_string(),
    )
}

fn quarter_value(
    value: DateTimeValue,
    locale_data: &CldrDateTimeLocaleData,
    count: usize,
    stand_alone: bool,
) -> String {
    let quarter = ((value.month - 1) / 3) + 1;
    if count <= 2 {
        return integer_value(quarter as i32, locale_data, count);
    }
    contextual_name(
        locale_data.quarters,
        if stand_alone { "stand-alone" } else { "format" },
        width_for_text(count),
        &quarter.to_string(),
    )
}

fn weekday_name(
    value: DateTimeValue,
    locale_data: &CldrDateTimeLocaleData,
    count: usize,
) -> String {
    contextual_name(
        locale_data.weekdays,
        "format",
        width_for_weekday(count),
        WEEKDAY_KEYS[weekday_index(value.year, value.month, value.day)],
    )
}

fn local_weekday_value(
    value: DateTimeValue,
    locale_data: &CldrDateTimeLocaleData,
    count: usize,
    stand_alone: bool,
) -> String {
    let day = weekday_index(value.year, value.month, value.day);
    if count <= 2 {
        let local_day = ((day as i32 - locale_data.first_day_of_week as i32).rem_euclid(7)) + 1;
        return integer_value(local_day, locale_data, count);
    }
    contextual_name(
        locale_data.weekdays,
        if stand_alone { "stand-alone" } else { "format" },
        width_for_weekday(count),
        WEEKDAY_KEYS[day],
    )
}

fn day_period_name(
    value: DateTimeValue,
    locale_data: &CldrDateTimeLocaleData,
    count: usize,
    symbol: char,
) -> String {
    contextual_name(
        locale_data.day_periods,
        "format",
        width_for_day_period(count),
        &day_period_key(value, locale_data, symbol),
    )
}

fn day_period_key(
    value: DateTimeValue,
    locale_data: &CldrDateTimeLocaleData,
    symbol: char,
) -> String {
    let fallback = if value.hour < 12 { "am" } else { "pm" };
    if symbol == 'a' {
        return fallback.to_string();
    }
    if symbol == 'b' {
        return select_day_period_rule(value, locale_data.day_period_rules, true)
            .unwrap_or(fallback)
            .to_string();
    }
    select_day_period_rule(value, locale_data.day_period_rules, false)
        .unwrap_or(fallback)
        .to_string()
}

fn select_day_period_rule(
    value: DateTimeValue,
    encoded_rules: &'static str,
    exact_only: bool,
) -> Option<&'static str> {
    if encoded_rules.is_empty() {
        return None;
    }
    let minute = value.hour as u16 * 60 + value.minute as u16;
    let exact_minute = if value.second == 0 && value.millisecond == 0 {
        Some(minute)
    } else {
        None
    };
    let mut range_match = None;
    for raw_rule in encoded_rules.split(';') {
        let (period, span) = match raw_rule.split_once('=') {
            Some(parts) => parts,
            None => continue,
        };
        if let Some((start, end)) = span.split_once('-') {
            if !exact_only
                && minute_in_day_period_range(
                    minute,
                    start.parse().unwrap_or(0),
                    end.parse().unwrap_or(0),
                )
                && range_match.is_none()
            {
                range_match = Some(period);
            }
        } else if Some(span.parse().unwrap_or(u16::MAX)) == exact_minute {
            return Some(period);
        }
    }
    if exact_only {
        None
    } else {
        range_match
    }
}

fn minute_in_day_period_range(minute: u16, start: u16, end: u16) -> bool {
    if start <= end {
        minute >= start && minute < end
    } else {
        minute >= start || minute < end
    }
}

fn hour12(value: DateTimeValue) -> u8 {
    let hour = value.hour % 12;
    if hour == 0 {
        12
    } else {
        hour
    }
}

fn fraction_value(
    value: DateTimeValue,
    locale_data: &CldrDateTimeLocaleData,
    count: usize,
) -> String {
    let milliseconds = format!("{:03}", value.millisecond);
    let text = format!("{milliseconds}000000000");
    localize_digits(
        &text[..count.min(text.len())],
        locale_data.numbering_system_digits,
    )
}

fn integer_value(value: i32, locale_data: &CldrDateTimeLocaleData, count: usize) -> String {
    integer_text(value, locale_data, if count >= 2 { count } else { 0 })
}

fn integer_text(value: i32, locale_data: &CldrDateTimeLocaleData, minimum_digits: usize) -> String {
    let mut text = value.abs().to_string();
    while text.len() < minimum_digits {
        text.insert(0, '0');
    }
    if value < 0 {
        text.insert(0, '-');
    }
    localize_digits(&text, locale_data.numbering_system_digits)
}

fn contextual_name(
    source: CldrDateTimeNestedMap3,
    context: &str,
    width: &str,
    key: &str,
) -> String {
    let context_data = nested_map3_get(source, context)
        .or_else(|| nested_map3_get(source, "format"))
        .or_else(|| nested_map3_get(source, "stand-alone"))
        .unwrap_or(&[]);
    name_by_width(context_data, width, key)
}

fn name_by_width(source: CldrDateTimeNestedMap2, width: &str, key: &str) -> String {
    for candidate in [width, "abbreviated", "wide", "short", "narrow"] {
        if let Some(value) =
            nested_map2_get(source, candidate).and_then(|map| string_map_get(map, key))
        {
            return value.to_string();
        }
    }
    key.to_string()
}

fn width_for_text(count: usize) -> &'static str {
    match count {
        4 => "wide",
        5 => "narrow",
        _ => "abbreviated",
    }
}

fn width_for_weekday(count: usize) -> &'static str {
    if count == 4 {
        "wide"
    } else if count == 5 {
        "narrow"
    } else if count >= 6 {
        "short"
    } else {
        "abbreviated"
    }
}

fn width_for_day_period(count: usize) -> &'static str {
    if count == 4 {
        "wide"
    } else if count >= 5 {
        "narrow"
    } else {
        "abbreviated"
    }
}

fn weekday_index(year: i32, month: u8, day: u8) -> usize {
    let mut year = year;
    let month = month as usize;
    let offsets = [0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4];
    if month < 3 {
        year -= 1;
    }
    ((year + year / 4 - year / 100 + year / 400 + offsets[month - 1] + day as i32) % 7) as usize
}

fn call_style(
    call: &FunctionCall<'_>,
    option_name: &str,
    legacy_option_name: &str,
    fallback: &str,
    legacy_time_precision: bool,
) -> Result<String, Diagnostic> {
    if let Some(value) = call.option_value(option_name)? {
        return Ok(DateTimeCoreStyle::from_name(&value, option_name)?
            .key()
            .to_string());
    }
    if let Some(value) = call.option_value(legacy_option_name)? {
        let style = if legacy_time_precision {
            time_precision_style_option(&value, legacy_option_name)?
        } else {
            DateTimeCoreStyle::from_name(&value, legacy_option_name)?
        };
        return Ok(style.key().to_string());
    }
    if let Some(value) = call.option_value("style")? {
        return Ok(DateTimeCoreStyle::from_name(&value, "style")?
            .key()
            .to_string());
    }
    Ok(DateTimeCoreStyle::from_name(fallback, option_name)?
        .key()
        .to_string())
}

fn call_non_empty_option(
    call: &FunctionCall<'_>,
    name: &str,
) -> Result<Option<String>, Diagnostic> {
    let value = call.option_value(name)?;
    if value.as_deref() == Some("") {
        return Err(bad_option(format!("{name} must not be empty.")));
    }
    Ok(value)
}

fn call_source_value<'a>(call: &FunctionCall<'a>) -> &'a str {
    call.inherited_source()
        .map(|source| source.value())
        .unwrap_or_else(|| call.value())
}

fn time_style_option(
    time_style: Option<&str>,
    time_precision: Option<&str>,
    precision: Option<&str>,
    style: &str,
    _fallback: &str,
) -> Result<DateTimeCoreStyle, Diagnostic> {
    if let Some(value) = time_style {
        return DateTimeCoreStyle::from_name(value, "timeStyle");
    }
    if let Some(value) = time_precision {
        return time_precision_style_option(value, "timePrecision");
    }
    if let Some(value) = precision {
        return time_precision_style_option(value, "precision");
    }
    DateTimeCoreStyle::from_name(style, "timeStyle")
}

fn time_precision_style_option(value: &str, name: &str) -> Result<DateTimeCoreStyle, Diagnostic> {
    if value == "second" {
        Ok(DateTimeCoreStyle::Medium)
    } else {
        DateTimeCoreStyle::from_name(value, name)
    }
}

fn format_skeleton(
    skeleton: &str,
    value: DateTimeValue,
    locale_data: &CldrDateTimeLocaleData,
    hour_cycle: Option<&str>,
    preserve_same_family_hour_cycle: bool,
) -> Result<String, Diagnostic> {
    if skeleton.chars().count() > MAX_SKELETON_LENGTH {
        return Err(bad_option("Date/time skeleton is too large."));
    }
    if let Some(formatted) = format_semantic_style_skeleton(
        skeleton,
        value,
        locale_data,
        hour_cycle,
        preserve_same_family_hour_cycle,
    )? {
        return Ok(formatted);
    }
    let canonical = canonical_skeleton(skeleton, locale_data, hour_cycle, value)?;
    let suppress_day_period = should_suppress_day_period(skeleton);
    let date_time_join_style = skeleton_date_time_join_style(skeleton)?;
    if let Some(pattern) = skeleton_pattern(&canonical, locale_data) {
        let pattern = if suppress_day_period {
            strip_day_period_pattern_fields(&pattern)
        } else {
            pattern
        };
        return format_pattern(&pattern, value, locale_data);
    }
    format_composed_skeleton(
        skeleton,
        &canonical,
        value,
        locale_data,
        suppress_day_period,
        &date_time_join_style,
    )
}

fn skeleton_date_time_join_style(skeleton: &str) -> Result<String, Diagnostic> {
    let Some(body) = skeleton.strip_prefix(SEMANTIC_SKELETON_PREFIX) else {
        return Ok("medium".to_string());
    };
    let options = parse_semantic_skeleton_options(body)?;
    Ok(semantic_option(
        &options,
        "length",
        "medium",
        &["full", "long", "medium", "short"],
    )?
    .to_string())
}

fn format_semantic_style_skeleton(
    skeleton: &str,
    value: DateTimeValue,
    locale_data: &CldrDateTimeLocaleData,
    hour_cycle: Option<&str>,
    preserve_same_family_hour_cycle: bool,
) -> Result<Option<String>, Diagnostic> {
    let Some(body) = skeleton.strip_prefix(SEMANTIC_SKELETON_PREFIX) else {
        return Ok(None);
    };
    let options = parse_semantic_skeleton_options(body)?;
    let fields = parse_semantic_skeleton_fields(&options)?;
    validate_semantic_skeleton(&fields, &options)?;
    if options
        .iter()
        .any(|(key, _)| !SEMANTIC_DIRECT_STYLE_OPTION_KEYS.contains(&key.as_str()))
    {
        return Ok(None);
    }

    let length = semantic_option(
        &options,
        "length",
        "medium",
        &["full", "long", "medium", "short"],
    )?;
    let time_style = semantic_option(
        &options,
        "timestyle",
        "auto",
        &["auto", "short", "medium", "long", "full"],
    )?;
    let date_key = semantic_field_set_key(&fields, SEMANTIC_DATE_FIELD_ORDER.as_slice());
    let expected_date_key = if length == "full" {
        "year,month,day,weekday"
    } else {
        "year,month,day"
    };
    let has_date = !date_key.is_empty();
    let has_time = fields.iter().any(|field| field == "time");
    let has_zone = fields.iter().any(|field| field == "zone");
    if !semantic_field_set_key(&fields, SEMANTIC_TIME_FIELD_ORDER.as_slice()).is_empty() {
        return Ok(None);
    }
    if has_date && date_key != expected_date_key {
        return Ok(None);
    }
    if has_time && !options.iter().any(|(key, _)| key == "timestyle") {
        return Ok(None);
    }
    if !has_time && (has_zone || time_style != "auto") {
        return Ok(None);
    }
    if has_time && has_zone != semantic_time_style_has_zone(&time_style) {
        return Ok(None);
    }
    let expected_field_count = (if has_date {
        expected_date_key.split(',').count()
    } else {
        0
    }) + usize::from(has_time)
        + usize::from(has_zone);
    if fields.len() != expected_field_count {
        return Ok(None);
    }

    if has_date && has_time {
        let date_part = format_pattern(
            string_map_get(locale_data.date_formats, &length).unwrap_or(""),
            value,
            locale_data,
        )?;
        let time_part = format_time_style_pattern(
            string_map_get(locale_data.time_formats, &time_style).unwrap_or(""),
            value,
            locale_data,
            hour_cycle,
            preserve_same_family_hour_cycle,
        )?;
        let join_pattern = date_time_style_join_pattern(locale_data, &length);
        return Ok(Some(
            join_pattern
                .replace("{1}", &date_part)
                .replace("{0}", &time_part),
        ));
    }
    if has_date {
        return Ok(Some(format_pattern(
            string_map_get(locale_data.date_formats, &length).unwrap_or(""),
            value,
            locale_data,
        )?));
    }
    if has_time {
        return Ok(Some(format_time_style_pattern(
            string_map_get(locale_data.time_formats, &time_style).unwrap_or(""),
            value,
            locale_data,
            hour_cycle,
            preserve_same_family_hour_cycle,
        )?));
    }
    Ok(None)
}

fn format_time_style_pattern(
    pattern: &str,
    value: DateTimeValue,
    locale_data: &CldrDateTimeLocaleData,
    hour_cycle: Option<&str>,
    preserve_same_family_hour_cycle: bool,
) -> Result<String, Diagnostic> {
    let Some(hour_cycle) = hour_cycle else {
        return format_pattern(pattern, value, locale_data);
    };
    let hour_symbol = preferred_hour_symbol(locale_data, Some(hour_cycle));
    if let Some(pattern_hour_symbol) = time_style_pattern_hour_symbol(pattern) {
        if preserve_same_family_hour_cycle
            && is_hour12_field(pattern_hour_symbol) == is_hour12_field(hour_symbol)
        {
            return format_pattern(
                &replace_time_style_pattern_hour_symbol(pattern, hour_symbol),
                value,
                locale_data,
            );
        }
    }
    let Some(skeleton) = time_style_pattern_skeleton(pattern, locale_data, hour_cycle) else {
        return format_pattern(pattern, value, locale_data);
    };
    let canonical = canonical_standard_skeleton(&skeleton, locale_data, None)?;
    if let Some(matched) = skeleton_pattern(&canonical, locale_data) {
        return format_pattern(&matched, value, locale_data);
    }
    format_pattern(pattern, value, locale_data)
}

fn date_time_style_join_pattern<'a>(
    locale_data: &'a CldrDateTimeLocaleData,
    style: &str,
) -> &'a str {
    string_map_get(locale_data.date_time_style_join_formats, style)
        .or_else(|| string_map_get(locale_data.date_time_formats, style))
        .or_else(|| string_map_get(locale_data.date_time_formats, "medium"))
        .unwrap_or("{1} {0}")
}

fn time_style_pattern_hour_symbol(pattern: &str) -> Option<char> {
    let chars: Vec<char> = pattern.chars().collect();
    let mut index = 0usize;
    while index < chars.len() {
        let symbol = chars[index];
        if symbol == '\'' {
            index = read_quoted_pattern(&chars, index).next_index;
        } else if symbol.is_ascii_alphabetic() {
            let mut end = index + 1;
            while end < chars.len() && chars[end] == symbol {
                end += 1;
            }
            if is_hour_field(symbol) {
                return Some(symbol);
            }
            index = end;
        } else {
            index += 1;
        }
    }
    None
}

fn replace_time_style_pattern_hour_symbol(pattern: &str, hour_symbol: char) -> String {
    let chars: Vec<char> = pattern.chars().collect();
    let mut output = String::new();
    let mut index = 0usize;
    while index < chars.len() {
        let symbol = chars[index];
        if symbol == '\'' {
            let quoted = read_quoted_pattern(&chars, index);
            output.extend(chars[index..quoted.next_index].iter());
            index = quoted.next_index;
        } else if symbol.is_ascii_alphabetic() {
            let mut end = index + 1;
            while end < chars.len() && chars[end] == symbol {
                end += 1;
            }
            if is_hour_field(symbol) {
                for _ in index..end {
                    output.push(hour_symbol);
                }
            } else {
                output.extend(chars[index..end].iter());
            }
            index = end;
        } else {
            output.push(symbol);
            index += 1;
        }
    }
    output
}

fn time_style_pattern_skeleton(
    pattern: &str,
    locale_data: &CldrDateTimeLocaleData,
    hour_cycle: &str,
) -> Option<String> {
    let mut widths = Vec::new();
    let hour_symbol = preferred_hour_symbol(locale_data, Some(hour_cycle));
    let mut has_hour = false;
    for (symbol, width) in pattern_field_runs(pattern) {
        if is_hour_field(symbol) {
            set_skeleton_width(&mut widths, hour_symbol, width);
            has_hour = true;
        } else if !is_day_period_field(symbol) && SKELETON_TIME_FIELDS.contains(symbol) {
            set_skeleton_width(&mut widths, symbol, width);
        }
    }
    if !has_hour {
        return None;
    }
    let mut skeleton = String::new();
    for symbol in SKELETON_FIELD_ORDER.chars() {
        if let Some((_, width)) = widths.iter().find(|(candidate, _)| *candidate == symbol) {
            for _ in 0..*width {
                skeleton.push(symbol);
            }
        }
    }
    Some(skeleton)
}

fn skeleton_pattern(canonical: &str, locale_data: &CldrDateTimeLocaleData) -> Option<String> {
    if let Some(pattern) = skeleton_pattern_without_append(canonical, locale_data) {
        return Some(pattern);
    }
    if has_date_and_time_fields(canonical) {
        None
    } else {
        appended_skeleton_pattern(canonical, locale_data)
    }
}

fn skeleton_pattern_without_append(
    canonical: &str,
    locale_data: &CldrDateTimeLocaleData,
) -> Option<String> {
    if let Some(pattern) = string_map_get(locale_data.available_formats, &canonical) {
        return Some(pattern.to_string());
    }
    let requested_fields = skeleton_field_set(&canonical);
    let mut best_candidate: Option<&str> = None;
    let mut best_pattern: Option<&str> = None;
    let mut best_distance = usize::MAX;
    for (candidate, pattern) in locale_data.available_formats {
        if skeleton_field_set(candidate) != requested_fields {
            continue;
        }
        let distance = skeleton_distance(canonical, candidate);
        if distance < best_distance
            || (distance == best_distance
                && best_candidate
                    .map(|current| *candidate < current)
                    .unwrap_or(true))
        {
            best_candidate = Some(*candidate);
            best_pattern = Some(*pattern);
            best_distance = distance;
        }
    }
    if let (Some(pattern), Some(candidate)) = (best_pattern, best_candidate) {
        return Some(adjust_pattern_widths(pattern, canonical, candidate));
    }
    synthetic_skeleton_pattern(canonical, locale_data)
}

fn appended_skeleton_pattern(
    canonical: &str,
    locale_data: &CldrDateTimeLocaleData,
) -> Option<String> {
    let requested_fields = skeleton_field_set(canonical);
    let mut best_candidate: Option<&str> = None;
    let mut best_pattern: Option<&str> = None;
    let mut best_field_count = 0usize;
    let mut best_distance = usize::MAX;
    for (candidate, pattern) in locale_data.available_formats {
        let candidate_fields = skeleton_field_set(candidate);
        if candidate_fields.is_empty() || candidate_fields == requested_fields {
            continue;
        }
        if !field_set_contains(&requested_fields, &candidate_fields) {
            continue;
        }
        let field_count = candidate_fields.chars().count();
        let distance = skeleton_distance(canonical, candidate);
        if field_count > best_field_count
            || (field_count == best_field_count
                && (distance < best_distance
                    || (distance == best_distance
                        && best_candidate
                            .map(|current| *candidate < current)
                            .unwrap_or(true))))
        {
            best_candidate = Some(*candidate);
            best_pattern = Some(*pattern);
            best_field_count = field_count;
            best_distance = distance;
        }
    }
    let (Some(best_pattern), Some(best_candidate)) = (best_pattern, best_candidate) else {
        return None;
    };
    let mut output = adjust_pattern_widths(best_pattern, canonical, best_candidate);
    let mut current_fields: Vec<char> = skeleton_field_set(best_candidate).chars().collect();
    for (symbol, width) in skeleton_widths(canonical) {
        let field = field_set_symbol(symbol);
        if current_fields.contains(&field) {
            continue;
        }
        let key = append_item_key(symbol)?;
        let field_skeleton = symbol.to_string().repeat(width);
        let field_pattern =
            skeleton_pattern_without_append(&field_skeleton, locale_data).unwrap_or(field_skeleton);
        let template = append_item_template(locale_data, key);
        let field_name = string_map_get(locale_data.field_names, key).unwrap_or(key);
        output = apply_append_item_pattern(template, &output, &field_pattern, field_name);
        current_fields.push(field);
    }
    Some(output)
}

fn field_set_contains(container: &str, subset: &str) -> bool {
    subset.chars().all(|field| container.contains(field))
}

fn apply_append_item_pattern(
    template: &str,
    base_pattern: &str,
    field_pattern: &str,
    field_name: &str,
) -> String {
    template
        .replace("{0}", base_pattern)
        .replace("{1}", field_pattern)
        .replace("{2}", &quote_pattern_literal(field_name))
}

fn quote_pattern_literal(value: &str) -> String {
    format!("'{}'", value.replace('\'', "''"))
}

fn append_item_template<'a>(locale_data: &'a CldrDateTimeLocaleData, key: &'a str) -> &'a str {
    string_map_get(locale_data.append_items, key)
        .unwrap_or_else(|| default_append_item_template(key))
}

fn default_append_item_template(key: &str) -> &'static str {
    match key {
        "Quarter" | "Month" | "Week" | "Day" | "Hour" | "Minute" | "Second" => "{0} ({2}: {1})",
        _ => "{0} {1}",
    }
}

fn has_date_and_time_fields(canonical: &str) -> bool {
    let (date_skeleton, time_skeleton) = split_date_time_skeleton(canonical);
    !date_skeleton.is_empty() && !time_skeleton.is_empty()
}

fn append_item_key(symbol: char) -> Option<&'static str> {
    if symbol == 'G' {
        return Some("Era");
    }
    if is_year_field(symbol) {
        return Some("Year");
    }
    if is_quarter_field(symbol) {
        return Some("Quarter");
    }
    if is_month_field(symbol) {
        return Some("Month");
    }
    if matches!(symbol, 'w' | 'W') {
        return Some("Week");
    }
    if matches!(symbol, 'd' | 'D' | 'F' | 'g') {
        return Some("Day");
    }
    if is_weekday_field(symbol) {
        return Some("Day-Of-Week");
    }
    if is_hour_field(symbol) {
        return Some("Hour");
    }
    if symbol == 'm' {
        return Some("Minute");
    }
    if matches!(symbol, 's' | 'S' | 'A') {
        return Some("Second");
    }
    if is_time_zone_field(symbol) {
        return Some("Timezone");
    }
    None
}

fn synthetic_skeleton_pattern(
    canonical: &str,
    locale_data: &CldrDateTimeLocaleData,
) -> Option<String> {
    let widths = skeleton_widths(canonical);
    if widths.len() == 1 {
        let (symbol, width) = widths[0];
        if symbol == 'G' {
            return Some(symbol.to_string().repeat(width));
        }
        if is_day_period_field(symbol) {
            return Some(symbol.to_string().repeat(width));
        }
        if is_quarter_field(symbol) {
            return Some(symbol.to_string().repeat(width));
        }
        if is_synthetic_numeric_field(symbol) {
            return Some(symbol.to_string().repeat(width));
        }
        if symbol == 'S' {
            return Some(symbol.to_string().repeat(width));
        }
        if is_time_zone_field(symbol) {
            return Some(symbol.to_string().repeat(width));
        }
    }
    synthetic_fractional_second_pattern(canonical, locale_data, &widths)
}

fn synthetic_fractional_second_pattern(
    canonical: &str,
    locale_data: &CldrDateTimeLocaleData,
    widths: &[(char, usize)],
) -> Option<String> {
    let fraction_width = width_for_skeleton_symbol(widths, 'S')?;
    width_for_skeleton_symbol(widths, 's')?;
    let base_skeleton = skeleton_without_field(canonical, 'S');
    let base_pattern = skeleton_pattern(&base_skeleton, locale_data)
        .or_else(|| synthetic_seconds_pattern(&base_skeleton))?;
    insert_fractional_second(&base_pattern, fraction_width, locale_data.decimal_separator)
}

fn synthetic_seconds_pattern(canonical: &str) -> Option<String> {
    let widths = skeleton_widths(canonical);
    let width = width_for_skeleton_symbol(&widths, 's')?;
    if widths.len() == 1 {
        Some("s".repeat(width))
    } else {
        None
    }
}

fn skeleton_without_field(skeleton: &str, removed_symbol: char) -> String {
    let chars: Vec<char> = skeleton.chars().collect();
    let mut output = String::new();
    let mut index = 0usize;
    while index < chars.len() {
        let symbol = chars[index];
        let mut end = index + 1;
        while end < chars.len() && chars[end] == symbol {
            end += 1;
        }
        if symbol != removed_symbol {
            for ch in &chars[index..end] {
                output.push(*ch);
            }
        }
        index = end;
    }
    output
}

fn insert_fractional_second(
    pattern: &str,
    width: usize,
    decimal_separator: &str,
) -> Option<String> {
    let chars: Vec<char> = pattern.chars().collect();
    let mut output = String::new();
    let mut in_quote = false;
    let mut index = 0usize;
    while index < chars.len() {
        let ch = chars[index];
        if ch == '\'' {
            output.push(ch);
            if index + 1 < chars.len() && chars[index + 1] == '\'' {
                output.push('\'');
                index += 2;
            } else {
                in_quote = !in_quote;
                index += 1;
            }
        } else if !in_quote && ch == 's' {
            let mut end = index + 1;
            while end < chars.len() && chars[end] == ch {
                end += 1;
            }
            for ch in &chars[index..end] {
                output.push(*ch);
            }
            output.push_str(decimal_separator);
            output.push_str(&"S".repeat(width));
            for ch in &chars[end..] {
                output.push(*ch);
            }
            return Some(output);
        } else {
            output.push(ch);
            index += 1;
        }
    }
    None
}

fn format_composed_skeleton(
    raw_skeleton: &str,
    canonical: &str,
    value: DateTimeValue,
    locale_data: &CldrDateTimeLocaleData,
    suppress_day_period: bool,
    date_time_join_style: &str,
) -> Result<String, Diagnostic> {
    let (date_skeleton, time_skeleton) = split_date_time_skeleton(canonical);
    if date_skeleton.is_empty() || time_skeleton.is_empty() {
        return Err(unsupported_skeleton(raw_skeleton));
    }
    let Some(date_pattern) = skeleton_pattern(&date_skeleton, locale_data) else {
        return Err(unsupported_skeleton(raw_skeleton));
    };
    let Some(mut time_pattern) = skeleton_pattern(&time_skeleton, locale_data) else {
        return Err(unsupported_skeleton(raw_skeleton));
    };
    if suppress_day_period {
        time_pattern = strip_day_period_pattern_fields(&time_pattern);
    }
    let date_part = format_pattern(&date_pattern, value, locale_data)?;
    let time_part = format_pattern(&time_pattern, value, locale_data)?;
    Ok(
        string_map_get(locale_data.date_time_formats, date_time_join_style)
            .or_else(|| string_map_get(locale_data.date_time_formats, "medium"))
            .unwrap_or("{1} {0}")
            .replace("{1}", &date_part)
            .replace("{0}", &time_part),
    )
}

fn unsupported_skeleton(skeleton: &str) -> Diagnostic {
    bad_option(format!("Unsupported CLDR date/time skeleton: {skeleton}."))
}

fn canonical_skeleton(
    skeleton: &str,
    locale_data: &CldrDateTimeLocaleData,
    hour_cycle: Option<&str>,
    value: DateTimeValue,
) -> Result<String, Diagnostic> {
    if let Some(body) = skeleton.strip_prefix(SEMANTIC_SKELETON_PREFIX) {
        let standard = semantic_skeleton_to_standard(body, locale_data, value)?;
        return canonical_standard_skeleton(&standard, locale_data, hour_cycle);
    }
    canonical_standard_skeleton(skeleton, locale_data, hour_cycle)
}

fn canonical_standard_skeleton(
    skeleton: &str,
    locale_data: &CldrDateTimeLocaleData,
    hour_cycle: Option<&str>,
) -> Result<String, Diagnostic> {
    let mut widths: Vec<(char, usize)> = Vec::new();
    let chars: Vec<char> = skeleton.chars().collect();
    let mut index = 0usize;
    while index < chars.len() {
        let symbol = chars[index];
        if !symbol.is_ascii_alphabetic() {
            return Err(bad_option(
                "Date/time skeleton must contain only ASCII pattern letters.",
            ));
        }
        let mut end = index + 1;
        while end < chars.len() && chars[end] == symbol {
            end += 1;
        }
        let width = end - index;
        if width > MAX_SKELETON_FIELD_WIDTH {
            return Err(bad_option("Date/time skeleton field width is too large."));
        }
        if symbol == 'C' {
            apply_c_hour_format(&mut widths, locale_data, hour_cycle, width);
        } else {
            let normalized = normalize_skeleton_symbol(symbol, locale_data, hour_cycle);
            set_skeleton_width(&mut widths, normalized, width);
        }
        index = end;
    }
    let mut canonical = String::new();
    for symbol in SKELETON_FIELD_ORDER.chars() {
        if let Some((_, width)) = widths.iter().find(|(candidate, _)| *candidate == symbol) {
            for _ in 0..*width {
                canonical.push(symbol);
            }
        }
    }
    if canonical.is_empty() {
        return Err(bad_option("Date/time skeleton must not be empty."));
    }
    Ok(canonical)
}

fn semantic_skeleton_to_standard(
    body: &str,
    locale_data: &CldrDateTimeLocaleData,
    value: DateTimeValue,
) -> Result<String, Diagnostic> {
    let options = parse_semantic_skeleton_options(body)?;
    let fields = parse_semantic_skeleton_fields(&options)?;
    validate_semantic_skeleton(&fields, &options)?;
    let length = semantic_option(
        &options,
        "length",
        "medium",
        &["full", "long", "medium", "short"],
    )?;
    let alignment = semantic_option(&options, "alignment", "inline", &["inline", "column"])?;
    let year_style = semantic_option(
        &options,
        "yearstyle",
        "auto",
        &["auto", "full", "with-era", "numeric", "2-digit"],
    )?;
    let era_style = semantic_option(
        &options,
        "erastyle",
        "auto",
        SEMANTIC_TEXT_STYLE_VALUES.as_slice(),
    )?;
    let month_style = semantic_option(
        &options,
        "monthstyle",
        "auto",
        SEMANTIC_DATE_STYLE_VALUES.as_slice(),
    )?;
    let quarter_style = semantic_option(
        &options,
        "quarterstyle",
        "auto",
        SEMANTIC_DATE_STYLE_VALUES.as_slice(),
    )?;
    let day_style = semantic_option(
        &options,
        "daystyle",
        "auto",
        SEMANTIC_NUMERIC_STYLE_VALUES.as_slice(),
    )?;
    let weekday_style = semantic_option(
        &options,
        "weekdaystyle",
        "auto",
        SEMANTIC_TEXT_STYLE_VALUES.as_slice(),
    )?;
    let day_period_style = semantic_option(
        &options,
        "dayperiodstyle",
        "auto",
        SEMANTIC_TEXT_STYLE_VALUES.as_slice(),
    )?;
    semantic_option(
        &options,
        "hourstyle",
        "auto",
        SEMANTIC_NUMERIC_STYLE_VALUES.as_slice(),
    )?;
    semantic_option(
        &options,
        "minutestyle",
        "auto",
        SEMANTIC_NUMERIC_STYLE_VALUES.as_slice(),
    )?;
    semantic_option(
        &options,
        "secondstyle",
        "auto",
        SEMANTIC_NUMERIC_STYLE_VALUES.as_slice(),
    )?;
    let time_precision = semantic_option(
        &options,
        "timeprecision",
        "second",
        &[
            "hour",
            "minute",
            "minute-optional",
            "second",
            "fractional-second",
        ],
    )?;
    let time_style = semantic_option(
        &options,
        "timestyle",
        "auto",
        &["auto", "short", "medium", "long", "full"],
    )?;
    let effective_time_precision = semantic_time_style_precision(&time_style, &time_precision);
    let hour_cycle = semantic_option(
        &options,
        "hourcycle",
        "auto",
        &["auto", "h11", "h12", "h23", "h24", "clock12", "clock24"],
    )?;
    let zone_style = semantic_option(
        &options,
        "zonestyle",
        "auto",
        &["auto", "generic", "specific", "location", "offset"],
    )?;
    let effective_zone_style = semantic_time_style_zone_style(&time_style, &zone_style);
    let effective_zone_standalone = fields.len() == 1 || time_style == "full";
    let effective_zone_length = if semantic_time_style_has_zone(&time_style) {
        time_style.as_str()
    } else {
        length.as_str()
    };
    let date_widths = semantic_date_field_widths(locale_data, &length);
    let mut standard = String::new();
    if semantic_has_field(&fields, "era") {
        standard.push_str(&semantic_era_skeleton(&date_widths, &length, &era_style));
    }
    if semantic_has_field(&fields, "year") {
        standard.push_str(&semantic_year_skeleton(
            &date_widths,
            &year_style,
            !semantic_has_field(&fields, "era"),
        ));
    }
    if semantic_has_field(&fields, "quarter") {
        standard.push_str(&semantic_quarter_skeleton(
            &fields,
            &length,
            &alignment,
            &quarter_style,
        ));
    }
    if semantic_has_field(&fields, "month") {
        standard.push_str(&semantic_month_skeleton(
            &fields,
            &date_widths,
            &length,
            &alignment,
            &month_style,
        ));
    }
    if semantic_has_field(&fields, "weekofmonth") {
        standard.push('W');
    }
    if semantic_has_field(&fields, "day") {
        standard.push_str(&semantic_day_skeleton(&date_widths, &alignment, &day_style));
    }
    if semantic_has_field(&fields, "dayofyear") {
        for _ in 0..(if alignment == "column" { 3 } else { 1 }) {
            standard.push('D');
        }
    }
    if semantic_has_field(&fields, "dayofweekinmonth") {
        for _ in 0..(if alignment == "column" { 2 } else { 1 }) {
            standard.push('F');
        }
    }
    if semantic_has_field(&fields, "modifiedjulianday") {
        for _ in 0..(if alignment == "column" { 6 } else { 1 }) {
            standard.push('g');
        }
    }
    if semantic_has_field(&fields, "weekday") {
        standard.push_str(&semantic_weekday_skeleton(&fields, &length, &weekday_style));
    }
    if semantic_has_field(&fields, "weekofyear") {
        standard.push_str(if alignment == "column" { "ww" } else { "w" });
    }
    if semantic_has_field(&fields, "dayperiod") {
        standard.push_str(&semantic_day_period_skeleton(&length, &day_period_style));
    }
    if has_semantic_time_components(&fields) {
        standard.push_str(&semantic_explicit_time_skeleton(
            &fields,
            &hour_cycle,
            &alignment,
            &options,
        )?);
    }
    if semantic_has_field(&fields, "time") {
        standard.push_str(&semantic_time_skeleton(
            effective_time_precision,
            &hour_cycle,
            &alignment,
            value,
            &options,
        )?);
    }
    if semantic_has_field(&fields, "zone") {
        standard.push_str(&semantic_zone_skeleton(
            effective_zone_style,
            effective_zone_standalone,
            effective_zone_length,
        ));
    }
    if standard.is_empty() {
        return Err(bad_option(
            "Date/time semantic skeleton must include at least one field.",
        ));
    }
    Ok(standard)
}

fn parse_semantic_skeleton_options(body: &str) -> Result<Vec<(String, String)>, Diagnostic> {
    let parts: Vec<&str> = body
        .split(';')
        .map(str::trim)
        .filter(|part| !part.is_empty())
        .collect();
    if parts.is_empty() {
        return Err(bad_option(
            "Date/time semantic skeleton must include fields.",
        ));
    }
    let mut options: Vec<(String, String)> = Vec::new();
    let mut implicit_date_style: Option<String> = None;
    let mut implicit_time_fields = false;
    for (index, part) in parts.iter().enumerate() {
        let (raw_key, raw_value) = if let Some((key, value)) = part.split_once('=') {
            (key, value)
        } else if index == 0 {
            ("fields", *part)
        } else {
            ("", *part)
        };
        let raw_key_alias = semantic_normalize(raw_key);
        let key = semantic_normalize_option_key(raw_key);
        let value = semantic_normalize_option_value(&key, raw_value);
        if key.is_empty()
            || value.is_empty()
            || !semantic_contains(SEMANTIC_OPTION_KEYS.as_slice(), &key)
            || options.iter().any(|(candidate, _)| candidate == &key)
        {
            return Err(bad_option("Invalid date/time semantic skeleton option."));
        }
        if raw_key_alias == "style" || raw_key_alias == "datestyle" || raw_key_alias == "datelength"
        {
            implicit_date_style = Some(value.clone());
        }
        if raw_key_alias == "timestyle" {
            implicit_time_fields = true;
        }
        options.push((key, value));
    }
    if !options.iter().any(|(key, _)| key == "fields") {
        let fields = implicit_semantic_fields(
            implicit_date_style.as_deref(),
            implicit_time_fields,
            semantic_option_value(&options, "timestyle"),
        );
        if !fields.is_empty() {
            options.push(("fields".to_string(), fields));
        }
    }
    Ok(options)
}

fn implicit_semantic_fields(
    date_style: Option<&str>,
    has_time_style: bool,
    time_style: Option<&str>,
) -> String {
    let date_fields = if date_style == Some("full") {
        "date,weekday"
    } else {
        "date"
    };
    if date_style.is_some() && has_time_style {
        return if time_style == Some("long") || time_style == Some("full") {
            format!("{date_fields},time,zone")
        } else {
            format!("{date_fields},time")
        };
    }
    if date_style.is_some() {
        return date_fields.to_string();
    }
    if has_time_style {
        return if time_style == Some("long") || time_style == Some("full") {
            "time,zone"
        } else {
            "time"
        }
        .to_string();
    }
    String::new()
}

fn semantic_normalize_option_key(value: &str) -> String {
    let normalized = semantic_normalize(value);
    if normalized == "style" || normalized == "datestyle" || normalized == "datelength" {
        return "length".to_string();
    }
    if normalized == "precision" {
        return "timeprecision".to_string();
    }
    if normalized == "timestyle" {
        return "timestyle".to_string();
    }
    if normalized == "hour12" {
        return "hourcycle".to_string();
    }
    if normalized == "zone" || normalized == "timezonename" || normalized == "timezonestyle" {
        return "zonestyle".to_string();
    }
    if normalized == "fractionalseconddigits" {
        return "fractionalsecond".to_string();
    }
    match normalized.as_str() {
        "era" => "erastyle".to_string(),
        "year" => "yearstyle".to_string(),
        "month" => "monthstyle".to_string(),
        "quarter" => "quarterstyle".to_string(),
        "day" => "daystyle".to_string(),
        "weekday" => "weekdaystyle".to_string(),
        "dayperiod" => "dayperiodstyle".to_string(),
        "hour" => "hourstyle".to_string(),
        "minute" => "minutestyle".to_string(),
        "second" => "secondstyle".to_string(),
        _ => normalized,
    }
}

fn semantic_normalize_option_value(key: &str, value: &str) -> String {
    if key == "fields" {
        return value.trim().to_ascii_lowercase();
    }
    let normalized = semantic_normalize(value);
    if key == "yearstyle" && normalized == "withera" {
        return "with-era".to_string();
    }
    if semantic_contains(SEMANTIC_STYLE_OPTION_KEYS.as_slice(), key)
        && (normalized == "2digit" || normalized == "twodigit")
    {
        return "2-digit".to_string();
    }
    if semantic_contains(SEMANTIC_STYLE_OPTION_KEYS.as_slice(), key) && normalized == "wide" {
        return "long".to_string();
    }
    if semantic_contains(SEMANTIC_STYLE_OPTION_KEYS.as_slice(), key) && normalized == "abbreviated"
    {
        return "short".to_string();
    }
    if key == "timeprecision" && normalized == "short" {
        return "minute".to_string();
    }
    if key == "timeprecision" && normalized == "medium" {
        return "second".to_string();
    }
    if key == "timeprecision" && normalized == "minuteoptional" {
        return "minute-optional".to_string();
    }
    if key == "timeprecision" && normalized == "fractionalsecond" {
        return "fractional-second".to_string();
    }
    if key == "zonestyle" && (normalized == "shortoffset" || normalized == "longoffset") {
        return "offset".to_string();
    }
    if key == "zonestyle" && (normalized == "shortgeneric" || normalized == "longgeneric") {
        return "generic".to_string();
    }
    if key == "zonestyle" && (normalized == "short" || normalized == "long") {
        return "specific".to_string();
    }
    if key == "hourcycle" && normalized == "true" {
        return "clock12".to_string();
    }
    if key == "hourcycle" && normalized == "false" {
        return "clock24".to_string();
    }
    normalized
}

fn parse_semantic_skeleton_fields(options: &[(String, String)]) -> Result<Vec<String>, Diagnostic> {
    let Some(fields_text) = semantic_option_value(options, "fields") else {
        return Err(bad_option(
            "Date/time semantic skeleton must include fields.",
        ));
    };
    let mut fields: Vec<String> = Vec::new();
    for field in fields_text.split(',') {
        let normalized = semantic_normalize_field(field);
        let canonical_fields = if normalized == "date" || normalized == "yearmonthday" {
            vec!["year".to_string(), "month".to_string(), "day".to_string()]
        } else if normalized == "eradate" || normalized == "erayearmonthday" {
            vec![
                "era".to_string(),
                "year".to_string(),
                "month".to_string(),
                "day".to_string(),
            ]
        } else if normalized == "eradateweekday"
            || normalized == "weekdayeradate"
            || normalized == "erayearmonthdayweekday"
            || normalized == "weekdayerayearmonthday"
        {
            vec![
                "era".to_string(),
                "year".to_string(),
                "month".to_string(),
                "day".to_string(),
                "weekday".to_string(),
            ]
        } else if normalized == "eradatetime" || normalized == "erayearmonthdaytime" {
            vec![
                "era".to_string(),
                "year".to_string(),
                "month".to_string(),
                "day".to_string(),
                "time".to_string(),
            ]
        } else if normalized == "eradatetimeweekday"
            || normalized == "weekdayeradatetime"
            || normalized == "erayearmonthdaytimeweekday"
            || normalized == "weekdayerayearmonthdaytime"
        {
            vec![
                "era".to_string(),
                "year".to_string(),
                "month".to_string(),
                "day".to_string(),
                "weekday".to_string(),
                "time".to_string(),
            ]
        } else if normalized == "datetime" || normalized == "yearmonthdaytime" {
            vec![
                "year".to_string(),
                "month".to_string(),
                "day".to_string(),
                "time".to_string(),
            ]
        } else if normalized == "datetimeweekday"
            || normalized == "weekdaydatetime"
            || normalized == "yearmonthdaytimeweekday"
            || normalized == "weekdayyearmonthdaytime"
        {
            vec![
                "year".to_string(),
                "month".to_string(),
                "day".to_string(),
                "weekday".to_string(),
                "time".to_string(),
            ]
        } else if normalized == "datetimeweekdayzone"
            || normalized == "weekdaydatetimezone"
            || normalized == "zoneddatetimeweekday"
            || normalized == "zonedweekdaydatetime"
            || normalized == "yearmonthdaytimeweekdayzone"
            || normalized == "weekdayyearmonthdaytimezone"
            || normalized == "zonedyearmonthdaytimeweekday"
            || normalized == "zonedweekdayyearmonthdaytime"
        {
            vec![
                "year".to_string(),
                "month".to_string(),
                "day".to_string(),
                "weekday".to_string(),
                "time".to_string(),
                "zone".to_string(),
            ]
        } else if normalized == "eradatetimezone"
            || normalized == "zonederadatetime"
            || normalized == "erayearmonthdaytimezone"
            || normalized == "zonederayearmonthdaytime"
        {
            vec![
                "era".to_string(),
                "year".to_string(),
                "month".to_string(),
                "day".to_string(),
                "time".to_string(),
                "zone".to_string(),
            ]
        } else if normalized == "eradatetimeweekdayzone"
            || normalized == "weekdayeradatetimezone"
            || normalized == "zonederadatetimeweekday"
            || normalized == "zonedweekdayeradatetime"
            || normalized == "erayearmonthdaytimeweekdayzone"
            || normalized == "weekdayerayearmonthdaytimezone"
            || normalized == "zonederayearmonthdaytimeweekday"
            || normalized == "zonedweekdayerayearmonthdaytime"
        {
            vec![
                "era".to_string(),
                "year".to_string(),
                "month".to_string(),
                "day".to_string(),
                "weekday".to_string(),
                "time".to_string(),
                "zone".to_string(),
            ]
        } else if normalized == "dateweekday"
            || normalized == "weekdaydate"
            || normalized == "yearmonthdayweekday"
            || normalized == "weekdayyearmonthday"
        {
            vec![
                "year".to_string(),
                "month".to_string(),
                "day".to_string(),
                "weekday".to_string(),
            ]
        } else if normalized == "datetimezone"
            || normalized == "zoneddatetime"
            || normalized == "yearmonthdaytimezone"
            || normalized == "zonedyearmonthdaytime"
        {
            vec![
                "year".to_string(),
                "month".to_string(),
                "day".to_string(),
                "time".to_string(),
                "zone".to_string(),
            ]
        } else if normalized == "yearmonth" {
            vec!["year".to_string(), "month".to_string()]
        } else if normalized == "erayearmonth" {
            vec!["era".to_string(), "year".to_string(), "month".to_string()]
        } else if normalized == "yearquarter" {
            vec!["year".to_string(), "quarter".to_string()]
        } else if normalized == "erayearquarter" {
            vec!["era".to_string(), "year".to_string(), "quarter".to_string()]
        } else if normalized == "yearweek" {
            vec!["year".to_string(), "weekofyear".to_string()]
        } else if normalized == "erayearweek" {
            vec![
                "era".to_string(),
                "year".to_string(),
                "weekofyear".to_string(),
            ]
        } else if normalized == "erayear" {
            vec!["era".to_string(), "year".to_string()]
        } else if normalized == "monthweek" {
            vec!["month".to_string(), "weekofmonth".to_string()]
        } else if normalized == "yearmonthweek" {
            vec![
                "year".to_string(),
                "month".to_string(),
                "weekofmonth".to_string(),
            ]
        } else if normalized == "erayearmonthweek" {
            vec![
                "era".to_string(),
                "year".to_string(),
                "month".to_string(),
                "weekofmonth".to_string(),
            ]
        } else if normalized == "monthday" {
            vec!["month".to_string(), "day".to_string()]
        } else {
            vec![normalized]
        };
        for canonical in canonical_fields {
            if !semantic_contains(SEMANTIC_FIELD_ORDER.as_slice(), &canonical)
                || fields.iter().any(|candidate| candidate == &canonical)
            {
                return Err(bad_option("Invalid date/time semantic skeleton field."));
            }
            fields.push(canonical);
        }
    }
    if fields.is_empty() {
        return Err(bad_option(
            "Date/time semantic skeleton must include fields.",
        ));
    }
    Ok(fields)
}

fn semantic_normalize_field(value: &str) -> String {
    let normalized = semantic_normalize(value);
    if normalized == "dayofmonth" {
        return "day".to_string();
    }
    if normalized == "dayofweek" {
        return "weekday".to_string();
    }
    if normalized == "monthofyear" {
        return "month".to_string();
    }
    if normalized == "quarterofyear" {
        return "quarter".to_string();
    }
    if normalized == "yearofera" {
        return "year".to_string();
    }
    if normalized == "week" {
        return "weekofyear".to_string();
    }
    if normalized == "weekofyear" {
        return "weekofyear".to_string();
    }
    if normalized == "weekofmonth" {
        return "weekofmonth".to_string();
    }
    if normalized == "dayofyear" {
        return "dayofyear".to_string();
    }
    if normalized == "dayofweekinmonth" {
        return "dayofweekinmonth".to_string();
    }
    if normalized == "modifiedjulianday" {
        return "modifiedjulianday".to_string();
    }
    if normalized == "millisecondsinday" {
        return "millisecondsinday".to_string();
    }
    if normalized == "fractionalseconddigits" {
        return "fractionalsecond".to_string();
    }
    if normalized == "dayperiod" {
        return "dayperiod".to_string();
    }
    if normalized == "hourofday" {
        return "hour".to_string();
    }
    if normalized == "minuteofhour" {
        return "minute".to_string();
    }
    if normalized == "secondofminute" {
        return "second".to_string();
    }
    if normalized == "timezonename" {
        return "zone".to_string();
    }
    if normalized == "timezone" {
        return "zone".to_string();
    }
    normalized
}

fn validate_semantic_skeleton(
    fields: &[String],
    options: &[(String, String)],
) -> Result<(), Diagnostic> {
    let date_key = semantic_field_set_key(fields, SEMANTIC_DATE_FIELD_ORDER.as_slice());
    let time_key = semantic_field_set_key(fields, SEMANTIC_TIME_FIELD_ORDER.as_slice());
    let has_date_fields = !date_key.is_empty();
    let has_explicit_time = !time_key.is_empty();
    let has_time = semantic_has_field(fields, "time") || has_explicit_time;
    let has_zone = semantic_has_field(fields, "zone");
    let has_day_period = semantic_has_field(fields, "dayperiod");
    let valid_date_fields = if has_time || has_zone {
        !has_date_fields || semantic_contains(SEMANTIC_DATE_FIELD_SETS.as_slice(), &date_key)
    } else {
        !has_date_fields
            || semantic_contains(SEMANTIC_DATE_FIELD_SETS.as_slice(), &date_key)
            || semantic_contains(SEMANTIC_CALENDAR_PERIOD_FIELD_SETS.as_slice(), &date_key)
    };
    let valid_field_set = if has_day_period {
        valid_date_fields && (has_time || !has_zone)
    } else if has_time || has_zone {
        !has_date_fields || semantic_contains(SEMANTIC_DATE_FIELD_SETS.as_slice(), &date_key)
    } else {
        semantic_contains(SEMANTIC_DATE_FIELD_SETS.as_slice(), &date_key)
            || semantic_contains(SEMANTIC_CALENDAR_PERIOD_FIELD_SETS.as_slice(), &date_key)
    };
    if !valid_field_set {
        return Err(bad_option("Invalid date/time semantic skeleton field set."));
    }
    if semantic_has_field(fields, "time") && has_explicit_time {
        return Err(bad_option(
            "time field cannot be combined with explicit time component fields.",
        ));
    }
    if semantic_option_value(options, "timestyle").is_some()
        && semantic_option_value(options, "timeprecision").is_some()
    {
        return Err(bad_option(
            "timeStyle cannot be combined with timePrecision.",
        ));
    }
    let time_style = semantic_option_value(options, "timestyle");
    if time_style.is_some() && !semantic_has_field(fields, "time") {
        return Err(bad_option("timeStyle requires the time field."));
    }
    if semantic_time_style_has_zone(time_style.unwrap_or("")) && !has_zone {
        return Err(bad_option("timeStyle=long/full requires the zone field."));
    }
    if semantic_time_style_has_zone(time_style.unwrap_or(""))
        && semantic_option_value(options, "zonestyle").is_some()
    {
        return Err(bad_option(
            "timeStyle=long/full cannot be combined with zoneStyle.",
        ));
    }
    if has_explicit_time && !semantic_contains(SEMANTIC_TIME_FIELD_SETS.as_slice(), &time_key) {
        return Err(bad_option(
            "Invalid date/time semantic skeleton time field set.",
        ));
    }
    if has_explicit_time && semantic_option_value(options, "timeprecision").is_some() {
        return Err(bad_option("timePrecision requires the time field."));
    }
    if has_explicit_time
        && semantic_option_value(options, "fractionalsecond").is_some()
        && !semantic_has_field(fields, "fractionalsecond")
    {
        return Err(bad_option(
            "fractionalSecond requires the fractionalSecond field.",
        ));
    }
    if semantic_has_field(fields, "fractionalsecond") {
        semantic_fractional_second_width(options)?;
    }
    if has_explicit_time
        && !semantic_has_field(fields, "hour")
        && (semantic_option_value(options, "hourcycle").is_some() || has_day_period)
    {
        return Err(bad_option(
            "hourCycle and dayPeriod require the hour field.",
        ));
    }
    if !semantic_has_field(fields, "hour") && semantic_option_value(options, "hourstyle").is_some()
    {
        return Err(bad_option("hourStyle requires the hour field."));
    }
    if !semantic_has_field(fields, "minute")
        && semantic_option_value(options, "minutestyle").is_some()
    {
        return Err(bad_option("minuteStyle requires the minute field."));
    }
    if !semantic_has_field(fields, "second")
        && semantic_option_value(options, "secondstyle").is_some()
    {
        return Err(bad_option("secondStyle requires the second field."));
    }
    if !semantic_has_field(fields, "year") && semantic_option_value(options, "yearstyle").is_some()
    {
        return Err(bad_option("yearStyle requires the year field."));
    }
    if !semantic_has_field(fields, "era") && semantic_option_value(options, "erastyle").is_some() {
        return Err(bad_option("eraStyle requires the era field."));
    }
    if !semantic_has_field(fields, "month")
        && semantic_option_value(options, "monthstyle").is_some()
    {
        return Err(bad_option("monthStyle requires the month field."));
    }
    if !semantic_has_field(fields, "quarter")
        && semantic_option_value(options, "quarterstyle").is_some()
    {
        return Err(bad_option("quarterStyle requires the quarter field."));
    }
    if !semantic_has_field(fields, "day") && semantic_option_value(options, "daystyle").is_some() {
        return Err(bad_option("dayStyle requires the day field."));
    }
    if !semantic_has_field(fields, "weekday")
        && semantic_option_value(options, "weekdaystyle").is_some()
    {
        return Err(bad_option("weekdayStyle requires the weekday field."));
    }
    if !has_day_period && semantic_option_value(options, "dayperiodstyle").is_some() {
        return Err(bad_option("dayPeriodStyle requires the dayPeriod field."));
    }
    if !has_time
        && (semantic_option_value(options, "timeprecision").is_some()
            || semantic_option_value(options, "timestyle").is_some()
            || semantic_option_value(options, "fractionalsecond").is_some()
            || semantic_option_value(options, "hourcycle").is_some())
    {
        return Err(bad_option(
            "timePrecision and hourCycle require the time field.",
        ));
    }
    if !has_zone && semantic_option_value(options, "zonestyle").is_some() {
        return Err(bad_option("zoneStyle requires the zone field."));
    }
    if !(semantic_has_field(fields, "year")
        || semantic_has_field(fields, "quarter")
        || semantic_has_field(fields, "month")
        || semantic_has_field(fields, "day")
        || semantic_has_field(fields, "dayofyear")
        || semantic_has_field(fields, "dayofweekinmonth")
        || semantic_has_field(fields, "modifiedjulianday")
        || has_time)
        && semantic_option_value(options, "alignment").is_some()
    {
        return Err(bad_option("alignment requires a date or time field."));
    }
    Ok(())
}

fn semantic_option(
    options: &[(String, String)],
    key: &str,
    fallback: &str,
    allowed_values: &[&str],
) -> Result<String, Diagnostic> {
    let value = semantic_option_value(options, key).unwrap_or(fallback);
    if !allowed_values.iter().any(|allowed| *allowed == value) {
        return Err(bad_option(format!(
            "Date/time semantic skeleton {key} must be one of {}.",
            allowed_values.join(", ")
        )));
    }
    Ok(value.to_string())
}

fn semantic_option_value<'a>(options: &'a [(String, String)], key: &str) -> Option<&'a str> {
    options
        .iter()
        .find(|(candidate, _)| candidate == key)
        .map(|(_, value)| value.as_str())
}

fn semantic_normalize(value: &str) -> String {
    value
        .trim()
        .chars()
        .filter(|ch| *ch != '-' && *ch != '_')
        .collect::<String>()
        .to_ascii_lowercase()
}

fn semantic_contains(values: &[&str], value: &str) -> bool {
    values.iter().any(|candidate| *candidate == value)
}

fn semantic_has_field(fields: &[String], field: &str) -> bool {
    fields.iter().any(|candidate| candidate == field)
}

fn semantic_field_set_key(fields: &[String], order: &[&str]) -> String {
    let mut output: Vec<&str> = Vec::new();
    for field in order {
        if semantic_has_field(fields, field) {
            output.push(field);
        }
    }
    output.join(",")
}

fn semantic_date_field_widths(
    locale_data: &CldrDateTimeLocaleData,
    length: &str,
) -> Vec<(char, usize)> {
    let pattern = string_map_get(locale_data.date_formats, length).unwrap_or("");
    let mut widths: Vec<(char, usize)> = Vec::new();
    for (symbol, width) in pattern_field_runs(pattern) {
        if symbol == 'G' || is_year_field(symbol) || is_month_field(symbol) || symbol == 'd' {
            set_skeleton_width(&mut widths, symbol, width);
        }
    }
    if !widths.iter().any(|(symbol, _)| is_year_field(*symbol)) {
        set_skeleton_width(&mut widths, 'y', if length == "short" { 2 } else { 1 });
    }
    if !widths.iter().any(|(symbol, _)| is_month_field(*symbol)) {
        set_skeleton_width(
            &mut widths,
            'M',
            if is_wide_length(length) {
                4
            } else if length == "medium" {
                3
            } else {
                1
            },
        );
    }
    if semantic_width(&widths, 'd').is_none() {
        set_skeleton_width(&mut widths, 'd', 1);
    }
    widths
}

fn pattern_field_runs(pattern: &str) -> Vec<(char, usize)> {
    let chars: Vec<char> = pattern.chars().collect();
    let mut fields = Vec::new();
    let mut in_quote = false;
    let mut index = 0usize;
    while index < chars.len() {
        let symbol = chars[index];
        if symbol == '\'' {
            if index + 1 < chars.len() && chars[index + 1] == '\'' {
                index += 2;
            } else {
                in_quote = !in_quote;
                index += 1;
            }
        } else if !in_quote && symbol.is_ascii_alphabetic() {
            let mut end = index + 1;
            while end < chars.len() && chars[end] == symbol {
                end += 1;
            }
            fields.push((symbol, end - index));
            index = end;
        } else {
            index += 1;
        }
    }
    fields
}

fn semantic_era_skeleton(date_widths: &[(char, usize)], length: &str, era_style: &str) -> String {
    let width = if era_style == "auto" {
        semantic_width(date_widths, 'G').unwrap_or(if is_wide_length(length) { 4 } else { 1 })
    } else {
        era_style_width(era_style)
    };
    "G".repeat(width)
}

fn era_style_width(style: &str) -> usize {
    if style == "long" {
        4
    } else if style == "narrow" {
        5
    } else {
        1
    }
}

fn semantic_year_skeleton(
    date_widths: &[(char, usize)],
    year_style: &str,
    include_era: bool,
) -> String {
    let year_symbol = ['y', 'u', 'r']
        .into_iter()
        .find(|symbol| semantic_width(date_widths, *symbol).is_some())
        .unwrap_or('y');
    let source_width = semantic_width(date_widths, year_symbol).unwrap_or(1);
    let year_width = semantic_year_width(source_width, year_style);
    let mut skeleton = String::new();
    if include_era {
        if let Some(width) = semantic_width(date_widths, 'G') {
            for _ in 0..width {
                skeleton.push('G');
            }
        }
    }
    if include_era && year_style == "with-era" && semantic_width(date_widths, 'G').is_none() {
        skeleton.push('G');
    }
    for _ in 0..year_width {
        skeleton.push(year_symbol);
    }
    skeleton
}

fn semantic_year_width(source_width: usize, year_style: &str) -> usize {
    if year_style == "auto" {
        source_width
    } else if year_style == "2-digit" {
        2
    } else if year_style == "numeric" {
        1
    } else if source_width == 2 {
        1
    } else {
        source_width
    }
}

fn semantic_quarter_skeleton(
    fields: &[String],
    length: &str,
    alignment: &str,
    quarter_style: &str,
) -> String {
    let symbol = if fields.len() == 1 { 'q' } else { 'Q' };
    let mut width = if quarter_style == "auto" {
        length_style_width(length)
    } else {
        date_field_style_width(quarter_style)
    };
    if alignment == "column" && width < 3 {
        width = width.max(2);
    }
    symbol.to_string().repeat(width)
}

fn semantic_month_skeleton(
    fields: &[String],
    date_widths: &[(char, usize)],
    length: &str,
    alignment: &str,
    month_style: &str,
) -> String {
    let (symbol, mut width) = if fields.len() == 1 {
        (
            'L',
            if month_style == "auto" {
                length_style_width(length)
            } else {
                date_field_style_width(month_style)
            },
        )
    } else {
        let symbol = if semantic_width(date_widths, 'M').is_some() {
            'M'
        } else if semantic_width(date_widths, 'L').is_some() {
            'L'
        } else {
            'M'
        };
        (
            symbol,
            if month_style == "auto" {
                semantic_width(date_widths, symbol).unwrap_or(length_style_width(length))
            } else {
                date_field_style_width(month_style)
            },
        )
    };
    if alignment == "column" && width < 3 {
        width = width.max(2);
    }
    let mut skeleton = String::new();
    for _ in 0..width {
        skeleton.push(symbol);
    }
    skeleton
}

fn semantic_day_skeleton(
    date_widths: &[(char, usize)],
    alignment: &str,
    day_style: &str,
) -> String {
    let mut width = if day_style == "auto" {
        semantic_width(date_widths, 'd').unwrap_or(1)
    } else {
        date_field_style_width(day_style)
    };
    if alignment == "column" && width < 3 {
        width = width.max(2);
    }
    "d".repeat(width)
}

fn length_style_width(length: &str) -> usize {
    if is_wide_length(length) {
        4
    } else if length == "medium" {
        3
    } else {
        1
    }
}

fn is_wide_length(length: &str) -> bool {
    length == "full" || length == "long"
}

fn date_field_style_width(style: &str) -> usize {
    match style {
        "numeric" => 1,
        "2-digit" => 2,
        "short" => 3,
        "long" => 4,
        _ => 5,
    }
}

fn semantic_weekday_skeleton(fields: &[String], length: &str, weekday_style: &str) -> String {
    if weekday_style == "short" {
        return "EEE".to_string();
    }
    if weekday_style == "long" {
        return "EEEE".to_string();
    }
    if weekday_style == "narrow" {
        return "EEEEE".to_string();
    }
    if fields.len() == 1 && length == "short" {
        "EEEEE".to_string()
    } else if is_wide_length(length) {
        "EEEE".to_string()
    } else {
        "EEE".to_string()
    }
}

fn semantic_day_period_skeleton(length: &str, day_period_style: &str) -> String {
    let style = if day_period_style == "auto" {
        length
    } else {
        day_period_style
    };
    "B".repeat(if is_wide_length(style) {
        4
    } else if style == "narrow" || (day_period_style == "auto" && length == "short") {
        5
    } else {
        1
    })
}

fn has_semantic_time_components(fields: &[String]) -> bool {
    semantic_has_field(fields, "hour")
        || semantic_has_field(fields, "minute")
        || semantic_has_field(fields, "second")
        || semantic_has_field(fields, "fractionalsecond")
        || semantic_has_field(fields, "millisecondsinday")
}

fn semantic_explicit_time_skeleton(
    fields: &[String],
    hour_cycle: &str,
    alignment: &str,
    options: &[(String, String)],
) -> Result<String, Diagnostic> {
    let has_hour = semantic_has_field(fields, "hour");
    let has_minute = semantic_has_field(fields, "minute");
    let has_second = semantic_has_field(fields, "second");
    let has_fractional_second = semantic_has_field(fields, "fractionalsecond");
    let has_milliseconds_in_day = semantic_has_field(fields, "millisecondsinday");
    let mut skeleton = String::new();
    if has_hour {
        for _ in 0..semantic_numeric_field_width(
            options,
            "hourstyle",
            if alignment == "column" { 2 } else { 1 },
        ) {
            skeleton.push(semantic_hour_symbol(hour_cycle));
        }
    }
    if has_minute {
        let fallback_width = if !has_hour && !has_second && alignment == "column" {
            2
        } else {
            1
        };
        for _ in 0..semantic_numeric_field_width(options, "minutestyle", fallback_width) {
            skeleton.push('m');
        }
    }
    if has_second {
        let fallback_width = if !has_hour && !has_minute && alignment == "column" {
            2
        } else {
            1
        };
        for _ in 0..semantic_numeric_field_width(options, "secondstyle", fallback_width) {
            skeleton.push('s');
        }
    }
    if has_fractional_second {
        for _ in 0..semantic_fractional_second_width(options)? {
            skeleton.push('S');
        }
    }
    if has_milliseconds_in_day {
        for _ in 0..(if alignment == "column" { 8 } else { 1 }) {
            skeleton.push('A');
        }
    }
    Ok(skeleton)
}

fn semantic_numeric_field_width(
    options: &[(String, String)],
    key: &str,
    fallback_width: usize,
) -> usize {
    match semantic_option_value(options, key).unwrap_or("auto") {
        "auto" => fallback_width,
        "2-digit" => 2,
        _ => 1,
    }
}

fn semantic_fractional_second_width(options: &[(String, String)]) -> Result<usize, Diagnostic> {
    semantic_option_value(options, "fractionalsecond")
        .and_then(|value| value.parse::<usize>().ok())
        .filter(|width| (1..=9).contains(width))
        .ok_or_else(|| {
            bad_option(
                "Date/time semantic skeleton fractionalSecond must be an integer from 1 to 9.",
            )
        })
}

fn semantic_time_skeleton(
    time_precision: &str,
    hour_cycle: &str,
    alignment: &str,
    value: DateTimeValue,
    options: &[(String, String)],
) -> Result<String, Diagnostic> {
    let hour_symbol = semantic_hour_symbol(hour_cycle);
    let mut skeleton = String::new();
    for _ in 0..(if alignment == "column" { 2 } else { 1 }) {
        skeleton.push(hour_symbol);
    }
    if matches!(time_precision, "minute" | "second" | "fractional-second") {
        skeleton.push('m');
    }
    if time_precision == "minute-optional" && value.minute != 0 {
        skeleton.push('m');
    }
    if matches!(time_precision, "second" | "fractional-second") {
        skeleton.push('s');
    }
    if time_precision == "fractional-second" {
        for _ in 0..semantic_fractional_second_width(options)? {
            skeleton.push('S');
        }
    } else if semantic_option_value(options, "fractionalsecond").is_some() {
        return Err(bad_option(
            "fractionalSecond requires timePrecision=fractional-second.",
        ));
    }
    Ok(skeleton)
}

fn semantic_time_style_precision<'a>(time_style: &str, time_precision: &'a str) -> &'a str {
    match time_style {
        "short" => "minute",
        "medium" | "long" | "full" => "second",
        _ => time_precision,
    }
}

fn semantic_time_style_zone_style<'a>(time_style: &str, zone_style: &'a str) -> &'a str {
    if semantic_time_style_has_zone(time_style) {
        "specific"
    } else {
        zone_style
    }
}

fn semantic_time_style_has_zone(time_style: &str) -> bool {
    time_style == "long" || time_style == "full"
}

fn semantic_hour_symbol(hour_cycle: &str) -> char {
    match hour_cycle {
        "h11" => 'K',
        "h12" | "clock12" => 'h',
        "h23" | "clock24" => 'H',
        "h24" => 'k',
        _ => 'C',
    }
}

fn semantic_zone_skeleton(zone_style: &str, standalone: bool, length: &str) -> String {
    let style = if zone_style == "auto" {
        "generic"
    } else {
        zone_style
    };
    match style {
        "specific" if standalone && length != "short" => "zzzz".to_string(),
        "specific" => "z".to_string(),
        "location" => "VVVV".to_string(),
        "offset" => "O".to_string(),
        _ if standalone && length != "short" => "vvvv".to_string(),
        _ => "v".to_string(),
    }
}

fn semantic_width(widths: &[(char, usize)], symbol: char) -> Option<usize> {
    widths
        .iter()
        .find(|(candidate, _)| *candidate == symbol)
        .map(|(_, width)| *width)
}

fn apply_c_hour_format(
    widths: &mut Vec<(char, usize)>,
    locale_data: &CldrDateTimeLocaleData,
    hour_cycle: Option<&str>,
    width: usize,
) {
    if hour_cycle.is_some() {
        let hour_symbol = preferred_hour_symbol(locale_data, hour_cycle);
        set_skeleton_width(widths, hour_symbol, c_hour_width(width));
        if is_hour12_field(hour_symbol) {
            set_skeleton_width(widths, 'B', day_period_width_for_c(width));
        }
        return;
    }
    for token in locale_data.allowed_hour_formats.split_whitespace() {
        let chars: Vec<char> = token.chars().collect();
        if !is_c_hour_format_token(&chars) {
            continue;
        }
        set_skeleton_width(widths, chars[0], c_hour_width(width));
        if chars.len() > 1 {
            set_skeleton_width(widths, chars[1], day_period_width_for_c(width));
        }
        return;
    }
    let hour_symbol = preferred_hour_symbol(locale_data, hour_cycle);
    set_skeleton_width(widths, hour_symbol, c_hour_width(width));
}

fn is_c_hour_format_token(token: &[char]) -> bool {
    (token.len() == 1 || token.len() == 2)
        && matches!(token[0], 'h' | 'H' | 'k' | 'K')
        && (token.len() == 1 || matches!(token[1], 'b' | 'B'))
}

fn set_skeleton_width(widths: &mut Vec<(char, usize)>, symbol: char, width: usize) {
    if let Some((_, current_width)) = widths
        .iter_mut()
        .find(|(candidate, _)| *candidate == symbol)
    {
        *current_width = (*current_width).max(width);
    } else {
        widths.push((symbol, width));
    }
}

fn normalize_skeleton_symbol(
    symbol: char,
    locale_data: &CldrDateTimeLocaleData,
    hour_cycle: Option<&str>,
) -> char {
    match symbol {
        'l' => 'L',
        'j' | 'J' => preferred_hour_symbol(locale_data, hour_cycle),
        _ => symbol,
    }
}

fn c_hour_width(width: usize) -> usize {
    if width % 2 == 0 {
        2
    } else {
        1
    }
}

fn day_period_width_for_c(width: usize) -> usize {
    if width >= 5 {
        5
    } else if width >= 3 {
        4
    } else {
        1
    }
}

fn should_suppress_day_period(skeleton: &str) -> bool {
    skeleton.contains('J')
        && !skeleton.contains('a')
        && !skeleton.contains('b')
        && !skeleton.contains('B')
        && !skeleton.contains('C')
}

fn strip_day_period_pattern_fields(pattern: &str) -> String {
    let chars: Vec<char> = pattern.chars().collect();
    let mut output = String::new();
    let mut pending_whitespace = String::new();
    let mut index = 0usize;
    while index < chars.len() {
        let ch = chars[index];
        if ch == '\'' {
            let quoted = read_quoted_pattern(&chars, index);
            output.push_str(&pending_whitespace);
            for raw in &chars[index..quoted.next_index] {
                output.push(*raw);
            }
            pending_whitespace.clear();
            index = quoted.next_index;
        } else if ch.is_ascii_alphabetic() {
            let mut end = index + 1;
            while end < chars.len() && chars[end] == ch {
                end += 1;
            }
            if is_day_period_field(ch) {
                pending_whitespace.clear();
            } else {
                output.push_str(&pending_whitespace);
                for raw in &chars[index..end] {
                    output.push(*raw);
                }
                pending_whitespace.clear();
            }
            index = end;
        } else if is_pattern_whitespace(ch) {
            pending_whitespace.push(ch);
            index += 1;
        } else {
            output.push_str(&pending_whitespace);
            output.push(ch);
            pending_whitespace.clear();
            index += 1;
        }
    }
    output.push_str(&pending_whitespace);
    output.trim_matches(is_pattern_whitespace).to_string()
}

fn is_pattern_whitespace(value: char) -> bool {
    matches!(value, ' ' | '\u{00A0}' | '\u{202F}') || value.is_whitespace()
}

fn preferred_hour_symbol(locale_data: &CldrDateTimeLocaleData, hour_cycle: Option<&str>) -> char {
    match hour_cycle {
        Some("h11") => return 'K',
        Some("h12") => return 'h',
        Some("h23") => return 'H',
        Some("h24") => return 'k',
        _ => {}
    }
    let short_time = string_map_get(locale_data.time_formats, "short").unwrap_or("");
    if short_time.contains('H') {
        'H'
    } else if short_time.contains('k') {
        'k'
    } else if short_time.contains('K') {
        'K'
    } else {
        'h'
    }
}

fn skeleton_field_set(skeleton: &str) -> String {
    let mut fields: Vec<char> = skeleton_widths(skeleton)
        .into_iter()
        .map(|(symbol, _)| field_set_symbol(symbol))
        .collect();
    fields.sort_unstable();
    fields.dedup();
    fields.into_iter().collect()
}

fn field_set_symbol(symbol: char) -> char {
    if is_year_field(symbol) {
        return 'y';
    }
    if is_hour_field(symbol) {
        return 'j';
    }
    if is_month_field(symbol) {
        return 'M';
    }
    if is_quarter_field(symbol) {
        return 'Q';
    }
    if is_day_period_field(symbol) {
        return 'B';
    }
    if is_weekday_field(symbol) {
        return 'E';
    }
    if is_time_zone_field(symbol) {
        return 'v';
    }
    symbol
}

fn skeleton_distance(requested: &str, candidate: &str) -> usize {
    let requested_widths = skeleton_widths(requested);
    let candidate_widths = skeleton_widths(candidate);
    let mut distance = 0usize;
    for (symbol, requested_width) in requested_widths {
        let candidate_symbol = candidate_symbol_for_requested(symbol, &candidate_widths);
        let candidate_width = candidate_symbol
            .and_then(|candidate| width_for_skeleton_symbol(&candidate_widths, candidate))
            .unwrap_or(0);
        distance += requested_width.abs_diff(candidate_width);
        if is_text_width(requested_width) != is_text_width(candidate_width) {
            distance += 8;
        }
        distance += hour_field_distance(symbol, candidate_symbol);
    }
    distance
}

fn skeleton_widths(skeleton: &str) -> Vec<(char, usize)> {
    let chars: Vec<char> = skeleton.chars().collect();
    let mut widths: Vec<(char, usize)> = Vec::new();
    let mut index = 0usize;
    while index < chars.len() {
        let symbol = chars[index];
        let mut end = index + 1;
        while end < chars.len() && chars[end] == symbol {
            end += 1;
        }
        if let Some((_, width)) = widths
            .iter_mut()
            .find(|(candidate, _)| *candidate == symbol)
        {
            *width = (*width).max(end - index);
        } else {
            widths.push((symbol, end - index));
        }
        index = end;
    }
    widths
}

fn width_for_skeleton_symbol(widths: &[(char, usize)], symbol: char) -> Option<usize> {
    widths
        .iter()
        .find(|(candidate, _)| *candidate == symbol)
        .map(|(_, width)| *width)
}

fn is_text_width(width: usize) -> bool {
    width >= 3
}

fn is_hour_field(symbol: char) -> bool {
    SKELETON_HOUR_FIELDS.contains(symbol)
}

fn is_year_field(symbol: char) -> bool {
    matches!(symbol, 'y' | 'u' | 'r')
}

fn is_weekday_field(symbol: char) -> bool {
    matches!(symbol, 'E' | 'e' | 'c')
}

fn is_month_field(symbol: char) -> bool {
    matches!(symbol, 'M' | 'L')
}

fn is_quarter_field(symbol: char) -> bool {
    matches!(symbol, 'Q' | 'q')
}

fn is_day_period_field(symbol: char) -> bool {
    matches!(symbol, 'a' | 'b' | 'B')
}

fn is_synthetic_numeric_field(symbol: char) -> bool {
    matches!(symbol, 'D' | 'F' | 'g' | 'm' | 's' | 'A')
}

fn is_time_zone_field(symbol: char) -> bool {
    matches!(symbol, 'z' | 'Z' | 'O' | 'v' | 'V' | 'X' | 'x')
}

fn candidate_symbol_for_requested(symbol: char, widths: &[(char, usize)]) -> Option<char> {
    if width_for_skeleton_symbol(widths, symbol).is_some() {
        return Some(symbol);
    }
    if is_year_field(symbol) {
        return ['y', 'u', 'r']
            .into_iter()
            .find(|candidate| width_for_skeleton_symbol(widths, *candidate).is_some());
    }
    if is_hour_field(symbol) {
        return SKELETON_HOUR_FIELDS
            .chars()
            .find(|candidate| width_for_skeleton_symbol(widths, *candidate).is_some());
    }
    if is_quarter_field(symbol) {
        return ['Q', 'q']
            .into_iter()
            .find(|candidate| width_for_skeleton_symbol(widths, *candidate).is_some());
    }
    if is_month_field(symbol) {
        return ['M', 'L']
            .into_iter()
            .find(|candidate| width_for_skeleton_symbol(widths, *candidate).is_some());
    }
    if is_day_period_field(symbol) {
        return ['B', 'b', 'a']
            .into_iter()
            .find(|candidate| width_for_skeleton_symbol(widths, *candidate).is_some());
    }
    if is_weekday_field(symbol) {
        return ['E', 'e', 'c']
            .into_iter()
            .find(|candidate| width_for_skeleton_symbol(widths, *candidate).is_some());
    }
    if is_time_zone_field(symbol) {
        return ['v', 'z', 'O', 'Z', 'X', 'x', 'V']
            .into_iter()
            .find(|candidate| width_for_skeleton_symbol(widths, *candidate).is_some());
    }
    None
}

fn hour_field_distance(requested_symbol: char, candidate_symbol: Option<char>) -> usize {
    let Some(candidate_symbol) = candidate_symbol else {
        return 0;
    };
    if requested_symbol == candidate_symbol
        || !is_hour_field(requested_symbol)
        || !is_hour_field(candidate_symbol)
    {
        return 0;
    }
    if is_hour12_field(requested_symbol) == is_hour12_field(candidate_symbol) {
        1
    } else {
        4
    }
}

fn is_hour12_field(symbol: char) -> bool {
    symbol == 'h' || symbol == 'K'
}

fn requested_symbol_for_pattern(
    symbol: char,
    requested_widths: &[(char, usize)],
    candidate_widths: &[(char, usize)],
) -> char {
    if is_year_field(symbol) {
        return if candidate_symbol_for_requested(symbol, candidate_widths).is_some() {
            candidate_symbol_for_requested(symbol, requested_widths).unwrap_or(symbol)
        } else {
            symbol
        };
    }
    if is_weekday_field(symbol) {
        return if candidate_symbol_for_requested(symbol, candidate_widths).is_some() {
            requested_weekday_symbol_for_pattern(symbol, requested_widths)
        } else {
            symbol
        };
    }
    if is_day_period_field(symbol) {
        return if candidate_symbol_for_requested(symbol, candidate_widths).is_some() {
            requested_day_period_symbol_for_pattern(symbol, requested_widths)
        } else {
            symbol
        };
    }
    if is_time_zone_field(symbol) {
        return if candidate_symbol_for_requested(symbol, candidate_widths).is_some() {
            requested_time_zone_symbol_for_pattern(symbol, requested_widths)
        } else {
            symbol
        };
    }
    if (!is_year_field(symbol)
        && !is_hour_field(symbol)
        && !is_month_field(symbol)
        && !is_quarter_field(symbol)
        && !is_day_period_field(symbol)
        && !is_time_zone_field(symbol))
        || candidate_symbol_for_requested(symbol, candidate_widths).is_none()
    {
        return symbol;
    }
    candidate_symbol_for_requested(symbol, requested_widths).unwrap_or(symbol)
}

fn requested_weekday_symbol_for_pattern(symbol: char, requested_widths: &[(char, usize)]) -> char {
    if width_for_skeleton_symbol(requested_widths, 'c').is_some() {
        return 'c';
    }
    if width_for_skeleton_symbol(requested_widths, 'e').is_some() {
        return 'e';
    }
    if width_for_skeleton_symbol(requested_widths, 'E').is_some() {
        return 'E';
    }
    symbol
}

fn requested_day_period_symbol_for_pattern(
    symbol: char,
    requested_widths: &[(char, usize)],
) -> char {
    if width_for_skeleton_symbol(requested_widths, 'a').is_some() {
        return 'a';
    }
    if width_for_skeleton_symbol(requested_widths, 'b').is_some() {
        return 'b';
    }
    if width_for_skeleton_symbol(requested_widths, 'B').is_some() {
        return 'B';
    }
    symbol
}

fn requested_time_zone_symbol_for_pattern(
    symbol: char,
    requested_widths: &[(char, usize)],
) -> char {
    for time_zone_symbol in ['z', 'Z', 'O', 'v', 'V', 'X', 'x'] {
        if width_for_skeleton_symbol(requested_widths, time_zone_symbol).is_some() {
            return time_zone_symbol;
        }
    }
    symbol
}

fn width_for_pattern_symbol(widths: &[(char, usize)], symbol: char) -> Option<usize> {
    width_for_skeleton_symbol(widths, symbol).or_else(|| {
        if is_year_field(symbol) {
            return ['y', 'u', 'r']
                .into_iter()
                .find_map(|candidate| width_for_skeleton_symbol(widths, candidate));
        }
        if is_weekday_field(symbol) {
            return ['E', 'e', 'c']
                .into_iter()
                .find_map(|candidate| width_for_skeleton_symbol(widths, candidate));
        }
        if is_month_field(symbol) {
            return ['M', 'L']
                .into_iter()
                .find_map(|candidate| width_for_skeleton_symbol(widths, candidate));
        }
        if is_day_period_field(symbol) {
            return ['B', 'b', 'a']
                .into_iter()
                .find_map(|candidate| width_for_skeleton_symbol(widths, candidate));
        }
        if is_quarter_field(symbol) {
            return ['Q', 'q']
                .into_iter()
                .find_map(|candidate| width_for_skeleton_symbol(widths, candidate));
        }
        if is_time_zone_field(symbol) {
            return ['z', 'Z', 'O', 'v', 'V', 'X', 'x']
                .into_iter()
                .find_map(|candidate| width_for_skeleton_symbol(widths, candidate));
        }
        None
    })
}

fn adjust_pattern_widths(
    pattern: &str,
    requested_skeleton: &str,
    candidate_skeleton: &str,
) -> String {
    let requested_widths = skeleton_widths(requested_skeleton);
    let candidate_widths = skeleton_widths(candidate_skeleton);
    let chars: Vec<char> = pattern.chars().collect();
    let mut output = String::new();
    let mut in_quote = false;
    let mut index = 0usize;
    while index < chars.len() {
        let ch = chars[index];
        if ch == '\'' {
            output.push(ch);
            if chars.get(index + 1) == Some(&'\'') {
                output.push('\'');
                index += 2;
            } else {
                in_quote = !in_quote;
                index += 1;
            }
        } else if !in_quote && ch.is_ascii_alphabetic() {
            let mut end = index + 1;
            while end < chars.len() && chars[end] == ch {
                end += 1;
            }
            let pattern_width = end - index;
            let requested_symbol =
                requested_symbol_for_pattern(ch, &requested_widths, &candidate_widths);
            let width = match (
                width_for_pattern_symbol(&requested_widths, ch),
                width_for_pattern_symbol(&candidate_widths, ch),
            ) {
                (Some(requested_width), Some(candidate_width))
                    if should_adjust_pattern_width(
                        requested_symbol,
                        requested_width,
                        candidate_width,
                        pattern_width,
                    ) =>
                {
                    requested_width
                }
                _ => pattern_width,
            };
            for _ in 0..width {
                output.push(requested_symbol);
            }
            index = end;
        } else {
            output.push(ch);
            index += 1;
        }
    }
    output
}

fn should_adjust_pattern_width(
    symbol: char,
    requested_width: usize,
    candidate_width: usize,
    pattern_width: usize,
) -> bool {
    if matches!(symbol, 'e' | 'c') && pattern_width >= 3 && requested_width <= 2 {
        return true;
    }
    if is_weekday_field(symbol) && pattern_width >= 3 && requested_width >= 4 {
        return true;
    }
    pattern_width == candidate_width
}

fn split_date_time_skeleton(skeleton: &str) -> (String, String) {
    let mut date_skeleton = String::new();
    let mut time_skeleton = String::new();
    for symbol in skeleton.chars() {
        if SKELETON_TIME_FIELDS.contains(symbol) {
            time_skeleton.push(symbol);
        } else {
            date_skeleton.push(symbol);
        }
    }
    (date_skeleton, time_skeleton)
}

fn string_map_get(map: CldrDateTimeStringMap, key: &str) -> Option<&'static str> {
    map.iter()
        .find(|(candidate, _)| *candidate == key)
        .map(|(_, value)| *value)
}

fn nested_map2_get(map: CldrDateTimeNestedMap2, key: &str) -> Option<CldrDateTimeStringMap> {
    map.iter()
        .find(|(candidate, _)| *candidate == key)
        .map(|(_, value)| *value)
}

fn nested_map3_get(map: CldrDateTimeNestedMap3, key: &str) -> Option<CldrDateTimeNestedMap2> {
    map.iter()
        .find(|(candidate, _)| *candidate == key)
        .map(|(_, value)| *value)
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

fn invalid_date_time_operand() -> Diagnostic {
    bad_operand("Date/time core requires a valid host date/time value or ISO date string.")
}

fn bad_operand(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-operand", message, 0, 0)
}

fn bad_option(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-option", message, 0, 0)
}
