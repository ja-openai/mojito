import './app.css';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import {
  BrowserRouter,
  Navigate,
  NavLink,
  Outlet,
  Route,
  Routes,
  useLocation,
  useParams,
} from 'react-router-dom';

import { RequireUser, useUser } from './components/RequireUser';
import { UserMenu } from './components/UserMenu';
import { AiTranslatePage } from './page/ai-translate/AiTranslatePage';
import { GlossariesPage } from './page/glossaries/GlossariesPage';
import { GlossaryWorkspacePage } from './page/glossaries/GlossaryWorkspacePage';
import { MonitoringPage } from './page/monitoring/MonitoringPage';
import { RepositoriesPage } from './page/repositories/RepositoriesPage';
import { ReviewProjectPage } from './page/review-project/ReviewProjectPage';
import { ReviewProjectCreatePage } from './page/review-projects/ReviewProjectCreatePage';
import { ReviewProjectsPage } from './page/review-projects/ReviewProjectsPage';
import { ScreenshotsDropzonePage } from './page/screenshots/ScreenshotsDropzonePage';
import { AdminAiLocalePromptSuffixPage } from './page/settings/AdminAiLocalePromptSuffixPage';
import { AdminAiTranslateAutomationPage } from './page/settings/AdminAiTranslateAutomationPage';
import { AdminGlossaryDetailPage } from './page/settings/AdminGlossaryDetailPage';
import { AdminReviewAutomationBatchPage } from './page/settings/AdminReviewAutomationBatchPage';
import { AdminReviewAutomationDetailPage } from './page/settings/AdminReviewAutomationDetailPage';
import { AdminReviewAutomationRunsPage } from './page/settings/AdminReviewAutomationRunsPage';
import { AdminReviewAutomationsPage } from './page/settings/AdminReviewAutomationsPage';
import { AdminReviewFeatureBatchPage } from './page/settings/AdminReviewFeatureBatchPage';
import { AdminReviewFeatureDetailPage } from './page/settings/AdminReviewFeatureDetailPage';
import { AdminReviewFeaturesPage } from './page/settings/AdminReviewFeaturesPage';
import { AdminSettingsPage } from './page/settings/AdminSettingsPage';
import { AdminTeamPoolsPage } from './page/settings/AdminTeamPoolsPage';
import { AdminTeamsPage } from './page/settings/AdminTeamsPage';
import { AdminTemporaryBulkTranslationAcceptPage } from './page/settings/AdminTemporaryBulkTranslationAcceptPage';
import { AdminTranslationIncidentsPage } from './page/settings/AdminTranslationIncidentsPage';
import { AdminUserBatchPage } from './page/settings/AdminUserBatchPage';
import { AdminUserDetailPage } from './page/settings/AdminUserDetailPage';
import { AdminUserSettingsPage } from './page/settings/AdminUserSettingsPage';
import { SettingsPage } from './page/settings/SettingsPage';
import { TeamDetailPage } from './page/settings/TeamDetailPage';
import { StatisticsPage } from './page/statistics/StatisticsPage';
import { TextUnitDetailPage } from './page/text-unit-detail/TextUnitDetailPage';
import { CharCodeHelperPage } from './page/tools/CharCodeHelperPage';
import { IcuMessagePreviewPage } from './page/tools/IcuMessagePreviewPage';
import { WorkbenchPage } from './page/workbench/WorkbenchPage';
import { canAccessGlossaries } from './utils/permissions';

type NavItem = {
  to: string;
  label: string;
  element: ReactNode;
};

const navItems: NavItem[] = [
  { to: '/repositories', label: 'Repositories', element: <RepositoriesPage /> },
  { to: '/workbench', label: 'Workbench', element: <WorkbenchPage /> },
  { to: '/review-projects', label: 'Review Projects', element: <ReviewProjectsPage /> },
];

const queryClient = new QueryClient();

function LegacyGlossariesRedirect() {
  const location = useLocation();
  return <Navigate to={`/glossaries${location.search}`} replace />;
}

function LegacyGlossarySettingsRedirect() {
  const params = useParams<{ glossaryId?: string }>();
  if (!params.glossaryId) {
    return <Navigate to="/glossaries" replace />;
  }
  return <Navigate to={`/glossaries/${params.glossaryId}/settings`} replace />;
}

function AppLayout({ showHeader }: { showHeader: boolean }) {
  const user = useUser();
  const location = useLocation();
  const canAccessIncidents = user.role === 'ROLE_ADMIN' || user.role === 'ROLE_PM';
  const headerNavItems = [
    ...navItems.map(({ to, label }) => ({ to, label })),
    ...(canAccessIncidents ? [{ to: '/translation-incidents', label: 'Incidents' }] : []),
    ...(canAccessGlossaries(user) ? [{ to: '/glossaries', label: 'Glossaries' }] : []),
    { to: '/settings/system', label: 'Settings' },
  ];

  return (
    <div className={`app-shell${showHeader ? '' : ' app-shell--bare'}`}>
      {showHeader ? (
        <header className="app-shell__header">
          <div className="app-shell__header-content">
            <nav className="app-shell__nav">
              {headerNavItems.map(({ to, label }) => (
                <NavLink
                  key={to}
                  to={to}
                  className={({ isActive }) => {
                    const isSettingsSection =
                      to === '/settings/system' && location.pathname.startsWith('/settings/');
                    const isIncidentSection =
                      to === '/translation-incidents' &&
                      location.pathname.startsWith('/translation-incidents');
                    return `app-shell__nav-link${isActive || isSettingsSection || isIncidentSection ? ' is-active' : ''}`;
                  }}
                >
                  {label}
                </NavLink>
              ))}
            </nav>
            <UserMenu />
          </div>
        </header>
      ) : null}
      <main className="app-shell__main">
        <Outlet />
      </main>
    </div>
  );
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route
            element={
              <RequireUser>
                <AppLayout showHeader />
              </RequireUser>
            }
          >
            <Route path="/" element={<Navigate to="/repositories" replace />} />
            {navItems.map(({ to, element }) => (
              <Route key={to} path={to} element={element} />
            ))}
            <Route path="/glossaries" element={<GlossariesPage />} />
            <Route path="/glossaries/:glossaryId/settings" element={<AdminGlossaryDetailPage />} />
            <Route path="/ai-translate" element={<AiTranslatePage />} />
            <Route path="/monitoring" element={<MonitoringPage />} />
            <Route path="/statistics" element={<StatisticsPage />} />
            <Route path="/review-projects/new" element={<ReviewProjectCreatePage />} />
            <Route path="/translation-incidents" element={<AdminTranslationIncidentsPage />} />
            <Route path="/screenshots" element={<ScreenshotsDropzonePage />} />
            <Route path="/settings" element={<Navigate to="/settings/me" replace />} />
            <Route path="/settings/me" element={<SettingsPage />} />
            <Route path="/settings/admin" element={<Navigate to="/settings/system" replace />} />
            <Route path="/settings/system" element={<AdminSettingsPage />} />
            <Route path="/settings/teams" element={<AdminTeamsPage />} />
            <Route
              path="/settings/team"
              element={<Navigate to="/settings/system/team-pools" replace />}
            />
            <Route path="/tools/char-code" element={<CharCodeHelperPage />} />
            <Route path="/tools/icu-preview" element={<IcuMessagePreviewPage />} />
            <Route path="*" element={<Navigate to="/repositories" replace />} />
          </Route>
          <Route
            element={
              <RequireUser>
                <AppLayout showHeader={false} />
              </RequireUser>
            }
          >
            <Route path="/glossaries/:glossaryId" element={<GlossaryWorkspacePage />} />
            <Route path="/review-projects/:projectId" element={<ReviewProjectPage />} />
            <Route path="/text-units/:tmTextUnitId" element={<TextUnitDetailPage />} />
            <Route
              path="/settings/admin/ai-translate"
              element={<AdminAiTranslateAutomationPage />}
            />
            <Route
              path="/settings/system/ai-translate"
              element={<AdminAiTranslateAutomationPage />}
            />
            <Route path="/settings/admin/review-features" element={<AdminReviewFeaturesPage />} />
            <Route path="/settings/system/review-features" element={<AdminReviewFeaturesPage />} />
            <Route path="/settings/admin/glossaries" element={<LegacyGlossariesRedirect />} />
            <Route path="/settings/system/glossaries" element={<LegacyGlossariesRedirect />} />
            <Route
              path="/settings/admin/review-automations"
              element={<AdminReviewAutomationsPage />}
            />
            <Route
              path="/settings/system/review-automations"
              element={<AdminReviewAutomationsPage />}
            />
            <Route
              path="/settings/admin/review-automation-runs"
              element={<AdminReviewAutomationRunsPage />}
            />
            <Route
              path="/settings/system/review-automation-runs"
              element={<AdminReviewAutomationRunsPage />}
            />
            <Route
              path="/settings/admin/ai-translate/prompt-suffixes"
              element={<AdminAiLocalePromptSuffixPage />}
            />
            <Route
              path="/settings/system/ai-translate/prompt-suffixes"
              element={<AdminAiLocalePromptSuffixPage />}
            />
            <Route path="/settings/admin/teams" element={<AdminTeamsPage />} />
            <Route path="/settings/admin/users" element={<AdminUserSettingsPage />} />
            <Route path="/settings/system/teams" element={<AdminTeamsPage />} />
            <Route path="/settings/system/users" element={<AdminUserSettingsPage />} />
            <Route path="/settings/admin/users/:userId" element={<AdminUserDetailPage />} />
            <Route path="/settings/admin/users/batch" element={<AdminUserBatchPage />} />
            <Route path="/settings/admin/teams/:teamId" element={<TeamDetailPage />} />
            <Route path="/settings/admin/team-pools" element={<AdminTeamPoolsPage />} />
            <Route path="/settings/system/users/:userId" element={<AdminUserDetailPage />} />
            <Route path="/settings/system/users/batch" element={<AdminUserBatchPage />} />
            <Route path="/settings/system/teams/:teamId" element={<TeamDetailPage />} />
            <Route path="/settings/system/team-pools" element={<AdminTeamPoolsPage />} />
            <Route
              path="/settings/admin/review-features/batch"
              element={<AdminReviewFeatureBatchPage />}
            />
            <Route
              path="/settings/system/review-features/batch"
              element={<AdminReviewFeatureBatchPage />}
            />
            <Route
              path="/settings/admin/review-features/:featureId"
              element={<AdminReviewFeatureDetailPage />}
            />
            <Route
              path="/settings/system/review-features/:featureId"
              element={<AdminReviewFeatureDetailPage />}
            />
            <Route
              path="/settings/admin/glossaries/:glossaryId"
              element={<LegacyGlossarySettingsRedirect />}
            />
            <Route
              path="/settings/system/glossaries/:glossaryId"
              element={<LegacyGlossarySettingsRedirect />}
            />
            <Route
              path="/settings/admin/review-automations/batch"
              element={<AdminReviewAutomationBatchPage />}
            />
            <Route
              path="/settings/system/review-automations/batch"
              element={<AdminReviewAutomationBatchPage />}
            />
            <Route
              path="/settings/admin/review-automations/:automationId"
              element={<AdminReviewAutomationDetailPage />}
            />
            <Route
              path="/settings/system/review-automations/:automationId"
              element={<AdminReviewAutomationDetailPage />}
            />
            <Route
              path="/settings/admin/temporary-bulk-translation-accept"
              element={<AdminTemporaryBulkTranslationAcceptPage />}
            />
            <Route
              path="/settings/system/temporary-bulk-translation-accept"
              element={<AdminTemporaryBulkTranslationAcceptPage />}
            />
            <Route
              path="/settings/admin/translation-incidents"
              element={<AdminTranslationIncidentsPage />}
            />
            <Route
              path="/settings/system/translation-incidents"
              element={<AdminTranslationIncidentsPage />}
            />
          </Route>
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
