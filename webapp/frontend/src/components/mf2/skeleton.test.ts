// @vitest-environment node

import { describe, expect, it } from 'vitest';

import { mf2TranslationErrors } from './translationValidation';

const source = `.input {$status :string}
.input {$count :integer select=exact}
.match $status $count
active 0 {{No jobs.}}
active * {{Jobs for {$name}: {$count}.}}
paused * {{Paused: {$count}.}}
* * {{Other: {$count}.}}`;

function errors(original: string, target: string, locale = 'fr') {
  return mf2TranslationErrors({ source: original, target, locale });
}

describe('MF2 protected skeleton', () => {
  it('allows translation, newlines, literal braces, and reordered placeholders', () => {
    const target = source.replace(
      'Jobs for {$name}: {$count}.',
      '{$count} tâches\npour {$name}. \\{Exemple\\}',
    );
    expect(errors(source, target)).toEqual([]);
  });

  it('compares parsed values, including quoted literals and reordered options', () => {
    expect(errors(source, source.replace('select=exact', 'select=|exact|'))).toEqual([]);
    const original =
      '.input {$count :number select=ordinal maximumFractionDigits=0}\n.match $count\none {{First}}\n* {{Other}}';
    expect(
      errors(
        original,
        original.replace(
          'select=ordinal maximumFractionDigits=0',
          'maximumFractionDigits=|0| select=|ordinal|',
        ),
      ),
    ).toEqual([]);
  });

  it.each([
    ['selector type', (s: string) => s.replace(':integer', ':number')],
    ['selector behavior', (s: string) => s.replace('select=exact', 'select=plural')],
    ['fixed key', (s: string) => s.replace('paused *', 'stopped *')],
    ['exact-number key', (s: string) => s.replace('active 0', 'active 1')],
    ['removed branch', (s: string) => s.replace('paused * {{Paused: {$count}.}}\n', '')],
    ['removed placeholder', (s: string) => s.replace('{$name}', 'quelqu’un')],
    ['changed placeholder function', (s: string) => s.replace('{$name}', '{$name :string}')],
    ['duplicated placeholder', (s: string) => s.replace('{$name}', '{$name} {$name}')],
    ['selector order', (s: string) => s.replace('.match $status $count', '.match $count $status')],
  ])('blocks a %s change even when the target parses', (_name, mutate) => {
    expect(errors(source, mutate(source)).some((error) => error.code.startsWith('mf2-'))).toBe(
      true,
    );
  });

  it('protects non-selector locals and formatter option values', () => {
    const original = '.local $fee = {$price :currency currency=EUR}\n{{Admission: {$fee}.}}';
    expect(errors(original, original.replace('currency=EUR', 'currency=USD'))).toEqual(
      expect.arrayContaining([expect.objectContaining({ code: 'mf2-declarations-changed' })]),
    );
    const inline = 'Admission: {$price :currency currency=EUR}.';
    expect(errors(inline, inline.replace('currency=EUR', 'currency=USD'))).toEqual(
      expect.arrayContaining([expect.objectContaining({ code: 'mf2-placeholders-changed' })]),
    );
  });

  it('protects markup values, multiplicity, and nesting', () => {
    const original = 'Read {#link href=|/help| @id=help}{#bold}help{/bold}{/link}, {$name}.';
    for (const target of [
      original.replace('/help', '/other'),
      original.replace('@id=help', '@id=other'),
      original.replace('{/bold}{/link}', '{/link}{/bold}'),
      original.replace('{#bold}', '').replace('{/bold}', ''),
    ])
      expect(errors(original, target)).toEqual(
        expect.arrayContaining([expect.objectContaining({ code: 'mf2-placeholders-changed' })]),
      );
  });

  it('distinguishes a literal star key from a wildcard', () => {
    const original =
      '.input {$status :string}\n.match $status\n|*| {{Literal star}}\n* {{Fallback}}';
    expect(errors(original, '.input {$status :string}\n.match $status\n* {{Fallback}}')).toEqual(
      expect.arrayContaining([expect.objectContaining({ code: 'mf2-branches-changed' })]),
    );
  });

  it('preserves a fixed-status fallback even when its singular plural form remains', () => {
    const original = `.input {$status :string}
.input {$count :number}
.match $status $count
active one {{One active job}}
active * {{Active jobs: {$count}}}
* * {{Other jobs: {$count}}}`;
    expect(errors(original, original.replace('active * {{Active jobs: {$count}}}\n', ''))).toEqual(
      expect.arrayContaining([expect.objectContaining({ code: 'mf2-branches-changed' })]),
    );
  });

  it('allows locale plural forms and spelled-out Arabic zero, one and two', () => {
    const original =
      '.input {$count :number}\n.match $count\none {{One week}}\n* {{You have {$count} weeks.}}';
    const target = `.input {$count :number}
.match $count
zero {{ليس لديك أي أسابيع.}}
one {{لديك أسبوع واحد.}}
two {{لديك أسبوعان.}}
few {{لديك {$count} أسابيع.}}
many {{لديك {$count} أسبوعًا.}}
* {{لديك {$count} أسبوع.}}`;
    expect(errors(original, target, 'ar')).toEqual([]);
    expect(errors(original, target.replace('{$count} أسابيع', 'بعض الأسابيع'), 'ar')).not.toEqual(
      [],
    );
  });

  it.each(['ru', 'fr'])(
    'allows a bare counter where the %s singular category covers several quantities',
    (locale) => {
      const original =
        '.input {$count :number}\n.match $count\none {{One week}}\n* {{{$name}: {$count} weeks.}}';
      const target = original.replace('One week', '{$count} week');
      expect(errors(original, target, locale)).toEqual([]);
      for (const invalid of [
        target.replace('{$count} weeks.', 'weeks.'),
        target.replace('{$count} week}}', '{$count} {$count} week}}'),
        target.replace('{$count} week}}', '{$name}: {$count} week}}'),
        target.replace('{$count} week}}', '{$count :number} week}}'),
      ]) {
        expect(errors(original, invalid, locale)).toContainEqual(
          expect.objectContaining({ code: 'mf2-placeholders-changed' }),
        );
      }
    },
  );

  it('does not allow adding a counter for an input that is not a selector', () => {
    const original = '.input {$count :number}\n{{One week}}';
    expect(errors(original, original.replace('One week', '{$count} weeks'), 'ru')).toContainEqual(
      expect.objectContaining({ code: 'mf2-placeholders-changed' }),
    );
  });

  it('allows fewer French ordinal categories and more Polish categories with markup', () => {
    const ordinal =
      '.input {$n :number select=ordinal}\n.match $n\none {{{$n}st}}\ntwo {{{$n}nd}}\nfew {{{$n}rd}}\n* {{{$n}th}}';
    expect(
      errors(ordinal, '.input {$n :number select=ordinal}\n.match $n\none {{{$n}er}}\n* {{{$n}e}}'),
    ).toEqual([]);
    const original =
      '.input {$n :number}\n.match $n\none {{Read {#link}one{/link}.}}\n* {{Read {#link}{$n}{/link}.}}';
    const target =
      '.input {$n :number}\n.match $n\none {{Czytaj {#link}jeden{/link}.}}\nfew {{Czytaj {#link}{$n}{/link}.}}\nmany {{Czytaj {#link}{$n}{/link}.}}\n* {{Czytaj {#link}{$n}{/link}.}}';
    expect(errors(original, target, 'pl')).toEqual([]);
  });

  it('allows adding plural selection from an existing numeric input, preserving other placeholders', () => {
    const original = '.input {$count :number}\n{{{$name}: {$count} files.}}';
    const target =
      '.input {$count :number}\n.match $count\none {{{$name}: one file.}}\n* {{{$name}: {$count} files.}}';
    expect(errors(original, target, 'en')).toEqual([]);
    expect(errors(original, target.replace('{$name}: one', 'One'), 'en')).not.toEqual([]);
  });
});
