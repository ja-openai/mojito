package com.box.l10n.mojito.queue;

import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring for optional async queue cross-process wakeup providers. */
@Configuration
@ConditionalOnProperty(name = "l10n.org.async-job-queue.enabled", havingValue = "true")
class AsyncJobQueueWakeupConfiguration {

  @Bean
  @ConditionalOnProperty(
      name = "l10n.org.async-job-queue.wakeup.mode",
      havingValue = AsyncJobQueueValidation.WAKEUP_MODE_POSTGRES_LISTEN_NOTIFY)
  AsyncJobQueueWakeupNotifier jdbcPostgresAsyncJobQueueWakeupNotifier(
      DataSource dataSource,
      AsyncJobQueueProperties asyncJobQueueProperties,
      MeterRegistry meterRegistry) {
    return new JdbcPostgresAsyncJobQueueWakeupNotifier(
        dataSource, asyncJobQueueProperties, meterRegistry);
  }

  @Bean
  @ConditionalOnProperty(
      name = "l10n.org.async-job-queue.wakeup.mode",
      havingValue = AsyncJobQueueValidation.WAKEUP_MODE_POSTGRES_LISTEN_NOTIFY)
  JdbcPostgresAsyncJobQueueWakeupListener jdbcPostgresAsyncJobQueueWakeupListener(
      DataSource dataSource,
      AsyncJobQueueProperties asyncJobQueueProperties,
      AsyncJobQueueCoordinator asyncJobQueueCoordinator,
      MeterRegistry meterRegistry) {
    return new JdbcPostgresAsyncJobQueueWakeupListener(
        dataSource, asyncJobQueueProperties, asyncJobQueueCoordinator, meterRegistry);
  }
}
