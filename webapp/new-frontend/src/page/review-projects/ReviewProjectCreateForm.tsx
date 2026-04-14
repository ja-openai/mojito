import './review-projects-page.css';

import { useEffect, useMemo, useRef, useState } from 'react';

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

export type ReviewProjectCreateFormValues = {
  name: string;
  dueDate: string;
  type: ApiReviewProjectType;
  localeTags: string[];
  notes: string | null;
  tmTextUnitIds?: number[] | null;
  reviewFeatureIds?: number[] | null;
  statusFilter: ReviewProjectCreateStatusFilter;
  skipTextUnitsInOpenProjects: boolean;
  screenshotImageIds: string[];
  teamId: number | null;
  assignTranslator: boolean;
};

export type ReviewProjectSourceMode = 'TEXT_UNITS' | 'REVIEW_FEATURE';

type Props = {
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
  onSubmit: (payload: ReviewProjectCreateFormValues) => void;
  onCancel?: () => void;
};

export function ReviewProjectCreateForm({
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
  reviewFeatureOptions,
  selectedReviewFeatureIds = [],
  onChangeReviewFeatures,
  teamOptions,
  selectedTeamId = null,
  onChangeTeam,
  selectedStatusFilter,
  onChangeStatusFilter,
  isSubmitting = false,
  errorMessage,
  submitLabel = 'Create',
  onSubmit,
  onCancel,
}: Props) {
  const [name, setName] = useState(defaultName);
  const [dueDate, setDueDate] = useState(defaultDueDate);
  const [type, setType] = useState<ApiReviewProjectType>('NORMAL');
  const [selectedLocaleTags, setSelectedLocaleTags] = useState<string[]>([]);
  const [skipTextUnitsInOpenProjects, setSkipTextUnitsInOpenProjects] = useState(true);
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

  const canSubmit = useMemo(
    () =>
      Boolean(name.trim()) &&
      Boolean(dueDate) &&
      (sourceMode === 'TEXT_UNITS'
        ? tmTextUnitIds.length > 0
        : selectedReviewFeatureIds.length > 0) &&
      selectedLocaleTags.length > 0 &&
      uploadQueue.every((item) => item.status !== 'uploading'),
    [
      dueDate,
      name,
      selectedLocaleTags.length,
      selectedReviewFeatureIds.length,
      sourceMode,
      tmTextUnitIds.length,
      uploadQueue,
    ],
  );

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

        <div className="review-create__field">
          <span className="review-create__label">Scope</span>
          <div
            className="review-projects-page__mode-toggle"
            role="group"
            aria-label="Project scope"
          >
            <button
              type="button"
              className={`review-projects-page__mode-button${
                sourceMode === 'TEXT_UNITS' ? ' is-active' : ''
              }`}
              onClick={() => onChangeSourceMode('TEXT_UNITS')}
              disabled={isSubmitting || !collectionOptions?.length}
            >
              Selected text units
            </button>
            <button
              type="button"
              className={`review-projects-page__mode-button${
                sourceMode === 'REVIEW_FEATURE' ? ' is-active' : ''
              }`}
              onClick={() => onChangeSourceMode('REVIEW_FEATURE')}
              disabled={isSubmitting || !reviewFeatureOptions?.length}
            >
              Review feature
            </button>
          </div>
        </div>

        {sourceMode === 'TEXT_UNITS' && collectionOptions && onChangeCollection ? (
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

        {sourceMode === 'TEXT_UNITS' && !collectionOptions && collectionName ? (
          <div className="review-create__field">
            <span className="review-create__label">Collection</span>
            <div className="review-create__pill">{collectionName}</div>
          </div>
        ) : null}

        {sourceMode === 'REVIEW_FEATURE' && reviewFeatureOptions && onChangeReviewFeatures ? (
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
            {sourceMode === 'TEXT_UNITS'
              ? `${tmTextUnitIds.length} selected text unit${tmTextUnitIds.length === 1 ? '' : 's'}`
              : `Creates one request per selected feature from review-needed strings (${selectedReviewFeatureIds.length} selected).`}
          </span>
        </div>

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

        {teamOptions && onChangeTeam ? (
          <>
            <label className="review-create__field">
              <span className="review-create__label">Team (optional)</span>
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
              options={REVIEW_PROJECT_TYPES.filter((option) => option !== 'UNKNOWN').map(
                (option) => ({
                  value: option,
                  label: REVIEW_PROJECT_TYPE_LABELS[option],
                }),
              )}
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

      <div className="review-create__actions">
        {errorMessage ? <div className="review-create__error">{errorMessage}</div> : null}
        {onCancel ? (
          <button type="button" className="review-create__ghost" onClick={onCancel}>
            Cancel
          </button>
        ) : null}
        <button
          type="button"
          className="review-create__cta"
          onClick={() => {
            if (!canSubmit || isSubmitting) return;
            const dueIso = localDateTimeInputToIso(dueDate);
            if (!dueIso) {
              return;
            }
            onSubmit({
              name: name.trim(),
              dueDate: dueIso,
              type,
              localeTags: selectedLocaleTags,
              notes: notes.trim().length > 0 ? notes : null,
              tmTextUnitIds: sourceMode === 'TEXT_UNITS' ? tmTextUnitIds : null,
              reviewFeatureIds: sourceMode === 'REVIEW_FEATURE' ? selectedReviewFeatureIds : null,
              statusFilter: selectedStatusFilter,
              skipTextUnitsInOpenProjects,
              screenshotImageIds: screenshotKeys,
              teamId: selectedTeamId,
              assignTranslator,
            });
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
