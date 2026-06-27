package com.box.l10n.mojito.service.blobstorage.database;

import com.box.l10n.mojito.entity.MBlob;
import com.box.l10n.mojito.retry.DataIntegrityViolationExceptionRetryTemplate;
import com.box.l10n.mojito.service.blobstorage.BlobStorage;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.google.common.base.Preconditions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation that use the database to store the blobs.
 *
 * <p>Expired blobs are cleaned up by {@link DatabaseBlobStorageCleanupJob}, time to live can be
 * configured with {@link DatabaseBlobStorageConfigurationProperties#min1DayTtl}
 *
 * <p>This is implementation should be used only for testing or for deployments with limited load.
 */
public class DatabaseBlobStorage implements BlobStorage {

  static Logger logger = LoggerFactory.getLogger(DatabaseBlobStorage.class);

  static final String CLEANUP_DURATION_METRIC = "DatabaseBlobStorage.cleanup.duration";
  static final String CLEANUP_STEP_DURATION_METRIC = "DatabaseBlobStorage.cleanup.step.duration";
  static final String CLEANUP_DELETED_ROWS_METRIC = "DatabaseBlobStorage.cleanup.deletedRows";

  DatabaseBlobStorageConfigurationProperties databaseBlobStorageConfigurationProperties;

  MBlobRepository mBlobRepository;

  DataIntegrityViolationExceptionRetryTemplate dataIntegrityViolationExceptionRetryTemplate;

  MeterRegistry meterRegistry;

  public DatabaseBlobStorage(
      DatabaseBlobStorageConfigurationProperties databaseBlobStorageConfigurationProperties,
      MBlobRepository mBlobRepository,
      DataIntegrityViolationExceptionRetryTemplate dataIntegrityViolationExceptionRetryTemplate,
      MeterRegistry meterRegistry) {

    Preconditions.checkNotNull(mBlobRepository);
    Preconditions.checkNotNull(databaseBlobStorageConfigurationProperties);
    Preconditions.checkNotNull(dataIntegrityViolationExceptionRetryTemplate);
    Preconditions.checkNotNull(meterRegistry);

    this.mBlobRepository = mBlobRepository;
    this.databaseBlobStorageConfigurationProperties = databaseBlobStorageConfigurationProperties;
    this.dataIntegrityViolationExceptionRetryTemplate =
        dataIntegrityViolationExceptionRetryTemplate;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public void put(String name, byte[] content, Retention retention) {

    dataIntegrityViolationExceptionRetryTemplate.execute(
        context -> {
          if (context.getRetryCount() > 0) {
            logger.info(
                "Assume concurrent modification happened, retry attempt: {}",
                context.getRetryCount());
          }
          putBase(name, content, retention);
          return null;
        });
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void putBase(String name, byte[] content, Retention retention) {
    MBlob mBlob =
        mBlobRepository
            .findByName(name)
            .orElseGet(
                () -> {
                  MBlob mb = new MBlob();
                  mb.setName(name);
                  return mb;
                });

    mBlob.setContent(content);

    if (Retention.MIN_1_DAY.equals(retention)) {
      mBlob.setExpireAfterSeconds(databaseBlobStorageConfigurationProperties.getMin1DayTtl());
    }

    mBlobRepository.save(mBlob);
  }

  @Override
  public void delete(String name) {
    mBlobRepository.findByName(name).ifPresent(mb -> mBlobRepository.deleteById(mb.getId()));
  }

  @Override
  public boolean exists(String name) {
    return mBlobRepository
        .findByName(name)
        .map(mb -> Boolean.TRUE)
        .orElse(Boolean.FALSE)
        .booleanValue();
  }

  @Override
  public Optional<byte[]> getBytes(String name) {
    byte[] bytes = null;
    return mBlobRepository.findByName(name).map(MBlob::getContent);
  }

  @Override
  public String getTargetDescription(String name) {
    return "mblob:" + name;
  }

  public void deleteExpired() {
    String cleanupRunId = UUID.randomUUID().toString();
    long cleanupStartNanos = System.nanoTime();
    int batches = 0;
    int totalDeletedRows = 0;
    int deletedCount;
    do {
      int batch = batches + 1;
      PageRequest pageable = PageRequest.of(0, 500);

      long findStartNanos = System.nanoTime();
      List<Long> expired = mBlobRepository.findExpiredBlobIdsWithNow(ZonedDateTime.now(), pageable);
      long findDurationNanos = System.nanoTime() - findStartNanos;
      long findDurationMs = nanosToMillis(findDurationNanos);
      recordCleanupStepDuration("findExpiredIds", findDurationNanos);

      long deleteDurationNanos = 0;
      if (!expired.isEmpty()) {
        long deleteStartNanos = System.nanoTime();
        deletedCount = mBlobRepository.deleteByIds(expired);
        deleteDurationNanos = System.nanoTime() - deleteStartNanos;
        recordCleanupStepDuration("deleteBatch", deleteDurationNanos);
        batches++;
        totalDeletedRows += deletedCount;
      } else {
        deletedCount = 0;
      }

      long deleteDurationMs = nanosToMillis(deleteDurationNanos);
      logger.debug(
          "Database blob cleanup batch: cleanupRunId={}, batch={}, findDurationMs={}, deleteDurationMs={}, batchSize={}, deletedRows={}",
          cleanupRunId,
          batch,
          findDurationMs,
          deleteDurationMs,
          expired.size(),
          deletedCount);
      if (isSlow(findDurationMs) || isSlow(deleteDurationMs)) {
        logger.warn(
            "Slow database blob cleanup batch: cleanupRunId={}, batch={}, findDurationMs={}, deleteDurationMs={}, batchSize={}, deletedRows={}, thresholdMs={}",
            cleanupRunId,
            batch,
            findDurationMs,
            deleteDurationMs,
            expired.size(),
            deletedCount,
            databaseBlobStorageConfigurationProperties.getCleanupSlowThresholdMs());
      }
    } while (deletedCount > 0);

    long cleanupDurationNanos = System.nanoTime() - cleanupStartNanos;
    long cleanupDurationMs = nanosToMillis(cleanupDurationNanos);
    meterRegistry.timer(CLEANUP_DURATION_METRIC).record(cleanupDurationNanos, TimeUnit.NANOSECONDS);
    meterRegistry.counter(CLEANUP_DELETED_ROWS_METRIC).increment(totalDeletedRows);

    if (isSlow(cleanupDurationMs)) {
      logger.warn(
          "Slow database blob cleanup: cleanupRunId={}, durationMs={}, batches={}, deletedRows={}, thresholdMs={}",
          cleanupRunId,
          cleanupDurationMs,
          batches,
          totalDeletedRows,
          databaseBlobStorageConfigurationProperties.getCleanupSlowThresholdMs());
    }
    logger.info(
        "Database blob cleanup finished: cleanupRunId={}, durationMs={}, batches={}, deletedRows={}",
        cleanupRunId,
        cleanupDurationMs,
        batches,
        totalDeletedRows);
  }

  private void recordCleanupStepDuration(String step, long durationNanos) {
    meterRegistry
        .timer(CLEANUP_STEP_DURATION_METRIC, "step", step)
        .record(durationNanos, TimeUnit.NANOSECONDS);
  }

  private boolean isSlow(long durationMs) {
    long thresholdMs = databaseBlobStorageConfigurationProperties.getCleanupSlowThresholdMs();
    return thresholdMs > 0 && durationMs >= thresholdMs;
  }

  private long nanosToMillis(long nanos) {
    return TimeUnit.NANOSECONDS.toMillis(nanos);
  }
}
