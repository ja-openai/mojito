import { useUserPreferences } from './useUserPreferences';

export function useVisibleTextEditorEnabled(): boolean {
  return useUserPreferences().data?.visibleTextEditorEnabled ?? false;
}
