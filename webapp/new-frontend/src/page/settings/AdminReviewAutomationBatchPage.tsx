import './settings-page.css';
import './admin-user-batch-page.css';
import './admin-review-automation-batch-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';

import {
  batchUpsertReviewAutomations,
  fetchReviewAutomationBatchExport,
  fetchReviewAutomationOptions,
} from '../../api/review-automations';
import { fetchReviewFeatureOptions } from '../../api/review-features';
import { fetchTeams } from '../../api/teams';
import { useUser } from '../../components/RequireUser';
import {
  DEFAULT_REVIEW_AUTOMATION_CRON_EXPRESSION,
  DEFAULT_REVIEW_AUTOMATION_TIME_ZONE,
} from '../../utils/reviewAutomationSchedule';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

type ParsedRow = {
  lineNumber: number;
  raw: string;
  id?: number;
  action: 'create' | 'update';
  name: string;
  enabled: boolean;
  cronExpression: string;
  timeZone: string;
  teamId: number;
  teamName: string;
  dueDateOffsetDays: number;
  maxWordCountPerProject: number;
  assignTranslator: boolean;
  featureIds: number[];
  featureNames: string[];
  errors: string[];
};

const DEFAULT_MAX_WORD_COUNT = 2000;

const EXAMPLE_INPUT = `Payments daily | enabled | 0 0 0 * * ? | America/Los_Angeles | Core Localization | assign-translator | 1 | 2000 | Payments
Vendor catch-up | disabled | 0 30 6 * * ? | UTC | Vendor Team | no-translator | 2 | 1500 | Billing, Checkout`;

const normalizeAutomationName = (value: string) => value.trim().replace(/\s+/g, ' ');

const formatBatchExportRow = (row: {
  name: string;
  enabled: boolean;
  cronExpression: string;
  timeZone: string;
  teamName: string | null;
  dueDateOffsetDays: number;
  maxWordCountPerProject: number;
  assignTranslator?: boolean | null;
  featureNames: string[];
}) =>
  `${row.name} | ${row.enabled ? 'enabled' : 'disabled'} | ${row.cronExpression} | ${row.timeZone} | ${row.teamName ?? ''} | ${row.assignTranslator === false ? 'no-translator' : 'assign-translator'} | ${row.dueDateOffsetDays} | ${row.maxWordCountPerProject} | ${row.featureNames.join(', ')}`;

const normalizeEnabled = (value?: string | null) => {
  const trimmed = (value ?? '').trim().toLowerCase();
  if (!trimmed) {
    return true;
  }
  if (['enabled', 'true', 'yes', 'on'].includes(trimmed)) {
    return true;
  }
  if (['disabled', 'false', 'no', 'off'].includes(trimmed)) {
    return false;
  }
  return null;
};

const normalizeMaxWordCount = (value?: string | null) => {
  const trimmed = (value ?? '').trim();
  if (!trimmed) {
    return DEFAULT_MAX_WORD_COUNT;
  }
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed) || !Number.isInteger(parsed) || parsed < 1) {
    return null;
  }
  return parsed;
};

const normalizeDueDateOffsetDays = (value?: string | null) => {
  const trimmed = (value ?? '').trim();
  if (!trimmed) {
    return 1;
  }
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed) || !Number.isInteger(parsed) || parsed < 0) {
    return null;
  }
  return parsed;
};

const normalizeAssignTranslator = (value?: string | null) => {
  const trimmed = (value ?? '').trim().toLowerCase();
  if (!trimmed) {
    return true;
  }
  if (['assign-translator', 'assign', 'true', 'yes', 'on'].includes(trimmed)) {
    return true;
  }
  if (['no-translator', 'skip-translator', 'none', 'false', 'no', 'off'].includes(trimmed)) {
    return false;
  }
  return null;
};

const normalizeTimeZone = (value?: string | null) => {
  const trimmed = (value ?? '').trim();
  return trimmed || DEFAULT_REVIEW_AUTOMATION_TIME_ZONE;
};

function parseBatchInput(
  input: string,
  featureIdsByName: Map<string, number>,
  featureDisplayNamesByName: Map<string, string>,
  existingAutomationsByName: Map<string, { id: number }>,
  teamIdsByName: Map<string, number>,
  teamDisplayNamesByName: Map<string, string>,
): ParsedRow[] {
  const rows = input
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line, index) => {
      const parts = line.split('|').map((part) => part.trim());
      const normalizedName = normalizeAutomationName(parts[0] ?? '');
      const enabled = normalizeEnabled(parts[1] ?? '');
      const cronExpression = (parts[2] ?? '').trim();
      const timeZone = normalizeTimeZone(parts[3] ?? '');
      const teamName = (parts[4] ?? '').trim();
      const hasAssignTranslatorColumn = parts.length >= 9;
      const assignTranslator = hasAssignTranslatorColumn
        ? normalizeAssignTranslator(parts[5] ?? '')
        : true;
      const dueDateOffsetDays = normalizeDueDateOffsetDays(
        parts[hasAssignTranslatorColumn ? 6 : 5] ?? '',
      );
      const maxWordCountPerProject = normalizeMaxWordCount(
        parts[hasAssignTranslatorColumn ? 7 : 6] ?? '',
      );
      const featureNames =
        parts[hasAssignTranslatorColumn ? 8 : 7]
          ?.split(/[;,]+/)
          .map((part) => part.trim())
          .filter(Boolean) ?? [];

      const errors: string[] = [];
      if (!normalizedName) {
        errors.push('Missing automation name');
      }
      if (enabled == null) {
        errors.push('Invalid enabled flag');
      }
      if (!cronExpression) {
        errors.push('Missing cron expression');
      }
      const normalizedTeamKey = teamName.toLowerCase();
      const teamId = normalizedTeamKey ? teamIdsByName.get(normalizedTeamKey) : null;
      if (teamId == null) {
        errors.push(`Unknown team: ${teamName || '(blank)'}`);
      }
      if (dueDateOffsetDays == null) {
        errors.push('Invalid due date offset days');
      }
      if (maxWordCountPerProject == null) {
        errors.push('Invalid max word count');
      }
      if (assignTranslator == null) {
        errors.push('Invalid translator assignment flag');
      }

      const resolvedFeatureIds: number[] = [];
      const resolvedFeatureNames: string[] = [];
      const seenFeatureIds = new Set<number>();
      for (const featureName of featureNames) {
        const key = featureName.toLowerCase();
        const featureId = featureIdsByName.get(key);
        if (featureId == null) {
          errors.push(`Unknown review feature: ${featureName}`);
          continue;
        }
        if (seenFeatureIds.has(featureId)) {
          continue;
        }
        seenFeatureIds.add(featureId);
        resolvedFeatureIds.push(featureId);
        resolvedFeatureNames.push(featureDisplayNamesByName.get(key) ?? featureName);
      }

      const existing = normalizedName
        ? existingAutomationsByName.get(normalizedName.toLowerCase())
        : undefined;
      const action: ParsedRow['action'] = existing ? 'update' : 'create';

      return {
        lineNumber: index + 1,
        raw: line,
        id: existing?.id,
        action,
        name: normalizedName,
        enabled: enabled ?? true,
        cronExpression,
        timeZone,
        teamId: teamId ?? -1,
        teamName: teamDisplayNamesByName.get(normalizedTeamKey) ?? teamName,
        dueDateOffsetDays: dueDateOffsetDays ?? 1,
        maxWordCountPerProject: maxWordCountPerProject ?? DEFAULT_MAX_WORD_COUNT,
        assignTranslator: assignTranslator ?? true,
        featureIds: resolvedFeatureIds.sort((left, right) => left - right),
        featureNames: resolvedFeatureNames,
        errors,
      };
    });

  const countsByName = new Map<string, number>();
  for (const row of rows) {
    if (!row.name) {
      continue;
    }
    const key = row.name.toLowerCase();
    countsByName.set(key, (countsByName.get(key) ?? 0) + 1);
  }

  return rows.map((row) => {
    const nextErrors = [...row.errors];
    if (row.name && (countsByName.get(row.name.toLowerCase()) ?? 0) > 1) {
      nextErrors.push('Duplicate automation name in batch');
    }
    return { ...row, errors: nextErrors };
  });
}

export function AdminReviewAutomationBatchPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [input, setInput] = useState('');
  const [prefillMode, setPrefillMode] = useState<'merge' | 'replace'>('merge');
  const [statusNotice, setStatusNotice] = useState<{
    kind: 'success' | 'error';
    message: string;
  } | null>(null);

  const reviewAutomationsQuery = useQuery({
    queryKey: ['review-automations', 'batch-source'],
    queryFn: fetchReviewAutomationOptions,
    enabled: isAdmin,
    staleTime: 30_000,
  });
  const batchExportQuery = useQuery({
    queryKey: ['review-automations', 'batch-export'],
    queryFn: fetchReviewAutomationBatchExport,
    enabled: isAdmin,
    staleTime: 30_000,
  });
  const reviewFeaturesQuery = useQuery({
    queryKey: ['review-features', 'options'],
    queryFn: fetchReviewFeatureOptions,
    enabled: isAdmin,
    staleTime: 30_000,
  });
  const teamsQuery = useQuery({
    queryKey: ['teams', 'review-automation-batch'],
    queryFn: fetchTeams,
    enabled: isAdmin,
    staleTime: 30_000,
  });

  const batchMutation = useMutation({
    mutationFn: batchUpsertReviewAutomations,
    onSuccess: async (result) => {
      await queryClient.invalidateQueries({ queryKey: ['review-automations'] });
      setStatusNotice({
        kind: 'success',
        message: `Batch applied: ${result.createdCount} created, ${result.updatedCount} updated.`,
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to apply batch update.',
      });
    },
  });

  const featureIdsByName = useMemo(() => {
    const next = new Map<string, number>();
    for (const feature of reviewFeaturesQuery.data ?? []) {
      next.set(feature.name.toLowerCase(), feature.id);
    }
    return next;
  }, [reviewFeaturesQuery.data]);

  const featureDisplayNamesByName = useMemo(() => {
    const next = new Map<string, string>();
    for (const feature of reviewFeaturesQuery.data ?? []) {
      next.set(feature.name.toLowerCase(), feature.name);
    }
    return next;
  }, [reviewFeaturesQuery.data]);

  const teamIdsByName = useMemo(() => {
    const next = new Map<string, number>();
    for (const team of teamsQuery.data ?? []) {
      next.set(team.name.toLowerCase(), team.id);
    }
    return next;
  }, [teamsQuery.data]);

  const teamDisplayNamesByName = useMemo(() => {
    const next = new Map<string, string>();
    for (const team of teamsQuery.data ?? []) {
      next.set(team.name.toLowerCase(), team.name);
    }
    return next;
  }, [teamsQuery.data]);

  const existingAutomationsByName = useMemo(() => {
    const next = new Map<string, { id: number }>();
    for (const automation of reviewAutomationsQuery.data ?? []) {
      next.set(automation.name.toLowerCase(), { id: automation.id });
    }
    return next;
  }, [reviewAutomationsQuery.data]);

  const parsedRows = useMemo(
    () =>
      parseBatchInput(
        input,
        featureIdsByName,
        featureDisplayNamesByName,
        existingAutomationsByName,
        teamIdsByName,
        teamDisplayNamesByName,
      ),
    [
      existingAutomationsByName,
      featureDisplayNamesByName,
      featureIdsByName,
      input,
      teamDisplayNamesByName,
      teamIdsByName,
    ],
  );

  const validRows = parsedRows.filter((row) => row.errors.length === 0);
  const invalidRows = parsedRows.filter((row) => row.errors.length > 0);
  const isSourceLoading =
    reviewAutomationsQuery.isLoading || reviewFeaturesQuery.isLoading || teamsQuery.isLoading;
  const sourceError =
    (reviewAutomationsQuery.error instanceof Error && reviewAutomationsQuery.error.message) ||
    (reviewFeaturesQuery.error instanceof Error && reviewFeaturesQuery.error.message) ||
    (teamsQuery.error instanceof Error && teamsQuery.error.message) ||
    null;
  const loadExistingError =
    (batchExportQuery.error instanceof Error && batchExportQuery.error.message) || null;
  const existingInputNames = useMemo(
    () => new Set(parsedRows.map((row) => row.name.toLowerCase()).filter(Boolean)),
    [parsedRows],
  );

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  const handleApply = () => {
    if (validRows.length === 0) {
      setStatusNotice({ kind: 'error', message: 'Paste batch rows first.' });
      return;
    }
    if (invalidRows.length > 0) {
      setStatusNotice({ kind: 'error', message: 'Fix batch input errors before applying.' });
      return;
    }
    batchMutation.mutate({
      rows: validRows.map((row) => ({
        id: row.id ?? null,
        name: row.name,
        enabled: row.enabled,
        cronExpression: row.cronExpression,
        timeZone: row.timeZone,
        teamId: row.teamId,
        dueDateOffsetDays: row.dueDateOffsetDays,
        maxWordCountPerProject: row.maxWordCountPerProject,
        assignTranslator: row.assignTranslator,
        featureIds: row.featureIds,
      })),
    });
  };

  const applyPrefillRows = (
    rows: Array<{
      name: string;
      enabled: boolean;
      cronExpression: string;
      timeZone: string;
      teamName: string | null;
      dueDateOffsetDays: number;
      maxWordCountPerProject: number;
      featureNames: string[];
    }>,
    options?: { emptyMessage: string; sourceLabel: string },
  ) => {
    if (rows.length === 0) {
      setStatusNotice({
        kind: 'error',
        message: options?.emptyMessage ?? 'Nothing is available to prefill.',
      });
      return;
    }

    const rowsToLoad =
      prefillMode === 'merge'
        ? rows.filter((row) => !existingInputNames.has(row.name.toLowerCase()))
        : rows;

    if (rowsToLoad.length === 0) {
      setStatusNotice({
        kind: 'success',
        message: `All ${options?.sourceLabel ?? 'prefill'} rows are already present in the batch editor.`,
      });
      return;
    }

    const nextText = rowsToLoad.map((row) => formatBatchExportRow(row)).join('\n');
    setInput((previous) => {
      if (prefillMode === 'replace' || !previous.trim()) {
        return nextText;
      }
      return `${previous.trimEnd()}\n${nextText}`;
    });
    setStatusNotice({
      kind: 'success',
      message:
        prefillMode === 'replace'
          ? `Replaced with ${rowsToLoad.length} ${options?.sourceLabel ?? 'prefill'} row${rowsToLoad.length === 1 ? '' : 's'}.`
          : `Merged ${rowsToLoad.length} ${options?.sourceLabel ?? 'prefill'} row${rowsToLoad.length === 1 ? '' : 's'}.`,
    });
  };

  const handlePrefillFromExistingAutomations = () => {
    applyPrefillRows(batchExportQuery.data ?? [], {
      emptyMessage: 'No existing review automations are available to prefill.',
      sourceLabel: 'existing automation',
    });
  };

  const handlePrefillFromReviewFeatureRoster = () => {
    applyPrefillRows(
      (reviewFeaturesQuery.data ?? []).map((feature) => ({
        name: feature.name,
        enabled: false,
        cronExpression: DEFAULT_REVIEW_AUTOMATION_CRON_EXPRESSION,
        timeZone: DEFAULT_REVIEW_AUTOMATION_TIME_ZONE,
        teamName: '',
        assignTranslator: true,
        dueDateOffsetDays: 1,
        maxWordCountPerProject: DEFAULT_MAX_WORD_COUNT,
        featureNames: [feature.name],
      })),
      {
        emptyMessage: 'No review features are available to prefill.',
        sourceLabel: 'review feature roster',
      },
    );
  };

  return (
    <div className="user-batch-page">
      <SettingsSubpageHeader
        backTo="/settings/system/review-automations"
        backLabel="Back to review automations"
        context="Settings > Review automations"
        title="Batch update review automations"
      />

      <div className="user-batch-page__content">
        <section className="user-batch-page__grid review-automation-batch-page__grid">
          <div className="review-automation-batch-page__editor-column">
            {isSourceLoading ? (
              <div className="user-batch-page__summary">Loading review automation references…</div>
            ) : null}
            {sourceError ? (
              <div className="user-batch-page__summary user-batch-page__summary--error">
                {sourceError}
              </div>
            ) : null}
            {loadExistingError ? (
              <div className="user-batch-page__summary user-batch-page__summary--error">
                {loadExistingError}
              </div>
            ) : null}
            <div className="review-automation-batch-page__batch-controls">
              <div
                className="review-automation-batch-page__mode-toggle"
                role="group"
                aria-label="Batch prefill mode"
              >
                <button
                  type="button"
                  className={`review-automation-batch-page__mode-option${
                    prefillMode === 'merge' ? ' is-active' : ''
                  }`}
                  onClick={() => {
                    setPrefillMode('merge');
                    setStatusNotice(null);
                  }}
                >
                  Merge
                </button>
                <button
                  type="button"
                  className={`review-automation-batch-page__mode-option${
                    prefillMode === 'replace' ? ' is-active' : ''
                  }`}
                  onClick={() => {
                    setPrefillMode('replace');
                    setStatusNotice(null);
                  }}
                >
                  Replace
                </button>
              </div>
              <button
                type="button"
                className="settings-button settings-button--ghost review-automation-batch-page__prefill-button"
                onClick={handlePrefillFromExistingAutomations}
                disabled={batchExportQuery.isLoading}
              >
                Prefill from existing automations
              </button>
              <button
                type="button"
                className="settings-button settings-button--ghost review-automation-batch-page__prefill-button"
                onClick={handlePrefillFromReviewFeatureRoster}
                disabled={reviewFeaturesQuery.data == null}
              >
                Prefill from review feature roster
              </button>
            </div>
            <div className="user-batch-page__field user-batch-page__input">
              <textarea
                className="user-batch-page__textarea review-automation-batch-page__textarea"
                value={input}
                onChange={(event) => {
                  setInput(event.target.value);
                  setStatusNotice(null);
                }}
                placeholder={EXAMPLE_INPUT}
                rows={12}
              />
            </div>
          </div>
          <aside
            className="review-automation-batch-page__docs-column"
            aria-label="Batch format help"
          >
            <div className="review-automation-batch-page__docs-title">Format</div>
            <pre className="user-batch-page__example-code">{EXAMPLE_INPUT}</pre>
            <ul className="user-batch-page__intro-list review-automation-batch-page__docs-list">
              <li>
                `name | enabled|disabled | cron | timezone | team | assign-translator|no-translator
                | due-date-offset-days | max-word-count | feature-a, feature-b`
              </li>
              <li>Blank status defaults to enabled.</li>
              <li>Blank timezone defaults to UTC.</li>
              <li>Team name must match an existing enabled team.</li>
              <li>
                Existing rows without the translator assignment column still default to assign.
              </li>
              <li>Blank due date offset defaults to 1 day.</li>
              <li>Blank max word count defaults to 2000.</li>
              <li>Review feature names can be comma- or semicolon-separated.</li>
              <li>Merge skips automation names already present in the editor.</li>
            </ul>
          </aside>
        </section>

        <section className="user-batch-page__section user-batch-page__section--full">
          <div className="user-batch-page__summary">
            {parsedRows.length} row{parsedRows.length === 1 ? '' : 's'} parsed, {validRows.length}{' '}
            valid, {invalidRows.length} invalid.
          </div>
          {statusNotice ? (
            <div
              className={`user-batch-page__summary${
                statusNotice.kind === 'error' ? ' user-batch-page__summary--error' : ''
              }`}
            >
              {statusNotice.message}
            </div>
          ) : null}
          <div className="user-batch-page__preview">
            <div className="user-batch-page__preview-header">
              <div>Line</div>
              <div>Action</div>
              <div>Name</div>
              <div>Status</div>
              <div>Cron</div>
              <div>TZ</div>
              <div>Team</div>
              <div>Translator</div>
              <div>Due in</div>
              <div>Max size</div>
              <div>Features</div>
              <div>Errors</div>
            </div>
            {parsedRows.length > 0 ? (
              parsedRows.map((row) => (
                <div key={row.lineNumber} className="user-batch-page__preview-row">
                  <div className="user-batch-page__cell--muted">{row.lineNumber}</div>
                  <div>{row.action}</div>
                  <div>{row.name || '—'}</div>
                  <div>{row.enabled ? 'Enabled' : 'Disabled'}</div>
                  <div className="user-batch-page__cell--muted">{row.cronExpression || '—'}</div>
                  <div className="user-batch-page__cell--muted">{row.timeZone}</div>
                  <div className="user-batch-page__cell--muted">{row.teamName || '—'}</div>
                  <div className="user-batch-page__cell--muted">
                    {row.assignTranslator ? 'Assign' : 'Skip'}
                  </div>
                  <div className="user-batch-page__cell--muted">{row.dueDateOffsetDays}d</div>
                  <div className="user-batch-page__cell--muted">{row.maxWordCountPerProject}</div>
                  <div className="user-batch-page__cell--muted">
                    {row.featureNames.length ? row.featureNames.join(', ') : 'No review features'}
                  </div>
                  <div>
                    {row.errors.length > 0 ? (
                      <span className="user-batch-page__error">{row.errors.join(', ')}</span>
                    ) : (
                      <span className="user-batch-page__cell--muted">Ready</span>
                    )}
                  </div>
                </div>
              ))
            ) : (
              <div className="user-batch-page__empty">
                Paste review automation rows to see a preview.
              </div>
            )}
          </div>
        </section>

        <div className="user-batch-page__actions">
          <button
            type="button"
            className="settings-button settings-button--ghost"
            onClick={() => void navigate('/settings/system/review-automations')}
          >
            Back
          </button>
          <button
            type="button"
            className="settings-button settings-button--primary user-batch-page__create"
            onClick={handleApply}
            disabled={batchMutation.isPending || parsedRows.length === 0 || isSourceLoading}
          >
            Apply batch
          </button>
        </div>
      </div>
    </div>
  );
}
