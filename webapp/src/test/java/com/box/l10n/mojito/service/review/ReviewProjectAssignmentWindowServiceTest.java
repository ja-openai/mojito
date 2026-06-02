package com.box.l10n.mojito.service.review;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentWindow;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.security.user.UserService;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class ReviewProjectAssignmentWindowServiceTest {

  private final ReviewProjectAssignmentWindowRepository assignmentWindowRepository =
      Mockito.mock(ReviewProjectAssignmentWindowRepository.class);
  private final UserService userService = Mockito.mock(UserService.class);
  private final ReviewProjectAssignmentWindowService service =
      new ReviewProjectAssignmentWindowService(assignmentWindowRepository, userService);

  @Test
  public void acceptCurrentAssignmentIfAssignedTranslatorAcceptsOpenWindow() {
    User translator = user(12L, "translator");
    ReviewProject project = project(34L, translator);
    ReviewProjectAssignmentWindow window = new ReviewProjectAssignmentWindow();
    window.setReviewProject(project);
    window.setAssignedTranslatorUser(translator);
    window.setAssignedAt(ZonedDateTime.parse("2026-06-02T12:00:00Z"));

    when(userService.getCurrentUser()).thenReturn(Optional.of(translator));
    when(assignmentWindowRepository.findOpenWindowByReviewProjectId(34L))
        .thenReturn(Optional.of(window));

    service.acceptCurrentAssignmentIfAssignedTranslator(project);

    ArgumentCaptor<ReviewProjectAssignmentWindow> captor =
        ArgumentCaptor.forClass(ReviewProjectAssignmentWindow.class);
    verify(assignmentWindowRepository).save(captor.capture());
    assertNotNull(captor.getValue().getAcceptedAt());
  }

  @Test
  public void acceptCurrentAssignmentIfAssignedTranslatorIgnoresOtherUsers() {
    User translator = user(12L, "translator");
    ReviewProject project = project(34L, translator);
    when(userService.getCurrentUser()).thenReturn(Optional.of(user(56L, "other")));

    service.acceptCurrentAssignmentIfAssignedTranslator(project);

    verify(assignmentWindowRepository, never()).save(any());
  }

  private ReviewProject project(Long id, User translator) {
    ReviewProject project = new ReviewProject();
    project.setId(id);
    project.setAssignedTranslatorUser(translator);
    return project;
  }

  private User user(Long id, String username) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    return user;
  }
}
