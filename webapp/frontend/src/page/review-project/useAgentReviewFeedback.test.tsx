import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it } from 'vitest';

import type { ApiAgentReviewContext } from '../../api/agent-reviews';
import type { ApiReviewProjectTextUnit } from '../../api/review-projects';
import { EMPTY_AGENT_REVIEW_FEEDBACK } from './agent-review-feedback';
import type { PendingAction, ReviewProjectActionState } from './review-project-mutations';
import { useAgentReviewFeedback } from './useAgentReviewFeedback';

const proposal: ApiAgentReviewContext = {
  proposalId: 901,
  proposalRevision: 1,
  proposalVersion: 3,
  findingId: 'finding',
  runId: 51,
  reviewType: 'TRANSLATION_QUALITY',
  reviewedSource: 'Enable notifications',
  reviewedTarget: 'Activer les notifications',
  proposedTarget: null,
  rationale: 'Check the wording.',
  category: 'OPTIONAL_IMPROVEMENT',
  verificationStatus: 'READY',
  disposition: 'RESOLVED',
  stale: false,
};
const successor = {
  ...proposal,
  proposalId: 902,
  proposalRevision: 2,
  proposalVersion: 1,
  previousProposalId: 901,
  lastFeedbackRequestId: 'saved-feedback',
};
const row: ApiReviewProjectTextUnit = {
  id: 10,
  tmTextUnit: { id: 20 },
  baselineTmTextUnitVariant: null,
  currentTmTextUnitVariant: null,
};
const idle: ReviewProjectActionState = { phase: 'idle' };
const feedbackKey = (proposalId: number) => [
  'review-project-draft',
  'translator',
  7,
  row.id,
  `agent-feedback:${proposalId}`,
];
const clients: QueryClient[] = [];
afterEach(() => clients.splice(0).forEach((client) => client.clear()));

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  clients.push(client);
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  const view = renderHook(
    ({ proposal: current, action }) =>
      useAgentReviewFeedback('translator', 7, row.id, current, action),
    { initialProps: { proposal, action: idle }, wrapper },
  );
  return { ...view, client };
}

function savedAction(atomic: boolean): ReviewProjectActionState {
  const source = atomic ? proposal : successor;
  const agentReview = {
    proposalId: source.proposalId,
    proposalRevision: source.proposalRevision,
    proposalVersion: source.proposalVersion,
    requestId: 'saved-feedback',
    action: atomic ? ('ACCEPT' as const) : ('KEEP_CURRENT' as const),
  };
  const action: PendingAction = atomic
    ? {
        kind: 'save-decision',
        request: {
          textUnitId: row.id,
          tmTextUnitId: 20,
          target: 'Updated translation',
          comment: null,
          status: 'APPROVED',
          includedInLocalizedFile: true,
          decisionState: 'DECIDED',
          agentReview,
          reopenAgentReview: {
            requestKey: 'reopen',
            expectedProposalVersion: source.proposalVersion,
            expectedCurrentVariantId: null,
            expectedSource: source.reviewedSource,
            expectedSourceComment: null,
            expectedCurrentTarget: source.reviewedTarget,
            expectedCurrentStatus: null,
            expectedCurrentIncludedInLocalizedFile: null,
          },
        },
      }
    : {
        kind: 'decision-state',
        request: { textUnitId: row.id, decisionState: 'DECIDED', agentReview },
      };
  return {
    phase: 'succeeded',
    operationId: 41,
    attemptId: 41,
    action,
    originalAction: action,
    textUnit: { ...row, agentReview: successor },
    resolution: 'saved',
  };
}

function unloadIsPrevented() {
  const event = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(event);
  return event.defaultPrevented;
}

describe('feedback drafts across reopened review rounds', () => {
  it('moves a restored note to the verified successor and clears both draft keys after saving', () => {
    const { result, rerender, client } = setup();
    act(() =>
      result.current.updateValues((values) => ({
        ...values,
        explanation: 'Previously saved note',
      })),
    );
    expect(client.getQueryData(feedbackKey(901))).toBeDefined();
    rerender({ proposal: successor, action: idle });
    expect(result.current.values.explanation).toBe('Previously saved note');
    expect(client.getQueryData(feedbackKey(901))).toBeUndefined();
    expect(client.getQueryData(feedbackKey(902))).toBeDefined();
    act(() => result.current.startOperation(41));
    rerender({ proposal: successor, action: savedAction(false) });
    expect(result.current.values).toEqual(EMPTY_AGENT_REVIEW_FEEDBACK);
    expect(client.getQueryCache().findAll({ queryKey: ['review-project-draft'] })).toHaveLength(0);
    expect(unloadIsPrevented()).toBe(false);
  });

  it.each(['', 'Feedback submitted with an edited translation'])(
    'acknowledges an atomic successor receipt and clears the submitted draft: %j',
    (explanation) => {
      const { result, rerender, client } = setup();
      act(() => result.current.updateValues((values) => ({ ...values, explanation })));
      act(() => result.current.startOperation(41));
      expect(client.getQueryData(feedbackKey(901))).toBeDefined();
      rerender({ proposal: successor, action: savedAction(true) });
      expect(result.current.read()?.operation).toBeNull();
      expect(result.current.values).toEqual(EMPTY_AGENT_REVIEW_FEEDBACK);
      expect(client.getQueryCache().findAll({ queryKey: ['review-project-draft'] })).toHaveLength(
        0,
      );
      expect(unloadIsPrevented()).toBe(false);
    },
  );

  it('keeps edits made after submission under the successor without leaving the old draft', () => {
    const { result, rerender, client } = setup();
    act(() => result.current.updateValues((values) => ({ ...values, explanation: 'Submitted' })));
    act(() => result.current.startOperation(41));
    act(() =>
      result.current.updateValues((values) => ({ ...values, explanation: 'New unsent note' })),
    );
    rerender({ proposal: successor, action: savedAction(true) });
    expect(result.current.read()?.operation).toBeNull();
    expect(result.current.values.explanation).toBe('New unsent note');
    expect(result.current.dirty).toBe(true);
    expect(client.getQueryData(feedbackKey(901))).toBeUndefined();
    expect(client.getQueryData(feedbackKey(902))).toMatchObject({
      values: { explanation: 'New unsent note' },
    });
    expect(unloadIsPrevented()).toBe(true);
  });

  it('does not delete a newer predecessor draft that differs from the transferred session', () => {
    const { result, rerender, client } = setup();
    act(() => result.current.updateValues((values) => ({ ...values, explanation: 'Local note' })));
    const otherDraft = {
      base: EMPTY_AGENT_REVIEW_FEEDBACK,
      values: { ...EMPTY_AGENT_REVIEW_FEEDBACK, explanation: 'More recent retained work' },
    };
    client.setQueryData(feedbackKey(901), otherDraft);
    rerender({ proposal: successor, action: idle });
    expect(client.getQueryData(feedbackKey(901))).toEqual(otherDraft);
    expect(client.getQueryData(feedbackKey(902))).toMatchObject({
      values: { explanation: 'Local note' },
    });
  });

  it('does not clear a predecessor draft when the incoming proposal has unrelated lineage', () => {
    const { result, rerender, client } = setup();
    act(() => result.current.updateValues((values) => ({ ...values, explanation: 'Unsent note' })));
    rerender({ proposal: { ...successor, previousProposalId: 800 }, action: idle });
    expect(client.getQueryData(feedbackKey(901))).toMatchObject({
      values: { explanation: 'Unsent note' },
    });
  });

  it('does not acknowledge a different feedback receipt as the submitted atomic operation', () => {
    const { result, rerender } = setup();
    act(() => result.current.startOperation(41));
    const action = savedAction(true);
    if (action.phase !== 'succeeded') throw new Error('Expected a saved action');
    action.textUnit.agentReview = { ...successor, lastFeedbackRequestId: 'different-feedback' };
    rerender({ proposal: successor, action });
    expect(result.current.read()?.operation?.id).toBe(41);
  });
});
