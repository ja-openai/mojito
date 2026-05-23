package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.queue.AsyncJobHandler;
import com.box.l10n.mojito.queue.AsyncJobHandlerResult;
import com.box.l10n.mojito.queue.AsyncJobRecord;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = {"l10n.org.async-job-queue.enabled", "l10n.org.async-job-queue.asset-localize.enabled"},
    havingValue = "true")
public class AssetLocalizeAsyncJobHandler implements AsyncJobHandler {

  private final PollableTaskService pollableTaskService;
  private final PollableTaskBlobStorage pollableTaskBlobStorage;
  private final PollableTaskExceptionUtils pollableTaskExceptionUtils;
  private final LocalizedAssetGenerationService localizedAssetGenerationService;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  @Autowired
  public AssetLocalizeAsyncJobHandler(
      PollableTaskService pollableTaskService,
      PollableTaskBlobStorage pollableTaskBlobStorage,
      PollableTaskExceptionUtils pollableTaskExceptionUtils,
      LocalizedAssetGenerationService localizedAssetGenerationService,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.pollableTaskService = Objects.requireNonNull(pollableTaskService);
    this.pollableTaskBlobStorage = Objects.requireNonNull(pollableTaskBlobStorage);
    this.pollableTaskExceptionUtils = Objects.requireNonNull(pollableTaskExceptionUtils);
    this.localizedAssetGenerationService = Objects.requireNonNull(localizedAssetGenerationService);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
  }

  @Override
  public String queueName() {
    return AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME;
  }

  @Override
  public AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord) throws Exception {
    AssetLocalizeAsyncJobPayload payload = payloadFrom(asyncJobRecord.jobData());
    requirePollableTask(payload.pollableTaskId());

    boolean success = false;
    try (var timer =
        Timer.resource(meterRegistry, "AssetLocalizeAsyncJobHandler.process")
            .tag("queueName", queueName())) {
      LocalizedAssetBody input =
          pollableTaskBlobStorage.getInput(payload.pollableTaskId(), LocalizedAssetBody.class);
      LocalizedAssetBody output = localizedAssetGenerationService.generate(input);
      pollableTaskBlobStorage.saveOutput(payload.pollableTaskId(), output);
      success = true;
      return AsyncJobHandlerResult.done(asyncJobRecord.jobData());
    } finally {
      meterRegistry
          .counter(
              "assetLocalizeAsyncJob.process",
              "queueName",
              queueName(),
              "result",
              success ? "succeeded" : "failed")
          .increment();
    }
  }

  @Override
  public void onJobDone(
      AsyncJobRecord asyncJobRecord, AsyncJobHandlerResult asyncJobHandlerResult) {
    AssetLocalizeAsyncJobPayload payload =
        payloadFrom(
            asyncJobHandlerResult.jobData() != null
                ? asyncJobHandlerResult.jobData()
                : asyncJobRecord.jobData());
    pollableTaskService.finishTask(payload.pollableTaskId(), null, null, null);
    meterRegistry
        .counter(
            "assetLocalizeAsyncJob.pollableTask.finished",
            "queueName",
            queueName(),
            "result",
            "succeeded")
        .increment();
  }

  @Override
  public void onJobFailedPermanently(
      AsyncJobRecord asyncJobRecord, Throwable failure, String lastError) {
    AssetLocalizeAsyncJobPayload payload = payloadFrom(asyncJobRecord.jobData());
    PollableTask pollableTask = requirePollableTask(payload.pollableTaskId());
    ExceptionHolder exceptionHolder = new ExceptionHolder(pollableTask);
    pollableTaskExceptionUtils.processException(failure, exceptionHolder);
    pollableTaskService.finishTask(pollableTask.getId(), null, exceptionHolder, null);
    meterRegistry
        .counter(
            "assetLocalizeAsyncJob.pollableTask.finished",
            "queueName",
            queueName(),
            "result",
            "failed")
        .increment();
  }

  private PollableTask requirePollableTask(Long pollableTaskId) {
    PollableTask pollableTask = pollableTaskService.getPollableTask(pollableTaskId);
    if (pollableTask == null) {
      throw new IllegalStateException("PollableTask not found: " + pollableTaskId);
    }
    return pollableTask;
  }

  private AssetLocalizeAsyncJobPayload payloadFrom(String jobData) {
    return objectMapper.readValueUnchecked(jobData, AssetLocalizeAsyncJobPayload.class);
  }
}
