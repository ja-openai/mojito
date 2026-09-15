import './review-projects-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';

import {
  createIncidentReviewProjects,
  type IncidentReviewProjectRequest,
  type IncidentReviewProjectResult,
  previewIncidentReviewProjects,
  type ReviewSource,
} from '../../api/incident-review-projects';
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
import { useRepositorySelectionOptions } from '../../utils/repositorySelection';
import { useWorkbenchCollections } from '../workbench/useWorkbenchCollections';
import type {
  ReviewProjectCreateFormValues,
  ReviewProjectSourceMode,
} from './ReviewProjectCreateForm';
import { ReviewProjectCreateForm } from './ReviewProjectCreateForm';

type ReviewProjectNavState = {
  tmTextUnitIds?: number[];
  repositoryIds?: number[];
  collectionName?: string | null;
  collectionId?: string | null;
  defaultName?: string;
  defaultDueDate?: string;
  statusFilter?: ReviewProjectCreateStatusFilter | null;
  sourceMode?: string | null;
};

const REPOSITORY_ID_QUERY_PARAMS = ['repositoryId', 'repositoryIds', 'repositoryIds[]'];

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
    (candidate.repositoryIds === undefined || isNumberArray(candidate.repositoryIds)) &&
    isOptionalString(candidate.collectionName) &&
    isOptionalString(candidate.collectionId) &&
    (candidate.defaultName === undefined || typeof candidate.defaultName === 'string') &&
    (candidate.defaultDueDate === undefined || typeof candidate.defaultDueDate === 'string') &&
    isOptionalStatusFilter(candidate.statusFilter) &&
    (candidate.sourceMode === undefined ||
      candidate.sourceMode === null ||
      typeof candidate.sourceMode === 'string')
  );
}

function parseReviewProjectSourceMode(
  value: string | null | undefined,
): ReviewProjectSourceMode | null {
  const normalized = value?.trim().toLowerCase().replace(/_/g, '-');
  if (!normalized) {
    return null;
  }
  if (
    normalized === 'text-units' ||
    normalized === 'textunits' ||
    normalized === 'selected-text-units'
  ) {
    return 'TEXT_UNITS';
  }
  if (normalized === 'repository' || normalized === 'repositories' || normalized === 'repo') {
    return 'REPOSITORIES';
  }
  if (
    normalized === 'review-feature' ||
    normalized === 'review-features' ||
    normalized === 'feature'
  ) {
    return 'REVIEW_FEATURE';
  }
  return null;
}

function toReviewProjectSourceModeParam(mode: ReviewProjectSourceMode): string {
  switch (mode) {
    case 'REPOSITORIES':
      return 'repositories';
    case 'REVIEW_FEATURE':
      return 'review-feature';
    case 'TEXT_UNITS':
    default:
      return 'text-units';
  }
}

function parseNumberQueryParams(params: URLSearchParams, names: string[]): number[] {
  const seen = new Set<number>();
  const parsed: number[] = [];
  names
    .flatMap((name) => params.getAll(name))
    .flatMap((value) => value.split(','))
    .map((value) => Number(value.trim()))
    .forEach((value) => {
      if (!Number.isSafeInteger(value) || value <= 0 || seen.has(value)) {
        return;
      }
      seen.add(value);
      parsed.push(value);
    });
  return parsed;
}

function getRepositoryIdsFromSearch(search: string): number[] {
  return parseNumberQueryParams(new URLSearchParams(search), REPOSITORY_ID_QUERY_PARAMS);
}

function normalizeIds(values: number[] | null | undefined) {
  return Array.from(
    new Set((values ?? []).filter((value) => Number.isSafeInteger(value) && value > 0)),
  ).sort((left, right) => left - right);
}

function getInitialSourceMode(search: string, navState: ReviewProjectNavState | null) {
  const params = new URLSearchParams(search);
  if (params.get('scope') === 'incidents') return 'REPOSITORIES';
  const querySourceMode = parseReviewProjectSourceMode(params.get('scope'));
  if (querySourceMode) {
    return querySourceMode;
  }
  if (getRepositoryIdsFromSearch(search).length > 0) {
    return 'REPOSITORIES';
  }
  const stateSourceMode = parseReviewProjectSourceMode(navState?.sourceMode);
  if (stateSourceMode) {
    return stateSourceMode;
  }
  if (navState?.repositoryIds?.length && !navState.tmTextUnitIds?.length) {
    return 'REPOSITORIES';
  }
  return 'TEXT_UNITS';
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
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const [urlSearchParams, setUrlSearchParams] = useSearchParams();
  const navState = isReviewProjectNavState(location.state) ? location.state : null;
  const repositoriesQuery = useRepositories();
  const repositories = repositoriesQuery.data ?? [];
  const { collections, activeCollection } = useWorkbenchCollections();
  const [tmIds, setTmIds] = useState<number[]>(() => normalizeIds(navState?.tmTextUnitIds));
  const [selectedCollectionId, setSelectedCollectionId] = useState<string | null>(null);
  const [sourceMode, setSourceMode] = useState<ReviewProjectSourceMode>(() =>
    getInitialSourceMode(location.search, navState),
  );
  const [reviewSource, setReviewSource] = useState<ReviewSource>(() =>
    urlSearchParams.get('source') === 'incidents' || urlSearchParams.get('scope') === 'incidents'
      ? 'INCIDENTS'
      : 'CURRENT_TRANSLATIONS',
  );
  const [selectedRepositoryIds, setSelectedRepositoryIds] = useState<number[]>(() => {
    const queryRepositoryIds = getRepositoryIdsFromSearch(location.search);
    if (queryRepositoryIds.length) {
      return queryRepositoryIds;
    }
    return getInitialSourceMode(location.search, navState) === 'REPOSITORIES'
      ? normalizeIds(navState?.repositoryIds)
      : [];
  });
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
  const [incidentCreationReport, setIncidentCreationReport] =
    useState<IncidentReviewProjectResult | null>(null);
  const [lastIncidentRequest, setLastIncidentRequest] =
    useState<IncidentReviewProjectRequest | null>(null);
  const [incidentPreviewRevision, setIncidentPreviewRevision] = useState(0);

  const createReviewProject = useCreateReviewProject();
  const createIncidentProjects = useMutation({ mutationFn: createIncidentReviewProjects });
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
  const repositoryOptions = useRepositorySelectionOptions(repositories);
  const hasCollections = collectionOptions.length > 0;
  const hasSelectedTextUnits = tmIds.length > 0;
  const hasTextUnitSource = hasCollections || hasSelectedTextUnits;
  const hasRepositories = repositoryOptions.length > 0;
  const hasReviewFeatures = reviewFeatureOptions.length > 0;
  const showCreateForm =
    hasTextUnitSource ||
    hasRepositories ||
    hasReviewFeatures ||
    repositoriesQuery.isLoading ||
    reviewFeatureOptionsQuery.isLoading;

  useEffect(() => {
    const state = navState;
    if (!state) {
      return;
    }
    if (state.tmTextUnitIds?.length) {
      const unique = Array.from(new Set(state.tmTextUnitIds));
      setTmIds(unique);
    }
    if (state.repositoryIds?.length) {
      setSelectedRepositoryIds(normalizeIds(state.repositoryIds));
    }
    const stateSourceMode = parseReviewProjectSourceMode(state.sourceMode);
    if (stateSourceMode) {
      setSourceMode(stateSourceMode);
    } else if (state.repositoryIds?.length && !state.tmTextUnitIds?.length) {
      setSourceMode('REPOSITORIES');
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
    if (sourceMode === 'REPOSITORIES' && repositoriesQuery.isLoading) {
      return;
    }
    if (sourceMode === 'REVIEW_FEATURE' && reviewFeatureOptionsQuery.isLoading) {
      return;
    }

    const currentModeIsAvailable =
      (sourceMode === 'TEXT_UNITS' && hasTextUnitSource && reviewSource !== 'INCIDENTS') ||
      (sourceMode === 'REPOSITORIES' && hasRepositories) ||
      (sourceMode === 'REVIEW_FEATURE' && hasReviewFeatures);
    if (currentModeIsAvailable) {
      return;
    }

    if (hasTextUnitSource && reviewSource !== 'INCIDENTS') {
      setSourceMode('TEXT_UNITS');
      return;
    }
    if (hasRepositories) {
      setSourceMode('REPOSITORIES');
      return;
    }
    if (hasReviewFeatures) {
      setSourceMode('REVIEW_FEATURE');
    }
  }, [
    hasRepositories,
    hasTextUnitSource,
    hasReviewFeatures,
    repositoriesQuery.isLoading,
    reviewFeatureOptionsQuery.isLoading,
    sourceMode,
    reviewSource,
  ]);

  useEffect(() => {
    if (repositoryOptions.length === 0) {
      return;
    }
    const availableIds = new Set(repositoryOptions.map((option) => option.id));
    setSelectedRepositoryIds((current) =>
      current.filter((repositoryId) => availableIds.has(repositoryId)),
    );
  }, [repositoryOptions]);

  useEffect(() => {
    const nextParams = new URLSearchParams(urlSearchParams);
    if (reviewSource === 'INCIDENTS') nextParams.set('source', 'incidents');
    else nextParams.delete('source');
    if (sourceMode === 'TEXT_UNITS') {
      nextParams.delete('scope');
    } else {
      nextParams.set('scope', toReviewProjectSourceModeParam(sourceMode));
    }
    REPOSITORY_ID_QUERY_PARAMS.forEach((param) => nextParams.delete(param));
    if (sourceMode === 'REPOSITORIES') {
      selectedRepositoryIds.forEach((repositoryId) => {
        nextParams.append('repositoryIds', String(repositoryId));
      });
    }

    if (nextParams.toString() !== urlSearchParams.toString()) {
      setUrlSearchParams(nextParams, { replace: true });
    }
  }, [selectedRepositoryIds, setUrlSearchParams, sourceMode, reviewSource, urlSearchParams]);

  useEffect(() => {
    if (statusFilterWasCustomized) {
      return;
    }
    setSelectedStatusFilter(sourceMode === 'TEXT_UNITS' ? 'ALL' : 'REVIEW_NEEDED');
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

  const toIncidentRequest = useCallback(
    (values: ReviewProjectCreateFormValues): IncidentReviewProjectRequest => {
      if (values.teamId == null) throw new Error('Select a team for incident review.');
      return {
        allRepositories: values.allRepositories,
        repositoryIds: values.repositoryIds,
        reviewFeatureIds: values.reviewFeatureIds,
        localeTags: values.localeTags,
        reviewType: values.incidentReviewType,
        teamId: values.teamId,
        name: values.name,
        dueDate: values.dueDate,
        maxWordCountPerProject: values.maxWordCountPerProject,
        assignTranslator: values.assignTranslator,
        type: values.type,
        notes: values.notes,
        screenshotImageIds: values.screenshotImageIds,
      };
    },
    [],
  );

  const handlePreviewIncidents = useCallback(
    (values: ReviewProjectCreateFormValues) =>
      previewIncidentReviewProjects(toIncidentRequest(values)),
    [toIncidentRequest],
  );

  const handleSubmit = useCallback(
    (values: ReviewProjectCreateFormValues) => {
      if (createReviewProject.isPending || createIncidentProjects.isPending) return;
      const needsExplicitScope = values.reviewSource !== 'INCIDENTS' || !values.allRepositories;
      if (needsExplicitScope && sourceMode === 'TEXT_UNITS' && !tmIds.length) {
        setErrorMessage('Add at least one text unit id.');
        setSubmissionReport(null);
        return;
      }
      if (
        needsExplicitScope &&
        sourceMode === 'REVIEW_FEATURE' &&
        !values.reviewFeatureIds?.length
      ) {
        setErrorMessage('Select at least one review feature.');
        setSubmissionReport(null);
        return;
      }
      if (needsExplicitScope && sourceMode === 'REPOSITORIES' && !values.repositoryIds?.length) {
        setErrorMessage('Select at least one repository.');
        setSubmissionReport(null);
        return;
      }
      setErrorMessage(null);
      setSubmissionReport(null);
      setIncidentCreationReport(null);
      void (async () => {
        try {
          if (values.reviewSource === 'INCIDENTS') {
            const request = toIncidentRequest(values);
            setLastIncidentRequest(request);
            const response = await createIncidentProjects.mutateAsync(request);
            setIncidentCreationReport(response);
            setIncidentPreviewRevision((revision) => revision + 1);
            void queryClient.invalidateQueries({ queryKey: ['review-projects'] });
            return;
          }
          if (sourceMode === 'TEXT_UNITS') {
            const response = await createReviewProject.mutateAsync({
              localeTags: values.localeTags,
              notes: values.notes,
              type: values.type,
              dueDate: values.dueDate,
              tmTextUnitIds: tmIds,
              reviewFeatureId: null,
              repositoryIds: null,
              statusFilter: values.statusFilter,
              skipTextUnitsInOpenProjects: values.skipTextUnitsInOpenProjects,
              maxWordCountPerProject: values.maxWordCountPerProject,
              screenshotImageIds: values.screenshotImageIds,
              name: values.name,
              teamId: values.teamId ?? null,
              assignTranslator: values.assignTranslator,
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

          if (sourceMode === 'REPOSITORIES') {
            const response = await createReviewProject.mutateAsync({
              localeTags: values.localeTags,
              notes: values.notes,
              type: values.type,
              dueDate: values.dueDate,
              tmTextUnitIds: null,
              reviewFeatureId: null,
              repositoryIds: values.repositoryIds ?? [],
              statusFilter: values.statusFilter,
              skipTextUnitsInOpenProjects: values.skipTextUnitsInOpenProjects,
              maxWordCountPerProject: values.maxWordCountPerProject,
              screenshotImageIds: values.screenshotImageIds,
              name: values.name,
              teamId: values.teamId ?? null,
              assignTranslator: values.assignTranslator,
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
              repositoryIds: null,
              statusFilter: values.statusFilter,
              skipTextUnitsInOpenProjects: values.skipTextUnitsInOpenProjects,
              maxWordCountPerProject: values.maxWordCountPerProject,
              screenshotImageIds: values.screenshotImageIds,
              name: buildFeatureScopedRequestName(values.name, feature?.name ?? ''),
              teamId: values.teamId ?? null,
              assignTranslator: values.assignTranslator,
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
    [
      createReviewProject,
      createIncidentProjects,
      toIncidentRequest,
      queryClient,
      navigate,
      reviewFeaturesById,
      sourceMode,
      tmIds,
    ],
  );

  const handleContinueIncidents = async () => {
    if (!lastIncidentRequest || createIncidentProjects.isPending) return;
    setErrorMessage(null);
    try {
      const next = await createIncidentProjects.mutateAsync(lastIncidentRequest);
      setIncidentCreationReport((previous) =>
        previous
          ? {
              ...next,
              eligibleIncidentCount: previous.eligibleIncidentCount + next.eligibleIncidentCount,
              skippedIncidentCount: previous.skippedIncidentCount + next.skippedIncidentCount,
              projectCount: previous.projectCount + next.projectCount,
              scannedIncidentCount:
                (previous.scannedIncidentCount ?? 0) + (next.scannedIncidentCount ?? 0),
              localeTags: [...new Set([...previous.localeTags, ...next.localeTags])],
              projectIds: [...previous.projectIds, ...next.projectIds].slice(-100),
              requestIds: [...previous.requestIds, ...next.requestIds].slice(-100),
              skipped: [...previous.skipped, ...next.skipped].slice(-100),
            }
          : next,
      );
      setIncidentPreviewRevision((revision) => revision + 1);
      void queryClient.invalidateQueries({ queryKey: ['review-projects'] });
    } catch (error) {
      setErrorMessage(
        getCreateReviewProjectErrorMessage(error) || 'Unable to create the next incident batch.',
      );
    }
  };

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
              reviewSource={reviewSource}
              onChangeReviewSource={(source) => {
                setReviewSource(source);
                setErrorMessage(null);
                setSubmissionReport(null);
                setIncidentCreationReport(null);
                if (source === 'INCIDENTS' && sourceMode === 'TEXT_UNITS') {
                  setSourceMode(hasRepositories ? 'REPOSITORIES' : 'REVIEW_FEATURE');
                }
              }}
              onPreviewIncidents={handlePreviewIncidents}
              incidentPreviewRevision={incidentPreviewRevision}
              defaultName={prefillName || 'Review project'}
              defaultDueDate={prefillDueDate ?? defaultDueDate}
              localeOptions={localeOptions}
              tmTextUnitIds={tmIds}
              sourceMode={sourceMode}
              onChangeSourceMode={setSourceMode}
              collectionName={prefillCollectionName ?? null}
              collectionOptions={collectionOptions.length ? collectionOptions : undefined}
              selectedCollectionId={selectedCollectionId}
              onChangeCollection={(id) => {
                setSelectedCollectionId(id);
                if (!id) {
                  setPrefillCollectionName(null);
                }
              }}
              repositoryOptions={repositoryOptions}
              selectedRepositoryIds={selectedRepositoryIds}
              onChangeRepositories={setSelectedRepositoryIds}
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
              isSubmitting={createReviewProject.isPending || createIncidentProjects.isPending}
              errorMessage={errorMessage}
              submitLabel="Create"
              onSubmit={handleSubmit}
              onCancel={() => {
                void navigate(-1);
              }}
            />
            {reviewSource === 'INCIDENTS' && incidentCreationReport ? (
              <div className="review-create__report" role="status">
                <div className="review-create__report-title">Incident review projects</div>
                <p>
                  Created {incidentCreationReport.projectCount} project
                  {incidentCreationReport.projectCount === 1 ? '' : 's'}.{' '}
                  {incidentCreationReport.skippedIncidentCount} incident
                  {incidentCreationReport.skippedIncidentCount === 1 ? '' : 's'} skipped.
                </p>
                {incidentCreationReport.projectCount > incidentCreationReport.projectIds.length ? (
                  <p>Showing the most recent 100 projects.</p>
                ) : null}
                {incidentCreationReport.projectIds.length ? (
                  <ul>
                    {incidentCreationReport.projectIds.map((id) => (
                      <li key={id}>
                        <Link to={`/review-projects/${id}`}>Open review project #{id}</Link>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p>
                    {incidentCreationReport.hasMore
                      ? 'No projects were created from the incidents checked so far. More incidents remain to check.'
                      : 'No eligible incidents remain in this pass through the selection.'}
                  </p>
                )}
                {incidentCreationReport.hasMore ? (
                  <div>
                    <p>
                      More incidents remain to check for this selection. Progress is saved between
                      batches.
                    </p>
                    <button
                      type="button"
                      className="review-create__cta"
                      disabled={createIncidentProjects.isPending}
                      onClick={() => void handleContinueIncidents()}
                    >
                      {createIncidentProjects.isPending ? 'Creating…' : 'Create next batch'}
                    </button>
                  </div>
                ) : null}
                {incidentCreationReport.skipped.length ? (
                  <details>
                    <summary>Skipped incidents</summary>
                    {incidentCreationReport.skippedIncidentCount >
                    incidentCreationReport.skipped.length ? (
                      <p>Showing the most recent 100 skipped incidents.</p>
                    ) : null}
                    <ul>
                      {incidentCreationReport.skipped.map((item) => (
                        <li key={item.incidentId}>
                          Incident #{item.incidentId}: {item.reason}
                        </li>
                      ))}
                    </ul>
                  </details>
                ) : null}
              </div>
            ) : null}
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
            <div className="review-create__empty-title">
              No collections, repositories, or review features yet
            </div>
            <div className="review-create__empty-body">
              Review projects can be created from Workbench collections, repositories, or review
              features.
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
