package com.box.l10n.mojito.service.blobstorage.database;

import com.box.l10n.mojito.entity.DatabaseBlobCleanupSettings;
import com.box.l10n.mojito.quartz.QuartzSchedulerManager;
import com.box.l10n.mojito.service.DBUtils;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
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
public class DatabaseBlobCleanupService {

  private static final Logger logger = LoggerFactory.getLogger(DatabaseBlobCleanupService.class);

  private static final int MAX_BATCH_SIZE = 5_000;
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

  private final DatabaseBlobCleanupSettingsRepository settingsRepository;
  private final MBlobRepository mBlobRepository;
  private final JdbcTemplate jdbcTemplate;
  private final DBUtils dbUtils;
  private final QuartzSchedulerManager quartzSchedulerManager;
  private final MeterRegistry meterRegistry;
  private final TransactionTemplate transactionTemplate;

  public DatabaseBlobCleanupService(
      DatabaseBlobCleanupSettingsRepository settingsRepository,
      MBlobRepository mBlobRepository,
      JdbcTemplate jdbcTemplate,
      DBUtils dbUtils,
      QuartzSchedulerManager quartzSchedulerManager,
      MeterRegistry meterRegistry,
      PlatformTransactionManager transactionManager) {
    this.settingsRepository = Objects.requireNonNull(settingsRepository);
    this.mBlobRepository = Objects.requireNonNull(mBlobRepository);
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    this.dbUtils = Objects.requireNonNull(dbUtils);
    this.quartzSchedulerManager = Objects.requireNonNull(quartzSchedulerManager);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
    transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @Transactional
  public DatabaseBlobCleanupSettings getSettings() {
    return settingsRepository
        .findFirstByOrderByIdAsc()
        .orElseGet(() -> settingsRepository.saveAndFlush(new DatabaseBlobCleanupSettings()));
  }

  @Transactional
  public DatabaseBlobCleanupSettings updateSettings(SettingsUpdate update) {
    DatabaseBlobCleanupSettings settings = getSettings();
    settings.setEnabled(update.enabled());
    settings.setBatchSize(requireRange(update.batchSize(), 1, MAX_BATCH_SIZE, "batchSize"));
    settings.setMaxBatchesPerRun(
        requireRange(update.maxBatchesPerRun(), 1, Integer.MAX_VALUE, "maxBatchesPerRun"));
    settings.setPauseMillis(requireRange(update.pauseMillis(), 0, MAX_PAUSE_MILLIS, "pauseMillis"));
    settings.setMaxRetries(requireRange(update.maxRetries(), 0, MAX_RETRIES, "maxRetries"));
    if (!settings.isEnabled() && STATUS_RUNNING.equals(settings.getStatus())) {
      settings.setStopRequested(true);
      settings.setStatus(STATUS_STOP_REQUESTED);
    }
    return settingsRepository.save(settings);
  }

  public DatabaseBlobCleanupSettings start() {
    DatabaseBlobCleanupSettings settings =
        transactionTemplate.execute(
            status -> {
              DatabaseBlobCleanupSettings current = getSettings();
              current.setEnabled(true);
              current.setStopRequested(false);
              if (!STATUS_RUNNING.equals(current.getStatus())) {
                current.setStatus(STATUS_QUEUED);
              }
              current.setLastError(null);
              return settingsRepository.saveAndFlush(current);
            });

    try {
      Scheduler scheduler =
          quartzSchedulerManager.getScheduler(QuartzSchedulerManager.DEFAULT_SCHEDULER_NAME);
      JobKey jobKey = JobKey.jobKey(DatabaseBlobStorageCleanupJob.JOB_NAME);
      if (!scheduler.checkExists(jobKey)) {
        scheduler.addJob(
            JobBuilder.newJob(DatabaseBlobStorageCleanupJob.class)
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
    return settings;
  }

  @Transactional
  public DatabaseBlobCleanupSettings stop() {
    DatabaseBlobCleanupSettings settings = getSettings();
    settings.setEnabled(false);
    settings.setStopRequested(true);
    settings.setStatus(
        STATUS_RUNNING.equals(settings.getStatus()) ? STATUS_STOP_REQUESTED : STATUS_STOPPED);
    return settingsRepository.save(settings);
  }

  public void runIfEnabled() {
    settingsRepository
        .findFirstByOrderByIdAsc()
        .filter(DatabaseBlobCleanupSettings::isEnabled)
        .map(DatabaseBlobCleanupSettings::getId)
        .ifPresent(this::runCleanup);
  }

  void runCleanup(long settingsId) {
    markStarted(settingsId);
    int completedBatches = 0;
    int consecutiveRetries = 0;

    while (true) {
      DatabaseBlobCleanupSettings settings = getSettings(settingsId);
      if (!settings.isEnabled() || settings.isStopRequested()) {
        finish(settingsId, STATUS_STOPPED, null);
        return;
      }
      if (completedBatches >= settings.getMaxBatchesPerRun()) {
        finish(settingsId, STATUS_PAUSED, null);
        return;
      }

      try {
        int deletedRows = deleteBatch(settings);
        if (deletedRows == 0) {
          if (hasExpiredRows()) {
            consecutiveRetries++;
            if (consecutiveRetries > settings.getMaxRetries()) {
              finish(settingsId, STATUS_FAILED, "Expired rows remain locked after retry limit");
              return;
            }
            pause(retryDelayMillis(settings, consecutiveRetries));
            continue;
          }
          finish(settingsId, STATUS_DRAINED, null);
          return;
        }

        consecutiveRetries = 0;
        completedBatches++;
        recordProgress(settingsId, deletedRows);
        meterRegistry
            .counter(DatabaseBlobStorage.CLEANUP_DELETED_ROWS_METRIC)
            .increment(deletedRows);
        logger.info(
            "Database blob cleanup batch: settingsId={}, batch={}, deletedRows={}",
            settingsId,
            completedBatches,
            deletedRows);
        pause(settings.getPauseMillis());
      } catch (RuntimeException e) {
        if (isRetryableLockFailure(e) && consecutiveRetries < settings.getMaxRetries()) {
          consecutiveRetries++;
          logger.warn(
              "Database blob cleanup lock conflict: settingsId={}, retry={}, maxRetries={}",
              settingsId,
              consecutiveRetries,
              settings.getMaxRetries(),
              e);
          pause(retryDelayMillis(settings, consecutiveRetries));
          continue;
        }

        finish(settingsId, STATUS_FAILED, e.getMessage());
        logger.error("Database blob cleanup failed: settingsId={}", settingsId, e);
        return;
      }
    }
  }

  private int deleteBatch(DatabaseBlobCleanupSettings settings) {
    return Objects.requireNonNull(
        transactionTemplate.execute(
            status -> {
              Timestamp now = Timestamp.from(ZonedDateTime.now().toInstant());
              String forceIndex =
                  dbUtils.isMysql() ? " force index (I__MBLOB__EXPIRATION_DATE_ID)" : "";
              String skipLocked = dbUtils.isMysql() ? " for update skip locked" : "";
              String sql =
                  "select id from mblob"
                      + forceIndex
                      + " where expiration_date < ? order by expiration_date, id limit ?"
                      + skipLocked;
              List<Long> ids =
                  jdbcTemplate.queryForList(sql, Long.class, now, settings.getBatchSize());
              return ids.isEmpty() ? 0 : mBlobRepository.deleteByIds(ids);
            }));
  }

  private boolean hasExpiredRows() {
    String forceIndex = dbUtils.isMysql() ? " force index (I__MBLOB__EXPIRATION_DATE_ID)" : "";
    String sql =
        "select count(*) from (select id from mblob"
            + forceIndex
            + " where expiration_date < ? limit 1) expired";
    Integer count =
        jdbcTemplate.queryForObject(
            sql, Integer.class, Timestamp.from(ZonedDateTime.now().toInstant()));
    return count != null && count > 0;
  }

  private void markStarted(long settingsId) {
    transactionTemplate.executeWithoutResult(
        status -> {
          DatabaseBlobCleanupSettings settings = getSettings(settingsId);
          settings.setStatus(STATUS_RUNNING);
          settings.setLastStartedDate(ZonedDateTime.now());
          settings.setLastProgressDate(settings.getLastStartedDate());
          settings.setLastFinishedDate(null);
          settings.setLastDeletedCount(0);
          settings.setLastError(null);
          settings.setStopRequested(false);
          settingsRepository.save(settings);
        });
  }

  private void recordProgress(long settingsId, int deletedRows) {
    transactionTemplate.executeWithoutResult(
        status -> {
          DatabaseBlobCleanupSettings settings = getSettings(settingsId);
          settings.setLastDeletedCount(settings.getLastDeletedCount() + deletedRows);
          settings.setTotalDeletedCount(settings.getTotalDeletedCount() + deletedRows);
          settings.setLastProgressDate(ZonedDateTime.now());
          settingsRepository.save(settings);
        });
  }

  private void finish(long settingsId, String finalStatus, String error) {
    transactionTemplate.executeWithoutResult(
        status -> {
          DatabaseBlobCleanupSettings settings = getSettings(settingsId);
          settings.setStatus(finalStatus);
          settings.setLastFinishedDate(ZonedDateTime.now());
          settings.setStopRequested(false);
          settings.setLastError(truncateError(error));
          settingsRepository.save(settings);
        });
  }

  private DatabaseBlobCleanupSettings getSettings(long settingsId) {
    return settingsRepository
        .findById(settingsId)
        .orElseThrow(
            () -> new IllegalArgumentException("Cleanup settings not found: " + settingsId));
  }

  private int requireRange(int value, int minimum, int maximum, String field) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
    }
    return value;
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

  private int retryDelayMillis(DatabaseBlobCleanupSettings settings, int retry) {
    return Math.min(MAX_PAUSE_MILLIS, Math.max(settings.getPauseMillis(), 250) * (retry + 1));
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

  public record SettingsUpdate(
      boolean enabled, int batchSize, int maxBatchesPerRun, int pauseMillis, int maxRetries) {}
}
