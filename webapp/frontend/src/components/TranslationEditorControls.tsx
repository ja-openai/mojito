import './visible-text-editor.css';

import { type ReactNode, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';

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
  text?: string;
  title: string;
  wrap?: readonly [string, string];
};

const TEXT_TOOL_GROUPS: Array<{ label: string; tools: SpecialTextTool[] }> = [
  {
    label: 'Spaces & punctuation',
    tools: [
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
        code: 'WJ',
        label: 'Word joiner',
        text: '\u2060',
        title: 'Prevent a line break without adding a space (U+2060).',
      },
      {
        code: '\u2019',
        label: 'Curly apostrophe',
        text: '\u2019',
        title: 'Insert a typographic apostrophe.',
      },
    ],
  },
  {
    label: 'Direction marks',
    tools: [
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
    ],
  },
  {
    label: 'Phrase direction',
    tools: [
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
    ],
  },
];

function SystemKeyboardHelp() {
  return (
    <div className="visible-text-editor__keyboard-help">
      <p>
        Press <kbd>Esc</kbd> to return to your translation, then use a shortcut.
      </p>
      <section>
        <h4>macOS</h4>
        <dl>
          <div>
            <dt>Characters & emoji</dt>
            <dd>
              <kbd>Ctrl</kbd> <kbd aria-label="Command">⌘</kbd> <kbd>Space</kbd>
            </dd>
          </div>
          <div>
            <dt>Switch input language</dt>
            <dd>
              <kbd>Ctrl</kbd> <kbd>Space</kbd>
            </dd>
          </div>
        </dl>
        <a
          aria-label="Add a keyboard or input method on macOS (opens in a new tab)"
          data-translation-editor-control
          href="https://support.apple.com/guide/mac-help/mchlp1406/mac"
          target="_blank"
          rel="noopener noreferrer"
        >
          Add a keyboard or input method ↗
        </a>
      </section>
      <section>
        <h4>Windows</h4>
        <dl>
          <div>
            <dt>Characters & emoji</dt>
            <dd>
              <kbd>Win</kbd> <kbd>.</kbd>
            </dd>
          </div>
          <div>
            <dt>Switch input language</dt>
            <dd>
              <kbd>Win</kbd> <kbd>Space</kbd>
            </dd>
          </div>
        </dl>
        <a
          aria-label="Add a keyboard or input method on Windows (opens in a new tab)"
          data-translation-editor-control
          href="https://support.microsoft.com/en-us/windows/hardware/input-devices/manage-the-language-and-keyboard-input-layout-settings-in-windows"
          target="_blank"
          rel="noopener noreferrer"
        >
          Add a keyboard or input method ↗
        </a>
      </section>
      <p>
        Use a character picker for symbols, or a language input method (IME) to compose text. Input
        switching requires another keyboard in your system settings.
      </p>
    </div>
  );
}

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
  const panelRef = useRef<HTMLDivElement | null>(null);
  const menuId = useId();
  const helpId = useId();
  const [showKeyboardHelp, setShowKeyboardHelp] = useState(false);

  useLayoutEffect(() => {
    if (!open) return;
    const panel = panelRef.current;
    const control = menuRef.current;
    if (!panel || !control) return;
    panel.scrollTop = 0;
    const positionPanel = () => {
      const padding = 12;
      const gap = 6;
      const anchor = control.getBoundingClientRect();
      const spaceAbove = anchor.top - padding - gap;
      const spaceBelow = window.innerHeight - anchor.bottom - padding - gap;
      panel.style.maxHeight = `${Math.max(0, Math.min(480, Math.max(spaceAbove, spaceBelow)))}px`;
      panel.style.transform = '';
      const rect = panel.getBoundingClientRect();
      const offsetX = Math.max(
        padding - rect.left,
        Math.min(0, window.innerWidth - padding - rect.right),
      );
      const offsetY =
        rect.height > spaceBelow && spaceAbove > spaceBelow
          ? -rect.height - anchor.height - gap * 2
          : 0;
      panel.style.transform = `translate(${offsetX}px, ${offsetY}px)`;
    };
    positionPanel();
    window.addEventListener('resize', positionPanel);
    window.addEventListener('scroll', positionPanel, true);
    return () => {
      window.removeEventListener('resize', positionPanel);
      window.removeEventListener('scroll', positionPanel, true);
    };
  }, [open, showKeyboardHelp]);

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
        aria-controls={menuId}
        aria-expanded={open}
        className="visible-text-editor__control-button"
        data-translation-editor-control
        disabled={disabled}
        onClick={() => {
          setShowKeyboardHelp(false);
          onOpenChange(!open);
        }}
        onMouseDown={(event) => event.preventDefault()}
        title="Insert a character or view system keyboard help"
        type="button"
      >
        Characters
        <span className="visible-text-editor__marks-chevron" aria-hidden="true" />
      </button>
      <div className="visible-text-editor__special-menu" hidden={!open} id={menuId} ref={panelRef}>
        <div hidden={showKeyboardHelp}>
          {TEXT_TOOL_GROUPS.map((group) => (
            <div
              className="visible-text-editor__special-group"
              key={group.label}
              role="group"
              aria-label={group.label}
            >
              <div className="visible-text-editor__special-group-label" aria-hidden="true">
                {group.label}
              </div>
              {group.tools.map((tool) => (
                <button
                  className="visible-text-editor__special-option"
                  data-translation-editor-control
                  disabled={disabled}
                  key={tool.code}
                  onClick={() => {
                    onApplyTextTool(tool);
                    onOpenChange(false);
                  }}
                  onMouseDown={(event) => event.preventDefault()}
                  title={tool.title}
                  type="button"
                >
                  <span>{tool.label}</span>
                  <small aria-hidden="true">{tool.code}</small>
                </button>
              ))}
            </div>
          ))}
        </div>
        <button
          aria-controls={helpId}
          aria-expanded={showKeyboardHelp}
          className="visible-text-editor__keyboard-help-toggle"
          data-translation-editor-control
          disabled={disabled}
          onClick={() => setShowKeyboardHelp(!showKeyboardHelp)}
          onMouseDown={(event) => event.preventDefault()}
          type="button"
        >
          <span>{showKeyboardHelp ? 'Back to characters' : 'System keyboard help'}</span>
          <span aria-hidden="true">{showKeyboardHelp ? '←' : '→'}</span>
        </button>
        <div id={helpId} hidden={!showKeyboardHelp}>
          <SystemKeyboardHelp />
        </div>
      </div>
    </div>
  );
}
