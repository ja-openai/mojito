package com.box.l10n.mojito.rest.admin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

@Configuration
public class LinguistTimeSpentHybridConfig {

  private final LinguistTimeSpentHybridProperties properties;

  public LinguistTimeSpentHybridConfig(LinguistTimeSpentHybridProperties properties) {
    this.properties = properties;
  }

  @Bean(name = "linguistTimeSpentHybridExecutor")
  public AsyncTaskExecutor linguistTimeSpentHybridExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(properties.pool().corePoolSize());
    executor.setMaxPoolSize(properties.pool().maxPoolSize());
    executor.setQueueCapacity(properties.pool().queueCapacity());
    executor.setThreadNamePrefix(properties.pool().threadNamePrefix() + "-");
    executor.initialize();
    return new DelegatingSecurityContextAsyncTaskExecutor(executor);
  }
}
