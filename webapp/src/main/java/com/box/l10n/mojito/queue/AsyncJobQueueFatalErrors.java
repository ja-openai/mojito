package com.box.l10n.mojito.queue;

/** Shared classifier for JVM-fatal errors that must not be swallowed by queue safeguards. */
final class AsyncJobQueueFatalErrors {

  private AsyncJobQueueFatalErrors() {}

  static boolean isJvmFatal(Throwable throwable) {
    return throwable instanceof VirtualMachineError
        || (throwable != null && "java.lang.ThreadDeath".equals(throwable.getClass().getName()));
  }
}
