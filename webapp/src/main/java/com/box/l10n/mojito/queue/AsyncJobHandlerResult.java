package com.box.l10n.mojito.queue;

import java.time.Instant;

/** Result of processing a claimed async job. */
public record AsyncJobHandlerResult(Action action, Instant availableAt, String jobData) {

  public enum Action {
    DONE,
    REQUEUE
  }

  public static AsyncJobHandlerResult done() {
    return new AsyncJobHandlerResult(Action.DONE, null, null);
  }

  public static AsyncJobHandlerResult done(String jobData) {
    return new AsyncJobHandlerResult(Action.DONE, null, jobData);
  }

  public static AsyncJobHandlerResult requeue(Instant availableAt) {
    return new AsyncJobHandlerResult(Action.REQUEUE, availableAt, null);
  }

  public static AsyncJobHandlerResult requeue(Instant availableAt, String jobData) {
    return new AsyncJobHandlerResult(Action.REQUEUE, availableAt, jobData);
  }
}
