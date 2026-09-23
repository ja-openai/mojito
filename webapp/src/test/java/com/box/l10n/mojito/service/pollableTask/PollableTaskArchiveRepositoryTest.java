package com.box.l10n.mojito.service.pollableTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.AssetExtraction;
import com.box.l10n.mojito.entity.Drop;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.PollableTaskArchiveCheckpoint;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexAutomationRun;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRefreshRun;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.IncidentReviewJobAccess;
import com.box.l10n.mojito.service.assetExtraction.AssetExtractionRepository;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.blobstorage.BlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.BlobStorageType;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.drop.DropRepository;
import com.box.l10n.mojito.service.glossary.TermIndexAutomationRunRepository;
import com.box.l10n.mojito.service.glossary.TermIndexRefreshRunRepository;
import com.box.l10n.mojito.service.oaireview.AiReviewChatJobAccess;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.security.user.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public class PollableTaskArchiveRepositoryTest extends ServiceTestBase {

  @Autowired PollableTaskService taskService;

  @Autowired PollableTaskRepository taskRepository;

  @Autowired PollableTaskArchiveCheckpointRepository checkpointRepository;

  @Autowired PollableTaskArchiveRetryRepository retryRepository;

  @Autowired PollableTaskArchiveReferenceService referenceService;

  @Autowired AssetExtractionRepository assetExtractionRepository;

  @Autowired TermIndexRefreshRunRepository refreshRunRepository;

  @Autowired TermIndexAutomationRunRepository automationRunRepository;

  @Autowired DropRepository dropRepository;

  @Autowired PollableTaskBlobStorage taskBlobStorage;

  @Autowired PlatformTransactionManager transactionManager;

  @Autowired UserRepository userRepository;

  @Test
  public void scansFinishedKeysAndChecksEveryIncomingReferenceByTaskId() {
    ZonedDateTime cutoff = ZonedDateTime.now().minusDays(30);
    PollableTask eligible = oldFinishedTask("archive-eligible");
    PollableTask secondEligible = oldFinishedTask("archive-second-eligible");

    PollableTask recent = taskService.createPollableTask(null, "archive-recent", null, 0);
    taskService.finishTask(recent.getId(), null, null, null);

    PollableTask unfinished = taskService.createPollableTask(null, "archive-unfinished", null, 0);

    PollableTask expectsChildren = oldFinishedTask("archive-expects-children");
    expectsChildren.setExpectedSubTaskNumber(1);
    taskRepository.save(expectsChildren);

    PollableTask parent = oldFinishedTask("archive-parent");
    PollableTask child = taskService.createPollableTask(parent.getId(), "archive-child", null, 0);
    finishInThePast(child);

    PollableTask extractionTask = oldFinishedTask("archive-extraction-reference");
    AssetExtraction extraction = new AssetExtraction();
    extraction.setPollableTask(extractionTask);
    assetExtractionRepository.save(extraction);

    PollableTask refreshTask = oldFinishedTask("archive-refresh-reference");
    TermIndexRefreshRun refreshRun = new TermIndexRefreshRun();
    refreshRun.setPollableTaskId(refreshTask.getId());
    refreshRunRepository.save(refreshRun);

    PollableTask automationTask = oldFinishedTask("archive-automation-reference");
    TermIndexAutomationRun automationRun = new TermIndexAutomationRun();
    automationRun.setType(TermIndexAutomationRun.TYPE_GENERATE_CANDIDATES);
    automationRun.setPollableTaskId(automationTask.getId());
    automationRunRepository.save(automationRun);

    PollableTask dropImportTask = oldFinishedTask("archive-drop-import-reference");
    PollableTask dropExportTask = oldFinishedTask("archive-drop-export-reference");
    Drop drop = new Drop();
    drop.setName("archive-reference-drop");
    drop.setImportPollableTask(dropImportTask);
    drop.setExportPollableTask(dropExportTask);
    dropRepository.save(drop);

    PollableTaskRepository.ArchiveCandidate highWater =
        taskRepository
            .findArchiveHighWater(
                PollableTaskArchiveService.INITIAL_FINISHED_DATE, 0, cutoff, PageRequest.of(0, 1))
            .getFirst();
    List<Long> selected =
        taskRepository
            .findArchiveCandidates(
                PollableTaskArchiveService.INITIAL_FINISHED_DATE,
                0,
                cutoff,
                highWater.getFinishedDate(),
                highWater.getId(),
                PageRequest.of(0, 1000))
            .stream()
            .map(candidate -> taskRepository.findById(candidate.getId()).orElseThrow())
            .filter(
                task ->
                    task.getParentTask() == null
                        && task.getExpectedSubTaskNumber() == 0
                        && !referenceService.hasIncomingReference(task.getId()))
            .map(PollableTask::getId)
            .toList();

    assertThat(selected).contains(eligible.getId(), secondEligible.getId());
    assertThat(selected)
        .doesNotContain(
            recent.getId(),
            unfinished.getId(),
            expectsChildren.getId(),
            parent.getId(),
            child.getId(),
            extractionTask.getId(),
            refreshTask.getId(),
            automationTask.getId(),
            dropImportTask.getId(),
            dropExportTask.getId());
  }

  @Test
  public void checksReferencesSeparatelyWhenLockingTaskForDeletion() {
    PollableTask eligible = oldFinishedTask("archive-lock-eligible");
    PollableTask referenced = oldFinishedTask("archive-lock-referenced");
    TermIndexRefreshRun refreshRun = new TermIndexRefreshRun();
    refreshRun.setPollableTaskId(referenced.getId());
    refreshRunRepository.save(refreshRun);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              assertThat(taskRepository.findForArchiveUpdate(eligible.getId())).isPresent();
              assertThat(referenceService.hasIncomingReference(eligible.getId())).isFalse();
              assertThat(taskRepository.findForArchiveUpdate(referenced.getId())).isPresent();
              assertThat(referenceService.hasIncomingReference(referenced.getId())).isTrue();
            });
  }

  @Test
  public void resumesEqualTimestampKeysUnderAFixedHighWater() {
    resetCheckpoint();
    ZonedDateTime sharedFinishedDate = ZonedDateTime.now().minusDays(800).withNano(0);
    PollableTask first = oldFinishedTask("archive-keyset-first");
    first.setFinishedDate(sharedFinishedDate);
    taskRepository.saveAndFlush(first);
    PollableTask second = oldFinishedTask("archive-keyset-second");
    second.setFinishedDate(sharedFinishedDate);
    taskRepository.saveAndFlush(second);

    ArchiveFixture fixture = archiveFixture(790, 1, false);
    PollableTaskArchiveService.BatchResult firstBatch = fixture.service().archiveFinishedTasks();
    PollableTaskArchiveCheckpoint firstCheckpoint = checkpointRepository.findById(1).orElseThrow();
    ZonedDateTime fixedCutoff = firstCheckpoint.getCutoffFinishedBefore();
    Long originalHighWaterId = firstCheckpoint.getHighWaterTaskId();

    PollableTask later = oldFinishedTask("archive-keyset-later");
    later.setFinishedDate(sharedFinishedDate.plusHours(1));
    taskRepository.saveAndFlush(later);

    PollableTaskArchiveService.BatchResult secondBatch = fixture.service().archiveFinishedTasks();
    PollableTaskArchiveCheckpoint secondCheckpoint = checkpointRepository.findById(1).orElseThrow();

    assertThat(firstBatch.scanned()).isEqualTo(1);
    assertThat(secondBatch.scanned()).isEqualTo(1);
    assertThat(firstCheckpoint.getLastTaskId()).isEqualTo(first.getId());
    assertThat(originalHighWaterId).isEqualTo(second.getId());
    assertThat(secondCheckpoint.getLastTaskId()).isEqualTo(second.getId());
    assertThat(secondCheckpoint.getCutoffFinishedBefore()).isNull();
    assertThat(fixture.archiveObjects())
        .containsKeys("v1/" + first.getId() + ".json", "v1/" + second.getId() + ".json");
    assertThat(fixture.archiveObjects()).doesNotContainKey("v1/" + later.getId() + ".json");
    assertThat(fixedCutoff).isNotNull();

    PollableTaskArchiveService.BatchResult thirdBatch = fixture.service().archiveFinishedTasks();

    assertThat(thirdBatch.scanned()).isEqualTo(1);
    assertThat(fixture.archiveObjects()).containsKey("v1/" + later.getId() + ".json");
  }

  @Test
  public void resetsRetainedProgressWhenDeletionModeIsEnabled() {
    resetCheckpoint();
    PollableTask task = oldFinishedTask("archive-preview-then-delete");
    task.setFinishedDate(ZonedDateTime.now().minusDays(3000));
    taskRepository.saveAndFlush(task);

    ArchiveFixture fixture = archiveFixture(2990, 10, false);
    PollableTaskArchiveService.BatchResult preview = fixture.service().archiveFinishedTasks();

    assertThat(preview.verified()).isEqualTo(1);
    assertThat(taskRepository.findById(task.getId())).isPresent();

    fixture.properties().setDeleteSource(true);
    PollableTaskArchiveService deletionService =
        new PollableTaskArchiveService(
            taskRepository,
            checkpointRepository,
            retryRepository,
            referenceService,
            fixture.archiveStorage(),
            fixture.properties(),
            transactionManager,
            new SimpleMeterRegistry());
    PollableTaskArchiveService.BatchResult deletion = deletionService.archiveFinishedTasks();

    assertThat(deletion).isEqualTo(new PollableTaskArchiveService.BatchResult(1, 0, 1, 1, 0, 0));
    assertThat(taskRepository.findById(task.getId())).isEmpty();
    assertThat(checkpointRepository.findById(1).orElseThrow().isDeleteSourceMode()).isTrue();
  }

  @Test
  public void userProfileChangesBetweenPreviewAndDeletionDoNotInvalidateTaskSnapshot() {
    resetCheckpoint();
    User owner = new User();
    owner.setUsername("archive-profile-" + UUID.randomUUID());
    owner.setCommonName("Original archive owner");
    owner.setEnabled(true);
    owner = userRepository.saveAndFlush(owner);
    PollableTask task = oldFinishedTask("archive-profile-change");
    task.setCreatedByUser(owner);
    task.setFinishedDate(ZonedDateTime.now().minusDays(18000));
    taskRepository.saveAndFlush(task);
    ArchiveFixture fixture = archiveFixture(17990, 100, false);

    assertThat(fixture.service().archiveFinishedTasks().verified()).isEqualTo(1);
    String originalJson = fixture.archiveObjects().get("v1/" + task.getId() + ".json");
    owner.setCommonName("Changed live profile");
    owner.setEnabled(false);
    owner.setCanTranslateAllLocales(false);
    userRepository.saveAndFlush(owner);
    fixture.properties().setDeleteSource(true);

    assertThat(restartedArchiveService(fixture).archiveFinishedTasks().deleted()).isEqualTo(1);

    assertThat(taskRepository.findById(task.getId())).isEmpty();
    assertThat(fixture.archiveObjects().get("v1/" + task.getId() + ".json"))
        .isEqualTo(originalJson);
    PollableTask restored = fixture.archiveStorage().findArchivedTask(task.getId()).orElseThrow();
    assertThat(restored.getCreatedByUser().getId()).isEqualTo(owner.getId());
    assertThat(restored.getCreatedByUser().getCommonName()).isEqualTo("Original archive owner");
    assertThat(userRepository.findById(owner.getId()).orElseThrow().getEnabled()).isFalse();
  }

  @Test
  public void restartWithLongerRetentionResetsInterruptedSweepWithoutPermanentlySkippingTasks() {
    resetCheckpoint();
    PollableTask first = oldFinishedTask("archive-policy-first");
    first.setFinishedDate(ZonedDateTime.now().minusDays(16000));
    taskRepository.saveAndFlush(first);
    PollableTask younger = oldFinishedTask("archive-policy-younger");
    younger.setFinishedDate(ZonedDateTime.now().minusDays(100));
    taskRepository.saveAndFlush(younger);
    ArchiveFixture fixture = archiveFixture(90, 1, true);
    fixture.service().archiveFinishedTasks();
    PollableTaskArchiveCheckpoint interrupted = checkpointRepository.findById(1).orElseThrow();
    assertThat(interrupted.getHighWaterFinishedDate()).isNotNull();
    assertThat(taskRepository.findById(younger.getId())).isPresent();

    fixture.properties().setRetentionDays(365);
    restartedArchiveService(fixture).archiveFinishedTasks();

    assertThat(checkpointRepository.findById(1).orElseThrow().getRetentionDays()).isEqualTo(365);
    assertThat(taskRepository.findById(younger.getId())).isPresent();
    assertThat(fixture.archiveObjects()).doesNotContainKey("v1/" + younger.getId() + ".json");

    fixture.properties().setRetentionDays(90);
    fixture.properties().setBatchSize(1000);
    PollableTaskArchiveService resumed = restartedArchiveService(fixture);
    for (int attempts = 0;
        attempts < 10 && taskRepository.findById(younger.getId()).isPresent();
        attempts++) {
      resumed.archiveFinishedTasks();
    }
    assertThat(taskRepository.findById(younger.getId())).isEmpty();
    assertThat(fixture.archiveObjects()).containsKey("v1/" + younger.getId() + ".json");
  }

  @Test
  public void archivesDeletesAndReadsBackStandaloneTaskThroughInspection() {
    resetCheckpoint();
    PollableTask task = oldFinishedTask("archive-end-to-end");
    task.setFinishedDate(ZonedDateTime.now().minusDays(400));
    task.setMessage("completed archive integration test");
    taskRepository.saveAndFlush(task);

    ArchiveFixture fixture = archiveFixture(365, 100, true);
    PollableTaskArchiveService.BatchResult result = fixture.service().archiveFinishedTasks();

    assertThat(result.deleted()).isGreaterThanOrEqualTo(1);
    assertThat(taskRepository.findById(task.getId())).isEmpty();
    assertThat(fixture.archiveObjects()).containsKey("v1/" + task.getId() + ".json");

    PollableTaskService archiveBackedTaskService = new PollableTaskService();
    archiveBackedTaskService.pollableTaskRepository = taskRepository;
    archiveBackedTaskService.pollableTaskArchiveStorage = fixture.archiveStorage();
    archiveBackedTaskService.transactionManager = transactionManager;
    PollableTask restored =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  PollableTask resultTask = archiveBackedTaskService.getPollableTask(task.getId());
                  assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                      .isTrue();
                  return resultTask;
                });
    assertThat(restored.getName()).isEqualTo("archive-end-to-end");
    assertThat(restored.getMessage()).isEqualTo("completed archive integration test");
    assertThat(restored.isAllFinished()).isTrue();

    PollableTaskInspectionService inspectionService =
        new PollableTaskInspectionService(
            archiveBackedTaskService,
            taskBlobStorage,
            mock(RepositoryRepository.class),
            ObjectMapper.withNoFailOnUnknownProperties(),
            mock(AiReviewChatJobAccess.class),
            mock(IncidentReviewJobAccess.class));
    PollableTaskInspectionService.TaskInspection inspection =
        inspectionService.inspectTask(task.getId());
    assertThat(inspection.status()).isEqualTo(PollableTaskInspectionService.TaskStatus.SUCCEEDED);
    assertThat(inspection.subTaskIds()).isEmpty();
    assertThat(inspection.message().asText()).isEqualTo("completed archive integration test");
  }

  private ArchiveFixture archiveFixture(int retentionDays, int batchSize, boolean deleteSource) {
    StructuredBlobStorage blobStorage = mock(StructuredBlobStorage.class);
    Map<String, String> archiveObjects = new HashMap<>();
    when(blobStorage.getString(eq(StructuredBlobStorage.Prefix.POLLABLE_TASK_ARCHIVE), anyString()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return Optional.ofNullable(archiveObjects.get(invocation.getArgument(1)));
            });
    AzureBlobStorage azureStorage = mock(AzureBlobStorage.class);
    when(azureStorage.createPollableTaskArchiveIfAbsent(anyString(), any(byte[].class)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              String key =
                  ((String) invocation.getArgument(0)).substring("pollable_task_archive/".length());
              String content =
                  new String((byte[]) invocation.getArgument(1), StandardCharsets.UTF_8);
              return archiveObjects.putIfAbsent(key, content) == null;
            });

    BlobStorageConfigurationProperties blobProperties = new BlobStorageConfigurationProperties();
    blobProperties.setDefaultType(BlobStorageType.DATABASE);
    blobProperties.getRouting().getPrefixes().put("pollable-task-archive", BlobStorageType.AZURE);
    ObjectMapper objectMapper = ObjectMapper.withNoFailOnUnknownProperties();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    PollableTaskArchiveStorage archiveStorage =
        new PollableTaskArchiveStorage(
            blobStorage,
            blobProperties,
            objectMapper,
            meterRegistry,
            new StaticListableBeanFactory(Map.of("azure", azureStorage))
                .getBeanProvider(AzureBlobStorage.class));
    PollableTaskArchiveProperties archiveProperties = new PollableTaskArchiveProperties();
    archiveProperties.setRetentionDays(retentionDays);
    archiveProperties.setBatchSize(batchSize);
    archiveProperties.setDeleteSource(deleteSource);
    PollableTaskArchiveService archiveService =
        new PollableTaskArchiveService(
            taskRepository,
            checkpointRepository,
            retryRepository,
            referenceService,
            archiveStorage,
            archiveProperties,
            transactionManager,
            meterRegistry);
    return new ArchiveFixture(archiveService, archiveStorage, archiveProperties, archiveObjects);
  }

  private void resetCheckpoint() {
    retryRepository.deleteAllInBatch();
    checkpointRepository.deleteAllInBatch();
  }

  private PollableTaskArchiveService restartedArchiveService(ArchiveFixture fixture) {
    return new PollableTaskArchiveService(
        taskRepository,
        checkpointRepository,
        retryRepository,
        referenceService,
        fixture.archiveStorage(),
        fixture.properties(),
        transactionManager,
        new SimpleMeterRegistry());
  }

  private PollableTask oldFinishedTask(String name) {
    PollableTask task = taskService.createPollableTask(null, name, null, 0);
    return finishInThePast(task);
  }

  private PollableTask finishInThePast(PollableTask task) {
    taskService.finishTask(task.getId(), null, null, null);
    PollableTask finished = taskRepository.findById(task.getId()).orElseThrow();
    finished.setFinishedDate(ZonedDateTime.now().minusDays(60));
    return taskRepository.saveAndFlush(finished);
  }

  private record ArchiveFixture(
      PollableTaskArchiveService service,
      PollableTaskArchiveStorage archiveStorage,
      PollableTaskArchiveProperties properties,
      Map<String, String> archiveObjects) {}
}
