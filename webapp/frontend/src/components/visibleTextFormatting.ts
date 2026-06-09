import { isControlCode, isInvisibleDirectionalOrZeroWidthCode } from '../utils/textCharacters';

export type VisibleTextMarksMode = 'auto' | 'all' | 'off';

export type VisibleTextMarkerItem = {
  char: string;
  rawEnd: number;
  rawStart: number;
};

export function resolveVisibleTextMarksMode(
  marksMode: VisibleTextMarksMode | undefined,
  showInvisibles: boolean | undefined,
): VisibleTextMarksMode {
  if (marksMode) {
    return marksMode;
  }
  if (showInvisibles == null) {
    return 'auto';
  }
  return showInvisibles ? 'auto' : 'off';
}

export function visibleTextMarkerClassFor(char: string): string {
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

export function visibleTextWidgetText(markerText: string): string {
  return markerText.replace(/\r?\n/gu, '');
}

export function shouldRenderVisibleTextWidget(char: string, markerText: string): boolean {
  if (char === '\n' || char === '\r') {
    return true;
  }
  if (markerText.startsWith('<')) {
    return true;
  }
  const code = char.codePointAt(0);
  return code != null && (isInvisibleDirectionalOrZeroWidthCode(code) || isControlCode(code));
}

export function shouldShowVisibleTextIssueMarker(
  value: string,
  item: VisibleTextMarkerItem,
): boolean {
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
