import './review-projects-page.css';

import { useEffect, useMemo, useState } from 'react';

import {
  type ApiReviewProjectType,
  REVIEW_PROJECT_TYPE_LABELS,
  REVIEW_PROJECT_TYPES,
} from '../../api/review-projects';
import { type CollectionOption, CollectionSelect } from '../../components/CollectionSelect';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import {
  RequestAttachmentsDropzone,
  type RequestAttachmentUploadQueueItem,
} from '../../components/review-request/RequestAttachmentsDropzone';
import { RequestDescriptionEditor } from '../../components/review-request/RequestDescriptionEditor';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import type { LocaleSelectionOption } from '../../utils/localeSelection';
import {
  buildUploadFileKey,
  getAttachmentKindFromFile,
  isSupportedRequestAttachmentFile,
  toDescriptionAttachmentMarkdown,
} from '../../utils/request-attachments';

export type ReviewProjectCreateFormValues = {
  name: string;
  dueDate: string;
  type: ApiReviewProjectType;
  localeTags: string[];
  notes: string | null;
  tmTextUnitIds: number[];
  screenshotImageIds: string[];
};

type Props = {
  defaultName: string;
  defaultDueDate: string;
  localeOptions: LocaleSelectionOption[];
  tmTextUnitIds: number[];
  collectionName?: string | null;
  collectionOptions?: CollectionOption[];
  selectedCollectionId?: string | null;
  onChangeCollection?: (id: string | null) => void;
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
  collectionName,
  collectionOptions,
  selectedCollectionId,
  onChangeCollection,
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
  const [notes, setNotes] = useState('');
  const [screenshotKeys, setScreenshotKeys] = useState<string[]>([]);
  const [uploadQueue, setUploadQueue] = useState<RequestAttachmentUploadQueueItem[]>([]);

  useEffect(() => setName(defaultName), [defaultName]);
  useEffect(() => setDueDate(defaultDueDate), [defaultDueDate]);
  useEffect(() => {
    setSelectedLocaleTags((current) => {
      const allowed = new Set(localeOptions.map((opt) => opt.tag));
      return current.filter((tag) => allowed.has(tag));
    });
  }, [localeOptions]);

  const canSubmit = useMemo(
    () =>
      Boolean(name.trim()) &&
      Boolean(dueDate) &&
      tmTextUnitIds.length > 0 &&
      selectedLocaleTags.length > 0 &&
      uploadQueue.every((item) => item.status !== 'uploading'),
    [dueDate, name, selectedLocaleTags.length, tmTextUnitIds.length, uploadQueue],
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

  const uploadImage = async (file: File): Promise<string> => {
    const key = buildUploadFileKey(file);
    const buffer = await file.arrayBuffer();
    const response = await fetch(`/api/images/${encodeURIComponent(key)}`, {
      method: 'PUT',
      credentials: 'include',
      body: buffer,
    });
    if (!response.ok) {
      const msg = await response.text().catch(() => '');
      throw new Error(msg || 'Upload failed');
    }
    return key;
  };

  const handleFiles = async (files: FileList | null): Promise<string[]> => {
    if (!files || files.length === 0) {
      return [];
    }
    const fileArr = Array.from(files);
    const queueEntries: RequestAttachmentUploadQueueItem[] = fileArr.map((file) => {
      const kind = getAttachmentKindFromFile(file);
      const isSupported = isSupportedRequestAttachmentFile(file);
      return {
        key: `${file.name}-${Date.now()}-${Math.random().toString(36).slice(2)}`,
        name: file.name,
        status: isSupported ? ('uploading' as const) : ('error' as const),
        kind,
        preview: kind === 'image' || kind === 'video' ? URL.createObjectURL(file) : null,
        error: isSupported ? undefined : 'Unsupported file type',
      };
    });
    setUploadQueue((prev) => [...queueEntries, ...prev]);
    const uploadedKeys: string[] = [];
    await Promise.all(
      queueEntries.map(async (entry, index) => {
        const file = fileArr[index];
        if (!isSupportedRequestAttachmentFile(file)) {
          return;
        }
        try {
          const uploadedKey = await uploadImage(file);
          uploadedKeys.push(uploadedKey);
          setUploadQueue((prev) =>
            prev.map((item) => (item.key === entry.key ? { ...item, status: 'done' } : item)),
          );
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

        {collectionOptions && onChangeCollection ? (
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
        ) : collectionName ? (
          <div className="review-create__field">
            <span className="review-create__label">Collection</span>
            <div className="review-create__pill">{collectionName}</div>
          </div>
        ) : null}

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
            const dueIso = new Date(dueDate).toISOString();
            onSubmit({
              name: name.trim(),
              dueDate: dueIso,
              type,
              localeTags: selectedLocaleTags,
              notes: notes.trim().length > 0 ? notes : null,
              tmTextUnitIds,
              screenshotImageIds: screenshotKeys,
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
