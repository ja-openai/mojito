import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { ApiUserPreferences } from '../api/userPreferences';
import { useReviewProjectSearchEnabled } from './useReviewProjectSearchEnabled';
import { UserContext } from './useUser';
import { userPreferencesQueryKey } from './useUserPreferences';

const preferences: ApiUserPreferences = {
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

function SearchPreference() {
  return <output>{useReviewProjectSearchEnabled() ? 'Enabled' : 'Disabled'}</output>;
}

function signedInAs(username: string, queryClient: QueryClient) {
  return (
    <QueryClientProvider client={queryClient}>
      <UserContext.Provider
        value={{ username, role: 'ROLE_TRANSLATOR', canTranslateAllLocales: true, userLocales: [] }}
      >
        <SearchPreference />
      </UserContext.Provider>
    </QueryClientProvider>
  );
}

describe('useReviewProjectSearchEnabled', () => {
  it('immediately uses the signed-in account preference and restores it when switching back', () => {
    const queryClient = new QueryClient();
    queryClient.setQueryData(userPreferencesQueryKey('alice'), {
      ...preferences,
      reviewProjectSearchEnabled: true,
    });
    queryClient.setQueryData(userPreferencesQueryKey('bob'), preferences);
    const { rerender } = render(signedInAs('alice', queryClient));
    expect(screen.getByRole('status')).toHaveTextContent('Enabled');

    rerender(signedInAs('bob', queryClient));
    expect(screen.getByRole('status')).toHaveTextContent('Disabled');

    rerender(signedInAs('alice', queryClient));
    expect(screen.getByRole('status')).toHaveTextContent('Enabled');
  });

  it('uses saved account updates without subscribing to another account after switching', async () => {
    const queryClient = new QueryClient();
    queryClient.setQueryData(userPreferencesQueryKey('alice'), preferences);
    queryClient.setQueryData(userPreferencesQueryKey('bob'), preferences);
    const { rerender } = render(signedInAs('alice', queryClient));
    rerender(signedInAs('bob', queryClient));

    act(() => {
      queryClient.setQueryData(userPreferencesQueryKey('alice'), {
        ...preferences,
        reviewProjectSearchEnabled: true,
      });
    });
    expect(screen.getByRole('status')).toHaveTextContent('Disabled');

    act(() => {
      queryClient.setQueryData(userPreferencesQueryKey('bob'), {
        ...preferences,
        reviewProjectSearchEnabled: true,
      });
    });
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Enabled'));
  });
});
