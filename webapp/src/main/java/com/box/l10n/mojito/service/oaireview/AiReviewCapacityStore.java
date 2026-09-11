package com.box.l10n.mojito.service.oaireview;

import com.box.l10n.mojito.entity.MBlob;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.database.MBlobRepository;
import com.box.l10n.mojito.service.oaireview.AiReviewExecutionStore.Capacity;
import com.box.l10n.mojito.service.oaireview.AiReviewExecutionStore.Reservation;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.SQLTimeoutException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Approximate admission accounting, independent of the mandatory pollable-task lifecycle. Each
 * compare-and-set uses a fresh, short transaction. Contention may admit an uncounted request after
 * three snapshots below the user's allowance. Global occupancy is logged but never rejects work.
 */
@Service
public class AiReviewCapacityStore {
  private static final Logger logger = LoggerFactory.getLogger(AiReviewCapacityStore.class);
  private static final int ATTEMPTS = 3;
  private final MBlobRepository blobs;
  private final ObjectMapper mapper;
  private final AiReviewExecutionProperties configuration;
  private final JdbcTemplate jdbc;
  private final MeterRegistry metrics;
  private final TransactionTemplate transaction;
  private volatile boolean initialized;

  public AiReviewCapacityStore(
      MBlobRepository blobs,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper mapper,
      AiReviewExecutionProperties configuration,
      PlatformTransactionManager transactionManager,
      JdbcTemplate jdbc,
      MeterRegistry metrics) {
    this.blobs = blobs;
    this.mapper = mapper;
    this.configuration = configuration;
    this.jdbc = jdbc;
    this.metrics = metrics;
    transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setTimeout(2);
  }

  public enum Admission {
    RESERVED,
    USER_LIMIT,
    BEST_EFFORT
  }

  public Admission reserve(String token, Long userId, Instant deadline, Instant now) {
    Objects.requireNonNull(token);
    Objects.requireNonNull(deadline);
    Objects.requireNonNull(now);
    initializeCapacity();
    AtomicBoolean globalThresholdReported = new AtomicBoolean();
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      Admission admission;
      try {
        admission =
            tx(
                () -> {
                  Snapshot snapshot = read();
                  Capacity capacity = capacity(snapshot);
                  capacity
                      .reservations()
                      .values()
                      .removeIf(reservation -> !reservation.deadline().isAfter(now));
                  if (capacity.reservations().containsKey(token)) return Admission.RESERVED;
                  long userCount =
                      capacity.reservations().values().stream()
                          .filter(reservation -> Objects.equals(userId, reservation.userId()))
                          .count();
                  if (userCount >= configuration.getMaxInFlightPerUser())
                    return Admission.USER_LIMIT;
                  if (capacity.reservations().size() >= configuration.getMaxInFlight()
                      && globalThresholdReported.compareAndSet(false, true)) {
                    // Global occupancy is diagnostic only. Other users' work must not prevent
                    // this user from starting a review within their own allowance.
                    metric("global_threshold");
                    logger.warn(
                        "AI review global capacity threshold reached: userId={}, globalInFlight={}, globalThreshold={}, userInFlight={}, userLimit={}",
                        userId,
                        capacity.reservations().size(),
                        configuration.getMaxInFlight(),
                        userCount,
                        configuration.getMaxInFlightPerUser());
                  }
                  capacity.reservations().put(token, new Reservation(userId, deadline));
                  return compareAndSet(snapshot, bytes(capacity)) == 1 ? Admission.RESERVED : null;
                });
      } catch (WriteContention exception) {
        admission = null;
        metric("bounded_write_contention");
      }
      if (admission != null) {
        metric(admission.name().toLowerCase(java.util.Locale.ROOT));
        return admission;
      }
      metric("optimistic_conflict");
    }
    // Each attempt read valid counts below the user limit. Only accounting is relaxed here; the
    // caller must still durably claim and fence the task before starting the provider request.
    metric("best_effort");
    return Admission.BEST_EFFORT;
  }

  /** A failed accounting update must never roll back a completed review's canonical result. */
  public boolean release(String token, Instant now) {
    Objects.requireNonNull(token);
    Objects.requireNonNull(now);
    initializeCapacity();
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      try {
        if (tx(
            () -> {
              Snapshot snapshot = read();
              Capacity capacity = capacity(snapshot);
              boolean changed =
                  capacity
                      .reservations()
                      .values()
                      .removeIf(reservation -> !reservation.deadline().isAfter(now));
              changed |= capacity.reservations().remove(token) != null;
              if (!changed) return true;
              return compareAndSet(snapshot, bytes(capacity)) == 1;
            })) return true;
      } catch (WriteContention exception) {
        metrics
            .counter("AiReviewExecution.capacityRelease", "reason", "bounded_write_contention")
            .increment();
      }
      metrics
          .counter("AiReviewExecution.capacityRelease", "reason", "optimistic_conflict")
          .increment();
    }
    metrics.counter("AiReviewExecution.capacityRelease", "reason", "deferred").increment();
    return false;
  }

  private synchronized void initializeCapacity() {
    if (initialized) return;
    try {
      tx(
          () -> {
            if (blobs.findIdByName(AiReviewExecutionStore.CAPACITY_NAME).isEmpty()) {
              MBlob row = new MBlob();
              row.setName(AiReviewExecutionStore.CAPACITY_NAME);
              row.setContent(bytes(new Capacity(new HashMap<>())));
              blobs.saveAndFlush(row);
            }
            return null;
          });
    } catch (DataIntegrityViolationException race) {
      if (tx(() -> blobs.findIdByName(AiReviewExecutionStore.CAPACITY_NAME).isEmpty())) throw race;
    }
    initialized = true;
  }

  private Snapshot read() {
    // A scalar JDBC read bypasses request-bound JPA entities and their potentially stale content.
    return jdbc.queryForObject(
        "select id, content from mblob where name = ?",
        (result, row) -> new Snapshot(result.getLong(1), result.getBytes(2)),
        AiReviewExecutionStore.CAPACITY_NAME);
  }

  int compareAndSet(Snapshot snapshot, byte[] replacement) {
    try {
      return jdbc.execute(
          (PreparedStatementCreator)
              connection ->
                  connection.prepareStatement(
                      "update mblob set content = ? where id = ? and name = ? and content = ?"),
          statement -> {
            // UPDATE still needs a database write lock. Bound that wait rather than claiming
            // optimistic concurrency makes the statement non-blocking.
            statement.setQueryTimeout(1);
            statement.setBytes(1, replacement);
            statement.setLong(2, snapshot.id());
            statement.setString(3, AiReviewExecutionStore.CAPACITY_NAME);
            statement.setBytes(4, snapshot.content());
            return statement.executeUpdate();
          });
    } catch (RuntimeException exception) {
      // Only a failed write is retryable. Read failures and malformed state never fail open.
      if (isWriteContention(exception)) throw new WriteContention(exception);
      throw exception;
    }
  }

  private Capacity capacity(Snapshot snapshot) {
    if (snapshot == null || snapshot.content() == null)
      throw new IllegalStateException("Missing review capacity");
    Capacity parsed =
        mapper.readValueUnchecked(
            new String(snapshot.content(), StandardCharsets.UTF_8), Capacity.class);
    if (parsed == null
        || parsed.reservations() == null
        || parsed.reservations().entrySet().stream()
            .anyMatch(
                entry ->
                    entry.getKey() == null
                        || entry.getValue() == null
                        || entry.getValue().deadline() == null))
      throw new IllegalStateException("Invalid review capacity state");
    return new Capacity(new HashMap<>(parsed.reservations()));
  }

  private byte[] bytes(Capacity capacity) {
    return mapper.writeValueAsStringUnchecked(capacity).getBytes(StandardCharsets.UTF_8);
  }

  private void metric(String reason) {
    metrics.counter("AiReviewExecution.admission", "reason", reason).increment();
  }

  private <T> T tx(Supplier<T> action) {
    return transaction.execute(status -> action.get());
  }

  private static boolean isWriteContention(Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof PessimisticLockingFailureException
          || cause instanceof QueryTimeoutException
          || cause instanceof SQLTimeoutException) return true;
    }
    return false;
  }

  record Snapshot(long id, byte[] content) {}

  private static class WriteContention extends RuntimeException {
    WriteContention(Throwable cause) {
      super(cause);
    }
  }
}
