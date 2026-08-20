import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AdminTeamPoolsPage } from './AdminTeamPoolsPage';

const mocks = vi.hoisted(() => ({
  emptyList: [] as unknown[],
  fetchTeam: vi.fn(),
  fetchTeamLocalePools: vi.fn(),
  fetchTeamPmPool: vi.fn(),
  fetchTeamTranslators: vi.fn(),
  replaceTeamLocalePools: vi.fn(),
  replaceTeamPmPool: vi.fn(),
  users: [
    {
      id: 2,
      username: 'translator@example.com',
      enabled: true,
      canTranslateAllLocales: true,
      authorities: [{ authority: 'ROLE_TRANSLATOR' }],
      userLocales: [],
      teamIds: [1],
    },
    {
      id: 3,
      username: 'pm@example.com',
      enabled: true,
      canTranslateAllLocales: true,
      authorities: [{ authority: 'ROLE_PM' }],
      userLocales: [],
      teamIds: [1],
    },
  ],
}));

vi.mock('../../api/teams', () => mocks);

vi.mock('../../hooks/useLocales', () => ({
  useLocales: () => ({ data: mocks.emptyList }),
}));

vi.mock('../../hooks/useRepositories', () => ({
  useRepositories: () => ({ data: mocks.emptyList }),
}));

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({ role: 'ROLE_ADMIN' }),
}));

vi.mock('../../hooks/useUsers', () => ({
  useUsers: () => ({ data: mocks.users }),
}));

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { retry: false },
      queries: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/settings/system/team-pools?teamId=1']}>
        <Routes>
          <Route path="/settings/system/team-pools" element={<AdminTeamPoolsPage />} />
          <Route path="/repositories" element={<div>Repositories</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminTeamPoolsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.fetchTeam.mockResolvedValue({ id: 1, name: 'Large team', enabled: true });
    mocks.fetchTeamLocalePools.mockResolvedValue({
      entries: Array.from({ length: 30 }, (_, index) => ({
        localeTag: `en-x-pool-${index + 1}`,
        translatorUserIds: [2],
      })),
    });
    mocks.fetchTeamPmPool.mockResolvedValue({ userIds: [] });
    mocks.fetchTeamTranslators.mockResolvedValue({ userIds: [2] });
    mocks.replaceTeamLocalePools.mockResolvedValue(undefined);
    mocks.replaceTeamPmPool.mockResolvedValue(undefined);
  });

  it('keeps PM controls above the translator editor and long assignment list', async () => {
    renderPage();

    const pmPool = await screen.findByRole('region', { name: 'PM pool' });
    const translatorEditor = screen.getByRole('region', { name: 'Team pools editor' });
    const assignmentList = screen.getByRole('region', { name: 'Team pools list' });

    expect(await screen.findAllByRole('button', { name: 'Edit' })).toHaveLength(30);
    expect(pmPool.compareDocumentPosition(translatorEditor)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(translatorEditor.compareDocumentPosition(assignmentList)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
  });
});
