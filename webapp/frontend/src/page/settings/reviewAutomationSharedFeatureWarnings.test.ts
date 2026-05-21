import { describe, expect, it } from 'vitest';

import type { ApiReviewAutomationSummary } from '../../api/review-automations';
import { getSharedFeatureScheduleWarnings } from './reviewAutomationSharedFeatureWarnings';

describe('getSharedFeatureScheduleWarnings', () => {
  it('returns enabled automations that share selected features', () => {
    const warnings = getSharedFeatureScheduleWarnings({
      selectedFeatureIds: [10, 30],
      currentAutomationId: 1,
      automations: [
        automation({
          id: 1,
          name: 'Current',
          enabled: true,
          features: [{ id: 10, name: 'Product UI' }],
        }),
        automation({
          id: 2,
          name: 'Morning',
          enabled: true,
          nextRunAt: '2026-05-22T09:00:00Z',
          features: [
            { id: 10, name: 'Product UI' },
            { id: 20, name: 'Marketing' },
          ],
        }),
        automation({
          id: 3,
          name: 'Disabled',
          enabled: false,
          features: [{ id: 30, name: 'Docs' }],
        }),
        automation({
          id: 4,
          name: 'Afternoon',
          enabled: true,
          nextRunAt: '2026-05-22T15:00:00Z',
          features: [{ id: 30, name: 'Docs' }],
        }),
      ],
    });

    expect(warnings).toEqual([
      {
        automationId: 2,
        automationName: 'Morning',
        featureNames: ['Product UI'],
        nextRunAt: '2026-05-22T09:00:00Z',
      },
      {
        automationId: 4,
        automationName: 'Afternoon',
        featureNames: ['Docs'],
        nextRunAt: '2026-05-22T15:00:00Z',
      },
    ]);
  });
});

function automation({
  id,
  name,
  enabled,
  nextRunAt = null,
  features,
}: {
  id: number;
  name: string;
  enabled: boolean;
  nextRunAt?: string | null;
  features: ApiReviewAutomationSummary['features'];
}): ApiReviewAutomationSummary {
  return {
    id,
    name,
    enabled,
    createdDate: null,
    lastModifiedDate: null,
    cronExpression: '0 0 9 ? * MON-FRI',
    timeZone: 'UTC',
    team: { id: 1, name: 'Review' },
    dueDateOffsetDays: 1,
    maxWordCountPerProject: 2000,
    assignTranslator: true,
    trigger: {
      status: 'HEALTHY',
      quartzState: 'NORMAL',
      nextRunAt,
      previousRunAt: null,
      lastRunAt: null,
      lastSuccessfulRunAt: null,
      repairRecommended: false,
    },
    featureCount: features.length,
    features,
  };
}
