import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AdminSettingsPage } from './AdminSettingsPage';

let currentRole = 'ROLE_ADMIN';
vi.mock('../../hooks/useUser', () => ({ useUser: () => ({ role: currentRole }) }));

function renderDirectory() {
  render(
    <MemoryRouter initialEntries={['/settings/system']}>
      <Routes>
        <Route path="/settings/system" element={<AdminSettingsPage />} />
        <Route
          path="/settings/system/generation"
          element={<h1>Generation settings destination</h1>}
        />
        <Route path="/settings/me" element={<h1>My settings destination</h1>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('generation settings directory entry', () => {
  beforeEach(() => {
    currentRole = 'ROLE_ADMIN';
  });

  it('lets administrators open localized file generation settings', async () => {
    renderDirectory();

    await userEvent.click(screen.getByRole('button', { name: /Localized file generation/ }));

    expect(
      await screen.findByRole('heading', { name: 'Generation settings destination' }),
    ).toBeInTheDocument();
  });

  it.each(['ROLE_PM', 'ROLE_TRANSLATOR', 'ROLE_USER'])('hides the entry from %s', (role) => {
    currentRole = role;
    renderDirectory();

    expect(
      screen.queryByRole('button', { name: /Localized file generation/ }),
    ).not.toBeInTheDocument();
  });
});
