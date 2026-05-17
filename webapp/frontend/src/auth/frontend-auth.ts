import { InteractionRequiredAuthError, PublicClientApplication } from '@azure/msal-browser';

import type { FrontendConfig } from '../api/frontend-config';
import { fetchFrontendConfig } from '../api/frontend-config';

type FetchInput = Parameters<typeof fetch>[0];
type FetchInit = Parameters<typeof fetch>[1];

let frontendConfig: FrontendConfig | null = null;
let originalFetch: typeof fetch | null = null;
let msalInstance: PublicClientApplication | null = null;
let msalReadyPromise: Promise<PublicClientApplication> | null = null;

export async function initializeFrontendAuth(): Promise<{ shouldRender: boolean }> {
  frontendConfig = await fetchFrontendConfig();
  installAuthenticatedFetch(frontendConfig);

  if (isCurrentPath('/auth/callback')) {
    const handled = await completeAuthRedirect();
    return { shouldRender: !handled };
  }

  return { shouldRender: true };
}

export async function completeAuthRedirect(): Promise<boolean> {
  if (!frontendConfig || !isMsalStateless(frontendConfig)) {
    return false;
  }

  const instance = await ensureMsalReady(frontendConfig);
  const result = await instance.handleRedirectPromise({ navigateToLoginRequestUrl: false });
  if (result?.account) {
    instance.setActiveAccount(result.account);
  } else {
    const accounts = instance.getAllAccounts();
    if (accounts.length > 0) {
      instance.setActiveAccount(accounts[0]);
    }
  }

  window.location.replace(getPathWithContext(getReturnToPath(result?.state), frontendConfig));
  return true;
}

export async function loginWithStatelessProvider(returnTo?: string): Promise<void> {
  if (!frontendConfig || !isMsalStateless(frontendConfig)) {
    return;
  }

  const instance = await ensureMsalReady(frontendConfig);
  const state = returnTo || getCurrentPathWithoutContext(frontendConfig);
  await instance.loginRedirect({
    scopes: getMsalScopes(frontendConfig),
    state: JSON.stringify({ returnTo: state }),
  });
}

function installAuthenticatedFetch(config: FrontendConfig) {
  if (originalFetch || typeof window === 'undefined') {
    return;
  }

  originalFetch = window.fetch.bind(window);
  window.fetch = async (input: FetchInput, init?: FetchInit) => {
    if (!originalFetch) {
      throw new Error('Fetch is not initialized');
    }

    const requestUrl = getRequestUrl(input);
    if (
      !requestUrl ||
      !isSameOriginApiRequest(requestUrl, config) ||
      isFrontendConfigRequest(requestUrl, config)
    ) {
      return originalFetch(input, init);
    }

    const method = getRequestMethod(input, init);
    const headers = getRequestHeaders(input, init);
    const nextInit: FetchInit = { ...init, headers };

    if (isStateless(config)) {
      nextInit.credentials = 'omit';

      if (isCloudflareStateless(config)) {
        const localAssertion = config.stateless?.cloudflare?.localJwtAssertion;
        if (localAssertion) {
          headers.set('CF-Access-Jwt-Assertion', localAssertion);
        }
      } else if (isMsalStateless(config)) {
        const token = await getMsalAccessToken(config);
        headers.set('Authorization', `Bearer ${token}`);
      }
    } else {
      nextInit.credentials = init?.credentials ?? 'same-origin';
      if (
        method !== 'GET' &&
        method !== 'HEAD' &&
        config.csrfToken &&
        !headers.has('X-CSRF-TOKEN')
      ) {
        headers.set('X-CSRF-TOKEN', config.csrfToken);
      }
    }

    const response = await originalFetch(input, nextInit);
    if (response.status === 401) {
      handleUnauthenticatedResponse(config);
    }
    return response;
  };
}

async function getMsalAccessToken(config: FrontendConfig): Promise<string> {
  const instance = await ensureMsalReady(config);
  let account = instance.getActiveAccount();
  if (!account) {
    const accounts = instance.getAllAccounts();
    if (accounts.length > 0) {
      account = accounts[0];
      instance.setActiveAccount(account);
    }
  }

  if (!account) {
    await loginWithStatelessProvider();
    return new Promise(() => undefined);
  }

  try {
    const result = await instance.acquireTokenSilent({
      scopes: getMsalScopes(config),
      account,
    });
    return result.accessToken;
  } catch (error) {
    if (error instanceof InteractionRequiredAuthError) {
      await loginWithStatelessProvider();
      return new Promise(() => undefined);
    }
    throw error;
  }
}

async function ensureMsalReady(config: FrontendConfig): Promise<PublicClientApplication> {
  if (!msalInstance) {
    const msal = config.stateless?.msal ?? {};
    msalInstance = new PublicClientApplication({
      auth: {
        authority: msal.authority ?? undefined,
        clientId: msal.clientId ?? '',
        redirectUri: `${window.location.origin}${getContextPath(config)}/auth/callback`,
      },
      cache: {
        cacheLocation: 'localStorage',
      },
    });
  }

  if (!msalReadyPromise) {
    msalReadyPromise = msalInstance
      .initialize()
      .then(() => msalInstance as PublicClientApplication);
  }

  return msalReadyPromise;
}

function handleUnauthenticatedResponse(config: FrontendConfig) {
  if (isStateless(config)) {
    void loginWithStatelessProvider();
    return;
  }

  const unauthRedirectTo = config.security?.unauthRedirectTo;
  if (unauthRedirectTo) {
    window.location.href = getPathWithContext(unauthRedirectTo, config);
    return;
  }

  window.location.href = getLoginUrl(config);
}

function getLoginUrl(config: FrontendConfig): string {
  const returnTo = getCurrentPathWithoutContext(config).replace(/^\//, '');
  const query = returnTo ? `?showPage=${encodeURIComponent(returnTo)}` : '';
  return `${getContextPath(config)}/login${query}`;
}

function getReturnToPath(state?: string): string {
  if (!state) {
    return '/repositories';
  }

  try {
    const parsed = JSON.parse(state) as { returnTo?: unknown };
    return typeof parsed.returnTo === 'string' && parsed.returnTo
      ? parsed.returnTo
      : '/repositories';
  } catch {
    return '/repositories';
  }
}

function getCurrentPathWithoutContext(config: FrontendConfig): string {
  const contextPath = getContextPath(config);
  const currentPath = `${window.location.pathname}${window.location.search}`;
  if (contextPath && currentPath.startsWith(`${contextPath}/`)) {
    return currentPath.substring(contextPath.length);
  }
  return currentPath;
}

function getPathWithContext(path: string, config: FrontendConfig): string {
  if (/^https?:\/\//.test(path)) {
    return path;
  }

  const contextPath = getContextPath(config);
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  if (contextPath && normalizedPath.startsWith(`${contextPath}/`)) {
    return normalizedPath;
  }
  return `${contextPath}${normalizedPath}`;
}

function isCurrentPath(path: string): boolean {
  return window.location.pathname === path || window.location.pathname.endsWith(path);
}

function getContextPath(config: FrontendConfig): string {
  const contextPath = config.contextPath?.trim();
  if (!contextPath || contextPath === '/') {
    return '';
  }
  return contextPath.startsWith('/') ? contextPath : `/${contextPath}`;
}

function getMsalScopes(config: FrontendConfig): string[] {
  const scope = config.stateless?.msal?.scope;
  return scope ? [scope] : [];
}

function isStateless(config: FrontendConfig): boolean {
  return config.stateless?.enabled === true;
}

function isMsalStateless(config: FrontendConfig): boolean {
  return isStateless(config) && getStatelessType(config) === 'MSAL';
}

function isCloudflareStateless(config: FrontendConfig): boolean {
  return isStateless(config) && getStatelessType(config) === 'CLOUDFLARE';
}

function getStatelessType(config: FrontendConfig): string {
  return (config.stateless?.type ?? 'MSAL').toUpperCase();
}

function getRequestUrl(input: FetchInput): URL | null {
  try {
    if (typeof input === 'string' || input instanceof URL) {
      return new URL(input.toString(), window.location.origin);
    }

    return new URL(input.url, window.location.origin);
  } catch {
    return null;
  }
}

function getRequestMethod(input: FetchInput, init?: FetchInit): string {
  if (init?.method) {
    return init.method.toUpperCase();
  }

  if (typeof Request !== 'undefined' && input instanceof Request) {
    return input.method.toUpperCase();
  }

  return 'GET';
}

function getRequestHeaders(input: FetchInput, init?: FetchInit): Headers {
  if (init?.headers) {
    return new Headers(init.headers);
  }

  if (typeof Request !== 'undefined' && input instanceof Request) {
    return new Headers(input.headers);
  }

  return new Headers();
}

function isSameOriginApiRequest(url: URL, config: FrontendConfig): boolean {
  if (url.origin !== window.location.origin) {
    return false;
  }

  const contextPath = getContextPath(config);
  return (
    url.pathname.startsWith('/api/') ||
    (!!contextPath && url.pathname.startsWith(`${contextPath}/api/`))
  );
}

function isFrontendConfigRequest(url: URL, config: FrontendConfig): boolean {
  const contextPath = getContextPath(config);
  return (
    url.pathname === '/api/frontend/config' ||
    (!!contextPath && url.pathname === `${contextPath}/api/frontend/config`)
  );
}
