import { render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ApiUserProfile } from './api/users';
import { App } from './App';

const mockUserState = vi.hoisted((): { currentUser: ApiUserProfile } => ({
  currentUser: {
    username: 'admin',
    role: 'ROLE_ADMIN',
    canTranslateAllLocales: true,
    userLocales: [],
  },
}));

vi.mock('./hooks/useCurrentUser', () => ({
  useCurrentUser: () => ({
    data: mockUserState.currentUser,
    isLoading: false,
    isError: false,
  }),
}));

vi.mock('./page/settings/AdminStringAuthoringPage', () => ({
  AdminStringAuthoringPage: () => <div>String authoring page</div>,
}));

function setUserRole(role: ApiUserProfile['role']) {
  mockUserState.currentUser = {
    ...mockUserState.currentUser,
    username: role.toLowerCase(),
    role,
  };
}

describe('App', () => {
  beforeEach(() => {
    setUserRole('ROLE_ADMIN');
    window.history.pushState({}, '', '/');
  });

  afterEach(() => {
    window.history.pushState({}, '', '/');
  });

  it('renders the shell', () => {
    render(<App />);
  });

  it('shows a string authoring tab for admins and keeps settings inactive on that route', () => {
    window.history.pushState({}, '', '/string-authoring');

    render(<App />);

    const nav = screen.getByRole('navigation');
    const stringAuthoringLink = within(nav).getByRole('link', { name: 'String Authoring' });
    const settingsLink = within(nav).getByRole('link', { name: 'Settings' });
    expect(stringAuthoringLink).toHaveAttribute('href', '/string-authoring');
    expect(stringAuthoringLink).toHaveClass('is-active');
    expect(settingsLink).not.toHaveClass('is-active');
    expect(screen.getByText('String authoring page')).toBeInTheDocument();
  });

  it('hides the string authoring tab from project managers', () => {
    setUserRole('ROLE_PM');

    render(<App />);

    expect(screen.queryByRole('link', { name: 'String Authoring' })).not.toBeInTheDocument();
  });

  it('hides the string authoring tab from users without source authoring access', () => {
    setUserRole('ROLE_TRANSLATOR');

    render(<App />);

    expect(screen.queryByRole('link', { name: 'String Authoring' })).not.toBeInTheDocument();
  });
});
