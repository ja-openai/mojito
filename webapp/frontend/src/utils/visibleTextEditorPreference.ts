export const VISIBLE_TEXT_EDITOR_ENABLED_KEY = 'visibleTextEditor.enabled.v1';

const VISIBLE_TEXT_EDITOR_PREFERENCE_EVENT = 'mojito:visible-text-editor-preference';

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

export function getVisibleTextEditorEnabledKey(username: string): string {
  return `${VISIBLE_TEXT_EDITOR_ENABLED_KEY}.${encodeURIComponent(username.trim())}`;
}

export function loadVisibleTextEditorEnabled(username: string): boolean {
  const storage = getStorage();
  if (!storage) {
    return false;
  }
  return storage.getItem(getVisibleTextEditorEnabledKey(username)) === 'true';
}

export function saveVisibleTextEditorEnabled(enabled: boolean, username: string): void {
  const storage = getStorage();
  if (!storage) {
    return;
  }

  const storageKey = getVisibleTextEditorEnabledKey(username);
  if (enabled) {
    storage.setItem(storageKey, 'true');
  } else {
    storage.removeItem(storageKey);
  }
  storage.removeItem(VISIBLE_TEXT_EDITOR_ENABLED_KEY);

  window.dispatchEvent(new Event(VISIBLE_TEXT_EDITOR_PREFERENCE_EVENT));
}

export function subscribeVisibleTextEditorPreference(
  username: string,
  listener: () => void,
): () => void {
  if (typeof window === 'undefined') {
    return () => {};
  }
  const storageKey = getVisibleTextEditorEnabledKey(username);

  const handleStorage = (event: StorageEvent) => {
    if (event.key && event.key !== storageKey) {
      return;
    }
    listener();
  };

  window.addEventListener('storage', handleStorage);
  window.addEventListener(VISIBLE_TEXT_EDITOR_PREFERENCE_EVENT, listener);

  return () => {
    window.removeEventListener('storage', handleStorage);
    window.removeEventListener(VISIBLE_TEXT_EDITOR_PREFERENCE_EVENT, listener);
  };
}
