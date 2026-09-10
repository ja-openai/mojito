import { describe, expect, it } from 'vitest';

import { getPlaceholderCompletion, getSourcePlaceholderCandidates } from './placeholderCompletion';
import { extractIcuAndHtmlProtectedTextTokens } from './protectedTextTokens';

describe('getSourcePlaceholderCandidates', () => {
  it('preserves exact supported placeholder spelling and source order, deduplicating repeats', () => {
    expect(
      getSourcePlaceholderCandidates(
        'Hi %1$s, { name }, {price, number, ::currency/USD}: %(total)03d, %#@files@, { name }.',
      ),
    ).toEqual([
      { text: '%1$s', kind: 'platform-placeholder' },
      { text: '{ name }', kind: 'icu-placeholder' },
      { text: '{price, number, ::currency/USD}', kind: 'icu-placeholder' },
      { text: '%(total)03d', kind: 'platform-placeholder' },
      { text: '%#@files@', kind: 'platform-placeholder' },
    ]);
  });

  it('offers leaf arguments from nested forms, excluding skeletons, pound signs, and HTML', () => {
    expect(
      getSourcePlaceholderCandidates(
        '<b>{count, plural, one {# {name}} other {{gender, select, other {{price, number}}}}}</b>',
      ),
    ).toEqual([
      { text: '{name}', kind: 'icu-placeholder' },
      { text: '{price, number}', kind: 'icu-placeholder' },
    ]);
  });

  it('excludes ICU quoted literals and escaped platform placeholders', () => {
    expect(getSourcePlaceholderCandidates("'{literal}' {name} %%s %%%d")).toEqual([
      { text: '{name}', kind: 'icu-placeholder' },
      { text: '%d', kind: 'platform-placeholder' },
    ]);
  });

  it('fails closed for malformed ICU while still recognizing platform placeholders', () => {
    expect(getSourcePlaceholderCandidates('Broken {name and %1$s')).toEqual([
      { text: '%1$s', kind: 'platform-placeholder' },
    ]);
  });

  it('does not offer printf-looking text from inside an ICU formatter as a separate placeholder', () => {
    expect(getSourcePlaceholderCandidates('{when, date, %d} and %1$s')).toEqual([
      { text: '{when, date, %d}', kind: 'icu-placeholder' },
      { text: '%1$s', kind: 'platform-placeholder' },
    ]);
  });

  it('does not invent placeholders for prose, percentages, or unsupported syntax', () => {
    expect(getSourcePlaceholderCandidates('Done: 100%. Hello [[name]].')).toEqual([]);
    expect(getSourcePlaceholderCandidates('100%complete, 50%off; %1$s')).toEqual([
      { text: '%1$s', kind: 'platform-placeholder' },
    ]);
  });
});

describe('getPlaceholderCompletion', () => {
  const candidates = getSourcePlaceholderCandidates(
    '{name} {number, number} { price } %1$s %2$d %(total)03d %#@files@',
  );

  function complete(value: string, caret = value.length) {
    return getPlaceholderCompletion(value, { start: caret, end: caret }, candidates);
  }

  it('offers source ICU placeholders after a brace and filters by argument prefix', () => {
    expect(complete('Hello {')).toEqual({
      from: 6,
      to: 7,
      options: candidates.slice(0, 3),
    });
    expect(complete('Hello {N')).toEqual({
      from: 6,
      to: 8,
      options: candidates.slice(0, 2),
    });
    expect(complete('{pr')?.options).toEqual([candidates[2]]);
    expect(complete('{ pr')?.options).toEqual([candidates[2]]);
    expect(complete('{unknown')).toBeNull();
  });

  it('replaces an adjacent argument closing brace while retaining following text', () => {
    const value = 'Hello {na}!';
    const result = complete(value, 9)!;
    expect(result).toEqual({ from: 6, to: 10, options: [candidates[0]] });
    expect(`${value.slice(0, result.from)}${result.options[0].text}${value.slice(result.to)}`).toBe(
      'Hello {name}!',
    );
  });

  it('preserves plural closing syntax when completing an argument inside a form', () => {
    const value = '{count, plural, other {Hello {na}}';
    const caret = value.indexOf('{na') + 3;
    const closingSyntax = {
      start: caret,
      end: value.length,
      kind: 'icu-syntax' as const,
      label: 'ICU plural syntax',
    };
    const result = getPlaceholderCompletion(value, { start: caret, end: caret }, candidates, [
      closingSyntax,
    ]);
    expect(result).toEqual({ from: caret - 3, to: caret, options: [candidates[0]] });
    expect(complete(value, caret)?.to).toBe(caret);
  });

  it('does not mistake a plural or select body opening brace for an argument trigger', () => {
    expect(complete('{count, plural, other {}}', 23)).toBeNull();
    expect(complete('{gender, select, other {}}', 24)).toBeNull();
  });

  it('uses ICU apostrophe rules for quoted literals, escaped apostrophes, and contractions', () => {
    expect(complete("Literal '{na")).toBeNull();
    expect(complete("Literal '}' and {na")).not.toBeNull();
    expect(complete("''{na")).not.toBeNull();
    expect(complete("Don't remove {na")).not.toBeNull();
    expect(complete("'# {na")).not.toBeNull();
    const quotedPlural = "{count, plural, other {'# {na}}";
    expect(complete(quotedPlural, quotedPlural.indexOf('{na') + 3)).toBeNull();
  });

  it('suppresses ICU completion when another part of the target is malformed', () => {
    expect(complete('Broken {unfinished, and {na')).toBeNull();
    expect(complete('{name} and {na')).not.toBeNull();
  });

  it('offers only matching platform placeholders and retains their exact syntax', () => {
    expect(complete('Hello %')?.options).toEqual(candidates.slice(3));
    expect(complete('Hello %1$')?.options).toEqual([candidates[3]]);
    expect(complete('Total %(to')?.options).toEqual([candidates[5]]);
    expect(complete('Files %#@fi')?.options).toEqual([candidates[6]]);
    expect(complete('Broken { but %1$')?.options).toEqual([candidates[3]]);
    expect(complete('100% done')).toBeNull();
    expect(complete('100%')).toBeNull();
    expect(complete('99.5%')).toBeNull();
    expect(complete('١٠٠%')).toBeNull();
  });

  it('honors escaped percent runs', () => {
    expect(complete('%%')).toBeNull();
    expect(complete('%%1$')).toBeNull();
    expect(complete('%%%%')).toBeNull();
    expect(complete('%%%1$')?.options).toEqual([candidates[3]]);
  });

  it('does not autocomplete completed placeholders or the middle of a suffix', () => {
    for (const value of ['{name}', '%1$s', '%(total)03d', '%#@files@']) {
      expect(complete(value)).toBeNull();
    }
    expect(complete('{name}', 3)).toBeNull();
    expect(complete('{name', 3)).toBeNull();
    expect(complete('%1$s', 2)).toBeNull();
    expect(complete('%%', 1)).toBeNull();
    expect(complete('%(total)03d', 5)).toBeNull();
    expect(complete('%(total)03d', 7)).toBeNull();
    expect(complete('%*d', 1)).toBeNull();
  });

  it('does not offer completions inside protected placeholders, HTML, or syntax', () => {
    for (const [value, caret] of [
      ['{name}', 5],
      ['%1$s', 1],
      ['<a title="{na">text</a>', 13],
      ['{count, plural, other {}}', 23],
    ] as const) {
      expect(
        getPlaceholderCompletion(value, { start: caret, end: caret }, candidates, [
          ...extractIcuAndHtmlProtectedTextTokens(value),
        ]),
      ).toBeNull();
    }
  });

  it('requires a collapsed valid selection and source candidates', () => {
    expect(getPlaceholderCompletion('{na', null, candidates)).toBeNull();
    expect(getPlaceholderCompletion('{na', { start: 1, end: 3 }, candidates)).toBeNull();
    expect(getPlaceholderCompletion('{na', { start: -1, end: -1 }, candidates)).toBeNull();
    expect(getPlaceholderCompletion('{na', { start: 4, end: 4 }, candidates)).toBeNull();
    expect(getPlaceholderCompletion('{na', { start: 3, end: 3 }, [])).toBeNull();
  });

  it('keeps all matching source placeholders available', () => {
    const manyCandidates = getSourcePlaceholderCandidates(
      Array.from({ length: 10 }, (_, index) => `{name${index}}`).join(' '),
    );
    expect(
      getPlaceholderCompletion('{', { start: 1, end: 1 }, manyCandidates)?.options,
    ).toHaveLength(10);
  });
});
