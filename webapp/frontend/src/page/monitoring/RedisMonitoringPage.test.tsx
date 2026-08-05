import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { RedisMonitoringPage } from './RedisMonitoringPage';

const mocks = vi.hoisted(() => ({
  fetchRedisSnapshot: vi.fn(),
  runRedisProbe: vi.fn(),
  role: 'ROLE_ADMIN',
}));

vi.mock('../../api/monitoring', () => ({
  fetchRedisSnapshot: mocks.fetchRedisSnapshot,
  runRedisProbe: mocks.runRedisProbe,
}));

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({ username: 'admin', role: mocks.role }),
}));

const readySnapshot = {
  timestamp: '2026-08-05T19:00:00Z',
  enabled: true,
  status: 'READY',
  endpoint: 'redis://localhost:6379',
  database: 0,
  ssl: false,
  metrics: {
    version: '7.4.5',
    uptimeSeconds: 123,
    usedMemoryBytes: 1_048_576,
    usedMemoryHuman: '1.00M',
    maxMemoryBytes: 0,
    connectedClients: 2,
    keyCount: 4,
  },
  checks: [
    { name: 'PING', success: true, latencyMs: 1, message: null },
    { name: 'Server info', success: true, latencyMs: 2, message: null },
  ],
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/monitoring/redis']}>
      <Routes>
        <Route path="/monitoring/redis" element={<RedisMonitoringPage />} />
        <Route path="/repositories" element={<div>Repositories</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('RedisMonitoringPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.role = 'ROLE_ADMIN';
    mocks.fetchRedisSnapshot.mockResolvedValue(readySnapshot);
    mocks.runRedisProbe.mockResolvedValue({
      ...readySnapshot,
      checks: [
        ...readySnapshot.checks,
        { name: 'Write probe', success: true, latencyMs: 1, message: null },
        { name: 'Read probe', success: true, latencyMs: 1, message: null },
        { name: 'Delete probe', success: true, latencyMs: 1, message: null },
      ],
    });
  });

  it('shows instance metrics and runs the write/read/delete probe', async () => {
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText('redis://localhost:6379')).toBeInTheDocument();
    expect(screen.getByText('7.4.5')).toBeInTheDocument();
    expect(screen.getByText('1.00M')).toBeInTheDocument();
    expect(screen.getByText('Unlimited')).toBeInTheDocument();
    expect(screen.getByText('Keys in database 0')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Run write/read/delete probe' }));

    await waitFor(() => expect(mocks.runRedisProbe).toHaveBeenCalledOnce());
    expect(await screen.findByText('Delete probe')).toBeInTheDocument();
  });

  it('disables the active probe when Redis is not configured', async () => {
    mocks.fetchRedisSnapshot.mockResolvedValue({
      ...readySnapshot,
      enabled: false,
      status: 'NOT_CONFIGURED',
      metrics: null,
      checks: [],
    });

    renderPage();

    expect(await screen.findByText('Not configured')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run write/read/delete probe' })).toBeDisabled();
  });

  it('redirects non-admin users without requesting Redis status', async () => {
    mocks.role = 'ROLE_PM';

    renderPage();

    expect(await screen.findByText('Repositories')).toBeInTheDocument();
    expect(mocks.fetchRedisSnapshot).not.toHaveBeenCalled();
  });
});
