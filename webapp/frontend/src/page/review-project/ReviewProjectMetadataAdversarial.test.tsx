import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ApiReviewProjectDetail } from '../../api/review-projects';
import type * as ReviewProjectsApi from '../../api/review-projects';
import type { ApiUserProfile } from '../../api/users';
import { REVIEW_PROJECT_DETAIL_QUERY_KEY } from '../../hooks/useReviewProjectDetail';
import { UserContext } from '../../hooks/useUser';
import { buildCarryoverProject, carryoverFixtures } from './review-project-carryover.fixtures';
import { useReviewProjectMutations } from './review-project-mutations';

const updateStatus = vi.hoisted(() => vi.fn());
vi.mock('../../api/review-projects', async (load) => ({
  ...(await load<typeof ReviewProjectsApi>()),
  updateReviewProjectStatus: updateStatus,
}));

const user: ApiUserProfile = {
  username: 'metadata-reviewer',
  role: 'ROLE_PM',
  canTranslateAllLocales: true,
  userLocales: [],
};
const clients: QueryClient[] = [];
beforeEach(() => {
  updateStatus.mockReset();
});
afterEach(() => clients.splice(0).forEach((client) => client.clear()));

function setup() {
  const first = buildCarryoverProject(carryoverFixtures[0]);
  const second = buildCarryoverProject(carryoverFixtures[1]);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  clients.push(client);
  for (const project of [first, second]) {
    client.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], project);
  }
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <UserContext.Provider value={user}>{children}</UserContext.Provider>
    </QueryClientProvider>
  );
  const hook = renderHook(({ projectId }) => useReviewProjectMutations(projectId), {
    wrapper,
    initialProps: { projectId: first.id },
  });
  return { ...hook, client, first, second };
}

describe('Review Project metadata response ownership', () => {
  it('does not insert an old project response into the project visited while it was pending', async () => {
    let resolve!: (value: ApiReviewProjectDetail) => void;
    updateStatus.mockReturnValue(new Promise<ApiReviewProjectDetail>((done) => (resolve = done)));
    const { result, rerender, client, first, second } = setup();
    act(() => result.current.onRequestProjectStatus('CLOSED'));
    await waitFor(() => expect(updateStatus).toHaveBeenCalledWith(first.id, 'CLOSED'));
    rerender({ projectId: second.id });
    await act(() => Promise.resolve(resolve({ ...first, status: 'CLOSED' })));
    expect(client.getQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, second.id])).toEqual(second);
  });

  it('does not show an earlier project status error in the current project', async () => {
    let reject!: (error: Error) => void;
    updateStatus.mockReturnValue(new Promise<ApiReviewProjectDetail>((_, fail) => (reject = fail)));
    const { result, rerender, first, second } = setup();
    act(() => result.current.onRequestProjectStatus('CLOSED'));
    await waitFor(() => expect(updateStatus).toHaveBeenCalledWith(first.id, 'CLOSED'));
    rerender({ projectId: second.id });
    await act(() => Promise.resolve(reject(new Error('First project cannot close'))));
    expect(result.current.errorMessage).toBeNull();
  });
});
