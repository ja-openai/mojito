import { useCallback, useEffect, useMemo, useRef } from 'react';

import {
  canParseProtectedTextTokens,
  extractProtectedTextTokens,
  getProtectedTextDiagnostics,
  preservesProtectedTextTokenStructure,
  type ProtectedTextToken,
  type ProtectedTextTokenMode,
  relocateProtectedTextTokens,
} from '../utils/protectedTextTokens';

type ParseableTokenSnapshot = {
  mode: ProtectedTextTokenMode;
  protectedTokens: ProtectedTextToken[];
  value: string;
};

export function useProtectedTextTokenGuard(value: string, mode: ProtectedTextTokenMode) {
  const currentProtectedTokens = useMemo(
    () => extractProtectedTextTokens(value, mode),
    [mode, value],
  );
  const diagnostics = useMemo(() => getProtectedTextDiagnostics(value, mode), [mode, value]);
  const currentSnapshot = useMemo<ParseableTokenSnapshot | null>(
    () =>
      canParseProtectedTextTokens(value, mode)
        ? {
            mode,
            protectedTokens: currentProtectedTokens,
            value,
          }
        : null,
    [currentProtectedTokens, mode, value],
  );
  const lastParseableSnapshotRef = useRef<ParseableTokenSnapshot | null>(currentSnapshot);
  const validationSnapshot =
    currentSnapshot ??
    (lastParseableSnapshotRef.current?.mode === mode ? lastParseableSnapshotRef.current : null);
  const protectedTokens = useMemo(() => {
    if (currentSnapshot || !validationSnapshot) {
      return currentProtectedTokens;
    }

    return (
      relocateProtectedTextTokens(
        validationSnapshot.value,
        validationSnapshot.protectedTokens,
        value,
      ) ?? currentProtectedTokens
    );
  }, [currentProtectedTokens, currentSnapshot, validationSnapshot, value]);
  const validationBase = useMemo(() => {
    if (currentSnapshot || !validationSnapshot) {
      return {
        protectedTokens: currentProtectedTokens,
        value,
      };
    }

    if (validationSnapshot.protectedTokens.length === 0) {
      return {
        protectedTokens,
        value,
      };
    }

    return validationSnapshot;
  }, [currentProtectedTokens, currentSnapshot, protectedTokens, validationSnapshot, value]);

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
        previousValue: validationBase.value,
        previousTokens: validationBase.protectedTokens,
        nextValue,
        mode,
      }),
    [mode, validationBase],
  );

  return {
    diagnostics,
    protectedTokens,
    validateNextValue,
  };
}
