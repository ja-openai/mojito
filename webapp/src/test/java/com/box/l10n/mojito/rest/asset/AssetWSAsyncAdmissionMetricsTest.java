package com.box.l10n.mojito.rest.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableFutureTask;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobSubmissionService;
import com.box.l10n.mojito.service.tm.GenerateLocalizedAssetJob;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;

public class AssetWSAsyncAdmissionMetricsTest {

  private static final String LATENCY = "assetWS.getLocalizedAssetForContentAsync.schedule.latency";
  private static final String SCHEDULE = "assetWS.getLocalizedAssetForContentAsync.schedule";
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final Logger originalLogger = AssetWS.logger;
  private final AssetWS assetWS = new AssetWS();
  private final QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> jobInfo =
      QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
          .withInput(new LocalizedAssetBody())
          .build();

  @After
  public void tearDown() {
    AssetWS.logger = originalLogger;
    registry.close();
  }

  @Test
  public void acknowledgedAdmissionsHaveComparableTimersAndBoundedTags() {
    for (String route : List.of("quartz", "assetlocalize")) {
      configureRoute(route);
      PollableFuture<LocalizedAssetBody> accepted = acceptedTask();
      stubSuccess(route, accepted);

      assertThat(assetWS.scheduleLocalizedAssetJob(jobInfo)).isSameAs(accepted);

      Timer timer = registry.get(LATENCY).tags(tags(route, "succeeded")).timer();
      assertThat(timer.count()).isEqualTo(1);
      assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isGreaterThanOrEqualTo(0);
      assertThat(timer.getId().getTags()).containsExactlyElementsOf(tags(route, "succeeded"));
      assertThat(registry.get(SCHEDULE).tags(tags(route, "succeeded")).counter().count())
          .isEqualTo(1);
      assertThat(registry.find(LATENCY).tags(tags(route, "failed")).timer()).isNull();
      assertNoFallback(route);
    }
  }

  @Test
  public void thrownAdmissionsAreTimedWithoutChangingUnknownOutcomes() {
    for (String route : List.of("quartz", "assetlocalize")) {
      configureRoute(route);
      RuntimeException failure = new IllegalStateException("commit acknowledgement unknown");
      stubFailure(route, failure);

      assertThat(
              assertThrows(
                  RuntimeException.class, () -> assetWS.scheduleLocalizedAssetJob(jobInfo)))
          .isSameAs(failure);

      assertThat(registry.get(LATENCY).tags(tags(route, "failed")).timer().count()).isEqualTo(1);
      assertThat(registry.find(LATENCY).tags(tags(route, "succeeded")).timer()).isNull();
      assertNoFallback(route);
    }
  }

  @Test
  public void unavailableServiceIsTimedButDoesNotFallBackToQuartz() {
    configureRoute("assetlocalize");
    assetWS.assetLocalizeAsyncJobSubmissionService = null;

    assertThrows(IllegalStateException.class, () -> assetWS.scheduleLocalizedAssetJob(jobInfo));

    assertThat(registry.get(LATENCY).tags(tags("assetlocalize", "failed")).timer().count())
        .isEqualTo(1);
    verifyNoInteractions(assetWS.quartzPollableTaskScheduler);
  }

  @Test
  public void timerRegistrationAndLoggerFailuresCannotRejectAcceptedWork() {
    failRegistration(LATENCY, new IllegalStateException("timer unavailable"));
    failLogger(new AssertionError("logger unavailable"));
    for (String route : List.of("quartz", "assetlocalize")) {
      configureRoute(route);
      PollableFuture<LocalizedAssetBody> accepted = acceptedTask();
      stubSuccess(route, accepted);

      assertThat(assetWS.scheduleLocalizedAssetJob(jobInfo)).isSameAs(accepted);

      assertNoFallback(route);
      assertThat(registry.find(SCHEDULE).tags(tags(route, "failed")).counter()).isNull();
    }
  }

  @Test
  public void timerRecordingAndLoggerFailuresPreserveAcceptedWorkAndOriginalFailures() {
    for (String route : List.of("quartz", "assetlocalize")) {
      for (String result : List.of("succeeded", "failed")) {
        configureRoute(route);
        SimpleMeterRegistry failingRegistry = spy(registry);
        Timer timer = mock(Timer.class);
        doThrow(new AssertionError("timer recording failed"))
            .when(timer)
            .record(anyLong(), eq(TimeUnit.NANOSECONDS));
        doReturn(timer).when(failingRegistry).timer(LATENCY, tags(route, result));
        assetWS.meterRegistry = failingRegistry;
        failLogger(new IllegalStateException("logger unavailable"));
        RuntimeException failure = new IllegalStateException("original admission failure");
        PollableFuture<LocalizedAssetBody> accepted = acceptedTask();
        if (result.equals("succeeded")) {
          stubSuccess(route, accepted);
          assertThat(assetWS.scheduleLocalizedAssetJob(jobInfo)).isSameAs(accepted);
        } else {
          stubFailure(route, failure);
          assertThat(
                  assertThrows(
                      RuntimeException.class, () -> assetWS.scheduleLocalizedAssetJob(jobInfo)))
              .isSameAs(failure);
        }
        verify(timer).record(anyLong(), eq(TimeUnit.NANOSECONDS));
        assertNoFallback(route);
      }
    }
  }

  @Test
  public void counterAndLoggerFailuresDoNotSuppressTheIndependentTimer() {
    configureRoute("assetlocalize");
    PollableFuture<LocalizedAssetBody> accepted = acceptedTask();
    stubSuccess("assetlocalize", accepted);
    failRegistration(SCHEDULE, new IllegalStateException("counter unavailable"));
    failLogger(new AssertionError("logger unavailable"));

    assertThat(assetWS.scheduleLocalizedAssetJob(jobInfo)).isSameAs(accepted);

    assertThat(registry.get(LATENCY).tags(tags("assetlocalize", "succeeded")).timer().count())
        .isEqualTo(1);
    assertNoFallback("assetlocalize");
  }

  @Test
  public void cyclicNonFatalDiagnosticsCannotRejectAcceptedWork() {
    configureRoute("assetlocalize");
    PollableFuture<LocalizedAssetBody> accepted = acceptedTask();
    stubSuccess("assetlocalize", accepted);
    RuntimeException first = new IllegalStateException("first diagnostic");
    RuntimeException second = new IllegalStateException("second diagnostic");
    first.initCause(second);
    second.initCause(first);
    failRegistration(LATENCY, first);
    failLogger(second);

    assertThat(assetWS.scheduleLocalizedAssetJob(jobInfo)).isSameAs(accepted);

    assertThat(first.getCause()).isSameAs(second);
    assertThat(second.getCause()).isSameAs(first);
    assertNoFallback("assetlocalize");
  }

  @Test
  public void wrappedTimerFatalPropagatesWithoutManufacturingFailedAdmission() {
    configureRoute("assetlocalize");
    stubSuccess("assetlocalize", acceptedTask());
    FatalTestError fatal = new FatalTestError();
    failRegistration(LATENCY, new IllegalStateException(fatal));

    assertThat(assertThrows(FatalTestError.class, () -> assetWS.scheduleLocalizedAssetJob(jobInfo)))
        .isSameAs(fatal);

    assertThat(registry.find(SCHEDULE).tags(tags("assetlocalize", "failed")).counter()).isNull();
    assertNoFallback("assetlocalize");
  }

  @Test
  public void suppressedLoggerFatalPropagatesWithoutFallback() {
    configureRoute("assetlocalize");
    stubSuccess("assetlocalize", acceptedTask());
    FatalTestError fatal = new FatalTestError();
    RuntimeException loggingFailure = new IllegalStateException("logger unavailable");
    loggingFailure.addSuppressed(fatal);
    failLogger(loggingFailure);
    failRegistration(LATENCY, new IllegalStateException("timer unavailable"));

    assertThat(assertThrows(FatalTestError.class, () -> assetWS.scheduleLocalizedAssetJob(jobInfo)))
        .isSameAs(fatal);
    assertNoFallback("assetlocalize");
  }

  private void configureRoute(String route) {
    assetWS.meterRegistry = registry;
    assetWS.quartzPollableTaskScheduler = mock(QuartzPollableTaskScheduler.class);
    assetWS.assetLocalizeAsyncJobSubmissionService =
        mock(AssetLocalizeAsyncJobSubmissionService.class);
    assetWS.asyncJobQueueEnabled = route.equals("assetlocalize");
    assetWS.asyncJobQueueAssetLocalizeEnabled = true;
    assetWS.asyncJobQueueAssetLocalizeProducerEnabled = true;
  }

  private PollableFuture<LocalizedAssetBody> acceptedTask() {
    return new QuartzPollableFutureTask<>(new PollableTask(), LocalizedAssetBody.class);
  }

  private void stubSuccess(String route, PollableFuture<LocalizedAssetBody> accepted) {
    if (route.equals("assetlocalize")) {
      when(assetWS.assetLocalizeAsyncJobSubmissionService.scheduleJob(jobInfo))
          .thenReturn(accepted);
    } else {
      when(assetWS.quartzPollableTaskScheduler.scheduleJob(jobInfo)).thenReturn(accepted);
    }
  }

  private void stubFailure(String route, RuntimeException failure) {
    if (route.equals("assetlocalize")) {
      when(assetWS.assetLocalizeAsyncJobSubmissionService.scheduleJob(jobInfo)).thenThrow(failure);
    } else {
      when(assetWS.quartzPollableTaskScheduler.scheduleJob(jobInfo)).thenThrow(failure);
    }
  }

  private void assertNoFallback(String route) {
    if (route.equals("assetlocalize")) {
      verify(assetWS.assetLocalizeAsyncJobSubmissionService).scheduleJob(jobInfo);
      verifyNoInteractions(assetWS.quartzPollableTaskScheduler);
    } else {
      verify(assetWS.quartzPollableTaskScheduler).scheduleJob(jobInfo);
      verifyNoInteractions(assetWS.assetLocalizeAsyncJobSubmissionService);
    }
  }

  private Tags tags(String route, String result) {
    return Tags.of("route", route, "result", result);
  }

  private void failRegistration(String name, RuntimeException failure) {
    registry
        .config()
        .meterFilter(
            new MeterFilter() {
              @Override
              public Meter.Id map(Meter.Id id) {
                if (id.getName().equals(name)) {
                  throw failure;
                }
                return id;
              }
            });
  }

  private void failLogger(Throwable failure) {
    AssetWS.logger =
        mock(
            Logger.class,
            invocation -> {
              if (invocation.getMethod().getName().equals("warn")) {
                throw failure;
              }
              return RETURNS_DEFAULTS.answer(invocation);
            });
  }

  private static final class FatalTestError extends VirtualMachineError {}
}
