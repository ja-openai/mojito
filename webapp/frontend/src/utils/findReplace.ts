import { formatIntegerCount } from './numberFormat';
import { REPLACEMENT_TEXT_MAX_LENGTH } from './replacementLimits';
import { textsAreEquivalent } from './textEquivalence';

const REPLACEMENT_TEXT_MAX_LENGTH_LABEL = formatIntegerCount(REPLACEMENT_TEXT_MAX_LENGTH);

export type FindReplaceFindOptions = {
  findText: string;
  matchCase: boolean;
  regex?: boolean;
  wholeWord?: boolean;
};

export type FindReplaceOptions = FindReplaceFindOptions & {
  replaceText: string;
  preserveCase?: boolean;
};

export type FindReplaceMatchRange = {
  start: number;
  end: number;
};

export function validateFindReplaceOptions(options: FindReplaceOptions): string | null {
  if (options.findText.length > REPLACEMENT_TEXT_MAX_LENGTH) {
    return `Find text must be ${REPLACEMENT_TEXT_MAX_LENGTH_LABEL} characters or fewer.`;
  }
  if (options.replaceText.length > REPLACEMENT_TEXT_MAX_LENGTH) {
    return `Replace text must be ${REPLACEMENT_TEXT_MAX_LENGTH_LABEL} characters or fewer.`;
  }
  if (options.regex && options.findText.length > 0) {
    const pattern = buildFindPattern(options);
    if (!pattern) {
      return 'Find regex must be valid.';
    }
    if (patternMatchesEmptyText(pattern)) {
      return 'Find regex must not match empty text.';
    }
  }
  return null;
}

export function replaceFindReplaceText(
  value: string,
  options: FindReplaceOptions,
): { value: string; count: number } {
  const { findText, replaceText } = options;
  if (!findText) {
    return { value, count: 0 };
  }
  if (validateFindReplaceOptions(options)) {
    return { value, count: 0 };
  }

  const pattern = buildFindPattern(options);
  if (!pattern) {
    return { value, count: 0 };
  }

  let count = 0;
  const nextValue = value.replace(pattern, (match, ...args: unknown[]) => {
    const start = getReplacementOffset(args);
    const end = start + match.length;
    if (options.wholeWord && !isWholeWordMatch(value, start, end)) {
      return match;
    }

    const nextReplacement = options.preserveCase
      ? applyReplacementCase(replaceText, match, options.matchCase)
      : replaceText;
    if (!textsAreEquivalent(match, nextReplacement)) {
      count += 1;
      return nextReplacement;
    }
    return match;
  });

  return { value: nextValue, count };
}

export function countFindReplaceMatches(value: string, options: FindReplaceFindOptions): number {
  return getFindReplaceMatchRanges(value, options).length;
}

export function getFindReplaceMatchRanges(
  value: string,
  options: FindReplaceFindOptions,
): FindReplaceMatchRange[] {
  if (!options.findText) {
    return [];
  }

  const pattern = buildFindPattern(options);
  if (!pattern || patternMatchesEmptyText(pattern)) {
    return [];
  }

  const ranges: FindReplaceMatchRange[] = [];
  for (const match of value.matchAll(pattern)) {
    const matchedText = match[0];
    if (!matchedText) {
      continue;
    }

    const start = match.index ?? 0;
    const end = start + matchedText.length;
    if (options.wholeWord && !isWholeWordMatch(value, start, end)) {
      continue;
    }
    ranges.push({ start, end });
  }
  return ranges;
}

export function replaceFindReplaceRange(
  value: string,
  options: FindReplaceOptions,
  range: FindReplaceMatchRange,
): { value: string; count: number } {
  if (!options.findText || validateFindReplaceOptions(options)) {
    return { value, count: 0 };
  }
  if (range.start < 0 || range.end > value.length || range.start >= range.end) {
    return { value, count: 0 };
  }

  const match = value.slice(range.start, range.end);
  const replacement = options.preserveCase
    ? applyReplacementCase(options.replaceText, match, options.matchCase)
    : options.replaceText;
  if (textsAreEquivalent(match, replacement)) {
    return { value, count: 0 };
  }

  return {
    value: `${value.slice(0, range.start)}${replacement}${value.slice(range.end)}`,
    count: 1,
  };
}

function buildFindPattern(options: FindReplaceFindOptions): RegExp | null {
  const source = options.regex ? options.findText : escapeRegExp(options.findText);
  try {
    return new RegExp(source, options.matchCase ? 'gu' : 'giu');
  } catch {
    return null;
  }
}

function patternMatchesEmptyText(pattern: RegExp): boolean {
  const flags = pattern.flags.replace('g', '');
  return new RegExp(pattern.source, flags).test('');
}

function getReplacementOffset(args: unknown[]): number {
  const maybeOffset = args[args.length - 2];
  if (typeof maybeOffset === 'number') {
    return maybeOffset;
  }

  const maybeOffsetWithNamedGroups = args[args.length - 3];
  return typeof maybeOffsetWithNamedGroups === 'number' ? maybeOffsetWithNamedGroups : 0;
}

function isWholeWordMatch(value: string, start: number, end: number): boolean {
  return !isWordCharacter(getCodePointBefore(value, start)) && !isWordCharacter(value.slice(end));
}

function getCodePointBefore(value: string, index: number): string | null {
  if (index <= 0) {
    return null;
  }
  const previousCharacters = Array.from(value.slice(0, index));
  return previousCharacters[previousCharacters.length - 1] ?? null;
}

function isWordCharacter(value: string | null): boolean {
  if (!value) {
    return false;
  }
  const [character] = Array.from(value);
  return Boolean(character && /[\p{L}\p{N}\p{M}_]/u.test(character));
}

function applyReplacementCase(
  replacement: string,
  match: string,
  preserveMixedCase: boolean,
): string {
  if (!replacement || !hasCasedLetter(match)) {
    return replacement;
  }

  if (match === match.toLocaleUpperCase()) {
    return replacement.toLocaleUpperCase();
  }
  if (match === match.toLocaleLowerCase()) {
    return replacement.toLocaleLowerCase();
  }
  if (isTitleCaseLike(match)) {
    return titleCaseReplacement(replacement);
  }
  if (preserveMixedCase) {
    return mixedCaseReplacement(replacement, match);
  }
  return replacement;
}

function isTitleCaseLike(value: string): boolean {
  const characters = Array.from(value);
  const firstCasedIndex = characters.findIndex(hasCasedLetter);
  if (firstCasedIndex === -1) {
    return false;
  }

  const firstCasedCharacter = characters[firstCasedIndex];
  const rest = characters.slice(firstCasedIndex + 1).join('');
  return (
    firstCasedCharacter === firstCasedCharacter.toLocaleUpperCase() &&
    firstCasedCharacter !== firstCasedCharacter.toLocaleLowerCase() &&
    rest === rest.toLocaleLowerCase()
  );
}

function titleCaseReplacement(value: string): string {
  const characters = Array.from(value);
  const firstCasedIndex = characters.findIndex(hasCasedLetter);
  if (firstCasedIndex === -1) {
    return value;
  }

  const prefix = characters.slice(0, firstCasedIndex).join('');
  const first = characters[firstCasedIndex].toLocaleUpperCase();
  const rest = characters
    .slice(firstCasedIndex + 1)
    .join('')
    .toLocaleLowerCase();
  return `${prefix}${first}${rest}`;
}

function mixedCaseReplacement(replacement: string, match: string): string {
  const matchCasedCharacters = Array.from(match).filter(hasCasedLetter);
  if (matchCasedCharacters.length === 0) {
    return replacement;
  }

  let matchCasedIndex = 0;
  return Array.from(replacement)
    .map((character) => {
      if (!hasCasedLetter(character)) {
        return character;
      }
      const matchCharacter = matchCasedCharacters[matchCasedIndex];
      matchCasedIndex += 1;
      if (!matchCharacter) {
        return character;
      }
      if (matchCharacter === matchCharacter.toLocaleUpperCase()) {
        return character.toLocaleUpperCase();
      }
      if (matchCharacter === matchCharacter.toLocaleLowerCase()) {
        return character.toLocaleLowerCase();
      }
      return character;
    })
    .join('');
}

function hasCasedLetter(value: string): boolean {
  return value.toLocaleLowerCase() !== value.toLocaleUpperCase();
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
