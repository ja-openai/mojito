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
  it('protects variables in a simple unquoted message', () => {
    const source = 'Hello {$name}.';
    const preview = mf2DocumentPreview(source);

    expect(preview.protectedTokens).toEqual([
      expect.objectContaining({
        displayText: '{$name}',
        kind: 'mf2-placeholder',
        label: 'MF2 variable name',
      }),
    ]);
  });

  it('protects only variables rendered inside the serialized message pattern', () => {
    const source = `.input {$count :number}
{{You have {$count} files.}}`;
    const preview = mf2DocumentPreview(source);

    expect(preview.value).toBe(source);
    expect(preview.protectedTokens).toHaveLength(1);
    expect(
      preview.protectedTokens.map((token) => preview.value.slice(token.start, token.end)),
    ).toEqual(['{$count}']);
    expect(preview.protectedTokens).toEqual([
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
    const fallback = preview.protectedTokens.find(
      (token) => token.label === 'MF2 fallback selector',
    );
    expect(fallback).toMatchObject({ displayText: 'fallback', kind: 'mf2-syntax' });
    expect(preview.value.slice(fallback?.start, fallback?.end)).toBe('*');
    expect(
      preview.protectedTokens.some(
        (token) => preview.value.slice(token.start, token.end) === '{$count :number}',
      ),
    ).toBe(false);
  });

  it('ignores variables in local declarations and quoted variant keys', () => {
    const source = `.local $copy = {$source}
.match $copy
|{{not a pattern}}| {{Use {$copy}}}
* {{Fallback {$copy}}}`;
    const preview = mf2DocumentPreview(source);
    const variables = preview.protectedTokens.filter((token) => token.kind === 'mf2-placeholder');

    expect(variables).toHaveLength(2);
    expect(variables.map((token) => preview.value.slice(token.start, token.end))).toEqual([
      '{$copy}',
      '{$copy}',
    ]);
    expect(variables.every((token) => token.start > source.indexOf('.match'))).toBe(true);
  });

  it('finds patterns after pipes and backslashes in bare variant keys', () => {
    const source = String.raw`.input {$status :string}
.match $status
foo|bar {{Pipe {$name}}}
foo\{{Backslash {$name}}}
* {{Other {$name}}}`;
    const preview = mf2DocumentPreview(source);
    const variables = preview.protectedTokens.filter((token) => token.kind === 'mf2-placeholder');

    expect(variables).toHaveLength(3);
    expect(variables.map((token) => preview.value.slice(token.start, token.end))).toEqual([
      '{$name}',
      '{$name}',
      '{$name}',
    ]);
  });

  it('treats later directive-like lines in a simple message as pattern text', () => {
    const source = `Intro {$before}
.input {$name}
.match text {$after}`;
    const preview = mf2DocumentPreview(source);

    expect(
      preview.protectedTokens.map((token) => preview.value.slice(token.start, token.end)),
    ).toEqual(['{$before}', '{$name}', '{$after}']);
  });

  it('recognizes every MF2 bidi marker as leading syntax whitespace', () => {
    const source = `\u061c.input {$status :string}
\u061c.match $status
foo|bar {{Hi {$name}}}
* {{Other {$name}}}`;
    const preview = mf2DocumentPreview(source);
    const variables = preview.protectedTokens.filter((token) => token.kind === 'mf2-placeholder');

    expect(variables).toHaveLength(2);
    expect(variables.every((token) => token.label === 'MF2 variable name')).toBe(true);
    expect(preview.protectedTokens.some((token) => token.label === 'MF2 variable status')).toBe(
      false,
    );
  });

  it('does not treat a quoted literal star as a fallback selector', () => {
    const source = `.input {$kind :string}
.input {$count :number}
.match $kind $count
|*| one {{Literal star}}
| * | one {{Spaced literal star}}
|foo * bar| one {{Embedded literal star}}
|foo \\| * bar| one {{Escaped pipe and literal star}}
* * {{Fallback}}`;
    const preview = mf2DocumentPreview(source);
    const fallbacks = preview.protectedTokens.filter(
      (token) => token.label === 'MF2 fallback selector',
    );

    expect(fallbacks).toHaveLength(2);
    expect(fallbacks.map((token) => preview.value.slice(token.start, token.end))).toEqual([
      '*',
      '*',
    ]);
    expect(fallbacks.some((token) => token.start === source.indexOf('|*|') + 1)).toBe(false);
  });

  it('shows malformed input as the complete raw serialization', () => {
    const source = `.input {$status :string}
.match $status
active {{Active}`;
    const preview = mf2DocumentPreview(source);

    expect(preview.value).toBe(source);
    expect(preview.protectedTokens).toEqual([]);
  });

  it('does not scan oversized raw fallbacks for preview tokens', () => {
    const source = `{$name}${'x'.repeat(MAX_MF2_MESSAGE_CODE_UNITS)}`;
    const preview = mf2DocumentPreview(source);

    expect(preview.value).toBe(source);
    expect(preview.protectedTokens).toEqual([]);
  });
});
