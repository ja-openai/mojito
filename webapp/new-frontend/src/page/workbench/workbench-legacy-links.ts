import type { SearchAttribute, SearchType, TextUnitSearchRequest } from '../../api/text-units';

export const legacyWorkbenchLinkErrorTitle = 'Cannot open workbench link';

const searchAttributes = [
  'stringId',
  'source',
  'target',
  'comment',
  'asset',
  'location',
  'pluralFormOther',
  'tmTextUnitIds',
] as const satisfies SearchAttribute[];

const searchTypes = ['exact', 'contains', 'ilike', 'regex'] as const satisfies SearchType[];

const legacyWorkbenchSearchParams = [
  'repoIds',
  'repoIds[]',
  'repoNames',
  'repoNames[]',
  'repositoryIds',
  'repositoryIds[]',
  'repositoryNames',
  'repositoryNames[]',
  'bcp47Tags',
  'bcp47Tags[]',
  'locale',
  'searchAttribute',
  'searchType',
  'searchText',
];

export type LegacyWorkbenchLinkResult =
  | { status: 'none' }
  | { status: 'pending' }
  | { status: 'error'; message: string }
  | { status: 'ready'; request: TextUnitSearchRequest; nextSearchParams: URLSearchParams };

function getSearchParamValues(searchParams: URLSearchParams, name: string): string[] {
  return [...searchParams.getAll(name), ...searchParams.getAll(`${name}[]`)]
    .map((value) => value.trim())
    .filter(Boolean);
}

function parseSearchAttribute(value: string | null): SearchAttribute {
  return searchAttributes.includes(value as SearchAttribute)
    ? (value as SearchAttribute)
    : 'target';
}

function parseSearchType(value: string | null): SearchType {
  return searchTypes.includes(value as SearchType) ? (value as SearchType) : 'contains';
}

function parseRepositoryIds(searchParams: URLSearchParams): number[] {
  return [
    ...getSearchParamValues(searchParams, 'repoIds'),
    ...getSearchParamValues(searchParams, 'repositoryIds'),
  ]
    .map((value) => Number(value))
    .filter((value) => Number.isInteger(value) && value > 0);
}

function clearLegacyWorkbenchSearchParams(searchParams: URLSearchParams): URLSearchParams {
  const nextParams = new URLSearchParams(searchParams);
  legacyWorkbenchSearchParams.forEach((param) => nextParams.delete(param));
  return nextParams;
}

export function resolveLegacyWorkbenchLink(
  searchParams: URLSearchParams,
  repositoryIdByName: ReadonlyMap<string, number>,
  repositoryCount: number,
): LegacyWorkbenchLinkResult {
  const searchText = searchParams.get('searchText')?.trim();
  if (!searchText) {
    return { status: 'none' };
  }

  const localeTags = Array.from(
    new Set([
      ...getSearchParamValues(searchParams, 'bcp47Tags'),
      ...searchParams
        .getAll('locale')
        .map((value) => value.trim())
        .filter(Boolean),
    ]),
  );
  if (!localeTags.length) {
    return { status: 'none' };
  }

  const repositoryNames = Array.from(
    new Set([
      ...getSearchParamValues(searchParams, 'repoNames'),
      ...getSearchParamValues(searchParams, 'repositoryNames'),
    ]),
  );
  const missingRepositoryNames = repositoryNames.filter((name) => !repositoryIdByName.has(name));
  if (missingRepositoryNames.length && repositoryCount === 0) {
    return { status: 'pending' };
  }

  const repositoryIds = Array.from(
    new Set([
      ...parseRepositoryIds(searchParams),
      ...repositoryNames.flatMap((name) => {
        const id = repositoryIdByName.get(name);
        return id == null ? [] : [id];
      }),
    ]),
  );

  if (!repositoryIds.length) {
    if (missingRepositoryNames.length) {
      return {
        status: 'error',
        message: `Could not find repository: ${missingRepositoryNames.join(', ')}`,
      };
    }
    return { status: 'none' };
  }

  return {
    status: 'ready',
    request: {
      repositoryIds,
      localeTags,
      searchAttribute: parseSearchAttribute(searchParams.get('searchAttribute')),
      searchType: parseSearchType(searchParams.get('searchType')),
      searchText,
      offset: 0,
    },
    nextSearchParams: clearLegacyWorkbenchSearchParams(searchParams),
  };
}
