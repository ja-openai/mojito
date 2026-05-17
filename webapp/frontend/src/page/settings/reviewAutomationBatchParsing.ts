import { DEFAULT_REVIEW_AUTOMATION_TIME_ZONE } from '../../utils/reviewAutomationSchedule';

export type ParsedReviewAutomationBatchRow = {
  lineNumber: number;
  raw: string;
  id?: number;
  action: 'create' | 'update';
  name: string;
  enabled: boolean;
  cronExpression: string;
  timeZone: string;
  teamId: number;
  teamName: string;
  dueDateOffsetDays: number;
  maxWordCountPerProject: number;
  assignTranslator: boolean;
  featureIds: number[];
  featureNames: string[];
  errors: string[];
};

export const DEFAULT_REVIEW_AUTOMATION_BATCH_MAX_WORD_COUNT = 2000;

export const formatReviewAutomationBatchRow = (row: {
  name: string;
  enabled: boolean;
  cronExpression: string;
  timeZone: string;
  teamName: string | null;
  dueDateOffsetDays: number;
  maxWordCountPerProject: number;
  assignTranslator?: boolean | null;
  featureNames: string[];
}) =>
  `${row.name} | ${row.enabled ? 'enabled' : 'disabled'} | ${row.cronExpression} | ${row.timeZone} | ${row.teamName ?? ''} | ${row.assignTranslator === false ? 'no-translator' : 'assign-translator'} | ${row.dueDateOffsetDays} | ${row.maxWordCountPerProject} | ${row.featureNames.join('; ')}`;

const normalizeAutomationName = (value: string) => value.trim().replace(/\s+/g, ' ');

const normalizeEnabled = (value?: string | null) => {
  const trimmed = (value ?? '').trim().toLowerCase();
  if (!trimmed) {
    return true;
  }
  if (['enabled', 'true', 'yes', 'on'].includes(trimmed)) {
    return true;
  }
  if (['disabled', 'false', 'no', 'off'].includes(trimmed)) {
    return false;
  }
  return null;
};

const normalizeMaxWordCount = (value?: string | null) => {
  const trimmed = (value ?? '').trim();
  if (!trimmed) {
    return DEFAULT_REVIEW_AUTOMATION_BATCH_MAX_WORD_COUNT;
  }
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed) || !Number.isInteger(parsed) || parsed < 1) {
    return null;
  }
  return parsed;
};

const normalizeDueDateOffsetDays = (value?: string | null) => {
  const trimmed = (value ?? '').trim();
  if (!trimmed) {
    return 1;
  }
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed) || !Number.isInteger(parsed) || parsed < 0) {
    return null;
  }
  return parsed;
};

const normalizeAssignTranslator = (value?: string | null) => {
  const trimmed = (value ?? '').trim().toLowerCase();
  if (!trimmed) {
    return true;
  }
  if (['assign-translator', 'assign', 'true', 'yes', 'on'].includes(trimmed)) {
    return true;
  }
  if (['no-translator', 'skip-translator', 'none', 'false', 'no', 'off'].includes(trimmed)) {
    return false;
  }
  return null;
};

const normalizeTimeZone = (value?: string | null) => {
  const trimmed = (value ?? '').trim();
  return trimmed || DEFAULT_REVIEW_AUTOMATION_TIME_ZONE;
};

const parseReviewFeatureNames = (
  value: string | undefined,
  featureIdsByName: Map<string, number>,
) => {
  const trimmed = (value ?? '').trim();
  if (!trimmed) {
    return [];
  }
  if (featureIdsByName.has(trimmed.toLowerCase())) {
    return [trimmed];
  }
  if (trimmed.includes(';')) {
    return trimmed
      .split(';')
      .map((part) => part.trim())
      .filter(Boolean);
  }
  return trimmed
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean);
};

export function parseReviewAutomationBatchInput(
  input: string,
  featureIdsByName: Map<string, number>,
  featureDisplayNamesByName: Map<string, string>,
  existingAutomationsByName: Map<string, { id: number }>,
  teamIdsByName: Map<string, number>,
  teamDisplayNamesByName: Map<string, string>,
): ParsedReviewAutomationBatchRow[] {
  const rows = input
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line, index) => {
      const parts = line.split('|').map((part) => part.trim());
      const normalizedName = normalizeAutomationName(parts[0] ?? '');
      const enabled = normalizeEnabled(parts[1] ?? '');
      const cronExpression = (parts[2] ?? '').trim();
      const timeZone = normalizeTimeZone(parts[3] ?? '');
      const teamName = (parts[4] ?? '').trim();
      const hasAssignTranslatorColumn = parts.length >= 9;
      const assignTranslator = hasAssignTranslatorColumn
        ? normalizeAssignTranslator(parts[5] ?? '')
        : true;
      const dueDateOffsetDays = normalizeDueDateOffsetDays(
        parts[hasAssignTranslatorColumn ? 6 : 5] ?? '',
      );
      const maxWordCountPerProject = normalizeMaxWordCount(
        parts[hasAssignTranslatorColumn ? 7 : 6] ?? '',
      );
      const featureNames = parseReviewFeatureNames(
        parts[hasAssignTranslatorColumn ? 8 : 7],
        featureIdsByName,
      );

      const errors: string[] = [];
      if (!normalizedName) {
        errors.push('Missing automation name');
      }
      if (enabled == null) {
        errors.push('Invalid enabled flag');
      }
      if (!cronExpression) {
        errors.push('Missing cron expression');
      }
      const normalizedTeamKey = teamName.toLowerCase();
      const teamId = normalizedTeamKey ? teamIdsByName.get(normalizedTeamKey) : null;
      if (teamId == null) {
        errors.push(`Unknown team: ${teamName || '(blank)'}`);
      }
      if (dueDateOffsetDays == null) {
        errors.push('Invalid due date offset days');
      }
      if (maxWordCountPerProject == null) {
        errors.push('Invalid max word count');
      }
      if (assignTranslator == null) {
        errors.push('Invalid translator assignment flag');
      }

      const resolvedFeatureIds: number[] = [];
      const resolvedFeatureNames: string[] = [];
      const seenFeatureIds = new Set<number>();
      for (const featureName of featureNames) {
        const key = featureName.toLowerCase();
        const featureId = featureIdsByName.get(key);
        if (featureId == null) {
          errors.push(`Unknown review feature: ${featureName}`);
          continue;
        }
        if (seenFeatureIds.has(featureId)) {
          continue;
        }
        seenFeatureIds.add(featureId);
        resolvedFeatureIds.push(featureId);
        resolvedFeatureNames.push(featureDisplayNamesByName.get(key) ?? featureName);
      }

      const existing = normalizedName
        ? existingAutomationsByName.get(normalizedName.toLowerCase())
        : undefined;
      const action: ParsedReviewAutomationBatchRow['action'] = existing ? 'update' : 'create';

      return {
        lineNumber: index + 1,
        raw: line,
        id: existing?.id,
        action,
        name: normalizedName,
        enabled: enabled ?? true,
        cronExpression,
        timeZone,
        teamId: teamId ?? -1,
        teamName: teamDisplayNamesByName.get(normalizedTeamKey) ?? teamName,
        dueDateOffsetDays: dueDateOffsetDays ?? 1,
        maxWordCountPerProject:
          maxWordCountPerProject ?? DEFAULT_REVIEW_AUTOMATION_BATCH_MAX_WORD_COUNT,
        assignTranslator: assignTranslator ?? true,
        featureIds: resolvedFeatureIds.sort((left, right) => left - right),
        featureNames: resolvedFeatureNames,
        errors,
      };
    });

  const countsByName = new Map<string, number>();
  for (const row of rows) {
    if (!row.name) {
      continue;
    }
    const key = row.name.toLowerCase();
    countsByName.set(key, (countsByName.get(key) ?? 0) + 1);
  }

  return rows.map((row) => {
    const nextErrors = [...row.errors];
    if (row.name && (countsByName.get(row.name.toLowerCase()) ?? 0) > 1) {
      nextErrors.push('Duplicate automation name in batch');
    }
    return { ...row, errors: nextErrors };
  });
}
