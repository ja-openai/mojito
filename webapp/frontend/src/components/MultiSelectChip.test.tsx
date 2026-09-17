import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MultiSelectChip } from './MultiSelectChip';

describe('MultiSelectChip', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('replaces a single selection and keeps the shared search and option metadata', async () => {
    const user = userEvent.setup();
    const handleChange = vi.fn();
    render(
      <MultiSelectChip
        label="Repository"
        options={[
          { value: 1, label: 'Website' },
          {
            value: 2,
            label: 'Help center',
            secondaryLabel: 'Documentation',
            searchText: 'support',
          },
        ]}
        selectedValues={[1]}
        onChange={handleChange}
        selectionMode="single"
        placeholder="Repository"
        emptyOptionsLabel="No repositories"
        quickActions={[{ label: 'All', onClick: vi.fn() }]}
        customActions={[{ label: 'Favorites', onClick: vi.fn() }]}
      />,
    );

    const trigger = screen.getByRole('button', { name: 'Repository' });
    await user.click(trigger);
    const search = screen.getByRole('searchbox');
    expect(search).toHaveFocus();
    expect(within(screen.getByRole('menu')).queryByRole('button')).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    await user.type(search, 'support');
    expect(screen.queryByRole('radio', { name: 'Website' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: 'Help center Documentation' }));

    expect(handleChange).toHaveBeenCalledExactlyOnceWith([2]);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('supports keyboard access to single-choice radios and Escape without changing selection', async () => {
    const user = userEvent.setup();
    const handleChange = vi.fn();
    render(
      <MultiSelectChip
        label="Repository"
        options={[{ value: 1, label: 'Website' }]}
        selectedValues={[]}
        onChange={handleChange}
        selectionMode="single"
        placeholder="Repository"
        emptyOptionsLabel="No repositories"
      />,
    );

    const trigger = screen.getByRole('button', { name: 'Repository' });
    await user.tab();
    expect(trigger).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(screen.getByRole('searchbox')).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('radio', { name: 'Website' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    expect(handleChange).not.toHaveBeenCalled();

    await user.keyboard('{Enter}');
    await user.tab();
    await user.keyboard(' ');
    expect(handleChange).toHaveBeenCalledExactlyOnceWith([1]);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('retains checkbox toggles and bulk actions by default', () => {
    const handleChange = vi.fn();
    render(
      <MultiSelectChip
        label="Repositories"
        options={[
          { value: 1, label: 'Website' },
          { value: 2, label: 'Help center' },
        ]}
        selectedValues={[1]}
        onChange={handleChange}
        placeholder="Repositories"
        emptyOptionsLabel="No repositories"
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Repositories' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Help center Only' }));
    expect(handleChange).toHaveBeenLastCalledWith([1, 2]);
    expect(screen.getByRole('menu')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Clear' }));
    expect(handleChange).toHaveBeenLastCalledWith([]);
    fireEvent.click(screen.getByRole('button', { name: 'Select all' }));
    expect(handleChange).toHaveBeenLastCalledWith([1, 2]);
    fireEvent.click(screen.getAllByRole('button', { name: 'Only' })[1]);
    expect(handleChange).toHaveBeenLastCalledWith([2]);
  });

  it('repositions an upward-opening panel when its contents grow', () => {
    vi.stubGlobal('innerHeight', 800);

    let panelHeight = 200;
    let triggerResize: (() => void) | undefined;
    const observe = vi.fn();

    class ResizeObserverMock {
      constructor(callback: ResizeObserverCallback) {
        triggerResize = () => callback([], this);
      }

      observe = observe;
      unobserve() {}
      disconnect() {}
    }

    vi.stubGlobal('ResizeObserver', ResizeObserverMock);
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (
      this: HTMLElement,
    ) {
      if (this.getAttribute('aria-label') === 'Select PMs') {
        return new DOMRect(100, 600, 160, 32);
      }
      if (this.getAttribute('role') === 'menu') {
        return new DOMRect(100, 0, 320, panelHeight);
      }
      return new DOMRect();
    });

    function TestPicker() {
      const [showAll, setShowAll] = useState(false);

      return (
        <MultiSelectChip
          label="PMs"
          options={
            showAll
              ? Array.from({ length: 20 }, (_, index) => ({
                  value: index,
                  label: `User ${index}`,
                }))
              : []
          }
          selectedValues={[]}
          onChange={() => {}}
          placeholder="Select PMs"
          emptyOptionsLabel="No PMs"
          buttonAriaLabel="Select PMs"
          customActions={[{ label: 'All users', onClick: () => setShowAll(true) }]}
        />
      );
    }

    render(<TestPicker />);
    fireEvent.click(screen.getByRole('button', { name: 'Select PMs' }));

    const menu = screen.getByRole('menu');
    expect(menu).toHaveStyle({ maxHeight: '576px', top: '392px' });
    expect(observe).toHaveBeenCalledWith(menu);

    panelHeight = 400;
    fireEvent.click(within(menu).getByRole('button', { name: 'All users' }));
    expect(within(menu).getByText('User 19')).toBeInTheDocument();
    act(() => triggerResize?.());

    expect(menu).toHaveStyle({ maxHeight: '576px', top: '192px' });
  });
});
