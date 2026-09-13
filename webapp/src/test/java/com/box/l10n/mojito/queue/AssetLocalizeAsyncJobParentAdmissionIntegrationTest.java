package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzPollableJob;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.queue.CommitFaultDataSource.CommitFault;
import com.box.l10n.mojito.rest.asset.LocaleInfo;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.rest.asset.MultiLocalizedAssetBody;
import com.box.l10n.mojito.service.asset.AssetService;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobPayload;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobSubmissionService;
import com.box.l10n.mojito.service.tm.GenerateMultiLocalizedAssetJob;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Characterizes the legacy parent's unsafe retry boundary, not durable fan-out recovery. Actual
 * application tasks/blobs use an isolated HSQL Spring context; queue INSERT/commit faults use a
 * second disposable HSQL database. No worker or Quartz scheduler runs. This does not establish
 * atomic JPA/JDBC admission, real network faults or production-dialect behavior.
 */
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:hsqldb:mem:queue_parent_admission;DB_CLOSE_DELAY=-1",
      "l10n.blob-storage.database.cleanup-enabled=false",
      "l10n.org.quartz.scheduler.enabled=false"
    })
public class AssetLocalizeAsyncJobParentAdmissionIntegrationTest extends ServiceTestBase {

  private static final String QUEUE = AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME;
  private static final List<String> TAGS = List.of("fr-FR", "de-DE", "ja-JP");

  @Autowired ApplicationContext applicationContext;
  @Autowired RepositoryService repositoryService;
  @Autowired RepositoryLocaleRepository repositoryLocaleRepository;
  @Autowired AssetService assetService;
  @Autowired PollableTaskService tasks;
  @Autowired PollableTaskBlobStorage blobs;
  @Autowired PollableTaskExceptionUtils exceptions;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate applicationJdbc;

  @Test
  public void committedSecondChildIsUnmappedAndParentReplayDuplicatesAcceptedWork()
      throws Exception {
    assertUnsafeParentReplay(CommitFault.AFTER_COMMIT);
  }

  @Test
  public void rolledBackSecondChildLeavesOrphanTaskAndReplayStillDuplicatesFirstChild()
      throws Exception {
    assertUnsafeParentReplay(CommitFault.ROLLBACK_BEFORE_COMMIT);
  }

  @Test
  public void acknowledgedFanOutPublishesAllChildMappingsWithoutCompletingChildren()
      throws Exception {
    try (QueueFixture queue = new QueueFixture(CommitFault.NONE)) {
      ParentFixture parent = createParent(queue);

      parent.job().execute(parent.context());

      List<Long> childIds = childIds(parent.id());
      assertThat(childIds).hasSize(TAGS.size());
      assertPendingChildren(childIds);
      assertThat(queuedTaskIds(queue)).containsExactlyElementsOf(childIds);
      assertMapping(parent.id(), childIds);
      PollableTask finishedParent = tasks.getPollableTask(parent.id());
      assertThat(finishedParent.getFinishedDate()).isNotNull();
      assertThat(finishedParent.getErrorMessage()).isNull();
      assertThat(finishedParent.getExpectedSubTaskNumber()).isEqualTo(TAGS.size());
      assertThat(finishedParent.isAllFinished()).isFalse();
      assertThat(queue.dataSource.faults.get()).isZero();
      assertThat(queue.inserts.get()).isEqualTo(TAGS.size());
      verifyNoInteractions(parent.quartz());
    }
  }

  private void assertUnsafeParentReplay(CommitFault fault) throws Exception {
    try (QueueFixture queue = new QueueFixture(fault)) {
      ParentFixture parent = createParent(queue);
      byte[] originalInput = blobs.getInputBytes(parent.id());

      // Run the real QuartzPollableJob wrapper, including its catch/finally task completion.
      parent.job().execute(parent.context());

      List<Long> originalChildren = childIds(parent.id());
      assertThat(originalChildren).hasSize(2);
      assertPendingChildren(originalChildren);
      assertThat(parent.lastInput().get().getGenerateLocalizedAssetJobIds())
          .containsExactlyEntriesOf(Map.of(TAGS.getFirst(), originalChildren.getFirst()));
      assertThat(blobs.findOutputJson(parent.id())).isEmpty();
      assertThat(blobs.getInputBytes(parent.id())).containsExactly(originalInput);
      List<Long> acceptedChildren =
          fault == CommitFault.AFTER_COMMIT
              ? originalChildren
              : List.of(originalChildren.getFirst());
      assertThat(queuedTaskIds(queue)).containsExactlyElementsOf(acceptedChildren);
      List<AsyncJobRecord> acceptedRows =
          queue.store.findByStatus(QUEUE, AsyncJobStatus.QUEUED, 10);
      for (int slot = 0; slot < originalChildren.size(); slot++) {
        LocalizedAssetBody child =
            blobs.getInput(originalChildren.get(slot), LocalizedAssetBody.class);
        assertThat(child.getLocaleId()).isEqualTo(parent.localeIds().get(slot));
        assertThat(child.getContent()).isEqualTo("parent admission fixture");
      }
      PollableTask failedParent = tasks.getPollableTask(parent.id());
      assertThat(failedParent.getFinishedDate()).isNotNull();
      assertThat(failedParent.getExpectedSubTaskNumber()).isEqualTo(TAGS.size());
      assertThat(failedParent.isAllFinished()).isFalse();
      assertThat(failedParent.getErrorStack()).contains(queue.dataSource.failure.getMessage());
      String originalError = failedParent.getErrorMessage();
      assertThat(originalError).isNotNull();
      assertThat(queue.dataSource.faults.get()).isEqualTo(1);
      assertThat(queue.inserts.get()).isEqualTo(2);
      assertThat(
              queue
                  .metrics
                  .get("assetLocalizeAsyncJob.schedule")
                  .tags("queueName", QUEUE, "result", "outcomeUnknown")
                  .counter()
                  .count())
          .isEqualTo(1);

      // Deliberate unsafe replay of the same task/input, not a supported recovery operation.
      // This fresh job instance has no earlier in-memory map; the one-shot fault is consumed.
      ParentFixture replay = parentJob(parent.id(), parent.localeIds(), queue);
      replay.job().execute(replay.context());

      List<Long> allChildren = childIds(parent.id());
      assertThat(allChildren)
          .hasSize(2 + TAGS.size())
          .startsWith(originalChildren.toArray(Long[]::new));
      List<Long> replayChildren = allChildren.subList(originalChildren.size(), allChildren.size());
      assertThat(replayChildren).doesNotContainAnyElementsOf(originalChildren);
      assertPendingChildren(allChildren);
      List<Long> expectedQueued = new ArrayList<>(acceptedChildren);
      expectedQueued.addAll(replayChildren);
      assertThat(queuedTaskIds(queue)).containsExactlyElementsOf(expectedQueued);
      assertThat(queue.store.getByIds(acceptedRows.stream().map(AsyncJobRecord::id).toList()))
          .containsExactlyInAnyOrderElementsOf(acceptedRows);
      assertMapping(parent.id(), replayChildren);
      for (int slot = 0; slot < acceptedChildren.size(); slot++) {
        assertThat(blobs.getInputBytes(replayChildren.get(slot)))
            .containsExactly(blobs.getInputBytes(originalChildren.get(slot)));
      }
      PollableTask replayedParent = tasks.getPollableTask(parent.id());
      assertThat(replayedParent.getFinishedDate()).isNotNull();
      assertThat(replayedParent.getErrorMessage()).isEqualTo(originalError);
      assertThat(replayedParent.getErrorStack()).isEqualTo(failedParent.getErrorStack());
      assertThat(replayedParent.getExpectedSubTaskNumber()).isEqualTo(TAGS.size());
      assertThat(replayedParent.isAllFinished()).isFalse();
      assertThat(blobs.getInputBytes(parent.id())).containsExactly(originalInput);
      assertThat(queue.dataSource.faults.get()).isEqualTo(1);
      assertThat(queue.inserts.get()).isEqualTo(2 + TAGS.size());
      verifyNoInteractions(parent.quartz(), replay.quartz());
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(TransactionSynchronizationManager.hasResource(queue.dataSource)).isFalse();
    }
  }

  private ParentFixture createParent(QueueFixture queue) throws Exception {
    assertThat(applicationContext.containsBean("triggerExpiringBlobCleanup")).isFalse();
    Repository repository =
        repositoryService.createRepository("parent-admission-" + UUID.randomUUID());
    List<Long> localeIds = new ArrayList<>();
    MultiLocalizedAssetBody body = new MultiLocalizedAssetBody();
    List<LocaleInfo> locales = new ArrayList<>();
    for (String tag : TAGS) {
      repositoryService.addRepositoryLocale(repository, tag);
      long id =
          repositoryLocaleRepository
              .findByRepositoryAndLocale_Bcp47Tag(repository, tag)
              .getLocale()
              .getId();
      localeIds.add(id);
      LocaleInfo locale = new LocaleInfo();
      locale.setLocaleId(id);
      locales.add(locale);
    }
    Asset asset = assetService.createAssetWithContent(repository.getId(), "parent.txt", "fixture");
    body.setAssetId(asset.getId());
    body.setSourceContent("parent admission fixture");
    body.setLocaleInfos(locales);
    PollableTask parent =
        tasks.createPollableTask(
            null,
            GenerateMultiLocalizedAssetJob.class.getCanonicalName(),
            "fan-out admission",
            TAGS.size(),
            3600);
    blobs.saveInput(parent.getId(), body);
    return parentJob(parent.getId(), localeIds, queue);
  }

  private ParentFixture parentJob(long parentId, List<Long> localeIds, QueueFixture queue)
      throws Exception {
    GenerateMultiLocalizedAssetJob job = spy(new GenerateMultiLocalizedAssetJob());
    applicationContext.getAutowireCapableBeanFactory().autowireBean(job);
    AssetLocalizeAsyncJobSubmissionService submission =
        new AssetLocalizeAsyncJobSubmissionService(
            tasks,
            blobs,
            exceptions,
            new AsyncJobQueueSubmissionService(
                queue.store, mock(AsyncJobQueueCoordinator.class), queue.metrics),
            mapper,
            queue.metrics);
    QuartzPollableTaskScheduler quartz = mock(QuartzPollableTaskScheduler.class);
    ReflectionTestUtils.setField(job, "assetLocalizeAsyncJobSubmissionService", submission);
    ReflectionTestUtils.setField(job, "quartzPollableTaskScheduler", quartz);
    ReflectionTestUtils.setField(job, "asyncJobQueueEnabled", true);
    ReflectionTestUtils.setField(job, "asyncJobQueueAssetLocalizeEnabled", true);
    ReflectionTestUtils.setField(job, "asyncJobQueueAssetLocalizeProducerEnabled", true);
    assertThat(ReflectionTestUtils.getField(job, "asyncJobQueueAssetLocalizeFanoutEnabled"))
        .as("the real application property binding defaults fan-out to Quartz")
        .isEqualTo(false);
    ReflectionTestUtils.setField(job, "asyncJobQueueAssetLocalizeFanoutEnabled", true);
    ReflectionTestUtils.setField(job, "meterRegistry", queue.metrics);
    AtomicReference<MultiLocalizedAssetBody> lastInput = new AtomicReference<>();
    doAnswer(
            invocation -> {
              lastInput.set(invocation.getArgument(0));
              return invocation.callRealMethod();
            })
        .when(job)
        .call(any(MultiLocalizedAssetBody.class));
    JobDataMap data = new JobDataMap();
    data.put(QuartzPollableJob.POLLABLE_TASK_ID, parentId);
    JobExecutionContext context = mock(JobExecutionContext.class);
    when(context.getMergedJobDataMap()).thenReturn(data);
    return new ParentFixture(parentId, localeIds, job, context, quartz, lastInput);
  }

  private List<Long> childIds(long parentId) {
    return applicationJdbc.queryForList(
        "SELECT id FROM pollable_task WHERE parent_task_id = ? ORDER BY id", Long.class, parentId);
  }

  private List<Long> queuedTaskIds(QueueFixture queue) {
    return queue
        .jdbc
        .queryForList("SELECT job_data FROM async_job_queue ORDER BY id", String.class)
        .stream()
        .map(json -> mapper.readValueUnchecked(json, AssetLocalizeAsyncJobPayload.class))
        .map(AssetLocalizeAsyncJobPayload::pollableTaskId)
        .toList();
  }

  private void assertPendingChildren(List<Long> ids) {
    for (Long id : ids) {
      PollableTask task = tasks.getPollableTask(id);
      assertThat(task.getFinishedDate()).isNull();
      assertThat(task.getErrorMessage()).isNull();
      assertThat(task.getErrorStack()).isNull();
    }
  }

  private void assertMapping(long parentId, List<Long> ids) {
    Map<String, Long> mapping =
        blobs.getOutput(parentId, MultiLocalizedAssetBody.class).getGenerateLocalizedAssetJobIds();
    assertThat(mapping).hasSize(TAGS.size());
    for (int i = 0; i < TAGS.size(); i++) {
      assertThat(mapping).containsEntry(TAGS.get(i), ids.get(i));
    }
  }

  private record ParentFixture(
      long id,
      List<Long> localeIds,
      GenerateMultiLocalizedAssetJob job,
      JobExecutionContext context,
      QuartzPollableTaskScheduler quartz,
      AtomicReference<MultiLocalizedAssetBody> lastInput) {}

  private static final class QueueFixture implements AutoCloseable {
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final AtomicInteger inserts = new AtomicInteger();
    final CommitFaultDataSource dataSource;
    final JdbcTemplate jdbc;
    final JdbcAsyncJobStore store;

    QueueFixture(CommitFault fault) {
      DriverManagerDataSource target =
          new DriverManagerDataSource(
              "jdbc:hsqldb:mem:parent_queue_" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", "");
      dataSource = new CommitFaultDataSource(target);
      jdbc = new JdbcTemplate(dataSource);
      jdbc.setQueryTimeout(10);
      jdbc.execute(
          """
          CREATE TABLE async_job_queue (
            id BIGINT GENERATED BY DEFAULT AS IDENTITY(START WITH 1) PRIMARY KEY,
            queue_name VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL,
            available_at TIMESTAMP(6) NOT NULL, lease_until TIMESTAMP(6),
            worker_id VARCHAR(128), lease_token VARCHAR(64), job_data LONGVARCHAR NOT NULL,
            attempt_count INTEGER DEFAULT 0 NOT NULL, last_error LONGVARCHAR,
            created_date TIMESTAMP(6) NOT NULL, updated_date TIMESTAMP(6) NOT NULL
          )
          """);
      NamedParameterJdbcTemplate named =
          new NamedParameterJdbcTemplate(jdbc) {
            @Override
            public int update(
                String sql, SqlParameterSource parameters, KeyHolder keys, String[] columns) {
              int result = super.update(sql, parameters, keys, columns);
              if (inserts.incrementAndGet() == 2 && fault != CommitFault.NONE) {
                // Arm only after the second queue INSERT; task/blob commits use the application DB.
                dataSource.failNextCommit(fault);
              }
              return result;
            }
          };
      store =
          new JdbcAsyncJobStore(
              named, AsyncJobQueueJdbcDialect.HSQL, new DataSourceTransactionManager(dataSource));
    }

    @Override
    public void close() {
      try {
        jdbc.execute("SHUTDOWN");
      } finally {
        metrics.close();
      }
    }
  }
}
