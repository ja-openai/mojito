package com.box.l10n.mojito.service.oaireview;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.oaireview.AiReviewCapacityStore.Admission;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Settings;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mandatory, transactional review lifecycle. Uses the existing task JSON and a non-expiring,
 * uniquely named database blob; this state must never be routed to external blob storage.
 *
 * <p>Capacity is approximate and updated optimistically in independent transactions. Task claims,
 * cancellation and results use conditional updates and a unique attempt token. An ambiguous attempt
 * is never sent again automatically.
 */
@Service
public class AiReviewExecutionStore {
  private static final Logger logger = LoggerFactory.getLogger(AiReviewExecutionStore.class);
  static final String CAPACITY_NAME = "ai_review_execution/v1/capacity";
  static final String SCHEMA = "ai-review-execution-v1";
  @PersistenceContext EntityManager entityManager;
  private final AiReviewCapacityStore capacityStore;
  private final PollableTaskBlobStorage taskBlobs;
  private final ObjectMapper mapper;
  private final AiReviewExecutionProperties configuration;
  private final TransactionTemplate transaction;
  Clock clock = Clock.systemUTC();

  public AiReviewExecutionStore(
      AiReviewCapacityStore capacityStore,
      PollableTaskBlobStorage taskBlobs,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper mapper,
      AiReviewExecutionProperties configuration,
      PlatformTransactionManager transactionManager) {
    this.capacityStore = capacityStore;
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

  private record Eligibility(Claim existing, Long userId, Instant deadline) {}

  public Claim tryClaim(long taskId, String owner) {
    return tryClaim(taskId, owner, null);
  }

  public Claim tryClaim(long taskId, String owner, Settings settings) {
    Eligibility eligibility =
        taskTransaction(
            () -> {
              TaskSnapshot task = readReviewTask(taskId);
              if (settings != null
                  && task != null
                  && task.finishedDate() == null
                  && state(task) == null) {
                // Persist the effective speed budget for old queued inputs too. Never extend a
                // task's existing timeout or give already claimed work a new deadline.
                long selectedTimeout =
                    configuration.resolveTimeoutSeconds(
                        settings.profileId(), settings.reasoningEffort());
                if (task.timeout() == null
                    || task.timeout() <= 0
                    || selectedTimeout < task.timeout()) {
                  writeTask(task, task.message(), selectedTimeout, null);
                  task = task.withTimeout(selectedTimeout);
                }
              }
              Claim existing = existingClaim(task);
              if (existing != null) return new Eligibility(existing, null, null);
              State state = state(task);
              return new Eligibility(
                  null, ownerId(task), state == null ? deadline(task) : state.deadline());
            });
    if (eligibility.existing() != null) return eligibility.existing();

    String token = UUID.randomUUID().toString();
    // No task transaction or database connection is held while capacity updates retry.
    Admission admission =
        capacityStore.reserve(token, eligibility.userId(), eligibility.deadline(), clock.instant());
    boolean started = false;
    try {
      Claim claimed =
          taskTransaction(
              () -> {
                TaskSnapshot task = readReviewTask(taskId);
                // Cancellation, another claim or expiration can win during capacity accounting.
                Claim existing = existingClaim(task);
                if (existing != null) return existing;
                State state = state(task);
                Instant deadline = state == null ? deadline(task) : state.deadline();
                if (admission == Admission.USER_LIMIT) {
                  if (state == null)
                    writeState(task, new State(SCHEMA, null, null, null, deadline, null));
                  return new Claim(Disposition.WAIT, null, deadline);
                }
                State claimedState =
                    new State(SCHEMA, token, owner, clock.instant(), deadline, null);
                writeState(task, claimedState);
                return claim(Disposition.START, claimedState);
              });
      started = claimed.disposition() == Disposition.START;
      if (claimed.disposition() == Disposition.WAIT && claimed.token() == null) {
        logger.warn(
            "AI review admission rejected: reason={}, taskId={}, userId={}, userLimit={}, globalThreshold={}",
            "user_limit",
            taskId,
            eligibility.userId(),
            configuration.getMaxInFlightPerUser(),
            configuration.getMaxInFlight());
      }
      return claimed;
    } finally {
      // Duplicate or cancelled tasks release any provisional reservation independently.
      if (!started && admission == Admission.RESERVED) releaseCapacity(token);
    }
  }

  private Claim existingClaim(TaskSnapshot task) {
    if (task == null) return claim(Disposition.DONE, null);
    State state = state(task);
    if (task.finishedDate() != null) return claim(Disposition.DONE, state);
    if (state != null && state.result() != null) return claim(Disposition.FINISH, state);
    Instant deadline = state == null ? deadline(task) : state.deadline();
    if (!deadline.isAfter(clock.instant()) || task.errorMessage() != null) {
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
    return state != null && state.token() != null ? claim(Disposition.WAIT, state) : null;
  }

  /** Persist the canonical result independently of approximate capacity accounting. */
  public void stageResult(long taskId, String token, AiReviewChatJob.Result result) {
    Objects.requireNonNull(token);
    Objects.requireNonNull(result);
    taskTransaction(
        () -> {
          TaskSnapshot task = readReviewTask(taskId);
          if (task == null || task.finishedDate() != null) return null;
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

  /** Release after transport settlement or abandoning an unstarted claim. */
  public boolean releaseCapacity(String token) {
    try {
      return capacityStore.release(token, clock.instant());
    } catch (RuntimeException exception) {
      logger.warn(
          "AI review capacity release deferred; reservation expires at its deadline", exception);
      return false;
    }
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
    return taskTransaction(
        () -> {
          TaskSnapshot task = readReviewTask(taskId);
          if (task == null || task.finishedDate() != null) return true;
          State state = state(task);
          if (state == null || state.result() == null) return false;
          writeTask(task, task.message(), task.timeout(), ZonedDateTime.now(clock));
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
    taskTransaction(
        () -> {
          TaskSnapshot task = readReviewTask(taskId);
          if (task == null || task.finishedDate() != null) return null;
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
    taskTransaction(
        () -> {
          TaskSnapshot task = readReviewTask(taskId);
          if (task == null || task.finishedDate() != null) return null;
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
          TaskSnapshot task = readTask(taskId);
          if (task == null || task.finishedDate() != null || task.errorMessage() != null)
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
          TaskSnapshot task = readTask(taskId);
          State state = task == null ? null : state(task);
          return Optional.ofNullable(state == null ? null : state.result());
        });
  }

  private Instant deadline(TaskSnapshot task) {
    long timeout = configuration.getTimeoutSeconds();
    if (task.timeout() != null && task.timeout() > 0) timeout = Math.min(timeout, task.timeout());
    return task.createdDate().toInstant().plusSeconds(timeout);
  }

  /** Match task-read authorization's ancestor fallback; ownerless legacy tasks share one bucket. */
  private Long ownerId(TaskSnapshot task) {
    Set<Long> visited = new HashSet<>();
    for (TaskSnapshot candidate = task;
        candidate != null && visited.add(candidate.id());
        candidate = candidate.parentId() == null ? null : readTask(candidate.parentId())) {
      if (candidate.userId() != null) return candidate.userId();
    }
    return null;
  }

  record TaskSnapshot(
      Long id,
      String name,
      String message,
      Long timeout,
      ZonedDateTime createdDate,
      ZonedDateTime finishedDate,
      String errorMessage,
      Long userId,
      Long parentId) {
    TaskSnapshot withTimeout(long value) {
      return new TaskSnapshot(
          id, name, message, value, createdDate, finishedDate, errorMessage, userId, parentId);
    }
  }

  private TaskSnapshot readReviewTask(long id) {
    TaskSnapshot task = readTask(id);
    if (task != null
        && !AiReviewChatJob.class.getCanonicalName().equals(task.name())
        && !AiReviewConfiguredChatJob.class.getCanonicalName().equals(task.name())) {
      throw new IllegalArgumentException("Not an interactive AI review task");
    }
    return task;
  }

  private TaskSnapshot readTask(long id) {
    // Scalar reads bypass request-scoped JPA entities. A stale managed task must never be
    // refreshed/modified here and later flush over another process's lifecycle transition.
    List<Object[]> rows =
        entityManager
            .createQuery(
                """
                select task.id, task.name, task.message, task.timeout, task.createdDate,
                    task.finishedDate, task.errorMessage, owner.id, parent.id
                from PollableTask task
                left join task.createdByUser owner left join task.parentTask parent
                where task.id = :id
                """,
                Object[].class)
            .setParameter("id", id)
            .getResultList();
    if (rows.isEmpty()) return null;
    Object[] row = rows.getFirst();
    return new TaskSnapshot(
        (Long) row[0],
        (String) row[1],
        (String) row[2],
        (Long) row[3],
        (ZonedDateTime) row[4],
        (ZonedDateTime) row[5],
        (String) row[6],
        (Long) row[7],
        (Long) row[8]);
  }

  private State state(TaskSnapshot task) {
    String message = task.message();
    if (message == null || !message.contains(SCHEMA)) return null;
    State state = mapper.readValueUnchecked(message, State.class);
    if (state == null || !SCHEMA.equals(state.schema()) || state.deadline() == null) {
      throw new IllegalStateException("Invalid review lifecycle state");
    }
    return state;
  }

  private void writeState(TaskSnapshot task, State state) {
    writeTask(task, mapper.writeValueAsStringUnchecked(state), task.timeout(), null);
  }

  private Claim claim(Disposition disposition, State state) {
    return new Claim(
        disposition, state == null ? null : state.token(), state == null ? null : state.deadline());
  }

  private void writeTask(
      TaskSnapshot expected, String message, Long timeout, ZonedDateTime finishedDate) {
    if (!compareAndSet(expected, message, timeout, finishedDate)) throw new StateChanged();
  }

  boolean compareAndSet(
      TaskSnapshot expected, String message, Long timeout, ZonedDateTime finishedDate) {
    // Only transition the exact unfinished snapshot used to make this decision. A winner's
    // claim or terminal result is reread rather than overwritten. Ordinary UPDATE still takes
    // a brief database write lock; no explicit locking read or lock is held across provider I/O.
    var update =
        entityManager
            .createQuery(
                """
                update PollableTask task
                set task.message = :message, task.timeout = :timeout,
                    task.finishedDate = :finishedDate, task.lastModifiedDate = :modified
                where task.id = :id and task.name = :name and task.finishedDate is null
                """
                    + (expected.message() == null
                        ? " and task.message is null"
                        : " and task.message = :expectedMessage")
                    + (expected.timeout() == null
                        ? " and task.timeout is null"
                        : " and task.timeout = :expectedTimeout")
                    + (expected.errorMessage() == null
                        ? " and task.errorMessage is null"
                        : " and task.errorMessage = :expectedError"))
            .setParameter("id", expected.id())
            .setParameter("name", expected.name())
            .setParameter("message", message)
            .setParameter("timeout", timeout)
            .setParameter("finishedDate", finishedDate)
            .setParameter("modified", ZonedDateTime.now(clock))
            .setHint("jakarta.persistence.query.timeout", 1000);
    if (expected.message() != null) update.setParameter("expectedMessage", expected.message());
    if (expected.timeout() != null) update.setParameter("expectedTimeout", expected.timeout());
    if (expected.errorMessage() != null)
      update.setParameter("expectedError", expected.errorMessage());
    return update.executeUpdate() == 1;
  }

  private static class StateChanged extends RuntimeException {}

  private <T> T taskTransaction(Supplier<T> action) {
    for (int attempt = 0; ; attempt++) {
      try {
        return tx(action);
      } catch (StateChanged conflict) {
        if (attempt == 2) {
          throw new ResponseStatusException(
              HttpStatus.SERVICE_UNAVAILABLE, "AI review state changed. Please retry.", conflict);
        }
        // A new transaction sees the committed winner even with repeatable-read isolation.
      }
    }
  }

  private <T> T tx(Supplier<T> action) {
    return transaction.execute(status -> action.get());
  }

  static AiReviewChatJob.Result error(int status, String message) {
    return new AiReviewChatJob.Result(null, new AiReviewChatJob.Error(status, message));
  }
}
