import type { TextUnitSearchRequest } from '../api/text-units';

export type GlossaryWorkbenchNavigationState = {
  workbenchSearch: TextUnitSearchRequest;
  localePrompt?: boolean;
};

export function buildGlossaryWorkbenchState({
  backingRepositoryId,
  localeTags,
  tmTextUnitId,
}: {
  backingRepositoryId: number;
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
  };
}
