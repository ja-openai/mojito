import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { fetchRepositories } from '../api/repositories';
import type * as TextUnitsApi from '../api/text-units';
import { type ApiTextUnit, searchTextUnits } from '../api/text-units';
import { REPOSITORIES_QUERY_KEY } from '../hooks/useRepositories';
import { TranslationSearchPanel } from './TranslationSearchPanel';

vi.mock('../api/text-units', async (importOriginal) => ({
  ...(await importOriginal<typeof TextUnitsApi>()),
  searchTextUnits: vi.fn(),
}));
vi.mock('../api/repositories', () => ({ fetchRepositories: vi.fn() }));

const searchMock = vi.mocked(searchTextUnits);
const repositoriesMock = vi.mocked(fetchRepositories);
const locales = [{ bcp47Tag: 'fr-FR' }, { bcp47Tag: 'de-DE' }, { bcp47Tag: 'es-ES' }];
const repositories = [
  {
    id: 1,
    name: 'example-mobile',
    repositoryLocales: locales.map((locale) => ({
      locale,
      toBeFullyTranslated: true,
      parentLocale: { bcp47Tag: 'en' },
    })),
  },
  { id: 2, name: 'example-web' },
];

function row(id: number, source: string): ApiTextUnit {
  return {
    tmTextUnitId: id,
    name: `message.${id}`,
    source,
    target: `Traduction ${id}`,
    targetLocale: 'fr-FR',
    repositoryName: 'example-web',
    used: true,
    translationCreatedByUsername: 'translator.alice',
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function renderPanel(localeTag = 'fr-FR', seedRepositories = true) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  if (seedRepositories) {
    queryClient.setQueryData(REPOSITORIES_QUERY_KEY, repositories);
  }
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <TranslationSearchPanel localeTag={localeTag} active />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

function enterQuery(query = 'coffee') {
  fireEvent.change(screen.getByLabelText('Search translation'), { target: { value: query } });
}

function search() {
  fireEvent.click(screen.getByRole('button', { name: 'Search' }));
}

function chooseOption(control: string, option: string | RegExp) {
  fireEvent.click(screen.getByRole('button', { name: control }));
  fireEvent.click(screen.getByRole('button', { name: option }));
}

function chooseSearchOption(option: string, index = 0) {
  const optionsButton = screen.getAllByRole('button', { name: 'Select search options' })[index];
  fireEvent.click(optionsButton);
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${option}`) }));
  fireEvent.click(optionsButton);
}

function addGermanLocale() {
  fireEvent.click(screen.getByRole('button', { name: 'Select locales' }));
  fireEvent.click(screen.getByRole('checkbox', { name: /de-DE/ }));
  fireEvent.click(screen.getByRole('button', { name: 'Select locales' }));
}

beforeEach(() => {
  vi.resetAllMocks();
  searchMock.mockResolvedValue([]);
  repositoriesMock.mockResolvedValue(repositories);
});

describe('TranslationSearchPanel', () => {
  it('defaults to Workbench Contains search for translations in the active locale and all repositories', async () => {
    searchMock.mockResolvedValue([row(1, 'Coffee is ready')]);
    renderPanel();

    expect(screen.getByRole('button', { name: 'Select search options' })).toHaveTextContent(
      'Translation · Contains',
    );
    expect(screen.getByRole('button', { name: 'Select repositories' })).toHaveTextContent(
      'All repositories',
    );
    expect(searchMock).not.toHaveBeenCalled();

    enterQuery('  coffee  ');
    search();

    await waitFor(() =>
      expect(searchMock).toHaveBeenCalledWith({
        textSearch: {
          operator: 'AND',
          predicates: [{ field: 'target', searchType: 'contains', value: 'coffee' }],
        },
        localeTags: ['fr-FR'],
        repositoryIds: [1, 2],
        offset: 0,
        limit: 51,
        orderedByTextUnitId: true,
        usedFilter: 'USED',
      }),
    );
    expect(await screen.findByText('Coffee is ready')).toBeInTheDocument();
    expect(screen.getByText('Traduction 1')).toBeInTheDocument();
    expect(screen.getByText('Saved by translator.alice')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open string' })).toHaveAttribute(
      'href',
      expect.stringContaining('/text-units/1?locale=fr-FR'),
    );
    expect(screen.getByText('example-web')).toBeInTheDocument();
  });

  it('lets the reviewer change the prefilled repository and locale scope', async () => {
    renderPanel();
    enterQuery();
    fireEvent.click(screen.getByRole('button', { name: 'Select repositories' }));
    fireEvent.click(screen.getByRole('checkbox', { name: /example-mobile/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Select repositories' }));
    addGermanLocale();
    search();

    await waitFor(() =>
      expect(searchMock).toHaveBeenCalledWith(
        expect.objectContaining({ repositoryIds: [2], localeTags: ['fr-FR', 'de-DE'] }),
      ),
    );
  });

  it('shows an unknown author explicitly and does not attribute a missing translation', async () => {
    searchMock.mockResolvedValue([
      { ...row(1, 'Unknown author'), translationCreatedByUsername: null },
      { ...row(2, 'Untranslated source'), target: null, translationCreatedByUsername: null },
    ]);
    renderPanel();
    enterQuery();
    search();

    expect(await screen.findByText('Saved by unknown')).toBeInTheDocument();
    expect(screen.getByText('No translation')).toBeInTheDocument();
    expect(screen.getAllByText(/^Saved by /)).toHaveLength(1);
  });

  it('keeps an explicitly empty repository selection empty and can restore the all preset', async () => {
    renderPanel();
    enterQuery();
    fireEvent.click(screen.getByRole('button', { name: 'Select repositories' }));
    fireEvent.click(screen.getByRole('button', { name: 'Clear repository selection' }));
    fireEvent.click(screen.getByRole('button', { name: 'Select repositories' }));
    expect(screen.getByRole('button', { name: 'Select repositories' })).toHaveTextContent(
      'No repositories',
    );
    expect(screen.getByRole('button', { name: 'Search' })).toBeDisabled();
    search();
    expect(searchMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Select repositories' }));
    fireEvent.click(screen.getByRole('button', { name: 'Select all repositories' }));
    fireEvent.click(screen.getByRole('button', { name: 'Select repositories' }));
    search();
    await waitFor(() =>
      expect(searchMock).toHaveBeenCalledWith(expect.objectContaining({ repositoryIds: [1, 2] })),
    );
  });

  it('uses Workbench Add, match operator, fields, and exact/regex options', async () => {
    renderPanel();
    expect(screen.queryByRole('button', { name: 'Add search condition' })).not.toBeInTheDocument();
    enterQuery('  100%_café  ');
    chooseSearchOption('Exact match');
    fireEvent.click(screen.getByRole('button', { name: 'Add search condition' }));
    chooseSearchOption('Source', 1);
    chooseSearchOption('Regex', 1);
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search source text' }), {
      target: { value: '  ^Coffee.*  ' },
    });
    chooseOption('Match operator', 'any');
    search();

    await waitFor(() =>
      expect(searchMock).toHaveBeenCalledWith(
        expect.objectContaining({
          textSearch: {
            operator: 'OR',
            predicates: [
              { field: 'target', searchType: 'exact', value: '100%_café' },
              { field: 'source', searchType: 'regex', value: '^Coffee.*' },
            ],
          },
        }),
      ),
    );

    fireEvent.click(screen.getAllByRole('button', { name: 'Remove search condition' })[1]);
    expect(screen.queryByRole('button', { name: 'Match operator' })).not.toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Search translation' })).toHaveValue(
      '  100%_café  ',
    );
    expect(screen.getByRole('button', { name: 'Select search options' })).toHaveTextContent(
      'Translation · Exact match',
    );
  });

  it('ignores an empty added condition and preserves literal Contains input for the shared API', async () => {
    renderPanel();
    enterQuery('  100%_café  ');
    fireEvent.click(screen.getByRole('button', { name: 'Add search condition' }));
    search();

    await waitFor(() =>
      expect(searchMock).toHaveBeenCalledWith(
        expect.objectContaining({
          textSearch: {
            operator: 'AND',
            predicates: [{ field: 'target', searchType: 'contains', value: '100%_café' }],
          },
        }),
      ),
    );
  });

  it('does not search a blank query or a query without a selected locale', () => {
    renderPanel();
    expect(screen.getByRole('button', { name: 'Search' })).toBeDisabled();
    enterQuery('   ');
    search();
    expect(searchMock).not.toHaveBeenCalled();

    enterQuery();
    fireEvent.click(screen.getByRole('button', { name: 'Select locales' }));
    fireEvent.click(screen.getByRole('checkbox', { name: /fr-FR/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Select locales' }));
    expect(screen.getByRole('button', { name: 'Search' })).toBeDisabled();
    search();
    expect(searchMock).not.toHaveBeenCalled();
  });

  it('waits for the repository list before searching all repositories', () => {
    repositoriesMock.mockReturnValue(new Promise(() => undefined));
    renderPanel('fr-FR', false);
    enterQuery();
    expect(screen.getByRole('button', { name: 'Search' })).toBeDisabled();
    search();
    expect(searchMock).not.toHaveBeenCalled();
  });

  it('shows progress and prevents duplicate button requests until the search completes', async () => {
    const pendingSearch = deferred<Awaited<ReturnType<typeof searchTextUnits>>>();
    searchMock.mockReturnValueOnce(pendingSearch.promise);
    renderPanel();
    enterQuery();
    search();

    expect(await screen.findByText('Searching…')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Search' })).toBeDisabled();
    search();
    expect(searchMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      pendingSearch.resolve([]);
      await pendingSearch.promise;
    });

    expect(await screen.findByText('No matches found.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Search' })).toBeEnabled();
  });

  it.each(['Control', 'Meta'])(
    'does not forward %s+Enter to the review-editor save shortcut',
    async (modifier) => {
      const user = userEvent.setup();
      const reviewShortcut = vi.fn();
      renderPanel();
      enterQuery();
      screen.getByLabelText('Search translation').focus();
      window.addEventListener('keydown', reviewShortcut);
      try {
        await user.keyboard(`{${modifier}>}{Enter}{/${modifier}}`);
      } finally {
        window.removeEventListener('keydown', reviewShortcut);
      }

      expect(reviewShortcut).not.toHaveBeenCalled();
    },
  );

  it('submits a search with Enter in the query input', async () => {
    const user = userEvent.setup();
    renderPanel();
    enterQuery();
    screen.getByLabelText('Search translation').focus();

    await user.keyboard('{Enter}');

    await waitFor(() => expect(searchMock).toHaveBeenCalledTimes(1));
  });

  it.each([{ isComposing: true }, { keyCode: 229 }])(
    'does not submit Enter while an input method is composing: %j',
    (composition) => {
      renderPanel();
      enterQuery('日本');
      const event = new KeyboardEvent('keydown', {
        key: 'Enter',
        bubbles: true,
        cancelable: true,
        ...composition,
      });

      fireEvent(screen.getByLabelText('Search translation'), event);

      expect(event.defaultPrevented).toBe(true);
      expect(searchMock).not.toHaveBeenCalled();
    },
  );

  it.each(['query', 'match type', 'field', 'locales', 'repositories'])(
    'clears results when the %s changes so old matches cannot be mistaken for new ones',
    async (control) => {
      searchMock.mockResolvedValue([row(1, 'Old result')]);
      renderPanel();
      enterQuery();
      search();
      expect(await screen.findByText('Old result')).toBeInTheDocument();

      if (control === 'query') {
        enterQuery('tea');
      } else if (control === 'match type') {
        chooseSearchOption('Exact match');
      } else if (control === 'field') {
        chooseSearchOption('Source');
      } else if (control === 'locales') {
        addGermanLocale();
      } else {
        fireEvent.click(screen.getByRole('button', { name: 'Select repositories' }));
        fireEvent.click(screen.getByRole('checkbox', { name: /example-mobile/ }));
      }

      expect(screen.queryByText('Old result')).not.toBeInTheDocument();
      expect(searchMock).toHaveBeenCalledTimes(1);
    },
  );

  it('ignores a late response for a previous query after a newer search has completed', async () => {
    const oldSearch = deferred<Awaited<ReturnType<typeof searchTextUnits>>>();
    searchMock
      .mockReturnValueOnce(oldSearch.promise)
      .mockResolvedValueOnce([row(2, 'Fresh result')]);
    renderPanel();
    enterQuery('coffee');
    search();
    await waitFor(() => expect(searchMock).toHaveBeenCalledTimes(1));

    enterQuery('tea');
    search();
    expect(await screen.findByText('Fresh result')).toBeInTheDocument();

    await act(async () => {
      oldSearch.resolve([row(1, 'Stale result')]);
      await oldSearch.promise;
    });

    expect(screen.getByText('Fresh result')).toBeInTheDocument();
    expect(screen.queryByText('Stale result')).not.toBeInTheDocument();
  });

  it('ignores an old request failure after the search controls change', async () => {
    const oldSearch = deferred<Awaited<ReturnType<typeof searchTextUnits>>>();
    searchMock.mockReturnValueOnce(oldSearch.promise);
    renderPanel();
    enterQuery();
    search();
    await waitFor(() => expect(searchMock).toHaveBeenCalledTimes(1));

    chooseSearchOption('Source');
    await act(async () => {
      oldSearch.reject(new Error('Obsolete search failure'));
      await oldSearch.promise.catch(() => undefined);
    });

    expect(screen.queryByText(/Obsolete search failure/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Search' })).toBeEnabled();
  });

  it('shows a failed search and allows a successful retry', async () => {
    searchMock
      .mockRejectedValueOnce(new Error('Search service unavailable'))
      .mockResolvedValueOnce([row(1, 'Recovered result')]);
    renderPanel();
    enterQuery();
    search();

    expect(await screen.findByText(/Search service unavailable/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Search' })).toBeEnabled();
    search();

    expect(await screen.findByText('Recovered result')).toBeInTheDocument();
    expect(screen.queryByText(/Search service unavailable/)).not.toBeInTheDocument();
  });

  it('distinguishes an empty result from a search that has not run yet', async () => {
    renderPanel();
    expect(screen.queryByText(/No (?:.*match|results)/i)).not.toBeInTheDocument();
    enterQuery();
    search();

    expect(await screen.findByText(/No (?:.*match|results)/i)).toBeInTheDocument();
  });

  it('requests next and previous pages with the same search scope', async () => {
    searchMock
      .mockResolvedValueOnce(
        Array.from({ length: 51 }, (_, index) =>
          row(index + 1, index === 0 ? 'First page' : `Result ${index + 1}`),
        ),
      )
      .mockResolvedValueOnce([row(52, 'Second page')])
      .mockResolvedValueOnce(
        Array.from({ length: 51 }, (_, index) =>
          row(index + 1, index === 0 ? 'First page' : `Result ${index + 1}`),
        ),
      );
    renderPanel();
    enterQuery();
    search();
    expect(await screen.findByText('First page')).toBeInTheDocument();
    expect(screen.queryByText('Result 51')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Second page')).toBeInTheDocument();
    expect(searchMock).toHaveBeenLastCalledWith(
      expect.objectContaining({
        textSearch: {
          operator: 'AND',
          predicates: [{ field: 'target', searchType: 'contains', value: 'coffee' }],
        },
        localeTags: ['fr-FR'],
        repositoryIds: [1, 2],
        offset: 50,
        limit: 51,
      }),
    );
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'Previous' }));
    expect(await screen.findByText('First page')).toBeInTheDocument();
    expect(screen.queryByText('Second page')).not.toBeInTheDocument();
  });
});
