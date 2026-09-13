package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

public class AsyncJobQueueCoordinatorFailureTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final AsyncJobStore store = mock(AsyncJobStore.class);
  private final TaskScheduler scheduler = mock(TaskScheduler.class);
  private final ThreadPoolTaskExecutor firstExecutor = mock(ThreadPoolTaskExecutor.class);
  private final ThreadPoolTaskExecutor secondExecutor = mock(ThreadPoolTaskExecutor.class);
  private final ThreadPoolTaskScheduler firstHeartbeat = mock(ThreadPoolTaskScheduler.class);
  private final ThreadPoolTaskScheduler secondHeartbeat = mock(ThreadPoolTaskScheduler.class);
  private final ScheduledFuture<?> firstPoll = mock(ScheduledFuture.class);
  private final ScheduledFuture<?> secondPoll = mock(ScheduledFuture.class);
  private final AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
  private final Logger originalLogger = AsyncJobQueueCoordinator.logger;

  @Before
  public void setUp() {
    properties.setStore("jdbc");
    AsyncJobQueueProperties.QueueSettings disabled = new AsyncJobQueueProperties.QueueSettings();
    disabled.setConsumerEnabled(false);
    properties.getQueues().put("disabled", disabled);
    when(scheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(ignored -> firstPoll)
        .thenAnswer(ignored -> secondPoll);
  }

  @After
  public void tearDown() {
    AsyncJobQueueCoordinator.logger = originalLogger;
    registry.close();
  }

  @Test(timeout = 5000)
  public void fatalConstructorFailureSurvivesBothOrdinaryResourceCleanupFailures() {
    InternalError fatal = new InternalError("fatal runtime construction");
    IllegalStateException heartbeatFailure = new IllegalStateException("heartbeat shutdown");
    IllegalStateException executorFailure = new IllegalStateException("executor shutdown");
    failGaugeRegistration("first", fatal);
    doThrow(heartbeatFailure).when(firstHeartbeat).shutdown();
    doThrow(executorFailure).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first");

    Throwable thrown = catchThrowable(coordinator::start);

    assertThat(thrown).isSameAs(fatal);
    assertThat(thrown.getSuppressed()).contains(heartbeatFailure, executorFailure);
    assertFirstConstructionCleaned(coordinator);
  }

  @Test(timeout = 5000)
  public void fatalHeartbeatInitializationSurvivesBothOrdinaryResourceCleanupFailures() {
    InternalError fatal = new InternalError("fatal heartbeat initialization");
    IllegalStateException heartbeatFailure = new IllegalStateException("heartbeat shutdown");
    IllegalStateException executorFailure = new IllegalStateException("executor shutdown");
    doThrow(fatal).when(firstHeartbeat).initialize();
    doThrow(heartbeatFailure).when(firstHeartbeat).shutdown();
    doThrow(executorFailure).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first");

    Throwable thrown = catchThrowable(coordinator::start);

    assertThat(thrown).isSameAs(fatal);
    assertThat(thrown.getSuppressed()).contains(heartbeatFailure, executorFailure);
    assertFirstConstructionCleaned(coordinator);
  }

  @Test(timeout = 5000)
  public void ordinaryConstructorFailureSurvivesOrdinaryResourceCleanupFailures() {
    IllegalStateException startupFailure = new IllegalStateException("constructor settings");
    IllegalStateException heartbeatFailure = new IllegalStateException("heartbeat shutdown");
    IllegalStateException executorFailure = new IllegalStateException("executor shutdown");
    failFirstConstructor(startupFailure);
    doThrow(heartbeatFailure).when(firstHeartbeat).shutdown();
    doThrow(executorFailure).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first");

    Throwable thrown = catchThrowable(coordinator::start);

    assertThat(thrown).isSameAs(startupFailure);
    assertThat(thrown.getSuppressed()).contains(heartbeatFailure, executorFailure);
    assertFirstConstructionCleaned(coordinator);
  }

  @Test(timeout = 5000)
  public void fatalHeartbeatCleanupOverridesOrdinaryConstructorAndLaterExecutorFailures() {
    IllegalStateException startupFailure = new IllegalStateException("constructor settings");
    InternalError fatal = new InternalError("fatal heartbeat shutdown");
    IllegalStateException executorFailure = new IllegalStateException("executor shutdown");
    failFirstConstructor(startupFailure);
    doThrow(fatal).when(firstHeartbeat).shutdown();
    doThrow(executorFailure).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first");

    Throwable thrown = catchThrowable(coordinator::start);

    assertThat(thrown).isSameAs(fatal);
    assertThat(thrown.getSuppressed()).contains(startupFailure, executorFailure);
    assertFirstConstructionCleaned(coordinator);
  }

  @Test(timeout = 5000)
  public void fatalExecutorCleanupOverridesOrdinaryConstructorFailure() {
    IllegalStateException startupFailure = new IllegalStateException("constructor settings");
    InternalError fatal = new InternalError("fatal executor shutdown");
    failFirstConstructor(startupFailure);
    doThrow(fatal).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first");

    Throwable thrown = catchThrowable(coordinator::start);

    assertThat(thrown).isSameAs(fatal);
    assertThat(thrown.getSuppressed()).contains(startupFailure);
    assertFirstConstructionCleaned(coordinator);
  }

  @Test(timeout = 5000)
  public void fatalSecondConstructorFailureCleansPreviouslyStartedQueue() {
    InternalError fatal = new InternalError("fatal second runtime construction");
    failGaugeRegistration("second", fatal);
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");

    Throwable thrown = catchThrowable(coordinator::start);

    assertBothQueuesCleaned(coordinator, false);
    assertThat(thrown).isSameAs(fatal);
    verify(scheduler).schedule(any(Runnable.class), any(Date.class));
  }

  @Test(timeout = 5000)
  public void fatalSecondInitialPollFailureCleansBothBuiltRuntimes() {
    InternalError fatal = new InternalError("fatal second initial poll");
    failSecondInitialPoll(fatal);
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");

    Throwable thrown = catchThrowable(coordinator::start);

    assertBothQueuesCleaned(coordinator, false);
    assertThat(thrown).isSameAs(fatal);
    verify(scheduler, times(2)).schedule(any(Runnable.class), any(Date.class));
  }

  @Test(timeout = 5000)
  public void ordinaryStartupFailureSurvivesOrdinaryRollbackFailure() {
    IllegalStateException startupFailure = new IllegalStateException("second initial poll");
    IllegalStateException stopFailure = new IllegalStateException("first executor shutdown");
    failSecondInitialPoll(startupFailure);
    doThrow(stopFailure).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");

    Throwable thrown = catchThrowable(coordinator::start);

    assertBothQueuesCleaned(coordinator, false);
    assertThat(thrown).isSameAs(startupFailure);
    assertThat(thrown.getSuppressed()).contains(stopFailure);
    assertStopFailureRecorded("first");
  }

  @Test(timeout = 5000)
  public void fatalRollbackOverridesOrdinaryStartupFailureAfterCleaningRemainingRuntime() {
    IllegalStateException startupFailure = new IllegalStateException("second initial poll");
    InternalError fatal = new InternalError("fatal first executor shutdown");
    failSecondInitialPoll(startupFailure);
    doThrow(fatal).when(firstExecutor).shutdown();
    doThrow(new IllegalStateException("second executor shutdown")).when(secondExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");

    Throwable thrown = catchThrowable(coordinator::start);

    assertBothQueuesCleaned(coordinator, false);
    assertThat(thrown).isSameAs(fatal);
    assertThat(thrown.getSuppressed()).contains(startupFailure);
    assertStopFailureRecorded("second");
  }

  @Test(timeout = 5000)
  public void fatalStartupFailureSurvivesOrdinaryRollbackFailure() {
    InternalError fatal = new InternalError("fatal second initial poll");
    IllegalStateException stopFailure = new IllegalStateException("first executor shutdown");
    failSecondInitialPoll(fatal);
    doThrow(stopFailure).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");

    Throwable thrown = catchThrowable(coordinator::start);

    assertBothQueuesCleaned(coordinator, false);
    assertThat(thrown).isSameAs(fatal);
    assertThat(thrown.getSuppressed()).contains(stopFailure);
    assertStopFailureRecorded("first");
  }

  @Test(timeout = 5000)
  public void ordinaryExplicitStopFailureIsRecordedAndSwallowedAfterFullCleanup() {
    assertOrdinaryExplicitStop(new IllegalStateException("first executor shutdown"));
  }

  @Test(timeout = 5000)
  public void nonfatalErrorFromExplicitStopIsRecordedAndSwallowedAfterFullCleanup() {
    assertOrdinaryExplicitStop(new AssertionError("nonfatal first executor shutdown"));
  }

  @Test(timeout = 5000)
  public void fatalExplicitStopFailureEscapesOnlyAfterRemainingRuntimeAndStateAreCleaned() {
    InternalError fatal = new InternalError("fatal first executor shutdown");
    doThrow(fatal).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");
    coordinator.start();
    assertStarted(coordinator);

    Throwable thrown = catchThrowable(coordinator::stop);

    assertBothQueuesCleaned(coordinator, true);
    assertThat(thrown).isSameAs(fatal);
    coordinator.stop();
    verify(firstExecutor).shutdown();
    verify(secondExecutor).shutdown();
  }

  @Test(timeout = 5000)
  public void firstFatalStopFailureTakesPriorityOverLaterFatalCleanup() {
    InternalError firstFatal = new InternalError("fatal first executor shutdown");
    InternalError secondFatal = new InternalError("fatal second executor shutdown");
    doThrow(firstFatal).when(firstExecutor).shutdown();
    doThrow(secondFatal).when(secondExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");
    coordinator.start();

    Throwable thrown = catchThrowable(coordinator::stop);

    assertBothQueuesCleaned(coordinator, true);
    assertThat(thrown).isSameAs(firstFatal);
    assertThat(thrown.getSuppressed()).contains(secondFatal);
  }

  @Test(timeout = 5000)
  public void ordinaryStopFailureStillRunsCallbackAfterCleanup() {
    doThrow(new IllegalStateException("first executor shutdown")).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");
    coordinator.start();
    Runnable callback = mock(Runnable.class);
    doAnswer(
            ignored -> {
              assertBothQueuesCleaned(coordinator, true);
              return null;
            })
        .when(callback)
        .run();

    Throwable thrown = catchThrowable(() -> coordinator.stop(callback));

    assertThat(thrown).isNull();
    verify(callback).run();
    assertStopFailureRecorded("first");
  }

  @Test(timeout = 5000)
  public void fatalStopFailureDoesNotRunCallbackButStillCleansAllRuntimes() {
    InternalError fatal = new InternalError("fatal first executor shutdown");
    doThrow(fatal).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");
    coordinator.start();
    Runnable callback = mock(Runnable.class);

    Throwable thrown = catchThrowable(() -> coordinator.stop(callback));

    assertBothQueuesCleaned(coordinator, true);
    assertThat(thrown).isSameAs(fatal);
    verifyNoInteractions(callback);
  }

  @Test(timeout = 5000)
  public void brokenDisabledConsumerDiagnosticDoesNotFailStartup() {
    Logger failingLogger = mock(Logger.class);
    doThrow(new AssertionError("disabled consumer logging unavailable"))
        .when(failingLogger)
        .info(anyString(), any(Object.class));
    doThrow(new AssertionError("fallback logging unavailable"))
        .when(failingLogger)
        .warn(anyString(), any(Throwable.class));
    AsyncJobQueueCoordinator.logger = failingLogger;
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");

    Throwable thrown = catchThrowable(coordinator::start);

    assertThat(thrown).isNull();
    assertStarted(coordinator);
    verify(failingLogger).info(anyString(), any(Object.class));
    coordinator.stop();
    assertBothQueuesCleaned(coordinator, true);
  }

  @Test(timeout = 5000)
  public void brokenConfiguredQueueWithoutHandlerDiagnosticDoesNotFailStartup() {
    properties.getQueues().put("missing", new AsyncJobQueueProperties.QueueSettings());
    Logger failingLogger = mock(Logger.class);
    doThrow(new AssertionError("missing handler logging unavailable"))
        .when(failingLogger)
        .warn(anyString(), any(Object.class));
    doThrow(new AssertionError("fallback logging unavailable"))
        .when(failingLogger)
        .warn(anyString(), any(Throwable.class));
    AsyncJobQueueCoordinator.logger = failingLogger;
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");

    Throwable thrown = catchThrowable(coordinator::start);

    assertThat(thrown).isNull();
    assertStarted(coordinator);
    verify(failingLogger).warn(anyString(), any(Object.class));
    coordinator.stop();
    assertBothQueuesCleaned(coordinator, true);
  }

  @Test(timeout = 5000)
  public void stopMetricFailureCannotAbortExplicitStopCleanup() {
    collideStopFailureCounter();
    doThrow(new IllegalStateException("first executor shutdown")).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");
    coordinator.start();
    assertStarted(coordinator);

    Throwable thrown = catchThrowable(coordinator::stop);

    assertBothQueuesCleaned(coordinator, true);
    assertThat(thrown).isNull();
    assertThat(registry.get("asyncJobQueue.runtime.stop.failed").timer()).isNotNull();
  }

  @Test(timeout = 5000)
  public void stopLoggingFailureCannotAbortExplicitStopCleanup() {
    Logger failingLogger = failStopLogging(new AssertionError("stop logging unavailable"));
    doThrow(new IllegalStateException("first executor shutdown")).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");
    coordinator.start();
    assertStarted(coordinator);

    Throwable thrown = catchThrowable(coordinator::stop);

    assertBothQueuesCleaned(coordinator, true);
    assertThat(thrown).isNull();
    verify(failingLogger).warn(anyString(), any(), any());
    assertStopFailureRecorded("first");
  }

  @Test(timeout = 5000)
  public void stopMetricFailureCannotMaskStartupFailureOrAbortRollback() {
    IllegalStateException startupFailure = new IllegalStateException("second initial poll");
    failSecondInitialPoll(startupFailure);
    collideStopFailureCounter();
    doThrow(new IllegalStateException("first executor shutdown")).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");

    Throwable thrown = catchThrowable(coordinator::start);

    assertBothQueuesCleaned(coordinator, false);
    assertThat(thrown).isSameAs(startupFailure);
  }

  @Test(timeout = 5000)
  public void stopLoggingFailureCannotMaskStartupFailureOrAbortRollback() {
    IllegalStateException startupFailure = new IllegalStateException("second initial poll");
    failSecondInitialPoll(startupFailure);
    Logger failingLogger = failStopLogging(new AssertionError("stop logging unavailable"));
    doThrow(new IllegalStateException("first executor shutdown")).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");

    Throwable thrown = catchThrowable(coordinator::start);

    assertBothQueuesCleaned(coordinator, false);
    assertThat(thrown).isSameAs(startupFailure);
    verify(failingLogger).warn(anyString(), any(), any());
    assertStopFailureRecorded("first");
  }

  @Test(timeout = 5000)
  public void startupLoggingFailureCannotMaskStartupFailureOrSkipRollback() {
    IllegalStateException startupFailure = new IllegalStateException("second initial poll");
    failSecondInitialPoll(startupFailure);
    Logger failingLogger = mock(Logger.class);
    doThrow(new AssertionError("startup logging unavailable"))
        .when(failingLogger)
        .error(anyString(), any(), any());
    doThrow(new AssertionError("startup logging unavailable"))
        .when(failingLogger)
        .error(anyString(), any(Throwable.class));
    doThrow(new AssertionError("fallback logging unavailable"))
        .when(failingLogger)
        .warn(anyString(), any(Throwable.class));
    AsyncJobQueueCoordinator.logger = failingLogger;
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");

    Throwable thrown = catchThrowable(coordinator::start);

    assertBothQueuesCleaned(coordinator, false);
    assertThat(thrown).isSameAs(startupFailure);
    assertThat(mockingDetails(failingLogger).getInvocations())
        .anyMatch(invocation -> invocation.getMethod().getName().equals("error"));
  }

  @Test(timeout = 5000)
  public void fatalStopMetricFailureEscapesAfterAllRuntimesAreCleaned() {
    InternalError fatal = new InternalError("fatal stop metric provider");
    registry
        .config()
        .onMeterAdded(
            meter -> {
              if (meter.getId().getName().equals("asyncJobQueue.runtime.stop.failed")) {
                throw fatal;
              }
            });
    doThrow(new IllegalStateException("first executor shutdown")).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");
    coordinator.start();
    assertStarted(coordinator);

    Throwable thrown = catchThrowable(coordinator::stop);

    assertBothQueuesCleaned(coordinator, true);
    assertThat(thrown).isSameAs(fatal);
  }

  @Test(timeout = 5000)
  public void fatalStopLoggingFailureEscapesAfterAllRuntimesAreCleaned() {
    InternalError fatal = new InternalError("fatal stop logging");
    Logger failingLogger = failStopLogging(fatal);
    doThrow(new IllegalStateException("first executor shutdown")).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");
    coordinator.start();
    assertStarted(coordinator);

    Throwable thrown = catchThrowable(coordinator::stop);

    assertBothQueuesCleaned(coordinator, true);
    assertThat(thrown).isSameAs(fatal);
    verify(failingLogger).warn(anyString(), any(), any());
    assertStopFailureRecorded("first");
  }

  private void assertOrdinaryExplicitStop(Throwable stopFailure) {
    doThrow(stopFailure).when(firstExecutor).shutdown();
    AsyncJobQueueCoordinator coordinator = coordinator("first", "second");
    coordinator.start();
    assertStarted(coordinator);

    Throwable thrown = catchThrowable(coordinator::stop);

    assertBothQueuesCleaned(coordinator, true);
    assertThat(thrown).isNull();
    assertStopFailureRecorded("first");
    coordinator.stop();
    verify(firstExecutor).shutdown();
    verify(secondExecutor).shutdown();
    assertStopFailureRecorded("first");
  }

  private void failGaugeRegistration(String queueName, InternalError failure) {
    registry
        .config()
        .onMeterAdded(
            meter -> {
              if (meter.getId().getName().equals("asyncJobQueue.executor.active")
                  && queueName.equals(meter.getId().getTag("queueName"))) {
                throw failure;
              }
            });
  }

  private void failFirstConstructor(IllegalStateException failure) {
    AsyncJobQueueProperties.QueueSettings settings =
        spy(new AsyncJobQueueProperties.QueueSettings());
    properties.getQueues().put("first", settings);
    // Arm the getter after coordinator validation so the production runtime constructor fails.
    doAnswer(
            ignored -> {
              doThrow(failure).when(settings).getMaxConcurrency();
              return null;
            })
        .when(firstHeartbeat)
        .initialize();
  }

  private void failSecondInitialPoll(Throwable failure) {
    when(scheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(ignored -> firstPoll)
        .thenThrow(failure);
  }

  private void collideStopFailureCounter() {
    registry.timer("asyncJobQueue.runtime.stop.failed", "queueName", "first");
  }

  private Logger failStopLogging(Throwable failure) {
    Logger failingLogger = mock(Logger.class);
    doThrow(failure).when(failingLogger).warn(anyString(), any(), any());
    doThrow(failure).when(failingLogger).warn(anyString(), any(Throwable.class));
    AsyncJobQueueCoordinator.logger = failingLogger;
    return failingLogger;
  }

  private AsyncJobQueueCoordinator coordinator(String... queueNames) {
    List<AsyncJobHandler> handlers = new ArrayList<>();
    handlers.add(handler("disabled"));
    for (String queueName : queueNames) {
      handlers.add(handler(queueName));
    }
    return new AsyncJobQueueCoordinator(store, properties, handlers, scheduler, registry) {
      @Override
      ThreadPoolTaskExecutor queueExecutor(
          String queueName, AsyncJobQueueProperties.QueueSettings queueSettings) {
        return switch (queueName) {
          case "first" -> firstExecutor;
          case "second" -> secondExecutor;
          default -> throw new AssertionError("Unexpected executor allocation: " + queueName);
        };
      }

      @Override
      ThreadPoolTaskScheduler queueHeartbeatScheduler(
          String queueName, AsyncJobQueueProperties.QueueSettings queueSettings) {
        return switch (queueName) {
          case "first" -> firstHeartbeat;
          case "second" -> secondHeartbeat;
          default -> throw new AssertionError("Unexpected heartbeat allocation: " + queueName);
        };
      }
    };
  }

  private AsyncJobHandler handler(String queueName) {
    AsyncJobHandler handler = mock(AsyncJobHandler.class);
    when(handler.queueName()).thenReturn(queueName);
    return handler;
  }

  private void assertStarted(AsyncJobQueueCoordinator coordinator) {
    assertThat(coordinator.isRunning()).isTrue();
    assertThat((Map<?, ?>) ReflectionTestUtils.getField(coordinator, "runtimesByQueueName"))
        .hasSize(2);
    assertThat((Set<?>) ReflectionTestUtils.getField(coordinator, "disabledConsumerQueues"))
        .hasSize(1);
    assertThat(registry.get("asyncJobQueue.poll.started").tag("queueName", "first").gauge().value())
        .isEqualTo(1);
    assertThat(
            registry.get("asyncJobQueue.poll.started").tag("queueName", "second").gauge().value())
        .isEqualTo(1);
  }

  private void assertFirstConstructionCleaned(AsyncJobQueueCoordinator coordinator) {
    verify(firstHeartbeat).initialize();
    verify(firstHeartbeat).shutdown();
    verify(firstExecutor).shutdown();
    verifyNoInteractions(secondExecutor, secondHeartbeat, scheduler, firstPoll, secondPoll, store);
    assertStoppedAndEmpty(coordinator);
  }

  private void assertBothQueuesCleaned(
      AsyncJobQueueCoordinator coordinator, boolean secondStarted) {
    verify(firstHeartbeat).initialize();
    verify(secondHeartbeat).initialize();
    verify(firstExecutor).shutdown();
    verify(secondExecutor).shutdown();
    verify(firstHeartbeat).shutdown();
    verify(secondHeartbeat).shutdown();
    verify(firstPoll).cancel(false);
    if (secondStarted) {
      verify(secondPoll).cancel(false);
    } else {
      verifyNoInteractions(secondPoll);
    }
    verifyNoInteractions(store);
    assertStoppedAndEmpty(coordinator);
  }

  private void assertStoppedAndEmpty(AsyncJobQueueCoordinator coordinator) {
    assertThat(coordinator.isRunning()).isFalse();
    assertThat((Map<?, ?>) ReflectionTestUtils.getField(coordinator, "runtimesByQueueName"))
        .isEmpty();
    assertThat((Set<?>) ReflectionTestUtils.getField(coordinator, "disabledConsumerQueues"))
        .isEmpty();
    assertThat(registry.getMeters())
        .noneMatch(meter -> meter.getId().getType() == Meter.Type.GAUGE);
  }

  private void assertStopFailureRecorded(String queueName) {
    assertThat(
            registry
                .get("asyncJobQueue.runtime.stop.failed")
                .tag("queueName", queueName)
                .counter()
                .count())
        .isEqualTo(1);
  }
}
