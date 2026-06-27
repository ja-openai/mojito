import { MF2Error } from "./errors.js";
import { isDecimalSourceFunction, parseDecimalNumber, parseInteger } from "./function_support.js";
import { registerNumericSelectors } from "./numeric_selectors.js";
import { formatOffset } from "./offset_function.js";

const MAX_FRACTION_DIGITS = 100;
const MAX_DATE_OPERAND_LENGTH = 256;
const MIN_TIMESTAMP_MS = -62_135_596_800_000;
const MAX_TIMESTAMP_MS = 253_402_300_799_999;
const ISO_DATE_TIME_RE = /^([0-9]{4})-([0-9]{2})-([0-9]{2})(?:T([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\.([0-9]{1,9}))?)?(Z|[+-][0-9]{2}:[0-9]{2})?)?$/;

export function createIntlFunctionRegistry(FunctionRegistry) {
  const formatters = new Map();
  const selectors = new Map();
  formatters.set("string", (call) => call.value);
  formatters.set("number", formatIntlNumber);
  formatters.set("percent", formatIntlPercent);
  formatters.set("integer", formatIntlInteger);
  formatters.set("currency", formatIntlCurrency);
  formatters.set("date", formatIntlDate);
  formatters.set("time", formatIntlTime);
  formatters.set("datetime", formatIntlDateTime);
  formatters.set("relativeTime", formatIntlRelativeTime);
  formatters.set("offset", formatOffset);
  registerNumericSelectors(selectors);
  return new FunctionRegistry(formatters, selectors);
}

function formatIntlNumber(call) {
  const value = parseCallNumber(call, "Number function requires a numeric operand.");
  return numberFormatter(call.locale, call, {}).format(value);
}

function formatIntlPercent(call) {
  const value = parseCallNumber(call, "Percent function requires a numeric operand.");
  return numberFormatter(call.locale, call, { style: "percent" }).format(value);
}

function formatIntlInteger(call) {
  const value = parseCallNumber(call, "Integer function requires a numeric operand.");
  return numberFormatter(call.locale, call, { maximumFractionDigits: 0, minimumFractionDigits: 0 }).format(Math.trunc(value));
}

function formatIntlCurrency(call) {
  const value = parseCallNumber(call, "Currency function requires a numeric operand.");
  const currency = inheritedOptionValue(call, "currency", null);
  if (currency == null || !/^[A-Za-z]{3}$/.test(currency)) throw MF2Error.badOption("Currency function requires a three-letter currency option.");
  return numberFormatter(call.locale, call, { style: "currency", currency: currency.toUpperCase() }).format(value);
}

function formatIntlDate(call) {
  return dateFormatter(call.locale, call, { dateStyle: dateTimeStyle(call, "dateStyle", "length", "medium") }).format(parseCallDate(call, "Date function requires a date operand."));
}

function formatIntlTime(call) {
  return dateFormatter(call.locale, call, { timeStyle: dateTimeStyle(call, "timeStyle", "precision", "medium") }).format(parseCallDate(call, "Time function requires a date operand."));
}

function formatIntlDateTime(call) {
  return dateFormatter(call.locale, call, {
    dateStyle: dateTimeStyle(call, "dateStyle", "dateLength", "medium"),
    timeStyle: dateTimeStyle(call, "timeStyle", "timePrecision", "medium"),
  }).format(parseCallDate(call, "Datetime function requires a date operand."));
}

function formatIntlRelativeTime(call) {
  const value = parseCallNumber(call, "Relative time function requires a numeric operand.");
  const unit = call.optionValue("unit", null);
  if (!["second", "minute", "hour", "day", "week", "month", "quarter", "year"].includes(unit)) {
    throw MF2Error.badOption("Relative time function requires unit second, minute, hour, day, week, month, quarter, or year.");
  }
  const numeric = optionOneOf(call, "numeric", ["always", "auto"], "always");
  const style = optionOneOf(call, "style", ["long", "short", "narrow"], "long");
  try {
    return new Intl.RelativeTimeFormat(call.locale, { numeric, style }).format(value, unit);
  } catch (error) {
    throw MF2Error.badOption(error.message);
  }
}

function numberFormatter(locale, call, baseOptions) {
  const options = {
    ...baseOptions,
    signDisplay: optionOneOf(call, "signDisplay", ["auto", "always", "exceptZero", "negative", "never"], undefined),
  };
  const minimumFractionDigits = nonNegativeIntegerOption(call, "minimumFractionDigits");
  const maximumFractionDigits = nonNegativeIntegerOption(call, "maximumFractionDigits");
  if (minimumFractionDigits != null) options.minimumFractionDigits = minimumFractionDigits;
  if (maximumFractionDigits != null) options.maximumFractionDigits = maximumFractionDigits;
  try {
    return new Intl.NumberFormat(locale, options);
  } catch (error) {
    throw MF2Error.badOption(error.message);
  }
}

function dateFormatter(locale, call, options) {
  const timeZone = call.optionValue("timeZone", null);
  if (timeZone != null) options.timeZone = timeZone;
  try {
    return new Intl.DateTimeFormat(locale, options);
  } catch (error) {
    throw MF2Error.badOption(error.message);
  }
}

function parseCallNumber(call, message) {
  let parsed = parseDecimalNumber(call.value);
  if (parsed == null) parsed = parseSourceNumber(call.inheritedSource);
  if (parsed == null) throw MF2Error.badOperand(message);
  return parsed;
}

function parseSourceNumber(source) {
  if (source == null) return null;
  if (isDecimalSourceFunction(source.function)) return parseDecimalNumber(source.value);
  return parseSourceNumber(source.inherited);
}

function inheritedOptionValue(call, name, fallback) {
  const own = call.optionValue(name, undefined);
  if (own !== undefined) return own;
  for (let source = call.inheritedSource; source != null; source = source.inherited) {
    const value = source.optionValue(name, undefined);
    if (value !== undefined) return value;
  }
  return fallback;
}

function parseCallDate(call, message) {
  const date = parseDateValue(call.rawValue, call.value) ?? parseSourceDate(call.inheritedSource);
  if (date == null) throw MF2Error.badOperand(message);
  return date;
}

function parseSourceDate(source) {
  for (let current = source; current != null; current = current.inherited) {
    if (!isDateTimeSourceFunction(current.function)) continue;
    const date = parseDateValue(null, current.value);
    if (date != null) return date;
  }
  return null;
}

function isDateTimeSourceFunction(functionRef) {
  return ["date", "time", "datetime"].includes(functionRef?.name);
}

function parseDateValue(rawValue, rendered) {
  if (rawValue instanceof Date) return isPortableDate(rawValue) ? rawValue : null;
  if (typeof rawValue === "number") {
    if (!Number.isFinite(rawValue) || rawValue < MIN_TIMESTAMP_MS || rawValue > MAX_TIMESTAMP_MS) return null;
    return new Date(rawValue);
  }
  if (typeof rendered !== "string") return null;
  return parseDateString(rendered);
}

function parseDateString(value) {
  const text = value.trim();
  if (text.length > MAX_DATE_OPERAND_LENGTH) return null;
  const match = ISO_DATE_TIME_RE.exec(text);
  if (match == null) return null;
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
    return null;
  }
  const offsetMinutes = zone === "" || zone === "Z" ? 0 : parseOffsetMinutes(zone);
  if (offsetMinutes == null) return null;
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  date.setUTCHours(hour, minute - offsetMinutes, second, millisecond);
  return isPortableDate(date) ? date : null;
}

function isPortableDate(date) {
  const timestamp = date.getTime();
  return Number.isFinite(timestamp) && timestamp >= MIN_TIMESTAMP_MS && timestamp <= MAX_TIMESTAMP_MS;
}

function parseOffsetMinutes(value) {
  const match = /^([+-])([0-9]{2}):([0-9]{2})$/.exec(value);
  if (match == null) return null;
  const hours = Number(match[2]);
  const minutes = Number(match[3]);
  if (hours > 18 || minutes > 59 || (hours === 18 && minutes !== 0)) return null;
  const total = hours * 60 + minutes;
  return match[1] === "-" ? -total : total;
}

function daysInMonth(year, month) {
  if (month === 2) return isLeapYear(year) ? 29 : 28;
  return [4, 6, 9, 11].includes(month) ? 30 : 31;
}

function isLeapYear(year) {
  return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}

function dateTimeStyle(call, optionName, legacyOptionName, fallback) {
  const sharedStyle = call.optionValue("style", fallback);
  const legacyValue = call.optionValue(legacyOptionName, sharedStyle);
  return optionOneOf(call, optionName, ["full", "long", "medium", "short"], legacyValue);
}

function optionOneOf(call, optionName, allowed, fallback) {
  const value = call.optionValue(optionName, fallback ?? null);
  if (value == null) return undefined;
  if (!allowed.includes(value)) throw MF2Error.badOption(`${optionName} option must be one of ${allowed.join(", ")}.`);
  return value;
}

function nonNegativeIntegerOption(call, optionName) {
  const value = call.optionValue(optionName, null);
  if (value == null) return null;
  const parsed = parseInteger(value);
  if (parsed == null || parsed < 0 || parsed > MAX_FRACTION_DIGITS) {
    throw MF2Error.badOption(`${optionName} option must be a non-negative integer.`);
  }
  return parsed;
}
