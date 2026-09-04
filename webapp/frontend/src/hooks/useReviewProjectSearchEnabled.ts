import { useUserPreferences } from './useUserPreferences';

export function useReviewProjectSearchEnabled(): boolean {
  return useUserPreferences().data?.reviewProjectSearchEnabled ?? false;
}
