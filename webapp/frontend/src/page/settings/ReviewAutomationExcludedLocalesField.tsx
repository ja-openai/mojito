import { useMemo } from 'react';

import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import { useLocales } from '../../hooks/useLocales';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';

export function ReviewAutomationExcludedLocalesField({
  selectedTags,
  onChange,
  disabled = false,
}: {
  selectedTags: string[];
  onChange: (tags: string[]) => void;
  disabled?: boolean;
}) {
  const localesQuery = useLocales();
  const resolveLocaleName = useLocaleDisplayNameResolver();
  const options = useMemo(
    () =>
      [...new Set([...(localesQuery.data ?? []).map((locale) => locale.bcp47Tag), ...selectedTags])]
        .sort((first, second) => first.localeCompare(second))
        .map((tag) => ({ tag, label: resolveLocaleName(tag) })),
    [localesQuery.data, resolveLocaleName, selectedTags],
  );

  return (
    <div className="settings-field">
      <div className="settings-field__label">Excluded locales</div>
      <LocaleMultiSelect
        label="Excluded locales"
        options={options}
        selectedTags={selectedTags}
        onChange={onChange}
        className="settings-repository-select"
        disabled={disabled || localesQuery.isLoading}
        buttonAriaLabel="Select excluded locales"
        showSelectionPresets
      />
      <p className="settings-hint">
        These languages are skipped when this automation creates review projects. Leave empty to
        include all locales from its review features.
      </p>
      {localesQuery.isError ? (
        <p className="settings-hint is-error">Could not load available locales.</p>
      ) : null}
    </div>
  );
}
