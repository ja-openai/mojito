import type { ApiGlossarySummary, ApiGlossaryTerm } from '../api/glossaries';

type GlossaryTextUnitRef = {
  repositoryId?: number | null;
  repositoryName?: string | number | null;
  assetPath?: string | number | null;
};

export type GlossaryTermTarget = {
  glossaryId: number;
  glossaryName: string;
};

const normalizeToken = (value?: string | number | null) =>
  value == null ? '' : String(value).trim().toLowerCase();

export function findGlossaryTargetForTextUnit(
  glossaries: ApiGlossarySummary[],
  textUnitRef: GlossaryTextUnitRef,
): GlossaryTermTarget | null {
  const assetPath = normalizeToken(textUnitRef.assetPath);
  const repositoryName = normalizeToken(textUnitRef.repositoryName);
  const repositoryId = textUnitRef.repositoryId ?? null;

  if (!assetPath || (!repositoryName && repositoryId == null)) {
    return null;
  }

  const glossary = glossaries.find((candidate) => {
    const candidateAssetPath = normalizeToken(candidate.assetPath);
    const candidateRepositoryName = normalizeToken(candidate.backingRepository.name);
    const candidateRepositoryId = candidate.backingRepository.id;
    const repositoryMatches =
      repositoryId != null
        ? candidateRepositoryId === repositoryId
        : candidateRepositoryName === repositoryName;

    return repositoryMatches && candidateAssetPath === assetPath;
  });

  return glossary ? { glossaryId: glossary.id, glossaryName: glossary.name } : null;
}

export function findGlossaryTermByTmTextUnitId(
  terms: ApiGlossaryTerm[],
  tmTextUnitId?: number | null,
) {
  if (tmTextUnitId == null) {
    return null;
  }
  return terms.find((term) => term.tmTextUnitId === tmTextUnitId) ?? null;
}
