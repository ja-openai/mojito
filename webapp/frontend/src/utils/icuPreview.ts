import { parseIcuMessage } from './icuMessageFormat';

export type IcuPreviewMode = 'source' | 'target';

function normalizeMessage(value?: string | null) {
  const trimmed = value?.trim();
  if (!trimmed || trimmed === '-') {
    return null;
  }
  return trimmed;
}

export function getIcuParameterNames(message?: string | null): string[] {
  const normalized = normalizeMessage(message);
  if (!normalized || !normalized.includes('{')) {
    return [];
  }
  try {
    return parseIcuMessage(normalized).parameters.map((parameter) => parameter.name);
  } catch {
    return [];
  }
}

export function hasIcuParameters(message?: string | null): boolean {
  return getIcuParameterNames(message).length > 0;
}

export function getMissingIcuSourceParameters(
  sourceMessage?: string | null,
  targetMessage?: string | null,
): string[] {
  const sourceParameters = getIcuParameterNames(sourceMessage);
  if (sourceParameters.length === 0) {
    return [];
  }

  const targetNormalized = normalizeMessage(targetMessage);
  if (!targetNormalized) {
    return sourceParameters;
  }

  if (!targetNormalized.includes('{')) {
    return sourceParameters;
  }

  let targetParameters: Set<string>;
  try {
    targetParameters = new Set(parseIcuMessage(targetNormalized).parameters.map((p) => p.name));
  } catch {
    return sourceParameters;
  }

  return sourceParameters.filter((name) => !targetParameters.has(name));
}

export function resolveIcuPreviewMode(
  requestedMode: IcuPreviewMode,
  hasIcuSource: boolean,
  hasIcuTarget: boolean,
): IcuPreviewMode {
  if (requestedMode === 'source' && hasIcuSource) {
    return 'source';
  }
  if (hasIcuTarget) {
    return 'target';
  }
  if (hasIcuSource) {
    return 'source';
  }
  return 'target';
}
