export const FIND_REPLACE_FIND_HISTORY_STORAGE_KEY = 'mojito.findReplace.findHistory';
export const FIND_REPLACE_REPLACE_HISTORY_STORAGE_KEY = 'mojito.findReplace.replaceHistory';

const FIND_REPLACE_HISTORY_LIMIT = 12;

export function readFindReplaceHistory(storageKey: string): string[] {
  try {
    const stored = window.localStorage.getItem(storageKey);
    if (!stored) {
      return [];
    }
    const parsed = JSON.parse(stored) as unknown;
    return Array.isArray(parsed)
      ? parsed.filter((value): value is string => typeof value === 'string' && value.length > 0)
      : [];
  } catch {
    return [];
  }
}

export function recordFindReplaceHistory(storageKey: string, value: string): string[] {
  const trimmed = value.trim();
  const current = readFindReplaceHistory(storageKey);
  const next = trimmed
    ? [trimmed, ...current.filter((item) => item !== trimmed)].slice(0, FIND_REPLACE_HISTORY_LIMIT)
    : current;
  try {
    window.localStorage.setItem(storageKey, JSON.stringify(next));
  } catch {
    // Ignore storage failures; the replace action itself should still run.
  }
  return next;
}
