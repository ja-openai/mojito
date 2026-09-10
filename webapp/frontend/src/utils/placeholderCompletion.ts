import {
  extractIcuProtectedTextTokens,
  extractPlatformPlaceholderTokens,
  type ProtectedTextToken,
} from './protectedTextTokens';

export type SourcePlaceholderCandidate = {
  text: string;
  kind: 'icu-placeholder' | 'platform-placeholder';
};

export type PlaceholderCompletion = {
  from: number;
  to: number;
  options: SourcePlaceholderCandidate[];
};

const ICU_CONTEXT_PROBE = '{_placeholder_completion}';

export function getSourcePlaceholderCandidates(source: string): SourcePlaceholderCandidate[] {
  const seen = new Set<string>();
  const icuTokens = extractIcuProtectedTextTokens(source);
  const platformTokens = extractPlatformPlaceholderTokens(source).filter(
    (token) =>
      !overlapsProtectedToken(token.start, token.end, icuTokens) &&
      !followsNumber(source, token.start),
  );
  return [...icuTokens, ...platformTokens]
    .sort((first, second) => first.start - second.start)
    .flatMap((token) => {
      if (token.kind !== 'icu-placeholder' && token.kind !== 'platform-placeholder') {
        return [];
      }
      const text = source.slice(token.start, token.end);
      if (text === '#' || seen.has(text)) {
        return [];
      }
      seen.add(text);
      return [{ text, kind: token.kind }];
    });
}

export function getPlaceholderCompletion(
  value: string,
  selection: { start: number; end: number } | null,
  candidates: SourcePlaceholderCandidate[],
  protectedTokens: ProtectedTextToken[] = [],
): PlaceholderCompletion | null {
  if (
    !selection ||
    selection.start !== selection.end ||
    !Number.isInteger(selection.start) ||
    selection.start < 0 ||
    selection.start > value.length ||
    candidates.length === 0
  ) {
    return null;
  }

  const caret = selection.start;
  const beforeCaret = value.slice(0, caret);
  const from = Math.max(beforeCaret.lastIndexOf('{'), beforeCaret.lastIndexOf('%'));
  if (from < 0 || overlapsProtectedToken(from, caret, protectedTokens)) {
    return null;
  }

  const isIcu = value[from] === '{';
  const prefix = value.slice(from, caret);
  const nextChar = value[caret] ?? '';
  // Do not turn a caret in the middle of a token into a duplicate suffix.
  if (
    (isIcu && /[\p{L}\p{N}\p{M}_.$,-]/u.test(nextChar)) ||
    (!isIcu && /[\p{L}\p{N}\p{M}_.$()*%#@+-]/u.test(nextChar)) ||
    (isIcu && /[}\n\r]/u.test(prefix)) ||
    (!isIcu && (isEscapedPercent(value, from) || followsNumber(value, from)))
  ) {
    return null;
  }

  const kind = isIcu ? 'icu-placeholder' : 'platform-placeholder';
  const normalizedPrefix = normalizePrefix(prefix, isIcu);
  const options = candidates.filter(
    (candidate) =>
      candidate.kind === kind &&
      normalizePrefix(candidate.text, isIcu).startsWith(normalizedPrefix) &&
      normalizePrefix(candidate.text, isIcu) !== normalizedPrefix,
  );
  if (options.length === 0) {
    return null;
  }

  if (!isIcu) {
    return { from, to: caret, options };
  }

  // Ask the existing parser whether this is an argument position. This handles ICU apostrophe
  // quoting and plural/select body braces without another implementation of their grammar.
  // Autocomplete stays closed if the rest of the target is malformed.
  const replacementEnds =
    nextChar === '}' && !overlapsProtectedToken(caret, caret + 1, protectedTokens)
      ? [caret + 1, caret]
      : [caret];
  for (const to of replacementEnds) {
    const proposedValue = `${value.slice(0, from)}${ICU_CONTEXT_PROBE}${value.slice(to)}`;
    const isArgumentPosition = extractIcuProtectedTextTokens(proposedValue).some(
      (token) =>
        token.kind === 'icu-placeholder' &&
        token.start === from &&
        token.end === from + ICU_CONTEXT_PROBE.length,
    );
    if (isArgumentPosition) {
      return { from, to, options };
    }
  }
  return null;
}

function normalizePrefix(text: string, isIcu: boolean): string {
  return (isIcu ? text.replace(/^\{\s*/u, '{') : text).toLowerCase();
}

function overlapsProtectedToken(from: number, to: number, tokens: ProtectedTextToken[]): boolean {
  return tokens.some((token) => from < token.end && to > token.start);
}

function isEscapedPercent(value: string, from: number): boolean {
  let precedingPercents = 0;
  for (let index = from - 1; index >= 0 && value[index] === '%'; index -= 1) {
    precedingPercents += 1;
  }
  return precedingPercents % 2 === 1;
}

function followsNumber(value: string, from: number): boolean {
  // Treat a percent immediately after a number as a literal percentage. This also avoids
  // interpreting `100%complete` as `%c`; separate percent escapes still follow the parity rule.
  return /\p{N}$/u.test(value.slice(Math.max(0, from - 2), from));
}
