import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  fetchGenerationExcludedLocales,
  updateGenerationExcludedLocales,
} from '../../api/generation-settings';
import { fetchLocales } from '../../api/locales';
import { AdminGenerationSettingsPage } from './AdminGenerationSettingsPage';

let currentRole = 'ROLE_ADMIN';

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({ role: currentRole }),
}));
vi.mock('../../api/generation-settings', () => ({
  fetchGenerationExcludedLocales: vi.fn(),
  updateGenerationExcludedLocales: vi.fn(),
}));
vi.mock('../../api/locales', () => ({ fetchLocales: vi.fn() }));

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/settings/system/generation']}>
        <Routes>
          <Route path="/settings/system/generation" element={<AdminGenerationSettingsPage />} />
          <Route path="/settings/me" element={<h1>My settings destination</h1>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...view, queryClient };
}

const saveButton = () => screen.getByRole('button', { name: 'Save changes' });
const localeButton = () => screen.getByRole('button', { name: 'Excluded locales' });
const french = () => screen.getByRole('checkbox', { name: /^French\s*fr\b/ });
const japanese = () => screen.getByRole('checkbox', { name: /^Japanese\s*ja\b/ });
const english = () => screen.getByRole('checkbox', { name: /^English\s*en\b/ });

async function waitForLoaded() {
  await waitFor(() => expect(localeButton()).toBeEnabled());
}

describe('AdminGenerationSettingsPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    currentRole = 'ROLE_ADMIN';
    vi.mocked(fetchGenerationExcludedLocales).mockResolvedValue(['fr']);
    vi.mocked(fetchLocales).mockResolvedValue([
      { bcp47Tag: 'en' },
      { bcp47Tag: 'fr' },
      { bcp47Tag: 'ja' },
    ]);
    vi.mocked(updateGenerationExcludedLocales).mockImplementation((tags) => Promise.resolve(tags));
  });

  it('loads saved locale selections and saves additions and removals explicitly', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitForLoaded();

    expect(screen.getByRole('heading', { name: 'Localized file generation' })).toBeInTheDocument();
    expect(saveButton()).toBeDisabled();
    await user.click(localeButton());
    expect(french()).toBeChecked();
    expect(japanese()).not.toBeChecked();
    await user.click(french());
    await user.click(japanese());
    await user.click(localeButton());
    expect(updateGenerationExcludedLocales).not.toHaveBeenCalled();
    await user.click(saveButton());

    await waitFor(() => expect(updateGenerationExcludedLocales).toHaveBeenCalled());
    expect(vi.mocked(updateGenerationExcludedLocales).mock.calls[0][0]).toEqual(['ja']);
    await waitFor(() => expect(saveButton()).toBeDisabled());
    await user.click(localeButton());
    expect(japanese()).toBeChecked();
    expect(french()).not.toBeChecked();
  });

  it('clears the global list only when the empty draft is saved', async () => {
    const user = userEvent.setup();
    vi.mocked(fetchGenerationExcludedLocales).mockResolvedValue(['fr', 'ja']);
    renderPage();
    await waitForLoaded();

    await user.click(screen.getByRole('button', { name: 'Clear selection' }));
    expect(updateGenerationExcludedLocales).not.toHaveBeenCalled();
    expect(saveButton()).toBeEnabled();
    await user.click(saveButton());

    await waitFor(() => expect(updateGenerationExcludedLocales).toHaveBeenCalled());
    expect(vi.mocked(updateGenerationExcludedLocales).mock.calls[0][0]).toEqual([]);
    await waitFor(() => expect(saveButton()).toBeDisabled());
    await user.click(localeButton());
    expect(french()).not.toBeChecked();
    expect(japanese()).not.toBeChecked();
  });

  it('resets an unsaved draft to the saved list without sending an update', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitForLoaded();
    await user.click(screen.getByRole('button', { name: 'Clear selection' }));
    await user.click(screen.getByRole('button', { name: 'Reset draft' }));

    expect(saveButton()).toBeDisabled();
    expect(updateGenerationExcludedLocales).not.toHaveBeenCalled();
    await user.click(localeButton());
    expect(french()).toBeChecked();
  });

  it('prevents saving when the saved list fails to load and allows retrying', async () => {
    const user = userEvent.setup();
    vi.mocked(fetchGenerationExcludedLocales).mockRejectedValueOnce(new Error('Offline'));
    renderPage();

    await screen.findByRole('button', { name: 'Retry' });
    const save = screen.queryByRole('button', { name: 'Save changes' });
    if (save) expect(save).toBeDisabled();
    expect(updateGenerationExcludedLocales).not.toHaveBeenCalled();
    vi.mocked(fetchGenerationExcludedLocales).mockResolvedValue(['ja']);
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    await waitForLoaded();
    await user.click(localeButton());
    expect(japanese()).toBeChecked();
    expect(french()).not.toBeChecked();
    expect(updateGenerationExcludedLocales).not.toHaveBeenCalled();
  });

  it('retains the draft after a failed save and retries the same locale list', async () => {
    const user = userEvent.setup();
    vi.mocked(updateGenerationExcludedLocales).mockRejectedValueOnce(new Error('Offline'));
    renderPage();
    await waitForLoaded();
    await user.click(localeButton());
    await user.click(japanese());
    await user.click(localeButton());
    await user.click(saveButton());
    expect(await screen.findByRole('alert')).toHaveTextContent('Offline');

    expect(saveButton()).toBeEnabled();
    await user.click(localeButton());
    expect(french()).toBeChecked();
    expect(japanese()).toBeChecked();
    await user.click(localeButton());
    await user.click(saveButton());
    await waitFor(() => expect(saveButton()).toBeDisabled());
    expect(vi.mocked(updateGenerationExcludedLocales).mock.calls.map(([tags]) => tags)).toEqual([
      ['fr', 'ja'],
      ['fr', 'ja'],
    ]);
  });

  it('preserves unsaved locale choices when the saved list refreshes in the background', async () => {
    const user = userEvent.setup();
    const { queryClient } = renderPage();
    await waitForLoaded();
    await user.click(localeButton());
    await user.click(japanese());

    act(() => {
      queryClient.setQueryData(['generation-excluded-locales'], ['en']);
    });

    expect(french()).toBeChecked();
    expect(japanese()).toBeChecked();
    expect(english()).not.toBeChecked();
    await user.click(localeButton());
    await user.click(saveButton());
    await waitFor(() => expect(updateGenerationExcludedLocales).toHaveBeenCalled());
    expect(vi.mocked(updateGenerationExcludedLocales).mock.calls[0][0]).toEqual(['fr', 'ja']);
  });

  it.each(['ROLE_PM', 'ROLE_TRANSLATOR', 'ROLE_USER'])(
    'redirects %s without fetching global settings or the locale catalog',
    async (role) => {
      currentRole = role;
      renderPage();

      await screen.findByRole('heading', { name: 'My settings destination' });
      expect(fetchGenerationExcludedLocales).not.toHaveBeenCalled();
      expect(fetchLocales).not.toHaveBeenCalled();
      expect(updateGenerationExcludedLocales).not.toHaveBeenCalled();
    },
  );
});
