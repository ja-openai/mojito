package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.security.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Incident plans and creation results remain private to their requesting PM or administrator. */
@Service
public class IncidentReviewJobAccess {

  private final PollableTaskService tasks;
  private final UserService users;

  public IncidentReviewJobAccess(PollableTaskService tasks, UserService users) {
    this.tasks = tasks;
    this.users = users;
  }

  public void assertCanRead(PollableTask task) {
    if (task == null) return;
    if (IncidentReviewPreviewJob.class.getCanonicalName().equals(task.getName())
        || IncidentReviewCreateJob.class.getCanonicalName().equals(task.getName())) {
      Long currentUserId = users.getCurrentUser().map(User::getId).orElse(null);
      Long ownerId = tasks.getCreatedByUserIdWithAncestorFallback(task.getId());
      if (currentUserId == null
          || !currentUserId.equals(ownerId)
          || !users.isCurrentUserAdminOrPm()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident review task not found.");
      }
    }
  }

  /** Generic task status serializes the whole subtree, including child messages and failures. */
  public void assertCanReadTree(PollableTask task) {
    assertCanRead(task);
    if (task != null && task.getSubTasks() != null) {
      for (PollableTask child : task.getSubTasks()) assertCanReadTree(child);
    }
  }
}
