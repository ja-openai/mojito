export type AiReviewMessage = {
  role: 'user' | 'assistant';
  content: string;
};

export type AiReviewRequest = {
  source?: string;
  target?: string;
  localeTag?: string;
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

export type AiReviewRequestError = Error & {
  status?: number;
  detail?: string;
};

export async function requestAiReview(payload: AiReviewRequest): Promise<AiReviewResponse> {
  if (!payload.messages.length) {
    throw new Error('AI review requires at least one message.');
  }

  return postJson<AiReviewResponse>('/api/ai/review', payload);
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

async function postJson<TResponse>(url: string, body: unknown): Promise<TResponse> {
  const response = await fetch(url, {
    method: 'POST',
    credentials: 'same-origin',
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
