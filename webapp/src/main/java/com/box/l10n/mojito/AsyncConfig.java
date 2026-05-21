package com.box.l10n.mojito;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

/**
 * @author jaurambault
 */
@Configuration
public class AsyncConfig {

  @Bean(name = "asyncExecutor")
  public AsyncTaskExecutor getAsyncExecutor() {
    ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
    threadPoolTaskExecutor.setBeanName("asyncExecutor");
    threadPoolTaskExecutor.setCorePoolSize(5);
    threadPoolTaskExecutor.setMaxPoolSize(10);
    threadPoolTaskExecutor.initialize();
    return threadPoolTaskExecutor;
  }

  @Bean(name = "pollableTaskExecutor")
  public AsyncTaskExecutor getPollableTaskExecutor() {
    ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
    threadPoolTaskExecutor.setBeanName("pollableTask");
    threadPoolTaskExecutor.setCorePoolSize(5);
    threadPoolTaskExecutor.setMaxPoolSize(30);
    threadPoolTaskExecutor.initialize();

    return new DelegatingSecurityContextAsyncTaskExecutor(threadPoolTaskExecutor);
  }
}
