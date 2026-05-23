package com.box.l10n.mojito.queue;

import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** Dedicated PostgreSQL LISTEN loop for cross-process async queue wakeup hints. */
class JdbcPostgresAsyncJobQueueWakeupListener implements SmartLifecycle {

  static Logger logger = LoggerFactory.getLogger(JdbcPostgresAsyncJobQueueWakeupListener.class);

  private final DataSource dataSource;
  private final String channel;
  private final long listenTimeoutMs;
  private final long triggerJitterMs;
  private final long reconnectDelayMs;
  private final int reconnectJitterPercent;
  private final AsyncJobQueueCoordinator asyncJobQueueCoordinator;
  private final MeterRegistry meterRegistry;
  private final AtomicReference<ListenConnection> activeConnection = new AtomicReference<>();
  private final Object lifecycleLock = new Object();

  private volatile boolean running;
  private volatile Thread listenerThread;

  JdbcPostgresAsyncJobQueueWakeupListener(
      DataSource dataSource,
      AsyncJobQueueProperties asyncJobQueueProperties,
      AsyncJobQueueCoordinator asyncJobQueueCoordinator,
      MeterRegistry meterRegistry) {
    this(dataSource, asyncJobQueueProperties.getWakeup(), asyncJobQueueCoordinator, meterRegistry);
    AsyncJobQueueValidation.validateProperties(asyncJobQueueProperties);
  }

  JdbcPostgresAsyncJobQueueWakeupListener(
      DataSource dataSource,
      AsyncJobQueueProperties.WakeupSettings wakeupSettings,
      AsyncJobQueueCoordinator asyncJobQueueCoordinator,
      MeterRegistry meterRegistry) {
    AsyncJobQueueValidation.validateWakeupSettings(
        wakeupSettings, AsyncJobQueueValidation.STORE_JDBC, AsyncJobQueueJdbcDialect.POSTGRESQL);
    this.dataSource = Objects.requireNonNull(dataSource);
    this.channel =
        AsyncJobQueueValidation.validatePostgresChannel(wakeupSettings.getPostgresChannel());
    this.listenTimeoutMs = wakeupSettings.getPostgresListenTimeoutMs();
    this.triggerJitterMs = wakeupSettings.getTriggerJitterMs();
    this.reconnectDelayMs = wakeupSettings.getReconnectDelayMs();
    this.reconnectJitterPercent = wakeupSettings.getReconnectJitterPercent();
    this.asyncJobQueueCoordinator = Objects.requireNonNull(asyncJobQueueCoordinator);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (running) {
        return;
      }
      if (listenerThread != null && listenerThread.isAlive()) {
        meterRegistry.counter("asyncJobQueue.wakeup.listener.startBlocked").increment();
        throw new IllegalStateException(
            "Cannot start PostgreSQL async queue wakeup listener because the previous listener"
                + " thread is still stopping");
      }
      running = true;
      listenerThread = new Thread(this::runListenerLoop, "async-job-postgres-wakeup-listener");
      listenerThread.setDaemon(true);
      listenerThread.start();
    }
  }

  @Override
  public void stop() {
    Thread threadToJoin;
    synchronized (lifecycleLock) {
      running = false;
      closeActiveConnection();
      threadToJoin = listenerThread;
      if (threadToJoin != null) {
        threadToJoin.interrupt();
      }
    }
    if (threadToJoin != null && threadToJoin != Thread.currentThread()) {
      try {
        threadToJoin.join(Math.min(5_000, Math.max(1, listenTimeoutMs)));
        if (threadToJoin.isAlive()) {
          meterRegistry.counter("asyncJobQueue.wakeup.listener.stopTimeout").increment();
          logger.warn(
              "Timed out waiting for PostgreSQL async queue wakeup listener to stop for channel {}",
              channel);
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        meterRegistry.counter("asyncJobQueue.wakeup.listener.stopInterrupted").increment();
      }
    }
    synchronized (lifecycleLock) {
      if (listenerThread == threadToJoin && (threadToJoin == null || !threadToJoin.isAlive())) {
        listenerThread = null;
      }
    }
  }

  @Override
  public void stop(Runnable callback) {
    stop();
    callback.run();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 1;
  }

  private void runListenerLoop() {
    while (running) {
      try {
        listenUntilDisconnected();
      } catch (Throwable exception) {
        if (isJvmFatal(exception)) {
          throw (Error) exception;
        }
        if (running) {
          incrementListenCounter("failed");
          logger.warn(
              "PostgreSQL async queue wakeup listener failed for channel {}; reconnecting",
              channel,
              exception);
        } else {
          logger.debug(
              "PostgreSQL async queue wakeup listener stopped for channel {}", channel, exception);
        }
      } finally {
        closeActiveConnection();
      }
      sleepBeforeReconnect();
    }
  }

  private void listenUntilDisconnected() throws Exception {
    Class<?> pgConnectionClass = Class.forName("org.postgresql.PGConnection");
    Method getNotifications = pgConnectionClass.getMethod("getNotifications", int.class);
    try (Connection connection = dataSource.getConnection();
        JdbcPostgresAsyncJobQueueWakeupConnections.AutoCommitScope ignored =
            JdbcPostgresAsyncJobQueueWakeupConnections.ensureAutoCommit(connection)) {
      ListenConnection listenConnection = new ListenConnection(connection, ignored);
      activeConnection.set(listenConnection);
      listen(connection);
      Object pgConnection = unwrapPgConnection(connection, pgConnectionClass);
      incrementListenCounter("connected");
      while (running) {
        Object[] notifications =
            (Object[]) getNotifications.invoke(pgConnection, (int) listenTimeoutMs);
        if (notifications == null || notifications.length == 0) {
          continue;
        }
        handleNotifications(notifications);
      }
      activeConnection.compareAndSet(listenConnection, null);
    } catch (InvocationTargetException exception) {
      Throwable targetException = exception.getTargetException();
      if (targetException instanceof Exception targetCheckedException) {
        throw targetCheckedException;
      }
      if (targetException instanceof Error targetError) {
        throw targetError;
      }
      throw new IllegalStateException(targetException);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private Object unwrapPgConnection(Connection connection, Class<?> pgConnectionClass)
      throws SQLException {
    return connection.unwrap((Class) pgConnectionClass);
  }

  private void listen(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("LISTEN " + quotedIdentifier(channel));
    }
  }

  void handleNotification(String notificationChannel, String payload) {
    Set<String> queueNames = new LinkedHashSet<>(1);
    collectNotification(notificationChannel, payload, queueNames);
    triggerQueuePolls(queueNames);
  }

  void handleNotifications(Object[] notifications) throws ReflectiveOperationException {
    Set<String> queueNames = new LinkedHashSet<>(notifications.length);
    for (Object notification : notifications) {
      collectNotification(
          notificationName(notification), notificationPayload(notification), queueNames);
    }
    triggerQueuePolls(queueNames);
  }

  private void collectNotification(
      String notificationChannel, String payload, Set<String> queueNames) {
    if (!channel.equals(notificationChannel)) {
      incrementReceivedCounter("unknown", "wrongChannel");
      return;
    }
    String queueName;
    try {
      queueName = AsyncJobQueueValidation.validateQueueName(payload);
    } catch (RuntimeException exception) {
      incrementReceivedCounter("unknown", "invalidPayload");
      logger.warn(
          "Ignoring PostgreSQL async queue wakeup with invalid queue payload on channel {}",
          channel,
          exception);
      return;
    }
    if (!queueNames.add(queueName)) {
      incrementReceivedCounter(queueName, "duplicate");
    }
  }

  private void triggerQueuePolls(Set<String> queueNames) {
    if (queueNames.isEmpty()) {
      return;
    }
    if (!sleepBeforeTrigger(queueNames)) {
      for (String queueName : queueNames) {
        incrementReceivedCounter(queueName, "triggerInterrupted");
      }
      return;
    }
    for (String queueName : queueNames) {
      try {
        asyncJobQueueCoordinator.triggerPollNow(queueName);
        incrementReceivedCounter(queueName, "triggered");
      } catch (Throwable exception) {
        if (isJvmFatal(exception)) {
          throw (Error) exception;
        }
        incrementReceivedCounter(queueName, "triggerFailed");
        logger.warn(
            "Failed to trigger async queue poll from PostgreSQL wakeup for queue {}",
            queueName,
            exception);
      }
    }
  }

  private boolean sleepBeforeTrigger(Set<String> queueNames) {
    long delayMs = triggerJitterDelayMs();
    if (delayMs <= 0) {
      return true;
    }
    try {
      TimeUnit.MILLISECONDS.sleep(delayMs);
      return true;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      logger.debug(
          "Interrupted before triggering async queue polls from PostgreSQL wakeup for queues {}",
          queueNames,
          exception);
      return false;
    }
  }

  long triggerJitterDelayMs() {
    return randomTriggerJitterDelayMs(triggerJitterMs);
  }

  static long randomTriggerJitterDelayMs(long triggerJitterMs) {
    if (triggerJitterMs <= 0) {
      return 0;
    }
    return ThreadLocalRandom.current().nextLong(triggerJitterMs + 1);
  }

  private String notificationName(Object notification) throws ReflectiveOperationException {
    return (String) notification.getClass().getMethod("getName").invoke(notification);
  }

  private String notificationPayload(Object notification) throws ReflectiveOperationException {
    return (String) notification.getClass().getMethod("getParameter").invoke(notification);
  }

  private void sleepBeforeReconnect() {
    if (!running) {
      return;
    }
    long delayMs = jitteredReconnectDelayMs();
    try {
      TimeUnit.MILLISECONDS.sleep(delayMs);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private long jitteredReconnectDelayMs() {
    if (reconnectJitterPercent <= 0) {
      return reconnectDelayMs;
    }
    long jitterRangeMs =
        AsyncJobQueueRuntime.jitterRangeMs(reconnectDelayMs, reconnectJitterPercent);
    long jitter = ThreadLocalRandom.current().nextLong(-jitterRangeMs, jitterRangeMs + 1);
    return AsyncJobQueueRuntime.positiveJitteredDelayMs(
        reconnectDelayMs, AsyncJobQueueRuntime.addJitter(reconnectDelayMs, jitter));
  }

  private void closeActiveConnection() {
    ListenConnection listenConnection = activeConnection.getAndSet(null);
    if (listenConnection == null) {
      return;
    }
    try {
      listenConnection.close();
    } catch (SQLException exception) {
      logger.warn(
          "Failed to close PostgreSQL async queue wakeup listener connection for channel {}",
          channel,
          exception);
      meterRegistry.counter("asyncJobQueue.wakeup.listener.close.failed").increment();
    }
  }

  static String quotedIdentifier(String identifier) {
    return "\""
        + AsyncJobQueueValidation.validatePostgresChannel(identifier).replace("\"", "\"\"")
        + "\"";
  }

  private void incrementListenCounter(String result) {
    meterRegistry
        .counter("asyncJobQueue.wakeup.listen", "provider", "postgres", "result", result)
        .increment();
  }

  private void incrementReceivedCounter(String queueName, String result) {
    meterRegistry
        .counter(
            "asyncJobQueue.wakeup.received",
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

  private static final class ListenConnection implements AutoCloseable {
    private final Connection connection;
    private final JdbcPostgresAsyncJobQueueWakeupConnections.AutoCommitScope autoCommitScope;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ListenConnection(
        Connection connection,
        JdbcPostgresAsyncJobQueueWakeupConnections.AutoCommitScope autoCommitScope) {
      this.connection = connection;
      this.autoCommitScope = autoCommitScope;
    }

    @Override
    public void close() throws SQLException {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      autoCommitScope.close();
      connection.close();
    }
  }
}
