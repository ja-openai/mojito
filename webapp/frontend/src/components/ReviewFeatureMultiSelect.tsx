import { useMemo, useState } from 'react';

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
  enabledOnlyByDefault?: boolean;
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
  enabledOnlyByDefault = false,
}: Props) {
  const [showAllOptions, setShowAllOptions] = useState(!enabledOnlyByDefault);

  const visibleOptions = useMemo(
    () => (showAllOptions ? options : options.filter((option) => option.enabled)),
    [options, showAllOptions],
  );

  const selectedOptionIds = useMemo(() => new Set(selectedIds), [selectedIds]);

  const visibleOptionIds = useMemo(
    () => new Set(visibleOptions.map((option) => option.id)),
    [visibleOptions],
  );

  const hiddenSelectedOptions = useMemo(
    () =>
      options.filter(
        (option) => selectedOptionIds.has(option.id) && !visibleOptionIds.has(option.id),
      ),
    [options, selectedOptionIds, visibleOptionIds],
  );

  const multiOptions: Array<MultiSelectOption<number>> = useMemo(
    () =>
      [...visibleOptions, ...hiddenSelectedOptions].map((option) => ({
        value: option.id,
        label: option.enabled ? option.name : `${option.name} (disabled)`,
      })),
    [hiddenSelectedOptions, visibleOptions],
  );

  const resolvedLabel = label ?? 'Review features';
  const hasDisabledOptions = options.some((option) => !option.enabled);

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
      customActions={
        enabledOnlyByDefault && hasDisabledOptions
          ? [
              {
                label: showAllOptions ? 'Enabled only' : 'Show all',
                onClick: () => setShowAllOptions((current) => !current),
                ariaLabel: showAllOptions
                  ? 'Show enabled review features only'
                  : 'Show all review features',
              },
            ]
          : undefined
      }
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
