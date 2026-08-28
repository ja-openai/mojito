// @vitest-environment node

import * as mf2Core from '@mojito-mf2/core';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MAX_MF2_MESSAGE_CODE_UNITS, MAX_MF2_SYNTAX_BRACES, parseMf2 } from './model';
import { mf2TranslationErrorCount } from './translationValidation';

const source = `.input {$count :number}
{{You have {$count} files.}}`;

afterEach(() => vi.restoreAllMocks());

describe('mf2TranslationErrorCount', () => {
  it('accepts a compatible target-only locale selector', () => {
    expect(
      mf2TranslationErrorCount({
        locale: 'fr',
        source,
        target: `.input {$count :number}
.match $count
one {{Vous avez {$count} fichier.}}
* {{Vous avez {$count} fichiers.}}`,
      }),
    ).toBe(0);
  });

  it('reports parser and source-contract errors', () => {
    expect(
      mf2TranslationErrorCount({
        locale: 'fr',
        source,
        target: `.input {$count :number}
{{Vous avez {$rogue} fichiers.}}`,
      }),
    ).toBeGreaterThan(0);
    expect(
      mf2TranslationErrorCount({
        locale: 'fr',
        source,
        target: '.match $count',
      }),
    ).toBeGreaterThan(0);
  });

  it('blocks a valid target when the MF2 source is malformed', () => {
    expect(
      mf2TranslationErrorCount({
        locale: 'fr',
        source: '.input {$count :number',
        target: 'Vous avez des fichiers.',
      }),
    ).toBeGreaterThan(0);
  });

  it('rejects oversized input before parsing it', () => {
    const parseToModelSpy = vi.spyOn(mf2Core, 'parseToModel');
    const errorCount = mf2TranslationErrorCount({
      locale: 'fr',
      source: 'x'.repeat(5_000_028),
      target: 'x'.repeat(MAX_MF2_MESSAGE_CODE_UNITS + 1),
    });

    expect(errorCount).toBe(2);
    expect(parseMf2('x'.repeat(MAX_MF2_MESSAGE_CODE_UNITS + 1), {}, 'fr').diagnostics).toEqual([
      expect.objectContaining({ code: 'input-too-large', severity: 'error' }),
    ]);
    expect(parseToModelSpy).not.toHaveBeenCalled();
  });

  it('rejects structurally excessive input before parsing it', () => {
    const parseToModelSpy = vi.spyOn(mf2Core, 'parseToModel');
    const excessiveInput = '{'.repeat(MAX_MF2_SYNTAX_BRACES + 1);
    const errorCount = mf2TranslationErrorCount({
      locale: 'fr',
      source: excessiveInput,
      target: excessiveInput,
    });

    expect(errorCount).toBe(2);
    expect(parseMf2(excessiveInput, {}, 'fr').diagnostics).toEqual([
      expect.objectContaining({ code: 'input-too-complex', severity: 'error' }),
    ]);
    expect(parseToModelSpy).not.toHaveBeenCalled();
  });
});
