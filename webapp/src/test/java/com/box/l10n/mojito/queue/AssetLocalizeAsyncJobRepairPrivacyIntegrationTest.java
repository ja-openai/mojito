package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.admin.AssetLocalizeAsyncJobRepairWS;
import com.box.l10n.mojito.rest.admin.AsyncJobQueueAdminWS;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobHandler;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobOutputStorage;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobPayload;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizePollableTaskLookupException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizePollableTaskRepairException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobSubmissionService;
import com.box.l10n.mojito.service.tm.GenerateLocalizedAssetJob;
import com.box.l10n.mojito.service.tm.LocalizedAssetGenerationService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityNotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** Real task persistence and HTTP serialization, with an in-memory queue and no generation. */
public class AssetLocalizeAsyncJobRepairPrivacyIntegrationTest extends ServiceTestBase {

  private static final String QUEUE = AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME;
  private static final String PRIVATE_MARKER = "private-queue-diagnostic-not-for-task-clients";
  private static final String LAST_ERROR =
      "java.sql.SQLException: " + PRIVATE_MARKER + "\nCaused by: source=\"confidential input\"";

  @Autowired PollableTaskService pollableTaskService;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired WebApplicationContext webApplicationContext;
  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired PlatformTransactionManager transactionManager;

  private final InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final AssetLocalizeAsyncJobOutputStorage output =
      mock(AssetLocalizeAsyncJobOutputStorage.class);

  @After
  public void closeMetrics() {
    metrics.close();
  }

  @Test
  public void failedRepairKeepsDiagnosticsOutOfTaskAndInspectionResponses() throws Exception {
    assertFailedRepair(false);
  }

  @Test
  public void lostTaskFinishAcknowledgementKeepsGenericFailureAndRepairIsIdempotent()
      throws Exception {
    assertFailedRepair(true);
  }

  @Test
  public void committedRepairSurvivesMetricCollisionAndBrokenLogging() throws Exception {
    assertFailedRepairWithBrokenLogging(false);
  }

  @Test
  public void lostFinishAcknowledgementSurvivesMetricCollisionAndBrokenLogging() throws Exception {
    assertFailedRepairWithBrokenLogging(true);
  }

  private void assertFailedRepairWithBrokenLogging(boolean loseFinishAcknowledgement)
      throws Exception {
    Gauge.builder("assetLocalizeAsyncJob.repair", () -> 1.0)
        .tags(
            "queueName",
            QUEUE,
            "status",
            "failed",
            "result",
            loseFinishAcknowledgement ? "finishFailed" : "repaired")
        .register(metrics);
    Logger logger = (Logger) LoggerFactory.getLogger(AssetLocalizeAsyncJobRepairService.class);
    Level originalLevel = logger.getLevel();
    @SuppressWarnings("unchecked")
    Appender<ILoggingEvent> appender = mock(Appender.class);
    AtomicInteger attemptedLogs = new AtomicInteger();
    doAnswer(
            invocation -> {
              attemptedLogs.incrementAndGet();
              throw new AssertionError("repair appender failed");
            })
        .when(appender)
        .doAppend(any(ILoggingEvent.class));
    logger.setLevel(Level.INFO);
    logger.addAppender(appender);
    try {
      // Reuse real task persistence, response redaction and already-finished row checks.
      assertFailedRepair(loseFinishAcknowledgement);
      assertThat(attemptedLogs.get()).isPositive();
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(originalLevel);
    }
  }

  @Test
  public void repairDoesNotRewriteHistoricalFinishedTaskErrors() {
    PollableTask task = newTask();
    ExceptionHolder historical = new ExceptionHolder(task);
    historical.setExpected(true);
    historical.setException(new Exception("historical task error"));
    pollableTaskService.finishTask(task.getId(), null, historical, null);
    var before = jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", task.getId());
    AsyncJobRecord failed = failedJob(task);

    assertThat(repair(pollableTaskService).repairTerminalPollableTask(failed.id().value()).result())
        .isEqualTo("alreadyFinished");

    assertThat(jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", task.getId()))
        .isEqualTo(before);
    assertThat(store.getByIds(List.of(failed.id()))).containsExactly(failed);
    verifyNoInteractions(output);
  }

  @Test
  public void failedRepairPreservesTaskFinishedAfterRequestCachedIt() throws Exception {
    assertRepairPreservesConcurrentlyFinishedTask(false);
  }

  @Test
  public void doneRepairDoesNotPublishAfterRequestCachedAnOpenTask() throws Exception {
    assertRepairPreservesConcurrentlyFinishedTask(true);
  }

  @Test
  public void freshReadReturnsNullForMissingTask() {
    assertThat(pollableTaskService.getFreshPollableTask(Long.MAX_VALUE)).isNull();
  }

  @Test
  public void deletedCachedTaskFailsRepairWithoutPublishingOrLeakingTransaction() {
    PollableTask task = newTask();
    AsyncJobRecord terminal = terminalJob(task, true);
    assertThat(TransactionSynchronizationManager.hasResource(entityManagerFactory)).isFalse();
    var requestEntityManager = entityManagerFactory.createEntityManager();
    var holder = new EntityManagerHolder(requestEntityManager);
    TransactionSynchronizationManager.bindResource(entityManagerFactory, holder);
    try {
      PollableTask cached = pollableTaskService.getPollableTask(task.getId());
      assertThat(requestEntityManager.contains(cached)).isTrue();
      var originalFlushMode = requestEntityManager.getFlushMode();
      assertThat(TransactionSynchronizationManager.hasResource(jdbc.getDataSource())).isFalse();
      // Commit a real deletion outside the request's persistence context, not a mocked refresh.
      assertThat(jdbc.update("DELETE FROM pollable_task WHERE id = ?", task.getId())).isOne();
      assertThat(requestEntityManager.contains(cached)).isTrue();

      assertThatThrownBy(() -> repair(pollableTaskService).repairTerminalPollableTask(terminal))
          .isExactlyInstanceOf(AssetLocalizePollableTaskLookupException.class)
          .hasCauseInstanceOf(EntityNotFoundException.class);

      verifyNoInteractions(output);
      assertThat(store.getByIds(List.of(terminal.id()))).containsExactly(terminal);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM pollable_task WHERE id = ?", Long.class, task.getId()))
          .isZero();
      assertThat(TransactionSynchronizationManager.getResource(entityManagerFactory))
          .isSameAs(holder);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
      assertThat(TransactionSynchronizationManager.hasResource(jdbc.getDataSource())).isFalse();
      assertThat(requestEntityManager.isOpen()).isTrue();
      assertThat(requestEntityManager.getTransaction().isActive()).isFalse();
      assertThat(requestEntityManager.getFlushMode()).isEqualTo(originalFlushMode);
    } finally {
      TransactionSynchronizationManager.unbindResource(entityManagerFactory);
      requestEntityManager.close();
    }
  }

  @Test
  public void doneRepairPersistsCompletionInRequestPersistenceContext() {
    assertRepairCompletesOpenTaskInRequest(true);
  }

  @Test
  public void failedRepairPersistsCompletionInRequestPersistenceContext() {
    assertRepairCompletesOpenTaskInRequest(false);
  }

  private void assertRepairCompletesOpenTaskInRequest(boolean done) {
    PollableTask task = newTask();
    AsyncJobRecord terminal = terminalJob(task, done);
    assertThat(TransactionSynchronizationManager.hasResource(entityManagerFactory)).isFalse();
    var requestEntityManager = entityManagerFactory.createEntityManager();
    TransactionSynchronizationManager.bindResource(
        entityManagerFactory, new EntityManagerHolder(requestEntityManager));
    try {
      var repair = repair(pollableTaskService);
      assertThat(repair.repairTerminalPollableTask(terminal).result()).isEqualTo("repaired");
      var persisted = jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", task.getId());
      assertThat(persisted.get("finished_date")).isNotNull();
      assertThat(persisted.get("error_message") == null).isEqualTo(done);
      assertThat(repair.repairTerminalPollableTask(terminal).result()).isEqualTo("alreadyFinished");
      assertThat(jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", task.getId()))
          .isEqualTo(persisted);
      if (done) {
        org.mockito.Mockito.verify(output)
            .publishOutput(new AssetLocalizeAsyncJobPayload(task.getId()));
      } else {
        verifyNoInteractions(output);
      }
    } finally {
      TransactionSynchronizationManager.unbindResource(entityManagerFactory);
      requestEntityManager.close();
    }
  }

  @Test
  public void freshReadSuspendsCallerWithoutFlushingOrDiscardingItsChanges() {
    PollableTask task = newTask();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              var holder =
                  (EntityManagerHolder)
                      TransactionSynchronizationManager.getResource(entityManagerFactory);
              PollableTask callerTask =
                  holder.getEntityManager().find(PollableTask.class, task.getId());
              callerTask.setMessage("uncommitted caller change");

              PollableTask fresh = pollableTaskService.getFreshPollableTask(task.getId());

              assertThat(fresh).isNotSameAs(callerTask);
              assertThat(fresh.getMessage()).isEqualTo(task.getMessage());
              assertThat(callerTask.getMessage()).isEqualTo("uncommitted caller change");
              assertThat(holder.getEntityManager().contains(callerTask)).isTrue();
              assertThat(TransactionSynchronizationManager.getResource(entityManagerFactory))
                  .isSameAs(holder);
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              status.setRollbackOnly();
            });
    assertThat(pollableTaskService.getPollableTask(task.getId()).getMessage())
        .isEqualTo(task.getMessage());
    assertThat(TransactionSynchronizationManager.hasResource(entityManagerFactory)).isFalse();
  }

  private void assertRepairPreservesConcurrentlyFinishedTask(boolean done) throws Exception {
    PollableTask task = newTask();
    AsyncJobRecord terminal = terminalJob(task, done);

    assertThat(TransactionSynchronizationManager.hasResource(entityManagerFactory)).isFalse();
    var requestEntityManager = entityManagerFactory.createEntityManager();
    var holder = new EntityManagerHolder(requestEntityManager);
    TransactionSynchronizationManager.bindResource(entityManagerFactory, holder);
    try {
      // Match OpenEntityManagerInView: reuse one persistence context across request transactions.
      PollableTask cached = pollableTaskService.getPollableTask(task.getId());
      assertThat(requestEntityManager.contains(cached)).isTrue();
      assertThat(cached.getFinishedDate()).isNull();
      var worker = Executors.newSingleThreadExecutor();
      try {
        worker
            .submit(
                () -> {
                  ExceptionHolder timeout = new ExceptionHolder(task);
                  timeout.setExpected(true);
                  timeout.setException(new Exception("task finished by another transaction"));
                  pollableTaskService.finishTask(task.getId(), null, timeout, null);
                })
            .get(10, TimeUnit.SECONDS);
      } finally {
        worker.shutdownNow();
        assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
      assertThat(cached.getFinishedDate()).isNull();
      var before = jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", task.getId());

      assertThat(repair(pollableTaskService).repairTerminalPollableTask(terminal).result())
          .isEqualTo("alreadyFinished");

      assertThat(jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", task.getId()))
          .isEqualTo(before);
      assertThat(store.getByIds(List.of(terminal.id()))).containsExactly(terminal);
      verifyNoInteractions(output);
      assertThat(TransactionSynchronizationManager.getResource(entityManagerFactory))
          .isSameAs(holder);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    } finally {
      TransactionSynchronizationManager.unbindResource(entityManagerFactory);
      requestEntityManager.close();
    }
  }

  @Test
  public void malformedInputCallbackPersistsUnexpectedErrorAndRawReplayCannotReopenIt()
      throws Exception {
    PollableTask task = newTask();
    String jobData =
        objectMapper.writeValueAsStringUnchecked(new AssetLocalizeAsyncJobPayload(task.getId()));
    AsyncJobId id = store.enqueueNow(QUEUE, jobData);
    AsyncJobRecord claim =
        store.claimNextJobs(QUEUE, 1, "worker", Duration.ofSeconds(30)).getFirst();
    var inputs = mock(PollableTaskBlobStorage.class);
    var generation = mock(LocalizedAssetGenerationService.class);
    org.mockito.Mockito.when(inputs.getInputBytes(task.getId()))
        .thenReturn(
            ("{\"content\":\"" + PRIVATE_MARKER + "\",\"bcp47Tag\":")
                .getBytes(StandardCharsets.UTF_8));
    var handler =
        new AssetLocalizeAsyncJobHandler(
            pollableTaskService,
            inputs,
            new PollableTaskExceptionUtils(),
            generation,
            objectMapper,
            metrics,
            output);
    var failure =
        org.junit.Assert.assertThrows(
            AsyncJobPermanentFailureException.class, () -> handler.process(claim));
    assertThat(
            store.markFailed(
                QUEUE, id, claim.workerId(), claim.leaseToken(), null, failure.toString()))
        .isTrue();

    handler.onJobFailedPermanently(claim, failure, failure.toString());

    PollableTask finished = pollableTaskService.getPollableTask(task.getId());
    assertThat(finished.getFinishedDate()).isNotNull();
    assertThat(objectMapper.readTreeUnchecked(finished.getErrorMessage()))
        .isEqualTo(
            objectMapper.readTreeUnchecked(
                "{\"expected\":false,\"message\":\"An unexpected error happened, task="
                    + task.getId()
                    + "\",\"type\":\"unexpected\"}"));
    assertThat(finished.getErrorStack())
        .contains("Asset localize async job failed permanently: " + id.value())
        .doesNotContain(PRIVATE_MARKER);
    var mvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    assertThat(response(mvc, "/api/pollableTasks/" + task.getId())).doesNotContain(PRIVATE_MARKER);
    String inspection = response(mvc, "/api/pollableTasks/" + task.getId() + "/inspection");
    assertThat(inspection).doesNotContain(PRIVATE_MARKER);
    assertThat(objectMapper.readTreeUnchecked(inspection).path("status").asText())
        .isEqualTo("FAILED");
    var terminal = jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", task.getId());

    // A repaired input cannot implicitly reopen the finished business task on raw queue replay.
    org.mockito.Mockito.when(inputs.getInputBytes(task.getId()))
        .thenReturn("{}".getBytes(StandardCharsets.UTF_8));
    assertThat(store.requeueFailedNow(QUEUE, id, jobData)).isTrue();
    AsyncJobRecord replay =
        store.claimNextJobs(QUEUE, 1, "worker", Duration.ofSeconds(30)).getFirst();
    assertThatThrownBy(() -> handler.process(replay))
        .isExactlyInstanceOf(AsyncJobPermanentFailureException.class)
        .hasMessageContaining("cannot execute finished pollable task");
    org.mockito.Mockito.verify(inputs).getInputBytes(task.getId());
    verifyNoInteractions(generation, output);
    assertThat(jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", task.getId()))
        .isEqualTo(terminal);
  }

  private void assertFailedRepair(boolean loseFinishAcknowledgement) throws Exception {
    PollableTask task = newTask();
    AsyncJobRecord failed = failedJob(task);
    PollableTaskService completion = pollableTaskService;
    if (loseFinishAcknowledgement) {
      completion = mock(PollableTaskService.class, delegatesTo(pollableTaskService));
      doAnswer(
              invocation -> {
                // Service-boundary fault after a committed task update, not an in-JDBC fault.
                pollableTaskService.finishTask(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3));
                throw new IllegalStateException("injected task-finish acknowledgement loss");
              })
          .when(completion)
          .finishTask(eq(task.getId()), isNull(), any(ExceptionHolder.class), isNull());
      AssetLocalizeAsyncJobRepairService repair = repair(completion);
      assertThatThrownBy(() -> repair.repairTerminalPollableTask(failed.id().value()))
          .isInstanceOf(AssetLocalizePollableTaskRepairException.class)
          .hasRootCauseMessage("injected task-finish acknowledgement loss");
    } else {
      var response =
          new AssetLocalizeAsyncJobRepairWS(repair(completion))
              .repairPollableTask(failed.id().value());
      assertThat(response.result()).isEqualTo("repaired");
      assertThat(response.status()).isEqualTo("failed");
      assertThat(objectMapper.writeValueAsStringUnchecked(response)).doesNotContain(PRIVATE_MARKER);
    }

    MockMvc mvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    String taskResponse = response(mvc, "/api/pollableTasks/" + task.getId());
    String inspectionResponse = response(mvc, "/api/pollableTasks/" + task.getId() + "/inspection");
    assertThat(List.of(taskResponse, inspectionResponse))
        .allSatisfy(
            response ->
                assertThat(response)
                    .doesNotContain(PRIVATE_MARKER, "java.sql.SQLException", "confidential input"));
    PollableTask finished = pollableTaskService.getPollableTask(task.getId());
    assertThat(finished.getFinishedDate()).isNotNull();
    assertThat(objectMapper.readTreeUnchecked(finished.getErrorMessage()))
        .isEqualTo(
            objectMapper.readTreeUnchecked(
                "{\"expected\":false,\"message\":\"An unexpected error happened, task="
                    + task.getId()
                    + "\",\"type\":\"unexpected\"}"));
    assertThat(finished.getErrorStack())
        .contains("Asset localize async job failed permanently: " + failed.id().value())
        .doesNotContain(PRIVATE_MARKER, "java.sql.SQLException", "confidential input");
    assertThat(
            objectMapper
                .readTreeUnchecked(taskResponse)
                .path("errorMessage")
                .path("expected")
                .asBoolean(true))
        .isFalse();
    assertThat(objectMapper.readTreeUnchecked(inspectionResponse).path("status").asText())
        .isEqualTo("FAILED");

    // The queue row and operator inspection retain the original diagnostic, not the task copy.
    assertThat(store.getByIds(List.of(failed.id()))).containsExactly(failed);
    AsyncJobQueueAdminWS admin =
        new AsyncJobQueueAdminWS(
            new AsyncJobQueueInspectionService(
                store, mock(AsyncJobQueueCoordinator.class), metrics));
    assertThat(admin.getJob(QUEUE, failed.id().value()).lastError()).isEqualTo(LAST_ERROR);
    var terminal = jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", task.getId());
    assertThat(repair(pollableTaskService).repairTerminalPollableTask(failed.id().value()).result())
        .isEqualTo("alreadyFinished");
    assertThat(jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", task.getId()))
        .isEqualTo(terminal);
    verifyNoInteractions(output);
  }

  private PollableTask newTask() {
    return pollableTaskService.createPollableTask(
        null, GenerateLocalizedAssetJob.class.getCanonicalName(), "repair privacy", 0, 3600);
  }

  private String response(MockMvc mvc, String path) throws Exception {
    return mvc.perform(get(path).with(user("repair-reader").roles("TRANSLATOR")))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private AsyncJobRecord failedJob(PollableTask task) {
    return terminalJob(task, false);
  }

  private AsyncJobRecord terminalJob(PollableTask task, boolean done) {
    AsyncJobId id =
        store.enqueueNow(
            QUEUE,
            objectMapper.writeValueAsStringUnchecked(
                new AssetLocalizeAsyncJobPayload(task.getId())));
    AsyncJobRecord claim =
        store.claimNextJobs(QUEUE, 1, "worker", Duration.ofSeconds(30)).getFirst();
    if (done) {
      assertThat(store.markDone(QUEUE, id, claim.workerId(), claim.leaseToken(), null)).isTrue();
    } else {
      assertThat(
              store.markFailed(QUEUE, id, claim.workerId(), claim.leaseToken(), null, LAST_ERROR))
          .isTrue();
    }
    return store.getByIds(List.of(id)).getFirst();
  }

  private AssetLocalizeAsyncJobRepairService repair(PollableTaskService completion) {
    return new AssetLocalizeAsyncJobRepairService(store, completion, metrics, output);
  }
}
