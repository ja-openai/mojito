package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.queue.AsyncJobHandlerResult;
import com.box.l10n.mojito.queue.AsyncJobId;
import com.box.l10n.mojito.queue.AsyncJobRecord;
import com.box.l10n.mojito.queue.AsyncJobStatus;
import com.box.l10n.mojito.queue.InMemoryAsyncJobStore;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.blobstorage.BlobStorage;
import com.box.l10n.mojito.service.blobstorage.BlobStorageRouter;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Exercise actual byte-to-text reads, not String-returning storage mocks. */
public class AssetLocalizeAsyncJobOutputEncodingTest {

  private static final String CANONICAL = "pollable_task/42/output";
  private static final String OUTPUT_ID = UUID.randomUUID().toString();
  private static final String PRIVATE = "pollable_task/42/assetlocalize/" + OUTPUT_ID + "/output";
  private static final byte[] ORIGINAL =
      "{\"content\":\"original output\"}".getBytes(StandardCharsets.UTF_8);

  private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
  private final ObjectMapper mapper = new ObjectMapper();
  private final InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final PollableTaskService tasks = mock(PollableTaskService.class);
  private final PollableTaskBlobStorage taskBlobs = new PollableTaskBlobStorage();
  private RuntimeException readFailure;
  private AssetLocalizeAsyncJobHandler handler;
  private AssetLocalizeAsyncJobRepairService repair;

  @Before
  public void setUp() {
    BlobStorage bytes =
        new BlobStorage() {
          @Override
          public Optional<byte[]> getBytes(String name) {
            if (readFailure != null) throw readFailure;
            return Optional.ofNullable(blobs.get(name)).map(byte[]::clone);
          }

          @Override
          public void put(String name, byte[] content, Retention retention) {
            blobs.put(name, content.clone());
          }

          @Override
          public void delete(String name) {
            blobs.remove(name);
          }

          @Override
          public boolean exists(String name) {
            return blobs.containsKey(name);
          }
        };
    BlobStorageRouter router = mock(BlobStorageRouter.class);
    when(router.getBlobStorage(StructuredBlobStorage.Prefix.POLLABLE_TASK)).thenReturn(bytes);
    StructuredBlobStorage structured = new StructuredBlobStorage(router);
    ReflectionTestUtils.setField(taskBlobs, "structuredBlobStorage", structured);
    ReflectionTestUtils.setField(taskBlobs, "objectMapper", mapper);
    var outputs = new AssetLocalizeAsyncJobOutputStorage(structured, taskBlobs, mapper);
    handler =
        new AssetLocalizeAsyncJobHandler(
            tasks,
            taskBlobs,
            new PollableTaskExceptionUtils(),
            mock(LocalizedAssetGenerationService.class),
            mapper,
            meters,
            outputs);
    repair = new AssetLocalizeAsyncJobRepairService(store, tasks, meters, outputs);
    PollableTask task = new PollableTask();
    task.setId(42L);
    when(tasks.getPollableTask(42L)).thenReturn(task);
    when(tasks.getFreshPollableTask(42L)).thenReturn(task);
  }

  @After
  public void close() {
    meters.close();
  }

  @Test
  public void completionRejectsMalformedPrivateBytesBeforePublishingOrFinishing() {
    assertMalformedOutputRejected(false, false);
  }

  @Test
  public void completionRejectsMalformedLegacyBytesBeforeFinishing() {
    assertMalformedOutputRejected(true, false);
  }

  @Test
  public void repairRejectsMalformedPrivateBytesBeforePublishingOrFinishing() {
    assertMalformedOutputRejected(false, true);
  }

  @Test
  public void repairRejectsMalformedLegacyBytesBeforeFinishing() {
    assertMalformedOutputRejected(true, true);
  }

  @Test
  public void validReplacementAndSupplementaryCharactersRemainPublishable() {
    String content = "literal \ufffd, \ufeff, \ud83d\ude00, fran\u00e7ais\n\u0000";
    byte[] json = mapper.writeValueAsBytes(new LocalizedAssetBody("fr", content));
    blobs.put(PRIVATE, json);
    AsyncJobRecord record = terminal(false);

    handler.onJobDone(record, AsyncJobHandlerResult.done(record.jobData()));

    assertThat(taskBlobs.getOutput(42L, LocalizedAssetBody.class).getContent()).isEqualTo(content);
    assertThat(blobs.get(PRIVATE)).containsExactly(json);
    verify(tasks).finishTask(42L, null, null, null);
  }

  @Test
  public void validLegacyOutputRemainsUnchangedDuringRepair() {
    byte[] json = "{\"content\":\"literal \ufffd \ud83d\ude00\"}".getBytes(StandardCharsets.UTF_8);
    blobs.put(CANONICAL, json);
    AsyncJobRecord record = terminal(true);

    assertThat(repair.repairTerminalPollableTask(record.id().value()).result())
        .isEqualTo("repaired");

    assertThat(blobs.get(CANONICAL)).containsExactly(json);
    assertThat(blobs).hasSize(1);
    verify(tasks).finishTask(42L, null, null, null);
  }

  @Test
  public void readFailuresRemainStorageFailuresForBothFormats() {
    readFailure = new IllegalStateException("storage unavailable");
    for (boolean legacy : new boolean[] {false, true}) {
      AsyncJobRecord record = terminal(legacy);
      assertThatThrownBy(
              () -> handler.onJobDone(record, AsyncJobHandlerResult.done(record.jobData())))
          .isSameAs(readFailure);
      assertThatThrownBy(() -> repair.repairTerminalPollableTask(record.id().value()))
          .hasCause(readFailure);
    }
    verify(tasks, never()).finishTask(eq(42L), any(), any(), any());
    assertThat(blobs).isEmpty();
  }

  @Test
  public void legacyStringStorageReaderStillReplacesMalformedBytes() {
    blobs.put(CANONICAL, malformedContent(new byte[] {(byte) 0x80}));

    assertThat(taskBlobs.getOutputJson(42L)).contains("\ufffd");
  }

  private void assertMalformedOutputRejected(boolean legacy, boolean useRepair) {
    AsyncJobRecord record = terminal(legacy);
    for (byte[] malformed :
        List.of(
            malformedContent(new byte[] {(byte) 0x80}),
            malformedContent(new byte[] {(byte) 0xc0, (byte) 0xaf}),
            malformedContent(new byte[] {(byte) 0xe2, (byte) 0x82}),
            malformedContent(new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80}),
            malformedContent(new byte[] {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80}),
            "{\"content\":\"UTF-16\"}".getBytes(StandardCharsets.UTF_16LE),
            "{\"content\":\"UTF-16\"}".getBytes(StandardCharsets.UTF_16BE),
            "\ufeff{\"content\":\"framing BOM\"}".getBytes(StandardCharsets.UTF_8))) {
      blobs.put(CANONICAL, ORIGINAL.clone());
      blobs.put(legacy ? CANONICAL : PRIVATE, malformed);
      if (useRepair) {
        assertThatThrownBy(() -> repair.repairTerminalPollableTask(record.id().value()))
            .isInstanceOf(
                AssetLocalizeAsyncJobRepairService.AssetLocalizePollableTaskRepairException.class)
            .cause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Invalid assetlocalize output for pollable task: 42")
            .hasNoCause();
      } else {
        assertThatThrownBy(
                () -> handler.onJobDone(record, AsyncJobHandlerResult.done(record.jobData())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Invalid assetlocalize output for pollable task: 42")
            .hasNoCause();
      }
      assertThat(blobs.get(CANONICAL)).containsExactly(legacy ? malformed : ORIGINAL);
      assertThat(blobs.get(legacy ? CANONICAL : PRIVATE)).containsExactly(malformed);
      assertThat(store.getByIds(List.of(record.id())).getFirst().status())
          .isEqualTo(AsyncJobStatus.DONE);
      verify(tasks, never()).finishTask(eq(42L), any(), any(), any());
    }
  }

  private AsyncJobRecord terminal(boolean legacy) {
    String queue = AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME;
    var payload = new AssetLocalizeAsyncJobPayload(42L, legacy ? null : OUTPUT_ID);
    AsyncJobId id = store.enqueueNow(queue, mapper.writeValueAsStringUnchecked(payload));
    AsyncJobRecord claimed =
        store.claimNextJobs(queue, 1, "worker", Duration.ofMinutes(1)).getFirst();
    assertThat(store.markDone(queue, id, claimed.workerId(), claimed.leaseToken(), null)).isTrue();
    return store.getByIds(List.of(id)).getFirst();
  }

  private byte[] malformedContent(byte[] content) {
    byte[] prefix = "{\"bcp47Tag\":\"fr\",\"content\":\"".getBytes(StandardCharsets.UTF_8);
    byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
    return ByteBuffer.allocate(prefix.length + content.length + suffix.length)
        .put(prefix)
        .put(content)
        .put(suffix)
        .array();
  }
}
