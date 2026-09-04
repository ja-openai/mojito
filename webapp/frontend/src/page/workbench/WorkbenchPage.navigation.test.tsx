import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as TextUnitsApi from '../../api/text-units';
import type { ApiTextUnit, TextUnitSearchRequest } from '../../api/text-units';
import { userPreferencesQueryKey } from '../../hooks/useUserPreferences';
import { saveWorkbenchSessionSearch } from './workbench-session-state';
import { WorkbenchPage } from './WorkbenchPage';
import type { WorkbenchPageView } from './WorkbenchPageView';

type ViewProps = ComponentProps<typeof WorkbenchPageView>;

const searchTextUnitsMock = vi.hoisted(() =>
  vi.fn<(request: TextUnitSearchRequest) => Promise<ApiTextUnit[]>>(),
);

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({
    username: 'navigation-reviewer',
    role: 'ROLE_ADMIN',
    canTranslateAllLocales: true,
    userLocales: [],
  }),
}));

vi.mock('../../hooks/useRepositories', () => ({
  useRepositories: () => ({
    data: [22, 23].map((id) => ({
      id,
      name: `repository-${id}`,
      isGlossary: id === 23,
      sourceLocale: { bcp47Tag: 'en' },
      repositoryLocales: ['fr', 'de'].map((bcp47Tag) => ({
        locale: { bcp47Tag },
        toBeFullyTranslated: true,
        parentLocale: { bcp47Tag: 'en' },
      })),
    })),
    isLoading: false,
    isError: false,
    error: null,
  }),
}));

vi.mock('../../api/text-units', async (importActual) => ({
  ...(await importActual<typeof TextUnitsApi>()),
  searchTextUnits: searchTextUnitsMock,
}));

// Keep the real search hook and router; expose view controls without virtual layout.
vi.mock('./WorkbenchPageView', () => ({
  WorkbenchPageView: (props: ViewProps) => (
    <>
      <output data-testid="workbench-view">
        {JSON.stringify({
          searchRequest: props.activeSearchRequest,
          repositoryIds: props.selectedRepositoryIds,
          localeTags: props.selectedLocaleTags,
          textSearchOperator: props.textSearchOperator,
          textSearchConditions: props.textSearchConditions.map(({ field, searchType, value }) => ({
            field,
            searchType,
            value,
          })),
          statusFilter: props.statusFilter,
          glossaryStatusFilter: props.glossaryStatusFilter,
          includeUsed: props.includeUsed,
          includeUnused: props.includeUnused,
          includeTranslate: props.includeTranslate,
          includeDoNotTranslate: props.includeDoNotTranslate,
          createdBefore: props.createdBefore,
          createdAfter: props.createdAfter,
          translationCreatedBefore: props.translationCreatedBefore,
          translationCreatedAfter: props.translationCreatedAfter,
          worksetSize: props.worksetSize,
          resultSortField: props.resultSortField,
          resultSortDirection: props.resultSortDirection,
          rowIds: props.rows.map((row) => row.id),
          restoreRowId: props.restoreRowId,
          restoreScrollTop: props.restoreScrollTop,
        })}
      </output>
      <button
        onClick={() => {
          props.onChangeResultSortField('tmTextUnitId');
          props.onChangeResultSortDirection('desc');
        }}
      >
        Sort by ID descending
      </button>
      <button
        onClick={() => {
          props.onChangeStatusFilter('TRANSLATED');
          props.onChangeResultSortField('source');
          props.onChangeResultSortDirection('asc');
        }}
      >
        Show translated strings sorted by source
      </button>
      <button onClick={props.onRestoreScrollConsumed}>Finish restoring position</button>
      {props.rows.map((row) => (
        <button key={row.id} onClick={() => props.onOpenDetails(row, row.tmTextUnitId * 10)}>
          Details {row.tmTextUnitId}
        </button>
      ))}
    </>
  ),
}));

const originalSearch: TextUnitSearchRequest = {
  repositoryIds: [22, 23],
  localeTags: ['fr', 'de'],
  textSearch: {
    operator: 'OR',
    predicates: [
      { field: 'source', searchType: 'contains', value: 'Account' },
      { field: 'comment', searchType: 'exact', value: 'Settings & profile' },
    ],
  },
  statusFilter: 'REVIEW_NEEDED',
  glossaryStatusFilter: 'CANDIDATE',
  usedFilter: 'UNUSED',
  doNotTranslateFilter: true,
  tmTextUnitCreatedBefore: '2026-09-01T23:59:00Z',
  tmTextUnitCreatedAfter: '2026-08-01T00:00:00Z',
  tmTextUnitVariantCreatedBefore: '2026-09-02T23:59:00Z',
  tmTextUnitVariantCreatedAfter: '2026-08-02T00:00:00Z',
  limit: 1000,
  offset: 0,
};

const resultRows: ApiTextUnit[] = Array.from({ length: 12 }, (_, index) => ({
  tmTextUnitId: 101 + index,
  name: `account.${index}`,
  source: `Account ${index}`,
  target: `Compte ${index}`,
  targetLocale: 'fr',
  repositoryName: 'repository-22',
  used: false,
  status: 'REVIEW_NEEDED',
}));

function HistoryControls() {
  const location = useLocation();
  const navigate = useNavigate();
  return (
    <>
      <output data-testid="current-location">
        {JSON.stringify({
          url: location.pathname + location.search,
          state: location.state as unknown,
        })}
      </output>
      <button onClick={() => void navigate(-1)}>Browser Back</button>
      <button onClick={() => void navigate(1)}>Browser Forward</button>
    </>
  );
}

function readView(): Record<string, unknown> {
  return JSON.parse(screen.getByTestId('workbench-view').textContent ?? '{}') as Record<
    string,
    unknown
  >;
}

function readLocation(): Record<string, unknown> {
  return JSON.parse(screen.getByTestId('current-location').textContent ?? '{}') as Record<
    string,
    unknown
  >;
}

const queryClients: QueryClient[] = [];

function renderWorkbench(searchRequest: TextUnitSearchRequest | null = originalSearch) {
  const token = searchRequest
    ? saveWorkbenchSessionSearch(searchRequest, 'navigation-session')
    : null;
  const workbenchUrl = token ? `/workbench?ws=${token}` : '/workbench';
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  queryClients.push(queryClient);
  queryClient.setQueryData(userPreferencesQueryKey('navigation-reviewer'), {
    initialized: true,
    worksetSize: 10,
    preferredLocales: ['fr'],
    shortcutHelp: null,
    visibleTextEditorEnabled: false,
    reviewProjectSearchEnabled: false,
    defaultReviewTeamIds: [],
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter
        initialEntries={[
          {
            pathname: '/workbench',
            search: token ? `?ws=${token}` : '',
            state: { unrelatedContext: 'kept' },
          },
        ]}
      >
        <HistoryControls />
        <Routes>
          <Route path="/workbench" element={<WorkbenchPage />} />
          <Route path="/text-units/:id" element={<div>Text unit details</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { queryClient, token, workbenchUrl };
}

async function prepareSortedWorkbench() {
  const context = renderWorkbench();
  await screen.findByRole('button', { name: 'Details 112' });
  await waitFor(() => expect(readView().searchRequest).toEqual(originalSearch));
  fireEvent.click(screen.getByRole('button', { name: 'Sort by ID descending' }));
  expect(readView()).toMatchObject({
    repositoryIds: [22, 23],
    localeTags: ['fr', 'de'],
    textSearchOperator: 'OR',
    textSearchConditions: originalSearch.textSearch?.predicates,
    statusFilter: 'REVIEW_NEEDED',
    glossaryStatusFilter: 'CANDIDATE',
    includeUsed: false,
    includeUnused: true,
    includeTranslate: false,
    includeDoNotTranslate: true,
    createdBefore: originalSearch.tmTextUnitCreatedBefore,
    createdAfter: originalSearch.tmTextUnitCreatedAfter,
    translationCreatedBefore: originalSearch.tmTextUnitVariantCreatedBefore,
    translationCreatedAfter: originalSearch.tmTextUnitVariantCreatedAfter,
    worksetSize: 1000,
    resultSortField: 'tmTextUnitId',
    resultSortDirection: 'desc',
    rowIds: [...resultRows].reverse().map((row) => `${row.tmTextUnitId}:fr`),
  });
  const viewBefore = readView();
  delete viewBefore.restoreRowId;
  delete viewBefore.restoreScrollTop;
  return { ...context, viewBefore };
}

async function expectRestored(
  workbenchUrl: string,
  viewBefore: Record<string, unknown>,
  rowId: string,
  scrollTop: number,
) {
  await waitFor(() =>
    expect(readView()).toEqual({ ...viewBefore, restoreRowId: rowId, restoreScrollTop: scrollTop }),
  );
  expect(readLocation()).toMatchObject({
    url: workbenchUrl,
    state: { unrelatedContext: 'kept', workbenchReturn: { rowId, scrollTop } },
  });
}

describe('Workbench Details navigation', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    searchTextUnitsMock.mockReset();
    searchTextUnitsMock.mockResolvedValue(resultRows);
  });

  afterEach(() => {
    queryClients.splice(0).forEach((queryClient) => queryClient.clear());
    window.sessionStorage.clear();
  });

  it('keeps fresh Workbench defaults when there is no saved search', async () => {
    renderWorkbench(null);

    await waitFor(() => expect(readView().worksetSize).toBe(10));
    expect(readView()).toMatchObject({
      searchRequest: null,
      statusFilter: 'ALL',
      glossaryStatusFilter: 'ALL',
      includeUsed: true,
      includeUnused: false,
      includeTranslate: true,
      includeDoNotTranslate: true,
      resultSortField: 'source',
      resultSortDirection: 'default',
    });
    expect(searchTextUnitsMock).not.toHaveBeenCalled();
  });

  it('restores ordinary repository filters without introducing a glossary filter', async () => {
    const searchRequest = {
      ...originalSearch,
      repositoryIds: [22],
      glossaryStatusFilter: undefined,
      usedFilter: 'USED' as const,
      doNotTranslateFilter: false,
      limit: 10,
    };
    const { workbenchUrl } = renderWorkbench(searchRequest);
    await screen.findByRole('button', { name: 'Details 103' });
    await waitFor(() => expect(readView().searchRequest).toEqual(searchRequest));

    fireEvent.click(screen.getByRole('button', { name: 'Details 103' }));
    fireEvent.click(screen.getByRole('button', { name: 'Browser Back' }));

    await waitFor(() => expect(readView().searchRequest).toEqual(searchRequest));
    expect(readView()).toMatchObject({
      glossaryStatusFilter: 'ALL',
      includeUsed: true,
      includeUnused: false,
      includeTranslate: true,
      includeDoNotTranslate: false,
      worksetSize: 10,
      restoreRowId: '103:fr',
      restoreScrollTop: 1030,
    });
    expect(readLocation().url).toBe(workbenchUrl);
  });

  it('restores every applied filter, expanded result limit, sort and origin through Back/Forward', async () => {
    const { workbenchUrl, viewBefore } = await prepareSortedWorkbench();
    expect(searchTextUnitsMock).toHaveBeenCalledWith({ ...originalSearch, limit: 1001 });

    fireEvent.click(screen.getByRole('button', { name: 'Details 112' }));
    expect(screen.getByText('Text unit details')).toBeInTheDocument();
    expect(readLocation()).toMatchObject({
      url: '/text-units/112?locale=fr',
      state: {
        from: '/workbench',
        workbenchUrl,
        workbenchReturn: {
          searchRequest: originalSearch,
          resultSortField: 'tmTextUnitId',
          resultSortDirection: 'desc',
          rowId: '112:fr',
          scrollTop: 1120,
        },
      },
    });

    fireEvent.click(screen.getByRole('button', { name: 'Browser Back' }));
    await expectRestored(workbenchUrl, viewBefore, '112:fr', 1120);
    fireEvent.click(screen.getByRole('button', { name: 'Finish restoring position' }));
    expect(readView().restoreRowId).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Browser Forward' }));
    expect(screen.getByText('Text unit details')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Browser Back' }));
    await expectRestored(workbenchUrl, viewBefore, '112:fr', 1120);
  });

  it('replaces the saved origin when opening another row after returning', async () => {
    const { workbenchUrl, viewBefore } = await prepareSortedWorkbench();
    fireEvent.click(screen.getByRole('button', { name: 'Details 112' }));
    fireEvent.click(screen.getByRole('button', { name: 'Browser Back' }));
    await expectRestored(workbenchUrl, viewBefore, '112:fr', 1120);
    fireEvent.click(screen.getByRole('button', { name: 'Finish restoring position' }));

    fireEvent.click(screen.getByRole('button', { name: 'Details 103' }));
    expect(readLocation().url).toBe('/text-units/103?locale=fr');
    fireEvent.click(screen.getByRole('button', { name: 'Browser Back' }));
    await expectRestored(workbenchUrl, viewBefore, '103:fr', 1030);
  });

  it('keeps newly applied filters and sorting after revisiting Details with Forward then Back', async () => {
    const { workbenchUrl, viewBefore } = await prepareSortedWorkbench();
    fireEvent.click(screen.getByRole('button', { name: 'Details 112' }));
    fireEvent.click(screen.getByRole('button', { name: 'Browser Back' }));
    await expectRestored(workbenchUrl, viewBefore, '112:fr', 1120);
    fireEvent.click(screen.getByRole('button', { name: 'Finish restoring position' }));

    fireEvent.click(
      screen.getByRole('button', { name: 'Show translated strings sorted by source' }),
    );
    const changedSearch = { ...originalSearch, statusFilter: 'TRANSLATED' };
    const changedView = {
      ...viewBefore,
      searchRequest: changedSearch,
      statusFilter: 'TRANSLATED',
      resultSortField: 'source',
      resultSortDirection: 'asc',
      rowIds: resultRows.map((row) => `${row.tmTextUnitId}:fr`),
    };
    await waitFor(() =>
      expect(readView()).toEqual({ ...changedView, restoreRowId: null, restoreScrollTop: null }),
    );
    expect(searchTextUnitsMock).toHaveBeenCalledWith({ ...changedSearch, limit: 1001 });

    fireEvent.click(screen.getByRole('button', { name: 'Browser Forward' }));
    expect(readLocation().url).toBe('/text-units/112?locale=fr');
    fireEvent.click(screen.getByRole('button', { name: 'Browser Back' }));
    await expectRestored(workbenchUrl, changedView, '112:fr', 1120);
  });

  it('uses the history snapshot when its session token has changed and query results were evicted', async () => {
    const { queryClient, token, workbenchUrl, viewBefore } = await prepareSortedWorkbench();
    fireEvent.click(screen.getByRole('button', { name: 'Details 112' }));
    saveWorkbenchSessionSearch(
      { repositoryIds: [23], localeTags: ['de'], statusFilter: 'APPROVED', limit: 10 },
      token,
    );
    queryClient.removeQueries({ queryKey: ['workbench-search'] });
    searchTextUnitsMock.mockClear();

    fireEvent.click(screen.getByRole('button', { name: 'Browser Back' }));
    await expectRestored(workbenchUrl, viewBefore, '112:fr', 1120);
    expect(searchTextUnitsMock).toHaveBeenCalledWith({ ...originalSearch, limit: 1001 });
    for (const [request] of searchTextUnitsMock.mock.calls) {
      expect(request).toEqual({ ...originalSearch, limit: 1001 });
    }
  });
});
