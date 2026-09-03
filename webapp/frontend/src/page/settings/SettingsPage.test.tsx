import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  loadReviewProjectSearchEnabled,
  saveReviewProjectSearchEnabled,
} from '../../utils/reviewProjectSearchPreference';
import {
  loadVisibleTextEditorEnabled,
  VISIBLE_TEXT_EDITOR_ENABLED_KEY,
} from '../../utils/visibleTextEditorPreference';
import { SettingsPage } from './SettingsPage';

const TEST_USERNAME = 'translator';
let currentUsername = TEST_USERNAME;

function renderSettingsPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(<SettingsPage />, {
    wrapper: ({ children }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    ),
  });
}

vi.mock('../../hooks/useRepositories', () => ({
  useRepositories: () => ({ data: [], isLoading: false, isError: false }),
}));

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({
    username: currentUsername,
    role: 'ROLE_TRANSLATOR',
    canTranslateAllLocales: false,
    userLocales: [],
  }),
}));

describe('SettingsPage', () => {
  beforeEach(() => {
    window.localStorage.clear();
    currentUsername = TEST_USERNAME;
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

  it('stages Search opt-in and opt-out until Save and preserves the saved setting on reload', async () => {
    const user = userEvent.setup();
    const view = renderSettingsPage();
    const section = within(screen.getByRole('region', { name: 'Review Project search' }));
    const toggle = section.getByRole('checkbox', { name: /Show the Search tab in Review Project/ });
    const save = section.getByRole('button', { name: 'Save' });

    expect(toggle).not.toBeChecked();
    expect(save).toBeDisabled();
    expect(section.getByRole('button', { name: 'Reset' })).toBeDisabled();
    expect(
      section.getByText(
        'Search current translations across repositories. Off by default; enable it to try the preview.',
      ),
    ).toBeInTheDocument();
    await user.click(toggle);
    expect(toggle).toBeChecked();
    expect(loadReviewProjectSearchEnabled(TEST_USERNAME)).toBe(false);
    await user.click(save);
    expect(loadReviewProjectSearchEnabled(TEST_USERNAME)).toBe(true);
    expect(loadReviewProjectSearchEnabled('other-user')).toBe(false);
    expect(save).toBeDisabled();

    view.unmount();
    renderSettingsPage();
    const reloaded = within(screen.getByRole('region', { name: 'Review Project search' }));
    const reloadedToggle = reloaded.getByRole('checkbox', {
      name: /Show the Search tab in Review Project/,
    });
    expect(reloadedToggle).toBeChecked();
    await user.click(reloadedToggle);
    expect(loadReviewProjectSearchEnabled(TEST_USERNAME)).toBe(true);
    await user.click(reloaded.getByRole('button', { name: 'Save' }));
    expect(loadReviewProjectSearchEnabled(TEST_USERNAME)).toBe(false);
    expect(reloaded.getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  it('keeps Search Reset as a draft until Save and discards unsaved changes on navigation', async () => {
    const user = userEvent.setup();
    saveReviewProjectSearchEnabled(true, TEST_USERNAME);
    const view = renderSettingsPage();
    const section = within(screen.getByRole('region', { name: 'Review Project search' }));

    await user.click(section.getByRole('button', { name: 'Reset' }));
    expect(section.getByRole('checkbox', { name: /Show the Search tab/ })).not.toBeChecked();
    expect(loadReviewProjectSearchEnabled(TEST_USERNAME)).toBe(true);
    expect(section.getByRole('button', { name: 'Save' })).toBeEnabled();

    view.unmount();
    renderSettingsPage();
    expect(screen.getByRole('checkbox', { name: /Show the Search tab/ })).toBeChecked();
  });

  it('switches Search saved state and drafts with the signed-in account', async () => {
    const user = userEvent.setup();
    saveReviewProjectSearchEnabled(true, TEST_USERNAME);
    const view = renderSettingsPage();
    const searchSection = () =>
      within(screen.getByRole('region', { name: 'Review Project search' }));
    expect(searchSection().getByRole('checkbox', { name: /Show the Search tab/ })).toBeChecked();
    await user.click(searchSection().getByRole('checkbox', { name: /Show the Search tab/ }));

    currentUsername = 'other-user';
    view.rerender(<SettingsPage />);
    expect(
      searchSection().getByRole('checkbox', { name: /Show the Search tab/ }),
    ).not.toBeChecked();
    expect(searchSection().getByRole('button', { name: 'Save' })).toBeDisabled();
    await user.click(searchSection().getByRole('checkbox', { name: /Show the Search tab/ }));
    await user.click(searchSection().getByRole('button', { name: 'Save' }));
    expect(loadReviewProjectSearchEnabled('other-user')).toBe(true);
    expect(loadReviewProjectSearchEnabled(TEST_USERNAME)).toBe(true);

    currentUsername = TEST_USERNAME;
    view.rerender(<SettingsPage />);
    expect(searchSection().getByRole('checkbox', { name: /Show the Search tab/ })).toBeChecked();
    expect(searchSection().getByRole('button', { name: 'Save' })).toBeDisabled();
  });
});
