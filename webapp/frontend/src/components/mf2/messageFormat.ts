export type MessageFormat = 'MF2' | null;

const STRICT_MF2_DECLARATION_PATTERNS = [
  /^\s*\.input\s+\{\s*\$[^\s{}]+/u,
  /^\s*\.local\s+\$[^\s=]+\s*=/u,
  /^\s*\.match(?:\s+\$[^\s{}]+)+/u,
];

export function isStrictMf2Source(source: string | null | undefined): boolean {
  const normalized = (source ?? '').replace(/^\uFEFF/u, '');
  return STRICT_MF2_DECLARATION_PATTERNS.some((pattern) => pattern.test(normalized));
}

export function normalizeMessageFormat(messageFormat: string | null | undefined): MessageFormat {
  return messageFormat?.trim().toUpperCase() === 'MF2' ? 'MF2' : null;
}

export function isMf2Message({
  messageFormat,
  source,
}: {
  messageFormat?: string | null;
  source: string | null | undefined;
}): boolean {
  if (messageFormat !== undefined) {
    return normalizeMessageFormat(messageFormat) === 'MF2';
  }
  return isStrictMf2Source(source);
}
