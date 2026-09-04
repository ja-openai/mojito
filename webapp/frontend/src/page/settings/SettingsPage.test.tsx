import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  loadReviewProjectSearchEnabled,
  saveReviewProjectSearchEnabled,
} from '../../utils/reviewProjectSearchPreference';
import {
  getVisibleTextEditorEnabledKey,
  loadVisibleTextEditorEnabled,
  saveVisibleTextEditorEnabled,
  VISIBLE_TEXT_EDITOR_ENABLED_KEY,
} from '../../utils/visibleTextEditorPreference';
import {
  loadReviewProjectShortcutHelpPreference,
  saveReviewProjectShortcutHelpPreference,
} from '../review-project/review-project-preferences';
import {
  loadPreferredLocales,
  loadPreferredWorksetSize,
  PREFERRED_LOCALES_KEY,
  savePreferredLocales,
  savePreferredWorksetSize,
} from '../workbench/workbench-preferences';
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

function editorToggle() {
  return screen.getByRole('checkbox', {
    name: /Use the assisted rich text editor in Workbench, Review Project, and text unit details/,
  });
}

function searchToggle() {
  return screen.getByRole('checkbox', {
    name: /Show Search in Review Project and text-unit details/,
  });
}

function shortcutToggle() {
  return screen.getByRole('checkbox', {
    name: /Show shortcut bar at the bottom of review projects/,
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
    userLocales: ['fr'],
  }),
}));

describe('SettingsPage', () => {
  beforeEach(() => {
    window.localStorage.clear();
    currentUsername = TEST_USERNAME;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('stages changes across sections and persists them with one Save changes button', async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(VISIBLE_TEXT_EDITOR_ENABLED_KEY, 'true');
    renderSettingsPage();

    const save = screen.getByRole('button', { name: 'Save changes' });
    expect(screen.getAllByRole('button', { name: 'Save changes' })).toHaveLength(1);
    expect(save).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Discard changes' })).toBeDisabled();
    expect(screen.getByText('No unsaved changes')).toBeInTheDocument();
    expect(editorToggle()).not.toBeChecked();
    expect(shortcutToggle()).toBeChecked();

    await user.type(screen.getByRole('spinbutton', { name: 'Result size limit' }), '25');
    await user.click(editorToggle());
    await user.click(searchToggle());
    await user.click(shortcutToggle());
    await user.click(screen.getByRole('button', { name: 'Select preferred locales' }));
    await user.click(screen.getByRole('button', { name: 'Select your locales' }));

    expect(loadPreferredWorksetSize()).toBeNull();
    expect(loadVisibleTextEditorEnabled(TEST_USERNAME)).toBe(false);
    expect(loadReviewProjectSearchEnabled(TEST_USERNAME)).toBe(false);
    expect(loadReviewProjectShortcutHelpPreference('bottom')).toBe('bottom');
    expect(loadPreferredLocales()).toEqual([]);
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument();
    expect(save).toBeEnabled();

    await user.click(save);

    expect(loadPreferredWorksetSize()).toBe(25);
    expect(loadVisibleTextEditorEnabled(TEST_USERNAME)).toBe(true);
    expect(loadVisibleTextEditorEnabled('admin')).toBe(false);
    expect(loadReviewProjectSearchEnabled(TEST_USERNAME)).toBe(true);
    expect(loadReviewProjectSearchEnabled('admin')).toBe(false);
    expect(loadReviewProjectShortcutHelpPreference('bottom')).toBe('header');
    expect(loadPreferredLocales()).toEqual(['fr']);
    expect(save).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Discard changes' })).toBeDisabled();
    expect(screen.getByText('Changes saved')).toBeInTheDocument();
  });

  it('stages Restore defaults across sections and lets Discard changes restore saved values', async () => {
    const user = userEvent.setup();
    savePreferredWorksetSize(25);
    savePreferredLocales(['fr']);
    saveVisibleTextEditorEnabled(true, TEST_USERNAME);
    saveReviewProjectSearchEnabled(true, TEST_USERNAME);
    saveReviewProjectShortcutHelpPreference('header', 'bottom');
    renderSettingsPage();

    const restoreDefaults = screen.getByRole('button', { name: 'Restore defaults' });
    const workset = screen.getByRole('spinbutton', { name: 'Result size limit' });
    await user.click(restoreDefaults);

    expect(workset).toHaveValue(null);
    expect(editorToggle()).not.toBeChecked();
    expect(searchToggle()).not.toBeChecked();
    expect(shortcutToggle()).toBeChecked();
    expect(screen.getByText('No preferred locales set.')).toBeInTheDocument();
    expect(loadPreferredWorksetSize()).toBe(25);
    expect(loadPreferredLocales()).toEqual(['fr']);
    expect(loadVisibleTextEditorEnabled(TEST_USERNAME)).toBe(true);
    expect(loadReviewProjectSearchEnabled(TEST_USERNAME)).toBe(true);
    expect(loadReviewProjectShortcutHelpPreference('bottom')).toBe('header');
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeEnabled();

    await user.click(screen.getByRole('button', { name: 'Discard changes' }));

    expect(workset).toHaveValue(25);
    expect(editorToggle()).toBeChecked();
    expect(searchToggle()).toBeChecked();
    expect(shortcutToggle()).not.toBeChecked();
    expect(screen.queryByText('No preferred locales set.')).not.toBeInTheDocument();
    expect(screen.getByText('No unsaved changes')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();

    await user.click(restoreDefaults);
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(loadPreferredWorksetSize()).toBeNull();
    expect(loadPreferredLocales()).toEqual([]);
    expect(loadVisibleTextEditorEnabled(TEST_USERNAME)).toBe(false);
    expect(loadReviewProjectSearchEnabled(TEST_USERNAME)).toBe(false);
    expect(loadReviewProjectShortcutHelpPreference('bottom')).toBe('bottom');
  });

  it('discards unsaved editor, Search, and shortcut changes on navigation', async () => {
    const user = userEvent.setup();
    const view = renderSettingsPage();
    await user.click(editorToggle());
    await user.click(searchToggle());
    await user.click(shortcutToggle());

    view.unmount();
    renderSettingsPage();

    expect(editorToggle()).not.toBeChecked();
    expect(searchToggle()).not.toBeChecked();
    expect(shortcutToggle()).toBeChecked();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
  });

  it('preserves saved Search on reload and stages opting out until Save changes', async () => {
    const user = userEvent.setup();
    const view = renderSettingsPage();
    expect(searchToggle()).not.toBeChecked();
    await user.click(searchToggle());
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    view.unmount();
    renderSettingsPage();
    expect(searchToggle()).toBeChecked();
    await user.click(searchToggle());
    expect(loadReviewProjectSearchEnabled(TEST_USERNAME)).toBe(true);
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(loadReviewProjectSearchEnabled(TEST_USERNAME)).toBe(false);
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
  });

  it('switches account preferences and discards their drafts when the signed-in account changes', async () => {
    const user = userEvent.setup();
    saveVisibleTextEditorEnabled(true, TEST_USERNAME);
    saveReviewProjectSearchEnabled(true, TEST_USERNAME);
    const view = renderSettingsPage();
    expect(editorToggle()).toBeChecked();
    expect(searchToggle()).toBeChecked();
    await user.click(editorToggle());
    await user.click(searchToggle());

    currentUsername = 'other-user';
    view.rerender(<SettingsPage />);
    expect(editorToggle()).not.toBeChecked();
    expect(searchToggle()).not.toBeChecked();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
    await user.click(editorToggle());
    await user.click(searchToggle());
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(loadVisibleTextEditorEnabled('other-user')).toBe(true);
    expect(loadReviewProjectSearchEnabled('other-user')).toBe(true);
    expect(loadVisibleTextEditorEnabled(TEST_USERNAME)).toBe(true);
    expect(loadReviewProjectSearchEnabled(TEST_USERNAME)).toBe(true);

    currentUsername = TEST_USERNAME;
    view.rerender(<SettingsPage />);
    expect(editorToggle()).toBeChecked();
    expect(searchToggle()).toBeChecked();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
  });

  it('blocks Save changes for an invalid result limit while allowing drafts to be discarded', async () => {
    const user = userEvent.setup();
    renderSettingsPage();
    const workset = screen.getByRole('spinbutton', { name: 'Result size limit' });

    await user.type(workset, '0');

    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Discard changes' })).toBeEnabled();
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument();
    expect(screen.getByText('Enter a positive whole number.')).toBeInTheDocument();
    await user.click(editorToggle());
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
    expect(loadVisibleTextEditorEnabled(TEST_USERNAME)).toBe(false);

    await user.click(screen.getByRole('button', { name: 'Discard changes' }));

    expect(workset).toHaveValue(null);
    expect(editorToggle()).not.toBeChecked();
    expect(screen.getByRole('button', { name: 'Discard changes' })).toBeDisabled();
    expect(screen.getByText('No unsaved changes')).toBeInTheDocument();
  });

  it('saves only changed preferences and preserves current stored values for unchanged settings', async () => {
    const user = userEvent.setup();
    saveReviewProjectShortcutHelpPreference('hidden', 'bottom');
    renderSettingsPage();
    await user.click(editorToggle());

    // Simulate another view changing a preference that this page has not edited.
    savePreferredWorksetSize(75);
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(loadVisibleTextEditorEnabled(TEST_USERNAME)).toBe(true);
    expect(loadPreferredWorksetSize()).toBe(75);
    expect(loadReviewProjectShortcutHelpPreference('bottom')).toBe('hidden');
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
  });

  it('preserves a locale draft when another tab updates the saved locales', async () => {
    const user = userEvent.setup();
    savePreferredLocales(['de']);
    renderSettingsPage();
    const localeSelector = screen.getByRole('button', { name: 'Select preferred locales' });
    await user.click(localeSelector);
    await user.click(screen.getByRole('button', { name: 'Select your locales' }));

    act(() => {
      savePreferredLocales(['es']);
      window.dispatchEvent(new StorageEvent('storage', { key: PREFERRED_LOCALES_KEY }));
    });

    expect(localeSelector).toHaveTextContent('My locales');
    expect(loadPreferredLocales()).toEqual(['es']);
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(loadPreferredLocales()).toEqual(['fr']);
  });

  it('keeps a failed Search save as a draft and allows retrying when storage recovers', async () => {
    const user = userEvent.setup();
    renderSettingsPage();
    await user.click(searchToggle());
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('Storage unavailable');
    });

    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(loadReviewProjectSearchEnabled(TEST_USERNAME)).toBe(false);
    expect(searchToggle()).toBeChecked();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Discard changes' })).toBeEnabled();
    expect(screen.getByText('Could not save all changes. Please try again.')).toBeInTheDocument();
    expect(screen.queryByText('Changes saved')).not.toBeInTheDocument();

    await user.click(searchToggle());

    expect(screen.getByRole('status')).toHaveTextContent('No unsaved changes');
    expect(screen.getByRole('status')).not.toHaveClass('is-error');
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Discard changes' })).toBeDisabled();
    expect(
      screen.queryByText('Could not save all changes. Please try again.'),
    ).not.toBeInTheDocument();

    setItem.mockRestore();
    await user.click(searchToggle());
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(loadReviewProjectSearchEnabled(TEST_USERNAME)).toBe(true);
    expect(screen.getByText('Changes saved')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
    expect(
      screen.queryByText('Could not save all changes. Please try again.'),
    ).not.toBeInTheDocument();
  });

  it('retains successfully saved values when a later preference write throws', async () => {
    const user = userEvent.setup();
    renderSettingsPage();
    const workset = screen.getByRole('spinbutton', { name: 'Result size limit' });
    await user.type(workset, '25');
    await user.click(editorToggle());
    const originalSetItem = Storage.prototype.setItem.bind(window.localStorage);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation((key, value) => {
      if (key === getVisibleTextEditorEnabledKey(TEST_USERNAME)) {
        throw new Error('Storage unavailable');
      }
      originalSetItem(key, value);
    });

    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(loadPreferredWorksetSize()).toBe(25);
    expect(loadVisibleTextEditorEnabled(TEST_USERNAME)).toBe(false);
    expect(editorToggle()).toBeChecked();
    expect(screen.getByText('Could not save all changes. Please try again.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeEnabled();

    await user.click(screen.getByRole('button', { name: 'Discard changes' }));

    expect(workset).toHaveValue(25);
    expect(editorToggle()).not.toBeChecked();
    expect(screen.getByText('No unsaved changes')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
  });
});
