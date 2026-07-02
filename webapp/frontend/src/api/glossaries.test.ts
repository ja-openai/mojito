// @vitest-environment node

import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  type ApiInflectionBindingManifest,
  exportInflectionProfilePack,
  fetchCompiledInflectionProfilePack,
  importInflectionProfiles,
  renderInflectionBindingManifest,
  reportInflectionBindingManifest,
  reviewInflectionProfile,
  upsertInflectionProfile,
} from './glossaries';

const manifest: ApiInflectionBindingManifest = {
  schema: 'mojito-mf2-inflection/message-term-binding-manifest/v0',
  locale: 'fr-FR',
  messages: {
    'inventory.deleted': 'Supprime {$item :term article=definite}.',
  },
  argumentTerms: {
    'inventory.deleted': {
      item: ['item.iron_sword'],
    },
  },
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('reportInflectionBindingManifest', () => {
  it('surfaces Spring manifest validation messages from JSON error bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        text: () =>
          Promise.resolve(
            JSON.stringify({
              error: 'Bad Request',
              message: '400 BAD_REQUEST "Expected non-blank message id"',
              status: 400,
            }),
          ),
      }),
    );

    await expect(reportInflectionBindingManifest(42, 'fr-FR', manifest)).rejects.toThrow(
      'Expected non-blank message id',
    );
  });
});

describe('exportInflectionProfilePack', () => {
  it('returns the profile pack content, summary, and attachment filename', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        headers: {
          get: (name: string) =>
            name.toLowerCase() === 'content-disposition'
              ? 'attachment; filename="glossary-42-inflection-fr.json"'
              : null,
        },
        text: () =>
          Promise.resolve(
            JSON.stringify({
              schema: 'mojito-mf2-inflection/term-inflection-profile-pack/v0',
              locale: 'fr',
              profiles: [
                {
                  termId: 'item.iron_sword',
                  source: 'iron sword',
                  status: 'APPROVED',
                },
              ],
            }),
          ),
      }),
    );

    const result = await exportInflectionProfilePack(42, 'fr');

    expect(result.filename).toBe('glossary-42-inflection-fr.json');
    expect(result.profileCount).toBe(1);
    expect(result.content).toContain('"termId":"item.iron_sword"');
  });

  it('surfaces authoring export validation messages from Spring JSON error bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        text: () =>
          Promise.resolve(
            JSON.stringify({
              error: 'Bad Request',
              message: '400 BAD_REQUEST "Source-backed provenance requires license"',
              status: 400,
            }),
          ),
      }),
    );

    await expect(exportInflectionProfilePack(42, 'fr')).rejects.toThrow(
      'Source-backed provenance requires license',
    );
  });
});

describe('fetchCompiledInflectionProfilePack', () => {
  it('returns compiled preview counts, policy headers, and pack summary', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      headers: {
        get: (name: string) => {
          const headers = new Map([
            ['x-mojito-inflection-approved-profiles', '2'],
            ['x-mojito-inflection-skipped-profiles', '1'],
            ['x-mojito-inflection-runtime-export', 'closed-world-glossary-approved-profile-forms'],
            ['x-mojito-inflection-composition-mode', 'explicit-form-rows-v0'],
          ]);
          return headers.get(name.toLowerCase()) ?? null;
        },
      },
      text: () =>
        Promise.resolve(
          JSON.stringify({
            schema: 'mojito-mf2-inflection/compiled-term-pack/v0',
            locale: 'fr',
            formSets: [
              {
                term: 0,
                forms: [
                  { key: 1, value: 2, kind: 'literal' },
                  { key: 3, value: 4, kind: 'literal' },
                ],
              },
            ],
          }),
        ),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await fetchCompiledInflectionProfilePack(42, 'fr');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/glossaries/42/inflection-profiles/compiled?locale=fr',
      expect.objectContaining({
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      }),
    );
    expect(result).toMatchObject({
      approvedProfileCount: 2,
      skippedProfileCount: 1,
      runtimeExport: 'closed-world-glossary-approved-profile-forms',
      compositionMode: 'explicit-form-rows-v0',
      profileCount: 1,
      formCount: 2,
    });
  });

  it('surfaces compiled export validation messages from Spring JSON error bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        text: () =>
          Promise.resolve(
            JSON.stringify({
              error: 'Bad Request',
              message: '400 BAD_REQUEST "Cannot compile inflection profile pack for locale fr"',
              status: 400,
            }),
          ),
      }),
    );

    await expect(fetchCompiledInflectionProfilePack(42, 'fr')).rejects.toThrow(
      'Cannot compile inflection profile pack for locale fr',
    );
  });
});

describe('importInflectionProfiles', () => {
  it('posts authoring pack content and returns import counts', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () =>
        Promise.resolve({
          localeTag: 'fr',
          profileCount: 1,
          createdProfileCount: 1,
          updatedProfileCount: 0,
          profiles: [],
        }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await importInflectionProfiles(42, '{"schema":"pack"}');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/glossaries/42/inflection-profiles/import',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ content: '{"schema":"pack"}' }),
      }),
    );
    expect(result).toMatchObject({
      localeTag: 'fr',
      profileCount: 1,
      createdProfileCount: 1,
      updatedProfileCount: 0,
    });
  });

  it('surfaces authoring import validation messages from Spring JSON error bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        text: () =>
          Promise.resolve(
            JSON.stringify({
              error: 'Bad Request',
              message: '400 BAD_REQUEST "Source-backed provenance requires generator"',
              status: 400,
            }),
          ),
      }),
    );

    await expect(importInflectionProfiles(42, '{"schema":"pack"}')).rejects.toThrow(
      'Source-backed provenance requires generator',
    );
  });
});

describe('renderInflectionBindingManifest', () => {
  it('preserves unsupported current V0 locale runtime render diagnostics', async () => {
    const serverMessage =
      'MF2 term binding manifest is not renderable: 1 binding diagnostics: inventory.deleted.item=unsupported-locale-runtime-term-inflection (unsupported by current V0 locale runtime)';
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        text: () =>
          Promise.resolve(
            JSON.stringify({
              error: 'Bad Request',
              message: `400 BAD_REQUEST "${serverMessage}"`,
              status: 400,
            }),
          ),
      }),
    );

    await expect(renderInflectionBindingManifest(42, 'ja', manifest)).rejects.toThrow(
      serverMessage,
    );
  });

  it('surfaces Spring render validation reasons from JSON error bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        text: () =>
          Promise.resolve(
            JSON.stringify({
              error: 'Bad Request',
              reason: 'Runtime variable "count" must not be blank',
              status: 400,
            }),
          ),
      }),
    );

    await expect(renderInflectionBindingManifest(42, 'fr-FR', manifest)).rejects.toThrow(
      'Runtime variable "count" must not be blank',
    );
  });

  it('surfaces Spring ProblemDetail messages from JSON error bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        text: () =>
          Promise.resolve(
            JSON.stringify({
              error: 'Bad Request',
              detail: 'Cannot render binding manifest with unresolved term bindings',
              status: 400,
            }),
          ),
      }),
    );

    await expect(renderInflectionBindingManifest(42, 'fr-FR', manifest)).rejects.toThrow(
      'Cannot render binding manifest with unresolved term bindings',
    );
  });

  it('uses the fallback message for generic Spring error bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        text: () =>
          Promise.resolve(
            JSON.stringify({
              error: 'Bad Request',
              status: 400,
            }),
          ),
      }),
    );

    await expect(renderInflectionBindingManifest(42, 'fr-FR', manifest)).rejects.toThrow(
      'Failed to render inflection bindings',
    );
  });
});

describe('reviewInflectionProfile', () => {
  it('posts reviewed morphology, forms, diagnostics, and provenance replacements', async () => {
    const response = {
      glossaryTermMetadataId: 7,
      tmTextUnitId: 99,
      termId: 'he.reviewed.hand',
      source: 'יד',
      localeTag: 'he',
      schema: 'mojito-mf2-inflection/term-inflection-profile-pack/v0',
      status: 'APPROVED',
      morphologyJson: '{}',
      formsJson: '{}',
      diagnosticsJson: '[]',
      provenanceJson: '{}',
    };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(response),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await reviewInflectionProfile(42, 99, 'he', {
      status: 'APPROVED',
      morphologyJson: '{"partOfSpeech":"noun"}',
      formsJson: '{"construct.dual":"ידי"}',
      diagnosticsJson: '[]',
      provenanceJson: '{"reviewedBy":"translator"}',
    });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/glossaries/42/terms/99/inflection-profiles/he/review',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          status: 'APPROVED',
          morphologyJson: '{"partOfSpeech":"noun"}',
          formsJson: '{"construct.dual":"ידי"}',
          diagnosticsJson: '[]',
          provenanceJson: '{"reviewedBy":"translator"}',
        }),
      }),
    );
    expect(result.termId).toBe('he.reviewed.hand');
  });

  it('surfaces review validation messages from Spring JSON error bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        text: () =>
          Promise.resolve(
            JSON.stringify({
              error: 'Bad Request',
              message: '400 BAD_REQUEST "Source-backed provenance requires generator"',
              status: 400,
            }),
          ),
      }),
    );

    await expect(
      reviewInflectionProfile(42, 99, 'fr', {
        status: 'APPROVED',
        diagnosticsJson: '[]',
      }),
    ).rejects.toThrow('Source-backed provenance requires generator');
  });
});

describe('upsertInflectionProfile', () => {
  it('uses the save fallback for generic Spring error bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        text: () =>
          Promise.resolve(
            JSON.stringify({
              error: 'Bad Request',
              status: 400,
            }),
          ),
      }),
    );

    await expect(
      upsertInflectionProfile(42, 99, 'fr', {
        status: 'APPROVED',
        morphologyJson: '{}',
        formsJson: '{}',
        diagnosticsJson: '[]',
        provenanceJson: '{}',
      }),
    ).rejects.toThrow('Failed to save inflection profile');
  });
});
