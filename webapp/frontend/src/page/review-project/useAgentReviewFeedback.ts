import { useQueryClient } from '@tanstack/react-query';
import { useLayoutEffect, useRef } from 'react';

import type { ApiAgentReviewContext } from '../../api/agent-reviews';
import {
  type AgentReviewFeedbackValues,
  EMPTY_AGENT_REVIEW_FEEDBACK,
} from './agent-review-feedback';
import type { ReviewProjectActionState } from './review-project-mutations';
import { useReviewProjectFormDraft } from './useReviewProjectFormDraft';

type RetainedFeedback = { base: AgentReviewFeedbackValues; values: AgentReviewFeedbackValues };

function sameFeedback(left: AgentReviewFeedbackValues, right: AgentReviewFeedbackValues) {
  return (
    left.originalAssessment === right.originalAssessment &&
    left.suggestionAssessment === right.suggestionAssessment &&
    left.explanation === right.explanation
  );
}

export function useAgentReviewFeedback(
  username: string,
  projectId: number,
  textUnitId: number,
  proposal: ApiAgentReviewContext | null | undefined,
  action: ReviewProjectActionState,
) {
  const queryClient = useQueryClient();
  const previousOwner = useRef({ username, projectId, textUnitId, proposal });
  const draft = useReviewProjectFormDraft(
    username,
    projectId,
    textUnitId,
    `agent-feedback:${proposal?.proposalId ?? 'none'}`,
    EMPTY_AGENT_REVIEW_FEEDBACK,
  );
  useLayoutEffect(() => {
    const previous = previousOwner.current;
    previousOwner.current = { username, projectId, textUnitId, proposal };
    if (
      previous.username !== username ||
      previous.projectId !== projectId ||
      previous.textUnitId !== textUnitId ||
      !previous.proposal ||
      proposal?.previousProposalId !== previous.proposal.proposalId ||
      proposal.proposalRevision !== previous.proposal.proposalRevision + 1
    )
      return;
    const previousKey = [
      'review-project-draft',
      username,
      projectId,
      textUnitId,
      `agent-feedback:${previous.proposal.proposalId}`,
    ];
    const nextKey = [
      'review-project-draft',
      username,
      projectId,
      textUnitId,
      `agent-feedback:${proposal.proposalId}`,
    ];
    const previousDraft = queryClient.getQueryData<RetainedFeedback>(previousKey);
    const nextDraft = queryClient.getQueryData<RetainedFeedback>(nextKey);
    // The form hook retained the same session under its successor key before this effect.
    // Remove only that verified copy, never another retained or more recent user's draft.
    if (
      previousDraft &&
      nextDraft &&
      sameFeedback(previousDraft.base, nextDraft.base) &&
      sameFeedback(previousDraft.values, nextDraft.values)
    ) {
      queryClient.removeQueries({ queryKey: previousKey, exact: true });
    }
  }, [projectId, proposal, queryClient, textUnitId, username]);
  const { cancelOperation, finishOperation } = draft;
  useLayoutEffect(() => {
    if (action.phase === 'idle') {
      cancelOperation();
    } else if (
      action.phase === 'succeeded' &&
      action.textUnit.id === textUnitId &&
      'agentReview' in action.action.request &&
      action.action.request.agentReview
    ) {
      const expected = action.action.request.agentReview;
      const saved = action.textUnit.agentReview;
      const savedSuccessor =
        action.action.kind === 'save-decision' &&
        action.action.request.reopenAgentReview != null &&
        saved?.proposalId === proposal?.proposalId &&
        saved?.previousProposalId === expected.proposalId &&
        saved.proposalRevision === expected.proposalRevision + 1 &&
        saved.lastFeedbackRequestId === expected.requestId;
      if (expected.proposalId === proposal?.proposalId || savedSuccessor) {
        finishOperation(action.operationId, EMPTY_AGENT_REVIEW_FEEDBACK);
      }
    }
  }, [action, cancelOperation, finishOperation, proposal?.proposalId, textUnitId]);
  return draft;
}
