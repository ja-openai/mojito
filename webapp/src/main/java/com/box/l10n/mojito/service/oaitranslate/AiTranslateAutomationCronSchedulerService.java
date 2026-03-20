package com.box.l10n.mojito.service.oaitranslate;

import static com.box.l10n.mojito.quartz.QuartzConfig.DYNAMIC_GROUP_NAME;
import static com.box.l10n.mojito.quartz.QuartzSchedulerManager.DEFAULT_SCHEDULER_NAME;

import com.box.l10n.mojito.quartz.QuartzSchedulerManager;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("!disablescheduling")
@Service
public class AiTranslateAutomationCronSchedulerService {

  private static final Logger logger =
      LoggerFactory.getLogger(AiTranslateAutomationCronSchedulerService.class);

  private static final TriggerKey TRIGGER_KEY =
      TriggerKey.triggerKey("triggerAiTranslateAutomationCron", DYNAMIC_GROUP_NAME);

  private final QuartzSchedulerManager quartzSchedulerManager;
  private final JobDetail aiTranslateAutomationCronJobDetail;

  public AiTranslateAutomationCronSchedulerService(
      QuartzSchedulerManager quartzSchedulerManager,
      @Qualifier("aiTranslateAutomationCron") JobDetail aiTranslateAutomationCronJobDetail) {
    this.quartzSchedulerManager = quartzSchedulerManager;
    this.aiTranslateAutomationCronJobDetail = aiTranslateAutomationCronJobDetail;
  }

  public void syncConfig(AiTranslateAutomationConfigService.Config config) {
    syncCronExpression(config.enabled() ? config.cronExpression() : null);
  }

  public void syncCronExpression(String cronExpression) {
    try {
      Scheduler scheduler = quartzSchedulerManager.getScheduler(DEFAULT_SCHEDULER_NAME);
      if (cronExpression == null || cronExpression.isBlank()) {
        if (scheduler.checkExists(TRIGGER_KEY)) {
          scheduler.unscheduleJob(TRIGGER_KEY);
          logger.info("AI translate automation cron unscheduled");
        }
        return;
      }

      CronTrigger trigger =
          TriggerBuilder.newTrigger()
              .withIdentity(TRIGGER_KEY)
              .forJob(aiTranslateAutomationCronJobDetail.getKey())
              .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
              .build();

      if (!scheduler.checkExists(aiTranslateAutomationCronJobDetail.getKey())) {
        scheduler.addJob(aiTranslateAutomationCronJobDetail, true);
      }

      if (scheduler.checkExists(TRIGGER_KEY)) {
        scheduler.rescheduleJob(TRIGGER_KEY, trigger);
        logger.info("AI translate automation cron rescheduled: cron={}", cronExpression);
      } else {
        scheduler.scheduleJob(trigger);
        logger.info("AI translate automation cron scheduled: cron={}", cronExpression);
      }
    } catch (SchedulerException e) {
      throw new RuntimeException("Failed to synchronize AI translate automation cron", e);
    }
  }
}
