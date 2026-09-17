import type { ApiReviewProjectTextUnit } from '../../api/review-projects';

export function getDecisionState(textUnit: ApiReviewProjectTextUnit): 'PENDING' | 'DECIDED' {
  const decision = textUnit.reviewProjectTextUnitDecision;
  if (decision?.decisionState === 'DECIDED' || decision?.decisionState === 'PENDING') {
    return decision.decisionState;
  }
  return decision?.decisionTmTextUnitVariant?.id != null ? 'DECIDED' : 'PENDING';
}
