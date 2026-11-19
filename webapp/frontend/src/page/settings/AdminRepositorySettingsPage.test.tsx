import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { fetchLocales } from '../../api/locales';
import { fetchRepositories } from '../../api/repositories';
import {
  fetchRepositoryTargetLocales,
  type RepositoryTargetLocales,
  updateRepositoryTargetLocales,
} from '../../api/repository-settings';
import { AdminRepositorySettingsPage } from './AdminRepositorySettingsPage';

let currentRole = 'ROLE_ADMIN';
vi.mock('../../hooks/useUser', () => ({ useUser: () => ({ role: currentRole }) }));
vi.mock('../../api/locales', () => ({ fetchLocales: vi.fn() }));
vi.mock('../../api/repositories', () => ({ fetchRepositories: vi.fn() }));
vi.mock('../../api/repository-settings', () => ({
  fetchRepositoryTargetLocales: vi.fn(),
  updateRepositoryTargetLocales: vi.fn(),
}));

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/settings/system/repositories']}>
        <Routes>
          <Route path="/settings/system/repositories" element={<AdminRepositorySettingsPage />} />
          <Route path="/settings/me" element={<h1>My settings destination</h1>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return queryClient;
}

const repositoryButton = () => screen.getByRole('button', { name: 'Repository' });
const localeButton = () => screen.getByRole('button', { name: 'Target locales' });
const saveButton = () => screen.getByRole('button', { name: 'Save changes' });
const french = () => screen.getByRole('checkbox', { name: /^French\s*fr\b/ });
const canadianFrench = () => screen.getByRole('checkbox', { name: /^French \(Canada\)\s*fr-CA\b/ });
const japanese = () => screen.getByRole('checkbox', { name: /^Japanese\s*ja\b/ });

async function chooseRepository(name = 'First repository') {
  await waitFor(() => expect(repositoryButton()).toBeEnabled());
  await userEvent.click(repositoryButton());
  await userEvent.click(screen.getByRole('button', { name }));
}

async function loadedPage() {
  const queryClient = renderPage();
  await chooseRepository();
  await waitFor(() => expect(localeButton()).toBeEnabled());
  return queryClient;
}

describe('AdminRepositorySettingsPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    currentRole = 'ROLE_ADMIN';
    vi.mocked(fetchRepositories).mockResolvedValue([
      { id: 7, name: 'First repository' },
      { id: 8, name: 'Second repository' },
    ]);
    vi.mocked(fetchLocales).mockResolvedValue(
      ['en', 'fr', 'fr-CA', 'ja'].map((bcp47Tag) => ({ bcp47Tag })),
    );
    vi.mocked(fetchRepositoryTargetLocales).mockImplementation((id) =>
      Promise.resolve({
        sourceLocaleTag: 'en',
        targetLocaleTags: id === 7 ? ['fr', 'fr-CA'] : ['ja'],
      }),
    );
    vi.mocked(updateRepositoryTargetLocales).mockImplementation((_id, targetLocaleTags) =>
      Promise.resolve({ sourceLocaleTag: 'en', targetLocaleTags }),
    );
  });

  afterEach(() => vi.restoreAllMocks());

  it('waits for a repository, excludes the source, and saves additions and removals', async () => {
    const queryClient = renderPage();
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    expect(fetchRepositoryTargetLocales).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Target locales' })).not.toBeInTheDocument();
    await chooseRepository();
    await waitFor(() => expect(localeButton()).toBeEnabled());
    expect(screen.getByText(/Source locale:/)).toHaveTextContent('English (en)');
    await userEvent.click(localeButton());
    expect(screen.queryByRole('checkbox', { name: /^English\s*en\b/ })).not.toBeInTheDocument();
    expect(french()).toBeChecked();
    expect(canadianFrench()).toBeChecked();
    await userEvent.click(canadianFrench());
    await userEvent.click(japanese());
    await userEvent.click(localeButton());
    expect(updateRepositoryTargetLocales).not.toHaveBeenCalled();
    await userEvent.click(saveButton());

    await waitFor(() => expect(saveButton()).toBeDisabled());
    expect(vi.mocked(updateRepositoryTargetLocales).mock.calls[0]).toEqual([7, ['fr', 'ja']]);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['repositories'] });
    await userEvent.click(localeButton());
    expect(french()).toBeChecked();
    expect(japanese()).toBeChecked();
    expect(canadianFrench()).not.toBeChecked();
  });

  it('supports clearing all targets and resetting an unsaved draft', async () => {
    await loadedPage();
    await userEvent.click(localeButton());
    await userEvent.click(screen.getByRole('button', { name: 'Clear' }));
    await userEvent.click(localeButton());
    await userEvent.click(screen.getByRole('button', { name: 'Reset draft' }));
    expect(saveButton()).toBeDisabled();
    expect(updateRepositoryTargetLocales).not.toHaveBeenCalled();
    await userEvent.click(localeButton());
    expect(french()).toBeChecked();
    await userEvent.click(screen.getByRole('button', { name: 'Clear' }));
    await userEvent.click(localeButton());
    await userEvent.click(saveButton());

    await waitFor(() => expect(saveButton()).toBeDisabled());
    expect(vi.mocked(updateRepositoryTargetLocales).mock.calls[0]).toEqual([7, []]);
  });

  it('retains the failed draft and allows retrying a rejected save', async () => {
    vi.mocked(updateRepositoryTargetLocales).mockRejectedValueOnce(
      new Error('fr-CA still inherits from fr'),
    );
    await loadedPage();
    await userEvent.click(localeButton());
    await userEvent.click(french());
    await userEvent.click(localeButton());
    await userEvent.click(saveButton());
    expect(await screen.findByRole('alert')).toHaveTextContent('fr-CA still inherits from fr');
    await userEvent.click(localeButton());
    expect(french()).not.toBeChecked();
    expect(canadianFrench()).toBeChecked();
    await userEvent.click(localeButton());
    await userEvent.click(saveButton());

    await waitFor(() => expect(saveButton()).toBeDisabled());
    expect(vi.mocked(updateRepositoryTargetLocales).mock.calls).toEqual([
      [7, ['fr-CA']],
      [7, ['fr-CA']],
    ]);
  });

  it('blocks saving after a failed load and retries the selected repository', async () => {
    vi.mocked(fetchRepositoryTargetLocales).mockRejectedValueOnce(new Error('Offline'));
    renderPage();
    await chooseRepository();
    expect(await screen.findByRole('alert')).toHaveTextContent('Offline');
    expect(saveButton()).toBeDisabled();
    expect(localeButton()).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Retry locales' }));
    await waitFor(() => expect(localeButton()).toBeEnabled());
    expect(fetchRepositoryTargetLocales).toHaveBeenLastCalledWith(7);
    expect(updateRepositoryTargetLocales).not.toHaveBeenCalled();
  });

  it('retries a failed repository list without saving configuration', async () => {
    vi.mocked(fetchRepositories).mockRejectedValueOnce(new Error('Repository list unavailable'));
    renderPage();
    await screen.findByText('Repository list unavailable');
    await userEvent.click(screen.getByRole('button', { name: 'Retry repositories' }));
    await chooseRepository();
    await waitFor(() => expect(localeButton()).toBeEnabled());
    expect(updateRepositoryTargetLocales).not.toHaveBeenCalled();
  });

  it('requires the locale catalog to load before editing and allows retrying', async () => {
    vi.mocked(fetchLocales).mockRejectedValue(new Error('Locale catalog unavailable'));
    renderPage();
    await chooseRepository();
    expect(await screen.findByRole('alert')).toHaveTextContent('Locale catalog unavailable');
    expect(localeButton()).toBeDisabled();
    expect(saveButton()).toBeDisabled();
    vi.mocked(fetchLocales).mockResolvedValue(
      ['en', 'fr', 'fr-CA', 'ja'].map((bcp47Tag) => ({ bcp47Tag })),
    );
    await userEvent.click(screen.getByRole('button', { name: 'Retry locales' }));
    await waitFor(() => expect(localeButton()).toBeEnabled());
    expect(updateRepositoryTargetLocales).not.toHaveBeenCalled();
  });

  it('confirms a dirty repository switch and never carries the old draft to another repository', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    await loadedPage();
    await userEvent.click(localeButton());
    await userEvent.click(japanese());
    await userEvent.click(localeButton());
    await chooseRepository('Second repository');
    expect(confirm).toHaveBeenCalledOnce();
    expect(repositoryButton()).toHaveTextContent('First repository');
    expect(fetchRepositoryTargetLocales).not.toHaveBeenCalledWith(8);
    await userEvent.click(localeButton());
    expect(japanese()).toBeChecked();
    await userEvent.click(localeButton());
    confirm.mockReturnValue(true);
    await chooseRepository('Second repository');
    await waitFor(() => expect(localeButton()).toBeEnabled());
    expect(saveButton()).toBeDisabled();
    await userEvent.click(localeButton());
    expect(french()).not.toBeChecked();
    expect(japanese()).toBeChecked();
    await userEvent.click(french());
    await userEvent.click(localeButton());
    await userEvent.click(saveButton());
    await waitFor(() => expect(saveButton()).toBeDisabled());
    expect(vi.mocked(updateRepositoryTargetLocales).mock.calls[0]).toEqual([8, ['ja', 'fr']]);
  });

  it('prevents repository switching while saving', async () => {
    let finishSave!: (settings: RepositoryTargetLocales) => void;
    vi.mocked(updateRepositoryTargetLocales).mockReturnValue(
      new Promise((resolve) => {
        finishSave = resolve;
      }),
    );
    await loadedPage();
    await userEvent.click(localeButton());
    await userEvent.click(japanese());
    await userEvent.click(localeButton());
    await userEvent.click(saveButton());
    expect(repositoryButton()).toBeDisabled();
    expect(localeButton()).toBeDisabled();
    act(() => {
      finishSave({ sourceLocaleTag: 'en', targetLocaleTags: ['fr', 'fr-CA', 'ja'] });
    });
    await waitFor(() => expect(repositoryButton()).toBeEnabled());
  });

  it('preserves a draft when saved data refreshes, and resets to the latest saved list', async () => {
    const queryClient = await loadedPage();
    await userEvent.click(localeButton());
    await userEvent.click(japanese());
    act(() => {
      queryClient.setQueryData(['repository-target-locales', 7], {
        sourceLocaleTag: 'en',
        targetLocaleTags: ['fr'],
      });
    });
    expect(canadianFrench()).toBeChecked();
    expect(japanese()).toBeChecked();
    await userEvent.click(localeButton());
    await userEvent.click(screen.getByRole('button', { name: 'Reset draft' }));
    await userEvent.click(localeButton());
    expect(french()).toBeChecked();
    expect(canadianFrench()).not.toBeChecked();
    expect(japanese()).not.toBeChecked();
  });

  it.each(['ROLE_PM', 'ROLE_TRANSLATOR', 'ROLE_USER'])(
    'redirects %s before fetching configuration or catalogs',
    async (role) => {
      currentRole = role;
      renderPage();
      await screen.findByRole('heading', { name: 'My settings destination' });
      expect(fetchRepositories).not.toHaveBeenCalled();
      expect(fetchLocales).not.toHaveBeenCalled();
      expect(fetchRepositoryTargetLocales).not.toHaveBeenCalled();
      expect(updateRepositoryTargetLocales).not.toHaveBeenCalled();
    },
  );
});
