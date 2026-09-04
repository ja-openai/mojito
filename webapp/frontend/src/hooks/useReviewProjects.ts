import { useQuery } from '@tanstack/react-query';

import type {
  ApiReviewProjectRequestGroupSummary,
  ApiReviewProjectSummary,
  ReviewProjectsSearchRequest,
} from '../api/review-projects';
import { searchReviewProjectRequests, searchReviewProjects } from '../api/review-projects';
import { useUser } from './useUser';

const REVIEW_PROJECTS_QUERY_KEY = 'review-projects';
const REVIEW_PROJECT_REQUESTS_QUERY_KEY = 'review-project-requests';

type UseReviewProjectsOptions = {
  enabled?: boolean;
};

export const useReviewProjects = (
  params: ReviewProjectsSearchRequest,
  options?: UseReviewProjectsOptions,
) => {
  const { username } = useUser();
  return useQuery<ApiReviewProjectSummary[]>({
    queryKey: [REVIEW_PROJECTS_QUERY_KEY, username, params],
    queryFn: async () => {
      const result = await searchReviewProjects(params);
      return result.reviewProjects ?? [];
    },
    placeholderData: (previousData, previousQuery) =>
      previousQuery?.queryKey[1] === username ? previousData : undefined,
    staleTime: 30_000,
    ...options,
  });
};

export const useReviewProjectRequests = (
  params: ReviewProjectsSearchRequest,
  options?: UseReviewProjectsOptions,
) => {
  const { username } = useUser();
  return useQuery<ApiReviewProjectRequestGroupSummary[]>({
    queryKey: [REVIEW_PROJECT_REQUESTS_QUERY_KEY, username, params],
    queryFn: async () => {
      const result = await searchReviewProjectRequests(params);
      return result.requestGroups ?? [];
    },
    placeholderData: (previousData, previousQuery) =>
      previousQuery?.queryKey[1] === username ? previousData : undefined,
    staleTime: 30_000,
    ...options,
  });
};

export { REVIEW_PROJECT_REQUESTS_QUERY_KEY, REVIEW_PROJECTS_QUERY_KEY };
