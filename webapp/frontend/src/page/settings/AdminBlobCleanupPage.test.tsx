import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  fetchBlobCleanupSettings,
  startBlobCleanup,
  stopBlobCleanup,
  updateBlobCleanupSettings,
} from '../../api/blob-cleanup';
import { AdminBlobCleanupPage } from './AdminBlobCleanupPage';

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({ role: 'ROLE_ADMIN' }),
}));

vi.mock('../../api/blob-cleanup', () => ({
  fetchBlobCleanupSettings: vi.fn(),
  updateBlobCleanupSettings: vi.fn(),
  startBlobCleanup: vi.fn(),
  stopBlobCleanup: vi.fn(),
}));

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const result = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AdminBlobCleanupPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...result, queryClient };
}

const settings = {
  enabled: true,
  batchSize: 500,
  maxBatchesPerRun: 100,
  pauseMillis: 250,
  maxRetries: 5,
  status: 'DRAINED',
  lastStartedDate: null,
  lastProgressDate: null,
  lastFinishedDate: null,
  lastDeletedCount: 1200,
  totalDeletedCount: 3400,
  lastError: null,
};

describe('AdminBlobCleanupPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(fetchBlobCleanupSettings).mockResolvedValue(settings);
    vi.mocked(startBlobCleanup).mockResolvedValue({ ...settings, status: 'QUEUED' });
    vi.mocked(stopBlobCleanup).mockResolvedValue({
      ...settings,
      enabled: false,
      status: 'STOPPED',
    });
    vi.mocked(updateBlobCleanupSettings).mockResolvedValue(settings);
  });

  it('shows global cleanup controls and accumulated progress', async () => {
    renderPage();

    expect(
      await screen.findByRole('region', { name: 'Global database blob cleanup' }),
    ).toBeInTheDocument();
    expect(screen.getByText('1,200')).toBeInTheDocument();
    expect(screen.getByText('3,400')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run now' })).toBeEnabled();
    expect(screen.queryByLabelText(/prefix/i)).not.toBeInTheDocument();
  });

  it('enables or disables cleanup from the admin page', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('checkbox', { name: 'Enabled' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => {
      expect(updateBlobCleanupSettings).toHaveBeenCalledWith({
        enabled: false,
        batchSize: 500,
        maxBatchesPerRun: 100,
        pauseMillis: 250,
        maxRetries: 5,
      });
    });
  });

  it('starts global cleanup immediately', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Run now' }));

    await waitFor(() => {
      expect(startBlobCleanup).toHaveBeenCalledOnce();
    });
  });

  it('preserves unsaved settings when cleanup progress refreshes', async () => {
    const user = userEvent.setup();
    const { queryClient } = renderPage();
    const batchSize = await screen.findByRole('spinbutton', { name: 'Batch size' });

    await user.clear(batchSize);
    await user.type(batchSize, '750');

    act(() => {
      queryClient.setQueryData(['blob-cleanup'], {
        ...settings,
        status: 'RUNNING',
        lastDeletedCount: 1300,
      });
    });

    await screen.findByText('1,300');
    expect(batchSize).toHaveValue(750);
  });

  it('stops an active cleanup run', async () => {
    vi.mocked(fetchBlobCleanupSettings).mockResolvedValue({
      ...settings,
      status: 'RUNNING',
      lastProgressDate: new Date().toISOString(),
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Stop' }));

    await waitFor(() => {
      expect(stopBlobCleanup).toHaveBeenCalledOnce();
    });
  });

  it('identifies and permits restarting stalled cleanup runs', async () => {
    vi.mocked(fetchBlobCleanupSettings).mockResolvedValue({
      ...settings,
      status: 'RUNNING',
      lastProgressDate: new Date(Date.now() - 6 * 60 * 1000).toISOString(),
    });
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText('STALLED')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Restart stalled run' }));

    await waitFor(() => {
      expect(startBlobCleanup).toHaveBeenCalledOnce();
    });
  });
});
