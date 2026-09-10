import { useLayoutEffect } from 'react';

import type { ApiAgentReviewContext } from '../../api/agent-reviews';
import { EMPTY_AGENT_REVIEW_FEEDBACK } from './agent-review-feedback';
import type { ReviewProjectActionState } from './review-project-mutations';
import { useReviewProjectFormDraft } from './useReviewProjectFormDraft';

export function useAgentReviewFeedback(
  username: string,
  projectId: number,
  textUnitId: number,
  proposal: ApiAgentReviewContext | null | undefined,
  action: ReviewProjectActionState,
) {
  const draft = useReviewProjectFormDraft(
    username,
    projectId,
    textUnitId,
    `agent-feedback:${proposal?.proposalId ?? 'none'}`,
    EMPTY_AGENT_REVIEW_FEEDBACK,
  );
  const { cancelOperation, finishOperation } = draft;
  useLayoutEffect(() => {
    if (action.phase === 'idle') {
      cancelOperation();
    } else if (
      action.phase === 'succeeded' &&
      action.textUnit.id === textUnitId &&
      'agentReview' in action.action.request &&
      action.action.request.agentReview?.proposalId === proposal?.proposalId
    ) {
      finishOperation(action.operationId, EMPTY_AGENT_REVIEW_FEEDBACK);
    }
  }, [action, cancelOperation, finishOperation, proposal?.proposalId, textUnitId]);
  return draft;
}
