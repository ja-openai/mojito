package com.box.l10n.mojito.service.jsonconfiglocalization;

import static com.box.l10n.mojito.quartz.QuartzSchedulerManager.DEFAULT_SCHEDULER_NAME;

import com.box.l10n.mojito.entity.JsonConfigLocalizationEntity;
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
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Profile("!disablescheduling")
@Service
public class JsonConfigLocalizationCronSchedulerService {

  private static final Logger logger =
      LoggerFactory.getLogger(JsonConfigLocalizationCronSchedulerService.class);

  private static final String JOB_GROUP_NAME = "JSON_CONFIG_LOCALIZATION";
  private static final String DEFAULT_TIME_ZONE = "UTC";

  private final QuartzSchedulerManager quartzSchedulerManager;
  private final JsonConfigLocalizationRepository jsonConfigLocalizationRepository;

  public JsonConfigLocalizationCronSchedulerService(
      QuartzSchedulerManager quartzSchedulerManager,
      JsonConfigLocalizationRepository jsonConfigLocalizationRepository) {
    this.quartzSchedulerManager = quartzSchedulerManager;
    this.jsonConfigLocalizationRepository = jsonConfigLocalizationRepository;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void syncAllOnStartup() {
    syncAll();
  }

  public void syncAll() {
    try {
      Scheduler scheduler = getScheduler();
      List<JsonConfigLocalizationEntity> setups = jsonConfigLocalizationRepository.findAll();
      Set<JobKey> desiredJobKeys = new HashSet<>();
      Set<TriggerKey> desiredTriggerKeys = new HashSet<>();

      for (JsonConfigLocalizationEntity setup : setups) {
        if (!Boolean.TRUE.equals(setup.getAutomationEnabled())
            || setup.getAutomationCronExpression() == null
            || setup.getAutomationCronExpression().isBlank()) {
          continue;
        }

        JobKey jobKey = jobKey(setup.getId());
        TriggerKey triggerKey = triggerKey(setup.getId());
        desiredJobKeys.add(jobKey);
        desiredTriggerKeys.add(triggerKey);
        scheduler.addJob(buildJobDetail(setup), true);
        CronTrigger trigger = buildTrigger(setup, jobKey);

        if (scheduler.checkExists(triggerKey)) {
          Trigger.TriggerState triggerState = scheduler.getTriggerState(triggerKey);
          if (Trigger.TriggerState.ERROR.equals(triggerState)) {
            recreateTrigger(scheduler, setup, triggerKey, trigger);
          } else {
            scheduler.rescheduleJob(triggerKey, trigger);
            logger.info(
                "JSON config localization cron rescheduled: setupId={}, cron={}, timeZone={}",
                setup.getId(),
                setup.getAutomationCronExpression(),
                automationTimeZone(setup));
          }
        } else {
          scheduler.scheduleJob(trigger);
          logger.info(
              "JSON config localization cron scheduled: setupId={}, cron={}, timeZone={}",
              setup.getId(),
              setup.getAutomationCronExpression(),
              automationTimeZone(setup));
        }
      }

      for (TriggerKey existingTriggerKey :
          scheduler.getTriggerKeys(GroupMatcher.triggerGroupEquals(JOB_GROUP_NAME))) {
        if (!desiredTriggerKeys.contains(existingTriggerKey)) {
          scheduler.unscheduleJob(existingTriggerKey);
          logger.info("JSON config localization cron unscheduled: trigger={}", existingTriggerKey);
        }
      }

      for (JobKey existingJobKey :
          scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP_NAME))) {
        if (!desiredJobKeys.contains(existingJobKey)) {
          scheduler.deleteJob(existingJobKey);
          logger.info("JSON config localization cron deleted job: job={}", existingJobKey);
        }
      }
    } catch (SchedulerException e) {
      throw new RuntimeException("Failed to synchronize JSON config localization cron", e);
    }
  }

  private Scheduler getScheduler() throws SchedulerException {
    return quartzSchedulerManager.getScheduler(DEFAULT_SCHEDULER_NAME);
  }

  private JobDetail buildJobDetail(JsonConfigLocalizationEntity setup) {
    return JobBuilder.newJob(JsonConfigLocalizationCronJob.class)
        .withIdentity(jobKey(setup.getId()))
        .usingJobData(
            JsonConfigLocalizationCronJob.JSON_CONFIG_LOCALIZATION_ID, setup.getId().toString())
        .withDescription("Schedule JSON config localization automation")
        .storeDurably(true)
        .requestRecovery(true)
        .build();
  }

  private CronTrigger buildTrigger(JsonConfigLocalizationEntity setup, JobKey jobKey) {
    return TriggerBuilder.newTrigger()
        .withIdentity(triggerKey(setup.getId()))
        .forJob(jobKey)
        .withSchedule(
            CronScheduleBuilder.cronSchedule(setup.getAutomationCronExpression())
                .inTimeZone(TimeZone.getTimeZone(automationTimeZone(setup))))
        .build();
  }

  private void recreateTrigger(
      Scheduler scheduler,
      JsonConfigLocalizationEntity setup,
      TriggerKey triggerKey,
      CronTrigger trigger)
      throws SchedulerException {
    if (scheduler.checkExists(triggerKey)) {
      scheduler.unscheduleJob(triggerKey);
    }
    scheduler.scheduleJob(trigger);
    logger.info(
        "JSON config localization cron recreated: setupId={}, cron={}, timeZone={}",
        setup.getId(),
        setup.getAutomationCronExpression(),
        automationTimeZone(setup));
  }

  private String automationTimeZone(JsonConfigLocalizationEntity setup) {
    return setup.getAutomationTimeZone() == null || setup.getAutomationTimeZone().isBlank()
        ? DEFAULT_TIME_ZONE
        : setup.getAutomationTimeZone();
  }

  private JobKey jobKey(Long setupId) {
    return JobKey.jobKey("json_config_localization:" + setupId, JOB_GROUP_NAME);
  }

  private TriggerKey triggerKey(Long setupId) {
    return TriggerKey.triggerKey("json_config_localization:" + setupId, JOB_GROUP_NAME);
  }
}
