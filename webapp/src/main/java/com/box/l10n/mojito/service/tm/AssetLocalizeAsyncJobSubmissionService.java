package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableFutureTask;
import com.box.l10n.mojito.queue.AsyncJobQueueSubmissionService;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
    name = {"l10n.org.async-job-queue.enabled", "l10n.org.async-job-queue.asset-localize.enabled"},
    havingValue = "true")
public class AssetLocalizeAsyncJobSubmissionService {

  static Logger logger = LoggerFactory.getLogger(AssetLocalizeAsyncJobSubmissionService.class);

  public static final String QUEUE_NAME = "assetlocalize";

  private final PollableTaskService pollableTaskService;
  private final PollableTaskBlobStorage pollableTaskBlobStorage;
  private final PollableTaskExceptionUtils pollableTaskExceptionUtils;
  private final AsyncJobQueueSubmissionService asyncJobQueueSubmissionService;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  @Value("${l10n.org.async-job-queue.asset-localize.producer-enabled:true}")
  boolean producerEnabled = true;

  @Autowired
  public AssetLocalizeAsyncJobSubmissionService(
      PollableTaskService pollableTaskService,
      PollableTaskBlobStorage pollableTaskBlobStorage,
      PollableTaskExceptionUtils pollableTaskExceptionUtils,
      AsyncJobQueueSubmissionService asyncJobQueueSubmissionService,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.pollableTaskService = Objects.requireNonNull(pollableTaskService);
    this.pollableTaskBlobStorage = Objects.requireNonNull(pollableTaskBlobStorage);
    this.pollableTaskExceptionUtils = Objects.requireNonNull(pollableTaskExceptionUtils);
    this.asyncJobQueueSubmissionService = Objects.requireNonNull(asyncJobQueueSubmissionService);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
  }

  public PollableFuture<LocalizedAssetBody> scheduleJob(
      QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> quartzJobInfo) {
    Objects.requireNonNull(quartzJobInfo);

    if (!producerEnabled) {
      recordSchedule("failed");
      throw new IllegalStateException("Asset localize async queue producer is disabled");
    }

    PollableTask pollableTask;
    try {
      AssetLocalizeAsyncJobEligibility.requireEligible(quartzJobInfo.getInput());
      pollableTask = createPollableTask(quartzJobInfo);
    } catch (Throwable e) {
      rethrowJvmFatal(e);
      recordSchedule("failed");
      throw unchecked(e);
    }

    String payload;
    try {
      pollableTaskBlobStorage.saveInput(pollableTask.getId(), quartzJobInfo.getInput());
      payload = payloadFor(pollableTask);
    } catch (Throwable e) {
      rethrowJvmFatal(e);
      recordSchedule("failed");
      finishPollableTaskWithError(pollableTask, e);
      throw unchecked(e);
    }

    try {
      asyncJobQueueSubmissionService.enqueueNow(QUEUE_NAME, payload);
    } catch (Throwable e) {
      rethrowJvmFatal(e);
      // Commit acknowledgement can fail after the runnable row is durable. Do not poison
      // its task, delete its input, retry enqueue, or fall back to Quartz on an unknown outcome.
      recordSchedule("outcomeUnknown");
      warnSafely(
          "Assetlocalize enqueue outcome is unknown for pollable task {}; preserving task state",
          pollableTask.getId(),
          e);
      throw unchecked(e);
    }
    // Enqueue returned successfully. Observability must not enter failure compensation.
    recordSchedule("succeeded");
    return new QuartzPollableFutureTask<>(pollableTask, LocalizedAssetBody.class);
  }

  private PollableTask createPollableTask(
      QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> quartzJobInfo) {
    return pollableTaskService.createPollableTask(
        quartzJobInfo.getParentId(),
        GenerateLocalizedAssetJob.class.getCanonicalName(),
        quartzJobInfo.getMessage(),
        quartzJobInfo.getExpectedSubTaskNumber(),
        quartzJobInfo.getTimeout());
  }

  private String payloadFor(PollableTask pollableTask) {
    return objectMapper.writeValueAsStringUnchecked(
        new AssetLocalizeAsyncJobPayload(pollableTask.getId()));
  }

  private void finishPollableTaskWithError(PollableTask pollableTask, Throwable throwable) {
    try {
      warnSafely(
          "Assetlocalize preparation failed for pollable task {}", pollableTask.getId(), throwable);
      ExceptionHolder exceptionHolder = new ExceptionHolder(pollableTask);
      // Task JSON exposes errorStack; do not persist raw diagnostics or their cause chains.
      pollableTaskExceptionUtils.processException(
          new IllegalStateException(
              "Asset localization preparation failed for pollable task: " + pollableTask.getId()),
          exceptionHolder);
      pollableTaskService.finishTask(pollableTask.getId(), null, exceptionHolder, null);
    } catch (Throwable finishFailure) {
      rethrowJvmFatal(finishFailure);
      warnSafely(
          "Failed to finish assetlocalize pollable task {} after submission failure",
          pollableTask.getId(),
          finishFailure);
      recordMetric(
          () ->
              meterRegistry
                  .counter(
                      "assetLocalizeAsyncJob.submission.finish.failed", "queueName", QUEUE_NAME)
                  .increment());
    }
  }

  private void recordSchedule(String result) {
    recordMetric(
        () ->
            meterRegistry
                .counter(
                    "assetLocalizeAsyncJob.schedule", "queueName", QUEUE_NAME, "result", result)
                .increment());
  }

  private void recordMetric(Runnable recording) {
    try {
      recording.run();
    } catch (Throwable failure) {
      rethrowJvmFatal(failure);
      warnSafely("Failed to record assetlocalize submission metric", failure);
    }
  }

  private void warnSafely(String message, Object... arguments) {
    try {
      logger.warn(message, arguments);
    } catch (Throwable failure) {
      rethrowJvmFatal(failure);
      // Nonfatal diagnostics must not replace the original submission exception.
    }
  }

  private static void rethrowJvmFatal(Throwable failure) {
    if (isJvmFatal(failure)) {
      throw (Error) failure;
    }
    // Storage/transaction wrappers can retain fatal causes or suppressed cleanup errors.
    // Identity tracking handles cycles without changing the original graph or recursing.
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    ArrayDeque<Throwable> pending = new ArrayDeque<>();
    pending.add(failure);
    while (!pending.isEmpty()) {
      Throwable current = pending.removeFirst();
      if (!visited.add(current)) {
        continue;
      }
      if (isJvmFatal(current)) {
        throw (Error) current;
      }
      Throwable cause = current.getCause();
      if (cause != null) {
        pending.addLast(cause);
      }
      for (Throwable suppressed : current.getSuppressed()) {
        pending.addLast(suppressed);
      }
    }
  }

  private static boolean isJvmFatal(Throwable throwable) {
    return throwable instanceof VirtualMachineError || throwable instanceof ThreadDeath;
  }

  private RuntimeException unchecked(Throwable throwable) {
    if (throwable instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    if (throwable instanceof Error error) {
      throw error;
    }
    return new IllegalStateException(throwable);
  }
}
