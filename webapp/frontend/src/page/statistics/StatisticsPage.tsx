import '../settings/settings-page.css';
import './statistics-page.css';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';

import {
  fetchTextUnitIngestionSnapshot,
  type IngestionGroupBy,
  recomputeTextUnitIngestion,
  type TextUnitIngestionPoint,
  type TextUnitIngestionRecomputeResult,
  type TextUnitIngestionSnapshot,
} from '../../api/monitoring';
import type { TextUnitSearchRequest } from '../../api/text-units';
import { useUser } from '../../components/RequireUser';
import { useRepositories } from '../../hooks/useRepositories';

type StatisticsViewMode = 'all' | 'repository';
type StatisticsMetric = 'textUnits' | 'words';

const GROUP_BY_OPTIONS: { value: IngestionGroupBy; label: string }[] = [
  { value: 'day', label: 'Day' },
  { value: 'month', label: 'Month' },
  { value: 'year', label: 'Year' },
];

const VIEW_MODE_OPTIONS: { value: StatisticsViewMode; label: string }[] = [
  { value: 'all', label: 'All repositories' },
  { value: 'repository', label: 'Grouped by repository' },
];

const METRIC_OPTIONS: { value: StatisticsMetric; label: string }[] = [
  { value: 'textUnits', label: 'Text unit count' },
  { value: 'words', label: 'Word count' },
];

function getErrorMessage(error: unknown) {
  if (error instanceof Error && error.message.trim()) {
    return error.message;
  }
  return 'Request failed.';
}

function formatNullableDate(value: string | null) {
  if (!value) {
    return '-';
  }
  return value;
}

function formatNullableDateTime(value: string | null) {
  if (!value) {
    return '-';
  }

  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }

  return parsed.toLocaleString();
}

function toGroupBy(value: string): IngestionGroupBy {
  if (value === 'month' || value === 'year') {
    return value;
  }
  return 'day';
}

function toViewMode(value: string): StatisticsViewMode {
  if (value === 'repository') {
    return 'repository';
  }
  return 'all';
}

function sanitizeTsvCell(value: string | number | null | undefined) {
  return String(value ?? '')
    .replace(/\t/g, ' ')
    .replace(/\r?\n/g, ' ')
    .trim();
}

function formatUtcIso(date: Date) {
  return date.toISOString();
}

function getPeriodDateRange(period: string, groupBy: IngestionGroupBy) {
  let start: Date;
  let endExclusive: Date;

  if (groupBy === 'day') {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(period);
    if (!match) {
      return null;
    }
    const year = Number(match[1]);
    const month = Number(match[2]) - 1;
    const day = Number(match[3]);
    start = new Date(Date.UTC(year, month, day));
    endExclusive = new Date(Date.UTC(year, month, day + 1));
  } else if (groupBy === 'month') {
    const match = /^(\d{4})-(\d{2})$/.exec(period);
    if (!match) {
      return null;
    }
    const year = Number(match[1]);
    const month = Number(match[2]) - 1;
    start = new Date(Date.UTC(year, month, 1));
    endExclusive = new Date(Date.UTC(year, month + 1, 1));
  } else {
    const match = /^(\d{4})$/.exec(period);
    if (!match) {
      return null;
    }
    const year = Number(match[1]);
    start = new Date(Date.UTC(year, 0, 1));
    endExclusive = new Date(Date.UTC(year + 1, 0, 1));
  }

  if (Number.isNaN(start.getTime()) || Number.isNaN(endExclusive.getTime())) {
    return null;
  }

  return {
    after: formatUtcIso(start),
    before: formatUtcIso(new Date(endExclusive.getTime() - 1)),
  };
}

export function StatisticsPage() {
  const user = useUser();
  const { data: repositories = [] } = useRepositories();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const canViewStatistics = isAdmin;

  const [groupBy, setGroupBy] = useState<IngestionGroupBy>('day');
  const [viewMode, setViewMode] = useState<StatisticsViewMode>('all');
  const [metric, setMetric] = useState<StatisticsMetric>('textUnits');
  const [fromDayInput, setFromDayInput] = useState('');
  const [toDayInput, setToDayInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [recomputing, setRecomputing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copyFeedback, setCopyFeedback] = useState<string | null>(null);
  const [snapshot, setSnapshot] = useState<TextUnitIngestionSnapshot | null>(null);
  const [recomputeResult, setRecomputeResult] = useState<TextUnitIngestionRecomputeResult | null>(
    null,
  );

  const numberFormatter = useMemo(() => new Intl.NumberFormat(), []);
  const isGroupedByRepository = viewMode === 'repository';
  const rows = useMemo(() => {
    const nextRows = [...(snapshot?.rows ?? [])];
    nextRows.sort((left, right) => {
      const periodCompare = right.period.localeCompare(left.period);
      if (periodCompare !== 0) {
        return periodCompare;
      }
      const leftRepository = left.repositoryName ?? '';
      const rightRepository = right.repositoryName ?? '';
      return leftRepository.localeCompare(rightRepository);
    });
    return nextRows;
  }, [snapshot?.rows]);
  const metricLabel = metric === 'words' ? 'Words added' : 'Text units added';

  const buildWorkbenchState = useCallback(
    (row: TextUnitIngestionPoint) => {
      const dateRange = getPeriodDateRange(row.period, groupBy);
      if (!dateRange) {
        return null;
      }

      const repositoryIds =
        row.repositoryId != null
          ? [row.repositoryId]
          : repositories.map((repository) => repository.id);
      if (!repositoryIds.length) {
        return null;
      }

      const workbenchSearch: TextUnitSearchRequest = {
        repositoryIds,
        localeTags: [],
        tmTextUnitCreatedAfter: dateRange.after,
        tmTextUnitCreatedBefore: dateRange.before,
        limit: Math.max(1, row.stringCount),
        offset: 0,
      };

      return {
        workbenchSearch,
        localePrompt: true,
      };
    },
    [groupBy, repositories],
  );

  const loadSnapshot = useCallback(async () => {
    if (!canViewStatistics) {
      return;
    }

    if (fromDayInput && toDayInput && fromDayInput > toDayInput) {
      setError('Invalid date range. From date must be before or equal to To date.');
      return;
    }

    setLoading(true);
    setError(null);
    setCopyFeedback(null);
    try {
      const nextSnapshot = await fetchTextUnitIngestionSnapshot({
        groupBy,
        groupByRepository: isGroupedByRepository,
        fromDay: fromDayInput || null,
        toDay: toDayInput || null,
      });
      setSnapshot(nextSnapshot);
    } catch (loadError) {
      setError(getErrorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }, [canViewStatistics, fromDayInput, groupBy, isGroupedByRepository, toDayInput]);

  const handleRecompute = useCallback(async () => {
    if (!isAdmin) {
      return;
    }

    setRecomputing(true);
    setError(null);
    try {
      const nextResult = await recomputeTextUnitIngestion();
      setRecomputeResult(nextResult);
      await loadSnapshot();
    } catch (recomputeError) {
      setError(getErrorMessage(recomputeError));
    } finally {
      setRecomputing(false);
    }
  }, [isAdmin, loadSnapshot]);

  const handleCopyTable = useCallback(async () => {
    if (rows.length === 0) {
      return;
    }

    const header = isGroupedByRepository
      ? ['Repository', 'Period', metricLabel]
      : ['Period', metricLabel];

    const lines = rows.map((row) => {
      const repositoryLabel =
        row.repositoryName?.trim() ||
        (row.repositoryId != null ? `Repository #${row.repositoryId}` : 'All repositories');

      const values = isGroupedByRepository
        ? [repositoryLabel, row.period, metric === 'words' ? row.wordCount : row.stringCount]
        : [row.period, metric === 'words' ? row.wordCount : row.stringCount];

      return values.map((value) => sanitizeTsvCell(value)).join('\t');
    });

    try {
      await navigator.clipboard.writeText([header.join('\t'), ...lines].join('\n'));
      setCopyFeedback(`Copied ${rows.length} rows to clipboard.`);
    } catch {
      setError('Failed to copy table to clipboard.');
    }
  }, [isGroupedByRepository, metric, metricLabel, rows]);

  useEffect(() => {
    if (!canViewStatistics) {
      return;
    }

    void loadSnapshot();
  }, [canViewStatistics, loadSnapshot]);

  if (!canViewStatistics) {
    return <Navigate to="/repositories" replace />;
  }

  return (
    <div className="settings-page statistics-page">
      <div className="settings-page__header">
        <h1>Statistics</h1>
      </div>
      <p className="settings-page__lead">Text unit ingestion trends by period and repository.</p>

      <section className="settings-card">
        <div className="settings-card__header">
          <h2>Text unit ingestion</h2>
        </div>

        <div className="statistics-page__compute-panel">
          <div className="statistics-page__compute-header">
            <h3 className="statistics-page__compute-title">Compute status</h3>
            {isAdmin ? (
              <button
                type="button"
                className="settings-button settings-button--primary"
                onClick={() => {
                  void handleRecompute();
                }}
                disabled={recomputing || loading}
              >
                {recomputing ? 'Recomputing…' : 'Recompute missing days'}
              </button>
            ) : null}
          </div>

          <div className="statistics-page__overview">
            <div className="statistics-page__overview-row">
              <span>Latest computed day</span>
              <span>{formatNullableDate(snapshot?.latestComputedDay ?? null)}</span>
            </div>
            <div className="statistics-page__overview-row">
              <span>Last compute time</span>
              <span>{formatNullableDateTime(snapshot?.lastComputedAt ?? null)}</span>
            </div>
            <div className="statistics-page__overview-row">
              <span>Latest recompute range</span>
              <span>
                {recomputeResult?.recomputedFromDay && recomputeResult?.recomputedToDay
                  ? `${recomputeResult.recomputedFromDay} to ${recomputeResult.recomputedToDay}`
                  : '-'}
              </span>
            </div>
            <div className="statistics-page__overview-row">
              <span>Rows saved (latest run)</span>
              <span>{numberFormatter.format(recomputeResult?.savedRows ?? 0)}</span>
            </div>
          </div>
        </div>

        <div className="statistics-page__table-toolbar">
          <div className="statistics-page__controls">
            <label className="settings-field__label" htmlFor="statistics-view-mode">
              View
            </label>
            <select
              id="statistics-view-mode"
              className="settings-input statistics-page__select"
              value={viewMode}
              onChange={(event) => setViewMode(toViewMode(event.target.value))}
              disabled={loading || recomputing}
            >
              {VIEW_MODE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>

            <label className="settings-field__label" htmlFor="statistics-group-by">
              Group by
            </label>
            <select
              id="statistics-group-by"
              className="settings-input statistics-page__select"
              value={groupBy}
              onChange={(event) => setGroupBy(toGroupBy(event.target.value))}
              disabled={loading || recomputing}
            >
              {GROUP_BY_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>

            <label className="settings-field__label" htmlFor="statistics-metric">
              Metric
            </label>
            <select
              id="statistics-metric"
              className="settings-input statistics-page__select"
              value={metric}
              onChange={(event) =>
                setMetric(event.target.value === 'words' ? 'words' : 'textUnits')
              }
              disabled={loading || recomputing}
            >
              {METRIC_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>

            <label className="settings-field__label" htmlFor="statistics-from-day">
              From
            </label>
            <input
              id="statistics-from-day"
              type="date"
              className="settings-input statistics-page__date-input"
              value={fromDayInput}
              onChange={(event) => setFromDayInput(event.target.value)}
              max={toDayInput || undefined}
              disabled={loading || recomputing}
            />

            <label className="settings-field__label" htmlFor="statistics-to-day">
              To
            </label>
            <input
              id="statistics-to-day"
              type="date"
              className="settings-input statistics-page__date-input"
              value={toDayInput}
              onChange={(event) => setToDayInput(event.target.value)}
              min={fromDayInput || undefined}
              disabled={loading || recomputing}
            />
          </div>

          <div className="statistics-page__actions">
            <button
              type="button"
              className="settings-button"
              onClick={() => {
                void loadSnapshot();
              }}
              disabled={loading || recomputing}
            >
              {loading ? 'Loading…' : 'Refresh'}
            </button>
            <button
              type="button"
              className="settings-button"
              onClick={() => {
                void handleCopyTable();
              }}
              disabled={rows.length === 0}
            >
              Copy table
            </button>
          </div>
        </div>

        {error ? (
          <p className="settings-hint is-error statistics-page__error" role="alert">
            {error}
          </p>
        ) : null}
        {copyFeedback ? <p className="settings-hint">{copyFeedback}</p> : null}

        {rows.length ? (
          <div className="statistics-page__table-wrap">
            <table className="statistics-page__table" aria-label="Text unit ingestion table">
              <thead>
                <tr>
                  {isGroupedByRepository ? <th>Repository</th> : null}
                  <th>Period</th>
                  <th>{metricLabel}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => {
                  const rowKey = `${row.period}:${row.repositoryId ?? 'all'}`;
                  const repositoryLabel =
                    row.repositoryName?.trim() ||
                    (row.repositoryId != null
                      ? `Repository #${row.repositoryId}`
                      : 'All repositories');
                  const workbenchState = buildWorkbenchState(row);

                  return (
                    <tr key={rowKey}>
                      {isGroupedByRepository ? <td>{repositoryLabel}</td> : null}
                      <td className="statistics-page__period-cell">
                        <span>{row.period}</span>
                        {workbenchState ? (
                          <Link
                            className="statistics-page__row-action"
                            to="/workbench"
                            state={workbenchState}
                          >
                            Open in Workbench
                          </Link>
                        ) : null}
                      </td>
                      <td>
                        {numberFormatter.format(
                          metric === 'words' ? row.wordCount : row.stringCount,
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="settings-page__hint">No persisted ingestion stats yet.</p>
        )}
      </section>
    </div>
  );
}
