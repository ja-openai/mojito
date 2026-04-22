import './glossary-term-metadata-panel.css';

import { Link } from 'react-router-dom';

import type { ApiGlossaryTerm } from '../api/glossaries';

type Props = {
  glossaryId: number;
  glossaryName: string;
  term: ApiGlossaryTerm | null;
  isLoading?: boolean;
  errorMessage?: string | null;
  showHeader?: boolean;
  valueClassName?: string;
};

const formatMetadataValue = (value?: string | null) =>
  value?.trim() ? value.trim().toLowerCase().replace(/_/g, ' ') : null;

const formatEvidenceSummary = (term: ApiGlossaryTerm) => {
  const screenshotCount = term.evidence.filter((evidence) => evidence.imageKey).length;
  const noteCount = term.evidence.filter(
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

export function GlossaryTermMetadataPanel({
  glossaryId,
  glossaryName,
  term,
  isLoading = false,
  errorMessage = null,
  showHeader = true,
  valueClassName,
}: Props) {
  if (isLoading) {
    return <div className="glossary-term-metadata__state">Loading glossary term…</div>;
  }

  if (errorMessage) {
    return (
      <div className="glossary-term-metadata__state glossary-term-metadata__state--error">
        {errorMessage}
      </div>
    );
  }

  if (!term) {
    return null;
  }

  const target = term.doNotTranslate
    ? 'Do not translate'
    : term.translations.find((translation) => translation.target?.trim())?.target?.trim() || null;
  const metadata = [
    glossaryName.trim() || null,
    formatMetadataValue(term.termType),
    formatMetadataValue(term.partOfSpeech),
    term.caseSensitive ? 'case-sensitive' : null,
    formatEvidenceSummary(term),
  ].filter((value): value is string => Boolean(value));
  const note = term.definition?.trim() || term.sourceComment?.trim() || null;

  return (
    <section className="glossary-term-metadata">
      {showHeader ? <div className="glossary-term-metadata__label">Glossary term</div> : null}
      <div className={valueClassName ?? 'glossary-term-metadata__value'}>
        <div className="glossary-term-metadata__term">
          <Link
            className="glossary-term-metadata__source"
            to={`/glossaries/${glossaryId}?termId=${term.tmTextUnitId}`}
          >
            {term.source}
          </Link>
          {target ? (
            <span
              className={
                term.doNotTranslate
                  ? 'glossary-term-metadata__target glossary-term-metadata__target--muted'
                  : 'glossary-term-metadata__target'
              }
            >
              {target}
            </span>
          ) : null}
          <span className="glossary-term-metadata__status">
            {formatMetadataValue(term.status) ?? glossaryName}
          </span>
        </div>
        {metadata.length > 0 ? (
          <div className="glossary-term-metadata__meta">
            {metadata.map((item, index) => (
              <span key={`${item}:${index}`}>
                {index > 0 ? <span className="glossary-term-metadata__separator"> · </span> : null}
                {item}
              </span>
            ))}
          </div>
        ) : null}
        {note ? <div className="glossary-term-metadata__note">{note}</div> : null}
      </div>
    </section>
  );
}
