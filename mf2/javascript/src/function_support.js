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

export function inheritedExactNumericSource(source, targetFunction) {
  if (source == null || targetFunction === "percent" || !isNumericFunction(source.function)) return false;
  const sourceFunction = source.function.name;
  if (sourceFunction === "percent") return false;
  if (sourceOptionValue(source, "select", null) === "exact") return true;
  return inheritedExactNumericSource(source.inherited, sourceFunction);
}

export function isDecimalSourceFunction(functionRef) {
  return isNumericFunction(functionRef) || functionRef?.name === "currency";
}

export function parseDecimalNumber(value) {
  const text = String(value);
  if (!/^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/.test(text)) return null;
  const parsed = Number(text);
  return Number.isFinite(parsed) ? parsed : null;
}

export function parseInteger(value) {
  if (!/^[+-]?\d+$/.test(String(value))) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

export function numericSourceOperand(source) {
  if (source == null) return null;
  let operand = numericSourceOperand(source.inherited);
  if (operand == null) operand = source.value;
  const name = source.function?.name;
  if (!isDecimalSourceFunction(source.function)) return operand;
  const parsed = parseDecimalNumber(operand);
  if (parsed == null) return null;
  if (name === "integer") return String(Math.trunc(parsed));
  if (name === "offset") {
    const add = sourceOptionValue(source, "add", null);
    const subtract = sourceOptionValue(source, "subtract", null);
    const delta = parseInteger(add ?? subtract);
    if (delta == null || (add == null) === (subtract == null)) return null;
    return addIntegerOffset(operand, add == null ? -delta : delta);
  }
  return String(operand);
}

const MAX_EXPANDED_DECIMAL_DIGITS = 4096;

export function addIntegerOffset(value, delta) {
  if (!Number.isSafeInteger(delta)) return null;
  const text = String(value);
  if (parseDecimalNumber(text) == null && parseInteger(text) == null) return null;
  const match = /^([+-]?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(text);
  if (match == null) return null;

  const fraction = match[3] ?? "";
  const digits = `${match[2]}${fraction}`;
  if (/^0+$/.test(digits)) return String(delta);
  const exponent = Number(match[4] ?? "0");
  if (!Number.isSafeInteger(exponent)) return null;

  let scale = fraction.length - exponent;
  let coefficient = BigInt(digits);
  if (match[1] === "-") coefficient = -coefficient;
  if (scale < 0) {
    if (-scale > MAX_EXPANDED_DECIMAL_DIGITS) return null;
    coefficient *= 10n ** BigInt(-scale);
    scale = 0;
  }
  if (scale > MAX_EXPANDED_DECIMAL_DIGITS) return null;
  coefficient += BigInt(delta) * (10n ** BigInt(scale));
  if (coefficient === 0n) return "0";

  const negative = coefficient < 0n;
  let output = (negative ? -coefficient : coefficient).toString();
  if (scale > 0) {
    output = output.padStart(scale + 1, "0");
    const split = output.length - scale;
    const fractionOutput = output.slice(split).replace(/0+$/, "");
    output = fractionOutput === "" ? output.slice(0, split) : `${output.slice(0, split)}.${fractionOutput}`;
  }
  return negative ? `-${output}` : output;
}
