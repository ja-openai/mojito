import './settings-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Navigate, useParams } from 'react-router-dom';

import { fetchReviewFeature, updateReviewFeature } from '../../api/review-features';
import { RepositoryMultiSelect } from '../../components/RepositoryMultiSelect';
import { useUser } from '../../components/RequireUser';
import { useRepositories } from '../../hooks/useRepositories';
import { useRepositorySelectionOptions } from '../../utils/repositorySelection';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

const normalizeFeatureName = (value: string) => value.trim().replace(/\s+/g, ' ');

export function AdminReviewFeatureDetailPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();
  const params = useParams<{ featureId?: string }>();
  const { data: repositories } = useRepositories();
  const repositoryOptions = useRepositorySelectionOptions(repositories ?? []);

  const [nameDraft, setNameDraft] = useState('');
  const [enabledDraft, setEnabledDraft] = useState(true);
  const [repositoryIdsDraft, setRepositoryIdsDraft] = useState<number[]>([]);
  const [statusNotice, setStatusNotice] = useState<{
    kind: 'success' | 'error';
    message: string;
  } | null>(null);

  const parsedFeatureId = useMemo(() => {
    const raw = params.featureId?.trim();
    if (!raw) {
      return null;
    }
    const next = Number(raw);
    return Number.isInteger(next) && next > 0 ? next : null;
  }, [params.featureId]);

  const featureQuery = useQuery({
    queryKey: ['review-feature', parsedFeatureId],
    queryFn: () => fetchReviewFeature(parsedFeatureId as number),
    enabled: isAdmin && parsedFeatureId != null,
    staleTime: 30_000,
  });

  const updateMutation = useMutation({
    mutationFn: (payload: { name: string; enabled: boolean; repositoryIds: number[] }) =>
      updateReviewFeature(parsedFeatureId as number, payload),
    onSuccess: async (updated) => {
      queryClient.setQueryData(['review-feature', parsedFeatureId], updated);
      await queryClient.invalidateQueries({ queryKey: ['review-features'] });
      await queryClient.invalidateQueries({ queryKey: ['review-feature', parsedFeatureId] });
      setStatusNotice({
        kind: 'success',
        message: `Saved review feature ${updated.name}.`,
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to save review feature.',
      });
    },
  });

  useEffect(() => {
    const feature = featureQuery.data;
    if (!feature) {
      return;
    }
    setNameDraft(feature.name);
    setEnabledDraft(feature.enabled);
    setRepositoryIdsDraft(
      feature.repositories.map((repository) => repository.id).sort((a, b) => a - b),
    );
  }, [featureQuery.data]);

  const isDirty = useMemo(() => {
    const feature = featureQuery.data;
    if (!feature) {
      return false;
    }
    const normalizedName = normalizeFeatureName(nameDraft);
    if (normalizedName !== feature.name) {
      return true;
    }
    if (enabledDraft !== feature.enabled) {
      return true;
    }
    const existingIds = feature.repositories
      .map((repository) => repository.id)
      .sort((a, b) => a - b);
    if (existingIds.length !== repositoryIdsDraft.length) {
      return true;
    }
    return existingIds.some((id, index) => id !== repositoryIdsDraft[index]);
  }, [enabledDraft, featureQuery.data, nameDraft, repositoryIdsDraft]);

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  if (parsedFeatureId == null) {
    return <Navigate to="/settings/system/review-features" replace />;
  }

  const handleSave = () => {
    const normalizedName = normalizeFeatureName(nameDraft);
    if (!normalizedName) {
      setStatusNotice({ kind: 'error', message: 'Review feature name is required.' });
      return;
    }
    updateMutation.mutate({
      name: normalizedName,
      enabled: enabledDraft,
      repositoryIds: [...repositoryIdsDraft].sort((a, b) => a - b),
    });
  };

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system/review-features"
        backLabel="Back to review features"
        context="Settings > Review features > Review feature"
        title={featureQuery.data?.name || `Feature #${parsedFeatureId}`}
      />
      <div className="settings-page">
        <section className="settings-card">
          {featureQuery.isError ? (
            <p className="settings-hint is-error">
              {featureQuery.error instanceof Error
                ? featureQuery.error.message
                : 'Could not load review feature.'}
            </p>
          ) : featureQuery.isLoading ? (
            <p className="settings-hint">Loading review feature…</p>
          ) : (
            <>
              <div className="settings-field">
                <label className="settings-field__label" htmlFor="review-feature-name">
                  Name
                </label>
                <input
                  id="review-feature-name"
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
                <label className="settings-toggle">
                  <input
                    type="checkbox"
                    checked={enabledDraft}
                    onChange={(event) => {
                      setEnabledDraft(event.target.checked);
                      setStatusNotice(null);
                    }}
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
                  selectedIds={repositoryIdsDraft}
                  onChange={(next) => {
                    setRepositoryIdsDraft([...next].sort((a, b) => a - b));
                    setStatusNotice(null);
                  }}
                  className="settings-repository-select"
                  buttonAriaLabel="Select repositories for review feature"
                />
                <p className="settings-hint">
                  This defines the current feature scope for automated grouping.
                </p>
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
