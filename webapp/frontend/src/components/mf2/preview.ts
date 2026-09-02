import type { ProtectedTextToken } from '../../utils/protectedTextTokens';
import {
  bracedPatternPartsInPattern,
  MAX_MF2_MESSAGE_CODE_UNITS,
  MAX_MF2_SYNTAX_BRACES,
  patternTextFromSource,
  placeholderExpressionsInPattern,
} from './model';

export type Mf2PatternPreview = {
  protectedTokens: ProtectedTextToken[];
  value: string;
};

export function mf2PatternPreview(pattern: string): Mf2PatternPreview {
  const value = String(pattern ?? '');
  const placeholders = new Map(
    placeholderExpressionsInPattern(value).map((expression) => [expression.from, expression]),
  );
  const protectedTokens: ProtectedTextToken[] = [];
  let previewValue = '';
  let sourceOffset = 0;

  for (const part of bracedPatternPartsInPattern(value)) {
    previewValue += patternTextFromSource(value.slice(sourceOffset, part.from));
    const start = previewValue.length;
    previewValue += part.source;
    const placeholder = placeholders.get(part.from);
    const isPlaceholder = placeholder?.to === part.to;
    protectedTokens.push({
      start,
      end: previewValue.length,
      kind: isPlaceholder ? 'mf2-placeholder' : 'mf2-syntax',
      label: isPlaceholder ? `MF2 variable ${placeholder.name}` : 'MF2 syntax',
      ...(isPlaceholder ? { displayText: placeholder.name } : {}),
    });
    sourceOffset = part.to;
  }

  previewValue += patternTextFromSource(value.slice(sourceOffset));
  return { protectedTokens, value: previewValue };
}

export type Mf2DocumentPreview = Mf2PatternPreview & { patternRanges: SourceRange[] };

export function mf2DocumentPreview(source: string): Mf2DocumentPreview {
  const value = String(source ?? '');
  if (!isWithinPreviewTokenLimits(value)) {
    return { patternRanges: [], protectedTokens: [], value };
  }

  const patternRanges = patternBodyRangesInDocument(value);
  const placeholders = new Map(
    placeholderExpressionsInPattern(value).map((expression) => [expression.from, expression]),
  );
  const protectedTokens: ProtectedTextToken[] = [
    ...bracedPatternPartsInPattern(value)
      .filter((part) => isInsidePatternBody(part.from, part.to, patternRanges))
      .map((part) => {
        const placeholder = placeholders.get(part.from);
        const isPlaceholder = placeholder?.to === part.to;
        return {
          start: part.from,
          end: part.to,
          kind: isPlaceholder ? ('mf2-placeholder' as const) : ('mf2-syntax' as const),
          label: isPlaceholder ? `MF2 variable ${placeholder.name}` : 'MF2 syntax',
          // Keep the serialized MF2 visible. Protection must not turn the document into one
          // resolved form or hide the syntax that identifies it as MessageFormat 2.
          displayText: part.source,
        };
      }),
    ...fallbackSelectorTokensInDocument(value, patternRanges),
  ].sort((left, right) => left.start - right.start || left.end - right.end);
  return { patternRanges, protectedTokens, value };
}

type SourceRange = { end: number; start: number };

// Complex MF2 documents wrap only translatable pattern bodies in `{{...}}`.
// Keep document declarations and selector structure outside the highlighted ranges.
function patternBodyRangesInDocument(value: string): SourceRange[] {
  if (!startsWithComplexMessageSyntax(value)) {
    return [{ start: 0, end: value.length }];
  }

  const patternRanges: SourceRange[] = [];
  let expressionDepth = 0;
  let inQuotedLiteral = false;

  for (let index = 0; index < value.length; ) {
    const character = codePointAt(value, index);
    if (inQuotedLiteral && character === '\\') {
      index += character.length;
      if (index < value.length) index += codePointAt(value, index).length;
      continue;
    }
    if (
      character === '|' &&
      (expressionDepth > 0 || inQuotedLiteral || isVariantKeyBoundary(value, index))
    ) {
      inQuotedLiteral = !inQuotedLiteral;
      index += character.length;
      continue;
    }
    if (!inQuotedLiteral && expressionDepth === 0 && value.startsWith('{{', index)) {
      const range = quotedPatternBodyRangeAt(value, index);
      if (range) {
        patternRanges.push(range);
        index = range.end + 2;
        continue;
      }
    }
    if (!inQuotedLiteral && character === '{') {
      expressionDepth += 1;
    } else if (!inQuotedLiteral && character === '}' && expressionDepth > 0) {
      expressionDepth -= 1;
    }
    index += character.length;
  }

  return patternRanges;
}

function quotedPatternBodyRangeAt(value: string, start: number): SourceRange | null {
  const contentStart = start + 2;
  let expressionDepth = 0;
  let inQuotedLiteral = false;

  for (let index = contentStart; index < value.length; ) {
    if (expressionDepth === 0 && value.startsWith('}}', index)) {
      return { start: contentStart, end: index };
    }
    const character = codePointAt(value, index);
    if (character === '\\') {
      index += character.length;
      if (index < value.length) index += codePointAt(value, index).length;
      continue;
    }
    if (expressionDepth > 0 && character === '|') {
      inQuotedLiteral = !inQuotedLiteral;
    } else if (!inQuotedLiteral && character === '{') {
      expressionDepth += 1;
    } else if (!inQuotedLiteral && character === '}' && expressionDepth > 0) {
      expressionDepth -= 1;
    }
    index += character.length;
  }

  return null;
}

function startsWithComplexMessageSyntax(value: string) {
  let index = 0;
  while (index < value.length) {
    const character = codePointAt(value, index);
    if (!isMf2SyntaxWhitespace(character)) break;
    index += character.length;
  }
  return (
    value.startsWith('.input', index) ||
    value.startsWith('.local', index) ||
    value.startsWith('.match', index) ||
    value.startsWith('{{', index)
  );
}

function isVariantKeyBoundary(value: string, index: number) {
  return index === 0 || isMf2SyntaxWhitespace(value[index - 1]);
}

function isMf2SyntaxWhitespace(character: string) {
  return /[\s\u061c\u200e\u200f\u2066-\u2069]/u.test(character);
}

function isInsidePatternBody(start: number, end: number, ranges: SourceRange[]) {
  return ranges.some((range) => start >= range.start && end <= range.end);
}

function codePointAt(value: string, index: number) {
  const codePoint = value.codePointAt(index);
  return codePoint == null ? '' : String.fromCodePoint(codePoint);
}

function fallbackSelectorTokensInDocument(
  value: string,
  patternRanges: SourceRange[],
): ProtectedTextToken[] {
  if (!startsWithComplexMessageSyntax(value) || !patternRanges.length) return [];
  const header = value.slice(0, patternRanges[0].start - 2);
  const matchDirective = /^[\t \u061c\u200e\u200f\u2066-\u2069]*\.match\b/mu.exec(header);
  if (matchDirective?.index == null) return [];
  const tokens: ProtectedTextToken[] = [];
  let structureStart = matchDirective.index + matchDirective[0].length;
  for (const range of patternRanges) {
    const line = value.slice(structureStart, range.start - 2);
    let inQuotedLiteral = false;
    let escaped = false;

    for (let index = 0; index < line.length; index += 1) {
      const character = line[index];
      if (inQuotedLiteral) {
        if (escaped) {
          escaped = false;
        } else if (character === '\\') {
          escaped = true;
        } else if (character === '|') {
          inQuotedLiteral = false;
        }
        continue;
      }
      if (character === '|' && isVariantKeyBoundary(line, index)) {
        inQuotedLiteral = true;
        continue;
      }
      if (
        character === '*' &&
        (index === 0 || isMf2SyntaxWhitespace(line[index - 1])) &&
        (index === line.length - 1 || isMf2SyntaxWhitespace(line[index + 1]))
      ) {
        const start = structureStart + index;
        tokens.push({
          displayText: 'fallback',
          end: start + 1,
          kind: 'mf2-syntax',
          label: 'MF2 fallback selector',
          start,
        });
      }
    }

    structureStart = range.end + 2;
  }

  return tokens;
}

function isWithinPreviewTokenLimits(value: string) {
  if (value.length > MAX_MF2_MESSAGE_CODE_UNITS) return false;
  let braceCount = 0;
  for (const character of value) {
    if (character !== '{' && character !== '}') continue;
    braceCount += 1;
    if (braceCount > MAX_MF2_SYNTAX_BRACES) return false;
  }
  return true;
}
