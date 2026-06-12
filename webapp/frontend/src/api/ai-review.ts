export type AiReviewMessage = {
  role: 'user' | 'assistant';
  content: string;
};

export type AiReviewRequest = {
  source?: string;
  target?: string;
  localeTag?: string;
  sourceDescription?: string;
  tmTextUnitId?: number;
  messages: AiReviewMessage[];
};

export type AiReviewSuggestion = {
  content: string;
  confidenceLevel?: number;
  explanation?: string;
};

export type AiReviewReview = {
  score: number;
  explanation: string;
};

export type AiReviewResponse = {
  message: AiReviewMessage;
  suggestions: AiReviewSuggestion[];
  review?: AiReviewReview;
};

type ProtoAiReviewTarget = {
  content?: string | null;
  explanation?: string | null;
  confidenceLevel?: number | null;
};

type ProtoAiReviewReview = {
  score?: number | null;
  explanation?: string | null;
};

type ProtoAiReviewOutput = {
  target?: ProtoAiReviewTarget | null;
  altTarget?: ProtoAiReviewTarget | null;
  existingTargetRating?: ProtoAiReviewReview | null;
  reviewRequired?: {
    required?: boolean | null;
    reason?: string | null;
  } | null;
};

type ProtoAiReviewSingleTextUnitResponse = {
  aiReviewOutput?: ProtoAiReviewOutput | null;
};

export type AiReviewRequestError = Error & {
  status?: number;
  detail?: string;
};

export async function requestAiReview(
  payload: AiReviewRequest,
  options: { signal?: AbortSignal } = {},
): Promise<AiReviewResponse> {
  if (!payload.messages.length) {
    throw new Error('AI review requires at least one message.');
  }

  return postJson<AiReviewResponse>('/api/ai/review', payload, options);
}

export async function fetchPrecomputedAiReview(
  tmTextUnitVariantId: number | null | undefined,
  options: { signal?: AbortSignal } = {},
): Promise<AiReviewResponse | null> {
  if (tmTextUnitVariantId == null) {
    return null;
  }

  const params = new URLSearchParams({
    tmTextUnitVariantId: String(tmTextUnitVariantId),
    onlyPrecomputed: 'true',
  });
  const response = await getJson<ProtoAiReviewSingleTextUnitResponse>(
    `/api/proto-ai-review-single-text-unit?${params.toString()}`,
    options,
  );
  return toAiReviewResponse(response.aiReviewOutput);
}

export function formatAiReviewError(error: unknown): {
  message: string;
  detail?: string;
} {
  const fallback = 'Unable to fetch AI suggestions. Please retry.';

  if (!(error instanceof Error)) {
    return { message: fallback };
  }

  const typedError = error as AiReviewRequestError;
  const status = typedError.status;
  const detail = typedError.detail;

  if (typeof status === 'number') {
    if (status === 429) {
      return {
        message: 'AI review is rate-limited right now (429). Please retry.',
        detail,
      };
    }
    if (status >= 500) {
      return {
        message: `AI review is temporarily unavailable (${status}). Please retry.`,
        detail,
      };
    }
    return {
      message: `AI review request failed (${status}). Please retry.`,
      detail,
    };
  }

  if (isLikelyHtml(error.message)) {
    return { message: fallback };
  }

  const message = error.message.trim();
  return { message: message || fallback };
}

async function getJson<TResponse>(
  url: string,
  options: { signal?: AbortSignal } = {},
): Promise<TResponse> {
  const response = await fetch(url, {
    method: 'GET',
    credentials: 'same-origin',
    signal: options.signal,
    headers: {
      Accept: 'application/json',
    },
  });

  const text = await response.text();
  if (!response.ok) {
    const contentType = response.headers.get('content-type');
    const details = extractErrorDetails(text, contentType);
    const error: AiReviewRequestError = new Error(
      details.message ?? `Request failed with status ${response.status}`,
    );
    error.status = response.status;
    if (details.detail) {
      error.detail = details.detail;
    }
    throw error;
  }

  if (!text) {
    throw new Error('AI review returned an empty response.');
  }

  return JSON.parse(text) as TResponse;
}

async function postJson<TResponse>(
  url: string,
  body: unknown,
  options: { signal?: AbortSignal } = {},
): Promise<TResponse> {
  const response = await fetch(url, {
    method: 'POST',
    credentials: 'same-origin',
    signal: options.signal,
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(body),
  });

  const text = await response.text();
  if (!response.ok) {
    const contentType = response.headers.get('content-type');
    const details = extractErrorDetails(text, contentType);
    const error: AiReviewRequestError = new Error(
      details.message ?? `Request failed with status ${response.status}`,
    );
    error.status = response.status;
    if (details.detail) {
      error.detail = details.detail;
    }
    throw error;
  }

  if (!text) {
    throw new Error('AI review returned an empty response.');
  }

  return JSON.parse(text) as TResponse;
}

function toAiReviewResponse(
  output: ProtoAiReviewOutput | null | undefined,
): AiReviewResponse | null {
  if (!output) {
    return null;
  }

  const suggestions = dedupeSuggestions(
    [output.target, output.altTarget]
      .map(toAiReviewSuggestion)
      .filter((suggestion): suggestion is AiReviewSuggestion => suggestion != null),
  );
  const review = toAiReviewReview(output.existingTargetRating) ?? undefined;
  const reviewRequiredReason = normalizeOptionalText(output.reviewRequired?.reason);
  const reviewRequired = output.reviewRequired?.required === true;
  const reviewRequiredFallback = reviewRequired
    ? 'AI review marked this translation for review.'
    : null;
  if (suggestions.length === 0 && !review && !reviewRequiredReason && !reviewRequiredFallback) {
    return null;
  }

  const reply =
    normalizeOptionalText(output.target?.explanation) ??
    reviewRequiredReason ??
    reviewRequiredFallback ??
    'Here are the latest suggestions.';

  return {
    message: {
      role: 'assistant',
      content: reply,
    },
    suggestions,
    review,
  };
}

function toAiReviewSuggestion(
  target: ProtoAiReviewTarget | null | undefined,
): AiReviewSuggestion | null {
  const content = normalizeOptionalText(target?.content);
  if (!content) {
    return null;
  }
  return {
    content,
    confidenceLevel: target?.confidenceLevel ?? undefined,
    explanation: normalizeOptionalText(target?.explanation) ?? undefined,
  };
}

function toAiReviewReview(review: ProtoAiReviewReview | null | undefined): AiReviewReview | null {
  const explanation = normalizeOptionalText(review?.explanation);
  const score = review?.score;
  if (!isAiReviewScore(score) || !explanation) {
    return null;
  }

  return {
    score,
    explanation,
  };
}

function isAiReviewScore(score: number | null | undefined): score is number {
  return typeof score === 'number' && Number.isInteger(score) && score >= 0 && score <= 2;
}

function dedupeSuggestions(suggestions: AiReviewSuggestion[]): AiReviewSuggestion[] {
  const seen = new Set<string>();
  return suggestions.filter((suggestion) => {
    if (seen.has(suggestion.content)) {
      return false;
    }
    seen.add(suggestion.content);
    return true;
  });
}

function extractErrorDetails(
  responseText: string,
  contentType: string | null,
): { message: string; detail?: string } {
  const trimmed = responseText.trim();
  if (!trimmed) {
    return { message: 'Request failed.' };
  }

  const hasHtmlContentType = (contentType ?? '').toLowerCase().includes('text/html');
  if (hasHtmlContentType || isLikelyHtml(trimmed)) {
    return { message: 'Request failed.' };
  }

  const parsedJsonMessage = parseJsonMessage(trimmed);
  const message = truncateText(parsedJsonMessage ?? firstLine(trimmed), 220);
  const detail = truncateText(trimmed, 1000);
  return {
    message: message || 'Request failed.',
    detail: detail && detail !== message ? detail : undefined,
  };
}

function parseJsonMessage(value: string): string | null {
  try {
    const parsed = JSON.parse(value) as unknown;
    if (!parsed || typeof parsed !== 'object') {
      return null;
    }

    const candidate = parsed as Record<string, unknown>;
    const fromMessage = candidate.message;
    if (typeof fromMessage === 'string' && fromMessage.trim().length > 0) {
      return fromMessage.trim();
    }
    const fromError = candidate.error;
    if (typeof fromError === 'string' && fromError.trim().length > 0) {
      return fromError.trim();
    }
    return null;
  } catch {
    return null;
  }
}

function firstLine(value: string): string {
  return value.split(/\r?\n/, 1)[0]?.trim() ?? '';
}

function truncateText(value: string, maxLength: number): string {
  if (value.length <= maxLength) {
    return value;
  }
  return `${value.slice(0, maxLength - 1).trimEnd()}…`;
}

function normalizeOptionalText(value: string | null | undefined): string | null {
  const trimmed = value?.trim();
  return trimmed ? trimmed : null;
}

function isLikelyHtml(value: string): boolean {
  const sample = value.trim().slice(0, 600).toLowerCase();
  return (
    sample.startsWith('<!doctype html') ||
    sample.startsWith('<html') ||
    sample.includes('<body') ||
    sample.includes('<head') ||
    sample.includes('<title')
  );
}
