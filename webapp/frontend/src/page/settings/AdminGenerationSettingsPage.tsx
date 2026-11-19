import './settings-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Navigate } from 'react-router-dom';

import {
  fetchGenerationExcludedLocales,
  updateGenerationExcludedLocales,
} from '../../api/generation-settings';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import { useLocales } from '../../hooks/useLocales';
import { useUser } from '../../hooks/useUser';
import { hasSameSet } from '../../utils/arraySelection';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

const QUERY_KEY = ['generation-excluded-locales'];
const EMPTY_LOCALES: string[] = [];

export function AdminGenerationSettingsPage() {
  const user = useUser();
  if (user.role !== 'ROLE_ADMIN') {
    return <Navigate to="/settings/me" replace />;
  }
  return <GenerationSettingsForm />;
}

function GenerationSettingsForm() {
  const queryClient = useQueryClient();
  const settings = useQuery({
    queryKey: QUERY_KEY,
    queryFn: fetchGenerationExcludedLocales,
    staleTime: 30_000,
  });
  const locales = useLocales();
  const resolveLocaleName = useLocaleDisplayNameResolver();
  const [draft, setDraft] = useState<string[] | null>(null);
  const selectedTags = draft ?? settings.data ?? EMPTY_LOCALES;
  const dirty = settings.data !== undefined && !hasSameSet(selectedTags, settings.data);
  const options = useMemo(
    () =>
      [...new Set([...(locales.data ?? []).map((locale) => locale.bcp47Tag), ...selectedTags])]
        .sort((first, second) => first.localeCompare(second))
        .map((tag) => ({ tag, label: resolveLocaleName(tag) })),
    [locales.data, resolveLocaleName, selectedTags],
  );
  const save = useMutation({
    mutationFn: updateGenerationExcludedLocales,
    onSuccess: async (saved) => {
      await queryClient.cancelQueries({ queryKey: QUERY_KEY });
      queryClient.setQueryData(QUERY_KEY, saved);
      setDraft(null);
    },
  });
  const disabled = settings.data === undefined || locales.data === undefined || save.isPending;

  function changeSelection(tags: string[]) {
    setDraft(tags);
    save.reset();
  }

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to system settings"
        context="System settings"
        title="Localized file generation"
      />
      <div className="settings-page">
        <section className="settings-card" aria-labelledby="generation-exclusions-title">
          <div className="settings-card__header">
            <h2 id="generation-exclusions-title">Excluded locales</h2>
          </div>
          <p className="settings-page__hint">
            Exclude locales from localized file generation across all repositories, including
            locales added to a repository later. Translations and review remain available. Existing
            generated files are not deleted.
          </p>
          <p className="settings-hint is-warning">
            Parallel pulls skip excluded locales. Other pulls fail if they request an excluded
            locale.
          </p>
          <div className="settings-field">
            <LocaleMultiSelect
              label="Excluded locales"
              buttonAriaLabel="Excluded locales"
              options={options}
              selectedTags={selectedTags}
              onChange={changeSelection}
              disabled={disabled}
              showAllSelectedSummary={false}
            />
            <p className="settings-hint">
              Leave empty to remove global exclusions. Repository-specific exclusions still apply.
            </p>
          </div>
          {settings.isLoading || locales.isLoading ? (
            <p className="settings-hint" role="status">
              Loading generation settings and locales…
            </p>
          ) : null}
          {settings.isError || locales.isError ? (
            <div className="settings-field">
              <p className="settings-hint is-error" role="alert">
                {settings.error?.message ?? locales.error?.message ?? 'Could not load settings.'}
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
                  Retry
                </button>
              </div>
            </div>
          ) : null}
          {save.isError ? (
            <p className="settings-hint is-error" role="alert">
              {save.error.message}
            </p>
          ) : null}
          <div className="settings-actions settings-actions--wrap">
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
            <button
              type="button"
              className="settings-button settings-button--ghost"
              disabled={disabled || selectedTags.length === 0}
              onClick={() => changeSelection([])}
            >
              Clear selection
            </button>
          </div>
          {save.isSuccess || dirty ? (
            <p className="settings-hint" role="status">
              {dirty ? 'You have unsaved changes.' : 'Changes saved.'}
            </p>
          ) : null}
        </section>
      </div>
    </div>
  );
}
