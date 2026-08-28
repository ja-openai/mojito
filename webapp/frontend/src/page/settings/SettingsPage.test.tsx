import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
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

  it('stages the assisted translation editor opt-in until Save', async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(VISIBLE_TEXT_EDITOR_ENABLED_KEY, 'true');
    renderSettingsPage();

    const translationEditorSection = screen
      .getByRole('heading', { name: 'Translation editor' })
      .closest('section');
    expect(translationEditorSection).not.toBeNull();
    const section = within(translationEditorSection as HTMLElement);

    const assistedEditorToggle = section.getByRole('checkbox', {
      name: /Use the assisted rich text editor in Workbench, Review Project, and text unit details/,
    });
    const saveButton = section.getByRole('button', { name: 'Save' });
    expect(assistedEditorToggle).not.toBeChecked();
    expect(saveButton).toBeDisabled();
    expect(loadVisibleTextEditorEnabled(TEST_USERNAME)).toBe(false);
    expect(
      screen.getByText(
        'Saved separately for each Mojito user in this browser after you select Save. New users start with it off.',
      ),
    ).toBeInTheDocument();

    await user.click(assistedEditorToggle);

    expect(assistedEditorToggle).toBeChecked();
    expect(saveButton).toBeEnabled();
    expect(loadVisibleTextEditorEnabled(TEST_USERNAME)).toBe(false);

    await user.click(saveButton);

    expect(loadVisibleTextEditorEnabled(TEST_USERNAME)).toBe(true);
    expect(loadVisibleTextEditorEnabled('admin')).toBe(false);
    expect(saveButton).toBeDisabled();
  });

  it('resets only the draft and discards unsaved changes on navigation', async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(`${VISIBLE_TEXT_EDITOR_ENABLED_KEY}.${TEST_USERNAME}`, 'true');
    const view = renderSettingsPage();
    const translationEditorSection = screen
      .getByRole('heading', { name: 'Translation editor' })
      .closest('section');
    expect(translationEditorSection).not.toBeNull();
    const section = within(translationEditorSection as HTMLElement);
    const assistedEditorToggle = section.getByRole('checkbox', {
      name: /Use the assisted rich text editor in Workbench, Review Project, and text unit details/,
    });

    expect(assistedEditorToggle).toBeChecked();
    await user.click(section.getByRole('button', { name: 'Reset' }));

    expect(assistedEditorToggle).not.toBeChecked();
    expect(loadVisibleTextEditorEnabled(TEST_USERNAME)).toBe(true);
    expect(section.getByRole('button', { name: 'Save' })).toBeEnabled();

    view.unmount();
    renderSettingsPage();

    expect(
      screen.getByRole('checkbox', {
        name: /Use the assisted rich text editor in Workbench, Review Project, and text unit details/,
      }),
    ).toBeChecked();
  });
});
