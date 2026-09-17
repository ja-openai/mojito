import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';

import {
  type AiReviewMessage,
  type AiReviewSuggestion,
  formatAiReviewError,
  requestAiReview,
} from '../../api/ai-review';
import {
  type ApiGlossaryTerm,
  type ApiMatchedGlossaryTerm,
  fetchGlossaries,
  fetchGlossaryTerms,
  matchGlossaryTerms,
} from '../../api/glossaries';
import {
  type ApiAiTranslateTextUnitAttempt,
  type ApiGitBlameWithUsage,
  type ApiTextUnitHistoryItem,
  deleteTextUnitCurrentVariant,
  fetchAiTranslateTextUnitAttempts,
  fetchGitBlameWithUsages,
  fetchSourceTextUnit,
  fetchTextUnitHistory,
  saveTextUnit,
  type SaveTextUnitRequest,
  searchTextUnits,
  type TextUnitSearchRequest,
} from '../../api/text-units';
import { isMf2Message } from '../../components/mf2/messageFormat';
import {
  mf2TranslationErrorCount,
  mf2TranslationErrors,
} from '../../components/mf2/translationValidation';
import type { VisibleTextMarksMode } from '../../components/VisibleTextEditor';
import { useAiReviewPreferences } from '../../hooks/useAiReviewPreferences';
import { useProtectedTextTokenGuard } from '../../hooks/useProtectedTextTokenGuard';
import { useReviewProjectSearchEnabled } from '../../hooks/useReviewProjectSearchEnabled';
import { useTextUnitReviewFeedback } from '../../hooks/useTextUnitReviewFeedback';
import { useUser } from '../../hooks/useUser';
import { useVisibleTextEditorEnabled } from '../../hooks/useVisibleTextEditorEnabled';
import { buildAiTranslateAttemptTimelineData } from '../../utils/aiTranslateHistory';
import {
  buildGlossaryContextMessage,
  filterSelfGlossaryMatches,
  prepareGlossaryMatches,
} from '../../utils/glossary-matches';
import {
  findGlossaryTargetForTextUnit,
  findGlossaryTermByTmTextUnitId,
} from '../../utils/glossaryTermLookup';
import {
  buildIntegrityCheckErrorReport,
  checkTextUnitIntegrityWithRetry,
  INTEGRITY_CHECK_FAILURE_MESSAGE,
  INTEGRITY_CHECK_UNAVAILABLE_MESSAGE,
  INTEGRITY_CHECK_UNAVAILABLE_TITLE,
} from '../../utils/integrityCheck';
import { isRtlLocale } from '../../utils/localeDirection';
import { canEditLocale as canEditLocaleForUser } from '../../utils/permissions';
import { buildTextUnitDetailUrl } from '../../utils/textUnitDetailUrl';
import { readWorkbenchDetailContext } from '../workbench/workbench-detail-link';
import { formatStatus, mapUiStatusToApi } from '../workbench/workbench-helpers';
import type { WorkbenchReturnState } from '../workbench/workbench-types';
import {
  type TextUnitDetailAiMessage,
  type TextUnitDetailHistoryComment,
  type TextUnitDetailHistoryRow,
  type TextUnitDetailMetaRow,
  type TextUnitDetailMetaSection,
  TextUnitDetailPageView,
  type TextUnitDetailScreenshot,
} from './TextUnitDetailPageView';
import { isTextUnitDetailDraftDirty, useTextUnitDetailDraft } from './useTextUnitDetailDraft';

type LocationState = {
  from?: string;
  workbenchSearch?: TextUnitSearchRequest | null;
  workbenchScrollTop?: number | null;
  workbenchRowId?: string | null;
  workbenchUrl?: string;
  workbenchReturn?: WorkbenchReturnState;
};

const editorStatusOptions = ['Accepted', 'To review', 'To translate', 'Rejected'];
const DEFAULT_AI_REVIEW_PROMPT = 'Review the translation and suggest improvements.';

type EmbeddedEditor = {
  tmTextUnitId: number;
  localeTag: string;
  onClose: () => void;
  onSaved: () => void;
  presentation?: 'compact' | 'full';
  onShowDetails?: () => void;
  onShowCompact?: () => void;
  onNext?: () => void;
  navigationKey?: string | number;
  autoFocus?: boolean;
};

export function TextUnitDetailPage({ embedded }: { embedded?: EmbeddedEditor } = {}) {
  const { tmTextUnitId: tmTextUnitIdParam } = useParams<{ tmTextUnitId: string }>();
  const parsedTmTextUnitId =
    embedded?.tmTextUnitId ?? (tmTextUnitIdParam ? Number(tmTextUnitIdParam) : NaN);
  const tmTextUnitId =
    Number.isFinite(parsedTmTextUnitId) && parsedTmTextUnitId > 0 ? parsedTmTextUnitId : null;

  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const locationState = (location.state as LocationState | null) ?? null;
  const currentUser = useUser();
  const aiSettings = useAiReviewPreferences();
  const aiPreset = aiSettings.preset;
  const aiReviewStyle = aiSettings.reviewStyle;
  const aiPreferencesReady = aiSettings.ready;
  const aiAutomaticDisabled = aiSettings.automaticDisabled;
  const queryClient = useQueryClient();

  const localeTag = embedded?.localeTag ?? searchParams.get('locale')?.trim() ?? null;
  const editorOwner = `${currentUser.username}:${tmTextUnitId}:${localeTag}`;
  const isEmbedded = Boolean(embedded);
  const isCompact = embedded?.presentation === 'compact';
  const retainedEditor = useTextUnitDetailDraft(embedded ? editorOwner : null);
  const { draft: editorDraft, setField: setDraftField, update: updateDraft } = retainedEditor;
  const {
    draftTarget,
    baselineTarget,
    baselineVariantId,
    draftStatus,
    baselineStatus,
    targetCommentDraft,
    isTargetCommentEditing,
  } = editorDraft;
  const setDraftTarget = useCallback(
    (value: string) => setDraftField('draftTarget', value),
    [setDraftField],
  );
  const setBaselineTarget = useCallback(
    (value: string) => setDraftField('baselineTarget', value),
    [setDraftField],
  );
  const setBaselineVariantId = useCallback(
    (value: number | null) => setDraftField('baselineVariantId', value),
    [setDraftField],
  );
  const setDraftStatus = useCallback(
    (value: typeof draftStatus) => setDraftField('draftStatus', value),
    [setDraftField],
  );
  const setBaselineStatus = useCallback(
    (value: typeof baselineStatus) => setDraftField('baselineStatus', value),
    [setDraftField],
  );
  const setTargetCommentDraft = useCallback(
    (value: string) => setDraftField('targetCommentDraft', value),
    [setDraftField],
  );
  const setIsTargetCommentEditing = useCallback(
    (value: boolean) => setDraftField('isTargetCommentEditing', value),
    [setDraftField],
  );
  const seededEditorRef = useRef<{ owner: string; version: string } | null>(
    embedded && editorDraft.seedKey ? { owner: editorOwner, version: editorDraft.seedKey } : null,
  );
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const [isHistoryCollapsed, setIsHistoryCollapsed] = useState(true);
  const [isMetaCollapsed, setIsMetaCollapsed] = useState(true);
  const [isGlossaryCollapsed, setIsGlossaryCollapsed] = useState(Boolean(embedded));
  const [isIcuPreviewCollapsed, setIsIcuPreviewCollapsed] = useState(true);
  const [icuPreviewMode, setIcuPreviewMode] = useState<'source' | 'target'>('target');
  const [isAiCollapsed, setIsAiCollapsed] = useState(false);
  const isVisibleTextEditorEnabled = useVisibleTextEditorEnabled();
  const isSearchEnabled = useReviewProjectSearchEnabled();
  const [translationMarksMode, setTranslationMarksMode] = useState<VisibleTextMarksMode>('auto');

  const [saveErrorMessage, setSaveErrorMessage] = useState<string | null>(null);
  const [pendingValidationSave, setPendingValidationSave] = useState<{
    request: SaveTextUnitRequest;
    title: string;
    body: string;
    failureDetail?: string | null;
    reportMessage?: string | null;
    reportHtml?: string | null;
    canBypass?: boolean;
    canRetry?: boolean;
  } | null>(null);
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);
  const [showDiscardDialog, setShowDiscardDialog] = useState(false);
  const [isValidatingSave, setIsValidatingSave] = useState(false);
  const saveAttemptRef = useRef(0);
  const saveInFlightRef = useRef(false);
  const navigationVersionRef = useRef(0);
  const nextAfterSaveRef = useRef<{
    request: SaveTextUnitRequest;
    version: number;
    onNext: () => void;
  } | null>(null);
  useEffect(() => {
    navigationVersionRef.current += 1;
    nextAfterSaveRef.current = null;
  }, [editorOwner, embedded?.navigationKey]);

  const aiRequestAttemptRef = useRef(0);
  const aiRequestAbortControllerRef = useRef<AbortController | null>(null);
  const [aiInput, setAiInput] = useState('');
  const [isAiResponding, setIsAiResponding] = useState(false);

  const isSourceOnly = !localeTag;
  const workbenchDetailContext = useMemo(
    () =>
      !embedded && locationState?.from !== '/workbench' && localeTag
        ? readWorkbenchDetailContext(location.hash)
        : null,
    [embedded, location.hash, locationState?.from, localeTag],
  );

  const textUnitQuery = useQuery({
    queryKey: ['text-unit-detail', tmTextUnitId, localeTag ?? 'source'],
    enabled: tmTextUnitId !== null,
    staleTime: isEmbedded ? 0 : 30_000,
    refetchOnMount: isEmbedded ? 'always' : true,
    refetchOnWindowFocus: false,
    queryFn: async () => {
      if (tmTextUnitId === null) {
        return null;
      }
      if (!localeTag) {
        return fetchSourceTextUnit(tmTextUnitId);
      }

      const results = await searchTextUnits({
        repositoryIds: [],
        localeTags: [localeTag],
        searchAttribute: 'tmTextUnitIds',
        searchType: 'exact',
        searchText: String(tmTextUnitId),
        limit: 5,
        offset: 0,
      });

      return results.find((item) => item.targetLocale === localeTag) ?? results[0] ?? null;
    },
  });

  const historyQuery = useQuery({
    queryKey: ['text-unit-history', tmTextUnitId, localeTag],
    enabled: !isCompact && tmTextUnitId !== null && Boolean(localeTag) && !isHistoryCollapsed,
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    queryFn: () => {
      if (tmTextUnitId === null || !localeTag) {
        return Promise.resolve([] as ApiTextUnitHistoryItem[]);
      }
      return fetchTextUnitHistory(tmTextUnitId, localeTag);
    },
  });

  const aiTranslateAttemptsQuery = useQuery({
    queryKey: ['text-unit-ai-translate-attempts', tmTextUnitId, localeTag],
    enabled: !isCompact && tmTextUnitId !== null && Boolean(localeTag) && !isHistoryCollapsed,
    staleTime: 0,
    refetchOnWindowFocus: false,
    queryFn: () => {
      if (tmTextUnitId === null || !localeTag) {
        return Promise.resolve([] as ApiAiTranslateTextUnitAttempt[]);
      }
      return fetchAiTranslateTextUnitAttempts(tmTextUnitId, localeTag);
    },
  });

  const gitBlameQuery = useQuery({
    queryKey: ['text-unit-git-blame', tmTextUnitId],
    enabled: !isCompact && tmTextUnitId !== null,
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    queryFn: () => {
      if (tmTextUnitId === null) {
        return Promise.resolve([] as ApiGitBlameWithUsage[]);
      }
      return fetchGitBlameWithUsages(tmTextUnitId);
    },
  });

  const activeTextUnit = textUnitQuery.data;
  const localeForEditing = localeTag;
  const displayLocale = localeTag ?? activeTextUnit?.targetLocale ?? null;

  const glossaryTargetsQuery = useQuery({
    queryKey: ['text-unit-detail-glossary-targets'],
    enabled:
      !isCompact &&
      Boolean(activeTextUnit?.repositoryName?.trim()) &&
      Boolean(activeTextUnit?.assetPath),
    staleTime: 60_000,
    refetchOnWindowFocus: false,
    queryFn: () => fetchGlossaries({ limit: 200 }),
  });
  const glossaryTermTarget = useMemo(
    () =>
      findGlossaryTargetForTextUnit(glossaryTargetsQuery.data?.glossaries ?? [], {
        repositoryName: activeTextUnit?.repositoryName,
        assetPath: activeTextUnit?.assetPath,
      }),
    [
      activeTextUnit?.assetPath,
      activeTextUnit?.repositoryName,
      glossaryTargetsQuery.data?.glossaries,
    ],
  );
  const glossaryTermQuery = useQuery({
    queryKey: [
      'text-unit-detail-glossary-term',
      glossaryTermTarget?.glossaryId ?? null,
      activeTextUnit?.tmTextUnitId ?? null,
      activeTextUnit?.source ?? null,
      localeForEditing,
    ],
    enabled:
      !isCompact &&
      glossaryTermTarget != null &&
      activeTextUnit?.tmTextUnitId != null &&
      Boolean(activeTextUnit?.source?.trim()),
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    queryFn: async () => {
      if (
        glossaryTermTarget == null ||
        activeTextUnit?.tmTextUnitId == null ||
        !activeTextUnit.source?.trim()
      ) {
        return null as ApiGlossaryTerm | null;
      }
      const response = await fetchGlossaryTerms(glossaryTermTarget.glossaryId, {
        search: activeTextUnit.source,
        localeTags: localeForEditing ? [localeForEditing] : [],
        limit: 25,
      });
      return findGlossaryTermByTmTextUnitId(response.terms, activeTextUnit.tmTextUnitId);
    },
  });

  const glossaryMatchesQuery = useQuery({
    queryKey: [
      'text-unit-glossary-matches',
      activeTextUnit?.repositoryName ?? null,
      localeForEditing,
      activeTextUnit?.source ?? null,
      activeTextUnit?.tmTextUnitId ?? null,
    ],
    enabled:
      !isCompact &&
      Boolean(activeTextUnit?.repositoryName?.trim()) &&
      Boolean(localeForEditing) &&
      Boolean(activeTextUnit?.source?.trim()),
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    queryFn: async () => {
      if (
        !activeTextUnit?.repositoryName?.trim() ||
        !localeForEditing ||
        !activeTextUnit?.source?.trim()
      ) {
        return [] as ApiMatchedGlossaryTerm[];
      }

      const response = await matchGlossaryTerms({
        repositoryName: activeTextUnit.repositoryName,
        localeTag: localeForEditing,
        sourceText: activeTextUnit.source,
        excludeTmTextUnitId: activeTextUnit.tmTextUnitId,
      });

      return prepareGlossaryMatches(
        filterSelfGlossaryMatches(response.matchedTerms, activeTextUnit.tmTextUnitId),
      );
    },
  });

  const hasRetainedChanges = Boolean(embedded) && isTextUnitDetailDraftDirty(editorDraft);
  const staleDraft =
    hasRetainedChanges &&
    editorDraft.seeded &&
    activeTextUnit &&
    (editorDraft.source !== (activeTextUnit.source ?? '') ||
      editorDraft.messageFormat !== (activeTextUnit.messageFormat ?? null) ||
      editorDraft.baselineVariantId !== (activeTextUnit.tmTextUnitVariantId ?? null));
  const canEdit = Boolean(
    (!isEmbedded || Boolean(activeTextUnit)) &&
    !staleDraft &&
    localeForEditing &&
    canEditLocaleForUser(currentUser, localeForEditing),
  );
  const feedback = useTextUnitReviewFeedback({
    username: currentUser.username,
    tmTextUnitId: activeTextUnit?.tmTextUnitId ?? null,
    localeId: activeTextUnit?.localeId ?? null,
    variantId: baselineVariantId,
    baselineTarget,
    target: draftTarget,
    enabled: canEdit && !isSourceOnly,
    problematic: draftStatus === 'Rejected',
    retainedDraft: embedded
      ? {
          values: editorDraft.feedback,
          onChange: (change) =>
            updateDraft((current) => ({
              ...current,
              feedback: change(current.feedback),
            })),
        }
      : undefined,
  });
  const {
    decorateRequest: decorateFeedbackRequest,
    reset: resetFeedback,
    saved: feedbackSaved,
    recordChatUsed,
    recordSuggestionUsed,
  } = feedback;
  const editorOwnerRef = useRef(editorOwner);
  editorOwnerRef.current = editorOwner;
  useEffect(() => {
    saveAttemptRef.current += 1;
    setIsValidatingSave(false);
    setShowDiscardDialog(false);
    return () => {
      saveAttemptRef.current += 1;
    };
  }, [editorOwner]);

  const saveMutation = useMutation({
    mutationFn: (request: SaveTextUnitRequest) => saveTextUnit(request),
    onMutate: () => ({
      owner: editorOwner,
      operation: embedded ? retainedEditor.beginOperation() : null,
      next: nextAfterSaveRef.current,
    }),
    onSuccess: (saved, request, context) => {
      if (!embedded) feedbackSaved(request);
      if (editorOwnerRef.current !== context.owner) {
        return;
      }
      const nextTarget = saved.target ?? request.target;
      const nextStatus = normalizeEditorStatus(
        formatStatus(saved.status ?? request.status, saved.includedInLocalizedFile),
      );

      if (embedded && context.operation) {
        retainedEditor.finishOperation(context.operation, (current) => ({
          ...current,
          baselineTarget: nextTarget,
          baselineVariantId: saved.tmTextUnitVariantId ?? request.reviewedVariantId ?? null,
          draftTarget: nextTarget,
          baselineStatus: nextStatus,
          draftStatus: nextStatus,
          baselineComment:
            'targetComment' in request
              ? (saved.targetComment ?? request.targetComment ?? '')
              : current.baselineComment,
          targetCommentDraft:
            'targetComment' in request
              ? (saved.targetComment ?? request.targetComment ?? '')
              : current.targetCommentDraft,
          isTargetCommentEditing:
            'targetComment' in request ? false : current.isTargetCommentEditing,
          feedback: { reason: '', note: '', chatUsed: false, aiSuggestionUsed: false },
          operationError: null,
        }));
        embedded.onSaved();
      } else {
        setBaselineTarget(nextTarget);
        setBaselineVariantId(saved.tmTextUnitVariantId ?? request.reviewedVariantId ?? null);
        setDraftTarget(nextTarget);
        setBaselineStatus(nextStatus);
        setDraftStatus(nextStatus);
      }
      if (mountedRef.current) {
        setSaveErrorMessage(null);
        setPendingValidationSave(null);
        if ('targetComment' in request) {
          setTargetCommentDraft(saved.targetComment ?? request.targetComment ?? '');
          setIsTargetCommentEditing(false);
          setIsMetaCollapsed(false);
        } else if (!embedded) {
          setIsHistoryCollapsed(false);
        }
      }

      void queryClient.invalidateQueries({
        queryKey: ['text-unit-detail', tmTextUnitId, localeTag],
      });
      void queryClient.invalidateQueries({
        queryKey: ['text-unit-history', tmTextUnitId, localeTag],
      });
      void queryClient.invalidateQueries({ queryKey: ['workbench-search'] });
      if (
        mountedRef.current &&
        context.next?.request === request &&
        context.next.version === navigationVersionRef.current
      ) {
        nextAfterSaveRef.current = null;
        context.next.onNext();
      }
    },
    onError: (error: unknown, _request, context) => {
      if (embedded && context?.operation) {
        retainedEditor.finishOperation(context.operation, (current) => ({
          ...current,
          operationError: error instanceof Error ? error.message : 'Unable to save translation.',
        }));
      }
      if (!mountedRef.current) return;
      if (context && editorOwnerRef.current !== context.owner) {
        return;
      }
      const status = (error as { status?: number })?.status;
      if (status === 409) {
        // Refresh the baseline for explicit Reset; retained edits stay owned by the draft.
        void queryClient.invalidateQueries({
          queryKey: ['text-unit-detail', tmTextUnitId, localeTag],
        });
      }
      if (status === 403) {
        setSaveErrorMessage('You cannot edit this locale.');
        return;
      }
      setSaveErrorMessage(error instanceof Error ? error.message : 'Unable to save translation.');
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (textUnitCurrentVariantId: number) =>
      deleteTextUnitCurrentVariant(textUnitCurrentVariantId),
    onMutate: () => ({ operation: embedded ? retainedEditor.beginOperation() : null }),
    onSuccess: (_result, _request, context) => {
      if (embedded && context.operation) {
        retainedEditor.finishOperation(context.operation, (current) => ({
          ...current,
          baselineTarget: '',
          baselineVariantId: null,
          draftTarget: '',
          baselineStatus: 'To translate',
          draftStatus: 'To translate',
          feedback: { reason: '', note: '', chatUsed: false, aiSuggestionUsed: false },
          operationError: null,
        }));
        embedded.onSaved();
      }
      if (!mountedRef.current) {
        void queryClient.invalidateQueries({
          queryKey: ['text-unit-detail', tmTextUnitId, localeTag],
        });
        return;
      }
      resetFeedback();
      setBaselineTarget('');
      setBaselineVariantId(null);
      setDraftTarget('');
      setBaselineStatus('To translate');
      setDraftStatus('To translate');
      setSaveErrorMessage(null);
      setPendingValidationSave(null);
      setShowDeleteDialog(false);
      setIsHistoryCollapsed(false);

      void queryClient.invalidateQueries({
        queryKey: ['text-unit-detail', tmTextUnitId, localeTag],
      });
      void queryClient.invalidateQueries({
        queryKey: ['text-unit-history', tmTextUnitId, localeTag],
      });
      void queryClient.invalidateQueries({ queryKey: ['workbench-search'] });
    },
    onError: (error: unknown, _request, context) => {
      if (embedded && context?.operation) {
        retainedEditor.finishOperation(context.operation, (current) => ({
          ...current,
          operationError: error instanceof Error ? error.message : 'Unable to delete translation.',
        }));
      }
      if (!mountedRef.current) return;
      setShowDeleteDialog(false);
      const status = (error as { status?: number })?.status;
      if (status === 403) {
        setSaveErrorMessage('You cannot delete this locale translation.');
        return;
      }
      setSaveErrorMessage(error instanceof Error ? error.message : 'Unable to delete translation.');
    },
  });

  const isMf2Translation =
    !isSourceOnly &&
    isMf2Message({
      messageFormat: activeTextUnit?.messageFormat,
      source: activeTextUnit?.source,
    });
  const useProtectedDetailEditor = isVisibleTextEditorEnabled && !isSourceOnly && !isMf2Translation;
  const mf2ErrorCount = useMemo(
    () =>
      isMf2Translation
        ? mf2TranslationErrorCount({
            locale: localeForEditing ?? 'en',
            source: activeTextUnit?.source ?? '',
            target: draftTarget,
          })
        : 0,
    [activeTextUnit?.source, draftTarget, isMf2Translation, localeForEditing],
  );
  const draftTargetTokenGuard = useProtectedTextTokenGuard(
    draftTarget,
    useProtectedDetailEditor ? 'icu-html' : 'none',
  );
  const draftTargetProtectedTokens = draftTargetTokenGuard.protectedTokens;
  const draftTargetProtectedDiagnostics = draftTargetTokenGuard.diagnostics;
  const validateDraftTarget = draftTargetTokenGuard.validateNextValue;
  const editorDirection = displayLocale && isRtlLocale(displayLocale) ? 'rtl' : 'ltr';

  const editorSeedKey = useMemo(() => {
    if (!activeTextUnit) {
      return null;
    }
    const status = normalizeEditorStatus(
      formatStatus(activeTextUnit.status, activeTextUnit.includedInLocalizedFile),
    );
    const variantId =
      activeTextUnit.tmTextUnitVariantId ?? activeTextUnit.tmTextUnitCurrentVariantId;
    return [
      activeTextUnit.tmTextUnitId,
      displayLocale ?? '',
      variantId ?? 'none',
      isSourceOnly ? (activeTextUnit.source ?? '') : (activeTextUnit.target ?? ''),
      status,
    ].join(':');
  }, [activeTextUnit, displayLocale, isSourceOnly]);

  useEffect(() => {
    if (!activeTextUnit || editorSeedKey === null) {
      return;
    }
    const sameOwner = seededEditorRef.current?.owner === editorOwner;
    if (
      sameOwner &&
      // Effect replay can clear a clean retained draft while preserving this ref.
      editorDraft.seeded &&
      (seededEditorRef.current?.version === editorSeedKey ||
        draftTarget !== baselineTarget ||
        draftStatus !== baselineStatus ||
        feedback.dirty ||
        (isEmbedded && (isTextUnitDetailDraftDirty(editorDraft) || editorDraft.pendingOperation)))
    ) {
      return;
    }
    seededEditorRef.current = { owner: editorOwner, version: editorSeedKey };
    updateDraft((current) => ({
      ...current,
      seeded: true,
      source: activeTextUnit.source ?? '',
      messageFormat: activeTextUnit.messageFormat ?? null,
      seedKey: editorSeedKey,
      baselineComment: activeTextUnit.targetComment ?? '',
    }));

    const nextTarget = isSourceOnly ? (activeTextUnit.source ?? '') : (activeTextUnit.target ?? '');
    const nextStatus = normalizeEditorStatus(
      formatStatus(activeTextUnit.status, activeTextUnit.includedInLocalizedFile),
    );

    setBaselineTarget(nextTarget);
    setBaselineVariantId(activeTextUnit.tmTextUnitVariantId ?? null);
    setDraftTarget(nextTarget);
    setBaselineStatus(nextStatus);
    setDraftStatus(nextStatus);
    setSaveErrorMessage(null);
    setPendingValidationSave(null);
  }, [
    activeTextUnit,
    activeTextUnit?.includedInLocalizedFile,
    activeTextUnit?.source,
    activeTextUnit?.status,
    activeTextUnit?.target,
    editorSeedKey,
    editorOwner,
    baselineStatus,
    baselineTarget,
    draftStatus,
    draftTarget,
    feedback.dirty,
    isSourceOnly,
    isEmbedded,
    editorDraft,
    updateDraft,
    setBaselineStatus,
    setBaselineTarget,
    setBaselineVariantId,
    setDraftStatus,
    setDraftTarget,
  ]);

  useEffect(() => {
    if (!embedded) setIsTargetCommentEditing(false);
  }, [activeTextUnit?.tmTextUnitId, displayLocale, embedded, setIsTargetCommentEditing]);

  useEffect(() => {
    if (!isTargetCommentEditing) {
      setTargetCommentDraft(activeTextUnit?.targetComment ?? '');
    }
  }, [activeTextUnit?.targetComment, isTargetCommentEditing, setTargetCommentDraft]);

  const aiContextKey = useMemo(() => {
    if (!activeTextUnit || !localeForEditing) {
      return null;
    }
    const variantId =
      activeTextUnit.tmTextUnitVariantId ?? activeTextUnit.tmTextUnitCurrentVariantId;
    return `${currentUser.username}:${activeTextUnit.tmTextUnitId}:${localeForEditing}:${variantId ?? 'none'}:${aiPreset}:${aiReviewStyle}`;
  }, [activeTextUnit, localeForEditing, currentUser.username, aiPreset, aiReviewStyle]);

  const [openedAiContextKey, setOpenedAiContextKey] = useState<string | null>(null);
  // Keep an opened conversation active when the same editor moves back inline.
  useEffect(() => {
    if (!isCompact && !isAiCollapsed) setOpenedAiContextKey(aiContextKey);
  }, [aiContextKey, isCompact, isAiCollapsed]);
  const isAiContextOpen = !isEmbedded || openedAiContextKey === aiContextKey;

  const [storedAiConversation, setStoredAiConversation] = useState<{
    contextKey: string | null;
    messages: TextUnitDetailAiMessage[];
  }>(() => ({ contextKey: aiContextKey, messages: [] }));
  const aiMessages = useMemo(
    () => (storedAiConversation.contextKey === aiContextKey ? storedAiConversation.messages : []),
    [aiContextKey, storedAiConversation],
  );
  const setAiMessages = useCallback(
    (next: React.SetStateAction<TextUnitDetailAiMessage[]>) => {
      setStoredAiConversation((previous) => ({
        contextKey: aiContextKey,
        messages:
          typeof next === 'function'
            ? next(previous.contextKey === aiContextKey ? previous.messages : [])
            : next,
      }));
    },
    [aiContextKey],
  );

  useEffect(() => {
    aiRequestAttemptRef.current += 1;
    aiRequestAbortControllerRef.current?.abort();
    aiRequestAbortControllerRef.current = null;
    setAiMessages([]);
    setAiInput('');
    setIsAiResponding(false);
    return () => {
      aiRequestAttemptRef.current += 1;
      aiRequestAbortControllerRef.current?.abort();
      aiRequestAbortControllerRef.current = null;
    };
  }, [aiContextKey, setAiMessages]);

  useEffect(() => {
    if (
      !aiPreferencesReady ||
      aiAutomaticDisabled ||
      !isAiContextOpen ||
      glossaryMatchesQuery.isLoading ||
      !activeTextUnit ||
      !localeForEditing ||
      aiContextKey === null
    ) {
      return;
    }

    let cancelled = false;
    const requestAttempt = (aiRequestAttemptRef.current += 1);
    aiRequestAbortControllerRef.current?.abort();
    const abortController = new AbortController();
    aiRequestAbortControllerRef.current = abortController;
    setAiMessages([]);
    setAiInput('');
    setIsAiResponding(true);

    const initialMessage: AiReviewMessage = {
      role: 'user',
      content: DEFAULT_AI_REVIEW_PROMPT,
    };
    const glossaryContextMessage = buildGlossaryContextMessage(glossaryMatchesQuery.data);

    void (async () => {
      try {
        const response = await requestAiReview(
          {
            presetId: aiPreset,
            reviewStyle: aiReviewStyle,
            requestType: 'automatic',
            surface: 'text_unit_detail',
            source: activeTextUnit.source ?? '',
            target: activeTextUnit.target ?? '',
            localeTag: localeForEditing,
            sourceDescription: activeTextUnit.comment ?? '',
            tmTextUnitId: activeTextUnit.tmTextUnitId,
            messages: [glossaryContextMessage, initialMessage].filter(
              (message): message is AiReviewMessage => message != null,
            ),
          },
          { signal: abortController.signal },
        );
        if (cancelled || aiRequestAttemptRef.current !== requestAttempt) {
          return;
        }

        setAiMessages([
          {
            id: `assistant-${Date.now()}`,
            sender: 'assistant',
            content: response.message.content,
            suggestions: response.suggestions,
            review: response.review,
          },
        ]);
      } catch (error: unknown) {
        if (cancelled || aiRequestAttemptRef.current !== requestAttempt) {
          return;
        }

        const aiError = formatAiReviewError(error);
        setAiMessages([
          {
            id: `assistant-error-${Date.now()}`,
            sender: 'assistant',
            content: aiError.message,
            isError: true,
            errorDetail: aiError.detail,
          },
        ]);
      } finally {
        if (!cancelled && aiRequestAttemptRef.current === requestAttempt) {
          setIsAiResponding(false);
        }
        if (aiRequestAbortControllerRef.current === abortController) {
          aiRequestAbortControllerRef.current = null;
        }
      }
    })();

    return () => {
      cancelled = true;
      abortController.abort();
      if (aiRequestAbortControllerRef.current === abortController) {
        aiRequestAttemptRef.current += 1;
        aiRequestAbortControllerRef.current = null;
        setIsAiResponding(false);
      }
    };
  }, [
    activeTextUnit,
    aiContextKey,
    aiPreset,
    aiReviewStyle,
    aiPreferencesReady,
    aiAutomaticDisabled,
    isAiContextOpen,
    glossaryMatchesQuery.data,
    glossaryMatchesQuery.isLoading,
    localeForEditing,
    setAiMessages,
  ]);

  const sortedHistoryItems = useMemo(() => {
    return [...(historyQuery.data ?? [])].sort((a, b) => {
      const dateDelta = safeDateValue(b.createdDate) - safeDateValue(a.createdDate);
      if (dateDelta !== 0) {
        return dateDelta;
      }
      return (b.id ?? 0) - (a.id ?? 0);
    });
  }, [historyQuery.data]);

  const aiTranslateTimelineData = useMemo<
    ReturnType<typeof buildAiTranslateAttemptTimelineData>
  >(() => {
    if (tmTextUnitId === null || !localeTag) {
      return { byVariantId: new Map(), unlinked: [] };
    }
    return buildAiTranslateAttemptTimelineData(
      aiTranslateAttemptsQuery.data ?? [],
      tmTextUnitId,
      localeTag,
    );
  }, [aiTranslateAttemptsQuery.data, localeTag, tmTextUnitId]);

  const gitBlame = useMemo(() => gitBlameQuery.data?.[0] ?? null, [gitBlameQuery.data]);
  const sourceScreenshots = useMemo<TextUnitDetailScreenshot[]>(
    () => (gitBlame?.screenshots ?? []).filter((screenshot) => Boolean(screenshot.src?.trim())),
    [gitBlame?.screenshots],
  );

  const textUnitLocation = useMemo(() => {
    const usagesFromGitBlame = gitBlame?.usages ?? [];
    if (usagesFromGitBlame.length > 0) {
      return usagesFromGitBlame.join(', ');
    }

    const usages =
      textUnitQuery.data?.assetTextUnitUsages
        ?.split(',')
        .map((value) => value.trim())
        .filter(Boolean) ?? [];
    return usages.length > 0 ? usages.join(', ') : null;
  }, [gitBlame?.usages, textUnitQuery.data?.assetTextUnitUsages]);

  const metaSections = useMemo<TextUnitDetailMetaSection[]>(() => {
    const textUnitRows: TextUnitDetailMetaRow[] = [
      { label: 'Repository', value: formatValue(activeTextUnit?.repositoryName) },
      { label: isSourceOnly ? 'Source locale' : 'Locale', value: formatValue(displayLocale) },
      { label: 'Created', value: formatDateTime(textUnitQuery.data?.tmTextUnitCreatedDate) },
      { label: 'Translated', value: formatDateTime(textUnitQuery.data?.createdDate) },
      { label: 'AssetPath', value: formatValue(textUnitQuery.data?.assetPath) },
      { label: 'PluralForm', value: formatValue(textUnitQuery.data?.pluralForm) },
      { label: 'PluralFormOther', value: formatValue(textUnitQuery.data?.pluralFormOther) },
      {
        label: 'TargetComment',
        value: formatValue(textUnitQuery.data?.targetComment),
        kind: 'targetComment',
      },
      { label: 'Location', value: formatValue(textUnitLocation) },
    ];

    const gitBlameRows: TextUnitDetailMetaRow[] = [
      { label: 'Author', value: formatValue(gitBlame?.gitBlame?.authorName) },
      { label: 'Email', value: formatValue(gitBlame?.gitBlame?.authorEmail) },
      { label: 'Commit', value: formatValue(gitBlame?.gitBlame?.commitName) },
      { label: 'Commit date', value: formatGitCommitTime(gitBlame?.gitBlame?.commitTime) },
    ];

    const moreRows: TextUnitDetailMetaRow[] = [
      { label: 'Virtual', value: formatBoolean(gitBlame?.isVirtual) },
      { label: 'TmTextUnitId', value: formatNumberish(textUnitQuery.data?.tmTextUnitId) },
      {
        label: 'TmTextUnitVariantId',
        value: formatNumberish(textUnitQuery.data?.tmTextUnitVariantId),
      },
      {
        label: 'TmTextUnitCurrentVariantId',
        value: formatNumberish(textUnitQuery.data?.tmTextUnitCurrentVariantId),
      },
      { label: 'AssetTextUnitId', value: formatNumberish(textUnitQuery.data?.assetTextUnitId) },
      { label: 'AssetId', value: formatNumberish(textUnitQuery.data?.assetId) },
      {
        label: 'LastSuccessfulAssetExtractionId',
        value: formatNumberish(textUnitQuery.data?.lastSuccessfulAssetExtractionId),
      },
      { label: 'AssetExtractionId', value: formatNumberish(textUnitQuery.data?.assetExtractionId) },
      { label: 'Branch', value: formatValue(gitBlame?.branch?.name) },
      { label: 'Screenshots', value: formatScreenshots(gitBlame?.screenshots) },
    ];

    return [
      { title: 'Text unit', rows: textUnitRows },
      { title: 'Git blame', rows: gitBlameRows },
      { title: 'More', rows: moreRows },
    ];
  }, [
    activeTextUnit?.repositoryName,
    displayLocale,
    gitBlame,
    isSourceOnly,
    textUnitLocation,
    textUnitQuery.data,
  ]);

  const historyRows = useMemo<TextUnitDetailHistoryRow[]>(() => {
    const historyLocaleTag = localeTag ?? activeTextUnit?.targetLocale ?? null;
    const historyEntries = sortedHistoryItems.map((item) => {
      const comments: TextUnitDetailHistoryComment[] = (item.tmTextUnitVariantComments ?? []).map(
        (comment, index) => ({
          key:
            comment.id != null
              ? String(comment.id)
              : `${comment.type ?? ''}-${comment.severity ?? ''}-${comment.content ?? ''}-${index}`,
          type: formatValue(comment.type),
          severity: formatValue(comment.severity),
          content: formatValue(comment.content),
        }),
      );
      const sourceTmTextUnitId = item.leveraging?.sourceTmTextUnitId;
      const sourceTmTextUnitVariantId = item.leveraging?.sourceTmTextUnitVariantId;
      const leveragingType = item.leveraging?.leveragingType?.trim() || null;
      const isLeveraged =
        typeof sourceTmTextUnitId === 'number' && typeof sourceTmTextUnitVariantId === 'number';
      const aiTranslateAttempts = aiTranslateTimelineData.byVariantId.get(item.id) ?? [];
      const badges = [
        ...(isLeveraged ? ['Leveraged'] : []),
        ...(aiTranslateAttempts.length > 0 ? ['AI Translate'] : []),
      ];

      return {
        timestamp: safeDateValue(item.createdDate),
        row: {
          key: String(item.id),
          variantId: String(item.id),
          userName: formatValue(item.createdByUser?.username ?? 'Unknown user'),
          translation: formatHistoryTranslation(item.content),
          date: formatDateTime(item.createdDate),
          status: formatValue(formatHistoryStatus(item.status, item.includedInLocalizedFile)),
          comments,
          aiTranslateAttempts,
          badges: badges.length > 0 ? badges : undefined,
          sourceLink: isLeveraged
            ? {
                label: `Source variant #${sourceTmTextUnitVariantId}`,
                to: {
                  pathname: `/text-units/${sourceTmTextUnitId}`,
                  search: historyLocaleTag ? `?locale=${encodeURIComponent(historyLocaleTag)}` : '',
                },
                title: leveragingType ?? 'Open leveraged source',
              }
            : null,
        },
      };
    });

    const unlinkedAiTranslateEntries = aiTranslateTimelineData.unlinked.map((attempt) => ({
      timestamp: safeDateValue(attempt.createdDate),
      row: {
        key: `ai-translate-${attempt.key}`,
        title: 'AI Translate attempt',
        userName: 'AI Translate',
        translation: '<no imported translation>',
        date: formatDateTime(attempt.createdDate),
        status: formatValue(attempt.status),
        comments: [],
        aiTranslateAttempts: [attempt],
        badges: ['AI Translate'],
      },
    }));

    return [...historyEntries, ...unlinkedAiTranslateEntries]
      .sort((a, b) => b.timestamp - a.timestamp)
      .map((entry) => entry.row);
  }, [activeTextUnit?.targetLocale, aiTranslateTimelineData, localeTag, sortedHistoryItems]);

  const navigateBack = () => {
    if (embedded) {
      navigationVersionRef.current += 1;
      nextAfterSaveRef.current = null;
      embedded.onClose();
      return;
    }
    if (workbenchDetailContext) {
      void navigate('/workbench', {
        state: {
          workbenchReturn: {
            ...workbenchDetailContext,
            rowId: `${tmTextUnitId}:${localeTag}`,
            scrollTop: 0,
          },
        },
      });
      return;
    }

    if (locationState?.from === '/workbench' && window.history.length > 1) {
      void navigate(-1);
      return;
    }

    if (locationState?.workbenchReturn) {
      void navigate(locationState.workbenchUrl ?? '/workbench', {
        state: { workbenchReturn: locationState.workbenchReturn },
      });
      return;
    }

    if (locationState?.workbenchSearch) {
      void navigate('/workbench', {
        state: {
          workbenchSearch: locationState.workbenchSearch,
          workbenchScrollTop: locationState.workbenchScrollTop ?? null,
          workbenchRowId: locationState.workbenchRowId ?? null,
        },
      });
      return;
    }
    void navigate(locationState?.from ?? '/workbench');
  };

  const isEditorDirty =
    draftTarget !== baselineTarget || draftStatus !== baselineStatus || feedback.dirty;
  const isEditorSaving =
    saveMutation.isPending || isValidatingSave || Boolean(editorDraft.pendingOperation);
  const hasUnsavedComment =
    isTargetCommentEditing && targetCommentDraft !== (activeTextUnit?.targetComment ?? '');
  const handleBack = () => {
    if (embedded) {
      navigateBack();
      return;
    }
    if (isEditorSaving || deleteMutation.isPending) {
      setSaveErrorMessage('Wait for the current save to finish before leaving.');
      return;
    }
    if (isEditorDirty || hasUnsavedComment) {
      setShowDiscardDialog(true);
      return;
    }
    navigateBack();
  };
  useEffect(() => {
    if (!isEditorDirty && !hasUnsavedComment && !isEditorSaving) {
      return;
    }
    const preventDiscard = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener('beforeunload', preventDiscard);
    return () => window.removeEventListener('beforeunload', preventDiscard);
  }, [hasUnsavedComment, isEditorDirty, isEditorSaving]);
  const hasCurrentTranslation = typeof activeTextUnit?.tmTextUnitVariantId === 'number';
  const showDeletedHistoryEntry = !hasCurrentTranslation && sortedHistoryItems.length > 1;
  const canDeleteCurrentTranslation =
    canEdit &&
    hasCurrentTranslation &&
    typeof activeTextUnit?.tmTextUnitCurrentVariantId === 'number';

  const buildSaveRequest = useCallback(
    (targetValue: string): SaveTextUnitRequest | null => {
      if (!activeTextUnit) {
        setSaveErrorMessage('Text unit is still loading.');
        return null;
      }

      if (typeof activeTextUnit.localeId !== 'number') {
        setSaveErrorMessage('Unable to save: missing locale id.');
        return null;
      }

      const statusUpdate = mapUiStatusToApi(draftStatus);
      if (!statusUpdate || !statusUpdate.status) {
        setSaveErrorMessage('Unable to save: invalid status selection.');
        return null;
      }

      return {
        tmTextUnitId: activeTextUnit.tmTextUnitId,
        localeId: activeTextUnit.localeId,
        expectedVariantId: baselineVariantId,
        target: targetValue,
        status: statusUpdate.status,
        includedInLocalizedFile: statusUpdate.includedInLocalizedFile,
      };
    },
    [activeTextUnit, baselineVariantId, draftStatus],
  );

  const saveRequestWithIntegrityCheck = useCallback(
    async (request: SaveTextUnitRequest) => {
      if (saveInFlightRef.current) return;
      saveInFlightRef.current = true;
      const attemptId = (saveAttemptRef.current += 1);
      const owner = editorOwnerRef.current;
      const isCurrentAttempt = () =>
        saveAttemptRef.current === attemptId && editorOwnerRef.current === owner;
      setSaveErrorMessage(null);

      if (
        isMf2Translation &&
        mf2TranslationErrorCount({
          locale: localeForEditing ?? 'en',
          source: activeTextUnit?.source ?? '',
          target: request.target,
        }) > 0
      ) {
        setSaveErrorMessage('Fix the MF2 errors before saving.');
        saveInFlightRef.current = false;
        return;
      }

      setIsValidatingSave(true);
      try {
        const integrityResult = await checkTextUnitIntegrityWithRetry({
          tmTextUnitId: request.tmTextUnitId,
          localeId: request.localeId,
          content: request.target,
        });
        if (!isCurrentAttempt()) {
          return;
        }

        if (integrityResult?.checkResult === false) {
          const failureDetail = integrityResult.failureDetail?.trim() || null;
          const report = buildIntegrityCheckErrorReport({
            url: buildTextUnitDetailUrl(request.tmTextUnitId, localeForEditing),
            suggestedTranslation: request.target.trim() || '(empty translation)',
            errorMessage: failureDetail ?? 'Unavailable',
          });
          setPendingValidationSave({
            request,
            title: 'Unable to save translation',
            body: INTEGRITY_CHECK_FAILURE_MESSAGE,
            failureDetail,
            reportMessage: report.reportMessage,
            reportHtml: report.reportHtml,
            canBypass: currentUser.role !== 'ROLE_TRANSLATOR',
          });
          return;
        }
      } catch {
        if (!isCurrentAttempt()) {
          return;
        }
        setPendingValidationSave({
          request,
          title: INTEGRITY_CHECK_UNAVAILABLE_TITLE,
          body: INTEGRITY_CHECK_UNAVAILABLE_MESSAGE,
          canRetry: true,
        });
        return;
      } finally {
        saveInFlightRef.current = false;
        if (isCurrentAttempt()) {
          setIsValidatingSave(false);
        }
      }

      saveInFlightRef.current = true;
      try {
        await saveMutation.mutateAsync(request).catch(() => undefined);
      } finally {
        saveInFlightRef.current = false;
      }
    },
    [activeTextUnit?.source, currentUser.role, isMf2Translation, localeForEditing, saveMutation],
  );

  const saveDraft = useCallback(
    async (targetOverride?: string, advance = false) => {
      if (saveInFlightRef.current || isEditorSaving || deleteMutation.isPending) return;
      if (advance && !isEditorDirty && !hasUnsavedComment) {
        embedded?.onNext?.();
        return;
      }
      if (!canEdit) {
        setSaveErrorMessage('You cannot edit this locale.');
        return;
      }

      const nextTarget = targetOverride ?? draftTarget;
      const hasChanges =
        nextTarget !== baselineTarget ||
        draftStatus !== baselineStatus ||
        (feedback.activeDirty && draftStatus === 'Rejected');
      if (!hasChanges) {
        return;
      }

      const request = buildSaveRequest(nextTarget);
      if (!request) {
        return;
      }

      const decoratedRequest = decorateFeedbackRequest(request);
      nextAfterSaveRef.current =
        advance && embedded?.onNext
          ? {
              request: decoratedRequest,
              version: navigationVersionRef.current,
              onNext: embedded.onNext,
            }
          : null;
      await saveRequestWithIntegrityCheck(decoratedRequest);
    },
    [
      baselineStatus,
      baselineTarget,
      buildSaveRequest,
      canEdit,
      draftStatus,
      draftTarget,
      feedback.activeDirty,
      decorateFeedbackRequest,
      saveRequestWithIntegrityCheck,
      isEditorSaving,
      isEditorDirty,
      hasUnsavedComment,
      deleteMutation.isPending,
      embedded,
    ],
  );

  const handleSaveEditor = useCallback(() => {
    void saveDraft();
  }, [saveDraft]);

  const handleResetEditor = useCallback(() => {
    nextAfterSaveRef.current = null;
    saveAttemptRef.current += 1;
    resetFeedback();
    setDraftTarget(baselineTarget);
    setDraftStatus(baselineStatus);
    setSaveErrorMessage(null);
    setPendingValidationSave(null);
    if (embedded && activeTextUnit) {
      const status = normalizeEditorStatus(
        formatStatus(activeTextUnit.status, activeTextUnit.includedInLocalizedFile),
      );
      updateDraft((current) => ({
        ...current,
        source: activeTextUnit.source ?? '',
        messageFormat: activeTextUnit.messageFormat ?? null,
        baselineTarget: activeTextUnit.target ?? '',
        draftTarget: activeTextUnit.target ?? '',
        baselineStatus: status,
        draftStatus: status,
        baselineVariantId: activeTextUnit.tmTextUnitVariantId ?? null,
        baselineComment: activeTextUnit.targetComment ?? '',
        targetCommentDraft: activeTextUnit.targetComment ?? '',
        isTargetCommentEditing: false,
        operationError: null,
      }));
    }
  }, [
    baselineStatus,
    baselineTarget,
    resetFeedback,
    embedded,
    activeTextUnit,
    updateDraft,
    setDraftTarget,
    setDraftStatus,
  ]);

  const handleStartTargetCommentEditing = useCallback(() => {
    setTargetCommentDraft(activeTextUnit?.targetComment ?? '');
    setIsTargetCommentEditing(true);
  }, [activeTextUnit?.targetComment, setTargetCommentDraft, setIsTargetCommentEditing]);

  const handleCancelTargetCommentEditing = useCallback(() => {
    setTargetCommentDraft(activeTextUnit?.targetComment ?? '');
    setIsTargetCommentEditing(false);
  }, [activeTextUnit?.targetComment, setTargetCommentDraft, setIsTargetCommentEditing]);

  const handleSaveTargetComment = useCallback(async () => {
    if (!canEdit) {
      setSaveErrorMessage('You cannot edit this locale.');
      return;
    }

    if (isSourceOnly) {
      setSaveErrorMessage('Target comments can only be edited for target locales.');
      return;
    }

    if (isEditorDirty) {
      setSaveErrorMessage('Save or reset the translation before editing the target comment.');
      return;
    }

    const request = buildSaveRequest(baselineTarget);
    if (!request) {
      return;
    }

    const trimmedComment = targetCommentDraft.trim();
    setIsMetaCollapsed(false);
    await saveMutation.mutateAsync({
      ...request,
      targetComment: trimmedComment ? trimmedComment : null,
    });
  }, [
    baselineTarget,
    buildSaveRequest,
    canEdit,
    isEditorDirty,
    isSourceOnly,
    saveMutation,
    targetCommentDraft,
  ]);

  const handleRequestDeleteEditor = useCallback(() => {
    if (!canDeleteCurrentTranslation || deleteMutation.isPending || saveMutation.isPending) {
      return;
    }
    setPendingValidationSave(null);
    setSaveErrorMessage(null);
    setShowDeleteDialog(true);
  }, [canDeleteCurrentTranslation, deleteMutation.isPending, saveMutation.isPending]);

  const handleConfirmDeleteEditor = useCallback(() => {
    if (!canDeleteCurrentTranslation || !hasCurrentTranslation) {
      setShowDeleteDialog(false);
      return;
    }

    const currentVariantId = activeTextUnit?.tmTextUnitCurrentVariantId;
    if (typeof currentVariantId !== 'number') {
      setShowDeleteDialog(false);
      setSaveErrorMessage('No current translation to delete.');
      return;
    }

    setSaveErrorMessage(null);
    void deleteMutation.mutateAsync(currentVariantId);
  }, [
    activeTextUnit?.tmTextUnitCurrentVariantId,
    canDeleteCurrentTranslation,
    deleteMutation,
    hasCurrentTranslation,
  ]);

  const handleDismissDeleteDialog = useCallback(() => {
    if (deleteMutation.isPending) {
      return;
    }
    setShowDeleteDialog(false);
  }, [deleteMutation.isPending]);

  const handleConfirmValidationSave = useCallback(() => {
    if (
      !pendingValidationSave ||
      pendingValidationSave.canRetry ||
      !pendingValidationSave.canBypass ||
      saveInFlightRef.current
    ) {
      return;
    }

    const request = pendingValidationSave.request;
    setPendingValidationSave(null);
    setSaveErrorMessage(null);
    saveInFlightRef.current = true;
    void saveMutation
      .mutateAsync(request)
      .catch(() => undefined)
      .finally(() => {
        saveInFlightRef.current = false;
      });
  }, [pendingValidationSave, saveMutation]);

  const handleRetryValidationSave = useCallback(() => {
    if (!pendingValidationSave?.canRetry) {
      return;
    }

    const request = pendingValidationSave.request;
    setPendingValidationSave(null);
    setSaveErrorMessage(null);
    void saveRequestWithIntegrityCheck(request);
  }, [pendingValidationSave, saveRequestWithIntegrityCheck]);

  const handleDismissValidationDialog = useCallback(() => {
    nextAfterSaveRef.current = null;
    setPendingValidationSave(null);
  }, []);

  const handleSubmitAi = useCallback(() => {
    if (!aiPreferencesReady || isAiResponding || !activeTextUnit || !localeForEditing) {
      return;
    }

    const trimmed = aiInput.trim();
    if (!trimmed) {
      return;
    }
    recordChatUsed();

    const userMessage: TextUnitDetailAiMessage = {
      id: `user-${Date.now()}`,
      sender: 'user',
      content: trimmed,
    };

    const baseMessages = aiMessages.filter((message) => !message.isError);
    const requestAttempt = (aiRequestAttemptRef.current += 1);
    aiRequestAbortControllerRef.current?.abort();
    const abortController = new AbortController();
    aiRequestAbortControllerRef.current = abortController;
    setAiMessages((previous) => [...previous, userMessage]);
    setAiInput('');
    setIsAiResponding(true);

    void (async () => {
      try {
        const conversation: AiReviewMessage[] = [...baseMessages, userMessage].map((message) => ({
          role: message.sender,
          content: message.content,
        }));
        const glossaryContextMessage = buildGlossaryContextMessage(glossaryMatchesQuery.data);

        const response = await requestAiReview(
          {
            presetId: aiPreset,
            reviewStyle: aiReviewStyle,
            requestType: baseMessages.length > 0 ? 'follow_up' : 'manual',
            surface: 'text_unit_detail',
            source: activeTextUnit.source ?? '',
            target: draftTarget,
            localeTag: localeForEditing,
            sourceDescription: activeTextUnit.comment ?? '',
            tmTextUnitId: activeTextUnit.tmTextUnitId,
            messages: [glossaryContextMessage, ...conversation].filter(
              (message): message is AiReviewMessage => message != null,
            ),
          },
          { signal: abortController.signal },
        );
        if (aiRequestAttemptRef.current !== requestAttempt) {
          return;
        }

        const assistantMessage: TextUnitDetailAiMessage = {
          id: `assistant-${Date.now()}`,
          sender: 'assistant',
          content: response.message.content,
          suggestions: response.suggestions,
          review: response.review,
        };

        setAiMessages((previous) => [...previous, assistantMessage]);
      } catch (error: unknown) {
        if (aiRequestAttemptRef.current !== requestAttempt) {
          return;
        }
        const aiError = formatAiReviewError(error);
        setAiMessages((previous) => [
          ...previous.filter((message) => !message.isError),
          {
            id: `assistant-error-${Date.now()}`,
            sender: 'assistant',
            content: aiError.message,
            isError: true,
            errorDetail: aiError.detail,
          },
        ]);
      } finally {
        if (aiRequestAttemptRef.current === requestAttempt) {
          setIsAiResponding(false);
        }
        if (aiRequestAbortControllerRef.current === abortController) {
          aiRequestAbortControllerRef.current = null;
        }
      }
    })();
  }, [
    aiPreset,
    aiReviewStyle,
    aiPreferencesReady,
    setAiMessages,
    activeTextUnit,
    aiInput,
    aiMessages,
    draftTarget,
    glossaryMatchesQuery.data,
    isAiResponding,
    localeForEditing,
    recordChatUsed,
  ]);

  const handleRetryAi = useCallback(
    (requestType: 'manual' | 'retry' = 'retry') => {
      if (!aiPreferencesReady || isAiResponding || !activeTextUnit || !localeForEditing) {
        return;
      }
      recordChatUsed();

      const baseMessages = aiMessages.filter((message) => !message.isError);
      const requestAttempt = (aiRequestAttemptRef.current += 1);
      aiRequestAbortControllerRef.current?.abort();
      const abortController = new AbortController();
      aiRequestAbortControllerRef.current = abortController;
      const conversation: AiReviewMessage[] =
        baseMessages.length > 0
          ? baseMessages.map((message) => ({
              role: message.sender,
              content: message.content,
            }))
          : [{ role: 'user', content: DEFAULT_AI_REVIEW_PROMPT }];
      const retryTarget = draftTarget;
      const glossaryContextMessage = buildGlossaryContextMessage(glossaryMatchesQuery.data);

      setIsAiResponding(true);
      void (async () => {
        try {
          const response = await requestAiReview(
            {
              presetId: aiPreset,
              reviewStyle: aiReviewStyle,
              requestType,
              surface: 'text_unit_detail',
              source: activeTextUnit.source ?? '',
              target: retryTarget,
              localeTag: localeForEditing,
              sourceDescription: activeTextUnit.comment ?? '',
              tmTextUnitId: activeTextUnit.tmTextUnitId,
              messages: [glossaryContextMessage, ...conversation].filter(
                (message): message is AiReviewMessage => message != null,
              ),
            },
            { signal: abortController.signal },
          );
          if (aiRequestAttemptRef.current !== requestAttempt) {
            return;
          }
          const assistantMessage: TextUnitDetailAiMessage = {
            id: `assistant-${Date.now()}`,
            sender: 'assistant',
            content: response.message.content,
            suggestions: response.suggestions,
            review: response.review,
          };
          setAiMessages((previous) => [
            ...previous.filter((message) => !message.isError),
            assistantMessage,
          ]);
        } catch (error: unknown) {
          if (aiRequestAttemptRef.current !== requestAttempt) {
            return;
          }
          const aiError = formatAiReviewError(error);
          setAiMessages((previous) => [
            ...previous.filter((message) => !message.isError),
            {
              id: `assistant-error-${Date.now()}`,
              sender: 'assistant',
              content: aiError.message,
              isError: true,
              errorDetail: aiError.detail,
            },
          ]);
        } finally {
          if (aiRequestAttemptRef.current === requestAttempt) {
            setIsAiResponding(false);
          }
          if (aiRequestAbortControllerRef.current === abortController) {
            aiRequestAbortControllerRef.current = null;
          }
        }
      })();
    },
    [
      aiPreset,
      aiReviewStyle,
      aiPreferencesReady,
      setAiMessages,
      activeTextUnit,
      aiMessages,
      draftTarget,
      glossaryMatchesQuery.data,
      isAiResponding,
      localeForEditing,
      recordChatUsed,
    ],
  );

  const getAiSuggestionError = useCallback(
    (suggestion: AiReviewSuggestion) =>
      isMf2Translation
        ? (mf2TranslationErrors({
            locale: localeForEditing ?? 'en',
            source: activeTextUnit?.source ?? '',
            target: suggestion.content,
          })[0]?.message ?? null)
        : null,
    [activeTextUnit?.source, isMf2Translation, localeForEditing],
  );

  const handleUseAiSuggestion = useCallback(
    (suggestion: AiReviewSuggestion) => {
      if (isEditorSaving || deleteMutation.isPending) {
        return;
      }
      const error = getAiSuggestionError(suggestion);
      if (error) {
        setSaveErrorMessage(error);
        return;
      }
      setDraftTarget(suggestion.content);
      recordSuggestionUsed();
      setSaveErrorMessage(null);
    },
    [
      deleteMutation.isPending,
      getAiSuggestionError,
      isEditorSaving,
      recordSuggestionUsed,
      setDraftTarget,
    ],
  );

  const editorWarningMessage = staleDraft
    ? 'This translation changed while you had a draft. Your edits are retained. Copy them if needed, then Reset to load the current translation before saving.'
    : isSourceOnly
      ? 'Open this page with a target locale to edit a translation or view translation history.'
      : !localeForEditing || !activeTextUnit
        ? 'Missing locale. Open this page from a workbench row to enable editing.'
        : !canEdit
          ? 'You do not have permission to edit this locale.'
          : null;

  if (tmTextUnitId === null) {
    return (
      <div className="review-project-page__state review-project-page__state--error">
        <div>Missing or invalid text unit id.</div>
      </div>
    );
  }

  return (
    <TextUnitDetailPageView
      key={currentUser.username}
      tmTextUnitId={tmTextUnitId}
      embedded={Boolean(embedded)}
      presentation={embedded?.presentation}
      navigationKey={embedded?.navigationKey}
      autoFocus={embedded?.autoFocus}
      onShowDetails={embedded?.onShowDetails}
      onShowCompact={embedded?.onShowCompact}
      onSaveAndNext={embedded?.onNext ? () => void saveDraft(undefined, true) : undefined}
      isSearchEnabled={isSearchEnabled}
      onBack={handleBack}
      backLabel={
        locationState?.from === '/content' || locationState?.from?.startsWith('/content?')
          ? 'Back to content'
          : undefined
      }
      openInWorkbench={workbenchDetailContext !== null}
      editorInfo={{
        target: draftTarget,
        status: draftStatus,
        isSourceOnly,
        statusOptions: editorStatusOptions,
        canEdit,
        canDelete: canDeleteCurrentTranslation,
        isDirty: isEditorDirty,
        hasSaveableChanges:
          draftTarget !== baselineTarget || draftStatus !== baselineStatus || feedback.activeDirty,
        isSaving: isEditorSaving,
        isDeleting: deleteMutation.isPending,
        mf2ErrorCount,
        errorMessage: saveErrorMessage ?? editorDraft.operationError,
        warningMessage: editorWarningMessage,
      }}
      visibleTextEditor={{
        enabled: isVisibleTextEditorEnabled && !isSourceOnly,
        marksMode: translationMarksMode,
        onChangeMarksMode: setTranslationMarksMode,
        protectedDiagnostics: draftTargetProtectedDiagnostics,
        protectedTokens: draftTargetProtectedTokens,
        validateNextValue: validateDraftTarget,
        dir: editorDirection,
      }}
      keyInfo={{
        stringId: formatValue(activeTextUnit?.name),
        locale: isSourceOnly ? `Source ${formatValue(displayLocale)}` : formatValue(displayLocale),
        source: formatValue(activeTextUnit?.source),
        messageFormat: activeTextUnit?.messageFormat,
        comment: formatValue(activeTextUnit?.comment),
        repositoryName: formatValue(activeTextUnit?.repositoryName),
      }}
      onChangeTarget={setDraftTarget}
      onChangeStatus={(value) => setDraftStatus(normalizeEditorStatus(value))}
      onSaveEditor={handleSaveEditor}
      editFeedback={feedback.widget}
      onResetEditor={handleResetEditor}
      onRequestDeleteEditor={handleRequestDeleteEditor}
      previewLocale={displayLocale ?? 'en'}
      isIcuPreviewCollapsed={isIcuPreviewCollapsed}
      onToggleIcuPreviewCollapsed={() => setIsIcuPreviewCollapsed((current) => !current)}
      icuPreviewMode={icuPreviewMode}
      onChangeIcuPreviewMode={(mode) => {
        setIcuPreviewMode(mode);
        setIsIcuPreviewCollapsed(false);
      }}
      isAiCollapsed={isAiCollapsed}
      onToggleAiCollapsed={() => setIsAiCollapsed((current) => !current)}
      aiMessages={aiMessages}
      aiSettings={aiSettings}
      onReviewAi={
        activeTextUnit && localeForEditing && !glossaryMatchesQuery.isLoading
          ? () => handleRetryAi('manual')
          : undefined
      }
      aiInput={aiInput}
      onChangeAiInput={setAiInput}
      onSubmitAi={handleSubmitAi}
      onRetryAi={() => handleRetryAi()}
      onUseAiSuggestion={handleUseAiSuggestion}
      getAiSuggestionError={getAiSuggestionError}
      isAiResponding={isAiResponding}
      glossaryMatches={glossaryMatchesQuery.data ?? []}
      isGlossaryLoading={glossaryMatchesQuery.isLoading}
      glossaryErrorMessage={getQueryErrorMessage(glossaryMatchesQuery.error)}
      glossaryTermMetadata={
        glossaryTermTarget
          ? {
              glossaryId: glossaryTermTarget.glossaryId,
              glossaryName: glossaryTermTarget.glossaryName,
              term: glossaryTermQuery.data ?? null,
              isLoading: glossaryTargetsQuery.isLoading || glossaryTermQuery.isLoading,
              errorMessage: getQueryErrorMessage(
                glossaryTargetsQuery.error ?? glossaryTermQuery.error,
              ),
            }
          : null
      }
      sourceScreenshots={sourceScreenshots}
      isGlossaryCollapsed={isGlossaryCollapsed}
      onToggleGlossaryCollapsed={() => setIsGlossaryCollapsed((current) => !current)}
      isMetaCollapsed={isMetaCollapsed}
      onToggleMetaCollapsed={() => setIsMetaCollapsed((current) => !current)}
      isMetaLoading={textUnitQuery.isLoading}
      metaErrorMessage={getQueryErrorMessage(textUnitQuery.error)}
      metaSections={metaSections}
      targetCommentEditor={{
        draft: targetCommentDraft,
        isEditing: isTargetCommentEditing,
        canEdit: canEdit && !isSourceOnly,
        isSaving: isEditorSaving,
        isDisabled: isEditorDirty || isEditorSaving,
        disabledReason: isEditorDirty
          ? 'Save or reset the translation before editing the target comment.'
          : null,
        onStart: handleStartTargetCommentEditing,
        onChange: setTargetCommentDraft,
        onSave: () => {
          void handleSaveTargetComment();
        },
        onCancel: handleCancelTargetCommentEditing,
      }}
      metaWarningMessage={
        gitBlameQuery.isError
          ? `Git blame metadata is unavailable: ${getQueryErrorMessage(gitBlameQuery.error) ?? '-'}`
          : null
      }
      isHistoryCollapsed={isHistoryCollapsed}
      onToggleHistoryCollapsed={() => setIsHistoryCollapsed((current) => !current)}
      isHistoryLoading={!localeTag ? false : textUnitQuery.isLoading || historyQuery.isLoading}
      historyErrorMessage={
        !localeTag
          ? null
          : (getQueryErrorMessage(textUnitQuery.error) ?? getQueryErrorMessage(historyQuery.error))
      }
      historyMissingLocale={!localeTag}
      historyRows={historyRows}
      historyInitialDate={formatDateTime(textUnitQuery.data?.tmTextUnitCreatedDate)}
      isHistoryCountReady={!localeTag || historyQuery.isSuccess}
      showDeletedHistoryEntry={showDeletedHistoryEntry}
      showValidationDialog={pendingValidationSave !== null}
      validationDialogTitle={pendingValidationSave?.title ?? ''}
      validationDialogBody={pendingValidationSave?.body ?? ''}
      validationDialogFailureDetail={pendingValidationSave?.failureDetail ?? null}
      validationDialogReportMessage={pendingValidationSave?.reportMessage ?? null}
      validationDialogReportHtml={pendingValidationSave?.reportHtml ?? null}
      validationDialogCanBypass={pendingValidationSave?.canBypass === true}
      validationDialogCanRetry={pendingValidationSave?.canRetry === true}
      onConfirmValidationSave={handleConfirmValidationSave}
      onRetryValidationSave={handleRetryValidationSave}
      onDismissValidationDialog={handleDismissValidationDialog}
      showDeleteDialog={showDeleteDialog}
      deleteDialogBody={
        isEditorDirty
          ? 'This will delete the current translation and discard unsaved edits.'
          : 'This will delete the current translation for this locale.'
      }
      onConfirmDeleteEditor={handleConfirmDeleteEditor}
      onDismissDeleteDialog={handleDismissDeleteDialog}
      showDiscardDialog={showDiscardDialog}
      onDismissDiscardDialog={() => setShowDiscardDialog(false)}
      onConfirmDiscard={() => {
        if (isEditorSaving || deleteMutation.isPending) {
          return;
        }
        resetFeedback();
        setShowDiscardDialog(false);
        navigateBack();
      }}
    />
  );
}

const normalizeEditorStatus = (
  value?: string | null,
): 'Accepted' | 'To review' | 'To translate' | 'Rejected' => {
  if (
    value === 'Accepted' ||
    value === 'To review' ||
    value === 'To translate' ||
    value === 'Rejected'
  ) {
    return value;
  }
  return 'To translate';
};

const safeDateValue = (value?: string | null) => {
  if (!value) {
    return Number.MIN_SAFE_INTEGER;
  }
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? Number.MIN_SAFE_INTEGER : parsed;
};

const formatDateTime = (value?: string | null) => {
  if (!value) {
    return '-';
  }

  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }

  return parsed.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
};

const formatHistoryStatus = (status?: string | null, includedInLocalizedFile?: boolean) => {
  if (includedInLocalizedFile === false) {
    return 'Rejected';
  }

  switch (status) {
    case 'APPROVED':
      return 'Accepted';
    case 'REVIEW_NEEDED':
      return 'To review';
    case 'TRANSLATION_NEEDED':
      return 'To translate';
    default:
      return null;
  }
};

const getQueryErrorMessage = (error: unknown) => {
  if (error instanceof Error) {
    return error.message;
  }
  return null;
};

const formatGitCommitTime = (commitTime?: string | null) => {
  if (!commitTime) {
    return '-';
  }

  const parsedSeconds = Number.parseInt(commitTime, 10);
  if (!Number.isFinite(parsedSeconds)) {
    return '-';
  }
  return formatDateTime(new Date(parsedSeconds * 1000).toISOString());
};

const formatBoolean = (value?: boolean) => {
  if (typeof value !== 'boolean') {
    return '-';
  }
  return value ? 'true' : 'false';
};

const formatNumberish = (value?: number | null) => {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return '-';
  }
  return String(value);
};

const formatScreenshots = (
  screenshots?: Array<{
    id?: number | null;
    name?: string | null;
    src?: string | null;
  }> | null,
) => {
  if (!screenshots || screenshots.length === 0) {
    return '-';
  }
  return String(screenshots.length);
};

const formatHistoryTranslation = (translation?: string | null) => {
  if (translation == null) {
    return '<no translation yet>';
  }
  return translation;
};

const formatValue = (value?: string | null) => {
  if (value == null || value.trim().length === 0) {
    return '-';
  }
  return value;
};
