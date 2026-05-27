import {
  formatMessageToPartsWithFallback,
  parseToModel,
  partsToString,
} from "@mojito-mf2/core";

export type Severity = "error" | "warning";

export type EditorDiagnostic = {
  code: string;
  end?: number;
  formLabel?: string;
  formLabels?: Array<string>;
  message: string;
  severity: Severity;
  start?: number;
};

export type EditorDeclaration = {
  argName: string;
  argSource?: string;
  attributeText: string;
  function: string;
  name: string;
  optionText: string;
  type: "input" | "local" | string;
};

export type EditorVariant = {
  keys: Array<string>;
  value: string;
};

export type LocalePluralRowSuggestion = {
  category: string;
  keys: Array<string>;
  kind: "cardinal" | "ordinal";
  label: string;
  sample: number | null;
  selector: string;
};

export type InvalidLocalePluralRow = {
  category: string;
  index: number;
  kind: "cardinal" | "ordinal";
  label: string;
  removable: boolean;
  selector: string;
};

export type PlaceholderCompletionToken = {
  from: number;
  query: string;
  text: string;
};

export type PlaceholderCompletionContext = {
  from: number;
  query: string;
};

export type PlaceholderCompletionReplacement = {
  cursor: number;
  from: number;
  insert: string;
  to: number;
};

export type PlaceholderExpression = {
  from: number;
  name: string;
  source: string;
  to: number;
};

export type EditorModel =
  | {
      declarations: Array<EditorDeclaration>;
      pattern: string;
      rustModel?: unknown;
      source: string;
      type: "message";
      variables: Array<string>;
    }
  | {
      declarations: Array<EditorDeclaration>;
      rustModel?: unknown;
      selectors: Array<string>;
      source: string;
      type: "select";
      variables: Array<string>;
      variants: Array<EditorVariant>;
    };

type SelectEditorModel = Extract<EditorModel, { type: "select" }>;

type SelectorContract = {
  function: string;
  name: string;
  optionText: string;
};

type MarkupShape = {
  attributes: Set<string>;
  close: number;
  open: number;
  options: Set<string>;
  standalone: number;
};

type RuntimeDeclaration = {
  name?: string;
  type?: string;
  value?: {
    arg?: RuntimeArg;
    attributes?: Record<string, RuntimeArg>;
    function?: {
      name?: string;
      options?: Record<string, RuntimeArg>;
    };
  };
};

type RuntimeArg = {
  name?: string;
  type?: string;
  value?: string;
};

type RuntimePart =
  | string
  | {
      arg?: RuntimeArg;
      attributes?: Record<string, RuntimeArg>;
      function?: { name?: string; options?: Record<string, RuntimeArg> };
      kind?: string;
      name?: string;
      options?: Record<string, RuntimeArg>;
      type?: string;
    };

type RuntimeModel = {
  declarations?: Array<RuntimeDeclaration>;
  pattern?: Array<RuntimePart>;
  selectors?: Array<{ name?: string }>;
  type?: string;
  variants?: Array<{
    keys?: Array<{ type?: string; value?: string }>;
    value?: Array<RuntimePart>;
  }>;
};

export function parseMf2(source: string, args: Record<string, unknown>, locale: string) {
  const parsed = parseToModel(source);
  const diagnostics = [...(parsed.diagnostics ?? [])].map(editorDiagnostic);
  let output = "";
  let parts: Array<unknown> = [];
  if (parsed.model) {
    try {
      const formatted = formatMessageToPartsWithFallback(parsed.model, coerceArgs(args), {
        bidiIsolation: "default",
        locale,
      });
      parts = formatted.parts ?? [];
      output = partsToString(parts, "default");
      diagnostics.push(...(formatted.errors ?? []).map(editorDiagnostic));
    } catch (error) {
      diagnostics.push(editorDiagnostic(error));
    }
  }
  return {
    diagnostics,
    model: modelFromRuntime(parsed.model, source),
    output,
    parts,
  };
}

export function diagnosticsFor(
  sourceModel: EditorModel | null,
  targetModel: EditorModel | null,
  targetDiagnostics: Array<EditorDiagnostic>,
  locale = "en",
) {
  const diagnostics = [...targetDiagnostics];
  if (!sourceModel || !targetModel) return diagnostics;
  const sourceInsertionNames = placeholderInsertionNameSet(sourceModel);
  const targetInsertionNames = targetPlaceholderInsertionNameSet(targetModel);
  const targetCoverageNames = targetSourceCoverageNameSet(targetModel);
  const targetRenderedNames = placeholderNamesInModelPatterns(targetModel);
  const sourceSelectorNames = selectorNamesFromModel(sourceModel);
  const targetSelectorNames = selectorNamesFromModel(targetModel);
  const sourceMarkupNames = markupNamesFromModel(sourceModel);
  const targetMarkupNames = markupNamesFromModel(targetModel);
  const sourceOptionNames = optionVariableNamesFromModel(sourceModel);
  const sourceOptionOwners = optionVariableOwnerNamesFromModel(sourceModel);
  const targetOptionNames = optionVariableNamesFromModel(targetModel);
  const targetOptionOwners = optionVariableOwnerNamesFromModel(targetModel);
  const targetAddedNames = new Set(
    [...targetInsertionNames].filter((name) => targetRenderedNames.has(name) || !targetOptionNames.has(name)),
  );
  for (const name of targetOptionNames) {
    if (
      optionVariableOwnerIsCovered(targetOptionOwners.get(name), targetRenderedNames, targetSelectorNames, targetMarkupNames)
      && optionVariableOwnerIsSourceCovered(targetOptionOwners.get(name), sourceInsertionNames, sourceSelectorNames, sourceMarkupNames)
    ) {
      targetAddedNames.add(name);
    }
  }
  for (const name of targetAddedNames) {
    if (targetSelectorNames.has(name)) continue;
    if (!sourceInsertionNames.has(name)) {
      diagnostics.push({
        code: "new-placeholder",
        message: `Target uses {$${name}}, which is not in the source.`,
        severity: "error",
      });
    }
  }
  for (const name of sourceInsertionNames) {
    if (sourceSelectorNames.has(name)) continue;
    if (!optionVariableOwnerIsCovered(sourceOptionOwners.get(name), targetRenderedNames, targetSelectorNames, targetMarkupNames)) continue;
    if (!targetCoverageNames.has(name)) {
      diagnostics.push({
        code: "missing-source-placeholder",
        message: `Target omits source placeholder {$${name}}; keep it omitted only when intentional.`,
        severity: "warning",
      });
    }
  }
  for (const name of sourceOptionNames) {
    if (!sourceSelectorNames.has(name) || targetOptionNames.has(name)) continue;
    if (!optionVariableOwnerIsCovered(sourceOptionOwners.get(name), targetRenderedNames, targetSelectorNames, targetMarkupNames)) continue;
    diagnostics.push({
      code: "missing-source-placeholder",
      message: `Target omits source placeholder {$${name}}; keep it omitted only when intentional.`,
      severity: "warning",
    });
  }
  const selectorDiagnostics = selectorContractDiagnostics(sourceModel, targetModel);
  diagnostics.push(...selectorDiagnostics);
  diagnostics.push(...targetVariantOrderDiagnostics(targetModel, locale));
  if (!selectorDiagnostics.length) {
    diagnostics.push(
      ...variantContractDiagnostics(sourceModel, targetModel, locale),
      ...variantPlaceholderDiagnostics(sourceModel, targetModel),
      ...markupContractDiagnostics(sourceModel, targetModel),
    );
  }
  return diagnostics;
}

export function printModel(model: EditorModel | null) {
  if (!model) return "";
  const declarations = (model.declarations ?? []).map(declarationToSource);
  if (model.type !== "select") {
    const pattern = patternValueToSource(model.pattern ?? "");
    return declarations.length ? [...declarations, `{{${pattern}}}`].join("\n") : pattern;
  }
  return [
    ...declarations,
    `.match ${model.selectors.map((name) => `$${name}`).join(" ")}`,
    ...model.variants.map((variant) => `${variant.keys.map(variantKeyToSource).join(" ")} {{${patternValueToSource(variant.value)}}}`),
  ].join("\n");
}

export function cloneModel(model: EditorModel): EditorModel {
  if (model.type === "select") {
    return {
      declarations: model.declarations.map((declaration) => ({ ...declaration })),
      selectors: [...model.selectors],
      source: model.source,
      type: "select",
      variables: [...model.variables],
      variants: model.variants.map((variant) => ({ keys: [...variant.keys], value: variant.value })),
    };
  }
  return {
    declarations: model.declarations.map((declaration) => ({ ...declaration })),
    pattern: model.pattern,
    source: model.source,
    type: "message",
    variables: [...model.variables],
  };
}

export function sourceLiteralPreview(source: string) {
  const parsed = parseToModel(source);
  const model = modelFromRuntime(parsed.model, source);
  if (!model) return source.split("\n").at(-1) ?? source;
  if (model.type !== "select") return model.pattern ?? "";
  return model.variants.find((variant) => (variant.keys ?? []).every((key) => key === "*"))?.value
    ?? model.variants[0]?.value
    ?? "";
}

export function sourceInputContractItems(model: EditorModel | null) {
  if (!model) return [];
  const declared = new Set<string>();
  const declarationSources = sourceDeclarationEntriesForModel(model);
  const rows = (model.declarations ?? []).map((declaration) => {
    declared.add(declaration.name);
    return declarationSources.get(declaration)?.line ?? declarationToSource(declaration);
  });
  const sourceByName = placeholderSourceByName(model);
  for (const name of placeholderNames(model)) {
    if (!declared.has(name)) rows.push(sourceByName[name] ?? `{$${name}}`);
  }
  return rows;
}

export function placeholderInsertionNames(model: EditorModel | null) {
  if (!model) return [];
  const names = placeholderInsertionNameSet(model);
  return [...names].sort();
}

export function placeholderInsertionNamesForActiveSource(model: EditorModel | null, sourcePattern: string) {
  if (!model) return [];
  const names = activePlaceholderInsertionNameSet(model, sourcePattern);
  return [...names].sort();
}

export function declarationToSource(declaration: EditorDeclaration) {
  if (declaration.type === "local") {
    return `.local $${declaration.name} = ${declarationExpressionToSource(declaration, declarationArgSource(declaration))}`;
  }
  return `.input ${declarationExpressionToSource(declaration, declarationArgSource(declaration, declaration.name))}`;
}

function declarationArgSource(declaration: EditorDeclaration, fallbackName = "") {
  return declaration.argSource ?? (declaration.argName ? `$${declaration.argName}` : fallbackName ? `$${fallbackName}` : "");
}

function declarationExpressionToSource(declaration: EditorDeclaration, argSource: string) {
  const arg = argSource;
  const functionSource = declaration.function
    ? `:${declaration.function}${declaration.optionText ? ` ${declaration.optionText}` : ""}`
    : "";
  const tail = [functionSource, declaration.attributeText].filter(Boolean).join(" ");
  return `{${arg}${arg && tail ? " " : ""}${tail}}`;
}

export function placeholderNames(model: EditorModel | null) {
  if (!model) return [];
  const names = new Set(model.variables ?? []);
  const patterns = model.type === "select" ? model.variants.map((variant) => variant.value) : [model.pattern];
  for (const pattern of patterns) {
    placeholderNamesInPattern(pattern).forEach((name) => names.add(name));
  }
  return [...names].sort();
}

export function placeholderSourceByName(model: EditorModel | null) {
  const sources: Record<string, string> = {};
  if (!model) return sources;
  const declarationSources = sourceDeclarationEntriesForModel(model);
  const patterns = model.type === "select" ? model.variants.map((variant) => variant.value) : [model.pattern];
  for (const pattern of patterns) {
    for (const expression of placeholderExpressionsInPattern(pattern)) {
      preferPlaceholderSource(sources, expression.name, expression.source);
    }
  }
  for (const declaration of model.declarations ?? []) {
    if (!declaration.name || sources[declaration.name]) continue;
    sources[declaration.name] = declarationSources.get(declaration)?.expression
      ?? declarationPlaceholderSource(declaration);
  }
  return sources;
}

export function placeholderSourcesInPattern(pattern: string) {
  const sources: Record<string, string> = {};
  for (const expression of placeholderExpressionsInPattern(pattern)) {
    preferPlaceholderSource(sources, expression.name, expression.source);
  }
  return sources;
}

function preferPlaceholderSource(sources: Record<string, string>, name: string, source: string) {
  const current = sources[name];
  if (!current || placeholderSourcePriority(source) > placeholderSourcePriority(current)) {
    sources[name] = source;
  }
}

function placeholderSourcePriority(source: string) {
  const tail = placeholderExpressionTail(source);
  let priority = 0;
  if (tail) priority += 100;
  if (tail.startsWith(":")) priority += 40;
  if (tail.includes("=")) priority += 20;
  if (/(^|\s)@/u.test(tail)) priority += 20;
  if (hasBidiMarker(source)) priority += 5;
  return priority;
}

function placeholderExpressionTail(source: string) {
  const value = String(source ?? "");
  if (value[0] !== "{") return "";
  let cursor = 1;
  while (isMf2SyntaxWhitespace(value[cursor])) cursor += 1;
  if (value[cursor] !== "$") return "";
  cursor += 1;
  if (isBidiMarkerChar(value[cursor])) cursor += value[cursor].length;
  while (cursor < value.length) {
    const char = codePointCharAt(value, cursor);
    if (!isPlaceholderNameChar(char)) break;
    cursor += char.length;
  }
  if (isBidiMarkerChar(value[cursor])) cursor += value[cursor].length;
  const end = value.lastIndexOf("}");
  return end < cursor ? "" : value.slice(cursor, end).trim();
}

function hasBidiMarker(value: string) {
  for (const char of String(value ?? "")) {
    if (isBidiMarkerChar(char)) return true;
  }
  return false;
}

function declarationPlaceholderSource(declaration: EditorDeclaration) {
  if (declaration.type === "input") return declarationExpressionToSource(declaration, declarationArgSource(declaration, declaration.name));
  return `{$${declaration.name}}`;
}

function sourceDeclarationEntriesForModel(model: EditorModel) {
  const lines = sourceDeclarationLines(model.source);
  const used = new Set<number>();
  const entries = new Map<EditorDeclaration, { expression?: string; line: string }>();
  for (const declaration of model.declarations ?? []) {
    const lineIndex = lines.findIndex((line, index) => !used.has(index) && sourceDeclarationLineMatches(line, declaration));
    if (lineIndex < 0) continue;
    used.add(lineIndex);
    const line = lines[lineIndex];
    entries.set(declaration, {
      expression: declaration.type === "input" ? firstBracedExpressionSource(line) ?? undefined : undefined,
      line,
    });
  }
  return entries;
}

function sourceDeclarationLines(source: string) {
  return String(source ?? "")
    .split(/\r\n|\r|\n/u)
    .map((line) => line.trim())
    .filter((line) => /^\.(?:input|local)\b/u.test(line));
}

function sourceDeclarationLineMatches(line: string, declaration: EditorDeclaration) {
  const type = declaration.type === "local" ? "local" : "input";
  if (!line.startsWith(`.${type}`)) return false;
  if (!declaration.name) return true;
  const name = type === "input" ? sourceInputDeclarationName(line) : sourceLocalDeclarationName(line);
  return name === declaration.name;
}

function sourceInputDeclarationName(line: string) {
  const expression = firstBracedExpressionSource(line);
  return expression ? placeholderExpressionsInPattern(expression)[0]?.name ?? "" : "";
}

function sourceLocalDeclarationName(line: string) {
  const dollarIndex = line.indexOf("$");
  return dollarIndex < 0 ? "" : variableNameAfterDollar(line, dollarIndex);
}

function variableNameAfterDollar(value: string, dollarIndex: number) {
  let cursor = dollarIndex + 1;
  if (isBidiMarkerChar(value[cursor])) cursor += value[cursor].length;
  const start = cursor;
  while (cursor < value.length) {
    const char = codePointCharAt(value, cursor);
    if (!isPlaceholderNameChar(char)) break;
    cursor += char.length;
  }
  return value.slice(start, cursor);
}

function firstBracedExpressionSource(value: string) {
  const start = value.indexOf("{");
  if (start < 0) return null;
  const end = variableExpressionEnd(value, start + 1);
  return end == null ? null : value.slice(start, end + 1);
}

export function filterPlaceholderNames(names: Array<string>, query: string) {
  const normalizedQuery = String(query ?? "").toLocaleLowerCase();
  return [...new Set(names)].filter((name) => {
    if (!normalizedQuery) return true;
    return name.toLocaleLowerCase().startsWith(normalizedQuery);
  });
}

export function placeholderCompletionToken(value: string, caretOffset = value.length): PlaceholderCompletionToken | null {
  const beforeCursor = String(value ?? "").slice(0, Math.max(0, caretOffset));
  const queryEnd = previousCodePointIsBidiMarker(beforeCursor, beforeCursor.length) ?? beforeCursor.length;
  const nameStart = placeholderTokenNameStart(beforeCursor, queryEnd);
  let tokenCursor = previousCodePointIsBidiMarker(beforeCursor, nameStart) ?? nameStart;
  let dollarCursor: number | null = null;
  if (beforeCursor[tokenCursor - 1] === "$") {
    dollarCursor = tokenCursor - 1;
    tokenCursor = dollarCursor;
  }
  let bracedCursor = tokenCursor;
  while (isMf2SyntaxWhitespace(beforeCursor[bracedCursor - 1])) bracedCursor -= 1;
  if (beforeCursor[bracedCursor - 1] === "{") {
    const from = bracedCursor - 1;
    if (isEscapedAt(beforeCursor, from) || isBlockedBracedCompletionStart(beforeCursor, from)) return null;
    return {
      from,
      query: beforeCursor.slice(nameStart, queryEnd),
      text: beforeCursor.slice(from),
    };
  }
  if (dollarCursor == null) return null;
  const from = dollarCursor;
  if (isEscapedAt(beforeCursor, from)) return null;
  return {
    from,
    query: beforeCursor.slice(nameStart, queryEnd),
    text: beforeCursor.slice(from),
  };
}

export function placeholderCompletionContext(value: string, caretOffset = value.length): PlaceholderCompletionContext | null {
  const token = placeholderCompletionToken(value, caretOffset);
  return token ? { from: token.from, query: token.query } : null;
}

export function placeholderCompletionReplacement(
  source: string,
  from: number,
  to: number,
  name: string,
  replacementSource = `{$${name}}`,
): PlaceholderCompletionReplacement {
  const typedToken = String(source ?? "").slice(from, to);
  return {
    cursor: from + replacementSource.length,
    from,
    insert: replacementSource,
    to: placeholderCompletionConsumesClosingBrace(typedToken, String(source ?? "").slice(to, to + 1)) ? to + 1 : to,
  };
}

export function placeholderCompletionConsumesClosingBrace(typedToken: string, nextChar: string) {
  return (typedToken.startsWith("{") || typedToken.startsWith("$")) && nextChar === "}";
}

export function placeholderNamesInPattern(pattern: string) {
  return placeholderExpressionsInPattern(pattern).map((expression) => expression.name);
}

export function missingPlaceholderNamesForPattern(sourcePattern: string, targetPattern: string) {
  const targetCounts = placeholderCountsInPattern(targetPattern);
  const missing: Array<string> = [];
  for (const requirement of placeholderRequirementsInPattern(sourcePattern)) {
    const deficit = requirement.count - (targetCounts.get(requirement.name) ?? 0);
    for (let index = 0; index < deficit; index += 1) missing.push(requirement.name);
  }
  return missing;
}

export function missingPlaceholderNamesForActiveSource(
  sourceModel: EditorModel | null,
  targetModel: EditorModel | null,
  sourcePattern: string,
  targetPattern: string,
) {
  const missing = missingPlaceholderNamesForPattern(sourcePattern, targetPattern);
  if (!sourceModel) return missing;
  const targetOptionNames = optionVariableNamesInPatternSource(targetPattern);
  const targetVisibleNames = placeholderCountsInPattern(targetPattern);
  const targetRenderedNames = new Set(placeholderNamesInPattern(targetPattern));
  const targetSelectorNames = targetModel ? selectorNamesFromModel(targetModel) : new Set<string>();
  const targetMarkupNames = markupNamesInPatternSource(targetPattern);
  const selectorNames = selectorNamesFromModel(sourceModel);
  const sourceOptionNames = optionVariableNamesFromModel(sourceModel);
  const activeSourceOptionNames = optionVariableNamesForActiveSource(sourceModel, sourcePattern);
  const activeSourceOptionOwners = optionVariableOwnerNamesForActiveSource(sourceModel, sourcePattern);
  const activeLocalDependencyNames = localDependencyNamesForActiveSource(sourceModel, sourcePattern);
  const activeRenderedNames = new Set(placeholderNamesInPattern(sourcePattern));
  const renderedNames = placeholderNamesInModelPatterns(sourceModel);
  for (const name of placeholderNames(sourceModel)) {
    if (activeRenderedNames.has(name)) continue;
    if (activeLocalDependencyNames.has(name) && !activeSourceOptionNames.has(name)) continue;
    if (renderedNames.has(name)) continue;
    if (selectorNames.has(name) && !activeSourceOptionNames.has(name)) continue;
    if (sourceOptionNames.has(name)) {
      if (!activeSourceOptionNames.has(name)) continue;
      if (!optionVariableOwnerIsCovered(activeSourceOptionOwners.get(name), targetRenderedNames, targetSelectorNames, targetMarkupNames)) continue;
      if (targetVisibleNames.has(name) || targetOptionNames.has(name)) continue;
      missing.push(name);
      continue;
    }
    if (targetVisibleNames.has(name)) continue;
    missing.push(name);
  }
  return missing;
}

export function patternWithRestoredPlaceholders(
  pattern: string,
  missing: Array<string>,
  sourceForName: (name: string) => string,
) {
  const restored = missing.map(sourceForName).join(" ");
  if (!restored) return pattern;
  const value = String(pattern ?? "");
  if (!value || /\s$/u.test(value)) return `${value}${restored}`;
  return `${value} ${restored}`;
}

export function placeholderExpressionsInPattern(pattern: string): Array<PlaceholderExpression> {
  const value = String(pattern ?? "");
  const expressions: Array<PlaceholderExpression> = [];
  for (let index = 0; index < value.length; index += 1) {
    if (value[index] !== "{") continue;
    if (isEscapedAt(value, index)) continue;
    const start = index;
    let cursor = index + 1;
    while (isMf2SyntaxWhitespace(value[cursor])) cursor += 1;
    if (value[cursor] !== "$") continue;
    cursor += 1;
    if (isBidiMarkerChar(value[cursor])) cursor += value[cursor].length;
    const nameStart = cursor;
    while (cursor < value.length) {
      const char = codePointCharAt(value, cursor);
      if (!isPlaceholderNameChar(char)) break;
      cursor += char.length;
    }
    const name = value.slice(nameStart, cursor);
    if (!name) continue;
    if (isBidiMarkerChar(value[cursor])) cursor += value[cursor].length;
    if (!isPlaceholderNameBoundary(value[cursor])) continue;
    const end = variableExpressionEnd(value, cursor);
    if (end == null) continue;
    expressions.push({
      from: start,
      name,
      source: value.slice(start, end + 1),
      to: end + 1,
    });
    index = end;
  }
  return expressions;
}

function variableExpressionEnd(value: string, start: number) {
  let quoted = false;
  for (let index = start; index < value.length; index += 1) {
    const char = value[index];
    if (char === "|" && !isEscapedAt(value, index)) quoted = !quoted;
    if (char === "}" && !quoted) return index;
  }
  return null;
}

function isEscapedAt(value: string, index: number) {
  let slashCount = 0;
  for (let cursor = index - 1; cursor >= 0 && value[cursor] === "\\"; cursor -= 1) {
    slashCount += 1;
  }
  return slashCount % 2 === 1;
}

function isBlockedBracedCompletionStart(value: string, index: number) {
  const previous = value[index - 1];
  if (previous === "$") return true;
  if (previous !== "{") return false;
  return value[index - 2] !== "{";
}

function isMf2Whitespace(char: string | undefined) {
  return char != null && /\s/u.test(char);
}

function isMf2SyntaxWhitespace(char: string | undefined) {
  return isMf2Whitespace(char) || isBidiMarkerChar(char);
}

function placeholderTokenNameStart(value: string, end = value.length) {
  let cursor = end;
  for (let previous = previousCodePointIndex(value, cursor); previous >= 0; previous = previousCodePointIndex(value, cursor)) {
    const char = value.slice(previous, cursor);
    if (!isPlaceholderNameChar(char)) break;
    cursor = previous;
  }
  return cursor;
}

function previousCodePointIsBidiMarker(value: string, index: number) {
  const previous = previousCodePointIndex(value, index);
  if (previous < 0) return null;
  return isBidiMarkerChar(value.slice(previous, index)) ? previous : null;
}

function previousCodePointIndex(value: string, index: number) {
  if (index <= 0) return -1;
  const previous = index - 1;
  const codeUnit = value.charCodeAt(previous);
  if (codeUnit >= 0xdc00 && codeUnit <= 0xdfff && previous > 0) {
    const before = value.charCodeAt(previous - 1);
    if (before >= 0xd800 && before <= 0xdbff) return previous - 1;
  }
  return previous;
}

function codePointCharAt(value: string, index: number) {
  const codePoint = value.codePointAt(index);
  if (codePoint == null) return "";
  return String.fromCodePoint(codePoint);
}

function isPlaceholderNameChar(char: string | undefined) {
  if (!char) return false;
  const codePoint = char.codePointAt(0);
  if (codePoint == null) return false;
  if (codePoint <= 0x7f) return isAsciiNameChar(codePoint);
  return codePoint >= 0xa1
    && codePoint <= 0x10fffd
    && !isBidiMarkerCodePoint(codePoint)
    && !isControlCodePoint(codePoint)
    && !isSurrogateCodePoint(codePoint)
    && !isMf2Whitespace(char)
    && !isNoncharacterCodePoint(codePoint);
}

function isPlaceholderNameBoundary(char: string | undefined) {
  return char == null || /\s|:|\}/u.test(char);
}

function selectorContractDiagnostics(sourceModel: EditorModel, targetModel: EditorModel) {
  const sourceSelectors = selectorContractFromModel(sourceModel);
  const targetSelectors = selectorContractFromModel(targetModel);
  const sourceByName = new Map(sourceSelectors.map((selector) => [selector.name, selector]));
  const targetByName = new Map(targetSelectors.map((selector) => [selector.name, selector]));
  const diagnostics: Array<EditorDiagnostic> = [];

  for (const selector of targetSelectors) {
    if (sourceByName.has(selector.name)) continue;
    diagnostics.push({
      code: "new-selector",
      message: `Target matches on $${selector.name}, which is not a source selector.`,
      severity: "error",
    });
  }

  for (const selector of sourceSelectors) {
    if (targetByName.has(selector.name)) continue;
    diagnostics.push({
      code: "missing-source-selector",
      message: `Target no longer matches on source selector $${selector.name}.`,
      severity: "error",
    });
  }

  const sameSelectorSet = sourceSelectors.length === targetSelectors.length
    && sourceSelectors.every((selector) => targetByName.has(selector.name));
  if (sameSelectorSet && selectorSignature(sourceSelectors) !== selectorSignature(targetSelectors)) {
    diagnostics.push({
      code: "selector-order-mismatch",
      message: `Target selector order is ${selectorListLabel(targetSelectors)}, but source order is ${selectorListLabel(sourceSelectors)}.`,
      severity: "error",
    });
  }

  for (const sourceSelector of sourceSelectors) {
    const targetSelector = targetByName.get(sourceSelector.name);
    if (!targetSelector || selectorAnnotationKey(sourceSelector) === selectorAnnotationKey(targetSelector)) continue;
    diagnostics.push({
      code: "selector-annotation-mismatch",
      message: `Target selector $${sourceSelector.name} uses ${selectorAnnotationLabel(targetSelector)}, but source uses ${selectorAnnotationLabel(sourceSelector)}.`,
      severity: "error",
    });
  }

  return diagnostics;
}

function variantPlaceholderDiagnostics(sourceModel: EditorModel, targetModel: EditorModel) {
  return pairedPatternsForDiagnostics(sourceModel, targetModel).flatMap(({ label, sourcePattern, targetPattern }) => {
    return missingPlaceholderNamesForActiveSource(sourceModel, targetModel, sourcePattern, targetPattern)
      .map((name) => ({
        code: "variant-missing-placeholder",
        formLabel: label,
        message: `Target form ${label} omits source placeholder {$${name}}; keep it omitted only when intentional.`,
        severity: "warning" as const,
      }));
  });
}

function markupContractDiagnostics(sourceModel: EditorModel, targetModel: EditorModel) {
  const sourceMarkup = markupShapesFromModel(sourceModel);
  const targetMarkup = markupShapesFromModel(targetModel);
  const diagnostics: Array<EditorDiagnostic> = [];

  for (const name of targetMarkup.keys()) {
    if (sourceMarkup.has(name)) continue;
    diagnostics.push({
      code: "new-markup",
      message: `Target uses {#${name}}, which is not in the source.`,
      severity: "error",
    });
  }

  for (const name of sourceMarkup.keys()) {
    if (targetMarkup.has(name)) continue;
    diagnostics.push({
      code: "missing-source-markup",
      message: `Target omits source markup {#${name}}; keep it omitted only when intentional.`,
      severity: "warning",
    });
  }

  for (const [name, sourceShape] of sourceMarkup.entries()) {
    const targetShape = targetMarkup.get(name);
    if (!targetShape || markupShapesEqual(sourceShape, targetShape)) continue;
    diagnostics.push({
      code: "markup-shape-mismatch",
      message: `Target markup {#${name}} has ${markupShapeLabel(targetShape)}, but source has ${markupShapeLabel(sourceShape)}.`,
      severity: "error",
    });
  }

  diagnostics.push(...markupPropDiagnostics(sourceMarkup, targetMarkup));
  return diagnostics;
}

function markupPropDiagnostics(
  sourceMarkup: Map<string, MarkupShape>,
  targetMarkup: Map<string, MarkupShape>,
) {
  const diagnostics: Array<EditorDiagnostic> = [];
  for (const [name, sourceShape] of sourceMarkup.entries()) {
    const targetShape = targetMarkup.get(name);
    if (!targetShape) continue;
    diagnostics.push(
      ...markupPropKindDiagnostics(name, "option", sourceShape.options, targetShape.options),
      ...markupPropKindDiagnostics(name, "attribute", sourceShape.attributes, targetShape.attributes),
    );
  }
  return diagnostics;
}

function markupPropKindDiagnostics(
  markupName: string,
  propKind: "attribute" | "option",
  sourceNames: Set<string>,
  targetNames: Set<string>,
) {
  const diagnostics: Array<EditorDiagnostic> = [];
  const displayKind = propKind === "attribute" ? "attribute" : "option";
  for (const name of targetNames) {
    if (sourceNames.has(name)) continue;
    diagnostics.push({
      code: `new-markup-${propKind}`,
      message: `Target markup {#${markupName}} adds ${displayKind} ${markupPropLabel(propKind, name)} that is not in the source.`,
      severity: "error",
    });
  }
  for (const name of sourceNames) {
    if (targetNames.has(name)) continue;
    diagnostics.push({
      code: `missing-source-markup-${propKind}`,
      message: `Target markup {#${markupName}} omits source ${displayKind} ${markupPropLabel(propKind, name)}.`,
      severity: propKind === "attribute" ? "warning" : "error",
    });
  }
  return diagnostics;
}

function markupShapesFromModel(model: EditorModel) {
  const runtime = model.rustModel as RuntimeModel | undefined;
  if (!runtime) return new Map<string, MarkupShape>();
  const shapes = new Map<string, MarkupShape>();
  const patterns = runtime.type === "select"
    ? (runtime.variants ?? []).map((variant) => variant.value ?? [])
    : [runtime.pattern ?? []];
  for (const pattern of patterns) {
    for (const part of pattern) {
      if (typeof part === "string" || part.type !== "markup" || !part.name) continue;
      addMarkupShapePart(shapes, part);
    }
  }
  return shapes;
}

function addMarkupShapePart(shapes: Map<string, MarkupShape>, part: Exclude<RuntimePart, string>) {
  if (!part.name) return;
  const shape = shapes.get(part.name) ?? {
    attributes: new Set<string>(),
    close: 0,
    open: 0,
    options: new Set<string>(),
    standalone: 0,
  };
  const kind = part.kind === "close" || part.kind === "standalone" ? part.kind : "open";
  shape[kind] += 1;
  for (const name of Object.keys(part.options ?? {})) shape.options.add(name);
  for (const name of Object.keys(part.attributes ?? {})) shape.attributes.add(name);
  shapes.set(part.name, shape);
}

function markupShapesEqual(left: MarkupShape, right: MarkupShape) {
  return MARKUP_KINDS.every((kind) => left[kind] === right[kind]);
}

function markupShapeLabel(shape: MarkupShape) {
  const parts = MARKUP_KINDS
    .filter((kind) => shape[kind] > 0)
    .map((kind) => `${kind} x${shape[kind]}`);
  return parts.length ? parts.join(", ") : "no markers";
}

function markupPropLabel(propKind: "attribute" | "option", name: string) {
  return propKind === "attribute" ? `@${name}` : name;
}

function variantContractDiagnostics(sourceModel: EditorModel, targetModel: EditorModel, locale: string) {
  if (sourceModel.type !== "select" || targetModel.type !== "select") return [];
  if (selectorSignature(selectorContractFromModel(sourceModel)) !== selectorSignature(selectorContractFromModel(targetModel))) {
    return [];
  }
  const targetSelectors = selectorContractFromModel(targetModel);
  const allowedSelectorValues = selectorValueAllowlistFromModels(sourceModel);
  const sourceVariants = variantContractsFromModel(sourceModel);
  const targetVariants = variantContractsFromModel(targetModel);
  const targetHasFallback = targetVariants.some((variant) => isAllWildcard(variant.keys));
  const sourceBySignature = new Map(sourceVariants.map((variant) => [variant.signature, variant]));
  const targetBySignature = new Map(targetVariants.map((variant) => [variant.signature, variant]));
  const diagnostics: Array<EditorDiagnostic> = [];

  for (const variant of sourceVariants) {
    if (targetBySignature.has(variant.signature) || nonFallbackVariantCovers(targetVariants, variant.keys)) continue;
    diagnostics.push({
      code: "missing-source-variant",
      formLabel: variant.label,
      message: `Target is missing source form ${variant.label}; fallback may still cover it.`,
      severity: "warning",
    });
  }

  for (const variant of targetVariants) {
    if (sourceBySignature.has(variant.signature) || nonFallbackVariantCovers(sourceVariants, variant.keys)) continue;
    const assessment = targetOnlyVariantAssessment(variant, targetSelectors, locale, allowedSelectorValues, targetHasFallback);
    if (assessment.status === "locale-recommended") continue;
    diagnostics.push({
      code: "target-only-variant",
      formLabel: variant.label,
      message: assessment.message,
      severity: "warning",
    });
  }

  diagnostics.push(...missingLocalePluralDiagnostics(targetModel, targetSelectors, locale));
  return diagnostics;
}

function targetVariantOrderDiagnostics(targetModel: EditorModel, locale: string) {
  if (targetModel.type !== "select") return [];
  return [
    ...overlappingNumericVariantDiagnostics(targetModel, locale),
    ...selectorPriorityVariantDiagnostics(targetModel, locale),
  ];
}

function overlappingNumericVariantDiagnostics(model: SelectEditorModel, locale: string) {
  const selectors = selectorContractFromModel(model);
  const diagnostics: Array<EditorDiagnostic> = [];
  const seen = new Set<string>();
  for (const overlap of numericVariantOverlaps(model, selectors, locale)) {
    const signature = [
      overlap.selector.name,
      overlap.exact,
      overlap.category,
      variantSignature(overlap.left.keys),
      variantSignature(overlap.right.keys),
    ].join("\u0000");
    if (seen.has(signature)) continue;
    seen.add(signature);
    diagnostics.push({
      code: "overlapping-numeric-variant",
      formLabels: [formLabel(overlap.left.keys, model.selectors), formLabel(overlap.right.keys, model.selectors)],
      message: `Target has both exact $${overlap.selector.name}: ${overlap.exact} and CLDR ${overlap.kind} category ${overlap.category} forms with the same surrounding keys; if both match, the earlier row wins.`,
      severity: "warning",
    });
  }
  return diagnostics;
}

function selectorPriorityVariantDiagnostics(model: SelectEditorModel, locale: string) {
  const selectors = selectorContractFromModel(model);
  const diagnostics: Array<EditorDiagnostic> = [];
  const seen = new Set<string>();
  for (const overlap of selectorPriorityVariantOverlaps(model, selectors, locale)) {
    const signature = [
      variantSignature(overlap.left.keys),
      variantSignature(overlap.right.keys),
      overlap.prioritySelector.name,
    ].join("\u0000");
    if (seen.has(signature)) continue;
    seen.add(signature);
    diagnostics.push({
      code: "selector-priority-overlap",
      formLabels: [formLabel(overlap.left.keys, model.selectors), formLabel(overlap.right.keys, model.selectors)],
      message: `Target forms ${formLabel(overlap.left.keys, model.selectors)} and ${formLabel(overlap.right.keys, model.selectors)} can both match; $${overlap.prioritySelector.name} is the first differing selector, so ${formLabel(overlap.winner.keys, model.selectors)} wins.`,
      severity: "warning",
    });
  }
  return diagnostics;
}

function numericVariantOverlaps(
  model: SelectEditorModel,
  selectors: Array<SelectorContract>,
  locale: string,
) {
  const overlaps: Array<{
    category: string;
    exact: string;
    kind: "cardinal" | "ordinal";
    left: EditorVariant;
    right: EditorVariant;
    selector: SelectorContract;
  }> = [];
  for (let leftIndex = 0; leftIndex < model.variants.length; leftIndex += 1) {
    for (let rightIndex = leftIndex + 1; rightIndex < model.variants.length; rightIndex += 1) {
      const left = model.variants[leftIndex];
      const right = model.variants[rightIndex];
      for (let selectorIndex = 0; selectorIndex < selectors.length; selectorIndex += 1) {
        const overlap = numericCategoryOverlap(left.keys[selectorIndex], right.keys[selectorIndex], selectors[selectorIndex], locale);
        if (!overlap || !sameSurroundingVariantKeys(left.keys, right.keys, selectorIndex)) continue;
        overlaps.push({
          ...overlap,
          left,
          right,
          selector: selectors[selectorIndex],
        });
      }
    }
  }
  return overlaps;
}

function selectorPriorityVariantOverlaps(
  model: SelectEditorModel,
  selectors: Array<SelectorContract>,
  locale: string,
) {
  const overlaps: Array<{
    left: EditorVariant;
    prioritySelector: SelectorContract;
    right: EditorVariant;
    winner: EditorVariant;
  }> = [];
  for (let leftIndex = 0; leftIndex < model.variants.length; leftIndex += 1) {
    for (let rightIndex = leftIndex + 1; rightIndex < model.variants.length; rightIndex += 1) {
      const left = model.variants[leftIndex];
      const right = model.variants[rightIndex];
      if (!variantKeysCanOverlap(left.keys, right.keys, selectors, locale)) continue;
      const leftRank = variantSpecificityRank(left.keys);
      const rightRank = variantSpecificityRank(right.keys);
      if (!hasMixedSpecificity(leftRank, rightRank)) continue;
      const comparison = compareVariantSpecificityRank(leftRank, rightRank);
      if (comparison === 0) continue;
      const priorityIndex = firstDifferingIndex(leftRank, rightRank);
      const prioritySelector = selectors[priorityIndex];
      if (!prioritySelector) continue;
      overlaps.push({
        left,
        prioritySelector,
        right,
        winner: comparison > 0 ? left : right,
      });
    }
  }
  return overlaps;
}

function variantKeysCanOverlap(
  leftKeys: Array<string>,
  rightKeys: Array<string>,
  selectors: Array<SelectorContract>,
  locale: string,
) {
  const length = Math.max(leftKeys.length, rightKeys.length, selectors.length);
  for (let index = 0; index < length; index += 1) {
    const left = String(leftKeys[index] ?? "*");
    const right = String(rightKeys[index] ?? "*");
    if (left === "*" || right === "*" || left === right) continue;
    if (numericCategoryOverlap(leftKeys[index], rightKeys[index], selectors[index], locale)) continue;
    return false;
  }
  return true;
}

function numericCategoryOverlap(
  leftKey: string | undefined,
  rightKey: string | undefined,
  selector: SelectorContract | undefined,
  locale: string,
) {
  const left = String(leftKey ?? "*");
  const right = String(rightKey ?? "*");
  const leftCategory = categoryForExactNumericKey(left, selector, locale);
  if (leftCategory && leftCategory.category === right) return { exact: left, ...leftCategory };
  const rightCategory = categoryForExactNumericKey(right, selector, locale);
  if (rightCategory && rightCategory.category === left) return { exact: right, ...rightCategory };
  return null;
}

function categoryForExactNumericKey(
  key: string,
  selector: SelectorContract | undefined,
  locale: string,
) {
  const kind = selectorContractPluralKind(selector);
  if (!kind || !isNumericKey(key)) return null;
  const value = Number(key);
  if (!Number.isFinite(value)) return null;
  const category = pluralRulesForLocale(locale, kind).select(value);
  return { category, kind };
}

function sameSurroundingVariantKeys(
  leftKeys: Array<string> = [],
  rightKeys: Array<string> = [],
  exceptIndex: number,
) {
  const length = Math.max(leftKeys.length, rightKeys.length);
  for (let index = 0; index < length; index += 1) {
    if (index === exceptIndex) continue;
    if (String(leftKeys[index] ?? "*") !== String(rightKeys[index] ?? "*")) return false;
  }
  return true;
}

function hasMixedSpecificity(leftRank: Array<number>, rightRank: Array<number>) {
  let leftMoreSpecific = false;
  let rightMoreSpecific = false;
  const length = Math.max(leftRank.length, rightRank.length);
  for (let index = 0; index < length; index += 1) {
    const delta = (leftRank[index] ?? 0) - (rightRank[index] ?? 0);
    if (delta > 0) leftMoreSpecific = true;
    if (delta < 0) rightMoreSpecific = true;
  }
  return leftMoreSpecific && rightMoreSpecific;
}

function firstDifferingIndex(leftRank: Array<number>, rightRank: Array<number>) {
  const length = Math.max(leftRank.length, rightRank.length);
  for (let index = 0; index < length; index += 1) {
    if ((leftRank[index] ?? 0) !== (rightRank[index] ?? 0)) return index;
  }
  return 0;
}

function variantContractsFromModel(model: SelectEditorModel) {
  return model.variants.map((variant) => {
    const keys = [...(variant.keys ?? [])];
    return {
      keys,
      label: formLabel(keys, model.selectors),
      signature: variantSignature(keys),
    };
  });
}

function nonFallbackVariantCovers(variants: Array<{ keys: Array<string> }>, keys: Array<string>) {
  return variants.some((variant) => !isAllWildcard(variant.keys) && variantMatchesKeys(variant.keys, keys));
}

function isAllWildcard(keys: Array<string> = []) {
  return keys.length > 0 && keys.every((key) => key === "*");
}

function targetOnlyVariantAssessment(
  variant: { keys: Array<string>; label: string },
  selectors: Array<SelectorContract>,
  locale: string,
  allowedSelectorValues: Map<string, Set<string>>,
  targetHasFallback: boolean,
) {
  const categoryDetails = variant.keys
    .map((key, index) => targetOnlyPluralCategoryDetail(key, selectors[index], locale))
    .filter((detail): detail is NonNullable<typeof detail> => detail != null);
  const customKeys = variant.keys.filter((key, index) =>
    key !== "*"
    && !targetOnlyPluralCategoryDetail(key, selectors[index], locale)
    && !isAllowedSelectorContextValue(key, selectors[index], allowedSelectorValues),
  );
  if (customKeys.length) {
    return {
      message: `Target adds form ${variant.label}; fixed or custom selector values are not inferred from CLDR plural rules, so confirm this row is intentional.`,
      status: "review" as const,
    };
  }
  if (!categoryDetails.length) {
    return {
      message: `Target adds form ${variant.label}; confirm this row is intentional.`,
      status: "review" as const,
    };
  }
  if (targetHasFallback && isGenericOtherLocaleVariant(variant.keys, categoryDetails)) {
    return {
      message: `Target adds form ${variant.label}, but fallback already covers the locale ${categoryDetails[0].kind} category other; confirm this row is intentional.`,
      status: "review" as const,
    };
  }
  const unsupported = categoryDetails.filter((detail) => !detail.supported);
  if (!unsupported.length) return { message: "", status: "locale-recommended" as const };
  const detailText = unsupported
    .map((detail) => {
      const categories = detail.categories.length ? detail.categories.join(", ") : "other";
      return `$${detail.selector}: ${detail.category} is not a ${detail.kind} category for ${locale}; expected ${categories}`;
    })
    .join("; ");
  return {
    message: `Target adds form ${variant.label}, but ${detailText}.`,
    status: "review" as const,
  };
}

function isGenericOtherLocaleVariant(
  keys: Array<string>,
  categoryDetails: Array<NonNullable<ReturnType<typeof targetOnlyPluralCategoryDetail>>>,
) {
  return categoryDetails.length === 1
    && categoryDetails[0].category === "other"
    && keys.every((key) => key === "*" || key === "other");
}

function targetOnlyPluralCategoryDetail(key: string, selector: SelectorContract | undefined, locale: string) {
  const category = String(key ?? "*");
  const kind = selectorContractPluralKind(selector);
  if (!kind || !PLURAL_CATEGORIES.includes(category)) return null;
  const categories = pluralCategorySetForLocale(locale, kind);
  return {
    categories,
    category,
    kind,
    selector: selector?.name ?? "",
    supported: categories.includes(category),
  };
}

function isAllowedSelectorContextValue(
  value: string,
  selector: SelectorContract | undefined,
  allowedSelectorValues: Map<string, Set<string>>,
) {
  if (!selector || selectorContractPluralKind(selector)) return false;
  return allowedSelectorValues.get(selector.name)?.has(String(value)) ?? false;
}

function selectorValueAllowlistFromModels(...models: Array<EditorModel | null>) {
  const values = new Map<string, Set<string>>();
  for (const model of models) {
    if (model?.type !== "select") continue;
    model.selectors.forEach((name, selectorIndex) => {
      if (!name) return;
      const set = values.get(name) ?? new Set<string>();
      for (const variant of model.variants) {
        const key = String(variant.keys[selectorIndex] ?? "*");
        if (key !== "*") set.add(key);
      }
      values.set(name, set);
    });
  }
  return values;
}

function missingLocalePluralDiagnostics(
  targetModel: SelectEditorModel,
  selectors: Array<SelectorContract>,
  locale: string,
) {
  const diagnostics: Array<EditorDiagnostic> = [];
  const fallbackCoversOther = targetModel.variants.some((variant) => variant.keys.every((key) => key === "*"));
  selectors.forEach((selector, selectorIndex) => {
    const kind = selectorContractPluralKind(selector);
    if (!kind) return;
    const existingSignatures = new Set(targetModel.variants.map((variant) => variantSignature(variant.keys)));
    const missing = pluralCategorySetForLocale(locale, kind)
      .filter((category) => {
        const keys = targetModel.selectors.map((_name, index) => (index === selectorIndex ? category : "*"));
        const exactKeys = exactNumericKeysForSelectorIndex(targetModel, selectorIndex, keys);
        return (category !== "other" || !fallbackCoversOther)
          && !existingSignatures.has(variantSignature(keys))
          && sampleForPluralCategory(category, locale, exactKeys, kind) != null;
      });
    for (const category of missing) {
      diagnostics.push({
        code: "missing-locale-plural-variant",
        message: missingLocalePluralMessage(locale, kind, category, selector.name, fallbackCoversOther),
        severity: "warning",
      });
    }
  });
  return diagnostics;
}

function missingLocalePluralMessage(
  locale: string,
  kind: "cardinal" | "ordinal",
  category: string,
  selectorName: string,
  fallbackCoversOther: boolean,
) {
  const prefix = `Target locale ${locale} has CLDR ${kind} category ${category} for $${selectorName}, but the target has no ${selectorName}: ${category} form`;
  return fallbackCoversOther ? `${prefix}; fallback may still cover it.` : `${prefix}; add this form or a fallback row.`;
}

function pairedPatternsForDiagnostics(sourceModel: EditorModel, targetModel: EditorModel) {
  if (targetModel.type === "select") {
    return targetModel.variants
      .map((variant) => {
        const sourceVariant = sourceModel.type === "select" ? bestVariantForKeys(sourceModel, variant.keys) : null;
        if (!sourceVariant) return null;
        return {
          label: formLabel(variant.keys, targetModel.selectors),
          sourcePattern: sourceVariant.value,
          targetPattern: variant.value,
        };
      })
      .filter((item): item is { label: string; sourcePattern: string; targetPattern: string } => item != null);
  }
  return [{
    label: "message",
    sourcePattern: sourceModel.type === "select" ? sourceLiteralPreview(sourceModel.source) : sourceModel.pattern,
    targetPattern: targetModel.pattern,
  }];
}

function placeholderRequirementsInPattern(pattern: string) {
  return [...placeholderCountsInPattern(pattern).entries()].map(([name, count]) => ({ count, name }));
}

function placeholderCountsInPattern(pattern: string) {
  const counts = new Map<string, number>();
  for (const name of placeholderNamesInPattern(pattern)) {
    counts.set(name, (counts.get(name) ?? 0) + 1);
  }
  return counts;
}

function placeholderNamesInModelPatterns(model: EditorModel) {
  const names = new Set<string>();
  const patterns = model.type === "select" ? model.variants.map((variant) => variant.value) : [model.pattern];
  for (const pattern of patterns) {
    placeholderNamesInPattern(pattern).forEach((name) => names.add(name));
  }
  return names;
}

function placeholderInsertionNameSet(model: EditorModel) {
  const renderedNames = placeholderNamesInModelPatterns(model);
  const names = new Set(renderedNames);
  const localDependencyNames = localDependencyNamesForRenderedLocals(model);
  const optionNames = optionVariableNamesForInsertion(model, localDependencyNames, renderedNames);
  for (const name of optionNames) names.add(name);
  for (const declaration of model.declarations ?? []) {
    if (declaration.name) names.add(declaration.name);
  }
  for (const name of localDependencyNames) {
    if (!renderedNames.has(name) && !optionNames.has(name)) names.delete(name);
  }
  const selectorNames = selectorNamesFromModel(model);
  for (const name of selectorNames) {
    if (!renderedNames.has(name) && !optionNames.has(name)) names.delete(name);
  }
  return names;
}

function targetPlaceholderInsertionNameSet(model: EditorModel) {
  const renderedNames = placeholderNamesInModelPatterns(model);
  const names = new Set(renderedNames);
  const localDependencyNames = localDependencyNamesForDeclaredLocals(model);
  const optionNames = optionVariableNamesInModelPatterns(model);
  for (const name of optionNames) names.add(name);
  for (const declaration of model.declarations ?? []) {
    if (declaration.name) names.add(declaration.name);
  }
  for (const name of localDependencyNames) {
    if (!renderedNames.has(name) && !optionNames.has(name)) names.delete(name);
  }
  const selectorNames = selectorNamesFromModel(model);
  for (const name of selectorNames) {
    if (!renderedNames.has(name) && !optionNames.has(name)) names.delete(name);
  }
  return names;
}

function targetSourceCoverageNameSet(model: EditorModel) {
  const renderedNames = placeholderNamesInModelPatterns(model);
  const names = targetPlaceholderInsertionNameSet(model);
  for (const declaration of model.declarations ?? []) {
    if (declaration.type === "local" && declaration.name && !renderedNames.has(declaration.name)) {
      names.delete(declaration.name);
    }
  }
  return names;
}

function activePlaceholderInsertionNameSet(model: EditorModel, sourcePattern: string) {
  const activeRenderedNames = new Set(placeholderNamesInPattern(sourcePattern));
  const renderedNames = placeholderNamesInModelPatterns(model);
  const names = new Set(activeRenderedNames);
  const activeLocalDependencyNames = localDependencyNamesForActiveSource(model, sourcePattern);
  const activeOptionNames = optionVariableNamesForActiveSource(model, sourcePattern);
  const selectorNames = selectorNamesFromModel(model);
  for (const name of activeOptionNames) names.add(name);
  for (const declaration of model.declarations ?? []) {
    if (!declaration.name) continue;
    if (activeLocalDependencyNames.has(declaration.name) && !activeRenderedNames.has(declaration.name) && !activeOptionNames.has(declaration.name)) continue;
    if (renderedNames.has(declaration.name) && !activeRenderedNames.has(declaration.name) && !activeOptionNames.has(declaration.name)) continue;
    if (selectorNames.has(declaration.name) && !activeRenderedNames.has(declaration.name) && !activeOptionNames.has(declaration.name)) continue;
    names.add(declaration.name);
  }
  return names;
}

function optionVariableNamesForActiveSource(model: EditorModel, sourcePattern: string) {
  const names = variableReferenceNamesInSource(sourcePattern);
  const activeLocalDependencyNames = localDependencyNamesForActiveSource(model, sourcePattern);
  const activeRenderedNames = new Set(placeholderNamesInPattern(sourcePattern));
  const renderedNames = placeholderNamesInModelPatterns(model);
  for (const declaration of model.declarations ?? []) {
    if (!declaration.name) continue;
    if (declaration.type === "local") continue;
    if (activeLocalDependencyNames.has(declaration.name) && !activeRenderedNames.has(declaration.name)) continue;
    if (!activeRenderedNames.has(declaration.name) && renderedNames.has(declaration.name)) continue;
    for (const name of variableReferenceNamesInSource(declarationToSource(declaration))) {
      if (name !== declaration.name && name !== declaration.argName) names.add(name);
    }
  }
  return names;
}

function optionVariableOwnerNamesForActiveSource(model: EditorModel, sourcePattern: string) {
  const owners = optionVariableOwnerNamesInPatternSource(sourcePattern);
  const activeLocalDependencyNames = localDependencyNamesForActiveSource(model, sourcePattern);
  const activeRenderedNames = new Set(placeholderNamesInPattern(sourcePattern));
  const renderedNames = placeholderNamesInModelPatterns(model);
  for (const declaration of model.declarations ?? []) {
    if (!declaration.name) continue;
    if (declaration.type === "local") continue;
    if (activeLocalDependencyNames.has(declaration.name) && !activeRenderedNames.has(declaration.name)) continue;
    if (!activeRenderedNames.has(declaration.name) && renderedNames.has(declaration.name)) continue;
    for (const name of variableReferenceNamesInSource(declarationToSource(declaration))) {
      if (name === declaration.name || name === declaration.argName) continue;
      const ownerNames = owners.get(name) ?? new Set<string>();
      ownerNames.add(declaration.name);
      owners.set(name, ownerNames);
    }
  }
  return owners;
}

function localDependencyNamesForActiveSource(model: EditorModel, sourcePattern: string) {
  return localDependencyNamesForRenderedNames(model, new Set(placeholderNamesInPattern(sourcePattern)));
}

function localDependencyNamesForRenderedLocals(model: EditorModel) {
  return localDependencyNamesForRenderedNames(model, placeholderNamesInModelPatterns(model));
}

function localDependencyNamesForDeclaredLocals(model: EditorModel) {
  const localNames = new Set(
    (model.declarations ?? [])
      .filter((declaration) => declaration.type === "local")
      .map((declaration) => declaration.name)
      .filter(Boolean),
  );
  return localDependencyNamesForRenderedNames(model, localNames);
}

function localDependencyNamesForRenderedNames(model: EditorModel, renderedNames: Set<string>) {
  const names = new Set<string>();
  const declarationsByName = new Map((model.declarations ?? []).map((declaration) => [declaration.name, declaration]));
  const queue = (model.declarations ?? []).filter((declaration) => {
    return declaration.type === "local" && declaration.name && renderedNames.has(declaration.name);
  });
  for (let index = 0; index < queue.length; index += 1) {
    const declaration = queue[index];
    for (const name of variableReferenceNamesInSource(declarationToSource(declaration))) {
      if (name === declaration.name || names.has(name)) continue;
      names.add(name);
      const dependencyDeclaration = declarationsByName.get(name);
      if (dependencyDeclaration) queue.push(dependencyDeclaration);
    }
  }
  return names;
}

function optionVariableNamesForInsertion(
  model: EditorModel,
  localDependencyNames: Set<string>,
  renderedNames: Set<string>,
) {
  const runtime = model.rustModel as RuntimeModel | undefined;
  const variables = new Set<string>();
  if (!runtime) return variables;
  for (const declaration of runtime.declarations ?? []) {
    if (declaration.type === "local") continue;
    if (declaration.name && localDependencyNames.has(declaration.name) && !renderedNames.has(declaration.name)) continue;
    addOptionVariablesFromExpression(declaration.value, variables);
  }
  for (const name of optionVariableNamesInRuntimePatterns(runtime)) variables.add(name);
  return variables;
}

function optionVariableNamesInModelPatterns(model: EditorModel) {
  const runtime = model.rustModel as RuntimeModel | undefined;
  return runtime ? optionVariableNamesInRuntimePatterns(runtime) : new Set<string>();
}

function optionVariableNamesInRuntimePatterns(runtime: RuntimeModel) {
  const variables = new Set<string>();
  const patterns = runtime.type === "select"
    ? (runtime.variants ?? []).map((variant) => variant.value ?? [])
    : [runtime.pattern ?? []];
  for (const pattern of patterns) {
    for (const part of pattern) {
      if (typeof part === "string") continue;
      if (part?.type === "expression") addOptionVariablesFromExpression(part, variables);
      if (part?.type === "markup") {
        addVariablesFromArgs(part.options, variables);
        addVariablesFromArgs(part.attributes, variables);
      }
    }
  }
  return variables;
}

function variableReferenceNamesInSource(source: string) {
  const names = new Set<string>();
  let quoted = false;
  const value = String(source ?? "");
  for (let index = 0; index < value.length; index += 1) {
    const char = value[index];
    if (char === "|" && !isEscapedAt(value, index)) {
      quoted = !quoted;
      continue;
    }
    if (quoted || char !== "$" || isEscapedAt(value, index)) continue;
    const name = variableNameAfterDollar(value, index);
    if (name) names.add(name);
  }
  return names;
}

function selectorContractFromModel(model: EditorModel): Array<SelectorContract> {
  if (model.type !== "select") return [];
  return model.selectors
    .map((name) => {
      const declaration = model.declarations.find((item) => item.name === name);
      return {
        function: declaration?.function ?? "",
        name,
        optionText: declaration?.optionText ?? "",
      };
    })
    .filter((selector) => selector.name);
}

function selectorNamesFromModel(model: EditorModel) {
  return new Set(selectorContractFromModel(model).map((selector) => selector.name));
}

function markupNamesFromModel(model: EditorModel) {
  return new Set(markupShapesFromModel(model).keys());
}

function markupNamesInPatternSource(pattern: string) {
  const names = new Set<string>();
  for (const part of runtimePartsFromPatternSource(pattern)) {
    if (typeof part !== "string" && part.type === "markup" && part.name) names.add(part.name);
  }
  return names;
}

function optionVariableNamesFromModel(model: EditorModel | null) {
  const runtime = model?.rustModel as RuntimeModel | undefined;
  const variables = new Set<string>();
  if (!runtime) return variables;
  for (const declaration of runtime.declarations ?? []) {
    addOptionVariablesFromExpression(declaration.value, variables);
  }
  const patterns = runtime.type === "select"
    ? (runtime.variants ?? []).map((variant) => variant.value ?? [])
    : [runtime.pattern ?? []];
  for (const pattern of patterns) {
    for (const part of pattern) {
      if (typeof part === "string") continue;
      if (part?.type === "expression") addOptionVariablesFromExpression(part, variables);
      if (part?.type === "markup") {
        addVariablesFromArgs(part.options, variables);
        addVariablesFromArgs(part.attributes, variables);
      }
    }
  }
  return variables;
}

function optionVariableNamesInPatternSource(pattern: string) {
  const variables = new Set<string>();
  addOptionVariablesFromPatternParts(runtimePartsFromPatternSource(pattern), variables);
  return variables;
}

function optionVariableOwnerNamesFromModel(model: EditorModel | null) {
  const runtime = model?.rustModel as RuntimeModel | undefined;
  const owners = new Map<string, Set<string>>();
  if (!runtime) return owners;
  for (const declaration of runtime.declarations ?? []) {
    addOptionVariableOwnersFromExpression(declaration.value, declaration.name ?? "", owners);
  }
  const patterns = runtime.type === "select"
    ? (runtime.variants ?? []).map((variant) => variant.value ?? [])
    : [runtime.pattern ?? []];
  for (const pattern of patterns) {
    for (const part of pattern) {
      if (typeof part === "string") continue;
      if (part?.type === "expression") addOptionVariableOwnersFromExpression(part, part.arg?.name ?? "", owners);
      if (part?.type === "markup") {
        const ownerName = markupOwnerName(part.name ?? "");
        addVariableOwnersFromArgs(part.options, ownerName, owners);
        addVariableOwnersFromArgs(part.attributes, ownerName, owners);
      }
    }
  }
  return owners;
}

function optionVariableOwnerNamesInPatternSource(pattern: string) {
  const owners = new Map<string, Set<string>>();
  addOptionVariableOwnersFromPatternParts(runtimePartsFromPatternSource(pattern), owners);
  return owners;
}

function optionVariableOwnerIsCovered(
  ownerNames: Set<string> | undefined,
  targetRenderedNames: Set<string>,
  targetSelectorNames: Set<string>,
  targetMarkupNames: Set<string>,
) {
  if (!ownerNames?.size) return true;
  for (const ownerName of ownerNames) {
    const markupName = markupOwnerMarkupName(ownerName);
    if (
      !ownerName
      || targetRenderedNames.has(ownerName)
      || targetSelectorNames.has(ownerName)
      || (markupName && targetMarkupNames.has(markupName))
    ) return true;
  }
  return false;
}

function optionVariableOwnerIsSourceCovered(
  ownerNames: Set<string> | undefined,
  sourceInsertionNames: Set<string>,
  sourceSelectorNames: Set<string>,
  sourceMarkupNames: Set<string>,
) {
  if (!ownerNames?.size) return true;
  for (const ownerName of ownerNames) {
    const markupName = markupOwnerMarkupName(ownerName);
    if (
      !ownerName
      || sourceInsertionNames.has(ownerName)
      || sourceSelectorNames.has(ownerName)
      || (markupName && sourceMarkupNames.has(markupName))
    ) return true;
  }
  return false;
}

function markupOwnerName(markupName: string) {
  return markupName ? `#${markupName}` : "";
}

function markupOwnerMarkupName(ownerName: string) {
  return ownerName.startsWith("#") ? ownerName.slice(1) : "";
}

function addOptionVariablesFromExpression(
  expression: RuntimePart | RuntimeDeclaration["value"] | undefined,
  variables: Set<string>,
) {
  if (!expression || typeof expression === "string") return;
  addVariablesFromArgs(expression.function?.options, variables);
  addVariablesFromArgs(expression.attributes, variables);
}

function addOptionVariablesFromPatternParts(pattern: Array<RuntimePart>, variables: Set<string>) {
  for (const part of pattern) {
    if (typeof part === "string") continue;
    if (part?.type === "expression") addOptionVariablesFromExpression(part, variables);
    if (part?.type === "markup") {
      addVariablesFromArgs(part.options, variables);
      addVariablesFromArgs(part.attributes, variables);
    }
  }
}

function addOptionVariableOwnersFromExpression(
  expression: RuntimePart | RuntimeDeclaration["value"] | undefined,
  ownerName: string,
  owners: Map<string, Set<string>>,
) {
  if (!expression || typeof expression === "string") return;
  addVariableOwnersFromArgs(expression.function?.options, ownerName, owners);
  addVariableOwnersFromArgs(expression.attributes, ownerName, owners);
}

function addOptionVariableOwnersFromPatternParts(
  pattern: Array<RuntimePart>,
  owners: Map<string, Set<string>>,
) {
  for (const part of pattern) {
    if (typeof part === "string") continue;
    if (part?.type === "expression") addOptionVariableOwnersFromExpression(part, part.arg?.name ?? "", owners);
    if (part?.type === "markup") {
      const ownerName = markupOwnerName(part.name ?? "");
      addVariableOwnersFromArgs(part.options, ownerName, owners);
      addVariableOwnersFromArgs(part.attributes, ownerName, owners);
    }
  }
}

function addVariableOwnersFromArgs(
  args: Record<string, RuntimeArg> | undefined,
  ownerName: string,
  owners: Map<string, Set<string>>,
) {
  for (const arg of Object.values(args ?? {})) {
    if (arg?.type !== "variable" || !arg.name) continue;
    const ownerNames = owners.get(arg.name) ?? new Set<string>();
    ownerNames.add(ownerName);
    owners.set(arg.name, ownerNames);
  }
}

function runtimePartsFromPatternSource(pattern: string) {
  const parsed = parseToModel(`{{${pattern}}}`);
  const runtime = parsed.model as RuntimeModel | undefined;
  return parsed.diagnostics?.length || !runtime || runtime.type !== "message" ? [] : runtime.pattern ?? [];
}

function selectorSignature(selectors: Array<{ name: string }>) {
  return selectors.map((selector) => selector.name).join("\u0000");
}

function selectorContractSignature(selectors: Array<{ function: string; name: string; optionText: string }>) {
  return selectors
    .map((selector) => `${selector.name}\u0001${selectorAnnotationKey(selector)}`)
    .join("\u0000");
}

function selectorListLabel(selectors: Array<{ name: string }>) {
  return selectors.map((selector) => `$${selector.name}`).join(", ") || "none";
}

function selectorAnnotationKey(selector: { function: string; optionText: string }) {
  return `${selector.function}\u0000${selector.optionText}`;
}

function selectorAnnotationLabel(selector: { function: string; optionText: string }) {
  const annotation = selector.function ? `:${selector.function}` : "no annotation";
  return selector.optionText ? `${annotation} ${selector.optionText}` : annotation;
}

export function localeDirection(locale: string) {
  try {
    const maximized = new Intl.Locale(locale).maximize();
    const textInfo = (maximized as Intl.Locale & { textInfo?: { direction?: "ltr" | "rtl" } }).textInfo;
    if (textInfo?.direction) return textInfo.direction;
    if (RTL_SCRIPTS.has(maximized.script ?? "")) return "rtl";
  } catch {
    // Fall through to the compact language/script map.
  }
  const [language, script] = String(locale ?? "").split(/[-_]/u);
  return RTL_SCRIPTS.has(script ?? "") || RTL_LANGUAGES.has(language?.toLowerCase() ?? "") ? "rtl" : "ltr";
}

export function formLabel(keys: Array<string> = [], selectors: Array<string> = []) {
  if (!keys.length) return "Message";
  return keys.map((key, index) => key === "*" ? `${selectors[index]}: fallback` : `${selectors[index]}: ${key}`).join(" / ");
}

export function sourceVariantForTargetKeys(sourceModel: EditorModel | null, targetKeys: Array<string> = []) {
  if (!sourceModel) return null;
  if (sourceModel.type !== "select") return { keys: [], value: sourceModel.pattern };
  return bestVariantForKeys(sourceModel, targetKeys);
}

export function sourceVariantForTargetModel(
  sourceModel: EditorModel | null,
  targetModel: EditorModel | null,
  targetKeys: Array<string> = [],
) {
  if (!sourceModel) return null;
  if (sourceModel.type !== "select") return { keys: [], value: sourceModel.pattern };
  if (targetModel?.type !== "select") return null;
  if (selectorContractSignature(selectorContractFromModel(sourceModel)) !== selectorContractSignature(selectorContractFromModel(targetModel))) {
    return null;
  }
  return bestVariantForKeys(sourceModel, targetKeys);
}

export function sourceFormLabelForTargetKeys(sourceModel: EditorModel | null, targetKeys: Array<string> = []) {
  if (!sourceModel || sourceModel.type !== "select") return "Message";
  const sourceVariant = sourceVariantForTargetKeys(sourceModel, targetKeys);
  return sourceVariant ? formLabel(sourceVariant.keys, sourceModel.selectors) : "No matching source form";
}

export function sourceFormLabelForTargetModel(
  sourceModel: EditorModel | null,
  targetModel: EditorModel | null,
  targetKeys: Array<string> = [],
) {
  if (!sourceModel || sourceModel.type !== "select") return "Message";
  const sourceVariant = sourceVariantForTargetModel(sourceModel, targetModel, targetKeys);
  return sourceVariant ? formLabel(sourceVariant.keys, sourceModel.selectors) : "No matching source form";
}

export function bestVariantForKeys(model: EditorModel | null, keys: Array<string> = []) {
  if (model?.type !== "select") return null;
  const candidates = model.variants
    .filter((variant) => variantMatchesKeys(variant.keys, keys))
    .sort((left, right) => compareVariantSpecificityRank(
      variantSpecificityRank(right.keys),
      variantSpecificityRank(left.keys),
    ));
  return candidates[0] ?? null;
}

export function localePluralRowSuggestions(model: EditorModel | null, locale: string) {
  if (model?.type !== "select") return [];
  const suggestions: Array<LocalePluralRowSuggestion> = [];
  const fallbackCoversOther = model.variants.some((variant) => variant.keys.every((key) => key === "*"));
  model.selectors.forEach((selector, selectorIndex) => {
    const kind = selectorPluralKind(model, selector);
    if (!kind) return;
    const existingSignatures = new Set(model.variants.map((variant) => variantSignature(variant.keys)));
    for (const category of pluralCategorySetForLocale(locale, kind)) {
      const keys = model.selectors.map((_name, index) => (index === selectorIndex ? category : "*"));
      const exactKeys = exactNumericKeysForSelectorIndex(model, selectorIndex, keys);
      const sample = sampleForPluralCategory(category, locale, exactKeys, kind);
      if (sample == null) continue;
      if (existingSignatures.has(variantSignature(keys))) continue;
      if (category === "other" && fallbackCoversOther) continue;
      suggestions.push({
        category,
        keys,
        kind,
        label: formLabel(keys, model.selectors),
        sample,
        selector,
      });
    }
  });
  return suggestions;
}

export function addLocalePluralRows(
  model: EditorModel | null,
  suggestions: Array<LocalePluralRowSuggestion>,
  sourceModel: EditorModel | null,
) {
  if (model?.type !== "select" || !suggestions.length) return null;
  const next = cloneModel(model) as SelectEditorModel;
  const fallback = next.variants.find((variant) => variant.keys.every((key) => key === "*"));
  const value = generatedLocaleRowValue(fallback, sourceModel);
  let insertIndex = fallback ? next.variants.indexOf(fallback) : next.variants.length;
  let firstInsertedIndex: number | null = null;
  const existing = new Set(next.variants.map((variant) => variant.keys.join("\u001F")));
  for (const suggestion of suggestions) {
    const signature = suggestion.keys.join("\u001F");
    if (existing.has(signature)) continue;
    firstInsertedIndex ??= insertIndex;
    next.variants.splice(insertIndex, 0, { keys: [...suggestion.keys], value });
    existing.add(signature);
    insertIndex++;
  }
  if (firstInsertedIndex == null) return null;
  return {
    activeIndex: firstInsertedIndex,
    model: next,
  };
}

export function invalidLocalePluralRows(model: EditorModel | null, locale: string) {
  if (model?.type !== "select") return [];
  const rows: Array<InvalidLocalePluralRow> = [];
  model.selectors.forEach((selector, selectorIndex) => {
    const kind = selectorPluralKind(model, selector);
    if (!kind) return;
    const allowed = new Set(pluralCategorySetForLocale(locale, kind));
    model.variants.forEach((variant, index) => {
      const category = variant.keys[selectorIndex] ?? "*";
      if (!PLURAL_CATEGORIES.includes(category) || allowed.has(category)) return;
      rows.push({
        category,
        index,
        kind,
        label: formLabel(variant.keys, model.selectors),
        removable: variant.value.trim() === "",
        selector,
      });
    });
  });
  return rows;
}

export function removeInvalidLocalePluralRows(
  model: EditorModel | null,
  invalidRows: Array<InvalidLocalePluralRow>,
) {
  if (model?.type !== "select") return null;
  const removableIndexes = [...new Set(invalidRows.filter((row) => row.removable).map((row) => row.index))]
    .filter((index) => index >= 0 && index < model.variants.length)
    .sort((left, right) => right - left);
  if (!removableIndexes.length) return null;
  const next = cloneModel(model) as SelectEditorModel;
  const firstRemovedIndex = Math.min(...removableIndexes);
  for (const index of removableIndexes) {
    next.variants.splice(index, 1);
  }
  return {
    activeIndex: Math.min(firstRemovedIndex, Math.max(0, next.variants.length - 1)),
    model: next,
    removedCount: removableIndexes.length,
  };
}

export function lineCount(value: string) {
  return String(value ?? "").split(/\r\n|\r|\n/u).length;
}

export function patternPreview(pattern: string) {
  const text = String(pattern ?? "").replace(/\s+/gu, " ").trim();
  if (!text) return "(empty)";
  return text.length > 56 ? `${text.slice(0, 55)}...` : text;
}

export function coerceArgs(args: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(args ?? {}).map(([name, value]) => {
    const text = String(value);
    return [name, /^-?\d+(?:\.\d+)?$/u.test(text.trim()) ? Number(text) : value];
  }));
}

function modelFromRuntime(model: unknown, source: string): EditorModel | null {
  const runtime = model as RuntimeModel | undefined;
  if (!runtime) return null;
  const declarations = (runtime.declarations ?? []).map(declarationFromRuntime);
  if (runtime.type === "select") {
    return {
      declarations,
      selectors: (runtime.selectors ?? []).map((selector) => selector.name ?? ""),
      source,
      type: "select",
      variables: variablesFromRuntime(runtime),
      variants: (runtime.variants ?? []).map((variant) => ({
        keys: (variant.keys ?? []).map((key) => key.type === "*" ? "*" : key.value ?? ""),
        value: patternToSource(variant.value ?? []),
      })),
      rustModel: runtime,
    };
  }
  return {
    declarations,
    pattern: patternToSource(runtime.pattern ?? []),
    source,
    type: "message",
    variables: variablesFromRuntime(runtime),
    rustModel: runtime,
  };
}

function declarationFromRuntime(declaration: RuntimeDeclaration): EditorDeclaration {
  const expression = declaration.value ?? {};
  const functionRef = expression.function ?? {};
  return {
    argName: expression.arg?.type === "variable" ? expression.arg.name ?? "" : "",
    argSource: argToSource(expression.arg),
    attributeText: sourceArgsToText(expression.attributes, "@"),
    function: functionRef.name ?? "",
    name: declaration.name ?? "",
    optionText: optionsToSource(functionRef.options),
    type: declaration.type ?? "input",
  };
}

function patternToSource(pattern: Array<RuntimePart>) {
  return pattern.map((part) => {
    if (typeof part === "string") return patternTextToSource(part);
    if (part.type === "expression") return expressionToSource(part);
    if (part.type === "markup") return markupToSource(part);
    return "";
  }).join("");
}

function expressionToSource(part: Exclude<RuntimePart, string>) {
  const arg = argToSource(part.arg);
  const functionOptions = sourceArgsToText(part.function?.options, "");
  const functionSource = part.function?.name
    ? `:${part.function.name}${functionOptions ? ` ${functionOptions}` : ""}`
    : "";
  const tail = [
    functionSource,
    sourceArgsToText(part.attributes, "@"),
  ].filter(Boolean).join(" ");
  return `{${arg}${arg && tail ? " " : ""}${tail}}`;
}

function markupToSource(part: Exclude<RuntimePart, string>) {
  const kind = part.kind === "close" ? "close" : part.kind === "standalone" ? "standalone" : "open";
  const prefix = kind === "close" ? "/" : "#";
  const tail = kind === "close" ? "" : [
    sourceArgsToText(part.options, ""),
    sourceArgsToText(part.attributes, "@"),
  ].filter(Boolean).join(" ");
  const suffix = kind === "standalone" ? "/" : "";
  return `{${prefix}${part.name}${tail ? ` ${tail}` : ""}${suffix}}`;
}

function argToSource(arg?: RuntimeArg) {
  if (!arg) return "";
  if (arg.type === "variable") return `$${arg.name ?? ""}`;
  return quotedLiteralToSource(arg.value ?? "");
}

function patternTextToSource(text: string) {
  return String(text ?? "").replace(/[\\{}]/gu, "\\$&");
}

function patternValueToSource(pattern: string) {
  const value = String(pattern ?? "");
  let output = "";
  for (let index = 0; index < value.length;) {
    const char = value[index];
    if (char === "\\" && /[\\{}]/u.test(value[index + 1] ?? "")) {
      output += value.slice(index, index + 2);
      index += 2;
      continue;
    }
    if (char === "{") {
      const end = preservableBracedPatternPartEnd(value, index);
      if (end != null) {
        output += value.slice(index, end + 1);
        index = end + 1;
        continue;
      }
      output += "\\{";
      index += 1;
      continue;
    }
    if (char === "}" || char === "\\") {
      output += `\\${char}`;
      index += 1;
      continue;
    }
    const codePoint = value.codePointAt(index) ?? 0;
    output += String.fromCodePoint(codePoint);
    index += codePoint > 0xffff ? 2 : 1;
  }
  return output;
}

function preservableBracedPatternPartEnd(value: string, start: number) {
  const end = variableExpressionEnd(value, start);
  if (end == null) return null;
  const content = value.slice(start + 1, end).trim();
  return PRESERVABLE_BRACED_PATTERN_START_RE.test(content) ? end : null;
}

function variantKeyToSource(key: string) {
  if (key === "*") return "*";
  return isBareVariantKey(key) ? key : quotedLiteralToSource(key);
}

function isBareVariantKey(key: string) {
  const value = String(key ?? "");
  if (!value || BARE_VARIANT_KEY_DISALLOWED_RE.test(value)) return false;
  return !hasControlOrNoncharacter(value);
}

function quotedLiteralToSource(value: string) {
  return `|${String(value ?? "").replace(/[\\{}|]/gu, "\\$&")}|`;
}

function hasControlOrNoncharacter(value: string) {
  for (const char of value) {
    const codePoint = char.codePointAt(0) ?? 0;
    if (isControlCodePoint(codePoint) || isNoncharacterCodePoint(codePoint)) return true;
  }
  return false;
}

function isControlCodePoint(codePoint: number) {
  return (codePoint >= 0 && codePoint <= 0x1f) || (codePoint >= 0x7f && codePoint <= 0x9f);
}

function isSurrogateCodePoint(codePoint: number) {
  return codePoint >= 0xd800 && codePoint <= 0xdfff;
}

function isAsciiNameChar(codePoint: number) {
  return isAsciiNameStart(codePoint)
    || (codePoint >= 0x30 && codePoint <= 0x39)
    || codePoint === 0x2d
    || codePoint === 0x2e;
}

function isAsciiNameStart(codePoint: number) {
  return (codePoint >= 0x41 && codePoint <= 0x5a)
    || codePoint === 0x2b
    || codePoint === 0x5f
    || (codePoint >= 0x61 && codePoint <= 0x7a);
}

function isBidiMarkerCodePoint(codePoint: number) {
  return BIDI_MARKERS.has(codePoint);
}

function isBidiMarkerChar(char: string | undefined) {
  const codePoint = char?.codePointAt(0);
  return codePoint != null && isBidiMarkerCodePoint(codePoint);
}

function isNoncharacterCodePoint(codePoint: number) {
  return (codePoint >= 0xfdd0 && codePoint <= 0xfdef) || (codePoint & 0xfffe) === 0xfffe;
}

function optionsToSource(options?: Record<string, RuntimeArg>) {
  return sourceArgsToText(options, "");
}

function sourceArgsToText(args?: Record<string, RuntimeArg>, prefix = "") {
  return Object.entries(args ?? {}).map(([name, value]) => `${prefix}${name}=${argToSource(value)}`).join(" ");
}

function variablesFromRuntime(model: RuntimeModel) {
  const variables = new Set<string>();
  for (const selector of model.selectors ?? []) {
    if (selector.name) variables.add(selector.name);
  }
  for (const declaration of model.declarations ?? []) {
    if (declaration.name) variables.add(declaration.name);
    addVariablesFromExpression(declaration.value, variables);
  }
  const patterns = model.type === "select"
    ? (model.variants ?? []).map((variant) => variant.value ?? [])
    : [model.pattern ?? []];
  for (const pattern of patterns) {
    for (const part of pattern) {
      if (typeof part === "string") continue;
      if (part?.type === "expression") addVariablesFromExpression(part, variables);
      if (part?.type === "markup") {
        addVariablesFromArgs(part.options, variables);
        addVariablesFromArgs(part.attributes, variables);
      }
    }
  }
  return [...variables];
}

function addVariablesFromExpression(
  expression: RuntimePart | RuntimeDeclaration["value"] | undefined,
  variables: Set<string>,
) {
  if (!expression || typeof expression === "string") return;
  addVariablesFromArg(expression.arg, variables);
  addVariablesFromArgs(expression.function?.options, variables);
  addVariablesFromArgs(expression.attributes, variables);
}

function addVariablesFromArgs(args: Record<string, RuntimeArg> | undefined, variables: Set<string>) {
  for (const arg of Object.values(args ?? {})) {
    addVariablesFromArg(arg, variables);
  }
}

function addVariablesFromArg(arg: RuntimeArg | undefined, variables: Set<string>) {
  if (arg?.type === "variable" && arg.name) variables.add(arg.name);
}

function variantMatchesKeys(variantKeys: Array<string>, targetKeys: Array<string>) {
  if (variantKeys.length !== targetKeys.length) return false;
  return variantKeys.every((key, index) => key === "*" || key === targetKeys[index]);
}

function variantSignature(keys: Array<string>) {
  return keys.join("\u001F");
}

function variantSpecificityRank(keys: Array<string>) {
  return keys.map((key) => key === "*" ? 0 : 1);
}

function compareVariantSpecificityRank(left: Array<number>, right: Array<number>) {
  const length = Math.min(left.length, right.length);
  for (let index = 0; index < length; index += 1) {
    if (left[index] !== right[index]) return left[index] - right[index];
  }
  return left.length - right.length;
}

function selectorPluralKind(model: SelectEditorModel, selector: string): "cardinal" | "ordinal" | null {
  const declaration = model.declarations.find((item) => item.name === selector);
  return pluralKindForSelectorAnnotation(declaration?.function ?? "", declaration?.optionText ?? "");
}

function selectorContractPluralKind(selector: SelectorContract | undefined): "cardinal" | "ordinal" | null {
  return pluralKindForSelectorAnnotation(selector?.function ?? "", selector?.optionText ?? "");
}

function pluralKindForSelectorAnnotation(
  functionName: string,
  optionText: string,
): "cardinal" | "ordinal" | null {
  if (!["number", "integer", "percent", "offset"].includes(functionName)) return null;
  if (optionText.includes("select=exact")) return null;
  return optionText.includes("select=ordinal") ? "ordinal" : "cardinal";
}

function exactNumericKeysForSelectorIndex(
  model: SelectEditorModel,
  index: number,
  surroundingKeys?: Array<string>,
) {
  if (index < 0) return new Set<string>();
  return new Set(
    model.variants
      .filter((variant) => !surroundingKeys || sameSurroundingVariantKeys(variant.keys, surroundingKeys, index))
      .map((variant) => variant.keys[index])
      .filter((key): key is string => isNumericKey(key))
      .map(canonicalNumericKey),
  );
}

function sampleForPluralCategory(
  category: string,
  locale: string,
  excluded: Set<string>,
  kind: "cardinal" | "ordinal",
) {
  const pluralRules = pluralRulesForLocale(locale, kind);
  for (const candidate of PLURAL_SAMPLE_CANDIDATES) {
    if (excluded.has(canonicalNumericKey(candidate))) continue;
    if (pluralRules.select(candidate) === category) return candidate;
  }
  return null;
}

function pluralCategorySetForLocale(locale: string, kind: "cardinal" | "ordinal") {
  const pluralRules = pluralRulesForLocale(locale, kind);
  const resolvedCategories = (pluralRules.resolvedOptions() as { pluralCategories?: Array<string> }).pluralCategories;
  if (resolvedCategories?.length) {
    return PLURAL_CATEGORIES.filter((category) => resolvedCategories.includes(category));
  }
  const categories = new Set<string>();
  for (const candidate of PLURAL_SAMPLE_CANDIDATES) {
    categories.add(pluralRules.select(candidate));
  }
  return PLURAL_CATEGORIES.filter((category) => categories.has(category));
}

function pluralRulesForLocale(locale: string, kind: "cardinal" | "ordinal") {
  try {
    return new Intl.PluralRules(normalizeIntlLocale(locale), { type: kind });
  } catch {
    return new Intl.PluralRules(undefined, { type: kind });
  }
}

function normalizeIntlLocale(locale: string) {
  return String(locale ?? "").trim().replaceAll("_", "-") || undefined;
}

function generatedLocaleRowValue(fallback: EditorVariant | undefined, sourceModel: EditorModel | null) {
  const value = fallback?.value ?? "";
  return targetValueLooksSourceDerived(value, sourceModel) ? "" : value;
}

function targetValueLooksSourceDerived(value: string, sourceModel: EditorModel | null) {
  if (value === "" || !sourceModel) return false;
  if (sourceModel.type === "select") return sourceModel.variants.some((variant) => variant.value === value);
  return sourceModel.pattern === value;
}

function isNumericKey(value: string | undefined) {
  return /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/u.test(String(value ?? "").trim());
}

function canonicalNumericKey(value: string | number) {
  return String(Number(value));
}

function editorDiagnostic(diagnostic: unknown): EditorDiagnostic {
  const value = diagnostic as Record<string, unknown>;
  return {
    code: String(value?.code ?? value?.name ?? "runtime-error"),
    end: diagnosticSourceOffset(value?.end),
    message: String(value?.message ?? diagnostic),
    severity: value?.severity === "warning" ? "warning" : "error",
    start: diagnosticSourceOffset(value?.start),
  };
}

function diagnosticSourceOffset(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

const PLURAL_CATEGORIES = ["zero", "one", "two", "few", "many", "other"];
const MARKUP_KINDS = ["open", "close", "standalone"] as const;
const BIDI_MARKERS = new Set([0x061c, 0x200e, 0x200f, 0x2066, 0x2067, 0x2068, 0x2069]);
const BARE_VARIANT_KEY_DISALLOWED_RE = /[\s:@^!%*<>?~&\\$|{}]/u;
const PRESERVABLE_BRACED_PATTERN_START_RE = /^[$#/|:]/u;
const PLURAL_SAMPLE_CANDIDATES = [
  0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 20, 21, 22, 100, 101,
  1_000, 10_000, 100_000, 1_000_000, 2_000_000,
  1.1, 1.5, 2.1, 5.5,
];
const RTL_LANGUAGES = new Set(["ar", "dv", "fa", "he", "iw", "ks", "ku", "ps", "sd", "ug", "ur", "yi"]);
const RTL_SCRIPTS = new Set(["Adlm", "Arab", "Hebr", "Mand", "Nkoo", "Rohg", "Samr", "Syrc", "Thaa"]);
