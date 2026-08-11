import './settings-page.css';
import './admin-blob-cleanup-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Navigate } from 'react-router-dom';

import {
  type ApiBlobCleanupSettings,
  type ApiBlobCleanupSettingsUpdate,
  fetchBlobCleanupSettings,
  startBlobCleanup,
  stopBlobCleanup,
  updateBlobCleanupSettings,
} from '../../api/blob-cleanup';
import { useUser } from '../../hooks/useUser';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

const QUERY_KEY = ['blob-cleanup'];
const STALE_PROGRESS_THRESHOLD_MS = 5 * 60 * 1000;

function formatDate(value: string | null): string {
  if (!value) {
    return 'Never';
  }
  return new Date(value).toLocaleString();
}

type CleanupEditorProps = {
  settings: ApiBlobCleanupSettings;
  busy: boolean;
  onSave: (update: ApiBlobCleanupSettingsUpdate) => void;
  onStart: () => void;
  onStop: () => void;
};

function CleanupEditor({ settings, onSave, onStart, onStop, busy }: CleanupEditorProps) {
  const savedSettings = useMemo<ApiBlobCleanupSettingsUpdate>(
    () => ({
      enabled: settings.enabled,
      batchSize: settings.batchSize,
      maxBatchesPerRun: settings.maxBatchesPerRun,
      pauseMillis: settings.pauseMillis,
      maxRetries: settings.maxRetries,
    }),
    [
      settings.enabled,
      settings.batchSize,
      settings.maxBatchesPerRun,
      settings.pauseMillis,
      settings.maxRetries,
    ],
  );
  const [draft, setDraft] = useState<ApiBlobCleanupSettingsUpdate>(savedSettings);

  useEffect(() => {
    setDraft(savedSettings);
  }, [savedSettings]);

  const lastProgressDate = settings.lastProgressDate ?? settings.lastStartedDate;
  const stalled =
    settings.status === 'RUNNING' &&
    lastProgressDate !== null &&
    Date.now() - new Date(lastProgressDate).getTime() > STALE_PROGRESS_THRESHOLD_MS;
  const running =
    !stalled && (settings.status === 'RUNNING' || settings.status === 'STOP_REQUESTED');
  const displayedStatus = stalled ? 'STALLED' : settings.status;
  const dirty = JSON.stringify(draft) !== JSON.stringify(savedSettings);

  function setNumber(field: keyof ApiBlobCleanupSettingsUpdate, value: string) {
    const parsed = Number.parseInt(value, 10);
    setDraft((current) => ({ ...current, [field]: Number.isNaN(parsed) ? 0 : parsed }));
  }

  return (
    <section className="settings-card blob-cleanup" aria-label="Global database blob cleanup">
      <div className="blob-cleanup__header">
        <div>
          <strong>Global expiration cleanup</strong>
          <p>Deletes expired temporary blobs from every database-backed blob family.</p>
        </div>
        <label className="blob-cleanup__enabled">
          <input
            type="checkbox"
            checked={draft.enabled}
            disabled={busy}
            onChange={(event) => {
              setDraft((current) => ({ ...current, enabled: event.target.checked }));
            }}
          />
          Enabled
        </label>
        <span
          className={`blob-cleanup__status blob-cleanup__status--${displayedStatus.toLowerCase()}`}
        >
          {displayedStatus.replace(/_/g, ' ')}
        </span>
      </div>

      <div className="blob-cleanup__fields">
        <label className="settings-field">
          <span className="settings-field__label">Batch size</span>
          <input
            className="settings-input"
            type="number"
            min={1}
            max={5000}
            value={draft.batchSize}
            disabled={busy}
            onChange={(event) => {
              setNumber('batchSize', event.target.value);
            }}
          />
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Maximum batches per run</span>
          <input
            className="settings-input"
            type="number"
            min={1}
            value={draft.maxBatchesPerRun}
            disabled={busy}
            onChange={(event) => {
              setNumber('maxBatchesPerRun', event.target.value);
            }}
          />
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Pause between batches (ms)</span>
          <input
            className="settings-input"
            type="number"
            min={0}
            max={60000}
            value={draft.pauseMillis}
            disabled={busy}
            onChange={(event) => {
              setNumber('pauseMillis', event.target.value);
            }}
          />
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Lock retries</span>
          <input
            className="settings-input"
            type="number"
            min={0}
            max={20}
            value={draft.maxRetries}
            disabled={busy}
            onChange={(event) => {
              setNumber('maxRetries', event.target.value);
            }}
          />
        </label>
      </div>

      <div className="blob-cleanup__progress">
        <div>
          <span>Last run</span>
          <strong>{formatDate(settings.lastStartedDate)}</strong>
        </div>
        <div>
          <span>Last progress</span>
          <strong>{formatDate(settings.lastProgressDate)}</strong>
        </div>
        <div>
          <span>Deleted this run</span>
          <strong>{settings.lastDeletedCount.toLocaleString()}</strong>
        </div>
        <div>
          <span>Total deleted</span>
          <strong>{settings.totalDeletedCount.toLocaleString()}</strong>
        </div>
        <div>
          <span>Finished</span>
          <strong>{formatDate(settings.lastFinishedDate)}</strong>
        </div>
      </div>

      {settings.lastError ? (
        <p className="blob-cleanup__error" role="alert">
          {settings.lastError}
        </p>
      ) : null}

      <div className="blob-cleanup__actions">
        <button
          type="button"
          className="settings-button settings-button--primary"
          disabled={!dirty || busy}
          onClick={() => {
            onSave(draft);
          }}
        >
          Save
        </button>
        {running ? (
          <button type="button" className="settings-button" disabled={busy} onClick={onStop}>
            Stop
          </button>
        ) : (
          <button
            type="button"
            className="settings-button"
            disabled={busy || dirty}
            onClick={onStart}
          >
            {stalled ? 'Restart stalled run' : 'Run now'}
          </button>
        )}
      </div>
    </section>
  );
}

export function AdminBlobCleanupPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const settingsQuery = useQuery({
    queryKey: QUERY_KEY,
    queryFn: fetchBlobCleanupSettings,
    enabled: isAdmin,
    refetchInterval: (query) =>
      query.state.data?.status === 'RUNNING' || query.state.data?.status === 'STOP_REQUESTED'
        ? 2000
        : 15000,
  });

  const mutation = useMutation({
    mutationFn: async (action: () => Promise<unknown>) => action(),
    onSuccess: async () => {
      setError(null);
      await queryClient.invalidateQueries({ queryKey: QUERY_KEY });
    },
    onError: (failure: Error) => {
      setError(failure.message);
    },
  });

  if (!isAdmin) {
    return <Navigate to="/settings/me" replace />;
  }

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to system settings"
        context="System settings"
        title="Database blob cleanup"
      />

      <div className="settings-page blob-cleanup-page">
        <p className="settings-page__hint">
          Manage expiration cleanup across all database blobs. Permanent blobs never expire and are
          not deleted.
        </p>

        {error || settingsQuery.error ? (
          <p className="blob-cleanup__error" role="alert">
            {error ?? settingsQuery.error?.message}
          </p>
        ) : null}

        {settingsQuery.data ? (
          <CleanupEditor
            settings={settingsQuery.data}
            busy={mutation.isPending}
            onSave={(update) => {
              mutation.mutate(() => updateBlobCleanupSettings(update));
            }}
            onStart={() => {
              mutation.mutate(startBlobCleanup);
            }}
            onStop={() => {
              mutation.mutate(stopBlobCleanup);
            }}
          />
        ) : null}
      </div>
    </div>
  );
}
