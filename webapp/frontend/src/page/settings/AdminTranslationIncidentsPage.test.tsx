import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type {
  ApiTranslationIncidentDetail,
  ApiTranslationIncidentPage,
  ApiTranslationIncidentSummary,
} from '../../api/translation-incidents';
import { AdminTranslationIncidentsPage } from './AdminTranslationIncidentsPage';

const mocks = vi.hoisted(() => ({
  fetchTranslationIncident: vi.fn(),
  fetchTranslationIncidents: vi.fn(),
  rejectTranslationIncident: vi.fn(),
  sendTranslationIncidentSlack: vi.fn(),
  updateTranslationIncidentStatus: vi.fn(),
}));

vi.mock('../../api/translation-incidents', () => mocks);
vi.mock('../../hooks/useUser', () => ({ useUser: () => ({ role: 'ROLE_ADMIN' }) }));
vi.mock('../../hooks/useRepositories', () => ({ useRepositories: () => ({ data: [] }) }));
vi.mock('../../hooks/useLocales', () => ({
  useLocales: () => ({
    data: [{ bcp47Tag: 'de' }, { bcp47Tag: 'fr-FR' }, { bcp47Tag: 'ja' }],
  }),
}));
vi.mock('../../components/virtual/useVirtualRows', () => ({
  useVirtualRows: ({ count }: { count: number }) => ({
    items: Array.from({ length: count }, (_, index) => ({
      index,
      key: index,
      start: index * 76,
      end: (index + 1) * 76,
      size: 76,
      lane: 0,
    })),
    totalSize: count * 76,
    measureElement: vi.fn(),
    scrollToIndex: vi.fn(),
  }),
}));

function incident(id: number, locale: string): ApiTranslationIncidentSummary {
  return {
    id,
    status: 'OPEN',
    resolution: 'PENDING_REVIEW',
    repositoryName: 'checkout',
    stringId: `checkout.incident-${id}`,
    observedLocale: locale,
    resolvedLocale: locale,
    reason: 'Review the translation.',
    sourceReference: null,
    lookupCandidateCount: 0,
    canReject: false,
    reviewProjectName: null,
    reviewProjectConfidence: null,
    selectedTranslationStatus: null,
    createdDate: '2026-09-01T12:00:00Z',
    lastModifiedDate: '2026-09-01T12:00:00Z',
    rejectedAt: null,
    closedAt: null,
    closedByUsername: null,
    incidentLink: null,
  };
}

function detail(summary: ApiTranslationIncidentSummary): ApiTranslationIncidentDetail {
  return {
    ...summary,
    lookupResolutionStatus: 'NOT_FOUND',
    localeResolutionStrategy: 'EXACT',
    localeUsedFallback: false,
    selectedTmTextUnitId: null,
    selectedTextUnitLink: null,
    selectedTmTextUnitCurrentVariantId: null,
    selectedTmTextUnitVariantId: null,
    selectedAssetPath: null,
    selectedSource: null,
    selectedTarget: null,
    selectedTargetComment: null,
    selectedIncludedInLocalizedFile: null,
    reviewProjectId: null,
    reviewProjectRequestId: null,
    reviewProjectLink: null,
    reviewProjectConfidenceScore: null,
    translationAuthorUsername: null,
    reviewerUsername: null,
    ownerUsername: null,
    translationAuthorSlackMention: null,
    reviewerSlackMention: null,
    ownerSlackMention: null,
    slackDestinationSource: null,
    slackChannelId: null,
    slackThreadTs: null,
    slackCanSend: false,
    slackNote: null,
    slackDraft: null,
    lookupCandidates: [],
    reviewProjectCandidates: [],
    rejectAuditComment: null,
    rejectAuditCommentId: null,
    rejectedByUsername: null,
  };
}

function page(items: ApiTranslationIncidentSummary[], totalElements = items.length) {
  return {
    items,
    page: 0,
    size: 100,
    totalElements,
    totalPages: Math.ceil(totalElements / 100),
    hasNext: totalElements > items.length,
    hasPrevious: false,
  } satisfies ApiTranslationIncidentPage;
}

function renderPage(search = '') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [{ path: '/translation-incidents', element: <AdminTranslationIncidentsPage /> }],
    { initialEntries: [`/translation-incidents${search}`] },
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

function chooseLocale(name: RegExp | string) {
  fireEvent.click(screen.getByRole('button', { name: 'Filter translation incidents by locale' }));
  fireEvent.click(within(screen.getByRole('menu')).getByRole('button', { name }));
}

describe('AdminTranslationIncidentsPage locale filter', () => {
  const frenchIncident = incident(23307, 'fr-FR');
  const japaneseIncident = incident(23308, 'ja');

  beforeEach(() => {
    vi.clearAllMocks();
    mocks.fetchTranslationIncidents.mockResolvedValue(page([frenchIncident]));
    mocks.fetchTranslationIncident.mockImplementation((id: number) =>
      Promise.resolve(detail(id === japaneseIncident.id ? japaneseIncident : frenchIncident)),
    );
  });

  it('initializes the locale from the URL and preserves a directly linked incident outside the first page', async () => {
    mocks.fetchTranslationIncidents.mockResolvedValue(page([incident(23306, 'fr-FR')], 200));
    const router = renderPage('?locale=fr-FR&incidentId=23307');

    expect(await screen.findByRole('heading', { name: frenchIncident.stringId })).toBeVisible();
    expect(
      screen.getByRole('button', { name: 'Filter translation incidents by locale' }),
    ).toHaveTextContent('fr-FR');
    expect(mocks.fetchTranslationIncidents).toHaveBeenCalledWith(
      expect.objectContaining({ locale: 'fr-FR', page: 0, size: 100 }),
    );
    expect(mocks.fetchTranslationIncident).toHaveBeenCalledWith(23307);
    expect(new URLSearchParams(router.state.location.search).get('incidentId')).toBe('23307');
  });

  it('refetches on locale changes, clears the previous selection, and preserves review filters', async () => {
    let resolveJapanesePage!: (value: ApiTranslationIncidentPage) => void;
    const japanesePage = new Promise<ApiTranslationIncidentPage>((resolve) => {
      resolveJapanesePage = resolve;
    });
    mocks.fetchTranslationIncidents.mockImplementation(({ locale }: { locale: string | null }) =>
      locale === 'ja' ? japanesePage : Promise.resolve(page([frenchIncident])),
    );
    const router = renderPage(
      '?locale=fr-FR&incidentId=23307&reviewType=TRANSLATION_QUALITY&reviewRunId=12',
    );
    await screen.findByRole('heading', { name: frenchIncident.stringId });

    chooseLocale(/^ja/);

    await waitFor(() =>
      expect(mocks.fetchTranslationIncidents).toHaveBeenLastCalledWith(
        expect.objectContaining({
          locale: 'ja',
          reviewType: 'TRANSLATION_QUALITY',
          reviewRunId: 12,
        }),
      ),
    );
    const params = new URLSearchParams(router.state.location.search);
    expect(params.get('locale')).toBe('ja');
    expect(params.get('reviewType')).toBe('TRANSLATION_QUALITY');
    expect(params.get('reviewRunId')).toBe('12');
    expect(params.has('incidentId')).toBe(false);
    expect(
      screen.queryByRole('heading', { name: frenchIncident.stringId }),
    ).not.toBeInTheDocument();

    act(() => resolveJapanesePage(page([japaneseIncident])));

    expect(await screen.findByRole('heading', { name: japaneseIncident.stringId })).toBeVisible();
    expect(new URLSearchParams(router.state.location.search).get('incidentId')).toBe('23308');
  });

  it('clears the locale filter to show incidents from all locales', async () => {
    mocks.fetchTranslationIncidents.mockImplementation(({ locale }: { locale: string | null }) =>
      Promise.resolve(page(locale ? [frenchIncident] : [japaneseIncident, frenchIncident])),
    );
    const router = renderPage('?locale=fr-FR&incidentId=23307');
    await screen.findByRole('heading', { name: frenchIncident.stringId });

    chooseLocale('All locales');

    expect(await screen.findByRole('heading', { name: japaneseIncident.stringId })).toBeVisible();
    expect(mocks.fetchTranslationIncidents).toHaveBeenLastCalledWith(
      expect.objectContaining({ locale: null }),
    );
    expect(new URLSearchParams(router.state.location.search).has('locale')).toBe(false);
    expect(
      screen.getByRole('button', { name: 'Filter translation incidents by locale' }),
    ).toHaveTextContent('All locales');
  });

  it('offers searchable locales beyond the limited incident page and clears details when none match', async () => {
    mocks.fetchTranslationIncidents.mockImplementation(({ locale }: { locale: string | null }) =>
      Promise.resolve(locale === 'ja' ? page([]) : page([frenchIncident], 1000)),
    );
    const router = renderPage('?incidentId=23307');
    await screen.findByRole('heading', { name: frenchIncident.stringId });
    fireEvent.click(screen.getByRole('button', { name: 'Filter translation incidents by locale' }));

    const menu = within(screen.getByRole('menu'));
    expect(menu.getByRole('button', { name: /^de/ })).toBeVisible();
    expect(menu.getByRole('button', { name: /^ja/ })).toBeVisible();
    fireEvent.change(menu.getByRole('searchbox'), { target: { value: 'Japanese' } });
    expect(menu.queryByRole('button', { name: /^de/ })).not.toBeInTheDocument();
    fireEvent.click(menu.getByRole('button', { name: /^ja/ }));

    expect(await screen.findByText('No translation incidents match this view.')).toBeVisible();
    expect(
      screen.queryByRole('heading', { name: frenchIncident.stringId }),
    ).not.toBeInTheDocument();
    expect(new URLSearchParams(router.state.location.search).has('incidentId')).toBe(false);
    expect(mocks.fetchTranslationIncidents).toHaveBeenLastCalledWith(
      expect.objectContaining({ locale: 'ja' }),
    );
  });
});
