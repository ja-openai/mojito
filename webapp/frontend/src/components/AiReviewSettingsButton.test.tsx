import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { AiReviewSettings } from '../hooks/useAiReviewPreferences';
import { AiReviewSettingsButton } from './AiReviewSettingsButton';

function renderSettings(overrides: Partial<AiReviewSettings> = {}) {
  const settings: AiReviewSettings = {
    preset: 'fast',
    automaticDisabled: false,
    ready: true,
    isSaving: false,
    error: null,
    onChangePreset: vi.fn(),
    onChangeAutomaticDisabled: vi.fn(),
    onRetryLoad: vi.fn(),
    ...overrides,
  };
  render(
    <>
      <AiReviewSettingsButton settings={settings} />
      <button>Outside</button>
    </>,
  );
  return settings;
}

describe('AiReviewSettingsButton', () => {
  it('opens the settings with the keyboard and returns focus on Escape', async () => {
    const user = userEvent.setup();
    renderSettings();
    const button = screen.getByRole('button', { name: 'AI review settings' });
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    await user.tab();
    await user.keyboard('{Enter}');
    expect(button).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('dialog')).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('checkbox', { name: 'Review automatically' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(button).toHaveFocus();
  });

  it('keeps the panel open when changing settings and dismisses an outside click', async () => {
    const user = userEvent.setup();
    const settings = renderSettings();
    await user.click(screen.getByRole('button', { name: 'AI review settings' }));
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(screen.queryByRole('slider')).not.toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Review automatically' }));
    expect(settings.onChangeAutomaticDisabled).toHaveBeenCalledWith(true);
    expect(screen.getByRole('dialog')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Outside' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('contains page shortcuts while keeping keyboard access to its controls', async () => {
    const user = userEvent.setup();
    const pageShortcut = vi.fn();
    window.addEventListener('keydown', pageShortcut);
    try {
      renderSettings();
      await user.click(screen.getByRole('button', { name: 'AI review settings' }));
      expect(screen.getByRole('dialog')).toHaveFocus();

      await user.keyboard('a{ArrowDown}j');
      expect(pageShortcut).not.toHaveBeenCalled();
      expect(screen.getByRole('dialog')).toHaveFocus();

      await user.tab();
      expect(screen.getByRole('checkbox', { name: 'Review automatically' })).toHaveFocus();
      await user.keyboard('{ArrowDown}');
      expect(pageShortcut).not.toHaveBeenCalled();
    } finally {
      window.removeEventListener('keydown', pageShortcut);
    }
  });

  it('leaves failed initial settings accessible so loading can be retried', () => {
    const settings = renderSettings({ ready: false, error: 'Could not load AI review settings.' });
    const button = screen.getByRole('button', { name: 'AI review settings' });
    expect(button).toBeEnabled();
    expect(button).toHaveAttribute('title', 'AI review settings need attention');
    fireEvent.click(button);
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(screen.getByRole('checkbox')).toBeDisabled();
    expect(screen.getByRole('alert')).toHaveTextContent(settings.error!);
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(settings.onRetryLoad).toHaveBeenCalledOnce();
  });
});
