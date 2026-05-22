package com.box.l10n.mojito.queue;

import java.util.Objects;

final class AsyncJobQueueValidation {

  static final int QUEUE_NAME_MAX_LENGTH = 64;
  static final int WORKER_ID_MAX_LENGTH = 128;

  private AsyncJobQueueValidation() {}

  static String validateQueueName(String queueName) {
    Objects.requireNonNull(queueName);
    if (queueName.isBlank()) {
      throw new IllegalArgumentException("queueName must not be blank");
    }
    if (queueName.length() > QUEUE_NAME_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "queueName must be at most " + QUEUE_NAME_MAX_LENGTH + " characters");
    }
    return queueName;
  }

  static String validateWorkerId(String workerId) {
    Objects.requireNonNull(workerId);
    if (workerId.isBlank()) {
      throw new IllegalArgumentException("workerId must not be blank");
    }
    if (workerId.length() > WORKER_ID_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "workerId must be at most " + WORKER_ID_MAX_LENGTH + " characters");
    }
    return workerId;
  }
}
