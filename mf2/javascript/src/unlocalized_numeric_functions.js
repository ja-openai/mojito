import { MF2Error } from "./errors.js";
import {
  MAX_FRACTION_DIGITS,
  functionOptionLiteral,
  isDecimalSourceFunction,
  numericSourceOperand,
  parseDecimalNumber,
  sourceOptionValue,
  sourceMemo,
} from "./function_support.js";

export function registerUnlocalizedNumericFormatters(formatters) {
  formatters.set("number", formatUnlocalizedNumber);
  formatters.set("percent", formatUnlocalizedPercent);
  formatters.set("integer", formatUnlocalizedInteger);
}

export function formatUnlocalizedNumber(call) {
  const value = parseCallDecimal(call, "Number function requires a numeric operand.");
  let formatted = formatUnlocalizedDecimalWithMaximumFractionDigits(value, maximumFractionDigits(call));
  if (signDisplayAlways(call) && value >= 0) formatted = `+${formatted}`;
  return appendMinimumFractionDigits(formatted, minimumFractionDigits(call));
}

export function formatUnlocalizedPercent(call) {
  const value = parseCallDecimal(call, "Percent function requires a numeric operand.");
  let formatted = formatUnlocalizedDecimalWithMaximumFractionDigits(scalePercent(value), maximumFractionDigits(call));
  if (signDisplayAlways(call) && value >= 0) formatted = `+${formatted}`;
  return `${appendMinimumFractionDigits(formatted, minimumFractionDigits(call))}%`;
}

export function formatUnlocalizedInteger(call) {
  const value = parseCallDecimal(call, "Integer function requires a numeric operand.");
  const integer = Math.trunc(value);
  return signDisplayAlways(call) && integer >= 0 ? `+${integer}` : String(integer);
}

export function parseCallDecimal(call, message) {
  let parsed = parseSourceDecimal(call.inheritedSource);
  if (parsed == null) parsed = parseDecimalNumber(call.value);
  if (parsed == null) throw MF2Error.badOperand(message);
  return parsed;
}

export function numericSelectionOperand(resolvedValue, functionRef) {
  if (functionOptionLiteral(functionRef, "select", "plural") === "exact") return null;
  const source = resolvedValue.source;
  const sourceInput = isDecimalSourceFunction(source?.function) ? numericSourceOperand(source) : null;
  const input = sourceInput ?? resolvedValue.rawValue;
  let value = parseDecimalNumber(input);
  if (value == null) return null;

  if (functionRef.name === "integer") return String(Math.trunc(value));
  if (functionRef.name === "offset") return String(input);

  const optionValue = (name, fallback = null) => sourceOptionFrom(
    source,
    name,
    functionOptionLiteral(functionRef, name, fallback),
    functionRef.name,
  );
  const minimum = optionValue("minimumFractionDigits", "0");
  const maximum = optionValue("maximumFractionDigits", null);
  const minimumDigits = minimum == null ? 0 : parseNonNegativeOption(minimum, "minimumFractionDigits option must be a non-negative integer.");
  const maximumDigits = maximum == null ? null : parseNonNegativeOption(maximum, "maximumFractionDigits option must be a non-negative integer.");
  validateFractionRange(minimumDigits, maximumDigits);

  if (functionRef.name === "percent") value = scalePercent(value);
  if (["number", "percent"].includes(functionRef.name)) {
    return appendMinimumFractionDigits(
      formatUnlocalizedDecimalWithMaximumFractionDigits(value, maximumDigits),
      minimumDigits,
    );
  }
  return null;
}

function parseSourceDecimal(source) {
  if (!isDecimalSourceFunction(source?.function)) return null;
  return parseDecimalNumber(numericSourceOperand(source));
}

function formatUnlocalizedDecimal(value, signAlways, minimumFractionDigits) {
  let formatted = String(value);
  if (formatted.endsWith(".0")) formatted = formatted.slice(0, -2);
  if (signAlways && value >= 0) formatted = `+${formatted}`;
  return appendMinimumFractionDigits(formatted, minimumFractionDigits);
}

export function formatUnlocalizedDecimalWithMaximumFractionDigits(value, digits) {
  if (digits == null) return formatUnlocalizedDecimal(value, false, 0);
  let formatted;
  try {
    formatted = value.toFixed(digits);
  } catch (error) {
    if (error instanceof RangeError) {
      throw MF2Error.badOption(`maximumFractionDigits option is outside the supported range: ${error.message}`);
    }
    throw error;
  }
  while (formatted.includes(".") && formatted.endsWith("0")) formatted = formatted.slice(0, -1);
  if (formatted.endsWith(".")) formatted = formatted.slice(0, -1);
  return formatted;
}

function appendMinimumFractionDigits(formatted, minimumFractionDigits) {
  if (minimumFractionDigits === 0) return formatted;
  const dot = formatted.indexOf(".");
  const fractionDigits = dot < 0 ? 0 : formatted.length - dot - 1;
  let output = formatted;
  if (fractionDigits === 0) output += ".";
  for (let index = fractionDigits; index < minimumFractionDigits; index += 1) output += "0";
  return output;
}

function minimumFractionDigits(call) {
  const value = numericCallOptionValue(call, "minimumFractionDigits", null);
  return value == null ? 0 : parseNonNegativeOption(value, "minimumFractionDigits option must be a non-negative integer.");
}

function maximumFractionDigits(call) {
  const value = numericCallOptionValue(call, "maximumFractionDigits", null);
  const maximum = value == null ? null : parseNonNegativeOption(value, "maximumFractionDigits option must be a non-negative integer.");
  validateFractionRange(minimumFractionDigits(call), maximum);
  return maximum;
}

function validateFractionRange(minimum, maximum) {
  if (maximum != null && minimum > maximum) throw MF2Error.badOption("minimumFractionDigits must not exceed maximumFractionDigits.");
}

function scalePercent(value) {
  // Shift the decimal exponent before converting back to a host number. Binary
  // multiplication introduces visible artifacts even for ordinary 0.29 inputs.
  const [coefficient, exponent = "0"] = String(value).split("e");
  const scaled = Number(`${coefficient}e${Number(exponent) + 2}`);
  if (!Number.isFinite(scaled)) throw MF2Error.badOperand("Percent function requires a bounded numeric operand.");
  return scaled;
}

export function parseNonNegativeOption(value, message) {
  const text = String(value);
  if (text.length === 0 || text.length > 16 || !/^\d+$/.test(text)) {
    throw MF2Error.badOption(message);
  }
  const normalized = text.replace(/^0+/, "") || "0";
  if (normalized.length > String(MAX_FRACTION_DIGITS).length) {
    throw MF2Error.badOption(message);
  }
  const parsed = Number(normalized);
  if (parsed > MAX_FRACTION_DIGITS) throw MF2Error.badOption(message);
  return parsed;
}

function signDisplayAlways(call) {
  return numericCallOptionValue(call, "signDisplay", null) === "always";
}

const MISSING_OPTION = Symbol("missing-option");

function numericCallOptionValue(call, name, fallback) {
  const direct = call.optionValue(name, MISSING_OPTION);
  if (direct !== MISSING_OPTION) return direct;
  return sourceOptionFrom(call.inheritedSource, name, fallback, call.function.name);
}

function sourceOptionFrom(source, name, fallback, targetFunction) {
  if (numericOptionIsDiscarded(targetFunction, name)) return fallback;
  let current = source;
  let target = targetFunction;
  const visited = [];
  const cacheKey = `numeric_option:${name}`;
  let resolved = MISSING_OPTION;
  while (current != null) {
    if (numericOptionIsDiscarded(target, name)) break;
    const memo = sourceMemo(current);
    if (memo?.has(cacheKey)) { resolved = memo.get(cacheKey); break; }
    visited.push(current);
    const sourceFunction = current.function?.name;
    if (!numericSourceFunctions(target).includes(sourceFunction)
        || numericOptionIsDiscarded(sourceFunction, name)) break;
    const value = sourceOptionValue(current, name, MISSING_OPTION);
    if (value !== MISSING_OPTION) { resolved = value; break; }
    target = sourceFunction;
    current = current.inherited;
  }
  for (const item of visited) sourceMemo(item)?.set(cacheKey, resolved);
  return resolved === MISSING_OPTION ? fallback : resolved;
}

function numericSourceFunctions(functionName) {
  return ["number", "integer", "percent", "offset"].includes(functionName)
    ? ["number", "integer", "percent", "offset"]
    : [];
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
