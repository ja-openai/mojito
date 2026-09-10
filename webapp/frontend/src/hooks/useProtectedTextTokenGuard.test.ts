import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { useProtectedTextTokenGuard } from './useProtectedTextTokenGuard';

describe('useProtectedTextTokenGuard', () => {
  it('keeps the last parseable ICU structure through transient invalid edits', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useProtectedTextTokenGuard(value, 'icu'),
      {
        initialProps: {
          value: 'Hello {name}.',
        },
      },
    );

    expect(result.current.validateNextValue('Hello {name}. {')).toBe(true);

    rerender({
      value: 'Hello {name}. {',
    });

    expect(result.current.protectedTokens).toEqual([
      {
        start: 6,
        end: 12,
        label: 'ICU argument name',
        kind: 'icu-placeholder',
      },
    ]);
    expect(result.current.validateNextValue('Hello {name}. Again.')).toBe(true);
    expect(result.current.validateNextValue('Hello {name}. {price}')).toBe(true);
  });

  it('relocates protected tokens while the current ICU value is invalid', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useProtectedTextTokenGuard(value, 'icu'),
      {
        initialProps: {
          value: 'Hello {name}.',
        },
      },
    );

    rerender({
      value: 'Well, Hello {name}. {',
    });

    expect(result.current.protectedTokens).toEqual([
      {
        start: 12,
        end: 18,
        label: 'ICU argument name',
        kind: 'icu-placeholder',
      },
    ]);
  });

  it('allows completing a new ICU token from an invalid draft when there are no protected tokens', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useProtectedTextTokenGuard(value, 'icu'),
      {
        initialProps: {
          value: 'Hello ',
        },
      },
    );

    expect(result.current.validateNextValue('Hello {')).toBe(true);

    rerender({
      value: 'Hello {',
    });

    expect(result.current.validateNextValue('Hello {name}.')).toBe(true);
  });

  it('allows completing a new ICU token from an invalid draft when protected tokens exist', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useProtectedTextTokenGuard(value, 'icu'),
      {
        initialProps: {
          value: 'Hello {name}.',
        },
      },
    );

    expect(result.current.validateNextValue('Hello {name}. {')).toBe(true);

    rerender({
      value: 'Hello {name}. {',
    });

    expect(result.current.validateNextValue('Hello {name}. {price}.')).toBe(true);
  });

  it('returns malformed placeholder diagnostics for protected editor surfaces', () => {
    const { result } = renderHook(() =>
      useProtectedTextTokenGuard('Broken %1$ placeholder', 'icu-html'),
    );

    expect(result.current.diagnostics).toEqual([
      {
        start: 7,
        end: 10,
        severity: 'warning',
        code: 'placeholder-malformed',
        message: 'Placeholder-like sequence %1$ is incomplete or malformed.',
      },
    ]);
  });

  it.each([
    {
      completeValue: 'Hello {name}, {amount}',
      restoredDraft: 'Hello {name}, {a',
      completedDraft: 'Hello {name}, {amount}',
      remainingTokens: ['{name}'],
    },
    {
      completeValue: 'Hello {name}, {name}',
      restoredDraft: 'Hello {name}, {na',
      completedDraft: 'Hello {name}, {name}',
      remainingTokens: ['{name}'],
    },
    {
      completeValue: 'Hello {name} and {name}, {amount}',
      restoredDraft: 'Hello {name} and {name}, {a',
      completedDraft: 'Hello {name} and {name}, {amount}',
      remainingTokens: ['{name}', '{name}'],
    },
  ])(
    'protects surviving tokens after an accepted history restoration: $restoredDraft',
    ({ completeValue, restoredDraft, completedDraft, remainingTokens }) => {
      const { result, rerender } = renderHook(
        ({ value }) => useProtectedTextTokenGuard(value, 'icu'),
        { initialProps: { value: completeValue } },
      );

      // A proposed direct mutation must still be rejected; only accepted values can use
      // surviving-token relocation after the editor has authorized history or token deletion.
      expect(result.current.validateNextValue(restoredDraft)).toBe(false);
      rerender({ value: restoredDraft });
      expect(
        result.current.protectedTokens.map(({ start, end }) => restoredDraft.slice(start, end)),
      ).toEqual(remainingTokens);
      expect(result.current.validateNextValue(restoredDraft.replace('{name}', '{changed}'))).toBe(
        false,
      );
      expect(result.current.validateNextValue(restoredDraft.replace('{name}', ''))).toBe(false);
      expect(result.current.validateNextValue(`${restoredDraft}m`)).toBe(true);

      rerender({ value: `${restoredDraft}m` });
      expect(result.current.validateNextValue(completedDraft)).toBe(true);
      expect(
        result.current.protectedTokens.map(({ start, end }) => restoredDraft.slice(start, end)),
      ).toEqual(remainingTokens);
      rerender({ value: completedDraft });
      expect(result.current.validateNextValue(restoredDraft)).toBe(false);
    },
  );
});
