import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  loadVisibleTextEditorEnabled,
  saveVisibleTextEditorEnabled,
  subscribeVisibleTextEditorPreference,
  VISIBLE_TEXT_EDITOR_ENABLED_KEY,
} from './visibleTextEditorPreference';

describe('visibleTextEditorPreference', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('defaults to disabled', () => {
    expect(loadVisibleTextEditorEnabled()).toBe(false);
  });

  it('saves enabled state and removes default disabled state', () => {
    saveVisibleTextEditorEnabled(true);
    expect(window.localStorage.getItem(VISIBLE_TEXT_EDITOR_ENABLED_KEY)).toBe('true');
    expect(loadVisibleTextEditorEnabled()).toBe(true);

    saveVisibleTextEditorEnabled(false);
    expect(window.localStorage.getItem(VISIBLE_TEXT_EDITOR_ENABLED_KEY)).toBeNull();
    expect(loadVisibleTextEditorEnabled()).toBe(false);
  });

  it('notifies same-tab subscribers when the preference changes', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeVisibleTextEditorPreference(listener);

    saveVisibleTextEditorEnabled(true);

    expect(listener).toHaveBeenCalledOnce();
    unsubscribe();
  });
});
