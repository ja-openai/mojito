use std::str::FromStr;

use icu_calendar::cal::Iso;
use icu_datetime::fieldsets;
use icu_datetime::input::{Date, DateTime, Time};
use icu_datetime::DateTimeFormatter;
use icu_decimal::input::{Decimal, SignDisplay};
use icu_decimal::DecimalFormatter;
use icu_locale_core::Locale;

use crate::diagnostic::Diagnostic;

use super::{FunctionCall, FunctionRegistry};

const MAX_FRACTION_DIGITS: i16 = 100;
const MAX_LOCALE_LENGTH: usize = 256;
const MAX_TIME_ZONE_OPTION_LENGTH: usize = 256;

pub(super) fn register(registry: &mut FunctionRegistry) {
    registry.register_formatter("number", format_icu4x_number);
    registry.register_formatter("integer", format_icu4x_integer);
    registry.register_formatter("date", format_icu4x_date);
    registry.register_formatter("time", format_icu4x_time);
    registry.register_formatter("datetime", format_icu4x_datetime);
}

fn format_icu4x_number(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let mut value = parse_decimal(call.value())
        .map_err(|_| bad_operand("Number function requires a numeric operand."))?;
    apply_fraction_digit_options(&mut value, &call)?;
    apply_sign_display(&mut value, &call)?;
    format_decimal(call.locale(), &value)
}

fn format_icu4x_integer(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    let mut value = parse_decimal(call.value())
        .map_err(|_| bad_operand("Integer function requires a numeric operand."))?;
    value.trunc(0);
    apply_sign_display(&mut value, &call)?;
    format_decimal(call.locale(), &value)
}

fn format_icu4x_date(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    validate_utc_time_zone(&call)?;
    let date = parse_date_value(call.value())
        .map_err(|_| bad_operand("Date function requires an ISO date or datetime operand."))?;
    let style = date_style(&call, "dateStyle", "length", "medium")?;
    format_date(call.locale(), date, style)
}

fn format_icu4x_time(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    validate_utc_time_zone(&call)?;
    let time = parse_time_value(call.value())
        .map_err(|_| bad_operand("Time function requires an ISO time or datetime operand."))?;
    let style = time_style(&call, "timeStyle", "precision", "medium")?;
    format_time(call.locale(), time, style)
}

fn format_icu4x_datetime(call: FunctionCall<'_>) -> Result<String, Diagnostic> {
    validate_utc_time_zone(&call)?;
    let datetime = parse_datetime_value(call.value())
        .map_err(|_| bad_operand("Datetime function requires an ISO date or datetime operand."))?;
    let style = call.option_value("style")?;
    if let Some(style) = style {
        let style = date_time_style(&style)?;
        return format_datetime(call.locale(), datetime, style);
    }
    let date_style = date_style(&call, "dateStyle", "dateLength", "medium")?;
    let time_style = time_style(&call, "timeStyle", "timePrecision", "medium")?;
    format_datetime_with_styles(call.locale(), datetime, date_style, time_style)
}

fn format_decimal(locale: &str, value: &Decimal) -> Result<String, Diagnostic> {
    let formatter = DecimalFormatter::try_new(parse_locale(locale)?.into(), Default::default())
        .map_err(|error| bad_option(format!("ICU4X decimal formatter error: {error}")))?;
    Ok(formatter.format(value).to_string())
}

fn format_date(locale: &str, date: Date<Iso>, style: DateStyle) -> Result<String, Diagnostic> {
    let locale = parse_locale(locale)?.into();
    let value = match style {
        DateStyle::Full => DateTimeFormatter::try_new(locale, fieldsets::YMD::long())
            .map_err(date_time_formatter_error)?
            .format(&date)
            .to_string(),
        DateStyle::Long => DateTimeFormatter::try_new(locale, fieldsets::YMD::long())
            .map_err(date_time_formatter_error)?
            .format(&date)
            .to_string(),
        DateStyle::Medium => DateTimeFormatter::try_new(locale, fieldsets::YMD::medium())
            .map_err(date_time_formatter_error)?
            .format(&date)
            .to_string(),
        DateStyle::Short => DateTimeFormatter::try_new(locale, fieldsets::YMD::short())
            .map_err(date_time_formatter_error)?
            .format(&date)
            .to_string(),
    };
    Ok(value)
}

fn format_time(locale: &str, time: Time, style: TimeStyle) -> Result<String, Diagnostic> {
    let locale = parse_locale(locale)?.into();
    let value = match style {
        TimeStyle::Full | TimeStyle::Long | TimeStyle::Medium | TimeStyle::Second => {
            DateTimeFormatter::try_new(locale, fieldsets::T::medium())
                .map_err(date_time_formatter_error)?
                .format(&time)
                .to_string()
        }
        TimeStyle::Short => DateTimeFormatter::try_new(locale, fieldsets::T::short())
            .map_err(date_time_formatter_error)?
            .format(&time)
            .to_string(),
    };
    Ok(value)
}

fn format_datetime(
    locale: &str,
    datetime: DateTime<Iso>,
    style: DateStyle,
) -> Result<String, Diagnostic> {
    format_datetime_with_styles(locale, datetime, style, style.into())
}

fn format_datetime_with_styles(
    locale: &str,
    datetime: DateTime<Iso>,
    date_style: DateStyle,
    time_style: TimeStyle,
) -> Result<String, Diagnostic> {
    let locale = parse_locale(locale)?.into();
    let value = match (date_style, time_style) {
        (
            DateStyle::Full,
            TimeStyle::Full | TimeStyle::Long | TimeStyle::Medium | TimeStyle::Second,
        ) => DateTimeFormatter::try_new(locale, fieldsets::YMDT::long())
            .map_err(date_time_formatter_error)?
            .format(&datetime)
            .to_string(),
        (
            DateStyle::Long,
            TimeStyle::Full | TimeStyle::Long | TimeStyle::Medium | TimeStyle::Second,
        ) => DateTimeFormatter::try_new(locale, fieldsets::YMDT::long())
            .map_err(date_time_formatter_error)?
            .format(&datetime)
            .to_string(),
        (
            DateStyle::Medium,
            TimeStyle::Full | TimeStyle::Long | TimeStyle::Medium | TimeStyle::Second,
        ) => DateTimeFormatter::try_new(locale, fieldsets::YMDT::medium())
            .map_err(date_time_formatter_error)?
            .format(&datetime)
            .to_string(),
        (
            DateStyle::Short,
            TimeStyle::Full | TimeStyle::Long | TimeStyle::Medium | TimeStyle::Second,
        ) => DateTimeFormatter::try_new(locale, fieldsets::YMDT::short())
            .map_err(date_time_formatter_error)?
            .format(&datetime)
            .to_string(),
        (DateStyle::Full, TimeStyle::Short) => {
            DateTimeFormatter::try_new(locale, fieldsets::YMD::long().with_time_hm())
                .map_err(date_time_formatter_error)?
                .format(&datetime)
                .to_string()
        }
        (DateStyle::Long, TimeStyle::Short) => {
            DateTimeFormatter::try_new(locale, fieldsets::YMD::long().with_time_hm())
                .map_err(date_time_formatter_error)?
                .format(&datetime)
                .to_string()
        }
        (DateStyle::Medium, TimeStyle::Short) => {
            DateTimeFormatter::try_new(locale, fieldsets::YMD::medium().with_time_hm())
                .map_err(date_time_formatter_error)?
                .format(&datetime)
                .to_string()
        }
        (DateStyle::Short, TimeStyle::Short) => {
            DateTimeFormatter::try_new(locale, fieldsets::YMD::short().with_time_hm())
                .map_err(date_time_formatter_error)?
                .format(&datetime)
                .to_string()
        }
    };
    Ok(value)
}

fn parse_decimal(value: &str) -> Result<Decimal, ()> {
    Decimal::from_str(value).map_err(|_| ())
}

fn apply_fraction_digit_options(
    value: &mut Decimal,
    call: &FunctionCall<'_>,
) -> Result<(), Diagnostic> {
    if let Some(maximum) = non_negative_i16_option(call, "maximumFractionDigits")? {
        value.round(-maximum);
    }
    if let Some(minimum) = non_negative_i16_option(call, "minimumFractionDigits")? {
        value.absolute.pad_end(-minimum);
    }
    Ok(())
}

fn apply_sign_display(value: &mut Decimal, call: &FunctionCall<'_>) -> Result<(), Diagnostic> {
    match call.option_value("signDisplay")?.as_deref() {
        Some("always") => value.apply_sign_display(SignDisplay::Always),
        Some("exceptZero") => value.apply_sign_display(SignDisplay::ExceptZero),
        Some("never") => value.apply_sign_display(SignDisplay::Never),
        Some("auto") | None => value.apply_sign_display(SignDisplay::Auto),
        Some("negative") => value.apply_sign_display(SignDisplay::Negative),
        Some(_) => {
            return Err(bad_option(
                "signDisplay option must be auto, always, exceptZero, negative, or never.",
            ))
        }
    }
    Ok(())
}

fn non_negative_i16_option(
    call: &FunctionCall<'_>,
    option_name: &str,
) -> Result<Option<i16>, Diagnostic> {
    let Some(value) = call.option_value(option_name)? else {
        return Ok(None);
    };
    if value.is_empty() || !value.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err(bad_option(format!(
            "{option_name} option must be a non-negative integer."
        )));
    }
    let parsed = value.parse::<i16>().map_err(|_| {
        bad_option(format!(
            "{option_name} option is outside the supported integer range."
        ))
    })?;
    if parsed > MAX_FRACTION_DIGITS {
        return Err(bad_option(format!(
            "{option_name} option must be a non-negative integer."
        )));
    }
    Ok(Some(parsed))
}

fn parse_locale(locale: &str) -> Result<Locale, Diagnostic> {
    if locale.len() > MAX_LOCALE_LENGTH {
        return Err(bad_option("locale must not exceed 256 characters."));
    }
    locale
        .replace('_', "-")
        .parse()
        .map_err(|error| bad_option(format!("Invalid locale for ICU4X formatter: {error}")))
}

fn validate_utc_time_zone(call: &FunctionCall<'_>) -> Result<(), Diagnostic> {
    let time_zone = call.option_value("timeZone")?;
    if let Some(value) = time_zone.as_deref() {
        if value.len() > MAX_TIME_ZONE_OPTION_LENGTH {
            return Err(bad_option(
                "timeZone option must not exceed 256 characters.",
            ));
        }
    }
    match time_zone.as_deref() {
        None | Some("UTC") | Some("Etc/UTC") => Ok(()),
        Some(_) => Err(bad_option(
            "Rust ICU4X date/time formatting currently supports only UTC timeZone.",
        )),
    }
}

fn parse_datetime_value(value: &str) -> Result<DateTime<Iso>, ()> {
    let value = strip_utc_suffix(value);
    let (date, time) = value.split_once('T').ok_or(())?;
    Ok(DateTime {
        date: parse_date(date)?,
        time: parse_time(time)?,
    })
}

fn parse_date_value(value: &str) -> Result<Date<Iso>, ()> {
    let value = strip_utc_suffix(value);
    let date = value.split_once('T').map_or(value, |(date, _)| date);
    parse_date(date)
}

fn parse_time_value(value: &str) -> Result<Time, ()> {
    let value = strip_utc_suffix(value);
    let time = value.split_once('T').map_or(value, |(_, time)| time);
    parse_time(time)
}

fn strip_utc_suffix(value: &str) -> &str {
    value.strip_suffix('Z').unwrap_or(value)
}

fn parse_date(value: &str) -> Result<Date<Iso>, ()> {
    let mut parts = value.split('-');
    let year = parts.next().ok_or(())?.parse::<i32>().map_err(|_| ())?;
    let month = parts.next().ok_or(())?.parse::<u8>().map_err(|_| ())?;
    let day = parts.next().ok_or(())?.parse::<u8>().map_err(|_| ())?;
    if parts.next().is_some() {
        return Err(());
    }
    Date::try_new_iso(year, month, day).map_err(|_| ())
}

fn parse_time(value: &str) -> Result<Time, ()> {
    if value.contains(['+', '-']) {
        return Err(());
    }
    let mut parts = value.split(':');
    let hour = parts.next().ok_or(())?.parse::<u8>().map_err(|_| ())?;
    let minute = parts.next().ok_or(())?.parse::<u8>().map_err(|_| ())?;
    let second = parts
        .next()
        .map(|second| second.split_once('.').map_or(second, |(second, _)| second))
        .unwrap_or("0")
        .parse::<u8>()
        .map_err(|_| ())?;
    if parts.next().is_some() {
        return Err(());
    }
    Time::try_new(hour, minute, second, 0).map_err(|_| ())
}

#[derive(Clone, Copy)]
enum DateStyle {
    Full,
    Long,
    Medium,
    Short,
}

#[derive(Clone, Copy)]
enum TimeStyle {
    Full,
    Long,
    Medium,
    Short,
    Second,
}

impl From<DateStyle> for TimeStyle {
    fn from(style: DateStyle) -> Self {
        match style {
            DateStyle::Full => Self::Full,
            DateStyle::Long => Self::Long,
            DateStyle::Medium => Self::Medium,
            DateStyle::Short => Self::Short,
        }
    }
}

fn date_style(
    call: &FunctionCall<'_>,
    option_name: &str,
    legacy_option_name: &str,
    fallback: &str,
) -> Result<DateStyle, Diagnostic> {
    let fallback = call
        .option_value("style")?
        .unwrap_or_else(|| fallback.to_string());
    let value = match call.option_value(option_name)? {
        Some(value) => value,
        None => call.option_value(legacy_option_name)?.unwrap_or(fallback),
    };
    date_time_style(&value)
}

fn date_time_style(value: &str) -> Result<DateStyle, Diagnostic> {
    match value {
        "full" => Ok(DateStyle::Full),
        "long" => Ok(DateStyle::Long),
        "medium" => Ok(DateStyle::Medium),
        "short" => Ok(DateStyle::Short),
        _ => Err(bad_option(
            "Date style option must be full, long, medium, or short.",
        )),
    }
}

fn time_style(
    call: &FunctionCall<'_>,
    option_name: &str,
    legacy_option_name: &str,
    fallback: &str,
) -> Result<TimeStyle, Diagnostic> {
    let fallback = call
        .option_value("style")?
        .unwrap_or_else(|| fallback.to_string());
    let value = match call.option_value(option_name)? {
        Some(value) => value,
        None => call.option_value(legacy_option_name)?.unwrap_or(fallback),
    };
    match value.as_str() {
        "full" => Ok(TimeStyle::Full),
        "long" => Ok(TimeStyle::Long),
        "medium" => Ok(TimeStyle::Medium),
        "short" => Ok(TimeStyle::Short),
        "second" => Ok(TimeStyle::Second),
        _ => Err(bad_option(
            "Time style option must be full, long, medium, short, or second.",
        )),
    }
}

fn date_time_formatter_error(error: impl std::fmt::Display) -> Diagnostic {
    bad_option(format!("ICU4X date/time formatter error: {error}"))
}

fn bad_operand(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-operand", message, 0, 0)
}

fn bad_option(message: impl Into<String>) -> Diagnostic {
    Diagnostic::new("bad-option", message, 0, 0)
}
