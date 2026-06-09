import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createRef, type Ref, useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { useProtectedTextTokenGuard } from '../hooks/useProtectedTextTokenGuard';
import { TranslationTextEditor } from './TranslationTextEditor';
import type { VisibleTextEditorHandle } from './VisibleTextEditor';

function getAddFormOptions() {
  return within(screen.getByRole('combobox', { name: /Add .* form/ }))
    .getAllByRole('option')
    .map((option) => option.textContent);
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

function ControlledTranslationTextEditor({
  editorRef,
  initialValue,
  onValueChange,
}: {
  editorRef: Ref<VisibleTextEditorHandle>;
  initialValue: string;
  onValueChange: (value: string) => void;
}) {
  const [value, setValue] = useState(initialValue);

  return (
    <TranslationTextEditor
      ref={editorRef}
      assisted
      value={value}
      onChange={(nextValue) => {
        onValueChange(nextValue);
        setValue(nextValue);
      }}
      showInvisibles={false}
      controlBar={{}}
    />
  );
}

function GuardedTranslationTextEditor({
  editorRef,
  initialValue,
  onValueChange,
}: {
  editorRef: Ref<VisibleTextEditorHandle>;
  initialValue: string;
  onValueChange: (value: string) => void;
}) {
  const [value, setValue] = useState(initialValue);
  const guard = useProtectedTextTokenGuard(value, 'icu-html');

  return (
    <TranslationTextEditor
      ref={editorRef}
      assisted
      value={value}
      onChange={(nextValue) => {
        onValueChange(nextValue);
        setValue(nextValue);
      }}
      showInvisibles={false}
      controlBar={{ protectedTokenCount: guard.protectedTokens.length }}
      protectedDiagnostics={guard.diagnostics}
      protectedTokens={guard.protectedTokens}
      validateNextValue={guard.validateNextValue}
    />
  );
}

describe('TranslationTextEditor', () => {
  it('scopes add-form actions to the innermost ICU plural at the caret', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const value =
      '{count, plural, one {{itemCount, plural, one {# item} other {# items}}} other {# groups}}';

    render(
      <TranslationTextEditor
        ref={ref}
        assisted
        value={value}
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{}}
      />,
    );

    await screen.findByRole('combobox', { name: 'Add plural form' });

    act(() => {
      const nestedCaret = value.indexOf('item}') + 'it'.length;
      ref.current?.setSelection({ start: nestedCaret, end: nestedCaret });
    });

    await waitFor(() => {
      expect(getAddFormOptions()).toContain('itemCount: zero');
      expect(getAddFormOptions()).not.toContain('count: zero');
    });
  });

  it('does not scope add-form actions to a plural when the caret is after it', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const value =
      '{fileCount, plural, one {# file} other {# files}} and {folderCount, plural, one {# folder} other {# folders}}';

    render(
      <TranslationTextEditor
        ref={ref}
        assisted
        value={value}
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{}}
      />,
    );

    await screen.findByRole('combobox', { name: 'Add plural form' });

    act(() => {
      const afterFirstPlural = value.indexOf(' and');
      ref.current?.setSelection({ start: afterFirstPlural, end: afterFirstPlural });
    });

    await waitFor(() => {
      expect(getAddFormOptions()).toContain('fileCount: zero');
      expect(getAddFormOptions()).toContain('folderCount: zero');
    });
  });

  it('offers only the required other form for ICU select messages', async () => {
    const handleChange = vi.fn();
    const value = '{gender, select, male {his}} file';

    render(
      <TranslationTextEditor
        assisted
        value={value}
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{}}
      />,
    );

    await screen.findByRole('combobox', { name: 'Add select form' });

    expect(getAddFormOptions()).toEqual(['Add select form', 'gender: other']);

    fireEvent.change(screen.getByLabelText('Add select form'), {
      target: { value: '0:other' },
    });

    expect(handleChange).toHaveBeenCalledWith('{gender, select, male {his} other { } } file');
  });

  it('keeps ICU form insertion undoable by the editor', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = '{count, plural, one {# file} other {# files}}';
    const insertedValue = '{count, plural, one {# file} few {# } other {# files}}';

    render(
      <ControlledTranslationTextEditor
        editorRef={ref}
        initialValue={value}
        onValueChange={handleChange}
      />,
    );

    await screen.findByRole('combobox', { name: 'Add plural form' });

    fireEvent.change(screen.getByLabelText('Add plural form'), {
      target: { value: '0:few' },
    });

    await waitFor(() => expect(handleChange).toHaveBeenLastCalledWith(insertedValue));

    const restoreDom = installProseMirrorHistoryDomMock();
    try {
      act(() => {
        expect(ref.current?.undo()).toBe(true);
      });
    } finally {
      restoreDom();
    }

    await waitFor(() => expect(handleChange).toHaveBeenLastCalledWith(value));
  });

  it.each([
    { label: 'Command', modifier: { metaKey: true } },
    { label: 'Ctrl', modifier: { ctrlKey: true } },
  ])('keeps ICU form insertion undoable with the $label shortcut', async ({ modifier }) => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = '{count, plural, one {# file} other {# files}}';
    const insertedValue = '{count, plural, one {# file} few {# } other {# files}}';

    render(
      <ControlledTranslationTextEditor
        editorRef={ref}
        initialValue={value}
        onValueChange={handleChange}
      />,
    );

    await screen.findByRole('combobox', { name: 'Add plural form' });
    const editor = screen.getByRole('textbox', { name: 'Text editor' });

    fireEvent.change(screen.getByLabelText('Add plural form'), {
      target: { value: '0:few' },
    });

    await waitFor(() => expect(handleChange).toHaveBeenLastCalledWith(insertedValue));

    const restoreDom = installProseMirrorHistoryDomMock();
    try {
      act(() => {
        editor.focus();
        fireEvent.keyDown(editor, {
          code: 'KeyZ',
          key: 'z',
          ...modifier,
        });
      });
    } finally {
      restoreDom();
    }

    await waitFor(() => expect(handleChange).toHaveBeenLastCalledWith(value));

    const restoreRedoDom = installProseMirrorHistoryDomMock();
    try {
      act(() => {
        editor.focus();
        fireEvent.keyDown(editor, {
          code: 'KeyZ',
          key: 'z',
          ...modifier,
          shiftKey: true,
        });
      });
    } finally {
      restoreRedoDom();
    }

    await waitFor(() => expect(handleChange).toHaveBeenLastCalledWith(insertedValue));
  });

  it('allows closing a transient ICU placeholder draft after an existing plural', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = 'Delete {count, plural, one {# app} other {# apps}} forever.';
    const partialValue = `${value} {draft`;
    const closedValue = `${value} {draft}`;

    const { container } = render(
      <GuardedTranslationTextEditor
        editorRef={ref}
        initialValue={value}
        onValueChange={handleChange}
      />,
    );

    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: value.length, end: value.length });
      ref.current?.insertText(' {draft');
    });

    await waitFor(() => expect(handleChange).toHaveBeenLastCalledWith(partialValue));
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();

    act(() => {
      ref.current?.insertText('}');
    });

    await waitFor(() => expect(handleChange).toHaveBeenLastCalledWith(closedValue));
    await waitFor(() => {
      expect(
        Array.from(container.querySelectorAll('.visible-text-editor__protected-token')).some(
          (token) => token.textContent === '{draft}',
        ),
      ).toBe(true);
    });
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
  });

  it('allows token edits after switching to unprotected mode', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();

    render(
      <TranslationTextEditor
        ref={ref}
        assisted
        value="Hello {name}"
        onChange={handleChange}
        showInvisibles={false}
        controlBar={{ protectedTokenCount: 1 }}
        protectedTokens={[
          {
            start: 6,
            end: 12,
            label: 'ICU argument name',
            kind: 'icu-placeholder',
          },
        ]}
        validateNextValue={() => false}
      />,
    );

    await waitFor(() => expect(ref.current).not.toBeNull());

    act(() => {
      ref.current?.setSelection({ start: 6, end: 12 });
      ref.current?.insertText('Alice');
    });

    expect(handleChange).not.toHaveBeenCalled();
    expect(
      await screen.findByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).toBeInTheDocument();

    fireEvent.click(
      screen.getByRole('button', {
        name: 'Placeholder editing is off. Edit placeholders',
      }),
    );

    expect(
      await screen.findByRole('button', {
        name: 'Placeholder editing is on. Lock placeholders',
      }),
    ).toHaveTextContent('Lock placeholders');

    act(() => {
      ref.current?.setSelection({ start: 6, end: 12 });
      ref.current?.insertText('Alice');
    });

    expect(handleChange).toHaveBeenCalledWith('Hello Alice');
  });

  it('keeps placeholder diagnostics visible while placeholder editing is enabled', async () => {
    render(
      <TranslationTextEditor
        assisted
        value="Broken %1$ placeholder"
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{}}
        protectedDiagnostics={[
          {
            start: 7,
            end: 10,
            severity: 'warning',
            code: 'placeholder-malformed',
            message: 'Placeholder-like sequence %1$ is incomplete or malformed.',
          },
        ]}
        protectedTokens={[
          {
            start: 7,
            end: 10,
            label: 'Platform placeholder',
            kind: 'platform-placeholder',
          },
        ]}
      />,
    );

    expect(await screen.findByText('%1$')).toHaveClass(
      'visible-text-editor__diagnostic--placeholder-malformed',
    );

    fireEvent.click(
      screen.getByRole('button', {
        name: 'Placeholder editing is off. Edit placeholders',
      }),
    );

    expect(
      await screen.findByRole('button', {
        name: 'Placeholder editing is on. Lock placeholders',
      }),
    ).toBeInTheDocument();
    expect(await screen.findByText('%1$')).toHaveClass(
      'visible-text-editor__diagnostic--placeholder-malformed',
    );
  });

  it('resets placeholder editing when assisted editing is disabled and reenabled', async () => {
    const protectedTokens = [
      {
        start: 6,
        end: 12,
        label: 'ICU argument name',
        kind: 'icu-placeholder' as const,
      },
    ];
    const props = {
      value: 'Hello {name}',
      onChange: vi.fn(),
      showInvisibles: false,
      controlBar: { protectedTokenCount: protectedTokens.length },
      protectedTokens,
    };
    const { rerender } = render(<TranslationTextEditor assisted {...props} />);

    fireEvent.click(
      await screen.findByRole('button', {
        name: 'Placeholder editing is off. Edit placeholders',
      }),
    );

    expect(
      await screen.findByRole('button', {
        name: 'Placeholder editing is on. Lock placeholders',
      }),
    ).toHaveTextContent('Lock placeholders');

    rerender(<TranslationTextEditor assisted={false} {...props} />);
    await waitFor(() => {
      expect(
        screen.queryByRole('button', {
          name: 'Placeholder editing is on. Lock placeholders',
        }),
      ).not.toBeInTheDocument();
    });

    rerender(<TranslationTextEditor assisted {...props} />);

    expect(
      await screen.findByRole('button', {
        name: 'Placeholder editing is off. Edit placeholders',
      }),
    ).toHaveTextContent('Edit placeholders');
  });

  it('resets placeholder editing when the compact control bar is removed and restored', async () => {
    const protectedTokens = [
      {
        start: 6,
        end: 12,
        label: 'ICU argument name',
        kind: 'icu-placeholder' as const,
      },
    ];
    const props = {
      value: 'Hello {name}',
      onChange: vi.fn(),
      showInvisibles: false,
      protectedTokens,
    };
    const { rerender } = render(
      <TranslationTextEditor
        assisted
        {...props}
        controlBar={{ protectedTokenCount: protectedTokens.length }}
      />,
    );

    fireEvent.click(
      await screen.findByRole('button', {
        name: 'Placeholder editing is off. Edit placeholders',
      }),
    );

    expect(
      await screen.findByRole('button', {
        name: 'Placeholder editing is on. Lock placeholders',
      }),
    ).toHaveTextContent('Lock placeholders');

    rerender(<TranslationTextEditor assisted {...props} />);
    await waitFor(() => {
      expect(
        screen.queryByRole('button', {
          name: 'Placeholder editing is on. Lock placeholders',
        }),
      ).not.toBeInTheDocument();
    });

    rerender(
      <TranslationTextEditor
        assisted
        {...props}
        controlBar={{ protectedTokenCount: protectedTokens.length }}
      />,
    );

    expect(
      await screen.findByRole('button', {
        name: 'Placeholder editing is off. Edit placeholders',
      }),
    ).toHaveTextContent('Edit placeholders');
  });
});
