import { describe, expect, it } from 'vitest';

import {
  applyMf2Completion,
  getCompletionOptions,
  getLiteralSourcePlaceholderNames,
  getMf2DeclarationPlaceholderNames,
  getMf2DiagnosticsForPlaceholders,
  getNextMf2Completion,
  getSourcePlaceholderNames,
} from './mf2TextModel';

describe('mf2TextModel', () => {
  it('extracts unique source placeholders in source order', () => {
    expect(
      getSourcePlaceholderNames(
        '.input {$name :string}\n.input {$count :number}\n{{Hello {$name} {$count}}}',
      ),
    ).toEqual(['name', 'count']);
  });

  it('keeps required target placeholders limited to literal source text', () => {
    expect(
      getLiteralSourcePlaceholderNames(`.input {$count :number}
.match $count
one {{One file}}
* {{Files for {$name}}}`),
    ).toEqual(['name']);
  });

  it('extracts declaration placeholders without reading target body variables', () => {
    expect(
      getMf2DeclarationPlaceholderNames(
        '.input {$count :number}\n.local {$name = :string}\n{{Hello {$unknown}}}',
      ),
    ).toEqual(['count', 'name']);
  });

  it('filters completion options by prefix and caps the list', () => {
    expect(
      getCompletionOptions(
        ['name', 'namespace', 'note', 'number', 'nickname', 'node', 'next'],
        'n',
      ),
    ).toEqual(['name', 'namespace', 'note', 'number', 'nickname', 'node']);
    expect(getCompletionOptions(['name', 'count'], 'co')).toEqual(['count']);
  });

  it('builds completion ranges from brace and dollar triggers', () => {
    expect(
      getNextMf2Completion({
        disabled: false,
        placeholderNames: ['name', 'count'],
        selectionEnd: 4,
        selectionStart: 4,
        text: '{$na',
      }),
    ).toEqual({ from: 0, query: 'na', selectedIndex: 0, to: 4 });

    expect(
      getNextMf2Completion({
        disabled: false,
        placeholderNames: ['name', 'count'],
        selectionEnd: 9,
        selectionStart: 9,
        text: 'Hello $na',
      }),
    ).toEqual({ from: 6, query: 'na', selectedIndex: 0, to: 9 });
  });

  it('completes a brace-prefixed placeholder from source declarations', () => {
    const source = '.input {$count :number}\n.input {$customerName :string}\n{{Hello {$count}}}';
    const placeholderNames = getMf2DeclarationPlaceholderNames(source);
    const completion = getNextMf2Completion({
      disabled: false,
      placeholderNames,
      selectionEnd: 'Bonjour {c'.length,
      selectionStart: 'Bonjour {c'.length,
      text: 'Bonjour {c',
    });

    expect(completion).toEqual({
      from: 'Bonjour '.length,
      query: 'c',
      selectedIndex: 0,
      to: 'Bonjour {c'.length,
    });
    expect(getCompletionOptions(placeholderNames, completion?.query ?? '')).toEqual([
      'count',
      'customerName',
    ]);
    expect(applyMf2Completion('Bonjour {c', completion!, 'count')).toEqual({
      nextSelection: 'Bonjour {$count}'.length,
      nextValue: 'Bonjour {$count}',
    });
  });

  it('does not complete when editing is disabled or the selection is not collapsed', () => {
    expect(
      getNextMf2Completion({
        disabled: true,
        placeholderNames: ['name'],
        selectionEnd: 4,
        selectionStart: 4,
        text: '{$na',
      }),
    ).toBeNull();
    expect(
      getNextMf2Completion({
        disabled: false,
        placeholderNames: ['name'],
        selectionEnd: 4,
        selectionStart: 1,
        text: '{$na',
      }),
    ).toBeNull();
  });

  it('applies completion while consuming an existing closing brace', () => {
    expect(
      applyMf2Completion(
        'Bonjour {$na}',
        { from: 8, query: 'na', selectedIndex: 0, to: 12 },
        'name',
      ),
    ).toEqual({
      nextSelection: 15,
      nextValue: 'Bonjour {$name}',
    });
  });

  it('surfaces placeholder and brace diagnostics', () => {
    expect(
      getMf2DiagnosticsForPlaceholders(['name', 'count'], ['name', 'count'], '{{Salut {$unknown}}'),
    ).toEqual([
      { severity: 'error', message: 'MF2 syntax: add the missing closing brace.' },
      { severity: 'warning', message: 'Unknown source placeholder {$unknown}.' },
      { severity: 'warning', message: 'Missing source placeholder {$name}, {$count}.' },
    ]);
  });
});
