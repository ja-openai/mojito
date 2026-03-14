package com.box.l10n.mojito.queue;

import java.util.Arrays;

public enum AsyncJobStatus {
  QUEUED("queued"),
  RUNNING("running"),
  DONE("done");

  private final String databaseValue;

  AsyncJobStatus(String databaseValue) {
    this.databaseValue = databaseValue;
  }

  public String getDatabaseValue() {
    return databaseValue;
  }

  public static AsyncJobStatus fromDatabaseValue(String databaseValue) {
    return Arrays.stream(values())
        .filter(status -> status.databaseValue.equals(databaseValue))
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("Unsupported async job status: " + databaseValue));
  }
}
