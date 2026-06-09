import { type MessageFormatElement, parse, TYPE } from '@formatjs/icu-messageformat-parser';

import {
  getLiteralSourcePlaceholderNames,
  getMf2DeclarationPlaceholderNames,
  getMf2DiagnosticsForPlaceholders,
} from './mf2TextModel';

export type ProtectedTextTokenKind =
  | 'html-tag'
  | 'icu-placeholder'
  | 'icu-syntax'
  | 'icu-pound'
  | 'platform-placeholder'
  | 'mf2-demo';
export type ProtectedTextTokenMode = 'icu' | 'icu-html' | 'mf2-demo' | 'none';

export type ProtectedTextToken = {
  start: number;
  end: number;
  label: string;
  kind: ProtectedTextTokenKind;
};

export type ProtectedTextDiagnostic = {
  start: number;
  end: number;
  severity: 'warning' | 'error';
  code: string;
  message: string;
};

export type ProtectedTextMovableRange = {
  start: number;
  end: number;
  label: string;
};

type ProtectedTextStructureCheck = {
  previousValue: string;
  previousTokens: ProtectedTextToken[];
  nextValue: string;
  mode: ProtectedTextTokenMode;
};

type LocatedElement = MessageFormatElement & {
  location?: SourceLocation;
};

type SourceLocation = {
  start: { offset: number };
  end: { offset: number };
};

type OptionWithLocation = {
  location?: SourceLocation;
  value: MessageFormatElement[];
};

export type IcuFormInsertion = {
  id: string;
  kind: 'category' | 'exact-value';
  label: string;
  messageType?: 'plural' | 'select';
  messageEnd: number;
  messageStart: number;
  nextValue?: string;
  selectionStart: number;
  selectionEnd: number;
  existingForms?: string[];
};

const ICU_PLURAL_FORM_ORDER = ['zero', 'one', 'two', 'few', 'many', 'other'];
const ICU_EXACT_VALUE_PATTERN = /^(?:0|[1-9]\d*)$/u;
const MF2_DEMO_TOKEN_PATTERN =
  /\{\$[A-Za-z_][\w.-]*(?:\})?|:[A-Za-z_][\w.-]*|\.(?:input|match|local)\b/gu;
const HTML_TAG_PATTERN = /<\/?\s*[A-Za-z][\w:.-]*(?:\s+[^<>]*?)?\/?\s*>/gu;
const APPLE_STRINGSDICT_PLACEHOLDER_PATTERN = /%#@[A-Za-z_][\w.-]*@/gu;
const APPLE_STRINGSDICT_PLACEHOLDER_LIKE_PATTERN = /%#@[A-Za-z_][\w.-]*/gu;
const APPLE_STRINGSDICT_PLACEHOLDER_LIKE_PREFIX_PATTERN = /^%#@[A-Za-z_][\w.-]*/u;
const PYTHON_NAMED_PLACEHOLDER_PATTERN =
  /%\([A-Za-z_][\w.-]*\)[-+#0 ]*(?:\d+|\*)?(?:\.(?:\d+|\*))?(?:[hlL])?[diouxXeEfFgGcrsra]/gu;
const PYTHON_NAMED_PLACEHOLDER_LIKE_PATTERN = /%\([A-Za-z_][\w.-]*(?:\))?/gu;
const PLATFORM_PLACEHOLDER_PATTERN =
  /%(?:\d+\$)?[-+#0,(<]*(?:\d+|\*)?(?:\.(?:\d+|\*))?(?:(?:hh|h|ll|l|q|L|z|t|j))?(?:[diuoxXfFeEgGaAcCsSp@]|[tT][A-Za-z]|n)/gu;
const PLATFORM_PLACEHOLDER_LIKE_PATTERN = /%(?:\d+\.(?:\d+)?|\d+\$?|\.(?:\d+)?|\*)/gu;
const PLACEHOLDER_ADJACENT_TEXT_PATTERN = /[\p{L}\p{N}_]/u;

export function extractProtectedTextTokens(
  value: string,
  mode: ProtectedTextTokenMode,
): ProtectedTextToken[] {
  if (!value || mode === 'none') {
    return [];
  }

  if (mode === 'icu') {
    return extractIcuProtectedTextTokens(value);
  }

  if (mode === 'icu-html') {
    return extractIcuAndHtmlProtectedTextTokens(value);
  }

  return extractMf2DemoProtectedTextTokens(value);
}

export function getProtectedTextDiagnostics(
  value: string,
  mode: ProtectedTextTokenMode,
): ProtectedTextDiagnostic[] {
  if (!value || mode === 'none') {
    return [];
  }

  if (mode === 'icu-html') {
    return extractPlatformPlaceholderDiagnostics(value);
  }

  if (mode === 'mf2-demo') {
    return getMf2DiagnosticsForPlaceholders(
      getMf2DeclarationPlaceholderNames(value),
      getLiteralSourcePlaceholderNames(value),
      value,
    ).map((diagnostic, index) => ({
      start: 0,
      end: Math.max(1, value.length),
      severity: diagnostic.severity,
      code: `mf2-${diagnostic.severity}-${index + 1}`,
      message: diagnostic.message,
    }));
  }

  return [];
}

export function canParseProtectedTextTokens(value: string, mode: ProtectedTextTokenMode): boolean {
  return mode === 'icu' || mode === 'icu-html' ? parseIcuMessage(value) !== null : true;
}

export function preservesProtectedTextTokenStructure({
  previousValue,
  previousTokens,
  nextValue,
  mode,
}: ProtectedTextStructureCheck): boolean {
  if (mode === 'none') {
    return true;
  }

  if (mode === 'icu' || mode === 'icu-html') {
    const previousAst = parseIcuMessage(previousValue);
    const nextAst = parseIcuMessage(nextValue);

    if (!previousAst || !nextAst) {
      return preservesProtectedTextTokenSequence({
        previousValue,
        previousTokens,
        nextValue,
      });
    }
  }

  return preservesRequiredProtectedTextTokenStructure({
    previousValue,
    previousTokens,
    nextValue,
    nextTokens: extractProtectedTextTokens(nextValue, mode),
  });
}

function preservesRequiredProtectedTextTokenStructure({
  previousValue,
  previousTokens,
  nextValue,
  nextTokens,
}: {
  previousValue: string;
  previousTokens: ProtectedTextToken[];
  nextValue: string;
  nextTokens: ProtectedTextToken[];
}): boolean {
  const previousStructure = getProtectedTextTokenStructure(previousValue, previousTokens);
  const nextStructure = getProtectedTextTokenStructure(nextValue, nextTokens);

  let nextIndex = 0;
  for (const [previousIndex, previousEntry] of previousStructure.entries()) {
    const searchStart = nextIndex;
    while (nextIndex < nextStructure.length && nextStructure[nextIndex] !== previousEntry) {
      nextIndex += 1;
    }

    if (nextIndex >= nextStructure.length) {
      return false;
    }

    // Extra tokens before an existing ICU boundary mean a plural/select skeleton was reshaped.
    if (previousIndex > 0 && nextIndex > searchStart && isIcuStructuralTokenEntry(previousEntry)) {
      return false;
    }

    nextIndex += 1;
  }
  return true;
}

function isIcuStructuralTokenEntry(entry: string): boolean {
  return entry.startsWith('icu-syntax\u0000') || entry.startsWith('icu-pound\u0000');
}

export function relocateProtectedTextTokens(
  previousValue: string,
  previousTokens: ProtectedTextToken[],
  nextValue: string,
): ProtectedTextToken[] | null {
  const relocatedTokens: ProtectedTextToken[] = [];
  let cursor = 0;

  for (const token of normalizeTokens(previousTokens)) {
    const rawToken = previousValue.slice(token.start, token.end);
    const nextOffset = nextValue.indexOf(rawToken, cursor);
    if (nextOffset < 0) {
      return null;
    }

    relocatedTokens.push({
      ...token,
      start: nextOffset,
      end: nextOffset + rawToken.length,
    });
    cursor = nextOffset + rawToken.length;
  }

  return relocatedTokens;
}

function preservesProtectedTextTokenSequence({
  previousValue,
  previousTokens,
  nextValue,
}: {
  previousValue: string;
  previousTokens: ProtectedTextToken[];
  nextValue: string;
}): boolean {
  return relocateProtectedTextTokens(previousValue, previousTokens, nextValue) !== null;
}

function getProtectedTextTokenStructure(value: string, tokens: ProtectedTextToken[]): string[] {
  return normalizeTokens(tokens).map(
    (token) => `${token.kind}\u0000${token.label}\u0000${value.slice(token.start, token.end)}`,
  );
}

export function extractIcuProtectedTextTokens(value: string): ProtectedTextToken[] {
  const ast = parseIcuMessage(value);
  if (!ast) {
    return [];
  }

  return normalizeTokens(collectIcuTokens(ast));
}

export function getIcuFormInsertions(value: string): IcuFormInsertion[] {
  const ast = parseIcuMessage(value);
  if (!ast) {
    return [];
  }

  const insertions: IcuFormInsertion[] = [];
  collectIcuFormInsertions(value, ast, insertions);
  return insertions;
}

export function getIcuMovableTextRanges(value: string): ProtectedTextMovableRange[] {
  const ast = parseIcuMessage(value);
  if (!ast) {
    return [];
  }

  const ranges: ProtectedTextMovableRange[] = [];
  collectIcuMovableTextRanges(ast, ranges);
  return normalizeMovableRanges(value, ranges);
}

export type IcuExactPluralOptionInsertionResult =
  | {
      ok: true;
      nextValue: string;
      selectionStart: number;
      selectionEnd: number;
    }
  | {
      ok: false;
      error: string;
    };

export function buildIcuExactPluralOptionInsertion(
  value: string,
  insertion: IcuFormInsertion,
  rawExactValue: string,
): IcuExactPluralOptionInsertionResult {
  if (insertion.kind !== 'exact-value') {
    return { ok: false, error: 'Choose an exact-value insertion.' };
  }

  const exactValue = normalizeExactPluralValue(rawExactValue);
  if (!exactValue) {
    return { ok: false, error: 'Enter a non-negative integer.' };
  }

  const optionName = `=${exactValue}`;
  if (insertion.existingForms?.includes(optionName)) {
    return { ok: false, error: `${optionName} already exists.` };
  }

  const prefix = optionInsertionPrefix(value, insertion.selectionStart);
  const text = `${prefix}${optionName} {# } `;
  const selectionStart = insertion.selectionStart + prefix.length + `${optionName} {# `.length;
  return {
    ok: true,
    nextValue: `${value.slice(0, insertion.selectionStart)}${text}${value.slice(
      insertion.selectionStart,
    )}`,
    selectionStart,
    selectionEnd: selectionStart,
  };
}

export function extractIcuAndHtmlProtectedTextTokens(value: string): ProtectedTextToken[] {
  return normalizeTokens([
    ...extractIcuProtectedTextTokens(value),
    ...extractHtmlTagTokens(value),
    ...extractPlatformPlaceholderTokens(value),
  ]);
}

export function extractPlatformPlaceholderTokens(value: string): ProtectedTextToken[] {
  const tokens: ProtectedTextToken[] = [];

  for (const match of value.matchAll(APPLE_STRINGSDICT_PLACEHOLDER_PATTERN)) {
    const raw = match[0];
    const start = match.index ?? 0;
    if (hasOddEscapingPercentRun(value, start)) {
      continue;
    }
    tokens.push({
      start,
      end: start + raw.length,
      label: `Apple stringsdict placeholder ${raw}`,
      kind: 'platform-placeholder',
    });
  }

  for (const match of value.matchAll(PYTHON_NAMED_PLACEHOLDER_PATTERN)) {
    const raw = match[0];
    const start = match.index ?? 0;
    if (hasOddEscapingPercentRun(value, start)) {
      continue;
    }
    tokens.push({
      start,
      end: start + raw.length,
      label: `Platform placeholder ${raw}`,
      kind: 'platform-placeholder',
    });
  }

  for (const match of value.matchAll(PLATFORM_PLACEHOLDER_PATTERN)) {
    const raw = match[0];
    const start = match.index ?? 0;
    if (
      hasOddEscapingPercentRun(value, start) ||
      isLikelyPythonNamedPlaceholderPrefix(value, start, raw) ||
      isLikelyAppleStringsdictPlaceholderPrefix(value, start, raw)
    ) {
      continue;
    }
    tokens.push({
      start,
      end: start + raw.length,
      label: `Platform placeholder ${raw}`,
      kind: 'platform-placeholder',
    });
  }

  return normalizeTokens(tokens);
}

export function extractPlatformPlaceholderDiagnostics(value: string): ProtectedTextDiagnostic[] {
  const tokens = extractPlatformPlaceholderTokens(value);
  const diagnostics: ProtectedTextDiagnostic[] = [];

  tokens.forEach((token) => {
    if (
      token.label.startsWith('Platform placeholder %#@') &&
      APPLE_STRINGSDICT_PLACEHOLDER_LIKE_PREFIX_PATTERN.test(value.slice(token.start))
    ) {
      return;
    }

    const nextChar = value.charAt(token.end);
    if (!PLACEHOLDER_ADJACENT_TEXT_PATTERN.test(nextChar)) {
      return;
    }

    const end = consumeIdentifierText(value, token.end);
    diagnostics.push({
      start: token.start,
      end,
      severity: 'warning',
      code: 'placeholder-adjacent-text',
      message: `Platform placeholder ${value.slice(
        token.start,
        token.end,
      )} touches text; add a separator or verify the placeholder syntax.`,
    });
  });

  for (const match of value.matchAll(APPLE_STRINGSDICT_PLACEHOLDER_LIKE_PATTERN)) {
    const raw = match[0];
    const start = match.index ?? 0;
    const end = start + raw.length;
    if (hasOddEscapingPercentRun(value, start) || value.charAt(end) === '@') {
      continue;
    }

    diagnostics.push({
      start,
      end,
      severity: 'warning',
      code: 'placeholder-malformed',
      message: `Apple stringsdict placeholder ${raw} is missing its closing @.`,
    });
  }

  for (const match of value.matchAll(PYTHON_NAMED_PLACEHOLDER_LIKE_PATTERN)) {
    const raw = match[0];
    const start = match.index ?? 0;
    const end = start + raw.length;
    if (hasOddEscapingPercentRun(value, start) || overlapsToken(start, end, tokens)) {
      continue;
    }

    diagnostics.push({
      start,
      end,
      severity: 'warning',
      code: 'placeholder-malformed',
      message: raw.endsWith(')')
        ? `Named placeholder ${raw} is missing its conversion type.`
        : `Named placeholder ${raw} is missing its closing ).`,
    });
  }

  for (const match of value.matchAll(PLATFORM_PLACEHOLDER_LIKE_PATTERN)) {
    const raw = match[0];
    const start = match.index ?? 0;
    const end = start + raw.length;
    if (hasOddEscapingPercentRun(value, start) || overlapsToken(start, end, tokens)) {
      continue;
    }

    const diagnosticEnd = PLACEHOLDER_ADJACENT_TEXT_PATTERN.test(value.charAt(end))
      ? consumeIdentifierText(value, end)
      : end;
    diagnostics.push({
      start,
      end: diagnosticEnd,
      severity: 'warning',
      code: 'placeholder-malformed',
      message: `Placeholder-like sequence ${raw} is incomplete or malformed.`,
    });
  }

  return normalizeDiagnostics(diagnostics);
}

function isLikelyPythonNamedPlaceholderPrefix(value: string, start: number, raw: string): boolean {
  if (!raw.startsWith('%(')) {
    return false;
  }
  return /[\p{L}\p{N}_)]/u.test(value.charAt(start + raw.length));
}

function isLikelyAppleStringsdictPlaceholderPrefix(
  value: string,
  start: number,
  raw: string,
): boolean {
  return (
    raw === '%#@' && APPLE_STRINGSDICT_PLACEHOLDER_LIKE_PREFIX_PATTERN.test(value.slice(start))
  );
}

function hasOddEscapingPercentRun(value: string, offset: number): boolean {
  let count = 0;
  for (let index = offset - 1; index >= 0 && value.charAt(index) === '%'; index -= 1) {
    count += 1;
  }
  return count % 2 === 1;
}

function consumeIdentifierText(value: string, start: number): number {
  let end = start;
  while (end < value.length && PLACEHOLDER_ADJACENT_TEXT_PATTERN.test(value.charAt(end))) {
    end += 1;
  }
  return end;
}

function overlapsToken(start: number, end: number, tokens: ProtectedTextToken[]): boolean {
  return tokens.some((token) => start < token.end && end > token.start);
}

function normalizeExactPluralValue(value: string): string | null {
  const normalized = value.trim().replace(/^=/u, '');
  return ICU_EXACT_VALUE_PATTERN.test(normalized) ? normalized : null;
}

function parseIcuMessage(value: string): MessageFormatElement[] | null {
  try {
    return parse(value, {
      captureLocation: true,
      ignoreTag: true,
      requiresOtherClause: false,
    });
  } catch {
    return null;
  }
}

export function extractHtmlTagTokens(value: string): ProtectedTextToken[] {
  const tokens: ProtectedTextToken[] = [];

  for (const match of value.matchAll(HTML_TAG_PATTERN)) {
    const raw = match[0];
    const start = match.index ?? 0;
    const name = raw.match(/^<\s*\/?\s*([A-Za-z][\w:.-]*)/u)?.[1] ?? 'tag';
    tokens.push({
      start,
      end: start + raw.length,
      label: `HTML tag ${name}`,
      kind: 'html-tag',
    });
  }

  return normalizeTokens(tokens);
}

export function extractMf2DemoProtectedTextTokens(value: string): ProtectedTextToken[] {
  const tokens: ProtectedTextToken[] = [];

  for (const match of value.matchAll(MF2_DEMO_TOKEN_PATTERN)) {
    const raw = match[0];
    const start = match.index ?? 0;
    const variableName = raw.startsWith('{$') ? raw.replace(/^\{\$/u, '').replace(/\}$/u, '') : '';
    tokens.push({
      start,
      end: start + raw.length,
      label: raw.startsWith('{$')
        ? `MF2 variable ${variableName}`
        : raw.startsWith(':')
          ? `MF2 function ${raw.slice(1)}`
          : `MF2 ${raw.slice(1)} declaration`,
      kind: 'mf2-demo',
    });
  }

  return normalizeTokens(tokens);
}

function collectIcuTokens(elements: MessageFormatElement[]): ProtectedTextToken[] {
  const tokens: ProtectedTextToken[] = [];

  elements.forEach((element) => {
    const located = element;
    const range = rangeFromLocation(located.location);

    switch (element.type) {
      case TYPE.literal:
        return;
      case TYPE.argument:
      case TYPE.number:
      case TYPE.date:
      case TYPE.time:
        if (range) {
          tokens.push({
            ...range,
            label: `ICU argument ${element.value}`,
            kind: 'icu-placeholder',
          });
        }
        return;
      case TYPE.pound:
        if (range) {
          tokens.push({
            ...range,
            label: 'ICU plural #',
            kind: 'icu-pound',
          });
        }
        return;
      case TYPE.select:
      case TYPE.plural:
        tokens.push(...collectIcuStructuredTokens(element));
        return;
      default:
        return;
    }
  });

  return tokens;
}

function collectIcuStructuredTokens(element: LocatedElement): ProtectedTextToken[] {
  const range = rangeFromLocation(element.location);
  if (!range || !('options' in element)) {
    return [];
  }

  const tokens: ProtectedTextToken[] = [];
  let cursor = range.start;

  Object.values(element.options as Record<string, OptionWithLocation>).forEach((option) => {
    const optionRange = rangeFromLocation(option.location);
    if (!optionRange) {
      return;
    }

    const bodyRange = bodyRangeFromOption(option, optionRange);
    if (cursor < bodyRange.start) {
      tokens.push({
        start: cursor,
        end: bodyRange.start,
        label: `ICU ${element.type === TYPE.plural ? 'plural' : 'select'} syntax`,
        kind: 'icu-syntax',
      });
    }

    tokens.push(...collectIcuTokens(option.value));
    cursor = bodyRange.end;
  });

  if (cursor < range.end) {
    tokens.push({
      start: cursor,
      end: range.end,
      label: `ICU ${element.type === TYPE.plural ? 'plural' : 'select'} syntax`,
      kind: 'icu-syntax',
    });
  }

  return tokens;
}

function collectIcuFormInsertions(
  value: string,
  elements: MessageFormatElement[],
  insertions: IcuFormInsertion[],
) {
  elements.forEach((element) => {
    if (element.type !== TYPE.plural && element.type !== TYPE.select) {
      if ('options' in element) {
        Object.values(element.options as Record<string, OptionWithLocation>).forEach((option) => {
          collectIcuFormInsertions(value, option.value, insertions);
        });
      }
      return;
    }

    const range = rangeFromLocation(element.location);
    if (!range) {
      return;
    }

    const options = element.options as Record<string, OptionWithLocation>;
    const existingForms = new Set(Object.keys(options));
    const insertionPoint = icuFormInsertionPoint(value, range, options);
    if (!insertionPoint) {
      return;
    }

    const availableForms =
      element.type === TYPE.plural
        ? ICU_PLURAL_FORM_ORDER.filter((form) => !existingForms.has(form))
        : existingForms.has('other')
          ? []
          : ['other'];

    availableForms.forEach((form) => {
      const prefix = optionInsertionPrefix(value, insertionPoint.offset);
      const bodyPrefix = element.type === TYPE.plural ? '# ' : ' ';
      const text = `${prefix}${form} {${bodyPrefix}} `;
      const selectionStart =
        insertionPoint.offset + prefix.length + `${form} {${bodyPrefix}`.length;
      const argumentName =
        element.value.trim() || (element.type === TYPE.plural ? 'plural' : 'select');
      insertions.push({
        id: `${range.start}:${form}`,
        kind: 'category',
        label: `${argumentName}: ${form}`,
        messageType: element.type === TYPE.plural ? 'plural' : 'select',
        nextValue: `${value.slice(0, insertionPoint.offset)}${text}${value.slice(
          insertionPoint.offset,
        )}`,
        messageEnd: range.end,
        messageStart: range.start,
        selectionStart,
        selectionEnd: selectionStart,
      });
    });

    if (element.type === TYPE.plural) {
      insertions.push({
        id: `${range.start}:exact-value`,
        kind: 'exact-value',
        label: `${element.value.trim() || 'plural'}: exact value...`,
        messageType: 'plural',
        messageEnd: range.end,
        messageStart: range.start,
        selectionStart: insertionPoint.offset,
        selectionEnd: insertionPoint.offset,
        existingForms: [...existingForms],
      });
    }

    Object.values(options).forEach((option) => {
      collectIcuFormInsertions(value, option.value, insertions);
    });
  });
}

function collectIcuMovableTextRanges(
  elements: MessageFormatElement[],
  ranges: ProtectedTextMovableRange[],
) {
  elements.forEach((element) => {
    if (element.type === TYPE.plural || element.type === TYPE.select) {
      const range = rangeFromLocation(element.location);
      if (range) {
        ranges.push({
          ...range,
          label: `ICU ${element.type === TYPE.plural ? 'plural' : 'select'} message`,
        });
      }
    }

    if ('options' in element) {
      Object.values(element.options as Record<string, OptionWithLocation>).forEach((option) => {
        collectIcuMovableTextRanges(option.value, ranges);
      });
    }
  });
}

function icuFormInsertionPoint(
  value: string,
  range: { start: number; end: number },
  options: Record<string, OptionWithLocation>,
): { offset: number } | null {
  const otherRange = rangeFromLocation(options.other?.location);
  if (otherRange) {
    return {
      offset: optionKeywordStart(value, range.start, otherRange.start, 'other') ?? otherRange.start,
    };
  }

  return { offset: Math.max(range.start, range.end - 1) };
}

function optionInsertionPrefix(value: string, offset: number): string {
  if (offset <= 0 || /\s/u.test(value.charAt(offset - 1))) {
    return '';
  }
  return ' ';
}

function optionKeywordStart(
  value: string,
  rangeStart: number,
  optionBodyStart: number,
  optionName: string,
): number | null {
  const prefix = value.slice(rangeStart, optionBodyStart);
  const match = new RegExp(`\\b${optionName}\\s*$`, 'u').exec(prefix);
  return match ? rangeStart + match.index : null;
}

function bodyRangeFromOption(
  option: OptionWithLocation,
  optionRange: { start: number; end: number },
): { start: number; end: number } {
  let start = optionRange.start + 1;
  let end = Math.max(start, optionRange.end - 1);

  option.value.forEach((child) => {
    const childRange = rangeFromLocation(child.location);
    if (!childRange) {
      return;
    }
    start = Math.min(start, childRange.start);
    end = Math.max(end, childRange.end);
  });

  return { start, end };
}

function rangeFromLocation(location?: SourceLocation): { start: number; end: number } | null {
  const start = location?.start.offset;
  const end = location?.end.offset;
  if (
    typeof start !== 'number' ||
    typeof end !== 'number' ||
    !Number.isFinite(start) ||
    !Number.isFinite(end) ||
    start >= end
  ) {
    return null;
  }
  return { start, end };
}

function normalizeTokens(tokens: ProtectedTextToken[]): ProtectedTextToken[] {
  const sorted = tokens
    .filter((token) => token.start >= 0 && token.end > token.start)
    .sort((a, b) => a.start - b.start || b.end - a.end);

  const output: ProtectedTextToken[] = [];
  let cursor = 0;

  sorted.forEach((token) => {
    if (token.start < cursor) {
      return;
    }
    output.push(token);
    cursor = token.end;
  });

  return output;
}

function normalizeMovableRanges(
  value: string,
  ranges: ProtectedTextMovableRange[],
): ProtectedTextMovableRange[] {
  return ranges
    .filter((range) => range.start >= 0 && range.end > range.start && range.end <= value.length)
    .sort((a, b) => a.start - b.start || a.end - b.end);
}

function normalizeDiagnostics(diagnostics: ProtectedTextDiagnostic[]): ProtectedTextDiagnostic[] {
  const sorted = diagnostics
    .filter((diagnostic) => diagnostic.start >= 0 && diagnostic.end > diagnostic.start)
    .sort((a, b) => a.start - b.start || b.end - a.end);
  const output: ProtectedTextDiagnostic[] = [];
  let cursor = 0;

  sorted.forEach((diagnostic) => {
    if (diagnostic.start < cursor) {
      return;
    }
    output.push(diagnostic);
    cursor = diagnostic.end;
  });

  return output;
}
