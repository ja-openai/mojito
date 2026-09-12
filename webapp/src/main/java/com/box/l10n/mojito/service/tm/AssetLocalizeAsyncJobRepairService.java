package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.queue.AsyncJobId;
import com.box.l10n.mojito.queue.AsyncJobRecord;
import com.box.l10n.mojito.queue.AsyncJobStatus;
import com.box.l10n.mojito.queue.AsyncJobStore;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
    name = {"l10n.org.async-job-queue.enabled", "l10n.org.async-job-queue.asset-localize.enabled"},
    havingValue = "true")
public class AssetLocalizeAsyncJobRepairService {

  static Logger logger = LoggerFactory.getLogger(AssetLocalizeAsyncJobRepairService.class);

  private final AsyncJobStore asyncJobStore;
  private final PollableTaskService pollableTaskService;
  private final MeterRegistry meterRegistry;
  private final AssetLocalizeAsyncJobOutputStorage outputStorage;

  public AssetLocalizeAsyncJobRepairService(
      AsyncJobStore asyncJobStore,
      PollableTaskService pollableTaskService,
      MeterRegistry meterRegistry,
      AssetLocalizeAsyncJobOutputStorage outputStorage) {
    this.asyncJobStore = Objects.requireNonNull(asyncJobStore);
    this.pollableTaskService = Objects.requireNonNull(pollableTaskService);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
    this.outputStorage = Objects.requireNonNull(outputStorage);
  }

  public RepairResult repairTerminalPollableTask(String asyncJobIdValue) {
    AsyncJobId asyncJobId;
    try {
      asyncJobId = new AsyncJobId(asyncJobIdValue);
    } catch (RuntimeException exception) {
      rethrowJvmFatal(exception);
      recordRepair("unknown", "invalidJobId");
      throw exception;
    }

    List<AsyncJobRecord> asyncJobRecords;
    try {
      asyncJobRecords = asyncJobStore.getByIds(List.of(asyncJobId));
    } catch (RuntimeException exception) {
      rethrowJvmFatal(exception);
      recordRepair("unknown", "jobLookupFailed");
      throw new AssetLocalizeAsyncJobLookupException(
          "Failed to look up asset localize async job: " + asyncJobId.value(), exception);
    }
    AsyncJobRecord asyncJobRecord =
        asyncJobRecords.stream()
            .filter(
                record ->
                    AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME.equals(record.queueName()))
            .findFirst()
            .orElseThrow(
                () -> {
                  recordRepair("unknown", "jobNotFound");
                  return new AssetLocalizeAsyncJobNotFoundException(
                      "Asset localize async job not found: " + asyncJobId.value());
                });
    return repairTerminalPollableTask(asyncJobRecord);
  }

  public RepairResult repairTerminalPollableTask(AsyncJobRecord asyncJobRecord) {
    Objects.requireNonNull(asyncJobRecord);
    if (!AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME.equals(asyncJobRecord.queueName())) {
      recordRepair("unknown", "wrongQueue");
      throw new IllegalArgumentException(
          "Expected queue "
              + AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME
              + " but got "
              + asyncJobRecord.queueName());
    }
    if (asyncJobRecord.status() != AsyncJobStatus.DONE
        && asyncJobRecord.status() != AsyncJobStatus.FAILED) {
      recordRepair(asyncJobRecord.status(), "nonTerminal");
      throw new IllegalStateException(
          "Asset localize async job must be terminal to repair pollable task: "
              + asyncJobRecord.id().value());
    }

    AssetLocalizeAsyncJobPayload payload;
    try {
      payload = AssetLocalizeAsyncJobPayload.fromJson(asyncJobRecord.jobData());
    } catch (RuntimeException exception) {
      rethrowJvmFatal(exception);
      recordRepair(asyncJobRecord.status(), "invalidPayload");
      throw new AssetLocalizeAsyncJobInvalidPayloadException(
          "Invalid asset localize async job payload: " + asyncJobRecord.id().value(), exception);
    }
    PollableTask pollableTask;
    try {
      pollableTask = pollableTaskService.getFreshPollableTask(payload.pollableTaskId());
    } catch (RuntimeException exception) {
      rethrowJvmFatal(exception);
      recordRepair(asyncJobRecord.status(), "pollableTaskLookupFailed");
      throw new AssetLocalizePollableTaskLookupException(
          "Failed to look up pollable task: " + payload.pollableTaskId(), exception);
    }
    if (pollableTask == null) {
      recordRepair(asyncJobRecord.status(), "pollableTaskNotFound");
      throw new AssetLocalizePollableTaskNotFoundException(
          "PollableTask not found: " + payload.pollableTaskId());
    }
    if (pollableTask.getFinishedDate() != null) {
      recordRepair(asyncJobRecord.status(), "alreadyFinished");
      return new RepairResult(
          asyncJobRecord.id().value(),
          pollableTask.getId(),
          asyncJobRecord.status().getDatabaseValue(),
          "alreadyFinished");
    }

    try {
      if (asyncJobRecord.status() == AsyncJobStatus.FAILED) {
        ExceptionHolder exceptionHolder = new ExceptionHolder(pollableTask);
        // Stored diagnostics have no trusted user-error classification. Task JSON also exposes
        // errorStack, so keep lastError on the operator-facing queue row, not in this exception.
        exceptionHolder.setExpected(false);
        exceptionHolder.setException(
            new IllegalStateException(
                "Asset localize async job failed permanently: " + asyncJobRecord.id().value()));
        pollableTaskService.finishTask(pollableTask.getId(), null, exceptionHolder, null);
      } else {
        outputStorage.publishOutput(payload);
        pollableTaskService.finishTask(pollableTask.getId(), null, null, null);
      }
    } catch (RuntimeException exception) {
      rethrowJvmFatal(exception);
      recordRepair(asyncJobRecord.status(), "finishFailed");
      recordDiagnostic(
          () ->
              logger.warn(
                  "Failed to repair assetlocalize pollable task {} for terminal async job {}",
                  pollableTask.getId(),
                  asyncJobRecord.id().value(),
                  exception));
      throw new AssetLocalizePollableTaskRepairException(
          "Failed to repair pollable task "
              + pollableTask.getId()
              + " for asset localize async job "
              + asyncJobRecord.id().value(),
          exception);
    }

    recordRepair(asyncJobRecord.status(), "repaired");
    recordDiagnostic(
        () ->
            logger.info(
                "Repaired assetlocalize pollable task {} for terminal async job {} with status {}",
                pollableTask.getId(),
                asyncJobRecord.id().value(),
                asyncJobRecord.status().getDatabaseValue()));
    return new RepairResult(
        asyncJobRecord.id().value(),
        pollableTask.getId(),
        asyncJobRecord.status().getDatabaseValue(),
        "repaired");
  }

  private void recordRepair(AsyncJobStatus status, String result) {
    recordRepair(status.getDatabaseValue(), result);
  }

  private void recordRepair(String status, String result) {
    recordDiagnostic(
        () ->
            meterRegistry
                .counter(
                    "assetLocalizeAsyncJob.repair",
                    "queueName",
                    AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME,
                    "status",
                    status,
                    "result",
                    result)
                .increment());
  }

  private void recordDiagnostic(Runnable recording) {
    try {
      recording.run();
    } catch (Throwable failure) {
      rethrowJvmFatal(failure);
      try {
        logger.warn("Failed to record assetlocalize repair diagnostic", failure);
      } catch (Throwable loggingFailure) {
        rethrowJvmFatal(loggingFailure);
        // A failed diagnostic must not replace a repair outcome; do not retry logging.
      }
    }
  }

  private static void rethrowJvmFatal(Throwable failure) {
    if (isJvmFatal(failure)) {
      throw (Error) failure;
    }
    // Transaction and diagnostic wrappers can retain fatal causes or suppressed errors.
    // Track identity to handle cycles without recursion or changing the original graph.
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

  @SuppressWarnings("removal")
  private static boolean isJvmFatal(Throwable throwable) {
    return throwable instanceof VirtualMachineError || throwable instanceof ThreadDeath;
  }

  public record RepairResult(
      String asyncJobId, Long pollableTaskId, String status, String result) {}

  public static class AssetLocalizeAsyncJobNotFoundException extends RuntimeException {
    public AssetLocalizeAsyncJobNotFoundException(String message) {
      super(message);
    }
  }

  public static class AssetLocalizeAsyncJobLookupException extends RuntimeException {
    public AssetLocalizeAsyncJobLookupException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class AssetLocalizeAsyncJobInvalidPayloadException extends RuntimeException {
    public AssetLocalizeAsyncJobInvalidPayloadException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class AssetLocalizePollableTaskNotFoundException extends RuntimeException {
    public AssetLocalizePollableTaskNotFoundException(String message) {
      super(message);
    }
  }

  public static class AssetLocalizePollableTaskLookupException extends RuntimeException {
    public AssetLocalizePollableTaskLookupException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class AssetLocalizePollableTaskRepairException extends RuntimeException {
    public AssetLocalizePollableTaskRepairException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
