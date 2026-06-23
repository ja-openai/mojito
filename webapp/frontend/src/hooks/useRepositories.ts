import { useQuery } from '@tanstack/react-query';

import type { ApiRepository } from '../api/repositories';
import { fetchRepositories } from '../api/repositories';

const REPOSITORIES_QUERY_KEY = ['repositories'];

export const useRepositories = (enabled = true) => {
  return useQuery<ApiRepository[]>({
    queryKey: REPOSITORIES_QUERY_KEY,
    queryFn: fetchRepositories,
    enabled,
    staleTime: 30_000,
  });
};

export { REPOSITORIES_QUERY_KEY };
