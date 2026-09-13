package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.refEq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.queue.AsyncJobHandlerResult;
import com.box.l10n.mojito.queue.AsyncJobId;
import com.box.l10n.mojito.queue.AsyncJobRecord;
import com.box.l10n.mojito.queue.AsyncJobStatus;
import com.box.l10n.mojito.queue.InMemoryAsyncJobStore;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AssetLocalizeAsyncJobOutputStorageTest {

  @Mock PollableTaskBlobStorage pollableTaskBlobStorage;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Map<String, String> blobs = new ConcurrentHashMap<>();
  private AssetLocalizeAsyncJobOutputStorage outputStorage;

  @Before
  public void setUp() {
    outputStorage =
        new AssetLocalizeAsyncJobOutputStorage(
            new StructuredBlobStorage(null) {
              @Override
              public void put(Prefix prefix, String name, String content, Retention retention) {
                assertThat(prefix).isEqualTo(Prefix.POLLABLE_TASK);
                assertThat(retention).isEqualTo(Retention.MIN_1_DAY);
                blobs.put(name, content);
              }

              @Override
              public Optional<byte[]> getBytes(Prefix prefix, String name) {
                return Optional.ofNullable(blobs.get(name))
                    .map(json -> json.getBytes(StandardCharsets.UTF_8));
              }
            },
            pollableTaskBlobStorage,
            objectMapper);
  }

  @Test
  public void staleLeaseHolderCannotOverwritePublishedWinningOutput() throws Exception {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    String queueName = AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME;
    AsyncJobId id =
        store.enqueueNow(
            queueName,
            objectMapper.writeValueAsStringUnchecked(new AssetLocalizeAsyncJobPayload(42L)));
    AsyncJobRecord stale =
        store.claimNextJobs(queueName, 1, "stale-worker", Duration.ofNanos(1)).get(0);
    PollableTaskService pollableTaskService = mock(PollableTaskService.class);
    PollableTask task = new PollableTask();
    task.setId(42L);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(task);
    LocalizedAssetGenerationService generationService = mock(LocalizedAssetGenerationService.class);
    LocalizedAssetBody staleInput = new LocalizedAssetBody("fr", "stale source");
    LocalizedAssetBody winningInput = new LocalizedAssetBody("fr", "winning source");
    when(pollableTaskBlobStorage.getInputBytes(42L))
        .thenReturn(
            objectMapper.writeValueAsBytes(staleInput),
            objectMapper.writeValueAsBytes(winningInput));
    AtomicReference<LocalizedAssetBody> published = new AtomicReference<>();
    doAnswer(
            invocation -> {
              published.set(invocation.getArgument(1));
              return null;
            })
        .when(pollableTaskBlobStorage)
        .saveOutput(eq(42L), any());

    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    try {
      AssetLocalizeAsyncJobHandler handler =
          new AssetLocalizeAsyncJobHandler(
              pollableTaskService,
              pollableTaskBlobStorage,
              new PollableTaskExceptionUtils(),
              generationService,
              objectMapper,
              meterRegistry,
              outputStorage);
      when(generationService.generate(refEq(winningInput)))
          .thenReturn(new LocalizedAssetBody("fr", "winning translation"));
      CountDownLatch staleGenerating = new CountDownLatch(1);
      CountDownLatch resumeStale = new CountDownLatch(1);
      when(generationService.generate(refEq(staleInput)))
          .thenAnswer(
              invocation -> {
                staleGenerating.countDown();
                assertThat(resumeStale.await(5, TimeUnit.SECONDS)).isTrue();
                return new LocalizedAssetBody("fr", "stale translation");
              });

      var executor = Executors.newSingleThreadExecutor();
      try {
        var staleFuture = executor.submit(() -> handler.process(stale));
        assertThat(staleGenerating.await(5, TimeUnit.SECONDS)).isTrue();
        AsyncJobRecord winner =
            store.claimNextJobs(queueName, 1, "winning-worker", Duration.ofMinutes(1)).get(0);
        AsyncJobHandlerResult result = handler.process(winner);
        assertThat(
                store.markDone(
                    queueName, id, winner.workerId(), winner.leaseToken(), result.jobData()))
            .isTrue();
        handler.onJobDone(store.getByIds(List.of(id)).get(0), result);
        assertThat(published.get().getContent()).isEqualTo("winning translation");

        resumeStale.countDown();
        AsyncJobHandlerResult staleResult = staleFuture.get(5, TimeUnit.SECONDS);

        assertThat(
                store.markDone(
                    queueName, id, stale.workerId(), stale.leaseToken(), staleResult.jobData()))
            .isFalse();
        assertThat(published.get().getContent()).isEqualTo("winning translation");
        assertThat(blobs).hasSize(2);
        verify(pollableTaskBlobStorage).saveOutput(eq(42L), any());
        verify(pollableTaskService).finishTask(42L, null, null, null);
      } finally {
        resumeStale.countDown();
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
    } finally {
      meterRegistry.close();
    }
  }

  @Test
  public void durablePointerAllowsPublicationAfterWorkerRestart() {
    AssetLocalizeAsyncJobPayload winner =
        outputStorage.saveAttemptOutput(42L, new LocalizedAssetBody("fr", "winning translation"));
    outputStorage.saveAttemptOutput(42L, new LocalizedAssetBody("fr", "stale translation"));
    verifyNoInteractions(pollableTaskBlobStorage);

    AssetLocalizeAsyncJobPayload persisted =
        objectMapper.readValueUnchecked(
            objectMapper.writeValueAsStringUnchecked(winner), AssetLocalizeAsyncJobPayload.class);
    setUp();
    outputStorage.publishOutput(persisted);

    var outputCaptor = org.mockito.ArgumentCaptor.forClass(LocalizedAssetBody.class);
    verify(pollableTaskBlobStorage).saveOutput(eq(42L), outputCaptor.capture());
    assertThat(outputCaptor.getValue().getContent()).isEqualTo("winning translation");
  }

  @Test
  public void missingAttemptOutputIsNotReplacedWithAnotherAttemptsCanonicalOutput() {
    AssetLocalizeAsyncJobPayload winner =
        outputStorage.saveAttemptOutput(42L, new LocalizedAssetBody("fr", "winning translation"));
    blobs.clear();

    assertThatThrownBy(() -> outputStorage.publishOutput(winner))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Missing assetlocalize attempt output");

    verifyNoInteractions(pollableTaskBlobStorage);
  }

  @Test
  public void missingPrivateOutputCannotFinishRepair() {
    AssetLocalizeAsyncJobPayload winner =
        outputStorage.saveAttemptOutput(42L, new LocalizedAssetBody("fr", "winning translation"));
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    String queueName = AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME;
    AsyncJobId id = store.enqueueNow(queueName, objectMapper.writeValueAsStringUnchecked(winner));
    AsyncJobRecord claimed =
        store.claimNextJobs(queueName, 1, "worker", Duration.ofMinutes(1)).get(0);
    assertThat(store.markDone(queueName, id, claimed.workerId(), claimed.leaseToken(), null))
        .isTrue();
    blobs.clear();
    PollableTaskService pollableTaskService = mock(PollableTaskService.class);
    PollableTask task = new PollableTask();
    task.setId(42L);
    when(pollableTaskService.getFreshPollableTask(42L)).thenReturn(task);

    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    try {
      AssetLocalizeAsyncJobRepairService repairService =
          new AssetLocalizeAsyncJobRepairService(
              store, pollableTaskService, meterRegistry, outputStorage);

      assertThatThrownBy(() -> repairService.repairTerminalPollableTask(id.value()))
          .isInstanceOf(
              AssetLocalizeAsyncJobRepairService.AssetLocalizePollableTaskRepairException.class)
          .hasRootCauseMessage("Missing assetlocalize attempt output for pollable task: 42");

      assertThat(store.getByIds(List.of(id)).get(0).status()).isEqualTo(AsyncJobStatus.DONE);
      verify(pollableTaskService, never()).finishTask(eq(42L), any(), any(), any());
      verifyNoInteractions(pollableTaskBlobStorage);
    } finally {
      meterRegistry.close();
    }
  }

  @Test
  public void legacyPayloadUsesExistingCanonicalOutput() {
    AssetLocalizeAsyncJobPayload legacy =
        objectMapper.readValueUnchecked(
            "{\"pollableTaskId\":42}", AssetLocalizeAsyncJobPayload.class);
    when(pollableTaskBlobStorage.getOutputBytes(42L))
        .thenReturn(
            "{\"bcp47Tag\":\"fr\",\"content\":\"legacy output\"}".getBytes(StandardCharsets.UTF_8));

    outputStorage.publishOutput(legacy);

    assertThat(legacy.outputId()).isNull();
    assertThat(objectMapper.writeValueAsStringUnchecked(legacy))
        .isEqualTo("{\"pollableTaskId\":42}");
    verify(pollableTaskBlobStorage).getOutputBytes(42L);
    verify(pollableTaskBlobStorage, never()).saveOutput(eq(42L), any());
  }

  @Test
  public void legacyPayloadWithExpiredOutputCannotBePublished() {
    doThrow(new IllegalStateException("output expired"))
        .when(pollableTaskBlobStorage)
        .getOutputBytes(42L);

    assertThatThrownBy(() -> outputStorage.publishOutput(new AssetLocalizeAsyncJobPayload(42L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("output expired");
  }

  @Test
  public void rejectsTrailingDocumentInRetainedOutput() {
    assertInvalidRetainedOutput(
        "{\"bcp47Tag\":\"fr\",\"content\":\"translation\"} {\"content\":\"other\"}");
  }

  @Test
  public void rejectsDuplicateFieldsInRetainedOutput() {
    assertInvalidRetainedOutput(
        "{\"bcp47Tag\":\"fr\",\"content\":\"translation\",\"content\":\"other\"}");
  }

  @Test
  public void malformedRetainedOutputDoesNotExposeBlobDataInException() {
    assertInvalidRetainedOutput("{\"private-translation-marker\":\"sensitive source\"}");
  }

  @Test
  public void rejectsEscapedUnpairedSurrogateInRetainedOutput() {
    assertInvalidRetainedOutput("{\"content\":\"private-translation-marker\\ud800\"}");
  }

  @Test
  public void rejectsEscapedUnpairedSurrogateInLegacyOutput() {
    when(pollableTaskBlobStorage.getOutputBytes(42L))
        .thenReturn(
            "{\"content\":\"private-translation-marker\\udc00\"}".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> outputStorage.publishOutput(new AssetLocalizeAsyncJobPayload(42L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Invalid assetlocalize output for pollable task: 42")
        .hasNoCause();

    verify(pollableTaskBlobStorage, never()).saveOutput(any(), any());
    assertThat(blobs).isEmpty();
  }

  @Test
  public void rejectsMalformedGeneratedOutputBeforeItsStringsCanBeLossilyEncoded() {
    LocalizedAssetBody output = new LocalizedAssetBody("fr", "private-translation-marker\ud800");

    assertThatThrownBy(() -> outputStorage.saveAttemptOutput(42L, output))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Invalid assetlocalize output for pollable task: 42")
        .hasNoCause();

    assertThat(blobs).isEmpty();
    verifyNoInteractions(pollableTaskBlobStorage);
  }

  @Test
  public void rejectsInvalidLegacyOutputWithoutRewritingIt() {
    for (String json :
        List.of(
            "{\"content\":\"first\"} {\"content\":\"second\"}",
            "{\"content\":\"first\",\"content\":\"second\"}",
            "{\"private-translation-marker\":\"sensitive source\"}",
            "null",
            "[{}]",
            "{\"content\":\"unfinished")) {
      when(pollableTaskBlobStorage.getOutputBytes(42L))
          .thenReturn(json.getBytes(StandardCharsets.UTF_8));

      assertThatThrownBy(() -> outputStorage.publishOutput(new AssetLocalizeAsyncJobPayload(42L)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Invalid assetlocalize output for pollable task: 42")
          .hasNoCause();
    }
    verify(pollableTaskBlobStorage, never()).saveOutput(any(), any());
    assertThat(blobs).isEmpty();
  }

  @Test
  public void framingValidationDoesNotMutateSharedMapperConfiguration() {
    objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    setUp();
    AssetLocalizeAsyncJobPayload payload =
        outputStorage.saveAttemptOutput(42L, new LocalizedAssetBody("fr", "translation"));
    blobs.replaceAll(
        (key, value) -> "{\"content\":\"translation\",\"futureField\":\"compatible\"}");

    outputStorage.publishOutput(payload);

    var output = org.mockito.ArgumentCaptor.forClass(LocalizedAssetBody.class);
    verify(pollableTaskBlobStorage).saveOutput(eq(42L), output.capture());
    assertThat(output.getValue().getContent()).isEqualTo("translation");
    assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
    assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isFalse();
    assertThat(objectMapper.isEnabled(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)).isFalse();
  }

  @Test
  public void outputValidationPreservesEmptyAndNullableOutputFields() {
    for (String content : new String[] {"", null, "\0\nfran\u00e7ais"}) {
      LocalizedAssetBody body = new LocalizedAssetBody("fr", content);
      AssetLocalizeAsyncJobPayload payload = outputStorage.saveAttemptOutput(42L, body);
      outputStorage.publishOutput(payload);
      when(pollableTaskBlobStorage.getOutputBytes(42L))
          .thenReturn(objectMapper.writeValueAsBytes(body));
      outputStorage.publishOutput(new AssetLocalizeAsyncJobPayload(42L));
    }
    var outputs = org.mockito.ArgumentCaptor.forClass(LocalizedAssetBody.class);
    verify(pollableTaskBlobStorage, org.mockito.Mockito.times(3))
        .saveOutput(eq(42L), outputs.capture());
    assertThat(outputs.getAllValues())
        .extracting(LocalizedAssetBody::getContent)
        .containsExactly("", null, "\0\nfran\u00e7ais");
  }

  private void assertInvalidRetainedOutput(String json) {
    AssetLocalizeAsyncJobPayload payload =
        outputStorage.saveAttemptOutput(42L, new LocalizedAssetBody("fr", "winning translation"));
    blobs.replaceAll((key, value) -> json);

    assertThatThrownBy(() -> outputStorage.publishOutput(payload))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Invalid assetlocalize output for pollable task: 42")
        .hasNoCause();

    verifyNoInteractions(pollableTaskBlobStorage);
    assertThat(blobs.values()).containsExactly(json);
  }

  @Test
  public void rejectsOutputIdsOutsideAttemptNamespace() {
    assertThatThrownBy(() -> new AssetLocalizeAsyncJobPayload(42L, "../../43/output"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AssetLocalizeAsyncJobPayload(42L, "0-0-0-0-1"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
