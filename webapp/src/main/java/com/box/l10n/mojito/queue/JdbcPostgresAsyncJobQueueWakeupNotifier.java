package com.box.l10n.mojito.queue;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publishes PostgreSQL NOTIFY wakeup hints after durable enqueue/replay commits. */
class JdbcPostgresAsyncJobQueueWakeupNotifier implements AsyncJobQueueWakeupNotifier {

  static Logger logger = LoggerFactory.getLogger(JdbcPostgresAsyncJobQueueWakeupNotifier.class);

  private final DataSource dataSource;
  private final String channel;
  private final MeterRegistry meterRegistry;

  JdbcPostgresAsyncJobQueueWakeupNotifier(
      DataSource dataSource,
      AsyncJobQueueProperties asyncJobQueueProperties,
      MeterRegistry meterRegistry) {
    this(dataSource, asyncJobQueueProperties.getWakeup().getPostgresChannel(), meterRegistry);
    AsyncJobQueueValidation.validateProperties(asyncJobQueueProperties);
  }

  JdbcPostgresAsyncJobQueueWakeupNotifier(
      DataSource dataSource, String channel, MeterRegistry meterRegistry) {
    this.dataSource = Objects.requireNonNull(dataSource);
    this.channel = AsyncJobQueueValidation.validatePostgresChannel(channel);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
  }

  @Override
  public void notifyJobAvailable(String queueName, AsyncJobId asyncJobId) {
    String validatedQueueName = AsyncJobQueueValidation.validateQueueName(queueName);
    Objects.requireNonNull(asyncJobId);
    try (Connection connection = dataSource.getConnection();
        JdbcPostgresAsyncJobQueueWakeupConnections.AutoCommitScope ignored =
            JdbcPostgresAsyncJobQueueWakeupConnections.ensureAutoCommit(connection);
        PreparedStatement preparedStatement =
            connection.prepareStatement("SELECT pg_notify(?, ?)")) {
      preparedStatement.setString(1, channel);
      preparedStatement.setString(2, validatedQueueName);
      preparedStatement.execute();
      incrementNotifyCounter(validatedQueueName, "succeeded");
    } catch (Throwable exception) {
      if (isJvmFatal(exception)) {
        throw (Error) exception;
      }
      incrementNotifyCounter(validatedQueueName, "failed");
      logger.warn(
          "Failed to publish async queue PostgreSQL wakeup for queue {}, job {}",
          validatedQueueName,
          asyncJobId.value(),
          exception);
    }
  }

  private void incrementNotifyCounter(String queueName, String result) {
    meterRegistry
        .counter(
            "asyncJobQueue.wakeup.notify",
            "queueName",
            queueName,
            "provider",
            "postgres",
            "result",
            result)
        .increment();
  }

  private boolean isJvmFatal(Throwable throwable) {
    return throwable instanceof VirtualMachineError
        || "java.lang.ThreadDeath".equals(throwable.getClass().getName());
  }
}
