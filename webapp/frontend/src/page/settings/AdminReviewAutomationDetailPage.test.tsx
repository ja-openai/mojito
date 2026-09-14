import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { ApiReviewAutomation } from '../../api/review-automations';
import { AdminReviewAutomationDetailPage } from './AdminReviewAutomationDetailPage';

const mocks = vi.hoisted(() => ({
  fetchReviewAutomation: vi.fn(),
  fetchReviewAutomationRuns: vi.fn(),
  fetchReviewAutomations: vi.fn(),
  fetchReviewFeatureOptions: vi.fn(),
  fetchTeams: vi.fn(),
  updateReviewAutomation: vi.fn(),
  repairReviewAutomationTrigger: vi.fn(),
  runReviewAutomationNow: vi.fn(),
  locales: [{ bcp47Tag: 'fr' }, { bcp47Tag: 'he' }],
}));

vi.mock('../../api/review-automations', () => mocks);
vi.mock('../../api/review-features', () => mocks);
vi.mock('../../api/teams', () => mocks);
vi.mock('../../hooks/useLocales', () => ({
  useLocales: () => ({ data: mocks.locales, isLoading: false, isError: false }),
}));
vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({ role: 'ROLE_ADMIN' }),
}));

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { retry: false },
      queries: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/settings/system/review-automations/7']}>
        <Routes>
          <Route
            path="/settings/system/review-automations/:automationId"
            element={<AdminReviewAutomationDetailPage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminReviewAutomationDetailPage excluded locales', () => {
  let savedAutomation: ApiReviewAutomation;

  beforeEach(() => {
    vi.clearAllMocks();
    savedAutomation = {
      id: 7,
      name: 'Daily review',
      enabled: true,
      cronExpression: '0 0 10 * * ?',
      timeZone: 'UTC',
      team: { id: 2, name: 'Localization' },
      dueDateOffsetDays: 1,
      maxWordCountPerProject: 2000,
      assignTranslator: true,
      excludedLocaleTags: [],
      trigger: null,
      features: [{ id: 3, name: 'Checkout' }],
    };
    mocks.fetchReviewAutomation.mockImplementation(() => Promise.resolve(savedAutomation));
    mocks.fetchReviewAutomationRuns.mockResolvedValue([]);
    mocks.fetchReviewAutomations.mockResolvedValue({ reviewAutomations: [], totalCount: 0 });
    mocks.fetchReviewFeatureOptions.mockResolvedValue([{ id: 3, name: 'Checkout', enabled: true }]);
    mocks.fetchTeams.mockResolvedValue([{ id: 2, name: 'Localization' }]);
    mocks.updateReviewAutomation.mockImplementation(
      (_id: number, payload: { excludedLocaleTags: string[] }) => {
        savedAutomation = { ...savedAutomation, excludedLocaleTags: payload.excludedLocaleTags };
        return Promise.resolve(savedAutomation);
      },
    );
  });

  it('saves a selected locale, reloads it, and explicitly clears the exclusion', async () => {
    const user = userEvent.setup();
    const firstRender = renderPage();
    const selector = await screen.findByRole('button', { name: 'Select excluded locales' });
    const save = screen.getByRole('button', { name: 'Save' });
    await waitFor(() => expect(save).toBeDisabled());

    await user.click(selector);
    await user.click(screen.getByRole('checkbox', { name: /Hebrew/ }));
    await user.click(selector);
    expect(save).toBeEnabled();
    await user.click(save);

    await waitFor(() =>
      expect(mocks.updateReviewAutomation).toHaveBeenCalledWith(
        7,
        expect.objectContaining({ excludedLocaleTags: ['he'], featureIds: [3] }),
      ),
    );
    await waitFor(() => expect(save).toBeDisabled());
    firstRender.unmount();

    renderPage();
    const reloadedSelector = await screen.findByRole('button', { name: 'Select excluded locales' });
    await waitFor(() => expect(reloadedSelector).toHaveTextContent('Hebrew'));
    await user.click(reloadedSelector);
    expect(screen.getByRole('checkbox', { name: /Hebrew/ })).toBeChecked();
    await user.click(screen.getByRole('button', { name: 'Clear locale selection' }));
    await user.click(reloadedSelector);
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(mocks.updateReviewAutomation).toHaveBeenLastCalledWith(
        7,
        expect.objectContaining({ excludedLocaleTags: [], featureIds: [3] }),
      ),
    );
  });
});
