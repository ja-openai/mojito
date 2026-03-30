import './review-projects-page.css';

import { useQuery } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';

import { type ApiReviewFeatureOption, fetchReviewFeatureOptions } from '../../api/review-features';
import {
  REVIEW_PROJECT_CREATE_STATUS_FILTERS,
  type ReviewProjectCreateResponse,
  type ReviewProjectCreateStatusFilter,
} from '../../api/review-projects';
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
  statusFilter?: ReviewProjectCreateStatusFilter | null;
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
  const isOptionalStatusFilter = (
    input: unknown,
  ): input is ReviewProjectCreateStatusFilter | null | undefined =>
    input === undefined ||
    input === null ||
    (typeof input === 'string' &&
      (REVIEW_PROJECT_CREATE_STATUS_FILTERS as readonly string[]).includes(input));

  return (
    (candidate.tmTextUnitIds === undefined || isNumberArray(candidate.tmTextUnitIds)) &&
    isOptionalString(candidate.collectionName) &&
    isOptionalString(candidate.collectionId) &&
    (candidate.defaultName === undefined || typeof candidate.defaultName === 'string') &&
    (candidate.defaultDueDate === undefined || typeof candidate.defaultDueDate === 'string') &&
    isOptionalStatusFilter(candidate.statusFilter)
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

function buildFeatureScopedRequestName(baseName: string, featureName: string) {
  const trimmedBaseName = baseName.trim();
  const trimmedFeatureName = featureName.trim();
  if (!trimmedFeatureName) {
    return trimmedBaseName;
  }
  return `${trimmedBaseName} · ${trimmedFeatureName}`;
}

type CreateSubmissionReport = {
  createdRequestIds: number[];
  createdProjectRequestCount: number;
  createdProjectCount: number;
  createdLocaleCount: number;
  skippedLocaleCount: number;
  erroredLocaleCount: number;
  responses: ReviewProjectCreateResponse[];
};

function buildCreateSubmissionReport(
  responses: ReviewProjectCreateResponse[],
): CreateSubmissionReport {
  return {
    createdRequestIds: responses.flatMap((response) =>
      response.requestId == null ? [] : [response.requestId],
    ),
    createdProjectRequestCount: responses.filter((response) => response.requestId != null).length,
    createdProjectCount: responses.reduce(
      (total, response) => total + response.projectIds.length,
      0,
    ),
    createdLocaleCount: responses.reduce(
      (total, response) => total + response.createdLocaleCount,
      0,
    ),
    skippedLocaleCount: responses.reduce(
      (total, response) => total + response.skippedLocaleCount,
      0,
    ),
    erroredLocaleCount: responses.reduce(
      (total, response) => total + response.erroredLocaleCount,
      0,
    ),
    responses,
  };
}

export function ReviewProjectCreatePage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { data: repositories = [] } = useRepositories();
  const { collections, activeCollection } = useWorkbenchCollections();
  const [tmIds, setTmIds] = useState<number[]>([]);
  const [selectedCollectionId, setSelectedCollectionId] = useState<string | null>(null);
  const [sourceMode, setSourceMode] = useState<ReviewProjectSourceMode>('TEXT_UNITS');
  const [selectedReviewFeatureIds, setSelectedReviewFeatureIds] = useState<number[]>([]);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [prefillName, setPrefillName] = useState('Review project');
  const [prefillDueDate, setPrefillDueDate] = useState<string | null>(null);
  const [prefillCollectionName, setPrefillCollectionName] = useState<string | null>(null);
  const [selectedTeamId, setSelectedTeamId] = useState<number | null>(null);
  const [selectedStatusFilter, setSelectedStatusFilter] =
    useState<ReviewProjectCreateStatusFilter>('ALL');
  const [statusFilterWasCustomized, setStatusFilterWasCustomized] = useState(false);
  const [submissionReport, setSubmissionReport] = useState<CreateSubmissionReport | null>(null);

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
  const reviewFeatureOptions = useMemo(
    () => reviewFeatureOptionsQuery.data ?? [],
    [reviewFeatureOptionsQuery.data],
  );
  const reviewFeaturesById = useMemo(
    () => new Map(reviewFeatureOptions.map((feature) => [feature.id, feature])),
    [reviewFeatureOptions],
  );
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
    if (state.statusFilter) {
      setSelectedStatusFilter(state.statusFilter);
      setStatusFilterWasCustomized(true);
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
    if (statusFilterWasCustomized) {
      return;
    }
    setSelectedStatusFilter(sourceMode === 'REVIEW_FEATURE' ? 'REVIEW_NEEDED' : 'ALL');
  }, [sourceMode, statusFilterWasCustomized]);

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
        setSubmissionReport(null);
        return;
      }
      if (sourceMode === 'REVIEW_FEATURE' && !values.reviewFeatureIds?.length) {
        setErrorMessage('Select at least one review feature.');
        setSubmissionReport(null);
        return;
      }
      setErrorMessage(null);
      setSubmissionReport(null);
      void (async () => {
        try {
          if (sourceMode === 'TEXT_UNITS') {
            const response = await createReviewProject.mutateAsync({
              localeTags: values.localeTags,
              notes: values.notes,
              type: values.type,
              dueDate: values.dueDate,
              tmTextUnitIds: tmIds,
              reviewFeatureId: null,
              statusFilter: values.statusFilter,
              skipTextUnitsInOpenProjects: values.skipTextUnitsInOpenProjects,
              screenshotImageIds: values.screenshotImageIds,
              name: values.name,
              teamId: values.teamId ?? null,
            });
            const report = buildCreateSubmissionReport([response]);
            if (report.skippedLocaleCount === 0 && report.erroredLocaleCount === 0) {
              const requestId = response.requestId ?? null;
              void navigate('/review-projects', {
                state: { requestId },
              });
              return;
            }
            setSubmissionReport(report);
            return;
          }

          const responses: ReviewProjectCreateResponse[] = [];
          for (const reviewFeatureId of values.reviewFeatureIds ?? []) {
            const feature = reviewFeaturesById.get(reviewFeatureId);
            const response = await createReviewProject.mutateAsync({
              localeTags: values.localeTags,
              notes: values.notes,
              type: values.type,
              dueDate: values.dueDate,
              tmTextUnitIds: null,
              reviewFeatureId,
              statusFilter: values.statusFilter,
              skipTextUnitsInOpenProjects: values.skipTextUnitsInOpenProjects,
              screenshotImageIds: values.screenshotImageIds,
              name: buildFeatureScopedRequestName(values.name, feature?.name ?? ''),
              teamId: values.teamId ?? null,
            });
            responses.push(response);
          }
          const report = buildCreateSubmissionReport(responses);
          if (report.skippedLocaleCount === 0 && report.erroredLocaleCount === 0) {
            void navigate('/review-projects', {
              state:
                report.createdRequestIds.length === 1
                  ? { requestId: report.createdRequestIds[0] }
                  : undefined,
            });
            return;
          }
          setSubmissionReport(report);
        } catch (err: unknown) {
          setErrorMessage(getCreateReviewProjectErrorMessage(err) || 'Failed to create project');
          setSubmissionReport(null);
        }
      })();
    },
    [createReviewProject, navigate, reviewFeaturesById, sourceMode, tmIds],
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
          <>
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
              selectedReviewFeatureIds={selectedReviewFeatureIds}
              onChangeReviewFeatures={setSelectedReviewFeatureIds}
              teamOptions={teamsQuery.data ?? []}
              selectedTeamId={selectedTeamId}
              onChangeTeam={setSelectedTeamId}
              selectedStatusFilter={selectedStatusFilter}
              onChangeStatusFilter={(next) => {
                setSelectedStatusFilter(next);
                setStatusFilterWasCustomized(true);
              }}
              isSubmitting={createReviewProject.isPending}
              errorMessage={errorMessage}
              submitLabel="Create"
              onSubmit={handleSubmit}
              onCancel={() => {
                void navigate(-1);
              }}
            />
            {submissionReport ? (
              <div
                className={`review-create__report${
                  submissionReport.erroredLocaleCount > 0 ? ' is-error' : ''
                }`}
              >
                <div className="review-create__report-title">Creation report</div>
                <div className="review-create__report-summary">
                  Created {submissionReport.createdProjectRequestCount} request
                  {submissionReport.createdProjectRequestCount === 1 ? '' : 's'},{' '}
                  {submissionReport.createdProjectCount} project
                  {submissionReport.createdProjectCount === 1 ? '' : 's'} across{' '}
                  {submissionReport.createdLocaleCount} locale
                  {submissionReport.createdLocaleCount === 1 ? '' : 's'}.
                </div>
                {submissionReport.skippedLocaleCount > 0 ? (
                  <div className="review-create__report-summary">
                    Skipped {submissionReport.skippedLocaleCount} locale
                    {submissionReport.skippedLocaleCount === 1 ? '' : 's'} with no matching text
                    units.
                  </div>
                ) : null}
                {submissionReport.erroredLocaleCount > 0 ? (
                  <div className="review-create__report-summary">
                    Encountered errors in {submissionReport.erroredLocaleCount} locale
                    {submissionReport.erroredLocaleCount === 1 ? '' : 's'}.
                  </div>
                ) : null}
                {submissionReport.createdRequestIds.length === 1 ? (
                  <div className="review-create__report-actions">
                    <button
                      type="button"
                      className="review-create__ghost"
                      onClick={() =>
                        void navigate('/review-projects', {
                          state: { requestId: submissionReport.createdRequestIds[0] },
                        })
                      }
                    >
                      Open created request
                    </button>
                  </div>
                ) : submissionReport.createdRequestIds.length > 1 ? (
                  <div className="review-create__report-actions">
                    <button
                      type="button"
                      className="review-create__ghost"
                      onClick={() => void navigate('/review-projects')}
                    >
                      Open review projects
                    </button>
                  </div>
                ) : null}
                <div className="review-create__report-list">
                  {submissionReport.responses.map((response, index) => {
                    const notableLocaleResults = response.localeResults.filter(
                      (localeResult) => localeResult.status !== 'CREATED',
                    );
                    if (notableLocaleResults.length === 0) {
                      return null;
                    }
                    return (
                      <div key={`${response.requestName ?? 'request'}-${index}`}>
                        <div className="review-create__report-request-name">
                          {response.requestName ?? 'Review project'}
                        </div>
                        {notableLocaleResults.map((localeResult) => (
                          <div
                            key={`${response.requestName ?? 'request'}-${localeResult.localeTag}-${localeResult.status}`}
                            className="review-create__report-item"
                          >
                            <span className="review-create__report-locale">
                              {localeResult.localeTag}
                            </span>
                            <span className="review-create__report-message">
                              {localeResult.status === 'SKIPPED_NO_TEXT_UNITS'
                                ? 'No matching text units'
                                : (localeResult.message ?? 'Unexpected error')}
                            </span>
                          </div>
                        ))}
                      </div>
                    );
                  })}
                </div>
              </div>
            ) : null}
          </>
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
