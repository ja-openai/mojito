import './review-projects-page.css';

import { useQuery } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';

import {
  fetchReviewFeatureOptions,
  type ApiReviewFeatureOption,
} from '../../api/review-features';
import { type ApiTeam, fetchTeams } from '../../api/teams';
import type { CollectionOption } from '../../components/CollectionSelect';
import { useCreateReviewProject } from '../../hooks/useCreateReviewProject';
import { useRepositories } from '../../hooks/useRepositories';
import { toDateTimeLocalInputValue } from '../../utils/dateTime';
import { useLocaleOptionsWithDisplayNames } from '../../utils/localeSelection';
import { useWorkbenchCollections } from '../workbench/useWorkbenchCollections';
import type {
  ReviewProjectCreateFormValues,
  ReviewProjectSourceMode,
} from './ReviewProjectCreateForm';
import { ReviewProjectCreateForm } from './ReviewProjectCreateForm';

type ReviewProjectNavState = {
  tmTextUnitIds?: number[];
  collectionName?: string | null;
  collectionId?: string | null;
  defaultName?: string;
  defaultDueDate?: string;
};

function isReviewProjectNavState(value: unknown): value is ReviewProjectNavState {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Partial<ReviewProjectNavState>;
  const isNumberArray = (input: unknown): input is number[] =>
    Array.isArray(input) && input.every((item) => typeof item === 'number');
  const isOptionalString = (input: unknown): input is string | null | undefined =>
    input === undefined || input === null || typeof input === 'string';

  return (
    (candidate.tmTextUnitIds === undefined || isNumberArray(candidate.tmTextUnitIds)) &&
    isOptionalString(candidate.collectionName) &&
    isOptionalString(candidate.collectionId) &&
    (candidate.defaultName === undefined || typeof candidate.defaultName === 'string') &&
    (candidate.defaultDueDate === undefined || typeof candidate.defaultDueDate === 'string')
  );
}

function getCreateReviewProjectErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    return typeof error.message === 'string' ? error.message.trim() : '';
  }
  if (typeof error === 'string') {
    return error.trim();
  }
  if (error && typeof error === 'object') {
    const candidate = error as { message?: unknown; error?: unknown };
    return (
      getCreateReviewProjectErrorMessage(candidate.message) ||
      getCreateReviewProjectErrorMessage(candidate.error)
    );
  }
  return '';
}

export function ReviewProjectCreatePage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { data: repositories = [] } = useRepositories();
  const { collections, activeCollection } = useWorkbenchCollections();
  const [tmIds, setTmIds] = useState<number[]>([]);
  const [selectedCollectionId, setSelectedCollectionId] = useState<string | null>(null);
  const [sourceMode, setSourceMode] = useState<ReviewProjectSourceMode>('TEXT_UNITS');
  const [selectedReviewFeatureId, setSelectedReviewFeatureId] = useState<number | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [prefillName, setPrefillName] = useState('Review project');
  const [prefillDueDate, setPrefillDueDate] = useState<string | null>(null);
  const [prefillCollectionName, setPrefillCollectionName] = useState<string | null>(null);
  const [selectedTeamId, setSelectedTeamId] = useState<number | null>(null);

  const createReviewProject = useCreateReviewProject();
  const teamsQuery = useQuery<ApiTeam[]>({
    queryKey: ['teams', 'review-project-create'],
    queryFn: fetchTeams,
    staleTime: 30_000,
  });
  const reviewFeatureOptionsQuery = useQuery<ApiReviewFeatureOption[]>({
    queryKey: ['review-feature-options', 'review-project-create'],
    queryFn: fetchReviewFeatureOptions,
    staleTime: 30_000,
  });

  const defaultDueDate = useMemo(
    () => toDateTimeLocalInputValue(new Date(Date.now() + 1 * 24 * 60 * 60 * 1000)),
    [],
  );

  const localeOptions = useLocaleOptionsWithDisplayNames(repositories, undefined);
  const collectionOptions = useMemo<CollectionOption[]>(
    () =>
      [...collections]
        .filter((collection) => collection.entries.length > 0)
        .sort((a, b) => b.updatedAt - a.updatedAt)
        .map((collection) => ({
          id: collection.id,
          name: collection.name || 'Untitled collection',
          size: collection.entries.length,
        })),
    [collections],
  );
  const reviewFeatureOptions = reviewFeatureOptionsQuery.data ?? [];
  const hasCollections = collectionOptions.length > 0;
  const hasReviewFeatures = reviewFeatureOptions.length > 0;
  const showCreateForm = hasCollections || hasReviewFeatures;
  const navState = isReviewProjectNavState(location.state) ? location.state : null;

  useEffect(() => {
    const state = navState;
    if (!state) {
      return;
    }
    if (state.tmTextUnitIds?.length) {
      const unique = Array.from(new Set(state.tmTextUnitIds));
      setTmIds(unique);
    }
    if (state.collectionName) {
      setPrefillCollectionName(state.collectionName);
    }
    if (state.collectionId) {
      setSelectedCollectionId(state.collectionId);
    }
    if (state.defaultName) {
      setPrefillName(state.defaultName);
    }
    if (state.defaultDueDate) {
      setPrefillDueDate(state.defaultDueDate);
    }
  }, [navState]);

  useEffect(() => {
    if (!hasCollections && hasReviewFeatures) {
      setSourceMode('REVIEW_FEATURE');
      return;
    }
    if (!hasReviewFeatures && hasCollections) {
      setSourceMode('TEXT_UNITS');
    }
  }, [hasCollections, hasReviewFeatures]);

  useEffect(() => {
    if (selectedCollectionId === null) {
      return;
    }
    const collection = collections.find((item) => item.id === selectedCollectionId);
    if (!collection) {
      return;
    }
    if (collection.entries.length === 0) {
      setTmIds([]);
      setPrefillCollectionName(collection.name);
      return;
    }
    const nextTmIds = Array.from(new Set(collection.entries.map((entry) => entry.tmTextUnitId)));
    setTmIds(nextTmIds);
    setPrefillCollectionName(collection.name);
  }, [collections, selectedCollectionId]);

  useEffect(() => {
    if (navState?.collectionId) {
      return;
    }
    if (selectedCollectionId || tmIds.length || !activeCollection) {
      return;
    }
    if (activeCollection.entries.length === 0) {
      return;
    }
    setSelectedCollectionId(activeCollection.id);
  }, [activeCollection, navState?.collectionId, selectedCollectionId, tmIds.length]);

  const handleSubmit = useCallback(
    (values: ReviewProjectCreateFormValues) => {
      if (createReviewProject.isPending) return;
      if (sourceMode === 'TEXT_UNITS' && !tmIds.length) {
        setErrorMessage('Add at least one text unit id.');
        return;
      }
      if (sourceMode === 'REVIEW_FEATURE' && values.reviewFeatureId == null) {
        setErrorMessage('Select a review feature.');
        return;
      }
      setErrorMessage(null);
      createReviewProject.mutate(
        {
          localeTags: values.localeTags,
          notes: values.notes,
          type: values.type,
          dueDate: values.dueDate,
          tmTextUnitIds: sourceMode === 'TEXT_UNITS' ? tmIds : null,
          reviewFeatureId: sourceMode === 'REVIEW_FEATURE' ? values.reviewFeatureId : null,
          screenshotImageIds: values.screenshotImageIds,
          name: values.name,
          teamId: values.teamId ?? null,
        },
        {
          onSuccess: (response) => {
            const requestId = response.requestId ?? null;
            void navigate('/review-projects', {
              state: { requestId },
            });
          },
          onError: (err: unknown) => {
            setErrorMessage(getCreateReviewProjectErrorMessage(err) || 'Failed to create project');
          },
        },
      );
    },
    [createReviewProject, navigate, sourceMode, tmIds],
  );

  return (
    <div className="review-projects-page review-projects-create">
      <div className="review-projects-page__bar">
        <div className="review-projects-page__summary-bar" style={{ width: '100%' }}>
          <div className="modal__title">New review project</div>
        </div>
      </div>

      <div className="review-create__page-shell">
        {showCreateForm ? (
          <ReviewProjectCreateForm
            defaultName={prefillName || 'Review project'}
            defaultDueDate={prefillDueDate ?? defaultDueDate}
            localeOptions={localeOptions}
            tmTextUnitIds={tmIds}
            sourceMode={sourceMode}
            onChangeSourceMode={setSourceMode}
            collectionName={prefillCollectionName ?? null}
            collectionOptions={collectionOptions}
            selectedCollectionId={selectedCollectionId}
            onChangeCollection={(id) => {
              setSelectedCollectionId(id);
              if (!id) {
                setPrefillCollectionName(null);
              }
            }}
            reviewFeatureOptions={reviewFeatureOptions}
            selectedReviewFeatureId={selectedReviewFeatureId}
            onChangeReviewFeature={setSelectedReviewFeatureId}
            teamOptions={teamsQuery.data ?? []}
            selectedTeamId={selectedTeamId}
            onChangeTeam={setSelectedTeamId}
            isSubmitting={createReviewProject.isPending}
            errorMessage={errorMessage}
            submitLabel="Create"
            onSubmit={handleSubmit}
            onCancel={() => {
              void navigate(-1);
            }}
          />
        ) : (
          <div className="review-create__empty">
            <div className="review-create__empty-title">No collections or review features yet</div>
            <div className="review-create__empty-body">
              Review projects can be created from Workbench collections or review features.
            </div>
            <Link className="review-create__empty-cta" to="/workbench">
              Open Workbench
            </Link>
          </div>
        )}
      </div>
    </div>
  );
}
