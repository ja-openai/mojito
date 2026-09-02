import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StrictMode, useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { Mf2TranslationEditor, type Mf2TranslationEditorSnapshot } from './Mf2TranslationEditor';

const COUNT_SOURCE = `.input {$count :number}
{{You have {$count} files.}}`;

const COUNT_SELECT_SOURCE = `.input {$count :number}
.match $count
one {{You have one file.}}
* {{You have files.}}`;

const COUNT_SELECT_TARGET = `.input {$count :number}
.match $count
one {{Vous avez un fichier.}}
* {{Vous avez des fichiers.}}`;

function ControlledEmptyTarget({ onTargetChange }: { onTargetChange: (target: string) => void }) {
  const [target, setTarget] = useState('');
  return (
    <Mf2TranslationEditor
      locale="fr"
      onTargetChange={(nextTarget) => {
        onTargetChange(nextTarget);
        setTarget(nextTarget);
      }}
      showArgumentInputs={false}
      showLocaleSelector={false}
      showPreview={false}
      showSource={false}
      source={COUNT_SOURCE}
      target={target}
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

describe('Mf2TranslationEditor', () => {
  it('keeps source input syntax out of the guided editor and available while editing placeholders', async () => {
    const user = userEvent.setup();
    const { container } = render(
      <Mf2TranslationEditor
        showArgumentInputs={false}
        showPreview={false}
        showSource={false}
        source={COUNT_SOURCE}
        target={COUNT_SOURCE}
      />,
    );

    expect(screen.queryByText('Variables')).not.toBeInTheDocument();
    expect(screen.queryByText('Source contract')).not.toBeInTheDocument();
    expect(screen.queryByText('No parser or contract issues.')).not.toBeInTheDocument();
    expect(screen.getByText('Insert special')).toBeVisible();
    expect(screen.getByText('placeholder menu')).not.toBeVisible();

    await user.click(screen.getByText('Shortcuts'));

    expect(screen.getByText('placeholder menu')).toBeVisible();
    expect(container.querySelector('.mf2-inline-editor')).toHaveClass(
      'mf2-inline-editor--menu-open',
    );

    await user.click(screen.getByText('Insert special'));

    expect(screen.getByText('placeholder menu')).not.toBeVisible();
    expect(screen.getByText('No-break space')).toBeVisible();

    await user.keyboard('{Escape}');

    expect(screen.getByText('No-break space')).not.toBeVisible();
    expect(container.querySelector('.mf2-inline-editor')).not.toHaveClass(
      'mf2-inline-editor--menu-open',
    );

    const editPlaceholders = screen.getByRole('button', {
      name: 'Placeholder editing is off. Edit placeholders',
    });
    expect(editPlaceholders).toHaveAttribute('aria-pressed', 'false');

    await user.click(editPlaceholders);

    expect(
      screen.getByRole('button', {
        name: 'Placeholder editing is on. Lock placeholders',
      }),
    ).toHaveAttribute('aria-pressed', 'true');
    const variables = screen.getByText('Variables').closest('.mf2-form-contract');
    expect(variables).toHaveTextContent('.input {$count :number}');
  });

  it('uses the shared hidden-character control without losing editor focus', async () => {
    const restoreRangeGeometry = installRangeGeometryMock();
    const user = userEvent.setup();

    function ControlledMarksMode() {
      const [marksMode, setMarksMode] = useState<'auto' | 'all' | 'off'>('auto');
      return (
        <Mf2TranslationEditor
          marksMode={marksMode}
          onChangeMarksMode={setMarksMode}
          showArgumentInputs={false}
          showLocaleSelector={false}
          showPreview={false}
          showSource={false}
          source="Hello"
          target="Bonjour  monde"
        />
      );
    }

    try {
      render(<ControlledMarksMode />);
      const editor = screen.getByRole('textbox', { name: 'Target Message' });
      editor.focus();

      await user.click(screen.getByRole('button', { name: 'Hidden characters: Auto' }));
      await user.click(screen.getByRole('option', { name: 'All' }));

      expect(screen.getByRole('button', { name: 'Hidden characters: All' })).toBeInTheDocument();
      expect(editor).toHaveFocus();
      expect(editor.textContent).toBe('Bonjour  monde');
    } finally {
      restoreRangeGeometry();
    }
  });

  it('promotes a controlled empty target into flat locale plural forms', async () => {
    const user = userEvent.setup();
    const onTargetChange = vi.fn();
    render(<ControlledEmptyTarget onTargetChange={onTargetChange} />);

    await user.click(screen.getByRole('button', { name: 'Add fr plural forms for $count' }));

    const expectedTarget = `.input {$count :number}
.match $count
one {{}}
many {{}}
other {{}}
* {{}}`;
    expect(onTargetChange).toHaveBeenCalledOnce();
    expect(onTargetChange).toHaveBeenCalledWith(expectedTarget);
    expect(await screen.findByRole('textbox', { name: 'Target count: one' })).toBeInTheDocument();
    expect(screen.getByText('count: many')).toBeInTheDocument();
    expect(screen.getByText('count: other')).toBeInTheDocument();
    expect(screen.getByText('count: fallback')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Add fr plural forms for $count' }),
    ).not.toBeInTheDocument();
  });

  it('keeps focus while moving down and back up through forms', async () => {
    const restoreRangeGeometry = installRangeGeometryMock();
    const user = userEvent.setup();

    try {
      render(
        <StrictMode>
          <Mf2TranslationEditor
            showArgumentInputs={false}
            showPreview={false}
            showSource={false}
            source={COUNT_SELECT_SOURCE}
            target={COUNT_SELECT_TARGET}
          />
        </StrictMode>,
      );

      const one = screen.getByRole('textbox', { name: 'Target count: one' });
      one.focus();
      placeCaret(one, 2);
      expect(one).toHaveFocus();

      await user.keyboard('{Shift>}{ArrowDown}{/Shift}');

      const fallback = screen.getByRole('textbox', { name: 'Target count: fallback' });
      expect(fallback).toHaveFocus();
      expect(window.getSelection()?.isCollapsed).toBe(true);
      expect(fallback.contains(window.getSelection()?.anchorNode ?? null)).toBe(true);

      await user.keyboard('{Shift>}{ArrowUp}{/Shift}');

      const restored = screen.getByRole('textbox', { name: 'Target count: one' });
      expect(restored).toHaveFocus();
      expect(window.getSelection()?.isCollapsed).toBe(true);
      expect(restored.contains(window.getSelection()?.anchorNode ?? null)).toBe(true);
    } finally {
      restoreRangeGeometry();
    }
  });

  it('tracks controlled target prop updates without emitting a local change', async () => {
    const onTargetChange = vi.fn();
    const { rerender } = render(
      <Mf2TranslationEditor
        onTargetChange={onTargetChange}
        showArgumentInputs={false}
        showPreview={false}
        showSource={false}
        source="Hello"
        target="Bonjour"
      />,
    );

    expect(screen.getByRole('textbox', { name: 'Target Message' })).toHaveTextContent('Bonjour');

    rerender(
      <Mf2TranslationEditor
        onTargetChange={onTargetChange}
        showArgumentInputs={false}
        showPreview={false}
        showSource={false}
        source="Hello"
        target="Salut"
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('textbox', { name: 'Target Message' })).toHaveTextContent('Salut');
    });
    expect(onTargetChange).not.toHaveBeenCalled();
  });

  function ControlledLiteralBraceTarget({
    onTargetChange,
  }: {
    onTargetChange: (target: string) => void;
  }) {
    const [target, setTarget] = useState('Bonjour');
    return (
      <Mf2TranslationEditor
        locale="fr"
        onTargetChange={(nextTarget) => {
          onTargetChange(nextTarget);
          setTarget(nextTarget);
        }}
        showArgumentInputs={false}
        showLocaleSelector={false}
        showPreview={false}
        showSource={false}
        source="Hello {$name}"
        target={target}
      />
    );
  }

  it('keeps a typed literal brace visible and preserves the caret', async () => {
    const restoreRangeGeometry = installRangeGeometryMock();
    const user = userEvent.setup();
    const onTargetChange = vi.fn();

    try {
      render(<ControlledLiteralBraceTarget onTargetChange={onTargetChange} />);
      const editor = screen.getByRole('textbox', { name: 'Target Message' });
      editor.focus();
      placeCaret(editor, 3);

      await user.keyboard('{{');

      expect(editor).toHaveTextContent('Bon{jour');
      expect(onTargetChange).toHaveBeenLastCalledWith('Bon\\{jour');
      expect(
        await screen.findByRole('listbox', { name: 'Placeholder suggestions for {' }),
      ).toBeVisible();
      expect(window.getSelection()?.anchorNode?.textContent).toBe('Bon{jour');
      expect(window.getSelection()?.anchorOffset).toBe(4);

      await user.keyboard('x');

      expect(editor).toHaveTextContent('Bon{xjour');
      expect(onTargetChange).toHaveBeenLastCalledWith('Bon\\{xjour');
      expect(window.getSelection()?.anchorNode?.textContent).toBe('Bon{xjour');
      expect(window.getSelection()?.anchorOffset).toBe(5);
    } finally {
      restoreRangeGeometry();
    }
  });

  it('can hide the locale selector while retaining the controlled locale', () => {
    render(
      <Mf2TranslationEditor
        locale="ru"
        showArgumentInputs={false}
        showLocaleSelector={false}
        showPreview={false}
        showSource={false}
        source={COUNT_SOURCE}
        target=""
      />,
    );

    expect(screen.queryByLabelText('Target language')).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Add ru plural forms for $count' }),
    ).toBeInTheDocument();
  });

  it('surfaces malformed source diagnostics without target ranges', async () => {
    let lastSnapshot: Mf2TranslationEditorSnapshot | undefined;
    const { container } = render(
      <Mf2TranslationEditor
        locale="fr"
        onChange={(snapshot) => {
          lastSnapshot = snapshot;
        }}
        showArgumentInputs={false}
        showLocaleSelector={false}
        showPreview={false}
        showSource={false}
        showActiveSourceComparison
        source=".input {$count :number"
        target="Vous avez des fichiers."
      />,
    );

    const sourceComparison = container.querySelector('.mf2-active-source-comparison');
    expect(sourceComparison).toHaveTextContent('Source·invalid MF2');
    expect(sourceComparison).toHaveTextContent('.input {$count :number');
    expect(
      (await screen.findAllByText(/Source: Placeholder is missing a closing brace/)).length,
    ).toBeGreaterThan(0);
    const diagnosticDetails = container.querySelector('.mf2-diagnostics');
    expect(diagnosticDetails).not.toHaveAttribute('open');
    fireEvent.click(diagnosticDetails?.querySelector('summary') as HTMLElement);
    expect(diagnosticDetails).toHaveAttribute('open');
    await waitFor(() => {
      const sourceDiagnostic = lastSnapshot?.diagnostics.find(
        (diagnostic) => diagnostic.code === 'source-unclosed-placeholder',
      );
      expect(sourceDiagnostic).toMatchObject({
        message: 'Source: Placeholder is missing a closing brace.',
        severity: 'error',
      });
      expect(sourceDiagnostic).not.toHaveProperty('start');
      expect(sourceDiagnostic).not.toHaveProperty('end');
    });
  });

  it('distinguishes warning-only diagnostics from errors', () => {
    const { container } = render(
      <Mf2TranslationEditor
        showArgumentInputs={false}
        showLocaleSelector={false}
        showPreview={false}
        showSource={false}
        source={COUNT_SOURCE}
        target="{{Vous avez des fichiers.}}"
      />,
    );

    const diagnosticDetails = container.querySelector('.mf2-diagnostics');
    expect(diagnosticDetails).toHaveClass('mf2-diagnostics-warning');
    expect(diagnosticDetails).not.toHaveClass('mf2-diagnostics-error');
    expect(diagnosticDetails?.querySelector('summary')).toHaveTextContent(/warning/);
  });

  it.each([
    ['Cmd+Enter', { metaKey: true }],
    ['Ctrl+Enter', { ctrlKey: true }],
  ])('submits from the rich editor with %s', (_shortcut, modifiers) => {
    const onSubmit = vi.fn();
    render(
      <Mf2TranslationEditor
        onSubmit={onSubmit}
        showArgumentInputs={false}
        showPreview={false}
        showSource={false}
        source="Hello"
        target="Bonjour"
      />,
    );

    fireEvent.keyDown(screen.getByRole('textbox', { name: 'Target Message' }), {
      key: 'Enter',
      ...modifiers,
    });

    expect(onSubmit).toHaveBeenCalledOnce();
  });

  it.each([
    ['back', '['],
    ['forward', ']'],
  ])('blocks the browser %s shortcut while the rich editor is focused', (_direction, key) => {
    const onTargetChange = vi.fn();
    render(
      <Mf2TranslationEditor
        onTargetChange={onTargetChange}
        showArgumentInputs={false}
        showPreview={false}
        showSource={false}
        source="Hello"
        target="Bonjour"
      />,
    );

    const editor = screen.getByRole('textbox', { name: 'Target Message' });
    editor.focus();

    expect(fireEvent.keyDown(editor, { key, metaKey: true })).toBe(false);
    expect(editor).toHaveFocus();
    expect(editor).toHaveTextContent('Bonjour');
    expect(onTargetChange).not.toHaveBeenCalled();
  });

  it.each([
    ['Control+[', { ctrlKey: true }],
    ['Command+Shift+[', { metaKey: true, shiftKey: true }],
  ])('does not block %s in the rich editor', (_shortcut, modifiers) => {
    render(
      <Mf2TranslationEditor
        showArgumentInputs={false}
        showPreview={false}
        showSource={false}
        source="Hello"
        target="Bonjour"
      />,
    );

    expect(
      fireEvent.keyDown(screen.getByRole('textbox', { name: 'Target Message' }), {
        key: '[',
        ...modifiers,
      }),
    ).toBe(true);
  });
});
