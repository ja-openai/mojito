package com.box.l10n.mojito.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Profile("!disablescheduling")
@Component
@ConditionalOnProperty(
    value = "l10n.management.metrics.quartz.sql-queue-monitoring.enabled",
    havingValue = "true")
public class QuartzPendingJobsReportingTask {

  private JdbcTemplate jdbcTemplate;
  private MeterRegistry meterRegistry;
  private Map<String, AtomicLong> queueSizes;
  private Map<String, AtomicLong> schedulerTriggerCounts;
  private Map<String, AtomicLong> schedulerOldestPendingAgeMs;

  public QuartzPendingJobsReportingTask(
      @Autowired DataSource dataSource, @Autowired MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    this.jdbcTemplate = new JdbcTemplate(dataSource);
    this.queueSizes = new ConcurrentHashMap<>();
    this.schedulerTriggerCounts = new ConcurrentHashMap<>();
    this.schedulerOldestPendingAgeMs = new ConcurrentHashMap<>();
  }

  @Scheduled(
      fixedRateString = "${l10n.management.metrics.quartz.sql-queue-monitoring.execution-rate}")
  public void reportPendingJobs() {
    Map<String, PendingJob> results = fetchResults();
    updateQueueSizes(results);
    results.forEach(this::registerJobQueueSize);

    Map<String, SchedulerTriggerCount> schedulerTriggerCounts = fetchSchedulerTriggerCounts();
    updateSchedulerTriggerCounts(schedulerTriggerCounts);
    schedulerTriggerCounts.forEach(this::registerSchedulerTriggerCount);

    Map<String, SchedulerOldestPending> schedulerOldestPendingAges = fetchSchedulerOldestPending();
    updateSchedulerOldestPendingAges(schedulerOldestPendingAges);
    schedulerOldestPendingAges.forEach(this::registerSchedulerOldestPendingAge);
  }

  private void registerJobQueueSize(String key, PendingJob pendingJob) {
    queueSizes.computeIfAbsent(key, k -> createGauge(pendingJob)).set(pendingJob.count);
  }

  private AtomicLong createGauge(PendingJob pendingJob) {
    return meterRegistry.gauge(
        "quartz.pending.jobs",
        Tags.of("jobClass", pendingJob.jobClass, "jobGroup", pendingJob.jobGroup),
        new AtomicLong(pendingJob.count));
  }

  private void updateQueueSizes(Map<String, PendingJob> pendingJobs) {
    queueSizes.forEach(
        (key, val) -> {
          Long size = pendingJobs.containsKey(key) ? pendingJobs.get(key).count : 0L;
          // If the list of yielded results doesn't contains the pendingjob, then we update its
          // value to be zero
          queueSizes.get(key).set(size);
        });
  }

  private void registerSchedulerTriggerCount(String key, SchedulerTriggerCount triggerCount) {
    schedulerTriggerCounts
        .computeIfAbsent(key, k -> createSchedulerTriggerCountGauge(triggerCount))
        .set(triggerCount.count);
  }

  private AtomicLong createSchedulerTriggerCountGauge(SchedulerTriggerCount triggerCount) {
    return meterRegistry.gauge(
        "quartz.scheduler.triggers",
        Tags.of(
            "schedulerName", triggerCount.schedulerName,
            "triggerState", triggerCount.triggerState),
        new AtomicLong(triggerCount.count));
  }

  private void updateSchedulerTriggerCounts(Map<String, SchedulerTriggerCount> triggerCounts) {
    schedulerTriggerCounts.forEach(
        (key, val) -> {
          Long size = triggerCounts.containsKey(key) ? triggerCounts.get(key).count : 0L;
          schedulerTriggerCounts.get(key).set(size);
        });
  }

  private void registerSchedulerOldestPendingAge(String key, SchedulerOldestPending pendingAge) {
    schedulerOldestPendingAgeMs
        .computeIfAbsent(key, k -> createSchedulerOldestPendingAgeGauge(pendingAge))
        .set(pendingAge.ageMs);
  }

  private AtomicLong createSchedulerOldestPendingAgeGauge(
      SchedulerOldestPending schedulerOldestPending) {
    return meterRegistry.gauge(
        "quartz.scheduler.oldestPendingAgeMs",
        Tags.of("schedulerName", schedulerOldestPending.schedulerName),
        new AtomicLong(schedulerOldestPending.ageMs));
  }

  private void updateSchedulerOldestPendingAges(Map<String, SchedulerOldestPending> pendingAges) {
    schedulerOldestPendingAgeMs.forEach(
        (key, val) -> {
          Long age = pendingAges.containsKey(key) ? pendingAges.get(key).ageMs : 0L;
          schedulerOldestPendingAgeMs.get(key).set(age);
        });
  }

  Map<String, PendingJob> fetchResults() {
    List<PendingJob> result =
        jdbcTemplate.query(
            "SELECT job_class_name, job_group, COUNT(*) FROM QRTZ_JOB_DETAILS GROUP BY job_class_name, job_group",
            (rs, num) ->
                new PendingJob(extractClassName(rs.getString(1)), rs.getString(2), rs.getLong(3)));

    return result.stream().collect(Collectors.toMap(PendingJob::getKey, Function.identity()));
  }

  Map<String, SchedulerTriggerCount> fetchSchedulerTriggerCounts() {
    List<SchedulerTriggerCount> result =
        jdbcTemplate.query(
            "SELECT sched_name, trigger_state, COUNT(*) "
                + "FROM QRTZ_TRIGGERS "
                + "GROUP BY sched_name, trigger_state",
            (rs, num) ->
                new SchedulerTriggerCount(rs.getString(1), rs.getString(2), rs.getLong(3)));

    return result.stream()
        .collect(Collectors.toMap(SchedulerTriggerCount::getKey, Function.identity()));
  }

  Map<String, SchedulerOldestPending> fetchSchedulerOldestPending() {
    long now = Instant.now().toEpochMilli();
    List<SchedulerOldestPending> result =
        jdbcTemplate.query(
            "SELECT sched_name, MIN(next_fire_time) "
                + "FROM QRTZ_TRIGGERS "
                + "WHERE next_fire_time IS NOT NULL "
                + "AND trigger_state IN ('WAITING', 'ACQUIRED', 'BLOCKED') "
                + "GROUP BY sched_name",
            (rs, num) -> {
              long nextFireTime = rs.getLong(2);
              long ageMs = nextFireTime <= 0 ? 0L : Math.max(now - nextFireTime, 0L);
              return new SchedulerOldestPending(rs.getString(1), ageMs);
            });

    return result.stream()
        .collect(Collectors.toMap(SchedulerOldestPending::getKey, Function.identity()));
  }

  static String extractClassName(String input) {
    String[] parts = input.split("\\.");
    return parts.length > 0 ? parts[parts.length - 1] : "";
  }

  /*
   * This class represents data associated with a group of jobs pending to be executed in our Quartz instance
   * */
  static class PendingJob {
    public String jobClass;
    public String jobGroup;
    public Long count;

    public PendingJob(String jobClass, String jobGroup, Long count) {
      this.jobClass = jobClass;
      this.jobGroup = jobGroup;
      this.count = count;
    }

    public String getKey() {
      return jobClass + "-" + jobGroup;
    }
  }

  static class SchedulerTriggerCount {
    public String schedulerName;
    public String triggerState;
    public Long count;

    public SchedulerTriggerCount(String schedulerName, String triggerState, Long count) {
      this.schedulerName = schedulerName;
      this.triggerState = triggerState;
      this.count = count;
    }

    public String getKey() {
      return schedulerName + "-" + triggerState;
    }
  }

  static class SchedulerOldestPending {
    public String schedulerName;
    public Long ageMs;

    public SchedulerOldestPending(String schedulerName, Long ageMs) {
      this.schedulerName = schedulerName;
      this.ageMs = ageMs;
    }

    public String getKey() {
      return schedulerName;
    }
  }
}
