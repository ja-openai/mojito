import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { fetchTeams } from '../../api/teams';
import type { ApiUserProfile } from '../../api/users';
import { loadDefaultReviewProjectTeamIds } from '../review-projects/review-projects-preferences';
import { SettingsPage } from './SettingsPage';

let mockUser: ApiUserProfile;

vi.mock('../../hooks/useRepositories', () => ({
  useRepositories: () => ({ data: [], isLoading: false, isError: false }),
}));

vi.mock('../../api/teams', () => ({
  fetchTeams: vi.fn(),
}));

vi.mock('../../hooks/useUser', () => ({
  useUser: () => mockUser,
}));

function renderSettingsPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <SettingsPage />
    </QueryClientProvider>,
  );
}

describe('SettingsPage default review teams', () => {
  beforeEach(() => {
    window.localStorage.clear();
    vi.mocked(fetchTeams).mockResolvedValue([
      { id: 2, name: 'Glossary', enabled: true },
      { id: 1, name: 'OpenAI', enabled: true },
    ]);
    mockUser = {
      username: 'admin',
      role: 'ROLE_ADMIN',
      canTranslateAllLocales: true,
      userLocales: [],
    };
  });

  it('saves default review project teams for admins from the team API', async () => {
    const user = userEvent.setup();
    renderSettingsPage();

    const reviewProjectsSection = screen
      .getByRole('heading', { name: 'Review projects' })
      .closest('section');
    expect(reviewProjectsSection).not.toBeNull();
    const section = within(reviewProjectsSection as HTMLElement);

    const teamSelector = section.getByRole('button', { name: 'Select default review teams' });
    await waitFor(() => expect(teamSelector).toBeEnabled());
    await user.click(teamSelector);
    expect(screen.queryByRole('button', { name: 'Select my teams' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: /OpenAI/ }));
    await user.click(section.getByRole('button', { name: 'Save' }));

    expect(loadDefaultReviewProjectTeamIds('admin')).toEqual([1]);
  });

  it('offers a my teams preset when the user has team assignments', async () => {
    mockUser = {
      username: 'pm',
      role: 'ROLE_PM',
      canTranslateAllLocales: true,
      userLocales: [],
      teamIds: [2, 1],
      teamNames: ['Glossary', 'OpenAI'],
    };
    const user = userEvent.setup();
    renderSettingsPage();

    const reviewProjectsSection = screen
      .getByRole('heading', { name: 'Review projects' })
      .closest('section');
    expect(reviewProjectsSection).not.toBeNull();
    const section = within(reviewProjectsSection as HTMLElement);

    const teamSelector = section.getByRole('button', { name: 'Select default review teams' });
    await waitFor(() => expect(teamSelector).toBeEnabled());
    await user.click(teamSelector);
    const myTeamsPreset = screen.getByRole('button', { name: 'Select my teams' });
    await user.click(myTeamsPreset);

    expect(myTeamsPreset).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Select all teams' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );

    await user.click(section.getByRole('button', { name: 'Save' }));

    expect(loadDefaultReviewProjectTeamIds('pm')).toEqual([2, 1]);
  });
});
