import { describe, expect, it } from 'vitest';

import type { ApiMatchedGlossaryTerm } from '../api/glossaries';
import { prepareGlossaryMatches } from './glossary-matches';

const baseMatch: ApiMatchedGlossaryTerm = {
  glossaryId: 1,
  glossaryName: 'Product',
  tmTextUnitId: 10,
  source: 'GPT',
  comment: null,
  definition: null,
  partOfSpeech: null,
  termType: null,
  enforcement: null,
  status: 'APPROVED',
  provenance: null,
  target: 'GPT',
  targetComment: null,
  doNotTranslate: true,
  caseSensitive: false,
  matchType: 'EXACT',
  startIndex: 4,
  endIndex: 7,
  matchedText: 'GPT',
  evidence: [],
};

describe('prepareGlossaryMatches', () => {
  it('dedupes repeated spans by glossary term id', () => {
    const matches = prepareGlossaryMatches([
      { ...baseMatch, startIndex: 8, endIndex: 11 },
      { ...baseMatch, startIndex: 0, endIndex: 3 },
    ]);

    expect(matches).toHaveLength(1);
    expect(matches[0]).toMatchObject({
      tmTextUnitId: 10,
      startIndex: 0,
      endIndex: 3,
    });
    expect(matches[0].ranges).toEqual([
      { matchType: 'EXACT', startIndex: 0, endIndex: 3, matchedText: 'GPT' },
      { matchType: 'EXACT', startIndex: 8, endIndex: 11, matchedText: 'GPT' },
    ]);
  });

  it('keeps distinct term ids even when the source text is the same', () => {
    const matches = prepareGlossaryMatches([
      { ...baseMatch, tmTextUnitId: 10, glossaryId: 1, source: 'GPT' },
      {
        ...baseMatch,
        tmTextUnitId: 11,
        glossaryId: 1,
        source: 'GPT',
        definition: 'Different product concept.',
      },
    ]);

    expect(matches.map((match) => match.tmTextUnitId)).toEqual([10, 11]);
  });
});
