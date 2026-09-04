import { useMemo } from 'react';

import type { SearchAttribute, SearchType } from '../../api/text-units';
import {
  type FilterSection,
  MultiSectionFilterChip,
} from '../../components/filters/MultiSectionFilterChip';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import {
  RepositoryMultiSelect,
  type RepositoryMultiSelectOption,
} from '../../components/RepositoryMultiSelect';
import { TextUnitSearchControl } from '../../components/TextUnitSearchControl';
import { useUserPreferences } from '../../hooks/useUserPreferences';
import { getStandardDateQuickRanges } from '../../utils/dateQuickRanges';
import type { LocaleSelectionOption } from '../../utils/localeSelection';
import { filterMyLocales } from '../../utils/localeSelection';
import { resultSizePresets, WORKSET_SIZE_DEFAULT, WORKSET_SIZE_MIN } from './workbench-constants';
import type {
  GlossaryStatusFilterValue,
  StatusFilterValue,
  WorkbenchTextSearchCondition,
  WorkbenchTextSearchOperator,
} from './workbench-types';

type StatusFilterOption = { value: StatusFilterValue; label: string };
type GlossaryStatusFilterOption = { value: GlossaryStatusFilterValue; label: string };

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

const glossaryStatusFilterOptions: GlossaryStatusFilterOption[] = [
  { value: 'ALL', label: 'All glossary statuses' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'CANDIDATE', label: 'Candidate' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'DEPRECATED', label: 'Deprecated' },
];

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
  glossaryStatusFilter: GlossaryStatusFilterValue;
  hasSelectedGlossaryRepository: boolean;
  includeUsed: boolean;
  includeUnused: boolean;
  includeTranslate: boolean;
  includeDoNotTranslate: boolean;
  onChangeStatusFilter: (value: StatusFilterValue) => void;
  onChangeGlossaryStatusFilter: (value: GlossaryStatusFilterValue) => void;
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
  glossaryStatusFilter,
  hasSelectedGlossaryRepository,
  includeUsed,
  includeUnused,
  includeTranslate,
  includeDoNotTranslate,
  onChangeStatusFilter,
  onChangeGlossaryStatusFilter,
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
  const { data: preferences } = useUserPreferences();
  const preferredLocales = useMemo(() => preferences?.preferredLocales ?? [], [preferences]);

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
          showSelectionPresets
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
          showSelectionPresets
        />
      </div>

      <div className="workbench-header__search">
        <TextUnitSearchControl
          disabled={searchControlsDisabled}
          operator={textSearchOperator}
          conditions={hasCompoundSearch ? textSearchConditions : [primaryTextSearchCondition]}
          onChangeOperator={onChangeTextSearchOperator}
          onChangeCondition={(id, patch) => {
            if (hasCompoundSearch) {
              onChangeTextSearchCondition(id, patch);
              return;
            }
            if (patch.field !== undefined) {
              onChangeSearchAttribute(patch.field);
            }
            if (patch.searchType !== undefined) {
              onChangeSearchType(patch.searchType);
            }
            if (patch.value !== undefined) {
              onChangeSearchInput(patch.value);
            }
          }}
          onAddCondition={onAddTextSearchCondition}
          onRemoveCondition={onRemoveTextSearchCondition}
          onSubmitSearch={onSubmitSearch}
        />
      </div>

      <div className="workbench-header__right">
        <SearchFilter
          disabled={searchControlsDisabled}
          statusFilter={statusFilter}
          glossaryStatusFilter={glossaryStatusFilter}
          showGlossaryStatusFilter={hasSelectedGlossaryRepository}
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
          onChangeGlossaryStatusFilter={onChangeGlossaryStatusFilter}
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
  glossaryStatusFilter: GlossaryStatusFilterValue;
  showGlossaryStatusFilter: boolean;
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
  onChangeGlossaryStatusFilter: (value: GlossaryStatusFilterValue) => void;
  onChangeIncludeUsed: (value: boolean) => void;
  onChangeIncludeUnused: (value: boolean) => void;
  onChangeIncludeTranslate: (value: boolean) => void;
  onChangeIncludeDoNotTranslate: (value: boolean) => void;
};

function SearchFilter({
  disabled,
  statusFilter,
  glossaryStatusFilter,
  showGlossaryStatusFilter,
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
  onChangeGlossaryStatusFilter,
  onChangeIncludeUsed,
  onChangeIncludeUnused,
  onChangeIncludeTranslate,
  onChangeIncludeDoNotTranslate,
}: FilterChipProps) {
  const worksetPresets = resultSizePresets;
  const statusLabel =
    statusFilterOptions.find((option) => option.value === statusFilter)?.label ?? 'All statuses';
  const glossaryStatusLabel =
    glossaryStatusFilterOptions.find((option) => option.value === glossaryStatusFilter)?.label ??
    'All glossary statuses';
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
  if (showGlossaryStatusFilter && glossaryStatusFilter !== 'ALL') {
    summaryParts.push(glossaryStatusLabel);
  }
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
  const sections: FilterSection[] = [
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
  ];

  if (showGlossaryStatusFilter) {
    sections.splice(1, 0, {
      kind: 'radio',
      label: 'Glossary status',
      description:
        'Applies only to glossary repositories. Regular repository results are not filtered by glossary status.',
      options: glossaryStatusFilterOptions,
      value: glossaryStatusFilter,
      onChange: (value) => onChangeGlossaryStatusFilter(value as GlossaryStatusFilterValue),
    });
  }

  return (
    <MultiSectionFilterChip
      align="right"
      ariaLabel="Filter workbench results"
      disabled={disabled}
      summary={summary}
      sections={sections}
    />
  );
}
