import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getVisibleTextEditorEnabledKey,
  loadVisibleTextEditorEnabled,
  saveVisibleTextEditorEnabled,
  subscribeVisibleTextEditorPreference,
  VISIBLE_TEXT_EDITOR_ENABLED_KEY,
} from './visibleTextEditorPreference';

describe('visibleTextEditorPreference', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('defaults to disabled per user', () => {
    expect(loadVisibleTextEditorEnabled('translator@example.com')).toBe(false);
  });

  it('saves enabled state per user and removes default disabled state', () => {
    const username = 'translator@example.com';
    const storageKey = getVisibleTextEditorEnabledKey(username);

    saveVisibleTextEditorEnabled(true, username);
    expect(window.localStorage.getItem(storageKey)).toBe('true');
    expect(loadVisibleTextEditorEnabled(username)).toBe(true);
    expect(loadVisibleTextEditorEnabled('admin@example.com')).toBe(false);

    saveVisibleTextEditorEnabled(false, username);
    expect(window.localStorage.getItem(storageKey)).toBeNull();
    expect(loadVisibleTextEditorEnabled(username)).toBe(false);
  });

  it('ignores the legacy browser-level preference for signed-in users', () => {
    window.localStorage.setItem(VISIBLE_TEXT_EDITOR_ENABLED_KEY, 'true');

    expect(loadVisibleTextEditorEnabled('translator@example.com')).toBe(false);
  });

  it('notifies same-tab subscribers when the preference changes', () => {
    const listener = vi.fn();
    const username = 'translator@example.com';
    const unsubscribe = subscribeVisibleTextEditorPreference(username, listener);

    saveVisibleTextEditorEnabled(true, username);

    expect(listener).toHaveBeenCalledOnce();
    unsubscribe();
  });
});
