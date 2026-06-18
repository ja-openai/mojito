import { describe, expect, it } from 'vitest';

import { normalizeReviewProjectsSessionState } from './review-projects-session-state';

describe('normalizeReviewProjectsSessionState', () => {
  it('normalizes multi-team filters', () => {
    const state = normalizeReviewProjectsSessionState({
      assignedScope: 'TO_TEAM',
      teamFilterIds: [3, 2.8, 3, 0, -1, null, '4'],
    });

    expect(state?.teamFilterIds).toEqual([3, 2]);
  });

  it('migrates legacy single-team filters', () => {
    const state = normalizeReviewProjectsSessionState({
      assignedScope: 'TO_TEAM',
      teamFilterId: 7,
    });

    expect(state?.teamFilterIds).toEqual([7]);
  });
});
