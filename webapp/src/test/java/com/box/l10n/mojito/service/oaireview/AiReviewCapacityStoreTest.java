package com.box.l10n.mojito.service.oaireview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.database.MBlobRepository;
import com.box.l10n.mojito.service.oaireview.AiReviewCapacityStore.Admission;
import com.box.l10n.mojito.service.oaireview.AiReviewExecutionStore.Capacity;
import com.box.l10n.mojito.service.oaireview.AiReviewExecutionStore.Reservation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Real conditional SQL against an isolated database, with deterministic competing writes. */
public class AiReviewCapacityStoreTest {
  private static final Instant NOW = Instant.parse("2026-09-11T19:00:00Z");
  private static final Instant DEADLINE = NOW.plusSeconds(180);
  private final ObjectMapper mapper = ObjectMapper.withNoFailOnUnknownProperties();
  private final MBlobRepository blobs = mock(MBlobRepository.class);
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final AiReviewExecutionProperties configuration = new AiReviewExecutionProperties();
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager transactions;
  private AiReviewCapacityStore store;
  private Logger capacityLogger;
  private Level previousLogLevel;
  private ListAppender<ILoggingEvent> logs;

  @Before
  public void setup() {
    JDBCDataSource source = new JDBCDataSource();
    source.setUrl("jdbc:hsqldb:mem:review_capacity_" + UUID.randomUUID());
    source.setUser("SA");
    source.setPassword("");
    jdbc = new JdbcTemplate(source);
    jdbc.execute("SET DATABASE TRANSACTION CONTROL MVCC");
    jdbc.execute(
        "create table mblob (id bigint primary key, name varchar(255) unique, content blob not null)");
    jdbc.update(
        "insert into mblob (id, name, content) values (1, ?, ?)",
        AiReviewExecutionStore.CAPACITY_NAME,
        bytes(new Capacity(Map.of())));
    when(blobs.findIdByName(AiReviewExecutionStore.CAPACITY_NAME)).thenReturn(Optional.of(1L));
    transactions = new DataSourceTransactionManager(source);
    store = capacityStore(jdbc);
    capacityLogger = (Logger) LoggerFactory.getLogger(AiReviewCapacityStore.class);
    previousLogLevel = capacityLogger.getLevel();
    capacityLogger.setLevel(Level.WARN);
    logs = new ListAppender<>();
    logs.start();
    capacityLogger.addAppender(logs);
  }

  @After
  public void close() {
    capacityLogger.detachAppender(logs);
    capacityLogger.setLevel(previousLogLevel);
    logs.stop();
    jdbc.execute("SHUTDOWN");
    metrics.close();
  }

  @Test
  public void userCountsAreSharedAcrossInstancesAndReleasedIndependently() {
    configuration.setMaxInFlightPerUser(1);
    assertEquals(Admission.RESERVED, store.reserve("first", 1L, DEADLINE, NOW));
    AiReviewCapacityStore other = capacityStore(jdbc);
    assertEquals(Admission.USER_LIMIT, other.reserve("second", 1L, DEADLINE, NOW));
    assertEquals(Admission.RESERVED, other.reserve("third", 2L, DEADLINE, NOW));
    assertTrue(other.release("first", NOW));
    assertTrue(other.release("first", NOW));
    assertEquals(Admission.RESERVED, store.reserve("second", 1L, DEADLINE, NOW));
    assertEquals(2, capacity().reservations().size());
  }

  @Test
  public void globalThresholdAllowsMoreReservationsAndExpiredReservationsArePruned() {
    configuration.setMaxInFlight(1);
    assertEquals(Admission.RESERVED, store.reserve("first", 1L, DEADLINE, NOW));
    assertEquals(Admission.RESERVED, store.reserve("second", 2L, DEADLINE, NOW));
    assertEquals(2, capacity().reservations().size());
    assertEquals(1, admissionCount("global_threshold"), 0);
    assertEquals(
        Admission.RESERVED, store.reserve("second", 2L, DEADLINE.plusSeconds(180), DEADLINE));
    assertEquals(
        Map.of("second", new Reservation(2L, DEADLINE.plusSeconds(180))),
        capacity().reservations());
  }

  @Test
  public void threeConflictsBelowCapacityAdmitBestEffortWithoutLosingOtherReservations() {
    assertEquals(Admission.RESERVED, store.reserve("first", 1L, DEADLINE, NOW));
    AiReviewCapacityStore contended = spy(store);
    doReturn(0).when(contended).compareAndSet(any(), any());
    assertEquals(Admission.BEST_EFFORT, contended.reserve("second", 2L, DEADLINE, NOW));
    verify(contended, times(3)).compareAndSet(any(), any());
    assertEquals(Map.of("first", new Reservation(1L, DEADLINE)), capacity().reservations());
    assertEquals(3, admissionCount("optimistic_conflict"), 0);
    assertEquals(1, admissionCount("best_effort"), 0);
  }

  @Test
  public void aFreshSnapshotAtGlobalThresholdAfterConflictStillReserves() {
    configuration.setMaxInFlight(1);
    AiReviewCapacityStore contended = spy(store);
    AtomicInteger attempts = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (attempts.getAndIncrement() == 0) {
                // Commit another client's write outside the suspended admission transaction.
                capacityStore(jdbc).reserve("other", 2L, DEADLINE, NOW);
                return 0;
              }
              return invocation.callRealMethod();
            })
        .when(contended)
        .compareAndSet(any(), any());
    assertEquals(Admission.RESERVED, contended.reserve("mine", 1L, DEADLINE, NOW));
    verify(contended, times(2)).compareAndSet(any(), any());
    assertEquals(2, capacity().reservations().size());
    assertEquals(1, admissionCount("global_threshold"), 0);
    assertEquals(0, admissionCount("best_effort"), 0);
  }

  @Test
  public void aFreshUserLimitAfterConflictStillRejectsBeforeTheGlobalWarning() {
    configuration.setMaxInFlight(1);
    configuration.setMaxInFlightPerUser(1);
    AiReviewCapacityStore contended = spy(store);
    doAnswer(
            invocation -> {
              capacityStore(jdbc).reserve("other", 1L, DEADLINE, NOW);
              return 0;
            })
        .when(contended)
        .compareAndSet(any(), any());
    assertEquals(Admission.USER_LIMIT, contended.reserve("mine", 1L, DEADLINE, NOW));
    verify(contended, times(1)).compareAndSet(any(), any());
    assertEquals(1, admissionCount("user_limit"), 0);
    assertEquals(0, admissionCount("global_threshold"), 0);
    assertEquals(0, admissionCount("best_effort"), 0);
    assertTrue(thresholdWarnings().isEmpty());
  }

  @Test
  public void globalThresholdWarnsWithoutRejectingUsersWhoHaveNoActiveRequests() {
    configuration.setMaxInFlight(1);
    configuration.setMaxInFlightPerUser(6);
    assertEquals(Admission.RESERVED, store.reserve("first", 1L, DEADLINE, NOW));
    assertTrue(thresholdWarnings().isEmpty());
    assertEquals(0, admissionCount("global_threshold"), 0);

    assertEquals(Admission.RESERVED, store.reserve("second", 2L, DEADLINE, NOW));
    assertEquals(Admission.RESERVED, store.reserve("third", 3L, DEADLINE, NOW));
    List<ILoggingEvent> warnings = thresholdWarnings();
    assertEquals(2, warnings.size());
    assertEquals(
        "AI review global capacity threshold reached: userId=2, globalInFlight=1, globalThreshold=1, userInFlight=0, userLimit=6",
        warnings.get(0).getFormattedMessage());
    assertEquals(
        "AI review global capacity threshold reached: userId=3, globalInFlight=2, globalThreshold=1, userInFlight=0, userLimit=6",
        warnings.get(1).getFormattedMessage());
    assertEquals(3, capacity().reservations().size());
    assertEquals(2, admissionCount("global_threshold"), 0);
  }

  @Test
  public void threeConflictsAtGlobalThresholdEmitOnlyOneWarningAndAdmitBestEffort() {
    configuration.setMaxInFlight(1);
    configuration.setMaxInFlightPerUser(6);
    assertEquals(Admission.RESERVED, store.reserve("first", 1L, DEADLINE, NOW));
    AiReviewCapacityStore contended = spy(store);
    doReturn(0).when(contended).compareAndSet(any(), any());

    assertEquals(Admission.BEST_EFFORT, contended.reserve("second", 2L, DEADLINE, NOW));
    verify(contended, times(3)).compareAndSet(any(), any());
    assertEquals(1, thresholdWarnings().size());
    assertEquals(
        "AI review global capacity threshold reached: userId=2, globalInFlight=1, globalThreshold=1, userInFlight=0, userLimit=6",
        thresholdWarnings().getFirst().getFormattedMessage());
    assertEquals(1, admissionCount("global_threshold"), 0);
    assertEquals(3, admissionCount("optimistic_conflict"), 0);
    assertEquals(1, admissionCount("best_effort"), 0);
    assertEquals(Map.of("first", new Reservation(1L, DEADLINE)), capacity().reservations());
  }

  @Test
  public void retryUsesFreshBytesAndPreservesACompetingReservation() {
    AiReviewCapacityStore contended = spy(store);
    AtomicInteger attempts = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (attempts.getAndIncrement() == 0) {
                capacityStore(jdbc).reserve("other", 2L, DEADLINE, NOW);
                return 0;
              }
              return invocation.callRealMethod();
            })
        .when(contended)
        .compareAndSet(any(), any());
    assertEquals(Admission.RESERVED, contended.reserve("mine", 1L, DEADLINE, NOW));
    assertEquals(2, capacity().reservations().size());
    assertTrue(capacity().reservations().containsKey("other"));
    assertTrue(capacity().reservations().containsKey("mine"));
  }

  @Test
  public void releaseConflictsLeaveAnExpiringReservationAndDoNotPretendItWasReleased() {
    store.reserve("first", 1L, DEADLINE, NOW);
    AiReviewCapacityStore contended = spy(store);
    doReturn(0).when(contended).compareAndSet(any(), any());
    assertFalse(contended.release("first", NOW));
    verify(contended, times(3)).compareAndSet(any(), any());
    assertEquals(DEADLINE, capacity().reservations().get("first").deadline());
    assertTrue(store.release("first", DEADLINE));
    assertTrue(capacity().reservations().isEmpty());
  }

  @Test
  public void malformedCapacityNeverAdmitsBestEffort() {
    jdbc.update(
        "update mblob set content = ? where id = 1", "invalid".getBytes(StandardCharsets.UTF_8));
    assertThrows(RuntimeException.class, () -> store.reserve("first", 1L, DEADLINE, NOW));
    assertEquals(0, admissionCount("best_effort"), 0);
  }

  @Test
  public void failedReadAfterAnEarlierUnderCapacityConflictNeverAdmitsBestEffort() {
    AiReviewCapacityStore contended = spy(store);
    doAnswer(
            invocation -> {
              jdbc.update("delete from mblob where id = 1");
              return 0;
            })
        .when(contended)
        .compareAndSet(any(), any());
    assertThrows(RuntimeException.class, () -> contended.reserve("mine", 1L, DEADLINE, NOW));
    assertEquals(0, admissionCount("best_effort"), 0);
  }

  @Test
  public void writeTimeoutsAreBoundedCollisionsButUnrelatedWriteFailuresAreNot() {
    JdbcTemplate failedWrites = spy(jdbc);
    doThrow(new QueryTimeoutException("bounded write timeout"))
        .when(failedWrites)
        .execute(
            any(PreparedStatementCreator.class),
            org.mockito.ArgumentMatchers.<PreparedStatementCallback<Integer>>any());
    assertEquals(
        Admission.BEST_EFFORT, capacityStore(failedWrites).reserve("first", 1L, DEADLINE, NOW));
    assertEquals(3, admissionCount("bounded_write_contention"), 0);

    doThrow(new DataAccessResourceFailureException("database disconnected"))
        .when(failedWrites)
        .execute(
            any(PreparedStatementCreator.class),
            org.mockito.ArgumentMatchers.<PreparedStatementCallback<Integer>>any());
    assertThrows(
        DataAccessResourceFailureException.class,
        () -> capacityStore(failedWrites).reserve("second", 2L, DEADLINE, NOW));
    assertEquals(1, admissionCount("best_effort"), 0);
  }

  private AiReviewCapacityStore capacityStore(JdbcTemplate template) {
    return new AiReviewCapacityStore(blobs, mapper, configuration, transactions, template, metrics);
  }

  private Capacity capacity() {
    byte[] content = jdbc.queryForObject("select content from mblob where id = 1", byte[].class);
    return mapper.readValueUnchecked(new String(content, StandardCharsets.UTF_8), Capacity.class);
  }

  private byte[] bytes(Capacity capacity) {
    return mapper.writeValueAsStringUnchecked(capacity).getBytes(StandardCharsets.UTF_8);
  }

  private double admissionCount(String reason) {
    return metrics.counter("AiReviewExecution.admission", "reason", reason).count();
  }

  private List<ILoggingEvent> thresholdWarnings() {
    return logs.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .filter(
            event -> event.getMessage().startsWith("AI review global capacity threshold reached:"))
        .toList();
  }
}
