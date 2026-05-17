import { useEffect, useMemo, useState } from 'react';
import { useLocation } from 'react-router-dom';

import { fetchFrontendConfig, type FrontendConfig } from '../../api/frontend-config';
import { loginWithStatelessProvider } from '../../auth/frontend-auth';

function getContextPath(config: FrontendConfig | null): string {
  const contextPath = config?.contextPath?.trim();
  if (!contextPath || contextPath === '/') {
    return '';
  }
  return contextPath.startsWith('/') ? contextPath : `/${contextPath}`;
}

export function LoginPage() {
  const location = useLocation();
  const [config, setConfig] = useState<FrontendConfig | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchFrontendConfig()
      .then((nextConfig) => {
        if (!cancelled) {
          setConfig(nextConfig);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setError('Could not load login configuration.');
        }
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const query = useMemo(() => new URLSearchParams(location.search), [location.search]);
  const showPage = query.get('showPage') ?? '';
  const contextPath = getContextPath(config);
  const loginAction = `${contextPath}/login${showPage ? `?showPage=${encodeURIComponent(showPage)}` : ''}`;
  const oauthRegistrations = Object.entries(config?.security?.oAuth2 ?? {});
  const isStatelessMsal =
    config?.stateless?.enabled === true &&
    (config.stateless.type ?? 'MSAL').toUpperCase() === 'MSAL';
  const returnTo = showPage ? `/${showPage}` : '/repositories';

  return (
    <main className="login-page">
      <section className="login-panel" aria-labelledby="login-title">
        <div className="login-panel__brand" aria-hidden="true">
          Mojito
        </div>
        <h1 id="login-title" className="login-panel__title">
          Sign in
        </h1>
        {query.has('error') ? (
          <p className="login-panel__notice" role="alert">
            Invalid username or password.
          </p>
        ) : null}
        {query.has('logout') ? (
          <p className="login-panel__notice">You have been logged out.</p>
        ) : null}
        {error ? (
          <p className="login-panel__notice" role="alert">
            {error}
          </p>
        ) : null}

        {isStatelessMsal ? (
          <button
            type="button"
            className="login-panel__button login-panel__button--primary"
            onClick={() => void loginWithStatelessProvider(returnTo)}
          >
            Continue with Microsoft
          </button>
        ) : (
          <form className="login-panel__form" action={loginAction} method="post">
            <label className="login-panel__field">
              <span>Username</span>
              <input name="username" type="text" autoComplete="username" disabled={!config} />
            </label>
            <label className="login-panel__field">
              <span>Password</span>
              <input
                name="password"
                type="password"
                autoComplete="current-password"
                disabled={!config}
              />
            </label>
            <input type="hidden" name="_csrf" value={config?.csrfToken ?? ''} />
            <button
              type="submit"
              className="login-panel__button login-panel__button--primary"
              disabled={!config}
            >
              Sign in
            </button>
          </form>
        )}

        {oauthRegistrations.length > 0 ? (
          <div className="login-panel__oauth" aria-label="OAuth providers">
            <div className="login-panel__divider">OAuth</div>
            {oauthRegistrations.map(([registrationId, registration]) => (
              <button
                key={registrationId}
                type="button"
                className="login-panel__button"
                onClick={() => {
                  window.location.href = `${contextPath}/login/oauth2/authorization/${registrationId}`;
                }}
              >
                {registration.uiLabelText || registrationId}
              </button>
            ))}
          </div>
        ) : null}
      </section>
    </main>
  );
}
