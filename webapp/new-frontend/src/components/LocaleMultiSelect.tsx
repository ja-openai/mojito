import type { MultiSelectCustomAction, MultiSelectOption } from './MultiSelectChip';
import { MultiSelectChip } from './MultiSelectChip';

export type LocaleOption = {
  tag: string;
  label: string;
};

type Props = {
  label?: string;
  options: LocaleOption[];
  selectedTags: string[];
  onChange: (next: string[]) => void;
  className?: string;
  disabled?: boolean;
  align?: 'left' | 'right';
  buttonAriaLabel?: string;
  myLocaleTags?: string[];
  myLocalesLabel?: string;
  myLocalesAriaLabel?: string;
  customActions?: MultiSelectCustomAction[];
};

export function LocaleMultiSelect({
  label = 'Locales',
  options,
  selectedTags,
  onChange,
  className,
  disabled = false,
  align = 'left',
  buttonAriaLabel,
  myLocaleTags,
  myLocalesLabel = 'My locales',
  myLocalesAriaLabel = 'Select your locales',
  customActions,
}: Props) {
  const multiOptions: Array<MultiSelectOption<string>> = options.map((option) => ({
    value: option.tag,
    label: option.label,
  }));

  const availableLocaleSet = new Set(options.map((option) => option.tag.toLowerCase()));
  const myLocaleSelections =
    myLocaleTags?.filter((tag) => availableLocaleSet.has(tag.toLowerCase())) ?? [];
  const isMyLocaleSelectionActive =
    myLocaleSelections.length > 0 &&
    myLocaleSelections.length === selectedTags.length &&
    myLocaleSelections.every((tag) =>
      selectedTags.map((value) => value.toLowerCase()).includes(tag.toLowerCase()),
    );

  const localeCustomActions: MultiSelectCustomAction[] =
    myLocaleSelections.length > 0
      ? [
          {
            label: myLocalesLabel,
            onClick: () => onChange(myLocaleSelections),
            disabled: isMyLocaleSelectionActive,
            ariaLabel: myLocalesAriaLabel,
          },
        ]
      : [];

  const mergedCustomActions = [...localeCustomActions, ...(customActions ?? [])];

  return (
    <MultiSelectChip
      label={label}
      options={multiOptions}
      selectedValues={selectedTags}
      onChange={onChange}
      placeholder={label}
      emptyOptionsLabel={label}
      className={className}
      align={align}
      disabled={disabled}
      buttonAriaLabel={buttonAriaLabel ?? label}
      customActions={mergedCustomActions.length > 0 ? mergedCustomActions : undefined}
      summaryFormatter={({ options: opts, selectedValues }) => {
        if (!opts.length) {
          return label ?? 'Locales';
        }
        if (!selectedValues.length) {
          return label ?? 'Locales';
        }
        if (isMyLocaleSelectionActive) {
          return myLocalesLabel;
        }
        if (selectedValues.length === opts.length) {
          return 'All locales';
        }
        if (selectedValues.length <= 2) {
          const selectedSet = new Set(selectedValues);
          return opts
            .filter((option) => selectedSet.has(option.value))
            .map((option) => option.label)
            .join(', ');
        }
        return `${selectedValues.length} locales`;
      }}
    />
  );
}
