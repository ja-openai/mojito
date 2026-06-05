import { beforeEach, describe, expect, it } from 'vitest';

import {
  buildCmsPublishLocaleTagsKey,
  buildCmsPublishStateKey,
  loadCmsPublishIntent,
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

  it('uses the server authoring and validated package shas for retry identity', () => {
    expect(
      buildCmsPublishStateKey(
        {
          project: {} as never,
          authoringSha256: 'a'.repeat(64),
          contentTypes: [],
          entries: [],
          publishSnapshots: [],
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
});
