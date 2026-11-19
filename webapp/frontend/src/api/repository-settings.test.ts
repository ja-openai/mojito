// @vitest-environment node

import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchRepositoryTargetLocales, updateRepositoryTargetLocales } from './repository-settings';

afterEach(() => vi.unstubAllGlobals());

describe('repository target locales API', () => {
  it('loads the source and saved targets for the selected repository', async () => {
    const settings = { sourceLocaleTag: 'en', targetLocaleTags: ['fr', 'ja'] };
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(Response.json(settings));
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchRepositoryTargetLocales(7)).resolves.toEqual(settings);
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith('/api/repositories/7/target-locales', {
      credentials: 'same-origin',
    });
  });

  it.each([{ tags: ['fr', 'ja'] }, { tags: [] }])(
    'saves a raw target list without patching repository metadata',
    async ({ tags }) => {
      const settings = { sourceLocaleTag: 'en', targetLocaleTags: tags };
      const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(Response.json(settings));
      vi.stubGlobal('fetch', fetchMock);

      await expect(updateRepositoryTargetLocales(7, tags)).resolves.toEqual(settings);
      expect(fetchMock).toHaveBeenCalledExactlyOnceWith('/api/repositories/7/target-locales', {
        method: 'PUT',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(tags),
      });
    },
  );

  it('preserves server errors for reads and writes', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn<typeof fetch>()
        .mockImplementation(() =>
          Promise.resolve(new Response('fr-CA still inherits from fr', { status: 400 })),
        ),
    );

    await expect(fetchRepositoryTargetLocales(7)).rejects.toThrow('fr-CA still inherits from fr');
    await expect(updateRepositoryTargetLocales(7, [])).rejects.toThrow(
      'fr-CA still inherits from fr',
    );
  });
});
