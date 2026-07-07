import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createRef, type Ref, useMemo, useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import {
  extractIcuProtectedTextTokens,
  getIcuFormOptions,
  getIcuMovableTextRanges,
  preservesProtectedTextTokenStructure,
} from '../utils/protectedTextTokens';
import { VisibleTextEditor, type VisibleTextEditorHandle } from './VisibleTextEditor';

function renderEditor(value: string) {
  return render(
    <VisibleTextEditor value={value} onChange={vi.fn()} showInvisibles ariaLabel="Translation" />,
  );
}

function openIcuFormMenu(container: HTMLElement) {
  const trigger = container.querySelector<HTMLElement>(
    '.visible-text-editor__protected-token--icu-syntax:not(.visible-text-editor__protected-token--empty-icu-syntax)',
  );
  expect(trigger).toBeInTheDocument();
  fireEvent.click(trigger as HTMLElement);
}

function lastValue(values: string[]): string | undefined {
  return values[values.length - 1];
}

function ControlledIcuEditor({
  editorRef,
  initialValue,
  onValueChange,
}: {
  editorRef: Ref<VisibleTextEditorHandle>;
  initialValue: string;
  onValueChange: (value: string) => void;
}) {
  const [value, setValue] = useState(initialValue);
  const protectedTokens = useMemo(() => extractIcuProtectedTextTokens(value), [value]);
  const movableProtectedRanges = useMemo(() => getIcuMovableTextRanges(value), [value]);

  return (
    <VisibleTextEditor
      ref={editorRef}
      value={value}
      onChange={(nextValue) => {
        onValueChange(nextValue);
        setValue(nextValue);
      }}
      showInvisibles={false}
      controlBar={{
        onToggleRawMode: vi.fn(),
        protectedTokenCount: protectedTokens.length,
        rawMode: false,
      }}
      movableProtectedRanges={movableProtectedRanges}
      protectedTokens={protectedTokens}
    />
  );
}

function restoreDescriptor(target: object, name: string, descriptor?: PropertyDescriptor) {
  if (descriptor) {
    Object.defineProperty(target, name, descriptor);
    return;
  }

  delete (target as Record<string, unknown>)[name];
}

function installProseMirrorHistoryDomMock() {
  const rangeDescriptors = {
    getBoundingClientRect: Object.getOwnPropertyDescriptor(
      Range.prototype,
      'getBoundingClientRect',
    ),
    getClientRects: Object.getOwnPropertyDescriptor(Range.prototype, 'getClientRects'),
  };
  const scrollByDescriptor = Object.getOwnPropertyDescriptor(window, 'scrollBy');
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
    restoreDescriptor(Range.prototype, 'getClientRects', rangeDescriptors.getClientRects);
    restoreDescriptor(
      Range.prototype,
      'getBoundingClientRect',
      rangeDescriptors.getBoundingClientRect,
    );
    restoreDescriptor(window, 'scrollBy', scrollByDescriptor);
  };
}

type DragStartInit = Parameters<typeof fireEvent.dragStart>[1] & {
  dataTransfer?: Record<string, unknown>;
};

function fireProseMirrorDragStart(element: HTMLElement, init: DragStartInit = {}) {
  const dataTransfer = {
    clearData: vi.fn(),
    dropEffect: 'move',
    effectAllowed: 'copyMove',
    files: [],
    getData: vi.fn(() => ''),
    setData: vi.fn(),
    types: [],
    ...init.dataTransfer,
  };
  const elementFromPointDescriptor = Object.getOwnPropertyDescriptor(document, 'elementFromPoint');
  Object.defineProperty(document, 'elementFromPoint', {
    configurable: true,
    value: () => element,
  });
  const restoreDragDom = installProseMirrorHistoryDomMock();

  try {
    return fireEvent.dragStart(element, { ...init, dataTransfer });
  } finally {
    restoreDragDom();
    restoreDescriptor(document, 'elementFromPoint', elementFromPointDescriptor);
  }
}

function installDropPositionMock(element: Node, offset: number) {
  const elementFromPointDescriptor = Object.getOwnPropertyDescriptor(document, 'elementFromPoint');
  const caretRangeFromPointDescriptor = Object.getOwnPropertyDescriptor(
    document,
    'caretRangeFromPoint',
  );

  Object.defineProperty(document, 'elementFromPoint', {
    configurable: true,
    value: () => (element instanceof Element ? element : element.parentElement),
  });
  Object.defineProperty(document, 'caretRangeFromPoint', {
    configurable: true,
    value: () => {
      const range = document.createRange();
      range.setStart(element, offset);
      range.collapse(true);
      return range;
    },
  });

  return () => {
    restoreDescriptor(document, 'elementFromPoint', elementFromPointDescriptor);
    restoreDescriptor(document, 'caretRangeFromPoint', caretRangeFromPointDescriptor);
  };
}

describe('VisibleTextEditor', () => {
  it('keeps normal single spaces quiet when marks are enabled', async () => {
    const { container } = renderEditor('Hello world');

    await waitFor(() => expect(container.querySelector('.ProseMirror')).toBeInTheDocument());

    expect(container.querySelectorAll('.visible-text-editor__marked-char')).toHaveLength(0);
  });

  it('marks risky whitespace without marking every normal space', async () => {
    const { container } = renderEditor('Hello  world ');

    await waitFor(() => expect(container.querySelector('.ProseMirror')).toBeInTheDocument());

    const markedSpaces = container.querySelectorAll(
      '.visible-text-editor__marked-char--space[data-marker="·"]',
    );
    expect(markedSpaces).toHaveLength(3);
  });

  it('can show every normal space when marks are set to all', async () => {
    const { container } = render(
      <VisibleTextEditor
        value="Hello world"
        onChange={vi.fn()}
        marksMode="all"
        ariaLabel="Translation"
      />,
    );

    await waitFor(() => expect(container.querySelector('.ProseMirror')).toBeInTheDocument());

    expect(
      container.querySelectorAll('.visible-text-editor__marked-char--space[data-marker="·"]'),
    ).toHaveLength(1);
  });

  it('keeps the hidden-space marker after an ICU message outside ICU styling', async () => {
    const value = '{count, plural, one {#file} other {#files}} after';
    const { container } = render(
      <VisibleTextEditor
        value={value}
        onChange={vi.fn()}
        marksMode="all"
        ariaLabel="Translation"
        controlBar={{
          icuFormOptions: getIcuFormOptions(value),
          onToggleIcuForm: vi.fn(),
        }}
        protectedTokens={extractIcuProtectedTextTokens(value)}
      />,
    );

    await waitFor(() => expect(container.querySelector('.ProseMirror')).toBeInTheDocument());

    const markedSpaces = container.querySelectorAll<HTMLElement>(
      '.visible-text-editor__marked-char--space[data-marker="·"]',
    );
    expect(markedSpaces).toHaveLength(1);
    expect(markedSpaces[0].closest('.visible-text-editor__icu-inline-message')).toBeNull();

    const icuMessages = container.querySelectorAll('.visible-text-editor__icu-inline-message');
    expect(icuMessages).toHaveLength(1);
    expect(icuMessages[0]).toHaveTextContent('count one#fileother#files');
    expect(
      Array.from(icuMessages[0].querySelectorAll('.visible-text-editor__icu-editable-text')).map(
        (element) => element.textContent,
      ),
    ).toEqual(['file', 'files']);
    expect(
      Array.from(
        icuMessages[0].querySelectorAll('.visible-text-editor__protected-token--icu-placeholder'),
      ).map((element) => element.textContent),
    ).toEqual(['#', '#']);
  });

  it('keeps text inserted after a placeholder inside an ICU body on the editable text surface', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = '{count, plural, one {{count} h} other {{count} h}}';
    const protectedTokens = extractIcuProtectedTextTokens(value);
    const { container } = render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        ariaLabel="Translation"
        protectedTokens={protectedTokens}
      />,
    );

    await waitFor(() => expect(ref.current).not.toBeNull());

    const otherPlaceholderStart = value.indexOf('{count} h', value.indexOf('other'));
    expect(otherPlaceholderStart).toBeGreaterThan(0);

    act(() => {
      const insertOffset = otherPlaceholderStart + '{count}'.length;
      ref.current?.setSelection({ start: insertOffset, end: insertOffset });
      ref.current?.insertText('s');
    });

    expect(handleChange).toHaveBeenLastCalledWith(
      '{count, plural, one {{count} h} other {{count}s h}}',
    );

    await waitFor(() => {
      const icuMessage = container.querySelector('.visible-text-editor__icu-inline-message');
      expect(icuMessage).toBeInTheDocument();
      expect(
        Array.from(
          icuMessage?.querySelectorAll('.visible-text-editor__icu-editable-text') ?? [],
        ).some((element) => element.textContent?.includes('s h')),
      ).toBe(true);
    });
  });

  it('places the caret after a clicked placeholder inside an ICU body', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = '{count, plural, one {{count} h} other {{count} h}}';
    const protectedTokens = extractIcuProtectedTextTokens(value);
    const { container } = render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        ariaLabel="Translation"
        protectedTokens={protectedTokens}
      />,
    );

    await waitFor(() => expect(ref.current).not.toBeNull());

    const placeholders = container.querySelectorAll<HTMLElement>(
      '.visible-text-editor__icu-inline-message .visible-text-editor__protected-token--icu-placeholder',
    );
    const otherPlaceholder = placeholders[1];
    expect(otherPlaceholder).toBeInTheDocument();
    vi.spyOn(otherPlaceholder, 'getBoundingClientRect').mockReturnValue({
      bottom: 20,
      height: 20,
      left: 0,
      right: 100,
      top: 0,
      width: 100,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    });

    fireEvent.click(otherPlaceholder, { clientX: 90 });
    act(() => {
      ref.current?.insertText('x');
    });

    expect(handleChange).toHaveBeenLastCalledWith(
      '{count, plural, one {{count} h} other {{count}x h}}',
    );
  });

  it('places the caret after a clicked standalone placeholder', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = 'Hello {name} friend';
    const protectedTokens = extractIcuProtectedTextTokens(value);
    const { container } = render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        ariaLabel="Translation"
        protectedTokens={protectedTokens}
      />,
    );

    const placeholder = await waitFor(() => {
      const element = container.querySelector<HTMLElement>(
        '.visible-text-editor__protected-token--icu-placeholder',
      );
      expect(element).toBeInTheDocument();
      expect(element).toHaveTextContent('name');
      return element as HTMLElement;
    });

    fireEvent.click(placeholder);
    act(() => {
      ref.current?.insertText('!');
    });

    expect(handleChange).toHaveBeenLastCalledWith('Hello {name}! friend');
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('types before an invisible ICU boundary instead of replacing it', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = '{count, plural, one {{count} h} other {{count} h}}';
    const protectedTokens = extractIcuProtectedTextTokens(value);
    render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        ariaLabel="Translation"
        controlBar={{ protectedTokenCount: protectedTokens.length }}
        protectedTokens={protectedTokens}
        validateNextValue={(nextValue) =>
          preservesProtectedTextTokenStructure({
            previousValue: value,
            previousTokens: protectedTokens,
            nextValue,
            mode: 'icu',
          })
        }
      />,
    );

    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: value.length - 2, end: value.length });
      ref.current?.insertText('x');
    });

    expect(handleChange).toHaveBeenLastCalledWith(
      '{count, plural, one {{count} h} other {{count} hx}}',
    );
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('skips ICU syntax tokens when arrowing between plural forms', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = '{count, plural, one {# file} other {# files}}';
    const protectedTokens = extractIcuProtectedTextTokens(value);
    render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        ariaLabel="Translation"
        controlBar={{ protectedTokenCount: protectedTokens.length }}
        protectedTokens={protectedTokens}
        validateNextValue={(nextValue) =>
          preservesProtectedTextTokenStructure({
            previousValue: value,
            previousTokens: protectedTokens,
            nextValue,
            mode: 'icu',
          })
        }
      />,
    );

    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    const firstFormEnd = value.indexOf('} other');
    const otherFormBodyStart = value.indexOf('# files');
    expect(firstFormEnd).toBeGreaterThan(0);
    expect(otherFormBodyStart).toBeGreaterThan(firstFormEnd);

    act(() => {
      ref.current?.setSelection({ start: firstFormEnd, end: firstFormEnd });
    });
    fireEvent.keyDown(editor, { key: 'ArrowRight' });
    act(() => {
      ref.current?.insertText('x');
    });

    expect(handleChange).toHaveBeenLastCalledWith(
      `${value.slice(0, otherFormBodyStart)}x${value.slice(otherFormBodyStart)}`,
    );
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('can turn marks off entirely', async () => {
    const { container } = render(
      <VisibleTextEditor
        value="Hello  world"
        onChange={vi.fn()}
        marksMode="off"
        ariaLabel="Translation"
      />,
    );

    await waitFor(() => expect(container.querySelector('.ProseMirror')).toBeInTheDocument());

    expect(container.querySelectorAll('.visible-text-editor__marked-char')).toHaveLength(0);
  });

  it('always marks special invisible whitespace', async () => {
    const { container } = renderEditor('Hello\u00a0world');

    await waitFor(() => expect(container.querySelector('.ProseMirror')).toBeInTheDocument());

    expect(
      container.querySelectorAll('.visible-text-editor__marked-char--special-space'),
    ).toHaveLength(1);
  });

  it('renders raw line breaks as stable hard breaks', async () => {
    const { container } = render(
      <VisibleTextEditor
        value={'Line one\nLine two'}
        onChange={vi.fn()}
        showInvisibles={false}
        ariaLabel="Translation"
      />,
    );

    await waitFor(() => expect(container.querySelector('.ProseMirror br')).toBeInTheDocument());

    expect(container.querySelector('.ProseMirror')?.textContent).toBe('Line oneLine two');
  });

  it('renders line-break markers before the hard break they mark', async () => {
    render(
      <VisibleTextEditor
        value={'Line one\nLine two'}
        onChange={vi.fn()}
        marksMode="all"
        ariaLabel="Translation"
      />,
    );

    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    await waitFor(() =>
      expect(
        editor.querySelector('.visible-text-editor__marker-widget--line-break'),
      ).toBeInTheDocument(),
    );

    const childNodes = Array.from(editor.childNodes);
    const hardBreakIndex = childNodes.findIndex((node) => node.nodeName === 'BR');
    const markerIndex = childNodes.findIndex(
      (node) =>
        node instanceof HTMLElement &&
        node.classList.contains('visible-text-editor__marker-widget--line-break'),
    );

    expect(hardBreakIndex).toBeGreaterThanOrEqual(0);
    expect(markerIndex).toBeGreaterThanOrEqual(0);
    expect(markerIndex).toBeLessThan(hardBreakIndex);
  });

  it('renders protected-text diagnostics inline even when hidden character marks are off', async () => {
    const { container, rerender } = render(
      <VisibleTextEditor
        value="Broken %1$ placeholder"
        onChange={vi.fn()}
        marksMode="off"
        protectedDiagnostics={[]}
      />,
    );

    await waitFor(() => expect(container.querySelector('.ProseMirror')).toBeInTheDocument());
    expect(container.querySelector('.visible-text-editor__diagnostic')).not.toBeInTheDocument();

    rerender(
      <VisibleTextEditor
        value="Broken %1$ placeholder"
        onChange={vi.fn()}
        marksMode="off"
        protectedDiagnostics={[
          {
            start: 7,
            end: 10,
            severity: 'warning',
            code: 'placeholder-malformed',
            message: 'Placeholder-like sequence %1$ is incomplete or malformed.',
          },
        ]}
      />,
    );

    const diagnostic = await waitFor(() => {
      const element = container.querySelector('.visible-text-editor__diagnostic--warning');
      expect(element).toHaveTextContent('%1$');
      return element as HTMLElement;
    });
    expect(diagnostic).toHaveClass('visible-text-editor__diagnostic--placeholder-malformed');
    expect(diagnostic).toHaveAttribute(
      'title',
      'Placeholder-like sequence %1$ is incomplete or malformed.',
    );
    expect(container.querySelector('.visible-text-editor__marked-char')).not.toBeInTheDocument();
  });

  it('serializes inserted line breaks back to raw text', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    render(<VisibleTextEditor ref={ref} value="" onChange={handleChange} showInvisibles={false} />);

    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.insertText('Line one\nLine two');
    });

    expect(handleChange).toHaveBeenLastCalledWith('Line one\nLine two');
  });

  it('preserves existing line breaks when typing after them', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = 'Line one\nLine two';
    render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        ariaLabel="Translation"
      />,
    );

    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      const afterLineBreak = 'Line one\n'.length;
      ref.current?.setSelection({ start: afterLineBreak, end: afterLineBreak });
      ref.current?.insertText('Typed ');
    });

    expect(handleChange).toHaveBeenLastCalledWith('Line one\nTyped Line two');
  });

  it('keeps inserted text in the ProseMirror undo and redo history', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    render(
      <VisibleTextEditor
        ref={ref}
        value="Hello"
        onChange={handleChange}
        showInvisibles={false}
        ariaLabel="Translation"
      />,
    );

    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: 5, end: 5 });
      ref.current?.insertText(' world');
    });

    expect(handleChange).toHaveBeenLastCalledWith('Hello world');

    const restoreHistoryDom = installProseMirrorHistoryDomMock();
    let didUndo = false;
    let didRedo = false;
    try {
      act(() => {
        didUndo = ref.current?.undo() ?? false;
      });

      expect(didUndo).toBe(true);
      expect(handleChange).toHaveBeenLastCalledWith('Hello');

      act(() => {
        didRedo = ref.current?.redo() ?? false;
      });
    } finally {
      restoreHistoryDom();
    }

    expect(didRedo).toBe(true);
    expect(handleChange).toHaveBeenLastCalledWith('Hello world');
  });

  it('preserves range selections when protected tokens are applied after render', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const baseProps = {
      ref,
      value: 'Hello {name}',
      onChange: vi.fn(),
      showInvisibles: false,
      ariaLabel: 'Translation',
    };
    const { rerender } = render(<VisibleTextEditor {...baseProps} protectedTokens={[]} />);

    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: 0, end: 5 });
    });

    rerender(
      <VisibleTextEditor
        {...baseProps}
        protectedTokens={[
          {
            start: 6,
            end: 12,
            label: 'ICU argument name',
            kind: 'icu-placeholder',
          },
        ]}
      />,
    );

    await waitFor(() => expect(ref.current?.getSelection()).toEqual({ start: 0, end: 5 }));
  });

  it('updates protection when identical token text moves without a value change', async () => {
    const baseProps = {
      value: '{name} {name}',
      onChange: vi.fn(),
      showInvisibles: false,
      ariaLabel: 'Translation',
    };
    const firstToken = {
      start: 0,
      end: 6,
      label: 'ICU argument name',
      kind: 'icu-placeholder' as const,
    };
    const secondToken = {
      start: 7,
      end: 13,
      label: 'ICU argument name',
      kind: 'icu-placeholder' as const,
    };
    const { container, rerender } = render(
      <VisibleTextEditor {...baseProps} protectedTokens={[firstToken]} />,
    );

    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    await waitFor(() =>
      expect(
        editor.childNodes[0] instanceof HTMLElement
          ? editor.childNodes[0].classList.contains('visible-text-editor__protected-token')
          : false,
      ).toBe(true),
    );

    rerender(<VisibleTextEditor {...baseProps} protectedTokens={[secondToken]} />);

    await waitFor(() => {
      const protectedToken = container.querySelector('.visible-text-editor__protected-token');
      expect(protectedToken?.previousSibling?.textContent).toBe('{name} ');
      expect(protectedToken).toHaveAttribute('aria-label', 'ICU argument name');
      expect(protectedToken).toHaveAttribute('data-raw', '{name}');
      expect(protectedToken).toHaveAttribute('title', 'ICU argument name');
    });
  });

  it('renders ICU syntax labels without repeated message type words', async () => {
    const value = '{count, plural, one {# file} other {# files}}';
    const protectedTokens = extractIcuProtectedTextTokens(value);
    const { container } = render(
      <VisibleTextEditor
        value={value}
        onChange={vi.fn()}
        showInvisibles={false}
        protectedTokens={protectedTokens}
      />,
    );

    await waitFor(() => {
      const firstSyntaxToken = container.querySelector(
        '.visible-text-editor__protected-token[data-raw="{count, plural, one {"]',
      );
      expect(firstSyntaxToken).toHaveTextContent('count one');
      expect(firstSyntaxToken).not.toHaveTextContent('plural');
    });
  });

  it('updates editability when disabled changes after mount', async () => {
    const baseProps = {
      value: 'Hello',
      onChange: vi.fn(),
      showInvisibles: false,
      ariaLabel: 'Translation',
    };
    const { rerender } = render(<VisibleTextEditor {...baseProps} disabled={false} />);

    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    expect(editor).toHaveAttribute('contenteditable', 'true');
    expect(editor).not.toHaveAttribute('aria-disabled');
    expect(editor).toHaveAttribute('aria-readonly', 'false');

    rerender(<VisibleTextEditor {...baseProps} disabled />);

    await waitFor(() => expect(editor).toHaveAttribute('contenteditable', 'false'));
    expect(editor).toHaveAttribute('aria-disabled', 'true');
    expect(editor).toHaveAttribute('aria-readonly', 'false');
  });

  it('does not handle paste while disabled', async () => {
    const handleChange = vi.fn();
    render(
      <VisibleTextEditor
        value="Hello"
        onChange={handleChange}
        showInvisibles={false}
        ariaLabel="Translation"
        disabled
      />,
    );

    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    fireEvent.paste(editor, {
      clipboardData: {
        getData: () => ' pasted',
      },
    });

    expect(handleChange).not.toHaveBeenCalled();
  });

  it('does not insert line breaks while disabled', async () => {
    const handleChange = vi.fn();
    render(
      <VisibleTextEditor
        value="Hello"
        onChange={handleChange}
        showInvisibles={false}
        ariaLabel="Translation"
        disabled
      />,
    );

    const editor = await screen.findByRole('textbox', { name: 'Translation' });
    fireEvent.keyDown(editor, {
      key: 'Enter',
    });

    expect(handleChange).not.toHaveBeenCalled();
  });

  it('shows placeholder editing as an action in the control bar', () => {
    render(
      <VisibleTextEditor
        value="Hello {name}"
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          rawMode: false,
          onToggleRawMode: vi.fn(),
        }}
      />,
    );

    const protectionButton = screen.getByRole('button', {
      name: 'Placeholder editing is off. Edit placeholders',
    });
    expect(protectionButton).toHaveTextContent('Edit placeholders');
    expect(protectionButton).toHaveAttribute('aria-pressed', 'false');
  });

  it('omits passive placeholder counts from the compact bar', () => {
    render(
      <VisibleTextEditor
        value="Hello {name}"
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          protectedTokenCount: 1,
        }}
      />,
    );

    expect(screen.queryByText('1 token found')).not.toBeInTheDocument();
  });

  it('shows completion options and applies the selected option with the keyboard', async () => {
    const restoreDom = installProseMirrorHistoryDomMock();
    const handleApply = vi.fn();

    try {
      render(
        <VisibleTextEditor
          value="Hello {c"
          onChange={vi.fn()}
          ariaLabel="Translation"
          completion={{
            ariaLabel: 'MF2 placeholders',
            onApply: handleApply,
            options: [
              { id: 'count', label: '{$count}', detail: 'source placeholder' },
              { id: 'customer', label: '{$customer}', detail: 'source placeholder' },
            ],
          }}
        />,
      );

      const editor = await screen.findByRole('textbox', { name: 'Translation' });
      expect(await screen.findByRole('listbox', { name: 'MF2 placeholders' })).toBeInTheDocument();

      fireEvent.keyDown(editor, { key: 'ArrowDown' });
      await waitFor(() =>
        expect(
          screen.getByRole('option', { name: '{$customer} source placeholder' }),
        ).toHaveAttribute('aria-selected', 'true'),
      );
      fireEvent.keyDown(editor, { key: 'Enter' });

      expect(handleApply).toHaveBeenCalledWith({
        id: 'customer',
        label: '{$customer}',
        detail: 'source placeholder',
      });
    } finally {
      restoreDom();
    }
  });

  it('applies completion options by click', async () => {
    const restoreDom = installProseMirrorHistoryDomMock();
    const handleApply = vi.fn();

    try {
      render(
        <VisibleTextEditor
          value="Hello {c"
          onChange={vi.fn()}
          ariaLabel="Translation"
          completion={{
            onApply: handleApply,
            options: [{ id: 'count', label: '{$count}', detail: 'source placeholder' }],
          }}
        />,
      );

      fireEvent.click(await screen.findByRole('option', { name: '{$count} source placeholder' }));

      expect(handleApply).toHaveBeenCalledWith({
        id: 'count',
        label: '{$count}',
        detail: 'source placeholder',
      });
    } finally {
      restoreDom();
    }
  });

  it('explains blocked protected edits in the compact status text', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();

    render(
      <VisibleTextEditor
        ref={ref}
        value="Hello {name}"
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: 1,
          rawMode: false,
        }}
        validateNextValue={() => false}
      />,
    );

    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.insertText('!');
    });

    expect(handleChange).not.toHaveBeenCalled();
    expect(
      await screen.findByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).toHaveAttribute('aria-live', 'polite');
  });

  it('allows deleting a selected whole protected token', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();

    render(
      <VisibleTextEditor
        ref={ref}
        value="Hello {name}"
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: 1,
          rawMode: false,
        }}
        protectedTokens={[
          {
            start: 6,
            end: 12,
            label: 'ICU argument name',
            kind: 'icu-placeholder',
          },
        ]}
      />,
    );

    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: 6, end: 12 });
      ref.current?.insertText('');
    });

    expect(handleChange).toHaveBeenLastCalledWith('Hello ');
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('allows deleting a selected protected token before page-level validation runs', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const validateNextValue = vi.fn(() => false);

    render(
      <VisibleTextEditor
        ref={ref}
        value="Hello {name}"
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: 1,
          rawMode: false,
        }}
        protectedTokens={[
          {
            start: 6,
            end: 12,
            label: 'ICU argument name',
            kind: 'icu-placeholder',
          },
        ]}
        validateNextValue={validateNextValue}
      />,
    );

    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: 6, end: 12 });
      ref.current?.insertText('');
    });

    expect(validateNextValue).not.toHaveBeenCalled();
    expect(handleChange).toHaveBeenLastCalledWith('Hello ');
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('allows deleting a selected whole protected token with Backspace', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const validateNextValue = vi.fn(() => false);

    render(
      <VisibleTextEditor
        ref={ref}
        value="Hello {name}"
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: 1,
          rawMode: false,
        }}
        protectedTokens={[
          {
            start: 6,
            end: 12,
            label: 'ICU argument name',
            kind: 'icu-placeholder',
          },
        ]}
        validateNextValue={validateNextValue}
      />,
    );

    const editor = await screen.findByRole('textbox');
    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: 6, end: 12 });
    });

    const restoreDom = installProseMirrorHistoryDomMock();
    try {
      fireEvent.keyDown(editor, { key: 'Backspace' });
    } finally {
      restoreDom();
    }

    expect(validateNextValue).not.toHaveBeenCalled();
    expect(handleChange).toHaveBeenLastCalledWith('Hello ');
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('allows deleting a whole protected token with Backspace from the following caret', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const validateNextValue = vi.fn(() => false);
    const value = 'Hello {name} friend';

    render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: 1,
          rawMode: false,
        }}
        protectedTokens={[
          {
            start: 6,
            end: 12,
            label: 'ICU argument name',
            kind: 'icu-placeholder',
          },
        ]}
        validateNextValue={validateNextValue}
      />,
    );

    const editor = await screen.findByRole('textbox');
    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: 12, end: 12 });
    });

    const restoreDom = installProseMirrorHistoryDomMock();
    try {
      fireEvent.keyDown(editor, { key: 'Backspace' });
    } finally {
      restoreDom();
    }

    expect(validateNextValue).not.toHaveBeenCalled();
    expect(handleChange).toHaveBeenLastCalledWith('Hello  friend');
  });

  it('allows deleting a whole protected token with Delete from the preceding caret', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const validateNextValue = vi.fn(() => false);
    const value = 'Hello {name} friend';

    render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: 1,
          rawMode: false,
        }}
        protectedTokens={[
          {
            start: 6,
            end: 12,
            label: 'ICU argument name',
            kind: 'icu-placeholder',
          },
        ]}
        validateNextValue={validateNextValue}
      />,
    );

    const editor = await screen.findByRole('textbox');
    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: 6, end: 6 });
    });

    const restoreDom = installProseMirrorHistoryDomMock();
    try {
      fireEvent.keyDown(editor, { key: 'Delete' });
    } finally {
      restoreDom();
    }

    expect(validateNextValue).not.toHaveBeenCalled();
    expect(handleChange).toHaveBeenLastCalledWith('Hello  friend');
  });

  it('blocks adjacent keyboard deletion of ICU syntax fragments', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = '{count, plural, one {# file} other {# files}}';
    const protectedTokens = extractIcuProtectedTextTokens(value);
    const syntaxStart = value.indexOf('} other {');

    render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        protectedTokens={protectedTokens}
      />,
    );

    const editor = await screen.findByRole('textbox');
    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: syntaxStart, end: syntaxStart });
    });

    const restoreDom = installProseMirrorHistoryDomMock();
    try {
      fireEvent.keyDown(editor, { key: 'Delete' });
    } finally {
      restoreDom();
    }

    expect(handleChange).not.toHaveBeenCalled();
    expect(
      await screen.findByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).toHaveAttribute('aria-live', 'polite');
  });

  it('allows deleting a selected whole ICU plural range before page-level validation runs', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const validateNextValue = vi.fn(() => false);
    const plural = '{count, plural, one {# file} other {# files}}';
    const value = `Move ${plural} later.`;
    const protectedTokens = extractIcuProtectedTextTokens(value);
    const pluralStart = value.indexOf(plural);
    const pluralEnd = pluralStart + plural.length;

    render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        movableProtectedRanges={[
          {
            start: pluralStart,
            end: pluralEnd,
            label: 'ICU plural message',
          },
        ]}
        protectedTokens={protectedTokens}
        validateNextValue={validateNextValue}
      />,
    );

    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: pluralStart, end: pluralEnd });
      ref.current?.insertText('');
    });

    expect(validateNextValue).not.toHaveBeenCalled();
    expect(handleChange).toHaveBeenLastCalledWith('Move  later.');
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('allows deleting a selected whole ICU plural range with Delete', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const validateNextValue = vi.fn(() => false);
    const plural = '{count, plural, one {# file} other {# files}}';
    const value = `Move ${plural} later.`;
    const protectedTokens = extractIcuProtectedTextTokens(value);
    const pluralStart = value.indexOf(plural);
    const pluralEnd = pluralStart + plural.length;

    render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        movableProtectedRanges={[
          {
            start: pluralStart,
            end: pluralEnd,
            label: 'ICU plural message',
          },
        ]}
        protectedTokens={protectedTokens}
        validateNextValue={validateNextValue}
      />,
    );

    const editor = await screen.findByRole('textbox');
    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: pluralStart, end: pluralEnd });
    });

    const restoreDom = installProseMirrorHistoryDomMock();
    try {
      fireEvent.keyDown(editor, { key: 'Delete' });
    } finally {
      restoreDom();
    }

    expect(validateNextValue).not.toHaveBeenCalled();
    expect(handleChange).toHaveBeenLastCalledWith('Move  later.');
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('blocks paste over a protected token', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();

    render(
      <VisibleTextEditor
        ref={ref}
        value="Hello {name}"
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: 1,
          rawMode: false,
        }}
        protectedTokens={[
          {
            start: 6,
            end: 12,
            label: 'ICU argument name',
            kind: 'icu-placeholder',
          },
        ]}
      />,
    );

    const editor = await screen.findByRole('textbox');
    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: 6, end: 12 });
    });
    fireEvent.paste(editor, {
      clipboardData: {
        getData: () => 'Alice',
      },
    });

    expect(handleChange).not.toHaveBeenCalled();
    expect(
      await screen.findByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).toHaveAttribute('aria-live', 'polite');
  });

  it('allows dragging a locked standalone placeholder token', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = 'Hello {name}';
    const protectedTokens = extractIcuProtectedTextTokens(value);
    const { container } = render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        protectedTokens={protectedTokens}
      />,
    );

    const protectedToken = await waitFor(() => {
      const element = container.querySelector('.visible-text-editor__protected-token');
      expect(element).toHaveTextContent('name');
      return element as HTMLElement;
    });

    expect(protectedToken).toHaveAttribute('draggable', 'true');
    expect(protectedToken).toHaveAttribute('contenteditable', 'false');
    const dataTransfer = { effectAllowed: 'copyMove', setData: vi.fn() };
    expect(fireProseMirrorDragStart(protectedToken, { dataTransfer })).toBe(true);
    expect(dataTransfer.setData).toHaveBeenCalledWith('text/plain', '{name}');
    expect(ref.current?.getSelection()).toEqual({ start: 6, end: 12 });
    expect(handleChange).not.toHaveBeenCalled();
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('moves a dragged standalone placeholder when dropped in the same editor', async () => {
    const handleChange = vi.fn();
    const validateNextValue = vi.fn(() => false);
    const value = 'Hello {name} friend';
    const protectedTokens = extractIcuProtectedTextTokens(value);
    const { container } = render(
      <VisibleTextEditor
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        protectedTokens={protectedTokens}
        validateNextValue={validateNextValue}
      />,
    );

    const editor = await screen.findByRole('textbox');
    const protectedToken = await waitFor(() => {
      const element = container.querySelector('.visible-text-editor__protected-token');
      expect(element).toHaveTextContent('name');
      return element as HTMLElement;
    });

    expect(fireProseMirrorDragStart(protectedToken)).toBe(true);

    const restoreDropPosition = installDropPositionMock(editor, editor.childNodes.length);
    const restoreDropDom = installProseMirrorHistoryDomMock();
    try {
      fireEvent.drop(editor, {
        clientX: 1,
        clientY: 1,
        dataTransfer: {
          clearData: vi.fn(),
          dropEffect: 'move',
          effectAllowed: 'move',
          files: [],
          getData: (type: string) => (type === 'text/plain' ? '{name}' : ''),
          setData: vi.fn(),
          types: ['text/plain'],
        },
      });
    } finally {
      restoreDropDom();
      restoreDropPosition();
    }

    expect(validateNextValue).not.toHaveBeenCalled();
    expect(handleChange).toHaveBeenLastCalledWith('Hello  friend{name}');
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('allows dragging locked plural syntax and plural pound placeholders', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = '{count, plural, one {# file} other {# files}}';
    const protectedTokens = extractIcuProtectedTextTokens(value);
    const { container } = render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        movableProtectedRanges={[
          {
            start: 0,
            end: value.length,
            label: 'ICU plural message',
          },
        ]}
        protectedTokens={protectedTokens}
      />,
    );

    const pluralSyntaxToken = await waitFor(() => {
      const tokens = Array.from(
        container.querySelectorAll('.visible-text-editor__protected-token'),
      );
      const syntaxToken = tokens.find((token) => token.textContent?.includes('count'));
      const poundToken = tokens.find((token) => token.textContent === '#');
      expect(syntaxToken).toBeDefined();
      expect(poundToken).toBeDefined();
      return syntaxToken as HTMLElement;
    });

    expect(pluralSyntaxToken).toHaveAttribute('draggable', 'true');
    expect(fireProseMirrorDragStart(pluralSyntaxToken)).toBe(true);
    expect(ref.current?.getSelection()).toEqual({ start: 0, end: value.length });

    const poundToken = container.querySelector<HTMLElement>(
      '.visible-text-editor__protected-token--icu-placeholder[data-raw="#"]',
    );
    expect(poundToken).toHaveAttribute('draggable', 'true');
    expect(poundToken).toHaveAttribute('contenteditable', 'false');

    act(() => {
      ref.current?.setSelection({ start: value.indexOf('#'), end: value.indexOf('#') });
    });

    expect(fireProseMirrorDragStart(poundToken!)).toBe(true);
    expect(ref.current?.getSelection()).toEqual({
      start: value.indexOf('#'),
      end: value.indexOf('#') + 1,
    });

    act(() => {
      ref.current?.setSelection({ start: value.indexOf('#'), end: value.indexOf('#') });
    });

    expect(ref.current?.getSelection()).toEqual({
      start: value.indexOf('#'),
      end: value.indexOf('#'),
    });
    expect(handleChange).not.toHaveBeenCalled();
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('moves a dragged whole ICU plural range when dropped in the same editor', async () => {
    const handleChange = vi.fn();
    const plural = '{count, plural, one {# file} other {# files}}';
    const value = `${plural} now`;
    const protectedTokens = extractIcuProtectedTextTokens(value);
    const { container } = render(
      <VisibleTextEditor
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        movableProtectedRanges={[
          {
            start: 0,
            end: plural.length,
            label: 'ICU plural message',
          },
        ]}
        protectedTokens={protectedTokens}
      />,
    );

    const editor = await screen.findByRole('textbox');
    const pluralSyntaxToken = await waitFor(() => {
      const element = Array.from(
        container.querySelectorAll('.visible-text-editor__protected-token'),
      ).find((token) => token.textContent?.includes('count'));
      expect(element).toBeDefined();
      return element as HTMLElement;
    });

    expect(fireProseMirrorDragStart(pluralSyntaxToken)).toBe(true);

    const restoreDropPosition = installDropPositionMock(editor, editor.childNodes.length);
    const restoreDropDom = installProseMirrorHistoryDomMock();
    try {
      fireEvent.drop(editor, {
        clientX: 1,
        clientY: 1,
        dataTransfer: {
          clearData: vi.fn(),
          dropEffect: 'move',
          effectAllowed: 'move',
          files: [],
          getData: (type: string) => (type === 'text/plain' ? plural : ''),
          setData: vi.fn(),
          types: ['text/plain'],
        },
      });
    } finally {
      restoreDropDom();
      restoreDropPosition();
    }

    expect(handleChange).toHaveBeenLastCalledWith(` now${plural}`);
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('keeps whole ICU plural drag/drop working after controlled token recomputation', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const plural = '{count, plural, one {# file} other {# files}}';
    const initialValue = `${plural} now`;
    const movedValue = ` now${plural}`;
    const valueChanges: string[] = [];
    const { container } = render(
      <ControlledIcuEditor
        editorRef={ref}
        initialValue={initialValue}
        onValueChange={(nextValue) => valueChanges.push(nextValue)}
      />,
    );

    const editor = await screen.findByRole('textbox');

    const dragPluralTo = async (dropOffset: number, dragText: string) => {
      const pluralSyntaxToken = await waitFor(() => {
        const element = Array.from(
          container.querySelectorAll('.visible-text-editor__protected-token'),
        ).find((token) => token.textContent?.includes('count'));
        expect(element).toBeDefined();
        return element as HTMLElement;
      });

      expect(fireProseMirrorDragStart(pluralSyntaxToken)).toBe(true);

      const restoreDropPosition = installDropPositionMock(editor, dropOffset);
      const restoreDropDom = installProseMirrorHistoryDomMock();
      try {
        fireEvent.drop(editor, {
          clientX: 1,
          clientY: 1,
          dataTransfer: {
            clearData: vi.fn(),
            dropEffect: 'move',
            effectAllowed: 'move',
            files: [],
            getData: (type: string) => (type === 'text/plain' ? dragText : ''),
            setData: vi.fn(),
            types: ['text/plain'],
          },
        });
      } finally {
        restoreDropDom();
        restoreDropPosition();
      }
    };

    await dragPluralTo(editor.childNodes.length, plural);
    await waitFor(() => expect(lastValue(valueChanges)).toBe(movedValue));

    await dragPluralTo(0, plural);
    await waitFor(() => expect(lastValue(valueChanges)).toBe(initialValue));
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('keeps undo and redo working after a controlled whole ICU plural drag', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const plural = '{count, plural, one {# file} other {# files}}';
    const initialValue = `${plural} now`;
    const movedValue = ` now${plural}`;
    const valueChanges: string[] = [];
    const { container } = render(
      <ControlledIcuEditor
        editorRef={ref}
        initialValue={initialValue}
        onValueChange={(nextValue) => valueChanges.push(nextValue)}
      />,
    );

    const editor = await screen.findByRole('textbox');
    const pluralSyntaxToken = await waitFor(() => {
      const element = Array.from(
        container.querySelectorAll('.visible-text-editor__protected-token'),
      ).find((token) => token.textContent?.includes('count'));
      expect(element).toBeDefined();
      return element as HTMLElement;
    });

    expect(fireProseMirrorDragStart(pluralSyntaxToken)).toBe(true);

    const restoreDropPosition = installDropPositionMock(editor, editor.childNodes.length);
    const restoreDropDom = installProseMirrorHistoryDomMock();
    try {
      fireEvent.drop(editor, {
        clientX: 1,
        clientY: 1,
        dataTransfer: {
          clearData: vi.fn(),
          dropEffect: 'move',
          effectAllowed: 'move',
          files: [],
          getData: (type: string) => (type === 'text/plain' ? plural : ''),
          setData: vi.fn(),
          types: ['text/plain'],
        },
      });
    } finally {
      restoreDropDom();
      restoreDropPosition();
    }

    await waitFor(() => expect(lastValue(valueChanges)).toBe(movedValue));

    const restoreHistoryDom = installProseMirrorHistoryDomMock();
    try {
      act(() => {
        expect(ref.current?.undo()).toBe(true);
      });
      await waitFor(() => expect(lastValue(valueChanges)).toBe(initialValue));

      act(() => {
        expect(ref.current?.redo()).toBe(true);
      });
      await waitFor(() => expect(lastValue(valueChanges)).toBe(movedValue));
    } finally {
      restoreHistoryDom();
    }
  });

  it('drops a moved ICU plural after a neighboring standalone placeholder token', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const plural = '{count, plural, one {# file} other {# files}}';
    const initialValue = `${plural} before {name}`;
    const movedValue = ` before {name}${plural}`;
    const valueChanges: string[] = [];
    const { container } = render(
      <ControlledIcuEditor
        editorRef={ref}
        initialValue={initialValue}
        onValueChange={(nextValue) => valueChanges.push(nextValue)}
      />,
    );

    const pluralSyntaxToken = await waitFor(() => {
      const element = Array.from(
        container.querySelectorAll('.visible-text-editor__protected-token'),
      ).find((token) => token.textContent?.includes('count'));
      expect(element).toBeDefined();
      return element as HTMLElement;
    });
    const nameToken = await waitFor(() => {
      const element = Array.from(
        container.querySelectorAll('.visible-text-editor__protected-token'),
      ).find((token) => token.textContent === 'name');
      expect(element).toBeDefined();
      return element as HTMLElement;
    });

    expect(fireProseMirrorDragStart(pluralSyntaxToken)).toBe(true);

    const elementFromPointDescriptor = Object.getOwnPropertyDescriptor(
      document,
      'elementFromPoint',
    );
    Object.defineProperty(document, 'elementFromPoint', {
      configurable: true,
      value: () => nameToken,
    });
    const restoreDropDom = installProseMirrorHistoryDomMock();
    try {
      fireEvent.drop(nameToken, {
        clientX: 1,
        clientY: 1,
        dataTransfer: {
          clearData: vi.fn(),
          dropEffect: 'move',
          effectAllowed: 'move',
          files: [],
          getData: (type: string) => (type === 'text/plain' ? plural : ''),
          setData: vi.fn(),
          types: ['text/plain'],
        },
      });
    } finally {
      restoreDropDom();
      restoreDescriptor(document, 'elementFromPoint', elementFromPointDescriptor);
    }

    await waitFor(() => expect(lastValue(valueChanges)).toBe(movedValue));
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('uses vertical token position for boundary drops in wrapped text', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const plural = '{count, plural, one {# file} other {# files}}';
    const initialValue = `${plural} before {name}`;
    const movedValue = ` before {name}${plural}`;
    const valueChanges: string[] = [];
    const { container } = render(
      <ControlledIcuEditor
        editorRef={ref}
        initialValue={initialValue}
        onValueChange={(nextValue) => valueChanges.push(nextValue)}
      />,
    );

    const pluralSyntaxToken = await waitFor(() => {
      const element = Array.from(
        container.querySelectorAll('.visible-text-editor__protected-token'),
      ).find((token) => token.textContent?.includes('count'));
      expect(element).toBeDefined();
      return element as HTMLElement;
    });
    const nameToken = await waitFor(() => {
      const element = Array.from(
        container.querySelectorAll('.visible-text-editor__protected-token'),
      ).find((token) => token.textContent === 'name');
      expect(element).toBeDefined();
      return element as HTMLElement;
    });

    expect(fireProseMirrorDragStart(pluralSyntaxToken)).toBe(true);

    const elementFromPointDescriptor = Object.getOwnPropertyDescriptor(
      document,
      'elementFromPoint',
    );
    const rectDescriptor = Object.getOwnPropertyDescriptor(nameToken, 'getBoundingClientRect');
    Object.defineProperty(document, 'elementFromPoint', {
      configurable: true,
      value: () => nameToken,
    });
    Object.defineProperty(nameToken, 'getBoundingClientRect', {
      configurable: true,
      value: () =>
        ({
          bottom: 10,
          height: 10,
          left: 0,
          right: 100,
          top: 0,
          width: 100,
          x: 0,
          y: 0,
          toJSON: () => ({}),
        }) as DOMRect,
    });
    const restoreDropDom = installProseMirrorHistoryDomMock();
    try {
      fireEvent.drop(nameToken, {
        clientX: 0,
        clientY: 20,
        dataTransfer: {
          clearData: vi.fn(),
          dropEffect: 'move',
          effectAllowed: 'move',
          files: [],
          getData: (type: string) => (type === 'text/plain' ? plural : ''),
          setData: vi.fn(),
          types: ['text/plain'],
        },
      });
    } finally {
      restoreDropDom();
      restoreDescriptor(nameToken, 'getBoundingClientRect', rectDescriptor);
      restoreDescriptor(document, 'elementFromPoint', elementFromPointDescriptor);
    }

    await waitFor(() => expect(lastValue(valueChanges)).toBe(movedValue));
  });

  it('drops a moved ICU plural after a neighboring ICU plural range when targeting its syntax', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const firstPlural = '{fileCount, plural, one {# file} other {# files}}';
    const secondPlural = '{folderCount, plural, one {# folder} other {# folders}}';
    const initialValue = `${firstPlural} then ${secondPlural}.`;
    const movedValue = ` then ${secondPlural}${firstPlural}.`;
    const valueChanges: string[] = [];
    const { container } = render(
      <ControlledIcuEditor
        editorRef={ref}
        initialValue={initialValue}
        onValueChange={(nextValue) => valueChanges.push(nextValue)}
      />,
    );

    const firstSyntaxToken = await waitFor(() => {
      const element = Array.from(
        container.querySelectorAll('.visible-text-editor__protected-token'),
      ).find((token) => token.textContent?.includes('fileCount'));
      expect(element).toBeDefined();
      return element as HTMLElement;
    });
    const secondSyntaxToken = await waitFor(() => {
      const element = Array.from(
        container.querySelectorAll('.visible-text-editor__protected-token'),
      ).find((token) => token.textContent?.includes('folderCount'));
      expect(element).toBeDefined();
      return element as HTMLElement;
    });

    expect(fireProseMirrorDragStart(firstSyntaxToken)).toBe(true);

    const elementFromPointDescriptor = Object.getOwnPropertyDescriptor(
      document,
      'elementFromPoint',
    );
    Object.defineProperty(document, 'elementFromPoint', {
      configurable: true,
      value: () => secondSyntaxToken,
    });
    const restoreDropDom = installProseMirrorHistoryDomMock();
    try {
      fireEvent.drop(secondSyntaxToken, {
        clientX: 1,
        clientY: 1,
        dataTransfer: {
          clearData: vi.fn(),
          dropEffect: 'move',
          effectAllowed: 'move',
          files: [],
          getData: (type: string) => (type === 'text/plain' ? firstPlural : ''),
          setData: vi.fn(),
          types: ['text/plain'],
        },
      });
    } finally {
      restoreDropDom();
      restoreDescriptor(document, 'elementFromPoint', elementFromPointDescriptor);
    }

    await waitFor(() => expect(lastValue(valueChanges)).toBe(movedValue));
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('repeats boundary drops between neighboring ICU plural ranges after controlled recomputation', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const firstPlural = '{fileCount, plural, one {# file} other {# files}}';
    const secondPlural = '{folderCount, plural, one {# folder} other {# folders}}';
    const initialValue = `${firstPlural} then ${secondPlural}.`;
    const firstMovedValue = ` then ${secondPlural}${firstPlural}.`;
    const secondMovedValue = ` then ${firstPlural}${secondPlural}.`;
    const valueChanges: string[] = [];
    const { container } = render(
      <ControlledIcuEditor
        editorRef={ref}
        initialValue={initialValue}
        onValueChange={(nextValue) => valueChanges.push(nextValue)}
      />,
    );

    async function dropPluralAfterToken(
      dragTokenText: string,
      targetTokenText: string,
      raw: string,
    ) {
      const dragToken = await waitFor(() => {
        const element = Array.from(
          container.querySelectorAll('.visible-text-editor__protected-token'),
        ).find((token) => token.textContent?.includes(dragTokenText));
        expect(element).toBeDefined();
        return element as HTMLElement;
      });
      const targetToken = await waitFor(() => {
        const element = Array.from(
          container.querySelectorAll('.visible-text-editor__protected-token'),
        ).find((token) => token.textContent?.includes(targetTokenText));
        expect(element).toBeDefined();
        return element as HTMLElement;
      });

      expect(fireProseMirrorDragStart(dragToken)).toBe(true);

      const elementFromPointDescriptor = Object.getOwnPropertyDescriptor(
        document,
        'elementFromPoint',
      );
      Object.defineProperty(document, 'elementFromPoint', {
        configurable: true,
        value: () => targetToken,
      });
      const restoreDropDom = installProseMirrorHistoryDomMock();
      try {
        fireEvent.drop(targetToken, {
          clientX: 1,
          clientY: 1,
          dataTransfer: {
            clearData: vi.fn(),
            dropEffect: 'move',
            effectAllowed: 'move',
            files: [],
            getData: (type: string) => (type === 'text/plain' ? raw : ''),
            setData: vi.fn(),
            types: ['text/plain'],
          },
        });
      } finally {
        restoreDropDom();
        restoreDescriptor(document, 'elementFromPoint', elementFromPointDescriptor);
      }
    }

    await dropPluralAfterToken('fileCount', 'folderCount', firstPlural);
    await waitFor(() => expect(lastValue(valueChanges)).toBe(firstMovedValue));

    await dropPluralAfterToken('folderCount', 'fileCount', secondPlural);
    await waitFor(() => expect(lastValue(valueChanges)).toBe(secondMovedValue));
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('uses any ICU plural syntax chip as a whole-range drag handle', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = '{count, plural, one {# file} other {# files}}';
    const protectedTokens = extractIcuProtectedTextTokens(value);
    const { container } = render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        movableProtectedRanges={[
          {
            start: 0,
            end: value.length,
            label: 'ICU plural message',
          },
        ]}
        protectedTokens={protectedTokens}
      />,
    );

    const { middleSyntaxToken, closingSyntaxToken } = await waitFor(() => {
      const element = Array.from(
        container.querySelectorAll('.visible-text-editor__protected-token'),
      );
      const middleToken = element.find((token) => token.getAttribute('data-raw') === '} other {');
      const closingToken = element.find((token) => token.getAttribute('data-raw') === '}}');
      expect(middleToken).toBeDefined();
      expect(closingToken).toBeDefined();
      return {
        middleSyntaxToken: middleToken as HTMLElement,
        closingSyntaxToken: closingToken as HTMLElement,
      };
    });

    act(() => {
      ref.current?.setSelection({ start: 0, end: 0 });
    });

    expect(fireProseMirrorDragStart(middleSyntaxToken)).toBe(true);
    expect(ref.current?.getSelection()).toEqual({ start: 0, end: value.length });

    act(() => {
      ref.current?.setSelection({ start: 0, end: 0 });
    });

    expect(fireProseMirrorDragStart(closingSyntaxToken)).toBe(true);
    expect(ref.current?.getSelection()).toEqual({ start: 0, end: value.length });
    expect(handleChange).not.toHaveBeenCalled();
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('blocks dragging ICU syntax pieces that are not in a movable range', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = '{count, plural, one {# file} other {# files}}';
    const protectedTokens = extractIcuProtectedTextTokens(value);
    const { container } = render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        protectedTokens={protectedTokens}
      />,
    );

    const syntaxToken = await waitFor(() => {
      const element = Array.from(
        container.querySelectorAll('.visible-text-editor__protected-token'),
      ).find((token) => token.textContent?.includes('count'));
      expect(element).toBeDefined();
      return element as HTMLElement;
    });

    act(() => {
      ref.current?.setSelection({ start: 0, end: 0 });
    });

    expect(fireProseMirrorDragStart(syntaxToken)).toBe(false);
    expect(handleChange).not.toHaveBeenCalled();
    expect(
      await screen.findByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).toHaveAttribute('aria-live', 'polite');
  });

  it('blocks dragging selected partial ICU syntax ranges', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = '{count, plural, one {# file} other {# files}}';
    const protectedTokens = extractIcuProtectedTextTokens(value);

    render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        movableProtectedRanges={[
          {
            start: 0,
            end: value.length,
            label: 'ICU plural message',
          },
        ]}
        protectedTokens={protectedTokens}
      />,
    );

    const editor = await screen.findByRole('textbox');
    await waitFor(() => expect(ref.current).not.toBeNull());
    const syntaxStart = value.indexOf('} other {');

    act(() => {
      ref.current?.setSelection({ start: syntaxStart, end: syntaxStart + '} other {'.length });
    });

    expect(fireProseMirrorDragStart(editor)).toBe(false);
    expect(handleChange).not.toHaveBeenCalled();
    expect(
      await screen.findByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).toHaveAttribute('aria-live', 'polite');
  });

  it('allows dragging a selected whole ICU plural range', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = '{count, plural, one {# file} other {# files}}';
    const protectedTokens = extractIcuProtectedTextTokens(value);

    const { container } = render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        movableProtectedRanges={[
          {
            start: 0,
            end: value.length,
            label: 'ICU plural message',
          },
        ]}
        protectedTokens={protectedTokens}
      />,
    );

    const editor = await screen.findByRole('textbox');
    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: 0, end: value.length });
    });

    expect(fireProseMirrorDragStart(editor)).toBe(true);
    const pluralToken = await waitFor(() => {
      const element = Array.from(
        container.querySelectorAll('.visible-text-editor__protected-token'),
      ).find((token) => token.textContent?.includes('count'));
      expect(element).toBeDefined();
      return element as HTMLElement;
    });
    expect(fireProseMirrorDragStart(pluralToken)).toBe(true);
    expect(ref.current?.getSelection()).toEqual({ start: 0, end: value.length });
    expect(handleChange).not.toHaveBeenCalled();
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('allows dragging a selection that contains a locked token', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = 'Hello {name} friend';
    const protectedTokens = extractIcuProtectedTextTokens(value);

    render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        protectedTokens={protectedTokens}
      />,
    );

    const editor = await screen.findByRole('textbox');
    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: 6, end: 12 });
    });

    expect(fireProseMirrorDragStart(editor)).toBe(true);
    expect(handleChange).not.toHaveBeenCalled();
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('does not block dragging ordinary text selections', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();

    render(
      <VisibleTextEditor
        ref={ref}
        value="Hello friend"
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: 0,
          rawMode: false,
        }}
        protectedTokens={[]}
      />,
    );

    const editor = await screen.findByRole('textbox');
    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: 0, end: 5 });
    });

    expect(fireProseMirrorDragStart(editor)).toBe(true);
    expect(handleChange).not.toHaveBeenCalled();
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('blocks dropping ordinary text directly on a locked token target', async () => {
    const handleChange = vi.fn();
    const value = 'Hello {name} friend';
    const protectedTokens = extractIcuProtectedTextTokens(value);
    const { container } = render(
      <VisibleTextEditor
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        protectedTokens={protectedTokens}
      />,
    );

    const protectedToken = await waitFor(() => {
      const element = container.querySelector('.visible-text-editor__protected-token');
      expect(element).toHaveTextContent('name');
      return element as HTMLElement;
    });

    const elementFromPointDescriptor = Object.getOwnPropertyDescriptor(
      document,
      'elementFromPoint',
    );
    Object.defineProperty(document, 'elementFromPoint', {
      configurable: true,
      value: () => protectedToken,
    });
    const restoreDropDom = installProseMirrorHistoryDomMock();

    try {
      fireEvent.drop(protectedToken, {
        clientX: 1,
        clientY: 1,
        dataTransfer: {
          clearData: vi.fn(),
          dropEffect: 'copy',
          effectAllowed: 'copy',
          files: [],
          getData: (type: string) => (type === 'text/plain' ? 'Alice' : ''),
          setData: vi.fn(),
          types: ['text/plain'],
        },
      });
    } finally {
      restoreDropDom();
      restoreDescriptor(document, 'elementFromPoint', elementFromPointDescriptor);
    }

    expect(handleChange).not.toHaveBeenCalled();
    expect(
      await screen.findByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).toHaveAttribute('aria-live', 'polite');
  });

  it('shows blocked drop feedback when dragging over a locked placeholder target', async () => {
    const value = 'Hello {name} friend';
    const protectedTokens = extractIcuProtectedTextTokens(value);
    const { container } = render(
      <VisibleTextEditor
        value={value}
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        protectedTokens={protectedTokens}
      />,
    );

    const editor = await screen.findByRole('textbox');
    const protectedToken = await waitFor(() => {
      const element = container.querySelector('.visible-text-editor__protected-token');
      expect(element).toHaveTextContent('name');
      return element as HTMLElement;
    });
    const dataTransfer = {
      dropEffect: 'copy',
      effectAllowed: 'copyMove',
    };
    const elementFromPointDescriptor = Object.getOwnPropertyDescriptor(
      document,
      'elementFromPoint',
    );
    Object.defineProperty(document, 'elementFromPoint', {
      configurable: true,
      value: () => protectedToken,
    });
    const restoreDragDom = installProseMirrorHistoryDomMock();

    try {
      fireEvent.dragOver(protectedToken, {
        clientX: 1,
        clientY: 1,
        dataTransfer,
      });
    } finally {
      restoreDragDom();
      restoreDescriptor(document, 'elementFromPoint', elementFromPointDescriptor);
    }

    expect(editor).toHaveClass('visible-text-editor__editor--drop-blocked');
    expect(editor).not.toHaveClass('visible-text-editor__editor--drop-allowed');
    expect(protectedToken).toHaveClass('visible-text-editor__protected-token--drop-blocked');
    expect(dataTransfer.dropEffect).toBe('none');

    fireEvent.dragLeave(editor, { relatedTarget: document.body });

    expect(editor).not.toHaveClass('visible-text-editor__editor--drop-blocked');
    expect(protectedToken).not.toHaveClass('visible-text-editor__protected-token--drop-blocked');
  });

  it('shows boundary drop feedback when dragging a protected range over a locked placeholder', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const plural = '{count, plural, one {# file} other {# files}}';
    const value = `${plural} before {name}`;
    const { container } = render(
      <ControlledIcuEditor editorRef={ref} initialValue={value} onValueChange={vi.fn()} />,
    );

    const editor = await screen.findByRole('textbox');
    const pluralSyntaxToken = await waitFor(() => {
      const element = Array.from(
        container.querySelectorAll('.visible-text-editor__protected-token'),
      ).find((token) => token.textContent?.includes('count'));
      expect(element).toBeDefined();
      return element as HTMLElement;
    });
    const nameToken = await waitFor(() => {
      const element = Array.from(
        container.querySelectorAll('.visible-text-editor__protected-token'),
      ).find((token) => token.textContent === 'name');
      expect(element).toBeDefined();
      return element as HTMLElement;
    });
    const dataTransfer = {
      dropEffect: 'copy',
      effectAllowed: 'copyMove',
    };

    expect(fireProseMirrorDragStart(pluralSyntaxToken)).toBe(true);

    const elementFromPointDescriptor = Object.getOwnPropertyDescriptor(
      document,
      'elementFromPoint',
    );
    Object.defineProperty(document, 'elementFromPoint', {
      configurable: true,
      value: () => nameToken,
    });
    const restoreDragDom = installProseMirrorHistoryDomMock();

    try {
      fireEvent.dragOver(nameToken, {
        clientX: 1,
        clientY: 1,
        dataTransfer,
      });
    } finally {
      restoreDragDom();
      restoreDescriptor(document, 'elementFromPoint', elementFromPointDescriptor);
    }

    expect(editor).toHaveClass('visible-text-editor__editor--drop-allowed');
    expect(editor).not.toHaveClass('visible-text-editor__editor--drop-blocked');
    expect(nameToken).toHaveClass('visible-text-editor__protected-token--drop-boundary');
    expect(nameToken).not.toHaveClass('visible-text-editor__protected-token--drop-blocked');
    expect(dataTransfer.dropEffect).toBe('move');

    fireEvent.dragLeave(editor, { relatedTarget: document.body });

    expect(editor).not.toHaveClass('visible-text-editor__editor--drop-allowed');
    expect(nameToken).not.toHaveClass('visible-text-editor__protected-token--drop-boundary');
  });

  it('shows allowed drop feedback away from locked placeholders', async () => {
    const value = 'Hello {name} friend';
    const protectedTokens = extractIcuProtectedTextTokens(value);
    const { container } = render(
      <VisibleTextEditor
        value={value}
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        protectedTokens={protectedTokens}
      />,
    );

    const editor = await screen.findByRole('textbox');
    await waitFor(() => {
      expect(container.querySelector('.visible-text-editor__protected-token')).toBeInTheDocument();
    });
    const elementFromPointDescriptor = Object.getOwnPropertyDescriptor(
      document,
      'elementFromPoint',
    );
    Object.defineProperty(document, 'elementFromPoint', {
      configurable: true,
      value: () => editor,
    });
    const restoreDragDom = installProseMirrorHistoryDomMock();

    try {
      fireEvent.dragEnter(editor, {
        clientX: 1,
        clientY: 1,
        dataTransfer: {
          dropEffect: 'copy',
          effectAllowed: 'copyMove',
        },
      });
    } finally {
      restoreDragDom();
      restoreDescriptor(document, 'elementFromPoint', elementFromPointDescriptor);
    }

    expect(editor).toHaveClass('visible-text-editor__editor--drop-allowed');
    expect(editor).not.toHaveClass('visible-text-editor__editor--drop-blocked');
  });

  it('allows adding a new ICU placeholder outside existing protected tokens', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = 'Hello {name}.';
    const protectedTokens = extractIcuProtectedTextTokens(value);

    render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        protectedTokens={protectedTokens}
        validateNextValue={(nextValue) =>
          preservesProtectedTextTokenStructure({
            previousValue: value,
            previousTokens: protectedTokens,
            nextValue,
            mode: 'icu',
          })
        }
      />,
    );

    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: value.length, end: value.length });
      ref.current?.insertText(' Pay {price}.');
    });

    expect(handleChange).toHaveBeenLastCalledWith('Hello {name}. Pay {price}.');
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('allows typing a partial ICU placeholder after an existing plural', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = 'Delete {count, plural, one {# app} other {# apps}} forever.';
    const protectedTokens = extractIcuProtectedTextTokens(value);

    render(
      <VisibleTextEditor
        ref={ref}
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{
          onToggleRawMode: vi.fn(),
          protectedTokenCount: protectedTokens.length,
          rawMode: false,
        }}
        protectedTokens={protectedTokens}
        validateNextValue={(nextValue) =>
          preservesProtectedTextTokenStructure({
            previousValue: value,
            previousTokens: protectedTokens,
            nextValue,
            mode: 'icu',
          })
        }
      />,
    );

    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: value.length, end: value.length });
      ref.current?.insertText(' {draft');
    });

    expect(handleChange).toHaveBeenLastCalledWith(`${value} {draft`);
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('omits empty protected-token status text from the compact bar', () => {
    render(
      <VisibleTextEditor
        value="Hello"
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          protectedTokenCount: 0,
        }}
      />,
    );

    expect(screen.queryByText('No tokens found')).not.toBeInTheDocument();
  });

  it('disables compact control bar actions when the editor is disabled', () => {
    const value = '{count, plural, one {# file} other {# files}}';
    const { container } = render(
      <VisibleTextEditor
        value={value}
        onChange={vi.fn()}
        showInvisibles={false}
        disabled
        controlBar={{
          icuExactFormInsertions: [
            {
              id: '0:exact-value',
              kind: 'exact-value',
              label: 'count: exact value...',
              messageStart: 0,
              messageEnd: 46,
              selectionStart: 28,
              selectionEnd: 28,
              existingForms: [],
            },
          ],
          onAddIcuForm: vi.fn(),
          marksMode: 'auto',
          onChangeMarksMode: vi.fn(),
          onToggleRawMode: vi.fn(),
          rawMode: false,
        }}
        protectedTokens={extractIcuProtectedTextTokens(value)}
      />,
    );

    expect(screen.getByRole('button', { name: 'Hidden characters: Auto' })).toBeDisabled();
    expect(
      screen.getByRole('button', {
        name: 'Placeholder editing is off. Edit placeholders',
      }),
    ).toBeDisabled();
    const formTrigger = container.querySelector(
      '.visible-text-editor__protected-token--icu-syntax',
    );
    expect(formTrigger).toHaveTextContent('count one');
    openIcuFormMenu(container);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('changes marks mode from the integrated hidden characters menu', () => {
    const handleChangeMarksMode = vi.fn();
    render(
      <VisibleTextEditor
        value="Hello  world"
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          marksMode: 'auto',
          onChangeMarksMode: handleChangeMarksMode,
        }}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Hidden characters: Auto' }));
    expect(screen.getByRole('listbox', { name: 'Hidden characters' })).toBeInTheDocument();
    expect(screen.getByRole('textbox').closest('.visible-text-editor')).toHaveClass(
      'visible-text-editor--menu-open',
    );

    fireEvent.click(screen.getByRole('option', { name: 'All' }));

    expect(handleChangeMarksMode).toHaveBeenCalledWith('all');
    expect(screen.queryByRole('listbox', { name: 'Hidden characters' })).not.toBeInTheDocument();
  });

  it('collects an exact ICU plural value from the inline form menu', async () => {
    const value = '{count, plural, one {# file} other {# files}}';
    const handleAddIcuForm = vi.fn().mockReturnValue({ ok: true });
    const { container } = render(
      <VisibleTextEditor
        value={value}
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          icuExactFormInsertions: [
            {
              id: '0:exact-value',
              kind: 'exact-value',
              label: 'count: exact value...',
              messageStart: 0,
              messageEnd: 46,
              selectionStart: 28,
              selectionEnd: 28,
              existingForms: [],
            },
          ],
          onAddIcuForm: handleAddIcuForm,
        }}
        protectedTokens={extractIcuProtectedTextTokens(value)}
      />,
    );

    openIcuFormMenu(container);
    fireEvent.click(screen.getByRole('menuitem', { name: 'count: exact value...' }));
    const exactValueInput = await screen.findByLabelText('Exact ICU plural value');

    expect(screen.getByRole('menu')).toContainElement(exactValueInput);
    expect(screen.getByLabelText('Text editor controls')).not.toContainElement(exactValueInput);

    fireEvent.change(exactValueInput, {
      target: { value: '0' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));

    expect(handleAddIcuForm).toHaveBeenCalledWith('0:exact-value', '0');
  });

  it('opens the ICU form menu from later form labels', () => {
    const value = '{count, plural, one {# file} other {# files}}';
    const { container } = render(
      <VisibleTextEditor
        value={value}
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          icuFormOptions: [
            {
              id: '0:few',
              checked: false,
              disabled: false,
              form: 'few',
              label: 'count: few',
              messageType: 'plural',
              nextValue: '{count, plural, one {# file} few {# } other {# files}}',
              messageStart: 0,
              messageEnd: 46,
              selectionStart: 35,
              selectionEnd: 35,
            },
          ],
          onToggleIcuForm: vi.fn(),
        }}
        protectedTokens={extractIcuProtectedTextTokens(value)}
      />,
    );

    const otherTrigger = Array.from(
      container.querySelectorAll<HTMLElement>('.visible-text-editor__protected-token--icu-syntax'),
    ).find((token) => token.textContent === 'other');
    expect(otherTrigger).toBeInTheDocument();

    fireEvent.click(otherTrigger as HTMLElement);

    expect(screen.getByRole('menu', { name: /count plural forms/ })).toBeInTheDocument();
  });

  it('clears the exact ICU plural input when its insertion is removed', async () => {
    const exactInsertion = {
      id: '0:exact-value',
      kind: 'exact-value' as const,
      label: 'count: exact value...',
      messageStart: 0,
      messageEnd: 46,
      selectionStart: 28,
      selectionEnd: 28,
      existingForms: [],
    };
    const handleAddIcuForm = vi.fn().mockReturnValue({ ok: true });
    const value = '{count, plural, one {# file} other {# files}}';
    const { container, rerender } = render(
      <VisibleTextEditor
        value={value}
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          icuExactFormInsertions: [exactInsertion],
          onAddIcuForm: handleAddIcuForm,
          rawMode: false,
        }}
        protectedTokens={extractIcuProtectedTextTokens(value)}
      />,
    );

    openIcuFormMenu(container);
    fireEvent.click(screen.getByRole('menuitem', { name: 'count: exact value...' }));

    expect(await screen.findByLabelText('Exact ICU plural value')).toBeInTheDocument();

    rerender(
      <VisibleTextEditor
        value={value}
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          icuExactFormInsertions: [],
          onAddIcuForm: undefined,
          rawMode: true,
        }}
        protectedTokens={extractIcuProtectedTextTokens(value)}
      />,
    );

    await waitFor(() =>
      expect(screen.queryByLabelText('Exact ICU plural value')).not.toBeInTheDocument(),
    );

    rerender(
      <VisibleTextEditor
        value={value}
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          icuExactFormInsertions: [exactInsertion],
          onAddIcuForm: handleAddIcuForm,
          rawMode: false,
        }}
        protectedTokens={extractIcuProtectedTextTokens(value)}
      />,
    );

    expect(screen.queryByLabelText('Exact ICU plural value')).not.toBeInTheDocument();
  });

  it('shows exact ICU plural value errors in the inline form menu', async () => {
    const value = '{count, plural, one {# file} other {# files}}';
    const handleAddIcuForm = vi.fn().mockReturnValue({
      ok: false,
      error: 'Enter a non-negative integer.',
    });
    const { container } = render(
      <VisibleTextEditor
        value={value}
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          icuExactFormInsertions: [
            {
              id: '0:exact-value',
              kind: 'exact-value',
              label: 'count: exact value...',
              messageStart: 0,
              messageEnd: 46,
              selectionStart: 28,
              selectionEnd: 28,
              existingForms: [],
            },
          ],
          onAddIcuForm: handleAddIcuForm,
        }}
        protectedTokens={extractIcuProtectedTextTokens(value)}
      />,
    );

    openIcuFormMenu(container);
    fireEvent.click(screen.getByRole('menuitem', { name: 'count: exact value...' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));

    expect(await screen.findByText('Enter a non-negative integer.')).toHaveAttribute(
      'aria-live',
      'polite',
    );
  });

  it('shows category ICU form errors in the inline form menu', async () => {
    const value = '{count, plural, one {# file} other {# files}}';
    const handleToggleIcuForm = vi.fn().mockReturnValue({
      ok: false,
      error: 'That ICU form is no longer available.',
    });
    const { container } = render(
      <VisibleTextEditor
        value={value}
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          icuFormOptions: [
            {
              id: '0:few',
              checked: false,
              disabled: false,
              form: 'few',
              label: 'count: few',
              messageType: 'plural',
              nextValue: '{count, plural, one {# file} few {# } other {# files}}',
              messageStart: 0,
              messageEnd: 46,
              selectionStart: 35,
              selectionEnd: 35,
            },
          ],
          onToggleIcuForm: handleToggleIcuForm,
        }}
        protectedTokens={extractIcuProtectedTextTokens(value)}
      />,
    );

    openIcuFormMenu(container);
    fireEvent.click(screen.getByRole('checkbox', { name: /few/ }));

    expect(await screen.findByText('That ICU form is no longer available.')).toHaveAttribute(
      'aria-live',
      'polite',
    );
  });

  it('clears exact ICU plural value errors after adding a category form', async () => {
    const handleAddIcuForm = vi.fn().mockReturnValue({
      ok: false,
      error: 'Enter a non-negative integer.',
    });
    const handleToggleIcuForm = vi.fn().mockReturnValue({ ok: true });
    const value = '{count, plural, one {# file} other {# files}}';
    const { container } = render(
      <VisibleTextEditor
        value={value}
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          icuExactFormInsertions: [
            {
              id: '0:exact-value',
              kind: 'exact-value',
              label: 'count: exact value...',
              messageStart: 0,
              messageEnd: 46,
              selectionStart: 28,
              selectionEnd: 28,
              existingForms: [],
            },
          ],
          icuFormOptions: [
            {
              id: '0:few',
              checked: false,
              disabled: false,
              form: 'few',
              label: 'count: few',
              messageType: 'plural',
              nextValue: '{count, plural, one {# file} few {# } other {# files}}',
              messageStart: 0,
              messageEnd: 46,
              selectionStart: 35,
              selectionEnd: 35,
            },
          ],
          onAddIcuForm: handleAddIcuForm,
          onToggleIcuForm: handleToggleIcuForm,
          protectedTokenCount: 5,
        }}
        protectedTokens={extractIcuProtectedTextTokens(value)}
      />,
    );

    openIcuFormMenu(container);
    fireEvent.click(screen.getByRole('menuitem', { name: 'count: exact value...' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));

    expect(await screen.findByText('Enter a non-negative integer.')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('checkbox', { name: /few/ }));

    expect(screen.queryByText('Enter a non-negative integer.')).not.toBeInTheDocument();
    expect(screen.queryByText('5 tokens found')).not.toBeInTheDocument();
  });
});
