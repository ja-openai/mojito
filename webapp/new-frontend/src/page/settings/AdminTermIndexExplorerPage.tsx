import './settings-page.css';
import './term-index-explorer-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  type CSSProperties,
  type KeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { Link, Navigate, useSearchParams } from 'react-router-dom';

import {
  type ApiGenerateTermIndexCandidatesResponse,
  type ApiTermIndexEntry,
  type ApiTermIndexOccurrence,
  type ApiTermIndexReviewStatus,
  type ApiTermIndexReviewStatusFilter,
  fetchTermIndexEntries,
  fetchTermIndexOccurrences,
  fetchTermIndexStatus,
  generateTermIndexCandidatesFromEntries,
  startTermIndexRefresh,
  updateTermIndexEntryReview,
  updateTermIndexEntryReviews,
  waitForTermIndexRefreshTask,
} from '../../api/term-index';
import { Modal } from '../../components/Modal';
import type { MultiSelectCustomAction } from '../../components/MultiSelectChip';
import { NumericPresetDropdown } from '../../components/NumericPresetDropdown';
import { PillDropdown } from '../../components/PillDropdown';
import { RepositoryMultiSelect } from '../../components/RepositoryMultiSelect';
import { useUser } from '../../components/RequireUser';
import { SearchControl } from '../../components/SearchControl';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import { useMeasuredRowRefs } from '../../components/virtual/useMeasuredRowRefs';
import { useVirtualRows } from '../../components/virtual/useVirtualRows';
import { VirtualList } from '../../components/virtual/VirtualList';
import { useRepositories } from '../../hooks/useRepositories';
import { formatLocalDateTime } from '../../utils/dateTime';
import {
  type RepositorySelectionOption,
  useRepositorySelection,
  useRepositorySelectionOptions,
} from '../../utils/repositorySelection';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

const TERM_RESULT_LIMIT_DEFAULT = 1000;
const TERM_RESULT_LIMIT_OPTIONS = [
  { value: 10, label: '10' },
  { value: 100, label: '100' },
  { value: 1000, label: '1k' },
  { value: 10000, label: '10k' },
];
const TERM_RESULT_LIMIT_MAX = 10000;
const RUN_RESULT_LIMIT_DEFAULT = 20;
const RUN_RESULT_LIMIT_OPTIONS = [
  { value: 20, label: '20' },
  { value: 50, label: '50' },
  { value: 100, label: '100' },
  { value: 200, label: '200' },
];
const RUN_RESULT_LIMIT_MAX = 500;
const MIN_OCCURRENCES_DEFAULT = 1;
const MIN_OCCURRENCES_OPTIONS = [
  { value: 1, label: '1+' },
  { value: 2, label: '2+' },
  { value: 5, label: '5+' },
  { value: 10, label: '10+' },
  { value: 25, label: '25+' },
  { value: 50, label: '50+' },
];
const REVIEW_STATUS_FILTER_OPTIONS: Array<{
  value: ApiTermIndexReviewStatusFilter;
  label: string;
}> = [
  { value: 'NON_REJECTED', label: 'Non-rejected' },
  { value: 'TO_REVIEW', label: 'To review' },
  { value: 'ACCEPTED', label: 'Accepted' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'ALL', label: 'All statuses' },
];
const REVIEW_STATUS_OPTIONS: Array<{
  value: ApiTermIndexReviewStatus;
  label: string;
}> = [
  { value: 'TO_REVIEW', label: 'To review' },
  { value: 'ACCEPTED', label: 'Accepted' },
  { value: 'REJECTED', label: 'Rejected' },
];
const TERM_TYPE_OPTIONS = [
  { value: 'BRAND', label: 'Brand' },
  { value: 'PRODUCT', label: 'Product' },
  { value: 'UI_LABEL', label: 'UI label' },
  { value: 'LEGAL', label: 'Legal' },
  { value: 'TECHNICAL', label: 'Technical' },
  { value: 'GENERAL', label: 'General' },
];
const ENFORCEMENT_OPTIONS = [
  { value: 'HARD', label: 'Hard' },
  { value: 'SOFT', label: 'Soft' },
  { value: 'REVIEW_ONLY', label: 'Review only' },
];
const EXAMPLE_RESULT_LIMIT = 500;
const DEFAULT_TERM_PANE_WIDTH_PCT = 42;
const DEFAULT_BATCH_SIZE = 1000;

type CandidateDraft = {
  definition: string;
  rationale: string;
  termType: string;
  partOfSpeech: string;
  enforcement: string;
  doNotTranslate: boolean;
  confidence: string;
  reviewStatus: ApiTermIndexReviewStatus;
};

type CandidateGenerationRequest = {
  mode: 'single' | 'bulk';
  entryIds: number[];
  entries: ApiTermIndexEntry[];
  draft: CandidateDraft | null;
};

const parsePositiveIntegerParam = (value: string | null) => {
  if (value == null) {
    return null;
  }
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
};

const isInteractiveTarget = (target: EventTarget | null) =>
  target instanceof HTMLElement &&
  target.closest('button, input, select, textarea, a, [contenteditable="true"]') != null;

const isTextEntryTarget = (target: EventTarget | null) => {
  if (!(target instanceof HTMLElement)) {
    return false;
  }

  const element = target.closest('input, select, textarea, [contenteditable="true"]');
  if (!(element instanceof HTMLElement)) {
    return false;
  }

  if (element instanceof HTMLInputElement) {
    const type = element.type.toLowerCase();
    return !['button', 'checkbox', 'radio', 'reset', 'submit'].includes(type);
  }

  return true;
};

const getTermIndexFromTarget = (target: EventTarget | null) => {
  if (!(target instanceof HTMLElement)) {
    return -1;
  }

  const row = target.closest<HTMLElement>('[data-term-index]');
  if (row?.dataset.termIndex == null) {
    return -1;
  }

  const index = Number(row.dataset.termIndex);
  return Number.isInteger(index) ? index : -1;
};

type TermIndexRefreshForm = {
  repositoryIds: number[];
  fullRefresh: boolean;
  excludeGlossaryRepositories: boolean;
};

const PaneGripIcon = () => (
  <svg
    className="term-index-explorer__pane-grip"
    viewBox="0 0 6 22"
    aria-hidden="true"
    focusable="false"
  >
    <circle cx="3" cy="4" r="1" />
    <circle cx="3" cy="8.5" r="1" />
    <circle cx="3" cy="13" r="1" />
    <circle cx="3" cy="17.5" r="1" />
  </svg>
);

const getRepositoryIds = (repositoryOptions: RepositorySelectionOption[]) =>
  repositoryOptions.map((repository) => repository.id);

const getProductRepositoryIds = (repositoryOptions: RepositorySelectionOption[]) =>
  repositoryOptions
    .filter((repository) => !repository.isGlossary)
    .map((repository) => repository.id);

const getGlossaryRepositoryIds = (repositoryOptions: RepositorySelectionOption[]) =>
  repositoryOptions
    .filter((repository) => repository.isGlossary)
    .map((repository) => repository.id);

const getRawTermExplorerPath = (entryId: number, search?: string) => {
  const params = new URLSearchParams({ entryId: String(entryId) });
  const trimmedSearch = search?.trim();
  if (trimmedSearch) {
    params.set('search', trimmedSearch);
  }
  return `/settings/system/glossary-term-index/terms?${params.toString()}`;
};

const createCandidateDraft = (entry: ApiTermIndexEntry | null): CandidateDraft => ({
  definition: '',
  rationale: '',
  termType: suggestCandidateTermType(entry?.displayTerm ?? ''),
  partOfSpeech: '',
  enforcement: 'SOFT',
  doNotTranslate: shouldCandidatePreserveSource(entry?.displayTerm ?? ''),
  confidence: '',
  reviewStatus: 'TO_REVIEW',
});

const suggestCandidateTermType = (term: string) => {
  const normalized = term.trim();
  if (!normalized) {
    return 'GENERAL';
  }
  if (/^[A-Z0-9][A-Z0-9._-]*$/.test(normalized) && /[A-Z]/.test(normalized)) {
    return 'BRAND';
  }
  if (/[._/-]/.test(normalized)) {
    return 'TECHNICAL';
  }
  return 'GENERAL';
};

const shouldCandidatePreserveSource = (term: string) => {
  const normalized = term.trim();
  return /^[A-Z0-9][A-Z0-9._/-]*$/.test(normalized) && /[A-Z]/.test(normalized);
};

const nullableTrimmed = (value: string) => {
  const normalized = value.trim();
  return normalized ? normalized : null;
};

const parseOptionalConfidence = (value: string) => {
  const normalized = value.trim();
  if (!normalized) {
    return null;
  }
  const parsed = Number(normalized);
  return Number.isFinite(parsed) ? Math.min(100, Math.max(0, Math.round(parsed))) : null;
};

const cloneCandidateDraft = (draft: CandidateDraft): CandidateDraft => ({ ...draft });

const hasSameRepositoryIds = (left: number[], right: number[]) => {
  if (left.length !== right.length) {
    return false;
  }
  const rightSet = new Set(right);
  return left.every((value) => rightSet.has(value));
};

const getRepositoryTypeSelectionActions = ({
  selectedRepositoryIds,
  productRepositoryIds,
  glossaryRepositoryIds,
  onChangeRepositorySelection,
}: {
  selectedRepositoryIds: number[];
  productRepositoryIds: number[];
  glossaryRepositoryIds: number[];
  onChangeRepositorySelection: (repositoryIds: number[]) => void;
}): MultiSelectCustomAction[] => {
  const selectedProducts =
    productRepositoryIds.length > 0 &&
    hasSameRepositoryIds(selectedRepositoryIds, productRepositoryIds);
  const selectedGlossaries =
    glossaryRepositoryIds.length > 0 &&
    hasSameRepositoryIds(selectedRepositoryIds, glossaryRepositoryIds);

  const actions: Array<MultiSelectCustomAction | null> = [
    selectedProducts
      ? null
      : {
          label: 'Repositories',
          onClick: () => onChangeRepositorySelection(productRepositoryIds),
          disabled: productRepositoryIds.length === 0,
          ariaLabel: 'Show repositories excluding glossaries',
        },
    selectedGlossaries
      ? null
      : {
          label: 'Glossaries',
          onClick: () => onChangeRepositorySelection(glossaryRepositoryIds),
          disabled: glossaryRepositoryIds.length === 0,
          ariaLabel: 'Show glossary repositories only',
        },
  ];

  return actions.filter((action): action is MultiSelectCustomAction => action != null);
};

const getRepositorySelectionSummaryFormatter =
  (allRepositoryIds: number[], productRepositoryIds: number[], glossaryRepositoryIds: number[]) =>
  ({ selectedIds, defaultSummary }: { selectedIds: number[]; defaultSummary: string }) => {
    if (selectedIds.length === 0 || hasSameRepositoryIds(selectedIds, allRepositoryIds)) {
      return 'All repositories';
    }
    if (
      productRepositoryIds.length > 0 &&
      glossaryRepositoryIds.length > 0 &&
      hasSameRepositoryIds(selectedIds, productRepositoryIds)
    ) {
      return 'Repositories';
    }
    if (
      glossaryRepositoryIds.length > 0 &&
      hasSameRepositoryIds(selectedIds, glossaryRepositoryIds)
    ) {
      return 'Glossaries';
    }
    return defaultSummary;
  };

const useTermIndexRepositorySelection = (repositoryOptions: RepositorySelectionOption[]) => {
  const allRepositoryIds = useMemo(() => getRepositoryIds(repositoryOptions), [repositoryOptions]);
  const productRepositoryIds = useMemo(
    () => getProductRepositoryIds(repositoryOptions),
    [repositoryOptions],
  );
  const glossaryRepositoryIds = useMemo(
    () => getGlossaryRepositoryIds(repositoryOptions),
    [repositoryOptions],
  );

  const {
    selectedIds: selectedRepositoryIds,
    hasTouched,
    onChangeSelection: updateSelectedRepositoryIds,
    setSelection,
  } = useRepositorySelection({ options: repositoryOptions });

  useEffect(() => {
    if (!hasTouched && productRepositoryIds.length > 0) {
      setSelection(productRepositoryIds, { touched: false });
    }
  }, [hasTouched, productRepositoryIds, setSelection]);

  const effectiveRepositoryIds =
    selectedRepositoryIds.length > 0 ? selectedRepositoryIds : allRepositoryIds;

  const repositorySelectionActions = useMemo(
    () =>
      getRepositoryTypeSelectionActions({
        selectedRepositoryIds,
        productRepositoryIds,
        glossaryRepositoryIds,
        onChangeRepositorySelection: updateSelectedRepositoryIds,
      }),
    [
      glossaryRepositoryIds,
      productRepositoryIds,
      selectedRepositoryIds,
      updateSelectedRepositoryIds,
    ],
  );

  const formatRepositorySelectionSummary = useMemo(
    () =>
      getRepositorySelectionSummaryFormatter(
        allRepositoryIds,
        productRepositoryIds,
        glossaryRepositoryIds,
      ),
    [allRepositoryIds, glossaryRepositoryIds, productRepositoryIds],
  );

  return {
    allRepositoryIds,
    productRepositoryIds,
    glossaryRepositoryIds,
    selectedRepositoryIds,
    effectiveRepositoryIds,
    repositorySelectionActions,
    formatRepositorySelectionSummary,
    updateSelectedRepositoryIds,
  };
};

export function AdminTermIndexExplorerPage() {
  return <AdminTermIndexRunsPage />;
}

export function AdminTermIndexRunsPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();
  const { data: repositories } = useRepositories();
  const repositoryOptions = useRepositorySelectionOptions(repositories ?? []);
  const {
    allRepositoryIds,
    productRepositoryIds,
    selectedRepositoryIds,
    effectiveRepositoryIds,
    repositorySelectionActions,
    formatRepositorySelectionSummary,
    updateSelectedRepositoryIds,
  } = useTermIndexRepositorySelection(repositoryOptions);
  const [refreshModalOpen, setRefreshModalOpen] = useState(false);
  const [refreshRepositoryIds, setRefreshRepositoryIds] = useState<number[]>([]);
  const [refreshFullRefresh, setRefreshFullRefresh] = useState(false);
  const [activeRefreshTaskId, setActiveRefreshTaskId] = useState<number | null>(null);
  const [runResultLimit, setRunResultLimit] = useState(RUN_RESULT_LIMIT_DEFAULT);
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; message: string } | null>(null);

  const statusQuery = useQuery({
    queryKey: ['term-index-status', effectiveRepositoryIds, runResultLimit],
    queryFn: () =>
      fetchTermIndexStatus({
        repositoryIds: effectiveRepositoryIds,
        recentRunLimit: runResultLimit,
      }),
    enabled: isAdmin && repositoryOptions.length > 0,
    staleTime: 5_000,
    refetchInterval: activeRefreshTaskId == null ? false : 3_000,
  });
  const updateRunResultLimit = (next: number) => {
    setRunResultLimit(Math.min(Math.max(1, next), RUN_RESULT_LIMIT_MAX));
  };
  const recentRunCount = statusQuery.data?.recentRuns.length ?? 0;
  const recentRunCountLabel = statusQuery.isLoading
    ? 'Loading runs...'
    : formatCappedListLabel('runs', recentRunCount, runResultLimit);

  const refreshMutation = useMutation({
    mutationFn: async (request: TermIndexRefreshForm) => {
      const startedTask = await startTermIndexRefresh({
        repositoryIds: request.repositoryIds,
        fullRefresh: request.fullRefresh,
        batchSize: DEFAULT_BATCH_SIZE,
        excludeGlossaryRepositories: request.excludeGlossaryRepositories,
      });
      setActiveRefreshTaskId(startedTask.id);
      return waitForTermIndexRefreshTask(startedTask.id);
    },
    onSuccess: async (_task, request) => {
      setActiveRefreshTaskId(null);
      setNotice({
        kind: 'success',
        message: request.fullRefresh
          ? 'Full term index refresh completed.'
          : 'Term index refresh completed.',
      });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['term-index-status'] }),
        queryClient.invalidateQueries({ queryKey: ['term-index-entries'] }),
        queryClient.invalidateQueries({ queryKey: ['term-index-occurrences'] }),
      ]);
    },
    onError: (error: Error) => {
      setActiveRefreshTaskId(null);
      setNotice({ kind: 'error', message: error.message || 'Term index refresh failed.' });
    },
  });

  const openRefreshModal = () => {
    setNotice(null);
    setRefreshRepositoryIds(selectedRepositoryIds);
    setRefreshFullRefresh(false);
    setRefreshModalOpen(true);
  };

  const startRefresh = () => {
    const repositoryIds = refreshRepositoryIds.length > 0 ? refreshRepositoryIds : allRepositoryIds;
    if (repositoryIds.length === 0 || refreshMutation.isPending) {
      return;
    }

    setNotice(null);
    setRefreshModalOpen(false);
    refreshMutation.mutate({
      repositoryIds,
      fullRefresh: refreshFullRefresh,
      excludeGlossaryRepositories: hasSameRepositoryIds(repositoryIds, productRepositoryIds),
    });
  };

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
        context="Settings > Glossaries"
        title="Term index runs"
        centerContent={<TermIndexSubnav active="runs" />}
      />
      <div className="settings-page settings-page--wide term-index-explorer">
        <section
          className="settings-card term-index-explorer__filter-card"
          aria-label="Run filters"
        >
          <div className="term-index-explorer__run-toolbar">
            <div className="term-index-explorer__repository-control">
              <RepositoryMultiSelect
                label="Repositories"
                options={repositoryOptions}
                selectedIds={selectedRepositoryIds}
                onChange={updateSelectedRepositoryIds}
                className="settings-repository-select"
                buttonAriaLabel="Filter term index status by repository. Use menu actions to switch between repositories and glossaries."
                customActions={repositorySelectionActions}
                summaryFormatter={formatRepositorySelectionSummary}
              />
            </div>
            <button
              type="button"
              className="settings-button settings-button--primary"
              disabled={refreshMutation.isPending}
              onClick={openRefreshModal}
            >
              {refreshMutation.isPending ? 'Refresh running' : 'Refresh index'}
            </button>
          </div>
          {activeRefreshTaskId != null ? (
            <p className="settings-hint">Refresh task {activeRefreshTaskId} is running.</p>
          ) : null}
          {notice ? (
            <p className={`settings-hint${notice.kind === 'error' ? ' is-error' : ''}`}>
              {notice.message}
            </p>
          ) : null}
        </section>

        <section className="settings-card">
          <div className="settings-card__header">
            <h2>Index status</h2>
          </div>
          <IndexStatusTables
            statusQuery={statusQuery}
            runResultLimit={runResultLimit}
            runCountLabel={recentRunCountLabel}
            onChangeRunResultLimit={updateRunResultLimit}
          />
        </section>
      </div>
      <TermIndexRefreshModal
        open={refreshModalOpen}
        repositoryOptions={repositoryOptions}
        selectedRepositoryIds={refreshRepositoryIds}
        fullRefresh={refreshFullRefresh}
        isRefreshing={refreshMutation.isPending}
        onRepositoryChange={setRefreshRepositoryIds}
        onFullRefreshChange={setRefreshFullRefresh}
        onClose={() => setRefreshModalOpen(false)}
        onStart={startRefresh}
      />
    </div>
  );
}

export function AdminTermIndexTermsPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();
  const [searchParams] = useSearchParams();
  const requestedSearchParam = searchParams.get('search');
  const requestedEntryIdParam = searchParams.get('entryId');
  const initialSearchQuery = requestedSearchParam?.trim() ?? '';
  const initialSelectedEntryId = parsePositiveIntegerParam(requestedEntryIdParam);
  const { data: repositories } = useRepositories();
  const repositoryOptions = useRepositorySelectionOptions(repositories ?? []);
  const {
    selectedRepositoryIds,
    effectiveRepositoryIds,
    repositorySelectionActions,
    formatRepositorySelectionSummary,
    updateSelectedRepositoryIds,
  } = useTermIndexRepositorySelection(repositoryOptions);
  const [searchQuery, setSearchQuery] = useState(initialSearchQuery);
  const [minOccurrences, setMinOccurrences] = useState(MIN_OCCURRENCES_DEFAULT);
  const [extractionMethod, setExtractionMethod] = useState<string | null>(null);
  const [reviewStatusFilter, setReviewStatusFilter] =
    useState<ApiTermIndexReviewStatusFilter>('NON_REJECTED');
  const [termResultLimit, setTermResultLimit] = useState(TERM_RESULT_LIMIT_DEFAULT);
  const [selectedEntryId, setSelectedEntryId] = useState<number | null>(initialSelectedEntryId);
  const [selectedEntryIds, setSelectedEntryIds] = useState<number[]>([]);
  const [termPaneWidthPct, setTermPaneWidthPct] = useState(DEFAULT_TERM_PANE_WIDTH_PCT);
  const [isPaneResizing, setIsPaneResizing] = useState(false);
  const workspaceRef = useRef<HTMLDivElement>(null);

  const resetTermSelection = () => {
    setSelectedEntryId(null);
    setSelectedEntryIds([]);
  };
  const updateTermPaneWidthPct = useCallback((next: number) => {
    setTermPaneWidthPct(Math.min(75, Math.max(25, next)));
  }, []);
  const updateTermResultLimit = (next: number) => {
    setTermResultLimit(Math.min(Math.max(1, next), TERM_RESULT_LIMIT_MAX));
    resetTermSelection();
  };
  const updateMinOccurrences = (next: number) => {
    setMinOccurrences(Math.max(1, next));
    resetTermSelection();
  };

  useEffect(() => {
    setSearchQuery(requestedSearchParam?.trim() ?? '');
    setSelectedEntryId(parsePositiveIntegerParam(requestedEntryIdParam));
    setSelectedEntryIds([]);
  }, [requestedEntryIdParam, requestedSearchParam]);

  const statusQuery = useQuery({
    queryKey: ['term-index-status', effectiveRepositoryIds],
    queryFn: () => fetchTermIndexStatus({ repositoryIds: effectiveRepositoryIds }),
    enabled: isAdmin && repositoryOptions.length > 0,
    staleTime: 30_000,
  });

  const entriesQuery = useQuery({
    queryKey: [
      'term-index-entries',
      effectiveRepositoryIds,
      searchQuery,
      extractionMethod,
      reviewStatusFilter,
      minOccurrences,
      termResultLimit,
    ],
    queryFn: () =>
      fetchTermIndexEntries({
        repositoryIds: effectiveRepositoryIds,
        search: searchQuery,
        extractionMethod,
        reviewStatus: reviewStatusFilter,
        minOccurrences,
        limit: termResultLimit,
      }),
    enabled: isAdmin && repositoryOptions.length > 0,
    staleTime: 5_000,
  });

  const entryData = entriesQuery.data;
  const entries = useMemo(() => entryData?.entries ?? [], [entryData]);
  const selectedEntry = useMemo(
    () => entries.find((entry) => entry.id === selectedEntryId) ?? null,
    [entries, selectedEntryId],
  );
  const selectedEntries = useMemo(
    () => entries.filter((entry) => selectedEntryIds.includes(entry.id)),
    [entries, selectedEntryIds],
  );
  const allEntriesSelected =
    entries.length > 0 && entries.every((entry) => selectedEntryIds.includes(entry.id));

  useEffect(() => {
    if (entries.length === 0) {
      if (!entriesQuery.isFetching) {
        setSelectedEntryId(null);
      }
      return;
    }
    if (selectedEntryId == null || !entries.some((entry) => entry.id === selectedEntryId)) {
      setSelectedEntryId(entries[0].id);
    }
  }, [entries, entriesQuery.isFetching, selectedEntryId]);

  useEffect(() => {
    const visibleEntryIds = new Set(entries.map((entry) => entry.id));
    setSelectedEntryIds((current) => current.filter((entryId) => visibleEntryIds.has(entryId)));
  }, [entries]);

  const occurrencesQuery = useQuery({
    queryKey: ['term-index-occurrences', selectedEntryId, effectiveRepositoryIds, extractionMethod],
    queryFn: () =>
      fetchTermIndexOccurrences(selectedEntryId ?? 0, {
        repositoryIds: effectiveRepositoryIds,
        extractionMethod,
        limit: EXAMPLE_RESULT_LIMIT,
      }),
    enabled: isAdmin && selectedEntryId != null && repositoryOptions.length > 0,
    staleTime: 5_000,
  });

  const occurrenceData = occurrencesQuery.data;
  const occurrences = occurrenceData?.occurrences ?? [];

  const updateReviewMutation = useMutation({
    mutationFn: ({
      entryId,
      reviewStatus,
      reviewReason,
    }: {
      entryId: number;
      reviewStatus: ApiTermIndexReviewStatus;
      reviewReason?: string | null;
    }) =>
      updateTermIndexEntryReview(entryId, {
        reviewStatus,
        reviewReason: reviewReason ?? null,
      }),
    onSuccess: async (entry) => {
      setSelectedEntryId(entry.id);
      await queryClient.invalidateQueries({ queryKey: ['term-index-entries'] });
    },
  });

  const batchUpdateReviewMutation = useMutation({
    mutationFn: ({
      entryIds,
      reviewStatus,
      reviewReason,
    }: {
      entryIds: number[];
      reviewStatus: ApiTermIndexReviewStatus;
      reviewReason?: string | null;
    }) =>
      updateTermIndexEntryReviews(entryIds, {
        reviewStatus,
        reviewReason: reviewReason ?? null,
      }),
    onSuccess: async () => {
      setSelectedEntryIds([]);
      await queryClient.invalidateQueries({ queryKey: ['term-index-entries'] });
    },
  });

  const updateEntryReview = (entryId: number, reviewStatus: ApiTermIndexReviewStatus) => {
    if (updateReviewMutation.isPending) {
      return;
    }
    updateReviewMutation.mutate({
      entryId,
      reviewStatus,
      reviewReason: reviewStatus === 'REJECTED' ? 'OTHER' : null,
    });
  };

  const updateSelectedEntriesReview = (reviewStatus: ApiTermIndexReviewStatus | null) => {
    if (selectedEntryIds.length === 0 || batchUpdateReviewMutation.isPending) {
      return;
    }
    if (reviewStatus == null) {
      return;
    }
    batchUpdateReviewMutation.mutate({
      entryIds: selectedEntryIds,
      reviewStatus,
      reviewReason: reviewStatus === 'REJECTED' ? 'OTHER' : null,
    });
  };

  const extractionMethodOptions = useMemo(
    () =>
      (statusQuery.data?.extractionMethods ?? []).map((method) => ({
        value: method,
        label: formatMethod(method),
      })),
    [statusQuery.data?.extractionMethods],
  );
  const termCountLabel = entriesQuery.isLoading
    ? 'Loading terms...'
    : formatCappedListLabel('terms', entries.length, termResultLimit);
  const minOccurrencesLabel =
    minOccurrences === 1 ? 'Any hits' : `${minOccurrences.toLocaleString()}+ hits`;
  const resultLimitLabel = `${formatShortCount(termResultLimit)} terms`;
  const workspaceStyle = useMemo(
    () =>
      ({
        '--term-index-left-pane-width': `${termPaneWidthPct}%`,
      }) as CSSProperties,
    [termPaneWidthPct],
  );
  const startPaneResize = useCallback(
    (event: ReactMouseEvent<HTMLDivElement>) => {
      event.preventDefault();
      setIsPaneResizing(true);

      const handleMove = (moveEvent: MouseEvent) => {
        if (!workspaceRef.current) {
          return;
        }
        const rect = workspaceRef.current.getBoundingClientRect();
        updateTermPaneWidthPct(((moveEvent.clientX - rect.left) / rect.width) * 100);
      };
      const handleUp = () => {
        setIsPaneResizing(false);
        window.removeEventListener('mousemove', handleMove);
        window.removeEventListener('mouseup', handleUp);
      };

      window.addEventListener('mousemove', handleMove);
      window.addEventListener('mouseup', handleUp);
    },
    [updateTermPaneWidthPct],
  );
  const handlePaneDividerKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      let nextWidth: number | null = null;
      if (event.key === 'ArrowLeft') {
        nextWidth = termPaneWidthPct - 5;
      } else if (event.key === 'ArrowRight') {
        nextWidth = termPaneWidthPct + 5;
      } else if (event.key === 'Home') {
        nextWidth = 25;
      } else if (event.key === 'End') {
        nextWidth = 75;
      }

      if (nextWidth == null) {
        return;
      }

      event.preventDefault();
      updateTermPaneWidthPct(nextWidth);
    },
    [termPaneWidthPct, updateTermPaneWidthPct],
  );

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  return (
    <div className="settings-subpage term-index-explorer__page">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
        context="Settings > Glossaries"
        title="Raw term review"
        centerContent={<TermIndexSubnav active="terms" />}
      />
      <div className="settings-page settings-page--wide term-index-explorer">
        <section
          className="settings-card term-index-explorer__filter-card"
          aria-label="Term filters"
        >
          <div className="term-index-explorer__filterbar">
            <div className="term-index-explorer__repository-control">
              <RepositoryMultiSelect
                label="Repositories"
                options={repositoryOptions}
                selectedIds={selectedRepositoryIds}
                onChange={(next) => {
                  updateSelectedRepositoryIds(next);
                  resetTermSelection();
                }}
                className="settings-repository-select"
                buttonAriaLabel="Select repositories for term review. Use menu actions to switch between repositories and glossaries."
                customActions={repositorySelectionActions}
                summaryFormatter={formatRepositorySelectionSummary}
              />
            </div>
            <SearchControl
              inputId="term-index-term-search"
              value={searchQuery}
              onChange={(next) => {
                setSearchQuery(next);
                resetTermSelection();
              }}
              placeholder="Search terms or normalized keys"
              inputAriaLabel="Search raw terms"
              className="term-index-explorer__search-control"
              leading={
                <SingleSelectDropdown
                  label="Extraction method"
                  options={extractionMethodOptions}
                  value={extractionMethod}
                  onChange={(next) => {
                    setExtractionMethod(next);
                    resetTermSelection();
                  }}
                  placeholder="All methods"
                  noneLabel="All methods"
                  className="term-index-explorer__search-type"
                  buttonAriaLabel="Filter by extraction method"
                  searchable={false}
                />
              }
            />
            <div className="term-index-explorer__filter-controls">
              <SingleSelectDropdown
                label="Review status"
                options={REVIEW_STATUS_FILTER_OPTIONS}
                value={reviewStatusFilter}
                onChange={(next) => {
                  setReviewStatusFilter(next ?? 'NON_REJECTED');
                  resetTermSelection();
                }}
                placeholder="Non-rejected"
                className="term-index-explorer__search-type"
                buttonAriaLabel="Filter by review status"
                searchable={false}
              />
              <NumericPresetDropdown
                value={minOccurrences}
                buttonLabel={minOccurrencesLabel}
                menuLabel="Minimum hits"
                presetOptions={MIN_OCCURRENCES_OPTIONS}
                onChange={updateMinOccurrences}
                ariaLabel="Minimum hits"
                className="term-index-explorer__filter-number"
                pillsClassName="settings-pills"
                optionClassName="settings-pill"
                optionActiveClassName="is-active"
                customButtonClassName="settings-pill"
                customActiveClassName="is-active"
                customInitialValue={MIN_OCCURRENCES_DEFAULT}
              />
              <NumericPresetDropdown
                value={termResultLimit}
                buttonLabel={resultLimitLabel}
                menuLabel="Result size limit"
                presetOptions={TERM_RESULT_LIMIT_OPTIONS}
                onChange={updateTermResultLimit}
                ariaLabel="Term result size limit"
                disabled={entriesQuery.isLoading}
                className="term-index-explorer__filter-number"
                pillsClassName="settings-pills"
                optionClassName="settings-pill"
                optionActiveClassName="is-active"
                customButtonClassName="settings-pill"
                customActiveClassName="is-active"
                customInitialValue={TERM_RESULT_LIMIT_DEFAULT}
              />
            </div>
          </div>
        </section>

        <section className="settings-card term-index-explorer__results-card" aria-label="Raw terms">
          <div className="term-index-explorer__subbar">
            <span className="settings-hint term-index-explorer__subbar-count">
              {termCountLabel}
            </span>
            <div className="term-index-explorer__subbar-actions">
              <button
                type="button"
                className="term-index-explorer__subbar-button"
                onClick={() =>
                  setSelectedEntryIds(allEntriesSelected ? [] : entries.map((entry) => entry.id))
                }
                disabled={entries.length === 0 || batchUpdateReviewMutation.isPending}
              >
                {allEntriesSelected ? 'Deselect visible' : 'Select all'}
              </button>
              {selectedEntries.length > 0 ? (
                <>
                  <span className="settings-hint term-index-explorer__selection-summary">
                    {selectedEntries.length.toLocaleString()} selected
                  </span>
                  <SingleSelectDropdown
                    label="Bulk actions"
                    value={null}
                    placeholder={`Bulk: ${selectedEntries.length.toLocaleString()}`}
                    options={REVIEW_STATUS_OPTIONS}
                    onChange={updateSelectedEntriesReview}
                    disabled={batchUpdateReviewMutation.isPending}
                    className="term-index-explorer__bulk-dropdown"
                    buttonAriaLabel="Apply bulk review status"
                    searchable={false}
                  />
                  <button
                    type="button"
                    className="term-index-explorer__subbar-button"
                    onClick={() => setSelectedEntryIds([])}
                    disabled={batchUpdateReviewMutation.isPending}
                  >
                    Clear selection
                  </button>
                </>
              ) : null}
            </div>
          </div>
          {batchUpdateReviewMutation.error ? (
            <p className="settings-hint is-error">
              {getErrorMessage(batchUpdateReviewMutation.error)}
            </p>
          ) : null}
          {updateReviewMutation.error ? (
            <p className="settings-hint is-error">{getErrorMessage(updateReviewMutation.error)}</p>
          ) : null}
          <div className="term-index-explorer__workspace" ref={workspaceRef} style={workspaceStyle}>
            <div className="term-index-explorer__terms">
              {entriesQuery.isLoading ? (
                <div className="term-index-explorer__loading-spacer" aria-hidden="true" />
              ) : entriesQuery.isError ? (
                <p className="settings-hint is-error">{getErrorMessage(entriesQuery.error)}</p>
              ) : entries.length === 0 ? (
                <p className="settings-hint">No indexed terms match the current filters.</p>
              ) : (
                <TermList
                  entries={entries}
                  selectedEntryId={selectedEntryId}
                  selectedEntryIds={selectedEntryIds}
                  rowMetadata="term"
                  onSelectEntry={setSelectedEntryId}
                  onUpdateEntryReview={updateEntryReview}
                  isUpdatingReview={updateReviewMutation.isPending}
                  onToggleEntrySelection={(entryId, checked) =>
                    setSelectedEntryIds((current) =>
                      checked
                        ? Array.from(new Set([...current, entryId]))
                        : current.filter((id) => id !== entryId),
                    )
                  }
                />
              )}
            </div>
            <div
              className={`term-index-explorer__pane-divider${isPaneResizing ? ' is-resizing' : ''}`}
              onMouseDown={startPaneResize}
              onDoubleClick={() => updateTermPaneWidthPct(DEFAULT_TERM_PANE_WIDTH_PCT)}
              onKeyDown={handlePaneDividerKeyDown}
              role="separator"
              tabIndex={0}
              aria-label="Resize term and example panes"
              aria-orientation="vertical"
              aria-valuemin={25}
              aria-valuemax={75}
              aria-valuenow={Math.round(termPaneWidthPct)}
            >
              <PaneGripIcon />
            </div>
            <div className="term-index-explorer__occurrences">
              {selectedEntry ? (
                <ExtractedTermDetailPanel
                  entry={selectedEntry}
                  occurrences={occurrences}
                  isLoading={occurrencesQuery.isLoading}
                  error={occurrencesQuery.error}
                />
              ) : (
                <p className="settings-hint">Select a term to inspect examples.</p>
              )}
            </div>
          </div>
        </section>
      </div>
    </div>
  );
}

export function AdminTermIndexCandidateGenerationPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();
  const { data: repositories } = useRepositories();
  const repositoryOptions = useRepositorySelectionOptions(repositories ?? []);
  const {
    selectedRepositoryIds,
    effectiveRepositoryIds,
    repositorySelectionActions,
    formatRepositorySelectionSummary,
    updateSelectedRepositoryIds,
  } = useTermIndexRepositorySelection(repositoryOptions);
  const [searchQuery, setSearchQuery] = useState('');
  const [minOccurrences, setMinOccurrences] = useState(MIN_OCCURRENCES_DEFAULT);
  const [extractionMethod, setExtractionMethod] = useState<string | null>(null);
  const [reviewStatusFilter, setReviewStatusFilter] =
    useState<ApiTermIndexReviewStatusFilter>('NON_REJECTED');
  const [termResultLimit, setTermResultLimit] = useState(TERM_RESULT_LIMIT_DEFAULT);
  const [selectedEntryId, setSelectedEntryId] = useState<number | null>(null);
  const [selectedEntryIds, setSelectedEntryIds] = useState<number[]>([]);
  const [candidateDraft, setCandidateDraft] = useState<CandidateDraft>(() =>
    createCandidateDraft(null),
  );
  const [candidateGenerationRequest, setCandidateGenerationRequest] =
    useState<CandidateGenerationRequest | null>(null);
  const [candidateGenerationReport, setCandidateGenerationReport] =
    useState<ApiGenerateTermIndexCandidatesResponse | null>(null);
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; message: string } | null>(null);
  const [termPaneWidthPct, setTermPaneWidthPct] = useState(DEFAULT_TERM_PANE_WIDTH_PCT);
  const [isPaneResizing, setIsPaneResizing] = useState(false);
  const workspaceRef = useRef<HTMLDivElement>(null);

  const resetTermSelection = () => {
    setSelectedEntryId(null);
    setSelectedEntryIds([]);
    setNotice(null);
  };
  const updateTermPaneWidthPct = useCallback((next: number) => {
    setTermPaneWidthPct(Math.min(75, Math.max(25, next)));
  }, []);
  const updateTermResultLimit = (next: number) => {
    setTermResultLimit(Math.min(Math.max(1, next), TERM_RESULT_LIMIT_MAX));
    resetTermSelection();
  };
  const updateMinOccurrences = (next: number) => {
    setMinOccurrences(Math.max(1, next));
    resetTermSelection();
  };

  const statusQuery = useQuery({
    queryKey: ['term-index-status', effectiveRepositoryIds],
    queryFn: () => fetchTermIndexStatus({ repositoryIds: effectiveRepositoryIds }),
    enabled: isAdmin && repositoryOptions.length > 0,
    staleTime: 30_000,
  });

  const entriesQuery = useQuery({
    queryKey: [
      'term-index-entries',
      'candidate-generation',
      effectiveRepositoryIds,
      searchQuery,
      extractionMethod,
      reviewStatusFilter,
      minOccurrences,
      termResultLimit,
    ],
    queryFn: () =>
      fetchTermIndexEntries({
        repositoryIds: effectiveRepositoryIds,
        search: searchQuery,
        extractionMethod,
        reviewStatus: reviewStatusFilter,
        minOccurrences,
        limit: termResultLimit,
      }),
    enabled: isAdmin && repositoryOptions.length > 0,
    staleTime: 5_000,
  });

  const entryData = entriesQuery.data;
  const entries = useMemo(() => entryData?.entries ?? [], [entryData]);
  const selectedEntry = useMemo(
    () => entries.find((entry) => entry.id === selectedEntryId) ?? null,
    [entries, selectedEntryId],
  );
  const selectedEntries = useMemo(
    () => entries.filter((entry) => selectedEntryIds.includes(entry.id)),
    [entries, selectedEntryIds],
  );
  const allEntriesSelected =
    entries.length > 0 && entries.every((entry) => selectedEntryIds.includes(entry.id));
  const repositoryScopeLabel = formatRepositorySelectionSummary({
    selectedIds: effectiveRepositoryIds,
    defaultSummary:
      effectiveRepositoryIds.length === 1
        ? '1 repository'
        : `${effectiveRepositoryIds.length.toLocaleString()} repositories`,
  });

  useEffect(() => {
    if (entries.length === 0) {
      if (!entriesQuery.isFetching) {
        setSelectedEntryId(null);
      }
      return;
    }
    if (selectedEntryId == null || !entries.some((entry) => entry.id === selectedEntryId)) {
      setSelectedEntryId(entries[0].id);
    }
  }, [entries, entriesQuery.isFetching, selectedEntryId]);

  useEffect(() => {
    setCandidateDraft(createCandidateDraft(selectedEntry));
  }, [selectedEntry]);

  useEffect(() => {
    const visibleEntryIds = new Set(entries.map((entry) => entry.id));
    setSelectedEntryIds((current) => current.filter((entryId) => visibleEntryIds.has(entryId)));
  }, [entries]);

  const occurrencesQuery = useQuery({
    queryKey: [
      'term-index-occurrences',
      'candidate-generation',
      selectedEntryId,
      effectiveRepositoryIds,
      extractionMethod,
    ],
    queryFn: () =>
      fetchTermIndexOccurrences(selectedEntryId ?? 0, {
        repositoryIds: effectiveRepositoryIds,
        extractionMethod,
        limit: EXAMPLE_RESULT_LIMIT,
      }),
    enabled: isAdmin && selectedEntryId != null && repositoryOptions.length > 0,
    staleTime: 5_000,
  });

  const occurrenceData = occurrencesQuery.data;
  const occurrences = occurrenceData?.occurrences ?? [];

  const updateReviewMutation = useMutation({
    mutationFn: ({
      entryId,
      reviewStatus,
      reviewReason,
    }: {
      entryId: number;
      reviewStatus: ApiTermIndexReviewStatus;
      reviewReason?: string | null;
    }) =>
      updateTermIndexEntryReview(entryId, {
        reviewStatus,
        reviewReason: reviewReason ?? null,
      }),
    onSuccess: async (entry) => {
      setSelectedEntryId(entry.id);
      await queryClient.invalidateQueries({ queryKey: ['term-index-entries'] });
    },
  });

  const generateCandidatesMutation = useMutation({
    mutationFn: ({ entryIds, draft }: { entryIds: number[]; draft?: CandidateDraft | null }) =>
      generateTermIndexCandidatesFromEntries({
        termIndexEntryIds: entryIds,
        repositoryIds: effectiveRepositoryIds,
        definition: draft ? nullableTrimmed(draft.definition) : null,
        rationale: draft ? nullableTrimmed(draft.rationale) : null,
        termType: draft?.termType || null,
        partOfSpeech: draft ? nullableTrimmed(draft.partOfSpeech) : null,
        enforcement: draft?.enforcement || null,
        doNotTranslate: draft?.doNotTranslate ?? null,
        confidence: draft ? parseOptionalConfidence(draft.confidence) : null,
        reviewStatus: draft?.reviewStatus ?? 'TO_REVIEW',
      }),
    onSuccess: async (result) => {
      setCandidateGenerationReport(result);
      setNotice({
        kind: 'success',
        message: `Generated ${result.candidateCount.toLocaleString()} candidates (${result.createdCandidateCount.toLocaleString()} new, ${result.updatedCandidateCount.toLocaleString()} refreshed).`,
      });
      setSelectedEntryIds([]);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['glossary-term-index-suggestions'] }),
        queryClient.invalidateQueries({ queryKey: ['term-index-entries'] }),
      ]);
    },
    onError: (error: Error) => {
      setNotice({ kind: 'error', message: error.message || 'Candidate generation failed.' });
    },
  });

  const closeCandidateGenerationModal = () => {
    if (generateCandidatesMutation.isPending) {
      return;
    }
    setCandidateGenerationRequest(null);
    setCandidateGenerationReport(null);
  };

  const confirmCandidateGeneration = () => {
    if (!candidateGenerationRequest || generateCandidatesMutation.isPending) {
      return;
    }
    setNotice(null);
    setCandidateGenerationReport(null);
    generateCandidatesMutation.mutate({
      entryIds: candidateGenerationRequest.entryIds,
      draft: candidateGenerationRequest.draft,
    });
  };

  const updateEntryReview = (entryId: number, reviewStatus: ApiTermIndexReviewStatus) => {
    if (updateReviewMutation.isPending) {
      return;
    }
    setNotice(null);
    updateReviewMutation.mutate({
      entryId,
      reviewStatus,
      reviewReason: reviewStatus === 'REJECTED' ? 'OTHER' : null,
    });
  };

  const generateSelectedCandidates = () => {
    if (selectedEntries.length === 0 || generateCandidatesMutation.isPending) {
      return;
    }
    setNotice(null);
    setCandidateGenerationReport(null);
    setCandidateGenerationRequest({
      mode: 'bulk',
      entryIds: selectedEntries.map((entry) => entry.id),
      entries: selectedEntries,
      draft: null,
    });
  };

  const generateSelectedEntryCandidate = () => {
    if (!selectedEntry || generateCandidatesMutation.isPending) {
      return;
    }
    setNotice(null);
    setCandidateGenerationReport(null);
    setCandidateGenerationRequest({
      mode: 'single',
      entryIds: [selectedEntry.id],
      entries: [selectedEntry],
      draft: cloneCandidateDraft(candidateDraft),
    });
  };

  const extractionMethodOptions = useMemo(
    () =>
      (statusQuery.data?.extractionMethods ?? []).map((method) => ({
        value: method,
        label: formatMethod(method),
      })),
    [statusQuery.data?.extractionMethods],
  );
  const termCountLabel = entriesQuery.isLoading
    ? 'Loading terms...'
    : formatCappedListLabel('terms', entries.length, termResultLimit);
  const minOccurrencesLabel =
    minOccurrences === 1 ? 'Any hits' : `${minOccurrences.toLocaleString()}+ hits`;
  const resultLimitLabel = `${formatShortCount(termResultLimit)} terms`;
  const workspaceStyle = useMemo(
    () =>
      ({
        '--term-index-left-pane-width': `${termPaneWidthPct}%`,
      }) as CSSProperties,
    [termPaneWidthPct],
  );
  const startPaneResize = useCallback(
    (event: ReactMouseEvent<HTMLDivElement>) => {
      event.preventDefault();
      setIsPaneResizing(true);

      const handleMove = (moveEvent: MouseEvent) => {
        if (!workspaceRef.current) {
          return;
        }
        const rect = workspaceRef.current.getBoundingClientRect();
        updateTermPaneWidthPct(((moveEvent.clientX - rect.left) / rect.width) * 100);
      };
      const handleUp = () => {
        setIsPaneResizing(false);
        window.removeEventListener('mousemove', handleMove);
        window.removeEventListener('mouseup', handleUp);
      };

      window.addEventListener('mousemove', handleMove);
      window.addEventListener('mouseup', handleUp);
    },
    [updateTermPaneWidthPct],
  );
  const handlePaneDividerKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      let nextWidth: number | null = null;
      if (event.key === 'ArrowLeft') {
        nextWidth = termPaneWidthPct - 5;
      } else if (event.key === 'ArrowRight') {
        nextWidth = termPaneWidthPct + 5;
      } else if (event.key === 'Home') {
        nextWidth = 25;
      } else if (event.key === 'End') {
        nextWidth = 75;
      }

      if (nextWidth == null) {
        return;
      }

      event.preventDefault();
      updateTermPaneWidthPct(nextWidth);
    },
    [termPaneWidthPct, updateTermPaneWidthPct],
  );

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  return (
    <div className="settings-subpage term-index-explorer__page">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
        context="Settings > Glossaries"
        title="Candidate generation"
        centerContent={<TermIndexSubnav active="candidates" />}
      />
      <div className="settings-page settings-page--wide term-index-explorer">
        <section
          className="settings-card term-index-explorer__filter-card"
          aria-label="Candidate source filters"
        >
          <div className="term-index-explorer__filterbar">
            <div className="term-index-explorer__repository-control">
              <RepositoryMultiSelect
                label="Repositories"
                options={repositoryOptions}
                selectedIds={selectedRepositoryIds}
                onChange={(next) => {
                  updateSelectedRepositoryIds(next);
                  resetTermSelection();
                }}
                className="settings-repository-select"
                buttonAriaLabel="Select repositories for candidate generation. Use menu actions to switch between repositories and glossaries."
                customActions={repositorySelectionActions}
                summaryFormatter={formatRepositorySelectionSummary}
              />
            </div>
            <SearchControl
              inputId="term-index-candidate-source-search"
              value={searchQuery}
              onChange={(next) => {
                setSearchQuery(next);
                resetTermSelection();
              }}
              placeholder="Search non-rejected extracted terms"
              inputAriaLabel="Search extracted terms for candidate generation"
              className="term-index-explorer__search-control"
              leading={
                <SingleSelectDropdown
                  label="Extraction method"
                  options={extractionMethodOptions}
                  value={extractionMethod}
                  onChange={(next) => {
                    setExtractionMethod(next);
                    resetTermSelection();
                  }}
                  placeholder="All methods"
                  noneLabel="All methods"
                  className="term-index-explorer__search-type"
                  buttonAriaLabel="Filter by extraction method"
                  searchable={false}
                />
              }
            />
            <div className="term-index-explorer__filter-controls">
              <SingleSelectDropdown
                label="Review status"
                options={REVIEW_STATUS_FILTER_OPTIONS}
                value={reviewStatusFilter}
                onChange={(next) => {
                  setReviewStatusFilter(next ?? 'NON_REJECTED');
                  resetTermSelection();
                }}
                placeholder="Non-rejected"
                className="term-index-explorer__search-type"
                buttonAriaLabel="Filter by raw term review status"
                searchable={false}
              />
              <NumericPresetDropdown
                value={minOccurrences}
                buttonLabel={minOccurrencesLabel}
                menuLabel="Minimum hits"
                presetOptions={MIN_OCCURRENCES_OPTIONS}
                onChange={updateMinOccurrences}
                ariaLabel="Minimum hits"
                className="term-index-explorer__filter-number"
                pillsClassName="settings-pills"
                optionClassName="settings-pill"
                optionActiveClassName="is-active"
                customButtonClassName="settings-pill"
                customActiveClassName="is-active"
                customInitialValue={MIN_OCCURRENCES_DEFAULT}
              />
              <NumericPresetDropdown
                value={termResultLimit}
                buttonLabel={resultLimitLabel}
                menuLabel="Result size limit"
                presetOptions={TERM_RESULT_LIMIT_OPTIONS}
                onChange={updateTermResultLimit}
                ariaLabel="Candidate source result size limit"
                disabled={entriesQuery.isLoading}
                className="term-index-explorer__filter-number"
                pillsClassName="settings-pills"
                optionClassName="settings-pill"
                optionActiveClassName="is-active"
                customButtonClassName="settings-pill"
                customActiveClassName="is-active"
                customInitialValue={TERM_RESULT_LIMIT_DEFAULT}
              />
            </div>
          </div>
        </section>

        <section
          className="settings-card term-index-explorer__results-card"
          aria-label="Candidate sources"
        >
          <div className="term-index-explorer__subbar">
            <span className="settings-hint term-index-explorer__subbar-count">
              {termCountLabel}
            </span>
            <div className="term-index-explorer__subbar-actions">
              <button
                type="button"
                className="term-index-explorer__subbar-button"
                onClick={() =>
                  setSelectedEntryIds(allEntriesSelected ? [] : entries.map((entry) => entry.id))
                }
                disabled={entries.length === 0 || generateCandidatesMutation.isPending}
              >
                {allEntriesSelected ? 'Deselect visible' : 'Select all'}
              </button>
              {selectedEntries.length > 0 ? (
                <>
                  <span className="settings-hint term-index-explorer__selection-summary">
                    {selectedEntries.length.toLocaleString()} selected
                  </span>
                  <button
                    type="button"
                    className="term-index-explorer__subbar-button"
                    onClick={generateSelectedCandidates}
                    disabled={generateCandidatesMutation.isPending}
                  >
                    Generate selected candidates
                  </button>
                  <button
                    type="button"
                    className="term-index-explorer__subbar-button"
                    onClick={() => setSelectedEntryIds([])}
                    disabled={generateCandidatesMutation.isPending}
                  >
                    Clear selection
                  </button>
                </>
              ) : null}
            </div>
          </div>
          {notice ? (
            <p className={`settings-hint${notice.kind === 'error' ? ' is-error' : ''}`}>
              {notice.message}
            </p>
          ) : null}
          {updateReviewMutation.error ? (
            <p className="settings-hint is-error">{getErrorMessage(updateReviewMutation.error)}</p>
          ) : null}
          <div className="term-index-explorer__workspace" ref={workspaceRef} style={workspaceStyle}>
            <div className="term-index-explorer__terms">
              {entriesQuery.isLoading ? (
                <div className="term-index-explorer__loading-spacer" aria-hidden="true" />
              ) : entriesQuery.isError ? (
                <p className="settings-hint is-error">{getErrorMessage(entriesQuery.error)}</p>
              ) : entries.length === 0 ? (
                <p className="settings-hint">No non-rejected extracted terms match the filters.</p>
              ) : (
                <TermList
                  entries={entries}
                  selectedEntryId={selectedEntryId}
                  selectedEntryIds={selectedEntryIds}
                  rowMetadata="candidate"
                  onSelectEntry={setSelectedEntryId}
                  onUpdateEntryReview={updateEntryReview}
                  isUpdatingReview={updateReviewMutation.isPending}
                  onToggleEntrySelection={(entryId, checked) =>
                    setSelectedEntryIds((current) =>
                      checked
                        ? Array.from(new Set([...current, entryId]))
                        : current.filter((id) => id !== entryId),
                    )
                  }
                />
              )}
            </div>
            <div
              className={`term-index-explorer__pane-divider${isPaneResizing ? ' is-resizing' : ''}`}
              onMouseDown={startPaneResize}
              onDoubleClick={() => updateTermPaneWidthPct(DEFAULT_TERM_PANE_WIDTH_PCT)}
              onKeyDown={handlePaneDividerKeyDown}
              role="separator"
              tabIndex={0}
              aria-label="Resize term and candidate panes"
              aria-orientation="vertical"
              aria-valuemin={25}
              aria-valuemax={75}
              aria-valuenow={Math.round(termPaneWidthPct)}
            >
              <PaneGripIcon />
            </div>
            <div className="term-index-explorer__occurrences">
              {selectedEntry ? (
                <CandidateDraftPanel
                  entry={selectedEntry}
                  draft={candidateDraft}
                  occurrences={occurrences}
                  isLoadingOccurrences={occurrencesQuery.isLoading}
                  occurrenceError={occurrencesQuery.error}
                  isGenerating={generateCandidatesMutation.isPending}
                  onDraftChange={setCandidateDraft}
                  onGenerate={generateSelectedEntryCandidate}
                />
              ) : (
                <p className="settings-hint">Select a term to draft a candidate.</p>
              )}
            </div>
          </div>
        </section>
      </div>
      <CandidateGenerationModal
        open={candidateGenerationRequest != null}
        request={candidateGenerationRequest}
        report={candidateGenerationReport}
        repositoryScopeLabel={repositoryScopeLabel}
        searchQuery={searchQuery}
        extractionMethod={extractionMethod}
        reviewStatusFilter={reviewStatusFilter}
        isGenerating={generateCandidatesMutation.isPending}
        error={generateCandidatesMutation.error}
        onClose={closeCandidateGenerationModal}
        onGenerate={confirmCandidateGeneration}
      />
    </div>
  );
}

function TermIndexSubnav({ active }: { active: 'runs' | 'terms' | 'candidates' }) {
  return (
    <nav className="term-index-explorer__subnav" aria-label="Term index sections">
      <Link
        className={`term-index-explorer__subnav-link${active === 'runs' ? ' is-active' : ''}`}
        to="/settings/system/glossary-term-index"
      >
        Runs
      </Link>
      <Link
        className={`term-index-explorer__subnav-link${active === 'terms' ? ' is-active' : ''}`}
        to="/settings/system/glossary-term-index/terms"
      >
        Terms
      </Link>
      <Link
        className={`term-index-explorer__subnav-link${active === 'candidates' ? ' is-active' : ''}`}
        to="/settings/system/glossary-term-index/candidates"
      >
        Candidates
      </Link>
    </nav>
  );
}

function TermIndexRefreshModal({
  open,
  repositoryOptions,
  selectedRepositoryIds,
  fullRefresh,
  isRefreshing,
  onRepositoryChange,
  onFullRefreshChange,
  onClose,
  onStart,
}: {
  open: boolean;
  repositoryOptions: ReturnType<typeof useRepositorySelectionOptions>;
  selectedRepositoryIds: number[];
  fullRefresh: boolean;
  isRefreshing: boolean;
  onRepositoryChange: (repositoryIds: number[]) => void;
  onFullRefreshChange: (fullRefresh: boolean) => void;
  onClose: () => void;
  onStart: () => void;
}) {
  const allRepositoryIds = useMemo(() => getRepositoryIds(repositoryOptions), [repositoryOptions]);
  const productRepositoryIds = useMemo(
    () => getProductRepositoryIds(repositoryOptions),
    [repositoryOptions],
  );
  const glossaryRepositoryIds = useMemo(
    () => getGlossaryRepositoryIds(repositoryOptions),
    [repositoryOptions],
  );
  const repositorySelectionActions = useMemo(
    () =>
      getRepositoryTypeSelectionActions({
        selectedRepositoryIds,
        productRepositoryIds,
        glossaryRepositoryIds,
        onChangeRepositorySelection: onRepositoryChange,
      }),
    [glossaryRepositoryIds, onRepositoryChange, productRepositoryIds, selectedRepositoryIds],
  );
  const formatRepositorySelectionSummary = useMemo(
    () =>
      getRepositorySelectionSummaryFormatter(
        allRepositoryIds,
        productRepositoryIds,
        glossaryRepositoryIds,
      ),
    [allRepositoryIds, glossaryRepositoryIds, productRepositoryIds],
  );
  const effectiveRefreshRepositoryIds =
    selectedRepositoryIds.length > 0 ? selectedRepositoryIds : allRepositoryIds;
  const refreshRepositoryCount = effectiveRefreshRepositoryIds.length;
  const canStart = refreshRepositoryCount > 0 && !isRefreshing;
  const repositorySummary =
    selectedRepositoryIds.length === 0
      ? 'All repositories selected'
      : refreshRepositoryCount === 1
        ? '1 repository selected'
        : `${refreshRepositoryCount.toLocaleString()} repositories selected`;

  return (
    <Modal
      open={open}
      onClose={onClose}
      closeOnBackdrop={!isRefreshing}
      ariaLabel="Refresh term index"
      className="term-index-explorer__refresh-modal"
    >
      <div className="modal__header">
        <div className="modal__title">Refresh term index</div>
      </div>
      <div className="modal__body term-index-explorer__refresh-modal-body">
        <div className="settings-field">
          <div className="term-index-explorer__repository-control">
            <RepositoryMultiSelect
              label="Repositories to refresh"
              options={repositoryOptions}
              selectedIds={selectedRepositoryIds}
              onChange={onRepositoryChange}
              className="settings-repository-select"
              buttonAriaLabel="Select repositories to refresh. Use menu actions to switch between repositories and glossaries."
              customActions={repositorySelectionActions}
              summaryFormatter={formatRepositorySelectionSummary}
            />
          </div>
          <p className={`settings-hint${refreshRepositoryCount === 0 ? ' is-error' : ''}`}>
            {refreshRepositoryCount === 0 ? 'Select at least one repository.' : repositorySummary}
          </p>
        </div>

        <fieldset className="term-index-explorer__refresh-mode">
          <legend>Refresh mode</legend>
          <label
            className={`term-index-explorer__refresh-mode-option${
              !fullRefresh ? ' is-selected' : ''
            }`}
          >
            <input
              type="radio"
              name="term-index-refresh-mode"
              checked={!fullRefresh}
              onChange={() => onFullRefreshChange(false)}
            />
            <span>
              <strong>Incremental</strong>
              <span>Process source text units after each repository cursor.</span>
            </span>
          </label>
          <label
            className={`term-index-explorer__refresh-mode-option${
              fullRefresh ? ' is-selected' : ''
            }`}
          >
            <input
              type="radio"
              name="term-index-refresh-mode"
              checked={fullRefresh}
              onChange={() => onFullRefreshChange(true)}
            />
            <span>
              <strong>Full reindex</strong>
              <span>Clear recorded occurrences for selected repositories and rebuild them.</span>
            </span>
          </label>
        </fieldset>
      </div>
      <div className="modal__actions">
        <button type="button" className="modal__button" onClick={onClose} disabled={isRefreshing}>
          Cancel
        </button>
        <button
          type="button"
          className="modal__button modal__button--primary"
          onClick={onStart}
          disabled={!canStart}
        >
          {isRefreshing ? 'Starting...' : 'Start refresh'}
        </button>
      </div>
    </Modal>
  );
}

function CandidateGenerationModal({
  open,
  request,
  report,
  repositoryScopeLabel,
  searchQuery,
  extractionMethod,
  reviewStatusFilter,
  isGenerating,
  error,
  onClose,
  onGenerate,
}: {
  open: boolean;
  request: CandidateGenerationRequest | null;
  report: ApiGenerateTermIndexCandidatesResponse | null;
  repositoryScopeLabel: string;
  searchQuery: string;
  extractionMethod: string | null;
  reviewStatusFilter: ApiTermIndexReviewStatusFilter;
  isGenerating: boolean;
  error: unknown;
  onClose: () => void;
  onGenerate: () => void;
}) {
  const selectedEntryCount = request?.entries.length ?? 0;
  const draft = request?.draft ?? null;
  const sourceLabel =
    request?.mode === 'single'
      ? (request.entries[0]?.displayTerm ?? 'Selected extracted term')
      : selectedEntryCount === 1
        ? '1 selected extracted term'
        : `${selectedEntryCount.toLocaleString()} selected extracted terms`;
  const sampleEntries = request?.entries.slice(0, 6) ?? [];
  const remainingEntryCount = Math.max(0, selectedEntryCount - sampleEntries.length);
  const reviewStatusLabel =
    REVIEW_STATUS_OPTIONS.find((option) => option.value === draft?.reviewStatus)?.label ??
    'To review';
  const reviewStatusFilterLabel =
    REVIEW_STATUS_FILTER_OPTIONS.find((option) => option.value === reviewStatusFilter)?.label ??
    reviewStatusFilter;
  const hasDraftOverrides =
    draft != null &&
    (Boolean(draft.definition.trim()) ||
      Boolean(draft.rationale.trim()) ||
      Boolean(draft.partOfSpeech.trim()) ||
      Boolean(draft.confidence.trim()) ||
      draft.termType !== 'GENERAL' ||
      draft.enforcement !== 'SOFT' ||
      draft.doNotTranslate ||
      draft.reviewStatus !== 'TO_REVIEW');
  const generatedCandidates = report?.candidates.slice(0, 10) ?? [];

  return (
    <Modal
      open={open}
      size="lg"
      ariaLabel="Generate term index candidates"
      onClose={onClose}
      closeOnBackdrop={!isGenerating}
    >
      <div className="modal__header">
        <div>
          <h3 className="modal__title">
            {report
              ? 'Candidate generation report'
              : request?.mode === 'single'
                ? 'Generate this candidate'
                : 'Generate selected candidates'}
          </h3>
          <p className="settings-hint">
            {report
              ? 'The candidate table has been refreshed from the selected extracted terms.'
              : 'Create or refresh stored candidates from reviewed extracted terms. Definition and rationale are only saved when provided here.'}
          </p>
        </div>
      </div>

      <div className="modal__body term-index-explorer__generation-modal-body">
        <dl className="term-index-explorer__generation-facts">
          <div>
            <dt>Source</dt>
            <dd>{sourceLabel}</dd>
          </div>
          <div>
            <dt>Repository scope</dt>
            <dd>{repositoryScopeLabel}</dd>
          </div>
          <div>
            <dt>Search filter</dt>
            <dd>{searchQuery.trim() || 'None'}</dd>
          </div>
          <div>
            <dt>Extraction method</dt>
            <dd>{formatMethod(extractionMethod)}</dd>
          </div>
          <div>
            <dt>Raw term status filter</dt>
            <dd>{reviewStatusFilterLabel}</dd>
          </div>
        </dl>

        {report ? (
          <>
            <dl className="term-index-explorer__generation-facts term-index-explorer__generation-facts--report">
              <div>
                <dt>Total</dt>
                <dd>{report.candidateCount.toLocaleString()}</dd>
              </div>
              <div>
                <dt>New</dt>
                <dd>{report.createdCandidateCount.toLocaleString()}</dd>
              </div>
              <div>
                <dt>Refreshed</dt>
                <dd>{report.updatedCandidateCount.toLocaleString()}</dd>
              </div>
            </dl>
            {generatedCandidates.length > 0 ? (
              <div className="term-index-explorer__generation-samples">
                <div className="settings-field__label">Generated candidates</div>
                <p className="settings-hint">
                  The candidate source list has been refreshed. Rows that were generated now show
                  their candidate IDs in this page.
                </p>
                <ul>
                  {generatedCandidates.map((candidate) => (
                    <li key={candidate.termIndexCandidateId}>
                      <span>{candidate.term}</span>
                      <span className="settings-hint">#{candidate.termIndexCandidateId}</span>
                      {candidate.termIndexExtractedTermId != null ? (
                        <span className="settings-hint">
                          extracted #{candidate.termIndexExtractedTermId}
                        </span>
                      ) : null}
                    </li>
                  ))}
                </ul>
              </div>
            ) : (
              <p className="settings-hint">
                No candidates were generated. Rejected or missing extracted terms are skipped.
              </p>
            )}
          </>
        ) : (
          <div className="term-index-explorer__generation-summary">
            <div className="settings-field__label">This will</div>
            <ul>
              <li>Create missing candidates for the selected extracted terms.</li>
              <li>Refresh existing extraction-backed candidates for the same extracted terms.</li>
              <li>Keep definition and rationale empty unless manual values are provided.</li>
              <li>Leave glossary terms unchanged until a candidate is accepted in a glossary.</li>
              <li>Skip rejected extracted terms if any are selected.</li>
            </ul>
            {draft ? (
              <div className="term-index-explorer__generation-overrides">
                <div className="settings-field__label">Candidate fields</div>
                {hasDraftOverrides ? (
                  <dl>
                    <div>
                      <dt>Term type</dt>
                      <dd>
                        {TERM_TYPE_OPTIONS.find((option) => option.value === draft.termType)
                          ?.label ?? draft.termType}
                      </dd>
                    </div>
                    <div>
                      <dt>Enforcement</dt>
                      <dd>
                        {ENFORCEMENT_OPTIONS.find((option) => option.value === draft.enforcement)
                          ?.label ?? draft.enforcement}
                      </dd>
                    </div>
                    <div>
                      <dt>Review status</dt>
                      <dd>{reviewStatusLabel}</dd>
                    </div>
                    {draft.partOfSpeech.trim() ? (
                      <div>
                        <dt>Part of speech</dt>
                        <dd>{draft.partOfSpeech.trim()}</dd>
                      </div>
                    ) : null}
                    {draft.confidence.trim() ? (
                      <div>
                        <dt>Confidence</dt>
                        <dd>{draft.confidence.trim()}%</dd>
                      </div>
                    ) : null}
                    {draft.doNotTranslate ? (
                      <div>
                        <dt>Flags</dt>
                        <dd>Do not translate</dd>
                      </div>
                    ) : null}
                  </dl>
                ) : (
                  <p className="settings-hint">
                    No custom fields set. Defaults will be used for this candidate.
                  </p>
                )}
              </div>
            ) : (
              <p className="settings-hint">
                Bulk generation uses default candidate fields, leaves definition and rationale
                empty, and sets generated candidates to To review.
              </p>
            )}
            {sampleEntries.length > 0 ? (
              <div className="term-index-explorer__generation-samples">
                <div className="settings-field__label">Selected extracted terms</div>
                <ul>
                  {sampleEntries.map((entry) => (
                    <li key={entry.id}>
                      <span>{entry.displayTerm}</span>
                      <span className="settings-hint">
                        extracted #{entry.id} · {entry.occurrenceCount.toLocaleString()} hits
                      </span>
                    </li>
                  ))}
                  {remainingEntryCount > 0 ? (
                    <li className="settings-hint">
                      +{remainingEntryCount.toLocaleString()} more selected
                    </li>
                  ) : null}
                </ul>
              </div>
            ) : null}
            {error ? <p className="settings-hint is-error">{getErrorMessage(error)}</p> : null}
          </div>
        )}
      </div>

      <div className="modal__footer">
        <button
          type="button"
          className="settings-button settings-button--ghost"
          onClick={onClose}
          disabled={isGenerating}
        >
          {report ? 'Close' : 'Cancel'}
        </button>
        {report ? null : (
          <button
            type="button"
            className="settings-button settings-button--primary"
            onClick={onGenerate}
            disabled={isGenerating || !request || request.entryIds.length === 0}
          >
            {isGenerating
              ? 'Generating...'
              : request?.mode === 'single'
                ? 'Generate this candidate'
                : 'Generate selected candidates'}
          </button>
        )}
      </div>
    </Modal>
  );
}

function CandidateDraftPanel({
  entry,
  draft,
  occurrences,
  isLoadingOccurrences,
  occurrenceError,
  isGenerating,
  onDraftChange,
  onGenerate,
}: {
  entry: ApiTermIndexEntry;
  draft: CandidateDraft;
  occurrences: ApiTermIndexOccurrence[];
  isLoadingOccurrences: boolean;
  occurrenceError: unknown;
  isGenerating: boolean;
  onDraftChange: (draft: CandidateDraft) => void;
  onGenerate: () => void;
}) {
  const updateDraft = <K extends keyof CandidateDraft>(key: K, value: CandidateDraft[K]) => {
    onDraftChange({ ...draft, [key]: value });
  };
  const createdLabel = entry.createdDate ? formatLocalDateTime(entry.createdDate) : null;

  return (
    <div className="term-index-explorer__candidate-panel">
      <section className="term-index-explorer__candidate-card" aria-label="Candidate draft">
        <div className="term-index-explorer__candidate-header">
          <div className="term-index-explorer__candidate-heading">
            <div className="term-index-explorer__candidate-title-line">
              <div className="term-index-explorer__candidate-title">{entry.displayTerm}</div>
              <span className="term-index-explorer__candidate-primary-id">
                {entry.termIndexCandidateId != null
                  ? `#${entry.termIndexCandidateId}`
                  : 'Not generated'}
              </span>
            </div>
            <div className="term-index-explorer__candidate-meta-line">
              <Link to={getRawTermExplorerPath(entry.id, entry.displayTerm)}>
                Extracted term #{entry.id}
              </Link>
              <span>{entry.occurrenceCount.toLocaleString()} hits</span>
              <span>{entry.repositoryCount.toLocaleString()} repositories</span>
              {createdLabel ? (
                <time dateTime={entry.createdDate ?? undefined} title={createdLabel}>
                  Source created {createdLabel}
                </time>
              ) : null}
            </div>
          </div>
          <button
            type="button"
            className="settings-button settings-button--primary"
            onClick={onGenerate}
            disabled={isGenerating || entry.reviewStatus === 'REJECTED'}
          >
            {isGenerating ? 'Generating' : 'Generate this candidate'}
          </button>
        </div>
        <div className="settings-grid settings-grid--two-column">
          <label className="settings-field">
            <span className="settings-field__label">Term type</span>
            <select
              className="settings-input"
              value={draft.termType}
              onChange={(event) => updateDraft('termType', event.target.value)}
            >
              {TERM_TYPE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="settings-field">
            <span className="settings-field__label">Enforcement</span>
            <select
              className="settings-input"
              value={draft.enforcement}
              onChange={(event) => updateDraft('enforcement', event.target.value)}
            >
              {ENFORCEMENT_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="settings-field">
            <span className="settings-field__label">Part of speech</span>
            <input
              className="settings-input"
              value={draft.partOfSpeech}
              onChange={(event) => updateDraft('partOfSpeech', event.target.value)}
              placeholder="Optional"
            />
          </label>
          <label className="settings-field">
            <span className="settings-field__label">Confidence</span>
            <input
              className="settings-input"
              type="number"
              min="0"
              max="100"
              value={draft.confidence}
              onChange={(event) => updateDraft('confidence', event.target.value)}
              placeholder="Automatic"
            />
          </label>
        </div>
        <label className="settings-field">
          <span className="settings-field__label">Definition</span>
          <textarea
            className="settings-input term-index-explorer__candidate-textarea"
            value={draft.definition}
            onChange={(event) => updateDraft('definition', event.target.value)}
            placeholder="Manual definition override"
          />
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Rationale</span>
          <textarea
            className="settings-input term-index-explorer__candidate-textarea"
            value={draft.rationale}
            onChange={(event) => updateDraft('rationale', event.target.value)}
            placeholder="Manual review rationale"
          />
        </label>
        <div className="term-index-explorer__candidate-options">
          <label className="settings-toggle">
            <input
              type="checkbox"
              checked={draft.doNotTranslate}
              onChange={(event) => updateDraft('doNotTranslate', event.target.checked)}
            />
            <span>Do not translate</span>
          </label>
          <SingleSelectDropdown
            label="Candidate review"
            value={draft.reviewStatus}
            options={REVIEW_STATUS_OPTIONS}
            onChange={(next) => updateDraft('reviewStatus', next ?? 'TO_REVIEW')}
            className="term-index-explorer__candidate-review"
            buttonAriaLabel="Set generated candidate review status"
            searchable={false}
          />
        </div>
      </section>
      <section className="term-index-explorer__candidate-card" aria-label="Usage examples">
        <div className="term-index-explorer__candidate-section-title">Usage examples</div>
        <OccurrencePanel
          entryId={entry.id}
          occurrences={occurrences}
          isLoading={isLoadingOccurrences}
          error={occurrenceError}
        />
      </section>
    </div>
  );
}

function ExtractedTermDetailPanel({
  entry,
  occurrences,
  isLoading,
  error,
}: {
  entry: ApiTermIndexEntry;
  occurrences: ApiTermIndexOccurrence[];
  isLoading: boolean;
  error: unknown;
}) {
  return (
    <div className="term-index-explorer__entry-detail-panel">
      <TermIndexEntryMetadata entry={entry} />
      <OccurrencePanel
        entryId={entry.id}
        occurrences={occurrences}
        isLoading={isLoading}
        error={error}
      />
    </div>
  );
}

function TermIndexEntryMetadata({ entry }: { entry: ApiTermIndexEntry }) {
  return (
    <div className="term-index-explorer__entry-metadata" aria-label="Term index metadata">
      <span>Extracted term #{entry.id}</span>
      <span>Created {formatLocalDateTime(entry.createdDate)}</span>
      <span>Updated {formatLocalDateTime(entry.lastModifiedDate)}</span>
      {entry.lastOccurrenceAt ? (
        <span>Last hit {formatLocalDateTime(entry.lastOccurrenceAt)}</span>
      ) : null}
    </div>
  );
}

function IndexStatusTables({
  statusQuery,
  runResultLimit,
  runCountLabel,
  onChangeRunResultLimit,
}: {
  statusQuery: ReturnType<typeof useQuery<Awaited<ReturnType<typeof fetchTermIndexStatus>>, Error>>;
  runResultLimit: number;
  runCountLabel: string;
  onChangeRunResultLimit: (value: number) => void;
}) {
  return (
    <div className="term-index-explorer__status-grid">
      <div>
        <div className="term-index-explorer__subheading">Repository cursors</div>
        {statusQuery.isLoading ? (
          <p className="settings-hint">Loading cursors...</p>
        ) : statusQuery.isError ? (
          <p className="settings-hint is-error">{getErrorMessage(statusQuery.error)}</p>
        ) : (statusQuery.data?.cursors ?? []).length === 0 ? (
          <p className="settings-hint">No cursor rows yet.</p>
        ) : (
          <div className="settings-table-wrapper">
            <table className="settings-table term-index-explorer__status-table">
              <thead>
                <tr>
                  <th>Repository</th>
                  <th>Status</th>
                  <th>Last success</th>
                  <th>Lease</th>
                </tr>
              </thead>
              <tbody>
                {(statusQuery.data?.cursors ?? []).map((cursor) => (
                  <tr key={cursor.repositoryId}>
                    <td>{cursor.repositoryName}</td>
                    <td>{cursor.status}</td>
                    <td>{formatLocalDateTime(cursor.lastSuccessfulScanAt)}</td>
                    <td>
                      {cursor.leaseExpiresAt
                        ? `Run ${cursor.currentRefreshRunId ?? 'unknown'} until ${formatLocalDateTime(
                            cursor.leaseExpiresAt,
                          )}`
                        : 'None'}
                      {cursor.errorMessage ? (
                        <div className="settings-hint is-error">{cursor.errorMessage}</div>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      <div>
        <div className="term-index-explorer__subheading-row">
          <div className="term-index-explorer__subheading">Recent refresh runs</div>
          <NumericPresetDropdown
            value={runResultLimit}
            buttonLabel={runCountLabel}
            menuLabel="Recent run limit"
            presetOptions={RUN_RESULT_LIMIT_OPTIONS}
            onChange={onChangeRunResultLimit}
            ariaLabel="Recent refresh run result size limit"
            disabled={statusQuery.isLoading}
            className="term-index-explorer__run-result-size"
            buttonClassName="term-index-explorer__run-result-size-button"
            pillsClassName="settings-pills"
            optionClassName="settings-pill"
            optionActiveClassName="is-active"
            customButtonClassName="settings-pill"
            customActiveClassName="is-active"
            customInitialValue={RUN_RESULT_LIMIT_DEFAULT}
          />
        </div>
        {statusQuery.isLoading ? (
          <p className="settings-hint">Loading runs...</p>
        ) : (statusQuery.data?.recentRuns ?? []).length === 0 ? (
          <p className="settings-hint">No refresh runs yet.</p>
        ) : (
          <div className="settings-table-wrapper">
            <table className="settings-table term-index-explorer__status-table">
              <thead>
                <tr>
                  <th>Run</th>
                  <th>Status</th>
                  <th>Text units</th>
                  <th>Extracted terms</th>
                  <th>Started</th>
                  <th>Completed</th>
                </tr>
              </thead>
              <tbody>
                {(statusQuery.data?.recentRuns ?? []).map((run) => (
                  <tr key={run.id}>
                    <td>#{run.id}</td>
                    <td>{run.status}</td>
                    <td>{run.processedTextUnitCount}</td>
                    <td>{run.extractedTermCount}</td>
                    <td>{formatLocalDateTime(run.startedAt)}</td>
                    <td>
                      {run.completedAt ? formatLocalDateTime(run.completedAt) : 'In progress'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

function TermList({
  entries,
  selectedEntryId,
  selectedEntryIds,
  rowMetadata,
  onSelectEntry,
  onUpdateEntryReview,
  isUpdatingReview,
  onToggleEntrySelection,
}: {
  entries: ApiTermIndexEntry[];
  selectedEntryId: number | null;
  selectedEntryIds: number[];
  rowMetadata: 'term' | 'candidate';
  onSelectEntry: (entryId: number) => void;
  onUpdateEntryReview: (entryId: number, reviewStatus: ApiTermIndexReviewStatus) => void;
  isUpdatingReview: boolean;
  onToggleEntrySelection: (entryId: number, checked: boolean) => void;
}) {
  const termRowRefs = useRef(new Map<number, HTMLDivElement>());
  const selectedIndex = entries.findIndex((entry) => entry.id === selectedEntryId);
  const estimateSize = useCallback(() => 40, []);
  const getItemKey = useCallback((index: number) => entries[index]?.id ?? index, [entries]);
  const {
    scrollRef,
    items: virtualItems,
    totalSize,
    scrollToIndex,
    measureElement,
  } = useVirtualRows<HTMLDivElement>({
    count: entries.length,
    getItemKey,
    estimateSize,
    overscan: 8,
  });
  const { getRowRef } = useMeasuredRowRefs<number, HTMLDivElement>({ measureElement });

  const findNextEntryIndex = (startIndex: number, direction: 1 | -1) => {
    const nextIndex = startIndex + direction;
    return nextIndex >= 0 && nextIndex < entries.length ? nextIndex : -1;
  };

  const activateEntryAtIndex = (index: number) => {
    const nextEntry = entries[index];
    if (!nextEntry) {
      return;
    }
    scrollToIndex(index, { align: 'auto' });
    onSelectEntry(nextEntry.id);
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        termRowRefs.current.get(nextEntry.id)?.focus();
      });
    });
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (
      event.key !== 'ArrowDown' &&
      event.key !== 'ArrowUp' &&
      event.key !== 'Home' &&
      event.key !== 'End'
    ) {
      return;
    }
    if (isTextEntryTarget(event.target)) {
      return;
    }

    const focusedIndex = getTermIndexFromTarget(event.target);
    const currentIndex = focusedIndex >= 0 ? focusedIndex : selectedIndex;
    let nextIndex: number;

    if (event.key === 'Home') {
      nextIndex = 0;
    } else if (event.key === 'End') {
      nextIndex = entries.length - 1;
    } else {
      const direction = event.key === 'ArrowDown' ? 1 : -1;
      const startIndex = currentIndex >= 0 ? currentIndex : direction === 1 ? -1 : entries.length;
      nextIndex = findNextEntryIndex(startIndex, direction);
    }

    if (nextIndex < 0 || entries.length === 0) {
      return;
    }

    event.preventDefault();
    activateEntryAtIndex(nextIndex);
  };

  useEffect(() => {
    if (selectedIndex >= 0) {
      scrollToIndex(selectedIndex, { align: 'auto' });
    }
  }, [scrollToIndex, selectedIndex]);

  return (
    <div
      className="term-index-explorer__term-list"
      role="listbox"
      aria-label="Indexed terms"
      aria-multiselectable="true"
      onKeyDown={handleKeyDown}
    >
      <VirtualList
        scrollRef={scrollRef}
        items={virtualItems}
        totalSize={totalSize}
        renderRow={(virtualRow) => {
          const entry = entries[virtualRow.index];
          if (!entry) {
            return null;
          }
          return {
            key: entry.id,
            className: 'term-index-explorer__term-virtual-row',
            props: {
              ref: getRowRef(entry.id),
            },
            content: (
              <TermRow
                entry={entry}
                index={virtualRow.index}
                rowMetadata={rowMetadata}
                selected={entry.id === selectedEntryId}
                selectedForBatch={selectedEntryIds.includes(entry.id)}
                focusable={
                  selectedIndex < 0 ? virtualRow.index === 0 : entry.id === selectedEntryId
                }
                rowRef={(node) => {
                  if (node) {
                    termRowRefs.current.set(entry.id, node);
                  } else {
                    termRowRefs.current.delete(entry.id);
                  }
                }}
                onSelect={() => onSelectEntry(entry.id)}
                onUpdateReview={(reviewStatus) => onUpdateEntryReview(entry.id, reviewStatus)}
                isUpdatingReview={isUpdatingReview}
                onToggleSelection={(checked) => onToggleEntrySelection(entry.id, checked)}
              />
            ),
          };
        }}
      />
    </div>
  );
}

function TermRow({
  entry,
  index,
  rowMetadata,
  selected,
  selectedForBatch,
  focusable,
  rowRef,
  onSelect,
  onUpdateReview,
  isUpdatingReview,
  onToggleSelection,
}: {
  entry: ApiTermIndexEntry;
  index: number;
  rowMetadata: 'term' | 'candidate';
  selected: boolean;
  selectedForBatch: boolean;
  focusable: boolean;
  rowRef: (node: HTMLDivElement | null) => void;
  onSelect: () => void;
  onUpdateReview: (reviewStatus: ApiTermIndexReviewStatus) => void;
  isUpdatingReview: boolean;
  onToggleSelection: (checked: boolean) => void;
}) {
  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (isInteractiveTarget(event.target)) {
      return;
    }
    if (event.altKey || event.ctrlKey || event.metaKey) {
      return;
    }
    if (event.key === 'Enter') {
      event.preventDefault();
      onSelect();
      return;
    }
    if (event.key === ' ') {
      event.preventDefault();
      onToggleSelection(!selectedForBatch);
    }
  };

  return (
    <div
      ref={rowRef}
      role="option"
      aria-selected={selected}
      data-term-index={index}
      tabIndex={focusable ? 0 : -1}
      className={`term-index-explorer__term-row${selected ? ' is-selected' : ''}`}
      onClick={onSelect}
      onFocus={onSelect}
      onKeyDown={handleKeyDown}
    >
      <input
        type="checkbox"
        className="term-index-explorer__term-checkbox"
        checked={selectedForBatch}
        onClick={(event) => event.stopPropagation()}
        onChange={(event) => onToggleSelection(event.target.checked)}
        aria-label={`Select ${entry.displayTerm}`}
      />
      <span className="term-index-explorer__term-body">
        <span className="term-index-explorer__term-main">
          <span className="term-index-explorer__term-name-cell">
            <span className="term-index-explorer__term-name">{entry.displayTerm}</span>
            {rowMetadata === 'term' ? (
              <span className="term-index-explorer__term-inline-meta">
                <span>#{entry.id}</span>
              </span>
            ) : null}
            {rowMetadata === 'candidate' && entry.termIndexCandidateId != null ? (
              <span className="term-index-explorer__term-inline-meta">
                <span>#{entry.termIndexCandidateId}</span>
              </span>
            ) : null}
          </span>
          <span className="term-index-explorer__term-hit-count">
            {entry.occurrenceCount.toLocaleString()} hits
          </span>
          <span className="term-index-explorer__term-repository-count">
            {entry.repositoryCount.toLocaleString()} repos
          </span>
        </span>
        <span className="term-index-explorer__term-meta">
          <PillDropdown
            value={entry.reviewStatus}
            options={REVIEW_STATUS_OPTIONS}
            onChange={onUpdateReview}
            disabled={isUpdatingReview}
            ariaLabel={`Review status for ${entry.displayTerm}`}
            className="term-index-explorer__term-review-dropdown"
          />
        </span>
      </span>
    </div>
  );
}

function OccurrencePanel({
  entryId,
  occurrences,
  isLoading,
  error,
}: {
  entryId: number;
  occurrences: ApiTermIndexOccurrence[];
  isLoading: boolean;
  error: unknown;
}) {
  const occurrenceRefs = useRef(new Map<number, HTMLElement>());
  const [focusedOccurrenceId, setFocusedOccurrenceId] = useState<number | null>(null);
  const focusedOccurrenceIndex = occurrences.findIndex(
    (occurrence) => occurrence.id === focusedOccurrenceId,
  );
  const focusableOccurrenceId =
    focusedOccurrenceIndex >= 0 ? focusedOccurrenceId : (occurrences[0]?.id ?? null);

  useEffect(() => {
    setFocusedOccurrenceId(null);
  }, [entryId]);

  const focusOccurrenceAtIndex = (index: number) => {
    const nextOccurrence = occurrences[index];
    if (!nextOccurrence) {
      return;
    }
    setFocusedOccurrenceId(nextOccurrence.id);
    requestAnimationFrame(() => {
      const node = occurrenceRefs.current.get(nextOccurrence.id);
      node?.scrollIntoView({ block: 'nearest' });
      node?.focus();
    });
  };

  const handleExampleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    let nextIndex: number | null = null;
    const currentIndex = focusedOccurrenceIndex >= 0 ? focusedOccurrenceIndex : 0;

    if (event.key === 'ArrowDown') {
      nextIndex = Math.min(currentIndex + 1, occurrences.length - 1);
    } else if (event.key === 'ArrowUp') {
      nextIndex = Math.max(currentIndex - 1, 0);
    } else if (event.key === 'Home') {
      nextIndex = 0;
    } else if (event.key === 'End') {
      nextIndex = occurrences.length - 1;
    }

    if (nextIndex == null || occurrences.length === 0) {
      return;
    }

    event.preventDefault();
    focusOccurrenceAtIndex(nextIndex);
  };

  return (
    <div className="term-index-explorer__occurrence-panel">
      {isLoading ? (
        <div className="term-index-explorer__loading-spacer" aria-hidden="true" />
      ) : error ? (
        <p className="settings-hint is-error">{getErrorMessage(error)}</p>
      ) : occurrences.length === 0 ? (
        <p className="settings-hint">No examples match the current filters.</p>
      ) : (
        <div
          className="term-index-explorer__example-list"
          role="list"
          aria-label="Term examples"
          onKeyDown={handleExampleKeyDown}
        >
          {occurrences.map((occurrence) => (
            <OccurrenceExample
              key={occurrence.id}
              occurrence={occurrence}
              focusable={occurrence.id === focusableOccurrenceId}
              articleRef={(node) => {
                if (node) {
                  occurrenceRefs.current.set(occurrence.id, node);
                } else {
                  occurrenceRefs.current.delete(occurrence.id);
                }
              }}
              onFocus={() => setFocusedOccurrenceId(occurrence.id)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function OccurrenceExample({
  occurrence,
  focusable,
  articleRef,
  onFocus,
}: {
  occurrence: ApiTermIndexOccurrence;
  focusable: boolean;
  articleRef: (node: HTMLElement | null) => void;
  onFocus: () => void;
}) {
  return (
    <article
      ref={articleRef}
      className="term-index-explorer__example"
      role="listitem"
      tabIndex={focusable ? 0 : -1}
      onFocus={onFocus}
    >
      <div className="term-index-explorer__example-meta">
        <span>{occurrence.repositoryName}</span>
        {occurrence.assetPath ? <span>{occurrence.assetPath}</span> : null}
        <Link
          to={`/text-units/${occurrence.tmTextUnitId}`}
          aria-label={`Text unit ${occurrence.tmTextUnitId}`}
        >
          #{occurrence.tmTextUnitId}
        </Link>
      </div>
      <HighlightedSource occurrence={occurrence} />
      <div className="term-index-explorer__example-footer">
        <span>{formatMethod(occurrence.extractionMethod)}</span>
        <span>{occurrence.extractorId}</span>
        {occurrence.confidence == null ? null : <span>{occurrence.confidence}%</span>}
      </div>
    </article>
  );
}

function HighlightedSource({ occurrence }: { occurrence: ApiTermIndexOccurrence }) {
  const source = occurrence.sourceText ?? '';
  if (!source) {
    return <div className="term-index-explorer__source">No source text.</div>;
  }

  const startIndex = clamp(occurrence.startIndex, 0, source.length);
  const endIndex = clamp(occurrence.endIndex, startIndex, source.length);

  return (
    <div className="term-index-explorer__source">
      {source.slice(0, startIndex)}
      <mark>{source.slice(startIndex, endIndex) || occurrence.matchedText}</mark>
      {source.slice(endIndex)}
    </div>
  );
}

function formatCappedListLabel(label: string, visibleCount: number, limit: number) {
  if (visibleCount === 0) {
    return `No ${label}`;
  }
  const countLabel = visibleCount.toLocaleString();
  if (visibleCount >= limit) {
    return `Showing first ${countLabel} ${label}`;
  }
  return `Showing ${countLabel} ${label}`;
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}

function formatShortCount(value: number) {
  return value >= 1000 && value % 1000 === 0 ? `${value / 1000}k` : value.toLocaleString();
}

function formatMethod(method: string | null | undefined) {
  if (!method) {
    return 'All methods';
  }
  return method
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'Something went wrong.';
}
