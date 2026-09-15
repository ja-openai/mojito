import { useQuery } from '@tanstack/react-query';

import { fetchReviewFeedbackPatterns } from '../../api/review-feedback';

export function ReviewFeedbackPatternsPanel() {
  const report = useQuery({
    queryKey: ['review-feedback-patterns'],
    queryFn: fetchReviewFeedbackPatterns,
    staleTime: 60_000,
  });
  return (
    <section className="settings-card" aria-label="Repeated review edits">
      <div className="settings-card__content">
        <h3>Repeated review edits</h3>
        <p className="settings-hint">
          Automatic observations from the latest {report.data?.windowSize ?? 0} AI review events.
          Counts use distinct strings and reviewers. Candidates require human review and never
          update prompts automatically.
        </p>
        {report.isError ? <p>Could not load repeated edit patterns.</p> : null}
        {report.isLoading ? <p>Loading patterns…</p> : null}
        {report.data?.patterns.length === 0 ? <p>No repeated edit evidence yet.</p> : null}
        {report.data?.patterns.slice(0, 25).map((pattern) => (
          <details
            key={`${pattern.projectId}:${pattern.repositoryId ?? 'unknown'}:${pattern.locale}:${pattern.model}:${pattern.promptVersion}:${pattern.transformHash}`}
          >
            <summary>
              {pattern.locale} · {pattern.category.replace(/_/g, ' ').toLowerCase()} ·{' '}
              {pattern.distinctStrings} strings · {pattern.reviewerCount} reviewers
              {pattern.candidateStatus === 'READY_FOR_HUMAN_REVIEW'
                ? ' · Ready for human review'
                : ''}
            </summary>
            <p className="settings-hint">
              {pattern.projectId == null
                ? `Workbench · Repository ${pattern.repositoryId ?? 'unknown'}`
                : `Project ${pattern.projectId}`}{' '}
              · Model {pattern.model} · Prompt {pattern.promptVersion}
              {' · '}
              {Math.round(pattern.correctionRate * 100)}% of {pattern.opportunities} reviewed
              strings · {pattern.disputedStrings} disputed strings
            </p>
            {pattern.examples.map((example) => (
              <dl key={example.eventId}>
                <dt>Source</dt>
                <dd>{example.source}</dd>
                <dt>AI translation</dt>
                <dd>{example.baseline}</dd>
                <dt>Accepted translation</dt>
                <dd>{example.accepted}</dd>
              </dl>
            ))}
          </details>
        ))}
      </div>
    </section>
  );
}
