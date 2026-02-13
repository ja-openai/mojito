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
