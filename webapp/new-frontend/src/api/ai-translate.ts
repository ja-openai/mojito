export type AiTranslateRequest = {
  repositoryName: string;
  targetBcp47tags: string[] | null;
  sourceTextMaxCountPerLocale: number;
  tmTextUnitIds: number[] | null;
  useBatch: boolean;
  useModel: string | null;
  promptSuffix: string | null;
  relatedStringsType: string;
  translateType: string;
  statusFilter: string;
  importStatus: string;
  glossaryName: string | null;
  glossaryTermSource: string | null;
  glossaryTermSourceDescription: string | null;
  glossaryTermTarget: string | null;
  glossaryTermTargetDescription: string | null;
  glossaryTermDoNotTranslate: boolean;
  glossaryTermCaseSensitive: boolean;
  glossaryOnlyMatchedTextUnits: boolean;
  dryRun: boolean;
  timeoutSeconds: number | null;
};

type PollableTaskResponse = {
  id: number;
  isAllFinished?: boolean;
  allFinished?: boolean;
  errorMessage?: string | null;
};

export type PollableTask = {
  id: number;
  isAllFinished: boolean;
  errorMessage?: string | null;
};

type AiTranslateResponse = {
  pollableTask: PollableTaskResponse;
};

export type AiTranslateReportResponse = {
  reportLocaleUrls?: string[] | null;
};

export type AiTranslateReportLocaleResponse = {
  content?: string | null;
};

const DEFAULT_POLL_INTERVAL_MS = 500;

export async function translateRepository(request: AiTranslateRequest): Promise<PollableTask> {
  const response = await postJson<AiTranslateResponse>('/api/proto-ai-translate', request);
  return normalizePollableTask(response.pollableTask);
}

export async function fetchPollableTask(pollableTaskId: number): Promise<PollableTask> {
  const response = await getJson<PollableTaskResponse>(`/api/pollableTasks/${pollableTaskId}`);
  return normalizePollableTask(response);
}

export async function waitForPollableTaskToFinish(
  pollableTaskId: number,
  timeoutMs?: number,
): Promise<PollableTask> {
  const startedAt = Date.now();

  for (;;) {
    if (timeoutMs && Date.now() - startedAt > timeoutMs) {
      throw new Error('Timed out while waiting for AI translate to finish');
    }

    const pollableTask = await fetchPollableTask(pollableTaskId);
    if (pollableTask.isAllFinished) {
      return pollableTask;
    }

    await delay(DEFAULT_POLL_INTERVAL_MS);
  }
}

export async function fetchAiTranslateReport(
  pollableTaskId: number,
): Promise<AiTranslateReportResponse> {
  return getJson<AiTranslateReportResponse>(`/api/proto-ai-translate/report/${pollableTaskId}`);
}

export async function fetchAiTranslateReportLocale(
  pollableTaskId: number,
  localeTag: string,
): Promise<AiTranslateReportLocaleResponse> {
  return getJson<AiTranslateReportLocaleResponse>(
    `/api/proto-ai-translate/report/${pollableTaskId}/locale/${localeTag}`,
  );
}

export async function fetchAiTranslateReportPath(
  path: string,
): Promise<AiTranslateReportLocaleResponse> {
  const trimmed = path.replace(/^\/+/, '');
  if (trimmed.startsWith('api/')) {
    return getJson<AiTranslateReportLocaleResponse>(`/${trimmed}`);
  }
  if (trimmed.startsWith('proto-ai-translate/')) {
    return getJson<AiTranslateReportLocaleResponse>(`/api/${trimmed}`);
  }
  return getJson<AiTranslateReportLocaleResponse>(`/api/proto-ai-translate/${trimmed}`);
}

function normalizePollableTask(task: PollableTaskResponse): PollableTask {
  const rawMessage = task.errorMessage;
  const normalizedMessage =
    typeof rawMessage === 'string' ? rawMessage : rawMessage ? JSON.stringify(rawMessage) : null;

  return {
    id: task.id,
    isAllFinished: task.isAllFinished ?? task.allFinished ?? false,
    errorMessage: normalizedMessage,
  };
}

async function postJson<TResponse>(url: string, body: unknown): Promise<TResponse> {
  const response = await fetch(url, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(body),
  });

  const text = await response.text();
  if (!response.ok) {
    const error: Error & { status?: number } = new Error(
      text || `Request failed with status ${response.status}`,
    );
    error.status = response.status;
    throw error;
  }
  return text ? (JSON.parse(text) as TResponse) : (undefined as TResponse);
}

async function getJson<TResponse>(url: string): Promise<TResponse> {
  const response = await fetch(url, {
    method: 'GET',
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
    },
  });

  const text = await response.text();
  if (!response.ok) {
    throw new Error(text || `Request failed with status ${response.status}`);
  }
  return text ? (JSON.parse(text) as TResponse) : (undefined as TResponse);
}

const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
