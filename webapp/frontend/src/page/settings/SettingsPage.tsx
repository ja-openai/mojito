import './settings-page.css';

import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';

import { fetchTeams } from '../../api/teams';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import { TeamMultiSelect, type TeamMultiSelectOption } from '../../components/TeamMultiSelect';
import { useRepositories } from '../../hooks/useRepositories';
import { useUser } from '../../hooks/useUser';
import { hasSameSet } from '../../utils/arraySelection';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import { buildLocaleOptionsFromRepositories } from '../../utils/localeSelection';
import {
  loadReviewProjectSearchEnabled,
  saveReviewProjectSearchEnabled,
} from '../../utils/reviewProjectSearchPreference';
import {
  loadVisibleTextEditorEnabled,
  saveVisibleTextEditorEnabled,
} from '../../utils/visibleTextEditorPreference';
import {
  getDefaultReviewProjectShortcutHelpPreference,
  loadReviewProjectShortcutHelpPreference,
  type ReviewProjectShortcutHelpPreference,
  saveReviewProjectShortcutHelpPreference,
} from '../review-project/review-project-preferences';
import {
  loadDefaultReviewProjectTeamIds,
  saveDefaultReviewProjectTeamIds,
} from '../review-projects/review-projects-preferences';
import { WORKSET_SIZE_DEFAULT } from '../workbench/workbench-constants';
import {
  loadPreferredLocales,
  loadPreferredWorksetSize,
  PREFERRED_LOCALES_KEY,
  savePreferredLocales,
  savePreferredWorksetSize,
} from '../workbench/workbench-preferences';

function sameLocales(first: string[], second: string[]) {
  return (
    first.length === second.length &&
    first.every((locale, index) => locale.toLowerCase() === second[index].toLowerCase())
  );
}

export function SettingsPage() {
  const user = useUser();
  const username = user.username;
  const defaultShortcutHelpPreference = getDefaultReviewProjectShortcutHelpPreference(user.role);
  const canConfigureDefaultReviewTeams = user.role === 'ROLE_ADMIN' || user.role === 'ROLE_PM';
  const { data: repositories } = useRepositories();
  const teamsQuery = useQuery({
    queryKey: ['teams', 'settings-default-review-teams'],
    queryFn: fetchTeams,
    enabled: canConfigureDefaultReviewTeams,
  });
  const resolveLocaleName = useLocaleDisplayNameResolver();
  const [savedWorkset, setSavedWorkset] = useState<number | null>(() => loadPreferredWorksetSize());
  const [savedPreferredLocales, setSavedPreferredLocales] = useState<string[]>(() =>
    loadPreferredLocales(),
  );
  const [savedDefaultReviewTeamIds, setSavedDefaultReviewTeamIds] = useState<number[]>(() =>
    loadDefaultReviewProjectTeamIds(username),
  );
  const [defaultReviewTeamDraft, setDefaultReviewTeamDraft] = useState<number[]>(() =>
    loadDefaultReviewProjectTeamIds(username),
  );
  const [savedShortcutHelpPreference, setSavedShortcutHelpPreference] =
    useState<ReviewProjectShortcutHelpPreference>(() =>
      loadReviewProjectShortcutHelpPreference(defaultShortcutHelpPreference),
    );
  const [shortcutHelpDraft, setShortcutHelpDraft] = useState(savedShortcutHelpPreference);
  const [saveStatus, setSaveStatus] = useState<'idle' | 'saved' | 'error'>('idle');
  const [savedVisibleTextEditorEnabled, setSavedVisibleTextEditorEnabled] = useState(() =>
    loadVisibleTextEditorEnabled(username),
  );
  const [visibleTextEditorDraft, setVisibleTextEditorDraft] = useState(() =>
    loadVisibleTextEditorEnabled(username),
  );
  const [savedReviewProjectSearchEnabled, setSavedReviewProjectSearchEnabled] = useState(() =>
    loadReviewProjectSearchEnabled(username),
  );
  const [reviewProjectSearchDraft, setReviewProjectSearchDraft] = useState(() =>
    loadReviewProjectSearchEnabled(username),
  );
  const [worksetDraft, setWorksetDraft] = useState<string>(() =>
    savedWorkset == null ? '' : String(savedWorkset),
  );
  const [preferredLocalesDraft, setPreferredLocalesDraft] =
    useState<string[]>(savedPreferredLocales);

  const localeOptions = useMemo(() => {
    const repositoryOptions = buildLocaleOptionsFromRepositories(
      repositories ?? [],
      resolveLocaleName,
    );
    const seen = new Set(repositoryOptions.map((option) => option.tag.toLowerCase()));
    const mergedSelections = [
      ...savedPreferredLocales,
      ...preferredLocalesDraft,
      ...user.userLocales,
    ];
    const extraOptions = mergedSelections
      .filter((tag) => {
        const lower = tag.toLowerCase();
        if (seen.has(lower)) {
          return false;
        }
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
      .map((team) => ({
        id: team.id,
        name: team.name.trim() || `Team #${team.id}`,
      }))
      .filter((option) => {
        if (!Number.isInteger(option.id) || option.id <= 0 || seen.has(option.id)) {
          return false;
        }
        seen.add(option.id);
        return true;
      })
      .sort((first, second) =>
        first.name.localeCompare(second.name, undefined, { sensitivity: 'base' }),
      );
  }, [teamsQuery.data]);
  const availableDefaultReviewTeamIdSet = useMemo(
    () => new Set(defaultReviewTeamOptions.map((option) => option.id)),
    [defaultReviewTeamOptions],
  );

  useEffect(() => {
    const handleStorage = (event: StorageEvent) => {
      if (event.key && event.key !== PREFERRED_LOCALES_KEY) {
        return;
      }
      const next = loadPreferredLocales();
      setPreferredLocalesDraft((draft) =>
        sameLocales(draft, savedPreferredLocales) ? next : draft,
      );
      setSavedPreferredLocales(next);
    };
    window.addEventListener('storage', handleStorage);
    return () => window.removeEventListener('storage', handleStorage);
  }, [savedPreferredLocales]);

  useEffect(() => {
    const nextWorkset = loadPreferredWorksetSize();
    setSavedWorkset(nextWorkset);
    setWorksetDraft(nextWorkset == null ? '' : String(nextWorkset));
    const nextLocales = loadPreferredLocales();
    setSavedPreferredLocales(nextLocales);
    setPreferredLocalesDraft(nextLocales);
    const nextShortcutHelp = loadReviewProjectShortcutHelpPreference(defaultShortcutHelpPreference);
    setSavedShortcutHelpPreference(nextShortcutHelp);
    setShortcutHelpDraft(nextShortcutHelp);
    const nextVisibleTextEditorEnabled = loadVisibleTextEditorEnabled(username);
    setSavedVisibleTextEditorEnabled(nextVisibleTextEditorEnabled);
    setVisibleTextEditorDraft(nextVisibleTextEditorEnabled);
    const nextSearchEnabled = loadReviewProjectSearchEnabled(username);
    setSavedReviewProjectSearchEnabled(nextSearchEnabled);
    setReviewProjectSearchDraft(nextSearchEnabled);
    const nextDefaultReviewTeamIds = loadDefaultReviewProjectTeamIds(username);
    setSavedDefaultReviewTeamIds(nextDefaultReviewTeamIds);
    setDefaultReviewTeamDraft(nextDefaultReviewTeamIds);
    setSaveStatus('idle');
  }, [username, defaultShortcutHelpPreference]);

  useEffect(() => {
    if (!teamsQuery.isSuccess) {
      return;
    }
    const pruneUnavailableTeamIds = (teamIds: number[]) =>
      teamIds.filter((teamId) => availableDefaultReviewTeamIdSet.has(teamId));

    setSavedDefaultReviewTeamIds((previous) => {
      const next = pruneUnavailableTeamIds(previous);
      return hasSameSet(previous, next) ? previous : next;
    });
    setDefaultReviewTeamDraft((previous) => {
      const next = pruneUnavailableTeamIds(previous);
      return hasSameSet(previous, next) ? previous : next;
    });
  }, [availableDefaultReviewTeamIdSet, teamsQuery.isSuccess]);

  const trimmedWorkset = worksetDraft.trim();
  const parsedWorkset = useMemo(() => {
    if (!trimmedWorkset) {
      return { value: null as number | null, valid: true };
    }
    const parsed = Number(trimmedWorkset);
    if (!Number.isSafeInteger(parsed) || parsed < 1) {
      return { value: null as number | null, valid: false };
    }
    return { value: parsed, valid: true };
  }, [trimmedWorkset]);

  const worksetError = !parsedWorkset.valid ? 'Enter a positive whole number.' : null;
  const isWorksetDirty = trimmedWorkset !== (savedWorkset == null ? '' : String(savedWorkset));
  const isPreferredLocalesDirty = !sameLocales(preferredLocalesDraft, savedPreferredLocales);
  const isDefaultReviewTeamsDirty = useMemo(
    () => !hasSameSet(defaultReviewTeamDraft, savedDefaultReviewTeamIds),
    [defaultReviewTeamDraft, savedDefaultReviewTeamIds],
  );
  const isShortcutHelpDirty = shortcutHelpDraft !== savedShortcutHelpPreference;
  const isVisibleTextEditorDirty = visibleTextEditorDraft !== savedVisibleTextEditorEnabled;
  const isReviewProjectSearchDirty = reviewProjectSearchDraft !== savedReviewProjectSearchEnabled;
  const isDirty =
    isWorksetDirty ||
    isPreferredLocalesDirty ||
    isShortcutHelpDirty ||
    isVisibleTextEditorDirty ||
    isReviewProjectSearchDirty ||
    (canConfigureDefaultReviewTeams && isDefaultReviewTeamsDirty);
  const canRestoreDefaults =
    trimmedWorkset !== '' ||
    preferredLocalesDraft.length > 0 ||
    shortcutHelpDraft !== defaultShortcutHelpPreference ||
    visibleTextEditorDraft ||
    reviewProjectSearchDraft ||
    (canConfigureDefaultReviewTeams && defaultReviewTeamDraft.length > 0);

  const handleSave = () => {
    if (!parsedWorkset.valid || !isDirty) {
      return;
    }
    try {
      // Only write changed preferences so unrelated updates in another tab are preserved.
      if (isWorksetDirty) {
        savePreferredWorksetSize(parsedWorkset.value);
        const saved = loadPreferredWorksetSize();
        if (saved !== parsedWorkset.value) throw new Error('Workset preference was not saved');
        setSavedWorkset(saved);
        setWorksetDraft(saved == null ? '' : String(saved));
      }
      if (isPreferredLocalesDirty) {
        savePreferredLocales(preferredLocalesDraft);
        const saved = loadPreferredLocales();
        if (!sameLocales(saved, preferredLocalesDraft)) throw new Error('Locales were not saved');
        setSavedPreferredLocales(saved);
        setPreferredLocalesDraft(saved);
      }
      if (isShortcutHelpDirty) {
        saveReviewProjectShortcutHelpPreference(shortcutHelpDraft, defaultShortcutHelpPreference);
        const saved = loadReviewProjectShortcutHelpPreference(defaultShortcutHelpPreference);
        if (saved !== shortcutHelpDraft) throw new Error('Shortcut preference was not saved');
        setSavedShortcutHelpPreference(saved);
      }
      if (isVisibleTextEditorDirty) {
        saveVisibleTextEditorEnabled(visibleTextEditorDraft, username);
        const saved = loadVisibleTextEditorEnabled(username);
        if (saved !== visibleTextEditorDraft) throw new Error('Editor preference was not saved');
        setSavedVisibleTextEditorEnabled(saved);
      }
      if (isReviewProjectSearchDirty) {
        saveReviewProjectSearchEnabled(reviewProjectSearchDraft, username);
        const saved = loadReviewProjectSearchEnabled(username);
        if (saved !== reviewProjectSearchDraft) throw new Error('Search preference was not saved');
        setSavedReviewProjectSearchEnabled(saved);
      }
      if (canConfigureDefaultReviewTeams && isDefaultReviewTeamsDirty) {
        saveDefaultReviewProjectTeamIds(defaultReviewTeamDraft, username);
        const saved = loadDefaultReviewProjectTeamIds(username);
        if (!hasSameSet(saved, defaultReviewTeamDraft))
          throw new Error('Default teams were not saved');
        setSavedDefaultReviewTeamIds(saved);
        setDefaultReviewTeamDraft(saved);
      }
      setSaveStatus('saved');
    } catch {
      setSaveStatus('error');
    }
  };

  const handleDiscard = () => {
    setWorksetDraft(savedWorkset == null ? '' : String(savedWorkset));
    setPreferredLocalesDraft(savedPreferredLocales);
    setShortcutHelpDraft(savedShortcutHelpPreference);
    setVisibleTextEditorDraft(savedVisibleTextEditorEnabled);
    setReviewProjectSearchDraft(savedReviewProjectSearchEnabled);
    setDefaultReviewTeamDraft(savedDefaultReviewTeamIds);
    setSaveStatus('idle');
  };

  const handleRestoreDefaults = () => {
    setWorksetDraft('');
    setPreferredLocalesDraft([]);
    setShortcutHelpDraft(defaultShortcutHelpPreference);
    setVisibleTextEditorDraft(false);
    setReviewProjectSearchDraft(false);
    if (canConfigureDefaultReviewTeams) setDefaultReviewTeamDraft([]);
    setSaveStatus('idle');
  };

  return (
    <div className="personal-settings-page">
      <div className="personal-settings-page__scroll">
        <div className="settings-page settings-page--personal">
          <div className="settings-page__header">
            <h1>My Settings</h1>
            <p className="settings-page__lead">Customize your experience in this browser.</p>
          </div>

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
                  checked={visibleTextEditorDraft}
                  onChange={(event) => setVisibleTextEditorDraft(event.target.checked)}
                />
                <span className="settings-radio-option__body">
                  <span className="settings-radio-option__label">
                    Use the assisted rich text editor in Workbench, Review Project, and text unit
                    details
                  </span>
                  <span className="settings-hint">
                    Adds issue highlights and protected placeholder chips. Saved for your account in
                    this browser.
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
                  checked={reviewProjectSearchDraft}
                  onChange={(event) => setReviewProjectSearchDraft(event.target.checked)}
                />
                <span className="settings-radio-option__body">
                  <span className="settings-radio-option__label">
                    Show Search in Review Project and text-unit details (preview)
                  </span>
                  <span className="settings-hint">
                    Search current translations across repositories. Saved for your account in this
                    browser.
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
                  disabled={teamsQuery.isLoading}
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
              disabled={!canRestoreDefaults}
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
            {saveStatus === 'error' && isDirty
              ? 'Could not save all changes. Please try again.'
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
              disabled={!isDirty}
            >
              Discard changes
            </button>
            <button
              type="button"
              className="settings-button settings-button--primary"
              onClick={handleSave}
              disabled={!isDirty || !parsedWorkset.valid}
            >
              Save changes
            </button>
          </div>
        </div>
      </footer>
    </div>
  );
}
