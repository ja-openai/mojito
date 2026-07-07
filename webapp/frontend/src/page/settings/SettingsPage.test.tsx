import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  loadVisibleTextEditorEnabled,
  VISIBLE_TEXT_EDITOR_ENABLED_KEY,
} from '../../utils/visibleTextEditorPreference';
import { SettingsPage } from './SettingsPage';

const TEST_USERNAME = 'translator';

function renderSettingsPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <SettingsPage />
    </QueryClientProvider>,
  );
}

vi.mock('../../hooks/useRepositories', () => ({
  useRepositories: () => ({ data: [], isLoading: false, isError: false }),
}));

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({
    username: TEST_USERNAME,
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
    window.localStorage.setItem(VISIBLE_TEXT_EDITOR_ENABLED_KEY, 'true');
    renderSettingsPage();

    expect(screen.getByRole('heading', { name: 'Translation editor' })).toBeInTheDocument();

    const assistedEditorToggle = screen.getByRole('checkbox', {
      name: /Use the assisted rich text editor in Workbench, Review Project, and text unit details/,
    });
    expect(assistedEditorToggle).not.toBeChecked();
    expect(loadVisibleTextEditorEnabled(TEST_USERNAME)).toBe(false);
    expect(
      screen.getByText(
        'Saved separately for each Mojito user in this browser. New users start with it off.',
      ),
    ).toBeInTheDocument();

    await user.click(assistedEditorToggle);

    expect(assistedEditorToggle).toBeChecked();
    expect(loadVisibleTextEditorEnabled(TEST_USERNAME)).toBe(true);
    expect(loadVisibleTextEditorEnabled('admin')).toBe(false);
  });
});
