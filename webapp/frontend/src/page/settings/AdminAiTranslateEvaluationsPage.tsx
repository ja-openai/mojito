import './settings-page.css';
import './admin-ai-translate-evaluations-page.css';

import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';

import {
  type ApiAiTranslateEvaluationExample,
  fetchAiTranslateEvaluations,
} from '../../api/ai-translate-evaluations';
import { SearchControl } from '../../components/SearchControl';
import { useUser } from '../../hooks/useUser';

type OutcomeFilter = 'all' | 'edited' | 'exact';

const EVALUATIONS_QUERY_KEY = ['ai-translate-evaluations'] as const;

export function AdminAiTranslateEvaluationsPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const [searchQuery, setSearchQuery] = useState('');
  const [outcomeFilter, setOutcomeFilter] = useState<OutcomeFilter>('edited');

  const reportQuery = useQuery({
    queryKey: EVALUATIONS_QUERY_KEY,
    queryFn: () => fetchAiTranslateEvaluations(),
    staleTime: 30_000,
    enabled: isAdmin,
  });

  const examples = useMemo(() => {
    const normalizedSearch = searchQuery.trim().toLowerCase();
    return (reportQuery.data?.examples ?? []).filter((example) => {
      if (outcomeFilter === 'edited' && example.exactAccepted) {
        return false;
      }
      if (outcomeFilter === 'exact' && !example.exactAccepted) {
        return false;
      }
      if (!normalizedSearch) {
        return true;
      }
      return evaluationSearchText(example).includes(normalizedSearch);
    });
  }, [outcomeFilter, reportQuery.data?.examples, searchQuery]);

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  const summary = reportQuery.data?.summary;
  const cohorts = reportQuery.data?.cohorts ?? [];

  return (
    <div className="settings-page settings-page--wide ai-evaluations-page">
      <section className="settings-card ai-evaluations-page__intro">
        <div className="settings-card__content">
          <h2>Learn from review edits</h2>
          <p className="settings-hint">
            These examples join an imported AI translation to the exact variant a human reviewed and
            accepted. Prompt changes create new fingerprints, so model and prompt cohorts can be
            compared without copying data into another eval system.
          </p>
          <p className="settings-hint">
            This page never changes a production prompt automatically. Use the evidence here, edit a
            locale prompt or source rule, and wait for enough reviewed examples before comparing the
            new cohort.
          </p>
        </div>
      </section>

      {reportQuery.isError ? (
        <section className="settings-card">
          <p className="ai-evaluations-page__empty">Could not load evaluation evidence.</p>
        </section>
      ) : reportQuery.isLoading || !summary ? (
        <section className="settings-card">
          <p className="ai-evaluations-page__empty">Loading review evidence…</p>
        </section>
      ) : (
        <>
          <section className="ai-evaluations-page__summary" aria-label="Evaluation summary">
            <SummaryCard label="Reviewed" value={String(summary.reviewedCount)} />
            <SummaryCard
              label="Accepted unchanged"
              value={formatPercent(summary.exactAcceptanceRate)}
              detail={`${summary.exactAcceptedCount} examples`}
            />
            <SummaryCard label="Edited" value={String(summary.editedCount)} />
            <SummaryCard
              label="Average edit distance"
              value={formatPercent(summary.averageNormalizedEditDistance)}
            />
          </section>

          <section className="settings-card">
            <div className="settings-card__header ai-evaluations-page__section-header">
              <div>
                <h2>Prompt cohorts</h2>
                <p className="settings-hint">
                  A cohort is prompt fingerprint + model + reasoning + verbosity + locale. These
                  metrics use the latest 500 reviewed examples; older lineage may show an unknown
                  prompt until new reviewed attempts accumulate.
                </p>
              </div>
              <Link
                className="settings-button settings-button--secondary"
                to="/settings/system/ai-translate/prompts?tab=locales"
              >
                Edit prompts
              </Link>
            </div>
            {cohorts.length === 0 ? (
              <p className="ai-evaluations-page__empty">No reviewed AI translations yet.</p>
            ) : (
              <div className="ai-evaluations-page__cohorts">
                <div className="ai-evaluations-page__cohort-row ai-evaluations-page__cohort-row--header">
                  <span>Prompt</span>
                  <span>Model and settings</span>
                  <span>Locale</span>
                  <span>Reviewed</span>
                  <span>Accepted unchanged</span>
                </div>
                {cohorts.map((cohort, index) => (
                  <div
                    className="ai-evaluations-page__cohort-row"
                    key={`${cohort.promptFingerprint ?? 'unknown'}-${cohort.model ?? 'unknown'}-${cohort.localeTag}-${index}`}
                  >
                    <code title={cohort.promptFingerprint ?? 'Unknown historical prompt'}>
                      {shortFingerprint(cohort.promptFingerprint)}
                    </code>
                    <span>
                      {cohort.model ?? 'Unknown model'} · {cohort.reasoningEffort ?? 'default'} ·{' '}
                      {cohort.textVerbosity ?? 'default'}
                    </span>
                    <span>{cohort.localeTag}</span>
                    <span>{cohort.summary.reviewedCount}</span>
                    <span>{formatPercent(cohort.summary.exactAcceptanceRate)}</span>
                  </div>
                ))}
              </div>
            )}
          </section>

          <section className="settings-card">
            <div className="settings-card__header">
              <div>
                <h2>Reviewed examples</h2>
                <p className="settings-hint">
                  Start with edited examples. Reviewer notes are evidence; an edit by itself does
                  not prove which prompt instruction should change.
                </p>
              </div>
            </div>
            <div className="ai-evaluations-page__toolbar">
              <SearchControl
                value={searchQuery}
                onChange={setSearchQuery}
                placeholder="Search source, target, locale, repository, or notes"
              />
              <label className="settings-field ai-evaluations-page__outcome-filter">
                <span className="settings-field__label">Outcome</span>
                <select
                  className="settings-input"
                  value={outcomeFilter}
                  onChange={(event) => setOutcomeFilter(event.target.value as OutcomeFilter)}
                >
                  <option value="edited">Edited</option>
                  <option value="exact">Accepted unchanged</option>
                  <option value="all">All</option>
                </select>
              </label>
            </div>
            <div className="ai-evaluations-page__example-count">
              {examples.length} {examples.length === 1 ? 'example' : 'examples'} shown
            </div>
            {examples.length === 0 ? (
              <p className="ai-evaluations-page__empty">No examples match these filters.</p>
            ) : (
              <div className="ai-evaluations-page__examples">
                {examples.map((example) => (
                  <EvaluationExample
                    key={`${example.attemptId}-${example.reviewProjectId}`}
                    example={example}
                  />
                ))}
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}

function SummaryCard({ label, value, detail }: { label: string; value: string; detail?: string }) {
  return (
    <div className="settings-card ai-evaluations-page__summary-card">
      <span className="ai-evaluations-page__summary-label">{label}</span>
      <strong>{value}</strong>
      {detail ? <span className="settings-hint">{detail}</span> : null}
    </div>
  );
}

function EvaluationExample({ example }: { example: ApiAiTranslateEvaluationExample }) {
  return (
    <details className="ai-evaluations-page__example" open={!example.exactAccepted}>
      <summary>
        <span
          className={`ai-evaluations-page__outcome${example.exactAccepted ? ' is-exact' : ' is-edited'}`}
        >
          {example.exactAccepted ? 'Accepted unchanged' : 'Edited'}
        </span>
        <span>{example.localeTag}</span>
        <span>{example.repositoryName}</span>
        <span className="ai-evaluations-page__source-preview">
          {example.source || '(empty source)'}
        </span>
        <span>{formatDateTime(example.reviewedAt)}</span>
      </summary>
      <div className="ai-evaluations-page__example-body">
        <div className="ai-evaluations-page__example-meta">
          <span>{example.model ?? 'Unknown model'}</span>
          <span>Prompt {shortFingerprint(example.promptFingerprint)}</span>
          <span>
            Edit distance{' '}
            {example.normalizedEditDistance == null
              ? 'not calculated'
              : formatPercent(example.normalizedEditDistance)}
          </span>
          <Link to={`/text-units/${example.tmTextUnitId}`}>Open text unit</Link>
          <Link to={`/review-projects/${example.reviewProjectId}`}>Open review project</Link>
        </div>
        <EvidenceField label="Source" value={example.source} />
        {example.sourceDescription ? (
          <EvidenceField label="Source description" value={example.sourceDescription} />
        ) : null}
        <div className="ai-evaluations-page__target-grid">
          <EvidenceField label="AI translation" value={example.aiTarget} />
          <EvidenceField label="Accepted translation" value={example.acceptedTarget} />
        </div>
        {example.decisionNotes ? (
          <EvidenceField label="Reviewer notes" value={example.decisionNotes} />
        ) : null}
      </div>
    </details>
  );
}

function EvidenceField({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="ai-evaluations-page__field">
      <span>{label}</span>
      <div>{value ?? '(empty)'}</div>
    </div>
  );
}

function evaluationSearchText(example: ApiAiTranslateEvaluationExample) {
  return [
    example.source,
    example.sourceDescription,
    example.aiTarget,
    example.acceptedTarget,
    example.decisionNotes,
    example.localeTag,
    example.repositoryName,
    example.model,
    example.promptFingerprint,
  ]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();
}

function shortFingerprint(fingerprint: string | null) {
  return fingerprint ? fingerprint.slice(0, 10) : 'unknown';
}

function formatPercent(value: number) {
  return `${Math.round(value * 100)}%`;
}

function formatDateTime(value: string | null) {
  if (!value) {
    return 'Unknown date';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}
