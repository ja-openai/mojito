import { isTransientHttpError, poll } from '../utils/poller';
import { normalizePollableTaskErrorMessage } from '../utils/pollableTask';

// Keep in sync with com.box.l10n.mojito.entity.review.ReviewProjectStatus
export const REVIEW_PROJECT_STATUSES = ['OPEN', 'CLOSED'] as const;
export type ApiReviewProjectStatus = (typeof REVIEW_PROJECT_STATUSES)[number];

// Keep in sync with com.box.l10n.mojito.entity.review.ReviewProjectType
export const REVIEW_PROJECT_TYPES = [
  'EMERGENCY',
  'NORMAL',
  'BUG_FIXES',
  'TERMINOLOGY',
  'UNKNOWN',
] as const;
export type ApiReviewProjectType = (typeof REVIEW_PROJECT_TYPES)[number];

export const REVIEW_PROJECT_STATUS_LABELS: Record<ApiReviewProjectStatus, string> = {
  OPEN: 'Open',
  CLOSED: 'Closed',
};

export const REVIEW_PROJECT_TYPE_LABELS: Record<ApiReviewProjectType, string> = {
  NORMAL: 'Normal',
  EMERGENCY: 'Emergency',
  BUG_FIXES: 'Bug fixes',
  TERMINOLOGY: 'Terminology',
  UNKNOWN: 'Unknown',
};

export type ApiReviewProjectTextUnit = {
  id: number;
  tmTextUnit: {
    id: number;
    name?: number | string | null;
    content?: string | null;
    comment?: string | null;
    createdDate?: string | null;
    asset?: {
      assetPath?: number | string | null;
      repository?: { id?: number | null; name?: string | null } | null;
    } | null;
    wordCount?: number | null;
  } | null;
  baselineTmTextUnitVariant: {
    id?: number | null;
    content?: string | null;
    status?: string | null;
    includedInLocalizedFile?: boolean | null;
    comment?: string | null;
  } | null;
  currentTmTextUnitVariant: {
    id?: number | null;
    content?: string | null;
    status?: string | null;
    includedInLocalizedFile?: boolean | null;
    comment?: string | null;
  } | null;
  reviewProjectTextUnitDecision?: {
    reviewedTmTextUnitVariantId?: number | null;
    notes?: string | null;
    decisionState?: string | null;
    decisionTmTextUnitVariant?: {
      id?: number | null;
      content?: string | null;
      status?: string | null;
      includedInLocalizedFile?: boolean | null;
      comment?: string | null;
    } | null;
  } | null;
};

export type ApiReviewProjectDetail = {
  id: number;
  createdDate?: string | null;
  dueDate?: string | null;
  closeReason?: string | null;
  textUnitCount?: number | null;
  wordCount?: number | null;
  type: ApiReviewProjectType;
  status: ApiReviewProjectStatus;
  reviewProjectRequest?: {
    id: number | null;
    name?: string | null;
    notes?: string | null;
    createdDate?: string | null;
    createdByUsername?: string | null;
    screenshotImageIds?: string[];
  } | null;
  locale?: { id: number | null; bcp47Tag?: string | null } | null;
  assignment?: {
    teamId?: number | null;
    teamName?: string | null;
    assignedPmUserId?: number | null;
    assignedPmUsername?: string | null;
    assignedTranslatorUserId?: number | null;
    assignedTranslatorUsername?: string | null;
  } | null;
  // New canonical field name from WS
  reviewProjectTextUnits?: ApiReviewProjectTextUnit[];
};

export type ApiReviewProjectAssignmentHistoryEntry = {
  id: number;
  createdDate?: string | null;
  teamId?: number | null;
  teamName?: string | null;
  assignedPmUserId?: number | null;
  assignedPmUsername?: string | null;
  assignedTranslatorUserId?: number | null;
  assignedTranslatorUsername?: string | null;
  eventType: 'CREATED_DEFAULT' | 'ASSIGNED' | 'REASSIGNED' | 'UNASSIGNED';
  note?: string | null;
};

export type ApiReviewProjectAssignmentHistoryResponse = {
  entries: ApiReviewProjectAssignmentHistoryEntry[];
};

export type SearchReviewProjectsResponse = {
  reviewProjects: ApiReviewProjectSummary[];
};

export type SearchReviewProjectRequestsResponse = {
  requestGroups: ApiReviewProjectRequestGroupSummary[];
};

export type ApiReviewProjectSummary = {
  id: number;
  createdDate?: string | null;
  lastModifiedDate?: string | null;
  createdByUsername?: string | null;
  dueDate?: string | null;
  closeReason?: string | null;
  textUnitCount?: number | null;
  wordCount?: number | null;
  acceptedCount?: number | null;
  type: ApiReviewProjectType;
  status: ApiReviewProjectStatus;
  locale?: { id: number | null; bcp47Tag?: string | null } | null;
  reviewProjectRequest?: {
    id: number | null;
    name?: string | null;
    createdByUsername?: string | null;
  } | null;
  assignment?: {
    teamId?: number | null;
    teamName?: string | null;
    assignedPmUserId?: number | null;
    assignedPmUsername?: string | null;
    assignedTranslatorUserId?: number | null;
    assignedTranslatorUsername?: string | null;
  } | null;
};

export type ApiReviewProjectRequestGroupSummary = {
  requestId: number | null;
  requestName?: string | null;
  requestCreatedByUsername?: string | null;
  openProjectCount?: number | null;
  closedProjectCount?: number | null;
  textUnitCount?: number | null;
  wordCount?: number | null;
  acceptedCount?: number | null;
  dueDate?: string | null;
  reviewProjects?: ApiReviewProjectSummary[] | null;
};

export type ReviewProjectsSearchRequest = {
  localeTags?: string[];
  statuses?: ApiReviewProjectStatus[];
  types?: ApiReviewProjectType[];
  createdAfter?: string | null;
  createdBefore?: string | null;
  dueAfter?: string | null;
  dueBefore?: string | null;
  limit?: number;
  searchQuery?: string;
  searchField?: 'NAME' | 'ID' | 'REQUEST_ID' | 'CREATED_BY';
  searchMatchType?: 'CONTAINS' | 'EXACT' | 'ILIKE';
};

export type ReviewProjectCreateRequest = {
  localeTags: string[];
  notes?: string | null;
  tmTextUnitIds?: number[] | null;
  type?: ApiReviewProjectType | null;
  dueDate: string; // ISO string
  screenshotImageIds?: string[] | null;
  name: string;
  teamId?: number | null;
};

export type ReviewProjectCreateResponse = {
  requestId: number;
  requestName?: string | null;
  localeTags: string[];
  dueDate: string;
  projectIds: number[];
};

const jsonHeaders = {
  'Content-Type': 'application/json',
};

type StartCreateReviewProjectRequestResponse = {
  pollableTaskId: number;
};

type PollableTaskStatusResponse = {
  id: number;
  isAllFinished?: boolean;
  allFinished?: boolean;
  errorMessage?: unknown;
};

const REVIEW_PROJECT_CREATE_POLL_INTERVAL_MS = 500;
const REVIEW_PROJECT_CREATE_MAX_POLL_INTERVAL_MS = 8_000;
const REVIEW_PROJECT_CREATE_TIMEOUT_MS = 120_000;

export const searchReviewProjects = async (
  params: ReviewProjectsSearchRequest,
): Promise<SearchReviewProjectsResponse> => {
  const response = await fetch('/api/review-projects/search', {
    method: 'POST',
    credentials: 'include',
    headers: jsonHeaders,
    body: JSON.stringify(params ?? {}),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load review projects');
  }

  return (await response.json()) as SearchReviewProjectsResponse;
};

export const searchReviewProjectRequests = async (
  params: ReviewProjectsSearchRequest,
): Promise<SearchReviewProjectRequestsResponse> => {
  const response = await fetch('/api/review-project-requests/search', {
    method: 'POST',
    credentials: 'include',
    headers: jsonHeaders,
    body: JSON.stringify(params ?? {}),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load review project requests');
  }

  return (await response.json()) as SearchReviewProjectRequestsResponse;
};

export const fetchReviewProjects = async (): Promise<ApiReviewProjectSummary[]> => {
  const res = await searchReviewProjects({});
  return res.reviewProjects ?? [];
};

export const createReviewProjectRequest = async (
  payload: ReviewProjectCreateRequest,
): Promise<ReviewProjectCreateResponse> => {
  const startResponse = await fetch('/api/review-project-requests', {
    method: 'POST',
    credentials: 'include',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!startResponse.ok) {
    const message = await startResponse.text().catch(() => '');
    throw new Error(message || 'Failed to create review project');
  }

  const { pollableTaskId } =
    (await startResponse.json()) as StartCreateReviewProjectRequestResponse;

  const completedTask = await waitForCreateReviewProjectTaskToFinish(pollableTaskId);
  const normalizedErrorMessage = normalizePollableTaskErrorMessage(completedTask.errorMessage);
  if (normalizedErrorMessage) {
    throw new Error(normalizedErrorMessage);
  }

  const outputResponse = await fetch(`/api/pollableTasks/${pollableTaskId}/output`, {
    method: 'GET',
    credentials: 'include',
    headers: { Accept: 'application/json' },
  });

  if (!outputResponse.ok) {
    const message = await outputResponse.text().catch(() => '');
    throw new Error(message || 'Failed to create review project');
  }

  return (await outputResponse.json()) as ReviewProjectCreateResponse;
};

async function waitForCreateReviewProjectTaskToFinish(
  pollableTaskId: number,
): Promise<PollableTaskStatusResponse> {
  return poll(() => fetchPollableTaskStatus(pollableTaskId), {
    intervalMs: REVIEW_PROJECT_CREATE_POLL_INTERVAL_MS,
    maxIntervalMs: REVIEW_PROJECT_CREATE_MAX_POLL_INTERVAL_MS,
    timeoutMs: REVIEW_PROJECT_CREATE_TIMEOUT_MS,
    timeoutMessage: 'Timed out while creating review project',
    isTransientError: isTransientHttpError,
    shouldStop: (task) => task.isAllFinished ?? task.allFinished ?? false,
  });
}

async function fetchPollableTaskStatus(pollableTaskId: number): Promise<PollableTaskStatusResponse> {
  const response = await fetch(`/api/pollableTasks/${pollableTaskId}`, {
    method: 'GET',
    credentials: 'include',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to poll review project creation status');
  }

  return (await response.json()) as PollableTaskStatusResponse;
}

export const fetchReviewProjectDetail = async (
  projectId: number,
): Promise<ApiReviewProjectDetail> => {
  const response = await fetch(`/api/review-projects/${projectId}`, {
    method: 'GET',
    credentials: 'include',
    headers: jsonHeaders,
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load review project');
  }

  return (await response.json()) as ApiReviewProjectDetail;
};

export const updateReviewProjectStatus = async (
  projectId: number,
  status: ApiReviewProjectStatus,
): Promise<ApiReviewProjectDetail> => {
  const response = await fetch(`/api/review-projects/${projectId}/status`, {
    method: 'POST',
    credentials: 'include',
    headers: jsonHeaders,
    body: JSON.stringify({ status }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to update review project status');
  }

  return (await response.json()) as ApiReviewProjectDetail;
};

export const updateReviewProjectRequest = async (
  projectId: number,
  payload: {
    name: string;
    notes?: string | null;
    type?: ApiReviewProjectType | null;
    dueDate?: string | null;
    screenshotImageIds?: string[] | null;
  },
): Promise<ApiReviewProjectDetail> => {
  const response = await fetch(`/api/review-projects/${projectId}/request`, {
    method: 'POST',
    credentials: 'include',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to update review project request');
  }

  return (await response.json()) as ApiReviewProjectDetail;
};

export const updateReviewProjectAssignment = async (
  projectId: number,
  payload: {
    teamId?: number | null;
    assignedPmUserId?: number | null;
    assignedTranslatorUserId?: number | null;
    note?: string | null;
  },
): Promise<ApiReviewProjectDetail> => {
  const response = await fetch(`/api/review-projects/${projectId}/assignment`, {
    method: 'POST',
    credentials: 'include',
    headers: jsonHeaders,
    body: JSON.stringify(payload ?? {}),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to update review project assignment');
  }

  return (await response.json()) as ApiReviewProjectDetail;
};

export const updateReviewProjectRequestPmAssignment = async (
  requestId: number,
  payload: {
    assignedPmUserId?: number | null;
    note?: string | null;
  },
): Promise<AdminBatchActionResponse> => {
  const response = await fetch(`/api/review-project-requests/${requestId}/assignment/pm`, {
    method: 'POST',
    credentials: 'include',
    headers: jsonHeaders,
    body: JSON.stringify(payload ?? {}),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to update request PM assignment');
  }

  return (await response.json()) as AdminBatchActionResponse;
};

export const fetchReviewProjectAssignmentHistory = async (
  projectId: number,
): Promise<ApiReviewProjectAssignmentHistoryResponse> => {
  const response = await fetch(`/api/review-projects/${projectId}/assignment-history`, {
    method: 'GET',
    credentials: 'include',
    headers: jsonHeaders,
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load review project assignment history');
  }

  return (await response.json()) as ApiReviewProjectAssignmentHistoryResponse;
};

export type AdminBatchActionResponse = {
  affectedCount: number;
};

export const adminBatchUpdateReviewProjectStatus = async (
  projectIds: number[],
  status: ApiReviewProjectStatus,
): Promise<AdminBatchActionResponse> => {
  const response = await fetch('/api/admin/review-projects/status', {
    method: 'POST',
    credentials: 'include',
    headers: jsonHeaders,
    body: JSON.stringify({ projectIds, status }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to batch update review project status');
  }

  return (await response.json()) as AdminBatchActionResponse;
};

export const adminBatchDeleteReviewProjects = async (
  projectIds: number[],
): Promise<AdminBatchActionResponse> => {
  const response = await fetch('/api/admin/review-projects/delete', {
    method: 'POST',
    credentials: 'include',
    headers: jsonHeaders,
    body: JSON.stringify({ projectIds }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to delete review projects');
  }

  return (await response.json()) as AdminBatchActionResponse;
};

export const saveReviewProjectTextUnitDecision = async ({
  textUnitId,
  target,
  comment,
  status,
  includedInLocalizedFile,
  decisionState,
  expectedCurrentTmTextUnitVariantId,
  overrideChangedCurrent = false,
  decisionNotes,
}: {
  textUnitId: number;
  target: string;
  comment: string | null;
  status: string;
  includedInLocalizedFile: boolean;
  decisionState: 'PENDING' | 'DECIDED';
  expectedCurrentTmTextUnitVariantId?: number | null;
  overrideChangedCurrent?: boolean;
  decisionNotes?: string | null;
}): Promise<ApiReviewProjectTextUnit> => {
  const response = await fetch(`/api/review-project-text-units/${textUnitId}/decision`, {
    method: 'POST',
    credentials: 'include',
    headers: jsonHeaders,
    body: JSON.stringify({
      target,
      comment,
      status,
      includedInLocalizedFile,
      decisionState,
      expectedCurrentTmTextUnitVariantId,
      overrideChangedCurrent,
      decisionNotes,
    }),
  });

  const responseClone = response.clone();
  let data: ApiReviewProjectTextUnit | null = null;
  try {
    data = (await response.json()) as ApiReviewProjectTextUnit;
  } catch {
    data = null;
  }

  if (!response.ok) {
    const message = await responseClone.text().catch(() => '');
    const error = new Error(message || 'Failed to save text unit decision') as Error & {
      status?: number;
      data?: ApiReviewProjectTextUnit | null;
    };
    error.status = response.status;
    error.data = data;
    throw error;
  }

  return data as ApiReviewProjectTextUnit;
};

export const setReviewProjectTextUnitDecisionState = async ({
  textUnitId,
  decisionState,
  expectedCurrentTmTextUnitVariantId,
  overrideChangedCurrent = false,
}: {
  textUnitId: number;
  decisionState: 'PENDING' | 'DECIDED';
  expectedCurrentTmTextUnitVariantId?: number | null;
  overrideChangedCurrent?: boolean;
}): Promise<ApiReviewProjectTextUnit> => {
  const response = await fetch(`/api/review-project-text-units/${textUnitId}/decision`, {
    method: 'POST',
    credentials: 'include',
    headers: jsonHeaders,
    body: JSON.stringify({
      decisionState,
      expectedCurrentTmTextUnitVariantId,
      overrideChangedCurrent,
    }),
  });

  const responseClone = response.clone();
  let data: ApiReviewProjectTextUnit | null = null;
  try {
    data = (await response.json()) as ApiReviewProjectTextUnit;
  } catch {
    data = null;
  }

  if (!response.ok) {
    const message = await responseClone.text().catch(() => '');
    const error = new Error(message || 'Failed to update decision state') as Error & {
      status?: number;
      data?: ApiReviewProjectTextUnit | null;
    };
    error.status = response.status;
    error.data = data;
    throw error;
  }

  return data as ApiReviewProjectTextUnit;
};
