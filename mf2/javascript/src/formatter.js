import { MF2Error } from "./errors.js";
import { selectCardinal, selectOrdinal } from "./cldr_plural_rules.js";
import { formatNumberCore } from "./number_core.js";
import { createPortableFunctionRegistry } from "./portable_functions.js";
import {
  functionOptionLiteral,
  inheritedExactNumericSource,
  isNumericFunction,
  numericSelectUsesVariable,
  parseDecimalNumber,
  parseDecimalOperand,
} from "./function_support.js";

export function formatMessage(model, arguments_ = {}, options = {}) {
  const result = formatMessageToParts(model, arguments_, options);
  const errors = result.errors;
  if (result.parts.length === 0 && errors.length > 0) return { value: "", errors, ok: false, hasErrors: true };
  const bidiIsolation = bidiIsolationOption(options);
  if (bidiIsolation.error) return formatErrorResult(bidiIsolation.error);
  return {
    value: partsToString(result.parts, bidiIsolation.value),
    errors,
    ok: errors.length === 0,
    hasErrors: errors.length > 0,
  };
}

export function formatMessageToParts(model, arguments_ = {}, options = {}) {
  validateModel(model);
  const locale = localeOption(options);
  if (locale.error) return errorPartsResult(locale.error);
  const functions = functionsOption(options);
  if (functions.error) return errorPartsResult(functions.error);
  const argumentsMap = argumentsOption(arguments_);
  if (argumentsMap.error) return errorPartsResult(argumentsMap.error);
  const recoveryHandlers = recoveryHandlersOption(options);
  if (recoveryHandlers.error) return errorPartsResult(recoveryHandlers.error);
  const context = new FormatContext(argumentsMap.value, locale.value, functions.value, true, recoveryHandlers.value);
  context.applyDeclarations(modelArrayField(model, "declarations"));
  const parts = model.type === "message"
    ? context.formatPatternToParts(modelArrayField(model, "pattern"))
    : context.formatSelectToParts(modelArrayField(model, "selectors"), modelArrayField(model, "variants"));
  return {
    parts,
    errors: context.errors,
    ok: context.errors.length === 0,
    hasErrors: context.errors.length > 0,
  };
}

function localeOption(options) {
  try {
    const value = String(options.locale ?? "en").trim();
    return { value: value === "" ? "en" : value };
  } catch (error) {
    return { error: MF2Error.badOption(safeErrorMessage(error)) };
  }
}

function functionsOption(options) {
  try {
    const value = options.functions ?? FunctionRegistry.defaults();
    if (value instanceof FunctionRegistry) return { value };
    return { error: MF2Error.badOption("functions must be a FunctionRegistry.") };
  } catch (error) {
    return { error: MF2Error.badOption(safeErrorMessage(error)) };
  }
}

function argumentsOption(arguments_) {
  try {
    return { value: new Map(Object.entries(arguments_ ?? {})) };
  } catch (error) {
    return { error: MF2Error.badOption(safeErrorMessage(error)) };
  }
}

function recoveryHandlersOption(options) {
  try {
    return {
      value: {
        onMissingArgument: recoveryHandlerOption(options.onMissingArgument),
        onFormatError: recoveryHandlerOption(options.onFormatError),
      },
    };
  } catch (error) {
    return { error: MF2Error.badOption(safeErrorMessage(error)) };
  }
}

function bidiIsolationOption(options) {
  try {
    const value = options.bidiIsolation ?? "none";
    return { value: typeof value === "string" ? value : "none" };
  } catch (error) {
    return { error: MF2Error.badOption(safeErrorMessage(error)) };
  }
}

function formatErrorResult(error) {
  return { value: "", errors: [error], ok: false, hasErrors: true };
}

function errorPartsResult(error) {
  return { parts: [], errors: [error], ok: false, hasErrors: true };
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

  withRegistry(other) {
    let registry = this;
    for (const [name, formatter] of other.formatters) {
      registry = registry.withFunction(name, formatter);
    }
    for (const [name, selector] of other.selectors) {
      registry = registry.withSelector(name, selector);
    }
    return registry;
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
  constructor(argumentsMap, locale, functions, fallback = false, recoveryHandlers = {}) {
    this.arguments = argumentsMap;
    this.locals = new Map();
    this.failedLocals = new Set();
    this.errors = [];
    this.locale = locale;
    this.functions = functions;
    this.fallback = fallback;
    this.onMissingArgument = recoveryHandlers.onMissingArgument ?? defaultRecovery;
    this.onFormatError = recoveryHandlers.onFormatError ?? defaultRecovery;
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
          this.locals.set(declaration.name, { rawValue: output.rawValue, source: output.source });
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
      const rendered = renderValueToString(inputValue.rawValue);
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
      if (annotation != null && (this.failedLocals.has(selector.name) || this.functions.hasSelector(annotation.function))) {
        if (!this.failedLocals.has(selector.name)) this.errors.push(MF2Error.badOperand("Selector operand is not available."));
        this.errors.push(new MF2Error("bad-selector", "Selector operand is not available."));
      }
      return {
        rendered: "",
        rawValue: "",
        normalizedRendered: annotation?.isString ? normalizeStringKey("") : null,
        exactMatch: false,
        selectionKey: null,
        function: annotation?.function ?? null,
        source: null,
      };
    }
    const value = this.value(selector.name);
    this.recordSelectorResolutionErrors(annotation);
    let rendered;
    let key;
    try {
      rendered = renderValueToString(value.rawValue);
      key = selectionKey(this.locale, annotation, value);
    } catch (error) {
      const recoverable = fallbackError(error);
      if (!this.fallback) throw recoverable;
      this.errors.push(recoverable);
      if (annotation != null) {
        this.errors.push(new MF2Error("bad-selector", "Selector operand is not available."));
      }
      return {
        rendered: "",
        rawValue: "",
        normalizedRendered: annotation?.isString ? normalizeStringKey("") : null,
        exactMatch: false,
        selectionKey: null,
        function: null,
        source: null,
      };
    }
    return {
      rendered,
      rawValue: value.rawValue,
      normalizedRendered: annotation?.isString ? normalizeStringKey(rendered) : null,
      exactMatch: annotation == null || annotation.exactMatch,
      selectionKey: key,
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
          rawValue: "",
          hadError: true,
          source: null,
          direction: null,
          fallbackSource: source,
        };
      }
      const resolved = this.value(expression.arg.name);
      rawValue = resolved.rawValue;
      try {
        value = renderValueToString(rawValue);
      } catch (error) {
        if (!this.fallback) throw fallbackError(error);
        const recoverable = fallbackError(error);
        this.errors.push(recoverable);
        const source = fallbackSource(expression);
        return {
          value: this.recoverFormatError(expression, source, recoverable),
          rawValue: "",
          hadError: true,
          source: null,
          direction: null,
          fallbackSource: source,
        };
      }
      source = resolved.source;
    } else {
      throw new MF2Error("unsupported-expression-arg", `Unsupported expression arg: ${expression.arg.type}`);
    }
    const functionRef = expression.function;
    if (functionRef == null) {
      try {
        value = renderPrimitiveValueToString(rawValue, this.locale);
        return { value, rawValue, hadError: false, source, direction: bidiDirectionFromSource(source) };
      } catch (error) {
        if (!this.fallback) throw fallbackError(error);
        const recoverable = fallbackError(error);
        this.errors.push(recoverable);
        const source = fallbackSource(expression);
        return {
          value: this.recoverFormatError(expression, source, recoverable),
          rawValue,
          hadError: true,
          source: null,
          direction: null,
          fallbackSource: source,
        };
      }
    }
    this.recordFunctionResolutionErrors(functionRef, source);
    try {
      const direction = bidiDirectionForFunction(functionRef, source);
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
        rawValue: formatted,
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
        rawValue: "",
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
      return optionValueToString(this.value(option.name).rawValue);
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
    if (selector.exactMatch && numericLiteralKeyMatchesSource(key.value ?? "", selector)) return 3;
    if (selector.exactMatch && !isNumericFunction(selector.function) && literalKeyMatches(key.value ?? "", selector)) return 2;
    if (key.value === selector.selectionKey) return 1;
    if (selector.function == null) return null;
    try {
      return this.functions.select({
        value: selector.rendered,
        rawValue: selector.rawValue,
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
  if (model == null || typeof model !== "object") {
    throw new MF2Error("unsupported-message-type", "Unsupported message type: .");
  }
  const type = model.type;
  const declarations = modelObjectEntries(modelArrayField(model, "declarations"), "declarations");
  validateDeclarations(declarations);
  if (type === "message") validatePattern(modelArrayField(model, "pattern"));
  else if (type === "select") {
    validateSelectorAnnotations(declarations, modelObjectEntries(modelArrayField(model, "selectors"), "selectors"));
    for (const variant of modelObjectEntries(modelArrayField(model, "variants"), "variants")) {
      modelObjectEntries(modelArrayField(variant, "keys"), "variant keys");
      validatePattern(modelArrayField(variant, "value"));
    }
  } else {
    throw new MF2Error("unsupported-message-type", `Unsupported message type: ${type ?? ""}.`);
  }
}

function modelArrayField(object, name) {
  const value = object?.[name];
  if (value == null) return [];
  if (Array.isArray(value)) return value;
  throw MF2Error.badOption(`${name} must be an array.`);
}

function modelObjectEntries(values, name) {
  return values.map((value) => {
    if (value != null && typeof value === "object" && !Array.isArray(value)) return value;
    throw MF2Error.badOption(`${name} entries must be objects.`);
  });
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
    if (typeof part === "string") {
      if (part === "") throw new MF2Error("invalid-pattern-text", "Pattern text parts must be non-empty.");
      continue;
    }
    if (part == null || typeof part !== "object" || Array.isArray(part)) {
      throw new MF2Error("unsupported-pattern-part", "Unsupported pattern part: ");
    }
    if (part.type === "expression") continue;
    if (part.type === "markup") {
      validateMarkup(part);
      continue;
    }
    throw new MF2Error("unsupported-pattern-part", `Unsupported pattern part: ${part.type ?? ""}`);
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

function numericLiteralKeyMatchesSource(value, selector) {
  const sourceKey = preferredNumericSourceKey(selector);
  return sourceKey != null && value === sourceKey && parseDecimalOperand(value) != null;
}

function preferredNumericSourceKey(selector) {
  const functionName = selector.function?.name;
  if (functionName !== "number" && functionName !== "percent") return null;
  const sourceValue = numericSourceValue(selector.source, functionName);
  const operand = parseSourceDecimal(sourceValue);
  if (operand == null) return null;
  if (functionName === "percent") {
    return renderSourceDecimal({ ...operand, scale: operand.scale - 2 }, { trimFractionZeros: false });
  }
  return operand.hasExponent ? renderSourceDecimal(operand, { trimFractionZeros: true }) : sourceValue;
}

function numericSourceValue(source, functionName) {
  if (source == null) return null;
  if (source.function?.name === functionName) return source.value;
  return numericSourceValue(source.inherited, functionName);
}

const SOURCE_DECIMAL_PATTERN = /^(-?)(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/;
const MAX_SOURCE_DECIMAL_EXPONENT = 1000000;
const MAX_SOURCE_DECIMAL_KEY_LENGTH = 4096;

function parseSourceDecimal(value) {
  if (value == null) return null;
  const match = SOURCE_DECIMAL_PATTERN.exec(String(value));
  if (!match) return null;
  const exponent = parseSourceExponent(match[4] ?? "");
  if (exponent == null) return null;
  const fraction = match[3] ?? "";
  const digits = `${match[2]}${fraction}`.replace(/^0+/, "") || "0";
  return {
    negative: match[1] === "-" && digits !== "0",
    digits,
    scale: fraction.length - exponent,
    hasExponent: match[4] != null,
  };
}

function parseSourceExponent(value) {
  if (value === "") return 0;
  const negative = value.startsWith("-");
  const unsigned = negative || value.startsWith("+") ? value.slice(1) : value;
  const digits = unsigned.replace(/^0+/, "") || "0";
  if (digits.length > 7) return null;
  const parsed = Number(digits);
  if (!Number.isSafeInteger(parsed) || parsed > MAX_SOURCE_DECIMAL_EXPONENT) return null;
  return negative ? -parsed : parsed;
}

function renderSourceDecimal(operand, { trimFractionZeros }) {
  const extraLength = operand.scale > operand.digits.length ? operand.scale - operand.digits.length : Math.max(-operand.scale, 0);
  if (operand.digits.length + extraLength + 2 > MAX_SOURCE_DECIMAL_KEY_LENGTH) return null;
  let text;
  if (operand.scale <= 0) {
    text = `${operand.digits}${"0".repeat(-operand.scale)}`;
  } else if (operand.scale >= operand.digits.length) {
    text = `0.${"0".repeat(operand.scale - operand.digits.length)}${operand.digits}`;
  } else {
    const split = operand.digits.length - operand.scale;
    text = `${operand.digits.slice(0, split)}.${operand.digits.slice(split)}`;
  }
  if (trimFractionZeros && text.includes(".")) text = text.replace(/\.?0+$/, "");
  return operand.negative ? `-${text}` : text;
}

function normalizeStringKey(value) {
  return String(value).normalize("NFC");
}

function unresolvedVariable(name) {
  return new MF2Error("unresolved-variable", `Variable $${name} could not be resolved.`);
}

function fallbackError(error) {
  if (error instanceof MF2Error) {
    if (error.code === "unsupported-function") return new MF2Error("unknown-function", error.message);
    return error;
  }
  const code = safeErrorCode(error);
  if (code === "unsupported-function") return new MF2Error("unknown-function", safeErrorMessage(error));
  if (code != null) return new MF2Error(code, safeErrorMessage(error));
  return new MF2Error("error", safeErrorMessage(error));
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
  if (expression.function) return functionNameSource(expression.function);
  return "";
}

function fallbackValue(source) {
  return `{${source ?? ""}}`;
}

function defaultRecovery(context) {
  return context.fallbackValue;
}

function recoveryHandlerOption(handler) {
  return typeof handler === "function" ? handler : defaultRecovery;
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

function functionNameSource(functionRef) {
  return `:${functionRef.name ?? ""}`;
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

function optionValueToString(value) {
  try {
    return valueToString(value);
  } catch {
    throw MF2Error.badOption("Function option value is not available.");
  }
}

function renderValueToString(value) {
  try {
    return valueToString(value);
  } catch {
    throw MF2Error.badOperand("Value could not be rendered.");
  }
}

function renderPrimitiveValueToString(value, locale) {
  try {
    if (typeof value === "number" || typeof value === "bigint") return formatNumberCore(value, { locale });
    return valueToString(value);
  } catch (error) {
    throw fallbackError(error);
  }
}
