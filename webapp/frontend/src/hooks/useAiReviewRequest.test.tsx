import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type * as AiReviewApi from '../api/ai-review';
import { type AiReviewResponse, requestAiReview } from '../api/ai-review';
import type { AiChatReviewMessage } from '../components/AiChatReview';
import { useAiReviewRequest } from './useAiReviewRequest';

vi.mock('../api/ai-review', async (importActual) => ({
  ...(await importActual<typeof AiReviewApi>()),
  requestAiReview: vi.fn(),
}));

function deferredReview() {
  let resolve!: (response: AiReviewResponse) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<AiReviewResponse>((accept, fail) => {
    resolve = accept;
    reject = fail;
  });
  return { promise, resolve, reject };
}

const payload = { target: 'Bonjour', messages: [{ role: 'user' as const, content: 'Review.' }] };
const response: AiReviewResponse = {
  message: { role: 'assistant', content: 'A useful correction.' },
  suggestions: [{ content: 'Bienvenue' }],
};

afterEach(() => vi.clearAllMocks());

describe('AI review request ownership', () => {
  it('ignores a replaced response and keeps the newer request active through old effect cleanup', async () => {
    const automatic = deferredReview();
    const manual = deferredReview();
    vi.mocked(requestAiReview)
      .mockReturnValueOnce(automatic.promise)
      .mockReturnValueOnce(manual.promise);
    const onAutomatic = vi.fn();
    const onManual = vi.fn();
    const { result } = renderHook(useAiReviewRequest);
    let cancelAutomatic!: () => void;
    act(() => {
      cancelAutomatic = result.current.start({ ...payload, requestType: 'automatic' }, onAutomatic);
      result.current.start({ ...payload, requestType: 'manual' }, onManual);
      cancelAutomatic();
    });
    expect(vi.mocked(requestAiReview).mock.calls[0][1]?.signal?.aborted).toBe(true);
    expect(vi.mocked(requestAiReview).mock.calls[1][1]?.signal?.aborted).toBe(false);
    await act(async () => {
      automatic.resolve(response);
      await automatic.promise;
    });
    expect(onAutomatic).not.toHaveBeenCalled();
    expect(result.current.isResponding).toBe(true);
    await act(async () => {
      manual.resolve(response);
      await manual.promise;
    });
    expect(onManual).toHaveBeenCalledWith(expect.objectContaining({ reviewedTarget: 'Bonjour' }));
    expect(result.current.isResponding).toBe(false);
  });

  it('discards failures after cancellation and aborts on unmount', async () => {
    const cancelled = deferredReview();
    const unmounted = deferredReview();
    vi.mocked(requestAiReview)
      .mockReturnValueOnce(cancelled.promise)
      .mockReturnValueOnce(unmounted.promise);
    const onMessage = vi.fn<(message: AiChatReviewMessage) => void>();
    const { result, unmount } = renderHook(useAiReviewRequest);
    act(() => {
      result.current.start(payload, onMessage);
      result.current.cancel();
    });
    await act(async () => {
      cancelled.reject(new Error('Late failure'));
      await cancelled.promise.catch(() => undefined);
    });
    expect(onMessage).not.toHaveBeenCalled();
    expect(result.current.isResponding).toBe(false);
    act(() => {
      result.current.start(payload, onMessage);
    });
    unmount();
    expect(vi.mocked(requestAiReview).mock.calls[1][1]?.signal?.aborted).toBe(true);
    await act(async () => {
      unmounted.resolve(response);
      await unmounted.promise;
    });
    expect(onMessage).not.toHaveBeenCalled();
  });

  it('keeps page-owned suggestions and reports a live request failure', async () => {
    vi.mocked(requestAiReview)
      .mockResolvedValueOnce(response)
      .mockRejectedValueOnce(
        Object.assign(new Error('Unavailable'), { status: 429, detail: 'Retry later.' }),
      );
    const ownedSuggestions = [{ content: 'Bienvenue', explanation: 'Owned by this review.' }];
    const onMessage = vi.fn<(message: AiChatReviewMessage) => void>();
    const { result } = renderHook(useAiReviewRequest);
    await act(async () => {
      result.current.start(payload, onMessage, () => ownedSuggestions);
      await Promise.resolve();
    });
    expect(onMessage.mock.calls[0][0].suggestions).toBe(ownedSuggestions);
    await act(async () => {
      result.current.start(payload, onMessage);
      await Promise.resolve();
    });
    expect(onMessage).toHaveBeenLastCalledWith(
      expect.objectContaining({
        isError: true,
        content: 'AI review is rate-limited right now (429). Please retry.',
        errorDetail: 'Retry later.',
      }),
    );
    expect(result.current.isResponding).toBe(false);
  });
});
