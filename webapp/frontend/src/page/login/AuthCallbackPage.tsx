import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';

import { completeAuthRedirect } from '../../auth/frontend-auth';

export function AuthCallbackPage() {
  const navigate = useNavigate();

  useEffect(() => {
    completeAuthRedirect()
      .then((handled) => {
        if (!handled) {
          void navigate('/repositories', { replace: true });
        }
      })
      .catch(() => {
        void navigate('/login?error', { replace: true });
      });
  }, [navigate]);

  return (
    <div className="app-loading-state" role="status" aria-live="polite">
      <div className="app-loading-state__card">
        <div className="spinner spinner--md" aria-hidden />
        <div className="app-loading-state__text">Signing you in…</div>
      </div>
    </div>
  );
}
