import type { ApiReviewProjectTextUnit } from './review-projects';

export type AgentReviewDecision = {
  proposalId: number;
  proposalRevision: number;
  proposalVersion: number;
  requestId: string;
  action: 'ACCEPT' | 'KEEP_CURRENT' | 'DEFER' | 'REQUEST_REVISION';
  originalAssessment?: 'GOOD' | 'BAD' | 'UNSURE';
  suggestionAssessment?: 'GOOD' | 'INCORRECT' | 'UNNECESSARY' | 'INSUFFICIENT_CONTEXT';
  explanation?: string;
};

export type ApiAgentReviewContext = {
  proposalId: number;
  proposalRevision: number;
  proposalVersion: number;
  findingId: string;
  runId: number;
  reviewType: string;
  reviewedSource: string;
  reviewedTarget: string | null;
  proposedTarget: string | null;
  rationale: string;
  category: string;
  verificationStatus: string;
  verificationNotes?: string | null;
  integrityDiagnostics?: string | null;
  evidence?: Array<{ label: string; url?: string | null }> | null;
  disposition: string;
  stale: boolean;
  canReconsider?: boolean;
  lastFeedbackRequestId?: string | null;
};

export type ApiAgentReviewFeedback = {
  id: number;
  proposalRevision: number;
  actorType: string;
  actorIdentity: string;
  proposalId: number;
  evidenceJson?: string | null;
  finalTarget?: string | null;
  respondsToFeedbackId?: number | null;
  responseProposalId?: number | null;
  createdDate: string;
  action: string;
  originalAssessment?: string | null;
  suggestionAssessment?: string | null;
  explanation?: string | null;
};

export const AGENT_REVIEW_FEEDBACK_QUERY_KEY = 'agent-review-feedback';

export async function fetchAgentReviewFeedback(
  projectId: number,
  proposalId: number,
  signal?: AbortSignal,
) {
  const feedback: ApiAgentReviewFeedback[] = [];
  let afterId = 0;
  const limit = 200;
  for (;;) {
    const response = await fetch(
      `/api/agent-reviews/projects/${projectId}/proposals/${proposalId}/feedback?afterId=${afterId}&limit=${limit}`,
      { credentials: 'include', signal },
    );
    if (!response.ok) throw new Error('Could not load review feedback.');
    const page = (await response.json()) as ApiAgentReviewFeedback[];
    feedback.push(...page);
    if (page.length < limit) return feedback;
    const nextId = page[page.length - 1]?.id;
    if (!Number.isSafeInteger(nextId) || nextId <= afterId)
      throw new Error('Could not load the next page of review feedback.');
    afterId = nextId;
  }
}

/** A proposal outcome never sends translation fields. */
export async function saveAgentReviewOutcome({
  textUnitId,
  ...request
}: {
  textUnitId: number;
  decisionState: 'PENDING' | 'DECIDED';
  expectedCurrentTmTextUnitVariantId?: number | null;
  expectedReviewStateRevision?: string | null;
  agentReview: AgentReviewDecision;
}): Promise<ApiReviewProjectTextUnit> {
  const response = await fetch(`/api/review-project-text-units/${textUnitId}/decision`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  const data: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    const error = new Error(
      data && typeof data === 'object' && 'message' in data && typeof data.message === 'string'
        ? data.message
        : 'Could not save review feedback.',
    ) as Error & { status: number; data: ApiReviewProjectTextUnit | null };
    error.status = response.status;
    error.data = data as ApiReviewProjectTextUnit | null;
    throw error;
  }
  return data as ApiReviewProjectTextUnit;
}
