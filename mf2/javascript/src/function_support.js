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
