package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import javax.sql.DataSource;
import org.junit.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public class AsyncJobQueuePostgresWakeupConfigurationTest {

  @Test
  public void postgresWakeupBeansAreNotCreatedForPollingMode() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      TestPropertyValues.of("l10n.org.async-job-queue.enabled=true").applyTo(context);
      context.register(
          AsyncJobQueueProperties.class,
          AsyncJobQueueWakeupConfiguration.class,
          WakeupTestConfig.class);

      context.refresh();

      assertThat(context.getBeansOfType(JdbcPostgresAsyncJobQueueWakeupNotifier.class)).isEmpty();
      assertThat(context.getBeansOfType(JdbcPostgresAsyncJobQueueWakeupListener.class)).isEmpty();
    }
  }

  @Test
  public void postgresWakeupBeansAreNotCreatedWhenQueueIsDisabled() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      TestPropertyValues.of(
              "l10n.org.async-job-queue.store=jdbc",
              "l10n.org.async-job-queue.jdbc-dialect=postgresql",
              "l10n.org.async-job-queue.wakeup.mode=postgres-listen-notify")
          .applyTo(context);
      context.register(
          AsyncJobQueueProperties.class,
          AsyncJobQueueWakeupConfiguration.class,
          WakeupTestConfig.class);

      context.refresh();

      assertThat(context.getBeansOfType(JdbcPostgresAsyncJobQueueWakeupNotifier.class)).isEmpty();
      assertThat(context.getBeansOfType(JdbcPostgresAsyncJobQueueWakeupListener.class)).isEmpty();
    }
  }

  @Test
  public void postgresWakeupBeansAreCreatedOnlyWhenConfigured() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      TestPropertyValues.of(
              "l10n.org.async-job-queue.enabled=true",
              "l10n.org.async-job-queue.store=jdbc",
              "l10n.org.async-job-queue.jdbc-dialect=postgresql",
              "l10n.org.async-job-queue.wakeup.mode=postgres-listen-notify")
          .applyTo(context);
      context.register(
          AsyncJobQueueProperties.class,
          AsyncJobQueueWakeupConfiguration.class,
          WakeupTestConfig.class);

      context.refresh();

      assertThat(context.getBean(JdbcPostgresAsyncJobQueueWakeupNotifier.class)).isNotNull();
      assertThat(context.getBean(JdbcPostgresAsyncJobQueueWakeupListener.class)).isNotNull();
    }
  }

  @Test
  public void postgresWakeupModeFailsFastForMysqlDialect() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      TestPropertyValues.of(
              "l10n.org.async-job-queue.enabled=true",
              "l10n.org.async-job-queue.store=jdbc",
              "l10n.org.async-job-queue.jdbc-dialect=mysql",
              "l10n.org.async-job-queue.wakeup.mode=postgres-listen-notify")
          .applyTo(context);
      context.register(
          AsyncJobQueueProperties.class,
          AsyncJobQueueWakeupConfiguration.class,
          WakeupTestConfig.class);

      assertThatThrownBy(context::refresh)
          .hasRootCauseInstanceOf(IllegalArgumentException.class)
          .hasRootCauseMessage(
              "wakeup.mode=postgres-listen-notify requires store=jdbc and jdbcDialect=postgresql");
    }
  }

  @Configuration
  @EnableConfigurationProperties(AsyncJobQueueProperties.class)
  static class WakeupTestConfig {

    @Bean
    DataSource dataSource() {
      return mock(DataSource.class);
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    AsyncJobQueueCoordinator asyncJobQueueCoordinator() {
      return mock(AsyncJobQueueCoordinator.class);
    }
  }
}
