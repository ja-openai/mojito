package com.box.l10n.mojito.service.oaireview;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.security.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Chat requests and results belong to their requester, including on generic task endpoints. */
@Service
public class AiReviewChatJobAccess {

  private final PollableTaskService pollableTaskService;
  private final UserService userService;

  public AiReviewChatJobAccess(PollableTaskService pollableTaskService, UserService userService) {
    this.pollableTaskService = pollableTaskService;
    this.userService = userService;
  }

  public void assertCanRead(PollableTask task) {
    if (isReviewChatJob(task)) {
      Long currentUserId = userService.getCurrentUser().map(User::getId).orElse(null);
      Long ownerId = pollableTaskService.getCreatedByUserIdWithAncestorFallback(task.getId());
      if (currentUserId == null || !currentUserId.equals(ownerId)) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI review task not found.");
      }
    }
  }

  public static boolean isReviewChatJob(PollableTask task) {
    return task != null && AiReviewChatJob.class.getCanonicalName().equals(task.getName());
  }
}
