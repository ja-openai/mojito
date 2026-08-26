export type ApiAiTranslateEvaluationSummary = {
  reviewedCount: number;
  exactAcceptedCount: number;
  editedCount: number;
  exactAcceptanceRate: number;
  averageNormalizedEditDistance: number;
};

export type ApiAiTranslateEvaluationCohort = {
  promptFingerprint: string | null;
  model: string | null;
  reasoningEffort: string | null;
  textVerbosity: string | null;
  localeTag: string;
  summary: ApiAiTranslateEvaluationSummary;
};

export type ApiAiTranslateEvaluationExample = {
  attemptId: number;
  reviewedAt: string | null;
  reviewProjectId: number;
  repositoryId: number;
  repositoryName: string;
  tmTextUnitId: number;
  textUnitName: string | null;
  source: string | null;
  sourceDescription: string | null;
  localeTag: string;
  model: string | null;
  promptFingerprint: string | null;
  reasoningEffort: string | null;
  textVerbosity: string | null;
  aiTarget: string | null;
  acceptedTarget: string | null;
  decisionNotes: string | null;
  exactAccepted: boolean;
  normalizedEditDistance: number | null;
};

export type ApiAiTranslateEvaluationReport = {
  summary: ApiAiTranslateEvaluationSummary;
  cohorts: ApiAiTranslateEvaluationCohort[];
  examples: ApiAiTranslateEvaluationExample[];
};

export async function fetchAiTranslateEvaluations(
  limit = 500,
): Promise<ApiAiTranslateEvaluationReport> {
  const response = await fetch(`/api/ai-translate/evaluations?limit=${limit}`, {
    method: 'GET',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load AI translation evaluations');
  }

  return (await response.json()) as ApiAiTranslateEvaluationReport;
}
