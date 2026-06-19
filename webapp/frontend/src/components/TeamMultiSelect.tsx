import { hasSameSet } from '../utils/arraySelection';
import type { MultiSelectCustomAction, MultiSelectOption } from './MultiSelectChip';
import { MultiSelectChip } from './MultiSelectChip';

export type TeamMultiSelectOption = {
  id: number;
  name: string;
};

type Props = {
  label?: string;
  options: TeamMultiSelectOption[];
  selectedIds: number[];
  onChange: (next: number[]) => void;
  className?: string;
  disabled?: boolean;
  align?: 'left' | 'right';
  buttonAriaLabel?: string;
  placeholder?: string;
  emptyOptionsLabel?: string;
  allTeamsLabel?: string;
  myTeamIds?: number[];
  myTeamsLabel?: string;
  myTeamsAriaLabel?: string;
  showSelectionPresets?: boolean;
  customActions?: MultiSelectCustomAction[];
};

export function TeamMultiSelect({
  label = 'Teams',
  options,
  selectedIds,
  onChange,
  className,
  disabled = false,
  align = 'left',
  buttonAriaLabel,
  placeholder,
  emptyOptionsLabel,
  allTeamsLabel = 'All teams',
  myTeamIds,
  myTeamsLabel = 'My teams',
  myTeamsAriaLabel = 'Select my teams',
  showSelectionPresets = false,
  customActions,
}: Props) {
  const multiOptions: Array<MultiSelectOption<number>> = options.map((option) => ({
    value: option.id,
    label: option.name,
    searchText: `${option.name} ${option.id}`,
  }));
  const resolvedLabel = label ?? 'Teams';
  const allTeamIds = options.map((option) => option.id);
  const availableTeamIdSet = new Set(allTeamIds);
  const myTeamSelections = myTeamIds?.filter((id) => availableTeamIdSet.has(id)) ?? [];
  const isMyTeamSelectionActive =
    myTeamSelections.length > 0 && hasSameSet(selectedIds, myTeamSelections);
  const isAllTeamSelectionActive =
    !isMyTeamSelectionActive && allTeamIds.length > 0 && hasSameSet(selectedIds, allTeamIds);
  const quickActions: MultiSelectCustomAction[] | undefined = showSelectionPresets
    ? [
        {
          label: 'All',
          onClick: () => onChange(allTeamIds),
          disabled: allTeamIds.length === 0,
          active: isAllTeamSelectionActive,
          ariaLabel: 'Select all teams',
        },
        ...(myTeamSelections.length > 0
          ? [
              {
                label: myTeamsLabel,
                onClick: () => onChange(myTeamSelections),
                active: isMyTeamSelectionActive,
                ariaLabel: myTeamsAriaLabel,
              },
            ]
          : []),
        {
          label: 'None',
          onClick: () => onChange([]),
          active: selectedIds.length === 0,
          ariaLabel: 'Clear team selection',
        },
      ]
    : undefined;

  return (
    <MultiSelectChip
      className={className}
      label={resolvedLabel}
      options={multiOptions}
      selectedValues={selectedIds}
      onChange={onChange}
      placeholder={placeholder ?? resolvedLabel}
      emptyOptionsLabel={emptyOptionsLabel ?? 'No teams'}
      align={align}
      disabled={disabled}
      buttonAriaLabel={buttonAriaLabel ?? resolvedLabel}
      customActions={customActions}
      quickActions={quickActions}
      summaryFormatter={({ options: opts, selectedValues }) => {
        if (!opts.length) {
          return emptyOptionsLabel ?? 'No teams';
        }
        if (!selectedValues.length) {
          return placeholder ?? resolvedLabel;
        }
        if (isMyTeamSelectionActive) {
          return myTeamsLabel;
        }
        if (selectedValues.length === opts.length) {
          return allTeamsLabel;
        }
        if (selectedValues.length === 1) {
          const selectedSet = new Set(selectedValues);
          return opts
            .filter((option) => selectedSet.has(option.value))
            .map((option) => option.label)
            .join(', ');
        }
        return `${selectedValues.length} teams`;
      }}
    />
  );
}
