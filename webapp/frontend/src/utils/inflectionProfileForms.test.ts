import { describe, expect, it } from 'vitest';

import {
  createEmptyInflectionFormRow,
  formatInflectionFormRowsAsJson,
  parseInflectionFormRows,
  serializeInflectionFormRows,
  validateInflectionFormRows,
} from './inflectionProfileForms';

describe('inflectionProfileForms', () => {
  it('parses string form maps while preserving row order', () => {
    const rows = parseInflectionFormRows('{"bare.singular":"epee","definite.plural":"les epees"}');

    expect(rows.map((row) => [row.key, row.value])).toEqual([
      ['bare.singular', 'epee'],
      ['definite.plural', 'les epees'],
    ]);
  });

  it('rejects non-object and non-string form JSON', () => {
    expect(() => parseInflectionFormRows('[]')).toThrow('Forms JSON must be a JSON object.');
    expect(() => parseInflectionFormRows('{"bare.singular":1}')).toThrow(
      'Forms JSON.bare.singular must be a string.',
    );
  });

  it('validates form keys, duplicates, and blank text', () => {
    expect(validateInflectionFormRows([{ id: '1', key: '1.bad', value: 'value' }])).toBe(
      'Unsupported inflection form key: 1.bad.',
    );
    expect(
      validateInflectionFormRows([
        { id: '1', key: 'bare.singular', value: 'value' },
        { id: '2', key: 'bare.singular', value: 'other' },
      ]),
    ).toBe('Duplicate form key: bare.singular.');
    expect(validateInflectionFormRows([{ id: '1', key: 'bare.singular', value: ' ' }])).toBe(
      'Form bare.singular must have text.',
    );
  });

  it('serializes valid rows as the compact backend string map', () => {
    expect(
      serializeInflectionFormRows([
        { id: '1', key: 'bare.singular', value: 'epee' },
        { id: '2', key: 'count.other', value: '{$count} epees' },
      ]),
    ).toBe('{"bare.singular":"epee","count.other":"{$count} epees"}');
  });

  it('suggests the first missing common form key for new rows', () => {
    const row = createEmptyInflectionFormRow([
      { id: '1', key: 'bare.singular', value: 'epee' },
      { id: '2', key: 'bare.plural', value: 'epees' },
    ]);

    expect(row.key).toBe('definite.singular');
    expect(formatInflectionFormRowsAsJson([row])).toBe('{\n  "definite.singular": ""\n}');
  });
});
