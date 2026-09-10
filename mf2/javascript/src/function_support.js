export const MAX_FRACTION_DIGITS = 100;

// Weak keys keep this state owned by one format call. Only literal-only
// histories opt in; variable option resolvers may observe later inputs.
const sourceMemos = new WeakMap();
export function memoizeFunctionSource(source) {
  if ((!source.inherited || sourceMemos.has(source.inherited))
      && Object.values(source.function.options ?? {}).every(option => option.type === "literal")) {
    sourceMemos.set(source, new Map());
  }
  return Object.freeze(source);
}
export function sourceMemo(source) { return sourceMemos.get(source); }

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
  if (targetFunction === "percent") return false;
  let current = source;
  const visited = [];
  let found = false;
  while (current != null) {
    const memo = sourceMemo(current);
    if (memo?.has("exact_select")) { found = memo.get("exact_select"); break; }
    visited.push(current);
    if (!isNumericFunction(current.function)) break;
    const sourceFunction = current.function.name;
    if (sourceFunction === "percent") break;
    if (sourceOptionValue(current, "select", null) === "exact") { found = true; break; }
    current = current.inherited;
  }
  for (const item of visited) sourceMemo(item)?.set("exact_select", found);
  return found;
}

export function isDecimalSourceFunction(functionRef) {
  return isNumericFunction(functionRef) || functionRef?.name === "currency";
}

export function parseDecimalNumber(value) {
  const text = String(value);
  if (!/^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/.test(text)) return null;
  const parsed = Number(text);
  if (!Number.isFinite(parsed)) return null;
  // A nonzero decimal below binary64's range must not become an exact zero.
  // Exponent digits do not make a zero coefficient nonzero (0e-999 is valid).
  if (parsed === 0 && /[1-9]/.test(text.split(/[eE]/, 1)[0])) return null;
  return Number.isInteger(parsed) && !Number.isSafeInteger(parsed) ? null : parsed;
}

export function parseInteger(value) {
  if (!/^[+-]?\d+$/.test(String(value))) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

export function numericSourceOperand(source) {
  if (source == null) return null;
  const chain = [];
  let operand = null;
  for (let current = source; current != null; current = current.inherited) {
    const memo = sourceMemo(current);
    if (memo?.has("numeric_operand")) { operand = memo.get("numeric_operand"); break; }
    chain.push(current);
  }
  for (let index = chain.length - 1; index >= 0; index -= 1) {
    const current = chain[index];
    if (operand == null) operand = current.value;
    const name = current.function?.name;
    if (!isDecimalSourceFunction(current.function)) {
      sourceMemo(current)?.set("numeric_operand", operand);
      continue;
    }
    const parsed = parseDecimalNumber(operand);
    if (parsed == null) return null;
    if (name === "integer") operand = String(Math.trunc(parsed));
    if (name === "offset") {
      const add = sourceOptionValue(current, "add", null);
      const subtract = sourceOptionValue(current, "subtract", null);
      const delta = parseInteger(add ?? subtract);
      if (delta == null || (add == null) === (subtract == null)) return null;
      operand = addIntegerOffset(operand, add == null ? -delta : delta);
      if (operand == null) return null;
    }
    sourceMemo(current)?.set("numeric_operand", operand);
  }
  return String(operand);
}

const MAX_EXPANDED_DECIMAL_DIGITS = 4096;

export function addIntegerOffset(value, delta) {
  if (!Number.isSafeInteger(delta)) return null;
  if (typeof value === "number"
      && (!Number.isFinite(value)
          || (Number.isInteger(value) && !Number.isSafeInteger(value)))) return null;
  const text = String(value);
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
