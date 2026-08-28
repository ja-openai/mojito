import { describe, expect, it } from 'vitest';

import { isMf2Message, isStrictMf2Source, normalizeMessageFormat } from './messageFormat';

describe('MF2 message format routing', () => {
  it('recognizes only strict MF2 declarations from source text', () => {
    expect(isStrictMf2Source('.input {$count :number}\n{{You have {$count} files.}}')).toBe(true);
    expect(isStrictMf2Source('\uFEFF.match $count\n* {{Files}}')).toBe(true);
    expect(isStrictMf2Source('{count, plural, one {File} other {Files}}')).toBe(false);
    expect(isStrictMf2Source('Documentation for .input {$count}.')).toBe(false);
    expect(isStrictMf2Source('Instructions:\n.input {$count}\nCopy that example.')).toBe(false);
  });

  it('normalizes explicit metadata', () => {
    expect(normalizeMessageFormat(' mf2 ')).toBe('MF2');
    expect(normalizeMessageFormat('icu')).toBeNull();
  });

  it('lets explicit non-MF2 metadata override the source fallback', () => {
    const source = '.input {$count :number}\n{{You have {$count} files.}}';
    expect(isMf2Message({ messageFormat: 'MF2', source })).toBe(true);
    expect(isMf2Message({ messageFormat: 'ICU', source })).toBe(false);
    expect(isMf2Message({ source })).toBe(true);
  });
});
