package com.box.l10n.mojito.queue;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.POLLABLE_TASK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.MBlob;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.okapi.InheritanceMode;
import com.box.l10n.mojito.okapi.Status;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.queue.CommitFaultDataSource.CommitFault;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.asset.AssetService;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.MBlobRepository;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.pollableTask.PollableTaskTimeoutException;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobHandler;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobOutputStorage;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobPayload;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizePollableTaskRepairException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobSubmissionService;
import com.box.l10n.mojito.service.tm.GenerateLocalizedAssetJob;
import com.box.l10n.mojito.service.tm.LocalizedAssetGenerationService;
import com.box.l10n.mojito.service.tm.TMService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real generation and blob storage with controlled queue polling. JDBC variants use separate
 * disposable queue databases; they do not certify whole-application MySQL/PostgreSQL ORM
 * compatibility, process crashes, admission atomicity or competing-attempt lineage fencing. Enqueue
 * acknowledgement loss is simulated after the real store returns. Completion acknowledgement loss
 * is injected inside JDBC commit, with actual commit/rollback and explicit lease expiry. Queue and
 * application databases remain separate, so neither proves atomic application admission. Canonical
 * publication and task-finish faults are injected before invocation or after the real service
 * returns, not inside a storage or JDBC commit; they exercise terminal repair, not network failure.
 * Cleanup runs explicitly against an isolated application database, never a scheduled sweep.
 */
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:hsqldb:mem:queue_output_retry;DB_CLOSE_DELAY=-1",
      "l10n.blob-storage.database.cleanup-enabled=false",
      "l10n.org.quartz.scheduler.enabled=false"
    })
public class AssetLocalizeAsyncJobOutputRetryIntegrationTest extends ServiceTestBase {

  private static final String QUEUE = AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME;
  private static final String STORAGE_FAILURE = "injected attempt-output storage failure";

  @Autowired RepositoryService repositoryService;
  @Autowired RepositoryLocaleRepository repositoryLocaleRepository;
  @Autowired AssetService assetService;
  @Autowired TMService tmService;
  @Autowired LocalizedAssetGenerationService generationService;
  @Autowired PollableTaskService pollableTaskService;
  @Autowired PollableTaskBlobStorage pollableTaskBlobStorage;
  @Autowired PollableTaskExceptionUtils pollableTaskExceptionUtils;
  @Autowired StructuredBlobStorage structuredBlobStorage;
  @Autowired DatabaseBlobStorage databaseBlobStorage;
  @Autowired MBlobRepository mBlobRepository;
  @Autowired ApplicationContext applicationContext;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;

  @Test
  public void omittedTagRetriesSameJobAfterOutputWriteFails() throws Exception {
    assertRetry(new InMemoryAsyncJobStore(), false, false, null);
  }

  @Test
  public void omittedTagRetriesChangedTranslationAfterOutputWriteAcknowledgementIsLost()
      throws Exception {
    assertRetry(new InMemoryAsyncJobStore(), true, true, null);
  }

  @Test
  public void explicitTagRetriesChangedTranslationAfterOutputWriteFails() throws Exception {
    assertRetry(new InMemoryAsyncJobStore(), false, true, "fr-FR");
  }

  @Test
  public void previouslyQueuedPullRunFailsWithoutChangingBusinessState() throws Exception {
    assertTrackedJobRejected(new InMemoryAsyncJobStore());
  }

  @Test
  public void rawFailedRowReplayPreservesFinishedTaskWithoutRegenerating() throws Exception {
    assertRawFailedRowReplay(new InMemoryAsyncJobStore());
  }

  @Test
  public void timeoutBeforeDoneCallbackKeepsWinnerPrivateThroughRetryAndRepair() throws Exception {
    assertTimeoutBeforeDoneCallback(new InMemoryAsyncJobStore());
  }

  @Test
  public void lostEnqueueAcknowledgementPreservesWorkerCompletionBeforeSubmissionUnwinds()
      throws Exception {
    assertLostEnqueueAcknowledgement(new InMemoryAsyncJobStore(), true);
  }

  @Test
  public void lostEnqueueAcknowledgementAllowsWorkerCompletionAfterSubmissionUnwinds()
      throws Exception {
    assertLostEnqueueAcknowledgement(new InMemoryAsyncJobStore(), false);
  }

  @Test
  public void repairRecoversFailureBeforeCanonicalOutputWrite() throws Exception {
    assertPublicationFault(new InMemoryAsyncJobStore(), PublicationFault.BEFORE_CANONICAL_WRITE);
  }

  @Test
  public void repairRecoversLostCanonicalOutputWriteAcknowledgement() throws Exception {
    assertPublicationFault(new InMemoryAsyncJobStore(), PublicationFault.AFTER_CANONICAL_WRITE);
  }

  @Test
  public void repairRecoversFailureBeforePollableTaskFinish() throws Exception {
    assertPublicationFault(new InMemoryAsyncJobStore(), PublicationFault.BEFORE_TASK_FINISH);
  }

  @Test
  public void repairRecognizesCommittedTaskAfterFinishAcknowledgementIsLost() throws Exception {
    assertPublicationFault(new InMemoryAsyncJobStore(), PublicationFault.AFTER_TASK_FINISH);
  }

  @Test
  public void repairRejectsMalformedRetainedWinnerUntilOriginalBlobIsRestored() throws Exception {
    assertPublicationFault(
        new InMemoryAsyncJobStore(),
        PublicationFault.BEFORE_TASK_FINISH,
        WinnerCondition.MALFORMED);
  }

  @Test
  public void cleanupOfUnpublishedWinnerBlocksRepairUntilOriginalBytesAreRestored()
      throws Exception {
    assertPublicationFault(
        new InMemoryAsyncJobStore(),
        PublicationFault.BEFORE_CANONICAL_WRITE,
        WinnerCondition.EXPIRED);
  }

  @Test
  public void cleanupOfWinnerBlocksRepairEvenWhenCanonicalOutputSurvives() throws Exception {
    assertPublicationFault(
        new InMemoryAsyncJobStore(), PublicationFault.BEFORE_TASK_FINISH, WinnerCondition.EXPIRED);
  }

  @Test
  public void mysqlQueueRetriesOutputAndRejectsTrackedWork() throws Exception {
    Assume.assumeTrue(Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
    try (MySQLContainer<?> container =
        new MySQLContainer<>("mysql:8.4")
            .withConnectTimeoutSeconds(10)
            .withUrlParam("connectTimeout", "5000")
            .withUrlParam("socketTimeout", "30000")) {
      assertDatabaseRetries(
          container, AsyncJobQueueJdbcDialect.MYSQL, "db/migration/V113__Async_Job_Queue.sql");
    }
  }

  @Test
  public void postgresqlQueueRetriesOutputAndRejectsTrackedWork() throws Exception {
    Assume.assumeTrue(Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
    try (PostgreSQLContainer<?> container =
        new PostgreSQLContainer<>("postgres:16")
            .withConnectTimeoutSeconds(10)
            .withUrlParam("connectTimeout", "5")
            .withUrlParam("socketTimeout", "30")) {
      assertDatabaseRetries(
          container,
          AsyncJobQueueJdbcDialect.POSTGRESQL,
          "db/postgresql/migration/V113__Async_Job_Queue.sql");
    }
  }

  private void assertDatabaseRetries(
      JdbcDatabaseContainer<?> container, AsyncJobQueueJdbcDialect dialect, String migration)
      throws Exception {
    container.start();
    // Only connection readiness is retried, never schema or queue operations.
    try (Connection connection = container.createConnection("")) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource(migration));
    }
    CommitFaultDataSource dataSource =
        new CommitFaultDataSource(
            new DriverManagerDataSource(
                container.getJdbcUrl(), container.getUsername(), container.getPassword()));
    JdbcTemplate queueJdbc = new JdbcTemplate(dataSource);
    queueJdbc.setQueryTimeout(10);
    AsyncJobStore store =
        new JdbcAsyncJobStore(
            new NamedParameterJdbcTemplate(queueJdbc),
            dialect,
            new DataSourceTransactionManager(dataSource));
    assertRetry(store, false, false, null);
    queueJdbc.update("DELETE FROM async_job_queue");
    assertRetry(store, true, true, null);
    queueJdbc.update("DELETE FROM async_job_queue");
    assertRetry(store, false, true, "fr-FR");
    queueJdbc.update("DELETE FROM async_job_queue");
    assertTrackedJobRejected(store);
    queueJdbc.update("DELETE FROM async_job_queue");
    assertLostEnqueueAcknowledgement(store, true);
    queueJdbc.update("DELETE FROM async_job_queue");
    assertLostEnqueueAcknowledgement(store, false);
    for (CommitFault fault :
        List.of(CommitFault.AFTER_COMMIT, CommitFault.ROLLBACK_BEFORE_COMMIT)) {
      queueJdbc.update("DELETE FROM async_job_queue");
      assertCompletionCommitFault(store, queueJdbc, dataSource, fault);
    }
    for (PublicationFault fault : PublicationFault.values()) {
      queueJdbc.update("DELETE FROM async_job_queue");
      assertPublicationFault(store, fault);
    }
    queueJdbc.update("DELETE FROM async_job_queue");
    assertPublicationFault(store, PublicationFault.BEFORE_TASK_FINISH, WinnerCondition.MALFORMED);
    queueJdbc.update("DELETE FROM async_job_queue");
    assertRawFailedRowReplay(store);
    queueJdbc.update("DELETE FROM async_job_queue");
    assertTimeoutBeforeDoneCallback(store);
    assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
  }

  private void assertTimeoutBeforeDoneCallback(AsyncJobStore store) throws Exception {
    assertNoTransaction();
    Fixture fixture = createFixture(null);
    long taskId =
        pollableTaskService
            .createPollableTask(
                null,
                GenerateLocalizedAssetJob.class.getCanonicalName(),
                fixture.runName(),
                0,
                3600)
            .getId();
    pollableTaskBlobStorage.saveInput(taskId, fixture.input());
    String input = pollableTaskBlobStorage.getInputJson(taskId);
    PollableTaskService completion =
        mock(PollableTaskService.class, delegatesTo(pollableTaskService));
    PollableTaskBlobStorage publication =
        mock(PollableTaskBlobStorage.class, delegatesTo(pollableTaskBlobStorage));
    LocalizedAssetGenerationService generation =
        mock(LocalizedAssetGenerationService.class, delegatesTo(generationService));
    AtomicReference<Map<String, Object>> terminalRow = new AtomicReference<>();
    AtomicReference<AsyncJobRecord> acceptedWinner = new AtomicReference<>();
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("timeout-before-done-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationMillis(5_000);
    executor.initialize();
    ThreadPoolExecutor workerPool = executor.getThreadPoolExecutor();
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    AssetLocalizeAsyncJobOutputStorage output =
        spy(
            new AssetLocalizeAsyncJobOutputStorage(
                structuredBlobStorage, publication, objectMapper));
    AssetLocalizeAsyncJobHandler handler =
        spy(
            new AssetLocalizeAsyncJobHandler(
                completion,
                publication,
                pollableTaskExceptionUtils,
                generation,
                objectMapper,
                metrics,
                output));
    doAnswer(
            invocation -> {
              assertNoTransaction();
              AsyncJobRecord callbackJob = invocation.getArgument(0);
              AsyncJobRecord done = store.getByIds(List.of(callbackJob.id())).getFirst();
              assertThat(done.status()).isEqualTo(AsyncJobStatus.DONE);
              assertThat(done.attemptCount()).isEqualTo(1);
              assertThat(structuredBlobStorage.getString(POLLABLE_TASK, outputName(payload(done))))
                  .isPresent();
              assertThat(pollableTaskBlobStorage.findOutputJson(taskId)).isEmpty();
              PollableTask task = pollableTaskService.getPollableTask(taskId);
              assertThat(task.getFinishedDate()).isNull();
              // Same timeout action as cleanup, at a controlled service boundary after queue DONE.
              // This is not clock expiration, a global cleaner run, or atomic fencing.
              ExceptionHolder timeout = new ExceptionHolder(task);
              timeout.setExpected(true);
              timeout.setException(
                  new PollableTaskTimeoutException(
                      "Zombie task detected: Maximum execution time exceeded."));
              pollableTaskService.finishTask(taskId, null, timeout, null);
              assertNoTransaction();
              terminalRow.set(jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", taskId));
              acceptedWinner.set(done);
              return invocation.callRealMethod();
            })
        .when(handler)
        .onJobDone(any(), any());
    AsyncJobQueueRuntime runtime =
        new AsyncJobQueueRuntime(
            QUEUE,
            store,
            settings(),
            handler,
            mock(TaskScheduler.class),
            executor,
            metrics,
            "timeout-before-done-worker");
    try {
      AsyncJobId id =
          store.enqueueNow(
              QUEUE,
              objectMapper.writeValueAsStringUnchecked(new AssetLocalizeAsyncJobPayload(taskId)));
      AsyncJobRecord done = awaitState(store, id, runtime, AsyncJobStatus.DONE, 1, true);
      assertThat(done).isEqualTo(acceptedWinner.get());
      assertThat(done.lastError()).isNull();
      assertThat(done.workerId()).isNull();
      assertThat(done.leaseToken()).isNull();
      assertThat(done.leaseUntil()).isNull();
      assertThat(terminalRow.get()).isNotNull();
      PollableTask timedOut = pollableTaskService.getPollableTask(taskId);
      assertThat(timedOut.getFinishedDate()).isNotNull();
      assertThat(objectMapper.readTree(timedOut.getErrorMessage()).path("expected").asBoolean())
          .isTrue();
      assertThat(objectMapper.readTree(timedOut.getErrorMessage()).path("type").asText())
          .isEqualTo(PollableTaskTimeoutException.class.getName());
      assertThat(timedOut.getErrorStack()).contains(PollableTaskTimeoutException.class.getName());
      String privateWinner =
          structuredBlobStorage.getString(POLLABLE_TASK, outputName(payload(done))).orElseThrow();
      assertThat(privateWinner).contains("Accueil");
      for (String phase : List.of("late callback", "callback retry", "repair")) {
        if (phase.equals("callback retry")) {
          new AssetLocalizeAsyncJobHandler(
                  completion,
                  publication,
                  pollableTaskExceptionUtils,
                  generation,
                  objectMapper,
                  metrics,
                  output)
              .onJobDone(done, AsyncJobHandlerResult.done(done.jobData()));
        } else if (phase.equals("repair")) {
          assertThat(
                  new AssetLocalizeAsyncJobRepairService(store, completion, metrics, output)
                      .repairTerminalPollableTask(id.value())
                      .result())
              .isEqualTo("alreadyFinished");
        }
        assertThat(jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", taskId))
            .as("timeout row after %s", phase)
            .isEqualTo(terminalRow.get());
        assertThat(pollableTaskBlobStorage.findOutputJson(taskId)).isEmpty();
        assertThat(pollableTaskBlobStorage.getInputJson(taskId)).isEqualTo(input);
        assertThat(structuredBlobStorage.getString(POLLABLE_TASK, outputName(payload(done))))
            .contains(privateWinner);
        assertThat(store.getByIds(List.of(id)).getFirst()).isEqualTo(done);
        assertThat(
                metrics
                    .get("assetLocalizeAsyncJob.pollableTask.finish.skipped")
                    .tags("queueName", QUEUE, "callback", "done", "reason", "alreadyFinished")
                    .counter()
                    .count())
            .isEqualTo(phase.equals("late callback") ? 1 : 2);
        assertThat(metrics.find("assetLocalizeAsyncJob.pollableTask.finished").counter()).isNull();
        assertThat(metrics.find("assetLocalizeAsyncJob.pollableTask.finish.failed").counter())
            .isNull();
        assertThat(metrics.find("asyncJobQueue.handler.completion.failed").counter()).isNull();
        verify(completion, never()).finishTask(anyLong(), any(), any(), any());
        verify(publication, never()).saveOutput(any(), any());
        verify(output, never()).publishOutput(any());
        verify(output).saveAttemptOutput(eq(taskId), any());
        verify(generation).generate(any());
      }
      verify(handler).onJobDone(any(), any());
      runtime.pollOnce();
      assertThat(runtime.inFlightCount()).isZero();
      assertThat(store.getByIds(List.of(id)).getFirst()).isEqualTo(done);
      verify(generation).generate(any());
      assertNoPullRun(fixture);
    } finally {
      try {
        runtime.stop();
      } finally {
        try {
          executor.shutdown();
          if (!workerPool.isTerminated()) {
            workerPool.shutdownNow();
          }
          assertThat(workerPool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
          metrics.close();
        }
      }
    }
    assertNoTransaction();
  }

  private void assertRawFailedRowReplay(AsyncJobStore store) throws Exception {
    assertNoTransaction();
    Fixture fixture = createFixture(null);
    PollableTask task =
        pollableTaskService.createPollableTask(
            null, GenerateLocalizedAssetJob.class.getCanonicalName(), fixture.runName(), 2, 3600);
    long taskId = task.getId();
    pollableTaskBlobStorage.saveInput(taskId, fixture.input());
    String input = pollableTaskBlobStorage.getInputJson(taskId);
    PollableTaskService completion =
        mock(PollableTaskService.class, delegatesTo(pollableTaskService));
    PollableTaskBlobStorage blobs =
        mock(PollableTaskBlobStorage.class, delegatesTo(pollableTaskBlobStorage));
    PollableTaskExceptionUtils exceptions =
        mock(PollableTaskExceptionUtils.class, delegatesTo(pollableTaskExceptionUtils));
    LocalizedAssetGenerationService generation =
        mock(LocalizedAssetGenerationService.class, delegatesTo(generationService));
    IllegalStateException originalFailure =
        new IllegalStateException("original generation failure");
    doThrow(originalFailure).when(generation).generate(any());
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("raw-replay-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationMillis(5_000);
    executor.initialize();
    ThreadPoolExecutor workerPool = executor.getThreadPoolExecutor();
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    AssetLocalizeAsyncJobOutputStorage output =
        spy(new AssetLocalizeAsyncJobOutputStorage(structuredBlobStorage, blobs, objectMapper));
    AsyncJobQueueRuntime runtime =
        new AsyncJobQueueRuntime(
            QUEUE,
            store,
            settings(),
            new AssetLocalizeAsyncJobHandler(
                completion, blobs, exceptions, generation, objectMapper, metrics, output),
            mock(TaskScheduler.class),
            executor,
            metrics,
            "raw-replay-worker");
    try {
      String payload =
          objectMapper.writeValueAsStringUnchecked(new AssetLocalizeAsyncJobPayload(taskId));
      AsyncJobId id = store.enqueueNow(QUEUE, payload);
      AsyncJobRecord failed = awaitState(store, id, runtime, AsyncJobStatus.FAILED, 2, true);
      assertThat(failed.lastError()).contains(originalFailure.getMessage());
      PollableTask finished = pollableTaskService.getPollableTask(taskId);
      assertThat(finished.getFinishedDate()).isNotNull();
      assertThat(finished.getErrorMessage())
          .contains("\"type\":\"unexpected\"")
          .doesNotContain(originalFailure.getMessage());
      assertThat(finished.getErrorStack())
          .contains("Asset localize async job failed permanently: " + id.value())
          .doesNotContain(originalFailure.getMessage());
      // Expected children are outstanding: guard the task's own finish, not aggregate completion.
      assertThat(finished.isAllFinished()).isFalse();
      var terminalRow = jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", taskId);
      verify(generation, times(2)).generate(any());
      verify(blobs, times(2)).getInputBytes(taskId);
      verifyNoInteractions(exceptions);
      verify(completion).finishTask(eq(taskId), isNull(), any(ExceptionHolder.class), isNull());
      verifyNoInteractions(output);
      assertThat(pollableTaskBlobStorage.findOutputJson(taskId)).isEmpty();

      // Generation is healthy again; raw replay must reject before reaching it or any blob I/O.
      doAnswer(invocation -> generationService.generate(invocation.getArgument(0)))
          .when(generation)
          .generate(any());
      clearInvocations(completion, blobs, exceptions, generation, output);
      AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
      new AsyncJobQueueInspectionService(store, coordinator, metrics)
          .requeueFailedJob(QUEUE, id.value(), null);
      verify(coordinator).triggerPollNow(QUEUE);
      AsyncJobRecord queued = store.getByIds(List.of(id)).getFirst();
      assertThat(queued.status()).isEqualTo(AsyncJobStatus.QUEUED);
      assertThat(queued.attemptCount()).isZero();
      assertThat(queued.jobData()).isEqualTo(payload);
      assertThat(jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", taskId))
          .isEqualTo(terminalRow);

      AsyncJobRecord rejected = awaitState(store, id, runtime, AsyncJobStatus.FAILED, 1, true);
      assertThat(rejected.lastError())
          .isEqualTo(
              AsyncJobPermanentFailureException.class.getName()
                  + ": Asset localize async queue cannot execute finished pollable task: "
                  + taskId);
      assertThat(rejected.jobData()).isEqualTo(payload);
      assertThat(rejected.workerId()).isNull();
      assertThat(rejected.leaseToken()).isNull();
      assertThat(rejected.leaseUntil()).isNull();
      assertThat(jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", taskId))
          .isEqualTo(terminalRow);
      assertThat(pollableTaskBlobStorage.getInputJson(taskId)).isEqualTo(input);
      assertThat(pollableTaskBlobStorage.findOutputJson(taskId)).isEmpty();
      runtime.pollOnce();
      assertThat(runtime.inFlightCount()).isZero();
      assertThat(store.getByIds(List.of(id)).getFirst()).isEqualTo(rejected);
      assertThat(store.claimNextJobs(QUEUE, 1, "other", Duration.ofSeconds(10))).isEmpty();
      verifyNoInteractions(blobs, exceptions, generation, output);
      verify(completion, times(2)).getPollableTask(taskId);
      verifyNoMoreInteractions(completion);
      // Only the initial ordinary generation failure retries; replay fails on its first attempt.
      assertThat(metrics.get("asyncJobQueue.retried").tag("queueName", QUEUE).counter().count())
          .isEqualTo(1);
      assertThat(
              metrics
                  .get("assetLocalizeAsyncJob.process")
                  .tags("queueName", QUEUE, "result", "failed")
                  .counter()
                  .count())
          .isEqualTo(3);
      assertThat(
              metrics
                  .get("assetLocalizeAsyncJob.pollableTask.finish.skipped")
                  .tags("queueName", QUEUE, "callback", "failed", "reason", "alreadyFinished")
                  .counter()
                  .count())
          .isEqualTo(1);
      assertThat(
              metrics
                  .get("assetLocalizeAsyncJob.pollableTask.finished")
                  .tags("queueName", QUEUE, "result", "failed")
                  .counter()
                  .count())
          .isEqualTo(1);
      assertThat(metrics.find("asyncJobQueue.handler.completion.failed").counter()).isNull();
      assertNoPullRun(fixture);
    } finally {
      try {
        runtime.stop();
      } finally {
        try {
          executor.shutdown();
          if (!workerPool.isTerminated()) {
            workerPool.shutdownNow();
          }
          assertThat(workerPool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
          metrics.close();
        }
      }
    }
    assertNoTransaction();
  }

  private void assertPublicationFault(AsyncJobStore store, PublicationFault fault)
      throws Exception {
    assertPublicationFault(store, fault, WinnerCondition.INTACT);
  }

  private void assertPublicationFault(
      AsyncJobStore store, PublicationFault fault, WinnerCondition winnerCondition)
      throws Exception {
    assertNoTransaction();
    Fixture fixture = createFixture(null);
    boolean taskCommitted = fault == PublicationFault.AFTER_TASK_FINISH;
    boolean canonicalCommitted = fault != PublicationFault.BEFORE_CANONICAL_WRITE;
    boolean outputFault =
        fault == PublicationFault.BEFORE_CANONICAL_WRITE
            || fault == PublicationFault.AFTER_CANONICAL_WRITE;
    PollableTaskBlobStorage publication =
        mock(PollableTaskBlobStorage.class, delegatesTo(pollableTaskBlobStorage));
    PollableTaskService completion =
        mock(PollableTaskService.class, delegatesTo(pollableTaskService));
    IllegalStateException injected = new IllegalStateException("publication fault: " + fault);
    if (outputFault) {
      doAnswer(
              invocation -> {
                assertNoTransaction();
                if (fault == PublicationFault.AFTER_CANONICAL_WRITE) {
                  pollableTaskBlobStorage.saveOutput(
                      invocation.getArgument(0), invocation.getArgument(1));
                }
                throw injected;
              })
          .when(publication)
          .saveOutput(any(), any());
    } else {
      doAnswer(
              invocation -> {
                assertNoTransaction();
                if (fault == PublicationFault.AFTER_TASK_FINISH) {
                  pollableTaskService.finishTask(
                      invocation.getArgument(0),
                      invocation.getArgument(1),
                      invocation.getArgument(2),
                      invocation.getArgument(3));
                  assertNoTransaction();
                }
                throw injected;
              })
          .when(completion)
          .finishTask(anyLong(), any(), any(), any());
    }
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("publication-fault-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationMillis(5_000);
    executor.initialize();
    ThreadPoolExecutor workerPool = executor.getThreadPoolExecutor();
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    try {
      AssetLocalizeAsyncJobOutputStorage outputStorage =
          new AssetLocalizeAsyncJobOutputStorage(structuredBlobStorage, publication, objectMapper);
      AssetLocalizeAsyncJobHandler handler =
          spy(
              new AssetLocalizeAsyncJobHandler(
                  completion,
                  pollableTaskBlobStorage,
                  pollableTaskExceptionUtils,
                  generationService,
                  objectMapper,
                  metrics,
                  outputStorage));
      AsyncJobQueueRuntime runtime =
          new AsyncJobQueueRuntime(
              QUEUE,
              store,
              settings(),
              handler,
              mock(TaskScheduler.class),
              executor,
              metrics,
              "publication-fault-worker");
      try {
        AssetLocalizeAsyncJobSubmissionService submission =
            new AssetLocalizeAsyncJobSubmissionService(
                pollableTaskService,
                pollableTaskBlobStorage,
                pollableTaskExceptionUtils,
                new AsyncJobQueueSubmissionService(
                    store, mock(AsyncJobQueueCoordinator.class), metrics),
                objectMapper,
                metrics);
        long taskId =
            submission
                .scheduleJob(
                    QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
                        .withInput(fixture.input())
                        .withMessage(fixture.runName())
                        .build())
                .getPollableTask()
                .getId();
        AsyncJobRecord queued = store.findByStatus(QUEUE, AsyncJobStatus.QUEUED, 10).getFirst();
        String input = pollableTaskBlobStorage.getInputJson(taskId);
        AsyncJobRecord done = awaitState(store, queued.id(), runtime, AsyncJobStatus.DONE, 1, true);
        assertThat(done.lastError()).isNull();
        assertThat(done.workerId()).isNull();
        assertThat(done.leaseToken()).isNull();
        assertThat(done.leaseUntil()).isNull();
        assertTask(taskId, fixture.runName(), taskCommitted);
        String privateOutput =
            structuredBlobStorage.getString(POLLABLE_TASK, outputName(payload(done))).orElseThrow();
        assertThat(privateOutput).contains("Accueil").doesNotContain("Page principale");
        var canonicalOutput = pollableTaskBlobStorage.findOutputJson(taskId);
        assertThat(canonicalOutput.isPresent())
            .as("canonical publication at %s", fault)
            .isEqualTo(canonicalCommitted);
        if (canonicalCommitted) {
          assertThat(objectMapper.readTree(canonicalOutput.orElseThrow()))
              .isEqualTo(objectMapper.readTree(privateOutput));
        }
        verify(publication).saveOutput(eq(taskId), any());
        verify(completion, times(outputFault ? 0 : 1)).finishTask(eq(taskId), any(), any(), any());
        assertThat(
                metrics
                    .get("asyncJobQueue.handler.completion.failed")
                    .tags("queueName", QUEUE, "callback", "done")
                    .counter()
                    .count())
            .isEqualTo(1);
        assertThat(
                metrics
                    .get("assetLocalizeAsyncJob.pollableTask.finish.failed")
                    .tags("queueName", QUEUE, "callback", "done")
                    .counter()
                    .count())
            .isEqualTo(1);
        assertThat(metrics.find("asyncJobQueue.handler.failed").tags("queueName", QUEUE).counter())
            .isNull();
        assertThat(
                metrics.find("asyncJobQueue.transition.failed").tags("queueName", QUEUE).counter())
            .isNull();

        // Fresh services simulate reconciliation after the failing publisher has gone away.
        // Changing TM makes accidental regeneration observably different from winner publication.
        tmService.addCurrentTMTextUnitVariant(
            fixture.textUnit().getId(), fixture.localeId(), "Page principale");
        PollableTaskBlobStorage recoveredPublication =
            mock(PollableTaskBlobStorage.class, delegatesTo(pollableTaskBlobStorage));
        PollableTaskService recoveredCompletion =
            mock(PollableTaskService.class, delegatesTo(pollableTaskService));
        AssetLocalizeAsyncJobRepairService repair =
            new AssetLocalizeAsyncJobRepairService(
                store,
                recoveredCompletion,
                metrics,
                new AssetLocalizeAsyncJobOutputStorage(
                    structuredBlobStorage, recoveredPublication, objectMapper));
        if (winnerCondition == WinnerCondition.EXPIRED) {
          assertThat(applicationContext.containsBean("triggerExpiringBlobCleanup")).isFalse();
          assertThat(taskCommitted).isFalse();
          String privateOutputName = outputName(payload(done));
          byte[] originalBytes =
              structuredBlobStorage.getBytes(POLLABLE_TASK, privateOutputName).orElseThrow();
          var pendingTask = jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", taskId);
          assertThat(structuredBlobStorage.getStorageType(POLLABLE_TASK))
              .isEqualTo(DatabaseBlobStorage.class.getSimpleName());
          String fullName = "pollable_task/" + privateOutputName;
          MBlob winner = mBlobRepository.findByName(fullName).orElseThrow();
          assertThat(winner.hasExpiration()).isTrue();
          assertThat(winner.getContent()).containsExactly(originalBytes);
          // Age only this fixture's winner past its actual TTL, then run the ordinary cleaner.
          winner.setCreatedDate(
              ZonedDateTime.now().minusSeconds(winner.getExpireAfterSeconds()).minusHours(1));
          mBlobRepository.saveAndFlush(winner);
          assertNoTransaction();
          assertThat(structuredBlobStorage.getBytes(POLLABLE_TASK, privateOutputName)).isPresent();
          databaseBlobStorage.deleteExpired();
          assertThat(mBlobRepository.findByName(fullName)).isEmpty();
          assertThat(structuredBlobStorage.getBytes(POLLABLE_TASK, privateOutputName)).isEmpty();

          // Repeated repair cannot substitute the surviving canonical copy or regenerate from TM.
          for (int attempt = 0; attempt < 2; attempt++) {
            runtime.pollOnce();
            assertThatThrownBy(() -> repair.repairTerminalPollableTask(done.id().value()))
                .isInstanceOf(AssetLocalizePollableTaskRepairException.class)
                .satisfies(
                    exception ->
                        assertThat(exception.getCause())
                            .isExactlyInstanceOf(IllegalStateException.class)
                            .hasMessage(
                                "Missing assetlocalize attempt output for pollable task: " + taskId)
                            .hasNoCause());
            assertThat(jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", taskId))
                .isEqualTo(pendingTask);
            assertThat(pollableTaskBlobStorage.findOutputJson(taskId)).isEqualTo(canonicalOutput);
            assertThat(pollableTaskBlobStorage.getInputJson(taskId)).isEqualTo(input);
            assertThat(structuredBlobStorage.getBytes(POLLABLE_TASK, privateOutputName)).isEmpty();
            assertThat(store.getByIds(List.of(done.id())).getFirst()).isEqualTo(done);
            verify(recoveredPublication, never()).saveOutput(any(), any());
            verify(recoveredCompletion, never()).finishTask(anyLong(), any(), any(), any());
            verify(handler).process(any());
            verify(handler).onJobDone(any(), any());
          }
          // Simulate recovery from an exact backup, not reconstruction from canonical output or TM.
          structuredBlobStorage.putBytes(
              POLLABLE_TASK, privateOutputName, originalBytes, Retention.MIN_1_DAY);
          assertThat(structuredBlobStorage.getBytes(POLLABLE_TASK, privateOutputName).orElseThrow())
              .containsExactly(originalBytes);
        }
        if (winnerCondition == WinnerCondition.MALFORMED) {
          assertThat(canonicalOutput).isPresent();
          assertTask(taskId, fixture.runName(), false);
          String privateOutputName = outputName(payload(done));
          // Keep the winner metadata while corrupting framing or decoded Unicode content.
          for (String malformedOutput :
              List.of(
                  privateOutput + "\n{\"content\":\"invalid retained winner\"}",
                  "{\"content\":\"invalid retained winner\"," + privateOutput.substring(1),
                  privateOutput.replace("Accueil", "\\ud800"),
                  privateOutput.replace("Accueil", "\\udc00"))) {
            structuredBlobStorage.put(
                POLLABLE_TASK, privateOutputName, malformedOutput, Retention.MIN_1_DAY);
            assertThatThrownBy(() -> repair.repairTerminalPollableTask(done.id().value()))
                .isInstanceOf(AssetLocalizePollableTaskRepairException.class)
                .satisfies(
                    exception ->
                        assertThat(exception.getCause())
                            .isExactlyInstanceOf(IllegalStateException.class)
                            .hasMessage("Invalid assetlocalize output for pollable task: " + taskId)
                            .hasNoCause());
            assertTask(taskId, fixture.runName(), false);
            assertThat(pollableTaskBlobStorage.getOutputJson(taskId))
                .isEqualTo(canonicalOutput.orElseThrow());
            assertThat(pollableTaskBlobStorage.getInputJson(taskId)).isEqualTo(input);
            assertThat(structuredBlobStorage.getString(POLLABLE_TASK, privateOutputName))
                .contains(malformedOutput);
            assertThat(store.getByIds(List.of(done.id())).getFirst()).isEqualTo(done);
            verify(recoveredPublication, never()).saveOutput(any(), any());
            verify(recoveredCompletion, never()).finishTask(anyLong(), any(), any(), any());
            verify(handler).process(any());
          }
          structuredBlobStorage.put(
              POLLABLE_TASK, privateOutputName, privateOutput, Retention.MIN_1_DAY);
        }
        var finishedBeforeRepair = pollableTaskService.getPollableTask(taskId).getFinishedDate();
        runtime.pollOnce();
        assertThat(repair.repairTerminalPollableTask(done.id().value()).result())
            .isEqualTo(taskCommitted ? "alreadyFinished" : "repaired");
        verify(recoveredPublication, times(taskCommitted ? 0 : 1)).saveOutput(eq(taskId), any());
        verify(recoveredCompletion, times(taskCommitted ? 0 : 1))
            .finishTask(eq(taskId), any(), any(), any());
        if (taskCommitted) {
          assertThat(pollableTaskService.getPollableTask(taskId).getFinishedDate())
              .isEqualTo(finishedBeforeRepair);
        }
        assertTask(taskId, fixture.runName(), true);
        var finished = pollableTaskService.getPollableTask(taskId).getFinishedDate();
        String published = pollableTaskBlobStorage.getOutputJson(taskId);
        if (canonicalCommitted) {
          assertThat(published).isEqualTo(canonicalOutput.orElseThrow());
        }
        assertThat(objectMapper.readTree(published))
            .isEqualTo(objectMapper.readTree(privateOutput));
        assertThat(pollableTaskBlobStorage.getOutput(taskId, LocalizedAssetBody.class).getContent())
            .contains("Accueil")
            .doesNotContain("Page principale");
        assertThat(repair.repairTerminalPollableTask(done.id().value()).result())
            .isEqualTo("alreadyFinished");
        runtime.pollOnce();
        assertThat(pollableTaskService.getPollableTask(taskId).getFinishedDate())
            .isEqualTo(finished);
        assertThat(pollableTaskBlobStorage.getOutputJson(taskId)).isEqualTo(published);
        assertThat(pollableTaskBlobStorage.getInputJson(taskId)).isEqualTo(input);
        assertThat(structuredBlobStorage.getString(POLLABLE_TASK, outputName(payload(done))))
            .contains(privateOutput);
        assertThat(store.getByIds(List.of(done.id())).getFirst()).isEqualTo(done);
        assertThat(store.countByStatus(QUEUE).stream().mapToLong(AsyncJobStatusCount::count).sum())
            .isEqualTo(1);
        verify(handler).process(any());
        verify(handler).onJobDone(any(), any());
        verify(handler, never()).onJobFailedPermanently(any(), any(), any());
        verify(recoveredPublication, times(taskCommitted ? 0 : 1)).saveOutput(eq(taskId), any());
        verify(recoveredCompletion, times(taskCommitted ? 0 : 1))
            .finishTask(eq(taskId), any(), any(), any());
        assertNoPullRun(fixture);
      } finally {
        runtime.stop();
      }
    } finally {
      try {
        executor.shutdown();
        if (!workerPool.isTerminated()) {
          workerPool.shutdownNow();
        }
        assertThat(workerPool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        metrics.close();
      }
    }
    assertNoTransaction();
  }

  private enum PublicationFault {
    BEFORE_CANONICAL_WRITE,
    AFTER_CANONICAL_WRITE,
    BEFORE_TASK_FINISH,
    AFTER_TASK_FINISH
  }

  private enum WinnerCondition {
    INTACT,
    MALFORMED,
    EXPIRED
  }

  private void assertCompletionCommitFault(
      AsyncJobStore store,
      JdbcTemplate queueJdbc,
      CommitFaultDataSource dataSource,
      CommitFault fault)
      throws Exception {
    assertNoTransaction();
    Fixture fixture = createFixture(null);
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("completion-ack-loss-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationMillis(5_000);
    executor.initialize();
    ThreadPoolExecutor workerPool = executor.getThreadPoolExecutor();
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    try {
      AssetLocalizeAsyncJobOutputStorage outputStorage =
          new AssetLocalizeAsyncJobOutputStorage(
              structuredBlobStorage, pollableTaskBlobStorage, objectMapper);
      AssetLocalizeAsyncJobHandler handler =
          spy(
              new AssetLocalizeAsyncJobHandler(
                  pollableTaskService,
                  pollableTaskBlobStorage,
                  pollableTaskExceptionUtils,
                  generationService,
                  objectMapper,
                  metrics,
                  outputStorage));
      List<AsyncJobRecord> claims = new CopyOnWriteArrayList<>();
      List<AsyncJobHandlerResult> results = new CopyOnWriteArrayList<>();
      doAnswer(
              invocation -> {
                claims.add(invocation.getArgument(0));
                AsyncJobHandlerResult result = (AsyncJobHandlerResult) invocation.callRealMethod();
                results.add(result);
                if (results.size() == 1) {
                  // Generation and blob writes are finished. With heartbeat disabled, the next
                  // queue transaction on this worker is the real fenced DONE transition.
                  dataSource.failNextCommit(fault);
                }
                return result;
              })
          .when(handler)
          .process(any());
      AsyncJobQueueRuntime runtime =
          new AsyncJobQueueRuntime(
              QUEUE,
              store,
              settings(),
              handler,
              mock(TaskScheduler.class),
              executor,
              metrics,
              "completion-ack-loss-worker");
      try {
        AssetLocalizeAsyncJobSubmissionService submission =
            new AssetLocalizeAsyncJobSubmissionService(
                pollableTaskService,
                pollableTaskBlobStorage,
                pollableTaskExceptionUtils,
                new AsyncJobQueueSubmissionService(
                    store, mock(AsyncJobQueueCoordinator.class), metrics),
                objectMapper,
                metrics);
        long taskId =
            submission
                .scheduleJob(
                    QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
                        .withInput(fixture.input())
                        .withMessage(fixture.runName())
                        .build())
                .getPollableTask()
                .getId();
        AsyncJobRecord original = store.findByStatus(QUEUE, AsyncJobStatus.QUEUED, 10).getFirst();
        String originalInput = pollableTaskBlobStorage.getInputJson(taskId);
        int priorFaults = dataSource.faults.get();
        int priorRollbacks = dataSource.injectedRollbacks.get();
        runtime.pollOnce();
        // Inspection also commits transactions. Do not let it consume the armed fault while
        // the worker is completing; wait on the runtime's memory-only lifecycle first.
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (runtime.inFlightCount() != 0 && System.nanoTime() < deadline) {
          Thread.sleep(10);
        }
        assertThat(runtime.inFlightCount()).isZero();
        assertThat(dataSource.fault.get()).isEqualTo(CommitFault.NONE);
        assertThat(dataSource.faults.get()).isEqualTo(priorFaults + 1);
        assertThat(dataSource.injectedRollbacks.get())
            .isEqualTo(priorRollbacks + (fault == CommitFault.ROLLBACK_BEFORE_COMMIT ? 1 : 0));
        assertThat(claims).hasSize(1);
        assertThat(results).hasSize(1);
        verify(handler, never()).onJobDone(any(), any());
        verify(handler, never()).onJobFailedPermanently(any(), any(), any());
        assertTask(taskId, fixture.runName(), false);
        assertThat(pollableTaskBlobStorage.findOutputJson(taskId)).isEmpty();
        AssetLocalizeAsyncJobPayload firstOutput =
            objectMapper.readValueUnchecked(
                results.getFirst().jobData(), AssetLocalizeAsyncJobPayload.class);
        String privateOutput =
            structuredBlobStorage.getString(POLLABLE_TASK, outputName(firstOutput)).orElseThrow();
        assertThat(privateOutput).contains("Accueil");
        assertThat(
                metrics
                    .get("asyncJobQueue.transition.failed")
                    .tags("queueName", QUEUE, "transition", "done")
                    .counter()
                    .count())
            .isEqualTo(1);
        assertThat(metrics.find("asyncJobQueue.handler.failed").tags("queueName", QUEUE).counter())
            .isNull();
        AssetLocalizeAsyncJobRepairService repair =
            new AssetLocalizeAsyncJobRepairService(
                store, pollableTaskService, metrics, outputStorage);
        AsyncJobRecord uncertain = store.getByIds(List.of(original.id())).getFirst();
        TMTextUnitVariant newer =
            tmService.addCurrentTMTextUnitVariant(
                fixture.textUnit().getId(), fixture.localeId(), "Page principale");
        assertThat(newer.getId()).isNotEqualTo(fixture.variant().getId());
        AsyncJobRecord completed;
        if (fault == CommitFault.AFTER_COMMIT) {
          assertThat(uncertain.status()).isEqualTo(AsyncJobStatus.DONE);
          assertThat(uncertain.jobData()).isEqualTo(results.getFirst().jobData());
          assertThat(uncertain.leaseToken()).isNull();
          assertThat(uncertain.lastError()).isNull();
          runtime.pollOnce();
          assertThat(claims).hasSize(1);
          assertThat(repair.repairTerminalPollableTask(original.id().value()).result())
              .isEqualTo("repaired");
          completed = store.getByIds(List.of(original.id())).getFirst();
          assertThat(completed).isEqualTo(uncertain);
          verify(handler, never()).onJobDone(any(), any());
        } else {
          assertThat(uncertain.status()).isEqualTo(AsyncJobStatus.RUNNING);
          assertThat(uncertain.jobData()).isEqualTo(original.jobData());
          assertThat(uncertain.leaseToken()).isEqualTo(claims.getFirst().leaseToken());
          assertThatThrownBy(() -> repair.repairTerminalPollableTask(original.id().value()))
              .isInstanceOf(IllegalStateException.class);
          assertTask(taskId, fixture.runName(), false);
          assertThat(pollableTaskBlobStorage.findOutputJson(taskId)).isEmpty();
          // Explicitly advance only this fixture's lease; this is not elapsed-time or crash proof.
          assertThat(
                  queueJdbc.update(
                      "UPDATE async_job_queue SET lease_until = created_date WHERE id = ?",
                      Long.valueOf(original.id().value())))
              .isEqualTo(1);
          completed = awaitState(store, original.id(), runtime, AsyncJobStatus.DONE, 2, true);
          assertThat(claims).hasSize(2);
          assertThat(claims.getLast().leaseToken()).isNotEqualTo(claims.getFirst().leaseToken());
          assertThat(payload(completed).outputId()).isNotEqualTo(firstOutput.outputId());
          verify(handler, times(1)).onJobDone(any(), any());
        }
        assertThat(
                store.markDone(
                    QUEUE,
                    original.id(),
                    claims.getFirst().workerId(),
                    claims.getFirst().leaseToken(),
                    results.getFirst().jobData()))
            .isFalse();
        assertThat(store.getByIds(List.of(original.id())).getFirst()).isEqualTo(completed);
        assertThat(repair.repairTerminalPollableTask(original.id().value()).result())
            .isEqualTo("alreadyFinished");
        assertTask(taskId, fixture.runName(), true);
        assertThat(pollableTaskBlobStorage.getInputJson(taskId)).isEqualTo(originalInput);
        String winningOutput =
            structuredBlobStorage
                .getString(POLLABLE_TASK, outputName(payload(completed)))
                .orElseThrow();
        assertThat(objectMapper.readTree(pollableTaskBlobStorage.getOutputJson(taskId)))
            .isEqualTo(objectMapper.readTree(winningOutput));
        String publishedContent =
            pollableTaskBlobStorage.getOutput(taskId, LocalizedAssetBody.class).getContent();
        assertThat(publishedContent)
            .contains(fault == CommitFault.AFTER_COMMIT ? "Accueil" : "Page principale")
            .doesNotContain(fault == CommitFault.AFTER_COMMIT ? "Page principale" : "Accueil");
        assertThat(structuredBlobStorage.getString(POLLABLE_TASK, outputName(firstOutput)))
            .contains(privateOutput);
        assertThat(completed.attemptCount()).isEqualTo(fault == CommitFault.AFTER_COMMIT ? 1 : 2);
        verify(handler, times(completed.attemptCount())).process(any());
        verify(handler, never()).onJobFailedPermanently(any(), any(), any());
        assertThat(store.countByStatus(QUEUE).stream().mapToLong(AsyncJobStatusCount::count).sum())
            .isEqualTo(1);
        assertNoPullRun(fixture);
      } finally {
        runtime.stop();
      }
    } finally {
      try {
        executor.shutdown();
        if (!workerPool.isTerminated()) {
          workerPool.shutdownNow();
        }
        assertThat(workerPool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        metrics.close();
      }
    }
    assertNoTransaction();
    assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
  }

  private void assertLostEnqueueAcknowledgement(
      AsyncJobStore store, boolean completeBeforeSubmissionUnwinds) throws Exception {
    assertNoTransaction();
    Fixture fixture = createFixture(null);
    String originalInput = objectMapper.writeValueAsStringUnchecked(fixture.input());
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("enqueue-ack-loss-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationMillis(5_000);
    executor.initialize();
    ThreadPoolExecutor workerPool = executor.getThreadPoolExecutor();
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    try {
      AssetLocalizeAsyncJobHandler handler =
          new AssetLocalizeAsyncJobHandler(
              pollableTaskService,
              pollableTaskBlobStorage,
              pollableTaskExceptionUtils,
              generationService,
              objectMapper,
              metrics,
              new AssetLocalizeAsyncJobOutputStorage(
                  structuredBlobStorage, pollableTaskBlobStorage, objectMapper));
      AsyncJobQueueRuntime runtime =
          new AsyncJobQueueRuntime(
              QUEUE,
              store,
              settings(),
              handler,
              mock(TaskScheduler.class),
              executor,
              metrics,
              "enqueue-ack-loss-worker");
      try {
        RuntimeException failure =
            new IllegalStateException("injected lost enqueue acknowledgement");
        AtomicReference<AsyncJobRecord> enqueued = new AtomicReference<>();
        AsyncJobStore submissionStore = mock(AsyncJobStore.class);
        when(submissionStore.enqueueNow(eq(QUEUE), anyString()))
            .thenAnswer(
                invocation -> {
                  // Real enqueue has returned (and JDBC has committed) before this seam fault.
                  AsyncJobId id =
                      store.enqueueNow(invocation.getArgument(0), invocation.getArgument(1));
                  AsyncJobRecord queued = store.getByIds(List.of(id)).getFirst();
                  enqueued.set(queued);
                  long taskId = payload(queued).pollableTaskId();
                  assertThat(queued.status()).isEqualTo(AsyncJobStatus.QUEUED);
                  assertThat(queued.attemptCount()).isZero();
                  assertTask(taskId, fixture.runName(), false);
                  assertThat(pollableTaskBlobStorage.getInputJson(taskId)).isEqualTo(originalInput);
                  assertThat(pollableTaskBlobStorage.findOutputJson(taskId)).isEmpty();
                  if (completeBeforeSubmissionUnwinds) {
                    awaitState(store, id, runtime, AsyncJobStatus.DONE, 1, true);
                    assertTask(taskId, fixture.runName(), true);
                  }
                  throw failure;
                });
        AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
        AssetLocalizeAsyncJobSubmissionService submission =
            new AssetLocalizeAsyncJobSubmissionService(
                pollableTaskService,
                pollableTaskBlobStorage,
                pollableTaskExceptionUtils,
                new AsyncJobQueueSubmissionService(submissionStore, coordinator, metrics),
                objectMapper,
                metrics);

        assertThatThrownBy(
                () ->
                    submission.scheduleJob(
                        QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
                            .withInput(fixture.input())
                            .withMessage(fixture.runName())
                            .build()))
            .isSameAs(failure);

        AsyncJobRecord originalJob = enqueued.get();
        assertThat(originalJob).isNotNull();
        long taskId = payload(originalJob).pollableTaskId();
        assertTask(taskId, fixture.runName(), completeBeforeSubmissionUnwinds);
        if (!completeBeforeSubmissionUnwinds) {
          assertThat(pollableTaskBlobStorage.findOutputJson(taskId)).isEmpty();
        }
        AsyncJobRecord completed =
            awaitState(store, originalJob.id(), runtime, AsyncJobStatus.DONE, 1, true);
        assertThat(completed.lastError()).isNull();
        AssetLocalizeAsyncJobPayload winner = payload(completed);
        assertThat(winner.pollableTaskId()).isEqualTo(taskId);
        assertThat(winner.outputId()).isNotBlank();
        assertTask(taskId, fixture.runName(), true);
        assertThat(pollableTaskBlobStorage.getInputJson(taskId)).isEqualTo(originalInput);
        assertThat(fixture.input().getContent()).isEqualTo(fixture.sourceXliff());
        assertThat(fixture.input().getBcp47Tag()).isNull();
        LocalizedAssetBody output =
            pollableTaskBlobStorage.getOutput(taskId, LocalizedAssetBody.class);
        assertThat(output.getContent()).contains(fixture.variant().getContent());
        assertThat(output.getBcp47Tag()).isEqualTo("fr-FR");
        assertThat(output.getAssetId()).isEqualTo(fixture.asset().getId());
        assertThat(output.getLocaleId()).isEqualTo(fixture.localeId());
        String attemptOutput =
            structuredBlobStorage.getString(POLLABLE_TASK, outputName(winner)).orElseThrow();
        assertThat(objectMapper.readTree(pollableTaskBlobStorage.getOutputJson(taskId)))
            .isEqualTo(objectMapper.readTree(attemptOutput));
        assertNoPullRun(fixture);
        assertThat(store.countByStatus(QUEUE).stream().mapToLong(AsyncJobStatusCount::count).sum())
            .isEqualTo(1);
        assertThat(store.findByStatus(QUEUE, AsyncJobStatus.DONE, 10))
            .extracting(AsyncJobRecord::id)
            .containsExactly(originalJob.id());
        verify(submissionStore).enqueueNow(QUEUE, originalJob.jobData());
        verifyNoMoreInteractions(submissionStore);
        verifyNoInteractions(coordinator);
        assertThat(
                metrics
                    .get("assetLocalizeAsyncJob.schedule")
                    .tags("queueName", QUEUE, "result", "outcomeUnknown")
                    .counter()
                    .count())
            .isEqualTo(1);
        for (String result : List.of("succeeded", "failed")) {
          assertThat(
                  metrics
                      .find("assetLocalizeAsyncJob.schedule")
                      .tags("queueName", QUEUE, "result", result)
                      .counter())
              .isNull();
        }
      } finally {
        runtime.stop();
      }
    } finally {
      try {
        executor.shutdown();
        if (!workerPool.isTerminated()) {
          workerPool.shutdownNow();
        }
        assertThat(workerPool.awaitTermination(10, TimeUnit.SECONDS))
            .as("enqueue acknowledgement worker terminated before releasing dependent resources")
            .isTrue();
      } finally {
        metrics.close();
      }
    }
    assertNoTransaction();
  }

  private void assertRetry(
      AsyncJobStore store, boolean writeBeforeFailure, boolean changeTranslation, String outputTag)
      throws Exception {
    assertNoTransaction();
    Fixture fixture = createFixture(outputTag);
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("lineage-retry-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationMillis(5_000);
    executor.initialize();
    ThreadPoolExecutor workerPool = executor.getThreadPoolExecutor();
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    try {
      FailingOutputStorage outputStorage = new FailingOutputStorage(writeBeforeFailure);
      AssetLocalizeAsyncJobHandler handler =
          new AssetLocalizeAsyncJobHandler(
              pollableTaskService,
              pollableTaskBlobStorage,
              pollableTaskExceptionUtils,
              generationService,
              objectMapper,
              metrics,
              outputStorage);
      AsyncJobQueueRuntime runtime =
          new AsyncJobQueueRuntime(
              QUEUE,
              store,
              settings(),
              handler,
              mock(TaskScheduler.class),
              executor,
              metrics,
              "lineage-worker");
      try {
        AssetLocalizeAsyncJobSubmissionService submission =
            new AssetLocalizeAsyncJobSubmissionService(
                pollableTaskService,
                pollableTaskBlobStorage,
                pollableTaskExceptionUtils,
                new AsyncJobQueueSubmissionService(
                    store, mock(AsyncJobQueueCoordinator.class), metrics),
                objectMapper,
                metrics);
        long taskId =
            submission
                .scheduleJob(
                    QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
                        .withInput(fixture.input())
                        .withMessage(fixture.runName())
                        .build())
                .getPollableTask()
                .getId();
        List<AsyncJobRecord> submitted = store.findByStatus(QUEUE, AsyncJobStatus.QUEUED, 10);
        assertThat(submitted).hasSize(1);
        AsyncJobRecord originalJob = submitted.get(0);
        assertThat(payload(originalJob).pollableTaskId()).isEqualTo(taskId);
        String originalInput = pollableTaskBlobStorage.getInputJson(taskId);

        runtime.pollOnce();
        AsyncJobRecord retry =
            awaitState(store, originalJob.id(), runtime, AsyncJobStatus.QUEUED, 1, false);
        assertThat(retry.jobData()).isEqualTo(originalJob.jobData());
        assertThat(retry.lastError()).contains(STORAGE_FAILURE);
        assertThat(outputStorage.contents).hasSize(1);
        assertThat(outputStorage.contents.get(0)).contains("Accueil");
        assertTask(taskId, fixture.runName(), false);
        assertThat(pollableTaskBlobStorage.findOutputJson(taskId)).isEmpty();
        assertThat(pollableTaskBlobStorage.getInputJson(taskId)).isEqualTo(originalInput);
        assertNoPullRun(fixture);
        assertThat(outputStorage.saved).hasSize(writeBeforeFailure ? 1 : 0);

        TMTextUnitVariant expected = fixture.variant();
        if (changeTranslation) {
          expected =
              tmService.addCurrentTMTextUnitVariant(
                  fixture.textUnit().getId(), fixture.localeId(), "Page principale");
          assertThat(expected.getId()).isNotEqualTo(fixture.variant().getId());
        }
        AsyncJobRecord completed =
            awaitState(store, originalJob.id(), runtime, AsyncJobStatus.DONE, 2, true);
        assertThat(completed.lastError()).isNull();
        AssetLocalizeAsyncJobPayload winner = payload(completed);
        assertThat(winner.pollableTaskId()).isEqualTo(taskId);
        assertThat(winner.outputId()).isNotBlank();
        assertThat(outputStorage.contents).hasSize(2);
        assertThat(outputStorage.contents.get(1)).contains(expected.getContent());
        assertThat(outputStorage.saved).hasSize(writeBeforeFailure ? 2 : 1);
        assertThat(winner).isEqualTo(outputStorage.saved.getLast());
        assertNoPullRun(fixture);
        assertTask(taskId, fixture.runName(), true);
        assertThat(pollableTaskBlobStorage.getInputJson(taskId)).isEqualTo(originalInput);
        assertThat(fixture.input().getContent()).isEqualTo(fixture.sourceXliff());
        assertThat(fixture.input().getBcp47Tag()).isNull();
        LocalizedAssetBody output =
            pollableTaskBlobStorage.getOutput(taskId, LocalizedAssetBody.class);
        assertThat(output.getContent()).isEqualTo(outputStorage.contents.get(1));
        assertThat(output.getBcp47Tag()).isEqualTo("fr-FR");
        assertThat(store.countByStatus(QUEUE).stream().mapToLong(AsyncJobStatusCount::count).sum())
            .isEqualTo(1);
        assertThat(store.findByStatus(QUEUE, AsyncJobStatus.DONE, 10))
            .extracting(AsyncJobRecord::id)
            .containsExactly(originalJob.id());
        if (writeBeforeFailure) {
          AssetLocalizeAsyncJobPayload orphan = outputStorage.saved.getFirst();
          assertThat(orphan.outputId()).isNotEqualTo(winner.outputId());
          LocalizedAssetBody orphanOutput =
              objectMapper.readValueUnchecked(
                  structuredBlobStorage.getString(POLLABLE_TASK, outputName(orphan)).orElseThrow(),
                  LocalizedAssetBody.class);
          assertThat(orphanOutput.getContent()).isEqualTo(outputStorage.contents.get(0));
          assertThat(output.getContent()).doesNotContain("Accueil");
        }
      } finally {
        runtime.stop();
      }
    } finally {
      try {
        executor.shutdown();
        if (!workerPool.isTerminated()) {
          workerPool.shutdownNow();
        }
        assertThat(workerPool.awaitTermination(10, TimeUnit.SECONDS))
            .as("lineage worker terminated before releasing dependent resources")
            .isTrue();
      } finally {
        metrics.close();
      }
    }
    assertNoTransaction();
  }

  private Fixture createFixture(String tag) throws Exception {
    String runName = "runtime-lineage-" + UUID.randomUUID();
    Repository repository = repositoryService.createRepository(runName);
    repositoryService.addRepositoryLocale(repository, "fr-FR");
    long localeId =
        repositoryLocaleRepository
            .findByRepositoryAndLocale_Bcp47Tag(repository, "fr-FR")
            .getLocale()
            .getId();
    Asset asset =
        assetService.createAssetWithContent(
            repository.getId(), "runtime-lineage.xliff", "fixture content");
    TMTextUnit unit =
        tmService.addTMTextUnit(
            repository.getTm().getId(), asset.getId(), "home", "Home", "Navigation label");
    TMTextUnitVariant variant =
        tmService.addCurrentTMTextUnitVariant(unit.getId(), localeId, "Accueil");
    String source =
        xliffDataFactory.generateSourceXliff(
            List.of(
                xliffDataFactory.createTextUnit(
                    unit.getId(), unit.getName(), unit.getContent(), unit.getComment())));
    LocalizedAssetBody input = new LocalizedAssetBody();
    input.setAssetId(asset.getId());
    input.setLocaleId(localeId);
    input.setContent(source);
    input.setOutputBcp47tag(tag);
    input.setStatus(Status.ALL);
    input.setInheritanceMode(InheritanceMode.USE_PARENT);
    return new Fixture(repository, asset, unit, variant, localeId, runName, source, input);
  }

  private void assertNoPullRun(Fixture fixture) {
    assertThat(
            jdbc.queryForList(
                "SELECT id FROM pull_run WHERE repository_id = ?",
                Long.class,
                fixture.repository().getId()))
        .isEmpty();
  }

  private void assertTrackedJobRejected(AsyncJobStore store) throws Exception {
    assertNoTransaction();
    Fixture fixture = createFixture("fr-FR");
    fixture.input().setPullRunName(fixture.runName());
    String originalInput = objectMapper.writeValueAsStringUnchecked(fixture.input());
    generationService.generate(
        objectMapper.readValueUnchecked(originalInput, LocalizedAssetBody.class));
    Lineage originalLineage = assertLineage(fixture, fixture.variant().getId());
    TMTextUnitVariant updated =
        tmService.addCurrentTMTextUnitVariant(
            fixture.textUnit().getId(), fixture.localeId(), "Page principale");
    assertThat(updated.getId()).isNotEqualTo(fixture.variant().getId());
    // Model a row accepted by an older producer, not a bypass in the current submission adapter.
    PollableTask task =
        pollableTaskService.createPollableTask(
            null, GenerateLocalizedAssetJob.class.getCanonicalName(), fixture.runName(), 0);
    pollableTaskBlobStorage.saveInput(task.getId(), fixture.input());
    String jobData =
        objectMapper.writeValueAsStringUnchecked(new AssetLocalizeAsyncJobPayload(task.getId()));
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("tracked-rejection-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationMillis(5_000);
    executor.initialize();
    ThreadPoolExecutor workerPool = executor.getThreadPoolExecutor();
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    LocalizedAssetGenerationService unusedGeneration = spy(generationService);
    AssetLocalizeAsyncJobOutputStorage unusedOutput =
        spy(
            new AssetLocalizeAsyncJobOutputStorage(
                structuredBlobStorage, pollableTaskBlobStorage, objectMapper));
    AsyncJobQueueRuntime runtime =
        new AsyncJobQueueRuntime(
            QUEUE,
            store,
            settings(),
            new AssetLocalizeAsyncJobHandler(
                pollableTaskService,
                pollableTaskBlobStorage,
                pollableTaskExceptionUtils,
                unusedGeneration,
                objectMapper,
                metrics,
                unusedOutput),
            mock(TaskScheduler.class),
            executor,
            metrics,
            "tracked-rejection-worker");
    try {
      new AsyncJobQueueSubmissionService(store, mock(AsyncJobQueueCoordinator.class), metrics)
          .enqueueNow(QUEUE, jobData);
      AsyncJobRecord queued = store.findByStatus(QUEUE, AsyncJobStatus.QUEUED, 10).getFirst();
      AsyncJobRecord failed =
          awaitState(store, queued.id(), runtime, AsyncJobStatus.FAILED, 1, true);
      assertThat(failed.jobData()).isEqualTo(jobData);
      assertThat(failed.lastError()).containsIgnoringCase("pull-run");
      PollableTask finished = pollableTaskService.getPollableTask(task.getId());
      assertThat(finished.getFinishedDate()).isNotNull();
      assertThat(objectMapper.readTree(finished.getErrorMessage()).path("type").asText())
          .isEqualTo("unexpected");
      assertThat(objectMapper.readTree(finished.getErrorMessage()).path("expected").asBoolean())
          .isFalse();
      assertThat(finished.getErrorStack())
          .contains("Asset localize async job failed permanently: " + failed.id().value())
          .doesNotContainIgnoringCase("pull-run");
      assertThat(pollableTaskBlobStorage.getInputJson(task.getId())).isEqualTo(originalInput);
      assertThat(pollableTaskBlobStorage.findOutputJson(task.getId())).isEmpty();
      assertThat(assertLineage(fixture, fixture.variant().getId())).isEqualTo(originalLineage);
      verifyNoInteractions(unusedGeneration, unusedOutput);
      assertThat(store.countByStatus(QUEUE).stream().mapToLong(AsyncJobStatusCount::count).sum())
          .isEqualTo(1);
    } finally {
      try {
        runtime.stop();
      } finally {
        try {
          executor.shutdown();
          if (!workerPool.isTerminated()) {
            workerPool.shutdownNow();
          }
          assertThat(workerPool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
          metrics.close();
        }
      }
    }
    assertNoTransaction();
  }

  private Lineage assertLineage(Fixture fixture, long variantId) {
    assertNoTransaction();
    List<Long> runs =
        jdbc.queryForList(
            "SELECT id FROM pull_run WHERE repository_id = ? AND name = ?",
            Long.class,
            fixture.repository().getId(),
            fixture.runName());
    assertThat(runs).hasSize(1);
    List<Long> assets =
        jdbc.queryForList(
            "SELECT id FROM pull_run_asset WHERE pull_run_id = ? AND asset_id = ?",
            Long.class,
            runs.getFirst(),
            fixture.asset().getId());
    assertThat(assets).hasSize(1);
    List<Association> associations =
        jdbc.query(
            "SELECT locale_id, tm_text_unit_variant_id, output_bcp47_tag FROM pull_run_text_unit_variant WHERE pull_run_asset_id = ?",
            (row, index) -> new Association(row.getLong(1), row.getLong(2), row.getString(3)),
            assets.getFirst());
    assertThat(associations)
        .extracting(Association::tag)
        .containsExactly(fixture.input().getOutputBcp47tag());
    assertThat(associations)
        .containsExactly(
            new Association(fixture.localeId(), variantId, fixture.input().getOutputBcp47tag()));
    return new Lineage(runs.getFirst(), assets.getFirst());
  }

  private void assertTask(long taskId, String message, boolean finished) {
    assertThat(
            jdbc.queryForList(
                "SELECT id FROM pollable_task WHERE message = ?", Long.class, message))
        .containsExactly(taskId);
    PollableTask task = pollableTaskService.getPollableTask(taskId);
    assertThat(task.getFinishedDate() != null).isEqualTo(finished);
    assertThat(task.getErrorMessage()).isNull();
    assertThat(task.getErrorStack()).isNull();
  }

  private AsyncJobRecord awaitState(
      AsyncJobStore store,
      AsyncJobId id,
      AsyncJobQueueRuntime runtime,
      AsyncJobStatus status,
      int attempts,
      boolean poll)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    AsyncJobRecord record;
    do {
      if (poll) {
        runtime.pollOnce();
      }
      record = store.getByIds(List.of(id)).getFirst();
      if (record.status() == status
          && record.attemptCount() == attempts
          && runtime.inFlightCount() == 0) {
        return record;
      }
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    throw new AssertionError(
        "Timed out waiting for " + status + " attempt " + attempts + ": " + record);
  }

  private AssetLocalizeAsyncJobPayload payload(AsyncJobRecord record) {
    return objectMapper.readValueUnchecked(record.jobData(), AssetLocalizeAsyncJobPayload.class);
  }

  private static void assertNoTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  private static String outputName(AssetLocalizeAsyncJobPayload payload) {
    return payload.pollableTaskId() + "/assetlocalize/" + payload.outputId() + "/output";
  }

  private static AsyncJobQueueProperties.QueueSettings settings() {
    AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setClaimBatchSize(1);
    settings.setMaxConcurrency(1);
    settings.setMaxAttempts(2);
    settings.setPollIntervalMs(1);
    settings.setRetryJitterPercent(0);
    settings.setHeartbeatIntervalMs(0);
    settings.setLeaseDurationMs(30_000);
    settings.setShutdownAwaitTerminationMs(5_000);
    return settings;
  }

  private final class FailingOutputStorage extends AssetLocalizeAsyncJobOutputStorage {
    private final boolean writeBeforeFailure;
    private final List<String> contents = new CopyOnWriteArrayList<>();
    private final List<AssetLocalizeAsyncJobPayload> saved = new CopyOnWriteArrayList<>();

    private FailingOutputStorage(boolean writeBeforeFailure) {
      super(
          AssetLocalizeAsyncJobOutputRetryIntegrationTest.this.structuredBlobStorage,
          AssetLocalizeAsyncJobOutputRetryIntegrationTest.this.pollableTaskBlobStorage,
          AssetLocalizeAsyncJobOutputRetryIntegrationTest.this.objectMapper);
      this.writeBeforeFailure = writeBeforeFailure;
    }

    @Override
    public AssetLocalizeAsyncJobPayload saveAttemptOutput(Long taskId, LocalizedAssetBody output) {
      assertNoTransaction();
      contents.add(output.getContent());
      boolean first = contents.size() == 1;
      if (first && !writeBeforeFailure) {
        throw new IllegalStateException(STORAGE_FAILURE);
      }
      AssetLocalizeAsyncJobPayload result = super.saveAttemptOutput(taskId, output);
      saved.add(result);
      if (first) {
        throw new IllegalStateException(STORAGE_FAILURE);
      }
      return result;
    }
  }

  private record Fixture(
      Repository repository,
      Asset asset,
      TMTextUnit textUnit,
      TMTextUnitVariant variant,
      long localeId,
      String runName,
      String sourceXliff,
      LocalizedAssetBody input) {}

  private record Lineage(long runId, long runAssetId) {}

  private record Association(long localeId, long variantId, String tag) {}
}
