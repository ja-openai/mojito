package com.box.l10n.mojito.service.blobstorage.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.box.l10n.mojito.entity.DatabaseBlobCleanupPolicy;
import com.box.l10n.mojito.entity.MBlob;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import java.time.ZonedDateTime;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class DatabaseBlobCleanupPolicyServiceTest extends ServiceTestBase {

  private static final String PREFIX = "pollable_task/";

  @Autowired DatabaseBlobCleanupPolicyRepository policyRepository;

  @Autowired DatabaseBlobCleanupPolicyService policyService;

  @Autowired MBlobRepository mBlobRepository;

  @Before
  public void cleanPoliciesAndBlobs() {
    policyRepository.deleteAll();
    deleteBlob("pollable_task/expired-1/input");
    deleteBlob("pollable_task/expired-2/input");
    deleteBlob("pollable_task/expired-3/input");
    deleteBlob("pollable_task/recent/input");
    deleteBlob("pollable_task/permanent/input");
    deleteBlob("other_cleanup/expired/input");
  }

  @Test
  public void drainsOnlyExpiredBlobsInTheConfiguredPrefix() {
    saveBlob("pollable_task/expired-1/input", 10, 86_400L);
    saveBlob("pollable_task/expired-2/input", 7, 86_400L);
    saveBlob("pollable_task/expired-3/input", 4, 86_400L);
    saveBlob("pollable_task/recent/input", 1, 86_400L);
    saveBlob("pollable_task/permanent/input", 10, null);
    saveBlob("other_cleanup/expired/input", 10, 86_400L);

    DatabaseBlobCleanupPolicy policy = createPolicy(true, 2, 0);

    policyService.runPolicy(policy.getId());

    DatabaseBlobCleanupPolicy completed = policyRepository.findById(policy.getId()).orElseThrow();
    assertEquals(DatabaseBlobCleanupPolicyService.STATUS_DRAINED, completed.getStatus());
    assertEquals(3L, completed.getLastDeletedCount());
    assertEquals(3L, completed.getTotalDeletedCount());
    assertNotNull(completed.getLastStartedDate());
    assertNotNull(completed.getLastFinishedDate());
    assertFalse(mBlobRepository.findByName("pollable_task/expired-1/input").isPresent());
    assertFalse(mBlobRepository.findByName("pollable_task/expired-2/input").isPresent());
    assertFalse(mBlobRepository.findByName("pollable_task/expired-3/input").isPresent());
    assertTrue(mBlobRepository.findByName("pollable_task/recent/input").isPresent());
    assertTrue(mBlobRepository.findByName("pollable_task/permanent/input").isPresent());
    assertTrue(mBlobRepository.findByName("other_cleanup/expired/input").isPresent());
  }

  @Test
  public void honorsOptionalBatchLimitAndResumesOnTheNextRun() {
    saveBlob("pollable_task/expired-1/input", 10, 86_400L);
    saveBlob("pollable_task/expired-2/input", 10, 86_400L);
    saveBlob("pollable_task/expired-3/input", 10, 86_400L);
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
  public void rejectsUnsafePrefixes() {
    try {
      policyService.createPolicy(
          new DatabaseBlobCleanupPolicyService.PolicyUpdate(
              "pollable_task/%", false, 3, 250, 0, 0, 5));
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

  private void deleteBlob(String name) {
    mBlobRepository.findByName(name).ifPresent(mBlobRepository::delete);
  }
}
