import { MF2Error } from "./errors.js";

// Numeric formatters here are intentionally unlocalized. Locale-pretty number,
// currency, date, and time formatting belongs in explicit platform adapters.
export function createPortableFunctionRegistry(FunctionRegistry) {
  const formatters = new Map();
  const selectors = new Map();
  formatters.set("string", (call) => call.value);
  formatters.set("number", formatUnlocalizedNumber);
  selectors.set("number", selectNumber);
  formatters.set("percent", formatUnlocalizedPercent);
  selectors.set("percent", selectPercent);
  formatters.set("integer", formatUnlocalizedInteger);
  selectors.set("integer", selectInteger);
  formatters.set("offset", formatOffset);
  selectors.set("offset", selectOffset);
  return new FunctionRegistry(formatters, selectors);
}

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

function isDecimalSourceFunction(functionRef) {
  return isNumericFunction(functionRef) || functionRef?.name === "currency";
}

function invalidNumericSelector(functionRef, source) {
  const select = functionOptionLiteral(functionRef, "select", null);
  return numericSelectUsesVariable(functionRef) || (select !== "exact" && inheritedExactNumericSource(source));
}

function formatUnlocalizedNumber(call) {
  const value = parseCallDecimal(call, "Number function requires a numeric operand.");
  return formatUnlocalizedDecimal(value, signDisplayAlways(call.function), minimumFractionDigits(call));
}

function selectNumber(match) {
  if (invalidNumericSelector(match.function, match.inheritedSource)) throw MF2Error.badSelector("Number selector cannot match this operand.");
  const value = parseMatchDecimal(match, "Number selector requires a numeric operand.");
  const key = parseDecimalNumber(match.key);
  return key != null && Object.is(value, key) ? 1 : null;
}

function formatUnlocalizedPercent(call) {
  const value = parseCallDecimal(call, "Percent function requires a numeric operand.");
  let formatted = formatUnlocalizedDecimalWithMaximumFractionDigits(value * 100, maximumFractionDigits(call));
  if (signDisplayAlways(call.function) && value >= 0) formatted = `+${formatted}`;
  return `${appendMinimumFractionDigits(formatted, minimumFractionDigits(call))}%`;
}

function selectPercent(match) {
  if (invalidNumericSelector(match.function, match.inheritedSource)) throw MF2Error.badSelector("Percent selector cannot match this operand.");
  const value = parseMatchDecimal(match, "Percent selector requires a numeric operand.") * 100;
  const key = parseDecimalNumber(match.key);
  return key != null && Object.is(value, key) ? 1 : null;
}

function formatUnlocalizedInteger(call) {
  const value = parseCallDecimal(call, "Integer function requires a numeric operand.");
  const integer = Math.trunc(value);
  return signDisplayAlways(call.function) && integer >= 0 ? `+${integer}` : String(integer);
}

function selectInteger(match) {
  if (invalidNumericSelector(match.function, match.inheritedSource)) throw MF2Error.badSelector("Integer selector cannot match this operand.");
  const value = parseMatchDecimal(match, "Integer selector requires a numeric operand.");
  const key = parseInteger(match.key);
  return key != null && Math.trunc(value) === key ? 1 : null;
}

function formatOffset(call) {
  const value = parseRequiredInteger(call.value, "Offset function requires a numeric operand.");
  const result = value + offsetDelta(call);
  return inheritedSignDisplayAlways(call.inheritedSource) && result >= 0 ? `+${result}` : String(result);
}

function selectOffset(match) {
  const value = parseRequiredInteger(match.value, "Offset selector requires a numeric operand.");
  const key = parseInteger(match.key);
  return key != null && value === key ? 1 : null;
}

function parseCallDecimal(call, message) {
  let parsed = parseDecimalNumber(call.value);
  if (parsed == null) parsed = parseSourceDecimal(call.inheritedSource);
  if (parsed == null) throw MF2Error.badOperand(message);
  return parsed;
}

function parseMatchDecimal(match, message) {
  let parsed = parseDecimalNumber(match.value);
  if (parsed == null) parsed = parseSourceDecimal(match.inheritedSource);
  if (parsed == null) throw MF2Error.badSelector(message);
  return parsed;
}

function parseSourceDecimal(source) {
  if (source == null) return null;
  if (isDecimalSourceFunction(source.function)) return parseDecimalNumber(source.value);
  return parseSourceDecimal(source.inherited);
}

export function parseDecimalNumber(value) {
  const text = String(value);
  if (!/^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/.test(text)) return null;
  const parsed = Number(text);
  return Number.isFinite(parsed) ? parsed : null;
}

function formatUnlocalizedDecimal(value, signAlways, minimumFractionDigits) {
  let formatted = String(value);
  if (formatted.endsWith(".0")) formatted = formatted.slice(0, -2);
  if (signAlways && value >= 0) formatted = `+${formatted}`;
  return appendMinimumFractionDigits(formatted, minimumFractionDigits);
}

function formatUnlocalizedDecimalWithMaximumFractionDigits(value, digits) {
  if (digits == null) return formatUnlocalizedDecimal(value, false, 0);
  let formatted = value.toFixed(digits);
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
  const value = call.optionValue("minimumFractionDigits", null);
  return value == null ? 0 : parseNonNegativeOption(value, "minimumFractionDigits option must be a non-negative integer.");
}

function maximumFractionDigits(call) {
  const value = call.optionValue("maximumFractionDigits", null);
  return value == null ? null : parseNonNegativeOption(value, "maximumFractionDigits option must be a non-negative integer.");
}

function parseNonNegativeOption(value, message) {
  if (!/^\d+$/.test(String(value))) throw MF2Error.badOption(message);
  return Number(value);
}

function signDisplayAlways(functionRef) {
  return functionOptionLiteral(functionRef, "signDisplay", null) === "always";
}

function inheritedSignDisplayAlways(source) {
  if (source == null) return false;
  if ((source.function?.name === "number" || source.function?.name === "integer") && sourceOptionValue(source, "signDisplay", null) === "always") return true;
  return inheritedSignDisplayAlways(source.inherited);
}

function offsetDelta(call) {
  const add = call.optionValue("add", null);
  const subtract = call.optionValue("subtract", null);
  if ((add == null && subtract == null) || (add != null && subtract != null)) throw MF2Error.badOption("Offset function requires exactly one of add or subtract.");
  const value = parseInteger(add ?? subtract);
  if (value == null) throw MF2Error.badOption(add != null ? "Offset add option must be an integer." : "Offset subtract option must be an integer.");
  return add != null ? value : -value;
}

function parseRequiredInteger(value, message) {
  const parsed = parseInteger(value);
  if (parsed == null) throw MF2Error.badOperand(message);
  return parsed;
}

function parseInteger(value) {
  if (!/^[+-]?\d+$/.test(String(value))) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}
