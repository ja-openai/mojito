import { describe, expect, it } from 'vitest';

import {
  buildIcuExactPluralOptionInsertion,
  canParseProtectedTextTokens,
  extractHtmlTagTokens,
  extractIcuAndHtmlProtectedTextTokens,
  extractIcuProtectedTextTokens,
  extractMf2DemoProtectedTextTokens,
  extractPlatformPlaceholderDiagnostics,
  extractPlatformPlaceholderTokens,
  getIcuFormInsertions,
  getIcuMovableTextRanges,
  getProtectedTextDiagnostics,
  preservesProtectedTextTokenStructure,
  relocateProtectedTextTokens,
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

  it('returns whole ICU plural and select ranges as movable units', () => {
    const message =
      'Move {count, plural, one {# file} other {# files}} after {gender, select, other {them}}.';

    expect(getIcuMovableTextRanges(message)).toEqual([
      {
        start: 5,
        end: 50,
        label: 'ICU plural message',
      },
      {
        start: 57,
        end: 87,
        label: 'ICU select message',
      },
    ]);
  });

  it('builds safe ICU plural form insertions before other', () => {
    const message = 'You have {count, plural, one {# file} other {# files}}.';
    const few = getIcuFormInsertions(message).find((insertion) => insertion.label === 'count: few');

    expect(few?.nextValue).toBe('You have {count, plural, one {# file} few {# } other {# files}}.');
    expect(few?.selectionStart).toBe('You have {count, plural, one {# file} few {# '.length);
    expect(few?.selectionEnd).toBe(few?.selectionStart);
  });

  it('offers an ICU exact-value plural insertion action', () => {
    const message = 'You have {count, plural, one {# file} other {# files}}.';

    expect(getIcuFormInsertions(message).map((insertion) => insertion.label)).toEqual([
      'count: zero',
      'count: two',
      'count: few',
      'count: many',
      'count: exact value...',
    ]);
  });

  it('offers the required ICU other form when it is missing', () => {
    const message = 'You have {count, plural, one {# file}}.';
    const other = getIcuFormInsertions(message).find(
      (insertion) => insertion.label === 'count: other',
    );

    expect(other?.nextValue).toBe('You have {count, plural, one {# file} other {# } }.');
    expect(other?.selectionStart).toBe('You have {count, plural, one {# file} other {# '.length);
    expect(other?.selectionEnd).toBe(other?.selectionStart);
  });

  it('offers the required ICU select other form when it is missing', () => {
    const message = '{gender, select, male {his}} file';

    expect(getIcuFormInsertions(message)).toEqual([
      {
        id: '0:other',
        kind: 'category',
        label: 'gender: other',
        messageType: 'select',
        nextValue: '{gender, select, male {his} other { } } file',
        messageEnd: 28,
        messageStart: 0,
        selectionStart: '{gender, select, male {his} other { '.length,
        selectionEnd: '{gender, select, male {his} other { '.length,
      },
    ]);
  });

  it('does not offer arbitrary ICU select forms when other exists', () => {
    const message = '{gender, select, male {his} other {their}} file';

    expect(getIcuFormInsertions(message)).toEqual([]);
  });

  it('builds ICU exact-value plural insertions before other', () => {
    const message = 'You have {count, plural, one {# file} other {# files}}.';
    const exact = getIcuFormInsertions(message).find(
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

  it('builds ICU exact-value plural insertions with a separator when other is missing', () => {
    const message = 'You have {count, plural, one {# file}}.';
    const exact = getIcuFormInsertions(message).find(
      (insertion) => insertion.kind === 'exact-value',
    );

    expect(exact).toBeDefined();
    const result = buildIcuExactPluralOptionInsertion(message, exact!, '=0');

    expect(result).toEqual({
      ok: true,
      nextValue: 'You have {count, plural, one {# file} =0 {# } }.',
      selectionStart: 'You have {count, plural, one {# file} =0 {# '.length,
      selectionEnd: 'You have {count, plural, one {# file} =0 {# '.length,
    });
  });

  it('rejects duplicate ICU exact-value plural insertions', () => {
    const message = 'You have {count, plural, =0 {none} one {# file} other {# files}}.';
    const exact = getIcuFormInsertions(message).find(
      (insertion) => insertion.kind === 'exact-value',
    );

    expect(buildIcuExactPluralOptionInsertion(message, exact!, '0')).toEqual({
      ok: false,
      error: '=0 already exists.',
    });
  });

  it('labels ICU form insertions by argument for multiple plurals', () => {
    const message =
      '{fileCount, plural, one {# file} other {# files}} and {folderCount, plural, one {# folder} other {# folders}}';

    expect(getIcuFormInsertions(message).map((insertion) => insertion.label)).toEqual([
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
    const message =
      '{count, plural, one {# <link>%1$s file</link>} other {# <link>%1$s files</link>}}';

    expect(
      extractIcuAndHtmlProtectedTextTokens(message).map((token) =>
        message.slice(token.start, token.end),
      ),
    ).toEqual([
      '{count, plural, one {',
      '#',
      '<link>',
      '%1$s',
      '</link>',
      '} other {',
      '#',
      '<link>',
      '%1$s',
      '</link>',
      '}}',
    ]);
  });

  it('extracts Android and iOS printf-style platform placeholders', () => {
    const message = 'Pay %1$@ by %.2f%% or use %lld files, %s, %#@files@, and %(name)s.';

    expect(
      extractPlatformPlaceholderTokens(message).map((token) =>
        message.slice(token.start, token.end),
      ),
    ).toEqual(['%1$@', '%.2f', '%lld', '%s', '%#@files@', '%(name)s']);
  });

  it('does not extract escaped printf-style platform placeholders', () => {
    const message = 'Literal %%s, %%@, %%1$s, %%.2f, %%#@files@, %%(name)s, and %%%%d.';

    expect(extractPlatformPlaceholderTokens(message)).toEqual([]);
  });

  it('warns about platform placeholders touching identifier text', () => {
    const message = 'Use %1ds, %@name, or %(name)suffix carefully.';
    const numberedStart = message.indexOf('%1d');
    const iosStart = message.indexOf('%@');
    const namedStart = message.indexOf('%(name)s');

    expect(
      extractPlatformPlaceholderTokens(message).map((token) =>
        message.slice(token.start, token.end),
      ),
    ).toEqual(['%1d', '%@', '%(name)s']);
    expect(extractPlatformPlaceholderDiagnostics(message)).toEqual([
      {
        start: numberedStart,
        end: numberedStart + '%1ds'.length,
        severity: 'warning',
        code: 'placeholder-adjacent-text',
        message:
          'Platform placeholder %1d touches text; add a separator or verify the placeholder syntax.',
      },
      {
        start: iosStart,
        end: iosStart + '%@name'.length,
        severity: 'warning',
        code: 'placeholder-adjacent-text',
        message:
          'Platform placeholder %@ touches text; add a separator or verify the placeholder syntax.',
      },
      {
        start: namedStart,
        end: namedStart + '%(name)suffix'.length,
        severity: 'warning',
        code: 'placeholder-adjacent-text',
        message:
          'Platform placeholder %(name)s touches text; add a separator or verify the placeholder syntax.',
      },
    ]);
  });

  it('warns about incomplete platform placeholder-like text', () => {
    const message = 'Broken %1$ and %. placeholders.';

    expect(extractPlatformPlaceholderTokens(message)).toEqual([]);
    expect(getProtectedTextDiagnostics(message, 'icu-html')).toEqual([
      {
        start: 7,
        end: 10,
        severity: 'warning',
        code: 'placeholder-malformed',
        message: 'Placeholder-like sequence %1$ is incomplete or malformed.',
      },
      {
        start: 15,
        end: 17,
        severity: 'warning',
        code: 'placeholder-malformed',
        message: 'Placeholder-like sequence %. is incomplete or malformed.',
      },
    ]);
  });

  it('extends malformed printf-like diagnostics over adjacent identifier text', () => {
    const message = 'Broken %1$kfoo and %1.zfoo placeholders.';

    expect(extractPlatformPlaceholderTokens(message)).toEqual([]);
    expect(getProtectedTextDiagnostics(message, 'icu-html')).toEqual([
      {
        start: message.indexOf('%1$kfoo'),
        end: message.indexOf('%1$kfoo') + '%1$kfoo'.length,
        severity: 'warning',
        code: 'placeholder-malformed',
        message: 'Placeholder-like sequence %1$ is incomplete or malformed.',
      },
      {
        start: message.indexOf('%1.zfoo'),
        end: message.indexOf('%1.zfoo') + '%1.zfoo'.length,
        severity: 'warning',
        code: 'placeholder-malformed',
        message: 'Placeholder-like sequence %1. is incomplete or malformed.',
      },
    ]);
  });

  it('warns about incomplete named platform placeholders', () => {
    const message = 'Broken %(name and %(count).';

    expect(extractPlatformPlaceholderTokens(message)).toEqual([]);
    expect(getProtectedTextDiagnostics(message, 'icu-html')).toEqual([
      {
        start: message.indexOf('%(name'),
        end: message.indexOf('%(name') + '%(name'.length,
        severity: 'warning',
        code: 'placeholder-malformed',
        message: 'Named placeholder %(name is missing its closing ).',
      },
      {
        start: message.indexOf('%(count)'),
        end: message.indexOf('%(count)') + '%(count)'.length,
        severity: 'warning',
        code: 'placeholder-malformed',
        message: 'Named placeholder %(count) is missing its conversion type.',
      },
    ]);
  });

  it('warns about incomplete Apple stringsdict placeholders', () => {
    const message = 'Broken %#@files placeholder.';

    expect(extractPlatformPlaceholderTokens(message)).toEqual([]);
    expect(getProtectedTextDiagnostics(message, 'icu-html')).toEqual([
      {
        start: 7,
        end: 15,
        severity: 'warning',
        code: 'placeholder-malformed',
        message: 'Apple stringsdict placeholder %#@files is missing its closing @.',
      },
    ]);
  });

  it('rejects changing platform placeholders in composite protected text', () => {
    const previousValue = 'Pay %1$s now.';
    const nextValue = 'Pay %2$s now.';

    expect(
      preservesProtectedTextTokenStructure({
        previousValue,
        previousTokens: extractIcuAndHtmlProtectedTextTokens(previousValue),
        nextValue,
        mode: 'icu-html',
      }),
    ).toBe(false);
  });

  it('extracts MF2 demo tokens', () => {
    const message = '.input {$count :number}\n.match {$count}';

    expect(
      extractMf2DemoProtectedTextTokens(message).map((token) =>
        message.slice(token.start, token.end),
      ),
    ).toEqual(['.input', '{$count', ':number', '.match', '{$count}']);
  });

  it('surfaces MF2 demo diagnostics through the shared diagnostic API', () => {
    const message = '.input {$count :number}\n{{Hello {$unknown}}';

    expect(getProtectedTextDiagnostics(message, 'mf2-demo')).toEqual([
      {
        start: 0,
        end: message.length,
        severity: 'error',
        code: 'mf2-error-1',
        message: 'MF2 syntax: add the missing closing brace.',
      },
      {
        start: 0,
        end: message.length,
        severity: 'warning',
        code: 'mf2-warning-2',
        message: 'Unknown source placeholder {$unknown}.',
      },
    ]);
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

  it('relocates protected tokens during transient invalid ICU edits', () => {
    const previousValue = 'Hello {name}.';
    const nextValue = 'Well, Hello {name}. {';

    expect(
      relocateProtectedTextTokens(
        previousValue,
        extractIcuProtectedTextTokens(previousValue),
        nextValue,
      ),
    ).toEqual([
      {
        start: 12,
        end: 18,
        label: 'ICU argument name',
        kind: 'icu-placeholder',
      },
    ]);
  });

  it('rejects removing an ICU placeholder during transient invalid ICU edits', () => {
    const previousValue = 'Hello {name}.';
    const nextValue = 'Hello . {';

    expect(canParseProtectedTextTokens(nextValue, 'icu')).toBe(false);
    expect(
      preservesProtectedTextTokenStructure({
        previousValue,
        previousTokens: extractIcuProtectedTextTokens(previousValue),
        nextValue,
        mode: 'icu',
      }),
    ).toBe(false);
  });

  it('rejects removing ICU syntax during transient invalid ICU edits', () => {
    const previousValue = 'You have {count, plural, one {# file} other {# files}}.';
    const nextValue = 'You have {count, plural, one {# file} other {files}. {';

    expect(canParseProtectedTextTokens(nextValue, 'icu')).toBe(false);
    expect(
      preservesProtectedTextTokenStructure({
        previousValue,
        previousTokens: extractIcuProtectedTextTokens(previousValue),
        nextValue,
        mode: 'icu',
      }),
    ).toBe(false);
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

  it('allows adding a new ICU placeholder outside existing protected structure', () => {
    const previousValue = 'Hello {name}.';
    const nextValue = 'Hello {name}. Pay {price}.';

    expect(
      preservesProtectedTextTokenStructure({
        previousValue,
        previousTokens: extractIcuProtectedTextTokens(previousValue),
        nextValue,
        mode: 'icu',
      }),
    ).toBe(true);
  });

  it('allows adding a new ICU placeholder before an existing plural', () => {
    const previousValue = 'You have {count, plural, one {# file} other {# files}}.';
    const nextValue = '{user}, you have {count, plural, one {# file} other {# files}}.';

    expect(
      preservesProtectedTextTokenStructure({
        previousValue,
        previousTokens: extractIcuProtectedTextTokens(previousValue),
        nextValue,
        mode: 'icu',
      }),
    ).toBe(true);
  });

  it('rejects changing an existing ICU placeholder', () => {
    const previousValue = 'Hello {name}.';
    const nextValue = 'Hello {price}.';

    expect(
      preservesProtectedTextTokenStructure({
        previousValue,
        previousTokens: extractIcuProtectedTextTokens(previousValue),
        nextValue,
        mode: 'icu',
      }),
    ).toBe(false);
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

  it('allows newly added ICU placeholders', () => {
    expect(
      preservesProtectedTextTokenStructure({
        previousValue: 'Hello world.',
        previousTokens: [],
        nextValue: 'Hello {name}.',
        mode: 'icu',
      }),
    ).toBe(true);
  });
});
