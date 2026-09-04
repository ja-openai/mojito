import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { fetchTeams } from '../../api/teams';
import type { ApiUserProfile } from '../../api/users';
import {
  loadDefaultReviewProjectTeamIds,
  saveDefaultReviewProjectTeamIds,
} from '../review-projects/review-projects-preferences';
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

  it('stages default review project teams for admins until the global Save changes action', async () => {
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
    expect(loadDefaultReviewProjectTeamIds('admin')).toEqual([]);
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(loadDefaultReviewProjectTeamIds('admin')).toEqual([1]);
    expect(loadDefaultReviewProjectTeamIds('other-user')).toEqual([]);
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
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

    expect(loadDefaultReviewProjectTeamIds('pm')).toEqual([]);
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(loadDefaultReviewProjectTeamIds('pm')).toEqual([2, 1]);
  });

  it('discards team edits and stages restoring the default team filter until Save changes', async () => {
    const user = userEvent.setup();
    saveDefaultReviewProjectTeamIds([1], 'admin');
    renderSettingsPage();

    const teamSelector = screen.getByRole('button', { name: 'Select default review teams' });
    await waitFor(() => expect(teamSelector).toBeEnabled());
    await user.click(teamSelector);
    expect(screen.getByRole('checkbox', { name: /OpenAI/ })).toBeChecked();
    await user.click(screen.getByRole('checkbox', { name: /Glossary/ }));
    expect(loadDefaultReviewProjectTeamIds('admin')).toEqual([1]);

    await user.click(screen.getByRole('button', { name: 'Discard changes' }));
    await user.click(teamSelector);
    expect(screen.getByRole('checkbox', { name: /OpenAI/ })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: /Glossary/ })).not.toBeChecked();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Restore defaults' }));
    expect(teamSelector).toHaveTextContent('No default teams');
    expect(loadDefaultReviewProjectTeamIds('admin')).toEqual([1]);
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(loadDefaultReviewProjectTeamIds('admin')).toEqual([]);
  });
});
