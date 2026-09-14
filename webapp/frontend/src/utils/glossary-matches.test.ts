import { describe, expect, it } from 'vitest';

import type { ApiMatchedGlossaryTerm } from '../api/glossaries';
import { getGlossaryVisibleText, prepareGlossaryMatches } from './glossary-matches';

const baseMatch: ApiMatchedGlossaryTerm = {
  glossaryId: 1,
  glossaryName: 'Product',
  tmTextUnitId: 10,
  source: 'Key',
  comment: null,
  definition: null,
  partOfSpeech: null,
  termType: null,
  enforcement: null,
  status: 'APPROVED',
  provenance: null,
  target: 'Key',
  targetComment: null,
  doNotTranslate: true,
  caseSensitive: false,
  matchType: 'EXACT',
  startIndex: 4,
  endIndex: 7,
  matchedText: 'Key',
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
      { matchType: 'EXACT', startIndex: 0, endIndex: 3, matchedText: 'Key' },
      { matchType: 'EXACT', startIndex: 8, endIndex: 11, matchedText: 'Key' },
    ]);
  });

  it('keeps distinct term ids even when the source text is the same', () => {
    const matches = prepareGlossaryMatches([
      { ...baseMatch, tmTextUnitId: 10, glossaryId: 1, source: 'Key' },
      {
        ...baseMatch,
        tmTextUnitId: 11,
        glossaryId: 1,
        source: 'Key',
        definition: 'Different product concept.',
      },
    ]);

    expect(matches.map((match) => match.tmTextUnitId)).toEqual([10, 11]);
  });
});

describe('getGlossaryVisibleText', () => {
  it.each([
    'Count < 5 and total > 2',
    'Start <link',
    'Start <link title="unfinished>trial',
    'Start &amp; continue {name}',
    '{count, plural, one {trial} other {trials}}',
    '{#link}Start trial{/link}',
    '[Start trial](destination)',
  ])('preserves text outside valid tag syntax: %s', (value) => {
    expect(getGlossaryVisibleText(value)).toBe(value);
  });
});
