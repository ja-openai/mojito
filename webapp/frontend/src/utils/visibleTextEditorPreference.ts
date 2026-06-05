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

export function loadVisibleTextEditorEnabled(): boolean {
  const storage = getStorage();
  if (!storage) {
    return false;
  }
  return storage.getItem(VISIBLE_TEXT_EDITOR_ENABLED_KEY) === 'true';
}

export function saveVisibleTextEditorEnabled(enabled: boolean): void {
  const storage = getStorage();
  if (!storage) {
    return;
  }

  if (enabled) {
    storage.setItem(VISIBLE_TEXT_EDITOR_ENABLED_KEY, 'true');
  } else {
    storage.removeItem(VISIBLE_TEXT_EDITOR_ENABLED_KEY);
  }

  window.dispatchEvent(new Event(VISIBLE_TEXT_EDITOR_PREFERENCE_EVENT));
}

export function subscribeVisibleTextEditorPreference(listener: () => void): () => void {
  if (typeof window === 'undefined') {
    return () => {};
  }

  const handleStorage = (event: StorageEvent) => {
    if (event.key && event.key !== VISIBLE_TEXT_EDITOR_ENABLED_KEY) {
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
