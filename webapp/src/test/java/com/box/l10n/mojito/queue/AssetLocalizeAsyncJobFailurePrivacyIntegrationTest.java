package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.admin.AsyncJobQueueAdminWS;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobHandler;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobOutputStorage;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobPayload;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobSubmissionService;
import com.box.l10n.mojito.service.tm.GenerateLocalizedAssetJob;
import com.box.l10n.mojito.service.tm.LocalizedAssetGenerationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.SoftAssertions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

/**
 * Actual queue runtime, HSQL task persistence and translator HTTP serialization, with mocked blob
 * IO/generation and an in-memory queue. This is not proof of real JDBC queue behavior.
 *
 * <p>Privacy is scoped to newly completed unexpected failures, including the direct permanent queue
 * signal. Ordinary checked business errors retain their public contract; historical task errors and
 * global sanitization are outside this test. Finish acknowledgement loss is injected after the real
 * task service returns, not inside JDBC commit or a network transport.
 */
public class AssetLocalizeAsyncJobFailurePrivacyIntegrationTest extends ServiceTestBase {

  private static final String QUEUE = AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME;
  private static final String PRIVATE_MARKER = "synthetic-private-worker-diagnostic";
  private static final String PRIVATE_SOURCE = "synthetic-private-worker-source";
  private static final String PRIVATE_SUPPRESSED = "synthetic-private-worker-suppressed";
  private static final String FINISH_ACK_LOSS = "synthetic task-finish acknowledgement loss";

  @Autowired PollableTaskService pollableTaskService;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired WebApplicationContext webApplicationContext;

  private final InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final PollableTaskBlobStorage inputs = mock(PollableTaskBlobStorage.class);
  private final LocalizedAssetGenerationService generation =
      mock(LocalizedAssetGenerationService.class);
  private final AssetLocalizeAsyncJobOutputStorage output =
      mock(AssetLocalizeAsyncJobOutputStorage.class);
  private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
  private final Semaphore workerReturned = new Semaphore(0);

  private PollableTask task;
  private MockMvc mvc;
  private AsyncJobQueueRuntime runtime;

  @Before
  public void setUpFailure() throws Exception {
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("assetlocalize-failure-privacy-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationMillis(5_000);
    executor.setTaskDecorator(
        runnable ->
            () -> {
              try {
                runnable.run();
              } finally {
                // Observe completion after the runtime's callback and in-flight cleanup.
                workerReturned.release();
              }
            });
    executor.initialize();
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    try (var connection = jdbc.getDataSource().getConnection()) {
      assertThat(connection.getMetaData().getDatabaseProductName()).containsIgnoringCase("HSQL");
    }
    task =
        pollableTaskService.createPollableTask(
            null, GenerateLocalizedAssetJob.class.getCanonicalName(), "failure privacy", 0, 3600);
    when(inputs.getInputBytes(task.getId())).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
    mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
  }

  @After
  public void closeRuntime() throws InterruptedException {
    ThreadPoolExecutor workerPool = executor.getThreadPoolExecutor();
    try {
      if (runtime != null) {
        runtime.stop();
      } else {
        executor.shutdown();
      }
    } finally {
      try {
        if (!workerPool.isTerminated()) {
          workerPool.shutdownNow();
        }
        assertThat(workerPool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        metrics.close();
      }
    }
  }

  @Test
  public void exhaustedRuntimeFailureKeepsDiagnosticsOutOfTaskAndTranslatorResponses()
      throws Exception {
    assertFailure(runtimeFailure(), false);
  }

  @Test
  public void directPermanentFailureKeepsFirstAttemptDiagnosticsOutOfTaskAndTranslatorResponses()
      throws Exception {
    assertFailure(
        new AsyncJobPermanentFailureException("synthetic permanent failure: " + PRIVATE_MARKER),
        false);
  }

  @Test
  public void lostFinishAcknowledgementKeepsCommittedTaskSafeWithoutCallbackRetryOrRegeneration()
      throws Exception {
    assertFailure(runtimeFailure(), true);
  }

  private RuntimeException runtimeFailure() {
    RuntimeException failure =
        new IllegalStateException(
            "synthetic generation failure: " + PRIVATE_MARKER,
            new SQLException("synthetic SQL diagnostic: source=\"" + PRIVATE_SOURCE + "\""));
    failure.addSuppressed(new IllegalArgumentException(PRIVATE_SUPPRESSED));
    return failure;
  }

  private void assertFailure(Exception failure, boolean loseFinishAcknowledgement)
      throws Exception {
    String jobData =
        objectMapper.writeValueAsStringUnchecked(new AssetLocalizeAsyncJobPayload(task.getId()));
    AsyncJobId id = store.enqueueNow(QUEUE, jobData);
    AtomicReference<AsyncJobRecord> terminalAtFinish = new AtomicReference<>();
    PollableTaskService completion =
        mock(PollableTaskService.class, delegatesTo(pollableTaskService));
    doAnswer(
            invocation -> {
              terminalAtFinish.set(store.getByIds(List.of(id)).getFirst());
              PollableTask finished =
                  pollableTaskService.finishTask(
                      invocation.getArgument(0),
                      invocation.getArgument(1),
                      invocation.getArgument(2),
                      invocation.getArgument(3));
              if (loseFinishAcknowledgement) {
                // The HSQL REQUIRES_NEW task update has returned before this service fault.
                throw new IllegalStateException(FINISH_ACK_LOSS);
              }
              return finished;
            })
        .when(completion)
        .finishTask(eq(task.getId()), isNull(), any(ExceptionHolder.class), isNull());
    // Answer injects the checked permanent signal directly, without a runtime wrapper.
    doAnswer(
            invocation -> {
              throw failure;
            })
        .when(generation)
        .generate(any(LocalizedAssetBody.class));
    AssetLocalizeAsyncJobHandler handler =
        spy(
            new AssetLocalizeAsyncJobHandler(
                completion,
                inputs,
                new PollableTaskExceptionUtils(),
                generation,
                objectMapper,
                metrics,
                output));
    runtime =
        new AsyncJobQueueRuntime(
            QUEUE,
            store,
            settings(),
            handler,
            mock(TaskScheduler.class),
            executor,
            metrics,
            "failure-privacy-worker");

    pollAttempt();
    int attempts = failure instanceof AsyncJobPermanentFailureException ? 1 : 2;
    if (attempts == 2) {
      AsyncJobRecord retry = store.getByIds(List.of(id)).getFirst();
      assertThat(retry.status()).isEqualTo(AsyncJobStatus.QUEUED);
      assertThat(retry.attemptCount()).isEqualTo(1);
      assertThat(retry.lastError())
          .contains(failure.toString(), PRIVATE_MARKER, PRIVATE_SOURCE, PRIVATE_SUPPRESSED);
      assertThat(pollableTaskService.getPollableTask(task.getId()).getFinishedDate()).isNull();
      verify(handler, never()).onJobFailedPermanently(any(), any(), anyString());
      verify(completion, never())
          .finishTask(eq(task.getId()), isNull(), any(ExceptionHolder.class), isNull());
      pollAttempt();
      assertThat(metrics.get("asyncJobQueue.retried").counter().count()).isEqualTo(1);
    } else {
      assertThat(metrics.find("asyncJobQueue.retried").counter()).isNull();
    }

    AsyncJobRecord failed = store.getByIds(List.of(id)).getFirst();
    assertThat(failed.status()).isEqualTo(AsyncJobStatus.FAILED);
    assertThat(failed.attemptCount()).isEqualTo(attempts);
    assertThat(failed.jobData()).isEqualTo(jobData);
    assertThat(failed.workerId()).isNull();
    assertThat(failed.leaseToken()).isNull();
    assertThat(failed.leaseUntil()).isNull();
    assertThat(terminalAtFinish.get()).isEqualTo(failed);
    assertThat(failed.lastError()).contains(failure.toString(), PRIVATE_MARKER);
    if (failure instanceof RuntimeException) {
      assertThat(failed.lastError()).contains(PRIVATE_SOURCE, PRIVATE_SUPPRESSED);
    }
    assertThat(metrics.get("asyncJobQueue.failed").counter().count()).isEqualTo(1);
    AsyncJobQueueAdminWS admin =
        new AsyncJobQueueAdminWS(
            new AsyncJobQueueInspectionService(
                store, mock(AsyncJobQueueCoordinator.class), metrics));
    assertThat(admin.getJob(QUEUE, id.value()).lastError())
        .isEqualTo(failed.lastError())
        .contains(PRIVATE_MARKER);

    if (loseFinishAcknowledgement) {
      assertThat(
              metrics
                  .get("asyncJobQueue.handler.completion.failed")
                  .tag("callback", "failed")
                  .counter()
                  .count())
          .isEqualTo(1);
    } else {
      assertThat(metrics.find("asyncJobQueue.handler.completion.failed").counter()).isNull();
    }
    var terminalTask = jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", task.getId());
    // Additional real polls cannot retry terminal work, including a failed finish callback.
    assertThat(runtime.pollOnce().claimedCount()).isZero();
    assertThat(runtime.pollOnce().claimedCount()).isZero();
    assertThat(runtime.inFlightCount()).isZero();
    assertThat(store.getByIds(List.of(id))).containsExactly(failed);
    assertThat(jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", task.getId()))
        .isEqualTo(terminalTask);
    verify(handler, times(attempts)).process(any(AsyncJobRecord.class));
    ArgumentCaptor<AsyncJobRecord> callback = ArgumentCaptor.forClass(AsyncJobRecord.class);
    verify(handler, times(1))
        .onJobFailedPermanently(callback.capture(), same(failure), eq(failed.lastError()));
    assertThat(callback.getValue().id()).isEqualTo(id);
    assertThat(callback.getValue().status()).isEqualTo(AsyncJobStatus.FAILED);
    assertThat(callback.getValue().attemptCount()).isEqualTo(attempts);
    verify(inputs, times(attempts)).getInputBytes(task.getId());
    verify(generation, times(attempts)).generate(any(LocalizedAssetBody.class));
    verifyNoInteractions(output);
    ArgumentCaptor<ExceptionHolder> holder = ArgumentCaptor.forClass(ExceptionHolder.class);
    verify(completion, times(1)).finishTask(eq(task.getId()), isNull(), holder.capture(), isNull());

    assertTaskPrivacy(id, holder.getValue());
  }

  private void assertTaskPrivacy(AsyncJobId id, ExceptionHolder holder) throws Exception {
    String taskResponse = response("/api/pollableTasks/" + task.getId());
    String inspectionResponse = response("/api/pollableTasks/" + task.getId() + "/inspection");
    PollableTask finished = pollableTaskService.getPollableTask(task.getId());
    String persistedStack =
        jdbc.queryForObject(
            "SELECT error_stacks FROM pollable_task WHERE id = ?", String.class, task.getId());

    SoftAssertions privacy = new SoftAssertions();
    String[] privateDiagnostics = {
      PRIVATE_MARKER,
      PRIVATE_SOURCE,
      PRIVATE_SUPPRESSED,
      FINISH_ACK_LOSS,
      "java.sql.SQLException",
      "Caused by:",
      "Suppressed:"
    };
    // Check both translator surfaces before the generic message assertion.
    privacy
        .assertThat(taskResponse)
        .as("translator GET /api/pollableTasks/%s", task.getId())
        .doesNotContain(privateDiagnostics);
    privacy
        .assertThat(inspectionResponse)
        .as("translator GET /api/pollableTasks/%s/inspection", task.getId())
        .doesNotContain(privateDiagnostics);
    privacy
        .assertThat(persistedStack)
        .as("committed pollable_task.error_stacks")
        .doesNotContain(privateDiagnostics);

    String safeMessage = "Asset localize async job failed permanently: " + id.value();
    privacy.assertThat(finished.getFinishedDate()).isNotNull();
    privacy.assertThat(persistedStack).isEqualTo(finished.getErrorStack()).contains(safeMessage);
    privacy.assertThat(holder.isExpected()).isFalse();
    privacy.assertThat(holder.getException()).hasMessage(safeMessage).hasNoCause();
    privacy.assertThat(holder.getException().getSuppressed()).isEmpty();
    var expectedError =
        objectMapper.readTreeUnchecked(
            "{\"expected\":false,\"message\":\"An unexpected error happened, task="
                + task.getId()
                + "\",\"type\":\"unexpected\"}");
    privacy
        .assertThat(objectMapper.readTreeUnchecked(finished.getErrorMessage()))
        .as("persisted unexpected classification")
        .isEqualTo(expectedError);
    var taskJson = objectMapper.readTreeUnchecked(taskResponse);
    privacy.assertThat(taskJson.path("errorMessage")).isEqualTo(expectedError);
    privacy.assertThat(taskJson.path("errorStack").asText()).isEqualTo(persistedStack);
    var inspection = objectMapper.readTreeUnchecked(inspectionResponse);
    privacy.assertThat(inspection.path("status").asText()).isEqualTo("FAILED");
    privacy.assertThat(inspection.path("error").path("expected").asBoolean(true)).isFalse();
    privacy
        .assertThat(inspection.path("error").path("reportedType").asText())
        .isEqualTo("unexpected");
    privacy
        .assertThat(inspection.path("error").path("exceptionMessage").asText())
        .isEqualTo(safeMessage);
    privacy
        .assertThat(inspection.path("error").path("stackTrace").asText())
        .isEqualTo(persistedStack);
    privacy.assertAll();
  }

  private void pollAttempt() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (runtime.pollOnce().claimedCount() == 0) {
      if (System.nanoTime() >= deadline) {
        throw new AssertionError("Timed out waiting for the next failure-privacy attempt");
      }
      // Only retry readiness uses polling; callback completion uses the worker signal below.
      TimeUnit.MILLISECONDS.sleep(1);
    }
    assertThat(workerReturned.tryAcquire(10, TimeUnit.SECONDS))
        .as("runtime worker returned after callback and cleanup")
        .isTrue();
    assertThat(runtime.inFlightCount()).isZero();
  }

  private String response(String path) throws Exception {
    return mvc.perform(get(path).with(user("failure-reader").roles("TRANSLATOR")))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static AsyncJobQueueProperties.QueueSettings settings() {
    AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setMaxAttempts(2);
    settings.setMaxConcurrency(1);
    settings.setClaimBatchSize(1);
    settings.setPollIntervalMs(1);
    settings.setRetryJitterPercent(0);
    settings.setHeartbeatIntervalMs(0);
    settings.setLeaseDurationMs(30_000);
    settings.setShutdownAwaitTerminationMs(5_000);
    return settings;
  }
}
