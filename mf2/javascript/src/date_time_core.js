import { DATE_TIME_DATA } from "./cldr_date_time_data.js";
import { localeLookupChain } from "./locale-key.js";

const DEFAULT_LOCALE = "en-US";
const DIGIT_ZERO = "0".charCodeAt(0);
const MIN_TIMESTAMP_MS = -62_135_596_800_000;
const MAX_TIMESTAMP_MS = 253_402_300_799_999;
const MAX_LOCALE_LENGTH = 256;
const MAX_OPTION_LENGTH = 256;
const MAX_OPERAND_LENGTH = 256;
const MAX_SKELETON_FIELD_WIDTH = 32;
const MAX_SKELETON_LENGTH = 256;
const ISO_DATE_TIME_RE = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:\d{2})?)?$/;
const STYLE_VALUES = ["full", "long", "medium", "short"];
const SEMANTIC_LENGTH_VALUES = ["full", "long", "medium", "short"];
const SEMANTIC_SKELETON_PREFIX = "semantic:";
const SEMANTIC_FIELD_ORDER = ["era", "year", "quarter", "month", "weekofmonth", "day", "dayofyear", "dayofweekinmonth", "modifiedjulianday", "weekday", "weekofyear", "dayperiod", "hour", "minute", "second", "fractionalsecond", "millisecondsinday", "time", "zone"];
const SEMANTIC_DATE_FIELD_ORDER = ["era", "year", "quarter", "month", "weekofmonth", "day", "dayofyear", "dayofweekinmonth", "modifiedjulianday", "weekday", "weekofyear"];
const SEMANTIC_TIME_FIELD_ORDER = ["hour", "minute", "second", "fractionalsecond", "millisecondsinday"];
const SEMANTIC_OPTION_KEYS = new Set(["fields", "length", "alignment", "yearstyle", "erastyle", "monthstyle", "quarterstyle", "daystyle", "weekdaystyle", "dayperiodstyle", "hourstyle", "minutestyle", "secondstyle", "timeprecision", "timestyle", "fractionalsecond", "hourcycle", "zonestyle"]);
const SEMANTIC_DIRECT_STYLE_OPTION_KEYS = new Set(["fields", "length", "timestyle"]);
const SEMANTIC_STYLE_OPTION_KEYS = new Set(["yearstyle", "erastyle", "monthstyle", "quarterstyle", "daystyle", "weekdaystyle", "dayperiodstyle", "hourstyle", "minutestyle", "secondstyle"]);
const SEMANTIC_FIELD_STYLE_OPTION_ALIASES = new Map([
  ["era", "erastyle"],
  ["year", "yearstyle"],
  ["month", "monthstyle"],
  ["quarter", "quarterstyle"],
  ["day", "daystyle"],
  ["weekday", "weekdaystyle"],
  ["dayperiod", "dayperiodstyle"],
  ["hour", "hourstyle"],
  ["minute", "minutestyle"],
  ["second", "secondstyle"],
]);
const SEMANTIC_DATE_STYLE_VALUES = ["auto", "numeric", "2-digit", "short", "long", "narrow"];
const SEMANTIC_NUMERIC_STYLE_VALUES = ["auto", "numeric", "2-digit"];
const SEMANTIC_TEXT_STYLE_VALUES = ["auto", "short", "long", "narrow"];
const SEMANTIC_DATE_FIELD_SETS = new Set([
  "day",
  "weekday",
  "day,weekday",
  "month,day",
  "month,day,weekday",
  "era,year,month,day",
  "era,year,month,day,weekday",
  "year,month,day",
  "year,month,day,weekday",
]);
const SEMANTIC_CALENDAR_PERIOD_FIELD_SETS = new Set([
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
]);
const SEMANTIC_TIME_FIELD_SETS = new Set([
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
]);
const SKELETON_FIELD_ORDER = "GyYuUrQqMLlwWdDFgEecabBhHkKJmsSAzZOvVXx";
const SKELETON_TIME_FIELDS = new Set([..."abBhHkKJmsSAzZOvVXx"]);
const SKELETON_HOUR_FIELDS = new Set(["h", "H", "k", "K"]);
const WEEKDAY_KEYS = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"];

export class DateTimeCoreError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "DateTimeCoreError";
    this.code = code;
  }
}

export function formatDateCore(value, options = {}) {
  const locale = localeOption(options.locale);
  const localeData = resolveNumberingSystemData(resolveLocaleData(locale), locale);
  validateCalendar(firstNonEmpty(options.calendar, localeUnicodeExtension(locale, "ca")));
  const explicitHourCycle = firstNonEmpty(options.hourCycle) != null;
  const hourCycle = validateHourCycle(firstNonEmpty(options.hourCycle, localeUnicodeExtension(locale, "hc")));
  const timeZone = parseTimeZone(options.timeZone);
  const date = applyTimeZone(parseDate(value), timeZone);
  if (options.skeleton != null) return formatSkeleton(options.skeleton, date, localeData, timeZone, hourCycle, explicitHourCycle);
  const style = dateStyleOption(options, "medium");
  return formatPattern(localeData.dateFormats[style], date, localeData, timeZone);
}

export function formatTimeCore(value, options = {}) {
  const locale = localeOption(options.locale);
  const localeData = resolveNumberingSystemData(resolveLocaleData(locale), locale);
  validateCalendar(firstNonEmpty(options.calendar, localeUnicodeExtension(locale, "ca")));
  const explicitHourCycle = firstNonEmpty(options.hourCycle) != null;
  const hourCycle = validateHourCycle(firstNonEmpty(options.hourCycle, localeUnicodeExtension(locale, "hc")));
  const timeZone = parseTimeZone(options.timeZone);
  const date = applyTimeZone(parseDate(value), timeZone);
  if (options.skeleton != null) return formatSkeleton(options.skeleton, date, localeData, timeZone, hourCycle, explicitHourCycle);
  const style = timeStyleOption(options, "medium");
  return formatTimeStylePattern(localeData.timeFormats[style], date, localeData, timeZone, hourCycle, explicitHourCycle);
}

export function formatDateTimeCore(value, options = {}) {
  const locale = localeOption(options.locale);
  const localeData = resolveNumberingSystemData(resolveLocaleData(locale), locale);
  validateCalendar(firstNonEmpty(options.calendar, localeUnicodeExtension(locale, "ca")));
  const explicitHourCycle = firstNonEmpty(options.hourCycle) != null;
  const hourCycle = validateHourCycle(firstNonEmpty(options.hourCycle, localeUnicodeExtension(locale, "hc")));
  const timeZone = parseTimeZone(options.timeZone);
  const date = applyTimeZone(parseDate(value), timeZone);
  if (options.skeleton != null) return formatSkeleton(options.skeleton, date, localeData, timeZone, hourCycle, explicitHourCycle);
  const dateStyle = dateStyleOption(options, "medium");
  const timeStyle = timeStyleOption(options, "medium");
  const datePart = formatPattern(localeData.dateFormats[dateStyle], date, localeData, timeZone);
  const timePart = formatTimeStylePattern(
    localeData.timeFormats[timeStyle],
    date,
    localeData,
    timeZone,
    hourCycle,
    explicitHourCycle,
  );
  return applyDateTimePattern(dateTimeStyleJoinPattern(localeData, dateStyle), datePart, timePart);
}

export function formatDateCoreToParts(value, options = {}) {
  return [{ type: "text", value: formatDateCore(value, options) }];
}

export function formatTimeCoreToParts(value, options = {}) {
  return [{ type: "text", value: formatTimeCore(value, options) }];
}

export function formatDateTimeCoreToParts(value, options = {}) {
  return [{ type: "text", value: formatDateTimeCore(value, options) }];
}

export function createDateTimeCoreFunctionRegistry(FunctionRegistry) {
  return FunctionRegistry.portable()
    .withFunction("date", formatCallDate)
    .withFunction("time", formatCallTime)
    .withFunction("datetime", formatCallDateTime);
}

function formatCallDate(call) {
  return formatDateCore(callSourceValue(call), {
    locale: call.locale,
    style: callNonEmptyOption(call, "style", "medium"),
    dateStyle: callNonEmptyOption(call, "dateStyle", null),
    length: callNonEmptyOption(call, "length", null),
    skeleton: callNonEmptyOption(call, "skeleton", null),
    hourCycle: callNonEmptyOption(call, "hourCycle", null),
    timeZone: callNonEmptyOption(call, "timeZone", "UTC"),
    calendar: callNonEmptyOption(call, "calendar", null),
  });
}

function formatCallTime(call) {
  return formatTimeCore(callSourceValue(call), {
    locale: call.locale,
    style: callNonEmptyOption(call, "style", "medium"),
    timeStyle: callNonEmptyOption(call, "timeStyle", null),
    precision: callNonEmptyOption(call, "precision", null),
    skeleton: callNonEmptyOption(call, "skeleton", null),
    hourCycle: callNonEmptyOption(call, "hourCycle", null),
    timeZone: callNonEmptyOption(call, "timeZone", "UTC"),
    calendar: callNonEmptyOption(call, "calendar", null),
  });
}

function formatCallDateTime(call) {
  return formatDateTimeCore(callSourceValue(call), {
    locale: call.locale,
    style: callNonEmptyOption(call, "style", "medium"),
    dateStyle: callNonEmptyOption(call, "dateStyle", null),
    timeStyle: callNonEmptyOption(call, "timeStyle", null),
    dateLength: callNonEmptyOption(call, "dateLength", null),
    timePrecision: callNonEmptyOption(call, "timePrecision", null),
    skeleton: callNonEmptyOption(call, "skeleton", null),
    hourCycle: callNonEmptyOption(call, "hourCycle", null),
    timeZone: callNonEmptyOption(call, "timeZone", "UTC"),
    calendar: callNonEmptyOption(call, "calendar", null),
  });
}

function callNonEmptyOption(call, name, fallback) {
  const value = call.optionValue(name, fallback);
  if (value === "") {
    throw new DateTimeCoreError("bad-option", `${name} must not be empty.`);
  }
  return value;
}

function localeOption(value) {
  if (value == null) return DEFAULT_LOCALE;
  let locale;
  try {
    locale = String(value);
  } catch {
    throw new DateTimeCoreError("bad-option", "locale must be coercible to a string.");
  }
  if (locale.length > MAX_LOCALE_LENGTH) {
    throw new DateTimeCoreError("bad-option", "locale must not exceed 256 characters.");
  }
  return locale;
}

function resolveLocaleData(locale) {
  const key = String(locale ?? DEFAULT_LOCALE);
  for (const candidate of localeLookupChain(key)) {
    const exact = DATE_TIME_DATA.locales[candidate];
    if (exact != null) return exact;
    for (const localeData of Object.values(DATE_TIME_DATA.locales)) {
      if (localeData.sourceLocale === candidate || localeData.numbersSourceLocale === candidate) {
        return localeData;
      }
    }
  }
  return DATE_TIME_DATA.locales[DEFAULT_LOCALE];
}

function localeUnicodeExtension(locale, key) {
  const parts = String(locale ?? "")
    .trim()
    .replaceAll("_", "-")
    .split("-")
    .filter(Boolean)
    .map((part) => part.toLowerCase());
  const start = parts.indexOf("u");
  if (start < 0) return null;
  for (let index = start + 1; index < parts.length;) {
    const part = parts[index];
    if (part.length === 1) return null;
    if (part.length !== 2) {
      index += 1;
      continue;
    }
    let end = index + 1;
    while (end < parts.length && parts[end].length > 2) end += 1;
    if (part === key) return end > index + 1 ? parts[index + 1] : null;
    index = end;
  }
  return null;
}

function resolveNumberingSystemData(localeData, locale) {
  const numberingSystem = localeUnicodeExtension(locale, "nu");
  if (numberingSystem == null || numberingSystem === "") return localeData;
  const digits = numberingSystemDigits(numberingSystem);
  if (digits == null) {
    throw new DateTimeCoreError("bad-option", "Date/time core does not include data for the requested numbering system.");
  }
  return { ...localeData, numberingSystemDigits: digits };
}

function numberingSystemDigits(numberingSystem) {
  if (numberingSystem === "latn") return "0123456789";
  for (const localeData of Object.values(DATE_TIME_DATA.locales)) {
    if (localeData.numberingSystem === numberingSystem && localeData.numberingSystemDigits != null) {
      return localeData.numberingSystemDigits;
    }
  }
  return null;
}

function firstNonEmpty(...values) {
  return values.find((value) => value != null && value !== "") ?? null;
}

function dateStyleOption(options, fallback) {
  return styleOption(options.dateStyle ?? options.dateLength ?? options.length ?? options.style ?? fallback, "dateStyle");
}

function timeStyleOption(options, fallback) {
  if (options.timeStyle != null) return styleOption(options.timeStyle, "timeStyle");
  if (options.timePrecision != null) return timePrecisionStyleOption(options.timePrecision, "timePrecision");
  if (options.precision != null) return timePrecisionStyleOption(options.precision, "precision");
  return styleOption(options.style ?? fallback, "timeStyle");
}

function styleOption(value, name) {
  const text = boundedStringOption(value, name);
  if (!STYLE_VALUES.includes(text)) {
    throw new DateTimeCoreError("bad-option", `${name} must be one of ${STYLE_VALUES.join(", ")}.`);
  }
  return text;
}

function timePrecisionStyleOption(value, name) {
  const text = boundedStringOption(value, name);
  return text === "second" ? "medium" : styleOption(text, name);
}

function callSourceValue(call) {
  return call.inheritedSource?.value ?? call.rawValue ?? call.value;
}

function validateCalendar(value) {
  if (value == null || value === "") return;
  const text = boundedStringOption(value, "calendar");
  if (text === "gregorian" || text === "gregory") return;
  throw new DateTimeCoreError("bad-option", "Date/time core currently supports only the gregorian/gregory calendar.");
}

function validateHourCycle(value) {
  if (value == null || value === "") return null;
  const text = boundedStringOption(value, "hourCycle");
  if (text === "h11" || text === "h12" || text === "h23" || text === "h24") return text;
  throw new DateTimeCoreError("bad-option", "hourCycle must be one of h11, h12, h23, h24.");
}

function parseTimeZone(value) {
  if (value == null) return { offsetMinutes: 0 };
  const text = boundedStringOption(value, "timeZone").trim();
  if (text === "" || text === "UTC" || text === "Etc/UTC" || text === "Z" || text === "GMT" || text === "Etc/GMT") {
    return { offsetMinutes: 0 };
  }
  const etcGmtOffset = parseEtcGmtOffsetMinutes(text);
  if (etcGmtOffset != null) return { offsetMinutes: etcGmtOffset };
  const match = /^(?:(?:UTC|GMT)([+-].+)|([+-].+))$/.exec(text);
  const offsetText = match?.[1] ?? match?.[2];
  const offsetMinutes = offsetText == null ? null : parseOffsetMinutes(offsetText);
  if (offsetMinutes == null) {
    throw new DateTimeCoreError("bad-option", "Date/time core supports only UTC or fixed-offset time zones.");
  }
  return { offsetMinutes };
}

function boundedStringOption(value, name) {
  let text;
  try {
    text = String(value);
  } catch {
    throw new DateTimeCoreError("bad-option", `${name} must be coercible to a string.`);
  }
  if (text.length > MAX_OPTION_LENGTH) {
    throw new DateTimeCoreError("bad-option", `${name} must not exceed 256 characters.`);
  }
  return text;
}

function parseEtcGmtOffsetMinutes(value) {
  const match = /^Etc\/GMT([+-]\d{1,2})$/.exec(value);
  if (match == null) return null;
  const hours = Number(match[1]);
  if (!Number.isInteger(hours) || Math.abs(hours) > 14) return null;
  return -hours * 60;
}

function parseOffsetMinutes(value) {
  const match = /^([+-])(\d{1,2})(?::?(\d{2}))?$/.exec(value);
  if (match == null) return null;
  const hours = Number(match[2]);
  const minutes = Number(match[3] ?? "0");
  if (hours > 18 || minutes > 59 || (hours === 18 && minutes !== 0)) return null;
  const total = hours * 60 + minutes;
  return match[1] === "-" ? -total : total;
}

function applyTimeZone(date, timeZone) {
  if (timeZone.offsetMinutes === 0) return date;
  return new Date(date.getTime() + timeZone.offsetMinutes * 60_000);
}

function parseDate(value) {
  if (value == null || typeof value === "boolean" || typeof value === "bigint") {
    throw new DateTimeCoreError("bad-operand", "Date/time core requires a valid Date, timestamp, or ISO date string.");
  }
  if (typeof value === "number" && (!Number.isFinite(value) || value < MIN_TIMESTAMP_MS || value > MAX_TIMESTAMP_MS)) {
    throw new DateTimeCoreError("bad-operand", "Date/time core requires a valid Date, timestamp, or ISO date string.");
  }
  if (!(value instanceof Date) && typeof value !== "number" && typeof value !== "string") {
    throw new DateTimeCoreError("bad-operand", "Date/time core requires a valid Date, timestamp, or ISO date string.");
  }
  const date = value instanceof Date ? value : typeof value === "string" ? parseDateString(value) : new Date(value);
  if (!Number.isFinite(date.getTime())) {
    throw new DateTimeCoreError("bad-operand", "Date/time core requires a valid Date, timestamp, or ISO date string.");
  }
  return date;
}

function parseDateString(value) {
  const text = value.trim();
  if (text.length > MAX_OPERAND_LENGTH) {
    throw new DateTimeCoreError("bad-operand", "Date/time core requires a valid Date, timestamp, or ISO date string.");
  }
  const match = ISO_DATE_TIME_RE.exec(text);
  if (match == null) {
    throw new DateTimeCoreError("bad-operand", "Date/time core requires a valid Date, timestamp, or ISO date string.");
  }
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4] ?? "0");
  const minute = Number(match[5] ?? "0");
  const second = Number(match[6] ?? "0");
  const millisecond = Number((match[7] ?? "").slice(0, 3).padEnd(3, "0"));
  const zone = match[8] ?? "";
  if (
    month < 1 ||
    month > 12 ||
    day < 1 ||
    day > daysInMonth(year, month) ||
    hour > 23 ||
    minute > 59 ||
    second > 59
  ) {
    throw new DateTimeCoreError("bad-operand", "Date/time core requires a valid Date, timestamp, or ISO date string.");
  }
  const offsetMinutes = zone === "" || zone === "Z" ? 0 : parseOffsetMinutes(zone);
  if (offsetMinutes == null) {
    throw new DateTimeCoreError("bad-operand", "Date/time core requires a valid Date, timestamp, or ISO date string.");
  }
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  date.setUTCHours(hour, minute - offsetMinutes, second, millisecond);
  return date;
}

function daysInMonth(year, month) {
  if (month === 2) return isLeapYear(year) ? 29 : 28;
  return [4, 6, 9, 11].includes(month) ? 30 : 31;
}

function applyDateTimePattern(pattern, datePart, timePart) {
  return pattern.replace("{1}", datePart).replace("{0}", timePart);
}

function dateTimeStyleJoinPattern(localeData, style) {
  return localeData.dateTimeStyleJoinFormats?.[style] ?? localeData.dateTimeFormats?.[style] ?? localeData.dateTimeFormats?.medium ?? "{1} {0}";
}

function formatSkeleton(skeleton, date, localeData, timeZone, hourCycle, preserveSameFamilyHourCycle) {
  const text = skeletonOption(skeleton);
  const semanticStyle = formatSemanticStyleSkeleton(text, date, localeData, timeZone, hourCycle, preserveSameFamilyHourCycle);
  if (semanticStyle != null) return semanticStyle;
  const canonical = canonicalSkeleton(text, localeData, hourCycle, date);
  const suppressDayPeriod = shouldSuppressDayPeriod(text);
  const dateTimeJoinStyle = skeletonDateTimeJoinStyle(text);
  const pattern = skeletonPattern(canonical, localeData);
  if (pattern == null) return formatComposedSkeleton(text, canonical, date, localeData, timeZone, suppressDayPeriod, dateTimeJoinStyle);
  const resolvedPattern = suppressDayPeriod ? stripDayPeriodPatternFields(pattern) : pattern;
  return formatPattern(resolvedPattern, date, localeData, timeZone);
}

function skeletonDateTimeJoinStyle(skeleton) {
  const text = String(skeleton ?? "");
  if (!text.startsWith(SEMANTIC_SKELETON_PREFIX)) return "medium";
  const options = parseSemanticSkeletonOptions(text.slice(SEMANTIC_SKELETON_PREFIX.length));
  return semanticOption(options, "length", "medium", SEMANTIC_LENGTH_VALUES);
}

function formatSemanticStyleSkeleton(skeleton, date, localeData, timeZone, hourCycle, preserveSameFamilyHourCycle) {
  const text = String(skeleton ?? "");
  if (!text.startsWith(SEMANTIC_SKELETON_PREFIX)) return null;
  const options = parseSemanticSkeletonOptions(text.slice(SEMANTIC_SKELETON_PREFIX.length));
  const fields = parseSemanticSkeletonFields(options);
  validateSemanticSkeleton(fields, options);
  if (![...options.keys()].every((key) => SEMANTIC_DIRECT_STYLE_OPTION_KEYS.has(key))) return null;

  const length = semanticOption(options, "length", "medium", SEMANTIC_LENGTH_VALUES);
  const timeStyle = semanticOption(options, "timestyle", "auto", ["auto", "short", "medium", "long", "full"]);
  const dateKey = semanticFieldSetKey(fields, SEMANTIC_DATE_FIELD_ORDER);
  const expectedDateKey = length === "full" ? "year,month,day,weekday" : "year,month,day";
  const hasDate = dateKey.length > 0;
  const hasTime = fields.has("time");
  const hasZone = fields.has("zone");
  if (semanticFieldSetKey(fields, SEMANTIC_TIME_FIELD_ORDER).length > 0) return null;
  if (hasDate && dateKey !== expectedDateKey) return null;
  if (hasTime && !options.has("timestyle")) return null;
  if (!hasTime && (hasZone || timeStyle !== "auto")) return null;
  if (hasTime && hasZone !== (timeStyle === "long" || timeStyle === "full")) return null;
  const expectedFieldCount = (hasDate ? expectedDateKey.split(",").length : 0) + (hasTime ? 1 : 0) + (hasZone ? 1 : 0);
  if (fields.size !== expectedFieldCount) return null;

  if (hasDate && hasTime) {
    const datePart = formatPattern(localeData.dateFormats[length], date, localeData, timeZone);
    const timePart = formatTimeStylePattern(
      localeData.timeFormats[timeStyle],
      date,
      localeData,
      timeZone,
      hourCycle,
      preserveSameFamilyHourCycle,
    );
    const joinPattern = dateTimeStyleJoinPattern(localeData, length);
    return applyDateTimePattern(joinPattern, datePart, timePart);
  }
  if (hasDate) return formatPattern(localeData.dateFormats[length], date, localeData, timeZone);
  if (hasTime) {
    return formatTimeStylePattern(
      localeData.timeFormats[timeStyle],
      date,
      localeData,
      timeZone,
      hourCycle,
      preserveSameFamilyHourCycle,
    );
  }
  return null;
}

function formatTimeStylePattern(pattern, date, localeData, timeZone, hourCycle, preserveSameFamilyHourCycle) {
  if (hourCycle == null) return formatPattern(pattern, date, localeData, timeZone);
  const hourSymbol = preferredHourSymbol(localeData, hourCycle);
  const patternHourSymbol = timeStylePatternHourSymbol(pattern);
  if (
    preserveSameFamilyHourCycle &&
    patternHourSymbol != null &&
    isHour12Field(patternHourSymbol) === isHour12Field(hourSymbol)
  ) {
    return formatPattern(replaceTimeStylePatternHourSymbol(pattern, hourSymbol), date, localeData, timeZone);
  }
  const skeleton = timeStylePatternSkeleton(pattern, localeData, hourCycle);
  if (skeleton == null) return formatPattern(pattern, date, localeData, timeZone);
  const canonical = canonicalStandardSkeleton(skeleton, localeData, null);
  const matched = skeletonPattern(canonical, localeData);
  return formatPattern(matched ?? pattern, date, localeData, timeZone);
}

function timeStylePatternHourSymbol(pattern) {
  for (let index = 0; index < pattern.length;) {
    const symbol = pattern[index];
    if (symbol === "'") {
      index = readQuotedPattern(pattern, index).nextIndex;
    } else if (isAsciiLetter(symbol)) {
      let end = index + 1;
      while (end < pattern.length && pattern[end] === symbol) end += 1;
      if (isHourField(symbol)) return symbol;
      index = end;
    } else {
      index += 1;
    }
  }
  return null;
}

function replaceTimeStylePatternHourSymbol(pattern, hourSymbol) {
  let output = "";
  for (let index = 0; index < pattern.length;) {
    const symbol = pattern[index];
    if (symbol === "'") {
      const quoted = readQuotedPattern(pattern, index);
      output += pattern.slice(index, quoted.nextIndex);
      index = quoted.nextIndex;
    } else if (isAsciiLetter(symbol)) {
      let end = index + 1;
      while (end < pattern.length && pattern[end] === symbol) end += 1;
      output += isHourField(symbol) ? hourSymbol.repeat(end - index) : pattern.slice(index, end);
      index = end;
    } else {
      output += symbol;
      index += 1;
    }
  }
  return output;
}

function timeStylePatternSkeleton(pattern, localeData, hourCycle) {
  const widths = new Map();
  const hourSymbol = preferredHourSymbol(localeData, hourCycle);
  let hasHour = false;
  for (let index = 0; index < pattern.length;) {
    const symbol = pattern[index];
    if (symbol === "'") {
      index = readQuotedPattern(pattern, index).nextIndex;
    } else if (isAsciiLetter(symbol)) {
      let end = index + 1;
      while (end < pattern.length && pattern[end] === symbol) end += 1;
      if (isHourField(symbol)) {
        setSkeletonWidth(widths, hourSymbol, end - index);
        hasHour = true;
      } else if (!isDayPeriodField(symbol) && SKELETON_TIME_FIELDS.has(symbol)) {
        setSkeletonWidth(widths, symbol, end - index);
      }
      index = end;
    } else {
      index += 1;
    }
  }
  if (!hasHour) return null;
  return [...SKELETON_FIELD_ORDER]
    .filter((symbol) => widths.has(symbol))
    .map((symbol) => symbol.repeat(widths.get(symbol)))
    .join("");
}

function skeletonPattern(canonical, localeData) {
  const pattern = skeletonPatternWithoutAppend(canonical, localeData);
  if (pattern != null) return pattern;
  return hasDateAndTimeFields(canonical) ? null : appendedSkeletonPattern(canonical, localeData);
}

function skeletonPatternWithoutAppend(canonical, localeData) {
  const direct = localeData.availableFormats?.[canonical];
  if (direct != null) return direct;
  const requestedFields = skeletonFieldSet(canonical);
  let bestCandidate = null;
  let bestPattern = null;
  let bestDistance = Number.POSITIVE_INFINITY;
  for (const [candidate, pattern] of Object.entries(localeData.availableFormats ?? {})) {
    if (skeletonFieldSet(candidate) !== requestedFields) continue;
    const distance = skeletonDistance(canonical, candidate);
    if (distance < bestDistance || (distance === bestDistance && (bestCandidate == null || candidate < bestCandidate))) {
      bestCandidate = candidate;
      bestPattern = pattern;
      bestDistance = distance;
    }
  }
  return bestPattern == null ? syntheticSkeletonPattern(canonical, localeData) : adjustPatternWidths(bestPattern, canonical, bestCandidate);
}

function appendedSkeletonPattern(canonical, localeData) {
  const requestedFields = skeletonFieldSet(canonical);
  let bestCandidate = null;
  let bestPattern = null;
  let bestFieldCount = -1;
  let bestDistance = Number.POSITIVE_INFINITY;
  for (const [candidate, pattern] of Object.entries(localeData.availableFormats ?? {})) {
    const candidateFields = skeletonFieldSet(candidate);
    if (candidateFields.length === 0 || candidateFields === requestedFields) continue;
    if (!fieldSetContains(requestedFields, candidateFields)) continue;
    const fieldCount = candidateFields.length;
    const distance = skeletonDistance(canonical, candidate);
    if (
      fieldCount > bestFieldCount ||
      (fieldCount === bestFieldCount &&
        (distance < bestDistance || (distance === bestDistance && (bestCandidate == null || candidate < bestCandidate))))
    ) {
      bestCandidate = candidate;
      bestPattern = pattern;
      bestFieldCount = fieldCount;
      bestDistance = distance;
    }
  }
  if (bestPattern == null) return null;
  let pattern = adjustPatternWidths(bestPattern, canonical, bestCandidate);
  const currentFields = new Set(skeletonFieldSet(bestCandidate));
  for (const [symbol, width] of skeletonWidths(canonical)) {
    const field = fieldSetSymbol(symbol);
    if (currentFields.has(field)) continue;
    const key = appendItemKey(symbol);
    const fieldSkeleton = symbol.repeat(width);
    const fieldPattern = skeletonPatternWithoutAppend(fieldSkeleton, localeData) ?? fieldSkeleton;
    if (key == null || fieldPattern == null) return null;
    pattern = applyAppendItemPattern(
      appendItemTemplate(localeData, key),
      pattern,
      fieldPattern,
      localeData.fieldNames?.[key] ?? key,
    );
    currentFields.add(field);
  }
  return pattern;
}

function fieldSetContains(container, subset) {
  for (const field of subset) {
    if (!container.includes(field)) return false;
  }
  return true;
}

function applyAppendItemPattern(template, basePattern, fieldPattern, fieldName) {
  return template.replace("{0}", basePattern).replace("{1}", fieldPattern).replace("{2}", quotePatternLiteral(fieldName));
}

function quotePatternLiteral(value) {
  return "'" + String(value).replaceAll("'", "''") + "'";
}

function appendItemTemplate(localeData, key) {
  return localeData.appendItems?.[key] ?? defaultAppendItemTemplate(key);
}

function defaultAppendItemTemplate(key) {
  switch (key) {
    case "Quarter":
    case "Month":
    case "Week":
    case "Day":
    case "Hour":
    case "Minute":
    case "Second":
      return "{0} ({2}: {1})";
    default:
      return "{0} {1}";
  }
}

function hasDateAndTimeFields(canonical) {
  const { dateSkeleton, timeSkeleton } = splitDateTimeSkeleton(canonical);
  return dateSkeleton.length > 0 && timeSkeleton.length > 0;
}

function appendItemKey(symbol) {
  if (symbol === "G") return "Era";
  if (isYearField(symbol)) return "Year";
  if (isQuarterField(symbol)) return "Quarter";
  if (isMonthField(symbol)) return "Month";
  if (symbol === "w" || symbol === "W") return "Week";
  if (symbol === "d" || symbol === "D" || symbol === "F" || symbol === "g") return "Day";
  if (isWeekdayField(symbol)) return "Day-Of-Week";
  if (isHourField(symbol)) return "Hour";
  if (symbol === "m") return "Minute";
  if (symbol === "s" || symbol === "S" || symbol === "A") return "Second";
  if (isTimeZoneField(symbol)) return "Timezone";
  return null;
}

function syntheticSkeletonPattern(canonical, localeData) {
  const widths = skeletonWidths(canonical);
  if (widths.size === 1) {
    const [[symbol, width]] = widths;
    if (symbol === "G") return symbol.repeat(width);
    if (isDayPeriodField(symbol)) return symbol.repeat(width);
    if (isQuarterField(symbol)) return symbol.repeat(width);
    if (isSyntheticNumericField(symbol)) return symbol.repeat(width);
    if (symbol === "S") return symbol.repeat(width);
    if (isTimeZoneField(symbol)) return symbol.repeat(width);
  }
  const fractionalSecond = syntheticFractionalSecondPattern(canonical, localeData, widths);
  if (fractionalSecond != null) return fractionalSecond;
  return null;
}

function syntheticFractionalSecondPattern(canonical, localeData, widths) {
  const fractionWidth = widths.get("S");
  if (fractionWidth == null || !widths.has("s")) return null;
  const baseSkeleton = skeletonWithoutField(canonical, "S");
  const basePattern = skeletonPattern(baseSkeleton, localeData) ?? syntheticSecondsPattern(baseSkeleton);
  return basePattern == null ? null : insertFractionalSecond(basePattern, fractionWidth, localeData.decimalSeparator ?? ".");
}

function syntheticSecondsPattern(canonical) {
  const widths = skeletonWidths(canonical);
  if (widths.size === 1 && widths.has("s")) return "s".repeat(widths.get("s"));
  return null;
}

function skeletonWithoutField(skeleton, removedSymbol) {
  let output = "";
  for (let index = 0; index < skeleton.length;) {
    const symbol = skeleton[index];
    let end = index + 1;
    while (end < skeleton.length && skeleton[end] === symbol) end += 1;
    if (symbol !== removedSymbol) output += skeleton.slice(index, end);
    index = end;
  }
  return output;
}

function insertFractionalSecond(pattern, width, decimalSeparator) {
  let output = "";
  let inQuote = false;
  for (let index = 0; index < pattern.length;) {
    const char = pattern[index];
    if (char === "'") {
      output += char;
      if (pattern[index + 1] === "'") {
        output += "'";
        index += 2;
      } else {
        inQuote = !inQuote;
        index += 1;
      }
    } else if (!inQuote && char === "s") {
      let end = index + 1;
      while (end < pattern.length && pattern[end] === char) end += 1;
      return output + pattern.slice(index, end) + decimalSeparator + "S".repeat(width) + pattern.slice(end);
    } else {
      output += char;
      index += 1;
    }
  }
  return null;
}

function formatComposedSkeleton(rawSkeleton, canonical, date, localeData, timeZone, suppressDayPeriod, dateTimeJoinStyle) {
  const { dateSkeleton, timeSkeleton } = splitDateTimeSkeleton(canonical);
  if (dateSkeleton.length === 0 || timeSkeleton.length === 0) {
    throw new DateTimeCoreError("bad-option", `Unsupported CLDR date/time skeleton: ${rawSkeleton}.`);
  }
  const datePattern = skeletonPattern(dateSkeleton, localeData);
  let timePattern = skeletonPattern(timeSkeleton, localeData);
  if (datePattern == null || timePattern == null) {
    throw new DateTimeCoreError("bad-option", `Unsupported CLDR date/time skeleton: ${rawSkeleton}.`);
  }
  if (suppressDayPeriod) timePattern = stripDayPeriodPatternFields(timePattern);
  const datePart = formatPattern(datePattern, date, localeData, timeZone);
  const timePart = formatPattern(timePattern, date, localeData, timeZone);
  const joinPattern = localeData.dateTimeFormats[dateTimeJoinStyle] ?? localeData.dateTimeFormats.medium ?? "{1} {0}";
  return applyDateTimePattern(joinPattern, datePart, timePart);
}

function canonicalSkeleton(skeleton, localeData, hourCycle, date) {
  const text = skeletonOption(skeleton);
  if (text.startsWith(SEMANTIC_SKELETON_PREFIX)) {
    return canonicalStandardSkeleton(semanticSkeletonToStandard(text.slice(SEMANTIC_SKELETON_PREFIX.length), localeData, date), localeData, hourCycle);
  }
  return canonicalStandardSkeleton(text, localeData, hourCycle);
}

function skeletonOption(value) {
  let text;
  try {
    text = String(value ?? "");
  } catch {
    throw new DateTimeCoreError("bad-option", "skeleton must be coercible to a string.");
  }
  if (text.length > MAX_SKELETON_LENGTH) {
    throw new DateTimeCoreError("bad-option", "Date/time skeleton is too large.");
  }
  return text;
}

function canonicalStandardSkeleton(skeleton, localeData, hourCycle) {
  const widths = new Map();
  for (let index = 0; index < skeleton.length;) {
    const symbol = skeleton[index];
    if (!isAsciiLetter(symbol)) {
      throw new DateTimeCoreError("bad-option", "Date/time skeleton must contain only ASCII pattern letters.");
    }
    let end = index + 1;
    while (end < skeleton.length && skeleton[end] === symbol) end += 1;
    const width = end - index;
    if (width > MAX_SKELETON_FIELD_WIDTH) {
      throw new DateTimeCoreError("bad-option", "Date/time skeleton field width is too large.");
    }
    if (symbol === "C") {
      applyCHourFormat(widths, localeData, hourCycle, width);
    } else {
      const normalized = normalizeSkeletonSymbol(symbol, localeData, hourCycle);
      setSkeletonWidth(widths, normalized, width);
    }
    index = end;
  }
  const canonical = [...SKELETON_FIELD_ORDER]
    .filter((symbol) => widths.has(symbol))
    .map((symbol) => symbol.repeat(widths.get(symbol)))
    .join("");
  if (canonical.length === 0) {
    throw new DateTimeCoreError("bad-option", "Date/time skeleton must not be empty.");
  }
  return canonical;
}

function semanticSkeletonToStandard(body, localeData, date) {
  const options = parseSemanticSkeletonOptions(body);
  const fields = parseSemanticSkeletonFields(options);
  validateSemanticSkeleton(fields, options);
  const length = semanticOption(options, "length", "medium", SEMANTIC_LENGTH_VALUES);
  const alignment = semanticOption(options, "alignment", "inline", ["inline", "column"]);
  const yearStyle = semanticOption(options, "yearstyle", "auto", ["auto", "full", "with-era", "numeric", "2-digit"]);
  const eraStyle = semanticOption(options, "erastyle", "auto", SEMANTIC_TEXT_STYLE_VALUES);
  const monthStyle = semanticOption(options, "monthstyle", "auto", SEMANTIC_DATE_STYLE_VALUES);
  const quarterStyle = semanticOption(options, "quarterstyle", "auto", SEMANTIC_DATE_STYLE_VALUES);
  const dayStyle = semanticOption(options, "daystyle", "auto", SEMANTIC_NUMERIC_STYLE_VALUES);
  const weekdayStyle = semanticOption(options, "weekdaystyle", "auto", SEMANTIC_TEXT_STYLE_VALUES);
  const dayPeriodStyle = semanticOption(options, "dayperiodstyle", "auto", SEMANTIC_TEXT_STYLE_VALUES);
  semanticOption(options, "hourstyle", "auto", SEMANTIC_NUMERIC_STYLE_VALUES);
  semanticOption(options, "minutestyle", "auto", SEMANTIC_NUMERIC_STYLE_VALUES);
  semanticOption(options, "secondstyle", "auto", SEMANTIC_NUMERIC_STYLE_VALUES);
  const timePrecision = semanticOption(options, "timeprecision", "second", ["hour", "minute", "minute-optional", "second", "fractional-second"]);
  const timeStyle = semanticOption(options, "timestyle", "auto", ["auto", "short", "medium", "long", "full"]);
  const effectiveTimePrecision = semanticTimeStylePrecision(timeStyle, timePrecision);
  const hourCycle = semanticOption(options, "hourcycle", "auto", ["auto", "h11", "h12", "h23", "h24", "clock12", "clock24"]);
  const zoneStyle = semanticOption(options, "zonestyle", "auto", ["auto", "generic", "specific", "location", "offset"]);
  const effectiveZoneStyle = semanticTimeStyleZoneStyle(timeStyle, zoneStyle);
  const effectiveZoneStandalone = fields.size === 1 || timeStyle === "full";
  const effectiveZoneLength = timeStyle === "long" || timeStyle === "full" ? timeStyle : length;
  const dateWidths = semanticDateFieldWidths(localeData, length);
  const output = [];
  if (fields.has("era")) output.push(semanticEraSkeleton(dateWidths, length, eraStyle));
  if (fields.has("year")) output.push(semanticYearSkeleton(dateWidths, yearStyle, !fields.has("era")));
  if (fields.has("quarter")) output.push(semanticQuarterSkeleton(fields, length, alignment, quarterStyle));
  if (fields.has("month")) output.push(semanticMonthSkeleton(fields, dateWidths, length, alignment, monthStyle));
  if (fields.has("weekofmonth")) output.push("W");
  if (fields.has("day")) output.push(semanticDaySkeleton(dateWidths, alignment, dayStyle));
  if (fields.has("dayofyear")) output.push("D".repeat(alignment === "column" ? 3 : 1));
  if (fields.has("dayofweekinmonth")) output.push("F".repeat(alignment === "column" ? 2 : 1));
  if (fields.has("modifiedjulianday")) output.push("g".repeat(alignment === "column" ? 6 : 1));
  if (fields.has("weekday")) output.push(semanticWeekdaySkeleton(fields, length, weekdayStyle));
  if (fields.has("weekofyear")) output.push("w".repeat(alignment === "column" ? 2 : 1));
  if (fields.has("dayperiod")) output.push(semanticDayPeriodSkeleton(length, dayPeriodStyle));
  if (hasSemanticTimeComponents(fields)) output.push(semanticExplicitTimeSkeleton(fields, hourCycle, alignment, options));
  if (fields.has("time")) output.push(semanticTimeSkeleton(effectiveTimePrecision, hourCycle, alignment, date, options));
  if (fields.has("zone")) output.push(semanticZoneSkeleton(effectiveZoneStyle, effectiveZoneStandalone, effectiveZoneLength));
  const standard = output.join("");
  if (standard.length === 0) {
    throw new DateTimeCoreError("bad-option", "Date/time semantic skeleton must include at least one field.");
  }
  return standard;
}

function parseSemanticSkeletonOptions(body) {
  const options = new Map();
  const parts = String(body ?? "").split(";").map((part) => part.trim()).filter(Boolean);
  if (parts.length === 0) {
    throw new DateTimeCoreError("bad-option", "Date/time semantic skeleton must include fields.");
  }
  let implicitDateStyle = null;
  let implicitTimeFields = false;
  for (const [index, part] of parts.entries()) {
    const equals = part.indexOf("=");
    const rawKey = equals < 0 ? (index === 0 ? "fields" : "") : part.slice(0, equals);
    const rawValue = equals < 0 ? part : part.slice(equals + 1);
    const rawKeyAlias = normalizeSemanticKeyAlias(rawKey);
    const key = normalizeSemanticOptionKey(rawKey);
    const value = normalizeSemanticOptionValue(key, rawValue);
    if (key === "" || value === "" || options.has(key) || !SEMANTIC_OPTION_KEYS.has(key)) {
      throw new DateTimeCoreError("bad-option", "Invalid date/time semantic skeleton option.");
    }
    if (rawKeyAlias === "style" || rawKeyAlias === "datestyle" || rawKeyAlias === "datelength") implicitDateStyle = value;
    if (rawKeyAlias === "timestyle") implicitTimeFields = true;
    options.set(key, value);
  }
  if (!options.has("fields")) {
    const fields = implicitSemanticFields(implicitDateStyle, implicitTimeFields, options.get("timestyle"));
    if (fields != null) options.set("fields", fields);
  }
  return options;
}

function implicitSemanticFields(dateStyle, hasTimeStyle, timeStyle) {
  const dateFields = dateStyle === "full" ? "date,weekday" : "date";
  if (dateStyle != null && hasTimeStyle) return timeStyle === "long" || timeStyle === "full" ? `${dateFields},time,zone` : `${dateFields},time`;
  if (dateStyle != null) return dateFields;
  if (hasTimeStyle) return timeStyle === "long" || timeStyle === "full" ? "time,zone" : "time";
  return null;
}

function normalizeSemanticKeyAlias(value) {
  return String(value ?? "").trim().replace(/[-_]/g, "").toLowerCase();
}

function normalizeSemanticOptionKey(value) {
  const normalized = normalizeSemanticKeyAlias(value);
  if (normalized === "style" || normalized === "datestyle" || normalized === "datelength") return "length";
  if (normalized === "precision") return "timeprecision";
  if (normalized === "timestyle") return "timestyle";
  if (normalized === "hour12") return "hourcycle";
  if (normalized === "zone" || normalized === "timezonename" || normalized === "timezonestyle") return "zonestyle";
  if (normalized === "fractionalseconddigits") return "fractionalsecond";
  const fieldStyleAlias = SEMANTIC_FIELD_STYLE_OPTION_ALIASES.get(normalized);
  if (fieldStyleAlias != null) return fieldStyleAlias;
  return normalized;
}

function normalizeSemanticOptionValue(key, value) {
  if (key === "fields") return value.trim().toLowerCase();
  const normalized = value.trim().replace(/[-_]/g, "").toLowerCase();
  if (key === "yearstyle" && normalized === "withera") return "with-era";
  if (SEMANTIC_STYLE_OPTION_KEYS.has(key) && (normalized === "2digit" || normalized === "twodigit")) return "2-digit";
  if (SEMANTIC_STYLE_OPTION_KEYS.has(key) && normalized === "wide") return "long";
  if (SEMANTIC_STYLE_OPTION_KEYS.has(key) && normalized === "abbreviated") return "short";
  if (key === "timeprecision" && normalized === "short") return "minute";
  if (key === "timeprecision" && normalized === "medium") return "second";
  if (key === "timeprecision" && normalized === "minuteoptional") return "minute-optional";
  if (key === "timeprecision" && normalized === "fractionalsecond") return "fractional-second";
  if (key === "zonestyle" && (normalized === "shortoffset" || normalized === "longoffset")) return "offset";
  if (key === "zonestyle" && (normalized === "shortgeneric" || normalized === "longgeneric")) return "generic";
  if (key === "zonestyle" && (normalized === "short" || normalized === "long")) return "specific";
  if (key === "hourcycle" && normalized === "true") return "clock12";
  if (key === "hourcycle" && normalized === "false") return "clock24";
  return normalized;
}

function parseSemanticSkeletonFields(options) {
  const fieldsText = options.get("fields");
  if (fieldsText == null) {
    throw new DateTimeCoreError("bad-option", "Date/time semantic skeleton must include fields.");
  }
  const fields = new Set();
  for (const field of fieldsText.split(",")) {
    const normalized = normalizeSemanticField(field);
    const canonicalFields = normalized === "date" || normalized === "yearmonthday"
      ? ["year", "month", "day"]
      : normalized === "eradate" || normalized === "erayearmonthday"
        ? ["era", "year", "month", "day"]
      : normalized === "eradateweekday" || normalized === "weekdayeradate" || normalized === "erayearmonthdayweekday" || normalized === "weekdayerayearmonthday"
        ? ["era", "year", "month", "day", "weekday"]
      : normalized === "eradatetime" || normalized === "erayearmonthdaytime"
        ? ["era", "year", "month", "day", "time"]
      : normalized === "eradatetimeweekday" || normalized === "weekdayeradatetime" || normalized === "erayearmonthdaytimeweekday" || normalized === "weekdayerayearmonthdaytime"
        ? ["era", "year", "month", "day", "weekday", "time"]
      : normalized === "datetime" || normalized === "yearmonthdaytime"
        ? ["year", "month", "day", "time"]
      : normalized === "datetimeweekday" || normalized === "weekdaydatetime" || normalized === "yearmonthdaytimeweekday" || normalized === "weekdayyearmonthdaytime"
        ? ["year", "month", "day", "weekday", "time"]
      : normalized === "datetimeweekdayzone" || normalized === "weekdaydatetimezone" || normalized === "zoneddatetimeweekday" || normalized === "zonedweekdaydatetime" || normalized === "yearmonthdaytimeweekdayzone" || normalized === "weekdayyearmonthdaytimezone" || normalized === "zonedyearmonthdaytimeweekday" || normalized === "zonedweekdayyearmonthdaytime"
        ? ["year", "month", "day", "weekday", "time", "zone"]
      : normalized === "eradatetimezone" || normalized === "zonederadatetime" || normalized === "erayearmonthdaytimezone" || normalized === "zonederayearmonthdaytime"
        ? ["era", "year", "month", "day", "time", "zone"]
      : normalized === "eradatetimeweekdayzone" || normalized === "weekdayeradatetimezone" || normalized === "zonederadatetimeweekday" || normalized === "zonedweekdayeradatetime" || normalized === "erayearmonthdaytimeweekdayzone" || normalized === "weekdayerayearmonthdaytimezone" || normalized === "zonederayearmonthdaytimeweekday" || normalized === "zonedweekdayerayearmonthdaytime"
        ? ["era", "year", "month", "day", "weekday", "time", "zone"]
      : normalized === "dateweekday" || normalized === "weekdaydate" || normalized === "yearmonthdayweekday" || normalized === "weekdayyearmonthday"
        ? ["year", "month", "day", "weekday"]
        : normalized === "datetimezone" || normalized === "zoneddatetime" || normalized === "yearmonthdaytimezone" || normalized === "zonedyearmonthdaytime"
          ? ["year", "month", "day", "time", "zone"]
        : normalized === "yearmonth"
          ? ["year", "month"]
          : normalized === "erayearmonth"
            ? ["era", "year", "month"]
          : normalized === "yearquarter"
            ? ["year", "quarter"]
            : normalized === "erayearquarter"
              ? ["era", "year", "quarter"]
              : normalized === "yearweek"
                ? ["year", "weekofyear"]
                : normalized === "erayearweek"
                  ? ["era", "year", "weekofyear"]
                  : normalized === "erayear"
                    ? ["era", "year"]
                    : normalized === "monthweek"
                      ? ["month", "weekofmonth"]
                      : normalized === "yearmonthweek"
                        ? ["year", "month", "weekofmonth"]
                        : normalized === "erayearmonthweek"
                          ? ["era", "year", "month", "weekofmonth"]
                          : normalized === "monthday"
                            ? ["month", "day"]
                            : [normalized];
    for (const canonical of canonicalFields) {
      if (!SEMANTIC_FIELD_ORDER.includes(canonical) || fields.has(canonical)) {
        throw new DateTimeCoreError("bad-option", "Invalid date/time semantic skeleton field.");
      }
      fields.add(canonical);
    }
  }
  if (fields.size === 0) {
    throw new DateTimeCoreError("bad-option", "Date/time semantic skeleton must include fields.");
  }
  return fields;
}

function normalizeSemanticField(value) {
  const normalized = String(value ?? "").trim().replace(/[-_]/g, "").toLowerCase();
  if (normalized === "dayofmonth") return "day";
  if (normalized === "dayofweek") return "weekday";
  if (normalized === "monthofyear") return "month";
  if (normalized === "quarterofyear") return "quarter";
  if (normalized === "yearofera") return "year";
  if (normalized === "week") return "weekofyear";
  if (normalized === "weekofyear") return "weekofyear";
  if (normalized === "weekofmonth") return "weekofmonth";
  if (normalized === "dayofyear") return "dayofyear";
  if (normalized === "dayofweekinmonth") return "dayofweekinmonth";
  if (normalized === "modifiedjulianday") return "modifiedjulianday";
  if (normalized === "millisecondsinday") return "millisecondsinday";
  if (normalized === "fractionalseconddigits") return "fractionalsecond";
  if (normalized === "dayperiod") return "dayperiod";
  if (normalized === "hourofday") return "hour";
  if (normalized === "minuteofhour") return "minute";
  if (normalized === "secondofminute") return "second";
  if (normalized === "timezonename") return "zone";
  if (normalized === "timezone") return "zone";
  return normalized;
}

function validateSemanticSkeleton(fields, options) {
  const dateKey = semanticFieldSetKey(fields, SEMANTIC_DATE_FIELD_ORDER);
  const timeKey = semanticFieldSetKey(fields, SEMANTIC_TIME_FIELD_ORDER);
  const hasDateFields = dateKey.length > 0;
  const hasExplicitTime = timeKey.length > 0;
  const hasTime = fields.has("time") || hasExplicitTime;
  const hasZone = fields.has("zone");
  const hasDayPeriod = fields.has("dayperiod");
  const validDateFields = hasTime || hasZone
    ? !hasDateFields || SEMANTIC_DATE_FIELD_SETS.has(dateKey)
    : !hasDateFields || SEMANTIC_DATE_FIELD_SETS.has(dateKey) || SEMANTIC_CALENDAR_PERIOD_FIELD_SETS.has(dateKey);
  const validFieldSet = hasDayPeriod
    ? validDateFields && (!hasZone || hasTime)
    : hasTime || hasZone
      ? !hasDateFields || SEMANTIC_DATE_FIELD_SETS.has(dateKey)
      : SEMANTIC_DATE_FIELD_SETS.has(dateKey) || SEMANTIC_CALENDAR_PERIOD_FIELD_SETS.has(dateKey);
  if (!validFieldSet) {
    throw new DateTimeCoreError("bad-option", "Invalid date/time semantic skeleton field set.");
  }
  if (fields.has("time") && hasExplicitTime) {
    throw new DateTimeCoreError("bad-option", "time field cannot be combined with explicit time component fields.");
  }
  if (options.has("timestyle") && options.has("timeprecision")) {
    throw new DateTimeCoreError("bad-option", "timeStyle cannot be combined with timePrecision.");
  }
  const timeStyle = options.get("timestyle");
  if (options.has("timestyle") && !fields.has("time")) {
    throw new DateTimeCoreError("bad-option", "timeStyle requires the time field.");
  }
  if ((timeStyle === "long" || timeStyle === "full") && !hasZone) {
    throw new DateTimeCoreError("bad-option", "timeStyle=long/full requires the zone field.");
  }
  if ((timeStyle === "long" || timeStyle === "full") && options.has("zonestyle")) {
    throw new DateTimeCoreError("bad-option", "timeStyle=long/full cannot be combined with zoneStyle.");
  }
  if (hasExplicitTime && !SEMANTIC_TIME_FIELD_SETS.has(timeKey)) {
    throw new DateTimeCoreError("bad-option", "Invalid date/time semantic skeleton time field set.");
  }
  if (hasExplicitTime && options.has("timeprecision")) {
    throw new DateTimeCoreError("bad-option", "timePrecision requires the time field.");
  }
  if (hasExplicitTime && options.has("fractionalsecond") && !fields.has("fractionalsecond")) {
    throw new DateTimeCoreError("bad-option", "fractionalSecond requires the fractionalSecond field.");
  }
  if (fields.has("fractionalsecond")) {
    semanticFractionalSecondWidth(options);
  }
  if (hasExplicitTime && !fields.has("hour") && (options.has("hourcycle") || hasDayPeriod)) {
    throw new DateTimeCoreError("bad-option", "hourCycle and dayPeriod require the hour field.");
  }
  if (!fields.has("hour") && options.has("hourstyle")) {
    throw new DateTimeCoreError("bad-option", "hourStyle requires the hour field.");
  }
  if (!fields.has("minute") && options.has("minutestyle")) {
    throw new DateTimeCoreError("bad-option", "minuteStyle requires the minute field.");
  }
  if (!fields.has("second") && options.has("secondstyle")) {
    throw new DateTimeCoreError("bad-option", "secondStyle requires the second field.");
  }
  if (!fields.has("year") && options.has("yearstyle")) {
    throw new DateTimeCoreError("bad-option", "yearStyle requires the year field.");
  }
  if (!fields.has("era") && options.has("erastyle")) {
    throw new DateTimeCoreError("bad-option", "eraStyle requires the era field.");
  }
  if (!fields.has("month") && options.has("monthstyle")) {
    throw new DateTimeCoreError("bad-option", "monthStyle requires the month field.");
  }
  if (!fields.has("quarter") && options.has("quarterstyle")) {
    throw new DateTimeCoreError("bad-option", "quarterStyle requires the quarter field.");
  }
  if (!fields.has("day") && options.has("daystyle")) {
    throw new DateTimeCoreError("bad-option", "dayStyle requires the day field.");
  }
  if (!fields.has("weekday") && options.has("weekdaystyle")) {
    throw new DateTimeCoreError("bad-option", "weekdayStyle requires the weekday field.");
  }
  if (!hasDayPeriod && options.has("dayperiodstyle")) {
    throw new DateTimeCoreError("bad-option", "dayPeriodStyle requires the dayPeriod field.");
  }
  if (!hasTime && (options.has("timeprecision") || options.has("timestyle") || options.has("fractionalsecond") || options.has("hourcycle"))) {
    throw new DateTimeCoreError("bad-option", "timePrecision and hourCycle require the time field.");
  }
  if (!hasZone && options.has("zonestyle")) {
    throw new DateTimeCoreError("bad-option", "zoneStyle requires the zone field.");
  }
  if (!(fields.has("year") || fields.has("quarter") || fields.has("month") || fields.has("day") || fields.has("dayofyear") || fields.has("dayofweekinmonth") || fields.has("modifiedjulianday") || hasTime) && options.has("alignment")) {
    throw new DateTimeCoreError("bad-option", "alignment requires a date or time field.");
  }
}

function semanticOption(options, key, fallback, allowedValues) {
  const value = options.get(key) ?? fallback;
  if (!allowedValues.includes(value)) {
    throw new DateTimeCoreError("bad-option", `Date/time semantic skeleton ${key} must be one of ${allowedValues.join(", ")}.`);
  }
  return value;
}

function semanticFieldSetKey(fields, order) {
  return order.filter((field) => fields.has(field)).join(",");
}

function semanticDateFieldWidths(localeData, length) {
  const pattern = localeData.dateFormats?.[length] ?? "";
  const widths = new Map();
  for (const [symbol, width] of patternFieldRuns(pattern)) {
    if (symbol === "G" || isYearField(symbol) || isMonthField(symbol) || symbol === "d") {
      setSkeletonWidth(widths, symbol, width);
    }
  }
  if (![...widths.keys()].some(isYearField)) widths.set("y", length === "short" ? 2 : 1);
  if (![...widths.keys()].some(isMonthField)) widths.set("M", isWideLength(length) ? 4 : length === "medium" ? 3 : 1);
  if (!widths.has("d")) widths.set("d", 1);
  return widths;
}

function patternFieldRuns(pattern) {
  const fields = [];
  let inQuote = false;
  for (let index = 0; index < pattern.length;) {
    const char = pattern[index];
    if (char === "'") {
      if (pattern[index + 1] === "'") {
        index += 2;
      } else {
        inQuote = !inQuote;
        index += 1;
      }
    } else if (!inQuote && isAsciiLetter(char)) {
      let end = index + 1;
      while (end < pattern.length && pattern[end] === char) end += 1;
      fields.push([char, end - index]);
      index = end;
    } else {
      index += 1;
    }
  }
  return fields;
}

function semanticEraSkeleton(dateWidths, length, eraStyle = "auto") {
  const width = eraStyle === "auto" ? dateWidths.get("G") ?? (isWideLength(length) ? 4 : 1) : eraStyleWidth(eraStyle);
  return "G".repeat(width);
}

function eraStyleWidth(style) {
  if (style === "long") return 4;
  if (style === "narrow") return 5;
  return 1;
}

function semanticYearSkeleton(dateWidths, yearStyle, includeEra = true) {
  const yearSymbol = ["y", "u", "r"].find((symbol) => dateWidths.has(symbol)) ?? "y";
  const sourceWidth = dateWidths.get(yearSymbol) ?? 1;
  const yearWidth = semanticYearWidth(sourceWidth, yearStyle);
  let skeleton = yearSymbol.repeat(yearWidth);
  if (includeEra && dateWidths.has("G")) skeleton = "G".repeat(dateWidths.get("G")) + skeleton;
  if (includeEra && yearStyle === "with-era" && !dateWidths.has("G")) skeleton = "G" + skeleton;
  return skeleton;
}

function semanticYearWidth(sourceWidth, yearStyle) {
  if (yearStyle === "auto") return sourceWidth;
  if (yearStyle === "2-digit") return 2;
  if (yearStyle === "numeric") return 1;
  return sourceWidth === 2 ? 1 : sourceWidth;
}

function semanticQuarterSkeleton(fields, length, alignment, quarterStyle = "auto") {
  const symbol = fields.size === 1 ? "q" : "Q";
  const width = quarterStyle === "auto" ? lengthStyleWidth(length) : dateFieldStyleWidth(quarterStyle);
  return symbol.repeat(alignment === "column" && width < 3 ? Math.max(width, 2) : width);
}

function semanticMonthSkeleton(fields, dateWidths, length, alignment, monthStyle = "auto") {
  if (fields.size === 1) {
    const width = monthStyle === "auto" ? lengthStyleWidth(length) : dateFieldStyleWidth(monthStyle);
    return "L".repeat(alignment === "column" && width < 3 ? Math.max(width, 2) : width);
  }
  const symbol = ["M", "L"].find((candidate) => dateWidths.has(candidate)) ?? "M";
  const width = monthStyle === "auto" ? dateWidths.get(symbol) ?? lengthStyleWidth(length) : dateFieldStyleWidth(monthStyle);
  return symbol.repeat(alignment === "column" && width < 3 ? Math.max(width, 2) : width);
}

function semanticDaySkeleton(dateWidths, alignment, dayStyle = "auto") {
  const width = dayStyle === "auto" ? dateWidths.get("d") ?? 1 : dateFieldStyleWidth(dayStyle);
  return "d".repeat(alignment === "column" && width < 3 ? Math.max(width, 2) : width);
}

function lengthStyleWidth(length) {
  return isWideLength(length) ? 4 : length === "medium" ? 3 : 1;
}

function isWideLength(length) {
  return length === "full" || length === "long";
}

function dateFieldStyleWidth(style) {
  if (style === "numeric") return 1;
  if (style === "2-digit") return 2;
  if (style === "short") return 3;
  if (style === "long") return 4;
  return 5;
}

function semanticWeekdaySkeleton(fields, length, weekdayStyle = "auto") {
  if (weekdayStyle === "short") return "EEE";
  if (weekdayStyle === "long") return "EEEE";
  if (weekdayStyle === "narrow") return "EEEEE";
  if (fields.size === 1 && length === "short") return "EEEEE";
  if (isWideLength(length)) return "EEEE";
  return "EEE";
}

function semanticDayPeriodSkeleton(length, dayPeriodStyle = "auto") {
  const style = dayPeriodStyle === "auto" ? length : dayPeriodStyle;
  return "B".repeat(isWideLength(style) ? 4 : style === "narrow" || (dayPeriodStyle === "auto" && length === "short") ? 5 : 1);
}

function hasSemanticTimeComponents(fields) {
  return fields.has("hour") || fields.has("minute") || fields.has("second") || fields.has("fractionalsecond") || fields.has("millisecondsinday");
}

function semanticExplicitTimeSkeleton(fields, hourCycle, alignment, options = new Map()) {
  const hasHour = fields.has("hour");
  const hasMinute = fields.has("minute");
  const hasSecond = fields.has("second");
  const hasFractionalSecond = fields.has("fractionalsecond");
  const hasMillisecondsInDay = fields.has("millisecondsinday");
  let skeleton = "";
  if (hasHour) skeleton += semanticHourSymbol(hourCycle).repeat(semanticNumericFieldWidth(options, "hourstyle", alignment === "column" ? 2 : 1));
  if (hasMinute) skeleton += "m".repeat(semanticNumericFieldWidth(options, "minutestyle", !hasHour && !hasSecond && alignment === "column" ? 2 : 1));
  if (hasSecond) skeleton += "s".repeat(semanticNumericFieldWidth(options, "secondstyle", !hasHour && !hasMinute && alignment === "column" ? 2 : 1));
  if (hasFractionalSecond) skeleton += "S".repeat(semanticFractionalSecondWidth(options));
  if (hasMillisecondsInDay) skeleton += "A".repeat(alignment === "column" ? 8 : 1);
  return skeleton;
}

function semanticNumericFieldWidth(options, key, fallbackWidth) {
  const style = options.get(key) ?? "auto";
  if (style === "auto") return fallbackWidth;
  if (style === "2-digit") return 2;
  return 1;
}

function semanticFractionalSecondWidth(options) {
  const fractionalSecond = options.get("fractionalsecond");
  const width = Number(fractionalSecond);
  if (!Number.isInteger(width) || width < 1 || width > 9) {
    throw new DateTimeCoreError("bad-option", "Date/time semantic skeleton fractionalSecond must be an integer from 1 to 9.");
  }
  return width;
}

function semanticTimeSkeleton(timePrecision, hourCycle, alignment, date, options) {
  let skeleton = semanticHourSymbol(hourCycle).repeat(alignment === "column" ? 2 : 1);
  if (timePrecision === "minute" || timePrecision === "second" || timePrecision === "fractional-second") skeleton += "m";
  if (timePrecision === "minute-optional" && date.getUTCMinutes() !== 0) skeleton += "m";
  if (timePrecision === "second" || timePrecision === "fractional-second") skeleton += "s";
  if (timePrecision === "fractional-second") {
    const fractionalSecond = options.get("fractionalsecond");
    const width = Number(fractionalSecond);
    if (!Number.isInteger(width) || width < 1 || width > 9) {
      throw new DateTimeCoreError("bad-option", "Date/time semantic skeleton fractionalSecond must be an integer from 1 to 9.");
    }
    skeleton += "S".repeat(width);
  } else if (options.has("fractionalsecond")) {
    throw new DateTimeCoreError("bad-option", "fractionalSecond requires timePrecision=fractional-second.");
  }
  return skeleton;
}

function semanticTimeStylePrecision(timeStyle, timePrecision) {
  if (timeStyle === "short") return "minute";
  if (timeStyle === "medium" || timeStyle === "long" || timeStyle === "full") return "second";
  return timePrecision;
}

function semanticTimeStyleZoneStyle(timeStyle, zoneStyle) {
  if (timeStyle === "long" || timeStyle === "full") return "specific";
  return zoneStyle;
}

function semanticHourSymbol(hourCycle) {
  if (hourCycle === "h11") return "K";
  if (hourCycle === "h12" || hourCycle === "clock12") return "h";
  if (hourCycle === "h23" || hourCycle === "clock24") return "H";
  if (hourCycle === "h24") return "k";
  return "C";
}

function semanticZoneSkeleton(zoneStyle, standalone, length) {
  const style = zoneStyle === "auto" ? "generic" : zoneStyle;
  if (style === "specific") return standalone && length !== "short" ? "zzzz" : "z";
  if (style === "location") return "VVVV";
  if (style === "offset") return "O";
  return standalone && length !== "short" ? "vvvv" : "v";
}

function applyCHourFormat(widths, localeData, hourCycle, width) {
  if (hourCycle != null) {
    const hourSymbol = preferredHourSymbol(localeData, hourCycle);
    setSkeletonWidth(widths, hourSymbol, cHourWidth(width));
    if (isHour12Field(hourSymbol)) setSkeletonWidth(widths, "B", dayPeriodWidthForC(width));
    return;
  }
  for (const token of String(localeData.allowedHourFormats ?? "").split(/\s+/)) {
    if (!isCHourFormatToken(token)) continue;
    setSkeletonWidth(widths, token[0], cHourWidth(width));
    if (token.length > 1) setSkeletonWidth(widths, token[1], dayPeriodWidthForC(width));
    return;
  }
  const hourSymbol = preferredHourSymbol(localeData, hourCycle);
  setSkeletonWidth(widths, hourSymbol, cHourWidth(width));
}

function isCHourFormatToken(token) {
  return /^[hHkK][bB]?$/.test(token);
}

function setSkeletonWidth(widths, symbol, width) {
  widths.set(symbol, Math.max(widths.get(symbol) ?? 0, width));
}

function normalizeSkeletonSymbol(symbol, localeData, hourCycle) {
  if (symbol === "l") return "L";
  if (symbol === "j" || symbol === "J") return preferredHourSymbol(localeData, hourCycle);
  return symbol;
}

function cHourWidth(width) {
  return width % 2 === 0 ? 2 : 1;
}

function dayPeriodWidthForC(width) {
  if (width >= 5) return 5;
  if (width >= 3) return 4;
  return 1;
}

function shouldSuppressDayPeriod(skeleton) {
  const text = String(skeleton ?? "");
  return text.includes("J") && !/[abBC]/.test(text);
}

function stripDayPeriodPatternFields(pattern) {
  let output = "";
  let pendingWhitespace = "";
  for (let index = 0; index < pattern.length;) {
    const char = pattern[index];
    if (char === "'") {
      const quoted = readQuotedPattern(pattern, index);
      output += pendingWhitespace + pattern.slice(index, quoted.nextIndex);
      pendingWhitespace = "";
      index = quoted.nextIndex;
    } else if (isAsciiLetter(char)) {
      let end = index + 1;
      while (end < pattern.length && pattern[end] === char) end += 1;
      if (isDayPeriodField(char)) {
        pendingWhitespace = "";
      } else {
        output += pendingWhitespace + pattern.slice(index, end);
        pendingWhitespace = "";
      }
      index = end;
    } else if (isPatternWhitespace(char)) {
      pendingWhitespace += char;
      index += 1;
    } else {
      output += pendingWhitespace + char;
      pendingWhitespace = "";
      index += 1;
    }
  }
  return (output + pendingWhitespace).trim();
}

function isPatternWhitespace(char) {
  return char === " " || char === "\u00A0" || char === "\u202F" || /\s/.test(char);
}

function preferredHourSymbol(localeData, hourCycle) {
  if (hourCycle === "h11") return "K";
  if (hourCycle === "h12") return "h";
  if (hourCycle === "h23") return "H";
  if (hourCycle === "h24") return "k";
  const shortTime = localeData.timeFormats?.short ?? "";
  if (shortTime.includes("H")) return "H";
  if (shortTime.includes("k")) return "k";
  if (shortTime.includes("K")) return "K";
  return "h";
}

function skeletonFieldSet(skeleton) {
  return [...new Set([...skeletonWidths(skeleton)].map(([symbol]) => fieldSetSymbol(symbol)))].sort().join("");
}

function fieldSetSymbol(symbol) {
  if (isYearField(symbol)) return "y";
  if (isHourField(symbol)) return "j";
  if (isMonthField(symbol)) return "M";
  if (isQuarterField(symbol)) return "Q";
  if (isDayPeriodField(symbol)) return "B";
  if (isWeekdayField(symbol)) return "E";
  if (isTimeZoneField(symbol)) return "v";
  return symbol;
}

function skeletonDistance(requested, candidate) {
  const requestedWidths = skeletonWidths(requested);
  const candidateWidths = skeletonWidths(candidate);
  let distance = 0;
  for (const [symbol, requestedWidth] of requestedWidths) {
    const candidateSymbol = candidateSymbolForRequested(symbol, candidateWidths);
    const candidateWidth = candidateSymbol == null ? 0 : candidateWidths.get(candidateSymbol);
    distance += Math.abs(requestedWidth - candidateWidth);
    if (isTextWidth(requestedWidth) !== isTextWidth(candidateWidth)) distance += 8;
    distance += hourFieldDistance(symbol, candidateSymbol);
  }
  return distance;
}

function skeletonWidths(skeleton) {
  const widths = new Map();
  for (let index = 0; index < skeleton.length;) {
    const symbol = skeleton[index];
    let end = index + 1;
    while (end < skeleton.length && skeleton[end] === symbol) end += 1;
    widths.set(symbol, Math.max(widths.get(symbol) ?? 0, end - index));
    index = end;
  }
  return widths;
}

function isTextWidth(width) {
  return width >= 3;
}

function isHourField(symbol) {
  return SKELETON_HOUR_FIELDS.has(symbol);
}

function isYearField(symbol) {
  return symbol === "y" || symbol === "u" || symbol === "r";
}

function isWeekdayField(symbol) {
  return symbol === "E" || symbol === "e" || symbol === "c";
}

function isMonthField(symbol) {
  return symbol === "M" || symbol === "L";
}

function isQuarterField(symbol) {
  return symbol === "Q" || symbol === "q";
}

function isDayPeriodField(symbol) {
  return symbol === "a" || symbol === "b" || symbol === "B";
}

function isSyntheticNumericField(symbol) {
  return symbol === "D" || symbol === "F" || symbol === "g" || symbol === "m" || symbol === "s" || symbol === "A";
}

function isTimeZoneField(symbol) {
  return symbol === "z" || symbol === "Z" || symbol === "O" || symbol === "v" || symbol === "V" || symbol === "X" || symbol === "x";
}

function candidateSymbolForRequested(symbol, candidateWidths) {
  if (candidateWidths.has(symbol)) return symbol;
  if (isYearField(symbol)) {
    for (const yearSymbol of ["y", "u", "r"]) {
      if (candidateWidths.has(yearSymbol)) return yearSymbol;
    }
  }
  if (isHourField(symbol)) {
    for (const hourSymbol of SKELETON_HOUR_FIELDS) {
      if (candidateWidths.has(hourSymbol)) return hourSymbol;
    }
    return null;
  }
  if (isQuarterField(symbol)) {
    for (const quarterSymbol of ["Q", "q"]) {
      if (candidateWidths.has(quarterSymbol)) return quarterSymbol;
    }
  }
  if (isMonthField(symbol)) {
    for (const monthSymbol of ["M", "L"]) {
      if (candidateWidths.has(monthSymbol)) return monthSymbol;
    }
  }
  if (isDayPeriodField(symbol)) {
    for (const dayPeriodSymbol of ["B", "b", "a"]) {
      if (candidateWidths.has(dayPeriodSymbol)) return dayPeriodSymbol;
    }
  }
  if (isWeekdayField(symbol)) {
    for (const weekdaySymbol of ["E", "e", "c"]) {
      if (candidateWidths.has(weekdaySymbol)) return weekdaySymbol;
    }
  }
  if (isTimeZoneField(symbol)) {
    for (const timeZoneSymbol of ["v", "z", "O", "Z", "X", "x", "V"]) {
      if (candidateWidths.has(timeZoneSymbol)) return timeZoneSymbol;
    }
  }
  return null;
}

function hourFieldDistance(requestedSymbol, candidateSymbol) {
  if (requestedSymbol === candidateSymbol) return 0;
  if (!isHourField(requestedSymbol) || !isHourField(candidateSymbol)) return 0;
  return isHour12Field(requestedSymbol) === isHour12Field(candidateSymbol) ? 1 : 4;
}

function isHour12Field(symbol) {
  return symbol === "h" || symbol === "K";
}

function requestedSymbolForPattern(symbol, requestedWidths, candidateWidths) {
  if (isYearField(symbol) && candidateSymbolForRequested(symbol, candidateWidths) != null) {
    return candidateSymbolForRequested(symbol, requestedWidths) ?? symbol;
  }
  if (isWeekdayField(symbol) && candidateSymbolForRequested(symbol, candidateWidths) != null) {
    return requestedWeekdaySymbolForPattern(symbol, requestedWidths);
  }
  if (isDayPeriodField(symbol) && candidateSymbolForRequested(symbol, candidateWidths) != null) {
    return requestedDayPeriodSymbolForPattern(symbol, requestedWidths);
  }
  if (isTimeZoneField(symbol) && candidateSymbolForRequested(symbol, candidateWidths) != null) {
    return requestedTimeZoneSymbolForPattern(symbol, requestedWidths);
  }
  if ((!isYearField(symbol) && !isHourField(symbol) && !isMonthField(symbol) && !isQuarterField(symbol) && !isDayPeriodField(symbol) && !isTimeZoneField(symbol)) || candidateSymbolForRequested(symbol, candidateWidths) == null) return symbol;
  return candidateSymbolForRequested(symbol, requestedWidths) ?? symbol;
}

function requestedWeekdaySymbolForPattern(symbol, requestedWidths) {
  if (requestedWidths.has("c")) return "c";
  if (requestedWidths.has("e")) return "e";
  if (requestedWidths.has("E")) return "E";
  return symbol;
}

function requestedDayPeriodSymbolForPattern(symbol, requestedWidths) {
  if (requestedWidths.has("a")) return "a";
  if (requestedWidths.has("b")) return "b";
  if (requestedWidths.has("B")) return "B";
  return symbol;
}

function requestedTimeZoneSymbolForPattern(symbol, requestedWidths) {
  for (const timeZoneSymbol of ["z", "Z", "O", "v", "V", "X", "x"]) {
    if (requestedWidths.has(timeZoneSymbol)) return timeZoneSymbol;
  }
  return symbol;
}

function widthForPatternSymbol(symbol, widths) {
  if (widths.has(symbol)) return widths.get(symbol);
  if (isYearField(symbol)) {
    for (const yearSymbol of ["y", "u", "r"]) {
      if (widths.has(yearSymbol)) return widths.get(yearSymbol);
    }
  }
  if (isWeekdayField(symbol)) {
    for (const weekdaySymbol of ["E", "e", "c"]) {
      if (widths.has(weekdaySymbol)) return widths.get(weekdaySymbol);
    }
  }
  if (isMonthField(symbol)) {
    for (const monthSymbol of ["M", "L"]) {
      if (widths.has(monthSymbol)) return widths.get(monthSymbol);
    }
  }
  if (isDayPeriodField(symbol)) {
    for (const dayPeriodSymbol of ["B", "b", "a"]) {
      if (widths.has(dayPeriodSymbol)) return widths.get(dayPeriodSymbol);
    }
  }
  if (isQuarterField(symbol)) {
    for (const quarterSymbol of ["Q", "q"]) {
      if (widths.has(quarterSymbol)) return widths.get(quarterSymbol);
    }
  }
  if (isTimeZoneField(symbol)) {
    for (const timeZoneSymbol of ["z", "Z", "O", "v", "V", "X", "x"]) {
      if (widths.has(timeZoneSymbol)) return widths.get(timeZoneSymbol);
    }
  }
  return undefined;
}

function adjustPatternWidths(pattern, requestedSkeleton, candidateSkeleton) {
  const requestedWidths = skeletonWidths(requestedSkeleton);
  const candidateWidths = skeletonWidths(candidateSkeleton);
  let output = "";
  let inQuote = false;
  for (let index = 0; index < pattern.length;) {
    const char = pattern[index];
    if (char === "'") {
      output += char;
      if (pattern[index + 1] === "'") {
        output += "'";
        index += 2;
      } else {
        inQuote = !inQuote;
        index += 1;
      }
    } else if (!inQuote && isAsciiLetter(char)) {
      let end = index + 1;
      while (end < pattern.length && pattern[end] === char) end += 1;
      const requestedSymbol = requestedSymbolForPattern(char, requestedWidths, candidateWidths);
      const requestedWidth = widthForPatternSymbol(char, requestedWidths);
      const candidateWidth = widthForPatternSymbol(char, candidateWidths);
      const patternWidth = end - index;
      output += requestedSymbol.repeat(shouldAdjustPatternWidth(requestedSymbol, requestedWidth, candidateWidth, patternWidth) ? requestedWidth : patternWidth);
      index = end;
    } else {
      output += char;
      index += 1;
    }
  }
  return output;
}

function shouldAdjustPatternWidth(symbol, requestedWidth, candidateWidth, patternWidth) {
  if (requestedWidth == null || candidateWidth == null) return false;
  if ((symbol === "e" || symbol === "c") && patternWidth >= 3 && requestedWidth <= 2) return true;
  if (isWeekdayField(symbol) && patternWidth >= 3 && requestedWidth >= 4) return true;
  return patternWidth === candidateWidth;
}

function splitDateTimeSkeleton(canonical) {
  let dateSkeleton = "";
  let timeSkeleton = "";
  for (const symbol of canonical) {
    if (SKELETON_TIME_FIELDS.has(symbol)) {
      timeSkeleton += symbol;
    } else {
      dateSkeleton += symbol;
    }
  }
  return { dateSkeleton, timeSkeleton };
}

function formatPattern(pattern, date, localeData, timeZone) {
  let output = "";
  for (let index = 0; index < pattern.length;) {
    const char = pattern[index];
    if (char === "'") {
      const quoted = readQuotedPattern(pattern, index);
      output += quoted.value;
      index = quoted.nextIndex;
    } else if (isAsciiLetter(char)) {
      let end = index + 1;
      while (end < pattern.length && pattern[end] === char) end += 1;
      output += formatField(char, end - index, date, localeData, timeZone);
      index = end;
    } else {
      output += char;
      index += 1;
    }
  }
  return output;
}

function readQuotedPattern(pattern, start) {
  if (pattern[start + 1] === "'") return { value: "'", nextIndex: start + 2 };
  let value = "";
  let index = start + 1;
  while (index < pattern.length) {
    if (pattern[index] === "'") {
      if (pattern[index + 1] === "'") {
        value += "'";
        index += 2;
      } else {
        return { value, nextIndex: index + 1 };
      }
    } else {
      value += pattern[index];
      index += 1;
    }
  }
  return { value, nextIndex: index };
}

function formatField(symbol, count, date, localeData, timeZone) {
  if (symbol === "G") return eraName(date, localeData, count);
  if (symbol === "y") return yearValue(date, localeData, count);
  if (symbol === "u") return extendedYearValue(date, localeData, count);
  if (symbol === "r") return extendedYearValue(date, localeData, count);
  if (symbol === "Y") return weekYearValue(date, localeData, count);
  if (symbol === "Q" || symbol === "q") return quarterValue(date, localeData, count, symbol === "q");
  if (symbol === "M" || symbol === "L") return monthValue(date, localeData, count, symbol === "L");
  if (symbol === "d") return integerValue(date.getUTCDate(), localeData, count);
  if (symbol === "D") return integerValue(dayOfYear(date), localeData, count);
  if (symbol === "F") return integerValue(dayOfWeekInMonth(date), localeData, count);
  if (symbol === "g") return integerValue(modifiedJulianDay(date), localeData, count);
  if (symbol === "w") return integerValue(weekOfYear(date, localeData), localeData, count);
  if (symbol === "W") return integerValue(weekOfMonth(date, localeData), localeData, count);
  if (symbol === "E") return weekdayName(date, localeData, count);
  if (symbol === "e") return localWeekdayValue(date, localeData, count, false);
  if (symbol === "c") return localWeekdayValue(date, localeData, count, true);
  if (symbol === "a" || symbol === "b" || symbol === "B") return dayPeriodName(date, localeData, count, symbol);
  if (symbol === "H") return integerValue(date.getUTCHours(), localeData, count);
  if (symbol === "k") return integerValue(date.getUTCHours() === 0 ? 24 : date.getUTCHours(), localeData, count);
  if (symbol === "h") return integerValue(hour12(date), localeData, count);
  if (symbol === "K") return integerValue(date.getUTCHours() % 12, localeData, count);
  if (symbol === "m") return integerValue(date.getUTCMinutes(), localeData, count);
  if (symbol === "s") return integerValue(date.getUTCSeconds(), localeData, count);
  if (symbol === "S") return fractionValue(date, localeData, count);
  if (symbol === "A") return integerValue(millisecondsInDay(date), localeData, count);
  if (symbol === "z" || symbol === "Z" || symbol === "O" || symbol === "v" || symbol === "V" || symbol === "X" || symbol === "x") {
    return timeZoneValue(symbol, count, localeData, timeZone);
  }
  throw new DateTimeCoreError("bad-option", `Unsupported CLDR date/time pattern field: ${symbol}.`);
}

function timeZoneValue(symbol, count, localeData, timeZone) {
  const names = localeData.timeZoneNames ?? {};
  if (timeZone.offsetMinutes !== 0) {
    if (symbol === "X") return isoOffset(timeZone.offsetMinutes, count, true);
    if (symbol === "x") return isoOffset(timeZone.offsetMinutes, count, false);
    if (symbol === "V" && count === 1) return "unk";
    if (symbol === "V" && count === 2) return fixedOffsetGmtId(timeZone.offsetMinutes, localeData);
    if (symbol === "V" && count === 3) return "Unknown Location";
    if (symbol === "Z" && count <= 3) return basicOffset(timeZone.offsetMinutes);
    if (symbol === "Z" && count === 5) return isoOffset(timeZone.offsetMinutes, 3, true);
    return localizedGmtOffset(names, timeZone.offsetMinutes, count, localeData);
  }
  if (symbol === "z") return count >= 4 ? names.utcLong ?? names.utcShort ?? "UTC" : names.utcShort ?? "UTC";
  if (symbol === "O" || symbol === "v") return localizedGmtZero(names);
  if (symbol === "V") return localizedGmtZero(names);
  if (symbol === "Z") {
    if (count <= 3) return "+0000";
    if (count === 5) return "Z";
    return localizedGmtZero(names);
  }
  if (symbol === "X") return "Z";
  if (symbol === "x") return count === 1 ? "+00" : count === 2 || count === 4 ? "+0000" : "+00:00";
  return "UTC";
}

function localizedGmtZero(names) {
  return names.gmtZeroFormat ?? (names.gmtFormat ?? "GMT{0}").replace("{0}", "");
}

function localizedGmtOffset(names, offsetMinutes, count, localeData) {
  const formatted = count >= 4 ? extendedOffset(offsetMinutes, true) : shortOffset(offsetMinutes);
  return (names.gmtFormat ?? "GMT{0}").replace("{0}", localizeDigits(formatted, localeData.numberingSystemDigits));
}

function fixedOffsetGmtId(offsetMinutes, localeData) {
  return `GMT${localizeDigits(extendedOffset(offsetMinutes, true), localeData.numberingSystemDigits)}`;
}

function isoOffset(offsetMinutes, count, useZeroZ) {
  if (offsetMinutes === 0 && useZeroZ) return "Z";
  if (count === 1) return shortIsoOffset(offsetMinutes);
  if (count === 2 || count === 4) return basicOffset(offsetMinutes);
  return extendedOffset(offsetMinutes, true);
}

function shortIsoOffset(offsetMinutes) {
  const { sign, hours, minutes } = offsetParts(offsetMinutes);
  return minutes === 0 ? `${sign}${pad2(hours)}` : `${sign}${pad2(hours)}${pad2(minutes)}`;
}

function shortOffset(offsetMinutes) {
  const { sign, hours, minutes } = offsetParts(offsetMinutes);
  return minutes === 0 ? `${sign}${hours}` : `${sign}${hours}:${pad2(minutes)}`;
}

function basicOffset(offsetMinutes) {
  const { sign, hours, minutes } = offsetParts(offsetMinutes);
  return `${sign}${pad2(hours)}${pad2(minutes)}`;
}

function extendedOffset(offsetMinutes, paddedHour) {
  const { sign, hours, minutes } = offsetParts(offsetMinutes);
  const hour = paddedHour ? pad2(hours) : String(hours);
  return `${sign}${hour}:${pad2(minutes)}`;
}

function offsetParts(offsetMinutes) {
  const sign = offsetMinutes < 0 ? "-" : "+";
  const absolute = Math.abs(offsetMinutes);
  return { sign, hours: Math.floor(absolute / 60), minutes: absolute % 60 };
}

function pad2(value) {
  return String(value).padStart(2, "0");
}

function eraName(date, localeData, count) {
  const era = date.getUTCFullYear() <= 0 ? "0" : "1";
  return nameByWidth(localeData.eras, widthForText(count), era);
}

function yearValue(date, localeData, count) {
  const year = date.getUTCFullYear();
  const yearOfEra = year <= 0 ? 1 - year : year;
  if (count === 2) return integerText(yearOfEra % 100, localeData, 2);
  return localizeDigits(String(yearOfEra), localeData.numberingSystemDigits);
}

function extendedYearValue(date, localeData, count) {
  return integerValue(date.getUTCFullYear(), localeData, count);
}

function weekYearValue(date, localeData, count) {
  const value = weekYearInfo(date, localeData).year;
  if (count === 2) return integerText(value % 100, localeData, 2);
  return localizeDigits(String(value), localeData.numberingSystemDigits);
}

function monthValue(date, localeData, count, standAlone) {
  const month = date.getUTCMonth() + 1;
  if (count <= 2) return integerValue(month, localeData, count);
  const context = standAlone ? "stand-alone" : "format";
  return contextualName(localeData.months, context, widthForText(count), String(month));
}

function quarterValue(date, localeData, count, standAlone) {
  const quarter = Math.floor(date.getUTCMonth() / 3) + 1;
  if (count <= 2) return integerValue(quarter, localeData, count);
  const context = standAlone ? "stand-alone" : "format";
  return contextualName(localeData.quarters, context, widthForText(count), String(quarter));
}

function weekdayName(date, localeData, count) {
  return contextualName(localeData.weekdays, "format", widthForWeekday(count), WEEKDAY_KEYS[date.getUTCDay()]);
}

function localWeekdayValue(date, localeData, count, standAlone) {
  const day = date.getUTCDay();
  if (count <= 2) {
    return integerValue(modulo(day - (localeData.firstDayOfWeek ?? 1), 7) + 1, localeData, count);
  }
  return contextualName(localeData.weekdays, standAlone ? "stand-alone" : "format", widthForWeekday(count), WEEKDAY_KEYS[day]);
}

function dayOfYear(date) {
  return daysBeforeMonth(date.getUTCFullYear(), date.getUTCMonth() + 1) + date.getUTCDate();
}

function dayOfWeekInMonth(date) {
  return Math.floor((date.getUTCDate() - 1) / 7) + 1;
}

function millisecondsInDay(date) {
  return ((date.getUTCHours() * 60 + date.getUTCMinutes()) * 60 + date.getUTCSeconds()) * 1000 + date.getUTCMilliseconds();
}

function modifiedJulianDay(date) {
  return ordinalDay(date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate()) - ordinalDay(1858, 11, 17);
}

function weekOfYear(date, localeData) {
  return weekYearInfo(date, localeData).week;
}

function weekYearInfo(date, localeData) {
  const year = date.getUTCFullYear();
  const ordinal = ordinalDay(year, date.getUTCMonth() + 1, date.getUTCDate());
  const weekStart = startOfWeek(ordinal, localeData.firstDayOfWeek ?? 1);
  const currentStart = firstWeekStartOfYear(year, localeData);
  const nextStart = firstWeekStartOfYear(year + 1, localeData);
  if (weekStart >= nextStart) return { year: year + 1, week: 1 };
  if (weekStart < currentStart) {
    const previousYear = year - 1;
    const previousStart = firstWeekStartOfYear(previousYear, localeData);
    return { year: previousYear, week: Math.floor((weekStart - previousStart) / 7) + 1 };
  }
  return { year, week: Math.floor((weekStart - currentStart) / 7) + 1 };
}

function weekOfMonth(date, localeData) {
  const year = date.getUTCFullYear();
  const month = date.getUTCMonth() + 1;
  const ordinal = ordinalDay(year, month, date.getUTCDate());
  const weekStart = startOfWeek(ordinal, localeData.firstDayOfWeek ?? 1);
  const firstStart = firstWeekStartOfMonth(year, month, localeData);
  return Math.floor((weekStart - firstStart) / 7) + 1;
}

function firstWeekStartOfYear(year, localeData) {
  return firstWeekStart(ordinalDay(year, 1, 1), localeData);
}

function firstWeekStartOfMonth(year, month, localeData) {
  return firstWeekStart(ordinalDay(year, month, 1), localeData);
}

function firstWeekStart(periodStart, localeData) {
  const weekStart = startOfWeek(periodStart, localeData.firstDayOfWeek ?? 1);
  const daysInPeriod = weekStart + 7 - periodStart;
  return daysInPeriod >= (localeData.minDaysInFirstWeek ?? 1) ? weekStart : weekStart + 7;
}

function startOfWeek(ordinal, firstDay) {
  return ordinal - modulo(dayOfWeek(ordinal) - firstDay, 7);
}

function dayOfWeek(ordinal) {
  return modulo(ordinal, 7);
}

function ordinalDay(year, month, day) {
  return daysBeforeYear(year) + daysBeforeMonth(year, month) + day;
}

function daysBeforeYear(year) {
  const previous = year - 1;
  return 365 * previous + Math.floor(previous / 4) - Math.floor(previous / 100) + Math.floor(previous / 400);
}

function daysBeforeMonth(year, month) {
  const monthLengths = isLeapYear(year)
    ? [0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335]
    : [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334];
  return monthLengths[month - 1] ?? 0;
}

function isLeapYear(year) {
  return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}

function modulo(value, divisor) {
  return ((value % divisor) + divisor) % divisor;
}

function dayPeriodName(date, localeData, count, symbol) {
  const period = dayPeriodKey(date, localeData, symbol);
  return contextualName(localeData.dayPeriods, "format", widthForDayPeriod(count), period);
}

function dayPeriodKey(date, localeData, symbol) {
  const fallback = date.getUTCHours() < 12 ? "am" : "pm";
  if (symbol === "a") return fallback;
  if (symbol === "b") return selectDayPeriodRule(date, localeData.dayPeriodRules, true) ?? fallback;
  return selectDayPeriodRule(date, localeData.dayPeriodRules, false) ?? fallback;
}

function selectDayPeriodRule(date, encodedRules, exactOnly) {
  if (!encodedRules) return null;
  const minute = date.getUTCHours() * 60 + date.getUTCMinutes();
  const exactMinute = date.getUTCSeconds() === 0 && date.getUTCMilliseconds() === 0 ? minute : -1;
  let rangeMatch = null;
  for (const rawRule of encodedRules.split(";")) {
    const [period, span] = rawRule.split("=");
    if (period == null || span == null) continue;
    const rangeIndex = span.indexOf("-");
    if (rangeIndex < 0) {
      if (Number(span) === exactMinute) return period;
      continue;
    }
    if (!exactOnly) {
      const start = Number(span.slice(0, rangeIndex));
      const end = Number(span.slice(rangeIndex + 1));
      if (minuteInDayPeriodRange(minute, start, end)) rangeMatch = rangeMatch ?? period;
    }
  }
  return exactOnly ? null : rangeMatch;
}

function minuteInDayPeriodRange(minute, start, end) {
  if (start <= end) return minute >= start && minute < end;
  return minute >= start || minute < end;
}

function hour12(date) {
  const hour = date.getUTCHours() % 12;
  return hour === 0 ? 12 : hour;
}

function fractionValue(date, localeData, count) {
  const milliseconds = String(date.getUTCMilliseconds()).padStart(3, "0");
  const value = (milliseconds + "000000000").slice(0, count);
  return localizeDigits(value, localeData.numberingSystemDigits);
}

function integerValue(value, localeData, count) {
  return integerText(value, localeData, count >= 2 ? count : 0);
}

function integerText(value, localeData, minimumDigits) {
  const text = String(Math.trunc(Math.abs(value))).padStart(minimumDigits, "0");
  const signed = value < 0 ? `-${text}` : text;
  return localizeDigits(signed, localeData.numberingSystemDigits);
}

function contextualName(source, context, width, key) {
  return nameByWidth(source[context] ?? source.format ?? source["stand-alone"] ?? {}, width, key);
}

function nameByWidth(source, width, key) {
  return (
    source[width]?.[key] ??
    source.abbreviated?.[key] ??
    source.wide?.[key] ??
    source.short?.[key] ??
    source.narrow?.[key] ??
    key
  );
}

function widthForText(count) {
  if (count === 4) return "wide";
  if (count === 5) return "narrow";
  return "abbreviated";
}

function widthForWeekday(count) {
  if (count === 4) return "wide";
  if (count === 5) return "narrow";
  if (count >= 6) return "short";
  return "abbreviated";
}

function widthForDayPeriod(count) {
  if (count === 4) return "wide";
  if (count >= 5) return "narrow";
  return "abbreviated";
}

function isAsciiLetter(value) {
  return /^[A-Za-z]$/.test(value);
}

function localizeDigits(value, digits) {
  if (digits == null || digits === "0123456789") return value;
  return value.replace(/[0-9]/g, (digit) => digits.charAt(digit.charCodeAt(0) - DIGIT_ZERO));
}
