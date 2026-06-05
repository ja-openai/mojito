export type ApiCmsRepositoryRef = {
  id: number;
  name: string;
};

export type ApiCmsAssetRef = {
  id: number;
  path: string;
};

export type ApiCmsAudit = {
  createdDate?: string | number | null;
  lastModifiedDate?: string | number | null;
  createdByUsername: string;
  lastModifiedByUsername: string;
};

export type ApiCmsProjectSummary = {
  id: number;
  entityVersion: number;
  audit: ApiCmsAudit;
  projectKey: string;
  name: string;
  description?: string | null;
  enabled: boolean;
  repository: ApiCmsRepositoryRef;
  asset: ApiCmsAssetRef;
  deliveryHint: string;
};

export type ApiCmsProjectView = ApiCmsProjectSummary & {
  sourceLocale: string;
};

export type ApiCmsFieldType = 'TEXT' | 'ICU_MESSAGE';

export type ApiCmsContentTypeField = {
  id: number;
  entityVersion: number;
  audit: ApiCmsAudit;
  contentTypeId: number;
  fieldKey: string;
  name: string;
  description?: string | null;
  fieldType: ApiCmsFieldType;
  localizable: boolean;
  required: boolean;
  sortOrder: number;
};

export type ApiCmsContentType = {
  id: number;
  entityVersion: number;
  audit: ApiCmsAudit;
  projectId: number;
  typeKey: string;
  name: string;
  description?: string | null;
  schemaVersion: number;
  metadataSchemaJson?: string | null;
  fields: ApiCmsContentTypeField[];
};

export type ApiCmsEntryStatus = 'DRAFT' | 'READY' | 'ARCHIVED';
export type ApiCmsVariantStatus = 'CONTROL' | 'CANDIDATE' | 'ARCHIVED';

export type ApiCmsFieldMapping = {
  id: number;
  entityVersion: number;
  audit: ApiCmsAudit;
  variantId: number;
  fieldId: number;
  fieldKey: string;
  tmTextUnitId: number;
  stringId: string;
  sourceContent: string;
  sourceComment?: string | null;
};

export type ApiCmsVariant = {
  id: number;
  entityVersion: number;
  audit: ApiCmsAudit;
  entryId: number;
  variantKey: string;
  name: string;
  candidateGroupKey?: string | null;
  status: ApiCmsVariantStatus;
  metadataJson?: string | null;
  sortOrder: number;
  fieldMappings: ApiCmsFieldMapping[];
};

export type ApiCmsEntry = {
  id: number;
  entityVersion: number;
  audit: ApiCmsAudit;
  projectId: number;
  contentTypeId: number;
  entryKey: string;
  name: string;
  description?: string | null;
  status: ApiCmsEntryStatus;
  metadataJson?: string | null;
  variants: ApiCmsVariant[];
};

export type ApiCmsPublishSnapshot = {
  id: number;
  projectId: number;
  snapshotVersion: number;
  status: 'PUBLISHED';
  localeTags: string[];
  artifactSha256: string;
  artifactByteSize: number;
  snapshotSigningKeyId: string;
  snapshotSignature: string;
  artifactSignature: string;
  artifactFilename: string;
  artifactExportPath: string;
  createdByUsername: string;
  publishedAt?: string | number | null;
};

export type ApiCmsProjectDetail = {
  project: ApiCmsProjectView;
  authoringSha256: string;
  contentTypes: ApiCmsContentType[];
  entries: ApiCmsEntry[];
  publishSnapshots: ApiCmsPublishSnapshot[];
};

export type ApiCmsProjectsResponse = {
  projects: ApiCmsProjectSummary[];
  totalCount: number;
};

export type ApiCmsLocaleCompleteness = {
  localeTag: string;
  totalFields: number;
  approvedFields: number;
  missingFields: number;
  reviewNeededFields: number;
  translationNeededFields: number;
  complete: boolean;
};

export type ApiCmsEntryCompleteness = {
  entryId: number;
  entryKey: string;
  locales: ApiCmsLocaleCompleteness[];
};

export type ApiCmsProjectCompleteness = {
  projectId: number;
  projectKey: string;
  authoringSha256: string;
  publishPackageSha256: string;
  publishPackageByteSize: number;
  localeTags: string[];
  locales: ApiCmsLocaleCompleteness[];
  entries: ApiCmsEntryCompleteness[];
  complete: boolean;
};

const jsonHeaders = {
  'Content-Type': 'application/json',
};

async function getErrorMessage(response: Response, fallbackMessage: string) {
  const responseText = await response.text().catch(() => '');
  const message = responseText.trim();
  if (!message) {
    return fallbackMessage;
  }
  try {
    const responseBody = JSON.parse(message) as unknown;
    if (typeof responseBody === 'string') {
      return responseBody || fallbackMessage;
    }
    if (responseBody == null || typeof responseBody !== 'object') {
      return message;
    }
    const responseRecord = responseBody as Record<string, unknown>;
    for (const key of ['message', 'detail', 'error']) {
      const value = responseRecord[key];
      if (typeof value === 'string' && value.trim()) {
        return value.trim();
      }
    }
  } catch {
    return message;
  }
  return message;
}

class CmsApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'CmsApiError';
    this.status = status;
  }
}

async function throwCmsError(response: Response, fallbackMessage: string): Promise<never> {
  throw new CmsApiError(response.status, await getErrorMessage(response, fallbackMessage));
}

export function isCmsConflictError(error: unknown) {
  return error instanceof CmsApiError && error.status === 409;
}

export function createCmsPublishRequestKey() {
  if (typeof globalThis.crypto?.randomUUID !== 'function') {
    throw new Error('Browser cannot create CMS publish request key');
  }
  return globalThis.crypto.randomUUID();
}

export async function fetchCmsProjects(options?: {
  search?: string;
  enabled?: boolean | null;
  limit?: number;
}): Promise<ApiCmsProjectsResponse> {
  const params = new URLSearchParams();
  if (options?.search?.trim()) {
    params.set('search', options.search.trim());
  }
  if (typeof options?.enabled === 'boolean') {
    params.set('enabled', String(options.enabled));
  }
  if (typeof options?.limit === 'number') {
    params.set('limit', String(options.limit));
  }

  const response = await fetch(`/api/content-cms/projects${params.size ? `?${params}` : ''}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    await throwCmsError(response, 'Failed to load CMS projects');
  }

  return (await response.json()) as ApiCmsProjectsResponse;
}

export async function fetchCmsProject(projectId: number): Promise<ApiCmsProjectDetail> {
  const response = await fetch(`/api/content-cms/projects/${projectId}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    await throwCmsError(response, 'Failed to load CMS project');
  }

  return (await response.json()) as ApiCmsProjectDetail;
}

export async function createCmsProject(payload: {
  projectKey: string;
  name: string;
  description?: string | null;
  enabled: boolean;
  repositoryId: number;
  assetPath?: string | null;
  deliveryHint?: string | null;
}): Promise<ApiCmsProjectDetail> {
  const response = await fetch('/api/content-cms/projects', {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    await throwCmsError(response, 'Failed to create CMS project');
  }

  return (await response.json()) as ApiCmsProjectDetail;
}

export async function updateCmsProject(
  projectId: number,
  payload: {
    name: string;
    description?: string | null;
    enabled: boolean;
    deliveryHint?: string | null;
    expectedVersion: number;
  },
): Promise<ApiCmsProjectDetail> {
  const response = await fetch(`/api/content-cms/projects/${projectId}`, {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    await throwCmsError(response, 'Failed to update CMS project');
  }

  return (await response.json()) as ApiCmsProjectDetail;
}

export async function createCmsContentType(
  projectId: number,
  payload: {
    typeKey: string;
    name: string;
    description?: string | null;
    metadataSchemaJson?: string | null;
  },
): Promise<ApiCmsProjectDetail> {
  const response = await fetch(`/api/content-cms/projects/${projectId}/content-types`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    await throwCmsError(response, 'Failed to create content type');
  }

  return (await response.json()) as ApiCmsProjectDetail;
}

export async function updateCmsContentType(
  contentTypeId: number,
  payload: {
    name: string;
    description?: string | null;
    metadataSchemaJson?: string | null;
    expectedVersion: number;
  },
): Promise<ApiCmsProjectDetail> {
  const response = await fetch(`/api/content-cms/content-types/${contentTypeId}`, {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    await throwCmsError(response, 'Failed to update content type');
  }

  return (await response.json()) as ApiCmsProjectDetail;
}

export async function createCmsContentTypeField(
  contentTypeId: number,
  payload: {
    fieldKey: string;
    name: string;
    description?: string | null;
    fieldType: ApiCmsFieldType;
    localizable: boolean;
    required: boolean;
    sortOrder?: number | null;
  },
): Promise<ApiCmsProjectDetail> {
  const response = await fetch(`/api/content-cms/content-types/${contentTypeId}/fields`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    await throwCmsError(response, 'Failed to create content type field');
  }

  return (await response.json()) as ApiCmsProjectDetail;
}

export async function updateCmsContentTypeField(
  fieldId: number,
  payload: {
    name: string;
    description?: string | null;
    fieldType: ApiCmsFieldType;
    localizable: boolean;
    required: boolean;
    sortOrder?: number | null;
    expectedVersion: number;
  },
): Promise<ApiCmsProjectDetail> {
  const response = await fetch(`/api/content-cms/content-type-fields/${fieldId}`, {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    await throwCmsError(response, 'Failed to update content type field');
  }

  return (await response.json()) as ApiCmsProjectDetail;
}

export async function createCmsEntry(
  projectId: number,
  payload: {
    contentTypeId: number;
    entryKey: string;
    name: string;
    description?: string | null;
    status: ApiCmsEntryStatus;
    metadataJson?: string | null;
  },
): Promise<ApiCmsProjectDetail> {
  const response = await fetch(`/api/content-cms/projects/${projectId}/entries`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    await throwCmsError(response, 'Failed to create CMS entry');
  }

  return (await response.json()) as ApiCmsProjectDetail;
}

export async function updateCmsEntry(
  entryId: number,
  payload: {
    name: string;
    description?: string | null;
    status: ApiCmsEntryStatus;
    metadataJson?: string | null;
    expectedVersion: number;
  },
): Promise<ApiCmsProjectDetail> {
  const response = await fetch(`/api/content-cms/entries/${entryId}`, {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    await throwCmsError(response, 'Failed to update CMS entry');
  }

  return (await response.json()) as ApiCmsProjectDetail;
}

export async function createCmsVariant(
  entryId: number,
  payload: {
    variantKey: string;
    name: string;
    candidateGroupKey?: string | null;
    status: ApiCmsVariantStatus;
    metadataJson?: string | null;
    sortOrder?: number | null;
  },
): Promise<ApiCmsProjectDetail> {
  const response = await fetch(`/api/content-cms/entries/${entryId}/variants`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    await throwCmsError(response, 'Failed to create CMS variant');
  }

  return (await response.json()) as ApiCmsProjectDetail;
}

export async function updateCmsVariant(
  variantId: number,
  payload: {
    name: string;
    candidateGroupKey?: string | null;
    status: ApiCmsVariantStatus;
    metadataJson?: string | null;
    sortOrder?: number | null;
    expectedVersion: number;
  },
): Promise<ApiCmsProjectDetail> {
  const response = await fetch(`/api/content-cms/variants/${variantId}`, {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    await throwCmsError(response, 'Failed to update CMS variant');
  }

  return (await response.json()) as ApiCmsProjectDetail;
}

export async function upsertCmsFieldMapping(
  variantId: number,
  payload: {
    fieldId: number;
    tmTextUnitId?: number | null;
    stringId?: string | null;
    sourceContent?: string | null;
    sourceComment?: string | null;
    expectedVersion?: number | null;
  },
): Promise<ApiCmsProjectDetail> {
  const response = await fetch(`/api/content-cms/variants/${variantId}/field-mappings`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    await throwCmsError(response, 'Failed to map CMS field');
  }

  return (await response.json()) as ApiCmsProjectDetail;
}

export async function unmapCmsFieldMapping(
  mappingId: number,
  expectedVersion: number,
): Promise<ApiCmsProjectDetail> {
  const response = await fetch(`/api/content-cms/field-mappings/${mappingId}`, {
    method: 'DELETE',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify({ expectedVersion }),
  });

  if (!response.ok) {
    await throwCmsError(response, 'Failed to unmap CMS field');
  }

  return (await response.json()) as ApiCmsProjectDetail;
}

export async function fetchCmsEntryCompleteness(
  entryId: number,
  locales?: string,
): Promise<ApiCmsEntryCompleteness> {
  const params = new URLSearchParams();
  if (locales?.trim()) {
    params.set('locales', locales.trim());
  }
  const response = await fetch(
    `/api/content-cms/entries/${entryId}/completeness${params.size ? `?${params}` : ''}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    await throwCmsError(response, 'Failed to validate CMS completeness');
  }

  return (await response.json()) as ApiCmsEntryCompleteness;
}

export async function fetchCmsProjectCompleteness(
  projectId: number,
  locales?: string,
): Promise<ApiCmsProjectCompleteness> {
  const params = new URLSearchParams();
  if (locales?.trim()) {
    params.set('locales', locales.trim());
  }
  const response = await fetch(
    `/api/content-cms/projects/${projectId}/completeness${params.size ? `?${params}` : ''}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    await throwCmsError(response, 'Failed to validate CMS package completeness');
  }

  return (await response.json()) as ApiCmsProjectCompleteness;
}

export async function publishCmsProject(
  projectId: number,
  localeTags: string[],
  expectedAuthoringSha256: string,
  expectedPackageSha256: string,
  publishRequestKey: string,
): Promise<ApiCmsPublishSnapshot> {
  const response = await fetch(`/api/content-cms/projects/${projectId}/publish-snapshots`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { ...jsonHeaders, 'Idempotency-Key': publishRequestKey },
    body: JSON.stringify({ localeTags, expectedAuthoringSha256, expectedPackageSha256 }),
  });

  if (!response.ok) {
    await throwCmsError(response, 'Failed to publish CMS snapshot');
  }

  return (await response.json()) as ApiCmsPublishSnapshot;
}
