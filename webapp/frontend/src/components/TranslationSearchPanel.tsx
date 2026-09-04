import './translation-search-panel.css';

import { useQuery } from '@tanstack/react-query';
import { useRef, useState } from 'react';

import { fetchRepositories } from '../api/repositories';
import {
  normalizeTextSearch,
  searchTextUnits,
  type TextSearchOperator,
  type TextUnitSearchRequest,
} from '../api/text-units';
import { REPOSITORIES_QUERY_KEY } from '../hooks/useRepositories';
import { useLocaleDisplayNameResolver } from '../utils/localeDisplayNames';
import { useLocaleOptionsWithDisplayNames } from '../utils/localeSelection';
import { toHtmlLangTag } from '../utils/localeTag';
import { useRepositorySelectionOptions } from '../utils/repositorySelection';
import { buildTextUnitDetailUrl } from '../utils/textUnitDetailUrl';
import { LocaleMultiSelect } from './LocaleMultiSelect';
import { LocalePill } from './LocalePill';
import { RepositoryMultiSelect } from './RepositoryMultiSelect';
import { type TextSearchCondition, TextUnitSearchControl } from './TextUnitSearchControl';

const PAGE_SIZE = 50;
const createCondition = (id: string): TextSearchCondition => ({
  id,
  field: 'target',
  searchType: 'contains',
  value: '',
});

export function TranslationSearchPanel({
  localeTag,
  active = true,
}: {
  localeTag: string;
  active?: boolean;
}) {
  const nextConditionId = useRef(1);
  const [conditions, setConditions] = useState<TextSearchCondition[]>(() => [createCondition('0')]);
  const [operator, setOperator] = useState<TextSearchOperator>('AND');
  // Null prefills all repositories after the catalog loads; [] is an explicit empty selection.
  const [repositorySelection, setRepositorySelection] = useState<number[] | null>(null);
  const [localeTags, setLocaleTags] = useState<string[]>(() => (localeTag ? [localeTag] : []));
  const [request, setRequest] = useState<TextUnitSearchRequest | null>(null);
  const resolveLocaleName = useLocaleDisplayNameResolver();
  const repositories = useQuery({
    queryKey: REPOSITORIES_QUERY_KEY,
    queryFn: fetchRepositories,
    enabled: active,
    staleTime: 30_000,
  });
  const repositoryOptions = useRepositorySelectionOptions(repositories.data);
  const repositoryIds =
    repositorySelection == null
      ? repositoryOptions.map(({ id }) => id)
      : repositorySelection.filter((id) => repositoryOptions.some((option) => option.id === id));
  const results = useQuery({
    queryKey: ['translation-search', request],
    queryFn: async () => {
      const textUnits = await searchTextUnits(request!);
      return { textUnits: textUnits.slice(0, PAGE_SIZE), hasMore: textUnits.length > PAGE_SIZE };
    },
    enabled: active && request != null,
    staleTime: 30_000,
    retry: false,
  });
  const repositoryLocaleOptions = useLocaleOptionsWithDisplayNames(repositories.data ?? []);
  const localeOptions =
    localeTag && !repositoryLocaleOptions.some((option) => option.tag === localeTag)
      ? [{ tag: localeTag, label: resolveLocaleName(localeTag) }, ...repositoryLocaleOptions]
      : repositoryLocaleOptions;
  const textSearch = normalizeTextSearch({
    operator,
    predicates: conditions.map(({ field, searchType, value }) => ({ field, searchType, value })),
  });
  const canSearch = Boolean(textSearch) && localeTags.length > 0 && repositoryIds.length > 0;

  const submit = () => {
    if (!canSearch || results.isFetching) return;
    const nextRequest: TextUnitSearchRequest = {
      repositoryIds,
      localeTags,
      textSearch,
      usedFilter: 'USED',
      limit: PAGE_SIZE + 1,
      offset: 0,
      orderedByTextUnitId: true,
    };
    if (request && JSON.stringify(request) === JSON.stringify(nextRequest)) {
      void results.refetch();
    } else {
      setRequest(nextRequest);
    }
  };

  return (
    <section
      className="translation-search"
      aria-label="Translation search"
      onKeyDown={(event) => {
        // Search keyboard actions must not trigger the review editor's save shortcuts.
        event.stopPropagation();
        if (
          event.key === 'Enter' &&
          (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229)
        ) {
          event.preventDefault();
        }
      }}
    >
      <div className="translation-search__controls">
        <RepositoryMultiSelect
          options={repositoryOptions}
          selectedIds={repositoryIds}
          onChange={(next) => {
            setRepositorySelection(next);
            setRequest(null);
          }}
          disabled={repositories.isLoading}
          buttonAriaLabel="Select repositories"
          showSelectionPresets
        />
        <LocaleMultiSelect
          options={localeOptions}
          selectedTags={localeTags}
          onChange={(next) => {
            setLocaleTags(next);
            setRequest(null);
          }}
          buttonAriaLabel="Select locales"
          showSelectionPresets
          customActions={[
            {
              label: 'Active locale',
              onClick: () => {
                setLocaleTags(localeTag ? [localeTag] : []);
                setRequest(null);
              },
              disabled: !localeTag,
            },
          ]}
        />
      </div>
      <TextUnitSearchControl
        disabled={false}
        operator={operator}
        conditions={conditions}
        onChangeOperator={(next) => {
          setOperator(next);
          setRequest(null);
        }}
        onChangeCondition={(id, patch) => {
          setConditions((current) =>
            current.map((condition) =>
              condition.id === id ? { ...condition, ...patch } : condition,
            ),
          );
          setRequest(null);
        }}
        onAddCondition={() => {
          const condition = createCondition(String(nextConditionId.current++));
          setConditions((current) => [...current, condition]);
          setRequest(null);
        }}
        onRemoveCondition={(id) => {
          setConditions((current) =>
            current.length > 1
              ? current.filter((condition) => condition.id !== id)
              : [{ ...current[0], value: '' }],
          );
          setRequest(null);
        }}
        onSubmitSearch={submit}
      />
      <div className="translation-search__submit">
        <button
          type="button"
          className="review-project-detail__actions-button review-project-detail__actions-button--primary"
          onClick={submit}
          disabled={!canSearch || results.isFetching}
        >
          Search
        </button>
      </div>
      {!localeTags.length ? (
        <div className="translation-search__hint">Select at least one locale.</div>
      ) : null}
      {!repositoryIds.length && repositories.isSuccess ? (
        <div className="translation-search__hint">
          {repositoryOptions.length
            ? 'Select at least one repository.'
            : 'No repositories available to search.'}
        </div>
      ) : null}
      {repositories.isError ? (
        <div role="alert">
          Unable to load repositories.{' '}
          <button
            type="button"
            className="review-project-detail__actions-button"
            onClick={() => void repositories.refetch()}
          >
            Retry
          </button>
        </div>
      ) : null}
      <div aria-live="polite" aria-busy={request != null && results.isFetching}>
        {request && results.isFetching ? (
          <div className="translation-search__hint">Searching…</div>
        ) : null}
        {request && results.isError ? (
          <div role="alert">
            {results.error instanceof Error
              ? results.error.message
              : 'Unable to search translations.'}
          </div>
        ) : null}
        {request && results.data && !results.isFetching && !results.isError ? (
          <>
            <div className="translation-search__summary">
              {!results.data.textUnits.length
                ? 'No matches found.'
                : `Matches ${(request.offset ?? 0) + 1}–${(request.offset ?? 0) + results.data.textUnits.length}`}
            </div>
            <ul className="translation-search__results">
              {results.data.textUnits.map((textUnit) => (
                <li
                  key={`${textUnit.tmTextUnitId}:${textUnit.targetLocale}:${textUnit.assetTextUnitId ?? ''}`}
                  className="translation-search__result"
                >
                  <div className="translation-search__result-meta">
                    <span>{textUnit.repositoryName}</span>
                    <LocalePill bcp47Tag={textUnit.targetLocale} />
                    <a
                      href={buildTextUnitDetailUrl(textUnit.tmTextUnitId, textUnit.targetLocale)}
                      target="_blank"
                      rel="noreferrer"
                    >
                      Open string
                    </a>
                  </div>
                  <div className="translation-search__name" title={textUnit.assetPath ?? undefined}>
                    {textUnit.name}
                  </div>
                  <div className="translation-search__text">
                    <span>Source</span>
                    <div dir="auto">{textUnit.source}</div>
                  </div>
                  <div className="translation-search__text">
                    <span>Translation</span>
                    <div dir="auto" lang={toHtmlLangTag(textUnit.targetLocale)}>
                      {textUnit.target ?? 'No translation'}
                    </div>
                  </div>
                  {textUnit.target != null ? (
                    <div className="translation-search__author">
                      Saved by {textUnit.translationCreatedByUsername?.trim() || 'unknown'}
                    </div>
                  ) : null}
                </li>
              ))}
            </ul>
            {(request.offset ?? 0) > 0 || results.data.hasMore ? (
              <div className="translation-search__pagination">
                <button
                  type="button"
                  className="review-project-detail__actions-button"
                  disabled={(request.offset ?? 0) === 0}
                  onClick={() =>
                    setRequest({
                      ...request,
                      offset: Math.max(0, (request.offset ?? 0) - PAGE_SIZE),
                    })
                  }
                >
                  Previous
                </button>
                <button
                  type="button"
                  className="review-project-detail__actions-button"
                  disabled={!results.data.hasMore}
                  onClick={() =>
                    setRequest({ ...request, offset: (request.offset ?? 0) + PAGE_SIZE })
                  }
                >
                  Next
                </button>
              </div>
            ) : null}
          </>
        ) : null}
        {!request && !textSearch ? (
          <div className="translation-search__hint">
            Enter a word or phrase to see how it is translated elsewhere.
          </div>
        ) : null}
      </div>
    </section>
  );
}
