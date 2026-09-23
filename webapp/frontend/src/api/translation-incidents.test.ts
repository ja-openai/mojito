// @vitest-environment node

import { afterEach, expect, it, vi } from 'vitest';

import { fetchTranslationIncidents } from './translation-incidents';

afterEach(() => vi.unstubAllGlobals());

it('filters independent incidents by workflow and exact run without changing status scope', async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValue(new Response(JSON.stringify({ items: [] }), { status: 200 }));
  vi.stubGlobal('fetch', fetchMock);
  await fetchTranslationIncidents({
    reviewType: 'TRANSLATION_QUALITY',
    reviewRunId: 51,
    status: 'OPEN',
    locale: 'fr-CA',
    size: 100,
  });
  const url = new URL(fetchMock.mock.calls[0][0] as string, 'http://localhost');
  expect(url.searchParams.get('reviewType')).toBe('TRANSLATION_QUALITY');
  expect(url.searchParams.get('reviewRunId')).toBe('51');
  expect(url.searchParams.get('status')).toBe('OPEN');
  expect(url.searchParams.get('locale')).toBe('fr-CA');
});

it('omits the locale filter when browsing all locales', async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValue(new Response(JSON.stringify({ items: [] }), { status: 200 }));
  vi.stubGlobal('fetch', fetchMock);
  await fetchTranslationIncidents({ locale: null });
  const url = new URL(fetchMock.mock.calls[0][0] as string, 'http://localhost');
  expect(url.searchParams.has('locale')).toBe(false);
});
