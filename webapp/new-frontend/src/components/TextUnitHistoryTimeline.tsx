import './text-unit-history-timeline.css';

export type TextUnitHistoryTimelineComment = {
  key: string;
  type: string;
  severity: string;
  content: string;
};

export type TextUnitHistoryTimelineEntry = {
  key: string;
  variantId: string;
  userName: string;
  translation: string;
  date: string;
  status: string;
  comments: TextUnitHistoryTimelineComment[];
  badges?: string[];
};

type TextUnitHistoryTimelineProps = {
  isLoading: boolean;
  errorMessage: string | null;
  missingLocale?: boolean;
  entries: TextUnitHistoryTimelineEntry[];
  showDeletedEntry?: boolean;
  initialDate?: string | null;
  emptyMessage?: string;
};

export function TextUnitHistoryTimeline({
  isLoading,
  errorMessage,
  missingLocale = false,
  entries,
  showDeletedEntry = false,
  initialDate,
  emptyMessage = 'No history yet.',
}: TextUnitHistoryTimelineProps) {
  if (missingLocale) {
    return (
      <div className="text-unit-history__state text-unit-history__state--warning">
        Missing locale. Open this page from the workbench row to load history.
      </div>
    );
  }

  if (isLoading) {
    return (
      <div className="text-unit-history__state">
        <span className="spinner spinner--md" aria-hidden />
        <span>Loading history…</span>
      </div>
    );
  }

  if (errorMessage) {
    return (
      <div className="text-unit-history__state text-unit-history__state--error">{errorMessage}</div>
    );
  }

  if (!showDeletedEntry && entries.length === 0 && !initialDate) {
    return <div className="text-unit-history__state">{emptyMessage}</div>;
  }

  return (
    <ol className="text-unit-history__timeline">
      {showDeletedEntry ? (
        <li className="text-unit-history__timeline-item">
          <div className="text-unit-history__timeline-dot" aria-hidden="true" />
          <div className="text-unit-history__timeline-card">
            <div className="text-unit-history__timeline-header">
              <div className="text-unit-history__timeline-summary">
                <span className="text-unit-history__timeline-title">Translation deleted</span>
                <span className="text-unit-history__timeline-summary-separator">&middot;</span>
                <span className="text-unit-history__timeline-summary-meta">-</span>
                <span className="text-unit-history__timeline-summary-separator">&middot;</span>
                <span className="text-unit-history__timeline-summary-status">Deleted</span>
              </div>
              <time className="text-unit-history__timeline-time">-</time>
            </div>
            <pre className="text-unit-history__timeline-content">{'<no current translation>'}</pre>
          </div>
        </li>
      ) : null}

      {entries.map((item) => (
        <li key={item.key} className="text-unit-history__timeline-item">
          <div className="text-unit-history__timeline-dot" aria-hidden="true" />
          <div className="text-unit-history__timeline-card">
            <div className="text-unit-history__timeline-header">
              <div className="text-unit-history__timeline-summary">
                <span className="text-unit-history__timeline-title">
                  Translation updated
                  <span className="text-unit-history__timeline-title-meta">#{item.variantId}</span>
                </span>
                {item.badges?.length ? (
                  <span className="text-unit-history__timeline-badges">
                    {item.badges.map((badge) => (
                      <span
                        key={`${item.key}-${badge}`}
                        className="text-unit-history__timeline-badge"
                        title={badge}
                      >
                        {badge}
                      </span>
                    ))}
                  </span>
                ) : null}
                <span className="text-unit-history__timeline-summary-separator">&middot;</span>
                <span className="text-unit-history__timeline-summary-meta">{item.userName}</span>
                {item.status !== '-' ? (
                  <>
                    <span className="text-unit-history__timeline-summary-separator">&middot;</span>
                    <span className="text-unit-history__timeline-summary-status">
                      {item.status}
                    </span>
                  </>
                ) : null}
              </div>
              <time className="text-unit-history__timeline-time">{item.date}</time>
            </div>
            <pre className="text-unit-history__timeline-content">{item.translation}</pre>
            {item.comments.length > 0 ? (
              <table className="text-unit-history__comment-table">
                <thead>
                  <tr>
                    <th>type</th>
                    <th>severity</th>
                    <th>content</th>
                  </tr>
                </thead>
                <tbody>
                  {item.comments.map((comment) => (
                    <tr key={comment.key}>
                      <td>{comment.type}</td>
                      <td>{comment.severity}</td>
                      <td>{comment.content}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : null}
          </div>
        </li>
      ))}

      {initialDate ? (
        <li className="text-unit-history__timeline-item">
          <div className="text-unit-history__timeline-dot" aria-hidden="true" />
          <div className="text-unit-history__timeline-card">
            <div className="text-unit-history__timeline-header">
              <div className="text-unit-history__timeline-summary">
                <span className="text-unit-history__timeline-title">
                  Text unit created (untranslated)
                </span>
                <span className="text-unit-history__timeline-summary-separator">&middot;</span>
                <span className="text-unit-history__timeline-summary-meta">-</span>
                <span className="text-unit-history__timeline-summary-separator">&middot;</span>
                <span className="text-unit-history__timeline-summary-status">Untranslated</span>
              </div>
              <time className="text-unit-history__timeline-time">{initialDate}</time>
            </div>
            <pre className="text-unit-history__timeline-content">{'<no translation yet>'}</pre>
          </div>
        </li>
      ) : null}
    </ol>
  );
}
