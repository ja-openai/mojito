import type { ApiAgentReviewContext } from '../../api/agent-reviews';
import type { AiReviewMessage } from '../../api/ai-review';
import {
  ownReviewProjectAiSuggestions,
  type ReviewProjectClientOwner,
} from '../../api/review-project-client-context';

/** Bind the recorded proposal separately from later AI chat suggestions. */
export function buildAgentReviewSuggestion(
  proposal: Pick<
    ApiAgentReviewContext,
    'proposalId' | 'proposalRevision' | 'proposalVersion' | 'rationale' | 'proposedTarget'
  >,
  owner: ReviewProjectClientOwner,
) {
  if (proposal.proposedTarget === null) return null;
  return ownReviewProjectAiSuggestions(
    [{ content: proposal.proposedTarget, kind: 'correction', explanation: proposal.rationale }],
    {
      kind: 'agent_proposal',
      owner,
      proposalId: proposal.proposalId,
      proposalRevision: proposal.proposalRevision,
    },
  )[0];
}

/** Recorded review data sent separately because the chat service omits assistant-role messages. */
export function buildAgentReviewChatContext(proposal: ApiAgentReviewContext): AiReviewMessage {
  return {
    role: 'user',
    content: JSON.stringify({
      context: 'recorded_incident_review',
      proposalId: proposal.proposalId,
      proposalRevision: proposal.proposalRevision,
      reviewedSource: proposal.reviewedSource,
      reviewedTarget: proposal.reviewedTarget,
      proposedTarget: proposal.proposedTarget,
      rationale: proposal.rationale,
      evidence: proposal.evidence ?? [],
      verificationStatus: proposal.verificationStatus,
      verificationNotes: proposal.verificationNotes ?? null,
      integrityDiagnostics: proposal.integrityDiagnostics ?? null,
      stale: proposal.stale,
    }),
  };
}
