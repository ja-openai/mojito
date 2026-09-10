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
  const relocatedTokens = useMemo(
    () =>
      !currentSnapshot && validationSnapshot
        ? relocateProtectedTextTokens(
            validationSnapshot.value,
            validationSnapshot.protectedTokens,
            value,
          )
        : null,
    [currentSnapshot, validationSnapshot, value],
  );
  const acceptedTokenRemoval = !currentSnapshot && validationSnapshot && relocatedTokens === null;
  const protectedTokens = useMemo(() => {
    if (currentSnapshot || !validationSnapshot) {
      return currentProtectedTokens;
    }

    if (relocatedTokens) {
      return relocatedTokens;
    }

    // History or an allowed whole-token deletion may restore an incomplete draft without a
    // newly added token. Keep its surviving tokens protected; validation of proposed edits
    // still uses strict relocation and cannot use this accepted-value-only fallback.
    return (
      relocateProtectedTextTokens(
        validationSnapshot.value,
        validationSnapshot.protectedTokens,
        value,
        { allowMissingTokens: true },
      ) ?? currentProtectedTokens
    );
  }, [currentProtectedTokens, currentSnapshot, relocatedTokens, validationSnapshot, value]);
  const validationBase = useMemo(() => {
    if (
      currentSnapshot ||
      !validationSnapshot ||
      acceptedTokenRemoval ||
      validationSnapshot.protectedTokens.length === 0
    ) {
      return {
        protectedTokens,
        value,
      };
    }

    return validationSnapshot;
  }, [acceptedTokenRemoval, currentSnapshot, protectedTokens, validationSnapshot, value]);

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
