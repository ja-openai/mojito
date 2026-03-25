package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewAutomation;
import com.box.l10n.mojito.service.team.TeamSlackNotificationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReviewAutomationTriggerHealthCheckService {

  private static final Logger logger =
      LoggerFactory.getLogger(ReviewAutomationTriggerHealthCheckService.class);

  private final ReviewAutomationRepository reviewAutomationRepository;
  private final ReviewAutomationCronSchedulerService reviewAutomationCronSchedulerService;
  private final TeamSlackNotificationService teamSlackNotificationService;
  private final MeterRegistry meterRegistry;
  private final Duration alertCooldown;
  private final Map<Long, Instant> nextAlertEligibleAtByAutomationId = new ConcurrentHashMap<>();

  public ReviewAutomationTriggerHealthCheckService(
      ReviewAutomationRepository reviewAutomationRepository,
      ReviewAutomationCronSchedulerService reviewAutomationCronSchedulerService,
      TeamSlackNotificationService teamSlackNotificationService,
      MeterRegistry meterRegistry,
      @Value("${l10n.review-automation.trigger-health-check.alert-cooldown-ms:21600000}")
          long alertCooldownMs) {
    this.reviewAutomationRepository = reviewAutomationRepository;
    this.reviewAutomationCronSchedulerService = reviewAutomationCronSchedulerService;
    this.teamSlackNotificationService = teamSlackNotificationService;
    this.meterRegistry = meterRegistry;
    this.alertCooldown = Duration.ofMillis(Math.max(alertCooldownMs, 60_000L));
  }

  public void checkAndHealTriggers() {
    List<ReviewAutomation> automations = reviewAutomationRepository.findAllEnabledWithTeam();
    if (automations.isEmpty()) {
      return;
    }

    Map<Long, ReviewAutomationCronSchedulerService.TriggerHealth> healthByAutomationId =
        reviewAutomationCronSchedulerService.getTriggerHealths(
            automations.stream().map(ReviewAutomation::getId).toList());

    for (ReviewAutomation automation : automations) {
      ReviewAutomationCronSchedulerService.TriggerHealth initialHealth =
          healthByAutomationId.get(automation.getId());
      if (initialHealth == null || !initialHealth.repairRecommended()) {
        nextAlertEligibleAtByAutomationId.remove(automation.getId());
        continue;
      }

      meterRegistry
          .counter(
              "review_automation.trigger_health.detected", "status", initialHealth.status().name())
          .increment();

      logger.warn(
          "Review automation trigger unhealthy: automationId={}, status={}, quartzState={}",
          automation.getId(),
          initialHealth.status(),
          initialHealth.quartzState());

      ReviewAutomationCronSchedulerService.TriggerHealth healedHealth = initialHealth;
      try {
        meterRegistry
            .counter(
                "review_automation.trigger_health.repair_attempted",
                "status",
                initialHealth.status().name())
            .increment();
        healedHealth = reviewAutomationCronSchedulerService.repairTrigger(automation.getId());
      } catch (RuntimeException ex) {
        logger.warn(
            "Review automation trigger repair failed: automationId={}, message={}",
            automation.getId(),
            ex.getMessage(),
            ex);
      }

      if (!healedHealth.repairRecommended()) {
        nextAlertEligibleAtByAutomationId.remove(automation.getId());
        meterRegistry.counter("review_automation.trigger_health.repair_succeeded").increment();
        logger.info(
            "Review automation trigger recovered: automationId={}, status={}, quartzState={}",
            automation.getId(),
            healedHealth.status(),
            healedHealth.quartzState());
        continue;
      }

      maybeSendAlert(automation, healedHealth);
    }
  }

  private void maybeSendAlert(
      ReviewAutomation automation, ReviewAutomationCronSchedulerService.TriggerHealth health) {
    Instant now = Instant.now();
    Instant nextAlertEligibleAt = nextAlertEligibleAtByAutomationId.get(automation.getId());
    if (nextAlertEligibleAt != null && now.isBefore(nextAlertEligibleAt)) {
      return;
    }

    boolean sent =
        teamSlackNotificationService.sendReviewAutomationTriggerAlert(
            automation, health, "Trigger remains unhealthy after automatic repair.");
    if (sent) {
      nextAlertEligibleAtByAutomationId.put(automation.getId(), now.plus(alertCooldown));
      meterRegistry
          .counter("review_automation.trigger_health.alert_sent", "status", health.status().name())
          .increment();
    }
  }
}
