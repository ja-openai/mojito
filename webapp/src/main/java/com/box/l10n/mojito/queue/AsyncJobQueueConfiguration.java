package com.box.l10n.mojito.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Spring wiring for the async job store backend selected by configuration. */
@Configuration
@ConditionalOnProperty(name = "l10n.org.async-job-queue.enabled", havingValue = "true")
public class AsyncJobQueueConfiguration {

  @Bean
  @ConditionalOnProperty(
      name = "l10n.org.async-job-queue.store",
      havingValue = "in-memory",
      matchIfMissing = true)
  AsyncJobStore inMemoryAsyncJobStore() {
    return new InMemoryAsyncJobStore();
  }

  @Bean
  @ConditionalOnProperty(name = "l10n.org.async-job-queue.store", havingValue = "jdbc")
  AsyncJobStore jdbcAsyncJobStore(
      NamedParameterJdbcTemplate namedParameterJdbcTemplate,
      AsyncJobQueueProperties asyncJobQueueProperties,
      PlatformTransactionManager transactionManager) {
    return new JdbcAsyncJobStore(
        namedParameterJdbcTemplate,
        AsyncJobQueueJdbcDialect.fromConfig(asyncJobQueueProperties.getJdbcDialect()),
        transactionManager);
  }
}
