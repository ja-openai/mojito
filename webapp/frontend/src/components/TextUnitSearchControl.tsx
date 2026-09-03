import './text-unit-search-control.css';

import type { ReactNode } from 'react';

import type { SearchAttribute, SearchType, TextSearchOperator } from '../api/text-units';
import { CONTAINS_SEARCH_HELPER, ILIKE_SEARCH_HELPER } from '../utils/likeSearch';
import { MultiSectionFilterChip } from './filters/MultiSectionFilterChip';
import { SearchControl } from './SearchControl';
import { SingleSelectDropdown } from './SingleSelectDropdown';

export type TextSearchCondition = {
  id: string;
  field: SearchAttribute;
  searchType: SearchType;
  value: string;
};

type SearchAttributeOption = { value: SearchAttribute; label: string; helper?: string };
type SearchTypeOption = { value: SearchType; label: string; helper?: string };

const searchAttributeOptions: SearchAttributeOption[] = [
  { value: 'target', label: 'Translation' },
  { value: 'source', label: 'Source' },
  { value: 'comment', label: 'Comment' },
  { value: 'stringId', label: 'String ID' },
  { value: 'asset', label: 'Asset path' },
  { value: 'location', label: 'Location' },
  { value: 'pluralFormOther', label: 'Plural (other)' },
  { value: 'tmTextUnitIds', label: 'TextUnit IDs' },
];

const searchTypeOptions: SearchTypeOption[] = [
  { value: 'exact', label: 'Exact match', helper: 'Find only the full text you type' },
  {
    value: 'contains',
    label: 'Contains',
    helper: CONTAINS_SEARCH_HELPER,
  },
  {
    value: 'ilike',
    label: 'iLike',
    helper: ILIKE_SEARCH_HELPER,
  },
  {
    value: 'regex',
    label: 'Regex',
    helper: 'Advanced: use a regular expression, e.g. \\x{FFFF}, ^/$, .*, (?i)insensitive',
  },
];

type TextUnitSearchControlProps = {
  disabled: boolean;
  operator: TextSearchOperator;
  conditions: TextSearchCondition[];
  onChangeOperator: (value: TextSearchOperator) => void;
  onChangeCondition: (
    id: string,
    patch: Partial<Pick<TextSearchCondition, 'field' | 'searchType' | 'value'>>,
  ) => void;
  onAddCondition: () => void;
  onRemoveCondition: (id: string) => void;
  onSubmitSearch: () => void;
};

type TextSearchConditionControlProps = {
  disabled: boolean;
  condition: TextSearchCondition;
  leading: ReactNode;
  after?: ReactNode;
  trailing?: ReactNode;
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
    case 'comment':
      return 'Search comment';
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
  trailing,
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
        trailing={trailing}
      />
      {after ? <div className="workbench-searchrow__after">{after}</div> : null}
    </div>
  );
}

function getSearchConditionSummary(condition: Pick<TextSearchCondition, 'field' | 'searchType'>) {
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
  condition: Pick<TextSearchCondition, 'field' | 'searchType'>;
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
}: TextUnitSearchControlProps) {
  return (
    <div className="workbench-searchbuilder">
      <div className="workbench-searchbuilder__meta">
        <label className="workbench-searchbuilder__toggle">
          <span>Match</span>
          <SingleSelectDropdown<TextSearchOperator>
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

export function TextUnitSearchControl(props: TextUnitSearchControlProps) {
  const { disabled, conditions, onChangeCondition, onAddCondition, onSubmitSearch } = props;
  const primaryCondition = conditions[0];
  if (!primaryCondition) {
    return null;
  }

  return (
    <div className="workbench-searchpane">
      {conditions.length > 1 ? (
        <CompoundTextSearchBuilder {...props} />
      ) : (
        <TextSearchConditionControl
          disabled={disabled}
          condition={primaryCondition}
          className="workbench-searchcontrol"
          onChangeValue={(value) => onChangeCondition(primaryCondition.id, { value })}
          onSubmitSearch={onSubmitSearch}
          leading={
            <SearchConditionOptionsChip
              disabled={disabled}
              condition={primaryCondition}
              onChangeField={(field) => onChangeCondition(primaryCondition.id, { field })}
              onChangeSearchType={(searchType) =>
                onChangeCondition(primaryCondition.id, { searchType })
              }
            />
          }
          trailing={
            primaryCondition.value.trim().length > 0 ? (
              <button
                type="button"
                className="workbench-searchcontrol__add"
                onClick={onAddCondition}
                disabled={disabled}
                aria-label="Add search condition"
                title="Add search condition"
              >
                Add
              </button>
            ) : null
          }
        />
      )}
    </div>
  );
}
