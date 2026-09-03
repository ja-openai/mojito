// @vitest-environment node

import { formatMessage, parseToModel } from '@mojito-mf2/core';
import { describe, expect, it } from 'vitest';

import { diagnosticsFor, missingPlaceholderNamesForActiveSource, parseMf2 } from './model';

function check(source: string, target: string, locale = 'ar') {
  const original = parseMf2(source, {}, locale, { includeRuntimeDiagnostics: false });
  const translated = parseMf2(target, {}, locale, { includeRuntimeDiagnostics: false });
  return {
    original: original.model!,
    translated: translated.model!,
    diagnostics: diagnosticsFor(original.model, translated.model, translated.diagnostics, locale),
  };
}

const keys = ['0', 'one', '*'];
const declaration = '.input {$likes :integer}\n.input {$shares :integer}\n.match $likes $shares\n';
const matrix = (translated: boolean) =>
  declaration +
  keys
    .flatMap((likes) =>
      keys.map((shares) => {
        const count = (key: string, name: string) =>
          key === '0' ? 'none' : translated && key === 'one' ? 'one' : `{$${name}}`;
        return `${likes} ${shares} {{${count(likes, 'likes')} likes; ${count(shares, 'shares')} shares for {$name}.}}`;
      }),
    )
    .join('\n');

describe('plural-aware MF2 warnings and repairs', () => {
  it('allows implied Arabic singular counts in both selectors and retains useful form reminders', () => {
    const { original, translated, diagnostics } = check(matrix(false), matrix(true));
    expect(diagnostics).toHaveLength(6);
    expect(diagnostics.every((d) => d.code === 'missing-locale-plural-variant')).toBe(true);
    if (original.type !== 'select' || translated.type !== 'select')
      throw new Error('Expected variants');
    for (let index = 0; index < translated.variants.length; index++) {
      expect(
        missingPlaceholderNamesForActiveSource(
          original,
          translated,
          original.variants[index].value,
          translated.variants[index].value,
          translated.variants[index],
          'ar',
        ),
      ).toEqual([]);
    }
  });

  it.each([
    ['name', 'one one'],
    ['shares', 'one *'],
    ['likes', '* one'],
  ])('still diagnoses and offers repair for a missing %s in %s', (name, key) => {
    const target = matrix(true)
      .split('\n')
      .map((row) => (row.startsWith(`${key} {{`) ? row.replace(`{$${name}}`, 'omitted') : row))
      .join('\n');
    const { original, translated, diagnostics } = check(matrix(false), target);
    expect(
      diagnostics.some(
        (d) => d.code === 'variant-missing-placeholder' && d.message.includes(`{$${name}}`),
      ),
    ).toBe(true);
    expect(diagnostics.some((d) => d.severity === 'error')).toBe(true);
    if (original.type !== 'select' || translated.type !== 'select')
      throw new Error('Expected variants');
    const index = translated.variants.findIndex((v) => v.keys.join(' ') === key);
    expect(
      missingPlaceholderNamesForActiveSource(
        original,
        translated,
        original.variants[index].value,
        translated.variants[index].value,
        translated.variants[index],
        'ar',
      ),
    ).toEqual([name]);
  });

  it('allows spelling out an exact count but preserves a formatted counter expression', () => {
    const source =
      '.input {$count :integer}\n.match $count\n2 {{Count: {$count}}}\n* {{Count: {$count}}}';
    expect(
      check(source, source.replace('2 {{Count: {$count}}}', '2 {{A pair}}')).diagnostics.filter(
        (d) => d.code === 'variant-missing-placeholder' || d.severity === 'error',
      ),
    ).toEqual([]);
    const formatted = source.replace(
      '2 {{Count: {$count}}}',
      '2 {{Count: {$count :number minimumFractionDigits=1}}}',
    );
    expect(check(formatted, source).diagnostics).toContainEqual(
      expect.objectContaining({ code: 'variant-missing-placeholder' }),
    );
  });

  it.each(['ru', 'fr', 'lv'])('preserves a variable count in the %s one category', (locale) => {
    const source =
      '.input {$count :number}\n.match $count\none {{Count: {$count}}}\n* {{Count: {$count}}}';
    expect(
      check(source, source.replace('one {{Count: {$count}}}', 'one {{One}}'), locale).diagnostics,
    ).toContainEqual(expect.objectContaining({ code: 'variant-missing-placeholder' }));
  });

  it('keeps warning about crossed wildcard rows when their intersection is unresolved', () => {
    const incomplete =
      declaration + 'one * {{{$likes} likes}}\n* one {{{$shares} shares}}\n* * {{Other}}';
    expect(
      check(incomplete, incomplete).diagnostics.filter(
        (d) => d.code === 'selector-priority-overlap',
      ),
    ).toHaveLength(1);
    const complete = incomplete + '\none one {{One like and one share}}';
    expect(
      check(complete, complete).diagnostics.filter((d) => d.code === 'selector-priority-overlap'),
    ).toEqual([]);
  });

  it.each(['number', 'integer'])(
    'treats exact and category forms as normal %s selection in either order',
    (fn) => {
      const rows = ['one {{Category}}', '0 {{Exact zero}}'];
      for (const ordered of [rows, [...rows].reverse()]) {
        const source = `.input {$count :${fn}}\n.match $count\n${ordered.join('\n')}\n* {{Other}}`;
        const model = parseToModel(source).model!;
        expect(
          formatMessage(model, { count: 0 }, { locale: 'fr', bidiIsolation: 'none' }).value,
        ).toBe('Exact zero');
        expect(
          check(source, source, 'fr').diagnostics.some((d) => d.code.includes('overlap')),
        ).toBe(false);
      }
    },
  );
});
