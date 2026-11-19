// @vitest-environment node

import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  fetchGenerationExcludedLocales,
  updateGenerationExcludedLocales,
} from './generation-settings';

afterEach(() => vi.unstubAllGlobals());

describe('global generation settings API', () => {
  it('loads the saved raw locale list from the global endpoint', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(Response.json(['fr', 'ja']));
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchGenerationExcludedLocales()).resolves.toEqual(['fr', 'ja']);

    expect(fetchMock).toHaveBeenCalledOnce();
    const [path, options] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/generation-excluded-locales');
    expect(options?.credentials).toBe('same-origin');
    expect(options?.method ?? 'GET').toBe('GET');
  });

  it.each([{ tags: ['fr', 'ja'] }, { tags: [] }])(
    'replaces the saved locale list with $tags',
    async ({ tags }) => {
      const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(Response.json(tags));
      vi.stubGlobal('fetch', fetchMock);

      await expect(updateGenerationExcludedLocales(tags)).resolves.toEqual(tags);

      expect(fetchMock).toHaveBeenCalledExactlyOnceWith('/api/generation-excluded-locales', {
        method: 'PUT',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(tags),
      });
    },
  );

  it('rejects failed reads and writes instead of treating them as empty settings', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockImplementation(() =>
        Promise.resolve(new Response('Service unavailable', { status: 503 })),
      );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchGenerationExcludedLocales()).rejects.toThrow();
    await expect(updateGenerationExcludedLocales([])).rejects.toThrow();
  });
});
