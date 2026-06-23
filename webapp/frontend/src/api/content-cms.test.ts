import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  fetchCmsProjectCompleteness,
  fetchCmsPublishSnapshots,
  publishCmsProject,
} from './content-cms';

describe('content CMS API', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('surfaces Spring validation messages instead of raw error JSON', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            status: 400,
            error: 'Bad Request',
            message: 'Cannot publish with incomplete locales: fr-FR',
          }),
          { status: 400, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    );

    await expect(
      publishCmsProject(12, ['fr-FR'], 'a'.repeat(64), 'b'.repeat(64), 'publish-request'),
    ).rejects.toThrow('Cannot publish with incomplete locales: fr-FR');
  });

  it('sends the publish request key as an idempotency header', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({}), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await publishCmsProject(12, ['fr-FR'], 'a'.repeat(64), 'b'.repeat(64), 'publish-request');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/content-cms/projects/12/publish-snapshots',
      expect.objectContaining({
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': 'publish-request',
        },
        body: JSON.stringify({
          localeTags: ['fr-FR'],
          expectedAuthoringSha256: 'a'.repeat(64),
          expectedPackageSha256: 'b'.repeat(64),
        }),
      }),
    );
  });

  it('requests package completeness with the publish locale scope', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({}), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await fetchCmsProjectCompleteness(12, ' fr-FR, ja-JP ');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/content-cms/projects/12/completeness?locales=fr-FR%2C+ja-JP',
      expect.objectContaining({
        headers: { Accept: 'application/json' },
      }),
    );
  });

  it('requests older publish snapshot metadata with a version cursor', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ snapshots: [], hasMore: false }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await fetchCmsPublishSnapshots(12, { beforeVersion: 4, limit: 10 });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/content-cms/projects/12/publish-snapshots?beforeVersion=4&limit=10',
      expect.objectContaining({
        headers: { Accept: 'application/json' },
      }),
    );
  });
});
