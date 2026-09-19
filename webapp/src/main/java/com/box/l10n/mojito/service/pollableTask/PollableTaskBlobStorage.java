package com.box.l10n.mojito.service.pollableTask;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.POLLABLE_TASK;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

  @Autowired(required = false)
  List<PollableTaskInputSource> inputSources = List.of();

  public void saveInput(Long pollableTaskId, Object input) {
    long startNanos = System.nanoTime();
    String inputName = getInputName(pollableTaskId);
    String inputJson = objectMapper.writeValueAsStringUnchecked(input);
    long serializedNanos = System.nanoTime();
    int payloadBytes = inputJson.getBytes(StandardCharsets.UTF_8).length;
    String storageType = structuredBlobStorage.getStorageType(POLLABLE_TASK);
    String target = structuredBlobStorage.getTargetDescription(POLLABLE_TASK, inputName);
    String inputType = input == null ? "null" : input.getClass().getName();

    recordInputDiagnostic(
        () ->
            meterRegistry
                .summary(SAVE_INPUT_PAYLOAD_BYTES_METRIC, "storageType", storageType)
                .record(payloadBytes));

    recordInputDiagnostic(
        () ->
            logger.info(
                "Saving pollable task input: pollableTaskId={}, inputType={}, payloadBytes={}, payloadChars={}, storageType={}, target={}, serializationDurationMs={}",
                pollableTaskId,
                inputType,
                payloadBytes,
                inputJson.length(),
                storageType,
                target,
                nanosToMillis(serializedNanos - startNanos)));

    try {
      structuredBlobStorage.put(POLLABLE_TASK, inputName, inputJson, Retention.MIN_1_DAY);
    } catch (RuntimeException | Error e) {
      // Skip diagnostic work after a fatal storage failure.
      rethrowJvmFatal(e);
      recordInputDiagnostic(
          () ->
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
                  e));
      recordInputDuration(storageType, "failure", startNanos);
      throw e;
    }
    recordInputDiagnostic(
        () ->
            logger.info(
                "Saved pollable task input: pollableTaskId={}, payloadBytes={}, storageType={}, target={}, writeDurationMs={}, totalDurationMs={}",
                pollableTaskId,
                payloadBytes,
                storageType,
                target,
                nanosToMillis(System.nanoTime() - serializedNanos),
                nanosToMillis(System.nanoTime() - startNanos)));
    recordInputDuration(storageType, "success", startNanos);
  }

  private void recordInputDuration(String storageType, String result, long startNanos) {
    recordInputDiagnostic(
        () ->
            meterRegistry
                .timer(SAVE_INPUT_DURATION_METRIC, "storageType", storageType, "result", result)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS));
  }

  private void recordInputDiagnostic(Runnable recording) {
    try {
      recording.run();
    } catch (Throwable failure) {
      rethrowJvmFatal(failure);
      try {
        logger.warn("Failed to record pollable task input diagnostic", failure);
      } catch (Throwable loggingFailure) {
        rethrowJvmFatal(loggingFailure);
        // Nonfatal diagnostics must not change an input write's outcome.
      }
    }
  }

  private static void rethrowJvmFatal(Throwable failure) {
    if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) {
      throw (Error) failure;
    }
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    ArrayDeque<Throwable> pending = new ArrayDeque<>();
    pending.add(failure);
    while (!pending.isEmpty()) {
      Throwable current = pending.removeFirst();
      if (!visited.add(current)) {
        continue;
      }
      if (current instanceof VirtualMachineError || current instanceof ThreadDeath) {
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

  public void saveOutput(Long pollableTaskId, Object output) {
    String outputName = getOutputName(pollableTaskId);
    String outputJson = objectMapper.writeValueAsStringUnchecked(output);
    structuredBlobStorage.put(POLLABLE_TASK, outputName, outputJson, Retention.MIN_1_DAY);
  }

  public <T> T getInput(Long pollableTaskId, Class<T> clazz) {
    String inputJson = getInputJson(pollableTaskId);
    T t = objectMapper.readValueUnchecked(inputJson, clazz);
    return t;
  }

  public String getOutputJson(Long pollableTaskId) {
    return findOutputJson(pollableTaskId)
        .orElseThrow(
            () -> new RuntimeException("Can't get the output json for: " + pollableTaskId));
  }

  /** Raw stored output for consumers that must validate encoding before JSON binding. */
  public byte[] getOutputBytes(Long pollableTaskId) {
    return structuredBlobStorage
        .getBytes(POLLABLE_TASK, getOutputName(pollableTaskId))
        .orElseThrow(
            () -> new RuntimeException("Can't get the output json for: " + pollableTaskId));
  }

  public String getInputJson(Long pollableTaskId) {
    return findInputJson(pollableTaskId)
        .orElseThrow(() -> new RuntimeException("Can't get the input json for: " + pollableTaskId));
  }

  /** Raw stored input for consumers that must validate encoding before JSON binding. */
  public byte[] getInputBytes(Long pollableTaskId) {
    Optional<String> referenced = findReferencedInput(pollableTaskId);
    if (referenced.isPresent()) {
      return referenced.get().getBytes(StandardCharsets.UTF_8);
    }
    return structuredBlobStorage
        .getBytes(POLLABLE_TASK, getInputName(pollableTaskId))
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
    Optional<String> referenced = findReferencedInput(pollableTaskId);
    if (referenced.isPresent()) {
      return referenced;
    }
    String inputName = getInputName(pollableTaskId);
    return structuredBlobStorage.getString(POLLABLE_TASK, inputName);
  }

  private Optional<String> findReferencedInput(long taskId) {
    for (PollableTaskInputSource source : inputSources) {
      Optional<String> input = source.findInputJson(taskId);
      if (input.isPresent()) {
        return input;
      }
    }
    return Optional.empty();
  }

  public Optional<String> findOutputJson(Long pollableTaskId) {
    String outputName = getOutputName(pollableTaskId);
    return structuredBlobStorage.getString(POLLABLE_TASK, outputName);
  }

  private long nanosToMillis(long nanos) {
    return TimeUnit.NANOSECONDS.toMillis(nanos);
  }
}
