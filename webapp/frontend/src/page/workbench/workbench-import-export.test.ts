// @vitest-environment node

import { describe, expect, it } from 'vitest';

import {
  buildImportTemplateCsv,
  getOrderedExportFields,
  parseImportFileContent,
} from './workbench-import-export';

describe('parseImportFileContent', () => {
  it('parses valid CSV rows into import payloads', () => {
    const parsed = parseImportFileContent(
      'translations.csv',
      [
        buildImportTemplateCsv(),
        'repo-a,src/messages.properties,fr-FR,42,,Bonjour',
        'repo-a,src/messages.properties,de-DE,,greeting.hello,Hallo',
      ].join('\n'),
    );

    expect(parsed.format).toBe('csv');
    expect(parsed.errors).toEqual([]);
    expect(parsed.textUnits).toEqual([
      {
        repositoryName: 'repo-a',
        assetPath: 'src/messages.properties',
        targetLocale: 'fr-FR',
        tmTextUnitId: 42,
        target: 'Bonjour',
      },
      {
        repositoryName: 'repo-a',
        assetPath: 'src/messages.properties',
        targetLocale: 'de-DE',
        name: 'greeting.hello',
        target: 'Hallo',
      },
    ]);
  });

  it('reports validation errors for malformed rows', () => {
    const parsed = parseImportFileContent(
      'translations.csv',
      [
        'repositoryName,assetPath,targetLocale,tmTextUnitId,name,target,branchId,doNotTranslate',
        'repo-a,src/messages.properties,fr-FR,,,Bonjour,abc,maybe',
      ].join('\n'),
    );

    expect(parsed.textUnits).toEqual([]);
    expect(parsed.errors).toEqual(['Row 2: either tmTextUnitId or name is required.']);
  });

  it('parses JSON textUnits payloads', () => {
    const parsed = parseImportFileContent(
      'translations.json',
      JSON.stringify({
        textUnits: [
          {
            repositoryName: 'repo-b',
            assetPath: 'src/ui.json',
            targetLocale: 'es-ES',
            name: 'button.submit',
            target: 'Enviar',
            doNotTranslate: false,
          },
        ],
      }),
    );

    expect(parsed.format).toBe('json');
    expect(parsed.errors).toEqual([]);
    expect(parsed.textUnits).toEqual([
      {
        repositoryName: 'repo-b',
        assetPath: 'src/ui.json',
        targetLocale: 'es-ES',
        name: 'button.submit',
        target: 'Enviar',
        doNotTranslate: false,
      },
    ]);
  });
});

describe('getOrderedExportFields', () => {
  it('moves priority fields to the front without dropping other fields', () => {
    expect(
      getOrderedExportFields([
        'target',
        'status',
        'assetPath',
        'tmTextUnitId',
        'source',
        'targetLocale',
      ]),
    ).toEqual(['targetLocale', 'source', 'target', 'tmTextUnitId', 'status', 'assetPath']);
  });
});
