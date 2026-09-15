import './review-edit-feedback.css';

import { REVIEW_FEEDBACK_REASONS, type ReviewFeedbackReason } from '../../api/review-feedback';

export function ReviewEditFeedback({
  reason,
  note,
  onReason,
  onNote,
  disabled,
}: {
  reason: ReviewFeedbackReason | '';
  note: string;
  onReason: (value: ReviewFeedbackReason | '') => void;
  onNote: (value: string) => void;
  disabled?: boolean;
}) {
  return (
    <section
      className="review-edit-feedback"
      aria-label="AI translation feedback"
      data-agent-review-feedback
    >
      <div className="review-edit-feedback__heading">Reason for change</div>
      <div className="review-edit-feedback__reasons" role="group" aria-label="Reason (optional)">
        {Object.entries(REVIEW_FEEDBACK_REASONS).map(([key, label]) => (
          <button
            key={key}
            type="button"
            aria-pressed={reason === key}
            disabled={disabled}
            onClick={() => onReason(reason === key ? '' : (key as ReviewFeedbackReason))}
          >
            {label}
          </button>
        ))}
      </div>
      <div className="review-edit-feedback__note">
        <textarea
          aria-label="AI feedback note"
          placeholder="Explain the issue or your correction (optional)"
          value={note}
          maxLength={500}
          disabled={disabled}
          onChange={(event) => onNote(event.target.value)}
          rows={1}
        />
        <span className="review-edit-feedback__count">{note.length} / 500</span>
      </div>
    </section>
  );
}
