import type { TextUnitSearchRequest } from '../api/text-units';

export type GlossaryWorkbenchNavigationState = {
  workbenchSearch: TextUnitSearchRequest;
  localePrompt?: boolean;
  glossaryContext?: GlossaryWorkbenchContext;
};

export type GlossaryWorkbenchContext = {
  glossaryId: number;
  glossaryName: string;
  backingRepositoryId: number;
  backingRepositoryName: string;
  assetPath: string;
};

export function buildGlossaryWorkbenchState({
  glossaryId,
  glossaryName,
  backingRepositoryId,
  backingRepositoryName,
  assetPath,
  localeTags,
  tmTextUnitId,
}: {
  glossaryId?: number | null;
  glossaryName?: string | null;
  backingRepositoryId: number;
  backingRepositoryName?: string | null;
  assetPath?: string | null;
  localeTags?: string[] | null;
  tmTextUnitId?: number | null;
}): GlossaryWorkbenchNavigationState {
  const normalizedLocaleTags =
    localeTags
      ?.map((localeTag) => localeTag.trim())
      .filter(Boolean)
      .filter((localeTag, index, values) => {
        const lower = localeTag.toLowerCase();
        return values.findIndex((value) => value.toLowerCase() === lower) === index;
      }) ?? [];

  return {
    workbenchSearch: {
      repositoryIds: [backingRepositoryId],
      localeTags: normalizedLocaleTags,
      searchAttribute: tmTextUnitId == null ? 'target' : 'tmTextUnitIds',
      searchType: tmTextUnitId == null ? 'contains' : 'exact',
      searchText: tmTextUnitId == null ? '' : String(tmTextUnitId),
      offset: 0,
    },
    localePrompt: normalizedLocaleTags.length === 0 ? true : undefined,
    glossaryContext:
      glossaryId != null && glossaryName && backingRepositoryName
        ? {
            glossaryId,
            glossaryName,
            backingRepositoryId,
            backingRepositoryName,
            assetPath: assetPath?.trim() || 'glossary',
          }
        : undefined,
  };
}
