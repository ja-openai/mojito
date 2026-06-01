import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type * as TextUnitsApi from '../../api/text-units';
import { WorkbenchPage } from './WorkbenchPage';

const searchTextUnitsMock = vi.hoisted(() => vi.fn(() => Promise.resolve([])));
const locationSearches: string[] = [];

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({
    username: 'admin',
    role: 'ROLE_ADMIN',
    canTranslateAllLocales: true,
    userLocales: [],
  }),
}));

vi.mock('../../hooks/useRepositories', () => ({
  useRepositories: () => ({
    data: [
      {
        id: 22,
        name: 'chatgpt-web',
        sourceLocale: { bcp47Tag: 'en' },
        repositoryLocales: [
          {
            locale: { bcp47Tag: 'fr' },
            toBeFullyTranslated: true,
            parentLocale: { bcp47Tag: 'en' },
          },
        ],
      },
      {
        id: 23,
        name: 'other-repo',
        sourceLocale: { bcp47Tag: 'en' },
        repositoryLocales: [
          {
            locale: { bcp47Tag: 'fr' },
            toBeFullyTranslated: true,
            parentLocale: { bcp47Tag: 'en' },
          },
        ],
      },
      {
        id: 24,
        name: 'test1',
        sourceLocale: { bcp47Tag: 'en' },
        repositoryLocales: [
          {
            locale: { bcp47Tag: 'fr-FR' },
            toBeFullyTranslated: true,
            parentLocale: { bcp47Tag: 'en' },
          },
        ],
      },
    ],
    isLoading: false,
    isError: false,
    error: null,
  }),
}));

vi.mock('../../api/text-units', async (importActual) => {
  const actual = await importActual<typeof TextUnitsApi>();
  return {
    ...actual,
    searchTextUnits: searchTextUnitsMock,
  };
});

afterEach(() => {
  searchTextUnitsMock.mockClear();
  locationSearches.length = 0;
});

function LocationProbe() {
  const location = useLocation();
  locationSearches.push(location.search);
  return null;
}

function renderWorkbench(path: string) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <LocationProbe />
        <WorkbenchPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('WorkbenchPage legacy links', () => {
  it('hydrates and searches from ICT string-id query params', async () => {
    renderWorkbench(
      '/workbench?repoNames[]=chatgpt-web&bcp47Tags[]=fr&searchAttribute=stringId&searchType=exact&searchText=SplashScreenV2.shouldWeBegin',
    );

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Select search options' })).toHaveTextContent(
        'String ID · Exact match',
      ),
    );

    expect(screen.getByLabelText('Search string ID')).toHaveValue('SplashScreenV2.shouldWeBegin');
    expect(screen.getByRole('button', { name: 'Select repositories' })).toHaveTextContent(
      'chatgpt-web',
    );
    expect(screen.getByRole('button', { name: 'Locales' })).toHaveTextContent('All locales');

    await waitFor(() =>
      expect(searchTextUnitsMock).toHaveBeenCalledWith(
        expect.objectContaining({
          repositoryIds: [22],
          localeTags: ['fr'],
          textSearch: {
            operator: 'AND',
            predicates: [
              {
                field: 'stringId',
                searchType: 'exact',
                value: 'SplashScreenV2.shouldWeBegin',
              },
            ],
          },
        }),
      ),
    );

    const finalSearch = locationSearches[locationSearches.length - 1] ?? '';
    expect(finalSearch).toMatch(/^\?ws=[0-9a-f-]+$/);
    expect(finalSearch).not.toContain('repoNames');
    expect(finalSearch).not.toContain('searchText');
  });

  it('prefers legacy query params over a stale workbench session token', async () => {
    renderWorkbench(
      '/workbench?repoNames%5B%5D=test1&bcp47Tags%5B%5D=fr-FR&searchAttribute=stringId&searchType=exact&searchText=SplashScreenV2.onYourMind&ws=d3f6c353-1291-4f49-9b89-e7d63274ea6a',
    );

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Select search options' })).toHaveTextContent(
        'String ID · Exact match',
      ),
    );

    expect(screen.getByLabelText('Search string ID')).toHaveValue('SplashScreenV2.onYourMind');
    expect(screen.getByRole('button', { name: 'Select repositories' })).toHaveTextContent('test1');
    expect(screen.getByRole('button', { name: 'Locales' })).toHaveTextContent('All locales');

    await waitFor(() =>
      expect(searchTextUnitsMock).toHaveBeenCalledWith(
        expect.objectContaining({
          repositoryIds: [24],
          localeTags: ['fr-FR'],
          textSearch: {
            operator: 'AND',
            predicates: [
              {
                field: 'stringId',
                searchType: 'exact',
                value: 'SplashScreenV2.onYourMind',
              },
            ],
          },
        }),
      ),
    );

    const finalSearch = locationSearches[locationSearches.length - 1] ?? '';
    expect(finalSearch).toMatch(/^\?ws=[0-9a-f-]+$/);
    expect(finalSearch).not.toContain('repoNames');
    expect(finalSearch).not.toContain('searchText');
  });
});
