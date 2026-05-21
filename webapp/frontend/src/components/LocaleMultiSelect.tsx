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
  showSelectionPresets?: boolean;
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
  showSelectionPresets = false,
}: Props) {
  const multiOptions: Array<MultiSelectOption<string>> = options.map((option) => ({
    value: option.tag,
    label: option.label,
  }));
  const allLocaleTags = options.map((option) => option.tag);

  const hasSameTags = (left: string[], right: string[]) => {
    if (left.length !== right.length) {
      return false;
    }
    const rightSet = new Set(right.map((tag) => tag.toLowerCase()));
    return left.every((tag) => rightSet.has(tag.toLowerCase()));
  };

  const availableLocaleSet = new Set(options.map((option) => option.tag.toLowerCase()));
  const myLocaleSelections =
    myLocaleTags?.filter((tag) => availableLocaleSet.has(tag.toLowerCase())) ?? [];
  const isAllLocaleSelectionActive =
    allLocaleTags.length > 0 && hasSameTags(selectedTags, allLocaleTags);
  const isMyLocaleSelectionActive =
    !isAllLocaleSelectionActive &&
    myLocaleSelections.length > 0 &&
    hasSameTags(selectedTags, myLocaleSelections);

  const localeCustomActions: MultiSelectCustomAction[] =
    myLocaleSelections.length > 0 && !showSelectionPresets
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
  const quickActions: MultiSelectCustomAction[] | undefined = showSelectionPresets
    ? [
        {
          label: 'All',
          onClick: () => onChange(allLocaleTags),
          disabled: allLocaleTags.length === 0,
          active: isAllLocaleSelectionActive,
          ariaLabel: 'Select all locales',
        },
        ...(myLocaleSelections.length > 0
          ? [
              {
                label: myLocalesLabel,
                onClick: () => onChange(myLocaleSelections),
                active: isMyLocaleSelectionActive,
                ariaLabel: myLocalesAriaLabel,
              },
            ]
          : []),
        {
          label: 'None',
          onClick: () => onChange([]),
          active: selectedTags.length === 0,
          ariaLabel: 'Clear locale selection',
        },
      ]
    : undefined;

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
      quickActions={quickActions}
      summaryFormatter={({ options: opts, selectedValues }) => {
        if (!opts.length) {
          return label ?? 'Locales';
        }
        if (!selectedValues.length) {
          if (showSelectionPresets) {
            return 'No locales';
          }
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
