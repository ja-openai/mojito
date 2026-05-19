import './admin-glossary-terms-panel.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  type ComponentProps,
  type CSSProperties,
  type MouseEvent as ReactMouseEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { Link, useNavigate } from 'react-router-dom';

import {
  acceptGlossaryTermIndexSuggestion,
  type ApiAcceptGlossaryTermIndexSuggestionRequest,
  type ApiGlossaryDetail,
  type ApiGlossaryTerm,
  type ApiGlossaryTermEvidence,
  type ApiGlossaryTermIndexSuggestion,
  type ApiGlossaryTermIndexSuggestionGlossaryPresenceFilter,
  type ApiGlossaryTermIndexSuggestionReviewStatusFilter,
  type ApiGlossaryTermSearchField,
  type ApiUpsertGlossaryTermRequest,
  batchUpdateGlossaryTerms,
  createGlossaryTerm,
  deleteGlossaryTerm,
  fetchGlossaryTerm,
  fetchGlossaryTermIndexSuggestions,
  fetchGlossaryTerms,
  ignoreGlossaryTermIndexSuggestion,
  updateGlossaryTerm,
} from '../../api/glossaries';
import {
  createGlossaryTermCandidateReviewProjectRequest,
  createGlossaryTerminologyReviewProjectRequest,
} from '../../api/review-projects';
import { type ApiTeamUserSummary, fetchTeams, fetchTeamUsersByRole } from '../../api/teams';
import { AutoTextarea } from '../../components/AutoTextarea';
import { ConfirmModal } from '../../components/ConfirmModal';
import { Modal } from '../../components/Modal';
import { MultiSelectChip } from '../../components/MultiSelectChip';
import { PillDropdown } from '../../components/PillDropdown';
import { useUser } from '../../components/RequireUser';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import {
  formatLocalDateTime,
  getLocalAndUtcDateTimeTooltip,
  localDateTimeInputToIso,
  toDateTimeLocalInputValue,
} from '../../utils/dateTime';
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
import { getUserLabel } from '../../utils/userDisplayName';
import { GlossaryCurationView, GlossarySuggestionDetailView } from './GlossaryCurationView';
import { GlossaryTermsListControls, GlossaryTermsListView } from './GlossaryTermsListView';

const TERM_TYPES = ['BRAND', 'PRODUCT', 'UI_LABEL', 'LEGAL', 'TECHNICAL', 'GENERAL'] as const;
const ENFORCEMENTS = ['HARD', 'SOFT', 'REVIEW_ONLY'] as const;
const STATUSES = ['CANDIDATE', 'APPROVED', 'DEPRECATED', 'REJECTED'] as const;
const TERM_TYPE_LABELS: Record<(typeof TERM_TYPES)[number], string> = {
  BRAND: 'Brand',
  PRODUCT: 'Product',
  UI_LABEL: 'UI label',
  LEGAL: 'Legal',
  TECHNICAL: 'Technical',
  GENERAL: 'General',
};
const ENFORCEMENT_LABELS: Record<(typeof ENFORCEMENTS)[number], string> = {
  HARD: 'Hard',
  SOFT: 'Soft',
  REVIEW_ONLY: 'Review only',
};
const STATUS_LABELS: Record<(typeof STATUSES)[number], string> = {
  CANDIDATE: 'Candidate',
  APPROVED: 'Approved',
  DEPRECATED: 'Deprecated',
  REJECTED: 'Rejected',
};
const ACTIVE_STATUS_FILTER = 'ACTIVE';
const ALL_STATUS_FILTER = 'ALL';
const PROVENANCES = ['MANUAL', 'IMPORTED', 'AUTOMATED', 'AI_EXTRACTED'] as const;
const PROVENANCE_LABELS: Record<(typeof PROVENANCES)[number], string> = {
  MANUAL: 'Manual',
  IMPORTED: 'Imported',
  AUTOMATED: 'Automated',
  AI_EXTRACTED: 'AI extracted',
};
type ReferenceType = ApiGlossaryTermEvidence['evidenceType'];
type SuggestionPendingAction = 'ACCEPT' | 'IGNORE';
type TerminologyReviewKind = 'TERMINOLOGY' | 'TERM_CANDIDATE';
type TerminologyReviewScope = 'glossary' | 'selected';
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
  { value: ACTIVE_STATUS_FILTER, label: 'Active' },
  { value: ALL_STATUS_FILTER, label: 'All statuses' },
  { value: 'CANDIDATE', label: 'Candidates' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'DEPRECATED', label: 'Deprecated' },
  { value: 'REJECTED', label: 'Rejected' },
] as const;
const DEFAULT_TERM_SEARCH_FIELD: ApiGlossaryTermSearchField = 'SOURCE';
const TERM_SEARCH_FIELD_OPTIONS: Array<{
  value: ApiGlossaryTermSearchField;
  label: string;
}> = [
  { value: 'SOURCE', label: 'Source' },
  { value: 'TARGET', label: 'Target' },
  { value: 'DEFINITION', label: 'Definition' },
  { value: 'REFERENCES', label: 'References' },
  { value: 'ALL', label: 'All' },
];
const DEFAULT_SUGGESTION_LIMIT = 50;
const SUGGESTION_LIMIT_OPTIONS = [
  { value: 25, label: '25' },
  { value: 50, label: '50' },
  { value: 100, label: '100' },
  { value: 200, label: '200' },
  { value: 500, label: '500' },
  { value: 1000, label: '1k' },
  { value: 10000, label: '10k' },
];
const DEFAULT_VISIBLE_LOCALE_COLUMNS = 3;
const AUTO_VISIBLE_LOCALE_COLUMNS_CAP = 5;
const TERMINOLOGY_REVIEW_DUE_DATE_OFFSET_DAYS = 2;
const DEFAULT_TERMS_LIMIT = 200;
const MAX_TERMS_LIMIT = 1000;
const TERMS_LIMIT_PRESETS = [50, 100, 200, 500, 1000];
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
  searchField: ApiGlossaryTermSearchField;
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
  onClearInitialOpenTerm?: () => void;
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
  createdDate?: string | null;
  lastModifiedDate?: string | null;
  termIndexCandidateId?: number | null;
  termIndexExtractedTermId?: number | null;
  termIndexOccurrenceCount?: number | null;
  termIndexRepositoryCount?: number | null;
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

type SaveTermVariables = {
  draft: TermDraft;
  replaceTerm?: boolean;
  copyTranslationsOnReplace?: boolean | null;
  copyTranslationStatus?: CopyTranslationStatus | null;
};

type CopyTranslationStatus = NonNullable<ApiUpsertGlossaryTermRequest['copyTranslationStatus']>;

type BatchDraft = {
  termType: string;
  enforcement: string;
  status: string;
  provenance: string;
  partOfSpeech: string;
  caseSensitive: '' | 'true' | 'false';
  doNotTranslate: '' | 'true' | 'false';
};

type TerminologyReviewDraft = {
  kind: TerminologyReviewKind;
  scope: TerminologyReviewScope;
  teamId: number | null;
  tmTextUnitIds: number[];
  termIndexCandidateIds: number[];
  specialistUserIds: number[];
  pmUserId: number | null;
  specialistDueDate: string;
  pmDueDate: string;
};

const createClientId = () =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto && crypto.randomUUID
    ? crypto.randomUUID()
    : Math.random().toString(36).slice(2);

const getRawTermExplorerPath = (entryId: number, search: string) => {
  const params = new URLSearchParams({ entryId: String(entryId) });
  const trimmedSearch = search.trim();
  if (trimmedSearch) {
    params.set('search', trimmedSearch);
  }
  return `/settings/system/glossary-term-index/terms?${params.toString()}`;
};

const getGlossaryTermWorkspacePath = (glossaryId: number, tmTextUnitId: number) =>
  `/glossaries/${glossaryId}/terms/${tmTextUnitId}`;

const formatCountLabel = (count: number, singularLabel: string, pluralLabel: string) =>
  `${count.toLocaleString()} ${count === 1 ? singularLabel : pluralLabel}`;

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
  createdDate: null,
  lastModifiedDate: null,
  termIndexCandidateId: null,
  termIndexExtractedTermId: null,
  termIndexOccurrenceCount: null,
  termIndexRepositoryCount: null,
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
    createdDate: term.createdDate ?? null,
    lastModifiedDate: term.lastModifiedDate ?? null,
    termIndexCandidateId: term.termIndexCandidateId ?? null,
    termIndexExtractedTermId: term.termIndexExtractedTermId ?? null,
    termIndexOccurrenceCount: term.termIndexOccurrenceCount ?? null,
    termIndexRepositoryCount: term.termIndexRepositoryCount ?? null,
    termKey: term.termKey,
    source: term.source,
    sourceComment: term.sourceComment ?? term.definition ?? '',
    definition: term.definition ?? term.sourceComment ?? '',
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

const normalizeBackingField = (value: string) => value.trim();

const getReplacementBackingFieldLabels = (original: TermDraft | null, draft: TermDraft) => {
  if (!original || draft.tmTextUnitId == null) {
    return [];
  }

  const labels: string[] = [];
  if (normalizeBackingField(original.termKey) !== normalizeBackingField(draft.termKey)) {
    labels.push('term key');
  }
  if (normalizeBackingField(original.source) !== normalizeBackingField(draft.source)) {
    labels.push('source term');
  }
  if (normalizeBackingField(original.definition) !== normalizeBackingField(draft.definition)) {
    labels.push('definition');
  }
  return labels;
};

const draftToRequest = (
  draft: TermDraft,
  {
    includeTranslations = true,
    translationLocaleTags = null,
    replaceTerm = false,
    copyTranslationsOnReplace = null,
    copyTranslationStatus = null,
  }: {
    includeTranslations?: boolean;
    translationLocaleTags?: string[] | null;
    replaceTerm?: boolean;
    copyTranslationsOnReplace?: boolean | null;
    copyTranslationStatus?: CopyTranslationStatus | null;
  } = {},
) => {
  const request = {
    termKey: draft.tmTextUnitId == null ? draft.termKey.trim() || null : draft.termKey || null,
    source: draft.source.trim(),
    sourceComment: draft.definition.trim() || null,
    definition: null,
    partOfSpeech: draft.partOfSpeech.trim() || null,
    termType: draft.termType || null,
    enforcement: draft.enforcement || null,
    status: draft.status || null,
    provenance: draft.provenance || null,
    caseSensitive: draft.caseSensitive,
    doNotTranslate: draft.doNotTranslate,
    replaceTerm,
    copyTranslationsOnReplace,
    copyTranslationStatus,
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

  const translationLocaleKeys =
    translationLocaleTags == null
      ? null
      : new Set(translationLocaleTags.map((localeTag) => localeTag.trim().toLowerCase()));
  const translations = draft.translations.filter(
    (translation) =>
      translationLocaleKeys == null ||
      translationLocaleKeys.has(translation.localeTag.trim().toLowerCase()),
  );

  return {
    ...request,
    translations: translations
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
    return ACTIVE_STATUS_FILTER;
  }
  return STATUS_FILTER_OPTIONS.some((option) => option.value === value)
    ? value
    : ACTIVE_STATUS_FILTER;
}

function normalizePersistedSearchField(value: unknown): ApiGlossaryTermSearchField {
  if (typeof value !== 'string') {
    return DEFAULT_TERM_SEARCH_FIELD;
  }
  return TERM_SEARCH_FIELD_OPTIONS.some((option) => option.value === value)
    ? (value as ApiGlossaryTermSearchField)
    : DEFAULT_TERM_SEARCH_FIELD;
}

function normalizePersistedPositiveNumber(value: unknown, fallback: number) {
  const next = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(next) && next >= 1 ? Math.round(next) : fallback;
}

function normalizeTermsLimit(value: unknown) {
  return Math.min(MAX_TERMS_LIMIT, normalizePersistedPositiveNumber(value, DEFAULT_TERMS_LIMIT));
}

function normalizeSuggestionLimit(value: unknown) {
  return normalizePersistedPositiveNumber(value, DEFAULT_SUGGESTION_LIMIT);
}

function getDefaultTerminologyReviewDueDateInput(
  offsetDays = TERMINOLOGY_REVIEW_DUE_DATE_OFFSET_DAYS,
) {
  return toDateTimeLocalInputValue(new Date(Date.now() + offsetDays * 24 * 60 * 60 * 1000));
}

function toTeamUserSelectOption(user: ApiTeamUserSummary) {
  return {
    value: user.id,
    label: getUserLabel(user) || `User #${user.id}`,
  };
}

function formatShortCount(value: number) {
  return value >= 1000 && value % 1000 === 0 ? `${value / 1000}k` : value.toLocaleString();
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
      searchField: normalizePersistedSearchField(parsed.searchField),
      selectedLocaleTags: dedupeLocaleTags(
        Array.isArray(parsed.selectedLocaleTags)
          ? parsed.selectedLocaleTags.filter(
              (localeTag): localeTag is string => typeof localeTag === 'string',
            )
          : [],
      ),
      selectedStatusFilter: normalizePersistedStatusFilter(parsed.selectedStatusFilter),
      termsLimit: normalizeTermsLimit(parsed.termsLimit),
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

const suggestionToAcceptRequest = (
  suggestion: ApiGlossaryTermIndexSuggestion,
): ApiAcceptGlossaryTermIndexSuggestionRequest => ({
  source: suggestion.term,
  definition: suggestion.definition ?? null,
  partOfSpeech: suggestion.suggestedPartOfSpeech || null,
  termType: suggestion.suggestedTermType || 'GENERAL',
  enforcement: suggestion.suggestedEnforcement || 'SOFT',
  status: 'CANDIDATE',
  caseSensitive: false,
  doNotTranslate: suggestion.suggestedDoNotTranslate,
  confidence: suggestion.confidence,
  rationale: suggestion.rationale ?? null,
  evidence: [
    ...(suggestion.rationale
      ? [
          {
            evidenceType: 'NOTE' as const,
            caption: suggestion.rationale,
          },
        ]
      : []),
    ...suggestion.examples.slice(0, 3).map((example) => ({
      evidenceType: 'STRING_USAGE' as const,
      caption: `Observed in ${example.repositoryName}: ${example.sourceText ?? ''}`.slice(0, 1024),
      tmTextUnitId: example.tmTextUnitId,
    })),
  ],
});

const canAcceptSuggestion = (suggestion: ApiGlossaryTermIndexSuggestion) =>
  suggestion.glossaryPresence === 'NOT_IN_GLOSSARY';

const suggestionToDraft = (
  suggestion: ApiGlossaryTermIndexSuggestion,
  localeTags: string[],
): TermDraft => {
  const draft = createBlankDraft(localeTags);
  draft.termIndexCandidateId = suggestion.termIndexCandidateId;
  draft.termIndexExtractedTermId = suggestion.termIndexExtractedTermId ?? null;
  draft.termIndexOccurrenceCount = suggestion.occurrenceCount ?? null;
  draft.termIndexRepositoryCount = suggestion.repositoryCount ?? null;
  draft.source = suggestion.term;
  draft.definition = suggestion.definition ?? suggestion.rationale ?? '';
  draft.sourceComment = draft.definition;
  draft.partOfSpeech = suggestion.suggestedPartOfSpeech ?? '';
  draft.termType = suggestion.suggestedTermType || 'GENERAL';
  draft.enforcement = suggestion.suggestedEnforcement || 'SOFT';
  draft.status = 'CANDIDATE';
  draft.provenance = suggestion.suggestedProvenance || 'AI_EXTRACTED';
  draft.caseSensitive = false;
  draft.doNotTranslate = suggestion.suggestedDoNotTranslate;
  draft.references = [
    ...(suggestion.rationale
      ? [
          {
            ...createBlankReference('NOTE'),
            caption: suggestion.rationale,
          },
        ]
      : []),
    ...suggestion.examples.slice(0, 3).map((example) => ({
      ...createBlankReference('STRING_USAGE'),
      caption: `Observed in ${example.repositoryName}: ${example.sourceText ?? ''}`.slice(0, 1024),
      tmTextUnitId: String(example.tmTextUnitId),
    })),
  ];
  return draft;
};

const draftToAcceptSuggestionRequest = (
  draft: TermDraft,
  suggestion: ApiGlossaryTermIndexSuggestion,
): ApiAcceptGlossaryTermIndexSuggestionRequest => ({
  termKey: draft.termKey.trim() || null,
  source: draft.source.trim() || suggestion.term,
  definition: draft.definition.trim() || null,
  partOfSpeech: draft.partOfSpeech.trim() || null,
  termType: draft.termType || suggestion.suggestedTermType || 'GENERAL',
  enforcement: draft.enforcement || suggestion.suggestedEnforcement || 'SOFT',
  status: draft.status || 'CANDIDATE',
  caseSensitive: draft.caseSensitive,
  doNotTranslate: draft.doNotTranslate,
  confidence: suggestion.confidence,
  rationale: suggestion.rationale ?? null,
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
        Boolean(reference.imageKey) || Boolean(reference.caption) || reference.tmTextUnitId != null,
    ),
});

export function AdminGlossaryTermsPanel({
  glossary,
  initialOpenTermId = null,
  onClearInitialOpenTerm,
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
  const navigate = useNavigate();
  const resolveLocaleDisplayName = useLocaleDisplayNameResolver();
  const previewUrlsRef = useRef<Set<string>>(new Set());
  const workspaceLayoutRef = useRef<HTMLDivElement | null>(null);
  const referenceScreenshotInputRef = useRef<HTMLInputElement | null>(null);
  const initialSelectedLocaleTags = persistedWorkspacePrefs?.selectedLocaleTags ?? [];
  const [searchDraft, setSearchDraft] = useState(persistedWorkspacePrefs?.searchDraft ?? '');
  const [searchField, setSearchField] = useState<ApiGlossaryTermSearchField>(
    persistedWorkspacePrefs?.searchField ?? DEFAULT_TERM_SEARCH_FIELD,
  );
  const [selectedLocaleTags, setSelectedLocaleTags] = useState<string[]>(initialSelectedLocaleTags);
  const [selectedStatusFilter, setSelectedStatusFilter] = useState<string | null>(
    persistedWorkspacePrefs?.selectedStatusFilter ?? ACTIVE_STATUS_FILTER,
  );
  const [selectedTermIds, setSelectedTermIds] = useState<number[]>([]);
  const [openStatusTermId, setOpenStatusTermId] = useState<number | null>(null);
  const [termsLimit, setTermsLimit] = useState(
    String(normalizeTermsLimit(persistedWorkspacePrefs?.termsLimit)),
  );
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
  const [originalEditorDraft, setOriginalEditorDraft] = useState<TermDraft | null>(null);
  const [termPendingDelete, setTermPendingDelete] = useState<TermDraft | null>(null);
  const [termPendingReplace, setTermPendingReplace] = useState<TermDraft | null>(null);
  const [replaceCopyTranslations, setReplaceCopyTranslations] = useState(true);
  const [replaceCopyTranslationStatus, setReplaceCopyTranslationStatus] =
    useState<CopyTranslationStatus>('KEEP_CURRENT');
  const [batchOpen, setBatchOpen] = useState(false);
  const [extractOpen, setExtractOpen] = useState(false);
  const [terminologyReviewDraft, setTerminologyReviewDraft] =
    useState<TerminologyReviewDraft | null>(null);
  const [terminologyReviewDeciderShowAllUsers, setTerminologyReviewDeciderShowAllUsers] =
    useState(false);
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
  const [suggestionSearchDraft, setSuggestionSearchDraft] = useState('');
  const [suggestionSearchQuery, setSuggestionSearchQuery] = useState('');
  const [suggestionLimit, setSuggestionLimit] = useState(String(DEFAULT_SUGGESTION_LIMIT));
  const [suggestionReviewStatusFilter, setSuggestionReviewStatusFilter] =
    useState<ApiGlossaryTermIndexSuggestionReviewStatusFilter>('NEW');
  const [suggestionGlossaryPresenceFilter, setSuggestionGlossaryPresenceFilter] =
    useState<ApiGlossaryTermIndexSuggestionGlossaryPresenceFilter>('NOT_IN_GLOSSARY');
  const [suggestionSearchNonce, setSuggestionSearchNonce] = useState(0);
  const [selectedSuggestionIds, setSelectedSuggestionIds] = useState<number[]>([]);
  const [activeSuggestionId, setActiveSuggestionId] = useState<number | null>(null);
  const [pendingSuggestionActions, setPendingSuggestionActions] = useState<
    Record<number, SuggestionPendingAction>
  >({});
  const [returnToExtractAfterEditor, setReturnToExtractAfterEditor] = useState(false);
  const [suggestionInEditor, setSuggestionInEditor] =
    useState<ApiGlossaryTermIndexSuggestion | null>(null);
  const [uploadQueue, setUploadQueue] = useState<RequestAttachmentUploadQueueItem[]>([]);
  const optimizeImagesBeforeUpload = true;
  const openedInitialTermIdRef = useRef<number | null>(null);
  const handledBackRequestRef = useRef(backRequestNonce);
  const expandedPrimaryPaneWidthPctRef = useRef(
    persistedWorkspacePrefs?.primaryPaneWidthPct ?? DEFAULT_PRIMARY_PANE_WIDTH_PCT,
  );

  const terminologyReviewTeamId = terminologyReviewDraft?.teamId ?? null;
  const terminologyReviewTeamsQuery = useQuery({
    queryKey: ['teams', 'terminology-review'],
    queryFn: fetchTeams,
    enabled: canManageTerms && terminologyReviewDraft != null,
    staleTime: 30_000,
  });
  const terminologyReviewSpecialistsQuery = useQuery({
    queryKey: ['team-users', terminologyReviewTeamId, 'TRANSLATOR', 'terminology-review'],
    queryFn: () => fetchTeamUsersByRole(terminologyReviewTeamId as number, 'TRANSLATOR'),
    enabled: canManageTerms && terminologyReviewDraft != null && terminologyReviewTeamId != null,
    staleTime: 30_000,
  });
  const terminologyReviewPmUsersQuery = useQuery({
    queryKey: ['team-users', terminologyReviewTeamId, 'PM', 'terminology-review'],
    queryFn: () => fetchTeamUsersByRole(terminologyReviewTeamId as number, 'PM'),
    enabled: canManageTerms && terminologyReviewDraft != null && terminologyReviewTeamId != null,
    staleTime: 30_000,
  });
  const hydratedWorkspacePrefsGlossaryIdRef = useRef<number | null>(null);

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
  useEffect(() => {
    const nextPrefs = loadGlossaryWorkspacePrefs(glossary.id);
    const nextSelectedLocaleTags = nextPrefs?.selectedLocaleTags ?? [];
    setSearchDraft(nextPrefs?.searchDraft ?? '');
    setSearchField(nextPrefs?.searchField ?? DEFAULT_TERM_SEARCH_FIELD);
    setSelectedLocaleTags(nextSelectedLocaleTags);
    setSelectedStatusFilter(nextPrefs?.selectedStatusFilter ?? ACTIVE_STATUS_FILTER);
    setTermsLimit(String(normalizeTermsLimit(nextPrefs?.termsLimit)));
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
      searchField,
      selectedLocaleTags,
      selectedStatusFilter,
      termsLimit: normalizeTermsLimit(termsLimit),
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
    searchField,
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

  const termsLimitNumber = normalizeTermsLimit(termsLimit);
  const suggestionLimitNumber = normalizeSuggestionLimit(suggestionLimit);

  const termsQuery = useQuery({
    queryKey: [
      'glossary-terms',
      glossary.id,
      searchDraft,
      searchField,
      selectedLocaleTags.join('|'),
      termsLimitNumber,
    ],
    queryFn: () =>
      fetchGlossaryTerms(glossary.id, {
        search: searchDraft,
        searchField,
        localeTags: selectedLocaleTags,
        limit: termsLimitNumber,
      }),
    staleTime: 15_000,
  });

  const suggestionsQuery = useQuery({
    queryKey: [
      'glossary-term-index-suggestions',
      glossary.id,
      suggestionSearchQuery,
      suggestionLimitNumber,
      suggestionReviewStatusFilter,
      suggestionGlossaryPresenceFilter,
      suggestionSearchNonce,
    ],
    queryFn: () =>
      fetchGlossaryTermIndexSuggestions(glossary.id, {
        search: suggestionSearchQuery,
        limit: suggestionLimitNumber,
        useAi: false,
        reviewStatusFilter: suggestionReviewStatusFilter,
        glossaryPresenceFilter: suggestionGlossaryPresenceFilter,
      }),
    enabled: extractOpen && suggestionSearchNonce > 0,
    staleTime: 0,
  });

  useEffect(() => {
    if (!extractOpen) {
      return;
    }

    const nextSearchQuery = suggestionSearchDraft.trim();
    if (suggestionSearchNonce > 0 && nextSearchQuery === suggestionSearchQuery) {
      return;
    }

    const searchTimer = window.setTimeout(() => {
      setSelectedSuggestionIds([]);
      setSuggestionSearchQuery(nextSearchQuery);
      setSuggestionSearchNonce((current) => (current === 0 ? 1 : current));
    }, 250);

    return () => window.clearTimeout(searchTimer);
  }, [extractOpen, suggestionSearchDraft, suggestionSearchNonce, suggestionSearchQuery]);

  const saveTermMutation = useMutation({
    mutationFn: async ({
      draft,
      replaceTerm = false,
      copyTranslationsOnReplace = null,
      copyTranslationStatus = null,
    }: SaveTermVariables) => {
      const request = draftToRequest(draft, {
        includeTranslations: draft.tmTextUnitId == null,
        translationLocaleTags: selectedLocaleTags,
        replaceTerm,
        copyTranslationsOnReplace,
        copyTranslationStatus,
      });
      if (draft.tmTextUnitId == null && suggestionInEditor) {
        return acceptGlossaryTermIndexSuggestion(
          glossary.id,
          suggestionInEditor.termIndexCandidateId,
          draftToAcceptSuggestionRequest(draft, suggestionInEditor),
        );
      }
      return draft.tmTextUnitId == null
        ? createGlossaryTerm(glossary.id, request)
        : updateGlossaryTerm(glossary.id, draft.tmTextUnitId, request);
    },
    onSuccess: async (savedTerm) => {
      await queryClient.invalidateQueries({ queryKey: ['glossary-terms', glossary.id] });
      await queryClient.invalidateQueries({
        queryKey: ['glossary-term-index-suggestions', glossary.id],
      });
      setEditorOpen(false);
      setUploadQueue([]);
      setOriginalEditorDraft(null);
      setTermPendingReplace(null);
      if (returnToExtractAfterEditor) {
        setExtractOpen(true);
      }
      setReturnToExtractAfterEditor(false);
      setSuggestionInEditor(null);
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

  const deleteTermMutation = useMutation({
    mutationFn: async (draft: TermDraft) => {
      if (draft.tmTextUnitId == null) {
        throw new Error('Cannot delete an unsaved glossary term.');
      }
      await deleteGlossaryTerm(glossary.id, draft.tmTextUnitId);
      return draft;
    },
    onSuccess: async (deletedTerm) => {
      await queryClient.invalidateQueries({ queryKey: ['glossary-terms', glossary.id] });
      setTermPendingDelete(null);
      setEditorOpen(false);
      setUploadQueue([]);
      setOriginalEditorDraft(null);
      setTermPendingReplace(null);
      setSelectedTermIds((current) =>
        deletedTerm.tmTextUnitId == null
          ? current
          : current.filter((id) => id !== deletedTerm.tmTextUnitId),
      );
      setStatusNotice({
        kind: 'success',
        message: `Deleted glossary term ${deletedTerm.source}.`,
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to delete glossary term.',
      });
    },
  });

  const inlineTranslationMutation = useMutation({
    mutationFn: async ({
      term,
      localeTag,
      target,
      targetComment,
    }: {
      term: ApiGlossaryTerm;
      localeTag: string;
      target: string;
      targetComment: string;
    }) => {
      const draft = termToDraft(term, glossary.localeTags);
      const existingTranslation = draft.translations.find(
        (translation) => translation.localeTag.toLowerCase() === localeTag.toLowerCase(),
      );
      if (existingTranslation) {
        existingTranslation.target = target;
        existingTranslation.targetComment = targetComment;
      } else {
        draft.translations.push({
          ...createBlankTranslation(localeTag),
          target,
          targetComment,
        });
      }
      return updateGlossaryTerm(
        glossary.id,
        term.tmTextUnitId,
        draftToRequest(draft, {
          includeTranslations: true,
          translationLocaleTags: [localeTag],
        }),
      );
    },
    onSuccess: async (savedTerm) => {
      await queryClient.invalidateQueries({ queryKey: ['glossary-terms', glossary.id] });
      setStatusNotice({
        kind: 'success',
        message: `Saved ${savedTerm.source} translation.`,
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to save glossary translation.',
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

  const terminologyReviewMutation = useMutation({
    mutationFn: (request: TerminologyReviewDraft) => {
      const hasAdvisors = request.specialistUserIds.length > 0;
      const specialistDueDate = hasAdvisors
        ? localDateTimeInputToIso(request.specialistDueDate)
        : null;
      const pmDueDate = localDateTimeInputToIso(request.pmDueDate);
      if (hasAdvisors && !specialistDueDate) {
        throw new Error('Advisor due date is required when advisors are selected.');
      }
      if (!pmDueDate) {
        throw new Error('Decider due date is required.');
      }
      if (request.kind === 'TERM_CANDIDATE') {
        return createGlossaryTermCandidateReviewProjectRequest(glossary.id, {
          name: `Term candidate review · ${glossary.name} · selected candidates`,
          notes: `Candidate curation review for selected term candidates in glossary ${glossary.name} (#${glossary.id}).`,
          dueDate: specialistDueDate ?? pmDueDate,
          teamId: request.teamId,
          specialistDueDate,
          pmDueDate,
          specialistUserIds:
            request.specialistUserIds.length > 0 ? request.specialistUserIds : null,
          pmUserId: request.pmUserId,
          assignTranslator: false,
          termIndexCandidateIds: request.termIndexCandidateIds,
        });
      }
      return createGlossaryTerminologyReviewProjectRequest(glossary.id, {
        name:
          request.scope === 'selected'
            ? `Terminology review · ${glossary.name} · selected terms`
            : `Terminology review · ${glossary.name}`,
        notes:
          request.scope === 'selected'
            ? `Source terminology review for selected terms in glossary ${glossary.name} (#${glossary.id}).`
            : `Source terminology review for glossary ${glossary.name} (#${glossary.id}).`,
        dueDate: specialistDueDate ?? pmDueDate,
        teamId: request.teamId,
        specialistDueDate,
        pmDueDate,
        specialistUserIds: request.specialistUserIds.length > 0 ? request.specialistUserIds : null,
        pmUserId: request.pmUserId,
        assignTranslator: false,
        tmTextUnitIds: request.scope === 'selected' ? request.tmTextUnitIds : null,
      });
    },
    onSuccess: (response) => {
      setTerminologyReviewDraft(null);
      const projectId = response.projectIds[0];
      if (projectId != null) {
        void navigate(`/review-projects/${projectId}`);
        return;
      }
      setStatusNotice({
        kind: 'error',
        message: 'No terminology review project was created for this glossary.',
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to create terminology review project.',
      });
    },
  });

  const openTerminologyReviewModal = useCallback(
    (scope: TerminologyReviewScope, tmTextUnitIds: number[]) => {
      terminologyReviewMutation.reset();
      setTerminologyReviewDeciderShowAllUsers(false);
      setTerminologyReviewDraft({
        kind: 'TERMINOLOGY',
        scope,
        teamId: null,
        tmTextUnitIds,
        termIndexCandidateIds: [],
        specialistUserIds: [],
        pmUserId: null,
        specialistDueDate: getDefaultTerminologyReviewDueDateInput(),
        pmDueDate: getDefaultTerminologyReviewDueDateInput(
          TERMINOLOGY_REVIEW_DUE_DATE_OFFSET_DAYS + 1,
        ),
      });
      setStatusNotice(null);
    },
    [terminologyReviewMutation],
  );

  const openTermCandidateReviewModal = useCallback(
    (termIndexCandidateIds: number[]) => {
      terminologyReviewMutation.reset();
      setTerminologyReviewDeciderShowAllUsers(false);
      setTerminologyReviewDraft({
        kind: 'TERM_CANDIDATE',
        scope: 'selected',
        teamId: null,
        tmTextUnitIds: [],
        termIndexCandidateIds,
        specialistUserIds: [],
        pmUserId: null,
        specialistDueDate: getDefaultTerminologyReviewDueDateInput(),
        pmDueDate: getDefaultTerminologyReviewDueDateInput(
          TERMINOLOGY_REVIEW_DUE_DATE_OFFSET_DAYS + 1,
        ),
      });
      setStatusNotice(null);
    },
    [terminologyReviewMutation],
  );

  const terminologyReviewTermCount =
    terminologyReviewDraft?.kind === 'TERM_CANDIDATE'
      ? terminologyReviewDraft.termIndexCandidateIds.length
      : terminologyReviewDraft?.scope === 'selected'
        ? terminologyReviewDraft.tmTextUnitIds.length
        : (termsQuery.data?.totalCount ?? 0);
  const canSubmitTerminologyReview =
    terminologyReviewDraft != null &&
    terminologyReviewDraft.teamId != null &&
    terminologyReviewTermCount > 0 &&
    (terminologyReviewDraft.specialistUserIds.length === 0 ||
      Boolean(localDateTimeInputToIso(terminologyReviewDraft.specialistDueDate))) &&
    Boolean(localDateTimeInputToIso(terminologyReviewDraft.pmDueDate)) &&
    !terminologyReviewMutation.isPending;
  const closeTerminologyReviewModal = useCallback(() => {
    if (terminologyReviewMutation.isPending) {
      return;
    }
    setTerminologyReviewDeciderShowAllUsers(false);
    setTerminologyReviewDraft(null);
  }, [terminologyReviewMutation.isPending]);

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

  const acceptSuggestionMutation = useMutation({
    mutationFn: async (suggestion: ApiGlossaryTermIndexSuggestion) =>
      acceptGlossaryTermIndexSuggestion(
        glossary.id,
        suggestion.termIndexCandidateId,
        suggestionToAcceptRequest(suggestion),
      ),
    onMutate: (suggestion) => {
      setPendingSuggestionActions((current) => ({
        ...current,
        [suggestion.termIndexCandidateId]: 'ACCEPT',
      }));
    },
    onSuccess: async (savedTerm, suggestion) => {
      await queryClient.invalidateQueries({ queryKey: ['glossary-terms', glossary.id] });
      await queryClient.invalidateQueries({
        queryKey: ['glossary-term-index-suggestions', glossary.id],
      });
      setSelectedSuggestionIds((current) =>
        current.filter((id) => id !== suggestion.termIndexCandidateId),
      );
      setStatusNotice({
        kind: 'success',
        message: `Accepted ${savedTerm.source} into glossary review.`,
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to accept glossary candidate.',
      });
    },
    onSettled: (_savedTerm, _error, suggestion) => {
      setPendingSuggestionActions((current) => {
        const next = { ...current };
        delete next[suggestion.termIndexCandidateId];
        return next;
      });
    },
  });

  const ignoreSuggestionMutation = useMutation({
    mutationFn: async (suggestion: ApiGlossaryTermIndexSuggestion) => {
      await ignoreGlossaryTermIndexSuggestion(glossary.id, suggestion.termIndexCandidateId, {
        reason: 'Ignored from glossary workspace.',
      });
      return suggestion;
    },
    onMutate: (suggestion) => {
      setPendingSuggestionActions((current) => ({
        ...current,
        [suggestion.termIndexCandidateId]: 'IGNORE',
      }));
    },
    onSuccess: async (suggestion) => {
      await queryClient.invalidateQueries({
        queryKey: ['glossary-term-index-suggestions', glossary.id],
      });
      setSelectedSuggestionIds((current) =>
        current.filter((id) => id !== suggestion.termIndexCandidateId),
      );
      setStatusNotice({
        kind: 'success',
        message: `Ignored ${suggestion.term}.`,
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to ignore glossary candidate.',
      });
    },
    onSettled: (_savedSuggestion, _error, suggestion) => {
      setPendingSuggestionActions((current) => {
        const next = { ...current };
        delete next[suggestion.termIndexCandidateId];
        return next;
      });
    },
  });

  const batchAcceptSuggestionsMutation = useMutation({
    mutationFn: async (suggestions: ApiGlossaryTermIndexSuggestion[]) => {
      const results = await Promise.allSettled(
        suggestions.map(async (suggestion) => ({
          suggestion,
          saved: await acceptGlossaryTermIndexSuggestion(
            glossary.id,
            suggestion.termIndexCandidateId,
            suggestionToAcceptRequest(suggestion),
          ),
        })),
      );

      const succeeded = results
        .filter(
          (
            result,
          ): result is PromiseFulfilledResult<{
            suggestion: ApiGlossaryTermIndexSuggestion;
            saved: ApiGlossaryTerm;
          }> => result.status === 'fulfilled',
        )
        .map((result) => result.value.suggestion.termIndexCandidateId);

      return {
        succeeded,
        failed: results.length - succeeded.length,
      };
    },
    onSuccess: async ({ succeeded, failed }) => {
      await queryClient.invalidateQueries({ queryKey: ['glossary-terms', glossary.id] });
      await queryClient.invalidateQueries({
        queryKey: ['glossary-term-index-suggestions', glossary.id],
      });
      setSelectedSuggestionIds((current) => current.filter((id) => !succeeded.includes(id)));
      setStatusNotice({
        kind: failed > 0 ? 'error' : 'success',
        message:
          failed > 0
            ? `Accepted ${succeeded.length} candidates. ${failed} failed and need review.`
            : `Accepted ${succeeded.length} candidates.`,
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to accept glossary candidates.',
      });
    },
  });

  const terms = useMemo(() => {
    const nextTerms = termsQuery.data?.terms ?? [];
    if (selectedStatusFilter == null || selectedStatusFilter === ALL_STATUS_FILTER) {
      return nextTerms;
    }
    if (selectedStatusFilter === ACTIVE_STATUS_FILTER) {
      return nextTerms.filter((term) => {
        const status = (term.status ?? 'CANDIDATE').toUpperCase();
        return status === 'CANDIDATE' || status === 'APPROVED';
      });
    }
    return nextTerms.filter(
      (term) => (term.status ?? 'CANDIDATE').toUpperCase() === selectedStatusFilter,
    );
  }, [selectedStatusFilter, termsQuery.data?.terms]);
  const initialOpenTermFromList = useMemo(
    () =>
      initialOpenTermId == null
        ? null
        : (terms.find((term) => term.tmTextUnitId === initialOpenTermId) ?? null),
    [initialOpenTermId, terms],
  );
  const initialOpenTermQuery = useQuery({
    queryKey: ['glossary-term', glossary.id, initialOpenTermId, selectedLocaleTags.join('|')],
    queryFn: () =>
      fetchGlossaryTerm(glossary.id, initialOpenTermId ?? 0, {
        localeTags: selectedLocaleTags,
      }),
    enabled: initialOpenTermId != null && initialOpenTermFromList == null,
    staleTime: 15_000,
  });
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
  const terminologyReviewTeamOptions = useMemo(
    () =>
      (terminologyReviewTeamsQuery.data ?? [])
        .filter((team) => team.enabled !== false)
        .map((team) => ({
          value: team.id,
          label: `${team.name} (#${team.id})`,
        }))
        .sort((first, second) =>
          first.label.localeCompare(second.label, undefined, { sensitivity: 'base' }),
        ),
    [terminologyReviewTeamsQuery.data],
  );
  const terminologyReviewSpecialistOptions = useMemo(
    () =>
      (terminologyReviewSpecialistsQuery.data?.users ?? [])
        .map(toTeamUserSelectOption)
        .sort((first, second) =>
          first.label.localeCompare(second.label, undefined, { sensitivity: 'base' }),
        ),
    [terminologyReviewSpecialistsQuery.data?.users],
  );
  const terminologyReviewAllDeciderOptions = useMemo(() => {
    const optionsByUserId = new Map<
      number,
      { value: number; label: string; helper: string; roles: Set<string> }
    >();
    const addUsers = (users: ApiTeamUserSummary[] | undefined, role: string) => {
      (users ?? []).forEach((user) => {
        const existing = optionsByUserId.get(user.id);
        if (existing) {
          existing.roles.add(role);
          existing.helper = Array.from(existing.roles).join(' + ');
          return;
        }
        optionsByUserId.set(user.id, {
          value: user.id,
          label: getUserLabel(user) || `User #${user.id}`,
          helper: role,
          roles: new Set([role]),
        });
      });
    };
    addUsers(terminologyReviewPmUsersQuery.data?.users, 'PM');
    addUsers(terminologyReviewSpecialistsQuery.data?.users, 'Linguist');
    return Array.from(optionsByUserId.values())
      .map((option) => ({
        value: option.value,
        label: option.label,
        helper: option.helper,
      }))
      .sort((first, second) =>
        first.label.localeCompare(second.label, undefined, { sensitivity: 'base' }),
      );
  }, [terminologyReviewPmUsersQuery.data?.users, terminologyReviewSpecialistsQuery.data?.users]);
  const terminologyReviewLinguistUserIdSet = useMemo(
    () => new Set((terminologyReviewSpecialistsQuery.data?.users ?? []).map((user) => user.id)),
    [terminologyReviewSpecialistsQuery.data?.users],
  );
  const terminologyReviewDeciderOptions = useMemo(() => {
    if (terminologyReviewDeciderShowAllUsers) {
      return terminologyReviewAllDeciderOptions;
    }

    const linguistOptions = terminologyReviewAllDeciderOptions.filter((option) =>
      terminologyReviewLinguistUserIdSet.has(option.value),
    );
    const selectedDeciderOption =
      terminologyReviewDraft?.pmUserId == null
        ? null
        : (terminologyReviewAllDeciderOptions.find(
            (option) => option.value === terminologyReviewDraft.pmUserId,
          ) ?? null);

    if (
      selectedDeciderOption != null &&
      !linguistOptions.some((option) => option.value === selectedDeciderOption.value)
    ) {
      return [selectedDeciderOption, ...linguistOptions];
    }

    return linguistOptions;
  }, [
    terminologyReviewAllDeciderOptions,
    terminologyReviewDeciderShowAllUsers,
    terminologyReviewDraft?.pmUserId,
    terminologyReviewLinguistUserIdSet,
  ]);
  const suggestions = useMemo(
    () => suggestionsQuery.data?.suggestions ?? [],
    [suggestionsQuery.data?.suggestions],
  );
  const acceptableSuggestions = useMemo(
    () => suggestions.filter(canAcceptSuggestion),
    [suggestions],
  );
  const selectedSuggestions = useMemo(
    () =>
      acceptableSuggestions.filter((suggestion) =>
        selectedSuggestionIds.includes(suggestion.termIndexCandidateId),
      ),
    [acceptableSuggestions, selectedSuggestionIds],
  );
  const activeSuggestion = useMemo(
    () =>
      activeSuggestionId == null
        ? null
        : (suggestions.find(
            (suggestion) => suggestion.termIndexCandidateId === activeSuggestionId,
          ) ?? null),
    [activeSuggestionId, suggestions],
  );
  const allSuggestionsSelected =
    acceptableSuggestions.length > 0 &&
    acceptableSuggestions.every((suggestion) =>
      selectedSuggestionIds.includes(suggestion.termIndexCandidateId),
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
  const replacementBackingFieldLabels = getReplacementBackingFieldLabels(
    originalEditorDraft,
    editorDraft,
  );
  const isReplacingBackingTextUnit = replacementBackingFieldLabels.length > 0;
  const handleSaveTerm = () => {
    if (canManageTerms && isReplacingBackingTextUnit) {
      setReplaceCopyTranslations(true);
      setReplaceCopyTranslationStatus('KEEP_CURRENT');
      setTermPendingReplace(editorDraft);
      return;
    }
    saveTermMutation.mutate({ draft: editorDraft });
  };
  const editorTitle = suggestionInEditor
    ? `Candidate: ${suggestionInEditor.term}`
    : editorDraft.tmTextUnitId == null
      ? canManageTerms
        ? 'Create glossary term'
        : 'Propose glossary term'
      : canManageTerms && editorDraft.status === 'CANDIDATE'
        ? `Review candidate: ${editorDraft.source}`
        : editorDraft.source;
  const detailPlaceholder = extractOpen
    ? {
        title: 'Glossary builder',
        message:
          'Select a candidate to review its definition, sources, and glossary metadata before accepting it into this glossary.',
      }
    : {
        title: canManageTerms ? 'Select a glossary term' : 'Browse glossary terms',
        message: canManageTerms
          ? 'Select a term to edit it here, or create a new term without leaving the table.'
          : 'Select a term to inspect its metadata and references.',
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
      onViewStateChange({ mode: 'extract', title: 'Glossary builder' });
      return;
    }

    onViewStateChange({ mode: 'terms', title: glossary.name });
  }, [editorOpen, editorTitle, extractOpen, glossary.name, onViewStateChange]);

  useEffect(() => {
    if (!extractOpen || editorOpen) {
      return;
    }

    setActiveSuggestionId((current) => {
      if (
        current != null &&
        suggestions.some((suggestion) => suggestion.termIndexCandidateId === current)
      ) {
        return current;
      }
      return suggestions[0]?.termIndexCandidateId ?? null;
    });
  }, [editorOpen, extractOpen, suggestions]);

  const openCreateModal = () => {
    setReturnToExtractAfterEditor(false);
    setSuggestionInEditor(null);
    setUploadQueue([]);
    const nextDraft = createBlankDraft(selectedLocaleTags);
    setEditorDraft(nextDraft);
    setOriginalEditorDraft(null);
    setCollapsedWorkspacePane(null);
    setEditorOpen(true);
  };

  const openEditModal = (term: ApiGlossaryTerm) => {
    setReturnToExtractAfterEditor(false);
    setSuggestionInEditor(null);
    setUploadQueue([]);
    const nextDraft = termToDraft(term, glossary.localeTags);
    setEditorDraft(nextDraft);
    setOriginalEditorDraft(nextDraft);
    setCollapsedWorkspacePane(null);
    setEditorOpen(true);
  };

  const openSuggestionModal = (suggestion: ApiGlossaryTermIndexSuggestion) => {
    if (!canAcceptSuggestion(suggestion)) {
      return;
    }
    const nextDraft = suggestionToDraft(suggestion, selectedLocaleTags);
    setActiveSuggestionId(suggestion.termIndexCandidateId);
    setReturnToExtractAfterEditor(true);
    setSuggestionInEditor(suggestion);
    setUploadQueue([]);
    setEditorDraft(nextDraft);
    setOriginalEditorDraft(null);
    setCollapsedWorkspacePane(null);
    setEditorOpen(true);
  };

  const openLinkedCandidate = () => {
    if (editorDraft.termIndexCandidateId == null) {
      return;
    }

    const nextSearchQuery = editorDraft.source.trim();
    setEditorOpen(false);
    setReturnToExtractAfterEditor(false);
    setSuggestionInEditor(null);
    setUploadQueue([]);
    setOriginalEditorDraft(null);
    setSelectedSuggestionIds([]);
    setActiveSuggestionId(editorDraft.termIndexCandidateId);
    setSuggestionReviewStatusFilter('ALL');
    setSuggestionGlossaryPresenceFilter('ALL');
    setSuggestionSearchDraft(nextSearchQuery);
    setSuggestionSearchQuery(nextSearchQuery);
    setSuggestionSearchNonce((current) => current + 1);
    setExtractOpen(true);
    setStatusNotice({
      kind: 'success',
      message: `Showing candidate #${editorDraft.termIndexCandidateId}.`,
    });
  };

  const closeEditor = useCallback(() => {
    if (saveTermMutation.isPending) {
      return;
    }
    setEditorOpen(false);
    setUploadQueue([]);
    setOriginalEditorDraft(null);
    setTermPendingReplace(null);
    if (returnToExtractAfterEditor) {
      setExtractOpen(true);
    }
    setReturnToExtractAfterEditor(false);
    setSuggestionInEditor(null);
    if (initialOpenTermId != null) {
      onClearInitialOpenTerm?.();
    }
  }, [
    initialOpenTermId,
    onClearInitialOpenTerm,
    returnToExtractAfterEditor,
    saveTermMutation.isPending,
  ]);

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

    const requestedTerm = initialOpenTermFromList ?? initialOpenTermQuery.data ?? null;
    if (!requestedTerm) {
      return;
    }

    openedInitialTermIdRef.current = initialOpenTermId;
    setReturnToExtractAfterEditor(false);
    setSuggestionInEditor(null);
    setUploadQueue([]);
    const nextDraft = termToDraft(requestedTerm, glossary.localeTags);
    setEditorDraft(nextDraft);
    setOriginalEditorDraft(nextDraft);
    setCollapsedWorkspacePane(null);
    setEditorOpen(true);
  }, [glossary.localeTags, initialOpenTermFromList, initialOpenTermId, initialOpenTermQuery.data]);

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

  const glossaryTermsListProps = {
    canManageTerms,
    searchDraft,
    onChangeSearch: (value) => {
      setSearchDraft(value);
      setStatusNotice(null);
    },
    searchField,
    searchFieldOptions: TERM_SEARCH_FIELD_OPTIONS,
    onChangeSearchField: (value) => {
      setSearchField(value);
      setStatusNotice(null);
    },
    localeOptions,
    selectedLocaleTags,
    onChangeSelectedLocaleTags: (next) => {
      setSelectedLocaleTags(sortLocaleTagsByOptions(next, localeOptions));
      setStatusNotice(null);
    },
    statusFilterOptions: STATUS_FILTER_OPTIONS.map((option) => ({
      value: option.value,
      label: option.label,
    })),
    selectedStatusFilter,
    onChangeSelectedStatusFilter: setSelectedStatusFilter,
    onOpenExtract: () => {
      setActiveSuggestionId(null);
      setExtractOpen(true);
    },
    onOpenCreate: openCreateModal,
    canImport: canImport && typeof onOpenImport === 'function',
    onOpenImport: () => onOpenImport?.(),
    canExport: canExport && typeof onOpenExport === 'function',
    onOpenExport: () => onOpenExport?.(),
    termsTotalCount: termsQuery.data?.totalCount ?? 0,
    visibleTermsCount: terms.length,
    termsLimit: termsLimitNumber,
    onChangeTermsLimit: (value) => setTermsLimit(String(normalizeTermsLimit(value))),
    termsLimitOptions: TERMS_LIMIT_PRESETS,
    visibleLocaleColumnLimit,
    onChangeVisibleLocaleColumnLimit: (value) =>
      setVisibleLocaleColumnLimit(
        Math.min(Math.max(1, value), Math.max(selectedLocaleTags.length, 1)),
      ),
    visibleLocaleColumnLimitOptions,
    localeColumnSummary,
    selectedTermIdsCount: selectedTermIds.length,
    isCreatingTerminologyReview: terminologyReviewMutation.isPending,
    onCreateTerminologyReview: () =>
      openTerminologyReviewModal(
        selectedTermIds.length > 0 ? 'selected' : 'glossary',
        selectedTermIds.length > 0 ? selectedTermIds : [],
      ),
    onCreateSelectedTerminologyReview: () =>
      openTerminologyReviewModal('selected', selectedTermIds),
    onOpenBatch: () => setBatchOpen(true),
    onClearSelection: () => setSelectedTermIds([]),
    statusNotice: !editorOpen ? statusNotice : null,
    isLoading: termsQuery.isLoading,
    errorMessage: termsQuery.isError
      ? termsQuery.error instanceof Error
        ? termsQuery.error.message
        : 'Could not load glossary terms.'
      : null,
    terms,
    displayedLocaleTags,
    allVisibleSelected,
    onToggleSelectAll: () =>
      setSelectedTermIds(allVisibleSelected ? [] : terms.map((term) => term.tmTextUnitId)),
    onOpenEditTerm: openEditModal,
    onOpenInlineTranslationEdit: openEditModal,
    activeTermId: editorOpen ? (editorDraft.tmTextUnitId ?? null) : null,
    selectedTermIds,
    onToggleTermSelection: (tmTextUnitId, checked) =>
      setSelectedTermIds((current) =>
        checked
          ? Array.from(new Set([...current, tmTextUnitId]))
          : current.filter((id) => id !== tmTextUnitId),
      ),
    getWorkbenchHref: (tmTextUnitId, localeTags = workbenchLocaleTags) => {
      const params = new URLSearchParams();
      params.set('tmTextUnitId', String(tmTextUnitId));
      params.set('repo', String(glossary.backingRepository.id));
      localeTags.forEach((localeTag) => params.append('locale', localeTag));
      return `/workbench?${params.toString()}`;
    },
    getWorkbenchState: (tmTextUnitId, localeTags = workbenchLocaleTags) =>
      buildGlossaryWorkbenchState({
        glossaryId: glossary.id,
        glossaryName: glossary.name,
        backingRepositoryId: glossary.backingRepository.id,
        backingRepositoryName: glossary.backingRepository.name,
        assetPath: glossary.assetPath,
        localeTags,
        tmTextUnitId,
      }),
    statusOptions: [...STATUSES],
    getStatusLabel: (status) => STATUS_LABELS[status as (typeof STATUSES)[number]] ?? status,
    getTermTypeLabel: (termType) =>
      TERM_TYPE_LABELS[termType as (typeof TERM_TYPES)[number]] ?? termType,
    getEnforcementLabel: (enforcement) =>
      ENFORCEMENT_LABELS[enforcement as (typeof ENFORCEMENTS)[number]] ?? enforcement,
    isChangingStatus: statusMutation.isPending,
    openStatusTermId,
    onOpenStatusTermIdChange: (tmTextUnitId, nextOpen) => {
      setOpenStatusTermId((current) => {
        if (nextOpen) {
          return tmTextUnitId;
        }
        return current === tmTextUnitId ? null : current;
      });
    },
    onChangeTermStatus: (term, status) => statusMutation.mutate({ term, status }),
    canEditTranslationLocale: (localeTag) => canManageTerms || canEditLocale(user, localeTag),
    savingTranslationKey:
      inlineTranslationMutation.isPending && inlineTranslationMutation.variables
        ? `${inlineTranslationMutation.variables.term.tmTextUnitId}:${inlineTranslationMutation.variables.localeTag.toLowerCase()}`
        : null,
    onSaveTermTranslation: async (term, localeTag, value) => {
      await inlineTranslationMutation.mutateAsync({
        term,
        localeTag,
        target: value.target,
        targetComment: value.targetComment,
      });
    },
  } satisfies ComponentProps<typeof GlossaryTermsListView>;

  return (
    <section
      className={`settings-card glossary-term-admin${editorOpen ? ' glossary-term-admin--editing' : ''}`}
    >
      {(editorOpen || extractOpen) && statusNotice ? (
        <p className={`settings-hint${statusNotice.kind === 'error' ? ' is-error' : ''}`}>
          {statusNotice.message}
        </p>
      ) : null}
      {!extractOpen ? <GlossaryTermsListControls {...glossaryTermsListProps} /> : null}

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
            <GlossaryCurationView
              searchDraft={suggestionSearchDraft}
              onChangeSearch={(value) => {
                setSuggestionSearchDraft(value);
                setActiveSuggestionId(null);
                setStatusNotice(null);
              }}
              reviewStatusFilter={suggestionReviewStatusFilter}
              onChangeReviewStatusFilter={(value) => {
                setSuggestionReviewStatusFilter(value);
                setSelectedSuggestionIds([]);
                setActiveSuggestionId(null);
                setStatusNotice(null);
              }}
              glossaryPresenceFilter={suggestionGlossaryPresenceFilter}
              onChangeGlossaryPresenceFilter={(value) => {
                setSuggestionGlossaryPresenceFilter(value);
                setSelectedSuggestionIds([]);
                setActiveSuggestionId(null);
                setStatusNotice(null);
              }}
              hasPendingSearchChanges={
                suggestionSearchDraft.trim() !== suggestionSearchQuery.trim()
              }
              onRefreshSuggestions={() => {
                setSelectedSuggestionIds([]);
                setActiveSuggestionId(null);
                setSuggestionSearchQuery(suggestionSearchDraft.trim());
                setSuggestionSearchNonce((current) => current + 1);
              }}
              isLoading={suggestionsQuery.isFetching}
              suggestions={suggestions}
              totalCount={suggestionsQuery.data?.totalCount ?? suggestions.length}
              hasSearched={suggestionSearchNonce > 0}
              errorMessage={
                suggestionsQuery.isError
                  ? suggestionsQuery.error instanceof Error
                    ? suggestionsQuery.error.message
                    : 'Could not load glossary candidates.'
                  : null
              }
              suggestionLimit={suggestionLimitNumber}
              suggestionLimitLabel={formatShortCount(suggestionLimitNumber)}
              onChangeSuggestionLimit={(value) =>
                setSuggestionLimit(String(normalizeSuggestionLimit(value)))
              }
              limitPresetOptions={SUGGESTION_LIMIT_OPTIONS}
              selectedSuggestionIds={selectedSuggestionIds}
              onToggleSuggestion={(termIndexCandidateId, checked) =>
                setSelectedSuggestionIds((current) =>
                  checked
                    ? Array.from(new Set([...current, termIndexCandidateId]))
                    : current.filter((id) => id !== termIndexCandidateId),
                )
              }
              allSuggestionsSelected={allSuggestionsSelected}
              onToggleSelectAll={() =>
                setSelectedSuggestionIds(
                  allSuggestionsSelected
                    ? []
                    : acceptableSuggestions.map((suggestion) => suggestion.termIndexCandidateId),
                )
              }
              onAcceptSelected={() => batchAcceptSuggestionsMutation.mutate(selectedSuggestions)}
              isAcceptingSelected={batchAcceptSuggestionsMutation.isPending}
              onCreateSelectedReview={() => {
                openTermCandidateReviewModal(
                  selectedSuggestions.map((suggestion) => suggestion.termIndexCandidateId),
                );
              }}
              isCreatingSelectedReview={
                terminologyReviewMutation.isPending &&
                terminologyReviewDraft?.kind === 'TERM_CANDIDATE'
              }
              activeSuggestionId={suggestionInEditor?.termIndexCandidateId ?? activeSuggestionId}
              onActivateSuggestion={(suggestion) => {
                setActiveSuggestionId(suggestion.termIndexCandidateId);
                setStatusNotice(null);
              }}
              onAcceptSuggestion={(suggestion) => {
                if (canAcceptSuggestion(suggestion)) {
                  acceptSuggestionMutation.mutate(suggestion);
                }
              }}
              onIgnoreSuggestion={(suggestion) => ignoreSuggestionMutation.mutate(suggestion)}
              pendingSuggestionActions={pendingSuggestionActions}
            />
          ) : (
            <GlossaryTermsListView {...glossaryTermsListProps} showControls={false} />
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
                  <div className="glossary-term-admin__term-dates">
                    <div className="glossary-term-admin__term-date">
                      <span className="settings-field__label">Created</span>
                      <span title={getLocalAndUtcDateTimeTooltip(editorDraft.createdDate)}>
                        {formatLocalDateTime(editorDraft.createdDate)}
                      </span>
                    </div>
                    <div className="glossary-term-admin__term-date">
                      <span className="settings-field__label">Updated</span>
                      <span title={getLocalAndUtcDateTimeTooltip(editorDraft.lastModifiedDate)}>
                        {formatLocalDateTime(editorDraft.lastModifiedDate)}
                      </span>
                    </div>
                  </div>
                  {editorDraft.tmTextUnitId != null ? (
                    <Link
                      className="settings-button settings-button--ghost glossary-term-admin__term-share-link"
                      to={getGlossaryTermWorkspacePath(glossary.id, editorDraft.tmTextUnitId)}
                    >
                      Share URL
                    </Link>
                  ) : null}
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
                      disabled={!canManageTerms}
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
                  <AutoTextarea
                    id="glossary-term-definition"
                    className="settings-input"
                    value={editorDraft.definition}
                    disabled={!canEditProposalDraft}
                    minRows={3}
                    maxRows={12}
                    onChange={(event) =>
                      setEditorDraft((current) => ({
                        ...current,
                        definition: event.target.value,
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
                          {TERM_TYPE_LABELS[termType]}
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
                          {ENFORCEMENT_LABELS[enforcement]}
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
                          {PROVENANCE_LABELS[provenance]}
                        </option>
                      ))}
                    </select>
                  </div>
                </div>

                {editorDraft.termIndexCandidateId != null ||
                editorDraft.termIndexExtractedTermId != null ? (
                  <section className="glossary-term-admin__section glossary-term-admin__index-section">
                    <div className="glossary-term-admin__section-header">
                      <h4 className="glossary-term-admin__section-title">Term index</h4>
                      <div className="glossary-term-admin__index-links">
                        {editorDraft.termIndexCandidateId != null ? (
                          <button
                            type="button"
                            className="settings-button settings-button--ghost glossary-term-admin__index-link"
                            onClick={openLinkedCandidate}
                          >
                            Candidate #{editorDraft.termIndexCandidateId}
                          </button>
                        ) : null}
                        {editorDraft.termIndexExtractedTermId != null ? (
                          <Link
                            className="settings-button settings-button--ghost glossary-term-admin__index-link"
                            to={getRawTermExplorerPath(
                              editorDraft.termIndexExtractedTermId,
                              editorDraft.source,
                            )}
                          >
                            Extracted term #{editorDraft.termIndexExtractedTermId}
                          </Link>
                        ) : null}
                      </div>
                    </div>
                    {editorDraft.termIndexOccurrenceCount != null ||
                    editorDraft.termIndexRepositoryCount != null ? (
                      <p className="glossary-term-admin__index-summary">
                        {[
                          editorDraft.termIndexOccurrenceCount == null
                            ? null
                            : formatCountLabel(
                                editorDraft.termIndexOccurrenceCount,
                                'occurrence',
                                'occurrences',
                              ),
                          editorDraft.termIndexRepositoryCount == null
                            ? null
                            : formatCountLabel(
                                editorDraft.termIndexRepositoryCount,
                                'repository',
                                'repositories',
                              ),
                        ]
                          .filter(Boolean)
                          .join(' across ')}
                        {editorDraft.termIndexCandidateId == null
                          ? '. This approved glossary term is matched directly from extracted usage, so no candidate row is required.'
                          : null}
                      </p>
                    ) : null}
                  </section>
                ) : null}

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

                <section className="glossary-term-admin__section">
                  <div className="glossary-term-admin__section-header">
                    <div>
                      <h4 className="glossary-term-admin__section-title">References</h4>
                      <p className="glossary-term-admin__section-description">
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
                          Add reference
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
                    disabled={saveTermMutation.isPending || deleteTermMutation.isPending}
                  >
                    {returnToExtractAfterEditor ? 'Back to candidates' : 'Cancel'}
                  </button>
                  <div className="glossary-term-admin__editor-page-actions">
                    {canManageTerms && editorDraft.tmTextUnitId != null ? (
                      <button
                        type="button"
                        className="settings-button settings-button--ghost"
                        onClick={() => setTermPendingDelete(editorDraft)}
                        disabled={saveTermMutation.isPending || deleteTermMutation.isPending}
                      >
                        Delete
                      </button>
                    ) : null}
                    {editorWorkbenchState ? (
                      <Link
                        to="/workbench"
                        state={editorWorkbenchState}
                        className="settings-button settings-button--ghost"
                      >
                        Open in Workbench
                      </Link>
                    ) : null}
                    {canManageTerms || editorDraft.tmTextUnitId == null ? (
                      <button
                        type="button"
                        className="settings-button settings-button--primary"
                        onClick={handleSaveTerm}
                        disabled={
                          saveTermMutation.isPending ||
                          deleteTermMutation.isPending ||
                          !editorDraft.source.trim() ||
                          (canManageTerms &&
                            editorDraft.tmTextUnitId != null &&
                            !editorDraft.termKey.trim())
                        }
                      >
                        {saveTermMutation.isPending
                          ? canManageTerms
                            ? 'Saving…'
                            : 'Submitting…'
                          : canManageTerms && isReplacingBackingTextUnit
                            ? 'Replace term'
                            : canManageTerms
                              ? 'Save term'
                              : 'Submit candidate'}
                      </button>
                    ) : null}
                  </div>
                </div>
              </div>
            </div>
          ) : extractOpen && activeSuggestion ? (
            <GlossarySuggestionDetailView
              suggestion={activeSuggestion}
              onOpenSuggestion={openSuggestionModal}
              onAcceptSuggestion={(suggestion) => {
                if (canAcceptSuggestion(suggestion)) {
                  acceptSuggestionMutation.mutate(suggestion);
                }
              }}
              onIgnoreSuggestion={(suggestion) => ignoreSuggestionMutation.mutate(suggestion)}
              pendingAction={pendingSuggestionActions[activeSuggestion.termIndexCandidateId]}
              isAcceptingSelected={batchAcceptSuggestionsMutation.isPending}
            />
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
        open={terminologyReviewDraft != null}
        size="lg"
        className="glossary-term-admin__terminology-review-modal"
        ariaLabel={
          terminologyReviewDraft?.kind === 'TERM_CANDIDATE'
            ? 'Create candidate review'
            : 'Create terminology review'
        }
        onClose={closeTerminologyReviewModal}
        closeOnBackdrop={!terminologyReviewMutation.isPending}
      >
        {terminologyReviewDraft ? (
          <>
            <div className="modal__header">
              <div>
                <h3 className="modal__title">
                  {terminologyReviewDraft.kind === 'TERM_CANDIDATE'
                    ? 'Create candidate review'
                    : 'Create terminology review'}
                </h3>
                <p className="settings-hint">
                  {terminologyReviewDraft.kind === 'TERM_CANDIDATE'
                    ? 'Creates a decider row, plus advisor rows when advisors are selected.'
                    : 'Creates a decider row, plus advisor rows when advisors are selected.'}
                </p>
              </div>
            </div>
            <div className="glossary-term-admin__terminology-review-modal-body">
              <div className="settings-grid settings-grid--two-column">
                {terminologyReviewDraft.kind === 'TERM_CANDIDATE' ? (
                  <div className="settings-field">
                    <span className="settings-field__label">Scope</span>
                    <div className="glossary-term-admin__terminology-review-summary">
                      Selected candidates
                    </div>
                  </div>
                ) : (
                  <label className="settings-field">
                    <span className="settings-field__label">Scope</span>
                    <select
                      className="settings-input"
                      value={terminologyReviewDraft.scope}
                      disabled={terminologyReviewMutation.isPending}
                      onChange={(event) => {
                        const scope = event.target.value as TerminologyReviewScope;
                        setTerminologyReviewDraft((current) =>
                          current == null
                            ? current
                            : {
                                ...current,
                                scope,
                                tmTextUnitIds: scope === 'selected' ? selectedTermIds : [],
                              },
                        );
                      }}
                    >
                      <option value="glossary">All reviewable terms</option>
                      <option value="selected" disabled={selectedTermIds.length === 0}>
                        {`Selected text unit${selectedTermIds.length === 1 ? '' : 's'} (${selectedTermIds.length})`}
                      </option>
                    </select>
                  </label>
                )}
                <div className="settings-field">
                  <span className="settings-field__label">
                    {terminologyReviewDraft.kind === 'TERM_CANDIDATE' ? 'Candidates' : 'Terms'}
                  </span>
                  <div className="glossary-term-admin__terminology-review-summary">
                    {terminologyReviewTermCount.toLocaleString()}{' '}
                    {terminologyReviewDraft.kind === 'TERM_CANDIDATE'
                      ? `candidate${terminologyReviewTermCount === 1 ? '' : 's'}`
                      : `term${terminologyReviewTermCount === 1 ? '' : 's'}`}
                  </div>
                </div>
                <div className="settings-field settings-field--full">
                  <span className="settings-field__label">Review team</span>
                  <SingleSelectDropdown<number>
                    label="Review team"
                    options={terminologyReviewTeamOptions}
                    value={terminologyReviewDraft.teamId}
                    onChange={(next) => {
                      setTerminologyReviewDeciderShowAllUsers(false);
                      setTerminologyReviewDraft((current) => {
                        return current == null
                          ? current
                          : {
                              ...current,
                              teamId: next,
                              specialistUserIds: [],
                              pmUserId: null,
                            };
                      });
                    }}
                    className="glossary-term-admin__terminology-review-picker"
                    placeholder={
                      terminologyReviewTeamsQuery.isFetching
                        ? 'Loading teams...'
                        : 'Select review team'
                    }
                    disabled={
                      terminologyReviewMutation.isPending || terminologyReviewTeamsQuery.isFetching
                    }
                    buttonAriaLabel="Choose terminology review team"
                    searchPlaceholder="Filter teams"
                    noResultsLabel="No teams found"
                  />
                  <span className="settings-hint">
                    Create a dedicated glossary review team when advisors span vendors.
                  </span>
                </div>
                <div className="settings-field settings-field--full">
                  <span className="settings-field__label">Advisors</span>
                  <MultiSelectChip<number>
                    label="Advisors"
                    options={terminologyReviewSpecialistOptions}
                    selectedValues={terminologyReviewDraft.specialistUserIds}
                    className="glossary-term-admin__terminology-review-picker"
                    onChange={(next) =>
                      setTerminologyReviewDraft((current) =>
                        current == null ? current : { ...current, specialistUserIds: next },
                      )
                    }
                    placeholder="Choose advisors"
                    emptyOptionsLabel={
                      terminologyReviewTeamId == null
                        ? 'Select a team first'
                        : terminologyReviewSpecialistsQuery.isFetching
                          ? 'Loading advisors…'
                          : 'No linguist users in this team'
                    }
                    disabled={
                      terminologyReviewMutation.isPending ||
                      terminologyReviewTeamId == null ||
                      terminologyReviewSpecialistsQuery.isFetching
                    }
                    buttonAriaLabel="Choose terminology review advisors"
                    searchPlaceholder="Filter advisors"
                    noResultsLabel="No advisors found"
                  />
                  <span className="settings-hint">
                    Leave empty to skip advisor review and create only the decider row.
                  </span>
                </div>
                <div className="settings-field settings-field--full">
                  <span className="settings-field__label">Decider</span>
                  <SingleSelectDropdown<number>
                    label="Decider"
                    options={terminologyReviewDeciderOptions}
                    value={terminologyReviewDraft.pmUserId}
                    className="glossary-term-admin__terminology-review-picker"
                    onChange={(next) =>
                      setTerminologyReviewDraft((current) =>
                        current == null ? current : { ...current, pmUserId: next },
                      )
                    }
                    placeholder={
                      terminologyReviewTeamId == null
                        ? 'Select team first'
                        : terminologyReviewPmUsersQuery.isFetching ||
                            terminologyReviewSpecialistsQuery.isFetching
                          ? 'Loading deciders…'
                          : terminologyReviewDeciderOptions.length === 0
                            ? terminologyReviewDeciderShowAllUsers
                              ? 'No team users found'
                              : 'No linguists in this team'
                            : 'Leave unassigned'
                    }
                    noneLabel="Leave unassigned"
                    disabled={
                      terminologyReviewMutation.isPending ||
                      terminologyReviewTeamId == null ||
                      terminologyReviewPmUsersQuery.isFetching ||
                      terminologyReviewSpecialistsQuery.isFetching
                    }
                    buttonAriaLabel="Choose terminology review decider"
                    searchPlaceholder="Filter deciders"
                    noResultsLabel={
                      terminologyReviewDeciderShowAllUsers
                        ? 'No team users found'
                        : 'No linguists found'
                    }
                    footerAction={
                      terminologyReviewTeamId == null
                        ? null
                        : {
                            label: terminologyReviewDeciderShowAllUsers
                              ? 'Linguists only'
                              : 'All team users',
                            onClick: () =>
                              setTerminologyReviewDeciderShowAllUsers((value) => !value),
                            disabled:
                              terminologyReviewMutation.isPending ||
                              terminologyReviewPmUsersQuery.isFetching ||
                              terminologyReviewSpecialistsQuery.isFetching,
                          }
                    }
                  />
                </div>
                <label className="settings-field">
                  <span className="settings-field__label">Advisor due</span>
                  <input
                    type="datetime-local"
                    className="settings-input"
                    value={
                      terminologyReviewDraft.specialistUserIds.length > 0
                        ? terminologyReviewDraft.specialistDueDate
                        : ''
                    }
                    disabled={
                      terminologyReviewMutation.isPending ||
                      terminologyReviewDraft.specialistUserIds.length === 0
                    }
                    onChange={(event) =>
                      setTerminologyReviewDraft((current) =>
                        current == null
                          ? current
                          : { ...current, specialistDueDate: event.target.value },
                      )
                    }
                  />
                  <span className="settings-hint">Only needed when advisor rows are selected.</span>
                </label>
                <label className="settings-field">
                  <span className="settings-field__label">Decider due</span>
                  <input
                    type="datetime-local"
                    className="settings-input"
                    value={terminologyReviewDraft.pmDueDate}
                    disabled={terminologyReviewMutation.isPending}
                    onChange={(event) =>
                      setTerminologyReviewDraft((current) =>
                        current == null ? current : { ...current, pmDueDate: event.target.value },
                      )
                    }
                  />
                </label>
              </div>
              {terminologyReviewTeamsQuery.isError ||
              terminologyReviewSpecialistsQuery.isError ||
              terminologyReviewPmUsersQuery.isError ? (
                <div className="glossary-term-admin__modal-error">
                  {terminologyReviewTeamsQuery.error instanceof Error
                    ? terminologyReviewTeamsQuery.error.message
                    : terminologyReviewSpecialistsQuery.error instanceof Error
                      ? terminologyReviewSpecialistsQuery.error.message
                      : terminologyReviewPmUsersQuery.error instanceof Error
                        ? terminologyReviewPmUsersQuery.error.message
                        : 'Could not load team users.'}
                </div>
              ) : null}
              {terminologyReviewMutation.isError ? (
                <div className="glossary-term-admin__modal-error">
                  {terminologyReviewMutation.error instanceof Error
                    ? terminologyReviewMutation.error.message
                    : 'Failed to create terminology review.'}
                </div>
              ) : null}
            </div>
            <div className="modal__actions">
              <button
                type="button"
                className="modal__button"
                onClick={closeTerminologyReviewModal}
                disabled={terminologyReviewMutation.isPending}
              >
                Cancel
              </button>
              <button
                type="button"
                className="modal__button modal__button--primary"
                onClick={() => terminologyReviewMutation.mutate(terminologyReviewDraft)}
                disabled={!canSubmitTerminologyReview}
              >
                {terminologyReviewMutation.isPending ? 'Creating…' : 'Create review'}
              </button>
            </div>
          </>
        ) : null}
      </Modal>

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
                  {TERM_TYPE_LABELS[termType]}
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
                  {ENFORCEMENT_LABELS[enforcement]}
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
                  {STATUS_LABELS[status]}
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
                  {PROVENANCE_LABELS[provenance]}
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

      <Modal
        open={termPendingReplace != null}
        size="sm"
        role="alertdialog"
        ariaLabel="Replace glossary term"
      >
        <div className="modal__title">Replace glossary term</div>
        <div className="modal__body">
          {termPendingReplace
            ? `Replacing ${termPendingReplace.source} will create a new backing text unit for the changed ${getReplacementBackingFieldLabels(
                originalEditorDraft,
                termPendingReplace,
              ).join(
                ', ',
              )}. The old text unit will be left unused so existing history remains intact.`
            : ''}
        </div>
        <div className="settings-grid">
          <label className="settings-field__row glossary-term-admin__replace-option">
            <span>Copy existing translations to the replacement term</span>
            <input
              type="checkbox"
              checked={replaceCopyTranslations}
              onChange={(event) => setReplaceCopyTranslations(event.target.checked)}
            />
          </label>
          <label className="settings-field">
            <span className="settings-field__label">Copied translation status</span>
            <select
              className="settings-input"
              value={replaceCopyTranslationStatus}
              onChange={(event) =>
                setReplaceCopyTranslationStatus(event.target.value as CopyTranslationStatus)
              }
              disabled={!replaceCopyTranslations}
            >
              <option value="KEEP_CURRENT">Keep current status</option>
              <option value="REVIEW_NEEDED">Send to review</option>
              <option value="APPROVED">Mark approved</option>
            </select>
          </label>
        </div>
        <div className="modal__actions glossary-term-admin__replace-actions">
          <button
            type="button"
            className="modal__button"
            onClick={() => {
              if (!saveTermMutation.isPending) {
                setTermPendingReplace(null);
              }
            }}
          >
            Cancel
          </button>
          <button
            type="button"
            className="modal__button modal__button--primary"
            onClick={() => {
              if (termPendingReplace) {
                saveTermMutation.mutate({
                  draft: termPendingReplace,
                  replaceTerm: true,
                  copyTranslationsOnReplace: replaceCopyTranslations,
                  copyTranslationStatus: replaceCopyTranslationStatus,
                });
              }
            }}
            disabled={saveTermMutation.isPending}
          >
            {saveTermMutation.isPending ? 'Replacing…' : 'Replace term'}
          </button>
        </div>
      </Modal>

      <ConfirmModal
        open={termPendingDelete != null}
        title="Delete glossary term"
        body={
          termPendingDelete
            ? `Delete ${termPendingDelete.source}? This removes the term from the glossary. Use Rejected instead if you want to keep an audit trail.`
            : ''
        }
        confirmLabel={deleteTermMutation.isPending ? 'Deleting…' : 'Delete'}
        cancelLabel="Cancel"
        onCancel={() => {
          if (!deleteTermMutation.isPending) {
            setTermPendingDelete(null);
          }
        }}
        onConfirm={() => {
          if (termPendingDelete) {
            deleteTermMutation.mutate(termPendingDelete);
          }
        }}
        requireText={termPendingDelete?.source}
      />
    </section>
  );
}
