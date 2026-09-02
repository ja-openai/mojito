import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { flushSync } from 'react-dom';
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
  fetchPrecomputedAiReview: vi.fn().mockResolvedValue(null),
  formatAiReviewError: () => ({ message: 'Fixture AI error', detail: null }),
  requestAiReview: vi.fn().mockResolvedValue({
    message: { role: 'assistant', content: 'No issues found.' },
    suggestions: [],
    review: null,
  }),
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
});

type Project = ReturnType<typeof buildCarryoverProject>;
type SaveRequest = Parameters<typeof ReviewProjectsApi.saveReviewProjectTextUnitDecision>[0];
const fixture = carryoverFixtures[0];
const editedTarget = 'સમીક્ષકનો અસાચવેલો અનુવાદ';
const originalRevision = 'fixture-review-state-original';
const externalRevision = 'fixture-review-state-external';

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

function target() {
  return screen.getByRole<HTMLTextAreaElement>('textbox', { name: 'Translation' }).value;
}

function edit() {
  fireEvent.change(screen.getByRole('textbox', { name: 'Translation' }), {
    target: { value: editedTarget },
  });
}

function accept(advance = false) {
  fireEvent.keyDown(window, { key: 'Enter', ctrlKey: true, shiftKey: advance });
}

async function expectSecondRow() {
  await waitFor(() => {
    expect(target()).toBe(fixture.next.target);
    expect(screen.getByTestId('review-location')).toHaveTextContent(
      `tu=${fixture.next.tmTextUnitId}`,
    );
  });
}

function externalRow(project: Project, decided = false): ApiReviewProjectTextUnit {
  const row = project.reviewProjectTextUnits[0];
  const variant = { ...row.currentTmTextUnitVariant!, id: 401, content: 'બાહ્ય અનુવાદ' };
  return {
    ...row,
    reviewStateRevision: externalRevision,
    currentTmTextUnitVariant: variant,
    reviewProjectTextUnitDecision: {
      decisionState: decided ? 'DECIDED' : 'PENDING',
      decisionTmTextUnitVariant: decided ? variant : null,
      lastModifiedByUsername: 'another-reviewer',
    },
  };
}

describe('Review Project save recovery with the real route', () => {
  it.each([409, 500])(
    'preserves row, draft, and error after delayed terminal HTTP %i',
    async (status) => {
      const harness = mountWorkflow();
      const pending = deferred<ApiReviewProjectTextUnit>();
      const error = Object.assign(new Error('Fixture terminal failure'), {
        status,
        data: status === 409 ? externalRow(harness.project) : undefined,
      });
      saveMock.mockRejectedValue(error).mockImplementationOnce(() => pending.promise);
      edit();
      accept(true);
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
      await act(async () => {
        pending.reject(error);
        await Promise.resolve();
      });
      const errorMessage =
        status === 409 ? 'This text unit changed since you started editing.' : error.message;
      expect(await screen.findByText(errorMessage)).toBeInTheDocument();
      expect(target()).toBe(editedTarget);
      expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
      expect(
        harness.currentProject().reviewProjectTextUnits[0].reviewProjectTextUnitDecision
          ?.decisionState,
      ).toBe('PENDING');
      expect(saveMock).toHaveBeenCalledTimes(status === 409 ? 1 : 3);
      if (status === 500) {
        saveMock.mockImplementation((request) => Promise.resolve(harness.responseFor(request)));
        accept(true);
        await expectSecondRow();
        expect(saveMock).toHaveBeenCalledTimes(4);
        expect(saveMock).toHaveBeenLastCalledWith(
          expect.objectContaining({
            textUnitId: 101,
            target: editedTarget,
            expectedCurrentTmTextUnitVariantId: 301,
            expectedReviewStateRevision: originalRevision,
          }),
        );
      }
    },
  );

  it('retries Use mine with the original draft and advances exactly once after success', async () => {
    const harness = mountWorkflow();
    const conflictRow = externalRow(harness.project);
    saveMock.mockRejectedValueOnce(
      Object.assign(new Error('Conflict'), { status: 409, data: conflictRow }),
    );
    edit();
    accept(true);
    const useMine = await screen.findByRole('button', { name: 'Use mine' });
    expect(target()).toBe(editedTarget);
    const pending = deferred<ApiReviewProjectTextUnit>();
    saveMock.mockImplementationOnce(() => pending.promise);
    fireEvent.click(useMine);
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
    expect(target()).toBe(editedTarget);
    const request = saveMock.mock.calls[1][0];
    expect(request).toMatchObject({
      textUnitId: 101,
      target: editedTarget,
      expectedCurrentTmTextUnitVariantId: 401,
      expectedReviewStateRevision: externalRevision,
      overrideChangedCurrent: false,
    });
    await act(() => Promise.resolve(pending.resolve(harness.responseFor(request))));
    await expectSecondRow();
    await act(() => harness.refresh(harness.currentProject()));
    expect(target()).toBe(fixture.next.target);
    expect(saveMock).toHaveBeenCalledTimes(2);
  });

  it('discards the failed action when Reset replaces a conflicted draft', async () => {
    const harness = mountWorkflow();
    const conflictRow = externalRow(harness.project);
    saveMock.mockRejectedValueOnce(
      Object.assign(new Error('Conflict'), { status: 409, data: conflictRow }),
    );
    edit();
    accept(true);
    await screen.findByRole('button', { name: 'Use mine' });
    await act(() =>
      harness.refresh({
        ...harness.project,
        reviewProjectTextUnits: [conflictRow, ...harness.project.reviewProjectTextUnits.slice(1)],
      }),
    );
    await screen.findAllByText('બાહ્ય અનુવાદ');
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));
    expect(target()).toBe('બાહ્ય અનુવાદ');
    expect(screen.queryByRole('button', { name: 'Use mine' })).not.toBeInTheDocument();
    accept();
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
    expect(saveMock).toHaveBeenLastCalledWith(
      expect.objectContaining({
        textUnitId: 101,
        target: 'બાહ્ય અનુવાદ',
        expectedCurrentTmTextUnitVariantId: 401,
        expectedReviewStateRevision: externalRevision,
      }),
    );
    await waitFor(() => expect(screen.getByRole('button', { name: 'Pending' })).toBeEnabled());
    expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
  });

  it.each([false, true])(
    'explicitly uses the external decision and advances once (already decided: %s)',
    async (decided) => {
      const harness = mountWorkflow();
      const conflictRow = externalRow(harness.project, decided);
      saveMock.mockRejectedValueOnce(
        Object.assign(new Error('Conflict'), { status: 409, data: conflictRow }),
      );
      const pending = deferred<ApiReviewProjectTextUnit>();
      decisionStateMock.mockImplementationOnce(() => pending.promise);
      edit();
      accept(true);
      fireEvent.click(await screen.findByRole('button', { name: 'Use external' }));
      await waitFor(() => expect(decisionStateMock).toHaveBeenCalledTimes(1));
      expect(decisionStateMock).toHaveBeenCalledWith(
        expect.objectContaining({
          textUnitId: 101,
          expectedCurrentTmTextUnitVariantId: 401,
          expectedReviewStateRevision: externalRevision,
        }),
      );
      await act(() =>
        Promise.resolve(
          pending.resolve({
            ...conflictRow,
            reviewProjectTextUnitDecision: {
              decisionState: 'DECIDED',
              decisionTmTextUnitVariant: conflictRow.currentTmTextUnitVariant,
            },
          }),
        ),
      );
      await expectSecondRow();
      expect(
        harness.currentProject().reviewProjectTextUnits[0].currentTmTextUnitVariant?.content,
      ).toBe('બાહ્ય અનુવાદ');
      expect(saveMock).toHaveBeenCalledTimes(1);
      expect(decisionStateMock).toHaveBeenCalledTimes(1);
    },
  );

  it.each(['failure', 'unavailable'] as const)(
    'cancels advance when a PM closes %s validation',
    async (kind) => {
      mountWorkflow({ role: 'ROLE_PM' });
      if (kind === 'failure')
        integrityMock.mockResolvedValueOnce({
          checkResult: false,
          failureDetail: 'Fixture validation failure',
        });
      else integrityMock.mockRejectedValueOnce(new Error('Fixture validation unavailable'));
      edit();
      accept(true);
      const dialog = await screen.findByRole('alertdialog');
      expect(saveMock).not.toHaveBeenCalled();
      fireEvent.click(
        within(dialog).getByRole('button', { name: kind === 'failure' ? 'Keep editing' : 'Close' }),
      );
      expect(target()).toBe(editedTarget);
      expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
      accept();
      await waitFor(() =>
        expect(screen.getByRole('button', { name: 'Decided' })).toHaveAttribute(
          'aria-pressed',
          'true',
        ),
      );
      expect(target()).toBe(editedTarget);
      expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
      expect(saveMock).toHaveBeenCalledTimes(1);
    },
  );

  it('retains advance intent through PM validation retry and advances after its save succeeds', async () => {
    const harness = mountWorkflow({ role: 'ROLE_PM' });
    integrityMock.mockRejectedValueOnce(new Error('Unavailable'));
    edit();
    accept(true);
    const dialog = await screen.findByRole('alertdialog');
    const pending = deferred<ApiReviewProjectTextUnit>();
    saveMock.mockImplementationOnce(() => pending.promise);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    expect(target()).toBe(editedTarget);
    await act(() =>
      Promise.resolve(pending.resolve(harness.responseFor(saveMock.mock.calls[0][0]))),
    );
    await expectSecondRow();
    expect(saveMock).toHaveBeenCalledTimes(1);
  });

  it('waits for the confirmed Save anyway request before advancing', async () => {
    const harness = mountWorkflow({ role: 'ROLE_PM' });
    integrityMock.mockResolvedValueOnce({
      checkResult: false,
      failureDetail: 'Fixture validation failure',
    });
    edit();
    accept(true);
    const dialog = await screen.findByRole('alertdialog');
    const pending = deferred<ApiReviewProjectTextUnit>();
    saveMock.mockImplementationOnce(() => pending.promise);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save anyway' }));
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    expect(target()).toBe(editedTarget);
    await act(() =>
      Promise.resolve(pending.resolve(harness.responseFor(saveMock.mock.calls[0][0]))),
    );
    await expectSecondRow();
    expect(integrityMock).toHaveBeenCalledTimes(1);
    expect(saveMock).toHaveBeenCalledTimes(1);
  });

  it('selects the next pending row once when the saved row leaves the active filter', async () => {
    const harness = mountWorkflow({ filter: '&state=PENDING' });
    accept(true);
    await expectSecondRow();
    expect(screen.getByTestId('review-location')).toHaveTextContent('state=PENDING');
    expect(harness.container.querySelectorAll('.review-project-row')).toHaveLength(2);
    expect(
      harness.currentProject().reviewProjectTextUnits[0].reviewProjectTextUnitDecision
        ?.decisionState,
    ).toBe('DECIDED');
    expect(saveMock).toHaveBeenCalledTimes(1);
  });

  it('preserves newer comment and note edits without advancing when an older save succeeds', async () => {
    const harness = mountWorkflow();
    const pending = deferred<ApiReviewProjectTextUnit>();
    saveMock.mockImplementationOnce(() => pending.promise);
    edit();
    accept(true);
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    const comment = screen.getByPlaceholderText(
      'Explain why you chose this translation (if not obvious).',
    );
    const notes = screen.getByPlaceholderText(
      'Explain why the baseline translation was bad (to improve AI translation).',
    );
    fireEvent.change(comment, { target: { value: 'Newer comment' } });
    fireEvent.change(notes, { target: { value: 'Newer notes' } });
    const firstRequest = saveMock.mock.calls[0][0];
    expect(firstRequest).toMatchObject({
      target: editedTarget,
      comment: null,
      decisionNotes: null,
    });
    const saved = harness.responseFor(firstRequest);
    await act(() => Promise.resolve(pending.resolve(saved)));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled());
    expect(target()).toBe(editedTarget);
    expect(comment).toHaveValue('Newer comment');
    expect(notes).toHaveValue('Newer notes');
    expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
    accept();
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
    expect(saveMock).toHaveBeenLastCalledWith(
      expect.objectContaining({
        textUnitId: 101,
        target: editedTarget,
        comment: 'Newer comment',
        decisionNotes: 'Newer notes',
        expectedCurrentTmTextUnitVariantId: saved.currentTmTextUnitVariant!.id,
        expectedReviewStateRevision: saved.reviewStateRevision,
      }),
    );
    await waitFor(() => expect(screen.getByRole('button', { name: 'Pending' })).toBeEnabled());
    expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
  });

  it('keeps the translator draft without advancing after dismissing a validation failure', async () => {
    mountWorkflow();
    saveMock.mockRejectedValueOnce(Object.assign(new Error('Forbidden'), { status: 403 }));
    integrityMock.mockResolvedValueOnce({
      checkResult: false,
      failureDetail: 'Missing placeholder',
    });
    edit();
    accept(true);
    const dialog = await screen.findByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
    expect(target()).toBe(editedTarget);
    expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
    expect(saveMock).toHaveBeenCalledTimes(1);
  });

  it('does not let an old save completion advance a newer browser-history selection', async () => {
    const harness = mountWorkflow();
    const pending = deferred<ApiReviewProjectTextUnit>();
    saveMock.mockImplementationOnce(() => pending.promise);
    accept(true);
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    act(() =>
      harness.navigate(`/review-projects/${fixture.projectId}?tu=${fixture.next.tmTextUnitId}`),
    );
    await expectSecondRow();
    act(() => harness.navigate(-1));
    await waitFor(() => expect(target()).toBe(fixture.predecessor.target));
    await act(() =>
      Promise.resolve(pending.resolve(harness.responseFor(saveMock.mock.calls[0][0]))),
    );
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Decided' })).toHaveAttribute(
        'aria-pressed',
        'true',
      ),
    );
    expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
    expect(saveMock).toHaveBeenCalledTimes(1);
  });
});

describe('Review Project draft identity across refresh', () => {
  it('keeps an acknowledged save when an older background request completes later', async () => {
    const harness = mountWorkflow();
    const oldResponse = deferred<Project>();
    fetchProjectMock.mockImplementationOnce(() => oldResponse.promise);
    let refresh!: Promise<void>;
    act(() => {
      refresh = harness.refetch();
    });
    await waitFor(() => expect(fetchProjectMock).toHaveBeenCalledTimes(1));
    edit();
    accept();
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Decided' })).toHaveAttribute(
        'aria-pressed',
        'true',
      );
      expect(screen.getByRole('button', { name: 'Pending' })).toBeEnabled();
    });
    const saved = harness.currentProject().reviewProjectTextUnits[0];
    expect(saved.currentTmTextUnitVariant?.content).toBe(editedTarget);
    await act(async () => {
      oldResponse.resolve(harness.project);
      await refresh;
    });
    expect(target()).toBe(editedTarget);
    expect(screen.getByRole('button', { name: 'Decided' })).toHaveAttribute('aria-pressed', 'true');
    expect(harness.currentProject().reviewProjectTextUnits[0]).toEqual(saved);
    fireEvent.change(
      screen.getByPlaceholderText('Explain why you chose this translation (if not obvious).'),
      { target: { value: 'Explanation added after acknowledgment' } },
    );
    accept();
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
    expect(saveMock).toHaveBeenLastCalledWith(
      expect.objectContaining({
        target: editedTarget,
        expectedCurrentTmTextUnitVariantId: saved.currentTmTextUnitVariant!.id,
        expectedReviewStateRevision: saved.reviewStateRevision,
      }),
    );
  });

  it('restores an unsaved draft and its original revision after leaving and returning to the route', async () => {
    const harness = mountWorkflow();
    edit();
    act(() => harness.navigate('/elsewhere'));
    expect(screen.getByText('Another app page')).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Translation' })).not.toBeInTheDocument();
    const changed = {
      ...harness.project,
      reviewProjectTextUnits: [
        externalRow(harness.project),
        ...harness.project.reviewProjectTextUnits.slice(1),
      ],
    };
    act(() => harness.replaceSnapshot(changed));
    act(() => harness.navigate(-1));
    await waitFor(() => expect(target()).toBe(editedTarget));
    expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
    accept();
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    expect(saveMock).toHaveBeenCalledWith(
      expect.objectContaining({
        textUnitId: 101,
        target: editedTarget,
        expectedCurrentTmTextUnitVariantId: 301,
        expectedReviewStateRevision: originalRevision,
      }),
    );
  });

  it('does not restore a discarded draft when the route is opened again', async () => {
    const harness = mountWorkflow();
    edit();
    fireEvent.click(
      within(harness.container.querySelectorAll('.review-project-row')[1] as HTMLElement).getByText(
        fixture.next.source,
      ),
    );
    fireEvent.click(await screen.findByRole('button', { name: 'Discard changes' }));
    await expectSecondRow();
    act(() => harness.navigate('/elsewhere'));
    expect(screen.getByText('Another app page')).toBeInTheDocument();
    const changed = {
      ...harness.project,
      reviewProjectTextUnits: [
        externalRow(harness.project),
        ...harness.project.reviewProjectTextUnits.slice(1),
      ],
    };
    act(() => harness.replaceSnapshot(changed));
    act(() => harness.navigate(`/review-projects/${fixture.projectId}?tu=201`));
    await waitFor(() => expect(target()).toBe('બાહ્ય અનુવાદ'));
    accept();
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    expect(saveMock).toHaveBeenCalledWith(
      expect.objectContaining({
        textUnitId: 101,
        target: 'બાહ્ય અનુવાદ',
        expectedCurrentTmTextUnitVariantId: 401,
        expectedReviewStateRevision: externalRevision,
      }),
    );
  });

  it('keeps the mounted draft through a failed background refresh and its successful retry', async () => {
    const harness = mountWorkflow();
    edit();
    const editor = screen.getByRole('textbox', { name: 'Translation' });
    await act(() => harness.failRefresh());
    const retry = await screen.findByRole('button', { name: 'Retry refresh' });
    expect(screen.getByRole('textbox', { name: 'Translation' })).toBe(editor);
    expect(target()).toBe(editedTarget);
    const changed = {
      ...harness.project,
      reviewProjectTextUnits: [
        externalRow(harness.project),
        ...harness.project.reviewProjectTextUnits.slice(1),
      ],
    };
    fetchProjectMock.mockResolvedValue(changed);
    fireEvent.click(retry);
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Retry refresh' })).not.toBeInTheDocument(),
    );
    expect(screen.getByRole('textbox', { name: 'Translation' })).toBe(editor);
    expect(target()).toBe(editedTarget);
    accept();
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    expect(saveMock).toHaveBeenCalledWith(
      expect.objectContaining({
        target: editedTarget,
        expectedCurrentTmTextUnitVariantId: 301,
        expectedReviewStateRevision: originalRevision,
      }),
    );
  });

  it.each(['settled', 'commit boundary'] as const)(
    'retains the local target and original variant after a %s refresh',
    async (timing) => {
      const harness = mountWorkflow();
      edit();
      fireEvent.change(
        screen.getByPlaceholderText('Explain why you chose this translation (if not obvious).'),
        { target: { value: 'Local explanation' } },
      );
      fireEvent.change(
        screen.getByPlaceholderText(
          'Explain why the baseline translation was bad (to improve AI translation).',
        ),
        { target: { value: 'Local review notes' } },
      );
      const changed = {
        ...harness.project,
        reviewProjectTextUnits: [
          externalRow(harness.project),
          ...harness.project.reviewProjectTextUnits.slice(1),
        ],
      };
      if (timing === 'settled') {
        await act(() => harness.refresh(changed));
        await screen.findAllByText('બાહ્ય અનુવાદ');
        expect(target()).toBe(editedTarget);
        accept();
      } else {
        act(() => {
          flushSync(() => harness.replaceSnapshot(changed));
          accept();
        });
      }
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
      expect(saveMock).toHaveBeenCalledWith(
        expect.objectContaining({
          textUnitId: 101,
          target: editedTarget,
          expectedCurrentTmTextUnitVariantId: 301,
          expectedReviewStateRevision: originalRevision,
          comment: 'Local explanation',
          decisionNotes: 'Local review notes',
        }),
      );
    },
  );

  it('adopts a changed remote variant when the local draft is clean', async () => {
    const harness = mountWorkflow();
    const changed = {
      ...harness.project,
      reviewProjectTextUnits: [
        externalRow(harness.project),
        ...harness.project.reviewProjectTextUnits.slice(1),
      ],
    };
    await act(() => harness.refresh(changed));
    await waitFor(() => expect(target()).toBe('બાહ્ય અનુવાદ'));
    accept();
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    expect(saveMock).toHaveBeenCalledWith(
      expect.objectContaining({
        textUnitId: 101,
        target: 'બાહ્ય અનુવાદ',
        expectedCurrentTmTextUnitVariantId: 401,
        expectedReviewStateRevision: externalRevision,
      }),
    );
  });
});

describe('Review Project composition shortcuts', () => {
  it.each([
    { name: 'isComposing', isComposing: true, keyCode: 13, start: false },
    { name: 'legacy keyCode 229', isComposing: false, keyCode: 229, start: false },
    { name: 'active composition session', isComposing: false, keyCode: 13, start: true },
  ])('does not save or advance MF2 during $name', async ({ isComposing, keyCode, start }) => {
    const project = threeRowProject();
    const first = project.reviewProjectTextUnits[0];
    const message = '.input {$count :number}\n{{You have {$count} files.}}';
    project.reviewProjectTextUnits[0] = {
      ...first,
      tmTextUnit: { ...first.tmTextUnit!, content: message },
      baselineTmTextUnitVariant: { ...first.baselineTmTextUnitVariant!, content: message },
      currentTmTextUnitVariant: { ...first.currentTmTextUnitVariant!, content: message },
    };
    mountWorkflow({ project });
    const editor = await screen.findByRole('textbox', { name: 'Target Message' });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled());
    editor.focus();
    const firstText = editor.querySelector('p')?.firstChild;
    if (!firstText) throw new Error('Expected an editable MF2 text node');
    const range = document.createRange();
    range.setStart(firstText, 0);
    range.collapse(true);
    window.getSelection()?.removeAllRanges();
    window.getSelection()?.addRange(range);
    document.dispatchEvent(new Event('selectionchange'));
    await userEvent.setup().keyboard('Updated ');
    expect(editor).toHaveTextContent('Updated You have');
    const updatedMessage = message.replace('You have', 'Updated You have');
    if (start) fireEvent.compositionStart(editor);
    await act(async () => {
      fireEvent.keyDown(editor, {
        key: 'Enter',
        ctrlKey: true,
        shiftKey: true,
        isComposing,
        keyCode,
      });
      await Promise.resolve();
    });
    expect(saveMock).not.toHaveBeenCalled();
    expect(decisionStateMock).not.toHaveBeenCalled();
    expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
    if (start) {
      fireEvent.compositionEnd(editor);
      await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled());
    }
    fireEvent.keyDown(editor, { key: 'Enter', ctrlKey: true });
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    expect(saveMock).toHaveBeenCalledWith(
      expect.objectContaining({ textUnitId: 101, target: updatedMessage }),
    );
  });
});
