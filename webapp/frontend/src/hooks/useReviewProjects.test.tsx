import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';

import {
  searchReviewProjectRequests,
  type SearchReviewProjectRequestsResponse,
  searchReviewProjects,
  type SearchReviewProjectsResponse,
} from '../api/review-projects';
import {
  REVIEW_PROJECT_REQUESTS_QUERY_KEY,
  REVIEW_PROJECTS_QUERY_KEY,
  useReviewProjectRequests,
  useReviewProjects,
} from './useReviewProjects';
import { UserContext } from './useUser';

vi.mock('../api/review-projects', () => ({
  searchReviewProjectRequests: vi.fn(),
  searchReviewProjects: vi.fn(),
}));

describe('review project account isolation', () => {
  it('keeps prior results for filter changes, but clears them when the account changes', async () => {
    const client = new QueryClient();
    let username = 'alice';
    let params = { limit: 100 };
    const aliceProjects = [{ id: 1, status: 'OPEN' as const, type: 'NORMAL' as const }];
    const aliceRequests = [{ requestId: 1, requestName: 'Alice request' }];
    client.setQueryData([REVIEW_PROJECTS_QUERY_KEY, username, params], aliceProjects);
    client.setQueryData([REVIEW_PROJECT_REQUESTS_QUERY_KEY, username, params], aliceRequests);
    let finishProjects: (value: SearchReviewProjectsResponse) => void = () => {};
    let finishRequests: (value: SearchReviewProjectRequestsResponse) => void = () => {};
    vi.mocked(searchReviewProjects).mockImplementation(
      () =>
        new Promise((resolve) => {
          finishProjects = resolve;
        }),
    );
    vi.mocked(searchReviewProjectRequests).mockImplementation(
      () =>
        new Promise((resolve) => {
          finishRequests = resolve;
        }),
    );
    function Wrapper({ children }: { children: ReactNode }) {
      return (
        <QueryClientProvider client={client}>
          <UserContext.Provider
            value={{ username, role: 'ROLE_PM', canTranslateAllLocales: true, userLocales: [] }}
          >
            {children}
          </UserContext.Provider>
        </QueryClientProvider>
      );
    }
    const { result, rerender } = renderHook(
      () => ({ projects: useReviewProjects(params), requests: useReviewProjectRequests(params) }),
      { wrapper: Wrapper },
    );
    expect(result.current.projects.data).toEqual(aliceProjects);
    expect(result.current.requests.data).toEqual(aliceRequests);

    params = { limit: 200 };
    rerender();
    expect(result.current.projects.data).toEqual(aliceProjects);
    expect(result.current.requests.data).toEqual(aliceRequests);

    username = 'bob';
    rerender();
    expect(result.current.projects.data).toBeUndefined();
    expect(result.current.requests.data).toBeUndefined();
    act(() => {
      finishProjects({ reviewProjects: [{ id: 2, status: 'OPEN', type: 'NORMAL' }] });
      finishRequests({ requestGroups: [{ requestId: 2, requestName: 'Bob request' }] });
    });
    await waitFor(() => expect(result.current.projects.data?.[0].id).toBe(2));
    expect(result.current.requests.data?.[0].requestId).toBe(2);
    expect(client.getQueryData([REVIEW_PROJECTS_QUERY_KEY, 'alice', { limit: 100 }])).toEqual(
      aliceProjects,
    );
    expect(
      client.getQueryData([REVIEW_PROJECT_REQUESTS_QUERY_KEY, 'alice', { limit: 100 }]),
    ).toEqual(aliceRequests);
  });
});
