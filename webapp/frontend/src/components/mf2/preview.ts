import type { ProtectedTextToken } from '../../utils/protectedTextTokens';
import {
  bracedPatternPartsInPattern,
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
