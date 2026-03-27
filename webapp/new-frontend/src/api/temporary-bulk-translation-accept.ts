export type BulkTranslationAcceptSelector =
  | 'PHRASE_IMPORTED_NEEDS_REVIEW'
  | 'NEEDS_REVIEW_OLDER_THAN';

export type BulkTranslationAcceptRequest = {
  selector: BulkTranslationAcceptSelector;
  repositoryIds: number[];
  createdBeforeDate?: string | null;
};

export type BulkTranslationAcceptRepositoryCount = {
  repositoryId: number;
  repositoryName: string;
  matchedCount: number;
};

export type BulkTranslationAcceptResponse = {
  totalCount: number;
  repositoryCounts: BulkTranslationAcceptRepositoryCount[];
};

const jsonHeaders = {
  'Content-Type': 'application/json',
};

async function postBulkTranslationAccept(
  path: 'dry-run' | 'execute',
  payload: BulkTranslationAcceptRequest,
): Promise<BulkTranslationAcceptResponse> {
  const response = await fetch(`/api/admin/temporary-bulk-translation-accept/${path}`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Bulk translation accept request failed');
  }

  return (await response.json()) as BulkTranslationAcceptResponse;
}

export function dryRunBulkTranslationAccept(
  payload: BulkTranslationAcceptRequest,
): Promise<BulkTranslationAcceptResponse> {
  return postBulkTranslationAccept('dry-run', payload);
}

export function executeBulkTranslationAccept(
  payload: BulkTranslationAcceptRequest,
): Promise<BulkTranslationAcceptResponse> {
  return postBulkTranslationAccept('execute', payload);
}
