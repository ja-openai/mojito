import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as AiReviewApi from '../../api/ai-review';
import type * as GlossariesApi from '../../api/glossaries';
import type * as TextUnitsApi from '../../api/text-units';
import type { ApiUserPreferences } from '../../api/userPreferences';
import { userPreferencesQueryKey } from '../../hooks/useUserPreferences';
import { buildWorkbenchDetailHash } from '../workbench/workbench-detail-link';
import type { WorkbenchReturnState } from '../workbench/workbench-types';
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
const fetchUserPreferencesMock = vi.hoisted(() => vi.fn());
const saveUserPreferencesMock = vi.hoisted(() => vi.fn());
vi.mock('../../api/userPreferences', () => ({
  fetchUserPreferences: fetchUserPreferencesMock,
  saveUserPreferences: saveUserPreferencesMock,
}));

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

const preferences: ApiUserPreferences = {
  initialized: true,
  aiReviewProfile: 'version_b',
  aiReviewReasoningEffort: 'low',
  aiReviewPreset: 'balanced',
  aiReviewAutomaticDisabled: false,
  worksetSize: null,
  preferredLocales: [],
  shortcutHelp: null,
  visibleTextEditorEnabled: false,
  reviewProjectSearchEnabled: false,
  defaultReviewTeamIds: [],
};

function WorkbenchDestination() {
  const location = useLocation();
  const { pathname, search } = location;
  const state: unknown = location.state;
  return (
    <>
      <h1>Workbench dashboard</h1>
      <output data-testid="workbench-destination">
        {JSON.stringify({ pathname, search, state })}
      </output>
    </>
  );
}

function renderTextUnitDetailPage(
  path:
    | string
    | {
        pathname: string;
        search?: string;
        hash?: string;
        state?: unknown;
      } = '/text-units/3?locale=pt-PT',
  previousPath?: string,
  accountPreferences: ApiUserPreferences = preferences,
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  queryClient.setQueryData(userPreferencesQueryKey('translator'), accountPreferences);

  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={previousPath ? [previousPath, path] : [path]}>
          <Routes>
            <Route path="/text-units/:tmTextUnitId" element={<TextUnitDetailPage />} />
            <Route path="/workbench" element={<WorkbenchDestination />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    ),
  };
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
    requestAiReviewMock.mockReset();
    fetchUserPreferencesMock.mockReset();
    fetchUserPreferencesMock.mockResolvedValue(preferences);
    saveUserPreferencesMock.mockReset();
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

  it('keeps manual review, follow-up and retry on the selected preset with automatic review disabled', async () => {
    editorPreference.enabled = false;
    renderTextUnitDetailPage('/text-units/3?locale=pt-PT', undefined, {
      ...preferences,
      aiReviewPreset: 'ultra',
      aiReviewAutomaticDisabled: true,
    });
    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    await waitFor(() => expect(editor).toHaveValue('Pagar {price} agora'));
    fireEvent.change(editor, { target: { value: 'Pagar {price} hoje' } });
    expect(requestAiReviewMock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    await screen.findByText('No issues found.');
    expect(requestAiReviewMock.mock.calls[0][0]).toMatchObject({
      presetId: 'ultra',
      requestType: 'manual',
      surface: 'text_unit_detail',
      target: 'Pagar {price} hoje',
    });
    requestAiReviewMock.mockRejectedValueOnce(new Error('Temporary review failure'));
    fireEvent.change(
      screen.getByPlaceholderText('Chat with AI: rephrase, adjust the tone, or ask a question…'),
      {
        target: { value: 'Explain the terminology.' },
      },
    );
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(3));
    expect(requestAiReviewMock.mock.calls[1][0]).toMatchObject({
      presetId: 'ultra',
      requestType: 'follow_up',
      surface: 'text_unit_detail',
    });
    expect(requestAiReviewMock.mock.calls[2][0]).toMatchObject({
      presetId: 'ultra',
      requestType: 'retry',
      surface: 'text_unit_detail',
      target: 'Pagar {price} hoje',
    });
    for (const [request] of requestAiReviewMock.mock.calls) {
      expect(request).not.toHaveProperty('profileId');
      expect(request).not.toHaveProperty('reasoningEffort');
      expect(request).not.toHaveProperty('modelName');
    }
  });

  it('keeps settings beside the AI Chat Review title while the conversation is collapsed', async () => {
    renderTextUnitDetailPage();
    await screen.findByText('No issues found.');
    const titleButton = screen.getByRole('button', { name: 'AI Chat Review' });
    const settingsButton = screen.getByRole('button', { name: 'AI review settings' });
    const speedButton = screen.getByRole('button', { name: 'Review speed: Balanced' });
    expect(titleButton.parentElement).toContainElement(settingsButton);
    expect(titleButton.parentElement).toContainElement(speedButton);
    expect(titleButton).not.toContainElement(settingsButton);
    expect(screen.queryByRole('combobox', { name: 'Model' })).not.toBeInTheDocument();
    fireEvent.click(titleButton);
    expect(
      screen.queryByPlaceholderText('Chat with AI: rephrase, adjust the tone, or ask a question…'),
    ).not.toBeInTheDocument();
    expect(settingsButton).toBeVisible();
    expect(speedButton).toBeVisible();
    fireEvent.click(settingsButton);
    expect(screen.getByRole('checkbox', { name: 'Review automatically' })).toBeVisible();
    expect(screen.queryByRole('combobox', { name: 'Model' })).not.toBeInTheDocument();
  });

  it.each([
    ['version_a', 'high', 'fast', 'Fast'],
    ['version_b', 'medium', 'thorough', 'Thorough'],
    ['version_b', 'high', 'deep', 'Deep'],
    ['version_b', 'low', 'balanced', 'Balanced'],
  ] as const)(
    'uses the compatible preset for legacy %s/%s preferences',
    async (profile, effort, preset, label) => {
      renderTextUnitDetailPage('/text-units/3?locale=pt-PT', undefined, {
        ...preferences,
        aiReviewPreset: undefined,
        aiReviewProfile: profile,
        aiReviewReasoningEffort: effort,
      });

      await screen.findByText('No issues found.');

      expect(screen.getByRole('button', { name: `Review speed: ${label}` })).toBeEnabled();
      expect(requestAiReviewMock.mock.calls[0][0]).toMatchObject({
        presetId: preset,
        requestType: 'automatic',
      });
      expect(saveUserPreferencesMock).not.toHaveBeenCalled();
    },
  );

  it('aborts and discards a manual response after changing presets', async () => {
    let finishOldReview!: (value: {
      message: { role: 'assistant'; content: string };
      suggestions: [];
    }) => void;
    requestAiReviewMock.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishOldReview = resolve;
        }),
    );
    saveUserPreferencesMock.mockResolvedValue({
      ...preferences,
      aiReviewPreset: 'fast',
      aiReviewAutomaticDisabled: true,
    });
    renderTextUnitDetailPage('/text-units/3?locale=pt-PT', undefined, {
      ...preferences,
      aiReviewAutomaticDisabled: true,
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Review' }));
    await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(1));
    const oldSignal = (requestAiReviewMock.mock.calls[0][1] as { signal: AbortSignal }).signal;
    fireEvent.click(screen.getByRole('button', { name: 'Review speed: Balanced' }));
    const slider = screen.getByRole('slider', { name: 'Review speed' });
    fireEvent.change(slider, { target: { value: '1' } });
    fireEvent.pointerUp(slider);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Review speed: Fast' })).toBeEnabled(),
    );
    expect(saveUserPreferencesMock.mock.calls[0][0]).toEqual({ aiReviewPreset: 'fast' });
    expect(oldSignal.aborted).toBe(true);
    await act(async () => {
      finishOldReview({
        message: { role: 'assistant', content: 'Old preset answer' },
        suggestions: [],
      });
      await Promise.resolve();
    });
    expect(screen.queryByText('Old preset answer')).not.toBeInTheDocument();
    expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    await screen.findByText('No issues found.');
    expect(requestAiReviewMock.mock.calls[1][0]).toMatchObject({
      presetId: 'fast',
      requestType: 'manual',
      surface: 'text_unit_detail',
    });
  });

  it('saves the selected speed before replacing an automatic review and ignores its old result', async () => {
    let finishOldReview!: (value: AiReviewApi.AiReviewResponse) => void;
    let finishSave!: (value: ApiUserPreferences) => void;
    requestAiReviewMock.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishOldReview = resolve;
        }),
    );
    saveUserPreferencesMock.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishSave = resolve;
        }),
    );
    const { queryClient } = renderTextUnitDetailPage();
    await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(1));
    const oldSignal = (requestAiReviewMock.mock.calls[0][1] as { signal: AbortSignal }).signal;
    expect(requestAiReviewMock.mock.calls[0][0]).toMatchObject({ presetId: 'balanced' });

    fireEvent.click(screen.getByRole('button', { name: 'Review speed: Balanced' }));
    const slider = screen.getByRole('slider', { name: 'Review speed' });
    fireEvent.change(slider, { target: { value: '3' } });
    fireEvent.keyUp(slider, { key: 'ArrowRight' });
    await waitFor(() => expect(saveUserPreferencesMock).toHaveBeenCalledTimes(1));
    expect(saveUserPreferencesMock.mock.calls[0][0]).toEqual({ aiReviewPreset: 'thorough' });
    expect(screen.getByRole('button', { name: 'Review speed: Balanced' })).toBeDisabled();
    expect(queryClient.getQueryData(userPreferencesQueryKey('translator'))).toMatchObject({
      aiReviewPreset: 'balanced',
    });
    expect(oldSignal.aborted).toBe(false);
    expect(requestAiReviewMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      finishSave({ ...preferences, aiReviewPreset: 'thorough' });
      await Promise.resolve();
    });
    await screen.findByText('No issues found.');
    expect(screen.getByRole('button', { name: 'Review speed: Thorough' })).toBeEnabled();
    expect(queryClient.getQueryData(userPreferencesQueryKey('translator'))).toMatchObject({
      aiReviewPreset: 'thorough',
    });
    expect(oldSignal.aborted).toBe(true);
    expect(requestAiReviewMock.mock.calls[1][0]).toMatchObject({
      presetId: 'thorough',
      requestType: 'automatic',
      surface: 'text_unit_detail',
    });

    await act(async () => {
      finishOldReview({
        message: { role: 'assistant', content: 'Old balanced review' },
        suggestions: [],
      });
      await Promise.resolve();
    });
    expect(screen.queryByText('Old balanced review')).not.toBeInTheDocument();
    expect(screen.getByText('No issues found.')).toBeVisible();
    expect(requestAiReviewMock).toHaveBeenCalledTimes(2);
  });

  it('wires the protected editor into the target-locale detail route', async () => {
    const { container } = renderTextUnitDetailPage();

    expect(await screen.findByRole('textbox', { name: 'Translation' })).toHaveClass('ProseMirror');
    expect(screen.getByRole('button', { name: 'Characters' })).toBeVisible();
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
    const { queryClient } = renderTextUnitDetailPage();
    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    await waitFor(() => expect(editor).toHaveValue('Pagar {price} agora'));
    expect(screen.getByRole('button', { name: 'Characters' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Search' })).not.toBeInTheDocument();
    expect(fetchRepositoriesMock).not.toHaveBeenCalled();

    fireEvent.change(editor, { target: { value: 'Pague {price} agora' } });
    act(() => {
      queryClient.setQueryData(userPreferencesQueryKey('translator'), {
        ...preferences,
        reviewProjectSearchEnabled: true,
      });
    });
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

    act(() => {
      queryClient.setQueryData(userPreferencesQueryKey('translator'), preferences);
    });
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
      expect(screen.getByText('Characters')).toBeVisible();
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

  it('keeps the origin URL and return snapshot when Back has no originating history entry', async () => {
    const workbenchReturn = {
      searchRequest: { repositoryIds: [7], localeTags: ['pt-PT'], limit: 200 },
      resultSortField: 'tmTextUnitId',
      resultSortDirection: 'desc',
      rowId: '3-pt-PT',
      scrollTop: 1200,
    };
    renderTextUnitDetailPage({
      pathname: '/text-units/3',
      search: '?locale=pt-PT',
      state: { workbenchUrl: '/workbench?ws=origin-session', workbenchReturn },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Back to workbench' }));
    expect(await screen.findByRole('heading', { name: 'Workbench dashboard' })).toBeVisible();
    expect(JSON.parse(screen.getByTestId('workbench-destination').textContent!)).toEqual({
      pathname: '/workbench',
      search: '?ws=origin-session',
      state: { workbenchReturn },
    });
  });

  it('opens a Workbench with the linked filters, sort, and text unit from a new-tab URL', async () => {
    window.sessionStorage.clear();
    const searchRequest: TextUnitsApi.TextUnitSearchRequest = {
      repositoryIds: [7, 8],
      localeTags: ['pt-PT', 'fr'],
      textSearch: {
        operator: 'OR',
        predicates: [
          { field: 'source', searchType: 'contains', value: 'Pay & save + café #1' },
          { field: 'comment', searchType: 'exact', value: 'Checkout' },
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
    const hash = buildWorkbenchDetailHash(searchRequest, 'translation', 'desc');
    renderTextUnitDetailPage(`/text-units/3?locale=pt-PT${hash}`);

    const openButton = screen.getByRole('button', { name: 'Open in Workbench' });
    expect(openButton).toHaveTextContent('Open in Workbench');
    fireEvent.click(openButton);

    expect(await screen.findByRole('heading', { name: 'Workbench dashboard' })).toBeVisible();
    expect(JSON.parse(screen.getByTestId('workbench-destination').textContent!)).toEqual({
      pathname: '/workbench',
      search: '',
      state: {
        workbenchReturn: {
          searchRequest,
          resultSortField: 'translation',
          resultSortDirection: 'desc',
          rowId: '3:pt-PT',
          scrollTop: 0,
        },
      },
    });
  });

  it.each(['#workbench=%7Bbroken', '#workbench=%7B%22searchRequest%22%3Anull%7D'])(
    'keeps the standalone fallback for malformed Workbench context %s',
    async (hash) => {
      renderTextUnitDetailPage(`/text-units/3?locale=pt-PT${hash}`);

      expect(screen.queryByRole('button', { name: 'Open in Workbench' })).not.toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Back to workbench' }));

      expect(await screen.findByRole('heading', { name: 'Workbench dashboard' })).toBeVisible();
      expect(JSON.parse(screen.getByTestId('workbench-destination').textContent!)).toEqual({
        pathname: '/workbench',
        search: '',
        state: null,
      });
    },
  );

  it.each([false, true])(
    'prefers the same-tab origin to URL context when originating history is available: %s',
    async (hasOriginHistory) => {
      const historyLength = vi
        .spyOn(window.history, 'length', 'get')
        .mockReturnValue(hasOriginHistory ? 2 : 1);
      const workbenchReturn: WorkbenchReturnState = {
        searchRequest: { repositoryIds: [7], localeTags: ['pt-PT'], limit: 200 },
        resultSortField: 'source',
        resultSortDirection: 'asc',
        rowId: '3:pt-PT',
        scrollTop: 1200,
        rowOffset: -25,
      };
      const workbenchUrl = '/workbench?ws=origin-session';

      try {
        renderTextUnitDetailPage(
          {
            pathname: '/text-units/3',
            search: '?locale=pt-PT',
            hash: buildWorkbenchDetailHash(
              { repositoryIds: [99], localeTags: ['fr'], limit: 10 },
              'translation',
              'desc',
            ),
            state: { from: '/workbench', workbenchUrl, workbenchReturn },
          },
          hasOriginHistory ? workbenchUrl : undefined,
        );

        expect(screen.queryByRole('button', { name: 'Open in Workbench' })).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: 'Back to workbench' }));

        expect(await screen.findByRole('heading', { name: 'Workbench dashboard' })).toBeVisible();
        expect(JSON.parse(screen.getByTestId('workbench-destination').textContent!)).toEqual({
          pathname: '/workbench',
          search: '?ws=origin-session',
          state: hasOriginHistory ? null : { workbenchReturn },
        });
      } finally {
        historyLength.mockRestore();
      }
    },
  );

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
