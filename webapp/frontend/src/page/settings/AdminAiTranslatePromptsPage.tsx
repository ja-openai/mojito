import './settings-page.css';
import './admin-ai-locale-prompt-suffix-page.css';
import './term-index-explorer-page.css';

import { Link, Navigate, useSearchParams } from 'react-router-dom';

import { useUser } from '../../hooks/useUser';
import { AdminAiLocalePromptSuffixPage } from './AdminAiLocalePromptSuffixPage';
import { AdminAiSourcePromptRulesPage } from './AdminAiSourcePromptRulesPage';
import { AdminAiTranslateEvaluationsPage } from './AdminAiTranslateEvaluationsPage';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

type PromptTab = 'locales' | 'source-rules' | 'learning';

const PROMPT_TABS: Array<{ id: PromptTab; label: string }> = [
  {
    id: 'locales',
    label: 'Locale prompts',
  },
  {
    id: 'source-rules',
    label: 'Source rules',
  },
  {
    id: 'learning',
    label: 'Learning',
  },
];

function parsePromptTab(value: string | null): PromptTab {
  if (value === 'source-rules' || value === 'learning') {
    return value;
  }
  return 'locales';
}

export function AdminAiTranslatePromptsPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const [searchParams] = useSearchParams();
  const activeTab = parsePromptTab(searchParams.get('tab'));

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
        context="Settings"
        title="AI translation prompts"
        centerContent={<AiTranslatePromptsSubnav active={activeTab} />}
      />
      {activeTab === 'locales' ? <AdminAiLocalePromptSuffixPage embedded /> : null}
      {activeTab === 'source-rules' ? <AdminAiSourcePromptRulesPage embedded /> : null}
      {activeTab === 'learning' ? <AdminAiTranslateEvaluationsPage /> : null}
    </div>
  );
}

function AiTranslatePromptsSubnav({ active }: { active: PromptTab }) {
  return (
    <nav className="term-index-explorer__subnav" aria-label="AI translation prompt sections">
      {PROMPT_TABS.map((tab) => (
        <Link
          key={tab.id}
          className={`term-index-explorer__subnav-link${active === tab.id ? ' is-active' : ''}`}
          to={
            tab.id === 'locales'
              ? '/settings/system/ai-translate/prompts'
              : `/settings/system/ai-translate/prompts?tab=${tab.id}`
          }
        >
          {tab.label}
        </Link>
      ))}
    </nav>
  );
}
