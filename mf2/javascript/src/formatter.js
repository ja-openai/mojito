import { MF2Error } from "./errors.js";
import { selectCardinal, selectOrdinal } from "./cldr_plural_rules.js";
import { createPortableFunctionRegistry } from "./portable_functions.js";
import {
  functionOptionLiteral,
  inheritedExactNumericSource,
  isNumericFunction,
  numericSelectUsesVariable,
  parseDecimalNumber,
} from "./function_support.js";

export function formatMessage(model, arguments_ = {}, options = {}) {
  const result = formatMessageToParts(model, arguments_, options);
  const errors = result.errors;
  return {
    value: partsToString(result.parts, options.bidiIsolation ?? "none"),
    errors,
    ok: errors.length === 0,
    hasErrors: errors.length > 0,
  };
}

export function formatMessageToParts(model, arguments_ = {}, options = {}) {
  validateModel(model);
  const context = new FormatContext(arguments_, options.locale ?? "en", options.functions ?? FunctionRegistry.defaults(), true, options);
  context.applyDeclarations(model.declarations ?? []);
  const parts = model.type === "message"
    ? context.formatPatternToParts(model.pattern ?? [])
    : context.formatSelectToParts(model.selectors ?? [], model.variants ?? []);
  return {
    parts,
    errors: context.errors,
    ok: context.errors.length === 0,
    hasErrors: context.errors.length > 0,
  };
}

export class FunctionRegistry {
  constructor(formatters = new Map(), selectors = new Map()) {
    this.formatters = new Map(formatters);
    this.selectors = new Map(selectors);
  }

  static defaults() {
    return FunctionRegistry.portable();
  }

  static portable() {
    return createPortableFunctionRegistry(FunctionRegistry);
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
  constructor(arguments_, locale, functions, fallback = false, options = {}) {
    this.arguments = new Map(Object.entries(arguments_ ?? {}));
    this.locals = new Map();
    this.failedLocals = new Set();
    this.errors = [];
    this.locale = locale == null || String(locale).trim() === "" ? "en" : String(locale);
    this.functions = functions;
    this.fallback = fallback;
    this.onMissingArgument = options.onMissingArgument ?? defaultRecovery;
    this.onFormatError = options.onFormatError ?? defaultRecovery;
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
          const source = output.fallbackSource ?? fallbackSource(part);
          const fallbackPart = { type: "fallback", source };
          if (output.value !== fallbackValue(source)) fallbackPart.value = output.value;
          parts.push(fallbackPart);
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
        const error = unresolvedVariable(expression.arg.name);
        if (!this.failedLocals.has(expression.arg.name)) this.errors.push(error);
        if (expression.function != null) this.errors.push(MF2Error.badOperand("Function operand is not available."));
        const source = fallbackSource(expression);
        return {
          value: this.recoverMissingArgument(expression, expression.arg.name, source, error),
          hadError: true,
          source: null,
          direction: null,
          fallbackSource: source,
        };
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
      const recoverable = fallbackError(error);
      this.errors.push(recoverable);
      const source = fallbackSource(expression);
      return {
        value: this.recoverFormatError(expression, source, recoverable),
        hadError: true,
        source: null,
        direction: null,
        fallbackSource: source,
      };
    }
  }

  recoverMissingArgument(expression, variableName, source, error) {
    return recoverValue(this.onMissingArgument, {
      code: error.code,
      message: error.message,
      locale: this.locale,
      variableName,
      functionName: expression.function?.name ?? null,
      sourceExpression: expressionSource(expression),
      fallbackValue: fallbackValue(source),
      error,
    });
  }

  recoverFormatError(expression, source, error) {
    return recoverValue(this.onFormatError, {
      code: error.code,
      message: error.message,
      locale: this.locale,
      variableName: expression.arg?.type === "variable" ? expression.arg.name : null,
      functionName: expression.function?.name ?? null,
      sourceExpression: expressionSource(expression),
      fallbackValue: fallbackValue(source),
      error,
    });
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

function fallbackValue(source) {
  return `{${source ?? ""}}`;
}

function defaultRecovery(context) {
  return context.fallbackValue;
}

function recoverValue(handler, context) {
  const replacement = handler(context);
  return replacement == null ? context.fallbackValue : String(replacement);
}

function expressionSource(expression) {
  return `{${[expression.arg ? expressionArgSource(expression.arg) : "", expression.function ? functionSource(expression.function) : ""].filter(Boolean).join(" ")}}`;
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
    else if (part.type === "fallback") output += part.value ?? `{${part.source ?? ""}}`;
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

export function valueToString(value) {
  if (value == null) return "";
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") {
    if (Number.isFinite(value) && Math.trunc(value) === value) return String(Math.trunc(value));
    return String(value);
  }
  return String(value);
}
