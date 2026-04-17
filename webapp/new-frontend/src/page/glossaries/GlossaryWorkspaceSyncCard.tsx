import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';

import {
  type ApiGlossaryDetail,
  fetchGlossaryTranslationProposals,
  fetchGlossaryWorkspaceSummary,
} from '../../api/glossaries';
import { useUser } from '../../components/RequireUser';
import { canManageGlossaryTerms } from '../../utils/permissions';

type GlossaryWorkspaceSyncCardProps = {
  glossary: ApiGlossaryDetail;
};

type LaneTone = 'ready' | 'next' | 'idea';

function getCoverageLabel(glossary: ApiGlossaryDetail) {
  if (glossary.scopeMode === 'GLOBAL') {
    return `Global scope across ${glossary.excludedRepositories.length.toLocaleString()} exclusions`;
  }
  return `${glossary.repositories.length.toLocaleString()} linked repositories`;
}

function getReadinessTone(count: number): LaneTone {
  if (count > 0) {
    return 'ready';
  }
  return 'next';
}

export function GlossaryWorkspaceSyncCard({ glossary }: GlossaryWorkspaceSyncCardProps) {
  const user = useUser();
  const canManage = canManageGlossaryTerms(user);

  const summaryQuery = useQuery({
    queryKey: ['glossary-workspace-summary', glossary.id],
    queryFn: () => fetchGlossaryWorkspaceSummary(glossary.id),
    staleTime: 30_000,
  });

  const proposalsQuery = useQuery({
    queryKey: ['glossary-proposals', glossary.id, 'summary-card'],
    queryFn: () => fetchGlossaryTranslationProposals(glossary.id, { status: 'PENDING', limit: 1 }),
    enabled: canManage,
    staleTime: 30_000,
  });

  return (
    <section className="settings-card glossary-sync-card">
      <div className="settings-card__header">
        <div>
          <div className="settings-hint">Prototype</div>
          <h2>Workspace Readiness</h2>
        </div>
        {canManage ? (
          <Link
            to={`/glossaries/${glossary.id}/settings`}
            className="settings-button settings-button--ghost"
          >
            Glossary settings
          </Link>
        ) : null}
      </div>

      {summaryQuery.isLoading ? (
        <p className="settings-hint">Loading publish-readiness summary…</p>
      ) : summaryQuery.isError || !summaryQuery.data ? (
        <p className="settings-hint is-error">
          {summaryQuery.error instanceof Error
            ? summaryQuery.error.message
            : 'Could not load glossary publish-readiness summary.'}
        </p>
      ) : (
        <>
          <p className="settings-hint glossary-sync-card__lead">
            This previews Mojito’s next step for glossary work: use the workspace as the operational
            layer around a visible backing repository, explicit readiness rules, and reviewable
            proposal flows.
          </p>

          <div className="glossary-sync-card__stats">
            <div className="glossary-sync-card__stat">
              <span className="glossary-sync-card__stat-value">
                {summaryQuery.data.publishReadyTermCount.toLocaleString()}
              </span>
              <span className="glossary-sync-card__stat-label">ready terms</span>
            </div>
            <div className="glossary-sync-card__stat">
              <span className="glossary-sync-card__stat-value">
                {summaryQuery.data.approvedTermCount.toLocaleString()}
              </span>
              <span className="glossary-sync-card__stat-label">approved terms</span>
            </div>
            <div className="glossary-sync-card__stat">
              <span className="glossary-sync-card__stat-value">
                {summaryQuery.data.termsWithEvidenceCount.toLocaleString()}
              </span>
              <span className="glossary-sync-card__stat-label">terms with evidence</span>
            </div>
            <div className="glossary-sync-card__stat">
              <span className="glossary-sync-card__stat-value">
                {summaryQuery.data.termsMissingAnyTranslationCount.toLocaleString()}
              </span>
              <span className="glossary-sync-card__stat-label">terms missing locale coverage</span>
            </div>
            {canManage ? (
              <div className="glossary-sync-card__stat">
                <span className="glossary-sync-card__stat-value">
                  {(proposalsQuery.data?.totalCount ?? 0).toLocaleString()}
                </span>
                <span className="glossary-sync-card__stat-label">pending proposals</span>
              </div>
            ) : null}
          </div>

          <div className="glossary-sync-card__meta">
            <span>{getCoverageLabel(glossary)}</span>
            <span>{glossary.localeTags.length.toLocaleString()} target locales</span>
            <span>{summaryQuery.data.totalTerms.toLocaleString()} total terms</span>
          </div>

          <div className="glossary-sync-card__lanes">
            <article
              className={`glossary-sync-card__lane glossary-sync-card__lane--${getReadinessTone(
                summaryQuery.data.approvedTermCount,
              )}`}
            >
              <div className="glossary-sync-card__lane-label">Internal AI guidance</div>
              <h3>Already backed by Mojito glossary matching</h3>
              <p className="settings-hint">
                Approved terms can already influence AI Translate and AI review flows through the
                glossary matcher and matched-term side panels.
              </p>
            </article>

            <article
              className={`glossary-sync-card__lane glossary-sync-card__lane--${getReadinessTone(
                summaryQuery.data.publishReadyTermCount,
              )}`}
            >
              <div className="glossary-sync-card__lane-label">Readiness policy</div>
              <h3>Approval, coverage, and evidence should gate broader usage</h3>
              <p className="settings-hint">
                The glossary workspace already has the raw signals. The missing piece is to make
                readiness explicit with blocker reasons, diffs, and operator-facing audit
                visibility.
              </p>
            </article>

            <article className="glossary-sync-card__lane glossary-sync-card__lane--idea">
              <div className="glossary-sync-card__lane-label">Proposal loop</div>
              <h3>Suggestions stay explicit instead of silently mutating terms</h3>
              <p className="settings-hint">
                The next differentiator is to keep glossary corrections and suggestions as
                attributed proposals with evidence, so glossary changes stay reviewable and
                auditable.
              </p>
            </article>
          </div>

          {summaryQuery.data.truncated ? (
            <p className="settings-hint">
              Summary is partial because the prototype scan limit was reached. Large glossaries
              still need a dedicated aggregated summary path.
            </p>
          ) : null}
        </>
      )}
    </section>
  );
}
