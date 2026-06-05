import {
  type CSSProperties,
  forwardRef,
  type KeyboardEvent as ReactKeyboardEvent,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';

import {
  buildIcuExactPluralOptionInsertion,
  getIcuPluralOptionInsertions,
  type ProtectedTextToken,
} from '../utils/protectedTextTokens';
import { AutoTextarea } from './AutoTextarea';
import { VisibleTextEditor, type VisibleTextEditorHandle } from './VisibleTextEditor';

export type TranslationTextEditorKeyDownEvent =
  | KeyboardEvent
  | ReactKeyboardEvent<HTMLTextAreaElement>;

type ControlBarOptions = {
  onToggleInvisibles?: () => void;
  position?: 'bottom' | 'top';
  protectedTokenCount?: number;
  protectedTokenLabel?: string;
};

type Props = {
  assisted: boolean;
  value: string;
  onChange: (nextValue: string) => void;
  showInvisibles: boolean;
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
  protectedTokens?: ProtectedTextToken[];
  readOnly?: boolean;
  spellCheck?: boolean;
  style?: CSSProperties;
  validateNextValue?: (nextValue: string) => boolean;
};

export const TranslationTextEditor = forwardRef<VisibleTextEditorHandle, Props>(
  function TranslationTextEditor(
    {
      assisted,
      value,
      onChange,
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
    const usesVisibleEditorRef = useRef(usesVisibleEditor);
    const disabledRef = useRef(disabled);
    const readOnlyRef = useRef(readOnly);

    valueRef.current = value;
    onChangeRef.current = onChange;
    usesVisibleEditorRef.current = usesVisibleEditor;
    disabledRef.current = disabled;
    readOnlyRef.current = readOnly;

    const icuPluralOptionInsertions = useMemo(
      () =>
        assisted && !rawMode && controlBar && !disabled && !readOnly
          ? getIcuPluralOptionInsertions(value)
          : [],
      [assisted, controlBar, disabled, rawMode, readOnly, value],
    );

    const scopedIcuPluralOptionInsertions = useMemo(() => {
      if (!editorSelection) {
        return icuPluralOptionInsertions;
      }

      const caret = editorSelection.start;
      const containingPluralInsertions = icuPluralOptionInsertions.filter(
        (insertion) => caret >= insertion.pluralStart && caret <= insertion.pluralEnd,
      );
      return containingPluralInsertions.length > 0
        ? containingPluralInsertions
        : icuPluralOptionInsertions;
    }, [editorSelection, icuPluralOptionInsertions]);

    const focusCurrentEditor = (nextUsesVisibleEditor = usesVisibleEditorRef.current) => {
      window.requestAnimationFrame(() => {
        if (nextUsesVisibleEditor) {
          visibleEditorRef.current?.focus();
          return;
        }
        textareaRef.current?.focus();
      });
    };

    const handleAddIcuPluralOption = (insertionId: string, exactValue?: string) => {
      const insertion = icuPluralOptionInsertions.find((item) => item.id === insertionId);
      if (!insertion) {
        return { ok: false as const, error: 'That plural form is no longer available.' };
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

      onChange(resolvedInsertion.nextValue);
      window.requestAnimationFrame(() => {
        visibleEditorRef.current?.setSelection({
          start: resolvedInsertion.selectionStart,
          end: resolvedInsertion.selectionEnd,
        });
        visibleEditorRef.current?.focus();
      });
      return { ok: true as const };
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
          protectedTokens={protectedTokens}
          readOnly={readOnly}
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
            icuPluralOptionInsertions: rawMode ? [] : scopedIcuPluralOptionInsertions,
            onAddIcuPluralOption: rawMode ? undefined : handleAddIcuPluralOption,
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
          protectedTokens={rawMode ? [] : protectedTokens}
          readOnly={readOnly}
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
