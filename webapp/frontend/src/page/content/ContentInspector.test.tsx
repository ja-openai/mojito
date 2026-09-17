import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { createRef, useEffect, useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PillDropdown } from '../../components/PillDropdown';
import { ContentInspector } from './ContentInspector';

let matches = false;
let listeners: Set<() => void>;

beforeEach(() => {
  matches = false;
  listeners = new Set();
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      get matches() {
        return matches;
      },
      addEventListener: (_event: string, listener: () => void) => listeners.add(listener),
      removeEventListener: (_event: string, listener: () => void) => listeners.delete(listener),
    })),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.body.style.overflow = '';
});

function resize(narrow: boolean) {
  act(() => {
    matches = narrow;
    listeners.forEach((listener) => listener());
  });
}

describe('ContentInspector', () => {
  it('keeps the same panel, editor instance and draft through drawer and desktop resizing', () => {
    const mounted = vi.fn();
    function Editor() {
      const [value, setValue] = useState('Original');
      useEffect(() => {
        mounted();
      }, []);
      return (
        <input
          aria-label="Translation"
          value={value}
          onChange={(event) => setValue(event.target.value)}
        />
      );
    }
    const panelRef = createRef<HTMLDivElement>();
    render(
      <ContentInspector
        ariaLabel="Translation editor"
        className="existing-pane"
        onClose={vi.fn()}
        panelRef={panelRef}
      >
        <Editor />
      </ContentInspector>,
    );
    const panel = panelRef.current;
    const input = screen.getByRole('textbox', { name: 'Translation' });
    fireEvent.change(input, { target: { value: 'Draft correction' } });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Back to preview' })).not.toBeInTheDocument();
    resize(true);
    expect(screen.getByRole('dialog', { name: 'Translation editor' })).toBe(panel);
    expect(panel).toHaveClass('existing-pane', 'content-inspector--drawer');
    expect(input).toHaveValue('Draft correction');
    resize(false);
    expect(panelRef.current).toBe(panel);
    expect(screen.getByRole('textbox')).toBe(input);
    expect(input).toHaveValue('Draft correction');
    expect(mounted).toHaveBeenCalledTimes(1);
    expect(document.body.style.overflow).toBe('');
  });

  it('traps Tab, ignores hidden or disabled controls and leaves Escape to the editor', () => {
    matches = true;
    const close = vi.fn();
    const escape = vi.fn();
    render(
      <ContentInspector ariaLabel="Translation editor" onClose={close}>
        <input
          aria-label="Translation"
          onKeyDown={(event) => {
            if (event.key === 'Escape') escape();
          }}
        />
        <div style={{ display: 'none' }}>
          <button>Hidden</button>
        </div>
        <button disabled>Disabled</button>
        <button>Save</button>
      </ContentInspector>,
    );
    const dialog = screen.getByRole('dialog');
    const back = within(dialog).getByRole('button', { name: 'Back to preview' });
    const save = screen.getByRole('button', { name: 'Save' });
    expect(back).toHaveFocus();
    fireEvent.keyDown(back, { key: 'Tab', shiftKey: true });
    expect(save).toHaveFocus();
    fireEvent.keyDown(save, { key: 'Tab' });
    expect(back).toHaveFocus();
    const input = screen.getByRole('textbox');
    input.focus();
    fireEvent.keyDown(input, { key: 'Escape' });
    expect(escape).toHaveBeenCalledOnce();
    expect(close).not.toHaveBeenCalled();
  });

  it('returns focus to the selected passage when the drawer unmounts', () => {
    matches = true;
    function Preview() {
      const [open, setOpen] = useState(false);
      return (
        <>
          <button onClick={() => setOpen(true)}>Edit passage</button>
          {open ? (
            <ContentInspector ariaLabel="Translation editor" onClose={() => setOpen(false)}>
              <input aria-label="Translation" />
            </ContentInspector>
          ) : null}
        </>
      );
    }
    render(<Preview />);
    const passage = screen.getByRole('button', { name: 'Edit passage' });
    passage.focus();
    fireEvent.click(passage);
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Back to preview' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(passage).toHaveFocus();
    expect(document.body.style.overflow).toBe('');
  });

  it('guards both close controls while busy and never opens a drawer when disabled', () => {
    matches = true;
    const close = vi.fn();
    const { rerender } = render(
      <ContentInspector ariaLabel="Translation editor" onClose={close} closeDisabled>
        <input aria-label="Translation" />
      </ContentInspector>,
    );
    for (const back of screen.getAllByRole('button', { name: 'Back to preview' })) {
      expect(back).toBeDisabled();
      fireEvent.click(back);
    }
    expect(close).not.toHaveBeenCalled();
    rerender(
      <ContentInspector ariaLabel="Translation editor" onClose={close} enabled={false}>
        <input aria-label="Translation" />
      </ContentInspector>,
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Back to preview' })).not.toBeInTheDocument();
    expect(screen.getByRole('textbox')).toBeInTheDocument();
  });

  it('allows a higher-layer modal to receive focus instead of trapping it back in the drawer', () => {
    matches = true;
    render(
      <>
        <ContentInspector ariaLabel="Translation editor" onClose={vi.fn()}>
          <input aria-label="Translation" />
        </ContentInspector>
        <div role="alertdialog" aria-modal="true" aria-label="Confirm decision">
          <button>Confirm</button>
        </div>
      </>,
    );
    const confirm = screen.getByRole('button', { name: 'Confirm' });
    confirm.focus();
    expect(confirm).toHaveFocus();
    fireEvent.keyDown(confirm, { key: 'Tab' });
    expect(confirm).toHaveFocus();
  });

  it('allows keyboard focus in an editor-owned portal while containing background focus', () => {
    matches = true;
    const change = vi.fn();
    render(
      <>
        <button>Background action</button>
        <ContentInspector ariaLabel="Translation editor" onClose={vi.fn()}>
          <PillDropdown
            ariaLabel="Translation status"
            value="review"
            options={[
              { value: 'review', label: 'To review' },
              { value: 'accepted', label: 'Accepted' },
            ]}
            onChange={change}
          />
        </ContentInspector>
      </>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Translation status' }));
    const menu = screen.getByRole('menu');
    expect(screen.getByRole('dialog')).not.toContainElement(menu);
    const accepted = within(menu).getByRole('button', { name: 'Accepted' });
    accepted.focus();
    expect(accepted).toHaveFocus();
    expect(fireEvent.keyDown(accepted, { key: 'Tab', shiftKey: true })).toBe(true);
    fireEvent.click(accepted);
    expect(change).toHaveBeenCalledWith('accepted');

    screen.getByRole('button', { name: 'Background action' }).focus();
    expect(screen.getByRole('button', { name: 'Back to preview' })).toHaveFocus();
  });

  it('tolerates matchMedia stubs without event methods and responds to window resize', () => {
    vi.stubGlobal('matchMedia', () => ({ matches }));
    render(
      <ContentInspector ariaLabel="Translation editor" onClose={vi.fn()}>
        <input aria-label="Translation" />
      </ContentInspector>,
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    act(() => {
      matches = true;
      window.dispatchEvent(new Event('resize'));
    });
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});
