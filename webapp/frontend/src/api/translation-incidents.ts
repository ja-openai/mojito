export type ApiTranslationIncidentStatus = 'OPEN' | 'CLOSED';

export type ApiTranslationIncidentResolution =
  | 'READY_TO_REJECT'
  | 'PENDING_REVIEW'
  | 'REJECTED'
  | 'REJECT_FAILED';

export type ApiTranslationIncidentSummary = {
  id: number;
  status: ApiTranslationIncidentStatus;
  resolution: ApiTranslationIncidentResolution;
  repositoryName: string | null;
  stringId: string;
  observedLocale: string;
  resolvedLocale: string | null;
  reason: string;
  sourceReference: string | null;
  lookupCandidateCount: number;
  canReject: boolean;
  reviewProjectName: string | null;
  reviewProjectConfidence: string | null;
  selectedTranslationStatus: string | null;
  createdDate: string;
  lastModifiedDate: string;
  rejectedAt: string | null;
  closedAt: string | null;
  closedByUsername: string | null;
  incidentLink: string | null;
};

export type ApiTranslationIncidentLookupCandidate = {
  repositoryName: string | null;
  tmTextUnitId: number | null;
  textUnitLink: string | null;
  tmTextUnitCurrentVariantId: number | null;
  tmTextUnitVariantId: number | null;
  assetPath: string | null;
  source: string | null;
  target: string | null;
  targetComment: string | null;
  status: string | null;
  includedInLocalizedFile: boolean;
  canReject: boolean;
};

export type ApiTranslationIncidentReviewProjectCandidate = {
  reviewProjectId: number | null;
  reviewProjectName: string | null;
  reviewProjectLink: string | null;
  reviewProjectRequestId: number | null;
  reviewProjectRequestLink: string | null;
  confidence: string | null;
  confidenceScore: number;
  confidenceReasons: string[];
  reviewerUsername: string | null;
  ownerUsername: string | null;
};

export type ApiTranslationIncidentDetail = {
  id: number;
  status: ApiTranslationIncidentStatus;
  resolution: ApiTranslationIncidentResolution;
  repositoryName: string | null;
  stringId: string;
  observedLocale: string;
  resolvedLocale: string | null;
  lookupResolutionStatus: string;
  localeResolutionStrategy: string;
  localeUsedFallback: boolean;
  reason: string;
  sourceReference: string | null;
  lookupCandidateCount: number;
  incidentLink: string | null;
  selectedTmTextUnitId: number | null;
  selectedTextUnitLink: string | null;
  selectedTmTextUnitCurrentVariantId: number | null;
  selectedTmTextUnitVariantId: number | null;
  selectedAssetPath: string | null;
  selectedSource: string | null;
  selectedTarget: string | null;
  selectedTargetComment: string | null;
  selectedTranslationStatus: string | null;
  selectedIncludedInLocalizedFile: boolean | null;
  canReject: boolean;
  reviewProjectId: number | null;
  reviewProjectRequestId: number | null;
  reviewProjectName: string | null;
  reviewProjectLink: string | null;
  reviewProjectConfidence: string | null;
  reviewProjectConfidenceScore: number | null;
  translationAuthorUsername: string | null;
  reviewerUsername: string | null;
  ownerUsername: string | null;
  translationAuthorSlackMention: string | null;
  reviewerSlackMention: string | null;
  ownerSlackMention: string | null;
  slackDestinationSource: string | null;
  slackChannelId: string | null;
  slackThreadTs: string | null;
  slackCanSend: boolean | null;
  slackNote: string | null;
  slackDraft: string | null;
  lookupCandidates: ApiTranslationIncidentLookupCandidate[];
  reviewProjectCandidates: ApiTranslationIncidentReviewProjectCandidate[];
  rejectAuditComment: string | null;
  rejectAuditCommentId: number | null;
  rejectedByUsername: string | null;
  closedAt: string | null;
  closedByUsername: string | null;
  createdDate: string;
  lastModifiedDate: string;
  rejectedAt: string | null;
};

export type ApiTranslationIncidentPage = {
  items: ApiTranslationIncidentSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
};

const jsonHeaders = {
  'Content-Type': 'application/json',
};

export async function fetchTranslationIncidents(options?: {
  status?: ApiTranslationIncidentStatus | null;
  query?: string | null;
  createdAfter?: string | null;
  createdBefore?: string | null;
  page?: number;
  size?: number;
}): Promise<ApiTranslationIncidentPage> {
  const params = new URLSearchParams();
  if (options?.status) {
    params.set('status', options.status);
  }
  if (options?.query) {
    params.set('query', options.query);
  }
  if (options?.createdAfter) {
    params.set('createdAfter', options.createdAfter);
  }
  if (options?.createdBefore) {
    params.set('createdBefore', options.createdBefore);
  }
  if (typeof options?.page === 'number') {
    params.set('page', String(options.page));
  }
  if (typeof options?.size === 'number') {
    params.set('size', String(options.size));
  }

  const response = await fetch(
    `/api/translation-incidents${params.size > 0 ? `?${params.toString()}` : ''}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load translation incidents');
  }

  return (await response.json()) as ApiTranslationIncidentPage;
}

export async function fetchTranslationIncident(
  incidentId: number,
): Promise<ApiTranslationIncidentDetail> {
  const response = await fetch(`/api/translation-incidents/${incidentId}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load translation incident');
  }

  return (await response.json()) as ApiTranslationIncidentDetail;
}

export async function rejectTranslationIncident(
  incidentId: number,
  comment: string | null,
): Promise<ApiTranslationIncidentDetail> {
  const response = await fetch(`/api/translation-incidents/${incidentId}/reject`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify({ comment }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to reject translation incident');
  }

  return (await response.json()) as ApiTranslationIncidentDetail;
}

export async function updateTranslationIncidentStatus(
  incidentId: number,
  status: ApiTranslationIncidentStatus,
): Promise<ApiTranslationIncidentDetail> {
  const response = await fetch(`/api/translation-incidents/${incidentId}/status`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify({ status }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to update translation incident status');
  }

  return (await response.json()) as ApiTranslationIncidentDetail;
}

export async function sendTranslationIncidentSlack(
  incidentId: number,
): Promise<ApiTranslationIncidentDetail> {
  const response = await fetch(`/api/translation-incidents/${incidentId}/send-slack`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to send Slack notification');
  }

  return (await response.json()) as ApiTranslationIncidentDetail;
}
