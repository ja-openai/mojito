package com.box.l10n.mojito.service.oaitranslate;

import static com.box.l10n.mojito.quartz.QuartzConfig.DYNAMIC_GROUP_NAME;
import static com.box.l10n.mojito.quartz.QuartzSchedulerManager.DEFAULT_SCHEDULER_NAME;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.quartz.QuartzSchedulerManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;

@RunWith(MockitoJUnitRunner.class)
public class AiTranslateAutomationCronSchedulerServiceTest {

  @Mock QuartzSchedulerManager quartzSchedulerManager;

  @Mock Scheduler scheduler;

  private AiTranslateAutomationCronSchedulerService aiTranslateAutomationCronSchedulerService;

  private JobDetail aiTranslateAutomationCronJobDetail;

  @Before
  public void setUp() {
    aiTranslateAutomationCronJobDetail =
        JobBuilder.newJob(AiTranslateAutomationCronJob.class)
            .withIdentity("aiTranslateCron")
            .build();

    aiTranslateAutomationCronSchedulerService =
        new AiTranslateAutomationCronSchedulerService(
            quartzSchedulerManager, aiTranslateAutomationCronJobDetail);
  }

  @Test
  public void syncConfigSchedulesConfiguredDynamicTrigger() throws Exception {
    AiTranslateAutomationConfigService.Config config =
        new AiTranslateAutomationConfigService.Config(
            true, java.util.List.of(1L), 100, "0 0 0 * * ?");
    when(quartzSchedulerManager.getScheduler(DEFAULT_SCHEDULER_NAME)).thenReturn(scheduler);
    when(scheduler.checkExists(aiTranslateAutomationCronJobDetail.getKey())).thenReturn(false);
    when(scheduler.checkExists(any(org.quartz.TriggerKey.class))).thenReturn(false);

    aiTranslateAutomationCronSchedulerService.syncConfig(config);

    verify(scheduler).addJob(aiTranslateAutomationCronJobDetail, true);

    ArgumentCaptor<org.quartz.Trigger> triggerCaptor =
        ArgumentCaptor.forClass(org.quartz.Trigger.class);
    verify(scheduler).scheduleJob(triggerCaptor.capture());
    CronTrigger trigger = (CronTrigger) triggerCaptor.getValue();
    assertEquals(DYNAMIC_GROUP_NAME, trigger.getKey().getGroup());
    assertEquals("0 0 0 * * ?", trigger.getCronExpression());
  }

  @Test
  public void syncCronExpressionReschedulesExistingDynamicTrigger() throws Exception {
    when(quartzSchedulerManager.getScheduler(DEFAULT_SCHEDULER_NAME)).thenReturn(scheduler);
    when(scheduler.checkExists(aiTranslateAutomationCronJobDetail.getKey())).thenReturn(true);
    when(scheduler.checkExists(any(org.quartz.TriggerKey.class))).thenReturn(true);

    aiTranslateAutomationCronSchedulerService.syncCronExpression("0 15 3 * * ?");

    ArgumentCaptor<CronTrigger> triggerCaptor = ArgumentCaptor.forClass(CronTrigger.class);
    verify(scheduler).rescheduleJob(any(org.quartz.TriggerKey.class), triggerCaptor.capture());
    CronTrigger trigger = triggerCaptor.getValue();
    assertEquals(DYNAMIC_GROUP_NAME, trigger.getKey().getGroup());
    assertEquals("0 15 3 * * ?", trigger.getCronExpression());
  }
}
