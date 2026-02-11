import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

import type {
  ApiReviewProjectRequestGroupSummary,
  ApiReviewProjectStatus,
  ApiReviewProjectSummary,
  ApiReviewProjectType,
  ReviewProjectsSearchRequest,
} from '../../api/review-projects';
import {
  adminBatchDeleteReviewProjects,
  adminBatchUpdateReviewProjectStatus,
  REVIEW_PROJECT_STATUS_LABELS,
  REVIEW_PROJECT_STATUSES,
  REVIEW_PROJECT_TYPE_LABELS,
  REVIEW_PROJECT_TYPES,
} from '../../api/review-projects';
import { ConfirmModal } from '../../components/ConfirmModal';
import { useUser } from '../../components/RequireUser';
import { useRepositories } from '../../hooks/useRepositories';
import {
  REVIEW_PROJECT_REQUESTS_QUERY_KEY,
  REVIEW_PROJECTS_QUERY_KEY,
  useReviewProjectRequests,
  useReviewProjects,
} from '../../hooks/useReviewProjects';
import { useLocaleOptionsWithDisplayNames } from '../../utils/localeSelection';
import { filterMyLocales } from '../../utils/localeSelection';
import { loadPreferredLocales } from '../workbench/workbench-preferences';
import {
  type ReviewProjectRequestGroupRow,
  type ReviewProjectRow,
  type ReviewProjectsAdminControls,
  ReviewProjectsPageView,
} from './ReviewProjectsPageView';

type FilterOption<T extends string | number> = { value: T; label: string };

type SelectAllLocalesParams = {
  localeOptions: { tag: string }[];
  defaultLocaleTags?: string[];
  projects: ApiReviewProjectSummary[] | undefined;
  selectedLocaleTags: string[];
  setSelectedLocaleTags: (tags: string[]) => void;
  userHasTouchedLocales: boolean;
};

type ReviewProjectsNavState = {
  requestId?: number | null;
};

function isReviewProjectsNavState(value: unknown): value is ReviewProjectsNavState {
  return (
    typeof value === 'object' &&
    value !== null &&
    'requestId' in value &&
    typeof (value as { requestId?: unknown }).requestId === 'number'
  );
}

function useSelectAllLocales({
  localeOptions,
  defaultLocaleTags,
  selectedLocaleTags,
  setSelectedLocaleTags,
  userHasTouchedLocales,
}: SelectAllLocalesParams) {
  useEffect(() => {
    if (userHasTouchedLocales) {
      return;
    }

    if (selectedLocaleTags.length > 0) {
      return;
    }

    const optionTags = localeOptions.map((option) => option.tag).filter(Boolean);
    if (optionTags.length === 0) {
      return;
    }

    if (defaultLocaleTags && defaultLocaleTags.length > 0) {
      const optionTagSet = new Set(optionTags.map((tag) => tag.toLowerCase()));
      const defaults = Array.from(
        new Set(defaultLocaleTags.filter((tag) => optionTagSet.has(tag.toLowerCase()))),
      );
      if (defaults.length > 0) {
        setSelectedLocaleTags(defaults);
        return;
      }
    }

    const next = Array.from(new Set(optionTags));
    const currentSet = new Set(selectedLocaleTags);
    const hasDifference =
      currentSet.size !== next.length || next.some((tag) => !currentSet.has(tag));

    if (hasDifference) {
      setSelectedLocaleTags(next);
    }
  }, [
    defaultLocaleTags,
    localeOptions,
    selectedLocaleTags,
    setSelectedLocaleTags,
    userHasTouchedLocales,
  ]);
}

const typeOptions: FilterOption<ApiReviewProjectType | 'all'>[] = [
  { value: 'all', label: 'All types' },
  ...REVIEW_PROJECT_TYPES.filter((type) => type !== 'UNKNOWN').map((type) => ({
    value: type,
    label: REVIEW_PROJECT_TYPE_LABELS[type],
  })),
];

const statusOptions: FilterOption<ApiReviewProjectStatus | 'all'>[] = [
  { value: 'all', label: 'All statuses' },
  ...REVIEW_PROJECT_STATUSES.map((status) => ({
    value: status,
    label: REVIEW_PROJECT_STATUS_LABELS[status],
  })),
];

const limitOptions: FilterOption<number>[] = [
  { value: 10, label: '10' },
  { value: 100, label: '100' },
  { value: 1000, label: '1k' },
  { value: 10000, label: '10k' },
];

const toFiniteNumberOrNull = (value: unknown) => {
  if (value == null) {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
};

const toReviewProjectRow = (project: ApiReviewProjectSummary): ReviewProjectRow => {
  const acceptedCountRaw = Number(project.acceptedCount ?? 0);
  const textUnitCountRaw = toFiniteNumberOrNull(project.textUnitCount);
  const wordCountRaw = toFiniteNumberOrNull(project.wordCount);

  return {
    id: project.id,
    name: project.reviewProjectRequest?.name ?? `Review project #${project.id}`,
    requestId: project.reviewProjectRequest?.id ?? null,
    requestCreatedByUsername: project.reviewProjectRequest?.createdByUsername ?? null,
    type: project.type,
    status: project.status,
    localeTag: project.locale?.bcp47Tag ?? null,
    acceptedCount: Number.isFinite(acceptedCountRaw) ? acceptedCountRaw : 0,
    textUnitCount: textUnitCountRaw,
    wordCount: wordCountRaw,
    dueDate: project.dueDate ?? null,
    closeReason: project.closeReason ?? null,
  };
};

const toReviewProjectRequestGroupRow = (
  requestGroup: ApiReviewProjectRequestGroupSummary,
): ReviewProjectRequestGroupRow => {
  const projects = (requestGroup.reviewProjects ?? []).map(toReviewProjectRow);
  const fallbackRequestId = requestGroup.requestId ?? -1;
  const key = `request:${fallbackRequestId}`;
  const textUnitCount =
    toFiniteNumberOrNull(requestGroup.textUnitCount) ??
    projects.reduce((sum, project) => sum + (project.textUnitCount ?? 0), 0);
  const wordCount =
    toFiniteNumberOrNull(requestGroup.wordCount) ??
    projects.reduce((sum, project) => sum + (project.wordCount ?? 0), 0);
  const acceptedCountRaw =
    toFiniteNumberOrNull(requestGroup.acceptedCount) ??
    projects.reduce((sum, project) => sum + project.acceptedCount, 0);
  const localeTags = Array.from(
    new Set(projects.map((project) => project.localeTag).filter((tag): tag is string => Boolean(tag))),
  ).sort((a, b) => a.localeCompare(b));

  return {
    key,
    requestId: requestGroup.requestId ?? null,
    name: requestGroup.requestName ?? `Request #${fallbackRequestId}`,
    createdByUsername: requestGroup.requestCreatedByUsername ?? null,
    localeTags,
    acceptedCount: Math.max(0, acceptedCountRaw ?? 0),
    textUnitCount: Math.max(0, textUnitCount ?? 0),
    wordCount: Math.max(0, wordCount ?? 0),
    dueDate: requestGroup.dueDate ?? null,
    projects,
  };
};

export function ReviewProjectsPage() {
  const user = useUser();
  const { data: repositoryData } = useRepositories();
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const isAdmin = user.role === 'ROLE_ADMIN';

  const [selectedLocaleTags, setSelectedLocaleTags] = useState<string[]>([]);
  const [hasTouchedLocales, setHasTouchedLocales] = useState(false);
  const [typeFilter, setTypeFilter] = useState<ApiReviewProjectType | 'all'>('all');
  const [statusFilter, setStatusFilter] = useState<ApiReviewProjectStatus | 'all'>('OPEN');
  const [limit, setLimit] = useState<number>(1000);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchField, setSearchField] = useState<'name' | 'id' | 'requestId' | 'createdBy'>(
    'name',
  );
  const [searchType, setSearchType] = useState<'contains' | 'exact' | 'ilike'>('contains');
  const [creatorFilter, setCreatorFilter] = useState<'all' | 'mine'>('all');
  const [createdAfter, setCreatedAfter] = useState<string | null>(null);
  const [createdBefore, setCreatedBefore] = useState<string | null>(null);
  const [dueAfter, setDueAfter] = useState<string | null>(null);
  const [dueBefore, setDueBefore] = useState<string | null>(null);
  const [selectedProjectIds, setSelectedProjectIds] = useState<number[]>([]);
  const [adminErrorMessage, setAdminErrorMessage] = useState<string | null>(null);
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false);
  const [displayMode, setDisplayMode] = useState<'queue' | 'requests'>(() =>
    isAdmin ? 'requests' : 'queue',
  );

  const repositories = useMemo(() => repositoryData ?? [], [repositoryData]);
  const localeOptions = useLocaleOptionsWithDisplayNames(repositories);
  const userLocales = useMemo(() => user.userLocales ?? [], [user.userLocales]);
  const isLimitedTranslator = useMemo(
    () => !user.canTranslateAllLocales && userLocales.length > 0,
    [user.canTranslateAllLocales, userLocales.length],
  );
  const userLocaleTagSet = useMemo(
    () => new Set(userLocales.map((tag) => tag.toLowerCase())),
    [userLocales],
  );
  const localeOptionsFiltered = useMemo(() => {
    if (!isLimitedTranslator) {
      return localeOptions;
    }
    return localeOptions.filter((option) => userLocaleTagSet.has(option.tag.toLowerCase()));
  }, [isLimitedTranslator, localeOptions, userLocaleTagSet]);
  const availableLocaleTags = useMemo(() => {
    const tags = localeOptionsFiltered.map((option) => option.tag).filter(Boolean);
    return Array.from(new Set(tags));
  }, [localeOptionsFiltered]);

  const searchParams = useMemo<ReviewProjectsSearchRequest>(() => {
    const searchFieldValue: ReviewProjectsSearchRequest['searchField'] =
      searchField === 'requestId'
        ? 'REQUEST_ID'
        : searchField === 'id'
          ? 'ID'
          : searchField === 'createdBy'
            ? 'CREATED_BY'
            : 'NAME';
    const searchMatchTypeValue: ReviewProjectsSearchRequest['searchMatchType'] =
      searchType === 'exact' ? 'EXACT' : searchType === 'ilike' ? 'ILIKE' : 'CONTAINS';
    const selectedLocaleSet = new Set(selectedLocaleTags);
    const localeTags =
      selectedLocaleTags.length === 0
        ? undefined
        : isLimitedTranslator
          ? selectedLocaleTags
          : availableLocaleTags.length > 0 &&
              availableLocaleTags.every((tag) => selectedLocaleSet.has(tag))
            ? undefined
            : selectedLocaleTags;

    return {
      localeTags,
      statuses: statusFilter === 'all' ? undefined : [statusFilter],
      types: typeFilter === 'all' ? undefined : [typeFilter],
      createdAfter,
      createdBefore,
      dueAfter,
      dueBefore,
      limit,
      searchQuery: searchQuery.trim() || undefined,
      searchField: searchFieldValue,
      searchMatchType: searchMatchTypeValue,
    };
  }, [
    availableLocaleTags,
    createdAfter,
    createdBefore,
    dueAfter,
    dueBefore,
    limit,
    searchField,
    searchQuery,
    searchType,
    selectedLocaleTags,
    statusFilter,
    typeFilter,
    isLimitedTranslator,
  ]);

  const requestSearchParams = useMemo<ReviewProjectsSearchRequest>(
    () => ({
      ...searchParams,
      statuses: undefined,
    }),
    [searchParams],
  );

  useEffect(() => {
    const state = isReviewProjectsNavState(location.state) ? location.state : null;
    if (!state) {
      return;
    }
    if (state.requestId != null) {
      setSearchField('requestId');
      setSearchType('exact');
      setSearchQuery(String(state.requestId));
    }
    void navigate(location.pathname, { replace: true });
  }, [location.pathname, location.state, navigate, setSearchField, setSearchQuery, setSearchType]);

  useEffect(() => {
    if (!isAdmin && displayMode !== 'queue') {
      setDisplayMode('queue');
    }
  }, [displayMode, isAdmin]);

  useEffect(() => {
    if (creatorFilter !== 'mine') {
      return;
    }

    if (searchField !== 'createdBy') {
      setSearchField('createdBy');
      return;
    }

    const username = (user.username ?? '').trim();
    if (!username) {
      return;
    }

    if (searchType !== 'exact') {
      setSearchType('exact');
    }

    if (searchQuery !== username) {
      setSearchQuery(username);
    }
  }, [creatorFilter, searchField, searchQuery, searchType, user.username]);

  const isRequestMode = isAdmin && displayMode === 'requests';
  const {
    data: queueProjectsData,
    isLoading: isLoadingQueue,
    isError: isErrorQueue,
    error: queueError,
    refetch: refetchQueue,
  } = useReviewProjects(searchParams, { enabled: !isRequestMode });
  const {
    data: requestGroupsData,
    isLoading: isLoadingRequestGroups,
    isError: isErrorRequestGroups,
    error: requestGroupsError,
    refetch: refetchRequestGroups,
  } = useReviewProjectRequests(requestSearchParams, { enabled: isRequestMode });

  const projects = useMemo(() => queueProjectsData ?? [], [queueProjectsData]);
  const requestGroups = useMemo(() => requestGroupsData ?? [], [requestGroupsData]);
  const preferredLocales = useMemo(() => loadPreferredLocales(), []);
  const myLocaleSelections = useMemo(
    () =>
      filterMyLocales({
        availableLocaleTags: localeOptionsFiltered.map((option) => option.tag),
        userLocales,
        preferredLocales,
        isLimitedTranslator,
        isAdmin,
      }),
    [isAdmin, isLimitedTranslator, localeOptionsFiltered, preferredLocales, userLocales],
  );

  const isLoading = isRequestMode ? isLoadingRequestGroups : isLoadingQueue;
  const isError = isRequestMode ? isErrorRequestGroups : isErrorQueue;
  const activeError = isRequestMode ? requestGroupsError : queueError;
  const status: 'loading' | 'error' | 'ready' = isLoading ? 'loading' : isError ? 'error' : 'ready';

  const errorMessage = isError
    ? activeError instanceof Error
      ? activeError.message
      : 'Failed to load review projects.'
    : undefined;

  const filteredProjects = useMemo(() => {
    const source = projects ?? [];
    const {
      localeTags,
      statuses,
      types,
      createdAfter: ca,
      createdBefore: cb,
      dueAfter: da,
      dueBefore: db,
      limit: lmt,
      searchQuery: q,
      searchField: sf,
      searchMatchType: mt,
    } = searchParams;

    // If the user intentionally cleared all locales, treat it as "no results"
    // instead of falling back to "all locales".
    const userClearedLocales = hasTouchedLocales && (!localeTags || localeTags.length === 0);
    if (userClearedLocales) {
      return [];
    }

    const createdAfterDate = ca ? new Date(ca) : null;
    const createdBeforeDate = cb ? new Date(cb) : null;
    const dueAfterDate = da ? new Date(da) : null;
    const dueBeforeDate = db ? new Date(db) : null;

    const matchesSearch = (project: ApiReviewProjectSummary) => {
      if (!q) return true;
      const field: 'id' | 'name' | 'requestId' | 'createdBy' =
        sf === 'ID'
          ? 'id'
          : sf === 'REQUEST_ID'
            ? 'requestId'
            : sf === 'CREATED_BY'
              ? 'createdBy'
              : 'name';
      const value =
        field === 'id'
          ? String(project.id)
          : field === 'requestId'
            ? String(project.reviewProjectRequest?.id ?? '')
            : field === 'createdBy'
              ? (project.createdByUsername ?? '')
            : (project.reviewProjectRequest?.name ?? `Review project #${project.id}`);
      const valueLower = value.toLowerCase();
      const queryLower = q.toLowerCase();

      if (mt === 'EXACT') {
        // Match backend behavior: case-insensitive exact comparison on name;
        // IDs are numeric but comparing as string keeps it stable.
        return valueLower === queryLower;
      }

      // Backend treats CONTAINS and ILIKE identically (case-insensitive LIKE)
      return valueLower.includes(queryLower);
    };

    const matchesDateRange = (
      value: string | null | undefined,
      after: Date | null,
      before: Date | null,
    ) => {
      if (!value) return true;
      const parsed = new Date(value);
      if (Number.isNaN(parsed.getTime())) return true;
      if (after && parsed < after) return false;
      if (before && parsed > before) return false;
      return true;
    };

    const matchesLocales = (project: ApiReviewProjectSummary) => {
      if (!localeTags || localeTags.length === 0) {
        return true;
      }
      const localeId = project.locale?.id ?? null;
      const localeTag = project.locale?.bcp47Tag ?? null;
      if (localeId == null && !localeTag) {
        return true;
      }
      return localeTags.some((tag) => {
        const lowered = tag.toLowerCase();
        if (localeTag && localeTag.toLowerCase() === lowered) {
          return true;
        }
        return localeId != null && String(localeId) === tag;
      });
    };

    const matchesStatus = (project: ApiReviewProjectSummary) => {
      if (!statuses || statuses.length === 0) return true;
      return statuses.includes(project.status);
    };

    const matchesType = (project: ApiReviewProjectSummary) => {
      if (!types || types.length === 0) return true;
      return types.includes(project.type);
    };

    const matchesCreator = (project: ApiReviewProjectSummary) => {
      if (creatorFilter !== 'mine' || sf !== 'CREATED_BY') {
        return true;
      }
      return (
        (project.createdByUsername ?? '').toLowerCase() === (user.username ?? '').toLowerCase()
      );
    };

    const filtered = source.filter(
      (project) =>
        matchesLocales(project) &&
        matchesStatus(project) &&
        matchesType(project) &&
        matchesCreator(project) &&
        matchesDateRange(project.createdDate ?? null, createdAfterDate, createdBeforeDate) &&
        matchesDateRange(project.dueDate ?? null, dueAfterDate, dueBeforeDate) &&
        matchesSearch(project),
    );

    const limitValue = lmt && lmt > 0 ? lmt : undefined;
    return limitValue ? filtered.slice(0, limitValue) : filtered;
  }, [creatorFilter, hasTouchedLocales, projects, searchParams, user.username]);

  const queueRows = useMemo<ReviewProjectRow[]>(
    () => filteredProjects.map(toReviewProjectRow),
    [filteredProjects],
  );
  const requestGroupRows = useMemo<ReviewProjectRequestGroupRow[]>(
    () => requestGroups.map(toReviewProjectRequestGroupRow),
    [requestGroups],
  );
  const requestModeRows = useMemo(
    () => requestGroupRows.flatMap((group) => group.projects),
    [requestGroupRows],
  );
  const rows = isRequestMode ? requestModeRows : queueRows;

  const visibleProjectIds = useMemo(
    () => (isRequestMode ? requestModeRows.map((row) => row.id) : queueRows.map((row) => row.id)),
    [isRequestMode, queueRows, requestModeRows],
  );
  const visibleProjectIdSet = useMemo(() => new Set(visibleProjectIds), [visibleProjectIds]);

  useEffect(() => {
    if (!isAdmin) {
      return;
    }
    setSelectedProjectIds((prev) => prev.filter((id) => visibleProjectIdSet.has(id)));
  }, [isAdmin, visibleProjectIdSet]);

  useEffect(() => {
    if (!isAdmin || selectedProjectIds.length === 0) {
      setDeleteConfirmOpen(false);
    }
  }, [isAdmin, selectedProjectIds.length]);

  const batchStatusMutation = useMutation({
    mutationFn: async ({
      projectIds,
      status,
    }: {
      projectIds: number[];
      status: ApiReviewProjectStatus;
    }) => adminBatchUpdateReviewProjectStatus(projectIds, status),
    onSuccess: () => {
      setAdminErrorMessage(null);
      setSelectedProjectIds([]);
      void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECTS_QUERY_KEY] });
      void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECT_REQUESTS_QUERY_KEY] });
      void refetchQueue();
      void refetchRequestGroups();
    },
    onError: (error) => {
      setAdminErrorMessage(error instanceof Error ? error.message : 'Batch update failed');
    },
  });

  const batchDeleteMutation = useMutation({
    mutationFn: async ({ projectIds }: { projectIds: number[] }) =>
      adminBatchDeleteReviewProjects(projectIds),
    onSuccess: () => {
      setAdminErrorMessage(null);
      setSelectedProjectIds([]);
      void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECTS_QUERY_KEY] });
      void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECT_REQUESTS_QUERY_KEY] });
      void refetchQueue();
      void refetchRequestGroups();
    },
    onError: (error) => {
      setAdminErrorMessage(error instanceof Error ? error.message : 'Batch delete failed');
    },
  });

  const isBatchSaving = batchStatusMutation.isPending || batchDeleteMutation.isPending;

  const handleRetry = useCallback(() => {
    if (isRequestMode) {
      void refetchRequestGroups();
    } else {
      void refetchQueue();
    }
  }, [isRequestMode, refetchQueue, refetchRequestGroups]);

  const handleRequestIdClick = useCallback((requestId: number) => {
    setDisplayMode('queue');
    setSearchField('requestId');
    setSearchType('exact');
    setSearchQuery(String(requestId));
  }, []);

  const handleSearchChange = useCallback(
    (value: string) => {
      if (creatorFilter === 'mine') {
        setCreatorFilter('all');
      }
      setSearchQuery(value);
    },
    [creatorFilter],
  );

  const handleSearchFieldChange = useCallback(
    (value: 'name' | 'id' | 'requestId' | 'createdBy') => {
      setSearchField(value);
      if (value !== 'createdBy' && creatorFilter === 'mine') {
        setCreatorFilter('all');
      }
    },
    [creatorFilter],
  );

  const toggleProjectSelection = useCallback((projectId: number) => {
    setSelectedProjectIds((prev) => {
      const exists = prev.includes(projectId);
      if (exists) {
        return prev.filter((id) => id !== projectId);
      }
      return [...prev, projectId];
    });
  }, []);

  const clearProjectSelection = useCallback(() => {
    setSelectedProjectIds([]);
  }, []);

  const setProjectSelection = useCallback((projectIds: number[], selected: boolean) => {
    setSelectedProjectIds((prev) => {
      const next = new Set(prev);
      projectIds.forEach((projectId) => {
        if (selected) {
          next.add(projectId);
        } else {
          next.delete(projectId);
        }
      });
      return Array.from(next);
    });
  }, []);

  const selectAllVisibleProjects = useCallback(() => {
    setSelectedProjectIds(visibleProjectIds);
  }, [visibleProjectIds]);

  const requestBatchStatus = useCallback(
    (nextStatus: ApiReviewProjectStatus) => {
      if (!isAdmin || selectedProjectIds.length === 0 || isBatchSaving) {
        return;
      }
      batchStatusMutation.mutate({ projectIds: [...selectedProjectIds], status: nextStatus });
    },
    [batchStatusMutation, isAdmin, isBatchSaving, selectedProjectIds],
  );

  const requestBatchDelete = useCallback(() => {
    if (!isAdmin || selectedProjectIds.length === 0 || isBatchSaving) {
      return;
    }
    setDeleteConfirmOpen(true);
  }, [isAdmin, isBatchSaving, selectedProjectIds.length]);

  const handleConfirmBatchDelete = useCallback(() => {
    if (!isAdmin || selectedProjectIds.length === 0 || isBatchSaving) {
      setDeleteConfirmOpen(false);
      return;
    }
    batchDeleteMutation.mutate({ projectIds: [...selectedProjectIds] });
    setDeleteConfirmOpen(false);
  }, [batchDeleteMutation, isAdmin, isBatchSaving, selectedProjectIds]);

  const handleCancelBatchDelete = useCallback(() => {
    setDeleteConfirmOpen(false);
  }, []);

  const adminControls: ReviewProjectsAdminControls | undefined = isAdmin
    ? {
        enabled: true,
        selectedProjectIds,
        onToggleProjectSelection: toggleProjectSelection,
        onSetProjectSelection: setProjectSelection,
        onSelectAllVisible: selectAllVisibleProjects,
        onClearSelection: clearProjectSelection,
        onBatchStatus: requestBatchStatus,
        onBatchDelete: requestBatchDelete,
        isSaving: isBatchSaving,
        errorMessage: adminErrorMessage,
      }
    : undefined;

  useSelectAllLocales({
    localeOptions: localeOptionsFiltered,
    defaultLocaleTags: isLimitedTranslator ? userLocales : undefined,
    projects,
    selectedLocaleTags,
    setSelectedLocaleTags,
    userHasTouchedLocales: hasTouchedLocales,
  });

  return (
    <>
      <ReviewProjectsPageView
        status={status}
        errorMessage={errorMessage}
        errorOnRetry={handleRetry}
        projects={rows}
        requestGroups={isRequestMode ? requestGroupRows : undefined}
        filters={{
          localeOptions: localeOptionsFiltered,
          selectedLocaleTags,
          onLocaleChange: (next) => {
            setHasTouchedLocales(true);
            setSelectedLocaleTags(next);
          },
          myLocaleTags: myLocaleSelections,
          typeOptions,
          typeValue: typeFilter,
          onTypeChange: setTypeFilter,
          statusOptions,
          statusValue: statusFilter,
          onStatusChange: setStatusFilter,
          limitOptions,
          limitValue: limit,
          onLimitChange: setLimit,
          createdAfter,
          createdBefore,
          onChangeCreatedAfter: setCreatedAfter,
          onChangeCreatedBefore: setCreatedBefore,
          dueAfter,
          dueBefore,
          onChangeDueAfter: setDueAfter,
          onChangeDueBefore: setDueBefore,
          searchQuery,
          onSearchChange: handleSearchChange,
          searchField,
          onSearchFieldChange: handleSearchFieldChange,
          searchType,
          onSearchTypeChange: setSearchType,
          creatorFilter,
          onCreatorFilterChange: setCreatorFilter,
        }}
        onRequestIdClick={handleRequestIdClick}
        canCreate={isAdmin}
        adminControls={adminControls}
        displayMode={displayMode}
        onDisplayModeChange={setDisplayMode}
      />
      {isAdmin ? (
        <ConfirmModal
          open={deleteConfirmOpen}
          title="Delete review projects?"
          body={`This will permanently delete ${selectedProjectIds.length} project(s). This cannot be undone.`}
          confirmLabel="Delete"
          cancelLabel="Cancel"
          onConfirm={handleConfirmBatchDelete}
          onCancel={handleCancelBatchDelete}
        />
      ) : null}
    </>
  );
}
