package com.box.l10n.mojito.rest.glossary;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

@Configuration
public class TermIndexEntriesHybridConfig {

  private final TermIndexEntriesHybridProperties properties;

  public TermIndexEntriesHybridConfig(TermIndexEntriesHybridProperties properties) {
    this.properties = properties;
  }

  @Bean(name = "termIndexEntriesHybridExecutor")
  public AsyncTaskExecutor termIndexEntriesHybridExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(properties.pool().corePoolSize());
    executor.setMaxPoolSize(properties.pool().maxPoolSize());
    executor.setQueueCapacity(properties.pool().queueCapacity());
    executor.setThreadNamePrefix(properties.pool().threadNamePrefix() + "-");
    executor.initialize();
    return new DelegatingSecurityContextAsyncTaskExecutor(executor);
  }
}
