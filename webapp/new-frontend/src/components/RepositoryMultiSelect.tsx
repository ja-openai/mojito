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
  summaryFormatter,
}: Props) {
  const multiOptions: Array<MultiSelectOption<number>> = options.map((option) => ({
    value: option.id,
    label: option.name,
  }));

  const resolvedLabel = label ?? 'Repositories';

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
      summaryFormatter={({ options: opts, selectedValues }) => {
        const defaultSummary = (() => {
          if (!opts.length) {
            return resolvedLabel;
          }
          if (!selectedValues.length) {
            return resolvedLabel;
          }
          if (selectedValues.length === opts.length) {
            return 'All repositories';
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
