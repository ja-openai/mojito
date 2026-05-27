import {
  autocompletion,
  closeCompletion,
  completionStatus,
  startCompletion,
  type CompletionContext,
  type CompletionResult,
} from "@codemirror/autocomplete";
import { linter, lintGutter, type Diagnostic } from "@codemirror/lint";
import { Compartment, EditorState, Transaction } from "@codemirror/state";
import { parseToModel } from "@mojito-mf2/core";
import { basicSetup, EditorView } from "codemirror";
import { useEffect, useRef, useState } from "react";

import { formNavigationIntent } from "./keyboard";
import { filterPlaceholderNames, placeholderCompletionContext, placeholderCompletionReplacement } from "./model";

export type RawTextTool = {
  text?: string;
  wrap?: readonly [string, string];
};

export type RawTextToolCommand = {
  id: number;
  tool: RawTextTool;
};

export type RawDocumentCommand = {
  id: number;
  value: string;
};

type Props = {
  active: boolean;
  ariaLabel?: string;
  className?: string;
  onChange: (value: string) => void;
  onNextForm?: () => void;
  onPreviousForm?: () => void;
  placeholders: Array<string>;
  placeholderSources?: Record<string, string>;
  readOnly?: boolean;
  replaceCommand?: RawDocumentCommand | null;
  textToolCommand?: RawTextToolCommand | null;
  value: string;
};

export function RawMf2CodeMirror({
  active,
  ariaLabel = "Raw target MF2",
  className,
  onChange,
  onNextForm,
  onPreviousForm,
  placeholders,
  placeholderSources = {},
  readOnly = false,
  replaceCommand,
  textToolCommand,
  value,
}: Props) {
  const [shouldCreateView, setShouldCreateView] = useState(active);
  const [viewReadyGeneration, setViewReadyGeneration] = useState(0);
  const hostRef = useRef<HTMLDivElement | null>(null);
  const contentAttributesCompartmentRef = useRef<Compartment | null>(null);
  const lastReplaceCommandRef = useRef<number | null>(null);
  const lastTextToolCommandRef = useRef<number | null>(null);
  const readOnlyCompartmentRef = useRef<Compartment | null>(null);
  const viewRef = useRef<EditorView | null>(null);
  const onChangeRef = useRef(onChange);
  const onNextFormRef = useRef(onNextForm);
  const onPreviousFormRef = useRef(onPreviousForm);
  const placeholdersRef = useRef(placeholders);
  const placeholderSourcesRef = useRef(placeholderSources);

  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  useEffect(() => {
    onNextFormRef.current = onNextForm;
  }, [onNextForm]);

  useEffect(() => {
    onPreviousFormRef.current = onPreviousForm;
  }, [onPreviousForm]);

  useEffect(() => {
    placeholdersRef.current = placeholders;
    const view = viewRef.current;
    if (view && completionStatus(view.state)) closeCompletion(view);
  }, [placeholders]);

  useEffect(() => {
    placeholderSourcesRef.current = placeholderSources;
    const view = viewRef.current;
    if (view && completionStatus(view.state)) closeCompletion(view);
  }, [placeholderSources]);

  useEffect(() => {
    if (active) setShouldCreateView(true);
  }, [active]);

  useEffect(() => {
    if (!shouldCreateView || viewRef.current) return;
    const host = hostRef.current;
    if (!host) return;
    const contentAttributesCompartment = new Compartment();
    const readOnlyCompartment = new Compartment();
    contentAttributesCompartmentRef.current = contentAttributesCompartment;
    readOnlyCompartmentRef.current = readOnlyCompartment;
    const view = new EditorView({
      parent: host,
      state: EditorState.create({
        doc: value,
        extensions: [
          basicSetup,
          contentAttributesCompartment.of(rawContentAttributes(ariaLabel, readOnly)),
          readOnlyCompartment.of(readOnlyExtensions(readOnly)),
          EditorView.lineWrapping,
          rawPlaceholderAutocompletion(() => placeholdersRef.current, () => placeholderSourcesRef.current),
          rawFormNavigation(() => ({
            next: onNextFormRef.current,
            previous: onPreviousFormRef.current,
          })),
          lintGutter(),
          mf2ParserLinter(),
          EditorView.updateListener.of((update) => {
            if (update.docChanged && !update.transactions.some((transaction) => transaction.annotation(Transaction.remote))) {
              onChangeRef.current(update.state.doc.toString());
            }
          }),
        ],
      }),
    });
    viewRef.current = view;
    setViewReadyGeneration((generation) => generation + 1);
    if (active) view.requestMeasure();
    return () => {
      view.destroy();
      contentAttributesCompartmentRef.current = null;
      readOnlyCompartmentRef.current = null;
      viewRef.current = null;
    };
  }, [shouldCreateView]);

  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const current = view.state.doc.toString();
    if (current === value) return;
    const selection = view.state.selection.main;
    view.dispatch({
      annotations: [Transaction.remote.of(true), Transaction.addToHistory.of(false)],
      changes: { from: 0, to: current.length, insert: value },
      selection: {
        anchor: clampOffset(selection.anchor, value.length),
        head: clampOffset(selection.head, value.length),
      },
    });
  }, [value]);

  useEffect(() => {
    if (active) viewRef.current?.requestMeasure();
  }, [active]);

  useEffect(() => {
    const compartment = readOnlyCompartmentRef.current;
    const view = viewRef.current;
    if (!compartment || !view) return;
    view.dispatch({
      effects: compartment.reconfigure(readOnlyExtensions(readOnly)),
    });
    if (readOnly) closeCompletion(view);
  }, [readOnly]);

  useEffect(() => {
    const compartment = contentAttributesCompartmentRef.current;
    const view = viewRef.current;
    if (!compartment || !view) return;
    view.dispatch({
      effects: compartment.reconfigure(rawContentAttributes(ariaLabel, readOnly)),
    });
  }, [ariaLabel, readOnly]);

  useEffect(() => {
    const view = viewRef.current;
    if (!view || !textToolCommand || readOnly) return;
    if (lastTextToolCommandRef.current === textToolCommand.id) return;
    lastTextToolCommandRef.current = textToolCommand.id;
    applyRawTextTool(view, textToolCommand.tool);
  }, [readOnly, textToolCommand, viewReadyGeneration]);

  useEffect(() => {
    const view = viewRef.current;
    if (!view || !replaceCommand || readOnly) return;
    if (lastReplaceCommandRef.current === replaceCommand.id) return;
    lastReplaceCommandRef.current = replaceCommand.id;
    applyRawDocumentReplace(view, replaceCommand.value);
  }, [readOnly, replaceCommand, viewReadyGeneration]);

  return <div aria-label={ariaLabel} className={className} ref={hostRef} role="group" />;
}

function rawContentAttributes(ariaLabel: string, readOnly: boolean) {
  return EditorView.contentAttributes.of({
    "aria-label": ariaLabel,
    "aria-multiline": "true",
    "aria-readonly": String(readOnly),
    role: "textbox",
  });
}

function readOnlyExtensions(readOnly: boolean) {
  return [
    EditorState.readOnly.of(readOnly),
    EditorView.editable.of(!readOnly),
  ];
}

function mf2ParserLinter() {
  return linter(
    (view) => {
      const source = view.state.doc.toString();
      const parsed = parseToModel(source);
      return (parsed.diagnostics ?? []).map((diagnostic) => codeMirrorDiagnostic(diagnostic, source.length));
    },
    { delay: 250 },
  );
}

function codeMirrorDiagnostic(diagnostic: Record<string, unknown>, docLength: number): Diagnostic {
  const severity = diagnostic.severity === "warning" ? "warning" : "error";
  return {
    ...codeMirrorDiagnosticRange(diagnostic, docLength),
    markClass: `mf2-cm-diagnostic mf2-cm-diagnostic-${severity}`,
    message: `${String(diagnostic.code ?? "mf2")}: ${String(diagnostic.message ?? diagnostic)}`,
    severity,
    source: "JavaScript MF2",
  };
}

function codeMirrorDiagnosticRange(diagnostic: Record<string, unknown>, docLength: number) {
  const from = clampOffset(diagnostic.start, docLength);
  const fallbackEnd = docLength > from ? from + 1 : from;
  const rawEnd = diagnostic.end == null || diagnostic.end === diagnostic.start ? fallbackEnd : diagnostic.end;
  const to = clampOffset(rawEnd, docLength);
  return { from, to: Math.max(from, to) };
}

function clampOffset(value: unknown, docLength: number) {
  const offset = Number(value);
  if (!Number.isFinite(offset)) return 0;
  return Math.max(0, Math.min(docLength, offset));
}

function applyRawTextTool(view: EditorView, tool: RawTextTool) {
  const range = view.state.selection.main;
  const selected = view.state.doc.sliceString(range.from, range.to);
  const insert = tool.wrap ? `${tool.wrap[0]}${selected}${tool.wrap[1]}` : tool.text ?? "";
  if (!insert) return;
  closeCompletion(view);
  const cursor = tool.wrap && range.empty ? range.from + tool.wrap[0].length : range.from + insert.length;
  view.dispatch({
    changes: { from: range.from, insert, to: range.to },
    selection: { anchor: cursor },
    userEvent: "input",
  });
  view.focus();
}

function applyRawDocumentReplace(view: EditorView, value: string) {
  const current = view.state.doc.toString();
  if (current === value) return;
  closeCompletion(view);
  view.dispatch({
    changes: { from: 0, insert: value, to: current.length },
    selection: { anchor: value.length },
    userEvent: "input",
  });
  view.focus();
}

function rawPlaceholderAutocompletion(
  namesProvider: () => Array<string>,
  sourcesProvider: () => Record<string, string>,
) {
  return [
    autocompletion({
      activateOnTyping: true,
      override: [
        (context: CompletionContext) => rawPlaceholderCompletionSource(context, namesProvider, sourcesProvider),
      ],
    }),
    EditorView.updateListener.of((update) => {
      if (!update.docChanged || !update.state.selection.main.empty) return;
      const pos = update.state.selection.main.head;
      if (!rawPlaceholderCompletionContext(update.state.doc.toString(), pos)) return;
      const status = completionStatus(update.state);
      if (status !== "active" && status !== "pending") startCompletion(update.view);
    }),
  ];
}

function rawFormNavigation(handlersProvider: () => {
  next?: () => void;
  previous?: () => void;
}) {
  return EditorView.domEventHandlers({
    keydown(event) {
      const navigation = formNavigationIntent(event);
      if (!navigation) return false;
      const handlers = handlersProvider();
      const handler = navigation.direction > 0 ? handlers.next : handlers.previous;
      if (!handler) return false;
      event.preventDefault();
      handler();
      return true;
    },
  });
}

function rawPlaceholderCompletionSource(
  context: CompletionContext,
  namesProvider: () => Array<string>,
  sourcesProvider: () => Record<string, string>,
) {
  return rawPlaceholderCompletionResult(context, namesProvider, sourcesProvider);
}

function rawPlaceholderCompletionResult(
  context: CompletionContext,
  namesProvider: () => Array<string>,
  sourcesProvider: () => Record<string, string>,
  expectedFrom?: number,
): CompletionResult | null {
  const completion = rawPlaceholderCompletionContext(context.state.doc.toString(), context.pos);
  if (!completion && !context.explicit) return null;
  if (!completion) return null;
  if (expectedFrom !== undefined && completion.from !== expectedFrom) return null;
  const names = filterPlaceholderNames(namesProvider(), completion.query);
  if (!names.length) return null;
  return {
    filter: false,
    from: completion.from,
    to: context.pos,
    options: names.map((name) => ({
      apply: (view: EditorView, _completion: unknown, from: number, to: number) => {
        const replacement = placeholderCompletionReplacement(
          view.state.doc.toString(),
          from,
          to,
          name,
          sourcesProvider()[name] ?? `{$${name}}`,
        );
        view.dispatch({
          changes: { from: replacement.from, insert: replacement.insert, to: replacement.to },
          selection: { anchor: replacement.cursor },
          userEvent: "input.complete",
        });
      },
      detail: "placeholder",
      label: `{$${name}}`,
      type: "variable",
    })),
    update: (_current, from, _to, nextContext) => (
      rawPlaceholderCompletionResult(nextContext, namesProvider, sourcesProvider, from)
    ),
  };
}

function rawPlaceholderCompletionContext(source: string, pos: number) {
  return placeholderCompletionContext(source, pos);
}
