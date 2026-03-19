import './settings-page.css';
import './admin-user-batch-page.css';
import './admin-review-feature-batch-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';

import {
  batchUpsertReviewFeatures,
  fetchReviewFeatureBatchExport,
  fetchReviewFeatureOptions,
} from '../../api/review-features';
import { useUser } from '../../components/RequireUser';
import { useRepositories } from '../../hooks/useRepositories';

type ParsedRow = {
  lineNumber: number;
  raw: string;
  id?: number;
  action: 'create' | 'update';
  name: string;
  enabled: boolean;
  repositoryIds: number[];
  repositoryNames: string[];
  errors: string[];
};

const EXAMPLE_INPUT = `Payments | enabled | repo-a, repo-b
Growth | disabled | repo-c
Accounts | enabled | repo-d, repo-e, repo-f`;

const normalizeFeatureName = (value: string) => value.trim().replace(/\s+/g, ' ');

const formatBatchExportRow = (row: { name: string; enabled: boolean; repositoryNames: string[] }) =>
  `${row.name} | ${row.enabled ? 'enabled' : 'disabled'} | ${row.repositoryNames.join(', ')}`;

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

function parseBatchInput(
  input: string,
  repositoryIdsByName: Map<string, number>,
  repositoryDisplayNamesByName: Map<string, string>,
  existingFeaturesByName: Map<string, { id: number }>,
): ParsedRow[] {
  const rows = input
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line, index) => {
      const parts = line.split('|').map((part) => part.trim());
      const normalizedName = normalizeFeatureName(parts[0] ?? '');
      const enabled = normalizeEnabled(parts[1] ?? '');
      const repositoryNames =
        parts[2]
          ?.split(/[;,]+/)
          .map((part) => part.trim())
          .filter(Boolean) ?? [];

      const errors: string[] = [];
      if (!normalizedName) {
        errors.push('Missing feature name');
      }
      if (enabled == null) {
        errors.push('Invalid enabled flag');
      }

      const resolvedRepositoryIds: number[] = [];
      const resolvedRepositoryNames: string[] = [];
      const seenRepositoryIds = new Set<number>();
      for (const repositoryName of repositoryNames) {
        const key = repositoryName.toLowerCase();
        const repositoryId = repositoryIdsByName.get(key);
        if (repositoryId == null) {
          errors.push(`Unknown repository: ${repositoryName}`);
          continue;
        }
        if (seenRepositoryIds.has(repositoryId)) {
          continue;
        }
        seenRepositoryIds.add(repositoryId);
        resolvedRepositoryIds.push(repositoryId);
        resolvedRepositoryNames.push(repositoryDisplayNamesByName.get(key) ?? repositoryName);
      }

      const existing = normalizedName
        ? existingFeaturesByName.get(normalizedName.toLowerCase())
        : undefined;
      const action: ParsedRow['action'] = existing ? 'update' : 'create';

      return {
        lineNumber: index + 1,
        raw: line,
        id: existing?.id,
        action,
        name: normalizedName,
        enabled: enabled ?? true,
        repositoryIds: resolvedRepositoryIds.sort((left, right) => left - right),
        repositoryNames: resolvedRepositoryNames,
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
      nextErrors.push('Duplicate feature name in batch');
    }
    return { ...row, errors: nextErrors };
  });
}

export function AdminReviewFeatureBatchPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: repositories } = useRepositories();
  const [input, setInput] = useState('');
  const [prefillMode, setPrefillMode] = useState<'merge' | 'replace'>('merge');
  const [statusNotice, setStatusNotice] = useState<{
    kind: 'success' | 'error';
    message: string;
  } | null>(null);

  const reviewFeaturesQuery = useQuery({
    queryKey: ['review-features', 'batch-source'],
    queryFn: fetchReviewFeatureOptions,
    enabled: isAdmin,
    staleTime: 30_000,
  });
  const batchExportQuery = useQuery({
    queryKey: ['review-features', 'batch-export'],
    queryFn: fetchReviewFeatureBatchExport,
    enabled: isAdmin,
    staleTime: 30_000,
  });

  const batchMutation = useMutation({
    mutationFn: batchUpsertReviewFeatures,
    onSuccess: async (result) => {
      await queryClient.invalidateQueries({ queryKey: ['review-features'] });
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

  const repositoryIdsByName = useMemo(() => {
    const next = new Map<string, number>();
    for (const repository of repositories ?? []) {
      next.set(repository.name.toLowerCase(), repository.id);
    }
    return next;
  }, [repositories]);

  const repositoryDisplayNamesByName = useMemo(() => {
    const next = new Map<string, string>();
    for (const repository of repositories ?? []) {
      next.set(repository.name.toLowerCase(), repository.name);
    }
    return next;
  }, [repositories]);

  const existingFeaturesByName = useMemo(() => {
    const next = new Map<string, { id: number }>();
    for (const feature of reviewFeaturesQuery.data ?? []) {
      next.set(feature.name.toLowerCase(), { id: feature.id });
    }
    return next;
  }, [reviewFeaturesQuery.data]);

  const parsedRows = useMemo(
    () =>
      parseBatchInput(
        input,
        repositoryIdsByName,
        repositoryDisplayNamesByName,
        existingFeaturesByName,
      ),
    [existingFeaturesByName, input, repositoryDisplayNamesByName, repositoryIdsByName],
  );

  const validRows = parsedRows.filter((row) => row.errors.length === 0);
  const invalidRows = parsedRows.filter((row) => row.errors.length > 0);
  const isSourceLoading = reviewFeaturesQuery.isLoading || repositories == null;
  const sourceError =
    (reviewFeaturesQuery.error instanceof Error && reviewFeaturesQuery.error.message) || null;
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
        repositoryIds: row.repositoryIds,
      })),
    });
  };

  const applyPrefillRows = (
    rows: Array<{ name: string; enabled: boolean; repositoryNames: string[] }>,
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

    const nextText = rowsToLoad.map(formatBatchExportRow).join('\n');
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

  const handlePrefillFromExistingFeatures = () => {
    applyPrefillRows(batchExportQuery.data ?? [], {
      emptyMessage: 'No existing review features are available to prefill.',
      sourceLabel: 'existing feature',
    });
  };

  const handlePrefillFromRepositoryRoster = () => {
    applyPrefillRows(
      (repositories ?? []).map((repository) => ({
        name: repository.name,
        enabled: true,
        repositoryNames: [repository.name],
      })),
      {
        emptyMessage: 'No repositories are available to prefill.',
        sourceLabel: 'repository roster',
      },
    );
  };

  return (
    <div className="user-batch-page">
      <header className="review-feature-batch-page__topbar">
        <button
          type="button"
          className="review-feature-batch-page__topbar-back"
          onClick={() => void navigate('/settings/admin/review-features')}
          aria-label="Back to review features"
        >
          <span className="review-feature-batch-page__topbar-back-icon" aria-hidden="true">
            ←
          </span>
        </button>
        <h1 className="review-feature-batch-page__topbar-title">Batch update review features</h1>
      </header>

      <div className="user-batch-page__content">
        <section className="user-batch-page__grid review-feature-batch-page__grid">
          <div className="review-feature-batch-page__editor-column">
            {isSourceLoading ? (
              <div className="user-batch-page__summary">Loading review feature references…</div>
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
            <div className="review-feature-batch-page__batch-controls">
              <div
                className="review-feature-batch-page__mode-toggle"
                role="group"
                aria-label="Batch prefill mode"
              >
                <button
                  type="button"
                  className={`review-feature-batch-page__mode-option${
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
                  className={`review-feature-batch-page__mode-option${
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
                className="settings-button settings-button--ghost review-feature-batch-page__prefill-button"
                onClick={handlePrefillFromExistingFeatures}
                disabled={batchExportQuery.isLoading}
              >
                Prefill from existing features
              </button>
              <button
                type="button"
                className="settings-button settings-button--ghost review-feature-batch-page__prefill-button"
                onClick={handlePrefillFromRepositoryRoster}
                disabled={repositories == null}
              >
                Prefill from repository roster
              </button>
            </div>
            <div className="user-batch-page__field user-batch-page__input">
              <textarea
                className="user-batch-page__textarea review-feature-batch-page__textarea"
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
          <aside className="review-feature-batch-page__docs-column" aria-label="Batch format help">
            <div className="review-feature-batch-page__docs-title">Format</div>
            <pre className="user-batch-page__example-code">{EXAMPLE_INPUT}</pre>
            <ul className="user-batch-page__intro-list review-feature-batch-page__docs-list">
              <li>`name | enabled|disabled | repo-a, repo-b`</li>
              <li>Blank status defaults to enabled.</li>
              <li>Repository names can be comma- or semicolon-separated.</li>
              <li>Merge skips feature names already present in the editor.</li>
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
              <div>Repositories</div>
              <div>Errors</div>
            </div>
            {parsedRows.length > 0 ? (
              parsedRows.map((row) => (
                <div key={row.lineNumber} className="user-batch-page__preview-row">
                  <div className="user-batch-page__cell--muted">{row.lineNumber}</div>
                  <div>{row.action}</div>
                  <div>{row.name || '—'}</div>
                  <div>{row.enabled ? 'Enabled' : 'Disabled'}</div>
                  <div className="user-batch-page__cell--muted">
                    {row.repositoryNames.length
                      ? row.repositoryNames.join(', ')
                      : 'No repositories'}
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
                Paste review feature rows to see a preview.
              </div>
            )}
          </div>
        </section>

        <div className="user-batch-page__actions">
          <button
            type="button"
            className="settings-button settings-button--ghost"
            onClick={() => void navigate('/settings/admin/review-features')}
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
