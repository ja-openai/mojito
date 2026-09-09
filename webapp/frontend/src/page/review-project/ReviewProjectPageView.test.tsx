import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { type ComponentProps } from 'react';
import { flushSync } from 'react-dom';
import type * as ReactRouterDom from 'react-router-dom';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type { AiReviewRequest, AiReviewResponse } from '../../api/ai-review';
import type * as GlossariesApi from '../../api/glossaries';
import type * as ReviewProjectsApi from '../../api/review-projects';
import type { ApiReviewProjectDetail, ApiReviewProjectTextUnit } from '../../api/review-projects';
import type * as TextUnitsApi from '../../api/text-units';
import type { ApiUserPreferences } from '../../api/userPreferences';
import type { ApiUserProfile } from '../../api/users';
import type * as Mf2TranslationEditorModule from '../../components/mf2/Mf2TranslationEditor';
import type {
  Mf2TranslationEditorHandle,
  Mf2TranslationEditorProps,
} from '../../components/mf2/Mf2TranslationEditor';
import { REPOSITORIES_QUERY_KEY } from '../../hooks/useRepositories';
import { REVIEW_PROJECT_DETAIL_QUERY_KEY } from '../../hooks/useReviewProjectDetail';
import { UserContext } from '../../hooks/useUser';
import { userPreferencesQueryKey } from '../../hooks/useUserPreferences';
import {
  type ReviewProjectMutationControls,
  useReviewProjectMutations,
} from './review-project-mutations';
import { ReviewProjectPageView } from './ReviewProjectPageView';

const matchGlossaryTermsMock = vi.hoisted(() => vi.fn());
const fetchPrecomputedAiReviewMock = vi.hoisted(() => vi.fn());
const requestAiReviewMock = vi.hoisted(() => vi.fn());
const saveReviewProjectTextUnitDecisionMock = vi.hoisted(() => vi.fn());
const visibleTextEditorEnabledMock = vi.hoisted(() => vi.fn(() => true));
const mf2TranslationEditorHostMock = vi.hoisted(() => ({ enabled: false, errorCount: 0 }));
const searchTextUnitsMock = vi.hoisted(() => vi.fn());
const fetchUserPreferencesMock = vi.hoisted(() => vi.fn());
const saveUserPreferencesMock = vi.hoisted(() => vi.fn());

vi.mock('../../api/userPreferences', () => ({
  fetchUserPreferences: fetchUserPreferencesMock,
  saveUserPreferences: saveUserPreferencesMock,
}));

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

vi.mock('../../api/text-units', async (importActual) => ({
  ...(await importActual<typeof TextUnitsApi>()),
  searchTextUnits: searchTextUnitsMock,
}));

vi.mock('../../api/ai-review', () => ({
  fetchPrecomputedAiReview: fetchPrecomputedAiReviewMock,
  formatAiReviewError: (error: unknown) => ({
    message: error instanceof Error ? error.message : 'Unable to run AI review.',
    detail: null,
  }),
  requestAiReview: requestAiReviewMock,
}));

vi.mock('../../api/glossaries', async (importActual) => {
  const actual = await importActual<typeof GlossariesApi>();
  return {
    ...actual,
    matchGlossaryTerms: matchGlossaryTermsMock,
  };
});

vi.mock('../../api/review-projects', async (importActual) => {
  const actual = await importActual<typeof ReviewProjectsApi>();
  return {
    ...actual,
    saveReviewProjectTextUnitDecision: saveReviewProjectTextUnitDecisionMock,
  };
});

vi.mock('../../components/mf2/Mf2TranslationEditor', async (importActual) => {
  const actual = await importActual<typeof Mf2TranslationEditorModule>();
  const { forwardRef, useEffect, useImperativeHandle, useRef } = await import('react');

  const HostGateMf2Editor = forwardRef<Mf2TranslationEditorHandle, Mf2TranslationEditorProps>(
    function HostGateMf2Editor(props, ref) {
      const {
        className,
        initialMode,
        initialTarget,
        locale,
        mode,
        onChange,
        onKeyDown,
        onSubmit,
        source,
        target: controlledTarget,
      } = props;
      const editorRef = useRef<HTMLTextAreaElement | null>(null);
      const target = controlledTarget ?? initialTarget ?? source;
      const errorCount = mf2TranslationEditorHostMock.errorCount;

      useImperativeHandle(
        ref,
        () => ({
          blur: () => editorRef.current?.blur(),
          focus: () => editorRef.current?.focus(),
        }),
        [],
      );

      useEffect(() => {
        onChange?.({
          diagnostics: Array.from({ length: errorCount }, (_, index) => ({
            code: `host-test-error-${index + 1}`,
            message: 'Injected MF2 contract error.',
            severity: 'error',
          })),
          locale: locale ?? 'en',
          mode: mode ?? initialMode ?? 'rich',
          target,
        });
      }, [errorCount, initialMode, locale, mode, onChange, target]);

      return (
        <section className={className} onKeyDown={onKeyDown}>
          <textarea
            aria-label="Target Message"
            className="mf2-pm-view"
            onKeyDown={(event) => {
              if (
                event.key === 'Enter' &&
                !event.shiftKey &&
                !event.altKey &&
                (event.metaKey || event.ctrlKey)
              ) {
                event.preventDefault();
                event.stopPropagation();
                onSubmit?.();
              }
            }}
            readOnly
            ref={editorRef}
            value={target}
          />
        </section>
      );
    },
  );

  const ActualMf2TranslationEditor = actual.Mf2TranslationEditor;
  const Mf2TranslationEditor = forwardRef<Mf2TranslationEditorHandle, Mf2TranslationEditorProps>(
    function Mf2TranslationEditor(props, ref) {
      if (mf2TranslationEditorHostMock.enabled) {
        return <HostGateMf2Editor {...props} ref={ref} />;
      }
      return <ActualMf2TranslationEditor {...props} ref={ref} />;
    },
  );

  return { ...actual, Mf2TranslationEditor };
});

vi.mock('../../components/virtual/useVirtualRows', () => ({
  useVirtualRows: () => ({
    scrollRef: { current: null },
    virtualizer: {},
    items: [{ index: 0, key: 'review-row-1', start: 0, end: 100, size: 100, lane: 0 }],
    totalSize: 100,
    scrollToIndex: vi.fn(),
    measureElement: vi.fn(),
  }),
}));

vi.mock('../../hooks/useVisibleTextEditorEnabled', () => ({
  useVisibleTextEditorEnabled: () => visibleTextEditorEnabledMock(),
}));

type ReviewProjectPageViewProps = ComponentProps<typeof ReviewProjectPageView>;

const noop = vi.fn();
const navigateMock = vi.hoisted(() => vi.fn());

vi.mock('react-router-dom', async (importActual) => {
  const actual = await importActual<typeof ReactRouterDom>();
  return {
    ...actual,
    useNavigate: () => navigateMock,
  };
});

beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', {
    configurable: true,
    value: vi.fn(),
  });
});

beforeEach(() => {
  window.localStorage.clear();
  fetchUserPreferencesMock.mockReset();
  fetchUserPreferencesMock.mockResolvedValue(preferences);
  saveUserPreferencesMock.mockReset();
  fetchPrecomputedAiReviewMock.mockReset();
  fetchPrecomputedAiReviewMock.mockResolvedValue(null);
  requestAiReviewMock.mockReset();
  requestAiReviewMock.mockResolvedValue({
    message: { role: 'assistant', content: 'No issues found.' },
    suggestions: [],
    review: null,
  });
  navigateMock.mockReset();
  matchGlossaryTermsMock.mockReset();
  matchGlossaryTermsMock.mockResolvedValue({ matchedTerms: [] });
  saveReviewProjectTextUnitDecisionMock.mockReset();
  visibleTextEditorEnabledMock.mockReset();
  visibleTextEditorEnabledMock.mockReturnValue(true);
  mf2TranslationEditorHostMock.enabled = false;
  mf2TranslationEditorHostMock.errorCount = 0;
  searchTextUnitsMock.mockReset();
  searchTextUnitsMock.mockResolvedValue([]);
});

const user: ApiUserProfile = {
  username: 'translator',
  role: 'ROLE_TRANSLATOR',
  canTranslateAllLocales: true,
  userLocales: [],
};

const textUnit: ApiReviewProjectTextUnit = {
  id: 101,
  tmTextUnit: {
    id: 3,
    name: 'checkout.pay',
    content: 'Pay {price} now',
    comment: 'Checkout payment copy',
    asset: null,
    wordCount: 3,
  },
  baselineTmTextUnitVariant: {
    id: 30,
    content: 'Pay {price} now',
    status: 'REVIEW_NEEDED',
    includedInLocalizedFile: true,
    comment: null,
  },
  currentTmTextUnitVariant: null,
  reviewProjectTextUnitDecision: {
    decisionState: 'PENDING',
    notes: null,
    decisionTmTextUnitVariant: null,
  },
  terminologyFeedbacks: [],
};

const mf2Source = `.input {$count :number}
{{You have {$count} files.}}`;

function buildMf2TextUnit(target: string): ApiReviewProjectTextUnit {
  return {
    ...textUnit,
    id: 103,
    tmTextUnit: {
      ...textUnit.tmTextUnit!,
      id: 5,
      name: 'files.count',
      content: mf2Source,
    },
    baselineTmTextUnitVariant: {
      ...textUnit.baselineTmTextUnitVariant!,
      id: 32,
      content: target,
    },
  };
}

const project: ApiReviewProjectDetail = {
  id: 7,
  type: 'NORMAL',
  status: 'OPEN',
  textUnitCount: 1,
  wordCount: 3,
  locale: { id: 17, bcp47Tag: 'pt-PT' },
  reviewProjectRequest: {
    id: 70,
    name: 'Checkout review',
    notes: null,
    screenshotImageIds: [],
  },
  assignment: null,
  reviewProjectTextUnits: [textUnit],
};

function buildNextTextUnit(): ApiReviewProjectTextUnit {
  return {
    ...textUnit,
    id: 102,
    tmTextUnit: {
      ...textUnit.tmTextUnit!,
      id: 4,
      name: 'checkout.cancel',
      content: 'Cancel payment',
      comment: 'Checkout cancel copy',
    },
    baselineTmTextUnitVariant: {
      id: 31,
      content: 'Cancel payment',
      status: 'REVIEW_NEEDED',
      includedInLocalizedFile: true,
      comment: null,
    },
  };
}

function buildMutations(
  overrides: Partial<ReviewProjectMutationControls> = {},
): ReviewProjectMutationControls {
  return {
    actionState: { phase: 'idle' },
    isSaving: false,
    isProjectStatusSaving: false,
    isProjectRequestSaving: false,
    isProjectDueDateSaving: false,
    isProjectAssignmentSaving: false,
    errorMessage: null,
    activeTextUnitId: null,
    conflictTextUnit: null,
    showValidationDialog: false,
    validationDialogTitle: '',
    validationDialogBody: '',
    validationDialogFailureDetail: null,
    validationDialogReportMessage: null,
    validationDialogReportHtml: null,
    validationDialogRequiresConfirmation: false,
    validationDialogCanRetry: false,
    onConfirmValidationSave: noop,
    onRetryValidationSave: noop,
    onDismissValidationSave: noop,
    onDiscardAction: vi.fn(),
    onUseConflictCurrent: noop,
    onOverwriteConflict: noop,
    onRequestSaveDecision: noop,
    onRequestDecisionState: noop,
    onRequestTerminologyFeedback: noop,
    onRequestTerminologyMetadata: noop,
    onRequestTerminologyResolution: noop,
    onRequestProjectStatus: noop,
    onRequestProjectRequestUpdate: vi.fn().mockResolvedValue(undefined),
    onRequestProjectDueDateUpdate: vi.fn().mockResolvedValue(undefined),
    onRequestProjectAssignmentUpdate: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

function renderReviewProjectPageView(
  overrides: Partial<ReviewProjectPageViewProps> = {},
  currentUser: ApiUserProfile = user,
  initialEntry?: string,
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });

  const props: ReviewProjectPageViewProps = {
    projectId: project.id,
    project,
    mutations: buildMutations(),
    selectedTextUnitQueryId: null,
    onSelectedTextUnitIdChange: noop,
    openRequestDetailsQuery: false,
    requestDetailsSource: null,
    onRequestDetailsQueryHandled: noop,
    onRequestDetailsFlowFinished: noop,
    ...overrides,
  };

  return render(
    <QueryClientProvider client={queryClient}>
      <UserContext.Provider value={currentUser}>
        <MemoryRouter initialEntries={initialEntry ? [initialEntry] : undefined}>
          <ReviewProjectPageView {...props} />
        </MemoryRouter>
      </UserContext.Provider>
    </QueryClientProvider>,
  );
}

function renderReviewProjectPageViewNode(
  props: ReviewProjectPageViewProps,
  queryClient: QueryClient,
  currentUser: ApiUserProfile = user,
) {
  return (
    <QueryClientProvider client={queryClient}>
      <UserContext.Provider value={currentUser}>
        <MemoryRouter>
          <ReviewProjectPageView {...props} />
        </MemoryRouter>
      </UserContext.Provider>
    </QueryClientProvider>
  );
}

describe('ReviewProjectPageView', () => {
  it('keeps the saved shortcut preference on a failed save and allows retrying', async () => {
    fetchUserPreferencesMock.mockResolvedValue({ ...preferences, shortcutHelp: 'header' });
    saveUserPreferencesMock.mockRejectedValueOnce(new Error('Network unavailable'));
    renderReviewProjectPageView();

    fireEvent.click(screen.getByRole('button', { name: 'Keyboard shortcuts' }));
    const shortcutBar = screen.getByRole('checkbox', { name: 'Show shortcut bar at the bottom' });
    await waitFor(() => expect(shortcutBar).not.toBeChecked());
    fireEvent.click(shortcutBar);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not save shortcut preference. Please try again.',
    );
    expect(shortcutBar).not.toBeChecked();
    expect(shortcutBar).toBeEnabled();
    expect(saveUserPreferencesMock).toHaveBeenCalledWith(
      { shortcutHelp: 'bottom' },
      expect.anything(),
    );

    saveUserPreferencesMock.mockResolvedValueOnce({ ...preferences, shortcutHelp: 'bottom' });
    fireEvent.click(shortcutBar);

    await waitFor(() => expect(shortcutBar).toBeChecked());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(saveUserPreferencesMock).toHaveBeenCalledTimes(2);
  });

  it('links open translation projects to find and replace', () => {
    renderReviewProjectPageView();

    expect(screen.getByRole('link', { name: 'Find and replace' })).toHaveAttribute(
      'href',
      '/review-projects/7/find-replace',
    );
  });

  it('opens find and replace with the command palette shortcut', () => {
    renderReviewProjectPageView();

    fireEvent.keyDown(window, { key: 'f', ctrlKey: true, shiftKey: true });

    expect(navigateMock).toHaveBeenCalledWith('/review-projects/7/find-replace');
  });

  it('preserves the review-projects session when opening find and replace', () => {
    renderReviewProjectPageView({}, user, '/review-projects/7?rps=review-session');

    expect(screen.getByRole('link', { name: 'Find and replace' })).toHaveAttribute(
      'href',
      '/review-projects/7/find-replace?rps=review-session',
    );

    fireEvent.keyDown(window, { key: 'f', ctrlKey: true, shiftKey: true });

    expect(navigateMock).toHaveBeenCalledWith('/review-projects/7/find-replace?rps=review-session');
  });

  it('does not intercept normal browser find', () => {
    renderReviewProjectPageView();

    fireEvent.keyDown(window, { key: 'f', ctrlKey: true });

    expect(navigateMock).not.toHaveBeenCalled();
  });

  it('ignores repeated accept-and-advance shortcut events', async () => {
    const onRequestSaveDecision = vi.fn();
    renderReviewProjectPageView({
      mutations: buildMutations({ onRequestSaveDecision }),
    });

    await screen.findByRole('textbox', { name: 'Translation' });
    fireEvent.keyDown(window, {
      key: 'Enter',
      ctrlKey: true,
      shiftKey: true,
      repeat: true,
    });

    expect(onRequestSaveDecision).not.toHaveBeenCalled();
  });

  it('still accepts a deliberate accept-and-advance shortcut', async () => {
    const onRequestSaveDecision = vi.fn();
    renderReviewProjectPageView({
      mutations: buildMutations({ onRequestSaveDecision }),
    });

    await screen.findByRole('textbox', { name: 'Translation' });
    fireEvent.keyDown(window, {
      key: 'Enter',
      ctrlKey: true,
      shiftKey: true,
      repeat: false,
    });

    expect(onRequestSaveDecision).toHaveBeenCalledWith(
      expect.objectContaining({
        textUnitId: textUnit.id,
        target: textUnit.baselineTmTextUnitVariant?.content,
        decisionState: 'DECIDED',
      }),
    );
  });

  it('accepts from the focused translation editor shortcut', async () => {
    const onRequestSaveDecision = vi.fn();
    renderReviewProjectPageView({
      mutations: buildMutations({ onRequestSaveDecision }),
    });

    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    fireEvent.keyDown(editor, { key: 'Enter', ctrlKey: true });

    expect(onRequestSaveDecision).toHaveBeenCalledWith(
      expect.objectContaining({
        decisionState: 'DECIDED',
        status: 'APPROVED',
        textUnitId: textUnit.id,
      }),
    );
  });

  it('blurs the native translation textarea on Escape when Visible Editor is off', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    renderReviewProjectPageView();

    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    expect(editor).toBeInstanceOf(HTMLTextAreaElement);
    expect(screen.getByRole('button', { name: 'Characters' })).toBeVisible();
    editor.focus();
    expect(editor).toHaveFocus();

    fireEvent.keyDown(editor, { key: 'Escape' });

    expect(editor).not.toHaveFocus();
  });

  it('moves focus from the native translation textarea on Tab when Visible Editor is off', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    renderReviewProjectPageView();

    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    const comment = screen.getByPlaceholderText(
      'Explain why you chose this translation (if not obvious).',
    );
    editor.focus();

    const wasNotCancelled = fireEvent.keyDown(editor, { key: 'Tab' });

    expect(wasNotCancelled).toBe(false);
    expect(comment).toHaveFocus();
  });

  it('handles Cmd+Enter from the native translation textarea when Visible Editor is off', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const onRequestSaveDecision = vi.fn();
    renderReviewProjectPageView({
      mutations: buildMutations({ onRequestSaveDecision }),
    });

    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    const wasNotCancelled = fireEvent.keyDown(editor, { key: 'Enter', metaKey: true });

    expect(wasNotCancelled).toBe(false);
    expect(onRequestSaveDecision).toHaveBeenCalledWith(
      expect.objectContaining({
        decisionState: 'DECIDED',
        status: 'APPROVED',
        textUnitId: textUnit.id,
      }),
    );
  });

  it.each(['ROLE_USER', 'ROLE_TRANSLATOR', 'ROLE_PM', 'ROLE_ADMIN'] as const)(
    'hides Search by default for %s',
    (role) => {
      renderReviewProjectPageView({}, { ...user, role });

      expect(screen.queryByRole('tab', { name: 'Search' })).not.toBeInTheDocument();
      expect(screen.queryByRole('region', { name: 'Translation search' })).not.toBeInTheDocument();
      expect(searchTextUnitsMock).not.toHaveBeenCalled();
    },
  );

  it('lets opted-in translators use Search without changing or saving the translation draft', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const onRequestSaveDecision = vi.fn();
    const onRequestDecisionState = vi.fn();
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    queryClient.setQueryData(userPreferencesQueryKey(user.username), {
      ...preferences,
      reviewProjectSearchEnabled: true,
    });
    queryClient.setQueryData(REPOSITORIES_QUERY_KEY, [
      { id: 1, name: 'example-mobile' },
      { id: 2, name: 'example-web' },
    ]);
    render(
      renderReviewProjectPageViewNode(
        {
          projectId: project.id,
          project,
          mutations: buildMutations({ onRequestSaveDecision, onRequestDecisionState }),
          selectedTextUnitQueryId: null,
          onSelectedTextUnitIdChange: noop,
          openRequestDetailsQuery: false,
          requestDetailsSource: null,
          onRequestDetailsQueryHandled: noop,
          onRequestDetailsFlowFinished: noop,
        },
        queryClient,
        user,
      ),
    );
    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    fireEvent.change(editor, { target: { value: 'Pague {price} agora' } });

    fireEvent.click(screen.getByRole('tab', { name: 'Search' }));
    const searchInput = await screen.findByRole('searchbox', { name: 'Search translation' });
    fireEvent.change(searchInput, { target: { value: 'pagamento' } });
    fireEvent.submit(searchInput.closest('form')!);

    await waitFor(() =>
      expect(searchTextUnitsMock).toHaveBeenCalledWith(
        expect.objectContaining({
          textSearch: {
            operator: 'AND',
            predicates: [{ field: 'target', searchType: 'contains', value: 'pagamento' }],
          },
          localeTags: ['pt-PT'],
          repositoryIds: [1, 2],
        }),
      ),
    );
    searchInput.focus();
    fireEvent.keyDown(searchInput, { key: 'Enter', ctrlKey: true });
    fireEvent.keyDown(searchInput, { key: 'Enter', metaKey: true });

    fireEvent.click(screen.getByRole('tab', { name: /^Glossary/ }));
    expect(screen.queryByRole('searchbox', { name: 'Search translation' })).not.toBeInTheDocument();
    expect(editor).toHaveValue('Pague {price} agora');
    fireEvent.click(screen.getByRole('tab', { name: 'Search' }));

    expect(screen.getByRole('searchbox', { name: 'Search translation' })).toBe(searchInput);
    expect(searchInput).toHaveValue('pagamento');
    expect(screen.getByRole('textbox', { name: 'Translation' })).toBe(editor);
    expect(editor).toHaveValue('Pague {price} agora');
    expect(searchTextUnitsMock).toHaveBeenCalledTimes(1);
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
    expect(onRequestDecisionState).not.toHaveBeenCalled();

    act(() => {
      queryClient.setQueryData(userPreferencesQueryKey(user.username), preferences);
    });
    await waitFor(() =>
      expect(screen.queryByRole('tab', { name: 'Search' })).not.toBeInTheDocument(),
    );
    expect(screen.queryByRole('region', { name: 'Translation search' })).not.toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /^Glossary/ })).toHaveAttribute('aria-selected', 'true');
    expect(editor).toHaveValue('Pague {price} agora');

    act(() => {
      queryClient.setQueryData(userPreferencesQueryKey('another-user'), {
        ...preferences,
        reviewProjectSearchEnabled: true,
      });
    });
    expect(screen.queryByRole('tab', { name: 'Search' })).not.toBeInTheDocument();
    act(() => {
      queryClient.setQueryData(userPreferencesQueryKey(user.username), {
        ...preferences,
        reviewProjectSearchEnabled: true,
      });
    });
    fireEvent.click(await screen.findByRole('tab', { name: 'Search' }));
    expect(screen.getByRole('searchbox', { name: 'Search translation' })).toHaveValue('');

    // A server refresh updates an already-open review without losing its draft.
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: userPreferencesQueryKey(user.username) });
    });
    await waitFor(() =>
      expect(screen.queryByRole('tab', { name: 'Search' })).not.toBeInTheDocument(),
    );
    expect(screen.getByRole('tab', { name: /^Glossary/ })).toHaveAttribute('aria-selected', 'true');
    expect(editor).toHaveValue('Pague {price} agora');
    expect(searchTextUnitsMock).toHaveBeenCalledTimes(1);
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
  });

  it('mounts a fresh translation editor when the selected text unit changes', async () => {
    const nextTextUnit = buildNextTextUnit();
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });
    const baseProps: ReviewProjectPageViewProps = {
      projectId: project.id,
      project: {
        ...project,
        reviewProjectTextUnits: [textUnit, nextTextUnit],
      },
      mutations: buildMutations(),
      selectedTextUnitQueryId: textUnit.tmTextUnit!.id,
      onSelectedTextUnitIdChange: noop,
      openRequestDetailsQuery: false,
      requestDetailsSource: null,
      onRequestDetailsQueryHandled: noop,
      onRequestDetailsFlowFinished: noop,
    };

    const { rerender } = render(renderReviewProjectPageViewNode(baseProps, queryClient));
    const originalEditor = await screen.findByRole('textbox', { name: 'Translation' });

    rerender(
      renderReviewProjectPageViewNode(
        {
          ...baseProps,
          selectedTextUnitQueryId: nextTextUnit.tmTextUnit!.id,
        },
        queryClient,
      ),
    );

    const nextEditor = await screen.findByRole('textbox', { name: 'Translation' });
    expect(nextEditor).not.toBe(originalEditor);
    expect(nextEditor).toHaveTextContent('Cancel payment');
  });

  it('saves the newly selected text unit with its own translation snapshot', async () => {
    const onRequestSaveDecision = vi.fn();
    const nextTextUnit: ApiReviewProjectTextUnit = {
      ...buildNextTextUnit(),
      currentTmTextUnitVariant: {
        id: 32,
        content: 'Cancelar pagamento',
        status: 'REVIEW_NEEDED',
        includedInLocalizedFile: true,
        comment: 'Current target comment',
      },
      reviewProjectTextUnitDecision: {
        decisionState: 'PENDING',
        notes: 'Next row decision notes',
        decisionTmTextUnitVariant: null,
      },
    };
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });
    const baseProps: ReviewProjectPageViewProps = {
      projectId: project.id,
      project: {
        ...project,
        reviewProjectTextUnits: [textUnit, nextTextUnit],
      },
      mutations: buildMutations({ onRequestSaveDecision }),
      selectedTextUnitQueryId: textUnit.tmTextUnit!.id,
      onSelectedTextUnitIdChange: noop,
      openRequestDetailsQuery: false,
      requestDetailsSource: null,
      onRequestDetailsQueryHandled: noop,
      onRequestDetailsFlowFinished: noop,
    };

    render(renderReviewProjectPageViewNode(baseProps, queryClient));
    await screen.findByRole('textbox', { name: 'Translation' });

    act(() => {
      flushSync(() => {
        fireEvent.keyDown(window, { key: 'ArrowDown' });
      });
      fireEvent.keyDown(window, { key: 'Enter', ctrlKey: true });
    });

    expect(onRequestSaveDecision).toHaveBeenCalledWith(
      expect.objectContaining({
        textUnitId: nextTextUnit.id,
        tmTextUnitId: nextTextUnit.tmTextUnit!.id,
        reportUrl: `${window.location.origin}/text-units/4?locale=pt-PT`,
        reviewProjectTextUnitUrl: `${window.location.origin}/review-projects/7?tu=4`,
        target: nextTextUnit.currentTmTextUnitVariant!.content,
        comment: nextTextUnit.currentTmTextUnitVariant!.comment,
        status: 'APPROVED',
        includedInLocalizedFile: true,
        decisionNotes: nextTextUnit.reviewProjectTextUnitDecision!.notes,
        expectedCurrentTmTextUnitVariantId: nextTextUnit.currentTmTextUnitVariant!.id,
      }),
    );
  });

  it('keeps arrow-key row navigation available after accepting and advancing', async () => {
    const nextTextUnit = buildNextTextUnit();
    const liveProject = { ...project, reviewProjectTextUnits: [textUnit, nextTextUnit] };
    const acceptedVariant = { ...textUnit.baselineTmTextUnitVariant, id: 31, status: 'APPROVED' };
    saveReviewProjectTextUnitDecisionMock.mockResolvedValue({
      ...textUnit,
      currentTmTextUnitVariant: acceptedVariant,
      reviewProjectTextUnitDecision: {
        decisionState: 'DECIDED',
        decisionTmTextUnitVariant: acceptedVariant,
      },
    });
    const queryClient = new QueryClient();
    queryClient.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], liveProject);
    function LiveReviewProjectPageView() {
      const mutations = useReviewProjectMutations(project.id);
      return (
        <ReviewProjectPageView
          projectId={project.id}
          project={liveProject}
          mutations={mutations}
          selectedTextUnitQueryId={null}
          onSelectedTextUnitIdChange={noop}
          openRequestDetailsQuery={false}
          requestDetailsSource={null}
          onRequestDetailsQueryHandled={noop}
          onRequestDetailsFlowFinished={noop}
        />
      );
    }
    render(
      <QueryClientProvider client={queryClient}>
        <UserContext.Provider value={user}>
          <MemoryRouter>
            <LiveReviewProjectPageView />
          </MemoryRouter>
        </UserContext.Provider>
      </QueryClientProvider>,
    );

    await act(() => {
      fireEvent.keyDown(window, {
        key: 'Enter',
        ctrlKey: true,
        shiftKey: true,
      });
      return Promise.resolve();
    });

    await waitFor(() => {
      expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveTextContent(
        'Cancel payment',
      );
    });

    const advancedEditor = screen.getByRole('textbox', { name: 'Translation' });
    expect(advancedEditor).toHaveFocus();
    fireEvent.keyDown(advancedEditor, { key: 'Escape' });
    expect(advancedEditor).not.toHaveFocus();

    fireEvent.keyDown(window, { key: 'ArrowUp' });

    await waitFor(() => {
      expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveTextContent(
        'Pay price now',
      );
    });
    expect(screen.getByRole('textbox', { name: 'Translation' })).not.toHaveFocus();

    fireEvent.keyDown(document.activeElement ?? window, { key: 'ArrowDown' });

    await waitFor(() => {
      expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveTextContent(
        'Cancel payment',
      );
    });
  });

  it('shows the accepted indicator immediately after an accept-and-advance save', async () => {
    const nextTextUnit = buildNextTextUnit();
    const liveProject = {
      ...project,
      reviewProjectTextUnits: [textUnit, nextTextUnit],
    };
    const acceptedVariant = { ...textUnit.baselineTmTextUnitVariant, id: 31, status: 'APPROVED' };
    const decidedTextUnit: ApiReviewProjectTextUnit = {
      ...textUnit,
      currentTmTextUnitVariant: acceptedVariant,
      reviewProjectTextUnitDecision: {
        decisionState: 'DECIDED',
        notes: null,
        decisionTmTextUnitVariant: acceptedVariant,
      },
    };
    saveReviewProjectTextUnitDecisionMock.mockResolvedValue(decidedTextUnit);
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });
    queryClient.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], liveProject);

    function LiveReviewProjectPageView() {
      const { data: currentProject } = useQuery({
        queryKey: [...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id],
        queryFn: () => Promise.resolve(liveProject),
        staleTime: Infinity,
      });
      const mutations = useReviewProjectMutations(project.id);

      return (
        <ReviewProjectPageView
          projectId={project.id}
          project={currentProject ?? null}
          mutations={mutations}
          selectedTextUnitQueryId={null}
          onSelectedTextUnitIdChange={noop}
          openRequestDetailsQuery={false}
          requestDetailsSource={null}
          onRequestDetailsQueryHandled={noop}
          onRequestDetailsFlowFinished={noop}
        />
      );
    }

    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <UserContext.Provider value={user}>
          <MemoryRouter>
            <LiveReviewProjectPageView />
          </MemoryRouter>
        </UserContext.Provider>
      </QueryClientProvider>,
    );

    expect(container.querySelector('.review-project-row__decided-dot')).toBeNull();

    await act(() => {
      fireEvent.keyDown(window, {
        key: 'Enter',
        ctrlKey: true,
        shiftKey: true,
      });
      return Promise.resolve();
    });

    await waitFor(() => {
      expect(container.querySelector('.review-project-row__decided-dot')).not.toBeNull();
      expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveTextContent(
        'Cancel payment',
      );
    });
  });

  it('delays the visible saving indicator for text-unit saves', async () => {
    const { container } = renderReviewProjectPageView({
      selectedTextUnitQueryId: textUnit.tmTextUnit!.id,
      mutations: buildMutations({
        actionState: {
          phase: 'pending',
          operationId: 1,
          attemptId: 1,
          originalAction: {
            kind: 'decision-state',
            request: { textUnitId: textUnit.id, decisionState: 'DECIDED' },
          },
          action: {
            kind: 'decision-state',
            request: { textUnitId: textUnit.id, decisionState: 'DECIDED' },
          },
        },
        isSaving: true,
        activeTextUnitId: textUnit.id,
      }),
    });

    expect(
      container.querySelector('.review-project-detail__saving-indicator.is-active'),
    ).toBeNull();

    await waitFor(
      () => {
        expect(
          container.querySelector('.review-project-detail__saving-indicator.is-active'),
        ).not.toBeNull();
      },
      { timeout: 1000 },
    );
  });

  it('uses the assisted protected editor in the selected text unit detail pane', async () => {
    const { container } = renderReviewProjectPageView();

    expect(await screen.findByRole('textbox', { name: 'Translation' })).toHaveClass('ProseMirror');
    expect(screen.getByRole('button', { name: 'Characters' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Hidden characters: Auto' })).toBeInTheDocument();
    expect(
      screen.getByRole('button', {
        name: 'Placeholder editing is off. Edit placeholders',
      }),
    ).toBeInTheDocument();
    expect(screen.queryByText('1 token found')).not.toBeInTheDocument();

    await waitFor(() => {
      const protectedToken = container.querySelector('.visible-text-editor__protected-token');
      expect(protectedToken).toHaveTextContent('price');
      expect(protectedToken).toHaveClass('visible-text-editor__protected-token--icu-placeholder');
    });
  });

  it('routes source-declared MF2 to the structured editor', async () => {
    const mf2TextUnit = buildMf2TextUnit(`.input {$count :number}
.match $count
one {{Você tem {$count} arquivo.}}
* {{Você tem {$count} arquivos.}}`);

    const { container } = renderReviewProjectPageView({
      project: {
        ...project,
        reviewProjectTextUnits: [mf2TextUnit],
      },
    });

    expect(await screen.findByRole('textbox', { name: 'Target count: one' })).toHaveClass(
      'mf2-pm-view',
    );
    expect(screen.queryByText('Variables')).not.toBeInTheDocument();
    expect(container.querySelector('.review-project-detail__value--source')).toHaveTextContent(
      '.input {$count :number}',
    );
    expect(container.querySelector('.review-project-detail__value--source')).toHaveTextContent(
      'You have {$count} files.',
    );
    expect(screen.getByRole('button', { name: 'Hidden characters: Auto' })).toBeInTheDocument();
    expect(
      screen.getByRole('button', {
        name: 'Placeholder editing is off. Edit placeholders',
      }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Translation' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: /Placeholders/ })).not.toBeInTheDocument();
    expect(screen.queryByText('Target language')).not.toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled();
    });
  });

  it('accepts and advances from a focused valid MF2 editor', async () => {
    const onRequestSaveDecision = vi.fn();
    const mf2TextUnit = buildMf2TextUnit(`.input {$count :number}
.match $count
one {{Você tem {$count} arquivo.}}
* {{Você tem {$count} arquivos.}}`);

    renderReviewProjectPageView({
      project: {
        ...project,
        reviewProjectTextUnits: [mf2TextUnit],
      },
      mutations: buildMutations({ onRequestSaveDecision }),
    });

    const editor = await screen.findByRole('textbox', { name: 'Target count: one' });
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled();
    });
    fireEvent.keyDown(editor, { key: 'Enter', ctrlKey: true, shiftKey: true });

    expect(onRequestSaveDecision).toHaveBeenCalledWith(
      expect.objectContaining({
        decisionState: 'DECIDED',
        status: 'APPROVED',
        textUnitId: mf2TextUnit.id,
      }),
    );
  });

  it('blocks MF2 acceptance while the target has contract errors', async () => {
    mf2TranslationEditorHostMock.enabled = true;
    mf2TranslationEditorHostMock.errorCount = 1;
    const onRequestSaveDecision = vi.fn();
    const mf2TextUnit = buildMf2TextUnit(`.input {$count :number}
{{Você tem {$rogue} arquivos.}}`);

    renderReviewProjectPageView({
      project: {
        ...project,
        reviewProjectTextUnits: [mf2TextUnit],
      },
      mutations: buildMutations({ onRequestSaveDecision }),
    });

    const editor = await screen.findByRole('textbox', { name: 'Target Message' });
    expect(await screen.findByRole('alert')).toHaveTextContent(
      /Fix \d+ MF2 errors? before accepting/,
    );
    expect(screen.getByRole('button', { name: 'Accept' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Decided' })).toBeDisabled();

    fireEvent.keyDown(editor, { key: 'Enter', ctrlKey: true });
    fireEvent.keyDown(window, { key: 'Enter', ctrlKey: true, shiftKey: true });

    fireEvent.click(screen.getByRole('tab', { name: 'Context' }));
    fireEvent.click(screen.getByRole('button', { name: 'Translation status' }));
    fireEvent.click(screen.getByRole('button', { name: 'Accepted' }));

    expect(onRequestSaveDecision).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Translation status' })).toHaveTextContent(
      'To review',
    );
  });

  it('allows an invalid rejected MF2 translation to be marked decided', async () => {
    mf2TranslationEditorHostMock.enabled = true;
    mf2TranslationEditorHostMock.errorCount = 1;
    const onRequestDecisionState = vi.fn();
    const onRequestSaveDecision = vi.fn();
    const mf2TextUnit = buildMf2TextUnit(`.input {$count :number}
{{Você tem {$rogue} arquivos.}}`);
    const rejectedMf2TextUnit = {
      ...mf2TextUnit,
      baselineTmTextUnitVariant: {
        ...mf2TextUnit.baselineTmTextUnitVariant!,
        includedInLocalizedFile: false,
      },
    };

    renderReviewProjectPageView({
      project: {
        ...project,
        reviewProjectTextUnits: [rejectedMf2TextUnit],
      },
      mutations: buildMutations({ onRequestDecisionState, onRequestSaveDecision }),
    });

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /Fix \d+ MF2 errors? before accepting/,
    );
    expect(screen.getByRole('button', { name: 'Accept' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Decided' })).toBeEnabled();

    fireEvent.click(screen.getByRole('button', { name: 'Decided' }));

    expect(onRequestDecisionState).toHaveBeenCalledWith({
      decisionState: 'DECIDED',
      expectedCurrentTmTextUnitVariantId: null,
      expectedReviewStateRevision: null,
      textUnitId: rejectedMf2TextUnit.id,
    });
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
  });

  it('overlays staged find-replace text', async () => {
    const stagedTextUnit: ApiReviewProjectTextUnit = {
      ...textUnit,
      reviewProjectTextUnitSuggestion: {
        id: 902,
        target: 'Pague {price} agora',
        source: 'FIND_REPLACE',
        previousTarget: 'Pay {price} now',
      },
    };
    const { container } = renderReviewProjectPageView({
      project: {
        ...project,
        reviewProjectTextUnits: [stagedTextUnit],
      },
    });

    expect(await screen.findByText('From find/replace')).toBeInTheDocument();
    expect(container.textContent).toContain('Pague');
  });

  it('waits for glossary matches before starting the automatic AI review', async () => {
    let resolveGlossaryMatches!: (value: { matchedTerms: [] }) => void;
    matchGlossaryTermsMock.mockReturnValue(
      new Promise((resolve) => {
        resolveGlossaryMatches = resolve;
      }),
    );
    const repositoryTextUnit: ApiReviewProjectTextUnit = {
      ...textUnit,
      tmTextUnit: {
        ...textUnit.tmTextUnit!,
        asset: {
          assetPath: 'checkout.json',
          repository: { id: 77, name: 'chatgpt-web' },
        },
      },
    };

    renderReviewProjectPageView({
      project: {
        ...project,
        reviewProjectTextUnits: [repositoryTextUnit],
      },
    });

    await waitFor(() => {
      expect(matchGlossaryTermsMock).toHaveBeenCalledTimes(1);
    });
    expect(requestAiReviewMock).not.toHaveBeenCalled();
    expect(screen.queryByText('Thinking…')).not.toBeInTheDocument();

    resolveGlossaryMatches({ matchedTerms: [] });

    await waitFor(() => {
      expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
    });
  });

  it('still starts the automatic AI review when glossary matching fails', async () => {
    matchGlossaryTermsMock.mockRejectedValue(new Error('Glossary unavailable'));
    const repositoryTextUnit: ApiReviewProjectTextUnit = {
      ...textUnit,
      tmTextUnit: {
        ...textUnit.tmTextUnit!,
        asset: {
          assetPath: 'checkout.json',
          repository: { id: 77, name: 'chatgpt-web' },
        },
      },
    };

    renderReviewProjectPageView({
      project: {
        ...project,
        reviewProjectTextUnits: [repositoryTextUnit],
      },
    });

    await waitFor(() => {
      expect(matchGlossaryTermsMock).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
    });
  });

  it('bypasses untagged precomputed reviews and sends only the selected preset', async () => {
    fetchPrecomputedAiReviewMock.mockResolvedValue({
      message: { role: 'assistant', content: 'Cached review from an unknown version.' },
      suggestions: [],
    });
    renderReviewProjectPageView();
    await screen.findByText('No issues found.');
    expect(fetchPrecomputedAiReviewMock).not.toHaveBeenCalled();
    expect(requestAiReviewMock).toHaveBeenCalledWith(
      expect.objectContaining({
        presetId: 'balanced',
        requestType: 'automatic',
        surface: 'review_project',
      }),
      expect.any(Object),
    );
    expect(requestAiReviewMock.mock.calls[0][0]).not.toHaveProperty('profileId');
    expect(requestAiReviewMock.mock.calls[0][0]).not.toHaveProperty('reasoningEffort');
    expect(requestAiReviewMock.mock.calls[0][0]).not.toHaveProperty('modelName');
    expect(screen.queryByText('Cached review from an unknown version.')).not.toBeInTheDocument();
  });

  it('waits for account settings and keeps manual review and Ask available when automatic review is off', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    let finishPreferences!: (value: ApiUserPreferences) => void;
    fetchUserPreferencesMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          finishPreferences = resolve;
        }),
    );
    renderReviewProjectPageView();
    await waitFor(() => expect(fetchUserPreferencesMock).toHaveBeenCalled());
    expect(requestAiReviewMock).not.toHaveBeenCalled();
    await act(async () => {
      finishPreferences({
        ...preferences,
        aiReviewPreset: 'thorough',
        aiReviewAutomaticDisabled: true,
      });
      await Promise.resolve();
    });
    const review = await screen.findByRole('button', { name: 'Review' });
    expect(review).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Review speed: Thorough' })).toHaveAttribute(
      'aria-disabled',
      'false',
    );
    expect(requestAiReviewMock).not.toHaveBeenCalled();
    fireEvent.change(screen.getByRole('textbox', { name: 'Translation' }), {
      target: { value: 'Pagar {price} hoje' },
    });
    fireEvent.click(review);
    await screen.findByText('No issues found.');
    expect(requestAiReviewMock.mock.calls[0][0]).toMatchObject({
      presetId: 'thorough',
      requestType: 'manual',
      surface: 'review_project',
      target: 'Pagar {price} hoje',
    });
    expect(saveUserPreferencesMock).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Review' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ask' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Review speed: Thorough' }));
    expect(screen.getByRole('checkbox', { name: 'Automatic review' })).not.toBeChecked();
    fireEvent.change(
      screen.getByPlaceholderText('Chat with AI: rephrase, adjust the tone, or ask a question…'),
      {
        target: { value: 'Explain the terminology.' },
      },
    );
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
    await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(2));
    expect(requestAiReviewMock.mock.calls[1][0]).toMatchObject({
      presetId: 'thorough',
      requestType: 'follow_up',
      surface: 'review_project',
    });
  });

  it('keeps the speed control beside the AI Chat Review title while the conversation is collapsed', async () => {
    renderReviewProjectPageView();
    await screen.findByText('No issues found.');
    const title = screen.getByText('AI Chat Review');
    const speedButton = screen.getByRole('button', { name: 'Review speed: Balanced' });
    expect(title.parentElement).toContainElement(speedButton);
    expect(screen.queryByRole('combobox', { name: 'Model' })).not.toBeInTheDocument();
    const header = title.closest('.review-project-detail__label-row') as HTMLElement;
    fireEvent.click(within(header).getByText('Hide', { selector: 'button' }));
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

  it('preserves the confirmed speed and existing review when saving a new speed fails', async () => {
    saveUserPreferencesMock.mockRejectedValueOnce(new Error('Could not save review speed.'));
    renderReviewProjectPageView();
    await screen.findByText('No issues found.');
    fireEvent.click(screen.getByRole('button', { name: 'Review speed: Balanced' }));
    const panel = screen.getByRole('dialog', { name: 'Review speed' });
    const slider = within(panel).getByRole('slider', { name: 'Review speed' });
    fireEvent.change(slider, { target: { value: '4' } });
    fireEvent.pointerUp(slider);

    expect(await within(panel).findByRole('alert')).toHaveTextContent(
      'Could not save review speed.',
    );
    expect(saveUserPreferencesMock.mock.calls[0][0]).toEqual({ aiReviewPreset: 'deep' });
    expect(screen.getByRole('button', { name: 'Review speed: Balanced' })).toHaveAttribute(
      'aria-disabled',
      'false',
    );
    expect(slider).toHaveValue('2');
    expect(screen.getByText('No issues found.')).toBeVisible();
    expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
    expect(requestAiReviewMock.mock.calls[0][0]).toMatchObject({ presetId: 'balanced' });
  });

  it('retries a failed preferences load from the speed popup before requesting review', async () => {
    fetchUserPreferencesMock.mockRejectedValueOnce(new Error('Settings unavailable'));
    renderReviewProjectPageView();
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

  it('enables automatic review without changing the selected preset', async () => {
    fetchUserPreferencesMock.mockResolvedValue({ ...preferences, aiReviewAutomaticDisabled: true });
    saveUserPreferencesMock.mockResolvedValue(preferences);
    renderReviewProjectPageView();
    const speedButton = await screen.findByRole('button', { name: 'Review speed: Balanced' });
    await waitFor(() => expect(speedButton).toHaveAttribute('aria-disabled', 'false'));
    fireEvent.click(speedButton);
    expect(requestAiReviewMock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Automatic review' }));
    await screen.findByText('No issues found.');
    expect(saveUserPreferencesMock.mock.calls[0][0]).toEqual({ aiReviewAutomaticDisabled: false });
    expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
    expect(requestAiReviewMock.mock.calls[0][0]).toMatchObject({
      presetId: 'balanced',
      requestType: 'automatic',
    });
  });

  it('skips precomputed AI review when glossary context is available', async () => {
    fetchPrecomputedAiReviewMock.mockResolvedValue({
      message: { role: 'assistant', content: 'Cached review without glossary context.' },
      suggestions: [],
      review: null,
    });
    requestAiReviewMock.mockResolvedValue({
      message: { role: 'assistant', content: 'Live review with glossary context.' },
      suggestions: [],
      review: null,
    });
    matchGlossaryTermsMock.mockResolvedValue({
      matchedTerms: [
        {
          glossaryId: 12,
          glossaryName: 'Product UI',
          tmTextUnitId: 44,
          source: 'Pay',
          comment: 'Payment action label.',
          definition: 'Starts checkout payment.',
          partOfSpeech: 'Verb',
          termType: 'UI label',
          enforcement: 'Required',
          status: 'Approved',
          provenance: 'Human curated',
          target: 'Pagar',
          targetComment: 'Use the payment verb.',
          doNotTranslate: false,
          caseSensitive: false,
          matchType: 'EXACT',
          startIndex: 0,
          endIndex: 3,
          matchedText: 'Pay',
          evidence: [],
        },
      ],
    });
    const repositoryTextUnit: ApiReviewProjectTextUnit = {
      ...textUnit,
      tmTextUnit: {
        ...textUnit.tmTextUnit!,
        asset: {
          assetPath: 'checkout.json',
          repository: { id: 77, name: 'chatgpt-web' },
        },
      },
    };

    renderReviewProjectPageView({
      project: {
        ...project,
        reviewProjectTextUnits: [repositoryTextUnit],
      },
    });

    await waitFor(() => {
      expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
    });
    expect(fetchPrecomputedAiReviewMock).not.toHaveBeenCalled();
    const [requestPayload] = requestAiReviewMock.mock.calls[0] as [
      { messages: Array<{ role: string; content: string }> },
    ];
    expect(requestPayload.messages[0]?.content).toContain('glossary terms matched');
    expect(await screen.findByText('Live review with glossary context.')).toBeInTheDocument();
  });

  it('skips precomputed AI review when warning context is available', async () => {
    fetchPrecomputedAiReviewMock.mockResolvedValue({
      message: { role: 'assistant', content: 'Cached review without warning context.' },
      suggestions: [],
      review: null,
    });
    requestAiReviewMock.mockResolvedValue({
      message: { role: 'assistant', content: 'Live review with warning context.' },
      suggestions: [],
      review: null,
    });
    const warningTextUnit: ApiReviewProjectTextUnit = {
      ...textUnit,
      baselineTmTextUnitVariant: {
        ...textUnit.baselineTmTextUnitVariant!,
        content: 'Pay  {price} now',
      },
    };

    renderReviewProjectPageView({
      project: {
        ...project,
        reviewProjectTextUnits: [warningTextUnit],
      },
    });

    await waitFor(() => {
      expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
    });
    expect(fetchPrecomputedAiReviewMock).not.toHaveBeenCalled();
    const [requestPayload] = requestAiReviewMock.mock.calls[0] as [
      { messages: Array<{ role: string; content: string }> },
    ];
    expect(requestPayload.messages[0]?.content).toContain(
      'deterministic translation quality warnings',
    );
    expect(requestPayload.messages[0]?.content).toContain('double-space');
    expect(await screen.findByText('Live review with warning context.')).toBeInTheDocument();
  });

  it.each([
    { target: 'Pay\u00a0{price} now', locale: 'en-US', assisted: false, code: 'U+00A0' },
    { target: 'Payer\u202f{price}', locale: 'fr-FR', assisted: true, code: 'U+202F' },
  ])(
    'sends neutral $code context for $locale with assisted=$assisted',
    async ({ target, locale, assisted, code }) => {
      visibleTextEditorEnabledMock.mockReturnValue(assisted);
      const onRequestSaveDecision = vi.fn();
      renderReviewProjectPageView({
        project: {
          ...project,
          locale: { ...project.locale!, bcp47Tag: locale },
          reviewProjectTextUnits: [
            {
              ...textUnit,
              baselineTmTextUnitVariant: {
                ...textUnit.baselineTmTextUnitVariant!,
                content: target,
              },
            },
          ],
        },
        mutations: buildMutations({ onRequestSaveDecision }),
      });

      await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(1));
      expect(fetchPrecomputedAiReviewMock).not.toHaveBeenCalled();
      const [payload] = requestAiReviewMock.mock.calls[0] as [AiReviewRequest];
      expect(payload.target).toBe(target);
      expect(payload.localeTag).toBe(locale);
      expect(payload.messages[0].content).toContain('neutral character observations');
      expect(payload.messages[0].content).toContain(code);
      expect(payload.messages[0].content).not.toContain(
        'deterministic translation quality warnings',
      );
      expect(payload.messages[0].content).not.toContain('Contains non-breaking spaces.');
      expect(onRequestSaveDecision).not.toHaveBeenCalled();
      if (!assisted) {
        expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue(target);
        // Preserve the existing inspection signal for plain-editor users.
        expect(screen.getByRole('button', { name: '1 translation warnings' })).toBeInTheDocument();
      } else {
        expect(document.querySelector('[data-marker="⏤"]')).toBeInTheDocument();
      }
    },
  );

  it('keeps neutral observations separate from a real boundary warning', async () => {
    const target = '\u00a0Pay {price}\u202fnow';
    renderReviewProjectPageView({
      project: {
        ...project,
        reviewProjectTextUnits: [
          {
            ...textUnit,
            baselineTmTextUnitVariant: { ...textUnit.baselineTmTextUnitVariant!, content: target },
          },
        ],
      },
    });

    await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(1));
    const [payload] = requestAiReviewMock.mock.calls[0] as [AiReviewRequest];
    const [warnings, observations] = payload.messages[0].content.split('\n\n');
    expect(warnings).toContain('leading-space: Unexpected leading whitespace at start.');
    expect(warnings).not.toMatch(/NBSP|non-breaking|nbsp/);
    expect(observations).toContain('NBSP (U+00A0)');
    expect(observations).toContain('NNBSP (U+202F)');
    expect(payload.target).toBe(target);
  });

  it('rebuilds neutral space observations from the correct target on follow-up and retry', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    requestAiReviewMock.mockRejectedValueOnce(new Error('Initial review failed'));
    const baseline = 'Pay\u00a0{price} now';
    renderReviewProjectPageView({
      project: {
        ...project,
        reviewProjectTextUnits: [
          {
            ...textUnit,
            baselineTmTextUnitVariant: {
              ...textUnit.baselineTmTextUnitVariant!,
              content: baseline,
            },
          },
        ],
      },
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await screen.findByText('No issues found.');
    const [initialRetry] = requestAiReviewMock.mock.calls[1] as [AiReviewRequest];
    expect(initialRetry).toMatchObject({
      presetId: 'balanced',
      requestType: 'retry',
      surface: 'review_project',
    });
    expect(initialRetry.target).toBe(baseline);
    expect(initialRetry.messages[0].content).toContain('NBSP (U+00A0)');

    const draft = 'Pay {price}\u202fnow';
    fireEvent.change(screen.getByRole('textbox', { name: 'Translation' }), {
      target: { value: draft },
    });
    fireEvent.change(
      screen.getByPlaceholderText('Chat with AI: rephrase, adjust the tone, or ask a question…'),
      {
        target: { value: 'Check this spacing.' },
      },
    );
    requestAiReviewMock.mockRejectedValueOnce(new Error('Follow-up failed'));
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(4));

    for (const [payload] of requestAiReviewMock.mock.calls.slice(2) as [AiReviewRequest][]) {
      expect(payload.target).toBe(draft);
      expect(payload.messages[0].content).toContain('NNBSP (U+202F)');
      expect(payload.messages[0].content).not.toContain('NBSP (U+00A0)');
      expect(payload.messages[0].content).not.toContain(
        'deterministic translation quality warnings',
      );
    }
    expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue(draft);
  });

  it('aborts the automatic AI review request when the selected text unit changes', async () => {
    requestAiReviewMock.mockImplementation(() => new Promise(() => undefined));
    const nextTextUnit: ApiReviewProjectTextUnit = {
      ...textUnit,
      id: 102,
      tmTextUnit: {
        ...textUnit.tmTextUnit!,
        id: 4,
        name: 'checkout.cancel',
        content: 'Cancel payment',
        comment: 'Checkout cancel copy',
      },
      baselineTmTextUnitVariant: {
        id: 31,
        content: 'Cancel payment',
        status: 'REVIEW_NEEDED',
        includedInLocalizedFile: true,
        comment: null,
      },
    };
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });
    const baseProps: ReviewProjectPageViewProps = {
      projectId: project.id,
      project: {
        ...project,
        reviewProjectTextUnits: [textUnit],
      },
      mutations: buildMutations(),
      selectedTextUnitQueryId: null,
      onSelectedTextUnitIdChange: noop,
      openRequestDetailsQuery: false,
      requestDetailsSource: null,
      onRequestDetailsQueryHandled: noop,
      onRequestDetailsFlowFinished: noop,
    };

    const { rerender } = render(renderReviewProjectPageViewNode(baseProps, queryClient));

    await waitFor(() => {
      expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
    });
    const [, requestOptions] = requestAiReviewMock.mock.calls[0] as [
      unknown,
      { signal?: AbortSignal },
    ];
    const signal = requestOptions.signal;
    expect(signal).toBeDefined();
    expect(signal?.aborted).toBe(false);

    rerender(
      renderReviewProjectPageViewNode(
        {
          ...baseProps,
          project: {
            ...project,
            reviewProjectTextUnits: [nextTextUnit],
          },
        },
        queryClient,
      ),
    );

    await waitFor(() => {
      expect(signal?.aborted).toBe(true);
    });
  });

  it('aborts and discards an old response when the saved preset changes', async () => {
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
    saveUserPreferencesMock.mockResolvedValue({ ...preferences, aiReviewPreset: 'fastest' });
    renderReviewProjectPageView();
    await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(1));
    const oldSignal = (requestAiReviewMock.mock.calls[0][1] as { signal: AbortSignal }).signal;
    fireEvent.click(screen.getByRole('button', { name: 'Review speed: Balanced' }));
    const slider = screen.getByRole('slider', { name: 'Review speed' });
    fireEvent.change(slider, { target: { value: '0' } });
    fireEvent.pointerUp(slider);
    await screen.findByText('No issues found.');
    expect(saveUserPreferencesMock.mock.calls[0][0]).toEqual({ aiReviewPreset: 'fastest' });
    expect(oldSignal.aborted).toBe(true);
    expect(requestAiReviewMock.mock.calls[1][0]).toMatchObject({
      presetId: 'fastest',
      requestType: 'automatic',
    });
    await act(async () => {
      finishOldReview({
        message: { role: 'assistant', content: 'Old preset answer' },
        suggestions: [],
      });
      await Promise.resolve();
    });
    expect(screen.queryByText('Old preset answer')).not.toBeInTheDocument();
  });

  it('saves the selected speed before replacing an automatic review and ignores its old result', async () => {
    let finishOldReview!: (value: AiReviewResponse) => void;
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
    renderReviewProjectPageView();
    await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(1));
    const oldSignal = (requestAiReviewMock.mock.calls[0][1] as { signal: AbortSignal }).signal;
    expect(requestAiReviewMock.mock.calls[0][0]).toMatchObject({ presetId: 'balanced' });

    fireEvent.click(screen.getByRole('button', { name: 'Review speed: Balanced' }));
    const slider = screen.getByRole('slider', { name: 'Review speed' });
    fireEvent.change(slider, { target: { value: '5' } });
    expect(saveUserPreferencesMock).not.toHaveBeenCalled();
    fireEvent.pointerUp(slider);
    await waitFor(() => expect(saveUserPreferencesMock).toHaveBeenCalledTimes(1));
    expect(saveUserPreferencesMock.mock.calls[0][0]).toEqual({ aiReviewPreset: 'ultra' });
    expect(screen.getByRole('button', { name: 'Review speed: Balanced' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    expect(oldSignal.aborted).toBe(false);
    expect(requestAiReviewMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      finishSave({ ...preferences, aiReviewPreset: 'ultra' });
      await Promise.resolve();
    });
    await screen.findByText('No issues found.');
    expect(screen.getByRole('button', { name: 'Review speed: Ultra' })).toHaveAttribute(
      'aria-disabled',
      'false',
    );
    expect(oldSignal.aborted).toBe(true);
    expect(requestAiReviewMock.mock.calls[1][0]).toMatchObject({
      presetId: 'ultra',
      requestType: 'automatic',
      surface: 'review_project',
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

  it('stops an automatic request when automatic review is disabled and allows manual Ask', async () => {
    requestAiReviewMock.mockImplementationOnce(() => new Promise(() => undefined));
    saveUserPreferencesMock.mockResolvedValue({ ...preferences, aiReviewAutomaticDisabled: true });
    renderReviewProjectPageView();
    await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(1));
    const oldSignal = (requestAiReviewMock.mock.calls[0][1] as { signal: AbortSignal }).signal;
    fireEvent.click(screen.getByRole('button', { name: 'Review speed: Balanced' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Automatic review' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Review' })).toBeEnabled());
    expect(oldSignal.aborted).toBe(true);
    fireEvent.change(
      screen.getByPlaceholderText('Chat with AI: rephrase, adjust the tone, or ask a question…'),
      {
        target: { value: 'Check this translation.' },
      },
    );
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
    await screen.findByText('No issues found.');
    expect(requestAiReviewMock.mock.calls[1][0]).toMatchObject({
      presetId: 'balanced',
      requestType: 'manual',
    });
  });

  it('keeps an in-flight manual Ask when automatic review is disabled', async () => {
    renderReviewProjectPageView();
    await screen.findByText('No issues found.');
    let finishManual!: (value: {
      message: { role: 'assistant'; content: string };
      suggestions: [];
    }) => void;
    requestAiReviewMock.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishManual = resolve;
        }),
    );
    saveUserPreferencesMock.mockResolvedValue({ ...preferences, aiReviewAutomaticDisabled: true });
    fireEvent.change(
      screen.getByPlaceholderText('Chat with AI: rephrase, adjust the tone, or ask a question…'),
      {
        target: { value: 'Explain the terminology.' },
      },
    );
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
    await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(2));
    const signal = (requestAiReviewMock.mock.calls[1][1] as { signal: AbortSignal }).signal;
    fireEvent.click(screen.getByRole('button', { name: 'Review speed: Balanced' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Automatic review' }));
    await waitFor(() =>
      expect(screen.getByRole('checkbox', { name: 'Automatic review' })).not.toBeChecked(),
    );
    expect(signal.aborted).toBe(false);
    await act(async () => {
      finishManual({
        message: { role: 'assistant', content: 'Manual explanation completed.' },
        suggestions: [],
      });
      await Promise.resolve();
    });
    expect(await screen.findByText('Manual explanation completed.')).toBeInTheDocument();
    expect(requestAiReviewMock).toHaveBeenCalledTimes(2);
  });

  it('ignores pending manual AI chat responses after the selected text unit changes', async () => {
    let resolveManualReview!: (value: {
      message: { role: 'assistant'; content: string };
      suggestions: [];
      review: null;
    }) => void;
    requestAiReviewMock.mockReset();
    requestAiReviewMock
      .mockResolvedValueOnce({
        message: { role: 'assistant', content: 'Initial review.' },
        suggestions: [],
        review: null,
      })
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveManualReview = resolve;
          }),
      )
      .mockResolvedValue({
        message: { role: 'assistant', content: 'Next text unit review.' },
        suggestions: [],
        review: null,
      });
    const nextTextUnit: ApiReviewProjectTextUnit = {
      ...textUnit,
      id: 102,
      tmTextUnit: {
        ...textUnit.tmTextUnit!,
        id: 4,
        name: 'checkout.cancel',
        content: 'Cancel payment',
        comment: 'Checkout cancel copy',
      },
      baselineTmTextUnitVariant: {
        id: 31,
        content: 'Cancel payment',
        status: 'REVIEW_NEEDED',
        includedInLocalizedFile: true,
        comment: null,
      },
    };
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });
    const baseProps: ReviewProjectPageViewProps = {
      projectId: project.id,
      project: {
        ...project,
        reviewProjectTextUnits: [textUnit],
      },
      mutations: buildMutations(),
      selectedTextUnitQueryId: null,
      onSelectedTextUnitIdChange: noop,
      openRequestDetailsQuery: false,
      requestDetailsSource: null,
      onRequestDetailsQueryHandled: noop,
      onRequestDetailsFlowFinished: noop,
    };

    const { rerender } = render(renderReviewProjectPageViewNode(baseProps, queryClient));

    expect(await screen.findByText('Initial review.')).toBeInTheDocument();
    fireEvent.change(
      screen.getByPlaceholderText('Chat with AI: rephrase, adjust the tone, or ask a question…'),
      {
        target: { value: 'Can you improve it?' },
      },
    );
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

    await waitFor(() => {
      expect(requestAiReviewMock).toHaveBeenCalledTimes(2);
    });
    const [, manualRequestOptions] = requestAiReviewMock.mock.calls[1] as [
      unknown,
      { signal?: AbortSignal },
    ];
    const manualSignal = manualRequestOptions.signal;
    expect(manualSignal).toBeDefined();
    expect(manualSignal?.aborted).toBe(false);

    rerender(
      renderReviewProjectPageViewNode(
        {
          ...baseProps,
          project: {
            ...project,
            reviewProjectTextUnits: [nextTextUnit],
          },
        },
        queryClient,
      ),
    );
    await waitFor(() => {
      expect(manualSignal?.aborted).toBe(true);
    });
    expect(await screen.findByText('Next text unit review.')).toBeInTheDocument();

    act(() => {
      resolveManualReview({
        message: { role: 'assistant', content: 'Stale manual answer.' },
        suggestions: [],
        review: null,
      });
    });

    expect(screen.queryByText('Stale manual answer.')).not.toBeInTheDocument();
  });
});
