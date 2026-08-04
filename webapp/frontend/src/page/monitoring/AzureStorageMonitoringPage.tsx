import '../settings/settings-page.css';
import './monitoring-page.css';

import { useCallback, useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';

import {
  type AzureStorageSnapshot,
  fetchAzureStorageSnapshot,
  runAzureStorageProbe,
} from '../../api/monitoring';
import { useUser } from '../../hooks/useUser';

function getErrorMessage(error: unknown) {
  return error instanceof Error && error.message.trim()
    ? error.message
    : 'Failed to check Azure Storage.';
}

function formatStatus(status: AzureStorageSnapshot['status']) {
  switch (status) {
    case 'NOT_CONFIGURED':
      return 'Not configured';
    case 'READY':
      return 'Ready';
    case 'UNAVAILABLE':
      return 'Unavailable';
  }
}

export function AzureStorageMonitoringPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const [snapshot, setSnapshot] = useState<AzureStorageSnapshot | null>(null);
  const [loading, setLoading] = useState(false);
  const [probing, setProbing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadStatus = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setSnapshot(await fetchAzureStorageSnapshot());
    } catch (loadError) {
      setError(getErrorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }, []);

  const runProbe = async () => {
    setProbing(true);
    setError(null);
    try {
      setSnapshot(await runAzureStorageProbe());
    } catch (probeError) {
      setError(getErrorMessage(probeError));
    } finally {
      setProbing(false);
    }
  };

  useEffect(() => {
    if (isAdmin) {
      void loadStatus();
    }
  }, [isAdmin, loadStatus]);

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  return (
    <div className="settings-page monitoring-page">
      <div className="settings-page__header">
        <h1>Azure Storage</h1>
      </div>
      <p className="settings-page__lead">
        Verify Azure Blob Storage before moving any existing storage routes.
      </p>

      <section className="settings-card">
        <div className="settings-card__header">
          <h2>Status</h2>
        </div>

        {snapshot ? (
          <div className="monitoring-page__overview">
            <div className="monitoring-page__overview-row">
              <span>Availability</span>
              <span>{formatStatus(snapshot.status)}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Endpoint</span>
              <span>{snapshot.endpoint || 'Not configured'}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Container</span>
              <span>{snapshot.container}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Object prefix</span>
              <span>{snapshot.prefix}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Default backend</span>
              <span>{snapshot.defaultBackend}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Last checked</span>
              <span>{new Date(snapshot.timestamp).toLocaleString()}</span>
            </div>
          </div>
        ) : null}

        <div className="monitoring-page__controls">
          <button
            type="button"
            className="settings-button"
            disabled={loading || probing}
            onClick={() => void loadStatus()}
          >
            {loading ? 'Checking…' : 'Refresh status'}
          </button>
          <button
            type="button"
            className="settings-button settings-button--primary"
            disabled={loading || probing || !snapshot?.enabled}
            onClick={() => void runProbe()}
          >
            {probing ? 'Running probe…' : 'Run write/read/delete probe'}
          </button>
        </div>

        {error ? (
          <p className="settings-hint is-error monitoring-page__error" role="alert">
            {error}
          </p>
        ) : null}
      </section>

      {snapshot?.checks.length ? (
        <section className="settings-card">
          <div className="settings-card__header">
            <h2>Checks</h2>
          </div>
          <div className="monitoring-page__table-wrap">
            <table className="monitoring-page__table" aria-label="Azure Storage checks">
              <thead>
                <tr>
                  <th>Check</th>
                  <th>Result</th>
                  <th>Latency (ms)</th>
                  <th>Details</th>
                </tr>
              </thead>
              <tbody>
                {snapshot.checks.map((check) => (
                  <tr key={check.name}>
                    <td>{check.name}</td>
                    <td>{check.success ? 'Passed' : 'Failed'}</td>
                    <td>{check.latencyMs}</td>
                    <td>{check.message || '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ) : null}

      {snapshot ? (
        <section className="settings-card">
          <div className="settings-card__header">
            <h2>Current storage routing</h2>
          </div>
          <div className="monitoring-page__table-wrap">
            <table className="monitoring-page__table" aria-label="Current storage routing">
              <thead>
                <tr>
                  <th>Prefix</th>
                  <th>Backend</th>
                </tr>
              </thead>
              <tbody>
                {snapshot.routes.map((route) => (
                  <tr key={route.prefix}>
                    <td>{route.prefix}</td>
                    <td>{route.backend}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ) : null}
    </div>
  );
}
