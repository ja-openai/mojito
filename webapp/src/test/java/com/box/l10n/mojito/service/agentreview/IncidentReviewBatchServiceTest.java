package com.box.l10n.mojito.service.agentreview;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.review.ReviewProjectType;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamService;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.Test;

public class IncidentReviewBatchServiceTest {
  private final UserService users = mock(UserService.class);
  private final TeamService teams = mock(TeamService.class);
  private final IncidentReviewBatchService service =
      new IncidentReviewBatchService(
          null, users, teams, null, null, null, null, null, null, null, null, null, null, null,
          null);

  @Test
  public void terminologyLabelCanCreateIncidentTranslationReview() {
    authorize();

    service.validateRequest(request(ReviewProjectType.TERMINOLOGY_CLEANUP), 7L);

    verify(teams).assertCurrentUserCanAccessTeam(3L);
  }

  @Test
  public void glossaryProjectTypesCannotCreateIncidentTranslationReview() {
    authorize();

    for (ReviewProjectType type :
        List.of(ReviewProjectType.TERMINOLOGY, ReviewProjectType.TERM_CANDIDATE)) {
      assertThatThrownBy(() -> service.validateRequest(request(type), 7L))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Incident review does not use terminology project types");
    }
  }

  private void authorize() {
    when(users.isCurrentUserAdminOrPm()).thenReturn(true);
    when(teams.getCurrentUserIdOrThrow()).thenReturn(7L);
  }

  private IncidentReviewBatchService.Request request(ReviewProjectType type) {
    return new IncidentReviewBatchService.Request(
        List.of(1L),
        null,
        List.of("fr-FR"),
        null,
        null,
        3L,
        "Term cleanup",
        ZonedDateTime.now().plusDays(1),
        1500,
        false,
        type,
        null,
        null);
  }
}
