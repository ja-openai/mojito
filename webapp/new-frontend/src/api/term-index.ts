import { normalizePollableTaskErrorMessage } from '../utils/pollableTask';
import { isTransientHttpError, poll } from '../utils/poller';

type PollableTaskResponse = {
  id: number;
  isAllFinished?: boolean;
  allFinished?: boolean;
  errorMessage?: string | null;
};

const TERM_ENTRY_SEARCH_POLL_TIMEOUT_MS = 120_000;
const DEFAULT_POLL_INTERVAL_MS = 1000;
const MAX_POLL_INTERVAL_MS = 8000;

export type ApiTermIndexPollableTask = {
  id: number;
  isAllFinished: boolean;
  errorMessage?: string | null;
};

export type ApiTermIndexRefreshRequest = {
  repositoryIds: number[];
  fullRefresh?: boolean | null;
  batchSize?: number | null;
  excludeGlossaryRepositories?: boolean | null;
};

export type ApiTermIndexEntry = {
  id: number;
  termIndexCandidateId?: number | null;
  normalizedKey: string;
  displayTerm: string;
  sourceLocaleTag: string;
  createdDate?: string | null;
  lastModifiedDate?: string | null;
  reviewStatus: ApiTermIndexReviewStatus;
  reviewAuthority: ApiTermIndexReviewAuthority;
  reviewReason?: string | null;
  reviewRationale?: string | null;
  reviewConfidence?: number | null;
  occurrenceCount: number;
  repositoryCount: number;
  lastOccurrenceAt?: string | null;
};

export type ApiTermIndexReviewStatus = 'TO_REVIEW' | 'ACCEPTED' | 'REJECTED' | (string & {});

export type ApiTermIndexReviewStatusFilter =
  | 'NON_REJECTED'
  | 'TO_REVIEW'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'ALL';

export type ApiTermIndexReviewAuthority = 'DEFAULT' | 'AI' | 'HUMAN' | (string & {});

export type ApiTermIndexEntriesResponse = {
  entries: ApiTermIndexEntry[];
};

export type ApiTermIndexBatchReviewResponse = {
  updatedEntryCount: number;
};

export type ApiGenerateTermIndexCandidatesRequest = {
  termIndexEntryIds: number[];
  repositoryIds?: number[];
  definition?: string | null;
  rationale?: string | null;
  termType?: string | null;
  partOfSpeech?: string | null;
  enforcement?: string | null;
  doNotTranslate?: boolean | null;
  confidence?: number | null;
  reviewStatus?: ApiTermIndexReviewStatus | null;
  reviewReason?: string | null;
  reviewRationale?: string | null;
  reviewConfidence?: number | null;
};

export type ApiGenerateTermIndexCandidatesResponse = {
  candidateCount: number;
  createdCandidateCount: number;
  updatedCandidateCount: number;
  candidates: Array<{
    termIndexCandidateId: number;
    termIndexExtractedTermId?: number | null;
    term: string;
    normalizedKey: string;
  }>;
};

type ApiTermIndexEntriesHybridResponse = {
  results?: ApiTermIndexEntriesResponse | null;
  pollingToken?: {
    requestId: string;
    recommendedPollingDurationMillis?: number;
  } | null;
  error?: {
    message?: string;
  } | null;
};

export type ApiTermIndexOccurrence = {
  id: number;
  repositoryId: number;
  repositoryName: string;
  assetId?: number | null;
  assetPath?: string | null;
  tmTextUnitId: number;
  textUnitName?: string | null;
  sourceText?: string | null;
  matchedText: string;
  startIndex: number;
  endIndex: number;
  extractorId: string;
  extractionMethod: string;
  confidence?: number | null;
  createdDate?: string | null;
};

export type ApiTermIndexOccurrencesResponse = {
  occurrences: ApiTermIndexOccurrence[];
};

export type ApiTermIndexCursor = {
  repositoryId: number;
  repositoryName: string;
  status: string;
  lastProcessedCreatedAt?: string | null;
  lastProcessedTmTextUnitId?: number | null;
  lastSuccessfulScanAt?: string | null;
  leaseOwner?: string | null;
  leaseExpiresAt?: string | null;
  currentRefreshRunId?: number | null;
  errorMessage?: string | null;
};

export type ApiTermIndexRefreshRun = {
  id: number;
  status: string;
  requestedRepositoryIds?: string | null;
  processedTextUnitCount: number;
  extractedTermCount: number;
  occurrenceCount: number;
  startedAt?: string | null;
  completedAt?: string | null;
  errorMessage?: string | null;
};

export type ApiTermIndexStatus = {
  cursors: ApiTermIndexCursor[];
  recentRuns: ApiTermIndexRefreshRun[];
  extractionMethods: string[];
};

type StartRefreshResponse = {
  pollableTask: PollableTaskResponse;
};

async function getErrorMessage(response: Response, fallbackMessage: string) {
  const text = await response.text().catch(() => '');
  if (!text) {
    return fallbackMessage;
  }

  try {
    const parsed = JSON.parse(text) as { message?: string; error?: string };
    if (parsed.message?.trim()) {
      return parsed.message.trim();
    }
    if (parsed.error?.trim()) {
      return parsed.error.trim();
    }
  } catch {
    // Fall through to raw text for non-JSON errors.
  }

  return text || fallbackMessage;
}

function normalizePollableTask(task: PollableTaskResponse): ApiTermIndexPollableTask {
  const rawMessage = task.errorMessage;
  const normalizedMessage =
    typeof rawMessage === 'string' ? rawMessage : rawMessage ? JSON.stringify(rawMessage) : null;

  return {
    id: task.id,
    isAllFinished: task.isAllFinished ?? task.allFinished ?? false,
    errorMessage: normalizedMessage,
  };
}

function appendRepositoryParams(params: URLSearchParams, repositoryIds?: number[]) {
  repositoryIds?.forEach((repositoryId) => {
    if (Number.isFinite(repositoryId)) {
      params.append('repositoryId', String(repositoryId));
    }
  });
}

export async function startTermIndexRefresh(
  request: ApiTermIndexRefreshRequest,
): Promise<ApiTermIndexPollableTask> {
  const response = await fetch('/api/glossary-term-index/refresh', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(await getErrorMessage(response, 'Failed to start term index refresh'));
  }

  const payload = (await response.json()) as StartRefreshResponse;
  return normalizePollableTask(payload.pollableTask);
}

export async function fetchTermIndexRefreshTask(
  pollableTaskId: number,
): Promise<ApiTermIndexPollableTask> {
  const response = await fetch(`/api/pollableTasks/${pollableTaskId}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    throw new Error(await getErrorMessage(response, 'Failed to poll term index refresh'));
  }

  return normalizePollableTask((await response.json()) as PollableTaskResponse);
}

export async function waitForTermIndexRefreshTask(
  pollableTaskId: number,
  timeoutMs = 300_000,
): Promise<ApiTermIndexPollableTask> {
  return poll(() => fetchTermIndexRefreshTask(pollableTaskId), {
    intervalMs: DEFAULT_POLL_INTERVAL_MS,
    maxIntervalMs: MAX_POLL_INTERVAL_MS,
    timeoutMs,
    timeoutMessage: 'Timed out while waiting for term index refresh',
    isTransientError: isTransientHttpError,
    shouldStop: (task) => task.isAllFinished,
  }).then((task) => {
    if (task.errorMessage) {
      throw new Error(normalizePollableTaskErrorMessage(task.errorMessage));
    }
    return task;
  });
}

export async function fetchTermIndexEntries(options?: {
  repositoryIds?: number[];
  search?: string;
  extractionMethod?: string | null;
  reviewStatus?: ApiTermIndexReviewStatusFilter | null;
  minOccurrences?: number;
  limit?: number;
}): Promise<ApiTermIndexEntriesResponse> {
  const response = await fetch('/api/glossary-term-index/entries/search-hybrid', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify({
      repositoryIds: options?.repositoryIds ?? [],
      search: options?.search?.trim() || null,
      extractionMethod: options?.extractionMethod?.trim() || null,
      reviewStatus: options?.reviewStatus ?? null,
      minOccurrences: options?.minOccurrences ?? null,
      limit: options?.limit ?? null,
    }),
  });

  const payload = await parseTermIndexEntriesHybridResponse(
    response,
    'Failed to load term index entries',
  );

  if (payload?.results) {
    return payload.results;
  }

  if (payload?.error) {
    throw createNonTransientError(payload.error.message || 'Failed to load term index entries');
  }

  if (payload?.pollingToken) {
    return pollForTermIndexEntries(payload.pollingToken);
  }

  throw new Error('Unexpected term index entry search response');
}

export async function updateTermIndexEntryReview(
  termIndexEntryId: number,
  request: {
    reviewStatus: ApiTermIndexReviewStatus;
    reviewReason?: string | null;
    reviewRationale?: string | null;
    reviewConfidence?: number | null;
  },
): Promise<ApiTermIndexEntry> {
  const response = await fetch(`/api/glossary-term-index/entries/${termIndexEntryId}/review`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(await getErrorMessage(response, 'Failed to update term review'));
  }

  return (await response.json()) as ApiTermIndexEntry;
}

export async function updateTermIndexEntryReviews(
  termIndexEntryIds: number[],
  request: {
    reviewStatus: ApiTermIndexReviewStatus;
    reviewReason?: string | null;
    reviewRationale?: string | null;
    reviewConfidence?: number | null;
  },
): Promise<ApiTermIndexBatchReviewResponse> {
  const response = await fetch('/api/glossary-term-index/entries/review', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify({
      termIndexEntryIds,
      ...request,
    }),
  });

  if (!response.ok) {
    throw new Error(await getErrorMessage(response, 'Failed to update term reviews'));
  }

  return (await response.json()) as ApiTermIndexBatchReviewResponse;
}

export async function generateTermIndexCandidatesFromEntries(
  request: ApiGenerateTermIndexCandidatesRequest,
): Promise<ApiGenerateTermIndexCandidatesResponse> {
  const response = await fetch('/api/glossary-term-index/candidates/generate', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(await getErrorMessage(response, 'Failed to generate term candidates'));
  }

  return (await response.json()) as ApiGenerateTermIndexCandidatesResponse;
}

export async function fetchTermIndexOccurrences(
  termIndexEntryId: number,
  options?: {
    repositoryIds?: number[];
    extractionMethod?: string | null;
    limit?: number;
  },
): Promise<ApiTermIndexOccurrencesResponse> {
  const params = new URLSearchParams();
  appendRepositoryParams(params, options?.repositoryIds);
  if (options?.extractionMethod?.trim()) {
    params.set('extractionMethod', options.extractionMethod.trim());
  }
  if (typeof options?.limit === 'number') {
    params.set('limit', String(options.limit));
  }

  const response = await fetch(
    `/api/glossary-term-index/entries/${termIndexEntryId}/occurrences${
      params.size ? `?${params.toString()}` : ''
    }`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    throw new Error(await getErrorMessage(response, 'Failed to load term index occurrences'));
  }

  return (await response.json()) as ApiTermIndexOccurrencesResponse;
}

async function pollForTermIndexEntries(pollingToken: {
  requestId: string;
  recommendedPollingDurationMillis?: number;
}): Promise<ApiTermIndexEntriesResponse> {
  const timeoutMs = getTermEntrySearchPollingTimeoutMs(
    pollingToken.recommendedPollingDurationMillis,
  );

  const results = await poll<ApiTermIndexEntriesResponse | null>(
    async () => {
      const response = await fetch(
        `/api/glossary-term-index/entries/search-hybrid/results/${pollingToken.requestId}`,
        {
          credentials: 'same-origin',
          headers: { Accept: 'application/json' },
        },
      );
      const payload = await parseTermIndexEntriesHybridResponse(
        response,
        'Failed to load term index entries',
      );

      if (payload?.results) {
        return payload.results;
      }
      if (payload?.error) {
        throw createNonTransientError(payload.error.message || 'Failed to load term index entries');
      }
      return null;
    },
    {
      intervalMs: DEFAULT_POLL_INTERVAL_MS,
      maxIntervalMs: MAX_POLL_INTERVAL_MS,
      timeoutMs,
      timeoutMessage: 'Timed out while loading term index entries',
      isTransientError: isTransientHttpError,
      shouldStop: (response) => response !== null,
    },
  );

  return results ?? { entries: [] };
}

async function parseTermIndexEntriesHybridResponse(
  response: Response,
  fallbackMessage: string,
): Promise<ApiTermIndexEntriesHybridResponse | null> {
  const text = await response.text();
  const payload = parseTermIndexEntriesHybridPayload(text);

  if (!response.ok) {
    if (payload?.error?.message) {
      throw createNonTransientError(payload.error.message);
    }
    const error: Error & { status?: number } = new Error(
      text || fallbackMessage || `Request failed with status ${response.status}`,
    );
    error.status = response.status;
    throw error;
  }

  return payload;
}

function parseTermIndexEntriesHybridPayload(text: string) {
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text) as ApiTermIndexEntriesHybridResponse;
  } catch {
    return null;
  }
}

function getTermEntrySearchPollingTimeoutMs(recommended?: number) {
  if (!Number.isFinite(recommended) || typeof recommended !== 'number' || recommended <= 0) {
    return TERM_ENTRY_SEARCH_POLL_TIMEOUT_MS;
  }
  return recommended;
}

function createNonTransientError(message: string) {
  const error: Error & { isTransient?: boolean } = new Error(message);
  error.isTransient = false;
  return error;
}

export async function fetchTermIndexStatus(options?: {
  repositoryIds?: number[];
  recentRunLimit?: number;
}): Promise<ApiTermIndexStatus> {
  const params = new URLSearchParams();
  appendRepositoryParams(params, options?.repositoryIds);
  if (typeof options?.recentRunLimit === 'number') {
    params.set('recentRunLimit', String(options.recentRunLimit));
  }

  const response = await fetch(
    `/api/glossary-term-index/status${params.size ? `?${params.toString()}` : ''}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    throw new Error(await getErrorMessage(response, 'Failed to load term index status'));
  }

  return (await response.json()) as ApiTermIndexStatus;
}
