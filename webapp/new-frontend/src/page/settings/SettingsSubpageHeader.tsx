import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';

type SettingsSubpageHeaderProps = {
  backTo: string;
  backLabel: string;
  context?: ReactNode;
  title: ReactNode;
  centerContent?: ReactNode;
  rightContent?: ReactNode;
};

export function SettingsSubpageHeader({
  backTo,
  backLabel,
  context,
  title,
  centerContent,
  rightContent,
}: SettingsSubpageHeaderProps) {
  return (
    <header className="settings-subpage__topbar">
      <div className="settings-subpage__topbar-row">
        <div className="settings-subpage__topbar-group settings-subpage__topbar-group--left">
          <Link
            to={backTo}
            className="settings-subpage__topbar-back"
            aria-label={backLabel}
            title={backLabel}
          >
            <svg
              className="settings-subpage__topbar-back-icon"
              viewBox="0 0 24 24"
              aria-hidden="true"
              focusable="false"
            >
              <path
                d="M20 12H6m0 0l5-5m-5 5l5 5"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </Link>
          <div className="settings-subpage__topbar-copy">
            {context ? <div className="settings-subpage__topbar-context">{context}</div> : null}
            <h1 className="settings-subpage__topbar-title">{title}</h1>
          </div>
        </div>
        {centerContent ? (
          <div className="settings-subpage__topbar-group settings-subpage__topbar-group--center">
            {centerContent}
          </div>
        ) : null}
        {rightContent ? (
          <div className="settings-subpage__topbar-group settings-subpage__topbar-group--right">
            {rightContent}
          </div>
        ) : null}
      </div>
    </header>
  );
}
