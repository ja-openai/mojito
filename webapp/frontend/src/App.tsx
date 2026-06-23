import './app.css';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { lazy, type ReactNode, Suspense, useState } from 'react';
import {
  createBrowserRouter,
  Navigate,
  NavLink,
  Outlet,
  Route,
  RouterProvider,
  Routes,
  useLocation,
  useParams,
} from 'react-router-dom';

import { RequireUser } from './components/RequireUser';
import { UserMenu } from './components/UserMenu';
import { useUser } from './hooks/useUser';
import { AiTranslatePage } from './page/ai-translate/AiTranslatePage';
import { GlossariesPage } from './page/glossaries/GlossariesPage';
import { GlossaryWorkspacePage } from './page/glossaries/GlossaryWorkspacePage';
import { AuthCallbackPage } from './page/login/AuthCallbackPage';
import { LoginPage } from './page/login/LoginPage';
import { MonitoringPage } from './page/monitoring/MonitoringPage';
import { RepositoriesPage } from './page/repositories/RepositoriesPage';
import { ReviewProjectPage } from './page/review-project/ReviewProjectPage';
import { ReviewProjectCreatePage } from './page/review-projects/ReviewProjectCreatePage';
import { ReviewProjectsPage } from './page/review-projects/ReviewProjectsPage';
import { ScreenshotsDropzonePage } from './page/screenshots/ScreenshotsDropzonePage';
import { AdminAiTranslateAutomationPage } from './page/settings/AdminAiTranslateAutomationPage';
import { AdminAiTranslatePromptsPage } from './page/settings/AdminAiTranslatePromptsPage';
import { AdminGlossaryDetailPage } from './page/settings/AdminGlossaryDetailPage';
import { AdminGlossaryWorkflowPage } from './page/settings/AdminGlossaryWorkflowPage';
import { AdminJsonConfigLocalizationPage } from './page/settings/AdminJsonConfigLocalizationPage';
import { AdminLinguistTimeSpentPage } from './page/settings/AdminLinguistTimeSpentPage';
import { AdminReviewAutomationBatchPage } from './page/settings/AdminReviewAutomationBatchPage';
import { AdminReviewAutomationDetailPage } from './page/settings/AdminReviewAutomationDetailPage';
import { AdminReviewAutomationRunsPage } from './page/settings/AdminReviewAutomationRunsPage';
import { AdminReviewAutomationsPage } from './page/settings/AdminReviewAutomationsPage';
import { AdminReviewFeatureBatchPage } from './page/settings/AdminReviewFeatureBatchPage';
import { AdminReviewFeatureDetailPage } from './page/settings/AdminReviewFeatureDetailPage';
import { AdminReviewFeaturesPage } from './page/settings/AdminReviewFeaturesPage';
import { AdminSettingsPage } from './page/settings/AdminSettingsPage';
import { AdminStringAuthoringPage } from './page/settings/AdminStringAuthoringPage';
import { AdminTeamPoolsPage } from './page/settings/AdminTeamPoolsPage';
import { AdminTeamsPage } from './page/settings/AdminTeamsPage';
import { AdminTemporaryBulkTranslationAcceptPage } from './page/settings/AdminTemporaryBulkTranslationAcceptPage';
import {
  AdminTermIndexAutomationPage,
  AdminTermIndexCandidatesPage,
  AdminTermIndexTermsPage,
} from './page/settings/AdminTermIndexExplorerPage';
import { AdminTranslationIncidentsPage } from './page/settings/AdminTranslationIncidentsPage';
import { AdminUserBatchPage } from './page/settings/AdminUserBatchPage';
import { AdminUserDetailPage } from './page/settings/AdminUserDetailPage';
import { AdminUserSettingsPage } from './page/settings/AdminUserSettingsPage';
import { SettingsPage } from './page/settings/SettingsPage';
import { SettingsSubpageHeader } from './page/settings/SettingsSubpageHeader';
import { TeamDetailPage } from './page/settings/TeamDetailPage';
import { StatisticsPage } from './page/statistics/StatisticsPage';
import { TextUnitDetailPage } from './page/text-unit-detail/TextUnitDetailPage';
import { BidiHelperPage } from './page/tools/BidiHelperPage';
import { CharCodeHelperPage } from './page/tools/CharCodeHelperPage';
import { IcuMessagePreviewPage } from './page/tools/IcuMessagePreviewPage';
import { TextAssistPrototypePage } from './page/tools/TextAssistPrototypePage';
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
  { to: '/screenshots', label: 'Screenshots', element: <ScreenshotsDropzonePage /> },
];

const queryClient = new QueryClient();
const AdminContentCmsPage = lazy(async () => {
  const module = await import('./page/settings/AdminContentCmsPage');
  return { default: module.AdminContentCmsPage };
});

function ContentCmsRouteLoadingState() {
  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
        context="Settings"
        title="Content CMS"
      />
      <div className="settings-page settings-page--wide">
        <p className="settings-page__hint" role="status" aria-live="polite">
          Loading Content CMS...
        </p>
      </div>
    </div>
  );
}

function LazyAdminContentCmsPage() {
  return (
    <Suspense fallback={<ContentCmsRouteLoadingState />}>
      <AdminContentCmsPage />
    </Suspense>
  );
}

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

function LegacyStringAuthoringRedirect() {
  const location = useLocation();
  return <Navigate to={`/string-authoring${location.search}`} replace />;
}

function AppLayout({ showHeader }: { showHeader: boolean }) {
  const user = useUser();
  const location = useLocation();
  const canAccessIncidents = user.role === 'ROLE_ADMIN' || user.role === 'ROLE_PM';
  const canAccessStringAuthoring = user.role === 'ROLE_ADMIN';
  const headerNavItems = [
    ...navItems.map(({ to, label }) => ({ to, label })),
    ...(canAccessStringAuthoring ? [{ to: '/string-authoring', label: 'String Authoring' }] : []),
    ...(canAccessIncidents ? [{ to: '/translation-incidents', label: 'Incidents' }] : []),
    ...(canAccessGlossaries(user) ? [{ to: '/glossaries', label: 'Glossaries' }] : []),
    { to: '/settings/system', label: 'Settings' },
  ];
  const hasSpecificActiveNavItem = headerNavItems.some(
    ({ to }) => to !== '/settings/system' && isNavPathActive(location.pathname, to),
  );

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
                      to === '/settings/system' &&
                      location.pathname.startsWith('/settings/') &&
                      !hasSpecificActiveNavItem;
                    return `app-shell__nav-link${isActive || isSettingsSection ? ' is-active' : ''}`;
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

function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/auth/callback" element={<AuthCallbackPage />} />
      <Route path="/tools/mf2-editor-prototype" element={<TextAssistPrototypePage />} />
      <Route
        element={
          <RequireUser>
            <AppLayout showHeader />
          </RequireUser>
        }
      >
        <Route path="/" element={<Navigate to="/repositories" replace />} />
        <Route path="/project-requests" element={<Navigate to="/review-projects" replace />} />
        <Route path="/branches" element={<Navigate to="/repositories" replace />} />
        <Route path="/screenshots-legacy" element={<Navigate to="/screenshots" replace />} />
        {navItems.map(({ to, element }) => (
          <Route key={to} path={to} element={element} />
        ))}
        <Route path="/string-authoring" element={<AdminStringAuthoringPage />} />
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
        <Route
          path="/settings/user-management"
          element={<Navigate to="/settings/system/users" replace />}
        />
        <Route path="/settings/box" element={<Navigate to="/settings/system" replace />} />
        <Route path="/settings/admin" element={<Navigate to="/settings/system" replace />} />
        <Route path="/settings/system" element={<AdminSettingsPage />} />
        <Route path="/settings/teams" element={<AdminTeamsPage />} />
        <Route
          path="/settings/team"
          element={<Navigate to="/settings/system/team-pools" replace />}
        />
        <Route path="/tools/char-code" element={<CharCodeHelperPage />} />
        <Route path="/tools/bidi-helper" element={<BidiHelperPage />} />
        <Route path="/tools/icu-preview" element={<IcuMessagePreviewPage />} />
        <Route path="/tools/text-assist" element={<TextAssistPrototypePage />} />
      </Route>
      <Route
        element={
          <RequireUser>
            <AppLayout showHeader={false} />
          </RequireUser>
        }
      >
        <Route path="/glossaries/:glossaryId" element={<GlossaryWorkspacePage />} />
        <Route
          path="/glossaries/:glossaryId/terms/:tmTextUnitId"
          element={<GlossaryWorkspacePage />}
        />
        <Route path="/review-projects/:projectId" element={<ReviewProjectPage />} />
        <Route path="/text-units/:tmTextUnitId" element={<TextUnitDetailPage />} />
        <Route path="/settings/admin/ai-translate" element={<AdminAiTranslateAutomationPage />} />
        <Route path="/settings/system/ai-translate" element={<AdminAiTranslateAutomationPage />} />
        <Route path="/settings/admin/review-features" element={<AdminReviewFeaturesPage />} />
        <Route path="/settings/system/review-features" element={<AdminReviewFeaturesPage />} />
        <Route
          path="/settings/admin/json-config-localization"
          element={<AdminJsonConfigLocalizationPage />}
        />
        <Route
          path="/settings/admin/json-config-localization/:repositoryId"
          element={<AdminJsonConfigLocalizationPage />}
        />
        <Route
          path="/settings/system/json-config-localization"
          element={<AdminJsonConfigLocalizationPage />}
        />
        <Route
          path="/settings/system/json-config-localization/:repositoryId"
          element={<AdminJsonConfigLocalizationPage />}
        />
        <Route path="/settings/admin/string-authoring" element={<LegacyStringAuthoringRedirect />} />
        <Route path="/settings/system/string-authoring" element={<LegacyStringAuthoringRedirect />} />
        <Route
          path="/settings/admin/linguist-time-spent"
          element={<AdminLinguistTimeSpentPage />}
        />
        <Route
          path="/settings/system/linguist-time-spent"
          element={<AdminLinguistTimeSpentPage />}
        />
        <Route path="/settings/admin/glossaries" element={<LegacyGlossariesRedirect />} />
        <Route path="/settings/system/glossaries" element={<LegacyGlossariesRedirect />} />
        <Route
          path="/settings/admin/glossary-term-index"
          element={<AdminTermIndexAutomationPage />}
        />
        <Route
          path="/settings/system/glossary-term-index"
          element={<AdminTermIndexAutomationPage />}
        />
        <Route
          path="/settings/admin/glossary-term-index/workflow"
          element={<AdminGlossaryWorkflowPage />}
        />
        <Route
          path="/settings/system/glossary-term-index/workflow"
          element={<AdminGlossaryWorkflowPage />}
        />
        <Route
          path="/settings/admin/glossary-term-index/terms"
          element={<AdminTermIndexTermsPage />}
        />
        <Route
          path="/settings/system/glossary-term-index/terms"
          element={<AdminTermIndexTermsPage />}
        />
        <Route
          path="/settings/admin/glossary-term-index/candidates"
          element={<AdminTermIndexCandidatesPage />}
        />
        <Route
          path="/settings/system/glossary-term-index/candidates"
          element={<AdminTermIndexCandidatesPage />}
        />
        <Route path="/settings/admin/review-automations" element={<AdminReviewAutomationsPage />} />
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
          element={<Navigate to="/settings/admin/ai-translate/prompts" replace />}
        />
        <Route
          path="/settings/system/ai-translate/prompt-suffixes"
          element={<Navigate to="/settings/system/ai-translate/prompts" replace />}
        />
        <Route
          path="/settings/admin/ai-translate/prompts"
          element={<AdminAiTranslatePromptsPage />}
        />
        <Route
          path="/settings/system/ai-translate/prompts"
          element={<AdminAiTranslatePromptsPage />}
        />
        <Route
          path="/settings/admin/ai-translate/source-prompt-rules"
          element={<Navigate to="/settings/admin/ai-translate/prompts?tab=source-rules" replace />}
        />
        <Route
          path="/settings/system/ai-translate/source-prompt-rules"
          element={<Navigate to="/settings/system/ai-translate/prompts?tab=source-rules" replace />}
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
        <Route path="/settings/admin/content-cms" element={<LazyAdminContentCmsPage />} />
        <Route path="/settings/system/content-cms" element={<LazyAdminContentCmsPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/repositories" replace />} />
    </Routes>
  );
}

export function App() {
  const [router] = useState(() => createBrowserRouter([{ path: '*', element: <AppRoutes /> }]));

  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  );
}
