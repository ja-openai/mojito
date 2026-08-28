// @vitest-environment node

import { describe, expect, it } from 'vitest';

import {
  diagnosticsFor,
  type EditorModel,
  parseMf2,
  promoteSimpleTargetToLocalePluralSelect,
  sourceDeclaredLocalePluralSelectors,
} from './model';

function parseModel(
  source: string,
  args: Record<string, unknown> = {},
  locale = 'en',
): EditorModel {
  const parsed = parseMf2(source, args, locale);
  expect(parsed.model).not.toBeNull();
  return parsed.model as EditorModel;
}

describe('source-declared locale plural selectors', () => {
  it('reports numeric source inputs and excludes exact selectors', () => {
    const source = parseModel(`.input {$count :number}
.input {$rank :integer select=ordinal}
.input {$exact :number select=exact}
.input {$status :string}
{{Files}}`);

    expect(sourceDeclaredLocalePluralSelectors(source)).toEqual([
      {
        function: 'number',
        kind: 'cardinal',
        name: 'count',
        optionText: '',
      },
      {
        function: 'integer',
        kind: 'ordinal',
        name: 'rank',
        optionText: 'select=|ordinal|',
      },
    ]);
  });
});

describe('promoteSimpleTargetToLocalePluralSelect', () => {
  it.each([
    ['fr', ['one', 'many', 'other', '*']],
    ['ru', ['one', 'few', 'many', 'other', '*']],
  ])('creates blank %s locale categories and a fallback', (locale, expectedKeys) => {
    const source = parseModel(
      `.input {$count :number}
{{You have {$count} files.}}`,
      { count: 2 },
    );
    const target = parseModel('La traduction existante.');

    const promoted = promoteSimpleTargetToLocalePluralSelect(source, target, 'count', locale);

    expect(promoted?.activeIndex).toBe(0);
    expect(promoted?.model.selectors).toEqual(['count']);
    expect(promoted?.model.variants.map((variant) => variant.keys[0])).toEqual(expectedKeys);
    expect(promoted?.model.variants.slice(0, -1).every((variant) => variant.value === '')).toBe(
      true,
    );
    const variants = promoted?.model.variants ?? [];
    expect(variants[variants.length - 1]).toEqual({
      keys: ['*'],
      value: 'La traduction existante.',
    });
    expect(promoted?.model.declarations).toContainEqual(
      expect.objectContaining({
        function: 'number',
        name: 'count',
        type: 'input',
      }),
    );
  });

  it('preserves the existing target pattern only in the fallback row', () => {
    const source = parseModel(
      `.input {$count :number}
{{You have {$count} files.}}`,
      { count: 3 },
    );
    const target = parseModel('Conserver {$count} ici.', { count: 3 }, 'fr');

    const promoted = promoteSimpleTargetToLocalePluralSelect(source, target, 'count', 'fr');

    expect(
      promoted?.model.variants.filter((variant) => variant.value === 'Conserver {$count} ici.'),
    ).toEqual([{ keys: ['*'], value: 'Conserver {$count} ici.' }]);
  });
});

describe('target-only selector diagnostics', () => {
  it('allows a matching selector-capable source input', () => {
    const source = parseModel(
      `.input {$count :number}
{{You have {$count} files.}}`,
      { count: 2 },
    );
    const target = parseModel(
      `.input {$count :number}
.match $count
one {{Vous avez {$count} fichier.}}
* {{Vous avez {$count} fichiers.}}`,
      { count: 2 },
      'fr',
    );

    expect(
      diagnosticsFor(source, target, [], 'fr').filter(
        (diagnostic) => diagnostic.severity === 'error',
      ),
    ).toEqual([]);
  });

  it('rejects a target selector with a different annotation', () => {
    const source = parseModel(
      `.input {$count :number}
{{You have files.}}`,
      { count: 2 },
    );
    const target = parseModel(
      `.input {$count :string}
.match $count
one {{Un fichier.}}
* {{Des fichiers.}}`,
      { count: 'one' },
      'fr',
    );

    expect(diagnosticsFor(source, target, [], 'fr')).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          code: 'selector-annotation-mismatch',
          severity: 'error',
        }),
      ]),
    );
  });

  it('rejects undeclared and non-selector-capable source inputs', () => {
    const undeclaredSource = parseModel('You have {$count} files.', { count: 2 });
    const dateSource = parseModel(
      `.input {$createdAt :date}
{{Created {$createdAt}.}}`,
      { createdAt: '2026-08-27' },
    );
    const countTarget = parseModel(
      `.input {$count :number}
.match $count
one {{Un fichier.}}
* {{Des fichiers.}}`,
      { count: 2 },
      'fr',
    );
    const dateTarget = parseModel(
      `.input {$createdAt :date}
.match $createdAt
today {{Aujourd'hui.}}
* {{Un autre jour.}}`,
      { createdAt: 'today' },
      'fr',
    );

    expect(diagnosticsFor(undeclaredSource, countTarget, [], 'fr')).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ code: 'new-selector', severity: 'error' }),
      ]),
    );
    expect(diagnosticsFor(dateSource, dateTarget, [], 'fr')).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ code: 'new-selector', severity: 'error' }),
      ]),
    );
  });
});
