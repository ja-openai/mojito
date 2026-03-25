import './settings-page.css';

import { Navigate, useNavigate } from 'react-router-dom';

import { useUser } from '../../components/RequireUser';

export function AdminSettingsPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const isPm = user.role === 'ROLE_PM';
  const navigate = useNavigate();

  if (!isAdmin && !isPm) {
    return <Navigate to="/settings/me" replace />;
  }

  return (
    <div className="settings-page">
      {isAdmin ? (
        <>
          <section className="settings-card" aria-labelledby="settings-ai-translation">
            <div className="settings-card__header">
              <h2 id="settings-ai-translation">AI Translation</h2>
            </div>
            <div className="settings-directory">
              <button
                type="button"
                className="settings-directory__card"
                onClick={() => {
                  void navigate('/settings/system/ai-translate');
                }}
              >
                <div className="settings-directory__title">Automation</div>
                <div className="settings-directory__description">
                  Configure AI translation automation rules.
                </div>
              </button>
              <button
                type="button"
                className="settings-directory__card"
                onClick={() => {
                  void navigate('/settings/system/ai-translate/prompt-suffixes');
                }}
              >
                <div className="settings-directory__title">AI prompt suffixes</div>
                <div className="settings-directory__description">
                  Manage locale-specific prompt suffixes.
                </div>
              </button>
            </div>
          </section>

          <section className="settings-card" aria-labelledby="settings-review-automation">
            <div className="settings-card__header">
              <h2 id="settings-review-automation">Review Automation</h2>
            </div>
            <div className="settings-directory">
              <button
                type="button"
                className="settings-directory__card"
                onClick={() => {
                  void navigate('/settings/system/review-features');
                }}
              >
                <div className="settings-directory__title">Review features</div>
                <div className="settings-directory__description">
                  Group repositories into review scopes.
                </div>
              </button>
              <button
                type="button"
                className="settings-directory__card"
                onClick={() => {
                  void navigate('/settings/system/review-automations');
                }}
              >
                <div className="settings-directory__title">Review automations</div>
                <div className="settings-directory__description">
                  Manage schedules and automated review runs.
                </div>
              </button>
            </div>
          </section>

          <section className="settings-card" aria-labelledby="settings-user-management">
            <div className="settings-card__header">
              <h2 id="settings-user-management">User management</h2>
            </div>
            <div className="settings-directory">
              <button
                type="button"
                className="settings-directory__card"
                onClick={() => {
                  void navigate('/settings/system/users');
                }}
              >
                <div className="settings-directory__title">Users</div>
                <div className="settings-directory__description">
                  Manage Mojito users and roles.
                </div>
              </button>
              <button
                type="button"
                className="settings-directory__card"
                onClick={() => {
                  void navigate('/settings/system/teams');
                }}
              >
                <div className="settings-directory__title">Teams</div>
                <div className="settings-directory__description">
                  Manage teams, pools, and ownership.
                </div>
              </button>
            </div>
          </section>
        </>
      ) : null}

      {isPm ? (
        <section className="settings-card" aria-labelledby="settings-team-directory">
          <div className="settings-card__header">
            <h2 id="settings-team-directory">Team settings</h2>
          </div>
          <div className="settings-directory">
            <button
              type="button"
              className="settings-directory__card"
              onClick={() => {
                void navigate('/settings/system/teams');
              }}
            >
              <div className="settings-directory__title">Teams</div>
              <div className="settings-directory__description">
                Review teams and pools you manage.
              </div>
            </button>
          </div>
        </section>
      ) : null}
    </div>
  );
}
