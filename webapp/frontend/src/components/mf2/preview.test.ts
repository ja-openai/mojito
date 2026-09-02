import { describe, expect, it } from 'vitest';

import { MAX_MF2_MESSAGE_CODE_UNITS } from './model';
import { mf2DocumentPreview, mf2PatternPreview } from './preview';

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
});

describe('mf2DocumentPreview', () => {
  it('shows the complete serialized document with protected variables', () => {
    const source = `.input {$count :number}
{{You have {$count} files.}}`;
    const preview = mf2DocumentPreview(source);

    expect(preview.value).toBe(source);
    expect(preview.protectedTokens).toHaveLength(2);
    expect(
      preview.protectedTokens.map((token) => preview.value.slice(token.start, token.end)),
    ).toEqual(['{$count :number}', '{$count}']);
    expect(preview.protectedTokens).toEqual([
      expect.objectContaining({
        displayText: '{$count :number}',
        kind: 'mf2-placeholder',
      }),
      expect.objectContaining({
        displayText: '{$count}',
        kind: 'mf2-placeholder',
      }),
    ]);
  });

  it('keeps every selected-message form in the serialized preview', () => {
    const source = `.input {$count :number}
.match $count
one {{One file}}
many {{Many files}}
other {{Other files}}
* {{Files: {$count}}}`;
    const preview = mf2DocumentPreview(source);

    expect(preview.value).toBe(source);
    expect(preview.value).toContain('.match $count');
    expect(preview.value).toContain('one {{One file}}');
    expect(preview.value).toContain('many {{Many files}}');
    expect(preview.value).toContain('other {{Other files}}');
    expect(preview.value).toContain('* {{Files: {$count}}}');
  });

  it('shows malformed input as the complete raw serialization', () => {
    const source = `.input {$status :string}
.match $status
active {{Active}`;
    const preview = mf2DocumentPreview(source);

    expect(preview.value).toBe(source);
    expect(preview.protectedTokens[0]).toMatchObject({
      displayText: '{$status :string}',
      kind: 'mf2-placeholder',
    });
  });

  it('does not scan oversized raw fallbacks for preview tokens', () => {
    const source = `{$name}${'x'.repeat(MAX_MF2_MESSAGE_CODE_UNITS)}`;
    const preview = mf2DocumentPreview(source);

    expect(preview.value).toBe(source);
    expect(preview.protectedTokens).toEqual([]);
  });
});
