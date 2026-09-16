package com.box.l10n.mojito.service.pollableTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.PollableTaskArchiveCheckpoint;
import com.box.l10n.mojito.entity.PollableTaskArchiveRetry;
import com.box.l10n.mojito.service.oaireview.AiReviewChatJob;
import com.box.l10n.mojito.service.oaireview.AiReviewConfiguredChatJob;
import com.box.l10n.mojito.service.pollableTask.PollableTaskArchiveStorage.ArchivedPollableTask;
import com.box.l10n.mojito.service.pollableTask.PollableTaskRepository.ArchiveCandidate;
import com.box.l10n.mojito.service.tm.GenerateLocalizedAssetJob;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

public class PollableTaskArchiveServiceTest {

  private PollableTaskRepository pollableTaskRepository;

  private PollableTaskArchiveCheckpointRepository checkpointRepository;

  private PollableTaskArchiveRetryRepository retryRepository;

  private PollableTaskArchiveReferenceService referenceService;

  private PollableTaskArchiveStorage archiveStorage;

  private PollableTaskArchiveProperties properties;

  private PlatformTransactionManager transactionManager;

  private SimpleMeterRegistry meterRegistry;

  private AtomicInteger activeTransactions;

  private PollableTaskArchiveCheckpoint checkpoint;

  private Map<Long, PollableTaskArchiveRetry> retries;

  @Before
  public void setUp() {
    pollableTaskRepository = mock(PollableTaskRepository.class);
    checkpointRepository = mock(PollableTaskArchiveCheckpointRepository.class);
    retryRepository = mock(PollableTaskArchiveRetryRepository.class);
    referenceService = mock(PollableTaskArchiveReferenceService.class);
    archiveStorage = mock(PollableTaskArchiveStorage.class);
    when(archiveStorage.isAzureArchiveConfigured()).thenReturn(true);
    when(archiveStorage.putArchive(any())).thenReturn(true);
    properties = new PollableTaskArchiveProperties();
    properties.setBatchSize(10);
    properties.setRetentionDays(30);
    transactionManager = mock(PlatformTransactionManager.class);
    activeTransactions = new AtomicInteger();
    when(transactionManager.getTransaction(any()))
        .thenAnswer(
            invocation -> {
              activeTransactions.incrementAndGet();
              return new SimpleTransactionStatus();
            });
    doAnswer(
            invocation -> {
              activeTransactions.decrementAndGet();
              return null;
            })
        .when(transactionManager)
        .commit(any());
    doAnswer(
            invocation -> {
              activeTransactions.decrementAndGet();
              return null;
            })
        .when(transactionManager)
        .rollback(any());
    checkpoint = new PollableTaskArchiveCheckpoint();
    checkpoint.setId(1);
    checkpoint.setLastFinishedDate(PollableTaskArchiveService.INITIAL_FINISHED_DATE);
    checkpoint.setLastTaskId(0L);
    checkpoint.setRetentionDays(properties.getRetentionDays());
    when(checkpointRepository.findForUpdate(anyInt()))
        .thenAnswer(invocation -> Optional.of(checkpoint));
    when(checkpointRepository.saveAndFlush(any(PollableTaskArchiveCheckpoint.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(checkpointRepository.save(any(PollableTaskArchiveCheckpoint.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    retries = new HashMap<>();
    when(retryRepository.findById(anyLong()))
        .thenAnswer(invocation -> Optional.ofNullable(retries.get(invocation.getArgument(0))));
    when(retryRepository.save(any(PollableTaskArchiveRetry.class)))
        .thenAnswer(
            invocation -> {
              PollableTaskArchiveRetry retry = invocation.getArgument(0);
              retries.put(retry.getTaskId(), retry);
              return retry;
            });
    doAnswer(
            invocation -> {
              retries.remove(invocation.getArgument(0));
              return null;
            })
        .when(retryRepository)
        .deleteById(anyLong());
    when(retryRepository.findByNextAttemptAtLessThanEqualOrderByNextAttemptAtAscTaskIdAsc(
            any(), any(Pageable.class)))
        .thenAnswer(
            invocation ->
                retries.values().stream()
                    .filter(retry -> !retry.getNextAttemptAt().isAfter(invocation.getArgument(0)))
                    .limit(((Pageable) invocation.getArgument(1)).getPageSize())
                    .toList());
    meterRegistry = new SimpleMeterRegistry();
  }

  @Test
  public void uploadsAndVerifiesWithoutDeletingByDefaultOrHoldingDatabaseTransactions() {
    PollableTask task = task(42L);
    ArchivedPollableTask archive = ArchivedPollableTask.from(task);
    givenCandidate(task);
    when(archiveStorage.findArchive(42L))
        .thenAnswer(
            invocation -> {
              assertThat(activeTransactions.get()).isZero();
              return Optional.empty();
            })
        .thenAnswer(
            invocation -> {
              assertThat(activeTransactions.get()).isZero();
              return Optional.of(archive);
            });
    doAnswer(
            invocation -> {
              assertThat(activeTransactions.get()).isZero();
              return true;
            })
        .when(archiveStorage)
        .putArchive(archive);

    PollableTaskArchiveService.BatchResult result = archiveService().archiveFinishedTasks();

    assertThat(result).isEqualTo(new PollableTaskArchiveService.BatchResult(1, 1, 1, 0, 0, 0));
    assertThat(activeTransactions.get()).isZero();
    assertThat(checkpoint.getLastTaskId()).isEqualTo(42L);
    assertThat(checkpoint.getHighWaterFinishedDate()).isNull();
    verify(pollableTaskRepository, never()).delete(any(PollableTask.class));
    assertThat(taskMetric("uploaded")).isEqualTo(1);
    assertThat(taskMetric("retained")).isEqualTo(1);
  }

  @Test
  public void deletesOnlyAfterAzureUploadAndReadBackVerification() {
    properties.setDeleteSource(true);
    PollableTask task = task(42L);
    ArchivedPollableTask archive = ArchivedPollableTask.from(task);
    givenCandidate(task);
    when(archiveStorage.findArchive(42L)).thenReturn(Optional.empty(), Optional.of(archive));
    when(pollableTaskRepository.findForArchiveUpdate(42L)).thenReturn(Optional.of(task));

    PollableTaskArchiveService.BatchResult result = archiveService().archiveFinishedTasks();

    assertThat(result).isEqualTo(new PollableTaskArchiveService.BatchResult(1, 1, 1, 1, 0, 0));
    InOrder order = inOrder(archiveStorage, pollableTaskRepository);
    order.verify(archiveStorage).putArchive(archive);
    order.verify(archiveStorage).findArchive(42L);
    order.verify(pollableTaskRepository).findForArchiveUpdate(42L);
    order.verify(pollableTaskRepository).delete(task);
    order.verify(pollableTaskRepository).flush();
    assertThat(taskMetric("deleted")).isEqualTo(1);
  }

  @Test
  public void retriesAnAlreadyVerifiedArchiveWithoutUploadingAgain() {
    properties.setDeleteSource(true);
    PollableTask task = task(42L);
    ArchivedPollableTask archive = ArchivedPollableTask.from(task);
    givenCandidate(task);
    when(archiveStorage.findArchive(42L)).thenReturn(Optional.of(archive));
    when(pollableTaskRepository.findForArchiveUpdate(42L)).thenReturn(Optional.of(task));

    PollableTaskArchiveService.BatchResult result = archiveService().archiveFinishedTasks();

    assertThat(result).isEqualTo(new PollableTaskArchiveService.BatchResult(1, 0, 1, 1, 0, 0));
    verify(archiveStorage, never()).putArchive(any());
    verify(pollableTaskRepository).delete(task);
  }

  @Test
  public void queuesFailedTasksAndAdvancesTheDurableWatermark() {
    properties.setDeleteSource(true);
    PollableTask task = task(42L);
    givenCandidate(task);
    when(archiveStorage.findArchive(42L)).thenReturn(Optional.empty());
    doThrow(new IllegalStateException("Azure unavailable")).when(archiveStorage).putArchive(any());

    PollableTaskArchiveService.BatchResult result = archiveService().archiveFinishedTasks();

    assertThat(result).isEqualTo(new PollableTaskArchiveService.BatchResult(1, 0, 0, 0, 0, 1));
    assertThat(retries).containsOnlyKeys(42L);
    assertThat(retries.get(42L).getAttemptCount()).isEqualTo(1);
    assertThat(retries.get(42L).getLastError()).isEqualTo("Azure unavailable");
    assertThat(retries.get(42L).getNextAttemptAt()).isAfter(ZonedDateTime.now());
    assertThat(checkpoint.getLastTaskId()).isEqualTo(42L);
    verify(pollableTaskRepository, never()).delete(any(PollableTask.class));
    assertThat(taskMetric("failed")).isEqualTo(1);
    assertThat(taskMetric("queued_retry")).isEqualTo(1);
  }

  @Test
  public void resumesDueRetriesAfterTheMainKeysetHasAdvanced() {
    PollableTask task = task(42L);
    ArchivedPollableTask archive = ArchivedPollableTask.from(task);
    checkpoint.setLastFinishedDate(task.getFinishedDate());
    checkpoint.setLastTaskId(task.getId());
    PollableTaskArchiveRetry retry = new PollableTaskArchiveRetry();
    retry.setTaskId(task.getId());
    retry.setFinishedDate(task.getFinishedDate());
    retry.setAttemptCount(2);
    retry.setNextAttemptAt(ZonedDateTime.now().minusMinutes(1));
    retries.put(task.getId(), retry);
    when(pollableTaskRepository.findForArchiveRead(task.getId())).thenReturn(Optional.of(task));
    when(archiveStorage.findArchive(task.getId())).thenReturn(Optional.of(archive));

    PollableTaskArchiveService.BatchResult result = archiveService().archiveFinishedTasks();

    assertThat(result).isEqualTo(new PollableTaskArchiveService.BatchResult(1, 0, 1, 0, 0, 0));
    assertThat(retries).isEmpty();
    assertThat(checkpoint.getLastTaskId()).isEqualTo(task.getId());
  }

  @Test
  public void retriesAndNewCandidatesShareOneBatchBudget() {
    properties.setBatchSize(2);
    PollableTask retryTask = task(41L);
    PollableTask firstNew = task(42L);
    PollableTask secondNew = task(43L);
    givenCandidate(secondNew);
    checkpoint.setLastFinishedDate(retryTask.getFinishedDate());
    checkpoint.setLastTaskId(retryTask.getId());
    PollableTaskArchiveRetry retry = new PollableTaskArchiveRetry();
    retry.setTaskId(retryTask.getId());
    retry.setFinishedDate(retryTask.getFinishedDate());
    retry.setAttemptCount(1);
    retry.setNextAttemptAt(ZonedDateTime.now().minusMinutes(1));
    retries.put(retryTask.getId(), retry);
    when(pollableTaskRepository.findForArchiveRead(41L)).thenReturn(Optional.of(retryTask));
    when(pollableTaskRepository.findForArchiveRead(42L)).thenReturn(Optional.of(firstNew));
    when(pollableTaskRepository.findArchiveCandidates(
            any(), anyLong(), any(), any(), anyLong(), any()))
        .thenAnswer(
            invocation ->
                List.of(candidate(firstNew), candidate(secondNew)).stream()
                    .limit(((Pageable) invocation.getArgument(5)).getPageSize())
                    .toList());
    when(archiveStorage.findArchive(41L))
        .thenReturn(Optional.of(ArchivedPollableTask.from(retryTask)));
    when(archiveStorage.findArchive(42L))
        .thenReturn(Optional.of(ArchivedPollableTask.from(firstNew)));

    PollableTaskArchiveService.BatchResult result = archiveService().archiveFinishedTasks();

    assertThat(result.scanned()).isEqualTo(2);
    assertThat(result.verified()).isEqualTo(2);
    assertThat(checkpoint.getLastTaskId()).isEqualTo(42L);
    assertThat(checkpoint.getHighWaterTaskId()).isEqualTo(43L);
    verify(pollableTaskRepository)
        .findArchiveCandidates(
            any(),
            anyLong(),
            any(),
            any(),
            anyLong(),
            eq(org.springframework.data.domain.PageRequest.of(0, 1)));
    verify(archiveStorage, never()).findArchive(43L);
  }

  @Test
  public void skipsNewCandidateFetchWhenRetriesConsumeTheBatch() {
    properties.setBatchSize(1);
    PollableTask task = task(41L);
    PollableTaskArchiveRetry retry = new PollableTaskArchiveRetry();
    retry.setTaskId(task.getId());
    retry.setFinishedDate(task.getFinishedDate());
    retry.setNextAttemptAt(ZonedDateTime.now().minusMinutes(1));
    retries.put(task.getId(), retry);
    givenCandidate(task);
    when(archiveStorage.findArchive(task.getId()))
        .thenReturn(Optional.of(ArchivedPollableTask.from(task)));

    assertThat(archiveService().archiveFinishedTasks().scanned()).isEqualTo(1);

    verify(pollableTaskRepository, never())
        .findArchiveCandidates(any(), anyLong(), any(), any(), anyLong(), any());
  }

  @Test
  public void preservesSourceWhenAzureReadBackDoesNotMatch() {
    properties.setDeleteSource(true);
    PollableTask task = task(42L);
    PollableTask differentTask = task(42L);
    differentTask.setMessage("changed remotely");
    givenCandidate(task);
    when(archiveStorage.findArchive(42L))
        .thenReturn(Optional.empty(), Optional.of(ArchivedPollableTask.from(differentTask)));

    PollableTaskArchiveService.BatchResult result = archiveService().archiveFinishedTasks();

    assertThat(result).isEqualTo(new PollableTaskArchiveService.BatchResult(1, 1, 0, 0, 0, 1));
    assertThat(retries).containsOnlyKeys(42L);
    verify(pollableTaskRepository, never()).delete(any(PollableTask.class));
  }

  @Test
  public void preservesConflictingImmutableArchiveInsteadOfOverwritingIt() {
    properties.setDeleteSource(true);
    PollableTask task = task(42L);
    PollableTask previouslyArchived = task(42L);
    previouslyArchived.setMessage("previous verified snapshot");
    givenCandidate(task);
    when(archiveStorage.findArchive(42L))
        .thenReturn(Optional.of(ArchivedPollableTask.from(previouslyArchived)));

    PollableTaskArchiveService.BatchResult result = archiveService().archiveFinishedTasks();

    assertThat(result.failed()).isEqualTo(1);
    assertThat(retries).containsOnlyKeys(42L);
    verify(archiveStorage, never()).putArchive(any());
    verify(pollableTaskRepository, never()).delete(any(PollableTask.class));
  }

  @Test
  public void retainsTaskTypesWhoseReferencesCannotBeFencedByForeignKeys() {
    properties.setDeleteSource(true);
    for (String name :
        List.of(
            AiReviewChatJob.class.getCanonicalName(),
            AiReviewConfiguredChatJob.class.getCanonicalName(),
            GenerateLocalizedAssetJob.class.getCanonicalName())) {
      PollableTask task = task(42L);
      task.setName(name);
      givenCandidate(task);

      PollableTaskArchiveService.BatchResult result = archiveService().archiveFinishedTasks();

      assertThat(result.skipped()).as(name).isEqualTo(1);
      assertThat(result.deleted()).isZero();
    }
    verify(archiveStorage, never()).findArchive(anyLong());
    verify(pollableTaskRepository, never()).delete(any(PollableTask.class));
  }

  @Test
  public void losingLeaseDuringRemoteIoPreventsDeletionAndPreservesNewOwner() {
    properties.setDeleteSource(true);
    PollableTask task = task(42L);
    givenCandidate(task);
    when(archiveStorage.findArchive(42L))
        .thenAnswer(
            invocation -> {
              checkpoint.setLeaseToken("replacement-worker");
              checkpoint.setLeaseExpiresAt(ZonedDateTime.now().plusMinutes(5));
              return Optional.of(ArchivedPollableTask.from(task));
            });

    assertThatThrownBy(() -> archiveService().archiveFinishedTasks())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lease was lost");

    assertThat(checkpoint.getLeaseToken()).isEqualTo("replacement-worker");
    verify(pollableTaskRepository, never()).delete(any(PollableTask.class));
    verify(pollableTaskRepository, never()).findForArchiveUpdate(anyLong());
  }

  @Test
  public void preservesSourceWhenReferenceAppearsBeforeDeletion() {
    properties.setDeleteSource(true);
    PollableTask task = task(42L);
    ArchivedPollableTask archive = ArchivedPollableTask.from(task);
    givenCandidate(task);
    when(archiveStorage.findArchive(42L)).thenReturn(Optional.of(archive));
    when(pollableTaskRepository.findForArchiveUpdate(42L)).thenReturn(Optional.of(task));
    when(referenceService.hasIncomingReference(42L)).thenReturn(false, true);

    PollableTaskArchiveService.BatchResult result = archiveService().archiveFinishedTasks();

    assertThat(result).isEqualTo(new PollableTaskArchiveService.BatchResult(1, 0, 1, 0, 1, 0));
    verify(pollableTaskRepository, never()).delete(any(PollableTask.class));
    assertThat(taskMetric("changed")).isEqualTo(1);
  }

  @Test
  public void preservesSourceWhenItsContentsChangeBeforeDeletion() {
    properties.setDeleteSource(true);
    PollableTask initialTask = task(42L);
    PollableTask modifiedTask = task(42L);
    modifiedTask.setMessage("new message");
    givenCandidate(initialTask);
    when(archiveStorage.findArchive(42L))
        .thenReturn(Optional.of(ArchivedPollableTask.from(initialTask)));
    when(pollableTaskRepository.findForArchiveUpdate(42L)).thenReturn(Optional.of(modifiedTask));

    PollableTaskArchiveService.BatchResult result = archiveService().archiveFinishedTasks();

    assertThat(result).isEqualTo(new PollableTaskArchiveService.BatchResult(1, 0, 1, 0, 1, 0));
    verify(pollableTaskRepository, never()).delete(any(PollableTask.class));
  }

  @Test
  public void reusesThePersistedCutoffAndHighWaterAcrossRuns() {
    PollableTask task = task(42L);
    ZonedDateTime fixedCutoff = ZonedDateTime.now().minusDays(45);
    checkpoint.setCutoffFinishedBefore(fixedCutoff);
    checkpoint.setHighWaterFinishedDate(task.getFinishedDate());
    checkpoint.setHighWaterTaskId(task.getId());
    ArchiveCandidate candidate = candidate(task);
    when(pollableTaskRepository.findArchiveCandidates(
            any(), anyLong(), eq(fixedCutoff), eq(task.getFinishedDate()), eq(task.getId()), any()))
        .thenReturn(List.of(candidate));
    when(pollableTaskRepository.findForArchiveRead(task.getId())).thenReturn(Optional.of(task));
    when(archiveStorage.findArchive(task.getId()))
        .thenReturn(Optional.of(ArchivedPollableTask.from(task)));

    PollableTaskArchiveService.BatchResult result = archiveService().archiveFinishedTasks();

    assertThat(result.scanned()).isEqualTo(1);
    verify(pollableTaskRepository, never()).findArchiveHighWater(any(), anyLong(), any(), any());
    assertThat(checkpoint.getLastTaskId()).isEqualTo(task.getId());
  }

  @Test
  public void resetsPreviewProgressWhenSourceDeletionIsExplicitlyEnabled() {
    properties.setDeleteSource(true);
    PollableTask task = task(42L);
    checkpoint.setLastFinishedDate(ZonedDateTime.parse("2025-06-01T00:00:00Z"));
    checkpoint.setLastTaskId(900L);
    givenCandidate(task);
    when(archiveStorage.findArchive(42L)).thenReturn(Optional.of(ArchivedPollableTask.from(task)));
    when(pollableTaskRepository.findForArchiveUpdate(42L)).thenReturn(Optional.of(task));

    PollableTaskArchiveService.BatchResult result = archiveService().archiveFinishedTasks();

    assertThat(result.deleted()).isEqualTo(1);
    assertThat(checkpoint.isDeleteSourceMode()).isTrue();
    verify(retryRepository).deleteAllInBatch();
    verify(pollableTaskRepository)
        .findArchiveHighWater(
            eq(PollableTaskArchiveService.INITIAL_FINISHED_DATE), eq(0L), any(), any());
  }

  @Test
  public void refusesOverlappingWorkersWithAnActivePersistedLease() {
    checkpoint.setLeaseToken("another-worker");
    checkpoint.setLeaseExpiresAt(ZonedDateTime.now().plusMinutes(2));

    PollableTaskArchiveService.BatchResult result = archiveService().archiveFinishedTasks();

    assertThat(result).isEqualTo(new PollableTaskArchiveService.BatchResult(0, 0, 0, 0, 0, 0));
    verify(pollableTaskRepository, never()).findArchiveHighWater(any(), anyLong(), any(), any());
    assertThat(taskMetric("busy")).isEqualTo(1);
    assertThat(checkpoint.getLeaseToken()).isEqualTo("another-worker");
  }

  @Test
  public void refusesNonAzureArchiveDestination() {
    when(archiveStorage.isAzureArchiveConfigured()).thenReturn(false);

    assertThatThrownBy(this::archiveService)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("route directly to Azure");
  }

  @Test
  public void rejectsUnsafeRetentionBatchAndLeaseSettings() {
    properties.setRetentionDays(0);
    assertThatThrownBy(this::archiveService)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one day");

    properties.setRetentionDays(30);
    properties.setBatchSize(1001);
    assertThatThrownBy(this::archiveService)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 1 and 1000");

    properties.setBatchSize(10);
    properties.setLeaseSeconds(29);
    assertThatThrownBy(this::archiveService)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 30 seconds");
  }

  private PollableTaskArchiveService archiveService() {
    return new PollableTaskArchiveService(
        pollableTaskRepository,
        checkpointRepository,
        retryRepository,
        referenceService,
        archiveStorage,
        properties,
        transactionManager,
        meterRegistry);
  }

  private void givenCandidate(PollableTask task) {
    ArchiveCandidate candidate = candidate(task);
    when(pollableTaskRepository.findArchiveHighWater(any(), anyLong(), any(), any(Pageable.class)))
        .thenReturn(List.of(candidate));
    when(pollableTaskRepository.findArchiveCandidates(
            any(), anyLong(), any(), any(), anyLong(), any(Pageable.class)))
        .thenReturn(List.of(candidate));
    when(pollableTaskRepository.findForArchiveRead(task.getId())).thenReturn(Optional.of(task));
  }

  private ArchiveCandidate candidate(PollableTask task) {
    return new ArchiveCandidate() {
      @Override
      public Long getId() {
        return task.getId();
      }

      @Override
      public ZonedDateTime getFinishedDate() {
        return task.getFinishedDate();
      }
    };
  }

  private double taskMetric(String result) {
    return meterRegistry
        .get(PollableTaskArchiveService.TASK_METRIC)
        .tag("result", result)
        .counter()
        .count();
  }

  private PollableTask task(long id) {
    PollableTask task = new PollableTask();
    task.setId(id);
    task.setName("ArchivedJob");
    task.setCreatedDate(ZonedDateTime.parse("2025-01-01T10:00:00Z"));
    task.setLastModifiedDate(ZonedDateTime.parse("2025-01-01T10:05:00Z"));
    task.setFinishedDate(ZonedDateTime.parse("2025-01-01T10:05:00Z"));
    task.setMessage("completed");
    task.setSubTasks(new LinkedHashSet<>());
    return task;
  }
}
