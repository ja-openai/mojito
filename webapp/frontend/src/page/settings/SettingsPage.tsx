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
import { clampWorksetSize } from '../workbench/workbench-helpers';
import {
  loadPreferredLocales,
  loadPreferredWorksetSize,
  PREFERRED_LOCALES_KEY,
  savePreferredLocales,
  savePreferredWorksetSize,
} from '../workbench/workbench-preferences';

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
  const [shortcutHelpPreference, setShortcutHelpPreference] =
    useState<ReviewProjectShortcutHelpPreference>(() =>
      loadReviewProjectShortcutHelpPreference(defaultShortcutHelpPreference),
    );
  const [visibleTextEditorEnabled, setVisibleTextEditorEnabled] = useState(() =>
    loadVisibleTextEditorEnabled(username),
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
      setSavedPreferredLocales(next);
      setPreferredLocalesDraft(next);
    };
    window.addEventListener('storage', handleStorage);
    return () => window.removeEventListener('storage', handleStorage);
  }, []);

  useEffect(() => {
    setVisibleTextEditorEnabled(loadVisibleTextEditorEnabled(username));
    const nextDefaultReviewTeamIds = loadDefaultReviewProjectTeamIds(username);
    setSavedDefaultReviewTeamIds(nextDefaultReviewTeamIds);
    setDefaultReviewTeamDraft(nextDefaultReviewTeamIds);
  }, [username]);

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
    const parsed = parseInt(trimmedWorkset, 10);
    if (!Number.isFinite(parsed) || parsed < 1) {
      return { value: null as number | null, valid: false };
    }
    return { value: clampWorksetSize(parsed), valid: true };
  }, [trimmedWorkset]);

  const worksetError = !parsedWorkset.valid && trimmedWorkset ? 'Enter a positive number' : null;
  const isWorksetDirty = useMemo(() => {
    const hasInput = trimmedWorkset.length > 0;
    if (!hasInput) {
      return savedWorkset !== null;
    }
    if (!parsedWorkset.valid) {
      return false;
    }
    return parsedWorkset.value !== savedWorkset;
  }, [parsedWorkset.valid, parsedWorkset.value, savedWorkset, trimmedWorkset]);
  const canResetWorkset = useMemo(
    () => savedWorkset !== null || trimmedWorkset.length > 0,
    [savedWorkset, trimmedWorkset],
  );

  const isPreferredLocalesDirty = useMemo(() => {
    if (preferredLocalesDraft.length !== savedPreferredLocales.length) {
      return true;
    }
    return preferredLocalesDraft.some(
      (value, index) => value.toLowerCase() !== (savedPreferredLocales[index]?.toLowerCase() ?? ''),
    );
  }, [preferredLocalesDraft, savedPreferredLocales]);
  const isDefaultReviewTeamsDirty = useMemo(
    () => !hasSameSet(defaultReviewTeamDraft, savedDefaultReviewTeamIds),
    [defaultReviewTeamDraft, savedDefaultReviewTeamIds],
  );
  const canResetDefaultReviewTeams =
    savedDefaultReviewTeamIds.length > 0 || defaultReviewTeamDraft.length > 0;

  const handleSaveWorksetPreference = () => {
    if (!parsedWorkset.valid || !isWorksetDirty) {
      return;
    }
    savePreferredWorksetSize(parsedWorkset.value);
    setSavedWorkset(parsedWorkset.value);
    setWorksetDraft(parsedWorkset.value == null ? '' : String(parsedWorkset.value));
  };

  const handleResetWorksetPreference = () => {
    savePreferredWorksetSize(null);
    setSavedWorkset(null);
    setWorksetDraft('');
  };

  const handleSavePreferredLocales = () => {
    savePreferredLocales(preferredLocalesDraft);
    const next = loadPreferredLocales();
    setSavedPreferredLocales(next);
    setPreferredLocalesDraft(next);
  };

  const handleSaveDefaultReviewTeams = () => {
    saveDefaultReviewProjectTeamIds(defaultReviewTeamDraft, username);
    const next = loadDefaultReviewProjectTeamIds(username);
    setSavedDefaultReviewTeamIds(next);
    setDefaultReviewTeamDraft(next);
  };

  const handleResetDefaultReviewTeams = () => {
    saveDefaultReviewProjectTeamIds([], username);
    setSavedDefaultReviewTeamIds([]);
    setDefaultReviewTeamDraft([]);
  };

  const handleShortcutBarVisibilityChange = (visible: boolean) => {
    const nextPreference: ReviewProjectShortcutHelpPreference = visible ? 'bottom' : 'header';
    saveReviewProjectShortcutHelpPreference(nextPreference, defaultShortcutHelpPreference);
    setShortcutHelpPreference(nextPreference);
  };

  const handleVisibleTextEditorChange = (enabled: boolean) => {
    saveVisibleTextEditorEnabled(enabled, username);
    setVisibleTextEditorEnabled(enabled);
  };

  return (
    <div className="settings-page">
      <div className="settings-page__header">
        <h1>My Settings</h1>
      </div>

      <section className="settings-card" aria-labelledby="settings-workbench">
        <div className="settings-card__header">
          <h2 id="settings-workbench">Override workbench result size limit</h2>
        </div>
        <p className="settings-note">
          Set the default workbench result size limit. The default ({WORKSET_SIZE_DEFAULT}) is
          intentionally conservative; increase it if you want to load more results by default. Very
          large values can slow things down, staying under about 1000 is usually a good balance.
        </p>
        <div className="settings-field">
          <div className="settings-field__row">
            <input
              id="workset-size-input"
              type="number"
              min={1}
              inputMode="numeric"
              className="settings-input"
              value={worksetDraft}
              onChange={(event) => setWorksetDraft(event.target.value)}
              placeholder="Default is 10"
            />
          </div>
          {worksetError ? <div className="settings-hint is-error">{worksetError}</div> : null}
        </div>
        <div className="settings-card__footer">
          <div className="settings-actions">
            <button
              type="button"
              className="settings-button settings-button--primary"
              onClick={handleSaveWorksetPreference}
              disabled={!parsedWorkset.valid || !isWorksetDirty}
            >
              Save
            </button>
            <button
              type="button"
              className="settings-button settings-button--ghost"
              onClick={handleResetWorksetPreference}
              disabled={!canResetWorkset}
            >
              Reset
            </button>
          </div>
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
              checked={visibleTextEditorEnabled}
              onChange={(event) => handleVisibleTextEditorChange(event.target.checked)}
            />
            <span className="settings-radio-option__body">
              <span className="settings-radio-option__label">
                Use the assisted rich text editor in Workbench, Review Project, and text unit
                details
              </span>
              <span className="settings-hint">
                Enables issue-focused text marks, unprotected editing, and protected placeholder
                chips for this Mojito user in this browser only.
              </span>
            </span>
          </label>
          <p className="settings-hint">
            Saved separately for each Mojito user in this browser. New users start with it off.
          </p>
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
              checked={shortcutHelpPreference === 'bottom'}
              onChange={(event) => handleShortcutBarVisibilityChange(event.target.checked)}
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
                When set, Review Projects opens to To team with these teams selected unless the URL
                already contains a saved filter.
              </p>
            )}
            <div className="settings-card__footer">
              <div className="settings-actions">
                <button
                  type="button"
                  className="settings-button settings-button--primary"
                  onClick={handleSaveDefaultReviewTeams}
                  disabled={!isDefaultReviewTeamsDirty}
                >
                  Save
                </button>
                <button
                  type="button"
                  className="settings-button settings-button--ghost"
                  onClick={handleResetDefaultReviewTeams}
                  disabled={!canResetDefaultReviewTeams}
                >
                  Reset
                </button>
              </div>
            </div>
          </div>
        ) : null}
      </section>

      <section className="settings-card" aria-labelledby="settings-preferred-locales">
        <div className="settings-card__header">
          <h2 id="settings-preferred-locales">Preferred locales</h2>
        </div>
        <p className="settings-note">
          Define your personal locale shortcuts. The workbench locale picker will show a &quot;My
          locales&quot; action that selects this list.
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
          <p className="settings-hint">
            Uses the same locale selector as workbench and repositories; includes repository locales
            plus any saved choices.
          </p>
        </div>
        <div className="settings-card__footer">
          <div className="settings-actions">
            <button
              type="button"
              className="settings-button settings-button--primary"
              onClick={handleSavePreferredLocales}
              disabled={!isPreferredLocalesDirty}
            >
              Save
            </button>
          </div>
        </div>
      </section>
    </div>
  );
}
