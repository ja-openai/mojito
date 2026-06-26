import type { AiReviewMessage } from '../api/ai-review';
import type { ApiMatchedGlossaryTerm, ApiMatchedGlossaryTermRange } from '../api/glossaries';

export function filterSelfGlossaryMatches(
  matches: ApiMatchedGlossaryTerm[] | null | undefined,
  tmTextUnitId: number | null | undefined,
): ApiMatchedGlossaryTerm[] {
  if (tmTextUnitId == null) {
    return [...(matches ?? [])];
  }
  return (matches ?? []).filter((match) => match.tmTextUnitId !== tmTextUnitId);
}

export function sortGlossaryMatches(
  matches: ApiMatchedGlossaryTerm[] | null | undefined,
): ApiMatchedGlossaryTerm[] {
  return [...(matches ?? [])].sort((left, right) => {
    if (left.startIndex !== right.startIndex) {
      return left.startIndex - right.startIndex;
    }
    if (left.endIndex !== right.endIndex) {
      return left.endIndex - right.endIndex;
    }
    return left.source.localeCompare(right.source, undefined, { sensitivity: 'base' });
  });
}

export function getGlossaryMatchRanges(
  match: ApiMatchedGlossaryTerm,
): ApiMatchedGlossaryTermRange[] {
  return match.ranges && match.ranges.length > 0
    ? match.ranges
    : [
        {
          matchType: match.matchType,
          startIndex: match.startIndex,
          endIndex: match.endIndex,
          matchedText: match.matchedText,
        },
      ];
}

export function dedupeGlossaryMatchesByTermId(
  matches: ApiMatchedGlossaryTerm[] | null | undefined,
): ApiMatchedGlossaryTerm[] {
  const matchesByTermId = new Map<string, ApiMatchedGlossaryTerm>();
  const rangeKeysByTermId = new Map<string, Set<string>>();
  const dedupedMatches: ApiMatchedGlossaryTerm[] = [];

  for (const match of matches ?? []) {
    const termId = `${match.glossaryId ?? 'none'}:${match.tmTextUnitId}`;
    const ranges = getGlossaryMatchRanges(match);
    const existingMatch = matchesByTermId.get(termId);
    const existingRangeKeys = rangeKeysByTermId.get(termId) ?? new Set<string>();
    const nextRanges = ranges.filter((range) => {
      const rangeKey = `${range.matchType}:${range.startIndex}:${range.endIndex}:${range.matchedText}`;
      if (existingRangeKeys.has(rangeKey)) {
        return false;
      }
      existingRangeKeys.add(rangeKey);
      return true;
    });

    rangeKeysByTermId.set(termId, existingRangeKeys);

    if (existingMatch) {
      existingMatch.ranges = [...getGlossaryMatchRanges(existingMatch), ...nextRanges];
      continue;
    }

    const dedupedMatch = { ...match, ranges: nextRanges };
    matchesByTermId.set(termId, dedupedMatch);
    dedupedMatches.push(dedupedMatch);
  }

  return dedupedMatches;
}

export function prepareGlossaryMatches(
  matches: ApiMatchedGlossaryTerm[] | null | undefined,
): ApiMatchedGlossaryTerm[] {
  return dedupeGlossaryMatchesByTermId(sortGlossaryMatches(matches));
}

export function buildGlossaryContextMessage(
  matches: ApiMatchedGlossaryTerm[] | null | undefined,
): AiReviewMessage | null {
  const sortedMatches = prepareGlossaryMatches(matches);
  if (sortedMatches.length === 0) {
    return null;
  }

  const lines = sortedMatches.map((match) => {
    const ranges = getGlossaryMatchRanges(match);
    const rangeSummary = ranges
      .map((range) => `${range.matchedText} [${range.startIndex}-${range.endIndex}]`)
      .join(', ');
    const parts = [`- ${rangeSummary}`];

    if (match.source.trim() !== match.matchedText.trim()) {
      parts.push(`source term: ${match.source}`);
    }

    if (match.doNotTranslate) {
      parts.push('required action: DO NOT TRANSLATE');
    } else if (match.target?.trim()) {
      parts.push(`required target: ${match.target.trim()}`);
    } else {
      parts.push('required target: translator review needed');
    }

    if (match.targetComment?.trim()) {
      parts.push(`target note: ${match.targetComment.trim()}`);
    }
    if (match.definition?.trim()) {
      parts.push(`definition: ${match.definition.trim()}`);
    }
    if (match.comment?.trim()) {
      parts.push(`source note: ${match.comment.trim()}`);
    }
    if (match.glossaryName?.trim()) {
      parts.push(`glossary: ${match.glossaryName.trim()}`);
    }
    if (match.termType?.trim()) {
      parts.push(`type: ${match.termType.trim()}`);
    }
    if (match.partOfSpeech?.trim()) {
      parts.push(`part of speech: ${match.partOfSpeech.trim()}`);
    }
    if (match.enforcement?.trim()) {
      parts.push(`enforcement: ${match.enforcement.trim()}`);
    }
    if (match.status?.trim()) {
      parts.push(`status: ${match.status.trim()}`);
    }
    if (match.caseSensitive) {
      parts.push('case-sensitive');
    }

    return parts.join(' | ');
  });

  return {
    role: 'user',
    content: [
      'Context only: glossary terms matched in the source text.',
      'Prefer these glossary constraints when reviewing, scoring, and suggesting edits.',
      ...lines,
    ].join('\n'),
  };
}
