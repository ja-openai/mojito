// @vitest-environment node

import { afterEach, describe, expect, it, vi } from 'vitest';

import { importTextUnitsBatch, searchTextUnits } from './text-units';

function mockSearchResponse() {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ results: [] })),
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function getPostedBody(fetchMock: ReturnType<typeof vi.fn>) {
  const requestInit = fetchMock.mock.calls[0]?.[1] as RequestInit;
  if (typeof requestInit.body !== 'string') {
    throw new Error('Expected request body to be a JSON string.');
  }
  return JSON.parse(requestInit.body) as Record<string, unknown>;
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('searchTextUnits', () => {
  it('keeps contains TextUnit ID searches out of the exact ID filter', async () => {
    const fetchMock = mockSearchResponse();

    await searchTextUnits({
      repositoryIds: [1],
      localeTags: ['fr-FR'],
      searchAttribute: 'tmTextUnitIds',
      searchType: 'contains',
      searchText: '30690',
    });

    const body = getPostedBody(fetchMock);

    expect(body).not.toHaveProperty('tmTextUnitIds');
    expect(body.textSearch).toEqual({
      operator: 'AND',
      predicates: [{ field: 'tmTextUnitIds', searchType: 'ILIKE', value: '%30690%' }],
    });
  });

  it('adds an exact ID filter for exact TextUnit ID searches', async () => {
    const fetchMock = mockSearchResponse();

    await searchTextUnits({
      repositoryIds: [],
      localeTags: ['fr-FR'],
      searchAttribute: 'tmTextUnitIds',
      searchType: 'exact',
      searchText: '30690, 30691',
    });

    expect(getPostedBody(fetchMock)).toMatchObject({
      tmTextUnitIds: [30690, 30691],
    });
  });

  it('does not extract exact TextUnit IDs from OR text search', async () => {
    const fetchMock = mockSearchResponse();

    await searchTextUnits({
      repositoryIds: [1],
      localeTags: ['fr-FR'],
      textSearch: {
        operator: 'OR',
        predicates: [
          { field: 'tmTextUnitIds', searchType: 'exact', value: '30690' },
          { field: 'source', searchType: 'exact', value: 'Other source' },
        ],
      },
    });

    expect(getPostedBody(fetchMock)).not.toHaveProperty('tmTextUnitIds');
  });

  it('passes explicit top-level TextUnit IDs through unchanged', async () => {
    const fetchMock = mockSearchResponse();

    await searchTextUnits({
      repositoryIds: [],
      localeTags: [],
      tmTextUnitIds: [30690, 30690, 30691],
    });

    expect(getPostedBody(fetchMock)).toMatchObject({
      tmTextUnitIds: [30690, 30691],
    });
  });
});

describe('importTextUnitsBatch', () => {
  it('waits longer than the default async timeout for imports', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
    const fetchMock = vi.fn((url: string) => {
      const body =
        url === '/api/textunitsBatch'
          ? { id: 123, allFinished: false }
          : { id: 123, allFinished: Date.now() >= 61_000 };

      return Promise.resolve({
        ok: true,
        text: () => Promise.resolve(JSON.stringify(body)),
      });
    });
    vi.stubGlobal('fetch', fetchMock);

    const importPromise = importTextUnitsBatch({ textUnits: [] });
    const importExpectation = expect(importPromise).resolves.toMatchObject({
      id: 123,
      isAllFinished: true,
    });

    await vi.advanceTimersByTimeAsync(61_000);

    await importExpectation;
  });
});
