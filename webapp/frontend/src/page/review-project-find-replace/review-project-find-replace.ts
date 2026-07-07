import type { ApiReviewProjectDetail, ApiReviewProjectTextUnit } from '../../api/review-projects';
import {
  countFindReplaceMatches,
  type FindReplaceFindOptions,
  type FindReplaceOptions,
  replaceFindReplaceText,
} from '../../utils/findReplace';
import {
  extractProtectedTextTokens,
  preservesProtectedTextTokenStructure,
} from '../../utils/protectedTextTokens';
import { textsAreEquivalent } from '../../utils/textEquivalence';

export type ReviewProjectFindReplaceOptions = FindReplaceOptions;

export type ReviewProjectFindReplaceRow = {
  id: number;
  textUnitId: number | null;
  name: string;
  locale: string;
  source: string;
  sourceComment: string | null;
  originalTarget: string;
  workingTarget: string;
  status: string | null;
  targetStatus: string;
  targetComment: string | null;
  existingDecisionNotes: string | null;
  includedInLocalizedFile: boolean;
  expectedCurrentTmTextUnitVariantId: number | null;
  assetPath: string | null;
  hasStagedSuggestion: boolean;
};

export type ReviewProjectFindReplacePlanTarget = {
  rowId: number;
  matchCount: number;
  nextTarget: string;
};

export type ReviewProjectFindReplaceIntegrityIssue = {
  rowId: number;
  message: string;
};

export type ReviewProjectFindReplacePlan = {
  blockedTargets: ReviewProjectFindReplaceIntegrityIssue[];
  targets: ReviewProjectFindReplacePlanTarget[];
  blockedMatches: number;
  totalMatches: number;
};

export type ReviewProjectFindReplaceVisibleRow = {
  row: ReviewProjectFindReplaceRow;
  changed: boolean;
  currentMatchCount: number;
};

export function buildReviewProjectFindReplaceRows(
  project: ApiReviewProjectDetail,
): ReviewProjectFindReplaceRow[] {
  const locale = project.locale?.bcp47Tag ?? 'No locale';

  return (project.reviewProjectTextUnits ?? []).map((textUnit) =>
    buildReviewProjectFindReplaceRow(textUnit, locale),
  );
}

export function buildReviewProjectFindReplaceRow(
  textUnit: ApiReviewProjectTextUnit,
  locale: string,
): ReviewProjectFindReplaceRow {
  const currentVariant =
    textUnit.currentTmTextUnitVariant?.id != null ? textUnit.currentTmTextUnitVariant : null;
  const variant = getEffectiveVariant(textUnit);
  const stagedSuggestion = textUnit.reviewProjectTextUnitSuggestion;
  const existingDecisionNotes = textUnit.reviewProjectTextUnitDecision?.notes ?? null;
  const originalTarget = variant?.content ?? '';
  return {
    id: textUnit.id,
    textUnitId: textUnit.tmTextUnit?.id ?? null,
    name: textUnit.tmTextUnit?.name != null ? String(textUnit.tmTextUnit.name) : 'Unmapped',
    locale,
    source: textUnit.tmTextUnit?.content ?? '',
    sourceComment: textUnit.tmTextUnit?.comment ?? null,
    originalTarget,
    workingTarget: stagedSuggestion?.target ?? originalTarget,
    status: stagedSuggestion ? 'REVIEW_NEEDED' : getStatusKey(variant),
    targetStatus:
      variant?.status ??
      (variant?.includedInLocalizedFile === false ? 'TRANSLATION_NEEDED' : 'REVIEW_NEEDED'),
    targetComment: variant?.comment ?? null,
    existingDecisionNotes,
    includedInLocalizedFile: variant?.includedInLocalizedFile ?? true,
    expectedCurrentTmTextUnitVariantId: currentVariant?.id ?? null,
    assetPath:
      textUnit.tmTextUnit?.asset?.assetPath != null
        ? String(textUnit.tmTextUnit.asset.assetPath)
        : null,
    hasStagedSuggestion: stagedSuggestion?.target != null,
  };
}

export function buildReviewProjectFindReplacePlan(
  rows: ReviewProjectFindReplaceRow[],
  options: ReviewProjectFindReplaceOptions,
): ReviewProjectFindReplacePlan {
  const plan: ReviewProjectFindReplacePlan = {
    blockedTargets: [],
    blockedMatches: 0,
    targets: [],
    totalMatches: 0,
  };

  if (!options.findText) {
    return plan;
  }

  for (const row of rows) {
    const replacement = replaceFindReplaceText(row.workingTarget, options);
    if (replacement.count === 0 || textsAreEquivalent(replacement.value, row.workingTarget)) {
      continue;
    }

    const integrityMessage = getReviewProjectFindReplaceIntegrityMessage(
      row.workingTarget,
      replacement.value,
    );
    if (integrityMessage) {
      plan.blockedTargets.push({
        rowId: row.id,
        message: integrityMessage,
      });
      plan.blockedMatches += replacement.count;
      continue;
    }

    plan.targets.push({
      rowId: row.id,
      matchCount: replacement.count,
      nextTarget: replacement.value,
    });
    plan.totalMatches += replacement.count;
  }

  return plan;
}

export function applyReviewProjectFindReplacePlan(
  rows: ReviewProjectFindReplaceRow[],
  plan: ReviewProjectFindReplacePlan,
): ReviewProjectFindReplaceRow[] {
  if (plan.targets.length === 0) {
    return rows;
  }

  const replacementsByRowId = new Map(plan.targets.map((target) => [target.rowId, target]));
  return rows.map((row) => {
    const replacement = replacementsByRowId.get(row.id);
    if (!replacement) {
      return row;
    }
    return {
      ...row,
      workingTarget: replacement.nextTarget,
    };
  });
}

export function getReviewProjectFindReplaceVisibleRows(
  rows: ReviewProjectFindReplaceRow[],
  options: ReviewProjectFindReplaceOptions,
): ReviewProjectFindReplaceVisibleRow[] {
  return rows
    .map((row) => ({
      row,
      changed: isReviewProjectFindReplaceRowChanged(row),
      currentMatchCount: countLiteralMatches(row.workingTarget, options),
    }))
    .filter(({ changed, currentMatchCount }) => {
      if (!options.findText) {
        return true;
      }
      return changed || currentMatchCount > 0;
    });
}

export function countChangedReviewProjectFindReplaceRows(
  rows: ReviewProjectFindReplaceRow[],
): number {
  return rows.filter(isReviewProjectFindReplaceRowChanged).length;
}

export function getReviewProjectFindReplaceIntegrityIssues(
  rows: ReviewProjectFindReplaceRow[],
): ReviewProjectFindReplaceIntegrityIssue[] {
  return rows
    .map((row) => {
      if (!isReviewProjectFindReplaceRowChanged(row)) {
        return null;
      }

      const message = getReviewProjectFindReplaceIntegrityMessage(
        row.originalTarget,
        row.workingTarget,
      );
      return message ? { rowId: row.id, message } : null;
    })
    .filter((issue): issue is ReviewProjectFindReplaceIntegrityIssue => Boolean(issue));
}

export function countLiteralMatches(value: string, options: FindReplaceFindOptions): number {
  return countFindReplaceMatches(value, options);
}

export function isReviewProjectFindReplaceRowChanged(row: ReviewProjectFindReplaceRow): boolean {
  return !textsAreEquivalent(row.originalTarget, row.workingTarget);
}

export function getReviewProjectFindReplaceIntegrityMessage(
  previousTarget: string,
  nextTarget: string,
): string | null {
  const previousTokens = extractProtectedTextTokens(previousTarget, 'icu-html');
  const preservesProtectedSyntax = preservesProtectedTextTokenStructure({
    previousValue: previousTarget,
    previousTokens,
    nextValue: nextTarget,
    mode: 'icu-html',
  });

  return preservesProtectedSyntax
    ? null
    : 'Changing this text would alter protected placeholders, markup, or platform syntax.';
}

function getEffectiveVariant(textUnit: ApiReviewProjectTextUnit) {
  const current = textUnit.currentTmTextUnitVariant;
  return current?.id != null ? current : textUnit.baselineTmTextUnitVariant;
}

function getStatusKey(
  variant: ApiReviewProjectTextUnit['baselineTmTextUnitVariant'] | null | undefined,
): string | null {
  if (!variant) {
    return null;
  }
  if (variant.includedInLocalizedFile === false) {
    return 'REJECTED';
  }
  return variant.status ?? null;
}
