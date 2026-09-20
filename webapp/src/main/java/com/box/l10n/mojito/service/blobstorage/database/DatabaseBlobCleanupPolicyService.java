package com.box.l10n.mojito.service.blobstorage.database;

import com.box.l10n.mojito.entity.DatabaseBlobCleanupPolicy;
import com.box.l10n.mojito.quartz.QuartzSchedulerManager;
import com.box.l10n.mojito.service.DBUtils;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.quartz.JobBuilder;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DatabaseBlobCleanupPolicyService {

  private static final Logger logger =
      LoggerFactory.getLogger(DatabaseBlobCleanupPolicyService.class);

  private static final Pattern PREFIX_PATTERN = Pattern.compile("[A-Za-z0-9_-]+/");
  private static final int MAX_BATCH_SIZE = 5_000;
  private static final int MAX_RETENTION_DAYS = 3_650;
  private static final int MAX_PAUSE_MILLIS = 60_000;
  private static final int MAX_RETRIES = 20;
  private static final int MAX_ERROR_LENGTH = 2_048;
  static final int EXECUTION_BUDGET_SECONDS = 10;

  static final String STATUS_IDLE = "IDLE";
  static final String STATUS_QUEUED = "QUEUED";
  static final String STATUS_RUNNING = "RUNNING";
  static final String STATUS_STOP_REQUESTED = "STOP_REQUESTED";
  static final String STATUS_STOPPED = "STOPPED";
  static final String STATUS_DRAINED = "DRAINED";
  static final String STATUS_PAUSED = "PAUSED";
  static final String STATUS_FAILED = "FAILED";

  private final DatabaseBlobCleanupPolicyRepository policyRepository;
  private final MBlobRepository mBlobRepository;
  private final JdbcTemplate jdbcTemplate;
  private final DBUtils dbUtils;
  private final QuartzSchedulerManager quartzSchedulerManager;
  private final MeterRegistry meterRegistry;
  private final TransactionTemplate transactionTemplate;

  public DatabaseBlobCleanupPolicyService(
      DatabaseBlobCleanupPolicyRepository policyRepository,
      MBlobRepository mBlobRepository,
      JdbcTemplate jdbcTemplate,
      DBUtils dbUtils,
      QuartzSchedulerManager quartzSchedulerManager,
      MeterRegistry meterRegistry,
      PlatformTransactionManager transactionManager) {
    this.policyRepository = Objects.requireNonNull(policyRepository);
    this.mBlobRepository = Objects.requireNonNull(mBlobRepository);
    this.jdbcTemplate =
        new JdbcTemplate(
            Objects.requireNonNull(Objects.requireNonNull(jdbcTemplate).getDataSource()));
    // Spring replaces a JDBC query timeout with the remaining transaction timeout. Use the same
    // budget for both, including the eligibility probe outside a transaction; never change the
    // shared application's JdbcTemplate. The JPA delete inherits this transaction's timeout too.
    this.jdbcTemplate.setQueryTimeout(EXECUTION_BUDGET_SECONDS);
    this.dbUtils = Objects.requireNonNull(dbUtils);
    this.quartzSchedulerManager = Objects.requireNonNull(quartzSchedulerManager);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
    this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.transactionTemplate.setTimeout(EXECUTION_BUDGET_SECONDS);
  }

  public List<DatabaseBlobCleanupPolicy> listPolicies() {
    return policyRepository.findAllByOrderByPrefixAsc();
  }

  @Transactional
  public DatabaseBlobCleanupPolicy createPolicy(PolicyUpdate update) {
    String prefix = normalizePrefix(update.prefix());
    if (policyRepository.existsByPrefix(prefix)) {
      throw new IllegalArgumentException("A cleanup policy already exists for this prefix");
    }

    DatabaseBlobCleanupPolicy policy = new DatabaseBlobCleanupPolicy();
    applyUpdate(policy, update, prefix);
    return policyRepository.save(policy);
  }

  @Transactional
  public DatabaseBlobCleanupPolicy updatePolicy(long policyId, PolicyUpdate update) {
    DatabaseBlobCleanupPolicy policy = getPolicy(policyId);
    String prefix = normalizePrefix(update.prefix());
    if (!policy.getPrefix().equals(prefix) && policyRepository.existsByPrefix(prefix)) {
      throw new IllegalArgumentException("A cleanup policy already exists for this prefix");
    }
    if (STATUS_RUNNING.equals(policy.getStatus()) && !policy.getPrefix().equals(prefix)) {
      throw new IllegalArgumentException("Stop the running policy before changing its prefix");
    }

    applyUpdate(policy, update, prefix);
    if (!policy.isEnabled() && STATUS_RUNNING.equals(policy.getStatus())) {
      policy.setStopRequested(true);
      policy.setStatus(STATUS_STOP_REQUESTED);
    }
    return policyRepository.save(policy);
  }

  public DatabaseBlobCleanupPolicy startPolicy(long policyId) {
    DatabaseBlobCleanupPolicy policy =
        transactionTemplate.execute(
            status -> {
              DatabaseBlobCleanupPolicy current = getPolicy(policyId);
              current.setEnabled(true);
              current.setStopRequested(false);
              if (!STATUS_RUNNING.equals(current.getStatus())) {
                current.setStatus(STATUS_QUEUED);
              }
              current.setLastError(null);
              return policyRepository.saveAndFlush(current);
            });

    try {
      Scheduler scheduler =
          quartzSchedulerManager.getScheduler(QuartzSchedulerManager.DEFAULT_SCHEDULER_NAME);
      JobKey jobKey = JobKey.jobKey(DatabaseBlobPolicyCleanupJobConfig.JOB_NAME);
      if (!scheduler.checkExists(jobKey)) {
        scheduler.addJob(
            JobBuilder.newJob(DatabaseBlobPolicyCleanupJob.class)
                .withIdentity(jobKey)
                .storeDurably()
                .requestRecovery()
                .build(),
            true);
      }
      scheduler.triggerJob(jobKey);
    } catch (SchedulerException e) {
      throw new IllegalStateException("Could not start database blob cleanup", e);
    }
    return policy;
  }

  @Transactional
  public DatabaseBlobCleanupPolicy stopPolicy(long policyId) {
    DatabaseBlobCleanupPolicy policy = getPolicy(policyId);
    policy.setEnabled(false);
    policy.setStopRequested(true);
    policy.setStatus(
        STATUS_RUNNING.equals(policy.getStatus())
                || STATUS_STOP_REQUESTED.equals(policy.getStatus())
            ? STATUS_STOP_REQUESTED
            : STATUS_STOPPED);
    return policyRepository.save(policy);
  }

  @Transactional
  public void deletePolicy(long policyId) {
    DatabaseBlobCleanupPolicy policy = getPolicy(policyId);
    if (STATUS_RUNNING.equals(policy.getStatus())
        || STATUS_STOP_REQUESTED.equals(policy.getStatus())) {
      throw new IllegalArgumentException("Stop the running policy before deleting it");
    }
    policyRepository.delete(policy);
  }

  public void runEnabledPolicies() {
    List<Long> policyIds =
        policyRepository.findByEnabledTrueOrderByPrefixAsc().stream()
            .map(DatabaseBlobCleanupPolicy::getId)
            .toList();
    policyIds.forEach(this::runPolicy);
  }

  void runPolicy(long policyId) {
    int completedBatches = 0;
    int consecutiveRetries = 0;
    boolean started = false;
    String prefix = null;

    while (true) {
      DatabaseBlobCleanupPolicy policy = null;
      BatchObservation observation = new BatchObservation();
      try {
        observation.phase = "start";
        if (!started) {
          markStarted(policyId);
          started = true;
        }
        observation.phase = "policy_read";
        policy = getPolicy(policyId);
        prefix = policy.getPrefix();
        if (!policy.isEnabled() || policy.isStopRequested()) {
          observation.phase = "finish";
          finish(policyId, STATUS_STOPPED, null);
          return;
        }
        if (policy.getMaxBatchesPerRun() > 0 && completedBatches >= policy.getMaxBatchesPerRun()) {
          observation.phase = "finish";
          finish(policyId, STATUS_PAUSED, null);
          return;
        }
        observation.phase = "transaction_begin";
        int deletedRows = deleteBatch(policy, observation);
        if (deletedRows == 0) {
          observation.phase = "eligibility";
          if (hasEligibleRows(policy)) {
            consecutiveRetries++;
            if (consecutiveRetries > policy.getMaxRetries()) {
              observation.phase = "finish";
              finish(policyId, STATUS_FAILED, "Eligible rows remain locked after retry limit");
              return;
            }
            pause(retryDelayMillis(policy, consecutiveRetries));
            continue;
          }
          observation.phase = "finish";
          finish(policyId, STATUS_DRAINED, null);
          return;
        }

        consecutiveRetries = 0;
        completedBatches++;
        observation.phase = "progress";
        long progressStarted = nanoTime();
        try {
          recordProgress(policyId, deletedRows);
        } finally {
          observation.progressNanos = nanoTime() - progressStarted;
        }
        meterRegistry
            .counter("DatabaseBlobStorage.policyCleanup.deletedRows", "prefix", policy.getPrefix())
            .increment(deletedRows);
        logger.info(
            "Database blob policy cleanup batch: policyId={}, prefix={}, batch={}, deletedRows={}",
            policyId,
            policy.getPrefix(),
            completedBatches,
            deletedRows);
        observation.phase = "complete";
        pause(policy.getPauseMillis());
      } catch (RuntimeException e) {
        observation.failed = true;
        boolean reconciliationRequired =
            isExecutionTimeout(e)
                || observation.transactionCommitted
                || "transaction_finalization".equals(observation.phase)
                || "start".equals(observation.phase)
                || "policy_read".equals(observation.phase)
                || "finish".equals(observation.phase)
                || e instanceof TransactionSystemException;
        if (!reconciliationRequired
            && policy != null
            && isRetryableLockFailure(e)
            && consecutiveRetries < policy.getMaxRetries()) {
          consecutiveRetries++;
          logger.warn(
              "Database blob policy cleanup lock conflict: policyId={}, prefix={}, retry={}, maxRetries={}",
              policyId,
              policy.getPrefix(),
              consecutiveRetries,
              policy.getMaxRetries(),
              e);
          pause(retryDelayMillis(policy, consecutiveRetries));
          continue;
        }

        // Cancellation/rollback can outlast the budget, and commit acknowledgement can be lost.
        // A separate progress write may also fail after deletion committed. Never retry those
        // cases automatically or treat persisted counters as proof of the affected rows. If this
        // disabling transaction fails, propagate it: successful disable has not been established.
        finish(
            policyId,
            STATUS_FAILED,
            reconciliationRequired
                ? "Cleanup failed during "
                    + observation.phase
                    + "; reconcile deleted rows before manually restarting. "
                    + "Execution cancellation, rollback or commit outcome may be uncertain."
                : e.getMessage(),
            reconciliationRequired);
        logger.error(
            "Database blob policy cleanup failed: policyId={}, prefix={}", policyId, prefix, e);
        return;
      } finally {
        if (observation.transactionNanos >= 0 || observation.failed) {
          logger.info(
              "Database blob cleanup phases: policyId={}, prefix={}, phase={}, failed={}, selectedRows={}, deletedRows={}, transactionCommitConfirmed={}, selectionMs={}, deleteMs={}, transactionMs={}, finalizationMs={}, progressMs={}",
              policyId,
              prefix,
              observation.phase,
              observation.failed,
              observation.selectedRows,
              observation.deletedRows,
              observation.transactionCommitted,
              millis(observation.selectionNanos),
              millis(observation.deleteNanos),
              millis(observation.transactionNanos),
              millis(observation.finalizationNanos),
              millis(observation.progressNanos));
        }
      }
    }
  }

  private int deleteBatch(DatabaseBlobCleanupPolicy policy, BatchObservation observation) {
    long transactionStarted = nanoTime();
    try {
      int deletedRows =
          Objects.requireNonNull(
              transactionTemplate.execute(
                  status -> {
                    try {
                      ZonedDateTime now = ZonedDateTime.now();
                      Timestamp cutoff =
                          Timestamp.from(now.minusDays(policy.getRetentionDays()).toInstant());
                      String forceIndex = dbUtils.isMysql() ? " force index (UK__MBLOB__NAME)" : "";
                      String skipLocked = dbUtils.isMysql() ? " for update skip locked" : "";
                      String sql =
                          "select id from mblob"
                              + forceIndex
                              + " where name >= ? and name < ?"
                              + " and expire_after_seconds is not null and created_date < ?"
                              + " and timestampadd(second, expire_after_seconds, created_date) < ? "
                              + MBlobRepository.CLEANUP_TASK_SAFETY_PREDICATE
                              + " order by name limit ?"
                              + skipLocked;
                      observation.phase = "selection";
                      long selectionStarted = nanoTime();
                      List<Long> ids;
                      try {
                        ids =
                            jdbcTemplate.queryForList(
                                sql,
                                Long.class,
                                policy.getPrefix(),
                                prefixUpperBound(policy.getPrefix()),
                                cutoff,
                                Timestamp.from(now.toInstant()),
                                policy.getBatchSize());
                      } finally {
                        observation.selectionNanos = nanoTime() - selectionStarted;
                      }
                      observation.selectedRows = ids.size();
                      requireExecutionBudget(transactionStarted);
                      observation.phase = "delete";
                      long deleteStarted = nanoTime();
                      try {
                        observation.deletedRows =
                            ids.isEmpty() ? 0 : mBlobRepository.deleteExpiredByIds(ids, now);
                      } finally {
                        observation.deleteNanos = nanoTime() - deleteStarted;
                      }
                      requireExecutionBudget(transactionStarted);
                      observation.phase = "transaction_finalization";
                      return observation.deletedRows;
                    } finally {
                      observation.callbackFinished = nanoTime();
                    }
                  }));
      observation.transactionCommitted = true;
      return deletedRows;
    } finally {
      long finished = nanoTime();
      observation.transactionNanos = finished - transactionStarted;
      // Includes commit or rollback and connection release, not just the server's commit time.
      if (observation.callbackFinished != null) {
        observation.finalizationNanos = finished - observation.callbackFinished;
      }
    }
  }

  long nanoTime() {
    return System.nanoTime();
  }

  private void requireExecutionBudget(long started) {
    if (nanoTime() - started >= TimeUnit.SECONDS.toNanos(EXECUTION_BUDGET_SECONDS)) {
      throw new TransactionTimedOutException("Database blob cleanup execution budget expired");
    }
  }

  private static long millis(long nanos) {
    return nanos < 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(nanos);
  }

  private static final class BatchObservation {
    String phase = "transaction_begin";
    boolean failed;
    boolean transactionCommitted;
    int selectedRows = -1;
    int deletedRows = -1;
    long selectionNanos = -1;
    long deleteNanos = -1;
    long transactionNanos = -1;
    long finalizationNanos = -1;
    long progressNanos = -1;
    Long callbackFinished;
  }

  private boolean hasEligibleRows(DatabaseBlobCleanupPolicy policy) {
    ZonedDateTime now = ZonedDateTime.now();
    Timestamp cutoff = Timestamp.from(now.minusDays(policy.getRetentionDays()).toInstant());
    String forceIndex = dbUtils.isMysql() ? " force index (UK__MBLOB__NAME)" : "";
    String sql =
        "select count(*) from (select id from mblob"
            + forceIndex
            + " where name >= ? and name < ?"
            + " and expire_after_seconds is not null and created_date < ?"
            + " and timestampadd(second, expire_after_seconds, created_date) < ? "
            + MBlobRepository.CLEANUP_TASK_SAFETY_PREDICATE
            + " limit 1) eligible";
    Integer count =
        jdbcTemplate.queryForObject(
            sql,
            Integer.class,
            policy.getPrefix(),
            prefixUpperBound(policy.getPrefix()),
            cutoff,
            Timestamp.from(now.toInstant()));
    return count != null && count > 0;
  }

  private void markStarted(long policyId) {
    transactionTemplate.executeWithoutResult(
        status -> {
          DatabaseBlobCleanupPolicy policy = getPolicy(policyId);
          policy.setStatus(STATUS_RUNNING);
          policy.setLastStartedDate(ZonedDateTime.now());
          policy.setLastFinishedDate(null);
          policy.setLastDeletedCount(0);
          policy.setLastError(null);
          policy.setStopRequested(false);
          policyRepository.save(policy);
        });
  }

  private void recordProgress(long policyId, int deletedRows) {
    transactionTemplate.executeWithoutResult(
        status -> {
          DatabaseBlobCleanupPolicy policy = getPolicy(policyId);
          policy.setLastDeletedCount(policy.getLastDeletedCount() + deletedRows);
          policy.setTotalDeletedCount(policy.getTotalDeletedCount() + deletedRows);
          policyRepository.save(policy);
        });
  }

  private void finish(long policyId, String finalStatus, String error) {
    finish(policyId, finalStatus, error, false);
  }

  private void finish(long policyId, String finalStatus, String error, boolean disable) {
    transactionTemplate.executeWithoutResult(
        status -> {
          DatabaseBlobCleanupPolicy policy = getPolicy(policyId);
          policy.setStatus(finalStatus);
          policy.setLastFinishedDate(ZonedDateTime.now());
          policy.setStopRequested(false);
          policy.setLastError(truncateError(error));
          if (disable) {
            policy.setEnabled(false);
          }
          policyRepository.save(policy);
        });
  }

  private void applyUpdate(DatabaseBlobCleanupPolicy policy, PolicyUpdate update, String prefix) {
    policy.setPrefix(prefix);
    policy.setEnabled(update.enabled());
    policy.setRetentionDays(
        requireRange(update.retentionDays(), 1, MAX_RETENTION_DAYS, "retentionDays"));
    policy.setBatchSize(requireRange(update.batchSize(), 1, MAX_BATCH_SIZE, "batchSize"));
    policy.setMaxBatchesPerRun(
        requireRange(update.maxBatchesPerRun(), 0, Integer.MAX_VALUE, "maxBatchesPerRun"));
    policy.setPauseMillis(requireRange(update.pauseMillis(), 0, MAX_PAUSE_MILLIS, "pauseMillis"));
    policy.setMaxRetries(requireRange(update.maxRetries(), 0, MAX_RETRIES, "maxRetries"));
  }

  private DatabaseBlobCleanupPolicy getPolicy(long policyId) {
    return policyRepository
        .findById(policyId)
        .orElseThrow(() -> new IllegalArgumentException("Cleanup policy not found: " + policyId));
  }

  private String normalizePrefix(String value) {
    String prefix = value == null ? "" : value.trim();
    if (!PREFIX_PATTERN.matcher(prefix).matches()) {
      throw new IllegalArgumentException(
          "Prefix must contain letters, numbers, underscores, or hyphens and end with '/'");
    }
    return prefix;
  }

  private int requireRange(int value, int minimum, int maximum, String field) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
    }
    return value;
  }

  static String prefixUpperBound(String prefix) {
    return prefix.substring(0, prefix.length() - 1) + "0";
  }

  private boolean isRetryableLockFailure(RuntimeException exception) {
    if (exception instanceof ConcurrencyFailureException) {
      return true;
    }
    if (!(exception instanceof DataAccessException)) {
      return false;
    }
    Throwable cause = exception;
    while (cause != null) {
      if (cause instanceof SQLException sqlException
          && (sqlException.getErrorCode() == 1205 || sqlException.getErrorCode() == 1213)) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  private boolean isExecutionTimeout(RuntimeException exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof QueryTimeoutException
          || cause instanceof jakarta.persistence.QueryTimeoutException
          || cause instanceof TransactionTimedOutException
          || cause instanceof SQLTimeoutException
          || (cause instanceof SQLException sql
              && (sql.getErrorCode() == 1317 || sql.getErrorCode() == 3024))) {
        return true;
      }
    }
    return false;
  }

  private int retryDelayMillis(DatabaseBlobCleanupPolicy policy, int retry) {
    return Math.min(MAX_PAUSE_MILLIS, Math.max(policy.getPauseMillis(), 250) * (retry + 1));
  }

  private void pause(int millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Database blob cleanup interrupted", e);
    }
  }

  private String truncateError(String error) {
    if (error == null || error.length() <= MAX_ERROR_LENGTH) {
      return error;
    }
    return error.substring(0, MAX_ERROR_LENGTH);
  }

  public record PolicyUpdate(
      String prefix,
      boolean enabled,
      int retentionDays,
      int batchSize,
      int maxBatchesPerRun,
      int pauseMillis,
      int maxRetries) {}
}
