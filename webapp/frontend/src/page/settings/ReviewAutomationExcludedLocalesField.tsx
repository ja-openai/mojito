import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { fetchReviewFeatureLocales } from '../../api/review-features';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import { useLocales } from '../../hooks/useLocales';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';

export function ReviewAutomationExcludedLocalesField({
  featureIds,
  selectedTags,
  onChange,
  disabled = false,
}: {
  featureIds: number[];
  selectedTags: string[];
  onChange: (tags: string[]) => void;
  disabled?: boolean;
}) {
  const [showAllLocales, setShowAllLocales] = useState(false);
  const localesQuery = useLocales();
  const sortedFeatureIds = [...new Set(featureIds)].sort((a, b) => a - b);
  const featureLocalesQuery = useQuery({
    queryKey: ['review-features', 'locales', sortedFeatureIds],
    queryFn: () => fetchReviewFeatureLocales(sortedFeatureIds),
    enabled: sortedFeatureIds.length > 0,
    staleTime: 30_000,
  });
  const resolveLocaleName = useLocaleDisplayNameResolver();
  const options = useMemo(
    () =>
      [
        ...new Set([
          ...(showAllLocales
            ? (localesQuery.data ?? []).map((locale) => locale.bcp47Tag)
            : (featureLocalesQuery.data ?? [])),
          ...selectedTags,
        ]),
      ]
        .sort((first, second) => first.localeCompare(second))
        .map((tag) => ({ tag, label: resolveLocaleName(tag) })),
    [featureLocalesQuery.data, localesQuery.data, resolveLocaleName, selectedTags, showAllLocales],
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
        disabled={disabled}
        buttonAriaLabel="Select excluded locales"
        showSelectionPresets
        showAllSelectedSummary={false}
        customActions={[
          {
            label: showAllLocales ? 'Show feature locales' : 'Show all locales',
            ariaLabel: showAllLocales
              ? 'Show locales used by selected review features'
              : 'Show all available locales',
            onClick: () => setShowAllLocales((current) => !current),
          },
        ]}
      />
      <p className="settings-hint">
        These languages are skipped when this automation creates review projects. Leave empty to
        include all locales from its review features.
      </p>
      <p className="settings-hint">
        {showAllLocales
          ? 'Showing all locales, including languages not yet enabled on these features.'
          : featureIds.length === 0
            ? 'Select review features to see their locales, or choose Show all locales.'
            : 'Showing locales used by the selected review features, plus any current exclusions.'}
      </p>
      {(showAllLocales ? localesQuery.isLoading : featureLocalesQuery.isLoading) ? (
        <p className="settings-hint">Loading locales…</p>
      ) : null}
      {(showAllLocales ? localesQuery.isError : featureLocalesQuery.isError) ? (
        <p className="settings-hint is-error">
          {showAllLocales
            ? 'Could not load available locales.'
            : 'Could not load feature locales. Choose Show all locales to use the full list.'}
        </p>
      ) : null}
    </div>
  );
}
