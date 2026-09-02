import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { flushSync } from 'react-dom';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as GlossariesApi from '../../api/glossaries';
import type * as ReviewProjectsApi from '../../api/review-projects';
import type { ApiReviewProjectTextUnit } from '../../api/review-projects';
import { REVIEW_PROJECT_DETAIL_QUERY_KEY } from '../../hooks/useReviewProjectDetail';
import { UserContext } from '../../hooks/useUser';
import {
  buildCarryoverProject,
  type CarryoverFixture,
  carryoverFixtures,
} from './review-project-carryover.fixtures';
import { useReviewProjectMutations } from './review-project-mutations';
import { ReviewProjectPageView } from './ReviewProjectPageView';

const saveMock = vi.hoisted(() =>
  vi.fn<typeof ReviewProjectsApi.saveReviewProjectTextUnitDecision>(),
);
const assistedMock = vi.hoisted(() => vi.fn(() => true));

vi.mock('../../api/review-projects', async (importActual) => ({
  ...(await importActual<typeof ReviewProjectsApi>()),
  saveReviewProjectTextUnitDecision: saveMock,
}));
vi.mock('../../api/glossaries', async (importActual) => ({
  ...(await importActual<typeof GlossariesApi>()),
  matchGlossaryTerms: vi.fn().mockResolvedValue({ matchedTerms: [] }),
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
// Keep the real row components and selection handlers; JSDOM has no viewport.
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
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', {
    configurable: true,
    value: vi.fn(),
  });
  Object.defineProperty(Range.prototype, 'getBoundingClientRect', {
    configurable: true,
    value: () => ({ top: 0, left: 0, bottom: 0, right: 0, height: 0, width: 0 }),
  });
  Object.defineProperty(Range.prototype, 'getClientRects', {
    configurable: true,
    value: () => [],
  });
});

beforeEach(() => {
  saveMock.mockReset();
  assistedMock.mockReturnValue(true);
});

type SaveRequest = Parameters<typeof ReviewProjectsApi.saveReviewProjectTextUnitDecision>[0];

function mountProject(fixture: CarryoverFixture, { deferSaves = false } = {}) {
  const project = buildCarryoverProject(fixture);
  const queryKey = [...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id];
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  queryClient.setQueryData(queryKey, project);
  const pending: Array<() => void> = [];
  saveMock.mockImplementation((request: SaveRequest) => {
    const row = project.reviewProjectTextUnits.find((unit) => unit.id === request.textUnitId)!;
    const variant = {
      id: 90000000 + pending.length,
      content: request.target,
      status: request.status,
      includedInLocalizedFile: request.includedInLocalizedFile,
      comment: request.comment ?? null,
    };
    const response: ApiReviewProjectTextUnit = {
      ...row,
      currentTmTextUnitVariant: variant,
      reviewProjectTextUnitDecision: {
        decisionState: request.decisionState,
        notes: request.decisionNotes ?? null,
        decisionTmTextUnitVariant: variant,
      },
    };
    return new Promise<ApiReviewProjectTextUnit>((resolve) => {
      pending.push(() => resolve(response));
      if (!deferSaves) resolve(response);
    });
  });

  function LivePage({ selectedId }: { selectedId: number | null }) {
    const { data } = useQuery({ queryKey, queryFn: () => Promise.resolve(project) });
    const mutations = useReviewProjectMutations(project.id);
    return (
      <ReviewProjectPageView
        projectId={project.id}
        project={data ?? null}
        mutations={mutations}
        selectedTextUnitQueryId={selectedId}
        onSelectedTextUnitIdChange={() => {}}
        openRequestDetailsQuery={false}
        requestDetailsSource={null}
        onRequestDetailsQueryHandled={() => {}}
        onRequestDetailsFlowFinished={() => {}}
      />
    );
  }

  function node(selectedId: number | null) {
    return (
      <QueryClientProvider client={queryClient}>
        <UserContext.Provider
          value={{
            username: 'fixture-reviewer',
            role: 'ROLE_TRANSLATOR',
            canTranslateAllLocales: true,
            userLocales: [],
          }}
        >
          <MemoryRouter>
            <LivePage selectedId={selectedId} />
          </MemoryRouter>
        </UserContext.Provider>
      </QueryClientProvider>
    );
  }
  const result = render(node(null));
  return {
    ...result,
    pending,
    queryClient,
    selectByUrl: () => result.rerender(node(fixture.next.tmTextUnitId)),
  };
}

function editorTarget() {
  const editor = screen.getByRole('textbox', { name: 'Translation' });
  return editor instanceof HTMLTextAreaElement ? editor.value : editor.textContent;
}

function accept({ advance = false } = {}) {
  fireEvent.keyDown(window, { key: 'Enter', ctrlKey: true, shiftKey: advance });
}

function selectNextWithMouse(container: HTMLElement) {
  const rows = container.querySelectorAll('.review-project-row');
  expect(rows).toHaveLength(2);
  fireEvent.click(rows[1]);
}

async function waitForSavedRow(container: HTMLElement) {
  await waitFor(() => {
    expect(container.querySelector('.review-project-row__decided-dot')).not.toBeNull();
    // Accept is disabled both while saving and after acceptance. Wait for the
    // saved-row snapshot and completed saving state before navigating.
    expect(screen.getByRole('button', { name: 'Decided' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Pending' })).toBeEnabled();
  });
}

function expectNextPayload(fixture: CarryoverFixture) {
  expect(saveMock).toHaveBeenLastCalledWith(
    expect.objectContaining({
      textUnitId: fixture.next.id,
      target: fixture.next.target,
      expectedCurrentTmTextUnitVariantId: fixture.next.variantId,
      status: 'APPROVED',
      decisionState: 'DECIDED',
    }),
  );
}

function replaceTranslation(target: string) {
  const editor = screen.getByRole('textbox', { name: 'Translation' });
  if (editor instanceof HTMLTextAreaElement) {
    fireEvent.change(editor, { target: { value: target } });
    return;
  }
  editor.focus();
  fireEvent.keyDown(editor, { key: 'a', code: 'KeyA', ctrlKey: true });
  fireEvent.paste(editor, { clipboardData: { getData: () => target } });
}

describe.each(carryoverFixtures)('Review Project $projectId carryover matrix', (fixture) => {
  describe.each([true, false])('assisted editor = %s', (assisted) => {
    it.each(['keyboard', 'mouse', 'URL'] as const)(
      'keeps row and target together when %s selection and Accept share a scheduling boundary',
      async (selection) => {
        assistedMock.mockReturnValue(assisted);
        const harness = mountProject(fixture);
        expect(editorTarget()).toBe(fixture.predecessor.target);
        act(() => {
          flushSync(() => {
            if (selection === 'keyboard') fireEvent.keyDown(window, { key: 'ArrowDown' });
            if (selection === 'mouse') selectNextWithMouse(harness.container);
            if (selection === 'URL') harness.selectByUrl();
          });
          accept();
        });
        await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
        // URL selection runs through an effect. An Accept dispatched before that
        // effect may still save A; the request must never combine A and B.
        if (selection === 'URL' && saveMock.mock.calls[0][0].textUnitId === -1) {
          expect(saveMock).toHaveBeenLastCalledWith(
            expect.objectContaining({ textUnitId: -1, target: fixture.predecessor.target }),
          );
        } else {
          expectNextPayload(fixture);
        }
      },
    );

    it('keeps targets separate in deliberate mouse review', async () => {
      assistedMock.mockReturnValue(assisted);
      const harness = mountProject(fixture);
      fireEvent.click(screen.getByRole('button', { name: 'Accept' }));
      await waitForSavedRow(harness.container);
      selectNextWithMouse(harness.container);
      await waitFor(() => expect(editorTarget()).toBe(fixture.next.target));
      fireEvent.click(screen.getByRole('button', { name: 'Accept' }));
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
      expectNextPayload(fixture);
    });

    it('waits for a delayed save before advancing and saving the next target', async () => {
      assistedMock.mockReturnValue(assisted);
      const harness = mountProject(fixture, { deferSaves: true });
      accept({ advance: true });
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
      expect(saveMock).toHaveBeenLastCalledWith(
        expect.objectContaining({ textUnitId: -1, target: fixture.predecessor.target }),
      );
      fireEvent.keyDown(window, { key: 'ArrowDown' });
      selectNextWithMouse(harness.container);
      accept({ advance: true });
      expect(editorTarget()).toBe(fixture.predecessor.target);
      expect(saveMock).toHaveBeenCalledTimes(1);
      await act(() => Promise.resolve(harness.pending[0]()));
      await waitFor(() => expect(editorTarget()).toBe(fixture.next.target));
      accept();
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
      expectNextPayload(fixture);
      await act(() => Promise.resolve(harness.pending[1]()));
    });

    it('keeps a delayed previous-row response separate after URL navigation', async () => {
      assistedMock.mockReturnValue(assisted);
      const harness = mountProject(fixture, { deferSaves: true });
      accept();
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
      harness.selectByUrl();
      await waitFor(() => expect(editorTarget()).toBe(fixture.next.target));
      accept();
      expect(saveMock).toHaveBeenCalledTimes(1);
      await act(() => Promise.resolve(harness.pending[0]()));
      await waitFor(() => expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled());
      expect(editorTarget()).toBe(fixture.next.target);
      accept();
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
      expectNextPayload(fixture);
      await act(() => Promise.resolve(harness.pending[1]()));
    });

    it('does not carry an edited target through Accept and advance', async () => {
      assistedMock.mockReturnValue(assisted);
      mountProject(fixture);
      const originalEditor = screen.getByRole('textbox', { name: 'Translation' });
      const editedTarget = `${fixture.predecessor.target} (સમીક્ષિત)`;
      replaceTranslation(editedTarget);
      await waitFor(() => expect(editorTarget()).toBe(editedTarget));
      accept({ advance: true });
      await waitFor(() => expect(editorTarget()).toBe(fixture.next.target));
      expect(screen.getByRole('textbox', { name: 'Translation' })).not.toBe(originalEditor);
      expect(saveMock).toHaveBeenLastCalledWith(
        expect.objectContaining({ textUnitId: -1, target: editedTarget }),
      );
      // Simulate returning from a background tab after the transition settled.
      fireEvent(window, new Event('blur'));
      fireEvent(document, new Event('visibilitychange'));
      fireEvent(window, new Event('focus'));
      accept();
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
      expectNextPayload(fixture);
    });

    it('suppresses overlapping Accept requests before React exposes saving state', async () => {
      assistedMock.mockReturnValue(assisted);
      const harness = mountProject(fixture, { deferSaves: true });
      act(() => {
        accept();
        accept();
        accept({ advance: true });
      });
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
      expect(saveMock).toHaveBeenLastCalledWith(
        expect.objectContaining({ textUnitId: -1, target: fixture.predecessor.target }),
      );
      await act(() => Promise.resolve(harness.pending[0]()));
      await waitForSavedRow(harness.container);
      expect(editorTarget()).toBe(fixture.predecessor.target);
      // An overlapping request did not start an operation or add an advance intent.
      // A deliberate shortcut after the original save can now move to the next row.
      accept({ advance: true });
      await waitFor(() => expect(editorTarget()).toBe(fixture.next.target));
      accept();
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
      expectNextPayload(fixture);
      await act(() => Promise.resolve(harness.pending[1]()));
    });

    it('keeps a conflict retry on its original row before reviewing the next row', async () => {
      assistedMock.mockReturnValue(assisted);
      const harness = mountProject(fixture);
      const predecessor = buildCarryoverProject(fixture).reviewProjectTextUnits[0];
      const conflict = Object.assign(new Error('Current variant changed'), {
        status: 409,
        data: {
          ...predecessor,
          currentTmTextUnitVariant: {
            ...predecessor.currentTmTextUnitVariant,
            id: 80000000,
          },
        },
      });
      saveMock.mockRejectedValueOnce(conflict);
      accept();
      fireEvent.click(await screen.findByRole('button', { name: 'Use mine' }));
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
      expect(saveMock).toHaveBeenLastCalledWith(
        expect.objectContaining({
          textUnitId: -1,
          target: fixture.predecessor.target,
          expectedCurrentTmTextUnitVariantId: 80000000,
          overrideChangedCurrent: false,
        }),
      );
      await waitForSavedRow(harness.container);
      selectNextWithMouse(harness.container);
      await waitFor(() => expect(editorTarget()).toBe(fixture.next.target));
      accept();
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(3));
      expectNextPayload(fixture);
    });

    it('keeps the selected row target when project detail refetches after resume', async () => {
      assistedMock.mockReturnValue(assisted);
      const harness = mountProject(fixture);
      accept({ advance: true });
      await waitFor(() => expect(editorTarget()).toBe(fixture.next.target));
      fireEvent(window, new Event('blur'));
      // The refetch returns the original fixture, including the previous row's
      // old snapshot, after the cache has already received its save response.
      await act(() =>
        harness.queryClient.refetchQueries({
          queryKey: [...REVIEW_PROJECT_DETAIL_QUERY_KEY, fixture.projectId],
        }),
      );
      fireEvent(window, new Event('focus'));
      expect(editorTarget()).toBe(fixture.next.target);
      accept();
      await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
      expectNextPayload(fixture);
    });
  });
});
