import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRef, type Ref, useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { useProtectedTextTokenGuard } from '../hooks/useProtectedTextTokenGuard';
import {
  TranslationTextEditor,
  type TranslationTextEditorKeyDownEvent,
} from './TranslationTextEditor';
import type { VisibleTextEditorHandle } from './VisibleTextEditor';

function queryIcuFormTrigger(text: RegExp | string) {
  return Array.from(
    document.querySelectorAll<HTMLElement>(
      '.visible-text-editor__protected-token--icu-form-trigger:not(.visible-text-editor__protected-token--empty-icu-syntax)',
    ),
  ).find((element) => {
    const content = element.textContent ?? '';
    return typeof text === 'string' ? content === text : text.test(content);
  });
}

function expectIcuFormTrigger(text: RegExp | string) {
  const trigger = queryIcuFormTrigger(text);
  expect(trigger).toBeInTheDocument();
  return trigger as HTMLElement;
}

function openFormMenu(text: RegExp | string = /\s/) {
  fireEvent.click(expectIcuFormTrigger(text));
  return screen.getByRole('menu');
}

function getFormCheckboxLabels() {
  return within(screen.getByRole('menu'))
    .getAllByRole('checkbox')
    .map((checkbox) => checkbox.closest('label')?.textContent);
}

function toggleForm(form: string) {
  fireEvent.click(within(screen.getByRole('menu')).getByRole('checkbox', { name: form }));
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
  assisted = true,
  editorRef,
  initialValue,
  onValueChange,
}: {
  assisted?: boolean;
  editorRef: Ref<VisibleTextEditorHandle>;
  initialValue: string;
  onValueChange: (value: string) => void;
}) {
  const [value, setValue] = useState(initialValue);

  return (
    <TranslationTextEditor
      ref={editorRef}
      assisted={assisted}
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
  it.each(
    [
      { mode: 'assisted', assisted: true, rawMode: false },
      { mode: 'unprotected', assisted: true, rawMode: true },
      { mode: 'native', assisted: false, rawMode: false },
    ].flatMap((mode) =>
      [
        { label: 'No-break space', text: '\u00a0' },
        { label: 'Word joiner', text: '\u2060' },
      ].map((tool) => ({ ...mode, ...tool })),
    ),
  )(
    'inserts $label at the caret in the $mode editor',
    async ({ assisted, rawMode, label, text }) => {
      const user = userEvent.setup();
      const ref = createRef<VisibleTextEditorHandle>();
      const handleChange = vi.fn();
      const restoreDom = installProseMirrorHistoryDomMock();

      try {
        render(
          <ControlledTranslationTextEditor
            assisted={assisted}
            editorRef={ref}
            initialValue="保存完了"
            onValueChange={handleChange}
          />,
        );
        const editor = await screen.findByRole('textbox', { name: 'Text editor' });
        if (rawMode) {
          await user.click(
            screen.getByRole('button', {
              name: 'Placeholder editing is off. Edit placeholders',
            }),
          );
        }
        act(() => ref.current?.setSelection({ start: 2, end: 2 }));

        await user.click(screen.getByRole('button', { name: 'Characters' }));
        await user.click(screen.getByRole('button', { name: new RegExp(`^${label}`) }));

        expect(handleChange).toHaveBeenLastCalledWith(`保存${text}完了`);
        await waitFor(() => expect(ref.current?.getSelection()).toEqual({ start: 3, end: 3 }));
        expect(editor).toHaveFocus();
        expect(screen.getByText(label)).not.toBeVisible();
      } finally {
        restoreDom();
      }
    },
  );

  it.each([true, false])(
    'wraps selected text with directional isolation and restores selection (assisted: %s)',
    async (assisted) => {
      const user = userEvent.setup();
      const ref = createRef<VisibleTextEditorHandle>();
      const handleChange = vi.fn();
      const restoreDom = installProseMirrorHistoryDomMock();

      try {
        render(
          <ControlledTranslationTextEditor
            assisted={assisted}
            editorRef={ref}
            initialValue="Bonjour monde"
            onValueChange={handleChange}
          />,
        );
        const editor = await screen.findByRole('textbox', { name: 'Text editor' });
        act(() => ref.current?.setSelection({ start: 8, end: 13 }));

        await user.click(screen.getByRole('button', { name: 'Characters' }));
        await user.click(screen.getByRole('button', { name: /^Keep LTR phrase/ }));

        expect(handleChange).toHaveBeenLastCalledWith('Bonjour \u2066monde\u2069');
        await waitFor(() => expect(ref.current?.getSelection()).toEqual({ start: 9, end: 14 }));
        expect(editor).toHaveFocus();
      } finally {
        restoreDom();
      }
    },
  );

  it.each([true, false])(
    'closes the special-character menu before editor Escape handlers run (assisted: %s)',
    async (assisted) => {
      const user = userEvent.setup();
      const ref = createRef<VisibleTextEditorHandle>();
      const onKeyDown = vi.fn((event: TranslationTextEditorKeyDownEvent) => {
        if (event.key === 'Escape') {
          event.stopPropagation();
          ref.current?.blur();
        }
      });
      render(
        <TranslationTextEditor
          ref={ref}
          assisted={assisted}
          controlBar={{}}
          value="Bonjour"
          onChange={vi.fn()}
          onKeyDown={onKeyDown}
        />,
      );
      const editor = await screen.findByRole('textbox', { name: 'Text editor' });
      act(() => ref.current?.setSelection({ start: 3, end: 3 }));
      await user.click(screen.getByRole('button', { name: 'Characters' }));
      expect(editor).toHaveFocus();

      await user.keyboard('{Escape}');

      expect(onKeyDown).not.toHaveBeenCalled();
      expect(screen.getByText('No-break space')).not.toBeVisible();
      expect(editor).toHaveFocus();
      expect(ref.current?.getSelection()).toEqual({ start: 3, end: 3 });
    },
  );

  it.each([true, false])(
    'preserves the insertion point through keyboard help navigation (assisted: %s)',
    async (assisted) => {
      const user = userEvent.setup();
      const ref = createRef<VisibleTextEditorHandle>();
      const handleChange = vi.fn();
      const restoreDom = installProseMirrorHistoryDomMock();

      try {
        render(
          <ControlledTranslationTextEditor
            assisted={assisted}
            editorRef={ref}
            initialValue="Bonjour monde"
            onValueChange={handleChange}
          />,
        );
        const editor = await screen.findByRole('textbox', { name: 'Text editor' });
        act(() => ref.current?.setSelection({ start: 8, end: 13 }));
        await user.click(screen.getByRole('button', { name: 'Characters' }));
        const helpButton = screen.getByRole('button', { name: 'System keyboard help' });
        helpButton.focus();
        await user.keyboard('{Enter}');

        expect(screen.getByRole('heading', { name: 'macOS' })).toBeVisible();
        expect(screen.getByRole('heading', { name: 'Windows' })).toBeVisible();
        expect(helpButton).toHaveFocus();
        expect(screen.getByText('No-break space')).not.toBeVisible();
        expect(handleChange).not.toHaveBeenCalled();

        await user.keyboard(' ');
        expect(screen.getByText('No-break space')).toBeVisible();
        await user.keyboard('{Enter}');
        await user.keyboard('{Escape}');

        expect(editor).toHaveFocus();
        expect(ref.current?.getSelection()).toEqual({ start: 8, end: 13 });
        await user.click(screen.getByRole('button', { name: 'Characters' }));
        expect(screen.getByText('macOS')).not.toBeVisible();
        await user.click(screen.getByRole('button', { name: 'Curly apostrophe' }));
        expect(handleChange).toHaveBeenLastCalledWith('Bonjour \u2019');
        expect(editor).toHaveFocus();
      } finally {
        restoreDom();
      }
    },
  );

  it.each([true, false])(
    'prevents special-character insertion while disabled or read-only (assisted: %s)',
    async (assisted) => {
      const user = userEvent.setup();
      const handleChange = vi.fn();
      const props = { assisted, controlBar: {}, value: 'Bonjour', onChange: handleChange };
      const { rerender } = render(<TranslationTextEditor {...props} disabled />);

      expect(screen.getByRole('button', { name: 'Characters' })).toBeDisabled();
      await user.click(screen.getByRole('button', { name: 'Characters' }));
      expect(screen.getByText('No-break space')).not.toBeVisible();

      rerender(<TranslationTextEditor {...props} readOnly />);
      expect(screen.getByRole('button', { name: 'Characters' })).toBeDisabled();
      await user.click(screen.getByRole('button', { name: 'Characters' }));
      expect(handleChange).not.toHaveBeenCalled();
    },
  );

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

    await waitFor(() => expectIcuFormTrigger('count one'));

    act(() => {
      const nestedCaret = value.indexOf('item}') + 'it'.length;
      ref.current?.setSelection({ start: nestedCaret, end: nestedCaret });
    });

    await waitFor(() => {
      expectIcuFormTrigger('itemCount one');
      expect(queryIcuFormTrigger('count one')).toBeUndefined();
    });

    openFormMenu('itemCount one');

    expect(
      within(screen.getByRole('menu')).getByRole('checkbox', { name: 'zero' }),
    ).not.toBeChecked();
    expect(getFormCheckboxLabels()).not.toContain('count: zero');
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

    await waitFor(() => expectIcuFormTrigger('fileCount one'));

    act(() => {
      const afterFirstPlural = value.indexOf(' and');
      ref.current?.setSelection({ start: afterFirstPlural, end: afterFirstPlural });
    });

    await waitFor(() => {
      expectIcuFormTrigger('fileCount one');
      expectIcuFormTrigger('folderCount one');
    });
  });

  it('marks required other forms as fixed for ICU select messages', async () => {
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

    await waitFor(() => expectIcuFormTrigger('gender male'));

    openFormMenu('gender male');

    const other = within(screen.getByRole('menu')).getByRole('checkbox', { name: /other/ });

    expect(other).toBeDisabled();
    expect(other).not.toBeChecked();
    expect(handleChange).not.toHaveBeenCalled();
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

    await waitFor(() => expectIcuFormTrigger('count one'));

    openFormMenu('count one');
    toggleForm('few');

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

  it('removes existing exact ICU plural forms from the inline form menu', async () => {
    const handleChange = vi.fn();
    const value = '{count, plural, one {# file} =15 {# files} other {# files}}';
    const removedValue = '{count, plural, one {# file} other {# files}}';

    render(
      <ControlledTranslationTextEditor
        editorRef={createRef<VisibleTextEditorHandle>()}
        initialValue={value}
        onValueChange={handleChange}
      />,
    );

    await waitFor(() => expectIcuFormTrigger('count one'));
    openFormMenu('count one');

    const exactForm = within(screen.getByRole('menu')).getByRole('checkbox', { name: '=15' });
    expect(exactForm).toBeChecked();

    toggleForm('=15');

    await waitFor(() => expect(handleChange).toHaveBeenLastCalledWith(removedValue));
  });

  it('adds ICU plural forms while placeholder protection is locked', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const handleChange = vi.fn();
    const value = 'Delete {count, plural, one {# app} other {# apps}} forever.';
    const insertedValue = 'Delete {count, plural, one {# app} few {# } other {# apps}} forever.';

    render(
      <GuardedTranslationTextEditor
        editorRef={ref}
        initialValue={value}
        onValueChange={handleChange}
      />,
    );

    await waitFor(() => expectIcuFormTrigger('count one'));
    openFormMenu('count one');
    toggleForm('few');

    await waitFor(() => expect(handleChange).toHaveBeenLastCalledWith(insertedValue));
    expect(
      screen.queryByText(
        'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.',
      ),
    ).not.toBeInTheDocument();
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

    await waitFor(() => expectIcuFormTrigger('count one'));
    const editor = screen.getByRole('textbox', { name: 'Text editor' });

    openFormMenu('count one');
    toggleForm('few');

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
          (token) => token.textContent === 'draft',
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
