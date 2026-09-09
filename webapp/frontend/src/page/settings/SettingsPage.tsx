import './settings-page.css';

import { useQuery } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { fetchTeams } from '../../api/teams';
import type { ApiUserPreferences, UserPreferencesPatch } from '../../api/userPreferences';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import { TeamMultiSelect, type TeamMultiSelectOption } from '../../components/TeamMultiSelect';
import { useRepositories } from '../../hooks/useRepositories';
import { useUser } from '../../hooks/useUser';
import { useSaveUserPreferences, useUserPreferences } from '../../hooks/useUserPreferences';
import { hasSameSet } from '../../utils/arraySelection';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import { buildLocaleOptionsFromRepositories } from '../../utils/localeSelection';
import { loadReviewProjectSearchEnabled } from '../../utils/reviewProjectSearchPreference';
import { loadVisibleTextEditorEnabled } from '../../utils/visibleTextEditorPreference';
import {
  getDefaultReviewProjectShortcutHelpPreference,
  loadReviewProjectShortcutHelpPreference,
  type ReviewProjectShortcutHelpPreference,
} from '../review-project/review-project-preferences';
import { loadDefaultReviewProjectTeamIds } from '../review-projects/review-projects-preferences';
import { WORKSET_SIZE_DEFAULT } from '../workbench/workbench-constants';
import { loadPreferredLocales, loadPreferredWorksetSize } from '../workbench/workbench-preferences';

function sameLocales(first: string[], second: string[]) {
  return (
    first.length === second.length &&
    first.every((locale, index) => locale.toLowerCase() === second[index].toLowerCase())
  );
}

type SettingsDraft = Omit<
  ApiUserPreferences,
  | 'initialized'
  | 'worksetSize'
  | 'shortcutHelp'
  | 'aiReviewProfile'
  | 'aiReviewAutomaticDisabled'
  | 'aiReviewReasoningEffort'
  | 'aiReviewPreset'
> & {
  worksetSize: string;
  shortcutHelp: ReviewProjectShortcutHelpPreference;
};

function toDraft(
  preferences: ApiUserPreferences,
  defaultShortcut: ReviewProjectShortcutHelpPreference,
): SettingsDraft {
  return {
    preferredLocales: preferences.preferredLocales,
    defaultReviewTeamIds: preferences.defaultReviewTeamIds,
    visibleTextEditorEnabled: preferences.visibleTextEditorEnabled,
    reviewProjectSearchEnabled: preferences.reviewProjectSearchEnabled,
    worksetSize: preferences.worksetSize == null ? '' : String(preferences.worksetSize),
    shortcutHelp: preferences.shortcutHelp ?? defaultShortcut,
  };
}

function sameDraft(a: SettingsDraft, b: SettingsDraft) {
  return (
    a.worksetSize === b.worksetSize &&
    sameLocales(a.preferredLocales, b.preferredLocales) &&
    a.shortcutHelp === b.shortcutHelp &&
    a.visibleTextEditorEnabled === b.visibleTextEditorEnabled &&
    a.reviewProjectSearchEnabled === b.reviewProjectSearchEnabled &&
    hasSameSet(a.defaultReviewTeamIds, b.defaultReviewTeamIds)
  );
}

export function SettingsPage() {
  const user = useUser();
  const preferencesQuery = useUserPreferences();
  if (!preferencesQuery.data) {
    return (
      <div role={preferencesQuery.isError ? 'alert' : 'status'}>
        {preferencesQuery.isError ? 'Could not load your settings.' : 'Loading settings…'}
        {preferencesQuery.isError ? (
          <button type="button" onClick={() => void preferencesQuery.refetch()}>
            Try again
          </button>
        ) : null}
      </div>
    );
  }
  return <SettingsForm key={user.username} preferences={preferencesQuery.data} />;
}

function SettingsForm({ preferences }: { preferences: ApiUserPreferences }) {
  const user = useUser();
  const username = user.username;
  const defaultShortcutHelpPreference = getDefaultReviewProjectShortcutHelpPreference(user.role);
  const canConfigureDefaultReviewTeams = user.role === 'ROLE_ADMIN' || user.role === 'ROLE_PM';
  const { data: repositories } = useRepositories();
  const teamsQuery = useQuery({
    queryKey: ['teams', 'settings-default-review-teams', username],
    queryFn: fetchTeams,
    enabled: canConfigureDefaultReviewTeams,
  });
  const savePreferences = useSaveUserPreferences();
  const resolveLocaleName = useLocaleDisplayNameResolver();
  const [form, setForm] = useState(() => {
    const saved = toDraft(preferences, defaultShortcutHelpPreference);
    let draft = saved;
    // Old browser values are only a draft. They become account settings on explicit Save.
    if (!preferences.initialized) {
      try {
        const workset = loadPreferredWorksetSize();
        draft = {
          worksetSize: workset == null ? '' : String(workset),
          preferredLocales: loadPreferredLocales(),
          shortcutHelp: loadReviewProjectShortcutHelpPreference(defaultShortcutHelpPreference),
          visibleTextEditorEnabled: loadVisibleTextEditorEnabled(username),
          reviewProjectSearchEnabled: loadReviewProjectSearchEnabled(username),
          defaultReviewTeamIds: canConfigureDefaultReviewTeams
            ? loadDefaultReviewProjectTeamIds(username)
            : [],
        };
      } catch {
        /* Browser storage may be unavailable; account settings still work. */
      }
    }
    return { saved, draft };
  });
  const [hasBrowserPreferences] = useState(() => !sameDraft(form.saved, form.draft));
  const [initializeOnSave, setInitializeOnSave] = useState(false);
  const [saveStatus, setSaveStatus] = useState<'idle' | 'saved' | 'error'>('idle');
  const { saved, draft } = form;
  const setDraft = <K extends keyof SettingsDraft>(key: K, value: SettingsDraft[K]) => {
    setForm((current) => ({ ...current, draft: { ...current.draft, [key]: value } }));
    setSaveStatus('idle');
  };

  const worksetDraft = draft.worksetSize;
  const preferredLocalesDraft = draft.preferredLocales;
  const defaultReviewTeamDraft = draft.defaultReviewTeamIds;
  const shortcutHelpDraft = draft.shortcutHelp;
  const visibleTextEditorDraft = draft.visibleTextEditorEnabled;
  const reviewProjectSearchDraft = draft.reviewProjectSearchEnabled;
  const savedPreferredLocales = saved.preferredLocales;
  const setWorksetDraft = (value: string) => setDraft('worksetSize', value);
  const setPreferredLocalesDraft = (value: string[]) => setDraft('preferredLocales', value);
  const setDefaultReviewTeamDraft = (value: number[]) => setDraft('defaultReviewTeamIds', value);
  const setShortcutHelpDraft = (value: ReviewProjectShortcutHelpPreference) =>
    setDraft('shortcutHelp', value);
  const setVisibleTextEditorDraft = (value: boolean) => setDraft('visibleTextEditorEnabled', value);
  const setReviewProjectSearchDraft = (value: boolean) =>
    setDraft('reviewProjectSearchEnabled', value);

  const localeOptions = useMemo(() => {
    const repositoryOptions = buildLocaleOptionsFromRepositories(
      repositories ?? [],
      resolveLocaleName,
    );
    const seen = new Set(repositoryOptions.map((option) => option.tag.toLowerCase()));
    const extraOptions = [...savedPreferredLocales, ...preferredLocalesDraft, ...user.userLocales]
      .filter((tag) => {
        const lower = tag.toLowerCase();
        if (seen.has(lower)) return false;
        seen.add(lower);
        return true;
      })
      .map((tag) => ({ tag, label: resolveLocaleName(tag) }));
    return [...repositoryOptions, ...extraOptions].sort((first, second) =>
      first.tag.localeCompare(second.tag, undefined, { sensitivity: 'base' }),
    );
  }, [
    preferredLocalesDraft,
    repositories,
    resolveLocaleName,
    savedPreferredLocales,
    user.userLocales,
  ]);
  const defaultReviewTeamOptions = useMemo<TeamMultiSelectOption[]>(() => {
    const seen = new Set<number>();
    return (teamsQuery.data ?? [])
      .map((team) => ({ id: team.id, name: team.name.trim() || `Team #${team.id}` }))
      .filter((option) => {
        if (!Number.isInteger(option.id) || option.id <= 0 || seen.has(option.id)) return false;
        seen.add(option.id);
        return true;
      })
      .sort((first, second) =>
        first.name.localeCompare(second.name, undefined, { sensitivity: 'base' }),
      );
  }, [teamsQuery.data]);
  const normalizeDraft = useCallback(
    (value: SettingsDraft): SettingsDraft => {
      if (!teamsQuery.isSuccess) return value;
      const available = new Set(defaultReviewTeamOptions.map((team) => team.id));
      return {
        ...value,
        defaultReviewTeamIds: value.defaultReviewTeamIds.filter((id) => available.has(id)),
      };
    },
    [defaultReviewTeamOptions, teamsQuery.isSuccess],
  );

  // Apply refreshed account values when the form becomes clean, preserving edits in progress.
  useEffect(() => {
    setForm((current) => {
      const nextSaved = normalizeDraft(current.saved);
      const nextDraft = normalizeDraft(current.draft);
      if (!initializeOnSave && sameDraft(nextSaved, nextDraft)) {
        const next = normalizeDraft(toDraft(preferences, defaultShortcutHelpPreference));
        return sameDraft(current.saved, next) && sameDraft(current.draft, next)
          ? current
          : { saved: next, draft: next };
      }
      return sameDraft(current.saved, nextSaved) && sameDraft(current.draft, nextDraft)
        ? current
        : { saved: nextSaved, draft: nextDraft };
    });
  }, [preferences, defaultShortcutHelpPreference, normalizeDraft, initializeOnSave, saved, draft]);

  const trimmedWorkset = worksetDraft.trim();
  const parsedWorkset = {
    value: trimmedWorkset ? Number(trimmedWorkset) : null,
    valid:
      !trimmedWorkset ||
      (Number.isInteger(Number(trimmedWorkset)) &&
        Number(trimmedWorkset) >= 1 &&
        Number(trimmedWorkset) <= 2147483647),
  };
  const worksetError = !parsedWorkset.valid ? 'Enter a whole number from 1 to 2147483647.' : null;
  const isDirty = initializeOnSave || !sameDraft(draft, saved);
  const canRestoreDefaults =
    trimmedWorkset !== '' ||
    preferredLocalesDraft.length > 0 ||
    shortcutHelpDraft !== defaultShortcutHelpPreference ||
    visibleTextEditorDraft ||
    reviewProjectSearchDraft ||
    (canConfigureDefaultReviewTeams && defaultReviewTeamDraft.length > 0);
  const isSaving = savePreferences.isPending;

  const handleSave = async () => {
    if (!parsedWorkset.valid || !isDirty || isSaving) return;
    const patch: UserPreferencesPatch = {};
    if (draft.worksetSize !== saved.worksetSize) patch.worksetSize = parsedWorkset.value;
    if (!sameLocales(draft.preferredLocales, saved.preferredLocales))
      patch.preferredLocales = draft.preferredLocales;
    if (draft.shortcutHelp !== saved.shortcutHelp)
      patch.shortcutHelp =
        draft.shortcutHelp === defaultShortcutHelpPreference ? null : draft.shortcutHelp;
    if (draft.visibleTextEditorEnabled !== saved.visibleTextEditorEnabled)
      patch.visibleTextEditorEnabled = draft.visibleTextEditorEnabled;
    if (draft.reviewProjectSearchEnabled !== saved.reviewProjectSearchEnabled)
      patch.reviewProjectSearchEnabled = draft.reviewProjectSearchEnabled;
    if (
      canConfigureDefaultReviewTeams &&
      !hasSameSet(draft.defaultReviewTeamIds, saved.defaultReviewTeamIds)
    )
      patch.defaultReviewTeamIds = draft.defaultReviewTeamIds;
    try {
      const response = await savePreferences.mutateAsync(patch);
      const next = normalizeDraft(toDraft(response, defaultShortcutHelpPreference));
      setInitializeOnSave(false);
      setForm({ saved: next, draft: next });
      setSaveStatus('saved');
    } catch {
      setSaveStatus('error');
    }
  };
  const handleDiscard = () => {
    const next = normalizeDraft(toDraft(preferences, defaultShortcutHelpPreference));
    setInitializeOnSave(false);
    setForm({ saved: next, draft: next });
    setSaveStatus('idle');
  };
  const handleRestoreDefaults = () => {
    setInitializeOnSave(!preferences.initialized);
    setForm((current) => ({
      saved: normalizeDraft(toDraft(preferences, defaultShortcutHelpPreference)),
      draft: {
        worksetSize: '',
        preferredLocales: [],
        shortcutHelp: defaultShortcutHelpPreference,
        visibleTextEditorEnabled: false,
        reviewProjectSearchEnabled: false,
        defaultReviewTeamIds: canConfigureDefaultReviewTeams
          ? []
          : current.draft.defaultReviewTeamIds,
      },
    }));
    setSaveStatus('idle');
  };

  return (
    <div className="personal-settings-page">
      <div className="personal-settings-page__scroll">
        <div className="settings-page settings-page--personal">
          <div className="settings-page__header">
            <h1>My Settings</h1>
            <p className="settings-page__lead">
              Customize your experience across browsers and devices.
            </p>
          </div>

          {!preferences.initialized && hasBrowserPreferences && isDirty ? (
            <p className="settings-note">
              Save to keep these settings with your account. Existing browser settings are included
              in this draft.
            </p>
          ) : null}
          <section className="settings-card" aria-labelledby="settings-workbench">
            <div className="settings-card__header">
              <h2 id="settings-workbench">Workbench</h2>
            </div>
            <p className="settings-note">
              Choose how many results to load at once. Large limits can slow down Workbench.
            </p>
            <div className="settings-field">
              <label className="settings-field__label" htmlFor="workset-size-input">
                Result size limit
              </label>
              <div className="settings-field__row">
                <input
                  id="workset-size-input"
                  type="number"
                  disabled={isSaving}
                  max={2147483647}
                  min={1}
                  step={1}
                  aria-invalid={!parsedWorkset.valid}
                  aria-describedby={worksetError ? 'workset-size-error' : 'workset-size-hint'}
                  inputMode="numeric"
                  className="settings-input"
                  value={worksetDraft}
                  onChange={(event) => setWorksetDraft(event.target.value)}
                  placeholder={`Default is ${WORKSET_SIZE_DEFAULT}`}
                />
              </div>
              <p id="workset-size-hint" className="settings-hint">
                Leave blank to use the default of {WORKSET_SIZE_DEFAULT}.
              </p>
              {worksetError ? (
                <div id="workset-size-error" className="settings-hint is-error">
                  {worksetError}
                </div>
              ) : null}
            </div>
          </section>

          <section className="settings-card" aria-labelledby="settings-translation-editor">
            <div className="settings-card__header">
              <h2 id="settings-translation-editor">Translation editor</h2>
            </div>
            <div className="settings-field">
              <label className="settings-radio-option">
                <input
                  type="checkbox"
                  disabled={isSaving}
                  checked={visibleTextEditorDraft}
                  onChange={(event) => setVisibleTextEditorDraft(event.target.checked)}
                />
                <span className="settings-radio-option__body">
                  <span className="settings-radio-option__label">
                    Use the assisted rich text editor in Workbench, Review Project, and text unit
                    details
                  </span>
                  <span className="settings-hint">
                    Adds issue highlights and protected placeholder chips.
                  </span>
                </span>
              </label>
            </div>
          </section>

          <section
            id="review-project-search"
            className="settings-card"
            aria-labelledby="settings-review-project-search"
          >
            <div className="settings-card__header">
              <h2 id="settings-review-project-search">Translation search</h2>
            </div>
            <div className="settings-field">
              <label className="settings-radio-option">
                <input
                  type="checkbox"
                  disabled={isSaving}
                  checked={reviewProjectSearchDraft}
                  onChange={(event) => setReviewProjectSearchDraft(event.target.checked)}
                />
                <span className="settings-radio-option__body">
                  <span className="settings-radio-option__label">
                    Show Search in Review Project and text-unit details (preview)
                  </span>
                  <span className="settings-hint">
                    Search current translations across repositories.
                  </span>
                </span>
              </label>
            </div>
          </section>

          <section className="settings-card" aria-labelledby="settings-review-projects">
            <div className="settings-card__header">
              <h2 id="settings-review-projects">Review projects</h2>
            </div>
            <div className="settings-field">
              <div className="settings-field__label">Shortcut bar</div>
              <label className="settings-radio-option">
                <input
                  type="checkbox"
                  disabled={isSaving}
                  checked={shortcutHelpDraft === 'bottom'}
                  onChange={(event) =>
                    setShortcutHelpDraft(event.target.checked ? 'bottom' : 'header')
                  }
                />
                <span className="settings-radio-option__body">
                  <span className="settings-radio-option__label">
                    Show shortcut bar at the bottom of review projects
                  </span>
                  <span className="settings-hint">
                    The keyboard shortcuts button is always available in the project header.
                  </span>
                </span>
              </label>
              <p className="settings-hint">
                Default is {defaultShortcutHelpPreference === 'bottom' ? 'shown' : 'hidden'}.
              </p>
            </div>
            {canConfigureDefaultReviewTeams ? (
              <div className="settings-field">
                <div className="settings-field__header">
                  <div className="settings-field__label">Default team filter</div>
                </div>
                <TeamMultiSelect
                  label="Default teams"
                  options={defaultReviewTeamOptions}
                  selectedIds={defaultReviewTeamDraft}
                  onChange={setDefaultReviewTeamDraft}
                  className="settings-repository-select"
                  disabled={isSaving || teamsQuery.isLoading || teamsQuery.isError}
                  buttonAriaLabel="Select default review teams"
                  placeholder="No default teams"
                  emptyOptionsLabel={teamsQuery.isLoading ? 'Loading teams' : 'No teams'}
                  allTeamsLabel="All teams"
                  myTeamIds={user.teamIds ?? []}
                  showSelectionPresets
                />
                {teamsQuery.isLoading ? (
                  <p className="settings-hint">Loading teams...</p>
                ) : teamsQuery.isError ? (
                  <p className="settings-hint is-error">Unable to load teams.</p>
                ) : defaultReviewTeamOptions.length === 0 ? (
                  <p className="settings-hint">No teams available.</p>
                ) : (
                  <p className="settings-hint">
                    When set, Review Projects opens to To team with these teams selected unless the
                    URL already contains a saved filter.
                  </p>
                )}
              </div>
            ) : null}
          </section>

          <section className="settings-card" aria-labelledby="settings-preferred-locales">
            <div className="settings-card__header">
              <h2 id="settings-preferred-locales">Preferred locales</h2>
            </div>
            <p className="settings-note">
              Choose the locales selected by &quot;My locales&quot; in Workbench.
            </p>
            <div className="settings-field">
              <div className="settings-field__header">
                <div className="settings-field__label">Locales</div>
              </div>
              <LocaleMultiSelect
                label="Preferred locales"
                disabled={isSaving}
                options={localeOptions}
                selectedTags={preferredLocalesDraft}
                onChange={setPreferredLocalesDraft}
                className="settings-locale-select"
                buttonAriaLabel="Select preferred locales"
                myLocaleTags={user.userLocales}
              />
              {preferredLocalesDraft.length === 0 ? (
                <p className="settings-hint">No preferred locales set.</p>
              ) : null}
            </div>
          </section>
          <div className="personal-settings-page__defaults">
            <button
              type="button"
              className="settings-button settings-button--ghost"
              onClick={handleRestoreDefaults}
              disabled={isSaving || !canRestoreDefaults}
            >
              Restore defaults
            </button>
            <p className="settings-hint">Applies to all settings on this page after you save.</p>
          </div>
        </div>
      </div>
      <footer className="personal-settings-page__footer" aria-label="Settings actions">
        <div className="personal-settings-page__actions">
          <p
            className={`personal-settings-page__status${saveStatus === 'error' && isDirty ? ' is-error' : ''}`}
            role="status"
          >
            {isSaving
              ? 'Saving changes…'
              : saveStatus === 'error' && isDirty
                ? 'Could not save changes. Please try again.'
                : isDirty
                  ? 'Unsaved changes'
                  : saveStatus === 'saved'
                    ? 'Changes saved'
                    : 'No unsaved changes'}
          </p>
          <div className="settings-actions">
            <button
              type="button"
              className="settings-button settings-button--ghost"
              onClick={handleDiscard}
              disabled={isSaving || !isDirty}
            >
              Discard changes
            </button>
            <button
              type="button"
              className="settings-button settings-button--primary"
              onClick={() => void handleSave()}
              disabled={isSaving || !isDirty || !parsedWorkset.valid}
            >
              Save changes
            </button>
          </div>
        </div>
      </footer>
    </div>
  );
}
