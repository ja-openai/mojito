import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as AiReviewApi from '../../api/ai-review';
import type * as GlossariesApi from '../../api/glossaries';
import type * as TextUnitsApi from '../../api/text-units';
import { TextUnitDetailPage } from './TextUnitDetailPage';

const searchTextUnitsMock = vi.hoisted(() => vi.fn());
const fetchTextUnitHistoryMock = vi.hoisted(() => vi.fn());
const fetchAiTranslateTextUnitAttemptsMock = vi.hoisted(() => vi.fn());
const fetchGitBlameWithUsagesMock = vi.hoisted(() => vi.fn());
const fetchGlossariesMock = vi.hoisted(() => vi.fn());
const fetchGlossaryTermsMock = vi.hoisted(() => vi.fn());
const matchGlossaryTermsMock = vi.hoisted(() => vi.fn());
const requestAiReviewMock = vi.hoisted(() => vi.fn());

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({
    username: 'translator',
    role: 'ROLE_TRANSLATOR',
    canTranslateAllLocales: true,
    userLocales: [],
  }),
}));

vi.mock('../../hooks/useVisibleTextEditorEnabled', () => ({
  useVisibleTextEditorEnabled: () => true,
}));

vi.mock('../../api/text-units', async (importActual) => {
  const actual = await importActual<typeof TextUnitsApi>();
  return {
    ...actual,
    fetchAiTranslateTextUnitAttempts: fetchAiTranslateTextUnitAttemptsMock,
    fetchGitBlameWithUsages: fetchGitBlameWithUsagesMock,
    fetchTextUnitHistory: fetchTextUnitHistoryMock,
    searchTextUnits: searchTextUnitsMock,
  };
});

vi.mock('../../api/glossaries', async (importActual) => {
  const actual = await importActual<typeof GlossariesApi>();
  return {
    ...actual,
    fetchGlossaries: fetchGlossariesMock,
    fetchGlossaryTerms: fetchGlossaryTermsMock,
    matchGlossaryTerms: matchGlossaryTermsMock,
  };
});

vi.mock('../../api/ai-review', async (importActual) => {
  const actual = await importActual<typeof AiReviewApi>();
  return {
    ...actual,
    requestAiReview: requestAiReviewMock,
  };
});

beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', {
    configurable: true,
    value: vi.fn(),
  });
});

function renderTextUnitDetailPage(path = '/text-units/3?locale=pt-PT') {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/text-units/:tmTextUnitId" element={<TextUnitDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TextUnitDetailPage', () => {
  beforeEach(() => {
    searchTextUnitsMock.mockResolvedValue([
      {
        tmTextUnitId: 3,
        tmTextUnitVariantId: 30,
        tmTextUnitCurrentVariantId: 30,
        localeId: 17,
        name: 'checkout.pay',
        source: 'Pay {price} now',
        comment: 'Checkout payment copy',
        target: 'Pagar {price} agora',
        targetLocale: 'pt-PT',
        targetComment: null,
        assetPath: 'checkout.json',
        used: true,
        repositoryName: 'web',
        status: 'APPROVED',
        includedInLocalizedFile: true,
      },
    ]);
    fetchTextUnitHistoryMock.mockResolvedValue([]);
    fetchAiTranslateTextUnitAttemptsMock.mockResolvedValue([]);
    fetchGitBlameWithUsagesMock.mockResolvedValue([]);
    fetchGlossariesMock.mockResolvedValue({ glossaries: [] });
    fetchGlossaryTermsMock.mockResolvedValue({ terms: [] });
    matchGlossaryTermsMock.mockResolvedValue({ matchedTerms: [] });
    requestAiReviewMock.mockResolvedValue({
      message: { role: 'assistant', content: 'No issues found.' },
      suggestions: [],
      review: null,
    });
  });

  it('wires the protected editor into the target-locale detail route', async () => {
    const { container } = renderTextUnitDetailPage();

    expect(await screen.findByRole('textbox', { name: 'Translation' })).toHaveClass('ProseMirror');
    expect(screen.getByRole('button', { name: 'Hidden characters: Auto' })).toBeInTheDocument();
    expect(
      screen.getByRole('button', {
        name: 'Placeholder editing is off. Edit placeholders',
      }),
    ).toBeInTheDocument();

    await waitFor(() => {
      const protectedToken = container.querySelector('.visible-text-editor__protected-token');
      expect(protectedToken).toHaveTextContent('price');
      expect(protectedToken).toHaveClass('visible-text-editor__protected-token--icu-placeholder');
    });

    expect(searchTextUnitsMock).toHaveBeenCalledWith(
      expect.objectContaining({
        localeTags: ['pt-PT'],
        searchAttribute: 'tmTextUnitIds',
        searchText: '3',
        searchType: 'exact',
      }),
    );
  });
});
