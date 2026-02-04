import '../review-project/review-project-page.css';
import './text-unit-detail-page.css';

export type TextUnitDetailMetaRow = {
  label: string;
  value: string;
};

export type TextUnitDetailMetaSection = {
  title: string;
  rows: TextUnitDetailMetaRow[];
};

export type TextUnitDetailHistoryComment = {
  key: string;
  type: string;
  severity: string;
  content: string;
};

export type TextUnitDetailHistoryRow = {
  key: string;
  variantId: string;
  userName: string;
  translation: string;
  date: string;
  status: string;
  comments: TextUnitDetailHistoryComment[];
};

type TextUnitDetailPageViewProps = {
  tmTextUnitId: number;
  onBack: () => void;
  isMetaLoading: boolean;
  metaErrorMessage: string | null;
  metaSections: TextUnitDetailMetaSection[];
  metaWarningMessage: string | null;
  isHistoryLoading: boolean;
  historyErrorMessage: string | null;
  historyMissingLocale: boolean;
  historyRows: TextUnitDetailHistoryRow[];
  historyInitialDate: string;
};

export function TextUnitDetailPageView({
  tmTextUnitId,
  onBack,
  isMetaLoading,
  metaErrorMessage,
  metaSections,
  metaWarningMessage,
  isHistoryLoading,
  historyErrorMessage,
  historyMissingLocale,
  historyRows,
  historyInitialDate,
}: TextUnitDetailPageViewProps) {
  return (
    <div className="review-project-page text-unit-detail-page">
      <header className="review-project-page__header">
        <div className="review-project-page__header-row">
          <div className="review-project-page__header-group review-project-page__header-group--left">
            <button
              type="button"
              className="review-project-page__header-back-link"
              onClick={onBack}
              aria-label="Back to workbench"
              title="Back to workbench"
            >
              <svg
                className="review-project-page__header-back-icon"
                viewBox="0 0 24 24"
                aria-hidden="true"
                focusable="false"
              >
                <path
                  d="M20 12H6m0 0l5-5m-5 5l5 5"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            </button>
            <span className="review-project-page__header-name">Text unit #{tmTextUnitId}</span>
          </div>
          <div className="review-project-page__header-group review-project-page__header-group--stats" />
          <div className="review-project-page__header-group review-project-page__header-group--meta" />
        </div>
      </header>

      <div className="text-unit-detail-page__content">
        <div className="text-unit-detail-page__layout">
          <section className="text-unit-detail-page__panel">
            <h1 className="text-unit-detail-page__title">Text Unit Details</h1>
            <p className="text-unit-detail-page__subtitle">
              Metadata port from the legacy modal (text unit, git blame, and debug fields).
            </p>

            {isMetaLoading ? (
              <div className="text-unit-detail-page__state">
                <span className="spinner spinner--md" aria-hidden />
                <span>Loading text unit details…</span>
              </div>
            ) : metaErrorMessage ? (
              <div className="text-unit-detail-page__state text-unit-detail-page__state--error">
                {metaErrorMessage}
              </div>
            ) : (
              <div className="text-unit-detail-page__sections">
                {metaSections.map((section) => (
                  <MetaSection key={section.title} title={section.title} rows={section.rows} />
                ))}

                {metaWarningMessage ? (
                  <div className="text-unit-detail-page__state text-unit-detail-page__state--warning">
                    {metaWarningMessage}
                  </div>
                ) : null}
              </div>
            )}
          </section>

          <section className="text-unit-detail-page__panel text-unit-detail-page__panel--history">
            <h2 className="text-unit-detail-page__title">History</h2>
            <p className="text-unit-detail-page__subtitle">
              Latest updates first, with comments inline on each event.
            </p>
            {historyMissingLocale ? (
              <div className="text-unit-detail-page__state text-unit-detail-page__state--warning">
                Missing locale. Open this page from the workbench row to load history.
              </div>
            ) : isHistoryLoading ? (
              <div className="text-unit-detail-page__state">
                <span className="spinner spinner--md" aria-hidden />
                <span>Loading history…</span>
              </div>
            ) : historyErrorMessage ? (
              <div className="text-unit-detail-page__state text-unit-detail-page__state--error">
                {historyErrorMessage}
              </div>
            ) : (
              <ol className="text-unit-detail-page__timeline">
                {historyRows.map((item) => (
                  <li key={item.key} className="text-unit-detail-page__timeline-item">
                    <div className="text-unit-detail-page__timeline-dot" aria-hidden="true" />
                    <div className="text-unit-detail-page__timeline-card">
                      <div className="text-unit-detail-page__timeline-header">
                        <div className="text-unit-detail-page__timeline-summary">
                          <span className="text-unit-detail-page__timeline-title">
                            Translation updated
                            <span className="text-unit-detail-page__timeline-title-meta">
                              #{item.variantId}
                            </span>
                          </span>
                          <span className="text-unit-detail-page__timeline-summary-separator">
                            &middot;
                          </span>
                          <span className="text-unit-detail-page__timeline-summary-meta">
                            {item.userName}
                          </span>
                          {item.status !== '-' ? (
                            <>
                              <span className="text-unit-detail-page__timeline-summary-separator">
                                &middot;
                              </span>
                              <span className="text-unit-detail-page__timeline-summary-status">
                                {item.status}
                              </span>
                            </>
                          ) : null}
                        </div>
                        <time className="text-unit-detail-page__timeline-time">{item.date}</time>
                      </div>
                      <pre className="text-unit-detail-page__timeline-content">{item.translation}</pre>
                      {item.comments.length > 0 ? (
                        <table className="text-unit-detail-page__history-comment-table">
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
                <li className="text-unit-detail-page__timeline-item">
                  <div className="text-unit-detail-page__timeline-dot" aria-hidden="true" />
                  <div className="text-unit-detail-page__timeline-card">
                    <div className="text-unit-detail-page__timeline-header">
                      <div className="text-unit-detail-page__timeline-summary">
                        <span className="text-unit-detail-page__timeline-title">
                          Text unit created (untranslated)
                        </span>
                        <span className="text-unit-detail-page__timeline-summary-separator">
                          &middot;
                        </span>
                        <span className="text-unit-detail-page__timeline-summary-meta">-</span>
                        <span className="text-unit-detail-page__timeline-summary-separator">
                          &middot;
                        </span>
                        <span className="text-unit-detail-page__timeline-summary-status">
                          Untranslated
                        </span>
                      </div>
                      <time className="text-unit-detail-page__timeline-time">{historyInitialDate}</time>
                    </div>
                    <pre className="text-unit-detail-page__timeline-content">{'<no translation yet>'}</pre>
                  </div>
                </li>
              </ol>
            )}
          </section>
        </div>
      </div>
    </div>
  );
}

function MetaSection({ title, rows }: { title: string; rows: TextUnitDetailMetaRow[] }) {
  return (
    <section className="text-unit-detail-page__meta-section">
      <h2 className="text-unit-detail-page__meta-title">{title}</h2>
      <dl className="text-unit-detail-page__meta">
        {rows.map((row) => (
          <div key={row.label} className="text-unit-detail-page__meta-row">
            <dt>{row.label}</dt>
            <dd>{row.value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
