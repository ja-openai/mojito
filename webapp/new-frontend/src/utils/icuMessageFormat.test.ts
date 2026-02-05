import { describe, expect, it } from 'vitest';

import {
  buildIcuExampleValueSets,
  mergeValues,
  parseIcuMessage,
  renderIcuWithValues,
} from './icuMessageFormat';

describe('parseIcuMessage', () => {
  it('infers parameters and option values from plural/select blocks', () => {
    const message =
      'Hello {name}, you have {count, plural, one {# item} other {# items}} in {gender, select, male {his} female {her} other {their}} cart.';

    const parsed = parseIcuMessage(message);

    expect(parsed.parameters.map((parameter) => parameter.name)).toEqual(['name', 'count', 'gender']);

    const count = parsed.parameters.find((parameter) => parameter.name === 'count');
    expect(count?.kinds).toContain('plural');
    expect(count?.pluralOptions).toEqual(['one', 'other']);

    const gender = parsed.parameters.find((parameter) => parameter.name === 'gender');
    expect(gender?.kinds).toContain('select');
    expect(gender?.selectOptions).toEqual(['male', 'female', 'other']);
  });

  it('throws for invalid ICU messages', () => {
    expect(() => parseIcuMessage('Hello {name')).toThrow();
  });
});

describe('buildIcuExampleValueSets', () => {
  it('creates multiple useful presets', () => {
    const parsed = parseIcuMessage(
      '{name} invited {guestCount, plural, one {# guest} other {# guests}} to {city}.',
    );
    const presets = buildIcuExampleValueSets(parsed.parameters);

    expect(presets.length).toBeGreaterThanOrEqual(3);
    expect(presets[0]?.values.name).toBeTypeOf('string');
    expect(presets.some((preset) => preset.values.guestCount === '0')).toBe(true);
  });
});

describe('renderIcuWithValues', () => {
  it('formats with coerced values', () => {
    const parsed = parseIcuMessage('Hello {name}, count={count, number}.');
    const rendered = renderIcuWithValues(
      parsed.ast,
      parsed.parameters,
      {
        name: 'Alex',
        count: '3',
      },
      'en',
    );

    expect(rendered).toContain('Hello Alex');
    expect(rendered).toContain('count=3');
  });
});

describe('mergeValues', () => {
  it('only merges keys that exist in inferred parameters', () => {
    const parsed = parseIcuMessage('Hello {name}, count={count, number}.');
    const merged = mergeValues(
      { name: 'Alex', count: '1' },
      { name: 'Sam', unused: 'ignored', count: '2' },
      parsed.parameters,
    );

    expect(merged).toEqual({ name: 'Sam', count: '2' });
  });
});
