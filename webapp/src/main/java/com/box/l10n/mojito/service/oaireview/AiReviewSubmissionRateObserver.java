package com.box.l10n.mojito.service.oaireview;

import com.box.l10n.mojito.json.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Best-effort submission telemetry, never admission control. Each user has a separate database row;
 * slow model calls do not consume tokens. Accounting and its bounded waits run off the request
 * thread.
 */
@Service
public class AiReviewSubmissionRateObserver {
  static final String PREFIX = "ai_review_submission_rate/v1/user/";
  static final long MAX_DELAY_MILLIS = 1000;
  static final long WARNING_INTERVAL_MILLIS = 30000;
  private static final Logger logger =
      LoggerFactory.getLogger(AiReviewSubmissionRateObserver.class);
  private final AiReviewSubmissionRateProperties configuration;
  private final ObjectMapper mapper;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final MeterRegistry metrics;
  private final Clock clock;
  private final ExecutorService executor;
  private final AtomicLong lastUnavailableWarning = new AtomicLong();
  private final AtomicLong overloadedObservations = new AtomicLong();
  private final AtomicLong unavailableObservations = new AtomicLong();

  @Autowired
  public AiReviewSubmissionRateObserver(
      AiReviewSubmissionRateProperties configuration,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper mapper,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      MeterRegistry metrics) {
    this(configuration, mapper, jdbc, transactions, metrics, Clock.systemUTC(), newExecutor());
  }

  AiReviewSubmissionRateObserver(
      AiReviewSubmissionRateProperties configuration,
      ObjectMapper mapper,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      MeterRegistry metrics,
      Clock clock,
      ExecutorService executor) {
    this.configuration = configuration;
    this.mapper = mapper;
    // Do not change the shared JdbcTemplate's timeout or use review completion threads.
    this.jdbc = new JdbcTemplate(jdbc.getDataSource());
    this.jdbc.setQueryTimeout(1);
    transaction = new TransactionTemplate(transactions);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    // Spring can replace a statement timeout with the enclosing transaction's remaining time.
    transaction.setTimeout(1);
    this.metrics = metrics;
    this.clock = clock;
    this.executor = executor;
  }

  private static ExecutorService newExecutor() {
    return new ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(256),
        runnable -> {
          Thread thread = new Thread(runnable, "ai-review-submission-observer");
          thread.setDaemon(true);
          return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * Called once for a valid authenticated HTTP submission, including one later rejected as busy.
   */
  void observe(Long userId) {
    observe(userId, clock.instant(), System.nanoTime());
  }

  /** Preserve arrival time across request validation/database waits as well as observer waits. */
  public void observe(Long userId, Instant submittedAt, long submittedAtNanos) {
    if (!configuration.isEnabled()) return;
    try {
      if (userId == null || userId <= 0) {
        metric("missing_user");
        return;
      }
      executor.execute(
          () ->
              observeAt(
                  userId,
                  submittedAt,
                  TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - submittedAtNanos)));
    } catch (RejectedExecutionException overloaded) {
      overloadedObservations.incrementAndGet();
    } catch (RuntimeException unavailable) {
      unavailableObservations.incrementAndGet();
    }
  }

  void observeAt(long userId, Instant submittedAt, long delayMillis) {
    long startedAt = System.nanoTime();
    reportDroppedObservations();
    if (elapsedDelay(delayMillis, startedAt) > MAX_DELAY_MILLIS) {
      unavailable("delayed");
      return;
    }
    try {
      for (int attempt = 0; attempt < 3; attempt++) {
        if (elapsedDelay(delayMillis, startedAt) > MAX_DELAY_MILLIS) {
          unavailable("delayed");
          return;
        }
        Outcome outcome;
        try {
          outcome =
              transaction.execute(
                  status -> update(userId, submittedAt.toEpochMilli(), delayMillis, startedAt));
        } catch (DuplicateKeyException competingFirstSubmission) {
          outcome = Outcome.CONFLICT;
        }
        if (outcome == Outcome.CONFLICT) continue;
        if (outcome == Outcome.DELAYED) {
          unavailable("delayed");
          return;
        }
        metric(
            outcome == Outcome.WARNING
                ? "excess"
                : outcome.name().toLowerCase(java.util.Locale.ROOT));
        if (outcome == Outcome.WARNING) {
          // Only the successful commit owns this warning; other pods share the cooldown.
          logger.warn(
              "AI review submission burst observed: userId={}, requestsPerSecond={}, burstAllowance={}, observationDelayMs={}, action=observe_only",
              userId,
              configuration.getRequestsPerSecond(),
              configuration.getBurst(),
              elapsedDelay(delayMillis, startedAt));
        }
        return;
      }
      unavailable("contended");
    } catch (RuntimeException failure) {
      // No database, parser, or telemetry failure can change the review's outcome.
      unavailable("unavailable");
    }
  }

  private static long elapsedDelay(long initialDelay, long startedAt) {
    return initialDelay + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
  }

  private Outcome update(long userId, long submittedAt, long delayMillis, long startedAt) {
    String name = PREFIX + userId;
    var rows =
        jdbc.query(
            "select id, content from mblob where name = ?",
            (rs, row) -> new Snapshot(rs.getLong(1), rs.getBytes(2)),
            name);
    if (elapsedDelay(delayMillis, startedAt) > MAX_DELAY_MILLIS) return Outcome.DELAYED;
    Snapshot snapshot = rows.isEmpty() ? null : rows.getFirst();
    Bucket previous =
        snapshot == null
            ? new Bucket(configuration.getBurst(), submittedAt, 0)
            : read(snapshot.content());
    // Processing delays and cross-pod clock skew must not manufacture bursts. Skipping an older
    // observation can undercount; this is explicitly approximate telemetry, not an exact quota.
    if (submittedAt < previous.updatedAtMillis()) return Outcome.OUT_OF_ORDER;
    double tokens =
        Math.min(
            configuration.getBurst(),
            previous.tokens()
                + (submittedAt - previous.updatedAtMillis())
                    / 1000.0
                    * configuration.getRequestsPerSecond());
    boolean excess = tokens + 1e-9 < 1;
    boolean warn = excess && submittedAt - previous.lastWarningMillis() >= WARNING_INTERVAL_MILLIS;
    Bucket next =
        new Bucket(
            excess ? tokens : Math.max(0, tokens - 1),
            submittedAt,
            warn ? submittedAt : previous.lastWarningMillis());
    byte[] content = mapper.writeValueAsStringUnchecked(next).getBytes(StandardCharsets.UTF_8);
    if (snapshot == null) {
      jdbc.update(
          "insert into mblob (name, content, created_date) values (?, ?, CURRENT_TIMESTAMP)",
          name,
          content);
    } else if (jdbc.update(
            "update mblob set content = ? where id = ? and name = ? and content = ?",
            content,
            snapshot.id(),
            name,
            snapshot.content())
        != 1) {
      return Outcome.CONFLICT;
    }
    return warn ? Outcome.WARNING : excess ? Outcome.EXCESS : Outcome.WITHIN_ALLOWANCE;
  }

  private Bucket read(byte[] content) {
    Bucket bucket =
        mapper.readValueUnchecked(new String(content, StandardCharsets.UTF_8), Bucket.class);
    if (bucket == null
        || !Double.isFinite(bucket.tokens())
        || bucket.tokens() < 0
        || bucket.updatedAtMillis() < 0
        || bucket.lastWarningMillis() < 0)
      throw new IllegalStateException("Invalid submission observation state");
    return bucket;
  }

  private void metric(String outcome) {
    metric(outcome, 1);
  }

  private void metric(String outcome, long count) {
    try {
      metrics.counter("AiReviewSubmission.observation", "outcome", outcome).increment(count);
    } catch (RuntimeException ignored) {
      // Optional telemetry must not become a new availability dependency.
    }
  }

  private void unavailable(String reason) {
    metric(reason);
    warnIncomplete(reason);
  }

  private void reportDroppedObservations() {
    reportDropped("overloaded", overloadedObservations.getAndSet(0));
    reportDropped("unavailable", unavailableObservations.getAndSet(0));
  }

  private void reportDropped(String reason, long count) {
    if (count == 0) return;
    metric(reason, count);
    warnIncomplete(reason);
  }

  private void warnIncomplete(String reason) {
    long now = System.nanoTime();
    long previous = lastUnavailableWarning.get();
    if (previous != 0 && now - previous < TimeUnit.SECONDS.toNanos(30)) return;
    if (lastUnavailableWarning.compareAndSet(previous, now)) {
      try {
        logger.warn(
            "AI review submission observation incomplete: reason={}, action=observe_only", reason);
      } catch (RuntimeException ignored) {
        // Even a failed logger must not turn a full observation queue into a failed review.
      }
    }
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
    reportDroppedObservations();
  }

  record Bucket(double tokens, long updatedAtMillis, long lastWarningMillis) {}

  private record Snapshot(long id, byte[] content) {}

  private enum Outcome {
    WITHIN_ALLOWANCE,
    EXCESS,
    WARNING,
    OUT_OF_ORDER,
    CONFLICT,
    DELAYED
  }
}
