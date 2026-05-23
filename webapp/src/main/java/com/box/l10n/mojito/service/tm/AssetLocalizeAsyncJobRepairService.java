package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.queue.AsyncJobId;
import com.box.l10n.mojito.queue.AsyncJobRecord;
import com.box.l10n.mojito.queue.AsyncJobStatus;
import com.box.l10n.mojito.queue.AsyncJobStore;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;
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
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  public AssetLocalizeAsyncJobRepairService(
      AsyncJobStore asyncJobStore,
      PollableTaskService pollableTaskService,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.asyncJobStore = Objects.requireNonNull(asyncJobStore);
    this.pollableTaskService = Objects.requireNonNull(pollableTaskService);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
  }

  public RepairResult repairTerminalPollableTask(String asyncJobIdValue) {
    AsyncJobId asyncJobId;
    try {
      asyncJobId = new AsyncJobId(asyncJobIdValue);
    } catch (RuntimeException exception) {
      recordRepair("unknown", "invalidJobId");
      throw exception;
    }

    List<AsyncJobRecord> asyncJobRecords;
    try {
      asyncJobRecords = asyncJobStore.getByIds(List.of(asyncJobId));
    } catch (RuntimeException exception) {
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
      payload =
          objectMapper.readValueUnchecked(
              asyncJobRecord.jobData(), AssetLocalizeAsyncJobPayload.class);
    } catch (RuntimeException exception) {
      recordRepair(asyncJobRecord.status(), "invalidPayload");
      throw new AssetLocalizeAsyncJobInvalidPayloadException(
          "Invalid asset localize async job payload: " + asyncJobRecord.id().value(), exception);
    }
    PollableTask pollableTask = pollableTaskService.getPollableTask(payload.pollableTaskId());
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
        exceptionHolder.setExpected(true);
        exceptionHolder.setException(new IllegalStateException(lastError(asyncJobRecord)));
        pollableTaskService.finishTask(pollableTask.getId(), null, exceptionHolder, null);
      } else {
        pollableTaskService.finishTask(pollableTask.getId(), null, null, null);
      }
    } catch (RuntimeException exception) {
      recordRepair(asyncJobRecord.status(), "finishFailed");
      logger.warn(
          "Failed to repair assetlocalize pollable task {} for terminal async job {}",
          pollableTask.getId(),
          asyncJobRecord.id().value(),
          exception);
      throw new AssetLocalizePollableTaskRepairException(
          "Failed to repair pollable task "
              + pollableTask.getId()
              + " for asset localize async job "
              + asyncJobRecord.id().value(),
          exception);
    }

    recordRepair(asyncJobRecord.status(), "repaired");
    logger.info(
        "Repaired assetlocalize pollable task {} for terminal async job {} with status {}",
        pollableTask.getId(),
        asyncJobRecord.id().value(),
        asyncJobRecord.status().getDatabaseValue());
    return new RepairResult(
        asyncJobRecord.id().value(),
        pollableTask.getId(),
        asyncJobRecord.status().getDatabaseValue(),
        "repaired");
  }

  private String lastError(AsyncJobRecord asyncJobRecord) {
    if (asyncJobRecord.lastError() == null || asyncJobRecord.lastError().isBlank()) {
      return "Asset localize async job failed permanently: " + asyncJobRecord.id().value();
    }
    return asyncJobRecord.lastError();
  }

  private void recordRepair(AsyncJobStatus status, String result) {
    recordRepair(status.getDatabaseValue(), result);
  }

  private void recordRepair(String status, String result) {
    meterRegistry
        .counter(
            "assetLocalizeAsyncJob.repair",
            "queueName",
            AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME,
            "status",
            status,
            "result",
            result)
        .increment();
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

  public static class AssetLocalizePollableTaskRepairException extends RuntimeException {
    public AssetLocalizePollableTaskRepairException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
