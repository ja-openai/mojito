import { baseKeymap } from 'prosemirror-commands';
import { history, redo, undo } from 'prosemirror-history';
import { keymap } from 'prosemirror-keymap';
import type { Node as ProseMirrorNode } from 'prosemirror-model';
import { Schema } from 'prosemirror-model';
import { EditorState, NodeSelection, Plugin, PluginKey, TextSelection } from 'prosemirror-state';
import { Decoration, DecorationSet, EditorView } from 'prosemirror-view';
import {
  forwardRef,
  useCallback,
  useEffect,
  useId,
  useImperativeHandle,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';

import { getVisibleTextMarker } from '../../utils/textCharacters';
import {
  resolveVisibleTextMarksMode,
  shouldRenderVisibleTextWidget,
  shouldShowVisibleTextIssueMarker,
  visibleTextMarkerClassFor,
  type VisibleTextMarksMode,
  visibleTextWidgetText,
} from '../visibleTextFormatting';
import { formNavigationIntent } from './keyboard';
import {
  bracedPatternPartsInPattern,
  filterPlaceholderNames,
  patternTextFromSource,
  patternTextToSource,
  placeholderCompletionConsumesClosingBrace,
  placeholderCompletionToken,
  placeholderExpressionsInPattern,
} from './model';
import type { RawTextTool } from './RawMf2CodeMirror';

export type Mf2ProseMirrorEditorHandle = {
  applyTextTool: (tool: RawTextTool) => boolean;
  blur: () => void;
  focus: () => void;
  restoreMissingPlaceholders: (missing: Array<string>) => boolean;
};

type CompletionState = {
  index: number;
  left: number;
  query: string;
  range: { from: number; to: number };
  text: string;
  top: number;
};

type Mf2ProseMirrorEditorProps = {
  ariaLabel: string;
  focusOnMount?: boolean;
  describedBy?: string;
  direction: 'ltr' | 'rtl';
  marksMode?: VisibleTextMarksMode;
  minLines: number;
  onChange: (pattern: string) => void;
  onNextForm: () => void;
  onPreviousForm: () => void;
  onSubmit?: () => void;
  pattern: string;
  placeholderSources: Record<string, string>;
  placeholders: Array<string>;
  readOnly: boolean;
};

function isBrowserHistoryShortcut(event: KeyboardEvent) {
  return (
    event.metaKey &&
    !event.altKey &&
    !event.ctrlKey &&
    !event.shiftKey &&
    (event.key === '[' || event.key === ']')
  );
}

const schema = new Schema({
  nodes: {
    doc: { content: 'paragraph' },
    paragraph: {
      content: 'inline*',
      group: 'block',
      parseDOM: [{ tag: 'p' }],
      toDOM: () => ['p', 0],
    },
    text: { group: 'inline' },
    placeholder: {
      atom: true,
      attrs: {
        name: { default: '' },
        source: { default: '' },
      },
      group: 'inline',
      inline: true,
      leafText: (node) => {
        const name = stringNodeAttribute(node, 'name');
        return stringNodeAttribute(node, 'source') || `{$${name}}`;
      },
      parseDOM: [
        {
          getAttrs: (node) => {
            if (!(node instanceof HTMLElement)) return false;
            return {
              name: node.dataset.placeholder ?? '',
              source: node.dataset.placeholderSource ?? '',
            };
          },
          tag: 'span[data-placeholder]',
        },
      ],
      selectable: true,
      toDOM: (node) => {
        const name = stringNodeAttribute(node, 'name');
        const source = stringNodeAttribute(node, 'source');
        return [
          'span',
          {
            class: 'mf2-chip mf2-pm-chip',
            'data-placeholder': name,
            'data-placeholder-source': source,
            contenteditable: 'false',
            title: source || `{$${name}}`,
          },
          `{$${name}}`,
        ];
      },
    },
    syntax: {
      atom: true,
      attrs: {
        source: { default: '' },
      },
      group: 'inline',
      inline: true,
      leafText: (node) => stringNodeAttribute(node, 'source'),
      parseDOM: [
        {
          getAttrs: (node) => {
            if (!(node instanceof HTMLElement)) return false;
            return { source: node.dataset.mf2Syntax ?? '' };
          },
          tag: 'span[data-mf2-syntax]',
        },
      ],
      selectable: true,
      toDOM: (node) => {
        const source = stringNodeAttribute(node, 'source');
        return [
          'span',
          {
            class: 'mf2-chip mf2-pm-chip mf2-pm-syntax',
            'data-mf2-syntax': source,
            contenteditable: 'false',
            title: source,
          },
          source,
        ];
      },
    },
  },
  marks: {},
});

const visibleTextPluginKey = new PluginKey<VisibleTextMarksMode>('mf2-visible-text');

export const Mf2ProseMirrorEditor = forwardRef<
  Mf2ProseMirrorEditorHandle,
  Mf2ProseMirrorEditorProps
>(function Mf2ProseMirrorEditor(
  {
    ariaLabel,
    describedBy,
    direction,
    focusOnMount = false,
    marksMode,
    minLines,
    onChange,
    onNextForm,
    onPreviousForm,
    onSubmit,
    pattern,
    placeholderSources,
    placeholders,
    readOnly,
  },
  ref,
) {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const viewRef = useRef<EditorView | null>(null);
  const composingRef = useRef(false);
  const completionRef = useRef<CompletionState | null>(null);
  const filteredCompletionNamesRef = useRef<Array<string>>([]);
  const onChangeRef = useRef(onChange);
  const onNextFormRef = useRef(onNextForm);
  const onPreviousFormRef = useRef(onPreviousForm);
  const onSubmitRef = useRef(onSubmit);
  const placeholdersRef = useRef(placeholders);
  const placeholderSourcesRef = useRef(placeholderSources);
  const readOnlyRef = useRef(readOnly);
  const resolvedMarksMode = resolveVisibleTextMarksMode(marksMode, undefined);
  const marksModeRef = useRef(resolvedMarksMode);
  marksModeRef.current = resolvedMarksMode;
  const [completion, setCompletion] = useState<CompletionState | null>(null);
  const completionId = useId();
  const filteredCompletionNames = useMemo(
    () => filterPlaceholderNames(placeholders, completion?.query ?? ''),
    [completion?.query, placeholders],
  );
  const activeCompletionIndex =
    completion && filteredCompletionNames.length
      ? Math.min(completion.index, filteredCompletionNames.length - 1)
      : 0;
  const activeCompletionOptionId =
    completion && filteredCompletionNames.length
      ? `${completionId}-option-${activeCompletionIndex}`
      : undefined;

  useLayoutEffect(() => {
    completionRef.current = completion;
    filteredCompletionNamesRef.current = filteredCompletionNames;
  }, [completion, filteredCompletionNames]);

  useLayoutEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  useLayoutEffect(() => {
    onNextFormRef.current = onNextForm;
  }, [onNextForm]);

  useLayoutEffect(() => {
    onPreviousFormRef.current = onPreviousForm;
  }, [onPreviousForm]);

  useLayoutEffect(() => {
    onSubmitRef.current = onSubmit;
  }, [onSubmit]);

  useLayoutEffect(() => {
    placeholdersRef.current = placeholders;
  }, [placeholders]);

  useLayoutEffect(() => {
    placeholderSourcesRef.current = placeholderSources;
  }, [placeholderSources]);

  useLayoutEffect(() => {
    readOnlyRef.current = readOnly;
    viewRef.current?.setProps({
      editable: () => !readOnlyRef.current,
    });
  }, [readOnly]);

  const closeCompletion = useCallback(() => {
    setCompletion(null);
  }, []);

  const refreshCompletion = useCallback((view = viewRef.current) => {
    if (!view || readOnlyRef.current || view.composing || composingRef.current) {
      setCompletion(null);
      return false;
    }
    const range = completionRangeFromSelection(view.state);
    if (!range) {
      setCompletion(null);
      return false;
    }
    const coords = view.coordsAtPos(range.to);
    const host = hostRef.current?.getBoundingClientRect();
    setCompletion((current) => ({
      index: current && current.query === range.query ? current.index : 0,
      left: Math.max(6, coords.left - (host?.left ?? coords.left)),
      query: range.query,
      range: { from: range.from, to: range.to },
      text: range.text,
      top: Math.max(6, coords.bottom - (host?.top ?? coords.bottom) + 5),
    }));
    return true;
  }, []);

  const syncPattern = useCallback((view: EditorView) => {
    const nextPattern = mf2ProseMirrorPatternFromDoc(view.state.doc);
    onChangeRef.current(nextPattern);
  }, []);

  const createState = useCallback(
    (nextPattern: string) => {
      const completionPlugin = new Plugin({
        props: {
          handleDOMEvents: {
            blur: () => {
              closeCompletion();
              return false;
            },
            compositionend: (view) => {
              composingRef.current = false;
              window.setTimeout(() => {
                syncPattern(view);
                refreshCompletion(view);
              }, 0);
              return false;
            },
            compositionstart: () => {
              composingRef.current = true;
              closeCompletion();
              return false;
            },
          },
          handleKeyDown: (view, event) => {
            if (!readOnlyRef.current && isBrowserHistoryShortcut(event)) {
              event.preventDefault();
              event.stopPropagation();
              return true;
            }
            if (readOnlyRef.current) return false;
            if (view.composing || composingRef.current || event.isComposing) return false;
            const navigation = formNavigationIntent(event);
            if (navigation) {
              event.preventDefault();
              closeCompletion();
              if (navigation.reason === 'command-enter' && onSubmitRef.current) {
                event.stopPropagation();
                onSubmitRef.current();
                return true;
              }
              if (navigation.direction > 0) onNextFormRef.current();
              else onPreviousFormRef.current();
              return true;
            }
            const arrowKey = event.key === 'ArrowDown' || event.key === 'ArrowUp';
            const hasCommandModifier = event.metaKey || event.ctrlKey;
            const hasTextNavigationModifier = hasCommandModifier || event.altKey;
            const currentCompletion = completionRef.current;
            if (currentCompletion) {
              if (arrowKey && !event.shiftKey && !hasTextNavigationModifier) {
                event.preventDefault();
                const names = filteredCompletionNamesRef.current;
                setCompletion((current) =>
                  current && names.length
                    ? {
                        ...current,
                        index:
                          (current.index + (event.key === 'ArrowDown' ? 1 : -1) + names.length) %
                          names.length,
                      }
                    : null,
                );
                return true;
              }
              if (event.key === 'Enter' && !event.shiftKey && !hasTextNavigationModifier) {
                event.preventDefault();
                const names = filteredCompletionNamesRef.current;
                commitPlaceholderCompletion(
                  view,
                  names,
                  Math.min(currentCompletion.index, Math.max(0, names.length - 1)),
                  currentCompletion,
                  placeholderSourcesRef.current,
                  closeCompletion,
                );
                return true;
              }
              if (event.key === 'Escape') {
                event.preventDefault();
                event.stopPropagation();
                closeCompletion();
                return true;
              }
            }
            if (
              (event.key === 'Backspace' || event.key === 'Delete') &&
              deleteProtectedNodeAtSelection(view, event.key)
            ) {
              event.preventDefault();
              closeCompletion();
              return true;
            }
            if (event.key.length === 1 && !event.metaKey && !event.ctrlKey && !event.altKey) {
              window.setTimeout(() => refreshCompletion(view), 0);
            }
            return false;
          },
        },
      });
      return EditorState.create({
        doc: mf2ProseMirrorDocFromPattern(nextPattern),
        plugins: [
          history(),
          keymap({
            'Mod-y': redo,
            'Mod-z': undo,
            'Shift-Mod-z': redo,
          }),
          createVisibleTextPlugin(marksModeRef.current),
          completionPlugin,
          keymap(baseKeymap),
        ],
        schema,
      });
    },
    [closeCompletion, refreshCompletion, syncPattern],
  );

  useLayoutEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    if (viewRef.current) return;
    const view = new EditorView(host, {
      attributes: {
        'aria-activedescendant': activeCompletionOptionId ?? '',
        'aria-autocomplete': 'list',
        'aria-controls': completion ? completionId : '',
        'aria-describedby': describedBy ?? '',
        'aria-expanded': String(Boolean(completion)),
        'aria-label': ariaLabel,
        'aria-multiline': 'true',
        'aria-readonly': String(readOnly),
        class: 'mf2-pm-view',
        dir: direction,
        role: 'textbox',
        spellcheck: 'false',
      },
      dispatchTransaction(transaction) {
        const current = viewRef.current;
        if (!current) return;
        const nextState = current.state.apply(transaction);
        current.updateState(nextState);
        if (!transaction.docChanged) return;
        if (current.composing || composingRef.current) return;
        syncPattern(current);
        window.setTimeout(() => refreshCompletion(current), 0);
      },
      editable: () => !readOnlyRef.current,
      state: createState(pattern),
    });
    viewRef.current = view;
    if (focusOnMount) view.focus();
    return () => {
      view.destroy();
      viewRef.current = null;
    };
    // EditorView owns this host for the component lifetime; later effects synchronize props.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useLayoutEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    view.dispatch(view.state.tr.setMeta(visibleTextPluginKey, resolvedMarksMode));
  }, [resolvedMarksMode]);

  useLayoutEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const currentPattern = mf2ProseMirrorPatternFromDoc(view.state.doc);
    if (currentPattern !== pattern) {
      view.updateState(createState(pattern));
      closeCompletion();
    }
  }, [closeCompletion, createState, pattern]);

  useLayoutEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    view.dom.setAttribute('aria-activedescendant', activeCompletionOptionId ?? '');
    view.dom.setAttribute('aria-autocomplete', 'list');
    view.dom.setAttribute('aria-controls', completion ? completionId : '');
    view.dom.setAttribute('aria-describedby', describedBy ?? '');
    view.dom.setAttribute('aria-expanded', String(Boolean(completion)));
    view.dom.setAttribute('aria-label', ariaLabel);
    view.dom.setAttribute('aria-multiline', 'true');
    view.dom.setAttribute('aria-readonly', String(readOnly));
    view.dom.setAttribute('dir', direction);
    view.dom.setAttribute('role', 'textbox');
    view.dom.setAttribute('spellcheck', 'false');
  }, [
    activeCompletionOptionId,
    ariaLabel,
    completion,
    completionId,
    describedBy,
    direction,
    readOnly,
  ]);

  useLayoutEffect(() => {
    hostRef.current?.style.setProperty('--mf2-prose-min-lines', String(minLines));
  }, [minLines]);

  useEffect(() => {
    if (!readOnly) return;
    closeCompletion();
  }, [closeCompletion, readOnly]);

  useImperativeHandle(
    ref,
    () => ({
      applyTextTool(tool) {
        const view = viewRef.current;
        if (!view || readOnlyRef.current) return false;
        if (tool.wrap) return wrapSelection(view, tool.wrap[0], tool.wrap[1]);
        if (!tool.text) return false;
        view.dispatch(view.state.tr.insertText(tool.text).scrollIntoView());
        view.focus();
        return true;
      },
      blur() {
        viewRef.current?.dom.blur();
      },
      focus() {
        viewRef.current?.focus();
      },
      restoreMissingPlaceholders(missing) {
        const view = viewRef.current;
        if (!view || readOnlyRef.current || !missing.length) return false;
        const nodes: Array<ProseMirrorNode> = [];
        if (mf2ProseMirrorPatternFromDoc(view.state.doc).trim()) nodes.push(schema.text(' '));
        for (const [index, name] of missing.entries()) {
          if (index) nodes.push(schema.text(' '));
          nodes.push(placeholderNode(name, placeholderSourcesRef.current[name] ?? `{$${name}}`));
        }
        const { from, to } = view.state.selection;
        let transaction = view.state.tr.replaceWith(from, to, nodes).scrollIntoView();
        const cursor = Math.min(
          transaction.doc.content.size,
          from + nodes.reduce((size, node) => size + node.nodeSize, 0),
        );
        transaction = transaction.setSelection(
          TextSelection.near(transaction.doc.resolve(cursor), -1),
        );
        view.dispatch(transaction);
        view.focus();
        return true;
      },
    }),
    [],
  );

  return (
    <div
      className="mf2-prose mf2-prosemirror-host"
      data-mf2-editor="prosemirror"
      data-readonly={String(readOnly)}
      ref={hostRef}
    >
      {completion ? (
        <div
          aria-label={`Placeholder suggestions for ${completion.text}`}
          className="mf2-completion"
          id={completionId}
          role={filteredCompletionNames.length ? 'listbox' : 'status'}
          style={{ left: completion.left, top: completion.top }}
        >
          {filteredCompletionNames.length ? (
            filteredCompletionNames.map((name, itemIndex) => (
              <div
                aria-selected={itemIndex === activeCompletionIndex}
                className="mf2-completion-option"
                id={`${completionId}-option-${itemIndex}`}
                key={name}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => {
                  const view = viewRef.current;
                  if (!view || !completion) return;
                  commitPlaceholderCompletion(
                    view,
                    filteredCompletionNames,
                    itemIndex,
                    completion,
                    placeholderSourcesRef.current,
                    closeCompletion,
                  );
                }}
                role="option"
              >
                {`{$${name}}`}
              </div>
            ))
          ) : (
            <em>No matching placeholder</em>
          )}
        </div>
      ) : null}
    </div>
  );
});

// eslint-disable-next-line react-refresh/only-export-components -- Pure conversion helper used by tests.
export function mf2ProseMirrorDocFromPattern(pattern: string) {
  const nodes: Array<ProseMirrorNode> = [];
  const placeholders = new Map(
    placeholderExpressionsInPattern(pattern).map((expression) => [expression.from, expression]),
  );
  let index = 0;
  for (const part of bracedPatternPartsInPattern(pattern)) {
    if (part.from > index) {
      nodes.push(schema.text(patternTextFromSource(pattern.slice(index, part.from))));
    }
    const placeholder = placeholders.get(part.from);
    nodes.push(
      placeholder?.to === part.to
        ? placeholderNode(placeholder.name, placeholder.source)
        : syntaxNode(part.source),
    );
    index = part.to;
  }
  if (index < pattern.length) {
    nodes.push(schema.text(patternTextFromSource(pattern.slice(index))));
  }
  return schema.nodes.doc.create(null, [schema.nodes.paragraph.create(null, nodes)]);
}

// eslint-disable-next-line react-refresh/only-export-components -- Pure conversion helper used by tests.
export function mf2ProseMirrorPatternFromDoc(doc: ProseMirrorNode) {
  const chunks: Array<string> = [];
  doc.descendants((node) => {
    if (node.isText) chunks.push(patternTextToSource(node.text ?? ''));
    if (node.type.name === 'placeholder') {
      const name = stringNodeAttribute(node, 'name');
      chunks.push(stringNodeAttribute(node, 'source') || `{$${name}}`);
    }
    if (node.type.name === 'syntax') chunks.push(stringNodeAttribute(node, 'source'));
  });
  return chunks.join('');
}

function createVisibleTextPlugin(initialMarksMode: VisibleTextMarksMode) {
  return new Plugin<VisibleTextMarksMode>({
    key: visibleTextPluginKey,
    props: {
      decorations(state) {
        return buildVisibleTextDecorations(
          state.doc,
          visibleTextPluginKey.getState(state) ?? 'auto',
        );
      },
    },
    state: {
      init: () => initialMarksMode,
      apply(transaction, current) {
        return (
          (transaction.getMeta(visibleTextPluginKey) as VisibleTextMarksMode | undefined) ?? current
        );
      },
    },
  });
}

function buildVisibleTextDecorations(doc: ProseMirrorNode, marksMode: VisibleTextMarksMode) {
  if (marksMode === 'off') return DecorationSet.empty;

  const decorations: Array<Decoration> = [];
  const value = doc.textContent;
  let textOffset = 0;

  doc.descendants((node, pos) => {
    if (!node.isText) {
      if (node.isLeaf) textOffset += node.textContent.length;
      return !node.isLeaf;
    }

    let nodeOffset = 0;
    for (const char of Array.from(node.text ?? '')) {
      const marker = getVisibleTextMarker(char);
      const from = pos + nodeOffset;
      const to = from + char.length;
      const rawStart = textOffset + nodeOffset;
      const rawEnd = rawStart + char.length;
      nodeOffset += char.length;

      if (
        !marker ||
        (marksMode === 'auto' &&
          !shouldShowVisibleTextIssueMarker(value, { char, rawEnd, rawStart }))
      ) {
        continue;
      }

      if (shouldRenderVisibleTextWidget(char, marker.text)) {
        decorations.push(
          Decoration.widget(from, () => createVisibleTextMarkerWidget(char, marker), {
            key: `${from}-${marker.text}`,
            side: -1,
          }),
        );
        continue;
      }

      decorations.push(
        Decoration.inline(
          from,
          to,
          {
            class: `visible-text-editor__marked-char visible-text-editor__marked-char--${visibleTextMarkerClassFor(
              char,
            )}`,
            'data-marker': visibleTextWidgetText(marker.text),
            title: marker.label,
          },
          {
            inclusiveEnd: false,
            inclusiveStart: false,
          },
        ),
      );
    }

    textOffset += node.nodeSize;
    return false;
  });

  return DecorationSet.create(doc, decorations);
}

function createVisibleTextMarkerWidget(char: string, marker: { label: string; text: string }) {
  const element = document.createElement('span');
  element.className = `visible-text-editor__marker-widget visible-text-editor__marker-widget--${visibleTextMarkerClassFor(
    char,
  )}`;
  element.textContent = visibleTextWidgetText(marker.text);
  element.setAttribute('aria-hidden', 'true');
  element.setAttribute('contenteditable', 'false');
  element.setAttribute('title', marker.label);
  return element;
}

function stringNodeAttribute(node: ProseMirrorNode, name: string) {
  const value: unknown = node.attrs[name];
  return typeof value === 'string' ? value : '';
}

function commitPlaceholderCompletion(
  view: EditorView,
  names: Array<string>,
  index: number,
  state: CompletionState,
  placeholderSources: Record<string, string>,
  close: () => void,
) {
  const name = names[Math.min(index, Math.max(0, names.length - 1))];
  if (!name) {
    close();
    return;
  }
  const typedToken = view.state.doc.textBetween(state.range.from, state.range.to, '\n', '\n');
  const nextChar = view.state.doc.textBetween(state.range.to, state.range.to + 1, '\n', '\n');
  const to = placeholderCompletionConsumesClosingBrace(typedToken, nextChar)
    ? state.range.to + 1
    : state.range.to;
  const node = placeholderNode(name, placeholderSources[name] ?? `{$${name}}`);
  let transaction = view.state.tr.replaceWith(state.range.from, to, node).scrollIntoView();
  transaction = transaction.setSelection(
    TextSelection.near(transaction.doc.resolve(state.range.from + node.nodeSize), 1),
  );
  close();
  view.dispatch(transaction);
  view.focus();
}

function placeholderNode(name: string, source = `{$${name}}`) {
  return schema.nodes.placeholder.create({ name, source });
}

function syntaxNode(source: string) {
  return schema.nodes.syntax.create({ source });
}

function completionRangeFromSelection(state: EditorState) {
  if (!state.selection.empty) return null;
  const parentStart = state.selection.$from.start();
  const beforeCursor = state.doc.textBetween(parentStart, state.selection.from, '\n', '\n');
  const token = placeholderCompletionToken(beforeCursor);
  if (!token) return null;
  return {
    from: parentStart + token.from,
    query: token.query,
    text: token.text,
    to: state.selection.from,
  };
}

function deleteProtectedNodeAtSelection(view: EditorView, key: string) {
  const { selection } = view.state;
  if (selection instanceof NodeSelection && isProtectedNode(selection.node)) {
    dispatchProtectedNodeDelete(view, selection.from, selection.to, selection.from);
    return true;
  }
  if (!selection.empty) return false;
  const adjacentNode = key === 'Backspace' ? selection.$from.nodeBefore : selection.$from.nodeAfter;
  if (!adjacentNode || !isProtectedNode(adjacentNode)) return false;
  const from = key === 'Backspace' ? selection.from - adjacentNode.nodeSize : selection.from;
  const to = key === 'Backspace' ? selection.from : selection.from + adjacentNode.nodeSize;
  dispatchProtectedNodeDelete(view, from, to, from);
  return true;
}

function isProtectedNode(node: ProseMirrorNode) {
  return node.type.name === 'placeholder' || node.type.name === 'syntax';
}

function dispatchProtectedNodeDelete(view: EditorView, from: number, to: number, cursor: number) {
  const transaction = view.state.tr.delete(from, to);
  const position = Math.max(0, Math.min(cursor, transaction.doc.content.size));
  view.dispatch(
    transaction.setSelection(TextSelection.near(transaction.doc.resolve(position), -1)),
  );
}

function wrapSelection(view: EditorView, prefix: string, suffix: string) {
  const { from, to } = view.state.selection;
  let transaction = view.state.tr;
  if (from === to) {
    transaction = transaction.insertText(`${prefix}${suffix}`, from, to);
    transaction = transaction.setSelection(
      TextSelection.create(transaction.doc, from + prefix.length),
    );
  } else {
    transaction = transaction.insertText(suffix, to, to);
    transaction = transaction.insertText(prefix, from, from);
    transaction = transaction.setSelection(
      TextSelection.create(transaction.doc, to + prefix.length + suffix.length),
    );
  }
  view.dispatch(transaction.scrollIntoView());
  view.focus();
  return true;
}
