import type { ApiReviewProjectDetail, ApiReviewProjectTextUnit } from '../../api/review-projects';

// Synthetic near-neighbor messages keep wrong-row carryover easy to detect.
// Gujarati targets exercise non-Latin drafts without relying on deployment data.
export const carryoverFixtures = [
  {
    projectId: 9001,
    predecessor: {
      source: 'Open the red book',
      target: 'લાલ પુસ્તક ખોલો.',
    },
    next: {
      id: 9101,
      tmTextUnitId: 9201,
      source: 'Close the red book',
      target: 'લાલ પુસ્તક બંધ કરો.',
      variantId: 9301,
    },
  },
  {
    projectId: 9002,
    predecessor: {
      source: 'Open the blue book',
      target: 'વાદળી પુસ્તક ખોલો.',
    },
    next: {
      id: 9102,
      tmTextUnitId: 9202,
      source: 'Close the blue book',
      target: 'વાદળી પુસ્તક બંધ કરો.',
      variantId: 9302,
    },
  },
] as const;

export type CarryoverFixture = (typeof carryoverFixtures)[number];

export function buildCarryoverProject(
  fixture: CarryoverFixture,
): ApiReviewProjectDetail & { reviewProjectTextUnits: ApiReviewProjectTextUnit[] } {
  const buildRow = (
    id: number,
    tmTextUnitId: number,
    variantId: number,
    source: string,
    target: string,
  ): ApiReviewProjectTextUnit => ({
    id,
    reviewStateRevision: `fixture-review-state-${id}`,
    tmTextUnit: { id: tmTextUnitId, name: `fixture.${id}`, content: source },
    baselineTmTextUnitVariant: {
      id: variantId,
      content: target,
      status: 'REVIEW_NEEDED',
      includedInLocalizedFile: true,
      comment: null,
    },
    currentTmTextUnitVariant: {
      id: variantId,
      content: target,
      status: 'REVIEW_NEEDED',
      includedInLocalizedFile: true,
      comment: null,
    },
    reviewProjectTextUnitDecision: {
      decisionState: 'PENDING',
      notes: null,
      decisionTmTextUnitVariant: null,
    },
    terminologyFeedbacks: [],
  });

  return {
    id: fixture.projectId,
    type: 'NORMAL',
    status: 'OPEN',
    textUnitCount: 2,
    locale: { id: 1, bcp47Tag: 'gu' },
    reviewProjectRequest: { id: 9401, name: 'Carryover regression fixture' },
    assignment: null,
    reviewProjectTextUnits: [
      buildRow(-1, -2, -3, fixture.predecessor.source, fixture.predecessor.target),
      buildRow(
        fixture.next.id,
        fixture.next.tmTextUnitId,
        fixture.next.variantId,
        fixture.next.source,
        fixture.next.target,
      ),
    ],
  };
}
