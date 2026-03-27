import { normalizePollableTaskErrorMessage } from '../utils/pollableTask';
import { isTransientHttpError, poll } from '../utils/poller';

export type BulkTranslationAcceptSelector =
  | 'PHRASE_IMPORTED_NEEDS_REVIEW'
  | 'NEEDS_REVIEW_OLDER_THAN';

export type BulkTranslationAcceptRequest = {
  selector: BulkTranslationAcceptSelector;
  repositoryIds: number[];
  createdBeforeDate?: string | null;
};

export type BulkTranslationAcceptRepositoryCount = {
  repositoryId: number;
  repositoryName: string;
  matchedCount: number;
};

export type BulkTranslationAcceptResponse = {
  totalCount: number;
  repositoryCounts: BulkTranslationAcceptRepositoryCount[];
};

type PollableTaskResponse = {
  id: number;
  isAllFinished?: boolean;
  allFinished?: boolean;
  errorMessage?: string | null;
};

type PollableTask = {
  id: number;
  isAllFinished: boolean;
  errorMessage?: string | null;
};

const jsonHeaders = {
  'Content-Type': 'application/json',
  Accept: 'application/json',
};

const DEFAULT_POLL_INTERVAL_MS = 500;
const MAX_POLL_INTERVAL_MS = 8000;
const BULK_TRANSLATION_ACCEPT_TIMEOUT_MS = 30 * 60 * 1000;

async function startBulkTranslationAccept(
  path: 'dry-run' | 'execute',
  payload: BulkTranslationAcceptRequest,
): Promise<PollableTask> {
  const response = await fetch(`/api/admin/temporary-bulk-translation-accept/${path}`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Bulk translation accept request failed');
  }

  return normalizePollableTask((await response.json()) as PollableTaskResponse);
}

async function fetchBulkTranslationAcceptTask(taskId: number): Promise<PollableTask> {
  const response = await fetch(`/api/pollableTasks/${taskId}`, {
    method: 'GET',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to poll bulk translation accept task');
  }

  return normalizePollableTask((await response.json()) as PollableTaskResponse);
}

async function waitForBulkTranslationAcceptTask(taskId: number): Promise<PollableTask> {
  return poll(() => fetchBulkTranslationAcceptTask(taskId), {
    intervalMs: DEFAULT_POLL_INTERVAL_MS,
    maxIntervalMs: MAX_POLL_INTERVAL_MS,
    timeoutMs: BULK_TRANSLATION_ACCEPT_TIMEOUT_MS,
    timeoutMessage: 'Timed out while waiting for bulk translation accept to finish',
    isTransientError: isTransientHttpError,
    shouldStop: (task) => task.isAllFinished,
  });
}

async function fetchBulkTranslationAcceptOutput(
  taskId: number,
): Promise<BulkTranslationAcceptResponse> {
  const response = await fetch(`/api/pollableTasks/${taskId}/output`, {
    method: 'GET',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load bulk translation accept output');
  }

  return (await response.json()) as BulkTranslationAcceptResponse;
}

function normalizePollableTask(task: PollableTaskResponse): PollableTask {
  return {
    id: task.id,
    isAllFinished: task.isAllFinished ?? task.allFinished ?? false,
    errorMessage: normalizePollableTaskErrorMessage(task.errorMessage) || null,
  };
}

export function dryRunBulkTranslationAccept(
  payload: BulkTranslationAcceptRequest,
): Promise<BulkTranslationAcceptResponse> {
  return runBulkTranslationAccept('dry-run', payload);
}

export function executeBulkTranslationAccept(
  payload: BulkTranslationAcceptRequest,
): Promise<BulkTranslationAcceptResponse> {
  return runBulkTranslationAccept('execute', payload);
}

async function runBulkTranslationAccept(
  path: 'dry-run' | 'execute',
  payload: BulkTranslationAcceptRequest,
): Promise<BulkTranslationAcceptResponse> {
  const pollableTask = await startBulkTranslationAccept(path, payload);
  const completedTask = await waitForBulkTranslationAcceptTask(pollableTask.id);
  if (completedTask.errorMessage) {
    throw new Error(completedTask.errorMessage);
  }
  return fetchBulkTranslationAcceptOutput(completedTask.id);
}
