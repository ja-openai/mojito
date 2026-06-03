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
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    PollableTask pollableTask;
    try {
      pollableTask = createPollableTask(quartzJobInfo);
    } catch (Throwable e) {
      if (isJvmFatal(e)) {
        throw (Error) e;
      }
      recordSchedule("failed");
      throw unchecked(e);
    }

    try {
      pollableTaskBlobStorage.saveInput(pollableTask.getId(), quartzJobInfo.getInput());
      asyncJobQueueSubmissionService.enqueueNow(QUEUE_NAME, payloadFor(pollableTask));
      recordSchedule("succeeded");
      return new QuartzPollableFutureTask<>(pollableTask, LocalizedAssetBody.class);
    } catch (Throwable e) {
      if (isJvmFatal(e)) {
        throw (Error) e;
      }
      recordSchedule("failed");
      finishPollableTaskWithError(pollableTask, e);
      throw unchecked(e);
    }
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
      ExceptionHolder exceptionHolder = new ExceptionHolder(pollableTask);
      pollableTaskExceptionUtils.processException(throwable, exceptionHolder);
      pollableTaskService.finishTask(pollableTask.getId(), null, exceptionHolder, null);
    } catch (Throwable finishFailure) {
      if (isJvmFatal(finishFailure)) {
        throw (Error) finishFailure;
      }
      logger.warn(
          "Failed to finish assetlocalize pollable task {} after submission failure",
          pollableTask.getId(),
          finishFailure);
      meterRegistry
          .counter("assetLocalizeAsyncJob.submission.finish.failed", "queueName", QUEUE_NAME)
          .increment();
    }
  }

  private void recordSchedule(String result) {
    meterRegistry
        .counter("assetLocalizeAsyncJob.schedule", "queueName", QUEUE_NAME, "result", result)
        .increment();
  }

  private boolean isJvmFatal(Throwable throwable) {
    return throwable instanceof VirtualMachineError
        || "java.lang.ThreadDeath".equals(throwable.getClass().getName());
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
