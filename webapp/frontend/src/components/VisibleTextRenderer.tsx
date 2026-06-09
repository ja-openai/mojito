import './visible-text-editor.css';

import { type CSSProperties, type KeyboardEvent as ReactKeyboardEvent, useMemo } from 'react';

import {
  extractProtectedTextTokens,
  getProtectedTextDiagnostics,
  type ProtectedTextDiagnostic,
  type ProtectedTextToken,
  type ProtectedTextTokenMode,
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

type Props = {
  value: string;
  ariaLabel?: string;
  className?: string;
  dir?: 'ltr' | 'rtl' | 'auto';
  disabled?: boolean;
  lang?: string;
  marksMode?: VisibleTextMarksMode;
  onFocus?: () => void;
  onKeyDown?: (event: ReactKeyboardEvent<HTMLDivElement>) => void;
  placeholder?: string;
  protectedDiagnostics?: ProtectedTextDiagnostic[];
  protectedTokens?: ProtectedTextToken[];
  showProtectedTokens?: boolean;
  showInvisibles?: boolean;
  spellCheck?: boolean;
  style?: CSSProperties;
  tokenMode?: ProtectedTextTokenMode;
};

type TextPart =
  | {
      kind: 'text';
      diagnostic?: ProtectedTextDiagnostic;
      text: string;
    }
  | {
      diagnostic?: ProtectedTextDiagnostic;
      kind: 'marked';
      char: string;
      label: string;
      markerText: string;
    }
  | {
      kind: 'marker-widget';
      char: string;
      label: string;
      markerText: string;
    }
  | {
      kind: 'protected-token';
      diagnostic?: ProtectedTextDiagnostic;
      text: string;
      token: ProtectedTextToken;
    };

function normalizeTokens(value: string, tokens: ProtectedTextToken[]): ProtectedTextToken[] {
  const output: ProtectedTextToken[] = [];
  let cursor = 0;
  tokens
    .filter((token) => token.start >= 0 && token.end > token.start && token.end <= value.length)
    .sort((a, b) => a.start - b.start || b.end - a.end)
    .forEach((token) => {
      if (token.start < cursor) {
        return;
      }
      output.push(token);
      cursor = token.end;
    });
  return output;
}

function appendTextPart(parts: TextPart[], text: string) {
  if (!text) {
    return;
  }
  const previous = parts[parts.length - 1];
  if (previous?.kind === 'text' && !previous.diagnostic) {
    previous.text += text;
    return;
  }
  parts.push({ kind: 'text', text });
}

function appendDiagnosticTextPart(
  parts: TextPart[],
  text: string,
  diagnostic?: ProtectedTextDiagnostic,
) {
  if (!diagnostic) {
    appendTextPart(parts, text);
    return;
  }

  const previous = parts[parts.length - 1];
  if (
    previous?.kind === 'text' &&
    previous.diagnostic?.start === diagnostic.start &&
    previous.diagnostic?.end === diagnostic.end &&
    previous.diagnostic?.code === diagnostic.code
  ) {
    previous.text += text;
    return;
  }
  parts.push({ diagnostic, kind: 'text', text });
}

function diagnosticForRange(
  diagnostics: ProtectedTextDiagnostic[],
  start: number,
  end: number,
): ProtectedTextDiagnostic | undefined {
  return diagnostics.find((diagnostic) => start < diagnostic.end && end > diagnostic.start);
}

function appendVisibleTextParts({
  marksMode,
  diagnostics,
  offset,
  parts,
  text,
  value,
}: {
  marksMode: VisibleTextMarksMode;
  diagnostics: ProtectedTextDiagnostic[];
  offset: number;
  parts: TextPart[];
  text: string;
  value: string;
}) {
  if (marksMode === 'off') {
    let localOffset = 0;
    for (const char of Array.from(text)) {
      const rawStart = offset + localOffset;
      const rawEnd = rawStart + char.length;
      localOffset += char.length;
      appendDiagnosticTextPart(parts, char, diagnosticForRange(diagnostics, rawStart, rawEnd));
    }
    return;
  }

  let localOffset = 0;
  for (const char of Array.from(text)) {
    const marker = getVisibleTextMarker(char);
    const rawStart = offset + localOffset;
    const rawEnd = rawStart + char.length;
    localOffset += char.length;

    if (
      !marker ||
      (marksMode === 'auto' && !shouldShowVisibleTextIssueMarker(value, { char, rawEnd, rawStart }))
    ) {
      appendDiagnosticTextPart(parts, char, diagnosticForRange(diagnostics, rawStart, rawEnd));
      continue;
    }

    const markerText = visibleTextWidgetText(marker.text);
    if (shouldRenderVisibleTextWidget(char, marker.text)) {
      if (char === '\n' || char === '\r') {
        parts.push({
          kind: 'marker-widget',
          char,
          label: marker.label,
          markerText,
        });
        appendTextPart(parts, char);
        continue;
      }

      parts.push({
        kind: 'marker-widget',
        char,
        label: marker.label,
        markerText,
      });
      appendTextPart(parts, char);
      continue;
    }

    parts.push({
      kind: 'marked',
      char,
      diagnostic: diagnosticForRange(diagnostics, rawStart, rawEnd),
      label: marker.label,
      markerText,
    });
  }
}

function buildTextParts(
  value: string,
  protectedTokens: ProtectedTextToken[],
  diagnostics: ProtectedTextDiagnostic[],
  marksMode: VisibleTextMarksMode,
): TextPart[] {
  const parts: TextPart[] = [];
  let cursor = 0;

  normalizeTokens(value, protectedTokens).forEach((token) => {
    appendVisibleTextParts({
      diagnostics,
      marksMode,
      offset: cursor,
      parts,
      text: value.slice(cursor, token.start),
      value,
    });
    parts.push({
      diagnostic: diagnosticForRange(diagnostics, token.start, token.end),
      kind: 'protected-token',
      text: value.slice(token.start, token.end),
      token,
    });
    cursor = token.end;
  });

  appendVisibleTextParts({
    diagnostics,
    marksMode,
    offset: cursor,
    parts,
    text: value.slice(cursor),
    value,
  });

  return parts;
}

function renderPart(part: TextPart, index: number) {
  if (part.kind === 'text') {
    if (part.diagnostic) {
      return (
        <span
          key={index}
          className={diagnosticClassName(part.diagnostic)}
          title={part.diagnostic.message}
        >
          {part.text}
        </span>
      );
    }
    return part.text;
  }

  if (part.kind === 'protected-token') {
    return (
      <span
        key={index}
        aria-label={part.token.label}
        className={`visible-text-editor__protected-token visible-text-editor__protected-token--${part.token.kind}${
          part.diagnostic ? ` ${diagnosticClassName(part.diagnostic)}` : ''
        }`}
        data-raw={part.text}
        title={part.diagnostic?.message ?? part.token.label}
      >
        {part.text}
      </span>
    );
  }

  if (part.kind === 'marker-widget') {
    return (
      <span
        key={index}
        className={`visible-text-editor__marker-widget visible-text-editor__marker-widget--${visibleTextMarkerClassFor(
          part.char,
        )}`}
        title={part.label}
        aria-hidden="true"
      >
        {part.markerText}
      </span>
    );
  }

  return (
    <span
      key={index}
      className={`visible-text-editor__marked-char visible-text-editor__marked-char--${visibleTextMarkerClassFor(
        part.char,
      )}${part.diagnostic ? ` ${diagnosticClassName(part.diagnostic)}` : ''}`}
      data-marker={part.markerText}
      title={part.diagnostic?.message ?? part.label}
    >
      {part.char}
    </span>
  );
}

function diagnosticClassName(diagnostic: ProtectedTextDiagnostic): string {
  return `visible-text-editor__diagnostic visible-text-editor__diagnostic--${diagnostic.severity} visible-text-editor__diagnostic--${diagnostic.code}`;
}

export function VisibleTextRenderer({
  value,
  ariaLabel,
  className,
  dir = 'auto',
  disabled = false,
  lang,
  marksMode,
  onFocus,
  onKeyDown,
  placeholder,
  protectedDiagnostics,
  protectedTokens,
  showProtectedTokens = true,
  showInvisibles,
  spellCheck = true,
  style,
  tokenMode = 'none',
}: Props) {
  const resolvedMarksMode = resolveVisibleTextMarksMode(marksMode, showInvisibles);
  const resolvedProtectedTokens = useMemo(
    () =>
      showProtectedTokens ? (protectedTokens ?? extractProtectedTextTokens(value, tokenMode)) : [],
    [protectedTokens, showProtectedTokens, tokenMode, value],
  );
  const resolvedProtectedDiagnostics = useMemo(
    () => protectedDiagnostics ?? getProtectedTextDiagnostics(value, tokenMode),
    [protectedDiagnostics, tokenMode, value],
  );
  const parts = useMemo(
    () =>
      buildTextParts(
        value,
        resolvedProtectedTokens,
        resolvedProtectedDiagnostics,
        resolvedMarksMode,
      ),
    [resolvedMarksMode, resolvedProtectedDiagnostics, resolvedProtectedTokens, value],
  );
  const isInteractive = Boolean(onFocus && !disabled);
  const rootClassName = `visible-text-renderer${
    isInteractive ? ' visible-text-renderer--interactive' : ''
  }${disabled ? ' visible-text-renderer--disabled' : ''}${className ? ` ${className}` : ''}`;

  return (
    <div
      aria-disabled={disabled || undefined}
      aria-label={isInteractive ? ariaLabel : undefined}
      aria-multiline={isInteractive ? 'true' : undefined}
      aria-readonly={isInteractive ? 'true' : undefined}
      className={rootClassName}
      data-empty={value.length === 0 ? 'true' : 'false'}
      data-placeholder={placeholder ?? ''}
      dir={dir}
      lang={lang}
      onFocus={isInteractive ? onFocus : undefined}
      onKeyDown={isInteractive ? onKeyDown : undefined}
      role={isInteractive ? 'textbox' : undefined}
      spellCheck={spellCheck}
      style={style}
      tabIndex={isInteractive ? 0 : undefined}
    >
      {parts.map(renderPart)}
    </div>
  );
}
