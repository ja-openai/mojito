import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as AiReviewApi from '../../api/ai-review';
import type * as GlossariesApi from '../../api/glossaries';
import type * as TextUnitsApi from '../../api/text-units';
import type { ApiUserPreferences } from '../../api/userPreferences';
import { userPreferencesQueryKey } from '../../hooks/useUserPreferences';
import { installProseMirrorDomMock } from '../../test/proseMirrorDom';
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
const currentUserRole = vi.hoisted(() => ({ role: 'ROLE_TRANSLATOR' }));
vi.mock('../../api/userPreferences', () => ({
  fetchUserPreferences: fetchUserPreferencesMock,
  saveUserPreferences: saveUserPreferencesMock,
}));

vi.mock('../../api/repositories', () => ({ fetchRepositories: fetchRepositoriesMock }));

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({
    username: 'translator',
    role: currentUserRole.role,
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
  accountPreferences: ApiUserPreferences | null = preferences,
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  if (accountPreferences) {
    queryClient.setQueryData(userPreferencesQueryKey('translator'), accountPreferences);
  }

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
    currentUserRole.role = 'ROLE_TRANSLATOR';
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

  it('falls back from saved Ultra for manual review, follow-up and retry without rewriting preferences', async () => {
    editorPreference.enabled = false;
    const { queryClient } = renderTextUnitDetailPage('/text-units/3?locale=pt-PT', undefined, {
      ...preferences,
      aiReviewPreset: 'ultra',
      aiReviewAutomaticDisabled: true,
      aiReviewStyle: 'corrections_only',
    });
    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    expect(screen.getByRole('button', { name: 'Review speed: Balanced' })).toHaveAttribute(
      'aria-disabled',
      'false',
    );
    await waitFor(() => expect(editor).toHaveValue('Pagar {price} agora'));
    fireEvent.change(editor, { target: { value: 'Pagar {price} hoje' } });
    expect(requestAiReviewMock).not.toHaveBeenCalled();
    const reviewButton = screen.getByRole('button', { name: 'Review' });
    expect(reviewButton.closest('form')).toContainElement(
      screen.getByPlaceholderText('Chat with AI: rephrase, adjust the tone, or ask a question…'),
    );
    expect(screen.queryByRole('button', { name: 'Ask' })).not.toBeInTheDocument();
    fireEvent.click(reviewButton);
    await screen.findByText('No issues found.');
    expect(requestAiReviewMock.mock.calls[0][0]).toMatchObject({
      presetId: 'balanced',
      requestType: 'manual',
      reviewStyle: 'corrections_only',
      surface: 'text_unit_detail',
      target: 'Pagar {price} hoje',
    });
    expect(queryClient.getQueryData(userPreferencesQueryKey('translator'))).toMatchObject({
      aiReviewPreset: 'ultra',
      aiReviewAutomaticDisabled: true,
      aiReviewStyle: 'corrections_only',
    });
    expect(saveUserPreferencesMock).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Review' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ask' })).toBeDisabled();
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
      presetId: 'balanced',
      requestType: 'follow_up',
      reviewStyle: 'corrections_only',
      surface: 'text_unit_detail',
    });
    expect(requestAiReviewMock.mock.calls[2][0]).toMatchObject({
      presetId: 'balanced',
      requestType: 'retry',
      reviewStyle: 'corrections_only',
      surface: 'text_unit_detail',
      target: 'Pagar {price} hoje',
    });
    for (const [request] of requestAiReviewMock.mock.calls) {
      expect(request).not.toHaveProperty('profileId');
      expect(request).not.toHaveProperty('reasoningEffort');
      expect(request).not.toHaveProperty('modelName');
    }
  });

  it('waits for a saved style before refreshing automatic review and discards the previous style result', async () => {
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
    renderTextUnitDetailPage('/text-units/3?locale=pt-PT', undefined, {
      ...preferences,
      aiReviewStyle: 'corrections_only',
    });
    await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(1));
    const oldSignal = (requestAiReviewMock.mock.calls[0][1] as { signal: AbortSignal }).signal;
    expect(requestAiReviewMock.mock.calls[0][0]).toMatchObject({ reviewStyle: 'corrections_only' });

    fireEvent.click(screen.getByRole('button', { name: 'Review speed: Balanced' }));
    const style = screen.getByRole('combobox', { name: 'Review style' });
    fireEvent.change(style, { target: { value: 'corrections_and_alternatives' } });
    await waitFor(() => expect(saveUserPreferencesMock).toHaveBeenCalledTimes(1));
    expect(saveUserPreferencesMock.mock.calls[0][0]).toEqual({
      aiReviewStyle: 'corrections_and_alternatives',
    });
    expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
    expect(oldSignal.aborted).toBe(false);
    await act(async () => {
      finishSave({ ...preferences, aiReviewStyle: 'corrections_and_alternatives' });
      await Promise.resolve();
    });
    await screen.findByText('No issues found.');
    expect(style).toHaveValue('corrections_and_alternatives');
    expect(oldSignal.aborted).toBe(true);
    expect(requestAiReviewMock.mock.calls[1][0]).toMatchObject({
      presetId: 'balanced',
      reviewStyle: 'corrections_and_alternatives',
      requestType: 'automatic',
      surface: 'text_unit_detail',
    });
    await act(async () => {
      finishOldReview({
        message: { role: 'assistant', content: 'Old corrections-only answer' },
        suggestions: [],
      });
      await Promise.resolve();
    });
    expect(screen.queryByText('Old corrections-only answer')).not.toBeInTheDocument();
    expect(requestAiReviewMock).toHaveBeenCalledTimes(2);
  });

  it('changes style with automatic review off without starting a review, then uses it for one-off Review', async () => {
    renderTextUnitDetailPage('/text-units/3?locale=pt-PT', undefined, {
      ...preferences,
      aiReviewAutomaticDisabled: true,
    });
    const reviewButton = await screen.findByRole('button', { name: 'Review' });
    await waitFor(() => expect(reviewButton).toBeEnabled());
    fireEvent.click(reviewButton);
    await screen.findByText('No issues found.');
    expect(requestAiReviewMock.mock.calls[0][0]).toMatchObject({
      reviewStyle: 'corrections_and_alternatives',
    });
    saveUserPreferencesMock.mockResolvedValue({
      ...preferences,
      aiReviewAutomaticDisabled: true,
      aiReviewStyle: 'corrections_only',
    });
    fireEvent.click(screen.getByRole('button', { name: 'Review speed: Balanced' }));
    fireEvent.change(screen.getByRole('combobox', { name: 'Review style' }), {
      target: { value: 'corrections_only' },
    });
    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: 'Review style' })).toHaveValue(
        'corrections_only',
      ),
    );
    expect(saveUserPreferencesMock.mock.calls[0][0]).toEqual({ aiReviewStyle: 'corrections_only' });
    expect(screen.getByRole('checkbox', { name: 'Automatic review' })).not.toBeChecked();
    expect(screen.queryByText('No issues found.')).not.toBeInTheDocument();
    expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    await screen.findByText('No issues found.');
    expect(requestAiReviewMock.mock.calls[1][0]).toMatchObject({
      presetId: 'balanced',
      reviewStyle: 'corrections_only',
      requestType: 'manual',
      surface: 'text_unit_detail',
    });
    expect(screen.getByRole('checkbox', { name: 'Automatic review' })).not.toBeChecked();
    expect(requestAiReviewMock).toHaveBeenCalledTimes(2);
  });

  it('saves score visibility without cancelling or repeating the review or clearing its result', async () => {
    let finishReview!: (value: AiReviewApi.AiReviewResponse) => void;
    requestAiReviewMock.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishReview = resolve;
        }),
    );
    renderTextUnitDetailPage();
    await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(1));
    const signal = (requestAiReviewMock.mock.calls[0][1] as { signal: AbortSignal }).signal;
    fireEvent.click(screen.getByRole('button', { name: 'Review speed: Balanced' }));
    expect(screen.getByRole('combobox', { name: 'Review style' })).toHaveValue(
      'corrections_and_alternatives',
    );
    const scoreToggle = screen.getByRole('checkbox', { name: 'Show score' });
    expect(scoreToggle).toBeChecked();
    saveUserPreferencesMock.mockResolvedValue({ ...preferences, aiReviewShowScore: false });
    fireEvent.click(scoreToggle);
    await waitFor(() => expect(scoreToggle).not.toBeChecked());
    expect(saveUserPreferencesMock.mock.calls[0][0]).toEqual({ aiReviewShowScore: false });
    expect(signal.aborted).toBe(false);
    expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
    await act(async () => {
      finishReview({
        message: { role: 'assistant', content: 'Current translation is valid.' },
        suggestions: [],
        review: { score: 2, explanation: 'Current translation is valid.', confidenceLevel: 94 },
      });
      await Promise.resolve();
    });
    await screen.findByText('Current translation is valid.');
    expect(screen.queryByLabelText('Model confidence: 94 out of 100')).not.toBeInTheDocument();
    saveUserPreferencesMock.mockResolvedValue({ ...preferences, aiReviewShowScore: true });
    fireEvent.click(scoreToggle);
    expect(await screen.findByLabelText('Model confidence: 94 out of 100')).toHaveTextContent(
      /^94$/,
    );
    expect(saveUserPreferencesMock.mock.calls[1][0]).toEqual({ aiReviewShowScore: true });
    expect(screen.getByText('Current translation is valid.')).toBeVisible();
    expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
    expect(requestAiReviewMock.mock.calls[0][0]).not.toHaveProperty('showScore');
  });

  it('keeps the speed control beside the AI Chat Review title while the conversation is collapsed', async () => {
    renderTextUnitDetailPage();
    await screen.findByText('No issues found.');
    const titleButton = screen.getByRole('button', { name: 'AI Chat Review' });
    const speedButton = screen.getByRole('button', { name: 'Review speed: Balanced' });
    expect(titleButton.parentElement).toContainElement(speedButton);
    expect(titleButton).not.toContainElement(speedButton);
    expect(screen.queryByRole('combobox', { name: 'Model' })).not.toBeInTheDocument();
    fireEvent.click(titleButton);
    expect(
      screen.queryByPlaceholderText('Chat with AI: rephrase, adjust the tone, or ask a question…'),
    ).not.toBeInTheDocument();
    expect(speedButton).toBeVisible();
    fireEvent.click(speedButton);
    expect(screen.getByRole('slider', { name: 'Review speed' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'AI review settings' })).not.toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Automatic review' })).toBeChecked();
    expect(screen.queryByRole('combobox', { name: 'Model' })).not.toBeInTheDocument();
  });

  it('retries a failed preferences load from the speed popup before requesting review', async () => {
    fetchUserPreferencesMock.mockRejectedValueOnce(new Error('Settings unavailable'));
    renderTextUnitDetailPage('/text-units/3?locale=pt-PT', undefined, null);
    await waitFor(() => expect(fetchUserPreferencesMock).toHaveBeenCalledTimes(1));
    const speedButton = await screen.findByRole('button', { name: 'Review speed: Balanced' });
    await waitFor(() => expect(speedButton).toHaveAttribute('aria-disabled', 'false'));
    fireEvent.click(speedButton);
    const panel = screen.getByRole('dialog', { name: 'Review speed' });
    expect(await within(panel).findByRole('alert')).toHaveTextContent(
      'Could not load AI review settings.',
    );
    expect(within(panel).getByRole('slider', { name: 'Review speed' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    expect(requestAiReviewMock).not.toHaveBeenCalled();

    fireEvent.click(within(panel).getByRole('button', { name: 'Try again' }));
    await screen.findByText('No issues found.');
    expect(fetchUserPreferencesMock).toHaveBeenCalledTimes(2);
    expect(requestAiReviewMock.mock.calls[0][0]).toMatchObject({
      presetId: 'balanced',
      requestType: 'automatic',
    });
  });

  it('keeps the selected preset for Ask and retry after disabling automatic review', async () => {
    const { queryClient } = renderTextUnitDetailPage('/text-units/3?locale=pt-PT', undefined, {
      ...preferences,
      aiReviewPreset: 'deep',
    });
    await screen.findByText('No issues found.');
    saveUserPreferencesMock.mockResolvedValue({
      ...preferences,
      aiReviewPreset: 'deep',
      aiReviewAutomaticDisabled: true,
    });
    fireEvent.click(screen.getByRole('button', { name: 'Review speed: Balanced' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Automatic review' }));
    await waitFor(() =>
      expect(screen.getByRole('checkbox', { name: 'Automatic review' })).not.toBeChecked(),
    );
    expect(saveUserPreferencesMock.mock.calls[0][0]).toEqual({ aiReviewAutomaticDisabled: true });
    expect(queryClient.getQueryData(userPreferencesQueryKey('translator'))).toMatchObject({
      aiReviewPreset: 'deep',
      aiReviewAutomaticDisabled: true,
    });
    expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
    requestAiReviewMock.mockRejectedValueOnce(new Error('Temporary review failure'));
    fireEvent.change(
      screen.getByPlaceholderText('Chat with AI: rephrase, adjust the tone, or ask a question…'),
      { target: { value: 'Explain the terminology.' } },
    );
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(3));
    expect(requestAiReviewMock.mock.calls[1][0]).toMatchObject({
      presetId: 'balanced',
      requestType: 'follow_up',
    });
    expect(requestAiReviewMock.mock.calls[2][0]).toMatchObject({
      presetId: 'balanced',
      requestType: 'retry',
    });
  });

  it('enables automatic review without changing the selected preset', async () => {
    renderTextUnitDetailPage('/text-units/3?locale=pt-PT', undefined, {
      ...preferences,
      aiReviewPreset: 'thorough',
      aiReviewAutomaticDisabled: true,
    });
    const speedButton = await screen.findByRole('button', { name: 'Review speed: Balanced' });
    expect(requestAiReviewMock).not.toHaveBeenCalled();
    saveUserPreferencesMock.mockResolvedValue({ ...preferences, aiReviewPreset: 'thorough' });
    fireEvent.click(speedButton);
    fireEvent.click(screen.getByRole('checkbox', { name: 'Automatic review' }));
    await screen.findByText('No issues found.');
    expect(saveUserPreferencesMock.mock.calls[0][0]).toEqual({ aiReviewAutomaticDisabled: false });
    expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
    expect(requestAiReviewMock.mock.calls[0][0]).toMatchObject({
      presetId: 'balanced',
      requestType: 'automatic',
    });
  });

  it.each([
    ['version_a', 'high', 'fast', 'Fast'],
    ['version_b', 'medium', 'balanced', 'Balanced'],
    ['version_b', 'high', 'balanced', 'Balanced'],
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

      expect(screen.getByRole('button', { name: `Review speed: ${label}` })).toHaveAttribute(
        'aria-disabled',
        'false',
      );
      expect(requestAiReviewMock.mock.calls[0][0]).toMatchObject({
        presetId: preset,
        requestType: 'automatic',
      });
      expect(saveUserPreferencesMock).not.toHaveBeenCalled();
    },
  );

  it('keeps automatic review off and discards the old manual response when speed changes', async () => {
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
      expect(screen.getByRole('button', { name: 'Review speed: Fast' })).toHaveAttribute(
        'aria-disabled',
        'false',
      ),
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
    expect(screen.getByRole('checkbox', { name: 'Automatic review' })).not.toBeChecked();
    expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    await screen.findByText('No issues found.');
    expect(requestAiReviewMock).toHaveBeenCalledTimes(2);
    expect(requestAiReviewMock.mock.calls[1][0]).toMatchObject({
      presetId: 'fast',
      requestType: 'manual',
      surface: 'text_unit_detail',
    });
  });

  it('explains automatic Ultra fallback while keeping Ultra selected for manual Ask', async () => {
    currentUserRole.role = 'ROLE_ADMIN';
    const saved = { ...preferences, aiReviewPreset: 'ultra' as const };
    const { queryClient } = renderTextUnitDetailPage(
      '/text-units/3?locale=pt-PT',
      undefined,
      saved,
    );
    await screen.findByText('No issues found.');
    expect(screen.getByRole('button', { name: 'Review speed: Ultra' })).toHaveTextContent(
      'Auto: Balanced',
    );
    expect(requestAiReviewMock.mock.calls[0][0]).toMatchObject({
      presetId: 'ultra',
      requestType: 'automatic',
    });
    fireEvent.change(
      screen.getByPlaceholderText('Chat with AI: rephrase, adjust the tone, or ask a question…'),
      { target: { value: 'Explain the terminology.' } },
    );
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
    await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(2));
    expect(requestAiReviewMock.mock.calls[1][0]).toMatchObject({
      presetId: 'ultra',
      requestType: 'follow_up',
    });
    expect(queryClient.getQueryData(userPreferencesQueryKey('translator'))).toMatchObject({
      aiReviewPreset: 'ultra',
      aiReviewAutomaticDisabled: false,
    });
    expect(saveUserPreferencesMock).not.toHaveBeenCalled();
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
    fireEvent.change(slider, { target: { value: '1' } });
    fireEvent.keyUp(slider, { key: 'ArrowLeft' });
    await waitFor(() => expect(saveUserPreferencesMock).toHaveBeenCalledTimes(1));
    expect(saveUserPreferencesMock.mock.calls[0][0]).toEqual({ aiReviewPreset: 'fast' });
    expect(screen.getByRole('button', { name: 'Review speed: Balanced' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    expect(queryClient.getQueryData(userPreferencesQueryKey('translator'))).toMatchObject({
      aiReviewPreset: 'balanced',
    });
    expect(oldSignal.aborted).toBe(false);
    expect(requestAiReviewMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      finishSave({ ...preferences, aiReviewPreset: 'fast' });
      await Promise.resolve();
    });
    await screen.findByText('No issues found.');
    expect(screen.getByRole('button', { name: 'Review speed: Fast' })).toHaveAttribute(
      'aria-disabled',
      'false',
    );
    expect(queryClient.getQueryData(userPreferencesQueryKey('translator'))).toMatchObject({
      aiReviewPreset: 'fast',
    });
    expect(oldSignal.aborted).toBe(true);
    expect(requestAiReviewMock.mock.calls[1][0]).toMatchObject({
      presetId: 'fast',
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

  it('completes a source argument and sends its exact spelling through integrity validation', async () => {
    const restoreDom = installProseMirrorDomMock();
    const user = userEvent.setup();
    searchTextUnitsMock.mockResolvedValue([
      {
        tmTextUnitId: 3,
        tmTextUnitVariantId: 30,
        tmTextUnitCurrentVariantId: 30,
        localeId: 17,
        name: 'checkout.pay',
        source: 'Pay {price} now',
        target: '',
        targetLocale: 'pt-PT',
        used: true,
        status: 'APPROVED',
        includedInLocalizedFile: true,
      },
    ]);

    try {
      renderTextUnitDetailPage();
      const editor = await screen.findByRole('textbox', { name: 'Translation' });
      editor.focus();
      await user.keyboard('{{pr');

      await user.click(await screen.findByRole('option', { name: '{price} Source placeholder' }));
      await waitFor(() =>
        expect(editor.querySelector('.visible-text-editor__protected-token')).toHaveAttribute(
          'data-raw',
          '{price}',
        ),
      );
      expect(saveTextUnitMock).not.toHaveBeenCalled();

      await user.click(screen.getByRole('button', { name: 'Save' }));
      await waitFor(() =>
        expect(saveTextUnitMock).toHaveBeenCalledWith(
          expect.objectContaining({ tmTextUnitId: 3, target: '{price}' }),
        ),
      );
      expect(checkTextUnitIntegrityMock).toHaveBeenCalledWith(
        expect.objectContaining({ tmTextUnitId: 3, content: '{price}' }),
      );
    } finally {
      restoreDom();
    }
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
      localeId: 17,
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
