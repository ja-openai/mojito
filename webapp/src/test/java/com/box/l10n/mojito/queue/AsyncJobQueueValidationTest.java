package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import java.time.Duration;
import java.time.Instant;
import org.junit.Test;

public class AsyncJobQueueValidationTest {

  @Test
  public void plusDurationRejectsInstantOverflowWithFieldContext() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AsyncJobQueueValidation.plusDuration(
                    "leaseUntil", Instant.now(), Duration.ofSeconds(Long.MAX_VALUE)));

    assertThat(exception).hasMessageContaining("leaseUntil");
  }

  @Test
  public void validateDatabaseTimestampRejectsPortableMysqlDatetimeBounds() {
    IllegalArgumentException tooEarly =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AsyncJobQueueValidation.validateDatabaseTimestamp(
                    "availableAt", AsyncJobQueueValidation.DATABASE_TIMESTAMP_MIN.minusNanos(1)));
    IllegalArgumentException tooLate =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AsyncJobQueueValidation.validateDatabaseTimestamp(
                    "availableAt", AsyncJobQueueValidation.DATABASE_TIMESTAMP_MAX.plusNanos(1)));

    assertThat(tooEarly).hasMessageContaining("availableAt must be between");
    assertThat(tooLate).hasMessageContaining("availableAt must be between");
  }

  @Test
  public void plusDurationWithinDatabaseTimestampRangeRejectsUnstorableResult() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AsyncJobQueueValidation.plusDurationWithinDatabaseTimestampRange(
                    "availableAt",
                    AsyncJobQueueValidation.DATABASE_TIMESTAMP_MAX,
                    Duration.ofMillis(1)));

    assertThat(exception).hasMessageContaining("availableAt must be between");
  }

  @Test
  public void truncateLastErrorBoundsPersistedErrorSummary() {
    String longError = "x".repeat(AsyncJobQueueValidation.LAST_ERROR_MAX_LENGTH + 1);

    assertThat(AsyncJobQueueValidation.truncateLastError(null)).isNull();
    assertThat(AsyncJobQueueValidation.truncateLastError("short")).isEqualTo("short");
    assertThat(AsyncJobQueueValidation.truncateLastError(longError))
        .hasSize(AsyncJobQueueValidation.LAST_ERROR_MAX_LENGTH);
  }

  @Test
  public void validateJobDataBoundsPersistedPayload() {
    String maxPayload = "x".repeat(AsyncJobQueueValidation.JOB_DATA_MAX_LENGTH);
    String tooLargePayload = maxPayload + "x";

    assertThat(AsyncJobQueueValidation.validateJobData(maxPayload)).isSameAs(maxPayload);
    assertThat(AsyncJobQueueValidation.validateOptionalJobData(null)).isNull();

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> AsyncJobQueueValidation.validateJobData(tooLargePayload)))
        .hasMessageContaining("jobData must be at most");
  }

  @Test
  public void validateTerminalStatusRejectsNonTerminalStatuses() {
    assertThat(AsyncJobQueueValidation.validateTerminalStatus(AsyncJobStatus.DONE))
        .isEqualTo(AsyncJobStatus.DONE);
    assertThat(AsyncJobQueueValidation.validateTerminalStatus(AsyncJobStatus.FAILED))
        .isEqualTo(AsyncJobStatus.FAILED);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> AsyncJobQueueValidation.validateTerminalStatus(AsyncJobStatus.QUEUED));

    assertThat(exception).hasMessageContaining("status must be terminal");
  }

  @Test
  public void validateStoreQueryLimitRejectsExcessiveLimit() {
    assertThat(AsyncJobQueueValidation.validateStoreQueryLimit("limit", 1)).isEqualTo(1);
    assertThat(
            AsyncJobQueueValidation.validateStoreQueryLimit(
                "limit", AsyncJobQueueValidation.STORE_QUERY_LIMIT_MAX))
        .isEqualTo(AsyncJobQueueValidation.STORE_QUERY_LIMIT_MAX);

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> AsyncJobQueueValidation.validateStoreQueryLimit("limit", 0)))
        .hasMessageContaining("limit must be > 0");
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    AsyncJobQueueValidation.validateStoreQueryLimit(
                        "limit", AsyncJobQueueValidation.STORE_QUERY_LIMIT_MAX + 1)))
        .hasMessageContaining("limit must be <=");
  }

  @Test
  public void validateQueueNameAllowsOnlyBoundedMetricSafeIdentifiers() {
    assertThat(AsyncJobQueueValidation.validateQueueName("assetlocalize"))
        .isEqualTo("assetlocalize");
    assertThat(AsyncJobQueueValidation.validateQueueName("stats.v2_batch-1"))
        .isEqualTo("stats.v2_batch-1");

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> AsyncJobQueueValidation.validateQueueName("asset localize")))
        .hasMessageContaining("letters, numbers, dots, underscores, or dashes");
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> AsyncJobQueueValidation.validateQueueName("asset/localize")))
        .hasMessageContaining("letters, numbers, dots, underscores, or dashes");
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> AsyncJobQueueValidation.validateQueueName("asset:localize")))
        .hasMessageContaining("letters, numbers, dots, underscores, or dashes");
  }

  @Test
  public void validateStatusMetricsIntervalRejectsExcessiveInterval() {
    assertThat(
            AsyncJobQueueValidation.validateStatusMetricsIntervalMs(
                AsyncJobQueueValidation.STATUS_METRICS_INTERVAL_MS_MAX))
        .isEqualTo(AsyncJobQueueValidation.STATUS_METRICS_INTERVAL_MS_MAX);

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    AsyncJobQueueValidation.validateStatusMetricsIntervalMs(
                        AsyncJobQueueValidation.STATUS_METRICS_INTERVAL_MS_MAX + 1)))
        .hasMessageContaining("statusMetricsIntervalMs must be <=");
  }

  @Test
  public void validateQueueSettingsRejectsExcessiveRuntimeBounds() {
    assertInvalidQueueSetting(
        settings -> settings.setPollIntervalMs(AsyncJobQueueValidation.POLL_INTERVAL_MS_MAX + 1),
        "pollIntervalMs must be <=");
    assertInvalidQueueSetting(
        settings ->
            settings.setMaxPollIntervalMs(AsyncJobQueueValidation.MAX_POLL_INTERVAL_MS_MAX + 1),
        "maxPollIntervalMs must be <=");
    assertInvalidQueueSetting(
        settings -> settings.setLeaseDurationMs(AsyncJobQueueValidation.LEASE_DURATION_MS_MAX + 1),
        "leaseDurationMs must be <=");
    assertInvalidQueueSetting(
        settings -> {
          settings.setLeaseDurationMs(AsyncJobQueueValidation.LEASE_DURATION_MS_MAX);
          settings.setHeartbeatIntervalMs(AsyncJobQueueValidation.HEARTBEAT_INTERVAL_MS_MAX + 1);
        },
        "heartbeatIntervalMs must be <=");
    assertInvalidQueueSetting(
        settings -> settings.setMaxAttempts(AsyncJobQueueValidation.MAX_ATTEMPTS_MAX + 1),
        "maxAttempts must be <=");
    assertInvalidQueueSetting(
        settings -> settings.setMaxRetryDelayMs(AsyncJobQueueValidation.MAX_RETRY_DELAY_MS_MAX + 1),
        "maxRetryDelayMs must be <=");
    assertInvalidQueueSetting(
        settings -> settings.setShutdownAwaitTerminationMs(-1),
        "shutdownAwaitTerminationMs must be >=");
    assertInvalidQueueSetting(
        settings ->
            settings.setShutdownAwaitTerminationMs(
                AsyncJobQueueValidation.SHUTDOWN_AWAIT_TERMINATION_MS_MAX + 1),
        "shutdownAwaitTerminationMs must be <=");
  }

  @Test
  public void validateRetentionSettingsRejectsInvalidBounds() {
    assertInvalidRetentionSetting(
        settings -> settings.setIntervalMs(0), "retention.intervalMs must be > 0");
    assertInvalidRetentionSetting(
        settings -> settings.setIntervalMs(AsyncJobQueueValidation.RETENTION_INTERVAL_MS_MAX + 1),
        "retention.intervalMs must be <=");
    assertInvalidRetentionSetting(
        settings -> settings.setDoneRetentionMs(0), "retention.doneRetentionMs must be > 0");
    assertInvalidRetentionSetting(
        settings -> settings.setDoneRetentionMs(AsyncJobQueueValidation.RETENTION_AGE_MS_MAX + 1),
        "retention.doneRetentionMs must be <=");
    assertInvalidRetentionSetting(
        settings -> settings.setFailedRetentionMs(0), "retention.failedRetentionMs must be > 0");
    assertInvalidRetentionSetting(
        settings -> settings.setFailedRetentionMs(AsyncJobQueueValidation.RETENTION_AGE_MS_MAX + 1),
        "retention.failedRetentionMs must be <=");
    assertInvalidRetentionSetting(
        settings -> settings.setBatchSize(0), "retention.batchSize must be > 0");
    assertInvalidRetentionSetting(
        settings -> settings.setBatchSize(AsyncJobQueueValidation.RETENTION_BATCH_SIZE_MAX + 1),
        "retention.batchSize must be <=");
  }

  @Test
  public void validateWakeupSettingsAllowsPollingForAnyStore() {
    AsyncJobQueueProperties.WakeupSettings wakeupSettings =
        new AsyncJobQueueProperties.WakeupSettings();

    assertThat(
            AsyncJobQueueValidation.validateWakeupSettings(
                wakeupSettings, AsyncJobQueueValidation.STORE_IN_MEMORY, null))
        .isSameAs(wakeupSettings);
  }

  @Test
  public void validateWakeupSettingsRequiresPostgresJdbcForListenNotify() {
    AsyncJobQueueProperties.WakeupSettings wakeupSettings =
        new AsyncJobQueueProperties.WakeupSettings();
    wakeupSettings.setMode(AsyncJobQueueValidation.WAKEUP_MODE_POSTGRES_LISTEN_NOTIFY);

    assertThat(
            AsyncJobQueueValidation.validateWakeupSettings(
                wakeupSettings,
                AsyncJobQueueValidation.STORE_JDBC,
                AsyncJobQueueJdbcDialect.POSTGRESQL))
        .isSameAs(wakeupSettings);

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    AsyncJobQueueValidation.validateWakeupSettings(
                        wakeupSettings, AsyncJobQueueValidation.STORE_IN_MEMORY, null)))
        .hasMessageContaining("requires store=jdbc and jdbcDialect=postgresql");
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    AsyncJobQueueValidation.validateWakeupSettings(
                        wakeupSettings,
                        AsyncJobQueueValidation.STORE_JDBC,
                        AsyncJobQueueJdbcDialect.MYSQL)))
        .hasMessageContaining("requires store=jdbc and jdbcDialect=postgresql");
  }

  @Test
  public void validateWakeupSettingsRejectsUnsafePostgresConfig() {
    assertInvalidWakeupSetting(settings -> settings.setMode(""), "wakeup.mode must not be blank");
    assertInvalidWakeupSetting(settings -> settings.setMode("redis"), "wakeup.mode must be one of");
    assertInvalidWakeupSetting(
        settings -> settings.setPostgresChannel("bad-channel"),
        "wakeup.postgresChannel must be a PostgreSQL-safe identifier");
    assertInvalidWakeupSetting(
        settings -> settings.setPostgresListenTimeoutMs(0),
        "wakeup.postgresListenTimeoutMs must be > 0");
    assertInvalidWakeupSetting(
        settings ->
            settings.setPostgresListenTimeoutMs(
                AsyncJobQueueValidation.WAKEUP_LISTEN_TIMEOUT_MS_MAX + 1),
        "wakeup.postgresListenTimeoutMs must be <=");
    assertInvalidWakeupSetting(
        settings -> settings.setTriggerJitterMs(-1), "wakeup.triggerJitterMs must be >= 0");
    assertInvalidWakeupSetting(
        settings ->
            settings.setTriggerJitterMs(AsyncJobQueueValidation.WAKEUP_TRIGGER_JITTER_MS_MAX + 1),
        "wakeup.triggerJitterMs must be <=");
    assertInvalidWakeupSetting(
        settings -> settings.setReconnectDelayMs(0), "wakeup.reconnectDelayMs must be > 0");
    assertInvalidWakeupSetting(
        settings ->
            settings.setReconnectDelayMs(AsyncJobQueueValidation.WAKEUP_RECONNECT_DELAY_MS_MAX + 1),
        "wakeup.reconnectDelayMs must be <=");
    assertInvalidWakeupSetting(
        settings -> settings.setReconnectJitterPercent(101),
        "wakeup.reconnectJitterPercent must be between 0 and 100");
  }

  private void assertInvalidQueueSetting(
      java.util.function.Consumer<AsyncJobQueueProperties.QueueSettings> mutator,
      String expectedMessage) {
    AsyncJobQueueProperties.QueueSettings queueSettings =
        new AsyncJobQueueProperties.QueueSettings();
    mutator.accept(queueSettings);

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> AsyncJobQueueValidation.validateQueueSettings(queueSettings)))
        .hasMessageContaining(expectedMessage);
  }

  private void assertInvalidRetentionSetting(
      java.util.function.Consumer<AsyncJobQueueProperties.RetentionSettings> mutator,
      String expectedMessage) {
    AsyncJobQueueProperties.RetentionSettings retentionSettings =
        new AsyncJobQueueProperties.RetentionSettings();
    mutator.accept(retentionSettings);

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> AsyncJobQueueValidation.validateRetentionSettings(retentionSettings)))
        .hasMessageContaining(expectedMessage);
  }

  private void assertInvalidWakeupSetting(
      java.util.function.Consumer<AsyncJobQueueProperties.WakeupSettings> mutator,
      String expectedMessage) {
    AsyncJobQueueProperties.WakeupSettings wakeupSettings =
        new AsyncJobQueueProperties.WakeupSettings();
    mutator.accept(wakeupSettings);

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    AsyncJobQueueValidation.validateWakeupSettings(
                        wakeupSettings,
                        AsyncJobQueueValidation.STORE_JDBC,
                        AsyncJobQueueJdbcDialect.POSTGRESQL)))
        .hasMessageContaining(expectedMessage);
  }
}
