package com.box.l10n.mojito.quartz;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.mockito.InOrder;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

public class QuartzConfigStartupTest {

  @Test
  public void waitsForApplicationReadyBeforeStartingSchedulers() throws Exception {
    Scheduler scheduler = scheduler("default");
    try (AnnotationConfigApplicationContext context = context(true, scheduler)) {
      // refresh() instantiates QuartzConfig and runs its @PostConstruct callbacks.
      // Starting from one of those callbacks can race transaction-advice initialization.
      verify(scheduler, never()).startDelayed(2);

      context.publishEvent(ready(context));

      verify(scheduler).startDelayed(2);
    }
  }

  @Test
  public void ignoresOtherContextsAndRepeatedReadyEvents() throws Exception {
    Scheduler scheduler = scheduler("default");
    try (AnnotationConfigApplicationContext context = context(true, scheduler);
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
      child.setParent(context);
      child.refresh();
      child.publishEvent(ready(child));
      verify(scheduler, never()).startDelayed(2);

      context.publishEvent(ready(context));
      scheduler.standby();
      context.publishEvent(ready(context));

      // A duplicate ready event must not undo a later manual standby.
      verify(scheduler).startDelayed(2);
      verify(scheduler).standby();
    }
  }

  @Test
  public void disabledStartupStillCleansUpAndAllowsManualStart() throws Exception {
    Scheduler scheduler = scheduler("default");
    try (AnnotationConfigApplicationContext context = context(false, scheduler)) {
      context.publishEvent(ready(context));
      verify(scheduler, never()).startDelayed(2);
      verify(scheduler, never()).start();
      verify(scheduler).unscheduleJobs(List.of());
      verify(scheduler).deleteJobs(List.of());

      scheduler.start();
      verify(scheduler).start();
    }
  }

  @Test
  public void removesOutdatedJobsBeforeStartingWithExistingStagger() throws Exception {
    Scheduler first = scheduler("default");
    Scheduler second = scheduler("secondary");
    try (AnnotationConfigApplicationContext context = context(true, first, second)) {
      context.publishEvent(ready(context));

      InOrder ordered = inOrder(first, second);
      ordered.verify(first).unscheduleJobs(List.of());
      ordered.verify(first).deleteJobs(List.of());
      ordered.verify(second).deleteJobs(List.of());
      ordered.verify(first).startDelayed(2);
      ordered.verify(second).startDelayed(3);
    }
  }

  @Test
  public void closingBeforeReadyNeverStartsSchedulers() throws Exception {
    Scheduler scheduler = scheduler("default");
    context(true, scheduler).close();
    verify(scheduler, never()).startDelayed(2);
  }

  private Scheduler scheduler(String name) throws SchedulerException {
    Scheduler scheduler = mock(Scheduler.class);
    when(scheduler.getSchedulerName()).thenReturn(name);
    when(scheduler.getJobKeys(GroupMatcher.jobGroupEquals(Scheduler.DEFAULT_GROUP)))
        .thenAnswer(invocation -> new HashSet<>());
    when(scheduler.getTriggerKeys(GroupMatcher.triggerGroupEquals(Scheduler.DEFAULT_GROUP)))
        .thenAnswer(invocation -> new HashSet<>());
    return scheduler;
  }

  private AnnotationConfigApplicationContext context(boolean enabled, Scheduler... schedulers) {
    QuartzSchedulerManager manager = mock(QuartzSchedulerManager.class);
    when(manager.getSchedulers()).thenReturn(List.of(schedulers));
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "scheduler-test", Map.of("l10n.org.quartz.scheduler.enabled", enabled)));
    context.registerBean(QuartzSchedulerManager.class, () -> manager);
    context.register(QuartzConfig.class);
    context.refresh();
    return context;
  }

  private ApplicationReadyEvent ready(AnnotationConfigApplicationContext context) {
    return new ApplicationReadyEvent(
        new SpringApplication(), new String[0], context, Duration.ZERO);
  }
}
