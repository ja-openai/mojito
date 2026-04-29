import type {
  ApiGlossaryTermIndexSuggestion,
  ApiGlossaryTermIndexSuggestionReviewStateFilter,
} from '../../api/glossaries';
import { NumericPresetDropdown } from '../../components/NumericPresetDropdown';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';

type SuggestionPendingAction = 'ACCEPT' | 'IGNORE';

type Props = {
  searchDraft: string;
  onChangeSearch: (value: string) => void;
  reviewStateFilter: ApiGlossaryTermIndexSuggestionReviewStateFilter;
  onChangeReviewStateFilter: (value: ApiGlossaryTermIndexSuggestionReviewStateFilter) => void;
  hasPendingSearchChanges: boolean;
  onRefreshSuggestions: () => void;
  onOpenCreateCandidate: () => void;
  isLoading: boolean;
  suggestions: ApiGlossaryTermIndexSuggestion[];
  totalCount: number;
  hasSearched: boolean;
  errorMessage: string | null;
  suggestionLimit: number;
  onChangeSuggestionLimit: (value: number) => void;
  limitPresetOptions: number[];
  selectedSuggestionIds: number[];
  onToggleSuggestion: (termIndexCandidateId: number, checked: boolean) => void;
  allSuggestionsSelected: boolean;
  onToggleSelectAll: () => void;
  onAcceptSelected: () => void;
  isAcceptingSelected: boolean;
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

const canAcceptSuggestion = (suggestion: ApiGlossaryTermIndexSuggestion) =>
  suggestion.reviewState !== 'LINKED' && suggestion.reviewState !== 'EXISTING_TERM';

const formatReviewState = (suggestion: ApiGlossaryTermIndexSuggestion) => {
  switch (suggestion.reviewState) {
    case 'IGNORED':
      return 'Ignored';
    case 'LINKED':
      return 'Accepted';
    case 'EXISTING_TERM':
      return 'Already in glossary';
    default:
      return null;
  }
};

const REVIEW_STATE_FILTER_OPTIONS: Array<{
  value: ApiGlossaryTermIndexSuggestionReviewStateFilter;
  label: string;
  helper: string;
}> = [
  {
    value: 'NEW',
    label: 'To review',
    helper: 'Hide ignored, accepted, and existing glossary terms.',
  },
  {
    value: 'IGNORED',
    label: 'Ignored',
    helper: 'Show suggestions previously ignored for this glossary.',
  },
  {
    value: 'REVIEWED',
    label: 'Reviewed',
    helper: 'Show ignored, accepted, and existing glossary matches.',
  },
  {
    value: 'LINKED',
    label: 'Accepted',
    helper: 'Show raw terms accepted into this glossary.',
  },
  {
    value: 'EXISTING_TERM',
    label: 'Already in glossary',
    helper: 'Show raw terms that match an existing glossary term.',
  },
  {
    value: 'ALL',
    label: 'All',
    helper: 'Show new and reviewed suggestions together.',
  },
];

export function GlossaryCurationView({
  searchDraft,
  onChangeSearch,
  reviewStateFilter,
  onChangeReviewStateFilter,
  hasPendingSearchChanges,
  onRefreshSuggestions,
  onOpenCreateCandidate,
  isLoading,
  suggestions,
  totalCount,
  hasSearched,
  errorMessage,
  suggestionLimit,
  onChangeSuggestionLimit,
  limitPresetOptions,
  selectedSuggestionIds,
  onToggleSuggestion,
  allSuggestionsSelected,
  onToggleSelectAll,
  onAcceptSelected,
  isAcceptingSelected,
  onOpenSuggestion,
  onAcceptSuggestion,
  onIgnoreSuggestion,
  pendingSuggestionActions,
}: Props) {
  const selectedCount = selectedSuggestionIds.length;
  const selectableSuggestionCount = suggestions.filter(canAcceptSuggestion).length;
  const statusText = isLoading
    ? 'Loading suggestions...'
    : !hasSearched
      ? 'Loading suggestions for this glossary.'
      : hasPendingSearchChanges
        ? 'Search text changed; apply it to update suggestions.'
        : totalCount > suggestions.length
          ? `${suggestions.length.toLocaleString()} suggestions shown from ${totalCount.toLocaleString()} matching raw terms`
          : `${suggestions.length.toLocaleString()} suggestions shown`;
  const refreshButtonLabel = isLoading
    ? 'Loading...'
    : hasPendingSearchChanges
      ? 'Apply search'
      : 'Refresh';

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
            label="Review state"
            options={REVIEW_STATE_FILTER_OPTIONS}
            value={reviewStateFilter}
            onChange={(value) => onChangeReviewStateFilter(value ?? 'NEW')}
            searchable={false}
            buttonAriaLabel="Select suggestion review state"
            className="glossary-term-admin__state-filter"
          />
          <button
            type="button"
            className="settings-button settings-button--primary"
            onClick={onRefreshSuggestions}
            disabled={isLoading}
          >
            {refreshButtonLabel}
          </button>
          <button
            type="button"
            className="settings-button settings-button--ghost"
            onClick={onOpenCreateCandidate}
          >
            Add candidate
          </button>
        </div>
      </div>

      <div className="glossary-term-admin__subbar glossary-term-admin__extract-subbar">
        <div className="settings-hint glossary-term-admin__subbar-meta">{statusText}</div>
        <div className="glossary-term-admin__subbar-actions">
          <NumericPresetDropdown
            value={suggestionLimit}
            buttonLabel={`Limit: ${suggestionLimit}`}
            menuLabel="Suggestion limit"
            presetOptions={limitPresetOptions.map((size) => ({
              value: size,
              label: String(size),
            }))}
            onChange={onChangeSuggestionLimit}
            ariaLabel="Suggestion limit"
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
          <p className="settings-hint">Loading suggestions...</p>
        ) : suggestions.length === 0 && !isLoading ? (
          <p className="settings-hint">No suggestions match these filters.</p>
        ) : (
          <div className="glossary-term-admin__extract-results">
            {suggestions.map((suggestion) => {
              const canAccept = canAcceptSuggestion(suggestion);
              const reviewState = formatReviewState(suggestion);
              const pendingAction = pendingSuggestionActions[suggestion.termIndexCandidateId];
              const isRowBusy = isAcceptingSelected || pendingAction != null;

              return (
                <article
                  key={suggestion.termIndexCandidateId}
                  className="glossary-term-admin__candidate-card"
                >
                  <div className="glossary-term-admin__candidate-top">
                    <label className="glossary-term-admin__candidate-select">
                      <input
                        type="checkbox"
                        checked={selectedSuggestionIds.includes(suggestion.termIndexCandidateId)}
                        onChange={(event) =>
                          onToggleSuggestion(suggestion.termIndexCandidateId, event.target.checked)
                        }
                        disabled={!canAccept}
                        aria-label={`Select term suggestion ${suggestion.term}`}
                      />
                      <div>
                        <div className="glossary-term-admin__candidate-term">{suggestion.term}</div>
                        <div className="glossary-term-admin__candidate-meta">
                          {suggestion.occurrenceCount.toLocaleString()} hits ·{' '}
                          {suggestion.repositoryCount.toLocaleString()} repositories ·{' '}
                          {suggestion.sourceCount.toLocaleString()} sources ·{' '}
                          {suggestion.confidence}% confidence ·{' '}
                          {formatMethod(suggestion.selectionMethod)}
                        </div>
                      </div>
                    </label>
                    {canAccept ? (
                      <div className="glossary-term-admin__candidate-actions">
                        <button
                          type="button"
                          className="settings-button settings-button--ghost glossary-term-admin__candidate-action"
                          onClick={() => onOpenSuggestion(suggestion)}
                          disabled={isRowBusy}
                        >
                          Review
                        </button>
                        <button
                          type="button"
                          className="settings-button settings-button--ghost glossary-term-admin__candidate-action"
                          onClick={() => onIgnoreSuggestion(suggestion)}
                          disabled={isRowBusy || suggestion.reviewState !== 'NEW'}
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
                      {suggestion.suggestedTermType}
                    </span>
                    <span className="glossary-term-admin__pill">
                      {suggestion.suggestedEnforcement}
                    </span>
                    {suggestion.suggestedDoNotTranslate ? (
                      <span className="glossary-term-admin__pill">Do not translate</span>
                    ) : null}
                    {reviewState ? (
                      <span className="glossary-term-admin__pill">{reviewState}</span>
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
