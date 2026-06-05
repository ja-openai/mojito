import './visible-text-editor.css';

import { baseKeymap } from 'prosemirror-commands';
import { history, redo, undo } from 'prosemirror-history';
import { keymap } from 'prosemirror-keymap';
import { Fragment, type Node as ProseMirrorNode, Schema, Slice } from 'prosemirror-model';
import { EditorState, Plugin, PluginKey, TextSelection } from 'prosemirror-state';
import { EditorView } from 'prosemirror-view';
import { Decoration, DecorationSet } from 'prosemirror-view';
import {
  type CSSProperties,
  forwardRef,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';

import type { IcuPluralOptionInsertion, ProtectedTextToken } from '../utils/protectedTextTokens';
import {
  getVisibleTextMarker,
  isControlCode,
  isInvisibleDirectionalOrZeroWidthCode,
} from '../utils/textCharacters';

export type VisibleTextEditorHandle = {
  blur: () => void;
  focus: () => void;
  getSelection: () => { start: number; end: number };
  insertText: (text: string) => void;
  redo: () => boolean;
  setSelection: (selection: { start: number; end: number }) => void;
  undo: () => boolean;
  wrapSelection: (open: string, close: string) => void;
};

type AddIcuPluralOptionResult = { ok: true } | { ok: false; error: string };

type Props = {
  value: string;
  onChange: (nextValue: string) => void;
  showInvisibles: boolean;
  ariaLabel?: string;
  className?: string;
  controlBar?: {
    icuPluralOptionInsertions?: IcuPluralOptionInsertion[];
    onAddIcuPluralOption?: (insertionId: string, exactValue?: string) => AddIcuPluralOptionResult;
    onToggleInvisibles?: () => void;
    onToggleRawMode?: () => void;
    position?: 'bottom' | 'top';
    rawMode?: boolean;
    protectedTokenCount?: number;
    protectedTokenLabel?: string;
  };
  dir?: 'ltr' | 'rtl' | 'auto';
  disabled?: boolean;
  lang?: string;
  onFocus?: () => void;
  onKeyDown?: (event: KeyboardEvent) => void;
  onSelectionChange?: (selection: { start: number; end: number }) => void;
  placeholder?: string;
  protectedTokens?: ProtectedTextToken[];
  readOnly?: boolean;
  spellCheck?: boolean;
  style?: CSSProperties;
  validateNextValue?: (nextValue: string) => boolean;
};

type VisibleTextPluginState = {
  showInvisibles: boolean;
};

type VisibleMarkerItem = {
  char: string;
  from: number;
  marker: {
    label: string;
    text: string;
  };
  rawEnd: number;
  rawStart: number;
  to: number;
};

type EditorDomAttributes = {
  ariaLabel: string;
  dir: 'ltr' | 'rtl' | 'auto';
  lang?: string;
  placeholder?: string;
  spellCheck: boolean;
};

const visibleTextPluginKey = new PluginKey<VisibleTextPluginState>('visible-text-editor');
const externalValueMetaKey = 'visible-text-editor-external-value';

const textEditorSchema = new Schema({
  nodes: {
    doc: { content: 'inline*' },
    text: { group: 'inline' },
    hardBreak: {
      group: 'inline',
      inline: true,
      atom: true,
      selectable: false,
      attrs: {
        raw: { default: '\n' },
      },
      toDOM() {
        return ['br'];
      },
    },
    protectedToken: {
      group: 'inline',
      inline: true,
      atom: true,
      selectable: true,
      attrs: {
        kind: { default: 'placeholder' },
        label: { default: 'placeholder' },
        raw: { default: '' },
      },
      toDOM(node) {
        const attrs = node.attrs as { kind: string; label: string; raw: string };
        return [
          'span',
          {
            'aria-label': attrs.label,
            class: `visible-text-editor__protected-token visible-text-editor__protected-token--${attrs.kind}`,
            'data-raw': attrs.raw,
            title: attrs.raw,
          },
          attrs.raw,
        ];
      },
    },
  },
});

function nodesFromRawText(value: string): ProseMirrorNode[] {
  const nodes: ProseMirrorNode[] = [];
  let textStart = 0;
  let index = 0;

  while (index < value.length) {
    const char = value.charAt(index);
    if (char !== '\n' && char !== '\r') {
      index += 1;
      continue;
    }

    if (textStart < index) {
      nodes.push(textEditorSchema.text(value.slice(textStart, index)));
    }

    const raw = char === '\r' && value.charAt(index + 1) === '\n' ? '\r\n' : char;
    nodes.push(textEditorSchema.nodes.hardBreak.create({ raw }));
    index += raw.length;
    textStart = index;
  }

  if (textStart < value.length) {
    nodes.push(textEditorSchema.text(value.slice(textStart)));
  }

  return nodes;
}

function fragmentFromRawText(value: string): Fragment {
  return Fragment.fromArray(nodesFromRawText(value));
}

function docFromText(value: string, protectedTokens: ProtectedTextToken[] = []): ProseMirrorNode {
  const children: ProseMirrorNode[] = [];
  let cursor = 0;

  normalizeProtectedTokens(value, protectedTokens).forEach((token) => {
    if (cursor < token.start) {
      children.push(...nodesFromRawText(value.slice(cursor, token.start)));
    }

    const raw = value.slice(token.start, token.end);
    children.push(
      textEditorSchema.nodes.protectedToken.create({
        kind: token.kind,
        label: token.label,
        raw,
      }),
    );
    cursor = token.end;
  });

  if (cursor < value.length) {
    children.push(...nodesFromRawText(value.slice(cursor)));
  }

  return textEditorSchema.topNodeType.create(null, children.length > 0 ? children : null);
}

function textFromDoc(doc: ProseMirrorNode): string {
  let output = '';
  doc.descendants((node) => {
    if (node.isText) {
      output += node.text ?? '';
      return false;
    }
    if (node.type.name === 'protectedToken') {
      output += String(node.attrs.raw ?? '');
      return false;
    }
    if (node.type.name === 'hardBreak') {
      output += String(node.attrs.raw ?? '\n');
      return false;
    }
    return true;
  });
  return output;
}

function textFromFragment(fragment: Fragment): string {
  let output = '';
  fragment.descendants((node) => {
    if (node.isText) {
      output += node.text ?? '';
      return false;
    }
    if (node.type.name === 'protectedToken') {
      output += String(node.attrs.raw ?? '');
      return false;
    }
    if (node.type.name === 'hardBreak') {
      output += String(node.attrs.raw ?? '\n');
      return false;
    }
    return true;
  });
  return output;
}

function stringAttribute(value: unknown): string {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : '';
}

function collectProtectedTokenCounts(doc: ProseMirrorNode): Map<string, number> {
  const counts = new Map<string, number>();

  doc.descendants((node) => {
    if (node.type.name !== 'protectedToken') {
      return true;
    }

    const attrs = node.attrs as { kind?: unknown; raw?: unknown };
    const key = `${stringAttribute(attrs.kind)}\u0000${stringAttribute(attrs.raw)}`;
    counts.set(key, (counts.get(key) ?? 0) + 1);
    return false;
  });

  return counts;
}

function preservesProtectedTokens(before: ProseMirrorNode, after: ProseMirrorNode): boolean {
  const beforeCounts = collectProtectedTokenCounts(before);
  if (beforeCounts.size === 0) {
    return true;
  }

  const afterCounts = collectProtectedTokenCounts(after);
  for (const [key, count] of beforeCounts.entries()) {
    if ((afterCounts.get(key) ?? 0) < count) {
      return false;
    }
  }
  return true;
}

function clampPosition(position: number, doc: ProseMirrorNode): number {
  return Math.max(0, Math.min(position, doc.content.size));
}

function normalizeProtectedTokens(
  value: string,
  protectedTokens: ProtectedTextToken[],
): ProtectedTextToken[] {
  const sorted = protectedTokens
    .filter((token) => token.start >= 0 && token.end > token.start && token.end <= value.length)
    .sort((a, b) => a.start - b.start || b.end - a.end);
  const output: ProtectedTextToken[] = [];
  let cursor = 0;

  sorted.forEach((token) => {
    if (token.start < cursor) {
      return;
    }
    output.push(token);
    cursor = token.end;
  });

  return output;
}

function protectedTokenSignature(value: string, tokens: ProtectedTextToken[]): string {
  return tokens
    .map((token) => `${token.kind}:${token.label}:${value.slice(token.start, token.end)}`)
    .join('|');
}

function rawOffsetFromDocPosition(doc: ProseMirrorNode, position: number): number {
  let rawOffset = 0;
  let found = false;

  doc.descendants((node, pos) => {
    if (found) {
      return false;
    }
    if (pos >= position) {
      found = true;
      return false;
    }
    if (node.isText) {
      const text = node.text ?? '';
      rawOffset += Math.min(text.length, Math.max(0, position - pos));
      if (position <= pos + text.length) {
        found = true;
      }
      return false;
    }
    if (node.type.name === 'protectedToken') {
      const raw = String(node.attrs.raw ?? '');
      if (position <= pos + node.nodeSize) {
        rawOffset += position <= pos ? 0 : raw.length;
        found = true;
      } else {
        rawOffset += raw.length;
      }
      return false;
    }
    if (node.type.name === 'hardBreak') {
      const raw = String(node.attrs.raw ?? '\n');
      if (position <= pos + node.nodeSize) {
        rawOffset += position <= pos ? 0 : raw.length;
        found = true;
      } else {
        rawOffset += raw.length;
      }
      return false;
    }
    return true;
  });

  return rawOffset;
}

function docPositionFromRawOffset(doc: ProseMirrorNode, rawOffset: number): number {
  let consumed = 0;
  let position = doc.content.size;
  let found = false;

  doc.descendants((node, pos) => {
    if (found) {
      return false;
    }
    if (node.isText) {
      const textLength = node.text?.length ?? 0;
      if (rawOffset <= consumed + textLength) {
        position = pos + Math.max(0, rawOffset - consumed);
        found = true;
      }
      consumed += textLength;
      return false;
    }
    if (node.type.name === 'protectedToken') {
      const rawLength = String(node.attrs.raw ?? '').length;
      if (rawOffset <= consumed + rawLength) {
        position = rawOffset <= consumed ? pos : pos + node.nodeSize;
        found = true;
      }
      consumed += rawLength;
      return false;
    }
    if (node.type.name === 'hardBreak') {
      const rawLength = String(node.attrs.raw ?? '\n').length;
      if (rawOffset <= consumed + rawLength) {
        position = rawOffset <= consumed ? pos : pos + node.nodeSize;
        found = true;
      }
      consumed += rawLength;
      return false;
    }
    return true;
  });

  return clampPosition(position, doc);
}

function applyEditorDomAttributes(view: EditorView, attributes: EditorDomAttributes) {
  view.dom.setAttribute('aria-label', attributes.ariaLabel);
  view.dom.setAttribute('aria-multiline', 'true');
  view.dom.setAttribute('data-empty', view.state.doc.content.size === 0 ? 'true' : 'false');
  view.dom.setAttribute('data-placeholder', attributes.placeholder ?? '');
  view.dom.setAttribute('dir', attributes.dir);
  if (attributes.lang) {
    view.dom.setAttribute('lang', attributes.lang);
  } else {
    view.dom.removeAttribute('lang');
  }
  view.dom.setAttribute('role', 'textbox');
  view.dom.setAttribute('spellcheck', attributes.spellCheck ? 'true' : 'false');
}

function markerClassFor(char: string): string {
  if (char === ' ') {
    return 'space';
  }
  if (char === '\t') {
    return 'tab';
  }
  if (char === '\n' || char === '\r') {
    return 'line-break';
  }
  if (char === '\u00a0' || char === '\u202f') {
    return 'special-space';
  }

  const code = char.codePointAt(0);
  if (code != null && isInvisibleDirectionalOrZeroWidthCode(code)) {
    return 'zero-width';
  }
  if (code != null && isControlCode(code)) {
    return 'control';
  }
  return 'other';
}

function widgetText(markerText: string): string {
  return markerText.replace(/\r?\n/gu, '');
}

function shouldRenderWidget(char: string, markerText: string): boolean {
  if (char === '\n' || char === '\r') {
    return true;
  }
  if (markerText.startsWith('<')) {
    return true;
  }
  const code = char.codePointAt(0);
  return code != null && (isInvisibleDirectionalOrZeroWidthCode(code) || isControlCode(code));
}

function shouldShowIssueMarker(value: string, item: VisibleMarkerItem): boolean {
  const { char, rawEnd, rawStart } = item;
  if (char === ' ') {
    return (
      rawStart === 0 ||
      rawEnd === value.length ||
      value.charAt(rawStart - 1) === ' ' ||
      value.charAt(rawEnd) === ' '
    );
  }

  return true;
}

function createMarkerWidget(char: string, markerText: string, label: string): HTMLElement {
  const element = document.createElement('span');
  element.className = `visible-text-editor__marker-widget visible-text-editor__marker-widget--${markerClassFor(
    char,
  )}`;
  element.textContent = widgetText(markerText);
  element.setAttribute('aria-hidden', 'true');
  element.setAttribute('contenteditable', 'false');
  element.setAttribute('title', label);
  return element;
}

function replaceSelectionWithRawText(view: EditorView, text: string) {
  view.dispatch(view.state.tr.replaceSelection(new Slice(fragmentFromRawText(text), 0, 0)));
}

function buildVisibleDecorations(doc: ProseMirrorNode): DecorationSet {
  const decorations: Decoration[] = [];
  const markerItems: VisibleMarkerItem[] = [];
  const value = textFromDoc(doc);
  let rawOffset = 0;

  doc.descendants((node, pos) => {
    if (node.type.name === 'protectedToken') {
      rawOffset += String(node.attrs.raw ?? '').length;
      return false;
    }

    if (node.type.name === 'hardBreak') {
      const raw = String(node.attrs.raw ?? '\n');
      const marker = getVisibleTextMarker(raw.includes('\n') ? '\n' : '\r');
      if (marker) {
        markerItems.push({
          char: raw.includes('\n') ? '\n' : '\r',
          from: pos,
          marker,
          rawEnd: rawOffset + raw.length,
          rawStart: rawOffset,
          to: pos + node.nodeSize,
        });
      }
      rawOffset += raw.length;
      return false;
    }

    if (!node.isText) {
      return true;
    }

    let offset = 0;
    for (const char of Array.from(node.text ?? '')) {
      const marker = getVisibleTextMarker(char);
      const from = pos + offset;
      const to = from + char.length;
      const rawStart = rawOffset + offset;
      const rawEnd = rawStart + char.length;
      offset += char.length;

      if (!marker) {
        continue;
      }

      markerItems.push({ char, from, marker, rawEnd, rawStart, to });
    }

    rawOffset += node.text?.length ?? 0;
    return false;
  });

  markerItems.forEach((item) => {
    const { char, from, marker, to } = item;
    if (!shouldShowIssueMarker(value, item)) {
      return;
    }

    if (shouldRenderWidget(char, marker.text)) {
      decorations.push(
        Decoration.widget(from, () => createMarkerWidget(char, marker.text, marker.label), {
          key: `${from}-${marker.text}`,
          side: -1,
        }),
      );
      return;
    }

    decorations.push(
      Decoration.inline(
        from,
        to,
        {
          class: `visible-text-editor__marked-char visible-text-editor__marked-char--${markerClassFor(
            char,
          )}`,
          'data-marker': widgetText(marker.text),
          title: marker.label,
        },
        {
          inclusiveEnd: false,
          inclusiveStart: false,
        },
      ),
    );
  });

  return DecorationSet.create(doc, decorations);
}

function insertLineBreak(state: EditorState, dispatch?: (tr: EditorState['tr']) => void): boolean {
  dispatch?.(
    state.tr
      .replaceSelectionWith(state.schema.nodes.hardBreak.create({ raw: '\n' }))
      .scrollIntoView(),
  );
  return true;
}

function createVisibleTextPlugin(showInvisibles: boolean): Plugin<VisibleTextPluginState> {
  return new Plugin<VisibleTextPluginState>({
    key: visibleTextPluginKey,
    filterTransaction(transaction, state) {
      if (!transaction.docChanged || transaction.getMeta(externalValueMetaKey)) {
        return true;
      }
      return preservesProtectedTokens(state.doc, transaction.doc);
    },
    props: {
      decorations(state) {
        const pluginState = visibleTextPluginKey.getState(state);
        return pluginState?.showInvisibles ? buildVisibleDecorations(state.doc) : null;
      },
      handlePaste(view, event) {
        const text = event.clipboardData?.getData('text/plain');
        if (text == null) {
          return false;
        }
        event.preventDefault();
        replaceSelectionWithRawText(view, text);
        return true;
      },
      clipboardTextSerializer(slice) {
        return textFromFragment(slice.content);
      },
    },
    state: {
      init: () => ({ showInvisibles }),
      apply(tr, current) {
        const next = tr.getMeta(visibleTextPluginKey) as
          | Partial<VisibleTextPluginState>
          | undefined;
        if (typeof next?.showInvisibles === 'boolean') {
          return { showInvisibles: next.showInvisibles };
        }
        return current;
      },
    },
  });
}

function createPlugins(showInvisibles: boolean) {
  return [
    keymap({
      Enter: insertLineBreak,
      'Mod-z': undo,
      'Mod-y': redo,
      'Shift-Mod-z': redo,
    }),
    history(),
    keymap(baseKeymap),
    createVisibleTextPlugin(showInvisibles),
  ];
}

function notifySelectionChange(
  view: EditorView,
  signatureRef: { current: string },
  onSelectionChange?: (selection: { start: number; end: number }) => void,
) {
  if (!onSelectionChange) {
    return;
  }

  const selection = {
    start: rawOffsetFromDocPosition(view.state.doc, view.state.selection.from),
    end: rawOffsetFromDocPosition(view.state.doc, view.state.selection.to),
  };
  const signature = `${selection.start}:${selection.end}`;
  if (signatureRef.current === signature) {
    return;
  }

  signatureRef.current = signature;
  onSelectionChange(selection);
}

export const VisibleTextEditor = forwardRef<VisibleTextEditorHandle, Props>(
  function VisibleTextEditor(
    {
      value,
      onChange,
      showInvisibles,
      ariaLabel = 'Text editor',
      className,
      controlBar,
      dir = 'auto',
      disabled = false,
      lang,
      onFocus,
      onKeyDown,
      onSelectionChange,
      placeholder,
      protectedTokens,
      readOnly = false,
      spellCheck = true,
      style,
      validateNextValue,
    },
    forwardedRef,
  ) {
    const mountRef = useRef<HTMLDivElement | null>(null);
    const viewRef = useRef<EditorView | null>(null);
    const exactValueInputRef = useRef<HTMLInputElement | null>(null);
    const onChangeRef = useRef(onChange);
    const onFocusRef = useRef(onFocus);
    const onKeyDownRef = useRef(onKeyDown);
    const onSelectionChangeRef = useRef(onSelectionChange);
    const valueRef = useRef(value);
    const disabledRef = useRef(disabled);
    const readOnlyRef = useRef(readOnly);
    const initialShowInvisiblesRef = useRef(showInvisibles);
    const protectedTokensRef = useRef(protectedTokens ?? []);
    const validateNextValueRef = useRef(validateNextValue);
    const protectedTokenSignatureRef = useRef(
      protectedTokenSignature(value, normalizeProtectedTokens(value, protectedTokens ?? [])),
    );
    const domAttributesRef = useRef<EditorDomAttributes>({
      ariaLabel,
      dir,
      lang,
      placeholder,
      spellCheck,
    });
    const applyingExternalValueRef = useRef(false);
    const selectionSignatureRef = useRef('');
    const [exactValueInsertionId, setExactValueInsertionId] = useState<string | null>(null);
    const [exactValueDraft, setExactValueDraft] = useState('');
    const [exactValueError, setExactValueError] = useState<string | null>(null);
    onChangeRef.current = onChange;
    onFocusRef.current = onFocus;
    onKeyDownRef.current = onKeyDown;
    onSelectionChangeRef.current = onSelectionChange;
    valueRef.current = value;
    disabledRef.current = disabled;
    readOnlyRef.current = readOnly;
    protectedTokensRef.current = protectedTokens ?? [];
    validateNextValueRef.current = validateNextValue;
    domAttributesRef.current = {
      ariaLabel,
      dir,
      lang,
      placeholder,
      spellCheck,
    };

    const editorClassName = useMemo(
      () =>
        `visible-text-editor${controlBar ? ' visible-text-editor--with-control-bar' : ''}${
          controlBar?.position === 'top' ? ' visible-text-editor--control-bar-top' : ''
        }${disabled ? ' visible-text-editor--disabled' : ''}${
          readOnly ? ' visible-text-editor--read-only' : ''
        }${className ? ` ${className}` : ''}`,
      [className, controlBar, disabled, readOnly],
    );

    useEffect(() => {
      const mount = mountRef.current;
      if (!mount || viewRef.current) {
        return;
      }

      const view = new EditorView(mount, {
        state: EditorState.create({
          doc: docFromText(valueRef.current, protectedTokensRef.current),
          schema: textEditorSchema,
          plugins: createPlugins(initialShowInvisiblesRef.current),
        }),
        dispatchTransaction(transaction) {
          if (
            transaction.docChanged &&
            !transaction.getMeta(externalValueMetaKey) &&
            validateNextValueRef.current &&
            !validateNextValueRef.current(textFromDoc(transaction.doc))
          ) {
            return;
          }

          const nextState = view.state.apply(transaction);
          view.updateState(nextState);
          applyEditorDomAttributes(view, domAttributesRef.current);
          notifySelectionChange(view, selectionSignatureRef, onSelectionChangeRef.current);

          if (!transaction.docChanged || applyingExternalValueRef.current) {
            return;
          }

          const nextValue = textFromDoc(nextState.doc);
          if (nextValue === valueRef.current) {
            return;
          }

          valueRef.current = nextValue;
          onChangeRef.current(nextValue);
        },
        editable: () => !disabledRef.current && !readOnlyRef.current,
        handleDOMEvents: {
          focus() {
            onFocusRef.current?.();
            return false;
          },
          keydown(_view, event) {
            onKeyDownRef.current?.(event);
            return event.defaultPrevented;
          },
        },
      });

      viewRef.current = view;
      applyEditorDomAttributes(view, domAttributesRef.current);

      return () => {
        view.destroy();
        viewRef.current = null;
      };
    }, []);

    useEffect(() => {
      const view = viewRef.current;
      if (!view) {
        return;
      }

      applyEditorDomAttributes(view, {
        ariaLabel,
        dir,
        lang,
        placeholder,
        spellCheck,
      });
      view.setProps({ editable: () => !disabledRef.current && !readOnlyRef.current });
    }, [ariaLabel, dir, lang, placeholder, readOnly, spellCheck]);

    useEffect(() => {
      const view = viewRef.current;
      if (!view) {
        return;
      }
      view.dispatch(
        view.state.tr.setMeta(visibleTextPluginKey, {
          showInvisibles,
        } satisfies VisibleTextPluginState),
      );
    }, [showInvisibles]);

    useEffect(() => {
      const view = viewRef.current;
      if (!view) {
        return;
      }

      const currentValue = textFromDoc(view.state.doc);
      const nextSignature = protectedTokenSignature(
        value,
        normalizeProtectedTokens(value, protectedTokens ?? []),
      );
      if (currentValue === value && protectedTokenSignatureRef.current === nextSignature) {
        return;
      }

      const rawSelectionStart = rawOffsetFromDocPosition(view.state.doc, view.state.selection.from);
      const nextDoc = docFromText(value, protectedTokens ?? []);
      const selectionStart = docPositionFromRawOffset(nextDoc, rawSelectionStart);
      let transaction = view.state.tr.replaceWith(0, view.state.doc.content.size, nextDoc.content);
      transaction = transaction
        .setSelection(TextSelection.create(transaction.doc, selectionStart))
        .setMeta('addToHistory', false)
        .setMeta(externalValueMetaKey, true);
      protectedTokenSignatureRef.current = nextSignature;

      applyingExternalValueRef.current = true;
      try {
        view.dispatch(transaction);
      } finally {
        applyingExternalValueRef.current = false;
      }
    }, [protectedTokens, value]);

    useEffect(() => {
      if (!exactValueInsertionId) {
        return;
      }

      window.requestAnimationFrame(() => {
        exactValueInputRef.current?.focus();
        exactValueInputRef.current?.select();
      });
    }, [exactValueInsertionId]);

    useImperativeHandle(
      forwardedRef,
      () => ({
        blur() {
          viewRef.current?.dom.blur();
        },
        focus() {
          viewRef.current?.focus();
        },
        getSelection() {
          const view = viewRef.current;
          const selection = view?.state.selection;
          return {
            start:
              view && selection
                ? rawOffsetFromDocPosition(view.state.doc, selection.from)
                : valueRef.current.length,
            end:
              view && selection
                ? rawOffsetFromDocPosition(view.state.doc, selection.to)
                : valueRef.current.length,
          };
        },
        insertText(text: string) {
          const view = viewRef.current;
          if (!view || disabledRef.current || readOnlyRef.current) {
            return;
          }
          replaceSelectionWithRawText(view, text);
          view.focus();
        },
        redo() {
          const view = viewRef.current;
          if (!view || disabledRef.current || readOnlyRef.current) {
            return false;
          }
          const didRedo = redo(view.state, view.dispatch, view);
          if (didRedo) {
            view.focus();
          }
          return didRedo;
        },
        setSelection(selection: { start: number; end: number }) {
          const view = viewRef.current;
          if (!view) {
            return;
          }
          const start = docPositionFromRawOffset(view.state.doc, Math.max(0, selection.start));
          const end = docPositionFromRawOffset(view.state.doc, Math.max(0, selection.end));
          view.dispatch(
            view.state.tr.setSelection(TextSelection.create(view.state.doc, start, end)),
          );
          view.focus();
        },
        undo() {
          const view = viewRef.current;
          if (!view || disabledRef.current || readOnlyRef.current) {
            return false;
          }
          const didUndo = undo(view.state, view.dispatch, view);
          if (didUndo) {
            view.focus();
          }
          return didUndo;
        },
        wrapSelection(open: string, close: string) {
          const view = viewRef.current;
          if (!view || disabledRef.current || readOnlyRef.current) {
            return;
          }

          const { from, to } = view.state.selection;
          let transaction = view.state.tr.insertText(open, from, from);
          const closePosition = to + open.length;
          transaction = transaction.insertText(close, closePosition, closePosition);

          const nextSelectionStart = from + open.length;
          const nextSelectionEnd = closePosition;
          transaction = transaction.setSelection(
            TextSelection.create(transaction.doc, nextSelectionStart, nextSelectionEnd),
          );
          view.dispatch(transaction);
          view.focus();
        },
      }),
      [],
    );

    const protectedTokenCount = controlBar?.protectedTokenCount ?? 0;
    const protectedTokenLabel =
      controlBar?.protectedTokenLabel ??
      `${protectedTokenCount} protected token${protectedTokenCount === 1 ? '' : 's'}`;
    const controlStatus =
      exactValueError ??
      (controlBar?.rawMode
        ? protectedTokenCount > 0
          ? `Protection paused · ${protectedTokenLabel}`
          : 'Protection paused'
        : protectedTokenCount > 0
          ? protectedTokenLabel
          : 'No protected tokens');
    const activeExactValueInsertion = controlBar?.icuPluralOptionInsertions?.find(
      (insertion) => insertion.id === exactValueInsertionId && insertion.kind === 'exact-value',
    );
    const resetExactValueInput = () => {
      setExactValueInsertionId(null);
      setExactValueDraft('');
      setExactValueError(null);
    };
    const commitExactValueInput = () => {
      if (!activeExactValueInsertion || !controlBar?.onAddIcuPluralOption) {
        resetExactValueInput();
        return;
      }

      const result = controlBar.onAddIcuPluralOption(activeExactValueInsertion.id, exactValueDraft);
      if (result.ok) {
        resetExactValueInput();
        return;
      }

      setExactValueError(result.error);
    };
    const controlBarElement = controlBar ? (
      <div className="visible-text-editor__control-bar" aria-label="Text editor controls">
        {controlBar.onToggleInvisibles ? (
          <button
            type="button"
            className="visible-text-editor__control-button"
            aria-pressed={showInvisibles}
            onMouseDown={(event) => event.preventDefault()}
            onClick={controlBar.onToggleInvisibles}
            title="Toggle spacing and control marks"
          >
            Marks
          </button>
        ) : null}
        {controlBar.onToggleRawMode ? (
          <button
            type="button"
            className="visible-text-editor__control-button"
            aria-pressed={!controlBar.rawMode}
            aria-label={
              controlBar.rawMode
                ? 'Protection is paused. Switch to protected editing'
                : 'Protection is active. Switch to unprotected editing'
            }
            onMouseDown={(event) => event.preventDefault()}
            onClick={controlBar.onToggleRawMode}
            title={
              controlBar.rawMode
                ? 'Protection is paused. Switch to protected editing'
                : 'Protection is active. Switch to unprotected editing'
            }
          >
            {controlBar.rawMode ? 'Unprotected' : 'Protected'}
          </button>
        ) : null}
        {controlBar.onAddIcuPluralOption &&
        controlBar.icuPluralOptionInsertions &&
        controlBar.icuPluralOptionInsertions.length > 0 ? (
          <select
            className="visible-text-editor__control-select"
            aria-label="Add ICU plural form"
            defaultValue=""
            onChange={(event) => {
              const insertionId = event.target.value;
              if (insertionId) {
                const insertion = controlBar.icuPluralOptionInsertions?.find(
                  (item) => item.id === insertionId,
                );
                if (insertion?.kind === 'exact-value') {
                  setExactValueInsertionId(insertionId);
                  setExactValueDraft('');
                  setExactValueError(null);
                } else {
                  const result = controlBar.onAddIcuPluralOption?.(insertionId);
                  if (result && !result.ok) {
                    setExactValueError(result.error);
                  } else {
                    setExactValueError(null);
                  }
                }
              }
              event.currentTarget.value = '';
            }}
            onMouseDown={(event) => event.stopPropagation()}
          >
            <option value="">Add form</option>
            {controlBar.icuPluralOptionInsertions.map((insertion) => (
              <option key={insertion.id} value={insertion.id}>
                {insertion.label}
              </option>
            ))}
          </select>
        ) : null}
        {activeExactValueInsertion ? (
          <span
            className={`visible-text-editor__exact-form${
              exactValueError ? ' visible-text-editor__exact-form--error' : ''
            }`}
            title={exactValueError ?? 'Add an exact ICU plural selector'}
          >
            <span className="visible-text-editor__exact-form-prefix" aria-hidden="true">
              =
            </span>
            <input
              ref={exactValueInputRef}
              className="visible-text-editor__exact-form-input"
              aria-label="Exact ICU plural value"
              aria-invalid={exactValueError ? 'true' : undefined}
              inputMode="numeric"
              pattern="[0-9]*"
              placeholder="0"
              value={exactValueDraft}
              onChange={(event) => {
                setExactValueDraft(event.target.value);
                setExactValueError(null);
              }}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault();
                  commitExactValueInput();
                }
                if (event.key === 'Escape') {
                  event.preventDefault();
                  resetExactValueInput();
                  viewRef.current?.focus();
                }
              }}
              onMouseDown={(event) => event.stopPropagation()}
            />
            <button
              type="button"
              className="visible-text-editor__exact-form-button"
              onMouseDown={(event) => event.preventDefault()}
              onClick={commitExactValueInput}
            >
              Add
            </button>
            <button
              type="button"
              className="visible-text-editor__exact-form-button visible-text-editor__exact-form-button--icon"
              aria-label="Cancel exact plural value"
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => {
                resetExactValueInput();
                viewRef.current?.focus();
              }}
            >
              x
            </button>
          </span>
        ) : null}
        <span
          className={`visible-text-editor__control-status${
            exactValueError ? ' visible-text-editor__control-status--error' : ''
          }`}
          aria-live="polite"
          title={controlStatus}
        >
          {controlStatus}
        </span>
      </div>
    ) : null;

    return (
      <div className={editorClassName} style={style}>
        {controlBar?.position === 'top' ? controlBarElement : null}
        <div className="visible-text-editor__surface" ref={mountRef} />
        {controlBar?.position === 'top' ? null : controlBarElement}
      </div>
    );
  },
);
