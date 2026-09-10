import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { ApiUserPreferences } from '../api/userPreferences';
import type { ApiUserProfile } from '../api/users';
import { useAiReviewPreferences } from './useAiReviewPreferences';
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
  aiReviewPreset: 'deep',
  aiReviewAutomaticDisabled: true,
};

function renderPreferences(saved = preferences, role: ApiUserProfile['role'] = 'ROLE_TRANSLATOR') {
  const client = new QueryClient();
  client.setQueryData(userPreferencesQueryKey('alice'), saved);
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <UserContext.Provider
        value={{
          username: 'alice',
          role,
          canTranslateAllLocales: true,
          userLocales: [],
        }}
      >
        {children}
      </UserContext.Provider>
    </QueryClientProvider>
  );
  return { ...renderHook(useAiReviewPreferences, { wrapper }), client };
}

afterEach(() => vi.unstubAllGlobals());

describe('AI review preferences', () => {
  it('preserves saved Ultra when an admin pauses automatic review', async () => {
    const saved = {
      ...preferences,
      aiReviewPreset: 'ultra' as const,
      aiReviewAutomaticDisabled: false,
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValue(Response.json({ ...saved, aiReviewAutomaticDisabled: true }));
    vi.stubGlobal('fetch', fetchMock);
    const { result, client } = renderPreferences(saved, 'ROLE_ADMIN');

    expect(result.current).toMatchObject({
      preset: 'ultra',
      automaticDisabled: false,
    });
    expect(fetchMock).not.toHaveBeenCalled();
    act(() => result.current.onChangeAutomaticDisabled(true));
    await waitFor(() => expect(result.current.automaticDisabled).toBe(true));
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith(
      '/api/users/me/preferences',
      expect.objectContaining({ body: JSON.stringify({ aiReviewAutomaticDisabled: true }) }),
    );
    expect(result.current.preset).toBe('ultra');
    expect(client.getQueryData(userPreferencesQueryKey('alice'))).toMatchObject({
      aiReviewPreset: 'ultra',
    });
  });

  it('defaults older account settings to alternatives with scores without rewriting them', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderPreferences();

    expect(result.current).toMatchObject({
      ready: true,
      preset: 'balanced',
      allowExtendedPresets: false,
      automaticDisabled: true,
      reviewStyle: 'corrections_and_alternatives',
      showScore: true,
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each(['ROLE_TRANSLATOR', 'ROLE_PM', 'ROLE_USER'] as const)(
    'uses Balanced for restricted saved presets and blocks restricted saves for %s',
    (role) => {
      const fetchMock = vi.fn();
      vi.stubGlobal('fetch', fetchMock);
      for (const preset of ['thorough', 'deep', 'ultra'] as const) {
        const { result, client, unmount } = renderPreferences(
          { ...preferences, aiReviewPreset: preset },
          role,
        );
        expect(result.current.preset).toBe('balanced');
        expect(result.current.allowExtendedPresets).toBe(false);
        act(() => result.current.onChangePreset(preset));
        expect(client.getQueryData(userPreferencesQueryKey('alice'))).toMatchObject({
          aiReviewPreset: preset,
          aiReviewAutomaticDisabled: true,
        });
        unmount();
      }
      expect(fetchMock).not.toHaveBeenCalled();
    },
  );

  it.each([
    ['ROLE_TRANSLATOR', 'medium', 'balanced'],
    ['ROLE_TRANSLATOR', 'high', 'balanced'],
    ['ROLE_ADMIN', 'medium', 'thorough'],
    ['ROLE_ADMIN', 'high', 'deep'],
  ] as const)(
    'resolves legacy %s/%s preferences to %s without rewriting them',
    (role, effort, preset) => {
      const fetchMock = vi.fn();
      vi.stubGlobal('fetch', fetchMock);
      const { result } = renderPreferences(
        { ...preferences, aiReviewPreset: undefined, aiReviewReasoningEffort: effort },
        role,
      );
      expect(result.current.preset).toBe(preset);
      expect(fetchMock).not.toHaveBeenCalled();
    },
  );

  it.each(['thorough', 'deep', 'ultra'] as const)(
    'keeps %s available to admins',
    async (preset) => {
      const savedPreferences = { ...preferences, aiReviewPreset: preset };
      const fetchMock = vi.fn().mockResolvedValue(Response.json(savedPreferences));
      vi.stubGlobal('fetch', fetchMock);
      const { result } = renderPreferences(savedPreferences, 'ROLE_ADMIN');
      expect(result.current.preset).toBe(preset);
      expect(result.current.allowExtendedPresets).toBe(true);
      expect(fetchMock).not.toHaveBeenCalled();
      act(() => result.current.onChangePreset(preset));
      await waitFor(() =>
        expect(fetchMock).toHaveBeenCalledExactlyOnceWith(
          '/api/users/me/preferences',
          expect.objectContaining({ body: JSON.stringify({ aiReviewPreset: preset }) }),
        ),
      );
    },
  );

  it('publishes only confirmed style and score saves while preserving speed and automatic review', async () => {
    let finishSave!: (response: Response) => void;
    const fetchMock = vi.fn().mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finishSave = resolve;
        }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const { result, client } = renderPreferences();

    act(() => result.current.onChangeReviewStyle('corrections_only'));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/users/me/preferences',
      expect.objectContaining({ body: JSON.stringify({ aiReviewStyle: 'corrections_only' }) }),
    );
    expect(result.current.reviewStyle).toBe('corrections_and_alternatives');
    expect(result.current.isSaving).toBe(true);
    const savedStyle: ApiUserPreferences = {
      ...preferences,
      aiReviewStyle: 'corrections_only',
      aiReviewShowScore: true,
    };
    await act(async () => {
      finishSave(Response.json(savedStyle));
      await Promise.resolve();
    });
    await waitFor(() => expect(result.current.reviewStyle).toBe('corrections_only'));

    fetchMock.mockResolvedValueOnce(Response.json({ ...savedStyle, aiReviewShowScore: false }));
    act(() => result.current.onChangeShowScore(false));
    await waitFor(() => expect(result.current.showScore).toBe(false));
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/users/me/preferences',
      expect.objectContaining({ body: JSON.stringify({ aiReviewShowScore: false }) }),
    );
    expect(client.getQueryData(userPreferencesQueryKey('alice'))).toMatchObject({
      aiReviewStyle: 'corrections_only',
      aiReviewShowScore: false,
      aiReviewPreset: 'deep',
      aiReviewAutomaticDisabled: true,
    });
  });

  it('keeps the confirmed style and score when a save fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 500 })));
    const { result } = renderPreferences({
      ...preferences,
      aiReviewStyle: 'corrections_only',
      aiReviewShowScore: false,
    });

    act(() => result.current.onChangeReviewStyle('corrections_and_alternatives'));
    await waitFor(() => expect(result.current.error).toMatch(/Could not save/));
    expect(result.current).toMatchObject({
      reviewStyle: 'corrections_only',
      showScore: false,
      automaticDisabled: true,
      preset: 'balanced',
    });
  });
});
