import type { AiReviewPreset, AiReviewStyle, ApiUserPreferences } from '../api/userPreferences';
import { useSaveUserPreferences, useUserPreferences } from './useUserPreferences';

export type AiReviewSettings = {
  preset: AiReviewPreset;
  automaticDisabled: boolean;
  reviewStyle: AiReviewStyle;
  showScore: boolean;
  ready: boolean;
  isSaving: boolean;
  error: string | null;
  onChangePreset: (preset: AiReviewPreset) => void;
  onChangeAutomaticDisabled: (disabled: boolean) => void;
  onChangeReviewStyle: (style: AiReviewStyle) => void;
  onChangeShowScore: (showScore: boolean) => void;
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
  const preset = resolvePreset(preferences.data);
  const automaticDisabled = preferences.data?.aiReviewAutomaticDisabled ?? false;
  return {
    preset,
    automaticDisabled,
    reviewStyle: preferences.data?.aiReviewStyle ?? 'corrections_and_alternatives',
    showScore: preferences.data?.aiReviewShowScore ?? true,
    ready: Boolean(preferences.data),
    isSaving: save.isPending,
    error:
      save.error?.message ?? (preferences.isError ? 'Could not load AI review settings.' : null),
    onChangePreset: (nextPreset) => save.mutate({ aiReviewPreset: nextPreset }),
    onChangeAutomaticDisabled: (disabled) => save.mutate({ aiReviewAutomaticDisabled: disabled }),
    onChangeReviewStyle: (style) => save.mutate({ aiReviewStyle: style }),
    onChangeShowScore: (showScore) => save.mutate({ aiReviewShowScore: showScore }),
    onRetryLoad: () => {
      void preferences.refetch();
    },
  };
}
