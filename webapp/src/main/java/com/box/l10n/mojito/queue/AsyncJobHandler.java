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
   * <p>Throwing signals unexpected failure; the runtime currently requeues with a small delay.
   */
  AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord) throws Exception;
}
