package com.box.l10n.mojito.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class OpenAIClientPoolMetricsTest {

  @Test
  public void submitRecordsPoolWaitMetricsWhenAllPermitsAreInUse() throws Exception {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    OpenAIClientPool openAIClientPool =
        new OpenAIClientPool(1, 1, 1, "test-api-key", "ai-review", meterRegistry);

    CompletableFuture<String> firstRequest = new CompletableFuture<>();
    openAIClientPool.submit(openAIClient -> firstRequest);

    CountDownLatch startedSecondSubmit = new CountDownLatch(1);
    AtomicReference<CompletableFuture<String>> secondResponseReference = new AtomicReference<>();
    Thread secondSubmitThread =
        new Thread(
            () -> {
              startedSecondSubmit.countDown();
              secondResponseReference.set(
                  openAIClientPool.submit(
                      openAIClient -> CompletableFuture.completedFuture("second")));
            });
    secondSubmitThread.start();

    assertTrue(startedSecondSubmit.await(1, TimeUnit.SECONDS));
    waitForWaitingSubmissions(meterRegistry, 1.0);

    firstRequest.complete("first");
    secondSubmitThread.join(1000);

    assertEquals("second", secondResponseReference.get().join());
    assertEquals(
        1.0,
        meterRegistry.counter("OpenAIClientPool.blockingSubmissions", "pool", "ai-review").count(),
        0.0);
    assertEquals(
        1L,
        meterRegistry.timer("OpenAIClientPool.acquireWaitDuration", "pool", "ai-review").count());
    assertEquals(
        1.0,
        meterRegistry
            .get("OpenAIClientPool.availablePermits")
            .tag("pool", "ai-review")
            .gauge()
            .value(),
        0.0);
    assertEquals(0.0, getWaitingSubmissionsGaugeValue(meterRegistry), 0.0);
  }

  private void waitForWaitingSubmissions(SimpleMeterRegistry meterRegistry, double expectedValue)
      throws InterruptedException {
    long deadlineMillis = System.currentTimeMillis() + 1000;
    while (System.currentTimeMillis() < deadlineMillis) {
      if (getWaitingSubmissionsGaugeValue(meterRegistry) == expectedValue) {
        return;
      }
      Thread.sleep(10);
    }
    assertEquals(expectedValue, getWaitingSubmissionsGaugeValue(meterRegistry), 0.0);
  }

  private double getWaitingSubmissionsGaugeValue(SimpleMeterRegistry meterRegistry) {
    return meterRegistry
        .get("OpenAIClientPool.waitingSubmissions")
        .tag("pool", "ai-review")
        .gauge()
        .value();
  }
}
