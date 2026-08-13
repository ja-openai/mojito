import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createBlobCleanupPolicy,
  fetchBlobCleanupPolicies,
  startBlobCleanupPolicy,
} from '../../api/blob-cleanup-policies';
import { AdminBlobCleanupPage } from './AdminBlobCleanupPage';

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({ role: 'ROLE_ADMIN' }),
}));

vi.mock('../../api/blob-cleanup-policies', () => ({
  fetchBlobCleanupPolicies: vi.fn(),
  createBlobCleanupPolicy: vi.fn(),
  updateBlobCleanupPolicy: vi.fn(),
  startBlobCleanupPolicy: vi.fn(),
  stopBlobCleanupPolicy: vi.fn(),
  deleteBlobCleanupPolicy: vi.fn(),
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

const policy = {
  id: 7,
  prefix: 'pollable_task/',
  enabled: true,
  retentionDays: 3,
  batchSize: 250,
  maxBatchesPerRun: 0,
  pauseMillis: 250,
  maxRetries: 5,
  status: 'DRAINED',
  lastStartedDate: null,
  lastFinishedDate: null,
  lastDeletedCount: 1200,
  totalDeletedCount: 3400,
  lastError: null,
};

describe('AdminBlobCleanupPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(fetchBlobCleanupPolicies).mockResolvedValue([policy]);
    vi.mocked(startBlobCleanupPolicy).mockResolvedValue({ ...policy, status: 'QUEUED' });
    vi.mocked(createBlobCleanupPolicy).mockResolvedValue(policy);
  });

  it('shows policy controls and accumulated cleanup progress', async () => {
    renderPage();

    expect(
      await screen.findByRole('region', { name: 'pollable_task/ policy' }),
    ).toBeInTheDocument();
    expect(screen.getByText('1,200')).toBeInTheDocument();
    expect(screen.getByText('3,400')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Start now' })).toBeEnabled();
  });

  it('starts a saved cleanup policy immediately', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Start now' }));

    await waitFor(() => {
      expect(startBlobCleanupPolicy).toHaveBeenCalledWith(7);
    });
  });

  it('preserves unsaved policy edits when cleanup progress refreshes', async () => {
    const user = userEvent.setup();
    const { queryClient } = renderPage();
    const batchSize = await screen.findByRole('spinbutton', { name: 'Batch size' });

    await user.clear(batchSize);
    await user.type(batchSize, '500');

    act(() => {
      queryClient.setQueryData(
        ['blob-cleanup-policies'],
        [{ ...policy, status: 'RUNNING', lastDeletedCount: 1300 }],
      );
    });

    await screen.findByText('1,300');
    expect(batchSize).toHaveValue(500);
  });

  it('creates new policies disabled with conservative batch defaults', async () => {
    const user = userEvent.setup();
    vi.mocked(fetchBlobCleanupPolicies).mockResolvedValue([]);
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Add policy' }));

    await waitFor(() => {
      expect(createBlobCleanupPolicy).toHaveBeenCalledWith({
        prefix: 'pollable_task/',
        enabled: false,
        retentionDays: 3,
        batchSize: 250,
        maxBatchesPerRun: 0,
        pauseMillis: 250,
        maxRetries: 5,
      });
    });
  });
});
