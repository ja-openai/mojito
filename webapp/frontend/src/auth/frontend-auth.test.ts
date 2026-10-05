import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { FrontendConfig } from '../api/frontend-config';

const mocks = vi.hoisted(() => ({
  fetchConfig: vi.fn(),
  initializeMsal: vi.fn().mockResolvedValue(undefined),
  logoutRedirect: vi.fn().mockResolvedValue(undefined),
  getActiveAccount: vi.fn(),
}));

vi.mock('../api/frontend-config', () => ({ fetchFrontendConfig: mocks.fetchConfig }));
vi.mock('@azure/msal-browser', () => ({
  InteractionRequiredAuthError: class extends Error {},
  PublicClientApplication: class {
    initialize = mocks.initializeMsal;
    logoutRedirect = mocks.logoutRedirect;
    getActiveAccount = mocks.getActiveAccount;
  },
}));

const baseConfig: FrontendConfig = {
  user: {
    username: 'alice',
    role: 'ROLE_TRANSLATOR',
    canTranslateAllLocales: false,
    userLocales: [],
  },
  locale: 'en',
  ict: false,
  csrfToken: 'test-csrf-token',
  contextPath: '/mojito',
};
const assign = vi.fn();

beforeEach(() => {
  vi.resetModules();
  vi.clearAllMocks();
  vi.stubGlobal('window', {
    location: { origin: 'https://example.com', pathname: '/mojito/workbench', search: '', assign },
    fetch: vi.fn(),
  });
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

async function initialize(config: FrontendConfig) {
  mocks.fetchConfig.mockResolvedValue(config);
  const auth = await import('./frontend-auth');
  await auth.initializeFrontendAuth();
  return auth;
}

describe('logout', () => {
  it('uses the origin-root Cloudflare endpoint even with an application context path', async () => {
    const auth = await initialize({
      ...baseConfig,
      stateless: { enabled: true, type: 'CLOUDFLARE' },
    });
    await auth.logout();
    expect(assign).toHaveBeenCalledWith('/cdn-cgi/access/logout');
    expect(mocks.initializeMsal).not.toHaveBeenCalled();
  });

  it('clears the MSAL account through the provider and returns to the login page', async () => {
    const account = { homeAccountId: 'test-account' };
    mocks.getActiveAccount.mockReturnValue(account);
    const auth = await initialize({ ...baseConfig, stateless: { enabled: true, type: 'MSAL' } });
    await auth.logout();
    expect(mocks.initializeMsal).toHaveBeenCalledOnce();
    expect(mocks.logoutRedirect).toHaveBeenCalledWith({
      account,
      postLogoutRedirectUri: 'https://example.com/mojito/login?logout',
    });
    expect(assign).not.toHaveBeenCalled();
  });

  it('posts the CSRF token to Spring logout when stateless auth is disabled', async () => {
    const submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(function (
      this: HTMLFormElement,
    ) {
      expect(this.method).toBe('post');
      expect(this.getAttribute('action')).toBe('/mojito/logout');
      expect(new FormData(this).get('_csrf')).toBe('test-csrf-token');
      expect(document.body.contains(this)).toBe(true);
    });
    const auth = await initialize({
      ...baseConfig,
      stateless: { enabled: false, type: 'CLOUDFLARE' },
    });
    await auth.logout();
    expect(submit).toHaveBeenCalledOnce();
    expect(document.querySelector('form')).toBeNull();
    expect(assign).not.toHaveBeenCalled();
  });

  it('supports session logout without a context path or stateless config', async () => {
    const submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(function (
      this: HTMLFormElement,
    ) {
      expect(this.getAttribute('action')).toBe('/logout');
    });
    const auth = await initialize({ ...baseConfig, contextPath: '' });
    await auth.logout();
    expect(submit).toHaveBeenCalledOnce();
  });

  it('propagates provider failures so the menu can offer a retry', async () => {
    mocks.logoutRedirect.mockRejectedValueOnce(new Error('Provider unavailable'));
    const auth = await initialize({ ...baseConfig, stateless: { enabled: true, type: 'MSAL' } });
    await expect(auth.logout()).rejects.toThrow('Provider unavailable');
  });
});
