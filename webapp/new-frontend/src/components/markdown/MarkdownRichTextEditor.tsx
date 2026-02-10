import './markdown.css';

import { type DragEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { htmlToMarkdown, markdownToHtml } from './markdown-format';

type Props = {
  value: string;
  onChange: (nextValue: string) => void;
  onDropFiles?: (files: FileList) => string | null | void | Promise<string | null | void>;
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
  onDropFiles,
  disabled = false,
  placeholder = 'Start typing…',
  className,
}: Props) {
  const editorRef = useRef<HTMLDivElement | null>(null);
  const lastEmittedValueRef = useRef(value);
  const [isEmpty, setIsEmpty] = useState(() => value.trim().length === 0);
  const [isDragOver, setIsDragOver] = useState(false);

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
    if (
      value !== lastEmittedValueRef.current ||
      (editorRef.current != null && editorRef.current.innerHTML === '')
    ) {
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

  const withEditorSelection = useCallback(
    (
      action: (params: {
        editor: HTMLDivElement;
        selection: Selection;
        range: Range;
      }) => void,
    ) => {
      if (disabled) {
        return;
      }
      const editor = editorRef.current;
      const selection = window.getSelection();
      if (!editor || !selection) {
        return;
      }
      editor.focus();
      if (selection.rangeCount === 0) {
        const fallback = document.createRange();
        fallback.selectNodeContents(editor);
        fallback.collapse(false);
        selection.removeAllRanges();
        selection.addRange(fallback);
      }
      const range = selection.rangeCount > 0 ? selection.getRangeAt(0) : null;
      if (!range) {
        return;
      }
      if (!editor.contains(range.commonAncestorContainer)) {
        const fallback = document.createRange();
        fallback.selectNodeContents(editor);
        fallback.collapse(false);
        selection.removeAllRanges();
        selection.addRange(fallback);
      }
      const activeRange = selection.getRangeAt(0);
      action({ editor, selection, range: activeRange });
      emitMarkdown();
    },
    [disabled, emitMarkdown],
  );

  const selectNodeContents = useCallback((selection: Selection, node: Node) => {
    const nextRange = document.createRange();
    nextRange.selectNodeContents(node);
    selection.removeAllRanges();
    selection.addRange(nextRange);
  }, []);

  const applyInlineWrap = useCallback(
    (tagName: 'strong' | 'em' | 'code', placeholder: string) => {
      withEditorSelection(({ selection, range }) => {
        const wrapper = document.createElement(tagName);
        if (range.collapsed) {
          wrapper.textContent = placeholder;
          range.insertNode(wrapper);
        } else {
          const fragment = range.extractContents();
          wrapper.append(fragment);
          range.insertNode(wrapper);
        }
        selectNodeContents(selection, wrapper);
      });
    },
    [selectNodeContents, withEditorSelection],
  );

  const applyList = useCallback(
    (ordered: boolean) => {
      withEditorSelection(({ selection, range }) => {
        const raw = range.toString();
        const lines = raw
          .split(/\r?\n/)
          .map((line) => line.trim())
          .filter(Boolean);
        const nextLines = lines.length > 0 ? lines : ['List item'];
        const list = document.createElement(ordered ? 'ol' : 'ul');
        nextLines.forEach((line) => {
          const item = document.createElement('li');
          item.textContent = line;
          list.append(item);
        });
        range.deleteContents();
        range.insertNode(list);
        selectNodeContents(selection, list);
      });
    },
    [selectNodeContents, withEditorSelection],
  );

  const applyBlockquote = useCallback(() => {
    withEditorSelection(({ selection, range }) => {
      const raw = range.toString().trim();
      const content = raw || 'Quote';
      const block = document.createElement('blockquote');
      const paragraph = document.createElement('p');
      const lines = content.split(/\r?\n/);
      lines.forEach((line, idx) => {
        if (idx > 0) {
          paragraph.append(document.createElement('br'));
        }
        paragraph.append(document.createTextNode(line));
      });
      block.append(paragraph);
      range.deleteContents();
      range.insertNode(block);
      selectNodeContents(selection, block);
    });
  }, [selectNodeContents, withEditorSelection]);

  const applyCodeBlock = useCallback(() => {
    withEditorSelection(({ selection, range }) => {
      const raw = range.toString();
      const content = raw || 'code';
      const pre = document.createElement('pre');
      const code = document.createElement('code');
      code.textContent = content;
      pre.append(code);
      range.deleteContents();
      range.insertNode(pre);
      selectNodeContents(selection, pre);
    });
  }, [selectNodeContents, withEditorSelection]);

  const clearFormatting = useCallback(() => {
    withEditorSelection(({ selection, range }) => {
      if (range.collapsed) {
        return;
      }
      const text = range.toString();
      range.deleteContents();
      const textNode = document.createTextNode(text);
      range.insertNode(textNode);
      const nextRange = document.createRange();
      nextRange.setStartAfter(textNode);
      nextRange.collapse(true);
      selection.removeAllRanges();
      selection.addRange(nextRange);
    });
  }, [withEditorSelection]);

  const normalizeLink = useCallback((raw: string): string | null => {
    const trimmed = raw.trim();
    if (!trimmed) {
      return null;
    }
    if (trimmed.startsWith('/') || trimmed.startsWith('#')) {
      return trimmed;
    }
    try {
      const parsed = new URL(trimmed);
      if (parsed.protocol === 'http:' || parsed.protocol === 'https:' || parsed.protocol === 'mailto:') {
        return parsed.toString();
      }
    } catch {
      return null;
    }
    return null;
  }, []);

  const addLink = useCallback(() => {
    if (disabled) {
      return;
    }
    const raw = window.prompt('Enter URL');
    const next = raw ? normalizeLink(raw) : null;
    if (!next) {
      return;
    }
    withEditorSelection(({ selection, range }) => {
      const anchor = document.createElement('a');
      anchor.href = next;
      anchor.target = '_blank';
      anchor.rel = 'noreferrer';

      if (range.collapsed) {
        anchor.textContent = 'link';
        range.insertNode(anchor);
      } else {
        const fragment = range.extractContents();
        anchor.append(fragment);
        range.insertNode(anchor);
      }
      selectNodeContents(selection, anchor);
    });
  }, [disabled, normalizeLink, selectNodeContents, withEditorSelection]);

  const getDropRange = useCallback((event: DragEvent<HTMLDivElement>): Range | null => {
    const docWithCaretRange = document as Document & {
      caretRangeFromPoint?: (x: number, y: number) => Range | null;
      caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null;
    };
    const byRange = docWithCaretRange.caretRangeFromPoint?.(event.clientX, event.clientY);
    if (byRange) {
      return byRange;
    }
    const byPosition = docWithCaretRange.caretPositionFromPoint?.(event.clientX, event.clientY);
    if (!byPosition) {
      return null;
    }
    const range = document.createRange();
    range.setStart(byPosition.offsetNode, byPosition.offset);
    range.collapse(true);
    return range;
  }, []);

  const handleDragOver = useCallback(
    (event: DragEvent<HTMLDivElement>) => {
      if (disabled || !onDropFiles) {
        return;
      }
      event.preventDefault();
      setIsDragOver(true);
    },
    [disabled, onDropFiles],
  );

  const handleDragLeave = useCallback((event: DragEvent<HTMLDivElement>) => {
    if (event.currentTarget.contains(event.relatedTarget as Node | null)) {
      return;
    }
    setIsDragOver(false);
  }, []);

  const handleDrop = useCallback(
    (event: DragEvent<HTMLDivElement>) => {
      if (disabled || !onDropFiles) {
        return;
      }
      event.preventDefault();
      setIsDragOver(false);
      const { files } = event.dataTransfer;
      if (!files || files.length === 0) {
        return;
      }
      void (async () => {
        const editor = editorRef.current;
        const selection = window.getSelection();
        if (!editor || !selection) {
          return;
        }

        const dropRange = getDropRange(event);
        if (dropRange && editor.contains(dropRange.commonAncestorContainer)) {
          selection.removeAllRanges();
          selection.addRange(dropRange);
        } else if (
          selection.rangeCount > 0 &&
          !editor.contains(selection.getRangeAt(0).commonAncestorContainer)
        ) {
          const fallback = document.createRange();
          fallback.selectNodeContents(editor);
          fallback.collapse(false);
          selection.removeAllRanges();
          selection.addRange(fallback);
        }

        const insertionRange =
          selection.rangeCount > 0 ? selection.getRangeAt(0).cloneRange() : null;
        const insertedText = await onDropFiles(files);
        if (!insertedText) {
          return;
        }

        editor.focus();
        const activeSelection = window.getSelection();
        if (!activeSelection) {
          return;
        }
        let rangeToUse = insertionRange;
        if (!rangeToUse || !editor.contains(rangeToUse.commonAncestorContainer)) {
          rangeToUse = document.createRange();
          rangeToUse.selectNodeContents(editor);
          rangeToUse.collapse(false);
        }
        activeSelection.removeAllRanges();
        activeSelection.addRange(rangeToUse);
        rangeToUse.deleteContents();
        const renderedHtml = markdownToHtml(insertedText);
        const container = document.createElement('div');
        container.innerHTML = renderedHtml;
        const fragment = document.createDocumentFragment();
        let lastInsertedNode: Node | null = null;
        while (container.firstChild) {
          lastInsertedNode = fragment.appendChild(container.firstChild);
        }

        if (lastInsertedNode) {
          rangeToUse.insertNode(fragment);
        } else {
          const textNode = document.createTextNode(insertedText);
          rangeToUse.insertNode(textNode);
          lastInsertedNode = textNode;
        }

        const cursor = document.createRange();
        cursor.setStartAfter(lastInsertedNode);
        cursor.collapse(true);
        activeSelection.removeAllRanges();
        activeSelection.addRange(cursor);
        emitMarkdown();
      })();
    },
    [disabled, emitMarkdown, getDropRange, onDropFiles],
  );

  const containerClassName = useMemo(
    () =>
      `markdown-editor${disabled ? ' markdown-editor--disabled' : ''}${
        isDragOver ? ' markdown-editor--drag-active' : ''
      }${
        className ? ` ${className}` : ''
      }`,
    [className, disabled, isDragOver],
  );

  return (
    <div className={containerClassName}>
      <div className="markdown-editor__toolbar">
        <ToolbarButton
          label="B"
          title="Bold"
          onClick={() => applyInlineWrap('strong', 'bold')}
          disabled={disabled}
        />
        <ToolbarButton
          label="I"
          title="Italic"
          onClick={() => applyInlineWrap('em', 'italic')}
          disabled={disabled}
        />
        <ToolbarButton
          label="• List"
          title="Bullet list"
          onClick={() => applyList(false)}
          disabled={disabled}
        />
        <ToolbarButton
          label="1. List"
          title="Numbered list"
          onClick={() => applyList(true)}
          disabled={disabled}
        />
        <ToolbarButton
          label="Quote"
          title="Quote"
          onClick={applyBlockquote}
          disabled={disabled}
        />
        <ToolbarButton
          label="Code"
          title="Inline code"
          onClick={applyCodeBlock}
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
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        data-placeholder={placeholder}
        data-empty={isEmpty ? 'true' : 'false'}
      />
    </div>
  );
}
