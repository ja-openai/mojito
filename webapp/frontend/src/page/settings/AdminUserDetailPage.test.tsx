import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AdminUserDetailPage } from './AdminUserDetailPage';

const mocks = vi.hoisted(() => ({
  deleteUser: vi.fn(),
  fetchTeams: vi.fn(),
  updateUser: vi.fn(),
  updateUserTeamAssignment: vi.fn(),
  users: [
    {
      id: 89,
      username: 'translator@example.com',
      givenName: 'Test',
      surname: 'Translator',
      commonName: null,
      enabled: true,
      canTranslateAllLocales: true,
      authorities: [{ authority: 'ROLE_USER' }],
      userLocales: [],
      teamIds: [2, 4, 5],
      teamNames: ["don't use 1", 'openai', 'glossary team'],
      pmTeamIds: [2, 5],
      pmTeamNames: ["don't use 1", 'glossary team'],
      pmTeamId: 2,
      pmTeamName: "don't use 1",
      translatorTeamIds: [4],
      translatorTeamNames: ['openai'],
      translatorTeamId: 4,
      translatorTeamName: 'openai',
    },
  ],
}));

vi.mock('../../api/teams', () => ({
  fetchTeams: mocks.fetchTeams,
  updateUserTeamAssignment: mocks.updateUserTeamAssignment,
}));

vi.mock('../../api/users', () => ({
  deleteUser: mocks.deleteUser,
  updateUser: mocks.updateUser,
}));

vi.mock('../../hooks/useLocales', () => ({
  useLocales: () => ({ data: [], isLoading: false, isError: false }),
}));

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({
    username: 'admin',
    role: 'ROLE_ADMIN',
    canTranslateAllLocales: true,
    userLocales: [],
  }),
}));

vi.mock('../../hooks/useUsers', () => ({
  USERS_QUERY_KEY: ['users'],
  useUsers: () => ({ data: mocks.users, isLoading: false, isError: false }),
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
      <MemoryRouter initialEntries={['/settings/system/users/89']}>
        <Routes>
          <Route path="/settings/system/users/:userId" element={<AdminUserDetailPage />} />
          <Route path="/repositories" element={<div>Repositories</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminUserDetailPage', () => {
  beforeEach(() => {
    mocks.deleteUser.mockResolvedValue(undefined);
    mocks.fetchTeams.mockResolvedValue([
      { id: 2, name: "don't use 1", enabled: true },
      { id: 4, name: 'openai', enabled: true },
      { id: 5, name: 'glossary team', enabled: true },
    ]);
    mocks.updateUser.mockResolvedValue(undefined);
    mocks.updateUserTeamAssignment.mockResolvedValue(undefined);
  });

  it('preserves multiple team memberships when editing a user assignment', async () => {
    const user = userEvent.setup();

    renderPage();

    const pmTeamsButton = screen.getByRole('button', { name: 'Select PM teams' });
    await waitFor(() =>
      expect(pmTeamsButton).toHaveTextContent("don't use 1 (#2), glossary team (#5)"),
    );
    expect(screen.getByText("Saved PM memberships: don't use 1, glossary team.")).toBeVisible();

    await user.click(pmTeamsButton);
    await user.click(await screen.findByLabelText('openai (#4)'));
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(mocks.updateUserTeamAssignment).toHaveBeenCalledWith(89, {
        pmTeamIds: [2, 5, 4],
        translatorTeamIds: [4],
      }),
    );
  });
});
