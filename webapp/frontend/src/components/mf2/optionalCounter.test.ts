// @vitest-environment node

import { type MF2Message, type MF2Pattern, parseToModel } from '@mojito-mf2/core';
import { describe, expect, it } from 'vitest';

import { isOptionalCounterExpression, optionalCounterNames } from './optionalCounter';

function parse(source: string): MF2Message {
  const parsed = parseToModel(source);
  expect(parsed.diagnostics).toEqual([]);
  expect(parsed.model).not.toBeNull();
  return parsed.model as MF2Message;
}

function optional(key: string, locale: string, annotation = 'integer') {
  const model = parse(`.input {$count :${annotation}}
.match $count
${key === '*' ? '' : `${key} {{Specific form.}}\n`}* {{Fallback: {$count}.}}`);
  if (model.type !== 'select') throw new Error('Expected select message');
  return [...optionalCounterNames(model, model.variants[0].keys, locale)];
}

it('keeps a local counter with inherited formatting protected', () => {
  const model = parse(`.input {$n :number minimumFractionDigits=2}
.local $count = {$n :number}
.match $count
1 {{Count: {$count}}}
* {{Count: {$count}}}`);
  expect(optionalCounterNames(model, [{ type: 'literal', value: '1' }], 'en')).toEqual(new Set());
});

describe('optional MF2 counters', () => {
  it.each(['zero', 'one', 'two'])('allows the singleton Arabic %s form', (key) => {
    expect(optional(key, 'ar')).toEqual(['count']);
  });

  it('allows English cardinal one for numeric and integer inputs', () => {
    expect(optional('one', 'en', 'number')).toEqual(['count']);
    expect(optional('one', 'en')).toEqual(['count']);
  });

  it.each([
    ['one', 'ru'],
    ['few', 'ru'],
    ['one', 'fr'],
    ['zero', 'lv'],
    ['one', 'lv'],
    ['few', 'ar'],
    ['many', 'ar'],
    ['other', 'en'],
    ['one', 'da'],
    ['one', 'he'],
  ])('preserves a count that varies within %s in %s', (key, locale) => {
    expect(optional(key, locale, 'number')).toEqual([]);
  });

  it('uses canonical locale fallbacks and the Portuguese regional override', () => {
    expect(optional('one', 'ar_EG')).toEqual(['count']);
    expect(optional('one', 'en-GB-u-nu-latn')).toEqual(['count']);
    expect(optional('one', 'pt_PT', 'number')).toEqual(['count']);
    expect(optional('one', 'pt-BR', 'number')).toEqual([]);
    expect(optional('one', 'zz')).toEqual([]);
  });

  it.each(['0', '1', '2', '42', '-1', '1.5', '|1|'])('allows exact numeric key %s', (key) => {
    expect(optional(key, 'ru', 'number')).toEqual(['count']);
    expect(optional(key, 'ru', 'number select=exact')).toEqual(['count']);
  });

  it('keeps numeric-looking string selectors and literal stars protected', () => {
    expect(optional('1', 'en', 'string')).toEqual([]);
    expect(optional('one', 'en', 'string')).toEqual([]);
    expect(optional('|*|', 'en')).toEqual([]);
    expect(optional('*', 'en')).toEqual([]);
  });

  it('keeps ordinal categories protected while allowing exact ordinal numbers', () => {
    expect(optional('one', 'en', 'integer select=ordinal')).toEqual([]);
    expect(optional('two', 'en', 'integer select=ordinal')).toEqual([]);
    expect(optional('1', 'en', 'integer select=ordinal')).toEqual(['count']);
    expect(optional('one', 'en', 'integer select=exact')).toEqual([]);
  });

  it.each([
    'offset subtract=1',
    'ns:number',
    'number select=$mode',
    'number select=unknown',
    'number maximumFractionDigits=0',
    'number maximumFractionDigits=$precision',
  ])('keeps custom or configured selector %s protected', (annotation) => {
    expect(optional('1', 'en', annotation)).toEqual([]);
    expect(optional('one', 'en', annotation)).toEqual([]);
  });

  it('only makes the counter for each fixed selector optional', () => {
    const model = parse(`.input {$numLikes :integer}
.input {$numShares :integer}
.match $numLikes $numShares
0 one {{No likes; shared once by {$name}.}}
* * {{{$numLikes} likes and {$numShares} shares by {$name}.}}`);
    if (model.type !== 'select') throw new Error('Expected select message');
    expect([...optionalCounterNames(model, model.variants[0].keys, 'ar')]).toEqual([
      'numLikes',
      'numShares',
    ]);
    expect([
      ...optionalCounterNames(model, [{ type: '*' }, { type: 'literal', value: 'one' }], 'ar'),
    ]).toEqual(['numShares']);
    expect([...optionalCounterNames(model, model.variants[1].keys, 'ar')]).toEqual([]);
  });

  it('never exempts expressions in a message without selectors', () => {
    const model = parse('.input {$count :number}\n{{{$count} items.}}');
    expect([...optionalCounterNames(model, [{ type: 'literal', value: 'one' }], 'en')]).toEqual([]);
  });
});

describe('optional counter expressions', () => {
  const names = new Set(['count']);

  it('only exempts the bare fixed counter expression', () => {
    const model = parse('{$count} {$name} {$count :number} {$count @id=count} {#count/}');
    if (model.type !== 'message') throw new Error('Expected pattern message');
    const parts = model.pattern.filter((part) => typeof part !== 'string');
    expect(parts.map((part) => isOptionalCounterExpression(part, names))).toEqual([
      true,
      false,
      false,
      false,
      false,
    ]);
    expect(isOptionalCounterExpression('Count', names)).toBe(false);
    const literal: MF2Pattern[number] = {
      type: 'expression',
      arg: { type: 'literal', value: '1' },
    };
    expect(isOptionalCounterExpression(literal, names)).toBe(false);
  });
});
