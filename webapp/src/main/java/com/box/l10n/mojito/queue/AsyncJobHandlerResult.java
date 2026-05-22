package com.box.l10n.mojito.queue;

import java.time.Instant;
import java.util.Objects;

/** Result of processing a claimed async job. */
public record AsyncJobHandlerResult(Action action, Instant availableAt, String jobData) {

  public AsyncJobHandlerResult {
    Objects.requireNonNull(action);
    if (action == Action.DONE && availableAt != null) {
      throw new IllegalArgumentException("availableAt must be null for done async job results");
    }
  }

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
