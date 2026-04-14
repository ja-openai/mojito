import './settings-page.css';
import './admin-review-features-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';

import {
  type ApiReviewFeatureSummary,
  createReviewFeature,
  deleteReviewFeature,
  fetchReviewFeatures,
} from '../../api/review-features';
import { ConfirmModal } from '../../components/ConfirmModal';
import { Modal } from '../../components/Modal';
import { NumericPresetDropdown } from '../../components/NumericPresetDropdown';
import { RepositoryMultiSelect } from '../../components/RepositoryMultiSelect';
import { useUser } from '../../components/RequireUser';
import { SearchControl } from '../../components/SearchControl';
import { useRepositories } from '../../hooks/useRepositories';
import { formatLocalDateTime as formatDateTime } from '../../utils/dateTime';
import { useRepositorySelectionOptions } from '../../utils/repositorySelection';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

type StatusNotice = {
  kind: 'success' | 'error';
  message: string;
};

type EnabledFilter = 'enabled' | 'disabled' | 'all';

const LIMIT_PRESETS = [25, 50, 100];

const normalizeFeatureName = (value: string) => value.trim().replace(/\s+/g, ' ');

export function AdminReviewFeaturesPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: repositories } = useRepositories();
  const repositoryOptions = useRepositorySelectionOptions(repositories ?? []);

  const [searchQuery, setSearchQuery] = useState('');
  const [enabledFilter, setEnabledFilter] = useState<EnabledFilter>('enabled');
  const [limit, setLimit] = useState(50);
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [newNameDraft, setNewNameDraft] = useState('');
  const [newEnabledDraft, setNewEnabledDraft] = useState(true);
  const [newRepositoryIdsDraft, setNewRepositoryIdsDraft] = useState<number[]>([]);
  const [createModalError, setCreateModalError] = useState<string | null>(null);
  const [statusNotice, setStatusNotice] = useState<StatusNotice | null>(null);
  const [featurePendingDelete, setFeaturePendingDelete] = useState<ApiReviewFeatureSummary | null>(
    null,
  );

  const reviewFeaturesQuery = useQuery({
    queryKey: ['review-features', searchQuery, enabledFilter, limit],
    queryFn: () =>
      fetchReviewFeatures({
        search: searchQuery,
        enabled: enabledFilter === 'all' ? null : enabledFilter === 'enabled' ? true : false,
        limit,
      }),
    enabled: isAdmin,
    staleTime: 30_000,
  });

  const createFeatureMutation = useMutation({
    mutationFn: createReviewFeature,
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: ['review-features'] });
      setIsCreateModalOpen(false);
      setNewNameDraft('');
      setNewEnabledDraft(true);
      setNewRepositoryIdsDraft([]);
      setCreateModalError(null);
      setStatusNotice({
        kind: 'success',
        message: `Created review feature ${created.name} (#${created.id}).`,
      });
    },
    onError: (error: Error) => {
      setCreateModalError(error.message || 'Failed to create review feature.');
    },
  });

  const deleteFeatureMutation = useMutation({
    mutationFn: (featureId: number) => deleteReviewFeature(featureId),
    onSuccess: async () => {
      const deletedName = featurePendingDelete?.name;
      await queryClient.invalidateQueries({ queryKey: ['review-features'] });
      setFeaturePendingDelete(null);
      setStatusNotice({
        kind: 'success',
        message: deletedName ? `Deleted review feature ${deletedName}.` : 'Deleted review feature.',
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to delete review feature.',
      });
    },
  });

  const features = reviewFeaturesQuery.data?.reviewFeatures ?? [];
  const totalCount = reviewFeaturesQuery.data?.totalCount ?? 0;

  const visibleCountLabel = useMemo(() => {
    if (features.length === totalCount) {
      return `${features.length} review features`;
    }
    return `${features.length} of ${totalCount} review features`;
  }, [features.length, totalCount]);

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  const handleCreateFeature = () => {
    const normalizedName = normalizeFeatureName(newNameDraft);
    if (!normalizedName) {
      setCreateModalError('Review feature name is required.');
      return;
    }
    createFeatureMutation.mutate({
      name: normalizedName,
      enabled: newEnabledDraft,
      repositoryIds: [...newRepositoryIdsDraft].sort((a, b) => a - b),
    });
  };

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
        context="Settings"
        title="Review features"
      />
      <div className="settings-page settings-page--wide review-feature-admin-page">
        <section className="settings-card">
          <div className="settings-card__content">
            <div className="review-feature-admin-page__toolbar">
              <SearchControl
                value={searchQuery}
                onChange={setSearchQuery}
                placeholder="Search review features"
                className="review-feature-admin-page__search"
              />
              <label className="review-feature-admin-page__filter">
                <span className="review-feature-admin-page__filter-label">Status</span>
                <select
                  className="settings-input review-feature-admin-page__filter-select"
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
                ariaLabel="Review feature result size"
                pillsClassName="settings-pills"
                optionClassName="settings-pill"
                optionActiveClassName="is-active"
                customActiveClassName="is-active"
                customButtonClassName="settings-pill"
                customInitialValue={50}
              />
              <div className="review-feature-admin-page__toolbar-actions">
                <button
                  type="button"
                  className="settings-button"
                  onClick={() => void navigate('/settings/system/review-features/batch')}
                >
                  Batch update
                </button>
                <button
                  type="button"
                  className="settings-button settings-button--primary"
                  onClick={() => {
                    setIsCreateModalOpen(true);
                    setCreateModalError(null);
                    setStatusNotice(null);
                  }}
                >
                  Create feature
                </button>
              </div>
            </div>

            <div className="review-feature-admin-page__count">
              <span className="review-feature-admin-page__count-text">{visibleCountLabel}</span>
              {statusNotice ? (
                <span
                  className={`settings-hint review-feature-admin-page__status${
                    statusNotice.kind === 'error' ? ' is-error' : ''
                  }`}
                >
                  {statusNotice.message}
                </span>
              ) : null}
            </div>

            {reviewFeaturesQuery.isError ? (
              <p className="review-feature-admin-page__empty">
                {reviewFeaturesQuery.error instanceof Error
                  ? reviewFeaturesQuery.error.message
                  : 'Could not load review features.'}
              </p>
            ) : reviewFeaturesQuery.isLoading ? (
              <p className="review-feature-admin-page__empty">Loading review features…</p>
            ) : features.length === 0 ? (
              <p className="review-feature-admin-page__empty">
                No review features match the current filters.
              </p>
            ) : (
              <div className="review-feature-admin-page__table">
                <div className="review-feature-admin-page__table-header">
                  <div className="review-feature-admin-page__cell">Feature</div>
                  <div className="review-feature-admin-page__cell">Status</div>
                  <div className="review-feature-admin-page__cell">Repositories</div>
                  <div className="review-feature-admin-page__cell">Updated</div>
                </div>
                {features.map((feature) => (
                  <FeatureRow
                    key={feature.id}
                    feature={feature}
                    onDelete={() => {
                      setFeaturePendingDelete(feature);
                      setStatusNotice(null);
                    }}
                  />
                ))}
              </div>
            )}
          </div>
        </section>

        <Modal
          open={isCreateModalOpen}
          size="lg"
          ariaLabel="Create review feature"
          onClose={() => setIsCreateModalOpen(false)}
          closeOnBackdrop
          className="review-feature-admin-page__create-dialog"
        >
          <div className="review-feature-admin-page__create-modal">
            <div className="modal__title">Create review feature</div>
            <div className="settings-field">
              <label className="settings-field__label" htmlFor="create-review-feature-name">
                Name
              </label>
              <input
                id="create-review-feature-name"
                type="text"
                className="settings-input"
                value={newNameDraft}
                onChange={(event) => {
                  setNewNameDraft(event.target.value);
                  setCreateModalError(null);
                }}
                placeholder="Review feature name"
              />
            </div>
            <div className="settings-field">
              <label className="settings-toggle">
                <input
                  type="checkbox"
                  checked={newEnabledDraft}
                  onChange={(event) => setNewEnabledDraft(event.target.checked)}
                />
                <span>Enable this review feature</span>
              </label>
            </div>
            <div className="settings-field">
              <div className="settings-field__header">
                <div className="settings-field__label">Repositories</div>
              </div>
              <RepositoryMultiSelect
                label="Repositories"
                options={repositoryOptions}
                selectedIds={newRepositoryIdsDraft}
                onChange={(next) => setNewRepositoryIdsDraft([...next].sort((a, b) => a - b))}
                className="settings-repository-select"
                buttonAriaLabel="Select repositories for new review feature"
              />
              <p className="settings-hint">
                Start with repositories only. Future filters can extend the feature scope later.
              </p>
              {createModalError ? (
                <p className="settings-hint is-error">{createModalError}</p>
              ) : null}
            </div>
          </div>
          <div className="modal__actions">
            <button
              type="button"
              className="modal__button"
              onClick={() => {
                setIsCreateModalOpen(false);
                setCreateModalError(null);
              }}
            >
              Cancel
            </button>
            <button
              type="button"
              className="modal__button modal__button--primary"
              onClick={handleCreateFeature}
              disabled={createFeatureMutation.isPending}
            >
              Create
            </button>
          </div>
        </Modal>

        <ConfirmModal
          open={featurePendingDelete != null}
          title="Delete review feature"
          body={
            featurePendingDelete == null
              ? ''
              : `Delete ${featurePendingDelete.name}? This only removes the feature configuration.`
          }
          confirmLabel="Delete"
          cancelLabel="Cancel"
          onConfirm={() => {
            if (featurePendingDelete == null) {
              return;
            }
            deleteFeatureMutation.mutate(featurePendingDelete.id);
          }}
          onCancel={() => {
            if (!deleteFeatureMutation.isPending) {
              setFeaturePendingDelete(null);
            }
          }}
          requireText={featurePendingDelete?.name}
          requireTextLabel="Type the feature name to confirm deletion."
        />
      </div>
    </div>
  );
}

function FeatureRow({
  feature,
  onDelete,
}: {
  feature: ApiReviewFeatureSummary;
  onDelete: () => void;
}) {
  const repositorySummary =
    feature.repositoryCount === 0
      ? 'No repositories'
      : feature.repositoryCount === 1
        ? (feature.repositories[0]?.name ?? '1 repository')
        : feature.repositoryCount === 2
          ? feature.repositories.map((repository) => repository.name).join(', ')
          : `${feature.repositories[0]?.name ?? 'Repository'} +${feature.repositoryCount - 1}`;

  return (
    <div className="review-feature-admin-page__row">
      <div className="review-feature-admin-page__cell">
        <div className="review-feature-admin-page__name-cell">
          <span className="review-feature-admin-page__name-group">
            <span className="review-feature-admin-page__name-text">{feature.name}</span>
            <span className="review-feature-admin-page__id-text">#{feature.id}</span>
          </span>
          <span className="review-feature-admin-page__actions">
            <Link
              className="review-feature-admin-page__row-action-link"
              to={`/settings/system/review-features/${feature.id}`}
            >
              Edit
            </Link>
            <button
              type="button"
              className="review-feature-admin-page__row-action-link review-feature-admin-page__row-action-button"
              onClick={onDelete}
            >
              Delete
            </button>
          </span>
        </div>
      </div>
      <div className="review-feature-admin-page__cell review-feature-admin-page__cell--muted">
        {feature.enabled ? 'Enabled' : 'Disabled'}
      </div>
      <div
        className="review-feature-admin-page__cell review-feature-admin-page__cell--muted"
        title={feature.repositories.map((repository) => repository.name).join(', ')}
      >
        {repositorySummary}
      </div>
      <div
        className="review-feature-admin-page__cell review-feature-admin-page__cell--muted"
        title={formatDateTime(feature.lastModifiedDate)}
      >
        {formatDateTime(feature.lastModifiedDate)}
      </div>
    </div>
  );
}
