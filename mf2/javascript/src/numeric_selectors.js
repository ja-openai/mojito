import { MF2Error } from "./errors.js";
import {
  functionOptionLiteral,
  inheritedExactNumericSource,
  isDecimalSourceFunction,
  numericSelectUsesVariable,
  parseDecimalNumber,
  parseInteger,
} from "./function_support.js";

export function registerNumericSelectors(selectors) {
  selectors.set("number", selectNumber);
  selectors.set("percent", selectPercent);
  selectors.set("integer", selectInteger);
  selectors.set("offset", selectOffset);
}

export function selectNumber(match) {
  if (invalidNumericSelector(match.function, match.inheritedSource)) throw MF2Error.badSelector("Number selector cannot match this operand.");
  const value = parseMatchDecimal(match, "Number selector requires a numeric operand.");
  const key = parseDecimalNumber(match.key);
  return key != null && Object.is(value, key) ? 1 : null;
}

export function selectPercent(match) {
  if (invalidNumericSelector(match.function, match.inheritedSource)) throw MF2Error.badSelector("Percent selector cannot match this operand.");
  const value = parseMatchDecimal(match, "Percent selector requires a numeric operand.") * 100;
  const key = parseDecimalNumber(match.key);
  return key != null && Object.is(value, key) ? 1 : null;
}

export function selectInteger(match) {
  if (invalidNumericSelector(match.function, match.inheritedSource)) throw MF2Error.badSelector("Integer selector cannot match this operand.");
  const value = parseMatchDecimal(match, "Integer selector requires a numeric operand.");
  const key = parseInteger(match.key);
  return key != null && Math.trunc(value) === key ? 1 : null;
}

export function selectOffset(match) {
  const value = parseRequiredInteger(match.value, "Offset selector requires a numeric operand.");
  const key = parseInteger(match.key);
  return key != null && value === key ? 1 : null;
}

function invalidNumericSelector(functionRef, source) {
  const select = functionOptionLiteral(functionRef, "select", null);
  return numericSelectUsesVariable(functionRef) || (select !== "exact" && inheritedExactNumericSource(source));
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

function parseRequiredInteger(value, message) {
  const parsed = parseInteger(value);
  if (parsed == null) throw MF2Error.badOperand(message);
  return parsed;
}
