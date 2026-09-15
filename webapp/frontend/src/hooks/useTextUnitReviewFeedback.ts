import { useQuery } from '@tanstack/react-query';
import { type ComponentProps, useCallback, useRef, useState } from 'react';

import {
  fetchTextUnitFeedbackBaseline,
  type ReviewerFeedback,
  type ReviewFeedbackReason,
} from '../api/review-feedback';
import type { SaveTextUnitRequest } from '../api/text-units';
import { isMaterialReviewEdit } from '../components/review-feedback/review-edit-diff';
import type { ReviewEditFeedback } from '../components/review-feedback/ReviewEditFeedback';
import { useReviewFeedbackVisibility } from '../components/review-feedback/useReviewFeedbackVisibility';

export type TextUnitFeedbackWidget = Omit<ComponentProps<typeof ReviewEditFeedback>, 'disabled'>;

const emptyFeedback = () => ({
  reason: '' as ReviewFeedbackReason | '',
  note: '',
  chatUsed: false,
  aiSuggestionUsed: false,
});

/** One draft belongs to the exact translation being edited, independently of query refreshes. */
export function useTextUnitReviewFeedback({
  username,
  tmTextUnitId,
  localeId,
  variantId,
  baselineTarget,
  target,
  enabled,
  problematic = false,
}: {
  username: string;
  tmTextUnitId: number | null;
  localeId: number | null;
  variantId: number | null;
  baselineTarget: string;
  target: string;
  enabled: boolean;
  problematic?: boolean;
}) {
  const owner = `${username}:${tmTextUnitId}:${localeId}:${variantId}`;
  const [draft, setDraft] = useState(() => ({ owner, values: emptyFeedback() }));
  if (draft.owner !== owner) setDraft({ owner, values: emptyFeedback() });
  const values = draft.owner === owner ? draft.values : emptyFeedback();
  const valuesRef = useRef({ owner, values });
  valuesRef.current = { owner, values };
  const lastRequest = useRef<{
    owner: string;
    fingerprint: string;
    request: SaveTextUnitRequest;
  } | null>(null);
  const baseline = useQuery({
    queryKey: ['text-unit-feedback-baseline', username, tmTextUnitId, localeId, variantId],
    queryFn: ({ signal }) =>
      fetchTextUnitFeedbackBaseline(tmTextUnitId!, localeId!, variantId!, signal),
    enabled: enabled && tmTextUnitId !== null && localeId !== null && variantId !== null,
    staleTime: Infinity,
    retry: false,
  });

  const update = useCallback(
    (patch: Partial<ReturnType<typeof emptyFeedback>>) => {
      setDraft((current) =>
        current.owner === owner ? { owner, values: { ...current.values, ...patch } } : current,
      );
    },
    [owner],
  );
  const reset = useCallback(() => {
    setDraft((current) => (current.owner === owner ? { owner, values: emptyFeedback() } : current));
    lastRequest.current = null;
  }, [owner]);

  const decorateRequest = useCallback(
    (request: SaveTextUnitRequest): SaveTextUnitRequest => {
      if (!enabled || variantId === null) return request;
      const current = valuesRef.current;
      if (current.owner !== owner) return request;
      const reviewFeedback: ReviewerFeedback = {
        reason: current.values.reason || undefined,
        note: current.values.note || undefined,
        chatUsed: current.values.chatUsed,
        aiSuggestionUsed: current.values.aiSuggestionUsed,
      };
      const payload = { ...request, reviewedVariantId: variantId, reviewFeedback };
      const fingerprint = `${owner}:${JSON.stringify(payload)}`;
      if (lastRequest.current?.fingerprint === fingerprint) return lastRequest.current.request;
      const captured = { ...payload, feedbackOperationId: crypto.randomUUID() };
      lastRequest.current = { owner, fingerprint, request: captured };
      return captured;
    },
    [enabled, owner, variantId],
  );

  const saved = useCallback((request: SaveTextUnitRequest) => {
    if (!request.feedbackOperationId) return;
    const recorded = lastRequest.current;
    if (recorded?.request.feedbackOperationId !== request.feedbackOperationId) return;
    const requestOwner = recorded.owner;
    setDraft((current) => {
      const feedback = request.reviewFeedback;
      if (
        current.owner !== requestOwner ||
        current.values.reason !== (feedback?.reason ?? '') ||
        current.values.note !== (feedback?.note ?? '') ||
        current.values.chatUsed !== (feedback?.chatUsed ?? false) ||
        current.values.aiSuggestionUsed !== (feedback?.aiSuggestionUsed ?? false)
      )
        return current;
      return { owner: current.owner, values: emptyFeedback() };
    });
    if (lastRequest.current?.request.feedbackOperationId === request.feedbackOperationId)
      lastRequest.current = null;
  }, []);

  const dirty = Boolean(values.reason || values.note);
  const show = useReviewFeedbackVisibility(
    enabled && tmTextUnitId !== null && localeId !== null && variantId !== null
      ? JSON.stringify([username, tmTextUnitId, localeId])
      : null,
    dirty ||
      (baseline.data?.ai === true && (problematic || isMaterialReviewEdit(baselineTarget, target))),
  );
  const widget: TextUnitFeedbackWidget | null = show
    ? {
        reason: values.reason,
        note: values.note,
        onReason: (reason) => update({ reason }),
        onNote: (note) => update({ note }),
      }
    : null;
  const recordChatUsed = useCallback(() => update({ chatUsed: true }), [update]);
  const recordSuggestionUsed = useCallback(() => update({ aiSuggestionUsed: true }), [update]);

  return {
    widget,
    dirty,
    decorateRequest,
    saved,
    reset,
    recordChatUsed,
    recordSuggestionUsed,
  };
}
