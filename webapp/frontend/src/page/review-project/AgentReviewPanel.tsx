import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import {
  AGENT_REVIEW_FEEDBACK_QUERY_KEY,
  type AgentReviewDecision,
  type ApiAgentReviewContext,
  fetchAgentReviewFeedback,
} from '../../api/agent-reviews';
import { formatLocalDateTime } from '../../utils/dateTime';
import { toHtmlLangTag } from '../../utils/localeTag';
import type { AgentReviewFeedbackValues } from './agent-review-feedback';
import { AgentReviewDiff } from './AgentReviewDiff';

const LABELS: Record<string, string> = {
  OBVIOUS_ERROR: 'Possible translation mistake',
  CONSISTENCY_ERROR: 'Consistency finding',
  OPTIONAL_IMPROVEMENT: 'Optional improvement',
  ROUTED: 'Awaiting human review',
  FOLLOW_UP: 'Waiting for an agent response',
  SUPERSEDED: 'Replaced by a newer proposal',
  EDIT_ACCEPT: 'Edited and accepted',
  REVISED_PROPOSAL: 'Revised proposal',
  CONTEXT_REQUEST: 'Requested more context',
  READY: 'Ready for human review',
  HOLD: 'Needs more context',
  OPTIONAL: 'Optional improvement',
  BAD: 'Has an issue',
  INSUFFICIENT_CONTEXT: 'Insufficient context',
  OPEN: 'Awaiting human review',
  RESOLVED: 'Resolved',
  KEEP_CURRENT: 'Kept current translation',
  DEFER: 'Deferred',
  REQUEST_REVISION: 'Requested another proposal',
  ACCEPT: 'Accepted translation',
  CHALLENGE: 'Asked for reconsideration',
  UNSURE: 'Unsure',
  GOOD: 'Useful',
  INCORRECT: 'Incorrect',
  UNNECESSARY: 'Unnecessary',
};

function label(value: string) {
  return LABELS[value] ?? value.replace(/_/g, ' ').toLowerCase();
}

function safeEvidenceUrl(value: string | null | undefined) {
  if (!value) return null;
  if (
    /^\/api\/agent-reviews\/projects\/[1-9]\d*\/proposals\/[1-9]\d*\/artifacts\/[a-fA-F0-9]{64}$/.test(
      value,
    )
  )
    return value;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : null;
  } catch {
    return null;
  }
}

type Evidence = { label: string; url?: string | null };

function readEvidence(json: string): Evidence[] {
  let value: unknown;
  try {
    value = JSON.parse(json);
  } catch {
    return [{ label: json }];
  }
  return (Array.isArray(value) ? (value as unknown[]) : [value]).map((entry): Evidence => {
    if (typeof entry === 'string') return { label: entry };
    if (entry && typeof entry === 'object' && 'label' in entry && typeof entry.label === 'string') {
      return {
        label: entry.label,
        url: 'url' in entry && typeof entry.url === 'string' ? entry.url : null,
      };
    }
    return { label: JSON.stringify(entry, null, 2) ?? '' };
  });
}

function EvidenceList({ entries }: { entries: Evidence[] }) {
  return (
    <ul>
      {entries.map((entry, index) => {
        const href = safeEvidenceUrl(entry.url);
        return (
          <li key={index} className="agent-review-panel__evidence">
            {href ? (
              <a href={href} target="_blank" rel="noopener noreferrer">
                {entry.label}
              </a>
            ) : (
              entry.label
            )}
          </li>
        );
      })}
    </ul>
  );
}

export function AgentReviewPanel({
  projectId,
  proposal,
  localeTag,
  currentSource,
  currentTarget,
  onReconsider,
}: {
  projectId: number;
  proposal: ApiAgentReviewContext;
  localeTag: string;
  currentSource: string | null;
  currentTarget: string | null;
  onReconsider?: () => void;
}) {
  const [historyOpen, setHistoryOpen] = useState(Boolean(proposal.canReconsider));
  const feedback = useQuery({
    queryKey: [
      AGENT_REVIEW_FEEDBACK_QUERY_KEY,
      projectId,
      proposal.proposalId,
      proposal.proposalVersion,
    ],
    queryFn: ({ signal }) => fetchAgentReviewFeedback(projectId, proposal.proposalId, signal),
    enabled: historyOpen || proposal.canReconsider === true,
    staleTime: 30_000,
  });
  return (
    <section className="agent-review-panel" aria-label="Agent finding">
      <div className="agent-review-panel__heading">
        <strong>{label(proposal.category)}</strong>
        <span className="agent-review-panel__status">
          {proposal.canReconsider ? 'Ready for reconsideration' : label(proposal.disposition)}
        </span>
      </div>
      <p className="agent-review-panel__reason">{proposal.rationale}</p>
      <AgentReviewDiff
        original={proposal.reviewedTarget}
        proposed={proposal.proposedTarget}
        localeTag={localeTag}
      />
      {proposal.stale ? (
        <div className="agent-review-panel__notice" role="status">
          <strong>The translation or source changed after this review.</strong>
          <p>Review the latest text and request a new proposal before accepting.</p>
          {currentSource !== proposal.reviewedSource ? (
            <>
              <span className="agent-review-diff__label">Source the agent reviewed</span>
              <div className="agent-review-diff__text" dir="auto">
                {proposal.reviewedSource}
              </div>
              <span className="agent-review-diff__label">Latest source</span>
              <div className="agent-review-diff__text" dir="auto">
                {currentSource ?? 'No source'}
              </div>
            </>
          ) : null}
          <span className="agent-review-diff__label">Latest current translation</span>
          <div className="agent-review-diff__text" dir="auto" lang={toHtmlLangTag(localeTag)}>
            {currentTarget ?? 'No translation'}
          </div>
        </div>
      ) : null}
      <details className="agent-review-panel__details">
        <summary>Evidence and verification</summary>
        <p>{label(proposal.verificationStatus)}. The human reviewer decides.</p>
        {proposal.verificationNotes ? <p>{proposal.verificationNotes}</p> : null}
        {proposal.evidence?.length ? (
          <EvidenceList entries={proposal.evidence} />
        ) : (
          <p>No additional evidence attached.</p>
        )}
        {proposal.integrityDiagnostics ? (
          <div className="agent-review-panel__notice">
            <strong>Translation validation</strong>
            <p>{proposal.integrityDiagnostics}</p>
          </div>
        ) : null}
        <p className="agent-review-panel__provenance">
          {label(proposal.reviewType)} · Run #{proposal.runId} · Finding #{proposal.findingId} ·
          Revision {proposal.proposalRevision}
        </p>
      </details>
      <details
        className="agent-review-panel__details"
        open={proposal.canReconsider ? true : undefined}
        onToggle={(event) => setHistoryOpen(event.currentTarget.open)}
      >
        <summary>Feedback and agent responses</summary>
        {feedback.isLoading ? <p role="status">Loading feedback…</p> : null}
        {feedback.isError ? (
          <p role="alert">
            Could not load feedback.{' '}
            <button type="button" onClick={() => void feedback.refetch()}>
              Retry
            </button>
          </p>
        ) : null}
        {feedback.data?.length === 0 ? <p>No feedback yet.</p> : null}
        {feedback.data?.length ? (
          <ol className="agent-review-panel__history">
            {feedback.data.map((entry) => (
              <li key={entry.id}>
                <strong>{label(entry.action)}</strong>
                <span className="agent-review-panel__provenance">
                  {entry.actorIdentity} · {formatLocalDateTime(entry.createdDate)} · Revision{' '}
                  {entry.proposalRevision}
                </span>
                {entry.originalAssessment ? (
                  <div>
                    Original:{' '}
                    {entry.originalAssessment === 'GOOD'
                      ? 'Acceptable'
                      : label(entry.originalAssessment)}
                  </div>
                ) : null}
                {entry.suggestionAssessment ? (
                  <div>Suggestion: {label(entry.suggestionAssessment)}</div>
                ) : null}
                {entry.explanation ? <p>{entry.explanation}</p> : null}
                {entry.evidenceJson ? (
                  <details className="agent-review-panel__details">
                    <summary>Response evidence</summary>
                    <EvidenceList entries={readEvidence(entry.evidenceJson)} />
                  </details>
                ) : null}
                {entry.finalTarget !== null && entry.finalTarget !== undefined ? (
                  <div>
                    <span className="agent-review-diff__label">Saved translation</span>
                    <div
                      className="agent-review-diff__text"
                      dir="auto"
                      lang={toHtmlLangTag(localeTag)}
                    >
                      {entry.finalTarget || 'Empty translation'}
                    </div>
                  </div>
                ) : null}
              </li>
            ))}
          </ol>
        ) : null}
      </details>
      {proposal.canReconsider && onReconsider ? (
        <div className="agent-review-panel__notice">
          <p>
            The agent responded to the previous feedback. Review its response before reconsidering
            this finding.
          </p>
          <button
            type="button"
            className="review-project-detail__actions-button"
            disabled={feedback.isLoading || feedback.isError || !feedback.data}
            onClick={onReconsider}
          >
            Reconsider finding
          </button>
        </div>
      ) : null}
    </section>
  );
}

export function AgentReviewFeedbackForm({
  values,
  onChange,
  onOutcome,
  onResetTranslation,
  disabled,
  hasUnsavedTranslation,
  pending,
  completed,
}: {
  values: AgentReviewFeedbackValues;
  onChange: (values: AgentReviewFeedbackValues) => void;
  onOutcome: (action: Exclude<AgentReviewDecision['action'], 'ACCEPT'>) => void;
  onResetTranslation: () => void;
  disabled: boolean;
  hasUnsavedTranslation: boolean;
  pending: boolean;
  completed: boolean;
}) {
  const [action, setAction] = useState<Exclude<AgentReviewDecision['action'], 'ACCEPT'> | null>(
    null,
  );
  return (
    <section className="agent-review-feedback" aria-label="Review feedback">
      <details className="agent-review-panel__details" open={action ? true : undefined}>
        <summary>
          Feedback for the agent <span className="agent-review-panel__provenance">(optional)</span>
        </summary>
        <div className="agent-review-feedback__fields">
          <label>
            Original translation
            <select
              value={values.originalAssessment}
              disabled={disabled || completed}
              onChange={(event) =>
                onChange({
                  ...values,
                  originalAssessment: event.target
                    .value as AgentReviewFeedbackValues['originalAssessment'],
                })
              }
            >
              <option value="">No assessment</option>
              <option value="GOOD">Acceptable</option>
              <option value="BAD">Has an issue</option>
              <option value="UNSURE">Unsure</option>
            </select>
          </label>
          <label>
            Agent suggestion
            <select
              value={values.suggestionAssessment}
              disabled={disabled || completed}
              onChange={(event) =>
                onChange({
                  ...values,
                  suggestionAssessment: event.target
                    .value as AgentReviewFeedbackValues['suggestionAssessment'],
                })
              }
            >
              <option value="">No assessment</option>
              <option value="GOOD">Useful</option>
              <option value="INCORRECT">Incorrect</option>
              <option value="UNNECESSARY">Unnecessary</option>
              <option value="INSUFFICIENT_CONTEXT">Insufficient context</option>
            </select>
          </label>
          <label className="agent-review-feedback__explanation">
            Explanation
            <textarea
              value={values.explanation}
              disabled={disabled || completed}
              rows={2}
              maxLength={10000}
              placeholder={
                values.suggestionAssessment === 'INCORRECT'
                  ? 'What should the agent fix or reconsider?'
                  : 'Add context that will help the next review.'
              }
              onChange={(event) => onChange({ ...values, explanation: event.target.value })}
            />
          </label>
        </div>
      </details>
      {!completed ? (
        <>
          <div className="agent-review-feedback__actions">
            <button
              type="button"
              disabled={disabled}
              aria-pressed={action === 'KEEP_CURRENT'}
              onClick={() => setAction('KEEP_CURRENT')}
            >
              Keep current
            </button>
            <button
              type="button"
              disabled={disabled}
              aria-pressed={action === 'REQUEST_REVISION'}
              onClick={() => setAction('REQUEST_REVISION')}
            >
              Ask for another proposal
            </button>
            <button
              type="button"
              disabled={disabled}
              aria-pressed={action === 'DEFER'}
              onClick={() => setAction('DEFER')}
            >
              Defer
            </button>
          </div>
          {action ? (
            <div className="agent-review-feedback__confirm">
              <p>
                {action === 'KEEP_CURRENT'
                  ? 'Resolve this finding and keep the current translation.'
                  : action === 'REQUEST_REVISION'
                    ? 'Return this finding to the agent on its next review run. The current translation stays as it is.'
                    : 'Leave this finding pending for later. The current translation stays as it is.'}
              </p>
              {hasUnsavedTranslation ? (
                <p>
                  Reset your translation edits before recording this outcome. Your feedback will be
                  kept.{' '}
                  <button
                    type="button"
                    className="review-project-detail__actions-button"
                    disabled={disabled}
                    onClick={onResetTranslation}
                  >
                    Reset translation edits
                  </button>
                </p>
              ) : null}
              <button
                type="button"
                className="review-project-detail__actions-button review-project-detail__actions-button--primary"
                disabled={disabled || hasUnsavedTranslation}
                onClick={() => onOutcome(action)}
              >
                {pending ? 'Saving…' : 'Save feedback'}
              </button>
              <button
                type="button"
                className="review-project-detail__actions-button"
                disabled={pending}
                onClick={() => setAction(null)}
              >
                Cancel
              </button>
            </div>
          ) : null}
        </>
      ) : null}
    </section>
  );
}
