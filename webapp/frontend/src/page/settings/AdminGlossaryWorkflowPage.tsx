import './settings-page.css';
import './glossary-workflow-page.css';

import { Link, Navigate } from 'react-router-dom';

import { useUser } from '../../hooks/useUser';
import { TermIndexSubnav } from './AdminTermIndexExplorerPage';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

type WorkflowOwner = 'Automation' | 'Human' | 'Human or automation';

type WorkflowStep = {
  title: string;
  owner: WorkflowOwner;
  actor: string;
  input: string;
  output: string;
  where: { label: string; to?: string };
  note: string;
};

const workflowSteps: WorkflowStep[] = [
  {
    title: 'Create glossary scope',
    owner: 'Human',
    actor: 'PM/admin',
    input:
      'Product area, glossary description, repositories, locales, and any existing glossary source.',
    output: 'An empty or imported glossary with an explicit target scope and purpose.',
    where: { label: 'Glossaries', to: '/glossaries' },
    note: 'Use the glossary description for purpose, include/exclude guidance, and product context. Target glossary selection is explicit today; AI assignment to the right glossary is not implemented yet.',
  },
  {
    title: 'Extract source terms',
    owner: 'Automation',
    actor: 'Term index job',
    input: 'Repository text units in the selected product scope.',
    output: 'Glossary-neutral extracted terms with occurrence evidence.',
    where: { label: 'Glossary automation', to: '/settings/system/glossary-term-index' },
    note: 'This is raw signal. It does not create candidates or glossary terms by itself.',
  },
  {
    title: 'AI triage raw extracted terms',
    owner: 'Automation',
    actor: 'AI/system review, with admin override when needed',
    input: 'Extracted terms and occurrence evidence.',
    output: 'Accepted or rejected raw terms that candidate generation can use.',
    where: { label: 'Terms', to: '/settings/system/glossary-term-index/terms' },
    note: 'This page is a system/debug view for admins, not the vendor review surface.',
  },
  {
    title: 'Generate candidate proposals',
    owner: 'Automation',
    actor: 'AI, MCP, import, or external candidate source',
    input: 'Reviewed raw terms.',
    output:
      'term_index_candidate rows with term, definition, rationale, type, POS, enforcement, doNotTranslate, and confidence.',
    where: { label: 'Candidates', to: '/settings/system/glossary-term-index/candidates' },
    note: 'Use Generate candidates on the automation dashboard for the default batch path, or generate from selected terms for targeted reruns. Candidates are not active glossary terms yet.',
  },
  {
    title: 'Create candidate review project',
    owner: 'Human',
    actor: 'PM/admin',
    input:
      'Selected generated candidates, target glossary, review team, advisors, and optional decider.',
    output: 'A review project backed by candidate rows and target glossary links.',
    where: { label: 'Candidates', to: '/settings/system/glossary-term-index/candidates' },
    note: 'This is where the workflow leaves the system candidate page and becomes vendor-reviewable.',
  },
  {
    title: 'Vendor/advisor review',
    owner: 'Human',
    actor: 'Assigned reviewers',
    input: 'Candidate review project rows.',
    output: 'Specialist input on whether each candidate should become glossary terminology.',
    where: { label: 'Review Projects', to: '/review-projects' },
    note: 'Reviewers give input. They do not directly create glossary terms.',
  },
  {
    title: 'Promote approved candidates',
    owner: 'Human',
    actor: 'PM/admin decider, ideally in batch',
    input: 'Candidate metadata plus reviewer input.',
    output: 'Accepted candidates become glossary terms. Rejected candidates get ignore decisions.',
    where: { label: 'Review Projects', to: '/review-projects' },
    note: 'This should be a promotion gate, not a second linguistic review. Future policy automation can promote trusted high-confidence approvals automatically.',
  },
  {
    title: 'Optional active glossary cleanup',
    owner: 'Human or automation',
    actor: 'PM/admin, reviewers, or later automation',
    input: 'Existing source glossary terms.',
    output: 'Cleaned-up source terminology.',
    where: { label: 'Glossaries', to: '/glossaries' },
    note: 'This is not required to build a glossary from generated candidates. Use it later when active source terms need cleanup or audit.',
  },
  {
    title: 'Optional glossary translation review',
    owner: 'Human or automation',
    actor: 'Vendor/advisor, PM/admin, or later automation',
    input: 'Existing glossary terms with target-language translations.',
    output: 'Reviewed target terminology translations.',
    where: { label: 'Glossaries', to: '/glossaries' },
    note: 'This is a separate translation-quality lane. It is only needed when the glossary has target translations to validate.',
  },
];

function ownerClassName(owner: WorkflowOwner) {
  if (owner === 'Automation') {
    return 'is-automation';
  }
  if (owner === 'Human') {
    return 'is-human';
  }
  return 'is-mixed';
}

export function AdminGlossaryWorkflowPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
        context="Settings > Glossaries"
        title="Glossary Workflow"
        centerContent={<TermIndexSubnav active="workflow" />}
      />
      <div className="settings-page settings-page--wide glossary-workflow-page">
        <section className="settings-card glossary-workflow-page__summary">
          <div className="settings-card__header">
            <h2>Build path</h2>
          </div>
          <div className="glossary-workflow-page__summary-grid">
            <div>
              <span className="glossary-workflow-page__summary-label">Automated</span>
              <strong>Extract, triage raw terms, generate candidates</strong>
              <p>
                Automation creates signals and proposals. It does not make generated candidates
                active in a glossary.
              </p>
            </div>
            <div>
              <span className="glossary-workflow-page__summary-label">Human setup</span>
              <strong>Choose glossary and assign review</strong>
              <p>
                PM/admin users choose the target glossary and review team. That is routing, not
                linguistic review.
              </p>
            </div>
            <div>
              <span className="glossary-workflow-page__summary-label">One human review</span>
              <strong>Candidate review project, then promotion</strong>
              <p>
                Vendors review candidate quality once. PM/admin resolution should be a batch
                promotion gate, not another full review.
              </p>
            </div>
          </div>
        </section>

        <section className="settings-card glossary-workflow-page__review-layers">
          <div className="settings-card__header">
            <h2>Human review layers</h2>
          </div>
          <div className="glossary-workflow-page__layer-grid">
            <div>
              <span className="glossary-workflow-page__layer-status is-required">Required</span>
              <strong>Candidate proposal review</strong>
              <p>
                Decides whether generated source terms should become glossary entries. This is the
                normal vendor/advisor review for glossary build.
              </p>
            </div>
            <div>
              <span className="glossary-workflow-page__layer-status">Optional</span>
              <strong>Active source-term review</strong>
              <p>
                Cleans up source glossary terms after promotion or import. This should not be part
                of every generated-candidate intake.
              </p>
            </div>
            <div>
              <span className="glossary-workflow-page__layer-status">Optional</span>
              <strong>Glossary translation review</strong>
              <p>
                Validates target-language terminology translations. This is a separate lane after
                source terms exist.
              </p>
            </div>
          </div>
        </section>

        <section className="settings-card">
          <div className="settings-card__header">
            <h2>Step-by-step workflow</h2>
          </div>
          <div className="glossary-workflow-page__timeline">
            {workflowSteps.map((step, index) => (
              <article className="glossary-workflow-page__step" key={step.title}>
                <div className="glossary-workflow-page__step-index">{index + 1}</div>
                <div className="glossary-workflow-page__step-body">
                  <div className="glossary-workflow-page__step-heading">
                    <h3>{step.title}</h3>
                    <span className={`glossary-workflow-page__owner ${ownerClassName(step.owner)}`}>
                      {step.owner}
                    </span>
                  </div>
                  <dl className="glossary-workflow-page__facts">
                    <div>
                      <dt>Actor</dt>
                      <dd>{step.actor}</dd>
                    </div>
                    <div>
                      <dt>Input</dt>
                      <dd>{step.input}</dd>
                    </div>
                    <div>
                      <dt>Output</dt>
                      <dd>{step.output}</dd>
                    </div>
                    <div>
                      <dt>Where</dt>
                      <dd>
                        {step.where.to ? (
                          <Link to={step.where.to}>{step.where.label}</Link>
                        ) : (
                          step.where.label
                        )}
                      </dd>
                    </div>
                  </dl>
                  <p className="glossary-workflow-page__note">{step.note}</p>
                </div>
              </article>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
}
