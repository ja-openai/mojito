import './visible-text-editor.css';

import { type ReactNode, useEffect, useRef } from 'react';

import type { VisibleTextMarksMode } from './visibleTextFormatting';

const marksModeOptions: Array<{ label: string; value: VisibleTextMarksMode }> = [
  { value: 'auto', label: 'Auto' },
  { value: 'all', label: 'All' },
  { value: 'off', label: 'Off' },
];

export function TranslationEditorControlBar({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <div
      aria-label="Text editor controls"
      className={`visible-text-editor__control-bar${className ? ` ${className}` : ''}`}
    >
      {children}
    </div>
  );
}

export function HiddenCharactersMenu({
  disabled = false,
  mode,
  onChange,
  onOpenChange,
  onRestoreFocus,
  open,
}: {
  disabled?: boolean;
  mode: VisibleTextMarksMode;
  onChange: (mode: VisibleTextMarksMode) => void;
  onOpenChange: (open: boolean) => void;
  onRestoreFocus?: () => void;
  open: boolean;
}) {
  const menuRef = useRef<HTMLDivElement | null>(null);
  const label = marksModeOptions.find((option) => option.value === mode)?.label ?? 'Auto';

  useEffect(() => {
    if (!open) return;

    const handlePointerDown = (event: PointerEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) {
        onOpenChange(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onOpenChange(false);
        onRestoreFocus?.();
      }
    };

    window.addEventListener('pointerdown', handlePointerDown, true);
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('pointerdown', handlePointerDown, true);
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [onOpenChange, onRestoreFocus, open]);

  useEffect(() => {
    if (disabled && open) onOpenChange(false);
  }, [disabled, onOpenChange, open]);

  return (
    <div className="visible-text-editor__marks-control" ref={menuRef}>
      <button
        data-translation-editor-control
        type="button"
        className="visible-text-editor__marks-button"
        aria-expanded={open}
        aria-haspopup="listbox"
        aria-label={`Hidden characters: ${label}`}
        disabled={disabled}
        onMouseDown={(event) => event.preventDefault()}
        onClick={() => onOpenChange(!open)}
        title="Choose hidden character display"
      >
        <span className="visible-text-editor__marks-label">Hidden chars</span>
        <span className="visible-text-editor__marks-value">{label}</span>
        <span className="visible-text-editor__marks-chevron" aria-hidden="true" />
      </button>
      {open ? (
        <div
          className="visible-text-editor__marks-menu"
          role="listbox"
          aria-label="Hidden characters"
        >
          {marksModeOptions.map((option) => (
            <button
              data-translation-editor-control
              key={option.value}
              type="button"
              className="visible-text-editor__marks-option"
              role="option"
              aria-selected={option.value === mode}
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => {
                onChange(option.value);
                onOpenChange(false);
                onRestoreFocus?.();
              }}
            >
              {option.label}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}

export type SpecialTextTool = {
  code: string;
  label: string;
  shortcut?: string;
  text?: string;
  title: string;
  wrap?: readonly [string, string];
};

const TEXT_TOOLS: Array<SpecialTextTool> = [
  {
    code: 'NBSP',
    label: 'No-break space',
    text: '\u00A0',
    title: 'Insert a space that keeps adjacent words together.',
  },
  {
    code: 'NNBSP',
    label: 'Narrow no-break',
    text: '\u202F',
    title: 'Insert a narrow non-breaking space.',
  },
  {
    code: 'LRM',
    label: 'LTR mark',
    text: '\u200E',
    title: 'Insert a left-to-right mark for nearby punctuation.',
  },
  {
    code: 'RLM',
    label: 'RTL mark',
    text: '\u200F',
    title: 'Insert a right-to-left mark for nearby punctuation.',
  },
  {
    code: 'LRI/PDI',
    label: 'Keep LTR phrase',
    title: 'Wrap the selection as an isolated left-to-right phrase.',
    wrap: ['\u2066', '\u2069'],
  },
  {
    code: 'RLI/PDI',
    label: 'Keep RTL phrase',
    title: 'Wrap the selection as an isolated right-to-left phrase.',
    wrap: ['\u2067', '\u2069'],
  },
  {
    code: 'FSI/PDI',
    label: 'Auto-direction phrase',
    title: 'Wrap the selection and let its first strong character choose direction.',
    wrap: ['\u2068', '\u2069'],
  },
  {
    code: '\u2019',
    label: 'Curly apostrophe',
    shortcut: 'Mac US: \u2325\u21E7]',
    text: '\u2019',
    title: 'Insert a typographic apostrophe. OS shortcuts depend on keyboard layout and IME.',
  },
];

export function SpecialTextTools({
  disabled = false,
  onApplyTextTool,
  onOpenChange,
  onRestoreFocus,
  open,
}: {
  disabled?: boolean;
  onApplyTextTool: (tool: SpecialTextTool) => void;
  onOpenChange: (open: boolean) => void;
  onRestoreFocus: () => void;
  open: boolean;
}) {
  const menuRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return;
    const handlePointerDown = (event: PointerEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) onOpenChange(false);
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      event.preventDefault();
      event.stopPropagation();
      onOpenChange(false);
      onRestoreFocus();
    };
    window.addEventListener('pointerdown', handlePointerDown, true);
    window.addEventListener('keydown', handleKeyDown, true);
    return () => {
      window.removeEventListener('pointerdown', handlePointerDown, true);
      window.removeEventListener('keydown', handleKeyDown, true);
    };
  }, [onOpenChange, onRestoreFocus, open]);

  useEffect(() => {
    if (disabled && open) onOpenChange(false);
  }, [disabled, onOpenChange, open]);

  return (
    <div className="visible-text-editor__special-control" ref={menuRef}>
      <button
        aria-expanded={open}
        className="visible-text-editor__control-button visible-text-editor__special-button"
        data-translation-editor-control
        disabled={disabled}
        onClick={() => onOpenChange(!open)}
        onMouseDown={(event) => event.preventDefault()}
        type="button"
      >
        Insert special
      </button>
      <div className="visible-text-editor__special-menu" hidden={!open}>
        {TEXT_TOOLS.map((tool) => (
          <button
            data-translation-editor-control
            disabled={disabled}
            key={tool.code}
            onClick={() => {
              onApplyTextTool(tool);
              onOpenChange(false);
            }}
            onMouseDown={(event) => event.preventDefault()}
            title={tool.shortcut ? `${tool.title} ${tool.shortcut}` : tool.title}
            type="button"
          >
            <span>{tool.label}</span>
            <small>{tool.code}</small>
          </button>
        ))}
      </div>
    </div>
  );
}
