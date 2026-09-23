import './review-projects-page.css';

import { useEffect, useMemo, useRef, useState } from 'react';

import type { IncidentReviewProjectResult, ReviewSource } from '../../api/incident-review-projects';
import type { ApiReviewFeatureOption } from '../../api/review-features';
import {
  type ApiReviewProjectType,
  REVIEW_PROJECT_CREATE_STATUS_FILTER_LABELS,
  REVIEW_PROJECT_CREATE_STATUS_FILTERS,
  REVIEW_PROJECT_TYPE_LABELS,
  REVIEW_PROJECT_TYPES,
  type ReviewProjectCreateStatusFilter,
} from '../../api/review-projects';
import { type CollectionOption, CollectionSelect } from '../../components/CollectionSelect';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import {
  RepositoryMultiSelect,
  type RepositoryMultiSelectOption,
} from '../../components/RepositoryMultiSelect';
import {
  RequestAttachmentsDropzone,
  type RequestAttachmentUploadQueueItem,
} from '../../components/review-request/RequestAttachmentsDropzone';
import { RequestDescriptionEditor } from '../../components/review-request/RequestDescriptionEditor';
import { ReviewFeatureMultiSelect } from '../../components/ReviewFeatureMultiSelect';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import { localDateTimeInputToIso } from '../../utils/dateTime';
import { prepareDbBackedUploadFile } from '../../utils/image-upload-optimizer';
import type { LocaleSelectionOption } from '../../utils/localeSelection';
import {
  buildRequestAttachmentUploadQueueEntries,
  canUploadRequestAttachmentFile,
  revokeRequestAttachmentUploadQueuePreviews,
  toDescriptionAttachmentMarkdown,
  uploadRequestAttachmentFile,
} from '../../utils/request-attachments';
import { IncidentSkipSummary } from './IncidentSkipSummary';

export type ReviewProjectCreateFormValues = {
  reviewSource: ReviewSource;
  incidentReviewType: string | null;
  allRepositories: boolean;
  name: string;
  dueDate: string;
  type: ApiReviewProjectType;
  localeTags: string[];
  notes: string | null;
  tmTextUnitIds?: number[] | null;
  reviewFeatureIds?: number[] | null;
  repositoryIds?: number[] | null;
  statusFilter: ReviewProjectCreateStatusFilter;
  skipTextUnitsInOpenProjects: boolean;
  maxWordCountPerProject: number | null;
  maxIncidentCount?: number | null;
  maxIncidentsPerProject?: number | null;
  screenshotImageIds: string[];
  teamId: number | null;
  assignTranslator: boolean;
};

export type ReviewProjectSourceMode = 'TEXT_UNITS' | 'REPOSITORIES' | 'REVIEW_FEATURE';

type Props = {
  reviewSource?: ReviewSource;
  onChangeReviewSource?: (source: ReviewSource) => void;
  onPreviewIncidents?: (payload: ReviewProjectCreateFormValues) => void;
  incidentPreview?: IncidentReviewProjectResult | null;
  isPreviewing?: boolean;
  onIncidentSettingsChange?: () => void;
  defaultName: string;
  defaultDueDate: string;
  localeOptions: LocaleSelectionOption[];
  tmTextUnitIds: number[];
  sourceMode: ReviewProjectSourceMode;
  onChangeSourceMode: (mode: ReviewProjectSourceMode) => void;
  collectionName?: string | null;
  collectionOptions?: CollectionOption[];
  selectedCollectionId?: string | null;
  onChangeCollection?: (id: string | null) => void;
  repositoryOptions?: RepositoryMultiSelectOption[];
  selectedRepositoryIds?: number[];
  onChangeRepositories?: (ids: number[]) => void;
  reviewFeatureOptions?: ApiReviewFeatureOption[];
  selectedReviewFeatureIds?: number[];
  onChangeReviewFeatures?: (ids: number[]) => void;
  teamOptions?: Array<{ id: number; name: string }>;
  selectedTeamId?: number | null;
  onChangeTeam?: (id: number | null) => void;
  selectedStatusFilter: ReviewProjectCreateStatusFilter;
  onChangeStatusFilter: (value: ReviewProjectCreateStatusFilter) => void;
  isSubmitting?: boolean;
  errorMessage?: string | null;
  submitLabel?: string;
  cancelLabel?: string;
  onSubmit: (payload: ReviewProjectCreateFormValues) => void;
  onCancel?: () => void;
};

export function ReviewProjectCreateForm({
  reviewSource = 'CURRENT_TRANSLATIONS',
  onChangeReviewSource,
  onPreviewIncidents,
  incidentPreview = null,
  isPreviewing = false,
  onIncidentSettingsChange,
  defaultName,
  defaultDueDate,
  localeOptions,
  tmTextUnitIds,
  sourceMode,
  onChangeSourceMode,
  collectionName,
  collectionOptions,
  selectedCollectionId,
  onChangeCollection,
  repositoryOptions = [],
  selectedRepositoryIds = [],
  onChangeRepositories,
  reviewFeatureOptions,
  selectedReviewFeatureIds = [],
  onChangeReviewFeatures,
  teamOptions,
  selectedTeamId = null,
  onChangeTeam,
  selectedStatusFilter,
  onChangeStatusFilter,
  isSubmitting: externalIsSubmitting = false,
  errorMessage,
  submitLabel = 'Create',
  cancelLabel = 'Cancel',
  onSubmit,
  onCancel,
}: Props) {
  const [incidentReviewType, setIncidentReviewType] = useState('');
  const [allIncidentRepositories, setAllIncidentRepositories] = useState(true);
  const isIncidentReview = reviewSource === 'INCIDENTS';
  const isAllIncidents = isIncidentReview && allIncidentRepositories;
  const isSubmitting = externalIsSubmitting || isPreviewing;
  const [name, setName] = useState(defaultName);
  const [dueDate, setDueDate] = useState(defaultDueDate);
  const [type, setType] = useState<ApiReviewProjectType>('NORMAL');
  const [selectedLocaleTags, setSelectedLocaleTags] = useState<string[]>([]);
  const [skipTextUnitsInOpenProjects, setSkipTextUnitsInOpenProjects] = useState(true);
  const [maxWordCountDraft, setMaxWordCountDraft] = useState('');
  const [maxIncidentCountDraft, setMaxIncidentCountDraft] = useState('');
  const [maxIncidentsPerProjectDraft, setMaxIncidentsPerProjectDraft] = useState('500');
  const [notes, setNotes] = useState('');
  const [screenshotKeys, setScreenshotKeys] = useState<string[]>([]);
  const [assignTranslator, setAssignTranslator] = useState(true);
  const [uploadQueue, setUploadQueue] = useState<RequestAttachmentUploadQueueItem[]>([]);
  const [optimizeImagesBeforeUpload, setOptimizeImagesBeforeUpload] = useState(true);
  const previewUrlsRef = useRef<Set<string>>(new Set());

  useEffect(() => setName(defaultName), [defaultName]);
  useEffect(() => setDueDate(defaultDueDate), [defaultDueDate]);
  useEffect(() => {
    setSelectedLocaleTags((current) => {
      const allowed = new Set(localeOptions.map((opt) => opt.tag));
      return current.filter((tag) => allowed.has(tag));
    });
  }, [localeOptions]);

  useEffect(() => {
    const nextUrls = new Set(
      uploadQueue
        .map((item) => item.preview)
        .filter((preview): preview is string => typeof preview === 'string' && preview.length > 0),
    );
    revokeRequestAttachmentUploadQueuePreviews(
      Array.from(previewUrlsRef.current)
        .filter((url) => !nextUrls.has(url))
        .map((preview) => ({ preview })),
    );
    previewUrlsRef.current = nextUrls;
  }, [uploadQueue]);

  useEffect(() => {
    return () => {
      revokeRequestAttachmentUploadQueuePreviews(
        Array.from(previewUrlsRef.current).map((preview) => ({ preview })),
      );
      previewUrlsRef.current.clear();
    };
  }, []);

  const maxWordCountPerProject = maxWordCountDraft.trim() ? Number(maxWordCountDraft) : null;
  const maxWordCountValid =
    maxWordCountPerProject === null ||
    (/^\d+$/.test(maxWordCountDraft.trim()) &&
      Number.isInteger(maxWordCountPerProject) &&
      maxWordCountPerProject >= 1 &&
      maxWordCountPerProject <= (isIncidentReview ? 100000 : 2147483647));
  const maxIncidentCount = maxIncidentCountDraft.trim() ? Number(maxIncidentCountDraft) : null;
  const maxIncidentCountValid =
    maxIncidentCount === null ||
    (/^\d+$/.test(maxIncidentCountDraft.trim()) &&
      Number.isSafeInteger(maxIncidentCount) &&
      maxIncidentCount >= 1 &&
      maxIncidentCount <= 2147483647);
  const maxIncidentsPerProject = Number(maxIncidentsPerProjectDraft);
  const maxIncidentsPerProjectValid =
    /^\d+$/.test(maxIncidentsPerProjectDraft.trim()) &&
    Number.isInteger(maxIncidentsPerProject) &&
    maxIncidentsPerProject >= 1 &&
    maxIncidentsPerProject <= 5000;

  const canSubmit = useMemo(
    () =>
      Boolean(name.trim()) &&
      Boolean(dueDate) &&
      (isAllIncidents ||
        (sourceMode === 'TEXT_UNITS'
          ? tmTextUnitIds.length > 0
          : sourceMode === 'REPOSITORIES'
            ? selectedRepositoryIds.length > 0
            : selectedReviewFeatureIds.length > 0)) &&
      selectedLocaleTags.length > 0 &&
      (!isIncidentReview ||
        (selectedTeamId != null && (isAllIncidents || sourceMode !== 'TEXT_UNITS'))) &&
      maxWordCountValid &&
      (!isIncidentReview || (maxIncidentCountValid && maxIncidentsPerProjectValid)) &&
      uploadQueue.every((item) => item.status !== 'uploading'),
    [
      dueDate,
      name,
      maxWordCountValid,
      maxIncidentCountValid,
      maxIncidentsPerProjectValid,
      selectedLocaleTags.length,
      selectedReviewFeatureIds.length,
      selectedRepositoryIds.length,
      isIncidentReview,
      isAllIncidents,
      selectedTeamId,
      sourceMode,
      tmTextUnitIds.length,
      uploadQueue,
    ],
  );

  const payload: ReviewProjectCreateFormValues = {
    reviewSource,
    allRepositories: isAllIncidents,
    incidentReviewType: incidentReviewType.trim() || null,
    name: name.trim(),
    dueDate: localDateTimeInputToIso(dueDate) ?? '',
    type,
    localeTags: selectedLocaleTags,
    notes: notes.trim().length > 0 ? notes : null,
    tmTextUnitIds: !isIncidentReview && sourceMode === 'TEXT_UNITS' ? tmTextUnitIds : null,
    repositoryIds: !isAllIncidents && sourceMode === 'REPOSITORIES' ? selectedRepositoryIds : null,
    reviewFeatureIds:
      !isAllIncidents && sourceMode === 'REVIEW_FEATURE' ? selectedReviewFeatureIds : null,
    statusFilter: selectedStatusFilter,
    skipTextUnitsInOpenProjects,
    maxWordCountPerProject,
    maxIncidentCount: isIncidentReview ? maxIncidentCount : null,
    maxIncidentsPerProject: isIncidentReview ? maxIncidentsPerProject : null,
    screenshotImageIds: screenshotKeys,
    teamId: selectedTeamId,
    assignTranslator,
  };
  const settingsKey = JSON.stringify(payload);
  useEffect(() => {
    onIncidentSettingsChange?.();
  }, [settingsKey, onIncidentSettingsChange]);
  const currentPreview = incidentPreview;

  const addScreenshotKeys = (raw: string[]) => {
    const next = raw
      .map((item) => item.trim())
      .filter(Boolean)
      .map((item) => item.slice(0, 255));
    if (!next.length) return;
    setScreenshotKeys((current) => {
      const set = new Set(current.map((key) => key.toLowerCase()));
      const merged = [...current];
      next.forEach((key) => {
        if (!set.has(key.toLowerCase())) {
          merged.push(key);
          set.add(key.toLowerCase());
        }
      });
      return merged;
    });
  };

  const handleFiles = async (files: FileList | null): Promise<string[]> => {
    if (!files || files.length === 0) {
      return [];
    }
    const preparedFiles = await Promise.all(
      Array.from(files).map(async (file) =>
        prepareDbBackedUploadFile(file, { optimizeImages: optimizeImagesBeforeUpload }),
      ),
    );
    const queueEntries = buildRequestAttachmentUploadQueueEntries(
      preparedFiles.map((prepared) => ({
        file: prepared.file,
        displayName: prepared.displayName,
        warning: prepared.warning,
      })),
    );
    setUploadQueue((prev) => [...queueEntries, ...prev]);
    const uploadedKeys: string[] = [];

    await Promise.all(
      queueEntries.map(async (entry, index) => {
        const file = preparedFiles[index]?.file;
        if (!file) {
          return;
        }
        if (!canUploadRequestAttachmentFile(file)) {
          return;
        }
        try {
          const uploadedKey = await uploadRequestAttachmentFile(file);
          uploadedKeys.push(uploadedKey);
          setUploadQueue((prev) => prev.filter((item) => item.key !== entry.key));
          addScreenshotKeys([uploadedKey]);
        } catch (error) {
          const message =
            error instanceof Error ? error.message : 'Upload failed. Please try again.';
          setUploadQueue((prev) =>
            prev.map((item) =>
              item.key === entry.key ? { ...item, status: 'error', error: message } : item,
            ),
          );
        }
      }),
    );
    return uploadedKeys;
  };

  return (
    <div className="review-create__body">
      <div className="review-create__stack">
        <label className="review-create__field">
          <span className="review-create__label">Project name</span>
          <input
            className="review-create__input"
            type="text"
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={120}
            placeholder="e.g. Release 12.3 review"
            disabled={isSubmitting}
          />
        </label>

        {onChangeReviewSource ? (
          <div className="review-create__field">
            <span className="review-create__label">Review source</span>
            <div
              className="review-projects-page__mode-toggle"
              role="group"
              aria-label="Review source"
            >
              {(['CURRENT_TRANSLATIONS', 'INCIDENTS'] as const).map((source) => (
                <button
                  key={source}
                  type="button"
                  className={`review-projects-page__mode-button${reviewSource === source ? ' is-active' : ''}`}
                  aria-pressed={reviewSource === source}
                  onClick={() => onChangeReviewSource(source)}
                  disabled={isSubmitting}
                >
                  {source === 'INCIDENTS' ? 'Incidents' : 'Current translations'}
                </button>
              ))}
            </div>
            {isIncidentReview ? (
              <span className="review-create__hint">
                Collect open incidents for one-at-a-time review. Current translations change only
                when a reviewer accepts a correction.
              </span>
            ) : null}
          </div>
        ) : null}

        <div className="review-create__field">
          <span className="review-create__label">
            {isIncidentReview ? 'Incident scope' : 'Scope'}
          </span>
          <div
            className="review-projects-page__mode-toggle"
            role="group"
            aria-label={isIncidentReview ? 'Incident scope' : 'Project scope'}
          >
            {isIncidentReview ? (
              <button
                type="button"
                className={`review-projects-page__mode-button${isAllIncidents ? ' is-active' : ''}`}
                aria-pressed={isAllIncidents}
                onClick={() => setAllIncidentRepositories(true)}
                disabled={isSubmitting}
              >
                All eligible incidents
              </button>
            ) : null}
            {!isIncidentReview ? (
              <button
                type="button"
                className={`review-projects-page__mode-button${
                  sourceMode === 'TEXT_UNITS' ? ' is-active' : ''
                }`}
                onClick={() => onChangeSourceMode('TEXT_UNITS')}
                disabled={isSubmitting || (!collectionOptions?.length && !tmTextUnitIds.length)}
              >
                Selected text units
              </button>
            ) : null}
            <button
              type="button"
              className={`review-projects-page__mode-button${
                !isAllIncidents && sourceMode === 'REPOSITORIES' ? ' is-active' : ''
              }`}
              onClick={() => {
                setAllIncidentRepositories(false);
                onChangeSourceMode('REPOSITORIES');
              }}
              disabled={isSubmitting || !repositoryOptions.length}
            >
              Repositories
            </button>
            <button
              type="button"
              className={`review-projects-page__mode-button${
                !isAllIncidents && sourceMode === 'REVIEW_FEATURE' ? ' is-active' : ''
              }`}
              onClick={() => {
                setAllIncidentRepositories(false);
                onChangeSourceMode('REVIEW_FEATURE');
              }}
              disabled={isSubmitting || !reviewFeatureOptions?.length}
            >
              Review feature
            </button>
          </div>
        </div>

        {!isAllIncidents && sourceMode === 'REPOSITORIES' && onChangeRepositories ? (
          <div className="review-create__field">
            <span className="review-create__label">Repositories</span>
            <RepositoryMultiSelect
              className="review-create__select-dropdown"
              options={repositoryOptions}
              selectedIds={selectedRepositoryIds}
              onChange={(next) => onChangeRepositories([...next].sort((a, b) => a - b))}
              disabled={isSubmitting}
              buttonAriaLabel="Select repositories"
              showSelectionPresets
            />
          </div>
        ) : null}

        {!isIncidentReview &&
        sourceMode === 'TEXT_UNITS' &&
        collectionOptions?.length &&
        onChangeCollection ? (
          <label className="review-create__field">
            <span className="review-create__label">Collection</span>
            <CollectionSelect
              options={collectionOptions}
              value={selectedCollectionId ?? null}
              onChange={onChangeCollection}
              disabled={isSubmitting}
              className="review-create__select"
            />
          </label>
        ) : null}

        {!isIncidentReview &&
        sourceMode === 'TEXT_UNITS' &&
        !collectionOptions?.length &&
        collectionName ? (
          <div className="review-create__field">
            <span className="review-create__label">Collection</span>
            <div className="review-create__pill">{collectionName}</div>
          </div>
        ) : null}

        {!isAllIncidents &&
        sourceMode === 'REVIEW_FEATURE' &&
        reviewFeatureOptions &&
        onChangeReviewFeatures ? (
          <div className="review-create__field">
            <span className="review-create__label">Review features</span>
            <ReviewFeatureMultiSelect
              label="Review features"
              className="review-create__select-dropdown"
              options={reviewFeatureOptions}
              selectedIds={selectedReviewFeatureIds}
              onChange={(next) => onChangeReviewFeatures([...next].sort((a, b) => a - b))}
              disabled={isSubmitting}
              buttonAriaLabel="Select review features"
              enabledOnlyByDefault
            />
          </div>
        ) : null}

        <div className="review-create__field">
          <span className="review-create__hint">
            {isAllIncidents
              ? 'Collects matching open incidents across repositories for the owning team. Incidents already assigned or reviewed in their current state are skipped.'
              : isIncidentReview
                ? `Collects open incidents from ${sourceMode === 'REPOSITORIES' ? `${selectedRepositoryIds.length} selected repositor${selectedRepositoryIds.length === 1 ? 'y' : 'ies'}` : `${selectedReviewFeatureIds.length} selected review feature${selectedReviewFeatureIds.length === 1 ? '' : 's'}`}. Incidents already assigned or reviewed in their current state are skipped.`
                : sourceMode === 'TEXT_UNITS'
                  ? `${tmTextUnitIds.length} selected text unit${tmTextUnitIds.length === 1 ? '' : 's'}`
                  : sourceMode === 'REPOSITORIES'
                    ? `${selectedRepositoryIds.length} selected repositor${selectedRepositoryIds.length === 1 ? 'y' : 'ies'}`
                    : `Creates one request per selected feature from review-needed strings (${selectedReviewFeatureIds.length} selected).`}
          </span>
        </div>

        {isIncidentReview ? (
          <label className="review-create__field">
            <span className="review-create__label">Incident review type (optional)</span>
            <SingleSelectDropdown
              label="Incident review type"
              className="review-create__select-dropdown"
              options={[{ value: 'TRANSLATION_QUALITY', label: 'Translation quality' }]}
              value={incidentReviewType || null}
              onChange={(value) => setIncidentReviewType(value ?? '')}
              noneLabel="All incident types"
              placeholder="All incident types"
              searchable={false}
              buttonAriaLabel="Incident review type"
              disabled={isSubmitting}
            />
          </label>
        ) : (
          <>
            <label className="review-create__field">
              <span className="review-create__label">Status filter</span>
              <SingleSelectDropdown
                label="Status filter"
                className="review-create__select-dropdown"
                options={REVIEW_PROJECT_CREATE_STATUS_FILTERS.map((option) => ({
                  value: option,
                  label: REVIEW_PROJECT_CREATE_STATUS_FILTER_LABELS[option],
                }))}
                value={selectedStatusFilter}
                onChange={(next) => {
                  if (next == null) {
                    return;
                  }
                  onChangeStatusFilter(next);
                }}
                disabled={isSubmitting}
                searchable={false}
              />
            </label>

            <label className="review-create__checkbox">
              <input
                type="checkbox"
                checked={skipTextUnitsInOpenProjects}
                onChange={(event) => setSkipTextUnitsInOpenProjects(event.target.checked)}
                disabled={isSubmitting}
              />
              <span>Skip text units already in active review projects</span>
            </label>
          </>
        )}

        <div className="review-create__field">
          <span className="review-create__label">Locales</span>
          <LocaleMultiSelect
            options={localeOptions.map((opt) => ({ tag: opt.tag, label: opt.label }))}
            selectedTags={selectedLocaleTags}
            onChange={setSelectedLocaleTags}
            className="review-create__locale-select"
            align="left"
            disabled={isSubmitting}
          />
        </div>

        {isIncidentReview ? (
          <div className="review-create__two-up">
            <div className="review-create__field">
              <label className="review-create__label" htmlFor="review-create-max-incidents">
                Maximum incidents overall (optional)
              </label>
              <input
                id="review-create-max-incidents"
                className="review-create__input"
                type="text"
                inputMode="numeric"
                value={maxIncidentCountDraft}
                onChange={(event) => setMaxIncidentCountDraft(event.target.value)}
                placeholder="All matching incidents"
                disabled={isSubmitting}
                aria-invalid={!maxIncidentCountValid}
                aria-describedby="review-create-max-incidents-hint"
              />
              <span className="review-create__hint" id="review-create-max-incidents-hint">
                Leave blank to include every eligible incident in the selected scope.
              </span>
              {!maxIncidentCountValid ? (
                <span className="review-create__error" role="alert">
                  Enter a whole number from 1 to 2,147,483,647, or leave blank.
                </span>
              ) : null}
            </div>
            <div className="review-create__field">
              <label className="review-create__label" htmlFor="review-create-incidents-per-project">
                Maximum incidents per project
              </label>
              <input
                id="review-create-incidents-per-project"
                className="review-create__input"
                type="text"
                inputMode="numeric"
                value={maxIncidentsPerProjectDraft}
                onChange={(event) => setMaxIncidentsPerProjectDraft(event.target.value)}
                disabled={isSubmitting}
                aria-invalid={!maxIncidentsPerProjectValid}
                aria-describedby="review-create-incidents-per-project-hint"
              />
              <span className="review-create__hint" id="review-create-incidents-per-project-hint">
                Split projects at this incident count or the word limit, whichever comes first.
              </span>
              {!maxIncidentsPerProjectValid ? (
                <span className="review-create__error" role="alert">
                  Enter a whole number from 1 to 5,000.
                </span>
              ) : null}
            </div>
          </div>
        ) : null}

        <div className="review-create__field">
          <label className="review-create__label" htmlFor="review-create-max-word-count">
            Max word count per project (optional)
          </label>
          <input
            id="review-create-max-word-count"
            className="review-create__input"
            type="text"
            inputMode="numeric"
            value={maxWordCountDraft}
            onChange={(event) => setMaxWordCountDraft(event.target.value)}
            placeholder="No limit"
            disabled={isSubmitting}
            aria-invalid={!maxWordCountValid}
            aria-describedby={
              maxWordCountValid
                ? 'review-create-max-word-count-hint'
                : 'review-create-max-word-count-hint review-create-max-word-count-error'
            }
          />
          <span className="review-create__hint" id="review-create-max-word-count-hint">
            Split each locale into projects by source word count. Leave blank for no limit.
            Individual strings stay whole and may exceed the limit.
          </span>
          {!maxWordCountValid ? (
            <span
              className="review-create__error"
              id="review-create-max-word-count-error"
              role="alert"
            >
              Enter a whole number from 1 to {isIncidentReview ? '100,000' : '2,147,483,647'}, or
              leave blank.
            </span>
          ) : null}
        </div>

        {teamOptions && onChangeTeam ? (
          <>
            <label className="review-create__field">
              <span className="review-create__label">
                {isIncidentReview ? 'Owning team' : 'Team (optional)'}
              </span>
              <SingleSelectDropdown
                label="Team"
                className="review-create__select-dropdown"
                options={teamOptions.map((team) => ({
                  value: team.id,
                  label: `${team.name} (#${team.id})`,
                }))}
                value={selectedTeamId}
                onChange={onChangeTeam}
                noneLabel="No team"
                placeholder="No team"
                disabled={isSubmitting}
                buttonAriaLabel="Select team for default assignment"
              />
            </label>
            <label className="review-create__checkbox">
              <input
                type="checkbox"
                checked={assignTranslator}
                onChange={(event) => setAssignTranslator(event.target.checked)}
                disabled={isSubmitting || selectedTeamId == null}
              />
              <span>Assign translator from locale pool</span>
            </label>
          </>
        ) : null}

        <div className="review-create__two-up">
          <label className="review-create__field">
            <span className="review-create__label">Type</span>
            <SingleSelectDropdown
              label="Type"
              className="review-create__select-dropdown"
              options={REVIEW_PROJECT_TYPES.filter(
                (option) => option !== 'UNKNOWN' && option !== 'TERM_CANDIDATE',
              ).map((option) => ({
                value: option,
                label: REVIEW_PROJECT_TYPE_LABELS[option],
              }))}
              value={type}
              onChange={(next) => {
                if (next == null) {
                  return;
                }
                setType(next);
              }}
              disabled={isSubmitting}
              searchable={false}
            />
          </label>
          <label className="review-create__field">
            <span className="review-create__label">Due date</span>
            <input
              className="review-create__input"
              type="datetime-local"
              value={dueDate}
              onChange={(event) => setDueDate(event.target.value)}
              disabled={isSubmitting}
            />
          </label>
        </div>

        <div className="review-create__field">
          <RequestDescriptionEditor
            value={notes}
            onChange={setNotes}
            canEdit
            disabled={isSubmitting}
            onDropFiles={async (files) => {
              const uploadedKeys = await handleFiles(files);
              if (uploadedKeys.length === 0) {
                return null;
              }
              const snippets = uploadedKeys.map((key) => toDescriptionAttachmentMarkdown(key));
              return `${snippets.join('\n')}\n`;
            }}
          />
        </div>

        <div className="review-create__field">
          <RequestAttachmentsDropzone
            label="Screenshots (optional)"
            hint="Drop images, videos, PDFs to upload."
            keys={screenshotKeys}
            uploadQueue={uploadQueue}
            disabled={isSubmitting}
            isUploading={uploadQueue.some((item) => item.status === 'uploading')}
            optimizeImages={optimizeImagesBeforeUpload}
            onToggleOptimizeImages={setOptimizeImagesBeforeUpload}
            onFilesSelected={async (files) => {
              await handleFiles(files);
            }}
            onRemoveKey={(key) =>
              setScreenshotKeys((current) => current.filter((value) => value !== key))
            }
          />
        </div>
      </div>

      {isIncidentReview && currentPreview ? (
        <div className="review-create__report" role="status">
          <div className="review-create__report-title">Incident preview</div>
          <div className="review-create__report-summary">
            {currentPreview.eligibleIncidentCount} eligible incident
            {currentPreview.eligibleIncidentCount === 1 ? '' : 's'}
            {' · '}
            {currentPreview.projectCount} project{currentPreview.projectCount === 1 ? '' : 's'}
            {' · '}
            {currentPreview.localeTags.length} locale
            {currentPreview.localeTags.length === 1 ? '' : 's'}
          </div>
          <IncidentSkipSummary
            skipped={currentPreview.skipped}
            skippedIncidentCount={currentPreview.skippedIncidentCount}
          />
          <p className="review-create__hint">
            {currentPreview.limitReached
              ? 'The overall incident limit was reached. Increase or clear it to include more matching incidents.'
              : 'This preview covers all matching incidents in the selected scope.'}{' '}
            Eligibility is checked again when each project is created.
          </p>
          {!currentPreview.eligibleIncidentCount ? (
            <p>No eligible incidents found. No projects need to be created.</p>
          ) : null}
        </div>
      ) : null}
      <div className="review-create__actions">
        {errorMessage ? <div className="review-create__error">{errorMessage}</div> : null}
        {onCancel ? (
          <button type="button" className="review-create__ghost" onClick={onCancel}>
            {cancelLabel}
          </button>
        ) : null}
        {isIncidentReview && onPreviewIncidents ? (
          <button
            type="button"
            className="review-create__ghost"
            disabled={!canSubmit || isSubmitting}
            onClick={() => onPreviewIncidents(payload)}
          >
            {isPreviewing ? 'Previewing…' : 'Preview incidents'}
          </button>
        ) : null}
        <button
          type="button"
          className="review-create__cta"
          onClick={() => {
            if (!canSubmit || isSubmitting) return;
            if (!payload.dueDate) {
              return;
            }
            onSubmit(payload);
          }}
          disabled={!canSubmit || isSubmitting}
        >
          {isSubmitting ? (
            <>
              <span className="spinner" aria-hidden="true" /> {submitLabel}…
            </>
          ) : (
            submitLabel
          )}
        </button>
      </div>
    </div>
  );
}
