import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { ApiUserPreferences } from '../api/userPreferences';
import { UserContext } from './useUser';
import {
  userPreferencesQueryKey,
  useSaveUserPreferences,
  useUserPreferences,
} from './useUserPreferences';

const defaults: ApiUserPreferences = {
  initialized: false,
  worksetSize: null,
  preferredLocales: [],
  shortcutHelp: null,
  visibleTextEditorEnabled: false,
  reviewProjectSearchEnabled: false,
  defaultReviewTeamIds: [],
};

function makeWrapper(queryClient: QueryClient, getUsername = () => 'alice') {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <UserContext.Provider
          value={{
            username: getUsername(),
            role: 'ROLE_TRANSLATOR',
            canTranslateAllLocales: true,
            userLocales: [],
          }}
        >
          {children}
        </UserContext.Provider>
      </QueryClientProvider>
    );
  };
}

afterEach(() => vi.unstubAllGlobals());

describe('account preferences', () => {
  it('loads saved preferences without importing browser values', async () => {
    window.localStorage.setItem('workbench.worksetSize.v1', '5000');
    const fetchMock = vi.fn().mockResolvedValue(Response.json(defaults));
    vi.stubGlobal('fetch', fetchMock);
    const client = new QueryClient();
    const { result } = renderHook(useUserPreferences, { wrapper: makeWrapper(client) });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(defaults);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/users/me/preferences');
    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty('method');
    expect(window.localStorage.getItem('workbench.worksetSize.v1')).toBe('5000');
    window.localStorage.removeItem('workbench.worksetSize.v1');
  });

  it('sends only changed fields and publishes the confirmed response to readers', async () => {
    const client = new QueryClient();
    const saved = { ...defaults, initialized: true, preferredLocales: ['fr'] };
    client.setQueryData(userPreferencesQueryKey('alice'), saved);
    const response = { ...saved, reviewProjectSearchEnabled: true, worksetSize: null };
    const fetchMock = vi.fn().mockResolvedValue(Response.json(response));
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(
      () => ({ read: useUserPreferences(), save: useSaveUserPreferences() }),
      { wrapper: makeWrapper(client) },
    );

    await act(async () => {
      expect(
        await result.current.save.mutateAsync({
          reviewProjectSearchEnabled: true,
          worksetSize: null,
        }),
      ).toEqual(response);
    });
    await waitFor(() => expect(result.current.read.data).toEqual(response));
    expect(fetchMock).toHaveBeenCalledWith('/api/users/me/preferences', {
      method: 'PATCH',
      credentials: 'same-origin',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ reviewProjectSearchEnabled: true, worksetSize: null }),
    });
  });

  it('retains confirmed values when saving fails', async () => {
    const client = new QueryClient();
    client.setQueryData(userPreferencesQueryKey('alice'), defaults);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 500 })));
    const { result } = renderHook(useSaveUserPreferences, { wrapper: makeWrapper(client) });

    await act(async () => {
      await expect(result.current.mutateAsync({ shortcutHelp: 'bottom' })).rejects.toThrow(
        'Could not save your settings.',
      );
    });
    expect(client.getQueryData(userPreferencesQueryKey('alice'))).toEqual(defaults);
  });

  it('keeps an in-flight save attached to its original account', async () => {
    const client = new QueryClient();
    client.setQueryData(userPreferencesQueryKey('alice'), defaults);
    client.setQueryData(userPreferencesQueryKey('bob'), defaults);
    let resolveSave: (response: Response) => void = () => {};
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(
        () =>
          new Promise<Response>((resolve) => {
            resolveSave = resolve;
          }),
      ),
    );
    let username = 'alice';
    const { result, rerender } = renderHook(
      () => ({ read: useUserPreferences(), save: useSaveUserPreferences() }),
      { wrapper: makeWrapper(client, () => username) },
    );
    act(() => result.current.save.mutate({ shortcutHelp: 'bottom' }));
    await waitFor(() => expect(result.current.save.isPending).toBe(true));
    username = 'bob';
    rerender();
    const saved = { ...defaults, initialized: true, shortcutHelp: 'bottom' as const };
    act(() => resolveSave(Response.json(saved)));
    await waitFor(() =>
      expect(client.getQueryData(userPreferencesQueryKey('alice'))).toEqual(saved),
    );
    expect(result.current.read.data).toEqual(defaults);
    expect(client.getQueryData(userPreferencesQueryKey('bob'))).toEqual(defaults);
  });
});
