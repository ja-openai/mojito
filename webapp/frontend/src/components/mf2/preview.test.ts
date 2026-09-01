import { describe, expect, it } from 'vitest';

import { sourceLiteralPreview } from './model';
import { mf2PatternPreview } from './preview';

describe('mf2PatternPreview', () => {
  it('protects a typed variable as one compact placeholder', () => {
    const preview = mf2PatternPreview('Use \\{literal\\} with {$name :string}.');

    expect(preview.value).toBe('Use {literal} with {$name :string}.');
    expect(preview.protectedTokens).toEqual([
      {
        displayText: 'name',
        end: 34,
        kind: 'mf2-placeholder',
        label: 'MF2 variable name',
        start: 19,
      },
    ]);
    expect(
      preview.value.slice(preview.protectedTokens[0].start, preview.protectedTokens[0].end),
    ).toBe('{$name :string}');
  });

  it('keeps escaped placeholder lookalikes as literal text', () => {
    const preview = mf2PatternPreview('Literal \\{$name\\}; real {$name}.');

    expect(preview.value).toBe('Literal {$name}; real {$name}.');
    expect(preview.protectedTokens).toHaveLength(1);
    expect(
      preview.value.slice(preview.protectedTokens[0].start, preview.protectedTokens[0].end),
    ).toBe('{$name}');
    expect(preview.protectedTokens[0].start).toBe(preview.value.lastIndexOf('{$name}'));
  });

  it('decodes literal backslashes without shifting placeholder ranges', () => {
    const preview = mf2PatternPreview('C:\\\\temp {$name}');

    expect(preview.value).toBe('C:\\temp {$name}');
    expect(
      preview.value.slice(preview.protectedTokens[0].start, preview.protectedTokens[0].end),
    ).toBe('{$name}');
  });

  it('protects MF2 markup and expressions while leaving prose as text', () => {
    const preview = mf2PatternPreview(
      'Tap {#link href=$url}here{/link}. {#br/} {|literal|} {:function}.',
    );

    expect(preview.protectedTokens.map((token) => token.kind)).toEqual([
      'mf2-syntax',
      'mf2-syntax',
      'mf2-syntax',
      'mf2-syntax',
      'mf2-syntax',
    ]);
    expect(
      preview.protectedTokens.map((token) => preview.value.slice(token.start, token.end)),
    ).toEqual(['{#link href=$url}', '{/link}', '{#br/}', '{|literal|}', '{:function}']);
    expect(preview.value).toContain('here');
  });

  it('renders the wildcard form from a selected message', () => {
    const pattern = sourceLiteralPreview(`.input {$count :number}
.match $count
one {{One file}}
* {{Files: {$count}}}`);

    const preview = mf2PatternPreview(pattern);

    expect(preview.value).toBe('Files: {$count}');
    expect(preview.protectedTokens).toHaveLength(1);
    expect(preview.protectedTokens[0]).toMatchObject({
      displayText: 'count',
      kind: 'mf2-placeholder',
    });
  });
});
