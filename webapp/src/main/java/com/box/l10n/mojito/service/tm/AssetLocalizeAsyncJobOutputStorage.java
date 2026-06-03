package com.box.l10n.mojito.service.tm;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.POLLABLE_TASK;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
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

  public AssetLocalizeAsyncJobOutputStorage(
      StructuredBlobStorage structuredBlobStorage,
      PollableTaskBlobStorage pollableTaskBlobStorage,
      ObjectMapper objectMapper) {
    this.structuredBlobStorage = Objects.requireNonNull(structuredBlobStorage);
    this.pollableTaskBlobStorage = Objects.requireNonNull(pollableTaskBlobStorage);
    this.objectMapper = Objects.requireNonNull(objectMapper);
  }

  public AssetLocalizeAsyncJobPayload saveAttemptOutput(
      Long pollableTaskId, LocalizedAssetBody output) {
    Objects.requireNonNull(output, "Localized asset output must not be null");
    AssetLocalizeAsyncJobPayload payload =
        new AssetLocalizeAsyncJobPayload(pollableTaskId, UUID.randomUUID().toString());
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
      Objects.requireNonNull(
          pollableTaskBlobStorage.getOutput(payload.pollableTaskId(), LocalizedAssetBody.class),
          "Localized asset output must not be null");
      return;
    }
    String outputJson =
        structuredBlobStorage
            .getString(POLLABLE_TASK, outputName(payload))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Missing assetlocalize attempt output for pollable task: "
                            + payload.pollableTaskId()));
    LocalizedAssetBody output =
        Objects.requireNonNull(
            objectMapper.readValueUnchecked(outputJson, LocalizedAssetBody.class),
            "Localized asset output must not be null");
    pollableTaskBlobStorage.saveOutput(payload.pollableTaskId(), output);
  }

  private String outputName(AssetLocalizeAsyncJobPayload payload) {
    return payload.pollableTaskId() + "/assetlocalize/" + payload.outputId() + "/output";
  }
}
