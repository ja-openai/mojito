import { MF2Error } from "./errors.js";
import {
  functionOptionLiteral,
  isDecimalSourceFunction,
  parseDecimalOperand,
  parseDecimalNumber,
  shiftDecimalOperand,
  truncateDecimalOperandToInteger,
} from "./function_support.js";

const MAX_FRACTION_DIGITS = 100;
const MAX_DECIMAL_OUTPUT_CHARS = 1000;

export function registerUnlocalizedNumericFormatters(formatters) {
  formatters.set("number", formatUnlocalizedNumber);
  formatters.set("percent", formatUnlocalizedPercent);
  formatters.set("integer", formatUnlocalizedInteger);
}

export function formatUnlocalizedNumber(call) {
  const message = "Number function requires a numeric operand.";
  const value = parseCallDecimalOperand(call, message);
  const minimum = minimumFractionDigits(call);
  const maximum = maximumFractionDigits(call);
  validateFractionDigits(minimum, maximum);
  const rounded = roundDecimalOperandToMaximumFractionDigits(value, maximum);
  ensureDecimalOutputBounded(rounded, minimum, message);
  return formatUnlocalizedDecimalOperand(rounded, signDisplayAlways(call.function), minimum);
}

export function formatUnlocalizedPercent(call) {
  const message = "Percent function requires a numeric operand.";
  const value = parseCallDecimalOperand(call, message);
  const minimum = minimumFractionDigits(call);
  const maximum = maximumFractionDigits(call);
  validateFractionDigits(minimum, maximum);
  const percentValue = roundDecimalOperandToMaximumFractionDigits(
    shiftDecimalOperand(value, 2),
    maximum,
  );
  ensureDecimalOutputBounded(percentValue, minimum, message);
  let formatted = formatUnlocalizedDecimalOperand(percentValue, false, 0);
  if (signDisplayAlways(call.function) && !value.negative) formatted = `+${formatted}`;
  return `${appendMinimumFractionDigits(formatted, minimum)}%`;
}

export function formatUnlocalizedInteger(call) {
  const message = "Integer function requires a numeric operand.";
  const integer = truncateDecimalOperandToInteger(parseCallDecimalOperand(call, message));
  ensureDecimalOutputBounded(integer, 0, message);
  const formatted = formatUnlocalizedDecimalOperand(integer, false, 0);
  return signDisplayAlways(call.function) && !integer.negative ? `+${formatted}` : formatted;
}

export function parseCallDecimal(call, message) {
  let parsed = parseDecimalNumber(call.value);
  if (parsed == null) parsed = parseSourceDecimal(call.inheritedSource);
  if (parsed == null) throw MF2Error.badOperand(message);
  return parsed;
}

function parseSourceDecimal(source) {
  if (source == null) return null;
  if (isDecimalSourceFunction(source.function)) return parseDecimalNumber(source.value);
  return parseSourceDecimal(source.inherited);
}

function parseCallDecimalOperand(call, message) {
  let parsed = parseDecimalOperand(call.value);
  if (parsed == null) parsed = parseSourceDecimalOperand(call.inheritedSource);
  if (parsed == null) throw MF2Error.badOperand(message);
  return parsed;
}

function parseSourceDecimalOperand(source) {
  if (source == null) return null;
  if (isDecimalSourceFunction(source.function)) return parseDecimalOperand(source.value);
  return parseSourceDecimalOperand(source.inherited);
}

function formatUnlocalizedDecimalOperand(operand, signAlways, minimumFractionDigits) {
  let formatted = decimalOperandToString(operand);
  if (signAlways && !operand.negative) formatted = `+${formatted}`;
  return appendMinimumFractionDigits(formatted, minimumFractionDigits);
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

function roundDecimalOperandToMaximumFractionDigits(operand, maximumFractionDigits) {
  if (maximumFractionDigits == null || operand.scale <= maximumFractionDigits) return operand;
  const drop = operand.scale - maximumFractionDigits;
  const keep = operand.digits.length - drop;
  const kept = keep > 0 ? operand.digits.slice(0, keep) : "0";
  const remainder = keep > 0 ? operand.digits.slice(keep) : operand.digits;
  let rounded = kept.replace(/^0+/, "") || "0";
  const comparison = compareRemainderToHalf(remainder, drop);
  if (comparison >= 0) {
    rounded = incrementDecimalString(rounded);
  }
  return normalizeDecimalOperandLocal(operand.negative, rounded, maximumFractionDigits);
}

function compareRemainderToHalf(remainder, droppedDigits) {
  if (!/[1-9]/.test(remainder)) return -1;
  if (remainder.length < droppedDigits) return -1;
  if (remainder[0] < "5") return -1;
  if (remainder[0] > "5") return 1;
  return /[1-9]/.test(remainder.slice(1)) ? 1 : 0;
}

function incrementDecimalString(value) {
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

function normalizeDecimalOperandLocal(negative, digits, scale) {
  digits = digits.replace(/^0+/, "");
  if (digits === "") return { negative: false, digits: "0", scale: 0 };
  while (digits.endsWith("0")) {
    digits = digits.slice(0, -1);
    scale -= 1;
  }
  return { negative, digits, scale };
}

function ensureDecimalOutputBounded(operand, minimumFractionDigits, message) {
  if (estimatedDecimalOutputChars(operand, minimumFractionDigits) > MAX_DECIMAL_OUTPUT_CHARS) {
    throw MF2Error.badOperand(message);
  }
}

function estimatedDecimalOutputChars(operand, minimumFractionDigits) {
  const sign = operand.negative ? 1 : 0;
  if (operand.scale <= 0) return sign + operand.digits.length - operand.scale;
  const integerDigits = Math.max(operand.digits.length - operand.scale, 1);
  const fractionDigits = Math.max(operand.scale, minimumFractionDigits);
  return sign + integerDigits + (fractionDigits > 0 ? 1 + fractionDigits : 0);
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
  const value = call.optionValue("minimumFractionDigits", null);
  return value == null ? 0 : parseNonNegativeOption(value, "minimumFractionDigits option must be a non-negative integer.");
}

function maximumFractionDigits(call) {
  const value = call.optionValue("maximumFractionDigits", null);
  return value == null ? null : parseNonNegativeOption(value, "maximumFractionDigits option must be a non-negative integer.");
}

function validateFractionDigits(minimum, maximum) {
  if (maximum != null && minimum > maximum) {
    throw MF2Error.badOption("maximumFractionDigits must be greater than or equal to minimumFractionDigits.");
  }
}

function parseNonNegativeOption(value, message) {
  if (!/^\d+$/.test(String(value))) throw MF2Error.badOption(message);
  const parsed = Number(value);
  if (parsed > MAX_FRACTION_DIGITS) throw MF2Error.badOption(message);
  return parsed;
}

function signDisplayAlways(functionRef) {
  return functionOptionLiteral(functionRef, "signDisplay", null) === "always";
}
