import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createRef } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { VisibleTextEditor, type VisibleTextEditorHandle } from './VisibleTextEditor';

function renderEditor(value: string) {
  return render(
    <VisibleTextEditor value={value} onChange={vi.fn()} showInvisibles ariaLabel="Translation" />,
  );
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

  it('shows protection as current state in the control bar', () => {
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
      name: 'Protection is active. Switch to unprotected editing',
    });
    expect(protectionButton).toHaveTextContent('Protected');
    expect(protectionButton).toHaveAttribute('aria-pressed', 'true');
  });

  it('collects an exact ICU plural value from the control bar', async () => {
    const handleAddIcuPluralOption = vi.fn().mockReturnValue({ ok: true });
    render(
      <VisibleTextEditor
        value="{count, plural, one {# file} other {# files}}"
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          icuPluralOptionInsertions: [
            {
              id: '0:exact-value',
              kind: 'exact-value',
              label: 'count: exact value...',
              pluralStart: 0,
              pluralEnd: 46,
              selectionStart: 28,
              selectionEnd: 28,
              existingForms: [],
            },
          ],
          onAddIcuPluralOption: handleAddIcuPluralOption,
        }}
      />,
    );

    fireEvent.change(screen.getByLabelText('Add ICU plural form'), {
      target: { value: '0:exact-value' },
    });
    fireEvent.change(await screen.findByLabelText('Exact ICU plural value'), {
      target: { value: '0' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));

    expect(handleAddIcuPluralOption).toHaveBeenCalledWith('0:exact-value', '0');
  });

  it('shows exact ICU plural value errors in the compact status text', async () => {
    const handleAddIcuPluralOption = vi.fn().mockReturnValue({
      ok: false,
      error: 'Enter a non-negative integer.',
    });
    render(
      <VisibleTextEditor
        value="{count, plural, one {# file} other {# files}}"
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          icuPluralOptionInsertions: [
            {
              id: '0:exact-value',
              kind: 'exact-value',
              label: 'count: exact value...',
              pluralStart: 0,
              pluralEnd: 46,
              selectionStart: 28,
              selectionEnd: 28,
              existingForms: [],
            },
          ],
          onAddIcuPluralOption: handleAddIcuPluralOption,
        }}
      />,
    );

    fireEvent.change(screen.getByLabelText('Add ICU plural form'), {
      target: { value: '0:exact-value' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));

    expect(await screen.findByText('Enter a non-negative integer.')).toBeInTheDocument();
  });

  it('clears exact ICU plural value errors after adding a category form', async () => {
    const handleAddIcuPluralOption = vi
      .fn()
      .mockReturnValueOnce({
        ok: false,
        error: 'Enter a non-negative integer.',
      })
      .mockReturnValueOnce({ ok: true });
    render(
      <VisibleTextEditor
        value="{count, plural, one {# file} other {# files}}"
        onChange={vi.fn()}
        showInvisibles={false}
        controlBar={{
          icuPluralOptionInsertions: [
            {
              id: '0:exact-value',
              kind: 'exact-value',
              label: 'count: exact value...',
              pluralStart: 0,
              pluralEnd: 46,
              selectionStart: 28,
              selectionEnd: 28,
              existingForms: [],
            },
            {
              id: '0:few',
              kind: 'category',
              label: 'count: few',
              nextValue: '{count, plural, one {# file} few {# } other {# files}}',
              pluralStart: 0,
              pluralEnd: 46,
              selectionStart: 35,
              selectionEnd: 35,
            },
          ],
          onAddIcuPluralOption: handleAddIcuPluralOption,
          protectedTokenCount: 5,
        }}
      />,
    );

    fireEvent.change(screen.getByLabelText('Add ICU plural form'), {
      target: { value: '0:exact-value' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));

    expect(await screen.findByText('Enter a non-negative integer.')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Add ICU plural form'), {
      target: { value: '0:few' },
    });

    expect(screen.queryByText('Enter a non-negative integer.')).not.toBeInTheDocument();
    expect(screen.getByText('5 protected tokens')).toBeInTheDocument();
  });
});
