import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getReviewProjectSearchEnabledKey,
  saveReviewProjectSearchEnabled,
} from '../utils/reviewProjectSearchPreference';
import { useReviewProjectSearchEnabled } from './useReviewProjectSearchEnabled';
import { UserContext } from './useUser';

function SearchPreference() {
  return <output>{useReviewProjectSearchEnabled() ? 'Enabled' : 'Disabled'}</output>;
}

function signedInAs(username: string) {
  return (
    <UserContext.Provider
      value={{ username, role: 'ROLE_TRANSLATOR', canTranslateAllLocales: true, userLocales: [] }}
    >
      <SearchPreference />
    </UserContext.Provider>
  );
}

beforeEach(() => window.localStorage.clear());
afterEach(() => vi.restoreAllMocks());

describe('useReviewProjectSearchEnabled', () => {
  it('immediately uses the signed-in account preference and restores it when switching back', () => {
    saveReviewProjectSearchEnabled(true, 'alice');
    const { rerender } = render(signedInAs('alice'));
    expect(screen.getByRole('status')).toHaveTextContent('Enabled');

    rerender(signedInAs('bob'));
    expect(screen.getByRole('status')).toHaveTextContent('Disabled');

    rerender(signedInAs('alice'));
    expect(screen.getByRole('status')).toHaveTextContent('Enabled');
  });

  it('replaces the storage subscription on account changes and releases it on unmount', () => {
    const addListener = vi.spyOn(window, 'addEventListener');
    const removeListener = vi.spyOn(window, 'removeEventListener');
    const { rerender, unmount } = render(signedInAs('alice'));
    const aliceListener = addListener.mock.calls.find(([event]) => event === 'storage')?.[1];
    expect(aliceListener).toBeDefined();

    rerender(signedInAs('bob'));
    expect(removeListener).toHaveBeenCalledWith('storage', aliceListener);
    const storageSubscriptions = addListener.mock.calls.filter(([event]) => event === 'storage');
    const bobListener = storageSubscriptions[storageSubscriptions.length - 1]?.[1];
    expect(bobListener).toBeDefined();
    expect(bobListener).not.toBe(aliceListener);

    act(() => {
      const key = getReviewProjectSearchEnabledKey('alice');
      window.localStorage.setItem(key, 'true');
      window.dispatchEvent(new StorageEvent('storage', { key, newValue: 'true' }));
    });
    expect(screen.getByRole('status')).toHaveTextContent('Disabled');

    act(() => {
      const key = getReviewProjectSearchEnabledKey('bob');
      window.localStorage.setItem(key, 'true');
      window.dispatchEvent(new StorageEvent('storage', { key, newValue: 'true' }));
    });
    expect(screen.getByRole('status')).toHaveTextContent('Enabled');

    unmount();
    expect(removeListener).toHaveBeenCalledWith('storage', bobListener);
  });
});
