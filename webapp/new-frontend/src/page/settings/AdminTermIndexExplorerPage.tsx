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
import { Link, Navigate } from 'react-router-dom';

import {
  type ApiTermIndexEntry,
  type ApiTermIndexOccurrence,
  fetchTermIndexEntries,
  fetchTermIndexOccurrences,
  fetchTermIndexStatus,
  startTermIndexRefresh,
  waitForTermIndexRefreshTask,
} from '../../api/term-index';
import { Modal } from '../../components/Modal';
import type { MultiSelectCustomAction } from '../../components/MultiSelectChip';
import { NumericPresetDropdown } from '../../components/NumericPresetDropdown';
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
const EXAMPLE_RESULT_LIMIT = 500;
const DEFAULT_BATCH_SIZE = 1000;

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
  const [termResultLimit, setTermResultLimit] = useState(TERM_RESULT_LIMIT_DEFAULT);
  const [selectedEntryId, setSelectedEntryId] = useState<number | null>(null);
  const [termPaneWidthPct, setTermPaneWidthPct] = useState(50);
  const [isPaneResizing, setIsPaneResizing] = useState(false);
  const workspaceRef = useRef<HTMLDivElement>(null);

  const resetTermSelection = () => {
    setSelectedEntryId(null);
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
      effectiveRepositoryIds,
      searchQuery,
      extractionMethod,
      minOccurrences,
      termResultLimit,
    ],
    queryFn: () =>
      fetchTermIndexEntries({
        repositoryIds: effectiveRepositoryIds,
        search: searchQuery,
        extractionMethod,
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

  useEffect(() => {
    if (entries.length === 0) {
      setSelectedEntryId(null);
      return;
    }
    if (selectedEntryId == null || !entries.some((entry) => entry.id === selectedEntryId)) {
      setSelectedEntryId(entries[0].id);
    }
  }, [entries, selectedEntryId]);

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
            <span className="settings-hint">{termCountLabel}</span>
          </div>
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
                  onSelectEntry={setSelectedEntryId}
                />
              )}
            </div>
            <div
              className={`term-index-explorer__pane-divider${isPaneResizing ? ' is-resizing' : ''}`}
              onMouseDown={startPaneResize}
              onDoubleClick={() => updateTermPaneWidthPct(50)}
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
                <OccurrencePanel
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

function TermIndexSubnav({ active }: { active: 'runs' | 'terms' }) {
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
  onSelectEntry,
}: {
  entries: ApiTermIndexEntry[];
  selectedEntryId: number | null;
  onSelectEntry: (entryId: number) => void;
}) {
  const termRowRefs = useRef(new Map<number, HTMLButtonElement>());
  const selectedIndex = entries.findIndex((entry) => entry.id === selectedEntryId);
  const estimateSize = useCallback(() => 64, []);
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

  const selectEntryAtIndex = (index: number) => {
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
    let nextIndex: number | null = null;

    if (event.key === 'ArrowDown') {
      nextIndex = Math.min(selectedIndex < 0 ? 0 : selectedIndex + 1, entries.length - 1);
    } else if (event.key === 'ArrowUp') {
      nextIndex = Math.max(selectedIndex < 0 ? 0 : selectedIndex - 1, 0);
    } else if (event.key === 'Home') {
      nextIndex = 0;
    } else if (event.key === 'End') {
      nextIndex = entries.length - 1;
    }

    if (nextIndex == null) {
      return;
    }

    event.preventDefault();
    selectEntryAtIndex(nextIndex);
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
                selected={entry.id === selectedEntryId}
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
  selected,
  focusable,
  rowRef,
  onSelect,
}: {
  entry: ApiTermIndexEntry;
  selected: boolean;
  focusable: boolean;
  rowRef: (node: HTMLButtonElement | null) => void;
  onSelect: () => void;
}) {
  return (
    <button
      ref={rowRef}
      type="button"
      role="option"
      aria-selected={selected}
      tabIndex={focusable ? 0 : -1}
      className={`term-index-explorer__term-row${selected ? ' is-selected' : ''}`}
      onClick={onSelect}
    >
      <span className="term-index-explorer__term-main">
        <span className="term-index-explorer__term-name">{entry.displayTerm}</span>
        <span className="term-index-explorer__term-key">{entry.normalizedKey}</span>
      </span>
      <span className="term-index-explorer__term-meta">
        {entry.occurrenceCount} hits · {entry.repositoryCount} repos · {entry.sourceLocaleTag}
      </span>
    </button>
  );
}

function OccurrencePanel({
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
  const occurrenceRefs = useRef(new Map<number, HTMLElement>());
  const [focusedOccurrenceId, setFocusedOccurrenceId] = useState<number | null>(null);
  const focusedOccurrenceIndex = occurrences.findIndex(
    (occurrence) => occurrence.id === focusedOccurrenceId,
  );
  const focusableOccurrenceId =
    focusedOccurrenceIndex >= 0 ? focusedOccurrenceId : (occurrences[0]?.id ?? null);

  useEffect(() => {
    setFocusedOccurrenceId(null);
  }, [entry.id]);

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
      <div className="term-index-explorer__occurrence-header">
        <div>
          <div className="term-index-explorer__occurrence-title">{entry.displayTerm}</div>
          <div className="settings-hint">
            {entry.occurrenceCount} hits across {entry.repositoryCount} repositories
          </div>
        </div>
      </div>
      <div className="term-index-explorer__subbar term-index-explorer__subbar--compact">
        <span className="settings-hint">
          {isLoading
            ? 'Loading examples...'
            : formatCappedListLabel('examples', occurrences.length, EXAMPLE_RESULT_LIMIT)}
        </span>
      </div>
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
