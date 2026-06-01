import './settings-page.css';
import '../../components/filters/filter-chip.css';
import './term-index-explorer-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  type CSSProperties,
  type KeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';

import { type ApiGlossarySummary, fetchGlossaries } from '../../api/glossaries';
import { createGlossaryTermCandidateReviewProjectRequest } from '../../api/review-projects';
import { type ApiTeamUserSummary, fetchTeams, fetchTeamUsersByRole } from '../../api/teams';
import {
  type ApiGenerateTermIndexCandidatesResponse,
  type ApiTermIndexCandidate,
  type ApiTermIndexEntry,
  type ApiTermIndexEntrySort,
  type ApiTermIndexOccurrence,
  type ApiTermIndexReviewAuthorityFilter,
  type ApiTermIndexReviewStatus,
  type ApiTermIndexReviewStatusFilter,
  type ApiTermIndexTaskProgress,
  type ApiTriageTermIndexEntriesResponse,
  fetchTermIndexCandidates,
  fetchTermIndexEntries,
  fetchTermIndexOccurrences,
  fetchTermIndexStatus,
  startGenerateTermIndexCandidatesFromEntries,
  startTermIndexRefresh,
  startTriageTermIndexEntries,
  triageTermIndexEntries,
  updateTermIndexCandidate,
  updateTermIndexCandidateReviews,
  updateTermIndexEntryReview,
  updateTermIndexEntryReviews,
  waitForTermIndexRefreshTask,
} from '../../api/term-index';
import {
  type DateQuickRange,
  MultiSectionFilterChip,
} from '../../components/filters/MultiSectionFilterChip';
import { Modal } from '../../components/Modal';
import { MultiSelectChip, type MultiSelectCustomAction } from '../../components/MultiSelectChip';
import { NumericPresetDropdown } from '../../components/NumericPresetDropdown';
import { PillDropdown } from '../../components/PillDropdown';
import { RepositoryMultiSelect } from '../../components/RepositoryMultiSelect';
import { SearchControl } from '../../components/SearchControl';
import {
  SingleSelectDropdown,
  type SingleSelectOption,
} from '../../components/SingleSelectDropdown';
import { useMeasuredRowRefs } from '../../components/virtual/useMeasuredRowRefs';
import { useVirtualRows } from '../../components/virtual/useVirtualRows';
import { VirtualList } from '../../components/virtual/VirtualList';
import { useRepositories } from '../../hooks/useRepositories';
import { useUser } from '../../hooks/useUser';
import { getStandardDateQuickRanges } from '../../utils/dateQuickRanges';
import { formatLocalDateTime } from '../../utils/dateTime';
import {
  type RepositorySelectionOption,
  useRepositorySelection,
  useRepositorySelectionOptions,
} from '../../utils/repositorySelection';
import { getUserLabel } from '../../utils/userDisplayName';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

const TERM_RESULT_LIMIT_DEFAULT = 1000;
const TERM_RESULT_LIMIT_OPTIONS = [
  { value: 10, label: '10' },
  { value: 100, label: '100' },
  { value: 1000, label: '1k' },
  { value: 10000, label: '10k' },
];
const TERM_RESULT_LIMIT_MAX = 10000;
const RUN_RESULT_LIMIT_DEFAULT = 20;
const RUN_RESULT_LIMIT_OPTIONS = [
  { value: 20, label: '20' },
  { value: 50, label: '50' },
  { value: 100, label: '100' },
  { value: 200, label: '200' },
];
const RUN_RESULT_LIMIT_MAX = 500;
const MIN_OCCURRENCES_DEFAULT = 1;
const MIN_OCCURRENCES_OPTIONS = [
  { value: 1, label: '1+' },
  { value: 2, label: '2+' },
  { value: 5, label: '5+' },
  { value: 10, label: '10+' },
  { value: 25, label: '25+' },
  { value: 50, label: '50+' },
];
const REVIEW_STATUS_FILTER_OPTIONS: Array<{
  value: ApiTermIndexReviewStatusFilter;
  label: string;
}> = [
  { value: 'NON_REJECTED', label: 'Non-rejected' },
  { value: 'TO_REVIEW', label: 'To review' },
  { value: 'ACCEPTED', label: 'Accepted' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'ALL', label: 'All statuses' },
];
const REVIEW_STATUS_OPTIONS: Array<{
  value: ApiTermIndexReviewStatus;
  label: string;
}> = [
  { value: 'TO_REVIEW', label: 'To review' },
  { value: 'ACCEPTED', label: 'Accepted' },
  { value: 'REJECTED', label: 'Rejected' },
];
const REVIEW_AUTHORITY_FILTER_OPTIONS: Array<{
  value: ApiTermIndexReviewAuthorityFilter;
  label: string;
}> = [
  { value: 'ALL', label: 'All review sources' },
  { value: 'NONE', label: 'Unreviewed' },
  { value: 'AI', label: 'AI reviewed' },
  { value: 'HUMAN', label: 'Human reviewed' },
];
const REVIEW_REASON_OPTIONS = [
  { value: 'STOP_WORD', label: 'Stop word' },
  { value: 'TOO_GENERIC', label: 'Too generic' },
  { value: 'FALSE_POSITIVE', label: 'False positive' },
  { value: 'OUT_OF_SCOPE', label: 'Out of scope' },
  { value: 'OTHER', label: 'Other' },
];
const TERM_SORT_OPTIONS: Array<{
  value: ApiTermIndexEntrySort;
  label: string;
}> = [
  { value: 'REVIEW_CONFIDENCE_DESC', label: 'Highest confidence' },
  { value: 'HITS', label: 'Most hits' },
  { value: 'REVIEW_CONFIDENCE_ASC', label: 'Lowest confidence' },
];
const ALL_EXTRACTORS_FILTER_VALUE = '__ALL_EXTRACTORS__';
const TERM_TYPE_OPTIONS = [
  { value: 'BRAND', label: 'Brand' },
  { value: 'PRODUCT', label: 'Product' },
  { value: 'UI_LABEL', label: 'UI label' },
  { value: 'LEGAL', label: 'Legal' },
  { value: 'TECHNICAL', label: 'Technical' },
  { value: 'GENERAL', label: 'General' },
];
const ENFORCEMENT_OPTIONS = [
  { value: 'HARD', label: 'Hard' },
  { value: 'SOFT', label: 'Soft' },
  { value: 'REVIEW_ONLY', label: 'Review only' },
];
const EXAMPLE_RESULT_LIMIT = 500;
const DEFAULT_TERM_PANE_WIDTH_PCT = 42;
const DEFAULT_BATCH_SIZE = 1000;
const TERM_INDEX_AI_REVIEW_BATCH_SIZE = 50;
const TERM_INDEX_FILTER_CHIP_CLASS_NAMES = {
  button: 'filter-chip__button',
  panel: 'filter-chip__panel',
  section: 'filter-chip__section',
  label: 'filter-chip__label',
  list: 'filter-chip__list',
  option: 'filter-chip__option',
  helper: 'filter-chip__helper',
  pills: 'filter-chip__pills',
  quick: 'filter-chip__quick',
  quickChip: 'filter-chip__quick-chip',
  custom: 'filter-chip__custom',
  customLabel: 'filter-chip__custom-label',
  customInput: 'filter-chip__custom-input',
  dateInput: 'filter-chip__date-input',
  clear: 'filter-chip__clear-link',
};

type CandidateDraft = {
  definition: string;
  rationale: string;
  termType: string;
  partOfSpeech: string;
  enforcement: string;
  doNotTranslate: boolean;
  confidence: string;
  reviewStatus: ApiTermIndexReviewStatus;
  editedFields: Partial<Record<CandidateDraftField, boolean>>;
};

type CandidateDraftField =
  | 'definition'
  | 'rationale'
  | 'termType'
  | 'partOfSpeech'
  | 'enforcement'
  | 'doNotTranslate'
  | 'confidence'
  | 'reviewStatus';

type CandidateGenerationRequest = {
  mode: 'single' | 'selected' | 'filter';
  entryIds: number[];
  entries: ApiTermIndexEntry[];
  draft: CandidateDraft | null;
  searchQuery?: string;
  extractionMethod?: string | null;
  limit?: number;
  minOccurrences?: number;
  lastOccurrenceAfter?: string | null;
  lastOccurrenceBefore?: string | null;
  reviewChangedAfter?: string | null;
  reviewChangedBefore?: string | null;
  reviewStatusFilter?: ApiTermIndexReviewStatusFilter;
  reviewAuthorityFilter?: ApiTermIndexReviewAuthorityFilter;
  refreshExistingCandidates?: boolean;
};

type CandidateReviewRequest = {
  entries: ApiTermIndexEntry[];
  candidateIds: number[];
  selectedEntryCount: number;
  glossaryId: number | null;
  teamId: number | null;
  specialistUserIds: number[];
  pmUserId: number | null;
};

type ExtractedTermTriageRequest = {
  mode: 'selected' | 'filter';
  entryIds: number[];
  entries: ApiTermIndexEntry[];
  searchQuery?: string;
  extractionMethod?: string | null;
  reviewStatusFilter?: ApiTermIndexReviewStatusFilter;
  reviewAuthorityFilter?: ApiTermIndexReviewAuthorityFilter;
  minOccurrences?: number;
  limit?: number;
  lastOccurrenceAfter?: string | null;
  lastOccurrenceBefore?: string | null;
  reviewChangedAfter?: string | null;
  reviewChangedBefore?: string | null;
};

type TermIndexReviewUpdate = {
  reviewStatus: ApiTermIndexReviewStatus;
  reviewReason?: string | null;
  reviewRationale?: string | null;
  reviewConfidence?: number | null;
};

type TermIndexReviewDraft = {
  reviewStatus: ApiTermIndexReviewStatus;
  reviewReason: string;
  reviewRationale: string;
  reviewConfidence: string;
};

type TermIndexBatchReviewUpdate = TermIndexReviewUpdate & {
  updateReviewReason: boolean;
  updateReviewRationale: boolean;
  updateReviewConfidence: boolean;
};

type TermIndexBatchReviewDraft = {
  reviewStatus: ApiTermIndexReviewStatus;
  updateReviewReason: boolean;
  reviewReason: string;
  updateReviewRationale: boolean;
  reviewRationale: string;
  updateReviewConfidence: boolean;
  reviewConfidence: string;
};

type TermIndexBulkReviewAction = 'MANUAL_REVIEW' | 'AI_REVIEW';

const TERM_INDEX_BULK_REVIEW_OPTIONS: Array<{
  value: TermIndexBulkReviewAction;
  label: string;
}> = [
  { value: 'MANUAL_REVIEW', label: 'Manual review' },
  { value: 'AI_REVIEW', label: 'AI review' },
];

const parsePositiveIntegerParam = (value: string | null) => {
  if (value == null) {
    return null;
  }
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
};

const isInteractiveTarget = (target: EventTarget | null) =>
  target instanceof HTMLElement &&
  target.closest('button, input, select, textarea, a, [contenteditable="true"]') != null;

const isTextEntryTarget = (target: EventTarget | null) => {
  if (!(target instanceof HTMLElement)) {
    return false;
  }

  const element = target.closest('input, select, textarea, [contenteditable="true"]');
  if (!(element instanceof HTMLElement)) {
    return false;
  }

  if (element instanceof HTMLInputElement) {
    const type = element.type.toLowerCase();
    return !['button', 'checkbox', 'radio', 'reset', 'submit'].includes(type);
  }

  return true;
};

const getTermIndexFromTarget = (target: EventTarget | null) => {
  if (!(target instanceof HTMLElement)) {
    return -1;
  }

  const row = target.closest<HTMLElement>('[data-term-index]');
  if (row?.dataset.termIndex == null) {
    return -1;
  }

  const index = Number(row.dataset.termIndex);
  return Number.isInteger(index) ? index : -1;
};

type TermIndexRefreshForm = {
  repositoryIds: number[];
  fullRefresh: boolean;
  excludeGlossaryRepositories: boolean;
};

const PaneGripIcon = () => (
  <svg
    className="term-index-explorer__pane-grip"
    viewBox="0 0 6 22"
    aria-hidden="true"
    focusable="false"
  >
    <circle cx="3" cy="4" r="1" />
    <circle cx="3" cy="8.5" r="1" />
    <circle cx="3" cy="13" r="1" />
    <circle cx="3" cy="17.5" r="1" />
  </svg>
);

const getRepositoryIds = (repositoryOptions: RepositorySelectionOption[]) =>
  repositoryOptions.map((repository) => repository.id);

const getProductRepositoryIds = (repositoryOptions: RepositorySelectionOption[]) =>
  repositoryOptions
    .filter((repository) => !repository.isGlossary)
    .map((repository) => repository.id);

const getGlossaryRepositoryIds = (repositoryOptions: RepositorySelectionOption[]) =>
  repositoryOptions
    .filter((repository) => repository.isGlossary)
    .map((repository) => repository.id);

const getRawTermExplorerPath = (entryId: number, search?: string) => {
  const params = new URLSearchParams({ entryId: String(entryId) });
  const trimmedSearch = search?.trim();
  if (trimmedSearch) {
    params.set('search', trimmedSearch);
  }
  return `/settings/system/glossary-term-index/terms?${params.toString()}`;
};

const candidateToEntry = (candidate: ApiTermIndexCandidate): ApiTermIndexEntry => ({
  id: candidate.id,
  termIndexExtractedTermId: candidate.termIndexExtractedTermId ?? null,
  termIndexCandidateId: candidate.id,
  candidateDefinition: candidate.definition ?? null,
  candidateRationale: candidate.rationale ?? null,
  candidateTermType: candidate.termType ?? null,
  candidatePartOfSpeech: candidate.partOfSpeech ?? null,
  candidateEnforcement: candidate.enforcement ?? null,
  candidateDoNotTranslate: candidate.doNotTranslate ?? null,
  candidateConfidence: candidate.confidence ?? null,
  candidateReviewStatus: candidate.reviewStatus,
  candidateReviewAuthority: candidate.reviewAuthority,
  candidateReviewReason: candidate.reviewReason ?? null,
  candidateReviewRationale: candidate.reviewRationale ?? null,
  candidateReviewConfidence: candidate.reviewConfidence ?? null,
  candidateReviewChangedAt: candidate.reviewChangedAt ?? null,
  candidateReviewChangedByUserId: candidate.reviewChangedByUserId ?? null,
  candidateReviewChangedByUsername: candidate.reviewChangedByUsername ?? null,
  candidateReviewChangedByCommonName: candidate.reviewChangedByCommonName ?? null,
  normalizedKey: candidate.normalizedKey,
  displayTerm: candidate.label?.trim() || candidate.term,
  sourceLocaleTag: candidate.sourceLocaleTag,
  createdDate: candidate.candidateCreatedDate ?? null,
  lastModifiedDate: null,
  reviewStatus: candidate.reviewStatus,
  reviewAuthority: candidate.reviewAuthority,
  reviewReason: candidate.reviewReason ?? null,
  reviewRationale: candidate.reviewRationale ?? null,
  reviewConfidence: candidate.reviewConfidence ?? null,
  reviewChangedAt: candidate.reviewChangedAt ?? null,
  reviewChangedByUserId: candidate.reviewChangedByUserId ?? null,
  reviewChangedByUsername: candidate.reviewChangedByUsername ?? null,
  reviewChangedByCommonName: candidate.reviewChangedByCommonName ?? null,
  occurrenceCount: candidate.occurrenceCount,
  repositoryCount: candidate.repositoryCount,
  lastOccurrenceAt: candidate.lastOccurrenceAt ?? null,
});

const createCandidateDraft = (entry: ApiTermIndexEntry | null): CandidateDraft => ({
  definition: entry?.candidateDefinition ?? '',
  rationale: entry?.candidateRationale ?? '',
  termType: entry?.candidateTermType ?? suggestCandidateTermType(entry?.displayTerm ?? ''),
  partOfSpeech: entry?.candidatePartOfSpeech ?? '',
  enforcement: entry?.candidateEnforcement ?? 'SOFT',
  doNotTranslate:
    entry?.candidateDoNotTranslate ?? shouldCandidatePreserveSource(entry?.displayTerm ?? ''),
  confidence: entry?.candidateConfidence == null ? '' : String(entry.candidateConfidence),
  reviewStatus: entry?.candidateReviewStatus ?? 'TO_REVIEW',
  editedFields: {},
});

const suggestCandidateTermType = (term: string) => {
  const normalized = term.trim();
  if (!normalized) {
    return 'GENERAL';
  }
  if (/^[A-Z0-9][A-Z0-9._-]*$/.test(normalized) && /[A-Z]/.test(normalized)) {
    return 'BRAND';
  }
  if (/[._/-]/.test(normalized)) {
    return 'TECHNICAL';
  }
  return 'GENERAL';
};

const shouldCandidatePreserveSource = (term: string) => {
  const normalized = term.trim();
  return /^[A-Z0-9][A-Z0-9._/-]*$/.test(normalized) && /[A-Z]/.test(normalized);
};

const nullableTrimmed = (value: string) => {
  const normalized = value.trim();
  return normalized ? normalized : null;
};

const getManualReviewUpdate = (
  entry: ApiTermIndexEntry,
  reviewStatus: ApiTermIndexReviewStatus = entry.reviewStatus,
  overrides: Partial<TermIndexReviewUpdate> = {},
): TermIndexReviewUpdate => ({
  reviewStatus,
  reviewReason:
    overrides.reviewReason !== undefined
      ? overrides.reviewReason
      : reviewStatus === 'REJECTED'
        ? (entry.reviewReason ?? 'OTHER')
        : null,
  reviewRationale:
    overrides.reviewRationale !== undefined
      ? overrides.reviewRationale
      : (entry.reviewRationale ?? null),
  reviewConfidence: overrides.reviewConfidence !== undefined ? overrides.reviewConfidence : null,
});

const createReviewDraft = (entry: ApiTermIndexEntry | null): TermIndexReviewDraft => ({
  reviewStatus: entry?.reviewStatus ?? 'TO_REVIEW',
  reviewReason: entry?.reviewReason ?? (entry?.reviewStatus === 'REJECTED' ? 'OTHER' : ''),
  reviewRationale: entry?.reviewRationale ?? '',
  reviewConfidence: entry?.reviewConfidence == null ? '' : String(entry.reviewConfidence),
});

const getReviewDraftUpdate = (draft: TermIndexReviewDraft): TermIndexReviewUpdate => {
  const reviewReason = nullableTrimmed(draft.reviewReason);
  return {
    reviewStatus: draft.reviewStatus,
    reviewReason: draft.reviewStatus === 'REJECTED' ? (reviewReason ?? 'OTHER') : reviewReason,
    reviewRationale: nullableTrimmed(draft.reviewRationale),
    reviewConfidence: parseOptionalConfidence(draft.reviewConfidence),
  };
};

const hasReviewDraftChanged = (draft: TermIndexReviewDraft, entry: ApiTermIndexEntry) =>
  draft.reviewStatus !== entry.reviewStatus ||
  draft.reviewReason !==
    (entry.reviewReason ?? (entry.reviewStatus === 'REJECTED' ? 'OTHER' : '')) ||
  draft.reviewRationale !== (entry.reviewRationale ?? '') ||
  draft.reviewConfidence !== (entry.reviewConfidence == null ? '' : String(entry.reviewConfidence));

const parseOptionalConfidence = (value: string) => {
  const normalized = value.trim();
  if (!normalized) {
    return null;
  }
  const parsed = Number(normalized);
  return Number.isFinite(parsed) ? Math.min(100, Math.max(0, Math.round(parsed))) : null;
};

const createBatchReviewDraft = (): TermIndexBatchReviewDraft => ({
  reviewStatus: 'TO_REVIEW',
  updateReviewReason: false,
  reviewReason: '',
  updateReviewRationale: false,
  reviewRationale: '',
  updateReviewConfidence: false,
  reviewConfidence: '',
});

const getBatchReviewDraftUpdate = (
  draft: TermIndexBatchReviewDraft,
): TermIndexBatchReviewUpdate => {
  const reviewReason = nullableTrimmed(draft.reviewReason);
  return {
    reviewStatus: draft.reviewStatus,
    updateReviewReason: draft.updateReviewReason,
    reviewReason:
      draft.updateReviewReason && draft.reviewStatus === 'REJECTED'
        ? (reviewReason ?? 'OTHER')
        : reviewReason,
    updateReviewRationale: draft.updateReviewRationale,
    reviewRationale: nullableTrimmed(draft.reviewRationale),
    updateReviewConfidence: draft.updateReviewConfidence,
    reviewConfidence: parseOptionalConfidence(draft.reviewConfidence),
  };
};

const cloneCandidateDraft = (draft: CandidateDraft): CandidateDraft => ({
  ...draft,
  editedFields: { ...draft.editedFields },
});

const hasSameRepositoryIds = (left: number[], right: number[]) => {
  if (left.length !== right.length) {
    return false;
  }
  const rightSet = new Set(right);
  return left.every((value) => rightSet.has(value));
};

const getRepositoryTypeSelectionActions = ({
  selectedRepositoryIds,
  productRepositoryIds,
  glossaryRepositoryIds,
  onChangeRepositorySelection,
}: {
  selectedRepositoryIds: number[];
  productRepositoryIds: number[];
  glossaryRepositoryIds: number[];
  onChangeRepositorySelection: (repositoryIds: number[]) => void;
}): MultiSelectCustomAction[] => {
  const selectedProducts =
    productRepositoryIds.length > 0 &&
    hasSameRepositoryIds(selectedRepositoryIds, productRepositoryIds);
  const selectedGlossaries =
    glossaryRepositoryIds.length > 0 &&
    hasSameRepositoryIds(selectedRepositoryIds, glossaryRepositoryIds);

  const actions: Array<MultiSelectCustomAction | null> = [
    selectedProducts
      ? null
      : {
          label: 'Repositories',
          onClick: () => onChangeRepositorySelection(productRepositoryIds),
          disabled: productRepositoryIds.length === 0,
          ariaLabel: 'Show repositories excluding glossaries',
        },
    selectedGlossaries
      ? null
      : {
          label: 'Glossaries',
          onClick: () => onChangeRepositorySelection(glossaryRepositoryIds),
          disabled: glossaryRepositoryIds.length === 0,
          ariaLabel: 'Show glossary repositories only',
        },
  ];

  return actions.filter((action): action is MultiSelectCustomAction => action != null);
};

const getRepositorySelectionSummaryFormatter =
  (allRepositoryIds: number[], productRepositoryIds: number[], glossaryRepositoryIds: number[]) =>
  ({ selectedIds, defaultSummary }: { selectedIds: number[]; defaultSummary: string }) => {
    if (selectedIds.length === 0 || hasSameRepositoryIds(selectedIds, allRepositoryIds)) {
      return 'All repositories';
    }
    if (
      productRepositoryIds.length > 0 &&
      glossaryRepositoryIds.length > 0 &&
      hasSameRepositoryIds(selectedIds, productRepositoryIds)
    ) {
      return 'Repositories';
    }
    if (
      glossaryRepositoryIds.length > 0 &&
      hasSameRepositoryIds(selectedIds, glossaryRepositoryIds)
    ) {
      return 'Glossaries';
    }
    return defaultSummary;
  };

const useTermIndexRepositorySelection = (repositoryOptions: RepositorySelectionOption[]) => {
  const allRepositoryIds = useMemo(() => getRepositoryIds(repositoryOptions), [repositoryOptions]);
  const productRepositoryIds = useMemo(
    () => getProductRepositoryIds(repositoryOptions),
    [repositoryOptions],
  );
  const glossaryRepositoryIds = useMemo(
    () => getGlossaryRepositoryIds(repositoryOptions),
    [repositoryOptions],
  );

  const {
    selectedIds: selectedRepositoryIds,
    hasTouched,
    onChangeSelection: updateSelectedRepositoryIds,
    setSelection,
  } = useRepositorySelection({ options: repositoryOptions });

  useEffect(() => {
    if (!hasTouched && productRepositoryIds.length > 0) {
      setSelection(productRepositoryIds, { touched: false });
    }
  }, [hasTouched, productRepositoryIds, setSelection]);

  const effectiveRepositoryIds =
    selectedRepositoryIds.length > 0 ? selectedRepositoryIds : allRepositoryIds;

  const repositorySelectionActions = useMemo(
    () =>
      getRepositoryTypeSelectionActions({
        selectedRepositoryIds,
        productRepositoryIds,
        glossaryRepositoryIds,
        onChangeRepositorySelection: updateSelectedRepositoryIds,
      }),
    [
      glossaryRepositoryIds,
      productRepositoryIds,
      selectedRepositoryIds,
      updateSelectedRepositoryIds,
    ],
  );

  const formatRepositorySelectionSummary = useMemo(
    () =>
      getRepositorySelectionSummaryFormatter(
        allRepositoryIds,
        productRepositoryIds,
        glossaryRepositoryIds,
      ),
    [allRepositoryIds, glossaryRepositoryIds, productRepositoryIds],
  );

  return {
    allRepositoryIds,
    productRepositoryIds,
    glossaryRepositoryIds,
    selectedRepositoryIds,
    effectiveRepositoryIds,
    repositorySelectionActions,
    formatRepositorySelectionSummary,
    updateSelectedRepositoryIds,
  };
};

export function AdminTermIndexExplorerPage() {
  return <AdminTermIndexAutomationPage />;
}

export function AdminTermIndexAutomationPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();
  const { data: repositories } = useRepositories();
  const repositoryOptions = useRepositorySelectionOptions(repositories ?? []);
  const {
    allRepositoryIds,
    productRepositoryIds,
    selectedRepositoryIds: selectedCursorRepositoryIds,
    effectiveRepositoryIds: effectiveCursorRepositoryIds,
    repositorySelectionActions,
    formatRepositorySelectionSummary,
    updateSelectedRepositoryIds: updateSelectedCursorRepositoryIds,
  } = useTermIndexRepositorySelection(repositoryOptions);
  const automationRepositoryIds =
    productRepositoryIds.length > 0 ? productRepositoryIds : allRepositoryIds;
  const [refreshModalOpen, setRefreshModalOpen] = useState(false);
  const [refreshRepositoryIds, setRefreshRepositoryIds] = useState<number[]>([]);
  const [refreshFullRefresh, setRefreshFullRefresh] = useState(false);
  const [activeRefreshTaskId, setActiveRefreshTaskId] = useState<number | null>(null);
  const [activeTriageTaskId, setActiveTriageTaskId] = useState<number | null>(null);
  const [activeCandidateGenerationTaskId, setActiveCandidateGenerationTaskId] = useState<
    number | null
  >(null);
  const [triageRequest, setTriageRequest] = useState<ExtractedTermTriageRequest | null>(null);
  const [triageReport, setTriageReport] = useState<ApiTriageTermIndexEntriesResponse | null>(null);
  const [triageProgress, setTriageProgress] = useState<ApiTermIndexTaskProgress | null>(null);
  const [triageOverwriteHumanReview, setTriageOverwriteHumanReview] = useState(false);
  const [candidateGenerationRequest, setCandidateGenerationRequest] =
    useState<CandidateGenerationRequest | null>(null);
  const [candidateGenerationReport, setCandidateGenerationReport] =
    useState<ApiGenerateTermIndexCandidatesResponse | null>(null);
  const [runResultLimit, setRunResultLimit] = useState(RUN_RESULT_LIMIT_DEFAULT);
  const [automationTaskRunning, setAutomationTaskRunning] = useState(false);
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; message: string } | null>(null);

  const statusQuery = useQuery({
    queryKey: ['term-index-status', effectiveCursorRepositoryIds, runResultLimit],
    queryFn: () =>
      fetchTermIndexStatus({
        repositoryIds: effectiveCursorRepositoryIds,
        recentRunLimit: runResultLimit,
      }),
    enabled: isAdmin && repositoryOptions.length > 0,
    staleTime: 5_000,
    refetchInterval: (query) => {
      const status = query.state.data;
      const hasRunningRun = status?.recentRuns.some((run) => run.status === 'RUNNING') ?? false;
      const hasRunningJob = status?.recentJobs.some((job) => job.status === 'RUNNING') ?? false;
      return activeRefreshTaskId == null &&
        activeTriageTaskId == null &&
        activeCandidateGenerationTaskId == null &&
        !automationTaskRunning &&
        !hasRunningRun &&
        !hasRunningJob
        ? false
        : 3_000;
    },
  });
  const extractionMethodOptions = useMemo(
    () =>
      (statusQuery.data?.extractionMethods ?? []).map((method) => ({
        value: method,
        label: formatMethod(method),
      })),
    [statusQuery.data?.extractionMethods],
  );
  const updateRunResultLimit = (next: number) => {
    setRunResultLimit(Math.min(Math.max(1, next), RUN_RESULT_LIMIT_MAX));
  };
  const recentRunCount = statusQuery.data?.recentRuns.length ?? 0;
  const recentRunCountLabel = statusQuery.isLoading
    ? 'Loading extraction runs...'
    : formatCappedListLabel('extraction runs', recentRunCount, runResultLimit);

  useEffect(() => {
    const recentJobs = statusQuery.data?.recentJobs ?? [];
    if (activeTriageTaskId != null) {
      const activeJob = recentJobs.find((job) => job.pollableTaskId === activeTriageTaskId);
      if (activeJob && activeJob.status !== 'RUNNING') {
        setActiveTriageTaskId(null);
      }
    }
    if (activeCandidateGenerationTaskId != null) {
      const activeJob = recentJobs.find(
        (job) => job.pollableTaskId === activeCandidateGenerationTaskId,
      );
      if (activeJob && activeJob.status !== 'RUNNING') {
        setActiveCandidateGenerationTaskId(null);
      }
    }
  }, [activeCandidateGenerationTaskId, activeTriageTaskId, statusQuery.data?.recentJobs]);

  const refreshMutation = useMutation({
    mutationFn: async (request: TermIndexRefreshForm) => {
      const startedTask = await startTermIndexRefresh({
        repositoryIds: request.repositoryIds,
        fullRefresh: request.fullRefresh,
        batchSize: DEFAULT_BATCH_SIZE,
        excludeGlossaryRepositories: request.excludeGlossaryRepositories,
      });
      setActiveRefreshTaskId(startedTask.id);
      return waitForTermIndexRefreshTask(startedTask.id);
    },
    onSuccess: async () => {
      setActiveRefreshTaskId(null);
      setNotice(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['term-index-status'] }),
        queryClient.invalidateQueries({ queryKey: ['term-index-entries'] }),
        queryClient.invalidateQueries({ queryKey: ['term-index-occurrences'] }),
      ]);
    },
    onError: (error: Error) => {
      setActiveRefreshTaskId(null);
      setNotice({ kind: 'error', message: error.message || 'Term extraction failed.' });
    },
  });
  const triageMutation = useMutation({
    mutationFn: ({
      request,
      overwriteHumanReview,
    }: {
      request: ExtractedTermTriageRequest;
      overwriteHumanReview: boolean;
    }) =>
      startTriageTermIndexEntries({
        termIndexEntryIds: [],
        repositoryIds: automationRepositoryIds,
        search: request.searchQuery ?? null,
        extractionMethod: request.extractionMethod ?? null,
        reviewStatus: request.reviewStatusFilter ?? 'NON_REJECTED',
        reviewAuthority: request.reviewAuthorityFilter ?? 'NONE',
        minOccurrences: request.minOccurrences ?? MIN_OCCURRENCES_DEFAULT,
        limit: request.limit ?? TERM_RESULT_LIMIT_DEFAULT,
        lastOccurrenceAfter: request.lastOccurrenceAfter ?? null,
        lastOccurrenceBefore: request.lastOccurrenceBefore ?? null,
        reviewChangedAfter: request.reviewChangedAfter ?? null,
        reviewChangedBefore: request.reviewChangedBefore ?? null,
        overwriteHumanReview,
      }),
    onMutate: () => {
      setAutomationTaskRunning(true);
    },
    onSuccess: async (startedTask) => {
      setActiveTriageTaskId(startedTask.id);
      setTriageRequest(null);
      setTriageReport(null);
      setTriageProgress(null);
      setNotice(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['term-index-status'] }),
        queryClient.invalidateQueries({ queryKey: ['term-index-entries'] }),
      ]);
    },
    onError: (error: Error) => {
      setActiveTriageTaskId(null);
      setTriageProgress(null);
      setNotice({ kind: 'error', message: error.message || 'Extracted term triage failed.' });
    },
    onSettled: () => {
      setAutomationTaskRunning(false);
    },
  });
  const generateCandidatesMutation = useMutation({
    mutationFn: ({ request }: { request: CandidateGenerationRequest }) =>
      startGenerateTermIndexCandidatesFromEntries({
        termIndexEntryIds: [],
        repositoryIds: automationRepositoryIds,
        search: request.searchQuery ?? null,
        extractionMethod: request.extractionMethod ?? null,
        termIndexReviewStatus: request.reviewStatusFilter ?? 'ACCEPTED',
        termIndexReviewAuthority: request.reviewAuthorityFilter ?? 'ALL',
        minOccurrences: request.minOccurrences ?? MIN_OCCURRENCES_DEFAULT,
        limit: request.limit ?? TERM_RESULT_LIMIT_DEFAULT,
        lastOccurrenceAfter: request.lastOccurrenceAfter ?? null,
        lastOccurrenceBefore: request.lastOccurrenceBefore ?? null,
        reviewChangedAfter: request.reviewChangedAfter ?? null,
        reviewChangedBefore: request.reviewChangedBefore ?? null,
        skipExistingCandidates: request.refreshExistingCandidates !== true,
      }),
    onMutate: () => {
      setAutomationTaskRunning(true);
    },
    onSuccess: async (startedTask) => {
      setActiveCandidateGenerationTaskId(startedTask.id);
      setCandidateGenerationRequest(null);
      setCandidateGenerationReport(null);
      setNotice(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['term-index-status'] }),
        queryClient.invalidateQueries({ queryKey: ['term-index-entries'] }),
      ]);
    },
    onError: (error: Error) => {
      setActiveCandidateGenerationTaskId(null);
      setNotice({ kind: 'error', message: error.message || 'Candidate generation failed.' });
    },
    onSettled: () => {
      setAutomationTaskRunning(false);
    },
  });

  const openRefreshModal = () => {
    setNotice(null);
    setRefreshRepositoryIds(productRepositoryIds);
    setRefreshFullRefresh(false);
    setRefreshModalOpen(true);
  };

  const startRefresh = () => {
    const repositoryIds = refreshRepositoryIds.length > 0 ? refreshRepositoryIds : allRepositoryIds;
    if (repositoryIds.length === 0 || refreshMutation.isPending) {
      return;
    }

    setNotice(null);
    setRefreshModalOpen(false);
    refreshMutation.mutate({
      repositoryIds,
      fullRefresh: refreshFullRefresh,
      excludeGlossaryRepositories: hasSameRepositoryIds(repositoryIds, productRepositoryIds),
    });
  };
  const openTriageModal = () => {
    setNotice(null);
    setTriageReport(null);
    setTriageProgress(null);
    setTriageOverwriteHumanReview(false);
    setTriageRequest({
      mode: 'filter',
      entryIds: [],
      entries: [],
      searchQuery: '',
      extractionMethod: null,
      reviewStatusFilter: 'NON_REJECTED',
      reviewAuthorityFilter: 'NONE',
      minOccurrences: MIN_OCCURRENCES_DEFAULT,
      limit: TERM_RESULT_LIMIT_DEFAULT,
      lastOccurrenceAfter: null,
      lastOccurrenceBefore: null,
      reviewChangedAfter: null,
      reviewChangedBefore: null,
    });
  };
  const closeTriageModal = () => {
    if (!triageMutation.isPending) {
      setTriageRequest(null);
      setTriageReport(null);
      setTriageProgress(null);
    }
  };
  const confirmTriage = () => {
    if (!triageRequest || triageMutation.isPending) {
      return;
    }
    setNotice(null);
    setTriageReport(null);
    setTriageProgress(null);
    triageMutation.mutate({
      request: triageRequest,
      overwriteHumanReview: triageOverwriteHumanReview,
    });
  };
  const openCandidateGenerationModal = () => {
    setNotice(null);
    setCandidateGenerationReport(null);
    setCandidateGenerationRequest({
      mode: 'filter',
      entryIds: [],
      entries: [],
      draft: null,
      searchQuery: '',
      extractionMethod: null,
      minOccurrences: MIN_OCCURRENCES_DEFAULT,
      limit: TERM_RESULT_LIMIT_DEFAULT,
      lastOccurrenceAfter: null,
      lastOccurrenceBefore: null,
      reviewChangedAfter: null,
      reviewChangedBefore: null,
      reviewStatusFilter: 'ACCEPTED',
      reviewAuthorityFilter: 'ALL',
      refreshExistingCandidates: false,
    });
  };
  const closeCandidateGenerationModal = () => {
    if (!generateCandidatesMutation.isPending) {
      setCandidateGenerationRequest(null);
      setCandidateGenerationReport(null);
    }
  };
  const confirmCandidateGeneration = () => {
    if (!candidateGenerationRequest || generateCandidatesMutation.isPending) {
      return;
    }
    setNotice(null);
    setCandidateGenerationReport(null);
    generateCandidatesMutation.mutate({
      request: candidateGenerationRequest,
    });
  };

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
        context="Settings > Glossaries"
        title="Glossary Automation"
        centerContent={<TermIndexSubnav active="automation" />}
      />
      <div className="settings-page settings-page--wide term-index-explorer">
        {notice ? (
          <section
            className="settings-card term-index-explorer__notice-card"
            aria-label="Glossary automation status"
          >
            <p className={`settings-hint${notice.kind === 'error' ? ' is-error' : ''}`}>
              {notice.message}
            </p>
          </section>
        ) : null}
        <section
          className="term-index-explorer__automation-workflow"
          aria-label="Automation workflow"
        >
          <AutomationActionCard
            title="Extract terms"
            description="Scan repositories and update raw extracted terms with occurrence evidence."
            cta={refreshMutation.isPending ? 'Extracting' : 'Extract terms'}
            disabled={refreshMutation.isPending}
            onClick={openRefreshModal}
            linkTo="/settings/system/glossary-term-index/terms"
            linkLabel="Inspect extracted terms"
          >
            <ExtractionStatusTables
              statusQuery={statusQuery}
              activeRefreshTaskId={activeRefreshTaskId}
              repositoryOptions={repositoryOptions}
              selectedRepositoryIds={selectedCursorRepositoryIds}
              repositorySelectionActions={repositorySelectionActions}
              formatRepositorySelectionSummary={formatRepositorySelectionSummary}
              onChangeRepositorySelection={updateSelectedCursorRepositoryIds}
              runResultLimit={runResultLimit}
              runCountLabel={recentRunCountLabel}
              onChangeRunResultLimit={updateRunResultLimit}
            />
          </AutomationActionCard>
          <AutomationActionCard
            title="Review extracted terms"
            description="Ask AI to classify unreviewed extracted terms before candidate generation."
            cta={
              triageMutation.isPending || activeTriageTaskId != null ? 'Reviewing' : 'Run AI review'
            }
            disabled={triageMutation.isPending || activeTriageTaskId != null}
            onClick={openTriageModal}
            linkTo="/settings/system/glossary-term-index/terms"
            linkLabel="Manual term review"
          >
            <AutomationJobsTable
              statusQuery={statusQuery}
              title="Recent review extracted jobs"
              emptyLabel="No review extracted jobs yet."
              jobNames={['REVIEW_EXTRACTED_TERMS']}
            />
          </AutomationActionCard>
          <AutomationActionCard
            title="Generate candidates"
            description="Create missing candidate proposals from accepted extracted terms."
            cta={
              generateCandidatesMutation.isPending || activeCandidateGenerationTaskId != null
                ? 'Generating'
                : 'Generate missing candidates'
            }
            disabled={
              generateCandidatesMutation.isPending || activeCandidateGenerationTaskId != null
            }
            onClick={openCandidateGenerationModal}
            linkTo="/settings/system/glossary-term-index/candidates"
            linkLabel="Review candidates"
          >
            <AutomationJobsTable
              statusQuery={statusQuery}
              title="Recent candidate generation jobs"
              emptyLabel="No candidate generation jobs yet."
              jobNames={['GENERATE_CANDIDATES']}
            />
          </AutomationActionCard>
        </section>
      </div>
      <TermIndexRefreshModal
        open={refreshModalOpen}
        repositoryOptions={repositoryOptions}
        selectedRepositoryIds={refreshRepositoryIds}
        fullRefresh={refreshFullRefresh}
        isRefreshing={refreshMutation.isPending}
        onRepositoryChange={setRefreshRepositoryIds}
        onFullRefreshChange={setRefreshFullRefresh}
        onClose={() => setRefreshModalOpen(false)}
        onStart={startRefresh}
      />
      <ExtractedTermTriageModal
        open={triageRequest != null}
        request={triageRequest}
        report={triageReport}
        progress={triageProgress}
        repositoryScopeLabel={formatRepositorySelectionSummary({
          selectedIds: automationRepositoryIds,
          defaultSummary:
            automationRepositoryIds.length === 1
              ? '1 repository'
              : `${automationRepositoryIds.length.toLocaleString()} repositories`,
        })}
        searchQuery=""
        extractionMethod={null}
        extractionMethodOptions={extractionMethodOptions}
        reviewStatusFilter={triageRequest?.reviewStatusFilter ?? 'NON_REJECTED'}
        reviewAuthorityFilter={triageRequest?.reviewAuthorityFilter ?? 'NONE'}
        minOccurrences={MIN_OCCURRENCES_DEFAULT}
        limit={triageRequest?.limit ?? TERM_RESULT_LIMIT_DEFAULT}
        lastOccurrenceAfter={null}
        lastOccurrenceBefore={null}
        reviewChangedAfter={null}
        reviewChangedBefore={null}
        overwriteHumanReview={triageOverwriteHumanReview}
        isReviewing={triageMutation.isPending}
        error={triageMutation.error}
        onRequestChange={setTriageRequest}
        onOverwriteHumanReviewChange={setTriageOverwriteHumanReview}
        onClose={closeTriageModal}
        onTriage={confirmTriage}
      />
      <CandidateGenerationModal
        open={candidateGenerationRequest != null}
        request={candidateGenerationRequest}
        report={candidateGenerationReport}
        repositoryScopeLabel={formatRepositorySelectionSummary({
          selectedIds: automationRepositoryIds,
          defaultSummary:
            automationRepositoryIds.length === 1
              ? '1 repository'
              : `${automationRepositoryIds.length.toLocaleString()} repositories`,
        })}
        searchQuery=""
        extractionMethod={null}
        reviewStatusFilter={candidateGenerationRequest?.reviewStatusFilter ?? 'ACCEPTED'}
        reviewAuthorityFilter={candidateGenerationRequest?.reviewAuthorityFilter ?? 'ALL'}
        isGenerating={generateCandidatesMutation.isPending}
        error={generateCandidatesMutation.error}
        onClose={closeCandidateGenerationModal}
        onRequestChange={setCandidateGenerationRequest}
        onGenerate={confirmCandidateGeneration}
      />
    </div>
  );
}

export function AdminTermIndexTermsPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();
  const [searchParams] = useSearchParams();
  const requestedSearchParam = searchParams.get('search');
  const requestedEntryIdParam = searchParams.get('entryId');
  const initialSearchQuery = requestedSearchParam?.trim() ?? '';
  const initialSelectedEntryId = parsePositiveIntegerParam(requestedEntryIdParam);
  const { data: repositories } = useRepositories();
  const repositoryOptions = useRepositorySelectionOptions(repositories ?? []);
  const {
    selectedRepositoryIds,
    effectiveRepositoryIds,
    repositorySelectionActions,
    formatRepositorySelectionSummary,
    updateSelectedRepositoryIds,
  } = useTermIndexRepositorySelection(repositoryOptions);
  const [searchQuery, setSearchQuery] = useState(initialSearchQuery);
  const [minOccurrences, setMinOccurrences] = useState(MIN_OCCURRENCES_DEFAULT);
  const [extractionMethod, setExtractionMethod] = useState<string | null>(null);
  const [reviewStatusFilter, setReviewStatusFilter] =
    useState<ApiTermIndexReviewStatusFilter>('NON_REJECTED');
  const [reviewAuthorityFilter, setReviewAuthorityFilter] =
    useState<ApiTermIndexReviewAuthorityFilter>('ALL');
  const [termSort, setTermSort] = useState<ApiTermIndexEntrySort>('REVIEW_CONFIDENCE_DESC');
  const [termResultLimit, setTermResultLimit] = useState(TERM_RESULT_LIMIT_DEFAULT);
  const [lastOccurrenceAfter, setLastOccurrenceAfter] = useState<string | null>(null);
  const [lastOccurrenceBefore, setLastOccurrenceBefore] = useState<string | null>(null);
  const [reviewChangedAfter, setReviewChangedAfter] = useState<string | null>(null);
  const [reviewChangedBefore, setReviewChangedBefore] = useState<string | null>(null);
  const [selectedEntryId, setSelectedEntryId] = useState<number | null>(initialSelectedEntryId);
  const [selectedEntryIds, setSelectedEntryIds] = useState<number[]>([]);
  const [triageRequest, setTriageRequest] = useState<ExtractedTermTriageRequest | null>(null);
  const [triageReport, setTriageReport] = useState<ApiTriageTermIndexEntriesResponse | null>(null);
  const [triageProgress, setTriageProgress] = useState<ApiTermIndexTaskProgress | null>(null);
  const [triageOverwriteHumanReview, setTriageOverwriteHumanReview] = useState(false);
  const [reviewOverrideEntry, setReviewOverrideEntry] = useState<ApiTermIndexEntry | null>(null);
  const [bulkReviewModalOpen, setBulkReviewModalOpen] = useState(false);
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; message: string } | null>(null);
  const [termPaneWidthPct, setTermPaneWidthPct] = useState(DEFAULT_TERM_PANE_WIDTH_PCT);
  const [isPaneResizing, setIsPaneResizing] = useState(false);
  const workspaceRef = useRef<HTMLDivElement>(null);

  const resetTermSelection = () => {
    setSelectedEntryId(null);
    setSelectedEntryIds([]);
    setNotice(null);
  };
  const updateTermPaneWidthPct = useCallback((next: number) => {
    setTermPaneWidthPct(Math.min(75, Math.max(25, next)));
  }, []);
  const updateTermResultLimit = (next: number) => {
    setTermResultLimit(Math.min(Math.max(1, next), TERM_RESULT_LIMIT_MAX));
    resetTermSelection();
  };
  const updateMinOccurrences = (next: number) => {
    setMinOccurrences(Math.max(1, next));
    resetTermSelection();
  };
  const updateLastOccurrenceAfter = (next: string | null) => {
    setLastOccurrenceAfter(next);
    resetTermSelection();
  };
  const updateLastOccurrenceBefore = (next: string | null) => {
    setLastOccurrenceBefore(next);
    resetTermSelection();
  };
  const updateReviewChangedAfter = (next: string | null) => {
    setReviewChangedAfter(next);
    resetTermSelection();
  };
  const updateReviewChangedBefore = (next: string | null) => {
    setReviewChangedBefore(next);
    resetTermSelection();
  };

  useEffect(() => {
    setSearchQuery(requestedSearchParam?.trim() ?? '');
    setSelectedEntryId(parsePositiveIntegerParam(requestedEntryIdParam));
    setSelectedEntryIds([]);
  }, [requestedEntryIdParam, requestedSearchParam]);

  const statusQuery = useQuery({
    queryKey: ['term-index-status', effectiveRepositoryIds],
    queryFn: () => fetchTermIndexStatus({ repositoryIds: effectiveRepositoryIds }),
    enabled: isAdmin && repositoryOptions.length > 0,
    staleTime: 30_000,
  });

  const entriesQuery = useQuery({
    queryKey: [
      'term-index-entries',
      effectiveRepositoryIds,
      searchQuery,
      extractionMethod,
      reviewStatusFilter,
      reviewAuthorityFilter,
      termSort,
      minOccurrences,
      termResultLimit,
      lastOccurrenceAfter,
      lastOccurrenceBefore,
      reviewChangedAfter,
      reviewChangedBefore,
    ],
    queryFn: () =>
      fetchTermIndexEntries({
        repositoryIds: effectiveRepositoryIds,
        search: searchQuery,
        extractionMethod,
        reviewStatus: reviewStatusFilter,
        reviewAuthority: reviewAuthorityFilter,
        minOccurrences,
        limit: termResultLimit,
        lastOccurrenceAfter,
        lastOccurrenceBefore,
        reviewChangedAfter,
        reviewChangedBefore,
        sortBy: termSort,
      }),
    enabled: isAdmin && repositoryOptions.length > 0,
    staleTime: 5_000,
  });

  const entryData = entriesQuery.data;
  const entries = useMemo(() => entryData?.entries ?? [], [entryData]);
  const selectedEntry = useMemo(
    () => entries.find((entry) => entry.id === selectedEntryId) ?? null,
    [entries, selectedEntryId],
  );
  const selectedEntries = useMemo(
    () => entries.filter((entry) => selectedEntryIds.includes(entry.id)),
    [entries, selectedEntryIds],
  );
  const allEntriesSelected =
    entries.length > 0 && entries.every((entry) => selectedEntryIds.includes(entry.id));

  useEffect(() => {
    if (entries.length === 0) {
      if (!entriesQuery.isFetching) {
        setSelectedEntryId(null);
      }
      return;
    }
    if (selectedEntryId == null || !entries.some((entry) => entry.id === selectedEntryId)) {
      setSelectedEntryId(entries[0].id);
    }
  }, [entries, entriesQuery.isFetching, selectedEntryId]);

  useEffect(() => {
    const visibleEntryIds = new Set(entries.map((entry) => entry.id));
    setSelectedEntryIds((current) => current.filter((entryId) => visibleEntryIds.has(entryId)));
  }, [entries]);

  const occurrencesQuery = useQuery({
    queryKey: ['term-index-occurrences', selectedEntryId, effectiveRepositoryIds, extractionMethod],
    queryFn: () =>
      fetchTermIndexOccurrences(selectedEntryId ?? 0, {
        repositoryIds: effectiveRepositoryIds,
        extractionMethod,
        limit: EXAMPLE_RESULT_LIMIT,
      }),
    enabled: isAdmin && selectedEntryId != null && repositoryOptions.length > 0,
    staleTime: 5_000,
  });

  const occurrenceData = occurrencesQuery.data;
  const occurrences = occurrenceData?.occurrences ?? [];

  const updateReviewMutation = useMutation({
    mutationFn: ({
      entryId,
      reviewStatus,
      reviewReason,
      reviewRationale,
      reviewConfidence,
    }: {
      entryId: number;
      reviewStatus: ApiTermIndexReviewStatus;
      reviewReason?: string | null;
      reviewRationale?: string | null;
      reviewConfidence?: number | null;
    }) =>
      updateTermIndexEntryReview(entryId, {
        reviewStatus,
        reviewReason: reviewReason ?? null,
        reviewRationale: reviewRationale ?? null,
        reviewConfidence: reviewConfidence ?? null,
      }),
    onSuccess: async (entry) => {
      setSelectedEntryId(entry.id);
      setReviewOverrideEntry(null);
      await queryClient.invalidateQueries({ queryKey: ['term-index-entries'] });
    },
  });

  const batchUpdateReviewMutation = useMutation({
    mutationFn: ({
      entryIds,
      reviewStatus,
      updateReviewReason,
      reviewReason,
      updateReviewRationale,
      reviewRationale,
      updateReviewConfidence,
      reviewConfidence,
    }: {
      entryIds: number[];
      reviewStatus: ApiTermIndexReviewStatus;
      updateReviewReason?: boolean;
      reviewReason?: string | null;
      updateReviewRationale?: boolean;
      reviewRationale?: string | null;
      updateReviewConfidence?: boolean;
      reviewConfidence?: number | null;
    }) =>
      updateTermIndexEntryReviews(entryIds, {
        reviewStatus,
        updateReviewReason: updateReviewReason ?? false,
        reviewReason: reviewReason ?? null,
        updateReviewRationale: updateReviewRationale ?? false,
        reviewRationale: reviewRationale ?? null,
        updateReviewConfidence: updateReviewConfidence ?? false,
        reviewConfidence: reviewConfidence ?? null,
      }),
    onSuccess: async () => {
      setSelectedEntryIds([]);
      setBulkReviewModalOpen(false);
      await queryClient.invalidateQueries({ queryKey: ['term-index-entries'] });
    },
  });

  const triageMutation = useMutation({
    mutationFn: ({
      request,
      overwriteHumanReview,
    }: {
      request: ExtractedTermTriageRequest;
      overwriteHumanReview: boolean;
    }) =>
      triageTermIndexEntries(
        {
          termIndexEntryIds: request.mode === 'selected' ? request.entryIds : [],
          repositoryIds: effectiveRepositoryIds,
          search: request.mode === 'filter' ? (request.searchQuery ?? null) : null,
          extractionMethod: request.mode === 'filter' ? (request.extractionMethod ?? null) : null,
          reviewStatus:
            request.mode === 'filter' ? (request.reviewStatusFilter ?? 'TO_REVIEW') : null,
          reviewAuthority:
            request.mode === 'filter' ? (request.reviewAuthorityFilter ?? 'NONE') : null,
          minOccurrences:
            request.mode === 'filter' ? (request.minOccurrences ?? MIN_OCCURRENCES_DEFAULT) : null,
          limit: request.mode === 'filter' ? (request.limit ?? TERM_RESULT_LIMIT_DEFAULT) : null,
          lastOccurrenceAfter:
            request.mode === 'filter' ? (request.lastOccurrenceAfter ?? null) : null,
          lastOccurrenceBefore:
            request.mode === 'filter' ? (request.lastOccurrenceBefore ?? null) : null,
          reviewChangedAfter:
            request.mode === 'filter' ? (request.reviewChangedAfter ?? null) : null,
          reviewChangedBefore:
            request.mode === 'filter' ? (request.reviewChangedBefore ?? null) : null,
          overwriteHumanReview,
        },
        {
          onProgress: setTriageProgress,
        },
      ),
    onSuccess: async (report) => {
      setTriageReport(report);
      setTriageProgress(null);
      setNotice({
        kind: 'success',
        message: `Reviewed ${report.reviewedEntryCount.toLocaleString()} extracted terms (${report.rejectedCount.toLocaleString()} rejected).`,
      });
      setSelectedEntryIds([]);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['term-index-entries'] }),
        queryClient.invalidateQueries({ queryKey: ['term-index-occurrences'] }),
      ]);
    },
    onError: (error: Error) => {
      setTriageProgress(null);
      setNotice({ kind: 'error', message: error.message || 'Extracted term triage failed.' });
    },
  });

  const updateEntryReview = (entry: ApiTermIndexEntry, reviewStatus: ApiTermIndexReviewStatus) => {
    if (updateReviewMutation.isPending) {
      return;
    }
    updateReviewMutation.mutate({
      entryId: entry.id,
      ...getManualReviewUpdate(entry, reviewStatus),
    });
  };

  const saveEntryReviewOverride = (entry: ApiTermIndexEntry, update: TermIndexReviewUpdate) => {
    if (updateReviewMutation.isPending) {
      return;
    }
    updateReviewMutation.mutate({
      entryId: entry.id,
      ...update,
    });
  };

  const openReviewOverride = (entry: ApiTermIndexEntry) => {
    updateReviewMutation.reset();
    setReviewOverrideEntry(entry);
  };

  const closeReviewOverride = () => {
    updateReviewMutation.reset();
    setReviewOverrideEntry(null);
  };

  const saveSelectedEntriesReview = (update: TermIndexBatchReviewUpdate) => {
    if (selectedEntryIds.length === 0 || batchUpdateReviewMutation.isPending) {
      return;
    }
    batchUpdateReviewMutation.mutate({
      entryIds: selectedEntryIds,
      ...update,
    });
  };

  const openTriageModal = () => {
    if (selectedEntries.length === 0 || triageMutation.isPending) {
      return;
    }
    setNotice(null);
    setTriageReport(null);
    setTriageProgress(null);
    setTriageOverwriteHumanReview(false);
    setTriageRequest({
      mode: 'selected',
      entryIds: selectedEntries.map((entry) => entry.id),
      entries: selectedEntries,
    });
  };

  const runBulkReviewAction = (action: TermIndexBulkReviewAction | null) => {
    if (!action || selectedEntries.length === 0) {
      return;
    }
    if (action === 'MANUAL_REVIEW') {
      setBulkReviewModalOpen(true);
      return;
    }
    openTriageModal();
  };

  const closeTriageModal = () => {
    if (triageMutation.isPending) {
      return;
    }
    setTriageRequest(null);
    setTriageReport(null);
    setTriageProgress(null);
  };

  const confirmTriage = () => {
    if (!triageRequest || triageMutation.isPending) {
      return;
    }
    setNotice(null);
    setTriageReport(null);
    setTriageProgress(null);
    triageMutation.mutate({
      request: triageRequest,
      overwriteHumanReview: triageOverwriteHumanReview,
    });
  };

  const extractionMethodOptions = useMemo(
    () =>
      (statusQuery.data?.extractionMethods ?? []).map((method) => ({
        value: method,
        label: formatMethod(method),
      })),
    [statusQuery.data?.extractionMethods],
  );
  const termCountLabel = entriesQuery.isLoading
    ? 'Terms'
    : formatCappedListLabel('terms', entries.length, termResultLimit);
  const showWorkspaceLoading = entriesQuery.isFetching && !entriesQuery.isError;
  const termDateQuickRanges = useMemo(() => getStandardDateQuickRanges(), []);
  const workspaceStyle = useMemo(
    () =>
      ({
        '--term-index-left-pane-width': `${termPaneWidthPct}%`,
      }) as CSSProperties,
    [termPaneWidthPct],
  );
  const startPaneResize = useCallback(
    (event: ReactMouseEvent<HTMLDivElement>) => {
      event.preventDefault();
      setIsPaneResizing(true);

      const handleMove = (moveEvent: MouseEvent) => {
        if (!workspaceRef.current) {
          return;
        }
        const rect = workspaceRef.current.getBoundingClientRect();
        updateTermPaneWidthPct(((moveEvent.clientX - rect.left) / rect.width) * 100);
      };
      const handleUp = () => {
        setIsPaneResizing(false);
        window.removeEventListener('mousemove', handleMove);
        window.removeEventListener('mouseup', handleUp);
      };

      window.addEventListener('mousemove', handleMove);
      window.addEventListener('mouseup', handleUp);
    },
    [updateTermPaneWidthPct],
  );
  const handlePaneDividerKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      let nextWidth: number | null = null;
      if (event.key === 'ArrowLeft') {
        nextWidth = termPaneWidthPct - 5;
      } else if (event.key === 'ArrowRight') {
        nextWidth = termPaneWidthPct + 5;
      } else if (event.key === 'Home') {
        nextWidth = 25;
      } else if (event.key === 'End') {
        nextWidth = 75;
      }

      if (nextWidth == null) {
        return;
      }

      event.preventDefault();
      updateTermPaneWidthPct(nextWidth);
    },
    [termPaneWidthPct, updateTermPaneWidthPct],
  );

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  return (
    <div className="settings-subpage term-index-explorer__page">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
        context="Settings > Glossaries"
        title="Extracted Terms"
        centerContent={<TermIndexSubnav active="terms" />}
      />
      <div className="settings-page settings-page--wide term-index-explorer">
        <section
          className="settings-card term-index-explorer__filter-card"
          aria-label="Term filters"
        >
          <div className="term-index-explorer__filterbar">
            <div className="term-index-explorer__repository-control">
              <RepositoryMultiSelect
                label="Repositories"
                options={repositoryOptions}
                selectedIds={selectedRepositoryIds}
                onChange={(next) => {
                  updateSelectedRepositoryIds(next);
                  resetTermSelection();
                }}
                className="term-index-explorer__repository-select"
                buttonAriaLabel="Select repositories for term review. Use menu actions to switch between repositories and glossaries."
                customActions={repositorySelectionActions}
                summaryFormatter={formatRepositorySelectionSummary}
              />
            </div>
            <SearchControl
              inputId="term-index-term-search"
              value={searchQuery}
              onChange={(next) => {
                setSearchQuery(next);
                resetTermSelection();
              }}
              placeholder="Search terms or normalized keys"
              inputAriaLabel="Search raw terms"
              className="term-index-explorer__search-control"
            />
            <div className="term-index-explorer__filter-controls">
              <TermIndexReviewFilterChip
                summaryPrefix="Extracted term review"
                reviewStatus={reviewStatusFilter}
                reviewAuthority={reviewAuthorityFilter}
                onReviewStatusChange={(next) => {
                  setReviewStatusFilter(next);
                  resetTermSelection();
                }}
                onReviewAuthorityChange={(next) => {
                  setReviewAuthorityFilter(next);
                  resetTermSelection();
                }}
                disabled={entriesQuery.isLoading}
              />
              <TermIndexQueryFilterChip
                extractionMethod={extractionMethod}
                extractionMethodOptions={extractionMethodOptions}
                onExtractionMethodChange={(next) => {
                  setExtractionMethod(next);
                  resetTermSelection();
                }}
                minOccurrences={minOccurrences}
                onMinOccurrencesChange={updateMinOccurrences}
                lastOccurrenceAfter={lastOccurrenceAfter}
                lastOccurrenceBefore={lastOccurrenceBefore}
                onLastOccurrenceAfterChange={updateLastOccurrenceAfter}
                onLastOccurrenceBeforeChange={updateLastOccurrenceBefore}
                reviewChangedAfter={reviewChangedAfter}
                reviewChangedBefore={reviewChangedBefore}
                onReviewChangedAfterChange={updateReviewChangedAfter}
                onReviewChangedBeforeChange={updateReviewChangedBefore}
                dateQuickRanges={termDateQuickRanges}
                disabled={entriesQuery.isLoading}
                ariaLabel="Filter raw term extractor, hits, last extracted date, and review updated date"
              />
            </div>
          </div>
        </section>

        <section className="settings-card term-index-explorer__results-card" aria-label="Raw terms">
          <div className="term-index-explorer__subbar">
            <TermIndexResultSizeDropdown
              countLabel={termCountLabel}
              resultLimit={termResultLimit}
              onChangeResultLimit={updateTermResultLimit}
              disabled={entriesQuery.isLoading}
            />
            <TermIndexSubbarSeparator />
            <TermIndexSortDropdown
              sortBy={termSort}
              onChangeSort={(next) => {
                setTermSort(next);
                resetTermSelection();
              }}
              disabled={entriesQuery.isLoading}
            />
            <div className="term-index-explorer__subbar-actions">
              {entries.length > 0 && !allEntriesSelected ? (
                <>
                  <TermIndexSubbarSeparator />
                  <button
                    type="button"
                    className="term-index-explorer__subbar-button"
                    onClick={() => setSelectedEntryIds(entries.map((entry) => entry.id))}
                    disabled={batchUpdateReviewMutation.isPending || triageMutation.isPending}
                  >
                    Select all
                  </button>
                </>
              ) : null}
              {selectedEntries.length > 0 ? (
                <>
                  <TermIndexSubbarSeparator />
                  <span className="settings-hint term-index-explorer__selection-summary">
                    {selectedEntries.length.toLocaleString()} selected
                  </span>
                  <TermIndexSubbarSeparator />
                  <SingleSelectDropdown
                    label="Bulk update"
                    options={TERM_INDEX_BULK_REVIEW_OPTIONS}
                    value={null}
                    onChange={runBulkReviewAction}
                    placeholder={triageMutation.isPending ? 'AI review running' : 'Bulk update'}
                    className="term-index-explorer__bulk-dropdown"
                    buttonAriaLabel="Bulk update selected extracted terms"
                    searchable={false}
                    disabled={batchUpdateReviewMutation.isPending || triageMutation.isPending}
                  />
                  <TermIndexSubbarSeparator />
                  <button
                    type="button"
                    className="term-index-explorer__subbar-button"
                    onClick={() => setSelectedEntryIds([])}
                    disabled={batchUpdateReviewMutation.isPending || triageMutation.isPending}
                  >
                    Clear selection
                  </button>
                </>
              ) : null}
            </div>
          </div>
          {notice ? (
            <p className={`settings-hint${notice.kind === 'error' ? ' is-error' : ''}`}>
              {notice.message}
            </p>
          ) : null}
          {batchUpdateReviewMutation.error ? (
            <p className="settings-hint is-error">
              {getErrorMessage(batchUpdateReviewMutation.error)}
            </p>
          ) : null}
          {updateReviewMutation.error ? (
            <p className="settings-hint is-error">{getErrorMessage(updateReviewMutation.error)}</p>
          ) : null}
          <div
            className={`term-index-explorer__workspace${showWorkspaceLoading ? ' is-loading' : ''}`}
            ref={workspaceRef}
            style={workspaceStyle}
          >
            <div className="term-index-explorer__terms">
              {entriesQuery.isLoading ? null : entriesQuery.isError ? (
                <p className="settings-hint is-error">{getErrorMessage(entriesQuery.error)}</p>
              ) : entries.length === 0 ? (
                <p className="settings-hint">No indexed terms match the current filters.</p>
              ) : (
                <TermList
                  entries={entries}
                  selectedEntryId={selectedEntryId}
                  selectedEntryIds={selectedEntryIds}
                  rowMetadata="term"
                  showReviewConfidence
                  onSelectEntry={setSelectedEntryId}
                  onUpdateEntryReview={updateEntryReview}
                  isUpdatingReview={updateReviewMutation.isPending}
                  onToggleEntrySelection={(entryId, checked) =>
                    setSelectedEntryIds((current) =>
                      checked
                        ? Array.from(new Set([...current, entryId]))
                        : current.filter((id) => id !== entryId),
                    )
                  }
                />
              )}
            </div>
            <div
              className={`term-index-explorer__pane-divider${isPaneResizing ? ' is-resizing' : ''}`}
              onMouseDown={startPaneResize}
              onDoubleClick={() => updateTermPaneWidthPct(DEFAULT_TERM_PANE_WIDTH_PCT)}
              onKeyDown={handlePaneDividerKeyDown}
              role="separator"
              tabIndex={0}
              aria-label="Resize term and example panes"
              aria-orientation="vertical"
              aria-valuemin={25}
              aria-valuemax={75}
              aria-valuenow={Math.round(termPaneWidthPct)}
            >
              <PaneGripIcon />
            </div>
            <div className="term-index-explorer__occurrences">
              {selectedEntry ? (
                <ExtractedTermDetailPanel
                  entry={selectedEntry}
                  occurrences={occurrences}
                  isLoading={occurrencesQuery.isLoading}
                  error={occurrencesQuery.error}
                  isSavingReview={updateReviewMutation.isPending}
                  reviewError={updateReviewMutation.error}
                  onOpenReviewOverride={openReviewOverride}
                />
              ) : (
                <p className="settings-hint">Select a term to inspect examples.</p>
              )}
            </div>
            {showWorkspaceLoading ? (
              <TermIndexWorkspaceLoadingOverlay label="Loading terms" />
            ) : null}
          </div>
        </section>
      </div>
      <ExtractedTermTriageModal
        open={triageRequest != null}
        request={triageRequest}
        report={triageReport}
        progress={triageProgress}
        repositoryScopeLabel={formatRepositorySelectionSummary({
          selectedIds: effectiveRepositoryIds,
          defaultSummary:
            effectiveRepositoryIds.length === 1
              ? '1 repository'
              : `${effectiveRepositoryIds.length.toLocaleString()} repositories`,
        })}
        searchQuery={searchQuery}
        extractionMethod={extractionMethod}
        extractionMethodOptions={extractionMethodOptions}
        reviewStatusFilter={triageRequest?.reviewStatusFilter ?? reviewStatusFilter}
        reviewAuthorityFilter={triageRequest?.reviewAuthorityFilter ?? reviewAuthorityFilter}
        minOccurrences={minOccurrences}
        limit={triageRequest?.limit ?? termResultLimit}
        lastOccurrenceAfter={lastOccurrenceAfter}
        lastOccurrenceBefore={lastOccurrenceBefore}
        reviewChangedAfter={reviewChangedAfter}
        reviewChangedBefore={reviewChangedBefore}
        overwriteHumanReview={triageOverwriteHumanReview}
        isReviewing={triageMutation.isPending}
        error={triageMutation.error}
        onRequestChange={setTriageRequest}
        onOverwriteHumanReviewChange={setTriageOverwriteHumanReview}
        onClose={closeTriageModal}
        onTriage={confirmTriage}
      />
      <TermIndexReviewOverrideModal
        entry={reviewOverrideEntry}
        isSaving={updateReviewMutation.isPending}
        error={updateReviewMutation.error}
        onClose={closeReviewOverride}
        onSave={saveEntryReviewOverride}
      />
      <TermIndexBatchReviewModal
        open={bulkReviewModalOpen}
        selectedCount={selectedEntryIds.length}
        isSaving={batchUpdateReviewMutation.isPending}
        error={batchUpdateReviewMutation.error}
        onClose={() => {
          batchUpdateReviewMutation.reset();
          setBulkReviewModalOpen(false);
        }}
        onSave={saveSelectedEntriesReview}
      />
    </div>
  );
}

export function AdminTermIndexCandidatesPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: repositories } = useRepositories();
  const repositoryOptions = useRepositorySelectionOptions(repositories ?? []);
  const {
    selectedRepositoryIds,
    effectiveRepositoryIds,
    repositorySelectionActions,
    formatRepositorySelectionSummary,
    updateSelectedRepositoryIds,
  } = useTermIndexRepositorySelection(repositoryOptions);
  const [searchQuery, setSearchQuery] = useState('');
  const minOccurrences = 0;
  const [reviewStatusFilter, setReviewStatusFilter] =
    useState<ApiTermIndexReviewStatusFilter>('NON_REJECTED');
  const [termResultLimit, setTermResultLimit] = useState(TERM_RESULT_LIMIT_DEFAULT);
  const reviewChangedAfter: string | null = null;
  const reviewChangedBefore: string | null = null;
  const [selectedEntryId, setSelectedEntryId] = useState<number | null>(null);
  const [selectedEntryIds, setSelectedEntryIds] = useState<number[]>([]);
  const [candidateDraft, setCandidateDraft] = useState<CandidateDraft>(() =>
    createCandidateDraft(null),
  );
  const [candidateReviewRequest, setCandidateReviewRequest] =
    useState<CandidateReviewRequest | null>(null);
  const candidateReviewTeamId = candidateReviewRequest?.teamId ?? null;
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; message: string } | null>(null);
  const [termPaneWidthPct, setTermPaneWidthPct] = useState(DEFAULT_TERM_PANE_WIDTH_PCT);
  const [isPaneResizing, setIsPaneResizing] = useState(false);
  const workspaceRef = useRef<HTMLDivElement>(null);

  const resetTermSelection = () => {
    setSelectedEntryId(null);
    setSelectedEntryIds([]);
    setNotice(null);
  };
  const updateTermPaneWidthPct = useCallback((next: number) => {
    setTermPaneWidthPct(Math.min(75, Math.max(25, next)));
  }, []);
  const updateTermResultLimit = (next: number) => {
    setTermResultLimit(Math.min(Math.max(1, next), TERM_RESULT_LIMIT_MAX));
    resetTermSelection();
  };

  const glossariesQuery = useQuery({
    queryKey: ['glossaries', 'term-index-candidate-review-targets'],
    queryFn: () => fetchGlossaries({ enabled: true, limit: 500 }),
    enabled: isAdmin,
    staleTime: 30_000,
  });
  const candidateReviewTeamsQuery = useQuery({
    queryKey: ['teams', 'term-index-candidate-review'],
    queryFn: fetchTeams,
    enabled: isAdmin,
    staleTime: 30_000,
  });
  const candidateReviewSpecialistsQuery = useQuery({
    queryKey: ['team-users', candidateReviewTeamId, 'TRANSLATOR', 'term-index-candidate-review'],
    queryFn: () => fetchTeamUsersByRole(candidateReviewTeamId as number, 'TRANSLATOR'),
    enabled: isAdmin && candidateReviewRequest != null && candidateReviewTeamId != null,
    staleTime: 30_000,
  });
  const candidateReviewPmUsersQuery = useQuery({
    queryKey: ['team-users', candidateReviewTeamId, 'PM', 'term-index-candidate-review'],
    queryFn: () => fetchTeamUsersByRole(candidateReviewTeamId as number, 'PM'),
    enabled: isAdmin && candidateReviewRequest != null && candidateReviewTeamId != null,
    staleTime: 30_000,
  });

  const entriesQuery = useQuery({
    queryKey: [
      'term-index-candidates',
      effectiveRepositoryIds,
      searchQuery,
      reviewStatusFilter,
      minOccurrences,
      termResultLimit,
      reviewChangedAfter,
      reviewChangedBefore,
    ],
    queryFn: () =>
      fetchTermIndexCandidates({
        repositoryIds: effectiveRepositoryIds,
        search: searchQuery,
        reviewStatus: reviewStatusFilter,
        reviewAuthority: 'ALL',
        minOccurrences,
        limit: termResultLimit,
        reviewChangedAfter,
        reviewChangedBefore,
      }),
    enabled: isAdmin && repositoryOptions.length > 0,
    staleTime: 5_000,
  });

  const entryData = entriesQuery.data;
  const entries = useMemo(() => entryData?.candidates.map(candidateToEntry) ?? [], [entryData]);
  const selectedEntry = useMemo(
    () => entries.find((entry) => entry.id === selectedEntryId) ?? null,
    [entries, selectedEntryId],
  );
  const selectedEntries = useMemo(
    () => entries.filter((entry) => selectedEntryIds.includes(entry.id)),
    [entries, selectedEntryIds],
  );
  const selectedCandidateEntries = selectedEntries;
  const selectedCandidateIds = useMemo(
    () =>
      Array.from(
        new Set(
          selectedCandidateEntries
            .map((entry) => entry.termIndexCandidateId)
            .filter((candidateId): candidateId is number => candidateId != null),
        ),
      ),
    [selectedCandidateEntries],
  );
  const candidateReviewTeamOptions = useMemo(
    () =>
      (candidateReviewTeamsQuery.data ?? [])
        .filter((team) => team.enabled !== false)
        .map((team) => ({
          value: team.id,
          label: `${team.name} (#${team.id})`,
        }))
        .sort((first, second) =>
          first.label.localeCompare(second.label, undefined, { sensitivity: 'base' }),
        ),
    [candidateReviewTeamsQuery.data],
  );
  const candidateReviewSpecialistOptions = useMemo(
    () =>
      (candidateReviewSpecialistsQuery.data?.users ?? [])
        .map(toTeamUserSelectOption)
        .sort((first, second) =>
          first.label.localeCompare(second.label, undefined, { sensitivity: 'base' }),
        ),
    [candidateReviewSpecialistsQuery.data?.users],
  );
  const candidateReviewDeciderOptions = useMemo(() => {
    const optionsByUserId = new Map<
      number,
      { value: number; label: string; helper: string; roles: Set<string> }
    >();
    const addUsers = (users: ApiTeamUserSummary[] | undefined, role: string) => {
      (users ?? []).forEach((teamUser) => {
        const existing = optionsByUserId.get(teamUser.id);
        if (existing) {
          existing.roles.add(role);
          existing.helper = Array.from(existing.roles).join(' + ');
          return;
        }
        optionsByUserId.set(teamUser.id, {
          value: teamUser.id,
          label: getUserLabel(teamUser) || `User #${teamUser.id}`,
          helper: role,
          roles: new Set([role]),
        });
      });
    };
    addUsers(candidateReviewPmUsersQuery.data?.users, 'PM');
    addUsers(candidateReviewSpecialistsQuery.data?.users, 'Advisor');
    return Array.from(optionsByUserId.values())
      .map((option) => ({
        value: option.value,
        label: option.label,
        helper: option.helper,
      }))
      .sort((first, second) =>
        first.label.localeCompare(second.label, undefined, { sensitivity: 'base' }),
      );
  }, [candidateReviewPmUsersQuery.data?.users, candidateReviewSpecialistsQuery.data?.users]);
  const allEntriesSelected =
    entries.length > 0 && entries.every((entry) => selectedEntryIds.includes(entry.id));
  useEffect(() => {
    if (entries.length === 0) {
      if (!entriesQuery.isFetching) {
        setSelectedEntryId(null);
      }
      return;
    }
    if (selectedEntryId == null || !entries.some((entry) => entry.id === selectedEntryId)) {
      setSelectedEntryId(entries[0].id);
    }
  }, [entries, entriesQuery.isFetching, selectedEntryId]);

  useEffect(() => {
    setCandidateDraft(createCandidateDraft(selectedEntry));
  }, [selectedEntry]);

  useEffect(() => {
    const visibleEntryIds = new Set(entries.map((entry) => entry.id));
    setSelectedEntryIds((current) => current.filter((entryId) => visibleEntryIds.has(entryId)));
  }, [entries]);

  const occurrencesQuery = useQuery({
    queryKey: [
      'term-index-occurrences',
      'candidate-review',
      selectedEntry?.termIndexExtractedTermId ?? null,
      effectiveRepositoryIds,
    ],
    queryFn: () =>
      fetchTermIndexOccurrences(selectedEntry?.termIndexExtractedTermId ?? 0, {
        repositoryIds: effectiveRepositoryIds,
        limit: EXAMPLE_RESULT_LIMIT,
      }),
    enabled:
      isAdmin && selectedEntry?.termIndexExtractedTermId != null && repositoryOptions.length > 0,
    staleTime: 5_000,
  });

  const occurrenceData = occurrencesQuery.data;
  const occurrences = occurrenceData?.occurrences ?? [];

  const updateCandidateMutation = useMutation({
    mutationFn: ({ candidateId, draft }: { candidateId: number; draft: CandidateDraft }) =>
      updateTermIndexCandidate(candidateId, {
        definition: nullableTrimmed(draft.definition),
        rationale: nullableTrimmed(draft.rationale),
        termType: nullableTrimmed(draft.termType),
        partOfSpeech: nullableTrimmed(draft.partOfSpeech),
        enforcement: nullableTrimmed(draft.enforcement),
        doNotTranslate: draft.doNotTranslate,
        confidence: parseOptionalConfidence(draft.confidence),
        reviewStatus: draft.reviewStatus,
      }),
    onSuccess: async (candidate) => {
      setSelectedEntryId(candidate.id);
      setNotice({
        kind: 'success',
        message: `Saved candidate #${candidate.id}.`,
      });
      await queryClient.invalidateQueries({ queryKey: ['term-index-candidates'] });
    },
    onError: (error: Error) => {
      setNotice({ kind: 'error', message: error.message || 'Candidate update failed.' });
    },
  });

  const updateSelectedCandidateReviewsMutation = useMutation({
    mutationFn: ({
      candidateIds,
      reviewStatus,
    }: {
      candidateIds: number[];
      reviewStatus: ApiTermIndexReviewStatus;
    }) => updateTermIndexCandidateReviews(candidateIds, { reviewStatus }),
    onSuccess: async (response) => {
      setSelectedEntryIds([]);
      setNotice({
        kind: 'success',
        message: `Updated ${response.updatedCandidateCount.toLocaleString()} candidate statuses.`,
      });
      await queryClient.invalidateQueries({ queryKey: ['term-index-candidates'] });
    },
    onError: (error: Error) => {
      setNotice({
        kind: 'error',
        message: error.message || 'Failed to update candidate statuses.',
      });
    },
  });

  const createCandidateReviewMutation = useMutation({
    mutationFn: (request: CandidateReviewRequest) => {
      if (request.teamId == null) {
        throw new Error('Select a review team.');
      }
      return createGlossaryTermCandidateReviewProjectRequest(request.glossaryId, {
        name: 'Term candidate review',
        notes:
          'Review generated term candidates before promoting accepted proposals into the target glossary.',
        teamId: request.teamId,
        assignTranslator: false,
        specialistUserIds: request.specialistUserIds.length > 0 ? request.specialistUserIds : null,
        pmUserId: request.pmUserId,
        termIndexCandidateIds: request.candidateIds,
      });
    },
    onSuccess: (response) => {
      setCandidateReviewRequest(null);
      setSelectedEntryIds([]);
      const projectId = response.projectIds[0];
      if (projectId != null) {
        void navigate(`/review-projects/${projectId}`);
        return;
      }
      setNotice({
        kind: 'error',
        message: 'No candidate review project was created.',
      });
    },
    onError: (error: Error) => {
      setNotice({
        kind: 'error',
        message: error.message || 'Failed to create candidate review project.',
      });
    },
  });

  const updateCandidateReview = (
    entry: ApiTermIndexEntry,
    reviewStatus: ApiTermIndexReviewStatus,
  ) => {
    if (updateCandidateMutation.isPending) {
      return;
    }
    setNotice(null);
    updateCandidateMutation.mutate({
      candidateId: entry.id,
      draft: {
        ...createCandidateDraft(entry),
        reviewStatus,
      },
    });
  };

  const updateSelectedCandidateReviewStatus = (reviewStatus: ApiTermIndexReviewStatus | null) => {
    if (
      reviewStatus == null ||
      selectedCandidateIds.length === 0 ||
      updateSelectedCandidateReviewsMutation.isPending
    ) {
      return;
    }
    setNotice(null);
    updateSelectedCandidateReviewsMutation.mutate({
      candidateIds: selectedCandidateIds,
      reviewStatus,
    });
  };

  const openCandidateReviewRequest = () => {
    if (selectedCandidateIds.length === 0 || createCandidateReviewMutation.isPending) {
      return;
    }
    const defaultTeamId =
      candidateReviewTeamOptions.length === 1
        ? (candidateReviewTeamOptions[0]?.value ?? null)
        : null;
    setNotice(null);
    setCandidateReviewRequest({
      entries: selectedCandidateEntries,
      candidateIds: selectedCandidateIds,
      selectedEntryCount: selectedEntries.length,
      glossaryId: null,
      teamId: defaultTeamId,
      specialistUserIds: [],
      pmUserId: null,
    });
  };

  const closeCandidateReviewModal = () => {
    if (createCandidateReviewMutation.isPending) {
      return;
    }
    setCandidateReviewRequest(null);
  };

  const confirmCandidateReviewProject = () => {
    if (!candidateReviewRequest || createCandidateReviewMutation.isPending) {
      return;
    }
    setNotice(null);
    createCandidateReviewMutation.mutate(candidateReviewRequest);
  };

  const saveSelectedCandidate = () => {
    if (!selectedEntry || updateCandidateMutation.isPending) {
      return;
    }
    setNotice(null);
    updateCandidateMutation.mutate({
      candidateId: selectedEntry.id,
      draft: cloneCandidateDraft(candidateDraft),
    });
  };

  const termCountLabel = entriesQuery.isLoading
    ? 'Candidates'
    : formatCappedListLabel('candidates', entries.length, termResultLimit);
  const showWorkspaceLoading = entriesQuery.isFetching && !entriesQuery.isError;
  const workspaceStyle = useMemo(
    () =>
      ({
        '--term-index-left-pane-width': `${termPaneWidthPct}%`,
      }) as CSSProperties,
    [termPaneWidthPct],
  );
  const startPaneResize = useCallback(
    (event: ReactMouseEvent<HTMLDivElement>) => {
      event.preventDefault();
      setIsPaneResizing(true);

      const handleMove = (moveEvent: MouseEvent) => {
        if (!workspaceRef.current) {
          return;
        }
        const rect = workspaceRef.current.getBoundingClientRect();
        updateTermPaneWidthPct(((moveEvent.clientX - rect.left) / rect.width) * 100);
      };
      const handleUp = () => {
        setIsPaneResizing(false);
        window.removeEventListener('mousemove', handleMove);
        window.removeEventListener('mouseup', handleUp);
      };

      window.addEventListener('mousemove', handleMove);
      window.addEventListener('mouseup', handleUp);
    },
    [updateTermPaneWidthPct],
  );
  const handlePaneDividerKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      let nextWidth: number | null = null;
      if (event.key === 'ArrowLeft') {
        nextWidth = termPaneWidthPct - 5;
      } else if (event.key === 'ArrowRight') {
        nextWidth = termPaneWidthPct + 5;
      } else if (event.key === 'Home') {
        nextWidth = 25;
      } else if (event.key === 'End') {
        nextWidth = 75;
      }

      if (nextWidth == null) {
        return;
      }

      event.preventDefault();
      updateTermPaneWidthPct(nextWidth);
    },
    [termPaneWidthPct, updateTermPaneWidthPct],
  );

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  return (
    <div className="settings-subpage term-index-explorer__page">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
        context="Settings > Glossaries"
        title="Candidates"
        centerContent={<TermIndexSubnav active="candidates" />}
      />
      <div className="settings-page settings-page--wide term-index-explorer">
        <section
          className="settings-card term-index-explorer__filter-card"
          aria-label="Candidate filters"
        >
          <div className="term-index-explorer__filterbar">
            <div className="term-index-explorer__repository-control">
              <RepositoryMultiSelect
                label="Repositories"
                options={repositoryOptions}
                selectedIds={selectedRepositoryIds}
                onChange={(next) => {
                  updateSelectedRepositoryIds(next);
                  resetTermSelection();
                }}
                className="term-index-explorer__repository-select"
                buttonAriaLabel="Select repositories for candidate review evidence. Use menu actions to switch between repositories and glossaries."
                customActions={repositorySelectionActions}
                summaryFormatter={formatRepositorySelectionSummary}
              />
            </div>
            <SearchControl
              inputId="term-index-candidate-source-search"
              value={searchQuery}
              onChange={(next) => {
                setSearchQuery(next);
                resetTermSelection();
              }}
              placeholder="Search candidates"
              inputAriaLabel="Search generated term candidates"
              className="term-index-explorer__search-control"
            />
            <div className="term-index-explorer__filter-controls">
              <TermIndexReviewFilterChip
                summaryPrefix="Candidate review"
                showAuthority={false}
                reviewStatus={reviewStatusFilter}
                reviewAuthority="ALL"
                onReviewStatusChange={(next) => {
                  setReviewStatusFilter(next);
                  resetTermSelection();
                }}
                disabled={entriesQuery.isLoading}
              />
            </div>
          </div>
        </section>

        <section
          className="settings-card term-index-explorer__results-card"
          aria-label="Candidate queue"
        >
          <div className="term-index-explorer__subbar">
            <TermIndexResultSizeDropdown
              countLabel={termCountLabel}
              resultLimit={termResultLimit}
              onChangeResultLimit={updateTermResultLimit}
              disabled={entriesQuery.isLoading}
            />
            <div className="term-index-explorer__subbar-actions">
              {entries.length > 0 && !allEntriesSelected ? (
                <>
                  <TermIndexSubbarSeparator />
                  <button
                    type="button"
                    className="term-index-explorer__subbar-button"
                    onClick={() => setSelectedEntryIds(entries.map((entry) => entry.id))}
                    disabled={
                      updateCandidateMutation.isPending ||
                      updateSelectedCandidateReviewsMutation.isPending
                    }
                  >
                    Select all
                  </button>
                </>
              ) : null}
              {selectedEntries.length > 0 ? (
                <>
                  <TermIndexSubbarSeparator />
                  <span className="settings-hint term-index-explorer__selection-summary">
                    {selectedEntries.length.toLocaleString()} selected
                  </span>
                  <TermIndexSubbarSeparator />
                  <SingleSelectDropdown<ApiTermIndexReviewStatus>
                    label="Candidate status"
                    options={REVIEW_STATUS_OPTIONS}
                    value={null}
                    onChange={updateSelectedCandidateReviewStatus}
                    placeholder={
                      updateSelectedCandidateReviewsMutation.isPending
                        ? 'Updating...'
                        : 'Set status'
                    }
                    buttonAriaLabel="Set selected candidate status"
                    searchable={false}
                    className="term-index-explorer__bulk-dropdown"
                    disabled={
                      updateSelectedCandidateReviewsMutation.isPending ||
                      selectedCandidateIds.length === 0
                    }
                  />
                  <TermIndexSubbarSeparator />
                  <button
                    type="button"
                    className="term-index-explorer__subbar-button"
                    onClick={openCandidateReviewRequest}
                    disabled={
                      createCandidateReviewMutation.isPending ||
                      updateSelectedCandidateReviewsMutation.isPending ||
                      selectedCandidateIds.length === 0 ||
                      glossariesQuery.isLoading ||
                      candidateReviewTeamsQuery.isLoading
                    }
                  >
                    Create candidate review
                  </button>
                  <TermIndexSubbarSeparator />
                  <button
                    type="button"
                    className="term-index-explorer__subbar-button"
                    onClick={() => setSelectedEntryIds([])}
                    disabled={
                      updateCandidateMutation.isPending ||
                      updateSelectedCandidateReviewsMutation.isPending ||
                      createCandidateReviewMutation.isPending
                    }
                  >
                    Clear selection
                  </button>
                </>
              ) : null}
            </div>
          </div>
          {notice ? (
            <p className={`settings-hint${notice.kind === 'error' ? ' is-error' : ''}`}>
              {notice.message}
            </p>
          ) : null}
          {updateCandidateMutation.error ? (
            <p className="settings-hint is-error">
              {getErrorMessage(updateCandidateMutation.error)}
            </p>
          ) : null}
          <div
            className={`term-index-explorer__workspace${showWorkspaceLoading ? ' is-loading' : ''}`}
            ref={workspaceRef}
            style={workspaceStyle}
          >
            <div className="term-index-explorer__terms">
              {entriesQuery.isLoading ? null : entriesQuery.isError ? (
                <p className="settings-hint is-error">{getErrorMessage(entriesQuery.error)}</p>
              ) : entries.length === 0 ? (
                <p className="settings-hint">No candidates match the filters.</p>
              ) : (
                <TermList
                  entries={entries}
                  selectedEntryId={selectedEntryId}
                  selectedEntryIds={selectedEntryIds}
                  rowMetadata="candidate"
                  showReviewConfidence
                  onSelectEntry={setSelectedEntryId}
                  onUpdateEntryReview={updateCandidateReview}
                  isUpdatingReview={updateCandidateMutation.isPending}
                  onToggleEntrySelection={(entryId, checked) =>
                    setSelectedEntryIds((current) =>
                      checked
                        ? Array.from(new Set([...current, entryId]))
                        : current.filter((id) => id !== entryId),
                    )
                  }
                />
              )}
            </div>
            <div
              className={`term-index-explorer__pane-divider${isPaneResizing ? ' is-resizing' : ''}`}
              onMouseDown={startPaneResize}
              onDoubleClick={() => updateTermPaneWidthPct(DEFAULT_TERM_PANE_WIDTH_PCT)}
              onKeyDown={handlePaneDividerKeyDown}
              role="separator"
              tabIndex={0}
              aria-label="Resize term and candidate panes"
              aria-orientation="vertical"
              aria-valuemin={25}
              aria-valuemax={75}
              aria-valuenow={Math.round(termPaneWidthPct)}
            >
              <PaneGripIcon />
            </div>
            <div className="term-index-explorer__occurrences">
              {selectedEntry ? (
                <CandidateDraftPanel
                  entry={selectedEntry}
                  draft={candidateDraft}
                  occurrences={occurrences}
                  isLoadingOccurrences={occurrencesQuery.isLoading}
                  occurrenceError={occurrencesQuery.error}
                  isSaving={updateCandidateMutation.isPending}
                  onDraftChange={setCandidateDraft}
                  onSave={saveSelectedCandidate}
                />
              ) : (
                <p className="settings-hint">Select a candidate to review.</p>
              )}
            </div>
            {showWorkspaceLoading ? (
              <TermIndexWorkspaceLoadingOverlay label="Loading candidates" />
            ) : null}
          </div>
        </section>
      </div>
      <CandidateReviewProjectModal
        open={candidateReviewRequest != null}
        request={candidateReviewRequest}
        glossaries={glossariesQuery.data?.glossaries ?? []}
        isLoadingGlossaries={glossariesQuery.isLoading}
        teamOptions={candidateReviewTeamOptions}
        specialistOptions={candidateReviewSpecialistOptions}
        deciderOptions={candidateReviewDeciderOptions}
        isLoadingTeams={candidateReviewTeamsQuery.isLoading}
        isLoadingSpecialists={candidateReviewSpecialistsQuery.isFetching}
        isLoadingDeciders={
          candidateReviewPmUsersQuery.isFetching || candidateReviewSpecialistsQuery.isFetching
        }
        isCreating={createCandidateReviewMutation.isPending}
        error={createCandidateReviewMutation.error}
        onChangeGlossary={(glossaryId) =>
          setCandidateReviewRequest((current) =>
            current == null ? current : { ...current, glossaryId },
          )
        }
        onChangeTeam={(teamId) =>
          setCandidateReviewRequest((current) =>
            current == null
              ? current
              : { ...current, teamId, specialistUserIds: [], pmUserId: null },
          )
        }
        onChangeSpecialists={(specialistUserIds) =>
          setCandidateReviewRequest((current) =>
            current == null ? current : { ...current, specialistUserIds },
          )
        }
        onChangeDecider={(pmUserId) =>
          setCandidateReviewRequest((current) =>
            current == null ? current : { ...current, pmUserId },
          )
        }
        onClose={closeCandidateReviewModal}
        onCreate={confirmCandidateReviewProject}
      />
    </div>
  );
}

export function TermIndexSubnav({
  active,
}: {
  active: 'workflow' | 'automation' | 'terms' | 'candidates';
}) {
  return (
    <nav className="term-index-explorer__subnav" aria-label="Term index sections">
      <Link
        className={`term-index-explorer__subnav-link${active === 'workflow' ? ' is-active' : ''}`}
        to="/settings/system/glossary-term-index/workflow"
      >
        Workflow
      </Link>
      <Link
        className={`term-index-explorer__subnav-link${active === 'automation' ? ' is-active' : ''}`}
        to="/settings/system/glossary-term-index"
      >
        Automation
      </Link>
      <Link
        className={`term-index-explorer__subnav-link${active === 'terms' ? ' is-active' : ''}`}
        to="/settings/system/glossary-term-index/terms"
      >
        Terms
      </Link>
      <Link
        className={`term-index-explorer__subnav-link${active === 'candidates' ? ' is-active' : ''}`}
        to="/settings/system/glossary-term-index/candidates"
      >
        Candidates
      </Link>
    </nav>
  );
}

function AutomationActionCard({
  title,
  description,
  cta,
  disabled,
  onClick,
  linkTo,
  linkLabel,
  children,
}: {
  title: string;
  description: string;
  cta: string;
  disabled: boolean;
  onClick: () => void;
  linkTo: string;
  linkLabel: string;
  children?: ReactNode;
}) {
  return (
    <article className="settings-card term-index-explorer__automation-card">
      <div className="term-index-explorer__automation-card-main">
        <div className="term-index-explorer__automation-card-content">
          <h2>{title}</h2>
          <p>{description}</p>
        </div>
        <div className="term-index-explorer__automation-card-actions">
          <button
            type="button"
            className="settings-button settings-button--primary"
            disabled={disabled}
            onClick={onClick}
          >
            {cta}
          </button>
          <Link className="settings-button settings-button--secondary" to={linkTo}>
            {linkLabel}
          </Link>
        </div>
      </div>
      {children ? (
        <div className="term-index-explorer__automation-card-status">{children}</div>
      ) : null}
    </article>
  );
}

function TermIndexWorkspaceLoadingOverlay({ label }: { label: string }) {
  return (
    <div className="term-index-explorer__workspace-loading" role="status" aria-live="polite">
      <span>{label}</span>
    </div>
  );
}

function TermIndexQueryFilterChip({
  extractionMethod,
  extractionMethodOptions,
  onExtractionMethodChange,
  minOccurrences,
  onMinOccurrencesChange,
  lastOccurrenceAfter,
  lastOccurrenceBefore,
  onLastOccurrenceAfterChange,
  onLastOccurrenceBeforeChange,
  reviewChangedAfter,
  reviewChangedBefore,
  onReviewChangedAfterChange,
  onReviewChangedBeforeChange,
  dateQuickRanges,
  disabled,
  ariaLabel,
}: {
  extractionMethod: string | null;
  extractionMethodOptions: Array<{ value: string; label: string }>;
  onExtractionMethodChange: (value: string | null) => void;
  minOccurrences: number;
  onMinOccurrencesChange: (value: number) => void;
  lastOccurrenceAfter: string | null;
  lastOccurrenceBefore: string | null;
  onLastOccurrenceAfterChange: (value: string | null) => void;
  onLastOccurrenceBeforeChange: (value: string | null) => void;
  reviewChangedAfter: string | null;
  reviewChangedBefore: string | null;
  onReviewChangedAfterChange: (value: string | null) => void;
  onReviewChangedBeforeChange: (value: string | null) => void;
  dateQuickRanges: DateQuickRange[];
  disabled?: boolean;
  ariaLabel: string;
}) {
  const extractorOptions = useMemo(
    () => [
      { value: ALL_EXTRACTORS_FILTER_VALUE, label: 'All extractors' },
      ...extractionMethodOptions,
    ],
    [extractionMethodOptions],
  );
  const extractorValue = extractionMethod ?? ALL_EXTRACTORS_FILTER_VALUE;
  return (
    <MultiSectionFilterChip
      ariaLabel={ariaLabel}
      align="right"
      className="filter-chip term-index-explorer__query-filter"
      classNames={TERM_INDEX_FILTER_CHIP_CLASS_NAMES}
      disabled={disabled}
      summary={formatTermIndexQueryFilterSummary(
        minOccurrences,
        extractionMethod,
        lastOccurrenceAfter,
        lastOccurrenceBefore,
        reviewChangedAfter,
        reviewChangedBefore,
      )}
      sections={[
        {
          kind: 'radio',
          label: 'Extractor',
          options: extractorOptions,
          value: extractorValue,
          onChange: (value) =>
            onExtractionMethodChange(value === ALL_EXTRACTORS_FILTER_VALUE ? null : String(value)),
        },
        {
          kind: 'size',
          label: 'Minimum hits',
          options: MIN_OCCURRENCES_OPTIONS,
          value: minOccurrences,
          onChange: onMinOccurrencesChange,
          min: 1,
        },
        {
          kind: 'date',
          label: 'Last extracted',
          after: lastOccurrenceAfter,
          before: lastOccurrenceBefore,
          onChangeAfter: onLastOccurrenceAfterChange,
          onChangeBefore: onLastOccurrenceBeforeChange,
          afterLabel: 'Last extracted after',
          beforeLabel: 'Last extracted before',
          quickRanges: dateQuickRanges,
          clearLabel: 'Clear date filter',
          onClear: () => {
            onLastOccurrenceAfterChange(null);
            onLastOccurrenceBeforeChange(null);
          },
        },
        {
          kind: 'date',
          label: 'Review updated',
          after: reviewChangedAfter,
          before: reviewChangedBefore,
          onChangeAfter: onReviewChangedAfterChange,
          onChangeBefore: onReviewChangedBeforeChange,
          afterLabel: 'Review updated after',
          beforeLabel: 'Review updated before',
          quickRanges: dateQuickRanges,
          clearLabel: 'Clear review date filter',
          onClear: () => {
            onReviewChangedAfterChange(null);
            onReviewChangedBeforeChange(null);
          },
        },
      ]}
    />
  );
}

function TermIndexReviewFilterChip({
  summaryPrefix = 'Review',
  showAuthority = true,
  reviewStatus,
  reviewAuthority,
  onReviewStatusChange,
  onReviewAuthorityChange,
  disabled,
}: {
  summaryPrefix?: string;
  showAuthority?: boolean;
  reviewStatus: ApiTermIndexReviewStatusFilter;
  reviewAuthority: ApiTermIndexReviewAuthorityFilter;
  onReviewStatusChange: (value: ApiTermIndexReviewStatusFilter) => void;
  onReviewAuthorityChange?: (value: ApiTermIndexReviewAuthorityFilter) => void;
  disabled?: boolean;
}) {
  return (
    <MultiSectionFilterChip
      ariaLabel={showAuthority ? 'Filter by review status and source' : 'Filter by review status'}
      align="right"
      className="filter-chip term-index-explorer__review-filter"
      classNames={TERM_INDEX_FILTER_CHIP_CLASS_NAMES}
      disabled={disabled}
      summary={formatTermIndexReviewFilterSummary(
        reviewStatus,
        reviewAuthority,
        summaryPrefix,
        showAuthority,
      )}
      sections={[
        {
          kind: 'radio',
          label: 'Status',
          options: REVIEW_STATUS_FILTER_OPTIONS,
          value: reviewStatus,
          onChange: (value) => onReviewStatusChange(value as ApiTermIndexReviewStatusFilter),
        },
        ...(showAuthority && onReviewAuthorityChange
          ? [
              {
                kind: 'radio' as const,
                label: 'Source',
                options: REVIEW_AUTHORITY_FILTER_OPTIONS,
                value: reviewAuthority,
                onChange: (value: string | number) =>
                  onReviewAuthorityChange(value as ApiTermIndexReviewAuthorityFilter),
              },
            ]
          : []),
      ]}
    />
  );
}

function TermIndexResultSizeDropdown({
  countLabel,
  resultLimit,
  onChangeResultLimit,
  disabled,
}: {
  countLabel: string;
  resultLimit: number;
  onChangeResultLimit: (value: number) => void;
  disabled: boolean;
}) {
  return (
    <NumericPresetDropdown
      value={resultLimit}
      buttonLabel={countLabel}
      menuLabel="Result size limit"
      presetOptions={TERM_RESULT_LIMIT_OPTIONS}
      onChange={onChangeResultLimit}
      disabled={disabled}
      ariaLabel="Term index result size"
      className="term-index-explorer__subbar-count term-index-explorer__subbar-dropdown"
      buttonClassName="term-index-explorer__subbar-button term-index-explorer__subbar-button--dropdown"
    />
  );
}

function TermIndexSortDropdown({
  sortBy,
  onChangeSort,
  disabled,
}: {
  sortBy: ApiTermIndexEntrySort;
  onChangeSort: (value: ApiTermIndexEntrySort) => void;
  disabled: boolean;
}) {
  const selectedSortLabel =
    TERM_SORT_OPTIONS.find((option) => option.value === sortBy)?.label ?? 'Highest confidence';
  return (
    <SingleSelectDropdown
      label="Sort"
      options={TERM_SORT_OPTIONS}
      value={sortBy}
      buttonSummary={`Sort by ${selectedSortLabel.toLowerCase()}`}
      onChange={(next) => {
        if (next != null) {
          onChangeSort(next);
        }
      }}
      className="term-index-explorer__sort-dropdown"
      buttonAriaLabel="Sort term index results"
      searchable={false}
      disabled={disabled}
    />
  );
}

function TermIndexSubbarSeparator() {
  return (
    <span className="term-index-explorer__subbar-separator" aria-hidden="true">
      ·
    </span>
  );
}

function TermIndexRefreshModal({
  open,
  repositoryOptions,
  selectedRepositoryIds,
  fullRefresh,
  isRefreshing,
  onRepositoryChange,
  onFullRefreshChange,
  onClose,
  onStart,
}: {
  open: boolean;
  repositoryOptions: ReturnType<typeof useRepositorySelectionOptions>;
  selectedRepositoryIds: number[];
  fullRefresh: boolean;
  isRefreshing: boolean;
  onRepositoryChange: (repositoryIds: number[]) => void;
  onFullRefreshChange: (fullRefresh: boolean) => void;
  onClose: () => void;
  onStart: () => void;
}) {
  const allRepositoryIds = useMemo(() => getRepositoryIds(repositoryOptions), [repositoryOptions]);
  const productRepositoryIds = useMemo(
    () => getProductRepositoryIds(repositoryOptions),
    [repositoryOptions],
  );
  const glossaryRepositoryIds = useMemo(
    () => getGlossaryRepositoryIds(repositoryOptions),
    [repositoryOptions],
  );
  const repositorySelectionActions = useMemo(
    () =>
      getRepositoryTypeSelectionActions({
        selectedRepositoryIds,
        productRepositoryIds,
        glossaryRepositoryIds,
        onChangeRepositorySelection: onRepositoryChange,
      }),
    [glossaryRepositoryIds, onRepositoryChange, productRepositoryIds, selectedRepositoryIds],
  );
  const formatRepositorySelectionSummary = useMemo(
    () =>
      getRepositorySelectionSummaryFormatter(
        allRepositoryIds,
        productRepositoryIds,
        glossaryRepositoryIds,
      ),
    [allRepositoryIds, glossaryRepositoryIds, productRepositoryIds],
  );
  const effectiveRefreshRepositoryIds =
    selectedRepositoryIds.length > 0 ? selectedRepositoryIds : allRepositoryIds;
  const refreshRepositoryCount = effectiveRefreshRepositoryIds.length;
  const canStart = refreshRepositoryCount > 0 && !isRefreshing;
  const repositorySummary =
    selectedRepositoryIds.length === 0
      ? 'All repositories selected'
      : refreshRepositoryCount === 1
        ? '1 repository selected'
        : `${refreshRepositoryCount.toLocaleString()} repositories selected`;

  return (
    <Modal
      open={open}
      onClose={onClose}
      closeOnBackdrop={!isRefreshing}
      ariaLabel="Run term extraction"
      className="term-index-explorer__refresh-modal"
    >
      <div className="modal__header">
        <div className="modal__title">Run term extraction</div>
      </div>
      <div className="modal__body term-index-explorer__refresh-modal-body">
        <div className="settings-field">
          <div className="term-index-explorer__repository-control">
            <RepositoryMultiSelect
              label="Repositories to extract"
              options={repositoryOptions}
              selectedIds={selectedRepositoryIds}
              onChange={onRepositoryChange}
              className="settings-repository-select"
              buttonAriaLabel="Select repositories for term extraction. Use menu actions to switch between repositories and glossaries."
              customActions={repositorySelectionActions}
              summaryFormatter={formatRepositorySelectionSummary}
            />
          </div>
          <p className={`settings-hint${refreshRepositoryCount === 0 ? ' is-error' : ''}`}>
            {refreshRepositoryCount === 0 ? 'Select at least one repository.' : repositorySummary}
          </p>
        </div>

        <fieldset className="term-index-explorer__refresh-mode">
          <legend>Extraction mode</legend>
          <label
            className={`term-index-explorer__refresh-mode-option${
              !fullRefresh ? ' is-selected' : ''
            }`}
          >
            <input
              type="radio"
              name="term-index-refresh-mode"
              checked={!fullRefresh}
              onChange={() => onFullRefreshChange(false)}
            />
            <span>
              <strong>Incremental</strong>
              <span>Process source text units after each repository cursor.</span>
            </span>
          </label>
          <label
            className={`term-index-explorer__refresh-mode-option${
              fullRefresh ? ' is-selected' : ''
            }`}
          >
            <input
              type="radio"
              name="term-index-refresh-mode"
              checked={fullRefresh}
              onChange={() => onFullRefreshChange(true)}
            />
            <span>
              <strong>Full reindex</strong>
              <span>Clear recorded occurrences for selected repositories and rebuild them.</span>
            </span>
          </label>
        </fieldset>
      </div>
      <div className="modal__actions">
        <button type="button" className="modal__button" onClick={onClose} disabled={isRefreshing}>
          Cancel
        </button>
        <button
          type="button"
          className="modal__button modal__button--primary"
          onClick={onStart}
          disabled={!canStart}
        >
          {isRefreshing ? 'Starting...' : 'Extract'}
        </button>
      </div>
    </Modal>
  );
}

function ExtractedTermTriageModal({
  open,
  request,
  report,
  progress,
  repositoryScopeLabel,
  searchQuery,
  extractionMethod,
  extractionMethodOptions,
  reviewStatusFilter,
  reviewAuthorityFilter,
  minOccurrences,
  limit,
  lastOccurrenceAfter,
  lastOccurrenceBefore,
  reviewChangedAfter,
  reviewChangedBefore,
  overwriteHumanReview,
  isReviewing,
  error,
  onRequestChange,
  onOverwriteHumanReviewChange,
  onClose,
  onTriage,
}: {
  open: boolean;
  request: ExtractedTermTriageRequest | null;
  report: ApiTriageTermIndexEntriesResponse | null;
  progress: ApiTermIndexTaskProgress | null;
  repositoryScopeLabel: string;
  searchQuery: string;
  extractionMethod: string | null;
  extractionMethodOptions: Array<{ value: string; label: string }>;
  reviewStatusFilter: ApiTermIndexReviewStatusFilter;
  reviewAuthorityFilter: ApiTermIndexReviewAuthorityFilter;
  minOccurrences: number;
  limit: number;
  lastOccurrenceAfter: string | null;
  lastOccurrenceBefore: string | null;
  reviewChangedAfter: string | null;
  reviewChangedBefore: string | null;
  overwriteHumanReview: boolean;
  isReviewing: boolean;
  error: unknown;
  onRequestChange: (request: ExtractedTermTriageRequest | null) => void;
  onOverwriteHumanReviewChange: (overwrite: boolean) => void;
  onClose: () => void;
  onTriage: () => void;
}) {
  const isFilterMode = request?.mode === 'filter';
  const effectiveSearchQuery = isFilterMode ? (request.searchQuery ?? '') : searchQuery;
  const effectiveExtractionMethod = isFilterMode
    ? (request.extractionMethod ?? null)
    : extractionMethod;
  const effectiveReviewStatusFilter = isFilterMode
    ? (request.reviewStatusFilter ?? 'NON_REJECTED')
    : reviewStatusFilter;
  const effectiveReviewAuthorityFilter = isFilterMode
    ? (request.reviewAuthorityFilter ?? 'NONE')
    : reviewAuthorityFilter;
  const effectiveMinOccurrences = isFilterMode
    ? (request.minOccurrences ?? MIN_OCCURRENCES_DEFAULT)
    : minOccurrences;
  const effectiveLimit = isFilterMode ? (request.limit ?? TERM_RESULT_LIMIT_DEFAULT) : limit;
  const effectiveLastOccurrenceAfter = isFilterMode
    ? (request.lastOccurrenceAfter ?? null)
    : lastOccurrenceAfter;
  const effectiveLastOccurrenceBefore = isFilterMode
    ? (request.lastOccurrenceBefore ?? null)
    : lastOccurrenceBefore;
  const effectiveReviewChangedAfter = isFilterMode
    ? (request.reviewChangedAfter ?? null)
    : reviewChangedAfter;
  const effectiveReviewChangedBefore = isFilterMode
    ? (request.reviewChangedBefore ?? null)
    : reviewChangedBefore;
  const sourceLabel =
    request?.mode === 'selected'
      ? request.entries.length === 1
        ? '1 selected extracted term'
        : `${request?.entries.length.toLocaleString() ?? 0} selected extracted terms`
      : 'Current filtered result set';
  const reviewStatusFilterLabel =
    REVIEW_STATUS_FILTER_OPTIONS.find((option) => option.value === effectiveReviewStatusFilter)
      ?.label ?? effectiveReviewStatusFilter;
  const reviewAuthorityFilterLabel = formatReviewAuthorityFilter(effectiveReviewAuthorityFilter);
  const sampleEntries = request?.entries.slice(0, 6) ?? [];
  const remainingEntryCount = Math.max(0, (request?.entries.length ?? 0) - sampleEntries.length);
  const triagedEntries = report?.entries.slice(0, 10) ?? [];
  const requestedTermCount =
    request?.mode === 'filter' ? effectiveLimit : (request?.entries.length ?? 0);
  const batchCount =
    requestedTermCount > 0 ? Math.ceil(requestedTermCount / TERM_INDEX_AI_REVIEW_BATCH_SIZE) : 0;
  const batchLabel = batchCount === 1 ? '1 AI batch' : `${batchCount.toLocaleString()} AI batches`;
  const reviewScopeLabel =
    request?.mode === 'filter'
      ? `Current filters, up to ${effectiveLimit.toLocaleString()} extracted terms`
      : sourceLabel;
  const updateFilterRequest = (patch: Partial<ExtractedTermTriageRequest>) => {
    if (!request || request.mode !== 'filter' || isReviewing) {
      return;
    }
    onRequestChange({ ...request, ...patch });
  };
  const updateLimit = (value: string) => {
    const parsedValue = Number(value);
    const nextLimit = Number.isFinite(parsedValue)
      ? Math.min(Math.max(1, parsedValue), TERM_RESULT_LIMIT_MAX)
      : TERM_RESULT_LIMIT_DEFAULT;
    updateFilterRequest({ limit: nextLimit });
  };
  const updateMinimumHits = (value: string) => {
    const parsedValue = Number(value);
    const nextMinimum = Number.isFinite(parsedValue)
      ? Math.max(1, Math.floor(parsedValue))
      : MIN_OCCURRENCES_DEFAULT;
    updateFilterRequest({ minOccurrences: nextMinimum });
  };
  const progressReviewableCount = progress?.reviewableEntryCount ?? requestedTermCount;
  const progressReviewedCount = Math.min(
    progress?.reviewedEntryCount ?? 0,
    progressReviewableCount,
  );
  const progressBatchCount = progress?.batchCount ?? batchCount;
  const progressCompletedBatchCount = Math.min(
    progress?.completedBatchCount ?? 0,
    progressBatchCount,
  );
  const progressLabel =
    progressReviewableCount > 0
      ? `${progressReviewedCount.toLocaleString()} of ${progressReviewableCount.toLocaleString()} processed`
      : 'Preparing review scope';

  return (
    <Modal
      open={open}
      size="lg"
      ariaLabel="Run AI review"
      onClose={onClose}
      closeOnBackdrop={!isReviewing}
    >
      <div className="modal__header">
        <div>
          <h3 className="modal__title">
            {report ? 'Extracted term review report' : 'Run AI review'}
          </h3>
          <p className="settings-hint">
            {report
              ? 'The extracted term review statuses were updated by AI review.'
              : 'Ask AI to classify extracted terms before candidate generation.'}
          </p>
        </div>
      </div>

      <div className="modal__body term-index-explorer__generation-modal-body">
        {report ? (
          <>
            <div className="term-index-explorer__triage-report">
              <span>
                <strong>{report.entryCount.toLocaleString()}</strong> matched
              </span>
              <span>
                <strong>{report.reviewedEntryCount.toLocaleString()}</strong> reviewed
              </span>
              <span>
                <strong>{report.updatedEntryCount.toLocaleString()}</strong> updated
              </span>
              <span>
                <strong>{report.acceptedCount.toLocaleString()}</strong> accepted
              </span>
              <span>
                <strong>{report.toReviewCount.toLocaleString()}</strong> to review
              </span>
              <span>
                <strong>{report.rejectedCount.toLocaleString()}</strong> rejected
              </span>
              <span>
                <strong>{report.skippedHumanReviewedCount.toLocaleString()}</strong> skipped human
              </span>
            </div>
            {triagedEntries.length > 0 ? (
              <div className="term-index-explorer__generation-samples">
                <div className="settings-field__label">Reviewed extracted terms</div>
                <ul>
                  {triagedEntries.map((entry) => (
                    <li key={entry.termIndexExtractedTermId}>
                      <span>{entry.term}</span>
                      <span className="settings-hint">#{entry.termIndexExtractedTermId}</span>
                      <span className="settings-hint">
                        {formatReviewStatus(entry.reviewStatus)}
                      </span>
                      {entry.reviewReason ? (
                        <span className="settings-hint">{formatMethod(entry.reviewReason)}</span>
                      ) : null}
                    </li>
                  ))}
                </ul>
              </div>
            ) : (
              <p className="settings-hint">No extracted terms were updated.</p>
            )}
          </>
        ) : (
          <div className="term-index-explorer__generation-summary">
            <div className="term-index-explorer__triage-summary">
              <div>
                <div className="settings-field__label">Scope</div>
                <p>{reviewScopeLabel}</p>
              </div>
              <div>
                <div className="settings-field__label">Runtime</div>
                <p>
                  {batchLabel} of up to {TERM_INDEX_AI_REVIEW_BATCH_SIZE} terms. For 1,000 terms,
                  expect about 20 serial AI calls and a few minutes of runtime; it can take longer
                  if the AI API is slow.
                </p>
              </div>
            </div>
            {isReviewing ? (
              <div className="term-index-explorer__triage-progress">
                <div className="term-index-explorer__triage-progress-header">
                  <span>{progress ? 'Reviewing extracted terms' : 'Waiting for worker'}</span>
                  <span>{progressLabel}</span>
                </div>
                <progress
                  max={Math.max(1, progressReviewableCount)}
                  value={progressReviewedCount}
                />
                <p>
                  {progressBatchCount > 0
                    ? `Batch ${progressCompletedBatchCount.toLocaleString()} of ${progressBatchCount.toLocaleString()}`
                    : 'The job is queued or calculating the review scope.'}
                  {progress
                    ? ` · ${(progress.acceptedCount ?? 0).toLocaleString()} accepted · ${(
                        progress.toReviewCount ?? 0
                      ).toLocaleString()} to review · ${(
                        progress.rejectedCount ?? 0
                      ).toLocaleString()} rejected`
                    : null}
                </p>
              </div>
            ) : null}
            {request?.mode === 'filter' ? (
              <div className="term-index-explorer__generation-controls">
                <div className="settings-grid settings-grid--two-column">
                  <label className="settings-field">
                    <span className="settings-field__label">Search</span>
                    <input
                      className="settings-input"
                      type="search"
                      value={effectiveSearchQuery}
                      placeholder="Any term"
                      onChange={(event) => updateFilterRequest({ searchQuery: event.target.value })}
                      disabled={isReviewing}
                    />
                  </label>
                  <label className="settings-field">
                    <span className="settings-field__label">Extraction method</span>
                    <select
                      className="settings-input"
                      value={effectiveExtractionMethod ?? ALL_EXTRACTORS_FILTER_VALUE}
                      onChange={(event) =>
                        updateFilterRequest({
                          extractionMethod:
                            event.target.value === ALL_EXTRACTORS_FILTER_VALUE
                              ? null
                              : event.target.value,
                        })
                      }
                      disabled={isReviewing}
                    >
                      <option value={ALL_EXTRACTORS_FILTER_VALUE}>All methods</option>
                      {extractionMethodOptions.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="settings-field">
                    <span className="settings-field__label">Status</span>
                    <select
                      className="settings-input"
                      value={effectiveReviewStatusFilter}
                      onChange={(event) =>
                        updateFilterRequest({
                          reviewStatusFilter: event.target.value as ApiTermIndexReviewStatusFilter,
                        })
                      }
                      disabled={isReviewing}
                    >
                      {REVIEW_STATUS_FILTER_OPTIONS.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="settings-field">
                    <span className="settings-field__label">Review source</span>
                    <select
                      className="settings-input"
                      value={effectiveReviewAuthorityFilter}
                      onChange={(event) =>
                        updateFilterRequest({
                          reviewAuthorityFilter: event.target.value,
                        })
                      }
                      disabled={isReviewing}
                    >
                      {REVIEW_AUTHORITY_FILTER_OPTIONS.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="settings-field">
                    <span className="settings-field__label">Minimum hits</span>
                    <input
                      className="settings-input"
                      type="number"
                      min="1"
                      value={effectiveMinOccurrences}
                      onChange={(event) => updateMinimumHits(event.target.value)}
                      disabled={isReviewing}
                    />
                  </label>
                  <label className="settings-field">
                    <span className="settings-field__label">Limit</span>
                    <input
                      className="settings-input"
                      type="number"
                      min="1"
                      max={TERM_RESULT_LIMIT_MAX}
                      value={effectiveLimit}
                      onChange={(event) => updateLimit(event.target.value)}
                      disabled={isReviewing}
                    />
                    <span className="settings-hint">
                      Maximum {TERM_RESULT_LIMIT_MAX.toLocaleString()} terms per run.
                    </span>
                  </label>
                </div>
                <div className="term-index-explorer__generation-filter-summary">
                  <div className="settings-field__label">Current scope</div>
                  <p className="settings-hint">
                    {repositoryScopeLabel}; last extracted{' '}
                    {formatDateRangeFilter(
                      effectiveLastOccurrenceAfter,
                      effectiveLastOccurrenceBefore,
                    )}
                    ; review updated{' '}
                    {formatDateRangeFilter(
                      effectiveReviewChangedAfter,
                      effectiveReviewChangedBefore,
                    )}
                    .
                  </p>
                </div>
              </div>
            ) : (
              <dl className="term-index-explorer__triage-config">
                <div>
                  <dt>Action</dt>
                  <dd>
                    Update extracted-term review status only. Candidates and glossary terms stay
                    unchanged.
                  </dd>
                </div>
                <div>
                  <dt>Repository scope</dt>
                  <dd>{repositoryScopeLabel}</dd>
                </div>
              </dl>
            )}
            <p className="settings-hint">
              Action: update extracted-term review status only. Candidates and glossary terms stay
              unchanged. Default scope is {reviewStatusFilterLabel.toLowerCase()} terms from{' '}
              {reviewAuthorityFilterLabel.toLowerCase()}.
            </p>
            <label className="settings-toggle">
              <input
                type="checkbox"
                checked={overwriteHumanReview}
                onChange={(event) => onOverwriteHumanReviewChange(event.target.checked)}
              />
              <span>Overwrite human review decisions</span>
            </label>
            {sampleEntries.length > 0 ? (
              <div className="term-index-explorer__generation-samples">
                <div className="settings-field__label">Sample extracted terms</div>
                <ul>
                  {sampleEntries.map((entry) => (
                    <li key={entry.id}>
                      <span>{entry.displayTerm}</span>
                      <span className="settings-hint">
                        extracted #{entry.id} · {entry.occurrenceCount.toLocaleString()} hits
                      </span>
                    </li>
                  ))}
                  {remainingEntryCount > 0 ? (
                    <li className="settings-hint">
                      +{remainingEntryCount.toLocaleString()} more in scope
                    </li>
                  ) : null}
                </ul>
              </div>
            ) : null}
            {error ? <p className="settings-hint is-error">{getErrorMessage(error)}</p> : null}
          </div>
        )}
      </div>

      <div className="modal__footer term-index-explorer__modal-footer">
        <button
          type="button"
          className="settings-button settings-button--ghost"
          onClick={onClose}
          disabled={isReviewing}
        >
          {report ? 'Close' : 'Cancel'}
        </button>
        {report ? null : (
          <button
            type="button"
            className="settings-button settings-button--primary"
            onClick={onTriage}
            disabled={isReviewing || !request}
          >
            {isReviewing ? 'Reviewing...' : 'Start AI review'}
          </button>
        )}
      </div>
    </Modal>
  );
}

function CandidateReviewProjectModal({
  open,
  request,
  glossaries,
  isLoadingGlossaries,
  teamOptions,
  specialistOptions,
  deciderOptions,
  isLoadingTeams,
  isLoadingSpecialists,
  isLoadingDeciders,
  isCreating,
  error,
  onChangeGlossary,
  onChangeTeam,
  onChangeSpecialists,
  onChangeDecider,
  onClose,
  onCreate,
}: {
  open: boolean;
  request: CandidateReviewRequest | null;
  glossaries: ApiGlossarySummary[];
  isLoadingGlossaries: boolean;
  teamOptions: SingleSelectOption<number>[];
  specialistOptions: Array<{ value: number; label: string }>;
  deciderOptions: SingleSelectOption<number>[];
  isLoadingTeams: boolean;
  isLoadingSpecialists: boolean;
  isLoadingDeciders: boolean;
  isCreating: boolean;
  error: Error | null;
  onChangeGlossary: (glossaryId: number | null) => void;
  onChangeTeam: (teamId: number | null) => void;
  onChangeSpecialists: (specialistUserIds: number[]) => void;
  onChangeDecider: (pmUserId: number | null) => void;
  onClose: () => void;
  onCreate: () => void;
}) {
  const selectedGlossaryId = request?.glossaryId ?? '';
  const selectedTeamId = request?.teamId ?? null;
  const selectedCandidateCount = request?.candidateIds.length ?? 0;
  const selectedSourceCount = request?.selectedEntryCount ?? selectedCandidateCount;
  return (
    <Modal
      open={open}
      size="md"
      onClose={onClose}
      closeOnBackdrop={!isCreating}
      ariaLabel="Create candidate review project"
    >
      <div className="modal__header">
        <div>
          <h3 className="modal__title">Create candidate review</h3>
          <p className="settings-hint">
            Review generated candidates as proposals. Select a target glossary to promote accepted
            proposals during decider resolution.
          </p>
        </div>
      </div>
      <div className="modal__body term-index-explorer__generation-modal-body">
        <div className="settings-field">
          <label className="settings-field__label" htmlFor="term-index-candidate-review-glossary">
            Target glossary
          </label>
          <select
            id="term-index-candidate-review-glossary"
            className="settings-input"
            value={selectedGlossaryId}
            onChange={(event) =>
              onChangeGlossary(event.target.value ? Number(event.target.value) : null)
            }
            disabled={isCreating || isLoadingGlossaries}
          >
            <option value="">No glossary, candidate status only</option>
            {glossaries.map((glossary) => (
              <option key={glossary.id} value={glossary.id}>
                {glossary.name}
              </option>
            ))}
          </select>
          <span className="settings-hint">
            If selected, accepted candidates are promoted into this glossary by default. Deciders
            can still accept individual candidates without glossary inclusion.
          </span>
        </div>
        <div className="settings-field">
          <span className="settings-field__label">Review team</span>
          <SingleSelectDropdown<number>
            label="Review team"
            options={teamOptions}
            value={selectedTeamId}
            onChange={onChangeTeam}
            className="term-index-explorer__candidate-review-picker"
            placeholder={isLoadingTeams ? 'Loading teams...' : 'Select review team'}
            disabled={isCreating || isLoadingTeams}
            buttonAriaLabel="Choose candidate review team"
            searchPlaceholder="Filter teams"
            noResultsLabel="No teams found"
          />
        </div>
        <div className="settings-field">
          <span className="settings-field__label">Advisors</span>
          <MultiSelectChip<number>
            label="Advisors"
            options={specialistOptions}
            selectedValues={request?.specialistUserIds ?? []}
            onChange={onChangeSpecialists}
            className="term-index-explorer__candidate-review-picker"
            placeholder="Choose advisors"
            emptyOptionsLabel={
              selectedTeamId == null
                ? 'Select a team first'
                : isLoadingSpecialists
                  ? 'Loading advisors...'
                  : 'No advisor users in this team'
            }
            disabled={isCreating || selectedTeamId == null || isLoadingSpecialists}
            buttonAriaLabel="Choose candidate review advisors"
            searchPlaceholder="Filter advisors"
            noResultsLabel="No advisors found"
          />
          <span className="settings-hint">
            Leave empty to skip advisor review and create only the decider row.
          </span>
        </div>
        <div className="settings-field">
          <span className="settings-field__label">Decider</span>
          <SingleSelectDropdown<number>
            label="Decider"
            options={deciderOptions}
            value={request?.pmUserId ?? null}
            onChange={onChangeDecider}
            className="term-index-explorer__candidate-review-picker"
            placeholder={
              selectedTeamId == null
                ? 'Select team first'
                : isLoadingDeciders
                  ? 'Loading deciders...'
                  : 'Leave unassigned'
            }
            noneLabel="Leave unassigned"
            disabled={isCreating || selectedTeamId == null || isLoadingDeciders}
            buttonAriaLabel="Choose candidate review decider"
            searchPlaceholder="Filter deciders"
            noResultsLabel="No deciders found"
          />
        </div>
        <p className="settings-hint">
          {request == null
            ? 'No candidates selected.'
            : `${selectedCandidateCount.toLocaleString()} candidate${
                selectedCandidateCount === 1 ? '' : 's'
              } will be reviewed from ${selectedSourceCount.toLocaleString()} selected row${
                selectedSourceCount === 1 ? '' : 's'
              }.`}
        </p>
        {error ? <p className="settings-hint is-error">{getErrorMessage(error)}</p> : null}
      </div>
      <div className="modal__footer term-index-explorer__modal-footer">
        <button type="button" className="modal__button" onClick={onClose} disabled={isCreating}>
          Cancel
        </button>
        <button
          type="button"
          className="modal__button modal__button--primary"
          onClick={onCreate}
          disabled={
            isCreating ||
            request?.glossaryId == null ||
            request.teamId == null ||
            request.candidateIds.length === 0
          }
        >
          {isCreating ? 'Creating...' : 'Create review project'}
        </button>
      </div>
    </Modal>
  );
}

function CandidateGenerationModal({
  open,
  request,
  report,
  repositoryScopeLabel,
  searchQuery,
  extractionMethod,
  reviewStatusFilter,
  reviewAuthorityFilter,
  isGenerating,
  error,
  onClose,
  onRequestChange,
  onGenerate,
}: {
  open: boolean;
  request: CandidateGenerationRequest | null;
  report: ApiGenerateTermIndexCandidatesResponse | null;
  repositoryScopeLabel: string;
  searchQuery: string;
  extractionMethod: string | null;
  reviewStatusFilter: ApiTermIndexReviewStatusFilter;
  reviewAuthorityFilter: ApiTermIndexReviewAuthorityFilter;
  isGenerating: boolean;
  error: unknown;
  onClose: () => void;
  onRequestChange: (request: CandidateGenerationRequest | null) => void;
  onGenerate: () => void;
}) {
  const selectedEntryCount = request?.entries.length ?? 0;
  const draft = request?.draft ?? null;
  const sourceLabel =
    request?.mode === 'single'
      ? (request.entries[0]?.displayTerm ?? 'Selected extracted term')
      : request?.mode === 'filter'
        ? 'Accepted extracted terms in default scope'
        : selectedEntryCount === 1
          ? '1 selected extracted term'
          : `${selectedEntryCount.toLocaleString()} selected extracted terms`;
  const effectiveSearchQuery =
    request?.mode === 'filter' ? (request.searchQuery ?? '') : searchQuery;
  const effectiveExtractionMethod =
    request?.mode === 'filter' ? (request.extractionMethod ?? null) : extractionMethod;
  const effectiveReviewStatusFilter =
    request?.mode === 'filter' ? (request.reviewStatusFilter ?? 'ACCEPTED') : reviewStatusFilter;
  const effectiveReviewAuthorityFilter =
    request?.mode === 'filter' ? (request.reviewAuthorityFilter ?? 'ALL') : reviewAuthorityFilter;
  const effectiveMinOccurrences =
    request?.mode === 'filter'
      ? (request.minOccurrences ?? MIN_OCCURRENCES_DEFAULT)
      : MIN_OCCURRENCES_DEFAULT;
  const sampleEntries = request?.entries.slice(0, 6) ?? [];
  const remainingEntryCount = Math.max(0, selectedEntryCount - sampleEntries.length);
  const reviewStatusLabel =
    REVIEW_STATUS_OPTIONS.find((option) => option.value === draft?.reviewStatus)?.label ??
    'To review';
  const reviewStatusFilterLabel =
    REVIEW_STATUS_FILTER_OPTIONS.find((option) => option.value === effectiveReviewStatusFilter)
      ?.label ?? effectiveReviewStatusFilter;
  const reviewAuthorityFilterLabel = formatReviewAuthorityFilter(effectiveReviewAuthorityFilter);
  const hasDraftOverrides =
    draft != null && Object.values(draft.editedFields).some((isEdited) => Boolean(isEdited));
  const generatedCandidates = report?.candidates.slice(0, 10) ?? [];
  const updateBatchLimit = (value: string) => {
    if (!request || request.mode !== 'filter') {
      return;
    }
    const parsedValue = Number(value);
    const nextLimit = Number.isFinite(parsedValue)
      ? Math.min(Math.max(1, parsedValue), TERM_RESULT_LIMIT_MAX)
      : TERM_RESULT_LIMIT_DEFAULT;
    onRequestChange({ ...request, limit: nextLimit });
  };
  const updateRefreshExistingCandidates = (refreshExistingCandidates: boolean) => {
    if (!request || request.mode !== 'filter') {
      return;
    }
    onRequestChange({ ...request, refreshExistingCandidates });
  };
  const updateReviewStatusFilter = (value: ApiTermIndexReviewStatusFilter) => {
    if (!request || request.mode !== 'filter') {
      return;
    }
    onRequestChange({ ...request, reviewStatusFilter: value });
  };
  const updateReviewAuthorityFilter = (value: ApiTermIndexReviewAuthorityFilter) => {
    if (!request || request.mode !== 'filter') {
      return;
    }
    onRequestChange({ ...request, reviewAuthorityFilter: value });
  };

  return (
    <Modal
      open={open}
      size="lg"
      ariaLabel="Generate term index candidates"
      onClose={onClose}
      closeOnBackdrop={!isGenerating}
    >
      <div className="modal__header">
        <div>
          <h3 className="modal__title">
            {report
              ? 'Candidate generation report'
              : request?.mode === 'single'
                ? 'Generate this candidate'
                : request?.mode === 'filter'
                  ? 'Generate candidates'
                  : 'Generate candidates for selection'}
          </h3>
          <p className="settings-hint">
            {report
              ? 'The candidate table has been refreshed from the selected extracted terms.'
              : request?.mode === 'filter'
                ? 'Create stored candidates for accepted extracted terms that do not already have one.'
                : 'Create or refresh stored candidates from reviewed extracted terms. AI fills candidate fields when they are not edited here.'}
          </p>
        </div>
      </div>

      <div className="modal__body term-index-explorer__generation-modal-body">
        {report || request?.mode !== 'filter' ? (
          <dl className="term-index-explorer__generation-facts">
            <div>
              <dt>Source</dt>
              <dd>{sourceLabel}</dd>
            </div>
            <div>
              <dt>Repository scope</dt>
              <dd>{repositoryScopeLabel}</dd>
            </div>
            <div>
              <dt>Search filter</dt>
              <dd>{effectiveSearchQuery.trim() || 'None'}</dd>
            </div>
            <div>
              <dt>Extraction method</dt>
              <dd>{formatMethod(effectiveExtractionMethod)}</dd>
            </div>
            <div>
              <dt>Raw term review filter</dt>
              <dd>
                {request?.mode === 'filter' ? 'Accepted' : reviewStatusFilterLabel} ·{' '}
                {reviewAuthorityFilterLabel}
              </dd>
            </div>
            <div>
              <dt>Limit</dt>
              <dd>
                {request?.mode === 'filter'
                  ? `${(request.limit ?? TERM_RESULT_LIMIT_DEFAULT).toLocaleString()} terms`
                  : 'Selected terms only'}
              </dd>
            </div>
          </dl>
        ) : null}

        {report ? (
          <>
            <dl className="term-index-explorer__generation-facts term-index-explorer__generation-facts--report">
              <div>
                <dt>Total</dt>
                <dd>{report.candidateCount.toLocaleString()}</dd>
              </div>
              <div>
                <dt>New</dt>
                <dd>{report.createdCandidateCount.toLocaleString()}</dd>
              </div>
              <div>
                <dt>Refreshed</dt>
                <dd>{report.updatedCandidateCount.toLocaleString()}</dd>
              </div>
              <div>
                <dt>Skipped existing</dt>
                <dd>{(report.skippedExistingCandidateCount ?? 0).toLocaleString()}</dd>
              </div>
            </dl>
            {generatedCandidates.length > 0 ? (
              <div className="term-index-explorer__generation-samples">
                <div className="settings-field__label">Generated candidates</div>
                <p className="settings-hint">
                  The candidate source list has been refreshed. Rows that were generated now show
                  their candidate IDs in this page.
                </p>
                <ul>
                  {generatedCandidates.map((candidate) => (
                    <li key={candidate.termIndexCandidateId}>
                      <span>{candidate.term}</span>
                      <span className="settings-hint">#{candidate.termIndexCandidateId}</span>
                      {candidate.label ? (
                        <span className="settings-hint">{candidate.label}</span>
                      ) : null}
                      {candidate.termIndexExtractedTermId != null ? (
                        <span className="settings-hint">
                          extracted #{candidate.termIndexExtractedTermId}
                        </span>
                      ) : null}
                      {candidate.definition ? (
                        <span className="settings-hint">{candidate.definition}</span>
                      ) : null}
                    </li>
                  ))}
                </ul>
              </div>
            ) : (
              <p className="settings-hint">
                No candidates were generated. Rejected or missing extracted terms are skipped.
              </p>
            )}
          </>
        ) : (
          <div className="term-index-explorer__generation-summary">
            {request?.mode === 'filter' ? (
              <div className="term-index-explorer__generation-controls">
                <div className="settings-grid settings-grid--two-column">
                  <label className="settings-field">
                    <span className="settings-field__label">Status</span>
                    <select
                      className="settings-input"
                      value={effectiveReviewStatusFilter}
                      onChange={(event) =>
                        updateReviewStatusFilter(
                          event.target.value as ApiTermIndexReviewStatusFilter,
                        )
                      }
                      disabled={isGenerating}
                    >
                      {REVIEW_STATUS_FILTER_OPTIONS.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="settings-field">
                    <span className="settings-field__label">Review source</span>
                    <select
                      className="settings-input"
                      value={effectiveReviewAuthorityFilter}
                      onChange={(event) => updateReviewAuthorityFilter(event.target.value)}
                      disabled={isGenerating}
                    >
                      {REVIEW_AUTHORITY_FILTER_OPTIONS.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="settings-field">
                    <span className="settings-field__label">Batch limit</span>
                    <input
                      className="settings-input"
                      type="number"
                      min="1"
                      max={TERM_RESULT_LIMIT_MAX}
                      value={request.limit ?? TERM_RESULT_LIMIT_DEFAULT}
                      onChange={(event) => updateBatchLimit(event.target.value)}
                      disabled={isGenerating}
                    />
                    <span className="settings-hint">
                      Maximum {TERM_RESULT_LIMIT_MAX.toLocaleString()} terms per run.
                    </span>
                  </label>
                  <label className="settings-toggle term-index-explorer__modal-toggle-field">
                    <input
                      type="checkbox"
                      checked={Boolean(request.refreshExistingCandidates)}
                      onChange={(event) => updateRefreshExistingCandidates(event.target.checked)}
                      disabled={isGenerating}
                    />
                    <span>Refresh existing candidates too</span>
                  </label>
                </div>
                <div className="term-index-explorer__generation-filter-summary">
                  <div className="settings-field__label">Default scope</div>
                  <p className="settings-hint">
                    {reviewStatusFilterLabel} extracted terms from{' '}
                    {repositoryScopeLabel.toLowerCase()}; {reviewAuthorityFilterLabel.toLowerCase()}
                    ; no search filter; all extraction methods;{' '}
                    {effectiveMinOccurrences.toLocaleString()}+ hit. Existing candidates are skipped
                    unless refresh is enabled.
                  </p>
                </div>
              </div>
            ) : null}
            <div className="settings-field__label">This will</div>
            <ul>
              <li>
                {request?.mode === 'filter'
                  ? 'Create missing candidates for accepted extracted terms in the default scope.'
                  : 'Create missing candidates for the selected extracted terms.'}
              </li>
              {request?.mode === 'filter' && !request.refreshExistingCandidates ? null : (
                <li>
                  {request?.mode === 'filter'
                    ? 'Refresh existing extraction-backed candidates for accepted terms in the current filters.'
                    : 'Refresh existing extraction-backed candidates for the same extracted terms.'}
                </li>
              )}
              <li>Ask AI to fill definition, rationale, and candidate metadata where possible.</li>
              {request?.mode === 'filter' ? null : (
                <li>Use edited fields from this form as overrides.</li>
              )}
              <li>Leave glossary terms unchanged until a candidate is accepted in a glossary.</li>
              <li>
                {request?.mode === 'filter'
                  ? 'Use the configured batch limit for this run.'
                  : 'Skip rejected extracted terms if any are selected.'}
              </li>
            </ul>
            {draft ? (
              <div className="term-index-explorer__generation-overrides">
                <div className="settings-field__label">Candidate fields</div>
                {hasDraftOverrides ? (
                  <dl>
                    {draft.editedFields.termType ? (
                      <div>
                        <dt>Term type</dt>
                        <dd>
                          {TERM_TYPE_OPTIONS.find((option) => option.value === draft.termType)
                            ?.label ?? draft.termType}
                        </dd>
                      </div>
                    ) : null}
                    {draft.editedFields.enforcement ? (
                      <div>
                        <dt>Enforcement</dt>
                        <dd>
                          {ENFORCEMENT_OPTIONS.find((option) => option.value === draft.enforcement)
                            ?.label ?? draft.enforcement}
                        </dd>
                      </div>
                    ) : null}
                    {draft.editedFields.reviewStatus ? (
                      <div>
                        <dt>Review status</dt>
                        <dd>{reviewStatusLabel}</dd>
                      </div>
                    ) : null}
                    {draft.editedFields.definition && draft.definition.trim() ? (
                      <div>
                        <dt>Definition</dt>
                        <dd>{draft.definition.trim()}</dd>
                      </div>
                    ) : null}
                    {draft.editedFields.rationale && draft.rationale.trim() ? (
                      <div>
                        <dt>Rationale</dt>
                        <dd>{draft.rationale.trim()}</dd>
                      </div>
                    ) : null}
                    {draft.editedFields.partOfSpeech && draft.partOfSpeech.trim() ? (
                      <div>
                        <dt>Part of speech</dt>
                        <dd>{draft.partOfSpeech.trim()}</dd>
                      </div>
                    ) : null}
                    {draft.editedFields.confidence && draft.confidence.trim() ? (
                      <div>
                        <dt>Confidence</dt>
                        <dd>{draft.confidence.trim()}%</dd>
                      </div>
                    ) : null}
                    {draft.editedFields.doNotTranslate ? (
                      <div>
                        <dt>Flags</dt>
                        <dd>{draft.doNotTranslate ? 'Do not translate' : 'Translate normally'}</dd>
                      </div>
                    ) : null}
                  </dl>
                ) : (
                  <p className="settings-hint">
                    No custom fields set. Defaults will be used for this candidate.
                  </p>
                )}
              </div>
            ) : (
              <p className="settings-hint">
                {request?.mode === 'filter'
                  ? 'Batch generation asks AI to fill candidate fields for accepted terms and keeps generated candidates in To review.'
                  : 'Bulk generation asks AI to fill candidate fields and keeps generated candidates in To review.'}
              </p>
            )}
            {sampleEntries.length > 0 ? (
              <div className="term-index-explorer__generation-samples">
                <div className="settings-field__label">Selected extracted terms</div>
                <ul>
                  {sampleEntries.map((entry) => (
                    <li key={entry.id}>
                      <span>{entry.displayTerm}</span>
                      <span className="settings-hint">
                        extracted #{entry.id} · {entry.occurrenceCount.toLocaleString()} hits
                      </span>
                    </li>
                  ))}
                  {remainingEntryCount > 0 ? (
                    <li className="settings-hint">
                      +{remainingEntryCount.toLocaleString()} more selected
                    </li>
                  ) : null}
                </ul>
              </div>
            ) : null}
            {error ? <p className="settings-hint is-error">{getErrorMessage(error)}</p> : null}
          </div>
        )}
      </div>

      <div className="modal__footer term-index-explorer__modal-footer">
        <button
          type="button"
          className="settings-button settings-button--ghost"
          onClick={onClose}
          disabled={isGenerating}
        >
          {report ? 'Close' : 'Cancel'}
        </button>
        {report ? null : (
          <button
            type="button"
            className="settings-button settings-button--primary"
            onClick={onGenerate}
            disabled={
              isGenerating ||
              !request ||
              (request.mode !== 'filter' && request.entryIds.length === 0)
            }
          >
            {isGenerating
              ? 'Generating...'
              : request?.mode === 'single'
                ? 'Generate'
                : request?.mode === 'filter'
                  ? 'Generate candidates'
                  : 'Generate selected'}
          </button>
        )}
      </div>
    </Modal>
  );
}

function CandidateDraftPanel({
  entry,
  draft,
  occurrences,
  isLoadingOccurrences,
  occurrenceError,
  isSaving,
  onDraftChange,
  onSave,
}: {
  entry: ApiTermIndexEntry;
  draft: CandidateDraft;
  occurrences: ApiTermIndexOccurrence[];
  isLoadingOccurrences: boolean;
  occurrenceError: unknown;
  isSaving: boolean;
  onDraftChange: (draft: CandidateDraft) => void;
  onSave: () => void;
}) {
  const updateDraft = <K extends CandidateDraftField>(key: K, value: CandidateDraft[K]) => {
    onDraftChange({
      ...draft,
      [key]: value,
      editedFields: { ...draft.editedFields, [key]: true },
    });
  };
  const createdLabel = entry.createdDate ? formatLocalDateTime(entry.createdDate) : null;
  const candidateReviewLabel = formatReviewChange(
    entry.candidateReviewStatus,
    entry.candidateReviewAuthority,
    entry.candidateReviewChangedAt,
    entry.candidateReviewChangedByCommonName,
    entry.candidateReviewChangedByUsername,
  );
  const extractedTermId = entry.termIndexExtractedTermId;

  return (
    <div className="term-index-explorer__candidate-panel">
      <section className="term-index-explorer__candidate-card" aria-label="Candidate draft">
        <div className="term-index-explorer__candidate-header">
          <div className="term-index-explorer__candidate-heading">
            <div className="term-index-explorer__candidate-title-line">
              <div className="term-index-explorer__candidate-title">{entry.displayTerm}</div>
              <span className="term-index-explorer__candidate-primary-id">
                #{entry.termIndexCandidateId ?? entry.id}
              </span>
            </div>
            <div className="term-index-explorer__candidate-meta-line">
              {extractedTermId != null ? (
                <Link to={getRawTermExplorerPath(extractedTermId, entry.displayTerm)}>
                  Extracted term #{extractedTermId}
                </Link>
              ) : (
                <span>No linked extracted term</span>
              )}
              <span>{entry.occurrenceCount.toLocaleString()} hits</span>
              <span>{entry.repositoryCount.toLocaleString()} repositories</span>
              {createdLabel ? (
                <time dateTime={entry.createdDate ?? undefined} title={createdLabel}>
                  Candidate created {createdLabel}
                </time>
              ) : null}
              <span>{candidateReviewLabel}</span>
            </div>
          </div>
          <button
            type="button"
            className="settings-button settings-button--primary"
            onClick={onSave}
            disabled={isSaving}
          >
            {isSaving ? 'Saving' : 'Save candidate'}
          </button>
        </div>
        <div className="settings-grid settings-grid--two-column">
          <label className="settings-field">
            <span className="settings-field__label">Term type</span>
            <select
              className="settings-input"
              value={draft.termType}
              onChange={(event) => updateDraft('termType', event.target.value)}
            >
              {TERM_TYPE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="settings-field">
            <span className="settings-field__label">Enforcement</span>
            <select
              className="settings-input"
              value={draft.enforcement}
              onChange={(event) => updateDraft('enforcement', event.target.value)}
            >
              {ENFORCEMENT_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="settings-field">
            <span className="settings-field__label">Part of speech</span>
            <input
              className="settings-input"
              value={draft.partOfSpeech}
              onChange={(event) => updateDraft('partOfSpeech', event.target.value)}
              placeholder="Optional"
            />
          </label>
          <label className="settings-field">
            <span className="settings-field__label">Confidence</span>
            <input
              className="settings-input"
              type="number"
              min="0"
              max="100"
              value={draft.confidence}
              onChange={(event) => updateDraft('confidence', event.target.value)}
              placeholder="Automatic"
            />
          </label>
        </div>
        <label className="settings-field">
          <span className="settings-field__label">Definition</span>
          <textarea
            className="settings-input term-index-explorer__candidate-textarea"
            value={draft.definition}
            onChange={(event) => updateDraft('definition', event.target.value)}
            placeholder="Optional candidate definition"
          />
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Rationale</span>
          <textarea
            className="settings-input term-index-explorer__candidate-textarea"
            value={draft.rationale}
            onChange={(event) => updateDraft('rationale', event.target.value)}
            placeholder="Optional review rationale"
          />
        </label>
        <div className="term-index-explorer__candidate-options">
          <label className="settings-toggle">
            <input
              type="checkbox"
              checked={draft.doNotTranslate}
              onChange={(event) => updateDraft('doNotTranslate', event.target.checked)}
            />
            <span>Do not translate</span>
          </label>
          <SingleSelectDropdown
            label="Candidate review"
            value={draft.reviewStatus}
            options={REVIEW_STATUS_OPTIONS}
            onChange={(next) => updateDraft('reviewStatus', next ?? 'TO_REVIEW')}
            className="term-index-explorer__candidate-review"
            buttonAriaLabel="Set generated candidate review status"
            searchable={false}
          />
        </div>
      </section>
      <section className="term-index-explorer__candidate-card" aria-label="Usage examples">
        <div className="term-index-explorer__candidate-section-title">Usage examples</div>
        <OccurrencePanel
          entryId={extractedTermId ?? 0}
          occurrences={occurrences}
          isLoading={isLoadingOccurrences}
          error={occurrenceError}
        />
      </section>
    </div>
  );
}

function ExtractedTermDetailPanel({
  entry,
  occurrences,
  isLoading,
  error,
  isSavingReview,
  reviewError,
  onOpenReviewOverride,
}: {
  entry: ApiTermIndexEntry;
  occurrences: ApiTermIndexOccurrence[];
  isLoading: boolean;
  error: unknown;
  isSavingReview: boolean;
  reviewError: unknown;
  onOpenReviewOverride: (entry: ApiTermIndexEntry) => void;
}) {
  return (
    <div className="term-index-explorer__entry-detail-panel">
      <TermIndexReviewSummary
        entry={entry}
        isSavingReview={isSavingReview}
        reviewError={reviewError}
        onOpenReviewOverride={onOpenReviewOverride}
      />
      <OccurrencePanel
        entryId={entry.id}
        occurrences={occurrences}
        isLoading={isLoading}
        error={error}
      />
    </div>
  );
}

function TermIndexReviewSummary({
  entry,
  isSavingReview,
  reviewError,
  onOpenReviewOverride,
}: {
  entry: ApiTermIndexEntry;
  isSavingReview: boolean;
  reviewError: unknown;
  onOpenReviewOverride: (entry: ApiTermIndexEntry) => void;
}) {
  const reviewActor =
    entry.reviewChangedByCommonName?.trim() ||
    entry.reviewChangedByUsername?.trim() ||
    reviewAuthorityLabel(entry.reviewAuthority);
  const reviewStatus = formatReviewStatus(entry.reviewStatus);
  const reviewReason = entry.reviewReason ? formatMethod(entry.reviewReason) : 'No reason set';
  const reviewRationale = entry.reviewRationale?.trim() || 'No rationale captured.';
  const reviewConfidence =
    entry.reviewConfidence == null ? 'No confidence' : `${entry.reviewConfidence}% confidence`;

  return (
    <section className="term-index-explorer__review-summary" aria-label="Extracted term review">
      <div className="term-index-explorer__review-summary-header">
        <div className="term-index-explorer__review-title-group">
          <div className="term-index-explorer__review-eyebrow">Extracted term #{entry.id}</div>
          <h2 className="term-index-explorer__review-title">{entry.displayTerm}</h2>
        </div>
        <button
          type="button"
          className="settings-button settings-button--ghost term-index-explorer__review-override-button"
          onClick={() => onOpenReviewOverride(entry)}
          disabled={isSavingReview}
        >
          Override review
        </button>
      </div>

      <dl className="term-index-explorer__review-facts">
        <div>
          <dt>Status</dt>
          <dd>{reviewStatus}</dd>
        </div>
        <div>
          <dt>Reason</dt>
          <dd>{reviewReason}</dd>
        </div>
        <div>
          <dt>Confidence</dt>
          <dd>{reviewConfidence}</dd>
        </div>
      </dl>

      <div className="term-index-explorer__review-rationale">
        <div className="term-index-explorer__review-rationale-label">Rationale</div>
        <p>{reviewRationale}</p>
      </div>
      {reviewError ? (
        <p className="settings-hint is-error">{getErrorMessage(reviewError)}</p>
      ) : null}

      <div className="term-index-explorer__entry-metadata" aria-label="Term index metadata">
        <span>Created {formatLocalDateTime(entry.createdDate)}</span>
        <span>Updated {formatLocalDateTime(entry.lastModifiedDate)}</span>
        {entry.reviewChangedAt ? (
          <span>
            Reviewed by {reviewActor} on {formatLocalDateTime(entry.reviewChangedAt)}
          </span>
        ) : null}
        {entry.lastOccurrenceAt ? (
          <span>Last hit {formatLocalDateTime(entry.lastOccurrenceAt)}</span>
        ) : null}
      </div>
    </section>
  );
}

function TermIndexReviewOverrideModal({
  entry,
  isSaving,
  error,
  onClose,
  onSave,
}: {
  entry: ApiTermIndexEntry | null;
  isSaving: boolean;
  error: unknown;
  onClose: () => void;
  onSave: (entry: ApiTermIndexEntry, update: TermIndexReviewUpdate) => void;
}) {
  const [draft, setDraft] = useState<TermIndexReviewDraft>(() => createReviewDraft(entry));
  const hasChanges = entry != null && hasReviewDraftChanged(draft, entry);

  useEffect(() => {
    setDraft(createReviewDraft(entry));
  }, [entry]);

  const updateDraft = <K extends keyof TermIndexReviewDraft>(
    field: K,
    value: TermIndexReviewDraft[K],
  ) => {
    setDraft((current) => ({ ...current, [field]: value }));
  };

  const saveReview = () => {
    if (entry == null || !hasChanges || isSaving) {
      return;
    }
    onSave(entry, getReviewDraftUpdate(draft));
  };

  return (
    <Modal
      open={entry != null}
      size="md"
      ariaLabel="Override extracted term review"
      onClose={onClose}
      closeOnBackdrop={!isSaving}
    >
      <div className="modal__header">
        <div>
          <h3 className="modal__title">Override review</h3>
          <p className="settings-hint">
            {entry ? `${entry.displayTerm} · extracted term #${entry.id}` : ''}
          </p>
        </div>
      </div>

      <div className="modal__body term-index-explorer__review-modal-body">
        <div className="settings-grid settings-grid--two-column">
          <label className="settings-field">
            <span className="settings-field__label">Status</span>
            <select
              className="settings-input"
              value={draft.reviewStatus}
              onChange={(event) => updateDraft('reviewStatus', event.target.value)}
              disabled={isSaving}
            >
              {REVIEW_STATUS_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="settings-field">
            <span className="settings-field__label">Reason</span>
            <select
              className="settings-input"
              value={draft.reviewReason}
              onChange={(event) => updateDraft('reviewReason', event.target.value)}
              disabled={isSaving}
            >
              <option value="">No reason</option>
              {REVIEW_REASON_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
        </div>

        <label className="settings-field">
          <span className="settings-field__label">Confidence</span>
          <input
            className="settings-input"
            type="number"
            min="0"
            max="100"
            value={draft.reviewConfidence}
            onChange={(event) => updateDraft('reviewConfidence', event.target.value)}
            placeholder="Optional"
            disabled={isSaving}
          />
        </label>

        <label className="settings-field">
          <span className="settings-field__label">Rationale</span>
          <textarea
            className="settings-input term-index-explorer__review-modal-textarea"
            value={draft.reviewRationale}
            onChange={(event) => updateDraft('reviewRationale', event.target.value)}
            placeholder="Optional review rationale"
            rows={4}
            disabled={isSaving}
          />
        </label>

        {error ? <p className="settings-hint is-error">{getErrorMessage(error)}</p> : null}
      </div>

      <div className="modal__footer term-index-explorer__modal-footer">
        <button
          type="button"
          className="settings-button settings-button--ghost"
          onClick={onClose}
          disabled={isSaving}
        >
          Cancel
        </button>
        <button
          type="button"
          className="settings-button settings-button--primary"
          onClick={saveReview}
          disabled={!hasChanges || isSaving}
        >
          {isSaving ? 'Saving...' : 'Save review'}
        </button>
      </div>
    </Modal>
  );
}

function TermIndexBatchReviewModal({
  open,
  selectedCount,
  isSaving,
  error,
  onClose,
  onSave,
}: {
  open: boolean;
  selectedCount: number;
  isSaving: boolean;
  error: unknown;
  onClose: () => void;
  onSave: (update: TermIndexBatchReviewUpdate) => void;
}) {
  const [draft, setDraft] = useState<TermIndexBatchReviewDraft>(() => createBatchReviewDraft());

  useEffect(() => {
    if (open) {
      setDraft(createBatchReviewDraft());
    }
  }, [open]);

  const updateDraft = <K extends keyof TermIndexBatchReviewDraft>(
    field: K,
    value: TermIndexBatchReviewDraft[K],
  ) => {
    setDraft((current) => ({ ...current, [field]: value }));
  };

  const saveReview = () => {
    if (selectedCount === 0 || isSaving) {
      return;
    }
    onSave(getBatchReviewDraftUpdate(draft));
  };

  const selectedLabel =
    selectedCount === 1 ? '1 selected term' : `${selectedCount.toLocaleString()} selected terms`;

  return (
    <Modal
      open={open}
      size="md"
      ariaLabel="Bulk update extracted term reviews"
      onClose={onClose}
      closeOnBackdrop={!isSaving}
    >
      <div className="modal__header">
        <div>
          <h3 className="modal__title">Bulk update review</h3>
          <p className="settings-hint">{selectedLabel}</p>
        </div>
      </div>

      <div className="modal__body term-index-explorer__review-modal-body">
        <label className="settings-field">
          <span className="settings-field__label">Status</span>
          <select
            className="settings-input"
            value={draft.reviewStatus}
            onChange={(event) => updateDraft('reviewStatus', event.target.value)}
            disabled={isSaving}
          >
            {REVIEW_STATUS_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>

        <div className="term-index-explorer__bulk-review-field">
          <label className="settings-toggle">
            <input
              type="checkbox"
              checked={draft.updateReviewReason}
              onChange={(event) => updateDraft('updateReviewReason', event.target.checked)}
              disabled={isSaving}
            />
            <span>Set reason</span>
          </label>
          <select
            className="settings-input"
            value={draft.reviewReason}
            onChange={(event) => updateDraft('reviewReason', event.target.value)}
            disabled={isSaving || !draft.updateReviewReason}
          >
            <option value="">No reason</option>
            {REVIEW_REASON_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>

        <div className="term-index-explorer__bulk-review-field">
          <label className="settings-toggle">
            <input
              type="checkbox"
              checked={draft.updateReviewConfidence}
              onChange={(event) => updateDraft('updateReviewConfidence', event.target.checked)}
              disabled={isSaving}
            />
            <span>Set confidence</span>
          </label>
          <input
            className="settings-input"
            type="number"
            min="0"
            max="100"
            value={draft.reviewConfidence}
            onChange={(event) => updateDraft('reviewConfidence', event.target.value)}
            placeholder="Optional"
            disabled={isSaving || !draft.updateReviewConfidence}
          />
        </div>

        <div className="term-index-explorer__bulk-review-field">
          <label className="settings-toggle">
            <input
              type="checkbox"
              checked={draft.updateReviewRationale}
              onChange={(event) => updateDraft('updateReviewRationale', event.target.checked)}
              disabled={isSaving}
            />
            <span>Set rationale</span>
          </label>
          <textarea
            className="settings-input term-index-explorer__review-modal-textarea"
            value={draft.reviewRationale}
            onChange={(event) => updateDraft('reviewRationale', event.target.value)}
            placeholder="Optional review rationale"
            rows={4}
            disabled={isSaving || !draft.updateReviewRationale}
          />
        </div>

        <p className="settings-hint">Unchecked fields are left unchanged on each selected term.</p>
        {error ? <p className="settings-hint is-error">{getErrorMessage(error)}</p> : null}
      </div>

      <div className="modal__footer term-index-explorer__modal-footer">
        <button
          type="button"
          className="settings-button settings-button--ghost"
          onClick={onClose}
          disabled={isSaving}
        >
          Cancel
        </button>
        <button
          type="button"
          className="settings-button settings-button--primary"
          onClick={saveReview}
          disabled={selectedCount === 0 || isSaving}
        >
          {isSaving ? 'Saving...' : 'Save review'}
        </button>
      </div>
    </Modal>
  );
}

function ExtractionStatusTables({
  statusQuery,
  activeRefreshTaskId,
  repositoryOptions,
  selectedRepositoryIds,
  repositorySelectionActions,
  formatRepositorySelectionSummary,
  onChangeRepositorySelection,
  runResultLimit,
  runCountLabel,
  onChangeRunResultLimit,
}: {
  statusQuery: ReturnType<typeof useQuery<Awaited<ReturnType<typeof fetchTermIndexStatus>>, Error>>;
  activeRefreshTaskId: number | null;
  repositoryOptions: RepositorySelectionOption[];
  selectedRepositoryIds: number[];
  repositorySelectionActions: ReturnType<typeof getRepositoryTypeSelectionActions>;
  formatRepositorySelectionSummary: ReturnType<typeof getRepositorySelectionSummaryFormatter>;
  onChangeRepositorySelection: (repositoryIds: number[]) => void;
  runResultLimit: number;
  runCountLabel: string;
  onChangeRunResultLimit: (value: number) => void;
}) {
  const activeRefreshRunId = statusQuery.data?.recentRuns.find(
    (run) => run.status === 'RUNNING',
  )?.id;
  const cursorCount = statusQuery.data?.cursors.length ?? 0;

  return (
    <div className="term-index-explorer__status-grid">
      <details className="term-index-explorer__cursor-disclosure">
        <summary className="term-index-explorer__cursor-summary">
          <span className="term-index-explorer__cursor-summary-title">Repository cursors</span>
          <span className="term-index-explorer__cursor-summary-meta">
            <span>
              {statusQuery.isLoading
                ? 'Loading...'
                : cursorCount === 1
                  ? '1 repository'
                  : `${cursorCount.toLocaleString()} repositories`}
            </span>
            <span aria-hidden="true" className="term-index-explorer__cursor-summary-chevron">
              ▾
            </span>
          </span>
        </summary>
        {statusQuery.isLoading ? (
          <p className="settings-hint">Loading cursors...</p>
        ) : statusQuery.isError ? (
          <p className="settings-hint is-error">{getErrorMessage(statusQuery.error)}</p>
        ) : (statusQuery.data?.cursors ?? []).length === 0 ? (
          <>
            <div className="term-index-explorer__cursor-controls">
              <RepositoryMultiSelect
                label="Cursor repositories"
                options={repositoryOptions}
                selectedIds={selectedRepositoryIds}
                onChange={onChangeRepositorySelection}
                className="term-index-explorer__repository-select"
                buttonAriaLabel="Filter repository cursor rows. Use menu actions to switch between repositories and glossaries."
                customActions={repositorySelectionActions}
                summaryFormatter={formatRepositorySelectionSummary}
              />
            </div>
            <p className="settings-hint">No cursor rows yet.</p>
          </>
        ) : (
          <>
            <div className="term-index-explorer__cursor-controls">
              <RepositoryMultiSelect
                label="Cursor repositories"
                options={repositoryOptions}
                selectedIds={selectedRepositoryIds}
                onChange={onChangeRepositorySelection}
                className="term-index-explorer__repository-select"
                buttonAriaLabel="Filter repository cursor rows. Use menu actions to switch between repositories and glossaries."
                customActions={repositorySelectionActions}
                summaryFormatter={formatRepositorySelectionSummary}
              />
            </div>
            <div className="settings-table-wrapper">
              <table className="settings-table term-index-explorer__status-table">
                <thead>
                  <tr>
                    <th>Repository</th>
                    <th>Status</th>
                    <th>Last success</th>
                    <th>Lease</th>
                  </tr>
                </thead>
                <tbody>
                  {(statusQuery.data?.cursors ?? []).map((cursor) => (
                    <tr key={cursor.repositoryId}>
                      <td>{cursor.repositoryName}</td>
                      <td>{cursor.status}</td>
                      <td>{formatLocalDateTime(cursor.lastSuccessfulScanAt)}</td>
                      <td>
                        {cursor.leaseExpiresAt
                          ? `Run ${cursor.currentRefreshRunId ?? 'unknown'} until ${formatLocalDateTime(
                              cursor.leaseExpiresAt,
                            )}`
                          : 'None'}
                        {cursor.errorMessage ? (
                          <div className="settings-hint is-error">{cursor.errorMessage}</div>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </details>
      <div>
        <div className="term-index-explorer__subheading-row">
          <div className="term-index-explorer__subheading">Recent extraction runs</div>
          <NumericPresetDropdown
            value={runResultLimit}
            buttonLabel={runCountLabel}
            menuLabel="Recent run limit"
            presetOptions={RUN_RESULT_LIMIT_OPTIONS}
            onChange={onChangeRunResultLimit}
            ariaLabel="Recent extraction run result size limit"
            disabled={statusQuery.isLoading}
            className="term-index-explorer__run-result-size"
            buttonClassName="term-index-explorer__run-result-size-button"
            pillsClassName="settings-pills"
            optionClassName="settings-pill"
            optionActiveClassName="is-active"
            customButtonClassName="settings-pill"
            customActiveClassName="is-active"
            customInitialValue={RUN_RESULT_LIMIT_DEFAULT}
          />
        </div>
        {statusQuery.isLoading ? (
          <p className="settings-hint">Loading extraction runs...</p>
        ) : (statusQuery.data?.recentRuns ?? []).length === 0 ? (
          <p className="settings-hint">No extraction runs yet.</p>
        ) : (
          <div className="settings-table-wrapper">
            <table className="settings-table term-index-explorer__status-table">
              <thead>
                <tr>
                  <th>Run</th>
                  <th>Task</th>
                  <th>Status</th>
                  <th>Text units</th>
                  <th>Extracted terms</th>
                  <th>Started</th>
                  <th>Completed</th>
                </tr>
              </thead>
              <tbody>
                {(statusQuery.data?.recentRuns ?? []).map((run) => (
                  <tr key={run.id}>
                    <td>#{run.id}</td>
                    <td>
                      {run.pollableTaskId != null
                        ? `#${run.pollableTaskId}`
                        : activeRefreshTaskId != null && run.id === activeRefreshRunId
                          ? `#${activeRefreshTaskId}`
                          : '-'}
                    </td>
                    <td>{run.status}</td>
                    <td>{run.processedTextUnitCount}</td>
                    <td>{run.extractedTermCount}</td>
                    <td>{formatLocalDateTime(run.startedAt)}</td>
                    <td>
                      {run.completedAt ? formatLocalDateTime(run.completedAt) : 'In progress'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

function AutomationJobsTable({
  statusQuery,
  title,
  emptyLabel,
  jobNames,
}: {
  statusQuery: ReturnType<typeof useQuery<Awaited<ReturnType<typeof fetchTermIndexStatus>>, Error>>;
  title: string;
  emptyLabel: string;
  jobNames: string[];
}) {
  const recentJobs = (statusQuery.data?.recentJobs ?? []).filter((job) =>
    jobNames.includes(job.name),
  );

  return (
    <div>
      <div className="term-index-explorer__subheading">{title}</div>
      {statusQuery.isLoading ? (
        <p className="settings-hint">Loading jobs...</p>
      ) : recentJobs.length === 0 ? (
        <p className="settings-hint">{emptyLabel}</p>
      ) : (
        <div className="settings-table-wrapper">
          <table className="settings-table term-index-explorer__status-table">
            <thead>
              <tr>
                <th>Task</th>
                <th>Type</th>
                <th>Status</th>
                <th>Progress</th>
                <th>Started</th>
                <th>Finished</th>
              </tr>
            </thead>
            <tbody>
              {recentJobs.map((job) => (
                <tr key={job.id}>
                  <td>#{job.id}</td>
                  <td>{formatTermIndexJobName(job.name)}</td>
                  <td>
                    {job.status}
                    {job.errorMessage ? (
                      <div className="settings-hint is-error">{job.errorMessage}</div>
                    ) : null}
                  </td>
                  <td>
                    <TermIndexJobProgress progress={job.progress} status={job.status} />
                  </td>
                  <td>{formatLocalDateTime(job.createdDate)}</td>
                  <td>
                    {job.finishedDate ? formatLocalDateTime(job.finishedDate) : 'In progress'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function formatTermIndexJobName(name: string) {
  if (name === 'REVIEW_EXTRACTED_TERMS') {
    return 'Review extracted terms';
  }
  if (name === 'GENERATE_CANDIDATES') {
    return 'Generate candidates';
  }
  return name;
}

function TermIndexJobProgress({
  progress,
  status,
}: {
  progress?: ApiTermIndexTaskProgress | null;
  status: string;
}) {
  const percent = getTermIndexJobProgressPercent(progress, status);
  const label = formatTermIndexJobProgress(progress);
  const isIndeterminate = percent == null && status === 'RUNNING';

  return (
    <div className="term-index-explorer__job-progress">
      <div className="term-index-explorer__job-progress-header">
        <span>{label}</span>
        {percent != null ? <span>{Math.round(percent)}%</span> : null}
      </div>
      {percent != null || isIndeterminate ? (
        <progress max={100} value={percent ?? undefined} aria-label="Term index job progress" />
      ) : null}
    </div>
  );
}

function getTermIndexJobProgressPercent(
  progress?: ApiTermIndexTaskProgress | null,
  status?: string,
) {
  if (!progress) {
    return status === 'COMPLETED' ? 100 : null;
  }
  if ((status === 'COMPLETED' || progress.status === 'COMPLETED') && status !== 'FAILED') {
    return 100;
  }
  if (progress.type === 'TRIAGE_EXTRACTED_TERMS') {
    const total = progress.reviewableEntryCount ?? progress.entryCount ?? 0;
    if (total <= 0) {
      return progress.status === 'COMPLETED' ? 100 : null;
    }
    return Math.min(100, ((progress.reviewedEntryCount ?? 0) / total) * 100);
  }
  if (progress.type === 'GENERATE_TERM_INDEX_CANDIDATES') {
    const total = progress.entryCount ?? 0;
    if (total <= 0) {
      return progress.status === 'COMPLETED' ? 100 : null;
    }
    return Math.min(100, ((progress.processedEntryCount ?? 0) / total) * 100);
  }
  return progress.status === 'COMPLETED' ? 100 : null;
}

function formatTermIndexJobProgress(progress?: ApiTermIndexTaskProgress | null) {
  if (!progress) {
    return '-';
  }
  if (progress.type === 'TRIAGE_EXTRACTED_TERMS') {
    const reviewed = progress.reviewedEntryCount ?? 0;
    const total = progress.reviewableEntryCount ?? progress.entryCount ?? 0;
    return `${reviewed.toLocaleString()} / ${total.toLocaleString()} reviewed · ${(
      progress.acceptedCount ?? 0
    ).toLocaleString()} accepted · ${(progress.rejectedCount ?? 0).toLocaleString()} rejected`;
  }
  if (progress.type === 'GENERATE_TERM_INDEX_CANDIDATES') {
    const processed = progress.processedEntryCount ?? 0;
    const total = progress.entryCount ?? 0;
    const totalLabel =
      progress.status === 'COMPLETED' && total > processed
        ? processed.toLocaleString()
        : total.toLocaleString();
    return `${processed.toLocaleString()} / ${totalLabel} terms · ${(
      progress.createdCandidateCount ?? 0
    ).toLocaleString()} new · ${(progress.updatedCandidateCount ?? 0).toLocaleString()} refreshed · ${(
      progress.skippedExistingCandidateCount ?? 0
    ).toLocaleString()} skipped`;
  }
  return progress.status ?? '-';
}

function TermList({
  entries,
  selectedEntryId,
  selectedEntryIds,
  rowMetadata,
  showReviewConfidence,
  onSelectEntry,
  onUpdateEntryReview,
  isUpdatingReview,
  onToggleEntrySelection,
}: {
  entries: ApiTermIndexEntry[];
  selectedEntryId: number | null;
  selectedEntryIds: number[];
  rowMetadata: 'term' | 'candidate';
  showReviewConfidence?: boolean;
  onSelectEntry: (entryId: number) => void;
  onUpdateEntryReview: (entry: ApiTermIndexEntry, reviewStatus: ApiTermIndexReviewStatus) => void;
  isUpdatingReview: boolean;
  onToggleEntrySelection: (entryId: number, checked: boolean) => void;
}) {
  const termRowRefs = useRef(new Map<number, HTMLDivElement>());
  const selectedIndex = entries.findIndex((entry) => entry.id === selectedEntryId);
  const estimateSize = useCallback(() => 40, []);
  const getItemKey = useCallback((index: number) => entries[index]?.id ?? index, [entries]);
  const {
    scrollRef,
    items: virtualItems,
    totalSize,
    scrollToIndex,
    measureElement,
  } = useVirtualRows<HTMLDivElement>({
    count: entries.length,
    getItemKey,
    estimateSize,
    overscan: 8,
  });
  const { getRowRef } = useMeasuredRowRefs<number, HTMLDivElement>({ measureElement });

  const findNextEntryIndex = (startIndex: number, direction: 1 | -1) => {
    const nextIndex = startIndex + direction;
    return nextIndex >= 0 && nextIndex < entries.length ? nextIndex : -1;
  };

  const activateEntryAtIndex = (index: number) => {
    const nextEntry = entries[index];
    if (!nextEntry) {
      return;
    }
    scrollToIndex(index, { align: 'auto' });
    onSelectEntry(nextEntry.id);
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        termRowRefs.current.get(nextEntry.id)?.focus();
      });
    });
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (
      event.key !== 'ArrowDown' &&
      event.key !== 'ArrowUp' &&
      event.key !== 'Home' &&
      event.key !== 'End'
    ) {
      return;
    }
    if (isTextEntryTarget(event.target)) {
      return;
    }

    const focusedIndex = getTermIndexFromTarget(event.target);
    const currentIndex = focusedIndex >= 0 ? focusedIndex : selectedIndex;
    let nextIndex: number;

    if (event.key === 'Home') {
      nextIndex = 0;
    } else if (event.key === 'End') {
      nextIndex = entries.length - 1;
    } else {
      const direction = event.key === 'ArrowDown' ? 1 : -1;
      const startIndex = currentIndex >= 0 ? currentIndex : direction === 1 ? -1 : entries.length;
      nextIndex = findNextEntryIndex(startIndex, direction);
    }

    if (nextIndex < 0 || entries.length === 0) {
      return;
    }

    event.preventDefault();
    activateEntryAtIndex(nextIndex);
  };

  useEffect(() => {
    if (selectedIndex >= 0) {
      scrollToIndex(selectedIndex, { align: 'auto' });
    }
  }, [scrollToIndex, selectedIndex]);

  const hasConfidenceColumn = showReviewConfidence === true;
  const hasSourceColumn = rowMetadata === 'term';

  return (
    <div
      className={`term-index-explorer__term-list${
        hasConfidenceColumn ? ' term-index-explorer__term-list--with-confidence' : ''
      }${hasSourceColumn ? ' term-index-explorer__term-list--with-source' : ''}`}
      role="listbox"
      aria-label="Indexed terms"
      aria-multiselectable="true"
      onKeyDown={handleKeyDown}
    >
      <div className="term-index-explorer__term-list-header" aria-hidden="true">
        <span className="term-index-explorer__term-list-header-selection" />
        <span className="term-index-explorer__term-list-header-cell">Term</span>
        <span className="term-index-explorer__term-list-header-cell term-index-explorer__term-list-header-cell--numeric">
          Hits
        </span>
        <span className="term-index-explorer__term-list-header-cell term-index-explorer__term-list-header-cell--numeric">
          Repos
        </span>
        {hasConfidenceColumn ? (
          <span className="term-index-explorer__term-list-header-cell term-index-explorer__term-list-header-cell--numeric">
            Confidence
          </span>
        ) : null}
        {hasSourceColumn ? (
          <span className="term-index-explorer__term-list-header-cell">Source</span>
        ) : null}
        <span className="term-index-explorer__term-list-header-cell term-index-explorer__term-list-header-cell--status">
          Status
        </span>
      </div>
      <VirtualList
        scrollRef={scrollRef}
        items={virtualItems}
        totalSize={totalSize}
        renderRow={(virtualRow) => {
          const entry = entries[virtualRow.index];
          if (!entry) {
            return null;
          }
          return {
            key: entry.id,
            className: 'term-index-explorer__term-virtual-row',
            props: {
              ref: getRowRef(entry.id),
            },
            content: (
              <TermRow
                entry={entry}
                index={virtualRow.index}
                rowMetadata={rowMetadata}
                showReviewConfidence={showReviewConfidence === true}
                selected={entry.id === selectedEntryId}
                selectedForBatch={selectedEntryIds.includes(entry.id)}
                focusable={
                  selectedIndex < 0 ? virtualRow.index === 0 : entry.id === selectedEntryId
                }
                rowRef={(node) => {
                  if (node) {
                    termRowRefs.current.set(entry.id, node);
                  } else {
                    termRowRefs.current.delete(entry.id);
                  }
                }}
                onSelect={() => onSelectEntry(entry.id)}
                onUpdateReview={(reviewStatus) => onUpdateEntryReview(entry, reviewStatus)}
                isUpdatingReview={isUpdatingReview}
                onToggleSelection={(checked) => onToggleEntrySelection(entry.id, checked)}
              />
            ),
          };
        }}
      />
    </div>
  );
}

function TermRow({
  entry,
  index,
  rowMetadata,
  showReviewConfidence,
  selected,
  selectedForBatch,
  focusable,
  rowRef,
  onSelect,
  onUpdateReview,
  isUpdatingReview,
  onToggleSelection,
}: {
  entry: ApiTermIndexEntry;
  index: number;
  rowMetadata: 'term' | 'candidate';
  showReviewConfidence: boolean;
  selected: boolean;
  selectedForBatch: boolean;
  focusable: boolean;
  rowRef: (node: HTMLDivElement | null) => void;
  onSelect: () => void;
  onUpdateReview: (reviewStatus: ApiTermIndexReviewStatus) => void;
  isUpdatingReview: boolean;
  onToggleSelection: (checked: boolean) => void;
}) {
  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (isInteractiveTarget(event.target)) {
      return;
    }
    if (event.altKey || event.ctrlKey || event.metaKey) {
      return;
    }
    if (event.key === 'Enter') {
      event.preventDefault();
      onSelect();
      return;
    }
    if (event.key === ' ') {
      event.preventDefault();
      onToggleSelection(!selectedForBatch);
    }
  };

  return (
    <div
      ref={rowRef}
      role="option"
      aria-selected={selected}
      data-term-index={index}
      tabIndex={focusable ? 0 : -1}
      className={`term-index-explorer__term-row${selected ? ' is-selected' : ''}`}
      onClick={onSelect}
      onFocus={onSelect}
      onKeyDown={handleKeyDown}
    >
      <input
        type="checkbox"
        className="term-index-explorer__term-checkbox"
        checked={selectedForBatch}
        onClick={(event) => event.stopPropagation()}
        onChange={(event) => onToggleSelection(event.target.checked)}
        aria-label={`Select ${entry.displayTerm}`}
      />
      <span className="term-index-explorer__term-name-cell">
        <span className="term-index-explorer__term-name">{entry.displayTerm}</span>
        {rowMetadata === 'term' ? (
          <span className="term-index-explorer__term-inline-meta">
            <span>#{entry.id}</span>
          </span>
        ) : null}
        {rowMetadata === 'candidate' && entry.termIndexCandidateId != null ? (
          <span className="term-index-explorer__term-inline-meta">
            <span>#{entry.termIndexCandidateId}</span>
          </span>
        ) : null}
      </span>
      <span
        className="term-index-explorer__term-hit-count"
        aria-label={`${entry.occurrenceCount.toLocaleString()} hits`}
      >
        <span>{entry.occurrenceCount.toLocaleString()}</span>
        <span className="term-index-explorer__term-cell-label">hits</span>
      </span>
      <span
        className="term-index-explorer__term-repository-count"
        aria-label={`${entry.repositoryCount.toLocaleString()} repositories`}
      >
        <span>{entry.repositoryCount.toLocaleString()}</span>
        <span className="term-index-explorer__term-cell-label">repos</span>
      </span>
      {showReviewConfidence ? (
        <span
          className="term-index-explorer__term-confidence"
          aria-label={
            entry.reviewConfidence == null
              ? 'No confidence'
              : `${entry.reviewConfidence}% confidence`
          }
        >
          <span>{entry.reviewConfidence == null ? 'No' : `${entry.reviewConfidence}%`}</span>
          <span className="term-index-explorer__term-cell-label">conf</span>
        </span>
      ) : null}
      {rowMetadata === 'term' ? (
        <span className="term-index-explorer__term-review-source">
          <span
            className={`term-index-explorer__review-source-pill ${getReviewSourceClassName(
              entry.reviewAuthority,
            )}`}
          >
            {formatReviewSourceShort(entry.reviewAuthority)}
          </span>
        </span>
      ) : null}
      <span className="term-index-explorer__term-meta">
        <PillDropdown
          value={entry.reviewStatus}
          options={REVIEW_STATUS_OPTIONS}
          onChange={onUpdateReview}
          disabled={isUpdatingReview}
          ariaLabel={`Review status for ${entry.displayTerm}`}
          className="term-index-explorer__term-review-dropdown"
        />
      </span>
    </div>
  );
}

function OccurrencePanel({
  entryId,
  occurrences,
  isLoading,
  error,
}: {
  entryId: number;
  occurrences: ApiTermIndexOccurrence[];
  isLoading: boolean;
  error: unknown;
}) {
  const occurrenceRefs = useRef(new Map<number, HTMLElement>());
  const [focusedOccurrenceId, setFocusedOccurrenceId] = useState<number | null>(null);
  const focusedOccurrenceIndex = occurrences.findIndex(
    (occurrence) => occurrence.id === focusedOccurrenceId,
  );
  const focusableOccurrenceId =
    focusedOccurrenceIndex >= 0 ? focusedOccurrenceId : (occurrences[0]?.id ?? null);

  useEffect(() => {
    setFocusedOccurrenceId(null);
  }, [entryId]);

  const focusOccurrenceAtIndex = (index: number) => {
    const nextOccurrence = occurrences[index];
    if (!nextOccurrence) {
      return;
    }
    setFocusedOccurrenceId(nextOccurrence.id);
    requestAnimationFrame(() => {
      const node = occurrenceRefs.current.get(nextOccurrence.id);
      node?.scrollIntoView({ block: 'nearest' });
      node?.focus();
    });
  };

  const handleExampleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    let nextIndex: number | null = null;
    const currentIndex = focusedOccurrenceIndex >= 0 ? focusedOccurrenceIndex : 0;

    if (event.key === 'ArrowDown') {
      nextIndex = Math.min(currentIndex + 1, occurrences.length - 1);
    } else if (event.key === 'ArrowUp') {
      nextIndex = Math.max(currentIndex - 1, 0);
    } else if (event.key === 'Home') {
      nextIndex = 0;
    } else if (event.key === 'End') {
      nextIndex = occurrences.length - 1;
    }

    if (nextIndex == null || occurrences.length === 0) {
      return;
    }

    event.preventDefault();
    focusOccurrenceAtIndex(nextIndex);
  };

  return (
    <div className="term-index-explorer__occurrence-panel">
      {isLoading ? (
        <div className="term-index-explorer__loading-spacer" aria-hidden="true" />
      ) : error ? (
        <p className="settings-hint is-error">{getErrorMessage(error)}</p>
      ) : occurrences.length === 0 ? (
        <p className="settings-hint">No examples match the current filters.</p>
      ) : (
        <div
          className="term-index-explorer__example-list"
          role="list"
          aria-label="Term examples"
          onKeyDown={handleExampleKeyDown}
        >
          {occurrences.map((occurrence) => (
            <OccurrenceExample
              key={occurrence.id}
              occurrence={occurrence}
              focusable={occurrence.id === focusableOccurrenceId}
              articleRef={(node) => {
                if (node) {
                  occurrenceRefs.current.set(occurrence.id, node);
                } else {
                  occurrenceRefs.current.delete(occurrence.id);
                }
              }}
              onFocus={() => setFocusedOccurrenceId(occurrence.id)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function OccurrenceExample({
  occurrence,
  focusable,
  articleRef,
  onFocus,
}: {
  occurrence: ApiTermIndexOccurrence;
  focusable: boolean;
  articleRef: (node: HTMLElement | null) => void;
  onFocus: () => void;
}) {
  return (
    <article
      ref={articleRef}
      className="term-index-explorer__example"
      role="listitem"
      tabIndex={focusable ? 0 : -1}
      onFocus={onFocus}
    >
      <div className="term-index-explorer__example-meta">
        <span>{occurrence.repositoryName}</span>
        {occurrence.assetPath ? <span>{occurrence.assetPath}</span> : null}
        <Link
          to={`/text-units/${occurrence.tmTextUnitId}`}
          aria-label={`Text unit ${occurrence.tmTextUnitId}`}
        >
          #{occurrence.tmTextUnitId}
        </Link>
      </div>
      <HighlightedSource occurrence={occurrence} />
      <div className="term-index-explorer__example-footer">
        <span>{formatMethod(occurrence.extractionMethod)}</span>
        <span>{occurrence.extractorId}</span>
        {occurrence.confidence == null ? null : <span>{occurrence.confidence}%</span>}
      </div>
    </article>
  );
}

function HighlightedSource({ occurrence }: { occurrence: ApiTermIndexOccurrence }) {
  const source = occurrence.sourceText ?? '';
  if (!source) {
    return <div className="term-index-explorer__source">No source text.</div>;
  }

  const startIndex = clamp(occurrence.startIndex, 0, source.length);
  const endIndex = clamp(occurrence.endIndex, startIndex, source.length);

  return (
    <div className="term-index-explorer__source">
      {source.slice(0, startIndex)}
      <mark>{source.slice(startIndex, endIndex) || occurrence.matchedText}</mark>
      {source.slice(endIndex)}
    </div>
  );
}

function formatCappedListLabel(label: string, visibleCount: number, limit: number) {
  if (visibleCount === 0) {
    return `No ${label}`;
  }
  const countLabel = visibleCount.toLocaleString();
  if (visibleCount >= limit) {
    return `Showing first ${countLabel} ${label} (more available)`;
  }
  return `Showing ${countLabel} ${label}`;
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}

function toTeamUserSelectOption(user: ApiTeamUserSummary) {
  return {
    value: user.id,
    label: getUserLabel(user) || `User #${user.id}`,
  };
}

function formatTermIndexQueryFilterSummary(
  minOccurrences: number,
  extractionMethod: string | null,
  lastOccurrenceAfter: string | null,
  lastOccurrenceBefore: string | null,
  reviewChangedAfter: string | null,
  reviewChangedBefore: string | null,
) {
  const parts = [minOccurrences === 1 ? 'Any hits' : `${minOccurrences.toLocaleString()}+ hits`];
  if (extractionMethod) {
    parts.push(formatMethod(extractionMethod));
  }
  if (lastOccurrenceAfter || lastOccurrenceBefore) {
    parts.push('Last extracted');
  }
  if (reviewChangedAfter || reviewChangedBefore) {
    parts.push('Review updated');
  }
  return parts.join(' · ');
}

function formatTermIndexReviewFilterSummary(
  status: ApiTermIndexReviewStatusFilter,
  authority: ApiTermIndexReviewAuthorityFilter,
  prefix = 'Review',
  showAuthority = true,
) {
  const statusLabel =
    REVIEW_STATUS_FILTER_OPTIONS.find((option) => option.value === status)?.label ?? status;
  if (!showAuthority || authority === 'ALL') {
    return `${prefix}: ${statusLabel}`;
  }
  return `${prefix}: ${statusLabel} · ${formatReviewAuthorityFilter(authority)}`;
}

function formatReviewAuthorityFilter(authority: ApiTermIndexReviewAuthorityFilter) {
  return (
    REVIEW_AUTHORITY_FILTER_OPTIONS.find((option) => option.value === authority)?.label ??
    reviewAuthorityLabel(authority)
  );
}

function formatDateRangeFilter(after: string | null, before: string | null) {
  if (after && before) {
    return `${formatLocalDateTime(after)} to ${formatLocalDateTime(before)}`;
  }
  if (after) {
    return `After ${formatLocalDateTime(after)}`;
  }
  if (before) {
    return `Before ${formatLocalDateTime(before)}`;
  }
  return 'Any date';
}

function formatMethod(method: string | null | undefined) {
  if (!method) {
    return 'All methods';
  }
  return method
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

function formatReviewStatus(status: string | null | undefined) {
  if (!status) {
    return 'To review';
  }
  return (
    REVIEW_STATUS_OPTIONS.find((option) => option.value === status)?.label ?? formatMethod(status)
  );
}

function formatReviewChange(
  status: string | null | undefined,
  authority: string | null | undefined,
  changedAt: string | null | undefined,
  changedByCommonName: string | null | undefined,
  changedByUsername: string | null | undefined,
) {
  if (authority === 'NONE') {
    return 'Not reviewed';
  }
  const statusLabel = formatReviewStatus(status);
  const actor =
    authority === 'AI'
      ? 'AI'
      : changedByCommonName?.trim() || changedByUsername?.trim() || reviewAuthorityLabel(authority);
  const changedAtLabel = changedAt ? ` on ${formatLocalDateTime(changedAt)}` : '';

  return `${statusLabel} by ${actor}${changedAtLabel}`;
}

function reviewAuthorityLabel(authority: string | null | undefined) {
  switch (authority) {
    case 'HUMAN':
      return 'unknown user';
    case 'AI':
      return 'AI';
    case 'NONE':
      return 'not reviewed';
    case null:
    case undefined:
      return 'unknown source';
    default:
      return formatMethod(authority);
  }
}

function formatReviewSourceShort(authority: string | null | undefined) {
  switch (authority) {
    case 'NONE':
      return 'Unreviewed';
    case 'AI':
      return 'AI';
    case 'HUMAN':
      return 'Human';
    default:
      return reviewAuthorityLabel(authority);
  }
}

function getReviewSourceClassName(authority: string | null | undefined) {
  switch (authority) {
    case 'NONE':
      return 'term-index-explorer__review-source-pill--none';
    case 'AI':
      return 'term-index-explorer__review-source-pill--ai';
    case 'HUMAN':
      return 'term-index-explorer__review-source-pill--human';
    default:
      return 'term-index-explorer__review-source-pill--unknown';
  }
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'Something went wrong.';
}
