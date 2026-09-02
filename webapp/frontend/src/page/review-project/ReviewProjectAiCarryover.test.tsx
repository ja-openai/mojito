import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { flushSync } from 'react-dom';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ApiReviewProjectDetail } from '../../api/review-projects';
import type { ApiUserProfile } from '../../api/users';
import { UserContext } from '../../hooks/useUser';
import { buildCarryoverProject, carryoverFixtures } from './review-project-carryover.fixtures';
import type { ReviewProjectMutationControls } from './review-project-mutations';
import { ReviewProjectPageView } from './ReviewProjectPageView';

const fetchPrecomputedAiReviewMock = vi.hoisted(() => vi.fn());
const requestAiReviewMock = vi.hoisted(() => vi.fn());

vi.mock('../../api/ai-review', () => ({
  fetchPrecomputedAiReview: fetchPrecomputedAiReviewMock,
  formatAiReviewError: (error: unknown) => ({ message: String(error), detail: null }),
  requestAiReview: requestAiReviewMock,
}));

vi.mock('../../components/virtual/useVirtualRows', () => ({
  useVirtualRows: () => ({
    scrollRef: { current: null },
    virtualizer: {},
    items: [0, 1].map((index) => ({
      index,
      key: index,
      start: index * 100,
      end: (index + 1) * 100,
      size: 100,
      lane: 0,
    })),
    totalSize: 200,
    scrollToIndex: vi.fn(),
    measureElement: vi.fn(),
  }),
}));

vi.mock('../../hooks/useVisibleTextEditorEnabled', () => ({
  useVisibleTextEditorEnabled: () => true,
}));

const user: ApiUserProfile = {
  username: 'translator',
  role: 'ROLE_TRANSLATOR',
  canTranslateAllLocales: true,
  userLocales: [],
};

function buildMutations(
  onRequestSaveDecision: ReviewProjectMutationControls['onRequestSaveDecision'],
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
    onConfirmValidationSave: vi.fn(),
    onRetryValidationSave: vi.fn(),
    onDismissValidationSave: vi.fn(),
    onDiscardAction: vi.fn(),
    onUseConflictCurrent: vi.fn(),
    onOverwriteConflict: vi.fn(),
    onRequestSaveDecision,
    onRequestDecisionState: vi.fn(),
    onRequestTerminologyFeedback: vi.fn(),
    onRequestTerminologyMetadata: vi.fn(),
    onRequestTerminologyResolution: vi.fn(),
    onRequestProjectStatus: vi.fn(),
    onRequestProjectRequestUpdate: vi.fn().mockResolvedValue(undefined),
    onRequestProjectDueDateUpdate: vi.fn().mockResolvedValue(undefined),
    onRequestProjectAssignmentUpdate: vi.fn().mockResolvedValue(undefined),
  };
}

beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', { configurable: true, value: vi.fn() });
});

beforeEach(() => {
  fetchPrecomputedAiReviewMock.mockReset();
  requestAiReviewMock.mockReset();
});

function renderProject(project: ApiReviewProjectDetail) {
  const onRequestSaveDecision = vi.fn();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <UserContext.Provider value={user}>
        <MemoryRouter>
          <ReviewProjectPageView
            projectId={project.id}
            project={project}
            mutations={buildMutations(onRequestSaveDecision)}
            selectedTextUnitQueryId={null}
            onSelectedTextUnitIdChange={vi.fn()}
            openRequestDetailsQuery={false}
            requestDetailsSource={null}
            onRequestDetailsQueryHandled={vi.fn()}
            onRequestDetailsFlowFinished={vi.fn()}
          />
        </MemoryRouter>
      </UserContext.Provider>
    </QueryClientProvider>,
  );
  return onRequestSaveDecision;
}

function reviewResponse(content: string, target: string) {
  return {
    message: { role: 'assistant' as const, content },
    suggestions: [{ content: target, confidenceLevel: 100 }],
    review: null,
  };
}

describe('Review Project AI suggestion ownership', () => {
  it.each(carryoverFixtures)(
    'does not apply project $projectId previous-row suggestions during a selection commit',
    async (fixture) => {
      const project = buildCarryoverProject(fixture);
      const first = project.reviewProjectTextUnits[0];
      fetchPrecomputedAiReviewMock.mockImplementation((variantId: number) =>
        variantId === first.currentTmTextUnitVariant!.id
          ? Promise.resolve(reviewResponse('First row review', fixture.predecessor.target))
          : new Promise<never>(() => {}),
      );
      const onRequestSaveDecision = renderProject(project);
      const previousSuggestionButton = await screen.findByRole('button', { name: 'Use' });
      // Finish the row-selection commit, then deliver a click before the effect's
      // conversation reset renders. This is a deterministic scheduling boundary,
      // not a claim that these incidents used AI suggestions.
      act(() => {
        flushSync(() => fireEvent.keyDown(window, { key: 'ArrowDown' }));
        expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveTextContent(
          fixture.next.target,
        );
        fireEvent.click(previousSuggestionButton);
      });
      fireEvent.keyDown(window, { key: 'Enter', ctrlKey: true });

      expect(onRequestSaveDecision).toHaveBeenCalledExactlyOnceWith(
        expect.objectContaining({
          textUnitId: fixture.next.id,
          tmTextUnitId: fixture.next.tmTextUnitId,
          target: fixture.next.target,
          expectedCurrentTmTextUnitVariantId: fixture.next.variantId,
          status: 'APPROVED',
          decisionState: 'DECIDED',
        }),
      );
      expect(previousSuggestionButton).not.toBeInTheDocument();
    },
  );

  it('applies a current-row suggestion after retrying an unsuccessful review', async () => {
    const fixture = carryoverFixtures[0];
    const project = buildCarryoverProject(fixture);
    const target = 'સુધારેલ અનુવાદ';
    fetchPrecomputedAiReviewMock.mockResolvedValue(null);
    requestAiReviewMock
      .mockRejectedValueOnce(new Error('Review unavailable'))
      .mockResolvedValueOnce(reviewResponse('Retried current row review', target));
    const onRequestSaveDecision = renderProject(project);
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Use' }));
    fireEvent.keyDown(window, { key: 'Enter', ctrlKey: true });

    expect(onRequestSaveDecision).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        textUnitId: project.reviewProjectTextUnits[0].id,
        target,
      }),
    );
    expect(requestAiReviewMock).toHaveBeenCalledTimes(2);
  });

  it('ignores a previous-row precomputed response arriving after the new row review', async () => {
    const fixture = carryoverFixtures[0];
    const project = buildCarryoverProject(fixture);
    let resolvePreviousReview!: (response: ReturnType<typeof reviewResponse>) => void;
    fetchPrecomputedAiReviewMock
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolvePreviousReview = resolve;
          }),
      )
      .mockResolvedValueOnce(reviewResponse('Current row review', fixture.next.target));
    const onRequestSaveDecision = renderProject(project);
    await screen.findByRole('textbox', { name: 'Translation' });
    fireEvent.keyDown(window, { key: 'ArrowDown' });
    const currentSuggestionButton = await screen.findByRole('button', { name: 'Use' });
    await act(async () => {
      resolvePreviousReview(
        reviewResponse('Stale previous row review', fixture.predecessor.target),
      );
      await Promise.resolve();
    });
    expect(screen.queryByText('Stale previous row review')).not.toBeInTheDocument();
    fireEvent.click(currentSuggestionButton);
    fireEvent.keyDown(window, { key: 'Enter', ctrlKey: true });

    expect(onRequestSaveDecision).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        textUnitId: fixture.next.id,
        target: fixture.next.target,
      }),
    );
  });
});
