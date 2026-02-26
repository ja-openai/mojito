import '../../components/chip-dropdown.css';
import '../../components/filters/filter-chip.css';
import './review-project-page.css';

import { useQuery } from '@tanstack/react-query';
import type { VirtualItem } from '@tanstack/react-virtual';
import type React from 'react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';

import {
  type AiReviewMessage,
  type AiReviewSuggestion,
  formatAiReviewError,
  requestAiReview,
} from '../../api/ai-review';
import type {
  ApiReviewProjectDetail,
  ApiReviewProjectTextUnit,
  ApiReviewProjectType,
} from '../../api/review-projects';
import { REVIEW_PROJECT_TYPE_LABELS, REVIEW_PROJECT_TYPES } from '../../api/review-projects';
import {
  type ApiTeam,
  fetchTeamLocalePools,
  fetchTeamPmPool,
  fetchTeams,
  fetchTeamTranslators,
} from '../../api/teams';
import {
  type ApiTextUnitHistoryItem,
  fetchTextUnitHistory,
} from '../../api/text-units';
import { type ApiUser, fetchAllUsersAdmin } from '../../api/users';
import { AiChatReview, type AiChatReviewMessage } from '../../components/AiChatReview';
import { AutoTextarea } from '../../components/AutoTextarea';
import { ConfirmModal } from '../../components/ConfirmModal';
import {
  type FilterOption,
  MultiSectionFilterChip,
} from '../../components/filters/MultiSectionFilterChip';
import { IcuPreviewSection } from '../../components/IcuPreviewSection';
import { LocalePill } from '../../components/LocalePill';
import { Modal } from '../../components/Modal';
import { Pill } from '../../components/Pill';
import { PillDropdown } from '../../components/PillDropdown';
import { useUser } from '../../components/RequireUser';
import {
  RequestAttachmentsDropzone,
  type RequestAttachmentUploadQueueItem,
} from '../../components/review-request/RequestAttachmentsDropzone';
import { RequestDescriptionEditor } from '../../components/review-request/RequestDescriptionEditor';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import {
  TextUnitHistoryTimeline,
  type TextUnitHistoryTimelineEntry,
} from '../../components/TextUnitHistoryTimeline';
import { getRowHeightPx } from '../../components/virtual/getRowHeightPx';
import { useVirtualRows } from '../../components/virtual/useVirtualRows';
import { VirtualList } from '../../components/virtual/VirtualList';
import { toHtmlLangTag } from '../../utils/localeTag';
import {
  buildRequestAttachmentUploadQueueEntries,
  isImageAttachmentKey,
  isPdfAttachmentKey,
  isSupportedRequestAttachmentFile,
  isVideoAttachmentKey,
  resolveAttachmentUrl,
  revokeRequestAttachmentUploadQueuePreviews,
  toDescriptionAttachmentMarkdown,
  uploadRequestAttachmentFile,
} from '../../utils/request-attachments';
import { REVIEW_PROJECTS_SESSION_QUERY_KEY } from '../review-projects/review-projects-session-state';
import type { ReviewProjectMutationControls } from './review-project-mutations';

const Chevron = ({ direction }: { direction: 'left' | 'right' | 'up' | 'down' }) => (
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

type StatusChoice = 'ACCEPTED' | 'NEEDS_REVIEW' | 'NEEDS_TRANSLATION' | 'REJECTED';

const STATUS_CHOICES: Array<{ value: StatusChoice; label: string }> = [
  { value: 'ACCEPTED', label: 'Accepted' },
  { value: 'NEEDS_REVIEW', label: 'To review' },
  { value: 'NEEDS_TRANSLATION', label: 'To translate' },
  { value: 'REJECTED', label: 'Rejected' },
];

type TextUnitVariant = ApiReviewProjectTextUnit['baselineTmTextUnitVariant'];
type DecisionStateChoice = 'PENDING' | 'DECIDED';
type DecisionStateFilter = DecisionStateChoice | 'all';
type StatusFilter = 'all' | 'APPROVED' | 'REVIEW_NEEDED' | 'TRANSLATION_NEEDED' | 'REJECTED';
type EditedFilter = 'all' | 'edited' | 'notEdited';
type EditKind = 'translation' | 'status' | 'comment';

const SAVING_INDICATOR_MIN_MS = 600;
const DEFAULT_AI_REVIEW_PROMPT = 'Review the translation and suggest improvements.';

function mapChoiceToApi(choice: StatusChoice): {
  status: string;
  includedInLocalizedFile: boolean;
} {
  switch (choice) {
    case 'ACCEPTED':
      return { status: 'APPROVED', includedInLocalizedFile: true };
    case 'NEEDS_REVIEW':
      return { status: 'REVIEW_NEEDED', includedInLocalizedFile: true };
    case 'REJECTED':
      return { status: 'TRANSLATION_NEEDED', includedInLocalizedFile: false };
    case 'NEEDS_TRANSLATION':
    default:
      return { status: 'TRANSLATION_NEEDED', includedInLocalizedFile: true };
  }
}

function mapVariantToChoice(status?: string | null, includedInLocalizedFile?: boolean | null) {
  if (includedInLocalizedFile === false) {
    return 'REJECTED' as const;
  }

  switch (status) {
    case 'APPROVED':
      return 'ACCEPTED' as const;
    case 'REVIEW_NEEDED':
      return 'NEEDS_REVIEW' as const;
    case 'TRANSLATION_NEEDED':
    default:
      return 'NEEDS_TRANSLATION' as const;
  }
}

function getEffectiveVariant(textUnit: ApiReviewProjectTextUnit): TextUnitVariant {
  const current = textUnit.currentTmTextUnitVariant;
  return current?.id != null ? current : textUnit.baselineTmTextUnitVariant;
}

function getDecisionState(textUnit: ApiReviewProjectTextUnit): DecisionStateChoice {
  const decision = textUnit.reviewProjectTextUnitDecision;
  if (decision?.decisionState === 'DECIDED' || decision?.decisionState === 'PENDING') {
    return decision.decisionState;
  }
  return decision?.decisionTmTextUnitVariant?.id != null ? 'DECIDED' : 'PENDING';
}

function getStatusKey(variant: TextUnitVariant | null | undefined): string | null {
  if (!variant) {
    return null;
  }
  if (variant.includedInLocalizedFile === false) {
    return 'REJECTED';
  }
  return variant.status ?? null;
}

function statusKeyToLabel(statusKey: string): string {
  switch (statusKey) {
    case 'APPROVED':
      return 'Accepted';
    case 'REVIEW_NEEDED':
      return 'Needs review';
    case 'TRANSLATION_NEEDED':
      return 'Needs translation';
    case 'REJECTED':
      return 'Rejected';
    default:
      return statusKey;
  }
}

function statusKeyToChipClass(statusKey: string | null): string {
  switch (statusKey) {
    case 'APPROVED':
      return 'accepted';
    case 'REVIEW_NEEDED':
      return 'needs-review';
    case 'TRANSLATION_NEEDED':
      return 'needs-translation';
    case 'REJECTED':
      return 'rejected';
    default:
      return 'unknown';
  }
}

const STATUS_FILTER_OPTIONS: Array<FilterOption<StatusFilter>> = [
  { value: 'all', label: 'All statuses' },
  { value: 'APPROVED', label: statusKeyToLabel('APPROVED') },
  { value: 'REVIEW_NEEDED', label: statusKeyToLabel('REVIEW_NEEDED') },
  { value: 'TRANSLATION_NEEDED', label: statusKeyToLabel('TRANSLATION_NEEDED') },
  { value: 'REJECTED', label: statusKeyToLabel('REJECTED') },
];

const DECISION_STATE_OPTIONS: Array<FilterOption<DecisionStateFilter>> = [
  { value: 'all', label: 'All states' },
  { value: 'PENDING', label: 'Pending' },
  { value: 'DECIDED', label: 'Decided' },
];

const EDITED_FILTER_OPTIONS: Array<FilterOption<EditedFilter>> = [
  { value: 'all', label: 'All rows' },
  { value: 'edited', label: 'Edited' },
  { value: 'notEdited', label: 'Non-edited' },
];

function normalizeOptional(value: string): string | null {
  return value === '' ? null : value;
}

function parseDecisionStateFilter(value: string | null): DecisionStateFilter {
  if (value === 'PENDING' || value === 'DECIDED') {
    return value;
  }
  return 'all';
}

function normalizeNullableString(value?: string | null): string {
  return value ?? '';
}

function getEditKinds(textUnit: ApiReviewProjectTextUnit): EditKind[] {
  const current =
    textUnit.currentTmTextUnitVariant?.id != null ? textUnit.currentTmTextUnitVariant : null;
  if (!current) {
    return [];
  }

  const baseline = textUnit.baselineTmTextUnitVariant;
  const kinds: EditKind[] = [];

  if (normalizeNullableString(current.content) !== normalizeNullableString(baseline?.content)) {
    kinds.push('translation');
  }

  const baselineStatus = getStatusKey(baseline);
  const currentStatus = getStatusKey(current);
  if (baselineStatus !== currentStatus) {
    kinds.push('status');
  }

  if (normalizeNullableString(current.comment) !== normalizeNullableString(baseline?.comment)) {
    kinds.push('comment');
  }

  if (kinds.length === 0 && current.id !== baseline?.id) {
    kinds.push('translation');
  }

  return kinds;
}

type DecisionSnapshot = {
  expectedCurrentVariantId: number | null;
  target: string;
  comment: string | null;
  decisionNotes: string | null;
  statusChoice: StatusChoice;
  decisionState: DecisionStateChoice;
};

type TranslationWarning = {
  code: string;
  message: string;
};

type PreviewSegment = {
  text: string;
  issue: boolean;
};

function getLeadingWhitespace(value: string): string {
  const match = value.match(/^\s+/);
  return match?.[0] ?? '';
}

function getTrailingWhitespace(value: string): string {
  const match = value.match(/\s+$/);
  return match?.[0] ?? '';
}

function isInvisibleDirectionalOrZeroWidthCode(code: number): boolean {
  return (
    code === 0x200b ||
    code === 0x200c ||
    code === 0x200d ||
    code === 0x200e ||
    code === 0x200f ||
    code === 0xfeff ||
    (code >= 0x2066 && code <= 0x2069)
  );
}

function isControlCode(code: number): boolean {
  return (
    (code >= 0x0 && code <= 0x8) ||
    (code >= 0xb && code <= 0xc) ||
    (code >= 0xe && code <= 0x1f) ||
    code === 0x7f
  );
}

function hasInvisibleDirectionalOrZeroWidthChars(value: string): boolean {
  for (const char of value) {
    const code = char.codePointAt(0);
    if (code == null) {
      continue;
    }
    if (isInvisibleDirectionalOrZeroWidthCode(code)) {
      return true;
    }
  }
  return false;
}

function hasControlChars(value: string): boolean {
  for (const char of value) {
    const code = char.codePointAt(0);
    if (code == null) {
      continue;
    }
    if (isControlCode(code)) {
      return true;
    }
  }
  return false;
}

function buildTranslationIssuePreview(source: string, target: string): PreviewSegment[] {
  const chars = Array.from(target);
  if (chars.length === 0) {
    return [];
  }

  const sourceLeadingWhitespace = getLeadingWhitespace(source);
  const sourceTrailingWhitespace = getTrailingWhitespace(source);
  const targetLeadingWhitespace = getLeadingWhitespace(target);
  const targetTrailingWhitespace = getTrailingWhitespace(target);
  const hasLeadingWhitespaceMismatch = sourceLeadingWhitespace !== targetLeadingWhitespace;
  const hasTrailingWhitespaceMismatch = sourceTrailingWhitespace !== targetTrailingWhitespace;

  let leadingEnd = 0;
  while (leadingEnd < chars.length && /\s/.test(chars[leadingEnd] ?? '')) {
    leadingEnd += 1;
  }

  let trailingStart = chars.length;
  while (trailingStart > 0 && /\s/.test(chars[trailingStart - 1] ?? '')) {
    trailingStart -= 1;
  }

  const pieces: Array<{ text: string; issue: boolean }> = chars.map((char, index) => {
    const code = char.codePointAt(0);
    const isLeadingOrTrailingWhitespace =
      /\s/.test(char) &&
      ((hasLeadingWhitespaceMismatch && index < leadingEnd) ||
        (hasTrailingWhitespaceMismatch && index >= trailingStart));
    const isRepeatedSpace = char === ' ' && index > 0 && chars[index - 1] === ' ';
    const isTab = char === '\t';
    const isNbsp = char === '\u00A0';
    const isInvisible = code != null && isInvisibleDirectionalOrZeroWidthCode(code);
    const isControl = code != null && isControlCode(code);

    const issue =
      isLeadingOrTrailingWhitespace ||
      isRepeatedSpace ||
      isTab ||
      isNbsp ||
      isInvisible ||
      isControl;

    if (!issue) {
      return { text: char, issue: false };
    }

    if (char === ' ') {
      return { text: '·', issue: true };
    }
    if (isTab) {
      return { text: '→', issue: true };
    }
    if (char === '\n') {
      return { text: '↵\n', issue: true };
    }
    if (char === '\r') {
      return { text: '␍', issue: true };
    }
    if (isNbsp) {
      return { text: '⍽', issue: true };
    }
    if (isInvisible) {
      return { text: '¤', issue: true };
    }
    if (isControl) {
      return { text: '�', issue: true };
    }

    return { text: char, issue: true };
  });

  const merged: PreviewSegment[] = [];
  for (const piece of pieces) {
    const previous = merged[merged.length - 1];
    if (previous && previous.issue === piece.issue) {
      previous.text += piece.text;
    } else {
      merged.push({ text: piece.text, issue: piece.issue });
    }
  }
  return merged;
}

function buildTranslationWarnings(source: string, target: string): TranslationWarning[] {
  const warnings: TranslationWarning[] = [];

  if (!target) {
    return warnings;
  }

  const sourceLeadingWhitespace = getLeadingWhitespace(source);
  const sourceTrailingWhitespace = getTrailingWhitespace(source);
  const targetLeadingWhitespace = getLeadingWhitespace(target);
  const targetTrailingWhitespace = getTrailingWhitespace(target);

  if (sourceLeadingWhitespace !== targetLeadingWhitespace) {
    warnings.push({
      code: 'leading-space',
      message:
        sourceLeadingWhitespace.length === 0
          ? 'Unexpected leading whitespace at start.'
          : 'Leading whitespace does not match source.',
    });
  }
  if (sourceTrailingWhitespace !== targetTrailingWhitespace) {
    warnings.push({
      code: 'trailing-space',
      message:
        sourceTrailingWhitespace.length === 0
          ? 'Unexpected trailing whitespace at end.'
          : 'Trailing whitespace does not match source.',
    });
  }
  if (/ {2,}/.test(target)) {
    warnings.push({ code: 'double-space', message: 'Contains repeated spaces.' });
  }
  if (/\t/.test(target)) {
    warnings.push({ code: 'tab', message: 'Contains tab characters.' });
  }
  if (target.includes('\u00A0')) {
    warnings.push({ code: 'nbsp', message: 'Contains non-breaking spaces.' });
  }
  if (hasInvisibleDirectionalOrZeroWidthChars(target)) {
    warnings.push({
      code: 'invisible',
      message: 'Contains invisible directional/zero-width characters.',
    });
  }
  if (hasControlChars(target)) {
    warnings.push({ code: 'control', message: 'Contains control characters.' });
  }

  return warnings;
}

function buildAiWarningContextMessage(warnings: TranslationWarning[]): AiReviewMessage | null {
  if (warnings.length === 0) {
    return null;
  }

  const warningLines = warnings
    .map((warning) => `- ${warning.code}: ${warning.message}`)
    .join('\n');
  return {
    role: 'user',
    content: [
      'Context only: deterministic translation quality warnings for current target text.',
      'Use these warnings when scoring and proposing edits.',
      warningLines,
    ].join('\n'),
  };
}

function buildSnapshot(textUnit: ApiReviewProjectTextUnit): DecisionSnapshot {
  const current =
    textUnit.currentTmTextUnitVariant?.id != null ? textUnit.currentTmTextUnitVariant : null;
  const baseVariant = current ?? textUnit.baselineTmTextUnitVariant;
  const statusChoice = mapVariantToChoice(
    baseVariant?.status ?? null,
    baseVariant?.includedInLocalizedFile ?? null,
  );

  return {
    expectedCurrentVariantId: current?.id ?? null,
    target: baseVariant?.content ?? '',
    comment: baseVariant?.comment ?? null,
    decisionNotes: textUnit.reviewProjectTextUnitDecision?.notes ?? null,
    statusChoice,
    decisionState: getDecisionState(textUnit),
  };
}

function buildSnapshotKey(textUnit: ApiReviewProjectTextUnit, snapshot: DecisionSnapshot): string {
  const baselineId = textUnit.baselineTmTextUnitVariant?.id ?? 'null';
  const currentId = snapshot.expectedCurrentVariantId ?? 'null';
  const decisionVariantId =
    textUnit.reviewProjectTextUnitDecision?.decisionTmTextUnitVariant?.id ?? 'null';
  const decisionNotes = textUnit.reviewProjectTextUnitDecision?.notes ?? '';
  const decisionState = snapshot.decisionState;
  return `${textUnit.id}:${baselineId}:${currentId}:${decisionVariantId}:${decisionNotes}:${decisionState}`;
}

type Props = {
  projectId: number;
  project: ApiReviewProjectDetail | null;
  mutations: ReviewProjectMutationControls;
  selectedTextUnitQueryId: number | null;
  onSelectedTextUnitIdChange: (id: number | null, options?: { replace?: boolean }) => void;
  openRequestDetailsQuery: boolean;
  requestDetailsSource: 'list' | null;
  onRequestDetailsQueryHandled: () => void;
  onRequestDetailsFlowFinished: () => void;
};

export function ReviewProjectPageView({
  projectId,
  project,
  mutations,
  selectedTextUnitQueryId,
  onSelectedTextUnitIdChange,
  openRequestDetailsQuery,
  requestDetailsSource,
  onRequestDetailsQueryHandled,
  onRequestDetailsFlowFinished,
}: Props) {
  const user = useUser();
  const canEditRequest = user.role === 'ROLE_ADMIN';
  const [searchParams, setSearchParams] = useSearchParams();
  const reviewProjectsSessionKey = searchParams.get(REVIEW_PROJECTS_SESSION_QUERY_KEY);
  const locale = project?.locale ?? null;
  const localeTag = locale?.bcp47Tag ?? '';
  const textUnits = useMemo<ApiReviewProjectTextUnit[]>(
    () => project?.reviewProjectTextUnits ?? [],
    [project?.reviewProjectTextUnits],
  );

  const layoutRef = useRef<HTMLDivElement>(null);
  const detailPaneRef = useRef<HTMLDivElement>(null);
  const [listWidthPct, setListWidthPct] = useState(20);
  const [lastListWidthPct, setLastListWidthPct] = useState(20);
  const [isListCollapsed, setIsListCollapsed] = useState(false);
  const [isResizing, setIsResizing] = useState(false);

  useEffect(() => {
    if (layoutRef.current) {
      layoutRef.current.style.setProperty('--review-list-width', `${listWidthPct}%`);
    }
  }, [listWidthPct]);

  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [stateFilter, setStateFilter] = useState<DecisionStateFilter>(() =>
    parseDecisionStateFilter(searchParams.get('state')),
  );
  const [editedFilter, setEditedFilter] = useState<EditedFilter>('all');
  const [selectedTextUnitId, setSelectedTextUnitId] = useState<number | null>(null);
  const [detailIsDirty, setDetailIsDirty] = useState(false);
  const [focusTranslationKey, setFocusTranslationKey] = useState(0);
  const [isShortcutsOpen, setIsShortcutsOpen] = useState(false);
  const [pendingSelection, setPendingSelection] = useState<{
    id: number;
    index?: number;
  } | null>(null);
  const [pendingAdvance, setPendingAdvance] = useState<{
    fromId: number;
    focusTranslation: boolean;
  } | null>(null);
  const previousSelectedRef = useRef<number | null>(null);
  const pendingQueryAutoScrollIdRef = useRef<number | null>(null);
  const lastAppliedQueryIdRef = useRef<number | null>(null);
  const [isScreenshotModalOpen, setIsScreenshotModalOpen] = useState(false);
  const [selectedScreenshotIdx, setSelectedScreenshotIdx] = useState<number>(0);
  const { onDismissValidationSave, showValidationDialog } = mutations;

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    return textUnits.filter((tu) => {
      if (!tu) return false;
      const statusKey = getStatusKey(getEffectiveVariant(tu));
      if (statusFilter !== 'all' && statusKey !== statusFilter) {
        return false;
      }
      if (stateFilter !== 'all' && getDecisionState(tu) !== stateFilter) {
        return false;
      }
      const isEdited = getEditKinds(tu).length > 0;
      if (editedFilter === 'edited' && !isEdited) {
        return false;
      }
      if (editedFilter === 'notEdited' && isEdited) {
        return false;
      }
      if (!term) return true;
      const haystacks = [
        tu.tmTextUnit?.name,
        tu.tmTextUnit?.content,
        tu.baselineTmTextUnitVariant?.content,
        tu.currentTmTextUnitVariant?.content,
      ]
        .filter(Boolean)
        .map((s) => String(s).toLowerCase());
      return haystacks.some((h) => h.includes(term));
    });
  }, [editedFilter, search, stateFilter, statusFilter, textUnits]);

  const screenshotImages = useMemo(
    () => project?.reviewProjectRequest?.screenshotImageIds ?? [],
    [project?.reviewProjectRequest?.screenshotImageIds],
  );

  useEffect(() => {
    if (!screenshotImages.length) {
      setSelectedScreenshotIdx(0);
      return;
    }
    setSelectedScreenshotIdx((idx) => Math.min(idx, screenshotImages.length - 1));
  }, [screenshotImages]);

  const selectedTextUnit = useMemo(
    () => filtered.find((tu) => tu.id === selectedTextUnitId),
    [filtered, selectedTextUnitId],
  );

  useEffect(() => {
    if (selectedTextUnitQueryId == null) {
      lastAppliedQueryIdRef.current = null;
      return;
    }
    if (lastAppliedQueryIdRef.current === selectedTextUnitQueryId) {
      return;
    }
    const matchedByTmTextUnitId =
      filtered.find((tu) => tu.tmTextUnit?.id === selectedTextUnitQueryId) ?? null;
    const matchedByReviewTextUnitId =
      filtered.find((tu) => tu.id === selectedTextUnitQueryId) ?? null;
    const matched = matchedByTmTextUnitId ?? matchedByReviewTextUnitId;
    const matchedId = matched?.id ?? null;
    if (matchedId == null) {
      return;
    }
    lastAppliedQueryIdRef.current = selectedTextUnitQueryId;
    if (selectedTextUnitId !== matchedId) {
      pendingQueryAutoScrollIdRef.current = matchedId;
    }
    setSelectedTextUnitId((current) => (current === matchedId ? current : matchedId));
  }, [filtered, selectedTextUnitId, selectedTextUnitQueryId]);

  useEffect(() => {
    if (filtered.length === 0) {
      setSelectedTextUnitId(null);
      return;
    }
    const hasSearchTerm = search.trim().length > 0;
    if (selectedTextUnitQueryId != null) {
      const hasQueryMatch = filtered.some(
        (tu) => tu.tmTextUnit?.id === selectedTextUnitQueryId || tu.id === selectedTextUnitQueryId,
      );
      if (hasQueryMatch) {
        return;
      }
    }
    const hasSelectedInFiltered =
      selectedTextUnitId != null && filtered.some((tu) => tu.id === selectedTextUnitId);
    if (hasSelectedInFiltered) {
      return;
    }
    if (hasSearchTerm) {
      setSelectedTextUnitId(null);
      return;
    }
    if (selectedTextUnitId == null || !hasSelectedInFiltered) {
      setSelectedTextUnitId(filtered[0]?.id ?? null);
    }
  }, [filtered, search, selectedTextUnitId, selectedTextUnitQueryId]);

  useEffect(() => {
    if (selectedTextUnitQueryId != null && !selectedTextUnit) {
      // Wait until we resolve the query-param selection before rewriting URL.
      return;
    }
    const nextQueryId = selectedTextUnit?.tmTextUnit?.id ?? selectedTextUnit?.id ?? null;
    const shouldReplace = selectedTextUnitQueryId == null || nextQueryId == null;
    onSelectedTextUnitIdChange(nextQueryId, { replace: shouldReplace });
  }, [onSelectedTextUnitIdChange, selectedTextUnit, selectedTextUnitQueryId]);

  useEffect(() => {
    if (
      previousSelectedRef.current !== null &&
      previousSelectedRef.current !== selectedTextUnitId &&
      showValidationDialog
    ) {
      onDismissValidationSave();
    }
    previousSelectedRef.current = selectedTextUnitId;
  }, [onDismissValidationSave, selectedTextUnitId, showValidationDialog]);

  const estimateRowHeight = useCallback(
    () =>
      getRowHeightPx({
        cssVariable: '--review-project-row-height',
        defaultRem: 6,
      }),
    [],
  );

  const getItemKey = useCallback((index: number) => filtered[index]?.id ?? index, [filtered]);

  const { scrollRef, items, totalSize, measureElement, scrollToIndex } =
    useVirtualRows<HTMLDivElement>({
      count: filtered.length,
      estimateSize: estimateRowHeight,
      getItemKey,
    });

  useEffect(() => {
    const pendingId = pendingQueryAutoScrollIdRef.current;
    if (pendingId == null) {
      return;
    }
    const index = filtered.findIndex((tu) => tu.id === pendingId);
    if (index < 0) {
      return;
    }
    scrollToIndex(index, { align: 'center' });
    pendingQueryAutoScrollIdRef.current = null;
  }, [filtered, scrollToIndex]);

  const attemptSelectTextUnit = useCallback(
    (nextId: number | null, nextIndex?: number) => {
      if (nextId == null || nextId === selectedTextUnitId || mutations.isSaving) {
        return;
      }
      if (detailIsDirty) {
        setPendingSelection({ id: nextId, index: nextIndex });
        return;
      }
      setSelectedTextUnitId(nextId);
      if (nextIndex != null) {
        scrollToIndex(nextIndex, { align: 'center' });
      }
    },
    [detailIsDirty, mutations.isSaving, scrollToIndex, selectedTextUnitId],
  );

  const handleKeyNav = useCallback(
    (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (
        target &&
        (target.tagName === 'INPUT' ||
          target.tagName === 'TEXTAREA' ||
          target.tagName === 'SELECT' ||
          target.isContentEditable)
      ) {
        return;
      }

      if (!filtered.length) return;

      const idx = selectedTextUnitId
        ? filtered.findIndex((tu) => tu.id === selectedTextUnitId)
        : -1;

      if (event.key === 'ArrowDown' || event.key === 'j') {
        event.preventDefault();
        const nextIndex = Math.min(filtered.length - 1, idx + 1);
        const nextId = filtered[nextIndex]?.id ?? null;
        attemptSelectTextUnit(nextId, nextIndex);
      } else if (event.key === 'ArrowUp' || event.key === 'k') {
        event.preventDefault();
        const prevIndex = Math.max(0, idx <= 0 ? 0 : idx - 1);
        const prevId = filtered[prevIndex]?.id ?? null;
        attemptSelectTextUnit(prevId, prevIndex);
      }
    },
    [attemptSelectTextUnit, filtered, selectedTextUnitId],
  );

  const advanceToNextTextUnit = useCallback(
    (fromId: number | null, focusTranslation: boolean) => {
      if (!filtered.length) {
        return;
      }
      const currentIndex = fromId == null ? -1 : filtered.findIndex((tu) => tu.id === fromId);
      const nextIndex = Math.min(filtered.length - 1, currentIndex + 1);
      const nextId = filtered[nextIndex]?.id ?? null;
      if (nextId == null || nextId === fromId) {
        return;
      }
      setSelectedTextUnitId(nextId);
      scrollToIndex(nextIndex, { align: 'center' });
      if (focusTranslation) {
        setFocusTranslationKey((value) => value + 1);
      }
    },
    [filtered, scrollToIndex],
  );

  const queueAdvance = useCallback(
    (focusTranslation: boolean) => {
      if (selectedTextUnitId == null) {
        return;
      }
      setPendingAdvance({ fromId: selectedTextUnitId, focusTranslation });
    },
    [selectedTextUnitId],
  );

  useEffect(() => {
    if (!pendingAdvance) {
      return;
    }
    if (mutations.isSaving || mutations.showValidationDialog) {
      return;
    }
    if (selectedTextUnitId !== pendingAdvance.fromId) {
      setPendingAdvance(null);
      return;
    }
    advanceToNextTextUnit(pendingAdvance.fromId, pendingAdvance.focusTranslation);
    setPendingAdvance(null);
  }, [
    advanceToNextTextUnit,
    mutations.isSaving,
    mutations.showValidationDialog,
    pendingAdvance,
    selectedTextUnitId,
  ]);

  const confirmDiscardChanges = useCallback(() => {
    if (!pendingSelection) {
      return;
    }
    setDetailIsDirty(false);
    setSelectedTextUnitId(pendingSelection.id);
    if (pendingSelection.index != null) {
      scrollToIndex(pendingSelection.index, { align: 'center' });
    }
    setPendingSelection(null);
  }, [pendingSelection, scrollToIndex]);

  const cancelDiscardChanges = useCallback(() => {
    setPendingSelection(null);
  }, []);

  const setDecisionStateFilter = useCallback(
    (next: DecisionStateFilter) => {
      setStateFilter(next);
      const nextParams = new URLSearchParams(searchParams);
      if (next === 'all') {
        nextParams.delete('state');
      } else {
        nextParams.set('state', next);
      }
      if (nextParams.toString() !== searchParams.toString()) {
        setSearchParams(nextParams, { replace: true });
      }
    },
    [searchParams, setSearchParams],
  );

  useEffect(() => {
    const next = parseDecisionStateFilter(searchParams.get('state'));
    setStateFilter((current) => (current === next ? current : next));
  }, [searchParams]);

  useEffect(() => {
    window.addEventListener('keydown', handleKeyNav);
    return () => window.removeEventListener('keydown', handleKeyNav);
  }, [handleKeyNav]);

  const collapseList = useCallback(() => {
    setIsListCollapsed(true);
    setListWidthPct(0);
  }, []);

  const expandList = useCallback(() => {
    setIsListCollapsed(false);
    setListWidthPct(lastListWidthPct || 20);
  }, [lastListWidthPct]);

  const toggleList = useCallback(() => {
    if (isListCollapsed) {
      expandList();
    } else {
      if (listWidthPct > 0) {
        setLastListWidthPct(listWidthPct);
      }
      collapseList();
    }
  }, [collapseList, expandList, isListCollapsed, listWidthPct]);

  const startResize = useCallback(
    (event: React.MouseEvent) => {
      event.preventDefault();
      setIsResizing(true);
      const onMove = (e: MouseEvent) => {
        if (!layoutRef.current) return;
        const rect = layoutRef.current.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const pct = Math.min(75, Math.max(0, (x / rect.width) * 100));
        if (pct <= 8) {
          if (listWidthPct > 0) {
            setLastListWidthPct(listWidthPct);
          }
          collapseList();
          return;
        }
        if (isListCollapsed) {
          setIsListCollapsed(false);
        }
        setLastListWidthPct(pct);
        setListWidthPct(pct);
      };
      const onUp = () => {
        setIsResizing(false);
        window.removeEventListener('mousemove', onMove);
        window.removeEventListener('mouseup', onUp);
      };
      window.addEventListener('mousemove', onMove);
      window.addEventListener('mouseup', onUp);
    },
    [collapseList, isListCollapsed, listWidthPct],
  );

  if (!project) {
    return <div>No project data for id {projectId}</div>;
  }

  return (
    <div className="review-project-page">
      <ReviewProjectHeader
        projectId={projectId}
        project={project}
        textUnits={textUnits}
        mutations={mutations}
        canEditRequest={canEditRequest}
        reviewProjectsSessionKey={reviewProjectsSessionKey}
        openRequestDetailsQuery={openRequestDetailsQuery}
        requestDetailsSource={requestDetailsSource}
        onRequestDetailsQueryHandled={onRequestDetailsQueryHandled}
        onRequestDetailsFlowFinished={onRequestDetailsFlowFinished}
        onOpenShortcuts={() => setIsShortcutsOpen(true)}
        onReviewPending={() => setDecisionStateFilter('PENDING')}
      />

      <div
        className={`review-project-page__content${isListCollapsed ? ' review-project-page__content--collapsed' : ''}`}
        ref={layoutRef}
      >
        <section
          className={`review-project-page__list-pane${
            isListCollapsed ? ' review-project-page__list-pane--collapsed' : ''
          }`}
        >
          <div className="review-project-page__controls">
            <input
              className="review-project-page__search-input"
              type="search"
              placeholder="Search source, translation, or id"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            <MultiSectionFilterChip
              ariaLabel="Filter text units"
              align="right"
              className="review-project-page__filter-chip"
              classNames={{
                button: 'filter-chip__button',
                panel: 'filter-chip__panel',
                section: 'filter-chip__section',
                label: 'filter-chip__label',
                list: 'filter-chip__list',
                option: 'filter-chip__option',
                helper: 'filter-chip__helper',
              }}
              sections={[
                {
                  kind: 'radio',
                  label: 'State',
                  options: DECISION_STATE_OPTIONS as Array<FilterOption<string | number>>,
                  value: stateFilter,
                  onChange: (value) => setDecisionStateFilter(value as DecisionStateFilter),
                },
                {
                  kind: 'radio',
                  label: 'Status',
                  options: STATUS_FILTER_OPTIONS as Array<FilterOption<string | number>>,
                  value: statusFilter,
                  onChange: (value) => setStatusFilter(value as StatusFilter),
                },
                {
                  kind: 'radio',
                  label: 'Edited',
                  options: EDITED_FILTER_OPTIONS as Array<FilterOption<string | number>>,
                  value: editedFilter,
                  onChange: (value) => setEditedFilter(value as EditedFilter),
                },
              ]}
            />
          </div>
          <VirtualList
            scrollRef={scrollRef}
            items={items}
            totalSize={totalSize}
            renderRow={(virtualItem: VirtualItem) => {
              const textUnit = filtered[virtualItem.index] as ApiReviewProjectTextUnit | undefined;
              if (!textUnit) {
                return null;
              }
              const isDecided = getDecisionState(textUnit) === 'DECIDED';
              return {
                key: virtualItem.key,
                props: {
                  ref: measureElement,
                  onClick: () => attemptSelectTextUnit(textUnit.id, virtualItem.index),
                  className:
                    textUnit.id === selectedTextUnitId
                      ? `review-project-row is-selected${
                          isDecided ? ' review-project-row--decided' : ''
                        }`
                      : `review-project-row${isDecided ? ' review-project-row--decided' : ''}`,
                },
                content: (
                  <TextUnitRow
                    textUnit={textUnit}
                    isSelected={textUnit.id === selectedTextUnitId}
                    isDecided={isDecided}
                  />
                ),
              };
            }}
          />
        </section>
        <div
          className={`review-project-page__resize-handle${
            isResizing ? ' is-resizing' : ''
          }${isListCollapsed ? ' review-project-page__resize-handle--collapsed' : ''}`}
          onMouseDown={startResize}
          role="separator"
          aria-label={isListCollapsed ? 'Expand review list' : 'Collapse review list'}
          aria-orientation="vertical"
          aria-expanded={!isListCollapsed}
        >
          <button
            type="button"
            className="review-project-handle-button review-project-page__collapse-toggle"
            onClick={toggleList}
            onMouseDown={(event) => event.stopPropagation()}
            aria-label={isListCollapsed ? 'Expand review list' : 'Collapse review list'}
            title={isListCollapsed ? 'Expand review list' : 'Collapse review list'}
          >
            <Chevron direction={isListCollapsed ? 'right' : 'left'} />
          </button>
        </div>
        <section className="review-project-page__detail-pane" ref={detailPaneRef}>
          {selectedTextUnit ? (
            <DetailPane
              textUnit={selectedTextUnit}
              localeTag={localeTag}
              mutations={mutations}
              screenshotImages={screenshotImages}
              currentScreenshotIdx={selectedScreenshotIdx}
              onChangeScreenshotIdx={setSelectedScreenshotIdx}
              onOpenGallery={() => setIsScreenshotModalOpen(true)}
              detailPaneRef={detailPaneRef}
              onDirtyChange={setDetailIsDirty}
              onQueueAdvance={queueAdvance}
              focusTranslationKey={focusTranslationKey}
            />
          ) : (
            <div className="review-project-page__empty-detail">No text unit selected</div>
          )}
        </section>
      </div>
      {isScreenshotModalOpen ? (
        <ScreenshotOverlay
          screenshotImages={screenshotImages}
          selectedScreenshotIdx={selectedScreenshotIdx}
          onChangeScreenshotIdx={setSelectedScreenshotIdx}
          onClose={() => setIsScreenshotModalOpen(false)}
        />
      ) : null}
      <ConfirmModal
        open={mutations.showValidationDialog}
        title="Translation check failed"
        body={mutations.validationDialogBody}
        confirmLabel="Save anyway"
        cancelLabel="Keep editing"
        onConfirm={mutations.onConfirmValidationSave}
        onCancel={mutations.onDismissValidationSave}
      />
      <ConfirmModal
        open={pendingSelection != null}
        title="Discard changes?"
        body="You have unsaved edits. Discard them and switch to another text unit?"
        confirmLabel="Discard changes"
        cancelLabel="Keep editing"
        onConfirm={confirmDiscardChanges}
        onCancel={cancelDiscardChanges}
      />
      <Modal
        open={isShortcutsOpen}
        size="md"
        ariaLabel="Keyboard shortcuts"
        onClose={() => setIsShortcutsOpen(false)}
        closeOnBackdrop
      >
        <div className="modal__header">
          <div className="modal__title">Keyboard shortcuts</div>
        </div>
        <div className="modal__body">
          <ul className="review-project-shortcuts__list">
            <li className="review-project-shortcuts__item">
              <span className="review-project-shortcuts__key">Esc</span>
              <span>Stop editing</span>
            </li>
            <li className="review-project-shortcuts__item">
              <span className="review-project-shortcuts__key">Cmd/Ctrl + Enter</span>
              <span>Accept and blur</span>
            </li>
            <li className="review-project-shortcuts__item">
              <span className="review-project-shortcuts__key">Cmd/Ctrl + Shift + Enter</span>
              <span>
                Accept and go to next text unit. If unchanged, mark decided and go to next.
              </span>
            </li>
            <li className="review-project-shortcuts__item">
              <span className="review-project-shortcuts__key">Tab</span>
              <span>Next editor (translation → comment → notes)</span>
            </li>
            <li className="review-project-shortcuts__item">
              <span className="review-project-shortcuts__key">Shift + Tab</span>
              <span>Previous editor</span>
            </li>
            <li className="review-project-shortcuts__item">
              <span className="review-project-shortcuts__key">a</span>
              <span>Accept selected text unit (outside edit fields)</span>
            </li>
            <li className="review-project-shortcuts__item">
              <span className="review-project-shortcuts__key">e</span>
              <span>Focus translation editor (outside edit fields)</span>
            </li>
            <li className="review-project-shortcuts__item">
              <span className="review-project-shortcuts__key">p</span>
              <span>Mark selected text unit as pending (outside edit fields)</span>
            </li>
          </ul>
        </div>
        <div className="modal__actions">
          <button type="button" className="modal__button" onClick={() => setIsShortcutsOpen(false)}>
            Close
          </button>
        </div>
      </Modal>
    </div>
  );
}

function TextUnitRow({
  textUnit,
  isSelected,
  isDecided,
}: {
  textUnit: ApiReviewProjectTextUnit;
  isSelected: boolean;
  isDecided: boolean;
}) {
  if (!textUnit) {
    return null;
  }
  const name = textUnit.tmTextUnit?.name ?? null;
  const source = textUnit.tmTextUnit?.content ?? null;
  const target = getEffectiveVariant(textUnit)?.content ?? null;
  return (
    <div className="review-project-row__inner" data-selected={isSelected ? 'true' : 'false'}>
      <div className="review-project-row__name" title={name != null ? String(name) : undefined}>
        {name || `Text unit ${textUnit.id}`}
      </div>
      <div className="review-project-row__strings">
        <div className="review-project-row__string-line" title={source ?? undefined}>
          <span className="review-project-row__string-text">{source || '—'}</span>
        </div>
        <div
          className="review-project-row__string-line review-project-row__string-line--target"
          title={target ?? undefined}
        >
          <span className="review-project-row__string-text review-project-row__string-text--target">
            {target || '—'}
          </span>
        </div>
      </div>
      {isDecided ? <span className="review-project-row__decided-dot" aria-hidden="true" /> : null}
    </div>
  );
}

function DetailPane({
  textUnit,
  localeTag,
  mutations,
  screenshotImages,
  currentScreenshotIdx,
  onChangeScreenshotIdx,
  onOpenGallery,
  detailPaneRef,
  onDirtyChange,
  onQueueAdvance,
  focusTranslationKey,
}: {
  textUnit: ApiReviewProjectTextUnit;
  localeTag: string;
  mutations: ReviewProjectMutationControls;
  screenshotImages: string[];
  currentScreenshotIdx: number;
  onChangeScreenshotIdx: (index: number) => void;
  onOpenGallery: () => void;
  detailPaneRef: React.RefObject<HTMLDivElement | null>;
  onDirtyChange: (dirty: boolean) => void;
  onQueueAdvance: (focusTranslation: boolean) => void;
  focusTranslationKey: number;
}) {
  const [isScreenshotsCollapsed, setIsScreenshotsCollapsed] = useState(false);
  const [heroHeight, setHeroHeight] = useState<number | null>(null);
  const [isHeroResizing, setIsHeroResizing] = useState(false);
  const [lastHeroHeight, setLastHeroHeight] = useState<number | null>(null);
  const [showBaseline, setShowBaseline] = useState(false);
  const [showStaleDecision, setShowStaleDecision] = useState(false);

  const [showSavingIndicator, setShowSavingIndicator] = useState(false);
  const [isIcuCollapsed, setIsIcuCollapsed] = useState(true);
  const [isHistoryCollapsed, setIsHistoryCollapsed] = useState(true);
  const [icuPreviewMode, setIcuPreviewMode] = useState<'source' | 'target'>('target');
  const [isAiCollapsed, setIsAiCollapsed] = useState(false);
  const [isWarningModalOpen, setIsWarningModalOpen] = useState(false);
  const [aiMessages, setAiMessages] = useState<AiChatReviewMessage[]>([]);
  const [aiInput, setAiInput] = useState('');
  const [isAiResponding, setIsAiResponding] = useState(false);
  const heroRef = useRef<HTMLDivElement | null>(null);
  const translationRef = useRef<HTMLTextAreaElement | null>(null);
  const commentRef = useRef<HTMLTextAreaElement | null>(null);
  const decisionNotesRef = useRef<HTMLTextAreaElement | null>(null);
  const savingIndicatorStartRef = useRef<number | null>(null);
  const savingIndicatorTimeoutRef = useRef<number | null>(null);
  const workbenchTextUnitId = textUnit.tmTextUnit?.id ?? null;
  const repositoryId = textUnit.tmTextUnit?.asset?.repository?.id ?? null;
  const textUnitName = textUnit.tmTextUnit?.name ?? `Text unit ${textUnit.id}`;
  const source = textUnit.tmTextUnit?.content ?? null;
  const sourceComment = textUnit.tmTextUnit?.comment ?? null;
  const baselineVariant = textUnit.baselineTmTextUnitVariant;
  const baselineStatusKey = getStatusKey(baselineVariant);
  const snapshot = useMemo(() => buildSnapshot(textUnit), [textUnit]);
  const snapshotKey = useMemo(() => buildSnapshotKey(textUnit, snapshot), [textUnit, snapshot]);
  const aiContextKey = useMemo(() => {
    const variantId =
      textUnit.currentTmTextUnitVariant?.id ?? textUnit.baselineTmTextUnitVariant?.id ?? 'none';
    return `${textUnit.id}:${localeTag}:${variantId}`;
  }, [
    localeTag,
    textUnit.baselineTmTextUnitVariant?.id,
    textUnit.currentTmTextUnitVariant?.id,
    textUnit.id,
  ]);

  const [draftTarget, setDraftTarget] = useState(snapshot.target);
  const [draftStatusChoice, setDraftStatusChoice] = useState<StatusChoice>(snapshot.statusChoice);
  const [draftComment, setDraftComment] = useState(snapshot.comment ?? '');
  const [draftDecisionNotes, setDraftDecisionNotes] = useState(snapshot.decisionNotes ?? '');
  const isMutationActive = mutations.activeTextUnitId === textUnit.id;
  const isSavingGlobal = mutations.isSaving;
  const isSaving = isMutationActive && isSavingGlobal;
  const errorMessage = isMutationActive ? mutations.errorMessage : null;
  const conflictTextUnit = isMutationActive ? mutations.conflictTextUnit : null;
  const decision = textUnit.reviewProjectTextUnitDecision;
  const decisionVariant = decision?.decisionTmTextUnitVariant ?? null;
  const decisionVariantId = decisionVariant?.id ?? null;
  const translationLang = toHtmlLangTag(localeTag);

  const historyQuery = useQuery({
    queryKey: ['review-project-text-unit-history', workbenchTextUnitId, localeTag],
    enabled: workbenchTextUnitId != null && Boolean(localeTag),
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    queryFn: () => {
      if (workbenchTextUnitId == null || !localeTag) {
        return Promise.resolve([] as ApiTextUnitHistoryItem[]);
      }
      return fetchTextUnitHistory(workbenchTextUnitId, localeTag);
    },
  });

  const historyErrorMessage =
    historyQuery.error instanceof Error ? historyQuery.error.message : 'Unable to load history.';

  const historyRows = useMemo<TextUnitHistoryTimelineEntry[]>(() => {
    return [...(historyQuery.data ?? [])]
      .sort((a, b) => {
        const aTimestamp =
          (a.id === decisionVariantId ? Date.parse(decision?.lastModifiedDate ?? '') || 0 : 0) ||
          (Date.parse(a.createdDate ?? '') || 0);
        const bTimestamp =
          (b.id === decisionVariantId ? Date.parse(decision?.lastModifiedDate ?? '') || 0 : 0) ||
          (Date.parse(b.createdDate ?? '') || 0);
        if (bTimestamp !== aTimestamp) {
          return bTimestamp - aTimestamp;
        }
        return (b.id ?? 0) - (a.id ?? 0);
      })
      .slice(0, 8)
      .map((item) => {
        const isAcceptedItem = item.id === decisionVariantId;
        const statusKey =
          item.includedInLocalizedFile === false ? 'REJECTED' : (item.status ?? null);
        const statusLabel = statusKey != null ? statusKeyToLabel(statusKey) : '-';
        const sourceTmTextUnitId = item.leveraging?.sourceTmTextUnitId;
        const sourceTmTextUnitVariantId = item.leveraging?.sourceTmTextUnitVariantId;
        const leveragingType = item.leveraging?.leveragingType?.trim() || null;
        const isLeveraged =
          typeof sourceTmTextUnitId === 'number' && typeof sourceTmTextUnitVariantId === 'number';
        const userName =
          (
            isAcceptedItem ? decision?.lastModifiedByUsername : item.createdByUser?.username
          )?.trim() ||
          item.createdByUser?.username?.trim() ||
          'Unknown user';
        const displayDate =
          isAcceptedItem && decision?.lastModifiedDate
            ? formatDateTime(decision.lastModifiedDate)
            : formatDateTime(item.createdDate);
        return {
          key: String(item.id),
          variantId: String(item.id),
          userName,
          translation: item.content ?? '—',
          date: displayDate,
          status: statusLabel,
          badges: [
            ...(item.id === baselineVariant?.id ? ['Baseline'] : []),
            ...(item.id === decisionVariantId ? ['Accepted'] : []),
            ...(isLeveraged ? ['Leveraged'] : []),
          ],
          sourceLink: isLeveraged
            ? {
                label: `Source variant #${sourceTmTextUnitVariantId}`,
                to: {
                  pathname: `/text-units/${sourceTmTextUnitId}`,
                  search: localeTag ? `?locale=${encodeURIComponent(localeTag)}` : '',
                },
                title: leveragingType ?? 'Open leveraged source',
              }
            : null,
          comments: (item.tmTextUnitVariantComments ?? []).map((comment, index) => ({
            key:
              comment.id != null
                ? String(comment.id)
                : String(comment.type ?? '') +
                  '-' +
                  String(comment.severity ?? '') +
                  '-' +
                  String(comment.content ?? '') +
                  '-' +
                  String(index),
            type: comment.type ?? '-',
            severity: comment.severity ?? '-',
            content: comment.content ?? '-',
          })),
        };
      });
  }, [
    baselineVariant?.id,
    decision?.lastModifiedByUsername,
    decision?.lastModifiedDate,
    decisionVariantId,
    historyQuery.data,
  ]);

  useEffect(() => {
    setDraftTarget(snapshot.target);
    setDraftStatusChoice(snapshot.statusChoice);
    setDraftComment(snapshot.comment ?? '');
    setDraftDecisionNotes(snapshot.decisionNotes ?? '');
  }, [snapshot, snapshotKey]);

  useEffect(() => {
    if (!localeTag) {
      setAiMessages([]);
      setAiInput('');
      setIsAiResponding(false);
      return;
    }

    let cancelled = false;
    setAiMessages([]);
    setAiInput('');
    setIsAiResponding(true);
    setIsAiCollapsed(false);

    const initialMessage: AiReviewMessage = {
      role: 'user',
      content: DEFAULT_AI_REVIEW_PROMPT,
    };
    const initialWarnings = buildTranslationWarnings(source ?? '', snapshot.target);
    const warningContextMessage = buildAiWarningContextMessage(initialWarnings);

    void (async () => {
      try {
        const contextMessages = [warningContextMessage].filter(
          (message): message is AiReviewMessage => message != null,
        );
        const response = await requestAiReview({
          source: source ?? '',
          target: snapshot.target,
          localeTag,
          sourceDescription: sourceComment ?? '',
          tmTextUnitId: workbenchTextUnitId ?? undefined,
          messages: [...contextMessages, initialMessage],
        });
        if (cancelled) {
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
        if (cancelled) {
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
        if (!cancelled) {
          setIsAiResponding(false);
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [aiContextKey, localeTag, snapshot.target, source, sourceComment, workbenchTextUnitId]);

  const draftStatusApi = mapChoiceToApi(draftStatusChoice);
  const snapshotStatusApi = mapChoiceToApi(snapshot.statusChoice);
  const draftCommentNormalized = normalizeOptional(draftComment);
  const draftDecisionNotesNormalized = normalizeOptional(draftDecisionNotes);
  const isTranslationDirty = draftTarget !== snapshot.target;
  const isDirty =
    isTranslationDirty ||
    draftStatusApi.status !== snapshotStatusApi.status ||
    draftStatusApi.includedInLocalizedFile !== snapshotStatusApi.includedInLocalizedFile ||
    draftCommentNormalized !== snapshot.comment ||
    draftDecisionNotesNormalized !== snapshot.decisionNotes;
  const isRejected = draftStatusApi.includedInLocalizedFile === false;
  const canReset = isDirty && !isSavingGlobal;
  const isAcceptedAndDecided =
    snapshot.statusChoice === 'ACCEPTED' && snapshot.decisionState === 'DECIDED';
  const canAccept = !isSavingGlobal && (!isAcceptedAndDecided || isDirty);
  const isCommentDirty = draftCommentNormalized !== snapshot.comment;
  const isDecisionNotesDirty = draftDecisionNotesNormalized !== snapshot.decisionNotes;
  const isStatusDropdownDisabled =
    isSavingGlobal || isTranslationDirty || isCommentDirty || isDecisionNotesDirty;
  const translationWarnings = useMemo(
    () => buildTranslationWarnings(source ?? '', draftTarget),
    [draftTarget, source],
  );
  const visibleWhitespacePreviewSegments = useMemo(
    () => buildTranslationIssuePreview(source ?? '', draftTarget),
    [draftTarget, source],
  );

  useEffect(() => {
    if (translationWarnings.length === 0) {
      setIsWarningModalOpen(false);
    }
  }, [translationWarnings.length]);

  useEffect(() => {
    onDirtyChange(isDirty);
    return () => onDirtyChange(false);
  }, [isDirty, onDirtyChange]);

  const requestSaveDecision = useCallback(
    ({
      targetOverride,
      statusChoiceOverride,
      commentOverride,
      decisionNotesOverride,
    }: {
      targetOverride?: string;
      statusChoiceOverride?: StatusChoice;
      commentOverride?: string | null;
      decisionNotesOverride?: string | null;
    } = {}) => {
      const nextTarget = targetOverride ?? draftTarget;
      const nextStatusApi = mapChoiceToApi(statusChoiceOverride ?? draftStatusChoice);
      mutations.onRequestSaveDecision({
        textUnitId: textUnit.id,
        tmTextUnitId: workbenchTextUnitId,
        target: nextTarget,
        comment: commentOverride ?? draftCommentNormalized,
        status: nextStatusApi.status,
        includedInLocalizedFile: nextStatusApi.includedInLocalizedFile,
        decisionState: 'DECIDED',
        expectedCurrentTmTextUnitVariantId: snapshot.expectedCurrentVariantId,
        decisionNotes: decisionNotesOverride ?? draftDecisionNotesNormalized,
      });
    },
    [
      draftCommentNormalized,
      draftDecisionNotesNormalized,
      draftStatusChoice,
      draftTarget,
      mutations,
      snapshot.expectedCurrentVariantId,
      textUnit.id,
      workbenchTextUnitId,
    ],
  );

  const requestDecisionState = useCallback(
    (decisionState: DecisionStateChoice) => {
      mutations.onRequestDecisionState({
        textUnitId: textUnit.id,
        decisionState,
        expectedCurrentTmTextUnitVariantId: snapshot.expectedCurrentVariantId,
      });
    },
    [mutations, snapshot.expectedCurrentVariantId, textUnit.id],
  );

  const handleReset = useCallback(() => {
    setDraftTarget(snapshot.target);
    setDraftStatusChoice(snapshot.statusChoice);
    setDraftComment(snapshot.comment ?? '');
    setDraftDecisionNotes(snapshot.decisionNotes ?? '');
  }, [snapshot]);

  const handleEditorKeyDown = useCallback((event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Escape') {
      event.currentTarget.blur();
      event.stopPropagation();
      return;
    }
    if (event.key === 'Tab' && !event.metaKey && !event.ctrlKey && !event.altKey) {
      const editors = [translationRef, commentRef, decisionNotesRef];
      const currentIndex = editors.findIndex((ref) => ref.current === event.currentTarget);
      if (currentIndex === -1) {
        return;
      }
      event.preventDefault();
      const nextIndex = event.shiftKey
        ? (currentIndex - 1 + editors.length) % editors.length
        : (currentIndex + 1) % editors.length;
      editors[nextIndex]?.current?.focus();
    }
  }, []);

  const handleAccept = useCallback(() => {
    requestSaveDecision({ statusChoiceOverride: 'ACCEPTED' });
  }, [requestSaveDecision]);

  const handleStatusChange = useCallback(
    (next: StatusChoice) => {
      setDraftStatusChoice(next);
      if (isStatusDropdownDisabled || next === snapshot.statusChoice) {
        return;
      }
      requestSaveDecision({
        statusChoiceOverride: next,
        targetOverride: snapshot.target,
        commentOverride: snapshot.comment,
        decisionNotesOverride: snapshot.decisionNotes,
      });
    },
    [
      isStatusDropdownDisabled,
      requestSaveDecision,
      snapshot.comment,
      snapshot.decisionNotes,
      snapshot.statusChoice,
      snapshot.target,
    ],
  );

  const handleSubmitAi = useCallback(() => {
    if (isAiResponding || !localeTag) {
      return;
    }

    const trimmed = aiInput.trim();
    if (!trimmed) {
      return;
    }

    const userMessage: AiChatReviewMessage = {
      id: `user-${Date.now()}`,
      sender: 'user',
      content: trimmed,
    };

    const baseMessages = aiMessages.filter((message) => !message.isError);
    setAiMessages((previous) => [...previous, userMessage]);
    setAiInput('');
    setIsAiResponding(true);

    void (async () => {
      try {
        const conversation: AiReviewMessage[] = [...baseMessages, userMessage].map((message) => ({
          role: message.sender,
          content: message.content,
        }));
        const warningContextMessage = buildAiWarningContextMessage(
          buildTranslationWarnings(source ?? '', draftTarget),
        );
        const contextMessages = [warningContextMessage].filter(
          (message): message is AiReviewMessage => message != null,
        );

        const response = await requestAiReview({
          source: source ?? '',
          target: draftTarget,
          localeTag,
          sourceDescription: sourceComment ?? '',
          tmTextUnitId: workbenchTextUnitId ?? undefined,
          messages: [...contextMessages, ...conversation],
        });

        const assistantMessage: AiChatReviewMessage = {
          id: `assistant-${Date.now()}`,
          sender: 'assistant',
          content: response.message.content,
          suggestions: response.suggestions,
          review: response.review,
        };

        setAiMessages((previous) => [...previous, assistantMessage]);
      } catch (error: unknown) {
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
        setIsAiResponding(false);
      }
    })();
  }, [
    aiInput,
    aiMessages,
    draftTarget,
    isAiResponding,
    localeTag,
    source,
    sourceComment,
    workbenchTextUnitId,
  ]);

  const handleRetryAi = useCallback(() => {
    if (isAiResponding || !localeTag) {
      return;
    }

    const baseMessages = aiMessages.filter((message) => !message.isError);
    const conversation: AiReviewMessage[] =
      baseMessages.length > 0
        ? baseMessages.map((message) => ({
            role: message.sender,
            content: message.content,
          }))
        : [{ role: 'user', content: DEFAULT_AI_REVIEW_PROMPT }];
    const retryTarget = baseMessages.length > 0 ? draftTarget : snapshot.target;
    const warningContextMessage = buildAiWarningContextMessage(
      buildTranslationWarnings(source ?? '', retryTarget),
    );

    setIsAiResponding(true);
    void (async () => {
      try {
        const contextMessages = [warningContextMessage].filter(
          (message): message is AiReviewMessage => message != null,
        );
        const response = await requestAiReview({
          source: source ?? '',
          target: retryTarget,
          localeTag,
          sourceDescription: sourceComment ?? '',
          tmTextUnitId: workbenchTextUnitId ?? undefined,
          messages: [...contextMessages, ...conversation],
        });
        const assistantMessage: AiChatReviewMessage = {
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
        setIsAiResponding(false);
      }
    })();
  }, [
    aiMessages,
    draftTarget,
    isAiResponding,
    localeTag,
    snapshot.target,
    source,
    sourceComment,
    workbenchTextUnitId,
  ]);

  const handleUseAiSuggestion = useCallback((suggestion: AiReviewSuggestion) => {
    setDraftTarget(suggestion.content);
  }, []);

  const getFocusedTextarea = useCallback(() => {
    const active = document.activeElement;
    if (active && active instanceof HTMLTextAreaElement) {
      return active;
    }
    return null;
  }, []);

  const isEditableTarget = useCallback((target: EventTarget | null): boolean => {
    if (!(target instanceof HTMLElement)) {
      return false;
    }
    const tagName = target.tagName;
    return (
      tagName === 'INPUT' ||
      tagName === 'TEXTAREA' ||
      tagName === 'SELECT' ||
      target.isContentEditable
    );
  }, []);

  useEffect(() => {
    if (focusTranslationKey === 0) {
      return;
    }
    translationRef.current?.focus();
  }, [focusTranslationKey]);

  useEffect(() => {
    const handleSaveShortcut = (event: KeyboardEvent) => {
      const lowerKey = event.key.toLowerCase();
      const isPlainKey = !event.metaKey && !event.ctrlKey && !event.altKey && !event.shiftKey;
      const isOutsideEditable = !isEditableTarget(event.target) && !event.repeat;

      if (lowerKey === 'e' && isPlainKey) {
        if (!isOutsideEditable) {
          return;
        }
        event.preventDefault();
        translationRef.current?.focus();
        return;
      }

      if (lowerKey === 'p' && isPlainKey) {
        if (!isOutsideEditable) {
          return;
        }
        if (mutations.showValidationDialog || mutations.isSaving || isDirty) {
          return;
        }
        if (snapshot.decisionState !== 'PENDING') {
          event.preventDefault();
          requestDecisionState('PENDING');
        }
        return;
      }

      const isAcceptHotkey =
        lowerKey === 'a' && !event.metaKey && !event.ctrlKey && !event.altKey && !event.shiftKey;
      if (isAcceptHotkey) {
        if (isEditableTarget(event.target) || event.repeat) {
          return;
        }
        if (mutations.showValidationDialog || mutations.isSaving) {
          return;
        }
        if (canAccept) {
          event.preventDefault();
          handleAccept();
        }
        return;
      }

      if (event.key !== 'Enter' || (!event.metaKey && !event.ctrlKey)) {
        return;
      }
      const focusedTextarea = getFocusedTextarea();
      if (focusedTextarea) {
        event.preventDefault();
      }
      if (event.shiftKey) {
        if (mutations.showValidationDialog || mutations.isSaving) {
          return;
        }
        if (canAccept) {
          handleAccept();
        } else if (snapshot.decisionState !== 'DECIDED') {
          requestDecisionState('DECIDED');
        }
        onQueueAdvance(true);
        focusedTextarea?.blur();
        return;
      }
      if (mutations.showValidationDialog) {
        focusedTextarea?.blur();
        return;
      }
      if (canAccept) {
        handleAccept();
      }
      focusedTextarea?.blur();
    };
    window.addEventListener('keydown', handleSaveShortcut);
    return () => window.removeEventListener('keydown', handleSaveShortcut);
  }, [
    canAccept,
    getFocusedTextarea,
    isEditableTarget,
    handleAccept,
    isDirty,
    mutations.isSaving,
    mutations.showValidationDialog,
    onQueueAdvance,
    requestDecisionState,
    snapshot.decisionState,
  ]);

  const handleDecisionStateChange = useCallback(
    (nextState: DecisionStateChoice) => {
      if (nextState === snapshot.decisionState) {
        return;
      }
      requestDecisionState(nextState);
    },
    [requestDecisionState, snapshot.decisionState],
  );

  const handleUseCurrent = useCallback(() => {
    if (!isMutationActive) {
      return;
    }
    mutations.onUseConflictCurrent();
  }, [isMutationActive, mutations]);

  const handleOverwrite = useCallback(() => {
    if (!isMutationActive) {
      return;
    }
    mutations.onOverwriteConflict();
  }, [isMutationActive, mutations]);

  const conflictVariant = conflictTextUnit ? getEffectiveVariant(conflictTextUnit) : null;
  const conflictStatusKey = getStatusKey(conflictVariant);

  const recomputeHeroHeight = useCallback(() => {
    if (!heroRef.current || !detailPaneRef.current || !screenshotImages.length) {
      return;
    }
    const containerHeight = detailPaneRef.current.clientHeight;
    if (!containerHeight) {
      return;
    }
    const minHeight = 140;
    const maxHeight = Math.max(minHeight, Math.floor(containerHeight * 0.6));
    const targetHeight = Math.min(
      maxHeight,
      Math.max(minHeight, Math.floor(containerHeight * 0.4)),
    );
    const clamp = (value: number) => Math.min(maxHeight, Math.max(minHeight, value));
    setHeroHeight((prev) => (prev == null ? targetHeight : clamp(prev)));
    setLastHeroHeight((prev) => (prev == null ? targetHeight : clamp(prev)));
  }, [detailPaneRef, screenshotImages.length]);

  useEffect(() => {
    recomputeHeroHeight();
  }, [recomputeHeroHeight, textUnit.id]);

  useEffect(() => {
    if (!detailPaneRef.current || !screenshotImages.length) {
      return;
    }
    if (typeof ResizeObserver === 'undefined') {
      recomputeHeroHeight();
      return;
    }
    const observer = new ResizeObserver(() => {
      recomputeHeroHeight();
    });
    observer.observe(detailPaneRef.current);
    return () => observer.disconnect();
  }, [detailPaneRef, recomputeHeroHeight, screenshotImages.length]);

  useEffect(() => {
    if (!screenshotImages.length) {
      setHeroHeight(null);
      setLastHeroHeight(null);
      setIsScreenshotsCollapsed(false);
    }
  }, [screenshotImages.length]);

  useEffect(() => {
    if (isScreenshotsCollapsed) {
      if (heroHeight != null) {
        setLastHeroHeight(heroHeight);
      }
      return;
    }
    if (heroHeight == null && lastHeroHeight != null) {
      setHeroHeight(lastHeroHeight);
    }
  }, [heroHeight, isScreenshotsCollapsed, lastHeroHeight]);

  useEffect(() => {
    setShowBaseline(false);
    setShowStaleDecision(false);
    setIsIcuCollapsed(true);
    setIcuPreviewMode('target');
  }, [textUnit.id]);

  useEffect(() => {
    if (isSaving) {
      if (savingIndicatorTimeoutRef.current != null) {
        window.clearTimeout(savingIndicatorTimeoutRef.current);
        savingIndicatorTimeoutRef.current = null;
      }
      savingIndicatorStartRef.current = Date.now();
      setShowSavingIndicator(true);
      return;
    }

    if (!showSavingIndicator) {
      return;
    }

    const startedAt = savingIndicatorStartRef.current;
    const elapsed = startedAt != null ? Date.now() - startedAt : SAVING_INDICATOR_MIN_MS;
    const remaining = SAVING_INDICATOR_MIN_MS - elapsed;

    if (remaining <= 0) {
      setShowSavingIndicator(false);
      savingIndicatorStartRef.current = null;
      return;
    }

    savingIndicatorTimeoutRef.current = window.setTimeout(() => {
      setShowSavingIndicator(false);
      savingIndicatorStartRef.current = null;
      savingIndicatorTimeoutRef.current = null;
    }, remaining);

    return () => {
      if (savingIndicatorTimeoutRef.current != null) {
        window.clearTimeout(savingIndicatorTimeoutRef.current);
        savingIndicatorTimeoutRef.current = null;
      }
    };
  }, [isSaving, showSavingIndicator]);

  return (
    <div className="review-project-detail">
      <div className="review-project-detail__header">
        <div className="review-project-detail__title" />
      </div>
      <div
        className={`review-project-detail__hero${
          screenshotImages.length ? ' review-project-detail__hero--has-shots' : ''
        }${isScreenshotsCollapsed ? ' review-project-detail__hero--collapsed' : ''}`}
        ref={heroRef}
        style={
          !isScreenshotsCollapsed && heroHeight != null ? { height: `${heroHeight}px` } : undefined
        }
      >
        {screenshotImages.length ? (
          <div className="review-project-detail__shots-badge">
            {`${currentScreenshotIdx + 1} / ${screenshotImages.length}`}
          </div>
        ) : null}
        {screenshotImages.length ? (
          <>
            {isScreenshotsCollapsed ? null : (
              <div className="review-project-detail__gallery review-project-detail__gallery--hero">
                <button
                  type="button"
                  className="review-project-detail__gallery-nav"
                  onClick={() =>
                    onChangeScreenshotIdx(
                      (currentScreenshotIdx - 1 + screenshotImages.length) %
                        screenshotImages.length,
                    )
                  }
                  aria-label="Previous screenshot"
                >
                  ‹
                </button>
                <div className="review-project-detail__gallery-main review-project-detail__gallery-main--hero">
                  {renderMedia(
                    screenshotImages[currentScreenshotIdx],
                    'review-project-detail__gallery-image review-project-detail__gallery-image--interactive',
                    {
                      controls: false,
                      muted: true,
                      loop: true,
                      preload: 'metadata',
                      onClick: onOpenGallery,
                      ariaLabel: 'Open screenshot gallery',
                      onLoad: recomputeHeroHeight,
                      onLoadedMetadata: recomputeHeroHeight,
                    },
                  )}
                </div>
                <button
                  type="button"
                  className="review-project-detail__gallery-nav"
                  onClick={() =>
                    onChangeScreenshotIdx((currentScreenshotIdx + 1) % screenshotImages.length)
                  }
                  aria-label="Next screenshot"
                >
                  ›
                </button>
              </div>
            )}
          </>
        ) : null}
      </div>
      {screenshotImages.length ? (
        <div
          className={`review-project-detail__hero-resize-handle${
            isHeroResizing ? ' is-resizing' : ''
          }${isScreenshotsCollapsed ? ' review-project-detail__hero-resize-handle--collapsed' : ''}`}
          onMouseDown={(event) => {
            if (!heroRef.current) return;
            event.preventDefault();
            if (isScreenshotsCollapsed) {
              setIsScreenshotsCollapsed(false);
            }
            setIsHeroResizing(true);
            const rect = heroRef.current.getBoundingClientRect();
            if (heroHeight == null) {
              setHeroHeight(rect.height);
              setLastHeroHeight(rect.height);
            }
            const minHeight = 140;
            const containerHeight =
              detailPaneRef.current?.clientHeight ??
              heroRef.current.parentElement?.clientHeight ??
              window.innerHeight;
            const maxHeight = Math.max(minHeight, Math.floor(containerHeight * 0.6));
            const onMove = (e: MouseEvent) => {
              const rawNext = Math.min(maxHeight, Math.max(0, e.clientY - rect.top));
              if (rawNext <= 80) {
                if (!isScreenshotsCollapsed) {
                  setIsScreenshotsCollapsed(true);
                }
                return;
              }
              if (isScreenshotsCollapsed) {
                setIsScreenshotsCollapsed(false);
              }
              const next = Math.max(minHeight, rawNext);
              setHeroHeight(next);
              setLastHeroHeight(next);
            };
            const onUp = () => {
              setIsHeroResizing(false);
              window.removeEventListener('mousemove', onMove);
              window.removeEventListener('mouseup', onUp);
            };
            window.addEventListener('mousemove', onMove);
            window.addEventListener('mouseup', onUp);
          }}
          role="separator"
          aria-label="Resize screenshots panel"
          aria-orientation="horizontal"
        >
          <button
            type="button"
            className="review-project-handle-button review-project-detail__hero-toggle"
            onClick={() =>
              setIsScreenshotsCollapsed((prev) => {
                if (!prev && heroHeight != null) {
                  setLastHeroHeight(heroHeight);
                }
                if (prev && lastHeroHeight != null) {
                  setHeroHeight(lastHeroHeight);
                }
                return !prev;
              })
            }
            onMouseDown={(event) => event.stopPropagation()}
            aria-label={isScreenshotsCollapsed ? 'Show screenshots' : 'Hide screenshots'}
            title={isScreenshotsCollapsed ? 'Show screenshots' : 'Hide screenshots'}
          >
            <Chevron direction={isScreenshotsCollapsed ? 'down' : 'up'} />
          </button>
        </div>
      ) : null}
      <div className="review-project-detail__layout">
        <div className="review-project-detail__main">
          {conflictTextUnit ? (
            <div className="review-project-detail__conflict" role="alert">
              <div className="review-project-detail__conflict-title-row">
                <div className="review-project-detail__conflict-title">
                  An external translation was added.
                </div>
                {conflictStatusKey ? (
                  <Pill
                    className={`review-project-detail__status-pill review-project-detail__status-chip review-project-detail__status-pill--right review-project-detail__status-chip--${statusKeyToChipClass(
                      conflictStatusKey,
                    )}`}
                  >
                    {statusKeyToLabel(conflictStatusKey)}
                  </Pill>
                ) : null}
              </div>
              <div className="review-project-detail__conflict-text">
                {conflictVariant?.content ?? '—'}
              </div>
              <div className="review-project-detail__conflict-actions">
                <button
                  type="button"
                  className="review-project-detail__actions-button"
                  onClick={handleUseCurrent}
                  disabled={isSavingGlobal}
                >
                  Use external
                </button>
                <button
                  type="button"
                  className="review-project-detail__actions-button review-project-detail__actions-button--primary"
                  onClick={handleOverwrite}
                  disabled={isSavingGlobal}
                >
                  Use mine
                </button>
              </div>
            </div>
          ) : null}

          {showBaseline && baselineVariant?.id != null ? (
            <div className="review-project-detail__field review-project-detail__field--baseline">
              <div className="review-project-detail__label-row review-project-detail__label-row--spread">
                <div className="review-project-detail__label">Baseline</div>
                <div className="review-project-detail__label-status" aria-hidden>
                  {baselineStatusKey ? (
                    <Pill
                      className={`review-project-detail__status-pill review-project-detail__status-chip review-project-detail__status-chip--${statusKeyToChipClass(
                        baselineStatusKey,
                      )}`}
                    >
                      {statusKeyToLabel(baselineStatusKey)}
                    </Pill>
                  ) : null}
                </div>
                <button
                  type="button"
                  className="review-project-detail__baseline-toggle review-project-detail__label-actions review-project-detail__label-actions--right review-project-detail__label-actions--fade"
                  onClick={() => setShowBaseline(false)}
                >
                  Hide
                </button>
              </div>
              <AutoTextarea
                className="review-project-detail__input review-project-detail__input--baseline review-project-detail__input--autosize"
                value={baselineVariant.content ?? ''}
                readOnly
                rows={1}
                style={{ resize: 'none' }}
              />
            </div>
          ) : null}

          {showStaleDecision ? (
            <div className="review-project-detail__field review-project-detail__field--baseline">
              <div className="review-project-detail__label-row review-project-detail__label-row--spread">
                <div className="review-project-detail__label">Stale decision</div>
                <div className="review-project-detail__label-status" aria-hidden>
                  {(() => {
                    const staleStatusKey = getStatusKey(decisionVariant);
                    return staleStatusKey ? (
                      <Pill
                        className={`review-project-detail__status-pill review-project-detail__status-chip review-project-detail__status-chip--${statusKeyToChipClass(
                          staleStatusKey,
                        )}`}
                      >
                        {statusKeyToLabel(staleStatusKey)}
                      </Pill>
                    ) : null;
                  })()}
                </div>
                <button
                  type="button"
                  className="review-project-detail__baseline-toggle review-project-detail__label-actions review-project-detail__label-actions--right review-project-detail__label-actions--fade"
                  onClick={() => setShowStaleDecision(false)}
                >
                  Hide
                </button>
              </div>
              <AutoTextarea
                className="review-project-detail__input review-project-detail__input--baseline review-project-detail__input--autosize"
                value={decisionVariant?.content ?? ''}
                readOnly
                rows={1}
                style={{ resize: 'none' }}
              />
            </div>
          ) : null}

          <div className="review-project-detail__field review-project-detail__field--translation">
            <div className="review-project-detail__label-row">
              <div className="review-project-detail__label">Translation</div>
            </div>
            <AutoTextarea
              className={`review-project-detail__input review-project-detail__input--autosize review-project-detail__input--translation${
                isRejected ? ' review-project-detail__input--rejected' : ''
              }`}
              ref={translationRef}
              value={draftTarget}
              onChange={(event) => {
                setDraftTarget(event.target.value);
              }}
              spellCheck={true}
              lang={translationLang}
              onKeyDown={handleEditorKeyDown}
              rows={1}
              style={{ resize: 'none' }}
            />
          </div>

          <div className="review-project-detail__editor-controls">
            <div className="review-project-detail__decision-cluster">
              <div className="review-project-detail__decision-segmented" role="group">
                <button
                  type="button"
                  className={`review-project-detail__decision-option${
                    snapshot.decisionState === 'PENDING' ? ' is-active' : ''
                  }`}
                  onClick={() => handleDecisionStateChange('PENDING')}
                  disabled={isDirty || isSavingGlobal}
                  aria-pressed={snapshot.decisionState === 'PENDING'}
                >
                  Pending
                </button>
                <button
                  type="button"
                  className={`review-project-detail__decision-option${
                    snapshot.decisionState === 'DECIDED' ? ' is-active' : ''
                  }`}
                  onClick={() => handleDecisionStateChange('DECIDED')}
                  disabled={isDirty || isSavingGlobal}
                  aria-pressed={snapshot.decisionState === 'DECIDED'}
                >
                  Decided
                </button>
              </div>
            </div>
            <div
              className={`review-project-detail__saving-indicator${
                showSavingIndicator ? ' is-active' : ''
              }`}
              role="status"
              aria-live="polite"
              aria-hidden={!showSavingIndicator}
            >
              <span className="spinner" aria-hidden="true" />
              <span>Saving…</span>
            </div>
            <div className="review-project-detail__editor-actions">
              <button
                type="button"
                className="review-project-detail__actions-button"
                onClick={handleReset}
                disabled={!canReset}
              >
                Reset
              </button>
              <button
                type="button"
                className="review-project-detail__actions-button review-project-detail__actions-button--primary"
                onClick={handleAccept}
                disabled={!canAccept}
              >
                Accept
              </button>
            </div>
          </div>

          {translationWarnings.length > 0 ? (
            <button
              type="button"
              className="review-project-detail__warning-inline"
              onClick={() => setIsWarningModalOpen(true)}
              aria-haspopup="dialog"
              aria-label={`${translationWarnings.length} translation warnings`}
            >
              <span className="review-project-detail__warning-inline-pill">
                {translationWarnings.length} warning
                {translationWarnings.length === 1 ? '' : 's'}
              </span>
              <span className="review-project-detail__warning-inline-summary">
                <span>{translationWarnings[0]?.message}</span>
                {translationWarnings.length > 1 ? (
                  <span> +{translationWarnings.length - 1} more</span>
                ) : null}
              </span>
            </button>
          ) : null}

          <IcuPreviewSection
            sourceMessage={source}
            targetMessage={draftTarget}
            targetLocale={localeTag}
            mode={icuPreviewMode}
            isCollapsed={isIcuCollapsed}
            onToggleCollapsed={() => setIsIcuCollapsed((current) => !current)}
            onChangeMode={(mode) => {
              setIcuPreviewMode(mode);
              setIsIcuCollapsed(false);
            }}
            className="review-project-detail__field review-project-detail__field--icu"
            titleClassName="review-project-detail__label"
          />

          <div className="review-project-detail__field review-project-detail__field--ai-chat">
            <div className="review-project-detail__label-row">
              <div className="review-project-detail__label">AI Chat Review</div>
              <button
                type="button"
                className="review-project-detail__baseline-toggle review-project-detail__label-actions--fade"
                onClick={() => setIsAiCollapsed((current) => !current)}
              >
                {isAiCollapsed ? 'Show' : 'Hide'}
              </button>
            </div>
            {!isAiCollapsed ? (
              <AiChatReview
                className="review-project-detail__ai-chat"
                messages={aiMessages}
                input={aiInput}
                onChangeInput={setAiInput}
                onSubmit={handleSubmitAi}
                onUseSuggestion={handleUseAiSuggestion}
                onRetryError={handleRetryAi}
                isResponding={isAiResponding}
              />
            ) : null}
          </div>

          <div className="review-project-detail__field">
            <div className="review-project-detail__label">Comment on translation</div>
            <AutoTextarea
              className="review-project-detail__input review-project-detail__input--compact review-project-detail__input--autosize"
              ref={commentRef}
              value={draftComment}
              onChange={(event) => setDraftComment(event.target.value)}
              placeholder="Explain why you chose this translation (if not obvious)."
              onKeyDown={handleEditorKeyDown}
              rows={1}
              style={{ resize: 'none' }}
            />
          </div>

          <div className="review-project-detail__field">
            <div className="review-project-detail__label">Decision notes</div>
            <AutoTextarea
              className="review-project-detail__input review-project-detail__input--compact review-project-detail__input--autosize"
              ref={decisionNotesRef}
              value={draftDecisionNotes}
              onChange={(event) => setDraftDecisionNotes(event.target.value)}
              placeholder="Explain why the baseline translation was bad (to improve AI translation)."
              onKeyDown={handleEditorKeyDown}
              rows={1}
              style={{ resize: 'none' }}
            />
          </div>

          {errorMessage ? <div className="review-project-detail__error">{errorMessage}</div> : null}
        </div>

        <div className="review-project-detail__side">
          <div className="review-project-detail__field review-project-detail__field--source">
            <div className="review-project-detail__label">Source</div>
            <div className="review-project-detail__value review-project-detail__value--source">
              {source || '—'}
            </div>
          </div>

          <div className="review-project-detail__field">
            <div className="review-project-detail__label">Comment</div>
            <div className="review-project-detail__value">{sourceComment ?? '—'}</div>
          </div>

          <div className="review-project-detail__field">
            <div className="review-project-detail__label-row">
              <div className="review-project-detail__label">Id</div>
              {workbenchTextUnitId != null ? (
                <Link
                  className="review-project-detail__baseline-toggle review-project-detail__label-actions--fade"
                  to={{
                    pathname: '/workbench',
                    search: `?tmTextUnitId=${encodeURIComponent(
                      String(workbenchTextUnitId),
                    )}${localeTag ? `&locale=${encodeURIComponent(localeTag)}` : ''}${
                      repositoryId != null
                        ? `&repo=${encodeURIComponent(String(repositoryId))}`
                        : ''
                    }`,
                  }}
                  state={{
                    workbenchSearch: {
                      searchAttribute: 'tmTextUnitIds',
                      searchType: 'exact',
                      searchText: String(workbenchTextUnitId),
                      localeTags: [localeTag],
                      repositoryIds: repositoryId != null ? [repositoryId] : [],
                    },
                  }}
                  title="Open this string in Workbench"
                >
                  Open in Workbench
                </Link>
              ) : null}
            </div>
            <div className="review-project-detail__value review-project-detail__value--meta">
              <span className="review-project-detail__title-text">{textUnitName}</span>
            </div>
          </div>

          <div className="review-project-detail__field review-project-detail__field--status">
            <div className="review-project-detail__label">Status</div>
            <PillDropdown
              value={draftStatusChoice}
              options={STATUS_CHOICES.map((option) => ({
                value: option.value,
                label: option.label,
              }))}
              onChange={handleStatusChange}
              ariaLabel="Translation status"
              className="review-project-detail__status-dropdown"
              disabled={isStatusDropdownDisabled}
            />
          </div>

          <div className="review-project-detail__field review-project-detail__field--history">
            <button
              type="button"
              className="review-project-detail__history-toggle"
              onClick={() => setIsHistoryCollapsed((current) => !current)}
              aria-expanded={!isHistoryCollapsed}
            >
              <span className="review-project-detail__label">History</span>
              <span className="review-project-detail__baseline-toggle review-project-detail__label-actions--fade">
                {isHistoryCollapsed ? 'Show' : 'Hide'}
              </span>
            </button>
            {!isHistoryCollapsed ? (
              <TextUnitHistoryTimeline
                isLoading={historyQuery.isLoading}
                errorMessage={historyQuery.isError ? historyErrorMessage : null}
                entries={historyRows}
                initialDate={formatDateTime(textUnit.tmTextUnit?.createdDate)}
                emptyMessage="No history yet."
              />
            ) : null}
          </div>
        </div>
      </div>
      <Modal
        open={isWarningModalOpen}
        size="md"
        closeOnBackdrop
        onClose={() => setIsWarningModalOpen(false)}
        ariaLabel="Translation warnings"
      >
        <div className="modal__title">Translation warnings</div>
        <div className="modal__body">
          {translationWarnings.length > 0 ? (
            <>
              <p className="review-project-detail__warning-modal-summary">
                {translationWarnings.length} issue
                {translationWarnings.length === 1 ? '' : 's'} detected.
              </p>
              <ul className="review-project-detail__warning-modal-list">
                {translationWarnings.map((warning) => (
                  <li key={warning.code}>{warning.message}</li>
                ))}
              </ul>
              <div className="review-project-detail__warning-modal-preview-label">
                Visible whitespace preview
              </div>
              <pre className="review-project-detail__warning-modal-preview">
                {visibleWhitespacePreviewSegments.length > 0
                  ? visibleWhitespacePreviewSegments.map((segment, idx) => (
                      <span
                        key={`${segment.issue ? 'issue' : 'normal'}-${idx}`}
                        className={
                          segment.issue
                            ? 'review-project-detail__warning-modal-preview-issue'
                            : undefined
                        }
                      >
                        {segment.text}
                      </span>
                    ))
                  : '(empty)'}
              </pre>
            </>
          ) : (
            'No translation warnings.'
          )}
        </div>
        <div className="modal__actions">
          <button
            type="button"
            className="modal__button modal__button--primary"
            onClick={() => setIsWarningModalOpen(false)}
          >
            Close
          </button>
        </div>
      </Modal>
    </div>
  );
}

function ScreenshotOverlay({
  screenshotImages,
  selectedScreenshotIdx,
  onChangeScreenshotIdx,
  onClose,
}: {
  screenshotImages: string[];
  selectedScreenshotIdx: number;
  onChangeScreenshotIdx: (index: number) => void;
  onClose: () => void;
}) {
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  return (
    <div
      className="review-project-screenshot-overlay"
      role="dialog"
      aria-modal="true"
      aria-label="Screenshot gallery"
    >
      <div className="review-project-screenshot-modal">
        <div className="review-project-screenshot-modal__header">
          <div className="review-project-screenshot-modal__header-group review-project-screenshot-modal__header-group--left">
            <button
              type="button"
              className="review-project-page__header-back-link review-project-screenshot-modal__back"
              onClick={onClose}
              aria-label="Back to review project"
              title="Back to review project"
            >
              <svg
                className="review-project-page__header-back-icon"
                viewBox="0 0 24 24"
                aria-hidden="true"
                focusable="false"
              >
                <path
                  d="M20 12H6m0 0l5-5m-5 5l5 5"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            </button>
            <span className="review-project-page__header-name review-project-screenshot-modal__title">
              Screenshots
            </span>
          </div>
          <div className="review-project-screenshot-modal__header-group review-project-screenshot-modal__header-group--center">
            {screenshotImages.length ? (
              <span className="review-project-screenshot-modal__count">
                <span className="review-project-screenshot-modal__count-number">
                  {selectedScreenshotIdx + 1}
                </span>
                <span className="review-project-screenshot-modal__count-sep"> / </span>
                <span className="review-project-screenshot-modal__count-number">
                  {screenshotImages.length}
                </span>
              </span>
            ) : null}
          </div>
          <div className="review-project-screenshot-modal__header-group review-project-screenshot-modal__header-group--right" />
        </div>
        {screenshotImages.length ? (
          <div className="review-project-screenshot-modal__gallery">
            <button
              type="button"
              className="review-project-screenshot-lightbox__nav review-project-screenshot-lightbox__nav--prev"
              onClick={() =>
                onChangeScreenshotIdx(
                  (selectedScreenshotIdx - 1 + screenshotImages.length) % screenshotImages.length,
                )
              }
              aria-label="Previous screenshot"
            >
              ‹
            </button>
            <div className="review-project-detail__gallery-main review-project-detail__gallery-main--modal">
              {renderMedia(
                screenshotImages[selectedScreenshotIdx],
                'review-project-screenshot-modal__image--main',
              )}
            </div>
            <button
              type="button"
              className="review-project-screenshot-lightbox__nav review-project-screenshot-lightbox__nav--next"
              onClick={() =>
                onChangeScreenshotIdx((selectedScreenshotIdx + 1) % screenshotImages.length)
              }
              aria-label="Next screenshot"
            >
              ›
            </button>
          </div>
        ) : null}
        {screenshotImages.length ? (
          <div className="review-project-detail__thumbs review-project-detail__thumbs--modal">
            {screenshotImages.map((key, idx) => {
              const isActive = idx === selectedScreenshotIdx;
              return (
                <button
                  key={`${key}-${idx}`}
                  type="button"
                  className={`review-project-detail__thumb${isActive ? ' is-active' : ''}`}
                  onClick={() => onChangeScreenshotIdx(idx)}
                  title="Click to preview"
                >
                  {renderThumbMedia(key)}
                </button>
              );
            })}
          </div>
        ) : null}
      </div>
    </div>
  );
}

function ReviewProjectHeader({
  projectId,
  project,
  textUnits: textUnitsProp,
  mutations,
  canEditRequest,
  reviewProjectsSessionKey,
  openRequestDetailsQuery,
  requestDetailsSource,
  onRequestDetailsQueryHandled,
  onRequestDetailsFlowFinished,
  onOpenShortcuts,
  onReviewPending,
}: {
  projectId: number;
  project: ApiReviewProjectDetail;
  textUnits: ApiReviewProjectTextUnit[];
  mutations: ReviewProjectMutationControls;
  canEditRequest: boolean;
  reviewProjectsSessionKey: string | null;
  openRequestDetailsQuery: boolean;
  requestDetailsSource: 'list' | null;
  onRequestDetailsQueryHandled: () => void;
  onRequestDetailsFlowFinished: () => void;
  onOpenShortcuts: () => void;
  onReviewPending: () => void;
}) {
  const { dueDate, textUnitCount, wordCount, status, type } = project;
  const name = project.reviewProjectRequest?.name ?? null;
  const requestId = project.reviewProjectRequest?.id ?? null;
  const description = project.reviewProjectRequest?.notes ?? '';
  const requestCreatedDate = project.reviewProjectRequest?.createdDate ?? project.createdDate ?? null;
  const requestCreatedBy = project.reviewProjectRequest?.createdByUsername ?? null;
  const requestAttachments = useMemo(
    () => project.reviewProjectRequest?.screenshotImageIds ?? [],
    [project.reviewProjectRequest?.screenshotImageIds],
  );
  const locale = project.locale ?? null;
  const textUnits = useMemo(() => textUnitsProp ?? [], [textUnitsProp]);
  const locales = useMemo(() => (locale ? [locale] : []), [locale]);
  const nextStatus = status === 'OPEN' ? 'CLOSED' : 'OPEN';
  const actionLabel = status === 'OPEN' ? 'Close project' : 'Reopen project';
  const [showCloseWarning, setShowCloseWarning] = useState(false);
  const [showDescription, setShowDescription] = useState(false);
  const [requestNameDraft, setRequestNameDraft] = useState(name ?? '');
  const [descriptionDraft, setDescriptionDraft] = useState(description);
  const [projectTypeDraft, setProjectTypeDraft] = useState<ApiReviewProjectType>(type);
  const [dueDateDraft, setDueDateDraft] = useState(toDateTimeLocalInputValue(dueDate));
  const [attachmentDrafts, setAttachmentDrafts] = useState<string[]>(requestAttachments);
  const [attachmentUploadQueue, setAttachmentUploadQueue] = useState<
    RequestAttachmentUploadQueueItem[]
  >([]);
  const attachmentUploadPreviewUrlsRef = useRef<Set<string>>(new Set());
  const [isAttachmentUploading, setIsAttachmentUploading] = useState(false);
  const [attachmentUploadError, setAttachmentUploadError] = useState<string | null>(null);
  const [requestSaveError, setRequestSaveError] = useState<string | null>(null);
  const [assignmentTeamIdDraft, setAssignmentTeamIdDraft] = useState<number | null>(
    project.assignment?.teamId ?? null,
  );
  const [assignmentPmUserIdDraft, setAssignmentPmUserIdDraft] = useState<number | null>(
    project.assignment?.assignedPmUserId ?? null,
  );
  const [assignmentTranslatorUserIdDraft, setAssignmentTranslatorUserIdDraft] = useState<
    number | null
  >(project.assignment?.assignedTranslatorUserId ?? null);
  const [assignmentNoteDraft, setAssignmentNoteDraft] = useState('');
  const [assignmentSaveStatus, setAssignmentSaveStatus] = useState<string | null>(null);
  const [assignmentSaveError, setAssignmentSaveError] = useState<string | null>(null);
  const actionClass =
    status === 'OPEN'
      ? 'review-project-page__header-action--close'
      : 'review-project-page__header-action--reopen';
  const navigate = useNavigate();
  const shouldReturnToReviewProjects = requestDetailsSource === 'list';
  const backToReviewProjects = useMemo(() => {
    if (!reviewProjectsSessionKey) {
      return '/review-projects';
    }
    const params = new URLSearchParams();
    params.set(REVIEW_PROJECTS_SESSION_QUERY_KEY, reviewProjectsSessionKey);
    return `/review-projects?${params.toString()}`;
  }, [reviewProjectsSessionKey]);

  // Assignment editing is moving to the review-projects request view.
  // Keeping it off here avoids project-level PM edits, which conflict with the request-level PM model.
  const canEditAssignment = false;
  const teamsQuery = useQuery<ApiTeam[]>({
    queryKey: ['teams', 'review-project-assignment'],
    queryFn: fetchTeams,
    enabled: canEditAssignment && showDescription,
    staleTime: 30_000,
  });
  const assignmentUsersQuery = useQuery({
    queryKey: ['users', 'admin', 'review-project-assignment'],
    queryFn: fetchAllUsersAdmin,
    enabled: canEditAssignment && showDescription,
    staleTime: 30_000,
  });
  const assignmentPmPoolQuery = useQuery({
    queryKey: ['team-pm-pool', assignmentTeamIdDraft, 'review-project-assignment'],
    queryFn: () => fetchTeamPmPool(assignmentTeamIdDraft as number),
    enabled: canEditAssignment && showDescription && assignmentTeamIdDraft != null,
    staleTime: 30_000,
  });
  const assignmentTranslatorsQuery = useQuery({
    queryKey: ['team-translators', assignmentTeamIdDraft, 'review-project-assignment'],
    queryFn: () => fetchTeamTranslators(assignmentTeamIdDraft as number),
    enabled: canEditAssignment && showDescription && assignmentTeamIdDraft != null,
    staleTime: 30_000,
  });
  const assignmentLocalePoolsQuery = useQuery({
    queryKey: ['team-locale-pools', assignmentTeamIdDraft, 'review-project-assignment'],
    queryFn: () => fetchTeamLocalePools(assignmentTeamIdDraft as number),
    enabled: canEditAssignment && showDescription && assignmentTeamIdDraft != null,
    staleTime: 30_000,
  });

  const { selectedCount, decidedCount, pendingCount, progressPercent, progressTitle } =
    useMemo(() => {
      const selected = textUnits?.length ?? 0;
      const decided = textUnits?.filter((tu) => getDecisionState(tu) === 'DECIDED').length ?? 0;
      const pending = Math.max(0, selected - decided);
      const percent = selected > 0 ? Math.round((decided / selected) * 100) : 0;
      const title = selected > 0 ? `${decided}/${selected}` : 'No text units';
      return {
        selectedCount: selected,
        decidedCount: decided,
        pendingCount: pending,
        progressPercent: percent,
        progressTitle: title,
      };
    }, [textUnits]);

  const handleProjectAction = useCallback(() => {
    if (mutations.isProjectStatusSaving) {
      return;
    }
    if (status === 'OPEN' && pendingCount > 0) {
      setShowCloseWarning(true);
      return;
    }
    mutations.onRequestProjectStatus(nextStatus);
  }, [mutations, nextStatus, pendingCount, status]);

  const confirmCloseProject = useCallback(() => {
    setShowCloseWarning(false);
    mutations.onRequestProjectStatus('CLOSED');
  }, [mutations]);

  const dismissCloseWarning = useCallback(() => {
    setShowCloseWarning(false);
  }, []);

  useEffect(() => {
    if (!showDescription) {
      return;
    }
    setRequestNameDraft(name ?? '');
    setDescriptionDraft(description);
    setProjectTypeDraft(type);
    setDueDateDraft(toDateTimeLocalInputValue(dueDate));
    setAttachmentDrafts(requestAttachments);
    setAttachmentUploadQueue([]);
    setIsAttachmentUploading(false);
    setAttachmentUploadError(null);
    setRequestSaveError(null);
    setAssignmentTeamIdDraft(project.assignment?.teamId ?? null);
    setAssignmentPmUserIdDraft(project.assignment?.assignedPmUserId ?? null);
    setAssignmentTranslatorUserIdDraft(project.assignment?.assignedTranslatorUserId ?? null);
    setAssignmentNoteDraft('');
    setAssignmentSaveStatus(null);
    setAssignmentSaveError(null);
  }, [
    description,
    dueDate,
    name,
    project.assignment?.assignedPmUserId,
    project.assignment?.assignedTranslatorUserId,
    project.assignment?.teamId,
    requestAttachments,
    showDescription,
    type,
  ]);

  const assignmentUsersById = useMemo(() => {
    const map = new Map<number, ApiUser>();
    (assignmentUsersQuery.data ?? []).forEach((entry) => {
      map.set(entry.id, entry);
    });
    return map;
  }, [assignmentUsersQuery.data]);

  const assignmentTeamOptions = useMemo(
    () =>
      (teamsQuery.data ?? []).map((team) => ({
        value: team.id,
        label: `${team.name} (#${team.id})`,
      })),
    [teamsQuery.data],
  );

  const projectLocaleTagLower = (project.locale?.bcp47Tag ?? '').trim().toLowerCase();

  const assignmentPmOptions = useMemo(() => {
    const orderedIds = assignmentPmPoolQuery.data?.userIds ?? [];
    const seen = new Set<number>();
    const options = orderedIds
      .filter((id): id is number => Number.isInteger(id) && id > 0)
      .filter((id) => {
        if (seen.has(id)) {
          return false;
        }
        seen.add(id);
        return true;
      })
      .map((id) => {
        const user = assignmentUsersById.get(id);
        return {
          value: id,
          label: user?.commonName
            ? `${user.commonName} (${user.username})`
            : (user?.username ?? `User #${id}`),
        };
      });
    if (
      assignmentPmUserIdDraft != null &&
      !options.some((option) => option.value === assignmentPmUserIdDraft)
    ) {
      const user = assignmentUsersById.get(assignmentPmUserIdDraft);
      options.unshift({
        value: assignmentPmUserIdDraft,
        label: user?.username ?? `User #${assignmentPmUserIdDraft}`,
      });
    }
    return options;
  }, [assignmentPmPoolQuery.data?.userIds, assignmentPmUserIdDraft, assignmentUsersById]);

  const assignmentTranslatorOptions = useMemo(() => {
    const localePoolRow =
      assignmentLocalePoolsQuery.data?.entries.find(
        (entry) => entry.localeTag.trim().toLowerCase() === projectLocaleTagLower,
      ) ?? null;
    const sourceIds =
      (localePoolRow?.translatorUserIds?.length ?? 0) > 0
        ? (localePoolRow?.translatorUserIds ?? [])
        : (assignmentTranslatorsQuery.data?.userIds ?? []);
    const seen = new Set<number>();
    const options = sourceIds
      .filter((id): id is number => Number.isInteger(id) && id > 0)
      .filter((id) => {
        if (seen.has(id)) {
          return false;
        }
        seen.add(id);
        return true;
      })
      .map((id) => {
        const user = assignmentUsersById.get(id);
        return {
          value: id,
          label: user?.commonName
            ? `${user.commonName} (${user.username})`
            : (user?.username ?? `User #${id}`),
        };
      });
    if (
      assignmentTranslatorUserIdDraft != null &&
      !options.some((option) => option.value === assignmentTranslatorUserIdDraft)
    ) {
      const user = assignmentUsersById.get(assignmentTranslatorUserIdDraft);
      options.unshift({
        value: assignmentTranslatorUserIdDraft,
        label: user?.username ?? `User #${assignmentTranslatorUserIdDraft}`,
      });
    }
    return options;
  }, [
    assignmentLocalePoolsQuery.data?.entries,
    assignmentTranslatorsQuery.data?.userIds,
    assignmentTranslatorUserIdDraft,
    assignmentUsersById,
    projectLocaleTagLower,
  ]);

  const isAssignmentDirty =
    assignmentTeamIdDraft !== (project.assignment?.teamId ?? null) ||
    assignmentPmUserIdDraft !== (project.assignment?.assignedPmUserId ?? null) ||
    assignmentTranslatorUserIdDraft !== (project.assignment?.assignedTranslatorUserId ?? null) ||
    assignmentNoteDraft.trim().length > 0;

  useEffect(() => {
    const nextUrls = new Set(
      attachmentUploadQueue
        .map((item) => item.preview)
        .filter((preview): preview is string => typeof preview === 'string' && preview.length > 0),
    );
    revokeRequestAttachmentUploadQueuePreviews(
      Array.from(attachmentUploadPreviewUrlsRef.current)
        .filter((url) => !nextUrls.has(url))
        .map((preview) => ({ preview })),
    );
    attachmentUploadPreviewUrlsRef.current = nextUrls;
  }, [attachmentUploadQueue]);

  useEffect(() => {
    return () => {
      revokeRequestAttachmentUploadQueuePreviews(
        Array.from(attachmentUploadPreviewUrlsRef.current).map((preview) => ({ preview })),
      );
      attachmentUploadPreviewUrlsRef.current.clear();
    };
  }, []);

  useEffect(() => {
    if (!openRequestDetailsQuery) {
      return;
    }
    if (requestId != null) {
      setShowDescription(true);
    }
    onRequestDetailsQueryHandled();
  }, [onRequestDetailsQueryHandled, openRequestDetailsQuery, requestId]);

  useEffect(() => {
    if (assignmentPmUserIdDraft == null) {
      return;
    }
    if (assignmentPmOptions.some((option) => option.value === assignmentPmUserIdDraft)) {
      return;
    }
    setAssignmentPmUserIdDraft(null);
  }, [assignmentPmOptions, assignmentPmUserIdDraft]);

  useEffect(() => {
    if (assignmentTranslatorUserIdDraft == null) {
      return;
    }
    if (
      assignmentTranslatorOptions.some((option) => option.value === assignmentTranslatorUserIdDraft)
    ) {
      return;
    }
    setAssignmentTranslatorUserIdDraft(null);
  }, [assignmentTranslatorOptions, assignmentTranslatorUserIdDraft]);

  const closeDescriptionModal = useCallback(() => {
    if (mutations.isProjectRequestSaving || isAttachmentUploading) {
      return;
    }
    onRequestDetailsFlowFinished();
    if (shouldReturnToReviewProjects) {
      void navigate(backToReviewProjects);
      return;
    }
    setShowDescription(false);
    setAttachmentUploadError(null);
    setRequestSaveError(null);
  }, [
    backToReviewProjects,
    isAttachmentUploading,
    mutations.isProjectRequestSaving,
    navigate,
    onRequestDetailsFlowFinished,
    shouldReturnToReviewProjects,
  ]);

  const addAttachmentKeys = useCallback((raw: string[]) => {
    const next = raw
      .map((item) => item.trim())
      .filter(Boolean)
      .map((item) => item.slice(0, 255));
    if (!next.length) {
      return;
    }
    setAttachmentDrafts((current) => {
      const set = new Set(current.map((key) => key.toLowerCase()));
      const merged = [...current];
      next.forEach((key) => {
        if (!set.has(key.toLowerCase())) {
          merged.push(key);
          set.add(key.toLowerCase());
        }
      });
      return merged;
    });
  }, []);

  const handleAttachmentFiles = useCallback(
    async (files: FileList | null): Promise<string[]> => {
      if (
        !files ||
        files.length === 0 ||
        mutations.isProjectRequestSaving ||
        isAttachmentUploading
      ) {
        return [];
      }
      setAttachmentUploadError(null);
      setIsAttachmentUploading(true);

      const fileList = Array.from(files);
      const queueEntries = buildRequestAttachmentUploadQueueEntries(fileList);
      setAttachmentUploadQueue((current) => [...queueEntries, ...current]);

      const uploaded: string[] = [];
      const failed: string[] = [];

      for (const [index, file] of fileList.entries()) {
        const queueEntry = queueEntries[index];
        if (!queueEntry || !isSupportedRequestAttachmentFile(file)) {
          if (!isSupportedRequestAttachmentFile(file)) {
            failed.push(`Unsupported file type: ${file.name}`);
          }
          continue;
        }
        try {
          const key = await uploadRequestAttachmentFile(file);
          uploaded.push(key);
          setAttachmentUploadQueue((current) =>
            current.filter((item) => item.key !== queueEntry.key),
          );
        } catch (error) {
          const message = error instanceof Error ? error.message : `Failed to upload ${file.name}`;
          failed.push(message);
          setAttachmentUploadQueue((current) =>
            current.map((item) =>
              item.key === queueEntry.key ? { ...item, status: 'error', error: message } : item,
            ),
          );
        }
      }

      if (uploaded.length > 0) {
        addAttachmentKeys(uploaded);
      }
      if (failed.length > 0) {
        setAttachmentUploadError(failed.join('; '));
      }
      setIsAttachmentUploading(false);
      return uploaded;
    },
    [addAttachmentKeys, isAttachmentUploading, mutations.isProjectRequestSaving],
  );
  const attachmentsDisabled =
    !canEditRequest || mutations.isProjectRequestSaving || isAttachmentUploading;

  const saveRequestDetails = useCallback(async () => {
    if (!canEditRequest) {
      return;
    }
    if (isAttachmentUploading) {
      setRequestSaveError('Please wait for uploads to finish.');
      return;
    }
    if (requestId == null) {
      setRequestSaveError('This review project has no request to update.');
      return;
    }
    const trimmedName = requestNameDraft.trim();
    if (!trimmedName) {
      setRequestSaveError('Name is required.');
      return;
    }
    if (!dueDateDraft) {
      setRequestSaveError('Due date is required.');
      return;
    }
    const dueDateParsed = new Date(dueDateDraft);
    if (Number.isNaN(dueDateParsed.getTime())) {
      setRequestSaveError('Due date is invalid.');
      return;
    }
    const dueDateIso = dueDateParsed.toISOString();

    try {
      setRequestSaveError(null);
      await mutations.onRequestProjectRequestUpdate({
        name: trimmedName,
        notes: descriptionDraft,
        type: projectTypeDraft,
        dueDate: dueDateIso,
        screenshotImageIds: attachmentDrafts,
      });
      onRequestDetailsFlowFinished();
      if (shouldReturnToReviewProjects) {
        void navigate(backToReviewProjects);
        return;
      }
      setShowDescription(false);
    } catch (error) {
      setRequestSaveError(
        error instanceof Error ? error.message : 'Failed to update request details.',
      );
    }
  }, [
    attachmentDrafts,
    backToReviewProjects,
    canEditRequest,
    descriptionDraft,
    dueDateDraft,
    isAttachmentUploading,
    mutations,
    navigate,
    onRequestDetailsFlowFinished,
    projectTypeDraft,
    requestId,
    requestNameDraft,
    shouldReturnToReviewProjects,
  ]);

  const saveAssignmentDetails = useCallback(async () => {
    if (!canEditAssignment) {
      return;
    }
    try {
      setAssignmentSaveError(null);
      setAssignmentSaveStatus(null);
      await mutations.onRequestProjectAssignmentUpdate({
        teamId: assignmentTeamIdDraft,
        assignedPmUserId: assignmentPmUserIdDraft,
        assignedTranslatorUserId: assignmentTranslatorUserIdDraft,
        note: assignmentNoteDraft.trim() || null,
      });
      setAssignmentNoteDraft('');
      setAssignmentSaveStatus('Saved. Team Slack notification is attempted when configured.');
    } catch (error) {
      setAssignmentSaveError(
        error instanceof Error ? error.message : 'Failed to update project assignment.',
      );
    }
  }, [
    assignmentNoteDraft,
    assignmentPmUserIdDraft,
    assignmentTeamIdDraft,
    assignmentTranslatorUserIdDraft,
    canEditAssignment,
    mutations,
  ]);

  const handleReviewPending = useCallback(() => {
    setShowCloseWarning(false);
    onReviewPending();
  }, [onReviewPending]);

  return (
    <>
      <header className="review-project-page__header">
        <div className="review-project-page__header-row">
          <div className="review-project-page__header-group review-project-page__header-group--left">
            <Link
              className="review-project-page__header-back-link"
              to={backToReviewProjects}
              aria-label="Back to review projects"
              title="Back to review projects"
            >
              <svg
                className="review-project-page__header-back-icon"
                viewBox="0 0 24 24"
                aria-hidden="true"
                focusable="false"
              >
                <path
                  d="M20 12H6m0 0l5-5m-5 5l5 5"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            </Link>
            <span className="review-project-page__header-name">
              {name ?? `Project ${projectId}`}
            </span>
            <span className="review-project-page__header-pills">
              <Pill
                className={`review-project-page__header-pill review-project-page__header-pill--type-${type}`}
              >
                {REVIEW_PROJECT_TYPE_LABELS[type]}
              </Pill>
            </span>
            <div className="review-project-page__header-locale-row">
              {locales.length > 0 ? (
                locales.map((locale) => {
                  const tag = locale.bcp47Tag ?? '';
                  return (
                    <LocalePill
                      key={String(locale.id ?? (tag || 'unknown-locale'))}
                      bcp47Tag={tag}
                      displayName={tag}
                      labelMode="tag"
                      className="review-project-page__header-locale-pill"
                    />
                  );
                })
              ) : (
                <span className="review-project-page__header-muted">No locale</span>
              )}
            </div>
          </div>

          <div className="review-project-page__header-group review-project-page__header-group--stats">
            <CountsInline words={wordCount} strings={textUnitCount ?? selectedCount} />
            <span className="review-project-page__header-dot">•</span>
            <div className="review-project-page__header-progress">
              <span className="review-project-page__header-progress-label" title={progressTitle}>
                {progressPercent}%
              </span>
              <ProgressBar percent={progressPercent} title={progressTitle} />
            </div>
          </div>

          <div className="review-project-page__header-group review-project-page__header-group--meta">
            {requestId != null ? (
              <button
                type="button"
                className="review-project-page__header-link"
                onClick={() => setShowDescription(true)}
                aria-label={canEditRequest ? 'Edit request details' : 'View request details'}
              >
                Request details
              </button>
            ) : null}
            {requestId != null ? (
              <span className="review-project-page__header-dot" aria-hidden>
                •
              </span>
            ) : null}
            <span>Due {formatDate(dueDate)}</span>
            <button
              type="button"
              className="review-project-page__header-help"
              onClick={(event) => {
                event.stopPropagation();
                onOpenShortcuts();
              }}
              aria-label="Keyboard shortcuts"
              title="Keyboard shortcuts"
            >
              <span aria-hidden="true">?</span>
            </button>
            <button
              type="button"
              className={`review-project-page__header-action ${actionClass}`}
              onClick={handleProjectAction}
              disabled={mutations.isProjectStatusSaving}
            >
              {mutations.isProjectStatusSaving ? 'Saving…' : actionLabel}
            </button>
          </div>
        </div>
      </header>
      <Modal
        open={showCloseWarning}
        size="md"
        role="alertdialog"
        ariaLabel="Close with pending items?"
      >
        <div className="modal__title">Close with pending items?</div>
        <div className="modal__body">
          {pendingCount} text unit{pendingCount === 1 ? '' : 's'} still{' '}
          {pendingCount === 1 ? 'needs' : 'need'} a decision ({decidedCount}/{selectedCount}{' '}
          decided). Close project anyway?
        </div>
        <div className="modal__actions">
          <button type="button" className="modal__button" onClick={dismissCloseWarning}>
            Keep open
          </button>
          <button
            type="button"
            className="modal__button modal__button--primary"
            onClick={handleReviewPending}
          >
            Review pending
          </button>
          <button
            type="button"
            className="modal__button modal__button--danger"
            onClick={confirmCloseProject}
          >
            Close project
          </button>
        </div>
      </Modal>
      {showDescription ? (
        <section
          className="review-project-page__description-screen"
          role="dialog"
          aria-modal="true"
          aria-label={canEditRequest ? 'Edit request details' : 'Request details'}
        >
          <div className="review-project-page review-project-page__description-screen-page">
            <header className="review-project-page__header">
              <div className="review-project-page__header-row">
                <div className="review-project-page__header-group review-project-page__header-group--left">
                  <button
                    type="button"
                    className="review-project-page__header-back-link"
                    onClick={closeDescriptionModal}
                    aria-label="Back to review project"
                    title="Back to review project"
                    disabled={mutations.isProjectRequestSaving || isAttachmentUploading}
                  >
                    <svg
                      className="review-project-page__header-back-icon"
                      viewBox="0 0 24 24"
                      aria-hidden="true"
                      focusable="false"
                    >
                      <path
                        d="M20 12H6m0 0l5-5m-5 5l5 5"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                      />
                    </svg>
                  </button>
                  <span className="review-project-page__header-name">
                    {canEditRequest ? 'Edit request details' : 'Request details'}
                  </span>
                </div>
              </div>
            </header>
            <div className="review-project-page__description-screen-body">
              <div className="review-project-page__description-screen-content review-project-page__description-modal-body">
                <label className="review-project-page__description-field">
                  <span className="review-project-page__description-label">Name</span>
                  <input
                    className="review-project-page__description-input"
                    type="text"
                    value={requestNameDraft}
                    onChange={(event) => setRequestNameDraft(event.target.value)}
                    disabled={!canEditRequest || mutations.isProjectRequestSaving}
                    placeholder="Request name"
                  />
                </label>
                <div className="review-project-page__description-two-up">
                  <label className="review-project-page__description-field">
                    <span className="review-project-page__description-label">Type</span>
                    <SingleSelectDropdown
                      label="Type"
                      className="review-project-page__description-select"
                      options={REVIEW_PROJECT_TYPES.filter((option) => option !== 'UNKNOWN').map(
                        (option) => ({
                          value: option,
                          label: REVIEW_PROJECT_TYPE_LABELS[option],
                        }),
                      )}
                      value={projectTypeDraft}
                      onChange={(next) => {
                        if (next == null) {
                          return;
                        }
                        setProjectTypeDraft(next);
                      }}
                      disabled={
                        !canEditRequest || mutations.isProjectRequestSaving || isAttachmentUploading
                      }
                      searchable={false}
                    />
                  </label>
                  <label className="review-project-page__description-field">
                    <span className="review-project-page__description-label">Due date</span>
                    <input
                      className="review-project-page__description-input"
                      type="datetime-local"
                      value={dueDateDraft}
                      onChange={(event) => setDueDateDraft(event.target.value)}
                      disabled={
                        !canEditRequest || mutations.isProjectRequestSaving || isAttachmentUploading
                      }
                    />
                  </label>
                </div>
                <div className="review-project-page__description-two-up">
                  <label className="review-project-page__description-field">
                    <span className="review-project-page__description-label">Created by</span>
                    <input
                      className="review-project-page__description-input"
                      type="text"
                      value={requestCreatedBy ?? '—'}
                      readOnly
                      disabled
                    />
                  </label>
                  <label className="review-project-page__description-field">
                    <span className="review-project-page__description-label">Created</span>
                    <input
                      className="review-project-page__description-input"
                      type="text"
                      value={formatDateTime(requestCreatedDate)}
                      readOnly
                      disabled
                    />
                  </label>
                </div>
                {canEditAssignment ? (
                  <div className="review-project-page__description-assignment">
                    <div className="review-project-page__description-assignment-header">
                      Assignment
                    </div>
                    <div className="review-project-page__description-two-up">
                      <label className="review-project-page__description-field">
                        <span className="review-project-page__description-label">Team</span>
                        <SingleSelectDropdown
                          label="Team"
                          className="review-project-page__description-select"
                          options={assignmentTeamOptions}
                          value={assignmentTeamIdDraft}
                          onChange={(next) => {
                            setAssignmentTeamIdDraft(next);
                            setAssignmentPmUserIdDraft(null);
                            setAssignmentTranslatorUserIdDraft(null);
                            setAssignmentSaveStatus(null);
                            setAssignmentSaveError(null);
                          }}
                          noneLabel="No team"
                          placeholder="No team"
                          disabled={mutations.isProjectAssignmentSaving || teamsQuery.isLoading}
                          buttonAriaLabel="Select team assignment"
                        />
                      </label>
                      <label className="review-project-page__description-field">
                        <span className="review-project-page__description-label">
                          Assignment note
                        </span>
                        <input
                          className="review-project-page__description-input"
                          type="text"
                          value={assignmentNoteDraft}
                          onChange={(event) => {
                            setAssignmentNoteDraft(event.target.value);
                            setAssignmentSaveStatus(null);
                            setAssignmentSaveError(null);
                          }}
                          placeholder="Optional note for assignment history / notification"
                          disabled={mutations.isProjectAssignmentSaving}
                        />
                      </label>
                    </div>
                    <div className="review-project-page__description-two-up">
                      <label className="review-project-page__description-field">
                        <span className="review-project-page__description-label">PM</span>
                        <SingleSelectDropdown
                          label="PM"
                          className="review-project-page__description-select"
                          options={assignmentPmOptions}
                          value={assignmentPmUserIdDraft}
                          onChange={(next) => {
                            setAssignmentPmUserIdDraft(next);
                            setAssignmentSaveStatus(null);
                            setAssignmentSaveError(null);
                          }}
                          noneLabel="No PM"
                          placeholder={
                            assignmentTeamIdDraft == null ? 'Select a team first' : 'No PM'
                          }
                          disabled={
                            assignmentTeamIdDraft == null ||
                            mutations.isProjectAssignmentSaving ||
                            assignmentPmPoolQuery.isLoading
                          }
                          buttonAriaLabel="Select assigned PM"
                        />
                      </label>
                      <label className="review-project-page__description-field">
                        <span className="review-project-page__description-label">Translator</span>
                        <SingleSelectDropdown
                          label="Translator"
                          className="review-project-page__description-select"
                          options={assignmentTranslatorOptions}
                          value={assignmentTranslatorUserIdDraft}
                          onChange={(next) => {
                            setAssignmentTranslatorUserIdDraft(next);
                            setAssignmentSaveStatus(null);
                            setAssignmentSaveError(null);
                          }}
                          noneLabel="No translator"
                          placeholder={
                            assignmentTeamIdDraft == null ? 'Select a team first' : 'No translator'
                          }
                          disabled={
                            assignmentTeamIdDraft == null ||
                            mutations.isProjectAssignmentSaving ||
                            assignmentLocalePoolsQuery.isLoading ||
                            assignmentTranslatorsQuery.isLoading
                          }
                          buttonAriaLabel="Select assigned translator"
                        />
                      </label>
                    </div>
                    <div className="review-project-page__description-assignment-help">
                      Saving assignment posts a Slack notification to the selected team channel when
                      configured.
                    </div>
                    {teamsQuery.isError ||
                    assignmentUsersQuery.isError ||
                    assignmentPmPoolQuery.isError ||
                    assignmentLocalePoolsQuery.isError ||
                    assignmentTranslatorsQuery.isError ? (
                      <div className="review-project-page__description-error">
                        Failed to load team assignment options.
                      </div>
                    ) : null}
                    {assignmentSaveError ? (
                      <div className="review-project-page__description-error">
                        {assignmentSaveError}
                      </div>
                    ) : null}
                    {assignmentSaveStatus ? (
                      <div className="review-project-page__description-info">
                        {assignmentSaveStatus}
                      </div>
                    ) : null}
                    <div className="review-project-page__description-actions review-project-page__description-actions--assignment">
                      {isAssignmentDirty ? (
                        <button
                          type="button"
                          className="review-project-detail__actions-button review-project-detail__actions-button--primary"
                          onClick={() => {
                            void saveAssignmentDetails();
                          }}
                          disabled={
                            mutations.isProjectAssignmentSaving ||
                            teamsQuery.isLoading ||
                            assignmentUsersQuery.isLoading
                          }
                        >
                          {mutations.isProjectAssignmentSaving ? 'Saving…' : 'Save assignment'}
                        </button>
                      ) : null}
                    </div>
                  </div>
                ) : null}
                <div className="review-project-page__description-field">
                  <RequestDescriptionEditor
                    value={descriptionDraft}
                    onChange={setDescriptionDraft}
                    canEdit={canEditRequest}
                    disabled={mutations.isProjectRequestSaving}
                    onDropFiles={async (files) => {
                      const uploadedKeys = await handleAttachmentFiles(files);
                      if (uploadedKeys.length === 0) {
                        return null;
                      }
                      const snippets = uploadedKeys.map((key) =>
                        toDescriptionAttachmentMarkdown(key),
                      );
                      return `${snippets.join('\n')}\n`;
                    }}
                  />
                </div>
                <div className="review-project-page__description-field">
                  <RequestAttachmentsDropzone
                    keys={attachmentDrafts}
                    uploadQueue={attachmentUploadQueue}
                    disabled={attachmentsDisabled}
                    isUploading={isAttachmentUploading}
                    onFilesSelected={async (files) => {
                      await handleAttachmentFiles(files);
                    }}
                    onRemoveKey={(key) =>
                      setAttachmentDrafts((current) => current.filter((value) => value !== key))
                    }
                  />
                </div>
                {attachmentUploadError ? (
                  <div className="review-project-page__description-error">
                    {attachmentUploadError}
                  </div>
                ) : null}
                {requestSaveError ? (
                  <div className="review-project-page__description-error">{requestSaveError}</div>
                ) : null}
                <div className="review-project-page__description-actions">
                  {canEditRequest ? (
                    <>
                      <button
                        type="button"
                        className="review-project-detail__actions-button"
                        onClick={closeDescriptionModal}
                        disabled={mutations.isProjectRequestSaving || isAttachmentUploading}
                      >
                        Cancel
                      </button>
                      <button
                        type="button"
                        className="review-project-detail__actions-button review-project-detail__actions-button--primary"
                        onClick={() => {
                          void saveRequestDetails();
                        }}
                        disabled={mutations.isProjectRequestSaving || isAttachmentUploading}
                      >
                        {mutations.isProjectRequestSaving ? 'Saving…' : 'Save'}
                      </button>
                    </>
                  ) : (
                    <button
                      type="button"
                      className="review-project-detail__actions-button"
                      onClick={closeDescriptionModal}
                    >
                      Close
                    </button>
                  )}
                </div>
              </div>
            </div>
          </div>
        </section>
      ) : null}
    </>
  );
}

function CountsInline({
  words,
  strings,
}: {
  words: number | null | undefined;
  strings: number | null | undefined;
}) {
  return (
    <span className="review-project-page__header-count-line">
      <span className="review-project-page__header-count">{formatNumber(words)}</span>
      <span className="review-project-page__header-muted">&nbsp;words</span>
      <span className="review-project-page__header-count-sep">&nbsp;·&nbsp;</span>
      <span className="review-project-page__header-count">{formatNumber(strings)}</span>
      <span className="review-project-page__header-muted">&nbsp;strings</span>
    </span>
  );
}

function ProgressBar({ percent, title }: { percent: number; title?: string }) {
  return (
    <div className="review-project-page__header-progress-bar" title={title}>
      <div
        className="review-project-page__header-progress-fill"
        style={{ width: `${Math.min(Math.max(percent, 0), 100)}%` }}
      />
    </div>
  );
}

const formatNumber = (value: number | null | undefined) => {
  if (value == null) {
    return '—';
  }
  return value.toLocaleString();
};

const formatDate = (value: string | null | undefined) => {
  if (!value) {
    return '—';
  }
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }
  return parsed.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
};

const formatDateTime = (value: string | null | undefined) => {
  if (!value) {
    return '—';
  }
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }
  return parsed.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
};

const toDateTimeLocalInputValue = (value: string | null | undefined) => {
  if (!value) {
    return '';
  }
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return '';
  }
  const pad = (num: number) => String(num).padStart(2, '0');
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}T${pad(
    parsed.getHours(),
  )}:${pad(parsed.getMinutes())}`;
};

const toAttachmentLabel = (key: string) => {
  const withoutQuery = key.split('?')[0];
  const ext = withoutQuery.includes('.') ? (withoutQuery.split('.').pop() ?? '') : '';
  return ext ? ext.toUpperCase() : 'FILE';
};

type MediaRenderOptions = {
  controls?: boolean;
  muted?: boolean;
  loop?: boolean;
  preload?: 'none' | 'metadata' | 'auto';
  onClick?: () => void;
  ariaLabel?: string;
  onLoad?: () => void;
  onLoadedMetadata?: () => void;
  asThumbnail?: boolean;
};

const renderMedia = (key: string, className?: string, options: MediaRenderOptions = {}) => {
  const url = resolveAttachmentUrl(key);
  const baseClass = className ? `${className} review-project-media` : 'review-project-media';
  const handleKeyDown = (event: React.KeyboardEvent<HTMLElement>) => {
    if (!options.onClick) {
      return;
    }
    if (event.key !== 'Enter' && event.key !== ' ') {
      return;
    }
    event.preventDefault();
    options.onClick();
  };
  const interactiveProps = options.onClick
    ? {
        onClick: options.onClick,
        role: 'button' as const,
        tabIndex: 0,
        onKeyDown: handleKeyDown,
        'aria-label': options.ariaLabel ?? 'Open media',
      }
    : {};
  if (isVideoAttachmentKey(key)) {
    return (
      <video
        key={url}
        className={`${baseClass} review-project-media--video`}
        src={url}
        controls={options.controls ?? true}
        muted={options.muted ?? false}
        loop={options.loop ?? false}
        playsInline
        preload={options.preload ?? 'metadata'}
        onLoadedMetadata={options.onLoadedMetadata}
        {...interactiveProps}
      />
    );
  }
  if (isPdfAttachmentKey(key)) {
    if (options.asThumbnail) {
      return (
        <span className={`${baseClass} review-project-media review-project-media--file-thumb`}>
          PDF
        </span>
      );
    }

    return (
      <a
        key={url}
        href={url}
        target="_blank"
        rel="noreferrer"
        className={`${baseClass} review-project-media review-project-media--file`}
      >
        Open attachment (PDF)
      </a>
    );
  }
  if (!isImageAttachmentKey(key)) {
    if (options.asThumbnail) {
      return (
        <span className={`${baseClass} review-project-media review-project-media--file-thumb`}>
          {toAttachmentLabel(key)}
        </span>
      );
    }

    return (
      <a
        key={url}
        href={url}
        target="_blank"
        rel="noreferrer"
        className={`${baseClass} review-project-media review-project-media--file`}
      >
        Open attachment ({toAttachmentLabel(key)})
      </a>
    );
  }
  return (
    <img
      key={url}
      className={baseClass}
      src={url}
      alt=""
      loading="lazy"
      onLoad={options.onLoad}
      {...interactiveProps}
    />
  );
};

const renderThumbMedia = (key: string) =>
  renderMedia(key, 'review-project-detail__thumb-media', {
    controls: false,
    muted: true,
    loop: true,
    preload: 'metadata',
    asThumbnail: true,
  });
