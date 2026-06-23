import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  buildCmsPublishLocaleTagsKey,
  buildCmsPublishStateKey,
  loadCmsPublishIntent,
  loadLatestCmsPublishIntentForProject,
  saveCmsPublishIntent,
} from './content-cms-publish-intent';

describe('content CMS publish intents', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it('preserves duplicate explicit locale tokens in retry identity', () => {
    expect(buildCmsPublishLocaleTagsKey(['fr-FR', 'fr-FR'])).toBe('fr-FR,fr-FR');
    expect(buildCmsPublishLocaleTagsKey(['fr-FR'])).toBe('fr-FR');
  });

  it('canonicalizes explicit locale token order in retry identity', () => {
    expect(buildCmsPublishLocaleTagsKey(['fr-FR', 'de-DE'])).toBe('de-DE,fr-FR');
    expect(buildCmsPublishLocaleTagsKey(['de-DE', 'fr-FR'])).toBe('de-DE,fr-FR');
  });

  it('uses the server authoring and validated package shas for retry identity', () => {
    expect(
      buildCmsPublishStateKey(
        {
          project: {} as never,
          authoringSha256: 'a'.repeat(64),
          contentTypes: [],
          entries: [],
          publishSnapshots: [],
          hasMorePublishSnapshots: false,
          nextBeforePublishSnapshotVersion: null,
        },
        'b'.repeat(64),
      ),
    ).toBe(`${'a'.repeat(64)}:${'b'.repeat(64)}`);
  });

  it('discards malformed stored publish request keys before reuse', () => {
    saveCmsPublishIntent({
      projectId: 12,
      localeTagsKey: 'fr-FR',
      publishStateKey: 'revision',
      publishRequestKey: 'publish-request',
    });
    const storageKey = window.sessionStorage.key(0);
    if (storageKey == null) {
      throw new Error('Expected stored publish intent');
    }
    window.sessionStorage.setItem(
      storageKey,
      JSON.stringify({
        version: 1,
        projectId: 12,
        localeTagsKey: 'fr-FR',
        publishStateKey: 'revision',
        publishRequestKey: 'Publish Request',
      }),
    );

    expect(loadCmsPublishIntent(12, 'fr-FR', 'revision')).toBeNull();
    expect(window.sessionStorage.length).toBe(0);
  });

  it('restores the latest same-authoring publish intent for reload recovery', () => {
    const nowSpy = vi.spyOn(Date, 'now');
    nowSpy.mockReturnValueOnce(100).mockReturnValueOnce(200).mockReturnValueOnce(300);
    const authoringSha256 = 'a'.repeat(64);
    saveCmsPublishIntent({
      projectId: 12,
      localeTagsKey: 'fr-FR',
      publishStateKey: `${authoringSha256}:${'b'.repeat(64)}`,
      publishRequestKey: 'publish-request',
    });
    saveCmsPublishIntent({
      projectId: 12,
      localeTagsKey: 'de-DE',
      publishStateKey: `${authoringSha256}:${'c'.repeat(64)}`,
      publishRequestKey: 'publish-request-2',
    });
    saveCmsPublishIntent({
      projectId: 12,
      localeTagsKey: 'ja-JP',
      publishStateKey: `${'d'.repeat(64)}:${'e'.repeat(64)}`,
      publishRequestKey: 'publish-request-3',
    });

    expect(loadLatestCmsPublishIntentForProject(12, authoringSha256)).toEqual({
      projectId: 12,
      localeTagsKey: 'de-DE',
      publishStateKey: `${authoringSha256}:${'c'.repeat(64)}`,
      publishRequestKey: 'publish-request-2',
    });
    expect(loadLatestCmsPublishIntentForProject(12, 'f'.repeat(64))).toBeNull();
    nowSpy.mockRestore();
  });
});
