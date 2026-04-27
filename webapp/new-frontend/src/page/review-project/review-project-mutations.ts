import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import type {
  ApiReviewProjectDetail,
  ApiReviewProjectStatus,
  ApiReviewProjectTextUnit,
  ApiReviewProjectType,
} from '../../api/review-projects';
import {
  saveReviewProjectTextUnitDecision,
  setReviewProjectTextUnitDecisionState,
  updateReviewProjectAssignment,
  updateReviewProjectDueDate,
  updateReviewProjectRequest,
  updateReviewProjectStatus,
} from '../../api/review-projects';
import type { TextUnitIntegrityCheckResult } from '../../api/text-units';
import { useUser } from '../../components/RequireUser';
import { REVIEW_PROJECT_DETAIL_QUERY_KEY } from '../../hooks/useReviewProjectDetail';
import {
  REVIEW_PROJECT_REQUESTS_QUERY_KEY,
  REVIEW_PROJECTS_QUERY_KEY,
} from '../../hooks/useReviewProjects';
import {
  buildIntegrityCheckErrorReport,
  checkTextUnitIntegrityWithRetry,
  INTEGRITY_CHECK_FAILURE_MESSAGE,
  INTEGRITY_CHECK_UNAVAILABLE_MESSAGE,
  INTEGRITY_CHECK_UNAVAILABLE_TITLE,
} from '../../utils/integrityCheck';

export type SaveDecisionRequest = {
  textUnitId: number;
  tmTextUnitId: number | null;
  reportUrl?: string | null;
  reviewProjectTextUnitUrl?: string | null;
  target: string;
  comment: string | null;
  status: string;
  includedInLocalizedFile: boolean;
  decisionState: 'PENDING' | 'DECIDED';
  expectedCurrentTmTextUnitVariantId?: number | null;
  overrideChangedCurrent?: boolean;
  decisionNotes?: string | null;
};

export type DecisionStateRequest = {
  textUnitId: number;
  decisionState: 'PENDING' | 'DECIDED';
  expectedCurrentTmTextUnitVariantId?: number | null;
  overrideChangedCurrent?: boolean;
};

export type PendingAction =
  | { kind: 'save-decision'; request: SaveDecisionRequest }
  | { kind: 'decision-state'; request: DecisionStateRequest };

export type PendingValidationSave = {
  title: string;
  body: string;
  action?: PendingAction;
  retryAction?: PendingAction;
  failureDetail?: string | null;
  reportUrl?: string | null;
  reportMessage?: string | null;
  reportHtml?: string | null;
};

export type ReviewProjectMutationControls = {
  isSaving: boolean;
  isProjectStatusSaving: boolean;
  isProjectRequestSaving: boolean;
  isProjectAssignmentSaving: boolean;
  isProjectDueDateSaving: boolean;
  errorMessage: string | null;
  activeTextUnitId: number | null;
  conflictTextUnit: ApiReviewProjectTextUnit | null;
  showValidationDialog: boolean;
  validationDialogTitle: string;
  validationDialogBody: string;
  validationDialogFailureDetail: string | null;
  validationDialogReportMessage: string | null;
  validationDialogReportHtml: string | null;
  validationDialogRequiresConfirmation: boolean;
  validationDialogCanRetry: boolean;
  onConfirmValidationSave: () => void;
  onRetryValidationSave: () => void;
  onDismissValidationSave: () => void;
  onUseConflictCurrent: () => void;
  onOverwriteConflict: () => void;
  onRequestSaveDecision: (request: SaveDecisionRequest) => void;
  onRequestDecisionState: (request: DecisionStateRequest) => void;
  onRequestProjectStatus: (status: ApiReviewProjectStatus) => void;
  onRequestProjectRequestUpdate: (request: {
    name: string;
    notes?: string | null;
    type?: ApiReviewProjectType | null;
    dueDate?: string | null;
    screenshotImageIds?: string[] | null;
  }) => Promise<void>;
  onRequestProjectAssignmentUpdate: (request: {
    teamId?: number | null;
    assignedPmUserId?: number | null;
    assignedTranslatorUserId?: number | null;
    note?: string | null;
  }) => Promise<void>;
  onRequestProjectDueDateUpdate: (dueDate: string) => Promise<void>;
};

type MutationError = Error & { status?: number; data?: ApiReviewProjectTextUnit | null };

export function useReviewProjectMutations(
  projectId: number | undefined,
): ReviewProjectMutationControls {
  const user = useUser();
  const queryClient = useQueryClient();
  const actionAttemptRef = useRef(0);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [activeTextUnitId, setActiveTextUnitId] = useState<number | null>(null);
  const [conflictTextUnit, setConflictTextUnit] = useState<ApiReviewProjectTextUnit | null>(null);
  const [conflictAction, setConflictAction] = useState<PendingAction | null>(null);
  const [pendingValidationSave, setPendingValidationSave] = useState<PendingValidationSave | null>(
    null,
  );

  const updateTextUnitInCache = useCallback(
    (updatedTextUnit: ApiReviewProjectTextUnit) => {
      if (projectId == null) {
        return;
      }
      queryClient.setQueryData<ApiReviewProjectDetail>(
        [...REVIEW_PROJECT_DETAIL_QUERY_KEY, projectId],
        (prev) => {
          if (!prev?.reviewProjectTextUnits) {
            return prev;
          }
          const nextTextUnits = prev.reviewProjectTextUnits.map((tu) =>
            tu.id === updatedTextUnit.id ? updatedTextUnit : tu,
          );
          return { ...prev, reviewProjectTextUnits: nextTextUnits };
        },
      );
    },
    [projectId, queryClient],
  );

  const buildTranslatorCheckFailure = useCallback(
    (
      action: Extract<PendingAction, { kind: 'save-decision' }>,
      result: TextUnitIntegrityCheckResult | null,
    ) => {
      const detail = result?.failureDetail?.trim();
      const reportUrl = action.request.reportUrl?.trim() || window.location.href;
      const attemptedTranslation = action.request.target.trim() || '(empty translation)';
      const errorMessage = detail || 'Unavailable';
      const report = buildIntegrityCheckErrorReport({
        url: reportUrl,
        additionalLinks: [
          {
            label: 'Review project text unit URL',
            url: action.request.reviewProjectTextUnitUrl,
          },
        ],
        suggestedTranslation: attemptedTranslation,
        errorMessage,
      });

      return {
        body: INTEGRITY_CHECK_FAILURE_MESSAGE,
        failureDetail: detail ?? null,
        reportUrl,
        reportMessage: report.reportMessage,
        reportHtml: report.reportHtml,
      };
    },
    [],
  );

  const shouldRetry = useCallback((failureCount: number, error: MutationError) => {
    const status = error?.status;
    if (status && status >= 400 && status < 500) {
      return false;
    }
    return failureCount < 2;
  }, []);

  const mutation = useMutation<ApiReviewProjectTextUnit, MutationError, PendingAction>({
    mutationFn: async (action) => {
      if (projectId == null) {
        throw new Error('Missing project id');
      }
      if (action.kind === 'save-decision') {
        return saveReviewProjectTextUnitDecision({
          textUnitId: action.request.textUnitId,
          target: action.request.target,
          comment: action.request.comment,
          status: action.request.status,
          includedInLocalizedFile: action.request.includedInLocalizedFile,
          decisionState: action.request.decisionState,
          expectedCurrentTmTextUnitVariantId: action.request.expectedCurrentTmTextUnitVariantId,
          overrideChangedCurrent: action.request.overrideChangedCurrent,
          decisionNotes: action.request.decisionNotes,
        });
      }
      return setReviewProjectTextUnitDecisionState({
        textUnitId: action.request.textUnitId,
        decisionState: action.request.decisionState,
        expectedCurrentTmTextUnitVariantId: action.request.expectedCurrentTmTextUnitVariantId,
        overrideChangedCurrent: action.request.overrideChangedCurrent,
      });
    },
    retry: shouldRetry,
  });

  const projectStatusMutation = useMutation<ApiReviewProjectDetail, Error, ApiReviewProjectStatus>({
    mutationFn: async (nextStatus) => {
      if (projectId == null) {
        throw new Error('Missing project id');
      }
      return updateReviewProjectStatus(projectId, nextStatus);
    },
    onSuccess: (updatedProject) => {
      if (projectId == null) {
        return;
      }
      queryClient.setQueryData<ApiReviewProjectDetail>(
        [...REVIEW_PROJECT_DETAIL_QUERY_KEY, projectId],
        updatedProject,
      );
      void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECTS_QUERY_KEY] });
      void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECT_REQUESTS_QUERY_KEY] });
      setErrorMessage(null);
    },
    onError: (error) => {
      setErrorMessage(error.message || 'Failed to update project status');
    },
  });

  const projectRequestMutation = useMutation<
    ApiReviewProjectDetail,
    Error,
    {
      name: string;
      notes?: string | null;
      type?: ApiReviewProjectType | null;
      dueDate?: string | null;
      screenshotImageIds?: string[] | null;
    }
  >({
    mutationFn: async (request) => {
      if (projectId == null) {
        throw new Error('Missing project id');
      }
      return updateReviewProjectRequest(projectId, request);
    },
    onSuccess: (updatedProject) => {
      if (projectId == null) {
        return;
      }
      queryClient.setQueryData<ApiReviewProjectDetail>(
        [...REVIEW_PROJECT_DETAIL_QUERY_KEY, projectId],
        updatedProject,
      );
      void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECTS_QUERY_KEY] });
      void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECT_REQUESTS_QUERY_KEY] });
      setErrorMessage(null);
    },
    onError: (error) => {
      setErrorMessage(error.message || 'Failed to update project request');
    },
  });

  const projectAssignmentMutation = useMutation<
    ApiReviewProjectDetail,
    Error,
    {
      teamId?: number | null;
      assignedPmUserId?: number | null;
      assignedTranslatorUserId?: number | null;
      note?: string | null;
    }
  >({
    mutationFn: async (request) => {
      if (projectId == null) {
        throw new Error('Missing project id');
      }
      return updateReviewProjectAssignment(projectId, request);
    },
    onSuccess: (updatedProject) => {
      if (projectId == null) {
        return;
      }
      queryClient.setQueryData<ApiReviewProjectDetail>(
        [...REVIEW_PROJECT_DETAIL_QUERY_KEY, projectId],
        updatedProject,
      );
      void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECTS_QUERY_KEY] });
      void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECT_REQUESTS_QUERY_KEY] });
      setErrorMessage(null);
    },
    onError: (error) => {
      setErrorMessage(error.message || 'Failed to update project assignment');
    },
  });

  const projectDueDateMutation = useMutation<ApiReviewProjectDetail, Error, string>({
    mutationFn: async (dueDate) => {
      if (projectId == null) {
        throw new Error('Missing project id');
      }
      return updateReviewProjectDueDate(projectId, dueDate);
    },
    onSuccess: (updatedProject) => {
      if (projectId == null) {
        return;
      }
      queryClient.setQueryData<ApiReviewProjectDetail>(
        [...REVIEW_PROJECT_DETAIL_QUERY_KEY, projectId],
        updatedProject,
      );
      void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECTS_QUERY_KEY] });
      void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECT_REQUESTS_QUERY_KEY] });
      setErrorMessage(null);
    },
    onError: (error) => {
      setErrorMessage(error.message || 'Failed to update project due date');
    },
  });

  const executeAction = useCallback(
    async (action: PendingAction, attemptId: number) => {
      if (projectId == null) {
        return;
      }
      try {
        const updated = await mutation.mutateAsync(action);
        if (attemptId !== actionAttemptRef.current) {
          return;
        }
        updateTextUnitInCache(updated);
        void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECTS_QUERY_KEY] });
        void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECT_REQUESTS_QUERY_KEY] });
        void queryClient.invalidateQueries({
          queryKey: ['review-project-text-unit-history'],
        });
        setErrorMessage(null);
        setConflictTextUnit(null);
        setConflictAction(null);
        setPendingValidationSave(null);
      } catch (error) {
        if (attemptId !== actionAttemptRef.current) {
          return;
        }
        const err = error as MutationError;
        if (err.status === 409 && err.data) {
          void queryClient.invalidateQueries({
            queryKey: ['review-project-text-unit-history'],
          });
          setConflictTextUnit(err.data);
          setConflictAction(action);
          setErrorMessage(null);
        } else {
          setConflictTextUnit(null);
          setConflictAction(null);
          setErrorMessage(err.message || 'Failed to save changes');
        }
      }
    },
    [mutation, projectId, queryClient, updateTextUnitInCache],
  );

  const performAction = useCallback(
    (action: PendingAction, skipIntegrityCheck = false) => {
      if (projectId == null) {
        return;
      }
      const attemptId = (actionAttemptRef.current += 1);
      setActiveTextUnitId(action.request.textUnitId);
      setErrorMessage(null);
      setConflictTextUnit(null);
      setConflictAction(null);
      setPendingValidationSave(null);

      if (
        action.kind === 'save-decision' &&
        !skipIntegrityCheck &&
        action.request.tmTextUnitId != null
      ) {
        void checkTextUnitIntegrityWithRetry({
          tmTextUnitId: action.request.tmTextUnitId,
          content: action.request.target,
        })
          .then((result) => {
            if (attemptId !== actionAttemptRef.current) {
              return;
            }
            if (result?.checkResult === false) {
              if (user.role === 'ROLE_TRANSLATOR') {
                const failure = buildTranslatorCheckFailure(action, result);
                setPendingValidationSave({
                  title: 'Unable to save translation',
                  body: failure.body,
                  failureDetail: failure.failureDetail,
                  reportUrl: failure.reportUrl,
                  reportMessage: failure.reportMessage,
                  reportHtml: failure.reportHtml,
                });
                return;
              }
              const detail = result.failureDetail?.trim();
              const reportUrl = action.request.reportUrl?.trim() || window.location.href;
              const report = buildIntegrityCheckErrorReport({
                url: reportUrl,
                additionalLinks: [
                  {
                    label: 'Review project text unit URL',
                    url: action.request.reviewProjectTextUnitUrl,
                  },
                ],
                suggestedTranslation: action.request.target.trim() || '(empty translation)',
                errorMessage: detail || 'Unavailable',
              });
              setPendingValidationSave({
                title: 'Unable to save translation',
                body: INTEGRITY_CHECK_FAILURE_MESSAGE,
                failureDetail: detail ?? null,
                reportMessage: report.reportMessage,
                reportHtml: report.reportHtml,
                action,
              });
              return;
            }
            void executeAction(action, attemptId);
          })
          .catch(() => {
            if (attemptId !== actionAttemptRef.current) {
              return;
            }
            setPendingValidationSave({
              title: INTEGRITY_CHECK_UNAVAILABLE_TITLE,
              body: INTEGRITY_CHECK_UNAVAILABLE_MESSAGE,
              retryAction: action,
            });
          });
        return;
      }

      void executeAction(action, attemptId);
    },
    [buildTranslatorCheckFailure, executeAction, projectId, user.role],
  );

  const onRequestSaveDecision = useCallback(
    (request: SaveDecisionRequest) => {
      performAction({ kind: 'save-decision', request });
    },
    [performAction],
  );

  const onRequestDecisionState = useCallback(
    (request: DecisionStateRequest) => {
      performAction({ kind: 'decision-state', request });
    },
    [performAction],
  );

  const onRequestProjectStatus = useCallback(
    (nextStatus: ApiReviewProjectStatus) => {
      if (projectId == null || projectStatusMutation.isPending) {
        return;
      }
      projectStatusMutation.mutate(nextStatus);
    },
    [projectId, projectStatusMutation],
  );

  const onRequestProjectRequestUpdate = useCallback(
    async (request: {
      name: string;
      notes?: string | null;
      type?: ApiReviewProjectType | null;
      dueDate?: string | null;
      screenshotImageIds?: string[] | null;
    }) => {
      if (projectId == null || projectRequestMutation.isPending) {
        return;
      }
      await projectRequestMutation.mutateAsync(request);
    },
    [projectId, projectRequestMutation],
  );

  const onRequestProjectAssignmentUpdate = useCallback(
    async (request: {
      teamId?: number | null;
      assignedPmUserId?: number | null;
      assignedTranslatorUserId?: number | null;
      note?: string | null;
    }) => {
      if (projectId == null || projectAssignmentMutation.isPending) {
        return;
      }
      await projectAssignmentMutation.mutateAsync(request);
    },
    [projectAssignmentMutation, projectId],
  );

  const onRequestProjectDueDateUpdate = useCallback(
    async (dueDate: string) => {
      if (projectId == null || projectDueDateMutation.isPending) {
        return;
      }
      await projectDueDateMutation.mutateAsync(dueDate);
    },
    [projectDueDateMutation, projectId],
  );

  const onConfirmValidationSave = useCallback(() => {
    if (!pendingValidationSave?.action) {
      return;
    }
    performAction(pendingValidationSave.action, true);
  }, [pendingValidationSave, performAction]);

  const onRetryValidationSave = useCallback(() => {
    if (!pendingValidationSave?.retryAction) {
      return;
    }
    performAction(pendingValidationSave.retryAction);
  }, [pendingValidationSave, performAction]);

  const onDismissValidationSave = useCallback(() => {
    actionAttemptRef.current += 1;
    setPendingValidationSave(null);
  }, []);

  const onUseConflictCurrent = useCallback(() => {
    if (!conflictTextUnit) {
      return;
    }

    updateTextUnitInCache(conflictTextUnit);
    setConflictTextUnit(null);
    setConflictAction(null);
    setErrorMessage(null);

    const isAlreadyDecided =
      conflictTextUnit.reviewProjectTextUnitDecision?.decisionState === 'DECIDED' ||
      conflictTextUnit.reviewProjectTextUnitDecision?.decisionTmTextUnitVariant?.id != null;

    if (isAlreadyDecided) {
      return;
    }

    performAction({
      kind: 'decision-state',
      request: {
        textUnitId: conflictTextUnit.id,
        decisionState: 'DECIDED',
        expectedCurrentTmTextUnitVariantId: conflictTextUnit.currentTmTextUnitVariant?.id ?? null,
      },
    });
  }, [conflictTextUnit, performAction, updateTextUnitInCache]);

  const onOverwriteConflict = useCallback(() => {
    if (!conflictAction || !conflictTextUnit) {
      return;
    }
    const expectedCurrentId = conflictTextUnit.currentTmTextUnitVariant?.id ?? null;
    if (conflictAction.kind === 'save-decision') {
      performAction(
        {
          kind: 'save-decision',
          request: {
            ...conflictAction.request,
            expectedCurrentTmTextUnitVariantId: expectedCurrentId,
            overrideChangedCurrent: true,
          },
        },
        false,
      );
      return;
    }
    performAction({
      kind: 'decision-state',
      request: {
        ...conflictAction.request,
        expectedCurrentTmTextUnitVariantId: expectedCurrentId,
        overrideChangedCurrent: true,
      },
    });
  }, [conflictAction, conflictTextUnit, performAction]);

  useEffect(() => {
    actionAttemptRef.current += 1;
    setErrorMessage(null);
    setActiveTextUnitId(null);
    setConflictTextUnit(null);
    setConflictAction(null);
    setPendingValidationSave(null);
  }, [projectId]);

  return useMemo(
    () => ({
      isSaving: mutation.isPending,
      isProjectStatusSaving: projectStatusMutation.isPending,
      isProjectRequestSaving: projectRequestMutation.isPending,
      isProjectAssignmentSaving: projectAssignmentMutation.isPending,
      isProjectDueDateSaving: projectDueDateMutation.isPending,
      errorMessage,
      activeTextUnitId,
      conflictTextUnit,
      showValidationDialog: pendingValidationSave != null,
      validationDialogTitle: pendingValidationSave?.title ?? '',
      validationDialogBody: pendingValidationSave?.body ?? '',
      validationDialogFailureDetail: pendingValidationSave?.failureDetail ?? null,
      validationDialogReportMessage: pendingValidationSave?.reportMessage ?? null,
      validationDialogReportHtml: pendingValidationSave?.reportHtml ?? null,
      validationDialogRequiresConfirmation: pendingValidationSave?.action != null,
      validationDialogCanRetry: pendingValidationSave?.retryAction != null,
      onConfirmValidationSave,
      onRetryValidationSave,
      onDismissValidationSave,
      onUseConflictCurrent,
      onOverwriteConflict,
      onRequestSaveDecision,
      onRequestDecisionState,
      onRequestProjectStatus,
      onRequestProjectRequestUpdate,
      onRequestProjectAssignmentUpdate,
      onRequestProjectDueDateUpdate,
    }),
    [
      activeTextUnitId,
      conflictTextUnit,
      errorMessage,
      mutation.isPending,
      onConfirmValidationSave,
      onDismissValidationSave,
      onOverwriteConflict,
      onRequestDecisionState,
      onRequestProjectDueDateUpdate,
      onRequestProjectRequestUpdate,
      onRequestProjectAssignmentUpdate,
      onRequestProjectStatus,
      onRequestSaveDecision,
      onUseConflictCurrent,
      onRetryValidationSave,
      pendingValidationSave,
      projectDueDateMutation.isPending,
      projectRequestMutation.isPending,
      projectAssignmentMutation.isPending,
      projectStatusMutation.isPending,
    ],
  );
}
