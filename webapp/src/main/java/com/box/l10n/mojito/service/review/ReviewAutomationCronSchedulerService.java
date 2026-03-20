package com.box.l10n.mojito.service.review;

import static com.box.l10n.mojito.quartz.QuartzSchedulerManager.DEFAULT_SCHEDULER_NAME;

import com.box.l10n.mojito.entity.review.ReviewAutomation;
import com.box.l10n.mojito.quartz.QuartzSchedulerManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
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
      Scheduler scheduler = quartzSchedulerManager.getScheduler(DEFAULT_SCHEDULER_NAME);

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
          scheduler.rescheduleJob(triggerKey, trigger);
          logger.info(
              "Review automation cron rescheduled: automationId={}, cron={}, timeZone={}",
              automation.getId(),
              automation.getCronExpression(),
              automation.getTimeZone());
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

  private TriggerKey triggerKey(Long automationId) {
    return TriggerKey.triggerKey("triggerReviewAutomationCron-" + automationId, JOB_GROUP_NAME);
  }

  private JobKey jobKey(Long automationId) {
    return JobKey.jobKey("jobReviewAutomationCron-" + automationId, JOB_GROUP_NAME);
  }
}
