import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchPrecomputedAiReview, requestAiReview } from './ai-review';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('requestAiReview', () => {
  it('passes the abort signal to fetch', async () => {
    const abortController = new AbortController();
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          message: { role: 'assistant', content: 'Looks good.' },
          suggestions: [],
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    await requestAiReview(
      {
        messages: [{ role: 'user', content: 'Review this translation.' }],
      },
      { signal: abortController.signal },
    );

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({
      signal: abortController.signal,
    });
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
