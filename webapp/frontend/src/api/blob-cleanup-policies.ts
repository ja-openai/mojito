export type ApiBlobCleanupPolicy = {
  id: number;
  prefix: string;
  enabled: boolean;
  retentionDays: number;
  batchSize: number;
  maxBatchesPerRun: number;
  pauseMillis: number;
  maxRetries: number;
  status: string;
  lastStartedDate: string | null;
  lastFinishedDate: string | null;
  lastDeletedCount: number;
  totalDeletedCount: number;
  lastError: string | null;
};

export type ApiBlobCleanupPolicyUpdate = Pick<
  ApiBlobCleanupPolicy,
  | 'prefix'
  | 'enabled'
  | 'retentionDays'
  | 'batchSize'
  | 'maxBatchesPerRun'
  | 'pauseMillis'
  | 'maxRetries'
>;

const BASE_URL = '/api/admin/blob-cleanup-policies';

async function request<T>(url: string, options: RequestInit, message: string): Promise<T> {
  const response = await fetch(url, { credentials: 'same-origin', ...options });
  if (!response.ok) {
    const detail = await response.text().catch(() => '');
    throw new Error(detail || message);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export function fetchBlobCleanupPolicies(): Promise<ApiBlobCleanupPolicy[]> {
  return request(BASE_URL, { method: 'GET' }, 'Failed to load blob cleanup policies');
}

export function createBlobCleanupPolicy(
  policy: ApiBlobCleanupPolicyUpdate,
): Promise<ApiBlobCleanupPolicy> {
  return request(
    BASE_URL,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(policy),
    },
    'Failed to create blob cleanup policy',
  );
}

export function updateBlobCleanupPolicy(
  id: number,
  policy: ApiBlobCleanupPolicyUpdate,
): Promise<ApiBlobCleanupPolicy> {
  return request(
    `${BASE_URL}/${id}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(policy),
    },
    'Failed to update blob cleanup policy',
  );
}

export function startBlobCleanupPolicy(id: number): Promise<ApiBlobCleanupPolicy> {
  return request(`${BASE_URL}/${id}/start`, { method: 'POST' }, 'Failed to start blob cleanup');
}

export function stopBlobCleanupPolicy(id: number): Promise<ApiBlobCleanupPolicy> {
  return request(`${BASE_URL}/${id}/stop`, { method: 'POST' }, 'Failed to stop blob cleanup');
}

export function deleteBlobCleanupPolicy(id: number): Promise<void> {
  return request(`${BASE_URL}/${id}`, { method: 'DELETE' }, 'Failed to delete blob cleanup policy');
}
