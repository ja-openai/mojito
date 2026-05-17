import './settings-page.css';
import './admin-translation-incidents-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  type CSSProperties,
  type MouseEvent as ReactMouseEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { Link, Navigate, useSearchParams } from 'react-router-dom';

import {
  type ApiTranslationIncidentDetail,
  type ApiTranslationIncidentStatus,
  type ApiTranslationIncidentSummary,
  fetchTranslationIncident,
  fetchTranslationIncidents,
  rejectTranslationIncident,
  sendTranslationIncidentSlack,
  updateTranslationIncidentStatus,
} from '../../api/translation-incidents';
import { NumericPresetDropdown } from '../../components/NumericPresetDropdown';
import { useUser } from '../../components/RequireUser';
import { SearchControl } from '../../components/SearchControl';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import { getRowHeightPx } from '../../components/virtual/getRowHeightPx';
import { useMeasuredRowRefs } from '../../components/virtual/useMeasuredRowRefs';
import { useVirtualRows } from '../../components/virtual/useVirtualRows';
import { VirtualList } from '../../components/virtual/VirtualList';
import { useRepositories } from '../../hooks/useRepositories';

const STATUS_FILTERS: Array<{
  label: string;
  value: ApiTranslationIncidentStatus;
}> = [
  { label: 'Open', value: 'OPEN' },
  { label: 'Closed', value: 'CLOSED' },
];

const LIMIT_OPTIONS = [
  { label: '25', value: 25 },
  { label: '100', value: 100 },
  { label: '500', value: 500 },
  { label: '1000', value: 1000 },
] as const;

const DEFAULT_LIST_WIDTH_PCT = 42;
const MIN_LIST_WIDTH_PCT = 24;
const MAX_LIST_WIDTH_PCT = 72;
const COLLAPSE_THRESHOLD_PCT = 10;
const DEFAULT_LIMIT = 100;
const SEARCH_DEBOUNCE_MS = 250;

const Chevron = ({ direction }: { direction: 'left' | 'right' }) => (
  <svg
    className={`translation-incidents-page__chevron translation-incidents-page__chevron--${direction}`}
    viewBox="0 0 10 6"
    aria-hidden="true"
    focusable="false"
  >
    <path
      d="M1 1l4 4 4-4"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
    />
  </svg>
);

function formatDateTime(value: string | null) {
  if (!value) {
    return '—';
  }
  return new Intl.DateTimeFormat(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(value));
}

function formatLabel(value: string) {
  return value
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

function formatTranslationResolution(value: string) {
  switch (value) {
    case 'READY_TO_REJECT':
      return 'Ready to reject';
    case 'PENDING_REVIEW':
      return 'Needs review';
    case 'REJECT_FAILED':
      return 'Reject failed';
    default:
      return formatLabel(value);
  }
}

function renderMaybeLink(value: string | null) {
  if (!value) {
    return '—';
  }
  if (value.startsWith('http://') || value.startsWith('https://')) {
    return (
      <a href={value} target="_blank" rel="noreferrer" className="settings-table__link">
        {value}
      </a>
    );
  }
  return value;
}

function StatusBadge({ value }: { value: string }) {
  return <span className="translation-incidents-page__status">{formatLabel(value)}</span>;
}

function TranslationResolutionBadge({ value }: { value: string }) {
  return (
    <span className="translation-incidents-page__status">{formatTranslationResolution(value)}</span>
  );
}

function IncidentQueueRow({
  incident,
  showStatus,
}: {
  incident: ApiTranslationIncidentSummary;
  showStatus: boolean;
}) {
  return (
    <>
      <div className="translation-incidents-page__queue-cell translation-incidents-page__queue-cell--string">
        <div>{incident.stringId}</div>
        <div className="settings-hint">{incident.repositoryName ?? 'Unknown repo'}</div>
      </div>
      <div className="translation-incidents-page__queue-cell">
        {incident.resolvedLocale ?? incident.observedLocale}
      </div>
      <div className="translation-incidents-page__queue-cell">
        {showStatus ? (
          <StatusBadge value={incident.status} />
        ) : (
          <TranslationResolutionBadge value={incident.resolution} />
        )}
      </div>
      <div className="translation-incidents-page__queue-cell">
        <div>{incident.reviewProjectName ?? '—'}</div>
        <div className="settings-hint">{incident.reviewProjectConfidence ?? '—'}</div>
      </div>
      <div className="translation-incidents-page__queue-cell">
        {formatDateTime(incident.lastModifiedDate)}
      </div>
    </>
  );
}

export function AdminTranslationIncidentsPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const isPm = user.role === 'ROLE_PM';
  const queryClient = useQueryClient();
  const { data: repositories } = useRepositories();
  const [searchParams, setSearchParams] = useSearchParams();
  const [statusFilter, setStatusFilter] = useState<ApiTranslationIncidentStatus | null>('OPEN');
  const [incidentSearch, setIncidentSearch] = useState('');
  const [debouncedIncidentSearch, setDebouncedIncidentSearch] = useState('');
  const [createdAfter, setCreatedAfter] = useState('');
  const [createdBefore, setCreatedBefore] = useState('');
  const [limit, setLimit] = useState<number>(DEFAULT_LIMIT);
  const [selectedIncidentId, setSelectedIncidentId] = useState<number | null>(null);
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; message: string } | null>(null);
  const [listWidthPct, setListWidthPct] = useState(DEFAULT_LIST_WIDTH_PCT);
  const [lastListWidthPct, setLastListWidthPct] = useState(DEFAULT_LIST_WIDTH_PCT);
  const [isListCollapsed, setIsListCollapsed] = useState(false);
  const [isResizing, setIsResizing] = useState(false);
  const layoutRef = useRef<HTMLDivElement | null>(null);

  const requestedIncidentId = useMemo(() => {
    const incidentId = Number(searchParams.get('incidentId'));
    return Number.isFinite(incidentId) && incidentId > 0 ? incidentId : null;
  }, [searchParams]);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => {
      setDebouncedIncidentSearch(incidentSearch.trim());
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timeoutId);
  }, [incidentSearch]);

  useEffect(() => {
    setSelectedIncidentId(requestedIncidentId);
  }, [requestedIncidentId]);

  const updateSelectedIncident = useCallback(
    (incidentId: number | null) => {
      setSelectedIncidentId(incidentId);
      setSearchParams((current) => {
        const next = new URLSearchParams(current);
        if (incidentId == null) {
          next.delete('incidentId');
        } else {
          next.set('incidentId', String(incidentId));
        }
        return next;
      });
    },
    [setSearchParams],
  );

  const incidentsQuery = useQuery({
    queryKey: [
      'translation-incidents',
      statusFilter,
      debouncedIncidentSearch,
      createdAfter,
      createdBefore,
      limit,
    ],
    queryFn: () =>
      fetchTranslationIncidents({
        status: statusFilter,
        query: debouncedIncidentSearch || null,
        createdAfter: createdAfter || null,
        createdBefore: createdBefore || null,
        page: 0,
        size: limit,
      }),
  });

  const incidents = useMemo(() => incidentsQuery.data?.items ?? [], [incidentsQuery.data]);
  const rowsParentRef = useRef<HTMLDivElement>(null);
  const estimateRowHeight = useCallback(
    () =>
      getRowHeightPx({
        element: rowsParentRef.current,
        cssVariable: '--translation-incidents-row-height',
        defaultRem: 4.75,
      }),
    [],
  );
  const getItemKey = useCallback((index: number) => incidents[index]?.id ?? index, [incidents]);
  const {
    items: virtualItems,
    totalSize,
    measureElement,
    scrollToIndex,
  } = useVirtualRows<HTMLDivElement>({
    count: incidents.length,
    estimateSize: estimateRowHeight,
    getItemKey,
    getScrollElement: () => rowsParentRef.current,
    overscan: 8,
  });
  const { getRowRef } = useMeasuredRowRefs<number, HTMLDivElement>({ measureElement });

  useEffect(() => {
    if (requestedIncidentId !== null) {
      return;
    }
    if (incidents.length === 0) {
      if (selectedIncidentId !== null) {
        setSelectedIncidentId(null);
      }
      return;
    }
    if (
      selectedIncidentId === null ||
      !incidents.some((incident) => incident.id === selectedIncidentId)
    ) {
      updateSelectedIncident(incidents[0].id);
    }
  }, [incidents, requestedIncidentId, selectedIncidentId, updateSelectedIncident]);

  const attemptSelectIncident = useCallback(
    (nextId: number | null, nextIndex?: number) => {
      if (nextId == null || nextId === selectedIncidentId) {
        return;
      }
      updateSelectedIncident(nextId);
      if (nextIndex != null) {
        scrollToIndex(nextIndex, { align: 'center' });
      }
    },
    [scrollToIndex, selectedIncidentId, updateSelectedIncident],
  );

  const handleKeyNav = useCallback(
    (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (
        target &&
        (target.tagName === 'INPUT' ||
          target.tagName === 'TEXTAREA' ||
          target.tagName === 'SELECT' ||
          target.isContentEditable)
      ) {
        return;
      }

      if (!incidents.length) {
        return;
      }

      const currentIndex =
        selectedIncidentId == null
          ? -1
          : incidents.findIndex((incident) => incident.id === selectedIncidentId);

      if (event.key === 'ArrowDown' || event.key === 'j') {
        event.preventDefault();
        const nextIndex = Math.min(incidents.length - 1, currentIndex + 1);
        attemptSelectIncident(incidents[nextIndex]?.id ?? null, nextIndex);
      } else if (event.key === 'ArrowUp' || event.key === 'k') {
        event.preventDefault();
        const prevIndex = Math.max(0, currentIndex <= 0 ? 0 : currentIndex - 1);
        attemptSelectIncident(incidents[prevIndex]?.id ?? null, prevIndex);
      }
    },
    [attemptSelectIncident, incidents, selectedIncidentId],
  );

  useEffect(() => {
    window.addEventListener('keydown', handleKeyNav);
    return () => window.removeEventListener('keydown', handleKeyNav);
  }, [handleKeyNav]);

  const detailQuery = useQuery<ApiTranslationIncidentDetail>({
    queryKey: ['translation-incident', selectedIncidentId],
    queryFn: () => fetchTranslationIncident(selectedIncidentId!),
    enabled: selectedIncidentId !== null,
  });

  const clearSelectionIfFilteredOut = useCallback(
    (nextStatus: ApiTranslationIncidentStatus) => {
      if (statusFilter !== null && statusFilter !== nextStatus) {
        updateSelectedIncident(null);
      }
    },
    [statusFilter, updateSelectedIncident],
  );

  const rejectMutation = useMutation({
    mutationFn: ({ incidentId, comment }: { incidentId: number; comment: string | null }) =>
      rejectTranslationIncident(incidentId, comment),
    onSuccess: async (detail) => {
      clearSelectionIfFilteredOut(detail.status);
      setNotice({
        kind: 'success',
        message: `Rejected translation incident #${detail.id}.`,
      });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['translation-incidents'] }),
        queryClient.invalidateQueries({ queryKey: ['translation-incident', detail.id] }),
      ]);
    },
    onError: (error: Error) => {
      setNotice({
        kind: 'error',
        message: error.message || 'Reject failed.',
      });
    },
  });

  const statusMutation = useMutation({
    mutationFn: ({
      incidentId,
      status,
    }: {
      incidentId: number;
      status: ApiTranslationIncidentStatus;
    }) => updateTranslationIncidentStatus(incidentId, status),
    onSuccess: async (detail) => {
      clearSelectionIfFilteredOut(detail.status);
      setNotice({
        kind: 'success',
        message:
          detail.status === 'CLOSED'
            ? `Closed translation incident #${detail.id}.`
            : `Reopened translation incident #${detail.id}.`,
      });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['translation-incidents'] }),
        queryClient.invalidateQueries({ queryKey: ['translation-incident', detail.id] }),
      ]);
    },
    onError: (error: Error) => {
      setNotice({
        kind: 'error',
        message: error.message || 'Status update failed.',
      });
    },
  });

  const sendSlackMutation = useMutation({
    mutationFn: ({ incidentId }: { incidentId: number }) =>
      sendTranslationIncidentSlack(incidentId),
    onSuccess: async (detail) => {
      setNotice({
        kind: 'success',
        message: `Sent Slack notification for incident #${detail.id}.`,
      });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['translation-incidents'] }),
        queryClient.invalidateQueries({ queryKey: ['translation-incident', detail.id] }),
      ]);
    },
    onError: (error: Error) => {
      setNotice({
        kind: 'error',
        message: error.message || 'Slack send failed.',
      });
    },
  });

  const collapseList = useCallback(() => {
    setIsListCollapsed(true);
    setListWidthPct(0);
  }, []);

  const expandList = useCallback(() => {
    setIsListCollapsed(false);
    setListWidthPct(lastListWidthPct || DEFAULT_LIST_WIDTH_PCT);
  }, [lastListWidthPct]);

  const toggleList = useCallback(() => {
    if (isListCollapsed) {
      expandList();
      return;
    }
    if (listWidthPct > 0) {
      setLastListWidthPct(listWidthPct);
    }
    collapseList();
  }, [collapseList, expandList, isListCollapsed, listWidthPct]);

  const startResize = useCallback(
    (event: ReactMouseEvent<HTMLDivElement>) => {
      event.preventDefault();
      setIsResizing(true);

      const onMove = (moveEvent: MouseEvent) => {
        if (!layoutRef.current) {
          return;
        }
        const rect = layoutRef.current.getBoundingClientRect();
        const x = moveEvent.clientX - rect.left;
        const pct = Math.min(MAX_LIST_WIDTH_PCT, Math.max(0, (x / rect.width) * 100));
        if (pct <= COLLAPSE_THRESHOLD_PCT) {
          if (listWidthPct > 0) {
            setLastListWidthPct(listWidthPct);
          }
          collapseList();
          return;
        }
        const nextPct = Math.max(MIN_LIST_WIDTH_PCT, pct);
        if (isListCollapsed) {
          setIsListCollapsed(false);
        }
        setLastListWidthPct(nextPct);
        setListWidthPct(nextPct);
      };

      const onUp = () => {
        setIsResizing(false);
        window.removeEventListener('mousemove', onMove);
        window.removeEventListener('mouseup', onUp);
      };

      window.addEventListener('mousemove', onMove);
      window.addEventListener('mouseup', onUp);
    },
    [collapseList, isListCollapsed, listWidthPct],
  );

  if (!isAdmin && !isPm) {
    return <Navigate to="/settings/me" replace />;
  }

  const detail = detailQuery.data ?? null;
  const isRejecting = rejectMutation.isPending;
  const isUpdatingStatus = statusMutation.isPending;
  const isSendingSlack = sendSlackMutation.isPending;
  const layoutStyle = {
    '--translation-incidents-list-width': `${listWidthPct}%`,
  } as CSSProperties;
  const detailLocale = detail ? (detail.resolvedLocale ?? detail.observedLocale) : null;
  const detailRepository =
    detail?.repositoryName ??
    detail?.lookupCandidates.find(
      (candidate) =>
        candidate.tmTextUnitId != null && candidate.tmTextUnitId === detail.selectedTmTextUnitId,
    )?.repositoryName ??
    (detail?.lookupCandidates.length === 1 ? detail.lookupCandidates[0]?.repositoryName : null) ??
    null;
  const workbenchRepositoryId =
    detailRepository == null
      ? null
      : (repositories?.find((repository) => repository.name === detailRepository)?.id ?? null);
  const detailClosedLabel = detail
    ? detail.closedAt
      ? `${formatDateTime(detail.closedAt)} · ${detail.closedByUsername ?? '—'}`
      : 'Not closed'
    : null;
  const totalMatchingIncidents = incidentsQuery.data?.totalElements ?? incidents.length;
  const countLabel = `Showing ${incidents.length} of ${totalMatchingIncidents} incident${
    totalMatchingIncidents === 1 ? '' : 's'
  }`;
  const showStatusInList = statusFilter == null;

  const queueSizeControl = (label: string) => (
    <NumericPresetDropdown
      value={limit}
      buttonLabel={label}
      menuLabel="Queue size"
      presetOptions={[...LIMIT_OPTIONS]}
      onChange={(next) => {
        setLimit(next);
        setNotice(null);
      }}
      ariaLabel="Choose incident queue size"
      className="translation-incidents-page__count"
      buttonClassName="translation-incidents-page__count-button"
      panelClassName="translation-incidents-page__count-panel"
      pillsClassName="settings-pills translation-incidents-page__count-pills"
      optionClassName="settings-pill"
      optionActiveClassName="is-active"
      customActiveClassName="is-active"
      customButtonClassName="settings-pill"
      customInitialValue={100}
    />
  );

  const handleReject = () => {
    if (!detail?.canReject || detail.status === 'CLOSED') {
      return;
    }
    const operatorComment = window.prompt(
      'Optional operator note for the rejection audit trail:',
      '',
    );
    const confirmed = window.confirm(
      `Reject the current Mojito translation for ${detail.stringId} (${detail.resolvedLocale ?? detail.observedLocale})?`,
    );
    if (!confirmed) {
      return;
    }
    setNotice(null);
    rejectMutation.mutate({ incidentId: detail.id, comment: operatorComment?.trim() || null });
  };

  const handleStatusChange = (nextStatus: ApiTranslationIncidentStatus | null) => {
    if (!detail || nextStatus == null || nextStatus === detail.status) {
      return;
    }
    setNotice(null);
    statusMutation.mutate({ incidentId: detail.id, status: nextStatus });
  };

  const handleSendSlack = () => {
    if (!detail || !detail.slackDraft || detail.slackCanSend !== true) {
      return;
    }
    setNotice(null);
    sendSlackMutation.mutate({ incidentId: detail.id });
  };

  const workbenchLocale = detail ? (detail.resolvedLocale ?? detail.observedLocale) : null;

  return (
    <div className="settings-page settings-page--wide translation-incidents-page">
      <div className="translation-incidents-page__bar">
        <div
          className="translation-incidents-page__filters"
          role="group"
          aria-label="Filter translation incidents"
        >
          <SearchControl
            value={incidentSearch}
            onChange={(value) => {
              setIncidentSearch(value);
              setNotice(null);
            }}
            placeholder="Search incident, string, repo, locale, or project"
            className="translation-incidents-page__search"
            inputAriaLabel="Search translation incidents"
          />
          <SingleSelectDropdown
            label="Status"
            options={STATUS_FILTERS}
            value={statusFilter}
            onChange={(next) => {
              setStatusFilter(next);
              setNotice(null);
            }}
            noneLabel="All statuses"
            placeholder="All statuses"
            searchable={false}
            className="translation-incidents-page__workflow-filter"
            buttonAriaLabel="Filter translation incidents by status"
          />
          <label className="translation-incidents-page__date-filter">
            <span>After</span>
            <input
              type="date"
              value={createdAfter}
              onChange={(event) => {
                setCreatedAfter(event.target.value);
                setNotice(null);
              }}
              aria-label="Created after"
            />
          </label>
          <label className="translation-incidents-page__date-filter">
            <span>Before</span>
            <input
              type="date"
              value={createdBefore}
              onChange={(event) => {
                setCreatedBefore(event.target.value);
                setNotice(null);
              }}
              aria-label="Created before"
            />
          </label>
        </div>
      </div>

      {notice ? (
        <p
          className={`translation-incidents-page__notice settings-hint${
            notice.kind === 'error' ? ' is-error' : ''
          }`}
        >
          {notice.message}
        </p>
      ) : null}

      <div className="translation-incidents-page__summary-bar">
        <div className="translation-incidents-page__summary-bar-center">
          {queueSizeControl(countLabel)}
        </div>
      </div>

      <div
        className={`translation-incidents-page__content${
          isListCollapsed ? ' translation-incidents-page__content--collapsed' : ''
        }`}
        ref={layoutRef}
        style={layoutStyle}
      >
        <section
          className={`translation-incidents-page__pane translation-incidents-page__list-pane${
            isListCollapsed ? ' translation-incidents-page__list-pane--collapsed' : ''
          }`}
        >
          {incidentsQuery.isLoading ? (
            <p className="translation-incidents-page__pane-placeholder settings-page__hint">
              Loading incidents…
            </p>
          ) : incidentsQuery.isError ? (
            <p className="translation-incidents-page__pane-placeholder settings-hint is-error">
              {incidentsQuery.error.message || 'Failed to load incidents.'}
            </p>
          ) : incidents.length === 0 ? (
            <p className="translation-incidents-page__pane-placeholder settings-page__hint">
              No translation incidents match this view.
            </p>
          ) : (
            <div className="translation-incidents-page__list-scroll">
              <div className="translation-incidents-page__queue-header" aria-hidden="true">
                <div className="translation-incidents-page__queue-cell translation-incidents-page__queue-cell--string">
                  String
                </div>
                <div className="translation-incidents-page__queue-cell">Locale</div>
                <div className="translation-incidents-page__queue-cell">
                  {showStatusInList ? 'Status' : 'Translation'}
                </div>
                <div className="translation-incidents-page__queue-cell">Review project</div>
                <div className="translation-incidents-page__queue-cell">Updated</div>
              </div>
              <VirtualList
                scrollRef={rowsParentRef}
                items={virtualItems}
                totalSize={totalSize}
                renderRow={(virtualItem) => {
                  const incident = incidents[virtualItem.index];
                  if (!incident) {
                    return null;
                  }
                  return {
                    key: incident.id,
                    props: {
                      ref: getRowRef(incident.id),
                      onClick: () => {
                        attemptSelectIncident(incident.id, virtualItem.index);
                        setNotice(null);
                      },
                      className: `translation-incidents-page__queue-row${
                        incident.id === selectedIncidentId ? ' is-selected' : ''
                      }`,
                    },
                    content: <IncidentQueueRow incident={incident} showStatus={showStatusInList} />,
                  };
                }}
              />
            </div>
          )}
        </section>

        <div
          className={`translation-incidents-page__resize-handle${
            isResizing ? ' is-resizing' : ''
          }${isListCollapsed ? ' translation-incidents-page__resize-handle--collapsed' : ''}`}
          onMouseDown={startResize}
          role="separator"
          aria-label={isListCollapsed ? 'Expand incident queue' : 'Collapse incident queue'}
          aria-orientation="vertical"
          aria-expanded={!isListCollapsed}
        >
          <button
            type="button"
            className="translation-incidents-page__handle-button"
            onClick={toggleList}
            onMouseDown={(event) => event.stopPropagation()}
            aria-label={isListCollapsed ? 'Expand incident queue' : 'Collapse incident queue'}
            title={isListCollapsed ? 'Expand incident queue' : 'Collapse incident queue'}
          >
            <Chevron direction={isListCollapsed ? 'right' : 'left'} />
          </button>
        </div>

        <section className="translation-incidents-page__pane translation-incidents-page__detail-pane">
          <div className="translation-incidents-page__detail-scroll">
            {detailQuery.isLoading ? (
              <p className="translation-incidents-page__pane-placeholder settings-page__hint">
                Loading incident detail…
              </p>
            ) : detailQuery.isError ? (
              <p className="translation-incidents-page__pane-placeholder settings-hint is-error">
                {detailQuery.error.message || 'Failed to load incident detail.'}
              </p>
            ) : detail ? (
              <div className="translation-incidents-page__detail-grid">
                <section className="translation-incidents-page__summary">
                  <div className="translation-incidents-page__summary-main">
                    <div className="translation-incidents-page__summary-eyebrow">
                      {detail.incidentLink ? (
                        <a
                          href={detail.incidentLink}
                          target="_blank"
                          rel="noreferrer"
                          className="settings-table__link"
                        >
                          Incident #{detail.id}
                        </a>
                      ) : (
                        `Incident #${detail.id}`
                      )}
                    </div>
                    <h2 className="translation-incidents-page__summary-title">{detail.stringId}</h2>
                    <p className="translation-incidents-page__summary-subtitle">
                      {detailLocale ?? '—'}
                      {detailRepository ? ` · ${detailRepository}` : ''}
                    </p>
                  </div>
                  <dl className="translation-incidents-page__summary-facts translation-incidents-page__summary-facts--state">
                    <div className="translation-incidents-page__detail-item">
                      <dt>Status</dt>
                      <dd>
                        <SingleSelectDropdown
                          label="Status"
                          options={STATUS_FILTERS}
                          value={detail.status}
                          onChange={handleStatusChange}
                          searchable={false}
                          disabled={isUpdatingStatus}
                          className="translation-incidents-page__detail-status-dropdown"
                          buttonAriaLabel="Change incident status"
                        />
                      </dd>
                    </div>
                    <div className="translation-incidents-page__detail-item">
                      <dt>Translation</dt>
                      <dd>{formatTranslationResolution(detail.resolution)}</dd>
                    </div>
                    <div className="translation-incidents-page__detail-item">
                      <dt>Last closed</dt>
                      <dd>{detailClosedLabel}</dd>
                    </div>
                    <div className="translation-incidents-page__detail-item">
                      <dt>Created</dt>
                      <dd>{formatDateTime(detail.createdDate)}</dd>
                    </div>
                  </dl>
                </section>

                <div className="translation-incidents-page__actions">
                  {detail.selectedTmTextUnitId != null ? (
                    <Link
                      to={{
                        pathname: '/workbench',
                        search: `?tmTextUnitId=${encodeURIComponent(
                          String(detail.selectedTmTextUnitId),
                        )}${workbenchLocale ? `&locale=${encodeURIComponent(workbenchLocale)}` : ''}${
                          workbenchRepositoryId != null
                            ? `&repo=${encodeURIComponent(String(workbenchRepositoryId))}`
                            : ''
                        }`,
                      }}
                      state={{
                        workbenchSearch: {
                          searchAttribute: 'tmTextUnitIds',
                          searchType: 'exact',
                          searchText: String(detail.selectedTmTextUnitId),
                          localeTags: workbenchLocale ? [workbenchLocale] : [],
                          repositoryIds:
                            workbenchRepositoryId != null ? [workbenchRepositoryId] : [],
                        },
                      }}
                      className="settings-button settings-button--ghost"
                      title="Open this string in Workbench"
                    >
                      Open in Workbench
                    </Link>
                  ) : null}
                  <button
                    type="button"
                    className="settings-button settings-button--ghost"
                    onClick={handleSendSlack}
                    disabled={!detail.slackDraft || detail.slackCanSend !== true || isSendingSlack}
                  >
                    {isSendingSlack ? 'Sending…' : 'Send Slack'}
                  </button>
                  <button
                    type="button"
                    className="settings-button settings-button--primary"
                    onClick={handleReject}
                    disabled={
                      !detail.canReject ||
                      isRejecting ||
                      detail.resolution === 'REJECTED' ||
                      detail.status === 'CLOSED'
                    }
                  >
                    {isRejecting ? 'Rejecting…' : 'Reject translation'}
                  </button>
                </div>
                <p className="settings-hint translation-incidents-page__action-note">
                  {detail.slackCanSend === true
                    ? detail.slackThreadTs
                      ? 'Slack replies in the existing review thread.'
                      : (detail.slackNote ??
                        'Slack posts the stored draft to the configured destination.')
                    : (detail.slackNote ?? 'Slack send is unavailable for this incident.')}
                </p>

                <section className="translation-incidents-page__detail-section translation-incidents-page__detail-section--context">
                  <dl className="translation-incidents-page__detail-list">
                    <div className="translation-incidents-page__detail-item">
                      <dt>Review project</dt>
                      <dd>
                        {detail.reviewProjectLink ? (
                          <a
                            href={detail.reviewProjectLink}
                            target="_blank"
                            rel="noreferrer"
                            className="settings-table__link"
                          >
                            {detail.reviewProjectName ?? detail.reviewProjectLink}
                          </a>
                        ) : (
                          (detail.reviewProjectName ?? '—')
                        )}
                      </dd>
                    </div>
                    <div className="translation-incidents-page__detail-item">
                      <dt>Repository</dt>
                      <dd>{detail.repositoryName ?? '—'}</dd>
                    </div>
                    <div className="translation-incidents-page__detail-item">
                      <dt>Observed locale</dt>
                      <dd>{detail.observedLocale}</dd>
                    </div>
                    <div className="translation-incidents-page__detail-item">
                      <dt>Resolved locale</dt>
                      <dd>{detail.resolvedLocale ?? '—'}</dd>
                    </div>
                    <div className="translation-incidents-page__detail-item">
                      <dt>Lookup result</dt>
                      <dd>
                        {detail.lookupResolutionStatus} · {detail.localeResolutionStrategy}
                        {detail.localeUsedFallback ? ' fallback' : ''}
                      </dd>
                    </div>
                    <div className="translation-incidents-page__detail-item">
                      <dt>Text unit</dt>
                      <dd>
                        {detail.selectedTextUnitLink ? (
                          <a
                            href={detail.selectedTextUnitLink}
                            target="_blank"
                            rel="noreferrer"
                            className="settings-table__link"
                          >
                            {detail.selectedTmTextUnitId}
                          </a>
                        ) : (
                          (detail.selectedTmTextUnitId ?? '—')
                        )}
                      </dd>
                    </div>
                    <div className="translation-incidents-page__detail-item">
                      <dt>Source reference</dt>
                      <dd>{renderMaybeLink(detail.sourceReference)}</dd>
                    </div>
                  </dl>
                  <dl className="translation-incidents-page__detail-list translation-incidents-page__detail-list--people">
                    <div className="translation-incidents-page__detail-item">
                      <dt>Author</dt>
                      <dd>{detail.translationAuthorUsername ?? '—'}</dd>
                    </div>
                    <div className="translation-incidents-page__detail-item">
                      <dt>Reviewer</dt>
                      <dd>{detail.reviewerUsername ?? '—'}</dd>
                    </div>
                    <div className="translation-incidents-page__detail-item">
                      <dt>Owner</dt>
                      <dd>{detail.ownerUsername ?? '—'}</dd>
                    </div>
                  </dl>
                </section>

                <section className="translation-incidents-page__message-grid">
                  <div className="translation-incidents-page__message-block translation-incidents-page__message-block--wide">
                    <h3>Reason</h3>
                    <p className="translation-incidents-page__code">{detail.reason}</p>
                  </div>
                  <div className="translation-incidents-page__message-block">
                    <h3>Source message</h3>
                    <p className="translation-incidents-page__code translation-incidents-page__code--translation">
                      {detail.selectedSource ?? '—'}
                    </p>
                  </div>
                  <div className="translation-incidents-page__message-block translation-incidents-page__message-block--snapshot">
                    <div className="translation-incidents-page__message-heading">
                      <h3>Captured translation</h3>
                      <p className="settings-hint">Stored when the incident was created.</p>
                    </div>
                    <p className="translation-incidents-page__code translation-incidents-page__code--translation">
                      {detail.selectedTarget ?? '—'}
                    </p>
                  </div>
                </section>

                {detail.lookupCandidates.length > 0 ? (
                  <details className="translation-incidents-page__detail-section translation-incidents-page__disclosure">
                    <summary className="translation-incidents-page__disclosure-summary">
                      <span>Lookup candidates</span>
                      <span className="settings-hint">
                        {detail.lookupCandidates.length} candidate
                        {detail.lookupCandidates.length === 1 ? '' : 's'}
                      </span>
                    </summary>
                    <div className="settings-table-wrapper">
                      <table className="settings-table">
                        <thead>
                          <tr>
                            <th>Repository</th>
                            <th>Text unit</th>
                            <th>Asset</th>
                            <th>Status</th>
                            <th>Reject</th>
                          </tr>
                        </thead>
                        <tbody>
                          {detail.lookupCandidates.map((candidate) => (
                            <tr
                              key={`${candidate.tmTextUnitId ?? 'missing'}:${candidate.tmTextUnitVariantId ?? 'missing'}`}
                            >
                              <td>{candidate.repositoryName ?? '—'}</td>
                              <td>
                                {candidate.textUnitLink ? (
                                  <a
                                    href={candidate.textUnitLink}
                                    target="_blank"
                                    rel="noreferrer"
                                    className="settings-table__link"
                                  >
                                    {candidate.tmTextUnitId ?? '—'}
                                  </a>
                                ) : (
                                  (candidate.tmTextUnitId ?? '—')
                                )}
                              </td>
                              <td>{candidate.assetPath ?? '—'}</td>
                              <td>{candidate.status ?? '—'}</td>
                              <td>{candidate.canReject ? 'Yes' : 'No'}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </details>
                ) : null}

                {detail.reviewProjectCandidates.length > 0 ? (
                  <details className="translation-incidents-page__detail-section translation-incidents-page__disclosure">
                    <summary className="translation-incidents-page__disclosure-summary">
                      <span>Related reviews</span>
                      <span className="settings-hint">
                        {detail.reviewProjectCandidates.length} match
                        {detail.reviewProjectCandidates.length === 1 ? '' : 'es'}
                      </span>
                    </summary>
                    <div className="settings-table-wrapper">
                      <table className="settings-table">
                        <thead>
                          <tr>
                            <th>Project</th>
                            <th>Confidence</th>
                            <th>Reviewer</th>
                            <th>Owner</th>
                          </tr>
                        </thead>
                        <tbody>
                          {detail.reviewProjectCandidates.map((candidate) => (
                            <tr
                              key={
                                candidate.reviewProjectId ??
                                candidate.reviewProjectName ??
                                'unknown'
                              }
                            >
                              <td>
                                {candidate.reviewProjectLink ? (
                                  <a
                                    href={candidate.reviewProjectLink}
                                    target="_blank"
                                    rel="noreferrer"
                                    className="settings-table__link"
                                  >
                                    {candidate.reviewProjectName ?? candidate.reviewProjectLink}
                                  </a>
                                ) : (
                                  (candidate.reviewProjectName ?? '—')
                                )}
                              </td>
                              <td>{candidate.confidence ?? '—'}</td>
                              <td>{candidate.reviewerUsername ?? '—'}</td>
                              <td>{candidate.ownerUsername ?? '—'}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </details>
                ) : null}

                {detail.rejectAuditComment ? (
                  <details className="translation-incidents-page__detail-section translation-incidents-page__disclosure">
                    <summary className="translation-incidents-page__disclosure-summary">
                      <span>Reject audit</span>
                    </summary>
                    <p className="translation-incidents-page__pre">{detail.rejectAuditComment}</p>
                  </details>
                ) : null}

                {detail.slackDraft ? (
                  <details className="translation-incidents-page__detail-section translation-incidents-page__disclosure">
                    <summary className="translation-incidents-page__disclosure-summary">
                      <span>Slack draft</span>
                      <span className="settings-hint">
                        Generated from the stored incident snapshot.
                      </span>
                    </summary>
                    <p className="translation-incidents-page__pre">{detail.slackDraft}</p>
                  </details>
                ) : null}
              </div>
            ) : (
              <p className="translation-incidents-page__pane-placeholder settings-page__hint">
                Select an incident to inspect the stored context.
              </p>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
