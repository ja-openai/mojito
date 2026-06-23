import { normalizePollableTaskErrorMessage } from '../utils/pollableTask';
import { isTransientHttpError, poll } from '../utils/poller';
import type { ApiPollableTask } from './text-units';

export type ApiJsonConfigLocalizationRepository = {
  id: number;
  name: string;
  sourceLocaleTag?: string | null;
  targetLocaleCount: number;
};

export type ApiJsonConfigLocalization = {
  id: number;
  createdDate?: string | null;
  lastModifiedDate?: string | null;
  name: string;
  repository: ApiJsonConfigLocalizationRepository;
  assetPath: string;
  provider?: string | null;
  providerConfigId?: string | null;
  statsigConsoleUrl?: string | null;
  schemaJson?: string | null;
  sourceConfigJson?: string | null;
  extractionMappingJson?: string | null;
  outputLocaleMappingJson?: string | null;
  automationEnabled?: boolean;
  automationCronExpression?: string | null;
  automationTimeZone?: string | null;
  automationOptionsJson?: string | null;
};

export type ApiJsonConfigLocalizationInput = {
  name?: string | null;
  assetPath?: string | null;
  provider?: string | null;
  providerConfigId?: string | null;
  schemaJson?: string | null;
  sourceConfigJson?: string | null;
  extractionMappingJson?: string | null;
  outputLocaleMappingJson?: string | null;
  automationEnabled?: boolean | null;
  automationCronExpression?: string | null;
  automationTimeZone?: string | null;
  automationOptionsJson?: string | null;
  expectedLastModifiedDate?: string | null;
};

export type ApiJsonConfigProfile = {
  format?:
    | 'EMBEDDED_TRANSLATIONS'
    | 'FLAT_SOURCE_ARRAY'
    | 'FORMATJS_MAP'
    | 'FORMATJS_MULTILINGUAL_MAP';
  collectionKey: string;
  itemIdField: string;
  translationsField: string;
  sourceLocaleTag: string;
  translatableFields: string[];
  sourceField?: string;
  commentField?: string;
};

export type ApiJsonConfigString = {
  stringId: string;
  source: string;
  comment?: string | null;
  used: boolean;
  doNotTranslate: boolean;
};

export type ApiJsonConfigDetectMappingResult = {
  profile: ApiJsonConfigProfile;
  warnings: string[];
};

export type ApiJsonConfigExtractionResult = {
  profile: ApiJsonConfigProfile;
  sourceConfigJson: string;
  strings: ApiJsonConfigString[];
  warnings: string[];
};

export type ApiJsonConfigExtractForRepositoryInput = {
  name?: string | null;
  assetPath?: string | null;
  provider?: string | null;
  providerConfigId?: string | null;
  schemaJson?: string | null;
  sourceConfigJson?: string | null;
  profile?: ApiJsonConfigProfile | null;
  strings?: ApiJsonConfigString[] | null;
  outputLocaleMappingJson?: string | null;
  expectedLastModifiedDate?: string | null;
};

export type ApiJsonConfigExtractForRepositoryResult = {
  setup: ApiJsonConfigLocalization;
  strings: ApiJsonConfigString[];
  warnings: string[];
  pollableTask?: PollableTaskResponse | null;
};

export type ApiJsonConfigExportResult = {
  json: string;
  warnings: string[];
};

export type ApiStatsigPullInput = {
  configId: string;
  assetPath?: string | null;
  profile?: ApiJsonConfigProfile | null;
  outputLocaleMappingJson?: string | null;
  extract?: boolean | null;
  expectedLastModifiedDate?: string | null;
};

export type ApiStatsigPushInput = {
  configId?: string | null;
  dryRun?: boolean | null;
};

export type ApiStatsigPushResult = {
  configId: string;
  dryRun: boolean;
  skipped?: boolean;
  responseJson?: string | null;
  warnings: string[];
};

export type ApiJsonConfigLocalizationRun = {
  id: number;
  setupId: number;
  triggerSource: string;
  status: string;
  createdDate?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  pullEnabled: boolean;
  extractEnabled: boolean;
  translateEnabled: boolean;
  mergeEnabled: boolean;
  saveConfigEnabled: boolean;
  pushEnabled: boolean;
  pulled: boolean;
  extracted: boolean;
  translated: boolean;
  merged: boolean;
  savedConfig: boolean;
  pushed: boolean;
  pushSkipped: boolean;
  summary?: string | null;
  errorMessage?: string | null;
};

export type ApiStatsigPullProgress = {
  onPulled?: (result: ApiJsonConfigExtractForRepositoryResult) => void;
  onExtracting?: (result: ApiJsonConfigExtractForRepositoryResult) => void;
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

export async function fetchJsonConfigLocalizations(): Promise<ApiJsonConfigLocalization[]> {
  return getJson<ApiJsonConfigLocalization[]>('/api/json-config-localizations');
}

export async function fetchJsonConfigLocalizationByRepository(
  repositoryId: number,
): Promise<ApiJsonConfigLocalization | null> {
  try {
    return await getJson<ApiJsonConfigLocalization>(
      `/api/json-config-localizations/repositories/${repositoryId}`,
    );
  } catch (error) {
    if (isHttpStatus(error, 404)) {
      return null;
    }
    throw error;
  }
}

export async function fetchJsonConfigLocalizationSetupsByRepository(
  repositoryId: number,
): Promise<ApiJsonConfigLocalization[]> {
  return getJson<ApiJsonConfigLocalization[]>(
    `/api/json-config-localizations/repositories/${repositoryId}/setups`,
  );
}

export async function fetchJsonConfigLocalizationById(
  setupId: number,
): Promise<ApiJsonConfigLocalization | null> {
  try {
    return await getJson<ApiJsonConfigLocalization>(
      `/api/json-config-localizations/setups/${setupId}`,
    );
  } catch (error) {
    if (isHttpStatus(error, 404)) {
      return null;
    }
    throw error;
  }
}

export async function fetchJsonConfigLocalizationRuns(
  setupId: number,
): Promise<ApiJsonConfigLocalizationRun[]> {
  return getJson<ApiJsonConfigLocalizationRun[]>(
    `/api/json-config-localizations/setups/${setupId}/automation-runs`,
  );
}

export async function createJsonConfigLocalization(
  repositoryId: number,
  input: ApiJsonConfigLocalizationInput,
): Promise<ApiJsonConfigLocalization> {
  return postJson<ApiJsonConfigLocalization>(
    `/api/json-config-localizations/repositories/${repositoryId}`,
    input,
  );
}

export async function upsertJsonConfigLocalization(
  repositoryId: number,
  input: ApiJsonConfigLocalizationInput,
): Promise<ApiJsonConfigLocalization> {
  return putJson<ApiJsonConfigLocalization>(
    `/api/json-config-localizations/repositories/${repositoryId}`,
    input,
  );
}

export async function updateJsonConfigLocalization(
  setupId: number,
  input: ApiJsonConfigLocalizationInput,
): Promise<ApiJsonConfigLocalization> {
  return putJson<ApiJsonConfigLocalization>(
    `/api/json-config-localizations/setups/${setupId}`,
    input,
  );
}

export async function deleteJsonConfigLocalization(repositoryId: number): Promise<void> {
  return deleteJson(`/api/json-config-localizations/repositories/${repositoryId}`);
}

export async function deleteJsonConfigLocalizationSetup(setupId: number): Promise<void> {
  return deleteJson(`/api/json-config-localizations/setups/${setupId}`);
}

export async function detectJsonConfigMapping(
  schemaJson: string,
): Promise<ApiJsonConfigDetectMappingResult> {
  return postJson<ApiJsonConfigDetectMappingResult>(
    '/api/json-config-localizations/detect-mapping',
    {
      schemaJson,
    },
  );
}

export async function extractJsonConfigStrings(input: {
  schemaJson: string;
  sourceConfigJson: string;
  profile?: ApiJsonConfigProfile | null;
}): Promise<ApiJsonConfigExtractionResult> {
  return postJson<ApiJsonConfigExtractionResult>('/api/json-config-localizations/extract', input);
}

export async function extractJsonConfigForRepository(
  repositoryId: number,
  input: ApiJsonConfigExtractForRepositoryInput,
): Promise<ApiJsonConfigExtractForRepositoryResult> {
  const result = await postJson<ApiJsonConfigExtractForRepositoryResult>(
    `/api/json-config-localizations/repositories/${repositoryId}/extract`,
    input,
  );
  if (!result.pollableTask?.id) {
    throw new Error('JSON config extraction did not return a pollable task.');
  }
  const completedTask = await waitForPollableTaskToFinish(
    normalizePollableTask(result.pollableTask).id,
  );
  if (completedTask.errorMessage) {
    throw new Error(completedTask.errorMessage);
  }
  return result;
}

export async function extractJsonConfigForSetup(
  setupId: number,
  input: ApiJsonConfigExtractForRepositoryInput,
): Promise<ApiJsonConfigExtractForRepositoryResult> {
  const result = await postJson<ApiJsonConfigExtractForRepositoryResult>(
    `/api/json-config-localizations/setups/${setupId}/extract`,
    input,
  );
  if (!result.pollableTask?.id) {
    throw new Error('JSON config extraction did not return a pollable task.');
  }
  const completedTask = await waitForPollableTaskToFinish(
    normalizePollableTask(result.pollableTask).id,
  );
  if (completedTask.errorMessage) {
    throw new Error(completedTask.errorMessage);
  }
  return result;
}

export async function exportJsonConfigForRepository(
  repositoryId: number,
): Promise<ApiJsonConfigExportResult> {
  return getJson<ApiJsonConfigExportResult>(
    `/api/json-config-localizations/repositories/${repositoryId}/export`,
  );
}

export async function exportJsonConfigForSetup(
  setupId: number,
): Promise<ApiJsonConfigExportResult> {
  return getJson<ApiJsonConfigExportResult>(
    `/api/json-config-localizations/setups/${setupId}/export`,
  );
}

export async function pullJsonConfigFromStatsig(
  repositoryId: number,
  input: ApiStatsigPullInput,
  progress?: ApiStatsigPullProgress,
): Promise<ApiJsonConfigExtractForRepositoryResult> {
  const result = await postJson<ApiJsonConfigExtractForRepositoryResult>(
    `/api/json-config-localizations/repositories/${repositoryId}/statsig/pull`,
    input,
  );
  progress?.onPulled?.(result);
  if (result.pollableTask?.id) {
    progress?.onExtracting?.(result);
    const completedTask = await waitForPollableTaskToFinish(
      normalizePollableTask(result.pollableTask).id,
    );
    if (completedTask.errorMessage) {
      throw new Error(completedTask.errorMessage);
    }
  }
  return result;
}

export async function pullJsonConfigFromStatsigForSetup(
  setupId: number,
  input: ApiStatsigPullInput,
  progress?: ApiStatsigPullProgress,
): Promise<ApiJsonConfigExtractForRepositoryResult> {
  const result = await postJson<ApiJsonConfigExtractForRepositoryResult>(
    `/api/json-config-localizations/setups/${setupId}/statsig/pull`,
    input,
  );
  progress?.onPulled?.(result);
  if (result.pollableTask?.id) {
    progress?.onExtracting?.(result);
    const completedTask = await waitForPollableTaskToFinish(
      normalizePollableTask(result.pollableTask).id,
    );
    if (completedTask.errorMessage) {
      throw new Error(completedTask.errorMessage);
    }
  }
  return result;
}

export async function pushJsonConfigToStatsig(
  repositoryId: number,
  input: ApiStatsigPushInput,
): Promise<ApiStatsigPushResult> {
  return postJson<ApiStatsigPushResult>(
    `/api/json-config-localizations/repositories/${repositoryId}/statsig/push`,
    input,
  );
}

export async function pushJsonConfigToStatsigForSetup(
  setupId: number,
  input: ApiStatsigPushInput,
): Promise<ApiStatsigPushResult> {
  return postJson<ApiStatsigPushResult>(
    `/api/json-config-localizations/setups/${setupId}/statsig/push`,
    input,
  );
}

async function fetchPollableTask(pollableTaskId: number): Promise<ApiPollableTask> {
  const response = await getJson<PollableTaskResponse>(`/api/pollableTasks/${pollableTaskId}`);
  return normalizePollableTask(response);
}

async function waitForPollableTaskToFinish(pollableTaskId: number): Promise<ApiPollableTask> {
  return poll(() => fetchPollableTask(pollableTaskId), {
    intervalMs: DEFAULT_POLL_INTERVAL_MS,
    maxIntervalMs: MAX_POLL_INTERVAL_MS,
    timeoutMs: ASYNC_POLL_TIMEOUT_MS,
    timeoutMessage: 'Timed out while waiting for JSON config localization changes to finish',
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
    throw buildHttpError(text, response.status);
  }
  return text ? (JSON.parse(text) as TResponse) : (undefined as TResponse);
}

async function putJson<TResponse>(url: string, body: unknown): Promise<TResponse> {
  const response = await fetch(url, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(body),
  });

  const text = await response.text();
  if (!response.ok) {
    throw buildHttpError(text, response.status);
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
    throw buildHttpError(text, response.status);
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
    throw buildHttpError(text, response.status);
  }
  return text ? (JSON.parse(text) as TResponse) : (undefined as TResponse);
}

function buildHttpError(text: string, status: number) {
  let message = text.trim();
  if (message) {
    try {
      const payload = JSON.parse(message) as { message?: unknown; error?: unknown };
      if (typeof payload.message === 'string' && payload.message.trim()) {
        message = payload.message.trim();
      } else if (typeof payload.error === 'string' && payload.error.trim()) {
        message = payload.error.trim();
      }
    } catch {
      // Keep the raw response text when it is not a Spring JSON error body.
    }
  }
  const error: Error & { status?: number } = new Error(
    message || `Request failed with status ${status}`,
  );
  error.status = status;
  return error;
}

function isHttpStatus(error: unknown, status: number): boolean {
  return error instanceof Error && (error as Error & { status?: number }).status === status;
}
