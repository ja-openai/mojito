import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as AiReviewApi from '../../api/ai-review';
import type * as GlossariesApi from '../../api/glossaries';
import type * as TextUnitsApi from '../../api/text-units';
import { saveReviewProjectSearchEnabled } from '../../utils/reviewProjectSearchPreference';
import { TextUnitDetailPage } from './TextUnitDetailPage';

const searchTextUnitsMock = vi.hoisted(() => vi.fn());
const fetchTextUnitHistoryMock = vi.hoisted(() => vi.fn());
const fetchAiTranslateTextUnitAttemptsMock = vi.hoisted(() => vi.fn());
const fetchGitBlameWithUsagesMock = vi.hoisted(() => vi.fn());
const fetchGlossariesMock = vi.hoisted(() => vi.fn());
const fetchGlossaryTermsMock = vi.hoisted(() => vi.fn());
const matchGlossaryTermsMock = vi.hoisted(() => vi.fn());
const requestAiReviewMock = vi.hoisted(() => vi.fn());
const saveTextUnitMock = vi.hoisted(() => vi.fn());
const checkTextUnitIntegrityMock = vi.hoisted(() => vi.fn());
const editorPreference = vi.hoisted(() => ({ enabled: true }));
const fetchRepositoriesMock = vi.hoisted(() => vi.fn());

vi.mock('../../api/repositories', () => ({ fetchRepositories: fetchRepositoriesMock }));

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({
    username: 'translator',
    role: 'ROLE_TRANSLATOR',
    canTranslateAllLocales: true,
    userLocales: [],
  }),
}));

vi.mock('../../hooks/useVisibleTextEditorEnabled', () => ({
  useVisibleTextEditorEnabled: () => editorPreference.enabled,
}));

vi.mock('../../api/text-units', async (importActual) => {
  const actual = await importActual<typeof TextUnitsApi>();
  return {
    ...actual,
    fetchAiTranslateTextUnitAttempts: fetchAiTranslateTextUnitAttemptsMock,
    fetchGitBlameWithUsages: fetchGitBlameWithUsagesMock,
    fetchTextUnitHistory: fetchTextUnitHistoryMock,
    searchTextUnits: searchTextUnitsMock,
    saveTextUnit: saveTextUnitMock,
    checkTextUnitIntegrity: checkTextUnitIntegrityMock,
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
          <Route path="/workbench" element={<h1>Workbench dashboard</h1>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const mf2Source = `.input {$status :string}
.input {$count :integer select=exact}
.match $status $count
active 0 {{The queue is empty.}}
active * {{Queued jobs: {$count}.}}
* * {{Jobs: {$count}.}}`;
const mf2Target = mf2Source.replace('The queue is empty.', 'La file est vide.');

function mockMf2TextUnit(messageFormat: string | null | undefined) {
  searchTextUnitsMock.mockResolvedValue([
    {
      tmTextUnitId: 3,
      tmTextUnitVariantId: 30,
      tmTextUnitCurrentVariantId: 30,
      localeId: 17,
      name: 'mf2.status.count',
      source: mf2Source,
      target: mf2Target,
      messageFormat,
      targetLocale: 'fr',
      used: true,
      status: 'APPROVED',
      includedInLocalizedFile: true,
    },
  ]);
}

describe('TextUnitDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    editorPreference.enabled = true;
    fetchRepositoriesMock.mockResolvedValue([
      { id: 1, name: 'mobile' },
      { id: 2, name: 'web' },
    ]);
    checkTextUnitIntegrityMock.mockResolvedValue({ checkResult: true });
    saveTextUnitMock.mockImplementation((request: TextUnitsApi.SaveTextUnitRequest) =>
      Promise.resolve(request),
    );
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

  it('lazily enables shared Search and preserves the translation draft through collapse and disable', async () => {
    editorPreference.enabled = false;
    renderTextUnitDetailPage();
    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    await waitFor(() => expect(editor).toHaveValue('Pagar {price} agora'));
    expect(screen.queryByRole('button', { name: 'Search' })).not.toBeInTheDocument();
    expect(fetchRepositoriesMock).not.toHaveBeenCalled();

    fireEvent.change(editor, { target: { value: 'Pague {price} agora' } });
    act(() => saveReviewProjectSearchEnabled(true, 'translator'));
    const searchToggle = await screen.findByRole('button', { name: 'Search', expanded: false });
    expect(screen.queryByRole('searchbox', { name: 'Search translation' })).not.toBeInTheDocument();
    expect(fetchRepositoriesMock).not.toHaveBeenCalled();

    fireEvent.click(searchToggle);
    expect(searchToggle).toHaveAttribute('aria-expanded', 'true');
    const searchRegion = screen.getByRole('region', { name: 'Translation search' });
    const searchPanel = within(searchRegion);
    const repositorySelect = searchPanel.getByRole('button', { name: 'Select repositories' });
    await waitFor(() => expect(fetchRepositoriesMock).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(repositorySelect).toHaveTextContent('All repositories'));
    expect(searchPanel.getByRole('button', { name: 'Select search options' })).toHaveTextContent(
      'Translation · Contains',
    );
    const searchInput = searchPanel.getByLabelText('Search translation');
    expect(searchInput).toBeVisible();
    fireEvent.change(searchInput, { target: { value: '  pagamento  ' } });
    fireEvent.click(searchPanel.getByRole('button', { name: 'Add search condition' }));
    const compoundSearchInput = searchPanel.getAllByLabelText('Search translation')[0];
    expect(searchPanel.getAllByLabelText('Search translation')).toHaveLength(2);
    searchTextUnitsMock.mockResolvedValueOnce([
      {
        tmTextUnitId: 7,
        name: 'checkout.payment',
        source: 'Payment ready',
        target: 'Pagamento pronto',
        targetLocale: 'pt-PT',
        repositoryName: 'mobile',
        used: true,
        translationCreatedByUsername: 'reviewer.alice',
      },
    ]);
    fireEvent.submit(compoundSearchInput.closest('form')!);

    expect(await searchPanel.findByText('Saved by reviewer.alice')).toBeVisible();
    expect(searchTextUnitsMock).toHaveBeenLastCalledWith({
      repositoryIds: [1, 2],
      localeTags: ['pt-PT'],
      textSearch: {
        operator: 'AND',
        predicates: [{ field: 'target', searchType: 'contains', value: 'pagamento' }],
      },
      usedFilter: 'USED',
      limit: 51,
      offset: 0,
      orderedByTextUnitId: true,
    });
    fireEvent.click(searchToggle);
    expect(searchToggle).toHaveAttribute('aria-expanded', 'false');
    expect(searchRegion).toBeInTheDocument();
    expect(searchRegion).not.toBeVisible();
    expect(editor).toHaveValue('Pague {price} agora');
    fireEvent.click(searchToggle);
    expect(searchToggle).toHaveAttribute('aria-expanded', 'true');
    expect(searchRegion).toBeVisible();
    expect(searchPanel.getAllByLabelText('Search translation')[0]).toBe(compoundSearchInput);
    expect(compoundSearchInput).toBeVisible();
    expect(compoundSearchInput).toHaveValue('  pagamento  ');
    expect(searchPanel.getByText('Saved by reviewer.alice')).toBeVisible();
    expect(searchTextUnitsMock).toHaveBeenCalledTimes(2);

    act(() => saveReviewProjectSearchEnabled(false, 'translator'));
    await waitFor(() => expect(searchToggle).not.toBeInTheDocument());
    expect(searchRegion).not.toBeInTheDocument();
    expect(editor).toBeInTheDocument();
    expect(editor).toBeVisible();
    expect(editor).toHaveValue('Pague {price} agora');
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
    expect(saveTextUnitMock).not.toHaveBeenCalled();
  });

  it.each(['MF2', undefined])(
    'routes MF2 metadata %s to the guided editor and structured source preview',
    async (messageFormat) => {
      mockMf2TextUnit(messageFormat);

      const { container } = renderTextUnitDetailPage('/text-units/3?locale=fr');

      expect(
        await screen.findByRole('textbox', { name: 'Target status: active / count: 0' }),
      ).toHaveTextContent('La file est vide.');
      expect(screen.queryByRole('textbox', { name: 'Translation' })).not.toBeInTheDocument();
      expect(screen.getByText('Insert special')).toBeVisible();
      expect(container.querySelector('.mf2-document-preview')).toHaveTextContent('.match');
      expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    },
  );

  it('honors explicit non-MF2 metadata even when the source resembles MF2', async () => {
    mockMf2TextUnit(null);

    const { container } = renderTextUnitDetailPage('/text-units/3?locale=fr');

    await waitFor(() =>
      expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveTextContent(
        'La file est vide.',
      ),
    );
    expect(container.querySelector('.mf2-inline-editor')).not.toBeInTheDocument();
  });

  it('blocks invalid MF2 saves with assistance off and preserves the full valid document on save', async () => {
    editorPreference.enabled = false;
    mockMf2TextUnit('MF2');
    renderTextUnitDetailPage('/text-units/3?locale=fr');

    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    await waitFor(() => expect(editor).toHaveValue(mf2Target));
    expect(editor.tagName).toBe('TEXTAREA');

    fireEvent.change(editor, { target: { value: `${mf2Target}\n}}` } });
    expect(screen.getByRole('alert')).toHaveTextContent(/MF2 errors? before saving/);
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(checkTextUnitIntegrityMock).not.toHaveBeenCalled();
    expect(saveTextUnitMock).not.toHaveBeenCalled();

    fireEvent.change(editor, { target: { value: mf2Target.replace('active 0', 'active 1') } });
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(saveTextUnitMock).not.toHaveBeenCalled();

    const nextTarget = mf2Target.replace('La file est vide.', 'Aucune tâche en attente.');
    fireEvent.change(editor, { target: { value: nextTarget } });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(saveTextUnitMock).toHaveBeenCalledWith({
        tmTextUnitId: 3,
        localeId: 17,
        target: nextTarget,
        status: 'APPROVED',
        includedInLocalizedFile: true,
      }),
    );
    expect(checkTextUnitIntegrityMock).toHaveBeenCalledWith({
      tmTextUnitId: 3,
      content: nextTarget,
    });
  });

  it('blocks structurally changed AI suggestions and accepts equivalent MF2 spelling', async () => {
    editorPreference.enabled = false;
    mockMf2TextUnit('MF2');
    const safe = mf2Target
      .replace('select=exact', 'select=|exact|')
      .replace('La file est vide.', 'Aucune tâche en attente.');
    requestAiReviewMock.mockResolvedValue({
      message: { role: 'assistant', content: 'Suggestions' },
      suggestions: [
        { content: mf2Target.replace('active 0', 'active 1'), explanation: 'Changed a fixed key' },
        { content: safe, explanation: 'Equivalent syntax with improved prose' },
      ],
    });
    renderTextUnitDetailPage('/text-units/3?locale=fr');
    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    const buttons = await screen.findAllByRole('button', { name: 'Use' });
    expect(buttons[0]).toBeDisabled();
    expect(buttons[0]).toHaveAccessibleDescription(/preserve fixed selector values/);
    fireEvent.click(buttons[0]);
    expect(editor).toHaveValue(mf2Target);
    expect(buttons[1]).toBeEnabled();
    fireEvent.click(buttons[1]);
    expect(editor).toHaveValue(safe);
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
    expect(saveTextUnitMock).not.toHaveBeenCalled();
  });

  it.each(['/text-units/3?locale=pt-PT', '/text-units/3?locale=pt-PT&from=workbench'])(
    'returns to Workbench in the current tab from %s',
    async (path) => {
      const closeSpy = vi.spyOn(window, 'close').mockImplementation(() => undefined);
      renderTextUnitDetailPage(path);

      try {
        fireEvent.click(screen.getByRole('button', { name: 'Back to workbench' }));
        expect(await screen.findByRole('heading', { name: 'Workbench dashboard' })).toBeVisible();
        expect(closeSpy).not.toHaveBeenCalled();
      } finally {
        closeSpy.mockRestore();
      }
    },
  );
});
