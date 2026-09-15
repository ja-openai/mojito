import type { ApiReviewProjectTextUnit } from '../../api/review-projects';

export const AGENT_REVIEW_STATUS_LABELS = {
  PENDING: 'Awaiting review',
  DECIDED: 'Reviewed',
  FOLLOW_UP: 'Waiting for agent',
  AGENT_RESPONDED: 'Agent responded',
  STALE: 'Changed since review',
  SUPERSEDED: 'Replaced',
} as const;

export type AgentReviewStatus = keyof typeof AGENT_REVIEW_STATUS_LABELS;
export type AgentReviewStatusFilter = AgentReviewStatus | 'all';

export function getAgentReviewStatus(row: ApiReviewProjectTextUnit): AgentReviewStatus {
  const proposal = row.agentReview;
  if (proposal?.canReconsider) return 'AGENT_RESPONDED';
  if (proposal?.disposition === 'FOLLOW_UP') return 'FOLLOW_UP';
  if (proposal?.disposition === 'SUPERSEDED') return 'SUPERSEDED';

  const decision = row.reviewProjectTextUnitDecision;
  const decided =
    decision?.decisionState === 'DECIDED' ||
    (decision?.decisionState !== 'PENDING' && decision?.decisionTmTextUnitVariant?.id != null);
  if (proposal?.disposition === 'RESOLVED' || decided) return 'DECIDED';
  if (proposal?.stale) return 'STALE';
  return 'PENDING';
}

export function parseAgentReviewStatusFilter(value: string | null): AgentReviewStatusFilter {
  return value != null && Object.prototype.hasOwnProperty.call(AGENT_REVIEW_STATUS_LABELS, value)
    ? (value as AgentReviewStatus)
    : 'all';
}

export function matchesAgentReviewStatusFilter(
  row: ApiReviewProjectTextUnit,
  filter: AgentReviewStatusFilter,
): boolean {
  if (filter === 'all') return true;
  const status = getAgentReviewStatus(row);
  // Changed proposals still need human attention; the dedicated filter narrows that queue.
  return status === filter || (filter === 'PENDING' && status === 'STALE');
}
