import type { AiReviewPreset, ApiUserPreferences } from '../api/userPreferences';
import { useSaveUserPreferences, useUserPreferences } from './useUserPreferences';

export type AiReviewSettings = {
  preset: AiReviewPreset;
  automaticDisabled: boolean;
  ready: boolean;
  isSaving: boolean;
  error: string | null;
  onChangePreset: (preset: AiReviewPreset) => void;
  onChangeAutomaticDisabled: (disabled: boolean) => void;
  onRetryLoad: () => void;
};

function resolvePreset(preferences?: ApiUserPreferences): AiReviewPreset {
  if (preferences?.aiReviewPreset) return preferences.aiReviewPreset;
  if (preferences?.aiReviewProfile === 'version_a') return 'fast';
  if (preferences?.aiReviewReasoningEffort === 'medium') return 'thorough';
  if (preferences?.aiReviewReasoningEffort === 'high') return 'deep';
  return 'balanced';
}

export function useAiReviewPreferences(): AiReviewSettings {
  const preferences = useUserPreferences();
  const save = useSaveUserPreferences();
  return {
    preset: resolvePreset(preferences.data),
    automaticDisabled: preferences.data?.aiReviewAutomaticDisabled ?? false,
    ready: Boolean(preferences.data),
    isSaving: save.isPending,
    error:
      save.error?.message ?? (preferences.isError ? 'Could not load AI review settings.' : null),
    onChangePreset: (preset) => save.mutate({ aiReviewPreset: preset }),
    onChangeAutomaticDisabled: (disabled) => save.mutate({ aiReviewAutomaticDisabled: disabled }),
    onRetryLoad: () => {
      void preferences.refetch();
    },
  };
}
