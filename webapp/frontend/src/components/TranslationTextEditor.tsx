import {
  type CSSProperties,
  forwardRef,
  type KeyboardEvent as ReactKeyboardEvent,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';

import {
  buildIcuExactPluralOptionInsertion,
  extractIcuProtectedTextTokens,
  getIcuFormInsertions,
  getIcuFormOptions,
  getIcuMovableTextRanges,
  type ProtectedTextDiagnostic,
  type ProtectedTextToken,
} from '../utils/protectedTextTokens';
import { AutoTextarea } from './AutoTextarea';
import {
  VisibleTextEditor,
  type VisibleTextEditorHandle,
  type VisibleTextMarksMode,
} from './VisibleTextEditor';

export type TranslationTextEditorKeyDownEvent =
  | KeyboardEvent
  | ReactKeyboardEvent<HTMLTextAreaElement>;

type ControlBarOptions = {
  icuForms?: boolean;
  marksMode?: VisibleTextMarksMode;
  onChangeMarksMode?: (mode: VisibleTextMarksMode) => void;
  position?: 'bottom' | 'top';
  protectedTokenCount?: number;
};

type Props = {
  assisted: boolean;
  value: string;
  onChange: (nextValue: string) => void;
  marksMode?: VisibleTextMarksMode;
  showInvisibles?: boolean;
  ariaLabel?: string;
  className?: string;
  controlBar?: ControlBarOptions;
  dir?: 'ltr' | 'rtl' | 'auto';
  disabled?: boolean;
  lang?: string;
  maxRows?: number;
  minRows?: number;
  onFocus?: () => void;
  onKeyDown?: (event: TranslationTextEditorKeyDownEvent) => void;
  placeholder?: string;
  protectedDiagnostics?: ProtectedTextDiagnostic[];
  protectedTokens?: ProtectedTextToken[];
  readOnly?: boolean;
  spellCheck?: boolean;
  style?: CSSProperties;
  validateNextValue?: (nextValue: string) => boolean;
};

function getScopedIcuFormItems<T extends { messageEnd: number; messageStart: number }>(
  insertions: T[],
  selection: { start: number; end: number } | null,
): T[] {
  if (!selection) {
    return insertions;
  }

  const caret = selection.start;
  const containingMessageInsertions = insertions.filter(
    (insertion) => caret >= insertion.messageStart && caret < insertion.messageEnd,
  );
  if (containingMessageInsertions.length === 0) {
    return insertions;
  }

  const smallestContainingMessageSize = Math.min(
    ...containingMessageInsertions.map(
      (insertion) => insertion.messageEnd - insertion.messageStart,
    ),
  );
  return containingMessageInsertions.filter(
    (insertion) => insertion.messageEnd - insertion.messageStart === smallestContainingMessageSize,
  );
}

export const TranslationTextEditor = forwardRef<VisibleTextEditorHandle, Props>(
  function TranslationTextEditor(
    {
      assisted,
      value,
      onChange,
      marksMode,
      showInvisibles,
      ariaLabel = 'Text editor',
      className,
      controlBar,
      dir = 'auto',
      disabled = false,
      lang,
      maxRows,
      minRows,
      onFocus,
      onKeyDown,
      placeholder,
      protectedDiagnostics,
      protectedTokens,
      readOnly = false,
      spellCheck = true,
      style,
      validateNextValue,
    },
    forwardedRef,
  ) {
    const visibleEditorRef = useRef<VisibleTextEditorHandle | null>(null);
    const textareaRef = useRef<HTMLTextAreaElement | null>(null);
    const valueRef = useRef(value);
    const onChangeRef = useRef(onChange);
    const [rawMode, setRawMode] = useState(false);
    const [editorSelection, setEditorSelection] = useState<{ start: number; end: number } | null>(
      null,
    );
    const usesVisibleEditor = assisted;
    const hasControlBar = Boolean(controlBar);
    const enableIcuFormControls = Boolean(controlBar && controlBar.icuForms !== false);
    const enableIcuFormMetadata = assisted && !rawMode && !disabled && !readOnly;
    const usesVisibleEditorRef = useRef(usesVisibleEditor);
    const disabledRef = useRef(disabled);
    const readOnlyRef = useRef(readOnly);

    valueRef.current = value;
    onChangeRef.current = onChange;
    usesVisibleEditorRef.current = usesVisibleEditor;
    disabledRef.current = disabled;
    readOnlyRef.current = readOnly;

    useEffect(() => {
      if (!assisted || !hasControlBar) {
        setRawMode(false);
      }
    }, [assisted, hasControlBar]);

    const icuFormOptions = useMemo(
      () => (enableIcuFormMetadata ? getIcuFormOptions(value) : []),
      [enableIcuFormMetadata, value],
    );

    const icuExactFormInsertions = useMemo(
      () =>
        enableIcuFormMetadata && enableIcuFormControls
          ? getIcuFormInsertions(value).filter((insertion) => insertion.kind === 'exact-value')
          : [],
      [enableIcuFormControls, enableIcuFormMetadata, value],
    );

    const scopedIcuFormOptions = useMemo(() => {
      return getScopedIcuFormItems(icuFormOptions, editorSelection);
    }, [editorSelection, icuFormOptions]);

    const scopedIcuExactFormInsertions = useMemo(() => {
      return getScopedIcuFormItems(icuExactFormInsertions, editorSelection);
    }, [editorSelection, icuExactFormInsertions]);

    const movableProtectedRanges = useMemo(
      () => (assisted && !rawMode ? getIcuMovableTextRanges(value) : []),
      [assisted, rawMode, value],
    );
    const resolvedProtectedTokens = useMemo(
      () =>
        protectedTokens ??
        (assisted && !rawMode ? extractIcuProtectedTextTokens(value) : undefined),
      [assisted, protectedTokens, rawMode, value],
    );

    const focusCurrentEditor = (nextUsesVisibleEditor = usesVisibleEditorRef.current) => {
      window.requestAnimationFrame(() => {
        if (nextUsesVisibleEditor) {
          visibleEditorRef.current?.focus();
          return;
        }
        textareaRef.current?.focus();
      });
    };

    const handleAddIcuForm = (insertionId: string, exactValue?: string) => {
      const insertion = icuExactFormInsertions.find((item) => item.id === insertionId);
      if (!insertion) {
        return { ok: false as const, error: 'That ICU form is no longer available.' };
      }

      const resolvedInsertion =
        insertion.kind === 'exact-value'
          ? buildIcuExactPluralOptionInsertion(valueRef.current, insertion, exactValue ?? '')
          : {
              ok: true as const,
              nextValue: insertion.nextValue ?? valueRef.current,
              selectionStart: insertion.selectionStart,
              selectionEnd: insertion.selectionEnd,
            };

      if (!resolvedInsertion.ok) {
        return resolvedInsertion;
      }

      return {
        ok: true as const,
        nextValue: resolvedInsertion.nextValue,
        selectionEnd: resolvedInsertion.selectionEnd,
        selectionStart: resolvedInsertion.selectionStart,
      };
    };

    const handleToggleIcuForm = (optionId: string, checked: boolean) => {
      const option = icuFormOptions.find((item) => item.id === optionId);
      if (!option) {
        return { ok: false as const, error: 'That ICU form is no longer available.' };
      }
      if (option.disabled || option.checked === checked || !option.nextValue) {
        return { ok: false as const, error: `${option.form} cannot be changed here.` };
      }

      return {
        ok: true as const,
        nextValue: option.nextValue,
        selectionEnd: option.selectionEnd,
        selectionStart: option.selectionStart,
      };
    };

    const handleToggleRawMode = () => {
      setRawMode((current) => {
        const nextRawMode = !current;
        focusCurrentEditor(assisted);
        return nextRawMode;
      });
    };

    useImperativeHandle(
      forwardedRef,
      () => ({
        blur() {
          if (usesVisibleEditorRef.current) {
            visibleEditorRef.current?.blur();
            return;
          }
          textareaRef.current?.blur();
        },
        focus() {
          if (usesVisibleEditorRef.current) {
            visibleEditorRef.current?.focus();
            return;
          }
          textareaRef.current?.focus();
        },
        getSelection() {
          if (usesVisibleEditorRef.current) {
            return visibleEditorRef.current?.getSelection() ?? { start: 0, end: 0 };
          }
          const element = textareaRef.current;
          return {
            start: element?.selectionStart ?? valueRef.current.length,
            end: element?.selectionEnd ?? valueRef.current.length,
          };
        },
        insertText(text: string) {
          if (usesVisibleEditorRef.current) {
            visibleEditorRef.current?.insertText(text);
            return;
          }
          const element = textareaRef.current;
          if (!element || disabledRef.current || readOnlyRef.current) {
            return;
          }
          const start = element.selectionStart ?? valueRef.current.length;
          const end = element.selectionEnd ?? valueRef.current.length;
          const nextValue = `${valueRef.current.slice(0, start)}${text}${valueRef.current.slice(
            end,
          )}`;
          onChangeRef.current(nextValue);
          window.requestAnimationFrame(() => {
            element.setSelectionRange(start + text.length, start + text.length);
            element.focus();
          });
        },
        redo() {
          return usesVisibleEditorRef.current ? (visibleEditorRef.current?.redo() ?? false) : false;
        },
        setSelection(selection: { start: number; end: number }) {
          if (usesVisibleEditorRef.current) {
            visibleEditorRef.current?.setSelection(selection);
            return;
          }
          const element = textareaRef.current;
          if (!element) {
            return;
          }
          const start = Math.max(0, Math.min(selection.start, valueRef.current.length));
          const end = Math.max(0, Math.min(selection.end, valueRef.current.length));
          element.setSelectionRange(start, end);
          element.focus();
        },
        undo() {
          return usesVisibleEditorRef.current ? (visibleEditorRef.current?.undo() ?? false) : false;
        },
        wrapSelection(open: string, close: string) {
          if (usesVisibleEditorRef.current) {
            visibleEditorRef.current?.wrapSelection(open, close);
            return;
          }
          const element = textareaRef.current;
          if (!element || disabledRef.current || readOnlyRef.current) {
            return;
          }
          const start = element.selectionStart ?? valueRef.current.length;
          const end = element.selectionEnd ?? valueRef.current.length;
          const selectedText = valueRef.current.slice(start, end);
          const nextValue = `${valueRef.current.slice(0, start)}${open}${selectedText}${close}${valueRef.current.slice(
            end,
          )}`;
          onChangeRef.current(nextValue);
          window.requestAnimationFrame(() => {
            element.setSelectionRange(
              start + open.length,
              start + open.length + selectedText.length,
            );
            element.focus();
          });
        },
      }),
      [],
    );

    if (assisted && !controlBar) {
      return (
        <VisibleTextEditor
          ref={visibleEditorRef}
          ariaLabel={ariaLabel}
          className={className}
          dir={dir}
          disabled={disabled}
          lang={lang}
          onChange={onChange}
          onFocus={onFocus}
          onKeyDown={(event) => onKeyDown?.(event)}
          onSelectionChange={setEditorSelection}
          placeholder={placeholder}
          movableProtectedRanges={movableProtectedRanges}
          protectedDiagnostics={protectedDiagnostics}
          protectedTokens={resolvedProtectedTokens}
          readOnly={readOnly}
          marksMode={marksMode}
          showInvisibles={showInvisibles}
          spellCheck={spellCheck}
          style={style}
          validateNextValue={validateNextValue}
          value={value}
        />
      );
    }

    if (usesVisibleEditor) {
      return (
        <VisibleTextEditor
          ref={visibleEditorRef}
          ariaLabel={ariaLabel}
          className={className}
          controlBar={{
            ...controlBar,
            icuExactFormInsertions: rawMode ? [] : scopedIcuExactFormInsertions,
            icuFormOptions: rawMode ? [] : scopedIcuFormOptions,
            onAddIcuForm: rawMode || !enableIcuFormControls ? undefined : handleAddIcuForm,
            onToggleIcuForm: rawMode || !enableIcuFormControls ? undefined : handleToggleIcuForm,
            onToggleRawMode: handleToggleRawMode,
            rawMode,
          }}
          dir={dir}
          disabled={disabled}
          lang={lang}
          onChange={onChange}
          onFocus={onFocus}
          onKeyDown={(event) => onKeyDown?.(event)}
          onSelectionChange={setEditorSelection}
          placeholder={placeholder}
          movableProtectedRanges={rawMode ? [] : movableProtectedRanges}
          protectedDiagnostics={protectedDiagnostics}
          protectedTokens={rawMode ? [] : resolvedProtectedTokens}
          readOnly={readOnly}
          marksMode={marksMode}
          showInvisibles={showInvisibles}
          spellCheck={spellCheck}
          style={style}
          validateNextValue={rawMode ? undefined : validateNextValue}
          value={value}
        />
      );
    }

    return (
      <AutoTextarea
        ref={textareaRef}
        aria-label={ariaLabel}
        className={className}
        dir={dir}
        disabled={disabled}
        lang={lang}
        maxRows={maxRows}
        minRows={minRows}
        onChange={(event) => onChange(event.target.value)}
        onFocus={onFocus}
        onKeyDown={(event) => onKeyDown?.(event)}
        placeholder={placeholder}
        readOnly={readOnly}
        spellCheck={spellCheck}
        style={style}
        value={value}
      />
    );
  },
);
