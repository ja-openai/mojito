import { useCallback, useEffect, useRef, useState } from 'react';

import {
  type AiReviewRequest,
  type AiReviewSuggestion,
  formatAiReviewError,
  requestAiReview,
} from '../api/ai-review';
import type { AiChatReviewMessage } from '../components/AiChatReview';

/** One active request per editor; page owners decide how to retain its conversation. */
export function useAiReviewRequest() {
  const activeRequest = useRef<AbortController | null>(null);
  const [isResponding, setIsResponding] = useState(false);
  const cancel = useCallback(() => {
    const request = activeRequest.current;
    activeRequest.current = null;
    request?.abort();
    setIsResponding(false);
  }, []);

  useEffect(
    () => () => {
      activeRequest.current?.abort();
      activeRequest.current = null;
    },
    [],
  );

  const start = useCallback(
    (
      payload: AiReviewRequest,
      onMessage: (message: AiChatReviewMessage) => void,
      ownSuggestions?: (suggestions: AiReviewSuggestion[]) => AiReviewSuggestion[],
    ) => {
      cancel();
      const request = new AbortController();
      activeRequest.current = request;
      setIsResponding(true);
      void (async () => {
        try {
          const response = await requestAiReview(payload, { signal: request.signal });
          if (activeRequest.current !== request) return;
          onMessage({
            id: `assistant-${Date.now()}`,
            sender: 'assistant',
            content: response.message.content,
            suggestions: ownSuggestions?.(response.suggestions) ?? response.suggestions,
            review: response.review,
            reviewedTarget: payload.target ?? '',
          });
        } catch (error: unknown) {
          if (activeRequest.current !== request) return;
          const detail = formatAiReviewError(error);
          onMessage({
            id: `assistant-error-${Date.now()}`,
            sender: 'assistant',
            content: detail.message,
            isError: true,
            errorDetail: detail.detail,
          });
        } finally {
          if (activeRequest.current === request) {
            activeRequest.current = null;
            setIsResponding(false);
          }
        }
      })();
      // An automatic effect's cleanup must not cancel a newer manual request.
      return () => {
        request.abort();
        if (activeRequest.current === request) cancel();
      };
    },
    [cancel],
  );

  return { start, cancel, isResponding };
}
