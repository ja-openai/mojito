import './settings-page.css';
import './admin-ai-locale-prompt-suffix-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Navigate } from 'react-router-dom';

import {
  type ApiAiTranslateLocalePromptSuffix,
  deleteAiTranslateLocalePromptSuffix,
  fetchAiTranslateLocalePromptSuffixes,
  upsertAiTranslateLocalePromptSuffix,
} from '../../api/ai-translate-locale-prompt-suffixes';
import { AutoTextarea } from '../../components/AutoTextarea';
import { LocaleMultiSelect, type LocaleOption } from '../../components/LocaleMultiSelect';
import { Modal } from '../../components/Modal';
import { useUser } from '../../components/RequireUser';
import { SearchControl } from '../../components/SearchControl';
import { useLocales } from '../../hooks/useLocales';
import { useRepositories } from '../../hooks/useRepositories';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import { buildLocaleOptionsFromRepositories } from '../../utils/localeSelection';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

type StatusNotice = {
  kind: 'success' | 'error';
  message: string;
};

type EditorMode = 'create' | 'edit';

const LOCALE_PROMPT_SUFFIXES_QUERY_KEY = ['ai-translate-locale-prompt-suffixes'] as const;

const normalizeQuery = (value: string) => value.trim().toLowerCase();

export function AdminAiLocalePromptSuffixPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();
  const resolveLocaleName = useLocaleDisplayNameResolver();
  const { data: repositories } = useRepositories();
  const { data: locales } = useLocales();

  const [searchQuery, setSearchQuery] = useState('');
  const [selectedFilterTags, setSelectedFilterTags] = useState<string[]>([]);
  const [useAllLocales, setUseAllLocales] = useState(false);
  const [statusNotice, setStatusNotice] = useState<StatusNotice | null>(null);
  const [isEditorOpen, setIsEditorOpen] = useState(false);
  const [editorMode, setEditorMode] = useState<EditorMode>('create');
  const [editorOriginalLocaleTag, setEditorOriginalLocaleTag] = useState<string | null>(null);
  const [editorLocaleTags, setEditorLocaleTags] = useState<string[]>([]);
  const [editorPromptSuffix, setEditorPromptSuffix] = useState('');

  const localePromptSuffixesQuery = useQuery<ApiAiTranslateLocalePromptSuffix[]>({
    queryKey: LOCALE_PROMPT_SUFFIXES_QUERY_KEY,
    queryFn: fetchAiTranslateLocalePromptSuffixes,
    staleTime: 30_000,
    enabled: isAdmin,
  });

  const repositoryLocaleOptions = useMemo<LocaleOption[]>(
    () => buildLocaleOptionsFromRepositories(repositories ?? [], resolveLocaleName),
    [repositories, resolveLocaleName],
  );
  const allLocaleOptions = useMemo<LocaleOption[]>(
    () =>
      (locales ?? [])
        .map((locale) => ({
          tag: locale.bcp47Tag,
          label: resolveLocaleName(locale.bcp47Tag),
        }))
        .sort((first, second) =>
          first.tag.localeCompare(second.tag, undefined, { sensitivity: 'base' }),
        ),
    [locales, resolveLocaleName],
  );
  const localeOptions = useMemo<LocaleOption[]>(
    () => (useAllLocales ? allLocaleOptions : repositoryLocaleOptions),
    [allLocaleOptions, repositoryLocaleOptions, useAllLocales],
  );
  const canShowAllLocales = useMemo(() => {
    if (allLocaleOptions.length === 0) {
      return false;
    }
    if (allLocaleOptions.length !== repositoryLocaleOptions.length) {
      return true;
    }
    return allLocaleOptions.some(
      (option, index) =>
        option.tag.toLowerCase() !== (repositoryLocaleOptions[index]?.tag ?? '').toLowerCase(),
    );
  }, [allLocaleOptions, repositoryLocaleOptions]);

  const rows = useMemo(() => {
    return (localePromptSuffixesQuery.data ?? []).map((row) => {
      const localeLabel = resolveLocaleName(row.localeTag);
      return {
        ...row,
        localeLabel,
        searchText: `${row.localeTag} ${localeLabel} ${row.promptSuffix}`.toLowerCase(),
      };
    });
  }, [localePromptSuffixesQuery.data, resolveLocaleName]);

  const normalizedSearch = normalizeQuery(searchQuery);
  const filteredRows = useMemo(() => {
    const allowedTags =
      selectedFilterTags.length > 0
        ? new Set(selectedFilterTags.map((tag) => tag.toLowerCase()))
        : null;

    return rows.filter((row) => {
      if (allowedTags && !allowedTags.has(row.localeTag.toLowerCase())) {
        return false;
      }
      if (normalizedSearch && !row.searchText.includes(normalizedSearch)) {
        return false;
      }
      return true;
    });
  }, [normalizedSearch, rows, selectedFilterTags]);

  const saveMutation = useMutation({
    mutationFn: upsertAiTranslateLocalePromptSuffix,
    onSuccess: async (savedRow) => {
      queryClient.setQueryData<ApiAiTranslateLocalePromptSuffix[]>(
        LOCALE_PROMPT_SUFFIXES_QUERY_KEY,
        (previous) => {
          const next = (previous ?? []).filter(
            (row) => row.localeTag.toLowerCase() !== savedRow.localeTag.toLowerCase(),
          );
          next.push(savedRow);
          next.sort((first, second) =>
            first.localeTag.localeCompare(second.localeTag, undefined, { sensitivity: 'base' }),
          );
          return next;
        },
      );
      await queryClient.invalidateQueries({ queryKey: LOCALE_PROMPT_SUFFIXES_QUERY_KEY });
      setIsEditorOpen(false);
      setStatusNotice({
        kind: 'success',
        message: editorMode === 'create' ? 'Suffix added.' : 'Suffix saved.',
      });
      resetEditor();
    },
    onError: (error: Error) => {
      setStatusNotice({ kind: 'error', message: error.message || 'Failed to save suffix.' });
    },
  });

  const deleteMutation = useMutation({
    mutationFn: deleteAiTranslateLocalePromptSuffix,
    onSuccess: async (_, localeTag) => {
      queryClient.setQueryData<ApiAiTranslateLocalePromptSuffix[]>(
        LOCALE_PROMPT_SUFFIXES_QUERY_KEY,
        (previous) =>
          (previous ?? []).filter((row) => row.localeTag.toLowerCase() !== localeTag.toLowerCase()),
      );
      await queryClient.invalidateQueries({ queryKey: LOCALE_PROMPT_SUFFIXES_QUERY_KEY });
      setIsEditorOpen(false);
      setStatusNotice({ kind: 'success', message: 'Suffix deleted.' });
      resetEditor();
    },
    onError: (error: Error) => {
      setStatusNotice({ kind: 'error', message: error.message || 'Failed to delete suffix.' });
    },
  });

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  const resetEditor = () => {
    setEditorMode('create');
    setEditorOriginalLocaleTag(null);
    setEditorLocaleTags([]);
    setEditorPromptSuffix('');
    saveMutation.reset();
    deleteMutation.reset();
  };

  const openCreateEditor = () => {
    resetEditor();
    setStatusNotice(null);
    setIsEditorOpen(true);
  };

  const openEditEditor = (row: ApiAiTranslateLocalePromptSuffix) => {
    setEditorMode('edit');
    setEditorOriginalLocaleTag(row.localeTag);
    setEditorLocaleTags([row.localeTag]);
    setEditorPromptSuffix(row.promptSuffix);
    setStatusNotice(null);
    saveMutation.reset();
    deleteMutation.reset();
    setIsEditorOpen(true);
  };

  const handleCloseEditor = () => {
    if (saveMutation.isPending || deleteMutation.isPending) {
      return;
    }
    setIsEditorOpen(false);
    resetEditor();
  };

  const handleEditorLocaleChange = (next: string[]) => {
    setStatusNotice(null);
    saveMutation.reset();
    deleteMutation.reset();

    if (editorMode === 'edit') {
      return;
    }
    if (next.length <= 1) {
      setEditorLocaleTags(next);
      return;
    }

    const current = new Set(editorLocaleTags.map((tag) => tag.toLowerCase()));
    const addedTag =
      next.find((tag) => !current.has(tag.toLowerCase())) ?? next[next.length - 1] ?? null;
    setEditorLocaleTags(addedTag ? [addedTag] : []);
  };

  const handleEditorPromptSuffixChange = (next: string) => {
    setEditorPromptSuffix(next);
    setStatusNotice(null);
    saveMutation.reset();
    deleteMutation.reset();
  };

  const trimmedPromptSuffix = editorPromptSuffix.trim();
  const selectedEditorLocaleTag = editorLocaleTags[0] ?? null;
  const canSave =
    selectedEditorLocaleTag != null &&
    trimmedPromptSuffix.length > 0 &&
    !saveMutation.isPending &&
    !deleteMutation.isPending;

  const handleSave = () => {
    if (!selectedEditorLocaleTag || !trimmedPromptSuffix) {
      return;
    }
    saveMutation.mutate({
      localeTag: selectedEditorLocaleTag,
      promptSuffix: trimmedPromptSuffix,
    });
  };

  const handleDelete = () => {
    if (!editorOriginalLocaleTag) {
      return;
    }
    deleteMutation.mutate(editorOriginalLocaleTag);
  };

  const editorLocaleOptions =
    editorMode === 'edit'
      ? (() => {
          const matchingOptions = localeOptions.filter(
            (option) => option.tag.toLowerCase() === (editorOriginalLocaleTag ?? '').toLowerCase(),
          );
          if (matchingOptions.length > 0 || !editorOriginalLocaleTag) {
            return matchingOptions;
          }
          return [
            {
              tag: editorOriginalLocaleTag,
              label: resolveLocaleName(editorOriginalLocaleTag),
            },
          ];
        })()
      : localeOptions;

  const localeSelectorActions = canShowAllLocales
    ? [
        {
          label: useAllLocales ? 'Repo locales' : 'All locales',
          onClick: () => setUseAllLocales((current) => !current),
          ariaLabel: useAllLocales ? 'Use repository locales only' : 'Show all locales',
        },
      ]
    : undefined;

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
        context="Settings"
        title="AI prompt suffixes"
      />
      <div className="settings-page settings-page--wide ai-locale-prompt-page">
        <section className="settings-card">
          <div className="settings-card__content">
            <div className="ai-locale-prompt-page__toolbar">
              <SearchControl
                value={searchQuery}
                onChange={setSearchQuery}
                placeholder="Search suffixes"
                className="ai-locale-prompt-page__search"
              />
              <LocaleMultiSelect
                label="Locales"
                options={localeOptions}
                selectedTags={selectedFilterTags}
                onChange={setSelectedFilterTags}
                className="ai-locale-prompt-page__filter"
                buttonAriaLabel="Filter suffixes by locale"
                customActions={localeSelectorActions}
              />
              <button
                type="button"
                className="settings-button settings-button--primary ai-locale-prompt-page__create"
                onClick={openCreateEditor}
                disabled={localeOptions.length === 0}
              >
                Add suffix
              </button>
            </div>

            <div className="ai-locale-prompt-page__count">
              <span className="ai-locale-prompt-page__count-text">
                {filteredRows.length} {filteredRows.length === 1 ? 'suffix' : 'suffixes'}
              </span>
              {statusNotice ? (
                <span
                  className={`settings-hint ai-locale-prompt-page__status${
                    statusNotice.kind === 'error' ? ' is-error' : ''
                  }`}
                >
                  {statusNotice.message}
                </span>
              ) : null}
            </div>

            {localePromptSuffixesQuery.isError ? (
              <p className="ai-locale-prompt-page__empty">Could not load suffixes.</p>
            ) : localePromptSuffixesQuery.isLoading ? (
              <p className="ai-locale-prompt-page__empty">Loading suffixes…</p>
            ) : rows.length === 0 ? (
              <p className="ai-locale-prompt-page__empty">No suffixes yet.</p>
            ) : filteredRows.length === 0 ? (
              <p className="ai-locale-prompt-page__empty">No suffixes match the current filters.</p>
            ) : (
              <div className="ai-locale-prompt-page__table">
                <div className="ai-locale-prompt-page__table-header">
                  <div className="ai-locale-prompt-page__cell">Locale</div>
                  <div className="ai-locale-prompt-page__cell">Suffix</div>
                  <div className="ai-locale-prompt-page__cell">Updated</div>
                  <div className="ai-locale-prompt-page__cell ai-locale-prompt-page__cell--actions">
                    Actions
                  </div>
                </div>
                {filteredRows.map((row) => (
                  <div
                    key={row.localeTag}
                    className="ai-locale-prompt-page__row"
                    onClick={() => openEditEditor(row)}
                    role="button"
                    tabIndex={0}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        openEditEditor(row);
                      }
                    }}
                  >
                    <div className="ai-locale-prompt-page__cell">
                      <div className="ai-locale-prompt-page__locale-cell">
                        <span className="ai-locale-prompt-page__locale-name">
                          {row.localeLabel}
                        </span>
                        <span className="ai-locale-prompt-page__locale-tag">{row.localeTag}</span>
                      </div>
                    </div>
                    <div className="ai-locale-prompt-page__cell ai-locale-prompt-page__cell--suffix">
                      {row.promptSuffix}
                    </div>
                    <div className="ai-locale-prompt-page__cell ai-locale-prompt-page__cell--muted">
                      {formatDateTime(row.updatedAt)}
                    </div>
                    <div className="ai-locale-prompt-page__cell ai-locale-prompt-page__cell--actions">
                      <button
                        type="button"
                        className="ai-locale-prompt-page__row-action"
                        onClick={(event) => {
                          event.stopPropagation();
                          openEditEditor(row);
                        }}
                      >
                        Edit
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </section>

        <Modal
          open={isEditorOpen}
          size="md"
          ariaLabel={editorMode === 'create' ? 'Add locale suffix' : 'Edit locale suffix'}
          onClose={handleCloseEditor}
          closeOnBackdrop
        >
          <div className="modal__title">
            {editorMode === 'create' ? 'Add suffix' : editorOriginalLocaleTag}
          </div>
          <div className="ai-locale-prompt-page__modal-fields">
            <div className="settings-field">
              <div className="settings-field__label">Locale</div>
              <LocaleMultiSelect
                label="Locale"
                options={editorLocaleOptions}
                selectedTags={editorLocaleTags}
                onChange={handleEditorLocaleChange}
                className="ai-locale-prompt-page__modal-locale"
                buttonAriaLabel="Select locale"
                disabled={editorMode === 'edit'}
                customActions={editorMode === 'create' ? localeSelectorActions : undefined}
              />
            </div>
            <div className="settings-field">
              <label className="settings-field__label" htmlFor="ai-locale-prompt-suffix-editor">
                Suffix
              </label>
              <AutoTextarea
                id="ai-locale-prompt-suffix-editor"
                className="settings-input ai-locale-prompt-page__textarea"
                value={editorPromptSuffix}
                onChange={(event) => handleEditorPromptSuffixChange(event.target.value)}
                minRows={5}
                maxRows={12}
              />
            </div>
            {saveMutation.error ? (
              <p className="settings-hint is-error">
                {saveMutation.error instanceof Error
                  ? saveMutation.error.message
                  : 'Failed to save suffix.'}
              </p>
            ) : null}
            {deleteMutation.error ? (
              <p className="settings-hint is-error">
                {deleteMutation.error instanceof Error
                  ? deleteMutation.error.message
                  : 'Failed to delete suffix.'}
              </p>
            ) : null}
          </div>
          <div className="modal__actions ai-locale-prompt-page__modal-actions">
            <button type="button" className="modal__button" onClick={handleCloseEditor}>
              Cancel
            </button>
            {editorMode === 'edit' ? (
              <button
                type="button"
                className="modal__button modal__button--danger"
                onClick={handleDelete}
                disabled={saveMutation.isPending || deleteMutation.isPending}
              >
                {deleteMutation.isPending ? 'Deleting…' : 'Delete'}
              </button>
            ) : null}
            <button
              type="button"
              className="modal__button modal__button--primary"
              onClick={handleSave}
              disabled={!canSave}
            >
              {saveMutation.isPending ? 'Saving…' : 'Save'}
            </button>
          </div>
        </Modal>
      </div>
    </div>
  );
}

function formatDateTime(value: string | null) {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString();
}
