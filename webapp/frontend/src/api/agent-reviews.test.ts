// @vitest-environment node

import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  fetchAgentReviewFeedback,
  reopenAgentFinding,
  saveAgentReviewOutcome,
} from './agent-reviews';
import { createReviewProjectClientContext } from './review-project-client-context';

afterEach(() => vi.unstubAllGlobals());

it('reopens the same project with the observed current state', async () => {
  const result = { projectId: 7, proposalId: 92, proposalRevision: 4 };
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(result)));
  vi.stubGlobal('fetch', fetchMock);
  const request = {
    requestKey: 'stable-round',
    expectedProposalVersion: 4,
    expectedCurrentVariantId: 22,
    expectedSource: 'Account',
    expectedSourceComment: null,
    expectedCurrentTarget: 'Compte',
    expectedCurrentStatus: 'APPROVED',
    expectedCurrentIncludedInLocalizedFile: true,
  };
  expect(await reopenAgentFinding(7, 91, request)).toEqual(result);
  expect(fetchMock).toHaveBeenCalledWith('/api/agent-reviews/projects/7/proposals/91/reopen', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
});

describe('Agent review outcome transport', () => {
  it.each(['KEEP_CURRENT', 'DEFER', 'REQUEST_REVISION'] as const)(
    'sends %s with proposal identity and no translation fields',
    async (action) => {
      const fetchMock = vi
        .fn()
        .mockResolvedValue(new Response(JSON.stringify({ id: 17 }), { status: 200 }));
      vi.stubGlobal('fetch', fetchMock);
      const clientContext = createReviewProjectClientContext('agent_outcome', {
        projectId: 7,
        textUnitId: 17,
        tmTextUnitId: 117,
        reviewStateRevision: 'checked-row',
      });
      await saveAgentReviewOutcome({
        textUnitId: 17,
        clientContext,
        decisionState: action === 'DEFER' ? 'PENDING' : 'DECIDED',
        expectedCurrentTmTextUnitVariantId: 22,
        expectedReviewStateRevision: 'checked-row',
        agentReview: {
          proposalId: 91,
          proposalRevision: 3,
          proposalVersion: 4,
          requestId: 'stable-key',
          action,
        },
      });
      const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
      expect(url).toBe('/api/review-project-text-units/17/decision');
      const body = JSON.parse(init.body as string) as Record<string, unknown>;
      expect(body.agentReview).toEqual({
        proposalId: 91,
        proposalRevision: 3,
        proposalVersion: 4,
        requestId: 'stable-key',
        action,
      });
      expect(body.expectedCurrentTmTextUnitVariantId).toBe(22);
      expect(body.expectedReviewStateRevision).toBe('checked-row');
      expect(body.clientContext).toMatchObject(clientContext);
      expect(body.clientContext).toHaveProperty('requestSequence', expect.any(Number));
      for (const field of ['target', 'status', 'includedInLocalizedFile', 'comment'])
        expect(body).not.toHaveProperty(field);
    },
  );

  it('preserves a full conflicting row for existing draft recovery', async () => {
    const row = { id: 17, reviewStateRevision: 'updated-row', agentReview: { proposalVersion: 5 } };
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify(row), { status: 409 })),
    );
    await expect(
      saveAgentReviewOutcome({
        textUnitId: 17,
        decisionState: 'PENDING',
        agentReview: {
          proposalId: 91,
          proposalRevision: 3,
          proposalVersion: 4,
          requestId: 'stable-key',
          action: 'DEFER',
        },
      }),
    ).rejects.toMatchObject({ status: 409, data: row });
  });
});

it('loads history beyond the first page so a recent challenge is not hidden', async () => {
  const older = Array.from({ length: 200 }, (_, index) => ({ id: index + 1, action: 'DEFER' }));
  const latest = { id: 201, action: 'CHALLENGE' };
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(new Response(JSON.stringify(older)))
    .mockResolvedValueOnce(new Response(JSON.stringify([latest])));
  vi.stubGlobal('fetch', fetchMock);
  expect(await fetchAgentReviewFeedback(7, 91)).toEqual([...older, latest]);
  expect(fetchMock.mock.calls[1][0]).toContain('afterId=200');
});
