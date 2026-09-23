import './review-projects-page.css';

import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';

import {
  createIncidentReviewProjects,
  type IncidentReviewProjectRequest,
  type IncidentReviewProjectResult,
  type IncidentReviewTask,
  type IncidentReviewTaskMode,
  previewIncidentReviewProjects,
  type ReviewSource,
  waitForIncidentReviewTask,
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
import { IncidentSkipSummary } from './IncidentSkipSummary';
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
  const [incidentPreview, setIncidentPreview] = useState<IncidentReviewProjectResult | null>(null);
  const [incidentTaskStatus, setIncidentTaskStatus] = useState<IncidentReviewTask | null>(null);
  const [completedIncidentTaskId, setCompletedIncidentTaskId] = useState<number | null>(null);
  const [incidentPollingError, setIncidentPollingError] = useState<string | null>(null);
  const [incidentPollRevision, setIncidentPollRevision] = useState(0);
  const [startingIncidentMode, setStartingIncidentMode] = useState<IncidentReviewTaskMode | null>(
    null,
  );
  const incidentStarting = useRef(false);
  const isMounted = useRef(true);
  useEffect(() => {
    isMounted.current = true;
    return () => {
      isMounted.current = false;
    };
  }, []);
  const incidentTaskId = Number(urlSearchParams.get('incidentTask'));
  const incidentTaskMode = urlSearchParams.get('incidentTaskMode');
  const hasIncidentTask =
    Number.isSafeInteger(incidentTaskId) &&
    incidentTaskId > 0 &&
    (incidentTaskMode === 'preview' || incidentTaskMode === 'create');
  const incidentTaskRunning = hasIncidentTask && completedIncidentTaskId !== incidentTaskId;
  const isWorkingOnIncidents = startingIncidentMode !== null || incidentTaskRunning;
  const resumedIncidentTask = useRef(hasIncidentTask);

  useEffect(() => {
    if (!hasIncidentTask) return;
    const controller = new AbortController();
    setIncidentPollingError(null);
    setIncidentTaskStatus(null);
    setCompletedIncidentTaskId(null);
    setIncidentPreview(null);
    setIncidentCreationReport(null);
    setErrorMessage(null);
    void waitForIncidentReviewTask(
      incidentTaskId,
      (task) => {
        if (!controller.signal.aborted) setIncidentTaskStatus(task);
      },
      controller.signal,
    )
      .then(({ task, result, outputError }) => {
        if (controller.signal.aborted) return;
        setIncidentTaskStatus(task);
        setCompletedIncidentTaskId(task.id);
        setErrorMessage(task.errorMessage || null);
        setIncidentPollingError(outputError || null);
        if (incidentTaskMode === 'preview') setIncidentPreview(result);
        else {
          setIncidentCreationReport(result);
          void queryClient.invalidateQueries({ queryKey: ['review-projects'] });
        }
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setIncidentPollingError(
            getCreateReviewProjectErrorMessage(error) || 'Unable to check incident task progress.',
          );
        }
      });
    // Leaving the page stops polling; the accepted job continues on the server.
    return () => controller.abort();
  }, [hasIncidentTask, incidentTaskId, incidentTaskMode, incidentPollRevision, queryClient]);

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
  const repositoryOptions = useRepositorySelectionOptions(repositories);
  const hasCollections = collectionOptions.length > 0;
  const hasSelectedTextUnits = tmIds.length > 0;
  const hasTextUnitSource = hasCollections || hasSelectedTextUnits;
  const hasRepositories = repositoryOptions.length > 0;
  const hasReviewFeatures = reviewFeatureOptions.length > 0;
  const showCreateForm =
    hasIncidentTask ||
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
        maxIncidentCount: values.maxIncidentCount,
        assignTranslator: values.assignTranslator,
        type: values.type,
        notes: values.notes,
        screenshotImageIds: values.screenshotImageIds,
      };
    },
    [],
  );

  const startIncidentTask = useCallback(
    async (mode: IncidentReviewTaskMode, values: ReviewProjectCreateFormValues) => {
      if (incidentStarting.current || incidentTaskRunning) return;
      incidentStarting.current = true;
      setStartingIncidentMode(mode);
      setErrorMessage(null);
      setIncidentPollingError(null);
      setIncidentTaskStatus(null);
      setIncidentPreview(null);
      setIncidentCreationReport(null);
      try {
        const request = toIncidentRequest(values);
        const { pollableTaskId } = await (mode === 'preview'
          ? previewIncidentReviewProjects(request)
          : createIncidentReviewProjects(request));
        if (!isMounted.current) return;
        resumedIncidentTask.current = false;
        setUrlSearchParams(
          (current) => {
            const next = new URLSearchParams(current);
            next.set('source', 'incidents');
            next.set('incidentTask', String(pollableTaskId));
            next.set('incidentTaskMode', mode);
            return next;
          },
          { replace: true },
        );
      } catch (error) {
        if (isMounted.current) {
          setErrorMessage(
            getCreateReviewProjectErrorMessage(error) || 'Unable to start incident review.',
          );
        }
      } finally {
        incidentStarting.current = false;
        if (isMounted.current) setStartingIncidentMode(null);
      }
    },
    [incidentTaskRunning, setUrlSearchParams, toIncidentRequest],
  );

  const handlePreviewIncidents = useCallback(
    (values: ReviewProjectCreateFormValues) => {
      void startIncidentTask('preview', values);
    },
    [startIncidentTask],
  );

  const handleIncidentSettingsChange = useCallback(() => {
    setIncidentPreview(null);
  }, []);

  const handleSubmit = useCallback(
    (values: ReviewProjectCreateFormValues) => {
      if (createReviewProject.isPending || incidentStarting.current || incidentTaskRunning) return;
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
      if (values.reviewSource === 'INCIDENTS') {
        void startIncidentTask('create', values);
        return;
      }
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
      startIncidentTask,
      incidentTaskRunning,
      navigate,
      reviewFeaturesById,
      sourceMode,
      tmIds,
    ],
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
              onIncidentSettingsChange={handleIncidentSettingsChange}
              incidentPreview={incidentPreview}
              isPreviewing={
                startingIncidentMode === 'preview' ||
                (incidentTaskRunning && incidentTaskMode === 'preview')
              }
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
              isSubmitting={createReviewProject.isPending || isWorkingOnIncidents}
              errorMessage={errorMessage}
              submitLabel="Create"
              cancelLabel={isWorkingOnIncidents ? 'Leave page' : 'Cancel'}
              onSubmit={handleSubmit}
              onCancel={() => {
                void navigate(-1);
              }}
            />
            {reviewSource === 'INCIDENTS' && (hasIncidentTask || startingIncidentMode) ? (
              <div className="review-create__report review-create__incident-report" role="status">
                <div className="review-create__incident-report-header">
                  <div className="review-create__report-title">
                    {isWorkingOnIncidents && !incidentPollingError ? (
                      <span className="spinner" aria-hidden="true" />
                    ) : null}
                    {(startingIncidentMode ?? incidentTaskMode) === 'preview'
                      ? 'Incident preview progress'
                      : 'Incident review projects'}
                  </div>
                  {hasIncidentTask ? (
                    <span className="review-create__report-summary">Task #{incidentTaskId}</span>
                  ) : null}
                </div>
                <p>
                  {incidentTaskStatus?.message ||
                    (isWorkingOnIncidents
                      ? 'Preparing incident review…'
                      : 'Incident review finished.')}
                </p>
                {hasIncidentTask && !startingIncidentMode && incidentTaskRunning ? (
                  <p className="review-create__report-summary">
                    You can leave this page and return to check progress.
                  </p>
                ) : null}
                {resumedIncidentTask.current ? (
                  <p className="review-create__report-summary">
                    Reconnected to the saved task. Form settings are not restored; choose your scope
                    before starting another task.
                  </p>
                ) : null}
                {incidentPollingError ? (
                  <div>
                    <p className="review-create__error" role="alert">
                      {incidentPollingError}
                    </p>
                    <p className="review-create__report-summary">
                      {incidentTaskStatus?.isAllFinished
                        ? 'The task finished, but its saved result could not be loaded. Reconnect to check any created projects.'
                        : 'The job may still be running. Reconnect to check its status before starting another.'}
                    </p>
                    <button
                      type="button"
                      className="review-create__ghost"
                      onClick={() => setIncidentPollRevision((value) => value + 1)}
                    >
                      Reconnect
                    </button>
                  </div>
                ) : null}
                {incidentCreationReport ? (
                  <>
                    <p>
                      Created {incidentCreationReport.projectCount} project
                      {incidentCreationReport.projectCount === 1 ? '' : 's'} with{' '}
                      {incidentCreationReport.eligibleIncidentCount} incident
                      {incidentCreationReport.eligibleIncidentCount === 1 ? '' : 's'}.
                    </p>
                    {errorMessage ? <p>Projects already created are preserved.</p> : null}
                    {incidentCreationReport.projectIds.length > 100 ? (
                      <p>Showing the most recent 100 projects.</p>
                    ) : null}
                    {incidentCreationReport.projectIds.length ? (
                      <ul>
                        {incidentCreationReport.projectIds.slice(-100).map((id) => (
                          <li key={id}>
                            <Link to={`/review-projects/${id}`}>Open review project #{id}</Link>
                          </li>
                        ))}
                      </ul>
                    ) : null}
                    <IncidentSkipSummary
                      skipped={incidentCreationReport.skipped}
                      skippedIncidentCount={incidentCreationReport.skippedIncidentCount}
                    />
                  </>
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
