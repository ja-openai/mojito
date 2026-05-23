export type ReviewProjectShortcutHelpPreference = 'header' | 'bottom' | 'hidden';

export const REVIEW_PROJECT_SHORTCUT_HELP_KEY = 'reviewProject.shortcutHelp.v1';
export const DEFAULT_REVIEW_PROJECT_SHORTCUT_HELP: ReviewProjectShortcutHelpPreference = 'header';

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

function normalizeShortcutHelpPreference(
  value: string | null,
): ReviewProjectShortcutHelpPreference {
  if (value === 'bottom' || value === 'hidden' || value === 'header') {
    return value;
  }
  return DEFAULT_REVIEW_PROJECT_SHORTCUT_HELP;
}

export function loadReviewProjectShortcutHelpPreference(): ReviewProjectShortcutHelpPreference {
  const storage = getStorage();
  if (!storage) {
    return DEFAULT_REVIEW_PROJECT_SHORTCUT_HELP;
  }
  return normalizeShortcutHelpPreference(storage.getItem(REVIEW_PROJECT_SHORTCUT_HELP_KEY));
}

export function saveReviewProjectShortcutHelpPreference(
  value: ReviewProjectShortcutHelpPreference,
): void {
  const storage = getStorage();
  if (!storage) {
    return;
  }
  if (value === DEFAULT_REVIEW_PROJECT_SHORTCUT_HELP) {
    storage.removeItem(REVIEW_PROJECT_SHORTCUT_HELP_KEY);
    return;
  }
  storage.setItem(REVIEW_PROJECT_SHORTCUT_HELP_KEY, value);
}
