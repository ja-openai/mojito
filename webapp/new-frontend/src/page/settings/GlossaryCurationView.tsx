import type { KeyboardEvent, MouseEvent } from 'react';
import { Link } from 'react-router-dom';

import type {
  ApiGlossaryTermIndexSuggestion,
  ApiGlossaryTermIndexSuggestionGlossaryPresenceFilter,
  ApiGlossaryTermIndexSuggestionReviewStatusFilter,
} from '../../api/glossaries';
import { NumericPresetDropdown } from '../../components/NumericPresetDropdown';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import { formatLocalDateTime } from '../../utils/dateTime';

type SuggestionPendingAction = 'ACCEPT' | 'IGNORE';
type LimitPresetOption = {
  value: number;
  label: string;
};

type Props = {
  searchDraft: string;
  onChangeSearch: (value: string) => void;
  reviewStatusFilter: ApiGlossaryTermIndexSuggestionReviewStatusFilter;
  onChangeReviewStatusFilter: (value: ApiGlossaryTermIndexSuggestionReviewStatusFilter) => void;
  glossaryPresenceFilter: ApiGlossaryTermIndexSuggestionGlossaryPresenceFilter;
  onChangeGlossaryPresenceFilter: (
    value: ApiGlossaryTermIndexSuggestionGlossaryPresenceFilter,
  ) => void;
  hasPendingSearchChanges: boolean;
  onRefreshSuggestions: () => void;
  isLoading: boolean;
  suggestions: ApiGlossaryTermIndexSuggestion[];
  totalCount: number;
  hasSearched: boolean;
  errorMessage: string | null;
  suggestionLimit: number;
  suggestionLimitLabel: string;
  onChangeSuggestionLimit: (value: number) => void;
  limitPresetOptions: LimitPresetOption[];
  selectedSuggestionIds: number[];
  onToggleSuggestion: (termIndexCandidateId: number, checked: boolean) => void;
  allSuggestionsSelected: boolean;
  onToggleSelectAll: () => void;
  onAcceptSelected: () => void;
  isAcceptingSelected: boolean;
  activeSuggestionId: number | null;
  onActivateSuggestion: (suggestion: ApiGlossaryTermIndexSuggestion) => void;
  onAcceptSuggestion: (suggestion: ApiGlossaryTermIndexSuggestion) => void;
  onIgnoreSuggestion: (suggestion: ApiGlossaryTermIndexSuggestion) => void;
  pendingSuggestionActions: Record<number, SuggestionPendingAction>;
};

type SuggestionDetailProps = {
  suggestion: ApiGlossaryTermIndexSuggestion;
  onOpenSuggestion: (suggestion: ApiGlossaryTermIndexSuggestion) => void;
  onAcceptSuggestion: (suggestion: ApiGlossaryTermIndexSuggestion) => void;
  onIgnoreSuggestion: (suggestion: ApiGlossaryTermIndexSuggestion) => void;
  pendingAction?: SuggestionPendingAction;
  isAcceptingSelected: boolean;
};

const formatMethod = (method: string | null | undefined) =>
  (method || 'UNKNOWN')
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');

const getRawTermExplorerPath = (entryId: number, search: string) => {
  const params = new URLSearchParams({ entryId: String(entryId) });
  const trimmedSearch = search.trim();
  if (trimmedSearch) {
    params.set('search', trimmedSearch);
  }
  return `/settings/system/glossary-term-index/terms?${params.toString()}`;
};

const getSuggestionOrigin = (suggestion: ApiGlossaryTermIndexSuggestion) => {
  const sourceType = suggestion.sources[0]?.sourceType?.toUpperCase();
  switch (sourceType) {
    case 'EXTRACTION':
      return { label: 'Extracted', className: 'is-extracted' };
    case 'HUMAN':
    case 'CODEX':
      return { label: 'Manual', className: 'is-manual' };
    case 'EXTERNAL':
      return { label: 'Imported', className: 'is-manual' };
    case 'AI':
      return { label: 'AI', className: 'is-manual' };
    default:
      return suggestion.termIndexExtractedTermId != null
        ? { label: 'Extracted', className: 'is-extracted' }
        : { label: 'Manual', className: 'is-manual' };
  }
};

const getCandidateCreatedDate = (suggestion: ApiGlossaryTermIndexSuggestion) =>
  suggestion.sources.find((source) => source.id === suggestion.termIndexCandidateId)?.createdDate ??
  suggestion.sources[0]?.createdDate ??
  null;

const getSuggestionReviewStatusValue = (suggestion: ApiGlossaryTermIndexSuggestion) => {
  return suggestion.reviewStatus;
};

const getSuggestionGlossaryPresenceValue = (suggestion: ApiGlossaryTermIndexSuggestion) => {
  return suggestion.glossaryPresence;
};

const canAcceptSuggestion = (suggestion: ApiGlossaryTermIndexSuggestion) =>
  getSuggestionGlossaryPresenceValue(suggestion) === 'NOT_IN_GLOSSARY';

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

const getSuggestionIndexFromTarget = (target: EventTarget | null) => {
  if (!(target instanceof HTMLElement)) {
    return -1;
  }

  const row = target.closest<HTMLElement>('[data-suggestion-index]');
  if (row?.dataset.suggestionIndex == null) {
    return -1;
  }

  const index = Number(row.dataset.suggestionIndex);
  return Number.isInteger(index) ? index : -1;
};

const formatReviewStatus = (suggestion: ApiGlossaryTermIndexSuggestion) => {
  switch (getSuggestionReviewStatusValue(suggestion)) {
    case 'NEW':
      return 'To review';
    case 'IGNORED':
      return 'Ignored';
    case 'ACCEPTED':
      return 'Accepted';
    default:
      return null;
  }
};

const formatCandidateReviewStatusValue = (suggestion: ApiGlossaryTermIndexSuggestion) => {
  switch (suggestion.candidateReviewStatus) {
    case 'TO_REVIEW':
      return 'To review';
    case 'ACCEPTED':
      return 'Accepted';
    case 'REJECTED':
      return 'Rejected';
    default:
      return suggestion.candidateReviewStatus ?? null;
  }
};

const formatCandidateReviewChange = (suggestion: ApiGlossaryTermIndexSuggestion) => {
  if (suggestion.candidateReviewAuthority === 'NONE') {
    return 'Not reviewed';
  }

  const actor =
    suggestion.candidateReviewAuthority === 'AI'
      ? 'AI'
      : suggestion.candidateReviewChangedByCommonName?.trim() ||
        suggestion.candidateReviewChangedByUsername?.trim() ||
        formatCandidateReviewAuthority(suggestion.candidateReviewAuthority);
  const changedAt = suggestion.candidateReviewChangedAt
    ? ` on ${formatLocalDateTime(suggestion.candidateReviewChangedAt)}`
    : '';

  return `Review by ${actor}${changedAt}`;
};

const formatCandidateReviewAuthority = (authority: string | null | undefined) => {
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
};

const getGlossaryPresence = (suggestion: ApiGlossaryTermIndexSuggestion) => {
  switch (getSuggestionGlossaryPresenceValue(suggestion)) {
    case 'LINKED':
      return { label: 'Linked in glossary', className: 'is-in-glossary' };
    case 'EXISTING_TERM':
      return { label: 'Source in glossary', className: 'is-in-glossary' };
    default:
      return { label: 'Not in glossary', className: 'is-not-in-glossary' };
  }
};

const renderSourceWithHighlight = (example: ApiGlossaryTermIndexSuggestion['examples'][number]) => {
  const sourceText = example.sourceText ?? '';
  if (
    sourceText.length === 0 ||
    example.startIndex == null ||
    example.endIndex == null ||
    example.startIndex < 0 ||
    example.endIndex <= example.startIndex ||
    example.endIndex > sourceText.length
  ) {
    return sourceText;
  }

  return (
    <>
      {sourceText.slice(0, example.startIndex)}
      <mark>{sourceText.slice(example.startIndex, example.endIndex)}</mark>
      {sourceText.slice(example.endIndex)}
    </>
  );
};

const REVIEW_STATUS_FILTER_OPTIONS: Array<{
  value: ApiGlossaryTermIndexSuggestionReviewStatusFilter;
  label: string;
  helper: string;
}> = [
  {
    value: 'NEW',
    label: 'To review',
    helper: 'Show candidates that have not been accepted or ignored for this glossary.',
  },
  {
    value: 'IGNORED',
    label: 'Ignored',
    helper: 'Show candidates previously ignored for this glossary.',
  },
  {
    value: 'ACCEPTED',
    label: 'Accepted',
    helper: 'Show candidates accepted from this queue.',
  },
  {
    value: 'REVIEWED',
    label: 'Reviewed',
    helper: 'Show ignored and accepted candidates.',
  },
  {
    value: 'ALL',
    label: 'All',
    helper: 'Show new and reviewed candidates together.',
  },
];

const GLOSSARY_PRESENCE_FILTER_OPTIONS: Array<{
  value: ApiGlossaryTermIndexSuggestionGlossaryPresenceFilter;
  label: string;
  helper: string;
}> = [
  {
    value: 'ALL',
    label: 'All',
    helper: 'Show candidates regardless of glossary membership.',
  },
  {
    value: 'IN_GLOSSARY',
    label: 'In glossary',
    helper: 'Show candidates already represented in this glossary.',
  },
  {
    value: 'NOT_IN_GLOSSARY',
    label: 'Not in glossary',
    helper: 'Show candidates not represented in this glossary.',
  },
];

export function GlossaryCurationView({
  searchDraft,
  onChangeSearch,
  reviewStatusFilter,
  onChangeReviewStatusFilter,
  glossaryPresenceFilter,
  onChangeGlossaryPresenceFilter,
  hasPendingSearchChanges,
  onRefreshSuggestions,
  isLoading,
  suggestions,
  totalCount,
  hasSearched,
  errorMessage,
  suggestionLimit,
  suggestionLimitLabel,
  onChangeSuggestionLimit,
  limitPresetOptions,
  selectedSuggestionIds,
  onToggleSuggestion,
  allSuggestionsSelected,
  onToggleSelectAll,
  onAcceptSelected,
  isAcceptingSelected,
  activeSuggestionId,
  onActivateSuggestion,
  onAcceptSuggestion,
  onIgnoreSuggestion,
  pendingSuggestionActions,
}: Props) {
  const selectedCount = selectedSuggestionIds.length;
  const selectableSuggestionCount = suggestions.filter(canAcceptSuggestion).length;
  const statusText = isLoading
    ? 'Loading candidates...'
    : !hasSearched
      ? 'Loading candidates for this glossary.'
      : hasPendingSearchChanges
        ? 'Search text changed; updating candidates.'
        : totalCount > suggestions.length
          ? `${suggestions.length.toLocaleString()} candidates shown from ${totalCount.toLocaleString()} stored candidates`
          : `${suggestions.length.toLocaleString()} candidates shown`;
  const activeSuggestionIndex =
    activeSuggestionId == null
      ? -1
      : suggestions.findIndex(
          (suggestion) => suggestion.termIndexCandidateId === activeSuggestionId,
        );

  const findNextSuggestionIndex = (startIndex: number, direction: 1 | -1) => {
    const nextIndex = startIndex + direction;
    return nextIndex >= 0 && nextIndex < suggestions.length ? nextIndex : -1;
  };

  const activateSuggestionAtIndex = (index: number) => {
    const suggestion = suggestions[index];
    if (suggestion) {
      onActivateSuggestion(suggestion);
    }
  };

  const openSuggestionAtIndex = (index: number) => {
    const suggestion = suggestions[index];
    if (suggestion) {
      onActivateSuggestion(suggestion);
    }
  };

  const toggleSuggestionSelection = (suggestion: ApiGlossaryTermIndexSuggestion) => {
    if (!canAcceptSuggestion(suggestion)) {
      return;
    }

    onToggleSuggestion(
      suggestion.termIndexCandidateId,
      !selectedSuggestionIds.includes(suggestion.termIndexCandidateId),
    );
  };

  const handleSuggestionResultsKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') {
      return;
    }
    if (isTextEntryTarget(event.target)) {
      return;
    }

    const direction = event.key === 'ArrowDown' ? 1 : -1;
    const focusedIndex = getSuggestionIndexFromTarget(event.target);
    const currentIndex =
      focusedIndex >= 0
        ? focusedIndex
        : activeSuggestionIndex >= 0
          ? activeSuggestionIndex
          : direction === 1
            ? -1
            : suggestions.length;
    const nextIndex = findNextSuggestionIndex(currentIndex, direction);
    if (nextIndex < 0) {
      return;
    }

    event.preventDefault();
    activateSuggestionAtIndex(nextIndex);
    event.currentTarget
      .querySelector<HTMLElement>(`[data-suggestion-index="${nextIndex}"]`)
      ?.focus();
  };

  const handleSuggestionRowClick = (
    event: MouseEvent<HTMLElement>,
    suggestion: ApiGlossaryTermIndexSuggestion,
  ) => {
    if (isInteractiveTarget(event.target)) {
      return;
    }

    event.currentTarget.focus();
    onActivateSuggestion(suggestion);
  };

  const handleSuggestionRowKeyDown = (
    event: KeyboardEvent<HTMLElement>,
    suggestion: ApiGlossaryTermIndexSuggestion,
    isRowBusy: boolean,
  ) => {
    if (isInteractiveTarget(event.target)) {
      return;
    }
    if (event.altKey || event.ctrlKey || event.metaKey) {
      return;
    }

    const key = event.key.toLowerCase();
    if (event.key === 'Enter') {
      event.preventDefault();
      onActivateSuggestion(suggestion);
      openSuggestionAtIndex(suggestions.indexOf(suggestion));
      return;
    }
    if (!canAcceptSuggestion(suggestion)) {
      return;
    }
    if (event.key === ' ') {
      event.preventDefault();
      toggleSuggestionSelection(suggestion);
      return;
    }
    if (key === 'a' && !isRowBusy) {
      event.preventDefault();
      onAcceptSuggestion(suggestion);
      return;
    }
    if (key === 'i' && !isRowBusy && getSuggestionReviewStatusValue(suggestion) === 'NEW') {
      event.preventDefault();
      onIgnoreSuggestion(suggestion);
    }
  };

  return (
    <div className="glossary-term-admin__editor-page glossary-term-admin__extract-page">
      <div className="glossary-term-admin__toolbar glossary-term-admin__curation-toolbar">
        <div className="glossary-term-admin__search">
          <input
            type="search"
            className="settings-input"
            placeholder="Filter by term, definition, or source"
            value={searchDraft}
            onChange={(event) => onChangeSearch(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                onRefreshSuggestions();
              }
            }}
          />
        </div>
        <div className="glossary-term-admin__toolbar-row">
          <SingleSelectDropdown
            label="Review"
            options={REVIEW_STATUS_FILTER_OPTIONS}
            value={reviewStatusFilter}
            onChange={(value) => onChangeReviewStatusFilter(value ?? 'NEW')}
            searchable={false}
            buttonAriaLabel="Select candidate review state"
            className="glossary-term-admin__state-filter"
          />
          <SingleSelectDropdown
            label="Glossary"
            options={GLOSSARY_PRESENCE_FILTER_OPTIONS}
            value={glossaryPresenceFilter}
            onChange={(value) => onChangeGlossaryPresenceFilter(value ?? 'ALL')}
            searchable={false}
            buttonAriaLabel="Select candidate glossary membership"
            className="glossary-term-admin__state-filter"
          />
        </div>
      </div>

      <div className="glossary-term-admin__subbar glossary-term-admin__extract-subbar">
        <div className="settings-hint glossary-term-admin__subbar-meta">{statusText}</div>
        <div className="glossary-term-admin__subbar-actions">
          <button
            type="button"
            className="glossary-term-admin__subbar-button"
            onClick={onRefreshSuggestions}
            disabled={isLoading}
          >
            {isLoading ? 'Refreshing...' : 'Refresh candidates'}
          </button>
          <span className="glossary-term-admin__subbar-separator" aria-hidden="true">
            ·
          </span>
          <NumericPresetDropdown
            value={suggestionLimit}
            buttonLabel={`Limit: ${suggestionLimitLabel}`}
            menuLabel="Candidate limit"
            presetOptions={limitPresetOptions}
            onChange={onChangeSuggestionLimit}
            ariaLabel="Candidate limit"
            className="glossary-term-admin__subbar-dropdown"
            buttonClassName="glossary-term-admin__subbar-button glossary-term-admin__subbar-button--dropdown"
          />
          <span className="glossary-term-admin__subbar-separator" aria-hidden="true">
            ·
          </span>
          <button
            type="button"
            className="glossary-term-admin__subbar-button"
            onClick={onToggleSelectAll}
            disabled={selectableSuggestionCount === 0}
          >
            {allSuggestionsSelected ? 'Clear selection' : 'Select all'}
          </button>
          <span className="glossary-term-admin__subbar-separator" aria-hidden="true">
            ·
          </span>
          <button
            type="button"
            className="glossary-term-admin__subbar-button"
            onClick={onAcceptSelected}
            disabled={isAcceptingSelected || selectedCount === 0}
          >
            {isAcceptingSelected ? 'Accepting...' : `Accept selected (${selectedCount})`}
          </button>
        </div>
      </div>

      {errorMessage ? <p className="settings-hint is-error">{errorMessage}</p> : null}

      <section className="glossary-term-admin__section glossary-term-admin__curation-results-section">
        {!hasSearched ? (
          <p className="settings-hint">Loading candidates...</p>
        ) : suggestions.length === 0 && !isLoading ? (
          <p className="settings-hint">
            No candidates match these filters
            {reviewStatusFilter === 'ALL' && glossaryPresenceFilter === 'ALL'
              ? '.'
              : '. Try Review: All and Glossary: All to include every stored candidate.'}
          </p>
        ) : (
          <div
            className="glossary-term-admin__suggestion-list"
            role="listbox"
            aria-label="Glossary candidate suggestions"
            aria-multiselectable="true"
            onKeyDown={handleSuggestionResultsKeyDown}
          >
            <div className="glossary-term-admin__suggestion-list-header" aria-hidden="true">
              <span className="glossary-term-admin__suggestion-header-selection" />
              <span className="glossary-term-admin__suggestion-header-cell">Term</span>
              <span className="glossary-term-admin__suggestion-header-cell glossary-term-admin__suggestion-header-cell--numeric">
                Hits
              </span>
              <span className="glossary-term-admin__suggestion-header-cell glossary-term-admin__suggestion-header-cell--numeric">
                Repos
              </span>
              <span className="glossary-term-admin__suggestion-header-cell glossary-term-admin__suggestion-header-cell--numeric">
                Confidence
              </span>
              <span className="glossary-term-admin__suggestion-header-cell glossary-term-admin__suggestion-header-cell--status">
                Glossary
              </span>
              <span className="glossary-term-admin__suggestion-header-cell glossary-term-admin__suggestion-header-cell--status">
                Review
              </span>
            </div>
            {suggestions.map((suggestion, index) => {
              const canAccept = canAcceptSuggestion(suggestion);
              const reviewStatus = formatReviewStatus(suggestion);
              const pendingAction = pendingSuggestionActions[suggestion.termIndexCandidateId];
              const isRowBusy = isAcceptingSelected || pendingAction != null;
              const isActive = suggestion.termIndexCandidateId === activeSuggestionId;
              const origin = getSuggestionOrigin(suggestion);
              const glossaryPresence = getGlossaryPresence(suggestion);

              return (
                <div
                  key={suggestion.termIndexCandidateId}
                  className={`glossary-term-admin__suggestion-row${isActive ? ' is-selected' : ''}`}
                  role="option"
                  aria-selected={isActive}
                  data-suggestion-index={index}
                  tabIndex={isActive || (activeSuggestionIndex < 0 && index === 0) ? 0 : -1}
                  aria-label={`Term candidate ${suggestion.term}`}
                  onClick={(event) => handleSuggestionRowClick(event, suggestion)}
                  onFocus={() => onActivateSuggestion(suggestion)}
                  onKeyDown={(event) => handleSuggestionRowKeyDown(event, suggestion, isRowBusy)}
                >
                  <input
                    type="checkbox"
                    className="glossary-term-admin__suggestion-checkbox"
                    checked={selectedSuggestionIds.includes(suggestion.termIndexCandidateId)}
                    onClick={(event) => event.stopPropagation()}
                    onChange={(event) =>
                      onToggleSuggestion(suggestion.termIndexCandidateId, event.target.checked)
                    }
                    disabled={!canAccept}
                    aria-label={`Select term candidate ${suggestion.term}`}
                  />
                  <span className="glossary-term-admin__suggestion-name-cell">
                    <span className="glossary-term-admin__suggestion-term">{suggestion.term}</span>
                    <span className="glossary-term-admin__suggestion-inline-meta">
                      <span>#{suggestion.termIndexCandidateId}</span>
                      <span
                        className={`glossary-term-admin__suggestion-origin ${origin.className}`}
                      >
                        {origin.label}
                      </span>
                      {suggestion.extractedTermMatchCount > 1 ? (
                        <span title="This candidate source matches more than one extracted term in the current glossary repository scope.">
                          {suggestion.extractedTermMatchCount.toLocaleString()} matches
                        </span>
                      ) : null}
                    </span>
                  </span>
                  <span
                    className="glossary-term-admin__suggestion-number"
                    aria-label={`${suggestion.occurrenceCount.toLocaleString()} hits`}
                  >
                    <span>{suggestion.occurrenceCount.toLocaleString()}</span>
                    <span className="glossary-term-admin__suggestion-cell-label">hits</span>
                  </span>
                  <span
                    className="glossary-term-admin__suggestion-number"
                    aria-label={`${suggestion.repositoryCount.toLocaleString()} repositories`}
                  >
                    <span>{suggestion.repositoryCount.toLocaleString()}</span>
                    <span className="glossary-term-admin__suggestion-cell-label">repos</span>
                  </span>
                  <span
                    className="glossary-term-admin__suggestion-number"
                    aria-label={`${suggestion.confidence}% confidence`}
                  >
                    <span>{suggestion.confidence}%</span>
                    <span className="glossary-term-admin__suggestion-cell-label">conf</span>
                  </span>
                  <span className="glossary-term-admin__suggestion-status">
                    {glossaryPresence.label}
                  </span>
                  <span className="glossary-term-admin__suggestion-status">
                    {reviewStatus ?? 'Unknown'}
                  </span>
                </div>
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
}

export function GlossarySuggestionDetailView({
  suggestion,
  onOpenSuggestion,
  onAcceptSuggestion,
  onIgnoreSuggestion,
  pendingAction,
  isAcceptingSelected,
}: SuggestionDetailProps) {
  const canAccept = canAcceptSuggestion(suggestion);
  const origin = getSuggestionOrigin(suggestion);
  const glossaryPresence = getGlossaryPresence(suggestion);
  const candidateCreatedDate = getCandidateCreatedDate(suggestion);
  const reviewStatus = formatReviewStatus(suggestion);
  const candidateReviewStatus = formatCandidateReviewStatusValue(suggestion);
  const candidateReviewChange = formatCandidateReviewChange(suggestion);
  const isBusy = isAcceptingSelected || pendingAction != null;

  return (
    <div className="glossary-term-admin__suggestion-detail">
      <section className="glossary-term-admin__suggestion-summary">
        <div className="glossary-term-admin__suggestion-summary-header">
          <div className="glossary-term-admin__suggestion-title-group">
            <div className="glossary-term-admin__suggestion-eyebrow">
              Candidate #{suggestion.termIndexCandidateId}
              {suggestion.termIndexExtractedTermId != null ? (
                <>
                  {' · '}
                  <Link
                    to={getRawTermExplorerPath(
                      suggestion.termIndexExtractedTermId,
                      suggestion.term,
                    )}
                  >
                    Extracted term #{suggestion.termIndexExtractedTermId}
                  </Link>
                </>
              ) : null}
            </div>
            <h3 className="glossary-term-admin__suggestion-title">{suggestion.term}</h3>
            <div className="glossary-term-admin__suggestion-meta-line">
              <span className={`glossary-term-admin__suggestion-origin ${origin.className}`}>
                {origin.label}
              </span>
              <span>{glossaryPresence.label}</span>
              <span>{suggestion.occurrenceCount.toLocaleString()} hits</span>
              <span>{suggestion.repositoryCount.toLocaleString()} repositories</span>
              <span>{suggestion.confidence}% confidence</span>
              {candidateCreatedDate ? (
                <span>Created {formatLocalDateTime(candidateCreatedDate)}</span>
              ) : null}
            </div>
          </div>
          {canAccept ? (
            <div className="glossary-term-admin__suggestion-detail-actions">
              <button
                type="button"
                className="settings-button settings-button--ghost"
                onClick={() => onOpenSuggestion(suggestion)}
                disabled={isBusy}
              >
                Edit
              </button>
              <button
                type="button"
                className="settings-button settings-button--ghost"
                onClick={() => onIgnoreSuggestion(suggestion)}
                disabled={isBusy || getSuggestionReviewStatusValue(suggestion) !== 'NEW'}
              >
                {pendingAction === 'IGNORE' ? 'Ignoring...' : 'Ignore'}
              </button>
              <button
                type="button"
                className="settings-button settings-button--primary"
                onClick={() => onAcceptSuggestion(suggestion)}
                disabled={isBusy}
              >
                {pendingAction === 'ACCEPT' ? 'Accepting...' : 'Accept'}
              </button>
            </div>
          ) : null}
        </div>

        <dl className="glossary-term-admin__suggestion-facts">
          <div>
            <dt>Glossary review</dt>
            <dd>{reviewStatus ?? 'Unknown'}</dd>
          </div>
          <div>
            <dt>Candidate review</dt>
            <dd>{candidateReviewStatus ?? 'Unknown'}</dd>
          </div>
          <div>
            <dt>Review source</dt>
            <dd>{candidateReviewChange}</dd>
          </div>
          <div>
            <dt>Type</dt>
            <dd>{suggestion.suggestedTermType}</dd>
          </div>
          <div>
            <dt>Enforcement</dt>
            <dd>{suggestion.suggestedEnforcement}</dd>
          </div>
          <div>
            <dt>Translation</dt>
            <dd>{suggestion.suggestedDoNotTranslate ? 'Do not translate' : 'Translate'}</dd>
          </div>
          {suggestion.suggestedPartOfSpeech ? (
            <div>
              <dt>Part of speech</dt>
              <dd>{suggestion.suggestedPartOfSpeech}</dd>
            </div>
          ) : null}
          <div>
            <dt>Selection</dt>
            <dd>{formatMethod(suggestion.selectionMethod)}</dd>
          </div>
        </dl>

        {suggestion.definition ? (
          <div className="glossary-term-admin__suggestion-rationale">
            <span className="glossary-term-admin__suggestion-rationale-label">Definition</span>
            <p>{suggestion.definition}</p>
          </div>
        ) : null}
        {suggestion.rationale && suggestion.rationale !== suggestion.definition ? (
          <div className="glossary-term-admin__suggestion-rationale">
            <span className="glossary-term-admin__suggestion-rationale-label">Rationale</span>
            <p>{suggestion.rationale}</p>
          </div>
        ) : null}
        {suggestion.candidateReviewRationale ? (
          <div className="glossary-term-admin__suggestion-rationale">
            <span className="glossary-term-admin__suggestion-rationale-label">
              Review rationale
            </span>
            <p>{suggestion.candidateReviewRationale}</p>
          </div>
        ) : null}
      </section>

      <section className="glossary-term-admin__suggestion-examples">
        <div className="glossary-term-admin__suggestion-section-title">
          String usage
          {suggestion.examples.length > 0 ? (
            <span>{suggestion.examples.length.toLocaleString()} examples shown</span>
          ) : null}
        </div>
        {suggestion.examples.length === 0 ? (
          <p className="settings-hint">No string examples are linked to this candidate.</p>
        ) : (
          <div className="glossary-term-admin__suggestion-example-list">
            {suggestion.examples.map((example, index) => (
              <article
                key={`${example.id ?? 'searched'}-${example.tmTextUnitId}-${index}`}
                className="glossary-term-admin__suggestion-example"
              >
                <div className="glossary-term-admin__suggestion-example-meta">
                  <strong>{example.repositoryName}</strong>
                  {example.assetPath ? <span>{example.assetPath}</span> : null}
                  <span>Text unit #{example.tmTextUnitId}</span>
                  <span>{formatMethod(example.extractionMethod)}</span>
                  {example.confidence != null ? (
                    <span>{example.confidence}% confidence</span>
                  ) : null}
                </div>
                <div className="glossary-term-admin__suggestion-source">
                  {renderSourceWithHighlight(example)}
                </div>
              </article>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
