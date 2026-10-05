import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { ApiUserProfile } from '../api/users';
import { logout } from '../auth/frontend-auth';
import { UserContext } from '../hooks/useUser';
import { UserMenu } from './UserMenu';

vi.mock('../auth/frontend-auth', () => ({ logout: vi.fn() }));

function renderMenu(role: ApiUserProfile['role'] = 'ROLE_TRANSLATOR') {
  render(
    <MemoryRouter>
      <UserContext.Provider
        value={{ username: 'alice', role, canTranslateAllLocales: false, userLocales: [] }}
      >
        <UserMenu />
      </UserContext.Provider>
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByRole('button', { name: 'Account menu for alice' }));
}

beforeEach(() => {
  vi.mocked(logout).mockReset().mockResolvedValue(undefined);
});

describe('UserMenu logout', () => {
  it.each(['ROLE_ADMIN', 'ROLE_PM', 'ROLE_TRANSLATOR', 'ROLE_USER'] as const)(
    'offers logout to %s',
    async (role) => {
      renderMenu(role);
      expect(screen.getByRole('menuitem', { name: 'My Settings' })).toBeInTheDocument();
      fireEvent.click(screen.getByRole('menuitem', { name: 'Log out' }));
      await waitFor(() => expect(logout).toHaveBeenCalledOnce());
      expect(screen.getByRole('menuitem', { name: 'Logging out…' })).toBeDisabled();
      if (role === 'ROLE_TRANSLATOR' || role === 'ROLE_USER') {
        expect(screen.queryByRole('menuitem', { name: 'System Settings' })).not.toBeInTheDocument();
      }
    },
  );

  it('shows an error and allows retrying a failed logout', async () => {
    vi.mocked(logout).mockRejectedValueOnce(new Error('Provider unavailable'));
    renderMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: 'Log out' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not log out. Please try again.',
    );
    expect(screen.getByRole('menuitem', { name: 'Log out' })).toBeEnabled();
    fireEvent.click(screen.getByRole('menuitem', { name: 'Log out' }));
    await waitFor(() => expect(logout).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
