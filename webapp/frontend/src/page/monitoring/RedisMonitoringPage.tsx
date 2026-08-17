import '../settings/settings-page.css';
import './monitoring-page.css';

import { useCallback, useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';

import { fetchRedisSnapshot, type RedisSnapshot, runRedisProbe } from '../../api/monitoring';
import { useUser } from '../../hooks/useUser';

function getErrorMessage(error: unknown) {
  return error instanceof Error && error.message.trim() ? error.message : 'Failed to check Redis.';
}

function formatStatus(status: RedisSnapshot['status']) {
  switch (status) {
    case 'NOT_CONFIGURED':
      return 'Not configured';
    case 'READY':
      return 'Ready';
    case 'UNAVAILABLE':
      return 'Unavailable';
  }
}

function formatUptime(seconds: number | null) {
  if (seconds === null) {
    return 'Unavailable';
  }

  const days = Math.floor(seconds / 86_400);
  const hours = Math.floor((seconds % 86_400) / 3_600);
  const minutes = Math.floor((seconds % 3_600) / 60);
  if (days > 0) {
    return `${days}d ${hours}h`;
  }
  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  return `${minutes}m ${seconds % 60}s`;
}

function formatBytes(bytes: number | null) {
  if (bytes === null) {
    return 'Unavailable';
  }
  if (bytes === 0) {
    return 'Unlimited';
  }
  return new Intl.NumberFormat(undefined, {
    style: 'unit',
    unit: 'megabyte',
    maximumFractionDigits: 1,
  }).format(bytes / 1_048_576);
}

export function RedisMonitoringPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const [snapshot, setSnapshot] = useState<RedisSnapshot | null>(null);
  const [loading, setLoading] = useState(false);
  const [probing, setProbing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadStatus = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setSnapshot(await fetchRedisSnapshot());
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
      setSnapshot(await runRedisProbe());
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
        <h1>Redis</h1>
      </div>
      <p className="settings-page__lead">
        Inspect the Redis instance and verify connectivity without changing existing storage.
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
              <span>{snapshot.endpoint}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Database</span>
              <span>{snapshot.database}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>TLS</span>
              <span>{snapshot.ssl ? 'Enabled' : 'Disabled'}</span>
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

      {snapshot?.metrics ? (
        <section className="settings-card">
          <div className="settings-card__header">
            <h2>Instance</h2>
          </div>
          <div className="monitoring-page__overview">
            <div className="monitoring-page__overview-row">
              <span>Redis version</span>
              <span>{snapshot.metrics.version || 'Unavailable'}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Uptime</span>
              <span>{formatUptime(snapshot.metrics.uptimeSeconds)}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Used memory</span>
              <span>
                {snapshot.metrics.usedMemoryHuman || formatBytes(snapshot.metrics.usedMemoryBytes)}
              </span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Memory limit</span>
              <span>{formatBytes(snapshot.metrics.maxMemoryBytes)}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Connected clients</span>
              <span>{snapshot.metrics.connectedClients ?? 'Unavailable'}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Keys in database {snapshot.database}</span>
              <span>{snapshot.metrics.keyCount}</span>
            </div>
          </div>
        </section>
      ) : null}

      {snapshot?.checks.length ? (
        <section className="settings-card">
          <div className="settings-card__header">
            <h2>Checks</h2>
          </div>
          <div className="monitoring-page__table-wrap">
            <table className="monitoring-page__table" aria-label="Redis checks">
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
    </div>
  );
}
