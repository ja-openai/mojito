import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  fetchLinguistTimeSpentReport,
  type LinguistTimeSpentReport,
} from '../../api/linguist-time-spent';
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

  it('redirects project managers without fetching the report', async () => {
    userState.role = 'ROLE_PM';

    renderPage();

    expect(await screen.findByText('Personal settings')).toBeInTheDocument();
    expect(fetchLinguistTimeSpentReport).not.toHaveBeenCalled();
  });
});
