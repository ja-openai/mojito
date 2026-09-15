import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { fetchTextUnitFeedbackBaseline } from '../api/review-feedback';
import type { SaveTextUnitRequest } from '../api/text-units';
import { useTextUnitReviewFeedback } from './useTextUnitReviewFeedback';

vi.mock('../api/review-feedback', () => ({
  fetchTextUnitFeedbackBaseline: vi.fn(),
}));

type HookProps = Parameters<typeof useTextUnitReviewFeedback>[0];
const original = 'Activer les notifications';
const changed = 'Désactiver les notifications';
const initialProps: HookProps = {
  username: 'reviewer',
  tmTextUnitId: 12,
  localeId: 3,
  variantId: 41,
  baselineTarget: original,
  target: changed,
  enabled: true,
};
const request: SaveTextUnitRequest = {
  tmTextUnitId: 12,
  localeId: 3,
  target: changed,
  targetComment: null,
  status: 'APPROVED',
  includedInLocalizedFile: true,
};
const clients: QueryClient[] = [];

beforeEach(() => {
  vi.mocked(fetchTextUnitFeedbackBaseline).mockReset();
  vi.mocked(fetchTextUnitFeedbackBaseline).mockResolvedValue({
    target: original,
    ai: true,
    kind: 'AI_TRANSLATE',
  });
});

afterEach(() => clients.splice(0).forEach((client) => client.clear()));

function setup(overrides: Partial<HookProps> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  clients.push(client);
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return {
    ...renderHook(useTextUnitReviewFeedback, {
      initialProps: { ...initialProps, ...overrides },
      wrapper,
    }),
    client,
  };
}

describe('useTextUnitReviewFeedback', () => {
  it('starts hidden until a material AI edit and stays visible when the edit is undone or cleared', async () => {
    const { result, rerender, client } = setup({ target: original });
    await waitFor(() =>
      expect(
        client.getQueryState(['text-unit-feedback-baseline', 'reviewer', 12, 3, 41])?.status,
      ).toBe('success'),
    );
    expect(fetchTextUnitFeedbackBaseline).toHaveBeenCalledWith(12, 3, 41, expect.any(AbortSignal));
    expect(result.current.widget).toBeNull();
    rerender({ ...initialProps, target: `  ${original.toUpperCase()}!  ` });
    expect(result.current.widget).toBeNull();

    rerender(initialProps);
    expect(result.current.widget).not.toBeNull();
    rerender({ ...initialProps, target: original });
    expect(result.current.widget).not.toBeNull();
    rerender({ ...initialProps, target: '' });
    expect(result.current.widget).not.toBeNull();
    expect(result.current.dirty).toBe(false);
  });

  it('shows feedback for an unchanged AI translation marked problematic', async () => {
    const { result } = setup({ target: original, problematic: true });
    await waitFor(() => expect(result.current.widget).not.toBeNull());
  });

  it('does not infer AI provenance when baseline attribution is absent', async () => {
    vi.mocked(fetchTextUnitFeedbackBaseline).mockResolvedValue({
      target: original,
      ai: false,
      kind: 'UNATTRIBUTED',
    });
    const { result, client } = setup({ problematic: true });
    await waitFor(() =>
      expect(
        client.getQueryState(['text-unit-feedback-baseline', 'reviewer', 12, 3, 41])?.status,
      ).toBe('success'),
    );
    expect(result.current.widget).toBeNull();
  });

  it('keeps the editor usable without a widget when baseline lookup fails', async () => {
    vi.mocked(fetchTextUnitFeedbackBaseline).mockRejectedValue(new Error('Baseline unavailable'));
    const { result, client } = setup();
    await waitFor(() =>
      expect(
        client.getQueryState(['text-unit-feedback-baseline', 'reviewer', 12, 3, 41])?.status,
      ).toBe('error'),
    );
    expect(result.current.widget).toBeNull();
    expect(result.current.decorateRequest(request)).toMatchObject({
      ...request,
      reviewedVariantId: 41,
      feedbackOperationId: expect.any(String) as unknown,
    });
    expect(fetchTextUnitFeedbackBaseline).toHaveBeenCalledTimes(1);
  });

  it('retains optional feedback when returning to the original translation', async () => {
    const { result, rerender } = setup();
    await waitFor(() => expect(result.current.widget).not.toBeNull());
    act(() => {
      result.current.widget!.onReason('WRONG_MEANING');
      result.current.widget!.onNote('The action needs to match the source.');
    });
    rerender({ ...initialProps, target: original });
    expect(result.current.dirty).toBe(true);
    expect(result.current.widget).toMatchObject({
      reason: 'WRONG_MEANING',
      note: 'The action needs to match the source.',
    });
    act(() => result.current.reset());
    expect(result.current.dirty).toBe(false);
    expect(result.current.widget).toMatchObject({ reason: '', note: '' });
  });

  it('stays visible after accepting a new variant of the same translation', async () => {
    const { result, rerender } = setup();
    await waitFor(() => expect(result.current.widget).not.toBeNull());
    act(() => result.current.widget!.onNote('Keep the source meaning.'));

    rerender({ ...initialProps, variantId: 42, baselineTarget: changed });
    expect(result.current.widget).toMatchObject({ reason: '', note: '' });
    expect(result.current.dirty).toBe(false);
  });

  it.each([
    ['reviewer', { username: 'another-reviewer' }],
    ['text unit', { tmTextUnitId: 13 }],
    ['locale', { localeId: 4 }],
    ['disabled editor', { enabled: false }],
  ] satisfies [string, Partial<HookProps>][])(
    'clears visibility for a new %s',
    async (_name, changes) => {
      const { result, rerender } = setup();
      await waitFor(() => expect(result.current.widget).not.toBeNull());

      rerender({ ...initialProps, ...changes, target: original });
      expect(result.current.widget).toBeNull();
      rerender({ ...initialProps, target: original });
      expect(result.current.widget).toBeNull();
    },
  );

  it('freezes feedback in a save request and reuses its operation ID only for exact retries', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.widget).not.toBeNull());
    act(() => {
      result.current.widget!.onReason('WRONG_MEANING');
      result.current.widget!.onNote('Keep the source meaning.');
      result.current.recordChatUsed();
      result.current.recordSuggestionUsed();
    });

    const first = result.current.decorateRequest(request);
    expect(first.reviewFeedback).toEqual({
      reason: 'WRONG_MEANING',
      note: 'Keep the source meaning.',
      chatUsed: true,
      aiSuggestionUsed: true,
    });
    expect(first.reviewedVariantId).toBe(41);
    expect(first.feedbackOperationId).toEqual(expect.any(String));
    expect(result.current.decorateRequest({ ...request })).toBe(first);

    act(() => result.current.widget!.onNote('A newer explanation.'));
    const revised = result.current.decorateRequest(request);
    expect(revised.feedbackOperationId).not.toBe(first.feedbackOperationId);
    expect(first.reviewFeedback?.note).toBe('Keep the source meaning.');
    expect(revised.reviewFeedback?.note).toBe('A newer explanation.');
    expect(result.current.decorateRequest({ ...request })).toBe(revised);

    const changedTarget = result.current.decorateRequest({ ...request, target: original });
    expect(changedTarget.feedbackOperationId).not.toBe(revised.feedbackOperationId);
    const changedStatus = result.current.decorateRequest({ ...request, status: 'REVIEW_NEEDED' });
    expect(changedStatus.feedbackOperationId).not.toBe(changedTarget.feedbackOperationId);
  });

  it('clears saved feedback and issues a new operation ID for a later save', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.widget).not.toBeNull());
    act(() => result.current.widget!.onNote('Source terminology.'));
    const captured = result.current.decorateRequest(request);
    act(() => result.current.saved(captured));
    expect(result.current.dirty).toBe(false);
    expect(result.current.widget?.note).toBe('');

    const next = result.current.decorateRequest(request);
    expect(next.feedbackOperationId).not.toBe(captured.feedbackOperationId);
    expect(next.reviewFeedback?.note).toBeUndefined();
  });

  it('preserves newer feedback when an earlier save finishes', async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.widget).not.toBeNull());
    act(() => result.current.widget!.onNote('First explanation.'));
    const earlier = result.current.decorateRequest(request);
    act(() => {
      result.current.widget!.onNote('Revised explanation.');
      result.current.widget!.onReason('CONTEXT');
    });
    const newer = result.current.decorateRequest(request);
    act(() => result.current.saved(earlier));
    expect(result.current.widget).toMatchObject({
      reason: 'CONTEXT',
      note: 'Revised explanation.',
    });
    expect(result.current.decorateRequest(request)).toBe(newer);
    act(() => result.current.saved(newer));
    expect(result.current.dirty).toBe(false);
  });

  it.each([
    ['reviewer', { username: 'another-reviewer' }],
    ['text unit', { tmTextUnitId: 13 }],
    ['locale', { localeId: 4 }],
    ['variant', { variantId: 42 }],
  ] satisfies [string, Partial<HookProps>][])(
    'resets feedback for a new %s and ignores late callbacks from the old owner',
    async (_name, changes) => {
      const ownerChange: Partial<HookProps> = changes;
      const { result, rerender } = setup();
      await waitFor(() => expect(result.current.widget).not.toBeNull());
      act(() => {
        result.current.widget!.onNote('An explanation.');
        result.current.recordChatUsed();
      });
      const oldWidget = result.current.widget!;
      const oldRequest = result.current.decorateRequest(request);
      const oldSaved = result.current.saved;

      rerender({ ...initialProps, ...ownerChange });
      expect(result.current.dirty).toBe(false);
      await waitFor(() => expect(result.current.widget).not.toBeNull());
      expect(result.current.widget?.note).toBe('');
      const nextOwnerRequest = {
        ...request,
        tmTextUnitId: ownerChange.tmTextUnitId ?? request.tmTextUnitId,
        localeId: ownerChange.localeId ?? request.localeId,
      };
      expect(result.current.decorateRequest(nextOwnerRequest).reviewFeedback?.chatUsed).toBe(false);
      act(() => {
        result.current.widget!.onNote('An explanation.');
        result.current.recordChatUsed();
        oldWidget.onNote('Late edit from the previous string.');
        oldSaved(oldRequest);
      });
      expect(result.current.widget?.note).toBe('An explanation.');
      expect(result.current.dirty).toBe(true);
      const next = result.current.decorateRequest(nextOwnerRequest);
      expect(next.feedbackOperationId).not.toBe(oldRequest.feedbackOperationId);
    },
  );

  it('does not query or decorate a disabled editor', () => {
    const { result } = setup({ enabled: false });
    expect(fetchTextUnitFeedbackBaseline).not.toHaveBeenCalled();
    expect(result.current.widget).toBeNull();
    expect(result.current.decorateRequest(request)).toBe(request);
  });
});
