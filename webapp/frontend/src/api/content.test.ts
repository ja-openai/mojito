import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { fetchContentAssets, fetchContentDirectories, fetchContentEmailParts } from './content';

function requestUrl() {
  const calls = vi.mocked(fetch).mock.calls;
  return new URL(calls[calls.length - 1][0] as string, 'http://localhost');
}

describe('content catalogue requests', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve({}) }),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  it('loads only the two exact email companions from the selected branch', async () => {
    vi.mocked(fetch).mockImplementation((input) => {
      const url = new URL(input as string, 'http://localhost');
      const part = url.searchParams.get('q');
      const result = part
        ? { assets: [{ assetId: part.includes('subject') ? 4 : 5, assetPath: part, branchId: 3 }] }
        : {
            document: { assetPath: url.pathname.endsWith('/4') ? 'subject' : 'preheader' },
            warnings: [],
          };
      return Promise.resolve({ ok: true, json: () => Promise.resolve(result) } as Response);
    });
    const result = await fetchContentEmailParts(7, 3, 'fr', 'emails/a & b_body.mdx');
    expect(result.documents).toHaveLength(2);
    const urls = vi
      .mocked(fetch)
      .mock.calls.map(([url]) => new URL(url as string, 'http://localhost'));
    expect(urls).toHaveLength(4);
    expect(
      urls
        .filter((url) => url.searchParams.has('q'))
        .map((url) => [
          url.searchParams.get('q'),
          url.searchParams.get('searchMode'),
          url.searchParams.get('branchId'),
        ]),
    ).toEqual([
      ['emails/a & b_subject.mdx', 'exact', '3'],
      ['emails/a & b_preheader.mdx', 'exact', '3'],
    ]);
    expect(
      urls
        .filter((url) => url.searchParams.has('locale'))
        .every(
          (url) =>
            url.searchParams.get('branchId') === '3' && url.searchParams.get('locale') === 'fr',
        ),
    ).toBe(true);
  });

  it('requests a bounded cursor page and encodes literal path and cursor values', async () => {
    await fetchContentAssets(7, {
      branchId: 3,
      query: 'content/100%_real/a & b.mdx',
      searchMode: 'prefix',
      directory: 'content/100%_real/',
      after: 'cursor+/=',
      before: null,
      offset: 0,
    });
    const url = requestUrl();
    expect(url.pathname).toBe('/api/repositories/7/content');
    expect(Object.fromEntries(url.searchParams)).toEqual({
      pagination: 'cursor',
      limit: '100',
      branchId: '3',
      q: 'content/100%_real/a & b.mdx',
      searchMode: 'prefix',
      directory: 'content/100%_real/',
      after: 'cursor+/=',
    });
  });

  it('requests immediate files for browsing without changing recursive search defaults', async () => {
    await fetchContentAssets(7, {
      branchId: 3,
      query: '',
      directory: 'modules/',
      recursive: false,
    });
    expect(requestUrl().searchParams.get('recursive')).toBe('false');
    expect(requestUrl().searchParams.get('directory')).toBe('modules/');
    await fetchContentAssets(7, { branchId: 3, query: 'guide' });
    expect(requestUrl().searchParams.has('recursive')).toBe(false);
  });

  it('supports a previous cursor and legacy offset without changing old links', async () => {
    await fetchContentAssets(7, {
      branchId: null,
      query: '',
      before: 'before',
      searchMode: 'exact',
    });
    expect(Object.fromEntries(requestUrl().searchParams)).toEqual({
      pagination: 'cursor',
      limit: '100',
      before: 'before',
      searchMode: 'exact',
    });
    await fetchContentAssets(7, {
      branchId: 3,
      query: 'guide',
      offset: 100,
      searchMode: 'contains',
    });
    expect(Object.fromEntries(requestUrl().searchParams)).toEqual({
      limit: '100',
      branchId: '3',
      q: 'guide',
      offset: '100',
      searchMode: 'contains',
    });
  });

  it('requests only one bounded directory level and page', async () => {
    await fetchContentDirectories(7, {
      branchId: 3,
      directory: 'content/100%_real/',
      after: 'next+/=',
    });
    expect(requestUrl().pathname).toBe('/api/repositories/7/content/directories');
    expect(Object.fromEntries(requestUrl().searchParams)).toEqual({
      limit: '50',
      branchId: '3',
      directory: 'content/100%_real/',
      after: 'next+/=',
    });
    await fetchContentDirectories(7, { branchId: null, directory: '', after: null });
    expect(Object.fromEntries(requestUrl().searchParams)).toEqual({ limit: '50' });
  });

  it('rejects failed catalogue and directory responses', async () => {
    vi.mocked(fetch).mockResolvedValue({ ok: false } as Response);
    await expect(fetchContentAssets(7, { branchId: null, query: '' })).rejects.toThrow(
      'Could not load repository content.',
    );
    await expect(
      fetchContentDirectories(7, { branchId: null, directory: '', after: null }),
    ).rejects.toThrow('Could not load repository content.');
  });
});
