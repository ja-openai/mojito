package com.box.l10n.mojito.queue;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Shared classifier for JVM-fatal errors that must not be swallowed by queue safeguards. */
final class AsyncJobQueueFatalErrors {

  private AsyncJobQueueFatalErrors() {}

  static boolean isJvmFatal(Throwable throwable) {
    return throwable instanceof VirtualMachineError || throwable instanceof ThreadDeath;
  }

  /** Includes causes and suppressed resource-cleanup errors without rewriting the error graph. */
  static Error findJvmFatal(Throwable failure) {
    if (failure == null) {
      return null;
    }
    if (isJvmFatal(failure)) {
      return (Error) failure;
    }
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    ArrayDeque<Throwable> pending = new ArrayDeque<>();
    pending.add(failure);
    while (!pending.isEmpty()) {
      Throwable current = pending.removeFirst();
      if (!visited.add(current)) {
        continue;
      }
      if (isJvmFatal(current)) {
        return (Error) current;
      }
      Throwable cause = current.getCause();
      if (cause != null) {
        pending.addLast(cause);
      }
      for (Throwable suppressed : current.getSuppressed()) {
        pending.addLast(suppressed);
      }
    }
    return null;
  }
}
