import { loadSessionTokenPayload, saveSessionTokenPayload } from '../../utils/sessionTokenStore';
import type {
  RepositoryMetric,
  RepositoryReviewCoverageFilter,
  RepositoryStatusFilter,
} from './RepositoriesPageView';

const STORAGE_PREFIX = 'repositories.searchState.v1:';
const STORAGE_MAX_ENTRIES = 24;

const STATUS_FILTER_VALUES: ReadonlySet<RepositoryStatusFilter> = new Set([
  'all',
  'rejected',
  'needs-translation',
  'needs-review',
]);
const REVIEW_COVERAGE_FILTER_VALUES: ReadonlySet<RepositoryReviewCoverageFilter> = new Set([
  'all',
  'missing-enabled-review-feature',
]);
const METRIC_VALUES: ReadonlySet<RepositoryMetric> = new Set(['textUnits', 'words']);

export const REPOSITORIES_SESSION_QUERY_KEY = 'rs';

export type RepositoriesSessionState = {
  selectedRepositoryId: number | null;
  selectedRepositoryIds: number[];
  repositorySelectionTouched: boolean;
  selectedLocaleTags: string[];
  localeSelectionTouched: boolean;
  statusFilter: RepositoryStatusFilter;
  reviewCoverageFilter: RepositoryReviewCoverageFilter;
  metric: RepositoryMetric;
};

function normalizeRepositoryIds(values: unknown): number[] {
  if (!Array.isArray(values)) {
    return [];
  }

  const unique = new Set<number>();
  values.forEach((value) => {
    const next = typeof value === 'number' ? value : Number(value);
    if (Number.isInteger(next) && next > 0) {
      unique.add(next);
    }
  });

  return Array.from(unique).sort((first, second) => first - second);
}

function normalizeLocaleTags(values: unknown): string[] {
  if (!Array.isArray(values)) {
    return [];
  }

  const byKey = new Map<string, string>();
  values.forEach((value) => {
    if (typeof value !== 'string') {
      return;
    }
    const trimmed = value.trim();
    if (!trimmed) {
      return;
    }
    const key = trimmed.toLowerCase();
    if (!byKey.has(key)) {
      byKey.set(key, trimmed);
    }
  });

  return Array.from(byKey.values()).sort((first, second) =>
    first.localeCompare(second, undefined, { sensitivity: 'base' }),
  );
}

function normalizeStatusFilter(value: unknown): RepositoryStatusFilter {
  if (typeof value === 'string' && STATUS_FILTER_VALUES.has(value as RepositoryStatusFilter)) {
    return value as RepositoryStatusFilter;
  }
  return 'all';
}

function normalizeReviewCoverageFilter(value: unknown): RepositoryReviewCoverageFilter {
  if (
    typeof value === 'string' &&
    REVIEW_COVERAGE_FILTER_VALUES.has(value as RepositoryReviewCoverageFilter)
  ) {
    return value as RepositoryReviewCoverageFilter;
  }
  return 'all';
}

function normalizeMetric(value: unknown): RepositoryMetric {
  if (typeof value === 'string' && METRIC_VALUES.has(value as RepositoryMetric)) {
    return value as RepositoryMetric;
  }
  return 'textUnits';
}

function normalizeSelectedRepositoryId(value: unknown): number | null {
  const next = typeof value === 'number' ? value : Number(value);
  if (Number.isInteger(next) && next > 0) {
    return next;
  }
  return null;
}

function normalizeState(value: unknown): RepositoriesSessionState | null {
  if (!value || typeof value !== 'object') {
    return null;
  }

  const candidate = value as Partial<RepositoriesSessionState>;
  return {
    selectedRepositoryId: normalizeSelectedRepositoryId(candidate.selectedRepositoryId),
    selectedRepositoryIds: normalizeRepositoryIds(candidate.selectedRepositoryIds),
    repositorySelectionTouched: Boolean(candidate.repositorySelectionTouched),
    selectedLocaleTags: normalizeLocaleTags(candidate.selectedLocaleTags),
    localeSelectionTouched: Boolean(candidate.localeSelectionTouched),
    statusFilter: normalizeStatusFilter(candidate.statusFilter),
    reviewCoverageFilter: normalizeReviewCoverageFilter(candidate.reviewCoverageFilter),
    metric: normalizeMetric(candidate.metric),
  };
}

function normalizeStateForStorage(state: RepositoriesSessionState): RepositoriesSessionState {
  return {
    selectedRepositoryId: normalizeSelectedRepositoryId(state.selectedRepositoryId),
    selectedRepositoryIds: normalizeRepositoryIds(state.selectedRepositoryIds),
    repositorySelectionTouched: Boolean(state.repositorySelectionTouched),
    selectedLocaleTags: normalizeLocaleTags(state.selectedLocaleTags),
    localeSelectionTouched: Boolean(state.localeSelectionTouched),
    statusFilter: normalizeStatusFilter(state.statusFilter),
    reviewCoverageFilter: normalizeReviewCoverageFilter(state.reviewCoverageFilter),
    metric: normalizeMetric(state.metric),
  };
}

export function serializeRepositoriesSessionState(state: RepositoriesSessionState): string {
  return JSON.stringify(normalizeStateForStorage(state));
}

export function loadRepositoriesSessionState(key: string): RepositoriesSessionState | null {
  const payload = loadSessionTokenPayload({ storagePrefix: STORAGE_PREFIX, key });
  return normalizeState(payload);
}

export function saveRepositoriesSessionState(
  state: RepositoriesSessionState,
  existingKey?: string | null,
): string {
  return saveSessionTokenPayload({
    storagePrefix: STORAGE_PREFIX,
    payload: normalizeStateForStorage(state),
    existingKey,
    maxEntries: STORAGE_MAX_ENTRIES,
  });
}
