import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { htmlToMarkdown, markdownToHtml } from './markdown-format';
import './markdown.css';

type Props = {
  value: string;
  onChange: (nextValue: string) => void;
  disabled?: boolean;
  placeholder?: string;
  className?: string;
};

function ToolbarButton({
  label,
  onClick,
  disabled,
  title,
}: {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  title?: string;
}) {
  return (
    <button
      type="button"
      className="markdown-editor__toolbar-button"
      onMouseDown={(event) => event.preventDefault()}
      onClick={onClick}
      disabled={disabled}
      title={title}
    >
      {label}
    </button>
  );
}

export function MarkdownRichTextEditor({
  value,
  onChange,
  disabled = false,
  placeholder = 'Start typing…',
  className,
}: Props) {
  const editorRef = useRef<HTMLDivElement | null>(null);
  const lastEmittedValueRef = useRef(value);
  const [isEmpty, setIsEmpty] = useState(() => value.trim().length === 0);

  const setEditorFromMarkdown = useCallback((markdown: string) => {
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    const nextHtml = markdownToHtml(markdown);
    if (editor.innerHTML !== nextHtml) {
      editor.innerHTML = nextHtml;
    }
    setIsEmpty(markdown.trim().length === 0);
  }, []);

  useEffect(() => {
    if (value !== lastEmittedValueRef.current) {
      setEditorFromMarkdown(value);
    }
    lastEmittedValueRef.current = value;
  }, [setEditorFromMarkdown, value]);

  const emitMarkdown = useCallback(() => {
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    const markdown = htmlToMarkdown(editor.innerHTML);
    lastEmittedValueRef.current = markdown;
    setIsEmpty(markdown.trim().length === 0);
    onChange(markdown);
  }, [onChange]);

  const runCommand = useCallback(
    (command: string, arg?: string) => {
      if (disabled) {
        return;
      }
      const editor = editorRef.current;
      if (!editor) {
        return;
      }
      editor.focus();
      document.execCommand(command, false, arg);
      emitMarkdown();
    },
    [disabled, emitMarkdown],
  );

  const clearFormatting = useCallback(() => {
    runCommand('removeFormat');
    runCommand('unlink');
  }, [runCommand]);

  const addLink = useCallback(() => {
    if (disabled) {
      return;
    }
    const raw = window.prompt('Enter URL');
    const next = raw?.trim() ?? '';
    if (!next) {
      return;
    }
    runCommand('createLink', next);
  }, [disabled, runCommand]);

  const containerClassName = useMemo(
    () =>
      `markdown-editor${disabled ? ' markdown-editor--disabled' : ''}${
        className ? ` ${className}` : ''
      }`,
    [className, disabled],
  );

  return (
    <div className={containerClassName}>
      <div className="markdown-editor__toolbar">
        <ToolbarButton
          label="B"
          title="Bold"
          onClick={() => runCommand('bold')}
          disabled={disabled}
        />
        <ToolbarButton
          label="I"
          title="Italic"
          onClick={() => runCommand('italic')}
          disabled={disabled}
        />
        <ToolbarButton
          label="• List"
          title="Bullet list"
          onClick={() => runCommand('insertUnorderedList')}
          disabled={disabled}
        />
        <ToolbarButton
          label="1. List"
          title="Numbered list"
          onClick={() => runCommand('insertOrderedList')}
          disabled={disabled}
        />
        <ToolbarButton
          label="Quote"
          title="Quote"
          onClick={() => runCommand('formatBlock', 'blockquote')}
          disabled={disabled}
        />
        <ToolbarButton
          label="Code"
          title="Inline code"
          onClick={() => runCommand('formatBlock', 'pre')}
          disabled={disabled}
        />
        <ToolbarButton label="Link" title="Add link" onClick={addLink} disabled={disabled} />
        <ToolbarButton
          label="Clear"
          title="Clear formatting"
          onClick={clearFormatting}
          disabled={disabled}
        />
      </div>
      <div
        ref={editorRef}
        className="markdown-editor__surface"
        role="textbox"
        aria-multiline="true"
        contentEditable={!disabled}
        suppressContentEditableWarning
        onInput={emitMarkdown}
        onBlur={emitMarkdown}
        data-placeholder={placeholder}
        data-empty={isEmpty ? 'true' : 'false'}
      />
    </div>
  );
}
