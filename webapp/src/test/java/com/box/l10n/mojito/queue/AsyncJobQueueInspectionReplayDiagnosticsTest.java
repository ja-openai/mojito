package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;

@RunWith(Parameterized.class)
public class AsyncJobQueueInspectionReplayDiagnosticsTest {

  enum Fault {
    COUNTER_REGISTRATION,
    COUNTER_INCREMENT,
    LOGGING,
    COUNTER_AND_LOGGING
  }

  @Parameterized.Parameters(name = "{0}")
  public static List<Fault> faults() {
    return Arrays.asList(Fault.values());
  }

  private static final String QUEUE = "assetlocalize";
  private final Fault fault;
  private final Logger originalLogger = AsyncJobQueueInspectionService.logger;
  private final InMemoryAsyncJobStore persisted = new InMemoryAsyncJobStore();
  private final AsyncJobStore store = mock(AsyncJobStore.class, delegatesTo(persisted));
  private final AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
  private final AsyncJobQueueWakeupNotifier notifier = mock(AsyncJobQueueWakeupNotifier.class);
  private final MeterRegistry registry = mock(MeterRegistry.class);
  private AsyncJobQueueInspectionService service;
  private AsyncJobId id;

  public AsyncJobQueueInspectionReplayDiagnosticsTest(Fault fault) {
    this.fault = fault;
  }

  @Before
  public void setup() {
    id = persisted.enqueueNow(QUEUE, "original");
    AsyncJobRecord job = persisted.claimNextJobs(QUEUE, 1, "worker", Duration.ofMinutes(1)).get(0);
    assertThat(persisted.markFailed(QUEUE, id, "worker", job.leaseToken(), null, "original error"))
        .isTrue();
    Counter counter = mock(Counter.class);
    when(registry.counter(anyString(), any(String[].class)))
        .thenAnswer(
            invocation -> {
              if (fault == Fault.COUNTER_REGISTRATION || fault == Fault.COUNTER_AND_LOGGING) {
                throw new IllegalStateException("counter registration unavailable");
              }
              return counter;
            });
    if (fault == Fault.COUNTER_INCREMENT) {
      doThrow(new AssertionError("counter increment unavailable")).when(counter).increment();
    }
    if (fault == Fault.LOGGING || fault == Fault.COUNTER_AND_LOGGING) {
      AsyncJobQueueInspectionService.logger =
          mock(
              Logger.class,
              invocation -> {
                if (List.of("info", "warn").contains(invocation.getMethod().getName())) {
                  throw new AssertionError("logging unavailable");
                }
                return RETURNS_DEFAULTS.answer(invocation);
              });
    }
    service = new AsyncJobQueueInspectionService(store, coordinator, notifier, registry);
  }

  @After
  public void tearDown() {
    AsyncJobQueueInspectionService.logger = originalLogger;
  }

  @Test
  public void acknowledgedReplayReturnsDespiteBrokenDiagnostics() {
    assertSuccessfulReplay();
  }

  @Test
  public void brokenLocalWakeupDiagnosticsDoNotPreventRemoteHintOrReturn() {
    doThrow(new IllegalStateException("scheduler unavailable"))
        .when(coordinator)
        .triggerPollNow(QUEUE);
    assertSuccessfulReplay();
  }

  @Test
  public void brokenRemoteWakeupDiagnosticsDoNotFailReplay() {
    doThrow(new IllegalStateException("notifier unavailable"))
        .when(notifier)
        .notifyJobAvailable(QUEUE, id);
    assertSuccessfulReplay();
  }

  @Test
  public void originalStoreFailureSurvivesBrokenDiagnosticsWithoutWakeups() {
    RuntimeException failure = new IllegalStateException("store outcome unknown");
    List<AsyncJobRecord> before = persisted.getByIds(List.of(id));
    doThrow(failure).when(store).requeueFailedNow(QUEUE, id, "replacement");
    assertThatThrownBy(() -> service.requeueFailedJob(QUEUE, id.value(), "replacement"))
        .isSameAs(failure);
    verify(store).requeueFailedNow(QUEUE, id, "replacement");
    verifyNoMoreInteractions(store);
    verifyNoInteractions(coordinator, notifier);
    assertThat(persisted.getByIds(List.of(id))).containsExactlyElementsOf(before);
  }

  @Test
  public void originalNonfatalStoreErrorSurvivesBrokenDiagnostics() {
    Error failure = new AssertionError("store invariant");
    List<AsyncJobRecord> before = persisted.getByIds(List.of(id));
    doThrow(failure).when(store).requeueFailedNow(QUEUE, id, "replacement");
    assertThatThrownBy(() -> service.requeueFailedJob(QUEUE, id.value(), "replacement"))
        .isSameAs(failure);
    verify(store).requeueFailedNow(QUEUE, id, "replacement");
    verifyNoMoreInteractions(store);
    verifyNoInteractions(coordinator, notifier);
    assertThat(persisted.getByIds(List.of(id))).containsExactlyElementsOf(before);
  }

  @Test
  public void lostStoreAcknowledgementRemainsAnErrorWithoutRepeatingCommittedReplay() {
    RuntimeException failure = new IllegalStateException("replay acknowledgement lost");
    doAnswer(
            invocation -> {
              assertThat(persisted.requeueFailedNow(QUEUE, id, "replacement")).isTrue();
              throw failure;
            })
        .when(store)
        .requeueFailedNow(QUEUE, id, "replacement");
    assertThatThrownBy(() -> service.requeueFailedJob(QUEUE, id.value(), "replacement"))
        .isSameAs(failure);
    verify(store).requeueFailedNow(QUEUE, id, "replacement");
    verifyNoMoreInteractions(store);
    verifyNoInteractions(coordinator, notifier);
    AsyncJobRecord replayed = persisted.getByIds(List.of(id)).get(0);
    assertThat(replayed.status()).isEqualTo(AsyncJobStatus.QUEUED);
    assertThat(replayed.attemptCount()).isZero();
    assertThat(replayed.jobData()).isEqualTo("replacement");
  }

  @Test
  public void genuinePostReplayLookupFailureStillEscapesWithoutRepeatingMutation() {
    RuntimeException failure = new IllegalStateException("lookup unavailable after replay");
    doThrow(failure).when(store).getByIds(List.of(id));
    assertThatThrownBy(() -> service.requeueFailedJob(QUEUE, id.value(), "replacement"))
        .isSameAs(failure);
    verifyReplayAndWakeups();
    assertThat(persisted.getByIds(List.of(id)).get(0).status()).isEqualTo(AsyncJobStatus.QUEUED);
  }

  @Test
  public void invalidPayloadKeepsItsValidationErrorWithoutAnyMutation() {
    List<AsyncJobRecord> before = persisted.getByIds(List.of(id));
    assertThatThrownBy(() -> service.requeueFailedJob(QUEUE, id.value(), "bad\0payload"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("jobData must not contain NUL");
    verifyNoInteractions(store, coordinator, notifier);
    assertThat(persisted.getByIds(List.of(id))).containsExactlyElementsOf(before);
  }

  @Test
  public void otherQueueReplayStillReportsNotFoundWithoutChangingItsRow() {
    List<AsyncJobRecord> before = persisted.getByIds(List.of(id));
    assertThatThrownBy(() -> service.requeueFailedJob("other", id.value(), null))
        .isInstanceOf(AsyncJobQueueInspectionService.AsyncJobNotFoundException.class);
    verify(store).requeueFailedNow("other", id, null);
    verify(store).getByIds(List.of(id));
    verifyNoMoreInteractions(store);
    verifyNoInteractions(coordinator, notifier);
    assertThat(persisted.getByIds(List.of(id))).containsExactlyElementsOf(before);
  }

  @Test
  public void nonFailedReplayStillReportsConflictWithoutChangingItsRow() {
    assertThat(persisted.requeueFailedNow(QUEUE, id, null)).isTrue();
    List<AsyncJobRecord> before = persisted.getByIds(List.of(id));
    assertThatThrownBy(() -> service.requeueFailedJob(QUEUE, id.value(), "replacement"))
        .isInstanceOf(AsyncJobQueueInspectionService.AsyncJobNotFailedException.class);
    verify(store).requeueFailedNow(QUEUE, id, "replacement");
    verify(store).getByIds(List.of(id));
    verifyNoMoreInteractions(store);
    verifyNoInteractions(coordinator, notifier);
    assertThat(persisted.getByIds(List.of(id))).containsExactlyElementsOf(before);
  }

  @Test
  public void fatalDiagnosticsEscapeWithOriginalIdentityAfterAcknowledgedReplay() {
    Error fatal =
        fault == Fault.COUNTER_AND_LOGGING
            ? new ThreadDeath()
            : new InternalError("fatal diagnostic");
    RuntimeException wrapper = new IllegalStateException("diagnostic wrapper");
    if (fault == Fault.COUNTER_REGISTRATION || fault == Fault.LOGGING) {
      wrapper.initCause(fatal);
    } else {
      wrapper.addSuppressed(fatal);
    }
    Counter counter = mock(Counter.class);
    if (fault != Fault.COUNTER_AND_LOGGING) {
      doReturn(counter).when(registry).counter(anyString(), any(String[].class));
    }
    if (fault == Fault.COUNTER_REGISTRATION) {
      doThrow(wrapper).when(registry).counter(anyString(), any(String[].class));
    } else if (fault == Fault.COUNTER_INCREMENT) {
      doThrow(wrapper).when(counter).increment();
    } else {
      AsyncJobQueueInspectionService.logger =
          mock(
              Logger.class,
              invocation -> {
                if (List.of("info", "warn").contains(invocation.getMethod().getName())) {
                  throw wrapper;
                }
                return RETURNS_DEFAULTS.answer(invocation);
              });
    }
    assertThatThrownBy(() -> service.requeueFailedJob(QUEUE, id.value(), "replacement"))
        .isSameAs(fatal);
    verifyReplayAndWakeups();
    assertThat(persisted.getByIds(List.of(id)).get(0).status()).isEqualTo(AsyncJobStatus.QUEUED);
  }

  private void assertSuccessfulReplay() {
    AsyncJobQueueInspectionService.AsyncJobDetails result =
        service.requeueFailedJob(QUEUE, id.value(), "replacement");
    assertThat(result.id()).isEqualTo(id.value());
    assertThat(result.status()).isEqualTo("queued");
    assertThat(result.jobData()).isEqualTo("replacement");
    assertThat(result.lastError()).isEqualTo("original error");
    assertThat(result.attemptCount()).isZero();
    verifyReplayAndWakeups();
    AsyncJobRecord claimed =
        persisted.claimNextJobs(QUEUE, 1, "replacement-worker", Duration.ofMinutes(1)).get(0);
    assertThat(claimed.id()).isEqualTo(id);
    assertThat(claimed.attemptCount()).isEqualTo(1);
  }

  private void verifyReplayAndWakeups() {
    verify(store).requeueFailedNow(QUEUE, id, "replacement");
    verify(store).getByIds(List.of(id));
    verifyNoMoreInteractions(store);
    verify(coordinator).triggerPollNow(QUEUE);
    verify(notifier).notifyJobAvailable(QUEUE, id);
    verifyNoMoreInteractions(coordinator, notifier);
  }
}
