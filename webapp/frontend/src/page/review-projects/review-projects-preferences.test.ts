import { beforeEach, describe, expect, it } from 'vitest';

import {
  loadDefaultReviewProjectTeamIds,
  normalizeDefaultReviewProjectTeamIds,
  saveDefaultReviewProjectTeamIds,
} from './review-projects-preferences';

describe('reviewProjectsPreferences', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('normalizes default review project team ids', () => {
    expect(normalizeDefaultReviewProjectTeamIds([2, 1, 2, 0, -1, 1.9, null, '3'])).toEqual([2, 1]);
  });

  it('saves default review project team ids per user', () => {
    saveDefaultReviewProjectTeamIds([3, 2, 3], 'pm@example.com');

    expect(loadDefaultReviewProjectTeamIds('pm@example.com')).toEqual([3, 2]);
    expect(loadDefaultReviewProjectTeamIds('admin@example.com')).toEqual([]);
  });

  it('clears empty default review project team ids', () => {
    saveDefaultReviewProjectTeamIds([3], 'pm@example.com');
    saveDefaultReviewProjectTeamIds([], 'pm@example.com');

    expect(loadDefaultReviewProjectTeamIds('pm@example.com')).toEqual([]);
  });
});
