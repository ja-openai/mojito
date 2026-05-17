import './settings-page.css';

import { useMutation } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Navigate } from 'react-router-dom';

import {
  type BulkTranslationAcceptRequest,
  type BulkTranslationAcceptResponse,
  dryRunBulkTranslationAccept,
  executeBulkTranslationAccept,
} from '../../api/temporary-bulk-translation-accept';
import { RepositoryMultiSelect } from '../../components/RepositoryMultiSelect';
import { useUser } from '../../components/RequireUser';
import { useRepositories } from '../../hooks/useRepositories';
import {
  useRepositorySelection,
  useRepositorySelectionOptions,
} from '../../utils/repositorySelection';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

type Selector = BulkTranslationAcceptRequest['selector'];

const DEFAULT_SELECTOR: Selector = 'PHRASE_IMPORTED_NEEDS_REVIEW';

function getDefaultCreatedBeforeDate() {
  const date = new Date();
  date.setDate(date.getDate() - 14);
  return date.toISOString().slice(0, 10);
}

function getSelectorLabel(selector: Selector) {
  switch (selector) {
    case 'PHRASE_IMPORTED_NEEDS_REVIEW':
      return 'Phrase imported and needs review';
    case 'NEEDS_REVIEW_OLDER_THAN':
      return 'Needs review older than date';
  }
}

export function AdminTemporaryBulkTranslationAcceptPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const { data: repositories } = useRepositories();
  const repositoryOptions = useRepositorySelectionOptions(repositories ?? []);
  const { selectedIds, onChangeSelection } = useRepositorySelection({
    options: repositoryOptions,
  });
  const [selector, setSelector] = useState<Selector>(DEFAULT_SELECTOR);
  const [createdBeforeDate, setCreatedBeforeDate] = useState<string>(getDefaultCreatedBeforeDate);
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; message: string } | null>(null);
  const [lastResult, setLastResult] = useState<{
    mode: 'dry-run' | 'execute';
    response: BulkTranslationAcceptResponse;
  } | null>(null);

  const isOlderThanSelector = selector === 'NEEDS_REVIEW_OLDER_THAN';
  const selectedRepositoryNames = useMemo(() => {
    const selectedSet = new Set(selectedIds);
    return repositoryOptions
      .filter((repository) => selectedSet.has(repository.id))
      .map((repository) => repository.name);
  }, [repositoryOptions, selectedIds]);

  const buildPayload = (): BulkTranslationAcceptRequest => ({
    selector,
    repositoryIds: selectedIds,
    createdBeforeDate: isOlderThanSelector ? createdBeforeDate : null,
  });

  const dryRunMutation = useMutation({
    mutationFn: dryRunBulkTranslationAccept,
    onSuccess: (response) => {
      setNotice({
        kind: 'success',
        message: `Dry run matched ${response.totalCount} current variants.`,
      });
      setLastResult({ mode: 'dry-run', response });
    },
    onError: (error: Error) => {
      setNotice({
        kind: 'error',
        message: error.message || 'Dry run failed.',
      });
    },
  });

  const executeMutation = useMutation({
    mutationFn: executeBulkTranslationAccept,
    onSuccess: (response) => {
      setNotice({
        kind: 'success',
        message: `Executed bulk accept for ${response.totalCount} current variants.`,
      });
      setLastResult({ mode: 'execute', response });
    },
    onError: (error: Error) => {
      setNotice({
        kind: 'error',
        message: error.message || 'Bulk accept failed.',
      });
    },
  });

  if (!isAdmin) {
    return <Navigate to="/settings/me" replace />;
  }

  const validateInputs = () => {
    if (selectedIds.length === 0) {
      setNotice({ kind: 'error', message: 'Select at least one repository.' });
      return false;
    }
    if (isOlderThanSelector && !createdBeforeDate) {
      setNotice({ kind: 'error', message: 'Choose a cutoff date.' });
      return false;
    }
    return true;
  };

  const handleDryRun = () => {
    if (!validateInputs()) {
      return;
    }
    setNotice(null);
    dryRunMutation.mutate(buildPayload());
  };

  const handleExecute = () => {
    if (!validateInputs()) {
      return;
    }
    const confirmed = window.confirm(
      `Bulk accept ${getSelectorLabel(selector).toLowerCase()} for ${selectedIds.length} selected repos?`,
    );
    if (!confirmed) {
      return;
    }
    setNotice(null);
    executeMutation.mutate(buildPayload());
  };

  const isWorking = dryRunMutation.isPending || executeMutation.isPending;

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to system settings"
        context="System settings"
        title="Temporary bulk translation accept"
      />

      <div className="settings-page">
        <section className="settings-card">
          <div className="settings-card__header">
            <h2>Scope</h2>
          </div>
          <p className="settings-page__hint">
            Temporary admin-only cleanup tool. Remove this page and its paired backend files after
            the one-off bulk accept is complete.
          </p>
          <div className="settings-grid settings-grid--two-column">
            <label className="settings-field">
              <span className="settings-field__label">Selector</span>
              <select
                className="settings-input"
                value={selector}
                onChange={(event) => {
                  setSelector(event.target.value as Selector);
                  setLastResult(null);
                  setNotice(null);
                }}
                disabled={isWorking}
              >
                <option value="PHRASE_IMPORTED_NEEDS_REVIEW">
                  Phrase imported and needs review
                </option>
                <option value="NEEDS_REVIEW_OLDER_THAN">Needs review older than date</option>
              </select>
            </label>

            <label className="settings-field">
              <span className="settings-field__label">Created Before</span>
              <input
                className="settings-input"
                type="date"
                value={createdBeforeDate}
                onChange={(event) => {
                  setCreatedBeforeDate(event.target.value);
                  setLastResult(null);
                  setNotice(null);
                }}
                disabled={!isOlderThanSelector || isWorking}
              />
            </label>
          </div>

          <label className="settings-field">
            <span className="settings-field__label">Repositories</span>
            <RepositoryMultiSelect
              className="settings-repository-select"
              options={repositoryOptions}
              selectedIds={selectedIds}
              onChange={(next) => {
                onChangeSelection(next);
                setLastResult(null);
                setNotice(null);
              }}
              disabled={isWorking}
            />
          </label>

          <p className="settings-note">
            {selectedRepositoryNames.length === 0
              ? 'No repositories selected yet.'
              : `${selectedRepositoryNames.length} repositories selected.`}
          </p>

          <div className="settings-actions">
            <button
              type="button"
              className="settings-button"
              onClick={handleDryRun}
              disabled={isWorking}
            >
              {dryRunMutation.isPending ? 'Running dry run…' : 'Dry run'}
            </button>
            <button
              type="button"
              className="settings-button settings-button--primary"
              onClick={handleExecute}
              disabled={isWorking}
            >
              {executeMutation.isPending ? 'Executing…' : 'Execute'}
            </button>
          </div>

          {notice ? (
            <p className={`settings-hint${notice.kind === 'error' ? ' is-error' : ''}`}>
              {notice.message}
            </p>
          ) : null}
        </section>

        <section className="settings-card">
          <div className="settings-card__header">
            <h2>Result</h2>
          </div>
          {lastResult ? (
            <>
              <p className="settings-page__hint">
                {lastResult.mode === 'dry-run' ? 'Dry run' : 'Execute'} total:{' '}
                {lastResult.response.totalCount}
              </p>
              <div className="settings-table-wrapper">
                <table className="settings-table">
                  <thead>
                    <tr>
                      <th>Repository</th>
                      <th>Matched Count</th>
                    </tr>
                  </thead>
                  <tbody>
                    {lastResult.response.repositoryCounts.map((row) => (
                      <tr key={row.repositoryId}>
                        <td>{row.repositoryName}</td>
                        <td>{row.matchedCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          ) : (
            <p className="settings-page__hint">
              Run a dry run to inspect counts by repository before executing.
            </p>
          )}
        </section>
      </div>
    </div>
  );
}
