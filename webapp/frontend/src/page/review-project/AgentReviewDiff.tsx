import './agent-review.css';

import { useMemo } from 'react';

import { buildInlineDiffParts } from '../../utils/inlineDiff';
import { toHtmlLangTag } from '../../utils/localeTag';

/** Always compares the immutable reviewed text, never the reviewer's mutable draft. */
export function AgentReviewDiff({
  original,
  proposed,
  localeTag,
  compact = false,
}: {
  original: string | null;
  proposed: string | null;
  localeTag: string;
  compact?: boolean;
}) {
  const oldParts = useMemo(
    () => buildInlineDiffParts(original ?? '', proposed ?? original ?? '', 'old'),
    [original, proposed],
  );
  const newParts = useMemo(
    () => buildInlineDiffParts(original ?? '', proposed ?? '', 'new'),
    [original, proposed],
  );
  return (
    <div className={`agent-review-diff${compact ? ' agent-review-diff--compact' : ''}`}>
      <div className="agent-review-diff__side">
        <span className="agent-review-diff__label">Reviewed original</span>
        <div className="agent-review-diff__text" dir="auto" lang={toHtmlLangTag(localeTag)}>
          {original === null ? (
            <span className="agent-review-diff__empty">No translation</span>
          ) : original === '' ? (
            <span className="agent-review-diff__empty">Empty translation</span>
          ) : (
            oldParts.map((part, index) =>
              part.kind === 'removed' ? <del key={index}>{part.value}</del> : part.value,
            )
          )}
        </div>
      </div>
      <div className="agent-review-diff__side">
        <span className="agent-review-diff__label">Agent proposal</span>
        <div className="agent-review-diff__text" dir="auto" lang={toHtmlLangTag(localeTag)}>
          {proposed === null ? (
            <span className="agent-review-diff__empty">No replacement proposed</span>
          ) : proposed === '' ? (
            <span className="agent-review-diff__empty">Empty translation proposed</span>
          ) : (
            newParts.map((part, index) =>
              part.kind === 'added' ? <ins key={index}>{part.value}</ins> : part.value,
            )
          )}
        </div>
      </div>
    </div>
  );
}
