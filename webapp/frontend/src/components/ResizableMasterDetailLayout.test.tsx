import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ResizableMasterDetailLayout } from './ResizableMasterDetailLayout';

const previewKey = 'test.resizable.preview';
const listKey = 'test.resizable.list';

function layout(overrides: Partial<ComponentProps<typeof ResizableMasterDetailLayout>> = {}) {
  return (
    <ResizableMasterDetailLayout
      sidebar={<input aria-label="Page search" defaultValue="" />}
      detail={<input aria-label="Translation" defaultValue="Initial translation" />}
      storageKey={previewKey}
      sidebarLabel="Pages"
      detailLabel="Translation editor"
      resizeLabel="Resize pages"
      {...overrides}
    />
  );
}

function pointer(target: Element | Window, type: string, clientX: number) {
  fireEvent(target, new MouseEvent(type, { bubbles: true, button: 0, clientX }));
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  window.localStorage.removeItem(previewKey);
  window.localStorage.removeItem(listKey);
});

describe('ResizableMasterDetailLayout', () => {
  it('keeps separate persisted widths while retaining the mounted editor', () => {
    window.localStorage.setItem(listKey, '42');
    const { rerender, unmount } = render(layout());
    const translation = screen.getByRole('textbox', { name: 'Translation' });
    fireEvent.change(translation, { target: { value: 'Unsaved translation' } });
    const handle = screen.getByRole('separator', { name: 'Resize pages' });
    fireEvent.keyDown(handle, { key: 'ArrowRight' });
    expect(handle).toHaveAttribute('aria-valuenow', '36');

    rerender(layout({ storageKey: listKey }));
    expect(handle).toHaveAttribute('aria-valuenow', '42');
    fireEvent.keyDown(handle, { key: 'ArrowLeft' });
    expect(handle).toHaveAttribute('aria-valuenow', '40');
    expect(screen.getByRole('textbox', { name: 'Translation' })).toBe(translation);
    expect(translation).toHaveValue('Unsaved translation');

    rerender(layout());
    expect(handle).toHaveAttribute('aria-valuenow', '36');
    expect(window.localStorage.getItem(previewKey)).toBe('36');
    expect(window.localStorage.getItem(listKey)).toBe('40');
    expect(translation).toHaveValue('Unsaved translation');

    unmount();
    render(layout());
    expect(screen.getByRole('separator', { name: 'Resize pages' })).toHaveAttribute(
      'aria-valuenow',
      '36',
    );
  });

  it('collapses the sidebar without losing its input and restores its previous width', () => {
    render(layout({ collapsible: true }));
    const search = screen.getByRole('textbox', { name: 'Page search' });
    const handle = screen.getByRole('separator', { name: 'Resize pages' });
    fireEvent.change(search, { target: { value: 'guides' } });
    fireEvent.keyDown(handle, { key: 'ArrowRight' });
    fireEvent.click(screen.getByRole('button', { name: 'Collapse pages' }));

    expect(handle).toHaveAttribute('aria-valuenow', '0');
    expect(search).toBeInTheDocument();
    expect(search).not.toBeVisible();
    expect(search).toHaveValue('guides');
    expect(screen.getByRole('textbox', { name: 'Translation' })).toBeVisible();
    expect(window.localStorage.getItem(previewKey)).toBe('36');

    fireEvent.click(screen.getByRole('button', { name: 'Expand pages' }));
    expect(handle).toHaveAttribute('aria-valuenow', '36');
    expect(screen.getByRole('textbox', { name: 'Page search' })).toBe(search);
    expect(search).toBeVisible();
    expect(search).toHaveValue('guides');

    fireEvent.click(screen.getByRole('button', { name: 'Collapse pages' }));
    fireEvent.keyDown(handle, { key: 'ArrowRight' });
    expect(handle).toHaveAttribute('aria-valuenow', '36');
    expect(search).toBeVisible();
  });

  it('shows the full sidebar when detail is closed and restores the split without remounting', () => {
    const { rerender, container } = render(layout());
    const search = screen.getByRole('textbox', { name: 'Page search' });
    const translation = screen.getByRole('textbox', { name: 'Translation' });
    const handle = screen.getByRole('separator', { name: 'Resize pages' });
    fireEvent.keyDown(handle, { key: 'ArrowLeft' });
    fireEvent.change(translation, { target: { value: 'Keep this draft' } });

    rerender(layout({ detailVisible: false }));
    expect(container.firstElementChild).toHaveClass(
      'resizable-master-detail-layout--detail-hidden',
    );
    expect(search).toBeVisible();
    expect(handle).not.toBeVisible();
    expect(screen.queryByRole('separator')).not.toBeInTheDocument();
    expect(translation).toBeInTheDocument();
    expect(translation).not.toBeVisible();

    rerender(layout());
    expect(container.firstElementChild).not.toHaveClass(
      'resizable-master-detail-layout--detail-hidden',
    );
    expect(handle).toBeVisible();
    expect(handle).toHaveAttribute('aria-valuenow', '32');
    expect(screen.getByRole('textbox', { name: 'Page search' })).toBe(search);
    expect(screen.getByRole('textbox', { name: 'Translation' })).toBe(translation);
    expect(translation).toHaveValue('Keep this draft');
  });

  it('uses controlled collapse requests for the button and keyboard without remounting content', () => {
    const onCollapsedChange = vi.fn();
    const props = { collapsible: true, collapsed: false, onCollapsedChange };
    const { rerender } = render(layout(props));
    const search = screen.getByRole('textbox', { name: 'Page search' });
    const handle = screen.getByRole('separator', { name: 'Resize pages' });
    fireEvent.change(search, { target: { value: 'guides' } });
    fireEvent.click(screen.getByRole('button', { name: 'Collapse pages' }));
    expect(onCollapsedChange).toHaveBeenLastCalledWith(true);
    expect(search).toBeVisible();

    rerender(layout({ ...props, collapsed: true }));
    expect(search).not.toBeVisible();
    expect(handle).toHaveAttribute('aria-valuenow', '0');
    fireEvent.keyDown(handle, { key: 'ArrowRight' });
    expect(onCollapsedChange).toHaveBeenLastCalledWith(false);
    expect(search).not.toBeVisible();

    rerender(layout(props));
    expect(screen.getByRole('textbox', { name: 'Page search' })).toBe(search);
    expect(search).toHaveValue('guides');
    expect(handle).toHaveAttribute('aria-valuenow', '34');
  });

  it('reports controlled collapse and restoration during the same pointer resize', () => {
    const onCollapsedChange = vi.fn();
    const props = { collapsible: true, collapsed: false, onCollapsedChange };
    const { container, rerender } = render(layout(props));
    const root = container.firstElementChild as HTMLElement;
    vi.spyOn(root, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 0, 1000, 600));
    const handle = screen.getByRole('separator', { name: 'Resize pages' });
    pointer(handle, 'pointerdown', 440);
    pointer(window, 'pointermove', 140);
    expect(onCollapsedChange).toHaveBeenLastCalledWith(true);
    rerender(layout({ ...props, collapsed: true }));
    expect(handle).toHaveAttribute('aria-valuenow', '0');
    pointer(window, 'pointermove', 500);
    expect(onCollapsedChange).toHaveBeenLastCalledWith(false);
    rerender(layout(props));
    expect(handle).toHaveAttribute('aria-valuenow', '40');
    pointer(window, 'pointerup', 500);
  });

  it('keeps the sidebar visible when detail is closed even if controlled collapse is requested', () => {
    const onCollapsedChange = vi.fn();
    const props = { collapsible: true, collapsed: true, onCollapsedChange };
    const { rerender } = render(layout(props));
    const search = screen.getByRole('textbox', { name: 'Page search', hidden: true });
    expect(search).not.toBeVisible();
    rerender(layout({ ...props, detailVisible: false }));
    expect(search).toBeVisible();
    expect(screen.queryByRole('separator')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /pages/ })).not.toBeInTheDocument();
    expect(onCollapsedChange).not.toHaveBeenCalled();
    rerender(layout(props));
    expect(search).not.toBeVisible();
    expect(screen.getByRole('button', { name: 'Expand pages' })).toBeVisible();

    rerender(layout({ ...props, collapsible: false }));
    expect(search).toBeVisible();
    expect(screen.queryByRole('button', { name: /pages/ })).not.toBeInTheDocument();
  });

  it('bounds pointer resizing, stops at pointer release, and removes listeners on unmount', () => {
    const { container, unmount } = render(layout());
    const root = container.firstElementChild as HTMLElement;
    vi.spyOn(root, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 0, 1000, 600));
    const handle = screen.getByRole('separator', { name: 'Resize pages' });

    pointer(handle, 'pointerdown', 440);
    pointer(window, 'pointermove', 500);
    expect(handle).toHaveAttribute('aria-valuenow', '40');
    pointer(window, 'pointermove', 1000);
    expect(handle).toHaveAttribute('aria-valuenow', '48');
    pointer(window, 'pointermove', 100);
    expect(handle).toHaveAttribute('aria-valuenow', '24');
    pointer(window, 'pointerup', 100);
    pointer(window, 'pointermove', 500);
    expect(handle).toHaveAttribute('aria-valuenow', '24');

    pointer(handle, 'pointerdown', 480);
    expect(window.localStorage.getItem(previewKey)).toBe('38');
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    unmount();
    pointer(window, 'pointermove', 560);
    pointer(window, 'pointerup', 560);
    expect(setItem).not.toHaveBeenCalled();
    expect(window.localStorage.getItem(previewKey)).toBe('38');
  });

  it('cancels an active resize before changing the storage key or hiding detail', () => {
    const { container, rerender } = render(layout());
    const root = container.firstElementChild as HTMLElement;
    vi.spyOn(root, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 0, 1000, 600));
    const handle = screen.getByRole('separator', { name: 'Resize pages' });
    pointer(handle, 'pointerdown', 460);
    rerender(layout({ storageKey: listKey }));
    pointer(window, 'pointermove', 580);
    expect(handle).toHaveAttribute('aria-valuenow', '34');
    expect(window.localStorage.getItem(previewKey)).toBe('36');
    expect(window.localStorage.getItem(listKey)).toBeNull();

    pointer(handle, 'pointerdown', 500);
    rerender(layout({ storageKey: listKey, detailVisible: false }));
    pointer(window, 'pointermove', 580);
    rerender(layout({ storageKey: listKey }));
    expect(handle).toHaveAttribute('aria-valuenow', '40');
    expect(window.localStorage.getItem(listKey)).toBe('40');
  });
});
