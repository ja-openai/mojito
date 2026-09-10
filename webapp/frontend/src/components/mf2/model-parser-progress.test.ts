import { describe, expect, it } from 'vitest';

import { MAX_MF2_MESSAGE_CODE_UNITS, parseMf2 } from './model';

describe('MF2 parser progress', () => {
  it('rejects a single opening brace after a variant below the editor size limit', () => {
    const source = '.input {$x :string} .match $x * {{ok}} {x}';
    expect(source.length).toBeLessThan(MAX_MF2_MESSAGE_CODE_UNITS);
    const result = parseMf2(source, {}, 'en');
    expect(result.model).toBeNull();
    expect(result.diagnostics.map(({ code }) => code)).toContain('invalid-variant-key');
  });
});
