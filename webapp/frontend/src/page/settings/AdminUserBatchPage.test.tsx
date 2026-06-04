import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AdminUserBatchPage } from './AdminUserBatchPage';

const mocks = vi.hoisted(() => ({
  createUser: vi.fn(),
  fetchTeams: vi.fn(),
  updateUserTeamAssignment: vi.fn(),
}));

vi.mock('../../api/teams', () => ({
  fetchTeams: mocks.fetchTeams,
  updateUserTeamAssignment: mocks.updateUserTeamAssignment,
}));

vi.mock('../../api/users', () => ({
  createUser: mocks.createUser,
}));

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({
    username: 'admin',
    role: 'ROLE_ADMIN',
    canTranslateAllLocales: true,
    userLocales: [],
  }),
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
      <MemoryRouter initialEntries={['/settings/system/users/batch']}>
        <Routes>
          <Route path="/settings/system/users/batch" element={<AdminUserBatchPage />} />
          <Route path="/repositories" element={<div>Repositories</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminUserBatchPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.createUser
      .mockResolvedValueOnce({
        id: 42,
        username: 'jane.doe',
        canTranslateAllLocales: false,
        authorities: [{ authority: 'ROLE_PM' }],
        userLocales: [],
      })
      .mockResolvedValueOnce({
        id: 43,
        username: 'translator',
        canTranslateAllLocales: false,
        authorities: [{ authority: 'ROLE_TRANSLATOR' }],
        userLocales: [],
      });
    mocks.fetchTeams.mockResolvedValue([
      { id: 2, name: 'Localization', enabled: true },
      { id: 4, name: 'Glossary', enabled: true },
    ]);
    mocks.updateUserTeamAssignment.mockResolvedValue(undefined);
  });

  it('assigns created PM and translator users to every selected team', async () => {
    const user = userEvent.setup();

    renderPage();

    const teamsButton = await screen.findByRole('button', { name: 'Select teams' });
    await waitFor(() => expect(teamsButton).toBeEnabled());

    await user.click(teamsButton);
    await user.click(await screen.findByLabelText('Localization (#2)'));
    await user.click(screen.getByLabelText('Glossary (#4)'));

    fireEvent.change(screen.getByPlaceholderText('Enter CSV list here'), {
      target: {
        value: [
          'jane.doe, PM, en-US, StrongPass!, Jane, Doe, JD',
          'translator, Translator, fr-FR, TempPass123',
        ].join('\n'),
      },
    });
    await user.click(screen.getByRole('button', { name: 'Create users' }));

    await waitFor(() => expect(mocks.updateUserTeamAssignment).toHaveBeenCalledTimes(2));
    expect(mocks.updateUserTeamAssignment).toHaveBeenNthCalledWith(1, 42, {
      pmTeamIds: [2, 4],
      translatorTeamIds: [],
    });
    expect(mocks.updateUserTeamAssignment).toHaveBeenNthCalledWith(2, 43, {
      pmTeamIds: [],
      translatorTeamIds: [2, 4],
    });
  });
});
