import { selectCardinal } from "./cldr_plural_rules.js";
import { isDecimalSourceFunction } from "./function_support.js";
import { localeLookupChain } from "./locale-key.js";

const DEFAULT_LOCALE = "en";
const MAX_LOCALE_LENGTH = 256;
const MAX_OPTION_LENGTH = 256;
const MAX_OPERAND_LENGTH = 256;
const MAX_RELATIVE_TIME_QUANTITY = 1_000_000_000;
const DECIMAL_NUMBER_RE = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/;
const STYLE_VALUES = ["long", "short", "narrow"];
const NUMERIC_VALUES = ["always", "auto"];
const POLICY_VALUES = ["precise", "compact", "chat"];
const UNIT_SECONDS = Object.freeze({
  second: 1,
  minute: 60,
  hour: 3_600,
  day: 86_400,
  week: 604_800,
  month: 2_592_000,
  quarter: 7_776_000,
  year: 31_536_000,
});
const UNIT_VALUES = ["auto", ...Object.keys(UNIT_SECONDS)];
const POLICIES = Object.freeze({
  precise: Object.freeze([
    [60, "second"],
    [3_600, "minute"],
    [86_400, "hour"],
    [604_800, "day"],
    [2_592_000, "week"],
    [31_536_000, "month"],
    [Number.POSITIVE_INFINITY, "year"],
  ]),
  compact: Object.freeze([
    [60, "second"],
    [3_600, "minute"],
    [86_400, "hour"],
    [Number.POSITIVE_INFINITY, "day"],
  ]),
  chat: Object.freeze([
    [45, "second"],
    [2_700, "minute"],
    [79_200, "hour"],
    [604_800, "day"],
    [Number.POSITIVE_INFINITY, "week"],
  ]),
});
const DATA_CACHE = new WeakMap();

export class RelativeTimeCoreError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "RelativeTimeCoreError";
    this.code = code;
  }
}

export function formatRelativeTimeCore(value, options = {}) {
  const data = preparedData(options.data);
  const locale = localeOption(options.locale);
  const style = optionOneOf(options.style ?? "short", STYLE_VALUES, "style");
  const numeric = optionOneOf(options.numeric ?? "always", NUMERIC_VALUES, "numeric");
  const policy = optionOneOf(options.policy ?? "precise", POLICY_VALUES, "policy");
  const unit = optionOneOf(options.unit ?? "auto", UNIT_VALUES, "unit");
  const seconds = parseFiniteNumber(value);
  const selectedUnit = unit === "auto" ? selectUnit(seconds, policy) : unit;
  const quantity = relativeTimeQuantity(seconds, selectedUnit);

  if (useRelativeZero(policy, numeric, seconds)) {
    const relative = relativeTerm(data, locale, style, selectedUnit, "0");
    if (relative != null) return relative;
  }
  if (numeric === "auto") {
    const offset = relativeOffset(seconds, selectedUnit, quantity);
    if (offset != null) {
      const relative = relativeTerm(data, locale, style, selectedUnit, offset);
      if (relative != null) return relative;
    }
  }

  const direction = isNegativeRelativeTime(seconds) ? "past" : "future";
  const category = selectCardinal(locale, quantity);
  const pattern = relativeTimePattern(data, locale, style, selectedUnit, direction, category);
  return pattern.replace("{0}", String(quantity));
}

export function formatRelativeTimeCoreToParts(value, options = {}) {
  return [{ type: "text", value: formatRelativeTimeCore(value, options) }];
}

export function createRelativeTimeCoreFunctionRegistry(FunctionRegistry, data) {
  preparedData(data);
  return FunctionRegistry.portable().withFunction("relativeTime", (call) => {
    const options = {
      locale: call.locale,
      style: call.optionValue("style", "short"),
      numeric: call.optionValue("numeric", "always"),
      policy: call.optionValue("policy", "precise"),
      unit: call.optionValue("unit", "auto"),
      data,
    };
    try {
      return formatRelativeTimeCore(call.rawValue ?? call.value, options);
    } catch (error) {
      const sourceValue = relativeTimeSourceValue(call.inheritedSource);
      if (!(error instanceof RelativeTimeCoreError) || error.code !== "bad-operand" || sourceValue == null) {
        throw error;
      }
      return formatRelativeTimeCore(sourceValue, options);
    }
  });
}

function relativeTimeSourceValue(source) {
  for (let current = source; current != null; current = current.inherited) {
    if (isDecimalSourceFunction(current.function)) return current.value;
  }
  return null;
}

function preparedData(data) {
  if (data == null || typeof data !== "object") {
    throw new RelativeTimeCoreError("missing-locale-data", "Relative-time core requires generated CLDR data.");
  }
  const cached = DATA_CACHE.get(data);
  if (cached != null) return cached;
  const localeMap = preparedLocaleMap(data.localeMap);
  if (localeMap == null || !Array.isArray(data.patternSets)) {
    throw new RelativeTimeCoreError("missing-locale-data", "Relative-time core data has an unsupported shape.");
  }
  const patternSets = new Map();
  for (const item of data.patternSets) {
    if (
      item != null &&
      typeof item.id === "string" &&
      item.id !== "" &&
      item.data != null &&
      typeof item.data === "object" &&
      !Array.isArray(item.data) &&
      Object.keys(item.data).length > 0
    ) {
      patternSets.set(item.id, item.data);
    }
  }
  if (patternSets.size === 0) {
    throw new RelativeTimeCoreError("missing-locale-data", "Relative-time core data has an unsupported shape.");
  }
  const prepared = { localeMap, patternSets };
  DATA_CACHE.set(data, prepared);
  return prepared;
}

function preparedLocaleMap(localeMap) {
  if (localeMap == null || typeof localeMap !== "object" || Array.isArray(localeMap)) {
    return null;
  }
  const entries = Object.entries(localeMap);
  if (entries.length === 0 || entries.some(([, setId]) => typeof setId !== "string")) {
    return null;
  }
  return Object.fromEntries(entries);
}

function parseFiniteNumber(value) {
  if (typeof value === "boolean") {
    throw new RelativeTimeCoreError("bad-operand", "Relative-time core requires a finite numeric value.");
  }
  const text = coerceStringOperand(value ?? "").trim();
  if (text === "") {
    throw new RelativeTimeCoreError("bad-operand", "Relative-time core requires a finite numeric value.");
  }
  if (text.length > MAX_OPERAND_LENGTH) {
    throw new RelativeTimeCoreError("bad-operand", "Relative-time core requires a finite numeric value.");
  }
  if (typeof value !== "number" && !DECIMAL_NUMBER_RE.test(text)) {
    throw new RelativeTimeCoreError("bad-operand", "Relative-time core requires a finite numeric value.");
  }
  const parsed = typeof value === "number" ? value : Number(text);
  if (!Number.isFinite(parsed)) {
    throw new RelativeTimeCoreError("bad-operand", "Relative-time core requires a finite numeric value.");
  }
  return parsed;
}

function localeOption(value) {
  if (value == null) return DEFAULT_LOCALE;
  let locale;
  try {
    locale = String(value);
  } catch {
    throw new RelativeTimeCoreError("bad-option", "locale must be coercible to a string.");
  }
  if (locale.length > MAX_LOCALE_LENGTH) {
    throw new RelativeTimeCoreError("bad-option", "locale must not exceed 256 characters.");
  }
  return locale === "" ? DEFAULT_LOCALE : locale;
}

function coerceStringOperand(value) {
  try {
    return String(value);
  } catch {
    throw new RelativeTimeCoreError("bad-operand", "Relative-time core requires a finite numeric value.");
  }
}

function optionOneOf(value, allowed, name) {
  let text;
  try {
    text = String(value);
  } catch {
    throw new RelativeTimeCoreError("bad-option", `${name} must be coercible to a string.`);
  }
  if (text.length > MAX_OPTION_LENGTH) {
    throw new RelativeTimeCoreError("bad-option", `${name} must not exceed 256 characters.`);
  }
  if (!allowed.includes(text)) {
    throw new RelativeTimeCoreError("bad-option", `${name} must be one of ${allowed.join(", ")}.`);
  }
  return text;
}

function selectUnit(seconds, policy) {
  const absolute = Math.abs(seconds);
  for (const [upper, unit] of POLICIES[policy]) {
    if (absolute < upper) return unit;
  }
  return "year";
}

function relativeTimeQuantity(seconds, unit) {
  const absolute = Math.abs(seconds);
  if (absolute === 0) return 0;
  const quantity = Math.max(1, Math.floor(absolute / UNIT_SECONDS[unit] + 0.5));
  if (quantity > MAX_RELATIVE_TIME_QUANTITY) {
    throw new RelativeTimeCoreError("bad-operand", "Relative-time core quantity is outside the supported range.");
  }
  return quantity;
}

function useRelativeZero(policy, numeric, seconds) {
  return policy === "chat" && numeric === "auto" && Math.abs(seconds) < 45;
}

function relativeOffset(seconds, unit, quantity) {
  if (quantity === 0) return "0";
  if (Math.abs(seconds) !== quantity * UNIT_SECONDS[unit]) return null;
  return isNegativeRelativeTime(seconds) ? `-${quantity}` : String(quantity);
}

function isNegativeRelativeTime(seconds) {
  return seconds < 0 || Object.is(seconds, -0);
}

function relativeTerm(data, locale, style, unit, offset) {
  return relativeUnitData(data, locale, style, unit).relative?.[offset] ?? null;
}

function relativeTimePattern(data, locale, style, unit, direction, category) {
  const patterns = relativeUnitData(data, locale, style, unit)[direction];
  const pattern = patterns?.[category] ?? patterns?.other;
  if (typeof pattern === "string") return pattern;
  throw new RelativeTimeCoreError(
    "missing-locale-data",
    `Missing relative-time pattern for ${locale}/${style}/${unit}/${direction}.`,
  );
}

function relativeUnitData(data, locale, style, unit) {
  const patternSet = patternSetFor(data, locale);
  const unitData = patternSet?.[style]?.[unit];
  if (unitData != null) return unitData;
  throw new RelativeTimeCoreError(
    "missing-locale-data",
    `Missing relative-time unit data for ${locale}/${style}/${unit}.`,
  );
}

function patternSetFor(data, locale) {
  for (const candidate of localeLookupChain(locale)) {
    const setId = data.localeMap[candidate];
    if (setId == null) continue;
    const patternSet = data.patternSets.get(setId);
    if (patternSet != null) return patternSet;
    throw new RelativeTimeCoreError("missing-locale-data", `Missing relative-time pattern set ${setId}.`);
  }
  throw new RelativeTimeCoreError("missing-locale-data", `Missing relative-time locale data for ${locale}.`);
}
