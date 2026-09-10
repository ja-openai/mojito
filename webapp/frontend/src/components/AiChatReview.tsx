import './ai-chat-review.css';

import { type FormEvent, useEffect, useId, useMemo, useRef } from 'react';

import type { AiReviewReview, AiReviewSuggestion } from '../api/ai-review';
import type { AiReviewSettings } from '../hooks/useAiReviewPreferences';
import { AiReviewConfidence } from './AiReviewConfidence';

export type AiChatReviewMessage = {
  id: string;
  sender: 'user' | 'assistant';
  content: string;
  suggestions?: AiReviewSuggestion[];
  review?: AiReviewReview;
  isError?: boolean;
  errorDetail?: string;
};

type AiChatReviewProps = {
  messages: AiChatReviewMessage[];
  currentTarget: string;
  input: string;
  onChangeInput: (value: string) => void;
  onSubmit: () => void;
  onUseSuggestion: (suggestion: AiReviewSuggestion) => void;
  getSuggestionError?: (suggestion: AiReviewSuggestion) => string | null;
  onRetryError?: () => void;
  onReview?: () => void;
  settings?: AiReviewSettings;
  isResponding: boolean;
  className?: string;
};

export function AiChatReview({
  messages,
  currentTarget,
  input,
  onChangeInput,
  onSubmit,
  onUseSuggestion,
  getSuggestionError,
  onRetryError,
  onReview,
  settings,
  isResponding,
  className,
}: AiChatReviewProps) {
  const validationId = useId();
  const suggestionErrors = useMemo(
    () =>
      new Map(
        messages.flatMap((message) =>
          (message.suggestions ?? []).map(
            (suggestion) => [suggestion, getSuggestionError?.(suggestion) ?? null] as const,
          ),
        ),
      ),
    [getSuggestionError, messages],
  );
  const threadRef = useRef<HTMLDivElement | null>(null);
  const hasInput = input.trim().length > 0;
  const showScore = settings?.showScore ?? true;
  const submitReview =
    Boolean(onReview) && settings?.automaticDisabled === true && messages.length === 0 && !hasInput;
  const submitDisabled =
    isResponding ||
    (settings ? !settings.ready || settings.isSaving : false) ||
    (!hasInput && !submitReview);

  useEffect(() => {
    const thread = threadRef.current;
    if (!thread) {
      return;
    }
    thread.scrollTo({ top: thread.scrollHeight });
  }, [isResponding, messages]);

  return (
    <div className={className}>
      <div className="ai-chat-review__thread" ref={threadRef}>
        {messages.map((message, index) => {
          const review = message.review;
          const reviewSummary = review?.explanation?.trim() || message.content;
          const allSuggestions = message.suggestions ?? [];
          const suggestions = allSuggestions.filter(
            (suggestion) => suggestion.content !== currentTarget,
          );
          const hasCorrection = suggestions.some((suggestion) => suggestion.kind !== 'alternative');
          const hasOnlyAlternatives = suggestions.length > 0 && !hasCorrection;
          const showResult =
            message.sender === 'assistant' &&
            (Boolean(review) || allSuggestions.length > 0) &&
            !message.isError;
          const resultStatus = hasCorrection
            ? 'Change suggested'
            : !review && hasOnlyAlternatives
              ? 'Alternative wording'
              : !review || review.score === 2
                ? hasOnlyAlternatives
                  ? 'No correction suggested'
                  : 'No change suggested'
                : 'Review needed';
          const resultExplanation =
            (hasOnlyAlternatives ? reviewSummary : '') ||
            (review && review.score !== 2 && suggestions.length === 0
              ? review.explanation?.trim()
              : '') ||
            (suggestions[0] ?? allSuggestions[0])?.explanation?.trim() ||
            message.content.trim() ||
            reviewSummary;
          const isLastMessage = index === messages.length - 1;
          const showRetryButton =
            Boolean(onRetryError) &&
            message.isError === true &&
            message.sender === 'assistant' &&
            isLastMessage &&
            !isResponding;

          return (
            <div
              key={message.id}
              className={`ai-chat-review__message ai-chat-review__message--${message.sender}`}
            >
              {showResult ? (
                <>
                  <p className="ai-chat-review__result-status">{resultStatus}</p>
                  {(suggestions.length === 0 || hasOnlyAlternatives) && resultExplanation ? (
                    <p className="ai-chat-review__message-content">{resultExplanation}</p>
                  ) : null}
                  {showScore && suggestions.length === 0 ? (
                    <AiReviewConfidence
                      value={
                        allSuggestions.find((suggestion) => suggestion.content === currentTarget)
                          ?.confidenceLevel ?? review?.confidenceLevel
                      }
                    />
                  ) : null}
                </>
              ) : (
                <p className="ai-chat-review__message-content">{message.content}</p>
              )}

              {message.isError && message.errorDetail ? (
                <details className="ai-chat-review__error-details">
                  <summary>Details</summary>
                  <pre>{message.errorDetail}</pre>
                </details>
              ) : null}

              {showRetryButton ? (
                <div className="ai-chat-review__error-actions">
                  <button type="button" className="ai-chat-review__button" onClick={onRetryError}>
                    Retry
                  </button>
                </div>
              ) : null}

              {suggestions.length > 0 ? (
                <div className="ai-chat-review__suggestions">
                  {suggestions.map((suggestion, suggestionIndex) => {
                    const explanation =
                      suggestion.explanation?.trim() ||
                      (!hasOnlyAlternatives && showResult && suggestionIndex === 0
                        ? resultExplanation
                        : '');
                    const showExplanation =
                      explanation && (!hasOnlyAlternatives || explanation !== resultExplanation);
                    return (
                      <div
                        key={`${message.id}-suggestion-${suggestionIndex}`}
                        className="ai-chat-review__suggestion"
                      >
                        <div className="ai-chat-review__suggestion-main">
                          {suggestion.kind || showScore ? (
                            <div className="ai-chat-review__suggestion-meta">
                              {suggestion.kind ? (
                                <span className="ai-chat-review__suggestion-kind">
                                  {suggestion.kind === 'alternative'
                                    ? 'Alternative wording'
                                    : 'Suggested correction'}
                                </span>
                              ) : null}
                              {showScore ? (
                                <AiReviewConfidence value={suggestion.confidenceLevel} />
                              ) : null}
                            </div>
                          ) : null}
                          <span className="ai-chat-review__suggestion-content">
                            {suggestion.content}
                          </span>
                          {showExplanation ? (
                            <span className="ai-chat-review__suggestion-explanation">
                              {explanation}
                            </span>
                          ) : null}
                          {suggestionErrors.get(suggestion) ? (
                            <span
                              className="ai-chat-review__suggestion-error"
                              id={`${validationId}-${index}-${suggestionIndex}`}
                            >
                              Cannot use this suggestion: {suggestionErrors.get(suggestion)}
                            </span>
                          ) : null}
                        </div>
                        <div className="ai-chat-review__suggestion-actions">
                          <button
                            type="button"
                            className="ai-chat-review__button"
                            disabled={Boolean(suggestionErrors.get(suggestion))}
                            aria-describedby={
                              suggestionErrors.get(suggestion)
                                ? `${validationId}-${index}-${suggestionIndex}`
                                : undefined
                            }
                            onClick={() => onUseSuggestion(suggestion)}
                          >
                            Use
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              ) : null}
            </div>
          );
        })}

        {isResponding ? (
          <div className="ai-chat-review__state">
            <span className="spinner spinner--md" aria-hidden />
            <span>Thinking…</span>
          </div>
        ) : null}
      </div>

      <form
        className="ai-chat-review__form"
        onSubmit={(event: FormEvent<HTMLFormElement>) => {
          event.preventDefault();
          if (submitDisabled) return;
          if (submitReview) onReview?.();
          else onSubmit();
        }}
      >
        <input
          type="text"
          value={input}
          onChange={(event) => onChangeInput(event.target.value)}
          placeholder="Chat with AI: rephrase, adjust the tone, or ask a question…"
          disabled={isResponding || (settings ? !settings.ready || settings.isSaving : false)}
        />
        <button
          type="submit"
          className="ai-chat-review__button ai-chat-review__button--primary"
          disabled={submitDisabled}
          title={
            submitReview ? 'Review this translation once. Automatic review stays off.' : undefined
          }
        >
          {submitReview ? 'Review' : 'Ask'}
        </button>
      </form>
    </div>
  );
}
