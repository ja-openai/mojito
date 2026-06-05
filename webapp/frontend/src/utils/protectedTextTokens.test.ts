import { describe, expect, it } from 'vitest';

import {
  buildIcuExactPluralOptionInsertion,
  canParseProtectedTextTokens,
  extractHtmlTagTokens,
  extractIcuAndHtmlProtectedTextTokens,
  extractIcuProtectedTextTokens,
  extractMf2DemoProtectedTextTokens,
  getIcuPluralOptionInsertions,
  preservesProtectedTextTokenStructure,
} from './protectedTextTokens';

describe('protectedTextTokens', () => {
  it('extracts simple ICU argument placeholders', () => {
    expect(extractIcuProtectedTextTokens('Hello {name}.')).toEqual([
      {
        start: 6,
        end: 12,
        label: 'ICU argument name',
        kind: 'icu-placeholder',
      },
    ]);
  });

  it('protects ICU plural syntax while leaving option text editable', () => {
    const message = 'You have {count, plural, one {# file} other {# files}}.';
    const tokens = extractIcuProtectedTextTokens(message);

    expect(tokens.map((token) => message.slice(token.start, token.end))).toEqual([
      '{count, plural, one {',
      '#',
      '} other {',
      '#',
      '}}',
    ]);
  });

  it('builds safe ICU plural option insertions before other', () => {
    const message = 'You have {count, plural, one {# file} other {# files}}.';
    const few = getIcuPluralOptionInsertions(message).find(
      (insertion) => insertion.label === 'count: few',
    );

    expect(few?.nextValue).toBe('You have {count, plural, one {# file} few {# } other {# files}}.');
    expect(few?.selectionStart).toBe('You have {count, plural, one {# file} few {# '.length);
    expect(few?.selectionEnd).toBe(few?.selectionStart);
  });

  it('offers an ICU exact-value plural insertion action', () => {
    const message = 'You have {count, plural, one {# file} other {# files}}.';

    expect(getIcuPluralOptionInsertions(message).map((insertion) => insertion.label)).toEqual([
      'count: zero',
      'count: two',
      'count: few',
      'count: many',
      'count: exact value...',
    ]);
  });

  it('builds ICU exact-value plural insertions before other', () => {
    const message = 'You have {count, plural, one {# file} other {# files}}.';
    const exact = getIcuPluralOptionInsertions(message).find(
      (insertion) =>
        insertion.kind === 'exact-value' && insertion.label === 'count: exact value...',
    );

    expect(exact).toBeDefined();
    const result = buildIcuExactPluralOptionInsertion(message, exact!, '=0');

    expect(result).toEqual({
      ok: true,
      nextValue: 'You have {count, plural, one {# file} =0 {# } other {# files}}.',
      selectionStart: 'You have {count, plural, one {# file} =0 {# '.length,
      selectionEnd: 'You have {count, plural, one {# file} =0 {# '.length,
    });
  });

  it('rejects duplicate ICU exact-value plural insertions', () => {
    const message = 'You have {count, plural, =0 {none} one {# file} other {# files}}.';
    const exact = getIcuPluralOptionInsertions(message).find(
      (insertion) => insertion.kind === 'exact-value',
    );

    expect(buildIcuExactPluralOptionInsertion(message, exact!, '0')).toEqual({
      ok: false,
      error: '=0 already exists.',
    });
  });

  it('labels ICU plural option insertions by argument for multiple plurals', () => {
    const message =
      '{fileCount, plural, one {# file} other {# files}} and {folderCount, plural, one {# folder} other {# folders}}';

    expect(getIcuPluralOptionInsertions(message).map((insertion) => insertion.label)).toEqual([
      'fileCount: zero',
      'fileCount: two',
      'fileCount: few',
      'fileCount: many',
      'fileCount: exact value...',
      'folderCount: zero',
      'folderCount: two',
      'folderCount: few',
      'folderCount: many',
      'folderCount: exact value...',
    ]);
  });

  it('extracts HTML placeholder tags', () => {
    const message = 'Read <termsOfUseLink>the terms</termsOfUseLink>.';

    expect(
      extractHtmlTagTokens(message).map((token) => message.slice(token.start, token.end)),
    ).toEqual(['<termsOfUseLink>', '</termsOfUseLink>']);
  });

  it('extracts ICU and HTML placeholder tags together', () => {
    const message = '{count, plural, one {# <link>file</link>} other {# <link>files</link>}}';

    expect(
      extractIcuAndHtmlProtectedTextTokens(message).map((token) =>
        message.slice(token.start, token.end),
      ),
    ).toEqual([
      '{count, plural, one {',
      '#',
      '<link>',
      '</link>',
      '} other {',
      '#',
      '<link>',
      '</link>',
      '}}',
    ]);
  });

  it('extracts MF2 demo tokens', () => {
    const message = '.input {$count :number}\n.match {$count}';

    expect(
      extractMf2DemoProtectedTextTokens(message).map((token) =>
        message.slice(token.start, token.end),
      ),
    ).toEqual(['.input', '{$count', ':number', '.match', '{$count}']);
  });

  it('allows ICU literal edits without changing protected structure', () => {
    const previousValue = 'You have {count, plural, one {# file} other {# files}}.';
    const nextValue = 'You have {count, plural, one {# document} other {# documents}}.';

    expect(
      preservesProtectedTextTokenStructure({
        previousValue,
        previousTokens: extractIcuProtectedTextTokens(previousValue),
        nextValue,
        mode: 'icu',
      }),
    ).toBe(true);
  });

  it('allows transient invalid ICU while typing an opening brace', () => {
    const previousValue = 'Hello {name}.';
    const nextValue = 'Hello {name}. {';

    expect(canParseProtectedTextTokens(previousValue, 'icu')).toBe(true);
    expect(canParseProtectedTextTokens(nextValue, 'icu')).toBe(false);
    expect(
      preservesProtectedTextTokenStructure({
        previousValue,
        previousTokens: extractIcuProtectedTextTokens(previousValue),
        nextValue,
        mode: 'icu',
      }),
    ).toBe(true);
  });

  it('allows returning from transient invalid ICU to a parseable message', () => {
    const previousValue = 'Hello {name}. {';
    const nextValue = 'Hello {name}.';

    expect(
      preservesProtectedTextTokenStructure({
        previousValue,
        previousTokens: extractIcuProtectedTextTokens(previousValue),
        nextValue,
        mode: 'icu',
      }),
    ).toBe(true);
  });

  it('allows completing a placeholder from transient invalid ICU', () => {
    const previousValue = 'Hello {';
    const nextValue = 'Hello {name}.';

    expect(
      preservesProtectedTextTokenStructure({
        previousValue,
        previousTokens: extractIcuProtectedTextTokens(previousValue),
        nextValue,
        mode: 'icu',
      }),
    ).toBe(true);
  });

  it('still protects HTML tags during transient invalid ICU edits', () => {
    const previousValue = '<link>Hello</link> {name}.';
    const nextValue = 'Hello</link> {';

    expect(
      preservesProtectedTextTokenStructure({
        previousValue,
        previousTokens: extractIcuAndHtmlProtectedTextTokens(previousValue),
        nextValue,
        mode: 'icu-html',
      }),
    ).toBe(false);
  });

  it('rejects newly added ICU plural options', () => {
    const previousValue = 'You have {count, plural, one {# file} other {# files}}.';
    const nextValue = 'You have {count, plural, one {# file} few {# files} other {# files}}.';

    expect(
      preservesProtectedTextTokenStructure({
        previousValue,
        previousTokens: extractIcuProtectedTextTokens(previousValue),
        nextValue,
        mode: 'icu',
      }),
    ).toBe(false);
  });

  it('rejects newly added ICU placeholders', () => {
    expect(
      preservesProtectedTextTokenStructure({
        previousValue: 'Hello world.',
        previousTokens: [],
        nextValue: 'Hello {name}.',
        mode: 'icu',
      }),
    ).toBe(false);
  });
});
