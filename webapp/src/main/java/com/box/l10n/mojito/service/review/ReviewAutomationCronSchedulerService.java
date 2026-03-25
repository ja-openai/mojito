package com.box.l10n.mojito.service.review;

import static com.box.l10n.mojito.quartz.QuartzSchedulerManager.DEFAULT_SCHEDULER_NAME;

import com.box.l10n.mojito.entity.review.ReviewAutomation;
import com.box.l10n.mojito.quartz.QuartzSchedulerManager;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("!disablescheduling")
@Service
public class ReviewAutomationCronSchedulerService {

  private static final Logger logger =
      LoggerFactory.getLogger(ReviewAutomationCronSchedulerService.class);

  private static final String JOB_GROUP_NAME = "REVIEW_AUTOMATION";

  private final QuartzSchedulerManager quartzSchedulerManager;
  private final ReviewAutomationRepository reviewAutomationRepository;

  public ReviewAutomationCronSchedulerService(
      QuartzSchedulerManager quartzSchedulerManager,
      ReviewAutomationRepository reviewAutomationRepository) {
    this.quartzSchedulerManager = quartzSchedulerManager;
    this.reviewAutomationRepository = reviewAutomationRepository;
  }

  public void syncAll() {
    try {
      Scheduler scheduler = getScheduler();

      List<ReviewAutomation> automations = reviewAutomationRepository.findAll();
      Set<JobKey> desiredJobKeys = new HashSet<>();
      Set<TriggerKey> desiredTriggerKeys = new HashSet<>();
      for (ReviewAutomation automation : automations) {
        if (!Boolean.TRUE.equals(automation.getEnabled())
            || automation.getCronExpression() == null
            || automation.getCronExpression().isBlank()) {
          continue;
        }

        JobKey jobKey = jobKey(automation.getId());
        TriggerKey triggerKey = triggerKey(automation.getId());
        desiredJobKeys.add(jobKey);
        desiredTriggerKeys.add(triggerKey);
        scheduler.addJob(buildJobDetail(automation), true);
        CronTrigger trigger = buildTrigger(automation, jobKey);

        if (scheduler.checkExists(triggerKey)) {
          Trigger.TriggerState triggerState = scheduler.getTriggerState(triggerKey);
          if (Trigger.TriggerState.ERROR.equals(triggerState)) {
            recreateTrigger(scheduler, automation, triggerKey, trigger);
          } else {
            scheduler.rescheduleJob(triggerKey, trigger);
            logger.info(
                "Review automation cron rescheduled: automationId={}, cron={}, timeZone={}",
                automation.getId(),
                automation.getCronExpression(),
                automation.getTimeZone());
          }
        } else {
          scheduler.scheduleJob(trigger);
          logger.info(
              "Review automation cron scheduled: automationId={}, cron={}, timeZone={}",
              automation.getId(),
              automation.getCronExpression(),
              automation.getTimeZone());
        }
      }

      for (TriggerKey existingTriggerKey :
          scheduler.getTriggerKeys(GroupMatcher.triggerGroupEquals(JOB_GROUP_NAME))) {
        if (!desiredTriggerKeys.contains(existingTriggerKey)) {
          scheduler.unscheduleJob(existingTriggerKey);
          logger.info("Review automation cron unscheduled: trigger={}", existingTriggerKey);
        }
      }

      for (JobKey existingJobKey :
          scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP_NAME))) {
        if (!desiredJobKeys.contains(existingJobKey)) {
          scheduler.deleteJob(existingJobKey);
          logger.info("Review automation cron deleted job: job={}", existingJobKey);
        }
      }
    } catch (SchedulerException e) {
      throw new RuntimeException("Failed to synchronize review automation cron", e);
    }
  }

  public Map<Long, TriggerHealth> getTriggerHealths(List<Long> automationIds) {
    if (automationIds == null || automationIds.isEmpty()) {
      return Map.of();
    }
    try {
      Scheduler scheduler = getScheduler();
      Map<Long, ReviewAutomation> automationById = new LinkedHashMap<>();
      for (ReviewAutomation automation :
          reviewAutomationRepository.findAllByIdsWithTeam(automationIds)) {
        automationById.put(automation.getId(), automation);
      }

      Map<Long, TriggerHealth> healthByAutomationId = new LinkedHashMap<>();
      for (Long automationId : automationIds) {
        ReviewAutomation automation = automationById.get(automationId);
        healthByAutomationId.put(
            automationId, getTriggerHealth(scheduler, automationId, automation));
      }
      return healthByAutomationId;
    } catch (SchedulerException e) {
      throw new RuntimeException("Failed to load review automation trigger status", e);
    }
  }

  public TriggerHealth getTriggerHealth(Long automationId) {
    ReviewAutomation automation = reviewAutomationRepository.findById(automationId).orElse(null);
    try {
      return getTriggerHealth(getScheduler(), automationId, automation);
    } catch (SchedulerException e) {
      throw new RuntimeException("Failed to load review automation trigger status", e);
    }
  }

  public TriggerHealth repairTrigger(Long automationId) {
    ReviewAutomation automation =
        reviewAutomationRepository
            .findById(automationId)
            .orElseThrow(
                () -> new IllegalArgumentException("Review automation not found: " + automationId));
    if (!Boolean.TRUE.equals(automation.getEnabled())) {
      throw new IllegalArgumentException("Review automation must be enabled to repair trigger");
    }
    if (automation.getCronExpression() == null || automation.getCronExpression().isBlank()) {
      throw new IllegalArgumentException("Review automation cron expression is required");
    }

    try {
      Scheduler scheduler = getScheduler();
      JobKey jobKey = jobKey(automation.getId());
      scheduler.addJob(buildJobDetail(automation), true);
      TriggerKey triggerKey = triggerKey(automation.getId());
      recreateTrigger(scheduler, automation, triggerKey, buildTrigger(automation, jobKey));
      return getTriggerHealth(scheduler, automation.getId(), automation);
    } catch (SchedulerException e) {
      throw new RuntimeException("Failed to repair review automation trigger", e);
    }
  }

  public enum TriggerStatus {
    HEALTHY,
    PAUSED,
    ERROR,
    MISSING
  }

  public record TriggerHealth(
      Long automationId,
      TriggerStatus status,
      String quartzState,
      ZonedDateTime nextRunAt,
      ZonedDateTime previousRunAt,
      boolean repairRecommended) {}

  private Scheduler getScheduler() throws SchedulerException {
    return quartzSchedulerManager.getScheduler(DEFAULT_SCHEDULER_NAME);
  }

  private JobDetail buildJobDetail(ReviewAutomation automation) {
    return JobBuilder.newJob(ReviewAutomationCronJob.class)
        .withIdentity(jobKey(automation.getId()))
        .usingJobData(ReviewAutomationCronJob.REVIEW_AUTOMATION_ID, automation.getId().toString())
        .withDescription("Schedule automatic review automation jobs")
        .storeDurably(true)
        .requestRecovery(true)
        .build();
  }

  private CronTrigger buildTrigger(ReviewAutomation automation, JobKey jobKey) {
    return TriggerBuilder.newTrigger()
        .withIdentity(triggerKey(automation.getId()))
        .forJob(jobKey)
        .withSchedule(
            CronScheduleBuilder.cronSchedule(automation.getCronExpression())
                .inTimeZone(TimeZone.getTimeZone(automation.getTimeZone())))
        .build();
  }

  private void recreateTrigger(
      Scheduler scheduler, ReviewAutomation automation, TriggerKey triggerKey, CronTrigger trigger)
      throws SchedulerException {
    if (scheduler.checkExists(triggerKey)) {
      scheduler.unscheduleJob(triggerKey);
    }
    scheduler.scheduleJob(trigger);
    logger.info(
        "Review automation cron recreated: automationId={}, cron={}, timeZone={}",
        automation.getId(),
        automation.getCronExpression(),
        automation.getTimeZone());
  }

  private TriggerHealth getTriggerHealth(
      Scheduler scheduler, Long automationId, ReviewAutomation automation)
      throws SchedulerException {
    TriggerKey triggerKey = triggerKey(automationId);
    if (!scheduler.checkExists(triggerKey)) {
      return new TriggerHealth(automationId, TriggerStatus.MISSING, "NONE", null, null, true);
    }

    Trigger.TriggerState triggerState = scheduler.getTriggerState(triggerKey);
    Trigger trigger = scheduler.getTrigger(triggerKey);
    ZoneId zoneId = resolveZoneId(automation, trigger);
    ZonedDateTime nextRunAt =
        toZonedDateTime(trigger != null ? trigger.getNextFireTime() : null, zoneId);
    ZonedDateTime previousRunAt =
        toZonedDateTime(trigger != null ? trigger.getPreviousFireTime() : null, zoneId);

    TriggerStatus status =
        switch (triggerState) {
          case PAUSED -> TriggerStatus.PAUSED;
          case ERROR -> TriggerStatus.ERROR;
          case NONE, COMPLETE -> TriggerStatus.MISSING;
          case NORMAL, BLOCKED -> TriggerStatus.HEALTHY;
        };

    boolean repairRecommended =
        TriggerStatus.ERROR.equals(status) || TriggerStatus.MISSING.equals(status);
    return new TriggerHealth(
        automationId, status, triggerState.name(), nextRunAt, previousRunAt, repairRecommended);
  }

  private ZoneId resolveZoneId(ReviewAutomation automation, Trigger trigger) {
    try {
      if (automation != null
          && automation.getTimeZone() != null
          && !automation.getTimeZone().isBlank()) {
        return ZoneId.of(automation.getTimeZone());
      }
      if (trigger instanceof CronTrigger cronTrigger && cronTrigger.getTimeZone() != null) {
        return cronTrigger.getTimeZone().toZoneId();
      }
    } catch (Exception e) {
      logger.debug("Falling back to system default zone for review automation trigger", e);
    }
    return ZoneId.systemDefault();
  }

  private ZonedDateTime toZonedDateTime(Date date, ZoneId zoneId) {
    if (date == null) {
      return null;
    }
    return ZonedDateTime.ofInstant(date.toInstant(), zoneId);
  }

  private TriggerKey triggerKey(Long automationId) {
    return TriggerKey.triggerKey("triggerReviewAutomationCron-" + automationId, JOB_GROUP_NAME);
  }

  private JobKey jobKey(Long automationId) {
    return JobKey.jobKey("jobReviewAutomationCron-" + automationId, JOB_GROUP_NAME);
  }
}
