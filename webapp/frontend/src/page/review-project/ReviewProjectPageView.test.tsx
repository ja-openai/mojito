import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { type ComponentProps } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';

import type { ApiReviewProjectDetail, ApiReviewProjectTextUnit } from '../../api/review-projects';
import type { ApiUserProfile } from '../../api/users';
import { UserContext } from '../../hooks/useUser';
import type { ReviewProjectMutationControls } from './review-project-mutations';
import { ReviewProjectPageView } from './ReviewProjectPageView';

vi.mock('../../api/ai-review', () => ({
  formatAiReviewError: (error: unknown) => ({
    message: error instanceof Error ? error.message : 'Unable to run AI review.',
    detail: null,
  }),
  requestAiReview: vi.fn().mockResolvedValue({
    message: { role: 'assistant', content: 'No issues found.' },
    suggestions: [],
    review: null,
  }),
}));

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
  useVisibleTextEditorEnabled: () => true,
}));

type ReviewProjectPageViewProps = ComponentProps<typeof ReviewProjectPageView>;

const noop = vi.fn();

beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', {
    configurable: true,
    value: vi.fn(),
  });
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

function buildMutations(
  overrides: Partial<ReviewProjectMutationControls> = {},
): ReviewProjectMutationControls {
  return {
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

function renderReviewProjectPageView(overrides: Partial<ReviewProjectPageViewProps> = {}) {
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
      <UserContext.Provider value={user}>
        <MemoryRouter>
          <ReviewProjectPageView {...props} />
        </MemoryRouter>
      </UserContext.Provider>
    </QueryClientProvider>,
  );
}

describe('ReviewProjectPageView', () => {
  it('uses the assisted protected editor in the selected text unit detail pane', async () => {
    const { container } = renderReviewProjectPageView();

    expect(await screen.findByRole('textbox', { name: 'Translation' })).toHaveClass('ProseMirror');
    expect(screen.getByRole('button', { name: 'Hidden characters: Auto' })).toBeInTheDocument();
    expect(
      screen.getByRole('button', {
        name: 'Placeholder editing is off. Edit placeholders',
      }),
    ).toBeInTheDocument();
    expect(screen.queryByText('1 token found')).not.toBeInTheDocument();

    await waitFor(() => {
      const protectedToken = container.querySelector('.visible-text-editor__protected-token');
      expect(protectedToken).toHaveTextContent('{price}');
      expect(protectedToken).toHaveClass('visible-text-editor__protected-token--icu-placeholder');
    });
  });
});
