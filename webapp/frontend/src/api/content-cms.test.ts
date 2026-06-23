import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  addCmsProjectTargetLocales,
  createCmsFirstCopyBlock,
  fetchCmsFieldTranslation,
  fetchCmsProjectCompleteness,
  fetchCmsProjectReleaseChanges,
  fetchCmsPublishSnapshots,
  makeCmsEntryCopyPiecesPrivate,
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

  it('creates the first copy block through the atomic authoring endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({}), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await createCmsFirstCopyBlock(12, {
      entryKey: 'welcome-email',
      entryName: 'Welcome email',
      entryDescription: 'Signup email',
      fieldKey: 'copy',
      sourceContent: 'Welcome',
      sourceComment: 'Headline',
    });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/content-cms/projects/12/first-copy-block',
      expect.objectContaining({
        body: JSON.stringify({
          entryKey: 'welcome-email',
          entryName: 'Welcome email',
          entryDescription: 'Signup email',
          fieldKey: 'copy',
          sourceContent: 'Welcome',
          sourceComment: 'Headline',
        }),
      }),
    );
  });

  it('adds target locales through the authoring endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({}), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await addCmsProjectTargetLocales(12, ['fr-FR', 'ja-JP']);

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/content-cms/projects/12/target-locales',
      expect.objectContaining({
        method: 'PATCH',
        body: JSON.stringify({ localeTags: ['fr-FR', 'ja-JP'] }),
      }),
    );
  });

  it('makes one block copy-piece set private through the authoring endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({}), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await makeCmsEntryCopyPiecesPrivate(12, { expectedVersion: 3 });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/content-cms/entries/12/private-copy-pieces',
      expect.objectContaining({
        body: JSON.stringify({ expectedVersion: 3 }),
      }),
    );
  });

  it('loads one CMS field translation through the scoped authoring endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ targetLocale: 'fr-FR' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await fetchCmsFieldTranslation({ id: 501 }, 'fr-FR');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/content-cms/field-mappings/501/translations/fr-FR',
      expect.objectContaining({
        headers: { Accept: 'application/json' },
      }),
    );
  });

  it('requests release readiness with the release locale scope', async () => {
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

  it('loads every release change with the release locale scope', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ changes: [], hasMore: false, actionNeededCount: 0 }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await fetchCmsProjectReleaseChanges(12, ' fr-FR, ja-JP ');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/content-cms/projects/12/release-changes?locales=fr-FR%2C+ja-JP',
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
