import '../settings/settings-page.css';
import '../monitoring/monitoring-page.css';
import './search-index-page.css';

import { useCallback, useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';

import {
  bootstrapSearchIndex,
  fetchActiveSearchIndexReindexTask,
  fetchSearchIndexReindexTask,
  fetchSearchIndexStatus,
  reindexSearchIndex,
  type SearchIndexReindexTask,
  type SearchIndexSearchResult,
  type SearchIndexStatus,
  searchSearchIndex,
} from '../../api/monitoring';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import { RepositoryMultiSelect } from '../../components/RepositoryMultiSelect';
import { useRepositories } from '../../hooks/useRepositories';
import { useUser } from '../../hooks/useUser';
import { useLocaleOptionsWithDisplayNames } from '../../utils/localeSelection';
import {
  useRepositorySelection,
  useRepositorySelectionOptions,
} from '../../utils/repositorySelection';

function formatBooleanLabel(value: boolean) {
  return value ? 'Yes' : 'No';
}

function getErrorMessage(error: unknown) {
  if (error instanceof Error && error.message.trim()) {
    return error.message;
  }
  return 'Search index request failed.';
}

export function SearchIndexPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const { data: repositories = [] } = useRepositories();
  const [loading, setLoading] = useState(false);
  const [bootstrapping, setBootstrapping] = useState(false);
  const [startingReindex, setStartingReindex] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<SearchIndexStatus | null>(null);
  const [pageSize, setPageSize] = useState('500');
  const [bulkSize, setBulkSize] = useState('200');
  const [reindexTask, setReindexTask] = useState<SearchIndexReindexTask | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchLimit, setSearchLimit] = useState('20');
  const [searchCurrentOnly, setSearchCurrentOnly] = useState(true);
  const [searchResults, setSearchResults] = useState<SearchIndexSearchResult | null>(null);
  const repositoryOptions = useRepositorySelectionOptions(repositories);
  const localeOptions = useLocaleOptionsWithDisplayNames(repositories);
  const { selectedIds: selectedRepositoryIds, setSelection: setSelectedRepositoryIds } =
    useRepositorySelection({ options: repositoryOptions });
  const [selectedLocaleTags, setSelectedLocaleTags] = useState<string[]>([]);
  const reindexing = startingReindex || Boolean(reindexTask && !reindexTask.isAllFinished);
  const reindexProgress = reindexTask?.progress ?? null;

  const loadStatus = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [nextStatus, activeReindexTask] = await Promise.all([
        fetchSearchIndexStatus(),
        fetchActiveSearchIndexReindexTask(),
      ]);
      setStatus(nextStatus);
      if (activeReindexTask) {
        setReindexTask(activeReindexTask);
      }
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
    void loadStatus();
  }, [isAdmin, loadStatus]);

  useEffect(() => {
    if (!reindexTask || reindexTask.isAllFinished) {
      return;
    }

    let cancelled = false;
    const timeoutId = window.setTimeout(() => {
      void (async () => {
        try {
          const nextTask = await fetchSearchIndexReindexTask(reindexTask.id);
          if (cancelled) {
            return;
          }
          setReindexTask(nextTask);
          if (nextTask.isAllFinished) {
            if (nextTask.errorMessage) {
              setError(nextTask.errorMessage);
            }
            setStatus(await fetchSearchIndexStatus());
          }
        } catch (pollError) {
          if (!cancelled) {
            setError(getErrorMessage(pollError));
          }
        }
      })();
    }, 1000);

    return () => {
      cancelled = true;
      window.clearTimeout(timeoutId);
    };
  }, [reindexTask]);

  const handleBootstrap = useCallback(async () => {
    setBootstrapping(true);
    setError(null);
    try {
      const nextStatus = await bootstrapSearchIndex();
      setStatus(nextStatus);
    } catch (bootstrapError) {
      setError(getErrorMessage(bootstrapError));
    } finally {
      setBootstrapping(false);
    }
  }, []);

  const handleReindex = useCallback(async () => {
    setStartingReindex(true);
    setError(null);
    try {
      const nextTask = await reindexSearchIndex({
        repositoryIds: selectedRepositoryIds,
        pageSize: pageSize.trim() ? Number.parseInt(pageSize.trim(), 10) : null,
        bulkSize: bulkSize.trim() ? Number.parseInt(bulkSize.trim(), 10) : null,
      });
      setReindexTask(nextTask);
      if (nextTask.isAllFinished) {
        setStatus(await fetchSearchIndexStatus());
      }
    } catch (reindexError) {
      setError(getErrorMessage(reindexError));
    } finally {
      setStartingReindex(false);
    }
  }, [bulkSize, pageSize, selectedRepositoryIds]);

  const handleSearch = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await searchSearchIndex({
        query: searchQuery,
        repositoryIds: selectedRepositoryIds,
        localeTags: selectedLocaleTags,
        currentOnly: searchCurrentOnly,
        limit: searchLimit.trim() ? Number.parseInt(searchLimit.trim(), 10) : null,
      });
      setSearchResults(result);
    } catch (searchError) {
      setError(getErrorMessage(searchError));
    } finally {
      setLoading(false);
    }
  }, [searchCurrentOnly, searchLimit, searchQuery, selectedLocaleTags, selectedRepositoryIds]);

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  return (
    <div className="settings-page monitoring-page">
      <div className="settings-page__header">
        <h1>Search index</h1>
      </div>
      <p className="settings-page__lead">
        Manage the local OpenSearch index, run lexical reindexing, and test fuzzy retrieval.
      </p>

      <section className="settings-card">
        <div className="settings-card__header">
          <h2>Status</h2>
        </div>
        <div className="monitoring-page__overview">
          <div className="monitoring-page__overview-row">
            <span>Enabled</span>
            <span>{status ? formatBooleanLabel(status.enabled) : '-'}</span>
          </div>
          <div className="monitoring-page__overview-row">
            <span>Base URL</span>
            <span>{status?.baseUrl ?? '-'}</span>
          </div>
          <div className="monitoring-page__overview-row">
            <span>Index name</span>
            <span>{status?.indexName ?? '-'}</span>
          </div>
          <div className="monitoring-page__overview-row">
            <span>Reachable</span>
            <span>{status ? formatBooleanLabel(status.reachable) : '-'}</span>
          </div>
          <div className="monitoring-page__overview-row">
            <span>Index exists</span>
            <span>{status ? formatBooleanLabel(status.indexExists) : '-'}</span>
          </div>
          <div className="monitoring-page__overview-row">
            <span>Cluster status</span>
            <span>{status?.clusterStatus ?? '-'}</span>
          </div>
          <div className="monitoring-page__overview-row">
            <span>Document count</span>
            <span>{status?.documentCount == null ? '-' : status.documentCount}</span>
          </div>
          <div className="monitoring-page__overview-row">
            <span>Detail</span>
            <span>{status?.detail ?? '-'}</span>
          </div>
        </div>
        <div className="settings-card__footer">
          <div className="settings-actions">
            <button
              type="button"
              className="settings-button settings-button--ghost"
              onClick={() => void loadStatus()}
              disabled={loading || bootstrapping}
            >
              {loading ? 'Refreshing…' : 'Refresh'}
            </button>
            <button
              type="button"
              className="settings-button settings-button--primary"
              onClick={() => void handleBootstrap()}
              disabled={
                loading ||
                bootstrapping ||
                reindexing ||
                status?.enabled === false ||
                status?.indexExists === true
              }
            >
              {bootstrapping ? 'Bootstrapping…' : 'Bootstrap index'}
            </button>
          </div>
        </div>
        {error ? (
          <p className="settings-hint is-error monitoring-page__error" role="alert">
            {error}
          </p>
        ) : null}
      </section>

      <section className="settings-card">
        <div className="settings-card__header">
          <h2>Reindex</h2>
        </div>
        <p className="settings-note">
          Run lexical indexing for all repositories or scope to selected repositories.
        </p>
        <div className="monitoring-page__controls search-index-page__controls">
          <div className="settings-field">
            <div className="settings-field__label">Repository</div>
            <RepositoryMultiSelect
              label="Repository"
              options={repositoryOptions}
              selectedIds={selectedRepositoryIds}
              onChange={(next) => {
                setSelectedRepositoryIds(next, { markTouched: true });
              }}
              className="monitoring-page__series-select"
              buttonAriaLabel="Repository"
            />
          </div>
          <div className="settings-field">
            <label className="settings-field__label" htmlFor="search-index-page-size">
              Page size
            </label>
            <input
              id="search-index-page-size"
              type="number"
              min={1}
              className="settings-input monitoring-page__iterations-input"
              value={pageSize}
              onChange={(event) => setPageSize(event.target.value)}
              placeholder="Page size"
            />
          </div>
          <div className="settings-field">
            <label className="settings-field__label" htmlFor="search-index-bulk-size">
              Bulk size
            </label>
            <input
              id="search-index-bulk-size"
              type="number"
              min={1}
              className="settings-input monitoring-page__iterations-input"
              value={bulkSize}
              onChange={(event) => setBulkSize(event.target.value)}
              placeholder="Bulk size"
            />
          </div>
          <button
            type="button"
            className="settings-button settings-button--primary"
            onClick={() => void handleReindex()}
            disabled={loading || bootstrapping || reindexing || status?.enabled === false}
          >
            {reindexing ? 'Reindexing…' : 'Reindex'}
          </button>
        </div>
        {reindexProgress ? (
          <div className="monitoring-page__overview">
            <div className="monitoring-page__overview-row">
              <span>Job status</span>
              <span>{reindexProgress.status}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Total docs</span>
              <span>{reindexProgress.totalDocuments}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Scanned docs</span>
              <span>{reindexProgress.scannedDocuments}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Indexed docs</span>
              <span>{reindexProgress.indexedDocuments}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Failed docs</span>
              <span>{reindexProgress.failedDocuments}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Last variant ID</span>
              <span>{reindexProgress.lastProcessedVariantId ?? '-'}</span>
            </div>
            <div className="monitoring-page__overview-row">
              <span>Reindex detail</span>
              <span>{reindexProgress.detail ?? '-'}</span>
            </div>
            {reindexProgress.totalDocuments > 0 ? (
              <progress
                className="search-index-page__progress"
                aria-label="Reindex progress"
                max={reindexProgress.totalDocuments}
                value={Math.min(
                  reindexProgress.indexedDocuments + reindexProgress.failedDocuments,
                  reindexProgress.totalDocuments,
                )}
              />
            ) : null}
          </div>
        ) : null}
      </section>

      <section className="settings-card">
        <div className="settings-card__header">
          <h2>Fuzzy search</h2>
        </div>
        <div className="monitoring-page__controls search-index-page__controls">
          <div className="settings-field">
            <div className="settings-field__label">Repository</div>
            <RepositoryMultiSelect
              label="Repository"
              options={repositoryOptions}
              selectedIds={selectedRepositoryIds}
              onChange={(next) => {
                setSelectedRepositoryIds(next, { markTouched: true });
              }}
              className="monitoring-page__series-select"
              buttonAriaLabel="Repository"
            />
          </div>
          <div className="settings-field">
            <div className="settings-field__label">Locale</div>
            <LocaleMultiSelect
              label="Locale"
              options={localeOptions.map((option) => ({ tag: option.tag, label: option.label }))}
              selectedTags={selectedLocaleTags}
              onChange={setSelectedLocaleTags}
              className="monitoring-page__series-select"
              buttonAriaLabel="Locale"
            />
          </div>
          <div className="settings-field search-index-page__query-field">
            <label className="settings-field__label" htmlFor="search-index-search-query">
              Query
            </label>
            <input
              id="search-index-search-query"
              type="text"
              className="settings-input"
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              placeholder="Fuzzy search query"
            />
          </div>
          <div className="settings-field">
            <label className="settings-field__label" htmlFor="search-index-search-limit">
              Limit
            </label>
            <input
              id="search-index-search-limit"
              type="number"
              min={1}
              className="settings-input monitoring-page__iterations-input"
              value={searchLimit}
              onChange={(event) => setSearchLimit(event.target.value)}
              placeholder="Limit"
            />
          </div>
          <label className="settings-hint search-index-page__current-only">
            <input
              type="checkbox"
              checked={searchCurrentOnly}
              onChange={(event) => setSearchCurrentOnly(event.target.checked)}
            />{' '}
            Current only
          </label>
          <button
            type="button"
            className="settings-button settings-button--ghost"
            onClick={() => void handleSearch()}
            disabled={loading || bootstrapping || !searchQuery.trim()}
          >
            {loading ? 'Searching…' : 'Search'}
          </button>
        </div>
        {searchResults ? (
          <div className="monitoring-page__table-wrap">
            <table className="monitoring-page__table" aria-label="Search index hits">
              <thead>
                <tr>
                  <th>Score</th>
                  <th>Variant</th>
                  <th>Text unit</th>
                  <th>Repository</th>
                  <th>Source locale</th>
                  <th>Target locale</th>
                  <th>Name</th>
                  <th>Target</th>
                  <th>Source</th>
                </tr>
              </thead>
              <tbody>
                {searchResults.hits.length === 0 ? (
                  <tr>
                    <td colSpan={9}>
                      No matches. Try clearing repository or locale filters; selected repositories
                      may not be indexed yet.
                    </td>
                  </tr>
                ) : (
                  searchResults.hits.map((hit) => (
                    <tr key={`${hit.tmTextUnitVariantId ?? 'na'}-${hit.score}`}>
                      <td>{hit.score.toFixed(2)}</td>
                      <td>{hit.tmTextUnitVariantId ?? '-'}</td>
                      <td>{hit.tmTextUnitId ?? '-'}</td>
                      <td>{hit.repositoryName ?? '-'}</td>
                      <td>{hit.sourceLocaleTag ?? '-'}</td>
                      <td>{hit.localeTag ?? '-'}</td>
                      <td>{hit.name ?? '-'}</td>
                      <td>{hit.target ?? '-'}</td>
                      <td>{hit.source ?? '-'}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        ) : null}
      </section>
    </div>
  );
}
