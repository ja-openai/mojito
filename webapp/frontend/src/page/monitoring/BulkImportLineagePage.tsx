import '../settings/settings-page.css';
import './monitoring-page.css';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Navigate } from 'react-router-dom';

import {
  type BulkImportInputPayload,
  type BulkImportOutputPayload,
  type BulkImportRunSummary,
  fetchBulkImportInput,
  fetchBulkImportOutput,
  fetchBulkImportRuns,
} from '../../api/monitoring';
import { ResizableMasterDetailLayout } from '../../components/ResizableMasterDetailLayout';
import { useUser } from '../../hooks/useUser';

const RUN_LIMIT = 50;
const VISIBLE_TEXT_UNIT_LIMIT = 200;
const RUN_LIST_WIDTH_STORAGE_KEY = 'bulk-import-lineage:run-list-width-percent';
const AI_TRANSLATE_SOURCE = 'AI_TRANSLATE';
const MACHINE_TRANSLATION_SOURCE = 'MACHINE_TRANSLATION';
const UNKNOWN_IDENTITY = 'UNKNOWN';
const IDENTITY_LABELS: Record<string, string> = {
  'ai-translate': 'AI Translate service',
  'machine-translation': 'Machine Translation service',
  not_reviewed: 'Not reviewed',
};

function getErrorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message.trim() ? error.message : fallback;
}

function formatTimestamp(value: string | null) {
  return value ? new Date(value).toLocaleString() : '-';
}

function formatVariantTransition(previousId?: number | null, resultingId?: number | null) {
  if (!previousId && !resultingId) {
    return '-';
  }
  return `${previousId ?? '-'} → ${resultingId ?? '-'}`;
}

function displayIdentity(...identities: Array<string | null | undefined>) {
  for (const identity of identities) {
    const normalized = identity?.trim();
    if (!normalized || normalized.toUpperCase() === UNKNOWN_IDENTITY) {
      continue;
    }
    return IDENTITY_LABELS[normalized.toLowerCase()] ?? normalized;
  }
  return null;
}

function hasUnknownIdentity(...identities: Array<string | null | undefined>) {
  return identities.some((identity) => identity?.trim().toUpperCase() === UNKNOWN_IDENTITY);
}

function automatedWorkflowLabel(source: string) {
  if (source === AI_TRANSLATE_SOURCE) {
    return 'AI Translate workflow';
  }
  if (source === MACHINE_TRANSLATION_SOURCE) {
    return 'Machine Translation workflow';
  }
  return null;
}

function formatInitiator(run: BulkImportRunSummary) {
  const identity = displayIdentity(run.actorIdentity);
  if (identity) {
    return identity;
  }
  const actorType = displayIdentity(run.actorType);
  if (actorType) {
    return actorType;
  }
  const workflow = automatedWorkflowLabel(run.source);
  return workflow && hasUnknownIdentity(run.actorIdentity, run.actorType)
    ? `Not recorded (${workflow})`
    : 'Not provided';
}

function formatTranslator(source: string, ...identities: Array<string | null | undefined>) {
  const identity = displayIdentity(...identities);
  if (identity) {
    return identity;
  }
  const workflow = automatedWorkflowLabel(source);
  return workflow && hasUnknownIdentity(...identities)
    ? `Not recorded (${workflow})`
    : 'Not provided';
}

function formatReviewer(...identities: Array<string | null | undefined>) {
  const identity = displayIdentity(...identities);
  if (identity) {
    return identity;
  }
  return hasUnknownIdentity(...identities) ? 'Not recorded' : 'Not provided';
}

export function BulkImportLineagePage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const [runs, setRuns] = useState<BulkImportRunSummary[]>([]);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [inputPayload, setInputPayload] = useState<BulkImportInputPayload | null>(null);
  const [outputPayload, setOutputPayload] = useState<BulkImportOutputPayload | null>(null);
  const [loadingRuns, setLoadingRuns] = useState(false);
  const [loadingReport, setLoadingReport] = useState(false);
  const [runsError, setRunsError] = useState<string | null>(null);
  const [reportError, setReportError] = useState<string | null>(null);

  const selectedRun = useMemo(
    () => runs.find((run) => run.runId === selectedRunId) ?? null,
    [runs, selectedRunId],
  );

  const loadRuns = useCallback(async () => {
    setLoadingRuns(true);
    setRunsError(null);
    try {
      const recentRuns = await fetchBulkImportRuns(RUN_LIMIT);
      setRuns(recentRuns);
      setSelectedRunId((current) =>
        current && recentRuns.some((run) => run.runId === current)
          ? current
          : (recentRuns[0]?.runId ?? null),
      );
    } catch (error) {
      setRunsError(getErrorMessage(error, 'Failed to load bulk import history.'));
    } finally {
      setLoadingRuns(false);
    }
  }, []);

  useEffect(() => {
    if (isAdmin) {
      void loadRuns();
    }
  }, [isAdmin, loadRuns]);

  useEffect(() => {
    if (!isAdmin || !selectedRun) {
      setInputPayload(null);
      setOutputPayload(null);
      return;
    }

    let active = true;
    setLoadingReport(true);
    setReportError(null);
    setInputPayload(null);
    setOutputPayload(null);

    const inputRequest = selectedRun.inputPayloadBlobName
      ? fetchBulkImportInput(selectedRun.runId)
      : Promise.resolve(null);
    const outputRequest = selectedRun.outputPayloadBlobName
      ? fetchBulkImportOutput(selectedRun.runId)
      : Promise.resolve(null);

    void Promise.allSettled([inputRequest, outputRequest]).then(([inputResult, outputResult]) => {
      if (!active) {
        return;
      }

      if (inputResult.status === 'fulfilled') {
        setInputPayload(inputResult.value);
      }
      if (outputResult.status === 'fulfilled') {
        setOutputPayload(outputResult.value);
      }

      const failures = [inputResult, outputResult].filter(
        (result): result is PromiseRejectedResult => result.status === 'rejected',
      );
      if (failures.length > 0) {
        setReportError(
          failures
            .map((failure) => getErrorMessage(failure.reason, 'Failed to load report payload.'))
            .join(' '),
        );
      }
      setLoadingReport(false);
    });

    return () => {
      active = false;
    };
  }, [isAdmin, selectedRun]);

  const reportRows = useMemo(() => {
    const inputTextUnits = inputPayload?.textUnits ?? [];
    const outputTextUnits = outputPayload?.textUnits ?? [];
    const count = Math.max(inputTextUnits.length, outputTextUnits.length);
    return Array.from({ length: count }, (_, index) => ({
      index,
      input: inputTextUnits[index],
      output: outputTextUnits[index],
    }));
  }, [inputPayload, outputPayload]);

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  const runList = (
    <section className="settings-card bulk-import-lineage-page__runs-pane">
      <div className="settings-card__header">
        <h2>Recent runs</h2>
        <button
          type="button"
          className="settings-button"
          disabled={loadingRuns}
          onClick={() => void loadRuns()}
        >
          {loadingRuns ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>

      {runsError ? (
        <p className="settings-hint is-error monitoring-page__error" role="alert">
          {runsError}
        </p>
      ) : null}

      <div className="monitoring-page__table-wrap bulk-import-lineage-page__runs-table-wrap">
        <table
          className="monitoring-page__table bulk-import-lineage-page__runs-table"
          aria-label="Recent bulk imports"
        >
          <thead>
            <tr>
              <th>Run</th>
              <th>Started</th>
              <th>Locale</th>
              <th>Initiator</th>
              <th>Outcome</th>
            </tr>
          </thead>
          <tbody>
            {runs.map((run) => (
              <tr key={run.runId} className={run.runId === selectedRunId ? 'is-selected' : ''}>
                <td>
                  <button
                    type="button"
                    className="bulk-import-lineage-page__run-button"
                    aria-pressed={run.runId === selectedRunId}
                    onClick={() => setSelectedRunId(run.runId)}
                  >
                    <strong>{run.repositoryName}</strong>
                    <span>{run.assetPath}</span>
                  </button>
                </td>
                <td>
                  <span>{formatTimestamp(run.createdDate)}</span>
                  <span className="bulk-import-lineage-page__secondary">{run.source}</span>
                </td>
                <td>{run.locale}</td>
                <td className="bulk-import-lineage-page__identity">{formatInitiator(run)}</td>
                <td>
                  <span
                    className={`bulk-import-lineage-page__status is-${run.status.toLowerCase()}`}
                  >
                    {run.status}
                  </span>
                  <span className="bulk-import-lineage-page__secondary">
                    {run.importedCount} / {run.requestedCount} imported
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {!loadingRuns && runs.length === 0 && !runsError ? (
        <p className="settings-page__hint">No bulk imports have been recorded yet.</p>
      ) : null}
    </section>
  );

  const runReport = selectedRun ? (
    <section className="settings-card bulk-import-lineage-page__report-pane">
      <div className="settings-card__header bulk-import-lineage-page__report-header">
        <h2>Run report</h2>
        <div className="monitoring-page__controls bulk-import-lineage-page__payload-actions">
          {selectedRun.inputPayloadBlobName ? (
            <a
              className="settings-button"
              href={`/api/monitoring/import-lineage/${encodeURIComponent(selectedRun.runId)}/input`}
              target="_blank"
              rel="noreferrer"
            >
              Open normalized input JSON
            </a>
          ) : null}
          {selectedRun.outputPayloadBlobName ? (
            <a
              className="settings-button"
              href={`/api/monitoring/import-lineage/${encodeURIComponent(selectedRun.runId)}/output`}
              target="_blank"
              rel="noreferrer"
            >
              Open result JSON
            </a>
          ) : null}
        </div>
      </div>

      <div className="monitoring-page__overview">
        <div className="monitoring-page__overview-row">
          <span>Run ID</span>
          <code>{selectedRun.runId}</code>
        </div>
        <div className="monitoring-page__overview-row">
          <span>Completed</span>
          <span>{formatTimestamp(selectedRun.completedDate)}</span>
        </div>
        <div className="monitoring-page__overview-row">
          <span>Initiator</span>
          <span>{formatInitiator(selectedRun)}</span>
        </div>
        <div className="monitoring-page__overview-row">
          <span>Options</span>
          <span>
            {selectedRun.importMode} · integrity {selectedRun.integrityChecksType}
          </span>
        </div>
        <div className="monitoring-page__overview-row">
          <span>Outcome</span>
          <span>
            {selectedRun.importedCount} imported · {selectedRun.skippedCount} skipped ·{' '}
            {selectedRun.requestedCount} requested
          </span>
        </div>
        {selectedRun.errorMessage ? (
          <div className="monitoring-page__overview-row">
            <span>Error</span>
            <span>{selectedRun.errorMessage}</span>
          </div>
        ) : null}
      </div>

      {reportError ? (
        <p className="settings-hint is-error monitoring-page__error" role="alert">
          {reportError}
        </p>
      ) : null}

      {loadingReport ? <p className="settings-page__hint">Loading report…</p> : null}

      {!loadingReport && reportRows.length > 0 ? (
        <>
          <div className="monitoring-page__table-wrap bulk-import-lineage-page__report-table-wrap">
            <table className="monitoring-page__table" aria-label="Bulk import text units">
              <thead>
                <tr>
                  <th>String</th>
                  <th>Source</th>
                  <th>Target</th>
                  <th>Outcome</th>
                  <th>Attribution</th>
                  <th>Variant transition</th>
                </tr>
              </thead>
              <tbody>
                {reportRows.slice(0, VISIBLE_TEXT_UNIT_LIMIT).map(({ index, input, output }) => {
                  const translator = formatTranslator(
                    selectedRun.source,
                    output?.translatorIdentity,
                    input?.translatorIdentity,
                  );
                  const reviewer = formatReviewer(
                    output?.reviewerIdentity,
                    input?.reviewerIdentity,
                  );
                  return (
                    <tr key={`${output?.tmTextUnitId ?? input?.tmTextUnitId ?? 'row'}-${index}`}>
                      <td>
                        <strong>{output?.name || input?.name || '(unnamed)'}</strong>
                        <span className="bulk-import-lineage-page__secondary">
                          {output?.tmTextUnitId ?? input?.tmTextUnitId ?? 'Unmatched'}
                        </span>
                      </td>
                      <td className="bulk-import-lineage-page__message">{input?.source || '-'}</td>
                      <td className="bulk-import-lineage-page__message">{input?.target || '-'}</td>
                      <td>
                        {output?.status || (selectedRun.status === 'FAILED' ? 'FAILED' : 'PENDING')}
                      </td>
                      <td className="bulk-import-lineage-page__identity">
                        <span>{translator}</span>
                        <span className="bulk-import-lineage-page__secondary">
                          Reviewer: {reviewer}
                        </span>
                      </td>
                      <td>
                        {formatVariantTransition(
                          output?.previousTmTextUnitVariantId,
                          output?.resultingTmTextUnitVariantId,
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {reportRows.length > VISIBLE_TEXT_UNIT_LIMIT ? (
            <p className="settings-page__hint">
              Showing the first {VISIBLE_TEXT_UNIT_LIMIT} of {reportRows.length} strings. Open the
              JSON files for the complete report.
            </p>
          ) : null}
        </>
      ) : null}

      {!loadingReport && reportRows.length === 0 && !reportError ? (
        <p className="settings-page__hint">No per-string payload is available for this run yet.</p>
      ) : null}
    </section>
  ) : (
    <section className="settings-card bulk-import-lineage-page__report-pane">
      <div className="settings-card__header">
        <h2>Run report</h2>
      </div>
      <p className="settings-page__hint">Select a run to review its lineage report.</p>
    </section>
  );

  return (
    <div className="settings-page settings-page--wide monitoring-page bulk-import-lineage-page">
      <div className="settings-page__header">
        <h1>Bulk import history</h1>
      </div>
      <p className="settings-page__lead">
        Review who initiated a batch, what it contained, and the outcome retained in its lineage
        report.
      </p>

      <ResizableMasterDetailLayout
        className="bulk-import-lineage-page__layout"
        storageKey={RUN_LIST_WIDTH_STORAGE_KEY}
        sidebarLabel="Bulk import runs"
        detailLabel="Selected bulk import report"
        resizeLabel="Resize bulk import run list"
        sidebarClassName="bulk-import-lineage-page__master"
        detailClassName="bulk-import-lineage-page__detail"
        defaultSidebarWidthPercent={42}
        minSidebarWidthPercent={30}
        maxSidebarWidthPercent={55}
        sidebar={runList}
        detail={runReport}
      />
    </div>
  );
}
