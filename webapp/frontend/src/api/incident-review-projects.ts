import { normalizePollableTaskErrorMessage } from '../utils/pollableTask';
import { isTransientHttpError, poll } from '../utils/poller';
import type { ApiReviewProjectType } from './review-projects';

export type ReviewSource = 'CURRENT_TRANSLATIONS' | 'INCIDENTS';

export type IncidentReviewProjectRequest = {
  allRepositories?: boolean;
  repositoryIds?: number[] | null;
  reviewFeatureIds?: number[] | null;
  localeTags: string[];
  reviewType?: string | null;
  teamId: number;
  name: string;
  dueDate: string;
  maxWordCountPerProject: number | null;
  maxIncidentCount?: number | null;
  assignTranslator: boolean;
  type: ApiReviewProjectType;
  notes: string | null;
  screenshotImageIds: string[];
};

export type IncidentReviewTaskMode = 'preview' | 'create';

export type IncidentReviewTask = {
  id: number;
  isAllFinished: boolean;
  message?: string | null;
  errorMessage?: string | null;
};

export type IncidentReviewTaskStart = { pollableTaskId: number };

export type IncidentReviewProjectResult = {
  eligibleIncidentCount: number;
  skippedIncidentCount: number;
  projectCount: number;
  localeTags: string[];
  projectIds: number[];
  requestIds: number[];
  skipped: Array<{ incidentId: number; reason: string }>;
  scannedIncidentCount?: number;
  hasMore?: boolean;
  incidentBatches?: number[][];
  limitReached?: boolean;
};

async function submitIncidentReviewRequest(
  path: string,
  request: IncidentReviewProjectRequest,
): Promise<IncidentReviewTaskStart> {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  const result = await readResponse<IncidentReviewTaskStart>(response);
  if (!result || !Number.isSafeInteger(result.pollableTaskId) || result.pollableTaskId <= 0) {
    throw Object.assign(
      new Error(`Incident review did not return a valid task ID (HTTP ${response.status}).`),
      {
        status: response.status,
      },
    );
  }
  return result;
}

export function previewIncidentReviewProjects(request: IncidentReviewProjectRequest) {
  return submitIncidentReviewRequest('/api/incident-review-projects/preview', request);
}

export function createIncidentReviewProjects(request: IncidentReviewProjectRequest) {
  return submitIncidentReviewRequest('/api/incident-review-projects', request);
}

export async function waitForIncidentReviewTask(
  taskId: number,
  onProgress: (task: IncidentReviewTask) => void,
  signal: AbortSignal,
): Promise<{
  task: IncidentReviewTask;
  result: IncidentReviewProjectResult | null;
  outputError?: string;
}> {
  const task = await poll(
    async () => {
      const response = await fetch(`/api/pollableTasks/${taskId}`, { signal });
      const raw = await readResponse<IncidentReviewTask & { allFinished?: boolean }>(response);
      return {
        ...raw,
        isAllFinished: raw.isAllFinished ?? raw.allFinished ?? false,
        errorMessage: normalizePollableTaskErrorMessage(raw.errorMessage) || null,
      };
    },
    {
      intervalMs: 1000,
      maxIntervalMs: 8000,
      timeoutMs: 60 * 60 * 1000,
      timeoutMessage: 'Stopped waiting for incident review. Reconnect to check its progress.',
      isTransientError: (error) => !signal.aborted && isTransientHttpError(error),
      onResult: onProgress,
      shouldStop: (task) => task.isAllFinished,
    },
  );
  // Failed creation can still have committed projects. Read its output before reporting failure.
  try {
    const response = await fetch(`/api/pollableTasks/${taskId}/output`, { signal });
    if (task.errorMessage && response.status === 404) return { task, result: null };
    const result = await readResponse<IncidentReviewProjectResult | null>(response);
    if (!result && !task.errorMessage)
      throw new Error('The completed incident task has no result.');
    return { task, result };
  } catch (error) {
    if (signal.aborted || !task.errorMessage) throw error;
    return {
      task,
      result: null,
      outputError: error instanceof Error ? error.message : 'Unable to load the saved result.',
    };
  }
}

async function readResponse<T>(response: Response): Promise<T> {
  const body = await response.text();
  let value: unknown;
  try {
    value = body ? JSON.parse(body) : null;
  } catch {
    // Proxy responses need a useful HTTP status rather than an HTML error page.
  }
  if (!response.ok || value === undefined) {
    const message = response.ok
      ? 'Incident review returned an unexpected response'
      : normalizePollableTaskErrorMessage(value) || 'Incident review request failed';
    throw Object.assign(new Error(`${message} (HTTP ${response.status}).`), {
      status: response.status,
    });
  }
  return value as T;
}
