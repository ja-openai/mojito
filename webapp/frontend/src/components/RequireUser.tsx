import type { ReactNode } from 'react';

import { useCurrentUser } from '../hooks/useCurrentUser';
import { UserContext } from '../hooks/useUser';

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

  return <UserContext.Provider value={data}>{children}</UserContext.Provider>;
}
