import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MultiSelectChip } from './MultiSelectChip';

describe('MultiSelectChip', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
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
