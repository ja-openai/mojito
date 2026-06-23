import { MF2Error } from "./errors.js";
import { isDecimalSourceFunction, parseDecimalNumber, parseInteger } from "./function_support.js";
import { registerNumericSelectors } from "./numeric_selectors.js";
import { formatOffset } from "./offset_function.js";

const MAX_FRACTION_DIGITS = 100;

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
  const date = rawValue instanceof Date ? rawValue : new Date(rendered);
  return Number.isNaN(date.getTime()) ? null : date;
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
