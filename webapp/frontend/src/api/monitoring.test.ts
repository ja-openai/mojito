// @vitest-environment node

import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  fetchActiveSearchIndexReindexTask,
  fetchSearchIndexReindexTask,
  reindexSearchIndex,
} from './monitoring';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('search index background jobs', () => {
  it('returns immediately after the reindex job is accepted', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () =>
        Promise.resolve({
          pollableTask: {
            id: 42,
            allFinished: false,
            message: { status: 'QUEUED', totalDocuments: 0 },
          },
        }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const task = await reindexSearchIndex({ repositoryIds: [7], pageSize: 500, bulkSize: 200 });

    expect(task).toMatchObject({
      id: 42,
      isAllFinished: false,
      progress: { status: 'QUEUED', totalDocuments: 0 },
    });
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it('handles a missing active indexing job without trying to parse an empty body', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ pollableTask: null }),
      }),
    );

    await expect(fetchActiveSearchIndexReindexTask()).resolves.toBeNull();
  });

  it('normalizes persisted progress and indexing failures from pollable tasks', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () =>
          Promise.resolve({
            id: 42,
            isAllFinished: true,
            message: JSON.stringify({ status: 'FAILED', indexedDocuments: 200 }),
            errorMessage: { message: 'OpenSearch is unavailable' },
          }),
      }),
    );

    await expect(fetchSearchIndexReindexTask(42)).resolves.toMatchObject({
      id: 42,
      isAllFinished: true,
      progress: { status: 'FAILED', indexedDocuments: 200 },
      errorMessage: 'OpenSearch is unavailable',
    });
  });
});
