package com.box.l10n.mojito.queue;

import static com.box.l10n.mojito.queue.AsyncJobQueueValidation.DATABASE_TIMESTAMP_MAX;
import static com.box.l10n.mojito.queue.AsyncJobQueueValidation.DATABASE_TIMESTAMP_MIN;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.junit.runners.Parameterized;
import org.postgresql.PGConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** Disposable V113 queue schema only; each connection has an explicit timezone, never the JVM. */
@RunWith(Parameterized.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class JdbcAsyncJobStoreTimezoneIntegrationTest {

  private static final Map<AsyncJobQueueJdbcDialect, JdbcDatabaseContainer<?>> CONTAINERS =
      new EnumMap<>(AsyncJobQueueJdbcDialect.class);

  @Parameterized.Parameters(name = "{0}, serverPrepared={1}")
  public static List<Object[]> databases() {
    return List.of(
        new Object[] {AsyncJobQueueJdbcDialect.MYSQL, false},
        new Object[] {AsyncJobQueueJdbcDialect.MYSQL, true},
        new Object[] {AsyncJobQueueJdbcDialect.POSTGRESQL, false},
        new Object[] {AsyncJobQueueJdbcDialect.POSTGRESQL, true});
  }

  @BeforeClass
  public static void openDatabases() throws Exception {
    assumeTrue(Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
    try {
      CONTAINERS.put(
          AsyncJobQueueJdbcDialect.MYSQL,
          new MySQLContainer<>("mysql:8.4")
              .withConnectTimeoutSeconds(10)
              .withUrlParam("connectTimeout", "5000")
              .withUrlParam("socketTimeout", "30000"));
      CONTAINERS.put(
          AsyncJobQueueJdbcDialect.POSTGRESQL,
          new PostgreSQLContainer<>("postgres:16")
              .withConnectTimeoutSeconds(10)
              .withUrlParam("connectTimeout", "5")
              .withUrlParam("socketTimeout", "30"));
      for (var entry : CONTAINERS.entrySet()) {
        JdbcDatabaseContainer<?> container = entry.getValue();
        container.start();
        String migration =
            entry.getKey() == AsyncJobQueueJdbcDialect.MYSQL
                ? "db/migration/V113__Async_Job_Queue.sql"
                : "db/postgresql/migration/V113__Async_Job_Queue.sql";
        try (Connection connection = container.createConnection("")) {
          ScriptUtils.executeSqlScript(connection, new ClassPathResource(migration));
        }
      }
    } catch (Exception | Error failure) {
      try {
        closeDatabases();
      } catch (RuntimeException | Error cleanupFailure) {
        if (cleanupFailure != failure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      throw failure;
    }
  }

  @AfterClass
  public static void closeDatabases() {
    Throwable cleanupFailure = null;
    try {
      for (JdbcDatabaseContainer<?> container : CONTAINERS.values()) {
        try {
          container.close();
        } catch (RuntimeException | Error failure) {
          if (cleanupFailure == null) {
            cleanupFailure = failure;
          } else if (cleanupFailure != failure) {
            cleanupFailure.addSuppressed(failure);
          }
        }
      }
    } finally {
      CONTAINERS.clear();
    }
    if (cleanupFailure instanceof RuntimeException failure) {
      throw failure;
    }
    if (cleanupFailure instanceof Error failure) {
      throw failure;
    }
  }

  @Rule public ErrorCollector errors = new ErrorCollector();

  private final AsyncJobQueueJdbcDialect dialect;
  private final boolean serverPrepared;
  private List<Peer> peers;

  public JdbcAsyncJobStoreTimezoneIntegrationTest(
      AsyncJobQueueJdbcDialect dialect, boolean serverPrepared) {
    this.dialect = dialect;
    this.serverPrepared = serverPrepared;
  }

  @Before
  public void configureConnectionLocalTimezones() {
    peers =
        List.of(
            peer("UTC", "+00:00", "+00:00", "UTC"),
            peer("Tokyo", "+09:00", "+09:00", "Asia/Tokyo"),
            peer("session-only", "+00:00", "-07:00", "America/Los_Angeles"),
            peer("LOCAL-driver", "LOCAL", "+00:00", "UTC"));
  }

  @Test
  public void absoluteSchedulingAndRowTimestampsSurviveCrossSessionReadback() {
    for (int index = 0; index < peers.size(); index++) {
      Peer writer = peers.get(index);
      Peer reader = crossSessionPeer(index);
      String queue = queueName();
      Instant before = databaseInstant();
      Instant future = before.plusSeconds(3600);
      Instant past = before.minusSeconds(60);
      AsyncJobId futureId = writer.store().enqueue(queue, "{}", future);
      AsyncJobId pastId = writer.store().enqueue(queue, "{}", past);
      Instant after = databaseInstant();
      AsyncJobRecord row = record(reader, futureId);
      String context = writer.name() + "->" + reader.name();
      trace(
          context
              + " db="
              + before
              + " due="
              + future
              + " readDue="
              + row.availableAt()
              + " created="
              + row.createdDate()
              + " updated="
              + row.updatedDate());

      errors.checkThat(context + " absolute due time", row.availableAt(), is(future));
      errors.checkThat(
          context + " producer round trip", record(writer, futureId).availableAt(), is(future));
      checkWindow(context + " createdDate", row.createdDate(), before, after);
      checkWindow(context + " updatedDate", row.updatedDate(), before, after);
      errors.checkThat(context + " past due time", record(reader, pastId).availableAt(), is(past));
      AsyncJobReadyStatus ready = reader.store().readyStatus(queue);
      errors.checkThat(context + " only the past job is ready", ready.count(), is(1L));
      errors.checkThat(context + " oldest due time", ready.oldestAvailableAt(), is(past));
      List<AsyncJobId> claimed =
          reader.store().claimNextJobs(queue, 10, "worker", Duration.ofMinutes(10)).stream()
              .map(AsyncJobRecord::id)
              .toList();
      trace(
          context
              + " ready="
              + ready.count()
              + " claimedPast="
              + claimed.contains(pastId)
              + " claimedFuture="
              + claimed.contains(futureId));
      errors.checkThat(context + " future job must not be claimed", claimed, is(List.of(pastId)));
    }
  }

  @Test
  public void databaseClockIsAnAbsoluteInstantRegardlessOfConnectionAndSessionTimezone() {
    for (Peer peer : peers) {
      Instant before = databaseInstant();
      Instant observed = peer.store().readyStatus(queueName()).observedAt();
      Instant after = databaseInstant();
      trace(
          peer.name()
              + " db="
              + before
              + " observed="
              + observed
              + " shiftSeconds="
              + Duration.between(before, observed).getSeconds());
      checkWindow(peer.name() + " database clock", observed, before, after);
    }
  }

  @Test
  public void boundaryAndDstInstantsRetainTheirUtcSqlDates() {
    Peer utc = peers.get(0);
    DateTimeFormatter sqlFormat =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    String sql =
        dialect == AsyncJobQueueJdbcDialect.MYSQL
            ? "SELECT DATE_FORMAT(available_at, '%Y-%m-%dT%H:%i:%s.%f')"
                + " FROM async_job_queue WHERE id = ?"
            : "SELECT to_char(available_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US')"
                + " FROM async_job_queue WHERE id = ?";
    for (Instant expected :
        List.of(
            DATABASE_TIMESTAMP_MIN,
            Instant.parse("2026-03-08T09:59:59.123456Z"),
            Instant.parse("2026-03-08T10:00:00Z"),
            Instant.parse("2026-11-01T08:30:00Z"),
            Instant.parse("2026-11-01T09:30:00Z"),
            DATABASE_TIMESTAMP_MAX)) {
      errors.checkSucceeds(
          () -> {
            AsyncJobId id = utc.store().enqueue(queueName(), "{}", expected);
            String stored =
                utc.jdbc().queryForObject(sql, String.class, Long.parseLong(id.value()));
            Instant read = record(utc, id).availableAt();
            trace("boundary/DST expected=" + expected + " storedUtc=" + stored + " read=" + read);
            errors.checkThat(
                "UTC SQL civil date for " + expected, stored, is(sqlFormat.format(expected)));
            errors.checkThat("Instant readback for " + expected, read, is(expected));
            return null;
          });
    }
  }

  @Test
  public void outOfRangeInstantsAreRejectedWithoutInsertingRows() {
    for (Peer peer : peers) {
      for (Instant invalid :
          List.of(DATABASE_TIMESTAMP_MIN.minusNanos(1), DATABASE_TIMESTAMP_MAX.plusNanos(1))) {
        String queue = queueName();
        errors.checkSucceeds(
            () -> {
              assertThrows(
                  peer.name() + " rejects " + invalid,
                  IllegalArgumentException.class,
                  () -> peer.store().enqueue(queue, "{}", invalid));
              return null;
            });
        errors.checkThat(
            peer.name() + " rejected enqueue must not insert a row for " + invalid,
            peers
                .get(0)
                .jdbc()
                .queryForObject(
                    "SELECT COUNT(*) FROM async_job_queue WHERE queue_name = ?", Long.class, queue),
            is(0L));
      }
    }
  }

  @Test
  public void leaseTimestampsAndHeartbeatsRetainAbsoluteMeaningAcrossSessions() {
    for (int index = 0; index < peers.size(); index++) {
      Peer owner = peers.get(index);
      Peer observer = crossSessionPeer(index);
      String queue = queueName();
      AsyncJobId id = owner.store().enqueue(queue, "{}", Instant.EPOCH);
      Duration lease = Duration.ofMinutes(10);
      Instant before = databaseInstant();
      AsyncJobRecord claimed = owner.store().claimNextJobs(queue, 1, "worker", lease).get(0);
      Instant after = databaseInstant();
      AsyncJobRecord viewed = record(observer, id);
      String context = owner.name() + "->" + observer.name();
      trace(
          context
              + " db="
              + before
              + " ownerLease="
              + claimed.leaseUntil()
              + " peerLease="
              + viewed.leaseUntil());

      checkWindow(
          context + " lease expiration",
          viewed.leaseUntil(),
          before.plus(lease),
          after.plus(lease));
      errors.checkThat(
          context + " consistent lease readback", viewed.leaseUntil(), is(claimed.leaseUntil()));
      checkWindow(context + " claim updatedDate", viewed.updatedDate(), before, after);
      errors.checkThat(
          context + " lease is still live",
          observer.store().expiredLeaseStatus(queue).count(),
          is(0L));

      Duration renewedLease = Duration.ofMinutes(20);
      Instant renewalBefore = databaseInstant();
      boolean renewed =
          observer.store().heartbeat(queue, id, "worker", claimed.leaseToken(), renewedLease);
      Instant renewalAfter = databaseInstant();
      errors.checkThat(context + " heartbeat on another session", renewed, is(true));
      if (renewed) {
        AsyncJobRecord renewedRow = record(owner, id);
        trace(context + " renewalDb=" + renewalBefore + " renewedLease=" + renewedRow.leaseUntil());
        checkWindow(
            context + " renewed lease",
            renewedRow.leaseUntil(),
            renewalBefore.plus(renewedLease),
            renewalAfter.plus(renewedLease));
        checkWindow(
            context + " heartbeat updatedDate",
            renewedRow.updatedDate(),
            renewalBefore,
            renewalAfter);
      }
      errors.checkThat(
          context + " completing a live lease on another session",
          observer.store().markDone(queue, id, "worker", claimed.leaseToken(), null),
          is(true));
    }
  }

  @Test
  public void liveLeaseCannotBeReclaimedByAWorkerInAnotherSessionTimezone() {
    for (int index = 0; index < peers.size(); index++) {
      Peer owner = peers.get(index);
      Peer contender = crossSessionPeer(index);
      String queue = queueName();
      owner.store().enqueue(queue, "{}", Instant.EPOCH);
      AsyncJobRecord claimed =
          owner.store().claimNextJobs(queue, 1, "owner", Duration.ofMinutes(10)).get(0);
      List<AsyncJobRecord> reclaimed =
          contender.store().claimNextJobs(queue, 1, "contender", Duration.ofMinutes(10));
      trace(
          owner.name()
              + "->"
              + contender.name()
              + " db="
              + databaseInstant()
              + " ownerLease="
              + claimed.leaseUntil()
              + " prematureReclaims="
              + reclaimed.size());
      errors.checkThat("Live lease must not be stolen across sessions", reclaimed.size(), is(0));
    }
  }

  @Test
  public void lifecycleSchedulingAndTerminalCutoffsRemainAbsoluteAcrossSessions() {
    for (int index = 0; index < peers.size(); index++) {
      Peer owner = peers.get(index);
      Peer operator = crossSessionPeer(index);
      String context = owner.name() + "->" + operator.name();
      Duration delay = Duration.ofHours(1);
      for (String schedule : List.of("relative", "absolute", "failed")) {
        String queue = queueName();
        AsyncJobRecord claimed = enqueueAndClaim(owner, queue);
        AsyncJobId id = claimed.id();
        if (schedule.equals("failed")) {
          Instant before = databaseInstant();
          errors.checkThat(
              context + " fail before replay",
              operator.store().markFailed(queue, id, "worker", claimed.leaseToken(), null, "test"),
              is(true));
          checkMutation(context + " failed", owner, id, AsyncJobStatus.FAILED, before);
        }
        Instant before = databaseInstant();
        Instant future = before.plus(delay);
        boolean scheduled =
            switch (schedule) {
              case "relative" ->
                  operator
                      .store()
                      .requeueAfter(queue, id, "worker", claimed.leaseToken(), delay, null, null);
              case "absolute" ->
                  operator
                      .store()
                      .requeue(queue, id, "worker", claimed.leaseToken(), future, null, null);
              case "failed" -> operator.store().requeueFailed(queue, id, future, null);
              default -> throw new AssertionError(schedule);
            };
        Instant after = databaseInstant();
        errors.checkThat(context + " " + schedule + " scheduled", scheduled, is(true));
        AsyncJobRecord row =
            checkMutation(context + " " + schedule, owner, id, AsyncJobStatus.QUEUED, before);
        if (schedule.equals("relative")) {
          checkWindow(context + " relative due", row.availableAt(), future, after.plus(delay));
        } else {
          errors.checkThat(context + " " + schedule + " due", row.availableAt(), is(future));
        }
        errors.checkThat(
            context + " " + schedule + " future is not ready",
            owner.store().readyStatus(queue).count(),
            is(0L));
        errors.checkThat(
            context + " " + schedule + " future cannot be claimed",
            owner.store().claimNextJobs(queue, 1, "worker", Duration.ofMinutes(10)).isEmpty(),
            is(true));
        trace(
            context
                + " "
                + schedule
                + " db="
                + before
                + " due="
                + row.availableAt()
                + " updated="
                + row.updatedDate());
      }

      for (AsyncJobStatus terminal : List.of(AsyncJobStatus.FAILED, AsyncJobStatus.DONE)) {
        String queue = queueName();
        AsyncJobRecord claimed = enqueueAndClaim(owner, queue);
        AsyncJobId id = claimed.id();
        Instant before = databaseInstant();
        errors.checkThat(
            context + " terminal failure",
            operator.store().markFailed(queue, id, "worker", claimed.leaseToken(), null, "test"),
            is(true));
        AsyncJobRecord row =
            checkMutation(context + " terminal failure", owner, id, AsyncJobStatus.FAILED, before);
        if (terminal == AsyncJobStatus.DONE) {
          before = databaseInstant();
          Instant past = before.minusSeconds(60);
          errors.checkThat(
              context + " past scheduled replay",
              operator.store().requeueFailed(queue, id, past, null),
              is(true));
          row = checkMutation(context + " replay", owner, id, AsyncJobStatus.QUEUED, before);
          errors.checkThat(context + " replay retains exact due date", row.availableAt(), is(past));
          claimed = owner.store().claimNextJobs(queue, 1, "worker", Duration.ofMinutes(10)).get(0);
          errors.checkThat(context + " replay claims same job", claimed.id(), is(id));
          before = databaseInstant();
          errors.checkThat(
              context + " replay completion",
              operator.store().markDone(queue, id, "worker", claimed.leaseToken(), null),
              is(true));
          row = checkMutation(context + " done", owner, id, AsyncJobStatus.DONE, before);
        }
        // Use the other session's readback as the cutoff, without wall-clock rounding or sleeps.
        Instant cutoff = row.updatedDate();
        errors.checkThat(
            context + " " + terminal + " equal cutoff retains row",
            operator.store().deleteTerminalJobs(queue, terminal, cutoff, 10),
            is(0));
        errors.checkThat(
            context + " " + terminal + " row still exists",
            owner.store().getByIds(List.of(id)).size(),
            is(1));
        errors.checkThat(
            context + " " + terminal + " one microsecond later purges row",
            operator.store().deleteTerminalJobs(queue, terminal, cutoff.plusNanos(1000), 10),
            is(1));
        errors.checkThat(
            context + " " + terminal + " row is gone",
            owner.store().getByIds(List.of(id)).isEmpty(),
            is(true));
        trace(context + " terminal=" + terminal + " strictCutoff=" + cutoff);
      }
    }
  }

  private AsyncJobRecord enqueueAndClaim(Peer peer, String queue) {
    AsyncJobId id = peer.store().enqueue(queue, "{}", Instant.EPOCH);
    AsyncJobRecord claimed =
        peer.store().claimNextJobs(queue, 1, "worker", Duration.ofMinutes(10)).get(0);
    assertEquals(id, claimed.id());
    return claimed;
  }

  private AsyncJobRecord checkMutation(
      String context, Peer observer, AsyncJobId id, AsyncJobStatus status, Instant before) {
    Instant after = databaseInstant();
    AsyncJobRecord row = record(observer, id);
    errors.checkThat(context + " status", row.status(), is(status));
    checkWindow(context + " updatedDate", row.updatedDate(), before, after);
    return row;
  }

  private Peer peer(String name, String driverZone, String mysqlZone, String postgresZone) {
    JdbcDatabaseContainer<?> container = CONTAINERS.get(dialect);
    DriverManagerDataSource target =
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword());
    Properties properties = new Properties();
    if (dialect == AsyncJobQueueJdbcDialect.MYSQL) {
      properties.setProperty("connectionTimeZone", driverZone);
      properties.setProperty("forceConnectionTimeZoneToSession", "false");
      properties.setProperty("preserveInstants", "true");
      properties.setProperty("useServerPrepStmts", Boolean.toString(serverPrepared));
    } else {
      properties.setProperty("prepareThreshold", serverPrepared ? "-1" : "0");
    }
    target.setConnectionProperties(properties);
    String sessionSql =
        dialect == AsyncJobQueueJdbcDialect.MYSQL
            ? "SET SESSION time_zone = '" + mysqlZone + "'"
            : "SET TIME ZONE '" + postgresZone + "'";
    DataSource source = new SessionDataSource(target, sessionSql);
    JdbcTemplate jdbc = new JdbcTemplate(source);
    jdbc.setQueryTimeout(10);
    if (dialect == AsyncJobQueueJdbcDialect.POSTGRESQL) {
      Integer actualThreshold =
          jdbc.execute(
              (ConnectionCallback<Integer>)
                  connection -> connection.unwrap(PGConnection.class).getPrepareThreshold());
      assertEquals(
          "Fixture must configure PG preparation on the connection",
          Integer.valueOf(serverPrepared ? -1 : 0),
          actualThreshold);
    }
    String actualZone =
        jdbc.queryForObject(
            dialect == AsyncJobQueueJdbcDialect.MYSQL
                ? "SELECT @@session.time_zone"
                : "SHOW TIME ZONE",
            String.class);
    assertEquals(
        "Fixture must really set the session timezone",
        dialect == AsyncJobQueueJdbcDialect.MYSQL ? mysqlZone : postgresZone,
        actualZone);
    return new Peer(
        name,
        jdbc,
        new JdbcAsyncJobStore(
            new NamedParameterJdbcTemplate(jdbc),
            dialect,
            new DataSourceTransactionManager(source)));
  }

  private Instant databaseInstant() {
    // Numeric epoch values bypass the JDBC temporal conversion this suite is testing.
    String sql =
        dialect == AsyncJobQueueJdbcDialect.MYSQL
            ? "SELECT TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(6))"
            : "SELECT (EXTRACT(EPOCH FROM clock_timestamp()) * 1000000)::bigint";
    long micros = peers.get(0).jdbc().queryForObject(sql, Long.class);
    return Instant.ofEpochSecond(
        Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000);
  }

  private void checkWindow(String context, Instant actual, Instant before, Instant after) {
    errors.checkThat(
        context + " actual=" + actual + " databaseWindow=[" + before + "," + after + "]",
        actual != null
            && !actual.isBefore(before.minusMillis(1))
            && !actual.isAfter(after.plusMillis(1)),
        is(true));
  }

  private void trace(String details) {
    System.out.println(
        "TZ "
            + dialect
            + " serverPrepared="
            + serverPrepared
            + " jvm="
            + ZoneId.systemDefault()
            + " "
            + details);
  }

  private Peer crossSessionPeer(int writerIndex) {
    return peers.get(writerIndex == 0 ? 1 : 0);
  }

  private static AsyncJobRecord record(Peer peer, AsyncJobId id) {
    return peer.store().getByIds(List.of(id)).get(0);
  }

  private static String queueName() {
    return "tz-" + UUID.randomUUID();
  }

  private record Peer(String name, JdbcTemplate jdbc, JdbcAsyncJobStore store) {}

  private static final class SessionDataSource extends DelegatingDataSource {
    private final String sessionSql;

    private SessionDataSource(DataSource target, String sessionSql) {
      super(target);
      this.sessionSql = sessionSql;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return configure(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return configure(super.getConnection(username, password));
    }

    private Connection configure(Connection connection) throws SQLException {
      try (Statement statement = connection.createStatement()) {
        statement.setQueryTimeout(10);
        statement.execute(sessionSql);
        return connection;
      } catch (SQLException failure) {
        try {
          connection.close();
        } catch (SQLException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
        throw failure;
      }
    }
  }
}
