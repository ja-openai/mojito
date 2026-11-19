import './settings-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Navigate } from 'react-router-dom';

import {
  fetchRepositoryTargetLocales,
  updateRepositoryTargetLocales,
} from '../../api/repository-settings';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import { RepositorySingleSelect } from '../../components/RepositorySingleSelect';
import { useLocales } from '../../hooks/useLocales';
import { REPOSITORIES_QUERY_KEY, useRepositories } from '../../hooks/useRepositories';
import { useUser } from '../../hooks/useUser';
import { hasSameSet } from '../../utils/arraySelection';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import { useRepositorySelectionOptions } from '../../utils/repositorySelection';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

const EMPTY_LOCALES: string[] = [];

export function AdminRepositorySettingsPage() {
  const user = useUser();
  const [repositoryId, setRepositoryId] = useState<number | null>(null);
  if (user.role !== 'ROLE_ADMIN') {
    return <Navigate to="/settings/me" replace />;
  }
  return (
    <RepositorySettingsForm
      key={repositoryId ?? 'none'}
      repositoryId={repositoryId}
      onRepositoryChange={setRepositoryId}
    />
  );
}

function RepositorySettingsForm({
  repositoryId,
  onRepositoryChange,
}: {
  repositoryId: number | null;
  onRepositoryChange: (id: number | null) => void;
}) {
  const queryClient = useQueryClient();
  const repositories = useRepositories();
  const repositoryOptions = useRepositorySelectionOptions(repositories.data);
  const locales = useLocales();
  const resolveLocaleName = useLocaleDisplayNameResolver();
  const queryKey = ['repository-target-locales', repositoryId];
  const settings = useQuery({
    queryKey,
    queryFn: () => fetchRepositoryTargetLocales(repositoryId as number),
    enabled: repositoryId !== null,
    staleTime: 30_000,
  });
  const [draft, setDraft] = useState<string[] | null>(null);
  const selectedTags = draft ?? settings.data?.targetLocaleTags ?? EMPTY_LOCALES;
  const dirty =
    settings.data !== undefined && !hasSameSet(selectedTags, settings.data.targetLocaleTags);
  const sourceTag = settings.data?.sourceLocaleTag;
  const localeOptions = useMemo(
    () =>
      [...new Set([...(locales.data ?? []).map((locale) => locale.bcp47Tag), ...selectedTags])]
        .filter((tag) => tag !== sourceTag)
        .sort((first, second) => first.localeCompare(second))
        .map((tag) => ({ tag, label: resolveLocaleName(tag) })),
    [locales.data, selectedTags, sourceTag, resolveLocaleName],
  );
  const save = useMutation({
    mutationFn: (tags: string[]) => updateRepositoryTargetLocales(repositoryId as number, tags),
    onSuccess: async (saved) => {
      await queryClient.cancelQueries({ queryKey });
      queryClient.setQueryData(queryKey, saved);
      setDraft(null);
      void queryClient.invalidateQueries({ queryKey: REPOSITORIES_QUERY_KEY });
    },
  });
  const disabled = settings.data === undefined || locales.data === undefined || save.isPending;

  function changeRepository(next: number | null) {
    if (next === repositoryId || save.isPending) return;
    if (dirty && !window.confirm('Discard unsaved locale changes for this repository?')) return;
    onRepositoryChange(next);
  }

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to system settings"
        context="System settings"
        title="Repository settings"
      />
      <div className="settings-page">
        <div className="settings-field">
          <span className="settings-field__label">Repository</span>
          <RepositorySingleSelect
            options={repositoryOptions}
            value={repositoryId}
            onChange={changeRepository}
            disabled={repositories.isLoading || save.isPending}
            placeholder={repositories.isLoading ? 'Loading repositories…' : 'Select repository'}
            buttonAriaLabel="Repository"
            className="settings-repository-select"
          />
          {repositories.isError ? (
            <div className="settings-field">
              <p className="settings-hint is-error" role="alert">
                {repositories.error.message}
              </p>
              <div className="settings-actions">
                <button
                  type="button"
                  className="settings-button settings-button--ghost"
                  disabled={repositories.isFetching || save.isPending}
                  onClick={() => void repositories.refetch()}
                >
                  Retry repositories
                </button>
              </div>
            </div>
          ) : null}
        </div>
        {repositoryId === null ? (
          <p className="settings-hint">Select a repository to configure its settings.</p>
        ) : (
          <section className="settings-card" aria-labelledby="repository-locales-title">
            <div className="settings-card__header">
              <h2 id="repository-locales-title">Translation locales</h2>
            </div>
            <p className="settings-page__hint">
              Choose the target locales available for translation. Existing inheritance and
              translation requirements are preserved. New locales inherit from the source locale.
            </p>
            {sourceTag ? (
              <p className="settings-hint">
                Source locale: {resolveLocaleName(sourceTag)} (<code>{sourceTag}</code>)
              </p>
            ) : null}
            <div className="settings-field">
              <LocaleMultiSelect
                label="Target locales"
                options={localeOptions}
                selectedTags={selectedTags}
                onChange={(tags) => {
                  setDraft(tags);
                  save.reset();
                }}
                disabled={disabled}
                showAllSelectedSummary={false}
              />
              <p className="settings-hint">
                Leave empty to remove all target locales. To remove an inherited parent locale,
                remove its dependent locales too.
              </p>
            </div>
            {settings.isLoading || locales.isLoading ? (
              <p className="settings-hint" role="status">
                Loading translation locales…
              </p>
            ) : null}
            {settings.isError || locales.isError ? (
              <div className="settings-field">
                <p className="settings-hint is-error" role="alert">
                  {settings.error?.message ?? locales.error?.message ?? 'Could not load locales.'}
                </p>
                <div className="settings-actions">
                  <button
                    type="button"
                    className="settings-button settings-button--ghost"
                    disabled={settings.isFetching || locales.isFetching || save.isPending}
                    onClick={() => {
                      if (settings.isError) void settings.refetch();
                      if (locales.isError) void locales.refetch();
                    }}
                  >
                    Retry locales
                  </button>
                </div>
              </div>
            ) : null}
            {save.isError ? (
              <p className="settings-hint is-error" role="alert">
                {save.error.message}
              </p>
            ) : null}
            <div className="settings-actions">
              <button
                type="button"
                className="settings-button settings-button--primary"
                disabled={disabled || !dirty}
                onClick={() => save.mutate(selectedTags)}
              >
                {save.isPending ? 'Saving…' : 'Save changes'}
              </button>
              <button
                type="button"
                className="settings-button settings-button--ghost"
                disabled={disabled || draft === null}
                onClick={() => {
                  setDraft(null);
                  save.reset();
                }}
              >
                Reset draft
              </button>
            </div>
            {save.isSuccess || dirty ? (
              <p className="settings-hint" role="status">
                {dirty ? 'You have unsaved changes.' : 'Changes saved.'}
              </p>
            ) : null}
          </section>
        )}
      </div>
    </div>
  );
}
