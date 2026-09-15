package com.box.l10n.mojito.service.review.feedback;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.rest.review.ReviewFeedbackWS;
import com.box.l10n.mojito.service.review.ReviewProjectService;
import com.box.l10n.mojito.service.review.ReviewProjectTextUnitRepository;
import com.box.l10n.mojito.service.team.TeamService;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

public class ReviewFeedbackAccessTest {
  @Test
  public void historicalContextRequiresExistingReviewAuthorizationAndPatternsRequireAdmin() {
    var projects = mock(ReviewProjectService.class);
    var rows = mock(ReviewProjectTextUnitRepository.class);
    var capture = mock(ReviewFeedbackCaptureService.class);
    var patterns = mock(ReviewFeedbackPatterns.class);
    var teams = mock(TeamService.class);
    var controller = new ReviewFeedbackWS(projects, rows, capture, patterns, teams);
    when(projects.getReviewProjectTextUnit(1L)).thenThrow(new AccessDeniedException("denied"));
    assertThrows(AccessDeniedException.class, () -> controller.baseline(1L));
    verifyNoInteractions(rows, capture);
    assertEquals(
        403,
        assertThrows(ResponseStatusException.class, controller::patterns).getStatusCode().value());
    verifyNoInteractions(patterns);
    when(teams.isCurrentUserAdmin()).thenReturn(true);
    controller.patterns();
    verify(patterns).report();
  }
}
