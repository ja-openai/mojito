import { parseToModel } from "@mojito-mf2/core/parser";
import { defaultLimits, intlSignatures, valueTypes } from "./signatures.js";
import { normalizeModel, normalizeSignatures } from "./normalize.js";
import {
  isNumeric,
  numericOptions,
  validateNumeric,
  validNumericLiteral,
} from "./numeric.js";
import { reporter } from "./diagnostics.js";
import { markupPatternContract } from "./markup.js";
import { validateModelData } from "./model-data.js";

export const contractVersion = 1;
export const own = (object, name) =>
  Object.hasOwn(object, name) ? object[name] : undefined;
export const canonical = (value) =>
  JSON.stringify(value, function (_key, entry) {
    if (entry && typeof entry === "object" && !Array.isArray(entry))
      return Object.fromEntries(
        Object.keys(entry)
          .sort()
          .map((key) => [key, entry[key]]),
      );
    return entry;
  });
const nfc = (name) => name.normalize("NFC");
const record = (map) =>
  Object.fromEntries([...map].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
const categories = new Set(["zero", "one", "two", "few", "many", "other"]);
export const isCategory = (key) => categories.has(key);

export function limitsFor(options) {
  const limits = { ...defaultLimits, ...options.limits };
  for (const [key, value] of Object.entries(limits)) {
    if (
      !Object.hasOwn(defaultLimits, key) ||
      !Number.isSafeInteger(value) ||
      value < 1
    )
      throw new TypeError(`Invalid analysis limit: ${key}`);
  }
  return limits;
}

export function analyzeMessage(source, options = {}) {
  const diagnostics = [];
  const report = reporter(diagnostics, limitsFor(options).diagnostics);
  const error = (code, message, location) =>
    report({
      code,
      severity: "error",
      message,
      ...(location ? { location } : {}),
    });
  const finish = (model = null, contract = null) => ({
    ok: !diagnostics.some((d) => d.severity === "error"),
    model,
    contract,
    diagnostics,
  });
  const limits = limitsFor(options);
  const profile = options.profile ?? "react";
  if (!["react", "model"].includes(profile))
    throw new TypeError("Unknown MF2 profile");
  if (typeof source !== "string") {
    error("source-type", "A message must be an MF2 source string.");
    return finish();
  }
  if (source.length > limits.sourceLength) {
    error(
      "source-limit",
      `Message exceeds ${limits.sourceLength} UTF-16 code units.`,
    );
    return finish();
  }
  const parsed = parseToModel(source);
  if (!parsed.model || parsed.hasDiagnostics) {
    report(
      ...parsed.diagnostics.map((d) => ({ ...d, code: `parse/${d.code}` })),
    );
    return finish();
  }
  return analyzeParsed(parsed.model, options, diagnostics);
}

function analyzeParsed(model, options, diagnostics = []) {
  const report = reporter(diagnostics, limitsFor(options).diagnostics);
  const error = (code, message, location) =>
    report({
      code,
      severity: "error",
      message,
      ...(location ? { location } : {}),
    });
  const finish = (model = null, contract = null) => ({
    ok: !diagnostics.some((d) => d.severity === "error"),
    model,
    contract,
    diagnostics,
  });
  const limits = limitsFor(options);
  const profile = options.profile ?? "react";
  if (!["react", "model"].includes(profile))
    throw new TypeError("Unknown MF2 profile");
  model = normalizeModel(model, error);
  const functions = normalizeSignatures(options.functions, error);
  const patterns =
    model.type === "select"
      ? model.variants.map((v) => v.value)
      : [model.pattern];
  if (
    model.declarations.length > limits.declarations ||
    (model.selectors?.length ?? 0) > limits.selectors ||
    patterns.length > limits.variants ||
    patterns.reduce((n, p) => n + p.length, 0) > limits.parts
  ) {
    error(
      "model-limit",
      "Message exceeds the declaration, selector, variant or part budget.",
    );
    return finish();
  }
  const declarations = new Map();
  const inputs = new Map();
  const bindings = new Map();
  const uses = new Set();
  for (const [index, declaration] of model.declarations.entries()) {
    if (declarations.has(declaration.name))
      error(
        "duplicate-declaration",
        `Duplicate declaration $${declaration.name}.`,
      );
    declarations.set(declaration.name, { ...declaration, index });
  }
  function external(
    arg,
    types = valueTypes,
    location = "expression",
    current = Infinity,
  ) {
    if (arg?.type !== "variable") return;
    let name = arg.name;
    // Local aliases can forward an external value. A function result has its
    // own semantics; don't constrain its original operand a second time.
    const visited = new Set();
    while (declarations.get(name)?.type === "local") {
      const local = declarations.get(name);
      if (visited.has(name) || local.index >= current) {
        error(
          "local-reference",
          `Local $${name} is referenced before its definition.`,
          location,
        );
        return;
      }
      visited.add(name);
      if (local.value.function || local.value.arg?.type !== "variable") return;
      name = nfc(local.value.arg.name);
    }
    const previous = inputs.get(name) ?? [...valueTypes];
    const allowed = previous.filter((type) => types.includes(type));
    if (!allowed.length)
      error(
        "argument-type-conflict",
        `Incompatible uses of $${name}.`,
        location,
      );
    inputs.set(name, allowed);
  }
  function signature(name, location) {
    const hasCustom = Object.hasOwn(functions ?? {}, name);
    const result = hasCustom ? functions[name] : own(intlSignatures, name);
    if (!hasCustom && result === undefined) {
      error(
        "unknown-function",
        `Declare an explicit capability signature for :${name}.`,
        location,
      );
      return null;
    }
    const validTypes = (types) =>
      Array.isArray(types) &&
      types.length > 0 &&
      types.every((type) => valueTypes.includes(type));
    if (
      !result ||
      typeof result !== "object" ||
      !validTypes(result.input) ||
      (result.selector !== undefined &&
        !["string", "plural", "exact"].includes(result.selector)) ||
      (result.options != null &&
        (typeof result.options !== "object" ||
          Array.isArray(result.options))) ||
      Object.values(result.options ?? {}).some(
        (option) =>
          !option ||
          !validTypes(option.types) ||
          (option.values !== undefined &&
            (!Array.isArray(option.values) ||
              !option.values.every((value) => typeof value === "string"))),
      )
    ) {
      error(
        "invalid-function-signature",
        `Invalid capability signature for :${name}.`,
        location,
      );
      return null;
    }
    return result;
  }
  function expression(part, location, current = Infinity) {
    const fn = part.function;
    if (!fn) {
      external(part.arg, valueTypes, location, current);
      return;
    }
    const spec = signature(fn.name, location);
    if (spec && !part.arg && !spec.input.includes("undefined"))
      error("function-operand", `:${fn.name} requires an operand.`, location);
    if (
      spec?.input.length === 1 &&
      spec.input[0] === "number" &&
      part.arg?.type === "literal" &&
      (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(
        part.arg.value,
      ) ||
        !Number.isFinite(Number(part.arg.value)))
    )
      error(
        "function-operand",
        `:${fn.name} requires a numeric operand.`,
        location,
      );
    if (
      part.arg?.type === "literal" &&
      (isNumeric(fn.name) || ["currency", "relativeTime"].includes(fn.name)) &&
      !Object.hasOwn(functions ?? {}, fn.name) &&
      !validNumericLiteral(part.arg.value)
    )
      error(
        "function-operand",
        `:${fn.name} requires a representable decimal operand.`,
        location,
      );
    external(part.arg, spec?.input ?? valueTypes, location, current);
    for (const [name, arg] of Object.entries(fn.options ?? {})) {
      const option = spec?.options && own(spec.options, name);
      external(arg, option?.types ?? valueTypes, location, current);
      if (spec && !option)
        error(
          "unknown-option",
          `:${fn.name} does not declare option ${name}.`,
          location,
        );
      if (
        option?.values &&
        arg.type === "literal" &&
        !option.values.includes(arg.value)
      )
        error(
          "option-value",
          `Invalid ${name} value for :${fn.name}.`,
          location,
        );
      if (
        option?.types.length === 1 &&
        option.types[0] === "number" &&
        arg.type === "literal" &&
        (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(arg.value) ||
          !Number.isFinite(Number(arg.value)))
      )
        error(
          "option-value",
          `Option ${name} requires a finite number.`,
          location,
        );
    }
    for (const [name, option] of Object.entries(spec?.options ?? {})) {
      if (option.required && !Object.hasOwn(fn.options ?? {}, name))
        error(
          "required-option",
          `:${fn.name} requires option ${name}.`,
          location,
        );
    }
    if (!functions || !Object.hasOwn(functions, fn.name))
      validateNumeric(part, declarations, error, location);
    uses.add(canonical(part));
  }
  for (const [index, declaration] of model.declarations.entries()) {
    expression(declaration.value, `declarations[${index}]`, index);
    if (
      declaration.type === "input" &&
      declaration.value.arg?.name !== declaration.name
    )
      error("input-name", "An input must declare its own argument name.");
  }
  const branches = patterns.map((pattern, index) => {
    const location = `patterns[${index}]`;
    const functions = [];
    for (const part of pattern) {
      if (typeof part === "string") continue;
      if (part.type === "expression") {
        expression(part, location);
        functions.push(canonical(part));
      } else {
        for (const value of Object.values(part.options ?? {}))
          external(value, valueTypes, location);
        const kinds = bindings.get(part.name) ?? new Set();
        kinds.add(part.kind);
        bindings.set(part.name, kinds);
      }
    }
    const shape = markupPatternContract(
      pattern,
      { ...options, profile, limits },
      error,
    );
    return {
      hierarchy: shape.hierarchy,
      functions: functions.sort(),
      keys:
        model.type === "select"
          ? model.variants[index].keys.map((key) =>
              key.type === "*" ? null : nfc(key.value),
            )
          : [],
      markup: shape.markup,
    };
  });
  const selectors = [];
  if (model.type === "select") {
    const duplicates = new Set();
    for (const selector of model.selectors) {
      external(selector);
      let declaration = declarations.get(selector.name);
      const visited = new Set();
      while (
        declaration &&
        !declaration.value.function &&
        declaration.value.arg?.type === "variable" &&
        !visited.has(declaration.name)
      ) {
        visited.add(declaration.name);
        declaration = declarations.get(nfc(declaration.value.arg.name));
      }
      const fn = declaration?.value.function;
      if (!fn)
        error(
          "selector-annotation",
          `Selector $${selector.name} requires a function annotation.`,
        );
      const spec = fn && signature(fn.name, `selector $${selector.name}`);
      if (spec && !spec.selector)
        error(
          "unsupported-selector",
          `:${fn.name} does not support selection.`,
        );
      const resolved =
        declaration && numericOptions(declaration.value, declarations);
      const selection = fn?.options?.select;
      if (selection?.type === "variable")
        error(
          "dynamic-selector-mode",
          "Dynamic selection modes are outside the static contract profile.",
        );
      if (
        profile === "react" &&
        fn &&
        isNumeric(fn.name) &&
        !Object.hasOwn(functions ?? {}, fn.name)
      ) {
        const opts = Object.fromEntries(
          [
            "notation",
            "minimumSignificantDigits",
            "maximumSignificantDigits",
            "style",
          ].map((name) => [name, resolved.option(name)]),
        );
        if (
          (opts.notation &&
            (opts.notation.type !== "literal" ||
              opts.notation.value !== "standard")) ||
          opts.minimumSignificantDigits ||
          opts.maximumSignificantDigits ||
          (fn.name === "number" &&
            opts.style &&
            (opts.style.type !== "literal" || opts.style.value === "percent"))
        )
          error(
            "unsupported-selector-options",
            "The Intl adapter cannot select with notation, significant digits or number style=percent.",
          );
      }
      const mode = selection?.value === "exact" ? "exact" : spec?.selector;
      selectors.push({
        name: selector.name,
        function: fn ?? null,
        mode: mode ?? null,
      });
    }
    for (const branch of branches.filter(Boolean)) {
      if (branch.keys.length !== selectors.length)
        error(
          "variant-arity",
          "Variant key count differs from selector count.",
        );
      const key = canonical(branch.keys);
      if (duplicates.has(key))
        error(
          "duplicate-variant",
          "Variant keys are duplicates after NFC normalization.",
        );
      duplicates.add(key);
      branch.keys.forEach((value, index) => {
        const selector = selectors[index];
        if (!selector || value === null || selector.mode === "string") return;
        if (selector.mode === "plural" && isCategory(value)) return;
        if (
          ["plural", "exact"].includes(selector.mode) &&
          !validNumericLiteral(value)
        )
          error("selector-key", `Invalid numeric selector key ${value}.`);
      });
    }
    if (
      !branches.some(
        (branch) => branch && branch.keys.every((key) => key === null),
      )
    )
      error(
        "missing-fallback",
        "A matcher requires an all-wildcard fallback variant.",
      );
  }
  const contract = {
    version: contractVersion,
    profile,
    inputs: record(inputs),
    bindings: record(
      new Map([...bindings].map(([name, kinds]) => [name, [...kinds].sort()])),
    ),
    declarations: model.declarations,
    functions: [...uses].sort(),
    selectors,
    branches: branches.filter(Boolean),
  };
  return finish(model, contract);
}

// Already-parsed callers (such as a TMS editor) can reuse their parser output.
// Validate first; normalization copies only changed paths. No source reparse occurs.
export function analyzeModel(model, options = {}) {
  const limits = limitsFor(options);
  try {
    validateModelData(model, limits);
    const copied = model;
    const object = (value) =>
      value !== null && typeof value === "object" && !Array.isArray(value);
    const arg = (value) =>
      object(value) &&
      (value.type === "variable"
        ? typeof value.name === "string"
        : value.type === "literal" && typeof value.value === "string");
    const attributes = (value) =>
      value === undefined ||
      (object(value) &&
        Object.values(value).every(
          (v) => v === true || (arg(v) && v.type === "literal"),
        ));
    const expression = (value) =>
      object(value) &&
      value.type === "expression" &&
      attributes(value.attributes) &&
      (value.arg !== undefined || value.function !== undefined) &&
      (!value.arg || arg(value.arg)) &&
      (!value.function ||
        (object(value.function) &&
          value.function.type === "function" &&
          typeof value.function.name === "string" &&
          (!value.function.options ||
            (object(value.function.options) &&
              Object.values(value.function.options).every(arg)))));
    const pattern = (value) =>
      Array.isArray(value) &&
      value.every((part) =>
        typeof part === "string"
          ? part.length > 0
          : expression(part) ||
            (object(part) &&
              part.type === "markup" &&
              attributes(part.attributes) &&
              typeof part.name === "string" &&
              ["open", "close", "standalone"].includes(part.kind) &&
              (!part.options ||
                (object(part.options) &&
                  Object.values(part.options).every(arg)))),
      );
    const valid =
      object(copied) &&
      Array.isArray(copied.declarations) &&
      copied.declarations.every(
        (d) =>
          object(d) &&
          ["input", "local"].includes(d.type) &&
          typeof d.name === "string" &&
          expression(d.value),
      ) &&
      (copied.type === "message"
        ? pattern(copied.pattern)
        : copied.type === "select" &&
          Array.isArray(copied.selectors) &&
          copied.selectors.every((s) => arg(s) && s.type === "variable") &&
          Array.isArray(copied.variants) &&
          copied.variants.every(
            (v) =>
              object(v) &&
              Array.isArray(v.keys) &&
              v.keys.every(
                (k) =>
                  object(k) &&
                  (k.type === "*" || (arg(k) && k.type === "literal")),
              ) &&
              pattern(v.value),
          ));
    if (!valid) throw new Error("Model does not match the MF2 model shape.");
    return analyzeParsed(copied, options);
  } catch (error) {
    return {
      ok: false,
      model: null,
      contract: null,
      diagnostics: [
        { code: "model-invalid", severity: "error", message: error.message },
      ],
    };
  }
}
