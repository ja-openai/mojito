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

export type RedisCheck = {
  name: string;
  success: boolean;
  latencyMs: number;
  message: string | null;
};

export type RedisMetrics = {
  version: string | null;
  uptimeSeconds: number | null;
  usedMemoryBytes: number | null;
  usedMemoryHuman: string | null;
  maxMemoryBytes: number | null;
  connectedClients: number | null;
  keyCount: number;
};

export type RedisSnapshot = {
  timestamp: string;
  enabled: boolean;
  status: 'NOT_CONFIGURED' | 'READY' | 'UNAVAILABLE';
  endpoint: string;
  database: number;
  ssl: boolean;
  metrics: RedisMetrics | null;
  checks: RedisCheck[];
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

export async function fetchRedisSnapshot(): Promise<RedisSnapshot> {
  const response = await fetch('/api/monitoring/redis', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to check Redis');
  }

  return (await response.json()) as RedisSnapshot;
}

export async function runRedisProbe(): Promise<RedisSnapshot> {
  const response = await fetch('/api/monitoring/redis/probe', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to run Redis probe');
  }

  return (await response.json()) as RedisSnapshot;
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
