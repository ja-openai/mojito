import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react';

import type {
  ApiReviewProjectDetail,
  ApiReviewProjectStatus,
  ApiReviewProjectTerminologyMetadataRequest,
  ApiReviewProjectTextUnit,
  ApiReviewProjectType,
  ApiTerminologyFeedbackRecommendation,
  ApiTerminologyResolutionStatus,
} from '../../api/review-projects';
import {
  saveReviewProjectTextUnitDecision,
  saveReviewProjectTextUnitTerminologyFeedback,
  saveReviewProjectTextUnitTerminologyResolution,
  setReviewProjectTextUnitDecisionState,
  updateReviewProjectAssignment,
  updateReviewProjectDueDate,
  updateReviewProjectRequest,
  updateReviewProjectStatus,
  updateReviewProjectTextUnitTerminologyMetadata,
} from '../../api/review-projects';
import type { TextUnitIntegrityCheckResult } from '../../api/text-units';
import { REVIEW_PROJECT_DETAIL_QUERY_KEY } from '../../hooks/useReviewProjectDetail';
import {
  REVIEW_PROJECT_REQUESTS_QUERY_KEY,
  REVIEW_PROJECTS_QUERY_KEY,
} from '../../hooks/useReviewProjects';
import { useUser } from '../../hooks/useUser';
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
  expectedReviewStateRevision?: string | null;
  overrideChangedCurrent?: boolean;
  decisionNotes?: string | null;
};

export type DecisionStateRequest = {
  textUnitId: number;
  decisionState: 'PENDING' | 'DECIDED';
  expectedCurrentTmTextUnitVariantId?: number | null;
  expectedReviewStateRevision?: string | null;
  overrideChangedCurrent?: boolean;
};

export type TerminologyFeedbackRequest = {
  textUnitId: number;
  recommendation: ApiTerminologyFeedbackRecommendation;
  confidence?: number | null;
  notes?: string | null;
};

export type TerminologyResolutionRequest = {
  textUnitId: number;
  glossaryId?: number | null;
  status: ApiTerminologyResolutionStatus;
  notes?: string | null;
  promoteToGlossary?: boolean | null;
};

export type TerminologyMetadataRequest = {
  textUnitId: number;
  request: ApiReviewProjectTerminologyMetadataRequest;
};

export type PendingAction =
  | { kind: 'save-decision'; request: SaveDecisionRequest }
  | { kind: 'decision-state'; request: DecisionStateRequest }
  | { kind: 'terminology-feedback'; request: TerminologyFeedbackRequest }
  | { kind: 'terminology-metadata'; request: TerminologyMetadataRequest }
  | { kind: 'terminology-resolution'; request: TerminologyResolutionRequest };

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

type ActionAttempt = {
  operationId: number;
  attemptId: number;
  action: PendingAction;
  originalAction: PendingAction;
};

type ActionTransportAttempt = ActionAttempt & { sessionOwner: object };

// One state owns the request and its outcome. A settled request is not necessarily a save.
// Explicit conflict/validation recovery keeps the operation ID and gets a new attempt ID.
export type ReviewProjectActionState =
  | { phase: 'idle' }
  | (ActionAttempt & { phase: 'pending' })
  | (ActionAttempt & { phase: 'validation'; validation: PendingValidationSave })
  | (ActionAttempt & { phase: 'conflict'; textUnit: ApiReviewProjectTextUnit })
  | (ActionAttempt & { phase: 'failed'; error: string })
  | (ActionAttempt & {
      phase: 'succeeded';
      textUnit: ApiReviewProjectTextUnit;
      resolution: 'saved' | 'use-current';
    });

export type ReviewProjectMutationControls = {
  actionState: ReviewProjectActionState;
  isSaving: boolean;
  isProjectStatusSaving: boolean;
  isProjectRequestSaving: boolean;
  isProjectDueDateSaving: boolean;
  isProjectAssignmentSaving: boolean;
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
  onDiscardAction: (textUnitId: number) => void;
  onUseConflictCurrent: () => number | void;
  onOverwriteConflict: () => void;
  onRequestSaveDecision: (request: SaveDecisionRequest) => number | void;
  onRequestDecisionState: (request: DecisionStateRequest) => number | void;
  onRequestTerminologyFeedback: (request: TerminologyFeedbackRequest) => number | void;
  onRequestTerminologyMetadata: (request: TerminologyMetadataRequest) => number | void;
  onRequestTerminologyResolution: (request: TerminologyResolutionRequest) => number | void;
  onRequestProjectStatus: (status: ApiReviewProjectStatus) => void;
  onRequestProjectRequestUpdate: (request: {
    name: string;
    notes?: string | null;
    type?: ApiReviewProjectType | null;
    dueDate?: string | null;
    screenshotImageIds?: string[] | null;
    teamId?: number | null;
    updateTeam?: boolean | null;
  }) => Promise<void>;
  onRequestProjectDueDateUpdate: (dueDate: string) => Promise<void>;
  onRequestProjectAssignmentUpdate: (request: {
    teamId?: number | null;
    assignedPmUserId?: number | null;
    assignedTranslatorUserId?: number | null;
    note?: string | null;
  }) => Promise<void>;
};

type ProjectMutationRequest<T> = {
  projectId: number;
  sessionId: number;
  request: T;
};

type MutationError = Error & { status?: number; data?: ApiReviewProjectTextUnit | null };

class ObsoleteReviewProjectRequestError extends Error {}

const SOURCE_CHANGED_SAVE_MESSAGE =
  'This row now refers to a different source string. Your draft has been kept. Reset to review the updated source before saving.';
type PreflightIntegrityCheckAction = Extract<PendingAction, { kind: 'save-decision' }> & {
  request: SaveDecisionRequest & { tmTextUnitId: number };
};

export function shouldInvalidateGlossaryQueriesForAction(action: PendingAction) {
  return action.kind === 'terminology-metadata' || action.kind === 'terminology-resolution';
}

export function shouldPreflightIntegrityCheckForAction(
  action: PendingAction,
  userRole: string,
  skipIntegrityCheck = false,
): action is PreflightIntegrityCheckAction {
  return (
    action.kind === 'save-decision' &&
    userRole !== 'ROLE_TRANSLATOR' &&
    !skipIntegrityCheck &&
    action.request.tmTextUnitId != null
  );
}

function hasCompleteRowResponse(
  action: PendingAction,
  textUnit: ApiReviewProjectTextUnit | null | undefined,
): textUnit is ApiReviewProjectTextUnit {
  return (
    textUnit != null &&
    typeof textUnit === 'object' &&
    textUnit.id === action.request.textUnitId &&
    'tmTextUnit' in textUnit &&
    'baselineTmTextUnitVariant' in textUnit &&
    'currentTmTextUnitVariant' in textUnit &&
    (!('expectedReviewStateRevision' in action.request) ||
      action.request.expectedReviewStateRevision == null ||
      (typeof textUnit.reviewStateRevision === 'string' && textUnit.reviewStateRevision.length > 0))
  );
}

function hasMatchingSavedDecision(
  action: PendingAction,
  textUnit: ApiReviewProjectTextUnit,
): boolean {
  if (action.kind !== 'save-decision') {
    return false;
  }

  const currentVariant = textUnit.currentTmTextUnitVariant;
  const decision = textUnit.reviewProjectTextUnitDecision;

  return (
    textUnit.id === action.request.textUnitId &&
    (action.request.tmTextUnitId == null ||
      textUnit.tmTextUnit?.id === action.request.tmTextUnitId) &&
    textUnit.reviewProjectTextUnitSuggestion == null &&
    currentVariant?.id != null &&
    decision?.decisionTmTextUnitVariant?.id === currentVariant.id &&
    decision.decisionState === action.request.decisionState &&
    currentVariant.content === action.request.target.normalize('NFC') &&
    currentVariant.status === action.request.status &&
    currentVariant.includedInLocalizedFile === action.request.includedInLocalizedFile &&
    (currentVariant.comment ?? null) === (action.request.comment ?? null) &&
    (decision.notes ?? null) === (action.request.decisionNotes ?? null)
  );
}

function confirmsRequestedChanges(action: PendingAction, textUnit: ApiReviewProjectTextUnit) {
  if (action.kind === 'save-decision') return hasMatchingSavedDecision(action, textUnit);
  if (action.kind !== 'decision-state') return true;
  const decision = textUnit.reviewProjectTextUnitDecision;
  const currentVariantId = textUnit.currentTmTextUnitVariant?.id ?? null;
  if (
    action.request.expectedCurrentTmTextUnitVariantId !== undefined &&
    currentVariantId !== action.request.expectedCurrentTmTextUnitVariantId
  ) {
    return false;
  }
  // Reopening an untouched row is a valid no-op: no decision entity has to be created.
  if ((decision?.decisionState ?? 'PENDING') !== action.request.decisionState) return false;
  return (
    action.request.decisionState === 'PENDING' ||
    (decision?.decisionTmTextUnitVariant?.id ?? null) ===
      (currentVariantId ?? textUnit.baselineTmTextUnitVariant?.id ?? null)
  );
}

export function useReviewProjectMutations(
  projectId: number | undefined,
): ReviewProjectMutationControls {
  const user = useUser();
  const queryClient = useQueryClient();
  const sessionOwner = useMemo(
    () => ({ projectId, username: user.username }),
    [projectId, user.username],
  );
  const activeSessionOwnerRef = useRef<object | null>(sessionOwner);
  const actionAttemptRef = useRef(0);
  const projectSessionRef = useRef(0);
  const inFlightActionAttemptRef = useRef<number | null>(null);
  const [actionState, setActionState] = useState<ReviewProjectActionState>({ phase: 'idle' });
  const [projectError, setProjectError] = useState<string | null>(null);
  const errorMessage = actionState.phase === 'failed' ? actionState.error : projectError;
  const activeTextUnitId =
    actionState.phase === 'pending' ||
    actionState.phase === 'conflict' ||
    actionState.phase === 'failed'
      ? actionState.action.request.textUnitId
      : null;
  const conflictTextUnit = actionState.phase === 'conflict' ? actionState.textUnit : null;
  const pendingValidationSave = actionState.phase === 'validation' ? actionState.validation : null;

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
    if (error instanceof ObsoleteReviewProjectRequestError) return false;
    const status = error?.status;
    if (status && status >= 400 && status < 500) {
      return false;
    }
    return failureCount < 2;
  }, []);

  const mutation = useMutation<ApiReviewProjectTextUnit, MutationError, ActionTransportAttempt>({
    mutationFn: async ({ action, attemptId, sessionOwner: owner }) => {
      // TanStack may invoke this again after retry backoff or an offline pause, using newer
      // hook options. Validate the immutable owner carried by the original request each time.
      if (activeSessionOwnerRef.current !== owner || actionAttemptRef.current !== attemptId) {
        throw new ObsoleteReviewProjectRequestError('The editing session has ended.');
      }
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
          expectedReviewStateRevision: action.request.expectedReviewStateRevision,
          overrideChangedCurrent: action.request.overrideChangedCurrent,
          decisionNotes: action.request.decisionNotes,
        });
      }
      if (action.kind === 'terminology-feedback') {
        return saveReviewProjectTextUnitTerminologyFeedback(action.request);
      }
      if (action.kind === 'terminology-resolution') {
        return saveReviewProjectTextUnitTerminologyResolution(action.request);
      }
      if (action.kind === 'terminology-metadata') {
        return updateReviewProjectTextUnitTerminologyMetadata(action.request);
      }
      return setReviewProjectTextUnitDecisionState({
        textUnitId: action.request.textUnitId,
        decisionState: action.request.decisionState,
        expectedCurrentTmTextUnitVariantId: action.request.expectedCurrentTmTextUnitVariantId,
        expectedReviewStateRevision: action.request.expectedReviewStateRevision,
        overrideChangedCurrent: action.request.overrideChangedCurrent,
      });
    },
    retry: shouldRetry,
  });

  const acceptProjectUpdate = (
    updatedProject: ApiReviewProjectDetail,
    context: ProjectMutationRequest<unknown>,
  ) => {
    if (context.sessionId !== projectSessionRef.current) return;
    if (updatedProject.id !== context.projectId) {
      setProjectError(
        'The update response did not match this project. Refresh to check the update.',
      );
      return;
    }
    queryClient.setQueryData<ApiReviewProjectDetail>(
      [...REVIEW_PROJECT_DETAIL_QUERY_KEY, context.projectId],
      (current) =>
        current
          ? { ...updatedProject, reviewProjectTextUnits: current.reviewProjectTextUnits }
          : updatedProject,
    );
    // A metadata response may predate a concurrent row save. Keep the row cache and refresh it.
    void queryClient.invalidateQueries({
      queryKey: [...REVIEW_PROJECT_DETAIL_QUERY_KEY, context.projectId],
      exact: true,
    });
    void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECTS_QUERY_KEY] });
    void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECT_REQUESTS_QUERY_KEY] });
    setProjectError(null);
  };
  const rejectProjectUpdate = (error: Error, context: ProjectMutationRequest<unknown>) => {
    if (context.sessionId === projectSessionRef.current) {
      setProjectError(error.message || 'Failed to update project');
    }
  };

  const assertProjectMutationOwner = (context: ProjectMutationRequest<unknown>) => {
    if (activeSessionOwnerRef.current == null || context.sessionId !== projectSessionRef.current) {
      throw new ObsoleteReviewProjectRequestError('The editing session has ended.');
    }
  };

  const projectStatusMutation = useMutation<
    ApiReviewProjectDetail,
    Error,
    ProjectMutationRequest<ApiReviewProjectStatus>
  >({
    mutationFn: (context) => {
      assertProjectMutationOwner(context);
      return updateReviewProjectStatus(context.projectId, context.request);
    },
    onSuccess: acceptProjectUpdate,
    onError: rejectProjectUpdate,
  });

  const projectRequestMutation = useMutation<
    ApiReviewProjectDetail,
    Error,
    ProjectMutationRequest<{
      name: string;
      notes?: string | null;
      type?: ApiReviewProjectType | null;
      dueDate?: string | null;
      screenshotImageIds?: string[] | null;
    }>
  >({
    mutationFn: (context) => {
      assertProjectMutationOwner(context);
      return updateReviewProjectRequest(context.projectId, context.request);
    },
    onSuccess: acceptProjectUpdate,
    onError: rejectProjectUpdate,
  });

  const projectDueDateMutation = useMutation<
    ApiReviewProjectDetail,
    Error,
    ProjectMutationRequest<string>
  >({
    mutationFn: (context) => {
      assertProjectMutationOwner(context);
      return updateReviewProjectDueDate(context.projectId, context.request);
    },
    onSuccess: acceptProjectUpdate,
    onError: rejectProjectUpdate,
  });

  const projectAssignmentMutation = useMutation<
    ApiReviewProjectDetail,
    Error,
    ProjectMutationRequest<{
      teamId?: number | null;
      assignedPmUserId?: number | null;
      assignedTranslatorUserId?: number | null;
      note?: string | null;
    }>
  >({
    mutationFn: (context) => {
      assertProjectMutationOwner(context);
      return updateReviewProjectAssignment(context.projectId, context.request);
    },
    onSuccess: acceptProjectUpdate,
    onError: rejectProjectUpdate,
  });

  const completeAction = useCallback(
    async (
      attempt: ActionAttempt,
      updated: ApiReviewProjectTextUnit,
      resolution: 'saved' | 'use-current',
    ) => {
      if (attempt.attemptId !== actionAttemptRef.current) {
        return;
      }
      // A GET started before this commit may still return the old full project. Cancel it before
      // publishing the acknowledged row so it cannot replace that row when it completes later.
      await queryClient.cancelQueries({
        queryKey: [...REVIEW_PROJECT_DETAIL_QUERY_KEY, projectId],
        exact: true,
      });
      if (attempt.attemptId !== actionAttemptRef.current) {
        return;
      }
      const cachedTextUnit = queryClient
        .getQueryData<ApiReviewProjectDetail>([...REVIEW_PROJECT_DETAIL_QUERY_KEY, projectId])
        ?.reviewProjectTextUnits?.find((row) => row.id === updated.id);
      if (
        (attempt.action.kind === 'save-decision' || attempt.action.kind === 'decision-state') &&
        cachedTextUnit != null &&
        cachedTextUnit.tmTextUnit?.id !== updated.tmTextUnit?.id
      ) {
        // A legitimate save can be followed by a metadata remap before its delayed reply
        // arrives. Keep the newer source already observed instead of restoring the older one.
        setActionState({ ...attempt, phase: 'failed', error: SOURCE_CHANGED_SAVE_MESSAGE });
        return;
      }
      if (
        (attempt.action.kind === 'save-decision' || attempt.action.kind === 'decision-state') &&
        attempt.action.request.expectedReviewStateRevision != null &&
        cachedTextUnit != null &&
        cachedTextUnit.reviewStateRevision !== attempt.action.request.expectedReviewStateRevision &&
        cachedTextUnit.reviewStateRevision !== updated.reviewStateRevision
      ) {
        // Opaque revisions cannot be sorted. A third observed revision could supersede the
        // committed request, so its delayed acknowledgement must not roll that row back.
        setActionState({
          ...attempt,
          phase: 'failed',
          error:
            'Another update was received while this save was in progress. Your draft has been kept. Refresh to check the current saved state.',
        });
        return;
      }
      updateTextUnitInCache(updated);
      void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECTS_QUERY_KEY] });
      void queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECT_REQUESTS_QUERY_KEY] });
      void queryClient.invalidateQueries({ queryKey: ['review-project-text-unit-history'] });
      if (shouldInvalidateGlossaryQueriesForAction(attempt.action)) {
        void queryClient.invalidateQueries({ queryKey: ['review-project-glossary-term'] });
        void queryClient.invalidateQueries({ queryKey: ['glossary-terms'] });
      }
      setActionState({ ...attempt, phase: 'succeeded', textUnit: updated, resolution });
    },
    [projectId, queryClient, updateTextUnitInCache],
  );

  const executeAction = useCallback(
    async (attempt: ActionTransportAttempt, resolution: 'saved' | 'use-current' = 'saved') => {
      const { action, attemptId } = attempt;
      try {
        const updated = await mutation.mutateAsync(attempt);
        if (attemptId !== actionAttemptRef.current) {
          return;
        }
        if (updated?.id !== action.request.textUnitId) {
          throw new Error('The save response did not match this row. Your draft has been kept.');
        }
        if (
          !hasCompleteRowResponse(action, updated) ||
          !confirmsRequestedChanges(action, updated)
        ) {
          throw new Error(
            'The server did not confirm the requested changes. Your draft has been kept. Refresh to check whether the save completed.',
          );
        }
        await completeAction(attempt, updated, resolution);
      } catch (error) {
        if (attemptId !== actionAttemptRef.current) {
          return;
        }
        const err = error as MutationError;
        if (err.status === 409 && hasCompleteRowResponse(action, err.data)) {
          if (
            hasMatchingSavedDecision(action, err.data) &&
            err.data.reviewProjectTextUnitDecision?.lastModifiedByUsername === user.username
          ) {
            await completeAction(attempt, err.data, resolution);
            return;
          }
          await queryClient.cancelQueries({
            queryKey: [...REVIEW_PROJECT_DETAIL_QUERY_KEY, projectId],
            exact: true,
          });
          if (attemptId !== actionAttemptRef.current) return;
          // Keep the reviewed base and local edits, but make Reset and route restoration
          // see the authoritative row that caused the conflict.
          updateTextUnitInCache(err.data);
          void queryClient.invalidateQueries({ queryKey: ['review-project-text-unit-history'] });
          setActionState({ ...attempt, phase: 'conflict', textUnit: err.data });
        } else if (
          err.status === 403 &&
          action.kind === 'save-decision' &&
          user.role === 'ROLE_TRANSLATOR' &&
          action.request.tmTextUnitId != null
        ) {
          try {
            const integrityResult = await checkTextUnitIntegrityWithRetry({
              tmTextUnitId: action.request.tmTextUnitId,
              content: action.request.target,
            });
            if (attemptId !== actionAttemptRef.current) {
              return;
            }
            if (integrityResult?.checkResult === false) {
              const failure = buildTranslatorCheckFailure(action, integrityResult);
              setActionState({
                ...attempt,
                phase: 'validation',
                validation: { title: 'Unable to save translation', ...failure },
              });
              return;
            }
          } catch {
            // Keep the original save error if the follow-up check cannot add detail.
          }
          if (attemptId === actionAttemptRef.current) {
            setActionState({
              ...attempt,
              phase: 'failed',
              error: err.message || 'Failed to save changes',
            });
          }
        } else {
          setActionState({
            ...attempt,
            phase: 'failed',
            error: err.message || 'Failed to save changes',
          });
        }
      } finally {
        if (inFlightActionAttemptRef.current === attemptId) {
          inFlightActionAttemptRef.current = null;
        }
      }
    },
    [
      buildTranslatorCheckFailure,
      completeAction,
      mutation,
      projectId,
      queryClient,
      updateTextUnitInCache,
      user.role,
      user.username,
    ],
  );

  const performAction = useCallback(
    (
      requestedAction: PendingAction,
      skipIntegrityCheck = false,
      operationId?: number,
      resolution: 'saved' | 'use-current' = 'saved',
      originalAction?: PendingAction,
    ) => {
      if (
        activeSessionOwnerRef.current !== sessionOwner ||
        projectId == null ||
        inFlightActionAttemptRef.current != null
      ) {
        return;
      }
      const attemptId = (actionAttemptRef.current += 1);
      // Snapshot the caller's request before any check or retry can yield. Every attempt in this
      // operation uses the same row, target, and base version unless the user resolves a conflict.
      const action = structuredClone(requestedAction);
      const attempt: ActionTransportAttempt = {
        action,
        originalAction: structuredClone(originalAction ?? requestedAction),
        sessionOwner,
        attemptId,
        operationId: operationId ?? attemptId,
      };
      inFlightActionAttemptRef.current = attemptId;
      setActionState({ ...attempt, phase: 'pending' });
      setProjectError(null);

      if (shouldPreflightIntegrityCheckForAction(action, user.role, skipIntegrityCheck)) {
        void checkTextUnitIntegrityWithRetry({
          tmTextUnitId: action.request.tmTextUnitId,
          content: action.request.target,
        })
          .then((result) => {
            if (attemptId !== actionAttemptRef.current) {
              return;
            }
            if (result?.checkResult === false) {
              inFlightActionAttemptRef.current = null;
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
              setActionState({
                ...attempt,
                phase: 'validation',
                validation: {
                  title: 'Unable to save translation',
                  body: INTEGRITY_CHECK_FAILURE_MESSAGE,
                  failureDetail: detail ?? null,
                  reportMessage: report.reportMessage,
                  reportHtml: report.reportHtml,
                  action,
                },
              });
              return;
            }
            void executeAction(attempt, resolution);
          })
          .catch(() => {
            if (attemptId !== actionAttemptRef.current) {
              return;
            }
            inFlightActionAttemptRef.current = null;
            setActionState({
              ...attempt,
              phase: 'validation',
              validation: {
                title: INTEGRITY_CHECK_UNAVAILABLE_TITLE,
                body: INTEGRITY_CHECK_UNAVAILABLE_MESSAGE,
                retryAction: action,
              },
            });
          });
      } else {
        void executeAction(attempt, resolution);
      }
      return attempt.operationId;
    },
    [executeAction, projectId, sessionOwner, user.role],
  );

  const onRequestSaveDecision = useCallback(
    (request: SaveDecisionRequest) => {
      return performAction({ kind: 'save-decision', request });
    },
    [performAction],
  );

  const onRequestDecisionState = useCallback(
    (request: DecisionStateRequest) => {
      return performAction({ kind: 'decision-state', request });
    },
    [performAction],
  );

  const onRequestTerminologyFeedback = useCallback(
    (request: TerminologyFeedbackRequest) => {
      return performAction({ kind: 'terminology-feedback', request });
    },
    [performAction],
  );

  const onRequestTerminologyResolution = useCallback(
    (request: TerminologyResolutionRequest) => {
      return performAction({ kind: 'terminology-resolution', request });
    },
    [performAction],
  );

  const onRequestTerminologyMetadata = useCallback(
    (request: TerminologyMetadataRequest) => {
      return performAction({ kind: 'terminology-metadata', request });
    },
    [performAction],
  );

  const onRequestProjectStatus = useCallback(
    (nextStatus: ApiReviewProjectStatus) => {
      if (
        activeSessionOwnerRef.current !== sessionOwner ||
        projectId == null ||
        projectStatusMutation.isPending
      ) {
        return;
      }
      projectStatusMutation.mutate({
        projectId,
        sessionId: projectSessionRef.current,
        request: nextStatus,
      });
    },
    [projectId, projectStatusMutation, sessionOwner],
  );

  const onRequestProjectRequestUpdate = useCallback(
    async (request: {
      name: string;
      notes?: string | null;
      type?: ApiReviewProjectType | null;
      dueDate?: string | null;
      screenshotImageIds?: string[] | null;
    }) => {
      if (
        activeSessionOwnerRef.current !== sessionOwner ||
        projectId == null ||
        projectRequestMutation.isPending
      ) {
        return;
      }
      await projectRequestMutation.mutateAsync({
        projectId,
        sessionId: projectSessionRef.current,
        request: structuredClone(request),
      });
    },
    [projectId, projectRequestMutation, sessionOwner],
  );

  const onRequestProjectDueDateUpdate = useCallback(
    async (dueDate: string) => {
      if (
        activeSessionOwnerRef.current !== sessionOwner ||
        projectId == null ||
        projectDueDateMutation.isPending
      ) {
        return;
      }
      await projectDueDateMutation.mutateAsync({
        projectId,
        sessionId: projectSessionRef.current,
        request: structuredClone(dueDate),
      });
    },
    [projectDueDateMutation, projectId, sessionOwner],
  );

  const onRequestProjectAssignmentUpdate = useCallback(
    async (request: {
      teamId?: number | null;
      assignedPmUserId?: number | null;
      assignedTranslatorUserId?: number | null;
      note?: string | null;
    }) => {
      if (
        activeSessionOwnerRef.current !== sessionOwner ||
        projectId == null ||
        projectAssignmentMutation.isPending
      ) {
        return;
      }
      await projectAssignmentMutation.mutateAsync({
        projectId,
        sessionId: projectSessionRef.current,
        request: structuredClone(request),
      });
    },
    [projectAssignmentMutation, projectId, sessionOwner],
  );

  const onConfirmValidationSave = useCallback(() => {
    if (
      actionState.phase === 'validation' &&
      actionState.attemptId === actionAttemptRef.current &&
      actionState.validation.action
    ) {
      performAction(actionState.validation.action, true, actionState.operationId);
    }
  }, [actionState, performAction]);

  const onRetryValidationSave = useCallback(() => {
    if (
      actionState.phase === 'validation' &&
      actionState.attemptId === actionAttemptRef.current &&
      actionState.validation.retryAction
    ) {
      performAction(actionState.validation.retryAction, false, actionState.operationId);
    }
  }, [actionState, performAction]);

  const onDismissValidationSave = useCallback(() => {
    // Dismissing a dialog must never release a request that is already being committed.
    if (
      actionState.phase !== 'validation' ||
      actionState.attemptId !== actionAttemptRef.current ||
      inFlightActionAttemptRef.current != null
    ) {
      return;
    }
    actionAttemptRef.current += 1;
    setActionState({ phase: 'idle' });
  }, [actionState]);

  const onUseConflictCurrent = useCallback(() => {
    if (
      actionState.phase !== 'conflict' ||
      actionState.attemptId !== actionAttemptRef.current ||
      inFlightActionAttemptRef.current != null
    ) {
      return;
    }
    const current = actionState.textUnit;
    const originalAction = actionState.originalAction;
    const requestedDecisionState =
      originalAction.kind === 'decision-state' || originalAction.kind === 'save-decision'
        ? originalAction.request.decisionState
        : 'DECIDED';
    // Even an already-decided conflict snapshot can change while the dialog is open.
    // Recheck exactly the displayed revision before acknowledging it or advancing.
    return performAction(
      {
        kind: 'decision-state',
        request: {
          textUnitId: current.id,
          decisionState: requestedDecisionState,
          expectedCurrentTmTextUnitVariantId: current.currentTmTextUnitVariant?.id ?? null,
          expectedReviewStateRevision: current.reviewStateRevision,
        },
      },
      false,
      actionState.operationId,
      'use-current',
      originalAction,
    );
  }, [actionState, performAction]);

  const onDiscardAction = useCallback(
    (textUnitId: number) => {
      if (
        actionState.phase === 'idle' ||
        actionState.attemptId !== actionAttemptRef.current ||
        actionState.action.request.textUnitId !== textUnitId ||
        inFlightActionAttemptRef.current != null
      ) {
        return;
      }
      actionAttemptRef.current += 1;
      setActionState({ phase: 'idle' });
    },
    [actionState],
  );

  const onOverwriteConflict = useCallback(() => {
    if (actionState.phase !== 'conflict' || actionState.attemptId !== actionAttemptRef.current) {
      return;
    }
    // A failed Use external attempt must not replace the original local draft intent.
    const action = actionState.originalAction;
    if (
      action.kind === 'save-decision' &&
      action.request.tmTextUnitId != null &&
      actionState.textUnit.tmTextUnit?.id !== action.request.tmTextUnitId
    ) {
      setActionState({
        ...actionState,
        phase: 'failed',
        error: SOURCE_CHANGED_SAVE_MESSAGE,
      });
      return;
    }
    if (action.kind === 'save-decision' || action.kind === 'decision-state') {
      performAction(
        {
          ...action,
          request: {
            ...action.request,
            expectedCurrentTmTextUnitVariantId:
              actionState.textUnit.currentTmTextUnitVariant?.id ?? null,
            // Approve only the version shown in this conflict. A later edit must conflict again.
            overrideChangedCurrent: false,
            expectedReviewStateRevision: actionState.textUnit.reviewStateRevision,
          },
        } as PendingAction,
        false,
        actionState.operationId,
      );
    } else {
      performAction(action, false, actionState.operationId);
    }
  }, [actionState, performAction]);

  useLayoutEffect(() => {
    activeSessionOwnerRef.current = sessionOwner;
    projectSessionRef.current += 1;
    actionAttemptRef.current += 1;
    inFlightActionAttemptRef.current = null;
    setProjectError(null);
    setActionState({ phase: 'idle' });
    return () => {
      // Late responses from a previous project or an unmounted page cannot alter this session.
      projectSessionRef.current += 1;
      activeSessionOwnerRef.current = null;
      actionAttemptRef.current += 1;
      inFlightActionAttemptRef.current = null;
    };
  }, [sessionOwner]);

  return useMemo(
    () => ({
      actionState,
      isSaving: actionState.phase === 'pending',
      isProjectStatusSaving: projectStatusMutation.isPending,
      isProjectRequestSaving: projectRequestMutation.isPending,
      isProjectDueDateSaving: projectDueDateMutation.isPending,
      isProjectAssignmentSaving: projectAssignmentMutation.isPending,
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
      onDiscardAction,
      onUseConflictCurrent,
      onOverwriteConflict,
      onRequestSaveDecision,
      onRequestDecisionState,
      onRequestTerminologyFeedback,
      onRequestTerminologyMetadata,
      onRequestTerminologyResolution,
      onRequestProjectStatus,
      onRequestProjectRequestUpdate,
      onRequestProjectDueDateUpdate,
      onRequestProjectAssignmentUpdate,
    }),
    [
      activeTextUnitId,
      conflictTextUnit,
      errorMessage,
      actionState,
      onConfirmValidationSave,
      onDismissValidationSave,
      onDiscardAction,
      onOverwriteConflict,
      onRequestDecisionState,
      onRequestProjectAssignmentUpdate,
      onRequestProjectDueDateUpdate,
      onRequestProjectRequestUpdate,
      onRequestProjectStatus,
      onRequestSaveDecision,
      onRequestTerminologyFeedback,
      onRequestTerminologyMetadata,
      onRequestTerminologyResolution,
      onUseConflictCurrent,
      onRetryValidationSave,
      pendingValidationSave,
      projectAssignmentMutation.isPending,
      projectDueDateMutation.isPending,
      projectRequestMutation.isPending,
      projectStatusMutation.isPending,
    ],
  );
}
