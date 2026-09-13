package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.okapi.InheritanceMode;
import com.box.l10n.mojito.okapi.Status;
import com.box.l10n.mojito.queue.AsyncJobHandlerResult;
import com.box.l10n.mojito.queue.AsyncJobId;
import com.box.l10n.mojito.queue.AsyncJobPermanentFailureException;
import com.box.l10n.mojito.queue.AsyncJobRecord;
import com.box.l10n.mojito.queue.AsyncJobStatus;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.blobstorage.AzureDatabaseFallbackBlobStorage;
import com.box.l10n.mojito.service.blobstorage.BlobStorage;
import com.box.l10n.mojito.service.blobstorage.BlobStorageRouter;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** Uses actual byte-to-text blob reads to cover encoding and JSON binding together. */
public class AssetLocalizeAsyncJobInputTest {

  private static final String INPUT_KEY = "pollable_task/42/input";
  private ObjectMapper mapper = ObjectMapper.withNoFailOnUnknownProperties();
  private final Map<String, byte[]> blobs = new HashMap<>();
  private final PollableTaskBlobStorage inputs = new PollableTaskBlobStorage();
  private final PollableTaskService tasks = mock(PollableTaskService.class);
  private final LocalizedAssetGenerationService generation =
      mock(LocalizedAssetGenerationService.class);
  private final AssetLocalizeAsyncJobOutputStorage outputs =
      mock(AssetLocalizeAsyncJobOutputStorage.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private RuntimeException storageFailure;
  private AssetLocalizeAsyncJobHandler handler;

  @Before
  public void setUp() {
    ReflectionTestUtils.setField(inputs, "objectMapper", mapper);
    BlobStorage bytes =
        new BlobStorage() {
          @Override
          public Optional<byte[]> getBytes(String name) {
            if (storageFailure != null) {
              throw storageFailure;
            }
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
    ReflectionTestUtils.setField(
        inputs, "structuredBlobStorage", new StructuredBlobStorage(router));
    PollableTask task = new PollableTask();
    task.setId(42L);
    when(tasks.getPollableTask(42L)).thenReturn(task);
    rebuildHandler();
  }

  @After
  public void tearDown() {
    meters.close();
  }

  @Test
  public void duplicateInputCannotHideTrackedWorkFromTheEligibilityGuard() {
    assertRejected("{\"pullRunName\":\"private-tracked-run\",\"pullRunName\":null}");
  }

  @Test
  public void trailingInputDocumentCannotBeIgnoredBeforeGeneration() {
    assertRejected("{} {\"pullRunName\":\"private-tracked-run\"}");
  }

  @Test
  public void inputParserDiagnosticsDoNotLeakSourceValues() {
    assertRejected("{\"assetId\":\"private-source-value\"}");
  }

  @Test
  public void malformedNullAndDuplicateUnknownInputFieldsFailClosed() {
    for (String json :
        new String[] {
          "null",
          "[]",
          "{",
          "",
          "{\"future\":1,\"future\":2}",
          "{\"content\":\"a\",\"con\\u0074ent\":\"b\"}",
          "{\"future\":{\"field\":1,\"field\":2}}"
        }) {
      assertRejected(json);
    }
  }

  @Test
  public void malformedUtf8CannotBeReplacedAndPassedToGeneration() throws Exception {
    when(generation.generate(any())).thenReturn(new LocalizedAssetBody("fr", "translation"));
    when(outputs.saveAttemptOutput(any(), any())).thenReturn(new AssetLocalizeAsyncJobPayload(42L));
    for (byte[] invalid :
        new byte[][] {
          {(byte) 0x80},
          {(byte) 0xc0, (byte) 0xaf},
          {(byte) 0xe2, (byte) 0x82},
          {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
          {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80}
        }) {
      byte[] prefix = "{\"content\":\"private-source-marker".getBytes(StandardCharsets.UTF_8);
      byte[] suffix = "\",\"bcp47Tag\":\"fr\"}".getBytes(StandardCharsets.UTF_8);
      byte[] json =
          ByteBuffer.allocate(prefix.length + invalid.length + suffix.length)
              .put(prefix)
              .put(invalid)
              .put(suffix)
              .array();
      assertRejectedBytes(json);
    }
  }

  @Test
  public void escapedUnpairedSurrogatesCannotReachGeneration() {
    for (String json :
        new String[] {
          "{\"content\":\"private-source-marker\\ud800\"}",
          "{\"bcp47Tag\":\"private-source-marker\\udc00\"}",
          "{\"outputBcp47tag\":\"private-source-marker\\udc00\\ud800\"}",
          "{\"filterOptions\":[\"private-source-marker\\ud800\"]}",
          "{\"pullWithNoSourceBranches\":[null,\"private-source-marker\\udc00\"]}"
        }) {
      assertRejected(json);
    }
  }

  @Test
  public void utf16DocumentsAndFramingBomAreNotNewlyAccepted() {
    assertRejectedBytes("{\"content\":\"UTF-16\"}".getBytes(StandardCharsets.UTF_16LE));
    assertRejectedBytes("{\"content\":\"UTF-16\"}".getBytes(StandardCharsets.UTF_16BE));
    assertRejectedBytes("\ufeff{\"content\":\"framing BOM\"}".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void legacyInputReadersKeepReplacementDecoding() {
    blobs.put(INPUT_KEY, new byte[] {'"', (byte) 0x80, '"'});

    assertThat(inputs.getInputJson(42L)).isEqualTo("\"\ufffd\"");
    assertThat(inputs.getInput(42L, String.class)).isEqualTo("\ufffd");
  }

  @Test
  public void fallbackBackfillsExactBytesBeforeRejectingMalformedInput() {
    String json = "{\"content\":\"private-source-marker!\",\"bcp47Tag\":\"fr\"}";
    byte[] malformed = json.getBytes(StandardCharsets.UTF_8);
    malformed[json.indexOf('!')] = (byte) 0x80;
    var azure = mock(AzureBlobStorage.class);
    var database = mock(DatabaseBlobStorage.class);
    when(azure.getBytes(INPUT_KEY)).thenReturn(Optional.empty());
    when(database.getStoredBlob(INPUT_KEY))
        .thenReturn(
            Optional.of(new DatabaseBlobStorage.StoredBlob(malformed, Retention.MIN_1_DAY)));
    var router = mock(BlobStorageRouter.class);
    when(router.getBlobStorage(StructuredBlobStorage.Prefix.POLLABLE_TASK))
        .thenReturn(new AzureDatabaseFallbackBlobStorage(azure, database, meters));
    ReflectionTestUtils.setField(
        inputs, "structuredBlobStorage", new StructuredBlobStorage(router));

    assertThatThrownBy(() -> handler.process(record()))
        .isExactlyInstanceOf(AsyncJobPermanentFailureException.class)
        .hasMessage("Invalid assetlocalize input for pollable task: 42")
        .hasNoCause();

    var backfilled = ArgumentCaptor.forClass(byte[].class);
    verify(azure).getBytes(INPUT_KEY);
    verify(azure)
        .put(
            org.mockito.ArgumentMatchers.eq(INPUT_KEY),
            backfilled.capture(),
            org.mockito.ArgumentMatchers.eq(Retention.MIN_1_DAY));
    assertThat(backfilled.getValue()).containsExactly(malformed);
    verify(database).getStoredBlob(INPUT_KEY);
    org.mockito.Mockito.verifyNoMoreInteractions(azure, database);
    verifyNoInteractions(generation, outputs);
  }

  @Test
  public void legacyUnknownFieldsAndNullableDefaultsRemainCompatible() throws Exception {
    putJson(
        " {\"assetId\":123,\"localeId\":456,\"bcp47Tag\":\"fr\","
            + "\"content\":\"source\\u0000text \ufffd \ufeff \ud83d\ude00 fran\u00e7ais\",\"pullWithNoSourceBranches\":[null,\"branch\"],"
            + "\"future\":{\"ignored\":[1,2]}} ");
    var output = new LocalizedAssetBody("fr", "translation");
    when(generation.generate(any())).thenReturn(output);
    var payload = new AssetLocalizeAsyncJobPayload(42L, "00000000-0000-0000-0000-000000000001");
    when(outputs.saveAttemptOutput(42L, output)).thenReturn(payload);

    assertThat(handler.process(record()).action()).isEqualTo(AsyncJobHandlerResult.Action.DONE);

    var captured = ArgumentCaptor.forClass(LocalizedAssetBody.class);
    verify(generation).generate(captured.capture());
    LocalizedAssetBody input = captured.getValue();
    assertThat(input.getAssetId()).isEqualTo(123L);
    assertThat(input.getLocaleId()).isEqualTo(456L);
    assertThat(input.getBcp47Tag()).isEqualTo("fr");
    assertThat(input.getContent())
        .isEqualTo("source\u0000text \ufffd \ufeff \ud83d\ude00 fran\u00e7ais");
    assertThat(input.getPullWithNoSourceBranches()).containsExactly(null, "branch");
    assertThat(input.getInheritanceMode()).isEqualTo(InheritanceMode.USE_PARENT);
    assertThat(input.getStatus()).isEqualTo(Status.ALL);
    assertThat(input.isPullWithNoSource()).isFalse();
    assertThat(input.getPullRunName()).isNull();
    assertThat(input.getFilterOptions()).isNull();
  }

  @Test
  public void framingReaderKeepsConfiguredBindingWithoutMutatingTheSharedMapper() throws Exception {
    mapper = ObjectMapper.withNoFailOnUnknownProperties();
    mapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);
    ReflectionTestUtils.setField(inputs, "objectMapper", mapper);
    rebuildHandler();
    putJson("{\"status\":\"all\",\"future\":true}");
    when(generation.generate(any())).thenReturn(new LocalizedAssetBody());
    when(outputs.saveAttemptOutput(any(), any())).thenReturn(new AssetLocalizeAsyncJobPayload(42L));
    handler.process(record());
    var captured = ArgumentCaptor.forClass(LocalizedAssetBody.class);
    verify(generation).generate(captured.capture());
    assertThat(captured.getValue().getStatus()).isEqualTo(Status.ALL);
    assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
    assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isFalse();
    assertThat(mapper.isEnabled(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)).isFalse();
    assertThat(mapper.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)).isTrue();
    // Non-queue callers retain the legacy reader; no global parser behavior changed.
    putJson("{\"content\":\"a\",\"content\":\"b\"} {}");
    assertThat(inputs.getInput(42L, LocalizedAssetBody.class).getContent()).isEqualTo("b");
  }

  @Test
  public void missingInputIsNotReclassifiedAsMalformedJson() {
    assertThatThrownBy(() -> handler.process(record()))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Can't get the input json for: 42");
    verifyNoInteractions(generation, outputs);
  }

  @Test
  public void storageFailureIsNotReclassifiedOrMasked() {
    storageFailure = new IllegalStateException("storage unavailable");
    assertThatThrownBy(() -> handler.process(record())).isSameAs(storageFailure);
    verifyNoInteractions(generation, outputs);
  }

  @Test
  public void mapperDefinitionFailureKeepsOrdinaryRetryClassificationAndRedaction() {
    // Install before the first reader is built, rather than reusing cached deserializers.
    mapper = ObjectMapper.withNoFailOnUnknownProperties();
    var module = new SimpleModule();
    module.addDeserializer(
        LocalizedAssetBody.class,
        new JsonDeserializer<LocalizedAssetBody>() {
          @Override
          public LocalizedAssetBody deserialize(JsonParser parser, DeserializationContext context)
              throws IOException {
            return context.reportBadDefinition(
                LocalizedAssetBody.class, "private-mapper-configuration");
          }
        });
    mapper.registerModule(module);
    rebuildHandler();
    putJson("{}");

    assertThatThrownBy(() -> handler.process(record()))
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessage("Invalid assetlocalize input for pollable task: 42")
        .hasNoCause();
    verifyNoInteractions(generation, outputs);
  }

  @Test
  public void malformedInputRemainsAnUnexpectedTaskErrorWithTheRealExceptionClassifier()
      throws Exception {
    putJson("{\"content\":\"private-source-value\",\"bcp47Tag\":");
    AsyncJobPermanentFailureException failure =
        org.junit.Assert.assertThrows(
            AsyncJobPermanentFailureException.class, () -> handler.process(record()));

    handler.onJobFailedPermanently(record(), failure, failure.toString());

    var holder = ArgumentCaptor.forClass(ExceptionHolder.class);
    verify(tasks)
        .finishTask(
            org.mockito.ArgumentMatchers.eq(42L),
            org.mockito.ArgumentMatchers.isNull(),
            holder.capture(),
            org.mockito.ArgumentMatchers.isNull());
    assertThat(holder.getValue().isExpected()).isFalse();
    assertThat(holder.getValue().getException())
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessage("Asset localize async job failed permanently: " + record().id().value())
        .hasNoCause();
    assertThat(holder.getValue().getException().getSuppressed()).isEmpty();
    assertThat(mapper.writeValueAsStringUnchecked(holder.getValue()))
        .isEqualTo(
            "{\"expected\":false,\"message\":\"An unexpected error happened, task=42\","
                + "\"type\":\"unexpected\"}");
    assertThat(org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace(failure))
        .doesNotContain("private-source-value");
    verifyNoInteractions(generation, outputs);
  }

  private void assertRejected(String json) {
    assertRejectedBytes(json.getBytes(StandardCharsets.UTF_8));
  }

  private void assertRejectedBytes(byte[] json) {
    blobs.put(INPUT_KEY, json.clone());
    assertThatThrownBy(() -> handler.process(record()))
        .isExactlyInstanceOf(AsyncJobPermanentFailureException.class)
        .hasMessage("Invalid assetlocalize input for pollable task: 42")
        .hasNoCause();
    verifyNoInteractions(generation, outputs);
    assertThat(blobs.get(INPUT_KEY)).containsExactly(json);
  }

  private void putJson(String json) {
    blobs.put(INPUT_KEY, json.getBytes(StandardCharsets.UTF_8));
  }

  private void rebuildHandler() {
    handler =
        new AssetLocalizeAsyncJobHandler(
            tasks, inputs, new PollableTaskExceptionUtils(), generation, mapper, meters, outputs);
  }

  private AsyncJobRecord record() {
    Instant now = Instant.now();
    return new AsyncJobRecord(
        new AsyncJobId("1"),
        "assetlocalize",
        AsyncJobStatus.RUNNING,
        now,
        now.plusSeconds(30),
        "worker",
        "token",
        "{\"pollableTaskId\":42}",
        1,
        null,
        now,
        now,
        false);
  }
}
