import type { ApiRepository } from '../api/repositories';
import type { LocaleOption } from '../components/LocaleMultiSelect';

type GlossaryScopeMode = 'GLOBAL' | 'SELECTED_REPOSITORIES';

type BuildScopedGlossaryLocaleOptionsArgs = {
  allOptions: LocaleOption[];
  repositories: ApiRepository[];
  scopeMode: GlossaryScopeMode;
  repositoryIds: number[];
  excludedRepositoryIds: number[];
  selectedTags: string[];
  backingRepositoryId?: number | null;
};

const normalizeLocaleTag = (tag: string | null | undefined) => tag?.trim().toLowerCase() ?? '';

export function buildScopedGlossaryLocaleOptions({
  allOptions,
  repositories,
  scopeMode,
  repositoryIds,
  excludedRepositoryIds,
  selectedTags,
  backingRepositoryId,
}: BuildScopedGlossaryLocaleOptionsArgs) {
  const selectedRepositoryIdSet = new Set(repositoryIds);
  const excludedRepositoryIdSet = new Set(excludedRepositoryIds);

  const scopedLocaleTagSet = new Set<string>();
  repositories.forEach((repository) => {
    if (repository.id === backingRepositoryId) {
      return;
    }

    const isInScope =
      scopeMode === 'GLOBAL'
        ? !excludedRepositoryIdSet.has(repository.id)
        : selectedRepositoryIdSet.has(repository.id);
    if (!isInScope) {
      return;
    }

    (repository.repositoryLocales ?? []).forEach((repositoryLocale) => {
      if (repositoryLocale.parentLocale == null) {
        return;
      }
      const normalizedTag = normalizeLocaleTag(repositoryLocale.locale?.bcp47Tag);
      if (normalizedTag) {
        scopedLocaleTagSet.add(normalizedTag);
      }
    });
  });

  if (!scopedLocaleTagSet.size) {
    return {
      options: allOptions,
      isFiltered: false,
    };
  }

  selectedTags.forEach((tag) => {
    const normalizedTag = normalizeLocaleTag(tag);
    if (normalizedTag) {
      scopedLocaleTagSet.add(normalizedTag);
    }
  });

  const options = allOptions.filter((option) =>
    scopedLocaleTagSet.has(normalizeLocaleTag(option.tag)),
  );

  return {
    options,
    isFiltered: options.length < allOptions.length,
  };
}
