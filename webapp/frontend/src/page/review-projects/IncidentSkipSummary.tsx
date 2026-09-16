import { Link } from 'react-router-dom';

import type { IncidentReviewProjectResult } from '../../api/incident-review-projects';

type Props = Pick<IncidentReviewProjectResult, 'skipped' | 'skippedIncidentCount'>;

const EXAMPLE_LIMIT = 3;

export function IncidentSkipSummary({ skipped, skippedIncidentCount }: Props) {
  if (skippedIncidentCount === 0) return null;

  const groups = new Map<string, number[]>();
  for (const item of skipped) {
    const ids = groups.get(item.reason) ?? [];
    ids.push(item.incidentId);
    groups.set(item.reason, ids);
  }

  return (
    <div className="review-create__skip-summary">
      <div>
        {skippedIncidentCount} incident{skippedIncidentCount === 1 ? '' : 's'} not included
      </div>
      {skipped.length < skippedIncidentCount ? (
        <p className="review-create__hint">
          {skipped.length
            ? `Reasons below cover the most recent ${skipped.length} of ${skippedIncidentCount} incidents not included.`
            : 'Details are unavailable for these incidents.'}
        </p>
      ) : null}
      {[...groups]
        .sort((a, b) => b[1].length - a[1].length)
        .map(([reason, ids]) => (
          <details key={reason}>
            <summary>
              {reason} · {ids.length} incident{ids.length === 1 ? '' : 's'}
            </summary>
            <div className="review-create__skip-examples">
              <span className="review-create__hint">
                {ids.length > EXAMPLE_LIMIT
                  ? `Examples (${EXAMPLE_LIMIT} of ${ids.length}):`
                  : 'Incidents:'}
              </span>
              {ids.slice(0, EXAMPLE_LIMIT).map((id) => (
                <Link
                  key={id}
                  to={`/translation-incidents?incidentId=${id}`}
                  target="_blank"
                  rel="noopener noreferrer"
                  aria-label={`Incident #${id} (opens in a new tab)`}
                >
                  #{id}
                </Link>
              ))}
            </div>
          </details>
        ))}
    </div>
  );
}
