package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.security.UserDetailsImpl;
import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService.Request;
import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService.Result;
import com.box.l10n.mojito.service.agentreview.ManualIncidentReviewService.Preview;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamService;
import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class IncidentReviewJobService {
  public record Command(Request request, Long requestedByUserId) {}

  private final QuartzPollableTaskScheduler scheduler;
  private final IncidentReviewBatchService batches;
  private final ManualIncidentReviewService manual;
  private final UserService users;
  private final TeamService teams;
  private final PollableTaskService tasks;
  private final PollableTaskBlobStorage outputs;

  public IncidentReviewJobService(
      QuartzPollableTaskScheduler scheduler,
      IncidentReviewBatchService batches,
      ManualIncidentReviewService manual,
      UserService users,
      TeamService teams,
      PollableTaskService tasks,
      PollableTaskBlobStorage outputs) {
    this.scheduler = scheduler;
    this.batches = batches;
    this.manual = manual;
    this.users = users;
    this.teams = teams;
    this.tasks = tasks;
    this.outputs = outputs;
  }

  public PollableFuture<Preview> preview(Request request) {
    Command command = command(request);
    return scheduler.scheduleJob(
        QuartzJobInfo.newBuilder(IncidentReviewPreviewJob.class)
            .withInlineInput(false)
            .withInput(command)
            .withMessage("Preparing incident review preview")
            .build());
  }

  public PollableFuture<Result> create(Request request) {
    Command command = command(request);
    return scheduler.scheduleJob(
        QuartzJobInfo.newBuilder(IncidentReviewCreateJob.class)
            .withInlineInput(false)
            .withInput(command)
            .withMessage("Preparing incident review projects")
            .build());
  }

  private Command command(Request request) {
    Long actor = teams.getCurrentUserIdOrThrow();
    batches.validateRequest(request, actor);
    return new Command(request, actor);
  }

  public Preview preview(Command command, long taskId) {
    return asRequester(
        command,
        () -> {
          Preview preview =
              manual.preview(
                  command.request(),
                  command.requestedByUserId(),
                  message -> tasks.updateMessage(taskId, message));
          tasks.updateMessage(
              taskId,
              "Preview ready: "
                  + preview.eligibleIncidentCount()
                  + " eligible incidents in "
                  + preview.projectCount()
                  + " projects");
          return preview;
        });
  }

  public Result create(Command command, long taskId) {
    return asRequester(
        command,
        () ->
            manual.create(
                command.request(),
                command.requestedByUserId(),
                message -> tasks.updateMessage(taskId, message),
                result -> outputs.saveOutput(taskId, result)));
  }

  private <T> T asRequester(Command command, Supplier<T> action) {
    var user =
        users
            .getUserById(command.requestedByUserId())
            .filter(candidate -> Boolean.TRUE.equals(candidate.getEnabled()))
            .orElseThrow(
                () -> new AccessDeniedException("The requesting user is no longer active"));
    SecurityContext previous = SecurityContextHolder.getContext();
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    UserDetailsImpl principal = new UserDetailsImpl(user);
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(principal, "", principal.getAuthorities()));
    try {
      SecurityContextHolder.setContext(context);
      batches.validateRequest(command.request(), command.requestedByUserId());
      return action.get();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }
}
