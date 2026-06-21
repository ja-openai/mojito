import './visible-text-editor.css';

import {
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
  useMemo,
} from 'react';

import {
  extractProtectedTextTokens,
  getProtectedTextDiagnostics,
  type ProtectedTextDiagnostic,
  type ProtectedTextToken,
  type ProtectedTextTokenMode,
} from '../utils/protectedTextTokens';
import { getVisibleTextMarker } from '../utils/textCharacters';
import {
  getVisibleTextIcuMessagesForValue,
  visibleIcuSyntaxDisplay,
  visibleProtectedTokenText,
  type VisibleTextIcuMessage,
} from '../utils/visibleTextIcuDisplay';
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
      end: number;
      start: number;
      text: string;
    }
  | {
      diagnostic?: ProtectedTextDiagnostic;
      end: number;
      kind: 'marked';
      char: string;
      label: string;
      markerText: string;
      start: number;
    }
  | {
      end: number;
      kind: 'marker-widget';
      char: string;
      label: string;
      markerText: string;
      start: number;
    }
  | {
      kind: 'protected-token';
      diagnostic?: ProtectedTextDiagnostic;
      end: number;
      start: number;
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

function appendTextPart(parts: TextPart[], text: string, start: number, end: number) {
  if (!text) {
    return;
  }
  const previous = parts[parts.length - 1];
  if (previous?.kind === 'text' && !previous.diagnostic && previous.end === start) {
    previous.text += text;
    previous.end = end;
    return;
  }
  parts.push({ end, kind: 'text', start, text });
}

function appendDiagnosticTextPart(
  parts: TextPart[],
  text: string,
  start: number,
  end: number,
  diagnostic?: ProtectedTextDiagnostic,
) {
  if (!diagnostic) {
    appendTextPart(parts, text, start, end);
    return;
  }

  const previous = parts[parts.length - 1];
  if (
    previous?.kind === 'text' &&
    previous.diagnostic?.start === diagnostic.start &&
    previous.diagnostic?.end === diagnostic.end &&
    previous.diagnostic?.code === diagnostic.code &&
    previous.end === start
  ) {
    previous.text += text;
    previous.end = end;
    return;
  }
  parts.push({ diagnostic, end, kind: 'text', start, text });
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
      appendDiagnosticTextPart(
        parts,
        char,
        rawStart,
        rawEnd,
        diagnosticForRange(diagnostics, rawStart, rawEnd),
      );
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
      appendDiagnosticTextPart(
        parts,
        char,
        rawStart,
        rawEnd,
        diagnosticForRange(diagnostics, rawStart, rawEnd),
      );
      continue;
    }

    const markerText = visibleTextWidgetText(marker.text);
    if (shouldRenderVisibleTextWidget(char, marker.text)) {
      if (char === '\n' || char === '\r') {
        parts.push({
          char,
          end: rawStart,
          kind: 'marker-widget',
          label: marker.label,
          markerText,
          start: rawStart,
        });
        appendTextPart(parts, char, rawStart, rawEnd);
        continue;
      }

      parts.push({
        char,
        end: rawStart,
        kind: 'marker-widget',
        label: marker.label,
        markerText,
        start: rawStart,
      });
      appendTextPart(parts, char, rawStart, rawEnd);
      continue;
    }

    parts.push({
      char,
      diagnostic: diagnosticForRange(diagnostics, rawStart, rawEnd),
      end: rawEnd,
      kind: 'marked',
      label: marker.label,
      markerText,
      start: rawStart,
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
      end: token.end,
      kind: 'protected-token',
      start: token.start,
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

function renderIcuSyntaxTokenContent(raw: string) {
  const display = visibleIcuSyntaxDisplay(raw);

  if (display.kind === 'empty') {
    return null;
  }

  if (display.kind === 'argument-form') {
    return (
      <span className="visible-text-editor__icu-syntax-label">
        <span className="visible-text-editor__icu-syntax-argument">{display.argument}</span>{' '}
        <span className="visible-text-editor__icu-syntax-form">{display.form}</span>
      </span>
    );
  }

  return <span className="visible-text-editor__icu-syntax-form">{display.form}</span>;
}

function icuEditableTextClassName(enabled: boolean) {
  return enabled ? ' visible-text-editor__icu-editable-text' : '';
}

function renderIcuEditableText(text: string, keyPrefix: string, markNumberPlaceholder: boolean) {
  if (!markNumberPlaceholder || !text.includes('#')) {
    return text;
  }

  const output: ReactNode[] = [];
  let pending = '';
  let index = 0;

  for (const char of Array.from(text)) {
    if (char !== '#') {
      pending += char;
      index += 1;
      continue;
    }

    if (pending) {
      output.push(pending);
      pending = '';
    }

    output.push(
      <span
        key={`${keyPrefix}-icu-number-${index}`}
        className="visible-text-editor__icu-number-placeholder"
        title="ICU number placeholder"
      >
        #
      </span>,
    );
    index += 1;
  }

  if (pending) {
    output.push(pending);
  }

  return output;
}

function renderPart(part: TextPart, index: number, icuGroup: VisibleTextIcuMessage | null = null) {
  const insideIcuMessage = Boolean(icuGroup);
  const markNumberPlaceholder = icuGroup?.messageType === 'plural';

  if (part.kind === 'text') {
    if (part.diagnostic) {
      return (
        <span
          key={index}
          className={`${diagnosticClassName(part.diagnostic)}${icuEditableTextClassName(
            insideIcuMessage,
          )}`}
          title={part.diagnostic.message}
        >
          {renderIcuEditableText(part.text, `text-${index}`, markNumberPlaceholder)}
        </span>
      );
    }
    if (insideIcuMessage) {
      return (
        <span key={index} className="visible-text-editor__icu-editable-text">
          {renderIcuEditableText(part.text, `text-${index}`, markNumberPlaceholder)}
        </span>
      );
    }
    return part.text;
  }

  if (part.kind === 'protected-token') {
    const displayText = visibleProtectedTokenText(part.token.kind, part.text);
    const emptySyntaxClass =
      part.token.kind === 'icu-syntax' && !displayText
        ? ' visible-text-editor__protected-token--empty-icu-syntax'
        : '';
    return (
      <span
        key={index}
        aria-label={part.token.label}
        className={`visible-text-editor__protected-token visible-text-editor__protected-token--${part.token.kind}${emptySyntaxClass}${
          part.diagnostic ? ` ${diagnosticClassName(part.diagnostic)}` : ''
        }`}
        data-raw={part.text}
        title={part.diagnostic?.message ?? part.token.label}
      >
        {part.token.kind === 'icu-syntax' ? renderIcuSyntaxTokenContent(part.text) : displayText}
      </span>
    );
  }

  if (part.kind === 'marker-widget') {
    return (
      <span
        key={index}
        className={`visible-text-editor__marker-widget visible-text-editor__marker-widget--${visibleTextMarkerClassFor(
          part.char,
        )}${icuEditableTextClassName(insideIcuMessage)}`}
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
      )}${part.diagnostic ? ` ${diagnosticClassName(part.diagnostic)}` : ''}${icuEditableTextClassName(
        insideIcuMessage,
      )}`}
      data-marker={part.markerText}
      title={part.diagnostic?.message ?? part.label}
    >
      {part.char}
    </span>
  );
}

function renderPartsWithIcuGroups(parts: TextPart[], groups: VisibleTextIcuMessage[]): ReactNode[] {
  if (groups.length === 0) {
    return parts.map((part, index) => renderPart(part, index));
  }

  const output: ReactNode[] = [];
  let groupIndex = 0;
  let partIndex = 0;

  while (partIndex < parts.length) {
    const group = groups[groupIndex];
    const part = parts[partIndex];

    if (!group) {
      output.push(renderPart(part, output.length));
      partIndex += 1;
      continue;
    }

    if (part.end <= group.messageStart) {
      output.push(renderPart(part, output.length));
      partIndex += 1;
      continue;
    }

    if (part.start >= group.messageEnd) {
      groupIndex += 1;
      continue;
    }

    if (part.start < group.messageStart || part.end > group.messageEnd) {
      output.push(renderPart(part, output.length));
      partIndex += 1;
      continue;
    }

    const groupParts: TextPart[] = [];
    while (partIndex < parts.length) {
      const candidate = parts[partIndex];
      if (candidate.start >= group.messageEnd) {
        break;
      }
      if (candidate.start >= group.messageStart && candidate.end <= group.messageEnd) {
        groupParts.push(candidate);
      } else {
        output.push(renderPart(candidate, output.length));
      }
      partIndex += 1;
    }

    if (groupParts.length > 0) {
      output.push(
        <span key={`icu-message-${group.key}`} className="visible-text-renderer__icu-message">
          {renderPartsWithIcuFormBodies(groupParts, group)}
        </span>,
      );
    }

    groupIndex += 1;
  }

  return output;
}

function renderPartsWithIcuFormBodies(
  parts: TextPart[],
  group: VisibleTextIcuMessage,
): ReactNode[] {
  if (group.formBodies.length === 0) {
    return parts.map((part, index) => renderPart(part, index, group));
  }

  const output: ReactNode[] = [];
  let bodyIndex = 0;
  let partIndex = 0;

  while (partIndex < parts.length) {
    const body = group.formBodies[bodyIndex];
    const part = parts[partIndex];

    if (!body) {
      output.push(renderPart(part, output.length, group));
      partIndex += 1;
      continue;
    }

    if (part.end <= body.start) {
      output.push(renderPart(part, output.length, group));
      partIndex += 1;
      continue;
    }

    if (part.start >= body.end) {
      bodyIndex += 1;
      continue;
    }

    if (part.start < body.start || part.end > body.end) {
      output.push(renderPart(part, output.length, group));
      partIndex += 1;
      continue;
    }

    const bodyParts: TextPart[] = [];
    while (partIndex < parts.length) {
      const candidate = parts[partIndex];
      if (candidate.start >= body.end) {
        break;
      }
      if (candidate.start >= body.start && candidate.end <= body.end) {
        bodyParts.push(candidate);
      } else {
        output.push(renderPart(candidate, output.length, group));
      }
      partIndex += 1;
    }

    if (bodyParts.length > 0) {
      output.push(
        <span
          key={`icu-form-body-${group.key}-${body.form}-${body.start}`}
          className="visible-text-editor__icu-form-body"
        >
          {bodyParts.map((bodyPart, index) => renderPart(bodyPart, index, group))}
        </span>,
      );
    }

    bodyIndex += 1;
  }

  return output;
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
  const icuMessageGroups = useMemo(
    () =>
      getVisibleTextIcuMessagesForValue(
        value,
        showProtectedTokens && resolvedProtectedTokens.some((token) => token.kind === 'icu-syntax'),
      ),
    [resolvedProtectedTokens, showProtectedTokens, value],
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
      {renderPartsWithIcuGroups(parts, icuMessageGroups)}
    </div>
  );
}
