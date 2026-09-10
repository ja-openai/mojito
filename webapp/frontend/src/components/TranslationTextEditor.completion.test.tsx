import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRef, type Ref, useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useProtectedTextTokenGuard } from '../hooks/useProtectedTextTokenGuard';
import { installProseMirrorDomMock } from '../test/proseMirrorDom';
import { TranslationTextEditor } from './TranslationTextEditor';
import type { VisibleTextEditorHandle } from './VisibleTextEditor';

function CompletionEditor({
  source,
  initialValue = 'Bonjour ',
  editorRef,
  onChange = () => {},
  onKeyDown = () => {},
  readOnly = false,
  disabled = false,
  assisted = true,
}: {
  source: string;
  initialValue?: string;
  editorRef: Ref<VisibleTextEditorHandle>;
  onChange?: (value: string) => void;
  onKeyDown?: (event: KeyboardEvent | React.KeyboardEvent<HTMLTextAreaElement>) => void;
  readOnly?: boolean;
  disabled?: boolean;
  assisted?: boolean;
}) {
  const [value, setValue] = useState(initialValue);
  const guard = useProtectedTextTokenGuard(value, 'icu-html');
  return (
    <TranslationTextEditor
      ref={editorRef}
      ariaLabel="Translation"
      assisted={assisted}
      source={source}
      value={value}
      onChange={(next) => {
        onChange(next);
        setValue(next);
      }}
      onKeyDown={onKeyDown}
      readOnly={readOnly}
      disabled={disabled}
      controlBar={{ protectedTokenCount: guard.protectedTokens.length }}
      protectedTokens={guard.protectedTokens}
      protectedDiagnostics={guard.diagnostics}
      validateNextValue={guard.validateNextValue}
    />
  );
}

describe('source placeholder completion', () => {
  let restoreDom: () => void;
  beforeEach(() => {
    restoreDom = installProseMirrorDomMock();
  });
  afterEach(() => restoreDom());

  it('completes the exact source expression while preserving existing placeholders', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const onChange = vi.fn();
    const initialValue = 'Bonjour {name}, total ';
    render(
      <CompletionEditor
        editorRef={ref}
        source="Hello {name}, total {amount, number, ::currency/EUR}"
        initialValue={initialValue}
        onChange={onChange}
      />,
    );
    act(() => {
      ref.current?.setSelection({ start: initialValue.length, end: initialValue.length });
      ref.current?.insertText('{a');
    });
    expect(await screen.findByRole('listbox', { name: 'Source placeholders' })).toBeVisible();
    fireEvent.keyDown(screen.getByRole('textbox', { name: 'Translation' }), { key: 'Enter' });
    expect(onChange).toHaveBeenLastCalledWith(
      'Bonjour {name}, total {amount, number, ::currency/EUR}',
    );
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: 'Translation' }));
    expect(screen.queryByText(/Placeholder edit blocked/)).not.toBeInTheDocument();
    act(() => {
      ref.current?.undo();
    });
    expect(onChange).toHaveBeenLastCalledWith('Bonjour {name}, total {a');
    const wasProtectedAfterUndo = Boolean(
      screen.getByRole('textbox', { name: 'Translation' }).querySelector('[data-raw="{name}"]'),
    );
    act(() => {
      ref.current?.redo();
    });
    expect(onChange).toHaveBeenLastCalledWith(
      'Bonjour {name}, total {amount, number, ::currency/EUR}',
    );
    expect(wasProtectedAfterUndo).toBe(true);
    expect(screen.queryByText(/Placeholder edit blocked/)).not.toBeInTheDocument();
  });

  it('keeps completion as one undoable edit and restores the caret on redo', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const onChange = vi.fn();
    render(<CompletionEditor editorRef={ref} source="Hello {name}" onChange={onChange} />);
    act(() => {
      ref.current?.setSelection({ start: 8, end: 8 });
      ref.current?.insertText('{na');
    });
    fireEvent.click(await screen.findByRole('option', { name: '{name} Source placeholder' }));
    expect(onChange).toHaveBeenLastCalledWith('Bonjour {name}');
    act(() => {
      ref.current?.undo();
    });
    expect(onChange).toHaveBeenLastCalledWith('Bonjour {na');
    act(() => {
      ref.current?.redo();
    });
    expect(onChange).toHaveBeenLastCalledWith('Bonjour {name}');
    expect(ref.current?.getSelection()).toEqual({ start: 14, end: 14 });
  });

  it.each(['%1$s', '%(name)s', '%@'])(
    'inserts a source %s placeholder using Tab',
    async (placeholder) => {
      const ref = createRef<VisibleTextEditorHandle>();
      const onChange = vi.fn();
      render(
        <CompletionEditor editorRef={ref} source={`Hello ${placeholder}`} onChange={onChange} />,
      );
      act(() => {
        ref.current?.setSelection({ start: 8, end: 8 });
        ref.current?.insertText('%');
      });
      await screen.findByRole('option', { name: `${placeholder} Source placeholder` });
      fireEvent.keyDown(screen.getByRole('textbox', { name: 'Translation' }), { key: 'Tab' });
      expect(onChange).toHaveBeenLastCalledWith(`Bonjour ${placeholder}`);
    },
  );

  it('consumes an adjacent closing brace without replacing surrounding text', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const onChange = vi.fn();
    render(
      <CompletionEditor
        editorRef={ref}
        source="Hello {name}"
        initialValue="Bonjour {}!"
        onChange={onChange}
      />,
    );
    act(() => {
      ref.current?.setSelection({ start: 9, end: 9 });
    });
    fireEvent.click(await screen.findByRole('option', { name: '{name} Source placeholder' }));
    expect(onChange).toHaveBeenLastCalledWith('Bonjour {name}!');
  });

  it('keeps Escape dismissed while typing the same prefix, then opens for a new one', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const onChange = vi.fn();
    render(<CompletionEditor editorRef={ref} source="{name} {number}" onChange={onChange} />);
    act(() => {
      ref.current?.setSelection({ start: 8, end: 8 });
      ref.current?.insertText('{');
    });
    await screen.findByRole('listbox');
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Escape' });
    act(() => {
      ref.current?.insertText('na');
    });
    await waitFor(() => expect(screen.queryByRole('listbox')).not.toBeInTheDocument());
    expect(onChange).toHaveBeenLastCalledWith('Bonjour {na');
    act(() => {
      ref.current?.insertText('me} ');
    });
    act(() => {
      ref.current?.insertText('{');
    });
    expect(await screen.findByRole('listbox')).toBeVisible();
  });

  it('uses the current source when the host changes documents', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const { rerender } = render(<CompletionEditor editorRef={ref} source="{oldName}" />);
    act(() => {
      ref.current?.setSelection({ start: 8, end: 8 });
      ref.current?.insertText('{');
    });
    await screen.findByRole('option', { name: '{oldName} Source placeholder' });
    rerender(<CompletionEditor editorRef={ref} source="{newName}" />);
    expect(
      await screen.findByRole('option', { name: '{newName} Source placeholder' }),
    ).toBeVisible();
    expect(
      screen.queryByRole('option', { name: '{oldName} Source placeholder' }),
    ).not.toBeInTheDocument();
    rerender(<CompletionEditor editorRef={ref} source="No placeholders" />);
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('does not suggest target-only placeholders', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const value = '{targetOnly} ';
    render(<CompletionEditor editorRef={ref} source="{sourceName}" initialValue={value} />);
    act(() => {
      ref.current?.setSelection({ start: value.length, end: value.length });
      ref.current?.insertText('{');
    });
    expect(await screen.findAllByRole('option')).toHaveLength(1);
    expect(screen.getByRole('option')).toHaveTextContent('{sourceName}');
  });

  it('protects platform completions and preserves undo without host-supplied token ranges', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const onChange = vi.fn();
    function StandaloneEditor() {
      const [value, setValue] = useState('Bonjour ');
      return (
        <TranslationTextEditor
          ref={ref}
          assisted
          source="Hello %1$s"
          value={value}
          onChange={(next) => {
            onChange(next);
            setValue(next);
          }}
        />
      );
    }
    render(<StandaloneEditor />);
    act(() => {
      ref.current?.setSelection({ start: 8, end: 8 });
      ref.current?.insertText('%');
    });
    fireEvent.click(await screen.findByRole('option', { name: '%1$s Source placeholder' }));
    expect(onChange).toHaveBeenLastCalledWith('Bonjour %1$s');
    expect(document.querySelector('[data-raw="%1$s"]')).toBeInTheDocument();
    act(() => {
      ref.current?.undo();
    });
    expect(onChange).toHaveBeenLastCalledWith('Bonjour %');
    act(() => {
      ref.current?.redo();
    });
    expect(onChange).toHaveBeenLastCalledWith('Bonjour %1$s');
    expect(document.querySelector('[data-raw="%1$s"]')).toBeInTheDocument();
  });

  it('does not apply a stale menu at a newly selected placeholder range', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const onChange = vi.fn();
    render(
      <CompletionEditor
        editorRef={ref}
        source="%s"
        initialValue="Bonjour % puis %"
        onChange={onChange}
      />,
    );
    act(() => {
      ref.current?.setSelection({ start: 9, end: 9 });
    });
    await screen.findByRole('listbox');
    act(() => {
      ref.current?.setSelection({ start: 16, end: 16 });
      fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
    });
    expect(onChange).not.toHaveBeenCalled();
  });

  it('resets dismissed suggestions when switching source documents', async () => {
    const ref = createRef<VisibleTextEditorHandle>();
    const { rerender } = render(
      <CompletionEditor editorRef={ref} source="{first}" initialValue="Bonjour {" />,
    );
    act(() => {
      ref.current?.setSelection({ start: 9, end: 9 });
    });
    await screen.findByRole('listbox');
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Escape' });
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    rerender(<CompletionEditor editorRef={ref} source="{second}" initialValue="Bonjour {" />);
    await screen.findByRole('option', { name: '{second} Source placeholder' });
    rerender(<CompletionEditor editorRef={ref} source="{first}" initialValue="Bonjour {" />);
    expect(await screen.findByRole('option', { name: '{first} Source placeholder' })).toBeVisible();
  });

  it('hides suggestions when placeholder editing is unlocked', async () => {
    const user = userEvent.setup();
    const ref = createRef<VisibleTextEditorHandle>();
    render(<CompletionEditor editorRef={ref} source="{name}" initialValue="Bonjour {" />);
    act(() => {
      ref.current?.setSelection({ start: 9, end: 9 });
    });
    await screen.findByRole('listbox');
    await user.click(
      screen.getByRole('button', { name: 'Placeholder editing is off. Edit placeholders' }),
    );
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it.each([{ readOnly: true }, { disabled: true }, { assisted: false }])(
    'does not complete in an inactive editor: %j',
    (props) => {
      const ref = createRef<VisibleTextEditorHandle>();
      render(
        <CompletionEditor editorRef={ref} source="{name}" initialValue="Bonjour {" {...props} />,
      );
      act(() => {
        ref.current?.setSelection({ start: 9, end: 9 });
      });
      expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    },
  );
});
