import './admin-glossary-terms-panel.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  type CSSProperties,
  type MouseEvent as ReactMouseEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { Link } from 'react-router-dom';

import {
  type ApiExtractedGlossaryCandidate,
  type ApiGlossaryDetail,
  type ApiGlossaryTerm,
  type ApiGlossaryTermEvidence,
  type ApiPollableTask,
  type ApiUpsertGlossaryTermRequest,
  batchUpdateGlossaryTerms,
  createGlossaryTerm,
  fetchGlossaryExtractionOutput,
  fetchGlossaryExtractionTask,
  fetchGlossaryTerms,
  startGlossaryTermExtraction,
  updateGlossaryTerm,
  waitForGlossaryExtractionTask,
} from '../../api/glossaries';
import { Modal } from '../../components/Modal';
import { PillDropdown } from '../../components/PillDropdown';
import { useUser } from '../../components/RequireUser';
import { buildGlossaryWorkbenchState } from '../../utils/glossaryWorkbench';
import { prepareDbBackedUploadFile } from '../../utils/image-upload-optimizer';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import { canEditLocale, canManageGlossaryTerms } from '../../utils/permissions';
import {
  buildRequestAttachmentUploadQueueEntries,
  canUploadRequestAttachmentFile,
  getAttachmentKindFromFile,
  type RequestAttachmentUploadQueueItem,
  resolveAttachmentUrl,
  revokeRequestAttachmentUploadQueuePreviews,
  uploadRequestAttachmentFile,
} from '../../utils/request-attachments';
import { GlossaryExtractView } from './GlossaryExtractView';
import { GlossaryTermsListView } from './GlossaryTermsListView';

const TERM_TYPES = ['BRAND', 'PRODUCT', 'UI_LABEL', 'LEGAL', 'TECHNICAL', 'GENERAL'] as const;
const ENFORCEMENTS = ['HARD', 'SOFT', 'REVIEW_ONLY'] as const;
const STATUSES = ['CANDIDATE', 'APPROVED', 'DEPRECATED', 'REJECTED'] as const;
const STATUS_LABELS: Record<(typeof STATUSES)[number], string> = {
  CANDIDATE: 'Candidate',
  APPROVED: 'Approved',
  DEPRECATED: 'Deprecated',
  REJECTED: 'Rejected',
};
const PROVENANCES = ['MANUAL', 'IMPORTED', 'AUTOMATED', 'AI_EXTRACTED'] as const;
type ReferenceType = ApiGlossaryTermEvidence['evidenceType'];
const REFERENCE_TYPES = [
  'SCREENSHOT',
  'NOTE',
  'STRING_USAGE',
  'CODE_REF',
] as const satisfies readonly ReferenceType[];
const REFERENCE_TYPE_LABELS: Record<ReferenceType, string> = {
  SCREENSHOT: 'Screenshot',
  NOTE: 'Note',
  STRING_USAGE: 'String usage',
  CODE_REF: 'Code reference',
};
const STATUS_FILTER_OPTIONS = [
  { value: 'CANDIDATE', label: 'Candidates' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'DEPRECATED', label: 'Deprecated' },
  { value: 'REJECTED', label: 'Rejected' },
] as const;
const EXTRACT_LIMIT_PRESETS = [25, 50, 100, 200];
const DEFAULT_VISIBLE_LOCALE_COLUMNS = 3;
const AUTO_VISIBLE_LOCALE_COLUMNS_CAP = 5;
const TERMS_LIMIT_PRESETS = [50, 100, 200, 500];
const VISIBLE_LOCALE_COLUMN_PRESETS = [1, 3, 5, 10];
const DEFAULT_PRIMARY_PANE_WIDTH_PCT = 58;
const MIN_PRIMARY_PANE_WIDTH_PCT = 36;
const MAX_PRIMARY_PANE_WIDTH_PCT = 72;
const PRIMARY_PANE_COLLAPSE_SNAP_PCT = 12;
const PRIMARY_PANE_EXPAND_SNAP_PCT = 22;
const DETAIL_PANE_COLLAPSE_SNAP_PCT = 88;
const DETAIL_PANE_EXPAND_SNAP_PCT = 78;
const GLOSSARY_WORKSPACE_PREFS_STORAGE_PREFIX = 'glossary.workspacePrefs.v1:';

type RepositoryOption = {
  id: number;
  name: string;
};

type GlossaryWorkspacePrefs = {
  searchDraft: string;
  selectedLocaleTags: string[];
  selectedStatusFilter: string | null;
  termsLimit: number;
  visibleLocaleColumnLimit: number;
  primaryPaneWidthPct: number;
  collapsedPane: CollapsedWorkspacePane;
};

type CollapsedWorkspacePane = 'primary' | 'detail' | null;

const Chevron = ({ direction }: { direction: 'left' | 'right' }) => (
  <svg
    className={`review-project-chevron review-project-chevron--${direction}`}
    viewBox="0 0 10 6"
    aria-hidden="true"
    focusable="false"
  >
    <path
      d="M1 1l4 4 4-4"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
    />
  </svg>
);

type Props = {
  glossary: ApiGlossaryDetail;
  repositoryOptions: RepositoryOption[];
  initialOpenTermId?: number | null;
  backRequestNonce?: number;
  canImport?: boolean;
  onOpenImport?: () => void;
  canExport?: boolean;
  onOpenExport?: () => void;
  onViewStateChange?: (state: {
    mode: 'terms' | 'extract' | 'editor';
    title?: string | null;
  }) => void;
};

type TranslationDraft = {
  id: string;
  localeTag: string;
  target: string;
  targetComment: string;
};

type ReferenceDraft = {
  id: string;
  referenceType: ReferenceType;
  caption: string;
  imageKey: string;
  tmTextUnitId: string;
};

type TermDraft = {
  tmTextUnitId?: number | null;
  termKey: string;
  source: string;
  sourceComment: string;
  definition: string;
  partOfSpeech: string;
  termType: string;
  enforcement: string;
  status: string;
  provenance: string;
  caseSensitive: boolean;
  doNotTranslate: boolean;
  translations: TranslationDraft[];
  references: ReferenceDraft[];
};

type BatchDraft = {
  termType: string;
  enforcement: string;
  status: string;
  provenance: string;
  partOfSpeech: string;
  caseSensitive: '' | 'true' | 'false';
  doNotTranslate: '' | 'true' | 'false';
};

const createClientId = () =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto && crypto.randomUUID
    ? crypto.randomUUID()
    : Math.random().toString(36).slice(2);

const createBlankTranslation = (localeTag = ''): TranslationDraft => ({
  id: createClientId(),
  localeTag,
  target: '',
  targetComment: '',
});

const createBlankReference = (referenceType: ReferenceType = 'NOTE'): ReferenceDraft => ({
  id: createClientId(),
  referenceType,
  caption: '',
  imageKey: '',
  tmTextUnitId: '',
});

const getReferencePlaceholder = (referenceType: ReferenceType) => {
  switch (referenceType) {
    case 'SCREENSHOT':
      return 'What should reviewers notice in this screenshot?';
    case 'STRING_USAGE':
      return 'Where this term appears, or why this usage matters';
    case 'CODE_REF':
      return 'Path, symbol, or code context';
    case 'NOTE':
    default:
      return 'Why this term should be in the glossary';
  }
};

const createBlankDraft = (localeTags: string[] = []): TermDraft => ({
  tmTextUnitId: null,
  termKey: '',
  source: '',
  sourceComment: '',
  definition: '',
  partOfSpeech: '',
  termType: 'GENERAL',
  enforcement: 'SOFT',
  status: 'CANDIDATE',
  provenance: 'MANUAL',
  caseSensitive: false,
  doNotTranslate: false,
  translations: localeTags.map((localeTag) => createBlankTranslation(localeTag)),
  references: [],
});

const dedupeLocaleTags = (localeTags: string[]) => {
  const seen = new Set<string>();
  return localeTags
    .map((localeTag) => localeTag.trim())
    .filter(Boolean)
    .filter((localeTag) => {
      const key = localeTag.toLowerCase();
      if (seen.has(key)) {
        return false;
      }
      seen.add(key);
      return true;
    });
};

const termToDraft = (term: ApiGlossaryTerm, localeTags: string[]): TermDraft => {
  const translationsByLocale = new Map(
    term.translations.map((translation) => [translation.localeTag.toLowerCase(), translation]),
  );
  const mergedLocaleTags = dedupeLocaleTags([
    ...localeTags,
    ...term.translations.map((translation) => translation.localeTag),
  ]);
  return {
    tmTextUnitId: term.tmTextUnitId,
    termKey: term.termKey,
    source: term.source,
    sourceComment: term.sourceComment ?? '',
    definition: term.definition ?? '',
    partOfSpeech: term.partOfSpeech ?? '',
    termType: term.termType ?? 'GENERAL',
    enforcement: term.enforcement ?? 'SOFT',
    status: term.status ?? 'CANDIDATE',
    provenance: term.provenance ?? 'MANUAL',
    caseSensitive: term.caseSensitive,
    doNotTranslate: term.doNotTranslate,
    translations: mergedLocaleTags.map((localeTag) => {
      const translation = translationsByLocale.get(localeTag.toLowerCase());
      return {
        id: createClientId(),
        localeTag,
        target: translation?.target ?? '',
        targetComment: translation?.targetComment ?? '',
      };
    }),
    references: term.evidence.map((reference) => ({
      id: createClientId(),
      referenceType: reference.evidenceType,
      caption: reference.caption ?? '',
      imageKey: reference.imageKey ?? '',
      tmTextUnitId: reference.tmTextUnitId == null ? '' : String(reference.tmTextUnitId),
    })),
  };
};

const draftToRequest = (draft: TermDraft, { includeTranslations = true } = {}) => {
  const request = {
    termKey: draft.tmTextUnitId == null ? draft.termKey.trim() || null : draft.termKey || null,
    source: draft.source.trim(),
    sourceComment: draft.sourceComment.trim() || null,
    definition: draft.definition.trim() || null,
    partOfSpeech: draft.partOfSpeech.trim() || null,
    termType: draft.termType || null,
    enforcement: draft.enforcement || null,
    status: draft.status || null,
    provenance: draft.provenance || null,
    caseSensitive: draft.caseSensitive,
    doNotTranslate: draft.doNotTranslate,
    evidence: draft.references
      .map((reference) => ({
        evidenceType: reference.referenceType,
        caption: reference.caption.trim() || null,
        imageKey: reference.imageKey.trim() || null,
        tmTextUnitId:
          reference.referenceType === 'STRING_USAGE' &&
          reference.tmTextUnitId.trim() &&
          Number.isFinite(Number(reference.tmTextUnitId))
            ? Number(reference.tmTextUnitId)
            : null,
      }))
      .filter(
        (reference) =>
          Boolean(reference.imageKey) ||
          Boolean(reference.caption) ||
          reference.tmTextUnitId != null,
      ),
  };

  if (!includeTranslations) {
    return request;
  }

  return {
    ...request,
    translations: draft.translations
      .map((translation) => ({
        localeTag: translation.localeTag.trim(),
        target: translation.target.trim() || null,
        targetComment: translation.targetComment.trim() || null,
      }))
      .filter((translation) => translation.localeTag && translation.target),
  };
};

const toBooleanPatchValue = (value: '' | 'true' | 'false') => {
  if (value === 'true') {
    return true;
  }
  if (value === 'false') {
    return false;
  }
  return null;
};

const areNumberArraysEqual = (left: number[], right: number[]) =>
  left.length === right.length && left.every((value, index) => value === right[index]);

const areStringArraysEqual = (left: string[], right: string[]) =>
  left.length === right.length && left.every((value, index) => value === right[index]);

const sortLocaleTagsByOptions = (
  localeTags: string[],
  localeOptions: Array<{ tag: string }>,
): string[] => {
  const optionOrder = new Map(
    localeOptions.map((option, index) => [option.tag.toLowerCase(), index] as const),
  );

  return dedupeLocaleTags(localeTags)
    .filter((localeTag) => optionOrder.has(localeTag.toLowerCase()))
    .sort(
      (first, second) =>
        (optionOrder.get(first.toLowerCase()) ?? Number.MAX_SAFE_INTEGER) -
        (optionOrder.get(second.toLowerCase()) ?? Number.MAX_SAFE_INTEGER),
    );
};

function getGlossaryWorkspacePrefsStorage() {
  if (typeof window === 'undefined') {
    return null;
  }
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

function normalizePersistedStatusFilter(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  return STATUS_FILTER_OPTIONS.some((option) => option.value === value) ? value : null;
}

function normalizePersistedPositiveNumber(value: unknown, fallback: number) {
  const next = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(next) && next >= 1 ? Math.round(next) : fallback;
}

function normalizePersistedPaneWidth(value: unknown) {
  const next = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(next)) {
    return DEFAULT_PRIMARY_PANE_WIDTH_PCT;
  }
  return Math.min(MAX_PRIMARY_PANE_WIDTH_PCT, Math.max(MIN_PRIMARY_PANE_WIDTH_PCT, next));
}

function normalizePersistedCollapsedPane(value: unknown): CollapsedWorkspacePane {
  return value === 'primary' || value === 'detail' ? value : null;
}

function getVisibleLocaleColumnLimitForSelection(
  currentLimit: number,
  selectedLocaleCount: number,
) {
  if (selectedLocaleCount <= 0) {
    return DEFAULT_VISIBLE_LOCALE_COLUMNS;
  }
  if (selectedLocaleCount <= AUTO_VISIBLE_LOCALE_COLUMNS_CAP) {
    return selectedLocaleCount;
  }
  if (currentLimit > AUTO_VISIBLE_LOCALE_COLUMNS_CAP) {
    return Math.min(currentLimit, selectedLocaleCount);
  }
  return AUTO_VISIBLE_LOCALE_COLUMNS_CAP;
}

function loadGlossaryWorkspacePrefs(glossaryId: number): GlossaryWorkspacePrefs | null {
  const storage = getGlossaryWorkspacePrefsStorage();
  if (!storage) {
    return null;
  }

  const raw = storage.getItem(`${GLOSSARY_WORKSPACE_PREFS_STORAGE_PREFIX}${glossaryId}`);
  if (!raw) {
    return null;
  }

  try {
    const parsed = JSON.parse(raw) as
      | (Partial<GlossaryWorkspacePrefs> & { isDetailPaneCollapsed?: unknown })
      | null;
    if (!parsed || typeof parsed !== 'object') {
      return null;
    }
    return {
      searchDraft: typeof parsed.searchDraft === 'string' ? parsed.searchDraft : '',
      selectedLocaleTags: dedupeLocaleTags(
        Array.isArray(parsed.selectedLocaleTags)
          ? parsed.selectedLocaleTags.filter(
              (localeTag): localeTag is string => typeof localeTag === 'string',
            )
          : [],
      ),
      selectedStatusFilter: normalizePersistedStatusFilter(parsed.selectedStatusFilter),
      termsLimit: normalizePersistedPositiveNumber(parsed.termsLimit, 200),
      visibleLocaleColumnLimit: normalizePersistedPositiveNumber(
        parsed.visibleLocaleColumnLimit,
        DEFAULT_VISIBLE_LOCALE_COLUMNS,
      ),
      primaryPaneWidthPct: normalizePersistedPaneWidth(parsed.primaryPaneWidthPct),
      collapsedPane:
        normalizePersistedCollapsedPane(parsed.collapsedPane) ??
        (parsed.isDetailPaneCollapsed ? 'detail' : null),
    };
  } catch {
    return null;
  }
}

function saveGlossaryWorkspacePrefs(glossaryId: number, prefs: GlossaryWorkspacePrefs) {
  const storage = getGlossaryWorkspacePrefsStorage();
  if (!storage) {
    return;
  }

  try {
    storage.setItem(
      `${GLOSSARY_WORKSPACE_PREFS_STORAGE_PREFIX}${glossaryId}`,
      JSON.stringify(prefs),
    );
  } catch {
    // Ignore storage failures.
  }
}

const extractedCandidateToRequest = (
  candidate: ApiExtractedGlossaryCandidate,
): ApiUpsertGlossaryTermRequest => ({
  source: candidate.term,
  definition: candidate.rationale ?? null,
  partOfSpeech: candidate.suggestedPartOfSpeech || null,
  termType: candidate.suggestedTermType || 'GENERAL',
  enforcement: candidate.suggestedEnforcement || 'SOFT',
  status: 'APPROVED',
  provenance: candidate.suggestedProvenance || 'AI_EXTRACTED',
  caseSensitive: false,
  doNotTranslate: candidate.suggestedDoNotTranslate,
});

export function AdminGlossaryTermsPanel({
  glossary,
  repositoryOptions,
  initialOpenTermId = null,
  backRequestNonce = 0,
  canImport = false,
  onOpenImport,
  canExport = false,
  onOpenExport,
  onViewStateChange,
}: Props) {
  const persistedWorkspacePrefs = loadGlossaryWorkspacePrefs(glossary.id);
  const user = useUser();
  const canManageTerms = canManageGlossaryTerms(user);
  const queryClient = useQueryClient();
  const resolveLocaleDisplayName = useLocaleDisplayNameResolver();
  const previewUrlsRef = useRef<Set<string>>(new Set());
  const workspaceLayoutRef = useRef<HTMLDivElement | null>(null);
  const referenceScreenshotInputRef = useRef<HTMLInputElement | null>(null);
  const initialSelectedLocaleTags = persistedWorkspacePrefs?.selectedLocaleTags ?? [];
  const [searchDraft, setSearchDraft] = useState(persistedWorkspacePrefs?.searchDraft ?? '');
  const [selectedLocaleTags, setSelectedLocaleTags] = useState<string[]>(initialSelectedLocaleTags);
  const [selectedStatusFilter, setSelectedStatusFilter] = useState<string | null>(
    persistedWorkspacePrefs?.selectedStatusFilter ?? null,
  );
  const [selectedTermIds, setSelectedTermIds] = useState<number[]>([]);
  const [openStatusTermId, setOpenStatusTermId] = useState<number | null>(null);
  const [termsLimit, setTermsLimit] = useState(String(persistedWorkspacePrefs?.termsLimit ?? 200));
  const [visibleLocaleColumnLimit, setVisibleLocaleColumnLimit] = useState(
    getVisibleLocaleColumnLimitForSelection(
      persistedWorkspacePrefs?.visibleLocaleColumnLimit ?? DEFAULT_VISIBLE_LOCALE_COLUMNS,
      initialSelectedLocaleTags.length,
    ),
  );
  const [primaryPaneWidthPct, setPrimaryPaneWidthPct] = useState(
    persistedWorkspacePrefs?.primaryPaneWidthPct ?? DEFAULT_PRIMARY_PANE_WIDTH_PCT,
  );
  const [isResizingWorkspace, setIsResizingWorkspace] = useState(false);
  const [collapsedWorkspacePane, setCollapsedWorkspacePane] = useState<CollapsedWorkspacePane>(
    persistedWorkspacePrefs?.collapsedPane ?? null,
  );
  const [editorOpen, setEditorOpen] = useState(false);
  const [editorDraft, setEditorDraft] = useState<TermDraft>(() => createBlankDraft([]));
  const [batchOpen, setBatchOpen] = useState(false);
  const [extractOpen, setExtractOpen] = useState(false);
  const [statusNotice, setStatusNotice] = useState<{
    kind: 'success' | 'error';
    message: string;
  } | null>(null);
  const [batchDraft, setBatchDraft] = useState<BatchDraft>({
    termType: '',
    enforcement: '',
    status: '',
    provenance: '',
    partOfSpeech: '',
    caseSensitive: '',
    doNotTranslate: '',
  });
  const [extractRepositoryIds, setExtractRepositoryIds] = useState<number[]>([]);
  const [extractLimit, setExtractLimit] = useState('50');
  const [extractMinOccurrences, setExtractMinOccurrences] = useState('2');
  const [extractTask, setExtractTask] = useState<ApiPollableTask | null>(null);
  const [extractedCandidates, setExtractedCandidates] = useState<ApiExtractedGlossaryCandidate[]>(
    [],
  );
  const [selectedExtractedCandidateTerms, setSelectedExtractedCandidateTerms] = useState<string[]>(
    [],
  );
  const [returnToExtractAfterEditor, setReturnToExtractAfterEditor] = useState(false);
  const [candidateSourceInEditor, setCandidateSourceInEditor] = useState<string | null>(null);
  const [uploadQueue, setUploadQueue] = useState<RequestAttachmentUploadQueueItem[]>([]);
  const optimizeImagesBeforeUpload = true;
  const openedInitialTermIdRef = useRef<number | null>(null);
  const handledBackRequestRef = useRef(backRequestNonce);
  const expandedPrimaryPaneWidthPctRef = useRef(
    persistedWorkspacePrefs?.primaryPaneWidthPct ?? DEFAULT_PRIMARY_PANE_WIDTH_PCT,
  );
  const hydratedWorkspacePrefsGlossaryIdRef = useRef<number | null>(null);

  const extractRepositoryOptions = useMemo(
    () =>
      repositoryOptions
        .filter((repository) => repository.id !== glossary.backingRepository.id)
        .sort((first, second) =>
          first.name.localeCompare(second.name, undefined, { sensitivity: 'base' }),
        ),
    [glossary.backingRepository.id, repositoryOptions],
  );

  const localeOptions = useMemo(
    () =>
      glossary.localeTags
        .map((localeTag) => ({
          tag: localeTag,
          label: resolveLocaleDisplayName(localeTag),
        }))
        .sort((first, second) =>
          first.label.localeCompare(second.label, undefined, { sensitivity: 'base' }),
        ),
    [glossary.localeTags, resolveLocaleDisplayName],
  );
  const editableLocaleOptions = useMemo(
    () => localeOptions.filter((option) => canEditLocale(user, option.tag)),
    [localeOptions, user],
  );

  useEffect(() => {
    const nextPrefs = loadGlossaryWorkspacePrefs(glossary.id);
    const nextSelectedLocaleTags = nextPrefs?.selectedLocaleTags ?? [];
    setSearchDraft(nextPrefs?.searchDraft ?? '');
    setSelectedLocaleTags(nextSelectedLocaleTags);
    setSelectedStatusFilter(nextPrefs?.selectedStatusFilter ?? null);
    setTermsLimit(String(nextPrefs?.termsLimit ?? 200));
    setVisibleLocaleColumnLimit(
      getVisibleLocaleColumnLimitForSelection(
        nextPrefs?.visibleLocaleColumnLimit ?? DEFAULT_VISIBLE_LOCALE_COLUMNS,
        nextSelectedLocaleTags.length,
      ),
    );
    setPrimaryPaneWidthPct(nextPrefs?.primaryPaneWidthPct ?? DEFAULT_PRIMARY_PANE_WIDTH_PCT);
    setCollapsedWorkspacePane(nextPrefs?.collapsedPane ?? null);
    expandedPrimaryPaneWidthPctRef.current =
      nextPrefs?.primaryPaneWidthPct ?? DEFAULT_PRIMARY_PANE_WIDTH_PCT;
    hydratedWorkspacePrefsGlossaryIdRef.current = glossary.id;
  }, [glossary.id]);

  useEffect(() => {
    setSelectedLocaleTags((current) => {
      const next = sortLocaleTagsByOptions(current, localeOptions);
      return areStringArraysEqual(current, next) ? current : next;
    });
  }, [localeOptions]);

  useEffect(() => {
    setVisibleLocaleColumnLimit((current) => {
      const next = getVisibleLocaleColumnLimitForSelection(current, selectedLocaleTags.length);
      return current === next ? current : next;
    });
  }, [selectedLocaleTags.length]);

  useEffect(() => {
    if (collapsedWorkspacePane == null) {
      expandedPrimaryPaneWidthPctRef.current = primaryPaneWidthPct;
    }
  }, [collapsedWorkspacePane, primaryPaneWidthPct]);

  useEffect(() => {
    if (hydratedWorkspacePrefsGlossaryIdRef.current !== glossary.id) {
      return;
    }
    saveGlossaryWorkspacePrefs(glossary.id, {
      searchDraft,
      selectedLocaleTags,
      selectedStatusFilter,
      termsLimit: normalizePersistedPositiveNumber(termsLimit, 200),
      visibleLocaleColumnLimit,
      primaryPaneWidthPct:
        collapsedWorkspacePane == null
          ? primaryPaneWidthPct
          : expandedPrimaryPaneWidthPctRef.current,
      collapsedPane: collapsedWorkspacePane,
    });
  }, [
    collapsedWorkspacePane,
    glossary.id,
    primaryPaneWidthPct,
    searchDraft,
    selectedLocaleTags,
    selectedStatusFilter,
    termsLimit,
    visibleLocaleColumnLimit,
  ]);

  useEffect(() => {
    const nextUrls = new Set(
      uploadQueue
        .map((item) => item.preview)
        .filter((preview): preview is string => typeof preview === 'string' && preview.length > 0),
    );
    revokeRequestAttachmentUploadQueuePreviews(
      Array.from(previewUrlsRef.current)
        .filter((url) => !nextUrls.has(url))
        .map((preview) => ({ preview })),
    );
    previewUrlsRef.current = nextUrls;
  }, [uploadQueue]);

  useEffect(() => {
    return () => {
      revokeRequestAttachmentUploadQueuePreviews(
        Array.from(previewUrlsRef.current).map((preview) => ({ preview })),
      );
      previewUrlsRef.current.clear();
    };
  }, []);

  useEffect(() => {
    if (extractRepositoryIds.length > 0) {
      return;
    }
    if (glossary.scopeMode === 'SELECTED_REPOSITORIES') {
      const availableRepositoryIds = new Set(
        extractRepositoryOptions.map((repository) => repository.id),
      );
      setExtractRepositoryIds(
        glossary.repositories
          .map((repository) => repository.id)
          .filter((repositoryId) => availableRepositoryIds.has(repositoryId)),
      );
    }
  }, [
    extractRepositoryIds.length,
    extractRepositoryOptions,
    glossary.repositories,
    glossary.scopeMode,
  ]);

  useEffect(() => {
    const availableRepositoryIds = new Set(
      extractRepositoryOptions.map((repository) => repository.id),
    );
    setExtractRepositoryIds((current) => {
      const next = current.filter((repositoryId) => availableRepositoryIds.has(repositoryId));
      return areNumberArraysEqual(current, next) ? current : next;
    });
  }, [extractRepositoryOptions]);

  const termsQuery = useQuery({
    queryKey: [
      'glossary-terms',
      glossary.id,
      searchDraft,
      selectedLocaleTags.join('|'),
      termsLimit,
    ],
    queryFn: () =>
      fetchGlossaryTerms(glossary.id, {
        search: searchDraft,
        localeTags: selectedLocaleTags,
        limit: Number.parseInt(termsLimit.trim() || '200', 10),
      }),
    staleTime: 15_000,
  });

  const saveTermMutation = useMutation({
    mutationFn: async (draft: TermDraft) => {
      const request = draftToRequest(draft, {
        includeTranslations: !canManageTerms && draft.tmTextUnitId == null,
      });
      return draft.tmTextUnitId == null
        ? createGlossaryTerm(glossary.id, request)
        : updateGlossaryTerm(glossary.id, draft.tmTextUnitId, request);
    },
    onSuccess: async (savedTerm) => {
      await queryClient.invalidateQueries({ queryKey: ['glossary-terms', glossary.id] });
      setEditorOpen(false);
      setUploadQueue([]);
      if (returnToExtractAfterEditor) {
        setExtractOpen(true);
      }
      if (candidateSourceInEditor) {
        setExtractedCandidates((current) =>
          current.filter((candidate) => candidate.term !== candidateSourceInEditor),
        );
      }
      setReturnToExtractAfterEditor(false);
      setCandidateSourceInEditor(null);
      setSelectedTermIds([]);
      setStatusNotice({
        kind: 'success',
        message: canManageTerms
          ? `Saved glossary term ${savedTerm.source}.`
          : `Submitted glossary candidate ${savedTerm.source}.`,
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to save glossary term.',
      });
    },
  });

  const batchMutation = useMutation({
    mutationFn: () =>
      batchUpdateGlossaryTerms(glossary.id, {
        tmTextUnitIds: selectedTermIds,
        partOfSpeech: batchDraft.partOfSpeech.trim() || null,
        termType: batchDraft.termType || null,
        enforcement: batchDraft.enforcement || null,
        status: batchDraft.status || null,
        provenance: batchDraft.provenance || null,
        caseSensitive: toBooleanPatchValue(batchDraft.caseSensitive),
        doNotTranslate: toBooleanPatchValue(batchDraft.doNotTranslate),
      }),
    onSuccess: async (result) => {
      await queryClient.invalidateQueries({ queryKey: ['glossary-terms', glossary.id] });
      setBatchOpen(false);
      setSelectedTermIds([]);
      setStatusNotice({
        kind: 'success',
        message: `Updated ${result.updatedTermCount} glossary terms.`,
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to update glossary terms.',
      });
    },
  });

  const statusMutation = useMutation({
    mutationFn: async ({ term, status }: { term: ApiGlossaryTerm; status: string }) => {
      const draft = termToDraft(term, glossary.localeTags);
      draft.status = status;
      return updateGlossaryTerm(
        glossary.id,
        term.tmTextUnitId,
        draftToRequest(draft, { includeTranslations: false }),
      );
    },
    onSuccess: async (savedTerm) => {
      await queryClient.invalidateQueries({ queryKey: ['glossary-terms', glossary.id] });
      setOpenStatusTermId(null);
      setStatusNotice({
        kind: 'success',
        message: `Updated ${savedTerm.source} to ${savedTerm.status ?? 'CANDIDATE'}.`,
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to update glossary term status.',
      });
    },
  });

  const extractMutation = useMutation({
    mutationFn: async () => {
      const pollableTask = await startGlossaryTermExtraction(glossary.id, {
        repositoryIds: extractRepositoryIds,
        limit: Number.parseInt(extractLimit.trim() || '50', 10),
        minOccurrences: Number.parseInt(extractMinOccurrences.trim() || '2', 10),
      });
      setExtractTask(pollableTask);
      const latestTask = await fetchGlossaryExtractionTask(pollableTask.id);
      setExtractTask(latestTask);
      const completedTask = await waitForGlossaryExtractionTask(pollableTask.id);
      setExtractTask(completedTask);
      return fetchGlossaryExtractionOutput(pollableTask.id);
    },
    onMutate: () => {
      setExtractTask(null);
      setExtractedCandidates([]);
      setStatusNotice({
        kind: 'success',
        message: 'Started glossary extraction…',
      });
    },
    onSuccess: (result) => {
      setExtractedCandidates(result.candidates);
      setSelectedExtractedCandidateTerms([]);
      setStatusNotice({
        kind: 'success',
        message: `Loaded ${result.candidates.length} glossary candidates.`,
      });
    },
    onError: (error: Error) => {
      setExtractTask(null);
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to extract glossary candidates.',
      });
    },
  });

  const batchAddExtractedCandidatesMutation = useMutation({
    mutationFn: async (candidates: ApiExtractedGlossaryCandidate[]) => {
      const results = await Promise.allSettled(
        candidates.map(async (candidate) => ({
          candidate,
          saved: await createGlossaryTerm(glossary.id, extractedCandidateToRequest(candidate)),
        })),
      );

      const succeeded = results
        .filter(
          (
            result,
          ): result is PromiseFulfilledResult<{
            candidate: ApiExtractedGlossaryCandidate;
            saved: ApiGlossaryTerm;
          }> => result.status === 'fulfilled',
        )
        .map((result) => result.value.candidate.term);

      return {
        succeeded,
        failed: results.length - succeeded.length,
      };
    },
    onSuccess: async ({ succeeded, failed }) => {
      await queryClient.invalidateQueries({ queryKey: ['glossary-terms', glossary.id] });
      setExtractedCandidates((current) =>
        current.filter((candidate) => !succeeded.includes(candidate.term)),
      );
      setSelectedExtractedCandidateTerms((current) =>
        current.filter((term) => !succeeded.includes(term)),
      );
      setStatusNotice({
        kind: failed > 0 ? 'error' : 'success',
        message:
          failed > 0
            ? `Added ${succeeded.length} extracted terms. ${failed} failed and need review.`
            : `Added ${succeeded.length} extracted terms.`,
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to add extracted glossary terms.',
      });
    },
  });

  const terms = useMemo(() => {
    const nextTerms = termsQuery.data?.terms ?? [];
    if (selectedStatusFilter == null) {
      return nextTerms;
    }
    return nextTerms.filter(
      (term) => (term.status ?? 'CANDIDATE').toUpperCase() === selectedStatusFilter,
    );
  }, [selectedStatusFilter, termsQuery.data?.terms]);
  const displayedLocaleTags = selectedLocaleTags.slice(0, visibleLocaleColumnLimit);
  const hiddenLocaleColumnCount = Math.max(
    selectedLocaleTags.length - displayedLocaleTags.length,
    0,
  );
  const visibleLocaleColumnLimitOptions = useMemo(
    () =>
      Array.from(new Set(VISIBLE_LOCALE_COLUMN_PRESETS))
        .filter((value) => value >= 1)
        .filter((value) => value <= Math.max(selectedLocaleTags.length, 1))
        .sort((first, second) => first - second),
    [selectedLocaleTags.length],
  );
  const localeColumnSummary =
    selectedLocaleTags.length === 0
      ? null
      : hiddenLocaleColumnCount > 0
        ? `Showing ${displayedLocaleTags.length} of ${selectedLocaleTags.length} locale columns`
        : `${selectedLocaleTags.length} locale column${selectedLocaleTags.length === 1 ? '' : 's'}`;
  const allVisibleSelected =
    terms.length > 0 && terms.every((term) => selectedTermIds.includes(term.tmTextUnitId));
  const selectedExtractedCandidates = useMemo(
    () =>
      extractedCandidates.filter((candidate) =>
        selectedExtractedCandidateTerms.includes(candidate.term),
      ),
    [extractedCandidates, selectedExtractedCandidateTerms],
  );
  const allExtractedCandidatesSelected =
    extractedCandidates.length > 0 &&
    extractedCandidates.every((candidate) =>
      selectedExtractedCandidateTerms.includes(candidate.term),
    );

  const selectedTerms = useMemo(
    () => terms.filter((term) => selectedTermIds.includes(term.tmTextUnitId)),
    [selectedTermIds, terms],
  );

  const workbenchLocaleTags = selectedLocaleTags;
  const editorWorkbenchState =
    editorDraft.tmTextUnitId == null
      ? null
      : buildGlossaryWorkbenchState({
          backingRepositoryId: glossary.backingRepository.id,
          localeTags: workbenchLocaleTags,
          tmTextUnitId: editorDraft.tmTextUnitId,
        });
  const canEditProposalDraft = canManageTerms || editorDraft.tmTextUnitId == null;
  const editorTitle =
    editorDraft.tmTextUnitId == null
      ? canManageTerms
        ? 'Create glossary term'
        : 'Propose glossary term'
      : canManageTerms && editorDraft.status === 'CANDIDATE'
        ? `Review candidate: ${editorDraft.source}`
        : editorDraft.source;
  const detailPlaceholder = extractOpen
    ? {
        title: 'Review candidates in context',
        message:
          'Select a candidate to review it here, or add candidates directly from the queue on the left.',
      }
    : {
        title: canManageTerms ? 'Select a glossary term' : 'Browse glossary terms',
        message: canManageTerms
          ? 'Select a term to edit it here, or create a new term without leaving the table.'
          : 'Select a term to inspect its metadata, translations, and references.',
      };

  useEffect(() => {
    if (!onViewStateChange) {
      return;
    }

    if (editorOpen) {
      onViewStateChange({ mode: 'editor', title: editorTitle });
      return;
    }

    if (extractOpen) {
      onViewStateChange({ mode: 'extract', title: 'Extract candidates' });
      return;
    }

    onViewStateChange({ mode: 'terms', title: glossary.name });
  }, [editorOpen, editorTitle, extractOpen, glossary.name, onViewStateChange]);

  const openCreateModal = () => {
    setReturnToExtractAfterEditor(false);
    setCandidateSourceInEditor(null);
    setUploadQueue([]);
    setEditorDraft(createBlankDraft([]));
    setCollapsedWorkspacePane(null);
    setEditorOpen(true);
  };

  const openEditModal = (term: ApiGlossaryTerm) => {
    setReturnToExtractAfterEditor(false);
    setCandidateSourceInEditor(null);
    setUploadQueue([]);
    setEditorDraft(termToDraft(term, glossary.localeTags));
    setCollapsedWorkspacePane(null);
    setEditorOpen(true);
  };

  const openCandidateModal = (candidate: ApiExtractedGlossaryCandidate) => {
    const nextDraft = createBlankDraft([]);
    nextDraft.source = candidate.term;
    nextDraft.termType = candidate.suggestedTermType || 'GENERAL';
    nextDraft.provenance = candidate.suggestedProvenance || 'AI_EXTRACTED';
    nextDraft.partOfSpeech = candidate.suggestedPartOfSpeech || '';
    nextDraft.enforcement = candidate.suggestedEnforcement || 'SOFT';
    nextDraft.doNotTranslate = candidate.suggestedDoNotTranslate;
    setReturnToExtractAfterEditor(true);
    setCandidateSourceInEditor(candidate.term);
    setUploadQueue([]);
    setEditorDraft(nextDraft);
    setCollapsedWorkspacePane(null);
    setEditorOpen(true);
  };

  const closeEditor = useCallback(() => {
    if (saveTermMutation.isPending) {
      return;
    }
    setEditorOpen(false);
    setUploadQueue([]);
    if (returnToExtractAfterEditor) {
      setExtractOpen(true);
    }
    setReturnToExtractAfterEditor(false);
    setCandidateSourceInEditor(null);
  }, [returnToExtractAfterEditor, saveTermMutation.isPending]);

  useEffect(() => {
    if (handledBackRequestRef.current === backRequestNonce) {
      return;
    }
    handledBackRequestRef.current = backRequestNonce;

    if (editorOpen) {
      closeEditor();
      return;
    }

    if (extractOpen) {
      setExtractOpen(false);
    }
  }, [backRequestNonce, closeEditor, editorOpen, extractOpen]);

  useEffect(() => {
    if (initialOpenTermId == null) {
      openedInitialTermIdRef.current = null;
      return;
    }
    if (openedInitialTermIdRef.current === initialOpenTermId) {
      return;
    }

    const requestedTerm = terms.find((term) => term.tmTextUnitId === initialOpenTermId);
    if (!requestedTerm) {
      return;
    }

    openedInitialTermIdRef.current = initialOpenTermId;
    setReturnToExtractAfterEditor(false);
    setCandidateSourceInEditor(null);
    setUploadQueue([]);
    setEditorDraft(termToDraft(requestedTerm, glossary.localeTags));
    setCollapsedWorkspacePane(null);
    setEditorOpen(true);
  }, [glossary.localeTags, initialOpenTermId, terms]);

  const addProposalTranslation = () => {
    const usedLocaleTags = new Set(
      editorDraft.translations.map((translation) => translation.localeTag.toLowerCase()),
    );
    const nextLocaleTag =
      editableLocaleOptions.find((option) => !usedLocaleTags.has(option.tag.toLowerCase()))?.tag ??
      '';
    setEditorDraft((current) => ({
      ...current,
      translations: [...current.translations, createBlankTranslation(nextLocaleTag)],
    }));
  };

  const handleReferenceScreenshotFiles = async (files: FileList | null): Promise<void> => {
    if (!files || files.length === 0) {
      return;
    }

    const imageFiles = Array.from(files).filter(
      (file) => getAttachmentKindFromFile(file) === 'image',
    );
    if (imageFiles.length === 0) {
      setStatusNotice({
        kind: 'error',
        message: 'Only image files can be uploaded as glossary screenshots.',
      });
      return;
    }

    const preparedFiles = await Promise.all(
      imageFiles.map((file) =>
        prepareDbBackedUploadFile(file, { optimizeImages: optimizeImagesBeforeUpload }),
      ),
    );
    const queueEntries = buildRequestAttachmentUploadQueueEntries(
      preparedFiles.map((prepared) => ({
        file: prepared.file,
        displayName: prepared.displayName,
        warning: prepared.warning,
      })),
    );
    setUploadQueue((current) => [...queueEntries, ...current]);

    await Promise.all(
      queueEntries.map(async (entry, index) => {
        const file = preparedFiles[index]?.file;
        const prepared = preparedFiles[index];
        if (!file || !prepared) {
          return;
        }
        if (!canUploadRequestAttachmentFile(file)) {
          return;
        }
        try {
          const uploadedKey = await uploadRequestAttachmentFile(file);
          setUploadQueue((current) => current.filter((item) => item.key !== entry.key));
          setEditorDraft((current) => ({
            ...current,
            references: [
              ...current.references,
              {
                id: createClientId(),
                referenceType: 'SCREENSHOT',
                caption: prepared.displayName,
                imageKey: uploadedKey,
                tmTextUnitId: '',
              },
            ],
          }));
        } catch (error) {
          const message =
            error instanceof Error ? error.message : 'Upload failed. Please try again.';
          setUploadQueue((current) =>
            current.map((item) =>
              item.key === entry.key ? { ...item, status: 'error', error: message } : item,
            ),
          );
        }
      }),
    );
  };

  const startWorkspaceResize = useCallback(
    (event: ReactMouseEvent<HTMLDivElement>) => {
      event.preventDefault();
      setIsResizingWorkspace(true);
      let collapsedPaneDuringDrag = collapsedWorkspacePane;

      const onMove = (moveEvent: MouseEvent) => {
        if (!workspaceLayoutRef.current) {
          return;
        }
        const rect = workspaceLayoutRef.current.getBoundingClientRect();
        const x = moveEvent.clientX - rect.left;
        const pct = (x / rect.width) * 100;

        if (pct <= PRIMARY_PANE_COLLAPSE_SNAP_PCT) {
          if (collapsedPaneDuringDrag !== 'primary') {
            collapsedPaneDuringDrag = 'primary';
            expandedPrimaryPaneWidthPctRef.current = primaryPaneWidthPct;
            setCollapsedWorkspacePane('primary');
          }
          return;
        }

        if (pct >= DETAIL_PANE_COLLAPSE_SNAP_PCT) {
          if (collapsedPaneDuringDrag !== 'detail') {
            collapsedPaneDuringDrag = 'detail';
            expandedPrimaryPaneWidthPctRef.current = primaryPaneWidthPct;
            setCollapsedWorkspacePane('detail');
          }
          return;
        }

        if (collapsedPaneDuringDrag === 'primary' && pct < PRIMARY_PANE_EXPAND_SNAP_PCT) {
          return;
        }

        if (collapsedPaneDuringDrag === 'detail' && pct > DETAIL_PANE_EXPAND_SNAP_PCT) {
          return;
        }

        const nextPct = Math.min(
          MAX_PRIMARY_PANE_WIDTH_PCT,
          Math.max(MIN_PRIMARY_PANE_WIDTH_PCT, pct),
        );
        if (collapsedPaneDuringDrag !== null) {
          collapsedPaneDuringDrag = null;
          setCollapsedWorkspacePane(null);
        }
        expandedPrimaryPaneWidthPctRef.current = nextPct;
        setPrimaryPaneWidthPct(nextPct);
      };

      const onUp = () => {
        setIsResizingWorkspace(false);
        window.removeEventListener('mousemove', onMove);
        window.removeEventListener('mouseup', onUp);
      };

      window.addEventListener('mousemove', onMove);
      window.addEventListener('mouseup', onUp);
    },
    [collapsedWorkspacePane, primaryPaneWidthPct],
  );

  const toggleDetailPaneCollapsed = useCallback(() => {
    setCollapsedWorkspacePane((current) => {
      if (current !== null) {
        setPrimaryPaneWidthPct(expandedPrimaryPaneWidthPctRef.current);
        return null;
      }
      expandedPrimaryPaneWidthPctRef.current = primaryPaneWidthPct;
      return 'detail';
    });
  }, [primaryPaneWidthPct]);

  const workspaceLayoutStyle = {
    '--glossary-term-admin-primary-width': `${primaryPaneWidthPct}%`,
  } as CSSProperties;

  return (
    <section
      className={`settings-card glossary-term-admin${editorOpen ? ' glossary-term-admin--editing' : ''}`}
    >
      {(editorOpen || extractOpen) && statusNotice ? (
        <p className={`settings-hint${statusNotice.kind === 'error' ? ' is-error' : ''}`}>
          {statusNotice.message}
        </p>
      ) : null}

      <div
        className={`glossary-term-admin__workspace${
          collapsedWorkspacePane === 'primary'
            ? ' glossary-term-admin__workspace--primary-collapsed'
            : collapsedWorkspacePane === 'detail'
              ? ' glossary-term-admin__workspace--detail-collapsed'
              : ''
        }`}
        ref={workspaceLayoutRef}
        style={workspaceLayoutStyle}
      >
        <div className="glossary-term-admin__workspace-pane glossary-term-admin__workspace-pane--primary">
          {extractOpen ? (
            <GlossaryExtractView
              extractRepositoryOptions={extractRepositoryOptions}
              extractRepositoryIds={extractRepositoryIds}
              onChangeExtractRepositoryIds={(next) =>
                setExtractRepositoryIds([...next].sort((a, b) => a - b))
              }
              extractMinOccurrences={extractMinOccurrences}
              onChangeExtractMinOccurrences={setExtractMinOccurrences}
              onRunExtraction={() => extractMutation.mutate()}
              isExtracting={extractMutation.isPending}
              canRunExtraction={!extractMutation.isPending && extractRepositoryIds.length > 0}
              extractTask={extractTask}
              extractedCandidates={extractedCandidates}
              extractLimit={Number.parseInt(extractLimit.trim() || '50', 10)}
              onChangeExtractLimit={(value) => setExtractLimit(String(value))}
              limitPresetOptions={EXTRACT_LIMIT_PRESETS}
              allExtractedCandidatesSelected={allExtractedCandidatesSelected}
              onToggleSelectAllExtracted={() =>
                setSelectedExtractedCandidateTerms(
                  allExtractedCandidatesSelected
                    ? []
                    : extractedCandidates
                        .filter((candidate) => !candidate.existingInGlossary)
                        .map((candidate) => candidate.term),
                )
              }
              selectedExtractedCandidatesCount={selectedExtractedCandidates.length}
              onAddSelectedExtracted={() =>
                batchAddExtractedCandidatesMutation.mutate(selectedExtractedCandidates)
              }
              isAddingSelected={batchAddExtractedCandidatesMutation.isPending}
              selectedExtractedCandidateTerms={selectedExtractedCandidateTerms}
              onToggleExtractedCandidate={(term, checked) =>
                setSelectedExtractedCandidateTerms((current) =>
                  checked
                    ? Array.from(new Set([...current, term]))
                    : current.filter((currentTerm) => currentTerm !== term),
                )
              }
              onOpenCandidateModal={openCandidateModal}
              onAddCandidate={(candidate) =>
                batchAddExtractedCandidatesMutation.mutate([candidate])
              }
            />
          ) : (
            <GlossaryTermsListView
              canManageTerms={canManageTerms}
              searchDraft={searchDraft}
              onChangeSearch={(value) => {
                setSearchDraft(value);
                setStatusNotice(null);
              }}
              localeOptions={localeOptions}
              selectedLocaleTags={selectedLocaleTags}
              onChangeSelectedLocaleTags={(next) => {
                setSelectedLocaleTags(sortLocaleTagsByOptions(next, localeOptions));
                setStatusNotice(null);
              }}
              statusFilterOptions={STATUS_FILTER_OPTIONS.map((option) => ({
                value: option.value,
                label: option.label,
              }))}
              selectedStatusFilter={selectedStatusFilter}
              onChangeSelectedStatusFilter={setSelectedStatusFilter}
              onOpenExtract={() => setExtractOpen(true)}
              onOpenCreate={openCreateModal}
              canImport={canImport && typeof onOpenImport === 'function'}
              onOpenImport={() => onOpenImport?.()}
              canExport={canExport && typeof onOpenExport === 'function'}
              onOpenExport={() => onOpenExport?.()}
              termsTotalCount={termsQuery.data?.totalCount ?? 0}
              visibleTermsCount={terms.length}
              termsLimit={Number.parseInt(termsLimit.trim() || '200', 10)}
              onChangeTermsLimit={(value) => setTermsLimit(String(value))}
              termsLimitOptions={TERMS_LIMIT_PRESETS}
              visibleLocaleColumnLimit={visibleLocaleColumnLimit}
              onChangeVisibleLocaleColumnLimit={(value) =>
                setVisibleLocaleColumnLimit(
                  Math.min(Math.max(1, value), Math.max(selectedLocaleTags.length, 1)),
                )
              }
              visibleLocaleColumnLimitOptions={visibleLocaleColumnLimitOptions}
              localeColumnSummary={localeColumnSummary}
              selectedTermIdsCount={selectedTermIds.length}
              onOpenBatch={() => setBatchOpen(true)}
              onClearSelection={() => setSelectedTermIds([])}
              statusNotice={!editorOpen ? statusNotice : null}
              isLoading={termsQuery.isLoading}
              errorMessage={
                termsQuery.isError
                  ? termsQuery.error instanceof Error
                    ? termsQuery.error.message
                    : 'Could not load glossary terms.'
                  : null
              }
              terms={terms}
              displayedLocaleTags={displayedLocaleTags}
              allVisibleSelected={allVisibleSelected}
              onToggleSelectAll={() =>
                setSelectedTermIds(allVisibleSelected ? [] : terms.map((term) => term.tmTextUnitId))
              }
              onOpenEditTerm={openEditModal}
              selectedTermIds={selectedTermIds}
              onToggleTermSelection={(tmTextUnitId, checked) =>
                setSelectedTermIds((current) =>
                  checked
                    ? Array.from(new Set([...current, tmTextUnitId]))
                    : current.filter((id) => id !== tmTextUnitId),
                )
              }
              getWorkbenchHref={(tmTextUnitId) => {
                const params = new URLSearchParams();
                params.set('tmTextUnitId', String(tmTextUnitId));
                params.set('repo', String(glossary.backingRepository.id));
                workbenchLocaleTags.forEach((localeTag) => params.append('locale', localeTag));
                return `/workbench?${params.toString()}`;
              }}
              getWorkbenchState={(tmTextUnitId) =>
                buildGlossaryWorkbenchState({
                  backingRepositoryId: glossary.backingRepository.id,
                  localeTags: workbenchLocaleTags,
                  tmTextUnitId,
                })
              }
              statusOptions={[...STATUSES]}
              getStatusLabel={(status) =>
                STATUS_LABELS[status as (typeof STATUSES)[number]] ?? status
              }
              isChangingStatus={statusMutation.isPending}
              openStatusTermId={openStatusTermId}
              onOpenStatusTermIdChange={(tmTextUnitId, nextOpen) => {
                setOpenStatusTermId((current) => {
                  if (nextOpen) {
                    return tmTextUnitId;
                  }
                  return current === tmTextUnitId ? null : current;
                });
              }}
              onChangeTermStatus={(term, status) => statusMutation.mutate({ term, status })}
            />
          )}
        </div>
        <div
          className={`glossary-term-admin__resize-handle${
            isResizingWorkspace ? ' is-resizing' : ''
          }`}
          onMouseDown={startWorkspaceResize}
          role="separator"
          aria-label={
            collapsedWorkspacePane === 'primary'
              ? 'Expand glossary table'
              : collapsedWorkspacePane === 'detail'
                ? 'Expand glossary detail panel'
                : 'Focus glossary table'
          }
          aria-orientation="vertical"
          aria-valuemin={MIN_PRIMARY_PANE_WIDTH_PCT}
          aria-valuemax={MAX_PRIMARY_PANE_WIDTH_PCT}
          aria-valuenow={
            collapsedWorkspacePane === 'primary'
              ? 0
              : collapsedWorkspacePane === 'detail'
                ? 100
                : Math.round(primaryPaneWidthPct)
          }
          aria-expanded={collapsedWorkspacePane === null}
          title={
            collapsedWorkspacePane === 'primary'
              ? 'Expand glossary table'
              : collapsedWorkspacePane === 'detail'
                ? 'Expand glossary detail panel'
                : 'Focus glossary table'
          }
        >
          <button
            type="button"
            className="glossary-term-admin__handle-grip review-project-handle-button"
            onClick={toggleDetailPaneCollapsed}
            onMouseDown={(event) => event.stopPropagation()}
            aria-label={
              collapsedWorkspacePane === 'primary'
                ? 'Expand glossary table'
                : collapsedWorkspacePane === 'detail'
                  ? 'Expand glossary detail panel'
                  : 'Focus glossary table'
            }
            title={
              collapsedWorkspacePane === 'primary'
                ? 'Expand glossary table'
                : collapsedWorkspacePane === 'detail'
                  ? 'Expand glossary detail panel'
                  : 'Focus glossary table'
            }
          >
            <Chevron direction={collapsedWorkspacePane === 'detail' ? 'left' : 'right'} />
          </button>
        </div>
        <div className="glossary-term-admin__workspace-pane glossary-term-admin__workspace-pane--detail">
          {editorOpen ? (
            <div className="glossary-term-admin__editor-page">
              <div className="glossary-term-admin__editor">
                <div className="glossary-term-admin__workflow-row">
                  <div className="glossary-term-admin__workflow-field">
                    <label className="settings-field__label" htmlFor="glossary-term-status">
                      Status
                    </label>
                    <PillDropdown
                      value={editorDraft.status}
                      options={STATUSES.map((status) => ({
                        value: status,
                        label: STATUS_LABELS[status],
                      }))}
                      onChange={(value) =>
                        setEditorDraft((current) => ({ ...current, status: value }))
                      }
                      ariaLabel="Glossary term status"
                      className="glossary-term-admin__status-dropdown"
                      disabled={!canManageTerms}
                    />
                  </div>
                </div>

                <div className="settings-grid settings-grid--two-column">
                  <div className="settings-field">
                    <label className="settings-field__label" htmlFor="glossary-term-source">
                      Source term
                    </label>
                    <input
                      id="glossary-term-source"
                      type="text"
                      className="settings-input"
                      value={editorDraft.source}
                      disabled={!canEditProposalDraft}
                      onChange={(event) =>
                        setEditorDraft((current) => ({ ...current, source: event.target.value }))
                      }
                    />
                  </div>
                  <div className="settings-field">
                    <label className="settings-field__label" htmlFor="glossary-term-key">
                      Term key
                    </label>
                    <input
                      id="glossary-term-key"
                      type="text"
                      className="settings-input"
                      value={editorDraft.termKey}
                      disabled={!canManageTerms || editorDraft.tmTextUnitId != null}
                      onChange={(event) =>
                        setEditorDraft((current) => ({ ...current, termKey: event.target.value }))
                      }
                    />
                  </div>
                </div>

                <div className="settings-field">
                  <label className="settings-field__label" htmlFor="glossary-term-definition">
                    Definition
                  </label>
                  <textarea
                    id="glossary-term-definition"
                    className="settings-input"
                    value={editorDraft.definition}
                    disabled={!canEditProposalDraft}
                    onChange={(event) =>
                      setEditorDraft((current) => ({ ...current, definition: event.target.value }))
                    }
                  />
                </div>

                <div className="settings-field">
                  <label className="settings-field__label" htmlFor="glossary-term-source-comment">
                    Source note
                  </label>
                  <textarea
                    id="glossary-term-source-comment"
                    className="settings-input"
                    value={editorDraft.sourceComment}
                    disabled={!canEditProposalDraft}
                    onChange={(event) =>
                      setEditorDraft((current) => ({
                        ...current,
                        sourceComment: event.target.value,
                      }))
                    }
                  />
                </div>

                <div className="settings-grid settings-grid--two-column">
                  <div className="settings-field">
                    <label className="settings-field__label" htmlFor="glossary-term-pos">
                      Part of speech
                    </label>
                    <input
                      id="glossary-term-pos"
                      type="text"
                      className="settings-input"
                      value={editorDraft.partOfSpeech}
                      disabled={!canEditProposalDraft}
                      onChange={(event) =>
                        setEditorDraft((current) => ({
                          ...current,
                          partOfSpeech: event.target.value,
                        }))
                      }
                    />
                  </div>
                  <div className="settings-field">
                    <label className="settings-field__label" htmlFor="glossary-term-type">
                      Term type
                    </label>
                    <select
                      id="glossary-term-type"
                      className="settings-input"
                      value={editorDraft.termType}
                      disabled={!canEditProposalDraft}
                      onChange={(event) =>
                        setEditorDraft((current) => ({ ...current, termType: event.target.value }))
                      }
                    >
                      {TERM_TYPES.map((termType) => (
                        <option key={termType} value={termType}>
                          {termType}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="settings-field">
                    <label className="settings-field__label" htmlFor="glossary-term-enforcement">
                      Enforcement
                    </label>
                    <select
                      id="glossary-term-enforcement"
                      className="settings-input"
                      value={editorDraft.enforcement}
                      disabled={!canManageTerms}
                      onChange={(event) =>
                        setEditorDraft((current) => ({
                          ...current,
                          enforcement: event.target.value,
                        }))
                      }
                    >
                      {ENFORCEMENTS.map((enforcement) => (
                        <option key={enforcement} value={enforcement}>
                          {enforcement}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="settings-field">
                    <label className="settings-field__label" htmlFor="glossary-term-provenance">
                      Provenance
                    </label>
                    <select
                      id="glossary-term-provenance"
                      className="settings-input"
                      value={editorDraft.provenance}
                      disabled={!canManageTerms}
                      onChange={(event) =>
                        setEditorDraft((current) => ({
                          ...current,
                          provenance: event.target.value,
                        }))
                      }
                    >
                      {PROVENANCES.map((provenance) => (
                        <option key={provenance} value={provenance}>
                          {provenance}
                        </option>
                      ))}
                    </select>
                  </div>
                </div>

                <div className="glossary-term-admin__toggle-row">
                  <label className="settings-toggle">
                    <input
                      type="checkbox"
                      checked={editorDraft.caseSensitive}
                      disabled={!canEditProposalDraft}
                      onChange={(event) =>
                        setEditorDraft((current) => ({
                          ...current,
                          caseSensitive: event.target.checked,
                        }))
                      }
                    />
                    <span>Case-sensitive</span>
                  </label>
                  <label className="settings-toggle">
                    <input
                      type="checkbox"
                      checked={editorDraft.doNotTranslate}
                      disabled={!canEditProposalDraft}
                      onChange={(event) =>
                        setEditorDraft((current) => ({
                          ...current,
                          doNotTranslate: event.target.checked,
                        }))
                      }
                    />
                    <span>Do not translate</span>
                  </label>
                </div>

                {!canManageTerms ? (
                  <section className="glossary-term-admin__section">
                    <div className="settings-card__header">
                      <h4>Optional translations</h4>
                    </div>
                    <>
                      <p className="settings-hint">
                        Add target translations for locales you can edit. Leave this empty if you
                        are only proposing the source term.
                      </p>
                      {editableLocaleOptions.length === 0 ? (
                        <p className="settings-hint">
                          You can still submit the candidate term, but you do not have editable
                          locales for target translations.
                        </p>
                      ) : null}
                      <div className="glossary-term-admin__section-actions">
                        <button
                          type="button"
                          className="settings-button settings-button--ghost"
                          onClick={addProposalTranslation}
                          disabled={
                            !canEditProposalDraft ||
                            editableLocaleOptions.length === 0 ||
                            editorDraft.translations.length >= editableLocaleOptions.length
                          }
                        >
                          Add translation
                        </button>
                      </div>
                      {editorDraft.translations.length === 0 ? null : (
                        <div className="glossary-term-admin__translation-list">
                          {editorDraft.translations.map((translation) => {
                            const usedLocaleTags = new Set(
                              editorDraft.translations
                                .filter((item) => item.id !== translation.id)
                                .map((item) => item.localeTag.toLowerCase()),
                            );

                            return (
                              <div
                                key={translation.id}
                                className="glossary-term-admin__translation-row"
                              >
                                <select
                                  className="settings-input"
                                  value={translation.localeTag}
                                  disabled={!canEditProposalDraft}
                                  onChange={(event) =>
                                    setEditorDraft((current) => ({
                                      ...current,
                                      translations: current.translations.map((item) =>
                                        item.id === translation.id
                                          ? { ...item, localeTag: event.target.value }
                                          : item,
                                      ),
                                    }))
                                  }
                                >
                                  <option value="">Locale</option>
                                  {editableLocaleOptions
                                    .filter(
                                      (option) =>
                                        option.tag === translation.localeTag ||
                                        !usedLocaleTags.has(option.tag.toLowerCase()),
                                    )
                                    .map((option) => (
                                      <option key={option.tag} value={option.tag}>
                                        {option.label}
                                      </option>
                                    ))}
                                </select>
                                <input
                                  type="text"
                                  className="settings-input"
                                  placeholder="Target translation"
                                  value={translation.target}
                                  disabled={!canEditProposalDraft}
                                  onChange={(event) =>
                                    setEditorDraft((current) => ({
                                      ...current,
                                      translations: current.translations.map((item) =>
                                        item.id === translation.id
                                          ? { ...item, target: event.target.value }
                                          : item,
                                      ),
                                    }))
                                  }
                                />
                                <input
                                  type="text"
                                  className="settings-input"
                                  placeholder="Target note"
                                  value={translation.targetComment}
                                  disabled={!canEditProposalDraft}
                                  onChange={(event) =>
                                    setEditorDraft((current) => ({
                                      ...current,
                                      translations: current.translations.map((item) =>
                                        item.id === translation.id
                                          ? { ...item, targetComment: event.target.value }
                                          : item,
                                      ),
                                    }))
                                  }
                                />
                                <button
                                  type="button"
                                  className="settings-button settings-button--ghost"
                                  disabled={!canEditProposalDraft}
                                  onClick={() =>
                                    setEditorDraft((current) => ({
                                      ...current,
                                      translations: current.translations.filter(
                                        (item) => item.id !== translation.id,
                                      ),
                                    }))
                                  }
                                >
                                  Remove
                                </button>
                              </div>
                            );
                          })}
                        </div>
                      )}
                    </>
                  </section>
                ) : null}

                <section className="glossary-term-admin__section">
                  <div className="settings-card__header">
                    <div>
                      <h4>References</h4>
                      <p className="settings-hint">
                        Capture why this term exists: reviewer notes, observed usage, code context,
                        or screenshots.
                      </p>
                    </div>
                    {canEditProposalDraft ? (
                      <div className="glossary-term-admin__section-actions">
                        <button
                          type="button"
                          className="settings-button settings-button--ghost"
                          onClick={() =>
                            setEditorDraft((current) => ({
                              ...current,
                              references: [...current.references, createBlankReference('NOTE')],
                            }))
                          }
                        >
                          Add note
                        </button>
                        <button
                          type="button"
                          className="settings-button settings-button--ghost"
                          onClick={() =>
                            setEditorDraft((current) => ({
                              ...current,
                              references: [
                                ...current.references,
                                createBlankReference('STRING_USAGE'),
                              ],
                            }))
                          }
                        >
                          Add usage
                        </button>
                        <button
                          type="button"
                          className="settings-button settings-button--ghost"
                          onClick={() =>
                            setEditorDraft((current) => ({
                              ...current,
                              references: [...current.references, createBlankReference('CODE_REF')],
                            }))
                          }
                        >
                          Add code reference
                        </button>
                        <button
                          type="button"
                          className="settings-button settings-button--ghost"
                          onClick={() => referenceScreenshotInputRef.current?.click()}
                        >
                          Attach screenshot
                        </button>
                      </div>
                    ) : null}
                  </div>
                  {canEditProposalDraft ? (
                    <>
                      <input
                        ref={referenceScreenshotInputRef}
                        type="file"
                        accept="image/*"
                        multiple
                        className="glossary-term-admin__hidden-file-input"
                        onChange={(event) => {
                          void handleReferenceScreenshotFiles(event.currentTarget.files);
                          event.currentTarget.value = '';
                        }}
                      />
                      {uploadQueue.length > 0 ? (
                        <div className="glossary-term-admin__reference-upload-list">
                          {uploadQueue.map((item) => (
                            <div
                              key={item.key}
                              className={`glossary-term-admin__reference-upload glossary-term-admin__reference-upload--${item.status}`}
                            >
                              <span>{item.name}</span>
                              <span>
                                {item.status === 'error'
                                  ? item.error || 'Upload failed'
                                  : 'Uploading screenshot...'}
                              </span>
                            </div>
                          ))}
                        </div>
                      ) : null}
                    </>
                  ) : null}
                  {editorDraft.references.length === 0 ? (
                    <p className="settings-hint">No references yet.</p>
                  ) : (
                    <div className="glossary-term-admin__reference-list">
                      {editorDraft.references.map((reference) => (
                        <div key={reference.id} className="glossary-term-admin__reference-card">
                          <div className="glossary-term-admin__reference-header">
                            <div className="glossary-term-admin__reference-type">
                              <select
                                className="settings-input"
                                value={reference.referenceType}
                                disabled={!canEditProposalDraft || Boolean(reference.imageKey)}
                                onChange={(event) => {
                                  const nextReferenceType = event.target.value as ReferenceType;
                                  setEditorDraft((current) => ({
                                    ...current,
                                    references: current.references.map((item) =>
                                      item.id === reference.id
                                        ? {
                                            ...item,
                                            referenceType: nextReferenceType,
                                            tmTextUnitId:
                                              nextReferenceType === 'STRING_USAGE'
                                                ? item.tmTextUnitId
                                                : '',
                                          }
                                        : item,
                                    ),
                                  }));
                                }}
                              >
                                {REFERENCE_TYPES.filter(
                                  (referenceType) =>
                                    referenceType !== 'SCREENSHOT' || Boolean(reference.imageKey),
                                ).map((referenceType) => (
                                  <option key={referenceType} value={referenceType}>
                                    {REFERENCE_TYPE_LABELS[referenceType]}
                                  </option>
                                ))}
                              </select>
                              {reference.imageKey ? (
                                <span className="settings-hint">Attached screenshot</span>
                              ) : null}
                            </div>
                            <button
                              type="button"
                              className="settings-button settings-button--ghost"
                              disabled={!canEditProposalDraft}
                              onClick={() =>
                                setEditorDraft((current) => ({
                                  ...current,
                                  references: current.references.filter(
                                    (item) => item.id !== reference.id,
                                  ),
                                }))
                              }
                            >
                              Remove
                            </button>
                          </div>
                          {reference.imageKey ? (
                            <div className="glossary-term-admin__reference-preview">
                              <img
                                src={resolveAttachmentUrl(reference.imageKey)}
                                alt={reference.caption || 'Reference screenshot'}
                              />
                            </div>
                          ) : null}
                          <textarea
                            className="settings-input"
                            placeholder={getReferencePlaceholder(reference.referenceType)}
                            value={reference.caption}
                            disabled={!canEditProposalDraft}
                            onChange={(event) =>
                              setEditorDraft((current) => ({
                                ...current,
                                references: current.references.map((item) =>
                                  item.id === reference.id
                                    ? { ...item, caption: event.target.value }
                                    : item,
                                ),
                              }))
                            }
                          />
                          {reference.referenceType === 'STRING_USAGE' ? (
                            <input
                              type="text"
                              className="settings-input"
                              placeholder="Referenced TM text unit id"
                              value={reference.tmTextUnitId}
                              disabled={!canEditProposalDraft}
                              onChange={(event) =>
                                setEditorDraft((current) => ({
                                  ...current,
                                  references: current.references.map((item) =>
                                    item.id === reference.id
                                      ? { ...item, tmTextUnitId: event.target.value }
                                      : item,
                                  ),
                                }))
                              }
                            />
                          ) : null}
                        </div>
                      ))}
                    </div>
                  )}
                </section>

                <div className="glossary-term-admin__editor-page-footer">
                  <button
                    type="button"
                    className="settings-button settings-button--ghost"
                    onClick={closeEditor}
                    disabled={saveTermMutation.isPending}
                  >
                    {returnToExtractAfterEditor ? 'Back to candidates' : 'Cancel'}
                  </button>
                  <div className="glossary-term-admin__editor-page-actions">
                    {editorWorkbenchState ? (
                      <Link
                        to="/workbench"
                        state={editorWorkbenchState}
                        className="settings-button settings-button--ghost"
                      >
                        Open in Workbench
                      </Link>
                    ) : null}
                    <button
                      type="button"
                      className="settings-button settings-button--primary"
                      onClick={() => saveTermMutation.mutate(editorDraft)}
                      disabled={saveTermMutation.isPending || !editorDraft.source.trim()}
                    >
                      {saveTermMutation.isPending
                        ? canManageTerms
                          ? 'Saving…'
                          : 'Submitting…'
                        : canManageTerms
                          ? 'Save term'
                          : 'Submit candidate'}
                    </button>
                  </div>
                </div>
              </div>
            </div>
          ) : (
            <div className="glossary-term-admin__detail-placeholder">
              <h3 className="glossary-term-admin__detail-placeholder-title">
                {detailPlaceholder.title}
              </h3>
              <p className="glossary-term-admin__detail-placeholder-body">
                {detailPlaceholder.message}
              </p>
            </div>
          )}
        </div>
      </div>

      <Modal
        open={batchOpen}
        size="lg"
        ariaLabel="Batch update glossary terms"
        onClose={() => setBatchOpen(false)}
        closeOnBackdrop
      >
        <div className="modal__header">
          <div>
            <h3 className="modal__title">Batch edit terms</h3>
            <p className="settings-hint">
              Apply the same metadata change to {selectedTerms.length} selected glossary terms.
            </p>
          </div>
        </div>
        <div className="settings-grid settings-grid--two-column">
          <div className="settings-field">
            <label className="settings-field__label">Term type</label>
            <select
              className="settings-input"
              value={batchDraft.termType}
              onChange={(event) =>
                setBatchDraft((current) => ({ ...current, termType: event.target.value }))
              }
            >
              <option value="">No change</option>
              {TERM_TYPES.map((termType) => (
                <option key={termType} value={termType}>
                  {termType}
                </option>
              ))}
            </select>
          </div>
          <div className="settings-field">
            <label className="settings-field__label">Enforcement</label>
            <select
              className="settings-input"
              value={batchDraft.enforcement}
              onChange={(event) =>
                setBatchDraft((current) => ({ ...current, enforcement: event.target.value }))
              }
            >
              <option value="">No change</option>
              {ENFORCEMENTS.map((enforcement) => (
                <option key={enforcement} value={enforcement}>
                  {enforcement}
                </option>
              ))}
            </select>
          </div>
          <div className="settings-field">
            <label className="settings-field__label">Status</label>
            <select
              className="settings-input"
              value={batchDraft.status}
              onChange={(event) =>
                setBatchDraft((current) => ({ ...current, status: event.target.value }))
              }
            >
              <option value="">No change</option>
              {STATUSES.map((status) => (
                <option key={status} value={status}>
                  {status}
                </option>
              ))}
            </select>
          </div>
          <div className="settings-field">
            <label className="settings-field__label">Provenance</label>
            <select
              className="settings-input"
              value={batchDraft.provenance}
              onChange={(event) =>
                setBatchDraft((current) => ({ ...current, provenance: event.target.value }))
              }
            >
              <option value="">No change</option>
              {PROVENANCES.map((provenance) => (
                <option key={provenance} value={provenance}>
                  {provenance}
                </option>
              ))}
            </select>
          </div>
          <div className="settings-field">
            <label className="settings-field__label">Part of speech</label>
            <input
              type="text"
              className="settings-input"
              placeholder="No change"
              value={batchDraft.partOfSpeech}
              onChange={(event) =>
                setBatchDraft((current) => ({ ...current, partOfSpeech: event.target.value }))
              }
            />
          </div>
          <div className="settings-field">
            <label className="settings-field__label">Case-sensitive</label>
            <select
              className="settings-input"
              value={batchDraft.caseSensitive}
              onChange={(event) =>
                setBatchDraft((current) => ({
                  ...current,
                  caseSensitive: event.target.value as BatchDraft['caseSensitive'],
                }))
              }
            >
              <option value="">No change</option>
              <option value="true">Set true</option>
              <option value="false">Set false</option>
            </select>
          </div>
          <div className="settings-field">
            <label className="settings-field__label">Do not translate</label>
            <select
              className="settings-input"
              value={batchDraft.doNotTranslate}
              onChange={(event) =>
                setBatchDraft((current) => ({
                  ...current,
                  doNotTranslate: event.target.value as BatchDraft['doNotTranslate'],
                }))
              }
            >
              <option value="">No change</option>
              <option value="true">Set true</option>
              <option value="false">Set false</option>
            </select>
          </div>
        </div>
        <div className="modal__actions">
          <button type="button" className="modal__button" onClick={() => setBatchOpen(false)}>
            Cancel
          </button>
          <button
            type="button"
            className="modal__button modal__button--primary"
            onClick={() => batchMutation.mutate()}
            disabled={batchMutation.isPending || selectedTermIds.length === 0}
          >
            {batchMutation.isPending ? 'Updating…' : 'Apply'}
          </button>
        </div>
      </Modal>
    </section>
  );
}
