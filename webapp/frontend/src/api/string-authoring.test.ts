// @vitest-environment node

import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  deleteStringAuthoringBranch,
  fetchStringAuthoringStrings,
  saveStringAuthoring,
} from './string-authoring';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('saveStringAuthoring', () => {
  it('returns after the save request is accepted without waiting for the pollable task', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      text: () =>
        Promise.resolve(
          JSON.stringify({
            assetPath: 'authoring/demo/strings.json',
            branchName: 'authoring/demo',
            cleanupDate: '2026-07-10T00:00:00Z',
            stringCount: 1,
            pollableTask: { id: 42, allFinished: false },
          }),
        ),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await saveStringAuthoring(7, {
      assetPath: 'authoring/demo/strings.json',
      branchName: 'authoring/demo',
      cleanupDate: '2026-07-10T00:00:00Z',
      strings: [{ name: 'checkout.title', source: 'Checkout' }],
    });

    expect(result.pollableTask.id).toBe(42);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/string-authoring/repositories/7/strings');
  });
});

describe('fetchStringAuthoringStrings', () => {
  it('sends the selected status filter', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      text: () => Promise.resolve('[]'),
    });
    vi.stubGlobal('fetch', fetchMock);

    await fetchStringAuthoringStrings(7, {
      branchName: 'authoring/demo',
      assetPath: 'authoring/demo/strings.json',
      usedFilter: 'UNUSED',
      limit: 500,
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      '/api/string-authoring/repositories/7/strings?branchName=authoring%2Fdemo&assetPath=authoring%2Fdemo%2Fstrings.json&usedFilter=UNUSED&limit=500',
    );
  });
});

describe('deleteStringAuthoringBranch', () => {
  it('falls back to the generic branch delete endpoint when the string authoring route is missing', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: false,
        status: 404,
        text: () => Promise.resolve('No static resource'),
      })
      .mockResolvedValueOnce({
        ok: true,
        text: () => Promise.resolve(JSON.stringify({ id: 99 })),
      })
      .mockResolvedValueOnce({
        ok: true,
        text: () => Promise.resolve(JSON.stringify({ id: 99, isAllFinished: true })),
      });
    vi.stubGlobal('fetch', fetchMock);

    const result = await deleteStringAuthoringBranch(7, 12, 'authoring/demo');

    expect(result).toMatchObject({
      id: 12,
      name: 'authoring/demo',
      pollableTask: { id: 99 },
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/string-authoring/repositories/7/branches/12',
      expect.objectContaining({ method: 'DELETE' }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/repositories/7/branches?branchId=12',
      expect.objectContaining({ method: 'DELETE' }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/pollableTasks/99',
      expect.objectContaining({ method: 'GET' }),
    );
  });
});
