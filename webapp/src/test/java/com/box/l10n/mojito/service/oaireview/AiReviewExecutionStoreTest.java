package com.box.l10n.mojito.service.oaireview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.box.l10n.mojito.entity.MBlob;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatMessage;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatResponse;
import com.box.l10n.mojito.service.DBUtils;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.blobstorage.database.MBlobRepository;
import com.box.l10n.mojito.service.oaireview.AiReviewCapacityStore.Admission;
import com.box.l10n.mojito.service.oaireview.AiReviewExecutionStore.Claim;
import com.box.l10n.mojito.service.oaireview.AiReviewExecutionStore.Disposition;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskRepository;
import com.box.l10n.mojito.service.security.user.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Exercises independent worker instances against the same real database transactions. */
@TestPropertySource(properties = "l10n.org.quartz.scheduler.enabled=false")
public class AiReviewExecutionStoreTest extends ServiceTestBase {
  @Autowired MBlobRepository blobs;
  @Autowired PollableTaskRepository tasks;
  @Autowired UserRepository users;
  @Autowired DataSource dataSource;
  @Autowired DBUtils dbUtils;
  @Autowired PlatformTransactionManager transactions;
  @Autowired EntityManagerFactory entityManagerFactory;
  @PersistenceContext EntityManager entityManager;
  // Keep the real scheduled sweeper from racing the deliberately controlled test clock.
  @MockitoBean AiReviewDispatchService backgroundDispatcher;

  private final ObjectMapper mapper = ObjectMapper.withNoFailOnUnknownProperties();
  private final List<Long> createdTasks = new ArrayList<>();
  private final List<Long> createdUsers = new ArrayList<>();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-09-10T22:00:00Z"));
  private AiReviewExecutionProperties configuration;
  private PollableTaskBlobStorage outputs;
  private AiReviewExecutionStore store;

  @Before
  public void setUpExecutionStore() {
    removeCapacity();
    clock.set(Instant.parse("2026-09-10T22:00:00Z"));
    configuration = new AiReviewExecutionProperties();
    configuration.setMaxInFlight(1);
    configuration.setTimeoutSeconds(180);
    outputs = mock(PollableTaskBlobStorage.class);
    store = worker(outputs);
  }

  @After
  public void removeExecutionRecords() {
    removeCapacity();
    tasks.deleteAllById(createdTasks.reversed());
    createdTasks.clear();
    users.deleteAllById(createdUsers);
    createdUsers.clear();
  }

  @Test
  public void defaultsAllowFourHundredAcrossTheClusterAndThreePerUser() {
    AiReviewExecutionProperties defaults = new AiReviewExecutionProperties();
    assertEquals(400, defaults.getMaxInFlight());
    assertEquals(3, defaults.getMaxInFlightPerUser());
    assertThrows(IllegalArgumentException.class, () -> defaults.setMaxInFlightPerUser(0));
    assertThrows(IllegalArgumentException.class, () -> defaults.setMaxInFlightPerUser(1001));
  }

  @Test
  public void threeRequestsForOneUserDoNotBlockAnotherUserAcrossProcesses() {
    configuration.setMaxInFlight(400);
    long firstUser = user();
    long otherUser = user();
    AiReviewExecutionStore otherProcess = worker(outputs);
    for (int i = 0; i < 3; i++) {
      long taskId = taskForUser(firstUser);
      Claim claim = (i % 2 == 0 ? store : otherProcess).tryClaim(taskId, "process-" + i);
      assertEquals(Disposition.START, claim.disposition());
      assertEquals(Long.valueOf(firstUser), capacity().reservations().get(claim.token()).userId());
    }

    assertEquals(
        Disposition.WAIT,
        worker(outputs).tryClaim(taskForUser(firstUser), "new-process").disposition());
    assertEquals(
        Disposition.START,
        worker(outputs).tryClaim(taskForUser(otherUser), "new-process").disposition());
    assertEquals(4, reservations());
  }

  @Test
  public void allUsersStillShareTheGlobalLimit() {
    configuration.setMaxInFlight(2);
    assertEquals(
        Disposition.START, store.tryClaim(taskForUser(user()), "first-process").disposition());
    assertEquals(
        Disposition.START,
        worker(outputs).tryClaim(taskForUser(user()), "second-process").disposition());

    assertEquals(
        Disposition.WAIT,
        worker(outputs).tryClaim(taskForUser(user()), "third-process").disposition());
    assertEquals(2, reservations());
  }

  @Test
  public void aUserSlotStaysReservedForCancelledTransportAndIsReusableAfterCompletionOrDeadline() {
    configuration.setMaxInFlight(400);
    configuration.setMaxInFlightPerUser(1);
    long userId = user();
    long firstTask = taskForUser(userId);
    Claim first = store.tryClaim(firstTask, "first-process");
    store.cancel(firstTask);
    store.finishStaged(firstTask);
    long nextTask = taskForUser(userId);
    assertEquals(
        Disposition.WAIT, worker(outputs).tryClaim(nextTask, "second-process").disposition());
    assertEquals(
        Disposition.START,
        worker(outputs).tryClaim(taskForUser(user()), "other-user-process").disposition());

    store.stageResult(firstTask, first.token(), result("Cancelled transport settled"));
    store.releaseCapacity(first.token());
    Claim next = worker(outputs).tryClaim(nextTask, "second-process");
    assertEquals(Disposition.START, next.disposition());
    store.stageResult(
        nextTask,
        next.token(),
        new AiReviewChatJob.Result(null, new AiReviewChatJob.Error(409, "Cancelled")));
    assertEquals(
        Disposition.WAIT, store.tryClaim(taskForUser(userId), "third-process").disposition());

    clock.advance(Duration.ofSeconds(181));
    assertEquals(
        Disposition.START,
        worker(outputs).tryClaim(taskForUser(userId), "replacement-process").disposition());
  }

  @Test
  public void legacyTaskWithNoDirectOwnerUsesItsAncestorsUserLimit() {
    configuration.setMaxInFlight(400);
    configuration.setMaxInFlightPerUser(1);
    long userId = user();
    long ownedTask = taskForUser(userId);
    assertEquals(Disposition.START, store.tryClaim(ownedTask, "worker").disposition());
    long child = taskForUser(null);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              PollableTask task = entityManager.find(PollableTask.class, child);
              task.setParentTask(entityManager.getReference(PollableTask.class, ownedTask));
              task.setName(AiReviewChatJob.class.getCanonicalName());
            });

    assertEquals(
        Disposition.WAIT, worker(outputs).tryClaim(child, "different-process").disposition());
    assertEquals(1, reservations());
  }

  @Test
  public void ownerlessLegacyTasksShareOneConservativeUserBucket() {
    configuration.setMaxInFlight(400);
    for (int i = 0; i < 3; i++) {
      assertEquals(
          Disposition.START,
          worker(outputs).tryClaim(taskForUser(null), "legacy-process-" + i).disposition());
    }

    assertEquals(
        Disposition.WAIT,
        worker(outputs).tryClaim(taskForUser(null), "fourth-process").disposition());
    assertEquals(
        Disposition.START,
        store.tryClaim(taskForUser(user()), "authenticated-process").disposition());
    assertEquals(4, reservations());
  }

  @Test
  public void mysqlAdmissionReturnsWhileAnotherConnectionStillHoldsTheCapacityLock()
      throws Exception {
    assumeTrue("MySQL-specific NOWAIT semantics", dbUtils.isMysql());
    initializeWorkers(store);
    long taskId = task(0, 3600);
    try (Connection locker = dataSource.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      locker.setAutoCommit(false);
      try {
        try (var statement =
            locker.prepareStatement("select id from mblob where name=? for update")) {
          statement.setString(1, AiReviewExecutionStore.CAPACITY_NAME);
          try (var rows = statement.executeQuery()) {
            assertTrue(rows.next());
          }
        }
        // A held accounting row cannot prevent either an existing or restarted process from
        // claiming a new task. Each bounded write attempt may wait for its one-second timeout.
        for (AiReviewExecutionStore contender : List.of(store, worker(outputs))) {
          long contenderTask = contender == store ? taskId : task(0, 3600);
          Claim claim =
              executor
                  .submit(() -> contender.tryClaim(contenderTask, "contending-process"))
                  .get(8, TimeUnit.SECONDS);
          assertEquals(Disposition.START, claim.disposition());
          assertTrue(contender.isActive(contenderTask, claim.token()));
          Claim duplicate = worker(outputs).tryClaim(contenderTask, "duplicate-process");
          assertEquals(Disposition.WAIT, duplicate.disposition());
          assertEquals(claim.token(), duplicate.token());
        }
      } finally {
        locker.rollback();
      }
    }
    assertEquals(0, reservations());
    assertEquals(Disposition.WAIT, store.tryClaim(taskId, "after-lock-release").disposition());
  }

  @Test
  public void mysqlTaskRowContentionReturnsUnavailableWithoutReservingCapacity() throws Exception {
    assumeTrue("MySQL-specific NOWAIT semantics", dbUtils.isMysql());
    long taskId = task(0, 3600);
    try (Connection locker = dataSource.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      locker.setAutoCommit(false);
      try {
        lockTaskRow(locker, taskId);
        ResponseStatusException error =
            executor
                .submit(
                    () ->
                        assertThrows(
                            ResponseStatusException.class,
                            () -> store.tryClaim(taskId, "contending-process")))
                .get(5, TimeUnit.SECONDS);
        assertEquals(503, error.getStatusCode().value());
      } finally {
        locker.rollback();
      }
    }
    assertEquals(0, reservations());
    assertNull(tasks.findById(taskId).orElseThrow().getMessage());
    assertEquals(Disposition.START, store.tryClaim(taskId, "after-lock-release").disposition());
  }

  @Test
  public void cancellationDuringReservationPreventsStartingAndReleasesProvisionalCapacity() {
    long taskId = task(0, 3600);
    AiReviewCapacityStore capacity = capacityWithAdmissionHook(token -> store.cancel(taskId));
    AiReviewExecutionStore contender = worker(outputs, capacity);

    Claim claim = contender.tryClaim(taskId, "cancelled-process");

    assertEquals(Disposition.FINISH, claim.disposition());
    assertNull(claim.token());
    assertEquals(409, contender.getStagedResult(taskId).orElseThrow().error().status());
    assertEquals(0, reservations());
    verifyNoInteractions(outputs);
  }

  @Test
  public void finishingDuringReservationPreventsStartingAndReleasesProvisionalCapacity() {
    long taskId = task(0, 3600);
    AiReviewCapacityStore capacity =
        capacityWithAdmissionHook(
            token ->
                new TransactionTemplate(transactions)
                    .executeWithoutResult(
                        status ->
                            entityManager
                                .find(PollableTask.class, taskId)
                                .setFinishedDate(ZonedDateTime.now(clock))));
    AiReviewExecutionStore contender = worker(outputs, capacity);

    Claim claim = contender.tryClaim(taskId, "finished-process");

    assertEquals(Disposition.DONE, claim.disposition());
    assertNull(claim.token());
    assertNotNull(tasks.findById(taskId).orElseThrow().getFinishedDate());
    assertEquals(0, reservations());
    verifyNoInteractions(outputs);
  }

  @Test
  public void mysqlTaskClaimFailureReleasesItsProvisionalCapacity() throws Exception {
    assumeTrue("MySQL-specific NOWAIT semantics", dbUtils.isMysql());
    long taskId = task(0, 3600);
    try (Connection locker = dataSource.getConnection()) {
      locker.setAutoCommit(false);
      try {
        AiReviewCapacityStore capacity =
            capacityWithAdmissionHook(
                token -> {
                  try {
                    lockTaskRow(locker, taskId);
                  } catch (java.sql.SQLException exception) {
                    throw new IllegalStateException(exception);
                  }
                });
        AiReviewExecutionStore contender = worker(outputs, capacity);

        ResponseStatusException error =
            assertThrows(
                ResponseStatusException.class,
                () -> contender.tryClaim(taskId, "contending-process"));

        assertEquals(503, error.getStatusCode().value());
        assertEquals(0, reservations());
      } finally {
        locker.rollback();
      }
    }
    assertNull(tasks.findById(taskId).orElseThrow().getMessage());
    assertEquals(Disposition.START, store.tryClaim(taskId, "after-lock-release").disposition());
  }

  @Test
  public void completedResultCanFinishEvenWhenCapacityReleaseMustRetry() {
    AiReviewCapacityStore capacity = spy(capacityStore());
    AiReviewExecutionStore completing = worker(outputs, capacity);
    long taskId = task(0, 3600);
    Claim claim = completing.tryClaim(taskId, "provider-process");
    AiReviewChatJob.Result completed =
        result("The result is durable despite accounting contention");
    doReturn(false).when(capacity).release(anyString(), any(Instant.class));

    completing.stageResult(taskId, claim.token(), completed);

    assertEquals(1, reservations());
    assertEquals(completed, worker(outputs).getStagedResult(taskId).orElseThrow());
    assertTrue(worker(outputs).finishStaged(taskId));
    assertNotNull(tasks.findById(taskId).orElseThrow().getFinishedDate());
    verify(outputs).saveOutput(taskId, completed);
    assertFalse(completing.releaseCapacity(claim.token()));
    assertEquals(1, reservations());
    doCallRealMethod().when(capacity).release(anyString(), any(Instant.class));
    assertTrue(completing.releaseCapacity(claim.token()));
    assertEquals(0, reservations());
    assertEquals(completed, completing.getStagedResult(taskId).orElseThrow());
  }

  @Test
  public void mysqlConcurrentFirstUseCreatesOneCapacityRecordAndOneReservation() throws Exception {
    assumeTrue("MySQL-specific unique insertion race", dbUtils.isMysql());
    long firstTask = task(0, 3600);
    long secondTask = task(0, 3600);
    assertTrue(blobs.findByName(AiReviewExecutionStore.CAPACITY_NAME).isEmpty());

    List<Claim> claims = concurrentClaims(store, firstTask, worker(outputs), secondTask);

    assertEquals(1, claims.stream().filter(c -> c.disposition() == Disposition.START).count());
    assertEquals(1, claims.stream().filter(c -> c.disposition() == Disposition.WAIT).count());
    assertEquals(1, reservations());
    Long rows =
        new TransactionTemplate(transactions)
            .execute(
                status ->
                    entityManager
                        .createQuery(
                            "select count(blob) from MBlob blob where blob.name=:name", Long.class)
                        .setParameter("name", AiReviewExecutionStore.CAPACITY_NAME)
                        .getSingleResult());
    assertEquals(Long.valueOf(1), rows);
  }

  @Test
  public void requestCachedTaskCannotOverwriteAConcurrentTerminalResult() throws Exception {
    long taskId = task(0, 3600);
    Claim claim = store.tryClaim(taskId, "provider-process");
    AiReviewChatJob.Result completed = result("Completed before cancellation");

    try (var request = new RequestEntityManager()) {
      // OpenEntityManagerInView keeps the authorization read managed across transactions.
      PollableTask cached = tasks.findById(taskId).orElseThrow();
      onAnotherThread(
          () -> {
            AiReviewExecutionStore completing = worker(outputs);
            completing.stageResult(taskId, claim.token(), completed);
            completing.releaseCapacity(claim.token());
            return completing.finishStaged(taskId);
          });
      assertNull(cached.getFinishedDate());

      store.cancel(taskId);
    }

    assertNotNull(tasks.findById(taskId).orElseThrow().getFinishedDate());
    assertEquals(completed, store.getStagedResult(taskId).orElseThrow());
  }

  @Test
  public void requestCachedCapacityCannotLoseAnotherProcessesReservation() throws Exception {
    AiReviewExecutionStore otherProcess = worker(outputs);
    initializeWorkers(store, otherProcess);
    long firstTask = task(0, 3600);
    long secondTask = task(0, 3600);
    Claim first;

    try (var request = new RequestEntityManager()) {
      assertEquals(0, reservations());
      first = onAnotherThread(() -> otherProcess.tryClaim(firstTask, "other-process"));
      assertEquals(Disposition.START, first.disposition());
      assertEquals("The request persistence context still has the old blob", 0, reservations());

      assertEquals(Disposition.WAIT, store.tryClaim(secondTask, "request-process").disposition());
    }

    assertEquals(1, reservations());
    assertTrue(capacity().reservations().containsKey(first.token()));
  }

  @Test
  public void requestReadsObserveCancellationCommittedByAnotherProcess() throws Exception {
    long taskId = task(0, 3600);
    Claim claim = store.tryClaim(taskId, "provider-process");

    try (var request = new RequestEntityManager()) {
      assertTrue(store.isActive(taskId, claim.token()));
      onAnotherThread(
          () -> {
            worker(outputs).cancel(taskId);
            return null;
          });

      boolean active = store.isActive(taskId, claim.token());
      var staged = store.getStagedResult(taskId);
      assertFalse(active);
      assertEquals(409, staged.orElseThrow().error().status());
    }
  }

  @Test
  public void independentWorkersCannotExceedSharedCapacity() throws Exception {
    AiReviewExecutionStore secondWorker = worker(outputs);
    initializeWorkers(store, secondWorker);
    long firstTask = task(0, 3600);
    long secondTask = task(0, 3600);

    List<Claim> claims = concurrentClaims(store, firstTask, secondWorker, secondTask);

    assertEquals(1, claims.stream().filter(c -> c.disposition() == Disposition.START).count());
    assertEquals(1, claims.stream().filter(c -> c.disposition() == Disposition.WAIT).count());
    assertEquals(1, reservations());
    MBlob capacity = blobs.findByName(AiReviewExecutionStore.CAPACITY_NAME).orElseThrow();
    assertFalse("Capacity must survive normal blob cleanup", capacity.hasExpiration());
  }

  @Test
  public void duplicateDispatchAndWorkerRestartNeverIssueAnotherAttempt() throws Exception {
    AiReviewExecutionStore secondWorker = worker(outputs);
    initializeWorkers(store, secondWorker);
    long taskId = task(0, 3600);

    List<Claim> claims = concurrentClaims(store, taskId, secondWorker, taskId);
    Claim started =
        claims.stream().filter(c -> c.disposition() == Disposition.START).findFirst().orElseThrow();
    assertEquals(1, claims.stream().filter(c -> c.disposition() == Disposition.START).count());

    Claim recovered = worker(outputs).tryClaim(taskId, "replacement-worker");
    assertEquals(Disposition.WAIT, recovered.disposition());
    assertEquals(started.token(), recovered.token());
    assertEquals(started.deadline(), recovered.deadline());
    assertEquals(1, reservations());
    verifyNoInteractions(outputs);
  }

  @Test
  public void stagedResultSurvivesRestartAndFinishingIsIdempotent() {
    long taskId = task(0, 3600);
    Claim claim = store.tryClaim(taskId, "first-worker");
    AiReviewChatJob.Result result = result("Reviewed translation 😀");

    store.stageResult(taskId, claim.token(), result);
    store.releaseCapacity(claim.token());
    assertEquals(0, reservations());
    assertNull(tasks.findById(taskId).orElseThrow().getFinishedDate());

    AiReviewExecutionStore restarted = worker(outputs);
    assertEquals(result, restarted.getStagedResult(taskId).orElseThrow());
    assertEquals(
        Disposition.FINISH, restarted.tryClaim(taskId, "replacement-worker").disposition());
    assertTrue(restarted.finishStaged(taskId));
    assertNotNull(tasks.findById(taskId).orElseThrow().getFinishedDate());
    assertTrue(restarted.finishStaged(taskId));
    assertEquals(Disposition.DONE, restarted.tryClaim(taskId, "replacement-worker").disposition());
    verify(outputs, atLeastOnce()).saveOutput(taskId, result);
  }

  @Test
  public void failedOutputWriteLeavesCanonicalResultAvailableForAnotherWorker() {
    long taskId = task(0, 3600);
    Claim claim = store.tryClaim(taskId, "first-worker");
    AiReviewChatJob.Result result = result("Durable result");
    store.stageResult(taskId, claim.token(), result);
    store.releaseCapacity(claim.token());
    doThrow(new IllegalStateException("Temporary output storage failure"))
        .when(outputs)
        .saveOutput(taskId, result);

    assertThrows(IllegalStateException.class, () -> store.finishStaged(taskId));
    assertNull(tasks.findById(taskId).orElseThrow().getFinishedDate());

    PollableTaskBlobStorage recoveredOutputs = mock(PollableTaskBlobStorage.class);
    AiReviewExecutionStore recovered = worker(recoveredOutputs);
    assertEquals(result, recovered.getStagedResult(taskId).orElseThrow());
    assertTrue(recovered.finishStaged(taskId));
    verify(recoveredOutputs).saveOutput(taskId, result);
  }

  @Test
  public void cancellationKeepsCapacityUntilMatchingTransportCompletes() {
    long cancelledTask = task(0, 3600);
    long waitingTask = task(0, 3600);
    Claim claim = store.tryClaim(cancelledTask, "first-worker");

    store.cancel(cancelledTask);
    AiReviewChatJob.Result cancellation = store.getStagedResult(cancelledTask).orElseThrow();
    assertNotNull(cancellation.error());
    assertFalse(store.isActive(cancelledTask, claim.token()));
    assertTrue(store.finishStaged(cancelledTask));
    assertEquals(
        Disposition.WAIT, worker(outputs).tryClaim(waitingTask, "other-worker").disposition());
    assertEquals(1, reservations());

    store.stageResult(cancelledTask, "unrelated-token", result("Wrong attempt"));
    store.releaseCapacity("unrelated-token");
    assertEquals(1, reservations());
    store.stageResult(cancelledTask, claim.token(), result("Late provider response"));
    store.releaseCapacity(claim.token());

    assertEquals(cancellation, store.getStagedResult(cancelledTask).orElseThrow());
    assertEquals(0, reservations());
    assertEquals(
        Disposition.START, worker(outputs).tryClaim(waitingTask, "other-worker").disposition());
  }

  @Test
  public void callbackFromAnOldTaskCannotReleaseTheNextTasksReservation() {
    long oldTask = task(0, 3600);
    long nextTask = task(0, 3600);
    Claim oldClaim = store.tryClaim(oldTask, "first-worker");
    AiReviewChatJob.Result firstResult = result("First result");
    store.stageResult(oldTask, oldClaim.token(), firstResult);
    store.releaseCapacity(oldClaim.token());
    Claim nextClaim = store.tryClaim(nextTask, "second-worker");
    assertEquals(Disposition.START, nextClaim.disposition());

    store.stageResult(oldTask, oldClaim.token(), result("Duplicate completion"));
    store.releaseCapacity(oldClaim.token());

    assertEquals(1, reservations());
    assertTrue(store.isActive(nextTask, nextClaim.token()));
    assertEquals(firstResult, store.getStagedResult(oldTask).orElseThrow());
  }

  @Test
  public void cancelledFutureDoesNotReleaseCapacityUntilItsProviderDeadline() {
    long cancelledTask = task(0, 3600);
    long waitingTask = task(0, 3600);
    Claim claim = store.tryClaim(cancelledTask, "worker");
    AiReviewChatJob.Result cancellation =
        new AiReviewChatJob.Result(
            null, new AiReviewChatJob.Error(409, "AI review was cancelled."));

    store.stageResult(cancelledTask, claim.token(), cancellation);
    assertTrue(store.finishStaged(cancelledTask));

    assertEquals(1, reservations());
    assertEquals(Disposition.WAIT, store.tryClaim(waitingTask, "other-worker").disposition());
    clock.advance(Duration.ofSeconds(181));
    long freshTask = task(0, 3600);
    Claim fresh = worker(outputs).tryClaim(freshTask, "replacement-worker");
    assertEquals(Disposition.START, fresh.disposition());
    assertEquals(1, reservations());
    assertEquals(cancellation, store.getStagedResult(cancelledTask).orElseThrow());
  }

  @Test
  public void expiredQueuedTaskNeverStartsProviderWork() {
    long expiredTask = task(181, 3600);

    Claim expired = store.tryClaim(expiredTask, "worker");

    assertEquals(Disposition.FINISH, expired.disposition());
    assertNull(expired.token());
    assertEquals(504, store.getStagedResult(expiredTask).orElseThrow().error().status());
    assertEquals(0, reservations());
    verifyNoInteractions(outputs);
  }

  @Test
  public void anAbandonedAttemptExpiresWithoutBeingSentAgainAndFreesItsSlot() {
    long abandonedTask = task(0, 3600);
    Claim original = store.tryClaim(abandonedTask, "lost-worker");
    clock.advance(Duration.ofSeconds(181));
    long freshTask = task(0, 3600);
    AiReviewExecutionStore recovered = worker(outputs);

    Claim expired = recovered.tryClaim(abandonedTask, "replacement-worker");

    assertEquals(Disposition.FINISH, expired.disposition());
    assertEquals(original.token(), expired.token());
    assertEquals(504, recovered.getStagedResult(abandonedTask).orElseThrow().error().status());
    assertFalse(recovered.isActive(abandonedTask, original.token()));
    assertEquals(
        Disposition.START, recovered.tryClaim(freshTask, "replacement-worker").disposition());
    recovered.stageResult(abandonedTask, original.token(), result("Too late"));
    recovered.releaseCapacity(original.token());
    assertEquals(1, reservations());
    assertEquals(504, recovered.getStagedResult(abandonedTask).orElseThrow().error().status());
  }

  @Test
  public void providerCompletionAfterDeadlineCannotPublishSuccess() {
    long taskId = task(0, 3600);
    Claim claim = store.tryClaim(taskId, "worker");
    clock.advance(Duration.ofSeconds(180));

    store.stageResult(taskId, claim.token(), result("After the deadline"));
    store.releaseCapacity(claim.token());

    assertEquals(504, store.getStagedResult(taskId).orElseThrow().error().status());
    assertFalse(store.isActive(taskId, claim.token()));
    assertEquals(0, reservations());
  }

  @Test
  public void queuedDeadlineDoesNotExtendWhenConfigurationChanges() {
    long occupiedTask = task(0, 3600);
    long queuedTask = task(0, 3600);
    store.tryClaim(occupiedTask, "worker");
    Claim queued = store.tryClaim(queuedTask, "worker");
    assertEquals(Disposition.WAIT, queued.disposition());
    assertEquals(clock.instant().plusSeconds(180), queued.deadline());

    configuration.setTimeoutSeconds(900);
    clock.advance(Duration.ofSeconds(181));
    Claim afterRestart = worker(outputs).tryClaim(queuedTask, "replacement-worker");

    assertEquals(Disposition.FINISH, afterRestart.disposition());
    assertEquals(queued.deadline(), afterRestart.deadline());
    assertEquals(504, store.getStagedResult(queuedTask).orElseThrow().error().status());
  }

  @Test
  public void aShorterTaskTimeoutLimitsTheOverallDeadline() {
    long taskId = task(0, 12);

    Claim claim = store.tryClaim(taskId, "worker");

    assertEquals(clock.instant().plusSeconds(12), claim.deadline());
    clock.advance(Duration.ofSeconds(12));
    assertFalse(store.isActive(taskId, claim.token()));
    assertEquals(Disposition.FINISH, store.tryClaim(taskId, "worker").disposition());
  }

  @Test
  public void malformedCapacityFailsClosedInsteadOfResettingAdmission() {
    long runningTask = task(0, 3600);
    long otherTask = task(0, 3600);
    store.tryClaim(runningTask, "worker");
    MBlob capacity = blobs.findByName(AiReviewExecutionStore.CAPACITY_NAME).orElseThrow();
    capacity.setContent("not-valid-json".getBytes(StandardCharsets.UTF_8));
    blobs.saveAndFlush(capacity);

    assertThrows(
        RuntimeException.class, () -> worker(outputs).tryClaim(otherTask, "replacement-worker"));

    assertEquals(
        "not-valid-json",
        new String(
            blobs.findById(capacity.getId()).orElseThrow().getContent(), StandardCharsets.UTF_8));
    assertNull(tasks.findById(otherTask).orElseThrow().getMessage());
    verifyNoInteractions(outputs);
  }

  @Test
  public void busyRejectionIsDurableAndDoesNotTakeAnotherCapacitySlot() {
    long runningTask = task(0, 3600);
    long rejectedTask = task(0, 3600);
    store.tryClaim(runningTask, "worker");
    assertEquals(Disposition.WAIT, store.tryClaim(rejectedTask, "other-worker").disposition());

    store.rejectBusy(rejectedTask);

    AiReviewExecutionStore recovered = worker(outputs);
    assertEquals(429, recovered.getStagedResult(rejectedTask).orElseThrow().error().status());
    assertEquals(
        Disposition.FINISH, recovered.tryClaim(rejectedTask, "replacement-worker").disposition());
    assertTrue(recovered.finishStaged(rejectedTask));
    assertEquals(1, reservations());
  }

  @Test
  public void aBusyResponseFromDuplicateDispatchCannotCancelAnExistingAttempt() {
    long taskId = task(0, 3600);
    Claim started = store.tryClaim(taskId, "first-worker");

    worker(outputs).rejectBusy(taskId);

    assertTrue(store.isActive(taskId, started.token()));
    assertTrue(store.getStagedResult(taskId).isEmpty());
    assertEquals(1, reservations());
  }

  @Test
  public void sweeperExpiresAbandonedTasksWithoutLaunchingOrOverwritingSavedResults() {
    long activeTask = task(0, 3600);
    long abandonedTask = task(181, 3600);
    long stagedTask = task(0, 3600);
    store.tryClaim(activeTask, "worker");
    configuration.setMaxInFlight(2);
    Claim staged = store.tryClaim(stagedTask, "worker");
    AiReviewChatJob.Result saved = result("Completed before its deadline");
    store.stageResult(stagedTask, staged.token(), saved);
    store.releaseCapacity(staged.token());

    worker(outputs).expire(activeTask);
    worker(outputs).expire(abandonedTask);
    assertTrue(store.getStagedResult(activeTask).isEmpty());
    assertEquals(504, store.getStagedResult(abandonedTask).orElseThrow().error().status());
    assertTrue(store.finishStaged(abandonedTask));

    clock.advance(Duration.ofSeconds(181));
    worker(outputs).expire(activeTask);
    worker(outputs).expire(stagedTask);
    assertEquals(504, store.getStagedResult(activeTask).orElseThrow().error().status());
    assertEquals(saved, store.getStagedResult(stagedTask).orElseThrow());
    assertTrue(store.finishStaged(stagedTask));
  }

  @Test
  public void unfinishedSweepUsesAnExclusiveCursorAndOnlyInteractiveTaskNames() {
    long before = task(0, 3600);
    long first = task(0, 3600);
    long unrelated = task(0, 3600);
    long legacy = task(0, 3600);
    long completed = task(0, 3600);
    long last = task(0, 3600);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              entityManager
                  .find(PollableTask.class, unrelated)
                  .setName(AiReviewJob.class.getCanonicalName());
              entityManager
                  .find(PollableTask.class, legacy)
                  .setName(AiReviewChatJob.class.getCanonicalName());
              entityManager
                  .find(PollableTask.class, completed)
                  .setFinishedDate(ZonedDateTime.now(clock));
            });

    assertEquals(List.of(first, legacy), store.unfinishedIds(before, 2));
    assertEquals(List.of(last), store.unfinishedIds(legacy, 2));
    assertTrue(store.unfinishedIds(last, 2).isEmpty());
  }

  @Test
  public void genericZombieCleanupExcludesBothInteractiveJobClasses() {
    long configuredTask = task(181, 180);
    long legacyTask = task(181, 180);
    long ordinaryTask = task(181, 180);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              entityManager
                  .find(PollableTask.class, legacyTask)
                  .setName(AiReviewChatJob.class.getCanonicalName());
              entityManager
                  .find(PollableTask.class, ordinaryTask)
                  .setName(AiReviewJob.class.getCanonicalName());
            });

    List<Long> genericCleanupIds =
        tasks
            .findZombiePollableTasks(
                ZonedDateTime.now(clock),
                PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "id")))
            .stream()
            .map(PollableTask::getId)
            .toList();

    assertTrue(genericCleanupIds.contains(ordinaryTask));
    assertFalse(genericCleanupIds.contains(configuredTask));
    assertFalse(genericCleanupIds.contains(legacyTask));
  }

  private AiReviewExecutionStore worker(PollableTaskBlobStorage outputStorage) {
    return worker(outputStorage, capacityStore());
  }

  private AiReviewExecutionStore worker(
      PollableTaskBlobStorage outputStorage, AiReviewCapacityStore capacityStore) {
    AiReviewExecutionStore worker =
        new AiReviewExecutionStore(
            capacityStore, outputStorage, mapper, configuration, transactions);
    worker.entityManager = entityManager;
    worker.clock = clock;
    return worker;
  }

  private AiReviewCapacityStore capacityStore() {
    return new AiReviewCapacityStore(
        blobs,
        mapper,
        configuration,
        transactions,
        new JdbcTemplate(dataSource),
        new SimpleMeterRegistry());
  }

  private AiReviewCapacityStore capacityWithAdmissionHook(Consumer<String> afterReservation) {
    AiReviewCapacityStore capacity = spy(capacityStore());
    doAnswer(
            invocation -> {
              Admission admission = (Admission) invocation.callRealMethod();
              assertEquals(Admission.RESERVED, admission);
              assertEquals(1, reservations());
              afterReservation.accept(invocation.getArgument(0));
              return admission;
            })
        .when(capacity)
        .reserve(anyString(), nullable(Long.class), any(Instant.class), any(Instant.class));
    return capacity;
  }

  private void lockTaskRow(Connection connection, long taskId) throws java.sql.SQLException {
    try (var statement =
        connection.prepareStatement("select id from pollable_task where id=? for update")) {
      statement.setLong(1, taskId);
      try (var rows = statement.executeQuery()) {
        assertTrue(rows.next());
      }
    }
  }

  private long task(long ageSeconds, long timeoutSeconds) {
    long id =
        new TransactionTemplate(transactions)
            .execute(
                status -> {
                  PollableTask task = new PollableTask();
                  task.setName(AiReviewConfiguredChatJob.class.getCanonicalName());
                  task.setTimeout(timeoutSeconds);
                  entityManager.persist(task);
                  entityManager.flush();
                  task.setCreatedDate(
                      ZonedDateTime.ofInstant(
                          clock.instant().minusSeconds(ageSeconds), ZoneOffset.UTC));
                  return task.getId();
                });
    createdTasks.add(id);
    return id;
  }

  private long taskForUser(Long userId) {
    long taskId = task(0, 3600);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status ->
                entityManager
                    .find(PollableTask.class, taskId)
                    .setCreatedByUser(
                        userId == null ? null : entityManager.getReference(User.class, userId)));
    return taskId;
  }

  private long user() {
    User user = new User();
    user.setUsername("ai-review-capacity-" + UUID.randomUUID());
    user.setEnabled(true);
    long id = users.saveAndFlush(user).getId();
    createdUsers.add(id);
    return id;
  }

  private void initializeWorkers(AiReviewExecutionStore... workers) {
    for (AiReviewExecutionStore worker : workers) {
      long completedTask = task(0, 3600);
      Claim claim = worker.tryClaim(completedTask, "initialization");
      assertEquals(Disposition.START, claim.disposition());
      worker.stageResult(completedTask, claim.token(), result("Initialization"));
      worker.releaseCapacity(claim.token());
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              status ->
                  entityManager
                      .find(PollableTask.class, completedTask)
                      .setFinishedDate(ZonedDateTime.now(clock)));
    }
  }

  private List<Claim> concurrentClaims(
      AiReviewExecutionStore first, long firstTask, AiReviewExecutionStore second, long secondTask)
      throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var one =
          executor.submit(
              () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return first.tryClaim(firstTask, "first-worker");
              });
      var two =
          executor.submit(
              () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return second.tryClaim(secondTask, "second-worker");
              });
      return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
    }
  }

  private int reservations() {
    return capacity().reservations().size();
  }

  private <T> T onAnotherThread(Callable<T> action) throws Exception {
    try (var executor = Executors.newSingleThreadExecutor()) {
      return executor.submit(action).get(10, TimeUnit.SECONDS);
    }
  }

  private final class RequestEntityManager implements AutoCloseable {
    private final EntityManager requestEntityManager = entityManagerFactory.createEntityManager();

    RequestEntityManager() {
      assertFalse(TransactionSynchronizationManager.hasResource(entityManagerFactory));
      TransactionSynchronizationManager.bindResource(
          entityManagerFactory, new EntityManagerHolder(requestEntityManager));
    }

    @Override
    public void close() {
      TransactionSynchronizationManager.unbindResource(entityManagerFactory);
      requestEntityManager.close();
    }
  }

  private AiReviewExecutionStore.Capacity capacity() {
    return blobs
        .findByName(AiReviewExecutionStore.CAPACITY_NAME)
        .map(
            capacity ->
                mapper.readValueUnchecked(
                    new String(capacity.getContent(), StandardCharsets.UTF_8),
                    AiReviewExecutionStore.Capacity.class))
        .orElseGet(() -> new AiReviewExecutionStore.Capacity(Map.of()));
  }

  private void removeCapacity() {
    blobs.findByName(AiReviewExecutionStore.CAPACITY_NAME).ifPresent(blobs::delete);
  }

  private static AiReviewChatJob.Result result(String message) {
    return new AiReviewChatJob.Result(
        new AiReviewChatResponse(new AiReviewChatMessage("assistant", message), List.of(), null),
        null);
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> instant;

    private MutableClock(Instant instant) {
      this.instant = new AtomicReference<>(instant);
    }

    void set(Instant value) {
      instant.set(value);
    }

    void advance(Duration duration) {
      instant.updateAndGet(value -> value.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC clock required");
      return this;
    }

    @Override
    public Instant instant() {
      return instant.get();
    }
  }
}
