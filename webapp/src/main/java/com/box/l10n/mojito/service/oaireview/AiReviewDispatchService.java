package com.box.l10n.mojito.service.oaireview;

import static com.box.l10n.mojito.quartz.QuartzPollableJob.INPUT;
import static com.box.l10n.mojito.quartz.QuartzPollableJob.POLLABLE_TASK_ID;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatRequest;
import com.box.l10n.mojito.service.oaireview.AiReviewExecutionStore.Claim;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Prepared;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Direct provider futures, no interactive queue. Restarted requests fail instead of resubmitting.
 */
@Service
public class AiReviewDispatchService {
  private static final Logger logger = LoggerFactory.getLogger(AiReviewDispatchService.class);
  private final AiReviewExecutionStore store;
  private final AiReviewChatWS chat;
  private final AiReviewInteractiveService interactive;
  private final PollableTaskBlobStorage blobs;
  private final ObjectMapper mapper;
  private final MeterRegistry metrics;
  private final String ownerId = UUID.randomUUID().toString();

  private record LocalCall(long taskId, CompletableFuture<?> future) {}

  private final Map<String, LocalCall> inFlight = new ConcurrentHashMap<>();
  private final ScheduledThreadPoolExecutor completions =
      new ScheduledThreadPoolExecutor(
          2,
          runnable -> {
            Thread thread = new Thread(runnable, "ai-review-completion");
            thread.setDaemon(true);
            return thread;
          });
  private long cleanupCursor;

  public AiReviewDispatchService(
      AiReviewExecutionStore store,
      AiReviewChatWS chat,
      AiReviewInteractiveService interactive,
      PollableTaskBlobStorage blobs,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper mapper,
      MeterRegistry metrics) {
    this.store = store;
    this.chat = chat;
    this.interactive = interactive;
    this.blobs = blobs;
    this.mapper = mapper;
    this.metrics = metrics;
    completions.setRemoveOnCancelPolicy(true);
    completions.scheduleWithFixedDelay(this::checkCancellations, 2, 2, TimeUnit.SECONDS);
  }

  /** Returns after starting HTTP, not its response. Full capacity produces a prompt busy result. */
  public boolean start(long taskId, Prepared prepared) {
    prepared = interactive.enforceExecutionPolicy(prepared);
    Claim claim = store.tryClaim(taskId, ownerId, prepared.settings());
    switch (claim.disposition()) {
      case DONE -> {
        return false;
      }
      case FINISH -> {
        completions.execute(() -> finish(taskId));
        return false;
      }
      case WAIT -> {
        if (claim.token() == null) {
          metrics.counter("AiReviewExecution.busy").increment();
          store.rejectBusy(taskId);
          completions.execute(() -> finish(taskId));
        }
        // A duplicate with a persisted attempt never sends another provider request.
        return false;
      }
      case START -> {
        launch(taskId, claim, prepared);
        return true;
      }
    }
    throw new IllegalStateException("Unknown review admission state");
  }

  /** Compatibility drain only. New submissions never create Quartz jobs. */
  public void dispatch(JobExecutionContext context) {
    long taskId = context.getMergedJobDataMap().getLong(POLLABLE_TASK_ID);
    String json = context.getMergedJobDataMap().getString(INPUT);
    Prepared prepared;
    if (AiReviewConfiguredChatJob.class.equals(context.getJobDetail().getJobClass())) {
      prepared =
          json == null
              ? blobs.getInput(taskId, Prepared.class)
              : mapper.readValueUnchecked(json, Prepared.class);
    } else {
      AiReviewChatRequest request =
          json == null
              ? blobs.getInput(taskId, AiReviewChatRequest.class)
              : mapper.readValueUnchecked(json, AiReviewChatRequest.class);
      prepared = interactive.prepareLegacyJob(request, taskId);
    }
    start(taskId, prepared);
    // Quartz retires the old one-shot trigger on return. Completion is independently owned.
  }

  private void launch(long taskId, Claim claim, Prepared prepared) {
    try {
      AiReviewChatWS.ReviewCall call =
          chat.chatPreparedCall(
              prepared, taskId, claim.deadline(), () -> store.isActive(taskId, claim.token()));
      CompletableFuture<AiReviewChatWS.AiReviewChatResponse> future = call.result();
      inFlight.put(claim.token(), new LocalCall(taskId, future));
      future.whenComplete(
          (response, failure) -> {
            AiReviewChatJob.Result result =
                failure == null ? new AiReviewChatJob.Result(response, null) : failure(failure);
            call.transportSettled()
                .thenRun(() -> completions.execute(() -> complete(taskId, claim, result)));
          });
      metrics.counter("AiReviewExecution.dispatched", "transport", "direct").increment();
    } catch (RuntimeException exception) {
      completions.execute(() -> complete(taskId, claim, failure(exception)));
    }
  }

  private void complete(long taskId, Claim claim, AiReviewChatJob.Result result) {
    try {
      store.stageResult(taskId, claim.token(), result);
      inFlight.remove(claim.token());
      finish(taskId);
      // Make the result available before any contended capacity-accounting writes.
      if (!store.releaseCapacity(claim.token())) retryCapacityRelease(claim, 1);
    } catch (RuntimeException exception) {
      logger.warn("AI review completion will retry, taskId={}", taskId, exception);
      if (!completions.isShutdown() && Instant.now().isBefore(claim.deadline().plusSeconds(30))) {
        completions.schedule(() -> complete(taskId, claim, result), 1, TimeUnit.SECONDS);
      } else {
        inFlight.remove(claim.token());
        // Cleanup materializes a staged result or fails at the original deadline.
      }
    }
  }

  private void retryCapacityRelease(Claim claim, int delaySeconds) {
    if (completions.isShutdown() || !Instant.now().isBefore(claim.deadline())) return;
    completions.schedule(
        () -> {
          if (!store.releaseCapacity(claim.token()))
            retryCapacityRelease(claim, Math.min(delaySeconds * 2, 10));
        },
        delaySeconds,
        TimeUnit.SECONDS);
  }

  private void finish(long taskId) {
    try {
      store.finishStaged(taskId);
    } catch (RuntimeException exception) {
      logger.warn(
          "AI review output persistence will be retried by cleanup, taskId={}", taskId, exception);
    }
  }

  public void cancel(long taskId) {
    store.cancel(taskId);
    inFlight.values().stream()
        .filter(call -> call.taskId() == taskId)
        .forEach(call -> call.future().cancel(true));
    completions.execute(() -> finish(taskId));
    // Every process checks its own futures, reaching the owning API instance without affinity.
  }

  /** Keyset pagination avoids repeatedly scanning a still-active first page. */
  @Scheduled(initialDelayString = "5000", fixedDelayString = "5000")
  public void cleanup() {
    try {
      List<Long> ids = store.unfinishedIds(cleanupCursor, 100);
      if (ids.isEmpty()) {
        cleanupCursor = 0;
        return;
      }
      for (Long id : ids) {
        cleanupCursor = id;
        try {
          store.expire(id);
          if (store.getStagedResult(id).isPresent()) finish(id);
        } catch (RuntimeException exception) {
          logger.warn("AI review cleanup will retry, taskId={}", id, exception);
        }
      }
      if (ids.size() < 100) cleanupCursor = 0;
    } catch (RuntimeException exception) {
      logger.warn("AI review cleanup is temporarily unavailable", exception);
    }
  }

  private void checkCancellations() {
    inFlight.forEach(
        (token, call) -> {
          try {
            if (!store.isActive(call.taskId(), token)) call.future().cancel(true);
          } catch (RuntimeException unavailable) {
            // The fixed provider deadline still applies while the database is unavailable.
            logger.warn(
                "Unable to check review cancellation, taskId={}", call.taskId(), unavailable);
          }
        });
  }

  static AiReviewChatJob.Result failure(Throwable exception) {
    while (exception instanceof CompletionException && exception.getCause() != null)
      exception = exception.getCause();
    if (exception instanceof CancellationException)
      return AiReviewExecutionStore.error(409, "AI review was cancelled.");
    if (exception instanceof ResponseStatusException status) {
      return AiReviewExecutionStore.error(
          status.getStatusCode().value(),
          status.getReason() == null ? "AI review failed. Please retry." : status.getReason());
    }
    return AiReviewExecutionStore.error(500, "AI review failed. Please retry.");
  }

  @PreDestroy
  void stop() {
    inFlight.values().forEach(call -> call.future().cancel(true));
    completions.shutdown();
  }
}
