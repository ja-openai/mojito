import { afterEach, describe, expect, it, vi } from 'vitest';

import { type AiReviewRequest, fetchPrecomputedAiReview, requestAiReview } from './ai-review';

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('requestAiReview', () => {
  const review = {
    message: { role: 'assistant', content: 'Looks good.' },
    suggestions: [],
  };
  const json = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });

  it.each(['review_project', 'text_unit_detail'] as const)(
    'waits briefly before submitting an automatic review from %s',
    async (surface) => {
      vi.useFakeTimers();
      const controller = new AbortController();
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(json({ taskId: 42 }, 202))
        .mockResolvedValueOnce(json({ status: 'completed', response: review }));
      vi.stubGlobal('fetch', fetchMock);

      const request = requestAiReview(
        {
          requestType: 'automatic',
          surface,
          messages: [{ role: 'user', content: 'Review.' }],
        },
        { signal: controller.signal },
      );
      await vi.advanceTimersByTimeAsync(299);
      expect(fetchMock).not.toHaveBeenCalled();
      await vi.advanceTimersByTimeAsync(1);
      await expect(request).resolves.toEqual(review);
      controller.abort();
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(vi.getTimerCount()).toBe(0);
    },
  );

  it('cancels an automatic review during the delay without creating a server task', async () => {
    vi.useFakeTimers();
    const controller = new AbortController();
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const result = requestAiReview(
      { requestType: 'automatic', messages: [{ role: 'user', content: 'Review.' }] },
      { signal: controller.signal },
    ).catch((error: unknown) => error);
    await vi.advanceTimersByTimeAsync(299);
    controller.abort();
    await expect(result).resolves.toMatchObject({ name: 'AbortError' });
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(1000);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('only submits the current string when navigation aborts earlier automatic reviews', async () => {
    vi.useFakeTimers();
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json({ taskId: 42 }, 202))
      .mockResolvedValueOnce(json({ status: 'completed', response: review }));
    vi.stubGlobal('fetch', fetchMock);
    const requests: Promise<unknown>[] = [];
    let controller: AbortController | undefined;

    for (const tmTextUnitId of [31, 32, 33]) {
      controller?.abort();
      controller = new AbortController();
      requests.push(
        requestAiReview(
          {
            tmTextUnitId,
            requestType: 'automatic',
            messages: [{ role: 'user', content: 'Review.' }],
          },
          { signal: controller.signal },
        ).catch((error: unknown) => error),
      );
      await vi.advanceTimersByTimeAsync(100);
    }

    expect(fetchMock).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(200);
    await expect(Promise.all(requests)).resolves.toEqual([
      expect.objectContaining({ name: 'AbortError' }),
      expect.objectContaining({ name: 'AbortError' }),
      review,
    ]);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string)).toMatchObject({
      tmTextUnitId: 33,
    });
  });

  it('does not submit when navigation aborts as the automatic delay ends', async () => {
    vi.useFakeTimers();
    const controller = new AbortController();
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const result = requestAiReview(
      { requestType: 'automatic', messages: [{ role: 'user', content: 'Review.' }] },
      { signal: controller.signal },
    ).catch((error: unknown) => error);
    vi.advanceTimersByTime(300);
    controller.abort();
    await expect(result).resolves.toMatchObject({ name: 'AbortError' });
    expect(fetchMock).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it.each<AiReviewRequest['requestType']>(['manual', 'follow_up', 'retry', undefined])(
    'submits %s requests immediately',
    async (requestType) => {
      vi.useFakeTimers();
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(json({ taskId: 42 }, 202))
        .mockResolvedValueOnce(json({ status: 'completed', response: review }));
      vi.stubGlobal('fetch', fetchMock);

      const request = requestAiReview({
        requestType,
        messages: [{ role: 'user', content: 'Review.' }],
      });
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(vi.getTimerCount()).toBe(0);
      await expect(request).resolves.toEqual(review);
    },
  );

  it('preserves review context and uses the abort signal for polling', async () => {
    const abortController = new AbortController();
    const payload = {
      source: 'Account',
      target: 'Compte',
      localeTag: 'fr',
      sourceDescription: 'Account navigation label',
      tmTextUnitId: 31,
      presetId: 'balanced' as const,
      reviewStyle: 'corrections_and_alternatives' as const,
      messages: [{ role: 'user' as const, content: 'Use the glossary term Compte.' }],
    };
    const response = {
      ...review,
      suggestions: [{ content: 'Mon compte', kind: 'alternative', confidenceLevel: 94 }],
      review: { score: 2, explanation: 'The current label is valid.', confidenceLevel: 96 },
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json({ taskId: 42 }, 202))
      .mockResolvedValueOnce(json({ status: 'completed', response }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(requestAiReview(payload, { signal: abortController.signal })).resolves.toEqual(
      response,
    );

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/ai/review/jobs',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(payload),
        signal: undefined,
      }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/ai/review/jobs/42',
      expect.objectContaining({ method: 'GET', signal: abortController.signal }),
    );
  });

  it('waits beyond a gateway deadline using short polls without restarting the review', async () => {
    vi.useFakeTimers();
    const startedAt = Date.now();
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json({ taskId: 42 }, 202))
      .mockImplementation(() =>
        Promise.resolve(
          json(
            Date.now() - startedAt < 35_000
              ? { status: 'pending' }
              : { status: 'completed', response: review },
          ),
        ),
      );
    vi.stubGlobal('fetch', fetchMock);

    const request = requestAiReview({ messages: [{ role: 'user', content: 'Review.' }] });
    await vi.advanceTimersByTimeAsync(35_000);
    await expect(request).resolves.toEqual(review);
    expect(
      fetchMock.mock.calls.filter(([, options]) => (options as RequestInit).method === 'POST'),
    ).toHaveLength(1);
    expect(fetchMock.mock.calls.slice(1).every(([url]) => url === '/api/ai/review/jobs/42')).toBe(
      true,
    );
  });

  it('retries a transient polling failure without submitting another job', async () => {
    vi.useFakeTimers();
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json({ taskId: 42 }, 202))
      .mockResolvedValueOnce(json({ message: 'Gateway unavailable' }, 504))
      .mockResolvedValueOnce(json({ status: 'completed', response: review }));
    vi.stubGlobal('fetch', fetchMock);

    const request = requestAiReview({ messages: [{ role: 'user', content: 'Review.' }] });
    await vi.advanceTimersByTimeAsync(1000);
    await expect(request).resolves.toEqual(review);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[2]?.[0]).toBe('/api/ai/review/jobs/42');
  });

  it('keeps a provider failure terminal and preserves its status and reason', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json({ taskId: 42 }, 202))
      .mockResolvedValueOnce(
        json({
          status: 'failed',
          error: { status: 504, message: 'The review provider timed out.' },
        }),
      );
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      requestAiReview({ messages: [{ role: 'user', content: 'Review.' }] }),
    ).rejects.toMatchObject({ status: 504, detail: 'The review provider timed out.' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('cancels server work and stops polling when navigation aborts the request', async () => {
    vi.useFakeTimers();
    const abortController = new AbortController();
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json({ taskId: 42 }, 202))
      .mockImplementation(() => Promise.resolve(json({ status: 'pending' })));
    vi.stubGlobal('fetch', fetchMock);

    const result = requestAiReview(
      { messages: [{ role: 'user', content: 'Review.' }] },
      { signal: abortController.signal },
    ).catch((error: unknown) => error);
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    abortController.abort();
    await vi.advanceTimersByTimeAsync(1000);
    await expect(result).resolves.toMatchObject({ name: 'AbortError' });
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/ai/review/jobs/42',
      expect.objectContaining({ method: 'DELETE', keepalive: true }),
    );
  });

  it('cancels an automatic task when navigation happens after submission but before its response', async () => {
    vi.useFakeTimers();
    const controller = new AbortController();
    let submitted!: (response: Response) => void;
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            submitted = resolve;
          }),
      )
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);
    const result = requestAiReview(
      { requestType: 'automatic', messages: [{ role: 'user', content: 'Review.' }] },
      { signal: controller.signal },
    ).catch((error: unknown) => error);
    await vi.advanceTimersByTimeAsync(300);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    controller.abort();
    submitted(json({ taskId: 73 }, 202));
    await expect(result).resolves.toMatchObject({ name: 'AbortError' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/ai/review/jobs/73',
      expect.objectContaining({ method: 'DELETE' }),
    );
    expect(vi.getTimerCount()).toBe(0);
  });

  it('does not submit an already aborted request', async () => {
    const abortController = new AbortController();
    abortController.abort();
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    await expect(
      requestAiReview(
        { messages: [{ role: 'user', content: 'Review.' }] },
        { signal: abortController.signal },
      ),
    ).rejects.toMatchObject({ name: 'AbortError' });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('fetchPrecomputedAiReview', () => {
  it('returns null without requesting when there is no variant id', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchPrecomputedAiReview(null)).resolves.toBeNull();

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('maps cached proto review output to the AI review response shape', async () => {
    const abortController = new AbortController();
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          aiReviewOutput: {
            target: {
              content: 'Afficher les aperçus mobiles',
              explanation: 'More natural UI wording.',
              confidenceLevel: 91,
            },
            altTarget: {
              content: 'Voir les aperçus mobiles',
              explanation: 'Acceptable but less direct.',
              confidenceLevel: 74,
            },
            existingTargetRating: {
              score: 2,
              explanation: 'Understandable, but slightly awkward.',
            },
          },
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchPrecomputedAiReview(31, { signal: abortController.signal })).resolves.toEqual(
      {
        message: { role: 'assistant', content: 'More natural UI wording.' },
        suggestions: [
          {
            content: 'Afficher les aperçus mobiles',
            explanation: 'More natural UI wording.',
            confidenceLevel: 91,
          },
          {
            content: 'Voir les aperçus mobiles',
            explanation: 'Acceptable but less direct.',
            confidenceLevel: 74,
          },
        ],
        review: {
          score: 2,
          explanation: 'Understandable, but slightly awkward.',
        },
      },
    );
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/proto-ai-review-single-text-unit?tmTextUnitVariantId=31&onlyPrecomputed=true',
      expect.objectContaining({
        method: 'GET',
        signal: abortController.signal,
      }),
    );
  });

  it('returns null when the backend has no precomputed output', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ aiReviewOutput: null }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchPrecomputedAiReview(31)).resolves.toBeNull();
  });

  it('returns null when cached proto review output has no useful content', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ aiReviewOutput: {} }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchPrecomputedAiReview(31)).resolves.toBeNull();
  });

  it('returns null when cached proto review rating is incomplete', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          aiReviewOutput: {
            existingTargetRating: {
              score: 1,
            },
          },
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchPrecomputedAiReview(31)).resolves.toBeNull();
  });

  it('returns null when cached proto review rating has no score', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          aiReviewOutput: {
            existingTargetRating: {
              explanation: 'Score is missing.',
            },
          },
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchPrecomputedAiReview(31)).resolves.toBeNull();
  });

  it('returns null when cached proto review rating score is out of range', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          aiReviewOutput: {
            existingTargetRating: {
              score: 82,
              explanation: 'Out-of-range score should not be used.',
            },
          },
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchPrecomputedAiReview(31)).resolves.toBeNull();
  });

  it('dedupes cached target suggestions to match live AI review responses', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          aiReviewOutput: {
            target: {
              content: 'Afficher les aperçus mobiles',
              explanation: 'Natural UI wording.',
              confidenceLevel: 91,
            },
            altTarget: {
              content: 'Afficher les aperçus mobiles',
              explanation: 'Same translation repeated.',
              confidenceLevel: 74,
            },
          },
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchPrecomputedAiReview(31)).resolves.toMatchObject({
      suggestions: [
        {
          content: 'Afficher les aperçus mobiles',
          explanation: 'Natural UI wording.',
          confidenceLevel: 91,
        },
      ],
    });
  });

  it('keeps cached proto review-required reasons even without suggestions', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          aiReviewOutput: {
            reviewRequired: {
              required: false,
              reason: 'Existing translation is acceptable.',
            },
          },
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchPrecomputedAiReview(31)).resolves.toEqual({
      message: { role: 'assistant', content: 'Existing translation is acceptable.' },
      suggestions: [],
      review: undefined,
    });
  });

  it('keeps cached proto review-required flags even when the reason is missing', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          aiReviewOutput: {
            reviewRequired: {
              required: true,
            },
          },
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchPrecomputedAiReview(31)).resolves.toEqual({
      message: {
        role: 'assistant',
        content: 'AI review marked this translation for review.',
      },
      suggestions: [],
      review: undefined,
    });
  });
});
