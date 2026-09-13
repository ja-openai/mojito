package com.box.l10n.mojito.queue;

import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optional Spring wiring for cross-process wakeup hints alongside the queue execution
 * configuration.
 *
 * <p>Explicit consumers may import this configuration without scanning Mojito. PostgreSQL mode
 * requires a DataSource that provides independently leased connections to the queue database.
 * Outermost TransactionAwareDataSourceProxy layers are bypassed for wakeups; remaining wrappers
 * must not enlist or return a caller-owned/shared connection. Polling remains the durable fallback.
 * Producer-only nodes publish hints without starting a listener.
 */
@Configuration
@ConditionalOnProperty(name = "l10n.org.async-job-queue.enabled", havingValue = "true")
public class AsyncJobQueueWakeupConfiguration {

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
