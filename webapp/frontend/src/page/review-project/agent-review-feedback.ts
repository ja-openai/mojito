import type { AgentReviewDecision } from '../../api/agent-reviews';

export type AgentReviewFeedbackValues = {
  originalAssessment: NonNullable<AgentReviewDecision['originalAssessment']> | '';
  suggestionAssessment: NonNullable<AgentReviewDecision['suggestionAssessment']> | '';
  explanation: string;
};

export const EMPTY_AGENT_REVIEW_FEEDBACK: AgentReviewFeedbackValues = {
  originalAssessment: '',
  suggestionAssessment: '',
  explanation: '',
};
