export type ApiReviewAutomationFeature = {
  id: number;
  name: string;
};

export type ApiReviewAutomationSummary = {
  id: number;
  createdDate?: string | null;
  lastModifiedDate?: string | null;
  name: string;
  enabled: boolean;
  cronExpression: string;
  timeZone: string;
  maxWordCountPerProject: number;
  featureCount: number;
  features: ApiReviewAutomationFeature[];
};

export type ApiReviewAutomation = {
  id: number;
  createdDate?: string | null;
  lastModifiedDate?: string | null;
  name: string;
  enabled: boolean;
  cronExpression: string;
  timeZone: string;
  maxWordCountPerProject: number;
  features: ApiReviewAutomationFeature[];
};

export type ApiReviewAutomationOption = {
  id: number;
  name: string;
  enabled: boolean;
};

export type ApiReviewAutomationBatchExportRow = {
  id: number;
  name: string;
  enabled: boolean;
  cronExpression: string;
  timeZone: string;
  maxWordCountPerProject: number;
  featureNames: string[];
};

export type ApiReviewAutomationsResponse = {
  reviewAutomations: ApiReviewAutomationSummary[];
  totalCount: number;
};

type UpsertReviewAutomationPayload = {
  name: string;
  enabled: boolean;
  cronExpression: string;
  timeZone: string;
  maxWordCountPerProject: number;
  featureIds: number[];
};

type BatchUpsertPayload = {
  rows: Array<{
    id?: number | null;
    name: string;
    enabled: boolean;
    cronExpression: string;
    timeZone: string;
    maxWordCountPerProject: number;
    featureIds: number[];
  }>;
};

export type ApiBatchUpsertReviewAutomationsResponse = {
  createdCount: number;
  updatedCount: number;
};

const jsonHeaders = {
  'Content-Type': 'application/json',
};

export async function fetchReviewAutomations(options?: {
  search?: string;
  enabled?: boolean | null;
  limit?: number;
}): Promise<ApiReviewAutomationsResponse> {
  const params = new URLSearchParams();
  if (options?.search?.trim()) {
    params.set('search', options.search.trim());
  }
  if (typeof options?.enabled === 'boolean') {
    params.set('enabled', String(options.enabled));
  }
  if (typeof options?.limit === 'number' && Number.isFinite(options.limit)) {
    params.set('limit', String(options.limit));
  }

  const response = await fetch(
    `/api/review-automations${params.size ? `?${params.toString()}` : ''}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load review automations');
  }

  return (await response.json()) as ApiReviewAutomationsResponse;
}

export async function fetchReviewAutomation(automationId: number): Promise<ApiReviewAutomation> {
  const response = await fetch(`/api/review-automations/${automationId}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load review automation');
  }

  return (await response.json()) as ApiReviewAutomation;
}

export async function fetchReviewAutomationOptions(): Promise<ApiReviewAutomationOption[]> {
  const response = await fetch('/api/review-automations/options', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load review automation options');
  }

  return (await response.json()) as ApiReviewAutomationOption[];
}

export async function fetchReviewAutomationBatchExport(): Promise<
  ApiReviewAutomationBatchExportRow[]
> {
  const response = await fetch('/api/review-automations/batch-export', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load review automation batch export');
  }

  return (await response.json()) as ApiReviewAutomationBatchExportRow[];
}

export async function createReviewAutomation(
  payload: UpsertReviewAutomationPayload,
): Promise<ApiReviewAutomation> {
  const response = await fetch('/api/review-automations', {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to create review automation');
  }

  return (await response.json()) as ApiReviewAutomation;
}

export async function updateReviewAutomation(
  automationId: number,
  payload: UpsertReviewAutomationPayload,
): Promise<ApiReviewAutomation> {
  const response = await fetch(`/api/review-automations/${automationId}`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to update review automation');
  }

  return (await response.json()) as ApiReviewAutomation;
}

export async function deleteReviewAutomation(automationId: number): Promise<void> {
  const response = await fetch(`/api/review-automations/${automationId}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to delete review automation');
  }
}

export async function batchUpsertReviewAutomations(
  payload: BatchUpsertPayload,
): Promise<ApiBatchUpsertReviewAutomationsResponse> {
  const response = await fetch('/api/review-automations/batch-upsert', {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to batch update review automations');
  }

  return (await response.json()) as ApiBatchUpsertReviewAutomationsResponse;
}
