package com.box.l10n.mojito.rest.textunit;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatRequest;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatResponse;
import com.box.l10n.mojito.service.oaireview.AiReviewChatJob;
import com.box.l10n.mojito.service.oaireview.AiReviewChatJobAccess;
import com.box.l10n.mojito.service.oaireview.AiReviewConfiguredChatJob;
import com.box.l10n.mojito.service.oaireview.AiReviewDispatchService;
import com.box.l10n.mojito.service.oaireview.AiReviewExecutionProperties;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Prepared;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import java.time.ZonedDateTime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AiReviewChatJobsWS {

  private final AiReviewChatWS aiReviewChatWS;
  private final PollableTaskService pollableTaskService;
  private final PollableTaskBlobStorage blobStorage;
  private final AiReviewChatJobAccess jobAccess;
  private final ObjectMapper objectMapper;
  private final AiReviewDispatchService dispatch;
  private final AiReviewExecutionProperties execution;

  public AiReviewChatJobsWS(
      AiReviewChatWS aiReviewChatWS,
      PollableTaskService pollableTaskService,
      PollableTaskBlobStorage blobStorage,
      AiReviewChatJobAccess jobAccess,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      AiReviewDispatchService dispatch,
      AiReviewExecutionProperties execution) {
    this.aiReviewChatWS = aiReviewChatWS;
    this.pollableTaskService = pollableTaskService;
    this.blobStorage = blobStorage;
    this.jobAccess = jobAccess;
    this.objectMapper = objectMapper;
    this.dispatch = dispatch;
    this.execution = execution;
  }

  @PostMapping("/api/ai/review/jobs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public StartResponse start(@RequestBody AiReviewChatRequest request) {
    if (request == null || request.messages() == null || request.messages().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages must not be empty");
    }
    Prepared prepared = aiReviewChatWS.prepare(request);
    PollableTask task =
        pollableTaskService.createPollableTask(
            null,
            AiReviewConfiguredChatJob.class.getCanonicalName(),
            null,
            0,
            execution.resolveTimeoutSeconds(
                prepared.settings().profileId(), prepared.settings().reasoningEffort()));
    dispatch.start(task.getId(), prepared);
    return new StartResponse(task.getId());
  }

  @DeleteMapping("/api/ai/review/jobs/{taskId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void cancel(@PathVariable long taskId) {
    PollableTask task = pollableTaskService.getPollableTask(taskId);
    if (!AiReviewChatJobAccess.isReviewChatJob(task)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI review task not found.");
    }
    jobAccess.assertCanRead(task);
    dispatch.cancel(taskId);
  }

  @GetMapping("/api/ai/review/jobs/{taskId}")
  public StatusResponse get(@PathVariable long taskId) {
    PollableTask task = pollableTaskService.getPollableTask(taskId);
    if (!AiReviewChatJobAccess.isReviewChatJob(task)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI review task not found.");
    }
    jobAccess.assertCanRead(task);
    if (task.getErrorMessage() != null) {
      return failed(HttpStatus.INTERNAL_SERVER_ERROR, "AI review failed. Please retry.");
    }
    if (task.getFinishedDate() == null) {
      if (task.getCreatedDate() != null
          && task.getTimeout() != null
          && task.getTimeout() > 0
          && task.getCreatedDate().plusSeconds(task.getTimeout()).isBefore(ZonedDateTime.now())) {
        return failed(HttpStatus.GATEWAY_TIMEOUT, "AI review took too long. Please retry.");
      }
      return new StatusResponse("pending", null, null);
    }
    String outputJson =
        blobStorage
            .findOutputJson(taskId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.GONE, "AI review result is no longer available. Please retry."));
    AiReviewChatJob.Result result =
        objectMapper.readValueUnchecked(outputJson, AiReviewChatJob.Result.class);
    if (result == null) {
      return failed(
          HttpStatus.INTERNAL_SERVER_ERROR, "AI review returned an empty response. Please retry.");
    }
    if (result.error() != null) {
      return new StatusResponse("failed", null, result.error());
    }
    if (result.response() == null) {
      return failed(
          HttpStatus.INTERNAL_SERVER_ERROR, "AI review returned an empty response. Please retry.");
    }
    return new StatusResponse("completed", result.response(), null);
  }

  private StatusResponse failed(HttpStatus status, String message) {
    return new StatusResponse("failed", null, new AiReviewChatJob.Error(status.value(), message));
  }

  public record StartResponse(long taskId) {}

  public record StatusResponse(
      String status, AiReviewChatResponse response, AiReviewChatJob.Error error) {}
}
