import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadVisibleTextEditorEnabled } from '../../utils/visibleTextEditorPreference';
import { SettingsPage } from './SettingsPage';

vi.mock('../../hooks/useRepositories', () => ({
  useRepositories: () => ({ data: [], isLoading: false, isError: false }),
}));

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({
    username: 'translator',
    role: 'ROLE_TRANSLATOR',
    canTranslateAllLocales: false,
    userLocales: [],
  }),
}));

describe('SettingsPage', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('shows and persists the assisted translation editor opt-in', async () => {
    const user = userEvent.setup();
    render(<SettingsPage />);

    expect(screen.getByRole('heading', { name: 'Translation editor' })).toBeInTheDocument();

    const assistedEditorToggle = screen.getByRole('checkbox', {
      name: /Use the assisted rich text editor in Workbench, Review Project, and text unit details/,
    });
    expect(assistedEditorToggle).not.toBeChecked();
    expect(loadVisibleTextEditorEnabled()).toBe(false);

    await user.click(assistedEditorToggle);

    expect(assistedEditorToggle).toBeChecked();
    expect(loadVisibleTextEditorEnabled()).toBe(true);
  });
});
