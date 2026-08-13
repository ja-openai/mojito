import './settings-page.css';
import './admin-blob-cleanup-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Navigate } from 'react-router-dom';

import {
  type ApiBlobCleanupPolicy,
  type ApiBlobCleanupPolicyUpdate,
  createBlobCleanupPolicy,
  deleteBlobCleanupPolicy,
  fetchBlobCleanupPolicies,
  startBlobCleanupPolicy,
  stopBlobCleanupPolicy,
  updateBlobCleanupPolicy,
} from '../../api/blob-cleanup-policies';
import { useUser } from '../../hooks/useUser';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

const QUERY_KEY = ['blob-cleanup-policies'];

const DEFAULT_POLICY: ApiBlobCleanupPolicyUpdate = {
  prefix: 'pollable_task/',
  enabled: false,
  retentionDays: 3,
  batchSize: 250,
  maxBatchesPerRun: 0,
  pauseMillis: 250,
  maxRetries: 5,
};

function formatDate(value: string | null): string {
  if (!value) {
    return 'Never';
  }
  return new Date(value).toLocaleString();
}

type PolicyEditorProps = {
  policy: ApiBlobCleanupPolicy;
  onDelete: (id: number) => void;
  onSave: (id: number, update: ApiBlobCleanupPolicyUpdate) => void;
  onStart: (id: number) => void;
  onStop: (id: number) => void;
  busy: boolean;
};

function PolicyEditor({ policy, onDelete, onSave, onStart, onStop, busy }: PolicyEditorProps) {
  const savedPolicy = useMemo<ApiBlobCleanupPolicyUpdate>(
    () => ({
      prefix: policy.prefix,
      enabled: policy.enabled,
      retentionDays: policy.retentionDays,
      batchSize: policy.batchSize,
      maxBatchesPerRun: policy.maxBatchesPerRun,
      pauseMillis: policy.pauseMillis,
      maxRetries: policy.maxRetries,
    }),
    [
      policy.prefix,
      policy.enabled,
      policy.retentionDays,
      policy.batchSize,
      policy.maxBatchesPerRun,
      policy.pauseMillis,
      policy.maxRetries,
    ],
  );
  const [draft, setDraft] = useState<ApiBlobCleanupPolicyUpdate>(savedPolicy);

  useEffect(() => {
    setDraft(savedPolicy);
  }, [savedPolicy]);

  const running = policy.status === 'RUNNING' || policy.status === 'STOP_REQUESTED';
  const dirty = JSON.stringify(draft) !== JSON.stringify(savedPolicy);

  function setNumber(field: keyof ApiBlobCleanupPolicyUpdate, value: string) {
    const parsed = Number(value);
    setDraft((current) => ({ ...current, [field]: Number.isFinite(parsed) ? parsed : 0 }));
  }

  return (
    <section className="settings-card blob-cleanup-policy" aria-label={`${policy.prefix} policy`}>
      <div className="blob-cleanup-policy__header">
        <label className="settings-field blob-cleanup-policy__prefix">
          <span className="settings-field__label">Prefix</span>
          <input
            className="settings-input"
            value={draft.prefix}
            disabled={running || busy}
            onChange={(event) => {
              setDraft((current) => ({ ...current, prefix: event.target.value }));
            }}
          />
        </label>
        <label className="blob-cleanup-policy__enabled">
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
          className={`blob-cleanup-policy__status blob-cleanup-policy__status--${policy.status.toLowerCase()}`}
        >
          {policy.status.replace(/_/g, ' ')}
        </span>
      </div>

      <div className="blob-cleanup-policy__fields">
        <label className="settings-field">
          <span className="settings-field__label">Retention days</span>
          <input
            className="settings-input"
            type="number"
            min={1}
            value={draft.retentionDays}
            disabled={busy}
            onChange={(event) => {
              setNumber('retentionDays', event.target.value);
            }}
          />
        </label>
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
          <span className="settings-field__label">Pause between batches (ms)</span>
          <input
            className="settings-input"
            type="number"
            min={0}
            value={draft.pauseMillis}
            disabled={busy}
            onChange={(event) => {
              setNumber('pauseMillis', event.target.value);
            }}
          />
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Maximum batches (0 = unlimited)</span>
          <input
            className="settings-input"
            type="number"
            min={0}
            value={draft.maxBatchesPerRun}
            disabled={busy}
            onChange={(event) => {
              setNumber('maxBatchesPerRun', event.target.value);
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

      <div className="blob-cleanup-policy__progress">
        <div>
          <span>Last run</span>
          <strong>{formatDate(policy.lastStartedDate)}</strong>
        </div>
        <div>
          <span>Deleted this run</span>
          <strong>{policy.lastDeletedCount.toLocaleString()}</strong>
        </div>
        <div>
          <span>Total deleted</span>
          <strong>{policy.totalDeletedCount.toLocaleString()}</strong>
        </div>
        <div>
          <span>Finished</span>
          <strong>{formatDate(policy.lastFinishedDate)}</strong>
        </div>
      </div>

      {policy.lastError ? (
        <p className="blob-cleanup-policy__error" role="alert">
          {policy.lastError}
        </p>
      ) : null}

      <div className="blob-cleanup-policy__actions">
        <button
          type="button"
          className="settings-button settings-button--primary"
          disabled={!dirty || busy}
          onClick={() => {
            onSave(policy.id, draft);
          }}
        >
          Save
        </button>
        {running ? (
          <button
            type="button"
            className="settings-button"
            disabled={busy}
            onClick={() => {
              onStop(policy.id);
            }}
          >
            Stop
          </button>
        ) : (
          <button
            type="button"
            className="settings-button"
            disabled={busy || dirty}
            onClick={() => {
              onStart(policy.id);
            }}
          >
            Start now
          </button>
        )}
        <button
          type="button"
          className="settings-button blob-cleanup-policy__delete"
          disabled={busy || running}
          onClick={() => {
            onDelete(policy.id);
          }}
        >
          Delete policy
        </button>
      </div>
    </section>
  );
}

export function AdminBlobCleanupPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();
  const [newPrefix, setNewPrefix] = useState(DEFAULT_POLICY.prefix);
  const [error, setError] = useState<string | null>(null);

  const policiesQuery = useQuery({
    queryKey: QUERY_KEY,
    queryFn: fetchBlobCleanupPolicies,
    enabled: isAdmin,
    refetchInterval: (query) =>
      query.state.data?.some(
        (policy) => policy.status === 'RUNNING' || policy.status === 'STOP_REQUESTED',
      )
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

  function perform(action: () => Promise<unknown>) {
    mutation.mutate(action);
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
          Delete expired database blobs by indexed prefix. Each policy drains continuously in small
          committed batches until it is stopped or no eligible blobs remain.
        </p>

        {error || policiesQuery.error ? (
          <p className="blob-cleanup-policy__error" role="alert">
            {error ?? policiesQuery.error?.message}
          </p>
        ) : null}

        <section className="settings-card blob-cleanup-page__create">
          <label className="settings-field">
            <span className="settings-field__label">New cleanup prefix</span>
            <input
              className="settings-input"
              value={newPrefix}
              placeholder="pollable_task/"
              onChange={(event) => {
                setNewPrefix(event.target.value);
              }}
              disabled={mutation.isPending}
            />
          </label>
          <button
            type="button"
            className="settings-button settings-button--primary"
            disabled={mutation.isPending || !newPrefix.trim()}
            onClick={() => {
              perform(() => createBlobCleanupPolicy({ ...DEFAULT_POLICY, prefix: newPrefix }));
            }}
          >
            Add policy
          </button>
        </section>

        {policiesQuery.data?.map((policy) => (
          <PolicyEditor
            key={policy.id}
            policy={policy}
            busy={mutation.isPending}
            onSave={(id, update) => {
              perform(() => updateBlobCleanupPolicy(id, update));
            }}
            onStart={(id) => {
              perform(() => startBlobCleanupPolicy(id));
            }}
            onStop={(id) => {
              perform(() => stopBlobCleanupPolicy(id));
            }}
            onDelete={(id) => {
              if (window.confirm(`Delete the ${policy.prefix} cleanup policy?`)) {
                perform(() => deleteBlobCleanupPolicy(id));
              }
            }}
          />
        ))}

        {!policiesQuery.isLoading && policiesQuery.data?.length === 0 ? (
          <p className="settings-page__hint">No cleanup policies configured.</p>
        ) : null}
      </div>
    </div>
  );
}
