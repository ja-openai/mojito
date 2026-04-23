import { normalizePollableTaskErrorMessage } from '../utils/pollableTask';
import { isTransientHttpError, poll } from '../utils/poller';

export type ApiGlossaryRepositoryRef = {
  id: number;
  name: string;
};

export type ApiGlossarySummary = {
  id: number;
  createdDate?: string | null;
  lastModifiedDate?: string | null;
  name: string;
  description?: string | null;
  enabled: boolean;
  priority: number;
  scopeMode: 'GLOBAL' | 'SELECTED_REPOSITORIES';
  assetPath: string;
  repositoryCount: number;
  backingRepository: ApiGlossaryRepositoryRef;
};

export type ApiGlossaryDetail = {
  id: number;
  createdDate?: string | null;
  lastModifiedDate?: string | null;
  name: string;
  description?: string | null;
  enabled: boolean;
  priority: number;
  scopeMode: 'GLOBAL' | 'SELECTED_REPOSITORIES';
  backingRepository: ApiGlossaryRepositoryRef;
  assetPath: string;
  localeTags: string[];
  repositories: ApiGlossaryRepositoryRef[];
  excludedRepositories: ApiGlossaryRepositoryRef[];
};

export type ApiGlossaryTermTranslation = {
  localeTag: string;
  target?: string | null;
  targetComment?: string | null;
  status?: string | null;
};

export type ApiGlossaryTermEvidence = {
  id?: number | null;
  evidenceType: 'SCREENSHOT' | 'STRING_USAGE' | 'CODE_REF' | 'NOTE';
  caption?: string | null;
  imageKey?: string | null;
  tmTextUnitId?: number | null;
  cropX?: number | null;
  cropY?: number | null;
  cropWidth?: number | null;
  cropHeight?: number | null;
  sortOrder?: number | null;
};

export type ApiGlossaryTerm = {
  metadataId?: number | null;
  tmTextUnitId: number;
  termKey: string;
  source: string;
  sourceComment?: string | null;
  definition?: string | null;
  partOfSpeech?: string | null;
  termType?: string | null;
  enforcement?: string | null;
  status?: string | null;
  provenance?: string | null;
  caseSensitive: boolean;
  doNotTranslate: boolean;
  translations: ApiGlossaryTermTranslation[];
  evidence: ApiGlossaryTermEvidence[];
};

export type ApiGlossaryTermsResponse = {
  terms: ApiGlossaryTerm[];
  totalCount: number;
  localeTags: string[];
};

export type ApiGlossaryWorkspaceSummary = {
  totalTerms: number;
  approvedTermCount: number;
  candidateTermCount: number;
  deprecatedTermCount: number;
  rejectedTermCount: number;
  doNotTranslateTermCount: number;
  termsWithEvidenceCount: number;
  termsMissingAnyTranslationCount: number;
  missingTranslationCount: number;
  fullyTranslatedTermCount: number;
  publishReadyTermCount: number;
  truncated: boolean;
};

export type ApiGlossariesResponse = {
  glossaries: ApiGlossarySummary[];
  totalCount: number;
};

export type ApiMatchedGlossaryTerm = {
  glossaryId?: number | null;
  glossaryName?: string | null;
  tmTextUnitId: number;
  source: string;
  comment?: string | null;
  definition?: string | null;
  partOfSpeech?: string | null;
  termType?: string | null;
  enforcement?: string | null;
  status?: string | null;
  provenance?: string | null;
  target?: string | null;
  targetComment?: string | null;
  doNotTranslate: boolean;
  caseSensitive: boolean;
  matchType: string;
  startIndex: number;
  endIndex: number;
  matchedText: string;
  evidence: ApiGlossaryTermEvidence[];
};

export type ApiUpsertGlossaryRequest = {
  name: string;
  description?: string | null;
  enabled?: boolean | null;
  priority?: number | null;
  scopeMode?: 'GLOBAL' | 'SELECTED_REPOSITORIES' | null;
  localeTags?: string[];
  repositoryIds?: number[];
  excludedRepositoryIds?: number[];
};

export type ApiImportGlossaryResponse = {
  createdTermCount: number;
  updatedTermCount: number;
  createdTranslationCount: number;
  updatedTranslationCount: number;
};

export type ApiUpsertGlossaryTermRequest = {
  termKey?: string | null;
  source: string;
  sourceComment?: string | null;
  definition?: string | null;
  partOfSpeech?: string | null;
  termType?: string | null;
  enforcement?: string | null;
  status?: string | null;
  provenance?: string | null;
  caseSensitive?: boolean | null;
  doNotTranslate?: boolean | null;
  replaceTerm?: boolean | null;
  copyTranslationsOnReplace?: boolean | null;
  copyTranslationStatus?: 'KEEP_CURRENT' | 'REVIEW_NEEDED' | 'APPROVED' | null;
  translations?: Array<{
    localeTag: string;
    target?: string | null;
    targetComment?: string | null;
  }>;
  evidence?: Array<{
    evidenceType: 'SCREENSHOT' | 'STRING_USAGE' | 'CODE_REF' | 'NOTE';
    caption?: string | null;
    imageKey?: string | null;
    tmTextUnitId?: number | null;
    cropX?: number | null;
    cropY?: number | null;
    cropWidth?: number | null;
    cropHeight?: number | null;
  }>;
};

export type ApiBatchUpdateGlossaryTermsRequest = {
  tmTextUnitIds: number[];
  partOfSpeech?: string | null;
  termType?: string | null;
  enforcement?: string | null;
  status?: string | null;
  provenance?: string | null;
  caseSensitive?: boolean | null;
  doNotTranslate?: boolean | null;
};

export type ApiBatchUpdateGlossaryTermsResponse = {
  updatedTermCount: number;
};

export type ApiExtractGlossaryTermsRequest = {
  repositoryIds: number[];
  limit?: number | null;
  minOccurrences?: number | null;
  scanLimit?: number | null;
};

export type ApiExtractedGlossaryCandidate = {
  term: string;
  occurrenceCount: number;
  repositoryCount: number;
  repositories: string[];
  sampleSources: string[];
  suggestedTermType: string;
  suggestedProvenance: string;
  existingInGlossary: boolean;
  confidence: number;
  definition?: string | null;
  rationale?: string | null;
  suggestedPartOfSpeech?: string | null;
  suggestedEnforcement?: string | null;
  suggestedDoNotTranslate: boolean;
  extractionMethod?: string | null;
};

export type ApiExtractGlossaryTermsResponse = {
  candidates: ApiExtractedGlossaryCandidate[];
};

type PollableTaskResponse = {
  id: number;
  isAllFinished?: boolean;
  allFinished?: boolean;
  errorMessage?: string | null;
};

export type ApiPollableTask = {
  id: number;
  isAllFinished: boolean;
  errorMessage?: string | null;
};

export type ApiStartGlossaryExtractionResponse = {
  pollableTask: PollableTaskResponse;
};

export type ApiGlossaryTranslationProposal = {
  id: number;
  createdDate?: string | null;
  lastModifiedDate?: string | null;
  tmTextUnitId: number;
  source?: string | null;
  localeTag: string;
  proposedTarget: string;
  proposedTargetComment?: string | null;
  note?: string | null;
  status: string;
  reviewerNote?: string | null;
};

export type ApiGlossaryTranslationProposalsResponse = {
  proposals: ApiGlossaryTranslationProposal[];
  totalCount: number;
};

export type ApiMatchGlossaryTermsRequest = {
  repositoryId?: number | null;
  repositoryName?: string | null;
  glossaryName?: string | null;
  localeTag: string;
  sourceText: string;
  excludeTmTextUnitId?: number | null;
};

export type ApiMatchGlossaryTermsResponse = {
  matchedTerms: ApiMatchedGlossaryTerm[];
};

export async function fetchGlossaries(options?: {
  search?: string;
  enabled?: boolean | null;
  limit?: number;
}): Promise<ApiGlossariesResponse> {
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

  const response = await fetch(`/api/glossaries${params.size ? `?${params.toString()}` : ''}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load glossaries');
  }

  return (await response.json()) as ApiGlossariesResponse;
}

export async function fetchGlossary(glossaryId: number): Promise<ApiGlossaryDetail> {
  const response = await fetch(`/api/glossaries/${glossaryId}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load glossary');
  }

  return (await response.json()) as ApiGlossaryDetail;
}

export async function fetchGlossaryTerms(
  glossaryId: number,
  options?: {
    search?: string;
    localeTags?: string[];
    limit?: number;
  },
): Promise<ApiGlossaryTermsResponse> {
  const params = new URLSearchParams();
  if (options?.search?.trim()) {
    params.set('search', options.search.trim());
  }
  options?.localeTags?.forEach((localeTag) => {
    const normalized = localeTag.trim();
    if (normalized) {
      params.append('locale', normalized);
    }
  });
  if (typeof options?.limit === 'number') {
    params.set('limit', String(options.limit));
  }

  const response = await fetch(
    `/api/glossaries/${glossaryId}/terms${params.size ? `?${params.toString()}` : ''}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load glossary terms');
  }

  return (await response.json()) as ApiGlossaryTermsResponse;
}

export async function fetchGlossaryWorkspaceSummary(
  glossaryId: number,
): Promise<ApiGlossaryWorkspaceSummary> {
  const response = await fetch(`/api/glossaries/${glossaryId}/workspace-summary`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load glossary workspace summary');
  }

  return (await response.json()) as ApiGlossaryWorkspaceSummary;
}

export async function createGlossary(
  request: ApiUpsertGlossaryRequest,
): Promise<ApiGlossaryDetail> {
  const response = await fetch('/api/glossaries', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to create glossary');
  }

  return (await response.json()) as ApiGlossaryDetail;
}

export async function updateGlossary(
  glossaryId: number,
  request: ApiUpsertGlossaryRequest,
): Promise<ApiGlossaryDetail> {
  const response = await fetch(`/api/glossaries/${glossaryId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to update glossary');
  }

  return (await response.json()) as ApiGlossaryDetail;
}

export async function createGlossaryTerm(
  glossaryId: number,
  request: ApiUpsertGlossaryTermRequest,
): Promise<ApiGlossaryTerm> {
  const response = await fetch(`/api/glossaries/${glossaryId}/terms`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to create glossary term');
  }

  return (await response.json()) as ApiGlossaryTerm;
}

export async function updateGlossaryTerm(
  glossaryId: number,
  tmTextUnitId: number,
  request: ApiUpsertGlossaryTermRequest,
): Promise<ApiGlossaryTerm> {
  const response = await fetch(`/api/glossaries/${glossaryId}/terms/${tmTextUnitId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to update glossary term');
  }

  return (await response.json()) as ApiGlossaryTerm;
}

export async function batchUpdateGlossaryTerms(
  glossaryId: number,
  request: ApiBatchUpdateGlossaryTermsRequest,
): Promise<ApiBatchUpdateGlossaryTermsResponse> {
  const response = await fetch(`/api/glossaries/${glossaryId}/terms/batch`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to batch update glossary terms');
  }

  return (await response.json()) as ApiBatchUpdateGlossaryTermsResponse;
}

export async function extractGlossaryTerms(
  glossaryId: number,
  request: ApiExtractGlossaryTermsRequest,
): Promise<ApiExtractGlossaryTermsResponse> {
  const startResponse = await startGlossaryTermExtraction(glossaryId, request);
  const completedTask = await waitForGlossaryExtractionTask(startResponse.id);
  if (completedTask.errorMessage) {
    throw new Error(normalizePollableTaskErrorMessage(completedTask.errorMessage));
  }
  return fetchGlossaryExtractionOutput(startResponse.id);
}

export async function startGlossaryTermExtraction(
  glossaryId: number,
  request: ApiExtractGlossaryTermsRequest,
): Promise<ApiPollableTask> {
  const response = await fetch(`/api/glossaries/${glossaryId}/extract`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to extract glossary candidates');
  }

  const payload = (await response.json()) as ApiStartGlossaryExtractionResponse;
  return normalizePollableTask(payload.pollableTask);
}

export async function fetchGlossaryExtractionTask(
  pollableTaskId: number,
): Promise<ApiPollableTask> {
  const response = await fetch(`/api/pollableTasks/${pollableTaskId}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to poll glossary extraction status');
  }

  const payload = (await response.json()) as PollableTaskResponse;
  return normalizePollableTask(payload);
}

export async function waitForGlossaryExtractionTask(
  pollableTaskId: number,
  timeoutMs = 180_000,
): Promise<ApiPollableTask> {
  return poll(() => fetchGlossaryExtractionTask(pollableTaskId), {
    intervalMs: 1000,
    maxIntervalMs: 8000,
    timeoutMs,
    timeoutMessage: 'Timed out while waiting for glossary extraction',
    isTransientError: isTransientHttpError,
    shouldStop: (task) => task.isAllFinished,
  });
}

export async function fetchGlossaryExtractionOutput(
  pollableTaskId: number,
): Promise<ApiExtractGlossaryTermsResponse> {
  const response = await fetch(`/api/pollableTasks/${pollableTaskId}/output`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load glossary extraction output');
  }

  return (await response.json()) as ApiExtractGlossaryTermsResponse;
}

function normalizePollableTask(task: PollableTaskResponse): ApiPollableTask {
  const rawMessage = task.errorMessage;
  const normalizedMessage =
    typeof rawMessage === 'string' ? rawMessage : rawMessage ? JSON.stringify(rawMessage) : null;

  return {
    id: task.id,
    isAllFinished: task.isAllFinished ?? task.allFinished ?? false,
    errorMessage: normalizedMessage,
  };
}

export async function deleteGlossary(glossaryId: number): Promise<void> {
  const response = await fetch(`/api/glossaries/${glossaryId}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to delete glossary');
  }
}

export async function deleteGlossaryTerm(glossaryId: number, tmTextUnitId: number): Promise<void> {
  const response = await fetch(`/api/glossaries/${glossaryId}/terms/${tmTextUnitId}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to delete glossary term');
  }
}

export async function submitGlossaryTranslationProposal(
  glossaryId: number,
  tmTextUnitId: number,
  payload: {
    localeTag: string;
    target: string;
    targetComment?: string | null;
    note?: string | null;
  },
): Promise<ApiGlossaryTranslationProposal> {
  const response = await fetch(`/api/glossaries/${glossaryId}/terms/${tmTextUnitId}/proposals`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to submit glossary proposal');
  }

  return (await response.json()) as ApiGlossaryTranslationProposal;
}

export async function fetchGlossaryTranslationProposals(
  glossaryId: number,
  options?: { status?: string; limit?: number },
): Promise<ApiGlossaryTranslationProposalsResponse> {
  const params = new URLSearchParams();
  if (options?.status?.trim()) {
    params.set('status', options.status.trim());
  }
  if (typeof options?.limit === 'number') {
    params.set('limit', String(options.limit));
  }

  const response = await fetch(
    `/api/glossaries/${glossaryId}/proposals${params.size ? `?${params.toString()}` : ''}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load glossary proposals');
  }

  return (await response.json()) as ApiGlossaryTranslationProposalsResponse;
}

export async function decideGlossaryTranslationProposal(
  glossaryId: number,
  proposalId: number,
  payload: { status: 'ACCEPTED' | 'REJECTED'; reviewerNote?: string | null },
): Promise<ApiGlossaryTranslationProposal> {
  const response = await fetch(`/api/glossaries/${glossaryId}/proposals/${proposalId}/decision`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to decide glossary proposal');
  }

  return (await response.json()) as ApiGlossaryTranslationProposal;
}

export async function matchGlossaryTerms(
  request: ApiMatchGlossaryTermsRequest,
): Promise<ApiMatchGlossaryTermsResponse> {
  const response = await fetch('/api/glossaries/match', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to match glossary terms');
  }

  return (await response.json()) as ApiMatchGlossaryTermsResponse;
}

export async function exportGlossary(glossaryId: number): Promise<Blob> {
  const response = await fetch(`/api/glossaries/${glossaryId}/export?format=json`, {
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to export glossary');
  }

  return await response.blob();
}

export async function importGlossary(
  glossaryId: number,
  request: { content: string },
): Promise<ApiImportGlossaryResponse> {
  const response = await fetch(`/api/glossaries/${glossaryId}/import`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    credentials: 'same-origin',
    body: JSON.stringify({ format: 'json', content: request.content }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to import glossary');
  }

  return (await response.json()) as ApiImportGlossaryResponse;
}
