import { MF2Error } from "./errors.js";
import {
  isDecimalSourceFunction,
  numericSourceOperand,
  parseDecimalNumber,
  parseInteger,
  sourceOptionValue,
} from "./function_support.js";
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
  const directCurrency = call.optionValue("currency", MISSING_OPTION);
  const inheritedCurrency = currencyOptionFrom(call.inheritedSource);
  if (directCurrency !== MISSING_OPTION && inheritedCurrency !== MISSING_OPTION) {
    throw MF2Error.badOption("Currency option cannot override the currency of a currency operand.");
  }
  const currency = inheritedCurrency !== MISSING_OPTION
    ? inheritedCurrency
    : directCurrency === MISSING_OPTION ? null : directCurrency;
  if (currency == null) throw MF2Error.badOperand("Currency function requires a currency operand or option.");
  if (!/^[A-Za-z]{3}$/.test(currency)) throw MF2Error.badOption("Currency function requires a three-letter currency option.");
  return numberFormatter(call.locale, call, { style: "currency", currency: currency.toUpperCase() }).format(value);
}

function formatIntlDate(call) {
  return formatIntlTemporal(
    call,
    { dateStyle: dateTimeStyle(call, "dateStyle", "length", "medium") },
    "Date function requires a date operand.",
  );
}

function formatIntlTime(call) {
  return formatIntlTemporal(
    call,
    timeOptions(call, "timeStyle", "precision", "medium"),
    "Time function requires a date operand.",
  );
}

function formatIntlDateTime(call) {
  const dateStyle = dateTimeStyle(call, "dateStyle", "dateLength", "medium");
  const time = timeOptions(call, "timeStyle", "timePrecision", "medium");
  const options = time.timeStyle == null
    ? { ...dateStyleComponents(dateStyle), ...time }
    : { dateStyle, ...time };
  return formatIntlTemporal(call, options, "Datetime function requires a date operand.");
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
    signDisplay: optionOneOf(
      call,
      "signDisplay",
      ["auto", "always", "exceptZero", "negative", "never"],
      undefined,
      numericOptionValue,
    ),
  };
  const minimumFractionDigits = nonNegativeIntegerOption(call, "minimumFractionDigits", numericOptionValue);
  const maximumFractionDigits = nonNegativeIntegerOption(call, "maximumFractionDigits", numericOptionValue);
  if (minimumFractionDigits != null) options.minimumFractionDigits = minimumFractionDigits;
  if (maximumFractionDigits != null) options.maximumFractionDigits = maximumFractionDigits;
  try {
    return new Intl.NumberFormat(locale, options);
  } catch (error) {
    throw MF2Error.badOption(error.message);
  }
}

function dateFormatter(locale, call, options) {
  const timeZone = inheritedOptionValue(call, "timeZone", null, ["date", "time", "datetime"]);
  if (timeZone != null) options.timeZone = timeZone;
  try {
    return new Intl.DateTimeFormat(locale, options);
  } catch (error) {
    throw MF2Error.badOption(error.message);
  }
}

function formatIntlTemporal(call, options, message) {
  const formatter = dateFormatter(call.locale, call, { ...options });
  const operand = parseCallDate(call, message);
  if (!operand.floating) return formatter.format(operand.value);
  // Offset-free MF2 literals are wall-clock values, not host-zone or UTC instants.
  const timeZone = formatter.resolvedOptions().timeZone;
  return formatter.format(floatingDateToInstant(operand.value, timeZone));
}

function parseCallDate(call, message) {
  const sourceDate = parseSourceDate(call.inheritedSource);
  if (sourceDate != null) return sourceDate;
  return parseDate(call.rawValue, call.value, message);
}

function parseCallNumber(call, message) {
  let parsed = parseSourceNumber(call.inheritedSource);
  if (parsed == null) parsed = parseDecimalNumber(call.value);
  if (parsed == null) throw MF2Error.badOperand(message);
  return parsed;
}

function parseSourceNumber(source) {
  if (!isDecimalSourceFunction(source?.function)) return null;
  return parseDecimalNumber(numericSourceOperand(source));
}

function parseDate(rawValue, rendered, message) {
  const operand = rawValue instanceof Date
    ? { value: rawValue, floating: false }
    : parseDateValue(rendered);
  if (operand == null || Number.isNaN(operand.value.getTime())) throw MF2Error.badOperand(message);
  return operand;
}

function parseSourceDate(source) {
  if (source == null) return null;
  if (["date", "time", "datetime"].includes(source.function?.name)) {
    const parsed = parseDateValue(source.value);
    if (parsed != null && !Number.isNaN(parsed.value.getTime())) return parsed;
  }
  return parseSourceDate(source.inherited);
}

const FLOATING_DATE_TIME_LITERAL = /^(?!0000)[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])(?:T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\.[0-9]{1,3})?)?$/;

function parseDateValue(value) {
  const text = String(value);
  const floating = FLOATING_DATE_TIME_LITERAL.test(text);
  if (!floating) return { value: new Date(text), floating: false };
  const instantText = text.includes("T") ? `${text}Z` : `${text}T00:00:00Z`;
  return { value: new Date(instantText), floating: true };
}

const MILLISECONDS_PER_DAY = 86_400_000;

function floatingDateToInstant(floating, timeZone) {
  if (timeZone === "UTC") return floating;
  const wallTime = floating.getTime();
  const partsFormatter = new Intl.DateTimeFormat("en-US-u-ca-iso8601-nu-latn", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    fractionalSecondDigits: 3,
    hourCycle: "h23",
  });
  const offsets = new Set();
  for (const days of [-7, -2, -1, 0, 1, 2, 7]) {
    const instant = wallTime + days * MILLISECONDS_PER_DAY;
    offsets.add(wallTimeAtInstant(instant, partsFormatter) - instant);
  }
  const candidates = [...offsets].map((offset) => {
    const instant = wallTime - offset;
    return { instant, wallDelta: wallTimeAtInstant(instant, partsFormatter) - wallTime };
  });
  const exact = candidates
    .filter((candidate) => candidate.wallDelta === 0)
    .sort((left, right) => left.instant - right.instant);
  if (exact.length > 0) return new Date(exact[0].instant);

  // A wall time in a daylight-saving gap has no exact instant. Match Temporal's
  // compatible disambiguation by selecting the nearest representable time after it.
  const after = candidates
    .filter((candidate) => candidate.wallDelta > 0)
    .sort((left, right) => left.wallDelta - right.wallDelta || left.instant - right.instant);
  if (after.length > 0) return new Date(after[0].instant);
  candidates.sort((left, right) => right.wallDelta - left.wallDelta || right.instant - left.instant);
  return new Date(candidates[0].instant);
}

function wallTimeAtInstant(instant, formatter) {
  const fields = Object.create(null);
  for (const part of formatter.formatToParts(new Date(instant))) {
    if (part.type !== "literal") fields[part.type] = part.value;
  }
  const value = new Date(0);
  value.setUTCFullYear(Number(fields.year), Number(fields.month) - 1, Number(fields.day));
  value.setUTCHours(
    Number(fields.hour),
    Number(fields.minute),
    Number(fields.second),
    Number(fields.fractionalSecond ?? 0),
  );
  return value.getTime();
}

function dateTimeStyle(call, optionName, legacyOptionName, fallback) {
  const sharedStyle = call.optionValue("style", fallback);
  const legacyValue = call.optionValue(legacyOptionName, sharedStyle);
  return optionOneOf(call, optionName, ["full", "long", "medium", "short"], legacyValue);
}

function timeOptions(call, optionName, legacyOptionName, fallback) {
  const explicitStyle = call.optionValue(optionName, null);
  if (explicitStyle != null) {
    return { timeStyle: requireOneOf(optionName, explicitStyle, ["full", "long", "medium", "short"]) };
  }
  const legacyValue = call.optionValue(legacyOptionName, null);
  if (["hour", "minute", "second"].includes(legacyValue)) return timePrecisionComponents(legacyValue);
  const sharedStyle = call.optionValue("style", fallback);
  return {
    timeStyle: requireOneOf(
      legacyOptionName,
      legacyValue ?? sharedStyle,
      ["full", "long", "medium", "short"],
    ),
  };
}

function timePrecisionComponents(precision) {
  const options = { hour: "numeric" };
  if (precision !== "hour") options.minute = "2-digit";
  if (precision === "second") options.second = "2-digit";
  return options;
}

function dateStyleComponents(style) {
  if (style === "full") return { weekday: "long", year: "numeric", month: "long", day: "numeric" };
  if (style === "long") return { year: "numeric", month: "long", day: "numeric" };
  if (style === "short") return { year: "2-digit", month: "numeric", day: "numeric" };
  return { year: "numeric", month: "short", day: "numeric" };
}

function optionOneOf(call, optionName, allowed, fallback, resolver = directOptionValue) {
  const value = resolver(call, optionName, fallback ?? null);
  if (value == null) return undefined;
  return requireOneOf(optionName, value, allowed);
}

function requireOneOf(optionName, value, allowed) {
  if (!allowed.includes(value)) throw MF2Error.badOption(`${optionName} option must be one of ${allowed.join(", ")}.`);
  return value;
}

function nonNegativeIntegerOption(call, optionName, resolver = directOptionValue) {
  const value = resolver(call, optionName, null);
  if (value == null) return null;
  const parsed = parseInteger(value);
  if (parsed == null || parsed < 0) throw MF2Error.badOption(`${optionName} option must be a non-negative integer.`);
  return parsed;
}

function directOptionValue(call, optionName, fallback) {
  return call.optionValue(optionName, fallback);
}

function numericOptionValue(call, optionName, fallback) {
  if (call.function.name === "currency") {
    return inheritedOptionValue(call, optionName, fallback, ["currency"]);
  }
  if (call.function.name === "integer" && ["minimumFractionDigits", "maximumFractionDigits"].includes(optionName)) {
    return call.optionValue(optionName, fallback);
  }
  const direct = call.optionValue(optionName, MISSING_OPTION);
  if (direct !== MISSING_OPTION) return direct;
  return inheritedNumericOptionValue(call.inheritedSource, optionName, fallback, call.function.name);
}

const MISSING_OPTION = Symbol("missing-option");

function inheritedOptionValue(call, optionName, fallback, sourceFunctions) {
  const direct = call.optionValue(optionName, MISSING_OPTION);
  if (direct !== MISSING_OPTION) return direct;
  const inherited = sourceOptionFrom(call.inheritedSource, optionName, sourceFunctions);
  return inherited === MISSING_OPTION ? fallback : inherited;
}

function sourceOptionFrom(source, optionName, sourceFunctions) {
  if (source == null) return MISSING_OPTION;
  if (sourceFunctions.includes(source.function?.name)) {
    const value = sourceOptionValue(source, optionName, MISSING_OPTION);
    if (value !== MISSING_OPTION) return value;
  }
  return sourceOptionFrom(source.inherited, optionName, sourceFunctions);
}

function inheritedNumericOptionValue(source, optionName, fallback, targetFunction) {
  if (source == null || numericOptionIsDiscarded(targetFunction, optionName)) return fallback;
  const sourceFunction = source.function?.name;
  if (!isNumericFunctionName(sourceFunction) || numericOptionIsDiscarded(sourceFunction, optionName)) {
    return fallback;
  }
  const value = sourceOptionValue(source, optionName, MISSING_OPTION);
  if (value !== MISSING_OPTION) return value;
  return inheritedNumericOptionValue(source.inherited, optionName, fallback, sourceFunction);
}

function currencyOptionFrom(source) {
  if (source == null || source.function?.name !== "currency") return MISSING_OPTION;
  const value = sourceOptionValue(source, "currency", MISSING_OPTION);
  if (value !== MISSING_OPTION) return value;
  return currencyOptionFrom(source.inherited);
}

function isNumericFunctionName(functionName) {
  return ["number", "integer", "percent", "offset"].includes(functionName);
}

function numericOptionIsDiscarded(functionName, optionName) {
  if (functionName === "integer") {
    return ["minimumFractionDigits", "maximumFractionDigits", "minimumSignificantDigits"].includes(optionName);
  }
  if (functionName === "percent") {
    return ["minimumIntegerDigits", "roundingIncrement", "select"].includes(optionName);
  }
  if (functionName === "offset") return ["add", "subtract"].includes(optionName);
  return false;
}
