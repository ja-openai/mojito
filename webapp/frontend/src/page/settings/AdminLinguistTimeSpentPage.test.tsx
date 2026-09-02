import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  fetchLinguistTimeSpentReport,
  type LinguistTimeSpentReport,
  recomputeLinguistTimeSpentReport,
} from '../../api/linguist-time-spent';
import { type ApiUser, fetchAllUsersAdmin } from '../../api/users';
import { AdminLinguistTimeSpentPage } from './AdminLinguistTimeSpentPage';

const userState = vi.hoisted(() => ({ role: 'ROLE_ADMIN' }));

vi.mock('../../hooks/useUser', () => ({ useUser: () => userState }));

vi.mock('../../hooks/useLocales', () => ({
  useLocales: () => ({ data: [], isLoading: false }),
}));

vi.mock('../../utils/localeDisplayNames', () => ({
  useLocaleDisplayNameResolver: () => (localeTag: string) => localeTag,
}));

vi.mock('../../api/linguist-time-spent', () => ({
  fetchLinguistTimeSpentReport: vi.fn(),
  recomputeLinguistTimeSpentReport: vi.fn(),
}));

vi.mock('../../api/users', () => ({ fetchAllUsersAdmin: vi.fn() }));

const users = [
  {
    id: 501,
    username: 'reviewer@example.test',
    givenName: 'Morgan',
    surname: 'Chen',
    enabled: true,
    canTranslateAllLocales: true,
    authorities: [{ authority: 'ROLE_TRANSLATOR' }],
  },
  {
    id: 502,
    username: 'former.reviewer@example.test',
    givenName: 'Alexandra',
    surname: 'Rivera',
    commonName: 'Alex Rivera',
    enabled: false,
    canTranslateAllLocales: false,
    authorities: [{ authority: 'ROLE_USER' }],
  },
] satisfies ApiUser[];

const summary = {
  windowCount: 1,
  projectCount: 1,
  decidedWordCount: 120,
  selfReportedSeconds: null,
  estimatedActiveSeconds: 300,
  rawDecisionSpanSeconds: 480,
  decisionIntervalCount: 4,
  rapidDecisionIntervalCount: 1,
  rapidDecisionIntervalPercent: 25,
  pauseSeconds: 0,
  pauseCount: 0,
  reportedMissingCount: 1,
  reviewFlagCount: 0,
  lastComputedAt: '2024-01-01T00:10:00Z',
};

const report = {
  summary,
  translatorScorecards: [
    {
      assignedTranslatorUserId: 501,
      assignedTranslatorUsername: 'reviewer@example.test',
      windowCount: 1,
      projectCount: 1,
      decidedWordCount: 120,
      averageAssignedToAcceptedSeconds: 60,
      notAcceptedCount: 0,
      notAcceptedPercent: 0,
      missedDeadlineCount: 0,
      missedDeadlinePercent: 0,
      reportedMissingCount: 1,
      reportedMissingPercent: 100,
      reviewFlagCount: 0,
      reviewFlagPercent: 0,
      selfReportedSeconds: null,
      estimatedActiveSeconds: 300,
      rawDecisionSpanSeconds: 480,
      decisionIntervalCount: 4,
      rapidDecisionIntervalCount: 1,
      rapidDecisionIntervalPercent: 25,
      reportedComputedRatio: null,
      lastComputedAt: '2024-01-01T00:10:00Z',
    },
  ],
  linguists: [
    {
      assignedTranslatorUserId: 501,
      assignedTranslatorUsername: 'reviewer@example.test',
      localeBcp47Tag: 'fr',
      metrics: summary,
    },
  ],
  windows: [
    {
      id: 101,
      assignmentWindowId: 201,
      reviewProjectId: 301,
      reviewProjectRequestId: 401,
      reviewProjectRequestName: 'Synthetic review project',
      reviewProjectStatus: 'CLOSED',
      localeBcp47Tag: 'fr',
      assignedTranslatorUserId: 501,
      assignedTranslatorUsername: 'reviewer@example.test',
      assignmentWindowStartedAt: '2024-01-01T00:00:00Z',
      assignmentAcceptedAt: '2024-01-01T00:01:00Z',
      assignmentWindowEndedAt: '2024-01-01T00:10:00Z',
      assignmentWindowEndReason: 'PROJECT_CLOSED',
      projectCreatedDate: '2024-01-01T00:00:00Z',
      projectDueDate: '2024-01-02T00:00:00Z',
      firstDecisionAt: '2024-01-01T00:02:00Z',
      lastDecisionAt: '2024-01-01T00:10:00Z',
      assignedToAcceptedSeconds: 60,
      acceptedToFirstDecisionSeconds: 60,
      textUnitCount: 5,
      wordCount: 120,
      decidedCount: 5,
      decidedWordCount: 120,
      selfReportedSeconds: 0,
      reportedComputedDeltaSeconds: null,
      reportedComputedRatio: null,
      estimatedActiveSeconds: 300,
      rawDecisionSpanSeconds: 480,
      decisionIntervalCount: 4,
      rapidDecisionIntervalCount: 1,
      rapidDecisionIntervalPercent: 25,
      medianDecisionIntervalSeconds: 10,
      p90DecisionIntervalSeconds: 20,
      p95DecisionIntervalSeconds: 30,
      projectSpanSeconds: 600,
      pauseSeconds: 0,
      pauseCount: 0,
      reviewFlag: 'MISSING_REPORT',
      reportedMissing: true,
      attributionConfidence: 'ACTOR',
      finalizedAt: '2024-01-01T00:10:00Z',
      computedAt: '2024-01-01T00:10:00Z',
    },
  ],
} satisfies LinguistTimeSpentReport;

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Routes>
          <Route path="/" element={<AdminLinguistTimeSpentPage />} />
          <Route path="/settings/me" element={<div>Personal settings</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminLinguistTimeSpentPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    userState.role = 'ROLE_ADMIN';
    vi.mocked(fetchLinguistTimeSpentReport).mockResolvedValue(report);
    vi.mocked(fetchAllUsersAdmin).mockResolvedValue(users);
    vi.mocked(recomputeLinguistTimeSpentReport).mockResolvedValue({
      matchedProjectCount: 1,
      computedWindowCount: 1,
      backfilledWindowCount: 0,
    });
  });

  it('shows observed review cadence without invented pauses or discrepancy flags', async () => {
    renderPage();

    expect(await screen.findByRole('columnheader', { name: 'p95' })).toBeInTheDocument();
    expect(screen.getByText('10s')).toBeInTheDocument();
    expect(screen.getByText('20s')).toBeInTheDocument();
    expect(screen.getByText('30s')).toBeInTheDocument();
    expect(screen.getAllByText('1 / 25.0%')).toHaveLength(4);
    expect(screen.queryByRole('columnheader', { name: 'Computed' })).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Pauses' })).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Discrepancy' })).not.toBeInTheDocument();
  });

  it('searches users by name, username, or ID and applies or clears the selected translator', async () => {
    const user = userEvent.setup();
    renderPage();

    const translatorFilter = screen.getByRole('button', { name: 'Filter by translator' });
    await waitFor(() => expect(translatorFilter).toBeEnabled());
    expect(translatorFilter).toHaveTextContent('All translators');
    await user.click(translatorFilter);

    const menu = within(screen.getByRole('menu'));
    expect(menu.getByRole('button', { name: /Morgan Chen/ })).toBeInTheDocument();
    const search = screen.getByPlaceholderText('Search by name, username, or ID');
    for (const query of ['aLeX rIvErA', 'Alexandra', 'former.reviewer@example.test', '502']) {
      await user.clear(search);
      await user.type(search, query);

      expect(
        menu.getByRole('button', { name: /Alex Rivera.*former\.reviewer@example\.test · ID 502/ }),
      ).toBeEnabled();
      expect(menu.queryByRole('button', { name: /Morgan Chen/ })).not.toBeInTheDocument();
      expect(fetchLinguistTimeSpentReport).toHaveBeenCalledTimes(1);
      expect(recomputeLinguistTimeSpentReport).not.toHaveBeenCalled();
    }

    await user.click(menu.getByRole('button', { name: /Alex Rivera/ }));
    expect(translatorFilter).toHaveTextContent('Alex Rivera');
    expect(fetchLinguistTimeSpentReport).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() =>
      expect(fetchLinguistTimeSpentReport).toHaveBeenLastCalledWith(
        expect.objectContaining({ translatorUserId: 502 }),
      ),
    );
    await waitFor(() => expect(translatorFilter).toBeEnabled());

    await user.click(translatorFilter);
    await user.click(screen.getByRole('button', { name: 'All translators' }));
    expect(translatorFilter).toHaveTextContent('All translators');
    expect(fetchLinguistTimeSpentReport).toHaveBeenCalledTimes(2);

    await user.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() =>
      expect(fetchLinguistTimeSpentReport).toHaveBeenLastCalledWith(
        expect.objectContaining({ translatorUserId: null }),
      ),
    );
  });

  it('recomputes for the selected translator before applying the report filter', async () => {
    const user = userEvent.setup();
    renderPage();

    const translatorFilter = screen.getByRole('button', { name: 'Filter by translator' });
    await waitFor(() => expect(translatorFilter).toBeEnabled());
    await user.click(translatorFilter);
    await user.click(screen.getByRole('button', { name: /Morgan Chen/ }));

    expect(fetchLinguistTimeSpentReport).toHaveBeenCalledTimes(1);
    expect(fetchLinguistTimeSpentReport).toHaveBeenLastCalledWith(
      expect.objectContaining({ translatorUserId: null }),
    );
    await user.click(screen.getByRole('button', { name: 'Recompute' }));

    await waitFor(() =>
      expect(recomputeLinguistTimeSpentReport).toHaveBeenCalledWith({
        projectCreatedAfter: null,
        projectCreatedBefore: null,
        status: 'CLOSED',
        translatorUserId: 501,
        localeBcp47Tag: null,
        limit: 500,
      }),
    );
    expect(
      await screen.findByText('Matched 1 projects, computed 1 assignment windows, backfilled 0.'),
    ).toBeInTheDocument();
  });

  it('shows a translator loading error while keeping the report visible', async () => {
    vi.mocked(fetchAllUsersAdmin).mockRejectedValue(new Error('Synthetic users API failure'));
    renderPage();

    expect(
      await screen.findByText('Failed to load translators. Please reload to retry.'),
    ).toBeInTheDocument();
    expect(await screen.findByRole('columnheader', { name: 'p95' })).toBeInTheDocument();
  });

  it('redirects project managers without fetching the report or admin users', async () => {
    userState.role = 'ROLE_PM';

    renderPage();

    expect(await screen.findByText('Personal settings')).toBeInTheDocument();
    expect(fetchLinguistTimeSpentReport).not.toHaveBeenCalled();
    expect(fetchAllUsersAdmin).not.toHaveBeenCalled();
  });
});
