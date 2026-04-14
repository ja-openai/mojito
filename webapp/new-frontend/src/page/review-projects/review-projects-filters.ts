import type { ApiReviewProjectStatus } from '../../api/review-projects';

export const REQUEST_STATUS_FILTERS = ['all', 'open', 'closed'] as const;
export type RequestStatusFilter = (typeof REQUEST_STATUS_FILTERS)[number];

export const COMPLETION_FILTERS = ['all', 'not_100', '100'] as const;
export type CompletionFilter = (typeof COMPLETION_FILTERS)[number];

type ProgressSource = {
  decidedCount: number;
  textUnitCount: number | null;
};

type ProjectStatusSource = ProgressSource & {
  status: ApiReviewProjectStatus;
};

type RequestGroupSource = ProgressSource & {
  openProjectCount: number;
  closedProjectCount: number;
  projects: ProjectStatusSource[];
};

const toFiniteNonNegative = (value: unknown) => {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    return 0;
  }
  return parsed < 0 ? 0 : parsed;
};

export const getCompletionState = (decidedValue: unknown, totalValue: unknown) => {
  const decided = toFiniteNonNegative(decidedValue);
  const total = toFiniteNonNegative(totalValue);
  return {
    decided,
    total,
    isComplete: total === 0 || decided >= total,
    hasWorkRemaining: total > 0 && decided < total,
  };
};

export const matchesCompletionFilter = (
  decidedValue: unknown,
  totalValue: unknown,
  filter: CompletionFilter,
) => {
  if (filter === 'all') {
    return true;
  }
  const { isComplete } = getCompletionState(decidedValue, totalValue);
  return filter === '100' ? isComplete : !isComplete;
};

export const matchesProjectStatusFilter = (
  status: ApiReviewProjectStatus,
  filter: ApiReviewProjectStatus | 'all',
) => filter === 'all' || status === filter;

export const getVisibleProjectsForRequest = <T extends ProjectStatusSource>(
  projects: T[],
  statusFilter: ApiReviewProjectStatus | 'all',
  completionFilter: CompletionFilter = 'all',
) =>
  projects.filter(
    (project) =>
      matchesProjectStatusFilter(project.status, statusFilter) &&
      matchesCompletionFilter(project.decidedCount, project.textUnitCount, completionFilter),
  );

export const matchesRequestStatusFilter = (
  group: RequestGroupSource,
  filter: RequestStatusFilter,
) => {
  if (filter === 'all') {
    return true;
  }
  const totalProjectCount = group.openProjectCount + group.closedProjectCount;
  const isClosed =
    totalProjectCount > 0
      ? group.openProjectCount === 0
      : group.projects.length > 0 && group.projects.every((project) => project.status === 'CLOSED');
  return filter === 'closed' ? isClosed : !isClosed;
};
