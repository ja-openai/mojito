import { useCallback, useEffect, useMemo, useRef } from 'react';

import {
  canParseProtectedTextTokens,
  extractProtectedTextTokens,
  preservesProtectedTextTokenStructure,
  type ProtectedTextToken,
  type ProtectedTextTokenMode,
} from '../utils/protectedTextTokens';

type ParseableTokenSnapshot = {
  mode: ProtectedTextTokenMode;
  protectedTokens: ProtectedTextToken[];
  value: string;
};

export function useProtectedTextTokenGuard(value: string, mode: ProtectedTextTokenMode) {
  const protectedTokens = useMemo(() => extractProtectedTextTokens(value, mode), [mode, value]);
  const currentSnapshot = useMemo<ParseableTokenSnapshot | null>(
    () =>
      canParseProtectedTextTokens(value, mode)
        ? {
            mode,
            protectedTokens,
            value,
          }
        : null,
    [mode, protectedTokens, value],
  );
  const lastParseableSnapshotRef = useRef<ParseableTokenSnapshot | null>(currentSnapshot);
  const validationSnapshot =
    currentSnapshot ??
    (lastParseableSnapshotRef.current?.mode === mode ? lastParseableSnapshotRef.current : null);

  useEffect(() => {
    if (currentSnapshot) {
      lastParseableSnapshotRef.current = currentSnapshot;
    } else if (lastParseableSnapshotRef.current?.mode !== mode) {
      lastParseableSnapshotRef.current = null;
    }
  }, [currentSnapshot, mode]);

  const validateNextValue = useCallback(
    (nextValue: string) =>
      preservesProtectedTextTokenStructure({
        previousValue: validationSnapshot?.value ?? value,
        previousTokens: validationSnapshot?.protectedTokens ?? protectedTokens,
        nextValue,
        mode,
      }),
    [mode, protectedTokens, validationSnapshot, value],
  );

  return {
    protectedTokens,
    validateNextValue,
  };
}
