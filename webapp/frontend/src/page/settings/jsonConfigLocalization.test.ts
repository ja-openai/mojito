// @vitest-environment node

import { describe, expect, it } from 'vitest';

import type { ApiTextUnit } from '../../api/text-units';
import {
  appendStatsigSourceConfigEntry,
  buildJsonConfigLocalizationExport,
  buildJsonConfigLocalizationLocaleFileExport,
  buildJsonConfigLocalizationReadiness,
  buildStatsigSourceConfigExport,
  DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE,
  detectStatsigSourceConfigProfile,
  extractStatsigSourceConfigStrings,
  getStatsigSourceConfigStringIds,
  type JsonConfigLocalizationDraftString,
  mergeExtractedDraftStrings,
  normalizeSourceStringId,
  parseJsonRecord,
  parseOutputLocaleMappingSpec,
} from './jsonConfigLocalization';

const HOT_TAKES_PROFILE = {
  format: 'EMBEDDED_TRANSLATIONS' as const,
  collectionKey: 'hotTakes',
  itemIdField: 'id',
  translationsField: 'translations',
  sourceLocaleTag: 'en-US',
  translatableFields: ['title', 'body'],
  sourceField: 'source',
  commentField: 'description',
};

describe('jsonConfigLocalization', () => {
  it('starts new JSON config mappings without a fixture collection', () => {
    expect(DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE).toEqual({
      format: 'EMBEDDED_TRANSLATIONS',
      collectionKey: '',
      itemIdField: 'id',
      translationsField: 'translations',
      sourceLocaleTag: 'en-US',
      translatableFields: [],
      sourceField: 'source',
      commentField: 'description',
    });
  });

  it('computes translated and reviewed counts by target locale', () => {
    const sourceStrings: JsonConfigLocalizationDraftString[] = [
      {
        clientId: 'text-unit-11',
        tmTextUnitId: 11,
        assetId: 5,
        stringId: 'welcome.title',
        source: 'Welcome',
        comment: '',
        used: true,
        doNotTranslate: false,
      },
    ];
    const targetRows = [
      buildTargetRow(11, 'fr-FR', 'Bienvenue', 'APPROVED'),
      buildTargetRow(11, 'es-ES', 'Bienvenido', 'REVIEW_NEEDED'),
    ];

    const readiness = buildJsonConfigLocalizationReadiness(sourceStrings, targetRows, [
      'fr-FR',
      'es-ES',
      'de-DE',
    ]);

    expect(readiness['text-unit-11']).toMatchObject({
      translatedCount: 2,
      reviewedCount: 1,
      totalTargetLocales: 3,
    });
    expect(readiness['text-unit-11'].locales.map((locale) => locale.status)).toEqual([
      'APPROVED',
      'REVIEW_NEEDED',
      'MISSING',
    ]);
  });

  it('exports only used strings and omits missing target strings', () => {
    const sourceStrings: JsonConfigLocalizationDraftString[] = [
      {
        clientId: 'text-unit-11',
        tmTextUnitId: 11,
        assetId: 5,
        stringId: 'welcome.title',
        source: 'Welcome',
        comment: '',
        used: true,
        doNotTranslate: false,
      },
      {
        clientId: 'text-unit-12',
        tmTextUnitId: 12,
        assetId: 5,
        stringId: 'unused.title',
        source: 'Unused',
        comment: '',
        used: false,
        doNotTranslate: false,
      },
    ];
    const readiness = buildJsonConfigLocalizationReadiness(
      sourceStrings,
      [buildTargetRow(11, 'fr-FR', 'Bienvenue', 'APPROVED')],
      ['fr-FR', 'es-ES'],
    );

    expect(
      buildJsonConfigLocalizationExport('en-US', ['fr-FR', 'es-ES'], sourceStrings, readiness),
    ).toEqual({
      'en-US': {
        'welcome.title': 'Welcome',
      },
      'fr-FR': {
        'welcome.title': 'Bienvenue',
      },
      'es-ES': {},
    });
  });

  it('exports locale maps with output locale mapping', () => {
    const sourceStrings: JsonConfigLocalizationDraftString[] = [
      {
        clientId: 'text-unit-11',
        tmTextUnitId: 11,
        assetId: 5,
        stringId: 'welcome.title',
        source: 'Welcome',
        comment: '',
        used: true,
        doNotTranslate: false,
      },
    ];
    const readiness = buildJsonConfigLocalizationReadiness(
      sourceStrings,
      [buildTargetRow(11, 'de', 'Willkommen', 'APPROVED')],
      ['de'],
    );

    expect(
      buildJsonConfigLocalizationExport('en-US', ['de'], sourceStrings, readiness, {
        de: 'de-DE',
      }),
    ).toEqual({
      'en-US': {
        'welcome.title': 'Welcome',
      },
      'de-DE': {
        'welcome.title': 'Willkommen',
      },
    });
  });

  it('exports one JSON file per locale for generic JSON setups', () => {
    const sourceStrings: JsonConfigLocalizationDraftString[] = [
      {
        clientId: 'text-unit-11',
        tmTextUnitId: 11,
        assetId: 5,
        stringId: 'welcome.title',
        source: 'Welcome',
        comment: '',
        used: true,
        doNotTranslate: false,
      },
      {
        clientId: 'text-unit-12',
        tmTextUnitId: 12,
        assetId: 5,
        stringId: 'unused.title',
        source: 'Unused',
        comment: '',
        used: false,
        doNotTranslate: false,
      },
    ];
    const readiness = buildJsonConfigLocalizationReadiness(
      sourceStrings,
      [buildTargetRow(11, 'fr', 'Bienvenue', 'APPROVED')],
      ['fr', 'de'],
    );

    expect(
      buildJsonConfigLocalizationLocaleFileExport('en-US', ['fr', 'de'], sourceStrings, readiness, {
        fr: 'fr-FR',
      }),
    ).toEqual([
      {
        localeTag: 'en-US',
        filename: 'en-US.json',
        messages: {
          'welcome.title': 'Welcome',
        },
      },
      {
        localeTag: 'fr-FR',
        filename: 'fr-FR.json',
        messages: {
          'welcome.title': 'Bienvenue',
        },
      },
      {
        localeTag: 'de',
        filename: 'de.json',
        messages: {},
      },
    ]);
  });

  it('marks previous same-id source inactive when extraction changes the source text', () => {
    const currentStrings: JsonConfigLocalizationDraftString[] = [
      {
        clientId: 'text-unit-11',
        tmTextUnitId: 11,
        assetId: 5,
        stringId: 'welcome.title',
        source: 'Old title',
        comment: 'items[0].title',
        used: true,
        doNotTranslate: false,
      },
    ];
    const extractedStrings: JsonConfigLocalizationDraftString[] = [
      {
        clientId: 'draft-1',
        tmTextUnitId: null,
        assetId: null,
        stringId: 'welcome.title',
        source: 'New title',
        comment: 'items[0].title',
        used: true,
        doNotTranslate: false,
      },
    ];

    expect(mergeExtractedDraftStrings(currentStrings, extractedStrings)).toEqual([
      {
        clientId: 'draft-1',
        tmTextUnitId: null,
        assetId: null,
        stringId: 'welcome.title',
        source: 'New title',
        comment: 'items[0].title',
        used: true,
        doNotTranslate: false,
      },
      {
        clientId: 'text-unit-11',
        tmTextUnitId: 11,
        assetId: 5,
        stringId: 'welcome.title',
        source: 'Old title',
        comment: 'items[0].title',
        used: false,
        doNotTranslate: false,
      },
    ]);
  });

  it('reuses exact same-id source during extraction', () => {
    const currentStrings: JsonConfigLocalizationDraftString[] = [
      {
        clientId: 'text-unit-11',
        tmTextUnitId: 11,
        assetId: 5,
        stringId: 'welcome.title',
        source: 'Welcome',
        comment: 'items[0].title',
        used: false,
        doNotTranslate: false,
      },
    ];
    const extractedStrings: JsonConfigLocalizationDraftString[] = [
      {
        clientId: 'draft-1',
        tmTextUnitId: null,
        assetId: null,
        stringId: 'welcome.title',
        source: 'Welcome',
        comment: 'items[0].title',
        used: true,
        doNotTranslate: true,
      },
    ];

    expect(mergeExtractedDraftStrings(currentStrings, extractedStrings)).toEqual([
      {
        clientId: 'text-unit-11',
        tmTextUnitId: 11,
        assetId: 5,
        stringId: 'welcome.title',
        source: 'Welcome',
        comment: 'items[0].title',
        used: true,
        doNotTranslate: true,
      },
    ]);
  });

  it('clears old generated path comments without duplicating same source strings', () => {
    const currentStrings: JsonConfigLocalizationDraftString[] = [
      {
        clientId: 'text-unit-11',
        tmTextUnitId: 11,
        assetId: 5,
        stringId: 'welcome.title',
        source: 'Welcome',
        comment: 'items[0].title',
        used: true,
        doNotTranslate: false,
      },
    ];
    const extractedStrings: JsonConfigLocalizationDraftString[] = [
      {
        clientId: 'draft-1',
        tmTextUnitId: null,
        assetId: null,
        stringId: 'welcome.title',
        source: 'Welcome',
        comment: '',
        used: true,
        doNotTranslate: false,
      },
    ];

    expect(mergeExtractedDraftStrings(currentStrings, extractedStrings)).toEqual([
      {
        clientId: 'text-unit-11',
        tmTextUnitId: 11,
        assetId: 5,
        stringId: 'welcome.title',
        source: 'Welcome',
        comment: '',
        used: true,
        doNotTranslate: false,
      },
    ]);
  });

  it('parses CLI-style output-to-Mojito locale mapping', () => {
    expect(
      parseOutputLocaleMappingSpec("-lm 'bg-BG:bg,de-DE:de,zh-TW:zh-Hant,missing-MM:missing'", [
        'bg',
        'de',
        'zh-Hant',
      ]),
    ).toEqual({
      mapping: {
        bg: 'bg-BG',
        de: 'de-DE',
        'zh-Hant': 'zh-TW',
      },
      warnings: [
        'Skipped "missing-MM:missing" because neither side matches a Mojito target locale.',
      ],
    });
  });

  it('normalizes freeform string ids conservatively', () => {
    expect(normalizeSourceStringId(' Welcome Title! 2 ')).toBe('Welcome.Title_2');
  });

  it('extracts flat source strings from a Statsig collection schema and source config', () => {
    const extraction = extractStatsigSourceConfigStrings(
      JSON.stringify(buildStatsigSchema()),
      JSON.stringify(buildStatsigSourceConfig()),
    );

    expect(extraction.profile).toEqual({
      format: 'EMBEDDED_TRANSLATIONS',
      collectionKey: 'hotTakes',
      itemIdField: 'id',
      translationsField: 'translations',
      sourceLocaleTag: 'en-US',
      translatableFields: ['title', 'body'],
      sourceField: 'source',
      commentField: 'description',
    });
    expect(
      extraction.strings.map((sourceString) => [
        sourceString.stringId,
        sourceString.source,
        sourceString.comment,
      ]),
    ).toEqual([
      ['usa_brazil_blink.title', 'USA can make Brazil blink', ''],
      ['usa_brazil_blink.body', 'A fast start makes the whole bracket nervous.', ''],
    ]);
  });

  it('extracts empty embedded source strings', () => {
    const extraction = extractStatsigSourceConfigStrings(
      JSON.stringify(buildStatsigSchema()),
      JSON.stringify({
        hotTakes: [
          {
            id: 'empty_title',
            translations: {
              'en-US': {
                title: '',
                body: 'Body copy',
              },
            },
          },
        ],
      }),
    );

    expect(
      extraction.strings.map((sourceString) => [sourceString.stringId, sourceString.source]),
    ).toEqual([
      ['empty_title.title', ''],
      ['empty_title.body', 'Body copy'],
    ]);
    expect(extraction.warnings).not.toEqual(
      expect.arrayContaining([expect.stringContaining('missing non-empty')]),
    );
  });

  it('returns valid string ids from a Statsig source config', () => {
    expect(
      Array.from(
        getStatsigSourceConfigStringIds(
          JSON.stringify(buildStatsigSourceConfig()),
          HOT_TAKES_PROFILE,
        ),
      ),
    ).toEqual(['usa_brazil_blink.title', 'usa_brazil_blink.body']);
  });

  it('detects mapping from a schema without an explicit item id field', () => {
    const result = detectStatsigSourceConfigProfile(buildStatsigSchemaWithoutId());

    expect(result.profile).toEqual({
      collectionKey: 'hotTakes',
      itemIdField: 'id',
      translationsField: 'translations',
      sourceLocaleTag: 'en-US',
      translatableFields: ['title', 'body'],
    });
    expect(result.warnings).toContain(
      'No stable item id field was found in the schema. Mojito will use "id" when present and fall back to array indexes for items without id. Add an id field to keep string ids stable when entries are reordered.',
    );
  });

  it('uses manual mapping when schema JSON is invalid', () => {
    const extraction = extractStatsigSourceConfigStrings(
      '{ invalid schema',
      JSON.stringify(buildStatsigSourceConfig()),
      0,
      HOT_TAKES_PROFILE,
    );

    expect(
      extraction.strings.map((sourceString) => [sourceString.stringId, sourceString.source]),
    ).toEqual([
      ['usa_brazil_blink.title', 'USA can make Brazil blink'],
      ['usa_brazil_blink.body', 'A fast start makes the whole bracket nervous.'],
    ]);
    expect(extraction.warnings[0]).toContain('Schema not used for detection');
  });

  it('does not run embedded schema detection for flat source array mappings', () => {
    const flatProfile = {
      format: 'FLAT_SOURCE_ARRAY' as const,
      collectionKey: 'messages',
      itemIdField: 'id',
      translationsField: 'translations',
      sourceLocaleTag: 'en-US',
      translatableFields: [],
      sourceField: 'source',
      commentField: 'description',
    };
    const schema = {
      type: 'object',
      properties: {
        messages: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              id: { type: 'string' },
              source: { type: 'string' },
              description: { type: 'string' },
            },
          },
        },
      },
    };
    const config = {
      messages: [{ id: 'item_1', source: 'Hello', description: 'Short greeting' }],
    };

    const extraction = extractStatsigSourceConfigStrings(
      JSON.stringify(schema),
      JSON.stringify(config),
      0,
      flatProfile,
    );
    const appendResult = appendStatsigSourceConfigEntry(
      JSON.stringify(schema),
      JSON.stringify(config),
      flatProfile,
    );

    expect(extraction.strings.map((sourceString) => sourceString.stringId)).toEqual(['item_1']);
    expect(extraction.warnings).not.toEqual(
      expect.arrayContaining([expect.stringContaining('translations object')]),
    );
    expect(appendResult.warnings).not.toEqual(
      expect.arrayContaining([expect.stringContaining('translations object')]),
    );
  });

  it('infers flat source array when a stale embedded mapping points to a source array', () => {
    const extraction = extractStatsigSourceConfigStrings(
      '',
      JSON.stringify({
        messages: [{ id: 'item_1', source: '', description: 'Short greeting' }],
      }),
      0,
      {
        ...DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE,
        collectionKey: 'messages',
        translatableFields: ['text'],
      },
    );

    expect(extraction.profile).toMatchObject({
      format: 'FLAT_SOURCE_ARRAY',
      collectionKey: 'messages',
      sourceField: 'source',
      commentField: 'description',
    });
    expect(
      extraction.strings.map((sourceString) => [
        sourceString.stringId,
        sourceString.source,
        sourceString.comment,
      ]),
    ).toEqual([['item_1', '', 'Short greeting']]);
  });

  it('extracts FormatJS message maps without a collection key', () => {
    const formatJsProfile = {
      format: 'FORMATJS_MAP' as const,
      collectionKey: '',
      itemIdField: 'id',
      translationsField: 'translations',
      sourceLocaleTag: 'en-US',
      translatableFields: [],
      sourceField: 'defaultMessage',
      commentField: 'description',
    };

    const extraction = extractStatsigSourceConfigStrings(
      '',
      JSON.stringify({
        'message.1': {
          defaultMessage: '',
          description: 'Shown after save',
        },
      }),
      0,
      formatJsProfile,
    );

    expect(extraction.profile).toEqual(formatJsProfile);
    expect(
      extraction.strings.map((sourceString) => [
        sourceString.stringId,
        sourceString.source,
        sourceString.comment,
      ]),
    ).toEqual([['message.1', '', 'Shown after save']]);
  });

  it('infers FormatJS message maps when a stale embedded mapping points to a message map', () => {
    const extraction = extractStatsigSourceConfigStrings(
      '',
      JSON.stringify({
        'message.1': {
          defaultMessage: 'Saved notification',
          description: 'Shown after save',
        },
      }),
      0,
      {
        ...DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE,
        collectionKey: 'messages',
        translatableFields: ['text'],
        sourceField: 'defaultMessage',
        commentField: 'description',
      },
    );

    expect(extraction.profile).toMatchObject({
      format: 'FORMATJS_MAP',
      collectionKey: '',
      sourceField: 'defaultMessage',
      commentField: 'description',
    });
    expect(
      extraction.strings.map((sourceString) => [
        sourceString.stringId,
        sourceString.source,
        sourceString.comment,
      ]),
    ).toEqual([['message.1', 'Saved notification', 'Shown after save']]);
  });

  it('supports nested FormatJS message maps', () => {
    const profile = {
      ...DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE,
      format: 'FORMATJS_MAP' as const,
      collectionKey: 'messages',
      sourceField: 'defaultMessage',
      commentField: 'description',
    };
    const extraction = extractStatsigSourceConfigStrings(
      '',
      JSON.stringify({
        messages: {
          'message.1': {
            defaultMessage: 'Saved notification',
            description: 'Shown after save',
          },
        },
      }),
      0,
      profile,
    );

    expect(
      getStatsigSourceConfigStringIds(JSON.stringify(extraction.sourceConfig), profile),
    ).toEqual(new Set(['message.1']));
    expect(
      buildStatsigSourceConfigExport(profile, extraction.sourceConfig, [], extraction.strings, {})
        .value,
    ).toEqual({
      messages: {
        'message.1': {
          defaultMessage: 'Saved notification',
          description: 'Shown after save',
        },
      },
    });
  });

  it('builds multilingual FormatJS message maps under a messages object', () => {
    const profile = {
      ...DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE,
      format: 'FORMATJS_MULTILINGUAL_MAP' as const,
      collectionKey: 'messages',
      sourceField: 'defaultMessage',
      commentField: 'description',
    };
    const extraction = extractStatsigSourceConfigStrings(
      '',
      JSON.stringify({
        messages: {
          'message.1': {
            defaultMessage: 'Saved notification',
            description: 'Shown after save',
            translations: {
              'fr-FR': 'Ancienne notification',
            },
          },
        },
      }),
      0,
      profile,
    );
    const sourceStrings = extraction.strings.map((sourceString) => ({
      ...sourceString,
      clientId: 'text-unit-11',
      tmTextUnitId: 11,
    }));
    const readiness = buildJsonConfigLocalizationReadiness(
      sourceStrings,
      [buildTargetRow(11, 'fr', 'Notification enregistree', 'APPROVED')],
      ['fr', 'ja-JP'],
    );

    expect(
      extraction.strings.map((sourceString) => [
        sourceString.stringId,
        sourceString.source,
        sourceString.comment,
      ]),
    ).toEqual([['message.1', 'Saved notification', 'Shown after save']]);
    expect(
      buildStatsigSourceConfigExport(
        profile,
        extraction.sourceConfig,
        ['fr', 'ja-JP'],
        sourceStrings,
        readiness,
        { fr: 'fr-FR' },
      ).value,
    ).toEqual({
      messages: {
        'message.1': {
          defaultMessage: 'Saved notification',
          description: 'Shown after save',
          translations: {
            'fr-FR': 'Notification enregistree',
          },
        },
      },
    });
  });

  it('builds multilingual FormatJS message maps under wildcard message paths', () => {
    const profile = {
      ...DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE,
      format: 'FORMATJS_MULTILINGUAL_MAP' as const,
      collectionKey: 'surface.**.messages',
      sourceField: 'defaultMessage',
      commentField: 'description',
    };
    const extraction = extractStatsigSourceConfigStrings(
      '',
      JSON.stringify({
        surface: {
          checkout: {
            messages: {
              'checkout.pay': {
                defaultMessage: 'Pay now',
                description: 'Primary checkout button.',
                translations: {},
              },
            },
          },
          profile: {
            settings: {
              messages: {
                'profile.saved': {
                  defaultMessage: 'Profile saved',
                  description: 'Toast after profile changes are saved.',
                  translations: {},
                },
              },
            },
          },
        },
      }),
      0,
      profile,
    );
    const sourceStrings = extraction.strings.map((sourceString, index) => ({
      ...sourceString,
      clientId: `text-unit-${index + 1}`,
      tmTextUnitId: index + 1,
    }));
    const readiness = buildJsonConfigLocalizationReadiness(
      sourceStrings,
      [
        buildTargetRow(1, 'fr', 'Payer maintenant', 'APPROVED'),
        buildTargetRow(2, 'fr', 'Profil enregistre', 'APPROVED'),
      ],
      ['fr'],
    );

    expect(
      extraction.strings.map((sourceString) => [
        sourceString.stringId,
        sourceString.source,
        sourceString.comment,
      ]),
    ).toEqual([
      ['checkout.pay', 'Pay now', 'Primary checkout button.'],
      ['profile.saved', 'Profile saved', 'Toast after profile changes are saved.'],
    ]);
    expect(
      buildStatsigSourceConfigExport(
        profile,
        extraction.sourceConfig,
        ['fr'],
        sourceStrings,
        readiness,
        { fr: 'fr-FR' },
      ).value,
    ).toEqual({
      surface: {
        checkout: {
          messages: {
            'checkout.pay': {
              defaultMessage: 'Pay now',
              description: 'Primary checkout button.',
              translations: {
                'fr-FR': 'Payer maintenant',
              },
            },
          },
        },
        profile: {
          settings: {
            messages: {
              'profile.saved': {
                defaultMessage: 'Profile saved',
                description: 'Toast after profile changes are saved.',
                translations: {
                  'fr-FR': 'Profil enregistre',
                },
              },
            },
          },
        },
      },
    });
  });

  it('accepts trailing commas in pasted JSON', () => {
    expect(
      parseJsonRecord(
        `{
          "hotTakes": [
            {
              "id": "one",
            },
          ],
        }`,
        'source config',
      ),
    ).toEqual({
      hotTakes: [{ id: 'one' }],
    });
  });

  it('falls back to index-based ids when source config items have no id field', () => {
    const extraction = extractStatsigSourceConfigStrings(
      JSON.stringify(buildStatsigSchemaWithoutId()),
      `{
        "hotTakes": [
          {
            "translations": {
              "en-US": {
                "title": "USA can make Brazil blink",
                "body": "The upset is not Haiti beating Brazil.",
              },
            },
          },
        ],
      }`,
      0,
      HOT_TAKES_PROFILE,
    );

    expect(
      extraction.strings.map((sourceString) => [sourceString.stringId, sourceString.source]),
    ).toEqual([
      ['hotTakes.0.title', 'USA can make Brazil blink'],
      ['hotTakes.0.body', 'The upset is not Haiti beating Brazil.'],
    ]);
    expect(extraction.warnings).toContain(
      'No stable item id field was found in the schema. Mojito will use "id" when present and fall back to array indexes for items without id. Add an id field to keep string ids stable when entries are reordered.',
    );
    expect(extraction.warnings).not.toContain(
      'hotTakes[0] is missing "id"; using index-based string ids under "hotTakes.0".',
    );
  });

  it('warns when some source config items are missing an id used by other items', () => {
    const extraction = extractStatsigSourceConfigStrings(
      JSON.stringify(buildStatsigSchema()),
      `{
        "hotTakes": [
          {
            "id": "first",
            "translations": {
              "en-US": {
                "title": "First",
                "body": "First body",
              },
            },
          },
          {
            "translations": {
              "en-US": {
                "title": "Second",
                "body": "Second body",
              },
            },
          },
        ],
      }`,
      0,
      HOT_TAKES_PROFILE,
    );

    expect(extraction.strings.map((sourceString) => sourceString.stringId)).toEqual([
      'first.title',
      'first.body',
      'hotTakes.1.title',
      'hotTakes.1.body',
    ]);
    expect(extraction.warnings).toContain(
      'hotTakes[1] is missing "id"; using index-based string ids under "hotTakes.1".',
    );
  });

  it('appends a schema-aware Statsig config entry with source fields', () => {
    const result = appendStatsigSourceConfigEntry(
      JSON.stringify(buildStatsigSchema()),
      JSON.stringify(buildStatsigSourceConfig()),
      HOT_TAKES_PROFILE,
    );

    expect(JSON.parse(result.sourceConfigJson)).toEqual({
      hotTakes: [
        buildStatsigSourceConfig().hotTakes[0],
        {
          id: 'item_2',
          translations: {
            'en-US': {
              title: '',
              body: '',
            },
          },
        },
      ],
    });
    expect(result.stringIds).toEqual(['item_2.title', 'item_2.body']);
    expect(result.appended).toBe(true);
  });

  it('appends another Statsig config entry when a blank entry already exists', () => {
    const config = {
      hotTakes: [
        buildStatsigSourceConfig().hotTakes[0],
        {
          id: 'item_2',
          translations: {
            'en-US': {
              title: '',
              body: '',
            },
          },
        },
      ],
    };
    const result = appendStatsigSourceConfigEntry(
      JSON.stringify(buildStatsigSchema()),
      JSON.stringify(config),
      HOT_TAKES_PROFILE,
    );

    expect(JSON.parse(result.sourceConfigJson)).toEqual({
      hotTakes: [
        ...config.hotTakes,
        {
          id: 'item_3',
          translations: {
            'en-US': {
              title: '',
              body: '',
            },
          },
        },
      ],
    });
    expect(result.itemKey).toBe('item_3');
    expect(result.stringIds).toEqual(['item_3.title', 'item_3.body']);
    expect(result.appended).toBe(true);
  });

  it('appends multilingual FormatJS entries inside the configured message map', () => {
    const profile = {
      ...DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE,
      format: 'FORMATJS_MULTILINGUAL_MAP' as const,
      collectionKey: 'messages',
      sourceField: 'defaultMessage',
      commentField: 'description',
    };
    const result = appendStatsigSourceConfigEntry('', JSON.stringify({ messages: {} }), profile);

    expect(JSON.parse(result.sourceConfigJson)).toEqual({
      messages: {
        'message.1': {
          defaultMessage: '',
          description: '',
          translations: {},
        },
      },
    });
    expect(result.itemKey).toBe('message.1');
    expect(result.stringIds).toEqual(['message.1']);
    expect(result.appended).toBe(true);
  });

  it('omits generated id fields when a strict schema does not allow them', () => {
    const schema = buildStatsigSchemaWithoutId() as ReturnType<
      typeof buildStatsigSchemaWithoutId
    > & {
      properties: {
        hotTakes: {
          items: {
            additionalProperties?: boolean;
          };
        };
      };
    };
    schema.properties.hotTakes.items.additionalProperties = false;
    const result = appendStatsigSourceConfigEntry(
      JSON.stringify(schema),
      JSON.stringify({ hotTakes: [] }),
      HOT_TAKES_PROFILE,
    );

    expect(JSON.parse(result.sourceConfigJson)).toEqual({
      hotTakes: [
        {
          translations: {
            'en-US': {
              title: '',
              body: '',
            },
          },
        },
      ],
    });
    expect(result.stringIds).toEqual(['hotTakes.0.title', 'hotTakes.0.body']);
    expect(result.appended).toBe(true);
  });

  it('builds a Statsig config export from active strings and target readiness', () => {
    const extraction = extractStatsigSourceConfigStrings(
      JSON.stringify(buildStatsigSchema()),
      JSON.stringify(buildStatsigSourceConfig()),
    );
    const sourceStrings = extraction.strings.map((sourceString, index) => ({
      ...sourceString,
      clientId: `text-unit-${index + 11}`,
      tmTextUnitId: index + 11,
    }));
    const readiness = buildJsonConfigLocalizationReadiness(
      sourceStrings,
      [buildTargetRow(11, 'fr', 'Les USA peuvent faire ciller le Bresil', 'APPROVED')],
      ['fr'],
    );

    expect(
      buildStatsigSourceConfigExport(
        extraction.profile,
        extraction.sourceConfig,
        ['fr'],
        sourceStrings,
        readiness,
        {
          fr: 'fr-FR',
        },
      ).value,
    ).toEqual({
      hotTakes: [
        {
          id: 'usa_brazil_blink',
          enabled: true,
          translations: {
            'en-US': {
              title: 'USA can make Brazil blink',
              body: 'A fast start makes the whole bracket nervous.',
            },
            'fr-FR': {
              title: 'Les USA peuvent faire ciller le Bresil',
            },
          },
        },
      ],
    });
  });

  it('keeps original source fields in Statsig config export when a Mojito source string is missing', () => {
    const extraction = extractStatsigSourceConfigStrings(
      JSON.stringify(buildStatsigSchema()),
      JSON.stringify(buildStatsigSourceConfig()),
    );
    const [titleString] = extraction.strings;
    const sourceStrings = [
      {
        ...titleString,
        source: 'Edited source title',
        clientId: 'text-unit-11',
        tmTextUnitId: 11,
      },
    ];
    const readiness = buildJsonConfigLocalizationReadiness(
      sourceStrings,
      [buildTargetRow(11, 'fr', 'Titre traduit', 'APPROVED')],
      ['fr'],
    );

    expect(
      buildStatsigSourceConfigExport(
        extraction.profile,
        extraction.sourceConfig,
        ['fr'],
        sourceStrings,
        readiness,
        {
          fr: 'fr-FR',
        },
      ).value,
    ).toEqual({
      hotTakes: [
        {
          id: 'usa_brazil_blink',
          enabled: true,
          translations: {
            'en-US': {
              title: 'Edited source title',
              body: 'A fast start makes the whole bracket nervous.',
            },
            'fr-FR': {
              title: 'Titre traduit',
            },
          },
        },
      ],
    });
  });
});

function buildTargetRow(
  tmTextUnitId: number,
  targetLocale: string,
  target: string,
  status: ApiTextUnit['status'],
): ApiTextUnit {
  return {
    tmTextUnitId,
    name: 'welcome.title',
    source: 'Welcome',
    target,
    targetLocale,
    used: true,
    status,
    includedInLocalizedFile: true,
  };
}

function buildStatsigSchema() {
  return {
    type: 'object',
    properties: {
      hotTakes: {
        type: 'array',
        items: {
          type: 'object',
          properties: {
            id: { type: 'string' },
            enabled: { type: 'boolean' },
            translations: {
              type: 'object',
              properties: {
                'en-US': {
                  type: 'object',
                  properties: {
                    title: { type: 'string' },
                    body: { type: 'string' },
                  },
                  required: ['title', 'body'],
                },
              },
            },
          },
        },
      },
    },
  };
}

function buildStatsigSchemaWithoutId() {
  return {
    type: 'object',
    properties: {
      hotTakes: {
        type: 'array',
        items: {
          type: 'object',
          properties: {
            translations: {
              type: 'object',
              properties: {
                'en-US': {
                  type: 'object',
                  properties: {
                    title: { type: 'string' },
                    body: { type: 'string' },
                  },
                },
              },
            },
          },
        },
      },
    },
  };
}

function buildStatsigSourceConfig() {
  return {
    hotTakes: [
      {
        id: 'usa_brazil_blink',
        enabled: true,
        translations: {
          'en-US': {
            title: 'USA can make Brazil blink',
            body: 'A fast start makes the whole bracket nervous.',
          },
        },
      },
    ],
  };
}
