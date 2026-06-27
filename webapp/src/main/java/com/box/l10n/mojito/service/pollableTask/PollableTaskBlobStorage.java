package com.box.l10n.mojito.service.pollableTask;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.POLLABLE_TASK;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class PollableTaskBlobStorage {

  static final Logger logger = LoggerFactory.getLogger(PollableTaskBlobStorage.class);

  static final String SAVE_INPUT_PAYLOAD_BYTES_METRIC =
      "PollableTaskBlobStorage.saveInput.payloadBytes";

  static final String SAVE_INPUT_DURATION_METRIC = "PollableTaskBlobStorage.saveInput.duration";

  @Autowired StructuredBlobStorage structuredBlobStorage;

  @Autowired
  @Qualifier("fail_on_unknown_properties_false")
  ObjectMapper objectMapper;

  @Autowired MeterRegistry meterRegistry;

  public void saveInput(Long pollableTaskId, Object input) {
    long startNanos = System.nanoTime();
    String inputName = getInputName(pollableTaskId);
    String inputJson = objectMapper.writeValueAsStringUnchecked(input);
    long serializedNanos = System.nanoTime();
    int payloadBytes = inputJson.getBytes(StandardCharsets.UTF_8).length;
    String storageType = structuredBlobStorage.getStorageType(POLLABLE_TASK);
    String target = structuredBlobStorage.getTargetDescription(POLLABLE_TASK, inputName);
    String inputType = input == null ? "null" : input.getClass().getName();

    meterRegistry
        .summary(SAVE_INPUT_PAYLOAD_BYTES_METRIC, "storageType", storageType)
        .record(payloadBytes);

    logger.info(
        "Saving pollable task input: pollableTaskId={}, inputType={}, payloadBytes={}, payloadChars={}, storageType={}, target={}, serializationDurationMs={}",
        pollableTaskId,
        inputType,
        payloadBytes,
        inputJson.length(),
        storageType,
        target,
        nanosToMillis(serializedNanos - startNanos));

    String result = "success";
    try {
      structuredBlobStorage.put(POLLABLE_TASK, inputName, inputJson, Retention.MIN_1_DAY);
      logger.info(
          "Saved pollable task input: pollableTaskId={}, payloadBytes={}, storageType={}, target={}, writeDurationMs={}, totalDurationMs={}",
          pollableTaskId,
          payloadBytes,
          storageType,
          target,
          nanosToMillis(System.nanoTime() - serializedNanos),
          nanosToMillis(System.nanoTime() - startNanos));
    } catch (RuntimeException | Error e) {
      result = "failure";
      logger.error(
          "Failed to save pollable task input: pollableTaskId={}, inputType={}, payloadBytes={}, payloadChars={}, storageType={}, target={}, writeDurationMs={}, totalDurationMs={}",
          pollableTaskId,
          inputType,
          payloadBytes,
          inputJson.length(),
          storageType,
          target,
          nanosToMillis(System.nanoTime() - serializedNanos),
          nanosToMillis(System.nanoTime() - startNanos),
          e);
      throw e;
    } finally {
      meterRegistry
          .timer(SAVE_INPUT_DURATION_METRIC, "storageType", storageType, "result", result)
          .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
  }

  public void saveOutput(Long pollableTaskId, Object output) {
    String outputName = getOutputName(pollableTaskId);
    String outputJson = objectMapper.writeValueAsStringUnchecked(output);
    structuredBlobStorage.put(POLLABLE_TASK, outputName, outputJson, Retention.MIN_1_DAY);
  }

  public <T> T getInput(Long pollableTaskId, Class<T> clazz) {
    String inputName = getInputName(pollableTaskId);
    String inputJson =
        structuredBlobStorage
            .getString(POLLABLE_TASK, inputName)
            .orElseThrow(
                () -> new RuntimeException("Can't get the input json for: " + pollableTaskId));
    T t = objectMapper.readValueUnchecked(inputJson, clazz);
    return t;
  }

  public String getOutputJson(Long pollableTaskId) {
    return findOutputJson(pollableTaskId)
        .orElseThrow(
            () -> new RuntimeException("Can't get the output json for: " + pollableTaskId));
  }

  public String getInputJson(Long pollableTaskId) {
    return findInputJson(pollableTaskId)
        .orElseThrow(() -> new RuntimeException("Can't get the input json for: " + pollableTaskId));
  }

  public <T> T getOutput(Long pollableTaskId, Class<T> clazz) {
    String outputJson = getOutputJson(pollableTaskId);
    T t = objectMapper.readValueUnchecked(outputJson, clazz);
    return t;
  }

  String getInputName(long pollableTaskId) {
    return pollableTaskId + "/input";
  }

  String getOutputName(long pollableTaskId) {
    return pollableTaskId + "/output";
  }

  public Optional<String> findInputJson(Long pollableTaskId) {
    String inputName = getInputName(pollableTaskId);
    return structuredBlobStorage.getString(POLLABLE_TASK, inputName);
  }

  public Optional<String> findOutputJson(Long pollableTaskId) {
    String outputName = getOutputName(pollableTaskId);
    return structuredBlobStorage.getString(POLLABLE_TASK, outputName);
  }

  private long nanosToMillis(long nanos) {
    return TimeUnit.NANOSECONDS.toMillis(nanos);
  }
}
