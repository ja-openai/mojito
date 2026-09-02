import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as GlossariesApi from '../../api/glossaries';
import type * as ReviewProjectsApi from '../../api/review-projects';
import type { ApiReviewProjectDetail, ApiReviewProjectTextUnit } from '../../api/review-projects';
import { REVIEW_PROJECT_DETAIL_QUERY_KEY } from '../../hooks/useReviewProjectDetail';
import { UserContext } from '../../hooks/useUser';
import { ReviewProjectPage } from './ReviewProjectPage';

const clients = new Set<QueryClient>();
const metadataMock = vi.hoisted(() =>
  vi.fn<typeof ReviewProjectsApi.updateReviewProjectTextUnitTerminologyMetadata>(),
);
const fetchMock = vi.hoisted(() => vi.fn<typeof ReviewProjectsApi.fetchReviewProjectDetail>());
const feedbackMock = vi.hoisted(() =>
  vi.fn<typeof ReviewProjectsApi.saveReviewProjectTextUnitTerminologyFeedback>(),
);
const resolutionMock = vi.hoisted(() =>
  vi.fn<typeof ReviewProjectsApi.saveReviewProjectTextUnitTerminologyResolution>(),
);

vi.mock('../../api/review-projects', async (importActual) => ({
  ...(await importActual<typeof ReviewProjectsApi>()),
  fetchReviewProjectDetail: fetchMock,
  updateReviewProjectTextUnitTerminologyMetadata: metadataMock,
  saveReviewProjectTextUnitTerminologyFeedback: feedbackMock,
  saveReviewProjectTextUnitTerminologyResolution: resolutionMock,
}));
vi.mock('../../api/glossaries', async (importActual) => ({
  ...(await importActual<typeof GlossariesApi>()),
  matchGlossaryTerms: vi.fn().mockResolvedValue({ matchedTerms: [] }),
}));
vi.mock('../../api/ai-review', () => ({
  fetchPrecomputedAiReview: vi.fn().mockResolvedValue(null),
  formatAiReviewError: () => ({ message: 'Fixture AI error', detail: null }),
  requestAiReview: vi.fn().mockResolvedValue({
    message: { role: 'assistant', content: 'No issues.' },
    suggestions: [],
    review: null,
  }),
}));
vi.mock('../../hooks/useVisibleTextEditorEnabled', () => ({
  useVisibleTextEditorEnabled: () => false,
}));
vi.mock('../../components/virtual/useVirtualRows', () => ({
  useVirtualRows: ({ count }: { count: number }) => ({
    scrollRef: { current: null },
    virtualizer: {},
    items: Array.from({ length: count }, (_, index) => ({
      index,
      key: index,
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
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', { configurable: true, value: vi.fn() });
});
beforeEach(() => {
  fetchMock.mockReset();
  feedbackMock.mockReset();
  resolutionMock.mockReset();
  metadataMock.mockReset();
});
afterEach(() => {
  clients.forEach((client) => client.clear());
  clients.clear();
});

function row(id = 101): ApiReviewProjectTextUnit {
  return {
    id,
    reviewStateRevision: `revision-${id}`,
    tmTextUnit: { id: id + 100, name: `term.${id}`, content: `Term ${id}`, asset: null },
    baselineTmTextUnitVariant: {
      id: id + 200,
      content: 'Term target',
      status: 'REVIEW_NEEDED',
      includedInLocalizedFile: true,
    },
    currentTmTextUnitVariant: null,
    reviewProjectTextUnitDecision: {
      decisionState: 'PENDING',
      notes: 'Saved decision notes',
      decisionTmTextUnitVariant: null,
    },
    terminologyFeedbacks: [
      {
        id: 1,
        recommendation: 'APPROVE',
        confidence: 3,
        notes: 'Saved recommendation notes',
        reviewerUsername: 'reviewer',
      },
    ],
    terminologyTerm: {
      glossaryId: 42,
      metadataId: id,
      tmTextUnitId: id + 100,
      source: `Term ${id}`,
      status: 'CANDIDATE',
      definition: 'Saved definition',
      termType: 'GENERAL',
      enforcement: 'SOFT',
    },
  };
}

function project(
  phase: ApiReviewProjectDetail['terminologyPhase'] = 'SPECIALIST_INPUT',
): ApiReviewProjectDetail {
  return {
    id: 7,
    type: 'TERMINOLOGY',
    terminologyPhase: phase,
    status: 'OPEN',
    locale: { id: 3, bcp47Tag: 'fr' },
    reviewProjectRequest: { id: 70, name: 'Terms', screenshotImageIds: [] },
    reviewProjectTextUnits: [row(), row(102)],
  };
}

function mount(initial = project()) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retryDelay: 0 } },
  });
  clients.add(client);
  const key = [...REVIEW_PROJECT_DETAIL_QUERY_KEY, initial.id];
  client.setQueryData(key, initial);
  fetchMock.mockResolvedValue(initial);
  function Navigation() {
    const navigate = useNavigate();
    return (
      <>
        <button onClick={() => void navigate('/elsewhere')}>Leave test page</button>
        <button onClick={() => void navigate(-1)}>Return test page</button>
      </>
    );
  }
  const node = () => (
    <QueryClientProvider client={client}>
      <UserContext.Provider
        value={{
          username: 'reviewer',
          role: 'ROLE_PM',
          canTranslateAllLocales: true,
          userLocales: [],
        }}
      >
        <MemoryRouter initialEntries={['/review-projects/7?tu=201']}>
          <Navigation />
          <Routes>
            <Route path="/review-projects/:projectId" element={<ReviewProjectPage />} />
            <Route path="/elsewhere" element={<p>Other page</p>} />
          </Routes>
        </MemoryRouter>
      </UserContext.Provider>
    </QueryClientProvider>
  );
  const result = render(node());
  return {
    ...result,
    refresh: (next: ApiReviewProjectDetail) => {
      fetchMock.mockResolvedValue(next);
      client.setQueryData(key, next);
      result.rerender(node());
    },
  };
}

const recommendationPlaceholder =
  'Add source-term context, risks, or why this should not be a glossary term.';
const decisionPlaceholder = 'Record why this final glossary decision was made.';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe('Review Project terminology adversarial workflows', () => {
  it('preserves dirty recommendation notes when an unrelated row update arrives', () => {
    const initial = project();
    const view = mount(initial);
    fireEvent.change(screen.getByPlaceholderText(recommendationPlaceholder), {
      target: { value: 'Unsent advisor reasoning' },
    });
    view.refresh({
      ...initial,
      reviewProjectTextUnits: [
        {
          ...row(),
          reviewStateRevision: 'new-row-revision',
          reviewProjectTextUnitSuggestion: {
            id: 900,
            target: 'Staged target',
            source: 'FIND_REPLACE',
          },
        },
        row(102),
      ],
    });
    expect(screen.getByPlaceholderText(recommendationPlaceholder)).toHaveValue(
      'Unsent advisor reasoning',
    );
  });

  it('preserves dirty decision notes when a concurrent decision update arrives', () => {
    const initial = project('PM_RESOLUTION');
    const view = mount(initial);
    fireEvent.change(screen.getByPlaceholderText(decisionPlaceholder), {
      target: { value: 'Unsent final reasoning' },
    });
    view.refresh({
      ...initial,
      reviewProjectTextUnits: [
        {
          ...row(),
          reviewStateRevision: 'new-row-revision',
          reviewProjectTextUnitDecision: {
            decisionState: 'DECIDED',
            notes: 'Someone else decided',
            decisionTmTextUnitVariant: null,
          },
        },
        row(102),
      ],
    });
    expect(screen.getByPlaceholderText(decisionPlaceholder)).toHaveValue('Unsent final reasoning');
  });

  it('preserves dirty term metadata when remote metadata changes', () => {
    const initial = project();
    const view = mount(initial);
    fireEvent.click(screen.getByRole('button', { name: 'Edit details' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Definition' }), {
      target: { value: 'Unsent definition' },
    });
    view.refresh({
      ...initial,
      reviewProjectTextUnits: [
        {
          ...row(),
          terminologyTerm: { ...row().terminologyTerm, definition: 'External definition' },
        },
        row(102),
      ],
    });
    expect(screen.getByRole('textbox', { name: 'Definition' })).toHaveValue('Unsent definition');
  });

  it('retains newer recommendation edits when an earlier feedback request succeeds', async () => {
    mount();
    const pending = deferred<ApiReviewProjectTextUnit>();
    feedbackMock.mockReturnValue(pending.promise);
    fireEvent.change(screen.getByPlaceholderText(recommendationPlaceholder), {
      target: { value: 'Submitted notes' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save notes' }));
    await waitFor(() => expect(feedbackMock).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByPlaceholderText(recommendationPlaceholder), {
      target: { value: 'Newer unsaved notes' },
    });
    await act(async () => {
      pending.resolve({
        ...row(),
        terminologyFeedbacks: [{ ...row().terminologyFeedbacks![0], notes: 'Submitted notes' }],
      });
      await pending.promise;
    });
    expect(screen.getByPlaceholderText(recommendationPlaceholder)).toHaveValue(
      'Newer unsaved notes',
    );
  });

  it('restores a dirty recommendation after leaving and returning to the route', () => {
    mount();
    fireEvent.change(screen.getByPlaceholderText(recommendationPlaceholder), {
      target: { value: 'Unsent advisor reasoning' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Leave test page' }));
    fireEvent.click(screen.getByRole('button', { name: 'Return test page' }));
    expect(screen.getByPlaceholderText(recommendationPlaceholder)).toHaveValue(
      'Unsent advisor reasoning',
    );
  });
  it('adopts updated recommendation notes when the local form is clean', () => {
    const initial = project();
    const view = mount(initial);
    view.refresh({
      ...initial,
      reviewProjectTextUnits: [
        {
          ...row(),
          terminologyFeedbacks: [
            { ...row().terminologyFeedbacks![0], notes: 'Updated saved recommendation' },
          ],
        },
        row(102),
      ],
    });
    expect(screen.getByPlaceholderText(recommendationPlaceholder)).toHaveValue(
      'Updated saved recommendation',
    );
    expect(screen.getByRole('button', { name: 'Save notes' })).toBeDisabled();
  });

  it('acknowledges saved PM notes and keeps a subsequent edit as a separate draft', async () => {
    mount(project('PM_RESOLUTION'));
    const pending = deferred<ApiReviewProjectTextUnit>();
    resolutionMock.mockReturnValue(pending.promise);
    fireEvent.change(screen.getByPlaceholderText(decisionPlaceholder), {
      target: { value: 'Submitted final notes' },
    });
    fireEvent.keyDown(window, { key: 'Enter', ctrlKey: true });
    await waitFor(() => expect(resolutionMock).toHaveBeenCalledTimes(1));
    expect(resolutionMock).toHaveBeenCalledWith(
      expect.objectContaining({ notes: 'Submitted final notes' }),
    );
    await act(async () => {
      pending.resolve({
        ...row(),
        reviewProjectTextUnitDecision: {
          decisionState: 'DECIDED',
          notes: 'Submitted final notes',
          decisionTmTextUnitVariant: null,
        },
      });
      await pending.promise;
    });
    expect(screen.getByPlaceholderText(decisionPlaceholder)).toHaveValue('Submitted final notes');
    const unload = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(unload);
    expect(unload.defaultPrevented).toBe(false);
    fireEvent.change(screen.getByPlaceholderText(decisionPlaceholder), {
      target: { value: 'Newer final notes' },
    });
    expect(screen.getByPlaceholderText(decisionPlaceholder)).toHaveValue('Newer final notes');
  });

  it('keeps newer metadata after a save, restores it after navigation, and explicitly cancels it', async () => {
    mount();
    const pending = deferred<ApiReviewProjectTextUnit>();
    metadataMock.mockReturnValue(pending.promise);
    fireEvent.click(screen.getByRole('button', { name: 'Edit details' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Definition' }), {
      target: { value: 'Submitted definition' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save term details' }));
    await waitFor(() => expect(metadataMock).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByRole('textbox', { name: 'Definition' }), {
      target: { value: 'Newer definition' },
    });
    await act(async () => {
      pending.resolve({
        ...row(),
        terminologyTerm: { ...row().terminologyTerm, definition: 'Submitted definition' },
      });
      await pending.promise;
    });
    expect(screen.getByRole('textbox', { name: 'Definition' })).toHaveValue('Newer definition');
    fireEvent.click(screen.getByRole('button', { name: 'Leave test page' }));
    fireEvent.click(screen.getByRole('button', { name: 'Return test page' }));
    expect(screen.getByRole('textbox', { name: 'Definition' })).toHaveValue('Newer definition');
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit details' }));
    expect(screen.getByRole('textbox', { name: 'Definition' })).toHaveValue('Submitted definition');
    expect(screen.getByRole('button', { name: 'Save term details' })).toBeDisabled();
  });

  it('includes metadata and advisor edits in row discard and never resurrects discarded work', async () => {
    mount();
    fireEvent.click(screen.getByRole('button', { name: 'Edit details' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Definition' }), {
      target: { value: 'Discarded definition' },
    });
    fireEvent.change(screen.getByPlaceholderText(recommendationPlaceholder), {
      target: { value: 'Discarded advisor notes' },
    });
    fireEvent.click(screen.getByText('Term 102'));
    fireEvent.click(await screen.findByRole('button', { name: 'Discard changes' }));
    fireEvent.click(screen.getByText('Term 101'));
    expect(screen.getByPlaceholderText(recommendationPlaceholder)).toHaveValue(
      'Saved recommendation notes',
    );
    fireEvent.click(screen.getByRole('button', { name: 'Edit details' }));
    expect(screen.getByRole('textbox', { name: 'Definition' })).toHaveValue('Saved definition');
    const unload = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(unload);
    expect(unload.defaultPrevented).toBe(false);
  });
});
