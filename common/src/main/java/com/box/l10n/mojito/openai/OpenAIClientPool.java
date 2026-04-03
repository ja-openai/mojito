package com.box.l10n.mojito.openai;

import com.google.common.base.Stopwatch;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.net.http.HttpClient;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenAIClientPool {

  static Logger logger = LoggerFactory.getLogger(OpenAIClientPool.class);

  int numberOfClients;
  OpenAIClientWithSemaphore[] openAIClientWithSemaphores;
  MeterRegistry meterRegistry;
  String poolName;
  AtomicInteger waitingSubmissions;

  /**
   * Pool to parallelize slower requests (1s+) over HTTP/2 connections.
   *
   * @param numberOfClients Number of OpenAIClient instances with independent HttpClients.
   * @param numberOfParallelRequestPerClient Maximum parallel requests per client, controlled by a
   *     semaphore to prevent overload.
   * @param sizeOfAsyncProcessors Shared async processors across all HttpClients to limit threads,
   *     as request time is the main bottleneck.
   * @param apiKey API key for authentication.
   */
  public OpenAIClientPool(
      int numberOfClients,
      int numberOfParallelRequestPerClient,
      int sizeOfAsyncProcessors,
      String apiKey) {
    this(
        numberOfClients,
        numberOfParallelRequestPerClient,
        sizeOfAsyncProcessors,
        apiKey,
        "default",
        null);
  }

  public OpenAIClientPool(
      int numberOfClients,
      int numberOfParallelRequestPerClient,
      int sizeOfAsyncProcessors,
      String apiKey,
      String poolName,
      MeterRegistry meterRegistry) {
    ExecutorService asyncExecutor = Executors.newWorkStealingPool(sizeOfAsyncProcessors);
    this.numberOfClients = numberOfClients;
    this.poolName = sanitizeTagValue(poolName);
    this.meterRegistry = meterRegistry;
    this.waitingSubmissions = new AtomicInteger();
    this.openAIClientWithSemaphores = new OpenAIClientWithSemaphore[numberOfClients];
    for (int i = 0; i < numberOfClients; i++) {
      this.openAIClientWithSemaphores[i] =
          new OpenAIClientWithSemaphore(
              OpenAIClient.builder()
                  .apiKey(apiKey)
                  .asyncExecutor(asyncExecutor)
                  .httpClient(HttpClient.newBuilder().executor(asyncExecutor).build())
                  .build(),
              new Semaphore(numberOfParallelRequestPerClient));
    }
    if (this.meterRegistry != null) {
      this.meterRegistry.gauge(
          metricName("availablePermits"), metricTags(), this, OpenAIClientPool::availablePermits);
      this.meterRegistry.gauge(metricName("waitingSubmissions"), metricTags(), waitingSubmissions);
    }
  }

  public <T> CompletableFuture<T> submit(Function<OpenAIClient, CompletableFuture<T>> f) {

    while (true) {
      for (OpenAIClientWithSemaphore openAIClientWithSemaphore : openAIClientWithSemaphores) {
        if (openAIClientWithSemaphore.semaphore().tryAcquire()) {
          try {
            return f.apply(openAIClientWithSemaphore.openAIClient())
                .whenComplete((o, e) -> openAIClientWithSemaphore.semaphore().release());
          } catch (Throwable t) {
            logger.error("Exception in apply(), semaphore released", t);
            openAIClientWithSemaphore.semaphore().release();
          }
        }
      }

      try {
        logger.debug("can't directly acquire any semaphore, do blocking");
        int randomSemaphoreIndex =
            ThreadLocalRandom.current().nextInt(openAIClientWithSemaphores.length);
        OpenAIClientWithSemaphore randomClientWithSemaphore =
            this.openAIClientWithSemaphores[randomSemaphoreIndex];
        incrementCounter(metricName("blockingSubmissions"));
        Stopwatch acquireStopwatch = Stopwatch.createStarted();
        waitingSubmissions.incrementAndGet();
        try {
          randomClientWithSemaphore.semaphore().acquire();
        } finally {
          waitingSubmissions.decrementAndGet();
          recordTimer(metricName("acquireWaitDuration"), acquireStopwatch);
        }
        try {
          return f.apply(randomClientWithSemaphore.openAIClient())
              .whenComplete((o, e) -> randomClientWithSemaphore.semaphore().release());
        } catch (Throwable t) {
          logger.error("Exception in apply(), semaphore released", t);
          randomClientWithSemaphore.semaphore().release();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Can't submit task to the OpenAIClientPool", e);
      }
    }
  }

  record OpenAIClientWithSemaphore(OpenAIClient openAIClient, Semaphore semaphore) {}

  private int availablePermits() {
    int availablePermits = 0;
    for (OpenAIClientWithSemaphore openAIClientWithSemaphore : openAIClientWithSemaphores) {
      availablePermits += openAIClientWithSemaphore.semaphore().availablePermits();
    }
    return availablePermits;
  }

  private void incrementCounter(String metricName) {
    if (meterRegistry == null) {
      return;
    }
    meterRegistry.counter(metricName, metricTags()).increment();
  }

  private void recordTimer(String metricName, Stopwatch stopwatch) {
    if (meterRegistry == null) {
      return;
    }
    meterRegistry.timer(metricName, metricTags()).record(stopwatch.elapsed());
  }

  private Tags metricTags() {
    return Tags.of("pool", poolName);
  }

  private String metricName(String name) {
    return "%s.%s".formatted(getClass().getSimpleName(), name);
  }

  private String sanitizeTagValue(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    return Objects.requireNonNull(value);
  }
}
