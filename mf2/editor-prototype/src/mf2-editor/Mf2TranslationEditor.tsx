import {
  type ReactNode,
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";

import {
  addLocalePluralRows,
  cloneModel,
  diagnosticsFor,
  formLabel,
  invalidLocalePluralRows,
  localePluralRowSuggestions,
  lineCount,
  localeDirection,
  missingPlaceholderNamesForActiveSource,
  parseMf2,
  patternPreview,
  patternWithRestoredPlaceholders,
  placeholderInsertionNamesForActiveSource,
  placeholderSourceByName,
  placeholderSourcesInPattern,
  printModel,
  removeInvalidLocalePluralRows,
  sourceInputContractItems,
  sourceFormLabelForTargetModel,
  sourceVariantForTargetModel,
  type EditorDiagnostic,
  type EditorModel,
  type EditorVariant,
  type InvalidLocalePluralRow,
} from "./model";
import {
  diagnosticRenderKey,
  diagnosticsForForm,
  diagnosticsNearActiveEditor,
  issueSeverity,
} from "./diagnostics";
import { formNavigationIntent, formRowActivationIntent } from "./keyboard";
import {
  RawMf2CodeMirror,
  type RawDocumentCommand,
  type RawTextTool,
  type RawTextToolCommand,
} from "./RawMf2CodeMirror";
import {
  Mf2ProseMirrorEditor,
  type Mf2ProseMirrorEditorHandle,
} from "./Mf2ProseMirrorEditor";

export type Mf2TranslationEditorSnapshot = {
  diagnostics: Array<EditorDiagnostic>;
  locale: string;
  mode: Mf2EditorMode;
  target: string;
};

export type Mf2EditorMode = "rich" | "raw";

export type Mf2LocaleOption = {
  label: string;
  value: string;
};

export type Mf2TranslationEditorProps = {
  args?: Record<string, unknown>;
  className?: string;
  documentKey?: string;
  initialMode?: Mf2EditorMode;
  initialTarget?: string;
  locale?: string;
  localeOptions?: Array<Mf2LocaleOption>;
  mode?: Mf2EditorMode;
  onChange?: (snapshot: Mf2TranslationEditorSnapshot) => void;
  onLocaleChange?: (locale: string) => void;
  onModeChange?: (mode: Mf2EditorMode) => void;
  onTargetChange?: (target: string) => void;
  readOnly?: boolean;
  showArgumentInputs?: boolean;
  showActiveSourceComparison?: boolean;
  showDebugTools?: boolean;
  showPreview?: boolean;
  showSource?: boolean;
  source: string;
  target?: string;
};

type LastValidTargetModel = {
  model: EditorModel;
  source: string;
};

type TextTool = RawTextTool & {
  code: string;
  label: string;
  shortcut?: string;
  title: string;
};

const DEFAULT_LOCALE_OPTIONS: Array<Mf2LocaleOption> = [
  { label: "English", value: "en" },
  { label: "French", value: "fr" },
  { label: "German", value: "de" },
  { label: "Russian", value: "ru" },
  { label: "Arabic", value: "ar" },
  { label: "Japanese", value: "ja" },
];

const EMPTY_ARGS: Record<string, unknown> = {};
const TEXT_TOOLS: Array<TextTool> = [
  { code: "NBSP", label: "No-break space", text: "\u00A0", title: "Insert a space that keeps adjacent words together." },
  { code: "NNBSP", label: "Narrow no-break", text: "\u202F", title: "Insert a narrow non-breaking space." },
  { code: "LRM", label: "LTR mark", text: "\u200E", title: "Insert a left-to-right mark for nearby punctuation." },
  { code: "RLM", label: "RTL mark", text: "\u200F", title: "Insert a right-to-left mark for nearby punctuation." },
  { code: "LRI/PDI", label: "Keep LTR phrase", title: "Wrap the selection as an isolated left-to-right phrase.", wrap: ["\u2066", "\u2069"] },
  { code: "RLI/PDI", label: "Keep RTL phrase", title: "Wrap the selection as an isolated right-to-left phrase.", wrap: ["\u2067", "\u2069"] },
  { code: "FSI/PDI", label: "Auto-direction phrase", title: "Wrap the selection and let its first strong character choose direction.", wrap: ["\u2068", "\u2069"] },
  {
    code: "\u2019",
    label: "Curly apostrophe",
    shortcut: "Mac US: \u2325\u21E7]",
    text: "\u2019",
    title: "Insert a typographic apostrophe. OS shortcuts depend on keyboard layout and IME.",
  },
];

export function Mf2TranslationEditor({
  args = EMPTY_ARGS,
  className,
  documentKey,
  initialMode = "rich",
  initialTarget,
  locale: controlledLocale,
  localeOptions,
  mode: controlledMode,
  onChange,
  onLocaleChange,
  onModeChange,
  onTargetChange,
  readOnly = false,
  showActiveSourceComparison = false,
  showArgumentInputs = true,
  showDebugTools = false,
  showPreview = true,
  showSource = true,
  source,
  target: controlledTarget,
}: Mf2TranslationEditorProps) {
  const [activeVariant, setActiveVariant] = useState(0);
  const [debug, setDebug] = useState(false);
  const [draftLocale, setDraftLocale] = useState(controlledLocale ?? "en");
  const [draftMode, setDraftMode] = useState<Mf2EditorMode>(initialMode);
  const [draftTarget, setDraftTarget] = useState(initialTarget ?? source);
  const [argValues, setArgValues] = useState<Record<string, unknown>>(args);
  const [rawReplaceCommand, setRawReplaceCommand] = useState<RawDocumentCommand | null>(null);
  const [rawTextToolCommand, setRawTextToolCommand] = useState<RawTextToolCommand | null>(null);
  const editorId = useId();
  const proseMirrorRef = useRef<Mf2ProseMirrorEditorHandle | null>(null);
  const focusAfterVariantMoveRef = useRef(false);
  const lastSnapshotSignatureRef = useRef<string | null>(null);
  const lastValidTargetModelRef = useRef<LastValidTargetModel | null>(null);
  const onChangeRef = useRef(onChange);
  const rawReplaceCommandIdRef = useRef(0);
  const rawTextToolCommandIdRef = useRef(0);
  const mode = controlledMode ?? draftMode;
  const modeIsControlled = controlledMode !== undefined;
  const locale = controlledLocale ?? draftLocale;
  const localeIsControlled = controlledLocale !== undefined;
  const target = controlledTarget ?? draftTarget;
  const targetIsControlled = controlledTarget !== undefined;

  const sourceParsed = useMemo(() => parseMf2(source, argValues, locale), [argValues, locale, source]);
  const targetParsed = useMemo(() => parseMf2(target, argValues, locale), [argValues, locale, target]);
  const sourceModel = sourceParsed.model;
  const targetModel = targetParsed.model;
  const richEditorCanMutate = !readOnly && Boolean(targetModel);
  const lastValidTargetModel = lastValidTargetModelRef.current?.source === source
    ? lastValidTargetModelRef.current.model
    : null;
  const editableModel = targetModel ?? lastValidTargetModel ?? sourceModel;
  const diagnostics = useMemo(
    () => diagnosticsFor(sourceModel, targetModel, targetParsed.diagnostics, locale),
    [locale, sourceModel, targetModel, targetParsed.diagnostics],
  );
  const variants = activePatterns(editableModel);
  const activeVariantIndex = variants.length ? Math.min(activeVariant, variants.length - 1) : 0;
  const activeTargetKeys = variants[activeVariantIndex]?.keys ?? [];
  const activePattern = variants[activeVariantIndex]?.value ?? "";
  const activeFormLabel = formLabel(activeTargetKeys, selectorsForModel(editableModel));
  const selectors = editableModel?.type === "select" ? editableModel.selectors : [];
  const sourcePlaceholderSources = useMemo(() => placeholderSourceByName(sourceModel), [sourceModel]);
  const nextFormShortcut = useMemo(() => platformNextFormShortcut(), []);
  const rawRedoShortcut = useMemo(() => platformRedoShortcut(), []);
  const rawUndoShortcut = useMemo(() => platformUndoShortcut(), []);
  const localeRowSuggestions = useMemo(
    () => localePluralRowSuggestions(targetModel, locale),
    [locale, targetModel],
  );
  const invalidLocaleRows = useMemo(
    () => invalidLocalePluralRows(targetModel, locale),
    [locale, targetModel],
  );
  const activeSourceVariant = sourceVariantForTargetModel(sourceModel, editableModel, activeTargetKeys);
  const activeSourceModel = activeSourceVariant ? sourceModel : null;
  const activeSourcePattern = activeSourceVariant?.value ?? "";
  const activeSourcePlaceholders = useMemo(
    () => placeholderInsertionNamesForActiveSource(activeSourceModel, activeSourcePattern),
    [activeSourceModel, activeSourcePattern],
  );
  const activeSourcePlaceholderSources = useMemo(() => placeholderSourcesInPattern(activeSourcePattern), [activeSourcePattern]);
  const activeSourceCompletionSources = useMemo(
    () => ({ ...sourcePlaceholderSources, ...activeSourcePlaceholderSources }),
    [activeSourcePlaceholderSources, sourcePlaceholderSources],
  );
  const activeSourceFormLabel = sourceFormLabelForTargetModel(sourceModel, editableModel, activeTargetKeys);
  const activeEditorMinLines = Math.max(1, lineCount(activePattern), lineCount(activeSourcePattern));
  const sourceContractText = useMemo(() => sourceInputContractItems(sourceModel).join(" ") || "No source inputs", [sourceModel]);
  const shortcutsId = `${editorId}-shortcuts`;
  const snapshotSignature = useMemo(
    () => editorSnapshotSignature(target, diagnostics, locale, mode),
    [diagnostics, locale, mode, target],
  );
  const direction = localeDirection(locale);
  const effectiveLocaleOptions = useMemo(() => {
    return localeOptionsWithCurrent(localeOptions?.length ? localeOptions : DEFAULT_LOCALE_OPTIONS, locale);
  }, [locale, localeOptions]);

  useLayoutEffect(() => {
    if (!modeIsControlled) setDraftMode(initialMode);
  }, [initialMode, modeIsControlled]);

  useLayoutEffect(() => {
    if (!targetIsControlled) setDraftTarget(initialTarget ?? source);
    setActiveVariant(0);
    lastSnapshotSignatureRef.current = null;
    lastValidTargetModelRef.current = null;
    setRawReplaceCommand(null);
    setRawTextToolCommand(null);
  }, [documentKey, initialTarget, source, targetIsControlled]);

  useLayoutEffect(() => {
    setArgValues((current) => (sameRecordValues(current, args) ? current : args));
  }, [args]);

  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  useEffect(() => {
    if (lastSnapshotSignatureRef.current === snapshotSignature) return;
    lastSnapshotSignatureRef.current = snapshotSignature;
    onChangeRef.current?.({ diagnostics, locale, mode, target });
  }, [diagnostics, locale, mode, snapshotSignature, target]);

  useEffect(() => {
    if (targetModel) lastValidTargetModelRef.current = { model: targetModel, source };
  }, [source, targetModel]);

  useEffect(() => {
    setActiveVariant((current) => Math.min(current, Math.max(0, variants.length - 1)));
  }, [variants.length]);

  useLayoutEffect(() => {
    if (!focusAfterVariantMoveRef.current || mode !== "rich") return;
    focusAfterVariantMoveRef.current = false;
    proseMirrorRef.current?.focus();
  }, [activeVariantIndex, mode]);

  const closeCompletion = useCallback(() => {}, []);

  const setEditorTarget = useCallback((nextTarget: string) => {
    if (readOnly) return;
    if (!targetIsControlled) setDraftTarget(nextTarget);
    onTargetChange?.(nextTarget);
  }, [onTargetChange, readOnly, targetIsControlled]);

  const updateActivePattern = useCallback((value: string) => {
    if (readOnly || !targetModel) {
      closeCompletion();
      return;
    }
    const next = cloneModel(targetModel);
    if (next.type === "select") {
      const variant = next.variants[activeVariantIndex];
      if (!variant) return;
      variant.value = value;
    } else {
      next.pattern = value;
    }
    const nextTarget = printModel(next);
    if (nextTarget !== target) setEditorTarget(nextTarget);
    closeCompletion();
  }, [activeVariantIndex, closeCompletion, readOnly, setEditorTarget, target, targetModel]);

  const moveActiveForm = useCallback((delta: number, focusEditor = true) => {
    const count = activePatterns(editableModel).length;
    if (count <= 1) {
      closeCompletion();
      return false;
    }
    focusAfterVariantMoveRef.current = focusEditor;
    setActiveVariant((current) => (current + delta + count) % count);
    closeCompletion();
    return true;
  }, [closeCompletion, editableModel]);

  const placeholderSourceForName = useCallback((name: string) => {
    return activeSourcePlaceholderSources[name] ?? sourcePlaceholderSources[name] ?? `{$${name}}`;
  }, [activeSourcePlaceholderSources, sourcePlaceholderSources]);

  function applyTextTool(tool: TextTool) {
    if (!richEditorCanMutate) return;
    proseMirrorRef.current?.applyTextTool(tool);
    proseMirrorRef.current?.focus();
  }

  function applyRawTextTool(tool: TextTool) {
    if (readOnly) return;
    rawTextToolCommandIdRef.current += 1;
    setRawTextToolCommand({ id: rawTextToolCommandIdRef.current, tool });
  }

  function updateRawTarget(value: string) {
    if (readOnly) return;
    setEditorTarget(value);
    closeCompletion();
  }

  function replaceTargetModel(nextModel: EditorModel) {
    const nextTarget = printModel(nextModel);
    if (mode === "raw") {
      rawReplaceCommandIdRef.current += 1;
      setRawReplaceCommand({ id: rawReplaceCommandIdRef.current, value: nextTarget });
      return;
    }
    setEditorTarget(nextTarget);
  }

  function selectMode(nextMode: Mf2EditorMode) {
    if (nextMode === mode) return;
    if (!modeIsControlled) setDraftMode(nextMode);
    onModeChange?.(nextMode);
    closeCompletion();
  }

  function selectLocale(nextLocale: string) {
    if (nextLocale === locale) return;
    if (!localeIsControlled) setDraftLocale(nextLocale);
    onLocaleChange?.(nextLocale);
    closeCompletion();
  }

  function selectVariant(index: number, focusEditor = false) {
    setActiveVariant(index);
    focusAfterVariantMoveRef.current = focusEditor;
    closeCompletion();
  }

  function addSuggestedLocaleRows() {
    if (readOnly) return;
    const result = addLocalePluralRows(targetModel, localeRowSuggestions, sourceModel);
    if (!result) return;
    replaceTargetModel(result.model);
    setActiveVariant(result.activeIndex);
    focusAfterVariantMoveRef.current = true;
    closeCompletion();
  }

  function removeBlankInvalidLocaleRows() {
    if (readOnly) return;
    const result = removeInvalidLocalePluralRows(targetModel, invalidLocaleRows);
    if (!result) return;
    replaceTargetModel(result.model);
    setActiveVariant(result.activeIndex);
    focusAfterVariantMoveRef.current = true;
    closeCompletion();
  }

  function restoreRawMissingPlaceholders() {
    if (readOnly || !targetModel || !missing.length) return;
    const next = cloneModel(targetModel);
    if (next.type === "select") {
      const variant = next.variants[activeVariantIndex];
      if (!variant) return;
      variant.value = patternWithRestoredPlaceholders(variant.value, missing, placeholderSourceForName);
    } else {
      next.pattern = patternWithRestoredPlaceholders(next.pattern, missing, placeholderSourceForName);
    }
    replaceTargetModel(next);
    closeCompletion();
  }

  const missing = missingPlaceholderNamesForActiveSource(
    activeSourceModel,
    targetModel,
    activeSourcePattern,
    activePattern,
  );
  const inlineDiagnostics = diagnosticsNearActiveEditor(diagnostics, activeFormLabel, mode);

  return (
    <section
      className={classNames("mf2-inline-editor", className)}
      data-debug={String(debug)}
      data-mode={mode}
      data-readonly={String(readOnly)}
      data-target-direction={direction}
    >
      <header className="mf2-inline-toolbar">
        <label>
          <span>Target language</span>
          <select value={locale} onChange={(event) => selectLocale(event.currentTarget.value)}>
            {effectiveLocaleOptions.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </label>
        <div className="mf2-segmented">
          <button type="button" aria-pressed={mode === "rich"} onClick={() => selectMode("rich")}>Edit</button>
          <button type="button" aria-pressed={mode === "raw"} onClick={() => selectMode("raw")}>Raw</button>
        </div>
        {showDebugTools ? (
          <button type="button" data-debug aria-pressed={debug} onClick={() => setDebug((value) => !value)}>
            {debug ? "Hide debug" : "Debug"}
          </button>
        ) : null}
      </header>

      {showSource ? (
        <div className="mf2-source-prose">
          <div>
            <span>Source</span>
            <strong>{activeSourceFormLabel}</strong>
          </div>
          <p dir="auto">{activeSourcePattern}</p>
        </div>
      ) : null}

      {mode === "rich" ? (
        <div className="mf2-edit-row">
          <div className="mf2-forms">
            <div className="mf2-form-meta-row">
              <SourceContract text={sourceContractText} />
            </div>
            {variants.map((variant, index) => {
              const label = formLabel(variant.keys, selectors);
              const formDiagnostics = diagnosticsForForm(diagnostics, label);
              const formSeverity = issueSeverity(formDiagnostics);
              return (
                <div
                  aria-current={index === activeVariantIndex ? "true" : undefined}
                  className={`mf2-form-row ${index === activeVariantIndex ? "is-active" : ""} ${formSeverity ? `has-${formSeverity}` : ""}`}
                  data-form={index}
                  key={`${variant.keys.join("\u001F")}:${index}`}
                  onClick={() => index !== activeVariantIndex && selectVariant(index)}
                  onKeyDown={(event) => {
                    if (index === activeVariantIndex) return;
                    const navigation = formNavigationIntent(event);
                    if (navigation) {
                      event.preventDefault();
                      moveActiveForm(navigation.direction);
                      return;
                    }
                    if (formRowActivationIntent(event)) {
                      event.preventDefault();
                      selectVariant(index, true);
                    }
                  }}
                  role={index === activeVariantIndex ? undefined : "button"}
                  tabIndex={index === activeVariantIndex ? undefined : 0}
                >
                  <span className="mf2-form-key">
                    <span>{label}</span>
                    <FormIssueBadge diagnostics={formDiagnostics} />
                  </span>
                  {index === activeVariantIndex ? (
                    <div className="mf2-form-editor-cell">
                      {showActiveSourceComparison && activeSourcePattern ? (
                        <ActiveSourceComparison
                          label={activeSourceFormLabel}
                          value={activeSourcePattern}
                        />
                      ) : null}
                      <div className="mf2-form-editor-slot">
                        <Mf2ProseMirrorEditor
                          ariaLabel={`Target ${label}`}
                          describedBy={richEditorCanMutate ? shortcutsId : undefined}
                          direction={direction}
                          minLines={activeEditorMinLines}
                          onChange={updateActivePattern}
                          onNextForm={() => moveActiveForm(1)}
                          onPreviousForm={() => moveActiveForm(-1)}
                          pattern={activePattern}
                          placeholderSources={activeSourceCompletionSources}
                          placeholders={activeSourcePlaceholders}
                          readOnly={!richEditorCanMutate}
                          ref={proseMirrorRef}
                        />
                      </div>
                      {richEditorCanMutate ? (
                        <ShortcutStrip
                          completionHasMatches={activeSourcePlaceholders.length > 0}
                          completionOpen={false}
                          id={shortcutsId}
                          missing={missing}
                          nextFormShortcut={nextFormShortcut}
                          onApplyTextTool={applyTextTool}
                          onRestoreMissing={() => {
                            if (!richEditorCanMutate) return;
                            proseMirrorRef.current?.restoreMissingPlaceholders(missing);
                            proseMirrorRef.current?.focus();
                          }}
                        />
                      ) : null}
                      <InlineDiagnostics diagnostics={inlineDiagnostics} totalCount={diagnostics.length} />
                    </div>
                  ) : (
                    <div className="mf2-form-preview">{patternPreview(variant.value)}</div>
                  )}
                </div>
              );
            })}
            {richEditorCanMutate ? (
              <RowHelper
                invalidRows={invalidLocaleRows}
                onAddLocaleRows={addSuggestedLocaleRows}
                onRemoveInvalidRows={removeBlankInvalidLocaleRows}
                suggestions={localeRowSuggestions}
              />
            ) : null}
          </div>
        </div>
      ) : null}

      {mode === "raw" && showActiveSourceComparison && activeSourcePattern ? (
        <ActiveSourceComparison
          label={activeSourceFormLabel}
          value={activeSourcePattern}
        />
      ) : null}
      {mode === "raw" ? (
        <div className="mf2-form-meta-row mf2-raw-contract-row">
          <SourceContract text={sourceContractText} />
        </div>
      ) : null}
      <RawMf2CodeMirror
        active={mode === "raw"}
        ariaLabel={`Raw target MF2 ${activeFormLabel}`}
        className="mf2-raw"
        key={documentKey ?? source}
        onChange={updateRawTarget}
        onNextForm={() => moveActiveForm(1, false)}
        onPreviousForm={() => moveActiveForm(-1, false)}
        placeholders={activeSourcePlaceholders}
        placeholderSources={activeSourceCompletionSources}
        readOnly={readOnly}
        replaceCommand={rawReplaceCommand}
        textToolCommand={rawTextToolCommand}
        value={target}
      />
      {mode === "raw" && !readOnly ? (
        <RawShortcutStrip
          missing={targetModel ? missing : []}
          nextFormShortcut={nextFormShortcut}
          onApplyTextTool={applyRawTextTool}
          onRestoreMissing={restoreRawMissingPlaceholders}
          redoShortcut={rawRedoShortcut}
          undoShortcut={rawUndoShortcut}
        />
      ) : null}
      {mode === "raw" && !readOnly ? (
        <RowHelper
          invalidRows={invalidLocaleRows}
          onAddLocaleRows={addSuggestedLocaleRows}
          onRemoveInvalidRows={removeBlankInvalidLocaleRows}
          suggestions={localeRowSuggestions}
        />
      ) : null}
      {mode === "raw" ? <InlineDiagnostics diagnostics={inlineDiagnostics} totalCount={diagnostics.length} /> : null}

      <div className="mf2-diagnostics">
        {diagnostics.length ? diagnostics.map((diagnostic, index) => (
          <span className={`mf2-issue mf2-issue-${diagnostic.severity}`} key={diagnosticRenderKey(diagnostic, index)}>
            <strong>{diagnostic.code}</strong> {diagnostic.message}
          </span>
        )) : <span className="mf2-ok">No parser or contract issues.</span>}
      </div>

      {showPreview ? (
        <div className={`mf2-preview-row ${showArgumentInputs ? "" : "mf2-preview-row-single"}`}>
          <output dir={direction}>{targetParsed.output}</output>
          {showArgumentInputs ? (
            <div className="mf2-args">
              {Object.keys(argValues).sort().map((name) => (
                <label key={name}>
                  <span>{name}</span>
                  <input
                    value={String(argValues[name] ?? "")}
                    onChange={(event) => setArgValues((current) => ({ ...current, [name]: event.currentTarget.value }))}
                  />
                </label>
              ))}
            </div>
          ) : null}
        </div>
      ) : null}

      {showDebugTools ? (
        <details className="mf2-debug" open={debug}>
          <summary>Parts / model</summary>
          <pre>{JSON.stringify({ model: targetModel?.rustModel ?? null, output: targetParsed.output, parts: targetParsed.parts }, null, 2)}</pre>
        </details>
      ) : null}
    </section>
  );
}

function SourceContract({ text }: { text: string }) {
  return (
    <div className="mf2-form-contract">
      <span>Source contract</span>
      <code>{text}</code>
    </div>
  );
}

function ActiveSourceComparison({ label, value }: { label: string; value: string }) {
  return (
    <div className="mf2-active-source-comparison">
      <span>Source</span>
      <strong>{label}</strong>
      <p dir="auto">{value}</p>
    </div>
  );
}

function FormIssueBadge({ diagnostics }: { diagnostics: Array<EditorDiagnostic> }) {
  if (!diagnostics.length) return null;
  const errors = diagnostics.filter((diagnostic) => diagnostic.severity === "error").length;
  const warnings = diagnostics.length - errors;
  const label = errors && warnings
    ? `${errors}e ${warnings}w`
    : errors
      ? `${errors} error${errors === 1 ? "" : "s"}`
      : `${warnings} warning${warnings === 1 ? "" : "s"}`;
  return <span className={`mf2-form-issue-badge mf2-form-issue-badge-${errors ? "error" : "warning"}`}>{label}</span>;
}

function InlineDiagnostics({
  diagnostics,
  totalCount,
}: {
  diagnostics: Array<EditorDiagnostic>;
  totalCount: number;
}) {
  if (!diagnostics.length) return null;
  const hiddenCount = Math.max(0, totalCount - diagnostics.length);
  return (
    <div className="mf2-inline-diagnostics" aria-live="polite">
      {diagnostics.map((diagnostic, index) => (
        <span className={`mf2-inline-issue mf2-inline-issue-${diagnostic.severity}`} key={diagnosticRenderKey(diagnostic, index)}>
          <strong>{diagnostic.code}</strong> {diagnostic.message}
        </span>
      ))}
      {hiddenCount ? <span className="mf2-inline-issue-more">+{hiddenCount} more below</span> : null}
    </div>
  );
}

function RawShortcutStrip({
  missing,
  nextFormShortcut,
  onApplyTextTool,
  onRestoreMissing,
  redoShortcut,
  undoShortcut,
}: {
  missing: Array<string>;
  nextFormShortcut: string;
  onApplyTextTool: (tool: TextTool) => void;
  onRestoreMissing: () => void;
  redoShortcut: string;
  undoShortcut: string;
}) {
  return (
    <div className="mf2-shortcuts mf2-raw-shortcuts">
      <ShortcutKey keys="{ or $" label="placeholder menu" />
      <ShortcutKey keys={undoShortcut} label="undo" />
      <ShortcutKey keys={redoShortcut} label="redo" />
      <ShortcutKey keys="Shift+Down" label="next form" />
      <ShortcutKey keys="Shift+Up" label="previous form" />
      <ShortcutKey keys={nextFormShortcut} label="next form" />
      <SpecialTextTools onApplyTextTool={onApplyTextTool} />
      <RestoreMissingButton missing={missing} onRestoreMissing={onRestoreMissing} />
      <span className="mf2-shortcut-note">Parser errors are highlighted inline.</span>
    </div>
  );
}

function RowHelper({
  invalidRows,
  onAddLocaleRows,
  onRemoveInvalidRows,
  suggestions,
}: {
  invalidRows: Array<InvalidLocalePluralRow>;
  onAddLocaleRows: () => void;
  onRemoveInvalidRows: () => void;
  suggestions: ReturnType<typeof localePluralRowSuggestions>;
}) {
  if (!suggestions.length && !invalidRows.length) return null;
  const suggestionSummaryText = suggestionSummary(suggestions);
  const removableInvalidRows = invalidRows.filter((row) => row.removable);
  const removableInvalidFormCount = uniqueInvalidRowIndexes(removableInvalidRows).length;
  const invalidSummary = rowSummary(invalidRows);
  const removableInvalidSummary = rowSummary(removableInvalidRows);
  const removeInvalidRowsLabel = rowHelperRemoveLabel(removableInvalidFormCount, removableInvalidSummary);
  const addLocaleRowsLabel = rowHelperAddLabel(suggestions.length, suggestionSummaryText);
  return (
    <div className="mf2-row-helper">
      {invalidRows.length ? (
        <span>
          {removableInvalidRows.length ? "Remove blank forms invalid for this locale" : "Review forms invalid for this locale"}
          {invalidSummary ? <>: <strong>{invalidSummary}</strong></> : null}
        </span>
      ) : null}
      {removableInvalidFormCount ? (
        <button
          aria-label={removeInvalidRowsLabel}
          type="button"
          onClick={onRemoveInvalidRows}
        >
          Remove {removableInvalidFormCount}
        </button>
      ) : null}
      {suggestions.length ? (
        <>
          <span>
            Add target-locale forms: <strong>{suggestionSummaryText}</strong>
          </span>
          <button
            aria-label={addLocaleRowsLabel}
            type="button"
            onClick={onAddLocaleRows}
          >
            Add {suggestions.length} row{suggestions.length === 1 ? "" : "s"}
          </button>
        </>
      ) : null}
    </div>
  );
}

function rowHelperRemoveLabel(count: number, summary: string) {
  const suffix = summary ? `: ${summary}` : "";
  return `Remove ${count} blank target-locale form${count === 1 ? "" : "s"}${suffix}`;
}

function rowHelperAddLabel(count: number, summary: string) {
  return `Add ${count} target-locale form${count === 1 ? "" : "s"}: ${summary}`;
}

function uniqueInvalidRowIndexes(rows: Array<InvalidLocalePluralRow>) {
  return [...new Set(rows.map((row) => row.index))];
}

function suggestionSummary(suggestions: ReturnType<typeof localePluralRowSuggestions>) {
  return categorySummary(suggestions);
}

function categorySummary(rows: Array<{ category: string; selector: string }>) {
  const bySelector = new Map<string, Array<string>>();
  for (const row of rows) {
    const categories = bySelector.get(row.selector) ?? [];
    if (!categories.includes(row.category)) categories.push(row.category);
    bySelector.set(row.selector, categories);
  }
  return [...bySelector.entries()]
    .map(([selector, categories]) => `$${selector}: ${categories.join(", ")}`)
    .join("; ");
}

function rowSummary(rows: Array<InvalidLocalePluralRow>) {
  const seen = new Set<string>();
  const uniqueRows = rows
    .filter((row) => {
      const key = `${row.selector}:${row.category}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  return categorySummary(uniqueRows);
}

function ShortcutStrip({
  completionHasMatches,
  completionOpen,
  id,
  missing,
  nextFormShortcut,
  onApplyTextTool,
  onRestoreMissing,
}: {
  completionHasMatches: boolean;
  completionOpen: boolean;
  id: string;
  missing: Array<string>;
  nextFormShortcut: string;
  onApplyTextTool: (tool: TextTool) => void;
  onRestoreMissing: () => void;
}) {
  return (
    <div className="mf2-shortcuts" id={id}>
      <ShortcutKey keys="{ or $" label="placeholder menu" />
      {completionOpen ? (
        <>
          {completionHasMatches ? (
            <>
              <ShortcutKey keys="Enter" label="accept" />
              <ShortcutKey keys="Up/Down" label="choose" />
            </>
          ) : null}
          <ShortcutKey keys="Esc" label="close" />
        </>
      ) : null}
      <ShortcutKey keys="Shift+Down" label="next form" />
      <ShortcutKey keys="Shift+Up" label="previous form" />
      <ShortcutKey keys={nextFormShortcut} label="next form" />
      <SpecialTextTools onApplyTextTool={onApplyTextTool} />
      <RestoreMissingButton missing={missing} onRestoreMissing={onRestoreMissing} />
    </div>
  );
}

function RestoreMissingButton({
  missing,
  onRestoreMissing,
}: {
  missing: Array<string>;
  onRestoreMissing: () => void;
}) {
  if (!missing.length) return null;
  return (
    <button
      className="mf2-shortcut-action"
      onClick={onRestoreMissing}
      onMouseDown={(event) => event.preventDefault()}
      type="button"
    >
      Restore {placeholderRestoreLabel(missing)}
    </button>
  );
}

function SpecialTextTools({ onApplyTextTool }: { onApplyTextTool: (tool: TextTool) => void }) {
  return (
    <details className="mf2-shortcut-special">
      <summary onMouseDown={(event) => event.preventDefault()}>Insert special</summary>
      <div className="mf2-text-tool-grid">
        {TEXT_TOOLS.map((tool) => (
          <button
            key={tool.code}
            onClick={(event) => {
              onApplyTextTool(tool);
              event.currentTarget.closest("details")?.removeAttribute("open");
            }}
            onMouseDown={(event) => event.preventDefault()}
            title={tool.title}
            type="button"
          >
            <span>{tool.label}</span>
            <small>{tool.shortcut ? `${tool.code} · ${tool.shortcut}` : tool.code}</small>
          </button>
        ))}
      </div>
    </details>
  );
}

function ShortcutKey({ keys, label }: { keys: string; label: string }) {
  return (
    <span className="mf2-shortcut-chip">
      <span className="mf2-shortcut-keys">{shortcutKeys(keys)}</span>
      <span className="mf2-shortcut-label">{label}</span>
    </span>
  );
}

function shortcutKeys(keys: string) {
  if (keys.includes(" or ")) {
    return interleave(
      keys.split(" or ").map((key) => key.trim()).map((key) => (
        <span className="mf2-shortcut-keys" key={key}>{shortcutKeys(key)}</span>
      )),
      <span className="mf2-shortcut-separator">or</span>,
    );
  }
  if (keys.includes("/")) {
    return interleave(
      keys.split("/").map((key) => key.trim()).map((key) => <kbd key={key}>{shortcutKeyLabel(key)}</kbd>),
      <span className="mf2-shortcut-separator">/</span>,
    );
  }
  return interleave(
    keys.split("+").map((key) => key.trim()).map((key) => <kbd key={key}>{shortcutKeyLabel(key)}</kbd>),
    <span className="mf2-shortcut-plus">+</span>,
  );
}

function interleave(items: Array<ReactNode>, separator: ReactNode) {
  return items.flatMap((item, index) => index ? [<span key={`separator-${index}`}>{separator}</span>, item] : [item]);
}

function shortcutKeyLabel(key: string) {
  const labels: Record<string, string> = {
    Cmd: "⌘",
    Ctrl: "Ctrl",
    Down: "↓",
    Enter: "↵",
    Esc: "Esc",
    Shift: "⇧",
    Up: "↑",
  };
  return labels[key] ?? key;
}

function platformNextFormShortcut() {
  return isApplePlatform() ? "Cmd+Enter" : "Ctrl+Enter";
}

function platformUndoShortcut() {
  return isApplePlatform() ? "Cmd+Z" : "Ctrl+Z";
}

function platformRedoShortcut() {
  return isApplePlatform() ? "Cmd+Shift+Z" : "Ctrl+Y";
}

function isApplePlatform() {
  return typeof navigator !== "undefined" && /Mac|iPhone|iPad|iPod/u.test(navigator.platform);
}

function activePatterns(model: EditorModel | null): Array<EditorVariant> {
  if (!model) return [];
  if (model.type === "select") return model.variants;
  return [{ keys: [], value: model.pattern ?? "" }];
}

function selectorsForModel(model: EditorModel | null) {
  return model?.type === "select" ? model.selectors : [];
}

function sameRecordValues(left: Record<string, unknown>, right: Record<string, unknown>) {
  const leftKeys = Object.keys(left);
  const rightKeys = Object.keys(right);
  if (leftKeys.length !== rightKeys.length) return false;
  return leftKeys.every((key) => {
    return Object.prototype.hasOwnProperty.call(right, key) && Object.is(left[key], right[key]);
  });
}

function placeholderRestoreLabel(names: Array<string>) {
  const counts = new Map<string, number>();
  for (const name of names) counts.set(name, (counts.get(name) ?? 0) + 1);
  return [...counts.entries()]
    .map(([name, count]) => `{$${name}}${count > 1 ? ` x${count}` : ""}`)
    .join(", ");
}

function classNames(...names: Array<string | undefined>) {
  return names.filter(Boolean).join(" ");
}

function editorSnapshotSignature(
  target: string,
  diagnostics: Array<EditorDiagnostic>,
  locale: string,
  mode: Mf2EditorMode,
) {
  return JSON.stringify({
    diagnostics: diagnostics.map((diagnostic) => [
      diagnostic.severity,
      diagnostic.code,
      diagnostic.message,
      diagnostic.start,
      diagnostic.end,
      diagnostic.formLabel,
      diagnostic.formLabels,
    ]),
    locale,
    mode,
    target,
  });
}

function localeOptionsWithCurrent(options: Array<Mf2LocaleOption>, currentLocale: string) {
  const normalized = uniqueLocaleOptions(options);
  if (normalized.some((option) => option.value === currentLocale)) return normalized;
  return [{ label: currentLocale, value: currentLocale }, ...normalized];
}

function uniqueLocaleOptions(options: Array<Mf2LocaleOption>) {
  const seen = new Set<string>();
  return options.filter((option) => {
    if (!option.value || seen.has(option.value)) return false;
    seen.add(option.value);
    return true;
  });
}
