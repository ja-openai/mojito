package com.box.l10n.mojito.queue;

/** Best-effort cross-process wakeup hint after a durable queue row becomes ready. */
interface AsyncJobQueueWakeupNotifier {

  AsyncJobQueueWakeupNotifier NO_OP = (queueName, asyncJobId) -> {};

  void notifyJobAvailable(String queueName, AsyncJobId asyncJobId);
}
