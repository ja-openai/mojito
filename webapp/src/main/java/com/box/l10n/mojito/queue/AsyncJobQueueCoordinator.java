package com.box.l10n.mojito.queue;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Starts one polling runtime per configured handler/queue pair.
 *
 * <p>Each runtime owns its own executor and adaptive poll loop while sharing the same queue store.
 */
@Service
@ConditionalOnProperty(name = "l10n.org.async-job-queue.enabled", havingValue = "true")
public class AsyncJobQueueCoordinator implements SmartLifecycle {

  static Logger logger = LoggerFactory.getLogger(AsyncJobQueueCoordinator.class);

  private final AsyncJobStore asyncJobStore;
  private final AsyncJobQueueProperties asyncJobQueueProperties;
  private final List<AsyncJobHandler> asyncJobHandlers;
  private final TaskScheduler taskScheduler;
  private final MeterRegistry meterRegistry;

  private final Object lifecycleLock = new Object();
  private final Map<String, AsyncJobQueueRuntime> runtimesByQueueName = new LinkedHashMap<>();

  private volatile boolean running;

  public AsyncJobQueueCoordinator(
      AsyncJobStore asyncJobStore,
      AsyncJobQueueProperties asyncJobQueueProperties,
      List<AsyncJobHandler> asyncJobHandlers,
      TaskScheduler taskScheduler,
      MeterRegistry meterRegistry) {
    this.asyncJobStore = Objects.requireNonNull(asyncJobStore);
    this.asyncJobQueueProperties = Objects.requireNonNull(asyncJobQueueProperties);
    this.asyncJobHandlers = Objects.requireNonNull(asyncJobHandlers);
    this.taskScheduler = Objects.requireNonNull(taskScheduler);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (running) {
        return;
      }

      runtimesByQueueName.clear();

      Map<String, AsyncJobHandler> handlersByQueueName = new LinkedHashMap<>();
      for (AsyncJobHandler asyncJobHandler : asyncJobHandlers) {
        AsyncJobHandler previous =
            handlersByQueueName.put(asyncJobHandler.queueName(), asyncJobHandler);
        if (previous != null) {
          throw new IllegalStateException(
              "Multiple AsyncJobHandler beans registered for queue: "
                  + asyncJobHandler.queueName());
        }
      }

      for (Map.Entry<String, AsyncJobHandler> entry : handlersByQueueName.entrySet()) {
        String queueName = entry.getKey();
        AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings(queueName);
        try {
          AsyncJobQueueRuntime runtime =
              new AsyncJobQueueRuntime(
                  queueName,
                  asyncJobStore,
                  queueSettings,
                  entry.getValue(),
                  taskScheduler,
                  queueExecutor(queueName, queueSettings),
                  meterRegistry,
                  workerId(queueName));
          runtimesByQueueName.put(queueName, runtime);
          runtime.start();
        } catch (RuntimeException e) {
          logger.error("Failed to start async job queue runtime for {}", queueName, e);
          stopStartedRuntimes();
          throw e;
        }
      }

      for (String configuredQueueName : asyncJobQueueProperties.getQueues().keySet()) {
        if (!handlersByQueueName.containsKey(configuredQueueName)) {
          logger.warn(
              "Async job queue {} is configured but has no handler; polling will not start",
              configuredQueueName);
        }
      }

      running = true;
    }
  }

  @Override
  public void stop() {
    synchronized (lifecycleLock) {
      stopStartedRuntimes();
      running = false;
    }
  }

  @Override
  public void stop(Runnable callback) {
    stop();
    callback.run();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  public void triggerPollNow(String queueName) {
    synchronized (lifecycleLock) {
      AsyncJobQueueRuntime runtime = runtimesByQueueName.get(queueName);
      if (runtime != null) {
        runtime.triggerPollNow();
      }
    }
  }

  private AsyncJobQueueProperties.QueueSettings queueSettings(String queueName) {
    AsyncJobQueueProperties.QueueSettings queueSettings =
        asyncJobQueueProperties.getQueues().get(queueName);
    return queueSettings != null ? queueSettings : new AsyncJobQueueProperties.QueueSettings();
  }

  private ThreadPoolTaskExecutor queueExecutor(
      String queueName, AsyncJobQueueProperties.QueueSettings queueSettings) {
    ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
    threadPoolTaskExecutor.setCorePoolSize(queueSettings.getMaxConcurrency());
    threadPoolTaskExecutor.setMaxPoolSize(queueSettings.getMaxConcurrency());
    threadPoolTaskExecutor.setQueueCapacity(0);
    threadPoolTaskExecutor.setThreadNamePrefix("async-job-" + queueName + "-");
    threadPoolTaskExecutor.initialize();
    return threadPoolTaskExecutor;
  }

  private String workerId(String queueName) {
    return queueName + "-" + UUID.randomUUID();
  }

  private void stopStartedRuntimes() {
    runtimesByQueueName.values().forEach(AsyncJobQueueRuntime::stop);
    runtimesByQueueName.clear();
  }
}
