import { MF2Error } from "./errors.js";
import { localeIsLtr } from "./locale_direction.js";
import { checkNumericSelection, copyNumericFunctions, hasNumericDirection } from "./numeric_registry_metadata.js";
import { selectCardinal, selectOrdinal } from "./cldr_plural_rules.js";
import { createPortableFunctionRegistry } from "./portable_functions.js";
import { numericSelectionOperand } from "./unlocalized_numeric_functions.js";
import {
  functionOptionLiteral,
  inheritedExactNumericSource,
  memoizeFunctionSource,
  isNumericFunction,
  numericSelectUsesVariable,
  parseDecimalNumber,
} from "./function_support.js";

export function formatMessage(model, arguments_ = {}, options = {}) {
  const { result, isolation } = formatPartsWithMetadata(model, arguments_, options);
  const errors = result.errors;
  return {
    value: renderParts(result.parts, options.bidiIsolation ?? "none", isolation),
    errors,
    ok: errors.length === 0,
    hasErrors: errors.length > 0,
  };
}

export function formatMessageToParts(model, arguments_ = {}, options = {}) {
  return formatPartsWithMetadata(model, arguments_, options).result;
}

function formatPartsWithMetadata(model, arguments_, options) {
  model = validateModel(model);
  const context = new FormatContext(arguments_, options.locale ?? "en", options.functions ?? FunctionRegistry.defaults(), true, options);
  context.applyDeclarations(model.declarations ?? []);
  const parts = model.type === "message"
    ? context.formatPatternToParts(model.pattern ?? [])
    : context.formatSelectToParts(model.selectors ?? [], model.variants ?? []);
  return { result: {
    parts,
    errors: context.errors,
    ok: context.errors.length === 0,
    hasErrors: context.errors.length > 0,
  }, isolation: context.expressionIsolation };
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
    return copyNumericFunctions(this, new FunctionRegistry(formatters, this.selectors), name);
  }

  withSelector(name, selector) {
    const selectors = new Map(this.selectors);
    selectors.set(name, selector);
    return copyNumericFunctions(this, new FunctionRegistry(this.formatters, selectors), undefined, name);
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

const sourceDirections = new WeakMap();
const sourceDirection = source => sourceDirections.get(source) ?? { direction: null, resolvedDirection: null, forceIsolation: false };

class FormatContext {
  constructor(arguments_, locale, functions, fallback = false, options = {}) {
    this.arguments = new Map(Object.entries(arguments_ ?? {}).map(([name, value]) => [name.normalize("NFC"), value]));
    this.locals = new Map();
    this.failedLocals = new Set();
    this.errors = [];
    this.expressionIsolation = [];
    this.knownLtrLocale = localeIsLtr(locale) === true;
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
      if (annotation != null && !annotation.isString) {
        if (!this.failedLocals.has(selector.name) && this.functions.hasSelector(annotation.function)) this.errors.push(MF2Error.badOperand("Selector operand is not available."));
        this.errors.push(new MF2Error("bad-selector", "Selector operand is not available."));
      }
      return {
        rendered: "",
        normalizedRendered: annotation?.isString ? normalizeStringKey("") : null,
        exactMatch: false,
        selectionKey: null,
        function: annotation?.function ?? null,
        source: null,
        failed: true,
      };
    }
    const value = this.value(selector.name);
    const rendered = valueToString(value.rawValue);
    this.recordSelectorResolutionErrors(annotation);
    let resolvedSelectionKey = null;
    try {
      if (annotation?.isNumeric) {
        checkNumericSelection(this.functions, {
          value: rendered,
          rawValue: value.rawValue,
          function: annotation.function,
          locale: this.locale,
          optionValue: (name, fallback) => this.optionValue(annotation.function, name, fallback),
          inheritedSource: value.source,
        });
      }
      resolvedSelectionKey = selectionKey(this.locale, annotation, value);
    } catch (error) {
      this.recoverSelectorError(error);
      return {
        rendered,
        normalizedRendered: annotation?.isString ? normalizeStringKey(rendered) : null,
        exactMatch: annotation == null || annotation.exactMatch,
        selectionKey: null,
        function: annotation?.function ?? null,
        source: value.source,
        failed: true,
      };
    }
    return {
      rendered,
      normalizedRendered: annotation?.isString ? normalizeStringKey(rendered) : null,
      exactMatch: annotation == null || annotation.exactMatch,
      selectionKey: resolvedSelectionKey,
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
          this.expressionIsolation.push(output.forceIsolation || !(output.resolvedDirection === "ltr" && this.knownLtrLocale));
          const expressionPart = { type: "expression", value: output.value };
          if (part.attributes && Object.keys(part.attributes).length > 0) expressionPart.attributes = structuredClone(part.attributes);
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
        if (part.options && Object.keys(part.options).length > 0) markup.options = structuredClone(part.options);
        if (part.attributes && Object.keys(part.attributes).length > 0) markup.attributes = structuredClone(part.attributes);
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
        if (expression.function != null) this.errors.push(this.functions.hasFormatter(expression.function)
          ? MF2Error.badOperand("Function operand is not available.")
          : new MF2Error("unknown-function", "Function is not defined by this registry."));
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
    const functionRef = expression.function ?? (source == null && ["number", "bigint"].includes(typeof rawValue)
      ? Object.freeze({ type: "function", name: "number" })
      : null);
    if (functionRef == null) return { value, hadError: false, source, ...sourceDirection(source) };
    this.recordFunctionResolutionErrors(functionRef, source);
    try {
      const directionInfo = this.resolveDirection(functionRef, source);
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
        source: this.functionSource(sourceValue, functionRef, source, directionInfo),
        ...directionInfo,
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
    if (optionName === "u:dir") return fallback;
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
    if (!numericSelectUsesVariable(functionRef)
        && !inheritedExactNumericSource(source, functionRef.name)) return;
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

  resolveDirection(functionRef, source) {
    let { direction, resolvedDirection } = sourceDirection(source);
    let forceIsolation = false;
    const option = functionRef.options?.["u:dir"];
    if (option != null) {
      try {
        let value;
        if (option.type === "literal") value = option.value;
        else {
          if (!this.hasValue(option.name)) throw unresolvedVariable(option.name);
          value = this.value(option.name).rawValue;
        }
        if (!["ltr", "rtl", "auto", "inherit"].includes(value)) throw MF2Error.badOption("u:dir option must be auto, ltr, rtl, or inherit.");
        if (value !== "inherit") {
          direction = value;
          resolvedDirection = value === "auto" ? null : value;
          forceIsolation = true;
        }
      } catch (error) {
        if (!this.fallback) throw error;
        const recovered = fallbackError(error);
        if (recovered.code !== "bad-option") this.errors.push(recovered);
        this.errors.push(MF2Error.badOption("Invalid u:dir option was ignored."));
      }
    }
    if (direction == null && resolvedDirection == null && this.knownLtrLocale && hasNumericDirection(this.functions, functionRef.name)) resolvedDirection = "ltr";
    return { direction, resolvedDirection, forceIsolation };
  }

  functionSource(value, functionRef, inherited, directionInfo = this.resolveDirection(functionRef, inherited)) {
    const source = memoizeFunctionSource({
      value,
      function: functionRef,
      inherited,
      optionValue: (name, fallback) => this.optionValue(functionRef, name, fallback),
    });
    sourceDirections.set(source, directionInfo);
    return source;
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
    if (selector.failed) return null;
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
      const recoverable = this.recoverSelectorError(error);
      if (recoverable.code !== "bad-variant-key") selector.failed = true;
      return null;
    }
  }

  recoverSelectorError(error) {
    if (!this.fallback) throw error;
    const recoverable = fallbackError(error);
    this.errors.push(recoverable);
    if (!["bad-selector", "bad-variant-key"].includes(recoverable.code)) {
      this.errors.push(new MF2Error("bad-selector", "Selector failed to match."));
    }
    return recoverable;
  }
}

function validateModel(model) {
  validateModelShape(model);
  model = normalizeModelBindings(model);
  validateDeclarations(model.declarations ?? []);
  if (model.type === "message") validatePattern(model.pattern ?? []);
  else if (model.type === "select") {
    validateSelectorAnnotations(model.declarations ?? [], model.selectors ?? []);
    for (const variant of model.variants ?? []) validatePattern(variant.value ?? []);
  }
  return model;
}

function normalizeModelBindings(model) {
  const arg = (value) => value.type === "variable" ? { ...value, name: value.name.normalize("NFC") } : value;
  const part = (value) => {
    if (typeof value === "string") return value;
    const result = { ...value };
    if (result.type === "expression" && result.arg) result.arg = arg(result.arg);
    if (result.type === "expression" && result.function) result.function = part(result.function);
    if (["function", "markup"].includes(result.type) && result.options) result.options = Object.fromEntries(Object.entries(result.options).map(([name, value]) => [name, arg(value)]));
    if (result.type === "function") {
      const snapshot = structuredClone(result);
      if (snapshot.options) {
        for (const option of Object.values(snapshot.options)) Object.freeze(option);
        Object.freeze(snapshot.options);
      }
      return Object.freeze(snapshot);
    }
    return result;
  };
  const normalized = { ...model, declarations: model.declarations.map((item) => ({ ...item, name: item.name.normalize("NFC"), value: part(item.value) })) };
  if (model.type === "message") normalized.pattern = model.pattern.map(part);
  else {
    normalized.selectors = model.selectors.map(arg);
    normalized.variants = model.variants.map((item) => ({ ...item, value: item.value.map(part) }));
  }
  return normalized;
}

function requireModel(condition) {
  if (!condition) throw new MF2Error("invalid-model", "Message model does not match the MF2 data model schema.");
}

function isRecord(value) {
  return value != null && typeof value === "object" && !Array.isArray(value);
}

function validateArgShape(arg, allowed = ["literal", "variable"]) {
  requireModel(isRecord(arg));
  requireModel(allowed.includes(arg.type));
  requireModel(typeof arg[arg.type === "literal" ? "value" : "name"] === "string");
}

function validateMetadataShape(node, fields = ["options", "attributes"]) {
  for (const field of fields) {
    if (!Object.hasOwn(node, field)) continue;
    requireModel(isRecord(node[field]));
    for (const value of Object.values(node[field])) {
      if (field === "attributes" && value === true) continue;
      validateArgShape(value, field === "attributes" ? ["literal"] : ["literal", "variable"]);
    }
  }
}

function validateExpressionShape(expression) {
  requireModel(isRecord(expression));
  requireModel(expression.type === "expression");
  requireModel(Object.hasOwn(expression, "arg") || Object.hasOwn(expression, "function"));
  if (Object.hasOwn(expression, "arg")) validateArgShape(expression.arg);
  if (Object.hasOwn(expression, "function")) {
    const func = expression.function;
    requireModel(isRecord(func));
    requireModel(func.type === "function" && typeof func.name === "string");
    validateMetadataShape(func, ["options"]);
  }
  validateMetadataShape(expression, ["attributes"]);
}

function validatePatternShape(pattern) {
  requireModel(Array.isArray(pattern));
  for (const part of pattern) {
    if (typeof part === "string") continue;
    requireModel(isRecord(part));
    if (part.type === "markup") {
      requireModel(typeof part.name === "string");
      validateMarkup(part);
      validateMetadataShape(part);
    } else validateExpressionShape(part);
  }
}

function validateModelShape(model) {
  // Only standardized fields are traversed. Unknown extension metadata is allowed.
  requireModel(isRecord(model));
  requireModel(["message", "select"].includes(model.type));
  requireModel(Array.isArray(model.declarations));
  for (const declaration of model.declarations) {
    requireModel(isRecord(declaration));
    requireModel(["input", "local"].includes(declaration.type) && typeof declaration.name === "string");
    validateExpressionShape(declaration.value);
  }
  if (model.type === "message") return validatePatternShape(model.pattern);
  requireModel(Array.isArray(model.selectors) && Array.isArray(model.variants));
  for (const selector of model.selectors) validateArgShape(selector, ["variable"]);
  for (const variant of model.variants) {
    requireModel(isRecord(variant) && Array.isArray(variant.keys));
    for (const key of variant.keys) {
      requireModel(isRecord(key));
      if (key.type === "*") requireModel(!Object.hasOwn(key, "value") || typeof key.value === "string");
      else validateArgShape(key, ["literal"]);
    }
    validatePatternShape(variant.value);
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
  const operand = numericSelectionOperand(resolvedValue, annotation.function);
  if (operand == null) return null;
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
  if (error instanceof MF2Error) {
    if (error.code === "unsupported-function") {
      return new MF2Error("unknown-function", error.message);
    }
    return error;
  }
  const code = safeErrorCode(error);
  if (code === "unsupported-function") {
    return new MF2Error("unknown-function", safeErrorMessage(error));
  }
  return new MF2Error(code ?? "error", safeErrorMessage(error));
}

function safeErrorCode(error) {
  try {
    return typeof error?.code === "string" ? error.code : null;
  } catch {
    return null;
  }
}

function safeErrorMessage(error) {
  if (error instanceof Error) {
    try {
      return error.message;
    } catch {
      return "Formatting failed.";
    }
  }
  try {
    return String(error);
  } catch {
    return "Formatting failed.";
  }
}

function fallbackSource(expression) {
  if (expression.arg) return expressionArgSource(expression.arg);
  if (expression.function) return `:${expression.function.name ?? ""}`;
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
  return renderParts(parts, bidiIsolation);
}

function renderParts(parts, bidiIsolation, isolation) {
  let output = "";
  let expressionIndex = 0;
  for (const part of parts) {
    if (part.type === "text") output += part.value ?? "";
    else if (part.type === "fallback") output += part.value ?? `{${part.source ?? ""}}`;
    else if (part.type === "expression") {
      output += isolateExpression(part.value ?? "", isolation?.[expressionIndex] === false ? "none" : bidiIsolation, part.direction);
      expressionIndex += 1;
    }
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

export function valueToString(value) {
  if (value == null) return "";
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") {
    if (Number.isFinite(value) && Math.trunc(value) === value) return String(Math.trunc(value));
    return String(value);
  }
  return String(value);
}
