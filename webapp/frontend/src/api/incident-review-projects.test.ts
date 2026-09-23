import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  createIncidentReviewProjects,
  type IncidentReviewProjectRequest,
  previewIncidentReviewProjects,
  waitForIncidentReviewTask,
} from './incident-review-projects';

const request: IncidentReviewProjectRequest = {
  allRepositories: true,
  localeTags: ['fr'],
  teamId: 1,
  name: 'Incident review',
  dueDate: '2026-10-01T12:00:00Z',
  maxWordCountPerProject: null,
  maxIncidentCount: null,
  assignTranslator: false,
  type: 'NORMAL',
  notes: null,
  screenshotImageIds: [],
};
const output = {
  eligibleIncidentCount: 1,
  skippedIncidentCount: 0,
  projectCount: 1,
  localeTags: ['fr'],
  projectIds: [31],
  requestIds: [32],
  skipped: [],
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('incident review asynchronous requests', () => {
  it.each([
    [previewIncidentReviewProjects, '/api/incident-review-projects/preview'],
    [createIncidentReviewProjects, '/api/incident-review-projects'],
  ] as const)('returns the accepted task without polling for %s', async (start, url) => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(Response.json({ pollableTaskId: 12 }, { status: 202 }));
    vi.stubGlobal('fetch', fetchMock);
    expect(await start(request)).toEqual({ pollableTaskId: 12 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(
      url,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(request),
      }),
    );
  });

  it('polls progress and reads final output, accepting both completion field spellings', async () => {
    vi.useFakeTimers();
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        Response.json({ id: 12, allFinished: false, message: 'Scanning incidents.' }),
      )
      .mockResolvedValueOnce(Response.json({ id: 12, isAllFinished: true, message: 'Finished.' }))
      .mockResolvedValueOnce(Response.json(output));
    vi.stubGlobal('fetch', fetchMock);
    const onProgress = vi.fn();
    const completion = waitForIncidentReviewTask(12, onProgress, new AbortController().signal);
    await vi.runAllTimersAsync();
    expect((await completion).result).toEqual(output);
    expect(onProgress).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({
        isAllFinished: false,
        message: 'Scanning incidents.',
      }),
    );
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/pollableTasks/12',
      '/api/pollableTasks/12',
      '/api/pollableTasks/12/output',
    ]);
  });

  it('retries transient HTTP polling errors without restarting the job', async () => {
    vi.useFakeTimers();
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response('<html>Unavailable</html>', { status: 503 }))
      .mockResolvedValueOnce(Response.json({ id: 12, allFinished: true }))
      .mockResolvedValueOnce(Response.json(output));
    vi.stubGlobal('fetch', fetchMock);
    const completion = waitForIncidentReviewTask(12, vi.fn(), new AbortController().signal);
    await vi.runAllTimersAsync();
    expect((await completion).result).toEqual(output);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('returns partial output alongside a normalized terminal error', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn<typeof fetch>()
        .mockResolvedValueOnce(
          Response.json({
            id: 12,
            allFinished: true,
            errorMessage: '{"message":"Later project failed"}',
          }),
        )
        .mockResolvedValueOnce(Response.json({ ...output, hasMore: true })),
    );
    const result = await waitForIncidentReviewTask(12, vi.fn(), new AbortController().signal);
    expect(result.task.errorMessage).toBe('Later project failed');
    expect(result.result?.projectIds).toEqual([31]);
    expect(result.result?.hasMore).toBe(true);
  });

  it('reports a failed preview even when there is no saved output', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn<typeof fetch>()
        .mockResolvedValueOnce(
          Response.json({ id: 12, allFinished: true, errorMessage: 'Preview failed' }),
        )
        .mockResolvedValueOnce(new Response('', { status: 404 })),
    );
    expect(
      await waitForIncidentReviewTask(12, vi.fn(), new AbortController().signal),
    ).toMatchObject({
      task: { errorMessage: 'Preview failed' },
      result: null,
    });
  });

  it('keeps the HTTP status in non-JSON error responses', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(new Response('<html>Bad gateway</html>', { status: 502 })),
    );
    await expect(createIncidentReviewProjects(request)).rejects.toMatchObject({
      status: 502,
      message: 'Incident review request failed (HTTP 502).',
    });
  });

  it('preserves terminal failure when absent output returns HTTP 500', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn<typeof fetch>()
        .mockResolvedValueOnce(
          Response.json({ id: 12, allFinished: true, errorMessage: 'Preview failed' }),
        )
        .mockResolvedValueOnce(
          Response.json({ message: 'Output is unavailable' }, { status: 500 }),
        ),
    );
    const completed = await waitForIncidentReviewTask(12, vi.fn(), new AbortController().signal);
    expect(completed.task.errorMessage).toBe('Preview failed');
    expect(completed.task.isAllFinished).toBe(true);
    expect(completed.result).toBeNull();
    expect(completed.outputError).toContain('HTTP 500');
  });

  it('preserves application error details and HTTP status', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(Response.json({ message: 'Select a locale' }, { status: 400 })),
    );
    await expect(previewIncidentReviewProjects(request)).rejects.toMatchObject({
      status: 400,
      message: 'Select a locale (HTTP 400).',
    });
  });

  it('does not retry polling after observation is aborted', async () => {
    const controller = new AbortController();
    controller.abort();
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockRejectedValue(new DOMException('Aborted', 'AbortError'));
    vi.stubGlobal('fetch', fetchMock);
    await expect(waitForIncidentReviewTask(12, vi.fn(), controller.signal)).rejects.toMatchObject({
      name: 'AbortError',
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it.each([
    null,
    {},
    { pollableTaskId: 0 },
    { pollableTaskId: 1.5 },
    { pollableTaskId: Number.MAX_SAFE_INTEGER + 1 },
  ])('rejects an invalid accepted task ID in %j', async (payload) => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(Response.json(payload, { status: 202 })),
    );
    await expect(createIncidentReviewProjects(request)).rejects.toMatchObject({
      status: 202,
      message: 'Incident review did not return a valid task ID (HTTP 202).',
    });
  });

  it('limits observation to one hour without canceling the server task', async () => {
    const now = vi
      .spyOn(Date, 'now')
      .mockReturnValueOnce(0)
      .mockReturnValue(60 * 60 * 1000 + 1);
    const fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal('fetch', fetchMock);
    try {
      await expect(
        waitForIncidentReviewTask(12, vi.fn(), new AbortController().signal),
      ).rejects.toThrow('Reconnect to check its progress.');
      expect(fetchMock).not.toHaveBeenCalled();
    } finally {
      now.mockRestore();
    }
  });
});
