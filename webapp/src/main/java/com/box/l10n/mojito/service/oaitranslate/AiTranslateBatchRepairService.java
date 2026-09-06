package com.box.l10n.mojito.service.oaitranslate;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.AI_TRANSLATE_WS;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.openai.OpenAIClient.CreateBatchResponse;
import com.box.l10n.mojito.openai.OpenAIClient.RetrieveBatchResponse;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateService.BatchRepairCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Resubmits failed translations once, preserving the original provider request context. */
@Service
class AiTranslateBatchRepairService {

  static final String REPAIR_ATTEMPT = "repairAttempt";
  static final String REPAIR_OF_BATCH = "repairOfBatch";

  private final OpenAIClient openAIClient;
  private final ObjectMapper objectMapper;
  private final StructuredBlobStorage structuredBlobStorage;

  AiTranslateBatchRepairService(
      @Qualifier("AiTranslate") OpenAIClient openAIClient,
      @Qualifier("AiTranslate") ObjectMapper objectMapper,
      StructuredBlobStorage structuredBlobStorage) {
    this.openAIClient = openAIClient;
    this.objectMapper = objectMapper;
    this.structuredBlobStorage = structuredBlobStorage;
  }

  static boolean isRepairBatch(RetrieveBatchResponse batch) {
    return batch.metadata() != null && batch.metadata().containsKey(REPAIR_ATTEMPT);
  }

  Set<Long> getRequestedTextUnitIds(RetrieveBatchResponse batch) {
    return Set.copyOf(getOriginalRequests(batch).keySet());
  }

  synchronized CreateBatchResponse createRepairBatch(
      RetrieveBatchResponse original, List<BatchRepairCandidate> repairs) {
    if (isRepairBatch(original)) {
      throw new IllegalArgumentException("A translation batch can only be repaired once");
    }
    if (repairs.isEmpty()) {
      throw new IllegalArgumentException("A repair batch requires failed translations");
    }
    String blobId =
        original.metadata() == null
            ? null
            : original.metadata().get(AiTranslateService.METADATA__TEXT_UNIT_DTOS__BLOB_ID);
    if (blobId == null || blobId.isBlank()) {
      throw new IllegalArgumentException("The original batch has no text unit snapshot");
    }

    Map<Long, BatchRepairCandidate> candidates = new LinkedHashMap<>();
    for (BatchRepairCandidate repair : repairs) {
      if (candidates.putIfAbsent(repair.tmTextUnitId(), repair) != null) {
        throw new IllegalArgumentException("Duplicate text unit in repair batch");
      }
    }
    String stateKey = "batch-repair-state-" + original.id();
    var existing = structuredBlobStorage.getString(AI_TRANSLATE_WS, stateKey);
    if (existing.isPresent()) {
      RepairState state = objectMapper.readValueUnchecked(existing.get(), RepairState.class);
      if (!Set.copyOf(state.textUnitIds()).equals(candidates.keySet())) {
        throw new IllegalStateException("A repair was already requested for different text units");
      }
      if (state.createdBatch() == null) {
        throw new IllegalStateException(
            "The previous repair submission outcome is unknown; inspect the original batch before retrying");
      }
      return state.createdBatch();
    }
    Map<Long, ObjectNode> originalRequests = getOriginalRequests(original);
    if (!originalRequests.keySet().containsAll(candidates.keySet())) {
      throw new IllegalArgumentException("Repair text unit was not in the original batch");
    }

    List<String> requestLines = new ArrayList<>();
    for (BatchRepairCandidate repair : candidates.values()) {
      ObjectNode request = originalRequests.get(repair.tmTextUnitId()).deepCopy();
      if (!"POST".equals(request.path("method").asText())
          || !"/v1/chat/completions".equals(request.path("url").asText())
          || !(request.path("body").path("messages") instanceof ArrayNode messages)
          || messages.isEmpty()) {
        throw new IllegalArgumentException("The original batch request cannot be repaired");
      }
      messages
          .addObject()
          .put("role", "user")
          .put(
              "content",
              AiTranslateCandidateValidation.REPAIR_INSTRUCTION
                  + "\n"
                  + objectMapper.writeValueAsStringUnchecked(repair));
      requestLines.add(objectMapper.writeValueAsStringUnchecked(request));
    }

    // Keep the original snapshot available for a full Batch window and later import replay.
    String snapshot =
        structuredBlobStorage
            .getString(AI_TRANSLATE_WS, blobId)
            .orElseThrow(
                () -> new IllegalStateException("The original text unit snapshot expired"));
    String repairBlobId = "batch-repair-snapshot-" + original.id();
    structuredBlobStorage.put(AI_TRANSLATE_WS, repairBlobId, snapshot, Retention.PERMANENT);

    // Persist before provider submission: an uncertain response must not create another repair.
    // The local monitor serializes callers here; the blob interface provides no cross-host CAS.
    RepairState pending = new RepairState(List.copyOf(candidates.keySet()), null);
    structuredBlobStorage.put(
        AI_TRANSLATE_WS,
        stateKey,
        objectMapper.writeValueAsStringUnchecked(pending),
        Retention.PERMANENT);
    OpenAIClient.UploadFileResponse uploaded =
        openAIClient.uploadFile(
            OpenAIClient.UploadFileRequest.forBatch(
                "translation-repair-" + original.id() + ".jsonl", String.join("\n", requestLines)));
    Map<String, String> metadata = new LinkedHashMap<>(original.metadata());
    metadata.put(AiTranslateService.METADATA__TEXT_UNIT_DTOS__BLOB_ID, repairBlobId);
    metadata.put(REPAIR_ATTEMPT, "1");
    metadata.put(REPAIR_OF_BATCH, original.id());
    CreateBatchResponse created =
        openAIClient.createBatch(
            OpenAIClient.CreateBatchRequest.forChatCompletion(uploaded.id(), metadata));
    if (created == null || created.id() == null || created.id().isBlank()) {
      throw new IllegalStateException("The repair batch submission returned no batch identity");
    }
    structuredBlobStorage.put(
        AI_TRANSLATE_WS,
        stateKey,
        objectMapper.writeValueAsStringUnchecked(new RepairState(pending.textUnitIds(), created)),
        Retention.PERMANENT);
    return created;
  }

  record RepairState(List<Long> textUnitIds, CreateBatchResponse createdBatch) {}

  private Map<Long, ObjectNode> getOriginalRequests(RetrieveBatchResponse batch) {
    if (batch.inputFileId() == null || batch.inputFileId().isBlank()) {
      throw new IllegalArgumentException("The original batch has no input file");
    }
    String input =
        openAIClient
            .downloadFileContent(new OpenAIClient.DownloadFileContentRequest(batch.inputFileId()))
            .content();
    Map<Long, ObjectNode> requests = new LinkedHashMap<>();
    for (String line : input.lines().filter(value -> !value.isBlank()).toList()) {
      JsonNode parsed = objectMapper.readTreeUnchecked(line);
      if (!(parsed instanceof ObjectNode request) || !request.path("custom_id").isTextual()) {
        throw new IllegalArgumentException("Invalid original batch request identity");
      }
      long id = Long.parseLong(request.path("custom_id").asText());
      if (id <= 0 || requests.putIfAbsent(id, request) != null) {
        throw new IllegalArgumentException("Invalid or duplicate original batch request identity");
      }
    }
    if (requests.isEmpty()) {
      throw new IllegalArgumentException("The original batch has no requests");
    }
    return requests;
  }
}
