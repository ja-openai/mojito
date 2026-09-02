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
  const protectedTokens: ProtectedTextToken[] = bracedPatternPartsInPattern(value).map((part) => {
    const placeholder = placeholders.get(part.from);
    const isPlaceholder = placeholder?.to === part.to;
    return {
      start: part.from,
      end: part.to,
      kind: isPlaceholder ? 'mf2-placeholder' : 'mf2-syntax',
      label: isPlaceholder ? `MF2 variable ${placeholder.name}` : 'MF2 syntax',
      // Keep the serialized MF2 visible. Protection must not turn the document into one
      // resolved form or hide the syntax that identifies it as MessageFormat 2.
      displayText: part.source,
    };
  });
  return { protectedTokens, value };
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
