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
  onOpenSuggestion: (suggestion: ApiGlossaryTermIndexSuggestion) => void;
  onAcceptSuggestion: (suggestion: ApiGlossaryTermIndexSuggestion) => void;
  onIgnoreSuggestion: (suggestion: ApiGlossaryTermIndexSuggestion) => void;
  pendingSuggestionActions: Record<number, SuggestionPendingAction>;
};

const formatMethod = (method: string) =>
  method
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

const formatCandidateReviewStatus = (suggestion: ApiGlossaryTermIndexSuggestion) => {
  switch (suggestion.candidateReviewStatus) {
    case 'TO_REVIEW':
      return 'Candidate: To review';
    case 'ACCEPTED':
      return 'Candidate: Accepted';
    case 'REJECTED':
      return 'Candidate: Rejected';
    default:
      return suggestion.candidateReviewStatus
        ? `Candidate: ${suggestion.candidateReviewStatus}`
        : null;
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
  onOpenSuggestion,
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
    if (suggestion && canAcceptSuggestion(suggestion)) {
      onOpenSuggestion(suggestion);
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
    if (canAcceptSuggestion(suggestion)) {
      onOpenSuggestion(suggestion);
    }
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
            className="glossary-term-admin__extract-results"
            onKeyDown={handleSuggestionResultsKeyDown}
          >
            {suggestions.map((suggestion, index) => {
              const canAccept = canAcceptSuggestion(suggestion);
              const reviewStatus = formatReviewStatus(suggestion);
              const candidateReviewStatus = formatCandidateReviewStatus(suggestion);
              const pendingAction = pendingSuggestionActions[suggestion.termIndexCandidateId];
              const isRowBusy = isAcceptingSelected || pendingAction != null;
              const isActive = suggestion.termIndexCandidateId === activeSuggestionId;
              const origin = getSuggestionOrigin(suggestion);
              const glossaryPresence = getGlossaryPresence(suggestion);
              const candidateCreatedDate = getCandidateCreatedDate(suggestion);

              return (
                <article
                  key={suggestion.termIndexCandidateId}
                  className={`glossary-term-admin__candidate-card${
                    canAccept ? ' glossary-term-admin__candidate-card--clickable' : ''
                  }${isActive ? ' is-active' : ''}`}
                  data-suggestion-index={index}
                  tabIndex={0}
                  aria-label={`Term candidate ${suggestion.term}`}
                  aria-current={isActive ? 'true' : undefined}
                  onClick={(event) => handleSuggestionRowClick(event, suggestion)}
                  onFocus={() => onActivateSuggestion(suggestion)}
                  onKeyDown={(event) => handleSuggestionRowKeyDown(event, suggestion, isRowBusy)}
                >
                  <div className="glossary-term-admin__candidate-top">
                    <div className="glossary-term-admin__candidate-select">
                      <input
                        type="checkbox"
                        checked={selectedSuggestionIds.includes(suggestion.termIndexCandidateId)}
                        onChange={(event) =>
                          onToggleSuggestion(suggestion.termIndexCandidateId, event.target.checked)
                        }
                        disabled={!canAccept}
                        aria-label={`Select term candidate ${suggestion.term}`}
                      />
                      <div>
                        <div className="glossary-term-admin__candidate-title">
                          <div className="glossary-term-admin__candidate-term">
                            {suggestion.term}
                          </div>
                          <span
                            className={`glossary-term-admin__candidate-origin ${origin.className}`}
                          >
                            {origin.label}
                          </span>
                        </div>
                        <div className="glossary-term-admin__candidate-meta">
                          {suggestion.occurrenceCount.toLocaleString()} hits ·{' '}
                          {suggestion.repositoryCount.toLocaleString()} repositories ·{' '}
                          {suggestion.sourceCount.toLocaleString()} sources ·{' '}
                          {suggestion.confidence}% confidence ·{' '}
                          {candidateCreatedDate
                            ? `Created ${formatLocalDateTime(candidateCreatedDate)} · `
                            : ''}
                          {formatMethod(suggestion.selectionMethod)}
                        </div>
                      </div>
                    </div>
                    {canAccept ? (
                      <div className="glossary-term-admin__candidate-actions">
                        <button
                          type="button"
                          className="settings-button settings-button--ghost glossary-term-admin__candidate-action"
                          onClick={() => onIgnoreSuggestion(suggestion)}
                          disabled={
                            isRowBusy || getSuggestionReviewStatusValue(suggestion) !== 'NEW'
                          }
                        >
                          {pendingAction === 'IGNORE' ? 'Ignoring...' : 'Ignore'}
                        </button>
                        <button
                          type="button"
                          className="settings-button settings-button--primary glossary-term-admin__candidate-action"
                          onClick={() => onAcceptSuggestion(suggestion)}
                          disabled={isRowBusy}
                        >
                          {pendingAction === 'ACCEPT' ? 'Accepting...' : 'Accept'}
                        </button>
                      </div>
                    ) : null}
                  </div>
                  <div className="glossary-term-admin__candidate-repos">
                    <span className="glossary-term-admin__pill">
                      Candidate #{suggestion.termIndexCandidateId}
                    </span>
                    <span className="glossary-term-admin__pill">
                      {suggestion.suggestedTermType}
                    </span>
                    <span className="glossary-term-admin__pill">
                      {suggestion.suggestedEnforcement}
                    </span>
                    {suggestion.suggestedDoNotTranslate ? (
                      <span className="glossary-term-admin__pill">Do not translate</span>
                    ) : null}
                    {reviewStatus ? (
                      <span className="glossary-term-admin__pill">{reviewStatus}</span>
                    ) : null}
                    {candidateReviewStatus ? (
                      <span className="glossary-term-admin__pill">{candidateReviewStatus}</span>
                    ) : null}
                    <span
                      className={`glossary-term-admin__pill glossary-term-admin__glossary-presence ${glossaryPresence.className}`}
                    >
                      {glossaryPresence.label}
                    </span>
                    {suggestion.termIndexExtractedTermId != null ? (
                      <Link
                        className="glossary-term-admin__pill glossary-term-admin__pill-link"
                        to={getRawTermExplorerPath(
                          suggestion.termIndexExtractedTermId,
                          suggestion.term,
                        )}
                      >
                        Extracted term #{suggestion.termIndexExtractedTermId}
                      </Link>
                    ) : null}
                    {suggestion.extractedTermMatchCount > 1 ? (
                      <span
                        className="glossary-term-admin__pill glossary-term-admin__extracted-match is-ambiguous"
                        title="This candidate source matches more than one extracted term in the current glossary repository scope."
                      >
                        {suggestion.extractedTermMatchCount.toLocaleString()} extracted matches
                      </span>
                    ) : null}
                  </div>
                  {suggestion.definition ? (
                    <p className="settings-hint">{suggestion.definition}</p>
                  ) : null}
                  {suggestion.rationale && suggestion.rationale !== suggestion.definition ? (
                    <p className="settings-hint">Reason: {suggestion.rationale}</p>
                  ) : null}
                  {suggestion.examples.length > 0 ? (
                    <div className="glossary-term-admin__candidate-samples">
                      {suggestion.examples.slice(0, 3).map((example, index) => (
                        <div
                          key={`${example.id ?? 'searched'}-${example.tmTextUnitId}-${index}`}
                          className="glossary-term-admin__candidate-sample"
                        >
                          <strong>{example.repositoryName}</strong>: {example.sourceText}
                        </div>
                      ))}
                    </div>
                  ) : null}
                </article>
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
}
