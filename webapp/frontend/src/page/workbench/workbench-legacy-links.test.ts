// @vitest-environment node

import { describe, expect, it } from 'vitest';

import { resolveLegacyWorkbenchLink } from './workbench-legacy-links';

describe('resolveLegacyWorkbenchLink', () => {
  it('resolves legacy ICT workbench links with repository names', () => {
    const result = resolveLegacyWorkbenchLink(
      new URLSearchParams(
        'repoNames[]=mojito&bcp47Tags[]=fr-FR&searchAttribute=stringId&searchType=exact&searchText=login.form.username',
      ),
      new Map([['mojito', 7]]),
      1,
    );

    expect(result.status).toBe('ready');
    if (result.status !== 'ready') return;
    expect(result.request).toEqual({
      repositoryIds: [7],
      localeTags: ['fr-FR'],
      searchAttribute: 'stringId',
      searchType: 'exact',
      searchText: 'login.form.username',
      offset: 0,
    });
    expect(result.nextSearchParams.toString()).toBe('');
  });

  it('keeps existing non-legacy query params when cleaning legacy params', () => {
    const result = resolveLegacyWorkbenchLink(
      new URLSearchParams(
        'repoIds[]=12&bcp47Tags[]=fr-FR&searchAttribute=target&searchText=Hello&foo=bar',
      ),
      new Map(),
      0,
    );

    expect(result.status).toBe('ready');
    if (result.status !== 'ready') return;
    expect(result.request.repositoryIds).toEqual([12]);
    expect(result.nextSearchParams.toString()).toBe('foo=bar');
  });

  it('waits for repositories before deciding a repository name is missing', () => {
    const result = resolveLegacyWorkbenchLink(
      new URLSearchParams('repoNames[]=mojito&bcp47Tags[]=fr-FR&searchText=Hello'),
      new Map(),
      0,
    );

    expect(result).toEqual({ status: 'pending' });
  });

  it('returns an error when a repository name is unknown after repositories load', () => {
    const result = resolveLegacyWorkbenchLink(
      new URLSearchParams('repoNames[]=missing&bcp47Tags[]=fr-FR&searchText=Hello'),
      new Map([['mojito', 7]]),
      1,
    );

    expect(result).toEqual({
      status: 'error',
      message: 'Could not find repository: missing',
    });
  });
});
