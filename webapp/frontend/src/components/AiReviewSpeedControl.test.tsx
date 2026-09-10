import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { AiReviewSpeedControl } from './AiReviewSpeedControl';

const automaticEnabled = { automaticDisabled: false, onChangeAutomaticDisabled: vi.fn() };

describe('AiReviewSpeedControl', () => {
  it('explains confidence beside Show score on hover, focus and click without changing preferences', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const onChangeAutomaticDisabled = vi.fn();
    const onChangeReviewStyle = vi.fn();
    const onChangeShowScore = vi.fn();
    render(
      <AiReviewSpeedControl
        value="balanced"
        onChange={onChange}
        automaticDisabled={false}
        onChangeAutomaticDisabled={onChangeAutomaticDisabled}
        onChangeReviewStyle={onChangeReviewStyle}
        showScore={false}
        onChangeShowScore={onChangeShowScore}
      />,
    );
    expect(
      screen.queryByRole('button', { name: 'About model confidence' }),
    ).not.toBeInTheDocument();
    const trigger = screen.getByRole('button', { name: 'Review speed: Balanced' });
    await user.click(trigger);
    const panel = screen.getByRole('dialog', { name: 'Review speed' });
    const score = within(panel).getByRole('checkbox', { name: 'Show score' });
    const info = within(panel).getByRole('button', { name: 'About model confidence' });
    expect(score).not.toBeChecked();
    expect(score.closest('label')).not.toContainElement(info);
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();

    await user.hover(info);
    expect(screen.getByRole('tooltip')).toHaveTextContent('self-reported confidence');
    expect(screen.getByRole('tooltip')).toHaveTextContent(
      'not a translation quality rating or a measured probability',
    );
    expect(info).toHaveAccessibleDescription(/self-reported confidence/);
    await user.unhover(info);
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();

    score.focus();
    await user.tab();
    expect(info).toHaveFocus();
    expect(screen.getByRole('tooltip')).toBeVisible();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();

    await user.click(trigger);
    await user.click(screen.getByRole('button', { name: 'About model confidence' }));
    expect(screen.getByRole('tooltip')).toBeVisible();
    expect(onChange).not.toHaveBeenCalled();
    expect(onChangeAutomaticDisabled).not.toHaveBeenCalled();
    expect(onChangeReviewStyle).not.toHaveBeenCalled();
    expect(onChangeShowScore).not.toHaveBeenCalled();
  });

  it('defaults to alternatives and visible scores and changes each preference independently', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const onChangeAutomaticDisabled = vi.fn();
    const onChangeReviewStyle = vi.fn();
    const onChangeShowScore = vi.fn();
    render(
      <AiReviewSpeedControl
        value="balanced"
        onChange={onChange}
        automaticDisabled
        onChangeAutomaticDisabled={onChangeAutomaticDisabled}
        onChangeReviewStyle={onChangeReviewStyle}
        onChangeShowScore={onChangeShowScore}
      />,
    );
    await user.click(screen.getByRole('button', { name: 'Review speed: Balanced' }));
    const style = screen.getByRole('combobox', { name: 'Review style' });
    expect(style).toHaveValue('corrections_and_alternatives');
    const score = screen.getByRole('checkbox', { name: 'Show score' });
    expect(score).toBeChecked();
    await user.selectOptions(style, 'corrections_only');
    expect(onChangeReviewStyle).toHaveBeenCalledExactlyOnceWith('corrections_only');
    await user.click(score);
    expect(onChangeShowScore).toHaveBeenCalledExactlyOnceWith(false);
    expect(onChange).not.toHaveBeenCalled();
    expect(onChangeAutomaticDisabled).not.toHaveBeenCalled();
    expect(screen.getByRole('checkbox', { name: 'Automatic review' })).not.toBeChecked();
  });

  it('keeps speed and automatic review independent and shows the paused state', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const onChangeAutomaticDisabled = vi.fn();
    const { rerender } = render(
      <AiReviewSpeedControl
        {...automaticEnabled}
        value="balanced"
        onChange={onChange}
        automaticDisabled={false}
        onChangeAutomaticDisabled={onChangeAutomaticDisabled}
      />,
    );
    const button = screen.getByRole('button', { name: 'Review speed: Balanced' });
    await user.click(button);
    const automatic = screen.getByRole('checkbox', { name: 'Automatic review' });
    expect(automatic).toBeChecked();
    await user.click(automatic);
    expect(onChangeAutomaticDisabled).toHaveBeenCalledExactlyOnceWith(true);
    expect(onChange).not.toHaveBeenCalled();
    expect(automatic).toBeChecked();
    rerender(
      <AiReviewSpeedControl
        {...automaticEnabled}
        value="balanced"
        onChange={onChange}
        automaticDisabled
        onChangeAutomaticDisabled={onChangeAutomaticDisabled}
      />,
    );
    expect(button).toHaveTextContent('BalancedAuto off');
    expect(button).toHaveAccessibleDescription('Auto off');
    expect(automatic).not.toBeChecked();
    expect(automatic).toHaveAccessibleDescription('Automatic review paused.');
    const slider = screen.getByRole('slider', { name: 'Review speed' });
    fireEvent.change(slider, { target: { value: '0' } });
    expect(slider).toHaveAttribute('aria-valuetext', 'Fastest');
    fireEvent.keyUp(slider, { key: 'Home' });
    expect(onChange).toHaveBeenCalledExactlyOnceWith('fastest');
    expect(onChangeAutomaticDisabled).toHaveBeenCalledOnce();
    await user.click(automatic);
    expect(onChangeAutomaticDisabled).toHaveBeenLastCalledWith(false);
    expect(onChange).toHaveBeenCalledOnce();
  });

  it('keeps load-error recovery accessible while speed selection is disabled', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const onChange = vi.fn();
    render(
      <AiReviewSpeedControl
        {...automaticEnabled}
        value="balanced"
        onChange={onChange}
        disabled
        error="Could not load AI review settings."
        onRetry={onRetry}
      />,
    );
    await user.click(screen.getByRole('button', { name: 'Review speed: Balanced' }));
    expect(screen.getByRole('slider')).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('checkbox', { name: 'Automatic review' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Could not load AI review settings.');
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledOnce();
    expect(onChange).not.toHaveBeenCalled();
  });

  it('keeps the automatic toggle focused and blocks changes while saving', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const onChangeAutomaticDisabled = vi.fn();
    const props = { value: 'balanced' as const, onChange, onChangeAutomaticDisabled };
    const { rerender } = render(<AiReviewSpeedControl {...props} automaticDisabled={false} />);
    const button = screen.getByRole('button', { name: 'Review speed: Balanced' });
    await user.click(button);
    const automatic = screen.getByRole('checkbox', { name: 'Automatic review' });
    await user.click(automatic);
    expect(onChangeAutomaticDisabled).toHaveBeenCalledExactlyOnceWith(true);
    rerender(<AiReviewSpeedControl {...props} automaticDisabled={false} disabled />);
    expect(automatic).toHaveFocus();
    await user.keyboard(' ');
    await user.click(automatic);
    expect(onChangeAutomaticDisabled).toHaveBeenCalledOnce();
    expect(automatic).toBeChecked();
    rerender(
      <AiReviewSpeedControl
        {...props}
        automaticDisabled={false}
        error="Could not save automatic review."
      />,
    );
    expect(automatic).toBeChecked();
    expect(screen.getByRole('alert')).toHaveTextContent('Could not save automatic review.');
    expect(onChange).not.toHaveBeenCalled();
    rerender(<AiReviewSpeedControl {...props} automaticDisabled={false} disabled />);
    await user.keyboard('{Escape}');
    expect(button).toHaveFocus();
  });

  it('previews a drag and commits once on pointer release', () => {
    const onChange = vi.fn();
    render(<AiReviewSpeedControl {...automaticEnabled} value="fastest" onChange={onChange} />);
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
    render(<AiReviewSpeedControl {...automaticEnabled} value="fastest" onChange={onChange} />);
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
        <AiReviewSpeedControl {...automaticEnabled} value="fastest" onChange={onChange} />
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
    const { rerender } = render(
      <AiReviewSpeedControl {...automaticEnabled} value="fastest" onChange={onChange} />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Review speed: Fastest' }));
    const slider = screen.getByRole('slider');
    fireEvent.change(slider, { target: { value: '5' } });
    fireEvent.pointerUp(slider);
    rerender(
      <AiReviewSpeedControl
        {...automaticEnabled}
        value="fastest"
        onChange={onChange}
        error="Could not save speed."
      />,
    );
    expect(slider).toHaveValue('0');
    expect(screen.getByRole('alert')).toHaveTextContent('Could not save speed.');
    expect(screen.getByRole('dialog')).toBeVisible();
    rerender(<AiReviewSpeedControl {...automaticEnabled} value="balanced" onChange={onChange} />);
    expect(slider).toHaveValue('2');
    expect(screen.getByRole('button', { name: 'Review speed: Balanced' })).toBeVisible();
  });

  it('blocks interaction while disabled, including an already-open slider', () => {
    const onChange = vi.fn();
    const { rerender } = render(
      <AiReviewSpeedControl {...automaticEnabled} value="fastest" onChange={onChange} disabled />,
    );
    const button = screen.getByRole('button', { name: 'Review speed: Fastest' });
    fireEvent.click(button);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    rerender(<AiReviewSpeedControl {...automaticEnabled} value="fastest" onChange={onChange} />);
    fireEvent.click(button);
    rerender(
      <AiReviewSpeedControl {...automaticEnabled} value="fastest" onChange={onChange} disabled />,
    );
    const slider = screen.getByRole('slider');
    expect(slider).toHaveAttribute('aria-disabled', 'true');
    expect(slider).toHaveFocus();
    expect(fireEvent.keyDown(slider, { key: 'ArrowRight' })).toBe(false);
    fireEvent.change(slider, { target: { value: '5' } });
    fireEvent.pointerUp(slider);
    expect(onChange).not.toHaveBeenCalled();
    expect(slider).toHaveValue('0');
    fireEvent.keyDown(slider, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(button).toHaveFocus();
  });
});
