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

export function mf2DocumentPreview(source: string): Mf2PatternPreview {
  const value = String(source ?? '');
  if (!isWithinPreviewTokenLimits(value)) {
    return { protectedTokens: [], value };
  }

  const placeholders = new Map(
    placeholderExpressionsInPattern(value).map((expression) => [expression.from, expression]),
  );
  const protectedTokens: ProtectedTextToken[] = [
    ...bracedPatternPartsInPattern(value).map((part) => {
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
    ...fallbackSelectorTokensInDocument(value),
  ].sort((left, right) => left.start - right.start || left.end - right.end);
  return { protectedTokens, value };
}

function fallbackSelectorTokensInDocument(value: string): ProtectedTextToken[] {
  const matchDirective = /^[\t ]*\.match\b/mu.exec(value);
  if (matchDirective?.index == null) return [];
  const bodyStart = matchDirective.index + matchDirective[0].length;
  const tokens: ProtectedTextToken[] = [];
  const lines = value.slice(bodyStart).matchAll(/[^\r\n]*(?:\r\n|\r|\n|$)/gu);

  for (const lineMatch of lines) {
    if (!lineMatch[0]) break;
    const lineStart = bodyStart + (lineMatch.index ?? 0);
    const line = lineMatch[0].replace(/(?:\r\n|\r|\n)$/u, '');
    const lineTokens: ProtectedTextToken[] = [];
    let inQuotedLiteral = false;
    let escaped = false;
    let hasPattern = false;

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
      if (character === '|') {
        inQuotedLiteral = true;
        continue;
      }
      if (character === '{' && line[index + 1] === '{') {
        hasPattern = true;
        break;
      }
      if (
        character === '*' &&
        (index === 0 || isSelectorWhitespace(line[index - 1])) &&
        (index === line.length - 1 || isSelectorWhitespace(line[index + 1]))
      ) {
        const start = lineStart + index;
        lineTokens.push({
          displayText: 'fallback',
          end: start + 1,
          kind: 'mf2-syntax',
          label: 'MF2 fallback selector',
          start,
        });
      }
    }

    if (hasPattern) tokens.push(...lineTokens);
  }

  return tokens;
}

function isSelectorWhitespace(character: string | undefined) {
  return character === ' ' || character === '\t';
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
