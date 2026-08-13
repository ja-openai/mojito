import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { SearchIndexPage } from './SearchIndexPage';

const mocks = vi.hoisted(() => ({
  bootstrapSearchIndex: vi.fn(),
  fetchActiveSearchIndexReindexTask: vi.fn(),
  fetchSearchIndexReindexTask: vi.fn(),
  fetchSearchIndexStatus: vi.fn(),
  reindexSearchIndex: vi.fn(),
  searchSearchIndex: vi.fn(),
  role: 'ROLE_ADMIN',
  repositories: [
    { id: 7, name: 'checkout' },
    { id: 19, name: 'billing' },
  ],
  localeOptions: [
    { tag: 'fr', label: 'French' },
    { tag: 'ja', label: 'Japanese' },
  ],
}));

vi.mock('../../api/monitoring', () => ({
  bootstrapSearchIndex: mocks.bootstrapSearchIndex,
  fetchActiveSearchIndexReindexTask: mocks.fetchActiveSearchIndexReindexTask,
  fetchSearchIndexReindexTask: mocks.fetchSearchIndexReindexTask,
  fetchSearchIndexStatus: mocks.fetchSearchIndexStatus,
  reindexSearchIndex: mocks.reindexSearchIndex,
  searchSearchIndex: mocks.searchSearchIndex,
}));

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({ username: 'admin', role: mocks.role }),
}));

vi.mock('../../hooks/useRepositories', () => ({
  useRepositories: () => ({ data: mocks.repositories }),
}));

vi.mock('../../utils/localeSelection', () => ({
  useLocaleOptionsWithDisplayNames: () => mocks.localeOptions,
}));

const readyStatus = {
  enabled: true,
  baseUrl: 'http://localhost:9200',
  indexName: 'tm-text-unit-variants-v1',
  reachable: true,
  indexExists: true,
  clusterStatus: 'green',
  documentCount: 14,
  detail: null,
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/monitoring/search-index']}>
      <Routes>
        <Route path="/monitoring/search-index" element={<SearchIndexPage />} />
        <Route path="/repositories" element={<div>Repositories</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('SearchIndexPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.role = 'ROLE_ADMIN';
    mocks.fetchSearchIndexStatus.mockResolvedValue(readyStatus);
    mocks.fetchActiveSearchIndexReindexTask.mockResolvedValue(null);
    mocks.bootstrapSearchIndex.mockResolvedValue(readyStatus);
    mocks.reindexSearchIndex.mockResolvedValue({
      id: 71,
      isAllFinished: true,
      errorMessage: null,
      progress: {
        status: 'COMPLETED',
        indexName: readyStatus.indexName,
        repositoryIds: [],
        pageSize: 500,
        bulkSize: 200,
        totalDocuments: 14,
        scannedDocuments: 14,
        indexedDocuments: 14,
        failedDocuments: 0,
        lastProcessedVariantId: 42,
        detail: null,
      },
    });
    mocks.fetchSearchIndexReindexTask.mockResolvedValue({
      id: 71,
      isAllFinished: true,
      errorMessage: null,
      progress: {
        status: 'COMPLETED',
        indexName: readyStatus.indexName,
        repositoryIds: [],
        pageSize: 500,
        bulkSize: 200,
        totalDocuments: 14,
        scannedDocuments: 14,
        indexedDocuments: 14,
        failedDocuments: 0,
        lastProcessedVariantId: 42,
        detail: null,
      },
    });
    mocks.searchSearchIndex.mockResolvedValue({
      indexName: readyStatus.indexName,
      limit: 20,
      currentOnly: true,
      hits: [
        {
          score: 2.5,
          tmTextUnitVariantId: 42,
          tmTextUnitId: 12,
          repositoryId: 7,
          repositoryName: 'checkout',
          assetId: 3,
          assetPath: 'checkout.json',
          sourceLocaleTag: 'en-US',
          localeTag: 'fr',
          name: 'checkout.button',
          source: 'Checkout',
          target: 'Paiement',
          status: 'APPROVED',
          current: true,
          assetDeleted: false,
        },
      ],
    });
  });

  it('shows cluster status and reindexes translation-memory documents', async () => {
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText('http://localhost:9200')).toBeInTheDocument();
    expect(screen.getByText('tm-text-unit-variants-v1')).toBeInTheDocument();
    expect(screen.getByText('green')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Reindex' }));

    await waitFor(() =>
      expect(mocks.reindexSearchIndex).toHaveBeenCalledWith({
        repositoryIds: [],
        pageSize: 500,
        bulkSize: 200,
      }),
    );
    expect(await screen.findByText('Scanned docs')).toBeInTheDocument();
    expect(screen.getByText('COMPLETED')).toBeInTheDocument();
    expect(screen.getByRole('progressbar', { name: 'Reindex progress' })).toHaveAttribute(
      'max',
      '14',
    );
  });

  it('starts indexing immediately and polls progress without blocking search', async () => {
    const user = userEvent.setup();
    mocks.reindexSearchIndex.mockResolvedValue({
      id: 71,
      isAllFinished: false,
      errorMessage: null,
      progress: {
        status: 'RUNNING',
        indexName: readyStatus.indexName,
        repositoryIds: [],
        pageSize: 500,
        bulkSize: 200,
        totalDocuments: 100,
        scannedDocuments: 40,
        indexedDocuments: 40,
        failedDocuments: 0,
        lastProcessedVariantId: 42,
        detail: null,
      },
    });

    renderPage();
    await screen.findByText('tm-text-unit-variants-v1');
    await user.click(screen.getByRole('button', { name: 'Reindex' }));

    expect(await screen.findByText('RUNNING')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reindexing…' })).toBeDisabled();
    expect(screen.getByRole('progressbar', { name: 'Reindex progress' })).toHaveAttribute(
      'value',
      '40',
    );

    await user.type(screen.getByLabelText('Query'), 'checkout');
    expect(screen.getByRole('button', { name: 'Search' })).toBeEnabled();

    expect(await screen.findByText('COMPLETED', {}, { timeout: 2_500 })).toBeInTheDocument();
    expect(mocks.fetchSearchIndexReindexTask).toHaveBeenCalledWith(71);
    expect(screen.getByRole('button', { name: 'Reindex' })).toBeEnabled();
  });

  it('reconnects to an active indexing job when the page opens', async () => {
    mocks.fetchActiveSearchIndexReindexTask.mockResolvedValue({
      id: 88,
      isAllFinished: false,
      errorMessage: null,
      progress: {
        status: 'RUNNING',
        indexName: readyStatus.indexName,
        repositoryIds: [7],
        pageSize: 500,
        bulkSize: 200,
        totalDocuments: 200,
        scannedDocuments: 80,
        indexedDocuments: 80,
        failedDocuments: 0,
        lastProcessedVariantId: 99,
        detail: null,
      },
    });

    renderPage();

    expect(await screen.findByText('RUNNING')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reindexing…' })).toBeDisabled();
    expect(screen.getByRole('progressbar', { name: 'Reindex progress' })).toHaveAttribute(
      'value',
      '80',
    );
  });

  it('bootstraps an index only when it does not exist', async () => {
    const user = userEvent.setup();
    mocks.fetchSearchIndexStatus.mockResolvedValue({
      ...readyStatus,
      indexExists: false,
      documentCount: null,
    });

    renderPage();

    const bootstrapButton = await screen.findByRole('button', { name: 'Bootstrap index' });
    expect(bootstrapButton).toBeEnabled();

    await user.click(bootstrapButton);

    await waitFor(() => expect(mocks.bootstrapSearchIndex).toHaveBeenCalledOnce());
    expect(screen.getByRole('button', { name: 'Bootstrap index' })).toBeDisabled();
  });

  it('runs fuzzy search with the current-variant filter enabled', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByText('tm-text-unit-variants-v1');
    await user.type(screen.getByPlaceholderText('Fuzzy search query'), 'checkout');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() =>
      expect(mocks.searchSearchIndex).toHaveBeenCalledWith({
        query: 'checkout',
        repositoryIds: [],
        localeTags: [],
        currentOnly: true,
        limit: 20,
      }),
    );
    expect(await screen.findByText('Paiement')).toBeInTheDocument();
    expect(screen.getByText('en-US')).toBeInTheDocument();
    expect(screen.getByText('fr')).toBeInTheDocument();
  });

  it('explains empty search results when repository or locale filters exclude indexed documents', async () => {
    const user = userEvent.setup();
    mocks.searchSearchIndex.mockResolvedValue({
      indexName: readyStatus.indexName,
      limit: 20,
      currentOnly: true,
      hits: [],
    });

    renderPage();
    await screen.findByText('tm-text-unit-variants-v1');
    await user.type(screen.getByLabelText('Query'), 'a');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    expect(
      await screen.findByText(/No matches\. Try clearing repository or locale filters/),
    ).toBeInTheDocument();
  });

  it('selects every repository and locale for fuzzy search', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByText('tm-text-unit-variants-v1');

    await user.click(screen.getAllByRole('button', { name: 'Repository' })[1]);
    await user.click(within(screen.getByRole('menu')).getByRole('button', { name: 'Select all' }));
    expect(screen.getAllByRole('button', { name: 'Repository' })[1]).toHaveTextContent(
      'All repositories',
    );

    await user.click(screen.getByRole('button', { name: 'Locale' }));
    await user.click(within(screen.getByRole('menu')).getByRole('button', { name: 'Select all' }));
    expect(screen.getByRole('button', { name: 'Locale' })).toHaveTextContent('All locales');

    await user.type(screen.getByPlaceholderText('Fuzzy search query'), 'checkout');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() =>
      expect(mocks.searchSearchIndex).toHaveBeenCalledWith({
        query: 'checkout',
        repositoryIds: [7, 19],
        localeTags: ['fr', 'ja'],
        currentOnly: true,
        limit: 20,
      }),
    );
  });

  it('reindexes every selected repository', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByText('tm-text-unit-variants-v1');
    await user.click(screen.getAllByRole('button', { name: 'Repository' })[0]);
    await user.click(within(screen.getByRole('menu')).getByRole('button', { name: 'Select all' }));
    await user.click(screen.getByRole('button', { name: 'Reindex' }));

    await waitFor(() =>
      expect(mocks.reindexSearchIndex).toHaveBeenCalledWith({
        repositoryIds: [7, 19],
        pageSize: 500,
        bulkSize: 200,
      }),
    );
  });

  it('clears all selected locales', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByText('tm-text-unit-variants-v1');
    await user.click(screen.getByRole('button', { name: 'Locale' }));

    const localeMenu = screen.getByRole('menu');
    await user.click(within(localeMenu).getByRole('button', { name: 'Select all' }));
    await user.click(within(localeMenu).getByRole('button', { name: 'Clear' }));

    expect(screen.getByRole('button', { name: 'Locale' })).toHaveTextContent('Locale');
  });

  it('redirects non-admin users without requesting index status', async () => {
    mocks.role = 'ROLE_PM';

    renderPage();

    expect(await screen.findByText('Repositories')).toBeInTheDocument();
    expect(mocks.fetchSearchIndexStatus).not.toHaveBeenCalled();
    expect(mocks.fetchActiveSearchIndexReindexTask).not.toHaveBeenCalled();
  });
});
