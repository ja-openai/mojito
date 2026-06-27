export function functionOptionLiteral(functionRef, name, fallback) {
  const option = functionRef.options?.[name];
  return option?.type === "literal" ? option.value : fallback;
}

export function sourceOptionValue(source, name, fallback) {
  if (source == null) return fallback;
  if (typeof source.optionValue === "function") return source.optionValue(name, fallback);
  return functionOptionLiteral(source.function, name, fallback);
}

export function isNumericFunction(functionRef) {
  return ["number", "integer", "percent", "offset"].includes(functionRef?.name);
}

export function numericSelectUsesVariable(functionRef) {
  return functionRef?.options?.select?.type === "variable";
}

export function inheritedExactNumericSource(source) {
  if (source == null) return false;
  if (isNumericFunction(source.function) && sourceOptionValue(source, "select", null) === "exact") return true;
  return inheritedExactNumericSource(source.inherited);
}

export function isDecimalSourceFunction(functionRef) {
  const name = functionRef?.name;
  return isNumericFunction(functionRef) || name === "currency" || name === "relativeTime";
}

const DECIMAL_PATTERN = /^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/;
const INTEGER_PATTERN = /^[+-]?\d+$/;
const MAX_DECIMAL_OPERAND_LENGTH = 256;
const MAX_DECIMAL_EXPONENT = 1000000;
const MAX_OFFSET_INTEGER_TEXT = "1000000000000000000000";
const MAX_OFFSET_INTEGER = 1000000000000000000000n;

export function parseDecimalNumber(value) {
  const text = String(value);
  if (text.length > MAX_DECIMAL_OPERAND_LENGTH) return null;
  if (!DECIMAL_PATTERN.test(text)) return null;
  const parsed = Number(text);
  return Number.isFinite(parsed) ? parsed : null;
}

export function parseDecimalOperand(value) {
  const text = String(value);
  if (text.length > MAX_DECIMAL_OPERAND_LENGTH) return null;
  const match = DECIMAL_PATTERN.exec(text);
  if (!match) return null;
  const exponent = parseBoundedExponent(match[3]?.slice(1) ?? "");
  if (exponent == null) return null;
  const negative = text.startsWith("-");
  const body = negative ? text.slice(1) : text;
  const significand = body.split(/[eE]/, 1)[0];
  const [integer, fraction = ""] = significand.split(".");
  return normalizeDecimalOperand(negative, integer + fraction, fraction.length - exponent);
}

export function parseIntegerOperand(value) {
  const text = String(value);
  if (text.length > MAX_DECIMAL_OPERAND_LENGTH) return null;
  if (!INTEGER_PATTERN.test(text)) return null;
  const negative = text.startsWith("-");
  const digits = negative || text.startsWith("+") ? text.slice(1) : text;
  return normalizeDecimalOperand(negative, digits, 0);
}

export function decimalOperandsEqual(left, right) {
  return left.digits === right.digits && left.scale === right.scale && left.negative === right.negative;
}

export function shiftDecimalOperand(operand, places) {
  return normalizeDecimalOperand(operand.negative, operand.digits, operand.scale - places);
}

export function truncateDecimalOperandToInteger(operand) {
  if (operand.scale <= 0) return operand;
  const keep = operand.digits.length - operand.scale;
  if (keep <= 0) return normalizeDecimalOperand(false, "0", 0);
  return normalizeDecimalOperand(operand.negative, operand.digits.slice(0, keep), 0);
}

function parseBoundedExponent(value) {
  if (value === "") return 0;
  const negative = value.startsWith("-");
  const unsigned = negative || value.startsWith("+") ? value.slice(1) : value;
  const digits = unsigned.replace(/^0+/, "") || "0";
  if (digits.length > 7) return null;
  const parsed = Number(digits);
  if (!Number.isSafeInteger(parsed) || parsed > MAX_DECIMAL_EXPONENT) return null;
  return negative ? -parsed : parsed;
}

function normalizeDecimalOperand(negative, digits, scale) {
  digits = digits.replace(/^0+/, "");
  if (digits === "") return { negative: false, digits: "0", scale: 0 };
  while (digits.endsWith("0")) {
    digits = digits.slice(0, -1);
    scale -= 1;
  }
  return { negative, digits, scale };
}

export function parseInteger(value) {
  const text = String(value);
  if (text.length > MAX_DECIMAL_OPERAND_LENGTH) return null;
  if (!INTEGER_PATTERN.test(text)) return null;
  const parsed = Number(text);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

export function parseOffsetInteger(value) {
  if (typeof value === "number") {
    return Number.isSafeInteger(value) ? BigInt(value) : null;
  }
  const normalized = normalizeBoundedIntegerText(value);
  return normalized == null ? null : BigInt(normalized);
}

export function offsetIntegerInRange(value) {
  return value > -MAX_OFFSET_INTEGER && value < MAX_OFFSET_INTEGER;
}

function normalizeBoundedIntegerText(value) {
  const text = String(value);
  if (!INTEGER_PATTERN.test(text)) return null;
  const negative = text.startsWith("-");
  const unsigned = negative || text.startsWith("+") ? text.slice(1) : text;
  const digits = unsigned.replace(/^0+/, "") || "0";
  if (digits === "0") return "0";
  if (digits.length > MAX_OFFSET_INTEGER_TEXT.length) return null;
  if (digits.length === MAX_OFFSET_INTEGER_TEXT.length && digits >= MAX_OFFSET_INTEGER_TEXT) return null;
  return negative ? `-${digits}` : digits;
}
