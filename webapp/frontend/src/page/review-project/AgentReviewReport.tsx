import './agent-review-report.css';

import type { ApiAgentReviewContext } from '../../api/agent-reviews';
import { toHtmlLangTag } from '../../utils/localeTag';

function ComparedText({
  text,
  other,
  original,
}: {
  text: string;
  other: string | null;
  original: boolean;
}) {
  if (other === null || text === other) return <>{text || 'Empty translation'}</>;
  const tokens = text.split(/(\s+)/u);
  const comparison = other.split(/(\s+)/u);
  let start = 0;
  let end = 0;
  while (start < tokens.length && start < comparison.length && tokens[start] === comparison[start])
    start++;
  while (
    end < tokens.length - start &&
    end < comparison.length - start &&
    tokens[tokens.length - 1 - end] === comparison[comparison.length - 1 - end]
  )
    end++;
  const changed = tokens.slice(start, tokens.length - end).join('');
  return (
    <>
      {tokens.slice(0, start).join('')}
      {original ? <del>{changed}</del> : <ins>{changed}</ins>}
      {end ? tokens.slice(-end).join('') : ''}
      {!text ? 'Empty translation' : null}
    </>
  );
}

export function AgentReviewReport({
  proposal,
  localeTag,
  draftTarget,
  onUseOriginal,
  onUseSuggestion,
  originalDisabled,
  suggestionDisabled,
  originalError,
  suggestionError,
  onOpenReport,
}: {
  proposal: ApiAgentReviewContext;
  localeTag: string;
  draftTarget: string;
  onUseOriginal: () => void;
  onUseSuggestion: () => void;
  originalDisabled: boolean;
  suggestionDisabled: boolean;
  originalError?: string | null;
  suggestionError?: string | null;
  onOpenReport: () => void;
}) {
  const originalSelected =
    proposal.reviewedTarget !== null && draftTarget === proposal.reviewedTarget;
  const suggestionSelected =
    !originalSelected &&
    proposal.proposedTarget !== null &&
    draftTarget === proposal.proposedTarget;
  const status =
    proposal.disposition === 'RESOLVED'
      ? 'Reviewed'
      : proposal.disposition === 'FOLLOW_UP'
        ? 'Awaiting feedback'
        : proposal.disposition === 'SUPERSEDED'
          ? 'Replaced'
          : null;
  return (
    <section className="agent-review-report" aria-label="Reported issue">
      <div className="agent-review-report__header">
        <strong>Reported issue</strong>
        <span className="agent-review-report__origin">
          {proposal.verificationStatus === 'HUMAN_REVIEW' ? 'Human review' : 'Automation'}
          {status ? ` · ${status}` : ''}
        </span>
        <div className="agent-review-report__actions">
          <button type="button" className="agent-review-report__link" onClick={onOpenReport}>
            View report →
          </button>
        </div>
      </div>
      <div className="agent-review-report__columns">
        <div>
          <h3>Original at review</h3>
          <p
            className="agent-review-report__translation"
            dir="auto"
            lang={toHtmlLangTag(localeTag)}
          >
            {proposal.reviewedTarget === null ? (
              'No translation recorded'
            ) : (
              <ComparedText
                text={proposal.reviewedTarget}
                other={proposal.proposedTarget}
                original
              />
            )}
          </p>
          {originalSelected ? (
            <span
              className="agent-review-report__selected"
              role="status"
              aria-label="Selected original translation"
            >
              <span aria-hidden="true">✓</span> Selected
            </span>
          ) : (
            <button
              type="button"
              className="review-project-detail__actions-button agent-review-report__choice"
              aria-label="Use original translation"
              disabled={
                originalDisabled || Boolean(originalError) || proposal.reviewedTarget === null
              }
              onClick={onUseOriginal}
            >
              Use original
            </button>
          )}
          {originalError ? <p className="agent-review-report__error">{originalError}</p> : null}
        </div>
        <div>
          <h3>Proposed correction</h3>
          <p
            className="agent-review-report__translation"
            dir="auto"
            lang={toHtmlLangTag(localeTag)}
          >
            {proposal.proposedTarget === null ? (
              'No correction proposed'
            ) : (
              <ComparedText
                text={proposal.proposedTarget}
                other={proposal.reviewedTarget}
                original={false}
              />
            )}
          </p>
          {suggestionSelected ? (
            <span
              className="agent-review-report__selected"
              role="status"
              aria-label="Selected proposed correction"
            >
              <span aria-hidden="true">✓</span> Selected
            </span>
          ) : proposal.proposedTarget !== null ? (
            <button
              type="button"
              className="review-project-detail__actions-button agent-review-report__choice"
              aria-label="Use suggestion"
              disabled={suggestionDisabled || Boolean(suggestionError)}
              onClick={onUseSuggestion}
            >
              Use suggestion
            </button>
          ) : null}
          {suggestionError ? <p className="agent-review-report__error">{suggestionError}</p> : null}
        </div>
        <div>
          <h3>Finding</h3>
          <p className="agent-review-report__finding">{proposal.rationale}</p>
        </div>
      </div>
    </section>
  );
}
