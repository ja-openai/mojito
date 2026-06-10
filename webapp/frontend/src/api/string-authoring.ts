import { normalizePollableTaskErrorMessage } from '../utils/pollableTask';
import { isTransientHttpError, poll } from '../utils/poller';
import type { ApiPollableTask } from './text-units';

export type ApiStringAuthoringBranchScope = 'AUTHORING' | 'ALL';
export type ApiStringAuthoringUsedFilter = 'USED' | 'UNUSED' | 'ALL';

export type ApiStringAuthoringAsset = {
  id: number;
  path: string;
};

export type ApiStringAuthoringBranch = {
  id: number;
  name: string | null;
  authoring: boolean;
  createdDate?: string | null;
  cleanupDate?: string | null;
};

export type ApiStringAuthoringString = {
  tmTextUnitId?: number | null;
  assetId?: number | null;
  assetPath?: string | null;
  name: string;
  source: string;
  comment?: string | null;
  pluralForm?: string | null;
  pluralFormOther?: string | null;
  used: boolean;
  createdDate?: string | null;
};

export type SaveStringAuthoringString = {
  name?: string | null;
  source: string;
  comment?: string | null;
  pluralForm?: string | null;
  pluralFormOther?: string | null;
  generateId?: boolean | null;
};

export type SaveStringAuthoringRequest = {
  assetPath?: string | null;
  branchName: string;
  cleanupDate?: string | null;
  strings: SaveStringAuthoringString[];
};

export type SaveStringAuthoringResult = {
  assetPath: string;
  branchName: string;
  stringCount: number;
  cleanupDate?: string | null;
  pollableTask: PollableTaskResponse;
};

export type DeleteStringAuthoringBranchResult = {
  id: number;
  name: string;
  pollableTask: PollableTaskResponse;
};

type PollableTaskResponse = {
  id: number;
  isAllFinished?: boolean;
  allFinished?: boolean;
  errorMessage?: unknown;
};

const ASYNC_POLL_TIMEOUT_MS = 60_000;
const DEFAULT_POLL_INTERVAL_MS = 1000;
const MAX_POLL_INTERVAL_MS = 8000;

export async function fetchStringAuthoringAssets(
  repositoryId: number,
  options?: { search?: string; limit?: number },
): Promise<ApiStringAuthoringAsset[]> {
  const params = new URLSearchParams();
  if (options?.search?.trim()) {
    params.set('search', options.search.trim());
  }
  if (options?.limit) {
    params.set('limit', String(options.limit));
  }
  return getJson<ApiStringAuthoringAsset[]>(
    `/api/string-authoring/repositories/${repositoryId}/assets${params.size ? `?${params}` : ''}`,
  );
}

export async function fetchStringAuthoringBranches(
  repositoryId: number,
  scope: ApiStringAuthoringBranchScope,
): Promise<ApiStringAuthoringBranch[]> {
  const params = new URLSearchParams({ scope });
  return getJson<ApiStringAuthoringBranch[]>(
    `/api/string-authoring/repositories/${repositoryId}/branches?${params}`,
  );
}

export async function fetchStringAuthoringStrings(
  repositoryId: number,
  options: {
    branchName: string;
    assetPath?: string | null;
    usedFilter?: ApiStringAuthoringUsedFilter;
    limit?: number;
  },
): Promise<ApiStringAuthoringString[]> {
  const params = new URLSearchParams({ branchName: options.branchName });
  if (options.assetPath?.trim()) {
    params.set('assetPath', options.assetPath.trim());
  }
  if (options.usedFilter) {
    params.set('usedFilter', options.usedFilter);
  }
  if (options.limit) {
    params.set('limit', String(options.limit));
  }
  return getJson<ApiStringAuthoringString[]>(
    `/api/string-authoring/repositories/${repositoryId}/strings?${params}`,
  );
}

export async function saveStringAuthoring(
  repositoryId: number,
  request: SaveStringAuthoringRequest,
): Promise<SaveStringAuthoringResult> {
  const result = await postJson<SaveStringAuthoringResult>(
    `/api/string-authoring/repositories/${repositoryId}/strings`,
    request,
  );
  if (!result.pollableTask?.id) {
    throw new Error('String authoring save did not return a pollable task.');
  }
  return result;
}

export async function waitForStringAuthoringTask(taskId: number): Promise<ApiPollableTask> {
  return waitForPollableTaskToFinish(taskId);
}

export async function deleteStringAuthoringBranch(
  repositoryId: number,
  branchId: number,
  branchName?: string | null,
): Promise<DeleteStringAuthoringBranchResult> {
  let result: DeleteStringAuthoringBranchResult;
  try {
    result = await deleteJson<DeleteStringAuthoringBranchResult>(
      `/api/string-authoring/repositories/${repositoryId}/branches/${branchId}`,
    );
  } catch (error) {
    if (!isNotFoundError(error)) {
      throw error;
    }
    const pollableTask = await deleteJson<PollableTaskResponse>(
      `/api/repositories/${repositoryId}/branches?branchId=${branchId}`,
    );
    result = {
      id: branchId,
      name: branchName ?? String(branchId),
      pollableTask,
    };
  }
  if (!result.pollableTask?.id) {
    throw new Error('String authoring branch delete did not return a pollable task.');
  }
  const completedTask = await waitForPollableTaskToFinish(result.pollableTask.id);
  if (completedTask.errorMessage) {
    throw new Error(completedTask.errorMessage);
  }
  return result;
}

function isNotFoundError(error: unknown): boolean {
  return error instanceof Error && (error as Error & { status?: number }).status === 404;
}

async function fetchPollableTask(taskId: number): Promise<ApiPollableTask> {
  const response = await getJson<PollableTaskResponse>(`/api/pollableTasks/${taskId}`);
  return normalizePollableTask(response);
}

async function waitForPollableTaskToFinish(taskId: number): Promise<ApiPollableTask> {
  return poll(() => fetchPollableTask(taskId), {
    intervalMs: DEFAULT_POLL_INTERVAL_MS,
    maxIntervalMs: MAX_POLL_INTERVAL_MS,
    timeoutMs: ASYNC_POLL_TIMEOUT_MS,
    timeoutMessage: 'Timed out while waiting for source strings to save',
    isTransientError: isTransientHttpError,
    shouldStop: (task) => task.isAllFinished,
  });
}

function normalizePollableTask(task: PollableTaskResponse): ApiPollableTask {
  return {
    id: task.id,
    isAllFinished: task.isAllFinished ?? task.allFinished ?? false,
    errorMessage: normalizePollableTaskErrorMessage(task.errorMessage),
  };
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
    const error: Error & { status?: number } = new Error(
      text || `Request failed with status ${response.status}`,
    );
    error.status = response.status;
    throw error;
  }
  return text ? (JSON.parse(text) as TResponse) : (undefined as TResponse);
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

async function deleteJson<TResponse>(url: string): Promise<TResponse> {
  const response = await fetch(url, {
    method: 'DELETE',
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
    },
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
