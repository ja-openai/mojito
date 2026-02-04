import '../review-project/review-project-page.css';
import './text-unit-detail-page.css';

import { type FormEvent, type ReactNode, useEffect, useRef } from 'react';

import type { AiReviewReview, AiReviewSuggestion } from '../../api/ai-review';
import { AutoTextarea } from '../../components/AutoTextarea';
import { ConfirmModal } from '../../components/ConfirmModal';
import { Pill } from '../../components/Pill';
import { PillDropdown } from '../../components/PillDropdown';

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

export type TextUnitDetailAiMessage = {
  id: string;
  sender: 'user' | 'assistant';
  content: string;
  suggestions?: AiReviewSuggestion[];
  review?: AiReviewReview;
};

type TextUnitDetailPageViewProps = {
  tmTextUnitId: number;
  onBack: () => void;
  editorInfo: {
    target: string;
    status: string;
    statusOptions: string[];
    canEdit: boolean;
    isDirty: boolean;
    isSaving: boolean;
    errorMessage: string | null;
    warningMessage: string | null;
  };
  keyInfo: {
    stringId: string;
    locale: string;
    source: string;
    comment: string;
    repositoryName: string;
  };
  onChangeTarget: (value: string) => void;
  onChangeStatus: (value: string) => void;
  onSaveEditor: () => void;
  onResetEditor: () => void;
  isAiCollapsed: boolean;
  onToggleAiCollapsed: () => void;
  aiMessages: TextUnitDetailAiMessage[];
  aiInput: string;
  onChangeAiInput: (value: string) => void;
  onSubmitAi: () => void;
  onUseAiSuggestion: (suggestion: AiReviewSuggestion) => void;
  onUseAiSuggestionAndSave: (suggestion: AiReviewSuggestion) => void;
  isAiResponding: boolean;
  isMetaCollapsed: boolean;
  onToggleMetaCollapsed: () => void;
  isMetaLoading: boolean;
  metaErrorMessage: string | null;
  metaSections: TextUnitDetailMetaSection[];
  metaWarningMessage: string | null;
  isHistoryCollapsed: boolean;
  onToggleHistoryCollapsed: () => void;
  isHistoryLoading: boolean;
  historyErrorMessage: string | null;
  historyMissingLocale: boolean;
  historyRows: TextUnitDetailHistoryRow[];
  historyInitialDate: string;
  showValidationDialog: boolean;
  validationDialogBody: string;
  onConfirmValidationSave: () => void;
  onDismissValidationDialog: () => void;
};

export function TextUnitDetailPageView({
  tmTextUnitId,
  onBack,
  editorInfo,
  keyInfo,
  onChangeTarget,
  onChangeStatus,
  onSaveEditor,
  onResetEditor,
  isAiCollapsed,
  onToggleAiCollapsed,
  aiMessages,
  aiInput,
  onChangeAiInput,
  onSubmitAi,
  onUseAiSuggestion,
  onUseAiSuggestionAndSave,
  isAiResponding,
  isMetaCollapsed,
  onToggleMetaCollapsed,
  isMetaLoading,
  metaErrorMessage,
  metaSections,
  metaWarningMessage,
  isHistoryCollapsed,
  onToggleHistoryCollapsed,
  isHistoryLoading,
  historyErrorMessage,
  historyMissingLocale,
  historyRows,
  historyInitialDate,
  showValidationDialog,
  validationDialogBody,
  onConfirmValidationSave,
  onDismissValidationDialog,
}: TextUnitDetailPageViewProps) {
  let firstReviewMessage: TextUnitDetailAiMessage | null = null;
  for (const candidate of aiMessages) {
    if (candidate.sender === 'assistant' && candidate.review) {
      firstReviewMessage = candidate;
      break;
    }
  }
  const aiThreadRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (isAiCollapsed) {
      return;
    }
    const thread = aiThreadRef.current;
    if (!thread) {
      return;
    }
    thread.scrollTo({ top: thread.scrollHeight });
  }, [aiMessages, isAiCollapsed, isAiResponding]);

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
            <div className="text-unit-detail-page__header-context">
              <Pill>{keyInfo.locale}</Pill>
              <span className="text-unit-detail-page__header-repository" title={keyInfo.repositoryName}>
                {keyInfo.repositoryName}
              </span>
            </div>
          </div>
          <div className="review-project-page__header-group review-project-page__header-group--stats" />
          <div className="review-project-page__header-group review-project-page__header-group--meta" />
        </div>
      </header>

      <div className="text-unit-detail-page__content">
        <div className="text-unit-detail-page__layout">
          <section className="text-unit-detail-page__panel text-unit-detail-page__panel--editor">
            <h1 className="text-unit-detail-page__title">Translation</h1>

            <div className="text-unit-detail-page__editor-field">
              <AutoTextarea
                className="text-unit-detail-page__editor-textarea"
                value={editorInfo.target}
                onChange={(event) => onChangeTarget(event.target.value)}
                disabled={!editorInfo.canEdit || editorInfo.isSaving}
                placeholder="Add translated copy"
                rows={1}
                style={{ resize: 'none' }}
              />
            </div>

            <div className="text-unit-detail-page__editor-controls">
              <div className="text-unit-detail-page__editor-status">
                <PillDropdown
                  value={editorInfo.status}
                  options={editorInfo.statusOptions.map((option) => ({
                    value: option,
                    label: option,
                  }))}
                  onChange={onChangeStatus}
                  disabled={!editorInfo.canEdit || editorInfo.isSaving}
                  ariaLabel="Translation status"
                />
              </div>
              <div className="text-unit-detail-page__editor-actions">
                <button
                  type="button"
                  className="text-unit-detail-page__button"
                  onClick={onResetEditor}
                  disabled={!editorInfo.isDirty || editorInfo.isSaving}
                >
                  Reset
                </button>
                <button
                  type="button"
                  className="text-unit-detail-page__button text-unit-detail-page__button--primary"
                  onClick={onSaveEditor}
                  disabled={!editorInfo.canEdit || !editorInfo.isDirty || editorInfo.isSaving}
                >
                  {editorInfo.isSaving ? 'Saving…' : 'Save'}
                </button>
              </div>
            </div>

            <section className="text-unit-detail-page__panel text-unit-detail-page__panel--section text-unit-detail-page__panel--ai-inline">
              <SectionHeader title="AI Chat Review" expanded={!isAiCollapsed} onToggle={onToggleAiCollapsed} />
              {!isAiCollapsed ? (
                <>
                  <div className="text-unit-detail-page__ai-thread" ref={aiThreadRef}>
                    {aiMessages.map((message) => {
                      const review = message.review;
                      const showReview =
                        message.id === firstReviewMessage?.id &&
                        message.sender === 'assistant' &&
                        Boolean(review);
                      const reviewBadge = review ? getReviewBadge(review.score) : null;
                      const reviewSummary = review?.explanation?.trim() || message.content;
                      const suggestions = message.suggestions ?? [];

                      return (
                        <div
                          key={message.id}
                          className={`text-unit-detail-page__ai-message text-unit-detail-page__ai-message--${message.sender}`}
                        >
                          {showReview && review ? (
                            <div className="text-unit-detail-page__ai-review">
                              {reviewBadge ? (
                                <span
                                  className={`text-unit-detail-page__ai-review-badge ${reviewBadge.className}`}
                                >
                                  {reviewBadge.label}
                                </span>
                              ) : null}
                              <p>{reviewSummary}</p>
                            </div>
                          ) : (
                            <p className="text-unit-detail-page__ai-message-content">{message.content}</p>
                          )}

                          {suggestions.length > 0 ? (
                            <div className="text-unit-detail-page__ai-suggestions">
                              {suggestions.map((suggestion, suggestionIndex) => (
                                <div
                                  key={`${message.id}-suggestion-${suggestionIndex}`}
                                  className="text-unit-detail-page__ai-suggestion"
                                >
                                  <div className="text-unit-detail-page__ai-suggestion-main">
                                    <span className="text-unit-detail-page__ai-suggestion-content">
                                      {suggestion.content}
                                    </span>
                                    {suggestion.explanation ? (
                                      <span className="text-unit-detail-page__ai-suggestion-explanation">
                                        {suggestion.explanation}
                                      </span>
                                    ) : null}
                                  </div>
                                  <div className="text-unit-detail-page__ai-suggestion-actions">
                                    <button
                                      type="button"
                                      className="text-unit-detail-page__button"
                                      onClick={() => onUseAiSuggestion(suggestion)}
                                    >
                                      Use
                                    </button>
                                    <button
                                      type="button"
                                      className="text-unit-detail-page__button text-unit-detail-page__button--primary"
                                      onClick={() => onUseAiSuggestionAndSave(suggestion)}
                                      disabled={!editorInfo.canEdit || editorInfo.isSaving}
                                    >
                                      Use + Save
                                    </button>
                                  </div>
                                </div>
                              ))}
                            </div>
                          ) : null}
                        </div>
                      );
                    })}

                    {isAiResponding ? (
                      <div className="text-unit-detail-page__state">
                        <span className="spinner spinner--md" aria-hidden />
                        <span>Thinking…</span>
                      </div>
                    ) : null}
                  </div>

                  <form
                    className="text-unit-detail-page__ai-form"
                    onSubmit={(event: FormEvent<HTMLFormElement>) => {
                      event.preventDefault();
                      onSubmitAi();
                    }}
                  >
                    <input
                      type="text"
                      value={aiInput}
                      onChange={(event) => onChangeAiInput(event.target.value)}
                      placeholder="Ask AI for a suggestion"
                      disabled={isAiResponding}
                    />
                    <button
                      type="submit"
                      className="text-unit-detail-page__button text-unit-detail-page__button--primary"
                      disabled={isAiResponding || aiInput.trim().length === 0}
                    >
                      Ask
                    </button>
                  </form>
                </>
              ) : null}
            </section>

            {editorInfo.warningMessage ? (
              <div className="text-unit-detail-page__state text-unit-detail-page__state--warning">
                {editorInfo.warningMessage}
              </div>
            ) : null}
            {editorInfo.errorMessage ? (
              <div className="text-unit-detail-page__state text-unit-detail-page__state--error">
                {editorInfo.errorMessage}
              </div>
            ) : null}
          </section>

          <div className="text-unit-detail-page__side">
            <section className="text-unit-detail-page__panel text-unit-detail-page__panel--section">
              <dl className="text-unit-detail-page__key-info">
                <div className="text-unit-detail-page__key-info-row">
                  <dt>Source</dt>
                  <dd>
                    <pre className="text-unit-detail-page__key-info-text">{keyInfo.source}</pre>
                  </dd>
                </div>
                <div className="text-unit-detail-page__key-info-row">
                  <dt>Comment</dt>
                  <dd>
                    <pre className="text-unit-detail-page__key-info-text">{keyInfo.comment}</pre>
                  </dd>
                </div>
                <div className="text-unit-detail-page__key-info-row">
                  <dt>Id</dt>
                  <dd>
                    <pre className="text-unit-detail-page__key-info-text">{keyInfo.stringId}</pre>
                  </dd>
                </div>
              </dl>
            </section>

            <section className="text-unit-detail-page__panel text-unit-detail-page__panel--section">
              <SectionHeader
                title={`History (${historyRows.length + 1})`}
                expanded={!isHistoryCollapsed}
                onToggle={onToggleHistoryCollapsed}
              />
              {!isHistoryCollapsed ? (
                historyMissingLocale ? (
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
                          <pre className="text-unit-detail-page__timeline-content">
                            {item.translation}
                          </pre>
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
                          <time className="text-unit-detail-page__timeline-time">
                            {historyInitialDate}
                          </time>
                        </div>
                        <pre className="text-unit-detail-page__timeline-content">
                          {'<no translation yet>'}
                        </pre>
                      </div>
                    </li>
                  </ol>
                )
              ) : null}
            </section>

            <section className="text-unit-detail-page__panel text-unit-detail-page__panel--section">
              <SectionHeader
                title="Metadata"
                expanded={!isMetaCollapsed}
                onToggle={onToggleMetaCollapsed}
              />

              {!isMetaCollapsed ? (
                isMetaLoading ? (
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
                )
              ) : null}
            </section>
          </div>
        </div>
      </div>

      <ConfirmModal
        open={showValidationDialog}
        title="Translation check failed"
        body={validationDialogBody}
        confirmLabel="Save anyway"
        cancelLabel="Keep editing"
        onConfirm={onConfirmValidationSave}
        onCancel={onDismissValidationDialog}
      />
    </div>
  );
}

function SectionHeader({
  title,
  expanded,
  onToggle,
  summary,
}: {
  title: string;
  expanded: boolean;
  onToggle: () => void;
  summary?: ReactNode;
}) {
  return (
    <button
      type="button"
      className="text-unit-detail-page__section-header"
      onClick={onToggle}
      aria-expanded={expanded}
    >
      <span className="text-unit-detail-page__section-heading">
        <span className="text-unit-detail-page__section-title">{title}</span>
        {summary ? <span className="text-unit-detail-page__section-summary">{summary}</span> : null}
      </span>
      <span className="text-unit-detail-page__section-action">{expanded ? 'Hide' : 'Show'}</span>
    </button>
  );
}

function MetaSection({ title, rows }: { title: string; rows: TextUnitDetailMetaRow[] }) {
  return (
    <section className="text-unit-detail-page__meta-section">
      <h3 className="text-unit-detail-page__meta-title">{title}</h3>
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

function getReviewBadge(score: number): { label: string; className: string } {
  if (score >= 0 && score <= 1) {
    if (score >= 0.8) {
      return {
        label: 'Excellent',
        className: 'text-unit-detail-page__ai-review-badge--high',
      };
    }
    if (score >= 0.4) {
      return {
        label: 'Needs polish',
        className: 'text-unit-detail-page__ai-review-badge--medium',
      };
    }
    return {
      label: 'Needs rewrite',
      className: 'text-unit-detail-page__ai-review-badge--low',
    };
  }

  if (score >= 0 && score <= 2) {
    if (score >= 2) {
      return {
        label: 'Excellent',
        className: 'text-unit-detail-page__ai-review-badge--high',
      };
    }
    if (score >= 1) {
      return {
        label: 'Needs polish',
        className: 'text-unit-detail-page__ai-review-badge--medium',
      };
    }
    return {
      label: 'Needs rewrite',
      className: 'text-unit-detail-page__ai-review-badge--low',
    };
  }

  if (score >= 85) {
    return {
      label: 'Excellent',
      className: 'text-unit-detail-page__ai-review-badge--high',
    };
  }
  if (score >= 60) {
    return {
      label: 'Needs polish',
      className: 'text-unit-detail-page__ai-review-badge--medium',
    };
  }
  return {
    label: 'Needs rewrite',
    className: 'text-unit-detail-page__ai-review-badge--low',
  };
}
