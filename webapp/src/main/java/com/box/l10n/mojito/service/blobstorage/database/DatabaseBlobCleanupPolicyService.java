package com.box.l10n.mojito.service.blobstorage.database;

import com.box.l10n.mojito.entity.DatabaseBlobCleanupPolicy;
import com.box.l10n.mojito.quartz.QuartzSchedulerManager;
import com.box.l10n.mojito.service.DBUtils;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.quartz.JobBuilder;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    this.dbUtils = Objects.requireNonNull(dbUtils);
    this.quartzSchedulerManager = Objects.requireNonNull(quartzSchedulerManager);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
    this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
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
        STATUS_RUNNING.equals(policy.getStatus()) ? STATUS_STOP_REQUESTED : STATUS_STOPPED);
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
    markStarted(policyId);
    int completedBatches = 0;
    int consecutiveRetries = 0;

    while (true) {
      DatabaseBlobCleanupPolicy policy = getPolicy(policyId);
      if (!policy.isEnabled() || policy.isStopRequested()) {
        finish(policyId, STATUS_STOPPED, null);
        return;
      }
      if (policy.getMaxBatchesPerRun() > 0 && completedBatches >= policy.getMaxBatchesPerRun()) {
        finish(policyId, STATUS_PAUSED, null);
        return;
      }

      try {
        int deletedRows = deleteBatch(policy);
        if (deletedRows == 0) {
          if (hasEligibleRows(policy)) {
            consecutiveRetries++;
            if (consecutiveRetries > policy.getMaxRetries()) {
              finish(policyId, STATUS_FAILED, "Eligible rows remain locked after retry limit");
              return;
            }
            pause(retryDelayMillis(policy, consecutiveRetries));
            continue;
          }
          finish(policyId, STATUS_DRAINED, null);
          return;
        }

        consecutiveRetries = 0;
        completedBatches++;
        recordProgress(policyId, deletedRows);
        meterRegistry
            .counter("DatabaseBlobStorage.policyCleanup.deletedRows", "prefix", policy.getPrefix())
            .increment(deletedRows);
        logger.info(
            "Database blob policy cleanup batch: policyId={}, prefix={}, batch={}, deletedRows={}",
            policyId,
            policy.getPrefix(),
            completedBatches,
            deletedRows);
        pause(policy.getPauseMillis());
      } catch (RuntimeException e) {
        if (isRetryableLockFailure(e) && consecutiveRetries < policy.getMaxRetries()) {
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

        finish(policyId, STATUS_FAILED, e.getMessage());
        logger.error(
            "Database blob policy cleanup failed: policyId={}, prefix={}",
            policyId,
            policy.getPrefix(),
            e);
        return;
      }
    }
  }

  private int deleteBatch(DatabaseBlobCleanupPolicy policy) {
    return Objects.requireNonNull(
        transactionTemplate.execute(
            status -> {
              Timestamp cutoff =
                  Timestamp.from(
                      ZonedDateTime.now().minusDays(policy.getRetentionDays()).toInstant());
              String forceIndex = dbUtils.isMysql() ? " force index (UK__MBLOB__NAME)" : "";
              String skipLocked = dbUtils.isMysql() ? " for update skip locked" : "";
              String sql =
                  "select id from mblob"
                      + forceIndex
                      + " where name >= ? and name < ?"
                      + " and expire_after_seconds is not null and created_date < ?"
                      + " order by name limit ?"
                      + skipLocked;
              List<Long> ids =
                  jdbcTemplate.queryForList(
                      sql,
                      Long.class,
                      policy.getPrefix(),
                      prefixUpperBound(policy.getPrefix()),
                      cutoff,
                      policy.getBatchSize());
              return ids.isEmpty() ? 0 : mBlobRepository.deleteByIds(ids);
            }));
  }

  private boolean hasEligibleRows(DatabaseBlobCleanupPolicy policy) {
    Timestamp cutoff =
        Timestamp.from(ZonedDateTime.now().minusDays(policy.getRetentionDays()).toInstant());
    String forceIndex = dbUtils.isMysql() ? " force index (UK__MBLOB__NAME)" : "";
    String sql =
        "select count(*) from (select id from mblob"
            + forceIndex
            + " where name >= ? and name < ?"
            + " and expire_after_seconds is not null and created_date < ? limit 1) eligible";
    Integer count =
        jdbcTemplate.queryForObject(
            sql, Integer.class, policy.getPrefix(), prefixUpperBound(policy.getPrefix()), cutoff);
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
    transactionTemplate.executeWithoutResult(
        status -> {
          DatabaseBlobCleanupPolicy policy = getPolicy(policyId);
          policy.setStatus(finalStatus);
          policy.setLastFinishedDate(ZonedDateTime.now());
          policy.setStopRequested(false);
          policy.setLastError(truncateError(error));
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
