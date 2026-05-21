import type { RepositorySelectionOption } from '../utils/repositorySelection';
import type { MultiSelectCustomAction, MultiSelectOption } from './MultiSelectChip';
import { MultiSelectChip } from './MultiSelectChip';
export type { RepositorySelectionOption as RepositoryMultiSelectOption } from '../utils/repositorySelection';

type Props = {
  label?: string;
  options: RepositorySelectionOption[];
  selectedIds: number[];
  onChange: (next: number[]) => void;
  className?: string;
  disabled?: boolean;
  align?: 'left' | 'right';
  buttonAriaLabel?: string;
  customActions?: MultiSelectCustomAction[];
  showSelectionPresets?: boolean;
  summaryFormatter?: (args: {
    options: RepositorySelectionOption[];
    selectedIds: number[];
    defaultSummary: string;
  }) => string;
};

export function RepositoryMultiSelect({
  label = 'Repositories',
  options,
  selectedIds,
  onChange,
  className,
  disabled = false,
  align = 'left',
  buttonAriaLabel,
  customActions,
  showSelectionPresets = false,
  summaryFormatter,
}: Props) {
  const multiOptions: Array<MultiSelectOption<number>> = options.map((option) => ({
    value: option.id,
    label: option.name,
  }));

  const resolvedLabel = label ?? 'Repositories';
  const allRepositoryIds = options.map((option) => option.id);
  const productRepositoryIds = options
    .filter((option) => !option.isGlossary)
    .map((option) => option.id);
  const glossaryRepositoryIds = options
    .filter((option) => option.isGlossary)
    .map((option) => option.id);

  const hasSameIds = (left: number[], right: number[]) => {
    if (left.length !== right.length) {
      return false;
    }
    const rightSet = new Set(right);
    return left.every((id) => rightSet.has(id));
  };

  const quickActions: MultiSelectCustomAction[] | undefined = showSelectionPresets
    ? [
        {
          label: 'All',
          onClick: () => onChange(allRepositoryIds),
          disabled: allRepositoryIds.length === 0,
          active: allRepositoryIds.length > 0 && hasSameIds(selectedIds, allRepositoryIds),
          ariaLabel: 'Select all repositories',
        },
        {
          label: 'Repositories',
          onClick: () => onChange(productRepositoryIds),
          disabled: productRepositoryIds.length === 0,
          active: productRepositoryIds.length > 0 && hasSameIds(selectedIds, productRepositoryIds),
          ariaLabel: 'Select repositories excluding glossaries',
        },
        {
          label: 'Glossaries',
          onClick: () => onChange(glossaryRepositoryIds),
          disabled: glossaryRepositoryIds.length === 0,
          active:
            glossaryRepositoryIds.length > 0 && hasSameIds(selectedIds, glossaryRepositoryIds),
          ariaLabel: 'Select glossary backing repositories only',
        },
        {
          label: 'None',
          onClick: () => onChange([]),
          active: selectedIds.length === 0,
          ariaLabel: 'Clear repository selection',
        },
      ]
    : undefined;

  return (
    <MultiSelectChip
      className={className}
      label={label}
      options={multiOptions}
      selectedValues={selectedIds}
      onChange={onChange}
      placeholder={resolvedLabel}
      emptyOptionsLabel={resolvedLabel}
      align={align}
      disabled={disabled}
      buttonAriaLabel={buttonAriaLabel ?? resolvedLabel}
      customActions={customActions}
      quickActions={quickActions}
      summaryFormatter={({ options: opts, selectedValues }) => {
        const defaultSummary = (() => {
          if (!opts.length) {
            return resolvedLabel;
          }
          if (!selectedValues.length) {
            if (showSelectionPresets) {
              return 'No repositories';
            }
            return resolvedLabel;
          }
          if (selectedValues.length === opts.length) {
            return 'All repositories';
          }
          if (
            showSelectionPresets &&
            productRepositoryIds.length > 0 &&
            hasSameIds(selectedValues, productRepositoryIds)
          ) {
            return 'Repositories';
          }
          if (
            showSelectionPresets &&
            glossaryRepositoryIds.length > 0 &&
            hasSameIds(selectedValues, glossaryRepositoryIds)
          ) {
            return 'Glossaries';
          }
          if (selectedValues.length === 1) {
            const selectedSet = new Set(selectedValues);
            return opts
              .filter((option) => selectedSet.has(option.value))
              .map((option) => option.label)
              .join(', ');
          }
          return `${selectedValues.length} repositories`;
        })();
        if (summaryFormatter) {
          return summaryFormatter({
            options,
            selectedIds: selectedValues,
            defaultSummary,
          });
        }
        return defaultSummary;
      }}
    />
  );
}
