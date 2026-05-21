package com.box.l10n.mojito.service.pollableTask;

import static com.box.l10n.mojito.service.pollableTask.PollableAspectParameters.DEFAULT_TIMEOUT;

import java.util.Objects;

public record PollableTaskInvocation<T>(
    Long parentId,
    String name,
    String message,
    int expectedSubTaskNumber,
    Long timeout,
    PollableTaskOperation<?> operation) {

  public PollableTaskInvocation {
    Objects.requireNonNull(name);
    Objects.requireNonNull(operation);
    timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
  }

  public static <T> PollableTaskInvocation<T> of(
      String name, String message, PollableTaskOperation<T> operation) {
    return new PollableTaskInvocation<>(null, name, message, 0, DEFAULT_TIMEOUT, operation);
  }

  public static <T> PollableTaskInvocation<T> ofFuture(
      String name, String message, PollableTaskOperation<PollableFuture<T>> operation) {
    return new PollableTaskInvocation<>(null, name, message, 0, DEFAULT_TIMEOUT, operation);
  }
}
