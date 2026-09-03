import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getReviewProjectSearchEnabledKey,
  loadReviewProjectSearchEnabled,
  REVIEW_PROJECT_SEARCH_ENABLED_KEY,
  saveReviewProjectSearchEnabled,
  subscribeReviewProjectSearchPreference,
} from './reviewProjectSearchPreference';

describe('reviewProjectSearchPreference', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('defaults off and ignores preferences for another account or the whole browser', () => {
    window.localStorage.setItem(REVIEW_PROJECT_SEARCH_ENABLED_KEY, 'true');
    saveReviewProjectSearchEnabled(true, 'admin@example.com');

    expect(loadReviewProjectSearchEnabled('translator@example.com')).toBe(false);
    expect(loadReviewProjectSearchEnabled('admin@example.com')).toBe(true);
  });

  it('persists enabled state for the trimmed username and removes the disabled default', () => {
    const username = ' translator@example.com ';
    const key = getReviewProjectSearchEnabledKey(username);

    expect(key).toBe(`${REVIEW_PROJECT_SEARCH_ENABLED_KEY}.translator%40example.com`);
    saveReviewProjectSearchEnabled(true, username);
    expect(window.localStorage.getItem(key)).toBe('true');
    expect(loadReviewProjectSearchEnabled('translator@example.com')).toBe(true);

    saveReviewProjectSearchEnabled(false, username);
    expect(window.localStorage.getItem(key)).toBeNull();
    expect(loadReviewProjectSearchEnabled(username)).toBe(false);
  });

  it('only accepts an explicitly enabled preference', () => {
    window.localStorage.setItem(getReviewProjectSearchEnabledKey('translator'), 'invalid');
    expect(loadReviewProjectSearchEnabled('translator')).toBe(false);
  });

  it('notifies same-window subscribers on enable and disable and cleans up subscriptions', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeReviewProjectSearchPreference('translator', listener);

    saveReviewProjectSearchEnabled(true, 'translator');
    saveReviewProjectSearchEnabled(false, 'translator');
    expect(listener).toHaveBeenCalledTimes(2);

    unsubscribe();
    saveReviewProjectSearchEnabled(true, 'translator');
    expect(listener).toHaveBeenCalledTimes(2);
  });

  it('observes cross-tab changes for the current user and storage clears only', () => {
    const listener = vi.fn();
    const username = 'translator';
    const unsubscribe = subscribeReviewProjectSearchPreference(username, listener);

    window.dispatchEvent(
      new StorageEvent('storage', { key: getReviewProjectSearchEnabledKey('other-user') }),
    );
    expect(listener).not.toHaveBeenCalled();

    window.dispatchEvent(
      new StorageEvent('storage', { key: getReviewProjectSearchEnabledKey(username) }),
    );
    window.dispatchEvent(new StorageEvent('storage', { key: null }));
    expect(listener).toHaveBeenCalledTimes(2);

    unsubscribe();
    window.dispatchEvent(new StorageEvent('storage', { key: null }));
    expect(listener).toHaveBeenCalledTimes(2);
  });

  it('defaults off when storage reads are unavailable', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('Storage unavailable', 'SecurityError');
    });

    expect(loadReviewProjectSearchEnabled('translator')).toBe(false);
  });

  it('does not notify subscribers when saving fails', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeReviewProjectSearchPreference('translator', listener);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Storage full', 'QuotaExceededError');
    });

    expect(() => saveReviewProjectSearchEnabled(true, 'translator')).not.toThrow();
    expect(listener).not.toHaveBeenCalled();
    expect(loadReviewProjectSearchEnabled('translator')).toBe(false);
    unsubscribe();
  });
});
