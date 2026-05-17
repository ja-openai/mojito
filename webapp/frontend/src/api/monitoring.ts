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
