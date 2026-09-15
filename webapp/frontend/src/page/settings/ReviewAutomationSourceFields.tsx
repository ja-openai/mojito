import type {
  ReviewAutomationIncidentScope,
  ReviewAutomationSource,
} from '../../api/review-automations';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';

export function ReviewAutomationSourceFields({
  reviewSource,
  incidentReviewType,
  incidentScope,
  onScopeChange,
  onSourceChange,
  onTypeChange,
}: {
  reviewSource: ReviewAutomationSource;
  incidentReviewType: string;
  incidentScope: ReviewAutomationIncidentScope;
  onScopeChange: (scope: ReviewAutomationIncidentScope) => void;
  onSourceChange: (source: ReviewAutomationSource) => void;
  onTypeChange: (reviewType: string) => void;
}) {
  return (
    <div className="settings-field">
      <label className="settings-field">
        <span className="settings-field__label">Review source</span>
        <select
          className="settings-input"
          value={reviewSource}
          onChange={(event) => onSourceChange(event.target.value as ReviewAutomationSource)}
        >
          <option value="CURRENT_TRANSLATIONS">Current translations</option>
          <option value="INCIDENTS">Incidents</option>
        </select>
      </label>
      {reviewSource === 'INCIDENTS' ? (
        <>
          <div className="settings-field">
            <span className="settings-field__label">Incident scope</span>
            <SingleSelectDropdown
              label="Incident scope"
              buttonAriaLabel="Select incident scope"
              value={incidentScope}
              options={[
                { value: 'ALL', label: 'All eligible incidents' },
                { value: 'REVIEW_FEATURES', label: 'Selected review features' },
              ]}
              onChange={(value) => onScopeChange(value ?? 'ALL')}
              searchable={false}
            />
          </div>
          <div className="settings-field">
            <span className="settings-field__label">Incident review type</span>
            <SingleSelectDropdown
              label="Incident review type"
              buttonAriaLabel="Select incident review type"
              value={incidentReviewType || null}
              options={[
                { value: 'TRANSLATION_QUALITY', label: 'Translation quality' },
                ...(incidentReviewType && incidentReviewType !== 'TRANSLATION_QUALITY'
                  ? [
                      {
                        value: incidentReviewType,
                        label: incidentReviewType.replace(/_/g, ' ').toLowerCase(),
                      },
                    ]
                  : []),
              ]}
              onChange={(value) => onTypeChange(value ?? '')}
              noneLabel="All incident types"
              placeholder="All incident types"
              searchable={false}
            />
          </div>
          <p className="settings-hint">
            Open incidents are staged for human review. Creating projects keeps current translations
            unchanged. Already assigned or reviewed findings are skipped.
          </p>
        </>
      ) : null}
    </div>
  );
}
