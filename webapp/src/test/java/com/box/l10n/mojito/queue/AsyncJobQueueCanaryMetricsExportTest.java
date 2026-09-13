package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

public class AsyncJobQueueCanaryMetricsExportTest {

  private static final String ADMISSION =
      "assetWS.getLocalizedAssetForContentAsync.schedule.latency";
  private static final Map<String, Duration> TIMERS =
      Map.of(
          ADMISSION,
          Duration.ofSeconds(60),
          "asyncJobQueue.claim.latency",
          Duration.ofSeconds(10),
          "asyncJobQueue.queueWait.latency",
          Duration.ofMinutes(5),
          "asyncJobQueue.processing.latency",
          Duration.ofMinutes(5),
          "quartz.jobs.execution",
          Duration.ofMinutes(5),
          "quartz.jobs.waiting",
          Duration.ofMinutes(5));

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  MetricsAutoConfiguration.class, PrometheusMetricsExportAutoConfiguration.class))
          .withPropertyValues(
              "management.metrics.use-global-registry=false",
              "management.defaults.metrics.export.enabled=false",
              "management.prometheus.metrics.export.enabled=true");

  @Test
  public void histogramProfileIsOptIn() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
          TIMERS.keySet().forEach(name -> registry.timer(name).record(Duration.ofMillis(250)));
          String scrape = registry.scrape();
          assertThat(scrape).doesNotContain("_bucket{");
          TIMERS
              .keySet()
              .forEach(name -> assertThat(registry.get(name).timer().count()).isEqualTo(1));
        });
  }

  @Test
  public void canaryProfileExportsBoundedBucketsForBothRoutesAndQueueQuartzTimers()
      throws Exception {
    Properties properties = profile();
    assertThat(properties).hasSize(TIMERS.size() * 3);
    for (String name : TIMERS.keySet()) {
      assertThat(
              properties.getProperty(
                  "management.metrics.distribution.percentiles-histogram." + name))
          .isEqualTo("true");
      assertThat(
              properties.getProperty(
                  "management.metrics.distribution.minimum-expected-value." + name))
          .isEqualTo("1ms");
      assertThat(
              properties.getProperty(
                  "management.metrics.distribution.maximum-expected-value." + name))
          .isNotBlank();
    }
    runner
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues(
            "spring.profiles.active=queue-canary-metrics",
            "spring.config.location=classpath:/config/")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
              for (String route : List.of("quartz", "assetlocalize")) {
                for (String result : List.of("succeeded", "failed")) {
                  registry
                      .timer(ADMISSION, Tags.of("route", route, "result", result))
                      .record(Duration.ofMillis(250));
                }
              }
              TIMERS.keySet().stream()
                  .filter(name -> !name.equals(ADMISSION))
                  .forEach(name -> registry.timer(name).record(Duration.ofMillis(250)));
              TIMERS.forEach(
                  (name, maximum) -> {
                    var histogram = registry.get(name).timer().takeSnapshot().histogramCounts();
                    assertThat(histogram.length).isBetween(2, 150);
                    assertThat(histogram)
                        .allSatisfy(
                            bucket ->
                                assertThat(bucket.bucket())
                                    .isBetween(
                                        (double) Duration.ofMillis(1).toNanos(),
                                        (double) maximum.toNanos()));
                  });
              String scrape = registry.scrape();
              TIMERS
                  .keySet()
                  .forEach(
                      name ->
                          assertThat(scrape)
                              .contains(
                                  registry.config().namingConvention().name(name, Meter.Type.TIMER)
                                      + "_bucket{"));
              assertThat(scrape)
                  .contains("route=\"quartz\"", "route=\"assetlocalize\"", "le=\"+Inf\"")
                  .doesNotContain(
                      "quantile=", "pollableTaskId=", "jobId=", "assetId=", "repositoryId=");
            });
  }

  @Test
  public void freshnessGaugeIsExportedAndRetainsACompleteSample() {
    runner.run(
        context -> {
          PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
          InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
          store.enqueue("assetlocalize", "{}", Instant.now().minusSeconds(1));
          AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
          properties.getQueues().put("assetlocalize", new AsyncJobQueueProperties.QueueSettings());
          AsyncJobQueueStatusMetricsReporter reporter =
              new AsyncJobQueueStatusMetricsReporter(store, properties, List.of(), registry);
          reporter.reportStatusCounts();

          assertThat(
                  registry
                      .get("asyncJobQueue.statusMetrics.lastSuccessEpochSeconds")
                      .tag("queueName", "assetlocalize")
                      .gauge()
                      .value())
              .isPositive();
          assertThat(registry.scrape())
              .contains(
                  "asyncJobQueue_statusMetrics_lastSuccessEpochSeconds{queueName=\"assetlocalize\"}",
                  "asyncJobQueue_ready_count{queueName=\"assetlocalize\"} 1.0");
        });
  }

  private Properties profile() throws Exception {
    Properties properties = new Properties();
    try (InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("config/application-queue-canary-metrics.properties")) {
      assertThat(input).isNotNull();
      properties.load(input);
    }
    return properties;
  }
}
