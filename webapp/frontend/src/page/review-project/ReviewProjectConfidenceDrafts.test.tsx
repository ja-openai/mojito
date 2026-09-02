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
    const currentProject = queryClient.getQueryData<Project>(queryKey) ?? project;
    const row = currentProject.reviewProjectTextUnits.find(
      (item) => item.id === request.textUnitId,
    )!;
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
  let username = 'fixture-reviewer';
  const node = () => (
    <QueryClientProvider client={queryClient}>
      <UserContext.Provider
        value={{
          username,
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
    queryClient,
    responseFor,
    switchUser: (next: string) => {
      username = next;
      result.rerender(node());
    },
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

function draftField(field: 'target' | 'comment' | 'decisionNotes') {
  if (field === 'target') return screen.getByRole('textbox', { name: 'Translation' });
  return screen.getByPlaceholderText(
    field === 'comment'
      ? 'Explain why you chose this translation (if not obvious).'
      : 'Explain why the baseline translation was bad (to improve AI translation).',
  );
}

describe('Review Project confidence schedules', () => {
  it('adopts a remapped source automatically when the original draft is clean', async () => {
    const harness = mountWorkflow();
    const changed = {
      ...externalRow(harness.project),
      tmTextUnit: { id: 777, name: 'Changed source', content: 'Changed source with a clean draft' },
    };
    await act(() =>
      harness.refresh({
        ...harness.project,
        reviewProjectTextUnits: [changed, ...harness.project.reviewProjectTextUnits.slice(1)],
      }),
    );
    await waitFor(() => expect(screen.getByTestId('review-location')).toHaveTextContent('tu=777'));
    expect(target()).toBe(changed.currentTmTextUnitVariant?.content);
    expect(screen.queryByText(/source text changed/i)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled();
  });

  it('retains the original source identity with a dirty draft across route restoration', async () => {
    const harness = mountWorkflow();
    edit();
    act(() => harness.navigate('/elsewhere'));
    const changed = {
      ...externalRow(harness.project),
      tmTextUnit: { id: 777, name: 'Changed source', content: 'Changed source while away' },
    };
    act(() =>
      harness.replaceSnapshot({
        ...harness.project,
        reviewProjectTextUnits: [changed, ...harness.project.reviewProjectTextUnits.slice(1)],
      }),
    );
    act(() => harness.navigate(-1));
    await screen.findByText(/source text changed/i);
    expect(target()).toBe(editedTarget);
    expect(screen.getByRole('button', { name: 'Accept' })).toBeDisabled();
    await act(async () => {
      accept(true);
      await Promise.resolve();
    });
    expect(saveMock).not.toHaveBeenCalled();
    const retained = harness.queryClient.getQueryData<{
      base: { tmTextUnitId: number; source: string };
      values: { target: string };
    }>(['review-project-draft', 'fixture-reviewer', fixture.projectId, 101]);
    expect(retained?.base).toEqual(
      expect.objectContaining({ tmTextUnitId: 201, source: fixture.predecessor.source }),
    );
    expect(retained?.values.target).toBe(editedTarget);
  });

  it('blocks both conflict recovery choices when the conflict remaps the source', async () => {
    const harness = mountWorkflow();
    const conflict = {
      ...externalRow(harness.project),
      tmTextUnit: { id: 777, name: 'Changed source', content: 'Changed source in conflict' },
    };
    saveMock.mockRejectedValueOnce(
      Object.assign(new Error('Conflict'), { status: 409, data: conflict }),
    );
    edit();
    accept(true);
    await screen.findByText(/source text changed/i);
    expect(screen.getByRole('button', { name: 'Use external' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Use mine' })).toBeDisabled();
    expect(target()).toBe(editedTarget);
    expect(saveMock).toHaveBeenCalledTimes(1);
    expect(decisionStateMock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));
    expect(target()).toBe(conflict.currentTmTextUnitVariant?.content);
    expect(screen.queryByRole('button', { name: 'Use mine' })).not.toBeInTheDocument();
  });

  it.each(['tmTextUnitId', 'source'] as const)(
    'requires explicit review when a dirty row changes source identity through %s',
    async (change) => {
      const harness = mountWorkflow({ role: 'ROLE_PM' });
      edit();
      const changed = {
        ...externalRow(harness.project),
        tmTextUnit: {
          ...harness.project.reviewProjectTextUnits[0].tmTextUnit!,
          ...(change === 'tmTextUnitId'
            ? { id: 777 }
            : { content: 'Different source after editing started' }),
        },
      };
      await act(() =>
        harness.refresh({
          ...harness.project,
          reviewProjectTextUnits: [changed, ...harness.project.reviewProjectTextUnits.slice(1)],
        }),
      );
      if (change === 'tmTextUnitId') {
        await waitFor(() =>
          expect(screen.getByTestId('review-location')).toHaveTextContent('tu=777'),
        );
      } else {
        await screen.findAllByText('Different source after editing started');
      }
      expect(target()).toBe(editedTarget);
      await act(async () => {
        accept(true);
        await Promise.resolve();
      });
      expect(saveMock).not.toHaveBeenCalled();
      expect(decisionStateMock).not.toHaveBeenCalled();
      expect(screen.getByText(/source text changed/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Accept' })).toBeDisabled();
      expect(screen.getByTestId('review-location')).toHaveTextContent(
        `tu=${changed.tmTextUnit.id}`,
      );
      fireEvent.click(screen.getByRole('button', { name: 'Reset' }));
      expect(screen.queryByText(/source text changed/i)).not.toBeInTheDocument();
      expect(target()).toBe(changed.currentTmTextUnitVariant?.content);
      accept();
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
      expect(saveMock.mock.calls[0][0]).toEqual(
        expect.objectContaining({
          target: changed.currentTmTextUnitVariant?.content,
          expectedReviewStateRevision: changed.reviewStateRevision,
        }),
      );
      expect(integrityMock).toHaveBeenLastCalledWith({
        content: changed.currentTmTextUnitVariant?.content,
        tmTextUnitId: changed.tmTextUnit.id,
      });
    },
  );

  it.each(['', 'New translation'])(
    'preserves target %j and newer notes while a prior save leaves the pending filter',
    async (newTarget) => {
      const harness = mountWorkflow({ filter: '&state=PENDING' });
      const pending = deferred<ApiReviewProjectTextUnit>();
      saveMock.mockImplementationOnce(() => pending.promise);
      fireEvent.change(screen.getByRole('textbox', { name: 'Translation' }), {
        target: { value: newTarget },
      });
      accept(true);
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
      expect(draftField('decisionNotes')).toBeEnabled();
      await userEvent.setup().type(draftField('decisionNotes'), 'New notes during save');
      await act(() =>
        Promise.resolve(pending.resolve(harness.responseFor(saveMock.mock.calls[0][0]))),
      );
      await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled());
      expect(target()).toBe(newTarget);
      expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
      accept();
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
      expect(saveMock.mock.calls[1][0]).toEqual(
        expect.objectContaining({
          textUnitId: 101,
          target: newTarget,
          expectedReviewStateRevision: 'fixture-review-state-saved-1',
        }),
      );
    },
  );

  it('retains a notes-only draft and its reviewed revision across same-project user switches', async () => {
    const harness = mountWorkflow();
    const notes = screen.getByPlaceholderText(
      'Explain why the baseline translation was bad (to improve AI translation).',
    );
    fireEvent.change(notes, { target: { value: 'Reviewer one notes' } });
    act(() => harness.switchUser('reviewer-two'));
    expect(
      screen.getByPlaceholderText(
        'Explain why the baseline translation was bad (to improve AI translation).',
      ),
    ).toHaveValue('');
    await act(() =>
      harness.refresh({
        ...harness.project,
        reviewProjectTextUnits: [
          externalRow(harness.project),
          ...harness.project.reviewProjectTextUnits.slice(1),
        ],
      }),
    );
    act(() => harness.switchUser('fixture-reviewer'));
    expect(
      screen.getByPlaceholderText(
        'Explain why the baseline translation was bad (to improve AI translation).',
      ),
    ).toHaveValue('Reviewer one notes');
    expect(target()).toBe(fixture.predecessor.target);
    accept();
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    expect(saveMock.mock.calls[0][0]).toEqual(
      expect.objectContaining({
        textUnitId: 101,
        decisionNotes: 'Reviewer one notes',
        expectedReviewStateRevision: originalRevision,
      }),
    );
  });

  it('preserves the current draft after back cancellation followed by forward navigation', async () => {
    const harness = mountWorkflow();
    act(() =>
      harness.navigate(`/review-projects/${fixture.projectId}?tu=${fixture.next.tmTextUnitId}`),
    );
    await expectSecondRow();
    edit();
    act(() => harness.navigate(-1));
    fireEvent.click(await screen.findByRole('button', { name: 'Keep editing' }));
    expect(target()).toBe(editedTarget);
    expect(screen.getByTestId('review-location')).toHaveTextContent(
      `tu=${fixture.next.tmTextUnitId}`,
    );
    act(() => harness.navigate(1));
    expect(target()).toBe(editedTarget);
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    accept();
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    expect(saveMock.mock.calls[0][0].textUnitId).toBe(fixture.next.id);
  });

  it('does not replace an unsaved empty translation when its row disappears from the search results', async () => {
    const harness = mountWorkflow();
    fireEvent.change(screen.getByRole('textbox', { name: 'Translation' }), {
      target: { value: '' },
    });
    fireEvent.change(screen.getByPlaceholderText('Search source, translation, comments, or id'), {
      target: { value: 'Third source' },
    });
    expect(target()).toBe('');
    expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
    act(() => harness.navigate(`/review-projects/${fixture.projectId}?tu=202`));
    fireEvent.click(await screen.findByRole('button', { name: 'Keep editing' }));
    expect(target()).toBe('');
    accept();
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    expect(saveMock.mock.calls[0][0]).toEqual(
      expect.objectContaining({ textUnitId: 101, target: '' }),
    );
  });

  it.each(['before-snapshot', 'after-snapshot'] as const)(
    'keeps the saved row and URL consistent when a filter hides its successor %s',
    async (timing) => {
      const harness = mountWorkflow();
      const pending = deferred<ApiReviewProjectTextUnit>();
      saveMock.mockImplementationOnce(() => pending.promise);
      edit();
      accept(true);
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
      const filter = () =>
        fireEvent.change(
          screen.getByPlaceholderText('Search source, translation, comments, or id'),
          {
            target: { value: 'Third source' },
          },
        );
      if (timing === 'before-snapshot') filter();
      await act(() =>
        Promise.resolve(pending.resolve(harness.responseFor(saveMock.mock.calls[0][0]))),
      );
      if (timing === 'after-snapshot') filter();
      if (timing === 'after-snapshot') {
        expect(screen.queryByRole('textbox', { name: 'Translation' })).not.toBeInTheDocument();
        fireEvent.click(screen.getByText('Third source'));
      }
      await waitFor(() => {
        expect(target()).toBe('ત્રીજું');
        expect(screen.getByTestId('review-location')).toHaveTextContent('tu=202');
      });
      expect(saveMock).toHaveBeenCalledTimes(1);
      accept();
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
      expect(saveMock.mock.calls[1][0]).toEqual(
        expect.objectContaining({ textUnitId: 102, target: 'ત્રીજું' }),
      );
    },
  );

  it.each(['before-response', 'after-response'] as const)(
    'recovers from exhausted retries with aligned selection and URL after %s refresh',
    async (timing) => {
      const harness = mountWorkflow();
      const error = Object.assign(new Error('Deliberate exhausted save'), { status: 500 });
      saveMock.mockRejectedValue(error);
      edit();
      accept(true);
      await screen.findByText('Deliberate exhausted save');
      expect(saveMock).toHaveBeenCalledTimes(3);
      const pending = deferred<ApiReviewProjectTextUnit>();
      saveMock.mockImplementationOnce(() => pending.promise);
      accept(true);
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(4));
      const saved = harness.responseFor(saveMock.mock.calls[3][0]);
      const refreshed = {
        ...harness.project,
        reviewProjectTextUnits: [saved, ...harness.project.reviewProjectTextUnits.slice(1)],
      };
      if (timing === 'before-response') await act(() => harness.refresh(refreshed));
      await act(() => Promise.resolve(pending.resolve(saved)));
      if (timing === 'after-response') await act(() => harness.refresh(refreshed));
      await expectSecondRow();
      saveMock.mockImplementation((request) => Promise.resolve(harness.responseFor(request)));
      fireEvent.keyDown(window, { key: 'Enter', ctrlKey: true });
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(5));
      expect(saveMock.mock.calls[4][0]).toEqual(
        expect.objectContaining({ textUnitId: fixture.next.id, target: fixture.next.target }),
      );
    },
  );

  it.each(['target', 'comment', 'decisionNotes'] as const)(
    'discards %s edits made before Use external starts',
    async (field) => {
      const harness = mountWorkflow();
      const conflict = externalRow(harness.project);
      saveMock.mockRejectedValueOnce(
        Object.assign(new Error('Conflict'), { status: 409, data: conflict }),
      );
      edit();
      accept(true);
      await screen.findByRole('button', { name: 'Use external' });
      fireEvent.change(draftField(field), { target: { value: 'Edited before choosing current' } });
      const pending = deferred<ApiReviewProjectTextUnit>();
      decisionStateMock.mockImplementationOnce(() => pending.promise);
      fireEvent.click(screen.getByRole('button', { name: 'Use external' }));
      await waitFor(() => expect(decisionStateMock).toHaveBeenCalledTimes(1));
      await act(() =>
        Promise.resolve(
          pending.resolve({
            ...conflict,
            reviewStateRevision: 'current-adopted',
            reviewProjectTextUnitDecision: {
              ...conflict.reviewProjectTextUnitDecision,
              decisionState: 'DECIDED',
              decisionTmTextUnitVariant: conflict.currentTmTextUnitVariant,
            },
          }),
        ),
      );
      await expectSecondRow();
      expect(
        harness.queryClient.getQueryData([
          'review-project-draft',
          'fixture-reviewer',
          fixture.projectId,
          101,
        ]),
      ).toBeUndefined();
    },
  );

  it('preserves the original submission and newer local fields after Use external conflicts again then Use mine succeeds', async () => {
    const harness = mountWorkflow();
    const conflict = externalRow(harness.project);
    saveMock.mockRejectedValueOnce(
      Object.assign(new Error('First conflict'), { status: 409, data: conflict }),
    );
    edit();
    accept(true);
    await screen.findByRole('button', { name: 'Use external' });
    fireEvent.change(draftField('target'), { target: { value: 'Newer local target' } });
    fireEvent.change(draftField('decisionNotes'), { target: { value: 'Newer local notes' } });
    const secondConflict = {
      ...conflict,
      reviewStateRevision: 'second-conflict',
      currentTmTextUnitVariant: {
        ...conflict.currentTmTextUnitVariant!,
        id: 402,
        content: 'Newer external target',
      },
    };
    decisionStateMock.mockRejectedValueOnce(
      Object.assign(new Error('Second conflict'), { status: 409, data: secondConflict }),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Use external' }));
    await screen.findAllByText('Newer external target');
    fireEvent.click(screen.getByRole('button', { name: 'Use mine' }));
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
    expect(saveMock.mock.calls[1][0]).toEqual(
      expect.objectContaining({
        target: editedTarget,
        decisionNotes: null,
        expectedReviewStateRevision: 'second-conflict',
      }),
    );
    await waitFor(() => expect(screen.getByRole('button', { name: 'Reset' })).toBeEnabled());
    expect(target()).toBe('Newer local target');
    expect(draftField('decisionNotes')).toHaveValue('Newer local notes');
    expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
  });

  it.each(['comment', 'decisionNotes'] as const)(
    'preserves %s edits made after Use external starts',
    async (field) => {
      const harness = mountWorkflow();
      const conflict = externalRow(harness.project);
      saveMock.mockRejectedValueOnce(
        Object.assign(new Error('Conflict'), { status: 409, data: conflict }),
      );
      edit();
      accept(true);
      const pending = deferred<ApiReviewProjectTextUnit>();
      decisionStateMock.mockImplementationOnce(() => pending.promise);
      fireEvent.click(await screen.findByRole('button', { name: 'Use external' }));
      await waitFor(() => expect(decisionStateMock).toHaveBeenCalledTimes(1));
      expect(draftField('target')).toBeDisabled();
      expect(draftField(field)).toBeEnabled();
      await userEvent.setup().type(draftField(field), 'Edited after choosing current');
      const acknowledged = {
        ...conflict,
        reviewStateRevision: 'current-adopted',
        reviewProjectTextUnitDecision: {
          ...conflict.reviewProjectTextUnitDecision,
          decisionState: 'DECIDED' as const,
          decisionTmTextUnitVariant: conflict.currentTmTextUnitVariant,
        },
      };
      await act(() => Promise.resolve(pending.resolve(acknowledged)));
      await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled());
      expect(draftField(field)).toHaveValue('Edited after choosing current');
      expect(target()).toBe(conflict.currentTmTextUnitVariant?.content);
      expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
      expect(screen.getByRole('button', { name: 'Reset' })).toBeEnabled();
      accept();
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
      expect(saveMock.mock.calls[1][0]).toEqual(
        expect.objectContaining({
          textUnitId: 101,
          [field]: 'Edited after choosing current',
          target: conflict.currentTmTextUnitVariant?.content,
          expectedReviewStateRevision: 'current-adopted',
        }),
      );
    },
  );
});
