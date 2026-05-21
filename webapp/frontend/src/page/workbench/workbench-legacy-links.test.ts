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
        'repoIds[]=12&bcp47Tags[]=fr-FR&searchAttribute=target&searchText=Hello&foo=bar&ws=stale',
      ),
      new Map(),
      0,
    );

    expect(result.status).toBe('ready');
    if (result.status !== 'ready') return;
    expect(result.request.repositoryIds).toEqual([12]);
    expect(result.nextSearchParams.toString()).toBe('foo=bar');
  });

  it('hydrates search controls even when a legacy link omits locales', () => {
    const result = resolveLegacyWorkbenchLink(
      new URLSearchParams(
        'repoNames[]=chatgpt-web&searchAttribute=stringId&searchType=exact&searchText=SplashScreenV2.shouldWeBegin',
      ),
      new Map([['chatgpt-web', 22]]),
      1,
    );

    expect(result.status).toBe('ready');
    if (result.status !== 'ready') return;
    expect(result.request).toEqual({
      repositoryIds: [22],
      localeTags: [],
      searchAttribute: 'stringId',
      searchType: 'exact',
      searchText: 'SplashScreenV2.shouldWeBegin',
      offset: 0,
    });
  });

  it('resolves encoded legacy params before a stale workbench session token', () => {
    const result = resolveLegacyWorkbenchLink(
      new URLSearchParams(
        'repoNames%5B%5D=test1&bcp47Tags%5B%5D=fr-FR&searchAttribute=stringId&searchType=exact&searchText=SplashScreenV2.onYourMind&ws=d3f6c353-1291-4f49-9b89-e7d63274ea6a',
      ),
      new Map([['test1', 24]]),
      1,
    );

    expect(result.status).toBe('ready');
    if (result.status !== 'ready') return;
    expect(result.request).toEqual({
      repositoryIds: [24],
      localeTags: ['fr-FR'],
      searchAttribute: 'stringId',
      searchType: 'exact',
      searchText: 'SplashScreenV2.onYourMind',
      offset: 0,
    });
    expect(result.nextSearchParams.toString()).toBe('');
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
