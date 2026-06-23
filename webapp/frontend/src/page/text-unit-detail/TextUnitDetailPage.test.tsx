import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { TextUnitDetailPage } from './TextUnitDetailPage';

type RenderedViewProps = {
  backLabel: string;
  isAiCollapsed: boolean;
  isCmsHandoff: boolean;
  isGlossaryCollapsed: boolean;
  onBack: () => void;
  originContext: string | null;
};

const mocks = vi.hoisted(() => ({
  fetchAiTranslateTextUnitAttempts: vi.fn(),
  fetchGitBlameWithUsages: vi.fn(),
  fetchGlossaries: vi.fn(),
  fetchGlossaryTerms: vi.fn(),
  fetchSourceTextUnit: vi.fn(),
  fetchTextUnitHistory: vi.fn(),
  matchGlossaryTerms: vi.fn(),
  renderedViewProps: {
    current: null as RenderedViewProps | null,
  },
  requestAiReview: vi.fn(),
  searchTextUnits: vi.fn(),
  useUser: vi.fn(),
}));

vi.mock('../../api/ai-review', () => ({
  formatAiReviewError: vi.fn((error: unknown) => ({
    detail: error instanceof Error ? error.message : null,
    message: 'AI review failed.',
  })),
  requestAiReview: mocks.requestAiReview,
}));

vi.mock('../../api/glossaries', () => ({
  fetchGlossaries: mocks.fetchGlossaries,
  fetchGlossaryTerms: mocks.fetchGlossaryTerms,
  matchGlossaryTerms: mocks.matchGlossaryTerms,
}));

vi.mock('../../api/text-units', () => ({
  deleteTextUnitCurrentVariant: vi.fn(),
  fetchAiTranslateTextUnitAttempts: mocks.fetchAiTranslateTextUnitAttempts,
  fetchGitBlameWithUsages: mocks.fetchGitBlameWithUsages,
  fetchSourceTextUnit: mocks.fetchSourceTextUnit,
  fetchTextUnitHistory: mocks.fetchTextUnitHistory,
  saveTextUnit: vi.fn(),
  searchTextUnits: mocks.searchTextUnits,
}));

vi.mock('../../hooks/useUser', () => ({
  useUser: mocks.useUser,
}));

vi.mock('../../hooks/useVisibleTextEditorEnabled', () => ({
  useVisibleTextEditorEnabled: () => true,
}));

vi.mock('./TextUnitDetailPageView', () => ({
  TextUnitDetailPageView: (props: RenderedViewProps) => {
    mocks.renderedViewProps.current = props;
    return (
      <div data-testid="text-unit-detail-page-view">
        {props.isAiCollapsed ? 'collapsed' : 'expanded'}
      </div>
    );
  },
}));

const textUnit = {
  tmTextUnitId: 1,
  tmTextUnitVariantId: 10,
  tmTextUnitCurrentVariantId: 10,
  localeId: 7,
  name: 'cms.growth-email-copy.welcome-email.default.copy',
  source: 'Welcome to Acme. Start your first project in minutes.',
  comment: 'Friendly welcome sentence. Keep Acme untranslated.',
  target: '',
  targetLocale: 'fr-FR',
  used: true,
  repositoryName: 'cms-growth-email-copy',
  status: 'TRANSLATION_NEEDED' as const,
  includedInLocalizedFile: false,
};

describe('TextUnitDetailPage', () => {
  beforeEach(() => {
    mocks.fetchAiTranslateTextUnitAttempts.mockResolvedValue([]);
    mocks.fetchGitBlameWithUsages.mockResolvedValue([]);
    mocks.fetchGlossaries.mockResolvedValue({ glossaries: [], totalCount: 0 });
    mocks.fetchGlossaryTerms.mockResolvedValue({ localeTags: [], terms: [], totalCount: 0 });
    mocks.fetchSourceTextUnit.mockResolvedValue(textUnit);
    mocks.fetchTextUnitHistory.mockResolvedValue([]);
    mocks.matchGlossaryTerms.mockResolvedValue({ matchedTerms: [] });
    mocks.renderedViewProps.current = null;
    mocks.requestAiReview.mockResolvedValue({
      message: { content: 'Looks good.', role: 'assistant' },
      suggestions: [],
    });
    mocks.searchTextUnits.mockResolvedValue([textUnit]);
    mocks.useUser.mockReturnValue({
      canTranslateAllLocales: true,
      role: 'ROLE_ADMIN',
      userLocales: [],
    });
  });

  it('keeps AI review collapsed for CMS handoffs until the author opens it', async () => {
    renderPage(
      '/text-units/1?locale=fr-FR&returnTo=%2Fsettings%2Fsystem%2Fcontent-cms%3FprojectId%3D1%26entryId%3D1%26fieldId%3D1',
    );

    await screen.findByTestId('text-unit-detail-page-view');

    await waitFor(() => {
      expect(latestViewProps().isCmsHandoff).toBe(true);
      expect(latestViewProps().isAiCollapsed).toBe(true);
      expect(latestViewProps().isGlossaryCollapsed).toBe(true);
      expect(latestViewProps().originContext).toBeNull();
    });
    expect(mocks.requestAiReview).not.toHaveBeenCalled();
  });

  it('keeps the existing automatic AI review outside CMS handoffs', async () => {
    renderPage('/text-units/1?locale=fr-FR');

    await screen.findByTestId('text-unit-detail-page-view');

    await waitFor(() => {
      expect(latestViewProps().isCmsHandoff).toBe(false);
      expect(latestViewProps().isAiCollapsed).toBe(false);
      expect(latestViewProps().isGlossaryCollapsed).toBe(false);
      expect(mocks.requestAiReview).toHaveBeenCalledTimes(1);
    });
  });

  it('returns direct source-only CMS detail links to Product copy', async () => {
    const router = renderPage(
      '/text-units/1?returnTo=%2Fsettings%2Fsystem%2Fcontent-cms%3FprojectId%3D1%26entryId%3D1%26fieldId%3D1',
    );

    await screen.findByTestId('text-unit-detail-page-view');

    await waitFor(() => {
      expect(latestViewProps().isCmsHandoff).toBe(true);
      expect(latestViewProps().backLabel).toBe('Back to Product copy');
    });

    act(() => {
      latestViewProps().onBack();
    });

    expect(router.state.location.pathname).toBe('/settings/system/content-cms');
    expect(router.state.location.search).toBe('?projectId=1&entryId=1&fieldId=1');
  });

  it('restores Product copy breadcrumb and target locale from durable CMS detail params', async () => {
    const router = renderPage(
      '/text-units/1?locale=fr-FR&returnTo=%2Fsettings%2Fsystem%2Fcontent-cms%3FprojectId%3D1%26entryId%3D1%26fieldId%3D1%26locale%3Dfr-FR&cmsProject=Growth+email+copy&cmsEntry=Welcome+email&cmsField=Headline',
    );

    await screen.findByTestId('text-unit-detail-page-view');

    await waitFor(() => {
      expect(latestViewProps().backLabel).toBe('Back to Product copy');
      expect(latestViewProps().originContext).toBe(
        'Product copy: Growth email copy / Welcome email / Headline',
      );
    });

    act(() => {
      latestViewProps().onBack();
    });

    expect(router.state.location.pathname).toBe('/settings/system/content-cms');
    expect(router.state.location.search).toBe('?projectId=1&entryId=1&fieldId=1&locale=fr-FR');
  });

  it('returns unavailable CMS translation detail links to inline Product copy recovery', async () => {
    mocks.searchTextUnits.mockResolvedValue([]);
    const router = renderPage(
      '/text-units/1?locale=ja-JP&returnTo=%2Fsettings%2Fsystem%2Fcontent-cms%3FprojectId%3D1%26entryId%3D1%26fieldId%3D1%26locale%3Dja-JP',
    );

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/settings/system/content-cms');
      expect(router.state.location.search).toBe('?projectId=1&entryId=1&fieldId=1&locale=ja-JP');
    });

    expect(mocks.searchTextUnits).toHaveBeenCalledWith(
      expect.objectContaining({
        localeTags: ['ja-JP'],
        searchText: '1',
      }),
    );
    expect(screen.queryByTestId('text-unit-detail-page-view')).not.toBeInTheDocument();
  });
});

function renderPage(initialEntry: string) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  });
  const router = createMemoryRouter(
    [
      {
        element: <TextUnitDetailPage />,
        path: '/text-units/:tmTextUnitId',
      },
    ],
    { initialEntries: [initialEntry] },
  );

  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );

  return router;
}

function latestViewProps() {
  if (!mocks.renderedViewProps.current) {
    throw new Error('Expected TextUnitDetailPageView to render.');
  }
  return mocks.renderedViewProps.current;
}
