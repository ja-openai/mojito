import { MF2Error } from "./errors.js";
import {
  functionOptionLiteral,
  decimalOperandsEqual,
  inheritedExactNumericSource,
  isDecimalSourceFunction,
  numericSelectUsesVariable,
  parseDecimalOperand,
  parseIntegerOperand,
  parseOffsetInteger,
  shiftDecimalOperand,
  truncateDecimalOperandToInteger,
} from "./function_support.js";

export function registerNumericSelectors(selectors) {
  selectors.set("number", selectNumber);
  selectors.set("percent", selectPercent);
  selectors.set("integer", selectInteger);
  selectors.set("offset", selectOffset);
}

export function selectNumber(match) {
  if (invalidNumericSelector(match.function, match.inheritedSource)) throw MF2Error.badSelector("Number selector cannot match this operand.");
  const value = parseMatchDecimalOperand(match, "Number selector requires a numeric operand.");
  const key = parseDecimalOperand(match.key);
  return key != null && decimalOperandsEqual(value, key) ? 2 : null;
}

export function selectPercent(match) {
  if (invalidNumericSelector(match.function, match.inheritedSource)) throw MF2Error.badSelector("Percent selector cannot match this operand.");
  const value = shiftDecimalOperand(parseMatchDecimalOperand(match, "Percent selector requires a numeric operand."), 2);
  const key = parseDecimalOperand(match.key);
  return key != null && decimalOperandsEqual(value, key) ? 2 : null;
}

export function selectInteger(match) {
  if (invalidNumericSelector(match.function, match.inheritedSource)) throw MF2Error.badSelector("Integer selector cannot match this operand.");
  const value = truncateDecimalOperandToInteger(parseMatchDecimalOperand(match, "Integer selector requires a numeric operand."));
  const key = parseIntegerOperand(match.key);
  return key != null && decimalOperandsEqual(value, key) ? 2 : null;
}

export function selectOffset(match) {
  const value = parseRequiredInteger(match.rawValue ?? match.value, "Offset selector requires a numeric operand.");
  const key = parseOffsetInteger(match.key);
  return key != null && value === key ? 2 : null;
}

function invalidNumericSelector(functionRef, source) {
  const select = functionOptionLiteral(functionRef, "select", null);
  return numericSelectUsesVariable(functionRef) || (select !== "exact" && inheritedExactNumericSource(source));
}

function parseMatchDecimalOperand(match, message) {
  let parsed = parseSourceDecimalOperand(match.inheritedSource);
  if (parsed == null) parsed = parseDecimalOperand(match.value);
  if (parsed == null) throw MF2Error.badSelector(message);
  return parsed;
}

function parseSourceDecimalOperand(source) {
  if (source == null) return null;
  if (isDecimalSourceFunction(source.function)) return parseDecimalOperand(source.value);
  return parseSourceDecimalOperand(source.inherited);
}

function parseRequiredInteger(value, message) {
  const parsed = parseOffsetInteger(value);
  if (parsed == null) throw MF2Error.badOperand(message);
  return parsed;
}
