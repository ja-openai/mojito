import type { InfiniteData } from '@tanstack/react-query';
import { useInfiniteQuery } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import type { ApiRepository } from '../../api/repositories';
import {
  type ApiTextUnit,
  getCanonicalTextSearch,
  normalizeTextSearch,
  searchTextUnits,
  type TextSearch,
  type TextUnitSearchRequest,
} from '../../api/text-units';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import { useRepositories } from '../../hooks/useRepositories';
import type { LocaleSelectionOption } from '../../utils/localeSelection';
import { useLocaleOptionsWithDisplayNames, useLocaleSelection } from '../../utils/localeSelection';
import type { RepositorySelectionOption } from '../../utils/repositorySelection';
import {
  useRepositorySelection,
  useRepositorySelectionOptions,
} from '../../utils/repositorySelection';
import { WORKSET_SIZE_DEFAULT } from './workbench-constants';
import { clampWorksetSize, mapApiTextUnitToRow, serializeSearchRequest } from './workbench-helpers';
import { loadPreferredWorksetSize } from './workbench-preferences';
import type {
  StatusFilterValue,
  WorkbenchRow,
  WorkbenchTextSearchCondition,
  WorkbenchTextSearchOperator,
} from './workbench-types';

type SearchQueryKey = ['workbench-search', TextUnitSearchRequest | null];

type Params = {
  initialSearchRequest?: TextUnitSearchRequest | null;
  canEditLocale?: (locale: string) => boolean;
};

type SearchState = {
  repositoryOptions: RepositorySelectionOption[];
  repositories: ApiRepository[];
  localeOptions: LocaleSelectionOption[];
  isRepositoriesLoading: boolean;
  repositoryErrorMessage: string | null;
  selectedRepositoryIds: number[];
  selectedLocaleTags: string[];
  onChangeRepositorySelection: (next: number[]) => void;
  onChangeLocaleSelection: (next: string[]) => void;
  searchAttribute: WorkbenchTextSearchCondition['field'];
  searchType: WorkbenchTextSearchCondition['searchType'];
  searchInputValue: string;
  onChangeSearchAttribute: (value: WorkbenchTextSearchCondition['field']) => void;
  onChangeSearchType: (value: WorkbenchTextSearchCondition['searchType']) => void;
  onChangeSearchInput: (value: string) => void;
  textSearchOperator: WorkbenchTextSearchOperator;
  textSearchConditions: WorkbenchTextSearchCondition[];
  onChangeTextSearchOperator: (value: WorkbenchTextSearchOperator) => void;
  onChangeTextSearchCondition: (
    id: string,
    patch: Partial<Pick<WorkbenchTextSearchCondition, 'field' | 'searchType' | 'value'>>,
  ) => void;
  onAddTextSearchCondition: () => void;
  onRemoveTextSearchCondition: (id: string) => void;
  onSubmitSearch: () => void;
  statusFilter: StatusFilterValue;
  includeUsed: boolean;
  includeUnused: boolean;
  includeTranslate: boolean;
  includeDoNotTranslate: boolean;
  onChangeStatusFilter: (value: StatusFilterValue) => void;
  onChangeIncludeUsed: (value: boolean) => void;
  onChangeIncludeUnused: (value: boolean) => void;
  onChangeIncludeTranslate: (value: boolean) => void;
  onChangeIncludeDoNotTranslate: (value: boolean) => void;
  createdBefore: string | null;
  createdAfter: string | null;
  translationCreatedBefore: string | null;
  translationCreatedAfter: string | null;
  onChangeCreatedBefore: (value: string | null) => void;
  onChangeCreatedAfter: (value: string | null) => void;
  onChangeTranslationCreatedBefore: (value: string | null) => void;
  onChangeTranslationCreatedAfter: (value: string | null) => void;
  worksetSize: number;
  onChangeWorksetSize: (value: number) => void;
  canSearch: boolean;
  activeSearchRequest: TextUnitSearchRequest | null;
  isSearchLoading: boolean;
  searchErrorMessage: string | null;
  rows: WorkbenchRow[];
  hasMoreResults: boolean;
  refetchSearch: () => Promise<unknown>;
  resetSearch: () => void;
  hasHydratedSearch: boolean;
};

type SearchRequestInputs = {
  repositoryIds: number[];
  localeTags: string[];
  textSearch?: TextSearch;
  searchLimit: number;
  statusFilter: StatusFilterValue;
  includeUsed: boolean;
  includeUnused: boolean;
  includeTranslate: boolean;
  includeDoNotTranslate: boolean;
  createdBefore: string | null;
  createdAfter: string | null;
  translationCreatedBefore: string | null;
  translationCreatedAfter: string | null;
};

let nextTextSearchConditionId = 0;

function createTextSearchCondition(
  overrides: Partial<Omit<WorkbenchTextSearchCondition, 'id'>> = {},
): WorkbenchTextSearchCondition {
  nextTextSearchConditionId += 1;
  return {
    id: String(nextTextSearchConditionId),
    field: 'target',
    searchType: 'contains',
    value: '',
    ...overrides,
  };
}

function createSimpleTextSearchCondition(
  overrides: Partial<Omit<WorkbenchTextSearchCondition, 'id'>> = {},
): WorkbenchTextSearchCondition {
  return createTextSearchCondition({ searchType: 'contains', ...overrides });
}

function createAdvancedTextSearchCondition(
  overrides: Partial<Omit<WorkbenchTextSearchCondition, 'id'>> = {},
): WorkbenchTextSearchCondition {
  return createTextSearchCondition({ searchType: 'regex', ...overrides });
}

function toEditableTextSearchConditions(
  textSearch?: TextSearch,
): WorkbenchTextSearchCondition[] {
  const normalizedTextSearch = normalizeTextSearch(textSearch);
  if (!normalizedTextSearch) {
    return [createSimpleTextSearchCondition()];
  }

  return normalizedTextSearch.predicates.map((predicate) =>
    createTextSearchCondition({
      field: predicate.field,
      searchType: predicate.searchType,
      value: predicate.value,
    }),
  );
}

function toTextSearchRequest(
  operator: WorkbenchTextSearchOperator,
  conditions: WorkbenchTextSearchCondition[],
): TextSearch | undefined {
  return normalizeTextSearch({
    operator,
    predicates: conditions.map(({ field, searchType, value }) => ({
      field,
      searchType,
      value,
    })),
  });
}

function getNonTextSignature(request: TextUnitSearchRequest): string {
  return JSON.stringify({
    repositoryIds: [...request.repositoryIds].sort((a, b) => a - b),
    localeTags: [...request.localeTags].sort((a, b) => a.localeCompare(b)),
    statusFilter: request.statusFilter ?? null,
    usedFilter: request.usedFilter ?? null,
    doNotTranslateFilter:
      typeof request.doNotTranslateFilter === 'boolean' ? request.doNotTranslateFilter : null,
    tmTextUnitCreatedBefore: request.tmTextUnitCreatedBefore ?? null,
    tmTextUnitCreatedAfter: request.tmTextUnitCreatedAfter ?? null,
    tmTextUnitVariantCreatedBefore: request.tmTextUnitVariantCreatedBefore ?? null,
    tmTextUnitVariantCreatedAfter: request.tmTextUnitVariantCreatedAfter ?? null,
    limit: request.limit ?? null,
    offset: request.offset ?? null,
  });
}

function buildSearchRequestFromInputs({
  repositoryIds,
  localeTags,
  textSearch,
  searchLimit,
  statusFilter,
  includeUsed,
  includeUnused,
  includeTranslate,
  includeDoNotTranslate,
  createdBefore,
  createdAfter,
  translationCreatedBefore,
  translationCreatedAfter,
}: SearchRequestInputs): TextUnitSearchRequest | null {
  if (!repositoryIds.length || !localeTags.length) {
    return null;
  }

  const usedFilter = (() => {
    if (!includeUsed && !includeUnused) {
      return undefined;
    }
    if (includeUsed && !includeUnused) {
      return 'USED' as const;
    }
    if (!includeUsed && includeUnused) {
      return 'UNUSED' as const;
    }
    return undefined;
  })();

  const doNotTranslateFilter = (() => {
    if (!includeTranslate && !includeDoNotTranslate) {
      return undefined;
    }
    if (includeDoNotTranslate && !includeTranslate) {
      return true;
    }
    if (includeTranslate && !includeDoNotTranslate) {
      return false;
    }
    return undefined;
  })();

  return {
    repositoryIds,
    localeTags,
    textSearch,
    limit: searchLimit,
    offset: 0,
    statusFilter: statusFilter === 'ALL' ? undefined : statusFilter,
    usedFilter,
    doNotTranslateFilter,
    tmTextUnitCreatedBefore: createdBefore ?? undefined,
    tmTextUnitCreatedAfter: createdAfter ?? undefined,
    tmTextUnitVariantCreatedBefore: translationCreatedBefore ?? undefined,
    tmTextUnitVariantCreatedAfter: translationCreatedAfter ?? undefined,
  };
}

export function useWorkbenchSearch({ initialSearchRequest, canEditLocale }: Params): SearchState {
  const [activeSearchRequest, setActiveSearchRequest] = useState<TextUnitSearchRequest | null>(
    null,
  );
  const setActiveSearchRequestIfChanged = useCallback((next: TextUnitSearchRequest | null) => {
    setActiveSearchRequest((current) => {
      if (current === next) {
        return current;
      }
      if (current === null || next === null) {
        return current === next ? current : next;
      }
      return serializeSearchRequest(current) === serializeSearchRequest(next) ? current : next;
    });
  }, []);
  const updateActiveSearchRequest = useCallback(
    (updater: (current: TextUnitSearchRequest | null) => TextUnitSearchRequest | null): void => {
      setActiveSearchRequest((current) => {
        const next = updater(current);
        if (current === next) {
          return current;
        }
        if (current === null || next === null) {
          return current === next ? current : next;
        }
        return serializeSearchRequest(current) === serializeSearchRequest(next) ? current : next;
      });
    },
    [],
  );

  const [textSearchOperator, setTextSearchOperator] = useState<WorkbenchTextSearchOperator>('AND');
  const [textSearchConditions, setTextSearchConditions] = useState<WorkbenchTextSearchCondition[]>(
    () => [createSimpleTextSearchCondition()],
  );
  const [statusFilter, setStatusFilter] = useState<StatusFilterValue>('ALL');
  const [includeUsed, setIncludeUsed] = useState(true);
  const [includeUnused, setIncludeUnused] = useState(false);
  const [includeTranslate, setIncludeTranslate] = useState(true);
  const [includeDoNotTranslate, setIncludeDoNotTranslate] = useState(true);
  const [createdBefore, setCreatedBefore] = useState<string | null>(null);
  const [createdAfter, setCreatedAfter] = useState<string | null>(null);
  const [translationCreatedBefore, setTranslationCreatedBefore] = useState<string | null>(null);
  const [translationCreatedAfter, setTranslationCreatedAfter] = useState<string | null>(null);
  const [worksetSize, setWorksetSize] = useState<number>(
    () => loadPreferredWorksetSize() ?? WORKSET_SIZE_DEFAULT,
  );
  const hydratedSearchSignatureRef = useRef<string | null>(null);
  const lastHydratedRequestRef = useRef<TextUnitSearchRequest | null>(null);
  const [hasHydratedSearch, setHasHydratedSearch] = useState(initialSearchRequest == null);

  const {
    data: repositories,
    isLoading: isRepositoriesLoading,
    isError: isRepositoriesError,
    error: repositoriesError,
  } = useRepositories();

  const repositoryOptions: RepositorySelectionOption[] =
    useRepositorySelectionOptions(repositories);

  const {
    selectedIds: selectedRepositoryIds,
    onChangeSelection: onChangeRepositorySelection,
    setSelection: setRepositorySelection,
  } = useRepositorySelection({
    options: repositoryOptions,
    initialSelected: initialSearchRequest?.repositoryIds ?? [],
    allowStaleSelections: true,
  });

  const allowedRepositoryIds = useMemo(
    () =>
      selectedRepositoryIds.length
        ? new Set(selectedRepositoryIds)
        : new Set(repositoryOptions.map((option) => option.id)),
    [repositoryOptions, selectedRepositoryIds],
  );

  const localeOptions: LocaleSelectionOption[] = useLocaleOptionsWithDisplayNames(
    repositories ?? [],
    allowedRepositoryIds,
  );

  const {
    selectedTags: selectedLocaleTags,
    onChangeSelection: onChangeLocaleSelection,
    setSelection: setLocaleSelection,
  } = useLocaleSelection({
    options: localeOptions,
    initialSelected: initialSearchRequest?.localeTags ?? [],
    allowStaleSelections: true,
  });

  const initialSearchSignature = useMemo(
    () => (initialSearchRequest ? serializeSearchRequest(initialSearchRequest) : null),
    [initialSearchRequest],
  );

  const lastAppliedNonTextSignatureRef = useRef<string | null>(null);
  const lastSuccessfulSearchDataRef = useRef<InfiniteData<ApiTextUnit[], number> | undefined>(
    undefined,
  );

  useEffect(() => {
    if (!initialSearchRequest || !initialSearchSignature) {
      return;
    }
    const alreadyHydrated =
      hydratedSearchSignatureRef.current === initialSearchSignature &&
      lastHydratedRequestRef.current === initialSearchRequest;
    if (alreadyHydrated) {
      return;
    }

    const initialLocales = initialSearchRequest.localeTags ?? [];
    const initialTextSearch = getCanonicalTextSearch(initialSearchRequest);
    setRepositorySelection(initialSearchRequest.repositoryIds ?? [], { markTouched: false });
    setLocaleSelection(initialLocales, { markTouched: false });
    setTextSearchOperator(initialTextSearch?.operator ?? 'AND');
    setTextSearchConditions(
      initialTextSearch
        ? toEditableTextSearchConditions(initialTextSearch)
        : [createSimpleTextSearchCondition()],
    );
    setStatusFilter((initialSearchRequest.statusFilter as StatusFilterValue | undefined) ?? 'ALL');

    const usedFilter = initialSearchRequest.usedFilter;
    const initialIncludeUsed = usedFilter === 'UNUSED' ? false : true;
    const initialIncludeUnused = usedFilter === 'USED' ? false : true;
    setIncludeUsed(initialIncludeUsed);
    setIncludeUnused(initialIncludeUnused);

    const dntFilter = initialSearchRequest.doNotTranslateFilter;
    const initialIncludeTranslate = typeof dntFilter === 'boolean' ? !dntFilter : true;
    const initialIncludeDoNotTranslate = typeof dntFilter === 'boolean' ? dntFilter : true;
    setIncludeTranslate(initialIncludeTranslate);
    setIncludeDoNotTranslate(initialIncludeDoNotTranslate);

    setCreatedBefore(initialSearchRequest.tmTextUnitCreatedBefore ?? null);
    setCreatedAfter(initialSearchRequest.tmTextUnitCreatedAfter ?? null);
    setTranslationCreatedBefore(initialSearchRequest.tmTextUnitVariantCreatedBefore ?? null);
    setTranslationCreatedAfter(initialSearchRequest.tmTextUnitVariantCreatedAfter ?? null);
    const initialLimit = clampWorksetSize(
      initialSearchRequest.limit ?? loadPreferredWorksetSize() ?? WORKSET_SIZE_DEFAULT,
    );
    setWorksetSize(initialLimit);

    if (initialLocales.length > 0) {
      const normalizedRequest = buildSearchRequestFromInputs({
        repositoryIds: initialSearchRequest.repositoryIds ?? [],
        localeTags: initialLocales,
        textSearch: initialTextSearch,
        searchLimit: initialLimit,
        statusFilter: (initialSearchRequest.statusFilter as StatusFilterValue | undefined) ?? 'ALL',
        includeUsed: initialIncludeUsed,
        includeUnused: initialIncludeUnused,
        includeTranslate: initialIncludeTranslate,
        includeDoNotTranslate: initialIncludeDoNotTranslate,
        createdBefore: initialSearchRequest.tmTextUnitCreatedBefore ?? null,
        createdAfter: initialSearchRequest.tmTextUnitCreatedAfter ?? null,
        translationCreatedBefore: initialSearchRequest.tmTextUnitVariantCreatedBefore ?? null,
        translationCreatedAfter: initialSearchRequest.tmTextUnitVariantCreatedAfter ?? null,
      });

      if (normalizedRequest) {
        setActiveSearchRequestIfChanged(normalizedRequest);
        lastAppliedNonTextSignatureRef.current = getNonTextSignature(normalizedRequest);
      } else {
        setActiveSearchRequestIfChanged(null);
      }
    } else {
      setActiveSearchRequestIfChanged(null);
    }

    hydratedSearchSignatureRef.current = initialSearchSignature;
    lastHydratedRequestRef.current = initialSearchRequest;
    setHasHydratedSearch(true);
  }, [
    initialSearchRequest,
    initialSearchSignature,
    setActiveSearchRequestIfChanged,
    setLocaleSelection,
    setRepositorySelection,
  ]);

  useEffect(() => {
    if (hasHydratedSearch || localeOptions.length) {
      return;
    }
    setLocaleSelection([], { markTouched: false });
  }, [hasHydratedSearch, localeOptions.length, setLocaleSelection]);

  const canSearch = selectedRepositoryIds.length > 0 && selectedLocaleTags.length > 0;
  const searchLimit = clampWorksetSize(worksetSize);
  const pendingTextSearch = useMemo(
    () => toTextSearchRequest(textSearchOperator, textSearchConditions),
    [textSearchConditions, textSearchOperator],
  );
  const pendingTextSearchDebounced = useDebouncedValue(pendingTextSearch, 350);

  const buildPendingSearchRequest = useCallback(
    (textSearch?: TextSearch): TextUnitSearchRequest | null =>
      buildSearchRequestFromInputs({
        repositoryIds: selectedRepositoryIds,
        localeTags: selectedLocaleTags,
        textSearch,
        searchLimit,
        statusFilter,
        includeUsed,
        includeUnused,
        includeTranslate,
        includeDoNotTranslate,
        createdBefore,
        createdAfter,
        translationCreatedBefore,
        translationCreatedAfter,
      }),
    [
      createdAfter,
      createdBefore,
      translationCreatedAfter,
      translationCreatedBefore,
      includeDoNotTranslate,
      includeTranslate,
      includeUnused,
      includeUsed,
      searchLimit,
      selectedLocaleTags,
      selectedRepositoryIds,
      statusFilter,
    ],
  );

  const pendingSearchRequest = useMemo(
    () => buildPendingSearchRequest(pendingTextSearch),
    [buildPendingSearchRequest, pendingTextSearch],
  );

  const pendingSearchRequestDebounced = useMemo(
    () => buildPendingSearchRequest(pendingTextSearchDebounced),
    [buildPendingSearchRequest, pendingTextSearchDebounced],
  );

  const nonTextDraftSignature = useMemo(() => {
    if (!pendingSearchRequest) {
      return null;
    }
    return getNonTextSignature(pendingSearchRequest);
  }, [pendingSearchRequest]);

  const resetSearch = useCallback(() => {
    const preferredWorkset = loadPreferredWorksetSize() ?? WORKSET_SIZE_DEFAULT;
    const nextWorksetSize = clampWorksetSize(preferredWorkset);

    setRepositorySelection([], { markTouched: false });
    setLocaleSelection([], { markTouched: false });
    setTextSearchOperator('AND');
    setTextSearchConditions([createSimpleTextSearchCondition()]);
    setStatusFilter('ALL');
    setIncludeUsed(true);
    setIncludeUnused(false);
    setIncludeTranslate(true);
    setIncludeDoNotTranslate(true);
    setCreatedBefore(null);
    setCreatedAfter(null);
    setTranslationCreatedBefore(null);
    setTranslationCreatedAfter(null);
    setWorksetSize(nextWorksetSize);
    setActiveSearchRequestIfChanged(null);
    lastAppliedNonTextSignatureRef.current = null;
    lastSuccessfulSearchDataRef.current = undefined;
  }, [setActiveSearchRequestIfChanged, setLocaleSelection, setRepositorySelection]);

  useEffect(() => {
    if (!pendingSearchRequest || !canSearch) {
      return;
    }
    if (nonTextDraftSignature === null) {
      return;
    }
    if (nonTextDraftSignature === lastAppliedNonTextSignatureRef.current) {
      return;
    }

    lastAppliedNonTextSignatureRef.current = nonTextDraftSignature;
    setActiveSearchRequestIfChanged(pendingSearchRequest);
  }, [canSearch, nonTextDraftSignature, pendingSearchRequest, setActiveSearchRequestIfChanged]);

  useEffect(() => {
    if (!pendingSearchRequestDebounced || !canSearch) {
      return;
    }
    setActiveSearchRequestIfChanged(pendingSearchRequestDebounced);
  }, [canSearch, pendingSearchRequestDebounced, setActiveSearchRequestIfChanged]);

  useEffect(() => {
    if (canSearch) {
      return;
    }
    lastAppliedNonTextSignatureRef.current = null;
    lastSuccessfulSearchDataRef.current = undefined;
    updateActiveSearchRequest((current) => (current === null ? current : null));
  }, [canSearch, updateActiveSearchRequest]);

  useEffect(() => {
    if (!activeSearchRequest) {
      return;
    }
    const appliedLimit = activeSearchRequest.limit ?? WORKSET_SIZE_DEFAULT;
    if (appliedLimit === searchLimit) {
      return;
    }
    setActiveSearchRequestIfChanged({ ...activeSearchRequest, limit: searchLimit, offset: 0 });
  }, [activeSearchRequest, searchLimit, setActiveSearchRequestIfChanged]);

  const searchQuery = useInfiniteQuery<
    ApiTextUnit[],
    Error,
    InfiniteData<ApiTextUnit[], number>,
    SearchQueryKey,
    number
  >({
    queryKey: ['workbench-search', activeSearchRequest],
    placeholderData: () => (activeSearchRequest ? lastSuccessfulSearchDataRef.current : undefined),
    queryFn: ({ pageParam }) => {
      if (!activeSearchRequest) {
        return Promise.resolve([]);
      }
      const limit = clampWorksetSize(activeSearchRequest.limit ?? WORKSET_SIZE_DEFAULT);
      return searchTextUnits({ ...activeSearchRequest, limit: limit + 1, offset: pageParam });
    },
    enabled: Boolean(activeSearchRequest),
    initialPageParam: 0,
    getNextPageParam: () => undefined,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });

  useEffect(() => {
    if (searchQuery.data && !searchQuery.isFetching) {
      lastSuccessfulSearchDataRef.current = searchQuery.data;
    }
  }, [searchQuery.data, searchQuery.isFetching]);

  const rows = useMemo(() => {
    const limit = clampWorksetSize(activeSearchRequest?.limit ?? searchLimit);
    const flatResults = searchQuery.data?.pages.flat() ?? [];
    const visibleResults = flatResults.slice(0, limit);
    return visibleResults.map((item) => mapApiTextUnitToRow(item, canEditLocale));
  }, [activeSearchRequest?.limit, canEditLocale, searchLimit, searchQuery.data]);

  const hasMoreResults = useMemo(() => {
    if (!activeSearchRequest) {
      return false;
    }
    const limit = clampWorksetSize(activeSearchRequest.limit ?? searchLimit);
    const flatResults = searchQuery.data?.pages.flat() ?? [];
    return flatResults.length > limit;
  }, [activeSearchRequest, searchLimit, searchQuery.data]);

  const onSubmitSearch = useCallback(() => {
    if (!canSearch || !pendingSearchRequest) {
      return;
    }
    setActiveSearchRequestIfChanged(pendingSearchRequest);
  }, [canSearch, pendingSearchRequest, setActiveSearchRequestIfChanged]);

  const onChangeTextSearchOperator = useCallback((value: WorkbenchTextSearchOperator) => {
    setTextSearchOperator(value);
  }, []);

  const ensureFirstTextSearchCondition = useCallback(
    (
      updater: (
        current: WorkbenchTextSearchCondition,
      ) => WorkbenchTextSearchCondition,
    ) => {
      setTextSearchConditions((current) => {
        const [firstCondition, ...rest] = current;
        return [updater(firstCondition ?? createSimpleTextSearchCondition()), ...rest];
      });
    },
    [],
  );

  const onChangeSearchAttribute = useCallback(
    (value: WorkbenchTextSearchCondition['field']) => {
      ensureFirstTextSearchCondition((current) => ({ ...current, field: value }));
    },
    [ensureFirstTextSearchCondition],
  );

  const onChangeSearchType = useCallback(
    (value: WorkbenchTextSearchCondition['searchType']) => {
      ensureFirstTextSearchCondition((current) => ({ ...current, searchType: value }));
    },
    [ensureFirstTextSearchCondition],
  );

  const onChangeSearchInput = useCallback(
    (value: string) => {
      ensureFirstTextSearchCondition((current) => ({ ...current, value }));
    },
    [ensureFirstTextSearchCondition],
  );

  const onChangeTextSearchCondition = useCallback(
    (
      id: string,
      patch: Partial<Pick<WorkbenchTextSearchCondition, 'field' | 'searchType' | 'value'>>,
    ) => {
      setTextSearchConditions((current) =>
        current.map((condition) =>
          condition.id === id ? { ...condition, ...patch } : condition,
        ),
      );
    },
    [],
  );

  const onAddTextSearchCondition = useCallback(() => {
    setTextSearchConditions((current) => [...current, createSimpleTextSearchCondition()]);
  }, []);

  const onRemoveTextSearchCondition = useCallback((id: string) => {
    setTextSearchConditions((current) => {
      if (current.length <= 1) {
        const [firstCondition] = current;
        return [
          {
            ...(firstCondition ?? createSimpleTextSearchCondition()),
            value: '',
          },
        ];
      }
      return current.filter((condition) => condition.id !== id);
    });
  }, []);

  const onChangeWorksetSize = useCallback(
    (value: number) => {
      const next = clampWorksetSize(value);
      if (next === worksetSize) {
        return;
      }
      setWorksetSize(next);
      if (activeSearchRequest) {
        updateActiveSearchRequest((previous) =>
          previous ? { ...previous, limit: next, offset: previous.offset ?? 0 } : previous,
        );
      }
    },
    [activeSearchRequest, updateActiveSearchRequest, worksetSize],
  );

  const repositoryErrorMessage = isRepositoriesError
    ? repositoriesError instanceof Error
      ? repositoriesError.message
      : 'Failed to load repositories.'
    : null;

  const searchErrorMessage =
    searchQuery.isError && activeSearchRequest ? searchQuery.error.message : null;

  const isSearchLoading = Boolean(activeSearchRequest) && searchQuery.isFetching;
  const primarySearchCondition = textSearchConditions[0] ?? createSimpleTextSearchCondition();

  return {
    repositoryOptions,
    repositories: repositories ?? [],
    localeOptions,
    isRepositoriesLoading,
    repositoryErrorMessage,
    selectedRepositoryIds,
    selectedLocaleTags,
    onChangeRepositorySelection,
    onChangeLocaleSelection,
    searchAttribute: primarySearchCondition.field,
    searchType: primarySearchCondition.searchType,
    searchInputValue: primarySearchCondition.value,
    onChangeSearchAttribute,
    onChangeSearchType,
    onChangeSearchInput,
    textSearchOperator,
    textSearchConditions,
    onChangeTextSearchOperator,
    onChangeTextSearchCondition,
    onAddTextSearchCondition,
    onRemoveTextSearchCondition,
    onSubmitSearch,
    statusFilter,
    includeUsed,
    includeUnused,
    includeTranslate,
    includeDoNotTranslate,
    onChangeStatusFilter: setStatusFilter,
    onChangeIncludeUsed: setIncludeUsed,
    onChangeIncludeUnused: setIncludeUnused,
    onChangeIncludeTranslate: setIncludeTranslate,
    onChangeIncludeDoNotTranslate: setIncludeDoNotTranslate,
    createdBefore,
    createdAfter,
    translationCreatedBefore,
    translationCreatedAfter,
    onChangeCreatedBefore: setCreatedBefore,
    onChangeCreatedAfter: setCreatedAfter,
    onChangeTranslationCreatedBefore: setTranslationCreatedBefore,
    onChangeTranslationCreatedAfter: setTranslationCreatedAfter,
    worksetSize,
    onChangeWorksetSize,
    canSearch,
    activeSearchRequest,
    isSearchLoading,
    searchErrorMessage,
    rows,
    hasMoreResults,
    refetchSearch: () => searchQuery.refetch(),
    resetSearch,
    hasHydratedSearch,
  };
}
