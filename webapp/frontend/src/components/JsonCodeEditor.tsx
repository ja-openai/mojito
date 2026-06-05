import './json-code-editor.css';

import { redo, redoDepth, undo, undoDepth } from '@codemirror/commands';
import { json } from '@codemirror/lang-json';
import { foldable, foldEffect, unfoldAll } from '@codemirror/language';
import CodeMirror, { type EditorView, keymap } from '@uiw/react-codemirror';
import {
  forwardRef,
  type ReactNode,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';

type JsonCodeEditorProps = {
  value: string;
  ariaLabel: string;
  className?: string;
  placeholder?: string;
  height?: string;
  minHeight?: string;
  maxHeight?: string;
  readOnly?: boolean;
  showHistoryControls?: boolean;
  toolbarActions?: ReactNode;
  toolbarPosition?: 'top' | 'bottom';
  collapseLocaleKeysExcept?: string | null;
  onChange?: (value: string) => void;
  onSave?: () => void;
};

export type JsonCodeEditorHandle = {
  focusAtText: (needle: string, options?: { offset?: number; match?: 'first' | 'last' }) => boolean;
  focus: () => void;
};

const jsonExtensions = [json()];
const basicSetup = {
  autocompletion: true,
  bracketMatching: true,
  closeBrackets: true,
  foldGutter: true,
  highlightActiveLine: true,
  highlightSelectionMatches: true,
  lineNumbers: true,
};

export const JsonCodeEditor = forwardRef<JsonCodeEditorHandle, JsonCodeEditorProps>(
  function JsonCodeEditor(
    {
      value,
      ariaLabel,
      className,
      placeholder,
      height,
      minHeight = '16rem',
      maxHeight,
      readOnly = false,
      showHistoryControls = false,
      toolbarActions,
      toolbarPosition = 'top',
      collapseLocaleKeysExcept,
      onChange,
      onSave,
    },
    ref,
  ) {
    const viewRef = useRef<EditorView | null>(null);
    const previousCollapseLocaleRef = useRef<string | null>(null);
    const [viewReadyCount, setViewReadyCount] = useState(0);
    const [historyState, setHistoryState] = useState({ canUndo: false, canRedo: false });

    const syncHistoryState = useCallback((view: EditorView | null) => {
      if (!view) {
        setHistoryState({ canUndo: false, canRedo: false });
        return;
      }
      setHistoryState((current) => {
        const next = {
          canUndo: undoDepth(view.state) > 0,
          canRedo: redoDepth(view.state) > 0,
        };
        return current.canUndo === next.canUndo && current.canRedo === next.canRedo
          ? current
          : next;
      });
    }, []);

    const runHistoryCommand = useCallback(
      (command: typeof undo) => {
        const view = viewRef.current;
        if (!view) {
          return;
        }
        command(view);
        view.focus();
        syncHistoryState(view);
      },
      [syncHistoryState],
    );

    const extensions = useMemo(() => {
      if (!onSave || readOnly) {
        return jsonExtensions;
      }

      return [
        ...jsonExtensions,
        keymap.of([
          {
            key: 'Mod-s',
            preventDefault: true,
            run: () => {
              onSave();
              return true;
            },
          },
        ]),
      ];
    }, [onSave, readOnly]);

    useImperativeHandle(
      ref,
      () => ({
        focusAtText: (needle, options) => {
          const view = viewRef.current;
          if (!view || !needle) {
            return false;
          }

          const text = view.state.doc.toString();
          const start = options?.match === 'last' ? text.lastIndexOf(needle) : text.indexOf(needle);
          if (start < 0) {
            view.focus();
            return false;
          }

          const offset = options?.offset ?? 0;
          const position = Math.max(0, Math.min(view.state.doc.length, start + offset));
          view.dispatch({
            selection: { anchor: position },
            scrollIntoView: true,
          });
          view.focus();
          return true;
        },
        focus: () => {
          viewRef.current?.focus();
        },
      }),
      [],
    );

    useEffect(() => {
      const view = viewRef.current;
      const sourceLocale = collapseLocaleKeysExcept?.trim();
      const previousSourceLocale = previousCollapseLocaleRef.current;
      previousCollapseLocaleRef.current = sourceLocale || null;
      if (!view) {
        return;
      }

      if (!sourceLocale) {
        if (previousSourceLocale) {
          unfoldAll(view);
        }
        return;
      }

      if (view.hasFocus) {
        return;
      }

      const timeout = window.setTimeout(() => {
        if (view.hasFocus) {
          return;
        }
        collapseLocaleBlocks(view, sourceLocale);
      }, 0);

      return () => window.clearTimeout(timeout);
    }, [collapseLocaleKeysExcept, value, viewReadyCount]);

    const toolbarElement =
      showHistoryControls || toolbarActions ? (
        <div
          className={`json-code-editor__toolbar${toolbarPosition === 'bottom' ? ' is-bottom' : ''}`}
          aria-label={`${ariaLabel} actions`}
        >
          <div className="json-code-editor__toolbar-group">{toolbarActions}</div>
          {showHistoryControls ? (
            <div className="json-code-editor__toolbar-group">
              <button
                type="button"
                className="json-code-editor__history-button"
                onClick={() => runHistoryCommand(undo)}
                disabled={!historyState.canUndo}
              >
                Undo
              </button>
              <button
                type="button"
                className="json-code-editor__history-button"
                onClick={() => runHistoryCommand(redo)}
                disabled={!historyState.canRedo}
              >
                Redo
              </button>
            </div>
          ) : null}
        </div>
      ) : null;

    return (
      <div
        className={`json-code-editor${readOnly ? ' is-readonly' : ''}${
          className ? ` ${className}` : ''
        }`}
      >
        {toolbarPosition === 'top' ? toolbarElement : null}
        <CodeMirror
          aria-label={ariaLabel}
          basicSetup={basicSetup}
          className="json-code-editor__code"
          editable={!readOnly}
          extensions={extensions}
          height={height}
          maxHeight={maxHeight}
          minHeight={minHeight}
          onChange={(nextValue) => {
            onChange?.(nextValue);
          }}
          onCreateEditor={(view) => {
            viewRef.current = view;
            setViewReadyCount((current) => current + 1);
            syncHistoryState(view);
          }}
          onUpdate={(viewUpdate) => {
            syncHistoryState(viewUpdate.view);
          }}
          placeholder={placeholder}
          readOnly={readOnly}
          theme="light"
          value={value}
        />
        {toolbarPosition === 'bottom' ? toolbarElement : null}
      </div>
    );
  },
);

function collapseLocaleBlocks(view: EditorView, sourceLocale: string) {
  const effects = [];
  for (let lineNumber = 1; lineNumber <= view.state.doc.lines; lineNumber += 1) {
    const line = view.state.doc.line(lineNumber);
    const localeKey = line.text.match(/^\s*"([^"]+)"\s*:\s*\{/)?.[1];
    if (!localeKey || localeKey === sourceLocale || !isLocaleLikeKey(localeKey)) {
      continue;
    }

    const range = foldable(view.state, line.from, line.to);
    if (range) {
      effects.push(foldEffect.of(range));
    }
  }

  if (effects.length) {
    view.dispatch({ effects });
  }
}

function isLocaleLikeKey(value: string): boolean {
  return /^[a-z]{2,3}(?:-[A-Za-z0-9]{1,8})?$/.test(value);
}
