import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import {
  MemoryRouter,
  type NavigateFunction,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as GlossariesApi from '../../api/glossaries';
import type * as ReviewProjectsApi from '../../api/review-projects';
import type { ApiReviewProjectTextUnit } from '../../api/review-projects';
import type { ApiUserProfile } from '../../api/users';
import { REVIEW_PROJECT_DETAIL_QUERY_KEY } from '../../hooks/useReviewProjectDetail';
import { UserContext } from '../../hooks/useUser';
import type * as IntegrityCheck from '../../utils/integrityCheck';
import { buildCarryoverProject, carryoverFixtures } from './review-project-carryover.fixtures';
import { ReviewProjectPage } from './ReviewProjectPage';
import { type ReviewProjectDecisionSnapshot, useReviewProjectDraft } from './useReviewProjectDraft';

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
const clients = new Set<QueryClient>();

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
  clients.add(queryClient);
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
    queryClient,
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

afterEach(() => {
  clients.forEach((client) => client.clear());
  clients.clear();
});

const original: ReviewProjectDecisionSnapshot = {
  tmTextUnitId: 201,
  source: 'Original source',
  messageFormat: null,
  expectedCurrentVariantId: 30,
  reviewStateRevision: 'old-review-version',
  target: 'Original translation',
  comment: null,
  decisionNotes: null,
  statusChoice: 'NEEDS_REVIEW',
  decisionState: 'PENDING',
  suggestionSourceLabel: null,
};
const savedSnapshot: ReviewProjectDecisionSnapshot = {
  ...original,
  expectedCurrentVariantId: 31,
  reviewStateRevision: 'saved-review-version',
  target: 'Saved translation',
  statusChoice: 'ACCEPTED',
  decisionState: 'DECIDED',
};

function mountDraft({
  client = new QueryClient(),
  username = 'translator',
  projectId = 7,
  rowId = 101,
  snapshot = original,
}: {
  client?: QueryClient;
  username?: string;
  projectId?: number;
  rowId?: number;
  snapshot?: ReviewProjectDecisionSnapshot;
} = {}) {
  clients.add(client);
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client }, children);
  return {
    client,
    ...renderHook(
      ({ nextSnapshot }) => useReviewProjectDraft(username, projectId, rowId, nextSnapshot),
      {
        initialProps: { nextSnapshot: snapshot },
        wrapper,
      },
    ),
  };
}

function unloadIsPrevented() {
  const event = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(event);
  return event.defaultPrevented;
}

describe('Review Project adversarial draft lifecycle', () => {
  it('adopts the current server snapshot when restoring a draft retained only for a pending clean save', () => {
    const first = mountDraft();
    act(() => first.result.current.startOperation(1));
    expect(first.client.getQueryData(['review-project-draft', 'translator', 7, 101])).toBeDefined();
    first.unmount();

    const reopened = mountDraft({ client: first.client, snapshot: savedSnapshot });
    expect(reopened.result.current.session.values.target).toBe(savedSnapshot.target);
    expect(reopened.result.current.session.base).toEqual(savedSnapshot);
    expect(reopened.result.current.dirty).toBe(false);
  });

  it.each(['target', 'comment', 'decisionNotes', 'statusChoice'] as const)(
    'preserves a newer %s edit through a successful earlier operation and a late response for that operation',
    (field) => {
      const { result } = mountDraft();
      act(() => {
        result.current.updateValues((values) => ({ ...values, target: savedSnapshot.target }));
        result.current.startOperation(1);
      });
      const lateFinish = result.current.finishOperation;
      const next = field === 'statusChoice' ? 'REJECTED' : `Newer ${field}`;
      act(() => {
        result.current.updateValues((values) => ({ ...values, [field]: next }));
        result.current.finishOperation(1, savedSnapshot, false, false);
      });
      expect(result.current.session.values[field]).toBe(next);
      expect(result.current.dirty).toBe(true);
      expect(result.current.session.base).toEqual(savedSnapshot);
      act(() => lateFinish(1, original, true, false));
      expect(result.current.session.values[field]).toBe(next);
      expect(result.current.session.base).toEqual(savedSnapshot);
    },
  );

  it('ignores a completion for an earlier operation after a newer operation starts', () => {
    const { result } = mountDraft();
    act(() => {
      result.current.updateValues((values) => ({ ...values, target: 'One' }));
      result.current.startOperation(1);
      result.current.cancelOperation();
      result.current.updateValues((values) => ({ ...values, target: 'Two' }));
      result.current.startOperation(2);
      result.current.finishOperation(1, savedSnapshot, true, false);
    });
    expect(result.current.session.values.target).toBe('Two');
    expect(result.current.session.base).toEqual(original);
    expect(result.current.session.operation?.id).toBe(2);
  });

  it('keeps the original base through multiple refreshes, restores it on return, and resets to the newest remote', () => {
    const first = mountDraft();
    act(() => first.result.current.updateValues((values) => ({ ...values, target: 'My draft' })));
    const secondRemote = { ...savedSnapshot, target: 'Remote 2', reviewStateRevision: 'remote-2' };
    const thirdRemote = { ...savedSnapshot, target: 'Remote 3', reviewStateRevision: 'remote-3' };
    first.rerender({ nextSnapshot: savedSnapshot });
    first.rerender({ nextSnapshot: secondRemote });
    first.unmount();
    const reopened = mountDraft({ client: first.client, snapshot: thirdRemote });
    expect(reopened.result.current.session.values.target).toBe('My draft');
    expect(reopened.result.current.session.base).toEqual(original);
    act(() => reopened.result.current.reset());
    expect(reopened.result.current.session.base).toEqual(thirdRemote);
    expect(reopened.result.current.session.values.target).toBe('Remote 3');
    expect(unloadIsPrevented()).toBe(false);
  });

  it.each([{ username: 'other-user' }, { projectId: 8 }, { rowId: 102 }])(
    'isolates a retained dirty draft from another context: %j',
    (differentContext) => {
      const first = mountDraft();
      act(() =>
        first.result.current.updateValues((values) => ({ ...values, target: 'Private draft' })),
      );
      first.unmount();
      const unrelated = mountDraft({
        client: first.client,
        snapshot: savedSnapshot,
        ...differentContext,
      });
      expect(unrelated.result.current.session.values.target).toBe(savedSnapshot.target);
      unrelated.unmount();
      const originalContext = mountDraft({ client: first.client });
      expect(originalContext.result.current.session.values.target).toBe('Private draft');
      act(() => originalContext.result.current.reset());
      expect(unloadIsPrevented()).toBe(false);
    },
  );

  it('protects two retained drafts until both are cleared, including when two QueryClients coexist', () => {
    const first = mountDraft();
    const second = mountDraft();
    act(() => {
      first.result.current.updateValues((values) => ({ ...values, target: 'Draft A' }));
      second.result.current.updateValues((values) => ({ ...values, target: 'Draft B' }));
    });
    first.unmount();
    second.unmount();
    first.client.clear();
    expect(unloadIsPrevented()).toBe(true);
    second.client.clear();
    expect(unloadIsPrevented()).toBe(false);
  });

  it('clears the retained draft if the reviewer undoes the edit back to the original value', () => {
    const { result, client } = mountDraft();
    act(() => result.current.updateValues((values) => ({ ...values, target: 'Temporary edit' })));
    expect(unloadIsPrevented()).toBe(true);
    act(() => result.current.updateValues((values) => ({ ...values, target: original.target })));
    expect(client.getQueryData(['review-project-draft', 'translator', 7, 101])).toBeUndefined();
    expect(unloadIsPrevented()).toBe(false);
  });

  it('keeps a newer edit after a state-only save, then a new full save acknowledges it', () => {
    const { result } = mountDraft();
    const stateOnly = {
      ...original,
      decisionState: 'DECIDED' as const,
      reviewStateRevision: 'state-only',
    };
    act(() => {
      result.current.updateValues((values) => ({ ...values, target: 'Retained draft' }));
      result.current.startOperation(1);
      result.current.updateValues((values) => ({ ...values, decisionNotes: 'New notes' }));
      result.current.finishOperation(1, stateOnly, false, true);
    });
    expect(result.current.session.values).toMatchObject({
      target: 'Retained draft',
      decisionNotes: 'New notes',
    });
    expect(result.current.session.base).toEqual(stateOnly);
    const finalSnapshot = {
      ...savedSnapshot,
      target: 'Retained draft',
      decisionNotes: 'New notes',
    };
    act(() => {
      result.current.startOperation(2);
      result.current.finishOperation(2, finalSnapshot, false, false);
    });
    expect(result.current.dirty).toBe(false);
    expect(unloadIsPrevented()).toBe(false);
  });
});

describe('Review Project adversarial route lifecycle', () => {
  it('shows the newest server translation after a clean pending save leaves and returns to the route', async () => {
    const harness = mountWorkflow();
    const pending = deferred<ApiReviewProjectTextUnit>();
    saveMock.mockImplementationOnce(() => pending.promise);
    accept();
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    act(() => harness.navigate('/elsewhere'));
    expect(screen.getByText('Another app page')).toBeInTheDocument();
    const changed = {
      ...harness.project,
      reviewProjectTextUnits: [
        externalRow(harness.project, true),
        ...harness.project.reviewProjectTextUnits.slice(1),
      ],
    };
    act(() => harness.replaceSnapshot(changed));
    act(() => harness.navigate(-1));
    await waitFor(() => expect(target()).toBe('બાહ્ય અનુવાદ'));
    await act(() =>
      Promise.resolve(pending.resolve(harness.responseFor(saveMock.mock.calls[0][0]))),
    );
    expect(target()).toBe('બાહ્ય અનુવાદ');
  });

  it('clears a reset conflict and adopts the conflict response as the newest known server row', async () => {
    const harness = mountWorkflow();
    const changed = externalRow(harness.project);
    saveMock.mockRejectedValueOnce(
      Object.assign(new Error('Conflict'), { status: 409, data: changed }),
    );
    edit();
    accept();
    await screen.findByRole('button', { name: 'Use mine' });
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));
    expect(target()).toBe(changed.currentTmTextUnitVariant?.content);
    expect(screen.queryByRole('button', { name: 'Use mine' })).not.toBeInTheDocument();
    accept();
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
    expect(saveMock).toHaveBeenLastCalledWith(
      expect.objectContaining({
        target: changed.currentTmTextUnitVariant?.content,
        expectedCurrentTmTextUnitVariantId: changed.currentTmTextUnitVariant?.id,
        expectedReviewStateRevision: changed.reviewStateRevision,
      }),
    );
  });

  it('retains a failed draft through leaving the app route and ignores the obsolete request after return', async () => {
    const harness = mountWorkflow();
    const pending = deferred<ApiReviewProjectTextUnit>();
    saveMock.mockImplementationOnce(() => pending.promise);
    edit();
    accept(true);
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    act(() => harness.navigate('/elsewhere'));
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
    await act(() =>
      Promise.resolve(pending.resolve(harness.responseFor(saveMock.mock.calls[0][0]))),
    );
    expect(target()).toBe(editedTarget);
    expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
    accept();
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
    expect(saveMock).toHaveBeenLastCalledWith(
      expect.objectContaining({
        target: editedTarget,
        expectedCurrentTmTextUnitVariantId: 301,
        expectedReviewStateRevision: originalRevision,
      }),
    );
  });

  it('keeps a comment edited during a state-only request and uses the saved revision next', async () => {
    const harness = mountWorkflow();
    const pending = deferred<ApiReviewProjectTextUnit>();
    const initialRow = harness.project.reviewProjectTextUnits[0];
    const decided = {
      ...initialRow,
      reviewStateRevision: 'state-only-revision',
      reviewProjectTextUnitDecision: {
        ...initialRow.reviewProjectTextUnitDecision,
        decisionState: 'DECIDED' as const,
        decisionTmTextUnitVariant: initialRow.currentTmTextUnitVariant,
      },
    };
    decisionStateMock.mockImplementationOnce(() => pending.promise);
    fireEvent.click(screen.getByRole('button', { name: 'Decided' }));
    await waitFor(() => expect(decisionStateMock).toHaveBeenCalledTimes(1));
    fireEvent.change(
      screen.getByPlaceholderText('Explain why you chose this translation (if not obvious).'),
      {
        target: { value: 'Comment typed during state save' },
      },
    );
    await act(() => Promise.resolve(pending.resolve(decided)));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled());
    expect(
      screen.getByPlaceholderText('Explain why you chose this translation (if not obvious).'),
    ).toHaveValue('Comment typed during state save');
    expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
    accept();
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    expect(saveMock).toHaveBeenLastCalledWith(
      expect.objectContaining({
        target: initialRow.currentTmTextUnitVariant?.content,
        comment: 'Comment typed during state save',
        expectedCurrentTmTextUnitVariantId: 301,
        expectedReviewStateRevision: 'state-only-revision',
      }),
    );
  });

  it.each([
    'Explain why you chose this translation (if not obvious).',
    'Explain why the baseline translation was bad (to improve AI translation).',
  ])('preserves a whitespace-only draft in %s through a remote update', async (placeholder) => {
    const harness = mountWorkflow();
    fireEvent.change(screen.getByPlaceholderText(placeholder), { target: { value: '   ' } });
    expect(screen.getByRole('button', { name: 'Reset' })).toBeEnabled();
    const changed = {
      ...harness.project,
      reviewProjectTextUnits: [
        externalRow(harness.project),
        ...harness.project.reviewProjectTextUnits.slice(1),
      ],
    };
    await act(() => harness.refresh(changed));
    expect(target()).toBe(
      harness.project.reviewProjectTextUnits[0].currentTmTextUnitVariant?.content,
    );
    expect(screen.getByPlaceholderText(placeholder)).toHaveValue('   ');
    accept();
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    expect(saveMock).toHaveBeenCalledWith(
      expect.objectContaining({ expectedReviewStateRevision: originalRevision }),
    );
  });

  it('does not lose a dirty draft when an outside update removes the row from the pending filter', async () => {
    const harness = mountWorkflow({ filter: '&state=PENDING' });
    edit();
    const changed = {
      ...harness.project,
      reviewProjectTextUnits: [
        externalRow(harness.project, true),
        ...harness.project.reviewProjectTextUnits.slice(1),
      ],
    };
    await act(() => harness.refresh(changed));
    expect(target()).toBe(editedTarget);
    expect(screen.getByTestId('review-location')).toHaveTextContent('tu=201');
    accept();
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    expect(saveMock).toHaveBeenCalledWith(
      expect.objectContaining({
        textUnitId: 101,
        target: editedTarget,
        expectedReviewStateRevision: originalRevision,
      }),
    );
  });
});
