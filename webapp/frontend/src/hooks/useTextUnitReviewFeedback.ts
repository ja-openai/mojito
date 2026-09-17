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

export type TextUnitFeedbackWidget = ComponentProps<typeof ReviewEditFeedback>;
export type TextUnitFeedbackDraft = {
  reason: ReviewFeedbackReason | '';
  note: string;
  chatUsed: boolean;
  aiSuggestionUsed: boolean;
};

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
  retainedDraft,
}: {
  username: string;
  tmTextUnitId: number | null;
  localeId: number | null;
  variantId: number | null;
  baselineTarget: string;
  target: string;
  enabled: boolean;
  problematic?: boolean;
  retainedDraft?: {
    values: TextUnitFeedbackDraft;
    onChange: (change: (values: TextUnitFeedbackDraft) => TextUnitFeedbackDraft) => void;
  };
}) {
  const owner = `${username}:${tmTextUnitId}:${localeId}:${variantId}`;
  const [draft, setDraft] = useState(() => ({ owner, values: emptyFeedback() }));
  if (draft.owner !== owner) setDraft({ owner, values: emptyFeedback() });
  const values = retainedDraft?.values ?? (draft.owner === owner ? draft.values : emptyFeedback());
  const retainedDraftRef = useRef(retainedDraft);
  retainedDraftRef.current = retainedDraft;
  const valuesRef = useRef({ owner, values, target });
  valuesRef.current = { owner, values, target };
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
      if (retainedDraftRef.current) {
        retainedDraftRef.current.onChange((current) => ({ ...current, ...patch }));
        return;
      }
      setDraft((current) =>
        current.owner === owner ? { owner, values: { ...current.values, ...patch } } : current,
      );
    },
    [owner],
  );
  const reset = useCallback(() => {
    retainedDraftRef.current?.onChange(() => emptyFeedback());
    setDraft((current) => (current.owner === owner ? { owner, values: emptyFeedback() } : current));
    lastRequest.current = null;
  }, [owner]);

  const decorateRequest = useCallback(
    (request: SaveTextUnitRequest): SaveTextUnitRequest => {
      if (!enabled || variantId === null) return request;
      const current = valuesRef.current;
      if (current.owner !== owner) return request;
      const acceptsFeedback =
        !request.includedInLocalizedFile ||
        request.target.normalize('NFC') !== baselineTarget.normalize('NFC');
      const reviewFeedback: ReviewerFeedback = {
        reason: acceptsFeedback ? current.values.reason || undefined : undefined,
        note: acceptsFeedback ? current.values.note || undefined : undefined,
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
    [baselineTarget, enabled, owner, variantId],
  );

  const saved = useCallback((request: SaveTextUnitRequest) => {
    if (!request.feedbackOperationId) return;
    const recorded = lastRequest.current;
    if (recorded?.request.feedbackOperationId !== request.feedbackOperationId) return;
    const requestOwner = recorded.owner;
    const currentValues = valuesRef.current;
    if (
      retainedDraftRef.current &&
      currentValues.owner === requestOwner &&
      currentValues.target === request.target &&
      currentValues.values.reason === (request.reviewFeedback?.reason ?? '') &&
      currentValues.values.note === (request.reviewFeedback?.note ?? '') &&
      currentValues.values.chatUsed === (request.reviewFeedback?.chatUsed ?? false) &&
      currentValues.values.aiSuggestionUsed === (request.reviewFeedback?.aiSuggestionUsed ?? false)
    ) {
      retainedDraftRef.current.onChange(() => emptyFeedback());
    }
    setDraft((current) => {
      const feedback = request.reviewFeedback;
      if (
        current.owner !== requestOwner ||
        valuesRef.current.target !== request.target ||
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
  const active = problematic || target.normalize('NFC') !== baselineTarget.normalize('NFC');
  const activeDirty = dirty && active;
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
        disabled: !active,
        onReason: (reason) => update({ reason }),
        onNote: (note) => update({ note }),
      }
    : null;
  const recordChatUsed = useCallback(() => update({ chatUsed: true }), [update]);
  const recordSuggestionUsed = useCallback(() => update({ aiSuggestionUsed: true }), [update]);

  return {
    widget,
    dirty,
    activeDirty,
    decorateRequest,
    saved,
    reset,
    recordChatUsed,
    recordSuggestionUsed,
  };
}
