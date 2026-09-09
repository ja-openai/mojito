package com.box.l10n.mojito.service.oaireview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import com.box.l10n.mojito.entity.AiReviewRequestUsage;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.security.Role;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.oaireview.AiReviewRequestUsageService.StartInput;
import com.box.l10n.mojito.service.security.user.UserDeletionService;
import com.box.l10n.mojito.service.security.user.UserService;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class AiReviewRequestUsageServiceTest extends ServiceTestBase {

  @Autowired AiReviewRequestUsageService usageService;
  @Autowired AiReviewRequestUsageRepository usageRepository;
  @Autowired UserService userService;
  @Autowired UserDeletionService userDeletionService;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  public void recordsResolvedSettingsAndExplicitActorSeparatelyFromReturnedSettings() {
    User user = createUser();
    Long id =
        usageService.start(
            new StartInput(
                user.getId(),
                123L,
                456L,
                "uk",
                "review_project",
                "follow_up",
                "ultra",
                "requested-model",
                "max",
                "priority"));

    AiReviewRequestUsage started = usageRepository.findById(id).orElseThrow();
    assertEquals(user.getId(), started.getUser().getId());
    assertEquals(Long.valueOf(123), started.getPollableTaskId());
    assertEquals(Long.valueOf(456), started.getTmTextUnitId());
    assertEquals("uk", started.getLocale());
    assertEquals("review_project", started.getSurface());
    assertEquals("follow_up", started.getRequestType());
    assertEquals("ultra", started.getProfileId());
    assertEquals("requested-model", started.getModelName());
    assertEquals("max", started.getReasoningEffort());
    assertEquals("priority", started.getRequestedServiceTier());
    assertEquals("started", started.getStatus());
    assertNotNull(started.getStartedAt());
    assertNull(started.getFinishedAt());
    assertNull(started.getDurationMs());

    usageService.finish(id, "completed", 2400L, "returned-model", "default");

    AiReviewRequestUsage finished = usageRepository.findById(id).orElseThrow();
    assertEquals("completed", finished.getStatus());
    assertEquals(Long.valueOf(2400), finished.getDurationMs());
    assertNotNull(finished.getFinishedAt());
    assertEquals(started.getStartedAt(), finished.getStartedAt());
    assertEquals("requested-model", finished.getModelName());
    assertEquals("returned-model", finished.getReturnedModel());
    assertEquals("priority", finished.getRequestedServiceTier());
    assertEquals("default", finished.getReturnedServiceTier());

    usageService.finish(id, "failed", 3000L, null, null);
    AiReviewRequestUsage repeated = usageRepository.findById(id).orElseThrow();
    assertEquals("completed", repeated.getStatus());
    assertEquals(finished.getFinishedAt(), repeated.getFinishedAt());
    assertEquals(Long.valueOf(2400), repeated.getDurationMs());
  }

  @Test
  public void commitsStartAndFinishEvenWhenTheCallerTransactionRollsBack() {
    TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);
    Long id =
        callerTransaction.execute(
            transaction -> {
              Long usageId = usageService.start(legacyInput("en"));
              transaction.setRollbackOnly();
              return usageId;
            });
    assertEquals("started", usageRepository.findById(id).orElseThrow().getStatus());

    callerTransaction.executeWithoutResult(
        transaction -> {
          usageService.finish(id, "timeout", 120000L, null, null);
          transaction.setRollbackOnly();
        });
    AiReviewRequestUsage usage = usageRepository.findById(id).orElseThrow();
    assertEquals("timeout", usage.getStatus());
    assertEquals(Long.valueOf(120000), usage.getDurationMs());
  }

  @Test
  public void allowsLegacyRequestsWithoutActorOrTaskAndRecordsFailureKinds() {
    for (String status : List.of("timeout", "provider_failed", "failed")) {
      Long id = usageService.start(legacyInput(null));
      usageService.finish(id, status, 100L, null, null);
      AiReviewRequestUsage usage = usageRepository.findById(id).orElseThrow();
      assertNull(usage.getUser());
      assertNull(usage.getPollableTaskId());
      assertNull(usage.getTmTextUnitId());
      assertNull(usage.getLocale());
      assertEquals("legacy", usage.getRequestType());
      assertEquals(status, usage.getStatus());
      assertNotNull(usage.getFinishedAt());
      assertNull(usage.getReturnedModel());
      assertNull(usage.getReturnedServiceTier());
    }
  }

  @Test
  public void deletingActorRetainsAnonymousUsage() {
    User user = createUser();
    Long id =
        usageService.start(
            new StartInput(
                user.getId(),
                null,
                null,
                "fr",
                "text_unit_detail",
                "manual",
                "version_a",
                "model-name",
                "low",
                null));
    userDeletionService.hardDeleteUser(user.getId());

    AiReviewRequestUsage usage = usageRepository.findById(id).orElseThrow();
    assertNull(usage.getUser());
    assertEquals("manual", usage.getRequestType());
    assertEquals("version_a", usage.getProfileId());
  }

  @Test
  public void rejectsUnboundedMetadataAndInvalidTerminalStatesWithoutPartialUpdates() {
    assertThrows(
        IllegalArgumentException.class, () -> usageService.start(legacyInput("x".repeat(65))));
    Long id = usageService.start(legacyInput("de"));
    assertThrows(
        IllegalArgumentException.class, () -> usageService.finish(id, "started", 1L, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> usageService.finish(id, "completed", -1L, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> usageService.finish(id, "completed", 1L, "x".repeat(256), null));

    AiReviewRequestUsage usage = usageRepository.findById(id).orElseThrow();
    assertEquals("started", usage.getStatus());
    assertNull(usage.getFinishedAt());
    assertNull(usage.getDurationMs());
  }

  private StartInput legacyInput(String locale) {
    return new StartInput(
        null, null, null, locale, "unknown", "legacy", "version_b", "model-name", null, null);
  }

  private User createUser() {
    return userService.createUserWithRole(
        "review-usage-" + UUID.randomUUID(), "test", Role.ROLE_TRANSLATOR);
  }
}
