import './request-attachments-dropzone.css';

import { type ChangeEvent, type DragEvent, useCallback, useRef, useState } from 'react';

import {
  isImageAttachmentKey,
  isPdfAttachmentKey,
  isVideoAttachmentKey,
  type RequestAttachmentKind,
  resolveAttachmentUrl,
  toDescriptionAttachmentMarkdown,
} from '../../utils/request-attachments';

export type RequestAttachmentUploadQueueItem = {
  key: string;
  name: string;
  status: 'uploading' | 'done' | 'error';
  kind: RequestAttachmentKind;
  preview?: string | null;
  error?: string;
};

const ATTACHMENT_MARKDOWN_MIME = 'application/x-mojito-markdown';

type Props = {
  label?: string;
  hint?: string;
  emptyLabel?: string;
  uploadButtonLabel?: string;
  dropHint?: string;
  keys: string[];
  uploadQueue?: RequestAttachmentUploadQueueItem[];
  disabled?: boolean;
  isUploading?: boolean;
  onFilesSelected: (files: FileList | null) => Promise<void> | void;
  onRemoveKey: (key: string) => void;
};

export function RequestAttachmentsDropzone({
  label = 'Attachments',
  hint = 'Screenshots, videos, PDFs',
  emptyLabel = 'No attachments',
  uploadButtonLabel = 'Drop files or click to upload',
  dropHint = 'or drop files here',
  keys,
  uploadQueue = [],
  disabled = false,
  isUploading = false,
  onFilesSelected,
  onRemoveKey,
}: Props) {
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const dragDepthRef = useRef(0);
  const [isDropActive, setIsDropActive] = useState(false);

  const openFilePicker = useCallback(() => {
    if (disabled) {
      return;
    }
    fileInputRef.current?.click();
  }, [disabled]);

  const handleDragEnter = useCallback(
    (event: DragEvent<HTMLDivElement>) => {
      if (disabled) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      dragDepthRef.current += 1;
      setIsDropActive(true);
    },
    [disabled],
  );

  const handleDragLeave = useCallback((event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    dragDepthRef.current = Math.max(dragDepthRef.current - 1, 0);
    if (dragDepthRef.current === 0) {
      setIsDropActive(false);
    }
  }, []);

  const handleDragOver = useCallback(
    (event: DragEvent<HTMLDivElement>) => {
      if (disabled) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
    },
    [disabled],
  );

  const handleDrop = useCallback(
    (event: DragEvent<HTMLDivElement>) => {
      if (disabled) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      dragDepthRef.current = 0;
      setIsDropActive(false);
      void onFilesSelected(event.dataTransfer.files);
    },
    [disabled, onFilesSelected],
  );

  const handleInputChange = useCallback(
    (event: ChangeEvent<HTMLInputElement>) => {
      void onFilesSelected(event.target.files);
      event.target.value = '';
    },
    [onFilesSelected],
  );

  const queueItems = uploadQueue.filter((item) => item.status !== 'done');

  const getKeyKind = useCallback((key: string): RequestAttachmentKind => {
    if (isImageAttachmentKey(key)) {
      return 'image';
    }
    if (isVideoAttachmentKey(key)) {
      return 'video';
    }
    if (isPdfAttachmentKey(key)) {
      return 'pdf';
    }
    return 'file';
  }, []);

  return (
    <div className="request-attachments">
      <div className="request-attachments__label-row">
        <span className="request-attachments__label">{label}</span>
        <span className="request-attachments__hint">{hint}</span>
      </div>

      <div
        className={`request-attachments__dropzone${
          isDropActive ? ' request-attachments__dropzone--active' : ''
        }${disabled ? ' request-attachments__dropzone--disabled' : ''}`}
        onClick={openFilePicker}
        onKeyDown={(event) => {
          if (disabled) {
            return;
          }
          if (event.key !== 'Enter' && event.key !== ' ') {
            return;
          }
          event.preventDefault();
          openFilePicker();
        }}
        onDragEnter={handleDragEnter}
        onDragLeave={handleDragLeave}
        onDragOver={handleDragOver}
        onDrop={handleDrop}
        role="button"
        tabIndex={disabled ? -1 : 0}
        aria-disabled={disabled}
      >
        <input
          ref={fileInputRef}
          type="file"
          multiple
          className="request-attachments__file-input"
          accept="image/*,video/*,application/pdf"
          onChange={handleInputChange}
          disabled={disabled}
        />
        <div className="request-attachments__dropzone-main">
          <button
            type="button"
            className="request-attachments__pick-button"
            onClick={(event) => {
              event.stopPropagation();
              openFilePicker();
            }}
            disabled={disabled}
          >
            Choose files
          </button>
          <div className="request-attachments__drop-hint">{dropHint}</div>
        </div>
        <span className="request-attachments__upload-button">
          {isUploading ? 'Uploading…' : uploadButtonLabel}
        </span>
        {isUploading ? (
          <span className="request-attachments__upload-status">Uploading files…</span>
        ) : null}
      </div>

      {keys.length > 0 ? (
        <div className="request-attachments__upload-list" aria-label="Upload status">
          {keys.map((key) => {
            const kind = getKeyKind(key);
            const src = resolveAttachmentUrl(key);
            const insertSnippet = `${toDescriptionAttachmentMarkdown(key)}\n`;
            return (
              <div
                key={key}
                className="request-attachments__upload-row request-attachments__upload-row--attached"
                draggable={!disabled}
                onDragStart={(event) => {
                  event.dataTransfer.effectAllowed = 'copy';
                  event.dataTransfer.setData(ATTACHMENT_MARKDOWN_MIME, insertSnippet);
                  event.dataTransfer.setData('text/plain', insertSnippet);
                  event.dataTransfer.setData('text/markdown', insertSnippet);
                }}
                title="Drag into description to insert"
              >
                {kind === 'image' ? (
                  <img
                    src={src}
                    alt=""
                    className="request-attachments__upload-thumb"
                    loading="lazy"
                  />
                ) : kind === 'video' ? (
                  <video
                    src={src}
                    className="request-attachments__upload-thumb request-attachments__upload-thumb--video"
                    muted
                    loop
                    playsInline
                    preload="metadata"
                  />
                ) : (
                  <span className="request-attachments__upload-thumb request-attachments__upload-thumb--placeholder">
                    {kind === 'pdf' ? 'PDF' : 'FILE'}
                  </span>
                )}
                <a
                  className="request-attachments__upload-name request-attachments__upload-name--link"
                  href={src}
                  target="_blank"
                  rel="noreferrer"
                  title={key}
                >
                  {key}
                </a>
                <button
                  type="button"
                  className="request-attachments__upload-action"
                  onClick={() => onRemoveKey(key)}
                  disabled={disabled}
                  aria-label={`Remove ${key}`}
                >
                  ×
                </button>
              </div>
            );
          })}
        </div>
      ) : (
        <span className="request-attachments__empty">{emptyLabel}</span>
      )}

      {queueItems.length > 0 ? (
        <div className="request-attachments__upload-list" aria-label="Upload status">
          {queueItems.map((item) => (
            <div key={item.key} className="request-attachments__upload-row">
              {item.preview ? (
                item.kind === 'video' ? (
                  <video
                    src={item.preview}
                    className="request-attachments__upload-thumb request-attachments__upload-thumb--video"
                    muted
                    loop
                    playsInline
                    preload="metadata"
                  />
                ) : (
                  <img
                    src={item.preview}
                    alt=""
                    className="request-attachments__upload-thumb"
                    loading="lazy"
                  />
                )
              ) : (
                <span className="request-attachments__upload-thumb request-attachments__upload-thumb--placeholder">
                  {item.kind === 'pdf' ? 'PDF' : 'FILE'}
                </span>
              )}
              <span className="request-attachments__upload-name">{item.name}</span>
              <span className={`request-attachments__upload-status-label status-${item.status}`}>
                {item.status === 'uploading' ? 'Uploading…' : item.error || 'Failed'}
              </span>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}
