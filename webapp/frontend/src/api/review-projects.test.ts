// @vitest-environment node

import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  saveReviewProjectTextUnitDecision,
  setReviewProjectTextUnitDecisionState,
} from './review-projects';

afterEach(() => vi.unstubAllGlobals());

const request = {
  textUnitId: 17,
  target: 'Translation',
  comment: 'Comment',
  status: 'APPROVED',
  includedInLocalizedFile: true,
  decisionState: 'DECIDED' as const,
  decisionNotes: 'Reviewer notes',
  expectedCurrentTmTextUnitVariantId: 22,
  expectedReviewStateRevision: 'reviewed-row-revision',
};

describe('Review Project decision revision transport', () => {
  it.each([
    ['translation', saveReviewProjectTextUnitDecision],
    ['state only', setReviewProjectTextUnitDecisionState],
  ] as const)(
    'sends the reviewed revision and receives the complete %s row',
    async (_label, save) => {
      const row = {
        id: 17,
        reviewStateRevision: 'saved-row-revision',
        reviewProjectTextUnitSuggestion: { id: 44, target: 'Staged translation' },
      };
      const fetchMock = vi
        .fn()
        .mockResolvedValue(new Response(JSON.stringify(row), { status: 200 }));
      vi.stubGlobal('fetch', fetchMock);
      expect(await save(request)).toEqual(row);
      const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
      expect(url).toBe('/api/review-project-text-units/17/decision');
      if (typeof init.body !== 'string') throw new Error('Expected a JSON request body');
      expect(JSON.parse(init.body)).toMatchObject({
        decisionState: 'DECIDED',
        expectedCurrentTmTextUnitVariantId: 22,
        expectedReviewStateRevision: 'reviewed-row-revision',
      });
    },
  );

  it('retains the conflicting row revision for explicit recovery', async () => {
    const row = { id: 17, reviewStateRevision: 'changed-row-revision' };
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify(row), { status: 409 })),
    );
    await expect(saveReviewProjectTextUnitDecision(request)).rejects.toMatchObject({
      status: 409,
      data: row,
    });
  });
});
