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

    expect(result.current.protectedTokens).toEqual([]);
    expect(result.current.validateNextValue('Hello {name}. Again.')).toBe(true);
    expect(result.current.validateNextValue('Hello {name}. {price}')).toBe(false);
  });
});
