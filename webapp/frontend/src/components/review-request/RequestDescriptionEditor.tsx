import './request-description-editor.css';

import { useState } from 'react';

import { MarkdownPreview } from '../markdown/MarkdownPreview';
import { MarkdownRichTextEditor } from '../markdown/MarkdownRichTextEditor';

type Props = {
  value: string;
  onChange: (nextValue: string) => void;
  canEdit: boolean;
  disabled?: boolean;
  placeholder?: string;
  previewEmptyLabel?: string;
  editorDropHint?: string;
  onDropFiles?: (files: FileList) => string | null | void | Promise<string | null | void>;
};

export function RequestDescriptionEditor({
  value,
  onChange,
  canEdit,
  disabled = false,
  placeholder = 'Write request guidance, checklists, links, and notes.',
  previewEmptyLabel = 'No description provided.',
  editorDropHint = 'Drop files in editor to upload and attach below',
  onDropFiles,
}: Props) {
  const [mode, setMode] = useState<'edit' | 'preview'>('edit');

  return (
    <div className="request-description-editor">
      <div className="request-description-editor__header">
        <span className="request-description-editor__label">Description</span>
        {canEdit ? (
          <div className="request-description-editor__header-controls">
            <span className="request-description-editor__hint">{editorDropHint}</span>
            <div
              className="request-description-editor__mode-toggle"
              role="group"
              aria-label="Description editor mode"
            >
              <button
                type="button"
                className={`request-description-editor__mode-button${
                  mode === 'edit' ? ' request-description-editor__mode-button--active' : ''
                }`}
                onClick={() => setMode('edit')}
              >
                Edit
              </button>
              <button
                type="button"
                className={`request-description-editor__mode-button${
                  mode === 'preview' ? ' request-description-editor__mode-button--active' : ''
                }`}
                onClick={() => setMode('preview')}
              >
                Preview
              </button>
            </div>
          </div>
        ) : (
          <span className="request-description-editor__hint">Rich text, stored as markdown</span>
        )}
      </div>

      {canEdit ? (
        mode === 'edit' ? (
          <MarkdownRichTextEditor
            value={value}
            onChange={onChange}
            onDropFiles={onDropFiles}
            disabled={disabled}
            placeholder={placeholder}
          />
        ) : (
          <MarkdownPreview markdown={value} emptyLabel={previewEmptyLabel} />
        )
      ) : (
        <MarkdownPreview markdown={value} emptyLabel={previewEmptyLabel} />
      )}
    </div>
  );
}
