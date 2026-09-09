import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type * as ReviewProjectsApi from '../../api/review-projects';
import type { ApiTeam } from '../../api/teams';
import type { ApiUserPreferences } from '../../api/userPreferences';
import type { ApiUserProfile } from '../../api/users';
import { UserContext } from '../../hooks/useUser';
import { userPreferencesQueryKey } from '../../hooks/useUserPreferences';
import {
  normalizeReviewProjectsSessionState,
  saveReviewProjectsSessionState,
} from './review-projects-session-state';
import { ReviewProjectsPage } from './ReviewProjectsPage';
import type { ReviewProjectsPageView } from './ReviewProjectsPageView';

const fetchTeamsMock = vi.hoisted(() => vi.fn());
const searchReviewProjectsMock = vi.hoisted(() => vi.fn());
const searchReviewProjectRequestsMock = vi.hoisted(() => vi.fn());

vi.mock('../../api/teams', () => ({ fetchTeams: fetchTeamsMock }));
vi.mock('../../api/review-projects', async (importActual) => ({
  ...(await importActual<typeof ReviewProjectsApi>()),
  searchReviewProjects: searchReviewProjectsMock,
  searchReviewProjectRequests: searchReviewProjectRequestsMock,
}));
vi.mock('../../hooks/useRepositories', () => ({
  useRepositories: () => ({ data: undefined }),
}));
vi.mock('./ReviewProjectsPageView', () => ({
  ReviewProjectsPageView: (props: ComponentProps<typeof ReviewProjectsPageView>) => (
    <output aria-label="Review filters">
      {JSON.stringify({
        status: props.status,
        assignedScope: props.filters.assignedScope,
        teamValues: props.filters.teamValues,
        teamOptions: props.filters.teamOptions,
        displayMode: props.displayMode,
        sessionKey: props.reviewProjectsSessionKey,
      })}
    </output>
  ),
}));

const defaultPreferences: ApiUserPreferences = {
  initialized: true,
  worksetSize: null,
  preferredLocales: [],
  shortcutHelp: null,
  visibleTextEditorEnabled: false,
  reviewProjectSearchEnabled: false,
  defaultReviewTeamIds: [],
  aiReviewProfile: 'version_b',
  aiReviewAutomaticDisabled: false,
};

const pm: ApiUserProfile = {
  username: 'alice',
  role: 'ROLE_PM',
  canTranslateAllLocales: true,
  userLocales: [],
};

function readFilters() {
  return JSON.parse(screen.getByLabelText('Review filters').textContent ?? '{}') as {
    status: string;
    assignedScope: string;
    teamValues: number[];
    teamOptions: { value: number; label: string }[];
    displayMode: string;
    sessionKey: string | null;
  };
}

function renderPage(
  queryClient: QueryClient,
  user: ApiUserProfile,
  defaultReviewTeamIds: number[],
  path = '/review-projects',
) {
  queryClient.setQueryData(userPreferencesQueryKey(user.username), {
    ...defaultPreferences,
    defaultReviewTeamIds,
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <UserContext.Provider value={user}>
        <MemoryRouter initialEntries={[path]}>
          <ReviewProjectsPage />
        </MemoryRouter>
      </UserContext.Provider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  window.sessionStorage.clear();
  vi.resetAllMocks();
  searchReviewProjectsMock.mockResolvedValue({ reviewProjects: [] });
  searchReviewProjectRequestsMock.mockResolvedValue({ requestGroups: [] });
});

describe('ReviewProjectsPage account preferences', () => {
  it('waits for the current PM teams before applying defaults when another account has cached teams', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    fetchTeamsMock.mockResolvedValueOnce([{ id: 1, name: 'Alice team', enabled: true }]);
    const alicePage = renderPage(queryClient, pm, [1]);
    await waitFor(() => expect(readFilters()).toMatchObject({ status: 'ready', teamValues: [1] }));
    expect(searchReviewProjectRequestsMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ assignedScope: 'TO_TEAM', teamIds: [1] }),
    );
    alicePage.unmount();

    let resolveBobTeams!: (teams: ApiTeam[]) => void;
    const bobTeams = new Promise<ApiTeam[]>((resolve) => {
      resolveBobTeams = resolve;
    });
    fetchTeamsMock.mockReturnValueOnce(bobTeams);
    searchReviewProjectRequestsMock.mockClear();
    renderPage(queryClient, { ...pm, username: 'bob' }, [2]);

    await waitFor(() => expect(fetchTeamsMock).toHaveBeenCalledTimes(2));
    expect(readFilters()).toMatchObject({ status: 'loading', teamOptions: [], sessionKey: null });
    expect(searchReviewProjectRequestsMock).not.toHaveBeenCalled();

    await act(async () => {
      resolveBobTeams([{ id: 2, name: 'Bob team', enabled: true }]);
      await bobTeams;
    });

    await waitFor(() =>
      expect(readFilters()).toMatchObject({
        status: 'ready',
        assignedScope: 'TO_TEAM',
        teamValues: [2],
        teamOptions: [{ value: 2, label: 'Bob team' }],
        displayMode: 'requests',
      }),
    );
    expect(searchReviewProjectRequestsMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ assignedScope: 'TO_TEAM', teamIds: [2] }),
    );
    expect(readFilters().sessionKey).toBeTruthy();
  });

  it('restores an explicit session team selection instead of replacing it with account defaults', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    fetchTeamsMock.mockResolvedValue([
      { id: 2, name: 'Default team', enabled: true },
      { id: 3, name: 'Session team', enabled: true },
    ]);
    const savedState = normalizeReviewProjectsSessionState({
      assignedScope: 'TO_TEAM',
      teamFilterIds: [3],
      displayMode: 'list',
    })!;
    const sessionKey = saveReviewProjectsSessionState(savedState);
    renderPage(queryClient, pm, [2], `/review-projects?rps=${sessionKey}`);

    await waitFor(() =>
      expect(readFilters()).toMatchObject({
        status: 'ready',
        assignedScope: 'TO_TEAM',
        teamValues: [3],
        displayMode: 'list',
        sessionKey,
      }),
    );
    expect(searchReviewProjectsMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ assignedScope: 'TO_TEAM', teamIds: [3] }),
    );
    expect(searchReviewProjectRequestsMock).not.toHaveBeenCalled();
  });

  it('keeps a translator in list mode when opening a request-mode session with saved team defaults', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const savedState = normalizeReviewProjectsSessionState({ displayMode: 'requests' })!;
    const sessionKey = saveReviewProjectsSessionState(savedState);
    renderPage(
      queryClient,
      { ...pm, role: 'ROLE_TRANSLATOR' },
      [2],
      `/review-projects?rps=${sessionKey}`,
    );

    await waitFor(() =>
      expect(readFilters()).toMatchObject({
        status: 'ready',
        assignedScope: 'TO_ME',
        teamValues: [],
        displayMode: 'list',
      }),
    );
    expect(fetchTeamsMock).not.toHaveBeenCalled();
    expect(searchReviewProjectsMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ assignedScope: 'TO_ME', teamIds: undefined }),
    );
    expect(searchReviewProjectRequestsMock).not.toHaveBeenCalled();
  });
});
