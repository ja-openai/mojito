import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { ApiAiTranslateAutomationConfig } from '../../api/ai-translate-automation';
import { AdminAiTranslateAutomationPage } from './AdminAiTranslateAutomationPage';

const mocks = vi.hoisted(() => ({
  fetchAiTranslateAutomationConfig: vi.fn(),
  fetchAiTranslateAutomationRuns: vi.fn(),
  fetchAiTranslateLineageAttempts: vi.fn(),
  updateAiTranslateAutomationConfig: vi.fn(),
  runAiTranslateAutomationNow: vi.fn(),
}));

vi.mock('../../api/ai-translate-automation', () => mocks);
vi.mock('../../hooks/useUser', () => ({ useUser: () => ({ role: 'ROLE_ADMIN' }) }));
vi.mock('../../hooks/useLocales', () => ({
  useLocales: () => ({ data: [{ bcp47Tag: 'fr' }] }),
}));
vi.mock('../../hooks/useRepositories', () => ({
  useRepositories: () => ({
    data: [
      {
        id: 1,
        name: 'First repository',
        repositoryLocales: [{ locale: { bcp47Tag: 'fr' }, parentLocale: { bcp47Tag: 'en' } }],
      },
      { id: 2, name: 'Second repository', repositoryLocales: [] },
    ],
  }),
}));

const config: ApiAiTranslateAutomationConfig = {
  enabled: true,
  repositoryIds: [],
  excludedRepositoryIds: [2],
  excludedLocaleTagsByRepositoryId: { '1': ['fr'], '2': ['ja'] },
  sourceTextMaxCountPerLocale: 100,
  cronExpression: null,
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AdminAiTranslateAutomationPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function clearFirstRepositoryExclusions() {
  const repositoryButton = screen.getByRole('button', {
    name: 'Choose repository for automatic AI locale exclusions',
  });
  await waitFor(() => expect(repositoryButton).toBeEnabled());
  fireEvent.click(repositoryButton);
  fireEvent.click(screen.getByRole('button', { name: 'First repository' }));
  const localeButton = screen.getByRole('button', {
    name: 'Select automatic AI translation excluded locales',
  });
  fireEvent.click(localeButton);
  fireEvent.click(screen.getByRole('button', { name: 'Clear locale selection' }));
  fireEvent.click(localeButton);
}

describe('AdminAiTranslateAutomationPage locale exclusions', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    mocks.fetchAiTranslateAutomationConfig.mockResolvedValue(config);
    mocks.fetchAiTranslateAutomationRuns.mockResolvedValue([]);
    mocks.updateAiTranslateAutomationConfig.mockImplementation(
      (next: ApiAiTranslateAutomationConfig) => {
        mocks.fetchAiTranslateAutomationConfig.mockResolvedValue(next);
        return Promise.resolve(next);
      },
    );
  });

  it('saves locale exclusions with the existing automation settings and retains out-of-scope rules', async () => {
    renderPage();
    const repositoryButton = screen.getByRole('button', {
      name: 'Choose repository for automatic AI locale exclusions',
    });
    await waitFor(() => expect(repositoryButton).toBeEnabled());
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    fireEvent.click(repositoryButton);
    expect(screen.queryByRole('button', { name: 'Second repository' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'First repository' }));
    const localeButton = screen.getByRole('button', {
      name: 'Select automatic AI translation excluded locales',
    });
    fireEvent.click(localeButton);
    expect(screen.getByRole('checkbox', { name: /^French\s*fr\b/ })).toBeChecked();
    fireEvent.click(screen.getByRole('button', { name: 'Clear locale selection' }));
    fireEvent.click(localeButton);
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await screen.findByText('Settings saved.');
    expect(mocks.updateAiTranslateAutomationConfig).toHaveBeenCalledWith(
      { ...config, excludedLocaleTagsByRepositoryId: { '2': ['ja'] } },
      expect.anything(),
    );
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  it('only offers included repositories in include mode', async () => {
    mocks.fetchAiTranslateAutomationConfig.mockResolvedValue({
      ...config,
      repositoryIds: [1],
      excludedRepositoryIds: [],
    });
    renderPage();
    const button = screen.getByRole('button', {
      name: 'Choose repository for automatic AI locale exclusions',
    });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);
    expect(screen.getByRole('button', { name: 'First repository' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Second repository' })).not.toBeInTheDocument();
  });

  it('keeps Save and locale editing disabled while a save is pending', async () => {
    let resolveSave!: (next: ApiAiTranslateAutomationConfig) => void;
    const savePromise = new Promise<ApiAiTranslateAutomationConfig>((resolve) => {
      resolveSave = resolve;
    });
    mocks.updateAiTranslateAutomationConfig.mockReturnValue(savePromise);
    renderPage();
    await clearFirstRepositoryExclusions();
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(mocks.updateAiTranslateAutomationConfig).toHaveBeenCalledOnce());
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(
      screen.getByRole('button', { name: 'Select automatic AI translation excluded locales' }),
    ).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Run now' })).toBeDisabled();

    const savedConfig = { ...config, excludedLocaleTagsByRepositoryId: { '2': ['ja'] } };
    mocks.fetchAiTranslateAutomationConfig.mockResolvedValue(savedConfig);
    await act(async () => {
      resolveSave(savedConfig);
      await savePromise;
    });
    expect(await screen.findByText('Settings saved.')).toBeInTheDocument();
  });

  it('keeps a failed save visible and retains the locale draft for retry', async () => {
    mocks.updateAiTranslateAutomationConfig.mockRejectedValueOnce(
      new Error('Could not save exclusions'),
    );
    renderPage();
    await clearFirstRepositoryExclusions();
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Could not save exclusions')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('Settings saved.');
    expect(mocks.updateAiTranslateAutomationConfig).toHaveBeenLastCalledWith(
      { ...config, excludedLocaleTagsByRepositoryId: { '2': ['ja'] } },
      expect.anything(),
    );
    expect(screen.queryByText('Could not save exclusions')).not.toBeInTheDocument();
  });

  it('keeps locale editing disabled when loading settings fails', async () => {
    mocks.fetchAiTranslateAutomationConfig.mockRejectedValue(new Error('Settings unavailable'));
    renderPage();
    expect(await screen.findByText('Settings unavailable')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Choose repository for automatic AI locale exclusions' }),
    ).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(mocks.updateAiTranslateAutomationConfig).not.toHaveBeenCalled();
  });
});
