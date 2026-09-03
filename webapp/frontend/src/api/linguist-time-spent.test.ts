// @vitest-environment node

import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchLinguistTimeSpentReport, type LinguistTimeSpentReport } from './linguist-time-spent';

afterEach(() => {
  vi.unstubAllGlobals();
});

const report: LinguistTimeSpentReport = {
  summary: {
    windowCount: 0,
    projectCount: 0,
    decidedWordCount: 0,
    selfReportedSeconds: null,
    estimatedActiveSeconds: 0,
    rawDecisionSpanSeconds: 0,
    decisionIntervalCount: 0,
    rapidDecisionIntervalCount: 0,
    rapidDecisionIntervalPercent: 0,
    pauseSeconds: 0,
    pauseCount: 0,
    reportedMissingCount: 0,
    reviewFlagCount: 0,
    lastComputedAt: null,
  },
  translatorScorecards: [],
  linguists: [],
  windows: [],
  translatorScorecardsHasNext: true,
  linguistsHasNext: false,
  windowsHasNext: true,
};

describe('fetchLinguistTimeSpentReport', () => {
  it.each([
    { scorecardPage: 0, linguistPage: 0, detailPage: 0 },
    { scorecardPage: 4, linguistPage: 2, detailPage: 1 },
  ])(
    'sends independent page parameters %j and preserves response paging metadata',
    async (pages) => {
      const fetchMock = vi
        .fn<(url: string, options?: RequestInit) => Promise<Response>>()
        .mockResolvedValue(
          new Response(JSON.stringify({ results: report }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        );
      vi.stubGlobal('fetch', fetchMock);

      await expect(
        fetchLinguistTimeSpentReport({
          status: 'CLOSED',
          translatorUserId: 502,
          localeBcp47Tag: 'fr',
          summaryLimit: 25,
          detailLimit: 25,
          ...pages,
        }),
      ).resolves.toEqual(report);

      expect(fetchMock).toHaveBeenCalledOnce();
      const [requestUrl, requestOptions] = fetchMock.mock.calls[0];
      const url = new URL(requestUrl, 'http://localhost');
      expect(url.pathname).toBe('/api/admin/linguist-time-spent');
      expect(Object.fromEntries(url.searchParams)).toEqual({
        status: 'CLOSED',
        translatorUserId: '502',
        localeBcp47Tag: 'fr',
        summaryLimit: '25',
        detailLimit: '25',
        scorecardPage: String(pages.scorecardPage),
        linguistPage: String(pages.linguistPage),
        detailPage: String(pages.detailPage),
      });
      expect(requestOptions).toMatchObject({ method: 'GET', credentials: 'same-origin' });
    },
  );
});
