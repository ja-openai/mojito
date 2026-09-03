import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  fetchLinguistTimeSpentReport,
  type LinguistTimeSpentReport,
  type LinguistTimeSpentReportParams,
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
  translatorScorecardsHasNext: false,
  linguistsHasNext: false,
  windowsHasNext: false,
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

function pagedReport({
  scorecardPage = 0,
  linguistPage = 0,
  detailPage = 0,
}: LinguistTimeSpentReportParams): LinguistTimeSpentReport {
  return {
    ...report,
    translatorScorecards: [
      {
        ...report.translatorScorecards[0],
        assignedTranslatorUserId: 601 + scorecardPage,
        assignedTranslatorUsername: `scorecard-page-${scorecardPage + 1}@example.test`,
      },
    ],
    linguists: [
      {
        ...report.linguists[0],
        assignedTranslatorUserId: 701 + linguistPage,
        assignedTranslatorUsername: `linguist-page-${linguistPage + 1}@example.test`,
      },
    ],
    windows: [
      {
        ...report.windows[0],
        id: 801 + detailPage,
        reviewProjectRequestName: `Window page ${detailPage + 1}`,
      },
    ],
    translatorScorecardsHasNext: scorecardPage === 0,
    linguistsHasNext: linguistPage === 0,
    windowsHasNext: detailPage === 0,
  };
}

function pagination(label: string) {
  return within(screen.getByRole('navigation', { name: `${label} pagination` }));
}

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

  it('searches users by name, email, username, or ID and applies or clears the selected translator', async () => {
    const user = userEvent.setup();
    renderPage();

    const translatorFilter = screen.getByRole('button', { name: 'Filter by translator' });
    await waitFor(() => expect(translatorFilter).toBeEnabled());
    expect(translatorFilter).toHaveTextContent('All translators');
    await user.click(translatorFilter);

    const menu = within(screen.getByRole('menu'));
    expect(menu.getByRole('button', { name: /Morgan Chen/ })).toBeInTheDocument();
    const search = screen.getByPlaceholderText('Search by name, email, username, or ID');
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

  it('pages each table independently, preserves rows while loading, and stops on the final page', async () => {
    const user = userEvent.setup();
    vi.mocked(fetchLinguistTimeSpentReport).mockImplementation((params) =>
      Promise.resolve(pagedReport(params)),
    );
    renderPage();

    expect(await screen.findByText('scorecard-page-1@example.test')).toBeInTheDocument();
    const scorecard = pagination('Translator scorecard');
    const linguist = pagination('By linguist and language');
    const windows = pagination('Project assignment windows');
    await waitFor(() => expect(scorecard.getByRole('button', { name: 'Next' })).toBeEnabled());
    expect(scorecard.getByRole('button', { name: 'Previous' })).toBeDisabled();
    expect(fetchLinguistTimeSpentReport).toHaveBeenLastCalledWith(
      expect.objectContaining({
        scorecardPage: 0,
        linguistPage: 0,
        detailPage: 0,
        summaryLimit: 25,
        detailLimit: 25,
      }),
    );

    let resolveNextPage!: (value: LinguistTimeSpentReport) => void;
    const nextPage = new Promise<LinguistTimeSpentReport>((resolve) => {
      resolveNextPage = resolve;
    });
    vi.mocked(fetchLinguistTimeSpentReport).mockReturnValueOnce(nextPage);
    await user.click(scorecard.getByRole('button', { name: 'Next' }));
    await waitFor(() =>
      expect(fetchLinguistTimeSpentReport).toHaveBeenLastCalledWith(
        expect.objectContaining({ scorecardPage: 1, linguistPage: 0, detailPage: 0 }),
      ),
    );
    expect(screen.getByText('scorecard-page-1@example.test')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(
      'Updating report… Previous results are shown while loading.',
    );
    expect(scorecard.queryByText('Page 2')).not.toBeInTheDocument();
    for (const table of [scorecard, linguist, windows]) {
      expect(table.getByText('Loading…')).toBeInTheDocument();
      expect(table.getByRole('button', { name: 'Previous' })).toBeDisabled();
      expect(table.getByRole('button', { name: 'Next' })).toBeDisabled();
    }

    await act(async () => {
      resolveNextPage(pagedReport({ scorecardPage: 1 }));
      await nextPage;
    });
    expect(await screen.findByText('scorecard-page-2@example.test')).toBeInTheDocument();
    expect(screen.queryByText('scorecard-page-1@example.test')).not.toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(screen.getByText('linguist-page-1@example.test')).toBeInTheDocument();
    expect(screen.getByText('Window page 1')).toBeInTheDocument();
    expect(scorecard.getByText('Page 2')).toBeInTheDocument();
    expect(linguist.getByText('Page 1')).toBeInTheDocument();
    expect(windows.getByText('Page 1')).toBeInTheDocument();
    expect(scorecard.getByRole('button', { name: 'Next' })).toBeDisabled();

    await user.click(linguist.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('linguist-page-2@example.test')).toBeInTheDocument();
    expect(fetchLinguistTimeSpentReport).toHaveBeenLastCalledWith(
      expect.objectContaining({ scorecardPage: 1, linguistPage: 1, detailPage: 0 }),
    );
    await waitFor(() => expect(windows.getByRole('button', { name: 'Next' })).toBeEnabled());
    await user.click(windows.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Window page 2')).toBeInTheDocument();
    expect(fetchLinguistTimeSpentReport).toHaveBeenLastCalledWith(
      expect.objectContaining({ scorecardPage: 1, linguistPage: 1, detailPage: 1 }),
    );
    expect(screen.getByText('scorecard-page-2@example.test')).toBeInTheDocument();
    for (const table of [scorecard, linguist, windows]) {
      expect(table.getByText('Page 2')).toBeInTheDocument();
      expect(table.getByRole('button', { name: 'Next' })).toBeDisabled();
    }

    await waitFor(() => expect(scorecard.getByRole('button', { name: 'Previous' })).toBeEnabled());
    await user.click(scorecard.getByRole('button', { name: 'Previous' }));
    expect(await screen.findByText('scorecard-page-1@example.test')).toBeInTheDocument();
    expect(fetchLinguistTimeSpentReport).toHaveBeenLastCalledWith(
      expect.objectContaining({ scorecardPage: 0, linguistPage: 1, detailPage: 1 }),
    );
    expect(screen.getByText('linguist-page-2@example.test')).toBeInTheDocument();
    expect(screen.getByText('Window page 2')).toBeInTheDocument();
    expect(recomputeLinguistTimeSpentReport).not.toHaveBeenCalled();
  });

  it('keeps draft filters out of paging requests and resets all tables when filters are applied', async () => {
    const user = userEvent.setup();
    vi.mocked(fetchLinguistTimeSpentReport).mockImplementation((params) =>
      Promise.resolve(pagedReport(params)),
    );
    renderPage();

    const translatorFilter = screen.getByRole('button', { name: 'Filter by translator' });
    await waitFor(() => expect(translatorFilter).toBeEnabled());
    await user.click(translatorFilter);
    await user.click(screen.getByRole('button', { name: /Alex Rivera/ }));
    expect(fetchLinguistTimeSpentReport).toHaveBeenCalledTimes(1);

    for (const label of [
      'Translator scorecard',
      'By linguist and language',
      'Project assignment windows',
    ]) {
      const next = pagination(label).getByRole('button', { name: 'Next' });
      await waitFor(() => expect(next).toBeEnabled());
      await user.click(next);
      await waitFor(() => expect(screen.getByRole('button', { name: 'Apply' })).toBeEnabled());
      expect(fetchLinguistTimeSpentReport).toHaveBeenLastCalledWith(
        expect.objectContaining({ translatorUserId: null }),
      );
    }
    expect(fetchLinguistTimeSpentReport).toHaveBeenLastCalledWith(
      expect.objectContaining({ scorecardPage: 1, linguistPage: 1, detailPage: 1 }),
    );

    await user.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() =>
      expect(fetchLinguistTimeSpentReport).toHaveBeenLastCalledWith(
        expect.objectContaining({
          translatorUserId: 502,
          scorecardPage: 0,
          linguistPage: 0,
          detailPage: 0,
        }),
      ),
    );
    expect(await screen.findByText('scorecard-page-1@example.test')).toBeInTheDocument();
    for (const label of [
      'Translator scorecard',
      'By linguist and language',
      'Project assignment windows',
    ]) {
      expect(pagination(label).getByText('Page 1')).toBeInTheDocument();
    }

    await waitFor(() => expect(translatorFilter).toBeEnabled());
    await user.click(translatorFilter);
    await user.click(screen.getByRole('button', { name: /Morgan Chen/ }));
    await user.click(pagination('Translator scorecard').getByRole('button', { name: 'Next' }));
    await waitFor(() =>
      expect(fetchLinguistTimeSpentReport).toHaveBeenLastCalledWith(
        expect.objectContaining({
          translatorUserId: 502,
          scorecardPage: 1,
          linguistPage: 0,
          detailPage: 0,
        }),
      ),
    );
    expect(recomputeLinguistTimeSpentReport).not.toHaveBeenCalled();
  });

  it.each([0, 25])(
    'disables both directions on a terminal first page with %i rows',
    async (rowCount) => {
      vi.mocked(fetchLinguistTimeSpentReport).mockResolvedValue({
        ...report,
        translatorScorecards: Array.from({ length: rowCount }, (_, index) => ({
          ...report.translatorScorecards[0],
          assignedTranslatorUserId: 1000 + index,
        })),
        linguists: Array.from({ length: rowCount }, (_, index) => ({
          ...report.linguists[0],
          assignedTranslatorUserId: 1000 + index,
        })),
        windows: Array.from({ length: rowCount }, (_, index) => ({
          ...report.windows[0],
          id: 1000 + index,
        })),
      });
      renderPage();
      await waitFor(() => expect(screen.getByRole('button', { name: 'Apply' })).toBeEnabled());

      for (const label of [
        'Translator scorecard',
        'By linguist and language',
        'Project assignment windows',
      ]) {
        const table = pagination(label);
        expect(table.getByText('Page 1')).toBeInTheDocument();
        expect(table.getByRole('button', { name: 'Previous' })).toBeDisabled();
        expect(table.getByRole('button', { name: 'Next' })).toBeDisabled();
      }
      if (rowCount === 0) {
        expect(screen.getByText('No scorecard rows found.')).toBeInTheDocument();
        expect(screen.getByText('No computed rows found.')).toBeInTheDocument();
      } else {
        const scorecardSection = screen
          .getByRole('heading', { name: 'Translator scorecard' })
          .closest('section')!;
        expect(within(scorecardSection).getAllByRole('row')).toHaveLength(26);
      }
    },
  );

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
