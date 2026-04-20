import './settings-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';

import { fetchGlossary, updateGlossary } from '../../api/glossaries';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import { RepositoryMultiSelect } from '../../components/RepositoryMultiSelect';
import { useUser } from '../../components/RequireUser';
import { useLocales } from '../../hooks/useLocales';
import { useRepositories } from '../../hooks/useRepositories';
import { buildScopedGlossaryLocaleOptions } from '../../utils/glossaryLocaleScope';
import { buildGlossaryWorkbenchState } from '../../utils/glossaryWorkbench';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import { useRepositorySelectionOptions } from '../../utils/repositorySelection';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

const normalizeGlossaryName = (value: string) => value.trim().replace(/\s+/g, ' ');
type GlossaryScopeMode = 'GLOBAL' | 'SELECTED_REPOSITORIES';

export function AdminGlossaryDetailPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();
  const params = useParams<{ glossaryId?: string }>();
  const { data: repositories } = useRepositories();
  const { data: locales, isLoading: localesLoading, isError: localesError } = useLocales();
  const resolveLocaleDisplayName = useLocaleDisplayNameResolver();
  const repositoryOptions = useRepositorySelectionOptions(repositories ?? []);

  const [nameDraft, setNameDraft] = useState('');
  const [descriptionDraft, setDescriptionDraft] = useState('');
  const [enabledDraft, setEnabledDraft] = useState(true);
  const [priorityDraft, setPriorityDraft] = useState('0');
  const [scopeModeDraft, setScopeModeDraft] = useState<GlossaryScopeMode>('GLOBAL');
  const [localeTagsDraft, setLocaleTagsDraft] = useState<string[]>([]);
  const [repositoryIdsDraft, setRepositoryIdsDraft] = useState<number[]>([]);
  const [excludedRepositoryIdsDraft, setExcludedRepositoryIdsDraft] = useState<number[]>([]);
  const [showAllLocaleOptions, setShowAllLocaleOptions] = useState(false);
  const [statusNotice, setStatusNotice] = useState<{
    kind: 'success' | 'error';
    message: string;
  } | null>(null);

  const parsedGlossaryId = useMemo(() => {
    const raw = params.glossaryId?.trim();
    if (!raw) {
      return null;
    }
    const next = Number(raw);
    return Number.isInteger(next) && next > 0 ? next : null;
  }, [params.glossaryId]);

  const glossaryQuery = useQuery({
    queryKey: ['glossary', parsedGlossaryId],
    queryFn: () => fetchGlossary(parsedGlossaryId as number),
    enabled: isAdmin && parsedGlossaryId != null,
    staleTime: 30_000,
  });

  const updateMutation = useMutation({
    mutationFn: (payload: {
      name: string;
      description?: string | null;
      enabled: boolean;
      priority: number;
      scopeMode: GlossaryScopeMode;
      localeTags: string[];
      repositoryIds: number[];
      excludedRepositoryIds: number[];
    }) => updateGlossary(parsedGlossaryId as number, payload),
    onSuccess: async (updated) => {
      queryClient.setQueryData(['glossary', parsedGlossaryId], updated);
      await queryClient.invalidateQueries({ queryKey: ['glossaries'] });
      await queryClient.invalidateQueries({ queryKey: ['glossary', parsedGlossaryId] });
      setStatusNotice({
        kind: 'success',
        message: `Saved glossary ${updated.name}.`,
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to save glossary.',
      });
    },
  });

  useEffect(() => {
    const glossary = glossaryQuery.data;
    if (!glossary) {
      return;
    }
    setNameDraft(glossary.name);
    setDescriptionDraft(glossary.description ?? '');
    setEnabledDraft(glossary.enabled);
    setPriorityDraft(String(glossary.priority));
    setScopeModeDraft(glossary.scopeMode);
    setLocaleTagsDraft(glossary.localeTags);
    setRepositoryIdsDraft(
      glossary.repositories.map((repository) => repository.id).sort((a, b) => a - b),
    );
    setExcludedRepositoryIdsDraft(
      glossary.excludedRepositories.map((repository) => repository.id).sort((a, b) => a - b),
    );
    setShowAllLocaleOptions(false);
  }, [glossaryQuery.data]);

  const isDirty = useMemo(() => {
    const glossary = glossaryQuery.data;
    if (!glossary) {
      return false;
    }
    if (normalizeGlossaryName(nameDraft) !== glossary.name) {
      return true;
    }
    if ((descriptionDraft.trim() || null) !== (glossary.description ?? null)) {
      return true;
    }
    if (enabledDraft !== glossary.enabled) {
      return true;
    }
    if (priorityDraft.trim() !== String(glossary.priority)) {
      return true;
    }
    if (scopeModeDraft !== glossary.scopeMode) {
      return true;
    }
    const currentLocaleTags = [...glossary.localeTags].sort((a, b) =>
      a.localeCompare(b, undefined, { sensitivity: 'base' }),
    );
    const nextLocaleTags = [...localeTagsDraft].sort((a, b) =>
      a.localeCompare(b, undefined, { sensitivity: 'base' }),
    );
    if (
      currentLocaleTags.length !== nextLocaleTags.length ||
      currentLocaleTags.some(
        (tag, index) => tag.toLowerCase() !== nextLocaleTags[index]?.toLowerCase(),
      )
    ) {
      return true;
    }

    const currentRepositoryIds = glossary.repositories
      .map((repository) => repository.id)
      .sort((a, b) => a - b);
    if (
      currentRepositoryIds.length !== repositoryIdsDraft.length ||
      currentRepositoryIds.some((id, index) => id !== repositoryIdsDraft[index])
    ) {
      return true;
    }

    const currentExcludedIds = glossary.excludedRepositories
      .map((repository) => repository.id)
      .sort((a, b) => a - b);
    if (currentExcludedIds.length !== excludedRepositoryIdsDraft.length) {
      return true;
    }
    return currentExcludedIds.some((id, index) => id !== excludedRepositoryIdsDraft[index]);
  }, [
    descriptionDraft,
    enabledDraft,
    excludedRepositoryIdsDraft,
    glossaryQuery.data,
    localeTagsDraft,
    nameDraft,
    priorityDraft,
    repositoryIdsDraft,
    scopeModeDraft,
  ]);

  const sortedRepositoryOptions = useMemo(
    () =>
      [...repositoryOptions].sort((first, second) =>
        first.name.localeCompare(second.name, undefined, { sensitivity: 'base' }),
      ),
    [repositoryOptions],
  );

  const localeOptions = useMemo(
    () =>
      (locales ?? [])
        .map((locale) => ({
          tag: locale.bcp47Tag,
          label: resolveLocaleDisplayName(locale.bcp47Tag),
        }))
        .sort((first, second) =>
          first.label.localeCompare(second.label, undefined, { sensitivity: 'base' }),
        ),
    [locales, resolveLocaleDisplayName],
  );

  const scopedLocaleOptionsState = useMemo(
    () =>
      buildScopedGlossaryLocaleOptions({
        allOptions: localeOptions,
        repositories: repositories ?? [],
        scopeMode: scopeModeDraft,
        repositoryIds: repositoryIdsDraft,
        excludedRepositoryIds: excludedRepositoryIdsDraft,
        selectedTags: localeTagsDraft,
        backingRepositoryId: glossaryQuery.data?.backingRepository.id ?? null,
      }),
    [
      excludedRepositoryIdsDraft,
      glossaryQuery.data?.backingRepository.id,
      localeOptions,
      localeTagsDraft,
      repositories,
      repositoryIdsDraft,
      scopeModeDraft,
    ],
  );

  const visibleLocaleOptions =
    showAllLocaleOptions || !scopedLocaleOptionsState.isFiltered
      ? localeOptions
      : scopedLocaleOptionsState.options;

  const localeCustomActions = useMemo(() => {
    if (!scopedLocaleOptionsState.isFiltered) {
      return undefined;
    }
    return [
      {
        label: showAllLocaleOptions ? 'Show scoped locales' : 'Show all locales',
        onClick: () => setShowAllLocaleOptions((previous) => !previous),
        ariaLabel: showAllLocaleOptions
          ? 'Show locales used by repositories in scope'
          : 'Show all available locales',
      },
    ];
  }, [scopedLocaleOptionsState.isFiltered, showAllLocaleOptions]);

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  if (parsedGlossaryId == null) {
    return <Navigate to="/glossaries" replace />;
  }

  const handleSave = () => {
    const normalizedName = normalizeGlossaryName(nameDraft);
    if (!normalizedName) {
      setStatusNotice({ kind: 'error', message: 'Glossary name is required.' });
      return;
    }
    const priority = Number.parseInt(priorityDraft.trim() || '0', 10);
    if (!Number.isFinite(priority)) {
      setStatusNotice({ kind: 'error', message: 'Priority must be a whole number.' });
      return;
    }
    updateMutation.mutate({
      name: normalizedName,
      description: descriptionDraft.trim() || null,
      enabled: enabledDraft,
      priority,
      scopeMode: scopeModeDraft,
      localeTags: localeTagsDraft,
      repositoryIds: [...repositoryIdsDraft].sort((a, b) => a - b),
      excludedRepositoryIds: [...excludedRepositoryIdsDraft].sort((a, b) => a - b),
    });
  };

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/glossaries"
        backLabel="Back to glossaries"
        context="Glossaries"
        title={
          glossaryQuery.data?.name
            ? `${glossaryQuery.data.name} settings`
            : `Glossary #${parsedGlossaryId} settings`
        }
      />
      <div className="settings-page settings-page--wide">
        <section className="settings-card">
          {glossaryQuery.isError ? (
            <p className="settings-hint is-error">
              {glossaryQuery.error instanceof Error
                ? glossaryQuery.error.message
                : 'Could not load glossary.'}
            </p>
          ) : glossaryQuery.isLoading ? (
            <p className="settings-hint">Loading glossary…</p>
          ) : (
            <>
              <div className="settings-field">
                <label className="settings-field__label" htmlFor="glossary-name">
                  Name
                </label>
                <input
                  id="glossary-name"
                  type="text"
                  className="settings-input"
                  value={nameDraft}
                  onChange={(event) => {
                    setNameDraft(event.target.value);
                    setStatusNotice(null);
                  }}
                />
              </div>
              <div className="settings-field">
                <label className="settings-field__label" htmlFor="glossary-description">
                  Description
                </label>
                <textarea
                  id="glossary-description"
                  className="settings-input"
                  value={descriptionDraft}
                  onChange={(event) => {
                    setDescriptionDraft(event.target.value);
                    setStatusNotice(null);
                  }}
                />
              </div>
              <div className="settings-grid settings-grid--two-column">
                <div className="settings-field">
                  <label className="settings-field__label" htmlFor="glossary-priority">
                    Priority
                  </label>
                  <input
                    id="glossary-priority"
                    type="number"
                    className="settings-input"
                    value={priorityDraft}
                    onChange={(event) => {
                      setPriorityDraft(event.target.value);
                      setStatusNotice(null);
                    }}
                  />
                </div>
                <div className="settings-field">
                  <label className="settings-field__label" htmlFor="glossary-scope-mode">
                    Scope
                  </label>
                  <select
                    id="glossary-scope-mode"
                    className="settings-input"
                    value={scopeModeDraft}
                    onChange={(event) => {
                      setScopeModeDraft(event.target.value as GlossaryScopeMode);
                      setStatusNotice(null);
                    }}
                  >
                    <option value="GLOBAL">Global</option>
                    <option value="SELECTED_REPOSITORIES">Selected repositories</option>
                  </select>
                </div>
              </div>
              <div className="settings-field">
                <label className="settings-toggle">
                  <input
                    type="checkbox"
                    checked={enabledDraft}
                    onChange={(event) => {
                      setEnabledDraft(event.target.checked);
                      setStatusNotice(null);
                    }}
                  />
                  <span>Enable this glossary</span>
                </label>
              </div>
              <div className="settings-field">
                <div className="settings-field__header">
                  <div className="settings-field__label">
                    {scopeModeDraft === 'GLOBAL'
                      ? 'Excluded repositories'
                      : 'Selected repositories'}
                  </div>
                </div>
                <RepositoryMultiSelect
                  label={
                    scopeModeDraft === 'GLOBAL' ? 'Excluded repositories' : 'Selected repositories'
                  }
                  options={sortedRepositoryOptions}
                  selectedIds={
                    scopeModeDraft === 'GLOBAL' ? excludedRepositoryIdsDraft : repositoryIdsDraft
                  }
                  onChange={(next) => {
                    if (scopeModeDraft === 'GLOBAL') {
                      setExcludedRepositoryIdsDraft([...next].sort((a, b) => a - b));
                    } else {
                      setRepositoryIdsDraft([...next].sort((a, b) => a - b));
                    }
                    setStatusNotice(null);
                  }}
                  className="settings-repository-select"
                  buttonAriaLabel="Select repositories for glossary"
                />
                <p className="settings-hint">
                  {scopeModeDraft === 'GLOBAL'
                    ? 'Global glossaries apply everywhere unless a repository is excluded.'
                    : 'Selected glossaries apply only to the repositories listed here.'}
                </p>
              </div>
              <div className="settings-field">
                <div className="settings-field__header">
                  <div className="settings-field__label">Locales</div>
                </div>
                <LocaleMultiSelect
                  label="Locales"
                  options={visibleLocaleOptions}
                  selectedTags={localeTagsDraft}
                  onChange={(next) => {
                    setLocaleTagsDraft(next);
                    setStatusNotice(null);
                  }}
                  className="settings-locale-select"
                  align="right"
                  buttonAriaLabel="Select glossary locales"
                  disabled={localesLoading || localesError}
                  customActions={localeCustomActions}
                />
                <p className="settings-hint">
                  Pick the glossary target locales explicitly. The source locale is managed by the
                  backing repository.
                </p>
                {localesLoading ? <p className="settings-hint">Loading locales…</p> : null}
                {localesError ? (
                  <p className="settings-hint is-error">Could not load locales.</p>
                ) : null}
              </div>
              <div className="settings-field">
                <div className="settings-field__header">
                  <div className="settings-field__label">Backing repository</div>
                </div>
                <Link
                  to="/workbench"
                  state={buildGlossaryWorkbenchState({
                    glossaryId: glossaryQuery.data?.id,
                    glossaryName: glossaryQuery.data?.name,
                    backingRepositoryId: glossaryQuery.data?.backingRepository.id ?? 0,
                    backingRepositoryName: glossaryQuery.data?.backingRepository.name,
                    assetPath: glossaryQuery.data?.assetPath,
                    localeTags: glossaryQuery.data?.localeTags,
                  })}
                  className="settings-table__link"
                >
                  {glossaryQuery.data?.backingRepository.name}
                </Link>
              </div>
              {statusNotice ? (
                <p className={`settings-hint${statusNotice.kind === 'error' ? ' is-error' : ''}`}>
                  {statusNotice.message}
                </p>
              ) : null}
              <div className="settings-card__footer">
                <div className="settings-actions">
                  <button
                    type="button"
                    className="settings-button settings-button--primary"
                    onClick={handleSave}
                    disabled={updateMutation.isPending || !isDirty}
                  >
                    Save
                  </button>
                </div>
              </div>
            </>
          )}
        </section>
      </div>
    </div>
  );
}
