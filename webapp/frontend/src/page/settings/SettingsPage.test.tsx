import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  type ApiUserPreferences,
  fetchUserPreferences,
  saveUserPreferences,
} from '../../api/userPreferences';
import { userPreferencesQueryKey } from '../../hooks/useUserPreferences';
import { saveVisibleTextEditorEnabled } from '../../utils/visibleTextEditorPreference';
import { savePreferredLocales, savePreferredWorksetSize } from '../workbench/workbench-preferences';
import { SettingsPage } from './SettingsPage';

let currentUsername = 'translator';
let accounts: Record<string, ApiUserPreferences>;
const defaults = (): ApiUserPreferences => ({
  initialized: true,
  worksetSize: null,
  preferredLocales: [],
  shortcutHelp: null,
  visibleTextEditorEnabled: false,
  reviewProjectSearchEnabled: false,
  defaultReviewTeamIds: [],
});
vi.mock('../../api/userPreferences', () => ({
  fetchUserPreferences: vi.fn(),
  saveUserPreferences: vi.fn(),
}));
vi.mock('../../hooks/useRepositories', () => ({ useRepositories: () => ({ data: [] }) }));
vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({
    username: currentUsername,
    role: 'ROLE_TRANSLATOR',
    canTranslateAllLocales: false,
    userLocales: ['fr'],
  }),
}));

function renderSettingsPage(seed = true) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  if (seed)
    Object.entries(accounts).forEach(([username, preferences]) =>
      queryClient.setQueryData(userPreferencesQueryKey(username), preferences),
    );
  const view = render(<SettingsPage />, {
    wrapper: ({ children }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    ),
  });
  return { ...view, queryClient };
}
const editorToggle = () =>
  screen.getByRole('checkbox', { name: /Use the assisted rich text editor/ });
const searchToggle = () => screen.getByRole('checkbox', { name: /Show Search in Review Project/ });
const shortcutToggle = () =>
  screen.getByRole('checkbox', { name: /Show shortcut bar at the bottom/ });
const saveButton = () => screen.getByRole('button', { name: 'Save changes' });
const discardButton = () => screen.getByRole('button', { name: 'Discard changes' });
const worksetInput = () => screen.getByRole('spinbutton', { name: 'Result size limit' });

beforeEach(() => {
  window.localStorage.clear();
  currentUsername = 'translator';
  accounts = { translator: defaults(), 'other-user': defaults() };
  vi.mocked(fetchUserPreferences).mockImplementation(() =>
    Promise.resolve(accounts[currentUsername]),
  );
  vi.mocked(saveUserPreferences).mockReset();
  vi.mocked(saveUserPreferences).mockImplementation((patch) => {
    accounts[currentUsername] = { ...accounts[currentUsername], ...patch, initialized: true };
    return Promise.resolve(accounts[currentUsername]);
  });
});
afterEach(() => vi.restoreAllMocks());

describe('SettingsPage account preferences', () => {
  it('stages all sections and sends one patch only after Save changes', async () => {
    const user = userEvent.setup();
    renderSettingsPage();
    expect(saveButton()).toBeDisabled();
    expect(shortcutToggle()).toBeChecked();
    await user.type(worksetInput(), '25');
    await user.click(editorToggle());
    await user.click(searchToggle());
    await user.click(shortcutToggle());
    await user.click(screen.getByRole('button', { name: 'Select preferred locales' }));
    await user.click(screen.getByRole('button', { name: 'Select your locales' }));
    expect(saveUserPreferences).not.toHaveBeenCalled();
    await user.click(saveButton());
    await screen.findByText('Changes saved');
    expect(saveUserPreferences).toHaveBeenCalledExactlyOnceWith(
      {
        worksetSize: 25,
        preferredLocales: ['fr'],
        shortcutHelp: 'header',
        visibleTextEditorEnabled: true,
        reviewProjectSearchEnabled: true,
      },
      expect.anything(),
    );
    expect(saveButton()).toBeDisabled();
    expect(window.localStorage.length).toBe(0);
  });

  it('uses account settings even when browser storage contains different values', () => {
    savePreferredWorksetSize(99);
    saveVisibleTextEditorEnabled(true, 'translator');
    accounts.translator = { ...defaults(), worksetSize: 50 };
    renderSettingsPage();
    expect(worksetInput()).toHaveValue(50);
    expect(editorToggle()).not.toBeChecked();
    expect(saveButton()).toBeDisabled();
  });

  it('stages legacy browser preferences for explicit import and leaves other users opt-ins behind', async () => {
    const user = userEvent.setup();
    accounts.translator = { ...defaults(), initialized: false };
    savePreferredWorksetSize(25);
    savePreferredLocales(['fr']);
    saveVisibleTextEditorEnabled(true, 'other-user');
    renderSettingsPage();
    expect(worksetInput()).toHaveValue(25);
    expect(editorToggle()).not.toBeChecked();
    expect(
      screen.getByText(/Existing browser settings are included in this draft/),
    ).toBeInTheDocument();
    expect(saveUserPreferences).not.toHaveBeenCalled();
    await user.click(saveButton());
    await screen.findByText('Changes saved');
    expect(accounts.translator).toMatchObject({
      initialized: true,
      worksetSize: 25,
      preferredLocales: ['fr'],
      visibleTextEditorEnabled: false,
    });
    expect(window.localStorage.getItem('workbench.worksetSize.v1')).toBe('25');
  });

  it('restores defaults as a draft, discards it, then saves the reset atomically', async () => {
    const user = userEvent.setup();
    accounts.translator = {
      ...defaults(),
      worksetSize: 25,
      preferredLocales: ['fr'],
      shortcutHelp: 'header',
      visibleTextEditorEnabled: true,
      reviewProjectSearchEnabled: true,
    };
    renderSettingsPage();
    await user.click(screen.getByRole('button', { name: 'Restore defaults' }));
    expect(worksetInput()).toHaveValue(null);
    expect(editorToggle()).not.toBeChecked();
    expect(shortcutToggle()).toBeChecked();
    expect(saveUserPreferences).not.toHaveBeenCalled();
    await user.click(discardButton());
    expect(worksetInput()).toHaveValue(25);
    expect(editorToggle()).toBeChecked();
    expect(saveButton()).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Restore defaults' }));
    await user.click(saveButton());
    await screen.findByText('Changes saved');
    expect(accounts.translator).toEqual(defaults());
  });

  it('can save defaults instead of importing old browser values', async () => {
    const user = userEvent.setup();
    accounts.translator = { ...defaults(), initialized: false };
    savePreferredWorksetSize(25);
    const view = renderSettingsPage();
    await user.click(screen.getByRole('button', { name: 'Restore defaults' }));
    expect(saveButton()).toBeEnabled();
    await user.click(saveButton());
    await screen.findByText('Changes saved');
    expect(accounts.translator).toEqual(defaults());
    view.unmount();
    renderSettingsPage();
    expect(worksetInput()).toHaveValue(null);
    expect(saveButton()).toBeDisabled();
  });

  it('restores defaults against the latest account settings after a deferred refresh', async () => {
    const user = userEvent.setup();
    const { queryClient } = renderSettingsPage();
    await user.click(editorToggle());
    accounts.translator = { ...defaults(), worksetSize: 100 };
    await act(() =>
      queryClient.setQueryData(userPreferencesQueryKey('translator'), accounts.translator),
    );
    await user.click(screen.getByRole('button', { name: 'Restore defaults' }));
    expect(saveButton()).toBeEnabled();
    expect(worksetInput()).toHaveValue(null);
    await user.click(saveButton());
    await screen.findByText('Changes saved');
    expect(accounts.translator).toEqual(defaults());
  });

  it('applies a deferred refresh when the last draft change is reverted', async () => {
    const user = userEvent.setup();
    const { queryClient } = renderSettingsPage();
    await user.click(editorToggle());
    await act(() =>
      queryClient.setQueryData(userPreferencesQueryKey('translator'), {
        ...defaults(),
        worksetSize: 100,
      }),
    );
    await user.click(editorToggle());
    await waitFor(() => expect(worksetInput()).toHaveValue(100));
    expect(saveButton()).toBeDisabled();
  });

  it('discards unsaved changes on navigation and loads saved values in a fresh query cache', async () => {
    const user = userEvent.setup();
    const view = renderSettingsPage();
    await user.click(editorToggle());
    view.unmount();
    const next = renderSettingsPage(false);
    await screen.findByRole('heading', { name: 'My Settings' });
    expect(editorToggle()).not.toBeChecked();
    await user.click(searchToggle());
    await user.click(saveButton());
    await screen.findByText('Changes saved');
    next.unmount();
    renderSettingsPage(false);
    await screen.findByRole('heading', { name: 'My Settings' });
    expect(searchToggle()).toBeChecked();
  });

  it('discards a draft when switching accounts', async () => {
    const user = userEvent.setup();
    accounts.translator = { ...defaults(), visibleTextEditorEnabled: true };
    const view = renderSettingsPage();
    await user.click(editorToggle());
    await user.click(searchToggle());
    currentUsername = 'other-user';
    view.rerender(<SettingsPage />);
    expect(editorToggle()).not.toBeChecked();
    expect(searchToggle()).not.toBeChecked();
    expect(saveButton()).toBeDisabled();
    currentUsername = 'translator';
    view.rerender(<SettingsPage />);
    expect(editorToggle()).toBeChecked();
    expect(searchToggle()).not.toBeChecked();
  });

  it.each(['0', '1.5', '2147483648'])(
    'blocks invalid result size %s while allowing discard',
    async (value) => {
      const user = userEvent.setup();
      renderSettingsPage();
      await user.type(worksetInput(), value);
      expect(saveButton()).toBeDisabled();
      expect(screen.getByText('Enter a whole number from 1 to 2147483647.')).toBeInTheDocument();
      await user.click(discardButton());
      expect(worksetInput()).toHaveValue(null);
    },
  );

  it('sends only dirty fields and uses the server response for unrelated concurrent changes', async () => {
    const user = userEvent.setup();
    accounts.translator = { ...defaults(), shortcutHelp: 'hidden' };
    renderSettingsPage();
    await user.click(editorToggle());
    accounts.translator = { ...accounts.translator, worksetSize: 75 };
    await user.click(saveButton());
    await screen.findByText('Changes saved');
    expect(saveUserPreferences).toHaveBeenCalledExactlyOnceWith(
      { visibleTextEditorEnabled: true },
      expect.anything(),
    );
    expect(worksetInput()).toHaveValue(75);
    expect(accounts.translator.shortcutHelp).toBe('hidden');
  });

  it('refreshes clean forms but preserves drafts when saved settings refresh', async () => {
    const user = userEvent.setup();
    const { queryClient } = renderSettingsPage();
    await act(() =>
      queryClient.setQueryData(userPreferencesQueryKey('translator'), {
        ...defaults(),
        worksetSize: 75,
      }),
    );
    await waitFor(() => expect(worksetInput()).toHaveValue(75));
    await user.click(editorToggle());
    await act(() =>
      queryClient.setQueryData(userPreferencesQueryKey('translator'), {
        ...defaults(),
        worksetSize: 100,
      }),
    );
    expect(editorToggle()).toBeChecked();
    expect(worksetInput()).toHaveValue(75);
    await user.click(discardButton());
    expect(worksetInput()).toHaveValue(100);
    expect(editorToggle()).not.toBeChecked();
  });

  it('keeps every failed change as a draft and retries without partial success', async () => {
    const user = userEvent.setup();
    vi.mocked(saveUserPreferences).mockRejectedValueOnce(new Error('Offline'));
    renderSettingsPage();
    await user.type(worksetInput(), '25');
    await user.click(searchToggle());
    await user.click(saveButton());
    await screen.findByText('Could not save changes. Please try again.');
    expect(accounts.translator).toEqual(defaults());
    expect(searchToggle()).toBeChecked();
    expect(worksetInput()).toHaveValue(25);
    expect(saveButton()).toBeEnabled();
    await user.click(saveButton());
    await screen.findByText('Changes saved');
    expect(accounts.translator).toMatchObject({
      worksetSize: 25,
      reviewProjectSearchEnabled: true,
    });
  });

  it('disables edits and actions while a save is pending', async () => {
    const user = userEvent.setup();
    let finish!: (value: ApiUserPreferences) => void;
    vi.mocked(saveUserPreferences).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    renderSettingsPage();
    await user.click(editorToggle());
    await user.click(saveButton());
    expect(screen.getByText('Saving changes…')).toBeInTheDocument();
    expect(saveButton()).toBeDisabled();
    expect(discardButton()).toBeDisabled();
    expect(editorToggle()).toBeDisabled();
    expect(worksetInput()).toBeDisabled();
    act(() => finish({ ...defaults(), visibleTextEditorEnabled: true }));
    await screen.findByText('Changes saved');
  });

  it('does not fall back to browser settings when loading the account fails', async () => {
    vi.mocked(fetchUserPreferences).mockRejectedValueOnce(new Error('Offline'));
    savePreferredWorksetSize(25);
    renderSettingsPage(false);
    await screen.findByText('Could not load your settings.');
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(worksetInput()).toHaveValue(null));
  });
});
