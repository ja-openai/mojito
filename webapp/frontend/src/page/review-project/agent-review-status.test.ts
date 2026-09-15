import { describe, expect, it } from 'vitest';

import type { ApiAgentReviewContext } from '../../api/agent-reviews';
import type { ApiReviewProjectTextUnit } from '../../api/review-projects';
import {
  getAgentReviewStatus,
  matchesAgentReviewStatusFilter,
  parseAgentReviewStatusFilter,
} from './agent-review-status';

function row(
  proposal: Partial<ApiAgentReviewContext> = {},
  decision: ApiReviewProjectTextUnit['reviewProjectTextUnitDecision'] = null,
): ApiReviewProjectTextUnit {
  return {
    id: 1,
    tmTextUnit: null,
    baselineTmTextUnitVariant: null,
    currentTmTextUnitVariant: {
      id: 10,
      content: 'Current translation',
      status: 'APPROVED',
      includedInLocalizedFile: true,
    },
    agentReview: {
      proposalId: 1,
      proposalRevision: 1,
      proposalVersion: 1,
      findingId: 'finding',
      runId: 1,
      reviewType: 'TRANSLATION_QUALITY',
      reviewedSource: 'Source',
      reviewedTarget: 'Current translation',
      proposedTarget: 'Suggested translation',
      rationale: 'A translation issue',
      category: 'OBVIOUS_ERROR',
      verificationStatus: 'READY',
      disposition: 'ROUTED',
      stale: false,
      ...proposal,
    },
    reviewProjectTextUnitDecision: decision,
  };
}

describe('incident review queue status', () => {
  it('keeps a finding awaiting review even when its current translation is accepted', () => {
    expect(getAgentReviewStatus(row())).toBe('PENDING');
  });

  it('distinguishes an agent follow-up and response from a completed human review', () => {
    const decision = { decisionState: 'DECIDED' };
    expect(getAgentReviewStatus(row({ disposition: 'FOLLOW_UP' }, decision))).toBe('FOLLOW_UP');
    expect(
      getAgentReviewStatus(row({ disposition: 'FOLLOW_UP', canReconsider: true }, decision)),
    ).toBe('AGENT_RESPONDED');
    expect(getAgentReviewStatus(row({ disposition: 'SUPERSEDED' }, decision))).toBe('SUPERSEDED');
  });

  it('recognizes resolved and legacy decided rows without calling stale history pending', () => {
    expect(getAgentReviewStatus(row({ disposition: 'RESOLVED', stale: true }))).toBe('DECIDED');
    expect(getAgentReviewStatus(row({}, { decisionState: 'DECIDED' }))).toBe('DECIDED');
    expect(getAgentReviewStatus(row({}, { decisionTmTextUnitVariant: { id: 10 } }))).toBe(
      'DECIDED',
    );
    expect(
      getAgentReviewStatus(
        row({}, { decisionState: 'PENDING', decisionTmTextUnitVariant: { id: 10 } }),
      ),
    ).toBe('PENDING');
    expect(getAgentReviewStatus(row({ stale: true }))).toBe('STALE');
  });

  it('preserves old state links and accepts the review statuses shown on queue rows', () => {
    for (const status of [
      'PENDING',
      'DECIDED',
      'FOLLOW_UP',
      'AGENT_RESPONDED',
      'STALE',
      'SUPERSEDED',
    ]) {
      expect(parseAgentReviewStatusFilter(status)).toBe(status);
    }
    expect(parseAgentReviewStatusFilter('APPROVED')).toBe('all');
    expect(parseAgentReviewStatusFilter(null)).toBe('all');
  });

  it('includes changed proposals in the pending queue and allows narrowing to changed only', () => {
    const changed = row({ stale: true });
    expect(matchesAgentReviewStatusFilter(changed, 'PENDING')).toBe(true);
    expect(matchesAgentReviewStatusFilter(changed, 'STALE')).toBe(true);
    expect(matchesAgentReviewStatusFilter(row(), 'STALE')).toBe(false);
    expect(
      matchesAgentReviewStatusFilter(
        row({ disposition: 'FOLLOW_UP' }, { decisionState: 'DECIDED' }),
        'DECIDED',
      ),
    ).toBe(false);
    expect(matchesAgentReviewStatusFilter(row({ disposition: 'RESOLVED' }), 'PENDING')).toBe(false);
    expect(matchesAgentReviewStatusFilter(changed, 'all')).toBe(true);
  });
});
