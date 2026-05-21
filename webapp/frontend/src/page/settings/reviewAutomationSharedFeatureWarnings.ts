import type { ApiReviewAutomationSummary } from '../../api/review-automations';

export const SHARED_FEATURE_AUTOMATION_WARNING_LIMIT = 200;

export type SharedFeatureScheduleWarning = {
  automationId: number;
  automationName: string;
  featureNames: string[];
  nextRunAt: string | null;
};

export function getSharedFeatureScheduleWarnings({
  selectedFeatureIds,
  automations,
  currentAutomationId,
}: {
  selectedFeatureIds: number[];
  automations: ApiReviewAutomationSummary[];
  currentAutomationId?: number | null;
}): SharedFeatureScheduleWarning[] {
  if (!selectedFeatureIds.length || !automations.length) {
    return [];
  }

  const selectedFeatureIdSet = new Set(selectedFeatureIds);
  return automations
    .filter((automation) => automation.enabled && automation.id !== currentAutomationId)
    .map((automation) => {
      const featureNames = automation.features
        .filter((feature) => selectedFeatureIdSet.has(feature.id))
        .map((feature) => feature.name);
      return {
        automationId: automation.id,
        automationName: automation.name,
        featureNames,
        nextRunAt: automation.trigger?.nextRunAt ?? null,
      };
    })
    .filter((warning) => warning.featureNames.length > 0)
    .sort((left, right) => {
      if (left.nextRunAt && right.nextRunAt) {
        return left.nextRunAt.localeCompare(right.nextRunAt);
      }
      if (left.nextRunAt) {
        return -1;
      }
      if (right.nextRunAt) {
        return 1;
      }
      return left.automationName.localeCompare(right.automationName);
    });
}
