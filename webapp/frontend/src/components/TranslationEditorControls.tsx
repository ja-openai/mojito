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
