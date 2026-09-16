package com.box.l10n.mojito.service.pollableTask;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.PollableTaskArchiveCheckpoint;
import com.box.l10n.mojito.entity.PollableTaskArchiveRetry;
import com.box.l10n.mojito.service.oaireview.AiReviewChatJobAccess;
import com.box.l10n.mojito.service.pollableTask.PollableTaskArchiveStorage.ArchivedPollableTask;
import com.box.l10n.mojito.service.pollableTask.PollableTaskRepository.ArchiveCandidate;
import com.box.l10n.mojito.service.tm.GenerateLocalizedAssetJob;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Archives bounded keyset batches without holding a database transaction during Azure I/O. */
@Service
@ConditionalOnProperty(value = "l10n.pollable-task.archive.enabled", havingValue = "true")
public class PollableTaskArchiveService {

  static final String TASK_METRIC = "PollableTaskArchiveService.task";

  static final String BATCH_METRIC = "PollableTaskArchiveService.batch.duration";

  static final ZonedDateTime INITIAL_FINISHED_DATE =
      ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);

  private static final int CHECKPOINT_ID = 1;

  private static final int MAX_BATCH_SIZE = 1000;

  private static final int MAX_ERROR_LENGTH = 2048;

  private static final Logger logger = LoggerFactory.getLogger(PollableTaskArchiveService.class);

  private final PollableTaskRepository pollableTaskRepository;

  private final PollableTaskArchiveCheckpointRepository checkpointRepository;

  private final PollableTaskArchiveRetryRepository retryRepository;

  private final PollableTaskArchiveReferenceService referenceService;

  private final PollableTaskArchiveStorage pollableTaskArchiveStorage;

  private final PollableTaskArchiveProperties properties;

  private final MeterRegistry meterRegistry;

  private final TransactionTemplate readTransaction;

  private final TransactionTemplate writeTransaction;

  public PollableTaskArchiveService(
      PollableTaskRepository pollableTaskRepository,
      PollableTaskArchiveCheckpointRepository checkpointRepository,
      PollableTaskArchiveRetryRepository retryRepository,
      PollableTaskArchiveReferenceService referenceService,
      PollableTaskArchiveStorage pollableTaskArchiveStorage,
      PollableTaskArchiveProperties properties,
      PlatformTransactionManager transactionManager,
      MeterRegistry meterRegistry) {
    this.pollableTaskRepository = Objects.requireNonNull(pollableTaskRepository);
    this.checkpointRepository = Objects.requireNonNull(checkpointRepository);
    this.retryRepository = Objects.requireNonNull(retryRepository);
    this.referenceService = Objects.requireNonNull(referenceService);
    this.pollableTaskArchiveStorage = Objects.requireNonNull(pollableTaskArchiveStorage);
    this.properties = Objects.requireNonNull(properties);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
    if (!pollableTaskArchiveStorage.isAzureArchiveConfigured()) {
      throw new IllegalStateException(
          "Pollable-task archival requires the pollable-task-archive blob prefix to route directly to Azure");
    }
    if (properties.getRetentionDays() < 1) {
      throw new IllegalArgumentException(
          "Pollable-task archive retention must be at least one day");
    }
    if (properties.getBatchSize() < 1 || properties.getBatchSize() > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "Pollable-task archive batch size must be between 1 and " + MAX_BATCH_SIZE);
    }
    if (properties.getLeaseSeconds() < 30) {
      throw new IllegalArgumentException("Pollable-task archive lease must be at least 30 seconds");
    }

    readTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    readTransaction.setReadOnly(true);
    readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    writeTransaction = new TransactionTemplate(transactionManager);
    writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public BatchResult archiveFinishedTasks() {
    Timer.Sample sample = Timer.start(meterRegistry);
    String result = "failure";
    Lease lease = null;
    try {
      lease = acquireLease();
      if (lease == null) {
        recordTask("busy");
        result = "busy";
        return new BatchResult(0, 0, 0, 0, 0, 0);
      }

      Counters counters = new Counters();
      List<PollableTaskArchiveRetry> retries =
          readTransaction.execute(
              status ->
                  retryRepository.findByNextAttemptAtLessThanEqualOrderByNextAttemptAtAscTaskIdAsc(
                      ZonedDateTime.now(), PageRequest.of(0, properties.getBatchSize())));
      for (PollableTaskArchiveRetry retry : retries) {
        processCandidate(
            new Candidate(retry.getTaskId(), retry.getFinishedDate()), lease, true, counters);
      }

      int remaining = properties.getBatchSize() - counters.scanned;
      if (remaining > 0 && lease.highWaterFinishedDate() != null) {
        Lease currentLease = lease;
        List<ArchiveCandidate> candidates =
            readTransaction.execute(
                status ->
                    pollableTaskRepository.findArchiveCandidates(
                        currentLease.lastFinishedDate(),
                        currentLease.lastTaskId(),
                        currentLease.finishedBefore(),
                        currentLease.highWaterFinishedDate(),
                        currentLease.highWaterTaskId(),
                        PageRequest.of(0, remaining)));
        if (candidates.isEmpty()) {
          consumeMissingHighWater(lease);
        }
        for (ArchiveCandidate candidate : candidates) {
          processCandidate(
              new Candidate(candidate.getId(), candidate.getFinishedDate()),
              lease,
              false,
              counters);
        }
      }

      BatchResult batchResult = counters.toResult();
      logger.info(
          "Pollable-task archive batch completed: retentionDays={}, batchSize={}, deleteSource={}, cutoff={}, highWaterFinishedDate={}, highWaterTaskId={}, scanned={}, uploaded={}, verified={}, deleted={}, skipped={}, failed={}",
          properties.getRetentionDays(),
          properties.getBatchSize(),
          properties.isDeleteSource(),
          lease.finishedBefore(),
          lease.highWaterFinishedDate(),
          lease.highWaterTaskId(),
          batchResult.scanned(),
          batchResult.uploaded(),
          batchResult.verified(),
          batchResult.deleted(),
          batchResult.skipped(),
          batchResult.failed());
      result = counters.failed == 0 ? "success" : "partial_failure";
      return batchResult;
    } finally {
      if (lease != null) {
        releaseLease(lease);
      }
      sample.stop(meterRegistry.timer(BATCH_METRIC, "result", result));
    }
  }

  private Lease acquireLease() {
    return writeTransaction.execute(
        status -> {
          ZonedDateTime now = ZonedDateTime.now();
          PollableTaskArchiveCheckpoint checkpoint =
              checkpointRepository.findForUpdate(CHECKPOINT_ID).orElseGet(this::createCheckpoint);
          if (checkpoint.getLeaseToken() != null
              && checkpoint.getLeaseExpiresAt() != null
              && checkpoint.getLeaseExpiresAt().isAfter(now)) {
            return null;
          }

          if (checkpoint.isDeleteSourceMode() != properties.isDeleteSource()
              || checkpoint.getRetentionDays() != properties.getRetentionDays()) {
            checkpoint.setLastFinishedDate(INITIAL_FINISHED_DATE);
            checkpoint.setLastTaskId(0L);
            checkpoint.setCutoffFinishedBefore(null);
            checkpoint.setHighWaterFinishedDate(null);
            checkpoint.setHighWaterTaskId(null);
            checkpoint.setDeleteSourceMode(properties.isDeleteSource());
            checkpoint.setRetentionDays(properties.getRetentionDays());
            retryRepository.deleteAllInBatch();
            logger.info(
                "Reset pollable-task archive checkpoint after policy changed: deleteSource={}, retentionDays={}",
                properties.isDeleteSource(),
                properties.getRetentionDays());
          }

          ZonedDateTime finishedBefore = checkpoint.getCutoffFinishedBefore();
          if (checkpoint.getHighWaterFinishedDate() == null) {
            finishedBefore = now.minusDays(properties.getRetentionDays());
            List<ArchiveCandidate> highWater =
                pollableTaskRepository.findArchiveHighWater(
                    checkpoint.getLastFinishedDate(),
                    checkpoint.getLastTaskId(),
                    finishedBefore,
                    PageRequest.of(0, 1));
            if (!highWater.isEmpty()) {
              ArchiveCandidate bound = highWater.getFirst();
              checkpoint.setCutoffFinishedBefore(finishedBefore);
              checkpoint.setHighWaterFinishedDate(bound.getFinishedDate());
              checkpoint.setHighWaterTaskId(bound.getId());
            }
          }

          String token = UUID.randomUUID().toString();
          checkpoint.setLeaseToken(token);
          checkpoint.setLeaseExpiresAt(now.plusSeconds(properties.getLeaseSeconds()));
          checkpointRepository.saveAndFlush(checkpoint);
          return new Lease(
              token,
              finishedBefore,
              checkpoint.getLastFinishedDate(),
              checkpoint.getLastTaskId(),
              checkpoint.getHighWaterFinishedDate(),
              checkpoint.getHighWaterTaskId());
        });
  }

  private PollableTaskArchiveCheckpoint createCheckpoint() {
    PollableTaskArchiveCheckpoint checkpoint = new PollableTaskArchiveCheckpoint();
    checkpoint.setId(CHECKPOINT_ID);
    checkpoint.setLastFinishedDate(INITIAL_FINISHED_DATE);
    checkpoint.setLastTaskId(0L);
    checkpoint.setDeleteSourceMode(properties.isDeleteSource());
    checkpoint.setRetentionDays(properties.getRetentionDays());
    return checkpointRepository.saveAndFlush(checkpoint);
  }

  private void processCandidate(
      Candidate candidate, Lease lease, boolean retry, Counters counters) {
    counters.scanned++;
    try {
      renewLease(lease);
      Optional<ArchivedPollableTask> snapshot = loadSnapshot(candidate, lease.finishedBefore());
      if (snapshot.isEmpty()) {
        finishCandidate(candidate, lease, retry, null);
        counters.skipped++;
        recordTask("changed");
        return;
      }

      ArchivedPollableTask archivedTask = snapshot.get();
      Optional<ArchivedPollableTask> existingArchive =
          pollableTaskArchiveStorage.findArchive(candidate.taskId());
      if (existingArchive.isEmpty()) {
        if (pollableTaskArchiveStorage.putArchive(archivedTask)) {
          counters.uploaded++;
          recordTask("uploaded");
        }
        existingArchive = pollableTaskArchiveStorage.findArchive(candidate.taskId());
      }

      if (existingArchive.isEmpty() || !existingArchive.get().matchesTaskState(archivedTask)) {
        throw new IllegalStateException(
            "Immutable pollable-task archive does not match task: " + candidate.taskId());
      }
      counters.verified++;

      Completion completion =
          finishCandidate(
              candidate, lease, retry, properties.isDeleteSource() ? archivedTask : null);
      if (completion == Completion.DELETED) {
        counters.deleted++;
        recordTask("deleted");
      } else if (completion == Completion.CHANGED) {
        counters.skipped++;
        recordTask("changed");
      } else {
        recordTask("retained");
      }
    } catch (RuntimeException exception) {
      counters.failed++;
      recordTask("failed");
      recordFailure(candidate, lease, retry, exception);
      logger.error("Failed to archive completed pollable task {}", candidate.taskId(), exception);
    }
  }

  private Optional<ArchivedPollableTask> loadSnapshot(
      Candidate candidate, ZonedDateTime finishedBefore) {
    return readTransaction.execute(
        status ->
            pollableTaskRepository
                .findForArchiveRead(candidate.taskId())
                .filter(
                    task ->
                        task.getFinishedDate() != null
                            && task.getFinishedDate().isEqual(candidate.finishedDate())
                            && isArchivable(task, finishedBefore))
                .map(ArchivedPollableTask::from));
  }

  private boolean isArchivable(PollableTask task, ZonedDateTime finishedBefore) {
    return task.getFinishedDate() != null
        && task.getFinishedDate().isBefore(finishedBefore)
        // Usage history and queue repair/replay hold task IDs without foreign keys. A lookup alone
        // cannot fence a new reference, so retain these task types until those writers support it.
        && !AiReviewChatJobAccess.isReviewChatJob(task)
        && !GenerateLocalizedAssetJob.class.getCanonicalName().equals(task.getName())
        && task.getParentTask() == null
        && task.getExpectedSubTaskNumber() == 0
        && !referenceService.hasIncomingReference(task.getId())
        && (task.getSubTasks() == null || task.getSubTasks().isEmpty());
  }

  private Completion finishCandidate(
      Candidate candidate, Lease lease, boolean retry, ArchivedPollableTask verifiedArchive) {
    return writeTransaction.execute(
        status -> {
          PollableTaskArchiveCheckpoint checkpoint = ownedCheckpoint(lease);
          Completion completion = Completion.RETAINED;
          if (verifiedArchive != null) {
            Optional<PollableTask> currentTask =
                pollableTaskRepository.findForArchiveUpdate(candidate.taskId());
            if (currentTask.isEmpty()
                || !isArchivable(currentTask.get(), lease.finishedBefore())
                || !ArchivedPollableTask.from(currentTask.get())
                    .matchesTaskState(verifiedArchive)) {
              completion = Completion.CHANGED;
            } else {
              pollableTaskRepository.delete(currentTask.get());
              pollableTaskRepository.flush();
              completion = Completion.DELETED;
            }
          }

          if (retry) {
            retryRepository.deleteById(candidate.taskId());
          } else {
            checkpointCandidate(checkpoint, candidate);
          }
          checkpointRepository.save(checkpoint);
          return completion;
        });
  }

  private void recordFailure(
      Candidate candidate, Lease lease, boolean retry, RuntimeException exception) {
    writeTransaction.executeWithoutResult(
        status -> {
          PollableTaskArchiveCheckpoint checkpoint = ownedCheckpoint(lease);
          PollableTaskArchiveRetry taskRetry =
              retryRepository
                  .findById(candidate.taskId())
                  .orElseGet(
                      () -> {
                        PollableTaskArchiveRetry entry = new PollableTaskArchiveRetry();
                        entry.setTaskId(candidate.taskId());
                        entry.setFinishedDate(candidate.finishedDate());
                        return entry;
                      });
          int attempts = taskRetry.getAttemptCount() + 1;
          taskRetry.setAttemptCount(attempts);
          taskRetry.setNextAttemptAt(
              ZonedDateTime.now().plusMinutes(Math.min(60L, 1L << Math.min(attempts - 1, 6))));
          String error = Objects.toString(exception.getMessage(), exception.getClass().getName());
          taskRetry.setLastError(error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH)));
          retryRepository.save(taskRetry);
          if (!retry) {
            checkpointCandidate(checkpoint, candidate);
          }
          checkpointRepository.save(checkpoint);
          recordTask("queued_retry");
        });
  }

  private void checkpointCandidate(PollableTaskArchiveCheckpoint checkpoint, Candidate candidate) {
    checkpoint.setLastFinishedDate(candidate.finishedDate());
    checkpoint.setLastTaskId(candidate.taskId());
    if (candidate.finishedDate().isEqual(checkpoint.getHighWaterFinishedDate())
        && candidate.taskId() == checkpoint.getHighWaterTaskId()) {
      checkpoint.setCutoffFinishedBefore(null);
      checkpoint.setHighWaterFinishedDate(null);
      checkpoint.setHighWaterTaskId(null);
    }
  }

  private void consumeMissingHighWater(Lease lease) {
    writeTransaction.executeWithoutResult(
        status -> {
          PollableTaskArchiveCheckpoint checkpoint = ownedCheckpoint(lease);
          checkpoint.setLastFinishedDate(lease.highWaterFinishedDate());
          checkpoint.setLastTaskId(lease.highWaterTaskId());
          checkpoint.setCutoffFinishedBefore(null);
          checkpoint.setHighWaterFinishedDate(null);
          checkpoint.setHighWaterTaskId(null);
          checkpointRepository.save(checkpoint);
        });
  }

  private void renewLease(Lease lease) {
    writeTransaction.executeWithoutResult(
        status -> {
          PollableTaskArchiveCheckpoint checkpoint = ownedCheckpoint(lease);
          checkpoint.setLeaseExpiresAt(
              ZonedDateTime.now().plusSeconds(properties.getLeaseSeconds()));
          checkpointRepository.save(checkpoint);
        });
  }

  private PollableTaskArchiveCheckpoint ownedCheckpoint(Lease lease) {
    PollableTaskArchiveCheckpoint checkpoint =
        checkpointRepository
            .findForUpdate(CHECKPOINT_ID)
            .orElseThrow(
                () -> new IllegalStateException("Pollable-task archive checkpoint is missing"));
    if (!lease.token().equals(checkpoint.getLeaseToken())) {
      throw new IllegalStateException("Pollable-task archive checkpoint lease was lost");
    }
    return checkpoint;
  }

  private void releaseLease(Lease lease) {
    writeTransaction.executeWithoutResult(
        status ->
            checkpointRepository
                .findForUpdate(CHECKPOINT_ID)
                .filter(checkpoint -> lease.token().equals(checkpoint.getLeaseToken()))
                .ifPresent(
                    checkpoint -> {
                      checkpoint.setLeaseToken(null);
                      checkpoint.setLeaseExpiresAt(null);
                      checkpointRepository.save(checkpoint);
                    }));
  }

  private void recordTask(String result) {
    meterRegistry.counter(TASK_METRIC, "result", result).increment();
  }

  private record Lease(
      String token,
      ZonedDateTime finishedBefore,
      ZonedDateTime lastFinishedDate,
      long lastTaskId,
      ZonedDateTime highWaterFinishedDate,
      Long highWaterTaskId) {}

  private record Candidate(long taskId, ZonedDateTime finishedDate) {}

  private enum Completion {
    RETAINED,
    DELETED,
    CHANGED
  }

  private static final class Counters {

    private int scanned;

    private int uploaded;

    private int verified;

    private int deleted;

    private int skipped;

    private int failed;

    private BatchResult toResult() {
      return new BatchResult(scanned, uploaded, verified, deleted, skipped, failed);
    }
  }

  public record BatchResult(
      int scanned, int uploaded, int verified, int deleted, int skipped, int failed) {}
}
