import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { type ComponentProps } from 'react';
import { flushSync } from 'react-dom';
import type * as ReactRouterDom from 'react-router-dom';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as AgentReviewsApi from '../../api/agent-reviews';
import type { AiReviewRequest, AiReviewResponse } from '../../api/ai-review';
import type * as GlossariesApi from '../../api/glossaries';
import type * as ReviewFeedbackApi from '../../api/review-feedback';
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
import { installProseMirrorDomMock } from '../../test/proseMirrorDom';
import {
  type ReviewProjectMutationControls,
  useReviewProjectMutations,
} from './review-project-mutations';
import { ReviewProjectPageView } from './ReviewProjectPageView';

const fetchAgentReviewFeedbackMock = vi.hoisted(() => vi.fn());
const saveAgentReviewOutcomeMock = vi.hoisted(() =>
  vi.fn<typeof AgentReviewsApi.saveAgentReviewOutcome>(),
);
const reopenAgentFindingMock = vi.hoisted(() => vi.fn<typeof AgentReviewsApi.reopenAgentFinding>());
const fetchReviewFeedbackBaselineMock = vi.hoisted(() =>
  vi.fn<typeof ReviewFeedbackApi.fetchReviewFeedbackBaseline>(),
);
vi.mock('../../api/review-feedback', async (importActual) => ({
  ...(await importActual<typeof ReviewFeedbackApi>()),
  fetchReviewFeedbackBaseline: fetchReviewFeedbackBaselineMock,
}));
vi.mock('../../api/agent-reviews', async (importActual) => ({
  ...(await importActual<typeof AgentReviewsApi>()),
  fetchAgentReviewFeedback: fetchAgentReviewFeedbackMock,
  saveAgentReviewOutcome: saveAgentReviewOutcomeMock,
  reopenAgentFinding: reopenAgentFindingMock,
}));

const matchGlossaryTermsMock = vi.hoisted(() => vi.fn());
const fetchPrecomputedAiReviewMock = vi.hoisted(() => vi.fn());
const requestAiReviewMock = vi.hoisted(() => vi.fn());
const saveReviewProjectTextUnitDecisionMock = vi.hoisted(() => vi.fn());
const visibleTextEditorEnabledMock = vi.hoisted(() => vi.fn(() => true));
const mf2TranslationEditorHostMock = vi.hoisted(() => ({ enabled: false, errorCount: 0 }));
const virtualRowsLimitMock = vi.hoisted(() => ({ value: 1 }));
const searchTextUnitsMock = vi.hoisted(() => vi.fn());
const fetchTextUnitHistoryMock = vi.hoisted(() =>
  vi.fn<typeof TextUnitsApi.fetchTextUnitHistory>(),
);
const fetchAiTranslateTextUnitAttemptsMock = vi.hoisted(() =>
  vi.fn<typeof TextUnitsApi.fetchAiTranslateTextUnitAttempts>(),
);
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
  fetchTextUnitHistory: fetchTextUnitHistoryMock,
  fetchAiTranslateTextUnitAttempts: fetchAiTranslateTextUnitAttemptsMock,
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
  useVirtualRows: ({ count }: { count: number }) => ({
    scrollRef: { current: null },
    virtualizer: {},
    items: Array.from({ length: Math.min(count, virtualRowsLimitMock.value) }, (_, index) => ({
      index,
      key: `review-row-${index}`,
      start: index * 100,
      end: (index + 1) * 100,
      size: 100,
      lane: 0,
    })),
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

const testQueryClients = new Set<QueryClient>();

function createQueryClient(options?: ConstructorParameters<typeof QueryClient>[0]) {
  const queryClient = new QueryClient(options);
  testQueryClients.add(queryClient);
  return queryClient;
}

afterEach(() => {
  cleanup();
  // Retained-draft unload guards intentionally outlive a page, but not a test session.
  for (const queryClient of testQueryClients) queryClient.clear();
  testQueryClients.clear();
});

beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', {
    configurable: true,
    value: vi.fn(),
  });
});

beforeEach(() => {
  virtualRowsLimitMock.value = 1;
  fetchAgentReviewFeedbackMock.mockReset();
  saveAgentReviewOutcomeMock.mockReset();
  fetchAgentReviewFeedbackMock.mockResolvedValue([]);
  fetchReviewFeedbackBaselineMock.mockReset();
  fetchReviewFeedbackBaselineMock.mockResolvedValue({
    target: null,
    ai: false,
    kind: 'UNATTRIBUTED',
  });
  reopenAgentFindingMock.mockReset();
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
  fetchTextUnitHistoryMock.mockReset();
  fetchTextUnitHistoryMock.mockResolvedValue([]);
  fetchAiTranslateTextUnitAttemptsMock.mockReset();
  fetchAiTranslateTextUnitAttemptsMock.mockResolvedValue([]);
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
  const queryClient = createQueryClient({
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

  it('completes a source printf placeholder before accepting the exact translation', async () => {
    const restoreDom = installProseMirrorDomMock();
    const user = userEvent.setup();
    const onRequestSaveDecision = vi.fn();
    const unit: ApiReviewProjectTextUnit = {
      ...textUnit,
      tmTextUnit: { ...textUnit.tmTextUnit!, content: 'Pay %1$s now' },
      baselineTmTextUnitVariant: { ...textUnit.baselineTmTextUnitVariant!, content: '' },
    };

    try {
      renderReviewProjectPageView({
        project: { ...project, reviewProjectTextUnits: [unit] },
        mutations: buildMutations({ onRequestSaveDecision }),
      });
      const editor = await screen.findByRole('textbox', { name: 'Translation' });
      editor.focus();
      await user.keyboard('%');

      expect(await screen.findByRole('listbox', { name: 'Source placeholders' })).toBeVisible();
      await user.keyboard('{Enter}');
      expect(onRequestSaveDecision).not.toHaveBeenCalled();
      await waitFor(() => expect(editor).toHaveTextContent('%1$s'));
      expect(editor.querySelector('.visible-text-editor__protected-token')).toBeInTheDocument();

      await user.keyboard('{Control>}{Enter}{/Control}');
      expect(onRequestSaveDecision).toHaveBeenCalledWith(
        expect.objectContaining({ target: '%1$s', textUnitId: unit.id, status: 'APPROVED' }),
      );
    } finally {
      restoreDom();
    }
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
    const queryClient = createQueryClient({
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
    const queryClient = createQueryClient({
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
    const queryClient = createQueryClient({
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
    const queryClient = createQueryClient();
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
    const queryClient = createQueryClient({
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

    expect(onRequestDecisionState).toHaveBeenCalledWith(
      expect.objectContaining({
        decisionState: 'DECIDED',
        expectedCurrentTmTextUnitVariantId: null,
        expectedReviewStateRevision: null,
        textUnitId: rejectedMf2TextUnit.id,
      }),
    );
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

  it('bypasses untagged precomputed reviews and sends the selected preset and default style', async () => {
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
        reviewStyle: 'corrections_and_alternatives',
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
        aiReviewStyle: 'corrections_only',
      });
      await Promise.resolve();
    });
    const review = await screen.findByRole('button', { name: 'Review' });
    expect(review).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Review speed: Balanced' })).toHaveAttribute(
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
      presetId: 'balanced',
      requestType: 'manual',
      reviewStyle: 'corrections_only',
      surface: 'review_project',
      target: 'Pagar {price} hoje',
    });
    expect(saveUserPreferencesMock).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Review' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ask' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Review speed: Balanced' }));
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
      presetId: 'balanced',
      requestType: 'follow_up',
      reviewStyle: 'corrections_only',
      surface: 'review_project',
    });
  });

  it('waits for a saved style before refreshing automatic review and discards the previous style result', async () => {
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
    fetchUserPreferencesMock.mockResolvedValue({
      ...preferences,
      aiReviewStyle: 'corrections_only',
    });
    renderReviewProjectPageView();
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
      surface: 'review_project',
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
    fetchUserPreferencesMock.mockResolvedValue({ ...preferences, aiReviewAutomaticDisabled: true });
    renderReviewProjectPageView();
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
      surface: 'review_project',
    });
    expect(screen.getByRole('checkbox', { name: 'Automatic review' })).not.toBeChecked();
    expect(requestAiReviewMock).toHaveBeenCalledTimes(2);
  });

  it('saves score visibility without cancelling or repeating the review or clearing its result', async () => {
    let finishReview!: (value: AiReviewResponse) => void;
    requestAiReviewMock.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishReview = resolve;
        }),
    );
    renderReviewProjectPageView();
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
    fireEvent.change(slider, { target: { value: '1' } });
    fireEvent.pointerUp(slider);

    expect(await within(panel).findByRole('alert')).toHaveTextContent(
      'Could not save review speed.',
    );
    expect(saveUserPreferencesMock.mock.calls[0][0]).toEqual({ aiReviewPreset: 'fast' });
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
    { target: 'Pay\u00a0{price} now', locale: 'en-US', assisted: true, code: 'U+00A0' },
    { target: 'Payer\u202f{price}', locale: 'fr-FR', assisted: false, code: 'U+202F' },
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
      } else {
        expect(
          document.querySelector(`[data-marker="${code === 'U+00A0' ? '⍽' : '⏤'}"]`),
        ).toBeInTheDocument();
      }
      expect(
        screen.queryByRole('button', { name: /translation warnings/ }),
      ).not.toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Non-breaking spaces' }));
      const dialog = screen.getByRole('dialog', { name: 'Non-breaking spaces' });
      expect(within(dialog).getByText(new RegExp(code.replace('+', '\\+')))).toBeInTheDocument();
      expect(within(dialog).queryByText(/issues? detected/)).not.toBeInTheDocument();
      expect(
        dialog.querySelector('.review-project-detail__warning-modal-preview-issue'),
      ).toBeNull();
      expect(onRequestSaveDecision).not.toHaveBeenCalled();
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
    fireEvent.click(screen.getByRole('button', { name: '1 translation warnings' }));
    const dialog = screen.getByRole('dialog', { name: 'Translation warnings' });
    expect(within(dialog).getByText('1 issue detected.')).toBeInTheDocument();
    expect(within(dialog).getByText('Unexpected leading whitespace at start.')).toBeInTheDocument();
    expect(
      Array.from(
        dialog.querySelectorAll('.review-project-detail__warning-modal-preview-issue'),
      ).map((element) => element.textContent),
    ).toEqual(['⍽']);
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
      reviewStyle: 'corrections_and_alternatives',
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
      expect(payload.reviewStyle).toBe('corrections_and_alternatives');
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
    const queryClient = createQueryClient({
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

  it.each([
    ['ROLE_TRANSLATOR', '1', 'fast', 'Fast', '2'],
    ['ROLE_ADMIN', '5', 'ultra', 'Ultra', '5'],
  ] as const)(
    'saves a permitted speed for %s before replacing an automatic review and ignores its old result',
    async (role, value, preset, label, max) => {
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
      renderReviewProjectPageView({}, { ...user, role });
      await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(1));
      const oldSignal = (requestAiReviewMock.mock.calls[0][1] as { signal: AbortSignal }).signal;
      expect(requestAiReviewMock.mock.calls[0][0]).toMatchObject({ presetId: 'balanced' });

      fireEvent.click(screen.getByRole('button', { name: 'Review speed: Balanced' }));
      const slider = screen.getByRole('slider', { name: 'Review speed' });
      expect(slider).toHaveAttribute('max', max);
      fireEvent.change(slider, { target: { value } });
      expect(saveUserPreferencesMock).not.toHaveBeenCalled();
      fireEvent.pointerUp(slider);
      await waitFor(() => expect(saveUserPreferencesMock).toHaveBeenCalledTimes(1));
      expect(saveUserPreferencesMock.mock.calls[0][0]).toEqual({ aiReviewPreset: preset });
      expect(screen.getByRole('button', { name: 'Review speed: Balanced' })).toHaveAttribute(
        'aria-disabled',
        'true',
      );
      expect(oldSignal.aborted).toBe(false);
      expect(requestAiReviewMock).toHaveBeenCalledTimes(1);

      await act(async () => {
        finishSave({ ...preferences, aiReviewPreset: preset });
        await Promise.resolve();
      });
      await screen.findByText('No issues found.');
      expect(screen.getByRole('button', { name: `Review speed: ${label}` })).toHaveAttribute(
        'aria-disabled',
        'false',
      );
      expect(oldSignal.aborted).toBe(true);
      expect(requestAiReviewMock.mock.calls[1][0]).toMatchObject({
        presetId: preset,
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
    },
  );

  it('explains automatic Ultra fallback while preserving Ultra for manual Ask', async () => {
    fetchUserPreferencesMock.mockResolvedValue({ ...preferences, aiReviewPreset: 'ultra' });
    renderReviewProjectPageView({}, { ...user, role: 'ROLE_ADMIN' });
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
    expect(screen.getByRole('button', { name: 'Review speed: Ultra' })).toHaveTextContent(
      'Auto: Balanced',
    );
    expect(saveUserPreferencesMock).not.toHaveBeenCalled();
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
    const queryClient = createQueryClient({
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

function buildAgentTextUnit(
  overrides: Partial<NonNullable<ApiReviewProjectTextUnit['agentReview']>> = {},
): ApiReviewProjectTextUnit {
  return {
    ...textUnit,
    reviewStateRevision: 'agent-row-v1',
    currentTmTextUnitVariant: textUnit.baselineTmTextUnitVariant,
    agentReview: {
      proposalId: 901,
      proposalRevision: 1,
      proposalVersion: 2,
      findingId: 'finding-checkout',
      runId: 51,
      reviewType: 'TRANSLATION_QUALITY',
      reviewedSource: 'Pay {price} now',
      reviewedTarget: 'Pay {price} now',
      proposedTarget: 'Pague {price} agora',
      rationale: 'The target is still in English.',
      category: 'OBVIOUS_ERROR',
      verificationStatus: 'READY',
      verificationNotes: 'Confirmed this is a payment action.',
      disposition: 'ROUTED',
      stale: false,
      evidence: [
        { label: 'Checkout screenshot', url: 'https://example.com/screenshot' },
        { label: 'Unsafe link stays text', url: 'javascript:alert(1)' },
      ],
      ...overrides,
    },
  };
}

function renderAgentReview(
  overrides: Partial<NonNullable<ApiReviewProjectTextUnit['agentReview']>> = {},
  mutations = buildMutations(),
) {
  return renderReviewProjectPageView({
    project: { ...project, reviewProjectTextUnits: [buildAgentTextUnit(overrides)] },
    mutations,
  });
}

function renderReviewWithRetainedNotes(
  row: ApiReviewProjectTextUnit,
  notes: { comment?: string; decisionNotes?: string },
  mutations = buildMutations(),
  originalAssessment?: 'BAD',
) {
  visibleTextEditorEnabledMock.mockReturnValue(false);
  const queryClient = createQueryClient({ defaultOptions: { queries: { retry: false } } });
  const props: ReviewProjectPageViewProps = {
    projectId: 7,
    project: { ...project, reviewProjectTextUnits: [row] },
    mutations,
    selectedTextUnitQueryId: row.id,
    onSelectedTextUnitIdChange: noop,
    openRequestDetailsQuery: false,
    requestDetailsSource: null,
    onRequestDetailsQueryHandled: noop,
    onRequestDetailsFlowFinished: noop,
  };
  const firstVisit = render(renderReviewProjectPageViewNode(props, queryClient));
  fireEvent.change(screen.getByRole('textbox', { name: 'Translation' }), {
    target: { value: 'Temporary draft for retained-note setup' },
  });
  firstVisit.unmount();
  const key = ['review-project-draft', 'translator', 7, row.id];
  const retained = queryClient.getQueryData<{
    base: { target: string };
    values: Record<string, unknown>;
  }>(key)!;
  expect(retained).toBeDefined();
  queryClient.setQueryData(key, {
    ...retained,
    values: { ...retained.values, target: retained.base.target, ...notes },
  });
  if (originalAssessment && row.agentReview) {
    const base = { originalAssessment: '', suggestionAssessment: '', explanation: '' };
    queryClient.setQueryData([...key, `agent-feedback:${row.agentReview.proposalId}`], {
      base,
      values: { ...base, originalAssessment },
    });
  }
  return {
    view: render(renderReviewProjectPageViewNode(props, queryClient)),
    props,
    queryClient,
  };
}

describe('Agent proposal review in Review Projects', () => {
  it('filters incidents by their review state instead of the current translation status', () => {
    virtualRowsLimitMock.value = 10;
    const cases = [
      ['pending', { disposition: 'ROUTED' }],
      ['reviewed', { disposition: 'RESOLVED' }],
      ['waiting', { disposition: 'FOLLOW_UP' }],
      ['responded', { disposition: 'FOLLOW_UP', canReconsider: true }],
      ['changed', { disposition: 'ROUTED', stale: true }],
    ] as const;
    const rows = cases.map(([name, agent], index) => {
      const row = buildAgentTextUnit(agent);
      return {
        ...row,
        id: 101 + index,
        tmTextUnit: { ...row.tmTextUnit!, id: 3 + index, name },
        currentTmTextUnitVariant: { ...row.currentTmTextUnitVariant!, status: 'APPROVED' },
        agentReview: { ...row.agentReview!, proposalId: 901 + index },
        reviewProjectTextUnitDecision: {
          decisionState: index === 0 || index === 4 ? 'PENDING' : 'DECIDED',
        },
      };
    });
    const { container } = renderReviewProjectPageView({
      project: { ...project, reviewProjectTextUnits: rows },
    });
    const queue = container.querySelector('.review-project-page__list-pane') as HTMLElement;
    fireEvent.click(screen.getByRole('button', { name: 'Filter text units' }));
    expect(screen.getByText('Review status')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Accepted' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edited' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Awaiting review' }));
    expect(queue.querySelectorAll('.review-project-row')).toHaveLength(2);
    expect(within(queue).getByText('pending')).toBeVisible();
    expect(within(queue).getByText('changed')).toBeVisible();
    for (const [filter, name] of [
      ['Reviewed', 'reviewed'],
      ['Waiting for agent', 'waiting'],
      ['Agent responded', 'responded'],
      ['Changed since review', 'changed'],
    ]) {
      fireEvent.click(screen.getByRole('button', { name: filter }));
      expect(queue.querySelectorAll('.review-project-row')).toHaveLength(1);
      expect(within(queue).getByText(name)).toBeVisible();
    }
  });

  it('keeps a translation draft intact when the selected incident is filtered out', () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    renderAgentReview();
    fireEvent.change(screen.getByRole('textbox', { name: 'Translation' }), {
      target: { value: 'My unsaved correction' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Filter text units' }));
    fireEvent.click(screen.getByRole('button', { name: 'Reviewed' }));
    expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue(
      'My unsaved correction',
    );
    expect(screen.queryByRole('dialog', { name: 'Discard changes?' })).not.toBeInTheDocument();
  });

  it('preserves the regular review status filters', () => {
    renderReviewProjectPageView();
    fireEvent.click(screen.getByRole('button', { name: 'Filter text units' }));
    const filters = within(
      screen.getByText('All states').closest('.filter-chip__panel') as HTMLElement,
    );
    expect(filters.getByRole('button', { name: 'Pending' })).toBeVisible();
    expect(filters.getByRole('button', { name: 'Needs review' })).toBeVisible();
    expect(filters.getByRole('button', { name: 'Edited' })).toBeVisible();
    expect(screen.queryByText('Review status')).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Report' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add decision note' })).not.toBeInTheDocument();
  });

  it('keeps the report above the regular editor and separate from optional AI chat', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const { container } = renderAgentReview();
    expect(container.querySelector('.review-project-row')).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Incident review queue' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Review finding' })).not.toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue('Pay {price} now');
    expect(screen.getByText('AI Chat Review')).toBeVisible();
    expect(await screen.findByText('The target is still in English.')).toBeVisible();
    const report = screen.getByRole('region', { name: 'Reported issue' });
    expect(within(report).getByRole('button', { name: 'View report →' })).toBeVisible();
    expect(screen.queryByRole('button', { name: /decision note/i })).not.toBeInTheDocument();
    expect(within(report).getByRole('heading', { name: 'Original at review' })).toBeVisible();
    expect(within(report).getByRole('heading', { name: 'Proposed correction' })).toBeVisible();
    expect(
      report.compareDocumentPosition(screen.getByRole('textbox', { name: 'Translation' })) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(container.querySelector('.ai-chat-review__thread')).not.toHaveTextContent(
      'The target is still in English.',
    );
    await waitFor(() => expect(screen.getByRole('button', { name: 'Review' })).toBeEnabled());
    expect(screen.getByRole('button', { name: 'Use suggestion' })).toBeEnabled();
    expect(screen.getByRole('button', { name: /^Accept$/ })).toBeEnabled();
    expect(screen.getByRole('button', { name: /^Reset$/ })).toBeDisabled();
    expect(screen.getByRole('tab', { name: 'Glossary' })).toBeVisible();
    expect(screen.getByRole('tab', { name: 'History' })).toBeVisible();
    expect(screen.getByRole('tab', { name: 'Context' })).toBeVisible();
    expect(screen.getByRole('tab', { name: 'Report' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Start a new review' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Assistant' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit translation' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Keep current' })).not.toBeInTheDocument();
    expect(screen.queryByText('More actions')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Find and replace' })).not.toBeInTheDocument();
    expect(requestAiReviewMock).not.toHaveBeenCalled();
  });

  it('opens full report details while keeping History dedicated to translation history', async () => {
    fetchTextUnitHistoryMock.mockResolvedValue([
      {
        id: 29,
        content: 'Earlier approved translation',
        status: 'APPROVED',
        createdByUser: { username: 'Earlier translator' },
      },
    ]);
    renderAgentReview();
    fireEvent.click(screen.getByRole('tab', { name: 'History' }));
    expect(await screen.findByText('Earlier approved translation')).toBeVisible();
    expect(fetchTextUnitHistoryMock).toHaveBeenCalledWith(3, 'pt-PT');
    expect(fetchAgentReviewFeedbackMock).not.toHaveBeenCalled();
    expect(
      screen.queryByRole('region', { name: 'Evidence and verification' }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText('Review feedback')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'View report →' }));
    expect(screen.getByRole('tab', { name: 'Report' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('region', { name: 'Evidence and verification' })).toBeVisible();
    expect(screen.getByRole('region', { name: 'Feedback and agent responses' })).toBeVisible();
    expect(screen.getByText('Review feedback')).toBeVisible();
    await waitFor(() => expect(fetchAgentReviewFeedbackMock).toHaveBeenCalledTimes(1));
    expect(screen.queryByText('Earlier approved translation')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Start a new review' })).not.toBeInTheDocument();
  });

  it('runs a fresh AI review only on request and keeps the recorded report separate', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    requestAiReviewMock.mockResolvedValueOnce({
      message: { role: 'assistant', content: 'This draft uses the expected payment terminology.' },
      suggestions: [],
    });
    const { container } = renderAgentReview();
    expect(requestAiReviewMock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Use suggestion' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Review' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(1));
    expect(requestAiReviewMock.mock.calls[0][0]).toMatchObject({
      requestType: 'manual',
      target: 'Pague {price} agora',
    });
    expect(
      await screen.findByText('This draft uses the expected payment terminology.'),
    ).toBeVisible();
    expect(container.querySelector('.ai-chat-review__thread')).not.toHaveTextContent(
      'The target is still in English.',
    );
    expect(
      within(screen.getByRole('region', { name: 'Reported issue' })).getByText(
        'The target is still in English.',
      ),
    ).toBeVisible();
  });

  it.each([
    ['READY', 'Automation'],
    ['HOLD', 'Automation'],
    ['HUMAN_REVIEW', 'Human review'],
  ])('labels %s requests without inventing an automation source', (verificationStatus, label) => {
    renderAgentReview({ verificationStatus });
    const report = within(screen.getByRole('region', { name: 'Reported issue' }));
    expect(report.getByText(label)).toBeVisible();
    const request = report.getByRole('button', { name: 'View report →' });
    expect(request).toBeVisible();
    expect(report.getByText('The target is still in English.')).toBeVisible();
    if (verificationStatus === 'HUMAN_REVIEW') {
      expect(report.queryByText('Automation')).not.toBeInTheDocument();
    }
    fireEvent.click(request);
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Report' })).toHaveAttribute('aria-selected', 'true');
    expect(
      within(screen.getByRole('region', { name: 'Evidence and verification' })).getByText(
        'The target is still in English.',
      ),
    ).toBeVisible();
  });

  it('keeps a long finding in the report and accessible in full details', () => {
    const rationale = 'The target uses an outdated product name. '.repeat(12).trim();
    renderAgentReview({ rationale });
    const request = screen.getByRole('button', { name: 'View report →' });
    expect(
      within(screen.getByRole('region', { name: 'Reported issue' })).getByText(rationale),
    ).toBeVisible();
    fireEvent.click(request);
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    expect(
      within(screen.getByRole('region', { name: 'Evidence and verification' })).getByText(
        rationale,
      ),
    ).toBeVisible();
  });

  it.each([
    ['RESOLVED', 'Reviewed'],
    ['FOLLOW_UP', 'Awaiting feedback'],
    ['SUPERSEDED', 'Replaced'],
  ])(
    'shows saved human and agent history in the Report tab for %s status',
    async (disposition, status) => {
      fetchAgentReviewFeedbackMock.mockResolvedValue([
        {
          id: 3,
          proposalId: 901,
          proposalRevision: 1,
          actorType: 'AGENT',
          actorIdentity: 'Agent',
          createdDate: '2026-09-14T12:00:00Z',
          action: 'CHALLENGE',
          explanation: 'A later agent response is not the saved human note.',
        },
        {
          id: 2,
          proposalId: 901,
          proposalRevision: 1,
          actorType: 'HUMAN',
          actorIdentity: 'Reviewer',
          createdDate: '2026-09-14T11:00:00Z',
          action: 'KEEP_CURRENT',
          explanation: 'The current wording matches the approved product copy.',
        },
        {
          id: 1,
          proposalId: 901,
          proposalRevision: 1,
          actorType: 'HUMAN',
          actorIdentity: 'Reviewer',
          createdDate: '2026-09-14T10:00:00Z',
          action: 'DEFER',
          explanation: 'An older note awaiting context.',
        },
      ]);
      renderAgentReview({ disposition, canReviewAgain: disposition !== 'SUPERSEDED' });
      const request = screen.getByRole('button', { name: 'View report →' });
      expect(request).toBeVisible();
      expect(
        within(screen.getByRole('region', { name: 'Reported issue' })).getByText(
          `Automation · ${status}`,
        ),
      ).toBeVisible();
      expect(fetchAgentReviewFeedbackMock).not.toHaveBeenCalled();
      expect(screen.queryByRole('button', { name: /decision note/i })).not.toBeInTheDocument();
      fireEvent.click(request);
      expect(screen.getByRole('tab', { name: 'Report' })).toHaveAttribute('aria-selected', 'true');
      const history = within(screen.getByRole('region', { name: 'Feedback and agent responses' }));
      expect(history.getByRole('heading', { name: 'Review history' })).toBeVisible();
      expect(await history.findByText('Kept current translation')).toBeVisible();
      expect(
        history.getByText('The current wording matches the approved product copy.'),
      ).toBeVisible();
      expect(history.getByText('An older note awaiting context.')).toBeVisible();
      expect(
        history.getByText('A later agent response is not the saved human note.'),
      ).toBeVisible();
      expect(fetchAgentReviewFeedbackMock).toHaveBeenCalledWith(7, 901, expect.any(AbortSignal));
      if (disposition === 'SUPERSEDED') {
        expect(screen.getByRole('button', { name: /^Pending$/ })).toBeDisabled();
      } else {
        expect(screen.getByRole('button', { name: /^Pending$/ })).toBeEnabled();
      }
      expect(screen.getByRole('region', { name: 'Evidence and verification' })).toBeVisible();
    },
  );

  it('keeps ordinary AI review feedback quiet for cosmetic edits and saves material edit feedback with Accept', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    fetchReviewFeedbackBaselineMock.mockResolvedValue({
      target: 'Pay {price} now',
      ai: true,
      kind: 'AI_TRANSLATE',
    });
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    renderReviewProjectPageView({ mutations: buildMutations({ onRequestSaveDecision }) });
    await waitFor(() => expect(fetchReviewFeedbackBaselineMock).toHaveBeenCalledTimes(1));
    await waitFor(() =>
      expect(screen.queryByText('Comment on translation')).not.toBeInTheDocument(),
    );
    expect(screen.queryByText('Decision notes')).not.toBeInTheDocument();
    expect(screen.queryByText('Unsent notes')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('region', { name: 'AI translation feedback' }),
    ).not.toBeInTheDocument();
    const editor = screen.getByRole('textbox', { name: 'Translation' });
    fireEvent.change(editor, { target: { value: 'Pay {price} now!' } });
    expect(
      screen.queryByRole('region', { name: 'AI translation feedback' }),
    ).not.toBeInTheDocument();
    fireEvent.change(editor, { target: { value: 'Pague {price} agora' } });
    fireEvent.click(await screen.findByRole('button', { name: 'Terminology' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'AI feedback note' }), {
      target: { value: 'Use the approved product term.' },
    });
    expect(screen.queryByText('Comment on translation')).not.toBeInTheDocument();
    expect(screen.queryByText('Decision notes')).not.toBeInTheDocument();
    expect(screen.queryByText('Unsent notes')).not.toBeInTheDocument();
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    expect(onRequestSaveDecision).toHaveBeenCalledOnce();
    const saved = onRequestSaveDecision.mock.calls[0][0];
    expect(saved.target).toBe('Pague {price} agora');
    expect(saved.reviewFeedback).toMatchObject({
      reason: 'TERMINOLOGY',
      note: 'Use the approved product term.',
    });
    expect(saved.decisionNotes).toBe('Use the approved product term.');
  });

  it('keeps standard note fields for ordinary reviews without an AI baseline', async () => {
    renderReviewProjectPageView();
    await waitFor(() => expect(fetchReviewFeedbackBaselineMock).toHaveBeenCalledOnce());
    expect(screen.getByText('Comment on translation')).toBeVisible();
    expect(screen.getByText('Decision notes')).toBeVisible();
    expect(screen.queryByText('Unsent notes')).not.toBeInTheDocument();
  });

  it.each(['ROUTED', 'RESOLVED'])(
    'replaces empty legacy fields with unified feedback in %s incident review',
    (disposition) => {
      renderAgentReview({ disposition, canReviewAgain: disposition === 'RESOLVED' });
      expect(screen.queryByText('Comment on translation')).not.toBeInTheDocument();
      expect(screen.queryByText('Decision notes')).not.toBeInTheDocument();
      expect(screen.queryByText('Unsent notes')).not.toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Use suggestion' }));
      expect(screen.getByRole('region', { name: 'AI translation feedback' })).toBeVisible();
      expect(screen.queryByText('Comment on translation')).not.toBeInTheDocument();
      expect(screen.queryByText('Decision notes')).not.toBeInTheDocument();
    },
  );

  it('shows incident feedback immediately on Use suggestion and retains it across original/suggestion selection', () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    renderAgentReview({}, buildMutations({ onRequestSaveDecision }));
    expect(
      screen.queryByRole('region', { name: 'AI translation feedback' }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Use suggestion' }));
    const feedbackRegion = screen.getByRole('region', { name: 'AI translation feedback' });
    const feedback = within(feedbackRegion);
    fireEvent.click(screen.getByRole('button', { name: 'Use original translation' }));
    expect(screen.getByRole('region', { name: 'AI translation feedback' })).toBe(feedbackRegion);
    fireEvent.change(screen.getByRole('textbox', { name: 'Translation' }), {
      target: { value: '' },
    });
    fireEvent.click(screen.getByRole('button', { name: /^Reset$/ }));
    expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue('Pay {price} now');
    expect(screen.getByRole('region', { name: 'AI translation feedback' })).toBe(feedbackRegion);
    fireEvent.change(feedback.getByRole('textbox', { name: 'AI feedback note' }), {
      target: { value: 'Temporary explanation' },
    });
    fireEvent.change(feedback.getByRole('textbox', { name: 'AI feedback note' }), {
      target: { value: '' },
    });
    expect(screen.getByRole('region', { name: 'AI translation feedback' })).toBe(feedbackRegion);
    fireEvent.click(screen.getByRole('button', { name: 'Use suggestion' }));
    fireEvent.click(feedback.getByRole('button', { name: 'Terminology' }));
    fireEvent.change(feedback.getByRole('textbox', { name: 'AI feedback note' }), {
      target: { value: 'Use the approved product term.' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Use original translation' }));
    expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue('Pay {price} now');
    expect(feedback.getByRole('button', { name: 'Terminology' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(feedback.getByRole('textbox', { name: 'AI feedback note' })).toHaveValue(
      'Use the approved product term.',
    );
    fireEvent.click(screen.getByRole('button', { name: 'Use suggestion' }));
    expect(feedback.getByRole('textbox', { name: 'AI feedback note' })).toHaveValue(
      'Use the approved product term.',
    );
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    expect(onRequestSaveDecision.mock.calls[0][0]).toMatchObject({
      target: 'Pague {price} agora',
      agentReview: { action: 'ACCEPT', explanation: 'Use the approved product term.' },
      reviewFeedback: { reason: 'TERMINOLOGY', note: 'Use the approved product term.' },
    });
  });

  it.each([null, 'Pague {price} agora'])(
    'shows feedback for tiny incident edits with proposal %j and retains a note without a reason when returning to original',
    (proposedTarget) => {
      visibleTextEditorEnabledMock.mockReturnValue(false);
      renderAgentReview({ proposedTarget });
      const editor = screen.getByRole('textbox', { name: 'Translation' });
      fireEvent.change(editor, { target: { value: 'Pay {price} now!' } });
      expect(screen.getByRole('region', { name: 'AI translation feedback' })).toBeVisible();
      fireEvent.change(screen.getByRole('textbox', { name: 'AI feedback note' }), {
        target: { value: 'The exclamation mark is intentional.' },
      });
      fireEvent.click(screen.getByRole('button', { name: 'Use original translation' }));
      expect(screen.getByRole('region', { name: 'AI translation feedback' })).toBeVisible();
      expect(screen.getByRole('textbox', { name: 'AI feedback note' })).toHaveValue(
        'The exclamation mark is intentional.',
      );
      fireEvent.change(editor, { target: { value: 'Pay {price} now!' } });
      expect(screen.getByRole('region', { name: 'AI translation feedback' })).toBeVisible();
      expect(screen.getByRole('textbox', { name: 'AI feedback note' })).toHaveValue(
        'The exclamation mark is intentional.',
      );
    },
  );

  it('compares incident feedback visibility to the frozen original after the accepted correction becomes current', () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const row = buildAgentTextUnit({ disposition: 'RESOLVED', canReviewAgain: true });
    row.currentTmTextUnitVariant = {
      ...row.currentTmTextUnitVariant!,
      content: 'Pague {price} agora',
    };
    row.reviewProjectTextUnitDecision = {
      decisionState: 'DECIDED',
      decisionTmTextUnitVariant: row.currentTmTextUnitVariant,
    };
    renderReviewProjectPageView({ project: { ...project, reviewProjectTextUnits: [row] } });
    expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue('Pague {price} agora');
    expect(screen.getByRole('region', { name: 'AI translation feedback' })).toBeVisible();
    expect(screen.getByRole('status', { name: 'Selected proposed correction' })).toBeVisible();
  });

  it.each(['note', 'reason'])(
    'saves a completed incident %s-only change through atomic reopening without changing the accepted translation',
    async (field) => {
      visibleTextEditorEnabledMock.mockReturnValue(false);
      const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
      const onRequestDecisionState =
        vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
      fetchAgentReviewFeedbackMock.mockResolvedValue([
        {
          id: 81,
          proposalId: 901,
          proposalRevision: 1,
          actorType: 'HUMAN',
          actorIdentity: 'Reviewer',
          createdDate: '2026-09-14T11:00:00Z',
          action: 'ACCEPT',
          explanation: 'The earlier decision accepted this correction.',
        },
      ]);
      const row = buildAgentTextUnit({ disposition: 'RESOLVED', canReviewAgain: true });
      row.currentTmTextUnitVariant = {
        ...row.currentTmTextUnitVariant!,
        id: 31,
        content: 'Pague {price} agora',
      };
      row.reviewProjectTextUnitDecision = {
        decisionState: 'DECIDED',
        decisionTmTextUnitVariant: row.currentTmTextUnitVariant,
      };
      renderReviewProjectPageView({
        project: { ...project, reviewProjectTextUnits: [row] },
        mutations: buildMutations({ onRequestSaveDecision, onRequestDecisionState }),
      });
      expect(screen.getByRole('button', { name: /^Accept$/ })).toBeDisabled();
      expect(screen.getByRole('region', { name: 'AI translation feedback' })).toBeVisible();
      fireEvent.click(screen.getByRole('button', { name: 'View report →' }));
      const history = within(screen.getByRole('region', { name: 'Feedback and agent responses' }));
      expect(
        await history.findByText('The earlier decision accepted this correction.'),
      ).toBeVisible();
      if (field === 'note') {
        fireEvent.change(screen.getByRole('textbox', { name: 'AI feedback note' }), {
          target: { value: 'This correction uses the approved product term.' },
        });
      } else {
        fireEvent.click(screen.getByRole('button', { name: 'Terminology' }));
      }
      expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue(
        'Pague {price} agora',
      );
      expect(screen.getByRole('button', { name: /^Accept$/ })).toBeEnabled();
      fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
      expect(onRequestSaveDecision).toHaveBeenCalledOnce();
      expect(onRequestSaveDecision.mock.calls[0][0]).toMatchObject({
        target: 'Pague {price} agora',
        decisionState: 'DECIDED',
        agentReview: {
          action: 'ACCEPT',
          proposalId: 901,
          explanation:
            field === 'note' ? 'This correction uses the approved product term.' : undefined,
        },
        reviewFeedback: {
          note: field === 'note' ? 'This correction uses the approved product term.' : undefined,
          reason: field === 'reason' ? 'TERMINOLOGY' : undefined,
        },
        reopenAgentReview: { expectedProposalVersion: 2, expectedCurrentVariantId: 31 },
      });
      expect(onRequestDecisionState).not.toHaveBeenCalled();
      expect(reopenAgentFindingMock).not.toHaveBeenCalled();
      expect(history.getByText('The earlier decision accepted this correction.')).toBeVisible();
    },
  );

  it('saves an optional Report explanation with normal Accept without a separate note control', () => {
    const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
    renderAgentReview({}, buildMutations({ onRequestDecisionState }));
    expect(screen.queryByRole('button', { name: /decision note/i })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'View report →' }));
    fireEvent.click(screen.getByText('Review feedback'));
    expect(onRequestDecisionState).not.toHaveBeenCalled();
    fireEvent.change(screen.getByRole('textbox', { name: 'Explanation' }), {
      target: { value: 'This wording was approved for the payment screen.' },
    });
    fireEvent.keyDown(screen.getByRole('textbox', { name: 'Explanation' }), {
      key: 'Enter',
      ctrlKey: true,
    });
    fireEvent.keyDown(screen.getByRole('textbox', { name: 'Explanation' }), {
      key: 'Enter',
      metaKey: true,
      shiftKey: true,
    });
    expect(onRequestDecisionState).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('tab', { name: 'Context' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Report' }));
    fireEvent.click(screen.getByText('Review feedback'));
    expect(screen.getByLabelText('Explanation')).toHaveValue(
      'This wording was approved for the payment screen.',
    );
    fireEvent.change(screen.getByLabelText('Explanation'), {
      target: { value: 'The payment team approved the current wording.' },
    });
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    expect(onRequestDecisionState).toHaveBeenCalledTimes(1);
    expect(onRequestDecisionState.mock.calls[0][0]).toMatchObject({
      decisionState: 'DECIDED',
      agentReview: {
        action: 'KEEP_CURRENT',
        explanation: 'The payment team approved the current wording.',
      },
    });
    expect(onRequestDecisionState.mock.calls[0][0]).not.toHaveProperty('target');
  });

  it('keeps saved Report history readable but blocks Pending while translation edits are unsaved', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    fetchAgentReviewFeedbackMock.mockResolvedValue([
      {
        id: 81,
        proposalId: 901,
        proposalRevision: 1,
        actorType: 'HUMAN',
        actorIdentity: 'Reviewer',
        createdDate: '2026-09-14T11:00:00Z',
        action: 'KEEP_CURRENT',
        explanation: 'The current wording was approved.',
      },
    ]);
    renderAgentReview({ disposition: 'RESOLVED', canReviewAgain: true });
    fireEvent.change(screen.getByRole('textbox', { name: 'Translation' }), {
      target: { value: 'Unsaved translation edit' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'View report →' }));
    const history = within(screen.getByRole('region', { name: 'Feedback and agent responses' }));
    expect(await history.findByText('The current wording was approved.')).toBeVisible();
    expect(screen.getByRole('button', { name: /^Pending$/ })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: /^Pending$/ }));
    expect(reopenAgentFindingMock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /^Reset$/ }));
    expect(screen.getByRole('button', { name: /^Pending$/ })).toBeEnabled();
    expect(history.getByText('The current wording was approved.')).toBeVisible();
  });

  it('loads saved Report history on revisit and records a new explanation after Pending', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const row = buildAgentTextUnit();
    const note = 'The payment team approved the current wording.';
    let liveProject: ApiReviewProjectDetail = { ...project, reviewProjectTextUnits: [row] };
    let savedRow: ApiReviewProjectTextUnit = row;
    let savedRevision = 0;
    saveAgentReviewOutcomeMock.mockImplementation((request) => {
      const currentRow = liveProject.reviewProjectTextUnits![0];
      savedRow = {
        ...currentRow,
        reviewStateRevision: `agent-saved-${++savedRevision}`,
        reviewProjectTextUnitDecision: {
          decisionState: 'DECIDED',
          decisionTmTextUnitVariant: currentRow.currentTmTextUnitVariant,
        },
        agentReview: {
          ...currentRow.agentReview!,
          proposalVersion: currentRow.agentReview!.proposalVersion + 1,
          disposition: 'RESOLVED',
          canReviewAgain: true,
          lastFeedbackRequestId: request.agentReview.requestId,
        },
      };
      liveProject = { ...project, reviewProjectTextUnits: [savedRow] };
      return Promise.resolve(savedRow);
    });
    fetchAgentReviewFeedbackMock.mockResolvedValue([
      {
        id: 81,
        proposalId: 901,
        proposalRevision: 1,
        actorType: 'HUMAN',
        actorIdentity: 'Reviewer',
        createdDate: '2026-09-14T11:00:00Z',
        action: 'KEEP_CURRENT',
        explanation: note,
      },
    ]);
    function LiveReview() {
      const { data } = useQuery({
        queryKey: [...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id],
        queryFn: () => Promise.resolve(liveProject),
        staleTime: Infinity,
      });
      return (
        <ReviewProjectPageView
          projectId={project.id}
          project={data ?? null}
          mutations={useReviewProjectMutations(project.id)}
          selectedTextUnitQueryId={null}
          onSelectedTextUnitIdChange={noop}
          openRequestDetailsQuery={false}
          requestDetailsSource={null}
          onRequestDetailsQueryHandled={noop}
          onRequestDetailsFlowFinished={noop}
        />
      );
    }
    function mountReview() {
      const queryClient = createQueryClient({ defaultOptions: { queries: { retry: false } } });
      queryClient.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], liveProject);
      const view = render(
        <QueryClientProvider client={queryClient}>
          <UserContext.Provider value={user}>
            <MemoryRouter>
              <LiveReview />
            </MemoryRouter>
          </UserContext.Provider>
        </QueryClientProvider>,
      );
      return { ...view, queryClient };
    }
    const firstVisit = mountReview();
    fireEvent.click(screen.getByRole('button', { name: 'View report →' }));
    fireEvent.click(screen.getByText('Review feedback'));
    fireEvent.change(screen.getByRole('textbox', { name: 'Explanation' }), {
      target: { value: note },
    });
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    await waitFor(() => expect(saveAgentReviewOutcomeMock).toHaveBeenCalledTimes(1));
    expect(saveAgentReviewOutcomeMock.mock.calls[0][0]).toMatchObject({
      decisionState: 'DECIDED',
      agentReview: { action: 'KEEP_CURRENT', explanation: note },
    });
    expect(saveAgentReviewOutcomeMock.mock.calls[0][0]).not.toHaveProperty('target');
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^Decided$/ })).toHaveAttribute(
        'aria-pressed',
        'true',
      ),
    );
    expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue('Pay {price} now');
    firstVisit.unmount();

    // A new query client and component tree must recover the note from persisted feedback.
    const secondVisit = mountReview();
    fireEvent.click(screen.getByRole('button', { name: 'View report →' }));
    const history = within(screen.getByRole('region', { name: 'Feedback and agent responses' }));
    expect(await history.findByText(note)).toBeVisible();
    expect(history.getByText('Kept current translation')).toBeVisible();
    expect(fetchAgentReviewFeedbackMock).toHaveBeenCalledWith(7, 901, expect.any(AbortSignal));
    reopenAgentFindingMock.mockImplementationOnce(() => {
      liveProject = {
        ...project,
        reviewProjectTextUnits: [
          {
            ...savedRow,
            reviewStateRevision: 'agent-row-v3',
            reviewProjectTextUnitDecision: { decisionState: 'PENDING' },
            agentReview: {
              ...savedRow.agentReview!,
              proposalId: 902,
              proposalRevision: 2,
              proposalVersion: 0,
              previousProposalId: 901,
              disposition: 'ROUTED',
              canReviewAgain: false,
              lastFeedbackRequestId: null,
            },
          },
        ],
      };
      return Promise.resolve({ projectId: 7, proposalId: 902, proposalRevision: 2 });
    });
    fireEvent.click(screen.getByRole('button', { name: /^Pending$/ }));
    await waitFor(() => expect(reopenAgentFindingMock).toHaveBeenCalledTimes(1));
    expect(reopenAgentFindingMock.mock.calls[0]).toEqual([
      7,
      901,
      expect.objectContaining({ expectedProposalVersion: 3, expectedCurrentVariantId: 30 }),
    ]);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^Pending$/ })).toHaveAttribute(
        'aria-pressed',
        'true',
      ),
    );
    expect(screen.getByRole('tab', { name: 'Report' })).toHaveAttribute('aria-selected', 'true');
    fireEvent.click(screen.getByText('Review feedback'));
    const explanation = screen.getByRole('textbox', { name: 'Explanation' });
    expect(explanation).toBeVisible();
    expect(explanation).toBeEnabled();
    expect(explanation).toHaveValue('');
    expect(
      await within(screen.getByRole('region', { name: 'Feedback and agent responses' })).findByText(
        note,
      ),
    ).toBeVisible();
    fireEvent.change(explanation, { target: { value: `${note} Reconsidering the new context.` } });
    expect(explanation).toHaveValue(`${note} Reconsidering the new context.`);
    expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue('Pay {price} now');
    expect(saveAgentReviewOutcomeMock).toHaveBeenCalledTimes(1);
    expect(saveReviewProjectTextUnitDecisionMock).not.toHaveBeenCalled();
    expect(navigateMock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    await waitFor(() => expect(saveAgentReviewOutcomeMock).toHaveBeenCalledTimes(2));
    expect(saveAgentReviewOutcomeMock.mock.calls[1][0]).toMatchObject({
      agentReview: {
        proposalId: 902,
        action: 'KEEP_CURRENT',
        explanation: `${note} Reconsidering the new context.`,
      },
    });
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^Decided$/ })).toHaveAttribute(
        'aria-pressed',
        'true',
      ),
    );
    expect(
      secondVisit.queryClient.getQueryCache().findAll({ queryKey: ['review-project-draft'] }),
    ).toHaveLength(0);
    const unload = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(unload);
    expect(unload.defaultPrevented).toBe(false);
  });

  it('allows choosing the original and editing Report feedback but blocks changes during composition', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
    renderAgentReview({}, buildMutations({ onRequestDecisionState }));
    const editor = screen.getByRole('textbox', { name: 'Translation' });
    fireEvent.change(editor, { target: { value: 'Unfinished correction' } });
    fireEvent.click(screen.getByRole('button', { name: 'View report →' }));
    fireEvent.click(screen.getByText('Review feedback'));
    expect(screen.getByRole('textbox', { name: 'Explanation' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Use original translation' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: 'Use original translation' }));
    expect(editor).toHaveValue('Pay {price} now');
    expect(onRequestDecisionState).not.toHaveBeenCalled();
    expect(screen.getByRole('textbox', { name: 'Explanation' })).toBeEnabled();
    fireEvent.compositionStart(editor);
    expect(screen.getByRole('textbox', { name: 'Explanation' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Use suggestion' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Use suggestion' }));
    expect(editor).toHaveValue('Pay {price} now');
    expect(onRequestDecisionState).not.toHaveBeenCalled();
    fireEvent.compositionEnd(editor);
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Explanation' })).toBeEnabled());
  });

  it('blocks editing Report feedback and choosing report text during a save', () => {
    const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
    renderReviewProjectPageView({
      project: { ...project, reviewProjectTextUnits: [buildAgentTextUnit()] },
      selectedTextUnitQueryId: textUnit.tmTextUnit!.id,
      mutations: buildMutations({
        isSaving: true,
        activeTextUnitId: textUnit.id,
        onRequestDecisionState,
      }),
    });
    fireEvent.click(screen.getByRole('button', { name: 'View report →' }));
    fireEvent.click(screen.getByText('Review feedback'));
    expect(screen.getByRole('textbox', { name: 'Explanation' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Use suggestion' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Use suggestion' }));
    expect(onRequestDecisionState).not.toHaveBeenCalled();
  });

  it('uses the regular Pending and Decided controls while recording unchanged current without a translation write', () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
    renderAgentReview({}, buildMutations({ onRequestSaveDecision, onRequestDecisionState }));
    expect(screen.getByRole('button', { name: /^Pending$/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('button', { name: /^Decided$/ })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    fireEvent.click(screen.getByRole('button', { name: /^Decided$/ }));
    expect(onRequestDecisionState.mock.calls[0][0]).toMatchObject({
      expectedCurrentTmTextUnitVariantId: 30,
      expectedReviewStateRevision: 'agent-row-v1',
      decisionState: 'DECIDED',
      agentReview: { action: 'KEEP_CURRENT', proposalId: 901, proposalVersion: 2 },
    });
    expect(onRequestDecisionState.mock.calls[0][0]).not.toHaveProperty('target');
    expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue('Pay {price} now');
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
    expect(reopenAgentFindingMock).not.toHaveBeenCalled();
  });

  it('reopens a completed incident with Pending in the same project and reuses its request on retry', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
    const row = buildAgentTextUnit({ disposition: 'RESOLVED', canReviewAgain: true });
    row.reviewProjectTextUnitDecision = {
      decisionState: 'DECIDED',
      decisionTmTextUnitVariant: row.currentTmTextUnitVariant,
    };
    const queryClient = createQueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    const props: ReviewProjectPageViewProps = {
      projectId: 7,
      project: { ...project, reviewProjectTextUnits: [row] },
      mutations: buildMutations({ onRequestSaveDecision, onRequestDecisionState }),
      selectedTextUnitQueryId: null,
      onSelectedTextUnitIdChange: noop,
      openRequestDetailsQuery: false,
      requestDetailsSource: null,
      onRequestDetailsQueryHandled: noop,
      onRequestDetailsFlowFinished: noop,
    };
    const view = render(renderReviewProjectPageViewNode(props, queryClient));
    expect(screen.getByRole('button', { name: /^Decided$/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('button', { name: /^Pending$/ })).toBeEnabled();
    reopenAgentFindingMock.mockRejectedValueOnce(new Error('Please retry reopening.'));
    fireEvent.click(screen.getByRole('button', { name: /^Pending$/ }));
    await waitFor(() => expect(reopenAgentFindingMock).toHaveBeenCalledTimes(1));
    const request = reopenAgentFindingMock.mock.calls[0][2];
    expect(request.requestKey).toEqual(expect.any(String));
    expect(reopenAgentFindingMock.mock.calls[0]).toEqual([
      7,
      901,
      expect.objectContaining({
        expectedProposalVersion: 2,
        expectedCurrentVariantId: 30,
        expectedSource: 'Pay {price} now',
        expectedSourceComment: 'Checkout payment copy',
        expectedCurrentTarget: 'Pay {price} now',
        expectedCurrentStatus: 'REVIEW_NEEDED',
        expectedCurrentIncludedInLocalizedFile: true,
      }),
    ]);
    expect(await screen.findByRole('alert')).toHaveTextContent('Please retry reopening.');
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
    expect(onRequestDecisionState).not.toHaveBeenCalled();
    reopenAgentFindingMock.mockResolvedValueOnce({
      projectId: 7,
      proposalId: 902,
      proposalRevision: 2,
    });
    fireEvent.click(screen.getByRole('button', { name: /^Pending$/ }));
    await waitFor(() => expect(reopenAgentFindingMock).toHaveBeenCalledTimes(2));
    expect(reopenAgentFindingMock.mock.calls[1][2]).toEqual(request);
    await waitFor(() =>
      expect(invalidate).toHaveBeenCalledWith({
        queryKey: [...REVIEW_PROJECT_DETAIL_QUERY_KEY, 7],
        exact: true,
      }),
    );
    expect(navigateMock).not.toHaveBeenCalled();
    view.rerender(
      renderReviewProjectPageViewNode(
        {
          ...props,
          project: {
            ...project,
            reviewProjectTextUnits: [
              {
                ...row,
                reviewStateRevision: 'agent-row-v2',
                reviewProjectTextUnitDecision: { decisionState: 'PENDING' },
                agentReview: {
                  ...row.agentReview!,
                  proposalId: 902,
                  proposalRevision: 2,
                  proposalVersion: 0,
                  disposition: 'ROUTED',
                  canReviewAgain: false,
                },
              },
            ],
          },
        },
        queryClient,
      ),
    );
    expect(screen.getByRole('button', { name: /^Pending$/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('textbox', { name: 'Translation' })).toBeEnabled();
    expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue('Pay {price} now');
    expect(screen.getByRole('button', { name: /^Accept$/ })).toBeEnabled();
  });

  it('blocks incident decision controls while a translation draft or IME composition is active', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
    renderAgentReview({}, buildMutations({ onRequestDecisionState }));
    const editor = screen.getByRole('textbox', { name: 'Translation' });
    fireEvent.change(editor, { target: { value: 'Unfinished translation' } });
    expect(screen.getByRole('button', { name: /^Pending$/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^Decided$/ })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: /^Decided$/ }));
    expect(onRequestDecisionState).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /^Reset$/ }));
    fireEvent.compositionStart(editor);
    expect(screen.getByRole('button', { name: /^Decided$/ })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: /^Decided$/ }));
    expect(onRequestDecisionState).not.toHaveBeenCalled();
    fireEvent.compositionEnd(editor);
    await waitFor(() => expect(screen.getByRole('button', { name: /^Decided$/ })).toBeEnabled());
    expect(reopenAgentFindingMock).not.toHaveBeenCalled();
  });

  it('does not use Decided to bypass a negative original assessment', () => {
    const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
    renderAgentReview({}, buildMutations({ onRequestDecisionState }));
    fireEvent.click(screen.getByRole('tab', { name: 'Report' }));
    fireEvent.click(screen.getByText('Review feedback'));
    fireEvent.change(screen.getByLabelText('Original translation'), { target: { value: 'BAD' } });
    expect(screen.getByRole('button', { name: /^Decided$/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^Accept$/ })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    fireEvent.click(screen.getByRole('button', { name: /^Decided$/ }));
    expect(onRequestDecisionState).not.toHaveBeenCalled();
  });

  it('blocks accepting an incident draft with a confirmed MF2 validation error', async () => {
    mf2TranslationEditorHostMock.enabled = true;
    mf2TranslationEditorHostMock.errorCount = 1;
    const proposedTarget = '.input {$count :number}\n{{Você tem {$count} arquivos.}}';
    const row = buildMf2TextUnit(mf2Source);
    row.agentReview = buildAgentTextUnit({
      reviewedSource: row.tmTextUnit?.content ?? '',
      reviewedTarget: row.baselineTmTextUnitVariant?.content,
      proposedTarget,
    }).agentReview;
    renderReviewProjectPageView({ project: { ...project, reviewProjectTextUnits: [row] } });
    const editor = await screen.findByRole('textbox', { name: 'Target Message' });
    expect(editor).toBeVisible();
    fireEvent.click(await screen.findByRole('button', { name: 'Use suggestion' }));
    expect(editor).toHaveValue(proposedTarget);
    expect(await screen.findByText('Fix 1 MF2 error before accepting.')).toBeVisible();
    expect(screen.getByRole('button', { name: /^Accept$/ })).toBeDisabled();
  });

  it('keeps optional incident feedback intact when visiting the ordinary context and history tabs', async () => {
    renderAgentReview();
    expect(fetchAgentReviewFeedbackMock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('tab', { name: 'Report' }));
    fireEvent.click(screen.getByText('Review feedback'));
    fireEvent.change(screen.getByLabelText('Explanation'), {
      target: { value: 'Please check the product terminology.' },
    });
    const evidence = screen.getByRole('region', { name: 'Evidence and verification' });
    expect(evidence.closest('.review-project-detail__side')).not.toBeNull();
    await waitFor(() => expect(fetchAgentReviewFeedbackMock).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('tab', { name: 'History' }));
    await waitFor(() => expect(fetchTextUnitHistoryMock).toHaveBeenCalledWith(3, 'pt-PT'));
    expect(screen.queryByText('Review feedback')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: 'Context' }));
    expect(screen.queryByText('Review feedback')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: 'Report' }));
    fireEvent.click(screen.getByText('Review feedback'));
    expect(screen.getByLabelText('Explanation')).toHaveValue(
      'Please check the product terminology.',
    );
    expect(screen.getByRole('button', { name: /^Accept$/ })).toBeVisible();
  });

  it('focuses the current editor with E and accepts an explicit correction without a suggestion', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    renderAgentReview({ proposedTarget: null }, buildMutations({ onRequestSaveDecision }));
    const editor = screen.getByRole('textbox', { name: 'Translation' });
    expect(editor).toHaveValue('Pay {price} now');
    expect(screen.queryByRole('button', { name: 'Use suggestion' })).not.toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'e' });
    await waitFor(() => expect(editor).toHaveFocus());
    fireEvent.change(editor, { target: { value: 'Pague {price} agora' } });
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    expect(onRequestSaveDecision.mock.calls[0][0]).toMatchObject({
      target: 'Pague {price} agora',
      agentReview: { action: 'ACCEPT', proposalId: 901 },
    });
  });

  it('stages the persistent report suggestion without a write, then accepts the exact proposal and reviewed row', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
    renderAgentReview({}, buildMutations({ onRequestSaveDecision, onRequestDecisionState }));
    const editor = screen.getByRole('textbox', { name: 'Translation' });
    expect(editor).toHaveValue('Pay {price} now');
    expect(screen.getByRole('button', { name: 'View report →' })).toBeVisible();
    expect(screen.queryByRole('button', { name: /decision note/i })).not.toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: 'Use suggestion' }));
    expect(editor).toHaveValue('Pague {price} agora');
    expect(screen.getByRole('button', { name: 'View report →' })).toBeVisible();
    expect(screen.queryByRole('button', { name: /decision note/i })).not.toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Selected proposed correction' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Use original translation' })).toBeEnabled();
    expect(
      within(screen.getByRole('region', { name: 'Reported issue' })).getByText(
        'The target is still in English.',
      ),
    ).toBeVisible();
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
    expect(onRequestDecisionState).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    expect(onRequestSaveDecision.mock.calls[0][0]).toMatchObject({
      target: 'Pague {price} agora',
      status: 'APPROVED',
      includedInLocalizedFile: true,
      expectedCurrentTmTextUnitVariantId: 30,
      expectedReviewStateRevision: 'agent-row-v1',
      agentReview: { proposalId: 901, proposalRevision: 1, proposalVersion: 2, action: 'ACCEPT' },
    });
    expect(onRequestSaveDecision.mock.calls[0][0].agentReview?.requestId).toEqual(
      expect.any(String),
    );
    expect(onRequestSaveDecision.mock.calls[0][0].agentReview).toMatchObject({
      originalAssessment: undefined,
      suggestionAssessment: undefined,
    });
    expect(onRequestDecisionState).not.toHaveBeenCalled();
    expect(requestAiReviewMock).not.toHaveBeenCalled();
  });

  it.each([null, 'Pague {price} agora'])(
    'records unchanged current acceptance without a translation write (suggestion %s)',
    (proposedTarget) => {
      const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
      const onRequestDecisionState =
        vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
      renderAgentReview(
        { proposedTarget },
        buildMutations({ onRequestSaveDecision, onRequestDecisionState }),
      );
      fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
      expect(onRequestDecisionState.mock.calls[0][0]).toMatchObject({
        decisionState: 'DECIDED',
        expectedCurrentTmTextUnitVariantId: 30,
        expectedReviewStateRevision: 'agent-row-v1',
        agentReview: {
          proposalId: 901,
          proposalRevision: 1,
          proposalVersion: 2,
          action: 'KEEP_CURRENT',
        },
      });
      expect(onRequestDecisionState.mock.calls[0][0]).not.toHaveProperty('target');
      expect(onRequestSaveDecision).not.toHaveBeenCalled();
    },
  );

  it('shows absent current text instead of a historical baseline and keeps it without a translation write', () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
    const row = { ...buildAgentTextUnit({ reviewedTarget: null }), currentTmTextUnitVariant: null };
    renderReviewProjectPageView({
      project: { ...project, reviewProjectTextUnits: [row] },
      mutations: buildMutations({ onRequestSaveDecision, onRequestDecisionState }),
    });
    expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue('');
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    expect(onRequestDecisionState.mock.calls[0][0]).toMatchObject({
      expectedCurrentTmTextUnitVariantId: null,
      agentReview: { action: 'KEEP_CURRENT' },
    });
    expect(onRequestDecisionState.mock.calls[0][0]).not.toHaveProperty('target');
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
  });

  it('preserves a pending chat request and conversation when an equivalent incident snapshot is refetched', async () => {
    let finishReview!: (value: AiReviewResponse) => void;
    requestAiReviewMock.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishReview = resolve;
        }),
    );
    const row = buildAgentTextUnit();
    const queryClient = createQueryClient({ defaultOptions: { queries: { retry: false } } });
    const props: ReviewProjectPageViewProps = {
      projectId: 7,
      project: { ...project, reviewProjectTextUnits: [row] },
      mutations: buildMutations(),
      selectedTextUnitQueryId: null,
      onSelectedTextUnitIdChange: noop,
      openRequestDetailsQuery: false,
      requestDetailsSource: null,
      onRequestDetailsQueryHandled: noop,
      onRequestDetailsFlowFinished: noop,
    };
    const view = render(renderReviewProjectPageViewNode(props, queryClient));
    await screen.findByText('The target is still in English.');
    fireEvent.change(
      screen.getByPlaceholderText('Chat with AI: rephrase, adjust the tone, or ask a question…'),
      { target: { value: 'Explain the terminology.' } },
    );
    await waitFor(() => expect(screen.getByRole('button', { name: 'Ask' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
    await waitFor(() => expect(requestAiReviewMock).toHaveBeenCalledTimes(1));
    const request = requestAiReviewMock.mock.calls[0][0] as AiReviewRequest;
    const incidentContext = request.messages.find((message) =>
      message.content.includes('"context":"recorded_incident_review"'),
    );
    expect(incidentContext?.role).toBe('user');
    expect(JSON.parse(incidentContext!.content)).toMatchObject({
      proposalId: 901,
      reviewedSource: 'Pay {price} now',
      reviewedTarget: 'Pay {price} now',
      proposedTarget: 'Pague {price} agora',
      rationale: 'The target is still in English.',
    });
    const signal = (requestAiReviewMock.mock.calls[0][1] as { signal: AbortSignal }).signal;
    expect(signal.aborted).toBe(false);
    view.rerender(
      renderReviewProjectPageViewNode(
        {
          ...props,
          project: {
            ...project,
            reviewProjectTextUnits: [
              {
                ...row,
                agentReview: {
                  ...row.agentReview!,
                  evidence: row.agentReview!.evidence?.map((item) => ({ ...item })),
                },
              },
            ],
          },
        },
        queryClient,
      ),
    );
    expect(signal.aborted).toBe(false);
    expect(screen.getByText('Explain the terminology.')).toBeVisible();
    await act(async () => {
      finishReview({
        message: { role: 'assistant', content: 'This is the payment verb.' },
        suggestions: [],
      });
      await Promise.resolve();
    });
    expect(await screen.findByText('This is the payment verb.')).toBeVisible();
    expect(screen.getByText('The target is still in English.')).toBeVisible();
    expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
  });

  it('tracks the selected original or proposal through switching, manual edits, and Reset without saving', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
    renderAgentReview({}, buildMutations({ onRequestSaveDecision, onRequestDecisionState }));
    const editor = screen.getByRole('textbox', { name: 'Translation' });
    expect(screen.getByRole('status', { name: 'Selected original translation' })).toBeVisible();
    expect(
      screen.queryByRole('button', { name: /Selected original|Use original/ }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Use suggestion' })).toBeEnabled();
    fireEvent.click(await screen.findByRole('button', { name: 'Use suggestion' }));
    expect(editor).toHaveValue('Pague {price} agora');
    expect(screen.getByRole('status', { name: 'Selected proposed correction' })).toBeVisible();
    expect(
      screen.queryByRole('button', { name: /Selected proposed|Use suggestion/ }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Use original translation' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: 'Use original translation' }));
    expect(editor).toHaveValue('Pay {price} now');
    expect(screen.getByRole('status', { name: 'Selected original translation' })).toBeVisible();
    fireEvent.change(editor, { target: { value: 'A different translation' } });
    expect(screen.getByRole('button', { name: 'Use original translation' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Use suggestion' })).toBeEnabled();
    expect(screen.queryByRole('status', { name: /^Selected/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Use original translation' })).toHaveTextContent(
      /^Use original$/,
    );
    expect(screen.getByRole('button', { name: 'Use suggestion' })).toHaveTextContent(
      /^Use suggestion$/,
    );
    expect(
      within(screen.getByRole('region', { name: 'Reported issue' })).queryByText(/^Use$/),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /^Reset$/ }));
    expect(editor).toHaveValue('Pay {price} now');
    expect(screen.getByRole('status', { name: 'Selected original translation' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Use suggestion' })).toBeEnabled();
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
    expect(onRequestDecisionState).not.toHaveBeenCalled();
  });

  it('does not navigate away after the reviewer leaves an in-flight Pending request', async () => {
    type ReopenResult = Awaited<ReturnType<typeof AgentReviewsApi.reopenAgentFinding>>;
    let complete!: (value: ReopenResult) => void;
    const pending = new Promise<ReopenResult>((resolve) => {
      complete = resolve;
    });
    reopenAgentFindingMock.mockReturnValue(pending);
    const view = renderAgentReview({ disposition: 'RESOLVED', canReviewAgain: true });
    fireEvent.click(screen.getByRole('button', { name: /^Pending$/ }));
    expect(reopenAgentFindingMock).toHaveBeenCalledTimes(1);
    const unload = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(unload);
    expect(unload.defaultPrevented).toBe(true);
    view.unmount();
    await act(async () => {
      complete({ projectId: 7, proposalId: 902, proposalRevision: 2 });
      await pending;
    });
    expect(navigateMock).not.toHaveBeenCalled();
  });

  it('keeps a superseded finding read-only while still showing the saved current translation', () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const acceptedRow = buildAgentTextUnit({
      disposition: 'SUPERSEDED',
      stale: false,
      canReviewAgain: false,
    });
    acceptedRow.currentTmTextUnitVariant = {
      ...acceptedRow.currentTmTextUnitVariant!,
      content: 'Pague {price} agora',
    };
    renderReviewProjectPageView({ project: { ...project, reviewProjectTextUnits: [acceptedRow] } });
    expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue('Pague {price} agora');
    expect(screen.getByRole('textbox', { name: 'Translation' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^Accept$/ })).toBeDisabled();
    expect(screen.queryByRole('region', { name: 'Review finding' })).not.toBeInTheDocument();
  });

  it.each(['RESOLVED', 'FOLLOW_UP'] as const)(
    'lets a linguist edit an eligible %s review and saves a new review round atomically',
    (disposition) => {
      visibleTextEditorEnabledMock.mockReturnValue(false);
      const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
      const onRequestDecisionState =
        vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
      renderAgentReview(
        { disposition, canReviewAgain: true, canReconsider: false },
        buildMutations({ onRequestSaveDecision, onRequestDecisionState }),
      );
      const editor = screen.getByRole('textbox', { name: 'Translation' });
      expect(editor).toBeEnabled();
      expect(editor).toHaveValue('Pay {price} now');
      expect(screen.getByRole('button', { name: /^Accept$/ })).toBeDisabled();
      fireEvent.change(editor, { target: { value: 'Pague {price} imediatamente' } });
      expect(screen.getByRole('button', { name: /^Accept$/ })).toBeEnabled();
      fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
      expect(onRequestSaveDecision.mock.calls[0][0]).toMatchObject({
        target: 'Pague {price} imediatamente',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        expectedCurrentTmTextUnitVariantId: 30,
        expectedReviewStateRevision: 'agent-row-v1',
        agentReview: { action: 'ACCEPT', proposalId: 901, proposalRevision: 1, proposalVersion: 2 },
        reopenAgentReview: {
          expectedProposalVersion: 2,
          expectedCurrentVariantId: 30,
          expectedSource: 'Pay {price} now',
          expectedSourceComment: 'Checkout payment copy',
          expectedCurrentTarget: 'Pay {price} now',
          expectedCurrentStatus: 'REVIEW_NEEDED',
          expectedCurrentIncludedInLocalizedFile: true,
        },
      });
      expect(onRequestSaveDecision.mock.calls[0][0].reopenAgentReview?.requestKey).toEqual(
        expect.any(String),
      );
      expect(onRequestDecisionState).not.toHaveBeenCalled();
      expect(reopenAgentFindingMock).not.toHaveBeenCalled();
    },
  );

  it('uses a suggestion on a completed review without a separate Pending action or write', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    renderAgentReview(
      { disposition: 'RESOLVED', canReviewAgain: true },
      buildMutations({ onRequestSaveDecision }),
    );
    const editor = screen.getByRole('textbox', { name: 'Translation' });
    fireEvent.click(await screen.findByRole('button', { name: 'Use suggestion' }));
    expect(editor).toHaveValue('Pague {price} agora');
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
    expect(reopenAgentFindingMock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    expect(onRequestSaveDecision.mock.calls[0][0]).toMatchObject({
      target: 'Pague {price} agora',
      reopenAgentReview: { expectedProposalVersion: 2 },
      agentReview: { action: 'ACCEPT' },
    });
  });

  it('adopts the new current value after an atomic completed-review edit without retaining a phantom draft', () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const row = buildAgentTextUnit({ disposition: 'RESOLVED', canReviewAgain: true });
    row.reviewProjectTextUnitDecision = {
      decisionState: 'DECIDED',
      decisionTmTextUnitVariant: row.currentTmTextUnitVariant,
    };
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>(
      () => 41,
    );
    const queryClient = createQueryClient({ defaultOptions: { queries: { retry: false } } });
    const props: ReviewProjectPageViewProps = {
      projectId: 7,
      project: { ...project, reviewProjectTextUnits: [row] },
      mutations: buildMutations({ onRequestSaveDecision }),
      selectedTextUnitQueryId: null,
      onSelectedTextUnitIdChange: noop,
      openRequestDetailsQuery: false,
      requestDetailsSource: null,
      onRequestDetailsQueryHandled: noop,
      onRequestDetailsFlowFinished: noop,
    };
    const view = render(renderReviewProjectPageViewNode(props, queryClient));
    fireEvent.change(screen.getByRole('textbox', { name: 'Translation' }), {
      target: { value: 'Pague {price} imediatamente' },
    });
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    const request = onRequestSaveDecision.mock.calls[0][0];
    const variant = {
      ...row.currentTmTextUnitVariant!,
      id: 31,
      content: 'Pague {price} imediatamente',
      status: 'APPROVED',
    };
    const savedRow: ApiReviewProjectTextUnit = {
      ...row,
      reviewStateRevision: 'agent-row-v2',
      currentTmTextUnitVariant: variant,
      reviewProjectTextUnitDecision: {
        decisionState: 'DECIDED',
        decisionTmTextUnitVariant: variant,
      },
      agentReview: {
        ...row.agentReview!,
        proposalId: 902,
        previousProposalId: 901,
        proposalRevision: 2,
        proposalVersion: 1,
        lastFeedbackRequestId: request.agentReview!.requestId,
      },
    };
    const action = { kind: 'save-decision' as const, request };
    view.rerender(
      renderReviewProjectPageViewNode(
        {
          ...props,
          project: { ...project, reviewProjectTextUnits: [savedRow] },
          mutations: buildMutations({
            actionState: {
              phase: 'succeeded',
              operationId: 41,
              attemptId: 41,
              action,
              originalAction: action,
              textUnit: savedRow,
              resolution: 'saved',
            },
          }),
        },
        queryClient,
      ),
    );
    expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue(
      'Pague {price} imediatamente',
    );
    expect(screen.getByRole('textbox', { name: 'Translation' })).toBeEnabled();
    expect(screen.getByRole('button', { name: /^Reset$/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^Accept$/ })).toBeDisabled();
    expect(screen.queryByRole('dialog', { name: 'Discard changes?' })).not.toBeInTheDocument();
    expect(
      queryClient.getQueryCache().findAll({ queryKey: ['review-project-draft'] }),
    ).toHaveLength(0);
    const unload = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(unload);
    expect(unload.defaultPrevented).toBe(false);
  });

  it('blocks stale suggestion use and edited acceptance including shortcuts', async () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
    renderAgentReview(
      { stale: true, reviewedTarget: 'Earlier translation' },
      buildMutations({ onRequestSaveDecision, onRequestDecisionState }),
    );
    expect(await screen.findByRole('button', { name: 'Use suggestion' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Use original translation' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Use original translation' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Translation' }), {
      target: { value: 'My correction' },
    });
    expect(screen.getByRole('button', { name: /^Accept$/ })).toBeDisabled();
    fireEvent.keyDown(window, { key: 'a' });
    fireEvent.keyDown(window, { key: 'Enter', ctrlKey: true, shiftKey: true });
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
    expect(onRequestDecisionState).not.toHaveBeenCalled();
  });

  it('accepts the latest unchanged translation with feedback on a stale request without changing its current translation', () => {
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
    renderAgentReview(
      { stale: true, reviewedTarget: 'Earlier translation' },
      buildMutations({ onRequestSaveDecision, onRequestDecisionState }),
    );
    fireEvent.click(screen.getByRole('tab', { name: 'Report' }));
    fireEvent.click(screen.getByText('Review feedback'));
    fireEvent.change(screen.getByLabelText('Suggested translation'), {
      target: { value: 'INCORRECT' },
    });
    fireEvent.change(screen.getByLabelText('Explanation'), {
      target: { value: 'The latest product terminology changed.' },
    });
    fireEvent.click(screen.getByRole('tab', { name: 'Report' }));
    expect(screen.queryByText('More actions')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Ask for another proposal' }),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Defer' })).not.toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Explanation' })).toHaveValue(
      'The latest product terminology changed.',
    );
    expect(onRequestDecisionState).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    expect(onRequestDecisionState.mock.calls[0][0]).toMatchObject({
      decisionState: 'DECIDED',
      expectedCurrentTmTextUnitVariantId: 30,
      expectedReviewStateRevision: 'agent-row-v1',
      agentReview: {
        action: 'KEEP_CURRENT',
        proposalId: 901,
        proposalRevision: 1,
        proposalVersion: 2,
        suggestionAssessment: 'INCORRECT',
        explanation: 'The latest product terminology changed.',
      },
    });
    expect(onRequestDecisionState.mock.calls[0][0].agentReview?.requestId).toEqual(
      expect.any(String),
    );
    expect(onRequestDecisionState.mock.calls[0][0]).not.toHaveProperty('target');
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
  });

  it('preserves feedback when resetting translation edits and clears it with a later feedback-only reset', () => {
    visibleTextEditorEnabledMock.mockReturnValue(false);
    renderAgentReview();
    const editor = screen.getByRole('textbox', { name: 'Translation' });
    fireEvent.change(editor, { target: { value: 'Pague {price} imediatamente' } });
    fireEvent.click(screen.getByRole('tab', { name: 'Report' }));
    fireEvent.click(screen.getByText('Review feedback'));
    fireEvent.change(screen.getByLabelText('Explanation'), {
      target: { value: 'Need product context.' },
    });
    fireEvent.click(screen.getByRole('button', { name: /^Reset$/ }));
    expect(editor).toHaveValue('Pay {price} now');
    expect(screen.getByLabelText('Explanation')).toHaveValue('Need product context.');
    fireEvent.click(screen.getByRole('button', { name: /^Reset$/ }));
    expect(screen.getByLabelText('Explanation')).toHaveValue('');
    expect(screen.getByRole('button', { name: /^Reset$/ })).toBeDisabled();
  });

  it('allows an explicit acceptance of the latest unchanged current text on a stale finding', () => {
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
    renderAgentReview(
      { stale: true, reviewedTarget: 'Earlier translation' },
      buildMutations({ onRequestSaveDecision, onRequestDecisionState }),
    );
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    expect(onRequestDecisionState.mock.calls[0][0]).toMatchObject({
      expectedCurrentTmTextUnitVariantId: 30,
      agentReview: { action: 'KEEP_CURRENT' },
    });
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
  });

  it('blocks proposal use when its source no longer matches the current string', async () => {
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    renderAgentReview(
      { reviewedSource: 'Earlier checkout copy' },
      buildMutations({ onRequestSaveDecision }),
    );
    expect(await screen.findByRole('button', { name: 'Use suggestion' })).toBeDisabled();
    expect(screen.getByRole('status', { name: 'Selected original translation' })).toBeVisible();
    expect(
      screen.queryByRole('button', { name: 'Use original translation' }),
    ).not.toBeInTheDocument();
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
  });

  it('records separate original and suggestion judgments with unchanged acceptance without saving text', () => {
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
    renderAgentReview({}, buildMutations({ onRequestSaveDecision, onRequestDecisionState }));
    fireEvent.click(screen.getByRole('tab', { name: 'Report' }));
    fireEvent.click(screen.getByText('Review feedback'));
    fireEvent.change(screen.getByLabelText('Original translation'), { target: { value: 'GOOD' } });
    fireEvent.change(screen.getByLabelText('Suggested translation'), {
      target: { value: 'UNNECESSARY' },
    });
    fireEvent.change(screen.getByLabelText('Explanation'), {
      target: { value: 'This phrase matches our approved glossary.' },
    });
    fireEvent.keyDown(screen.getByLabelText('Explanation'), { key: 'Enter', ctrlKey: true });
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
    expect(onRequestDecisionState).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    expect(onRequestDecisionState.mock.calls[0][0]).toMatchObject({
      agentReview: {
        action: 'KEEP_CURRENT',
        originalAssessment: 'GOOD',
        suggestionAssessment: 'UNNECESSARY',
        explanation: 'This phrase matches our approved glossary.',
      },
    });
    expect(onRequestSaveDecision).not.toHaveBeenCalled();
  });

  it('requires clearing a negative current assessment before keeping the unchanged translation', () => {
    const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
    renderAgentReview({}, buildMutations({ onRequestDecisionState }));
    fireEvent.click(screen.getByRole('tab', { name: 'Report' }));
    fireEvent.click(screen.getByText('Review feedback'));
    fireEvent.change(screen.getByLabelText('Original translation'), { target: { value: 'BAD' } });
    expect(screen.getByRole('button', { name: /^Accept$/ })).toBeDisabled();
    fireEvent.keyDown(window, { key: 'a' });
    expect(onRequestDecisionState).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Original translation'), { target: { value: '' } });
    expect(screen.getByRole('button', { name: /^Accept$/ })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    expect(onRequestDecisionState.mock.calls[0][0].agentReview).toMatchObject({
      action: 'KEEP_CURRENT',
      originalAssessment: undefined,
    });
  });

  it('preserves negative current feedback on correction acceptance without inventing a positive rating', async () => {
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    renderAgentReview({}, buildMutations({ onRequestSaveDecision }));
    fireEvent.click(screen.getByRole('tab', { name: 'Report' }));
    fireEvent.click(screen.getByText('Review feedback'));
    fireEvent.change(screen.getByLabelText('Original translation'), { target: { value: 'BAD' } });
    fireEvent.click(await screen.findByRole('button', { name: 'Use suggestion' }));
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    expect(onRequestSaveDecision.mock.calls[0][0].agentReview).toMatchObject({
      action: 'ACCEPT',
      originalAssessment: 'BAD',
      suggestionAssessment: undefined,
    });
  });

  it.each(['ROUTED', 'RESOLVED'])(
    'does not accept metadata-only edits to a reported BAD original in %s review',
    (disposition) => {
      const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
      const onRequestDecisionState =
        vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
      const row = buildAgentTextUnit();
      const { view, props, queryClient } = renderReviewWithRetainedNotes(
        row,
        {
          comment: 'This is still the reported original.',
          decisionNotes: 'A correction is needed.',
        },
        buildMutations({ onRequestSaveDecision, onRequestDecisionState }),
      );
      fireEvent.click(screen.getByRole('tab', { name: 'Report' }));
      fireEvent.click(screen.getByText('Review feedback'));
      fireEvent.change(screen.getByLabelText('Original translation'), { target: { value: 'BAD' } });
      if (disposition === 'RESOLVED') {
        view.rerender(
          renderReviewProjectPageViewNode(
            {
              ...props,
              project: {
                ...project,
                reviewProjectTextUnits: [
                  {
                    ...row,
                    agentReview: { ...row.agentReview!, disposition, canReviewAgain: true },
                    reviewProjectTextUnitDecision: {
                      decisionState: 'DECIDED',
                      decisionTmTextUnitVariant: row.currentTmTextUnitVariant,
                    },
                  },
                ],
              },
            },
            queryClient,
          ),
        );
      }
      fireEvent.click(screen.getByText('Unsent notes'));
      expect(
        screen.getByPlaceholderText('Explain why you chose this translation (if not obvious).'),
      ).toHaveValue('This is still the reported original.');
      expect(
        screen.getByPlaceholderText(
          'Explain why the baseline translation was bad (to improve AI translation).',
        ),
      ).toHaveValue('A correction is needed.');
      expect(screen.getByRole('button', { name: /^Accept$/ })).toBeDisabled();
      fireEvent.keyDown(window, { key: 'Enter', ctrlKey: true });
      fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
      expect(onRequestSaveDecision).not.toHaveBeenCalled();
      expect(onRequestDecisionState).not.toHaveBeenCalled();

      if (disposition === 'ROUTED') {
        fireEvent.change(screen.getByLabelText('Original translation'), {
          target: { value: 'GOOD' },
        });
        expect(screen.getByRole('button', { name: /^Accept$/ })).toBeEnabled();
        fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
        expect(onRequestSaveDecision.mock.calls[0][0]).toMatchObject({
          target: 'Pay {price} now',
          comment: 'This is still the reported original.',
          decisionNotes: 'A correction is needed.',
          agentReview: { action: 'ACCEPT', originalAssessment: 'GOOD' },
        });
      }
    },
  );

  it('allows metadata edits on a completed correction even when the frozen original was rated BAD', () => {
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    const row = buildAgentTextUnit({ disposition: 'RESOLVED', canReviewAgain: true });
    const correction = { ...row.currentTmTextUnitVariant!, content: 'Pague {price} agora' };
    row.currentTmTextUnitVariant = correction;
    row.reviewProjectTextUnitDecision = {
      decisionState: 'DECIDED',
      decisionTmTextUnitVariant: correction,
    };
    renderReviewWithRetainedNotes(
      row,
      { comment: 'Earlier note about the correction.' },
      buildMutations({ onRequestSaveDecision }),
      'BAD',
    );
    fireEvent.click(screen.getByText('Unsent notes'));
    expect(screen.queryByText('Decision notes')).not.toBeInTheDocument();
    fireEvent.change(
      screen.getByPlaceholderText('Explain why you chose this translation (if not obvious).'),
      { target: { value: 'The saved correction matches the glossary.' } },
    );
    expect(screen.getByRole('button', { name: /^Accept$/ })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    expect(onRequestSaveDecision.mock.calls[0][0]).toMatchObject({
      target: 'Pague {price} agora',
      comment: 'The saved correction matches the glossary.',
      agentReview: { action: 'ACCEPT' },
      reopenAgentReview: { expectedProposalVersion: 2 },
    });
  });

  it('skips closed legacy notes on Tab and visits the fields once their disclosure is open', () => {
    renderReviewWithRetainedNotes(buildAgentTextUnit(), {
      comment: 'Retained comment',
      decisionNotes: 'Retained decision note',
    });
    const editor = screen.getByRole('textbox', { name: 'Translation' });
    const comment = screen.getByPlaceholderText(
      'Explain why you chose this translation (if not obvious).',
    );
    const decisionNotes = screen.getByPlaceholderText(
      'Explain why the baseline translation was bad (to improve AI translation).',
    );
    editor.focus();
    const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
    fireEvent(editor, tab);
    expect(tab.defaultPrevented).toBe(false);
    fireEvent.click(screen.getByText('Unsent notes'));
    fireEvent.keyDown(editor, { key: 'Tab' });
    expect(comment).toHaveFocus();
    fireEvent.keyDown(comment, { key: 'Tab' });
    expect(decisionNotes).toHaveFocus();
  });

  it.each(['read-only', 'saving'])(
    'leaves native Tab navigation available in a %s incident MF2 editor',
    (state) => {
      mf2TranslationEditorHostMock.enabled = true;
      const row = buildMf2TextUnit(mf2Source);
      row.agentReview = buildAgentTextUnit({
        reviewedSource: mf2Source,
        reviewedTarget: mf2Source,
        proposedTarget: null,
        disposition: state === 'read-only' ? 'SUPERSEDED' : 'ROUTED',
        canReviewAgain: false,
      }).agentReview;
      renderReviewProjectPageView({
        project: { ...project, reviewProjectTextUnits: [row] },
        selectedTextUnitQueryId: row.tmTextUnit!.id,
        mutations: buildMutations({
          isSaving: state === 'saving',
          activeTextUnitId: state === 'saving' ? row.id : null,
        }),
      });
      expect(
        screen.queryByPlaceholderText('Explain why you chose this translation (if not obvious).'),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByPlaceholderText(
          'Explain why the baseline translation was bad (to improve AI translation).',
        ),
      ).not.toBeInTheDocument();
      const editor = screen.getByRole('textbox', { name: 'Target Message' });
      editor.focus();
      for (const shiftKey of [false, true]) {
        const tab = new KeyboardEvent('keydown', {
          key: 'Tab',
          shiftKey,
          bubbles: true,
          cancelable: true,
        });
        fireEvent(editor, tab);
        expect(tab.defaultPrevented).toBe(false);
      }
    },
  );

  it.each(['ROUTED', 'RESOLVED'])(
    'recovers and saves legacy notes with normal Accept for %s incident review without changing text',
    (disposition) => {
      visibleTextEditorEnabledMock.mockReturnValue(false);
      const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
      const onRequestDecisionState =
        vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
      const row = buildAgentTextUnit({
        disposition,
        canReviewAgain: disposition === 'RESOLVED',
      });
      row.reviewProjectTextUnitDecision = {
        decisionState: disposition === 'RESOLVED' ? 'DECIDED' : 'PENDING',
        decisionTmTextUnitVariant: disposition === 'RESOLVED' ? row.currentTmTextUnitVariant : null,
      };
      renderReviewWithRetainedNotes(
        row,
        { comment: 'Earlier unsent comment.', decisionNotes: 'Earlier unsent decision note.' },
        buildMutations({ onRequestSaveDecision, onRequestDecisionState }),
      );
      const comment = screen.getByPlaceholderText(
        'Explain why you chose this translation (if not obvious).',
      );
      const decisionNotes = screen.getByPlaceholderText(
        'Explain why the baseline translation was bad (to improve AI translation).',
      );
      expect(comment).not.toBeVisible();
      expect(decisionNotes).not.toBeVisible();
      fireEvent.click(screen.getByText('Unsent notes'));
      expect(comment).toHaveValue('Earlier unsent comment.');
      expect(decisionNotes).toHaveValue('Earlier unsent decision note.');
      expect(comment).toBeVisible();
      expect(comment).toBeEnabled();
      expect(decisionNotes).toBeVisible();
      expect(decisionNotes).toBeEnabled();
      fireEvent.change(comment, { target: { value: 'Use the approved checkout wording.' } });
      fireEvent.change(decisionNotes, { target: { value: 'The original remained in English.' } });
      expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue('Pay {price} now');
      expect(onRequestSaveDecision).not.toHaveBeenCalled();
      expect(screen.getByRole('button', { name: /^Accept$/ })).toBeEnabled();
      fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
      expect(onRequestSaveDecision).toHaveBeenCalledOnce();
      expect(onRequestSaveDecision.mock.calls[0][0]).toMatchObject({
        target: 'Pay {price} now',
        comment: 'Use the approved checkout wording.',
        decisionNotes: 'The original remained in English.',
        decisionState: 'DECIDED',
        agentReview: { action: 'ACCEPT', proposalId: 901 },
      });
      if (disposition === 'RESOLVED') {
        expect(onRequestSaveDecision.mock.calls[0][0].reopenAgentReview).toMatchObject({
          expectedProposalVersion: 2,
          expectedCurrentVariantId: 30,
        });
      }
      expect(onRequestDecisionState).not.toHaveBeenCalled();
    },
  );

  it('recovers ordinary AI notes and shows the shared decision note only in the widget after editing', async () => {
    fetchReviewFeedbackBaselineMock.mockResolvedValue({
      target: 'Pay {price} now',
      ai: true,
      kind: 'AI_TRANSLATE',
    });
    const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
    renderReviewWithRetainedNotes(
      textUnit,
      { comment: 'Retained comment.', decisionNotes: 'Retained AI decision note.' },
      buildMutations({ onRequestSaveDecision }),
    );
    await waitFor(() => expect(screen.getByText('Unsent notes')).toBeVisible());
    expect(
      screen.queryByRole('region', { name: 'AI translation feedback' }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('Unsent notes'));
    expect(
      screen.getByPlaceholderText('Explain why you chose this translation (if not obvious).'),
    ).toHaveValue('Retained comment.');
    expect(
      screen.getByPlaceholderText(
        'Explain why the baseline translation was bad (to improve AI translation).',
      ),
    ).toHaveValue('Retained AI decision note.');
    fireEvent.change(screen.getByRole('textbox', { name: 'Translation' }), {
      target: { value: 'Pague {price} agora' },
    });
    expect(screen.getByRole('textbox', { name: 'AI feedback note' })).toHaveValue(
      'Retained AI decision note.',
    );
    expect(screen.queryByText('Decision notes')).not.toBeInTheDocument();
    expect(
      screen.getByPlaceholderText('Explain why you chose this translation (if not obvious).'),
    ).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
    expect(onRequestSaveDecision.mock.calls[0][0]).toMatchObject({
      target: 'Pague {price} agora',
      comment: 'Retained comment.',
      decisionNotes: 'Retained AI decision note.',
      reviewFeedback: { note: 'Retained AI decision note.' },
    });
  });

  it('does not allow evidence to create executable links or silently overwrite a conflict', () => {
    const current = buildAgentTextUnit({ stale: true });
    renderAgentReview(
      {
        evidence: [
          { label: 'Checkout screenshot', url: 'https://example.com/screenshot' },
          {
            label: 'Saved artifact',
            url: `/api/agent-reviews/projects/7/proposals/11/artifacts/${'a'.repeat(64)}`,
          },
          { label: 'Unsafe link stays text', url: 'javascript:alert(1)' },
          { label: 'Unrelated API stays text', url: '/api/users/me' },
        ],
      },
      buildMutations({ activeTextUnitId: current.id, conflictTextUnit: current }),
    );
    expect(screen.queryByRole('button', { name: 'Use mine' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Use external' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: 'Report' }));
    expect(screen.getByRole('link', { name: 'Checkout screenshot' })).toHaveAttribute(
      'href',
      'https://example.com/screenshot',
    );
    expect(screen.queryByRole('link', { name: 'Unsafe link stays text' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Saved artifact' })).toHaveAttribute(
      'href',
      `/api/agent-reviews/projects/7/proposals/11/artifacts/${'a'.repeat(64)}`,
    );
    expect(
      screen.queryByRole('link', { name: 'Unrelated API stays text' }),
    ).not.toBeInTheDocument();
  });
});

it('adopts unchanged current text after keep-current feedback without leaving a phantom proposal draft', () => {
  visibleTextEditorEnabledMock.mockReturnValue(false);
  const initialRow = buildAgentTextUnit();
  const queryClient = createQueryClient({ defaultOptions: { queries: { retry: false } } });
  const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>(
    () => 40,
  );
  const initialProps: ReviewProjectPageViewProps = {
    projectId: 7,
    project: { ...project, reviewProjectTextUnits: [initialRow] },
    mutations: buildMutations({ onRequestDecisionState }),
    selectedTextUnitQueryId: null,
    onSelectedTextUnitIdChange: noop,
    openRequestDetailsQuery: false,
    requestDetailsSource: null,
    onRequestDetailsQueryHandled: noop,
    onRequestDetailsFlowFinished: noop,
  };
  const view = render(renderReviewProjectPageViewNode(initialProps, queryClient));
  fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
  const request = onRequestDecisionState.mock.calls[0][0];
  const savedRow: ApiReviewProjectTextUnit = {
    ...initialRow,
    reviewStateRevision: 'agent-row-v2',
    reviewProjectTextUnitDecision: {
      decisionState: 'DECIDED',
      decisionTmTextUnitVariant: initialRow.currentTmTextUnitVariant,
    },
    agentReview: {
      ...initialRow.agentReview!,
      proposalVersion: 3,
      disposition: 'RESOLVED',
      lastFeedbackRequestId: request.agentReview!.requestId,
    },
  };
  const action = { kind: 'decision-state' as const, request };
  view.rerender(
    renderReviewProjectPageViewNode(
      {
        ...initialProps,
        project: { ...project, reviewProjectTextUnits: [savedRow] },
        mutations: buildMutations({
          actionState: {
            phase: 'succeeded',
            operationId: 40,
            attemptId: 40,
            action,
            originalAction: action,
            textUnit: savedRow,
            resolution: 'saved',
          },
        }),
      },
      queryClient,
    ),
  );
  expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveValue('Pay {price} now');
  expect(screen.getByRole('button', { name: /^Reset$/ })).toBeDisabled();
  expect(screen.queryByRole('dialog', { name: 'Discard changes?' })).not.toBeInTheDocument();
});

it('requires an agent response and explicit reconsideration before another human decision', async () => {
  fetchAgentReviewFeedbackMock.mockResolvedValue([
    {
      id: 1,
      proposalId: 901,
      proposalRevision: 1,
      actorType: 'AGENT',
      actorIdentity: 'Verifier',
      createdDate: '2026-09-10T05:00:00Z',
      action: 'CHALLENGE',
      explanation: 'This glossary entry only applies to the noun form.',
      evidenceJson: JSON.stringify([
        { label: 'Glossary usage', url: 'https://example.com/glossary' },
        { label: 'Unsafe response link', url: 'javascript:alert(1)' },
      ]),
    },
  ]);
  const onRequestSaveDecision = vi.fn<ReviewProjectMutationControls['onRequestSaveDecision']>();
  const onRequestDecisionState = vi.fn<ReviewProjectMutationControls['onRequestDecisionState']>();
  renderAgentReview(
    { disposition: 'FOLLOW_UP', canReconsider: true },
    buildMutations({ onRequestSaveDecision, onRequestDecisionState }),
  );
  expect(screen.getByRole('button', { name: /^Accept$/ })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Use suggestion' })).toBeDisabled();
  fireEvent.click(screen.getByRole('tab', { name: 'Report' }));
  expect(
    await screen.findByText('This glossary entry only applies to the noun form.'),
  ).toBeInTheDocument();
  fireEvent.click(screen.getByText('Response evidence'));
  expect(screen.getByRole('link', { name: 'Glossary usage' })).toHaveAttribute(
    'href',
    'https://example.com/glossary',
  );
  expect(screen.queryByRole('link', { name: 'Unsafe response link' })).not.toBeInTheDocument();
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Reconsider finding' })).toBeEnabled(),
  );
  fireEvent.click(screen.getByRole('button', { name: 'Reconsider finding' }));
  expect(onRequestSaveDecision).not.toHaveBeenCalled();
  expect(onRequestDecisionState).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: 'Use suggestion' }));
  expect(screen.getByRole('button', { name: /^Accept$/ })).toBeEnabled();
  fireEvent.click(screen.getByRole('button', { name: /^Accept$/ }));
  expect(onRequestSaveDecision.mock.calls[0][0]).toMatchObject({
    agentReview: { proposalId: 901, proposalVersion: 2, action: 'ACCEPT' },
  });
});

it('keeps follow-up rows read-only until the agent has answered the human round', () => {
  visibleTextEditorEnabledMock.mockReturnValue(false);
  renderAgentReview({ disposition: 'FOLLOW_UP', canReconsider: false });
  expect(screen.queryByRole('button', { name: 'Reconsider finding' })).not.toBeInTheDocument();
  expect(screen.getByRole('textbox', { name: 'Translation' })).toBeDisabled();
  expect(screen.getByRole('button', { name: /^Accept$/ })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Use suggestion' })).toBeDisabled();
});
