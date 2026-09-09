package com.box.l10n.mojito.service.oaireview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.security.user.UserService;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class AiReviewChatJobAccessTest {

  private final PollableTaskService tasks = mock(PollableTaskService.class);
  private final UserService users = mock(UserService.class);
  private final AiReviewChatJobAccess access = new AiReviewChatJobAccess(tasks, users);

  @Test
  public void creatorCanReadTheirReview() {
    when(users.getCurrentUser()).thenReturn(Optional.of(user(7L)));
    when(tasks.getCreatedByUserIdWithAncestorFallback(91L)).thenReturn(7L);
    for (String jobName : reviewJobNames()) {
      access.assertCanRead(task(jobName));
    }
  }

  @Test
  public void anotherUserCannotReadReview() {
    when(users.getCurrentUser()).thenReturn(Optional.of(user(8L)));
    when(tasks.getCreatedByUserIdWithAncestorFallback(91L)).thenReturn(7L);
    for (String jobName : reviewJobNames()) {
      assertEquals(
          HttpStatus.NOT_FOUND,
          assertThrows(ResponseStatusException.class, () -> access.assertCanRead(task(jobName)))
              .getStatusCode());
    }
  }

  @Test
  public void missingUserOrOwnerFailsClosed() {
    for (String jobName : reviewJobNames()) {
      when(users.getCurrentUser()).thenReturn(Optional.empty());
      assertThrows(ResponseStatusException.class, () -> access.assertCanRead(task(jobName)));
      when(users.getCurrentUser()).thenReturn(Optional.of(user(7L)));
      assertThrows(ResponseStatusException.class, () -> access.assertCanRead(task(jobName)));
    }
  }

  @Test
  public void unrelatedTasksKeepExistingAccess() {
    PollableTask task = task("unrelated job");
    access.assertCanRead(task);
    access.assertCanRead(null);
    verifyNoInteractions(tasks, users);
  }

  private User user(long id) {
    User user = new User();
    user.setId(id);
    return user;
  }

  private PollableTask task(String jobName) {
    PollableTask task = new PollableTask();
    task.setId(91L);
    task.setName(jobName);
    return task;
  }

  private List<String> reviewJobNames() {
    return List.of(
        AiReviewChatJob.class.getCanonicalName(),
        AiReviewConfiguredChatJob.class.getCanonicalName());
  }
}
