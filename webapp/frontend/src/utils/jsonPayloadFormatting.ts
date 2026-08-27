const STRUCTURED_TEXT_CONTENT_TYPES = new Set(['input_text', 'output_text']);

export function formatJsonPayload(payload: string) {
  try {
    const parsed = JSON.parse(payload) as unknown;
    return JSON.stringify(unpackStructuredTextContent(parsed), null, 2);
  } catch {
    return payload;
  }
}

export function getJsonPayloadInstructions(payload: string) {
  try {
    const parsed = JSON.parse(payload) as unknown;
    if (!isRecord(parsed) || typeof parsed.instructions !== 'string') {
      return null;
    }
    return parsed.instructions.trim() ? parsed.instructions : null;
  } catch {
    return null;
  }
}

function unpackStructuredTextContent(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(unpackStructuredTextContent);
  }

  if (!isRecord(value)) {
    return value;
  }

  const shouldUnpackText =
    typeof value.type === 'string' && STRUCTURED_TEXT_CONTENT_TYPES.has(value.type);

  return Object.fromEntries(
    Object.entries(value).map(([key, nestedValue]) => [
      key,
      key === 'text' && shouldUnpackText
        ? unpackJsonObjectOrArray(nestedValue)
        : unpackStructuredTextContent(nestedValue),
    ]),
  );
}

function unpackJsonObjectOrArray(value: unknown) {
  if (typeof value !== 'string') {
    return unpackStructuredTextContent(value);
  }

  try {
    const parsed = JSON.parse(value.trim()) as unknown;
    return isRecord(parsed) || Array.isArray(parsed) ? unpackStructuredTextContent(parsed) : value;
  } catch {
    return value;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
