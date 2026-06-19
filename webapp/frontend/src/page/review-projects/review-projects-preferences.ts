const DEFAULT_TEAM_IDS_KEY_PREFIX = 'reviewProjects.defaultTeamIds.v1:';

function getStorage(): Storage | null {
  if (typeof window === 'undefined') {
    return null;
  }
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function getDefaultTeamIdsKey(username?: string | null): string {
  return `${DEFAULT_TEAM_IDS_KEY_PREFIX}${username?.trim() || 'anonymous'}`;
}

export function normalizeDefaultReviewProjectTeamIds(value: unknown): number[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const seen = new Set<number>();
  const normalized: number[] = [];
  value.forEach((entry) => {
    if (typeof entry !== 'number' || !Number.isInteger(entry) || entry <= 0) {
      return;
    }
    if (seen.has(entry)) {
      return;
    }
    seen.add(entry);
    normalized.push(entry);
  });
  return normalized;
}

export function loadDefaultReviewProjectTeamIds(username?: string | null): number[] {
  const storage = getStorage();
  if (!storage) {
    return [];
  }
  const raw = storage.getItem(getDefaultTeamIdsKey(username));
  if (!raw) {
    return [];
  }
  try {
    return normalizeDefaultReviewProjectTeamIds(JSON.parse(raw));
  } catch {
    return [];
  }
}

export function saveDefaultReviewProjectTeamIds(teamIds: number[], username?: string | null): void {
  const storage = getStorage();
  if (!storage) {
    return;
  }
  const normalized = normalizeDefaultReviewProjectTeamIds(teamIds);
  const key = getDefaultTeamIdsKey(username);
  if (normalized.length === 0) {
    storage.removeItem(key);
    return;
  }
  storage.setItem(key, JSON.stringify(normalized));
}
