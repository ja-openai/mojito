import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { FileTree, type FileTreeItem, FileTreeRow } from './FileTree';

// Give the real virtualizer a viewport; jsdom does not perform layout.
let resizeCallbacks: Array<() => void>;
beforeEach(() => {
  resizeCallbacks = [];
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockImplementation(function (
    this: HTMLElement,
  ) {
    return this.classList.contains('file-tree') ? 320 : 32;
  });
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(300);
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(320);
  vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockImplementation(function (
    this: HTMLElement,
  ) {
    return Number.parseFloat(
      this.querySelector<HTMLElement>('.file-tree__canvas')?.style.height ?? '320',
    );
  });
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(callback: (entries: unknown[]) => void) {
        resizeCallbacks.push(() => callback([]));
      }
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  HTMLElement.prototype.scrollTo = vi.fn(function (
    this: HTMLElement,
    options?: ScrollToOptions | number,
    y?: number,
  ) {
    this.scrollTop = typeof options === 'number' ? (y ?? 0) : (options?.top ?? 0);
    this.dispatchEvent(new Event('scroll'));
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  delete (HTMLElement.prototype as Partial<HTMLElement>).scrollTo;
});

function files(count: number): FileTreeItem[] {
  return Array.from({ length: count }, (_, index) => ({
    key: `file-${index}`,
    depth: 1,
    content: (
      <FileTreeRow label={`File ${index}`} ariaLabel={`Open file ${index}`} onSelect={() => {}} />
    ),
  }));
}

describe('FileTree', () => {
  it('mounts only the viewport for 100,000 rows and reveals a distant selection', async () => {
    const items = files(100_000);
    const { rerender } = render(<FileTree items={items} ariaLabel="Files" />);
    expect(await screen.findByRole('button', { name: 'Open file 0' })).toBeInTheDocument();
    expect(screen.getAllByRole('listitem').length).toBeLessThan(40);
    expect(screen.queryByRole('button', { name: 'Open file 99999' })).not.toBeInTheDocument();

    rerender(<FileTree items={items} ariaLabel="Files" revealKey="file-99999" />);
    expect(await screen.findByRole('button', { name: 'Open file 99999' })).toBeInTheDocument();
    expect(screen.getAllByRole('listitem').length).toBeLessThan(40);
    expect(screen.queryByRole('button', { name: 'Open file 0' })).not.toBeInTheDocument();
  });

  it('supports keyboard navigation beyond the mounted window', async () => {
    render(<FileTree items={files(100)} ariaLabel="Files" />);
    const first = await screen.findByRole('button', { name: 'Open file 0' });
    first.focus();
    fireEvent.keyDown(first, { key: 'End' });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Open file 99' })).toHaveFocus());
    fireEvent.keyDown(document.activeElement!, { key: 'ArrowUp' });
    expect(screen.getByRole('button', { name: 'Open file 98' })).toHaveFocus();
    fireEvent.keyDown(document.activeElement!, { key: 'Home' });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Open file 0' })).toHaveFocus());
  });

  it('keeps expansion controls and selected file semantics', async () => {
    const toggle = vi.fn();
    const open = vi.fn();
    render(
      <FileTree
        ariaLabel="Files"
        items={[
          {
            key: 'folder',
            depth: 0,
            content: (
              <FileTreeRow
                label="modules"
                ariaLabel="Browse modules"
                expanded={false}
                onToggle={toggle}
                onSelect={open}
              />
            ),
          },
          {
            key: 'selected',
            depth: 1,
            content: (
              <FileTreeRow
                label="Note.mdx"
                ariaLabel="Open note"
                selected
                current="page"
                onSelect={open}
              />
            ),
          },
        ]}
      />,
    );
    const folder = await screen.findByRole('button', { name: 'Browse modules' });
    fireEvent.keyDown(folder, { key: 'ArrowRight' });
    expect(toggle).toHaveBeenCalledOnce();
    fireEvent.keyDown(folder, { key: 'ArrowLeft' });
    expect(toggle).toHaveBeenCalledOnce();
    const file = screen.getByRole('button', { name: 'Open note' });
    expect(file).toHaveAttribute('aria-current', 'page');
    fireEvent.click(file);
    expect(open).toHaveBeenCalledOnce();
  });

  it('skips status rows and keeps navigation keys out of review shortcuts', async () => {
    const globalShortcut = vi.fn();
    window.addEventListener('keydown', globalShortcut);
    try {
      const items = files(2);
      items.splice(1, 0, {
        key: 'empty',
        depth: 1,
        focusable: false,
        content: <p>Empty folder.</p>,
      });
      render(<FileTree items={items} ariaLabel="Files" />);
      const first = await screen.findByRole('button', { name: 'Open file 0' });
      first.focus();
      fireEvent.keyDown(first, { key: 'ArrowDown' });
      expect(screen.getByRole('button', { name: 'Open file 1' })).toHaveFocus();
      expect(globalShortcut).not.toHaveBeenCalled();
      fireEvent.keyDown(document.activeElement!, { key: 'ArrowUp' });
      expect(first).toHaveFocus();
    } finally {
      window.removeEventListener('keydown', globalShortcut);
    }
  });

  it('reveals selection when a hidden pane opens without jumping on unrelated expansion', async () => {
    let height = 0;
    vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockImplementation(function (
      this: HTMLElement,
    ) {
      return this.classList.contains('file-tree') ? height : 32;
    });
    const items = files(100);
    const { rerender } = render(<FileTree items={items} ariaLabel="Files" revealKey="file-90" />);
    expect(screen.queryByRole('button', { name: 'Open file 90' })).not.toBeInTheDocument();
    height = 320;
    act(() => resizeCallbacks.forEach((resize) => resize()));
    expect(await screen.findByRole('button', { name: 'Open file 90' })).toBeInTheDocument();
    const viewport = screen.getByRole('list', { name: 'Files' });
    fireEvent.scroll(viewport, { target: { scrollTop: 0 } });
    expect(await screen.findByRole('button', { name: 'Open file 0' })).toBeInTheDocument();
    rerender(
      <FileTree
        items={[{ key: 'status', depth: 0, focusable: false, content: 'Loading…' }, ...items]}
        ariaLabel="Files"
        revealKey="file-90"
      />,
    );
    expect(viewport.scrollTop).toBe(0);
  });
});
