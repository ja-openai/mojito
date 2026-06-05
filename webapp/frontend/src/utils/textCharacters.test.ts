import { describe, expect, it } from 'vitest';

import {
  buildIsolateWarnings,
  buildTextAssistWarnings,
  buildVisibleTextSegments,
  escapeVisibleText,
  getVisibleTextMarker,
} from './textCharacters';

describe('textCharacters', () => {
  it('detects whitespace and invisible character issues', () => {
    expect(buildTextAssistWarnings('Save', ' Save\u200f\u00a0')).toEqual([
      { code: 'leading-space', message: 'Unexpected leading whitespace at start.' },
      { code: 'trailing-space', message: 'Unexpected trailing whitespace at end.' },
      { code: 'nbsp', message: 'Contains non-breaking spaces.' },
      {
        code: 'invisible',
        message: 'Contains invisible directional or zero-width characters.',
      },
    ]);
  });

  it('renders issue-only visible text without changing normal characters', () => {
    const segments = buildVisibleTextSegments({
      source: 'Hello',
      target: 'Hello  world',
      mode: 'issues',
    });

    expect(segments.map((segment) => segment.text).join('')).toBe('Hello ·world');
    expect(segments.some((segment) => segment.issue)).toBe(true);
  });

  it('renders all visible spaces and line breaks when requested', () => {
    const segments = buildVisibleTextSegments({
      source: 'Line one\nLine two',
      target: 'Line one\nLine two',
      mode: 'all',
    });

    expect(segments.map((segment) => segment.text).join('')).toBe('Line·one↵\nLine·two');
  });

  it('reports unbalanced isolates', () => {
    expect(buildIsolateWarnings('\u2066Nova')).toEqual([
      {
        title: 'Unclosed LRI',
        message: 'LRI at character 1 needs a later PDI.',
        index: 0,
      },
    ]);
  });

  it('escapes invisible controls for compact display', () => {
    expect(escapeVisibleText('A\u2066B\u2069')).toBe('A<LRI>B<PDI>');
  });

  it('returns marker metadata for invisible editor decorations', () => {
    expect(getVisibleTextMarker(' ')).toEqual({ label: 'space', text: '·' });
    expect(getVisibleTextMarker('\n')).toEqual({ label: 'line feed', text: '↵\n' });
    expect(getVisibleTextMarker('x')).toBeNull();
  });
});
