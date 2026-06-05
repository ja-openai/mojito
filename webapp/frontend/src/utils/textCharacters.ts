export type TextAssistPreviewMode = 'off' | 'issues' | 'all';

export type TextAssistWarningCode =
  | 'leading-space'
  | 'trailing-space'
  | 'line-breaks'
  | 'double-space'
  | 'tab'
  | 'nbsp'
  | 'nnbsp'
  | 'invisible'
  | 'isolate-balance'
  | 'control';

export type TextAssistWarning = {
  code: TextAssistWarningCode;
  message: string;
};

export type TextAssistSegment = {
  text: string;
  issue: boolean;
  visible: boolean;
  label?: string;
};

export type VisibleTextMarker = {
  label: string;
  text: string;
};

export type TextAssistCharacter = {
  char: string;
  code: string;
  index: number;
  label: string;
  kind: 'text' | 'space' | 'line-break' | 'special-space' | 'bidi' | 'zero-width' | 'control';
  issue: boolean;
  start: number;
  end: number;
};

export type TextAssistInsertTool = {
  label: string;
  value: string;
  name: string;
  group: 'space' | 'bidi-primary' | 'bidi-legacy';
};

export type TextAssistWrapTool = {
  label: string;
  open: string;
  close: string;
};

type IndexedChar = {
  char: string;
  index: number;
  start: number;
  end: number;
};

const BIDI_PRIMARY_TOOLS: TextAssistInsertTool[] = [
  { label: 'LRM', value: '\u200e', name: 'left-to-right mark', group: 'bidi-primary' },
  { label: 'RLM', value: '\u200f', name: 'right-to-left mark', group: 'bidi-primary' },
  { label: 'ALM', value: '\u061c', name: 'Arabic letter mark', group: 'bidi-primary' },
  { label: 'LRI', value: '\u2066', name: 'left-to-right isolate', group: 'bidi-primary' },
  { label: 'RLI', value: '\u2067', name: 'right-to-left isolate', group: 'bidi-primary' },
  { label: 'FSI', value: '\u2068', name: 'first-strong isolate', group: 'bidi-primary' },
  { label: 'PDI', value: '\u2069', name: 'pop directional isolate', group: 'bidi-primary' },
];

const BIDI_LEGACY_TOOLS: TextAssistInsertTool[] = [
  { label: 'LRE', value: '\u202a', name: 'left-to-right embedding', group: 'bidi-legacy' },
  { label: 'RLE', value: '\u202b', name: 'right-to-left embedding', group: 'bidi-legacy' },
  { label: 'PDF', value: '\u202c', name: 'pop directional formatting', group: 'bidi-legacy' },
  { label: 'LRO', value: '\u202d', name: 'left-to-right override', group: 'bidi-legacy' },
  { label: 'RLO', value: '\u202e', name: 'right-to-left override', group: 'bidi-legacy' },
];

const SPECIAL_SPACE_TOOLS: TextAssistInsertTool[] = [
  { label: 'NBSP', value: '\u00a0', name: 'non-breaking space', group: 'space' },
  { label: 'NNBSP', value: '\u202f', name: 'narrow non-breaking space', group: 'space' },
];

export const TEXT_ASSIST_INSERT_TOOLS: TextAssistInsertTool[] = [
  ...SPECIAL_SPACE_TOOLS,
  ...BIDI_PRIMARY_TOOLS,
  ...BIDI_LEGACY_TOOLS,
];

export const TEXT_ASSIST_WRAP_TOOLS: TextAssistWrapTool[] = [
  { label: 'Wrap LRI...PDI', open: '\u2066', close: '\u2069' },
  { label: 'Wrap RLI...PDI', open: '\u2067', close: '\u2069' },
  { label: 'Wrap FSI...PDI', open: '\u2068', close: '\u2069' },
];

const ZERO_WIDTH_LABELS = new Map<string, string>([
  ['\u034f', 'CGJ'],
  ['\u180e', 'MVS'],
  ['\u200b', 'ZWSP'],
  ['\u200c', 'ZWNJ'],
  ['\u200d', 'ZWJ'],
  ['\u2060', 'WJ'],
  ['\ufeff', 'BOM'],
]);

const BIDI_TOOL_BY_VALUE = new Map(
  [...BIDI_PRIMARY_TOOLS, ...BIDI_LEGACY_TOOLS].map((tool) => [tool.value, tool]),
);

function splitIndexed(value: string): IndexedChar[] {
  const chars: IndexedChar[] = [];
  let offset = 0;
  for (const [index, char] of Array.from(value).entries()) {
    chars.push({ char, index, start: offset, end: offset + char.length });
    offset += char.length;
  }
  return chars;
}

export function formatCodePoint(codePoint: number) {
  const hex = codePoint.toString(16).toUpperCase();
  return `U+${hex.padStart(Math.max(4, hex.length), '0')}`;
}

export function getLeadingWhitespace(value: string): string {
  return value.match(/^\s+/u)?.[0] ?? '';
}

export function getTrailingWhitespace(value: string): string {
  return value.match(/\s+$/u)?.[0] ?? '';
}

export function isControlCode(code: number): boolean {
  return (
    (code >= 0x0 && code <= 0x8) ||
    (code >= 0xb && code <= 0xc) ||
    (code >= 0xe && code <= 0x1f) ||
    (code >= 0x7f && code <= 0x9f)
  );
}

export function isInvisibleDirectionalOrZeroWidthCode(code: number): boolean {
  return (
    code === 0x061c ||
    code === 0x034f ||
    code === 0x180e ||
    (code >= 0x200b && code <= 0x200f) ||
    (code >= 0x202a && code <= 0x202e) ||
    code === 0x2060 ||
    (code >= 0x2066 && code <= 0x2069) ||
    code === 0xfeff
  );
}

function lineBreakSignature(value: string): string {
  return value.replace(/\r\n/g, '\n').replace(/\r/g, '\n').match(/\n/g)?.join('') ?? '';
}

function isWhitespace(char: string): boolean {
  return /^\s$/u.test(char);
}

function isLineBreak(char: string): boolean {
  return char === '\n' || char === '\r';
}

function visibleLabelFor(char: string): { label: string; text: string } | null {
  const marker = BIDI_TOOL_BY_VALUE.get(char);
  if (marker) {
    return { label: `${marker.label} (${marker.name})`, text: `<${marker.label}>` };
  }

  const zeroWidthLabel = ZERO_WIDTH_LABELS.get(char);
  if (zeroWidthLabel) {
    return { label: zeroWidthLabel, text: `<${zeroWidthLabel}>` };
  }

  if (char === ' ') {
    return { label: 'space', text: '·' };
  }
  if (char === '\t') {
    return { label: 'tab', text: '→' };
  }
  if (char === '\n') {
    return { label: 'line feed', text: '↵\n' };
  }
  if (char === '\r') {
    return { label: 'carriage return', text: '␍' };
  }
  if (char === '\u00a0') {
    return { label: 'non-breaking space', text: '⍽' };
  }
  if (char === '\u202f') {
    return { label: 'narrow non-breaking space', text: '⏤' };
  }

  const code = char.codePointAt(0);
  if (code != null && isControlCode(code)) {
    return { label: `control ${formatCodePoint(code)}`, text: `<${formatCodePoint(code)}>` };
  }

  return null;
}

export function getVisibleTextMarker(char: string): VisibleTextMarker | null {
  return visibleLabelFor(char);
}

function issueIndexesFor(source: string, target: string): Set<number> {
  const chars = splitIndexed(target);
  const issues = new Set<number>();

  const sourceLeadingWhitespace = getLeadingWhitespace(source);
  const sourceTrailingWhitespace = getTrailingWhitespace(source);
  const targetLeadingWhitespace = getLeadingWhitespace(target);
  const targetTrailingWhitespace = getTrailingWhitespace(target);
  const hasLeadingWhitespaceMismatch = sourceLeadingWhitespace !== targetLeadingWhitespace;
  const hasTrailingWhitespaceMismatch = sourceTrailingWhitespace !== targetTrailingWhitespace;
  const hasLineBreakMismatch = lineBreakSignature(source) !== lineBreakSignature(target);

  let leadingEnd = 0;
  while (leadingEnd < chars.length && isWhitespace(chars[leadingEnd]?.char ?? '')) {
    leadingEnd += 1;
  }

  let trailingStart = chars.length;
  while (trailingStart > 0 && isWhitespace(chars[trailingStart - 1]?.char ?? '')) {
    trailingStart -= 1;
  }

  for (const item of chars) {
    const code = item.char.codePointAt(0);
    if (
      (hasLeadingWhitespaceMismatch && item.index < leadingEnd) ||
      (hasTrailingWhitespaceMismatch && item.index >= trailingStart) ||
      (hasLineBreakMismatch && isLineBreak(item.char)) ||
      (item.char === ' ' && item.index > 0 && chars[item.index - 1]?.char === ' ') ||
      item.char === '\t' ||
      item.char === '\u00a0' ||
      item.char === '\u202f' ||
      (code != null && isInvisibleDirectionalOrZeroWidthCode(code)) ||
      (code != null && isControlCode(code))
    ) {
      issues.add(item.index);
    }
  }

  return issues;
}

export function buildTextAssistWarnings(source: string, target: string): TextAssistWarning[] {
  const warnings: TextAssistWarning[] = [];

  if (!target) {
    return warnings;
  }

  const sourceLeadingWhitespace = getLeadingWhitespace(source);
  const sourceTrailingWhitespace = getTrailingWhitespace(source);
  const targetLeadingWhitespace = getLeadingWhitespace(target);
  const targetTrailingWhitespace = getTrailingWhitespace(target);

  if (sourceLeadingWhitespace !== targetLeadingWhitespace) {
    warnings.push({
      code: 'leading-space',
      message:
        sourceLeadingWhitespace.length === 0
          ? 'Unexpected leading whitespace at start.'
          : 'Leading whitespace does not match source.',
    });
  }
  if (sourceTrailingWhitespace !== targetTrailingWhitespace) {
    warnings.push({
      code: 'trailing-space',
      message:
        sourceTrailingWhitespace.length === 0
          ? 'Unexpected trailing whitespace at end.'
          : 'Trailing whitespace does not match source.',
    });
  }
  if (lineBreakSignature(source) !== lineBreakSignature(target)) {
    warnings.push({ code: 'line-breaks', message: 'Line breaks differ from source.' });
  }
  if (/ {2,}/u.test(target)) {
    warnings.push({ code: 'double-space', message: 'Contains repeated spaces.' });
  }
  if (/\t/u.test(target)) {
    warnings.push({ code: 'tab', message: 'Contains tab characters.' });
  }
  if (target.includes('\u00a0')) {
    warnings.push({ code: 'nbsp', message: 'Contains non-breaking spaces.' });
  }
  if (target.includes('\u202f')) {
    warnings.push({ code: 'nnbsp', message: 'Contains narrow non-breaking spaces.' });
  }

  let hasInvisible = false;
  let hasControl = false;
  for (const char of Array.from(target)) {
    const code = char.codePointAt(0);
    if (code == null) {
      continue;
    }
    hasInvisible ||= isInvisibleDirectionalOrZeroWidthCode(code);
    hasControl ||= isControlCode(code);
  }

  if (hasInvisible) {
    warnings.push({
      code: 'invisible',
      message: 'Contains invisible directional or zero-width characters.',
    });
  }

  const isolateWarnings = buildIsolateWarnings(target);
  if (isolateWarnings.length > 0) {
    warnings.push({
      code: 'isolate-balance',
      message: isolateWarnings[0]?.message ?? 'Directional isolates are unbalanced.',
    });
  }

  if (hasControl) {
    warnings.push({ code: 'control', message: 'Contains control characters.' });
  }

  return warnings;
}

export function buildVisibleTextSegments({
  source,
  target,
  mode,
}: {
  source: string;
  target: string;
  mode: TextAssistPreviewMode;
}): TextAssistSegment[] {
  if (mode === 'off' || target.length === 0) {
    return target ? [{ text: target, issue: false, visible: false }] : [];
  }

  const issues = issueIndexesFor(source, target);
  const pieces = splitIndexed(target).map((item): TextAssistSegment => {
    const issue = issues.has(item.index);
    const visibleLabel = visibleLabelFor(item.char);
    const shouldShowVisible = visibleLabel != null && (mode === 'all' || issue);

    if (!shouldShowVisible) {
      return { text: item.char, issue: false, visible: false };
    }

    return {
      text: visibleLabel.text,
      issue,
      visible: true,
      label: visibleLabel.label,
    };
  });

  return mergeSegments(pieces);
}

export function describeTextCharacters(source: string, target: string): TextAssistCharacter[] {
  const issues = issueIndexesFor(source, target);
  return splitIndexed(target).map((item) => {
    const codePoint = item.char.codePointAt(0) ?? 0;
    const marker = BIDI_TOOL_BY_VALUE.get(item.char);
    const zeroWidthLabel = ZERO_WIDTH_LABELS.get(item.char);
    let label = item.char;
    let kind: TextAssistCharacter['kind'] = 'text';

    if (marker) {
      label = marker.label;
      kind = 'bidi';
    } else if (zeroWidthLabel) {
      label = zeroWidthLabel;
      kind = 'zero-width';
    } else if (item.char === ' ') {
      label = 'space';
      kind = 'space';
    } else if (item.char === '\t') {
      label = 'tab';
      kind = 'control';
    } else if (isLineBreak(item.char)) {
      label = item.char === '\n' ? 'LF' : 'CR';
      kind = 'line-break';
    } else if (item.char === '\u00a0') {
      label = 'NBSP';
      kind = 'special-space';
    } else if (item.char === '\u202f') {
      label = 'NNBSP';
      kind = 'special-space';
    } else if (isControlCode(codePoint)) {
      label = 'control';
      kind = 'control';
    }

    return {
      char: item.char,
      code: formatCodePoint(codePoint),
      index: item.index,
      label,
      kind,
      issue: issues.has(item.index),
      start: item.start,
      end: item.end,
    };
  });
}

export function escapeVisibleText(value: string): string {
  return (
    Array.from(value)
      .map((char) => visibleLabelFor(char)?.text ?? char)
      .join('') || '(empty)'
  );
}

export function buildIsolateWarnings(
  value: string,
): Array<{ title: string; message: string; index: number }> {
  const stack: TextAssistCharacter[] = [];
  const warnings: Array<{ title: string; message: string; index: number }> = [];

  for (const item of describeTextCharacters('', value)) {
    if (item.label === 'LRI' || item.label === 'RLI' || item.label === 'FSI') {
      stack.push(item);
    } else if (item.label === 'PDI') {
      if (stack.length > 0) {
        stack.pop();
      } else {
        warnings.push({
          title: 'Stray PDI',
          message: `PDI at character ${item.index + 1} has no open isolate to close.`,
          index: item.index,
        });
      }
    }
  }

  for (const item of stack.reverse()) {
    warnings.push({
      title: `Unclosed ${item.label}`,
      message: `${item.label} at character ${item.index + 1} needs a later PDI.`,
      index: item.index,
    });
  }

  return warnings;
}

function mergeSegments(pieces: TextAssistSegment[]): TextAssistSegment[] {
  const merged: TextAssistSegment[] = [];
  for (const piece of pieces) {
    const previous = merged[merged.length - 1];
    if (
      previous &&
      previous.issue === piece.issue &&
      previous.visible === piece.visible &&
      previous.label === piece.label
    ) {
      previous.text += piece.text;
    } else {
      merged.push({ ...piece });
    }
  }
  return merged;
}
