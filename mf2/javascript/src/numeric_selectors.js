import { MF2Error } from "./errors.js";
import {
  functionOptionLiteral,
  inheritedExactNumericSource,
  isDecimalSourceFunction,
  numericSourceOperand,
  numericSelectUsesVariable,
  parseDecimalNumber,
  parseInteger,
  sourceOptionValue,
} from "./function_support.js";
import {
  formatUnlocalizedDecimalWithMaximumFractionDigits,
  parseNonNegativeOption,
} from "./unlocalized_numeric_functions.js";

export function registerNumericSelectors(selectors) {
  selectors.set("number", selectNumber);
  selectors.set("percent", selectPercent);
  selectors.set("integer", selectInteger);
  selectors.set("offset", selectOffset);
}

export function selectNumber(match) {
  if (invalidNumericSelector(match.function, match.inheritedSource)) throw MF2Error.badSelector("Number selector cannot match this operand.");
  const value = selectionDecimal(match, "Number selector requires a numeric operand.");
  validateNumericVariantKey(match);
  return exactDecimalKeyMatches(match, value) ? 2 : null;
}

export function selectPercent(match) {
  if (invalidNumericSelector(match.function, match.inheritedSource)) throw MF2Error.badSelector("Percent selector cannot match this operand.");
  const value = selectionDecimal(match, "Percent selector requires a numeric operand.", 100);
  validateNumericVariantKey(match);
  return exactDecimalKeyMatches(match, value) ? 2 : null;
}

export function selectInteger(match) {
  if (invalidNumericSelector(match.function, match.inheritedSource)) throw MF2Error.badSelector("Integer selector cannot match this operand.");
  const value = parseMatchDecimal(match, "Integer selector requires a numeric operand.");
  validateNumericVariantKey(match);
  const key = parseInteger(match.key);
  return key != null && Math.trunc(value) === key ? 2 : null;
}

export function selectOffset(match) {
  if (functionOptionLiteral(match.function, "select", null) === "exact") return null;
  const value = parseMatchDecimal(match, "Offset selector requires a numeric operand.");
  validateNumericVariantKey(match);
  return exactDecimalKeyMatches(match, value) ? 2 : null;
}

function invalidNumericSelector(functionRef, source) {
  const select = functionOptionLiteral(functionRef, "select", null);
  return numericSelectUsesVariable(functionRef)
    || (select !== "exact" && inheritedExactNumericSource(source, functionRef.name));
}

function parseMatchDecimal(match, message) {
  let parsed = parseSourceDecimal(match.inheritedSource);
  if (parsed == null) parsed = parseDecimalNumber(match.value);
  if (parsed == null) throw MF2Error.badSelector(message);
  return parsed;
}

function selectionDecimal(match, message, multiplier = 1) {
  const value = parseMatchDecimal(match, message) * multiplier;
  const maximum = numericMatchOptionValue(match, "maximumFractionDigits", null);
  if (maximum == null) return value;
  const digits = parseNonNegativeOption(
    maximum,
    "maximumFractionDigits option must be a non-negative integer.",
  );
  return parseDecimalNumber(formatUnlocalizedDecimalWithMaximumFractionDigits(value, digits));
}

function exactDecimalKeyMatches(match, value) {
  if (Number.isInteger(value) && usesCanonicalIntegerSerialization(match)) {
    return match.key === String(value);
  }
  const key = parseDecimalNumber(match.key);
  return key != null && Object.is(value, key);
}

function validateNumericVariantKey(match) {
  const parsed = parseDecimalNumber(match.key);
  if (parsed != null) return;
  if (["zero", "one", "two", "few", "many", "other"].includes(match.key)) return;
  throw MF2Error.badVariantKey(`Variant key ${JSON.stringify(match.key)} is not valid for :${match.function.name}.`);
}

function usesCanonicalIntegerSerialization(match) {
  return [
    "minimumFractionDigits",
    "minimumIntegerDigits",
    "minimumSignificantDigits",
    "maximumSignificantDigits",
  ].every((name) => numericMatchOptionValue(match, name, null) == null);
}

const MISSING_OPTION = Symbol("missing-option");

function numericMatchOptionValue(match, name, fallback) {
  const direct = match.optionValue(name, MISSING_OPTION);
  if (direct !== MISSING_OPTION) return direct;
  return sourceOptionFrom(match.inheritedSource, name, fallback, match.function.name);
}

function sourceOptionFrom(source, name, fallback, targetFunction) {
  let current = source;
  let target = targetFunction;
  while (current != null) {
    if (numericOptionIsDiscarded(target, name)) return fallback;
    const sourceFunction = current.function?.name;
    if (!numericSourceFunctions(target).includes(sourceFunction)
        || numericOptionIsDiscarded(sourceFunction, name)) return fallback;
    const value = sourceOptionValue(current, name, MISSING_OPTION);
    if (value !== MISSING_OPTION) return value;
    target = sourceFunction;
    current = current.inherited;
  }
  return fallback;
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

function parseSourceDecimal(source) {
  if (!isDecimalSourceFunction(source?.function)) return null;
  return parseDecimalNumber(numericSourceOperand(source));
}
