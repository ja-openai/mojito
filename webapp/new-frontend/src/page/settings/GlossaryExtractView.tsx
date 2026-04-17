import type { ApiExtractedGlossaryCandidate, ApiPollableTask } from '../../api/glossaries';
import { NumericPresetDropdown } from '../../components/NumericPresetDropdown';
import { RepositoryMultiSelect } from '../../components/RepositoryMultiSelect';

type RepositoryOption = {
  id: number;
  name: string;
};

type Props = {
  extractRepositoryOptions: RepositoryOption[];
  extractRepositoryIds: number[];
  onChangeExtractRepositoryIds: (next: number[]) => void;
  extractMinOccurrences: string;
  onChangeExtractMinOccurrences: (value: string) => void;
  onRunExtraction: () => void;
  isExtracting: boolean;
  canRunExtraction: boolean;
  extractTask: ApiPollableTask | null;
  extractedCandidates: ApiExtractedGlossaryCandidate[];
  extractLimit: number;
  onChangeExtractLimit: (value: number) => void;
  limitPresetOptions: number[];
  allExtractedCandidatesSelected: boolean;
  onToggleSelectAllExtracted: () => void;
  selectedExtractedCandidatesCount: number;
  onAddSelectedExtracted: () => void;
  isAddingSelected: boolean;
  selectedExtractedCandidateTerms: string[];
  onToggleExtractedCandidate: (term: string, checked: boolean) => void;
  onOpenCandidateModal: (candidate: ApiExtractedGlossaryCandidate) => void;
  onAddCandidate: (candidate: ApiExtractedGlossaryCandidate) => void;
};

export function GlossaryExtractView({
  extractRepositoryOptions,
  extractRepositoryIds,
  onChangeExtractRepositoryIds,
  extractMinOccurrences,
  onChangeExtractMinOccurrences,
  onRunExtraction,
  isExtracting,
  canRunExtraction,
  extractTask,
  extractedCandidates,
  extractLimit,
  onChangeExtractLimit,
  limitPresetOptions,
  allExtractedCandidatesSelected,
  onToggleSelectAllExtracted,
  selectedExtractedCandidatesCount,
  onAddSelectedExtracted,
  isAddingSelected,
  selectedExtractedCandidateTerms,
  onToggleExtractedCandidate,
  onOpenCandidateModal,
  onAddCandidate,
}: Props) {
  const statusText =
    extractTask && isExtracting
      ? `Extraction job ${extractTask.id} is running. Polling for results…`
      : extractedCandidates.length > 0
        ? `${extractedCandidates.length} candidates loaded`
        : 'Run extraction to load candidates.';

  return (
    <div className="glossary-term-admin__editor-page glossary-term-admin__extract-page">
      <section className="glossary-term-admin__section">
        <div className="glossary-term-admin__extract-controls">
          <div className="settings-field">
            <label className="settings-field__label">Source repositories</label>
            <RepositoryMultiSelect
              label="Source repositories"
              options={extractRepositoryOptions}
              selectedIds={extractRepositoryIds}
              onChange={onChangeExtractRepositoryIds}
              className="settings-repository-select glossary-term-admin__extract-repository"
              buttonAriaLabel="Select repositories for glossary extraction"
            />
          </div>
          <div className="settings-field">
            <label className="settings-field__label">Min occurrences</label>
            <input
              type="number"
              className="settings-input"
              value={extractMinOccurrences}
              onChange={(event) => onChangeExtractMinOccurrences(event.target.value)}
            />
          </div>
          <div className="glossary-term-admin__extract-primary-action">
            <button
              type="button"
              className="settings-button settings-button--primary"
              onClick={onRunExtraction}
              disabled={!canRunExtraction}
            >
              {isExtracting ? 'Extracting…' : 'Run extraction'}
            </button>
          </div>
        </div>

        <div className="glossary-term-admin__subbar glossary-term-admin__extract-subbar">
          <div className="settings-hint glossary-term-admin__subbar-meta">{statusText}</div>
          <div className="glossary-term-admin__subbar-actions">
            <NumericPresetDropdown
              value={extractLimit}
              buttonLabel={`Limit: ${extractLimit}`}
              menuLabel="Candidate limit"
              presetOptions={limitPresetOptions.map((size) => ({
                value: size,
                label: String(size),
              }))}
              onChange={onChangeExtractLimit}
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
              onClick={onToggleSelectAllExtracted}
              disabled={extractedCandidates.length === 0}
            >
              {allExtractedCandidatesSelected ? 'Clear selection' : 'Select all'}
            </button>
            <span className="glossary-term-admin__subbar-separator" aria-hidden="true">
              ·
            </span>
            <button
              type="button"
              className="glossary-term-admin__subbar-button"
              onClick={onAddSelectedExtracted}
              disabled={isAddingSelected || selectedExtractedCandidatesCount === 0}
            >
              {isAddingSelected ? 'Adding…' : `Add selected (${selectedExtractedCandidatesCount})`}
            </button>
          </div>
        </div>
      </section>

      <section className="glossary-term-admin__section">
        {extractedCandidates.length === 0 ? (
          <p className="settings-hint">No candidates loaded yet.</p>
        ) : (
          <div className="glossary-term-admin__extract-results">
            {extractedCandidates.map((candidate) => (
              <article key={candidate.term} className="glossary-term-admin__candidate-card">
                <div className="glossary-term-admin__candidate-top">
                  <label className="glossary-term-admin__candidate-select">
                    <input
                      type="checkbox"
                      checked={selectedExtractedCandidateTerms.includes(candidate.term)}
                      onChange={(event) =>
                        onToggleExtractedCandidate(candidate.term, event.target.checked)
                      }
                      disabled={candidate.existingInGlossary}
                      aria-label={`Select extracted candidate ${candidate.term}`}
                    />
                    <div>
                      <div className="glossary-term-admin__candidate-term">{candidate.term}</div>
                      <div className="glossary-term-admin__candidate-meta">
                        {candidate.occurrenceCount} hits • {candidate.repositoryCount} repositories
                        {' • '}
                        {candidate.suggestedTermType} • {candidate.confidence}% confidence
                      </div>
                    </div>
                  </label>
                  <div className="glossary-term-admin__candidate-actions">
                    {candidate.existingInGlossary ? (
                      <span className="glossary-term-admin__pill">Already in glossary</span>
                    ) : (
                      <>
                        <button
                          type="button"
                          className="settings-button settings-button--ghost glossary-term-admin__candidate-action"
                          onClick={() => onOpenCandidateModal(candidate)}
                        >
                          Review
                        </button>
                        <button
                          type="button"
                          className="settings-button settings-button--primary glossary-term-admin__candidate-action"
                          onClick={() => onAddCandidate(candidate)}
                          disabled={isAddingSelected}
                        >
                          Add
                        </button>
                      </>
                    )}
                  </div>
                </div>
                <div className="glossary-term-admin__candidate-repos">
                  {candidate.repositories.map((repository) => (
                    <span key={repository} className="glossary-term-admin__pill">
                      {repository}
                    </span>
                  ))}
                </div>
                {candidate.rationale ? (
                  <p className="settings-hint">{candidate.rationale}</p>
                ) : null}
                <div className="glossary-term-admin__candidate-samples">
                  {candidate.sampleSources.map((sample) => (
                    <div key={sample} className="glossary-term-admin__candidate-sample">
                      {sample}
                    </div>
                  ))}
                </div>
              </article>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
