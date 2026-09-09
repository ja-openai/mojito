import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { flushSync } from 'react-dom';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type { AiReviewRequest } from '../../api/ai-review';
import type { ApiReviewProjectDetail } from '../../api/review-projects';
import type { ApiUserProfile } from '../../api/users';
import { UserContext } from '../../hooks/useUser';
import { userPreferencesQueryKey } from '../../hooks/useUserPreferences';
import { buildCarryoverProject, carryoverFixtures } from './review-project-carryover.fixtures';
import type { ReviewProjectMutationControls } from './review-project-mutations';
import { ReviewProjectPageView } from './ReviewProjectPageView';

const requestAiReviewMock = vi.hoisted(() => vi.fn());

vi.mock('../../api/ai-review', () => ({
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
  requestAiReviewMock.mockReset();
});

function renderProject(project: ApiReviewProjectDetail) {
  const onRequestSaveDecision = vi.fn();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(userPreferencesQueryKey(user.username), {
    initialized: true,
    worksetSize: null,
    preferredLocales: [],
    shortcutHelp: null,
    visibleTextEditorEnabled: false,
    reviewProjectSearchEnabled: false,
    defaultReviewTeamIds: [],
    aiReviewProfile: 'version_b',
    aiReviewAutomaticDisabled: false,
  });
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
      const firstTmTextUnitId = project.reviewProjectTextUnits[0].tmTextUnit!.id;
      const previousSuggestion = fixture.predecessor.target.replace(/\.$/, '');
      requestAiReviewMock.mockImplementation((request: AiReviewRequest) =>
        request.tmTextUnitId === firstTmTextUnitId && request.target === fixture.predecessor.target
          ? Promise.resolve(reviewResponse('First row review', previousSuggestion))
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

  it('ignores a previous-row review response arriving after the new row review', async () => {
    const fixture = carryoverFixtures[0];
    const project = buildCarryoverProject(fixture);
    const firstTmTextUnitId = project.reviewProjectTextUnits[0].tmTextUnit!.id;
    // Keep the proposed target different from the current draft so its Use button is shown.
    const nextDraft = fixture.next.target.replace(/\.$/, '');
    project.reviewProjectTextUnits[1].currentTmTextUnitVariant!.content = nextDraft;
    let resolvePreviousReview!: (response: ReturnType<typeof reviewResponse>) => void;
    const previousReview = new Promise<ReturnType<typeof reviewResponse>>((resolve) => {
      resolvePreviousReview = resolve;
    });
    requestAiReviewMock.mockImplementation((request: AiReviewRequest) => {
      if (
        request.tmTextUnitId === firstTmTextUnitId &&
        request.target === fixture.predecessor.target
      ) {
        return previousReview;
      }
      if (request.tmTextUnitId === fixture.next.tmTextUnitId && request.target === nextDraft) {
        return Promise.resolve(reviewResponse('Current row review', fixture.next.target));
      }
      return new Promise<never>(() => {});
    });
    const onRequestSaveDecision = renderProject(project);
    await waitFor(() =>
      expect(requestAiReviewMock).toHaveBeenCalledWith(
        expect.objectContaining({
          tmTextUnitId: firstTmTextUnitId,
          target: fixture.predecessor.target,
        }),
        expect.any(Object),
      ),
    );
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
