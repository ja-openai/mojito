package com.box.l10n.mojito.service.oaireview;

import com.box.l10n.mojito.entity.MBlob;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.database.MBlobRepository;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PessimisticLockException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mandatory, transactional review lifecycle. Uses the existing task JSON and a non-expiring,
 * uniquely named database blob; this state must never be routed to external blob storage.
 *
 * <p>Locks are always capacity then task. Admission uses NOWAIT and never waits for a provider
 * permit. A reservation survives process loss until the original overall deadline. An ambiguous
 * attempt is never sent again automatically.
 */
@Service
public class AiReviewExecutionStore {
  static final String CAPACITY_NAME = "ai_review_execution/v1/capacity";
  static final String SCHEMA = "ai-review-execution-v1";
  private static final Map<String, Object> NOWAIT = Map.of("jakarta.persistence.lock.timeout", 0);
  @PersistenceContext EntityManager entityManager;
  private final MBlobRepository blobs;
  private final PollableTaskBlobStorage taskBlobs;
  private final ObjectMapper mapper;
  private final AiReviewExecutionProperties configuration;
  private final TransactionTemplate transaction;
  private volatile boolean initialized;
  Clock clock = Clock.systemUTC();

  public AiReviewExecutionStore(
      MBlobRepository blobs,
      PollableTaskBlobStorage taskBlobs,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper mapper,
      AiReviewExecutionProperties configuration,
      PlatformTransactionManager transactionManager) {
    this.blobs = blobs;
    this.taskBlobs = taskBlobs;
    this.mapper = mapper;
    this.configuration = configuration;
    transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setTimeout(10);
  }

  public enum Disposition {
    START,
    WAIT,
    FINISH,
    DONE
  }

  public record Claim(Disposition disposition, String token, Instant deadline) {}

  public record State(
      String schema,
      String token,
      String owner,
      Instant startedAt,
      Instant deadline,
      AiReviewChatJob.Result result) {}

  public record Reservation(Long userId, Instant deadline) {}

  public record Capacity(Map<String, Reservation> reservations) {}

  public Claim tryClaim(long taskId, String owner) {
    try {
      initializeCapacity();
      return tx(
          () -> {
            MBlob row = lockCapacity();
            Capacity capacity = capacity(row);
            Instant now = clock.instant();
            capacity
                .reservations()
                .values()
                .removeIf(reservation -> !reservation.deadline().isAfter(now));
            writeCapacity(row, capacity);
            PollableTask task = lockTask(taskId);
            if (task == null) return claim(Disposition.DONE, null);
            State state = state(task);
            if (task.getFinishedDate() != null) return claim(Disposition.DONE, state);
            if (state != null && state.result() != null) return claim(Disposition.FINISH, state);
            Instant deadline = state == null ? deadline(task) : state.deadline();
            if (!deadline.isAfter(now) || task.getErrorMessage() != null) {
              State expired =
                  new State(
                      SCHEMA,
                      state == null ? null : state.token(),
                      state == null ? null : state.owner(),
                      state == null ? null : state.startedAt(),
                      deadline,
                      error(504, "AI review took too long. Please retry."));
              writeState(task, expired);
              return claim(Disposition.FINISH, expired);
            }
            if (state != null && state.token() != null) return claim(Disposition.WAIT, state);
            Long userId = ownerId(task);
            long userInFlight =
                capacity.reservations().values().stream()
                    .filter(reservation -> Objects.equals(userId, reservation.userId()))
                    .count();
            if (capacity.reservations().size() >= configuration.getMaxInFlight()
                || userInFlight >= configuration.getMaxInFlightPerUser()) {
              if (state == null)
                writeState(task, new State(SCHEMA, null, null, null, deadline, null));
              return new Claim(Disposition.WAIT, null, deadline);
            }
            String token = UUID.randomUUID().toString();
            State started = new State(SCHEMA, token, owner, now, deadline, null);
            capacity.reservations().put(token, new Reservation(userId, deadline));
            writeCapacity(row, capacity);
            writeState(task, started);
            return claim(Disposition.START, started);
          });
    } catch (RuntimeException exception) {
      if (isLockContention(exception)) return new Claim(Disposition.WAIT, null, null);
      throw exception;
    }
  }

  /** Called only when the local transport has completed. Fences both result and slot release. */
  public void stageResult(long taskId, String token, AiReviewChatJob.Result result) {
    stageResult(taskId, token, result, true);
  }

  public void stageResult(
      long taskId, String token, AiReviewChatJob.Result result, boolean transportSettled) {
    Objects.requireNonNull(token);
    Objects.requireNonNull(result);
    tx(
        () -> {
          MBlob row = lockCapacity();
          Capacity capacity = capacity(row);
          // Removing this opaque token cannot remove a different attempt's reservation.
          if (transportSettled) capacity.reservations().remove(token);
          writeCapacity(row, capacity);
          PollableTask task = lockTask(taskId);
          if (task == null || task.getFinishedDate() != null) return null;
          State state = state(task);
          if (state == null || !token.equals(state.token()) || state.result() != null) return null;
          AiReviewChatJob.Result accepted =
              state.deadline().isAfter(clock.instant())
                  ? result
                  : error(504, "AI review took too long. Please retry.");
          writeState(
              task,
              new State(
                  SCHEMA, token, state.owner(), state.startedAt(), state.deadline(), accepted));
          return null;
        });
  }

  /**
   * The canonical result is durable before this write. If the pod dies during blob storage, any
   * worker retries materialization from task JSON. The task remains pending until the blob exists.
   */
  public boolean finishStaged(long taskId) {
    Optional<AiReviewChatJob.Result> result = getStagedResult(taskId);
    if (result.isEmpty()) return false;
    // Staged results are immutable. Repeated writes use the same value, so network I/O can stay
    // outside the task transaction and recovery can safely retry a partially completed write.
    taskBlobs.saveOutput(taskId, result.get());
    return tx(
        () -> {
          PollableTask task = lockTask(taskId);
          if (task == null || task.getFinishedDate() != null) return true;
          State state = state(task);
          if (state == null || state.result() == null) return false;
          task.setFinishedDate(ZonedDateTime.now(clock));
          return true;
        });
  }

  /** Cancellation is durable; capacity remains reserved until transport completion or deadline. */
  public void cancel(long taskId) {
    stageUnstartedOrCancelled(taskId, error(409, "AI review was cancelled."));
  }

  public void rejectBusy(long taskId) {
    stageUnstartedOrCancelled(taskId, error(429, "AI review is busy. Please retry in a moment."));
  }

  private void stageUnstartedOrCancelled(long taskId, AiReviewChatJob.Result result) {
    tx(
        () -> {
          PollableTask task = lockTask(taskId);
          if (task == null || task.getFinishedDate() != null) return null;
          State current = state(task);
          if (current != null && current.result() != null) return null;
          if (result.error().status() == 429 && current != null && current.token() != null)
            return null;
          writeState(
              task,
              new State(
                  SCHEMA,
                  current == null ? null : current.token(),
                  current == null ? null : current.owner(),
                  current == null ? null : current.startedAt(),
                  current == null ? deadline(task) : current.deadline(),
                  result));
          return null;
        });
  }

  public void expire(long taskId) {
    tx(
        () -> {
          PollableTask task = lockTask(taskId);
          if (task == null || task.getFinishedDate() != null) return null;
          State current = state(task);
          if (current != null && current.result() != null) return null;
          Instant deadline = current == null ? deadline(task) : current.deadline();
          if (deadline.isAfter(clock.instant())) return null;
          writeState(
              task,
              new State(
                  SCHEMA,
                  current == null ? null : current.token(),
                  current == null ? null : current.owner(),
                  current == null ? null : current.startedAt(),
                  deadline,
                  error(504, "AI review timed out or was interrupted. Please retry.")));
          return null;
        });
  }

  public List<Long> unfinishedIds(long afterId, int limit) {
    return tx(
        () ->
            entityManager
                .createQuery(
                    """
                    select task.id from PollableTask task where task.id > :afterId
                    and task.name in :names and task.finishedDate is null order by task.id
                    """,
                    Long.class)
                .setParameter("afterId", afterId)
                .setParameter(
                    "names",
                    List.of(
                        AiReviewChatJob.class.getCanonicalName(),
                        AiReviewConfiguredChatJob.class.getCanonicalName()))
                .setMaxResults(Math.min(limit, 100))
                .getResultList());
  }

  public boolean isActive(long taskId, String token) {
    return tx(
        () -> {
          PollableTask task = entityManager.find(PollableTask.class, taskId);
          if (task == null || task.getFinishedDate() != null || task.getErrorMessage() != null)
            return false;
          State state = state(task);
          return state != null
              && token.equals(state.token())
              && state.result() == null
              && state.deadline().isAfter(clock.instant());
        });
  }

  public Optional<AiReviewChatJob.Result> getStagedResult(long taskId) {
    return tx(
        () -> {
          PollableTask task = entityManager.find(PollableTask.class, taskId);
          State state = task == null ? null : state(task);
          return Optional.ofNullable(state == null ? null : state.result());
        });
  }

  private Instant deadline(PollableTask task) {
    long timeout = configuration.getTimeoutSeconds();
    if (task.getTimeout() != null && task.getTimeout() > 0)
      timeout = Math.min(timeout, task.getTimeout());
    return task.getCreatedDate().toInstant().plusSeconds(timeout);
  }

  /** Match task-read authorization's ancestor fallback; ownerless legacy tasks share one bucket. */
  private Long ownerId(PollableTask task) {
    Set<Long> visited = new HashSet<>();
    for (PollableTask candidate = task;
        candidate != null && visited.add(candidate.getId());
        candidate = candidate.getParentTask()) {
      if (candidate.getCreatedByUser() != null) return candidate.getCreatedByUser().getId();
    }
    return null;
  }

  private PollableTask lockTask(long id) {
    PollableTask task =
        entityManager.find(PollableTask.class, id, LockModeType.PESSIMISTIC_WRITE, NOWAIT);
    if (task != null && !AiReviewChatJobAccess.isReviewChatJob(task)) {
      throw new IllegalArgumentException("Not an interactive AI review task");
    }
    return task;
  }

  private MBlob lockCapacity() {
    Long id =
        blobs
            .findIdByName(CAPACITY_NAME)
            .orElseThrow(() -> new IllegalStateException("Review capacity record is missing"));
    return entityManager.find(MBlob.class, id, LockModeType.PESSIMISTIC_WRITE, NOWAIT);
  }

  private synchronized void initializeCapacity() {
    if (initialized) return;
    try {
      tx(
          () -> {
            if (blobs.findIdByName(CAPACITY_NAME).isEmpty()) {
              MBlob row = new MBlob();
              row.setName(CAPACITY_NAME);
              writeCapacity(row, new Capacity(new HashMap<>()));
              // No expiration: this is admission state, not an evictable cache.
              blobs.saveAndFlush(row);
            }
            return null;
          });
    } catch (DataIntegrityViolationException race) {
      // Another pod created the unique row. Inspect it in a new transaction below.
    }
    tx(
        () -> {
          capacity(lockCapacity());
          return null;
        });
    initialized = true;
  }

  private Capacity capacity(MBlob row) {
    if (row == null || row.getContent() == null)
      throw new IllegalStateException("Missing review capacity");
    Capacity parsed =
        mapper.readValueUnchecked(
            new String(row.getContent(), StandardCharsets.UTF_8), Capacity.class);
    if (parsed == null
        || parsed.reservations() == null
        || parsed.reservations().entrySet().stream()
            .anyMatch(
                entry ->
                    entry.getKey() == null
                        || entry.getValue() == null
                        || entry.getValue().deadline() == null)) {
      throw new IllegalStateException("Invalid review capacity state");
    }
    return new Capacity(new HashMap<>(parsed.reservations()));
  }

  private void writeCapacity(MBlob row, Capacity capacity) {
    row.setContent(mapper.writeValueAsStringUnchecked(capacity).getBytes(StandardCharsets.UTF_8));
  }

  private State state(PollableTask task) {
    String message = task.getMessage();
    if (message == null || !message.contains(SCHEMA)) return null;
    State state = mapper.readValueUnchecked(message, State.class);
    if (state == null || !SCHEMA.equals(state.schema()) || state.deadline() == null) {
      throw new IllegalStateException("Invalid review lifecycle state");
    }
    return state;
  }

  private void writeState(PollableTask task, State state) {
    task.setMessage(mapper.writeValueAsStringUnchecked(state));
  }

  private Claim claim(Disposition disposition, State state) {
    return new Claim(
        disposition, state == null ? null : state.token(), state == null ? null : state.deadline());
  }

  private <T> T tx(Supplier<T> action) {
    return transaction.execute(status -> action.get());
  }

  static AiReviewChatJob.Result error(int status, String message) {
    return new AiReviewChatJob.Result(null, new AiReviewChatJob.Error(status, message));
  }

  static boolean isLockContention(Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof LockTimeoutException
          || cause instanceof PessimisticLockException
          || cause instanceof CannotAcquireLockException) return true;
    }
    return false;
  }
}
