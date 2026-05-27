import { MF2Error } from "./errors.js";
import { selectCardinal, selectOrdinal } from "./generated_plural_rules.js";

const NOT_SIMPLE_MESSAGE = Symbol("not-simple-message");
const SIMPLE_MESSAGE_CACHE = new WeakMap();

export function formatMessage(model, arguments_ = {}, options = {}) {
  const simpleFormatter = simpleMessageFormatter(model);
  if (simpleFormatter) return simpleFormatter(arguments_, options.bidiIsolation ?? "none");
  return partsToString(formatMessageToParts(model, arguments_, options), options.bidiIsolation ?? "none");
}

export function formatMessageToParts(model, arguments_ = {}, options = {}) {
  validateModel(model);
  const context = new FormatContext(arguments_, options.locale ?? "en", options.functions ?? FunctionRegistry.defaults());
  context.applyDeclarations(model.declarations ?? []);
  if (model.type === "message") return context.formatPatternToParts(model.pattern ?? []);
  if (model.type === "select") return context.formatSelectToParts(model.selectors ?? [], model.variants ?? []);
  throw new MF2Error("unsupported-message-type", `Unsupported message type: ${model.type}`);
}

export function formatMessageWithFallback(model, arguments_ = {}, options = {}) {
  const result = formatMessageToPartsWithFallback(model, arguments_, options);
  return { value: partsToString(result.parts, options.bidiIsolation ?? "none"), errors: result.errors };
}

export function formatMessageToPartsWithFallback(model, arguments_ = {}, options = {}) {
  validateModel(model);
  const context = new FormatContext(arguments_, options.locale ?? "en", options.functions ?? FunctionRegistry.defaults(), true);
  context.applyDeclarations(model.declarations ?? []);
  const parts = model.type === "message"
    ? context.formatPatternToParts(model.pattern ?? [])
    : context.formatSelectToParts(model.selectors ?? [], model.variants ?? []);
  return { parts, errors: context.errors };
}

export class FunctionRegistry {
  constructor(formatters = new Map(), selectors = new Map()) {
    this.formatters = new Map(formatters);
    this.selectors = new Map(selectors);
  }

  static defaults() {
    const formatters = new Map();
    const selectors = new Map();
    formatters.set("string", (call) => call.value);
    formatters.set("number", formatNumber);
    selectors.set("number", selectNumber);
    formatters.set("percent", formatPercent);
    selectors.set("percent", selectPercent);
    formatters.set("currency", formatCurrency);
    selectors.set("currency", () => {
      throw MF2Error.badSelector("Currency selector is not supported.");
    });
    formatters.set("integer", formatInteger);
    selectors.set("integer", selectInteger);
    formatters.set("datetime", formatDateTime);
    formatters.set("date", formatDate);
    formatters.set("time", formatTime);
    formatters.set("offset", formatOffset);
    selectors.set("offset", selectOffset);
    return new FunctionRegistry(formatters, selectors);
  }

  withFunction(name, formatter) {
    const formatters = new Map(this.formatters);
    formatters.set(name, formatter);
    return new FunctionRegistry(formatters, this.selectors);
  }

  withSelector(name, selector) {
    const selectors = new Map(this.selectors);
    selectors.set(name, selector);
    return new FunctionRegistry(this.formatters, selectors);
  }

  hasFormatter(functionRef) {
    return this.formatters.has(functionRef.name);
  }

  hasSelector(functionRef) {
    return this.selectors.has(functionRef.name);
  }

  format(call) {
    const formatter = this.formatters.get(call.function.name);
    if (!formatter) throw new MF2Error("unsupported-function", `Function :${call.function.name} is not supported by this formatter registry.`);
    return formatter(call);
  }

  select(match) {
    return this.selectors.get(match.function.name)?.(match) ?? null;
  }
}

class FormatContext {
  constructor(arguments_, locale, functions, fallback = false) {
    this.arguments = new Map(Object.entries(arguments_ ?? {}));
    this.locals = new Map();
    this.failedLocals = new Set();
    this.errors = [];
    this.locale = locale == null || String(locale).trim() === "" ? "en" : String(locale);
    this.functions = functions;
    this.fallback = fallback;
    this.selectorAnnotations = new Map();
  }

  applyDeclarations(declarations) {
    this.selectorAnnotations = selectorAnnotations(declarations);
    for (const declaration of declarations) {
      if (declaration.type === "input") this.applyInputDeclaration(declaration);
      if (declaration.type === "local") {
        const output = this.formatExpressionOutput(declaration.value);
        if (output.hadError) {
          this.failedLocals.add(declaration.name);
          this.locals.delete(declaration.name);
        } else {
          this.locals.set(declaration.name, { rawValue: output.value, source: output.source });
        }
      }
    }
  }

  applyInputDeclaration(input) {
    const functionRef = input.value?.function;
    if (!functionRef || !this.functions.hasFormatter(functionRef) || !this.functions.hasSelector(functionRef)) return;
    if (!this.hasValue(input.name)) {
      if (!this.fallback) throw MF2Error.missingArgument(input.name);
      this.failedLocals.add(input.name);
      this.errors.push(unresolvedVariable(input.name), MF2Error.badOperand("Function operand is not available."));
      return;
    }
    const inputValue = this.value(input.name);
    this.recordFunctionResolutionErrors(functionRef, inputValue.source);
    try {
      const rendered = valueToString(inputValue.rawValue);
      const formatted = this.functions.format({
        value: rendered,
        rawValue: inputValue.rawValue,
        function: functionRef,
        locale: this.locale,
        optionValue: (name, fallback) => this.optionValue(functionRef, name, fallback),
        inheritedSource: inputValue.source,
      });
      const sourceValue = inputValue.source?.value ?? rendered;
      this.locals.set(input.name, {
        rawValue: formatted,
        source: this.functionSource(sourceValue, functionRef, inputValue.source),
      });
    } catch (error) {
      if (!this.fallback) throw error;
      this.errors.push(fallbackError(error));
      this.failedLocals.add(input.name);
    }
  }

  formatSelectToParts(selectors, variants) {
    const selectorValues = selectors.map((selector) => this.selectorValue(selector));
    const signatures = new Set();
    let fallback = null;
    let selected = null;
    let selectedRank = null;
    for (const variant of variants) {
      this.validateVariant(variant, selectorValues, signatures);
      if (fallback == null && variant.keys.every((key) => key.type === "*")) fallback = variant;
      const rank = this.variantMatchRank(variant, selectorValues);
      if (rank != null && (selectedRank == null || compareRank(rank, selectedRank) > 0)) {
        selected = variant;
        selectedRank = rank;
      }
    }
    if (fallback == null) throw new MF2Error("missing-fallback-variant", "Select messages must include a catch-all fallback variant.");
    return this.formatPatternToParts((selected ?? fallback).value ?? []);
  }

  selectorValue(selector) {
    const annotation = this.selectorAnnotations.get(selector.name);
    if (!this.hasValue(selector.name)) {
      if (!this.fallback) throw MF2Error.missingArgument(selector.name);
      if (!this.failedLocals.has(selector.name)) this.errors.push(unresolvedVariable(selector.name));
      if (annotation != null && this.functions.hasSelector(annotation.function)) {
        if (!this.failedLocals.has(selector.name)) this.errors.push(MF2Error.badOperand("Selector operand is not available."));
        this.errors.push(new MF2Error("bad-selector", "Selector operand is not available."));
      }
      return {
        rendered: "",
        normalizedRendered: annotation?.isString ? normalizeStringKey("") : null,
        exactMatch: false,
        selectionKey: null,
        function: annotation?.function ?? null,
        source: null,
      };
    }
    const value = this.value(selector.name);
    const rendered = valueToString(value.rawValue);
    this.recordSelectorResolutionErrors(annotation);
    return {
      rendered,
      normalizedRendered: annotation?.isString ? normalizeStringKey(rendered) : null,
      exactMatch: annotation == null || annotation.exactMatch,
      selectionKey: selectionKey(this.locale, annotation, value),
      function: annotation?.function ?? null,
      source: value.source,
    };
  }

  formatPatternToParts(pattern) {
    const parts = [];
    for (const part of pattern) {
      if (typeof part === "string") {
        parts.push({ type: "text", value: part });
      } else if (part.type === "expression") {
        const output = this.formatExpressionOutput(part);
        if (output.hadError) {
          parts.push({ type: "fallback", source: fallbackSource(part) });
        } else {
          const expressionPart = { type: "expression", value: output.value };
          if (part.attributes && Object.keys(part.attributes).length > 0) expressionPart.attributes = part.attributes;
          if (output.direction) expressionPart.direction = output.direction;
          parts.push(expressionPart);
        }
      } else if (part.type === "markup") {
        if (part.options?.["u:dir"]) {
          const error = new MF2Error("bad-option", "u:dir is not valid on markup.");
          if (!this.fallback) throw error;
          this.errors.push(error);
        }
        const markup = { type: "markup", kind: part.kind, name: part.name };
        if (part.options && Object.keys(part.options).length > 0) markup.options = part.options;
        if (part.attributes && Object.keys(part.attributes).length > 0) markup.attributes = part.attributes;
        parts.push(markup);
      } else {
        throw new MF2Error("unsupported-pattern-part", `Unsupported pattern part: ${part.type}`);
      }
    }
    return parts;
  }

  formatExpressionOutput(expression) {
    let value;
    let rawValue;
    let source = null;
    if (expression.arg == null) {
      value = "";
      rawValue = "";
    } else if (expression.arg.type === "literal") {
      value = expression.arg.value ?? "";
      rawValue = value;
    } else if (expression.arg.type === "variable") {
      if (!this.hasValue(expression.arg.name)) {
        if (!this.fallback) throw MF2Error.missingArgument(expression.arg.name);
        if (!this.failedLocals.has(expression.arg.name)) this.errors.push(unresolvedVariable(expression.arg.name));
        if (expression.function != null) this.errors.push(MF2Error.badOperand("Function operand is not available."));
        return { value: fallbackSource(expression), hadError: true, source: null, direction: null };
      }
      const resolved = this.value(expression.arg.name);
      rawValue = resolved.rawValue;
      value = valueToString(rawValue);
      source = resolved.source;
    } else {
      throw new MF2Error("unsupported-expression-arg", `Unsupported expression arg: ${expression.arg.type}`);
    }
    const functionRef = expression.function;
    if (functionRef == null) return { value, hadError: false, source, direction: bidiDirectionFromSource(source) };
    this.recordFunctionResolutionErrors(functionRef, source);
    const direction = bidiDirectionForFunction(functionRef, source);
    try {
      const formatted = this.functions.format({
        value,
        rawValue,
        function: functionRef,
        locale: this.locale,
        optionValue: (name, fallback) => this.optionValue(functionRef, name, fallback),
        inheritedSource: source,
      });
      const sourceValue = source?.value ?? value;
      return {
        value: formatted,
        hadError: false,
        source: this.functionSource(sourceValue, functionRef, source),
        direction,
      };
    } catch (error) {
      if (!this.fallback) throw error;
      this.errors.push(fallbackError(error));
      return { value: fallbackSource(expression), hadError: true, source: null, direction: null };
    }
  }

  optionValue(functionRef, optionName, fallback) {
    const option = functionRef.options?.[optionName];
    if (option == null) return fallback;
    if (option.type === "literal") return option.value ?? "";
    if (option.type === "variable") {
      if (!this.hasValue(option.name)) throw MF2Error.missingArgument(option.name);
      return valueToString(this.value(option.name).rawValue);
    }
    return fallback;
  }

  hasValue(name) {
    return !this.failedLocals.has(name) && (this.locals.has(name) || this.arguments.has(name));
  }

  value(name) {
    return this.locals.get(name) ?? { rawValue: this.arguments.get(name), source: null };
  }

  recordFunctionResolutionErrors(functionRef, source) {
    if (!isNumericFunction(functionRef)) return;
    if (!numericSelectUsesVariable(functionRef) && !inheritedExactNumericSource(source)) return;
    const error = new MF2Error("bad-option", "Numeric select option is not valid in this context.");
    if (!this.fallback) throw error;
    this.errors.push(error);
  }

  recordSelectorResolutionErrors(annotation) {
    if (annotation?.function.name !== "currency") return;
    const error = new MF2Error("bad-selector", "Currency selector is not supported.");
    if (!this.fallback) throw error;
    this.errors.push(error);
  }

  functionSource(value, functionRef, inherited) {
    return {
      value,
      function: functionRef,
      inherited,
      optionValue: (name, fallback) => this.optionValue(functionRef, name, fallback),
    };
  }

  validateVariant(variant, selectorValues, signatures) {
    if ((variant.keys ?? []).length !== selectorValues.length) throw new MF2Error("variant-key-count-mismatch", "Variant key count must match selector count.");
    const signature = JSON.stringify(variantKeySignature(variant.keys ?? [], selectorValues));
    if (signatures.has(signature)) throw new MF2Error("duplicate-variant", "Select variants must have unique key tuples.");
    signatures.add(signature);
  }

  variantMatchRank(variant, selectorValues) {
    if ((variant.keys ?? []).length !== selectorValues.length) return null;
    const rank = [];
    for (let index = 0; index < variant.keys.length; index += 1) {
      const itemRank = this.keyMatchRank(variant.keys[index], selectorValues[index]);
      if (itemRank == null) return null;
      rank.push(itemRank);
    }
    return rank;
  }

  keyMatchRank(key, selector) {
    if (key.type === "*") return 0;
    if ((selector.exactMatch && literalKeyMatches(key.value ?? "", selector)) || key.value === selector.selectionKey) return 1;
    if (selector.function == null) return null;
    try {
      return this.functions.select({
        value: selector.rendered,
        rawValue: selector.rendered,
        function: selector.function,
        key: key.value ?? "",
        locale: this.locale,
        optionValue: (name, fallback) => this.optionValue(selector.function, name, fallback),
        inheritedSource: selector.source,
      });
    } catch (error) {
      if (!this.fallback) throw error;
      this.errors.push(fallbackError(error), new MF2Error("bad-selector", "Selector failed to match."));
      return null;
    }
  }
}

function validateModel(model) {
  validateDeclarations(model.declarations ?? []);
  if (model.type === "message") validatePattern(model.pattern ?? []);
  else if (model.type === "select") {
    validateSelectorAnnotations(model.declarations ?? [], model.selectors ?? []);
    for (const variant of model.variants ?? []) validatePattern(variant.value ?? []);
  }
}

function validateDeclarations(declarations) {
  const names = new Set();
  for (const declaration of declarations) {
    const name = declaration.name ?? "";
    if (declaration.type === "input") validateInputDeclaration(declaration);
    if (names.has(name)) throw new MF2Error("duplicate-declaration", `Declaration $${name} is defined more than once.`);
    names.add(name);
  }
  validateLocalReferences(declarations);
}

function validateLocalReferences(declarations) {
  const forbidden = new Set();
  for (let index = declarations.length - 1; index >= 0; index -= 1) {
    const declaration = declarations[index];
    if (declaration.type !== "local") continue;
    forbidden.add(declaration.name ?? "");
    if (expressionReferencesAny(declaration.value ?? {}, forbidden)) {
      throw new MF2Error("duplicate-declaration", `Local declaration $${declaration.name} must not reference itself or later local declarations.`);
    }
  }
}

function expressionReferencesAny(expression, names) {
  return argReferencesAny(expression.arg, names) || Object.values(expression.function?.options ?? {}).some((option) => argReferencesAny(option, names));
}

function argReferencesAny(arg, names) {
  return arg?.type === "variable" && names.has(arg.name);
}

function validateInputDeclaration(declaration) {
  const arg = declaration.value?.arg;
  if (arg?.type === "variable" && arg.name === declaration.name) return;
  throw new MF2Error("invalid-input-declaration", `Input declaration $${declaration.name} must bind the same variable name.`);
}

function validatePattern(pattern) {
  for (const part of pattern) {
    if (typeof part === "string" && part === "") throw new MF2Error("invalid-pattern-text", "Pattern text parts must be non-empty.");
    if (typeof part === "object" && part?.type === "markup") validateMarkup(part);
  }
}

function validateMarkup(markup) {
  if (["open", "standalone", "close"].includes(markup.kind)) return;
  throw new MF2Error("invalid-markup-kind", "Markup kind must be open, standalone, or close.");
}

function simpleMessageFormatter(model) {
  if (model == null || typeof model !== "object") return null;
  const cached = SIMPLE_MESSAGE_CACHE.get(model);
  if (cached === NOT_SIMPLE_MESSAGE) return null;
  if (cached != null) return cached;
  const compiled = compileSimpleMessage(model);
  SIMPLE_MESSAGE_CACHE.set(model, compiled ?? NOT_SIMPLE_MESSAGE);
  return compiled;
}

function compileSimpleMessage(model) {
  if (model.type !== "message" || (model.declarations ?? []).length !== 0) return null;
  const ops = [];
  for (const part of model.pattern ?? []) {
    if (typeof part === "string") {
      if (part === "") throw new MF2Error("invalid-pattern-text", "Pattern text parts must be non-empty.");
      ops.push(part);
    } else if (part?.type === "expression") {
      if (part.function != null) return null;
      if (part.arg == null) {
        ops.push(["literal", ""]);
      } else if (part.arg.type === "literal") {
        ops.push(["literal", part.arg.value ?? ""]);
      } else if (part.arg.type === "variable") {
        ops.push(["variable", part.arg.name ?? ""]);
      } else {
        return null;
      }
    } else if (part?.type === "markup") {
      validateMarkup(part);
      if (part.options?.["u:dir"]) throw new MF2Error("bad-option", "u:dir is not valid on markup.");
    } else {
      return null;
    }
  }
  return (arguments_, bidiIsolation) => formatSimpleOps(ops, arguments_, bidiIsolation);
}

function formatSimpleOps(ops, arguments_, bidiIsolation) {
  let output = "";
  const values = arguments_ ?? {};
  for (const op of ops) {
    if (typeof op === "string") {
      output += op;
    } else if (op[0] === "literal") {
      output += isolateSimpleExpression(op[1], bidiIsolation);
    } else {
      const name = op[1];
      if (!Object.prototype.hasOwnProperty.call(values, name)) throw MF2Error.missingArgument(name);
      output += isolateSimpleExpression(valueToString(values[name]), bidiIsolation);
    }
  }
  return output;
}

function isolateSimpleExpression(value, bidiIsolation) {
  return bidiIsolation === "default" ? `\u2068${value}\u2069` : value;
}

function validateSelectorAnnotations(declarations, selectors) {
  const annotations = selectorAnnotations(declarations);
  for (const selector of selectors) {
    if (!annotations.has(selector.name ?? "")) throw new MF2Error("missing-selector-annotation", `Selector $${selector.name} must reference a declaration with a function.`);
  }
}

function selectorAnnotations(declarations) {
  const expressions = new Map();
  const annotations = new Map();
  for (const declaration of declarations) {
    expressions.set(declaration.name ?? "", declaration.value ?? {});
    if (declaration.value?.function) annotations.set(declaration.name ?? "", SelectorAnnotation.from(declaration.value.function));
  }
  let changed = true;
  while (changed) {
    changed = false;
    for (const [name, expression] of expressions.entries()) {
      if (annotations.has(name) || expression.arg?.type !== "variable") continue;
      const annotation = annotations.get(expression.arg.name);
      if (annotation) {
        annotations.set(name, annotation);
        changed = true;
      }
    }
  }
  return annotations;
}

class SelectorAnnotation {
  constructor(functionRef, numberSelect) {
    this.function = functionRef;
    this.numberSelect = numberSelect;
  }

  static from(functionRef) {
    const option = functionRef.options?.select;
    const select = option?.type === "literal" ? option.value : "plural";
    return new SelectorAnnotation(functionRef, ["ordinal", "exact"].includes(select) ? select : "plural");
  }

  get exactMatch() {
    return this.function.name === "string" || (this.isNumeric && this.numberSelect === "exact");
  }

  get isString() {
    return this.function.name === "string";
  }

  get isNumeric() {
    return ["number", "integer", "percent", "offset"].includes(this.function.name);
  }
}

function selectionKey(locale, annotation, resolvedValue) {
  if (annotation == null || !annotation.isNumeric || annotation.numberSelect === "exact") return null;
  let operand = valueToString(resolvedValue.rawValue);
  if (annotation.function.name === "percent") operand = operand.endsWith("%") ? operand.slice(0, -1) : String(Number(operand) * 100);
  return selectPluralCategory(locale, operand, annotation.numberSelect);
}

export function selectPluralCategory(locale, value, select = "plural") {
  try {
    return select === "ordinal" ? selectOrdinal(locale, value) : selectCardinal(locale, value);
  } catch {
    return null;
  }
}

function variantKeySignature(keys, selectorValues) {
  return keys.map((key, index) => {
    if (key.type === "*") return ["*", ""];
    const selector = selectorValues[index];
    return ["=", selector.normalizedRendered == null ? (key.value ?? "") : normalizeStringKey(key.value ?? "")];
  });
}

function compareRank(left, right) {
  const length = Math.min(left.length, right.length);
  for (let index = 0; index < length; index += 1) {
    if (left[index] !== right[index]) return left[index] - right[index];
  }
  return left.length - right.length;
}

function literalKeyMatches(value, selector) {
  return selector.normalizedRendered == null ? value === selector.rendered : normalizeStringKey(value) === selector.normalizedRendered;
}

function normalizeStringKey(value) {
  return String(value).normalize("NFC");
}

function unresolvedVariable(name) {
  return new MF2Error("unresolved-variable", `Variable $${name} could not be resolved.`);
}

function fallbackError(error) {
  if (error.code === "unsupported-function") return new MF2Error("unknown-function", error.message);
  return error;
}

function fallbackSource(expression) {
  if (expression.arg) return expressionArgSource(expression.arg);
  if (expression.function) return functionSource(expression.function);
  return "";
}

function expressionArgSource(arg) {
  if (arg.type === "variable") return `$${arg.name ?? ""}`;
  return quoteLiteralSource(String(arg.value ?? ""));
}

function functionSource(functionRef) {
  let source = `:${functionRef.name ?? ""}`;
  for (const [name, value] of Object.entries(functionRef.options ?? {})) {
    source += ` ${name}=${expressionArgSource(value)}`;
  }
  return source;
}

function quoteLiteralSource(value) {
  return `|${value.replaceAll("\\", "\\\\").replaceAll("|", "\\|")}|`;
}

export function partsToString(parts, bidiIsolation = "none") {
  let output = "";
  for (const part of parts) {
    if (part.type === "text") output += part.value ?? "";
    else if (part.type === "fallback") output += `{${part.source ?? ""}}`;
    else if (part.type === "expression") output += isolateExpression(part.value ?? "", bidiIsolation, part.direction);
  }
  return output;
}

function isolateExpression(value, bidiIsolation, direction) {
  if (bidiIsolation === "default") return `${bidiMarker(direction)}${value}\u2069`;
  return value;
}

function bidiMarker(direction) {
  if (direction === "ltr") return "\u2066";
  if (direction === "rtl") return "\u2067";
  return "\u2068";
}

function bidiDirectionForFunction(functionRef, source) {
  const value = functionOptionLiteral(functionRef, "u:dir", null);
  if (value != null) return parseBidiDirection(value);
  return bidiDirectionFromSource(source);
}

function bidiDirectionFromSource(source) {
  if (!source) return null;
  const value = functionOptionLiteral(source.function, "u:dir", null);
  if (value != null) return parseBidiDirection(value);
  return bidiDirectionFromSource(source.inherited);
}

function parseBidiDirection(value) {
  if (["auto", "ltr", "rtl"].includes(value)) return value;
  throw new MF2Error("bad-option", "u:dir option must be auto, ltr, or rtl.");
}

function functionOptionLiteral(functionRef, name, fallback) {
  const option = functionRef.options?.[name];
  return option?.type === "literal" ? option.value : fallback;
}

function sourceOptionValue(source, name, fallback) {
  if (source == null) return fallback;
  if (typeof source.optionValue === "function") return source.optionValue(name, fallback);
  return functionOptionLiteral(source.function, name, fallback);
}

function isNumericFunction(functionRef) {
  return ["number", "integer", "percent", "offset"].includes(functionRef?.name);
}

function isDecimalSourceFunction(functionRef) {
  return isNumericFunction(functionRef) || functionRef?.name === "currency";
}

function numericSelectUsesVariable(functionRef) {
  return functionRef?.options?.select?.type === "variable";
}

function inheritedExactNumericSource(source) {
  if (source == null) return false;
  if (isNumericFunction(source.function) && sourceOptionValue(source, "select", null) === "exact") return true;
  return inheritedExactNumericSource(source.inherited);
}

function invalidNumericSelector(functionRef, source) {
  const select = functionOptionLiteral(functionRef, "select", null);
  return numericSelectUsesVariable(functionRef) || (select !== "exact" && inheritedExactNumericSource(source));
}

export function valueToString(value) {
  if (value == null) return "";
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") {
    if (Number.isFinite(value) && Math.trunc(value) === value) return String(Math.trunc(value));
    return String(value);
  }
  return String(value);
}

function formatNumber(call) {
  const value = parseCallDecimal(call, "Number function requires a numeric operand.");
  return formatDecimalNumber(value, signDisplayAlways(call.function), minimumFractionDigits(call));
}

function selectNumber(match) {
  if (invalidNumericSelector(match.function, match.inheritedSource)) throw MF2Error.badSelector("Number selector cannot match this operand.");
  const value = parseMatchDecimal(match, "Number selector requires a numeric operand.");
  const key = parseDecimalNumber(match.key);
  return key != null && Object.is(value, key) ? 1 : null;
}

function formatPercent(call) {
  const value = parseCallDecimal(call, "Percent function requires a numeric operand.");
  let formatted = formatDecimalWithMaximumFractionDigits(value * 100, maximumFractionDigits(call));
  if (signDisplayAlways(call.function) && value >= 0) formatted = `+${formatted}`;
  return `${appendMinimumFractionDigits(formatted, minimumFractionDigits(call))}%`;
}

function selectPercent(match) {
  if (invalidNumericSelector(match.function, match.inheritedSource)) throw MF2Error.badSelector("Percent selector cannot match this operand.");
  const value = parseMatchDecimal(match, "Percent selector requires a numeric operand.") * 100;
  const key = parseDecimalNumber(match.key);
  return key != null && Object.is(value, key) ? 1 : null;
}

function formatCurrency(call) {
  const value = parseCallDecimal(call, "Currency function requires a numeric operand.");
  const currency = currencyCode(call);
  if (currency == null) throw MF2Error.badOperand("Currency function requires a currency option.");
  const digits = currencyFractionDigits(call);
  const number = digits == null ? formatDecimalNumber(value, false, 0) : value.toFixed(digits);
  return `${currency} ${number}`;
}

function formatInteger(call) {
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

function formatDateTime(call) {
  if (isIsoDate(call.value) || isIsoDateTime(call.value)) return call.value;
  throw MF2Error.badOperand("Datetime function requires a date or datetime operand.");
}

function formatDate(call) {
  if (isIsoDate(call.value) || isIsoDateTime(call.value)) return call.value;
  throw MF2Error.badOperand("Date function requires a date or datetime operand.");
}

function formatTime(call) {
  if (isIsoDateTime(call.value)) return call.value;
  throw MF2Error.badOperand("Datetime and time functions require a datetime operand.");
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

function parseDecimalNumber(value) {
  const text = String(value);
  if (!/^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/.test(text)) return null;
  const parsed = Number(text);
  return Number.isFinite(parsed) ? parsed : null;
}

function formatDecimalNumber(value, signAlways, minimumFractionDigits) {
  let formatted = String(value);
  if (formatted.endsWith(".0")) formatted = formatted.slice(0, -2);
  if (signAlways && value >= 0) formatted = `+${formatted}`;
  return appendMinimumFractionDigits(formatted, minimumFractionDigits);
}

function formatDecimalWithMaximumFractionDigits(value, digits) {
  if (digits == null) return formatDecimalNumber(value, false, 0);
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

function currencyCode(call) {
  return call.optionValue("currency", null) ?? inheritedCurrencyCode(call.inheritedSource);
}

function inheritedCurrencyCode(source) {
  if (source == null) return null;
  if (source.function?.name === "currency") {
    const currency = sourceOptionValue(source, "currency", null);
    if (currency != null) return currency;
  }
  return inheritedCurrencyCode(source.inherited);
}

function currencyFractionDigits(call) {
  const value = call.optionValue("fractionDigits", null);
  if (value == null || value === "auto") return null;
  return parseNonNegativeOption(value, "fractionDigits option must be auto or a non-negative integer.");
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

function isIsoDateTime(value) {
  const text = String(value);
  const separator = text.indexOf("T");
  return separator >= 0 && isIsoDate(text.slice(0, separator)) && isIsoTime(text.slice(separator + 1));
}

function isIsoDate(value) {
  return /^\d{4}-\d{2}-\d{2}$/.test(String(value));
}

function isIsoTime(value) {
  return /^\d{2}:\d{2}:\d{2}$/.test(String(value));
}
