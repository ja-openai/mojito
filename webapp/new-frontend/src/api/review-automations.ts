export type ApiReviewAutomationFeature = {
  id: number;
  name: string;
};

export type ApiReviewAutomationTrigger = {
  status: string;
  quartzState: string;
  nextRunAt: string | null;
  previousRunAt: string | null;
  lastRunAt: string | null;
  lastSuccessfulRunAt: string | null;
  repairRecommended: boolean;
};

export type ApiReviewAutomationSummary = {
  id: number;
  createdDate?: string | number | null;
  lastModifiedDate?: string | number | null;
  name: string;
  enabled: boolean;
  cronExpression: string;
  timeZone: string;
  team: { id: number; name: string } | null;
  dueDateOffsetDays: number;
  maxWordCountPerProject: number;
  trigger: ApiReviewAutomationTrigger | null;
  featureCount: number;
  features: ApiReviewAutomationFeature[];
};

export type ApiReviewAutomation = {
  id: number;
  createdDate?: string | number | null;
  lastModifiedDate?: string | number | null;
  name: string;
  enabled: boolean;
  cronExpression: string;
  timeZone: string;
  team: { id: number; name: string } | null;
  dueDateOffsetDays: number;
  maxWordCountPerProject: number;
  trigger: ApiReviewAutomationTrigger | null;
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
  teamName: string | null;
  dueDateOffsetDays: number;
  maxWordCountPerProject: number;
  featureNames: string[];
};

export type ApiReviewAutomationRunResult = {
  runId: number | null;
  automationId: number;
  automationName: string;
  featureCount: number;
  createdProjectRequestCount: number;
  createdProjectCount: number;
  createdLocaleCount: number;
  skippedLocaleCount: number;
  erroredLocaleCount: number;
};

export type ApiReviewAutomationRun = {
  id: number;
  automationId: number;
  automationName: string;
  triggerSource: string;
  requestedByUserId: number | null;
  requestedByUsername: string | null;
  status: string;
  createdAt: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  featureCount: number;
  createdProjectRequestCount: number;
  createdProjectCount: number;
  createdLocaleCount: number;
  skippedLocaleCount: number;
  erroredLocaleCount: number;
  errorMessage: string | null;
};

export type ApiReviewAutomationSchedulePreview = {
  timeZone: string;
  nextRuns: string[];
};

export type ApiReviewAutomationTriggerRepairResult = {
  automationId: number;
  trigger: ApiReviewAutomationTrigger | null;
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
  teamId: number;
  dueDateOffsetDays: number;
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
    teamId: number;
    dueDateOffsetDays: number;
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

async function getErrorMessage(response: Response, fallbackMessage: string) {
  const text = await response.text().catch(() => '');
  if (!text) {
    return fallbackMessage;
  }

  try {
    const parsed = JSON.parse(text) as { message?: string; error?: string };
    if (parsed.message?.trim()) {
      return parsed.message.trim();
    }
    if (parsed.error?.trim()) {
      return parsed.error.trim();
    }
  } catch {
    // Fall back to raw text for non-JSON responses.
  }

  return text || fallbackMessage;
}

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
    throw new Error(await getErrorMessage(response, 'Failed to load review automations'));
  }

  return (await response.json()) as ApiReviewAutomationsResponse;
}

export async function fetchReviewAutomation(automationId: number): Promise<ApiReviewAutomation> {
  const response = await fetch(`/api/review-automations/${automationId}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    throw new Error(await getErrorMessage(response, 'Failed to load review automation'));
  }

  return (await response.json()) as ApiReviewAutomation;
}

export async function fetchReviewAutomationOptions(): Promise<ApiReviewAutomationOption[]> {
  const response = await fetch('/api/review-automations/options', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    throw new Error(await getErrorMessage(response, 'Failed to load review automation options'));
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
    throw new Error(
      await getErrorMessage(response, 'Failed to load review automation batch export'),
    );
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
    throw new Error(await getErrorMessage(response, 'Failed to create review automation'));
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
    throw new Error(await getErrorMessage(response, 'Failed to update review automation'));
  }

  return (await response.json()) as ApiReviewAutomation;
}

export async function deleteReviewAutomation(automationId: number): Promise<void> {
  const response = await fetch(`/api/review-automations/${automationId}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    throw new Error(await getErrorMessage(response, 'Failed to delete review automation'));
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
    throw new Error(await getErrorMessage(response, 'Failed to batch update review automations'));
  }

  return (await response.json()) as ApiBatchUpsertReviewAutomationsResponse;
}

export async function runReviewAutomationNow(
  automationId: number,
): Promise<ApiReviewAutomationRunResult> {
  const response = await fetch(`/api/review-automations/${automationId}/run`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    throw new Error(await getErrorMessage(response, 'Failed to run review automation'));
  }

  return (await response.json()) as ApiReviewAutomationRunResult;
}

export async function repairReviewAutomationTrigger(
  automationId: number,
): Promise<ApiReviewAutomationTriggerRepairResult> {
  const response = await fetch(`/api/review-automations/${automationId}/repair-trigger`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    throw new Error(await getErrorMessage(response, 'Failed to repair review automation trigger'));
  }

  return (await response.json()) as ApiReviewAutomationTriggerRepairResult;
}

export async function fetchReviewAutomationRuns(options?: {
  automationIds?: number[];
  limit?: number;
}): Promise<ApiReviewAutomationRun[]> {
  const params = new URLSearchParams();
  for (const automationId of options?.automationIds ?? []) {
    if (Number.isInteger(automationId) && automationId > 0) {
      params.append('automationIds', String(automationId));
    }
  }
  if (typeof options?.limit === 'number' && Number.isFinite(options.limit)) {
    params.set('limit', String(options.limit));
  }

  const response = await fetch(
    `/api/review-automations/runs${params.size ? `?${params.toString()}` : ''}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    throw new Error(await getErrorMessage(response, 'Failed to load review automation runs'));
  }

  return (await response.json()) as ApiReviewAutomationRun[];
}

export async function fetchReviewAutomationSchedulePreview(options: {
  cronExpression: string;
  timeZone?: string | null;
  count?: number;
}): Promise<ApiReviewAutomationSchedulePreview> {
  const params = new URLSearchParams();
  params.set('cronExpression', options.cronExpression);
  if (options.timeZone?.trim()) {
    params.set('timeZone', options.timeZone.trim());
  }
  if (typeof options.count === 'number' && Number.isFinite(options.count)) {
    params.set('count', String(options.count));
  }

  const response = await fetch(`/api/review-automations/schedule-preview?${params.toString()}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    throw new Error(
      await getErrorMessage(response, 'Failed to preview review automation schedule'),
    );
  }

  return (await response.json()) as ApiReviewAutomationSchedulePreview;
}
