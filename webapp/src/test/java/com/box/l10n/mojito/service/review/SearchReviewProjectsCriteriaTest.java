package com.box.l10n.mojito.service.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class SearchReviewProjectsCriteriaTest {

  @Test
  public void normalizesTeamIds() {
    SearchReviewProjectsCriteria criteria =
        criteria(SearchReviewProjectsCriteria.AssignedScope.TO_TEAM, List.of(3L, 2L, 3L, 0L, -1L));

    assertEquals(List.of(3L, 2L), criteria.teamIds());
    assertTrue(criteria.hasTeamFilter());
  }

  @Test
  public void onlyAppliesTeamFilterForTeamScope() {
    SearchReviewProjectsCriteria criteria =
        criteria(SearchReviewProjectsCriteria.AssignedScope.TO_ME, List.of(3L, 2L));

    assertFalse(criteria.hasTeamFilter());
  }

  private SearchReviewProjectsCriteria criteria(
      SearchReviewProjectsCriteria.AssignedScope assignedScope, List<Long> teamIds) {
    return new SearchReviewProjectsCriteria(
        null,
        null,
        null,
        null,
        assignedScope,
        teamIds,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
