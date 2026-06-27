import { NUMBER_DATA } from "./cldr_number_data.js";
import {
  parseDecimalOperand,
  shiftDecimalOperand,
  truncateDecimalOperandToInteger,
} from "./function_support.js";
import { localeLookupChain } from "./locale-key.js";

const DEFAULT_LOCALE = "en-US";
const MAX_LOCALE_LENGTH = 256;
const MAX_OPTION_LENGTH = 256;
const MAX_OPERAND_LENGTH = 256;
const MAX_FRACTION_DIGITS = 100;
const MAX_SAFE_BIGINT = BigInt(Number.MAX_SAFE_INTEGER);
const DIGIT_ZERO = "0".charCodeAt(0);

export class NumberCoreError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "NumberCoreError";
    this.code = code;
  }
}

export function formatNumberCore(value, options = {}) {
  const style = optionOneOf(options.style ?? "number", ["number", "integer", "percent", "currency"], "style");
  const localeData = resolveLocaleData(options.locale ?? DEFAULT_LOCALE);
  const parsed = parseFiniteDecimalOperand(value);
  if (parsed == null) throw new NumberCoreError("bad-operand", "Number core requires a finite numeric value.");

  const currency = style === "currency" ? parseCurrency(options.currency) : null;
  const pattern = patternForStyle(localeData, style);
  const fraction = fractionOptions(localeData, style, currency, options, pattern);
  const normalized = style === "integer" ? truncateDecimalPreservingZeroSign(parsed) : parsed;
  const scaled = style === "percent" ? shiftDecimalPreservingZeroSign(normalized, 2) : normalized;
  if (!isSupportedMagnitude(scaled)) {
    throw new NumberCoreError("bad-operand", "Number core numeric value is outside the supported magnitude.");
  }
  const signDisplay = optionOneOf(options.signDisplay ?? "auto", ["auto", "always", "never"], "signDisplay");
  const useGrouping = booleanOption(options.useGrouping ?? true, "useGrouping");
  const formatted = formatDecimal(absDecimalOperand(scaled), localeData, pattern, fraction, useGrouping);

  if (style === "percent") {
    return applySignedPattern(pattern, formatted, scaled, localeData.symbols, signDisplay, {
      percentSign: localeData.symbols.percentSign,
    });
  }
  if (style === "currency") {
    return applySignedPattern(pattern, formatted, scaled, localeData.symbols, signDisplay, {
      currency: currencyDisplay(localeData, currency, options.currencyDisplay ?? "symbol"),
    });
  }
  return applySign(formatted, scaled, localeData.symbols, signDisplay);
}

export function formatNumberCoreToParts(value, options = {}) {
  return [{ type: "text", value: formatNumberCore(value, options) }];
}

export function createNumberCoreFunctionRegistry(FunctionRegistry) {
  return FunctionRegistry.portable()
    .withFunction("number", (call) => formatCallNumber(call, "number"))
    .withFunction("integer", (call) => formatCallNumber(call, "integer"))
    .withFunction("percent", (call) => formatCallNumber(call, "percent"))
    .withFunction("currency", (call) => formatCallNumber(call, "currency"));
}

function formatCallNumber(call, style) {
  const currency = style === "currency" ? inheritedOptionValue(call, "currency", null) : call.optionValue("currency", null);
  if (style === "currency" && currency == null) {
    throw new NumberCoreError("bad-operand", "Currency function requires a currency option.");
  }
  return formatNumberCore(callNumberValue(call, style), {
    locale: call.locale,
    style,
    currency,
    currencyDisplay: call.optionValue("currencyDisplay", "symbol"),
    maximumFractionDigits: call.optionValue("maximumFractionDigits", undefined),
    minimumFractionDigits: call.optionValue("minimumFractionDigits", undefined),
    signDisplay: call.optionValue("signDisplay", "auto"),
    useGrouping: call.optionValue("useGrouping", "true"),
  });
}

function resolveLocaleData(locale) {
  const key = coerceStringOption(locale ?? DEFAULT_LOCALE, "locale");
  if (key.length > MAX_LOCALE_LENGTH) {
    throw new NumberCoreError("bad-option", "locale must not exceed 256 characters.");
  }
  for (const candidate of localeLookupChain(key)) {
    const exact = NUMBER_DATA.locales[candidate];
    if (exact != null) return exact;
    for (const localeData of Object.values(NUMBER_DATA.locales)) {
      if (localeData.numbersSourceLocale === candidate) return localeData;
    }
  }
  return NUMBER_DATA.locales[DEFAULT_LOCALE];
}

function patternForStyle(localeData, style) {
  if (style === "percent") return localeData.percentPattern;
  if (style === "currency") return localeData.currencyPattern;
  return localeData.decimalPattern;
}

function fractionOptions(localeData, style, currency, options, pattern) {
  let defaults = fractionDefaultsFromPattern(pattern);
  if (style === "integer") defaults = { minimum: 0, maximum: 0 };
  if (style === "currency") {
    const currencyDefaults = NUMBER_DATA.currencyFractions[currency] ?? NUMBER_DATA.currencyFractions.DEFAULT;
    defaults = { minimum: currencyDefaults.digits, maximum: currencyDefaults.digits };
  }
  let minimum = nonNegativeIntegerOption(options.minimumFractionDigits, defaults.minimum, "minimumFractionDigits");
  let maximum = nonNegativeIntegerOption(options.maximumFractionDigits, defaults.maximum, "maximumFractionDigits");
  if (options.minimumFractionDigits != null && options.maximumFractionDigits == null && maximum < minimum) {
    maximum = minimum;
  }
  if (options.maximumFractionDigits != null && options.minimumFractionDigits == null && maximum < minimum) {
    minimum = maximum;
  }
  if (maximum < minimum) {
    throw new NumberCoreError("bad-option", "maximumFractionDigits must be greater than or equal to minimumFractionDigits.");
  }
  return { minimum, maximum };
}

function fractionDefaultsFromPattern(pattern) {
  const match = numberPattern(pattern).match(/\.([0#]+)/);
  if (match == null) return { minimum: 0, maximum: 0 };
  const fraction = match[1];
  return {
    minimum: countChars(fraction, "0"),
    maximum: fraction.length,
  };
}

function formatDecimal(value, localeData, pattern, fraction, useGrouping) {
  const rounded = roundDecimalOperand(value, fraction.maximum);
  let [integer, decimal = ""] = rounded.split(".");
  while (decimal.length > fraction.minimum && decimal.endsWith("0")) decimal = decimal.slice(0, -1);
  while (decimal.length < fraction.minimum) decimal += "0";

  const grouping = groupingInfo(pattern);
  if (useGrouping && shouldGroup(integer, grouping, localeData.minimumGroupingDigits)) {
    integer = groupInteger(integer, grouping, localeData.symbols.group);
  }
  integer = localizeDigits(integer, localeData.numberingSystemDigits);
  if (decimal) return `${integer}${localeData.symbols.decimal}${localizeDigits(decimal, localeData.numberingSystemDigits)}`;
  return integer;
}

function roundDecimalOperand(value, maximumFractionDigits) {
  const { integer, fraction } = decimalParts(decimalOperandToString(value));
  const dropped = fraction.length - maximumFractionDigits;
  let units;
  let roundDigit = "0";
  if (dropped <= 0) {
    units = `${integer}${fraction}${"0".repeat(-dropped)}`;
  } else {
    units = `${integer}${fraction.slice(0, maximumFractionDigits)}`;
    roundDigit = fraction.charAt(maximumFractionDigits) || "0";
  }
  units = units.replace(/^0+/, "") || "0";
  if (roundDigit >= "5") units = incrementDigits(units);
  if (maximumFractionDigits === 0) return units;
  units = units.padStart(maximumFractionDigits + 1, "0");
  return `${units.slice(0, -maximumFractionDigits)}.${units.slice(-maximumFractionDigits)}`;
}

function decimalParts(text) {
  let [mantissa, exponentText = "0"] = text.toLowerCase().split("e", 2);
  let exponent = Number(exponentText);
  if (!Number.isFinite(exponent)) exponent = 0;
  if (mantissa.startsWith("+") || mantissa.startsWith("-")) mantissa = mantissa.slice(1);
  let [integer, fraction = ""] = mantissa.split(".", 2);
  if (integer === "") integer = "0";
  const digits = `${integer}${fraction}` || "0";
  const point = integer.length + exponent;
  if (point <= 0) {
    return { integer: "0", fraction: `${"0".repeat(-point)}${digits}`.replace(/0+$/, "") };
  }
  if (point >= digits.length) {
    return { integer: `${digits}${"0".repeat(point - digits.length)}`.replace(/^0+(?=\d)/, ""), fraction: "" };
  }
  return {
    integer: digits.slice(0, point).replace(/^0+(?=\d)/, "") || "0",
    fraction: digits.slice(point).replace(/0+$/, ""),
  };
}

function incrementDigits(value) {
  const digits = value.split("");
  for (let index = digits.length - 1; index >= 0; index -= 1) {
    if (digits[index] !== "9") {
      digits[index] = String.fromCharCode(digits[index].charCodeAt(0) + 1);
      return digits.join("");
    }
    digits[index] = "0";
  }
  return `1${digits.join("")}`;
}

function groupingInfo(pattern) {
  const integerPattern = numberPattern(pattern).split(".", 1)[0];
  const groups = integerPattern.split(",");
  if (groups.length === 1) return { primary: 0, secondary: 0 };
  const primary = placeholderCount(groups[groups.length - 1]);
  const secondary = groups.length > 2 ? placeholderCount(groups[groups.length - 2]) : primary;
  return { primary, secondary };
}

function shouldGroup(integer, grouping, minimumGroupingDigits) {
  if (grouping.primary <= 0) return false;
  return integer.length >= grouping.primary + minimumGroupingDigits;
}

function groupInteger(integer, grouping, separator) {
  const groups = [];
  let end = integer.length;
  let size = grouping.primary;
  while (end > 0) {
    const start = Math.max(0, end - size);
    groups.unshift(integer.slice(start, end));
    end = start;
    size = grouping.secondary || grouping.primary;
  }
  return groups.join(separator);
}

function applySign(formatted, value, symbols, signDisplay) {
  if (signDisplay === "never") return formatted;
  if (isNegative(value)) return `${symbols.minusSign}${formatted}`;
  if (signDisplay === "always") return `${symbols.plusSign}${formatted}`;
  return formatted;
}

function applyPattern(pattern, formatted, replacements) {
  let output = pattern.replace(/[#0,.]+/, formatted);
  if (replacements.percentSign != null) output = output.replaceAll("%", replacements.percentSign);
  if (replacements.currency != null) output = output.replaceAll("¤", replacements.currency);
  return output;
}

function applySignedPattern(pattern, formatted, value, symbols, signDisplay, replacements) {
  const [positivePattern, negativePattern] = pattern.split(";", 2);
  if (isNegative(value) && signDisplay !== "never") {
    if (negativePattern != null) return applyPattern(negativePattern, formatted, replacements);
    return `${symbols.minusSign}${applyPattern(positivePattern, formatted, replacements)}`;
  }
  const output = applyPattern(positivePattern, formatted, replacements);
  if (signDisplay === "always") return `${symbols.plusSign}${output}`;
  return output;
}

function isNegative(value) {
  return value.negative;
}

function currencyDisplay(localeData, currency, display) {
  const value = optionOneOf(display, ["symbol", "narrowSymbol", "code"], "currencyDisplay");
  if (value === "code") return currencyCodeDisplay(localeData, currency);
  return localeData.currencies[currency]?.[value] ?? localeData.currencies[currency]?.symbol ?? currency;
}

function currencyCodeDisplay(localeData, currency) {
  const positivePattern = String(localeData.currencyPattern ?? "").split(";", 1)[0];
  const before = /[#0]\u00a4/.test(positivePattern)
    ? currencySpacingInsert(localeData, "beforeCurrency")
    : "";
  const after = /\u00a4[#0]/.test(positivePattern)
    ? currencySpacingInsert(localeData, "afterCurrency")
    : "";
  return `${before}${currency}${after}`;
}

function currencySpacingInsert(localeData, direction) {
  return localeData.currencySpacing?.[direction]?.insertBetween ?? "\u00a0";
}

function parseFiniteDecimalOperand(value) {
  if (typeof value === "number" && !Number.isFinite(value)) return null;
  if (typeof value === "number" && Object.is(value, -0)) return { negative: true, digits: "0", scale: 0 };
  if (typeof value === "bigint" && (value > MAX_SAFE_BIGINT || value < -MAX_SAFE_BIGINT)) return null;
  const text = (typeof value === "bigint" ? value.toString() : coerceStringOperand(value ?? "")).trim();
  if (text.length > MAX_OPERAND_LENGTH) return null;
  const parsed = parseDecimalOperand(text);
  if (parsed == null) return null;
  return text.startsWith("-") && parsed.digits === "0" && parsed.scale === 0
    ? { ...parsed, negative: true }
    : parsed;
}

function isSupportedMagnitude(value) {
  return decimalIntegerDigitCount(value) <= 21;
}

function callNumberValue(call, style) {
  if (call.inheritedSource != null) {
    if (style === "number" && call.inheritedSource.function?.name === "integer") {
      const parsed = parseFiniteDecimalOperand(call.inheritedSource.value);
      if (parsed != null) return decimalOperandToString(truncateDecimalPreservingZeroSign(parsed));
    }
    return call.inheritedSource.value;
  }
  return call.rawValue ?? call.value;
}

function decimalOperandToString(operand) {
  const sign = operand.negative ? "-" : "";
  if (operand.scale <= 0) return `${sign}${operand.digits}${"0".repeat(-operand.scale)}`;
  if (operand.scale >= operand.digits.length) {
    return `${sign}0.${"0".repeat(operand.scale - operand.digits.length)}${operand.digits}`;
  }
  const integerDigits = operand.digits.length - operand.scale;
  return `${sign}${operand.digits.slice(0, integerDigits)}.${operand.digits.slice(integerDigits)}`;
}

function absDecimalOperand(operand) {
  return operand.negative ? { ...operand, negative: false } : operand;
}

function shiftDecimalPreservingZeroSign(operand, places) {
  const shifted = shiftDecimalOperand(operand, places);
  return operand.negative && shifted.digits === "0" && shifted.scale === 0 ? { ...shifted, negative: true } : shifted;
}

function truncateDecimalPreservingZeroSign(operand) {
  const truncated = truncateDecimalOperandToInteger(operand);
  return operand.negative && truncated.digits === "0" && truncated.scale === 0 ? { ...truncated, negative: true } : truncated;
}

function decimalIntegerDigitCount(operand) {
  if (operand.scale <= 0) return operand.digits.length - operand.scale;
  return Math.max(operand.digits.length - operand.scale, 0);
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

function parseCurrency(value) {
  if (typeof value !== "string") {
    throw new NumberCoreError("bad-option", "currency must be a three-letter ISO 4217 code.");
  }
  if (value.length > MAX_OPTION_LENGTH) {
    throw new NumberCoreError("bad-option", "currency must not exceed 256 characters.");
  }
  if (!/^[A-Za-z]{3}$/.test(value)) {
    throw new NumberCoreError("bad-option", "currency must be a three-letter ISO 4217 code.");
  }
  return value.toUpperCase();
}

function nonNegativeIntegerOption(value, fallback, name) {
  if (value == null) return fallback;
  const text = coerceStringOption(value, name);
  if (!/^\d+$/.test(text)) throw new NumberCoreError("bad-option", `${name} must be a non-negative integer.`);
  const parsed = Number(text);
  if (parsed > MAX_FRACTION_DIGITS) throw new NumberCoreError("bad-option", `${name} must be a non-negative integer.`);
  return parsed;
}

function coerceStringOperand(value) {
  try {
    return String(value);
  } catch {
    throw new NumberCoreError("bad-operand", "Number core requires a finite numeric value.");
  }
}

function coerceStringOption(value, name) {
  let text;
  try {
    text = String(value);
  } catch {
    throw new NumberCoreError("bad-option", `${name} must be coercible to a string.`);
  }
  if (text.length > MAX_OPTION_LENGTH) {
    throw new NumberCoreError("bad-option", `${name} must not exceed 256 characters.`);
  }
  return text;
}

function optionOneOf(value, allowed, name) {
  const text = coerceStringOption(value, name);
  if (!allowed.includes(text)) throw new NumberCoreError("bad-option", `${name} must be one of ${allowed.join(", ")}.`);
  return text;
}

function booleanOption(value, name) {
  if (value === true || value === "true") return true;
  if (value === false || value === "false") return false;
  const text = coerceStringOption(value, name);
  if (text === "true") return true;
  if (text === "false") return false;
  throw new NumberCoreError("bad-option", `${name} must be true or false.`);
}

function numberPattern(pattern) {
  return pattern.match(/[#0,.]+/)?.[0] ?? "";
}

function placeholderCount(pattern) {
  return countChars(pattern, "#") + countChars(pattern, "0");
}

function countChars(value, needle) {
  let count = 0;
  for (const char of value) if (char === needle) count += 1;
  return count;
}

function localizeDigits(value, digits) {
  if (digits == null || digits === "0123456789") return value;
  return value.replace(/[0-9]/g, (digit) => digits.charAt(digit.charCodeAt(0) - DIGIT_ZERO));
}
