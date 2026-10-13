// Static counterpart of the current Intl adapter's numeric option inheritance.
// Keep reset behavior covered by runtime-backed tests when changing the adapter.
const numeric = new Set(["number", "integer", "percent", "offset"]);
export const isNumeric = (name) => numeric.has(name);
// Match the runtime's accepted decimal domain, including underflow and safe
// integer bounds. A literal which can never render is a build-time error.
export function validNumericLiteral(text) {
  if (!/^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/.test(text)) return false;
  const value = Number(text);
  return (
    Number.isFinite(value) &&
    (!Number.isInteger(value) || Number.isSafeInteger(value)) &&
    !(value === 0 && /[1-9]/.test(text.split(/[eE]/, 1)[0]))
  );
}

const discarded = (fn, name) =>
  (name === "style" && fn !== "number") ||
  (fn === "integer" &&
    [
      "minimumFractionDigits",
      "maximumFractionDigits",
      "minimumSignificantDigits",
    ].includes(name)) ||
  (fn === "percent" &&
    ["minimumIntegerDigits", "roundingIncrement", "select"].includes(name)) ||
  (fn === "offset" && ["add", "subtract"].includes(name));

export function numericOptions(expression, declarations) {
  const literalOption = (value) => {
    const seen = new Set();
    while (value?.type === "variable") {
      const declaration = declarations.get(value.name);
      if (
        !declaration ||
        declaration.type !== "local" ||
        declaration.value.function ||
        seen.has(value.name)
      )
        break;
      seen.add(value.name);
      value = declaration.value.arg;
    }
    return value;
  };
  const chain = [];
  const seen = new Set();
  let current = expression;
  while (current) {
    if (current.function) chain.push(current.function);
    if (current.arg?.type !== "variable") break;
    const declaration = declarations.get(current.arg.name);
    if (!declaration || seen.has(declaration.name)) break;
    seen.add(declaration.name);
    // An input's argument refers to the external value, not its own declaration.
    if (declaration.value === current) break;
    current = declaration.value;
  }
  const fn = chain[0];
  const option = (name) => {
    if (Object.hasOwn(fn?.options ?? {}, name))
      return literalOption(fn.options[name]);
    if (!fn) return undefined;
    if (fn.name === "currency") {
      for (const source of chain.slice(1)) {
        if (
          source.name === "currency" &&
          Object.hasOwn(source.options ?? {}, name)
        )
          return literalOption(source.options[name]);
      }
      return undefined;
    }
    if (discarded(fn.name, name)) return undefined;
    for (const source of chain.slice(1)) {
      if (!numeric.has(source.name) || discarded(source.name, name)) break;
      if (Object.hasOwn(source.options ?? {}, name))
        return literalOption(source.options[name]);
    }
  };
  // Currency provenance is stricter than ordinary numeric option inheritance.
  let inheritedCurrency;
  for (const source of chain.slice(1)) {
    if (source.name !== "currency") break;
    if (Object.hasOwn(source.options ?? {}, "currency")) {
      inheritedCurrency = literalOption(source.options.currency);
      break;
    }
  }
  let inheritedExact = false;
  if (fn?.name !== "percent") {
    for (const source of chain.slice(1)) {
      if (!numeric.has(source.name) || source.name === "percent") break;
      if (literalOption(source.options?.select)?.value === "exact")
        inheritedExact = true;
    }
  }
  return { fn, option, inheritedCurrency, inheritedExact };
}

export function validateNumeric(expression, declarations, error, location) {
  const resolved = numericOptions(expression, declarations);
  const { fn, option, inheritedCurrency, inheritedExact } = resolved;
  if (!fn || (!numeric.has(fn.name) && fn.name !== "currency")) return resolved;
  const fail = (message) => error("option-value", message, location);
  const ranges = {
    minimumFractionDigits: [0, 100],
    maximumFractionDigits: [0, 100],
    minimumIntegerDigits: [1, 21],
    minimumSignificantDigits: [1, 21],
    maximumSignificantDigits: [1, 21],
  };
  for (const [name, [min, max]] of Object.entries(ranges)) {
    const value = option(name);
    if (
      value?.type === "literal" &&
      (!/^\d{1,16}$/.test(value.value) ||
        !Number.isInteger(Number(value.value)) ||
        Number(value.value) < min ||
        Number(value.value) > max)
    )
      fail(`${name} must be an integer between ${min} and ${max}.`);
  }
  for (const kind of ["FractionDigits", "SignificantDigits"]) {
    const base =
      fn.name === "integer" && kind === "FractionDigits"
        ? { type: "literal", value: "0" }
        : undefined;
    const min = option(`minimum${kind}`) ?? base;
    const max = option(`maximum${kind}`) ?? base;
    if (
      min?.type === "literal" &&
      max?.type === "literal" &&
      Number(min.value) > Number(max.value)
    )
      fail(
        `minimum${kind} must not exceed maximum${kind}, including inherited options.`,
      );
  }
  if (fn.name === "currency") {
    const direct = Object.hasOwn(fn.options ?? {}, "currency")
      ? option("currency")
      : undefined;
    const currency = inheritedCurrency ?? direct;
    if (direct && inheritedCurrency)
      fail("Currency cannot override an inherited currency option.");
    if (!currency)
      error(
        "required-option",
        ":currency requires a currency option or a currency operand.",
        location,
      );
    else if (
      currency.type === "literal" &&
      !/^[A-Za-z]{3}$/.test(currency.value)
    )
      fail("Currency must be a three-letter code.");
  }
  if (fn.name === "offset") {
    if (
      expression.arg?.type === "literal" &&
      !Number.isSafeInteger(Number(expression.arg.value))
    )
      error(
        "function-operand",
        ":offset requires a safe integer operand.",
        location,
      );
    const add = option("add");
    const subtract = option("subtract");
    if (Boolean(add) === Boolean(subtract))
      error(
        "required-option",
        ":offset requires exactly one of add or subtract.",
        location,
      );
    for (const value of [add, subtract]) {
      if (
        value?.type === "literal" &&
        (!/^[+-]?\d+$/.test(value.value) ||
          !Number.isSafeInteger(Number(value.value)))
      )
        fail("Offset add/subtract must be safe integers.");
    }
  }
  if (
    numeric.has(fn.name) &&
    (fn.options?.select?.type === "variable" || inheritedExact)
  )
    error(
      "unsupported-numeric-selection",
      "Dynamic select or inherited select=exact is unsupported by the Intl adapter; a :percent annotation resets exact provenance.",
      location,
    );
  return resolved;
}
