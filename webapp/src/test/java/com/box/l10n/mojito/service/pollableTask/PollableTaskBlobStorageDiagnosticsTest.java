package com.box.l10n.mojito.service.pollableTask;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.POLLABLE_TASK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PollableTaskBlobStorageDiagnosticsTest {

  private static final String PAYLOAD_METRIC = "PollableTaskBlobStorage.saveInput.payloadBytes";
  private static final String DURATION_METRIC = "PollableTaskBlobStorage.saveInput.duration";
  private static final String STORAGE_TYPE = "AzureBlobStorage";
  private static final Map<String, String> INPUT = Map.of("text", "caf\u00e9 \ud83c\udf0d");
  private static final String INPUT_JSON = "{\"text\":\"caf\u00e9 \ud83c\udf0d\"}";

  private final PollableTaskBlobStorage service = new PollableTaskBlobStorage();
  private final StructuredBlobStorage blobs = mock(StructuredBlobStorage.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  @Before
  public void setUp() {
    service.structuredBlobStorage = blobs;
    service.objectMapper = new ObjectMapper();
    service.meterRegistry = meters;
    when(blobs.getStorageType(POLLABLE_TASK)).thenReturn(STORAGE_TYPE);
    when(blobs.getTargetDescription(POLLABLE_TASK, "42/input"))
        .thenReturn("test-container/pollable_task/42/input");
  }

  @After
  public void tearDown() {
    meters.close();
  }

  @Test
  public void payloadSummaryCollisionDoesNotPreventExactlyOneLegacyWrite() {
    meters.counter(PAYLOAD_METRIC, "storageType", STORAGE_TYPE);

    service.saveInput(42L, INPUT);

    verifyExactlyOneLegacyPut();
    assertDuration("success");
  }

  @Test
  public void successDurationCollisionDoesNotFailAcknowledgedWrite() {
    collideDuration("success");

    service.saveInput(42L, INPUT);

    verifyExactlyOneLegacyPut();
    assertPayloadSummary();
    assertThat(meters.find(DURATION_METRIC).tag("result", "failure").timer()).isNull();
  }

  @Test
  public void failureDurationCollisionDoesNotReplaceStorageException() {
    collideDuration("failure");
    RuntimeException failure = new IllegalStateException("storage unavailable");
    failPut(failure);

    assertThat(catchThrowable(() -> service.saveInput(42L, INPUT))).isSameAs(failure);

    verifyExactlyOneLegacyPut();
  }

  @Test
  public void failureDurationCollisionDoesNotMaskStorageVirtualMachineError() {
    Error fatal = new SyntheticVirtualMachineError();
    assertStorageFatalSkipsMetrics(fatal, fatal);
  }

  @Test
  public void failureDurationCollisionDoesNotMaskStorageThreadDeath() {
    Error fatal = new SyntheticThreadDeath();
    assertStorageFatalSkipsMetrics(fatal, fatal);
  }

  @Test
  public void failureDurationCollisionDoesNotMaskWrappedStorageFatal() {
    Error fatal = new SyntheticVirtualMachineError();
    assertStorageFatalSkipsMetrics(new RuntimeException("storage provider", fatal), fatal);
  }

  @Test
  public void failureDurationCollisionDoesNotMaskSuppressedStorageFatal() {
    Error fatal = new SyntheticThreadDeath();
    RuntimeException failure = new RuntimeException("storage provider");
    failure.addSuppressed(new RuntimeException("storage cleanup", fatal));
    assertStorageFatalSkipsMetrics(failure, fatal);
  }

  @Test
  public void ordinarySerializationFailureRetainsIdentityWithoutWriting() {
    Object invalidInput = new Object();
    ObjectMapper mapper = spy(new ObjectMapper());
    AtomicReference<RuntimeException> serializationFailure = new AtomicReference<>();
    // Capture the real mapper's failure at the boundary, rather than synthesize a mapper error.
    doAnswer(
            invocation -> {
              try {
                return invocation.callRealMethod();
              } catch (RuntimeException failure) {
                serializationFailure.set(failure);
                throw failure;
              }
            })
        .when(mapper)
        .writeValueAsStringUnchecked(invalidInput);
    service.objectMapper = mapper;

    assertThat(catchThrowable(() -> service.saveInput(42L, invalidInput)))
        .isInstanceOf(RuntimeException.class)
        .isSameAs(serializationFailure.get())
        .hasCauseInstanceOf(InvalidDefinitionException.class);

    verify(mapper).writeValueAsStringUnchecked(invalidInput);
    verifyNoInteractions(blobs);
    assertThat(meters.getMeters()).isEmpty();
  }

  @Test
  public void ordinaryStorageFailureRetainsIdentityAndFailureMetricsWithoutRetry() {
    RuntimeException failure = new IllegalStateException("storage unavailable");
    failPut(failure);

    assertThat(catchThrowable(() -> service.saveInput(42L, INPUT))).isSameAs(failure);

    verifyExactlyOneLegacyPut();
    assertPayloadSummary();
    assertDuration("failure");
  }

  @Test
  public void successfulWriteRetainsUtf8PayloadSizeAndTimerTags() {
    service.saveInput(42L, INPUT);

    verifyExactlyOneLegacyPut();
    assertThat(INPUT_JSON.getBytes(StandardCharsets.UTF_8).length)
        .isGreaterThan(INPUT_JSON.length());
    assertPayloadSummary();
    assertDuration("success");
  }

  @Test
  public void infoAndFallbackWarningFailuresDoNotPreventOrRejectSuccessfulWrite() {
    List<Level> failedLogs =
        withFailingInputLogs(
            Level.INFO,
            new IllegalStateException("warning appender failed"),
            () -> service.saveInput(42L, INPUT));

    assertThat(failedLogs).containsExactly(Level.INFO, Level.WARN, Level.INFO, Level.WARN);
    verifyExactlyOneLegacyPut();
    assertPayloadSummary();
    assertDuration("success");
  }

  @Test
  public void errorAndFallbackWarningFailuresDoNotMaskStorageException() {
    RuntimeException failure = new IllegalStateException("storage unavailable");
    failPut(failure);

    List<Level> failedLogs =
        withFailingInputLogs(
            Level.ERROR,
            new IllegalStateException("warning appender failed"),
            () ->
                assertThat(catchThrowable(() -> service.saveInput(42L, INPUT))).isSameAs(failure));

    assertThat(failedLogs).containsExactly(Level.ERROR, Level.WARN);
    verifyExactlyOneLegacyPut();
    assertPayloadSummary();
    assertDuration("failure");
  }

  @Test
  public void fatalFallbackWarningEscapesBeforeWrite() {
    Error fatal = new SyntheticVirtualMachineError();

    List<Level> failedLogs =
        withFailingInputLogs(
            Level.INFO,
            fatal,
            () -> assertThat(catchThrowable(() -> service.saveInput(42L, INPUT))).isSameAs(fatal));

    assertThat(failedLogs).containsExactly(Level.INFO, Level.WARN);
    verify(blobs, never()).put(any(), any(), any(), any());
    assertThat(meters.find(DURATION_METRIC).timer()).isNull();
  }

  @Test
  public void directFatalPayloadMetricEscapesBeforeWrite() {
    Error fatal = new SyntheticVirtualMachineError();
    assertPayloadMetricFatalEscapes(fatal, fatal);
  }

  @Test
  public void wrappedFatalPayloadMetricEscapesBeforeWrite() {
    Error fatal = new SyntheticVirtualMachineError();
    assertPayloadMetricFatalEscapes(new RuntimeException("metric provider", fatal), fatal);
  }

  @Test
  public void suppressedFatalPayloadMetricEscapesBeforeWrite() {
    Error fatal = new SyntheticThreadDeath();
    RuntimeException failure = new RuntimeException("metric provider");
    failure.addSuppressed(new RuntimeException("provider cleanup", fatal));
    assertPayloadMetricFatalEscapes(failure, fatal);
  }

  private List<Level> withFailingInputLogs(
      Level failingLevel, Throwable warningFailure, Runnable action) {
    Logger logger = (Logger) PollableTaskBlobStorage.logger;
    Level previousLevel = logger.getLevel();
    Thread callingThread = Thread.currentThread();
    List<Level> failedLogs = new ArrayList<>();
    @SuppressWarnings("unchecked")
    Appender<ILoggingEvent> appender = mock(Appender.class);
    doAnswer(
            invocation -> {
              if (Thread.currentThread() != callingThread) {
                return null;
              }
              ILoggingEvent event = invocation.getArgument(0);
              if (!event.getLoggerName().equals(PollableTaskBlobStorage.class.getName())) {
                return null;
              }
              if (event.getLevel() == Level.WARN
                  && event.getMessage().equals("Failed to record pollable task input diagnostic")) {
                failedLogs.add(Level.WARN);
                throw warningFailure;
              }
              if (event.getLevel() == failingLevel
                  && event.getMessage().contains("pollable task input:")) {
                failedLogs.add(failingLevel);
                throw new IllegalStateException("input diagnostic appender failed");
              }
              return null;
            })
        .when(appender)
        .doAppend(any(ILoggingEvent.class));
    try {
      logger.setLevel(Level.INFO);
      logger.addAppender(appender);
      action.run();
      return failedLogs;
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previousLevel);
    }
  }

  private void collideDuration(String result) {
    meters.counter(DURATION_METRIC, "storageType", STORAGE_TYPE, "result", result);
  }

  private void failPut(Throwable failure) {
    doThrow(failure).when(blobs).put(POLLABLE_TASK, "42/input", INPUT_JSON, Retention.MIN_1_DAY);
  }

  private void assertStorageFatalSkipsMetrics(Throwable failure, Error fatal) {
    collideDuration("failure");
    MeterRegistry observedMeters = spy(meters);
    service.meterRegistry = observedMeters;
    doAnswer(
            invocation -> {
              // Exclude pre-write diagnostics from the no-more-metrics assertion.
              clearInvocations(observedMeters);
              throw failure;
            })
        .when(blobs)
        .put(POLLABLE_TASK, "42/input", INPUT_JSON, Retention.MIN_1_DAY);

    assertThat(catchThrowable(() -> service.saveInput(42L, INPUT))).isSameAs(fatal);

    verifyExactlyOneLegacyPut();
    verifyNoInteractions(observedMeters);
  }

  private void assertPayloadMetricFatalEscapes(Throwable failure, Error fatal) {
    MeterRegistry failingMeters = mock(MeterRegistry.class);
    service.meterRegistry = failingMeters;
    when(failingMeters.summary(PAYLOAD_METRIC, "storageType", STORAGE_TYPE)).thenThrow(failure);

    assertThat(catchThrowable(() -> service.saveInput(42L, INPUT))).isSameAs(fatal);

    verify(blobs, never()).put(any(), any(), any(), any());
    verify(failingMeters).summary(PAYLOAD_METRIC, "storageType", STORAGE_TYPE);
    verifyNoMoreInteractions(failingMeters);
  }

  private void verifyExactlyOneLegacyPut() {
    verify(blobs).put(POLLABLE_TASK, "42/input", INPUT_JSON, Retention.MIN_1_DAY);
    verify(blobs, times(1)).put(any(), any(), any(), any());
  }

  private void assertPayloadSummary() {
    DistributionSummary summary =
        meters.get(PAYLOAD_METRIC).tag("storageType", STORAGE_TYPE).summary();
    assertThat(summary.count()).isEqualTo(1);
    assertThat(summary.totalAmount()).isEqualTo(INPUT_JSON.getBytes(StandardCharsets.UTF_8).length);
    assertThat(summary.getId().getTags()).containsExactly(Tag.of("storageType", STORAGE_TYPE));
    assertThat(meters.find(PAYLOAD_METRIC).summaries()).hasSize(1);
  }

  private void assertDuration(String result) {
    Timer timer =
        meters.get(DURATION_METRIC).tags("storageType", STORAGE_TYPE, "result", result).timer();
    assertThat(timer.count()).isEqualTo(1);
    assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isGreaterThanOrEqualTo(0);
    assertThat(timer.getId().getTags())
        .containsExactlyInAnyOrder(Tag.of("storageType", STORAGE_TYPE), Tag.of("result", result));
    assertThat(meters.find(DURATION_METRIC).timers()).hasSize(1);
  }

  private static final class SyntheticVirtualMachineError extends VirtualMachineError {}

  private static final class SyntheticThreadDeath extends ThreadDeath {}
}
