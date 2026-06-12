import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { type ComponentProps } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as GlossariesApi from '../../api/glossaries';
import type { ApiReviewProjectDetail, ApiReviewProjectTextUnit } from '../../api/review-projects';
import type { ApiUserProfile } from '../../api/users';
import { UserContext } from '../../hooks/useUser';
import type { ReviewProjectMutationControls } from './review-project-mutations';
import { ReviewProjectPageView } from './ReviewProjectPageView';

const matchGlossaryTermsMock = vi.hoisted(() => vi.fn());
const fetchPrecomputedAiReviewMock = vi.hoisted(() => vi.fn());
const requestAiReviewMock = vi.hoisted(() => vi.fn());

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

beforeEach(() => {
  fetchPrecomputedAiReviewMock.mockReset();
  fetchPrecomputedAiReviewMock.mockResolvedValue(null);
  requestAiReviewMock.mockReset();
  requestAiReviewMock.mockResolvedValue({
    message: { role: 'assistant', content: 'No issues found.' },
    suggestions: [],
    review: null,
  });
  matchGlossaryTermsMock.mockReset();
  matchGlossaryTermsMock.mockResolvedValue({ matchedTerms: [] });
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

function renderReviewProjectPageViewNode(
  props: ReviewProjectPageViewProps,
  queryClient: QueryClient,
) {
  return (
    <QueryClientProvider client={queryClient}>
      <UserContext.Provider value={user}>
        <MemoryRouter>
          <ReviewProjectPageView {...props} />
        </MemoryRouter>
      </UserContext.Provider>
    </QueryClientProvider>
  );
}

describe('ReviewProjectPageView', () => {
  it('delays the visible saving indicator for text-unit saves', async () => {
    const { container } = renderReviewProjectPageView({
      mutations: buildMutations({
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

  it('uses precomputed AI review when available and skips the live request', async () => {
    fetchPrecomputedAiReviewMock.mockResolvedValue({
      message: { role: 'assistant', content: 'Cached review.' },
      suggestions: [
        {
          content: 'Pague {price} agora',
          confidenceLevel: 90,
          explanation: 'Cached suggestion.',
        },
      ],
      review: {
        score: 80,
        explanation: 'Cached score.',
      },
    });

    renderReviewProjectPageView();

    await waitFor(() => {
      expect(fetchPrecomputedAiReviewMock).toHaveBeenCalledTimes(1);
    });
    const [variantId, options] = fetchPrecomputedAiReviewMock.mock.calls[0] as unknown as [
      number,
      { signal?: AbortSignal },
    ];
    expect(variantId).toBe(30);
    expect(options.signal).toBeInstanceOf(AbortSignal);
    expect(await screen.findByText('Cached score.')).toBeInTheDocument();
    expect(screen.getByText('Pague {price} agora')).toBeInTheDocument();
    expect(requestAiReviewMock).not.toHaveBeenCalled();
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

  it('falls back to live AI review when precomputed lookup fails', async () => {
    fetchPrecomputedAiReviewMock.mockRejectedValue(new Error('Cache unavailable'));
    requestAiReviewMock.mockResolvedValue({
      message: { role: 'assistant', content: 'Live review after cache miss.' },
      suggestions: [],
      review: null,
    });

    renderReviewProjectPageView();

    await waitFor(() => {
      expect(fetchPrecomputedAiReviewMock).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(requestAiReviewMock).toHaveBeenCalledTimes(1);
    });
    expect(await screen.findByText('Live review after cache miss.')).toBeInTheDocument();
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
    const queryClient = new QueryClient({
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

  it('aborts the precomputed AI review lookup when the selected text unit changes', async () => {
    fetchPrecomputedAiReviewMock.mockImplementation(() => new Promise(() => undefined));
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
    const queryClient = new QueryClient({
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
      expect(fetchPrecomputedAiReviewMock).toHaveBeenCalledTimes(1);
    });
    const [initialVariantId, initialOptions] = fetchPrecomputedAiReviewMock.mock
      .calls[0] as unknown as [number, { signal?: AbortSignal }];
    expect(initialVariantId).toBe(30);
    const initialSignal = initialOptions.signal;
    expect(initialSignal).toBeDefined();
    expect(initialSignal?.aborted).toBe(false);

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
      expect(initialSignal?.aborted).toBe(true);
    });
    await waitFor(() => {
      expect(fetchPrecomputedAiReviewMock).toHaveBeenCalledTimes(2);
    });
    const [nextVariantId, nextOptions] = fetchPrecomputedAiReviewMock.mock.calls[1] as unknown as [
      number,
      { signal?: AbortSignal },
    ];
    expect(nextVariantId).toBe(31);
    expect(nextOptions.signal?.aborted).toBe(false);
    expect(requestAiReviewMock).not.toHaveBeenCalled();
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
    const queryClient = new QueryClient({
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
    fireEvent.change(screen.getByPlaceholderText('Ask AI for a suggestion'), {
      target: { value: 'Can you improve it?' },
    });
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
