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
  fetchPrecomputedAiReview,
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
import type {
  ApiReviewProjectAssignmentHistoryEntry,
  ApiReviewProjectDetail,
  ApiReviewProjectTerminologyMetadataRequest,
  ApiReviewProjectTerminologyPhase,
  ApiReviewProjectTerminologyTerm,
  ApiReviewProjectTextUnit,
  ApiReviewProjectType,
  ApiTerminologyFeedbackRecommendation,
  ApiTerminologyResolutionStatus,
} from '../../api/review-projects';
import {
  fetchReviewProjectAssignmentHistory,
  isTerminologyReviewProjectType,
  REVIEW_PROJECT_TERMINOLOGY_PHASE_LABELS,
  REVIEW_PROJECT_TYPE_LABELS,
  REVIEW_PROJECT_TYPES,
  TERMINOLOGY_FEEDBACK_RECOMMENDATION_LABELS,
  TERMINOLOGY_RESOLUTION_STATUS_LABELS,
} from '../../api/review-projects';
import {
  type ApiTeam,
  type ApiTeamUserSummary,
  fetchTeams,
  fetchTeamUsersByRole,
} from '../../api/teams';
import {
  type ApiAiTranslateTextUnitAttempt,
  type ApiTextUnitHistoryItem,
  fetchAiTranslateTextUnitAttempts,
  fetchTextUnitHistory,
} from '../../api/text-units';
import { AiChatReview, type AiChatReviewMessage } from '../../components/AiChatReview';
import { AutoTextarea } from '../../components/AutoTextarea';
import { ConfirmModal } from '../../components/ConfirmModal';
import {
  type FilterOption,
  MultiSectionFilterChip,
} from '../../components/filters/MultiSectionFilterChip';
import { GlossaryMatchesPanel } from '../../components/GlossaryMatchesPanel';
import { IcuPreviewSection } from '../../components/IcuPreviewSection';
import { IntegrityCheckAlertModal } from '../../components/IntegrityCheckAlertModal';
import { LocalePill } from '../../components/LocalePill';
import { Modal } from '../../components/Modal';
import { Pill } from '../../components/Pill';
import { PillDropdown } from '../../components/PillDropdown';
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
import {
  TranslationTextEditor,
  type TranslationTextEditorKeyDownEvent,
} from '../../components/TranslationTextEditor';
import { getRowHeightPx } from '../../components/virtual/getRowHeightPx';
import { useVirtualRows } from '../../components/virtual/useVirtualRows';
import { VirtualList } from '../../components/virtual/VirtualList';
import type {
  VisibleTextEditorHandle,
  VisibleTextMarksMode,
} from '../../components/VisibleTextEditor';
import { useProtectedTextTokenGuard } from '../../hooks/useProtectedTextTokenGuard';
import { useUser } from '../../hooks/useUser';
import { useVisibleTextEditorEnabled } from '../../hooks/useVisibleTextEditorEnabled';
import { buildAiTranslateAttemptTimelineData } from '../../utils/aiTranslateHistory';
import {
  formatLocalDate as formatDate,
  formatLocalDateTime as formatDateTime,
  getLocalAndUtcDateTimeTooltip,
  localDateTimeInputToIso,
  toDateTimeLocalInputValue,
} from '../../utils/dateTime';
import {
  buildGlossaryContextMessage,
  filterSelfGlossaryMatches,
  sortGlossaryMatches,
} from '../../utils/glossary-matches';
import { getGlossaryTermScreenshotKeys } from '../../utils/glossaryTermEvidence';
import {
  findGlossaryTargetForTextUnit,
  findGlossaryTermByTmTextUnitId,
} from '../../utils/glossaryTermLookup';
import { hasIcuParameters } from '../../utils/icuPreview';
import { prepareDbBackedUploadFile } from '../../utils/image-upload-optimizer';
import { toHtmlLangTag } from '../../utils/localeTag';
import { canManageGlossaryTerms } from '../../utils/permissions';
import {
  buildRequestAttachmentUploadQueueEntries,
  canUploadRequestAttachmentFile,
  isImageAttachmentKey,
  isPdfAttachmentKey,
  isVideoAttachmentKey,
  resolveAttachmentUrl,
  revokeRequestAttachmentUploadQueuePreviews,
  toDescriptionAttachmentMarkdown,
  uploadRequestAttachmentFile,
} from '../../utils/request-attachments';
import {
  buildReviewProjectTextUnitUrl,
  buildTextUnitDetailUrl,
} from '../../utils/textUnitDetailUrl';
import { REVIEW_PROJECTS_SESSION_QUERY_KEY } from '../review-projects/review-projects-session-state';
import type { ReviewProjectMutationControls } from './review-project-mutations';
import {
  getDefaultReviewProjectShortcutHelpPreference,
  loadReviewProjectShortcutHelpPreference,
  REVIEW_PROJECT_SHORTCUT_HELP_KEY,
  saveReviewProjectShortcutHelpPreference,
} from './review-project-preferences';

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

const mergeScreenshotImageKeys = (...groups: string[][]) => {
  const seen = new Set<string>();
  const result: string[] = [];

  for (const key of groups.flat()) {
    const normalizedKey = key.trim();
    if (!normalizedKey || seen.has(normalizedKey)) {
      continue;
    }
    seen.add(normalizedKey);
    result.push(normalizedKey);
  }

  return result;
};

const getTextUnitWordCount = (textUnit: ApiReviewProjectTextUnit) => {
  const wordCount = textUnit.tmTextUnit?.wordCount;
  return typeof wordCount === 'number' && Number.isFinite(wordCount) && wordCount > 0
    ? wordCount
    : 0;
};

const sumTextUnitWords = (textUnits: ApiReviewProjectTextUnit[]) =>
  textUnits.reduce((sum, textUnit) => sum + getTextUnitWordCount(textUnit), 0);

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
type SortByFilter = 'none' | 'id' | 'source' | 'translation' | 'location' | 'assetPath';
type SortOrderFilter = 'asc' | 'desc';
type EditKind = 'translation' | 'status' | 'comment';
type TerminologyConfidenceChoice = 'unspecified' | '1' | '2' | '3' | '4' | '5';
type TerminologyResolutionStatusChoice = ApiTerminologyResolutionStatus;
type ContextTab = 'glossary' | 'icu' | 'history' | 'context';
type DetailEditorField = 'translation' | 'comment' | 'decisionNotes';

const SAVING_INDICATOR_DELAY_MS = 200;
const DEFAULT_AI_REVIEW_PROMPT = 'Review the translation and suggest improvements.';
const DEFAULT_TERMINOLOGY_CONFIDENCE: TerminologyConfidenceChoice = '5';
const TERMINOLOGY_FEEDBACK_RECOMMENDATION_OPTIONS: ApiTerminologyFeedbackRecommendation[] = [
  'APPROVE',
  'KEEP_CANDIDATE',
  'REJECT',
];
const TERMINOLOGY_CONFIDENCE_OPTIONS: Array<{
  value: Exclude<TerminologyConfidenceChoice, 'unspecified'>;
  label: string;
}> = [
  { value: '5', label: 'High' },
  { value: '3', label: 'Medium' },
  { value: '1', label: 'Low' },
];
const TERMINOLOGY_RESOLUTION_STATUS_OPTIONS: TerminologyResolutionStatusChoice[] = [
  'APPROVED',
  'CANDIDATE',
  'REJECTED',
];
const TERMINOLOGY_TERM_TYPES = ['BRAND', 'PRODUCT', 'UI_LABEL', 'LEGAL', 'TECHNICAL', 'GENERAL'];
const TERMINOLOGY_ENFORCEMENTS = ['HARD', 'SOFT', 'REVIEW_ONLY'];
const getErrorMessage = (error: unknown, fallback: string) =>
  error instanceof Error ? error.message : fallback;
const formatGlossaryMetadataValue = (value?: string | null) =>
  value?.trim() ? value.trim().toLowerCase().replace(/_/g, ' ') : null;

const normalizeTerminologyResolutionStatus = (
  value?: string | null,
): TerminologyResolutionStatusChoice =>
  TERMINOLOGY_RESOLUTION_STATUS_OPTIONS.includes(value as TerminologyResolutionStatusChoice)
    ? (value as TerminologyResolutionStatusChoice)
    : 'CANDIDATE';

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

function buildAssignmentTeamOptions(
  teams: ApiTeam[],
  currentTeamId: number | null | undefined,
  currentTeamName: string | null | undefined,
) {
  const options = teams.map((team) => ({
    value: team.id,
    label: `${team.name} (#${team.id})`,
  }));
  if (currentTeamId != null && !options.some((option) => option.value === currentTeamId)) {
    options.unshift({
      value: currentTeamId,
      label: `${currentTeamName?.trim() || 'Team'} (#${currentTeamId})`,
    });
  }
  return options;
}

function mergeTeamUsers(...groups: Array<ApiTeamUserSummary[] | undefined>) {
  const byId = new Map<number, ApiTeamUserSummary>();
  groups.forEach((users) => {
    (users ?? []).forEach((user) => {
      if (!byId.has(user.id)) {
        byId.set(user.id, user);
      }
    });
  });
  return Array.from(byId.values());
}

function buildAssignmentUserOptions(
  users: ApiTeamUserSummary[],
  currentUserId: number | null | undefined,
  currentUsername: string | null | undefined,
) {
  const options = users.map((user) => ({
    value: user.id,
    label: user.commonName ? `${user.commonName} (${user.username})` : user.username,
  }));
  if (currentUserId != null && !options.some((option) => option.value === currentUserId)) {
    options.unshift({
      value: currentUserId,
      label: currentUsername?.trim() || `User #${currentUserId}`,
    });
  }
  return options;
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

function getAssignmentHistoryEventLabel(
  eventType: ApiReviewProjectAssignmentHistoryEntry['eventType'],
) {
  switch (eventType) {
    case 'CREATED_DEFAULT':
      return 'Created';
    case 'ASSIGNED':
      return 'Assigned';
    case 'REASSIGNED':
      return 'Reassigned';
    case 'UNASSIGNED':
      return 'Unassigned';
    default:
      return eventType;
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

const SORT_BY_FILTER_OPTIONS: Array<FilterOption<SortByFilter>> = [
  { value: 'none', label: 'None' },
  { value: 'id', label: 'ID' },
  { value: 'source', label: 'Source' },
  { value: 'translation', label: 'Translation' },
  { value: 'location', label: 'Location' },
  { value: 'assetPath', label: 'Asset path' },
];

const SORT_ORDER_FILTER_OPTIONS: Array<FilterOption<SortOrderFilter>> = [
  { value: 'asc', label: 'Ascending' },
  { value: 'desc', label: 'Descending' },
];

const reviewProjectListSortCollator = new Intl.Collator(undefined, {
  numeric: true,
  sensitivity: 'base',
});

function compareNullableStrings(a?: string | null, b?: string | null): number {
  const left = (a ?? '').trim();
  const right = (b ?? '').trim();
  if (!left && !right) {
    return 0;
  }
  if (!left) {
    return 1;
  }
  if (!right) {
    return -1;
  }
  return reviewProjectListSortCollator.compare(left, right);
}

function getTextUnitSortStringValue(textUnit: ApiReviewProjectTextUnit, sortBy: SortByFilter) {
  switch (sortBy) {
    case 'source':
      return textUnit.tmTextUnit?.content;
    case 'translation':
      return getEffectiveVariant(textUnit)?.content;
    case 'location':
      return textUnit.tmTextUnit?.name != null ? String(textUnit.tmTextUnit.name) : null;
    case 'assetPath':
      return textUnit.tmTextUnit?.asset?.assetPath != null
        ? String(textUnit.tmTextUnit.asset.assetPath)
        : null;
    default:
      return null;
  }
}

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

function formatTabCount(count?: number): string | null {
  if (!count) {
    return null;
  }
  return count > 99 ? '99+' : String(count);
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

type TerminologyFeedbackSnapshot = {
  recommendation: ApiTerminologyFeedbackRecommendation | null;
  confidence: TerminologyConfidenceChoice;
  notes: string;
};

function buildTerminologyFeedbackSnapshot(
  textUnit: ApiReviewProjectTextUnit,
  username: string,
): TerminologyFeedbackSnapshot {
  const currentUserFeedback =
    textUnit.terminologyFeedbacks?.find((feedback) => feedback.reviewerUsername === username) ??
    null;
  const confidence =
    currentUserFeedback?.confidence != null &&
    currentUserFeedback.confidence >= 1 &&
    currentUserFeedback.confidence <= 5
      ? (String(currentUserFeedback.confidence) as TerminologyConfidenceChoice)
      : 'unspecified';

  return {
    recommendation: currentUserFeedback?.recommendation ?? null,
    confidence,
    notes: currentUserFeedback?.notes ?? '',
  };
}

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
  const defaultShortcutHelpPreference = getDefaultReviewProjectShortcutHelpPreference(user.role);
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
  const [sortByFilter, setSortByFilter] = useState<SortByFilter>('none');
  const [sortOrderFilter, setSortOrderFilter] = useState<SortOrderFilter>('asc');
  const [selectedTextUnitId, setSelectedTextUnitId] = useState<number | null>(null);
  const [detailIsDirty, setDetailIsDirty] = useState(false);
  const [focusTranslationKey, setFocusTranslationKey] = useState(0);
  const [isShortcutsOpen, setIsShortcutsOpen] = useState(false);
  const [shortcutHelpPreference, setShortcutHelpPreference] = useState(() =>
    loadReviewProjectShortcutHelpPreference(defaultShortcutHelpPreference),
  );
  const isShortcutBarVisible = shortcutHelpPreference === 'bottom';
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
  const pendingQuerySelectionIdRef = useRef<number | null>(null);
  const [isScreenshotModalOpen, setIsScreenshotModalOpen] = useState(false);
  const [screenshotModalImages, setScreenshotModalImages] = useState<string[]>([]);
  const [selectedScreenshotIdx, setSelectedScreenshotIdx] = useState<number>(0);
  const { onDismissValidationSave, showValidationDialog } = mutations;

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    const filteredRows = textUnits.filter((tu) => {
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
        tu.tmTextUnit?.comment,
        tu.baselineTmTextUnitVariant?.content,
        tu.baselineTmTextUnitVariant?.comment,
        tu.currentTmTextUnitVariant?.content,
        tu.currentTmTextUnitVariant?.comment,
        tu.reviewProjectTextUnitDecision?.decisionTmTextUnitVariant?.comment,
        tu.reviewProjectTextUnitDecision?.notes,
      ]
        .filter(Boolean)
        .map((s) => String(s).toLowerCase());
      return haystacks.some((h) => h.includes(term));
    });

    if (sortByFilter === 'none') {
      return filteredRows;
    }

    const sortDirection = sortOrderFilter === 'asc' ? 1 : -1;
    return [...filteredRows].sort((left, right) => {
      if (sortByFilter === 'id') {
        return sortDirection * (left.id - right.id);
      }

      const leftValue = getTextUnitSortStringValue(left, sortByFilter);
      const rightValue = getTextUnitSortStringValue(right, sortByFilter);
      const compare = compareNullableStrings(leftValue, rightValue);
      if (compare !== 0) {
        return sortDirection * compare;
      }
      // Keep ordering stable when sort values are equal.
      return left.id - right.id;
    });
  }, [editedFilter, search, sortByFilter, sortOrderFilter, stateFilter, statusFilter, textUnits]);

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
      pendingQuerySelectionIdRef.current = null;
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
    pendingQuerySelectionIdRef.current = matchedId;
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
    if (
      selectedTextUnitQueryId != null &&
      pendingQuerySelectionIdRef.current != null &&
      selectedTextUnit?.id !== pendingQuerySelectionIdRef.current
    ) {
      // A browser back/forward navigation updated ?tu= while local selection still points to the
      // previous row. Wait for that selection update before syncing the URL back out.
      return;
    }

    pendingQuerySelectionIdRef.current = null;
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

  useEffect(() => {
    const handleStorage = (event: StorageEvent) => {
      if (event.key && event.key !== REVIEW_PROJECT_SHORTCUT_HELP_KEY) {
        return;
      }
      setShortcutHelpPreference(
        loadReviewProjectShortcutHelpPreference(defaultShortcutHelpPreference),
      );
    };
    window.addEventListener('storage', handleStorage);
    return () => window.removeEventListener('storage', handleStorage);
  }, [defaultShortcutHelpPreference]);

  const handleShortcutBarVisibilityChange = useCallback(
    (visible: boolean) => {
      const nextPreference = visible ? 'bottom' : 'header';
      saveReviewProjectShortcutHelpPreference(nextPreference, defaultShortcutHelpPreference);
      setShortcutHelpPreference(nextPreference);
    },
    [defaultShortcutHelpPreference],
  );

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
  const isTerminologyProject = isTerminologyReviewProjectType(project.type);
  const isTerminologyDeciderProject = project.terminologyPhase === 'PM_RESOLUTION';
  const primaryShortcutLabel = isTerminologyProject
    ? isTerminologyDeciderProject
      ? 'Save selected decision and blur'
      : 'Save advisor input and blur'
    : 'Accept and blur';
  const primaryAdvanceShortcutLabel = isTerminologyProject
    ? isTerminologyDeciderProject
      ? 'Save selected decision and go to next term.'
      : 'Save advisor input and go to next term.'
    : 'Accept and go to next text unit. If unchanged, mark decided and go to next.';

  return (
    <div className="review-project-page">
      <ReviewProjectHeader
        projectId={projectId}
        project={project}
        textUnits={textUnits}
        mutations={mutations}
        canEditRequest={canEditRequest}
        isTranslator={user.role === 'ROLE_TRANSLATOR'}
        reviewProjectsSessionKey={reviewProjectsSessionKey}
        openRequestDetailsQuery={openRequestDetailsQuery}
        requestDetailsSource={requestDetailsSource}
        onRequestDetailsQueryHandled={onRequestDetailsQueryHandled}
        onRequestDetailsFlowFinished={onRequestDetailsFlowFinished}
        onOpenShortcuts={() => setIsShortcutsOpen(true)}
        showShortcutsButton
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
              placeholder="Search source, translation, comments, or id"
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
                  options: DECISION_STATE_OPTIONS,
                  value: stateFilter,
                  onChange: (value) => setDecisionStateFilter(value as DecisionStateFilter),
                },
                {
                  kind: 'radio',
                  label: 'Status',
                  options: STATUS_FILTER_OPTIONS,
                  value: statusFilter,
                  onChange: (value) => setStatusFilter(value as StatusFilter),
                },
                {
                  kind: 'radio',
                  label: 'Edited',
                  options: EDITED_FILTER_OPTIONS,
                  value: editedFilter,
                  onChange: (value) => setEditedFilter(value as EditedFilter),
                },
                {
                  kind: 'radio',
                  label: 'Sort by',
                  options: SORT_BY_FILTER_OPTIONS,
                  value: sortByFilter,
                  onChange: (value) => setSortByFilter(value as SortByFilter),
                },
                {
                  kind: 'radio',
                  label: 'Order',
                  options: SORT_ORDER_FILTER_OPTIONS,
                  value: sortOrderFilter,
                  onChange: (value) => setSortOrderFilter(value as SortOrderFilter),
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
              projectId={projectId}
              projectType={project?.type ?? 'NORMAL'}
              terminologyPhase={project?.terminologyPhase ?? null}
              textUnit={selectedTextUnit}
              localeTag={localeTag}
              mutations={mutations}
              screenshotImages={screenshotImages}
              currentScreenshotIdx={selectedScreenshotIdx}
              onChangeScreenshotIdx={setSelectedScreenshotIdx}
              onOpenGallery={(images) => {
                setScreenshotModalImages(images);
                setIsScreenshotModalOpen(true);
              }}
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
      {isShortcutBarVisible ? (
        <ReviewProjectShortcutBar
          primaryShortcutLabel={primaryShortcutLabel}
          primaryAdvanceShortcutLabel={primaryAdvanceShortcutLabel}
          isTerminologyProject={isTerminologyProject}
          isTerminologyDeciderProject={isTerminologyDeciderProject}
          onOpenShortcuts={() => setIsShortcutsOpen(true)}
        />
      ) : null}
      {isScreenshotModalOpen ? (
        <ScreenshotOverlay
          screenshotImages={screenshotModalImages}
          selectedScreenshotIdx={selectedScreenshotIdx}
          onChangeScreenshotIdx={setSelectedScreenshotIdx}
          onClose={() => {
            setIsScreenshotModalOpen(false);
            setScreenshotModalImages([]);
          }}
        />
      ) : null}
      <IntegrityCheckAlertModal
        open={mutations.showValidationDialog}
        title={mutations.validationDialogTitle}
        body={mutations.validationDialogBody}
        failureDetail={mutations.validationDialogFailureDetail}
        reportMessage={mutations.validationDialogReportMessage}
        reportHtml={mutations.validationDialogReportHtml}
        primaryLabel={
          mutations.validationDialogRequiresConfirmation
            ? 'Save anyway'
            : mutations.validationDialogCanRetry
              ? 'Try again'
              : 'OK'
        }
        primaryVariant={mutations.validationDialogRequiresConfirmation ? 'danger' : 'primary'}
        onPrimary={
          mutations.validationDialogRequiresConfirmation
            ? mutations.onConfirmValidationSave
            : mutations.validationDialogCanRetry
              ? mutations.onRetryValidationSave
              : mutations.onDismissValidationSave
        }
        secondaryLabel={
          mutations.validationDialogRequiresConfirmation
            ? 'Keep editing'
            : mutations.validationDialogCanRetry
              ? 'Close'
              : undefined
        }
        onSecondary={mutations.onDismissValidationSave}
        onClose={mutations.onDismissValidationSave}
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
              <span>{primaryShortcutLabel}</span>
            </li>
            <li className="review-project-shortcuts__item">
              <span className="review-project-shortcuts__key">Cmd/Ctrl + Shift + Enter</span>
              <span>{primaryAdvanceShortcutLabel}</span>
            </li>
            {!isTerminologyProject ? (
              <>
                <li className="review-project-shortcuts__item">
                  <span className="review-project-shortcuts__key">Tab</span>
                  <span>Next editor (translation → comment → notes)</span>
                </li>
                <li className="review-project-shortcuts__item">
                  <span className="review-project-shortcuts__key">Shift + Tab</span>
                  <span>Previous editor</span>
                </li>
              </>
            ) : null}
            {isTerminologyProject ? (
              <>
                <li className="review-project-shortcuts__item">
                  <span className="review-project-shortcuts__key">a</span>
                  <span>
                    {isTerminologyDeciderProject
                      ? 'Apply final status: Approved (outside edit fields)'
                      : 'Recommend Approved (outside edit fields)'}
                  </span>
                </li>
                <li className="review-project-shortcuts__item">
                  <span className="review-project-shortcuts__key">c</span>
                  <span>
                    {isTerminologyDeciderProject
                      ? 'Apply final status: Candidate (outside edit fields)'
                      : 'Recommend Candidate (outside edit fields)'}
                  </span>
                </li>
                <li className="review-project-shortcuts__item">
                  <span className="review-project-shortcuts__key">r</span>
                  <span>
                    {isTerminologyDeciderProject
                      ? 'Apply final status: Rejected (outside edit fields)'
                      : 'Recommend Rejected (outside edit fields)'}
                  </span>
                </li>
              </>
            ) : (
              <>
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
              </>
            )}
          </ul>
          <label className="review-project-shortcuts__option">
            <input
              type="checkbox"
              checked={isShortcutBarVisible}
              onChange={(event) => handleShortcutBarVisibilityChange(event.target.checked)}
            />
            <span>Show shortcut bar at the bottom</span>
          </label>
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
  projectId,
  projectType,
  terminologyPhase,
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
  projectId: number;
  projectType: ApiReviewProjectType;
  terminologyPhase: ApiReviewProjectTerminologyPhase | null;
  textUnit: ApiReviewProjectTextUnit;
  localeTag: string;
  mutations: ReviewProjectMutationControls;
  screenshotImages: string[];
  currentScreenshotIdx: number;
  onChangeScreenshotIdx: (index: number) => void;
  onOpenGallery: (images: string[]) => void;
  detailPaneRef: React.RefObject<HTMLDivElement | null>;
  onDirtyChange: (dirty: boolean) => void;
  onQueueAdvance: (focusTranslation: boolean) => void;
  focusTranslationKey: number;
}) {
  const user = useUser();
  const isVisibleTextEditorEnabled = useVisibleTextEditorEnabled();
  const isTerminologyProject = isTerminologyReviewProjectType(projectType);
  const isTermCandidateProject = projectType === 'TERM_CANDIDATE';
  const isSpecialistTerminologyProject = terminologyPhase === 'SPECIALIST_INPUT';
  const isPmTerminologyProject = terminologyPhase === 'PM_RESOLUTION';
  const [isScreenshotsCollapsed, setIsScreenshotsCollapsed] = useState(false);
  const [heroHeight, setHeroHeight] = useState<number | null>(null);
  const [isHeroResizing, setIsHeroResizing] = useState(false);
  const [lastHeroHeight, setLastHeroHeight] = useState<number | null>(null);
  const [showBaseline, setShowBaseline] = useState(false);
  const [showStaleDecision, setShowStaleDecision] = useState(false);

  const [showSavingIndicator, setShowSavingIndicator] = useState(false);
  const [translationMarksMode, setTranslationMarksMode] = useState<VisibleTextMarksMode>('auto');
  const [icuPreviewMode, setIcuPreviewMode] = useState<'source' | 'target'>('target');
  const [activeContextTab, setActiveContextTab] = useState<ContextTab>('glossary');
  const [isAiCollapsed, setIsAiCollapsed] = useState(false);
  const [isWarningModalOpen, setIsWarningModalOpen] = useState(false);
  const [aiMessages, setAiMessages] = useState<AiChatReviewMessage[]>([]);
  const [aiInput, setAiInput] = useState('');
  const [isAiResponding, setIsAiResponding] = useState(false);
  const heroRef = useRef<HTMLDivElement | null>(null);
  const translationRef = useRef<VisibleTextEditorHandle | null>(null);
  const commentRef = useRef<HTMLTextAreaElement | null>(null);
  const decisionNotesRef = useRef<HTMLTextAreaElement | null>(null);
  const savingIndicatorTimeoutRef = useRef<number | null>(null);
  const aiRequestAttemptRef = useRef(0);
  const aiRequestAbortControllerRef = useRef<AbortController | null>(null);
  const workbenchTextUnitId = textUnit.tmTextUnit?.id ?? null;
  const repositoryId = textUnit.tmTextUnit?.asset?.repository?.id ?? null;
  const repositoryName = textUnit.tmTextUnit?.asset?.repository?.name ?? null;
  const assetPath =
    textUnit.tmTextUnit?.asset?.assetPath != null
      ? String(textUnit.tmTextUnit.asset.assetPath)
      : null;
  const textUnitName = textUnit.tmTextUnit?.name ?? `Text unit ${textUnit.id}`;
  const source = textUnit.tmTextUnit?.content ?? null;
  const sourceComment = textUnit.tmTextUnit?.comment ?? null;
  const baselineVariant = textUnit.baselineTmTextUnitVariant;
  const baselineStatusKey = getStatusKey(baselineVariant);
  const snapshot = useMemo(() => buildSnapshot(textUnit), [textUnit]);
  const snapshotKey = useMemo(() => buildSnapshotKey(textUnit, snapshot), [textUnit, snapshot]);
  const terminologySnapshot = useMemo(
    () => buildTerminologyFeedbackSnapshot(textUnit, user.username),
    [textUnit, user.username],
  );
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
  const aiPrecomputedVariantId = getEffectiveVariant(textUnit)?.id ?? null;

  useEffect(() => {
    return () => {
      aiRequestAttemptRef.current += 1;
      aiRequestAbortControllerRef.current?.abort();
      aiRequestAbortControllerRef.current = null;
    };
  }, []);

  const [draftTarget, setDraftTarget] = useState(snapshot.target);
  const [draftStatusChoice, setDraftStatusChoice] = useState<StatusChoice>(snapshot.statusChoice);
  const [draftComment, setDraftComment] = useState(snapshot.comment ?? '');
  const [draftDecisionNotes, setDraftDecisionNotes] = useState(snapshot.decisionNotes ?? '');
  const hasIcuMessage = useMemo(
    () => hasIcuParameters(source) || hasIcuParameters(draftTarget),
    [draftTarget, source],
  );
  const draftTargetTokenGuard = useProtectedTextTokenGuard(
    draftTarget,
    isVisibleTextEditorEnabled ? 'icu-html' : 'none',
  );
  const draftTargetProtectedTokens = draftTargetTokenGuard.protectedTokens;
  const draftTargetProtectedDiagnostics = draftTargetTokenGuard.diagnostics;
  const validateDraftTarget = draftTargetTokenGuard.validateNextValue;
  const [draftTerminologyRecommendation, setDraftTerminologyRecommendation] =
    useState<ApiTerminologyFeedbackRecommendation | null>(terminologySnapshot.recommendation);
  const [draftTerminologyConfidence, setDraftTerminologyConfidence] =
    useState<TerminologyConfidenceChoice>(terminologySnapshot.confidence);
  const [draftTerminologyNotes, setDraftTerminologyNotes] = useState(terminologySnapshot.notes);
  const isMutationActive = mutations.activeTextUnitId === textUnit.id;
  const isSavingGlobal = mutations.isSaving;
  const isSaving = isMutationActive && isSavingGlobal;
  const errorMessage = isMutationActive ? mutations.errorMessage : null;
  const conflictTextUnit = isMutationActive ? mutations.conflictTextUnit : null;
  const decision = textUnit.reviewProjectTextUnitDecision;
  const decisionVariant = decision?.decisionTmTextUnitVariant ?? null;
  const decisionVariantId = decisionVariant?.id ?? null;
  const translationLang = toHtmlLangTag(localeTag);
  const terminologyTerm = textUnit.terminologyTerm ?? null;
  const glossaryTargetsQuery = useQuery({
    queryKey: ['review-project-glossary-targets'],
    enabled:
      terminologyTerm?.glossaryId == null &&
      Boolean(assetPath) &&
      (repositoryId != null || Boolean(repositoryName?.trim())),
    staleTime: 60_000,
    refetchOnWindowFocus: false,
    queryFn: () => fetchGlossaries({ limit: 200 }),
  });
  const glossaryTermTarget = useMemo(
    () =>
      findGlossaryTargetForTextUnit(glossaryTargetsQuery.data?.glossaries ?? [], {
        repositoryId,
        repositoryName,
        assetPath,
      }),
    [assetPath, glossaryTargetsQuery.data?.glossaries, repositoryId, repositoryName],
  );
  const terminologyGlossaryId =
    terminologyTerm?.glossaryId ?? glossaryTermTarget?.glossaryId ?? null;
  const glossaryTermQuery = useQuery({
    queryKey: [
      'review-project-glossary-term',
      glossaryTermTarget?.glossaryId ?? null,
      workbenchTextUnitId,
      source,
      localeTag,
    ],
    enabled:
      terminologyTerm == null &&
      glossaryTermTarget != null &&
      workbenchTextUnitId != null &&
      Boolean(source?.trim()),
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    queryFn: async () => {
      if (glossaryTermTarget == null || workbenchTextUnitId == null || !source?.trim()) {
        return null as ApiGlossaryTerm | null;
      }
      const response = await fetchGlossaryTerms(glossaryTermTarget.glossaryId, {
        search: source,
        localeTags: localeTag ? [localeTag] : [],
        limit: 25,
      });
      return findGlossaryTermByTmTextUnitId(response.terms, workbenchTextUnitId);
    },
  });
  const glossaryTerm = glossaryTermQuery.data ?? null;
  const glossaryEvidence =
    terminologyTerm?.evidence ?? textUnit.glossaryTermEvidence ?? glossaryTerm?.evidence;
  const glossaryTermScreenshotImages = useMemo(
    () => getGlossaryTermScreenshotKeys(glossaryEvidence),
    [glossaryEvidence],
  );
  const detailScreenshotImages = useMemo(
    () => mergeScreenshotImageKeys(screenshotImages, glossaryTermScreenshotImages),
    [glossaryTermScreenshotImages, screenshotImages],
  );
  const safeScreenshotIdx = detailScreenshotImages.length
    ? Math.min(currentScreenshotIdx, detailScreenshotImages.length - 1)
    : 0;
  const glossaryTermHref =
    terminologyGlossaryId != null
      ? `/glossaries/${terminologyGlossaryId}${
          terminologyTerm?.tmTextUnitId != null
            ? `?termId=${terminologyTerm.tmTextUnitId}`
            : glossaryTerm
              ? `?termId=${glossaryTerm.tmTextUnitId}`
              : ''
        }`
      : null;
  const glossaryTermComment =
    terminologyTerm?.definition?.trim() ||
    glossaryTerm?.definition?.trim() ||
    glossaryTerm?.sourceComment?.trim() ||
    sourceComment;
  const glossaryPartOfSpeech = formatGlossaryMetadataValue(
    terminologyTerm?.partOfSpeech ?? glossaryTerm?.partOfSpeech,
  );
  const glossaryTermType = formatGlossaryMetadataValue(
    terminologyTerm?.termType ?? glossaryTerm?.termType,
  );
  const terminologyResolutionSnapshot = useMemo(
    () => ({
      status: normalizeTerminologyResolutionStatus(terminologyTerm?.status ?? glossaryTerm?.status),
      notes: decision?.notes ?? '',
      promoteToGlossary:
        projectType === 'TERM_CANDIDATE' && decision?.decisionState === 'DECIDED'
          ? terminologyTerm?.tmTextUnitId != null
          : true,
    }),
    [
      decision?.decisionState,
      decision?.notes,
      glossaryTerm?.status,
      projectType,
      terminologyTerm?.status,
      terminologyTerm?.tmTextUnitId,
    ],
  );
  const terminologyDecisionState = decision?.decisionState ?? 'PENDING';
  const hasTermCandidate = isTermCandidateProject && terminologyTerm?.termIndexCandidateId != null;
  const canPromoteTermCandidate = hasTermCandidate && terminologyGlossaryId != null;
  const canResolveTerminology =
    canManageGlossaryTerms(user) &&
    (hasTermCandidate ||
      (terminologyGlossaryId != null && (terminologyTerm != null || glossaryTerm != null)));
  const [draftTerminologyResolutionStatus, setDraftTerminologyResolutionStatus] =
    useState<TerminologyResolutionStatusChoice>(terminologyResolutionSnapshot.status);
  const [draftTerminologyResolutionNotes, setDraftTerminologyResolutionNotes] = useState(
    terminologyResolutionSnapshot.notes,
  );
  const [draftPromoteToGlossary, setDraftPromoteToGlossary] = useState(
    terminologyResolutionSnapshot.promoteToGlossary,
  );
  const glossaryMatchesQuery = useQuery({
    queryKey: [
      'review-project-glossary-matches',
      repositoryId,
      localeTag,
      source,
      workbenchTextUnitId,
    ],
    enabled: repositoryId != null && Boolean(localeTag) && Boolean(source?.trim()),
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    queryFn: async () => {
      if (repositoryId == null || !localeTag || !source?.trim()) {
        return [] as ApiMatchedGlossaryTerm[];
      }

      const response = await matchGlossaryTerms({
        repositoryId,
        localeTag,
        sourceText: source,
        excludeTmTextUnitId: workbenchTextUnitId,
      });

      return sortGlossaryMatches(
        filterSelfGlossaryMatches(response.matchedTerms, workbenchTextUnitId),
      );
    },
  });

  const historyQuery = useQuery({
    queryKey: ['review-project-text-unit-history', workbenchTextUnitId, localeTag],
    enabled: workbenchTextUnitId != null && Boolean(localeTag) && activeContextTab === 'history',
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    queryFn: () => {
      if (workbenchTextUnitId == null || !localeTag) {
        return Promise.resolve([] as ApiTextUnitHistoryItem[]);
      }
      return fetchTextUnitHistory(workbenchTextUnitId, localeTag);
    },
  });

  const aiTranslateAttemptsQuery = useQuery({
    queryKey: ['review-project-text-unit-ai-translate-attempts', workbenchTextUnitId, localeTag],
    enabled: workbenchTextUnitId != null && Boolean(localeTag) && activeContextTab === 'history',
    staleTime: 0,
    refetchOnWindowFocus: false,
    queryFn: () => {
      if (workbenchTextUnitId == null || !localeTag) {
        return Promise.resolve([] as ApiAiTranslateTextUnitAttempt[]);
      }
      return fetchAiTranslateTextUnitAttempts(workbenchTextUnitId, localeTag);
    },
  });

  const aiTranslateTimelineData = useMemo<
    ReturnType<typeof buildAiTranslateAttemptTimelineData>
  >(() => {
    if (workbenchTextUnitId == null || !localeTag) {
      return { byVariantId: new Map(), unlinked: [] };
    }
    return buildAiTranslateAttemptTimelineData(
      aiTranslateAttemptsQuery.data ?? [],
      workbenchTextUnitId,
      localeTag,
    );
  }, [aiTranslateAttemptsQuery.data, localeTag, workbenchTextUnitId]);

  const historyErrorMessage =
    historyQuery.error instanceof Error ? historyQuery.error.message : 'Unable to load history.';

  const historyRows = useMemo<TextUnitHistoryTimelineEntry[]>(() => {
    const historyEntries = [...(historyQuery.data ?? [])].map((item) => {
      const timestamp =
        (item.id === decisionVariantId ? Date.parse(decision?.lastModifiedDate ?? '') || 0 : 0) ||
        Date.parse(item.createdDate ?? '') ||
        0;
      const isAcceptedItem = item.id === decisionVariantId;
      const statusKey = item.includedInLocalizedFile === false ? 'REJECTED' : (item.status ?? null);
      const statusLabel = statusKey != null ? statusKeyToLabel(statusKey) : '-';
      const sourceTmTextUnitId = item.leveraging?.sourceTmTextUnitId;
      const sourceTmTextUnitVariantId = item.leveraging?.sourceTmTextUnitVariantId;
      const leveragingType = item.leveraging?.leveragingType?.trim() || null;
      const isLeveraged =
        typeof sourceTmTextUnitId === 'number' && typeof sourceTmTextUnitVariantId === 'number';
      const aiTranslateAttempts = aiTranslateTimelineData.byVariantId.get(item.id) ?? [];
      const userName =
        (isAcceptedItem
          ? decision?.lastModifiedByUsername
          : item.createdByUser?.username
        )?.trim() ||
        item.createdByUser?.username?.trim() ||
        'Unknown user';
      const displayDate =
        isAcceptedItem && decision?.lastModifiedDate
          ? formatDateTime(decision.lastModifiedDate)
          : formatDateTime(item.createdDate);

      return {
        timestamp,
        tieBreaker: item.id ?? 0,
        row: {
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
            ...(aiTranslateAttempts.length > 0 ? ['AI Translate'] : []),
          ],
          aiTranslateAttempts,
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
        },
      };
    });

    const unlinkedAiTranslateEntries = aiTranslateTimelineData.unlinked.map((attempt) => ({
      timestamp: Date.parse(attempt.createdDate ?? '') || 0,
      tieBreaker: 0,
      row: {
        key: `ai-translate-${attempt.key}`,
        title: 'AI Translate attempt',
        userName: 'AI Translate',
        translation: '<no imported translation>',
        date: formatDateTime(attempt.createdDate),
        status: attempt.status,
        badges: ['AI Translate'],
        aiTranslateAttempts: [attempt],
        comments: [],
      },
    }));

    return [...historyEntries, ...unlinkedAiTranslateEntries]
      .sort((a, b) => {
        const aTimestamp = a.timestamp;
        const bTimestamp = b.timestamp;
        if (bTimestamp !== aTimestamp) {
          return bTimestamp - aTimestamp;
        }
        return b.tieBreaker - a.tieBreaker;
      })
      .slice(0, 8)
      .map((entry) => entry.row);
  }, [
    aiTranslateTimelineData,
    baselineVariant?.id,
    decision?.lastModifiedByUsername,
    decision?.lastModifiedDate,
    decisionVariantId,
    historyQuery.data,
    localeTag,
  ]);

  useEffect(() => {
    setDraftTarget(snapshot.target);
    setDraftStatusChoice(snapshot.statusChoice);
    setDraftComment(snapshot.comment ?? '');
    setDraftDecisionNotes(snapshot.decisionNotes ?? '');
  }, [snapshot, snapshotKey]);

  useEffect(() => {
    setDraftTerminologyRecommendation(terminologySnapshot.recommendation);
    setDraftTerminologyConfidence(terminologySnapshot.confidence);
    setDraftTerminologyNotes(terminologySnapshot.notes);
  }, [terminologySnapshot, textUnit.id]);

  useEffect(() => {
    setDraftTerminologyResolutionStatus(terminologyResolutionSnapshot.status);
    setDraftTerminologyResolutionNotes(terminologyResolutionSnapshot.notes);
    setDraftPromoteToGlossary(terminologyResolutionSnapshot.promoteToGlossary);
  }, [terminologyResolutionSnapshot, textUnit.id]);

  useEffect(() => {
    const requestAttempt = (aiRequestAttemptRef.current += 1);
    aiRequestAbortControllerRef.current?.abort();
    aiRequestAbortControllerRef.current = null;
    if (isTerminologyProject || !localeTag) {
      setAiMessages([]);
      setAiInput('');
      setIsAiResponding(false);
      return;
    }

    let cancelled = false;
    setAiMessages([]);
    setAiInput('');
    setIsAiResponding(false);

    if (glossaryMatchesQuery.isLoading) {
      return () => {
        cancelled = true;
      };
    }

    const abortController = new AbortController();
    aiRequestAbortControllerRef.current = abortController;
    setIsAiResponding(true);
    setIsAiCollapsed(false);

    const initialMessage: AiReviewMessage = {
      role: 'user',
      content: DEFAULT_AI_REVIEW_PROMPT,
    };
    const initialWarnings = buildTranslationWarnings(source ?? '', snapshot.target);
    const warningContextMessage = buildAiWarningContextMessage(initialWarnings);
    const glossaryContextMessage = buildGlossaryContextMessage(glossaryMatchesQuery.data);

    void (async () => {
      try {
        const contextMessages = [warningContextMessage, glossaryContextMessage].filter(
          (message): message is AiReviewMessage => message != null,
        );
        if (contextMessages.length === 0) {
          try {
            const precomputedResponse = await fetchPrecomputedAiReview(aiPrecomputedVariantId, {
              signal: abortController.signal,
            });
            if (cancelled || aiRequestAttemptRef.current !== requestAttempt) {
              return;
            }
            if (precomputedResponse) {
              setAiMessages([
                {
                  id: `assistant-${Date.now()}`,
                  sender: 'assistant',
                  content: precomputedResponse.message.content,
                  suggestions: precomputedResponse.suggestions,
                  review: precomputedResponse.review,
                },
              ]);
              setIsAiResponding(false);
              return;
            }
          } catch {
            if (abortController.signal.aborted) {
              return;
            }
          }
        }
        const response = await requestAiReview(
          {
            source: source ?? '',
            target: snapshot.target,
            localeTag,
            sourceDescription: sourceComment ?? '',
            tmTextUnitId: workbenchTextUnitId ?? undefined,
            messages: [...contextMessages, initialMessage],
          },
          { signal: abortController.signal },
        );
        if (cancelled) {
          return;
        }
        if (aiRequestAttemptRef.current !== requestAttempt) {
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
        if (aiRequestAttemptRef.current !== requestAttempt) {
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
        aiRequestAbortControllerRef.current = null;
      }
    };
  }, [
    aiContextKey,
    aiPrecomputedVariantId,
    glossaryMatchesQuery.data,
    glossaryMatchesQuery.isLoading,
    isTerminologyProject,
    localeTag,
    snapshot.target,
    source,
    sourceComment,
    workbenchTextUnitId,
  ]);

  const draftStatusApi = mapChoiceToApi(draftStatusChoice);
  const snapshotStatusApi = mapChoiceToApi(snapshot.statusChoice);
  const draftCommentNormalized = normalizeOptional(draftComment);
  const draftDecisionNotesNormalized = normalizeOptional(draftDecisionNotes);
  const draftTerminologyNotesNormalized = normalizeOptional(draftTerminologyNotes) ?? '';
  const draftTerminologyResolutionNotesNormalized =
    normalizeOptional(draftTerminologyResolutionNotes) ?? '';
  const isTerminologyDirty =
    draftTerminologyRecommendation !== terminologySnapshot.recommendation ||
    draftTerminologyConfidence !== terminologySnapshot.confidence ||
    draftTerminologyNotesNormalized !== terminologySnapshot.notes;
  const isTerminologyResolutionDirty =
    draftTerminologyResolutionStatus !== terminologyResolutionSnapshot.status ||
    draftTerminologyResolutionNotesNormalized !== terminologyResolutionSnapshot.notes ||
    (canPromoteTermCandidate &&
      draftPromoteToGlossary !== terminologyResolutionSnapshot.promoteToGlossary);
  const isTranslationDirty = draftTarget !== snapshot.target;
  const isTranslationReviewDirty =
    isTranslationDirty ||
    draftStatusApi.status !== snapshotStatusApi.status ||
    draftStatusApi.includedInLocalizedFile !== snapshotStatusApi.includedInLocalizedFile ||
    draftCommentNormalized !== snapshot.comment ||
    draftDecisionNotesNormalized !== snapshot.decisionNotes;
  const isDirty = isTerminologyProject
    ? isTerminologyDirty || isTerminologyResolutionDirty
    : isTranslationReviewDirty;
  const isRejected = draftStatusApi.includedInLocalizedFile === false;
  const canReset = !isTerminologyProject && isDirty && !isSavingGlobal;
  const isAcceptedAndDecided =
    snapshot.statusChoice === 'ACCEPTED' && snapshot.decisionState === 'DECIDED';
  const isTerminologyNotesDirty = draftTerminologyNotesNormalized !== terminologySnapshot.notes;
  const canAccept = isTerminologyProject
    ? !isSavingGlobal && draftTerminologyRecommendation != null && isTerminologyNotesDirty
    : !isSavingGlobal && (!isAcceptedAndDecided || isDirty);
  const canApplyTerminologyResolution =
    isTerminologyProject &&
    !isSpecialistTerminologyProject &&
    canResolveTerminology &&
    !isSavingGlobal &&
    (isTerminologyResolutionDirty || decision?.decisionState !== 'DECIDED');
  const canRunPrimaryShortcut = isPmTerminologyProject ? canApplyTerminologyResolution : canAccept;
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
        reportUrl:
          workbenchTextUnitId != null
            ? buildTextUnitDetailUrl(workbenchTextUnitId, localeTag)
            : window.location.href,
        reviewProjectTextUnitUrl: buildReviewProjectTextUnitUrl(
          projectId,
          workbenchTextUnitId ?? textUnit.id,
        ),
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
      projectId,
      snapshot.expectedCurrentVariantId,
      textUnit.id,
      localeTag,
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

  const requestSaveTerminologyFeedback = useCallback(
    (overrides?: {
      recommendation?: ApiTerminologyFeedbackRecommendation;
      confidence?: TerminologyConfidenceChoice;
    }) => {
      const recommendation = overrides?.recommendation ?? draftTerminologyRecommendation;
      const confidence = overrides?.confidence ?? draftTerminologyConfidence;
      if (recommendation == null) {
        return;
      }
      mutations.onRequestTerminologyFeedback({
        textUnitId: textUnit.id,
        recommendation,
        confidence: confidence === 'unspecified' ? null : Number.parseInt(confidence, 10),
        notes: draftTerminologyNotesNormalized || null,
      });
    },
    [
      draftTerminologyConfidence,
      draftTerminologyNotesNormalized,
      draftTerminologyRecommendation,
      mutations,
      textUnit.id,
    ],
  );

  const handleTerminologyRecommendationClick = useCallback(
    (recommendation: ApiTerminologyFeedbackRecommendation) => {
      const confidence =
        draftTerminologyConfidence === 'unspecified'
          ? DEFAULT_TERMINOLOGY_CONFIDENCE
          : draftTerminologyConfidence;
      setDraftTerminologyRecommendation(recommendation);
      setDraftTerminologyConfidence(confidence);
      requestSaveTerminologyFeedback({ recommendation, confidence });
    },
    [draftTerminologyConfidence, requestSaveTerminologyFeedback],
  );

  const handleTerminologyConfidenceClick = useCallback(
    (confidence: TerminologyConfidenceChoice) => {
      setDraftTerminologyConfidence(confidence);
      if (draftTerminologyRecommendation == null) {
        return;
      }
      requestSaveTerminologyFeedback({ confidence });
    },
    [draftTerminologyRecommendation, requestSaveTerminologyFeedback],
  );

  const requestSaveTerminologyResolution = useCallback(
    (overrides?: { status?: TerminologyResolutionStatusChoice }) => {
      const status = overrides?.status ?? draftTerminologyResolutionStatus;
      mutations.onRequestTerminologyResolution({
        textUnitId: textUnit.id,
        glossaryId: terminologyGlossaryId,
        status,
        notes: draftTerminologyResolutionNotesNormalized || null,
        promoteToGlossary:
          canPromoteTermCandidate && status !== 'REJECTED' ? draftPromoteToGlossary : false,
      });
    },
    [
      canPromoteTermCandidate,
      draftPromoteToGlossary,
      draftTerminologyResolutionNotesNormalized,
      draftTerminologyResolutionStatus,
      mutations,
      terminologyGlossaryId,
      textUnit.id,
    ],
  );

  const handleTerminologyResolutionClick = useCallback(
    (status: TerminologyResolutionStatusChoice) => {
      if (!canResolveTerminology || isSavingGlobal) {
        return;
      }
      setDraftTerminologyResolutionStatus(status);
      requestSaveTerminologyResolution({ status });
    },
    [canResolveTerminology, isSavingGlobal, requestSaveTerminologyResolution],
  );

  const handleTerminologyStatusShortcut = useCallback(
    (lowerKey: string) => {
      const recommendationByKey: Record<string, ApiTerminologyFeedbackRecommendation> = {
        a: 'APPROVE',
        c: 'KEEP_CANDIDATE',
        r: 'REJECT',
      };
      const resolutionByKey: Record<string, TerminologyResolutionStatusChoice> = {
        a: 'APPROVED',
        c: 'CANDIDATE',
        r: 'REJECTED',
      };
      const recommendation = recommendationByKey[lowerKey];
      if (recommendation == null) {
        return false;
      }
      if (mutations.showValidationDialog || mutations.isSaving) {
        return true;
      }
      if (isPmTerminologyProject) {
        const status = resolutionByKey[lowerKey];
        if (!canResolveTerminology || status == null) {
          return true;
        }
        handleTerminologyResolutionClick(status);
        return true;
      }
      handleTerminologyRecommendationClick(recommendation);
      return true;
    },
    [
      canResolveTerminology,
      handleTerminologyRecommendationClick,
      handleTerminologyResolutionClick,
      isPmTerminologyProject,
      mutations.isSaving,
      mutations.showValidationDialog,
    ],
  );

  const handleReset = useCallback(() => {
    setDraftTarget(snapshot.target);
    setDraftStatusChoice(snapshot.statusChoice);
    setDraftComment(snapshot.comment ?? '');
    setDraftDecisionNotes(snapshot.decisionNotes ?? '');
  }, [snapshot]);

  const focusDetailEditor = useCallback((field: DetailEditorField) => {
    if (field === 'translation') {
      translationRef.current?.focus();
      return;
    }
    if (field === 'comment') {
      commentRef.current?.focus();
      return;
    }
    decisionNotesRef.current?.focus();
  }, []);

  const blurDetailEditor = useCallback((field: DetailEditorField) => {
    if (field === 'translation') {
      translationRef.current?.blur();
      return;
    }
    if (field === 'comment') {
      commentRef.current?.blur();
      return;
    }
    decisionNotesRef.current?.blur();
  }, []);

  const focusNextDetailEditor = useCallback(
    (field: DetailEditorField, shiftKey: boolean) => {
      const editors: DetailEditorField[] = isTerminologyProject
        ? ['decisionNotes']
        : ['translation', 'comment', 'decisionNotes'];
      const currentIndex = editors.indexOf(field);
      if (currentIndex === -1) {
        return false;
      }
      const nextIndex = shiftKey
        ? (currentIndex - 1 + editors.length) % editors.length
        : (currentIndex + 1) % editors.length;
      focusDetailEditor(editors[nextIndex]);
      return true;
    },
    [focusDetailEditor, isTerminologyProject],
  );

  const handleTextEditorKeyDown = useCallback(
    (event: TranslationTextEditorKeyDownEvent, field: DetailEditorField) => {
      if (event.key === 'Escape') {
        blurDetailEditor(field);
        event.stopPropagation();
        return;
      }
      if (event.key === 'Tab' && !event.metaKey && !event.ctrlKey && !event.altKey) {
        const didFocusNext = focusNextDetailEditor(field, event.shiftKey);
        if (!didFocusNext) return;
        event.preventDefault();
      }
    },
    [blurDetailEditor, focusNextDetailEditor],
  );

  const handleEditorKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
      const field: DetailEditorField =
        event.currentTarget === commentRef.current ? 'comment' : 'decisionNotes';
      handleTextEditorKeyDown(event, field);
    },
    [handleTextEditorKeyDown],
  );

  const handleTranslationEditorKeyDown = useCallback(
    (event: TranslationTextEditorKeyDownEvent) => {
      handleTextEditorKeyDown(event, 'translation');
      if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
        event.preventDefault();
      }
    },
    [handleTextEditorKeyDown],
  );

  const handleAccept = useCallback(() => {
    if (isTerminologyProject) {
      if (isPmTerminologyProject) {
        requestSaveTerminologyResolution();
        return;
      }
      requestSaveTerminologyFeedback();
      return;
    }
    requestSaveDecision({ statusChoiceOverride: 'ACCEPTED' });
  }, [
    isPmTerminologyProject,
    isTerminologyProject,
    requestSaveDecision,
    requestSaveTerminologyFeedback,
    requestSaveTerminologyResolution,
  ]);

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
        const warningContextMessage = buildAiWarningContextMessage(
          buildTranslationWarnings(source ?? '', draftTarget),
        );
        const glossaryContextMessage = buildGlossaryContextMessage(glossaryMatchesQuery.data);
        const contextMessages = [warningContextMessage, glossaryContextMessage].filter(
          (message): message is AiReviewMessage => message != null,
        );

        const response = await requestAiReview(
          {
            source: source ?? '',
            target: draftTarget,
            localeTag,
            sourceDescription: sourceComment ?? '',
            tmTextUnitId: workbenchTextUnitId ?? undefined,
            messages: [...contextMessages, ...conversation],
          },
          { signal: abortController.signal },
        );
        if (aiRequestAttemptRef.current !== requestAttempt) {
          return;
        }

        const assistantMessage: AiChatReviewMessage = {
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
    aiInput,
    aiMessages,
    draftTarget,
    glossaryMatchesQuery.data,
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
    const glossaryContextMessage = buildGlossaryContextMessage(glossaryMatchesQuery.data);

    setIsAiResponding(true);
    const requestAttempt = (aiRequestAttemptRef.current += 1);
    aiRequestAbortControllerRef.current?.abort();
    const abortController = new AbortController();
    aiRequestAbortControllerRef.current = abortController;
    void (async () => {
      try {
        const contextMessages = [warningContextMessage, glossaryContextMessage].filter(
          (message): message is AiReviewMessage => message != null,
        );
        const response = await requestAiReview(
          {
            source: source ?? '',
            target: retryTarget,
            localeTag,
            sourceDescription: sourceComment ?? '',
            tmTextUnitId: workbenchTextUnitId ?? undefined,
            messages: [...contextMessages, ...conversation],
          },
          { signal: abortController.signal },
        );
        if (aiRequestAttemptRef.current !== requestAttempt) {
          return;
        }
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
    aiMessages,
    draftTarget,
    glossaryMatchesQuery.data,
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

  const getFocusedDetailEditor = useCallback(() => {
    const active = document.activeElement;
    if (active && active instanceof HTMLTextAreaElement) {
      return { blur: () => active.blur() };
    }
    if (
      active instanceof HTMLElement &&
      active.closest('.review-project-detail__input--translation.visible-text-editor')
    ) {
      return translationRef.current;
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
        if (isTerminologyProject) {
          return;
        }
        if (!isOutsideEditable) {
          return;
        }
        event.preventDefault();
        translationRef.current?.focus();
        return;
      }

      if (lowerKey === 'p' && isPlainKey) {
        if (isTerminologyProject) {
          return;
        }
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

      if (isTerminologyProject && isPlainKey && isOutsideEditable) {
        const handledTerminologyStatusShortcut = handleTerminologyStatusShortcut(lowerKey);
        if (handledTerminologyStatusShortcut) {
          event.preventDefault();
          return;
        }
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
        if (canRunPrimaryShortcut) {
          event.preventDefault();
          handleAccept();
        }
        return;
      }

      if (event.key !== 'Enter' || (!event.metaKey && !event.ctrlKey)) {
        return;
      }
      const focusedEditor = getFocusedDetailEditor();
      if (focusedEditor) {
        event.preventDefault();
      }
      if (event.shiftKey) {
        if (mutations.showValidationDialog || mutations.isSaving) {
          return;
        }
        if (isTerminologyProject) {
          if (canRunPrimaryShortcut) {
            handleAccept();
          }
          onQueueAdvance(false);
          focusedEditor?.blur();
          return;
        }
        if (canRunPrimaryShortcut) {
          handleAccept();
        } else if (snapshot.decisionState !== 'DECIDED') {
          requestDecisionState('DECIDED');
        }
        onQueueAdvance(!isTerminologyProject);
        focusedEditor?.blur();
        return;
      }
      if (mutations.showValidationDialog) {
        focusedEditor?.blur();
        return;
      }
      if (canRunPrimaryShortcut) {
        handleAccept();
      }
      focusedEditor?.blur();
    };
    window.addEventListener('keydown', handleSaveShortcut);
    return () => window.removeEventListener('keydown', handleSaveShortcut);
  }, [
    canRunPrimaryShortcut,
    getFocusedDetailEditor,
    handleAccept,
    handleTerminologyStatusShortcut,
    isDirty,
    isEditableTarget,
    isTerminologyProject,
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

  useEffect(() => {
    if (currentScreenshotIdx !== safeScreenshotIdx) {
      onChangeScreenshotIdx(safeScreenshotIdx);
    }
  }, [currentScreenshotIdx, onChangeScreenshotIdx, safeScreenshotIdx]);

  const recomputeHeroHeight = useCallback(() => {
    if (!heroRef.current || !detailPaneRef.current || !detailScreenshotImages.length) {
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
  }, [detailPaneRef, detailScreenshotImages.length]);

  useEffect(() => {
    recomputeHeroHeight();
  }, [recomputeHeroHeight, textUnit.id]);

  useEffect(() => {
    if (!detailPaneRef.current || !detailScreenshotImages.length) {
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
  }, [detailPaneRef, recomputeHeroHeight, detailScreenshotImages.length]);

  useEffect(() => {
    if (!detailScreenshotImages.length) {
      setHeroHeight(null);
      setLastHeroHeight(null);
      setIsScreenshotsCollapsed(false);
    }
  }, [detailScreenshotImages.length]);

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
    setIcuPreviewMode('target');
    setActiveContextTab('glossary');
  }, [textUnit.id]);

  useEffect(() => {
    if (isSaving) {
      if (savingIndicatorTimeoutRef.current != null) {
        window.clearTimeout(savingIndicatorTimeoutRef.current);
        savingIndicatorTimeoutRef.current = null;
      }
      if (showSavingIndicator) {
        return;
      }
      savingIndicatorTimeoutRef.current = window.setTimeout(() => {
        setShowSavingIndicator(true);
        savingIndicatorTimeoutRef.current = null;
      }, SAVING_INDICATOR_DELAY_MS);
      return;
    }

    if (savingIndicatorTimeoutRef.current != null) {
      window.clearTimeout(savingIndicatorTimeoutRef.current);
      savingIndicatorTimeoutRef.current = null;
    }

    if (showSavingIndicator) {
      setShowSavingIndicator(false);
    }

    return () => {
      if (savingIndicatorTimeoutRef.current != null) {
        window.clearTimeout(savingIndicatorTimeoutRef.current);
        savingIndicatorTimeoutRef.current = null;
      }
    };
  }, [isSaving, showSavingIndicator]);

  useEffect(() => {
    return () => {
      if (savingIndicatorTimeoutRef.current != null) {
        window.clearTimeout(savingIndicatorTimeoutRef.current);
        savingIndicatorTimeoutRef.current = null;
      }
    };
  }, []);

  return (
    <div className="review-project-detail">
      <div className="review-project-detail__header">
        <div className="review-project-detail__title" />
      </div>
      <div
        className={`review-project-detail__hero${
          detailScreenshotImages.length ? ' review-project-detail__hero--has-shots' : ''
        }${isScreenshotsCollapsed ? ' review-project-detail__hero--collapsed' : ''}`}
        ref={heroRef}
        style={
          !isScreenshotsCollapsed && heroHeight != null ? { height: `${heroHeight}px` } : undefined
        }
      >
        {detailScreenshotImages.length ? (
          <div className="review-project-detail__shots-badge">
            {`${safeScreenshotIdx + 1} / ${detailScreenshotImages.length}`}
          </div>
        ) : null}
        {detailScreenshotImages.length ? (
          <>
            {isScreenshotsCollapsed ? null : (
              <div className="review-project-detail__gallery review-project-detail__gallery--hero">
                <button
                  type="button"
                  className="review-project-detail__gallery-nav"
                  onClick={() =>
                    onChangeScreenshotIdx(
                      (safeScreenshotIdx - 1 + detailScreenshotImages.length) %
                        detailScreenshotImages.length,
                    )
                  }
                  aria-label="Previous screenshot"
                >
                  ‹
                </button>
                <div className="review-project-detail__gallery-main review-project-detail__gallery-main--hero">
                  {renderMedia(
                    detailScreenshotImages[safeScreenshotIdx],
                    'review-project-detail__gallery-image review-project-detail__gallery-image--interactive',
                    {
                      controls: isVideoAttachmentKey(detailScreenshotImages[safeScreenshotIdx]),
                      muted: true,
                      loop: true,
                      preload: 'metadata',
                      onClick: () => onOpenGallery(detailScreenshotImages),
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
                    onChangeScreenshotIdx((safeScreenshotIdx + 1) % detailScreenshotImages.length)
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
      {detailScreenshotImages.length ? (
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

          {isTerminologyProject ? (
            <>
              {terminologyTerm ? (
                <TerminologyMetadataPanel
                  term={terminologyTerm}
                  glossaryTermHref={glossaryTermHref}
                  isTermCandidateProject={projectType === 'TERM_CANDIDATE'}
                  canEdit={canManageGlossaryTerms(user)}
                  isSaving={isSavingGlobal}
                  onSave={(request) =>
                    mutations.onRequestTerminologyMetadata({
                      textUnitId: textUnit.id,
                      request,
                    })
                  }
                />
              ) : null}

              {!isPmTerminologyProject ? (
                <>
                  <div className="review-project-detail__section-label">Advisor input</div>

                  <div className="review-project-detail__field review-project-detail__field--translation">
                    <div className="review-project-detail__label-row">
                      <div className="review-project-detail__label">Recommended status</div>
                    </div>
                    <div
                      className="review-project-detail__decision-segmented"
                      role="group"
                      aria-label="Recommended status"
                    >
                      {TERMINOLOGY_FEEDBACK_RECOMMENDATION_OPTIONS.map((recommendation) => (
                        <button
                          key={recommendation}
                          type="button"
                          className={`review-project-detail__decision-option${
                            draftTerminologyRecommendation === recommendation ? ' is-active' : ''
                          }`}
                          onClick={() => handleTerminologyRecommendationClick(recommendation)}
                          disabled={isSavingGlobal}
                          aria-pressed={draftTerminologyRecommendation === recommendation}
                        >
                          {TERMINOLOGY_FEEDBACK_RECOMMENDATION_LABELS[recommendation]}
                        </button>
                      ))}
                    </div>
                  </div>

                  <div className="review-project-detail__field">
                    <div className="review-project-detail__label-row">
                      <div className="review-project-detail__label">Confidence</div>
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
                    </div>
                    <div
                      className="review-project-detail__decision-segmented"
                      role="group"
                      aria-label="Confidence"
                    >
                      {TERMINOLOGY_CONFIDENCE_OPTIONS.map((option) => (
                        <button
                          key={option.value}
                          type="button"
                          className={`review-project-detail__decision-option${
                            draftTerminologyConfidence === option.value ? ' is-active' : ''
                          }`}
                          onClick={() => handleTerminologyConfidenceClick(option.value)}
                          disabled={isSavingGlobal}
                          aria-pressed={draftTerminologyConfidence === option.value}
                        >
                          {option.label}
                        </button>
                      ))}
                    </div>
                  </div>

                  <div className="review-project-detail__field">
                    <div className="review-project-detail__label">Recommendation notes</div>
                    <AutoTextarea
                      className="review-project-detail__input review-project-detail__input--compact review-project-detail__input--autosize"
                      ref={decisionNotesRef}
                      value={draftTerminologyNotes}
                      onChange={(event) => setDraftTerminologyNotes(event.target.value)}
                      placeholder="Add source-term context, risks, or why this should not be a glossary term."
                      onKeyDown={handleEditorKeyDown}
                      rows={1}
                      style={{ resize: 'none' }}
                    />
                    <div className="review-project-detail__editor-actions">
                      <button
                        type="button"
                        className="review-project-detail__actions-button review-project-detail__actions-button--primary"
                        onClick={handleAccept}
                        disabled={!canAccept}
                      >
                        Save notes
                      </button>
                    </div>
                  </div>
                </>
              ) : null}

              <div className="review-project-detail__field">
                <div className="review-project-detail__label">Advisor responses</div>
                <TerminologyFeedbackList feedbacks={textUnit.terminologyFeedbacks ?? []} />
              </div>

              {!isSpecialistTerminologyProject ? (
                <>
                  <div className="review-project-detail__section-label">Decider final decision</div>

                  <div className="review-project-detail__field">
                    <div className="review-project-detail__label-row">
                      <div className="review-project-detail__label">Review decision</div>
                      <div className="review-project-detail__decision-current">
                        <span>
                          Current status:{' '}
                          {
                            TERMINOLOGY_RESOLUTION_STATUS_LABELS[
                              terminologyResolutionSnapshot.status
                            ]
                          }
                        </span>
                        <span>
                          Project: {terminologyDecisionState === 'DECIDED' ? 'Decided' : 'Pending'}
                        </span>
                      </div>
                    </div>
                    <div className="review-project-detail__decision-segmented" role="group">
                      {TERMINOLOGY_RESOLUTION_STATUS_OPTIONS.map((status) => (
                        <button
                          key={status}
                          type="button"
                          className={`review-project-detail__decision-option${
                            draftTerminologyResolutionStatus === status ? ' is-active' : ''
                          }`}
                          onClick={() => handleTerminologyResolutionClick(status)}
                          disabled={!canResolveTerminology || isSavingGlobal}
                          aria-pressed={draftTerminologyResolutionStatus === status}
                        >
                          {TERMINOLOGY_RESOLUTION_STATUS_LABELS[status]}
                        </button>
                      ))}
                    </div>
                    {!canResolveTerminology ? (
                      <div className="review-project-detail__hint">
                        Final decision is available to decider/admin users when this source maps to
                        a glossary term or term candidate.
                      </div>
                    ) : null}
                  </div>

                  {hasTermCandidate ? (
                    <div className="review-project-detail__field">
                      <label className="review-project-detail__terminology-checkbox">
                        <input
                          type="checkbox"
                          checked={canPromoteTermCandidate && draftPromoteToGlossary}
                          disabled={
                            !canPromoteTermCandidate || !canResolveTerminology || isSavingGlobal
                          }
                          onChange={(event) => setDraftPromoteToGlossary(event.target.checked)}
                        />
                        <span>
                          Include accepted candidate in target glossary
                          <small>
                            {canPromoteTermCandidate
                              ? ' Used when saving Approved or Candidate; Rejected decisions never promote.'
                              : ' No target glossary is set, so this decision updates candidate status only.'}
                          </small>
                        </span>
                      </label>
                    </div>
                  ) : null}

                  <div className="review-project-detail__field">
                    <div className="review-project-detail__label">Decision notes</div>
                    <AutoTextarea
                      className="review-project-detail__input review-project-detail__input--compact review-project-detail__input--autosize"
                      value={draftTerminologyResolutionNotes}
                      onChange={(event) => setDraftTerminologyResolutionNotes(event.target.value)}
                      placeholder="Record why this final glossary decision was made."
                      onKeyDown={handleEditorKeyDown}
                      rows={1}
                      style={{ resize: 'none' }}
                      disabled={!canResolveTerminology || isSavingGlobal}
                    />
                  </div>
                </>
              ) : null}
            </>
          ) : (
            <>
              <div className="review-project-detail__field review-project-detail__field--translation">
                <div className="review-project-detail__label-row">
                  <div className="review-project-detail__label">Translation</div>
                </div>
                <TranslationTextEditor
                  assisted={isVisibleTextEditorEnabled}
                  className={`review-project-detail__input review-project-detail__input--autosize review-project-detail__input--translation${
                    isRejected ? ' review-project-detail__input--rejected' : ''
                  }`}
                  ref={translationRef}
                  value={draftTarget}
                  onChange={setDraftTarget}
                  ariaLabel="Translation"
                  controlBar={
                    isVisibleTextEditorEnabled
                      ? {
                          marksMode: translationMarksMode,
                          onChangeMarksMode: setTranslationMarksMode,
                          protectedTokenCount: draftTargetProtectedTokens.length,
                        }
                      : undefined
                  }
                  spellCheck={true}
                  lang={translationLang}
                  disabled={isSavingGlobal}
                  onKeyDown={handleTranslationEditorKeyDown}
                  marksMode={translationMarksMode}
                  protectedDiagnostics={draftTargetProtectedDiagnostics}
                  protectedTokens={draftTargetProtectedTokens}
                  validateNextValue={isVisibleTextEditorEnabled ? validateDraftTarget : undefined}
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
            </>
          )}

          {errorMessage ? <div className="review-project-detail__error">{errorMessage}</div> : null}
        </div>

        <div className="review-project-detail__side">
          {!terminologyTerm ? (
            <div className="review-project-detail__field review-project-detail__field--source">
              <div className="review-project-detail__label">
                <span>Source</span>
                {glossaryTermHref ? (
                  <Link className="review-project-detail__source-affordance" to={glossaryTermHref}>
                    <Pill>Glossary term</Pill>
                  </Link>
                ) : null}
              </div>
              <div className="review-project-detail__value review-project-detail__value--source">
                {source || '—'}
              </div>
            </div>
          ) : null}

          {!terminologyTerm ? (
            <div className="review-project-detail__field">
              <div className="review-project-detail__label">Comment</div>
              <div className="review-project-detail__value">{glossaryTermComment ?? '—'}</div>
            </div>
          ) : null}

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

          {!terminologyTerm && glossaryTermTarget && glossaryPartOfSpeech ? (
            <div className="review-project-detail__field">
              <div className="review-project-detail__label">POS</div>
              <div className="review-project-detail__value">{glossaryPartOfSpeech}</div>
            </div>
          ) : null}

          {!terminologyTerm && glossaryTermTarget && glossaryTermType ? (
            <div className="review-project-detail__field">
              <div className="review-project-detail__label">Type</div>
              <div className="review-project-detail__value">{glossaryTermType}</div>
            </div>
          ) : null}

          {terminologyTerm ? <TerminologyEvidencePanel term={terminologyTerm} /> : null}

          <div className="review-project-detail__context-panel">
            <div className="review-project-detail__context-tabs" role="tablist">
              {[
                {
                  value: 'glossary' as const,
                  label: 'Glossary',
                  count: glossaryMatchesQuery.data?.length,
                },
                {
                  value: 'icu' as const,
                  label: 'Placeholders',
                  count: hasIcuMessage ? 1 : undefined,
                },
                { value: 'history' as const, label: 'History' },
                { value: 'context' as const, label: 'Context' },
              ].map((tab) =>
                (() => {
                  const tabCount = formatTabCount(tab.count);
                  return (
                    <button
                      key={tab.value}
                      type="button"
                      className={`review-project-detail__context-tab${
                        activeContextTab === tab.value ? ' is-active' : ''
                      }`}
                      role="tab"
                      aria-selected={activeContextTab === tab.value}
                      onClick={() => {
                        setActiveContextTab(tab.value);
                      }}
                    >
                      <span className="review-project-detail__context-tab-label">{tab.label}</span>
                      <span
                        className={`review-project-detail__context-tab-count-slot${
                          tabCount ? ' has-count' : ''
                        }`}
                      >
                        {tabCount ? (
                          <>
                            <span className="review-project-detail__context-tab-dot" aria-hidden>
                              ·
                            </span>
                            <span className="review-project-detail__context-tab-count">
                              {tabCount}
                            </span>
                          </>
                        ) : null}
                      </span>
                    </button>
                  );
                })(),
              )}
            </div>

            <div className="review-project-detail__context-body">
              {activeContextTab === 'glossary' ? (
                <GlossaryMatchesPanel
                  matches={glossaryMatchesQuery.data ?? []}
                  isLoading={glossaryMatchesQuery.isLoading}
                  errorMessage={
                    glossaryMatchesQuery.error instanceof Error
                      ? glossaryMatchesQuery.error.message
                      : null
                  }
                  currentTarget={isTerminologyProject ? (source ?? '') : draftTarget}
                  showHeader={false}
                />
              ) : null}

              {activeContextTab === 'icu' ? (
                hasIcuMessage ? (
                  <IcuPreviewSection
                    sourceMessage={source}
                    targetMessage={draftTarget}
                    targetLocale={localeTag}
                    mode={icuPreviewMode}
                    isCollapsed={false}
                    onToggleCollapsed={() => undefined}
                    onChangeMode={setIcuPreviewMode}
                    className="review-project-detail__field review-project-detail__field--icu review-project-detail__field--icu-tab"
                    titleClassName="review-project-detail__label"
                  />
                ) : (
                  <div className="review-project-detail__context-empty">
                    No ICU placeholders detected.
                  </div>
                )
              ) : null}

              {activeContextTab === 'history' ? (
                <TextUnitHistoryTimeline
                  isLoading={historyQuery.isLoading}
                  errorMessage={historyQuery.isError ? historyErrorMessage : null}
                  entries={historyRows}
                  initialDate={formatDateTime(textUnit.tmTextUnit?.createdDate)}
                  emptyMessage="No history yet."
                />
              ) : null}

              {activeContextTab === 'context' ? (
                <div className="review-project-detail__context-stack">
                  {repositoryName || assetPath ? (
                    <div className="review-project-detail__field">
                      <div className="review-project-detail__label">Location</div>
                      <div className="review-project-detail__value review-project-detail__value--meta">
                        {[repositoryName, assetPath].filter(Boolean).join(' / ')}
                      </div>
                    </div>
                  ) : null}

                  {!isTerminologyProject ? (
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
                  ) : null}
                </div>
              ) : null}
            </div>
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

function ReviewProjectShortcutBar({
  primaryShortcutLabel,
  primaryAdvanceShortcutLabel,
  isTerminologyProject,
  isTerminologyDeciderProject,
  onOpenShortcuts,
}: {
  primaryShortcutLabel: string;
  primaryAdvanceShortcutLabel: string;
  isTerminologyProject: boolean;
  isTerminologyDeciderProject: boolean;
  onOpenShortcuts: () => void;
}) {
  const decisionShortcuts = isTerminologyProject
    ? [
        {
          keyLabel: 'A',
          label: isTerminologyDeciderProject ? 'Approved' : 'Recommend approved',
        },
        {
          keyLabel: 'C',
          label: isTerminologyDeciderProject ? 'Candidate' : 'Recommend candidate',
        },
        {
          keyLabel: 'R',
          label: isTerminologyDeciderProject ? 'Rejected' : 'Recommend rejected',
        },
      ]
    : [
        { keyLabel: 'A', label: 'Accept' },
        { keyLabel: 'E', label: 'Edit' },
        { keyLabel: 'P', label: 'Pending' },
      ];

  return (
    <div className="review-project-shortcut-bar">
      <span className="review-project-shortcut-bar__item">
        <kbd>↑</kbd>
        <kbd>↓</kbd>
        <span>Next/Prev</span>
      </span>
      {decisionShortcuts.map((shortcut) => (
        <span key={shortcut.keyLabel} className="review-project-shortcut-bar__item">
          <kbd>{shortcut.keyLabel}</kbd>
          <span>{shortcut.label}</span>
        </span>
      ))}
      <span className="review-project-shortcut-bar__item">
        <kbd>Cmd/Ctrl Enter</kbd>
        <span>{primaryShortcutLabel}</span>
      </span>
      <span className="review-project-shortcut-bar__item">
        <kbd>Cmd/Ctrl Shift Enter</kbd>
        <span>{primaryAdvanceShortcutLabel}</span>
      </span>
      <button
        type="button"
        className="review-project-shortcut-bar__button"
        onClick={onOpenShortcuts}
      >
        <kbd>/</kbd>
        <span>Help</span>
      </button>
    </div>
  );
}

function ReviewProjectHeader({
  projectId,
  project,
  textUnits: textUnitsProp,
  mutations,
  canEditRequest,
  isTranslator,
  reviewProjectsSessionKey,
  openRequestDetailsQuery,
  requestDetailsSource,
  onRequestDetailsQueryHandled,
  onRequestDetailsFlowFinished,
  onOpenShortcuts,
  showShortcutsButton,
  onReviewPending,
}: {
  projectId: number;
  project: ApiReviewProjectDetail;
  textUnits: ApiReviewProjectTextUnit[];
  mutations: ReviewProjectMutationControls;
  canEditRequest: boolean;
  isTranslator: boolean;
  reviewProjectsSessionKey: string | null;
  openRequestDetailsQuery: boolean;
  requestDetailsSource: 'list' | null;
  onRequestDetailsQueryHandled: () => void;
  onRequestDetailsFlowFinished: () => void;
  onOpenShortcuts: () => void;
  showShortcutsButton: boolean;
  onReviewPending: () => void;
}) {
  const { dueDate, textUnitCount, wordCount, status, type } = project;
  const terminologyPhase = project.terminologyPhase ?? null;
  const name = project.reviewProjectRequest?.name ?? null;
  const requestId = project.reviewProjectRequest?.id ?? null;
  const description = project.reviewProjectRequest?.notes ?? '';
  const requestCreatedDate =
    project.reviewProjectRequest?.createdDate ?? project.createdDate ?? null;
  const requestCreatedBy = project.reviewProjectRequest?.createdByUsername ?? null;
  const requestAttachments = useMemo(
    () => project.reviewProjectRequest?.screenshotImageIds ?? [],
    [project.reviewProjectRequest?.screenshotImageIds],
  );
  const assignment = project.assignment ?? null;
  const isTerminologyProject = isTerminologyReviewProjectType(type);
  const pmAssignmentLabel = isTerminologyProject ? 'Decider' : 'PM';
  const translatorAssignmentLabel = isTerminologyProject ? 'Advisor' : 'Translator';
  const teamDisplayName =
    assignment?.teamName?.trim() ||
    (assignment?.teamId != null ? `#${assignment.teamId}` : 'No team');
  const locale = project.locale ?? null;
  const textUnits = useMemo(() => textUnitsProp ?? [], [textUnitsProp]);
  const locales = useMemo(() => (locale ? [locale] : []), [locale]);
  const nextStatus = status === 'OPEN' ? 'CLOSED' : 'OPEN';
  const actionLabel = status === 'OPEN' ? 'Close project' : 'Reopen project';
  const [showCloseWarning, setShowCloseWarning] = useState(false);
  const [closeRequestCopyStatus, setCloseRequestCopyStatus] = useState<
    'idle' | 'copied' | 'failed'
  >('idle');
  const pendingItemLabel =
    isTerminologyProject && terminologyPhase === 'SPECIALIST_INPUT'
      ? 'needs advisor input'
      : 'needs a decision';
  const progressDoneLabel = 'reviewed';
  const [showDescription, setShowDescription] = useState(false);
  const [isProjectDueDateModalOpen, setIsProjectDueDateModalOpen] = useState(false);
  const [requestNameDraft, setRequestNameDraft] = useState(name ?? '');
  const [descriptionDraft, setDescriptionDraft] = useState(description);
  const [projectTypeDraft, setProjectTypeDraft] = useState<ApiReviewProjectType>(type);
  const [dueDateDraft, setDueDateDraft] = useState(toDateTimeLocalInputValue(dueDate));
  const [requestTeamDraftId, setRequestTeamDraftId] = useState<number | null>(
    assignment?.teamId ?? null,
  );
  const [projectDueDateDraft, setProjectDueDateDraft] = useState(
    toDateTimeLocalInputValue(dueDate),
  );
  const [isAssignmentModalOpen, setIsAssignmentModalOpen] = useState(false);
  const [assignmentDraftPmUserId, setAssignmentDraftPmUserId] = useState<number | null>(
    assignment?.assignedPmUserId ?? null,
  );
  const [assignmentDraftTranslatorUserId, setAssignmentDraftTranslatorUserId] = useState<
    number | null
  >(assignment?.assignedTranslatorUserId ?? null);
  const [assignmentSaveError, setAssignmentSaveError] = useState<string | null>(null);
  const [attachmentDrafts, setAttachmentDrafts] = useState<string[]>(requestAttachments);
  const [attachmentUploadQueue, setAttachmentUploadQueue] = useState<
    RequestAttachmentUploadQueueItem[]
  >([]);
  const attachmentUploadPreviewUrlsRef = useRef<Set<string>>(new Set());
  const [optimizeImagesBeforeUpload, setOptimizeImagesBeforeUpload] = useState(true);
  const [isAttachmentUploading, setIsAttachmentUploading] = useState(false);
  const [attachmentUploadError, setAttachmentUploadError] = useState<string | null>(null);
  const [requestSaveError, setRequestSaveError] = useState<string | null>(null);
  const [projectDueDateSaveError, setProjectDueDateSaveError] = useState<string | null>(null);
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
  const activeAssignmentTeamId = assignment?.teamId ?? null;
  const teamsQuery = useQuery<ApiTeam[]>({
    queryKey: ['teams', 'review-project-request-details'],
    queryFn: fetchTeams,
    enabled: canEditRequest && showDescription,
    staleTime: 30_000,
  });
  const assignmentPmUsersQuery = useQuery({
    queryKey: ['team-users', activeAssignmentTeamId, 'PM', 'review-project-detail-assignment'],
    queryFn: () => fetchTeamUsersByRole(activeAssignmentTeamId as number, 'PM'),
    enabled: canEditRequest && isAssignmentModalOpen && activeAssignmentTeamId != null,
    staleTime: 30_000,
  });
  const assignmentTranslatorUsersQuery = useQuery({
    queryKey: [
      'team-users',
      activeAssignmentTeamId,
      'TRANSLATOR',
      'review-project-detail-assignment',
    ],
    queryFn: () => fetchTeamUsersByRole(activeAssignmentTeamId as number, 'TRANSLATOR'),
    enabled: canEditRequest && isAssignmentModalOpen && activeAssignmentTeamId != null,
    staleTime: 30_000,
  });
  const assignmentHistoryQuery = useQuery({
    queryKey: ['review-project-assignment-history', projectId],
    queryFn: () => fetchReviewProjectAssignmentHistory(projectId),
    enabled: canEditRequest && isAssignmentModalOpen,
    staleTime: 10_000,
  });
  const requestTeamOptions = useMemo(
    () =>
      buildAssignmentTeamOptions(teamsQuery.data ?? [], assignment?.teamId, assignment?.teamName),
    [assignment?.teamId, assignment?.teamName, teamsQuery.data],
  );
  const hasRequestTeamOptions = requestTeamOptions.length > 0;
  const assignmentPmUsers = useMemo(
    () =>
      isTerminologyProject
        ? mergeTeamUsers(
            assignmentPmUsersQuery.data?.users,
            assignmentTranslatorUsersQuery.data?.users,
          )
        : (assignmentPmUsersQuery.data?.users ?? []),
    [
      assignmentPmUsersQuery.data?.users,
      assignmentTranslatorUsersQuery.data?.users,
      isTerminologyProject,
    ],
  );
  const assignmentPmOptions = useMemo(
    () =>
      buildAssignmentUserOptions(
        assignmentPmUsers,
        assignment?.assignedPmUserId,
        assignment?.assignedPmUsername,
      ),
    [assignment?.assignedPmUserId, assignment?.assignedPmUsername, assignmentPmUsers],
  );
  const assignmentTranslatorOptions = useMemo(
    () =>
      buildAssignmentUserOptions(
        assignmentTranslatorUsersQuery.data?.users ?? [],
        assignment?.assignedTranslatorUserId,
        assignment?.assignedTranslatorUsername,
      ),
    [
      assignment?.assignedTranslatorUserId,
      assignment?.assignedTranslatorUsername,
      assignmentTranslatorUsersQuery.data?.users,
    ],
  );
  const assignmentLoadError =
    activeAssignmentTeamId == null
      ? null
      : assignmentPmUsersQuery.error != null
        ? getErrorMessage(assignmentPmUsersQuery.error, `Failed to load ${pmAssignmentLabel}s`)
        : assignmentTranslatorUsersQuery.error != null
          ? getErrorMessage(
              assignmentTranslatorUsersQuery.error,
              `Failed to load ${translatorAssignmentLabel.toLowerCase()}s`,
            )
          : null;
  const assignmentHasAssigneeWithoutTeam =
    activeAssignmentTeamId == null &&
    (assignmentDraftPmUserId != null || assignmentDraftTranslatorUserId != null);
  const assignmentIsChanged =
    assignmentDraftPmUserId !== (assignment?.assignedPmUserId ?? null) ||
    assignmentDraftTranslatorUserId !== (assignment?.assignedTranslatorUserId ?? null);
  const canSaveAssignment =
    canEditRequest &&
    assignmentIsChanged &&
    !mutations.isProjectAssignmentSaving &&
    assignmentLoadError == null &&
    !assignmentHasAssigneeWithoutTeam;

  const {
    selectedCount,
    decidedCount,
    pendingCount,
    progressPercent,
    progressPercentLabel,
    progressTitle,
    specialistInputPercentLabel,
    specialistInputTitle,
  } = useMemo(() => {
    const selectedTextUnits = textUnits ?? [];
    const selected = selectedTextUnits.length;
    const selectedWords = sumTextUnitWords(selectedTextUnits);
    const hasSpecialistFeedback = (tu: ApiReviewProjectTextUnit) =>
      (tu.terminologyFeedbacks ?? []).length > 0;
    const decidedTextUnits =
      isTerminologyProject && terminologyPhase === 'SPECIALIST_INPUT'
        ? selectedTextUnits.filter(hasSpecialistFeedback)
        : selectedTextUnits.filter((tu) => getDecisionState(tu) === 'DECIDED');
    const decided = decidedTextUnits.length;
    const decidedWords = sumTextUnitWords(decidedTextUnits);
    const specialistInputTextUnits =
      isTerminologyProject && terminologyPhase === 'PM_RESOLUTION'
        ? selectedTextUnits.filter((tu) => (tu.terminologyFeedbacks ?? []).length > 0)
        : [];
    const specialistInputCount = specialistInputTextUnits.length;
    const specialistInputWords = sumTextUnitWords(specialistInputTextUnits);
    const pending = Math.max(0, selected - decided);
    const hasWordProgress = selectedWords > 0;
    const percent = hasWordProgress
      ? (decidedWords / selectedWords) * 100
      : selected > 0
        ? (decided / selected) * 100
        : 0;
    const progressLabel =
      isTerminologyProject && terminologyPhase === 'PM_RESOLUTION'
        ? 'Decider reviewed'
        : 'Reviewed';
    const progressValue = hasWordProgress
      ? `${formatNumber(decidedWords)} of ${formatNumber(selectedWords)} words reviewed`
      : `${decided} of ${selected} text units reviewed`;
    const title = selected > 0 ? `${progressLabel}: ${progressValue}` : 'No text units';
    const specialistInputPercent = hasWordProgress
      ? (specialistInputWords / selectedWords) * 100
      : selected > 0 && specialistInputCount > 0
        ? (specialistInputCount / selected) * 100
        : 0;
    const specialistInputValue = hasWordProgress
      ? `${formatNumber(specialistInputWords)} of ${formatNumber(selectedWords)} words with advisor input`
      : `${specialistInputCount} of ${selected} text units with advisor input`;
    return {
      selectedCount: selected,
      decidedCount: decided,
      pendingCount: pending,
      progressPercent: percent,
      progressPercentLabel: Math.floor(percent),
      progressTitle: title,
      specialistInputPercentLabel: Math.floor(specialistInputPercent),
      specialistInputTitle:
        selected > 0 ? `Advisor input: ${specialistInputValue}` : 'No text units',
    };
  }, [isTerminologyProject, terminologyPhase, textUnits]);

  const handleProjectAction = useCallback(() => {
    if (mutations.isProjectStatusSaving) {
      return;
    }
    if (status === 'OPEN' && pendingCount > 0) {
      setCloseRequestCopyStatus('idle');
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
    setCloseRequestCopyStatus('idle');
  }, []);

  const translatorCloseRequestMessage = useMemo(() => {
    const projectLabel = name?.trim() || `Review project #${projectId}`;
    const localeLabel = locale?.bcp47Tag?.trim() || 'unknown locale';
    return [
      'Hi, I need help closing a review project that is not 100% complete.',
      '*URL*',
      window.location.href,
      '*Project*',
      `${projectLabel} (${localeLabel})`,
      '*Progress*',
      `${pendingCount} pending text unit${pendingCount === 1 ? '' : 's'} (${decidedCount}/${selectedCount} ${progressDoneLabel})`,
      '*Request*',
      'Please close this project early if that is appropriate.',
    ].join('\n\n');
  }, [
    decidedCount,
    locale?.bcp47Tag,
    name,
    pendingCount,
    progressDoneLabel,
    projectId,
    selectedCount,
  ]);

  const translatorCloseRequestHtml = useMemo(() => {
    const projectLabel = name?.trim() || `Review project #${projectId}`;
    const localeLabel = locale?.bcp47Tag?.trim() || 'unknown locale';
    const escapeReportHtml = (value: string) =>
      value
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
    const escapedUrl = escapeReportHtml(window.location.href);
    return [
      '<div>',
      '<p>Hi, I need help closing a review project that is not 100% complete.</p>',
      `<p><strong>URL</strong><br><a href="${escapedUrl}">${escapedUrl}</a></p>`,
      `<p><strong>Project</strong><br>${escapeReportHtml(projectLabel)} (${escapeReportHtml(localeLabel)})</p>`,
      `<p><strong>Progress</strong><br>${pendingCount} pending text unit${
        pendingCount === 1 ? '' : 's'
      } (${decidedCount}/${selectedCount} ${progressDoneLabel})</p>`,
      '<p><strong>Request</strong><br>Please close this project early if that is appropriate.</p>',
      '</div>',
    ].join('');
  }, [
    decidedCount,
    locale?.bcp47Tag,
    name,
    pendingCount,
    progressDoneLabel,
    projectId,
    selectedCount,
  ]);

  const copyTranslatorCloseRequest = useCallback(async () => {
    try {
      if (typeof ClipboardItem !== 'undefined' && navigator.clipboard.write) {
        await navigator.clipboard.write([
          new ClipboardItem({
            'text/html': new Blob([translatorCloseRequestHtml], { type: 'text/html' }),
            'text/plain': new Blob([translatorCloseRequestMessage], { type: 'text/plain' }),
          }),
        ]);
      } else {
        await navigator.clipboard.writeText(translatorCloseRequestMessage);
      }
      setCloseRequestCopyStatus('copied');
    } catch {
      try {
        await navigator.clipboard.writeText(translatorCloseRequestMessage);
        setCloseRequestCopyStatus('copied');
      } catch {
        setCloseRequestCopyStatus('failed');
      }
    }
  }, [translatorCloseRequestHtml, translatorCloseRequestMessage]);

  useEffect(() => {
    if (!showDescription) {
      return;
    }
    setRequestNameDraft(name ?? '');
    setDescriptionDraft(description);
    setProjectTypeDraft(type);
    setDueDateDraft(toDateTimeLocalInputValue(dueDate));
    setRequestTeamDraftId(assignment?.teamId ?? null);
    setProjectDueDateDraft(toDateTimeLocalInputValue(dueDate));
    setAttachmentDrafts(requestAttachments);
    setAttachmentUploadQueue([]);
    setIsAttachmentUploading(false);
    setAttachmentUploadError(null);
    setRequestSaveError(null);
    setProjectDueDateSaveError(null);
  }, [assignment?.teamId, description, dueDate, name, requestAttachments, showDescription, type]);

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

  const closeProjectDueDateModal = useCallback(() => {
    if (mutations.isProjectDueDateSaving) {
      return;
    }
    setIsProjectDueDateModalOpen(false);
    setProjectDueDateDraft(toDateTimeLocalInputValue(dueDate));
    setProjectDueDateSaveError(null);
  }, [dueDate, mutations.isProjectDueDateSaving]);

  const openAssignmentModal = useCallback(() => {
    setAssignmentDraftPmUserId(assignment?.assignedPmUserId ?? null);
    setAssignmentDraftTranslatorUserId(assignment?.assignedTranslatorUserId ?? null);
    setAssignmentSaveError(null);
    setIsAssignmentModalOpen(true);
  }, [assignment?.assignedPmUserId, assignment?.assignedTranslatorUserId]);

  const closeAssignmentModal = useCallback(() => {
    if (mutations.isProjectAssignmentSaving) {
      return;
    }
    setIsAssignmentModalOpen(false);
    setAssignmentSaveError(null);
  }, [mutations.isProjectAssignmentSaving]);

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

      const preparedFiles = await Promise.all(
        Array.from(files).map(async (file) =>
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
      setAttachmentUploadQueue((current) => [...queueEntries, ...current]);

      const uploaded: string[] = [];
      const failed: string[] = [];

      for (const [index, preparedFile] of preparedFiles.entries()) {
        const file = preparedFile.file;
        const queueEntry = queueEntries[index];
        if (!queueEntry || !canUploadRequestAttachmentFile(file)) {
          if (!queueEntry) {
            continue;
          }
          failed.push(queueEntry.error ?? `Failed to upload ${file.name}`);
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
    [
      addAttachmentKeys,
      isAttachmentUploading,
      mutations.isProjectRequestSaving,
      optimizeImagesBeforeUpload,
    ],
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
    const dueDateIso = localDateTimeInputToIso(dueDateDraft);
    if (!dueDateIso) {
      setRequestSaveError('Due date is invalid.');
      return;
    }

    try {
      setRequestSaveError(null);
      await mutations.onRequestProjectRequestUpdate({
        name: trimmedName,
        notes: descriptionDraft,
        type: projectTypeDraft,
        dueDate: dueDateIso,
        screenshotImageIds: attachmentDrafts,
        teamId: requestTeamDraftId,
        updateTeam: requestTeamDraftId !== (assignment?.teamId ?? null),
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
    requestTeamDraftId,
    shouldReturnToReviewProjects,
    assignment?.teamId,
  ]);

  const saveProjectDueDate = useCallback(async () => {
    if (!canEditRequest) {
      return;
    }
    if (!projectDueDateDraft) {
      setProjectDueDateSaveError('Due date is required.');
      return;
    }
    const dueDateIso = localDateTimeInputToIso(projectDueDateDraft);
    if (!dueDateIso) {
      setProjectDueDateSaveError('Due date is invalid.');
      return;
    }

    try {
      setProjectDueDateSaveError(null);
      await mutations.onRequestProjectDueDateUpdate(dueDateIso);
      setIsProjectDueDateModalOpen(false);
    } catch (error) {
      setProjectDueDateSaveError(
        error instanceof Error ? error.message : 'Failed to update project due date.',
      );
    }
  }, [canEditRequest, mutations, projectDueDateDraft]);

  const saveProjectAssignment = useCallback(async () => {
    if (!canEditRequest) {
      return;
    }
    if (assignmentHasAssigneeWithoutTeam) {
      setAssignmentSaveError('Select a team before assigning people.');
      return;
    }

    try {
      setAssignmentSaveError(null);
      await mutations.onRequestProjectAssignmentUpdate({
        teamId: assignment?.teamId ?? null,
        assignedPmUserId: assignmentDraftPmUserId,
        assignedTranslatorUserId: assignmentDraftTranslatorUserId,
        note: null,
      });
      setIsAssignmentModalOpen(false);
    } catch (error) {
      setAssignmentSaveError(
        error instanceof Error ? error.message : 'Failed to update project assignment.',
      );
    }
  }, [
    assignment?.teamId,
    assignmentDraftPmUserId,
    assignmentDraftTranslatorUserId,
    assignmentHasAssigneeWithoutTeam,
    canEditRequest,
    mutations,
  ]);

  const handleReviewPending = useCallback(() => {
    setShowCloseWarning(false);
    onReviewPending();
  }, [onReviewPending]);
  const dueDateTooltip = getLocalAndUtcDateTimeTooltip(dueDate);

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
              {isTerminologyProject && terminologyPhase != null ? (
                <Pill className="review-project-page__header-pill">
                  {REVIEW_PROJECT_TERMINOLOGY_PHASE_LABELS[terminologyPhase]}
                </Pill>
              ) : null}
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
            <div
              className="review-project-page__header-progress review-project-page__header-progress--tooltip"
              data-tooltip={progressTitle}
              aria-label={progressTitle}
              tabIndex={0}
            >
              <span className="review-project-page__header-progress-label">
                {progressPercentLabel}%
              </span>
              <ProgressBar percent={progressPercent} />
            </div>
            {isTerminologyProject && terminologyPhase === 'PM_RESOLUTION' ? (
              <>
                <span className="review-project-page__header-dot">•</span>
                <span
                  className="review-project-page__header-progress-label review-project-page__header-progress-label--tooltip"
                  data-tooltip={specialistInputTitle}
                  aria-label={specialistInputTitle}
                  tabIndex={0}
                >
                  Advisor input {specialistInputPercentLabel}%
                </span>
              </>
            ) : null}
          </div>

          <div className="review-project-page__header-group review-project-page__header-group--meta">
            {canEditRequest ? (
              <button
                type="button"
                className="review-project-page__header-link"
                onClick={openAssignmentModal}
                title={`Team ${teamDisplayName}`}
              >
                Edit assignment
              </button>
            ) : null}
            {canEditRequest && requestId != null ? (
              <span className="review-project-page__header-dot" aria-hidden>
                •
              </span>
            ) : null}
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
            {requestId != null || canEditRequest ? (
              <span className="review-project-page__header-dot" aria-hidden>
                •
              </span>
            ) : null}
            {canEditRequest ? (
              <button
                type="button"
                className="review-project-page__header-link"
                onClick={() => {
                  setProjectDueDateDraft(toDateTimeLocalInputValue(dueDate));
                  setProjectDueDateSaveError(null);
                  setIsProjectDueDateModalOpen(true);
                }}
                title={
                  dueDateTooltip
                    ? `Edit this project due date only\n${dueDateTooltip}`
                    : 'Edit this project due date only'
                }
              >
                Due {formatDate(dueDate)}
              </button>
            ) : (
              <span title={dueDateTooltip}>Due {formatDate(dueDate)}</span>
            )}
            {showShortcutsButton ? (
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
            ) : null}
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
        size={isTranslator ? 'xl' : 'md'}
        role="alertdialog"
        ariaLabel={isTranslator ? 'Project is not ready to close' : 'Close with pending items?'}
        className={
          isTranslator
            ? 'integrity-check-alert-modal integrity-check-alert-modal--report'
            : undefined
        }
      >
        {isTranslator ? (
          <>
            <div className="modal__header integrity-check-alert-modal__header">
              <div className="modal__title">Project is not ready to close</div>
            </div>
            <div className="integrity-check-alert-modal__body">
              <div className="integrity-check-alert-modal__error" role="alert">
                <p>
                  {pendingCount} text unit{pendingCount === 1 ? '' : 's'} still{' '}
                  {pendingCount === 1
                    ? pendingItemLabel
                    : pendingItemLabel.replace('needs', 'need')}{' '}
                  ({decidedCount}/{selectedCount} {progressDoneLabel}).
                </p>
                <p>You (as a translator) can only close projects when they are 100% complete.</p>
              </div>
              <section className="integrity-check-alert-modal__section integrity-check-alert-modal__section--report">
                <div className="integrity-check-alert-modal__section-header">
                  <p className="integrity-check-alert-modal__section-title">
                    If this project should be closed early, copy-paste this message to ask a
                    PM/admin for help.
                  </p>
                  <div className="integrity-check-alert-modal__copy-group">
                    {closeRequestCopyStatus === 'copied' ? (
                      <div className="integrity-check-alert-modal__copy-status">Copied.</div>
                    ) : null}
                    {closeRequestCopyStatus === 'failed' ? (
                      <div className="integrity-check-alert-modal__copy-status integrity-check-alert-modal__copy-status--error">
                        Copy failed.
                      </div>
                    ) : null}
                    <button
                      type="button"
                      className="integrity-check-alert-modal__copy-button"
                      onClick={() => {
                        void copyTranslatorCloseRequest();
                      }}
                      aria-label="Copy close request"
                      title="Copy close request"
                    >
                      <svg
                        className="integrity-check-alert-modal__copy-icon"
                        viewBox="0 0 24 24"
                        aria-hidden="true"
                        focusable="false"
                      >
                        <rect x="8" y="8" width="11" height="13" rx="2" fill="none" />
                        <path d="M5 16V5a2 2 0 0 1 2-2h9" fill="none" />
                      </svg>
                    </button>
                  </div>
                </div>
                <div
                  className="integrity-check-alert-modal__report"
                  dangerouslySetInnerHTML={{ __html: translatorCloseRequestHtml }}
                />
              </section>
            </div>
          </>
        ) : (
          <>
            <div className="modal__title">Close with pending items?</div>
            <div className="modal__body">
              {pendingCount} text unit{pendingCount === 1 ? '' : 's'} still{' '}
              {pendingCount === 1 ? pendingItemLabel : pendingItemLabel.replace('needs', 'need')} (
              {decidedCount}/{selectedCount} {progressDoneLabel}). Close project anyway?
            </div>
          </>
        )}
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
          {!isTranslator ? (
            <button
              type="button"
              className="modal__button modal__button--danger"
              onClick={confirmCloseProject}
            >
              Close project
            </button>
          ) : null}
        </div>
      </Modal>
      <Modal
        open={isProjectDueDateModalOpen}
        size="sm"
        role="dialog"
        ariaLabel="Edit project due date"
      >
        <div className="modal__title">Edit project due date</div>
        <div className="modal__body">
          <label className="review-project-page__description-field">
            <span className="review-project-page__description-label">Project due date</span>
            <input
              className="review-project-page__description-input"
              type="datetime-local"
              value={projectDueDateDraft}
              onChange={(event) => setProjectDueDateDraft(event.target.value)}
              disabled={mutations.isProjectDueDateSaving}
            />
          </label>
          {projectDueDateSaveError ? (
            <div className="review-project-page__description-error">{projectDueDateSaveError}</div>
          ) : null}
        </div>
        <div className="modal__actions">
          <button
            type="button"
            className="modal__button"
            onClick={closeProjectDueDateModal}
            disabled={mutations.isProjectDueDateSaving}
          >
            Cancel
          </button>
          <button
            type="button"
            className="modal__button modal__button--primary"
            onClick={() => {
              void saveProjectDueDate();
            }}
            disabled={mutations.isProjectDueDateSaving}
          >
            {mutations.isProjectDueDateSaving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </Modal>
      <Modal
        open={isAssignmentModalOpen}
        size="md"
        role="dialog"
        ariaLabel="Edit project assignment"
        onClose={closeAssignmentModal}
        closeOnBackdrop={!mutations.isProjectAssignmentSaving}
      >
        <div className="modal__title">Edit assignment</div>
        <div className="modal__body review-project-page__description-modal-body">
          <div className="review-project-page__assignment-summary">
            <span>Project #{projectId}</span>
            {locale?.bcp47Tag ? <span>{locale.bcp47Tag}</span> : null}
          </div>
          <div className="review-project-page__description-two-up">
            <label className="review-project-page__description-field">
              <span className="review-project-page__description-label">{pmAssignmentLabel}</span>
              <SingleSelectDropdown<number>
                label={pmAssignmentLabel}
                className="review-project-page__description-select"
                options={assignmentPmOptions}
                value={assignmentDraftPmUserId}
                onChange={(next) => {
                  setAssignmentDraftPmUserId(next);
                  setAssignmentSaveError(null);
                }}
                noneLabel={`No ${pmAssignmentLabel.toLowerCase()}`}
                placeholder={
                  activeAssignmentTeamId == null
                    ? 'Select a team first'
                    : assignmentPmUsersQuery.isLoading
                      ? `Loading ${pmAssignmentLabel.toLowerCase()}s`
                      : `Select ${pmAssignmentLabel.toLowerCase()}`
                }
                buttonAriaLabel={`Select ${pmAssignmentLabel.toLowerCase()}`}
                disabled={
                  mutations.isProjectAssignmentSaving ||
                  activeAssignmentTeamId == null ||
                  assignmentPmUsersQuery.isLoading
                }
              />
            </label>
            <label className="review-project-page__description-field">
              <span className="review-project-page__description-label">
                {translatorAssignmentLabel}
              </span>
              <SingleSelectDropdown<number>
                label={translatorAssignmentLabel}
                className="review-project-page__description-select"
                options={assignmentTranslatorOptions}
                value={assignmentDraftTranslatorUserId}
                onChange={(next) => {
                  setAssignmentDraftTranslatorUserId(next);
                  setAssignmentSaveError(null);
                }}
                noneLabel={`No ${translatorAssignmentLabel.toLowerCase()}`}
                placeholder={
                  activeAssignmentTeamId == null
                    ? 'Select a team first'
                    : assignmentTranslatorUsersQuery.isLoading
                      ? `Loading ${translatorAssignmentLabel.toLowerCase()}s`
                      : `Select ${translatorAssignmentLabel.toLowerCase()}`
                }
                buttonAriaLabel={`Select ${translatorAssignmentLabel.toLowerCase()}`}
                disabled={
                  mutations.isProjectAssignmentSaving ||
                  activeAssignmentTeamId == null ||
                  assignmentTranslatorUsersQuery.isLoading
                }
              />
            </label>
          </div>
          {assignmentLoadError ? (
            <div className="review-project-page__description-error">{assignmentLoadError}</div>
          ) : null}
          {assignmentSaveError ? (
            <div className="review-project-page__description-error">{assignmentSaveError}</div>
          ) : null}
          <div className="review-project-page__assignment-history">
            <div className="review-project-page__description-label">Assignment history</div>
            {assignmentHistoryQuery.isLoading ? (
              <div className="review-project-page__description-hint">Loading history…</div>
            ) : assignmentHistoryQuery.error ? (
              <div className="review-project-page__description-error">
                {getErrorMessage(assignmentHistoryQuery.error, 'Failed to load assignment history')}
              </div>
            ) : assignmentHistoryQuery.data?.entries.length ? (
              <div className="review-project-page__assignment-history-list">
                {assignmentHistoryQuery.data.entries.slice(0, 6).map((entry) => (
                  <div className="review-project-page__assignment-history-row" key={entry.id}>
                    <div>
                      <strong>{getAssignmentHistoryEventLabel(entry.eventType)}</strong>
                      {entry.assignedTranslatorUsername ? (
                        <span> · {entry.assignedTranslatorUsername}</span>
                      ) : null}
                      {entry.note ? (
                        <div className="review-project-page__description-hint">{entry.note}</div>
                      ) : null}
                    </div>
                    <span className="review-project-page__description-hint">
                      {formatDateTime(entry.createdDate)}
                    </span>
                  </div>
                ))}
              </div>
            ) : (
              <div className="review-project-page__description-hint">
                No assignment history yet.
              </div>
            )}
          </div>
        </div>
        <div className="modal__actions">
          <button
            type="button"
            className="modal__button"
            onClick={closeAssignmentModal}
            disabled={mutations.isProjectAssignmentSaving}
          >
            Cancel
          </button>
          <button
            type="button"
            className="modal__button modal__button--primary"
            onClick={() => {
              void saveProjectAssignment();
            }}
            disabled={!canSaveAssignment}
          >
            {mutations.isProjectAssignmentSaving ? 'Saving…' : 'Save'}
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
                      options={REVIEW_PROJECT_TYPES.filter(
                        (option) =>
                          option !== 'UNKNOWN' &&
                          (option !== 'TERM_CANDIDATE' || projectTypeDraft === 'TERM_CANDIDATE'),
                      ).map((option) => ({
                        value: option,
                        label: REVIEW_PROJECT_TYPE_LABELS[option],
                      }))}
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
                    <span className="review-project-page__description-label">Request due date</span>
                    <input
                      className="review-project-page__description-input"
                      type="datetime-local"
                      value={dueDateDraft}
                      onChange={(event) => setDueDateDraft(event.target.value)}
                      disabled={
                        !canEditRequest || mutations.isProjectRequestSaving || isAttachmentUploading
                      }
                    />
                    {canEditRequest ? (
                      <span className="review-project-page__description-help">
                        Applies to all projects in this request.
                      </span>
                    ) : null}
                  </label>
                </div>
                <label className="review-project-page__description-field">
                  <span className="review-project-page__description-label">Team</span>
                  <SingleSelectDropdown<number>
                    label="Team"
                    className="review-project-page__description-select"
                    options={requestTeamOptions}
                    value={requestTeamDraftId}
                    onChange={(next) => {
                      setRequestTeamDraftId(next);
                      setRequestSaveError(null);
                    }}
                    noneLabel="No team"
                    placeholder={
                      teamsQuery.isLoading
                        ? 'Loading teams'
                        : hasRequestTeamOptions
                          ? 'Select team'
                          : 'No teams configured'
                    }
                    noResultsLabel="No teams found"
                    buttonAriaLabel="Select request team"
                    disabled={
                      !canEditRequest ||
                      mutations.isProjectRequestSaving ||
                      isAttachmentUploading ||
                      teamsQuery.isLoading
                    }
                  />
                  {requestTeamDraftId !== (assignment?.teamId ?? null) ? (
                    <span className="review-project-page__description-help">
                      Applies to all projects in this request and clears current project assignees.
                    </span>
                  ) : null}
                  {!teamsQuery.isLoading && !teamsQuery.error && !hasRequestTeamOptions ? (
                    <span className="review-project-page__description-help">
                      Create or enable a team in Settings before assigning this request.
                    </span>
                  ) : null}
                  {teamsQuery.error ? (
                    <span className="review-project-page__description-error">
                      {getErrorMessage(teamsQuery.error, 'Failed to load teams')}
                    </span>
                  ) : null}
                </label>
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
                    optimizeImages={optimizeImagesBeforeUpload}
                    onToggleOptimizeImages={setOptimizeImagesBeforeUpload}
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

function TerminologyMetadataPanel({
  term,
  glossaryTermHref,
  isTermCandidateProject,
  canEdit,
  isSaving,
  onSave,
}: {
  term: ApiReviewProjectTerminologyTerm;
  glossaryTermHref: string | null;
  isTermCandidateProject: boolean;
  canEdit: boolean;
  isSaving: boolean;
  onSave: (request: ApiReviewProjectTerminologyMetadataRequest) => void;
}) {
  const [isEditing, setIsEditing] = useState(false);
  const [definition, setDefinition] = useState(term.definition ?? '');
  const [rationale, setRationale] = useState(term.rationale ?? '');
  const [partOfSpeech, setPartOfSpeech] = useState(term.partOfSpeech ?? '');
  const [termType, setTermType] = useState(term.termType ?? 'GENERAL');
  const [enforcement, setEnforcement] = useState(term.enforcement ?? 'SOFT');
  const [doNotTranslate, setDoNotTranslate] = useState(Boolean(term.doNotTranslate));

  useEffect(() => {
    setDefinition(term.definition ?? '');
    setRationale(term.rationale ?? '');
    setPartOfSpeech(term.partOfSpeech ?? '');
    setTermType(term.termType ?? 'GENERAL');
    setEnforcement(term.enforcement ?? 'SOFT');
    setDoNotTranslate(Boolean(term.doNotTranslate));
    setIsEditing(false);
  }, [
    term.definition,
    term.doNotTranslate,
    term.enforcement,
    term.metadataId,
    term.partOfSpeech,
    term.rationale,
    term.termIndexCandidateId,
    term.termType,
  ]);

  const normalizedDefinition = normalizeOptional(definition) ?? '';
  const normalizedRationale = normalizeOptional(rationale) ?? '';
  const normalizedPartOfSpeech = normalizeOptional(partOfSpeech) ?? '';
  const isDirty =
    normalizedDefinition !== (term.definition?.trim() ?? '') ||
    normalizedRationale !== (term.rationale?.trim() ?? '') ||
    normalizedPartOfSpeech !== (term.partOfSpeech?.trim() ?? '') ||
    termType !== (term.termType ?? 'GENERAL') ||
    enforcement !== (term.enforcement ?? 'SOFT') ||
    doNotTranslate !== Boolean(term.doNotTranslate);
  const isCandidateTerm =
    isTermCandidateProject || term.status?.toLocaleUpperCase() === 'CANDIDATE';

  return (
    <div className="review-project-detail__field review-project-detail__terminology-panel">
      <div className="review-project-detail__label-row">
        <div className="review-project-detail__label">Term details</div>
        {canEdit ? (
          <button
            type="button"
            className="review-project-detail__actions-button review-project-detail__terminology-edit-button"
            onClick={() => setIsEditing((current) => !current)}
            disabled={isSaving}
          >
            {isEditing ? 'Cancel' : 'Edit details'}
          </button>
        ) : null}
      </div>

      <div className="review-project-detail__terminology-summary">
        <div className="review-project-detail__terminology-term">
          <span>Term</span>
          <strong>{term.source ?? '—'}</strong>
        </div>
        <div className="review-project-detail__terminology-facts">
          <div>
            <span>Status</span>
            <strong>{formatGlossaryMetadataValue(term.status) ?? '—'}</strong>
          </div>
          <div>
            <span>Type</span>
            <strong>{formatGlossaryMetadataValue(term.termType) ?? '—'}</strong>
          </div>
          <div>
            <span>POS</span>
            <strong>{formatGlossaryMetadataValue(term.partOfSpeech) ?? '—'}</strong>
          </div>
          <div>
            <span>Enforcement</span>
            <strong>{formatGlossaryMetadataValue(term.enforcement) ?? '—'}</strong>
          </div>
          <div>
            <span>Translation</span>
            <strong>{term.doNotTranslate ? 'Do not translate' : 'Translate'}</strong>
          </div>
        </div>
      </div>

      <div className="review-project-detail__terminology-meta">
        <div>
          <span>Glossary</span>
          <strong>{term.glossaryName ?? term.glossaryId ?? '—'}</strong>
        </div>
        <div>
          <span>Candidate review</span>
          <strong>{formatGlossaryMetadataValue(term.candidateReviewStatus) ?? '—'}</strong>
        </div>
        {glossaryTermHref && !isCandidateTerm ? (
          <Link className="review-project-detail__terminology-link" to={glossaryTermHref}>
            Open glossary term
          </Link>
        ) : null}
      </div>

      {isEditing ? (
        <div className="review-project-detail__terminology-editor">
          <label className="review-project-detail__terminology-edit-field">
            <span>Definition</span>
            <AutoTextarea
              className="review-project-detail__input review-project-detail__input--compact review-project-detail__input--autosize"
              value={definition}
              onChange={(event) => setDefinition(event.target.value)}
              rows={1}
              style={{ resize: 'none' }}
            />
          </label>
          <label className="review-project-detail__terminology-edit-field">
            <span>Rationale</span>
            <AutoTextarea
              className="review-project-detail__input review-project-detail__input--compact review-project-detail__input--autosize"
              value={rationale}
              onChange={(event) => setRationale(event.target.value)}
              rows={1}
              style={{ resize: 'none' }}
            />
          </label>
          <div className="review-project-detail__terminology-edit-grid">
            <label className="review-project-detail__terminology-edit-field">
              <span>Term type</span>
              <select
                className="review-project-detail__input review-project-detail__input--compact"
                value={termType}
                onChange={(event) => setTermType(event.target.value)}
              >
                {TERMINOLOGY_TERM_TYPES.map((option) => (
                  <option key={option} value={option}>
                    {formatGlossaryMetadataValue(option)}
                  </option>
                ))}
              </select>
            </label>
            <label className="review-project-detail__terminology-edit-field">
              <span>Enforcement</span>
              <select
                className="review-project-detail__input review-project-detail__input--compact"
                value={enforcement}
                onChange={(event) => setEnforcement(event.target.value)}
              >
                {TERMINOLOGY_ENFORCEMENTS.map((option) => (
                  <option key={option} value={option}>
                    {formatGlossaryMetadataValue(option)}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <label className="review-project-detail__terminology-edit-field">
            <span>Part of speech</span>
            <input
              className="review-project-detail__input review-project-detail__input--compact"
              value={partOfSpeech}
              onChange={(event) => setPartOfSpeech(event.target.value)}
            />
          </label>
          <label className="review-project-detail__terminology-checkbox">
            <input
              type="checkbox"
              checked={doNotTranslate}
              onChange={(event) => setDoNotTranslate(event.target.checked)}
            />
            <span>Do not translate</span>
          </label>
          <div className="review-project-detail__editor-actions">
            <button
              type="button"
              className="review-project-detail__actions-button review-project-detail__actions-button--primary"
              disabled={!isDirty || isSaving}
              onClick={() =>
                onSave({
                  definition: normalizedDefinition || null,
                  rationale: normalizedRationale || null,
                  partOfSpeech: normalizedPartOfSpeech || null,
                  termType,
                  enforcement,
                  doNotTranslate,
                })
              }
            >
              Save term details
            </button>
          </div>
        </div>
      ) : (
        <div className="review-project-detail__terminology-copy">
          {term.definition ? (
            <div className="review-project-detail__terminology-note">
              <span>Definition</span>
              <p>{term.definition}</p>
            </div>
          ) : null}
          {term.rationale ? (
            <div className="review-project-detail__terminology-note">
              <span>Rationale</span>
              <p>{term.rationale}</p>
            </div>
          ) : null}
        </div>
      )}
    </div>
  );
}

function TerminologyEvidencePanel({ term }: { term: ApiReviewProjectTerminologyTerm }) {
  const sources = term.sources ?? [];
  const examples = term.examples ?? [];
  const reviewChanged = term.candidateReviewChangedAt
    ? `Updated ${formatDateTime(term.candidateReviewChangedAt)}${
        term.candidateReviewChangedByUsername ? ` by ${term.candidateReviewChangedByUsername}` : ''
      }`
    : null;

  if (!term.candidateReviewRationale && sources.length === 0 && examples.length === 0) {
    return null;
  }

  return (
    <div className="review-project-detail__field review-project-detail__terminology-panel review-project-detail__terminology-panel--evidence">
      <div className="review-project-detail__label">Term evidence</div>
      {term.candidateReviewRationale ? (
        <div className="review-project-detail__terminology-note">
          <span>Review rationale</span>
          <p>{term.candidateReviewRationale}</p>
          {reviewChanged ? <small>{reviewChanged}</small> : null}
        </div>
      ) : null}
      {sources.length > 0 ? (
        <div className="review-project-detail__terminology-list">
          <span>Sources</span>
          {sources.map((source, index) => (
            <div key={source.id ?? index}>
              {source.sourceType ?? 'Source'}
              {source.sourceName ? ` · ${source.sourceName}` : ''}
              {source.sourceExternalId ? ` · ${source.sourceExternalId}` : ''}
            </div>
          ))}
        </div>
      ) : null}

      {examples.length > 0 ? (
        <div className="review-project-detail__terminology-list">
          <span>Examples</span>
          {examples.map((example, index) => {
            const matchedText = normalizeOptional(example.matchedText ?? '');
            const termText = normalizeOptional(term.source ?? '');
            const showMatchedText =
              matchedText != null &&
              matchedText.toLocaleLowerCase() !== termText?.toLocaleLowerCase();
            const location = [example.repositoryName, example.assetPath]
              .filter(Boolean)
              .join(' · ');
            const fallbackHeading = example.sourceText ? null : (matchedText ?? termText ?? 'Term');

            return (
              <div key={example.id ?? index}>
                {showMatchedText ? <strong>{matchedText}</strong> : null}
                {showMatchedText && location ? ` · ${location}` : location}
                {!showMatchedText && !location && fallbackHeading ? (
                  <strong>{fallbackHeading}</strong>
                ) : null}
                {example.sourceText ? <p>{example.sourceText}</p> : null}
              </div>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}

function TerminologyFeedbackList({
  feedbacks,
}: {
  feedbacks: NonNullable<ApiReviewProjectTextUnit['terminologyFeedbacks']>;
}) {
  if (feedbacks.length === 0) {
    return <div className="review-project-detail__value">No advisor input yet.</div>;
  }

  return (
    <div className="review-project-detail__feedback-list">
      {feedbacks.map((feedback) => {
        const recommendation = feedback.recommendation;
        const recommendationLabel = recommendation
          ? TERMINOLOGY_FEEDBACK_RECOMMENDATION_LABELS[recommendation]
          : 'No recommendation';
        const suggestionParts = [
          feedback.confidence != null ? `confidence ${feedback.confidence}/5` : null,
        ].filter(Boolean);

        return (
          <div key={feedback.id} className="review-project-detail__feedback-item">
            <div className="review-project-detail__feedback-header">
              <Pill>{recommendationLabel}</Pill>
              <span className="review-project-detail__feedback-reviewer">
                {feedback.reviewerUsername ?? 'Unknown reviewer'}
              </span>
              {feedback.lastModifiedDate ? (
                <span className="review-project-detail__feedback-date">
                  {formatDateTime(feedback.lastModifiedDate)}
                </span>
              ) : null}
            </div>
            {suggestionParts.length > 0 ? (
              <div className="review-project-detail__feedback-meta">
                {suggestionParts.join(' · ')}
              </div>
            ) : null}
            {feedback.notes ? (
              <div className="review-project-detail__feedback-notes">{feedback.notes}</div>
            ) : null}
          </div>
        );
      })}
    </div>
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

function ProgressBar({ percent }: { percent: number }) {
  return (
    <div className="review-project-page__header-progress-bar">
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
