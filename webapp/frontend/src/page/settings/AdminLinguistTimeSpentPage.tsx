import './settings-page.css';
import '../../components/filters/filter-chip.css';

import { useMutation, useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';

import {
  fetchLinguistTimeSpentReport,
  type LinguistTimeSpentReportParams,
  type LinguistTimeSpentWindow,
  recomputeLinguistTimeSpentReport,
  type ReviewProjectStatusFilter,
} from '../../api/linguist-time-spent';
import {
  type DateQuickRange,
  MultiSectionFilterChip,
} from '../../components/filters/MultiSectionFilterChip';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import { useLocales } from '../../hooks/useLocales';
import { useUser } from '../../hooks/useUser';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

type Filters = {
  activityAfter: string | null;
  activityBefore: string | null;
  status: ReviewProjectStatusFilter;
  translatorUserId: string;
  localeBcp47Tag: string;
};

const DEFAULT_FILTERS: Filters = {
  activityAfter: null,
  activityBefore: null,
  status: 'CLOSED',
  translatorUserId: '',
  localeBcp47Tag: '',
};

function startOfToday() {
  const date = new Date();
  date.setHours(0, 0, 0, 0);
  return date;
}

function daysAgoIso(days: number) {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date.toISOString();
}

function startOfDayIso(daysOffset = 0) {
  const date = startOfToday();
  date.setDate(date.getDate() + daysOffset);
  return date.toISOString();
}

function startOfWeekIso() {
  const date = startOfToday();
  const diff = (date.getDay() + 6) % 7;
  date.setDate(date.getDate() - diff);
  return date.toISOString();
}

function startOfMonthIso() {
  const date = startOfToday();
  date.setDate(1);
  return date.toISOString();
}

function getReportDateQuickRanges(): DateQuickRange[] {
  return [
    { label: 'Today', after: startOfDayIso(), before: null },
    { label: 'Yesterday', after: startOfDayIso(-1), before: startOfDayIso() },
    { label: 'This week', after: startOfWeekIso(), before: null },
    { label: 'This month', after: startOfMonthIso(), before: null },
    { label: 'Last 7 days', after: daysAgoIso(7), before: null },
    { label: 'Last 30 days', after: daysAgoIso(30), before: null },
    { label: 'Last 90 days', after: daysAgoIso(90), before: null },
  ];
}

function asNumberOrNull(value: string) {
  const trimmed = value.trim();
  if (!trimmed) {
    return null;
  }
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}

function buildReportParams(filters: Filters): LinguistTimeSpentReportParams {
  return {
    activityAfter: filters.activityAfter,
    activityBefore: filters.activityBefore,
    status: filters.status,
    translatorUserId: asNumberOrNull(filters.translatorUserId),
    localeBcp47Tag: filters.localeBcp47Tag.trim() || null,
    summaryLimit: 100,
    detailLimit: 100,
  };
}

function formatDuration(seconds: number | null | undefined) {
  if (seconds === null || seconds === undefined) {
    return '-';
  }
  if (seconds < 60) {
    return `${Math.round(seconds)}s`;
  }
  const minutes = seconds / 60;
  if (minutes < 60) {
    return `${Math.round(minutes)}m`;
  }
  const hours = minutes / 60;
  return `${hours.toFixed(hours < 10 ? 1 : 0)}h`;
}

function formatNumber(value: number | null | undefined) {
  return value === null || value === undefined ? '-' : value.toLocaleString();
}

function formatDateTime(value: string | null | undefined) {
  if (!value) {
    return '-';
  }
  return new Date(value).toLocaleString();
}

function formatDate(value: string | null | undefined) {
  if (!value) {
    return null;
  }
  return new Date(value).toLocaleDateString();
}

function formatRatio(value: number | null | undefined) {
  return value === null || value === undefined ? '-' : `${value.toFixed(2)}x`;
}

function formatPercent(value: number | null | undefined) {
  return value === null || value === undefined ? '-' : `${value.toFixed(0)}%`;
}

function formatCountWithPercent(count: number, percent: number) {
  return `${formatNumber(count)} / ${formatPercent(percent)}`;
}

function formatFlag(value: string) {
  switch (value) {
    case 'MISSING_REPORT':
      return 'Missing report';
    case 'CHECK_HIGH':
      return 'Check high';
    case 'CHECK_LOW':
      return 'Check low';
    case 'OK':
    default:
      return 'OK';
  }
}

function formatWindowStatus(window: LinguistTimeSpentWindow) {
  if (!window.assignmentWindowEndedAt) {
    return 'Open';
  }
  switch (window.assignmentWindowEndReason) {
    case 'PROJECT_CLOSED':
      return 'Closed';
    case 'REASSIGNED':
      return 'Reassigned';
    case 'UNASSIGNED':
      return 'Unassigned';
    default:
      return window.assignmentWindowEndReason ?? 'Ended';
  }
}

function discrepancyLabel(window: LinguistTimeSpentWindow) {
  if (window.reportedMissing) {
    return 'Missing report';
  }
  return formatRatio(window.reportedComputedRatio);
}

function formatAcceptDelay(window: LinguistTimeSpentWindow) {
  if (!window.assignmentAcceptedAt) {
    return 'Not accepted';
  }
  return formatDuration(window.assignedToAcceptedSeconds);
}

function formatFirstDecisionDelay(window: LinguistTimeSpentWindow) {
  if (!window.assignmentAcceptedAt || !window.firstDecisionAt) {
    return '-';
  }
  return formatDuration(window.acceptedToFirstDecisionSeconds);
}

function formatDeadlineStatus(window: LinguistTimeSpentWindow) {
  if (!window.projectDueDate) {
    return '-';
  }
  if (!window.lastDecisionAt) {
    return 'No decisions';
  }
  const dueDate = new Date(window.projectDueDate);
  const lastDecisionAt = new Date(window.lastDecisionAt);
  if (lastDecisionAt > dueDate) {
    return `Late by ${formatDuration((lastDecisionAt.getTime() - dueDate.getTime()) / 1000)}`;
  }
  return 'On time';
}

function ReportHelp() {
  return (
    <details className="settings-help-popover">
      <summary aria-label="Explain linguist time report">?</summary>
      <div className="settings-help-popover__panel">
        <h3>How this report works</h3>
        <p>
          An assignment window is one period where one linguist is assigned to one review project.
          Reassignment creates a new window, so the same project can appear more than once.
        </p>
        <dl>
          <dt>Reported</dt>
          <dd>
            Self-reported time entered by the linguist. This should include project-level research
            or coordination that is not visible from decision timestamps.
          </dd>
          <dt>Computed</dt>
          <dd>
            Estimated active decision/editing time from attributed decisions. Long gaps are capped
            from word count so breaks are not counted as work.
          </dd>
          <dt>Raw time</dt>
          <dd>
            Uncapped measured time before pause caps. This includes an estimated cost for the first
            decision and full gaps between later decisions.
          </dd>
          <dt>Pauses</dt>
          <dd>
            Estimated excluded break time and count of decision gaps treated as likely breaks.
          </dd>
          <dt>Flags</dt>
          <dd>Missing self-reports or reported/computed ratios that need PM review.</dd>
        </dl>
      </div>
    </details>
  );
}

export function AdminLinguistTimeSpentPage() {
  const user = useUser();
  const canView = user.role === 'ROLE_ADMIN' || user.role === 'ROLE_PM';
  const isAdmin = user.role === 'ROLE_ADMIN';
  const resolveLocaleName = useLocaleDisplayNameResolver();
  const { data: locales, isLoading: localesLoading } = useLocales();
  const [filters, setFilters] = useState<Filters>(DEFAULT_FILTERS);
  const [appliedFilters, setAppliedFilters] = useState<Filters>(DEFAULT_FILTERS);
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; message: string } | null>(null);
  const dateQuickRanges = useMemo(() => getReportDateQuickRanges(), []);
  const localeOptions = useMemo(
    () =>
      (locales ?? [])
        .map((locale) => ({
          tag: locale.bcp47Tag,
          label: resolveLocaleName(locale.bcp47Tag),
        }))
        .sort((first, second) => {
          const labelCompare = first.label.localeCompare(second.label, undefined, {
            sensitivity: 'base',
          });
          if (labelCompare !== 0) {
            return labelCompare;
          }
          return first.tag.localeCompare(second.tag, undefined, { sensitivity: 'base' });
        }),
    [locales, resolveLocaleName],
  );

  const reportParams = buildReportParams(appliedFilters);
  const reportQuery = useQuery({
    queryKey: ['linguist-time-spent-report', reportParams],
    queryFn: () => fetchLinguistTimeSpentReport(reportParams),
    enabled: canView,
  });

  const recomputeMutation = useMutation({
    mutationFn: () =>
      recomputeLinguistTimeSpentReport({
        projectCreatedAfter: filters.activityAfter,
        projectCreatedBefore: filters.activityBefore,
        status: filters.status,
        translatorUserId: asNumberOrNull(filters.translatorUserId),
        localeBcp47Tag: filters.localeBcp47Tag.trim() || null,
        limit: 500,
      }),
    onSuccess: async (response) => {
      setNotice({
        kind: 'success',
        message: `Matched ${response.matchedProjectCount} projects, computed ${response.computedWindowCount} assignment windows, backfilled ${response.backfilledWindowCount}.`,
      });
      await reportQuery.refetch();
    },
    onError: (error: Error) => {
      setNotice({ kind: 'error', message: error.message || 'Recompute failed.' });
    },
  });

  if (!canView) {
    return <Navigate to="/settings/me" replace />;
  }

  const report = reportQuery.data;
  const isWorking = reportQuery.isFetching || recomputeMutation.isPending;
  const activitySummary = (() => {
    const after = formatDate(filters.activityAfter);
    const before = formatDate(filters.activityBefore);
    if (after && before) {
      return `Activity ${after} - ${before}`;
    }
    if (after) {
      return `Activity after ${after}`;
    }
    if (before) {
      return `Activity before ${before}`;
    }
    return 'Activity';
  })();

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to system settings"
        context="System settings"
        title="Linguist time spent"
      />

      <div className="settings-page settings-page--wide">
        <section className="settings-card">
          <div className="settings-card__header">
            <h2>Filters</h2>
            <ReportHelp />
          </div>
          <p className="settings-page__hint">
            Report reads persisted computed rows. Recompute is explicit and scoped by these filters.
          </p>
          <div className="settings-filter-grid settings-filter-grid--inline">
            <div className="settings-actions settings-actions--wrap">
              <MultiSectionFilterChip
                ariaLabel="Filter activity date"
                align="left"
                className="filter-chip"
                classNames={{
                  button: 'filter-chip__button',
                  panel: 'filter-chip__panel',
                  section: 'filter-chip__section',
                  label: 'filter-chip__label',
                  list: 'filter-chip__list',
                  option: 'filter-chip__option',
                  quick: 'filter-chip__quick',
                  quickChip: 'settings-pill',
                  dateInput: 'filter-chip__date-input',
                  clear: 'filter-chip__clear-link',
                }}
                summary={activitySummary}
                sections={[
                  {
                    kind: 'date',
                    label: 'Activity',
                    after: filters.activityAfter,
                    before: filters.activityBefore,
                    onChangeAfter: (value) =>
                      setFilters((current) => ({
                        ...current,
                        activityAfter: value,
                      })),
                    onChangeBefore: (value) =>
                      setFilters((current) => ({
                        ...current,
                        activityBefore: value,
                      })),
                    quickRanges: dateQuickRanges,
                    onClear: () =>
                      setFilters((current) => ({
                        ...current,
                        activityAfter: null,
                        activityBefore: null,
                      })),
                  },
                ]}
                disabled={isWorking}
              />
              <SingleSelectDropdown<ReviewProjectStatusFilter>
                label="Project status"
                options={[
                  { value: 'CLOSED', label: 'Closed' },
                  { value: 'OPEN', label: 'Open' },
                ]}
                value={filters.status}
                onChange={(value) =>
                  setFilters((current) => ({
                    ...current,
                    status: value ?? 'CLOSED',
                  }))
                }
                searchable={false}
                disabled={isWorking}
                buttonAriaLabel="Filter by project status"
              />
              <SingleSelectDropdown<string>
                label="Language"
                options={localeOptions.map((locale) => ({
                  value: locale.tag,
                  label: locale.label,
                  helper: locale.tag,
                }))}
                value={filters.localeBcp47Tag || null}
                onChange={(value) =>
                  setFilters((current) => ({
                    ...current,
                    localeBcp47Tag: value ?? '',
                  }))
                }
                noneLabel="All languages"
                placeholder="All languages"
                disabled={isWorking || localesLoading}
                buttonAriaLabel="Filter by language"
                searchPlaceholder="Filter languages"
              />
            </div>
            <label className="settings-field">
              <span className="settings-field__label">Translator user ID</span>
              <input
                className="settings-input"
                type="number"
                min="1"
                value={filters.translatorUserId}
                onChange={(event) =>
                  setFilters((current) => ({
                    ...current,
                    translatorUserId: event.target.value,
                  }))
                }
              />
            </label>
            <div className="settings-actions">
              <button
                type="button"
                className="settings-button settings-button--primary"
                onClick={() => {
                  setAppliedFilters(filters);
                  setNotice(null);
                }}
                disabled={isWorking}
              >
                Apply
              </button>
              {isAdmin ? (
                <button
                  type="button"
                  className="settings-button"
                  onClick={() => recomputeMutation.mutate()}
                  disabled={isWorking}
                >
                  Recompute
                </button>
              ) : null}
            </div>
          </div>
          {notice ? (
            <p className={`settings-hint ${notice.kind === 'error' ? 'is-error' : ''}`}>
              {notice.message}
            </p>
          ) : null}
          {reportQuery.error ? (
            <p className="settings-hint is-error">
              {reportQuery.error.message || 'Failed to load report.'}
            </p>
          ) : null}
        </section>

        <section className="settings-card">
          <div className="settings-card__header">
            <h2>Summary</h2>
          </div>
          {report ? (
            <div className="settings-table-wrapper">
              <table className="settings-table">
                <thead>
                  <tr>
                    <th>Assignment windows</th>
                    <th>Projects</th>
                    <th>Words</th>
                    <th>Reported</th>
                    <th>Computed</th>
                    <th>Raw time</th>
                    <th>Pauses</th>
                    <th>Flags</th>
                    <th>Last computed</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td>{formatNumber(report.summary.windowCount)}</td>
                    <td>{formatNumber(report.summary.projectCount)}</td>
                    <td>{formatNumber(report.summary.decidedWordCount)}</td>
                    <td>{formatDuration(report.summary.selfReportedSeconds)}</td>
                    <td>{formatDuration(report.summary.estimatedActiveSeconds)}</td>
                    <td>{formatDuration(report.summary.rawDecisionSpanSeconds)}</td>
                    <td>
                      {formatDuration(report.summary.pauseSeconds)} /{' '}
                      {formatNumber(report.summary.pauseCount)}
                    </td>
                    <td>
                      {formatNumber(report.summary.reportedMissingCount)} missing,{' '}
                      {formatNumber(report.summary.reviewFlagCount)} check
                    </td>
                    <td>{formatDateTime(report.summary.lastComputedAt)}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          ) : (
            <p className="settings-hint">Loading report...</p>
          )}
        </section>

        <section className="settings-card">
          <div className="settings-card__header">
            <h2>Translator scorecard</h2>
          </div>
          {report && report.translatorScorecards.length > 0 ? (
            <div className="settings-table-wrapper">
              <table className="settings-table">
                <thead>
                  <tr>
                    <th>Translator</th>
                    <th>Projects</th>
                    <th>Assignment windows</th>
                    <th>Words</th>
                    <th>Avg accept</th>
                    <th>Not accepted</th>
                    <th>Deadline misses</th>
                    <th>Missing reports</th>
                    <th>Checks</th>
                    <th>Reported / computed</th>
                    <th>Last computed</th>
                  </tr>
                </thead>
                <tbody>
                  {report.translatorScorecards.map((row) => (
                    <tr key={row.assignedTranslatorUserId ?? row.assignedTranslatorUsername ?? '-'}>
                      <td>{row.assignedTranslatorUsername ?? '-'}</td>
                      <td>{formatNumber(row.projectCount)}</td>
                      <td>{formatNumber(row.windowCount)}</td>
                      <td>{formatNumber(row.decidedWordCount)}</td>
                      <td>{formatDuration(row.averageAssignedToAcceptedSeconds)}</td>
                      <td>
                        {formatCountWithPercent(row.notAcceptedCount, row.notAcceptedPercent)}
                      </td>
                      <td>
                        {formatCountWithPercent(row.missedDeadlineCount, row.missedDeadlinePercent)}
                      </td>
                      <td>
                        {formatCountWithPercent(
                          row.reportedMissingCount,
                          row.reportedMissingPercent,
                        )}
                      </td>
                      <td>{formatCountWithPercent(row.reviewFlagCount, row.reviewFlagPercent)}</td>
                      <td>
                        {formatDuration(row.selfReportedSeconds)} /{' '}
                        {formatDuration(row.estimatedActiveSeconds)} (
                        {formatRatio(row.reportedComputedRatio)})
                      </td>
                      <td>{formatDateTime(row.lastComputedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="settings-hint">
              {reportQuery.isFetching ? 'Loading scorecard...' : 'No scorecard rows found.'}
            </p>
          )}
        </section>

        <section className="settings-card">
          <div className="settings-card__header">
            <h2>By linguist and language</h2>
          </div>
          {report && report.linguists.length > 0 ? (
            <div className="settings-table-wrapper">
              <table className="settings-table">
                <thead>
                  <tr>
                    <th>Linguist</th>
                    <th>Language</th>
                    <th>Projects</th>
                    <th>Assignment windows</th>
                    <th>Words</th>
                    <th>Reported</th>
                    <th>Computed</th>
                    <th>Raw time</th>
                    <th>Pauses</th>
                    <th>Flags</th>
                  </tr>
                </thead>
                <tbody>
                  {report.linguists.map((row) => (
                    <tr
                      key={`${row.assignedTranslatorUserId ?? 'unknown'}-${row.localeBcp47Tag ?? 'unknown'}`}
                    >
                      <td>{row.assignedTranslatorUsername ?? '-'}</td>
                      <td>{row.localeBcp47Tag ?? '-'}</td>
                      <td>{formatNumber(row.metrics.projectCount)}</td>
                      <td>{formatNumber(row.metrics.windowCount)}</td>
                      <td>{formatNumber(row.metrics.decidedWordCount)}</td>
                      <td>{formatDuration(row.metrics.selfReportedSeconds)}</td>
                      <td>{formatDuration(row.metrics.estimatedActiveSeconds)}</td>
                      <td>{formatDuration(row.metrics.rawDecisionSpanSeconds)}</td>
                      <td>
                        {formatDuration(row.metrics.pauseSeconds)} /{' '}
                        {formatNumber(row.metrics.pauseCount)}
                      </td>
                      <td>
                        {formatNumber(row.metrics.reportedMissingCount)} missing,{' '}
                        {formatNumber(row.metrics.reviewFlagCount)} check
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="settings-hint">
              {reportQuery.isFetching ? 'Loading linguist rows...' : 'No computed rows found.'}
            </p>
          )}
        </section>

        <section className="settings-card">
          <div className="settings-card__header">
            <h2>Project assignment windows</h2>
          </div>
          {report && report.windows.length > 0 ? (
            <div className="settings-table-wrapper">
              <table className="settings-table">
                <thead>
                  <tr>
                    <th>Project</th>
                    <th>Assignment window</th>
                    <th>Request</th>
                    <th>Linguist</th>
                    <th>Language</th>
                    <th>Decisions</th>
                    <th>Reported</th>
                    <th>Computed</th>
                    <th>Raw time</th>
                    <th>Pause count</th>
                    <th>Accepted after</th>
                    <th>First decision</th>
                    <th>Deadline</th>
                    <th>Discrepancy</th>
                    <th>Flag</th>
                    <th>Last decision</th>
                  </tr>
                </thead>
                <tbody>
                  {report.windows.map((window) => (
                    <tr key={window.id}>
                      <td>
                        <Link
                          className="settings-table__link"
                          to={`/review-projects/${window.reviewProjectId}`}
                        >
                          {window.reviewProjectId}
                        </Link>
                      </td>
                      <td>
                        #{window.assignmentWindowId} · {formatWindowStatus(window)}
                      </td>
                      <td>
                        {window.reviewProjectRequestName ?? window.reviewProjectRequestId ?? '-'}
                      </td>
                      <td>{window.assignedTranslatorUsername ?? '-'}</td>
                      <td>{window.localeBcp47Tag ?? '-'}</td>
                      <td>
                        {formatNumber(window.decidedCount)} /{' '}
                        {formatNumber(window.decidedWordCount)}w
                      </td>
                      <td>
                        {window.reportedMissing
                          ? 'Missing'
                          : formatDuration(window.selfReportedSeconds)}
                      </td>
                      <td>{formatDuration(window.estimatedActiveSeconds)}</td>
                      <td>{formatDuration(window.rawDecisionSpanSeconds)}</td>
                      <td>{formatNumber(window.pauseCount)}</td>
                      <td>{formatAcceptDelay(window)}</td>
                      <td>{formatFirstDecisionDelay(window)}</td>
                      <td>{formatDeadlineStatus(window)}</td>
                      <td>{discrepancyLabel(window)}</td>
                      <td>{formatFlag(window.reviewFlag)}</td>
                      <td>{formatDateTime(window.lastDecisionAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="settings-hint">
              {reportQuery.isFetching
                ? 'Loading assignment windows...'
                : 'No assignment windows found.'}
            </p>
          )}
        </section>
      </div>
    </div>
  );
}
