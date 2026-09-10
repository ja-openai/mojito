package com.box.l10n.mojito.rest.textunit;

import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatRequest;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatResponse;
import com.box.l10n.mojito.service.oaireview.AiReviewExecutionProperties;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

/**
 * Keeps the original response contract while sharing job admission, deadlines, and cancellation.
 */
@RestController
public class AiReviewChatCompatibilityWS {

  private final AiReviewChatJobsWS jobs;
  private final AiReviewExecutionProperties execution;
  private final ScheduledExecutorService statusChecks;

  @Autowired
  public AiReviewChatCompatibilityWS(
      AiReviewChatJobsWS jobs, AiReviewExecutionProperties execution) {
    this(jobs, execution, createStatusChecks());
  }

  AiReviewChatCompatibilityWS(
      AiReviewChatJobsWS jobs,
      AiReviewExecutionProperties execution,
      ScheduledExecutorService statusChecks) {
    this.jobs = jobs;
    this.execution = execution;
    this.statusChecks = statusChecks;
  }

  @PostMapping("/api/ai/review")
  public DeferredResult<AiReviewChatResponse> chat(@RequestBody AiReviewChatRequest request) {
    long taskId = jobs.start(request).taskId();
    PendingResponse pending = new PendingResponse(taskId);
    pending.start();
    return pending.result;
  }

  private static ScheduledExecutorService createStatusChecks() {
    ScheduledThreadPoolExecutor executor =
        new ScheduledThreadPoolExecutor(
            2,
            runnable -> {
              Thread thread = new Thread(runnable, "ai-review-compatibility-status");
              thread.setDaemon(true);
              return thread;
            });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  @PreDestroy
  public void stop() {
    statusChecks.shutdownNow();
  }

  private final class PendingResponse {
    private final long taskId;
    private final SecurityContext requester = SecurityContextHolder.createEmptyContext();
    private final DeferredResult<AiReviewChatResponse> result =
        new DeferredResult<>(TimeUnit.SECONDS.toMillis(execution.getTimeoutSeconds() + 30));
    private final AtomicBoolean settled = new AtomicBoolean();
    private final AtomicReference<ScheduledFuture<?>> polling = new AtomicReference<>();

    PendingResponse(long taskId) {
      this.taskId = taskId;
      requester.setAuthentication(SecurityContextHolder.getContext().getAuthentication());
      result.onTimeout(
          secured(
              () ->
                  fail(
                      new ResponseStatusException(
                          HttpStatus.GATEWAY_TIMEOUT, "AI review took too long. Please retry."))));
      result.onError(failure -> secured(() -> fail(failure)).run());
      result.onCompletion(secured(this::cancelPending));
    }

    void start() {
      try {
        ScheduledFuture<?> scheduled =
            statusChecks.scheduleWithFixedDelay(secured(this::poll), 0, 2, TimeUnit.SECONDS);
        polling.set(scheduled);
        // A quick result or disconnect can finish before the scheduled handle is assigned.
        if (settled.get()) stopPolling();
      } catch (RuntimeException failure) {
        secured(() -> fail(failure)).run();
      }
    }

    private Runnable secured(Runnable action) {
      return new DelegatingSecurityContextRunnable(action, requester);
    }

    private void poll() {
      if (settled.get()) return;
      try {
        AiReviewChatJobsWS.StatusResponse status = jobs.get(taskId);
        if ("completed".equals(status.status()) && status.response() != null) {
          if (settled.compareAndSet(false, true)) {
            stopPolling();
            result.setResult(status.response());
          }
        } else if ("failed".equals(status.status()) && status.error() != null) {
          fail(
              new ResponseStatusException(
                  HttpStatusCode.valueOf(status.error().status()), status.error().message()));
        } else if (!"pending".equals(status.status())) {
          fail(new IllegalStateException("Invalid AI review job status"));
        }
      } catch (RuntimeException failure) {
        fail(failure);
      }
    }

    private void fail(Throwable failure) {
      if (settled.compareAndSet(false, true)) {
        stopPolling();
        cancelJob();
        result.setErrorResult(
            failure instanceof ResponseStatusException
                ? failure
                : new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "AI review failed. Please retry."));
      }
    }

    private void cancelPending() {
      if (settled.compareAndSet(false, true)) {
        stopPolling();
        cancelJob();
      }
    }

    private void cancelJob() {
      try {
        jobs.cancel(taskId);
      } catch (RuntimeException ignored) {
        // Preserve the original response; the dispatcher's overall deadline remains the backstop.
      }
    }

    private void stopPolling() {
      ScheduledFuture<?> scheduled = polling.getAndSet(null);
      if (scheduled != null) scheduled.cancel(false);
    }
  }
}
