import { type MessageFormatElement, parse, TYPE } from '@formatjs/icu-messageformat-parser';

export type ProtectedTextTokenKind =
  | 'html-tag'
  | 'icu-placeholder'
  | 'icu-syntax'
  | 'icu-pound'
  | 'mf2-demo';
export type ProtectedTextTokenMode = 'icu' | 'icu-html' | 'mf2-demo' | 'none';

export type ProtectedTextToken = {
  start: number;
  end: number;
  label: string;
  kind: ProtectedTextTokenKind;
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

export type IcuPluralOptionInsertion = {
  id: string;
  kind: 'category' | 'exact-value';
  label: string;
  pluralEnd: number;
  pluralStart: number;
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
      if (mode === 'icu-html') {
        return hasSameProtectedTextTokenStructure({
          previousValue,
          previousTokens: previousTokens.filter((token) => token.kind === 'html-tag'),
          nextValue,
          nextTokens: extractHtmlTagTokens(nextValue),
        });
      }
      return true;
    }
  }

  return hasSameProtectedTextTokenStructure({
    previousValue,
    previousTokens,
    nextValue,
    nextTokens: extractProtectedTextTokens(nextValue, mode),
  });
}

function hasSameProtectedTextTokenStructure({
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

  if (previousStructure.length !== nextStructure.length) {
    return false;
  }

  return previousStructure.every((entry, index) => entry === nextStructure[index]);
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

export function getIcuPluralOptionInsertions(value: string): IcuPluralOptionInsertion[] {
  const ast = parseIcuMessage(value);
  if (!ast) {
    return [];
  }

  const insertions: IcuPluralOptionInsertion[] = [];
  collectIcuPluralOptionInsertions(value, ast, insertions);
  return insertions;
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
  insertion: IcuPluralOptionInsertion,
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

  const text = `${optionName} {# } `;
  const selectionStart = insertion.selectionStart + `${optionName} {# `.length;
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
  return normalizeTokens([...extractIcuProtectedTextTokens(value), ...extractHtmlTagTokens(value)]);
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

function collectIcuPluralOptionInsertions(
  value: string,
  elements: MessageFormatElement[],
  insertions: IcuPluralOptionInsertion[],
) {
  elements.forEach((element) => {
    if (element.type !== TYPE.plural) {
      if ('options' in element) {
        Object.values(element.options as Record<string, OptionWithLocation>).forEach((option) => {
          collectIcuPluralOptionInsertions(value, option.value, insertions);
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
    const insertionPoint = pluralInsertionPoint(value, range, options);
    if (!insertionPoint) {
      return;
    }

    ICU_PLURAL_FORM_ORDER.filter((form) => form !== 'other' && !existingForms.has(form)).forEach(
      (form) => {
        const text = `${form} {# } `;
        const selectionStart = insertionPoint.offset + `${form} {# `.length;
        const argumentName = element.value.trim() || 'plural';
        insertions.push({
          id: `${range.start}:${form}`,
          kind: 'category',
          label: `${argumentName}: ${form}`,
          nextValue: `${value.slice(0, insertionPoint.offset)}${text}${value.slice(
            insertionPoint.offset,
          )}`,
          pluralEnd: range.end,
          pluralStart: range.start,
          selectionStart,
          selectionEnd: selectionStart,
        });
      },
    );

    insertions.push({
      id: `${range.start}:exact-value`,
      kind: 'exact-value',
      label: `${element.value.trim() || 'plural'}: exact value...`,
      pluralEnd: range.end,
      pluralStart: range.start,
      selectionStart: insertionPoint.offset,
      selectionEnd: insertionPoint.offset,
      existingForms: [...existingForms],
    });

    Object.values(options).forEach((option) => {
      collectIcuPluralOptionInsertions(value, option.value, insertions);
    });
  });
}

function pluralInsertionPoint(
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
