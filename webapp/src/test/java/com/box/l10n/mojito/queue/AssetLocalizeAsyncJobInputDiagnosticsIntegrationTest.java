package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobPayload;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobSubmissionService;
import com.box.l10n.mojito.service.tm.GenerateLocalizedAssetJob;
import com.box.l10n.mojito.test.TestIdWatcher;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.util.ReflectionTestUtils;

/** Real task/blob persistence with an in-memory queue, not atomic durable admission. */
public class AssetLocalizeAsyncJobInputDiagnosticsIntegrationTest extends ServiceTestBase {

  @Autowired PollableTaskService tasks;
  @Autowired StructuredBlobStorage blobs;

  @Autowired
  @Qualifier("fail_on_unknown_properties_false")
  ObjectMapper mapper;

  @Rule public TestIdWatcher testId = new TestIdWatcher();

  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

  @After
  public void closeMetrics() {
    metrics.close();
  }

  @Test
  public void inputSummaryFailureDoesNotRejectQueueSubmission() {
    assertSubmissionSurvivesInputMetric(false);
  }

  @Test
  public void inputDurationFailureDoesNotRejectCommittedInputOrFailTask() {
    assertSubmissionSurvivesInputMetric(true);
  }

  private void assertSubmissionSurvivesInputMetric(boolean duration) {
    String storageType = blobs.getStorageType(StructuredBlobStorage.Prefix.POLLABLE_TASK);
    metrics.gauge(
        duration
            ? "PollableTaskBlobStorage.saveInput.duration"
            : "PollableTaskBlobStorage.saveInput.payloadBytes",
        duration
            ? Tags.of("storageType", storageType, "result", "success")
            : Tags.of("storageType", storageType),
        1);
    // Use a private service instance so the injected registry cannot affect other tests.
    PollableTaskBlobStorage inputs = new PollableTaskBlobStorage();
    ReflectionTestUtils.setField(inputs, "structuredBlobStorage", blobs);
    ReflectionTestUtils.setField(inputs, "objectMapper", mapper);
    ReflectionTestUtils.setField(inputs, "meterRegistry", metrics);
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    var submission =
        new AssetLocalizeAsyncJobSubmissionService(
            tasks,
            inputs,
            new PollableTaskExceptionUtils(),
            new AsyncJobQueueSubmissionService(store, coordinator, metrics),
            mapper,
            metrics);
    LocalizedAssetBody input = new LocalizedAssetBody();
    input.setContent("input-diagnostics-source");
    byte[] originalInput =
        mapper.writeValueAsStringUnchecked(input).getBytes(StandardCharsets.UTF_8);
    var request =
        QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
            .withInput(input)
            .withMessage(testId.getEntityName("inputDiagnostics"))
            .build();

    PollableTask returned = submission.scheduleJob(request).getPollableTask();

    PollableTask persisted = tasks.getPollableTask(returned.getId());
    assertThat(persisted.getFinishedDate()).isNull();
    assertThat(persisted.getErrorMessage()).isNull();
    assertThat(persisted.getErrorStack()).isNull();
    assertThat(inputs.getInputBytes(returned.getId())).containsExactly(originalInput);
    var queued = store.findByStatus("assetlocalize", AsyncJobStatus.QUEUED, 10);
    assertThat(queued).hasSize(1);
    assertThat(queued.getFirst().attemptCount()).isZero();
    assertThat(queued.getFirst().jobData())
        .isEqualTo(
            mapper.writeValueAsStringUnchecked(new AssetLocalizeAsyncJobPayload(returned.getId())));
    verify(coordinator).triggerPollNow("assetlocalize");
    assertThat(
            metrics
                .get("assetLocalizeAsyncJob.schedule")
                .tag("result", "succeeded")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(metrics.find("assetLocalizeAsyncJob.schedule").tag("result", "failed").counter())
        .isNull();
    assertThat(
            metrics
                .find("assetLocalizeAsyncJob.schedule")
                .tag("result", "outcomeUnknown")
                .counter())
        .isNull();
  }
}
