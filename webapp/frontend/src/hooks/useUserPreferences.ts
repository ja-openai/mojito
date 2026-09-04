import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { fetchUserPreferences, saveUserPreferences } from '../api/userPreferences';
import { useUser } from './useUser';

export const userPreferencesQueryKey = (username: string) => ['user-preferences', username];

export function useUserPreferences() {
  const { username } = useUser();
  return useQuery({
    queryKey: userPreferencesQueryKey(username),
    queryFn: ({ signal }) => fetchUserPreferences(signal),
    staleTime: 30_000,
    refetchOnWindowFocus: 'always',
    retry: false,
  });
}

export function useSaveUserPreferences() {
  const { username } = useUser();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: saveUserPreferences,
    scope: { id: `user-preferences:${username}` },
    onMutate: async () => {
      const queryKey = userPreferencesQueryKey(username);
      await queryClient.cancelQueries({ queryKey });
      return { queryKey };
    },
    onSuccess: async (preferences, _patch, context) => {
      await queryClient.cancelQueries({ queryKey: context.queryKey });
      queryClient.setQueryData(context.queryKey, preferences);
    },
  });
}
