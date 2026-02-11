import {
  type ApiReviewProjectStatus,
  type ApiReviewProjectType,
  REVIEW_PROJECT_STATUSES,
  REVIEW_PROJECT_TYPES,
} from '../../api/review-projects';
import { loadSessionTokenPayload, saveSessionTokenPayload } from '../../utils/sessionTokenStore';

const STORAGE_PREFIX = 'review-projects.searchState.v1:';
const STORAGE_MAX_ENTRIES = 48;
export const REVIEW_PROJECTS_SESSION_QUERY_KEY = 'rps';

const SEARCH_FIELDS = ['name', 'id', 'requestId', 'createdBy'] as const;
const SEARCH_TYPES = ['contains', 'exact', 'ilike'] as const;
const CREATOR_FILTERS = ['all', 'mine'] as const;
const DISPLAY_MODES = ['list', 'requests'] as const;

type SearchField = (typeof SEARCH_FIELDS)[number];
type SearchType = (typeof SEARCH_TYPES)[number];
type CreatorFilter = (typeof CREATOR_FILTERS)[number];
type DisplayMode = (typeof DISPLAY_MODES)[number];

export type ReviewProjectsSessionState = {
  selectedLocaleTags: string[];
  hasTouchedLocales: boolean;
  typeFilter: ApiReviewProjectType | 'all';
  statusFilter: ApiReviewProjectStatus | 'all';
  limit: number;
  searchQuery: string;
  searchField: SearchField;
  searchType: SearchType;
  creatorFilter: CreatorFilter;
  createdAfter: string | null;
  createdBefore: string | null;
  dueAfter: string | null;
  dueBefore: string | null;
  displayMode: DisplayMode;
};

const DEFAULT_STATE: ReviewProjectsSessionState = {
  selectedLocaleTags: [],
  hasTouchedLocales: false,
  typeFilter: 'all',
  statusFilter: 'OPEN',
  limit: 1000,
  searchQuery: '',
  searchField: 'name',
  searchType: 'contains',
  creatorFilter: 'all',
  createdAfter: null,
  createdBefore: null,
  dueAfter: null,
  dueBefore: null,
  displayMode: 'list',
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function sanitizeStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const deduped = new Set(
    value
      .filter((item): item is string => typeof item === 'string')
      .map((item) => item.trim())
      .filter((item) => item.length > 0),
  );
  return Array.from(deduped);
}

function sanitizeNullableString(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

function sanitizeNumber(value: unknown, fallback: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) {
    return fallback;
  }
  return Math.floor(value);
}

function sanitizeEnum<T extends string>(value: unknown, options: readonly T[], fallback: T): T {
  if (typeof value !== 'string') {
    return fallback;
  }
  return options.includes(value as T) ? (value as T) : fallback;
}

export function normalizeReviewProjectsSessionState(
  input: unknown,
): ReviewProjectsSessionState | null {
  if (!isRecord(input)) {
    return null;
  }

  const statusFilter = sanitizeEnum(
    input.statusFilter,
    [...REVIEW_PROJECT_STATUSES, 'all'] as const,
    DEFAULT_STATE.statusFilter,
  );
  const typeFilter = sanitizeEnum(
    input.typeFilter,
    [...REVIEW_PROJECT_TYPES, 'all'] as const,
    DEFAULT_STATE.typeFilter,
  );

  return {
    selectedLocaleTags: sanitizeStringArray(input.selectedLocaleTags),
    hasTouchedLocales:
      typeof input.hasTouchedLocales === 'boolean'
        ? input.hasTouchedLocales
        : DEFAULT_STATE.hasTouchedLocales,
    typeFilter,
    statusFilter,
    limit: sanitizeNumber(input.limit, DEFAULT_STATE.limit),
    searchQuery:
      typeof input.searchQuery === 'string' ? input.searchQuery : DEFAULT_STATE.searchQuery,
    searchField: sanitizeEnum(input.searchField, SEARCH_FIELDS, DEFAULT_STATE.searchField),
    searchType: sanitizeEnum(input.searchType, SEARCH_TYPES, DEFAULT_STATE.searchType),
    creatorFilter: sanitizeEnum(input.creatorFilter, CREATOR_FILTERS, DEFAULT_STATE.creatorFilter),
    createdAfter: sanitizeNullableString(input.createdAfter),
    createdBefore: sanitizeNullableString(input.createdBefore),
    dueAfter: sanitizeNullableString(input.dueAfter),
    dueBefore: sanitizeNullableString(input.dueBefore),
    displayMode: sanitizeEnum(input.displayMode, DISPLAY_MODES, DEFAULT_STATE.displayMode),
  };
}

export function loadReviewProjectsSessionState(key: string): ReviewProjectsSessionState | null {
  const payload = loadSessionTokenPayload({ storagePrefix: STORAGE_PREFIX, key });
  return normalizeReviewProjectsSessionState(payload);
}

export function saveReviewProjectsSessionState(
  state: ReviewProjectsSessionState,
  existingKey?: string | null,
): string {
  return saveSessionTokenPayload({
    storagePrefix: STORAGE_PREFIX,
    payload: state,
    existingKey,
    maxEntries: STORAGE_MAX_ENTRIES,
  });
}

export function serializeReviewProjectsSessionState(state: ReviewProjectsSessionState): string {
  return JSON.stringify(state);
}
