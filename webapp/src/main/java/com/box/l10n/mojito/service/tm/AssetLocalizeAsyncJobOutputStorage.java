package com.box.l10n.mojito.service.tm;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.POLLABLE_TASK;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Keeps speculative output private until the queue accepts the attempt's done transition. */
@Component
@ConditionalOnProperty(
    name = {"l10n.org.async-job-queue.enabled", "l10n.org.async-job-queue.asset-localize.enabled"},
    havingValue = "true")
public class AssetLocalizeAsyncJobOutputStorage {

  private final StructuredBlobStorage structuredBlobStorage;
  private final PollableTaskBlobStorage pollableTaskBlobStorage;
  private final ObjectMapper objectMapper;
  private final ObjectReader outputReader;

  public AssetLocalizeAsyncJobOutputStorage(
      StructuredBlobStorage structuredBlobStorage,
      PollableTaskBlobStorage pollableTaskBlobStorage,
      ObjectMapper objectMapper) {
    this.structuredBlobStorage = Objects.requireNonNull(structuredBlobStorage);
    this.pollableTaskBlobStorage = Objects.requireNonNull(pollableTaskBlobStorage);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.outputReader =
        objectMapper
            .readerFor(LocalizedAssetBody.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  }

  public AssetLocalizeAsyncJobPayload saveAttemptOutput(
      Long pollableTaskId, LocalizedAssetBody output) {
    Objects.requireNonNull(output, "Localized asset output must not be null");
    AssetLocalizeAsyncJobPayload payload =
        new AssetLocalizeAsyncJobPayload(pollableTaskId, UUID.randomUUID().toString());
    if (!AssetLocalizeAsyncJobUnicode.isWellFormed(output)) {
      throw new IllegalStateException(
          "Invalid assetlocalize output for pollable task: " + pollableTaskId);
    }
    structuredBlobStorage.put(
        POLLABLE_TASK,
        outputName(payload),
        objectMapper.writeValueAsStringUnchecked(output),
        Retention.MIN_1_DAY);
    return payload;
  }

  public void publishOutput(AssetLocalizeAsyncJobPayload payload) {
    if (payload.outputId() == null) {
      // Jobs completed by older workers already wrote the canonical output.
      readOutput(
          payload.pollableTaskId(),
          pollableTaskBlobStorage.getOutputBytes(payload.pollableTaskId()));
      return;
    }
    byte[] outputBytes =
        structuredBlobStorage
            .getBytes(POLLABLE_TASK, outputName(payload))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Missing assetlocalize attempt output for pollable task: "
                            + payload.pollableTaskId()));
    LocalizedAssetBody output = readOutput(payload.pollableTaskId(), outputBytes);
    pollableTaskBlobStorage.saveOutput(payload.pollableTaskId(), output);
  }

  private LocalizedAssetBody readOutput(Long pollableTaskId, byte[] outputBytes) {
    try {
      // String-based blob readers replace malformed UTF-8 before JSON validation can see it.
      String outputJson =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(outputBytes))
              .toString();
      LocalizedAssetBody output = outputReader.readValue(outputJson);
      if (AssetLocalizeAsyncJobUnicode.isWellFormed(output)) {
        return output;
      }
    } catch (IOException invalidOutput) {
      // Parser messages and cause chains can contain localized content or supplied field names.
    }
    throw new IllegalStateException(
        "Invalid assetlocalize output for pollable task: " + pollableTaskId);
  }

  private String outputName(AssetLocalizeAsyncJobPayload payload) {
    return payload.pollableTaskId() + "/assetlocalize/" + payload.outputId() + "/output";
  }
}
