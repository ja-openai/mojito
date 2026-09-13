package com.box.l10n.mojito.queue;

/**
 * A handler's explicit request to fail a job without spending the remaining retry budget.
 *
 * <p>Only a direct throw from {@link AsyncJobHandler#process} has this meaning; the runtime does
 * not search wrapped causes or suppressed exceptions. Use a safe diagnostic message: it is logged
 * and persisted in the queue's last error. Do not use this for transient storage failures.
 *
 * <p>The FAILED transition still requires a valid lease. A rejected transition or lost commit
 * acknowledgement does not invoke the failure callback, and lease recovery can execute the job
 * again. This signal neither rolls back business effects nor provides exactly-once execution.
 */
public final class AsyncJobPermanentFailureException extends Exception {

  public AsyncJobPermanentFailureException(String message) {
    super(message);
  }
}
