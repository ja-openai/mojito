import { isTransientHttpError, poll } from '../utils/poller';

export type ReviewProjectStatusFilter = 'OPEN' | 'CLOSED';

export type LinguistTimeSpentReportParams = {
  activityAfter?: string | null;
  activityBefore?: string | null;
  status?: ReviewProjectStatusFilter | null;
  translatorUserId?: number | null;
  localeBcp47Tag?: string | null;
  summaryLimit?: number;
  detailLimit?: number;
};

export type LinguistTimeSpentRecomputeRequest = {
  projectCreatedAfter?: string | null;
  projectCreatedBefore?: string | null;
  status?: ReviewProjectStatusFilter | null;
  translatorUserId?: number | null;
  localeBcp47Tag?: string | null;
  limit?: number;
};

export type LinguistTimeSpentSummary = {
  windowCount: number;
  projectCount: number;
  decidedWordCount: number;
  selfReportedSeconds: number | null;
  estimatedActiveSeconds: number;
  rawDecisionSpanSeconds: number;
  projectSpanSeconds?: number;
  pauseSeconds: number;
  pauseCount: number;
  reportedMissingCount: number;
  reviewFlagCount: number;
  lastComputedAt: string | null;
};

export type LinguistTimeSpentLinguistSummary = {
  assignedTranslatorUserId: number | null;
  assignedTranslatorUsername: string | null;
  localeBcp47Tag: string | null;
  metrics: LinguistTimeSpentSummary;
};

export type LinguistTimeSpentTranslatorScorecard = {
  assignedTranslatorUserId: number | null;
  assignedTranslatorUsername: string | null;
  windowCount: number;
  projectCount: number;
  decidedWordCount: number;
  averageAssignedToAcceptedSeconds: number | null;
  notAcceptedCount: number;
  notAcceptedPercent: number;
  missedDeadlineCount: number;
  missedDeadlinePercent: number;
  reportedMissingCount: number;
  reportedMissingPercent: number;
  reviewFlagCount: number;
  reviewFlagPercent: number;
  selfReportedSeconds: number | null;
  estimatedActiveSeconds: number;
  reportedComputedRatio: number | null;
  lastComputedAt: string | null;
};

export type LinguistTimeSpentWindow = {
  id: number;
  assignmentWindowId: number;
  reviewProjectId: number;
  reviewProjectRequestId: number | null;
  reviewProjectRequestName: string | null;
  reviewProjectStatus: string | null;
  localeBcp47Tag: string | null;
  assignedTranslatorUserId: number | null;
  assignedTranslatorUsername: string | null;
  assignmentWindowStartedAt: string | null;
  assignmentAcceptedAt: string | null;
  assignmentWindowEndedAt: string | null;
  assignmentWindowEndReason: string | null;
  projectCreatedDate: string | null;
  projectDueDate: string | null;
  firstDecisionAt: string | null;
  lastDecisionAt: string | null;
  assignedToAcceptedSeconds: number | null;
  acceptedToFirstDecisionSeconds: number | null;
  textUnitCount: number;
  wordCount: number;
  decidedCount: number;
  decidedWordCount: number;
  selfReportedSeconds: number;
  reportedComputedDeltaSeconds: number | null;
  reportedComputedRatio: number | null;
  estimatedActiveSeconds: number;
  rawDecisionSpanSeconds: number;
  projectSpanSeconds: number;
  pauseSeconds: number;
  pauseCount: number;
  reviewFlag: string;
  reportedMissing: boolean;
  attributionConfidence: string;
  finalizedAt: string | null;
  computedAt: string;
};

export type LinguistTimeSpentReport = {
  summary: LinguistTimeSpentSummary;
  translatorScorecards: LinguistTimeSpentTranslatorScorecard[];
  linguists: LinguistTimeSpentLinguistSummary[];
  windows: LinguistTimeSpentWindow[];
};

export type LinguistTimeSpentRecomputeResponse = {
  matchedProjectCount: number;
  computedWindowCount: number;
  backfilledWindowCount: number;
};

type LinguistTimeSpentHybridError = {
  type?: string | null;
  message?: string | null;
  stackTrace?: string | null;
};

type LinguistTimeSpentPollingToken = {
  requestId: string;
  recommendedPollingDurationMillis?: number | null;
};

type LinguistTimeSpentReportHybridResponse = {
  results?: LinguistTimeSpentReport | null;
  pollingToken?: LinguistTimeSpentPollingToken | null;
  error?: LinguistTimeSpentHybridError | null;
};

type LinguistTimeSpentRecomputeHybridResponse = {
  results?: LinguistTimeSpentRecomputeResponse | null;
  pollingToken?: LinguistTimeSpentPollingToken | null;
  error?: LinguistTimeSpentHybridError | null;
};

const LINGUIST_TIME_SPENT_POLL_INTERVAL_MS = 1000;
const LINGUIST_TIME_SPENT_MAX_POLL_INTERVAL_MS = 8000;
const LINGUIST_TIME_SPENT_TIMEOUT_MS = 120_000;

const jsonHeaders = {
  'Content-Type': 'application/json',
  Accept: 'application/json',
};

function appendParam(
  params: URLSearchParams,
  key: string,
  value: string | number | boolean | null | undefined,
) {
  if (value === null || value === undefined || value === '') {
    return;
  }
  params.set(key, String(value));
}

export async function fetchLinguistTimeSpentReport(
  filters: LinguistTimeSpentReportParams,
): Promise<LinguistTimeSpentReport> {
  const params = new URLSearchParams();
  appendParam(params, 'activityAfter', filters.activityAfter);
  appendParam(params, 'activityBefore', filters.activityBefore);
  appendParam(params, 'status', filters.status ?? 'CLOSED');
  appendParam(params, 'translatorUserId', filters.translatorUserId);
  appendParam(params, 'localeBcp47Tag', filters.localeBcp47Tag);
  appendParam(params, 'summaryLimit', filters.summaryLimit);
  appendParam(params, 'detailLimit', filters.detailLimit);

  const response = await fetch(`/api/admin/linguist-time-spent?${params.toString()}`, {
    method: 'GET',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  const payload = (await response.json()) as LinguistTimeSpentReportHybridResponse;
  if (!response.ok) {
    throw new Error(payload.error?.message || 'Failed to load linguist time spent report');
  }
  return resolveReportHybridResponse(payload);
}

export async function recomputeLinguistTimeSpentReport(
  payload: LinguistTimeSpentRecomputeRequest,
): Promise<LinguistTimeSpentRecomputeResponse> {
  const response = await fetch('/api/admin/linguist-time-spent/recompute', {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  const result = (await response.json()) as LinguistTimeSpentRecomputeHybridResponse;
  if (!response.ok) {
    throw new Error(result.error?.message || 'Failed to recompute linguist time spent stats');
  }
  return resolveRecomputeHybridResponse(result);
}

async function resolveReportHybridResponse(
  response: LinguistTimeSpentReportHybridResponse,
): Promise<LinguistTimeSpentReport> {
  if (response.results) {
    return response.results;
  }
  if (response.error) {
    throw new Error(response.error.message || 'Failed to load linguist time spent report');
  }
  if (response.pollingToken) {
    return pollForReportResults(response.pollingToken);
  }
  throw new Error('Unexpected linguist time spent report response');
}

async function resolveRecomputeHybridResponse(
  response: LinguistTimeSpentRecomputeHybridResponse,
): Promise<LinguistTimeSpentRecomputeResponse> {
  if (response.results) {
    return response.results;
  }
  if (response.error) {
    throw new Error(response.error.message || 'Failed to recompute linguist time spent stats');
  }
  if (response.pollingToken) {
    return pollForRecomputeResults(response.pollingToken);
  }
  throw new Error('Unexpected linguist time spent recompute response');
}

async function pollForReportResults(
  pollingToken: LinguistTimeSpentPollingToken,
): Promise<LinguistTimeSpentReport> {
  return pollForHybridResult(
    `/api/admin/linguist-time-spent/report/results/${pollingToken.requestId}`,
    pollingToken,
    resolveReportHybridResponse,
    'Timed out while loading linguist time spent report',
  );
}

async function pollForRecomputeResults(
  pollingToken: LinguistTimeSpentPollingToken,
): Promise<LinguistTimeSpentRecomputeResponse> {
  return pollForHybridResult(
    `/api/admin/linguist-time-spent/recompute/results/${pollingToken.requestId}`,
    pollingToken,
    resolveRecomputeHybridResponse,
    'Timed out while recomputing linguist time spent stats',
  );
}

async function pollForHybridResult<T, R extends { error?: LinguistTimeSpentHybridError | null }>(
  url: string,
  pollingToken: LinguistTimeSpentPollingToken,
  resolve: (response: R) => Promise<T>,
  timeoutMessage: string,
): Promise<T> {
  const timeoutMs =
    typeof pollingToken.recommendedPollingDurationMillis === 'number' &&
    pollingToken.recommendedPollingDurationMillis > 0
      ? pollingToken.recommendedPollingDurationMillis
      : LINGUIST_TIME_SPENT_TIMEOUT_MS;

  const result = await poll<T | null>(
    async () => {
      const response = await fetch(url, {
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      });
      const payload = (await response.json()) as R;
      if (!response.ok) {
        throw new Error(payload.error?.message || `Request failed with status ${response.status}`);
      }
      if ('pollingToken' in payload && payload.pollingToken) {
        return null;
      }
      return resolve(payload);
    },
    {
      intervalMs: LINGUIST_TIME_SPENT_POLL_INTERVAL_MS,
      maxIntervalMs: LINGUIST_TIME_SPENT_MAX_POLL_INTERVAL_MS,
      timeoutMs,
      timeoutMessage,
      isTransientError: isTransientHttpError,
      shouldStop: (value) => value !== null,
    },
  );
  if (result == null) {
    throw new Error(timeoutMessage);
  }
  return result;
}
