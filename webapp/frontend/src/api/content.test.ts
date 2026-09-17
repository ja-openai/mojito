import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { fetchContentAssets, fetchContentDirectories } from './content';

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
