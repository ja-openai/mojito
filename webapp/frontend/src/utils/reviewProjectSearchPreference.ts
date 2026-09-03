export const REVIEW_PROJECT_SEARCH_ENABLED_KEY = 'reviewProjectSearch.enabled.v1';

const PREFERENCE_EVENT = 'mojito:review-project-search-preference';

export function getReviewProjectSearchEnabledKey(username: string): string {
  return `${REVIEW_PROJECT_SEARCH_ENABLED_KEY}.${encodeURIComponent(username.trim())}`;
}

export function loadReviewProjectSearchEnabled(username: string): boolean {
  if (typeof window === 'undefined') return false;
  try {
    return window.localStorage.getItem(getReviewProjectSearchEnabledKey(username)) === 'true';
  } catch {
    return false;
  }
}

export function saveReviewProjectSearchEnabled(enabled: boolean, username: string): void {
  if (typeof window === 'undefined') return;
  try {
    const storageKey = getReviewProjectSearchEnabledKey(username);
    if (enabled) {
      window.localStorage.setItem(storageKey, 'true');
    } else {
      window.localStorage.removeItem(storageKey);
    }
  } catch {
    return;
  }
  window.dispatchEvent(new Event(PREFERENCE_EVENT));
}

export function subscribeReviewProjectSearchPreference(
  username: string,
  listener: () => void,
): () => void {
  if (typeof window === 'undefined') return () => {};
  const storageKey = getReviewProjectSearchEnabledKey(username);
  const handleStorage = (event: StorageEvent) => {
    if (event.key && event.key !== storageKey) return;
    listener();
  };
  window.addEventListener('storage', handleStorage);
  window.addEventListener(PREFERENCE_EVENT, listener);
  return () => {
    window.removeEventListener('storage', handleStorage);
    window.removeEventListener(PREFERENCE_EVENT, listener);
  };
}
