import { describe, expect, it } from 'vitest';

import { mf2ProseMirrorDocFromPattern, mf2ProseMirrorPatternFromDoc } from './Mf2ProseMirrorEditor';

describe('Mf2ProseMirrorEditor pattern conversion', () => {
  it.each([
    ['Bon\\{jour', 'Bon{jour'],
    ['Fin\\}', 'Fin}'],
    ['C:\\\\temp', 'C:\\temp'],
  ])('renders escaped pattern text without exposing syntax escapes', (pattern, visibleText) => {
    const doc = mf2ProseMirrorDocFromPattern(pattern);

    expect(doc.textContent).toBe(visibleText);
    expect(mf2ProseMirrorPatternFromDoc(doc)).toBe(pattern);
  });

  it('keeps placeholder expressions atomic while round-tripping literal braces', () => {
    const pattern = 'Use \\{literal\\} with {$name}.';
    const doc = mf2ProseMirrorDocFromPattern(pattern);

    expect(doc.toJSON()).toMatchObject({
      content: [
        {
          content: [
            { text: 'Use {literal} with ', type: 'text' },
            { attrs: { name: 'name', source: '{$name}' }, type: 'placeholder' },
            { text: '.', type: 'text' },
          ],
          type: 'paragraph',
        },
      ],
      type: 'doc',
    });
    expect(mf2ProseMirrorPatternFromDoc(doc)).toBe(pattern);
  });

  it('protects every braced MF2 construct without escaping its source', () => {
    const pattern =
      'Tap {#link href=$url}here{/link}. {#br/} {|literal|} {:function} {$name :string}.';
    const doc = mf2ProseMirrorDocFromPattern(pattern);

    expect(doc.toJSON()).toEqual({
      content: [
        {
          content: [
            { text: 'Tap ', type: 'text' },
            { attrs: { source: '{#link href=$url}' }, type: 'syntax' },
            { text: 'here', type: 'text' },
            { attrs: { source: '{/link}' }, type: 'syntax' },
            { text: '. ', type: 'text' },
            { attrs: { source: '{#br/}' }, type: 'syntax' },
            { text: ' ', type: 'text' },
            { attrs: { source: '{|literal|}' }, type: 'syntax' },
            { text: ' ', type: 'text' },
            { attrs: { source: '{:function}' }, type: 'syntax' },
            { text: ' ', type: 'text' },
            {
              attrs: { name: 'name', source: '{$name :string}' },
              type: 'placeholder',
            },
            { text: '.', type: 'text' },
          ],
          type: 'paragraph',
        },
      ],
      type: 'doc',
    });
    expect(doc.textContent).toBe(pattern);
    expect(mf2ProseMirrorPatternFromDoc(doc)).toBe(pattern);
  });

  it('keeps escaped syntax lookalikes as editable literal text', () => {
    const pattern = 'Literal: \\{$name\\} and \\{#link\\}.';
    const doc = mf2ProseMirrorDocFromPattern(pattern);

    expect(doc.toJSON()).toEqual({
      content: [
        {
          content: [{ text: 'Literal: {$name} and {#link}.', type: 'text' }],
          type: 'paragraph',
        },
      ],
      type: 'doc',
    });
    expect(mf2ProseMirrorPatternFromDoc(doc)).toBe(pattern);
  });

  it('protects placeholders that include bidi isolation markers', () => {
    const pattern = '{\u2068$name\u2069}';
    const doc = mf2ProseMirrorDocFromPattern(pattern);

    expect(doc.toJSON()).toEqual({
      content: [
        {
          content: [
            {
              attrs: { name: 'name', source: pattern },
              type: 'placeholder',
            },
          ],
          type: 'paragraph',
        },
      ],
      type: 'doc',
    });
    expect(mf2ProseMirrorPatternFromDoc(doc)).toBe(pattern);
  });
});
