import type { ApiUserProfile } from './users';

export type FrontendSecurityConfig = {
  unauthRedirectTo?: string | null;
  oAuth2?: Record<string, { uiLabelText?: string | null }>;
};

export type FrontendStatelessConfig = {
  enabled: boolean;
  type?: string | null;
  msal?: {
    authority?: string | null;
    clientId?: string | null;
    scope?: string | null;
  } | null;
  cloudflare?: {
    localJwtAssertion?: string | null;
  } | null;
};

export type FrontendConfig = {
  user: ApiUserProfile;
  locale: string;
  ict: boolean;
  csrfToken?: string | null;
  contextPath?: string | null;
  security?: FrontendSecurityConfig | null;
  stateless?: FrontendStatelessConfig | null;
};

const FRONTEND_CONFIG_URL = '/api/frontend/config';

let configPromise: Promise<FrontendConfig> | null = null;

export function resetFrontendConfigForTests() {
  configPromise = null;
}

export function fetchFrontendConfig(): Promise<FrontendConfig> {
  if (!configPromise) {
    configPromise = fetch(FRONTEND_CONFIG_URL, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    }).then(async (response) => {
      if (!response.ok) {
        const message = await response.text().catch(() => '');
        throw new Error(message || 'Failed to load frontend configuration');
      }

      return (await response.json()) as FrontendConfig;
    });
  }

  return configPromise;
}
