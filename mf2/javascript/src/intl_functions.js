import { MF2Error } from "./errors.js";
import { parseDecimalNumber, parseInteger } from "./function_support.js";
import { registerNumericSelectors } from "./numeric_selectors.js";
import { formatOffset } from "./offset_function.js";

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
  const currency = call.optionValue("currency", null);
  if (currency == null || !/^[A-Za-z]{3}$/.test(currency)) throw MF2Error.badOption("Currency function requires a three-letter currency option.");
  return numberFormatter(call.locale, call, { style: "currency", currency: currency.toUpperCase() }).format(value);
}

function formatIntlDate(call) {
  return dateFormatter(call.locale, call, { dateStyle: dateTimeStyle(call, "dateStyle", "length", "medium") }).format(parseDate(call.rawValue, call.value, "Date function requires a date operand."));
}

function formatIntlTime(call) {
  return dateFormatter(call.locale, call, { timeStyle: dateTimeStyle(call, "timeStyle", "precision", "medium") }).format(parseDate(call.rawValue, call.value, "Time function requires a date operand."));
}

function formatIntlDateTime(call) {
  return dateFormatter(call.locale, call, {
    dateStyle: dateTimeStyle(call, "dateStyle", "dateLength", "medium"),
    timeStyle: dateTimeStyle(call, "timeStyle", "timePrecision", "medium"),
  }).format(parseDate(call.rawValue, call.value, "Datetime function requires a date operand."));
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
  const parsed = parseDecimalNumber(call.value);
  if (parsed == null) throw MF2Error.badOperand(message);
  return parsed;
}

function parseDate(rawValue, rendered, message) {
  const date = rawValue instanceof Date ? rawValue : new Date(rendered);
  if (Number.isNaN(date.getTime())) throw MF2Error.badOperand(message);
  return date;
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
  if (parsed == null || parsed < 0) throw MF2Error.badOption(`${optionName} option must be a non-negative integer.`);
  return parsed;
}
