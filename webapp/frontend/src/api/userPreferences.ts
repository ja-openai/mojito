export type AiReviewProfile = 'version_a' | 'version_b';
export type AiReviewReasoningEffort = 'low' | 'medium' | 'high';
export type AiReviewPreset = 'fastest' | 'fast' | 'balanced' | 'thorough' | 'deep' | 'ultra';

export type ApiUserPreferences = {
  initialized: boolean;
  worksetSize: number | null;
  preferredLocales: string[];
  shortcutHelp: 'header' | 'bottom' | 'hidden' | null;
  visibleTextEditorEnabled: boolean;
  reviewProjectSearchEnabled: boolean;
  defaultReviewTeamIds: number[];
  aiReviewProfile: AiReviewProfile;
  aiReviewReasoningEffort?: AiReviewReasoningEffort;
  aiReviewPreset?: AiReviewPreset;
  aiReviewAutomaticDisabled: boolean;
};

export type UserPreferencesPatch = Partial<Omit<ApiUserPreferences, 'initialized'>>;

export async function fetchUserPreferences(signal?: AbortSignal): Promise<ApiUserPreferences> {
  const response = await fetch('/api/users/me/preferences', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  });
  if (!response.ok) {
    throw new Error('Could not load your settings.');
  }
  return (await response.json()) as ApiUserPreferences;
}

export async function saveUserPreferences(
  patch: UserPreferencesPatch,
): Promise<ApiUserPreferences> {
  const response = await fetch('/api/users/me/preferences', {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!response.ok) {
    throw new Error('Could not save your settings. Please try again.');
  }
  return (await response.json()) as ApiUserPreferences;
}
