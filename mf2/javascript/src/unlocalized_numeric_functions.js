import { MF2Error } from "./errors.js";
import {
  functionOptionLiteral,
  isDecimalSourceFunction,
  parseDecimalNumber,
} from "./function_support.js";

export function registerUnlocalizedNumericFormatters(formatters) {
  formatters.set("number", formatUnlocalizedNumber);
  formatters.set("percent", formatUnlocalizedPercent);
  formatters.set("integer", formatUnlocalizedInteger);
}

export function formatUnlocalizedNumber(call) {
  const value = parseCallDecimal(call, "Number function requires a numeric operand.");
  return formatUnlocalizedDecimal(value, signDisplayAlways(call.function), minimumFractionDigits(call));
}

export function formatUnlocalizedPercent(call) {
  const value = parseCallDecimal(call, "Percent function requires a numeric operand.");
  let formatted = formatUnlocalizedDecimalWithMaximumFractionDigits(value * 100, maximumFractionDigits(call));
  if (signDisplayAlways(call.function) && value >= 0) formatted = `+${formatted}`;
  return `${appendMinimumFractionDigits(formatted, minimumFractionDigits(call))}%`;
}

export function formatUnlocalizedInteger(call) {
  const value = parseCallDecimal(call, "Integer function requires a numeric operand.");
  const integer = Math.trunc(value);
  return signDisplayAlways(call.function) && integer >= 0 ? `+${integer}` : String(integer);
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
