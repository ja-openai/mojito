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
  it('keeps source input syntax out of Edit and available in Raw', async () => {
    const user = userEvent.setup();
    render(
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

    const edit = screen.getByRole('button', { name: 'Edit' });
    const raw = screen.getByRole('button', { name: 'Raw' });
    expect(edit).toHaveAttribute('aria-pressed', 'true');
    expect(raw).toHaveAttribute('aria-pressed', 'false');

    await user.click(raw);

    expect(edit).toHaveAttribute('aria-pressed', 'false');
    expect(raw).toHaveAttribute('aria-pressed', 'true');
    const variables = screen.getByText('Variables').closest('.mf2-form-contract');
    expect(variables).toHaveTextContent('.input {$count :number}');
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
});
