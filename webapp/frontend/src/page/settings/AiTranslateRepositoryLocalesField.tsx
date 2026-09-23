import { useState } from 'react';

import type { ApiRepository } from '../../api/repositories';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import { RepositorySingleSelect } from '../../components/RepositorySingleSelect';
import { useLocales } from '../../hooks/useLocales';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import { getNonRootRepositoryLocaleTags } from '../../utils/repositoryLocales';
import { useRepositorySelectionOptions } from '../../utils/repositorySelection';

export function AiTranslateRepositoryLocalesField({
  repositories,
  excludedLocaleTagsByRepositoryId,
  onChange,
  disabled = false,
}: {
  repositories: ApiRepository[];
  excludedLocaleTagsByRepositoryId: Record<string, string[]>;
  onChange: (next: Record<string, string[]>) => void;
  disabled?: boolean;
}) {
  const [repositoryId, setRepositoryId] = useState<number | null>(null);
  const repositoryOptions = useRepositorySelectionOptions(repositories);
  const repository = repositories.find((item) => item.id === repositoryId);

  return (
    <div className="settings-field">
      <div className="settings-field__label">Locale exclusions by repository</div>
      <p className="settings-hint">
        Saved exclusions apply to scheduled runs and Run now for this repository. Direct AI
        translation is unaffected.
      </p>
      <RepositorySingleSelect
        options={repositoryOptions}
        value={repository?.id ?? null}
        onChange={setRepositoryId}
        disabled={disabled}
        className="settings-repository-select"
        buttonAriaLabel="Choose repository for automatic AI locale exclusions"
      />
      {repository ? (
        <RepositoryLocaleExclusions
          key={repository.id}
          repository={repository}
          selectedTags={excludedLocaleTagsByRepositoryId[String(repository.id)] ?? []}
          onChange={(tags) => {
            const next = { ...excludedLocaleTagsByRepositoryId };
            if (tags.length) {
              next[String(repository.id)] = tags;
            } else {
              delete next[String(repository.id)];
            }
            onChange(next);
          }}
          disabled={disabled}
        />
      ) : (
        <p className="settings-hint">
          Select a repository in the automation scope to edit its locales.
        </p>
      )}
    </div>
  );
}

function RepositoryLocaleExclusions({
  repository,
  selectedTags,
  onChange,
  disabled,
}: {
  repository: ApiRepository;
  selectedTags: string[];
  onChange: (tags: string[]) => void;
  disabled: boolean;
}) {
  const localesQuery = useLocales();
  const resolveLocaleName = useLocaleDisplayNameResolver();
  const [showAllLocales, setShowAllLocales] = useState(false);
  const options = [
    ...new Set([
      ...getNonRootRepositoryLocaleTags(repository),
      ...(showAllLocales ? (localesQuery.data ?? []).map((locale) => locale.bcp47Tag) : []),
      ...selectedTags,
    ]),
  ]
    .sort((first, second) => first.localeCompare(second))
    .map((tag) => ({ tag, label: resolveLocaleName(tag) }));

  return (
    <>
      <LocaleMultiSelect
        label="Excluded locales"
        options={options}
        selectedTags={selectedTags}
        onChange={onChange}
        className="settings-repository-select"
        buttonAriaLabel="Select automatic AI translation excluded locales"
        disabled={disabled}
        showSelectionPresets
        showAllSelectedSummary={false}
        customActions={[
          {
            label: showAllLocales ? 'Show repository locales' : 'Show all locales',
            onClick: () => setShowAllLocales((current) => !current),
          },
        ]}
      />
      <p className="settings-hint">
        Leave empty to allow all locales. Tags match exactly: excluding <code>fr</code> does not
        exclude <code>fr-CA</code>.
      </p>
      <p className="settings-hint">
        {showAllLocales
          ? 'Showing all locales, including languages not yet enabled on this repository.'
          : 'Showing repository target locales, plus any current exclusions.'}
      </p>
      {showAllLocales && localesQuery.isLoading ? (
        <p className="settings-hint">Loading locales…</p>
      ) : null}
      {showAllLocales && localesQuery.isError ? (
        <p className="settings-hint is-error">Could not load all available locales.</p>
      ) : null}
    </>
  );
}
