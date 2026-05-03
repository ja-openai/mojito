export type ApiReviewFeatureRepository = {
  id: number;
  name: string;
};

export type ApiReviewFeatureSummary = {
  id: number;
  createdDate?: string | number | null;
  lastModifiedDate?: string | number | null;
  name: string;
  enabled: boolean;
  repositoryCount: number;
  repositories: ApiReviewFeatureRepository[];
};

export type ApiReviewFeature = {
  id: number;
  createdDate?: string | number | null;
  lastModifiedDate?: string | number | null;
  name: string;
  enabled: boolean;
  repositories: ApiReviewFeatureRepository[];
};

export type ApiReviewFeatureOption = {
  id: number;
  name: string;
  enabled: boolean;
};

export type ApiReviewFeatureBatchExportRow = {
  id: number;
  name: string;
  enabled: boolean;
  repositoryNames: string[];
};

export type ApiReviewFeatureRepositoryCoverageFeature = {
  id: number;
  name: string;
  enabled: boolean;
};

export type ApiReviewFeatureRepositoryCoverage = {
  repositoryId: number;
  repositoryName: string;
  reviewFeatureCount: number;
  enabledReviewFeatureCount: number;
  reviewFeatures: ApiReviewFeatureRepositoryCoverageFeature[];
};

export type ApiReviewFeaturesResponse = {
  reviewFeatures: ApiReviewFeatureSummary[];
  totalCount: number;
};

type UpsertReviewFeaturePayload = {
  name: string;
  enabled: boolean;
  repositoryIds: number[];
};

type BatchUpsertPayload = {
  rows: Array<{
    id?: number | null;
    name: string;
    enabled: boolean;
    repositoryIds: number[];
  }>;
};

export type ApiBatchUpsertReviewFeaturesResponse = {
  createdCount: number;
  updatedCount: number;
};

const jsonHeaders = {
  'Content-Type': 'application/json',
};

export async function fetchReviewFeatures(options?: {
  search?: string;
  enabled?: boolean | null;
  limit?: number;
}): Promise<ApiReviewFeaturesResponse> {
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
    `/api/review-features${params.size ? `?${params.toString()}` : ''}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load review features');
  }

  return (await response.json()) as ApiReviewFeaturesResponse;
}

export async function fetchReviewFeature(featureId: number): Promise<ApiReviewFeature> {
  const response = await fetch(`/api/review-features/${featureId}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load review feature');
  }

  return (await response.json()) as ApiReviewFeature;
}

export async function fetchReviewFeatureOptions(): Promise<ApiReviewFeatureOption[]> {
  const response = await fetch('/api/review-features/options', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load review feature options');
  }

  return (await response.json()) as ApiReviewFeatureOption[];
}

export async function fetchReviewFeatureBatchExport(): Promise<ApiReviewFeatureBatchExportRow[]> {
  const response = await fetch('/api/review-features/batch-export', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load review feature batch export');
  }

  return (await response.json()) as ApiReviewFeatureBatchExportRow[];
}

export async function fetchReviewFeatureRepositoryCoverage(): Promise<
  ApiReviewFeatureRepositoryCoverage[]
> {
  const response = await fetch('/api/review-features/repository-coverage', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load review feature repository coverage');
  }

  return (await response.json()) as ApiReviewFeatureRepositoryCoverage[];
}

export async function createReviewFeature(
  payload: UpsertReviewFeaturePayload,
): Promise<ApiReviewFeature> {
  const response = await fetch('/api/review-features', {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to create review feature');
  }

  return (await response.json()) as ApiReviewFeature;
}

export async function updateReviewFeature(
  featureId: number,
  payload: UpsertReviewFeaturePayload,
): Promise<ApiReviewFeature> {
  const response = await fetch(`/api/review-features/${featureId}`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to update review feature');
  }

  return (await response.json()) as ApiReviewFeature;
}

export async function deleteReviewFeature(featureId: number): Promise<void> {
  const response = await fetch(`/api/review-features/${featureId}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to delete review feature');
  }
}

export async function batchUpsertReviewFeatures(
  payload: BatchUpsertPayload,
): Promise<ApiBatchUpsertReviewFeaturesResponse> {
  const response = await fetch('/api/review-features/batch-upsert', {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to batch update review features');
  }

  return (await response.json()) as ApiBatchUpsertReviewFeaturesResponse;
}
