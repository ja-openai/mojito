import type { TextUnitSearchRequest } from '../../api/text-units';

const STORAGE_PREFIX = 'workbench.searchState.v1:';
export const WORKBENCH_SESSION_QUERY_KEY = 'ws';

type WorkbenchSessionState = {
  version: 1;
  savedAt: number;
  searchRequest: TextUnitSearchRequest;
};

function getStorage(): Storage | null {
  if (typeof window === 'undefined') {
    return null;
  }
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

function makeSessionKey() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export function loadWorkbenchSessionSearch(key: string): TextUnitSearchRequest | null {
  const storage = getStorage();
  if (!storage || !key) {
    return null;
  }

  const raw = storage.getItem(`${STORAGE_PREFIX}${key}`);
  if (!raw) {
    return null;
  }

  try {
    const parsed = JSON.parse(raw) as WorkbenchSessionState;
    if (parsed?.version !== 1 || !parsed.searchRequest) {
      return null;
    }
    return parsed.searchRequest;
  } catch {
    return null;
  }
}

export function saveWorkbenchSessionSearch(
  searchRequest: TextUnitSearchRequest,
  existingKey?: string | null,
): string {
  const storage = getStorage();
  const key = existingKey && existingKey.trim().length > 0 ? existingKey : makeSessionKey();
  if (!storage) {
    return key;
  }

  const state: WorkbenchSessionState = {
    version: 1,
    savedAt: Date.now(),
    searchRequest,
  };
  storage.setItem(`${STORAGE_PREFIX}${key}`, JSON.stringify(state));
  return key;
}
