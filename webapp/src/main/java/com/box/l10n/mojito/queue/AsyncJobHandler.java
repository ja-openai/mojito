package com.box.l10n.mojito.queue;

/**
 * Executes claimed async jobs for one logical queue.
 *
 * <p>Queue-specific wiring can be added later; the polling/runtime layer only depends on this
 * generic contract.
 */
public interface AsyncJobHandler {

  /** Logical queue name handled by this implementation. */
  String queueName();

  /**
   * Process a claimed job and return the state transition the runtime should apply afterwards.
   *
   * <p>Throwing signals unexpected failure; the runtime retries with bounded backoff until the
   * queue's attempt budget is exhausted. Directly throw {@link AsyncJobPermanentFailureException}
   * to request FAILED without consuming the remaining budget for a known unrecoverable condition.
   * Wrapped or suppressed markers do not bypass retries. Failure persistence remains lease-fenced.
   * JVM-fatal errors take precedence over that retry/permanent-failure policy, including fatal
   * causes or suppressed cleanup failures: the original fatal error escapes the worker without a
   * failure transition. Do not attach a handled fatal error as diagnostic context to an ordinary
   * failure. The claim remains subject to lease recovery and attempt-budget accounting; this does
   * not stop the runtime or guarantee JVM termination. A process crash or lease loss can also cause
   * another invocation after business effects have already committed. Queue lease tokens fence
   * queue-row transitions, not arbitrary business writes; handlers must arrange their own
   * idempotency or business-side fencing. If a heartbeat has already reported definitive lease loss
   * before handler entry, the runtime skips this invocation without a queue transition or callback.
   * This local check adds no database query, cannot rule out later lease loss, and does not
   * interrupt a handler already running. A heartbeat exception is an unknown outcome, not proof of
   * lease loss.
   */
  AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord) throws Exception;

  /**
   * Called after the runtime successfully persists the handler-requested done transition.
   *
   * <p>This is a best-effort local notification, not durable publication. A crash or lost commit
   * acknowledgement can leave the row DONE without invoking this callback; polling does not replay
   * it. Required business effects need a durable reconciliation protocol. The supplied job record
   * is a terminal {@link AsyncJobStatus#DONE} snapshot. Exceptions are logged and metered by the
   * runtime because the queue row is already done. JVM-fatal errors, including causes or suppressed
   * errors, instead propagate without changing that terminal result. No callback retry is added.
   */
  default void onJobDone(AsyncJobRecord asyncJobRecord, AsyncJobHandlerResult asyncJobHandlerResult)
      throws Exception {}

  /**
   * Called after the runtime successfully persists any terminal failure for a claimed job.
   *
   * <p>The supplied job record is a terminal {@link AsyncJobStatus#FAILED} snapshot. Like {@link
   * #onJobDone}, this callback is best-effort and can be missed after a crash or lost commit
   * acknowledgement; it is not automatically replayed.
   */
  default void onJobFailedPermanently(
      AsyncJobRecord asyncJobRecord, Throwable failure, String lastError) throws Exception {}
}
