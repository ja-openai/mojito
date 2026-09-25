import { describe, expect, it } from 'vitest';

import type { ApiMatchedGlossaryTerm } from '../api/glossaries';
import {
  buildGlossaryContextMessage,
  getGlossaryVisibleText,
  prepareGlossaryMatches,
} from './glossary-matches';

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

describe('buildGlossaryContextMessage', () => {
  it.each([null, '', ' \t '])(
    'keeps a missing target (%j) as context without inventing required wording',
    (target) => {
      const message = buildGlossaryContextMessage([
        {
          ...baseMatch,
          source: 'tool',
          matchedText: 'tool',
          endIndex: 8,
          target,
          doNotTranslate: false,
          enforcement: 'SOFT',
          definition: 'A callable app capability.',
        },
      ]);

      expect(message?.content).toContain('tool [4-8]');
      expect(message?.content).toContain('definition: A callable app capability.');
      expect(message?.content).toContain('enforcement: SOFT');
      expect(message?.content).toContain('target translation: not provided');
      expect(message?.content).toContain('no required wording or translation defect implied');
      expect(message?.content).not.toContain('required target:');
      expect(message?.content).not.toContain('translator review needed');
    },
  );

  it('retains a supplied target and its locale-specific note', () => {
    const message = buildGlossaryContextMessage([
      {
        ...baseMatch,
        target: ' 工具 ',
        targetComment: 'Use for callable app capabilities.',
        doNotTranslate: false,
      },
    ]);

    expect(message?.content).toContain('required target: 工具');
    expect(message?.content).toContain('target note: Use for callable app capabilities.');
    expect(message?.content).not.toContain('target translation: not provided');
  });

  it('preserves do-not-translate instructions even without a localized target', () => {
    const message = buildGlossaryContextMessage([{ ...baseMatch, target: null }]);

    expect(message?.content).toContain('required action: DO NOT TRANSLATE');
    expect(message?.content).not.toContain('required target:');
    expect(message?.content).not.toContain('target translation: not provided');
  });
});

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
