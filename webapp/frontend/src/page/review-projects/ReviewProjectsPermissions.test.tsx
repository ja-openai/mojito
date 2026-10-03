import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type * as ReviewProjectsApi from '../../api/review-projects';
import type { ApiUserProfile } from '../../api/users';
import { UserContext } from '../../hooks/useUser';
import { ReviewProjectsPage } from './ReviewProjectsPage';

const mocks = vi.hoisted(() => ({
  batchStatus: vi.fn(),
  searchProjects: vi.fn(),
  searchRequests: vi.fn(),
}));

vi.mock('../../api/review-projects', async (importActual) => ({
  ...(await importActual<typeof ReviewProjectsApi>()),
  adminBatchUpdateReviewProjectStatus: mocks.batchStatus,
  searchReviewProjects: mocks.searchProjects,
  searchReviewProjectRequests: mocks.searchRequests,
}));
vi.mock('../../api/teams', () => ({ fetchTeams: () => Promise.resolve([]) }));
vi.mock('../../hooks/useRepositories', () => ({
  useRepositories: () => ({ data: undefined }),
}));
vi.mock('../../hooks/useUserPreferences', () => ({
  useUserPreferences: () => ({ data: { defaultReviewTeamIds: [], preferredLocales: [] } }),
}));
vi.mock('../../components/virtual/useVirtualRows', () => ({
  useVirtualRows: ({ count }: { count: number }) => ({
    items: Array.from({ length: count }, (_, index) => ({
      index,
      key: index,
      start: index * 100,
      end: (index + 1) * 100,
      size: 100,
      lane: 0,
    })),
    totalSize: count * 100,
    measureElement: vi.fn(),
  }),
}));

const project: ReviewProjectsApi.ApiReviewProjectSummary = {
  id: 7,
  type: 'NORMAL',
  status: 'OPEN',
  locale: { id: 17, bcp47Tag: 'pt-PT' },
  textUnitCount: 1,
  wordCount: 3,
  decidedCount: 0,
  decidedWordCount: 0,
  reviewProjectRequest: { id: 70, name: 'Checkout review' },
};

function renderPage(role: ApiUserProfile['role']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <UserContext.Provider
        value={{ username: 'alice', role, canTranslateAllLocales: false, userLocales: [] }}
      >
        <MemoryRouter initialEntries={['/review-projects']}>
          <ReviewProjectsPage />
        </MemoryRouter>
      </UserContext.Provider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  window.sessionStorage.clear();
  vi.clearAllMocks();
  mocks.batchStatus.mockResolvedValue(undefined);
  mocks.searchProjects.mockResolvedValue({ reviewProjects: [project] });
  mocks.searchRequests.mockResolvedValue({
    requestGroups: [
      {
        requestId: 70,
        requestName: 'Checkout review',
        openProjectCount: 1,
        reviewProjects: [project],
      },
    ],
  });
});

describe('review project batch permissions', () => {
  it.each(['requests', 'list'] as const)(
    'lets a PM close and reopen projects in %s mode without admin actions',
    async (mode) => {
      renderPage('ROLE_PM');
      await screen.findByRole('region', { name: 'Project actions' });
      if (mode === 'list') {
        fireEvent.click(screen.getByRole('button', { name: 'List' }));
      }
      const selectionName =
        mode === 'requests' ? 'Select all projects in Checkout review' : 'Select review project 7';
      fireEvent.click(await screen.findByRole('checkbox', { name: selectionName }));
      const actions = within(screen.getByRole('region', { name: 'Project actions' }));
      fireEvent.click(actions.getByRole('button', { name: 'Close' }));
      await waitFor(() => expect(mocks.batchStatus).toHaveBeenCalledWith([7], 'CLOSED'));
      await waitFor(() => expect(actions.getByRole('button', { name: 'Reopen' })).toBeDisabled());

      fireEvent.click(screen.getByRole('checkbox', { name: selectionName }));
      fireEvent.click(actions.getByRole('button', { name: 'Reopen' }));
      await waitFor(() => expect(mocks.batchStatus).toHaveBeenCalledWith([7], 'OPEN'));
      expect(actions.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
      if (mode === 'requests') {
        expect(screen.getByRole('link', { name: 'Edit' })).toBeInTheDocument();
      }
      expect(screen.queryByRole('button', { name: 'Repair count' })).not.toBeInTheDocument();
      expect(screen.queryByRole('link', { name: 'New Project' })).not.toBeInTheDocument();
    },
  );

  it('keeps delete, edit, and repair actions available to admins', async () => {
    renderPage('ROLE_ADMIN');
    const actions = within(await screen.findByRole('region', { name: 'Project actions' }));
    expect(actions.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Edit' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Repair count' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'New Project' })).toBeInTheDocument();
  });

  it.each(['ROLE_TRANSLATOR', 'ROLE_USER'] as const)(
    'does not grant batch actions to %s',
    async (role) => {
      renderPage(role);
      await screen.findByRole('link', { name: 'Checkout review' });
      expect(screen.queryByRole('region', { name: 'Project actions' })).not.toBeInTheDocument();
      expect(
        screen.queryByRole('checkbox', { name: /Select review project/ }),
      ).not.toBeInTheDocument();
    },
  );
});
