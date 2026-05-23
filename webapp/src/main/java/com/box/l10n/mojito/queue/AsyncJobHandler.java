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
   * queue's attempt budget is exhausted.
   */
  AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord) throws Exception;

  /**
   * Called after the runtime successfully persists the handler-requested done transition.
   *
   * <p>Use this for side effects that should only become visible once the queue row is terminal.
   * Exceptions are logged and metered by the runtime because the queue row is already done.
   */
  default void onJobDone(AsyncJobRecord asyncJobRecord, AsyncJobHandlerResult asyncJobHandlerResult)
      throws Exception {}

  /** Called after the runtime successfully persists any terminal failure for a claimed job. */
  default void onJobFailedPermanently(
      AsyncJobRecord asyncJobRecord, Throwable failure, String lastError) throws Exception {}
}
