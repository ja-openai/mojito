import { type ReactNode, useEffect, useMemo, useState } from 'react';

import type { SearchAttribute, SearchType } from '../../api/text-units';
import { MultiSectionFilterChip } from '../../components/filters/MultiSectionFilterChip';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import {
  RepositoryMultiSelect,
  type RepositoryMultiSelectOption,
} from '../../components/RepositoryMultiSelect';
import { SearchControl } from '../../components/SearchControl';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import { getStandardDateQuickRanges } from '../../utils/dateQuickRanges';
import type { LocaleSelectionOption } from '../../utils/localeSelection';
import { filterMyLocales } from '../../utils/localeSelection';
import { resultSizePresets, WORKSET_SIZE_DEFAULT, WORKSET_SIZE_MIN } from './workbench-constants';
import { loadPreferredLocales, PREFERRED_LOCALES_KEY } from './workbench-preferences';
import type {
  StatusFilterValue,
  WorkbenchTextSearchCondition,
  WorkbenchTextSearchOperator,
} from './workbench-types';

type SearchAttributeOption = { value: SearchAttribute; label: string; helper?: string };
type SearchTypeOption = { value: SearchType; label: string; helper?: string };
type StatusFilterOption = { value: StatusFilterValue; label: string };

const searchAttributeOptions: SearchAttributeOption[] = [
  { value: 'target', label: 'Translation' },
  { value: 'source', label: 'Source' },
  { value: 'stringId', label: 'String ID' },
  { value: 'asset', label: 'Asset path' },
  { value: 'location', label: 'Location' },
  { value: 'pluralFormOther', label: 'Plural (other)' },
  { value: 'tmTextUnitIds', label: 'TextUnit IDs' },
];

const searchTypeOptions: SearchTypeOption[] = [
  { value: 'exact', label: 'Exact match', helper: 'Full string' },
  { value: 'contains', label: 'Contains', helper: 'Case-sensitive' },
  { value: 'ilike', label: 'iLike', helper: 'Pattern (% = any, _ = 1 char)' },
  {
    value: 'regex',
    label: 'Regex',
    helper: '\\x{FFFF}, ^/$, .*, (?i)incensitive',
  },
];

const statusFilterOptions: StatusFilterOption[] = [
  // Match legacy workbench semantics and wording.
  { value: 'ALL', label: 'All statuses' },
  { value: 'NOT_ACCEPTED', label: 'Not accepted' },
  { value: 'TRANSLATED', label: 'Translated' },
  { value: 'UNTRANSLATED', label: 'Untranslated' },
  { value: 'FOR_TRANSLATION', label: 'To translate' },
  { value: 'REVIEW_NEEDED', label: 'To review' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'APPROVED_AND_NOT_REJECTED', label: 'Accepted' },
];

type CompoundTextSearchBuilderProps = {
  disabled: boolean;
  operator: WorkbenchTextSearchOperator;
  conditions: WorkbenchTextSearchCondition[];
  onChangeOperator: (value: WorkbenchTextSearchOperator) => void;
  onChangeCondition: (
    id: string,
    patch: Partial<Pick<WorkbenchTextSearchCondition, 'field' | 'searchType' | 'value'>>,
  ) => void;
  onAddCondition: () => void;
  onRemoveCondition: (id: string) => void;
  onSubmitSearch: () => void;
};

type TextSearchConditionControlProps = {
  disabled: boolean;
  condition: WorkbenchTextSearchCondition;
  leading: ReactNode;
  after?: ReactNode;
  className?: string;
  onChangeValue: (value: string) => void;
  onSubmitSearch: () => void;
};

function getSearchPlaceholder(searchAttribute: SearchAttribute) {
  switch (searchAttribute) {
    case 'target':
      return 'Search translation';
    case 'source':
      return 'Search source text';
    case 'stringId':
      return 'Search string ID';
    case 'asset':
      return 'Search asset path';
    case 'location':
      return 'Search location (usage)';
    case 'pluralFormOther':
      return 'Search plural form (other)';
    case 'tmTextUnitIds':
      return 'Search TM TextUnit IDs (comma or space separated)';
    default:
      return 'Search';
  }
}

function TextSearchConditionControl({
  disabled,
  condition,
  leading,
  after,
  className,
  onChangeValue,
  onSubmitSearch,
}: TextSearchConditionControlProps) {
  const placeholder = getSearchPlaceholder(condition.field);

  return (
    <div className="workbench-searchrow">
      <SearchControl
        value={condition.value}
        onChange={onChangeValue}
        onSubmit={onSubmitSearch}
        disabled={disabled}
        placeholder={placeholder}
        inputAriaLabel={placeholder}
        className={className}
        leading={leading}
      />
      {after ? <div className="workbench-searchrow__after">{after}</div> : null}
    </div>
  );
}

function getSearchConditionSummary(condition: Pick<WorkbenchTextSearchCondition, 'field' | 'searchType'>) {
  return [
    searchAttributeOptions.find((option) => option.value === condition.field)?.label,
    searchTypeOptions.find((option) => option.value === condition.searchType)?.label,
  ]
    .filter(Boolean)
    .join(' · ');
}

function SearchConditionOptionsChip({
  disabled,
  condition,
  onChangeField,
  onChangeSearchType,
}: {
  disabled: boolean;
  condition: Pick<WorkbenchTextSearchCondition, 'field' | 'searchType'>;
  onChangeField: (value: SearchAttribute) => void;
  onChangeSearchType: (value: SearchType) => void;
}) {
  return (
    <MultiSectionFilterChip
      align="left"
      ariaLabel="Select search options"
      className="workbench-searchmode workbench-searchmode--inline"
      classNames={{
        button: 'workbench-searchmode__button',
        panel: 'workbench-searchmode__panel',
        section: 'workbench-searchmode__section',
        label: 'workbench-searchmode__label',
        list: 'workbench-searchmode__list',
        option: 'workbench-searchmode__option',
        helper: 'workbench-searchmode__helper',
      }}
      disabled={disabled}
      summary={getSearchConditionSummary(condition)}
      sections={[
        {
          kind: 'radio',
          label: 'Search attribute',
          options: searchAttributeOptions,
          value: condition.field,
          onChange: (value) => onChangeField(value as SearchAttribute),
        },
        {
          kind: 'radio',
          label: 'Match type',
          options: searchTypeOptions,
          value: condition.searchType,
          onChange: (value) => onChangeSearchType(value as SearchType),
        },
      ]}
    />
  );
}

function CompoundTextSearchBuilder({
  disabled,
  operator,
  conditions,
  onChangeOperator,
  onChangeCondition,
  onAddCondition,
  onRemoveCondition,
  onSubmitSearch,
}: CompoundTextSearchBuilderProps) {
  return (
    <div className="workbench-searchbuilder">
      <div className="workbench-searchbuilder__toolbar">
        <label className="workbench-searchbuilder__toggle">
          <span>Match</span>
          <SingleSelectDropdown<WorkbenchTextSearchOperator>
            label="Match operator"
            className="workbench-searchbuilder__select workbench-searchbuilder__select--operator"
            value={operator}
            options={[
              { value: 'AND', label: 'all' },
              { value: 'OR', label: 'any' },
            ]}
            onChange={(value) => {
              if (value) {
                onChangeOperator(value);
              }
            }}
            disabled={disabled}
            searchable={false}
            buttonAriaLabel="Match operator"
          />
        </label>
        <button
          type="button"
          className="workbench-searchbuilder__action"
          onClick={onAddCondition}
          disabled={disabled}
        >
          Add
        </button>
      </div>

      <div className="workbench-searchbuilder__rows">
        {conditions.map((condition) => (
          <div key={condition.id} className="workbench-searchbuilder__row">
            <TextSearchConditionControl
              disabled={disabled}
              condition={condition}
              className="workbench-searchbuilder__control"
              onChangeValue={(value) => onChangeCondition(condition.id, { value })}
              onSubmitSearch={onSubmitSearch}
              leading={
                <SearchConditionOptionsChip
                  disabled={disabled}
                  condition={condition}
                  onChangeField={(value) => onChangeCondition(condition.id, { field: value })}
                  onChangeSearchType={(value) =>
                    onChangeCondition(condition.id, { searchType: value })
                  }
                />
              }
              after={
                <button
                  type="button"
                  className="workbench-searchrow__remove"
                  onClick={() => onRemoveCondition(condition.id)}
                  disabled={disabled}
                  aria-label="Remove search condition"
                  title="Remove condition"
                >
                  <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false">
                    <path d="M6 2h4l.5 1H13v1H3V3h2.5L6 2Zm-1 3h1v7H5V5Zm3 0h1v7H8V5Zm3 0h1v7h-1V5ZM4 13V5h8v8H4Z" />
                  </svg>
                </button>
              }
            />
          </div>
        ))}
      </div>
    </div>
  );
}

type WorkbenchHeaderProps = {
  disabled: boolean;
  worksetSize: number;
  onChangeWorksetSize: (value: number) => void;
  repositoryOptions: RepositoryMultiSelectOption[];
  selectedRepositoryIds: number[];
  onChangeRepositorySelection: (next: number[]) => void;
  isRepositoryLoading: boolean;
  localeOptions: LocaleSelectionOption[];
  selectedLocaleTags: string[];
  onChangeLocaleSelection: (next: string[]) => void;
  userLocales: string[];
  isLimitedTranslator: boolean;
  searchAttribute: SearchAttribute;
  searchType: SearchType;
  searchInputValue: string;
  onChangeSearchAttribute: (value: SearchAttribute) => void;
  onChangeSearchType: (value: SearchType) => void;
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
  onChangeCreatedBefore: (value: string | null) => void;
  onChangeCreatedAfter: (value: string | null) => void;
  translationCreatedBefore: string | null;
  translationCreatedAfter: string | null;
  onChangeTranslationCreatedBefore: (value: string | null) => void;
  onChangeTranslationCreatedAfter: (value: string | null) => void;
};

export function WorkbenchHeader({
  disabled,
  worksetSize,
  onChangeWorksetSize,
  repositoryOptions,
  selectedRepositoryIds,
  onChangeRepositorySelection,
  localeOptions,
  selectedLocaleTags,
  onChangeLocaleSelection,
  userLocales,
  isLimitedTranslator,
  searchAttribute,
  searchType,
  searchInputValue,
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
  onChangeStatusFilter,
  onChangeIncludeUsed,
  onChangeIncludeUnused,
  onChangeIncludeTranslate,
  onChangeIncludeDoNotTranslate,
  createdBefore,
  createdAfter,
  onChangeCreatedBefore,
  onChangeCreatedAfter,
  translationCreatedBefore,
  translationCreatedAfter,
  onChangeTranslationCreatedBefore,
  onChangeTranslationCreatedAfter,
}: WorkbenchHeaderProps) {
  const [preferredLocales, setPreferredLocales] = useState<string[]>(() => loadPreferredLocales());

  useEffect(() => {
    const handleStorage = (event: StorageEvent) => {
      if (event.key && event.key !== PREFERRED_LOCALES_KEY) {
        return;
      }
      setPreferredLocales(loadPreferredLocales());
    };
    window.addEventListener('storage', handleStorage);
    return () => window.removeEventListener('storage', handleStorage);
  }, []);

  const myLocaleSelections = useMemo(
    () =>
      filterMyLocales({
        availableLocaleTags: localeOptions.map((option) => option.tag),
        userLocales,
        preferredLocales,
        isLimitedTranslator,
        // Header does not receive role; mimic previous behavior by using preferred locales when not limited.
        isAdmin: !isLimitedTranslator,
      }),
    [isLimitedTranslator, localeOptions, preferredLocales, userLocales],
  );

  const searchControlsDisabled = disabled;
  const shouldShowMyLocalesAction = isLimitedTranslator || preferredLocales.length > 0;
  const primaryTextSearchCondition = textSearchConditions[0] ?? {
    id: 'primary',
    field: searchAttribute,
    searchType,
    value: searchInputValue,
  };
  const hasCompoundSearch = textSearchConditions.length > 1;
  const canAddCompoundCondition = primaryTextSearchCondition.value.trim().length > 0;

  return (
    <div className="workbench-page__header workbench-header">
      <div className="workbench-header__left">
        <RepositoryMultiSelect
          className="workbench-chip-dropdown"
          options={repositoryOptions}
          selectedIds={selectedRepositoryIds}
          onChange={onChangeRepositorySelection}
          disabled={searchControlsDisabled}
          buttonAriaLabel="Select repositories"
        />
        <LocaleMultiSelect
          className="workbench-chip-dropdown workbench-chip-dropdown--locale"
          options={localeOptions}
          selectedTags={selectedLocaleTags}
          onChange={onChangeLocaleSelection}
          disabled={searchControlsDisabled}
          myLocaleTags={shouldShowMyLocalesAction ? myLocaleSelections : []}
          myLocalesAriaLabel={
            isLimitedTranslator ? 'Select your assigned locales' : 'Select your preferred locales'
          }
        />
      </div>

      <div className="workbench-header__search">
        <div className="workbench-searchpane">
          {hasCompoundSearch ? (
            <CompoundTextSearchBuilder
              disabled={searchControlsDisabled}
              operator={textSearchOperator}
              conditions={textSearchConditions}
              onChangeOperator={onChangeTextSearchOperator}
              onChangeCondition={onChangeTextSearchCondition}
              onAddCondition={onAddTextSearchCondition}
              onRemoveCondition={onRemoveTextSearchCondition}
              onSubmitSearch={onSubmitSearch}
            />
          ) : (
            <TextSearchConditionControl
              disabled={searchControlsDisabled}
              condition={primaryTextSearchCondition}
              className="workbench-searchcontrol"
              onChangeValue={onChangeSearchInput}
              onSubmitSearch={onSubmitSearch}
              leading={
                <SearchConditionOptionsChip
                  disabled={searchControlsDisabled}
                  condition={primaryTextSearchCondition}
                  onChangeField={onChangeSearchAttribute}
                  onChangeSearchType={onChangeSearchType}
                />
              }
              after={
                <button
                  type="button"
                  className="workbench-searchcontrol__add"
                  onClick={onAddTextSearchCondition}
                  disabled={searchControlsDisabled || !canAddCompoundCondition}
                  aria-hidden={!canAddCompoundCondition}
                  tabIndex={canAddCompoundCondition ? undefined : -1}
                  style={{
                    visibility: canAddCompoundCondition ? 'visible' : 'hidden',
                    pointerEvents: canAddCompoundCondition ? undefined : 'none',
                  }}
                >
                  Add
                </button>
              }
            />
          )}
        </div>
      </div>

      <div className="workbench-header__right">
        <SearchFilter
          disabled={searchControlsDisabled}
          statusFilter={statusFilter}
          includeUsed={includeUsed}
          includeUnused={includeUnused}
          includeTranslate={includeTranslate}
          includeDoNotTranslate={includeDoNotTranslate}
          createdBefore={createdBefore}
          createdAfter={createdAfter}
          translationCreatedBefore={translationCreatedBefore}
          translationCreatedAfter={translationCreatedAfter}
          onChangeCreatedBefore={onChangeCreatedBefore}
          onChangeCreatedAfter={onChangeCreatedAfter}
          onChangeTranslationCreatedBefore={onChangeTranslationCreatedBefore}
          onChangeTranslationCreatedAfter={onChangeTranslationCreatedAfter}
          worksetSize={worksetSize}
          onChangeWorksetSize={onChangeWorksetSize}
          onChangeStatusFilter={onChangeStatusFilter}
          onChangeIncludeUsed={onChangeIncludeUsed}
          onChangeIncludeUnused={onChangeIncludeUnused}
          onChangeIncludeTranslate={onChangeIncludeTranslate}
          onChangeIncludeDoNotTranslate={onChangeIncludeDoNotTranslate}
        />
      </div>
    </div>
  );
}

type FilterChipProps = {
  disabled: boolean;
  statusFilter: StatusFilterValue;
  includeUsed: boolean;
  includeUnused: boolean;
  includeTranslate: boolean;
  includeDoNotTranslate: boolean;
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
  onChangeStatusFilter: (value: StatusFilterValue) => void;
  onChangeIncludeUsed: (value: boolean) => void;
  onChangeIncludeUnused: (value: boolean) => void;
  onChangeIncludeTranslate: (value: boolean) => void;
  onChangeIncludeDoNotTranslate: (value: boolean) => void;
};

function SearchFilter({
  disabled,
  statusFilter,
  includeUsed,
  includeUnused,
  includeTranslate,
  includeDoNotTranslate,
  createdBefore,
  createdAfter,
  translationCreatedBefore,
  translationCreatedAfter,
  onChangeCreatedBefore,
  onChangeCreatedAfter,
  onChangeTranslationCreatedBefore,
  onChangeTranslationCreatedAfter,
  worksetSize,
  onChangeWorksetSize,
  onChangeStatusFilter,
  onChangeIncludeUsed,
  onChangeIncludeUnused,
  onChangeIncludeTranslate,
  onChangeIncludeDoNotTranslate,
}: FilterChipProps) {
  const worksetPresets = resultSizePresets;
  const statusLabel =
    statusFilterOptions.find((option) => option.value === statusFilter)?.label ?? 'All statuses';
  const defaultWorksetSize = WORKSET_SIZE_DEFAULT;

  type UsageFilterValue = 'any' | 'used' | 'unused';
  type TranslateFilterValue = 'any' | 'translate' | 'do-not-translate';

  const usageValue: UsageFilterValue =
    includeUsed && includeUnused ? 'any' : includeUsed ? 'used' : 'unused';
  const translateValue: TranslateFilterValue =
    includeTranslate && includeDoNotTranslate
      ? 'any'
      : includeTranslate
        ? 'translate'
        : 'do-not-translate';

  const handleUsageChange = (value: UsageFilterValue) => {
    if (value === 'any') {
      onChangeIncludeUsed(true);
      onChangeIncludeUnused(true);
    } else if (value === 'used') {
      onChangeIncludeUsed(true);
      onChangeIncludeUnused(false);
    } else {
      onChangeIncludeUsed(false);
      onChangeIncludeUnused(true);
    }
  };

  const handleTranslateChange = (value: TranslateFilterValue) => {
    if (value === 'any') {
      onChangeIncludeTranslate(true);
      onChangeIncludeDoNotTranslate(true);
    } else if (value === 'translate') {
      onChangeIncludeTranslate(true);
      onChangeIncludeDoNotTranslate(false);
    } else {
      onChangeIncludeTranslate(false);
      onChangeIncludeDoNotTranslate(true);
    }
  };

  const hasDateFilter = Boolean(createdBefore) || Boolean(createdAfter);
  const hasTranslationDateFilter =
    Boolean(translationCreatedBefore) || Boolean(translationCreatedAfter);
  const usageLabel =
    includeUsed && includeUnused ? 'Used: any' : !includeUsed && includeUnused ? 'Unused' : null;
  const summaryParts: string[] = [statusLabel];
  if (usageLabel) {
    summaryParts.push(usageLabel);
  }
  if (hasDateFilter) {
    summaryParts.push('Text unit date');
  }
  if (hasTranslationDateFilter) {
    summaryParts.push('Translation date');
  }
  if (worksetSize !== defaultWorksetSize) {
    const presetLabel = worksetPresets.find((preset) => preset.value === worksetSize)?.label;
    const compactLabel =
      presetLabel ??
      (worksetSize >= 1000 && worksetSize % 1000 === 0
        ? `${worksetSize / 1000}k`
        : String(worksetSize));
    summaryParts.push(`Size ${compactLabel}`);
  }
  const summary = summaryParts.join(' · ');

  const quickRanges = getStandardDateQuickRanges();

  return (
    <MultiSectionFilterChip
      align="right"
      ariaLabel="Filter workbench results"
      disabled={disabled}
      summary={summary}
      sections={[
        {
          kind: 'radio',
          label: 'Status',
          options: statusFilterOptions,
          value: statusFilter,
          onChange: (value) => onChangeStatusFilter(value as StatusFilterValue),
        },
        {
          kind: 'radio',
          label: 'Used',
          options: [
            { value: 'any', label: 'Any' },
            { value: 'used', label: 'Yes' },
            { value: 'unused', label: 'No' },
          ],
          value: usageValue,
          onChange: (value) => handleUsageChange(value as UsageFilterValue),
        },
        {
          kind: 'radio',
          label: 'Translate',
          options: [
            { value: 'any', label: 'Any' },
            { value: 'translate', label: 'Yes' },
            { value: 'do-not-translate', label: 'No' },
          ],
          value: translateValue,
          onChange: (value) => handleTranslateChange(value as TranslateFilterValue),
        },
        {
          kind: 'date',
          label: 'Text unit created',
          after: createdAfter ?? undefined,
          before: createdBefore ?? undefined,
          onChangeAfter: onChangeCreatedAfter,
          onChangeBefore: onChangeCreatedBefore,
          quickRanges,
          onClear: () => {
            onChangeCreatedBefore(null);
            onChangeCreatedAfter(null);
          },
          clearLabel: 'Clear dates',
        },
        {
          kind: 'date',
          label: 'Translation created',
          after: translationCreatedAfter ?? undefined,
          before: translationCreatedBefore ?? undefined,
          onChangeAfter: onChangeTranslationCreatedAfter,
          onChangeBefore: onChangeTranslationCreatedBefore,
          quickRanges,
          onClear: () => {
            onChangeTranslationCreatedBefore(null);
            onChangeTranslationCreatedAfter(null);
          },
          clearLabel: 'Clear dates',
        },
        {
          kind: 'size',
          label: 'Result size limit',
          options: worksetPresets,
          value: worksetSize,
          onChange: onChangeWorksetSize,
          min: WORKSET_SIZE_MIN,
        },
      ]}
    />
  );
}
