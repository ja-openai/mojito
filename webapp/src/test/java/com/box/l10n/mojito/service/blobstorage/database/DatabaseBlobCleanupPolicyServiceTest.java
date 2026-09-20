package com.box.l10n.mojito.service.blobstorage.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.DatabaseBlobCleanupPolicy;
import com.box.l10n.mojito.entity.MBlob;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.quartz.QuartzSchedulerManager;
import com.box.l10n.mojito.service.DBUtils;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.pollableTask.PollableTaskRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.engine.spi.SessionImplementor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:hsqldb:mem:blob_cleanup_policy;DB_CLOSE_DELAY=-1",
      "l10n.blob-storage.database.cleanup-enabled=false",
      "l10n.blob-storage.database.policy-cleanup-enabled=false"
    })
public class DatabaseBlobCleanupPolicyServiceTest extends ServiceTestBase {

  private static final String PREFIX = "cleanup_test/";
  private static final List<String> UNKNOWN_TASK_NAMES =
      List.of(
          "pollable_task/invalid-task/input",
          "pollable_task/9223372036854775807/input",
          "pollable_task/9999999999999999999/input",
          "pollable_task/99999999999999999999/input",
          "pollable_task/0/input");

  @Autowired DatabaseBlobCleanupPolicyRepository policyRepository;

  @Autowired DatabaseBlobCleanupPolicyService policyService;

  @Autowired MBlobRepository mBlobRepository;

  @Autowired PollableTaskRepository pollableTaskRepository;

  @Autowired ApplicationContext applicationContext;

  @Autowired JdbcTemplate jdbcTemplate;

  @Autowired PlatformTransactionManager transactionManager;

  @Autowired DBUtils dbUtils;

  @Autowired QuartzSchedulerManager quartzSchedulerManager;

  @Autowired MeterRegistry meterRegistry;

  @PersistenceContext EntityManager entityManager;

  private final List<Long> taskIds = new ArrayList<>();

  @Before
  public void cleanPoliciesAndBlobs() {
    policyRepository.deleteAll();
    deleteBlob("cleanup_test/expired-1/input");
    deleteBlob("cleanup_test/expired-2/input");
    deleteBlob("cleanup_test/expired-3/input");
    deleteBlob("cleanup_test/recent/input");
    deleteBlob("cleanup_test/permanent/input");
    deleteBlob("cleanup_test/long-ttl/input");
    UNKNOWN_TASK_NAMES.forEach(this::deleteBlob);
    deleteBlob("other_cleanup/expired/input");
  }

  @After
  public void cleanCreatedTasks() {
    for (int i = taskIds.size() - 1; i >= 0; i--) {
      Long taskId = taskIds.get(i);
      deleteBlob("pollable_task/" + taskId + "/input");
      deleteBlob("pollable_task/" + taskId + "/output");
      pollableTaskRepository.deleteById(taskId);
    }
    UNKNOWN_TASK_NAMES.forEach(this::deleteBlob);
  }

  @Test
  public void drainsOnlyExpiredBlobsInTheConfiguredPrefix() {
    saveBlob("cleanup_test/expired-1/input", 10, 86_400L);
    saveBlob("cleanup_test/expired-2/input", 7, 86_400L);
    saveBlob("cleanup_test/expired-3/input", 4, 86_400L);
    saveBlob("cleanup_test/recent/input", 1, 86_400L);
    saveBlob("cleanup_test/permanent/input", 10, null);
    saveBlob("cleanup_test/long-ttl/input", 10, 30 * 86_400L);
    saveBlob("other_cleanup/expired/input", 10, 86_400L);

    DatabaseBlobCleanupPolicy policy = createPolicy(true, 2, 0);

    policyService.runPolicy(policy.getId());

    DatabaseBlobCleanupPolicy completed = policyRepository.findById(policy.getId()).orElseThrow();
    assertEquals(DatabaseBlobCleanupPolicyService.STATUS_DRAINED, completed.getStatus());
    assertEquals(3L, completed.getLastDeletedCount());
    assertEquals(3L, completed.getTotalDeletedCount());
    assertNotNull(completed.getLastStartedDate());
    assertNotNull(completed.getLastFinishedDate());
    assertFalse(mBlobRepository.findByName("cleanup_test/expired-1/input").isPresent());
    assertFalse(mBlobRepository.findByName("cleanup_test/expired-2/input").isPresent());
    assertFalse(mBlobRepository.findByName("cleanup_test/expired-3/input").isPresent());
    assertTrue(mBlobRepository.findByName("cleanup_test/recent/input").isPresent());
    assertTrue(mBlobRepository.findByName("cleanup_test/permanent/input").isPresent());
    assertTrue(mBlobRepository.findByName("cleanup_test/long-ttl/input").isPresent());
    assertTrue(mBlobRepository.findByName("other_cleanup/expired/input").isPresent());
  }

  @Test
  public void preservesActiveTaskPayloadsAndCleansThemAfterCompletion() {
    PollableTask active = saveTask(false, null, 0);
    PollableTask completed = saveTask(true, null, 0);
    String activeInput = "pollable_task/" + active.getId() + "/input";
    String activeOutput = "pollable_task/" + active.getId() + "/output";
    String completedInput = "pollable_task/" + completed.getId() + "/input";
    String completedOutput = "pollable_task/" + completed.getId() + "/output";
    saveBlob(activeInput, 10, 86_400L);
    saveBlob(activeOutput, 10, 86_400L);
    saveBlob(completedInput, 10, 86_400L);
    saveBlob(completedOutput, 10, 86_400L);
    DatabaseBlobCleanupPolicy policy =
        policyService.createPolicy(
            new DatabaseBlobCleanupPolicyService.PolicyUpdate(
                "pollable_task/", true, 3, 1, 0, 0, 0));

    policyService.runPolicy(policy.getId());

    DatabaseBlobCleanupPolicy drained = policyRepository.findById(policy.getId()).orElseThrow();
    assertEquals(DatabaseBlobCleanupPolicyService.STATUS_DRAINED, drained.getStatus());
    assertEquals(2L, drained.getLastDeletedCount());
    assertTrue(mBlobRepository.findByName(activeInput).isPresent());
    assertTrue(mBlobRepository.findByName(activeOutput).isPresent());
    assertFalse(mBlobRepository.findByName(completedInput).isPresent());
    assertFalse(mBlobRepository.findByName(completedOutput).isPresent());

    active.setFinishedDate(ZonedDateTime.now());
    pollableTaskRepository.saveAndFlush(active);
    policyService.runPolicy(policy.getId());

    DatabaseBlobCleanupPolicy resumed = policyRepository.findById(policy.getId()).orElseThrow();
    assertEquals(DatabaseBlobCleanupPolicyService.STATUS_DRAINED, resumed.getStatus());
    assertEquals(2L, resumed.getLastDeletedCount());
    assertEquals(4L, resumed.getTotalDeletedCount());
    assertFalse(mBlobRepository.findByName(activeInput).isPresent());
    assertFalse(mBlobRepository.findByName(activeOutput).isPresent());
  }

  @Test
  public void preservesTaskGraphsAndUnrecognizedTaskPayloads() {
    PollableTask activeParent = saveTask(false, null, 1);
    saveTask(true, activeParent, 0);
    PollableTask finishedParent = saveTask(true, null, 0);
    saveTask(false, finishedParent, 0);
    saveTask(true, null, 1);
    for (Long taskId : taskIds) {
      saveBlob("pollable_task/" + taskId + "/input", 10, 86_400L);
    }
    UNKNOWN_TASK_NAMES.forEach(name -> saveBlob(name, 10, 86_400L));
    DatabaseBlobCleanupPolicy policy =
        policyService.createPolicy(
            new DatabaseBlobCleanupPolicyService.PolicyUpdate(
                "pollable_task/", true, 3, 1, 0, 0, 0));

    policyService.runPolicy(policy.getId());

    DatabaseBlobCleanupPolicy drained = policyRepository.findById(policy.getId()).orElseThrow();
    assertEquals(DatabaseBlobCleanupPolicyService.STATUS_DRAINED, drained.getStatus());
    assertEquals(0L, drained.getLastDeletedCount());
    for (Long taskId : taskIds) {
      assertTrue(mBlobRepository.findByName("pollable_task/" + taskId + "/input").isPresent());
    }
    UNKNOWN_TASK_NAMES.forEach(name -> assertTrue(mBlobRepository.findByName(name).isPresent()));
  }

  @Test
  public void honorsOptionalBatchLimitAndResumesOnTheNextRun() {
    saveBlob("cleanup_test/expired-1/input", 10, 86_400L);
    saveBlob("cleanup_test/expired-2/input", 10, 86_400L);
    saveBlob("cleanup_test/expired-3/input", 10, 86_400L);
    DatabaseBlobCleanupPolicy policy = createPolicy(true, 1, 2);

    policyService.runPolicy(policy.getId());

    DatabaseBlobCleanupPolicy paused = policyRepository.findById(policy.getId()).orElseThrow();
    assertEquals(DatabaseBlobCleanupPolicyService.STATUS_PAUSED, paused.getStatus());
    assertEquals(2L, paused.getLastDeletedCount());

    policyService.runPolicy(policy.getId());

    DatabaseBlobCleanupPolicy drained = policyRepository.findById(policy.getId()).orElseThrow();
    assertEquals(DatabaseBlobCleanupPolicyService.STATUS_DRAINED, drained.getStatus());
    assertEquals(1L, drained.getLastDeletedCount());
    assertEquals(3L, drained.getTotalDeletedCount());
  }

  @Test
  public void cleanupBudgetReachesJdbcAndActualJpaDelete() {
    saveBlob("cleanup_test/expired-1/input", 10, 86_400L);
    DatabaseBlobCleanupPolicy policy = createPolicy(true, 1, 1);
    MBlobRepository observed = mock(MBlobRepository.class);
    when(observed.deleteExpiredByIds(anyList(), any()))
        .thenAnswer(
            call -> {
              ConnectionHolder connection =
                  (ConnectionHolder)
                      TransactionSynchronizationManager.getResource(jdbcTemplate.getDataSource());
              assertNotNull(connection);
              assertTrue(connection.hasTimeout());
              assertTrue(connection.getTimeToLiveInSeconds() > 0);
              assertTrue(connection.getTimeToLiveInSeconds() <= 10);
              int remaining =
                  entityManager
                      .unwrap(SessionImplementor.class)
                      .getJdbcCoordinator()
                      .determineRemainingTransactionTimeOutPeriod();
              assertTrue(remaining > 0 && remaining <= 10);
              // Execute the actual Spring Data/Hibernate native delete in this same transaction.
              return mBlobRepository.deleteExpiredByIds(call.getArgument(0), call.getArgument(1));
            });

    budgetService(observed).runPolicy(policy.getId());

    assertFalse(mBlobRepository.findByName("cleanup_test/expired-1/input").isPresent());
    assertEquals(
        1L, policyRepository.findById(policy.getId()).orElseThrow().getTotalDeletedCount());
  }

  @Test
  public void expiredBudgetAfterActualJpaDeleteRollsBackBlobAndCounters() {
    saveBlob("cleanup_test/expired-1/input", 10, 86_400L);
    DatabaseBlobCleanupPolicy policy = createPolicy(true, 1, 1);
    AtomicLong clock = new AtomicLong();
    MBlobRepository observed = mock(MBlobRepository.class);
    when(observed.deleteExpiredByIds(anyList(), any()))
        .thenAnswer(
            call -> {
              int count =
                  mBlobRepository.deleteExpiredByIds(call.getArgument(0), call.getArgument(1));
              assertEquals(1, count);
              clock.set(TimeUnit.SECONDS.toNanos(10));
              return count;
            });
    DatabaseBlobCleanupPolicyService bounded = spy(budgetService(observed));
    doAnswer(call -> clock.get()).when(bounded).nanoTime();

    bounded.runPolicy(policy.getId());

    assertTrue(mBlobRepository.findByName("cleanup_test/expired-1/input").isPresent());
    DatabaseBlobCleanupPolicy failed = policyRepository.findById(policy.getId()).orElseThrow();
    assertFalse(failed.isEnabled());
    assertEquals(DatabaseBlobCleanupPolicyService.STATUS_FAILED, failed.getStatus());
    assertEquals(0L, failed.getTotalDeletedCount());
    assertEquals(0L, failed.getLastDeletedCount());
    assertTrue(failed.getLastError().contains("reconcile deleted rows"));
  }

  private DatabaseBlobCleanupPolicyService budgetService(MBlobRepository observed) {
    return new DatabaseBlobCleanupPolicyService(
        policyRepository,
        observed,
        jdbcTemplate,
        dbUtils,
        quartzSchedulerManager,
        meterRegistry,
        transactionManager);
  }

  @Test
  public void rejectsUnsafePrefixes() {
    try {
      policyService.createPolicy(
          new DatabaseBlobCleanupPolicyService.PolicyUpdate(
              "cleanup_test/%", false, 3, 250, 0, 0, 5));
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("Prefix"));
      assertEquals(0L, policyRepository.count());
      return;
    }
    throw new AssertionError("Unsafe cleanup prefix was accepted");
  }

  @Test
  public void stoppingPolicyDisablesFutureCleanup() {
    DatabaseBlobCleanupPolicy policy = createPolicy(true, 250, 0);

    DatabaseBlobCleanupPolicy stopped = policyService.stopPolicy(policy.getId());

    assertFalse(stopped.isEnabled());
    assertTrue(stopped.isStopRequested());
    assertEquals(DatabaseBlobCleanupPolicyService.STATUS_STOPPED, stopped.getStatus());
    assertNull(stopped.getLastError());
  }

  @Test
  public void startsManualCleanupWhenRecurringScheduleIsDisabled() throws InterruptedException {
    // Keep the manual Quartz path live without another cleaner consuming this fixture's row.
    assertFalse(applicationContext.containsBean("triggerExpiringBlobCleanup"));
    assertFalse(applicationContext.containsBean("triggerDatabaseBlobPolicyCleanupJob"));
    saveBlob("cleanup_test/expired-1/input", 10, 86_400L);
    DatabaseBlobCleanupPolicy policy = createPolicy(false, 250, 0);

    DatabaseBlobCleanupPolicy queued = policyService.startPolicy(policy.getId());

    assertTrue(queued.isEnabled());
    waitForCondition(
        "Manual cleanup did not drain the policy",
        () ->
            policyRepository
                .findById(policy.getId())
                .map(DatabaseBlobCleanupPolicy::getStatus)
                .filter(DatabaseBlobCleanupPolicyService.STATUS_DRAINED::equals)
                .isPresent());

    DatabaseBlobCleanupPolicy completed = policyRepository.findById(policy.getId()).orElseThrow();
    assertEquals(1L, completed.getLastDeletedCount());
    assertFalse(mBlobRepository.findByName("cleanup_test/expired-1/input").isPresent());
  }

  private DatabaseBlobCleanupPolicy createPolicy(
      boolean enabled, int batchSize, int maxBatchesPerRun) {
    return policyService.createPolicy(
        new DatabaseBlobCleanupPolicyService.PolicyUpdate(
            PREFIX, enabled, 3, batchSize, maxBatchesPerRun, 0, 5));
  }

  private void saveBlob(String name, int ageDays, Long expireAfterSeconds) {
    MBlob blob = new MBlob();
    blob.setName(name);
    blob.setCreatedDate(ZonedDateTime.now().minusDays(ageDays));
    if (expireAfterSeconds != null) {
      blob.setExpireAfterSeconds(expireAfterSeconds);
    }
    mBlobRepository.saveAndFlush(blob);
  }

  private PollableTask saveTask(boolean finished, PollableTask parent, int expectedChildren) {
    PollableTask task = new PollableTask();
    task.setName("blob-cleanup-safety-test");
    task.setParentTask(parent);
    task.setExpectedSubTaskNumber(expectedChildren);
    if (finished) {
      task.setFinishedDate(ZonedDateTime.now().minusDays(9));
    }
    task = pollableTaskRepository.saveAndFlush(task);
    taskIds.add(task.getId());
    return task;
  }

  private void deleteBlob(String name) {
    mBlobRepository.findByName(name).ifPresent(mBlobRepository::delete);
  }
}
