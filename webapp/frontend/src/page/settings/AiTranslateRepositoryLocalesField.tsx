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
  eligibleRepositoryIds,
  excludedLocaleTagsByRepositoryId,
  onChange,
  disabled = false,
}: {
  repositories: ApiRepository[];
  eligibleRepositoryIds: number[];
  excludedLocaleTagsByRepositoryId: Record<string, string[]>;
  onChange: (next: Record<string, string[]>) => void;
  disabled?: boolean;
}) {
  const [repositoryId, setRepositoryId] = useState<number | null>(null);
  const resolveLocaleName = useLocaleDisplayNameResolver();
  const exclusions = Object.entries(excludedLocaleTagsByRepositoryId).filter(
    ([, tags]) => tags.length > 0,
  );
  const repositoryOptions = useRepositorySelectionOptions(
    repositories.filter(
      (item) =>
        eligibleRepositoryIds.includes(item.id) &&
        !excludedLocaleTagsByRepositoryId[String(item.id)]?.length,
    ),
  );
  const getRepository = (id: number): ApiRepository =>
    repositories.find((item) => item.id === id) ?? { id, name: `Repository #${id}` };
  const repository =
    repositoryId !== null &&
    (eligibleRepositoryIds.includes(repositoryId) ||
      excludedLocaleTagsByRepositoryId[String(repositoryId)]?.length)
      ? getRepository(repositoryId)
      : null;

  const updateExclusions = (id: number, tags: string[]) => {
    const next = { ...excludedLocaleTagsByRepositoryId };
    if (tags.length) {
      next[String(id)] = tags;
    } else {
      delete next[String(id)];
    }
    onChange(next);
  };

  return (
    <div className="settings-field">
      <div className="settings-field__label">Locale exclusions by repository</div>
      <p className="settings-hint">
        Add exclusions for any number of repositories, then Save. Saved exclusions apply to
        scheduled runs and Run now. Direct AI translation is unaffected.
      </p>
      {exclusions.length ? (
        <ul className="settings-locale-exclusions" aria-label="Repository locale exclusions">
          {exclusions.map(([id, tags]) => {
            const item = getRepository(Number(id));
            return (
              <li
                key={id}
                className="settings-locale-exclusions__row"
                aria-label={`Locale exclusions for ${item.name}`}
              >
                <div className="settings-locale-exclusions__details">
                  <span className="settings-field__label">{item.name}</span>
                  <div className="settings-locale-exclusions__tags">
                    {tags.map((tag) => (
                      <code key={tag} title={resolveLocaleName(tag)}>
                        {tag}
                      </code>
                    ))}
                  </div>
                  {!eligibleRepositoryIds.includes(item.id) ? (
                    <span className="settings-hint">
                      Inactive: outside the current repository scope.
                    </span>
                  ) : null}
                </div>
                <div className="settings-actions">
                  <button
                    type="button"
                    className="settings-button settings-button--ghost"
                    onClick={() => setRepositoryId(item.id)}
                    disabled={disabled}
                    aria-label={`Edit locale exclusions for ${item.name}`}
                  >
                    Edit
                  </button>
                  <button
                    type="button"
                    className="settings-button settings-button--ghost"
                    onClick={() => {
                      updateExclusions(item.id, []);
                      if (repositoryId === item.id) setRepositoryId(null);
                    }}
                    disabled={disabled}
                    aria-label={`Remove locale exclusions for ${item.name}`}
                  >
                    Remove
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      ) : (
        <p className="settings-hint">No locale exclusions configured.</p>
      )}
      <RepositorySingleSelect
        options={repositoryOptions}
        value={null}
        onChange={setRepositoryId}
        disabled={disabled || repositoryOptions.length === 0}
        placeholder="Add repository"
        className="settings-repository-select"
        buttonAriaLabel="Choose repository for automatic AI locale exclusions"
      />
      {repository ? (
        <div className="settings-field">
          <div className="settings-field__label">Edit exclusions for {repository.name}</div>
          <RepositoryLocaleExclusions
            key={repository.id}
            repository={repository}
            selectedTags={excludedLocaleTagsByRepositoryId[String(repository.id)] ?? []}
            onChange={(tags) => updateExclusions(repository.id, tags)}
            disabled={disabled}
          />
        </div>
      ) : (
        <p className="settings-hint">
          Add a repository in the automation scope or edit an existing exclusion above.
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
