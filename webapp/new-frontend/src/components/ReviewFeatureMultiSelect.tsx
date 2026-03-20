import type { MultiSelectOption } from './MultiSelectChip';
import { MultiSelectChip } from './MultiSelectChip';

export type ReviewFeatureMultiSelectOption = {
  id: number;
  name: string;
  enabled: boolean;
};

type Props = {
  label?: string;
  options: ReviewFeatureMultiSelectOption[];
  selectedIds: number[];
  onChange: (next: number[]) => void;
  className?: string;
  disabled?: boolean;
  align?: 'left' | 'right';
  buttonAriaLabel?: string;
};

export function ReviewFeatureMultiSelect({
  label = 'Review features',
  options,
  selectedIds,
  onChange,
  className,
  disabled = false,
  align = 'left',
  buttonAriaLabel,
}: Props) {
  const multiOptions: Array<MultiSelectOption<number>> = options.map((option) => ({
    value: option.id,
    label: option.enabled ? option.name : `${option.name} (disabled)`,
  }));

  const resolvedLabel = label ?? 'Review features';

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
      summaryFormatter={({ options: opts, selectedValues }) => {
        if (!opts.length) {
          return resolvedLabel;
        }
        if (!selectedValues.length) {
          return resolvedLabel;
        }
        if (selectedValues.length === opts.length) {
          return 'All review features';
        }
        if (selectedValues.length === 1) {
          const selectedSet = new Set(selectedValues);
          return opts
            .filter((option) => selectedSet.has(option.value))
            .map((option) => option.label)
            .join(', ');
        }
        return `${selectedValues.length} review features`;
      }}
    />
  );
}
