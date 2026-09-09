import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ApiUserProfile } from './api/users';
import { App } from './App';
import type * as UserPreferencesHooks from './hooks/useUserPreferences';
import {
  normalizeReviewProjectsSessionState,
  saveReviewProjectsSessionState,
} from './page/review-projects/review-projects-session-state';
import { saveWorkbenchSessionSearch } from './page/workbench/workbench-session-state';

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

vi.mock('./hooks/useUserPreferences', async (importActual) => ({
  ...(await importActual<typeof UserPreferencesHooks>()),
  useUserPreferences: () => ({
    data: {
      initialized: true,
      worksetSize: null,
      preferredLocales: [],
      shortcutHelp: null,
      visibleTextEditorEnabled: false,
      reviewProjectSearchEnabled: false,
      defaultReviewTeamIds: [],
      aiReviewProfile: 'version_b',
      aiReviewAutomaticDisabled: false,
    },
    isError: false,
    isFetching: false,
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
    window.sessionStorage.clear();
    window.history.pushState({}, '', '/');
  });

  afterEach(() => {
    window.sessionStorage.clear();
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

  it('hides global linguist reporting from project managers', () => {
    setUserRole('ROLE_PM');
    window.history.pushState({}, '', '/settings/system');

    render(<App />);

    expect(screen.getByRole('button', { name: /Teams/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Linguist time spent/ })).not.toBeInTheDocument();
  });

  it('hides the string authoring tab from users without source authoring access', () => {
    setUserRole('ROLE_TRANSLATOR');

    render(<App />);

    expect(screen.queryByRole('link', { name: 'String Authoring' })).not.toBeInTheDocument();
  });

  it('keeps the latest Workbench session in global navigation', async () => {
    saveWorkbenchSessionSearch(
      { repositoryIds: [12], localeTags: ['fr'], statusFilter: 'REVIEW_NEEDED' },
      'workbench-session',
    );
    window.history.pushState({}, '', '/workbench?ws=workbench-session');

    render(<App />);

    const nav = screen.getByRole('navigation');
    expect(within(nav).getByRole('link', { name: 'Workbench' })).toHaveAttribute(
      'href',
      '/workbench?ws=workbench-session',
    );

    fireEvent.click(within(nav).getByRole('link', { name: 'Repositories' }));
    await waitFor(() => expect(window.location.pathname).toBe('/repositories'));

    expect(within(nav).getByRole('link', { name: 'Workbench' })).toHaveAttribute(
      'href',
      '/workbench?ws=workbench-session',
    );
  });

  it('keeps the latest Review Projects session in global navigation', async () => {
    const reviewProjectsState = normalizeReviewProjectsSessionState({
      selectedLocaleTags: ['fr'],
      hasTouchedLocales: true,
      listProjectStatusFilter: 'OPEN',
    });
    if (!reviewProjectsState) {
      throw new Error('Expected a valid Review Projects session state.');
    }
    saveReviewProjectsSessionState(reviewProjectsState, 'review-projects-session');
    window.history.pushState({}, '', '/review-projects?rps=review-projects-session');

    render(<App />);

    const nav = screen.getByRole('navigation');
    expect(within(nav).getByRole('link', { name: 'Review Projects' })).toHaveAttribute(
      'href',
      '/review-projects?rps=review-projects-session',
    );

    fireEvent.click(within(nav).getByRole('link', { name: 'Repositories' }));
    await waitFor(() => expect(window.location.pathname).toBe('/repositories'));

    expect(within(nav).getByRole('link', { name: 'Review Projects' })).toHaveAttribute(
      'href',
      '/review-projects?rps=review-projects-session',
    );
  });

  it('clears a remembered Workbench session when the dashboard removes it', async () => {
    saveWorkbenchSessionSearch(
      { repositoryIds: [12], localeTags: ['fr'], statusFilter: 'REVIEW_NEEDED' },
      'workbench-session',
    );
    window.history.pushState({}, '', '/workbench?ws=workbench-session');

    render(<App />);

    act(() => {
      window.history.pushState({}, '', '/workbench');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });

    await waitFor(() =>
      expect(screen.getByRole('link', { name: 'Workbench' })).toHaveAttribute('href', '/workbench'),
    );
  });

  it('keeps both dashboard sessions when switching between them', async () => {
    saveWorkbenchSessionSearch({ repositoryIds: [12], localeTags: ['fr'] }, 'workbench-session');
    const reviewState = normalizeReviewProjectsSessionState({ selectedLocaleTags: ['de'] });
    saveReviewProjectsSessionState(reviewState!, 'review-session');
    window.history.pushState({}, '', '/workbench?ws=workbench-session');
    render(<App />);

    act(() => {
      window.history.pushState({}, '', '/review-projects?rps=review-session');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });

    const nav = screen.getByRole('navigation');
    fireEvent.click(within(nav).getByRole('link', { name: 'Workbench' }));
    await waitFor(() => expect(window.location.search).toBe('?ws=workbench-session'));
    fireEvent.click(within(nav).getByRole('link', { name: 'Review Projects' }));
    await waitFor(() => expect(window.location.search).toBe('?rps=review-session'));
  });

  it('remembers a Review Project subroute session and clears it when the list resets', async () => {
    const reviewState = normalizeReviewProjectsSessionState({ selectedLocaleTags: ['de'] });
    saveReviewProjectsSessionState(reviewState!, 'review-session');
    window.history.pushState({}, '', '/review-projects/7?rps=review-session');
    render(<App />);

    // Review detail uses the shell without a header; leaving it restores the header.
    act(() => {
      window.history.pushState({}, '', '/repositories');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    const nav = screen.getByRole('navigation');
    expect(within(nav).getByRole('link', { name: 'Review Projects' })).toHaveAttribute(
      'href',
      '/review-projects?rps=review-session',
    );

    act(() => {
      window.history.pushState({}, '', '/review-projects');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    const resetSearch = window.location.search;
    expect(new URLSearchParams(resetSearch).get('rps')).not.toBe('review-session');
    fireEvent.click(within(nav).getByRole('link', { name: 'Repositories' }));
    await waitFor(() => expect(window.location.pathname).toBe('/repositories'));
    expect(within(nav).getByRole('link', { name: 'Review Projects' })).toHaveAttribute(
      'href',
      `/review-projects${resetSearch}`,
    );
  });
});
