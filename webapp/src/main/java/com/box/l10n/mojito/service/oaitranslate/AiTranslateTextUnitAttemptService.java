package com.box.l10n.mojito.service.oaitranslate;

import static com.box.l10n.mojito.entity.AiTranslateTextUnitAttempt.STATUS_FAILED;
import static com.box.l10n.mojito.entity.AiTranslateTextUnitAttempt.STATUS_IMPORTED;
import static com.box.l10n.mojito.entity.AiTranslateTextUnitAttempt.STATUS_REQUESTED;
import static com.box.l10n.mojito.entity.AiTranslateTextUnitAttempt.STATUS_RESPONDED;
import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.AI_TRANSLATE_LINEAGE;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

import com.box.l10n.mojito.entity.AiTranslateRun;
import com.box.l10n.mojito.entity.AiTranslateTextUnitAttempt;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTranslateTextUnitAttemptService {

  static final String REDACTED_IMAGE_DATA_URL = "[image data omitted]";

  private final AiTranslateTextUnitAttemptRepository aiTranslateTextUnitAttemptRepository;
  private final AiTranslateRunRepository aiTranslateRunRepository;
  private final StructuredBlobStorage structuredBlobStorage;
  private final ObjectMapper objectMapper;

  @PersistenceContext private EntityManager entityManager;

  public AiTranslateTextUnitAttemptService(
      AiTranslateTextUnitAttemptRepository aiTranslateTextUnitAttemptRepository,
      AiTranslateRunRepository aiTranslateRunRepository,
      StructuredBlobStorage structuredBlobStorage,
      @Qualifier("AiTranslate") ObjectMapper objectMapper) {
    this.aiTranslateTextUnitAttemptRepository = aiTranslateTextUnitAttemptRepository;
    this.aiTranslateRunRepository = aiTranslateRunRepository;
    this.structuredBlobStorage = structuredBlobStorage;
    this.objectMapper = objectMapper;
  }

  public record NoBatchAttemptRequest(
      Long tmTextUnitId,
      Long localeId,
      String requestGroupId,
      String translateType,
      String model,
      String requestPayloadBlobName) {}

  public record NoBatchImportedVariant(
      String requestGroupId, Long tmTextUnitId, Long tmTextUnitVariantId) {}

  public String putPayloadBlob(
      Long pollableTaskId, String requestGroupId, String fileName, String content) {
    String blobName = "%d/%s/%s".formatted(pollableTaskId, requestGroupId, fileName);
    structuredBlobStorage.put(
        AI_TRANSLATE_LINEAGE,
        blobName,
        redactImageDataUrls(content, objectMapper),
        Retention.PERMANENT);
    return blobName;
  }

  @Transactional
  public void createNoBatchAttempts(Long pollableTaskId, List<NoBatchAttemptRequest> requests) {
    if (requests == null || requests.isEmpty()) {
      return;
    }

    AiTranslateRun aiTranslateRun =
        aiTranslateRunRepository.findByPollableTask_Id(pollableTaskId).orElse(null);
    PollableTask pollableTask = entityManager.getReference(PollableTask.class, pollableTaskId);

    aiTranslateTextUnitAttemptRepository.saveAll(
        requests.stream()
            .map(request -> createNoBatchAttempt(aiTranslateRun, pollableTask, request))
            .toList());
  }

  @Transactional
  public void markNoBatchResponded(
      Long pollableTaskId,
      String requestGroupId,
      String completionId,
      String responsePayloadBlobName) {
    updateRequestGroup(
        pollableTaskId,
        requestGroupId,
        attempt -> {
          attempt.setStatus(STATUS_RESPONDED);
          attempt.setCompletionId(completionId);
          attempt.setResponsePayloadBlobName(responsePayloadBlobName);
          attempt.setErrorMessage(null);
        });
  }

  @Transactional
  public void markNoBatchFailed(
      Long pollableTaskId,
      String requestGroupId,
      String completionId,
      String responsePayloadBlobName,
      String errorMessage) {
    updateRequestGroup(
        pollableTaskId,
        requestGroupId,
        attempt -> {
          attempt.setStatus(STATUS_FAILED);
          attempt.setCompletionId(completionId);
          attempt.setResponsePayloadBlobName(responsePayloadBlobName);
          attempt.setErrorMessage(errorMessage);
        });
  }

  @Transactional
  public void markNoBatchImported(
      Long pollableTaskId, List<NoBatchImportedVariant> importedVariants) {
    if (importedVariants == null || importedVariants.isEmpty()) {
      return;
    }

    List<NoBatchImportedVariant> validImportedVariants =
        importedVariants.stream()
            .filter(importedVariant -> importedVariant.requestGroupId() != null)
            .filter(importedVariant -> importedVariant.tmTextUnitId() != null)
            .filter(importedVariant -> importedVariant.tmTextUnitVariantId() != null)
            .toList();
    if (validImportedVariants.isEmpty()) {
      return;
    }

    Map<String, List<NoBatchImportedVariant>> importedVariantsByRequestGroupId =
        validImportedVariants.stream().collect(groupingBy(NoBatchImportedVariant::requestGroupId));

    Map<String, Map<Long, NoBatchImportedVariant>> importedVariantByRequestGroupIdAndTextUnitId =
        importedVariantsByRequestGroupId.entrySet().stream()
            .collect(
                toMap(
                    Map.Entry::getKey,
                    entry ->
                        entry.getValue().stream()
                            .collect(
                                toMap(
                                    NoBatchImportedVariant::tmTextUnitId,
                                    Function.identity(),
                                    (first, second) -> second,
                                    LinkedHashMap::new)),
                    (first, second) -> second,
                    LinkedHashMap::new));

    aiTranslateTextUnitAttemptRepository
        .findByPollableTask_IdAndRequestGroupIdIn(
            pollableTaskId, importedVariantByRequestGroupIdAndTextUnitId.keySet())
        .forEach(
            attempt -> {
              NoBatchImportedVariant importedVariant =
                  importedVariantByRequestGroupIdAndTextUnitId
                      .getOrDefault(attempt.getRequestGroupId(), Map.of())
                      .get(attempt.getTmTextUnit().getId());
              if (importedVariant == null) {
                return;
              }

              attempt.setTmTextUnitVariant(
                  entityManager.getReference(
                      TMTextUnitVariant.class, importedVariant.tmTextUnitVariantId()));
              attempt.setStatus(STATUS_IMPORTED);
            });
  }

  private AiTranslateTextUnitAttempt createNoBatchAttempt(
      AiTranslateRun aiTranslateRun, PollableTask pollableTask, NoBatchAttemptRequest request) {
    AiTranslateTextUnitAttempt attempt = new AiTranslateTextUnitAttempt();
    attempt.setAiTranslateRun(aiTranslateRun);
    attempt.setPollableTask(pollableTask);
    attempt.setTmTextUnit(entityManager.getReference(TMTextUnit.class, request.tmTextUnitId()));
    attempt.setLocale(entityManager.getReference(Locale.class, request.localeId()));
    attempt.setRequestGroupId(request.requestGroupId());
    attempt.setTranslateType(request.translateType());
    attempt.setModel(request.model());
    attempt.setStatus(STATUS_REQUESTED);
    attempt.setRequestPayloadBlobName(request.requestPayloadBlobName());
    return attempt;
  }

  private void updateRequestGroup(
      Long pollableTaskId,
      String requestGroupId,
      java.util.function.Consumer<AiTranslateTextUnitAttempt> update) {
    if (pollableTaskId == null || requestGroupId == null) {
      return;
    }

    aiTranslateTextUnitAttemptRepository
        .findByPollableTask_IdAndRequestGroupId(pollableTaskId, requestGroupId)
        .forEach(update);
  }

  static String redactImageDataUrls(String content, ObjectMapper objectMapper) {
    if (content == null || content.isBlank()) {
      return content;
    }

    com.fasterxml.jackson.databind.ObjectMapper strictObjectMapper = objectMapper.copy();
    strictObjectMapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    String redactedJson = redactJson(content, objectMapper, strictObjectMapper);
    if (redactedJson != null) {
      return redactedJson;
    }

    List<String> lines =
        content
            .lines()
            .map(line -> redactJsonLine(line, objectMapper, strictObjectMapper))
            .toList();
    return String.join("\n", lines);
  }

  private static String redactJsonLine(
      String line,
      ObjectMapper objectMapper,
      com.fasterxml.jackson.databind.ObjectMapper strictObjectMapper) {
    if (line.isBlank()) {
      return line;
    }

    String redactedJson = redactJson(line, objectMapper, strictObjectMapper);
    return redactedJson == null ? line : redactedJson;
  }

  private static String redactJson(
      String content,
      ObjectMapper objectMapper,
      com.fasterxml.jackson.databind.ObjectMapper strictObjectMapper) {
    try {
      JsonNode jsonNode = strictObjectMapper.readTree(content);
      redactImageDataUrls(jsonNode);
      return objectMapper.writeValueAsStringUnchecked(jsonNode);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private static void redactImageDataUrls(JsonNode jsonNode) {
    if (jsonNode == null) {
      return;
    }

    if (jsonNode.isObject()) {
      ObjectNode objectNode = (ObjectNode) jsonNode;
      Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        if ("image_url".equals(field.getKey())
            && field.getValue().isTextual()
            && field.getValue().asText().startsWith("data:")) {
          objectNode.put(field.getKey(), REDACTED_IMAGE_DATA_URL);
        } else {
          redactImageDataUrls(field.getValue());
        }
      }
    } else if (jsonNode.isArray()) {
      ArrayNode arrayNode = (ArrayNode) jsonNode;
      arrayNode.forEach(AiTranslateTextUnitAttemptService::redactImageDataUrls);
    }
  }
}
