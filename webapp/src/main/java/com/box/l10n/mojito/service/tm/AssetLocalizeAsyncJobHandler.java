package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.queue.AsyncJobHandler;
import com.box.l10n.mojito.queue.AsyncJobHandlerResult;
import com.box.l10n.mojito.queue.AsyncJobPermanentFailureException;
import com.box.l10n.mojito.queue.AsyncJobRecord;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = {"l10n.org.async-job-queue.enabled", "l10n.org.async-job-queue.asset-localize.enabled"},
    havingValue = "true")
public class AssetLocalizeAsyncJobHandler implements AsyncJobHandler {

  private static final Logger logger = LoggerFactory.getLogger(AssetLocalizeAsyncJobHandler.class);

  private final PollableTaskService pollableTaskService;
  private final PollableTaskBlobStorage pollableTaskBlobStorage;
  private final PollableTaskExceptionUtils pollableTaskExceptionUtils;
  private final LocalizedAssetGenerationService localizedAssetGenerationService;
  private final ObjectMapper objectMapper;
  private final ObjectReader inputReader;
  private final MeterRegistry meterRegistry;
  private final AssetLocalizeAsyncJobOutputStorage outputStorage;

  @Autowired
  public AssetLocalizeAsyncJobHandler(
      PollableTaskService pollableTaskService,
      PollableTaskBlobStorage pollableTaskBlobStorage,
      PollableTaskExceptionUtils pollableTaskExceptionUtils,
      LocalizedAssetGenerationService localizedAssetGenerationService,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      AssetLocalizeAsyncJobOutputStorage outputStorage) {
    this.pollableTaskService = Objects.requireNonNull(pollableTaskService);
    this.pollableTaskBlobStorage = Objects.requireNonNull(pollableTaskBlobStorage);
    this.pollableTaskExceptionUtils = Objects.requireNonNull(pollableTaskExceptionUtils);
    this.localizedAssetGenerationService = Objects.requireNonNull(localizedAssetGenerationService);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.inputReader =
        objectMapper
            .readerFor(LocalizedAssetBody.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
    this.outputStorage = Objects.requireNonNull(outputStorage);
  }

  @Override
  public String queueName() {
    return AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME;
  }

  @Override
  public AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord) throws Exception {
    boolean success = false;
    long startNanos = System.nanoTime();
    try {
      AssetLocalizeAsyncJobPayload payload;
      try {
        payload = payloadFrom(asyncJobRecord.jobData());
      } catch (IllegalArgumentException invalidPayload) {
        // Invalid persisted identity cannot become executable through automatic retries.
        throw new AsyncJobPermanentFailureException("Invalid asset localize async job payload");
      }
      PollableTask pollableTask = requirePollableTask(payload.pollableTaskId());
      if (pollableTask.getFinishedDate() != null) {
        // Raw queue replay does not reopen the associated business task.
        throw new AsyncJobPermanentFailureException(
            "Asset localize async queue cannot execute finished pollable task: "
                + payload.pollableTaskId());
      }
      LocalizedAssetBody input = readInput(payload.pollableTaskId());
      if (!AssetLocalizeAsyncJobEligibility.isEligible(input)) {
        throw new AsyncJobPermanentFailureException(
            "Asset localize async queue does not support pull-run tracking");
      }
      LocalizedAssetBody output = localizedAssetGenerationService.generate(input);
      AssetLocalizeAsyncJobPayload completedPayload =
          outputStorage.saveAttemptOutput(payload.pollableTaskId(), output);
      AsyncJobHandlerResult result =
          AsyncJobHandlerResult.done(objectMapper.writeValueAsStringUnchecked(completedPayload));
      success = true;
      return result;
    } finally {
      long durationNanos = System.nanoTime() - startNanos;
      recordMetric(
          () ->
              meterRegistry
                  .timer("AssetLocalizeAsyncJobHandler.process", "queueName", queueName())
                  .record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS));
      String result = success ? "succeeded" : "failed";
      recordMetric(
          () ->
              meterRegistry
                  .counter(
                      "assetLocalizeAsyncJob.process", "queueName", queueName(), "result", result)
                  .increment());
    }
  }

  @Override
  public void onJobDone(
      AsyncJobRecord asyncJobRecord, AsyncJobHandlerResult asyncJobHandlerResult) {
    try {
      AssetLocalizeAsyncJobPayload payload =
          payloadFrom(
              asyncJobHandlerResult.jobData() != null
                  ? asyncJobHandlerResult.jobData()
                  : asyncJobRecord.jobData());
      if (requirePollableTask(payload.pollableTaskId()).getFinishedDate() != null) {
        // Match terminal repair: late or repeated callbacks cannot reopen a finished task.
        recordPollableTaskFinishSkipped("done");
        return;
      }
      outputStorage.publishOutput(payload);
      pollableTaskService.finishTask(payload.pollableTaskId(), null, null, null);
    } catch (RuntimeException exception) {
      recordPollableTaskFinishFailure("done");
      throw exception;
    }
    recordPollableTaskFinished("succeeded");
  }

  @Override
  public void onJobFailedPermanently(
      AsyncJobRecord asyncJobRecord, Throwable failure, String lastError) {
    try {
      AssetLocalizeAsyncJobPayload payload = payloadFrom(asyncJobRecord.jobData());
      PollableTask pollableTask = requirePollableTask(payload.pollableTaskId());
      if (pollableTask.getFinishedDate() != null) {
        // Preserve the original terminal result, including a timeout or rejected replay.
        recordPollableTaskFinishSkipped("failed");
        return;
      }
      ExceptionHolder exceptionHolder = new ExceptionHolder(pollableTask);
      if (failure instanceof RuntimeException
          || !(failure instanceof Exception)
          || failure instanceof AsyncJobPermanentFailureException) {
        // Task JSON exposes errorStack. Keep unexpected diagnostics on the operator-facing row;
        // queue permanence describes retry policy, not an expected user-facing failure.
        exceptionHolder.setExpected(false);
        exceptionHolder.setException(
            new IllegalStateException(
                "Asset localize async job failed permanently: " + asyncJobRecord.id().value()));
      } else {
        pollableTaskExceptionUtils.processException(failure, exceptionHolder);
      }
      pollableTaskService.finishTask(pollableTask.getId(), null, exceptionHolder, null);
    } catch (RuntimeException exception) {
      recordPollableTaskFinishFailure("failed");
      throw exception;
    }
    recordPollableTaskFinished("failed");
  }

  private PollableTask requirePollableTask(Long pollableTaskId) {
    PollableTask pollableTask = pollableTaskService.getPollableTask(pollableTaskId);
    if (pollableTask == null) {
      throw new IllegalStateException("PollableTask not found: " + pollableTaskId);
    }
    return pollableTask;
  }

  private LocalizedAssetBody readInput(Long pollableTaskId)
      throws AsyncJobPermanentFailureException {
    byte[] inputBytes = pollableTaskBlobStorage.getInputBytes(pollableTaskId);
    String message = "Invalid assetlocalize input for pollable task: " + pollableTaskId;
    String inputJson;
    try {
      // Replacement decoding would hide corrupt persisted input from JSON validation.
      inputJson =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(inputBytes))
              .toString();
    } catch (CharacterCodingException invalidEncoding) {
      throw new AsyncJobPermanentFailureException(message);
    }
    try {
      LocalizedAssetBody input = inputReader.readValue(inputJson);
      if (AssetLocalizeAsyncJobUnicode.isWellFormed(input)) {
        return input;
      }
    } catch (IOException invalidInput) {
      // Parser diagnostics can include input values even when source locations are redacted.
      if (!(invalidInput instanceof JsonParseException)
          && !(invalidInput instanceof MismatchedInputException)) {
        // Mapper/configuration and other decoding failures keep the ordinary retry policy.
        throw new IllegalStateException(message);
      }
    }
    throw new AsyncJobPermanentFailureException(message);
  }

  private AssetLocalizeAsyncJobPayload payloadFrom(String jobData) {
    return AssetLocalizeAsyncJobPayload.fromJson(jobData);
  }

  private void recordPollableTaskFinishFailure(String callback) {
    recordMetric(
        () ->
            meterRegistry
                .counter(
                    "assetLocalizeAsyncJob.pollableTask.finish.failed",
                    "queueName",
                    queueName(),
                    "callback",
                    callback)
                .increment());
  }

  private void recordPollableTaskFinishSkipped(String callback) {
    recordMetric(
        () ->
            meterRegistry
                .counter(
                    "assetLocalizeAsyncJob.pollableTask.finish.skipped",
                    "queueName",
                    queueName(),
                    "callback",
                    callback,
                    "reason",
                    "alreadyFinished")
                .increment());
  }

  private void recordPollableTaskFinished(String result) {
    recordMetric(
        () ->
            meterRegistry
                .counter(
                    "assetLocalizeAsyncJob.pollableTask.finished",
                    "queueName",
                    queueName(),
                    "result",
                    result)
                .increment());
  }

  private void recordMetric(Runnable recording) {
    try {
      recording.run();
    } catch (Throwable failure) {
      rethrowJvmFatal(failure);
      try {
        logger.warn("Failed to record assetlocalize handler metric", failure);
      } catch (Throwable loggingFailure) {
        rethrowJvmFatal(loggingFailure);
        // Diagnostics cannot discard generated output or replace a callback's outcome.
      }
    }
  }

  private static void rethrowJvmFatal(Throwable failure) {
    if (isJvmFatal(failure)) {
      throw (Error) failure;
    }
    // Diagnostic wrappers may retain fatal causes or suppressed cleanup errors, including cycles.
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
}
