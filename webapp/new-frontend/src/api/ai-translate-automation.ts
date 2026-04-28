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

export type ApiAiTranslateLineageAttempt = {
  id: number;
  createdDate: string | null;
  lastModifiedDate: string | null;
  tmTextUnitId: number;
  tmTextUnitName: string | null;
  tmTextUnitVariantId: number | null;
  localeBcp47Tag: string;
  repositoryId: number | null;
  repositoryName: string | null;
  pollableTaskId: number | null;
  aiTranslateRunId: number | null;
  requestGroupId: string | null;
  translateType: string | null;
  model: string | null;
  status: string | null;
  completionId: string | null;
  hasRequestPayload: boolean;
  hasResponsePayload: boolean;
  errorMessage: string | null;
};

export type FetchAiTranslateAutomationRunsParams = {
  repositoryIds?: number[];
  limit?: number;
};

export type FetchAiTranslateLineageAttemptsParams = {
  repositoryIds?: number[];
  pollableTaskIds?: number[];
  limit?: number;
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

export const fetchAiTranslateAutomationRuns = async ({
  repositoryIds = [],
  limit,
}: FetchAiTranslateAutomationRunsParams = {}): Promise<ApiAiTranslateAutomationRun[]> => {
  const params = new URLSearchParams();
  for (const repositoryId of repositoryIds) {
    params.append('repositoryIds', String(repositoryId));
  }
  if (typeof limit === 'number') {
    params.set('limit', String(limit));
  }

  const query = params.toString();
  const response = await fetch(`/api/ai-translate/automation/runs${query ? `?${query}` : ''}`, {
    method: 'GET',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load AI translate automation runs');
  }

  return (await response.json()) as ApiAiTranslateAutomationRun[];
};

export const fetchAiTranslateLineageAttempts = async ({
  repositoryIds = [],
  pollableTaskIds = [],
  limit,
}: FetchAiTranslateLineageAttemptsParams = {}): Promise<ApiAiTranslateLineageAttempt[]> => {
  const params = new URLSearchParams();
  for (const repositoryId of repositoryIds) {
    params.append('repositoryIds', String(repositoryId));
  }
  for (const pollableTaskId of pollableTaskIds) {
    params.append('pollableTaskIds', String(pollableTaskId));
  }
  if (typeof limit === 'number') {
    params.set('limit', String(limit));
  }

  const query = params.toString();
  const response = await fetch(`/api/ai-translate/lineage${query ? `?${query}` : ''}`, {
    method: 'GET',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load AI translate lineage');
  }

  return (await response.json()) as ApiAiTranslateLineageAttempt[];
};
