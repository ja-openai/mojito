import { describe, expect, it } from 'vitest';

import type { TextUnitSearchRequest } from '../../api/text-units';
import { buildWorkbenchDetailHash, readWorkbenchDetailContext } from './workbench-detail-link';

const searchRequest: TextUnitSearchRequest = {
  repositoryIds: [22, 23],
  localeTags: ['fr', 'ja'],
  tmTextUnitIds: [101, 102],
  textSearch: {
    operator: 'OR',
    predicates: [
      { field: 'source', searchType: 'contains', value: '設定 & café + # 50% / ? = 😀' },
      { field: 'comment', searchType: 'regex', value: '^account\\.[0-9]+\nnext line$' },
    ],
  },
  searchAttribute: 'target',
  searchType: 'exact',
  searchText: '  Preserve legacy spelling & spaces  ',
  offset: 0,
  limit: 25000,
  orderedByTextUnitId: true,
  statusFilter: 'REVIEW_NEEDED',
  glossaryStatusFilter: 'CANDIDATE',
  usedFilter: 'UNUSED',
  doNotTranslateFilter: false,
  tmTextUnitCreatedBefore: '2026-09-01T23:59:00Z',
  tmTextUnitCreatedAfter: '2026-08-01T00:00:00Z',
  tmTextUnitVariantCreatedBefore: '2026-09-02T23:59:00Z',
  tmTextUnitVariantCreatedAfter: '2026-08-02T00:00:00Z',
};

const context = {
  searchRequest,
  resultSortField: 'tmTextUnitId',
  resultSortDirection: 'desc',
};

function encodeContext(value: unknown): string {
  return `#workbench=${encodeURIComponent(JSON.stringify(value))}`;
}

describe('Workbench detail link context', () => {
  it('round-trips the entire applied request, Unicode and special characters without normalization', () => {
    const hash = buildWorkbenchDetailHash(searchRequest, 'tmTextUnitId', 'desc');
    const url = new URL(`/text-units/101?locale=fr${hash}`, 'https://mojito.example');

    expect(url.pathname).toBe('/text-units/101');
    expect(url.search).toBe('?locale=fr');
    expect(readWorkbenchDetailContext(url.hash)).toEqual(context);
  });

  it('captures a snapshot independent of later changes to the originating request', () => {
    const original = { ...searchRequest, repositoryIds: [...searchRequest.repositoryIds] };
    const hash = buildWorkbenchDetailHash(original, 'source', 'default');
    original.repositoryIds.push(99);
    original.limit = 10;

    expect(readWorkbenchDetailContext(hash)).toEqual({
      searchRequest,
      resultSortField: 'source',
      resultSortDirection: 'default',
    });
  });

  it('supports a minimal search without optional fields', () => {
    const minimal = { repositoryIds: [22], localeTags: ['fr'] };
    expect(
      readWorkbenchDetailContext(buildWorkbenchDetailHash(minimal, 'translation', 'asc')),
    ).toEqual({
      searchRequest: minimal,
      resultSortField: 'translation',
      resultSortDirection: 'asc',
    });
  });

  it('does not add context when no applied search exists', () => {
    expect(buildWorkbenchDetailHash(null, 'source', 'default')).toBe('');
  });

  it.each(['', '#history', '#workbench=', '#workbench=%E0%A4%A', '#workbench={broken'])(
    'ignores an absent or malformed fragment: %s',
    (hash) => {
      expect(readWorkbenchDetailContext(hash)).toBeNull();
    },
  );

  it.each(
    [
      null,
      [],
      {},
      { ...context, searchRequest: null },
      { ...context, resultSortField: 'unknown' },
      { ...context, resultSortDirection: ['desc'] },
      { ...context, searchRequest: { ...searchRequest, repositoryIds: '22' } },
      { ...context, searchRequest: { ...searchRequest, repositoryIds: [null] } },
      { ...context, searchRequest: { ...searchRequest, localeTags: [22] } },
      { ...context, searchRequest: { ...searchRequest, textSearch: { operator: 'OR' } } },
      {
        ...context,
        searchRequest: {
          ...searchRequest,
          textSearch: {
            operator: 'AND',
            predicates: [{ field: 'source', searchType: 'exact', value: 5 }],
          },
        },
      },
      { ...context, searchRequest: { ...searchRequest, searchAttribute: ['target'] } },
      { ...context, searchRequest: { ...searchRequest, searchType: ['exact'] } },
      { ...context, searchRequest: { ...searchRequest, glossaryStatusFilter: ['CANDIDATE'] } },
      { ...context, searchRequest: { ...searchRequest, tmTextUnitCreatedAfter: {} } },
      { ...context, searchRequest: { ...searchRequest, limit: -1 } },
      { ...context, searchRequest: { ...searchRequest, doNotTranslateFilter: 'false' } },
    ].map((payload) => ({ payload })),
  )('ignores an invalid context shape (%#)', ({ payload }) => {
    expect(readWorkbenchDetailContext(encodeContext(payload))).toBeNull();
  });
});
