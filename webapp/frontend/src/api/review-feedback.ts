export const REVIEW_FEEDBACK_REASONS = {
  TERMINOLOGY: 'Terminology',
  WRONG_MEANING: 'Wrong meaning',
  TONE_STYLE: 'Tone/style',
  GRAMMAR: 'Grammar',
  TOO_LONG: 'Too long',
  CONTEXT: 'Context',
  OTHER: 'Other',
} as const;
export type ReviewFeedbackReason = keyof typeof REVIEW_FEEDBACK_REASONS;
export type ReviewerFeedback = {
  reason?: ReviewFeedbackReason;
  note?: string;
  chatUsed: boolean;
  aiSuggestionUsed: boolean;
};
export type ReviewFeedbackBaseline = {
  target: string | null;
  ai: boolean;
  kind: string;
};
export async function fetchReviewFeedbackBaseline(
  id: number,
  signal?: AbortSignal,
): Promise<ReviewFeedbackBaseline> {
  const response = await fetch(`/api/review-project-text-units/${id}/feedback-baseline`, {
    credentials: 'include',
    signal,
  });
  if (!response.ok) throw new Error('Could not load review feedback context.');
  return (await response.json()) as ReviewFeedbackBaseline;
}

export type ReviewFeedbackPattern = {
  locale: string;
  projectId: number;
  model: string;
  promptVersion: string;
  category: string;
  transformHash: string;
  distinctStrings: number;
  reviewerCount: number;
  opportunities: number;
  correctionRate: number;
  disputedStrings: number;
  candidateStatus: 'READY_FOR_HUMAN_REVIEW' | 'OBSERVE';
  examples: { eventId: number; source: string; baseline: string; accepted: string }[];
};
export async function fetchReviewFeedbackPatterns(): Promise<{
  computedAt: string | null;
  windowSize: number;
  patterns: ReviewFeedbackPattern[];
}> {
  const response = await fetch('/api/review-feedback/patterns', { credentials: 'include' });
  if (!response.ok) throw new Error('Could not load review feedback patterns.');
  return (await response.json()) as {
    computedAt: string | null;
    windowSize: number;
    patterns: ReviewFeedbackPattern[];
  };
}
