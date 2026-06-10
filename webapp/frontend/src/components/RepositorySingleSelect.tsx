import type { RepositorySelectionOption } from '../utils/repositorySelection';
import { SingleSelectDropdown } from './SingleSelectDropdown';

type Props = {
  options: RepositorySelectionOption[];
  value: number | null;
  onChange: (next: number | null) => void;
  className?: string;
  disabled?: boolean;
  placeholder?: string;
  buttonAriaLabel?: string;
};

export function RepositorySingleSelect({
  options,
  value,
  onChange,
  className,
  disabled = false,
  placeholder = 'Select repository',
  buttonAriaLabel,
}: Props) {
  return (
    <SingleSelectDropdown<number>
      label="Repository"
      options={options.map((option) => ({
        value: option.id,
        label: option.name,
        ...(option.isGlossary ? { helper: 'Glossary repository' } : {}),
      }))}
      value={value}
      onChange={onChange}
      placeholder={placeholder}
      disabled={disabled}
      className={className}
      searchPlaceholder="Search repositories"
      noneLabel="Clear selection"
      buttonAriaLabel={buttonAriaLabel}
    />
  );
}
