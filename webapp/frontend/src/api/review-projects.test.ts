// @vitest-environment node

import { afterEach, describe, expect, it, vi } from 'vitest';

import { createReviewProjectClientContext } from './review-project-client-context';
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
  it('saves an edited completed incident through one atomic request', async () => {
    const clientContext = createReviewProjectClientContext('review_accept', {
      projectId: 7,
      textUnitId: 17,
      tmTextUnitId: 117,
      reviewStateRevision: request.expectedReviewStateRevision,
    });
    const reopenAgentReview = {
      requestKey: 'reopen-key',
      expectedProposalVersion: 4,
      expectedCurrentVariantId: 22,
      expectedSource: 'Account',
      expectedSourceComment: null,
      expectedCurrentTarget: 'Compte',
      expectedCurrentStatus: 'APPROVED',
      expectedCurrentIncludedInLocalizedFile: true,
    };
    const agentReview = {
      proposalId: 91,
      proposalRevision: 2,
      proposalVersion: 4,
      requestId: 'save-key',
      action: 'ACCEPT' as const,
    };
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: 17 })));
    vi.stubGlobal('fetch', fetchMock);
    await saveReviewProjectTextUnitDecision({
      ...request,
      clientContext,
      agentReview,
      reopenAgentReview,
    });
    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/agent-reviews/projects/7/proposals/91/reopen-and-save');
    expect(JSON.parse(init.body as string)).toMatchObject({
      reopen: reopenAgentReview,
      decision: {
        target: request.target,
        expectedReviewStateRevision: request.expectedReviewStateRevision,
        agentReview,
      },
    });
  });

  it.each([
    ['translation', saveReviewProjectTextUnitDecision],
    ['state only', setReviewProjectTextUnitDecisionState],
  ] as const)(
    'sends %s context with stable operation and separate transport sequences',
    async (_label, save) => {
      const clientContext = createReviewProjectClientContext('review_save', {
        projectId: 7,
        textUnitId: 17,
        tmTextUnitId: 117,
        reviewStateRevision: request.expectedReviewStateRevision,
      });
      const bodies: Record<string, unknown>[] = [];
      vi.stubGlobal(
        'fetch',
        vi.fn((_url, init: RequestInit) => {
          if (typeof init.body !== 'string') throw new Error('Expected JSON body');
          bodies.push(JSON.parse(init.body) as Record<string, unknown>);
          return Promise.resolve(new Response(JSON.stringify({ id: 17 }), { status: 200 }));
        }),
      );
      await save({ ...request, clientContext });
      await save({ ...request, clientContext });
      const first = bodies[0].clientContext as typeof clientContext;
      const second = bodies[1].clientContext as typeof clientContext;
      expect(second.operationId).toBe(first.operationId);
      expect(second.requestSequence).toBe(first.requestSequence! + 1);
      expect(JSON.stringify(first)).not.toContain(request.target);
      expect(JSON.stringify(first)).not.toContain(request.comment);
    },
  );

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
