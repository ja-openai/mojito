package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.queue.AsyncJobQueueSubmissionService;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.Test;
import org.slf4j.Logger;

public class AssetLocalizeAsyncJobSubmissionFatalFailureTest {

  private static final String SCHEDULE_METER = "assetLocalizeAsyncJob.schedule";
  private static final String FINISH_METER = "assetLocalizeAsyncJob.submission.finish.failed";
  private static final String PREPARATION_WARNING =
      "Assetlocalize preparation failed for pollable task {}";
  private static final String UNKNOWN_OUTCOME_WARNING =
      "Assetlocalize enqueue outcome is unknown for pollable task {}; preserving task state";
  private static final String FINISH_WARNING =
      "Failed to finish assetlocalize pollable task {} after submission failure";

  @Test(timeout = 5000)
  public void taskCreationFatalGraphStopsBeforeInputMetricsOrCompensation() {
    for (FatalFailure failure : fatalFailures()) {
      try (Fixture fixture = new Fixture()) {
        fixture.failTaskCreation(failure.failure());

        failure.assertEscapes(fixture);

        fixture.verifyNoCompensation();
        fixture.verifyNoMetrics();
        verifyNoInteractions(fixture.blobs, fixture.mapper, fixture.queue, fixture.logger);
      }
    }
  }

  @Test(timeout = 5000)
  public void inputSaveFatalGraphStopsBeforeSerializationMetricsOrCompensation() {
    for (FatalFailure failure : fatalFailures()) {
      try (Fixture fixture = new Fixture()) {
        doThrow(failure.failure()).when(fixture.blobs).saveInput(42L, fixture.job.getInput());

        failure.assertEscapes(fixture);

        fixture.verifyInputSaved();
        fixture.verifyNoCompensation();
        fixture.verifyNoMetrics();
        verifyNoInteractions(fixture.mapper, fixture.queue, fixture.logger);
      }
    }
  }

  @Test(timeout = 5000)
  public void payloadSerializerFatalGraphStopsBeforeEnqueueMetricsOrCompensation() {
    for (FatalFailure failure : fatalFailures()) {
      try (Fixture fixture = new Fixture()) {
        doThrow(failure.failure()).when(fixture.mapper).writeValueAsStringUnchecked(any());

        failure.assertEscapes(fixture);

        fixture.verifyPreparationAttempted();
        fixture.verifyNoCompensation();
        fixture.verifyNoMetrics();
        verifyNoInteractions(fixture.queue, fixture.logger);
      }
    }
  }

  @Test(timeout = 5000)
  public void enqueueFatalGraphStopsBeforeOutcomeClassificationOrWarnings() {
    for (FatalFailure failure : fatalFailures()) {
      try (Fixture fixture = new Fixture()) {
        doThrow(failure.failure()).when(fixture.queue).enqueueNow("assetlocalize", "payload");

        failure.assertEscapes(fixture);

        fixture.verifyPreparationAttempted();
        fixture.verifyEnqueueAttempted();
        fixture.verifyNoCompensation();
        fixture.verifyNoMetrics();
        verifyNoInteractions(fixture.logger);
      }
    }
  }

  @Test(timeout = 5000)
  public void successMeterFatalGraphEscapesWithoutCompensatingAcceptedSubmission() {
    for (FatalFailure failure : fatalFailures()) {
      try (Fixture fixture = new Fixture()) {
        doThrow(failure.failure()).when(fixture.scheduleCounter).increment();

        failure.assertEscapes(fixture);

        fixture.verifyPreparationAttempted();
        fixture.verifyEnqueueAttempted();
        fixture.verifyNoCompensation();
        fixture.verifyOnlySchedule("succeeded");
        verifyNoInteractions(fixture.logger);
      }
    }
  }

  @Test(timeout = 5000)
  public void failureMeterFatalGraphStopsBeforePreparationCompensationOrWarnings() {
    for (FatalFailure failure : fatalFailures()) {
      try (Fixture fixture = new Fixture()) {
        RuntimeException primary = fixture.failInput();
        doThrow(failure.failure()).when(fixture.scheduleCounter).increment();

        failure.assertEscapes(fixture);

        fixture.verifyInputSaved();
        fixture.verifyNoCompensation();
        fixture.verifyOnlySchedule("failed");
        verifyNoInteractions(fixture.mapper, fixture.queue, fixture.logger);
        assertOrdinaryFailureUnchanged(primary);
      }
    }
  }

  @Test(timeout = 5000)
  public void outcomeUnknownMeterFatalGraphStopsBeforeWarningOrCompensation() {
    for (FatalFailure failure : fatalFailures()) {
      try (Fixture fixture = new Fixture()) {
        RuntimeException primary = new IllegalStateException("commit acknowledgement lost");
        doThrow(primary).when(fixture.queue).enqueueNow("assetlocalize", "payload");
        doThrow(failure.failure()).when(fixture.scheduleCounter).increment();

        failure.assertEscapes(fixture);

        fixture.verifyPreparationAttempted();
        fixture.verifyEnqueueAttempted();
        fixture.verifyNoCompensation();
        fixture.verifyOnlySchedule("outcomeUnknown");
        verifyNoInteractions(fixture.logger);
        assertOrdinaryFailureUnchanged(primary);
      }
    }
  }

  @Test(timeout = 5000)
  public void preparationLoggerFatalGraphStopsBeforeExceptionClassificationOrFinish() {
    for (FatalFailure failure : fatalFailures()) {
      try (Fixture fixture = new Fixture()) {
        RuntimeException primary = fixture.failInput();
        doThrow(failure.failure())
            .when(fixture.logger)
            .warn(any(String.class), any(Object[].class));

        failure.assertEscapes(fixture);

        fixture.verifyInputSaved();
        fixture.verifyNoCompensation();
        fixture.verifyOnlySchedule("failed");
        fixture.verifyOnlyPreparationWarning(primary);
        verifyNoInteractions(fixture.mapper, fixture.queue);
        assertOrdinaryFailureUnchanged(primary);
      }
    }
  }

  @Test(timeout = 5000)
  public void outcomeUnknownLoggerFatalGraphEscapesWithoutRetryOrCompensation() {
    for (FatalFailure failure : fatalFailures()) {
      try (Fixture fixture = new Fixture()) {
        RuntimeException primary = new IllegalStateException("commit acknowledgement lost");
        doThrow(primary).when(fixture.queue).enqueueNow("assetlocalize", "payload");
        doThrow(failure.failure())
            .when(fixture.logger)
            .warn(any(String.class), any(Object[].class));

        failure.assertEscapes(fixture);

        fixture.verifyPreparationAttempted();
        fixture.verifyEnqueueAttempted();
        fixture.verifyNoCompensation();
        fixture.verifyOnlySchedule("outcomeUnknown");
        verify(fixture.logger).warn(UNKNOWN_OUTCOME_WARNING, new Object[] {42L, primary});
        verifyNoMoreInteractions(fixture.logger);
        assertOrdinaryFailureUnchanged(primary);
      }
    }
  }

  @Test(timeout = 5000)
  public void metricDiagnosticLoggerFatalGraphEscapesWithoutCompensatingAcceptedSubmission() {
    for (FatalFailure failure : fatalFailures()) {
      try (Fixture fixture = new Fixture()) {
        RuntimeException primary = new IllegalStateException("meter unavailable");
        doThrow(primary).when(fixture.scheduleCounter).increment();
        doThrow(failure.failure())
            .when(fixture.logger)
            .warn(any(String.class), any(Object[].class));

        failure.assertEscapes(fixture);

        fixture.verifyPreparationAttempted();
        fixture.verifyEnqueueAttempted();
        fixture.verifyNoCompensation();
        fixture.verifyOnlySchedule("succeeded");
        verify(fixture.logger)
            .warn("Failed to record assetlocalize submission metric", new Object[] {primary});
        verifyNoMoreInteractions(fixture.logger);
        assertOrdinaryFailureUnchanged(primary);
      }
    }
  }

  @Test(timeout = 5000)
  public void exceptionClassificationFatalGraphStopsBeforeFinishOrCleanupDiagnostics() {
    for (FatalFailure failure : fatalFailures()) {
      try (Fixture fixture = new Fixture()) {
        RuntimeException primary = fixture.failInput();
        doThrow(failure.failure())
            .when(fixture.exceptions)
            .processException(any(Throwable.class), any(ExceptionHolder.class));

        failure.assertEscapes(fixture);

        fixture.verifyInputSaved();
        fixture.verifyTaskCreated();
        verifyNoMoreInteractions(fixture.tasks);
        fixture.verifyClassificationAttempted();
        fixture.verifyOnlySchedule("failed");
        fixture.verifyOnlyPreparationWarning(primary);
        verifyNoInteractions(fixture.mapper, fixture.queue);
        assertOrdinaryFailureUnchanged(primary);
      }
    }
  }

  @Test(timeout = 5000)
  public void finishCompensationFatalGraphStopsBeforeCleanupWarningOrMetric() {
    for (FatalFailure failure : fatalFailures()) {
      try (Fixture fixture = new Fixture()) {
        RuntimeException primary = fixture.failInput();
        fixture.failFinish(failure.failure());

        failure.assertEscapes(fixture);

        fixture.verifyInputSaved();
        fixture.verifyCompensationAttempted();
        fixture.verifyOnlySchedule("failed");
        fixture.verifyOnlyPreparationWarning(primary);
        verifyNoInteractions(fixture.mapper, fixture.queue);
        assertOrdinaryFailureUnchanged(primary);
      }
    }
  }

  @Test(timeout = 5000)
  public void finishFailureLoggerFatalGraphStopsBeforeCleanupMetric() {
    for (FatalFailure failure : fatalFailures()) {
      try (Fixture fixture = new Fixture()) {
        RuntimeException primary = fixture.failInput();
        RuntimeException finishFailure = new IllegalStateException("finish unavailable");
        fixture.failFinish(finishFailure);
        doThrow(failure.failure())
            .when(fixture.logger)
            .warn(eq(FINISH_WARNING), any(Object[].class));

        failure.assertEscapes(fixture);

        fixture.verifyInputSaved();
        fixture.verifyCompensationAttempted();
        fixture.verifyOnlySchedule("failed");
        verify(fixture.logger).warn(PREPARATION_WARNING, new Object[] {42L, primary});
        verify(fixture.logger).warn(FINISH_WARNING, new Object[] {42L, finishFailure});
        verifyNoMoreInteractions(fixture.logger);
        verifyNoInteractions(fixture.mapper, fixture.queue);
        assertOrdinaryFailureUnchanged(primary);
        assertOrdinaryFailureUnchanged(finishFailure);
      }
    }
  }

  @Test(timeout = 5000)
  public void finishFailureMeterFatalGraphEscapesWithoutFurtherDiagnostics() {
    for (FatalFailure failure : fatalFailures()) {
      try (Fixture fixture = new Fixture()) {
        RuntimeException primary = fixture.failInput();
        RuntimeException finishFailure = new IllegalStateException("finish unavailable");
        fixture.failFinish(finishFailure);
        doThrow(failure.failure()).when(fixture.finishCounter).increment();

        failure.assertEscapes(fixture);

        fixture.verifyInputSaved();
        fixture.verifyCompensationAttempted();
        fixture.verifyScheduleAttempted("failed");
        verify(fixture.meters).counter(FINISH_METER, "queueName", "assetlocalize");
        verify(fixture.finishCounter).increment();
        verifyNoMoreInteractions(fixture.meters, fixture.scheduleCounter, fixture.finishCounter);
        verify(fixture.logger).warn(PREPARATION_WARNING, new Object[] {42L, primary});
        verify(fixture.logger).warn(FINISH_WARNING, new Object[] {42L, finishFailure});
        verifyNoMoreInteractions(fixture.logger);
        verifyNoInteractions(fixture.mapper, fixture.queue);
        assertOrdinaryFailureUnchanged(primary);
        assertOrdinaryFailureUnchanged(finishFailure);
      }
    }
  }

  @Test(timeout = 5000)
  public void mixedCauseAndSuppressedCyclesStillExposeFatalWithoutRewritingGraph() {
    try (Fixture fixture = new Fixture()) {
      Error fatal = new TestThreadDeath();
      RuntimeException first = new RuntimeException("first");
      RuntimeException second = new RuntimeException("second", first);
      first.initCause(second);
      first.addSuppressed(second);
      second.addSuppressed(first);
      second.addSuppressed(fatal);
      fixture.failTaskCreation(first);

      new FatalFailure(first, fatal).assertEscapes(fixture);

      assertThat(first.getCause()).isSameAs(second);
      assertThat(first.getSuppressed()).containsExactly(second);
      assertThat(second.getCause()).isSameAs(first);
      assertThat(second.getSuppressed()).containsExactly(first, fatal);
      fixture.verifyNoCompensation();
      fixture.verifyNoMetrics();
      verifyNoInteractions(fixture.blobs, fixture.mapper, fixture.queue, fixture.logger);
    }
  }

  @Test(timeout = 5000)
  public void nonfatalMixedCycleTerminatesAndRetainsOrdinaryFailurePolicy() {
    try (Fixture fixture = new Fixture()) {
      RuntimeException first = new RuntimeException("first");
      RuntimeException second = new RuntimeException("second", first);
      first.initCause(second);
      first.addSuppressed(second);
      first.addSuppressed(second);
      second.addSuppressed(first);
      fixture.failTaskCreation(first);

      assertThat(catchThrowable(() -> fixture.service.scheduleJob(fixture.job))).isSameAs(first);

      assertThat(first.getCause()).isSameAs(second);
      assertThat(first.getSuppressed()).containsExactly(second, second);
      assertThat(second.getCause()).isSameAs(first);
      assertThat(second.getSuppressed()).containsExactly(first);
      fixture.verifyNoCompensation();
      fixture.verifyOnlySchedule("failed");
      verifyNoInteractions(fixture.blobs, fixture.mapper, fixture.queue, fixture.logger);
    }
  }

  @Test(timeout = 5000)
  public void distinctButEqualThrowablesDoNotHideFatalOnSuppressedCause() {
    try (Fixture fixture = new Fixture()) {
      Error fatal = new TestVmError();
      EqualFailure first = new EqualFailure();
      EqualFailure second = new EqualFailure();
      first.addSuppressed(second);
      second.initCause(fatal);
      fixture.failTaskCreation(first);

      assertThat(first).isEqualTo(second).isNotSameAs(second);
      new FatalFailure(first, fatal).assertEscapes(fixture);

      assertThat(first.getCause()).isNull();
      assertThat(first.getSuppressed()).containsExactly(second);
      assertThat(second.getCause()).isSameAs(fatal);
      assertThat(second.getSuppressed()).isEmpty();
      fixture.verifyNoCompensation();
      fixture.verifyNoMetrics();
      verifyNoInteractions(fixture.blobs, fixture.mapper, fixture.queue, fixture.logger);
    }
  }

  @Test(timeout = 5000)
  public void deepCauseChainEscapesAsOriginalFatalWithoutRecursiveTraversal() {
    try (Fixture fixture = new Fixture()) {
      Error fatal = new TestVmError();
      StacklessFailure[] chain = new StacklessFailure[20000];
      Throwable cause = fatal;
      for (int i = chain.length - 1; i >= 0; i--) {
        chain[i] = new StacklessFailure(cause);
        cause = chain[i];
      }
      fixture.failTaskCreation(chain[0]);

      Throwable escaped = catchThrowable(() -> fixture.service.scheduleJob(fixture.job));

      // Do not render a 20,000-node stack trace when testing the unfixed producer.
      assertThat(escaped == fatal)
          .as("original fatal, not a wrapper or traversal overflow")
          .isTrue();
      for (int i = 0; i < chain.length; i++) {
        Throwable expectedCause = i + 1 < chain.length ? chain[i + 1] : fatal;
        assertThat(chain[i].getCause() == expectedCause).isTrue();
        assertThat(chain[i].getSuppressed()).isEmpty();
      }
      assertThat(fatal.getCause()).isNull();
      assertThat(fatal.getSuppressed()).isEmpty();
      fixture.verifyNoCompensation();
      fixture.verifyNoMetrics();
      verifyNoInteractions(fixture.blobs, fixture.mapper, fixture.queue, fixture.logger);
    }
  }

  private static List<FatalFailure> fatalFailures() {
    Error causeVm = new TestVmError();
    Error suppressedVm = new TestVmError();
    Error causeThreadDeath = new TestThreadDeath();
    Error suppressedThreadDeath = new TestThreadDeath();
    return List.of(
        new FatalFailure(new IllegalStateException("wrapped VM failure", causeVm), causeVm),
        suppressedFailure(suppressedVm),
        new FatalFailure(
            new IllegalStateException("wrapped thread death", causeThreadDeath), causeThreadDeath),
        suppressedFailure(suppressedThreadDeath));
  }

  private static FatalFailure suppressedFailure(Error fatal) {
    RuntimeException primary =
        new IllegalStateException("ordinary primary", new AssertionError("ordinary cause"));
    primary.addSuppressed(new AssertionError("ordinary cleanup"));
    primary.addSuppressed(fatal);
    return new FatalFailure(primary, fatal);
  }

  private static void assertOrdinaryFailureUnchanged(Throwable failure) {
    assertThat(failure.getCause()).isNull();
    assertThat(failure.getSuppressed()).isEmpty();
  }

  private record FatalFailure(Throwable failure, Error fatal) {
    void assertEscapes(Fixture fixture) {
      Throwable cause = failure.getCause();
      Throwable[] suppressed = failure.getSuppressed();
      Throwable fatalCause = fatal.getCause();
      Throwable[] fatalSuppressed = fatal.getSuppressed();

      assertThat(catchThrowable(() -> fixture.service.scheduleJob(fixture.job))).isSameAs(fatal);

      assertThat(failure.getCause()).isSameAs(cause);
      assertThat(failure.getSuppressed()).containsExactly(suppressed);
      assertThat(fatal.getCause()).isSameAs(fatalCause);
      assertThat(fatal.getSuppressed()).containsExactly(fatalSuppressed);
    }
  }

  private static class Fixture implements AutoCloseable {
    final PollableTaskService tasks = mock(PollableTaskService.class);
    final PollableTaskBlobStorage blobs = mock(PollableTaskBlobStorage.class);
    final PollableTaskExceptionUtils exceptions = mock(PollableTaskExceptionUtils.class);
    final AsyncJobQueueSubmissionService queue = mock(AsyncJobQueueSubmissionService.class);
    final ObjectMapper mapper = mock(ObjectMapper.class);
    final MeterRegistry meters = mock(MeterRegistry.class);
    final Counter scheduleCounter = mock(Counter.class);
    final Counter finishCounter = mock(Counter.class);
    final Logger logger = mock(Logger.class);
    final Logger originalLogger = AssetLocalizeAsyncJobSubmissionService.logger;
    final QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> job =
        QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
            .withInput(new LocalizedAssetBody())
            .build();
    final AssetLocalizeAsyncJobSubmissionService service =
        new AssetLocalizeAsyncJobSubmissionService(tasks, blobs, exceptions, queue, mapper, meters);

    Fixture() {
      PollableTask task = new PollableTask();
      task.setId(42L);
      when(tasks.createPollableTask(
              null, GenerateLocalizedAssetJob.class.getCanonicalName(), null, 0, 3600))
          .thenReturn(task);
      when(mapper.writeValueAsStringUnchecked(any())).thenReturn("payload");
      for (String result : List.of("succeeded", "failed", "outcomeUnknown")) {
        when(meters.counter(SCHEDULE_METER, "queueName", "assetlocalize", "result", result))
            .thenReturn(scheduleCounter);
      }
      when(meters.counter(FINISH_METER, "queueName", "assetlocalize")).thenReturn(finishCounter);
      AssetLocalizeAsyncJobSubmissionService.logger = logger;
    }

    void failTaskCreation(Throwable failure) {
      doThrow(failure)
          .when(tasks)
          .createPollableTask(
              null, GenerateLocalizedAssetJob.class.getCanonicalName(), null, 0, 3600);
    }

    RuntimeException failInput() {
      RuntimeException failure = new IllegalStateException("input unavailable");
      doThrow(failure).when(blobs).saveInput(42L, job.getInput());
      return failure;
    }

    void failFinish(Throwable failure) {
      doThrow(failure)
          .when(tasks)
          .finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());
    }

    void verifyTaskCreated() {
      verify(tasks)
          .createPollableTask(
              null, GenerateLocalizedAssetJob.class.getCanonicalName(), null, 0, 3600);
    }

    void verifyNoCompensation() {
      verifyTaskCreated();
      verifyNoMoreInteractions(tasks);
      verifyNoInteractions(exceptions);
    }

    void verifyInputSaved() {
      verify(blobs).saveInput(42L, job.getInput());
      verifyNoMoreInteractions(blobs);
    }

    void verifyPreparationAttempted() {
      verifyInputSaved();
      verify(mapper).writeValueAsStringUnchecked(new AssetLocalizeAsyncJobPayload(42L));
      verifyNoMoreInteractions(mapper);
    }

    void verifyEnqueueAttempted() {
      verify(queue).enqueueNow("assetlocalize", "payload");
      verifyNoMoreInteractions(queue);
    }

    void verifyClassificationAttempted() {
      verify(exceptions).processException(any(Throwable.class), any(ExceptionHolder.class));
      verifyNoMoreInteractions(exceptions);
    }

    void verifyCompensationAttempted() {
      verifyTaskCreated();
      verifyClassificationAttempted();
      verify(tasks).finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());
      verifyNoMoreInteractions(tasks);
    }

    void verifyNoMetrics() {
      verifyNoInteractions(meters, scheduleCounter, finishCounter);
    }

    void verifyScheduleAttempted(String result) {
      verify(meters).counter(SCHEDULE_METER, "queueName", "assetlocalize", "result", result);
      verify(scheduleCounter).increment();
    }

    void verifyOnlySchedule(String result) {
      verifyScheduleAttempted(result);
      verifyNoMoreInteractions(meters, scheduleCounter, finishCounter);
    }

    void verifyOnlyPreparationWarning(Throwable primary) {
      verify(logger).warn(PREPARATION_WARNING, new Object[] {42L, primary});
      verifyNoMoreInteractions(logger);
    }

    @Override
    public void close() {
      AssetLocalizeAsyncJobSubmissionService.logger = originalLogger;
    }
  }

  private static class TestVmError extends VirtualMachineError {}

  private static class TestThreadDeath extends ThreadDeath {}

  private static class EqualFailure extends RuntimeException {
    @Override
    public boolean equals(Object other) {
      return other instanceof EqualFailure;
    }

    @Override
    public int hashCode() {
      return 1;
    }
  }

  private static class StacklessFailure extends RuntimeException {
    StacklessFailure(Throwable cause) {
      super("deep cause", cause, true, false);
    }
  }
}
