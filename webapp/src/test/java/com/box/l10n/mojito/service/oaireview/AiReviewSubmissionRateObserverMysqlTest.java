package com.box.l10n.mojito.service.oaireview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.DBUtils;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.oaireview.AiReviewSubmissionRateObserver.Bucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

/** Cluster accounting and lock waits against MySQL, without modifying review tasks or user data. */
@TestPropertySource(properties = "l10n.org.quartz.scheduler.enabled=false")
public class AiReviewSubmissionRateObserverMysqlTest extends ServiceTestBase {
  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactions;
  @Autowired DBUtils dbUtils;
  // Cached test contexts share the database; never sweep another test's clock-controlled tasks.
  @MockitoBean AiReviewDispatchService backgroundDispatcher;
  private final ObjectMapper mapper = ObjectMapper.withNoFailOnUnknownProperties();
  private final AiReviewSubmissionRateProperties configuration =
      new AiReviewSubmissionRateProperties();
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final List<AiReviewSubmissionRateObserver> observers = new ArrayList<>();
  private final List<Long> observedUsers = new ArrayList<>();
  private JdbcTemplate jdbc;

  @Before
  public void setup() {
    assumeTrue("Requires MySQL transaction and unique-key behavior", dbUtils.isMysql());
    jdbc = new JdbcTemplate(dataSource);
  }

  @After
  public void cleanupObservations() {
    observers.forEach(AiReviewSubmissionRateObserver::close);
    if (jdbc != null) {
      observedUsers.forEach(
          userId ->
              jdbc.update(
                  "delete from mblob where name = ?",
                  AiReviewSubmissionRateObserver.PREFIX + userId));
    }
    metrics.close();
  }

  @Test
  public void differentInstancesShareAllowanceAndUsersRemainIndependent() {
    long firstUser = uniqueUserId();
    long secondUser = uniqueUserId();
    Instant now = Instant.now();
    AiReviewSubmissionRateObserver first = observer();
    AiReviewSubmissionRateObserver second = observer();
    for (int i = 0; i < 5; i++) {
      first.observeAt(firstUser, now, 0);
      second.observeAt(firstUser, now, 0);
    }
    second.observeAt(firstUser, now, 0);
    first.observeAt(secondUser, now, 0);
    assertEquals(0, bucket(firstUser).tokens(), 0);
    assertEquals(9, bucket(secondUser).tokens(), 0);
    assertEquals(11, count("within_allowance"), 0);
    assertEquals(1, count("excess"), 0);
  }

  @Test
  public void concurrentColdStartsKeepBothSubmissionsAndOnlyOneUserRow() throws Exception {
    AiReviewSubmissionRateObserver first = observer();
    AiReviewSubmissionRateObserver second = observer();
    ExecutorService callers = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 5; round++) {
        long userId = uniqueUserId();
        Instant now = Instant.now();
        CyclicBarrier startTogether = new CyclicBarrier(2);
        var firstCall =
            callers.submit(
                () -> {
                  startTogether.await(2, TimeUnit.SECONDS);
                  first.observeAt(userId, now, 0);
                  return null;
                });
        var secondCall =
            callers.submit(
                () -> {
                  startTogether.await(2, TimeUnit.SECONDS);
                  second.observeAt(userId, now, 0);
                  return null;
                });
        firstCall.get(4, TimeUnit.SECONDS);
        secondCall.get(4, TimeUnit.SECONDS);
        assertEquals(8, bucket(userId).tokens(), 0);
        assertEquals(
            Integer.valueOf(1),
            jdbc.queryForObject(
                "select count(*) from mblob where name = ?",
                Integer.class,
                AiReviewSubmissionRateObserver.PREFIX + userId));
      }
      assertEquals(10, count("within_allowance"), 0);
      assertEquals(0, count("unavailable"), 0);
    } finally {
      callers.shutdownNow();
    }
  }

  @Test
  public void heldMysqlRowLockCannotMakeSubmissionWaitForObservation() throws Exception {
    long userId = uniqueUserId();
    observer().observeAt(userId, Instant.now().minusMillis(10), 0);
    AiReviewSubmissionRateObserver async =
        new AiReviewSubmissionRateObserver(configuration, mapper, jdbc, transactions, metrics);
    observers.add(async);
    ExecutorService caller = Executors.newSingleThreadExecutor();
    try (Connection locker = dataSource.getConnection()) {
      locker.setAutoCommit(false);
      try (var statement =
          locker.prepareStatement("select id from mblob where name = ? for update")) {
        statement.setString(1, AiReviewSubmissionRateObserver.PREFIX + userId);
        try (var row = statement.executeQuery()) {
          assertTrue(row.next());
        }
      }
      try {
        caller.submit(() -> async.observe(userId)).get(1, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (count("unavailable") == 0 && System.nanoTime() < deadline) {
          TimeUnit.MILLISECONDS.sleep(10);
        }
        // Observation times out while the lock is still held; the HTTP caller already returned.
        assertEquals(1, count("unavailable"), 0);
        assertEquals(9, bucket(userId).tokens(), 0);
      } finally {
        locker.rollback();
        async.close();
      }
    } finally {
      caller.shutdownNow();
    }
  }

  private long uniqueUserId() {
    long id = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
    observedUsers.add(id);
    return id;
  }

  private AiReviewSubmissionRateObserver observer() {
    AiReviewSubmissionRateObserver result =
        new AiReviewSubmissionRateObserver(
            configuration,
            mapper,
            jdbc,
            transactions,
            metrics,
            Clock.fixed(Instant.now(), ZoneOffset.UTC),
            mock(ExecutorService.class));
    observers.add(result);
    return result;
  }

  private Bucket bucket(long userId) {
    byte[] content =
        jdbc.queryForObject(
            "select content from mblob where name = ?",
            byte[].class,
            AiReviewSubmissionRateObserver.PREFIX + userId);
    return mapper.readValueUnchecked(new String(content, StandardCharsets.UTF_8), Bucket.class);
  }

  private double count(String outcome) {
    var counter = metrics.find("AiReviewSubmission.observation").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }
}
