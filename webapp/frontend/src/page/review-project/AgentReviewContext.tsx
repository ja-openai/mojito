import './agent-review.css';

import { useQuery } from '@tanstack/react-query';
import { useId } from 'react';
import { Link } from 'react-router-dom';

import {
  AGENT_REVIEW_FEEDBACK_QUERY_KEY,
  type ApiAgentReviewContext,
  fetchAgentReviewFeedback,
} from '../../api/agent-reviews';
import { formatLocalDateTime } from '../../utils/dateTime';
import { toHtmlLangTag } from '../../utils/localeTag';
import type { AgentReviewFeedbackValues } from './agent-review-feedback';

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
  SUSPECTED: 'Suspected issue; not independently verified',
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
  HUMAN_REVIEW: 'Human review',
  REVIEW_AGAIN: 'Started another review',
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
          <li key={index} className="agent-review-context__evidence">
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

type AgentReviewContextProps = {
  projectId: number;
  proposal: ApiAgentReviewContext;
  localeTag: string;
  view: 'details' | 'history';
  onReconsider?: () => void;
};

export function AgentReviewContext({ view, ...props }: AgentReviewContextProps) {
  if (view === 'history') return <AgentReviewHistory {...props} />;
  const { proposal } = props;
  return (
    <section className="agent-review-context" aria-label="Evidence and verification">
      <h3>Review reason</h3>
      <p>{proposal.rationale}</p>
      <h3>Evidence and verification</h3>
      <p>{label(proposal.category)}</p>
      <p>
        {proposal.verificationStatus === 'HUMAN_REVIEW'
          ? 'Requested for human review. No agent verification is claimed.'
          : `${label(proposal.verificationStatus)}. The human reviewer decides.`}
      </p>
      {proposal.verificationNotes ? <p>{proposal.verificationNotes}</p> : null}
      {proposal.evidence?.length ? (
        <EvidenceList entries={proposal.evidence} />
      ) : (
        <p>No additional evidence attached.</p>
      )}
      {proposal.integrityDiagnostics ? (
        <div className="agent-review-context__notice">
          <strong>Translation validation</strong>
          <p>{proposal.integrityDiagnostics}</p>
        </div>
      ) : null}
      <p className="agent-review-context__provenance">
        {label(proposal.reviewType)} · Run #{proposal.runId} · Finding #{proposal.findingId} ·
        Revision {proposal.proposalRevision}
      </p>
    </section>
  );
}

function AgentReviewHistory({
  projectId,
  proposal,
  localeTag,
  onReconsider,
}: Omit<AgentReviewContextProps, 'view'>) {
  const feedback = useQuery({
    queryKey: [
      AGENT_REVIEW_FEEDBACK_QUERY_KEY,
      projectId,
      proposal.proposalId,
      proposal.proposalVersion,
    ],
    queryFn: ({ signal }) => fetchAgentReviewFeedback(projectId, proposal.proposalId, signal),
    staleTime: 30_000,
  });
  return (
    <section className="agent-review-context" aria-label="Feedback and agent responses">
      <h3>Review history</h3>
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
        <ol className="agent-review-context__history">
          {feedback.data.map((entry) => (
            <li key={entry.id}>
              <strong>{label(entry.action)}</strong>
              <span className="agent-review-context__provenance">
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
                <details className="agent-review-context__details">
                  <summary>Response evidence</summary>
                  <EvidenceList entries={readEvidence(entry.evidenceJson)} />
                </details>
              ) : null}
              {entry.finalTarget !== null && entry.finalTarget !== undefined ? (
                <div>
                  <span className="agent-review-context__label">Saved translation</span>
                  <div
                    className="agent-review-context__translation"
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
      {proposal.canReconsider && onReconsider ? (
        <div className="agent-review-context__notice">
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
      {proposal.nextReviewProjectId ? (
        <p>
          <Link to={`/review-projects/${proposal.nextReviewProjectId}`}>Open new review</Link>
        </p>
      ) : null}
    </section>
  );
}

export function AgentReviewFeedbackDetails({
  values,
  onChange,
  disabled,
  showSuggestion = true,
}: {
  values: AgentReviewFeedbackValues;
  onChange: (values: AgentReviewFeedbackValues) => void;
  disabled: boolean;
  showSuggestion?: boolean;
}) {
  const id = useId();
  return (
    <details className="agent-review-context__details" data-agent-review-feedback>
      <summary>Review feedback</summary>
      <div className="agent-review-feedback">
        <div className="review-project-detail__field">
          <label className="review-project-detail__label" htmlFor={`${id}-original`}>
            Original translation
          </label>
          <select
            id={`${id}-original`}
            className="review-project-detail__input review-project-detail__input--compact"
            value={values.originalAssessment}
            disabled={disabled}
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
        </div>
        {showSuggestion ? (
          <div className="review-project-detail__field">
            <label className="review-project-detail__label" htmlFor={`${id}-suggestion`}>
              Suggested translation
            </label>
            <select
              id={`${id}-suggestion`}
              className="review-project-detail__input review-project-detail__input--compact"
              value={values.suggestionAssessment}
              disabled={disabled}
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
          </div>
        ) : null}
        <div className="review-project-detail__field">
          <label className="review-project-detail__label" htmlFor={`${id}-explanation`}>
            Explanation
          </label>
          <textarea
            id={`${id}-explanation`}
            className="review-project-detail__input review-project-detail__input--compact"
            value={values.explanation}
            disabled={disabled}
            rows={2}
            maxLength={4000}
            placeholder="Add a note…"
            onChange={(event) => onChange({ ...values, explanation: event.target.value })}
          />
        </div>
      </div>
    </details>
  );
}
