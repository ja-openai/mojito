import './glossary-matches-panel.css';

import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';

import type { ApiMatchedGlossaryTerm } from '../api/glossaries';
import { getGlossaryTermScreenshotEvidence } from '../utils/glossaryTermEvidence';
import { resolveAttachmentUrl } from '../utils/request-attachments';
import { Modal } from './Modal';

type Props = {
  matches: ApiMatchedGlossaryTerm[];
  isLoading?: boolean;
  errorMessage?: string | null;
  emptyMessage?: string;
  currentTarget?: string | null;
  showHeader?: boolean;
};

const getGlossaryMatchKey = (match: ApiMatchedGlossaryTerm) =>
  [
    match.glossaryId ?? 'none',
    match.tmTextUnitId,
    match.startIndex,
    match.endIndex,
    match.matchedText,
  ].join(':');

const getGlossaryTermHref = (match: ApiMatchedGlossaryTerm) =>
  match.glossaryId == null ? null : `/glossaries/${match.glossaryId}/terms/${match.tmTextUnitId}`;

export function GlossaryMatchesPanel({
  matches,
  isLoading = false,
  errorMessage = null,
  emptyMessage = 'No glossary terms matched this source string.',
  currentTarget = null,
  showHeader = true,
}: Props) {
  const [selectedMatch, setSelectedMatch] = useState<ApiMatchedGlossaryTerm | null>(null);
  const selectedMatchKey = selectedMatch ? getGlossaryMatchKey(selectedMatch) : null;

  useEffect(() => {
    if (selectedMatchKey == null) {
      return;
    }

    const updatedMatch = matches.find((match) => getGlossaryMatchKey(match) === selectedMatchKey);
    setSelectedMatch(updatedMatch ?? null);
  }, [matches, selectedMatchKey]);

  const normalizeForContains = (value: string, caseSensitive: boolean) =>
    caseSensitive ? value : value.toLocaleLowerCase();

  const getRequiredTarget = (match: ApiMatchedGlossaryTerm) =>
    match.doNotTranslate ? 'Do not translate' : match.target || 'No target translation yet';

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

  const selectedMatchComplianceMessage = selectedMatch ? getComplianceMessage(selectedMatch) : null;

  return (
    <>
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
              const requiredTarget = getRequiredTarget(match);
              const requiredTargetClassName = match.doNotTranslate
                ? 'glossary-match-panel__pair-value glossary-match-panel__pair-value--muted'
                : 'glossary-match-panel__pair-value';

              return (
                <article key={getGlossaryMatchKey(match)} className="glossary-match-panel__item">
                  <div className="glossary-match-panel__pair">
                    <div className="glossary-match-panel__pair-value">{match.source}</div>
                    <div className={requiredTargetClassName}>{requiredTarget}</div>
                  </div>
                  <button
                    type="button"
                    className="glossary-match-panel__details-button"
                    aria-label={`Details for ${match.source || 'glossary term'}`}
                    onClick={() => setSelectedMatch(match)}
                  >
                    Details
                  </button>
                </article>
              );
            })}
          </div>
        )}
      </section>

      <GlossaryMatchDetailsModal
        match={selectedMatch}
        complianceMessage={selectedMatchComplianceMessage}
        evidenceSummary={selectedMatch ? formatEvidenceSummary(selectedMatch) : null}
        requiredTarget={selectedMatch ? getRequiredTarget(selectedMatch) : null}
        onClose={() => setSelectedMatch(null)}
      />
    </>
  );
}

function GlossaryMatchDetailsModal({
  match,
  complianceMessage,
  evidenceSummary,
  requiredTarget,
  onClose,
}: {
  match: ApiMatchedGlossaryTerm | null;
  complianceMessage: string | null;
  evidenceSummary: string | null;
  requiredTarget: string | null;
  onClose: () => void;
}) {
  const imageEvidence = getGlossaryTermScreenshotEvidence(match?.evidence);
  const noteEvidence =
    match?.evidence.filter((evidence) => !evidence.imageKey && evidence.caption) ?? [];
  const termHref = match ? getGlossaryTermHref(match) : null;
  const modalAriaLabel = match?.source
    ? `Glossary term details for ${match.source}`
    : 'Glossary term details';
  const termDescription = match?.definition?.trim() || null;
  const sourceNote = match?.comment?.trim() || null;
  const shouldShowSourceNote = sourceNote && sourceNote !== termDescription;
  const termDetailRows = match
    ? [
        { label: 'Glossary name', value: match.glossaryName },
        { label: 'Term description', value: termDescription },
        { label: 'Source note', value: shouldShowSourceNote ? sourceNote : null },
        { label: 'Translation note', value: match.targetComment },
        { label: 'Part of speech', value: match.partOfSpeech },
        { label: 'Term type', value: match.termType },
        { label: 'Enforcement', value: match.enforcement },
        { label: 'Status', value: match.status },
        { label: 'Provenance', value: match.provenance },
      ].filter((row) => row.value && row.value.trim())
    : [];
  const matchDetailRows = match
    ? [
        { label: 'Term ID', value: match.termKey },
        { label: 'Matched source text', value: match.matchedText },
        { label: 'Match type', value: match.matchType },
        { label: 'Evidence summary', value: evidenceSummary },
      ].filter((row) => row.value && row.value.trim())
    : [];

  return (
    <Modal
      open={match != null}
      size="xl"
      ariaLabel={modalAriaLabel}
      closeOnBackdrop
      onClose={onClose}
      className="glossary-match-panel__modal"
    >
      {match ? (
        <>
          <div className="modal__header glossary-match-panel__modal-header">
            <div>
              <h2 className="modal__title glossary-match-panel__modal-title">
                Glossary term details
              </h2>
              <dl
                className="glossary-match-panel__modal-summary"
                aria-label="Selected glossary term"
              >
                <div>
                  <dt>Source term</dt>
                  <dd>{match.source}</dd>
                </div>
                <div>
                  <dt>Glossary translation</dt>
                  <dd>{requiredTarget}</dd>
                </div>
              </dl>
            </div>
            <div className="glossary-match-panel__modal-actions">
              {termHref ? (
                <Link className="modal__button" to={termHref} target="_blank" rel="noreferrer">
                  Open term
                </Link>
              ) : null}
              <button
                type="button"
                className="modal__button modal__button--primary"
                onClick={onClose}
              >
                Close
              </button>
            </div>
          </div>

          <div className="glossary-match-panel__modal-body">
            {complianceMessage ? (
              <div className="glossary-match-panel__compliance-note">{complianceMessage}</div>
            ) : null}

            {termDetailRows.length > 0 ? (
              <section className="glossary-match-panel__modal-section">
                <h3>Term details</h3>
                <dl className="glossary-match-panel__detail-list">
                  {termDetailRows.map((row) => (
                    <div key={row.label}>
                      <dt>{row.label}</dt>
                      <dd>{row.value}</dd>
                    </div>
                  ))}
                </dl>
              </section>
            ) : null}

            {matchDetailRows.length > 0 ? (
              <details className="glossary-match-panel__modal-section glossary-match-panel__technical-details">
                <summary>Technical match details</summary>
                <dl className="glossary-match-panel__detail-list">
                  {matchDetailRows.map((row) => (
                    <div key={row.label}>
                      <dt>{row.label}</dt>
                      <dd>{row.value}</dd>
                    </div>
                  ))}
                </dl>
              </details>
            ) : null}

            <section className="glossary-match-panel__modal-section glossary-match-panel__screenshots-section">
              <div className="glossary-match-panel__modal-section-header">
                <h3>Evidence screenshots</h3>
                {imageEvidence.length > 0 ? (
                  <span>
                    {imageEvidence.length} screenshot{imageEvidence.length === 1 ? '' : 's'}
                  </span>
                ) : null}
              </div>
              {imageEvidence.length > 0 ? (
                <div className="glossary-match-panel__evidence">
                  {imageEvidence.map((evidence, index) => (
                    <a
                      key={`${match.tmTextUnitId}:${evidence.imageKey}:${index}`}
                      className="glossary-match-panel__evidence-preview"
                      href={resolveAttachmentUrl(evidence.imageKey)}
                      target="_blank"
                      rel="noreferrer"
                      aria-label={`Open evidence screenshot${
                        evidence.caption ? `: ${evidence.caption}` : ''
                      }`}
                    >
                      <img
                        src={resolveAttachmentUrl(evidence.imageKey)}
                        alt={evidence.caption || 'Evidence screenshot'}
                      />
                      {evidence.caption ? (
                        <span className="glossary-match-panel__evidence-caption">
                          {evidence.caption}
                        </span>
                      ) : null}
                    </a>
                  ))}
                </div>
              ) : (
                <p className="glossary-match-panel__empty-evidence">
                  No screenshots attached yet
                  {termHref ? '; open the term to add screenshot evidence.' : '.'}
                </p>
              )}
            </section>

            {noteEvidence.length > 0 ? (
              <section className="glossary-match-panel__modal-section">
                <h3>Evidence notes</h3>
                <ul className="glossary-match-panel__evidence-notes">
                  {noteEvidence.map((evidence, index) => (
                    <li key={`${match.tmTextUnitId}:note:${index}`}>{evidence.caption}</li>
                  ))}
                </ul>
              </section>
            ) : null}
          </div>
        </>
      ) : null}
    </Modal>
  );
}
