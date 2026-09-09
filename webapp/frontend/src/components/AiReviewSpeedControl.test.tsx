import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { AiReviewSpeedControl } from './AiReviewSpeedControl';

describe('AiReviewSpeedControl', () => {
  it('previews a drag and commits once on pointer release', () => {
    const onChange = vi.fn();
    render(<AiReviewSpeedControl value="fastest" onChange={onChange} />);
    fireEvent.click(screen.getByRole('button', { name: 'Review speed: Fastest' }));
    const slider = screen.getByRole('slider', { name: 'Review speed' });
    expect(slider).toHaveAttribute('max', '5');
    fireEvent.pointerDown(slider);
    fireEvent.change(slider, { target: { value: '1' } });
    fireEvent.change(slider, { target: { value: '5' } });
    expect(slider).toHaveAttribute('aria-valuetext', 'Ultra');
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Review speed: Fastest' })).toBeVisible();
    fireEvent.pointerUp(slider);
    fireEvent.pointerUp(slider);
    expect(onChange).toHaveBeenCalledExactlyOnceWith('ultra');
    expect(screen.getByRole('dialog', { name: 'Review speed' })).toBeVisible();
  });

  it('commits keyboard changes on key release and contains page shortcuts', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const pageShortcut = vi.fn();
    render(<AiReviewSpeedControl value="fastest" onChange={onChange} />);
    await user.click(screen.getByRole('button', { name: 'Review speed: Fastest' }));
    const slider = screen.getByRole('slider', { name: 'Review speed' });
    expect(slider).toHaveFocus();
    window.addEventListener('keydown', pageShortcut);
    try {
      const stops = [
        ['fast', 'Fast'],
        ['balanced', 'Balanced'],
        ['thorough', 'Thorough'],
        ['deep', 'Deep'],
        ['ultra', 'Ultra'],
      ];
      for (const [index, [preset, label]] of stops.entries()) {
        fireEvent.keyDown(slider, { key: 'ArrowRight' });
        fireEvent.change(slider, { target: { value: String(index + 1) } });
        expect(slider).toHaveAttribute('aria-valuetext', label);
        expect(onChange).toHaveBeenCalledTimes(index);
        fireEvent.keyUp(slider, { key: 'ArrowRight' });
        expect(onChange).toHaveBeenNthCalledWith(index + 1, preset);
      }
      fireEvent.keyDown(slider, { key: 'Home' });
      fireEvent.change(slider, { target: { value: '0' } });
      expect(onChange).toHaveBeenCalledTimes(5);
      fireEvent.keyUp(slider, { key: 'Home' });
      expect(onChange).toHaveBeenNthCalledWith(6, 'fastest');
      await user.keyboard('j{Escape}');
      expect(pageShortcut).not.toHaveBeenCalled();
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Review speed: Fastest' })).toHaveFocus();
    } finally {
      window.removeEventListener('keydown', pageShortcut);
    }
  });

  it('discards an uncommitted drag on cancellation or outside dismissal', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <>
        <AiReviewSpeedControl value="fastest" onChange={onChange} />
        <button>Outside</button>
      </>,
    );
    await user.click(screen.getByRole('button', { name: 'Review speed: Fastest' }));
    const slider = screen.getByRole('slider');
    fireEvent.change(slider, { target: { value: '5' } });
    fireEvent.pointerCancel(slider);
    expect(slider).toHaveValue('0');
    fireEvent.change(slider, { target: { value: '1' } });
    await user.click(screen.getByRole('button', { name: 'Outside' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Review speed: Fastest' }));
    expect(screen.getByRole('slider')).toHaveValue('0');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('resyncs confirmed values and reverts the preview when a save fails', () => {
    const onChange = vi.fn();
    const { rerender } = render(<AiReviewSpeedControl value="fastest" onChange={onChange} />);
    fireEvent.click(screen.getByRole('button', { name: 'Review speed: Fastest' }));
    const slider = screen.getByRole('slider');
    fireEvent.change(slider, { target: { value: '5' } });
    fireEvent.pointerUp(slider);
    rerender(
      <AiReviewSpeedControl value="fastest" onChange={onChange} error="Could not save speed." />,
    );
    expect(slider).toHaveValue('0');
    expect(screen.getByRole('alert')).toHaveTextContent('Could not save speed.');
    expect(screen.getByRole('dialog')).toBeVisible();
    rerender(<AiReviewSpeedControl value="balanced" onChange={onChange} />);
    expect(slider).toHaveValue('2');
    expect(screen.getByRole('button', { name: 'Review speed: Balanced' })).toBeVisible();
  });

  it('blocks interaction while disabled, including an already-open slider', () => {
    const onChange = vi.fn();
    const { rerender } = render(
      <AiReviewSpeedControl value="fastest" onChange={onChange} disabled />,
    );
    const button = screen.getByRole('button', { name: 'Review speed: Fastest' });
    fireEvent.click(button);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    rerender(<AiReviewSpeedControl value="fastest" onChange={onChange} />);
    fireEvent.click(button);
    rerender(<AiReviewSpeedControl value="fastest" onChange={onChange} disabled />);
    const slider = screen.getByRole('slider');
    expect(slider).toHaveAttribute('aria-disabled', 'true');
    expect(slider).toHaveFocus();
    expect(fireEvent.keyDown(slider, { key: 'ArrowRight' })).toBe(false);
    fireEvent.change(slider, { target: { value: '5' } });
    fireEvent.pointerUp(slider);
    expect(onChange).not.toHaveBeenCalled();
    expect(slider).toHaveValue('0');
  });
});
