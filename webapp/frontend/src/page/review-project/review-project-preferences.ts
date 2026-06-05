export type ReviewProjectShortcutHelpPreference = 'header' | 'bottom' | 'hidden';
export type ReviewProjectShortcutHelpRole =
  | 'ROLE_PM'
  | 'ROLE_TRANSLATOR'
  | 'ROLE_ADMIN'
  | 'ROLE_CMS_DELIVERY'
  | 'ROLE_USER';

export const REVIEW_PROJECT_SHORTCUT_HELP_KEY = 'reviewProject.shortcutHelp.v1';
export const DEFAULT_REVIEW_PROJECT_SHORTCUT_HELP: ReviewProjectShortcutHelpPreference = 'header';

export function getDefaultReviewProjectShortcutHelpPreference(
  role: ReviewProjectShortcutHelpRole,
): ReviewProjectShortcutHelpPreference {
  return role === 'ROLE_TRANSLATOR' ? 'bottom' : DEFAULT_REVIEW_PROJECT_SHORTCUT_HELP;
}

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
  defaultPreference: ReviewProjectShortcutHelpPreference,
): ReviewProjectShortcutHelpPreference {
  if (value === 'bottom' || value === 'hidden' || value === 'header') {
    return value;
  }
  return defaultPreference;
}

export function loadReviewProjectShortcutHelpPreference(
  defaultPreference: ReviewProjectShortcutHelpPreference = DEFAULT_REVIEW_PROJECT_SHORTCUT_HELP,
): ReviewProjectShortcutHelpPreference {
  const storage = getStorage();
  if (!storage) {
    return defaultPreference;
  }
  return normalizeShortcutHelpPreference(
    storage.getItem(REVIEW_PROJECT_SHORTCUT_HELP_KEY),
    defaultPreference,
  );
}

export function saveReviewProjectShortcutHelpPreference(
  value: ReviewProjectShortcutHelpPreference,
  defaultPreference: ReviewProjectShortcutHelpPreference = DEFAULT_REVIEW_PROJECT_SHORTCUT_HELP,
): void {
  const storage = getStorage();
  if (!storage) {
    return;
  }
  if (value === defaultPreference) {
    storage.removeItem(REVIEW_PROJECT_SHORTCUT_HELP_KEY);
    return;
  }
  storage.setItem(REVIEW_PROJECT_SHORTCUT_HELP_KEY, value);
}
