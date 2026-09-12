package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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

  private void assertLostCommitReply(boolean completion) throws Exception {
    assumeTrue(Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
    try (JdbcDatabaseContainer<?> database = database()) {
      database.start();
      try (Connection connection = database.createConnection("")) {
        ScriptUtils.executeSqlScript(
            connection,
            new ClassPathResource(
                dialect == AsyncJobQueueJdbcDialect.MYSQL
                    ? "db/migration/V109__Async_Job_Queue.sql"
                    : "db/postgresql/migration/V109__Async_Job_Queue.sql"));
      }
      DataSource direct =
          new DriverManagerDataSource(
              database.getJdbcUrl(), database.getUsername(), database.getPassword());
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
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    CountDownLatch pause = new CountDownLatch(1);
    while (!condition.getAsBoolean()) {
      assertTrue("independent connection observes the server commit", System.nanoTime() < deadline);
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
    private final ReplyBlackhole relay;

    CommitGate(DataSource target, ReplyBlackhole relay) {
      super(target);
      this.relay = relay;
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
                    relay.discardReplies.set(true);
                    entered.countDown();
                  }
                }
                try {
                  return method.invoke(target, arguments);
                } catch (InvocationTargetException failure) {
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
