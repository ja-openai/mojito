import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  MemoryRouter,
  type NavigateFunction,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as GlossariesApi from '../../api/glossaries';
import type * as ReviewProjectsApi from '../../api/review-projects';
import type { ApiReviewProjectTextUnit } from '../../api/review-projects';
import type { ApiUserProfile } from '../../api/users';
import { REVIEW_PROJECT_DETAIL_QUERY_KEY } from '../../hooks/useReviewProjectDetail';
import { UserContext } from '../../hooks/useUser';
import type * as IntegrityCheck from '../../utils/integrityCheck';
import { buildCarryoverProject, carryoverFixtures } from './review-project-carryover.fixtures';
import { ReviewProjectPage } from './ReviewProjectPage';

const saveMock = vi.hoisted(() =>
  vi.fn<typeof ReviewProjectsApi.saveReviewProjectTextUnitDecision>(),
);
const decisionStateMock = vi.hoisted(() =>
  vi.fn<typeof ReviewProjectsApi.setReviewProjectTextUnitDecisionState>(),
);
const fetchProjectMock = vi.hoisted(() =>
  vi.fn<typeof ReviewProjectsApi.fetchReviewProjectDetail>(),
);
const integrityMock = vi.hoisted(() =>
  vi.fn<typeof IntegrityCheck.checkTextUnitIntegrityWithRetry>(),
);
const assistedMock = vi.hoisted(() => vi.fn(() => false));
const fetchAiMock = vi.hoisted(() => vi.fn());
const requestAiMock = vi.hoisted(() => vi.fn());

vi.mock('../../api/review-projects', async (importActual) => ({
  ...(await importActual<typeof ReviewProjectsApi>()),
  saveReviewProjectTextUnitDecision: saveMock,
  setReviewProjectTextUnitDecisionState: decisionStateMock,
  fetchReviewProjectDetail: fetchProjectMock,
}));
vi.mock('../../api/glossaries', async (importActual) => ({
  ...(await importActual<typeof GlossariesApi>()),
  matchGlossaryTerms: vi.fn().mockResolvedValue({ matchedTerms: [] }),
}));
vi.mock('../../utils/integrityCheck', async (importActual) => ({
  ...(await importActual<typeof IntegrityCheck>()),
  checkTextUnitIntegrityWithRetry: integrityMock,
}));
vi.mock('../../api/ai-review', () => ({
  fetchPrecomputedAiReview: fetchAiMock,
  formatAiReviewError: () => ({ message: 'Fixture AI error', detail: null }),
  requestAiReview: requestAiMock,
}));
vi.mock('../../hooks/useVisibleTextEditorEnabled', () => ({
  useVisibleTextEditorEnabled: () => assistedMock(),
}));
vi.mock('../../components/virtual/useVirtualRows', () => ({
  useVirtualRows: ({
    count,
    getItemKey,
  }: {
    count: number;
    getItemKey: (i: number) => number;
  }) => ({
    scrollRef: { current: null },
    virtualizer: {},
    items: Array.from({ length: count }, (_, index) => ({
      index,
      key: getItemKey(index),
      start: index * 100,
      end: (index + 1) * 100,
      size: 100,
      lane: 0,
    })),
    totalSize: count * 100,
    scrollToIndex: vi.fn(),
    measureElement: vi.fn(),
  }),
}));

beforeAll(() => {
  // jsdom does not expose the browser's inherited contenteditable state.
  Object.defineProperty(HTMLElement.prototype, 'isContentEditable', {
    configurable: true,
    get(this: HTMLElement) {
      return this.closest('[contenteditable="true"]') != null;
    },
  });
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', { configurable: true, value: vi.fn() });
  Object.defineProperty(Range.prototype, 'getBoundingClientRect', {
    configurable: true,
    value: () => ({ top: 0, left: 0, bottom: 0, right: 0, height: 0, width: 0 }),
  });
  Object.defineProperty(Range.prototype, 'getClientRects', { configurable: true, value: () => [] });
});

beforeEach(() => {
  saveMock.mockReset();
  decisionStateMock.mockReset();
  fetchProjectMock.mockReset();
  integrityMock.mockReset();
  integrityMock.mockResolvedValue({ checkResult: true });
  assistedMock.mockReturnValue(false);
  fetchAiMock.mockReset().mockResolvedValue(null);
  requestAiMock.mockReset().mockResolvedValue(aiResponse('Current review'));
});

type Project = ReturnType<typeof buildCarryoverProject>;
type SaveRequest = Parameters<typeof ReviewProjectsApi.saveReviewProjectTextUnitDecision>[0];
const fixture = carryoverFixtures[0];
const originalRevision = 'fixture-review-state-original';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function threeRowProject(): Project {
  const project = buildCarryoverProject(fixture);
  const [first, second] = project.reviewProjectTextUnits;
  // Positive synthetic predecessor IDs exercise the real ?tu parser.
  return {
    ...project,
    textUnitCount: 3,
    reviewProjectTextUnits: [
      {
        ...first,
        id: 101,
        reviewStateRevision: originalRevision,
        tmTextUnit: { ...first.tmTextUnit!, id: 201 },
        baselineTmTextUnitVariant: { ...first.baselineTmTextUnitVariant!, id: 301 },
        currentTmTextUnitVariant: { ...first.currentTmTextUnitVariant!, id: 301 },
      },
      second,
      {
        ...second,
        id: 102,
        tmTextUnit: { id: 202, name: 'fixture.third', content: 'Third source' },
        baselineTmTextUnitVariant: {
          ...second.baselineTmTextUnitVariant!,
          id: 302,
          content: 'ત્રીજું',
        },
        currentTmTextUnitVariant: {
          ...second.currentTmTextUnitVariant!,
          id: 302,
          content: 'ત્રીજું',
        },
      },
    ],
  };
}

function mountWorkflow({
  project = threeRowProject(),
  role = 'ROLE_TRANSLATOR',
  filter = '',
}: { project?: Project; role?: ApiUserProfile['role']; filter?: string } = {}) {
  const queryKey = [...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id];
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      // Keep the production retry policy and request count without wall-clock backoff.
      mutations: { retryDelay: 0 },
    },
  });
  queryClient.setQueryData(queryKey, project);
  fetchProjectMock.mockResolvedValue(project);
  const responseFor = (request: SaveRequest): ApiReviewProjectTextUnit => {
    const row = project.reviewProjectTextUnits.find((item) => item.id === request.textUnitId)!;
    const variant = {
      id: 90000000 + saveMock.mock.calls.length,
      content: request.target,
      status: request.status,
      includedInLocalizedFile: request.includedInLocalizedFile,
      comment: request.comment,
    };
    return {
      ...row,
      reviewStateRevision: `fixture-review-state-saved-${saveMock.mock.calls.length}`,
      currentTmTextUnitVariant: variant,
      reviewProjectTextUnitDecision: {
        decisionState: request.decisionState,
        notes: request.decisionNotes,
        decisionTmTextUnitVariant: variant,
        lastModifiedByUsername: 'fixture-reviewer',
      },
    };
  };
  saveMock.mockImplementation((request) => Promise.resolve(responseFor(request)));
  let navigate!: NavigateFunction;
  function RouterProbe() {
    navigate = useNavigate();
    const location = useLocation();
    return <output data-testid="review-location">{location.pathname + location.search}</output>;
  }
  const node = () => (
    <QueryClientProvider client={queryClient}>
      <UserContext.Provider
        value={{
          username: 'fixture-reviewer',
          role,
          canTranslateAllLocales: true,
          userLocales: [],
        }}
      >
        <MemoryRouter initialEntries={[`/review-projects/${project.id}?tu=201${filter}`]}>
          <RouterProbe />
          <Routes>
            <Route path="/review-projects/:projectId" element={<ReviewProjectPage />} />
            <Route path="/elsewhere" element={<p>Another app page</p>} />
          </Routes>
        </MemoryRouter>
      </UserContext.Provider>
    </QueryClientProvider>
  );
  const result = render(node());
  return {
    ...result,
    project,
    responseFor,
    navigate: (destination: number | string) => {
      if (typeof destination === 'number') void navigate(destination);
      else void navigate(destination);
    },
    currentProject: () => queryClient.getQueryData<Project>(queryKey)!,
    refetch: () => queryClient.refetchQueries({ queryKey }),
    refresh: async (next: Project) => {
      fetchProjectMock.mockResolvedValue(next);
      await queryClient.refetchQueries({ queryKey });
    },
    failRefresh: async () => {
      fetchProjectMock.mockRejectedValue(new Error('Fixture refresh failed'));
      await queryClient.refetchQueries({ queryKey });
    },
    replaceSnapshot: (next: Project) => {
      queryClient.setQueryData(queryKey, next);
      result.rerender(node());
    },
  };
}

function aiResponse(content: string, suggestion?: string) {
  return {
    message: { role: 'assistant' as const, content },
    suggestions: suggestion ? [{ content: suggestion, confidenceLevel: 100 }] : [],
    review: null,
  };
}

type EditorMode = 'textarea' | 'assisted' | 'icu' | 'mf2-rich' | 'mf2-raw';
const mf2Message = '.input {$count :number}\n{{You have {$count} files.}}';
const icuMessage = 'Hello {name}, you have {count, plural, one {one file} other {# files}}.';

async function mountEditor(mode: EditorMode) {
  const project = threeRowProject();
  const source = mode.startsWith('mf2')
    ? mf2Message
    : mode === 'icu'
      ? icuMessage
      : 'Original translation';
  const row = project.reviewProjectTextUnits[0];
  project.reviewProjectTextUnits[0] = {
    ...row,
    tmTextUnit: { ...row.tmTextUnit!, content: source },
    currentTmTextUnitVariant: { ...row.currentTmTextUnitVariant!, content: source },
    baselineTmTextUnitVariant: { ...row.baselineTmTextUnitVariant!, content: source },
  };
  assistedMock.mockReturnValue(mode !== 'textarea');
  const harness = mountWorkflow({ project });
  if (mode === 'mf2-raw')
    fireEvent.click(
      await screen.findByRole('button', { name: 'Placeholder editing is off. Edit placeholders' }),
    );
  const editor = await screen.findByRole<HTMLElement>('textbox', {
    name:
      mode === 'mf2-rich'
        ? 'Target Message'
        : mode === 'mf2-raw'
          ? 'Raw target MF2 Message'
          : 'Translation',
  });
  await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled());
  return { ...harness, editor, source };
}

function placeCaret(editor: HTMLElement, offset = 0) {
  editor.focus();
  if (editor instanceof HTMLTextAreaElement) {
    editor.setSelectionRange(offset, offset);
    return;
  }
  const text = document.createTreeWalker(editor, NodeFilter.SHOW_TEXT).nextNode();
  if (!text) throw new Error('Expected an editor text node');
  const range = document.createRange();
  range.setStart(text, offset);
  range.collapse(true);
  window.getSelection()?.removeAllRanges();
  window.getSelection()?.addRange(range);
  document.dispatchEvent(new Event('selectionchange'));
}

function submit(editor: HTMLElement, extra: Record<string, unknown> = {}) {
  fireEvent.keyDown(editor, { key: 'Enter', ctrlKey: true, ...extra });
}

describe('Review Project actual editor adversarial interactions', () => {
  it.each([false, true])(
    'accepts raw MF2 without inserting a newline into the saved message (advance: %s)',
    async (advance) => {
      const { editor } = await mountEditor('mf2-raw');
      placeCaret(editor);
      submit(editor, { shiftKey: advance });
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
      expect(saveMock).toHaveBeenCalledWith(expect.objectContaining({ target: mf2Message }));
      if (advance)
        await waitFor(() =>
          expect(screen.getByTestId('review-location')).toHaveTextContent(
            `tu=${fixture.next.tmTextUnitId}`,
          ),
        );
    },
  );

  it('keeps a valid dirty MF2 target saveable when a background refresh changes its current variant', async () => {
    const harness = await mountEditor('mf2-rich');
    placeCaret(harness.editor);
    await userEvent.setup().keyboard('Updated ');
    const changed = structuredClone(harness.project);
    changed.reviewProjectTextUnits[0].currentTmTextUnitVariant!.id = 401;
    changed.reviewProjectTextUnits[0].currentTmTextUnitVariant!.content = mf2Message.replace(
      'You have',
      'External writer has',
    );
    changed.reviewProjectTextUnits[0].reviewStateRevision = 'external-revision';
    await act(() => harness.refresh(changed));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled());
    submit(harness.editor);
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    expect(saveMock).toHaveBeenCalledWith(
      expect.objectContaining({
        target: mf2Message.replace('You have', 'Updated You have'),
        expectedReviewStateRevision: originalRevision,
      }),
    );
  });

  it.each(
    (['mf2-rich', 'mf2-raw'] as const).flatMap((mode) => [
      { mode, signal: 'legacy keyCode 229', key: { keyCode: 229, which: 229 }, active: false },
      { mode, signal: 'isComposing', key: { isComposing: true }, active: false },
      { mode, signal: 'active composition', key: {}, active: true },
    ]),
  )(
    'does not accept $mode on non-shift Ctrl+Enter during $signal',
    async ({ mode, signal, key, active }) => {
      const { editor } = await mountEditor(mode);
      placeCaret(editor);
      if (active) fireEvent.compositionStart(editor);
      submit(editor, key);
      await act(async () => {
        await new Promise((resolve) => window.setTimeout(resolve, 20));
      });
      expect(saveMock, signal).not.toHaveBeenCalled();
      expect(decisionStateMock).not.toHaveBeenCalled();
      expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
      if (active) {
        fireEvent.compositionEnd(editor);
        await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled());
      }
      submit(editor);
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    },
  );

  it.each(['textarea', 'assisted', 'icu', 'mf2-rich'] as const)(
    'blocks saving and row movement through a %s composition-end publication boundary',
    async (mode) => {
      const { editor, source } = await mountEditor(mode);
      placeCaret(editor);
      await userEvent.setup().keyboard('Updated ');
      expect(screen.getByRole('button', { name: 'Reset' })).toBeEnabled();
      fireEvent.compositionStart(editor);
      submit(editor, { shiftKey: true });
      fireEvent.keyDown(window, { key: 'ArrowDown' });
      expect(saveMock).not.toHaveBeenCalled();
      expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
      fireEvent.compositionEnd(editor);
      submit(editor, { shiftKey: true });
      expect(saveMock).not.toHaveBeenCalled();
      await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled());
      submit(editor);
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
      expect(saveMock).toHaveBeenCalledWith(
        expect.objectContaining({
          target:
            mode === 'mf2-rich'
              ? source.replace('You have', 'Updated You have')
              : 'Updated ' + source,
          expectedCurrentTmTextUnitVariantId: 301,
          expectedReviewStateRevision: originalRevision,
        }),
      );
    },
  );

  it.each(['textarea', 'assisted', 'mf2-rich'] as const)(
    'retains a later AI choice made during %s save and does not auto-advance',
    async (mode) => {
      const suggestion =
        mode === 'mf2-rich' ? mf2Message.replace('You have', 'Now there are') : 'AI replacement';
      fetchAiMock.mockResolvedValue(aiResponse('Suggestion for this row', suggestion));
      const harness = await mountEditor(mode);
      const use = await screen.findByRole('button', { name: 'Use' });
      const pending = deferred<ApiReviewProjectTextUnit>();
      saveMock.mockImplementationOnce(() => pending.promise);
      submit(harness.editor, { shiftKey: true });
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
      const firstRequest = saveMock.mock.calls[0][0];
      fireEvent.click(use);
      await act(() => Promise.resolve(pending.resolve(harness.responseFor(firstRequest))));
      await waitFor(() => expect(screen.getByRole('button', { name: 'Reset' })).toBeEnabled());
      expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
      await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled());
      fireEvent.keyDown(window, { key: 'Enter', ctrlKey: true });
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
      expect(saveMock.mock.calls[1][0].target).toBe(suggestion);
      expect(saveMock.mock.calls[1][0].expectedReviewStateRevision).toBe(
        'fixture-review-state-saved-1',
      );
    },
  );

  it.each(['assisted', 'mf2-rich'] as const)(
    'cannot undo a previous %s row into the next translation after accepting and advancing',
    async (mode) => {
      const { editor } = await mountEditor(mode);
      placeCaret(editor);
      await userEvent.setup().keyboard('Updated ');
      submit(editor, { shiftKey: true });
      await waitFor(() =>
        expect(screen.getByTestId('review-location')).toHaveTextContent(
          `tu=${fixture.next.tmTextUnitId}`,
        ),
      );
      const nextEditor = screen.getByRole('textbox', { name: 'Translation' });
      placeCaret(nextEditor);
      fireEvent.keyDown(nextEditor, { key: 'z', ctrlKey: true });
      expect(nextEditor).toHaveTextContent(fixture.next.target);
      submit(nextEditor);
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
      expect(saveMock.mock.calls[1][0]).toMatchObject({
        textUnitId: fixture.next.id,
        target: fixture.next.target,
      });
    },
  );

  it('drops a delayed AI follow-up when the same row receives a new review revision', async () => {
    fetchAiMock.mockResolvedValue(aiResponse('Initial review'));
    const harness = await mountEditor('assisted');
    await screen.findByText('Initial review');
    const pending = deferred<ReturnType<typeof aiResponse>>();
    requestAiMock.mockImplementationOnce(() => pending.promise);
    fireEvent.change(screen.getByPlaceholderText('Ask AI for a suggestion'), {
      target: { value: 'Please improve it' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
    await waitFor(() => expect(requestAiMock).toHaveBeenCalledTimes(1));
    fetchAiMock.mockResolvedValue(aiResponse('New revision review', 'Current revision suggestion'));
    const changed = structuredClone(harness.project);
    changed.reviewProjectTextUnits[0].reviewStateRevision = 'external-notes-revision';
    await act(() => harness.refresh(changed));
    await screen.findByText('New revision review');
    await act(() =>
      Promise.resolve(pending.resolve(aiResponse('Old delayed review', 'Stale suggestion'))),
    );
    expect(screen.queryByText('Old delayed review')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Use' }));
    submit(harness.editor);
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    expect(saveMock).toHaveBeenCalledWith(
      expect.objectContaining({
        target: 'Current revision suggestion',
        expectedReviewStateRevision: 'external-notes-revision',
      }),
    );
  });
});
