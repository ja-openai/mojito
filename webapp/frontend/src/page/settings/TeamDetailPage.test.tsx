import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { TeamDetailPage } from './TeamDetailPage';

const mocks = vi.hoisted(() => ({
  role: 'ROLE_ADMIN',
  deleteTeam: vi.fn(),
  fetchAllUsersAdmin: vi.fn(),
  fetchSlackClientIds: vi.fn(),
  fetchTeam: vi.fn(),
  fetchTeamProjectManagers: vi.fn(),
  fetchTeamSlackChannelMembers: vi.fn(),
  fetchTeamSlackSettings: vi.fn(),
  fetchTeamSlackUserMappings: vi.fn(),
  fetchTeamTranslators: vi.fn(),
  replaceTeamProjectManagers: vi.fn(),
  replaceTeamSlackUserMappings: vi.fn(),
  replaceTeamTranslators: vi.fn(),
  sendTeamSlackChannelTest: vi.fn(),
  sendTeamSlackMentionTest: vi.fn(),
  setTeamEnabled: vi.fn(),
  updateTeam: vi.fn(),
  updateTeamSlackSettings: vi.fn(),
}));

vi.mock('../../api/teams', () => mocks);
vi.mock('../../api/users', () => ({ fetchAllUsersAdmin: mocks.fetchAllUsersAdmin }));
vi.mock('../../hooks/useUser', () => ({ useUser: () => ({ role: mocks.role }) }));

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { retry: false },
      queries: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/settings/system/teams/5']}>
        <Routes>
          <Route path="/settings/system/teams/:teamId" element={<TeamDetailPage />} />
          <Route path="/settings/system/teams" element={<div>Teams</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TeamDetailPage section navigation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.role = 'ROLE_ADMIN';
    mocks.fetchTeam.mockResolvedValue({ id: 5, name: 'Large team', enabled: true });
    mocks.fetchAllUsersAdmin.mockResolvedValue([
      {
        id: 2,
        username: 'translator@example.com',
        commonName: 'Team Translator',
        enabled: true,
        canTranslateAllLocales: true,
        authorities: [{ authority: 'ROLE_TRANSLATOR' }],
        userLocales: [],
        teamIds: [5],
      },
      {
        id: 3,
        username: 'pm@example.com',
        commonName: 'Team PM',
        enabled: true,
        canTranslateAllLocales: true,
        authorities: [{ authority: 'ROLE_PM' }],
        userLocales: [],
        teamIds: [5],
      },
    ]);
    mocks.fetchTeamProjectManagers.mockResolvedValue({ userIds: [3] });
    mocks.fetchTeamTranslators.mockResolvedValue({ userIds: [2] });
    mocks.fetchSlackClientIds.mockResolvedValue({ entries: ['test-client'] });
    mocks.fetchTeamSlackSettings.mockResolvedValue({
      enabled: true,
      slackClientId: 'test-client',
      slackChannelId: 'C123',
    });
    mocks.fetchTeamSlackUserMappings.mockResolvedValue({ entries: [] });
    mocks.fetchTeamSlackChannelMembers.mockResolvedValue({ entries: [] });
  });

  it('shows one labelled section at a time and preserves an unsaved name when switching', async () => {
    const user = userEvent.setup();
    renderPage();

    const generalTab = await screen.findByRole('tab', { name: 'General' });
    const generalPanel = screen.getByRole('tabpanel', { name: 'General' });
    const nameInput = await within(generalPanel).findByDisplayValue('Large team');
    expect(generalTab).toHaveAttribute('aria-selected', 'true');
    expect(generalPanel).toHaveAttribute('aria-labelledby', generalTab.id);
    expect(generalTab).toHaveAttribute('aria-controls', generalPanel.id);
    expect(screen.getAllByRole('tabpanel')).toHaveLength(1);

    await user.clear(nameInput);
    await user.type(nameInput, 'Renamed draft');
    await user.click(screen.getByRole('tab', { name: 'Slack notifications' }));

    expect(screen.getByRole('tabpanel', { name: 'Slack notifications' })).toBeVisible();
    expect(generalPanel).not.toBeVisible();
    expect(screen.getAllByRole('tabpanel')).toHaveLength(1);

    await user.click(generalTab);
    expect(
      within(screen.getByRole('tabpanel', { name: 'General' })).getByRole('textbox'),
    ).toHaveValue('Renamed draft');
    expect(mocks.updateTeam).not.toHaveBeenCalled();
  });

  it('preserves the translator editor mode and unsaved batch input across sections', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('tab', { name: 'Translators' }));
    const translatorsPanel = screen.getByRole('tabpanel', { name: 'Translators' });
    await within(translatorsPanel).findByText('translator@example.com');
    await user.click(within(translatorsPanel).getByRole('button', { name: 'Batch' }));
    const batchInput = within(translatorsPanel).getByRole('textbox', {
      name: 'Translator usernames',
    });
    await user.clear(batchInput);
    await user.type(batchInput, 'translator@example.com\nnew.translator@example.com');

    await user.click(screen.getByRole('tab', { name: 'PMs' }));
    await user.click(screen.getByRole('tab', { name: 'Translators' }));

    expect(
      within(screen.getByRole('tabpanel', { name: 'Translators' })).getByRole('textbox', {
        name: 'Translator usernames',
      }),
    ).toHaveValue('translator@example.com\nnew.translator@example.com');
    expect(mocks.replaceTeamTranslators).not.toHaveBeenCalled();
  });

  it('preserves the PM redirect without showing admin sections or querying Slack data', async () => {
    mocks.role = 'ROLE_PM';
    renderPage();

    expect(await screen.findByText('Teams')).toBeVisible();
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();

    expect(mocks.fetchTeamProjectManagers).not.toHaveBeenCalled();
    expect(mocks.fetchSlackClientIds).not.toHaveBeenCalled();
    expect(mocks.fetchTeamSlackSettings).not.toHaveBeenCalled();
    expect(mocks.fetchTeamSlackUserMappings).not.toHaveBeenCalled();
    expect(mocks.fetchTeamSlackChannelMembers).not.toHaveBeenCalled();
  });

  it.each([
    { section: 'Translators', picker: 'Select translators' },
    { section: 'PMs', picker: 'Select project managers' },
  ])(
    'closes the $section picker when keyboard navigation switches sections',
    async ({ section, picker }) => {
      const user = userEvent.setup();
      renderPage();

      const rosterTab = await screen.findByRole('tab', { name: section });
      await user.click(rosterTab);
      await user.click(screen.getByRole('button', { name: picker }));
      expect(await screen.findByRole('menu')).toBeVisible();

      for (let step = 0; step < 12 && document.activeElement !== rosterTab; step += 1) {
        await user.tab({ shift: true });
      }
      expect(rosterTab).toHaveFocus();
      await user.keyboard('{End}');

      expect(screen.getByRole('tab', { name: 'Slack mappings' })).toHaveFocus();
      expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    },
  );

  it('supports arrow, Home, and End navigation with a single tab stop', async () => {
    const user = userEvent.setup();
    renderPage();

    const generalTab = await screen.findByRole('tab', { name: 'General' });
    await user.click(generalTab);
    await user.keyboard('{ArrowRight}');
    const translatorsTab = screen.getByRole('tab', { name: 'Translators' });
    expect(translatorsTab).toHaveFocus();
    expect(translatorsTab).toHaveAttribute('aria-selected', 'true');
    expect(translatorsTab).toHaveAttribute('tabindex', '0');
    expect(generalTab).toHaveAttribute('tabindex', '-1');

    await user.keyboard('{End}');
    const mappingsTab = screen.getByRole('tab', { name: 'Slack mappings' });
    expect(mappingsTab).toHaveFocus();
    expect(mappingsTab).toHaveAttribute('aria-selected', 'true');

    await user.keyboard('{ArrowRight}');
    expect(generalTab).toHaveFocus();
    await user.keyboard('{ArrowLeft}');
    expect(mappingsTab).toHaveFocus();
    await user.keyboard('{Home}');
    expect(generalTab).toHaveFocus();
    expect(generalTab).toHaveAttribute('aria-selected', 'true');
    expect(screen.getAllByRole('tab').filter((tab) => tab.tabIndex === 0)).toEqual([generalTab]);
  });
});
