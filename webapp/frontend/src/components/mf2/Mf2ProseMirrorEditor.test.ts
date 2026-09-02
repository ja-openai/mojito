import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act, createElement, createRef, type Ref } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  mf2ProseMirrorDocFromPattern,
  Mf2ProseMirrorEditor,
  type Mf2ProseMirrorEditorHandle,
  mf2ProseMirrorPatternFromDoc,
} from './Mf2ProseMirrorEditor';

function editorElement({
  marksMode,
  onChange = vi.fn(),
  pattern,
  placeholders = [],
  onSubmit = vi.fn(),
  ref,
}: {
  marksMode: 'all' | 'auto' | 'off';
  onChange?: (pattern: string) => void;
  pattern: string;
  placeholders?: string[];
  onSubmit?: () => void;
  ref?: Ref<Mf2ProseMirrorEditorHandle>;
}) {
  return createElement(Mf2ProseMirrorEditor, {
    ariaLabel: 'Target Message',
    direction: 'ltr',
    marksMode,
    minLines: 1,
    onChange,
    onNextForm: vi.fn(),
    onPreviousForm: vi.fn(),
    pattern,
    placeholderSources: {},
    placeholders,
    onSubmit,
    readOnly: false,
    ref,
  });
}

function placeCaret(element: HTMLElement, offset: number) {
  const text = element.querySelector('p')?.firstChild;
  if (!text) throw new Error('Expected the MF2 editor to contain a text node.');
  const range = document.createRange();
  range.setStart(text, offset);
  range.collapse(true);
  const selection = window.getSelection();
  selection?.removeAllRanges();
  selection?.addRange(range);
  document.dispatchEvent(new Event('selectionchange'));
}

function installRangeGeometryMock() {
  const getBoundingClientRect = Object.getOwnPropertyDescriptor(
    Range.prototype,
    'getBoundingClientRect',
  );
  const getClientRects = Object.getOwnPropertyDescriptor(Range.prototype, 'getClientRects');
  const scrollBy = Object.getOwnPropertyDescriptor(window, 'scrollBy');
  const rect = {
    bottom: 1,
    height: 1,
    left: 0,
    right: 1,
    top: 0,
    width: 1,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  } as DOMRect;
  const rects = {
    0: rect,
    length: 1,
    item: (index: number) => (index === 0 ? rect : null),
    [Symbol.iterator]: function* () {
      yield rect;
    },
  } as DOMRectList;

  Object.defineProperty(Range.prototype, 'getClientRects', {
    configurable: true,
    value: () => rects,
  });
  Object.defineProperty(Range.prototype, 'getBoundingClientRect', {
    configurable: true,
    value: () => rect,
  });
  Object.defineProperty(window, 'scrollBy', {
    configurable: true,
    value: vi.fn(),
  });

  return () => {
    restoreDescriptor(Range.prototype, 'getClientRects', getClientRects);
    restoreDescriptor(Range.prototype, 'getBoundingClientRect', getBoundingClientRect);
    restoreDescriptor(window, 'scrollBy', scrollBy);
  };
}

function restoreDescriptor(target: object, name: string, descriptor?: PropertyDescriptor) {
  if (descriptor) {
    Object.defineProperty(target, name, descriptor);
    return;
  }
  delete (target as Record<string, unknown>)[name];
}

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

  it('represents and preserves every line-break sequence as an inline hard break', () => {
    const pattern = 'Line one\r\nLine two\nUse {$name}\rLine four';
    const doc = mf2ProseMirrorDocFromPattern(pattern);

    expect(doc.toJSON()).toEqual({
      content: [
        {
          content: [
            { text: 'Line one', type: 'text' },
            { attrs: { raw: '\r\n' }, type: 'hardBreak' },
            { text: 'Line two', type: 'text' },
            { attrs: { raw: '\n' }, type: 'hardBreak' },
            { text: 'Use ', type: 'text' },
            { attrs: { name: 'name', source: '{$name}' }, type: 'placeholder' },
            { attrs: { raw: '\r' }, type: 'hardBreak' },
            { text: 'Line four', type: 'text' },
          ],
          type: 'paragraph',
        },
      ],
      type: 'doc',
    });
    expect(mf2ProseMirrorPatternFromDoc(doc)).toBe(pattern);
  });
});

describe('MF2 guided multiline editing', () => {
  let restoreGeometry: () => void;

  beforeEach(() => {
    restoreGeometry = installRangeGeometryMock();
  });

  afterEach(() => {
    restoreGeometry();
  });

  it.each([
    ['Enter', '{Enter}'],
    ['Shift+Enter', '{Shift>}{Enter}{/Shift}'],
  ])(
    'inserts a line break with %s without exposing protected placeholders',
    async (_name, keys) => {
      const user = userEvent.setup();
      const onChange = vi.fn();
      const onSubmit = vi.fn();
      render(
        editorElement({
          marksMode: 'off',
          onChange,
          onSubmit,
          pattern: 'Hello {$name}!',
          placeholders: ['name'],
        }),
      );
      const editor = screen.getByRole('textbox', { name: 'Target Message' });
      editor.focus();
      placeCaret(editor, 6);

      await user.keyboard(keys);

      expect(editor.querySelectorAll('br')).toHaveLength(1);
      expect(editor.querySelector('[data-placeholder="name"]')).toHaveTextContent('{$name}');
      expect(onChange).toHaveBeenLastCalledWith('Hello \n{$name}!');
      expect(onSubmit).not.toHaveBeenCalled();
    },
  );

  it.each([
    ['Enter with a modern composition event', { isComposing: true }],
    ['Shift+Enter with a modern composition event', { isComposing: true, shiftKey: true }],
    ['Enter with a legacy IME event', { keyCode: 229 }],
    ['Shift+Enter with a legacy IME event', { keyCode: 229, shiftKey: true }],
  ])('leaves %s to the input method', (_name, eventState) => {
    const onChange = vi.fn();
    render(editorElement({ marksMode: 'off', onChange, pattern: 'Hello' }));
    const editor = screen.getByRole('textbox', { name: 'Target Message' });
    editor.focus();
    placeCaret(editor, 5);

    const notCancelled = fireEvent.keyDown(editor, { key: 'Enter', ...eventState });

    expect(notCancelled).toBe(true);
    expect(editor.querySelector('br')).toBeNull();
    expect(onChange).not.toHaveBeenCalled();

    fireEvent.keyDown(editor, { key: 'Enter' });

    expect(editor.querySelectorAll('br:not(.ProseMirror-trailingBreak)')).toHaveLength(1);
    expect(onChange).toHaveBeenLastCalledWith('Hello\n');
  });
});

describe('Mf2ProseMirrorEditor hidden character marks', () => {
  it('marks only risky whitespace in auto mode', async () => {
    const { container } = render(editorElement({ marksMode: 'auto', pattern: 'Hello  world ' }));

    await screen.findByRole('textbox', { name: 'Target Message' });

    expect(
      container.querySelectorAll('.visible-text-editor__marked-char--space[data-marker="·"]'),
    ).toHaveLength(3);
  });

  it('marks normal spaces in all mode', async () => {
    const { container } = render(editorElement({ marksMode: 'all', pattern: 'Hello world' }));

    await screen.findByRole('textbox', { name: 'Target Message' });

    expect(
      container.querySelectorAll('.visible-text-editor__marked-char--space[data-marker="·"]'),
    ).toHaveLength(1);
  });

  it('does not mark hidden characters in off mode', async () => {
    const { container } = render(
      editorElement({ marksMode: 'off', pattern: 'Hello  world\u200e' }),
    );

    await screen.findByRole('textbox', { name: 'Target Message' });

    expect(container.querySelector('.visible-text-editor__marked-char')).not.toBeInTheDocument();
    expect(container.querySelector('.visible-text-editor__marker-widget')).not.toBeInTheDocument();
  });

  it('renders a line-break marker before the inline hard break', async () => {
    const { container } = render(
      editorElement({ marksMode: 'auto', pattern: 'Line one\nLine two' }),
    );

    const editor = await screen.findByRole('textbox', { name: 'Target Message' });
    const paragraph = editor.querySelector('p');
    const childNodes = Array.from(paragraph?.childNodes ?? []);
    const hardBreakIndex = childNodes.findIndex((node) => node.nodeName === 'BR');
    const markerIndex = childNodes.findIndex(
      (node) =>
        node instanceof HTMLElement &&
        node.classList.contains('visible-text-editor__marker-widget--line-break'),
    );

    expect(
      container.querySelector('.visible-text-editor__marker-widget--line-break'),
    ).toBeVisible();
    expect(hardBreakIndex).toBeGreaterThanOrEqual(0);
    expect(markerIndex).toBeGreaterThanOrEqual(0);
    expect(markerIndex).toBeLessThan(hardBreakIndex);
  });

  it('keeps the current marks mode when an external pattern update rebuilds the document', async () => {
    const { container, rerender } = render(
      editorElement({ marksMode: 'all', pattern: 'Hello world' }),
    );
    await screen.findByRole('textbox', { name: 'Target Message' });
    expect(container.querySelector('.visible-text-editor__marked-char')).toBeInTheDocument();

    rerender(editorElement({ marksMode: 'off', pattern: 'Hello world' }));
    expect(container.querySelector('.visible-text-editor__marked-char')).not.toBeInTheDocument();

    rerender(editorElement({ marksMode: 'off', pattern: 'Hello again' }));
    expect(container.querySelector('.visible-text-editor__marked-char')).not.toBeInTheDocument();
  });

  it('updates marks without replacing the editor or clearing edit history', async () => {
    const restoreRangeGeometry = installRangeGeometryMock();
    const ref = createRef<Mf2ProseMirrorEditorHandle>();
    const onChange = vi.fn();
    try {
      const { container, rerender } = render(
        editorElement({ marksMode: 'all', onChange, pattern: 'Hello world', ref }),
      );
      const editor = await screen.findByRole('textbox', { name: 'Target Message' });
      editor.focus();
      placeCaret(editor, 5);

      act(() => {
        expect(ref.current?.applyTextTool({ text: '!' })).toBe(true);
      });
      expect(onChange).toHaveBeenLastCalledWith('Hello! world');

      rerender(editorElement({ marksMode: 'off', onChange, pattern: 'Hello! world', ref }));

      expect(screen.getByRole('textbox', { name: 'Target Message' })).toBe(editor);
      expect(editor).toHaveFocus();
      expect(container.querySelector('.visible-text-editor__marked-char')).not.toBeInTheDocument();

      fireEvent.keyDown(editor, { ctrlKey: true, key: 'z' });

      await waitFor(() => expect(onChange).toHaveBeenLastCalledWith('Hello world'));
    } finally {
      restoreRangeGeometry();
    }
  });
});

describe('MF2 guided completion', () => {
  let restoreGeometry: () => void;
  beforeEach(() => {
    restoreGeometry = installRangeGeometryMock();
  });
  afterEach(() => {
    restoreGeometry();
  });

  async function openCompletion(trigger = '{', placeholders = ['name', 'count']) {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const onSubmit = vi.fn();
    render(
      editorElement({ marksMode: 'off', pattern: 'Bon jour', placeholders, onChange, onSubmit }),
    );
    const editor = screen.getByRole('textbox', { name: 'Target Message' });
    editor.focus();
    placeCaret(editor, 3);
    await user.keyboard(trigger.split('{').join('{{'));
    return { editor, user, onChange, onSubmit };
  }

  it('offers described placeholders and literal text with coherent listbox relationships', async () => {
    const { editor } = await openCompletion();
    const listbox = await screen.findByRole('listbox', { name: 'Suggestions for {' });
    const options = screen.getAllByRole('option');
    expect(options.map((option) => option.textContent)).toEqual([
      '{$name} Insert name placeholder',
      '{$count} Insert count placeholder',
      'Literal { Raw MF2 stores it as \\{',
    ]);
    expect(editor).toHaveAttribute('aria-autocomplete', 'list');
    expect(editor).toHaveAttribute('aria-expanded', 'true');
    expect(editor).toHaveAttribute('aria-controls', listbox.id);
    expect(editor).toHaveAttribute('aria-activedescendant', options[0].id);
    expect(options[0]).toHaveAttribute('aria-selected', 'true');
    expect(editor).toHaveFocus();
  });

  it('wraps keyboard selection and keeps the literal brace as editable text on Enter', async () => {
    const { editor, user, onChange } = await openCompletion();
    const literal = await screen.findByRole('option', { name: /Literal \{/u });
    await user.keyboard('{ArrowUp}');
    expect(literal).toHaveAttribute('aria-selected', 'true');
    expect(editor).toHaveAttribute('aria-activedescendant', literal.id);
    await user.keyboard('{ArrowDown}');
    expect(screen.getAllByRole('option')[0]).toHaveAttribute('aria-selected', 'true');
    await user.keyboard('{ArrowUp}{Enter}');
    expect(editor.textContent).toBe('Bon{ jour');
    expect(editor.querySelector('[contenteditable="false"]')).toBeNull();
    expect(onChange).toHaveBeenLastCalledWith('Bon\\{ jour');
    expect(editor).toHaveAttribute('aria-expanded', 'false');
    expect(editor).toHaveAttribute('aria-controls', '');
    expect(editor).toHaveAttribute('aria-activedescendant', '');
    expect(window.getSelection()?.anchorOffset).toBe(4);
    await user.keyboard('name}');
    expect(editor.textContent).toBe('Bon{name} jour');
    expect(onChange).toHaveBeenLastCalledWith('Bon\\{name\\} jour');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('offers a literal brace even when the pattern has no placeholders', async () => {
    const { editor, user } = await openCompletion('{', []);
    expect(await screen.findAllByRole('option')).toHaveLength(1);
    await user.keyboard('{Enter}');
    expect(editor.textContent).toBe('Bon{ jour');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it.each(['$', '{$'])('keeps %s completion placeholder-focused', async (trigger) => {
    await openCompletion(trigger);
    expect(await screen.findAllByRole('option')).toHaveLength(2);
    expect(screen.queryByRole('option', { name: /Literal/u })).not.toBeInTheDocument();
  });

  it('filters case-insensitively, resets selection, and inserts an atomic placeholder', async () => {
    const { editor, user, onChange, onSubmit } = await openCompletion();
    await screen.findByRole('listbox');
    await user.keyboard('{ArrowUp}N');
    const option = await screen.findByRole('option', { name: '{$name} Insert name placeholder' });
    expect(screen.getAllByRole('option')).toHaveLength(1);
    expect(option).toHaveAttribute('aria-selected', 'true');
    await user.keyboard('{Enter}');
    expect(editor.querySelector('[data-placeholder="name"]')).toHaveTextContent('{$name}');
    expect(onChange).toHaveBeenLastCalledWith('Bon{$name} jour');
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('announces no matches without referencing a nonexistent active option', async () => {
    const { editor, user } = await openCompletion('$unknown');
    expect(await screen.findByRole('status')).toHaveTextContent('No matching placeholder');
    expect(editor).toHaveAttribute('aria-expanded', 'false');
    expect(editor).toHaveAttribute('aria-controls', '');
    expect(editor).toHaveAttribute('aria-activedescendant', '');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(editor.textContent).toBe('Bon$unknown jour');
  });

  it('Escape dismisses completion without changing text or invoking the host action', async () => {
    const { editor, user, onSubmit } = await openCompletion();
    await screen.findByRole('listbox');
    const afterTyping = editor.textContent;
    await user.keyboard('{Escape}x');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(editor.textContent).toBe(afterTyping?.replace('{', '{x'));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('retains the host submit shortcut while completion is open', async () => {
    const { user, onSubmit } = await openCompletion();
    await screen.findByRole('listbox');
    await user.keyboard('{Control>}{Enter}{/Control}');
    expect(onSubmit).toHaveBeenCalledOnce();
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });
});
