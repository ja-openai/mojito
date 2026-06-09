import './visible-text-editor.css';

import { baseKeymap } from 'prosemirror-commands';
import { dropCursor } from 'prosemirror-dropcursor';
import { history, redo, undo } from 'prosemirror-history';
import { keymap } from 'prosemirror-keymap';
import { Fragment, type Node as ProseMirrorNode, Schema, Slice } from 'prosemirror-model';
import { EditorState, NodeSelection, Plugin, PluginKey, TextSelection } from 'prosemirror-state';
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

import type {
  IcuFormInsertion,
  ProtectedTextDiagnostic,
  ProtectedTextMovableRange,
  ProtectedTextToken,
} from '../utils/protectedTextTokens';
import { getVisibleTextMarker } from '../utils/textCharacters';
import {
  resolveVisibleTextMarksMode,
  shouldRenderVisibleTextWidget,
  shouldShowVisibleTextIssueMarker,
  visibleTextMarkerClassFor,
  type VisibleTextMarksMode,
  visibleTextWidgetText,
} from './visibleTextFormatting';

export type { VisibleTextMarksMode } from './visibleTextFormatting';

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

type AddIcuFormResult =
  | {
      ok: true;
      nextValue?: string;
      selectionEnd?: number;
      selectionStart?: number;
    }
  | { ok: false; error: string };

export type VisibleTextCompletionOption = {
  detail?: string;
  id: string;
  label: string;
};

type Props = {
  value: string;
  onChange: (nextValue: string) => void;
  marksMode?: VisibleTextMarksMode;
  showInvisibles?: boolean;
  ariaLabel?: string;
  className?: string;
  controlBar?: {
    icuFormInsertions?: IcuFormInsertion[];
    marksMode?: VisibleTextMarksMode;
    onAddIcuForm?: (insertionId: string, exactValue?: string) => AddIcuFormResult;
    onChangeMarksMode?: (mode: VisibleTextMarksMode) => void;
    onToggleRawMode?: () => void;
    position?: 'bottom' | 'top';
    rawMode?: boolean;
    protectedTokenCount?: number;
  };
  completion?: {
    ariaLabel?: string;
    onApply: (option: VisibleTextCompletionOption) => void;
    options: VisibleTextCompletionOption[];
  };
  dir?: 'ltr' | 'rtl' | 'auto';
  disabled?: boolean;
  lang?: string;
  onFocus?: () => void;
  onKeyDown?: (event: KeyboardEvent) => void;
  onSelectionChange?: (selection: { start: number; end: number }) => void;
  placeholder?: string;
  movableProtectedRanges?: ProtectedTextMovableRange[];
  protectedTokens?: ProtectedTextToken[];
  protectedDiagnostics?: ProtectedTextDiagnostic[];
  readOnly?: boolean;
  spellCheck?: boolean;
  style?: CSSProperties;
  validateNextValue?: (nextValue: string) => boolean;
};

type VisibleTextPluginState = {
  diagnostics: ProtectedTextDiagnostic[];
  marksMode: VisibleTextMarksMode;
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
  disabled: boolean;
  dir: 'ltr' | 'rtl' | 'auto';
  lang?: string;
  placeholder?: string;
  readOnly: boolean;
  spellCheck: boolean;
};

type ProtectedDragRange = {
  from: number;
  raw: string;
  to: number;
};

const visibleTextPluginKey = new PluginKey<VisibleTextPluginState>('visible-text-editor');
const externalValueMetaKey = 'visible-text-editor-external-value';
const allowedProtectedDeletionMetaKey = 'visible-text-editor-allowed-protected-deletion';
const allowedProtectedMoveMetaKey = 'visible-text-editor-allowed-protected-move';
const DROP_ALLOWED_CLASS = 'visible-text-editor__editor--drop-allowed';
const DROP_BLOCKED_CLASS = 'visible-text-editor__editor--drop-blocked';
const TOKEN_DROP_BOUNDARY_CLASS = 'visible-text-editor__protected-token--drop-boundary';
const TOKEN_DROP_BLOCKED_CLASS = 'visible-text-editor__protected-token--drop-blocked';
const protectedEditBlockedMessage =
  'Placeholder edit blocked. Use Edit placeholders to change placeholders or tags.';
const marksModeOptions: { value: VisibleTextMarksMode; label: string }[] = [
  { value: 'auto', label: 'Auto' },
  { value: 'all', label: 'All' },
  { value: 'off', label: 'Off' },
];

function getIcuFormInsertionMessageType(insertion: IcuFormInsertion): 'plural' | 'select' | null {
  if (insertion.messageType) {
    return insertion.messageType;
  }
  return insertion.kind === 'exact-value' ? 'plural' : null;
}

function getAddIcuFormLabel(insertions?: IcuFormInsertion[]): string {
  const messageTypes = new Set(
    insertions
      ?.map(getIcuFormInsertionMessageType)
      .filter((type): type is 'plural' | 'select' => type !== null) ?? [],
  );

  if (messageTypes.size === 1) {
    return messageTypes.has('select') ? 'Add select form' : 'Add plural form';
  }

  return 'Add ICU form';
}

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
      draggable: true,
      selectable: true,
      disableDropCursor: true,
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
            contenteditable: 'false',
            'data-raw': attrs.raw,
            draggable: 'true',
            title: attrs.label,
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

function containsProtectedTokenNode(fragment: Fragment): boolean {
  let containsProtectedToken = false;
  fragment.descendants((node) => {
    if (node.type.name === 'protectedToken') {
      containsProtectedToken = true;
      return false;
    }
    return !containsProtectedToken;
  });
  return containsProtectedToken;
}

function eventTargetIsProtectedToken(target: EventTarget | null): boolean {
  return (
    target instanceof Element && Boolean(target.closest('.visible-text-editor__protected-token'))
  );
}

function protectedTokenElementFromEventTarget(target: EventTarget | null): HTMLElement | null {
  return target instanceof Element ? target.closest('.visible-text-editor__protected-token') : null;
}

function protectedTokenDropTargetFromDragEvent(
  view: EditorView,
  event: DragEvent,
): HTMLElement | null {
  const pointTarget =
    typeof document.elementFromPoint === 'function'
      ? document.elementFromPoint(event.clientX, event.clientY)
      : null;
  const tokenElement =
    protectedTokenElementFromEventTarget(pointTarget) ??
    protectedTokenElementFromEventTarget(event.target);
  return tokenElement && view.dom.contains(tokenElement) ? tokenElement : null;
}

function clearDropTargetState(view: EditorView) {
  view.dom.classList.remove(DROP_ALLOWED_CLASS, DROP_BLOCKED_CLASS);
  view.dom
    .querySelectorAll(`.${TOKEN_DROP_BLOCKED_CLASS}, .${TOKEN_DROP_BOUNDARY_CLASS}`)
    .forEach((element) => {
      element.classList.remove(TOKEN_DROP_BLOCKED_CLASS, TOKEN_DROP_BOUNDARY_CLASS);
    });
}

function updateDropTargetState(
  view: EditorView,
  event: DragEvent,
  allowTokenBoundaryDrop = false,
): boolean {
  const tokenElement = protectedTokenDropTargetFromDragEvent(view, event);
  clearDropTargetState(view);

  if (!tokenElement) {
    view.dom.classList.add(DROP_ALLOWED_CLASS);
    return false;
  }

  if (allowTokenBoundaryDrop) {
    view.dom.classList.add(DROP_ALLOWED_CLASS);
    tokenElement.classList.add(TOKEN_DROP_BOUNDARY_CLASS);
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'move';
    }
    event.preventDefault();
    return true;
  }

  view.dom.classList.add(DROP_BLOCKED_CLASS);
  tokenElement.classList.add(TOKEN_DROP_BLOCKED_CLASS);
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'none';
  }
  event.preventDefault();
  return true;
}

function protectedTokenPositionFromElement(view: EditorView, element: HTMLElement): number | null {
  const raw = element.getAttribute('data-raw') ?? '';
  const basePosition = view.posAtDOM(element, 0);

  for (const position of [basePosition, basePosition - 1, basePosition + 1]) {
    if (position < 0 || position > view.state.doc.content.size) {
      continue;
    }

    const node = view.state.doc.nodeAt(position);
    if (node?.type.name === 'protectedToken' && String(node.attrs.raw ?? '') === raw) {
      return position;
    }
  }

  return null;
}

function sliceMatchesMovableProtectedRange(
  doc: ProseMirrorNode,
  slice: Slice,
  movableRanges: ProtectedTextMovableRange[],
): boolean {
  const rawText = textFromFragment(slice.content);
  if (!rawText) {
    return false;
  }

  const currentValue = textFromDoc(doc);
  return movableRanges.some((range) => currentValue.slice(range.start, range.end) === rawText);
}

function containingMovableRangeForIcuSyntaxToken(
  doc: ProseMirrorNode,
  tokenPosition: number,
  movableRanges: ProtectedTextMovableRange[],
): ProtectedTextMovableRange | null {
  const token = doc.nodeAt(tokenPosition);
  if (token?.type.name !== 'protectedToken' || token.attrs.kind !== 'icu-syntax') {
    return null;
  }

  const rawStart = rawOffsetFromDocPosition(doc, tokenPosition);
  const rawEnd = rawOffsetFromDocPosition(doc, tokenPosition + token.nodeSize);
  const containingRanges = movableRanges.filter(
    (range) => range.start <= rawStart && range.end >= rawEnd && range.end > range.start,
  );
  if (containingRanges.length === 0) {
    return null;
  }

  return containingRanges.reduce((smallest, range) =>
    range.end - range.start < smallest.end - smallest.start ? range : smallest,
  );
}

function isDropAfterElement(event: DragEvent, element: HTMLElement): boolean {
  const rect = element.getBoundingClientRect();
  const clientY = Number.isFinite(event.clientY) ? event.clientY : rect.bottom;
  if (rect.height > 0) {
    if (clientY < rect.top) {
      return false;
    }
    if (clientY > rect.bottom) {
      return true;
    }
  }

  const clientX = Number.isFinite(event.clientX) ? event.clientX : rect.right;
  return rect.width <= 0 || clientX >= rect.left + rect.width / 2;
}

function protectedTokenBoundaryDropPositionFromDragEvent(
  view: EditorView,
  event: DragEvent,
  tokenElement: HTMLElement,
  movableRanges: ProtectedTextMovableRange[],
): number | null {
  const tokenPosition = protectedTokenPositionFromElement(view, tokenElement);
  if (tokenPosition === null) {
    return null;
  }

  const token = view.state.doc.nodeAt(tokenPosition);
  if (token?.type.name !== 'protectedToken') {
    return null;
  }

  const dropAfter = isDropAfterElement(event, tokenElement);
  if (token.attrs.kind === 'icu-syntax') {
    const containingRange = containingMovableRangeForIcuSyntaxToken(
      view.state.doc,
      tokenPosition,
      movableRanges,
    );
    if (containingRange) {
      return docPositionFromRawOffset(
        view.state.doc,
        dropAfter ? containingRange.end : containingRange.start,
      );
    }
  }

  return dropAfter ? tokenPosition + token.nodeSize : tokenPosition;
}

function selectedProtectedTokens(
  doc: ProseMirrorNode,
  from: number,
  to: number,
): Array<{
  end: number;
  fullyCovered: boolean;
  kind: string;
  raw: string;
  start: number;
}> {
  const tokens: Array<{
    end: number;
    fullyCovered: boolean;
    kind: string;
    raw: string;
    start: number;
  }> = [];

  doc.descendants((node, pos) => {
    if (node.type.name !== 'protectedToken') {
      return true;
    }

    const tokenStart = pos;
    const tokenEnd = pos + node.nodeSize;
    if (from >= tokenEnd || to <= tokenStart) {
      return false;
    }

    tokens.push({
      end: tokenEnd,
      fullyCovered: from <= tokenStart && to >= tokenEnd,
      kind: String(node.attrs.kind ?? ''),
      raw: String(node.attrs.raw ?? ''),
      start: tokenStart,
    });
    return false;
  });

  return tokens;
}

function selectionMatchesMovableProtectedRange(
  doc: ProseMirrorNode,
  selection: EditorState['selection'],
  movableRanges: ProtectedTextMovableRange[],
): boolean {
  if (selection.empty) {
    return false;
  }

  const rawStart = rawOffsetFromDocPosition(doc, selection.from);
  const rawEnd = rawOffsetFromDocPosition(doc, selection.to);
  return movableRanges.some((range) => range.start === rawStart && range.end === rawEnd);
}

function canDragProtectedSelection(
  doc: ProseMirrorNode,
  selection: EditorState['selection'],
  movableRanges: ProtectedTextMovableRange[],
): boolean {
  if (selection.empty || selectionMatchesMovableProtectedRange(doc, selection, movableRanges)) {
    return true;
  }

  const tokens = selectedProtectedTokens(doc, selection.from, selection.to);
  if (tokens.length === 0) {
    return true;
  }

  return tokens.every((token) => token.fullyCovered && token.kind !== 'icu-syntax');
}

function protectedDragRangeForSelection(
  doc: ProseMirrorNode,
  selection: EditorState['selection'],
  movableRanges: ProtectedTextMovableRange[],
): ProtectedDragRange | null {
  if (selection.empty || !canDragProtectedSelection(doc, selection, movableRanges)) {
    return null;
  }

  const protectedTokens = selectedProtectedTokens(doc, selection.from, selection.to);
  if (
    protectedTokens.length === 0 &&
    !selectionMatchesMovableProtectedRange(doc, selection, movableRanges)
  ) {
    return null;
  }

  return {
    from: selection.from,
    raw: textFromDoc(doc).slice(
      rawOffsetFromDocPosition(doc, selection.from),
      rawOffsetFromDocPosition(doc, selection.to),
    ),
    to: selection.to,
  };
}

function dropPositionFromDragEvent(view: EditorView, event: DragEvent): number | null {
  const coords = view.posAtCoords({ left: event.clientX, top: event.clientY });
  return coords ? clampPosition(coords.pos, view.state.doc) : null;
}

function moveProtectedDragRange(
  view: EditorView,
  dragRange: ProtectedDragRange,
  dropPosition: number,
): boolean {
  const from = clampPosition(dragRange.from, view.state.doc);
  const to = clampPosition(dragRange.to, view.state.doc);
  const dropPos = clampPosition(dropPosition, view.state.doc);
  if (from >= to || (dropPos >= from && dropPos <= to)) {
    return true;
  }

  const slice = view.state.doc.slice(from, to);
  const insertPosition = dropPos > to ? dropPos - (to - from) : dropPos;
  const insertedSize = slice.content.size;
  let transaction = view.state.tr
    .delete(from, to)
    .replace(insertPosition, insertPosition, slice)
    .setMeta(allowedProtectedMoveMetaKey, true)
    .scrollIntoView();
  transaction = transaction.setSelection(
    TextSelection.create(transaction.doc, insertPosition, insertPosition + insertedSize),
  );
  view.dispatch(transaction);
  return true;
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

function selectedProtectedTokenCoverage(
  doc: ProseMirrorNode,
  from: number,
  to: number,
): { containsProtectedToken: boolean; fullyCoversTouchedTokens: boolean } {
  let containsProtectedToken = false;
  let fullyCoversTouchedTokens = true;

  doc.descendants((node, pos) => {
    if (node.type.name !== 'protectedToken') {
      return true;
    }

    const tokenStart = pos;
    const tokenEnd = pos + node.nodeSize;
    const touchesToken = from < tokenEnd && to > tokenStart;
    if (!touchesToken) {
      return false;
    }

    const fullyCovered = from <= tokenStart && to >= tokenEnd;
    containsProtectedToken = containsProtectedToken || fullyCovered;
    fullyCoversTouchedTokens = fullyCoversTouchedTokens && fullyCovered;
    return false;
  });

  return { containsProtectedToken, fullyCoversTouchedTokens };
}

function isWholeProtectedSelectionDeletion(
  beforeDoc: ProseMirrorNode,
  afterDoc: ProseMirrorNode,
  selection: EditorState['selection'],
): boolean {
  if (selection.empty) {
    return false;
  }

  const coverage = selectedProtectedTokenCoverage(beforeDoc, selection.from, selection.to);
  if (!coverage.containsProtectedToken || !coverage.fullyCoversTouchedTokens) {
    return false;
  }

  const beforeValue = textFromDoc(beforeDoc);
  const afterValue = textFromDoc(afterDoc);
  const rawStart = rawOffsetFromDocPosition(beforeDoc, selection.from);
  const rawEnd = rawOffsetFromDocPosition(beforeDoc, selection.to);
  return afterValue === `${beforeValue.slice(0, rawStart)}${beforeValue.slice(rawEnd)}`;
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
    .map(
      (token) =>
        `${token.kind}:${token.label}:${token.start}:${token.end}:${value.slice(
          token.start,
          token.end,
        )}`,
    )
    .join('|');
}

function protectedTokenSignatureFromDoc(doc: ProseMirrorNode): string {
  const value = textFromDoc(doc);
  const tokens: ProtectedTextToken[] = [];

  doc.descendants((node, pos) => {
    if (node.type.name !== 'protectedToken') {
      return true;
    }

    const start = rawOffsetFromDocPosition(doc, pos);
    const end = rawOffsetFromDocPosition(doc, pos + node.nodeSize);
    tokens.push({
      start,
      end,
      label: stringAttribute(node.attrs.label),
      kind: stringAttribute(node.attrs.kind) as ProtectedTextToken['kind'],
    });
    return false;
  });

  return protectedTokenSignature(value, tokens);
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
  if (attributes.disabled) {
    view.dom.setAttribute('aria-disabled', 'true');
  } else {
    view.dom.removeAttribute('aria-disabled');
  }
  view.dom.setAttribute('aria-label', attributes.ariaLabel);
  view.dom.setAttribute('aria-multiline', 'true');
  view.dom.setAttribute('aria-readonly', attributes.readOnly ? 'true' : 'false');
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

function createMarkerWidget(char: string, markerText: string, label: string): HTMLElement {
  const element = document.createElement('span');
  element.className = `visible-text-editor__marker-widget visible-text-editor__marker-widget--${visibleTextMarkerClassFor(
    char,
  )}`;
  element.textContent = visibleTextWidgetText(markerText);
  element.setAttribute('aria-hidden', 'true');
  element.setAttribute('contenteditable', 'false');
  element.setAttribute('title', label);
  return element;
}

function replaceSelectionWithRawText(view: EditorView, text: string) {
  view.dispatch(view.state.tr.replaceSelection(new Slice(fragmentFromRawText(text), 0, 0)));
}

function replaceEditorValueWithRawText(
  view: EditorView,
  nextValue: string,
  protectedTokens: ProtectedTextToken[],
  selectionStart = nextValue.length,
  selectionEnd = selectionStart,
) {
  const nextDoc = docFromText(nextValue, protectedTokens);
  let transaction = view.state.tr.replaceWith(0, view.state.doc.content.size, nextDoc.content);
  const selectionFrom = docPositionFromRawOffset(transaction.doc, selectionStart);
  const selectionTo = docPositionFromRawOffset(transaction.doc, selectionEnd);
  transaction = transaction
    .setSelection(TextSelection.create(transaction.doc, selectionFrom, selectionTo))
    .scrollIntoView();
  view.dispatch(transaction);
}

function buildVisibleDecorations(
  doc: ProseMirrorNode,
  marksMode: VisibleTextMarksMode,
  diagnostics: ProtectedTextDiagnostic[] = [],
): DecorationSet {
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
          to: pos,
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

  diagnostics.forEach((diagnostic) => {
    const from = docPositionFromRawOffset(doc, diagnostic.start);
    const to = docPositionFromRawOffset(doc, diagnostic.end);
    if (to <= from) {
      return;
    }

    decorations.push(
      Decoration.inline(
        from,
        to,
        {
          class: `visible-text-editor__diagnostic visible-text-editor__diagnostic--${diagnostic.severity} visible-text-editor__diagnostic--${diagnostic.code}`,
          title: diagnostic.message,
        },
        {
          inclusiveEnd: false,
          inclusiveStart: false,
        },
      ),
    );
  });

  markerItems.forEach((item) => {
    if (marksMode === 'off') {
      return;
    }

    const { char, from, marker, to } = item;
    if (marksMode === 'auto' && !shouldShowVisibleTextIssueMarker(value, item)) {
      return;
    }

    if (shouldRenderVisibleTextWidget(char, marker.text)) {
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
  });

  return DecorationSet.create(doc, decorations);
}

function insertLineBreak(
  state: EditorState,
  dispatch?: (tr: EditorState['tr']) => void,
  view?: EditorView,
): boolean {
  if (view && !view.editable) {
    return false;
  }

  dispatch?.(
    state.tr
      .replaceSelectionWith(state.schema.nodes.hardBreak.create({ raw: '\n' }))
      .scrollIntoView(),
  );
  return true;
}

function canDeleteProtectedTokenAtCaret(node: ProseMirrorNode | null | undefined): boolean {
  return node?.type.name === 'protectedToken' && node.attrs.kind !== 'icu-syntax';
}

function deleteAdjacentProtectedToken(
  direction: 'backward' | 'forward',
  onBlockedEdit?: () => void,
) {
  return (state: EditorState, dispatch?: (tr: EditorState['tr']) => void, view?: EditorView) => {
    if (view && !view.editable) {
      return false;
    }

    const { selection } = state;
    if (!selection.empty) {
      return false;
    }

    const adjacentNode =
      direction === 'backward' ? selection.$from.nodeBefore : selection.$from.nodeAfter;
    if (!adjacentNode) {
      return false;
    }
    if (
      adjacentNode.type.name === 'protectedToken' &&
      !canDeleteProtectedTokenAtCaret(adjacentNode)
    ) {
      onBlockedEdit?.();
      return true;
    }
    if (!canDeleteProtectedTokenAtCaret(adjacentNode)) {
      return false;
    }

    const from = direction === 'backward' ? selection.from - adjacentNode.nodeSize : selection.from;
    const to = direction === 'backward' ? selection.from : selection.from + adjacentNode.nodeSize;
    dispatch?.(
      state.tr.delete(from, to).setMeta(allowedProtectedDeletionMetaKey, true).scrollIntoView(),
    );
    return true;
  };
}

function createVisibleTextPlugin(
  marksMode: VisibleTextMarksMode,
  diagnostics: ProtectedTextDiagnostic[],
  onBlockedEdit?: () => void,
  movableRangesRef?: { current: ProtectedTextMovableRange[] },
): Plugin<VisibleTextPluginState> {
  let protectedDragRange: ProtectedDragRange | null = null;

  return new Plugin<VisibleTextPluginState>({
    key: visibleTextPluginKey,
    filterTransaction(transaction, state) {
      if (!transaction.docChanged || transaction.getMeta(externalValueMetaKey)) {
        return true;
      }
      const preservesTokens = preservesProtectedTokens(state.doc, transaction.doc);
      const isAllowedProtectedDeletion = isWholeProtectedSelectionDeletion(
        state.doc,
        transaction.doc,
        state.selection,
      );
      const isAllowedAdjacentProtectedDeletion = Boolean(
        transaction.getMeta(allowedProtectedDeletionMetaKey),
      );
      if (!preservesTokens && !isAllowedProtectedDeletion && !isAllowedAdjacentProtectedDeletion) {
        onBlockedEdit?.();
      }
      return preservesTokens || isAllowedProtectedDeletion || isAllowedAdjacentProtectedDeletion;
    },
    props: {
      decorations(state) {
        const pluginState = visibleTextPluginKey.getState(state);
        if (!pluginState) {
          return null;
        }
        return buildVisibleDecorations(state.doc, pluginState.marksMode, pluginState.diagnostics);
      },
      handlePaste(view, event) {
        if (!view.editable) {
          return false;
        }

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
      handleDrop(view, event, slice, moved) {
        clearDropTargetState(view);
        if (!view.editable) {
          return false;
        }

        const protectedDropTarget = protectedTokenDropTargetFromDragEvent(view, event);
        if (protectedDragRange) {
          const dropPosition = protectedDropTarget
            ? protectedTokenBoundaryDropPositionFromDragEvent(
                view,
                event,
                protectedDropTarget,
                movableRangesRef?.current ?? [],
              )
            : dropPositionFromDragEvent(view, event);
          if (dropPosition !== null) {
            const movedProtectedRange = protectedDragRange;
            protectedDragRange = null;
            event.preventDefault();
            return moveProtectedDragRange(view, movedProtectedRange, dropPosition);
          }

          protectedDragRange = null;
          event.preventDefault();
          onBlockedEdit?.();
          return true;
        }
        protectedDragRange = null;

        const containsProtectedToken =
          Boolean(protectedDropTarget) ||
          eventTargetIsProtectedToken(event.target) ||
          containsProtectedTokenNode(slice.content);
        const isMovableProtectedSlice = sliceMatchesMovableProtectedRange(
          view.state.doc,
          slice,
          movableRangesRef?.current ?? [],
        );
        const isMovedProtectedSlice =
          moved &&
          containsProtectedTokenNode(slice.content) &&
          canDragProtectedSelection(
            view.state.doc,
            view.state.selection,
            movableRangesRef?.current ?? [],
          );
        if (containsProtectedToken && !isMovedProtectedSlice && !isMovableProtectedSlice) {
          event.preventDefault();
          onBlockedEdit?.();
          return true;
        }
        return false;
      },
      handleDOMEvents: {
        dragenter(view, event) {
          if (!view.editable) {
            return false;
          }
          return updateDropTargetState(view, event, Boolean(protectedDragRange));
        },
        dragover(view, event) {
          if (!view.editable) {
            return false;
          }
          return updateDropTargetState(view, event, Boolean(protectedDragRange));
        },
        dragleave(view, event) {
          const relatedTarget = event.relatedTarget;
          if (!(relatedTarget instanceof Node) || !view.dom.contains(relatedTarget)) {
            clearDropTargetState(view);
          }
          return false;
        },
        dragend(view) {
          protectedDragRange = null;
          clearDropTargetState(view);
          return false;
        },
        dragstart(view, event) {
          if (!view.editable) {
            return false;
          }

          const tokenElement = protectedTokenElementFromEventTarget(event.target);
          if (!tokenElement) {
            if (
              !canDragProtectedSelection(
                view.state.doc,
                view.state.selection,
                movableRangesRef?.current ?? [],
              )
            ) {
              event.preventDefault();
              onBlockedEdit?.();
              return true;
            }
            return false;
          }

          const tokenPosition = protectedTokenPositionFromElement(view, tokenElement);
          if (tokenPosition === null) {
            return false;
          }

          const { selection } = view.state;
          const alreadyDraggingSelection =
            !selection.empty && tokenPosition >= selection.from && tokenPosition < selection.to;
          const token = view.state.doc.nodeAt(tokenPosition);
          if (!alreadyDraggingSelection) {
            const movableRange = containingMovableRangeForIcuSyntaxToken(
              view.state.doc,
              tokenPosition,
              movableRangesRef?.current ?? [],
            );
            if (!movableRange && token?.attrs.kind === 'icu-syntax') {
              event.preventDefault();
              onBlockedEdit?.();
              return true;
            }
            const nextSelection = movableRange
              ? TextSelection.create(
                  view.state.doc,
                  docPositionFromRawOffset(view.state.doc, movableRange.start),
                  docPositionFromRawOffset(view.state.doc, movableRange.end),
                )
              : NodeSelection.create(view.state.doc, tokenPosition);
            view.dispatch(view.state.tr.setSelection(nextSelection));
          } else if (
            !canDragProtectedSelection(view.state.doc, selection, movableRangesRef?.current ?? [])
          ) {
            event.preventDefault();
            onBlockedEdit?.();
            return true;
          }

          const raw = textFromDoc(view.state.doc).slice(
            rawOffsetFromDocPosition(view.state.doc, view.state.selection.from),
            rawOffsetFromDocPosition(view.state.doc, view.state.selection.to),
          );
          protectedDragRange = protectedDragRangeForSelection(
            view.state.doc,
            view.state.selection,
            movableRangesRef?.current ?? [],
          );
          if (raw && event.dataTransfer) {
            event.dataTransfer.effectAllowed = 'move';
            event.dataTransfer.setData('text/plain', raw);
          }
          return false;
        },
      },
    },
    state: {
      init: () => ({ diagnostics, marksMode }),
      apply(tr, current) {
        const next = tr.getMeta(visibleTextPluginKey) as
          | Partial<VisibleTextPluginState>
          | undefined;
        if (next) {
          return {
            diagnostics: next.diagnostics ?? current.diagnostics,
            marksMode: next.marksMode ?? current.marksMode,
          };
        }
        return current;
      },
    },
  });
}

function createPlugins(
  marksMode: VisibleTextMarksMode,
  diagnostics: ProtectedTextDiagnostic[],
  onBlockedEdit?: () => void,
  movableRangesRef?: { current: ProtectedTextMovableRange[] },
) {
  return [
    keymap({
      Backspace: deleteAdjacentProtectedToken('backward', onBlockedEdit),
      Delete: deleteAdjacentProtectedToken('forward', onBlockedEdit),
      Enter: insertLineBreak,
      'Mod-z': undo,
      'Meta-z': undo,
      'Ctrl-z': undo,
      'Mod-y': redo,
      'Meta-y': redo,
      'Ctrl-y': redo,
      'Shift-Mod-z': redo,
      'Shift-Meta-z': redo,
      'Shift-Ctrl-z': redo,
    }),
    history(),
    keymap(baseKeymap),
    dropCursor({ color: false, width: 2, class: 'visible-text-editor__drop-cursor' }),
    createVisibleTextPlugin(marksMode, diagnostics, onBlockedEdit, movableRangesRef),
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
      marksMode,
      showInvisibles,
      ariaLabel = 'Text editor',
      className,
      completion,
      controlBar,
      dir = 'auto',
      disabled = false,
      lang,
      onFocus,
      onKeyDown,
      onSelectionChange,
      placeholder,
      movableProtectedRanges,
      protectedDiagnostics,
      protectedTokens,
      readOnly = false,
      spellCheck = true,
      style,
      validateNextValue,
    },
    forwardedRef,
  ) {
    const rootRef = useRef<HTMLDivElement | null>(null);
    const mountRef = useRef<HTMLDivElement | null>(null);
    const viewRef = useRef<EditorView | null>(null);
    const exactValueInputRef = useRef<HTMLInputElement | null>(null);
    const onChangeRef = useRef(onChange);
    const onFocusRef = useRef(onFocus);
    const onKeyDownRef = useRef(onKeyDown);
    const onSelectionChangeRef = useRef(onSelectionChange);
    const completionRef = useRef(completion);
    const valueRef = useRef(value);
    const disabledRef = useRef(disabled);
    const readOnlyRef = useRef(readOnly);
    const resolvedMarksMode = resolveVisibleTextMarksMode(marksMode, showInvisibles);
    const initialMarksModeRef = useRef(resolvedMarksMode);
    const initialDiagnosticsRef = useRef(protectedDiagnostics ?? []);
    const movableRangesRef = useRef(movableProtectedRanges ?? []);
    const protectedTokensRef = useRef(protectedTokens ?? []);
    const validateNextValueRef = useRef(validateNextValue);
    const protectedTokenSignatureRef = useRef(
      protectedTokenSignature(value, normalizeProtectedTokens(value, protectedTokens ?? [])),
    );
    const domAttributesRef = useRef<EditorDomAttributes>({
      ariaLabel,
      disabled,
      dir,
      lang,
      placeholder,
      readOnly,
      spellCheck,
    });
    const applyingExternalValueRef = useRef(false);
    const selectionSignatureRef = useRef('');
    const completionIndexRef = useRef(0);
    const completionOpenRef = useRef(false);
    const [exactValueInsertionId, setExactValueInsertionId] = useState<string | null>(null);
    const [exactValueDraft, setExactValueDraft] = useState('');
    const [exactValueError, setExactValueError] = useState<string | null>(null);
    const [blockedEditMessage, setBlockedEditMessage] = useState<string | null>(null);
    const [completionIndex, setCompletionIndex] = useState(0);
    const [completionPosition, setCompletionPosition] = useState<{
      left: number;
      top: number;
    } | null>(null);
    const [isMarksMenuOpen, setIsMarksMenuOpen] = useState(false);
    const marksMenuRef = useRef<HTMLDivElement | null>(null);
    onChangeRef.current = onChange;
    onFocusRef.current = onFocus;
    onKeyDownRef.current = onKeyDown;
    onSelectionChangeRef.current = onSelectionChange;
    completionRef.current = completion;
    valueRef.current = value;
    disabledRef.current = disabled;
    readOnlyRef.current = readOnly;
    movableRangesRef.current = movableProtectedRanges ?? [];
    protectedTokensRef.current = protectedTokens ?? [];
    validateNextValueRef.current = validateNextValue;
    domAttributesRef.current = {
      ariaLabel,
      disabled,
      dir,
      lang,
      placeholder,
      readOnly,
      spellCheck,
    };
    completionIndexRef.current = completionIndex;

    const editorClassName = useMemo(
      () =>
        `visible-text-editor${controlBar ? ' visible-text-editor--with-control-bar' : ''}${
          controlBar?.position === 'top' ? ' visible-text-editor--control-bar-top' : ''
        }${disabled ? ' visible-text-editor--disabled' : ''}${
          readOnly ? ' visible-text-editor--read-only' : ''
        }${
          isMarksMenuOpen || (completion?.options.length ?? 0) > 0
            ? ' visible-text-editor--menu-open'
            : ''
        }${className ? ` ${className}` : ''}`,
      [className, completion?.options.length, controlBar, disabled, isMarksMenuOpen, readOnly],
    );

    const updateCompletionPosition = () => {
      const view = viewRef.current;
      const root = rootRef.current;
      const options = completionRef.current?.options ?? [];
      if (!view || !root || options.length === 0 || disabledRef.current || readOnlyRef.current) {
        completionOpenRef.current = false;
        setCompletionPosition(null);
        return;
      }

      const coords = view.coordsAtPos(view.state.selection.to);
      const rootRect = root.getBoundingClientRect();
      const left = Math.max(
        8,
        Math.min(coords.left - rootRect.left, Math.max(8, rootRect.width - 220)),
      );
      completionOpenRef.current = true;
      setCompletionPosition({
        left,
        top: Math.max(8, coords.bottom - rootRect.top + 4),
      });
    };

    const applyCompletion = (option: VisibleTextCompletionOption) => {
      completionRef.current?.onApply(option);
      completionIndexRef.current = 0;
      completionOpenRef.current = false;
      setCompletionIndex(0);
      setCompletionPosition(null);
    };

    useEffect(() => {
      const mount = mountRef.current;
      if (!mount || viewRef.current) {
        return;
      }

      const showBlockedEditMessage = () => {
        setBlockedEditMessage(protectedEditBlockedMessage);
      };
      const view = new EditorView(mount, {
        state: EditorState.create({
          doc: docFromText(valueRef.current, protectedTokensRef.current),
          schema: textEditorSchema,
          plugins: createPlugins(
            initialMarksModeRef.current,
            initialDiagnosticsRef.current,
            showBlockedEditMessage,
            movableRangesRef,
          ),
        }),
        dispatchTransaction(transaction) {
          if (
            transaction.docChanged &&
            !transaction.getMeta(externalValueMetaKey) &&
            validateNextValueRef.current &&
            !isWholeProtectedSelectionDeletion(
              view.state.doc,
              transaction.doc,
              view.state.selection,
            ) &&
            !transaction.getMeta(allowedProtectedDeletionMetaKey) &&
            !transaction.getMeta(allowedProtectedMoveMetaKey) &&
            !validateNextValueRef.current(textFromDoc(transaction.doc))
          ) {
            showBlockedEditMessage();
            return;
          }

          const nextState = view.state.apply(transaction);
          view.updateState(nextState);
          applyEditorDomAttributes(view, domAttributesRef.current);
          notifySelectionChange(view, selectionSignatureRef, onSelectionChangeRef.current);
          window.requestAnimationFrame(updateCompletionPosition);

          if (!transaction.docChanged || applyingExternalValueRef.current) {
            return;
          }

          const nextValue = textFromDoc(nextState.doc);
          if (nextValue === valueRef.current) {
            return;
          }

          setBlockedEditMessage(null);
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
            const options = completionRef.current?.options ?? [];
            if (options.length > 0 && completionOpenRef.current) {
              if (event.key === 'ArrowDown') {
                event.preventDefault();
                setCompletionIndex((current) => {
                  const next = (current + 1) % options.length;
                  completionIndexRef.current = next;
                  return next;
                });
                return true;
              }
              if (event.key === 'ArrowUp') {
                event.preventDefault();
                setCompletionIndex((current) => {
                  const next = (current - 1 + options.length) % options.length;
                  completionIndexRef.current = next;
                  return next;
                });
                return true;
              }
              if (event.key === 'Enter' || event.key === 'Tab') {
                event.preventDefault();
                applyCompletion(options[Math.min(completionIndexRef.current, options.length - 1)]);
                return true;
              }
              if (event.key === 'Escape') {
                event.preventDefault();
                completionOpenRef.current = false;
                setCompletionPosition(null);
                return true;
              }
            }
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
        disabled,
        dir,
        lang,
        placeholder,
        readOnly,
        spellCheck,
      });
      view.setProps({ editable: () => !disabledRef.current && !readOnlyRef.current });
    }, [ariaLabel, dir, disabled, lang, placeholder, readOnly, spellCheck]);

    useEffect(() => {
      const view = viewRef.current;
      if (!view) {
        return;
      }
      view.dispatch(
        view.state.tr.setMeta(visibleTextPluginKey, {
          diagnostics: protectedDiagnostics ?? [],
          marksMode: resolvedMarksMode,
        } satisfies VisibleTextPluginState),
      );
    }, [protectedDiagnostics, resolvedMarksMode]);

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
      if (
        currentValue === value &&
        protectedTokenSignatureFromDoc(view.state.doc) === nextSignature
      ) {
        protectedTokenSignatureRef.current = nextSignature;
        return;
      }

      const rawSelectionStart = rawOffsetFromDocPosition(view.state.doc, view.state.selection.from);
      const rawSelectionEnd = rawOffsetFromDocPosition(view.state.doc, view.state.selection.to);
      const nextDoc = docFromText(value, protectedTokens ?? []);
      const selectionStart = docPositionFromRawOffset(nextDoc, rawSelectionStart);
      const selectionEnd = docPositionFromRawOffset(nextDoc, rawSelectionEnd);
      let transaction = view.state.tr.replaceWith(0, view.state.doc.content.size, nextDoc.content);
      transaction = transaction
        .setSelection(TextSelection.create(transaction.doc, selectionStart, selectionEnd))
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

    useEffect(() => {
      if (!blockedEditMessage) {
        return;
      }

      const timeout = window.setTimeout(() => setBlockedEditMessage(null), 4000);
      return () => window.clearTimeout(timeout);
    }, [blockedEditMessage]);

    useEffect(() => {
      if (controlBar?.rawMode || !validateNextValue) {
        setBlockedEditMessage(null);
      }
    }, [controlBar?.rawMode, validateNextValue]);

    useEffect(() => {
      const optionCount = completion?.options.length ?? 0;
      setCompletionIndex((current) => {
        const next = Math.min(current, Math.max(0, optionCount - 1));
        completionIndexRef.current = next;
        return next;
      });
      updateCompletionPosition();
    }, [completion?.options]);

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

    const controlStatus = exactValueError ?? blockedEditMessage;
    const shouldShowControlStatus = Boolean(exactValueError) || Boolean(blockedEditMessage);
    const shouldAnnounceControlStatus = Boolean(exactValueError || blockedEditMessage);
    const controlMarksMode = controlBar?.marksMode ?? resolvedMarksMode;
    const controlMarksLabel =
      marksModeOptions.find((option) => option.value === controlMarksMode)?.label ?? 'Auto';
    const addIcuFormLabel = getAddIcuFormLabel(controlBar?.icuFormInsertions);
    const activeExactValueInsertion = controlBar?.icuFormInsertions?.find(
      (insertion) => insertion.id === exactValueInsertionId && insertion.kind === 'exact-value',
    );
    const controlBarDisabled = disabled || readOnly;
    useEffect(() => {
      if (!isMarksMenuOpen) {
        return;
      }

      const handlePointerDown = (event: PointerEvent) => {
        if (marksMenuRef.current?.contains(event.target as Node)) {
          return;
        }
        setIsMarksMenuOpen(false);
      };
      const handleKeyDown = (event: KeyboardEvent) => {
        if (event.key === 'Escape') {
          setIsMarksMenuOpen(false);
        }
      };

      window.addEventListener('pointerdown', handlePointerDown, true);
      window.addEventListener('keydown', handleKeyDown);
      return () => {
        window.removeEventListener('pointerdown', handlePointerDown, true);
        window.removeEventListener('keydown', handleKeyDown);
      };
    }, [isMarksMenuOpen]);
    useEffect(() => {
      if (controlBarDisabled) {
        setIsMarksMenuOpen(false);
      }
    }, [controlBarDisabled]);
    const resetExactValueInput = () => {
      setExactValueInsertionId(null);
      setExactValueDraft('');
      setExactValueError(null);
    };
    const applyAddIcuFormResult = (result: AddIcuFormResult) => {
      if (!result.ok) {
        setExactValueError(result.error);
        return false;
      }

      if (typeof result.nextValue === 'string') {
        const view = viewRef.current;
        if (view) {
          replaceEditorValueWithRawText(
            view,
            result.nextValue,
            protectedTokensRef.current,
            result.selectionStart,
            result.selectionEnd,
          );
          view.focus();
        }
      }

      setExactValueError(null);
      return true;
    };
    useEffect(() => {
      if (!exactValueInsertionId || activeExactValueInsertion) {
        return;
      }

      setExactValueInsertionId(null);
      setExactValueDraft('');
      setExactValueError(null);
    }, [activeExactValueInsertion, exactValueInsertionId]);

    const commitExactValueInput = () => {
      if (!activeExactValueInsertion || !controlBar?.onAddIcuForm) {
        resetExactValueInput();
        return;
      }

      const result = controlBar.onAddIcuForm(activeExactValueInsertion.id, exactValueDraft);
      if (applyAddIcuFormResult(result)) {
        resetExactValueInput();
        return;
      }
    };
    const controlBarElement = controlBar ? (
      <div className="visible-text-editor__control-bar" aria-label="Text editor controls">
        {controlBar.onChangeMarksMode ? (
          <div className="visible-text-editor__marks-control" ref={marksMenuRef}>
            <button
              type="button"
              className="visible-text-editor__marks-button"
              aria-expanded={isMarksMenuOpen}
              aria-haspopup="listbox"
              aria-label={`Hidden characters: ${controlMarksLabel}`}
              disabled={controlBarDisabled}
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => setIsMarksMenuOpen((current) => !current)}
              title="Choose hidden character display"
            >
              <span className="visible-text-editor__marks-label">Hidden chars</span>
              <span className="visible-text-editor__marks-value">{controlMarksLabel}</span>
              <span className="visible-text-editor__marks-chevron" aria-hidden="true" />
            </button>
            {isMarksMenuOpen ? (
              <div
                className="visible-text-editor__marks-menu"
                role="listbox"
                aria-label="Hidden characters"
              >
                {marksModeOptions.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    className="visible-text-editor__marks-option"
                    role="option"
                    aria-selected={option.value === controlMarksMode}
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => {
                      controlBar.onChangeMarksMode?.(option.value);
                      setIsMarksMenuOpen(false);
                      viewRef.current?.focus();
                    }}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            ) : null}
          </div>
        ) : null}
        {controlBar.onToggleRawMode ? (
          <button
            type="button"
            className="visible-text-editor__control-button visible-text-editor__mode-button"
            aria-pressed={controlBar.rawMode}
            disabled={controlBarDisabled}
            aria-label={
              controlBar.rawMode
                ? 'Placeholder editing is on. Lock placeholders'
                : 'Placeholder editing is off. Edit placeholders'
            }
            onMouseDown={(event) => event.preventDefault()}
            onClick={controlBar.onToggleRawMode}
            title={
              controlBar.rawMode
                ? 'Placeholder editing is on. Lock placeholders'
                : 'Placeholder editing is off. Edit placeholders'
            }
          >
            {controlBar.rawMode ? 'Lock placeholders' : 'Edit placeholders'}
          </button>
        ) : null}
        {controlBar.onAddIcuForm &&
        controlBar.icuFormInsertions &&
        controlBar.icuFormInsertions.length > 0 ? (
          <select
            className="visible-text-editor__control-select visible-text-editor__icu-form-select"
            aria-label={addIcuFormLabel}
            defaultValue=""
            disabled={controlBarDisabled}
            onChange={(event) => {
              const insertionId = event.target.value;
              if (insertionId) {
                const insertion = controlBar.icuFormInsertions?.find(
                  (item) => item.id === insertionId,
                );
                if (insertion?.kind === 'exact-value') {
                  setExactValueInsertionId(insertionId);
                  setExactValueDraft('');
                  setExactValueError(null);
                } else {
                  const result = controlBar.onAddIcuForm?.(insertionId);
                  if (result) {
                    applyAddIcuFormResult(result);
                  }
                }
              }
              event.currentTarget.value = '';
            }}
            onMouseDown={(event) => event.stopPropagation()}
          >
            <option value="">{addIcuFormLabel}</option>
            {controlBar.icuFormInsertions.map((insertion) => (
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
              disabled={controlBarDisabled}
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
              disabled={controlBarDisabled}
              onMouseDown={(event) => event.preventDefault()}
              onClick={commitExactValueInput}
            >
              Add
            </button>
            <button
              type="button"
              className="visible-text-editor__exact-form-button visible-text-editor__exact-form-button--icon"
              aria-label="Cancel exact plural value"
              disabled={controlBarDisabled}
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
        {shouldShowControlStatus ? (
          <span
            className={`visible-text-editor__control-status${
              exactValueError ? ' visible-text-editor__control-status--error' : ''
            }${blockedEditMessage ? ' visible-text-editor__control-status--blocked' : ''}`}
            aria-live={shouldAnnounceControlStatus ? 'polite' : 'off'}
            title={controlStatus ?? undefined}
          >
            {controlStatus}
          </span>
        ) : null}
      </div>
    ) : null;

    const completionOptions = completion?.options ?? [];
    const completionElement =
      completionOptions.length > 0 && completionPosition ? (
        <div
          className="visible-text-editor__completion-menu"
          role="listbox"
          aria-label={completion?.ariaLabel ?? 'Text completions'}
          style={{
            left: completionPosition.left,
            top: completionPosition.top,
          }}
        >
          {completionOptions.map((option, index) => (
            <button
              key={option.id}
              type="button"
              className="visible-text-editor__completion-option"
              role="option"
              aria-selected={index === completionIndex}
              aria-label={option.detail ? `${option.label} ${option.detail}` : option.label}
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => {
                applyCompletion(option);
                viewRef.current?.focus();
              }}
            >
              <span className="visible-text-editor__completion-label">{option.label}</span>
              {option.detail ? (
                <span className="visible-text-editor__completion-detail">{option.detail}</span>
              ) : null}
            </button>
          ))}
        </div>
      ) : null;

    return (
      <div className={editorClassName} style={style} ref={rootRef}>
        {controlBar?.position === 'top' ? controlBarElement : null}
        <div className="visible-text-editor__surface" ref={mountRef} />
        {completionElement}
        {controlBar?.position === 'top' ? null : controlBarElement}
      </div>
    );
  },
);
