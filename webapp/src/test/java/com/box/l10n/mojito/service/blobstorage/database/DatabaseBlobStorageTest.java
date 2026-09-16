package com.box.l10n.mojito.service.blobstorage.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.box.l10n.mojito.entity.MBlob;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.blobstorage.BlobStorage;
import com.box.l10n.mojito.service.blobstorage.BlobStorageTestShared;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.pollableTask.PollableTaskRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

public class DatabaseBlobStorageTest extends ServiceTestBase implements BlobStorageTestShared {

  @Autowired(required = false)
  DatabaseBlobStorage databaseBlobStorage;

  @Autowired MBlobRepository mBlobRepository;

  @Autowired PollableTaskRepository pollableTaskRepository;

  @Autowired MeterRegistry meterRegistry;

  @Override
  public BlobStorage getBlobStorage() {
    return databaseBlobStorage;
  }

  // Junit 4 doesn't seem to support test in interface, might be fixed in Junit 5 - revisit with
  // spring migration
  @Before
  @Override
  public void bbefore() {
    BlobStorageTestShared.super.bbefore();
  }

  @Test
  @Override
  public void testNoMatchString() {
    BlobStorageTestShared.super.testNoMatchString();
  }

  @Test
  @Override
  public void testNoMatchBytes() {
    BlobStorageTestShared.super.testNoMatchBytes();
  }

  @Test
  @Override
  public void testMatchString() {
    BlobStorageTestShared.super.testMatchString();
  }

  @Test
  @Override
  public void testMatchBytes() {
    BlobStorageTestShared.super.testMatchBytes();
  }

  @Test
  @Override
  public void testMatchMin1DayRetentionString() {
    BlobStorageTestShared.super.testMatchMin1DayRetentionString();
  }

  @Test
  @Override
  public void testMatchMin1DayRetentionBytes() {
    BlobStorageTestShared.super.testMatchMin1DayRetentionBytes();
  }

  @Test
  @Override
  public void testUpdatesWithPut() {
    BlobStorageTestShared.super.testUpdatesWithPut();
  }

  @Test
  public void getStoredBlobPreservesPermanentRetention() {
    String name = "permanent-" + UUID.randomUUID();
    byte[] content = "permanent-content".getBytes(StandardCharsets.UTF_8);
    databaseBlobStorage.put(name, content, Retention.PERMANENT);

    assertThat(databaseBlobStorage.getStoredBlob(name))
        .hasValueSatisfying(
            storedBlob -> {
              assertThat(storedBlob.content()).containsExactly(content);
              assertThat(storedBlob.retention()).isEqualTo(Retention.PERMANENT);
            });
  }

  @Test
  public void getStoredBlobPreservesTemporaryRetention() {
    String name = "temporary-" + UUID.randomUUID();
    byte[] content = "temporary-content".getBytes(StandardCharsets.UTF_8);
    databaseBlobStorage.put(name, content, Retention.MIN_1_DAY);

    assertThat(databaseBlobStorage.getStoredBlob(name))
        .hasValueSatisfying(
            storedBlob -> {
              assertThat(storedBlob.content()).containsExactly(content);
              assertThat(storedBlob.retention()).isEqualTo(Retention.MIN_1_DAY);
            });
  }

  @Test
  public void getStoredBlobIsEmptyWhenMissing() {
    assertThat(databaseBlobStorage.getStoredBlob("missing-" + UUID.randomUUID())).isEmpty();
  }

  @Test
  public void defaultTemporaryRetentionDoesNotExpireBeforeOneDay() {
    String prefix = "one-day-boundary-" + UUID.randomUUID();
    String withinDay = prefix + "-within";
    String beyondDay = prefix + "-expired";
    String permanent = prefix + "-permanent";
    databaseBlobStorage.put(withinDay, "retained", Retention.MIN_1_DAY);
    databaseBlobStorage.put(beyondDay, "expired", Retention.MIN_1_DAY);
    databaseBlobStorage.put(permanent, "permanent", Retention.PERMANENT);
    ZonedDateTime now = ZonedDateTime.now();
    setCreatedDate(withinDay, now.minusHours(23).minusMinutes(45));
    setCreatedDate(beyondDay, now.minusHours(25));
    setCreatedDate(permanent, now.minusDays(2));

    databaseBlobStorage.deleteExpired();

    assertThat(databaseBlobStorage.getString(withinDay)).contains("retained");
    assertThat(databaseBlobStorage.getString(beyondDay)).isEmpty();
    assertThat(databaseBlobStorage.getString(permanent)).contains("permanent");
  }

  @Test
  public void permanentOverwriteClearsPreviousExpiration() {
    String name = "retention-promotion-" + UUID.randomUUID();
    databaseBlobStorage.put(name, "temporary", Retention.MIN_1_DAY);
    setCreatedDate(name, ZonedDateTime.now().minusDays(2));
    MBlob before = mBlobRepository.findByName(name).orElseThrow();

    databaseBlobStorage.put(name, "permanent", Retention.PERMANENT);

    MBlob after = mBlobRepository.findByName(name).orElseThrow();
    assertThat(after.getId()).isEqualTo(before.getId());
    assertThat(after.getCreatedDate()).isEqualTo(before.getCreatedDate());
    assertThat(after.hasExpiration()).isFalse();
    assertThat(databaseBlobStorage.getStoredBlob(name))
        .hasValueSatisfying(
            blob -> {
              assertThat(blob.retention()).isEqualTo(Retention.PERMANENT);
              assertThat(blob.content()).isEqualTo("permanent".getBytes(StandardCharsets.UTF_8));
            });
    databaseBlobStorage.deleteExpired();
    assertThat(databaseBlobStorage.getString(name)).contains("permanent");
  }

  @Test
  public void temporaryOverwriteReappliesExpirationWithoutResettingCreationTime() {
    String name = "retention-demotion-" + UUID.randomUUID();
    databaseBlobStorage.put(name, "permanent", Retention.PERMANENT);
    setCreatedDate(name, ZonedDateTime.now().minusDays(2));
    MBlob before = mBlobRepository.findByName(name).orElseThrow();

    databaseBlobStorage.put(name, "temporary", Retention.MIN_1_DAY);

    MBlob after = mBlobRepository.findByName(name).orElseThrow();
    assertThat(after.getId()).isEqualTo(before.getId());
    assertThat(after.getCreatedDate()).isEqualTo(before.getCreatedDate());
    assertThat(after.hasExpiration()).isTrue();
    assertThat(databaseBlobStorage.getString(name)).contains("temporary");
    databaseBlobStorage.deleteExpired();
    assertThat(databaseBlobStorage.getString(name)).isEmpty();
  }

  @Test
  public void cleanupRechecksPermanentPromotionAndStopsAfterZeroDeletes() {
    assertCleanupRechecksRetention(true);
  }

  @Test
  public void cleanupRechecksExtendedExpiryAndStopsAfterZeroDeletes() {
    assertCleanupRechecksRetention(false);
  }

  @Test
  public void cleanupDeletionRespectsSelectedIdsAndStrictCutoff() {
    ZonedDateTime cutoff = ZonedDateTime.parse("2026-09-10T00:00:00Z");
    String prefix = "cleanup-delete-scope-" + UUID.randomUUID();
    MBlob expired = expiringBlob(prefix + "-expired", cutoff.minusSeconds(61), 60);
    MBlob boundary = expiringBlob(prefix + "-boundary", cutoff.minusSeconds(60), 60);
    MBlob unselected = expiringBlob(prefix + "-unselected", cutoff.minusSeconds(61), 60);

    assertThat(
            mBlobRepository.deleteExpiredByIds(List.of(expired.getId(), boundary.getId()), cutoff))
        .isEqualTo(1);

    assertThat(mBlobRepository.findById(expired.getId())).isEmpty();
    assertThat(mBlobRepository.findById(boundary.getId())).isPresent();
    assertThat(mBlobRepository.findById(unselected.getId())).isPresent();
  }

  @Test
  public void cleanupKeepsActiveTaskPayloadsAndResumesAfterCompletion() {
    PollableTask task = new PollableTask();
    task.setName("generic-cleanup-active-task");
    task = pollableTaskRepository.saveAndFlush(task);
    String name = "pollable_task/" + task.getId() + "/input";
    MBlob blob = expiringBlob(name, ZonedDateTime.now().minusDays(2), 60);
    try {
      assertThat(
              mBlobRepository.findExpiredBlobIdsWithNow(
                  ZonedDateTime.now(), PageRequest.of(0, 500)))
          .doesNotContain(blob.getId());
      assertThat(mBlobRepository.deleteExpiredByIds(List.of(blob.getId()), ZonedDateTime.now()))
          .isZero();
      databaseBlobStorage.deleteExpired();
      assertThat(mBlobRepository.findById(blob.getId())).isPresent();

      task.setFinishedDate(ZonedDateTime.now());
      pollableTaskRepository.saveAndFlush(task);
      databaseBlobStorage.deleteExpired();

      assertThat(mBlobRepository.findById(blob.getId())).isEmpty();
    } finally {
      mBlobRepository.findById(blob.getId()).ifPresent(mBlobRepository::delete);
      pollableTaskRepository.deleteById(task.getId());
    }
  }

  @Test
  public void cleanupRechecksTaskGraphBeforeDeletingCandidate() {
    PollableTask parent = new PollableTask();
    parent.setName("generic-cleanup-finished-task");
    parent.setFinishedDate(ZonedDateTime.now().minusDays(2));
    parent = pollableTaskRepository.saveAndFlush(parent);
    MBlob blob =
        expiringBlob(
            "pollable_task/" + parent.getId() + "/output", ZonedDateTime.now().minusDays(2), 60);
    PollableTask child = null;
    ZonedDateTime cutoff = ZonedDateTime.now();
    try {
      assertThat(mBlobRepository.findExpiredBlobIdsWithNow(cutoff, PageRequest.of(0, 500)))
          .contains(blob.getId());
      child = new PollableTask();
      child.setName("generic-cleanup-new-child");
      child.setParentTask(parent);
      child = pollableTaskRepository.saveAndFlush(child);

      assertThat(mBlobRepository.deleteExpiredByIds(List.of(blob.getId()), cutoff)).isZero();
      assertThat(mBlobRepository.findById(blob.getId())).isPresent();
    } finally {
      if (child != null && child.getId() != null) {
        pollableTaskRepository.deleteById(child.getId());
      }
      mBlobRepository.findById(blob.getId()).ifPresent(mBlobRepository::delete);
      pollableTaskRepository.deleteById(parent.getId());
    }
  }

  private MBlob expiringBlob(String name, ZonedDateTime createdDate, long ttlSeconds) {
    MBlob blob = new MBlob();
    blob.setName(name);
    blob.setCreatedDate(createdDate);
    blob.setExpireAfterSeconds(ttlSeconds);
    return mBlobRepository.saveAndFlush(blob);
  }

  private void assertCleanupRechecksRetention(boolean permanent) {
    String prefix = "cleanup-retention-race-" + UUID.randomUUID();
    String protectedName = prefix + "-protected";
    String expiredName = prefix + "-expired";
    databaseBlobStorage.put(protectedName, "before", Retention.MIN_1_DAY);
    databaseBlobStorage.put(expiredName, "expired", Retention.MIN_1_DAY);
    setCreatedDate(protectedName, ZonedDateTime.now().minusDays(2));
    setCreatedDate(expiredName, ZonedDateTime.now().minusDays(2));
    Long protectedId = mBlobRepository.findByName(protectedName).orElseThrow().getId();
    AtomicBoolean firstBatch = new AtomicBoolean(true);
    MBlobRepository interleaved = mock(MBlobRepository.class, delegatesTo(mBlobRepository));
    doAnswer(
            invocation -> {
              List<Long> ids =
                  mBlobRepository.findExpiredBlobIdsWithNow(
                      invocation.getArgument(0), invocation.getArgument(1));
              if (firstBatch.getAndSet(false)) {
                assertThat(ids).contains(protectedId);
                // Commit a retention change after selection, before the cleaner issues its DELETE.
                if (permanent) {
                  databaseBlobStorage.put(protectedName, "promoted", Retention.PERMANENT);
                } else {
                  MBlob blob = mBlobRepository.findByName(protectedName).orElseThrow();
                  blob.setExpireAfterSeconds(java.time.Duration.ofDays(3).toSeconds());
                  mBlobRepository.saveAndFlush(blob);
                }
                // Model a stale selection: after zero deletes the cleaner must not rescan it.
                return List.of(protectedId);
              }
              throw new AssertionError("Cleanup must stop after a zero-deletion batch");
            })
        .when(interleaved)
        .findExpiredBlobIdsWithNow(any(ZonedDateTime.class), any(Pageable.class));
    DatabaseBlobStorage cleaner =
        new DatabaseBlobStorage(
            databaseBlobStorage.databaseBlobStorageConfigurationProperties, interleaved,
            databaseBlobStorage.dataIntegrityViolationExceptionRetryTemplate, meterRegistry);

    cleaner.deleteExpired();

    assertThat(firstBatch.get()).isFalse();
    assertThat(databaseBlobStorage.getString(protectedName))
        .contains(permanent ? "promoted" : "before");
    assertThat(databaseBlobStorage.getString(expiredName)).contains("expired");

    // A later run with a fresh candidate query collects the remaining expired control.
    databaseBlobStorage.deleteExpired();
    assertThat(databaseBlobStorage.getString(protectedName))
        .contains(permanent ? "promoted" : "before");
    assertThat(databaseBlobStorage.getString(expiredName)).isEmpty();
  }

  private void setCreatedDate(String name, ZonedDateTime createdDate) {
    MBlob blob = mBlobRepository.findByName(name).orElseThrow();
    blob.setCreatedDate(createdDate);
    mBlobRepository.save(blob);
  }

  @Test
  public void testCleanup() {

    ZonedDateTime now = ZonedDateTime.now();
    MBlob notExpired = new MBlob();
    notExpired.setCreatedDate(now);
    notExpired.setExpireAfterSeconds(1000);
    notExpired.setName("not-expired");
    notExpired = mBlobRepository.save(notExpired);

    MBlob expired = new MBlob();
    expired.setCreatedDate(now.minusDays(1));
    expired.setExpireAfterSeconds(1000);
    expired.setName("expired");
    expired = mBlobRepository.save(expired);

    databaseBlobStorage.deleteExpired();

    assertNull(mBlobRepository.findById(expired.getId()).orElse(null));
    assertNotNull(mBlobRepository.findById(notExpired.getId()).orElse(null));
    assertTrue(meterRegistry.get(DatabaseBlobStorage.CLEANUP_DURATION_METRIC).timer().count() > 0);
    assertTrue(
        meterRegistry
                .get(DatabaseBlobStorage.CLEANUP_STEP_DURATION_METRIC)
                .tag("step", "findExpiredIds")
                .timer()
                .count()
            > 0);
    assertTrue(
        meterRegistry
                .get(DatabaseBlobStorage.CLEANUP_STEP_DURATION_METRIC)
                .tag("step", "deleteBatch")
                .timer()
                .count()
            > 0);
    assertTrue(
        meterRegistry.get(DatabaseBlobStorage.CLEANUP_DELETED_ROWS_METRIC).counter().count() > 0);
  }
}
