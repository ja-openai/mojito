import '../settings/settings-page.css';
import '../settings/admin-glossaries-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';

import {
  type ApiGlossarySummary,
  createGlossary,
  deleteGlossary,
  fetchGlossaries,
} from '../../api/glossaries';
import { ConfirmModal } from '../../components/ConfirmModal';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import { Modal } from '../../components/Modal';
import { NumericPresetDropdown } from '../../components/NumericPresetDropdown';
import { RepositoryMultiSelect } from '../../components/RepositoryMultiSelect';
import { useUser } from '../../components/RequireUser';
import { SearchControl } from '../../components/SearchControl';
import { useLocales } from '../../hooks/useLocales';
import { useRepositories } from '../../hooks/useRepositories';
import { formatLocalDateTime as formatDateTime } from '../../utils/dateTime';
import { buildScopedGlossaryLocaleOptions } from '../../utils/glossaryLocaleScope';
import { buildGlossaryWorkbenchState } from '../../utils/glossaryWorkbench';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import { canAccessGlossaries } from '../../utils/permissions';
import { useRepositorySelectionOptions } from '../../utils/repositorySelection';

type StatusNotice = {
  kind: 'success' | 'error';
  message: string;
};

type EnabledFilter = 'enabled' | 'disabled' | 'all';
type GlossaryScopeMode = 'GLOBAL' | 'SELECTED_REPOSITORIES';

const LIMIT_PRESETS = [25, 50, 100];

const normalizeGlossaryName = (value: string) => value.trim().replace(/\s+/g, ' ');

export function GlossariesPage() {
  const user = useUser();
  const canAccess = canAccessGlossaries(user);
  const isAdmin = user.role === 'ROLE_ADMIN';
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: repositories } = useRepositories();
  const { data: locales, isLoading: localesLoading, isError: localesError } = useLocales();
  const resolveLocaleDisplayName = useLocaleDisplayNameResolver();
  const repositoryOptions = useRepositorySelectionOptions(repositories ?? []);

  const [searchQuery, setSearchQuery] = useState('');
  const [enabledFilter, setEnabledFilter] = useState<EnabledFilter>('enabled');
  const [limit, setLimit] = useState(50);
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [newNameDraft, setNewNameDraft] = useState('');
  const [newDescriptionDraft, setNewDescriptionDraft] = useState('');
  const [newEnabledDraft, setNewEnabledDraft] = useState(true);
  const [newPriorityDraft, setNewPriorityDraft] = useState('0');
  const [newScopeModeDraft, setNewScopeModeDraft] = useState<GlossaryScopeMode>('GLOBAL');
  const [newLocaleTagsDraft, setNewLocaleTagsDraft] = useState<string[]>([]);
  const [newRepositoryIdsDraft, setNewRepositoryIdsDraft] = useState<number[]>([]);
  const [newExcludedRepositoryIdsDraft, setNewExcludedRepositoryIdsDraft] = useState<number[]>([]);
  const [showAllLocaleOptions, setShowAllLocaleOptions] = useState(false);
  const [createModalError, setCreateModalError] = useState<string | null>(null);
  const [statusNotice, setStatusNotice] = useState<StatusNotice | null>(null);
  const [glossaryPendingDelete, setGlossaryPendingDelete] = useState<ApiGlossarySummary | null>(
    null,
  );

  const resetCreateDrafts = useCallback(() => {
    setNewNameDraft('');
    setNewDescriptionDraft('');
    setNewEnabledDraft(true);
    setNewPriorityDraft('0');
    setNewScopeModeDraft('GLOBAL');
    setNewLocaleTagsDraft([]);
    setNewRepositoryIdsDraft([]);
    setNewExcludedRepositoryIdsDraft([]);
    setShowAllLocaleOptions(false);
    setCreateModalError(null);
  }, []);

  const closeCreateModal = useCallback(() => {
    setIsCreateModalOpen(false);
    const searchParams = new URLSearchParams(location.search);
    if (searchParams.get('create') === '1') {
      void navigate('/glossaries', { replace: true });
    }
  }, [location.search, navigate]);

  useEffect(() => {
    if (!isAdmin) {
      return;
    }
    const searchParams = new URLSearchParams(location.search);
    if (searchParams.get('create') === '1') {
      resetCreateDrafts();
      setIsCreateModalOpen(true);
      setStatusNotice(null);
    }
  }, [isAdmin, location.search, resetCreateDrafts]);

  const glossariesQuery = useQuery({
    queryKey: ['glossaries', searchQuery, enabledFilter, limit],
    queryFn: () =>
      fetchGlossaries({
        search: searchQuery,
        enabled: enabledFilter === 'all' ? null : enabledFilter === 'enabled' ? true : false,
        limit,
      }),
    enabled: canAccess,
    staleTime: 30_000,
  });

  const createGlossaryMutation = useMutation({
    mutationFn: createGlossary,
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: ['glossaries'] });
      closeCreateModal();
      resetCreateDrafts();
      setStatusNotice({
        kind: 'success',
        message: `Created glossary ${created.name} (#${created.id}).`,
      });
    },
    onError: (error: Error) => {
      setCreateModalError(error.message || 'Failed to create glossary.');
    },
  });

  const deleteGlossaryMutation = useMutation({
    mutationFn: (glossaryId: number) => deleteGlossary(glossaryId),
    onSuccess: async () => {
      const deletedName = glossaryPendingDelete?.name;
      await queryClient.invalidateQueries({ queryKey: ['glossaries'] });
      setGlossaryPendingDelete(null);
      setStatusNotice({
        kind: 'success',
        message: deletedName ? `Deleted glossary ${deletedName}.` : 'Deleted glossary.',
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to delete glossary.',
      });
    },
  });

  const glossaries = glossariesQuery.data?.glossaries ?? [];
  const totalCount = glossariesQuery.data?.totalCount ?? 0;

  const visibleCountLabel = useMemo(() => {
    if (glossaries.length === totalCount) {
      return `${glossaries.length} glossaries`;
    }
    return `${glossaries.length} of ${totalCount} glossaries`;
  }, [glossaries.length, totalCount]);

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
        scopeMode: newScopeModeDraft,
        repositoryIds: newRepositoryIdsDraft,
        excludedRepositoryIds: newExcludedRepositoryIdsDraft,
        selectedTags: newLocaleTagsDraft,
      }),
    [
      localeOptions,
      newExcludedRepositoryIdsDraft,
      newLocaleTagsDraft,
      newRepositoryIdsDraft,
      newScopeModeDraft,
      repositories,
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

  if (!canAccess) {
    return <Navigate to="/repositories" replace />;
  }

  const handleCreateGlossary = () => {
    const normalizedName = normalizeGlossaryName(newNameDraft);
    if (!normalizedName) {
      setCreateModalError('Glossary name is required.');
      return;
    }
    const priority = Number.parseInt(newPriorityDraft.trim() || '0', 10);
    if (!Number.isFinite(priority)) {
      setCreateModalError('Priority must be a whole number.');
      return;
    }
    createGlossaryMutation.mutate({
      name: normalizedName,
      description: newDescriptionDraft.trim() || null,
      enabled: newEnabledDraft,
      priority,
      scopeMode: newScopeModeDraft,
      localeTags: newLocaleTagsDraft,
      repositoryIds: [...newRepositoryIdsDraft].sort((a, b) => a - b),
      excludedRepositoryIds: [...newExcludedRepositoryIdsDraft].sort((a, b) => a - b),
    });
  };

  return (
    <div className="settings-page settings-page--wide glossary-admin-page">
      <section className="settings-card">
        <div className="settings-card__content">
          <div className="glossary-admin-page__toolbar">
            <SearchControl
              value={searchQuery}
              onChange={setSearchQuery}
              placeholder="Search glossaries"
              className="glossary-admin-page__search"
            />
            <label className="glossary-admin-page__filter">
              <span className="glossary-admin-page__filter-label">Status</span>
              <select
                className="settings-input glossary-admin-page__filter-select"
                value={enabledFilter}
                onChange={(event) => setEnabledFilter(event.target.value as EnabledFilter)}
              >
                <option value="enabled">Enabled</option>
                <option value="disabled">Disabled</option>
                <option value="all">All</option>
              </select>
            </label>
            <NumericPresetDropdown
              value={limit}
              buttonLabel={`${limit} rows`}
              menuLabel="Result size"
              presetOptions={LIMIT_PRESETS.map((size) => ({
                value: size,
                label: String(size),
              }))}
              onChange={setLimit}
              ariaLabel="Glossary result size"
              pillsClassName="settings-pills"
              optionClassName="settings-pill"
              optionActiveClassName="is-active"
              customActiveClassName="is-active"
              customButtonClassName="settings-pill"
              customInitialValue={50}
            />
            {isAdmin ? (
              <div className="glossary-admin-page__toolbar-actions">
                <button
                  type="button"
                  className="settings-button settings-button--primary"
                  onClick={() => {
                    resetCreateDrafts();
                    setIsCreateModalOpen(true);
                    setStatusNotice(null);
                  }}
                >
                  Create glossary
                </button>
              </div>
            ) : null}
          </div>

          <div className="glossary-admin-page__count">
            <span className="glossary-admin-page__count-text">{visibleCountLabel}</span>
            {statusNotice ? (
              <span
                className={`settings-hint glossary-admin-page__status${
                  statusNotice.kind === 'error' ? ' is-error' : ''
                }`}
              >
                {statusNotice.message}
              </span>
            ) : null}
          </div>

          {glossariesQuery.isError ? (
            <p className="glossary-admin-page__empty">
              {glossariesQuery.error instanceof Error
                ? glossariesQuery.error.message
                : 'Could not load glossaries.'}
            </p>
          ) : glossariesQuery.isLoading ? (
            <p className="glossary-admin-page__empty">Loading glossaries…</p>
          ) : glossaries.length === 0 ? (
            <p className="glossary-admin-page__empty">No glossaries match the current filters.</p>
          ) : (
            <div className="glossary-admin-page__table">
              <div className="glossary-admin-page__table-header">
                <div className="glossary-admin-page__cell">Glossary</div>
                <div className="glossary-admin-page__cell">Scope</div>
                <div className="glossary-admin-page__cell">Priority</div>
                <div className="glossary-admin-page__cell">Status</div>
                <div className="glossary-admin-page__cell">Updated</div>
              </div>
              {glossaries.map((glossary) => (
                <GlossaryRow
                  key={glossary.id}
                  glossary={glossary}
                  isAdmin={isAdmin}
                  onDelete={() => {
                    setGlossaryPendingDelete(glossary);
                    setStatusNotice(null);
                  }}
                />
              ))}
            </div>
          )}
        </div>
      </section>

      {isAdmin ? (
        <>
          <Modal
            open={isCreateModalOpen}
            size="lg"
            ariaLabel="Create glossary"
            onClose={closeCreateModal}
            closeOnBackdrop
            className="glossary-admin-page__create-dialog"
          >
            <div className="glossary-admin-page__create-modal">
              <div className="modal__title">Create glossary</div>

              <div className="settings-field">
                <label className="settings-field__label" htmlFor="glossary-create-name">
                  Name
                </label>
                <input
                  id="glossary-create-name"
                  type="text"
                  className="settings-input"
                  value={newNameDraft}
                  onChange={(event) => {
                    setNewNameDraft(event.target.value);
                    setCreateModalError(null);
                  }}
                  placeholder="Glossary name"
                />
              </div>

              <div className="settings-field">
                <label className="settings-field__label" htmlFor="glossary-create-description">
                  Description
                </label>
                <textarea
                  id="glossary-create-description"
                  className="settings-input"
                  value={newDescriptionDraft}
                  onChange={(event) => {
                    setNewDescriptionDraft(event.target.value);
                    setCreateModalError(null);
                  }}
                  placeholder="What this glossary covers"
                />
              </div>

              <div className="settings-grid settings-grid--two-column">
                <div className="settings-field">
                  <label className="settings-field__label" htmlFor="glossary-create-priority">
                    Priority
                  </label>
                  <input
                    id="glossary-create-priority"
                    type="number"
                    className="settings-input"
                    value={newPriorityDraft}
                    onChange={(event) => {
                      setNewPriorityDraft(event.target.value);
                      setCreateModalError(null);
                    }}
                  />
                </div>

                <div className="settings-field">
                  <label className="settings-field__label" htmlFor="glossary-create-scope">
                    Scope
                  </label>
                  <select
                    id="glossary-create-scope"
                    className="settings-input"
                    value={newScopeModeDraft}
                    onChange={(event) => {
                      setNewScopeModeDraft(event.target.value as GlossaryScopeMode);
                      setCreateModalError(null);
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
                    checked={newEnabledDraft}
                    onChange={(event) => {
                      setNewEnabledDraft(event.target.checked);
                      setCreateModalError(null);
                    }}
                  />
                  <span>Enable this glossary</span>
                </label>
              </div>

              <div className="settings-field">
                <div className="settings-field__header">
                  <div className="settings-field__label">
                    {newScopeModeDraft === 'GLOBAL'
                      ? 'Excluded repositories'
                      : 'Selected repositories'}
                  </div>
                </div>
                <RepositoryMultiSelect
                  label={
                    newScopeModeDraft === 'GLOBAL'
                      ? 'Excluded repositories'
                      : 'Selected repositories'
                  }
                  options={sortedRepositoryOptions}
                  selectedIds={
                    newScopeModeDraft === 'GLOBAL'
                      ? newExcludedRepositoryIdsDraft
                      : newRepositoryIdsDraft
                  }
                  onChange={(next) => {
                    if (newScopeModeDraft === 'GLOBAL') {
                      setNewExcludedRepositoryIdsDraft([...next].sort((a, b) => a - b));
                    } else {
                      setNewRepositoryIdsDraft([...next].sort((a, b) => a - b));
                    }
                    setCreateModalError(null);
                  }}
                  className="settings-repository-select"
                  buttonAriaLabel="Select repositories for glossary"
                />
                <p className="settings-hint">
                  {newScopeModeDraft === 'GLOBAL'
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
                  selectedTags={newLocaleTagsDraft}
                  onChange={(next) => {
                    setNewLocaleTagsDraft(next);
                    setCreateModalError(null);
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

              {createModalError ? (
                <div className="settings-hint is-error">{createModalError}</div>
              ) : null}

              <div className="modal__actions">
                <button
                  type="button"
                  className="settings-button settings-button--ghost"
                  onClick={closeCreateModal}
                >
                  Cancel
                </button>
                <button
                  type="button"
                  className="settings-button settings-button--primary"
                  onClick={handleCreateGlossary}
                  disabled={createGlossaryMutation.isPending}
                >
                  Create glossary
                </button>
              </div>
            </div>
          </Modal>

          <ConfirmModal
            open={glossaryPendingDelete != null}
            title="Delete glossary"
            body={
              glossaryPendingDelete
                ? `Delete ${glossaryPendingDelete.name}? This soft-deletes the glossary and its backing repository.`
                : ''
            }
            confirmLabel="Delete"
            cancelLabel="Cancel"
            onCancel={() => {
              if (!deleteGlossaryMutation.isPending) {
                setGlossaryPendingDelete(null);
              }
            }}
            onConfirm={() => {
              if (glossaryPendingDelete?.id != null) {
                deleteGlossaryMutation.mutate(glossaryPendingDelete.id);
              }
            }}
            requireText={glossaryPendingDelete?.name}
          />
        </>
      ) : null}
    </div>
  );
}

function GlossaryRow({
  glossary,
  isAdmin,
  onDelete,
}: {
  glossary: ApiGlossarySummary;
  isAdmin: boolean;
  onDelete: () => void;
}) {
  return (
    <div className="glossary-admin-page__row">
      <div className="glossary-admin-page__cell glossary-admin-page__name-cell">
        <div className="glossary-admin-page__name-group">
          <Link
            className="settings-table__link glossary-admin-page__name-link"
            to={`/glossaries/${glossary.id}`}
          >
            {glossary.name}
          </Link>
          <span className="glossary-admin-page__id-text">#{glossary.id}</span>
        </div>
        <div className="glossary-admin-page__actions">
          <Link
            className="glossary-admin-page__row-action-link"
            to="/workbench"
            state={buildGlossaryWorkbenchState({
              glossaryId: glossary.id,
              glossaryName: glossary.name,
              backingRepositoryId: glossary.backingRepository.id,
              backingRepositoryName: glossary.backingRepository.name,
              assetPath: glossary.assetPath,
            })}
          >
            Workbench
          </Link>
          {isAdmin ? (
            <Link
              className="glossary-admin-page__row-action-link"
              to={`/glossaries/${glossary.id}/settings`}
            >
              Settings
            </Link>
          ) : null}
          {isAdmin ? (
            <button
              type="button"
              className="glossary-admin-page__row-action-link glossary-admin-page__row-action-button glossary-admin-page__row-action-button--danger"
              onClick={onDelete}
            >
              Delete
            </button>
          ) : null}
        </div>
      </div>
      <div className="glossary-admin-page__cell">
        {glossary.scopeMode === 'GLOBAL'
          ? glossary.repositoryCount > 0
            ? `Global (${glossary.repositoryCount} exclusions)`
            : 'Global'
          : `Selected (${glossary.repositoryCount})`}
      </div>
      <div className="glossary-admin-page__cell">{glossary.priority}</div>
      <div className="glossary-admin-page__cell">{glossary.enabled ? 'Enabled' : 'Disabled'}</div>
      <div className="glossary-admin-page__cell glossary-admin-page__cell--muted">
        {glossary.lastModifiedDate ? formatDateTime(glossary.lastModifiedDate) : 'Never'}
      </div>
    </div>
  );
}
