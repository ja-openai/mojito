import type { ReactNode } from 'react';

import { useCurrentUser } from '../hooks/useCurrentUser';
import { UserContext } from '../hooks/useUser';
import { useUserPreferences } from '../hooks/useUserPreferences';

function RequireUserPreferences({ children }: { children: ReactNode }) {
  const { data, isError, isFetching, refetch } = useUserPreferences();
  if (!data) {
    return (
      <div className="app-loading-state" role={isError ? 'alert' : 'status'}>
        <div className="app-loading-state__card">
          {isError ? (
            <>
              <div className="app-loading-state__text">Could not load your settings.</div>
              <button type="button" disabled={isFetching} onClick={() => void refetch()}>
                {isFetching ? 'Retrying…' : 'Retry'}
              </button>
            </>
          ) : (
            <>
              <div className="spinner spinner--md" aria-hidden />
              <div className="app-loading-state__text">Loading your settings…</div>
            </>
          )}
        </div>
      </div>
    );
  }
  return children;
}

export function RequireUser({ children }: { children: ReactNode }) {
  const { data, isLoading, isError } = useCurrentUser();

  if (isLoading) {
    return (
      <div className="app-loading-state" role="status" aria-live="polite">
        <div className="app-loading-state__card">
          <div className="spinner spinner--md" aria-hidden />
          <div className="app-loading-state__text">Loading user…</div>
        </div>
      </div>
    );
  }

  if (isError || !data) {
    return (
      <div className="app-loading-state" role="alert" aria-live="assertive">
        <div className="app-loading-state__card">
          <div className="app-loading-state__text">Could not load user information.</div>
        </div>
      </div>
    );
  }

  return (
    <UserContext.Provider value={data} key={data.username}>
      <RequireUserPreferences>{children}</RequireUserPreferences>
    </UserContext.Provider>
  );
}
