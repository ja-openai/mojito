export type ApiBlobCleanupSettings = {
  enabled: boolean;
  batchSize: number;
  maxBatchesPerRun: number;
  pauseMillis: number;
  maxRetries: number;
  status: string;
  lastStartedDate: string | null;
  lastProgressDate: string | null;
  lastFinishedDate: string | null;
  lastDeletedCount: number;
  totalDeletedCount: number;
  lastError: string | null;
};

export type ApiBlobCleanupSettingsUpdate = Pick<
  ApiBlobCleanupSettings,
  'enabled' | 'batchSize' | 'maxBatchesPerRun' | 'pauseMillis' | 'maxRetries'
>;

const BASE_URL = '/api/admin/blob-cleanup';

async function request<T>(url: string, options: RequestInit, message: string): Promise<T> {
  const response = await fetch(url, { credentials: 'same-origin', ...options });
  if (!response.ok) {
    const detail = await response.text().catch(() => '');
    throw new Error(detail || message);
  }
  return (await response.json()) as T;
}

export function fetchBlobCleanupSettings(): Promise<ApiBlobCleanupSettings> {
  return request(BASE_URL, { method: 'GET' }, 'Failed to load blob cleanup settings');
}

export function updateBlobCleanupSettings(
  settings: ApiBlobCleanupSettingsUpdate,
): Promise<ApiBlobCleanupSettings> {
  return request(
    BASE_URL,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(settings),
    },
    'Failed to update blob cleanup settings',
  );
}

export function startBlobCleanup(): Promise<ApiBlobCleanupSettings> {
  return request(`${BASE_URL}/start`, { method: 'POST' }, 'Failed to start blob cleanup');
}

export function stopBlobCleanup(): Promise<ApiBlobCleanupSettings> {
  return request(`${BASE_URL}/stop`, { method: 'POST' }, 'Failed to stop blob cleanup');
}
