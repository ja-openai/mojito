import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { beforeEach, describe, expect, it } from 'vitest';

import type { ApiReviewProjectDetail, ApiReviewProjectTextUnit } from '../../api/review-projects';
import type { ApiUserProfile } from '../../api/users';
import { REVIEW_PROJECT_DETAIL_QUERY_KEY } from '../../hooks/useReviewProjectDetail';
import {
  REVIEW_PROJECT_REQUESTS_QUERY_KEY,
  REVIEW_PROJECTS_QUERY_KEY,
} from '../../hooks/useReviewProjects';
import { UserContext } from '../../hooks/useUser';
import {
  type PendingAction,
  shouldInvalidateGlossaryQueriesForAction,
  shouldPreflightIntegrityCheckForAction,
  useReviewProjectMutations,
} from './review-project-mutations';

const saveReviewProjectTextUnitDecisionMock = vi.hoisted(() => vi.fn());
const updateReviewProjectTextUnitTerminologyMetadataMock = vi.hoisted(() => vi.fn());
const checkTextUnitIntegrityWithRetryMock = vi.hoisted(() => vi.fn());

vi.mock('../../api/review-projects', () => ({
  saveReviewProjectTextUnitDecision: saveReviewProjectTextUnitDecisionMock,
  saveReviewProjectTextUnitTerminologyFeedback: vi.fn(),
  saveReviewProjectTextUnitTerminologyResolution: vi.fn(),
  setReviewProjectTextUnitDecisionState: vi.fn(),
  updateReviewProjectAssignment: vi.fn(),
  updateReviewProjectDueDate: vi.fn(),
  updateReviewProjectRequest: vi.fn(),
  updateReviewProjectStatus: vi.fn(),
  updateReviewProjectTextUnitTerminologyMetadata:
    updateReviewProjectTextUnitTerminologyMetadataMock,
}));

vi.mock('../../utils/integrityCheck', () => ({
  buildIntegrityCheckErrorReport: vi.fn(() => ({
    reportMessage: 'Integrity report message',
    reportHtml: '<div>Integrity report message</div>',
  })),
  checkTextUnitIntegrityWithRetry: checkTextUnitIntegrityWithRetryMock,
  INTEGRITY_CHECK_FAILURE_MESSAGE:
    'This translation failed the placeholder/integrity check. Please fix the translation and try saving again.',
  INTEGRITY_CHECK_UNAVAILABLE_MESSAGE: 'Try again, or close without saving?',
  INTEGRITY_CHECK_UNAVAILABLE_TITLE: 'The string verification system is temporarily down',
}));

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
    content: 'Pay now',
    comment: 'Checkout payment copy',
    asset: null,
    wordCount: 2,
  },
  baselineTmTextUnitVariant: {
    id: 30,
    content: 'Pay now',
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
const textUnitTmTextUnitId = 3;

const project: ApiReviewProjectDetail = {
  id: 7,
  type: 'NORMAL',
  status: 'OPEN',
  textUnitCount: 1,
  wordCount: 2,
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

beforeEach(() => {
  saveReviewProjectTextUnitDecisionMock.mockReset();
  updateReviewProjectTextUnitTerminologyMetadataMock.mockReset();
  checkTextUnitIntegrityWithRetryMock.mockReset();
});

function renderMutationsHook(queryClient: QueryClient, currentUser = user) {
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(
      QueryClientProvider,
      { client: queryClient },
      createElement(UserContext.Provider, { value: currentUser }, children),
    );

  return renderHook(() => useReviewProjectMutations(project.id), { wrapper });
}

describe('shouldInvalidateGlossaryQueriesForAction', () => {
  it('invalidates glossary queries only for actions that can change glossary details', () => {
    const actions: Array<[PendingAction, boolean]> = [
      [
        {
          kind: 'save-decision',
          request: {
            textUnitId: 1,
            tmTextUnitId: 11,
            target: 'Translated',
            comment: null,
            status: 'APPROVED',
            includedInLocalizedFile: true,
            decisionState: 'DECIDED',
          },
        },
        false,
      ],
      [
        {
          kind: 'decision-state',
          request: {
            textUnitId: 1,
            decisionState: 'DECIDED',
          },
        },
        false,
      ],
      [
        {
          kind: 'terminology-feedback',
          request: {
            textUnitId: 1,
            recommendation: 'APPROVE',
          },
        },
        false,
      ],
      [
        {
          kind: 'terminology-metadata',
          request: {
            textUnitId: 1,
            request: {
              definition: 'A term definition.',
            },
          },
        },
        true,
      ],
      [
        {
          kind: 'terminology-resolution',
          request: {
            textUnitId: 1,
            status: 'APPROVED',
          },
        },
        true,
      ],
    ];

    for (const [action, expected] of actions) {
      expect(shouldInvalidateGlossaryQueriesForAction(action)).toBe(expected);
    }
  });
});

describe('shouldPreflightIntegrityCheckForAction', () => {
  const saveAction: PendingAction = {
    kind: 'save-decision',
    request: {
      textUnitId: 1,
      tmTextUnitId: 11,
      target: 'Translated',
      comment: null,
      status: 'APPROVED',
      includedInLocalizedFile: true,
      decisionState: 'DECIDED',
    },
  };

  it('skips preflight integrity checks for translators because save checks on the backend', () => {
    expect(shouldPreflightIntegrityCheckForAction(saveAction, 'ROLE_TRANSLATOR')).toBe(false);
  });

  it('keeps preflight integrity checks for roles that can confirm through failures', () => {
    expect(shouldPreflightIntegrityCheckForAction(saveAction, 'ROLE_PM')).toBe(true);
    expect(shouldPreflightIntegrityCheckForAction(saveAction, 'ROLE_ADMIN')).toBe(true);
  });

  it('skips preflight integrity checks when there is no checkable text unit or override retry asks to skip', () => {
    expect(shouldPreflightIntegrityCheckForAction(saveAction, 'ROLE_PM', true)).toBe(false);
    expect(
      shouldPreflightIntegrityCheckForAction(
        {
          ...saveAction,
          request: {
            ...saveAction.request,
            tmTextUnitId: null,
          },
        },
        'ROLE_PM',
      ),
    ).toBe(false);
  });
});

describe('useReviewProjectMutations', () => {
  it('clears the active text unit after a successful save', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });
    queryClient.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], project);
    const updatedTextUnit: ApiReviewProjectTextUnit = {
      ...textUnit,
      currentTmTextUnitVariant: {
        id: 31,
        content: 'Pagar agora',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        comment: 'Accepted',
      },
    };
    let resolveSave!: (value: ApiReviewProjectTextUnit) => void;
    saveReviewProjectTextUnitDecisionMock.mockReturnValue(
      new Promise<ApiReviewProjectTextUnit>((resolve) => {
        resolveSave = resolve;
      }),
    );
    const { result } = renderMutationsHook(queryClient);

    act(() => {
      result.current.onRequestSaveDecision({
        textUnitId: textUnit.id,
        tmTextUnitId: null,
        target: 'Pagar agora',
        comment: 'Accepted',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        decisionState: 'DECIDED',
      });
    });

    expect(result.current.activeTextUnitId).toBe(textUnit.id);

    act(() => {
      resolveSave(updatedTextUnit);
    });

    await waitFor(() => {
      expect(result.current.activeTextUnitId).toBeNull();
    });
  });

  it('keeps the active text unit after a save conflict so row actions stay visible', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });
    queryClient.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], project);
    const conflictTextUnit: ApiReviewProjectTextUnit = {
      ...textUnit,
      currentTmTextUnitVariant: {
        id: 32,
        content: 'Pagar já',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        comment: 'Saved elsewhere',
      },
    };
    const error = new Error('Conflict') as Error & {
      status?: number;
      data?: ApiReviewProjectTextUnit;
    };
    error.status = 409;
    error.data = conflictTextUnit;
    saveReviewProjectTextUnitDecisionMock.mockRejectedValue(error);
    const { result } = renderMutationsHook(queryClient);

    act(() => {
      result.current.onRequestSaveDecision({
        textUnitId: textUnit.id,
        tmTextUnitId: null,
        target: 'Pagar agora',
        comment: 'Accepted',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        decisionState: 'DECIDED',
      });
    });

    expect(result.current.activeTextUnitId).toBe(textUnit.id);

    await waitFor(() => {
      expect(result.current.conflictTextUnit).toStrictEqual(conflictTextUnit);
    });

    expect(result.current.activeTextUnitId).toBe(textUnit.id);
    expect(result.current.isSaving).toBe(false);
    expect(result.current.errorMessage).toBeNull();
  });

  it('keeps the active text unit after a generic save error so row error stays visible', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });
    queryClient.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], project);
    const error = new Error('Save rejected') as Error & { status?: number };
    error.status = 400;
    saveReviewProjectTextUnitDecisionMock.mockRejectedValue(error);
    const { result } = renderMutationsHook(queryClient);

    act(() => {
      result.current.onRequestSaveDecision({
        textUnitId: textUnit.id,
        tmTextUnitId: null,
        target: 'Pagar agora',
        comment: 'Accepted',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        decisionState: 'DECIDED',
      });
    });

    await waitFor(() => {
      expect(result.current.errorMessage).toBe('Save rejected');
    });

    expect(result.current.activeTextUnitId).toBe(textUnit.id);
    expect(result.current.isSaving).toBe(false);
    expect(result.current.conflictTextUnit).toBeNull();
  });

  it('patches a normal save into cache without refetching project detail or glossary queries', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });
    queryClient.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], project);
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    const updatedTextUnit: ApiReviewProjectTextUnit = {
      ...textUnit,
      currentTmTextUnitVariant: {
        id: 31,
        content: 'Pagar agora',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        comment: 'Accepted',
      },
      reviewProjectTextUnitDecision: {
        decisionState: 'DECIDED',
        notes: 'Looks good',
        decisionTmTextUnitVariant: {
          id: 31,
          content: 'Pagar agora',
          status: 'APPROVED',
          includedInLocalizedFile: true,
          comment: 'Accepted',
        },
      },
    };
    saveReviewProjectTextUnitDecisionMock.mockResolvedValue(updatedTextUnit);
    const { result } = renderMutationsHook(queryClient);

    act(() => {
      result.current.onRequestSaveDecision({
        textUnitId: textUnit.id,
        tmTextUnitId: null,
        target: 'Pagar agora',
        comment: 'Accepted',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        decisionState: 'DECIDED',
        decisionNotes: 'Looks good',
      });
    });

    await waitFor(() => {
      expect(saveReviewProjectTextUnitDecisionMock).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      const cachedProject = queryClient.getQueryData<ApiReviewProjectDetail>([
        ...REVIEW_PROJECT_DETAIL_QUERY_KEY,
        project.id,
      ]);
      expect(cachedProject?.reviewProjectTextUnits?.[0]).toStrictEqual(updatedTextUnit);
    });

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      ([filters]) => (filters as { queryKey?: unknown }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual([REVIEW_PROJECTS_QUERY_KEY]);
    expect(invalidatedKeys).toContainEqual([REVIEW_PROJECT_REQUESTS_QUERY_KEY]);
    expect(invalidatedKeys).toContainEqual(['review-project-text-unit-history']);
    expect(invalidatedKeys).not.toContainEqual([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id]);
    expect(invalidatedKeys).not.toContainEqual(['review-project-glossary-term']);
    expect(invalidatedKeys).not.toContainEqual(['glossary-terms']);
  });

  it('does not run the duplicate frontend integrity pre-check for translator saves', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });
    queryClient.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], project);
    const updatedTextUnit: ApiReviewProjectTextUnit = {
      ...textUnit,
      currentTmTextUnitVariant: {
        id: 31,
        content: 'Pagar agora',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        comment: 'Accepted',
      },
      reviewProjectTextUnitDecision: {
        decisionState: 'DECIDED',
        notes: null,
        decisionTmTextUnitVariant: {
          id: 31,
          content: 'Pagar agora',
          status: 'APPROVED',
          includedInLocalizedFile: true,
          comment: 'Accepted',
        },
      },
    };
    saveReviewProjectTextUnitDecisionMock.mockResolvedValue(updatedTextUnit);
    const { result } = renderMutationsHook(queryClient);

    act(() => {
      result.current.onRequestSaveDecision({
        textUnitId: textUnit.id,
        tmTextUnitId: textUnitTmTextUnitId,
        target: 'Pagar agora',
        comment: 'Accepted',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        decisionState: 'DECIDED',
      });
    });

    await waitFor(() => {
      expect(saveReviewProjectTextUnitDecisionMock).toHaveBeenCalledTimes(1);
    });
    expect(checkTextUnitIntegrityWithRetryMock).not.toHaveBeenCalled();
  });

  it('keeps the frontend integrity pre-check for PM saves', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });
    queryClient.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], project);
    const updatedTextUnit: ApiReviewProjectTextUnit = {
      ...textUnit,
      currentTmTextUnitVariant: {
        id: 31,
        content: 'Pagar agora',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        comment: 'Accepted',
      },
    };
    checkTextUnitIntegrityWithRetryMock.mockResolvedValue({ checkResult: true });
    saveReviewProjectTextUnitDecisionMock.mockResolvedValue(updatedTextUnit);
    const { result } = renderMutationsHook(queryClient, {
      ...user,
      role: 'ROLE_PM',
    });

    act(() => {
      result.current.onRequestSaveDecision({
        textUnitId: textUnit.id,
        tmTextUnitId: textUnitTmTextUnitId,
        target: 'Pagar agora',
        comment: 'Accepted',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        decisionState: 'DECIDED',
      });
    });

    await waitFor(() => {
      expect(checkTextUnitIntegrityWithRetryMock).toHaveBeenCalledWith({
        tmTextUnitId: textUnitTmTextUnitId,
        content: 'Pagar agora',
      });
    });
    await waitFor(() => {
      expect(saveReviewProjectTextUnitDecisionMock).toHaveBeenCalledTimes(1);
    });
  });

  it('waits for PM integrity pre-check success before saving', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });
    queryClient.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], project);
    const updatedTextUnit: ApiReviewProjectTextUnit = {
      ...textUnit,
      currentTmTextUnitVariant: {
        id: 31,
        content: 'Pagar agora',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        comment: 'Accepted',
      },
    };
    let resolveIntegrityCheck!: (value: { checkResult: true }) => void;
    checkTextUnitIntegrityWithRetryMock.mockReturnValue(
      new Promise((resolve) => {
        resolveIntegrityCheck = resolve;
      }),
    );
    saveReviewProjectTextUnitDecisionMock.mockResolvedValue(updatedTextUnit);
    const { result } = renderMutationsHook(queryClient, {
      ...user,
      role: 'ROLE_PM',
    });

    act(() => {
      result.current.onRequestSaveDecision({
        textUnitId: textUnit.id,
        tmTextUnitId: textUnitTmTextUnitId,
        target: 'Pagar agora',
        comment: 'Accepted',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        decisionState: 'DECIDED',
      });
    });

    await waitFor(() => {
      expect(checkTextUnitIntegrityWithRetryMock).toHaveBeenCalledTimes(1);
    });
    expect(result.current.activeTextUnitId).toBe(textUnit.id);
    expect(result.current.isSaving).toBe(true);
    expect(saveReviewProjectTextUnitDecisionMock).not.toHaveBeenCalled();

    act(() => {
      resolveIntegrityCheck({ checkResult: true });
    });

    await waitFor(() => {
      expect(saveReviewProjectTextUnitDecisionMock).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(result.current.isSaving).toBe(false);
    });
  });

  it('shows detailed translator integrity failure details after backend rejection', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });
    queryClient.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], project);
    const error = new Error('Forbidden') as Error & { status?: number };
    error.status = 403;
    saveReviewProjectTextUnitDecisionMock.mockRejectedValue(error);
    let resolveIntegrityCheck!: (value: { checkResult: false; failureDetail: string }) => void;
    checkTextUnitIntegrityWithRetryMock.mockReturnValue(
      new Promise((resolve) => {
        resolveIntegrityCheck = resolve;
      }),
    );
    const { result } = renderMutationsHook(queryClient);

    act(() => {
      result.current.onRequestSaveDecision({
        textUnitId: textUnit.id,
        tmTextUnitId: textUnitTmTextUnitId,
        reportUrl: 'https://mojito.example/review-projects/7',
        reviewProjectTextUnitUrl: 'https://mojito.example/review-projects/7/text-units/101',
        target: 'Pagar agora',
        comment: 'Accepted',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        decisionState: 'DECIDED',
      });
    });

    await waitFor(() => {
      expect(saveReviewProjectTextUnitDecisionMock).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(checkTextUnitIntegrityWithRetryMock).toHaveBeenCalledWith({
        tmTextUnitId: textUnitTmTextUnitId,
        content: 'Pagar agora',
      });
    });
    expect(result.current.activeTextUnitId).toBe(textUnit.id);
    expect(result.current.isSaving).toBe(true);

    act(() => {
      resolveIntegrityCheck({
        checkResult: false,
        failureDetail: 'Missing placeholder {count}',
      });
    });

    await waitFor(() => {
      expect(result.current.showValidationDialog).toBe(true);
    });
    expect(result.current.activeTextUnitId).toBeNull();
    expect(result.current.isSaving).toBe(false);
    expect(result.current.validationDialogTitle).toBe('Unable to save translation');
    expect(result.current.validationDialogFailureDetail).toBe('Missing placeholder {count}');
    expect(result.current.errorMessage).toBeNull();
  });

  it('clears the active text unit after PM integrity pre-check failure', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });
    queryClient.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], project);
    checkTextUnitIntegrityWithRetryMock.mockResolvedValue({
      checkResult: false,
      failureDetail: 'Missing placeholder {count}',
    });
    const { result } = renderMutationsHook(queryClient, {
      ...user,
      role: 'ROLE_PM',
    });

    act(() => {
      result.current.onRequestSaveDecision({
        textUnitId: textUnit.id,
        tmTextUnitId: textUnitTmTextUnitId,
        target: 'Pagar agora',
        comment: 'Accepted',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        decisionState: 'DECIDED',
      });
    });

    expect(result.current.activeTextUnitId).toBe(textUnit.id);
    await waitFor(() => {
      expect(result.current.showValidationDialog).toBe(true);
    });

    expect(result.current.activeTextUnitId).toBeNull();
    expect(saveReviewProjectTextUnitDecisionMock).not.toHaveBeenCalled();
  });

  it('still invalidates glossary queries for glossary-changing terminology metadata saves', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });
    queryClient.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], project);
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    const updatedTextUnit: ApiReviewProjectTextUnit = {
      ...textUnit,
      terminologyTerm: {
        glossaryId: 12,
        glossaryName: 'Product UI',
        metadataId: 99,
        tmTextUnitId: 3,
        source: 'Pay now',
        definition: 'Updated payment action.',
      },
    };
    updateReviewProjectTextUnitTerminologyMetadataMock.mockResolvedValue(updatedTextUnit);
    const { result } = renderMutationsHook(queryClient);

    act(() => {
      result.current.onRequestTerminologyMetadata({
        textUnitId: textUnit.id,
        request: {
          definition: 'Updated payment action.',
        },
      });
    });

    await waitFor(() => {
      expect(updateReviewProjectTextUnitTerminologyMetadataMock).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      const cachedProject = queryClient.getQueryData<ApiReviewProjectDetail>([
        ...REVIEW_PROJECT_DETAIL_QUERY_KEY,
        project.id,
      ]);
      expect(cachedProject?.reviewProjectTextUnits?.[0]).toStrictEqual(updatedTextUnit);
    });

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      ([filters]) => (filters as { queryKey?: unknown }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual([REVIEW_PROJECTS_QUERY_KEY]);
    expect(invalidatedKeys).toContainEqual([REVIEW_PROJECT_REQUESTS_QUERY_KEY]);
    expect(invalidatedKeys).toContainEqual(['review-project-text-unit-history']);
    expect(invalidatedKeys).toContainEqual(['review-project-glossary-term']);
    expect(invalidatedKeys).toContainEqual(['glossary-terms']);
    expect(invalidatedKeys).not.toContainEqual([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id]);
  });
});
