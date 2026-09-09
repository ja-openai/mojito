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
    access.assertCanRead(task());
  }

  @Test
  public void anotherUserCannotReadReview() {
    when(users.getCurrentUser()).thenReturn(Optional.of(user(8L)));
    when(tasks.getCreatedByUserIdWithAncestorFallback(91L)).thenReturn(7L);
    assertEquals(
        HttpStatus.NOT_FOUND,
        assertThrows(ResponseStatusException.class, () -> access.assertCanRead(task()))
            .getStatusCode());
  }

  @Test
  public void missingUserOrOwnerFailsClosed() {
    when(users.getCurrentUser()).thenReturn(Optional.empty());
    assertThrows(ResponseStatusException.class, () -> access.assertCanRead(task()));
    when(users.getCurrentUser()).thenReturn(Optional.of(user(7L)));
    assertThrows(ResponseStatusException.class, () -> access.assertCanRead(task()));
  }

  @Test
  public void unrelatedTasksKeepExistingAccess() {
    PollableTask task = task();
    task.setName("unrelated job");
    access.assertCanRead(task);
    access.assertCanRead(null);
    verifyNoInteractions(tasks, users);
  }

  private User user(long id) {
    User user = new User();
    user.setId(id);
    return user;
  }

  private PollableTask task() {
    PollableTask task = new PollableTask();
    task.setId(91L);
    task.setName(AiReviewChatJob.class.getCanonicalName());
    return task;
  }
}
