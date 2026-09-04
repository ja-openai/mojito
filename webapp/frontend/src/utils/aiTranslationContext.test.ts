// @vitest-environment node

import { describe, expect, it } from 'vitest';

import { buildAiTranslationContextMessage } from './aiTranslationContext';

describe('buildAiTranslationContextMessage', () => {
  it.each(['', 'A normal translation.', 'Bonjour !'])(
    'leaves a target without warnings or special spaces eligible for cached review: %j',
    (target) => {
      expect(buildAiTranslationContextMessage([], target)).toBeNull();
    },
  );

  it('does not send stale presence warnings when the current target has no non-breaking spaces', () => {
    expect(
      buildAiTranslationContextMessage(
        [
          { code: 'nbsp', message: 'Contains non-breaking spaces.' },
          { code: 'nnbsp', message: 'Contains narrow non-breaking spaces.' },
        ],
        'Bonjour !',
      ),
    ).toBeNull();
  });

  it('observes both non-breaking space types directly from the current target', () => {
    const message = buildAiTranslationContextMessage([], 'A\u00a0B\u202f!');

    expect(message?.role).toBe('user');
    expect(message?.content).toContain('NBSP (U+00A0)');
    expect(message?.content).toContain('NNBSP (U+202F)');
    expect(message?.content).not.toContain('deterministic translation quality warnings');
  });

  it('preserves real boundary and control warnings while removing presence-only warnings', () => {
    const message = buildAiTranslationContextMessage(
      [
        { code: 'leading-space', message: 'Unexpected leading whitespace at start.' },
        { code: 'nbsp', message: 'NBSP presence must not be a quality warning.' },
        { code: 'trailing-space', message: 'Trailing whitespace does not match source.' },
        { code: 'nnbsp', message: 'NNBSP presence must not be a quality warning.' },
        { code: 'control', message: 'Contains control characters.' },
      ],
      '\u00a0Text\u0007\u202f',
    );

    expect(message?.content).toContain('leading-space: Unexpected leading whitespace at start.');
    expect(message?.content).toContain(
      'trailing-space: Trailing whitespace does not match source.',
    );
    expect(message?.content).toContain('control: Contains control characters.');
    expect(message?.content).not.toContain('presence must not be a quality warning.');
    expect(message?.content).toContain('NBSP (U+00A0)');
    expect(message?.content).toContain('NNBSP (U+202F)');
  });

  it('keeps non-space quality warnings when no neutral observations are needed', () => {
    const message = buildAiTranslationContextMessage(
      [{ code: 'double-space', message: 'Contains repeated spaces.' }],
      'Hello  world',
    );

    expect(message?.role).toBe('user');
    expect(message?.content).toContain('double-space: Contains repeated spaces.');
    expect(message?.content).not.toContain('NBSP (U+00A0)');
    expect(message?.content).not.toContain('NNBSP (U+202F)');
  });

  it('asks for locale and style assessment without treating presence alone as a defect', () => {
    const message = buildAiTranslationContextMessage([], 'Bonjour\u00a0!');

    expect(message?.content).toMatch(/presence[^.]*not[^.]*defect/i);
    expect(message?.content).toMatch(/locale/i);
    expect(message?.content).toMatch(/style/i);
    expect(message?.content).toMatch(/specific[^.]*misuse/i);
  });

  it('uses one-based Unicode code point positions after supplementary characters', () => {
    const message = buildAiTranslationContextMessage([], '😀\u00a0𐐀X\u00a0\u202f');
    const lines = message?.content.split('\n') ?? [];
    const nbsp = lines.find((line) => line.includes('NBSP (U+00A0)')) ?? '';
    const nnbsp = lines.find((line) => line.includes('NNBSP (U+202F)')) ?? '';

    expect(nbsp).toMatch(/positions?[^\d]*2, 5\b/i);
    expect(nnbsp).toMatch(/positions?[^\d]*6\b/i);
  });

  it('bounds positions independently for each space type while retaining total counts', () => {
    const message = buildAiTranslationContextMessage([], '\u00a0'.repeat(25) + '\u202f'.repeat(25));
    const lines = message?.content.split('\n') ?? [];
    const nbsp = lines.find((line) => line.includes('NBSP (U+00A0)')) ?? '';
    const nnbsp = lines.find((line) => line.includes('NNBSP (U+202F)')) ?? '';

    expect(nbsp).toContain(Array.from({ length: 20 }, (_, index) => index + 1).join(', '));
    expect(nnbsp).toContain(Array.from({ length: 20 }, (_, index) => index + 26).join(', '));
    expect(nbsp).not.toMatch(/20, 21\b/);
    expect(nnbsp).not.toMatch(/45, 46\b/);
    expect(nbsp).toMatch(/count[:=]?\s*25|25 occurrences|25 total/i);
    expect(nnbsp).toMatch(/count[:=]?\s*25|25 occurrences|25 total/i);
  });
});
