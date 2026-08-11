package com.box.l10n.mojito.service.blobstorage.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import com.box.l10n.mojito.entity.DatabaseBlobCleanupSettings;
import com.box.l10n.mojito.entity.MBlob;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class DatabaseBlobCleanupServiceTest extends ServiceTestBase {

  @Autowired DatabaseBlobCleanupSettingsRepository settingsRepository;

  @Autowired DatabaseBlobCleanupService cleanupService;

  @Autowired MBlobRepository mBlobRepository;

  private String firstExpiredName;
  private String secondExpiredName;
  private String permanentName;
  private String longLivedName;

  @Before
  public void resetCleanupState() {
    settingsRepository.deleteAll();
    firstExpiredName = "pollable_task/expired-" + UUID.randomUUID();
    secondExpiredName = "ai_transalate_no_batch_output/expired-" + UUID.randomUUID();
    permanentName = "text_unit_ws_search_async/permanent-" + UUID.randomUUID();
    longLivedName = "review_project_request_search_async/long-lived-" + UUID.randomUUID();
  }

  @After
  public void removeTestBlobs() {
    List.of(firstExpiredName, secondExpiredName, permanentName, longLivedName)
        .forEach(name -> mBlobRepository.findByName(name).ifPresent(mBlobRepository::delete));
    settingsRepository.deleteAll();
  }

  @Test
  public void createsDisabledGlobalCleanupSettings() {
    DatabaseBlobCleanupSettings settings = cleanupService.getSettings();

    assertThat(settings.isEnabled()).isFalse();
    assertThat(settings.getBatchSize()).isEqualTo(500);
    assertThat(settings.getMaxBatchesPerRun()).isEqualTo(100);
    assertThat(settingsRepository.count()).isEqualTo(1);
  }

  @Test
  public void removesExpiredRowsAcrossAllPrefixesAndPreservesOtherRows() {
    saveBlob(firstExpiredName, 10, 86_400L);
    saveBlob(secondExpiredName, 7, 86_400L);
    saveBlob(permanentName, 10, null);
    saveBlob(longLivedName, 10, 20L * 86_400L);
    DatabaseBlobCleanupSettings settings =
        cleanupService.updateSettings(
            new DatabaseBlobCleanupService.SettingsUpdate(true, 1, 100, 0, 5));

    cleanupService.runCleanup(settings.getId());

    DatabaseBlobCleanupSettings completed =
        settingsRepository.findById(settings.getId()).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(DatabaseBlobCleanupService.STATUS_DRAINED);
    assertThat(completed.getLastDeletedCount()).isEqualTo(2);
    assertThat(completed.getTotalDeletedCount()).isEqualTo(2);
    assertThat(completed.getLastStartedDate()).isNotNull();
    assertThat(completed.getLastProgressDate()).isNotNull();
    assertThat(completed.getLastFinishedDate()).isNotNull();
    assertThat(mBlobRepository.findByName(firstExpiredName)).isEmpty();
    assertThat(mBlobRepository.findByName(secondExpiredName)).isEmpty();
    assertThat(mBlobRepository.findByName(permanentName)).isPresent();
    assertThat(mBlobRepository.findByName(longLivedName)).isPresent();
  }

  @Test
  public void skipsScheduledCleanupWhenDisabled() {
    saveBlob(firstExpiredName, 10, 86_400L);
    cleanupService.getSettings();

    cleanupService.runIfEnabled();

    assertThat(mBlobRepository.findByName(firstExpiredName)).isPresent();
  }

  @Test
  public void honorsConfiguredBatchLimitAndResumesNextRun() {
    saveBlob(firstExpiredName, 10, 86_400L);
    saveBlob(secondExpiredName, 10, 86_400L);
    DatabaseBlobCleanupSettings settings =
        cleanupService.updateSettings(
            new DatabaseBlobCleanupService.SettingsUpdate(true, 1, 1, 0, 5));

    cleanupService.runCleanup(settings.getId());

    DatabaseBlobCleanupSettings paused =
        settingsRepository.findById(settings.getId()).orElseThrow();
    assertThat(paused.getStatus()).isEqualTo(DatabaseBlobCleanupService.STATUS_PAUSED);
    assertThat(paused.getLastDeletedCount()).isEqualTo(1);

    cleanupService.runCleanup(settings.getId());

    DatabaseBlobCleanupSettings resumed =
        settingsRepository.findById(settings.getId()).orElseThrow();
    assertThat(resumed.getLastDeletedCount()).isEqualTo(1);
    assertThat(resumed.getTotalDeletedCount()).isEqualTo(2);
    assertThat(mBlobRepository.findByName(firstExpiredName)).isEmpty();
    assertThat(mBlobRepository.findByName(secondExpiredName)).isEmpty();
  }

  @Test
  public void stoppingCleanupDisablesFutureRuns() {
    cleanupService.updateSettings(
        new DatabaseBlobCleanupService.SettingsUpdate(true, 500, 100, 0, 5));

    DatabaseBlobCleanupSettings stopped = cleanupService.stop();

    assertThat(stopped.isEnabled()).isFalse();
    assertThat(stopped.getStatus()).isEqualTo(DatabaseBlobCleanupService.STATUS_STOPPED);
    assertThat(stopped.getLastError()).isNull();
  }

  @Test
  public void rejectsUnboundedCleanupSettings() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            cleanupService.updateSettings(
                new DatabaseBlobCleanupService.SettingsUpdate(true, 500, 0, 0, 5)));
  }

  @Test
  public void startsManualCleanupWithoutDeploymentProperties() throws InterruptedException {
    saveBlob(firstExpiredName, 10, 86_400L);
    cleanupService.updateSettings(
        new DatabaseBlobCleanupService.SettingsUpdate(false, 500, 100, 0, 5));

    DatabaseBlobCleanupSettings queued = cleanupService.start();

    assertThat(queued.isEnabled()).isTrue();
    waitForCondition(
        "Manual cleanup did not finish draining expired blobs",
        () ->
            settingsRepository
                .findById(queued.getId())
                .map(DatabaseBlobCleanupSettings::getStatus)
                .filter(DatabaseBlobCleanupService.STATUS_DRAINED::equals)
                .isPresent());

    DatabaseBlobCleanupSettings completed =
        settingsRepository.findById(queued.getId()).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(DatabaseBlobCleanupService.STATUS_DRAINED);
    assertThat(completed.getLastDeletedCount()).isEqualTo(1);
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
}
