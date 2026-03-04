export type ApiAiTranslateAutomationConfig = {
  enabled: boolean;
  repositoryIds: number[];
  sourceTextMaxCountPerLocale: number;
  cronExpression: string | null;
};

export type ApiAiTranslateAutomationRunResult = {
  scheduledRepositoryCount: number;
};

export type ApiAiTranslateAutomationRun = {
  id: number;
  triggerSource: string;
  repositoryId: number | null;
  repositoryName: string | null;
  requestedByUserId: number | null;
  pollableTaskId: number | null;
  model: string | null;
  translateType: string | null;
  relatedStringsType: string | null;
  sourceTextMaxCountPerLocale: number;
  status: string;
  createdAt: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  inputTokens: number;
  cachedInputTokens: number;
  outputTokens: number;
  reasoningTokens: number;
  estimatedCostUsd: string | null;
};

export const fetchAiTranslateAutomationConfig =
  async (): Promise<ApiAiTranslateAutomationConfig> => {
    const response = await fetch('/api/ai-translate/automation', {
      method: 'GET',
      credentials: 'same-origin',
    });

    if (!response.ok) {
      const message = await response.text().catch(() => '');
      throw new Error(message || 'Failed to load AI translate automation settings');
    }

    return (await response.json()) as ApiAiTranslateAutomationConfig;
  };

export const updateAiTranslateAutomationConfig = async (
  payload: ApiAiTranslateAutomationConfig,
): Promise<ApiAiTranslateAutomationConfig> => {
  const response = await fetch('/api/ai-translate/automation', {
    method: 'PUT',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to update AI translate automation settings');
  }

  return (await response.json()) as ApiAiTranslateAutomationConfig;
};

export const runAiTranslateAutomationNow = async (): Promise<ApiAiTranslateAutomationRunResult> => {
  const response = await fetch('/api/ai-translate/automation/run', {
    method: 'POST',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to run AI translate automation');
  }

  return (await response.json()) as ApiAiTranslateAutomationRunResult;
};

export const fetchAiTranslateAutomationRuns = async (): Promise<ApiAiTranslateAutomationRun[]> => {
  const response = await fetch('/api/ai-translate/automation/runs', {
    method: 'GET',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load AI translate automation runs');
  }

  return (await response.json()) as ApiAiTranslateAutomationRun[];
};
