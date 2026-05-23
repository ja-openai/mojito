import './glossary-matches-panel.css';

import { Link } from 'react-router-dom';

import type { ApiMatchedGlossaryTerm } from '../api/glossaries';
import { resolveAttachmentUrl } from '../utils/request-attachments';

type Props = {
  matches: ApiMatchedGlossaryTerm[];
  isLoading?: boolean;
  errorMessage?: string | null;
  emptyMessage?: string;
  currentTarget?: string | null;
  showHeader?: boolean;
};

export function GlossaryMatchesPanel({
  matches,
  isLoading = false,
  errorMessage = null,
  emptyMessage = 'No glossary terms matched this source string.',
  currentTarget = null,
  showHeader = true,
}: Props) {
  const normalizeForContains = (value: string, caseSensitive: boolean) =>
    caseSensitive ? value : value.toLocaleLowerCase();

  const getComplianceMessage = (match: ApiMatchedGlossaryTerm) => {
    const targetText = currentTarget?.trim();
    if (!targetText) {
      return null;
    }

    if (match.doNotTranslate) {
      const expectedSource = match.source.trim();
      if (!expectedSource) {
        return null;
      }

      return normalizeForContains(targetText, match.caseSensitive).includes(
        normalizeForContains(expectedSource, match.caseSensitive),
      )
        ? null
        : 'Current target does not preserve this do-not-translate term.';
    }

    const requiredTarget = match.target?.trim();
    if (!requiredTarget) {
      return null;
    }

    const obviousTargetCheck =
      match.matchType === 'EXACT' || match.matchType === 'CASE_INSENSITIVE';

    if (!obviousTargetCheck) {
      return null;
    }

    return normalizeForContains(targetText, match.caseSensitive).includes(
      normalizeForContains(requiredTarget, match.caseSensitive),
    )
      ? null
      : 'Current target does not contain the required glossary translation.';
  };

  const formatEvidenceSummary = (match: ApiMatchedGlossaryTerm) => {
    if (match.evidence.length === 0) {
      return null;
    }

    const screenshotCount = match.evidence.filter((evidence) => evidence.imageKey).length;
    const noteCount = match.evidence.filter(
      (evidence) => !evidence.imageKey && evidence.caption,
    ).length;
    const parts: string[] = [];

    if (screenshotCount > 0) {
      parts.push(`${screenshotCount} screenshot${screenshotCount === 1 ? '' : 's'}`);
    }

    if (noteCount > 0) {
      parts.push(`${noteCount} note${noteCount === 1 ? '' : 's'}`);
    }

    return parts.length > 0 ? parts.join(' · ') : null;
  };

  return (
    <section className="glossary-match-panel">
      {showHeader ? (
        <div className="glossary-match-panel__header">
          <div className="glossary-match-panel__title">Glossary</div>
          {isLoading ? <div className="glossary-match-panel__summary">Loading…</div> : null}
        </div>
      ) : null}

      {errorMessage ? (
        <div className="glossary-match-panel__state glossary-match-panel__state--error">
          {errorMessage}
        </div>
      ) : isLoading ? (
        <div className="glossary-match-panel__state">
          <span className="spinner spinner--md" aria-hidden />
          <span>Loading glossary…</span>
        </div>
      ) : matches.length === 0 ? (
        <div className="glossary-match-panel__state">{emptyMessage}</div>
      ) : (
        <div className="glossary-match-panel__list">
          {matches.map((match) => {
            const evidenceSummary = formatEvidenceSummary(match);
            const complianceMessage = getComplianceMessage(match);
            const imageEvidence = match.evidence.filter(
              (evidence): evidence is typeof evidence & { imageKey: string } =>
                Boolean(evidence.imageKey),
            );
            const requiredTarget = match.doNotTranslate
              ? 'Do not translate'
              : match.target || 'No target translation yet';
            const requiredTargetClassName = match.doNotTranslate
              ? 'glossary-match-panel__pair-value glossary-match-panel__pair-value--muted'
              : 'glossary-match-panel__pair-value';
            const note = match.targetComment || match.comment || match.definition || null;
            const hasDetails = Boolean(complianceMessage || imageEvidence.length);

            return (
              <article
                key={`${match.tmTextUnitId}:${match.startIndex}:${match.endIndex}:${match.target ?? ''}`}
                className="glossary-match-panel__item"
              >
                <div className="glossary-match-panel__pair">
                  <div className="glossary-match-panel__pair-value">
                    {match.glossaryId ? (
                      <Link
                        className="glossary-match-panel__term-link"
                        to={`/glossaries/${match.glossaryId}?termId=${match.tmTextUnitId}`}
                      >
                        {match.source}
                      </Link>
                    ) : (
                      <span>{match.source}</span>
                    )}
                  </div>
                  <div className={requiredTargetClassName}>{requiredTarget}</div>
                </div>

                {note ? <div className="glossary-match-panel__note">{note}</div> : null}

                {hasDetails ? (
                  <details className="glossary-match-panel__details">
                    <summary>Details</summary>

                    {complianceMessage ? (
                      <div className="glossary-match-panel__compliance-note">
                        {complianceMessage}
                      </div>
                    ) : null}

                    {evidenceSummary ? (
                      <div className="glossary-match-panel__meta">
                        <span>{evidenceSummary}</span>
                      </div>
                    ) : null}

                    {imageEvidence.length > 0 ? (
                      <div className="glossary-match-panel__evidence">
                        {imageEvidence.map((evidence, index) => (
                          <a
                            key={`${match.tmTextUnitId}:${evidence.imageKey}:${index}`}
                            className="glossary-match-panel__evidence-thumb"
                            href={resolveAttachmentUrl(evidence.imageKey)}
                            target="_blank"
                            rel="noreferrer"
                          >
                            <img
                              src={resolveAttachmentUrl(evidence.imageKey)}
                              alt={evidence.caption || 'Evidence'}
                            />
                          </a>
                        ))}
                      </div>
                    ) : null}
                  </details>
                ) : null}
              </article>
            );
          })}
        </div>
      )}
    </section>
  );
}
