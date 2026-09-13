package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Actual lost TCP replies during commit, not injected JDBC failures or multi-host partition soak.
 */
@RunWith(Parameterized.class)
public class JdbcAsyncJobStoreNetworkIntegrationTest {
  private final AsyncJobQueueJdbcDialect dialect;

  @Parameterized.Parameters(name = "{0}")
  public static List<AsyncJobQueueJdbcDialect> databases() {
    return List.of(AsyncJobQueueJdbcDialect.MYSQL, AsyncJobQueueJdbcDialect.POSTGRESQL);
  }

  public JdbcAsyncJobStoreNetworkIntegrationTest(AsyncJobQueueJdbcDialect dialect) {
    this.dialect = dialect;
  }

  @Test
  public void lostEnqueueCommitReplyPreservesOneJobAndPoolRecovers() throws Exception {
    assertLostCommitReply(false);
  }

  @Test
  public void lostCompletionCommitReplyLeavesDoneTerminalAndPoolRecovers() throws Exception {
    assertLostCommitReply(true);
  }

  @Test
  public void lostHeartbeatReplyKeepsLiveHandlerAndRecoversRenewal() throws Exception {
    assertLostHeartbeatReply(false);
  }

  @Test
  public void lostHeartbeatReplyPastExpiryRejectsStaleHandlerResult() throws Exception {
    assertLostHeartbeatReply(true);
  }

  private void assertLostHeartbeatReply(boolean expireLease) throws Exception {
    assumeTrue(Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
    try (JdbcDatabaseContainer<?> database = database()) {
      database.start();
      initializeSchema(database);
      JdbcAsyncJobStore peer = store(independentSource(database));
      String queue = "lost-heartbeat-" + UUID.randomUUID();
      AsyncJobId id = peer.enqueueNow(queue, "{}");
      AtomicInteger invocations = new AtomicInteger();
      AtomicInteger callbacks = new AtomicInteger();
      AtomicBoolean interrupted = new AtomicBoolean();
      CountDownLatch release = new CountDownLatch(1);
      CompletableFuture<AsyncJobRecord> dispatched = new CompletableFuture<>();
      CompletableFuture<AsyncJobRecord> done = new CompletableFuture<>();
      CompletableFuture<AsyncJobRecord> beforeRenewal = new CompletableFuture<>();
      CompletableFuture<Boolean> rejectedHeartbeat = new CompletableFuture<>();
      CompletableFuture<Boolean> completion = new CompletableFuture<>();
      AsyncJobHandler handler =
          new AsyncJobHandler() {
            @Override
            public String queueName() {
              return queue;
            }

            @Override
            public AsyncJobHandlerResult process(AsyncJobRecord job) throws Exception {
              invocations.incrementAndGet();
              dispatched.complete(job);
              try {
                assertTrue("test releases the live handler", release.await(30, TimeUnit.SECONDS));
              } catch (InterruptedException failure) {
                interrupted.set(true);
                throw failure;
              }
              return AsyncJobHandlerResult.done("{\"renewed\":true}");
            }

            @Override
            public void onJobDone(AsyncJobRecord job, AsyncJobHandlerResult result) {
              callbacks.incrementAndGet();
              done.complete(job);
            }
          };
      SimpleMeterRegistry meters = new SimpleMeterRegistry();
      try (AutoCloseable meterCleanup = meters::close;
          ReplyBlackhole relay =
              new ReplyBlackhole(database.getHost(), database.getFirstMappedPort());
          HikariDataSource pool = pool(database, relay)) {
        CommitGate source =
            new CommitGate(
                pool, relay, () -> beforeRenewal.complete(peer.getByIds(List.of(id)).getFirst()));
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setAwaitTerminationMillis(5000);
        scheduler.setThreadNamePrefix("lost-heartbeat-poll-");
        try (AutoCloseable schedulerCleanup = scheduler::shutdown) {
          scheduler.initialize();
          AsyncJobQueueProperties properties = runtimeProperties(queue);
          // Recovery needs handshake time; the expiry case deliberately outlasts a shorter lease.
          properties.getQueues().get(queue).setLeaseDurationMs(expireLease ? 10_000 : 30_000);
          AsyncJobQueueCoordinator coordinator =
              new AsyncJobQueueCoordinator(
                  observeTransitions(source, rejectedHeartbeat, completion),
                  properties,
                  List.of(handler),
                  scheduler,
                  meters);
          try (AutoCloseable coordinatorCleanup = coordinator::stop) {
            coordinator.start();
            try {
              AsyncJobRecord original = dispatched.get(10, TimeUnit.SECONDS);
              source.armed.set(true);
              assertTrue("heartbeat enters commit", source.entered.await(10, TimeUnit.SECONDS));
              int commitsAtFault = source.commits.get();
              int sessionsAtCommit = relay.sessions.get();
              AsyncJobRecord previousRenewal = beforeRenewal.get(1, TimeUnit.SECONDS);
              awaitCondition(
                  () ->
                      peer.getByIds(List.of(id))
                          .getFirst()
                          .leaseUntil()
                          .isAfter(previousRenewal.leaseUntil()));
              AsyncJobRecord unacknowledged = peer.getByIds(List.of(id)).getFirst();
              assertThat(unacknowledged.status()).isEqualTo(AsyncJobStatus.RUNNING);
              assertThat(unacknowledged.leaseToken()).isEqualTo(original.leaseToken());
              assertThat(unacknowledged.attemptCount()).isEqualTo(1);
              assertThat(source.commitFailure.isDone())
                  .as("renewal committed before timeout")
                  .isFalse();
              assertThat(callbacks.get()).isZero();
              assertThat(meters.get("asyncJobQueue.inflight").gauge().value()).isEqualTo(1);

              assertThat(source.commitFailure.get(10, TimeUnit.SECONDS))
                  .hasRootCauseInstanceOf(SocketTimeoutException.class);
              assertThat(source.commits.get()).isEqualTo(commitsAtFault);
              assertThat(relay.droppedBytes.get()).isPositive();
              assertThat(interrupted.get()).isFalse();
              assertThat(done.isDone()).isFalse();
              assertThat(peer.claimNextJobs(queue, 1, "peer", Duration.ofSeconds(30))).isEmpty();

              if (expireLease) {
                awaitCondition(
                    () -> peer.expiredLeaseStatus(queue).count() == 1,
                    15,
                    "unacknowledged heartbeat lease expires while replies remain blocked");
                assertThat(peer.getByIds(List.of(id)).getFirst()).isEqualTo(unacknowledged);
                List<AsyncJobRecord> reclaimed =
                    peer.claimNextJobs(queue, 1, original.workerId(), Duration.ofSeconds(30));
                assertThat(reclaimed).hasSize(1);
                AsyncJobRecord winner = reclaimed.getFirst();
                assertThat(winner.id()).isEqualTo(id);
                assertThat(winner.attemptCount()).isEqualTo(2);
                assertThat(winner.leaseReclaimed()).isTrue();
                assertThat(winner.leaseToken()).isNotEqualTo(original.leaseToken());
                assertThat(winner.updatedDate()).isAfterOrEqualTo(unacknowledged.leaseUntil());
                AsyncJobRecord winningRow = peer.getByIds(List.of(id)).getFirst();

                relay.discardReplies.set(false);
                assertThat(rejectedHeartbeat.get(15, TimeUnit.SECONDS)).isFalse();
                assertThat(release.getCount()).isEqualTo(1);
                assertThat(interrupted.get()).isFalse();
                assertThat(peer.getByIds(List.of(id)).getFirst()).isEqualTo(winningRow);
                release.countDown();
                assertThat(completion.get(10, TimeUnit.SECONDS)).isFalse();
                awaitDrained(meters);
                assertThat(invocations.get()).isEqualTo(1);
                assertThat(callbacks.get()).isZero();
                assertThat(done.isDone()).isFalse();
                assertThat(
                        meters
                            .get("asyncJobQueue.transition.failed")
                            .tag("transition", "done")
                            .counter()
                            .count())
                    .isEqualTo(1);
                assertThat(peer.getByIds(List.of(id)).getFirst()).isEqualTo(winningRow);
                assertThat(
                        peer.markDone(
                            queue, id, winner.workerId(), winner.leaseToken(), "{\"winner\":true}"))
                    .isTrue();
                AsyncJobRecord terminal = peer.getByIds(List.of(id)).getFirst();
                assertThat(terminal.status()).isEqualTo(AsyncJobStatus.DONE);
                assertThat(terminal.attemptCount()).isEqualTo(2);
                assertThat(terminal.jobData()).isEqualTo("{\"winner\":true}");
                assertThat(relay.sessions.get()).isGreaterThan(sessionsAtCommit);
                assertThat(relay.failures).isEmpty();
              } else {
                relay.discardReplies.set(false);
                awaitCondition(
                    () ->
                        peer.getByIds(List.of(id))
                            .getFirst()
                            .leaseUntil()
                            .isAfter(unacknowledged.leaseUntil()),
                    15);
                AsyncJobRecord renewed = peer.getByIds(List.of(id)).getFirst();
                assertThat(renewed.workerId()).isEqualTo(original.workerId());
                assertThat(renewed.leaseToken()).isEqualTo(original.leaseToken());
                assertThat(renewed.attemptCount()).isEqualTo(1);
                assertThat(relay.sessions.get()).isGreaterThan(sessionsAtCommit);
                assertThat(meters.get("asyncJobQueue.heartbeat.failed").counter().count())
                    .isGreaterThanOrEqualTo(1);

                release.countDown();
                assertThat(done.get(10, TimeUnit.SECONDS).status()).isEqualTo(AsyncJobStatus.DONE);
                awaitDrained(meters);
                assertThat(invocations.get()).isEqualTo(1);
                assertThat(callbacks.get()).isEqualTo(1);
                assertThat(interrupted.get()).isFalse();
                AsyncJobRecord terminal = peer.getByIds(List.of(id)).getFirst();
                assertThat(terminal.status()).isEqualTo(AsyncJobStatus.DONE);
                assertThat(terminal.attemptCount()).isEqualTo(1);
                assertThat(terminal.jobData()).isEqualTo("{\"renewed\":true}");
                assertThat(relay.failures).isEmpty();
              }
            } finally {
              relay.discardReplies.set(false);
              release.countDown();
            }
          }
        }
        awaitPoolDrained(pool);
      }
    }
  }

  @Test
  public void lostClaimReplyIsNotDispatchedAndRuntimeRecoversAfterLeaseExpiry() throws Exception {
    assumeTrue(Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
    try (JdbcDatabaseContainer<?> database = database()) {
      database.start();
      initializeSchema(database);
      JdbcAsyncJobStore peer = store(independentSource(database));
      String queue = "lost-claim-" + UUID.randomUUID();
      AsyncJobId id = peer.enqueueNow(queue, "{}");
      AtomicInteger invocations = new AtomicInteger();
      AtomicInteger callbacks = new AtomicInteger();
      CompletableFuture<AsyncJobRecord> dispatched = new CompletableFuture<>();
      CompletableFuture<AsyncJobRecord> done = new CompletableFuture<>();
      AsyncJobHandler handler =
          new AsyncJobHandler() {
            @Override
            public String queueName() {
              return queue;
            }

            @Override
            public AsyncJobHandlerResult process(AsyncJobRecord job) {
              invocations.incrementAndGet();
              dispatched.complete(job);
              return AsyncJobHandlerResult.done("{\"recovered\":true}");
            }

            @Override
            public void onJobDone(AsyncJobRecord job, AsyncJobHandlerResult result) {
              callbacks.incrementAndGet();
              done.complete(job);
            }
          };
      SimpleMeterRegistry meters = new SimpleMeterRegistry();
      try (AutoCloseable meterCleanup = meters::close;
          ReplyBlackhole relay =
              new ReplyBlackhole(database.getHost(), database.getFirstMappedPort());
          HikariDataSource pool = pool(database, relay)) {
        CommitGate source = new CommitGate(pool, relay);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setAwaitTerminationMillis(5000);
        scheduler.setThreadNamePrefix("lost-claim-poll-");
        try (AutoCloseable schedulerCleanup = scheduler::shutdown) {
          scheduler.initialize();
          AsyncJobQueueCoordinator coordinator =
              new AsyncJobQueueCoordinator(
                  store(source), runtimeProperties(queue), List.of(handler), scheduler, meters);
          try (AutoCloseable coordinatorCleanup = coordinator::stop) {
            source.armed.set(true);
            coordinator.start();
            try {
              assertTrue("claim enters commit", source.entered.await(10, TimeUnit.SECONDS));
              int sessionsAtCommit = relay.sessions.get();
              awaitCondition(
                  () -> peer.getByIds(List.of(id)).getFirst().status() == AsyncJobStatus.RUNNING);
              AsyncJobRecord unacknowledged = peer.getByIds(List.of(id)).getFirst();
              assertThat(unacknowledged.attemptCount()).isEqualTo(1);
              assertThat(source.commitFailure.isDone())
                  .as("commit is visible before timeout")
                  .isFalse();
              assertThat(invocations.get()).isZero();
              assertThat(callbacks.get()).isZero();
              assertThat(meters.get("asyncJobQueue.inflight").gauge().value()).isZero();

              assertThat(source.commitFailure.get(10, TimeUnit.SECONDS))
                  .hasRootCauseInstanceOf(SocketTimeoutException.class);
              assertThat(source.commits.get()).as("one uncertain claim commit").isEqualTo(1);
              assertThat(relay.droppedBytes.get()).isPositive();
              relay.discardReplies.set(false);
              // This is a real, unexpired committed lease, not an injected or backdated row.
              assertThat(peer.claimNextJobs(queue, 1, "peer", Duration.ofSeconds(10))).isEmpty();
              assertThat(invocations.get()).isZero();

              AsyncJobRecord recovered = dispatched.get(20, TimeUnit.SECONDS);
              assertThat(recovered.id()).isEqualTo(id);
              assertThat(recovered.attemptCount()).isEqualTo(2);
              assertThat(recovered.leaseReclaimed()).isTrue();
              assertThat(recovered.workerId()).isEqualTo(unacknowledged.workerId());
              assertThat(recovered.leaseToken()).isNotEqualTo(unacknowledged.leaseToken());
              assertThat(recovered.updatedDate()).isAfterOrEqualTo(unacknowledged.leaseUntil());
              assertThat(done.get(10, TimeUnit.SECONDS).status()).isEqualTo(AsyncJobStatus.DONE);
              awaitDrained(meters);
              assertThat(invocations.get()).isEqualTo(1);
              assertThat(callbacks.get()).isEqualTo(1);
              AsyncJobRecord terminal = peer.getByIds(List.of(id)).getFirst();
              assertThat(terminal.status()).isEqualTo(AsyncJobStatus.DONE);
              assertThat(terminal.attemptCount()).isEqualTo(2);
              assertThat(terminal.jobData()).isEqualTo("{\"recovered\":true}");
              assertThat(relay.sessions.get()).isGreaterThan(sessionsAtCommit);
              assertThat(
                      meters.find("asyncJobQueue.claim.failed").counters().stream()
                          .mapToDouble(counter -> counter.count())
                          .sum())
                  .isGreaterThanOrEqualTo(1);
            } finally {
              relay.discardReplies.set(false);
            }
          }
        }
        awaitPoolDrained(pool);
      }
    }
  }

  private static AsyncJobQueueProperties runtimeProperties(String queue) {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    properties.setStore("jdbc");
    AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setMaxConcurrency(1);
    settings.setClaimBatchSize(1);
    settings.setPollIntervalMs(25);
    settings.setMaxPollIntervalMs(100);
    settings.setPollJitterPercent(0);
    settings.setLeaseDurationMs(10_000);
    settings.setHeartbeatIntervalMs(1000);
    settings.setShutdownAwaitTerminationMs(5000);
    properties.getQueues().put(queue, settings);
    return properties;
  }

  private static void awaitDrained(SimpleMeterRegistry meters) throws InterruptedException {
    awaitCondition(
        () ->
            meters.get("asyncJobQueue.inflight").gauge().value() == 0
                && meters.get("asyncJobQueue.executor.active").gauge().value() == 0
                && meters.get("asyncJobQueue.executor.queued").gauge().value() == 0);
  }

  private static void awaitPoolDrained(HikariDataSource pool) throws InterruptedException {
    // Handler drainage and heartbeat cancellation do not join a renewal already inside JDBC.
    awaitCondition(
        () ->
            pool.getHikariPoolMXBean().getActiveConnections() == 0
                && pool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 0,
        15,
        "heartbeat connections and waiters drain after coordinator shutdown");
  }

  private void initializeSchema(JdbcDatabaseContainer<?> database) throws SQLException {
    try (Connection connection = database.createConnection("")) {
      ScriptUtils.executeSqlScript(
          connection,
          new ClassPathResource(
              dialect == AsyncJobQueueJdbcDialect.MYSQL
                  ? "db/migration/V109__Async_Job_Queue.sql"
                  : "db/postgresql/migration/V109__Async_Job_Queue.sql"));
    }
  }

  private static DataSource independentSource(JdbcDatabaseContainer<?> database) {
    return new DriverManagerDataSource(
        database.getJdbcUrl(), database.getUsername(), database.getPassword());
  }

  private void assertLostCommitReply(boolean completion) throws Exception {
    assumeTrue(Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
    try (JdbcDatabaseContainer<?> database = database()) {
      database.start();
      initializeSchema(database);
      DataSource direct = independentSource(database);
      JdbcTemplate oracle = new JdbcTemplate(direct);
      oracle.setQueryTimeout(5);
      JdbcAsyncJobStore peer = store(direct);
      String queue = "lost-reply-" + UUID.randomUUID();
      AsyncJobRecord running = completion ? seedRunning(peer, queue) : null;
      try (ReplyBlackhole relay =
              new ReplyBlackhole(database.getHost(), database.getFirstMappedPort());
          HikariDataSource pool = pool(database, relay)) {
        CommitGate source = new CommitGate(pool, relay);
        JdbcAsyncJobStore worker = store(source);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
          source.armed.set(true);
          Future<?> operation =
              executor.submit(
                  () -> {
                    if (completion) {
                      return worker.markDone(
                          queue,
                          running.id(),
                          running.workerId(),
                          running.leaseToken(),
                          "{\"done\":true}");
                    }
                    return worker.enqueueNow(queue, "{}");
                  });
          assertTrue(
              "commit reaches the real JDBC driver", source.entered.await(10, TimeUnit.SECONDS));
          int sessionsAtCommit = relay.sessions.get();
          // The oracle bypasses the relay: prove the transaction committed while its caller waits.
          awaitCondition(
              () ->
                  oracle.queryForObject(
                          "SELECT COUNT(*) FROM async_job_queue WHERE queue_name = ? AND status = ?",
                          Integer.class,
                          queue,
                          (completion ? AsyncJobStatus.DONE : AsyncJobStatus.QUEUED)
                              .getDatabaseValue())
                      == 1);
          assertThat(operation.isDone()).as("server commit precedes caller timeout").isFalse();
          Throwable failure;
          try {
            operation.get(15, TimeUnit.SECONDS);
            throw new AssertionError("missing commit reply must fail instead of reporting success");
          } catch (ExecutionException expected) {
            failure = expected.getCause();
          }
          assertThat(failure).hasRootCauseInstanceOf(SocketTimeoutException.class);
          assertThat(source.commits.get()).as("no blind queue-operation retry").isEqualTo(1);
          assertThat(relay.droppedBytes.get()).as("real server bytes were discarded").isPositive();
          assertThat(relay.failures).isEmpty();
          Long id =
              oracle.queryForObject(
                  "SELECT id FROM async_job_queue WHERE queue_name = ?", Long.class, queue);
          AsyncJobRecord committed =
              peer.getByIds(List.of(new AsyncJobId(id.toString()))).getFirst();
          assertThat(committed.status())
              .isEqualTo(completion ? AsyncJobStatus.DONE : AsyncJobStatus.QUEUED);
          assertThat(committed.jobData()).isEqualTo(completion ? "{\"done\":true}" : "{}");
          assertThat(committed.attemptCount()).isEqualTo(completion ? 1 : 0);

          relay.discardReplies.set(false);
          assertThat(worker.getByIds(List.of(committed.id()))).containsExactly(committed);
          assertThat(relay.sessions.get())
              .as("same pool replaces the timed-out physical connection")
              .isGreaterThan(sessionsAtCommit);
          if (completion) {
            assertThat(worker.claimNextJobs(queue, 1, "replacement", Duration.ofSeconds(30)))
                .isEmpty();
            assertThat(
                    worker.markDone(
                        queue, running.id(), running.workerId(), running.leaseToken(), "{}"))
                .isFalse();
            assertThat(peer.getByIds(List.of(committed.id()))).containsExactly(committed);
          } else {
            List<AsyncJobRecord> claims =
                worker.claimNextJobs(queue, 1, "replacement", Duration.ofSeconds(30));
            assertThat(claims).hasSize(1);
            AsyncJobRecord claim = claims.getFirst();
            assertThat(claim.id()).isEqualTo(committed.id());
            assertThat(claim.attemptCount()).isEqualTo(1);
            assertThat(
                    worker.markDone(queue, claim.id(), claim.workerId(), claim.leaseToken(), null))
                .isTrue();
            assertThat(worker.claimNextJobs(queue, 1, "replacement", Duration.ofSeconds(30)))
                .isEmpty();
          }
          assertThat(oracle.queryForObject("SELECT COUNT(*) FROM async_job_queue", Integer.class))
              .isEqualTo(1);
          assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
          assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
        } finally {
          relay.discardReplies.set(false);
          executor.shutdownNow();
          assertTrue("queue call exits", executor.awaitTermination(10, TimeUnit.SECONDS));
        }
      }
    }
  }

  private AsyncJobRecord seedRunning(JdbcAsyncJobStore peer, String queue) {
    peer.enqueueNow(queue, "{}");
    return peer.claimNextJobs(queue, 1, "original", Duration.ofSeconds(60)).getFirst();
  }

  private JdbcAsyncJobStore store(DataSource source) {
    return new JdbcAsyncJobStore(
        new NamedParameterJdbcTemplate(source), dialect, new DataSourceTransactionManager(source));
  }

  private JdbcAsyncJobStore observeTransitions(
      DataSource source,
      CompletableFuture<Boolean> rejectedHeartbeat,
      CompletableFuture<Boolean> completion) {
    return new JdbcAsyncJobStore(
        new NamedParameterJdbcTemplate(source), dialect, new DataSourceTransactionManager(source)) {
      @Override
      public boolean heartbeat(
          String queue, AsyncJobId id, String worker, String token, Duration duration) {
        boolean renewed = super.heartbeat(queue, id, worker, token, duration);
        if (!renewed) {
          rejectedHeartbeat.complete(false);
        }
        return renewed;
      }

      @Override
      public boolean markDone(
          String queue, AsyncJobId id, String worker, String token, String data) {
        boolean markedDone = super.markDone(queue, id, worker, token, data);
        completion.complete(markedDone);
        return markedDone;
      }
    };
  }

  private JdbcDatabaseContainer<?> database() {
    return dialect == AsyncJobQueueJdbcDialect.MYSQL
        ? new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectTimeout", "5000")
            .withUrlParam("socketTimeout", "5000")
        : new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("connectTimeout", "5")
            .withUrlParam("socketTimeout", "5");
  }

  private HikariDataSource pool(JdbcDatabaseContainer<?> database, ReplyBlackhole relay) {
    String originalAuthority = database.getHost() + ":" + database.getFirstMappedPort();
    assertThat(database.getJdbcUrl()).contains("//" + originalAuthority + "/");
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(
        database
            .getJdbcUrl()
            .replace("//" + originalAuthority + "/", "//127.0.0.1:" + relay.port() + "/"));
    config.setUsername(database.getUsername());
    config.setPassword(database.getPassword());
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(5000);
    config.setValidationTimeout(1000);
    config.setInitializationFailTimeout(5000);
    // MySQL expects milliseconds; PostgreSQL expects seconds.
    config.addDataSourceProperty(
        "socketTimeout", dialect == AsyncJobQueueJdbcDialect.MYSQL ? "5000" : "5");
    return new HikariDataSource(config);
  }

  private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
    awaitCondition(condition, 3);
  }

  private static void awaitCondition(BooleanSupplier condition, long timeoutSeconds)
      throws InterruptedException {
    awaitCondition(condition, timeoutSeconds, "independent connection observes the server commit");
  }

  private static void awaitCondition(
      BooleanSupplier condition, long timeoutSeconds, String description)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    CountDownLatch pause = new CountDownLatch(1);
    while (!condition.getAsBoolean()) {
      assertTrue(description, System.nanoTime() < deadline);
      pause.await(20, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Only positions the transport fault; commit executes normally and JDBC generates the failure.
   */
  private static final class CommitGate extends DelegatingDataSource {
    final AtomicBoolean armed = new AtomicBoolean();
    final AtomicInteger commits = new AtomicInteger();
    final CountDownLatch entered = new CountDownLatch(1);
    final CompletableFuture<Throwable> commitFailure = new CompletableFuture<>();
    private final ReplyBlackhole relay;
    private final Runnable beforeFaultCommit;

    CommitGate(DataSource target, ReplyBlackhole relay) {
      this(target, relay, () -> {});
    }

    CommitGate(DataSource target, ReplyBlackhole relay, Runnable beforeFaultCommit) {
      super(target);
      this.relay = relay;
      this.beforeFaultCommit = beforeFaultCommit;
    }

    @Override
    public Connection getConnection() throws SQLException {
      Connection target = super.getConnection();
      return (Connection)
          Proxy.newProxyInstance(
              ConnectionProxy.class.getClassLoader(),
              new Class<?>[] {ConnectionProxy.class},
              (proxy, method, arguments) -> {
                if (method.getName().equals("getTargetConnection")) {
                  return target;
                }
                if (method.getName().equals("commit")) {
                  commits.incrementAndGet();
                  if (armed.compareAndSet(true, false)) {
                    beforeFaultCommit.run();
                    relay.discardReplies.set(true);
                    entered.countDown();
                  }
                }
                try {
                  return method.invoke(target, arguments);
                } catch (InvocationTargetException failure) {
                  if (method.getName().equals("commit")) {
                    commitFailure.complete(failure.getCause());
                  }
                  throw failure.getCause();
                }
              });
    }
  }

  /** Loopback-only byte relay: no protocol parsing, TLS termination or injected JDBC exception. */
  private static final class ReplyBlackhole implements AutoCloseable {
    final AtomicBoolean discardReplies = new AtomicBoolean();
    final AtomicLong droppedBytes = new AtomicLong();
    final AtomicInteger sessions = new AtomicInteger();
    final ConcurrentLinkedQueue<IOException> failures = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ServerSocket listener = new ServerSocket();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final Future<?> acceptor;

    ReplyBlackhole(String host, int port) throws IOException {
      listener.bind(new InetSocketAddress("127.0.0.1", 0));
      acceptor =
          threads.submit(
              () -> {
                try {
                  while (!closing.get()) {
                    Socket client = listener.accept();
                    sockets.add(client);
                    Socket server = new Socket();
                    sockets.add(server);
                    server.connect(new InetSocketAddress(host, port), 2000);
                    sessions.incrementAndGet();
                    threads.submit(() -> copy(client, server, false));
                    threads.submit(() -> copy(server, client, true));
                  }
                } catch (IOException failure) {
                  if (!closing.get()) {
                    failures.add(failure);
                  }
                }
              });
    }

    int port() {
      return listener.getLocalPort();
    }

    private void copy(Socket source, Socket target, boolean replies) {
      try {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = source.getInputStream().read(buffer)) != -1) {
          if (replies && discardReplies.get()) {
            droppedBytes.addAndGet(count);
          } else {
            target.getOutputStream().write(buffer, 0, count);
          }
        }
      } catch (SocketException expectedPeerClose) {
        // JDBC timeout and pool eviction close a physical connection during the test.
      } catch (IOException failure) {
        if (!closing.get()) {
          failures.add(failure);
        }
      } finally {
        closeSocket(source);
        closeSocket(target);
      }
    }

    private void closeSocket(Socket socket) {
      try {
        socket.close();
      } catch (IOException failure) {
        failures.add(failure);
      } finally {
        sockets.remove(socket);
      }
    }

    @Override
    public void close() throws Exception {
      closing.set(true);
      listener.close();
      acceptor.get(5, TimeUnit.SECONDS);
      threads.shutdownNow();
      sockets.forEach(this::closeSocket);
      assertTrue("relay threads exit", threads.awaitTermination(5, TimeUnit.SECONDS));
      assertThat(sockets).isEmpty();
      assertThat(failures).isEmpty();
    }
  }
}
