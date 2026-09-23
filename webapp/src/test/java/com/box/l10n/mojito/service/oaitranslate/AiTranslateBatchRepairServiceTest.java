package com.box.l10n.mojito.service.oaitranslate;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.AI_TRANSLATE_WS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.openai.OpenAIClient.RetrieveBatchResponse;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateService.BatchRepairCandidate;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class AiTranslateBatchRepairServiceTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final OpenAIClient client = mock(OpenAIClient.class);
  private final StructuredBlobStorage storage = mock(StructuredBlobStorage.class);
  private final AiTranslateBatchRepairService service =
      new AiTranslateBatchRepairService(client, mapper, storage);

  @Test
  void retriesOnlyFailedIdsAndPreservesOriginalRequestContextAndSchema() {
    RetrieveBatchResponse original = original();
    ObjectNode originalRequest = request(42);
    ((ObjectNode) originalRequest.get("body")).put("reasoning_effort", "max");
    ((ObjectNode) originalRequest.get("body"))
        .putObject("response_format")
        .put("type", "json_object");
    when(client.downloadFileContent(any()))
        .thenReturn(
            new OpenAIClient.DownloadFileContentResponse(
                mapper.writeValueAsStringUnchecked(originalRequest)
                    + "\n"
                    + mapper.writeValueAsStringUnchecked(request(43))));
    when(storage.getString(AI_TRANSLATE_WS, "snapshot")).thenReturn(Optional.of("saved DTOs"));
    when(client.uploadFile(any()))
        .thenReturn(
            new OpenAIClient.UploadFileResponse(
                "file", "repair-input", "batch", "repair.jsonl", 0, 0, "processed", null));
    OpenAIClient.CreateBatchResponse created =
        mapper.readValueUnchecked(
            "{\"id\":\"repair\",\"input_file_id\":\"repair-input\"}",
            OpenAIClient.CreateBatchResponse.class);
    when(client.createBatch(any())).thenReturn(created);

    assertThat(service.createRepairBatch(original, List.of(candidate(42)))).isSameAs(created);

    ArgumentCaptor<OpenAIClient.UploadFileRequest> upload =
        ArgumentCaptor.forClass(OpenAIClient.UploadFileRequest.class);
    verify(client).uploadFile(upload.capture());
    String content = mapper.valueToTree(upload.getValue()).at("/fileContent/value").asText();
    assertThat(content.lines().count()).isEqualTo(1);
    ObjectNode repairedRequest = (ObjectNode) mapper.readTreeUnchecked(content);
    ArrayNode repairedMessages = (ArrayNode) repairedRequest.at("/body/messages");
    assertThat(repairedMessages).hasSize(3);
    assertThat(repairedMessages.get(2).path("role").asText()).isEqualTo("user");
    assertThat(repairedMessages.get(2).path("content").asText())
        .contains("42", "candidate without required forms", "plural-category-missing", "few");
    repairedMessages.remove(2);
    assertThat(repairedRequest).isEqualTo(originalRequest);

    ArgumentCaptor<OpenAIClient.CreateBatchRequest> batch =
        ArgumentCaptor.forClass(OpenAIClient.CreateBatchRequest.class);
    verify(client).createBatch(batch.capture());
    assertThat(batch.getValue().inputFileId()).isEqualTo("repair-input");
    assertThat(batch.getValue().metadata())
        .containsEntry(
            AiTranslateService.METADATA__TEXT_UNIT_DTOS__BLOB_ID, "batch-repair-snapshot-original")
        .containsEntry(AiTranslateBatchRepairService.REPAIR_ATTEMPT, "1")
        .containsEntry(AiTranslateBatchRepairService.REPAIR_OF_BATCH, "original");
    verify(storage)
        .put(AI_TRANSLATE_WS, "batch-repair-snapshot-original", "saved DTOs", Retention.PERMANENT);

    ArgumentCaptor<String> savedState = ArgumentCaptor.forClass(String.class);
    verify(storage, times(2))
        .put(
            eq(AI_TRANSLATE_WS),
            eq("batch-repair-state-original"),
            savedState.capture(),
            eq(Retention.PERMANENT));
    when(storage.getString(AI_TRANSLATE_WS, "batch-repair-state-original"))
        .thenReturn(Optional.of(savedState.getValue()));
    AiTranslateBatchRepairService restarted =
        new AiTranslateBatchRepairService(client, mapper, storage);
    assertThat(restarted.createRepairBatch(original, List.of(candidate(42))).id())
        .isEqualTo("repair");
    verify(client, times(1)).uploadFile(any());
    verify(client, times(1)).createBatch(any());
  }

  @Test
  void ambiguousSubmissionStateStopsReplayInsteadOfCreatingAnotherBatch() {
    RetrieveBatchResponse original = original();
    when(storage.getString(AI_TRANSLATE_WS, "batch-repair-state-original"))
        .thenReturn(
            Optional.of(
                mapper.writeValueAsStringUnchecked(
                    new AiTranslateBatchRepairService.RepairState(List.of(42L), null))));

    assertThatThrownBy(() -> service.createRepairBatch(original, List.of(candidate(42))))
        .hasMessageContaining("submission outcome is unknown");
    verifyNoInteractions(client);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void filtersUnusableGlossaryTargetsInUploadedRepairInput(boolean multipleTextUnits) {
    ObjectNode textUnit =
        (ObjectNode)
            mapper.readTreeUnchecked(
                """
                {
                  "tmTextUnitId": 42,
                  "source": "Create {name} with GitHub",
                  "sourceDescription": "Create action",
                  "existingTarget": {"content": "יצירה", "comment": "Keep this context"},
                  "relatedStrings": [{"source": "Nearby source"}],
                  "customContext": {"preserve": true},
                  "glossaryTerms": [
                    {"term": "Create", "termTarget": null, "doNotTranslate": false,
                     "termTargetComment": "English source comment"},
                    {"term": "Missing"},
                    {"term": "Empty", "termTarget": ""},
                    {"term": "Blank", "termTarget": " \\t\\n"},
                    {"term": "Unicode blank", "termTarget": "\\u2003"},
                    {"term": "Nonbreaking blank", "termTarget": "\\u00a0"},
                    {"term": "Localized", "termTarget": "יצירה", "doNotTranslate": false,
                     "termTargetComment": "Hebrew target comment", "extra": "kept"},
                    {"term": "GitHub", "termTarget": null, "doNotTranslate": true},
                    {"term": "OpenAI", "termTarget": " ", "doNotTranslate": true},
                    {"term": "DNT target", "termTarget": "Fixed spelling", "doNotTranslate": true}
                  ]
                }
                """);
    ObjectNode input;
    if (multipleTextUnits) {
      input = mapper.createObjectNode();
      input.putArray("textUnitsToTranslate").add(textUnit).add(textUnit.deepCopy());
      input.putObject("extraRequestContext").put("preserve", true);
    } else {
      input = textUnit;
    }
    input.put("locale", "he");
    String originalContent = mapper.writeValueAsStringUnchecked(input);
    ObjectNode originalRequest = request(42);
    ArrayNode originalMessages = (ArrayNode) originalRequest.at("/body/messages");
    originalMessages.addObject().put("role", "user").put("content", originalContent);
    originalMessages.addObject().put("role", "assistant").put("content", originalContent);
    when(client.downloadFileContent(any()))
        .thenReturn(
            new OpenAIClient.DownloadFileContentResponse(
                mapper.writeValueAsStringUnchecked(originalRequest)));
    when(storage.getString(AI_TRANSLATE_WS, "snapshot")).thenReturn(Optional.of("saved DTOs"));
    when(client.uploadFile(any()))
        .thenReturn(
            new OpenAIClient.UploadFileResponse(
                "file", "repair-input", "batch", "repair.jsonl", 0, 0, "processed", null));
    when(client.createBatch(any()))
        .thenReturn(
            mapper.readValueUnchecked(
                "{\"id\":\"repair\",\"input_file_id\":\"repair-input\"}",
                OpenAIClient.CreateBatchResponse.class));

    service.createRepairBatch(original(), List.of(candidate(42)));

    ArgumentCaptor<OpenAIClient.UploadFileRequest> upload =
        ArgumentCaptor.forClass(OpenAIClient.UploadFileRequest.class);
    verify(client).uploadFile(upload.capture());
    ObjectNode repairedRequest =
        (ObjectNode)
            mapper.readTreeUnchecked(
                mapper.valueToTree(upload.getValue()).at("/fileContent/value").asText());
    ArrayNode repairedMessages = (ArrayNode) repairedRequest.at("/body/messages");
    ObjectNode repairedInput =
        (ObjectNode) mapper.readTreeUnchecked(repairedMessages.get(2).path("content").asText());
    List<ObjectNode> expectedTextUnits =
        multipleTextUnits
            ? List.of(
                (ObjectNode) input.at("/textUnitsToTranslate/0"),
                (ObjectNode) input.at("/textUnitsToTranslate/1"))
            : List.of(input);
    for (ObjectNode expectedTextUnit : expectedTextUnits) {
      ArrayNode terms = (ArrayNode) expectedTextUnit.path("glossaryTerms");
      for (int i = 0; i < 6; i++) {
        terms.remove(0);
      }
      ((ObjectNode) terms.get(1)).put("termTarget", "GitHub");
      ((ObjectNode) terms.get(2)).put("termTarget", "OpenAI");
    }
    assertThat(repairedInput).isEqualTo(input);
    // Only the input glossary guidance changes; unrelated fields, roles and plain text survive.
    ((ObjectNode) repairedMessages.get(2)).put("content", originalContent);
    repairedMessages.remove(4);
    assertThat(repairedRequest).isEqualTo(originalRequest);
  }

  @Test
  void repairAttemptPresencePreventsAnyFurtherProviderRequest() {
    RetrieveBatchResponse original = original();
    when(original.metadata()).thenReturn(Map.of(AiTranslateBatchRepairService.REPAIR_ATTEMPT, ""));

    assertThatThrownBy(() -> service.createRepairBatch(original, List.of(candidate(42))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("only be repaired once");
    verifyNoInteractions(client, storage);
  }

  @Test
  void rejectsUnknownAndDuplicateRepairIdsBeforeUploading() {
    RetrieveBatchResponse original = original();
    when(client.downloadFileContent(any()))
        .thenReturn(
            new OpenAIClient.DownloadFileContentResponse(
                mapper.writeValueAsStringUnchecked(request(42))));

    assertThatThrownBy(() -> service.createRepairBatch(original, List.of(candidate(43))))
        .hasMessageContaining("not in the original batch");
    assertThatThrownBy(
            () -> service.createRepairBatch(original, List.of(candidate(42), candidate(42))))
        .hasMessageContaining("Duplicate text unit");
    verify(client, never()).uploadFile(any());
    verify(client, never()).createBatch(any());
    verify(storage, never()).put(any(), any(), any(String.class), any());
  }

  @Test
  void readsExactRequestedMembershipAndRejectsDuplicateOriginalIds() {
    RetrieveBatchResponse original = original();
    String request = mapper.writeValueAsStringUnchecked(request(42));
    when(client.downloadFileContent(any()))
        .thenReturn(new OpenAIClient.DownloadFileContentResponse(request));
    assertThat(service.getRequestedTextUnitIds(original)).containsExactly(42L);

    when(client.downloadFileContent(any()))
        .thenReturn(new OpenAIClient.DownloadFileContentResponse(request + "\n" + request));
    assertThatThrownBy(() -> service.getRequestedTextUnitIds(original))
        .hasMessageContaining("duplicate original batch request identity");
    verify(client, never()).uploadFile(any());
  }

  @Test
  void expiredSnapshotPreventsSchedulingAnUnimportableRepair() {
    RetrieveBatchResponse original = original();
    when(client.downloadFileContent(any()))
        .thenReturn(
            new OpenAIClient.DownloadFileContentResponse(
                mapper.writeValueAsStringUnchecked(request(42))));
    when(storage.getString(AI_TRANSLATE_WS, "snapshot")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.createRepairBatch(original, List.of(candidate(42))))
        .hasMessageContaining("snapshot expired");
    verify(client, never()).uploadFile(any());
    verify(client, never()).createBatch(any());
  }

  private RetrieveBatchResponse original() {
    RetrieveBatchResponse response = mock(RetrieveBatchResponse.class);
    when(response.id()).thenReturn("original");
    when(response.inputFileId()).thenReturn("original-input");
    when(response.metadata())
        .thenReturn(Map.of(AiTranslateService.METADATA__TEXT_UNIT_DTOS__BLOB_ID, "snapshot"));
    return response;
  }

  private ObjectNode request(long id) {
    ObjectNode request = mapper.createObjectNode();
    request.put("custom_id", Long.toString(id));
    request.put("method", "POST");
    request.put("url", "/v1/chat/completions");
    ObjectNode body = request.putObject("body");
    body.put("model", "original-model");
    ArrayNode messages = body.putArray("messages");
    messages
        .addObject()
        .put("role", "system")
        .put("content", "Original locale and plural guidance");
    messages.addObject().put("role", "user").put("content", "Original source and glossary context");
    return request;
  }

  private static BatchRepairCandidate candidate(long id) {
    return new BatchRepairCandidate(
        id,
        "candidate without required forms",
        List.of(
            TranslationIntegrityDiagnostic.targetError(
                "plural-category-missing", Map.of("missing", List.of("few")))));
  }
}
