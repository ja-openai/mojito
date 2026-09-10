import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { ApiUserPreferences } from '../api/userPreferences';
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

function renderPreferences(saved = preferences) {
  const client = new QueryClient();
  client.setQueryData(userPreferencesQueryKey('alice'), saved);
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <UserContext.Provider
        value={{
          username: 'alice',
          role: 'ROLE_TRANSLATOR',
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
  it('defaults older account settings to alternatives with scores without rewriting them', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderPreferences();

    expect(result.current).toMatchObject({
      ready: true,
      preset: 'deep',
      automaticDisabled: true,
      reviewStyle: 'corrections_and_alternatives',
      showScore: true,
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

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
      preset: 'deep',
    });
  });
});
