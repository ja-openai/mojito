import '../settings/settings-page.css';
import './monitoring-page.css';

import { useCallback, useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';

import {
  type DbLatencySeries,
  type DbMonitoringSnapshot,
  fetchDbLatencySnapshot,
  MAX_MONITORING_ITERATIONS,
  MIN_MONITORING_ITERATIONS,
} from '../../api/monitoring';
import { useUser } from '../../components/RequireUser';

const DEFAULT_ITERATIONS = 5;
const SERIES_SECTIONS: {
  key: keyof Pick<DbMonitoringSnapshot, 'raw' | 'hibernateHealth' | 'hibernateRepo'>;
  title: string;
}[] = [
  { key: 'raw', title: 'Direct JDBC (select 1)' },
  { key: 'hibernateHealth', title: 'Hibernate (select 1)' },
  { key: 'hibernateRepo', title: 'Hibernate repositories query' },
];

function formatLatency(latency: number) {
  if (!Number.isFinite(latency)) {
    return '-';
  }
  return latency.toFixed(2);
}

function clampIterations(value: string) {
  const parsed = parseInt(value, 10);
  if (!Number.isFinite(parsed)) {
    return DEFAULT_ITERATIONS;
  }
  return Math.max(MIN_MONITORING_ITERATIONS, Math.min(MAX_MONITORING_ITERATIONS, parsed));
}

function getErrorMessage(error: unknown) {
  if (error instanceof Error && error.message.trim()) {
    return error.message;
  }
  return 'Failed to measure latency.';
}

type MonitoringSeriesSectionProps = {
  title: string;
  series: DbLatencySeries;
};

function MonitoringSeriesSection({ title, series }: MonitoringSeriesSectionProps) {
  const metricRows = [
    { label: 'Min', value: series.minLatencyMs },
    { label: 'Max', value: series.maxLatencyMs },
    { label: 'Average', value: series.averageLatencyMs },
  ];

  return (
    <section className="settings-card monitoring-page__series-card">
      <div className="settings-card__header">
        <h2>{title}</h2>
      </div>

      <div className="monitoring-page__table-wrap">
        <table className="monitoring-page__table" aria-label={`${title} summary`}>
          <thead>
            <tr>
              <th>Metric</th>
              <th>Latency (ms)</th>
            </tr>
          </thead>
          <tbody>
            {metricRows.map((row) => (
              <tr key={row.label}>
                <td>{row.label}</td>
                <td>{formatLatency(row.value)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h3 className="monitoring-page__series-subtitle">Measurements</h3>
      <div className="monitoring-page__table-wrap">
        <table className="monitoring-page__table" aria-label={`${title} measurements`}>
          <thead>
            <tr>
              <th>Iteration</th>
              <th>Latency (ms)</th>
            </tr>
          </thead>
          <tbody>
            {series.measurements.map((measurement) => (
              <tr key={measurement.iteration}>
                <td>{measurement.iteration}</td>
                <td>{formatLatency(measurement.latencyMs)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

export function MonitoringPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const [iterationsInput, setIterationsInput] = useState(String(DEFAULT_ITERATIONS));
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<DbMonitoringSnapshot | null>(null);

  const runMeasurement = useCallback(async (rawIterations: string | number) => {
    const iterations =
      typeof rawIterations === 'number' ? rawIterations : clampIterations(rawIterations);
    setLoading(true);
    setError(null);
    try {
      const snapshot = await fetchDbLatencySnapshot(iterations);
      setResult(snapshot);
      setIterationsInput(String(snapshot.iterations));
    } catch (loadError) {
      setError(getErrorMessage(loadError));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!isAdmin) {
      return;
    }

    void runMeasurement(DEFAULT_ITERATIONS);
  }, [isAdmin, runMeasurement]);

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  return (
    <div className="settings-page monitoring-page">
      <div className="settings-page__header">
        <h1>Database latency</h1>
      </div>
      <p className="settings-page__lead">
        Run lightweight DB probes and compare raw JDBC with Hibernate calls.
      </p>

      <section className="settings-card">
        <div className="settings-card__header">
          <h2>Measure</h2>
        </div>
        <form
          className="monitoring-page__form"
          onSubmit={(event) => {
            event.preventDefault();
            void runMeasurement(iterationsInput);
          }}
        >
          <label className="settings-field__label" htmlFor="monitoring-iterations">
            Iterations
          </label>
          <div className="monitoring-page__controls">
            <input
              id="monitoring-iterations"
              type="number"
              min={MIN_MONITORING_ITERATIONS}
              max={MAX_MONITORING_ITERATIONS}
              className="settings-input monitoring-page__iterations-input"
              value={iterationsInput}
              onChange={(event) => setIterationsInput(event.target.value)}
            />
            <button
              type="submit"
              className="settings-button settings-button--primary"
              disabled={loading}
            >
              {loading ? 'Measuring…' : 'Measure'}
            </button>
          </div>
          <p className="settings-hint">
            Choose between {MIN_MONITORING_ITERATIONS} and {MAX_MONITORING_ITERATIONS} probes to
            average.
          </p>
        </form>
        {error ? (
          <p className="settings-hint is-error monitoring-page__error" role="alert">
            {error}
          </p>
        ) : null}
      </section>

      {result ? (
        <>
          <section className="settings-card">
            <div className="settings-card__header">
              <h2>Overview</h2>
            </div>
            <div className="monitoring-page__overview">
              <div className="monitoring-page__overview-row">
                <span>Last run</span>
                <span>{new Date(result.timestamp).toLocaleString()}</span>
              </div>
              <div className="monitoring-page__overview-row">
                <span>Iterations used</span>
                <span>{result.iterations}</span>
              </div>
            </div>
          </section>

          {SERIES_SECTIONS.map((section) => (
            <MonitoringSeriesSection
              key={section.key}
              title={section.title}
              series={result[section.key]}
            />
          ))}
        </>
      ) : (
        <section className="settings-card monitoring-page__empty-card">
          <p className="settings-page__hint">Run a measurement to see results.</p>
        </section>
      )}
    </div>
  );
}
