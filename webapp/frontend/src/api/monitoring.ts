import { normalizePollableTaskErrorMessage } from '../utils/pollableTask';

export const MIN_MONITORING_ITERATIONS = 1;
export const MAX_MONITORING_ITERATIONS = 20;

export type DbLatencyMeasurement = {
  iteration: number;
  latencyMs: number;
};

export type DbLatencySeries = {
  measurements: DbLatencyMeasurement[];
  minLatencyMs: number;
  maxLatencyMs: number;
  averageLatencyMs: number;
};

export type DbMonitoringSnapshot = {
  timestamp: string;
  iterations: number;
  raw: DbLatencySeries;
  hibernateHealth: DbLatencySeries;
  hibernateRepo: DbLatencySeries;
};

export type AzureStorageCheck = {
  name: string;
  success: boolean;
  latencyMs: number;
  message: string | null;
};

export type AzureStorageRoute = {
  prefix: string;
  backend: string;
};

export type AzureStorageSnapshot = {
  timestamp: string;
  enabled: boolean;
  status: 'NOT_CONFIGURED' | 'READY' | 'UNAVAILABLE';
  endpoint: string | null;
  container: string;
  prefix: string;
  defaultBackend: string;
  routes: AzureStorageRoute[];
  checks: AzureStorageCheck[];
};

export type BulkImportRunSummary = {
  runId: string;
  createdDate: string;
  completedDate: string | null;
  repositoryId: number;
  repositoryName: string;
  assetId: number;
  assetPath: string;
  locale: string;
  pollableTaskId: number | null;
  initiatingUserId: number | null;
  actorType: string;
  actorIdentity: string | null;
  source: string;
  importMode: string;
  integrityChecksType: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  requestedCount: number;
  importedCount: number;
  skippedCount: number;
  inputPayloadBlobName: string | null;
  outputPayloadBlobName: string | null;
  errorMessage: string | null;
};

export type BulkImportInputTextUnit = {
  tmTextUnitId?: number | null;
  name?: string | null;
  source?: string | null;
  sourceComment?: string | null;
  target?: string | null;
  targetComment?: string | null;
  status?: string | null;
  includedInLocalizedFile: boolean;
  translatorIdentity: string;
  reviewerIdentity: string;
};

export type BulkImportInputPayload = {
  runId: string;
  repository: string;
  locale: string;
  assetPath: string;
  source: string;
  importMode: string;
  integrityChecksType: string;
  textUnits: BulkImportInputTextUnit[];
};

export type BulkImportOutputTextUnit = {
  tmTextUnitId?: number | null;
  name?: string | null;
  previousTmTextUnitVariantId?: number | null;
  resultingTmTextUnitVariantId?: number | null;
  status: 'IMPORTED' | 'SKIPPED' | 'UNMATCHED';
  translatorIdentity: string;
  reviewerIdentity: string;
};

export type BulkImportOutputPayload = {
  runId: string;
  status: 'COMPLETED' | 'FAILED';
  requestedCount: number;
  importedCount: number;
  skippedCount: number;
  textUnits: BulkImportOutputTextUnit[];
  error?: string | null;
};

export type SearchIndexStatus = {
  enabled: boolean;
  baseUrl: string;
  indexName: string;
  reachable: boolean;
  indexExists: boolean;
  clusterStatus: string | null;
  documentCount: number | null;
  detail: string | null;
};

export type SearchIndexReindexRequest = {
  repositoryIds?: number[] | null;
  pageSize?: number | null;
  bulkSize?: number | null;
};

export type SearchIndexReindexResult = {
  indexName: string;
  repositoryIds: number[];
  pageSize: number;
  bulkSize: number;
  scannedDocuments: number;
  indexedDocuments: number;
  failedDocuments: number;
  lastProcessedVariantId: number | null;
  detail: string | null;
};

export type SearchIndexReindexProgress = SearchIndexReindexResult & {
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  totalDocuments: number;
};

export type SearchIndexReindexTask = {
  id: number;
  isAllFinished: boolean;
  progress: SearchIndexReindexProgress | null;
  errorMessage: string | null;
};

type SearchIndexPollableTaskResponse = {
  id: number;
  isAllFinished?: boolean;
  allFinished?: boolean;
  message?: unknown;
  errorMessage?: unknown;
};

export type SearchIndexSearchRequest = {
  query: string;
  repositoryIds?: number[] | null;
  localeTags?: string[] | null;
  currentOnly?: boolean | null;
  limit?: number | null;
};

export type SearchIndexSearchHit = {
  score: number;
  tmTextUnitVariantId: number | null;
  tmTextUnitId: number | null;
  repositoryId: number | null;
  repositoryName: string | null;
  assetId: number | null;
  assetPath: string | null;
  sourceLocaleTag: string | null;
  localeTag: string | null;
  name: string | null;
  source: string | null;
  target: string | null;
  status: string | null;
  current: boolean;
  assetDeleted: boolean;
};

export type SearchIndexSearchResult = {
  indexName: string;
  limit: number;
  currentOnly: boolean;
  hits: SearchIndexSearchHit[];
};

export type IngestionGroupBy = 'day' | 'month' | 'year';

export type TextUnitIngestionPoint = {
  period: string;
  repositoryId: number | null;
  repositoryName: string | null;
  stringCount: number;
  wordCount: number;
};

export type TextUnitIngestionSnapshot = {
  groupBy: IngestionGroupBy;
  groupedByRepository: boolean;
  latestComputedDay: string | null;
  lastComputedAt: string | null;
  rows: TextUnitIngestionPoint[];
};

export type TextUnitIngestionRecomputeResult = {
  latestComputedDayBefore: string | null;
  latestComputedDayAfter: string | null;
  recomputedFromDay: string | null;
  recomputedToDay: string | null;
  daysComputed: number;
  savedRows: number;
  computedAt: string;
};

export async function fetchDbLatencySnapshot(iterations: number): Promise<DbMonitoringSnapshot> {
  const params = new URLSearchParams({ iterations: String(iterations) });
  const response = await fetch(`/api/monitoring/db?${params.toString()}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to measure DB latency');
  }

  return (await response.json()) as DbMonitoringSnapshot;
}

export async function fetchAzureStorageSnapshot(): Promise<AzureStorageSnapshot> {
  const response = await fetch('/api/monitoring/azure-storage', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to check Azure Storage');
  }

  return (await response.json()) as AzureStorageSnapshot;
}

export async function runAzureStorageProbe(): Promise<AzureStorageSnapshot> {
  const response = await fetch('/api/monitoring/azure-storage/probe', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to run Azure Storage probe');
  }

  return (await response.json()) as AzureStorageSnapshot;
}

export async function fetchBulkImportRuns(limit = 50): Promise<BulkImportRunSummary[]> {
  const params = new URLSearchParams({ limit: String(limit) });
  const response = await fetch(`/api/monitoring/import-lineage?${params.toString()}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load bulk import history');
  }

  return (await response.json()) as BulkImportRunSummary[];
}

export async function fetchBulkImportInput(runId: string): Promise<BulkImportInputPayload> {
  return fetchBulkImportPayload<BulkImportInputPayload>(runId, 'input');
}

export async function fetchBulkImportOutput(runId: string): Promise<BulkImportOutputPayload> {
  return fetchBulkImportPayload<BulkImportOutputPayload>(runId, 'output');
}

async function fetchBulkImportPayload<T>(runId: string, kind: 'input' | 'output'): Promise<T> {
  const response = await fetch(
    `/api/monitoring/import-lineage/${encodeURIComponent(runId)}/${kind}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || `Failed to load bulk import ${kind}`);
  }

  return (await response.json()) as T;
}

export async function fetchSearchIndexStatus(): Promise<SearchIndexStatus> {
  const response = await fetch('/api/monitoring/search-index', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to check the search index');
  }

  return (await response.json()) as SearchIndexStatus;
}

export async function bootstrapSearchIndex(): Promise<SearchIndexStatus> {
  const response = await fetch('/api/monitoring/search-index/bootstrap', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to bootstrap the search index');
  }

  return (await response.json()) as SearchIndexStatus;
}

export async function reindexSearchIndex(
  request: SearchIndexReindexRequest,
): Promise<SearchIndexReindexTask> {
  const response = await fetch('/api/monitoring/search-index/reindex', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to reindex the search index');
  }

  const payload = (await response.json()) as { pollableTask: SearchIndexPollableTaskResponse };
  return normalizeSearchIndexReindexTask(payload.pollableTask);
}

export async function fetchActiveSearchIndexReindexTask(): Promise<SearchIndexReindexTask | null> {
  const response = await fetch('/api/monitoring/search-index/reindex', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to check the active search index job');
  }

  const payload = (await response.json()) as {
    pollableTask: SearchIndexPollableTaskResponse | null;
  };
  return payload.pollableTask ? normalizeSearchIndexReindexTask(payload.pollableTask) : null;
}

export async function fetchSearchIndexReindexTask(taskId: number): Promise<SearchIndexReindexTask> {
  const response = await fetch(`/api/pollableTasks/${taskId}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to check search index job progress');
  }

  return normalizeSearchIndexReindexTask(
    (await response.json()) as SearchIndexPollableTaskResponse,
  );
}

function normalizeSearchIndexReindexTask(
  task: SearchIndexPollableTaskResponse,
): SearchIndexReindexTask {
  let progress = task.message;
  if (typeof progress === 'string') {
    try {
      progress = JSON.parse(progress) as unknown;
    } catch {
      progress = null;
    }
  }

  return {
    id: task.id,
    isAllFinished: task.isAllFinished ?? task.allFinished ?? false,
    progress:
      typeof progress === 'object' && progress !== null && !Array.isArray(progress)
        ? (progress as SearchIndexReindexProgress)
        : null,
    errorMessage: normalizePollableTaskErrorMessage(task.errorMessage) || null,
  };
}

export async function searchSearchIndex(
  request: SearchIndexSearchRequest,
): Promise<SearchIndexSearchResult> {
  const response = await fetch('/api/monitoring/search-index/search', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to search the index');
  }

  return (await response.json()) as SearchIndexSearchResult;
}

export async function fetchTextUnitIngestionSnapshot(options: {
  groupBy: IngestionGroupBy;
  groupByRepository: boolean;
  fromDay?: string | null;
  toDay?: string | null;
}): Promise<TextUnitIngestionSnapshot> {
  const params = new URLSearchParams({
    groupBy: options.groupBy,
    groupByRepository: options.groupByRepository ? 'true' : 'false',
  });
  if (options.fromDay) {
    params.set('fromDay', options.fromDay);
  }
  if (options.toDay) {
    params.set('toDay', options.toDay);
  }

  const response = await fetch(`/api/monitoring/text-unit-ingestion?${params.toString()}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load text unit ingestion stats');
  }

  return (await response.json()) as TextUnitIngestionSnapshot;
}

export async function recomputeTextUnitIngestion(): Promise<TextUnitIngestionRecomputeResult> {
  const response = await fetch('/api/monitoring/text-unit-ingestion/recompute', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to recompute text unit ingestion stats');
  }

  return (await response.json()) as TextUnitIngestionRecomputeResult;
}
