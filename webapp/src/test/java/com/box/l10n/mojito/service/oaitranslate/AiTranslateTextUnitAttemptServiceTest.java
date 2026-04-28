package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.AiTranslateTextUnitAttempt;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class AiTranslateTextUnitAttemptServiceTest {

  @Test
  public void redactImageDataUrlsRedactsDataUrlsButKeepsRegularUrls() {
    String jsonl =
        """
        {"body":{"input":[{"content":[{"image_url":"data:image/png;base64,abcdef"},{"image_url":"https://example.com/image.png"}]}]}}
        {"image_url":"data:image/jpeg;base64,123456"}
        """;

    String redacted =
        AiTranslateTextUnitAttemptService.redactImageDataUrls(jsonl, new ObjectMapper());

    assertFalse(redacted.contains("data:image"));
    assertTrue(redacted.contains(AiTranslateTextUnitAttemptService.REDACTED_IMAGE_DATA_URL));
    assertTrue(redacted.contains("https://example.com/image.png"));
    assertEquals(2, redacted.lines().count());
  }

  @Test
  public void redactImageDataUrlsHandlesPrettyPrintedJson() {
    String json =
        """
        {
          "image_url": "data:image/png;base64,abcdef"
        }
        """;

    String redacted =
        AiTranslateTextUnitAttemptService.redactImageDataUrls(json, new ObjectMapper());

    assertFalse(redacted.contains("data:image"));
    assertTrue(redacted.contains(AiTranslateTextUnitAttemptService.REDACTED_IMAGE_DATA_URL));
  }

  @Test
  public void putPayloadBlobUsesLineagePrefixAndRedactsImageDataUrls() {
    StructuredBlobStorage structuredBlobStorage = mock(StructuredBlobStorage.class);
    AiTranslateTextUnitAttemptService service =
        new AiTranslateTextUnitAttemptService(
            mock(AiTranslateTextUnitAttemptRepository.class),
            mock(AiTranslateRunRepository.class),
            structuredBlobStorage,
            new ObjectMapper());

    String blobName =
        service.putPayloadBlob(
            42L, "group", "request.json", "{\"image_url\":\"data:image/png;base64,abc\"}");

    assertEquals("42/group/request.json", blobName);
    verify(structuredBlobStorage)
        .put(
            eq(StructuredBlobStorage.Prefix.AI_TRANSLATE_LINEAGE),
            eq("42/group/request.json"),
            eq("{\"image_url\":\"[image data omitted]\"}"),
            eq(Retention.PERMANENT));
  }

  @Test
  public void getTextUnitAttemptsReturnsSummariesWithoutBlobNames() {
    AiTranslateTextUnitAttemptRepository repository =
        mock(AiTranslateTextUnitAttemptRepository.class);
    AiTranslateTextUnitAttempt attempt = new AiTranslateTextUnitAttempt();
    TMTextUnitVariant variant = new TMTextUnitVariant();
    ZonedDateTime createdDate = ZonedDateTime.parse("2026-04-27T10:15:30Z");
    ZonedDateTime lastModifiedDate = ZonedDateTime.parse("2026-04-27T10:16:30Z");

    attempt.setId(7L);
    attempt.setCreatedDate(createdDate);
    attempt.setLastModifiedDate(lastModifiedDate);
    variant.setId(99L);
    attempt.setTmTextUnitVariant(variant);
    attempt.setRequestGroupId("request-group");
    attempt.setTranslateType("TRANSLATE");
    attempt.setModel("model-name");
    attempt.setStatus(AiTranslateTextUnitAttempt.STATUS_IMPORTED);
    attempt.setCompletionId("completion-id");
    attempt.setRequestPayloadBlobName("42/request-group/request.json");
    attempt.setResponsePayloadBlobName("42/request-group/response.json");

    when(repository.findByTmTextUnit_IdAndLocale_IdOrderByCreatedDateDesc(1L, 2L))
        .thenReturn(List.of(attempt));

    AiTranslateTextUnitAttemptService service =
        new AiTranslateTextUnitAttemptService(
            repository,
            mock(AiTranslateRunRepository.class),
            mock(StructuredBlobStorage.class),
            new ObjectMapper());

    List<AiTranslateTextUnitAttemptService.TextUnitAttemptSummary> summaries =
        service.getTextUnitAttempts(1L, 2L);

    assertEquals(1, summaries.size());
    AiTranslateTextUnitAttemptService.TextUnitAttemptSummary summary = summaries.get(0);
    assertEquals(Long.valueOf(7L), summary.id());
    assertEquals(createdDate, summary.createdDate());
    assertEquals(lastModifiedDate, summary.lastModifiedDate());
    assertEquals(Long.valueOf(99L), summary.tmTextUnitVariantId());
    assertEquals("request-group", summary.requestGroupId());
    assertEquals("TRANSLATE", summary.translateType());
    assertEquals("model-name", summary.model());
    assertEquals(AiTranslateTextUnitAttempt.STATUS_IMPORTED, summary.status());
    assertEquals("completion-id", summary.completionId());
    assertTrue(summary.hasRequestPayload());
    assertTrue(summary.hasResponsePayload());
  }

  @Test
  public void getTextUnitAttemptReturnsScopedSummaryWithoutBlobNames() {
    AiTranslateTextUnitAttemptRepository repository =
        mock(AiTranslateTextUnitAttemptRepository.class);
    AiTranslateTextUnitAttempt attempt = new AiTranslateTextUnitAttempt();
    TMTextUnitVariant variant = new TMTextUnitVariant();
    variant.setId(99L);
    attempt.setId(7L);
    attempt.setTmTextUnitVariant(variant);
    attempt.setRequestGroupId("request-group");
    attempt.setTranslateType("TRANSLATE");
    attempt.setModel("model-name");
    attempt.setStatus(AiTranslateTextUnitAttempt.STATUS_IMPORTED);
    attempt.setCompletionId("completion-id");
    attempt.setRequestPayloadBlobName("42/request-group/request.json");
    attempt.setResponsePayloadBlobName("42/request-group/response.json");

    when(repository.findByIdAndTmTextUnit_IdAndLocale_Id(7L, 1L, 2L))
        .thenReturn(Optional.of(attempt));

    AiTranslateTextUnitAttemptService service =
        new AiTranslateTextUnitAttemptService(
            repository,
            mock(AiTranslateRunRepository.class),
            mock(StructuredBlobStorage.class),
            new ObjectMapper());

    Optional<AiTranslateTextUnitAttemptService.TextUnitAttemptSummary> summary =
        service.getTextUnitAttempt(1L, 2L, 7L);

    assertTrue(summary.isPresent());
    assertEquals(Long.valueOf(7L), summary.get().id());
    assertEquals(Long.valueOf(99L), summary.get().tmTextUnitVariantId());
    assertEquals("request-group", summary.get().requestGroupId());
    assertEquals("TRANSLATE", summary.get().translateType());
    assertEquals("model-name", summary.get().model());
    assertEquals(AiTranslateTextUnitAttempt.STATUS_IMPORTED, summary.get().status());
    assertEquals("completion-id", summary.get().completionId());
    assertTrue(summary.get().hasRequestPayload());
    assertTrue(summary.get().hasResponsePayload());
  }

  @Test
  public void getRecentLineageReturnsSummariesWithoutBlobNames() {
    AiTranslateTextUnitAttemptRepository repository =
        mock(AiTranslateTextUnitAttemptRepository.class);
    ZonedDateTime createdDate = ZonedDateTime.parse("2026-04-27T10:15:30Z");
    ZonedDateTime lastModifiedDate = ZonedDateTime.parse("2026-04-27T10:16:30Z");
    AiTranslateTextUnitAttemptLineageRow row =
        new AiTranslateTextUnitAttemptLineageRow(
            7L,
            createdDate,
            lastModifiedDate,
            11L,
            "source.name",
            99L,
            "fr-FR",
            3L,
            "repo",
            42L,
            5L,
            "request-group",
            "TRANSLATE",
            "model-name",
            AiTranslateTextUnitAttempt.STATUS_IMPORTED,
            "completion-id",
            "42/request-group/request.json",
            "42/request-group/response.json",
            "error");

    when(repository.findRecentLineageRowsByRepositoryIds(eq(List.of(3L)), any()))
        .thenReturn(List.of(row));

    AiTranslateTextUnitAttemptService service =
        new AiTranslateTextUnitAttemptService(
            repository,
            mock(AiTranslateRunRepository.class),
            mock(StructuredBlobStorage.class),
            new ObjectMapper());

    List<AiTranslateTextUnitAttemptService.TextUnitAttemptLineageSummary> summaries =
        service.getRecentLineage(List.of(3L), List.of(), 20);

    assertEquals(1, summaries.size());
    AiTranslateTextUnitAttemptService.TextUnitAttemptLineageSummary summary = summaries.get(0);
    assertEquals(Long.valueOf(7L), summary.id());
    assertEquals(createdDate, summary.createdDate());
    assertEquals(lastModifiedDate, summary.lastModifiedDate());
    assertEquals(Long.valueOf(11L), summary.tmTextUnitId());
    assertEquals("source.name", summary.tmTextUnitName());
    assertEquals(Long.valueOf(99L), summary.tmTextUnitVariantId());
    assertEquals("fr-FR", summary.localeBcp47Tag());
    assertEquals(Long.valueOf(3L), summary.repositoryId());
    assertEquals("repo", summary.repositoryName());
    assertEquals(Long.valueOf(42L), summary.pollableTaskId());
    assertEquals(Long.valueOf(5L), summary.aiTranslateRunId());
    assertEquals("request-group", summary.requestGroupId());
    assertEquals("TRANSLATE", summary.translateType());
    assertEquals("model-name", summary.model());
    assertEquals(AiTranslateTextUnitAttempt.STATUS_IMPORTED, summary.status());
    assertEquals("completion-id", summary.completionId());
    assertTrue(summary.hasRequestPayload());
    assertTrue(summary.hasResponsePayload());
    assertEquals("error", summary.errorMessage());
  }

  @Test
  public void getRecentLineageCanFilterByPollableTask() {
    AiTranslateTextUnitAttemptRepository repository =
        mock(AiTranslateTextUnitAttemptRepository.class);
    AiTranslateTextUnitAttemptLineageRow row =
        new AiTranslateTextUnitAttemptLineageRow(
            7L,
            null,
            null,
            11L,
            null,
            null,
            "fr-FR",
            3L,
            "repo",
            42L,
            5L,
            "request-group",
            "TRANSLATE",
            null,
            AiTranslateTextUnitAttempt.STATUS_REQUESTED,
            null,
            null,
            null,
            null);

    when(repository.findRecentLineageRowsByPollableTaskIds(eq(List.of(42L)), any()))
        .thenReturn(List.of(row));

    AiTranslateTextUnitAttemptService service =
        new AiTranslateTextUnitAttemptService(
            repository,
            mock(AiTranslateRunRepository.class),
            mock(StructuredBlobStorage.class),
            new ObjectMapper());

    List<AiTranslateTextUnitAttemptService.TextUnitAttemptLineageSummary> summaries =
        service.getRecentLineage(List.of(3L), List.of(42L), 20);

    assertEquals(1, summaries.size());
    assertEquals(Long.valueOf(42L), summaries.get(0).pollableTaskId());
    assertFalse(summaries.get(0).hasRequestPayload());
    assertFalse(summaries.get(0).hasResponsePayload());
  }

  @Test
  public void getRequestPayloadReadsScopedLineageBlob() {
    AiTranslateTextUnitAttemptRepository repository =
        mock(AiTranslateTextUnitAttemptRepository.class);
    StructuredBlobStorage structuredBlobStorage = mock(StructuredBlobStorage.class);
    AiTranslateTextUnitAttempt attempt = new AiTranslateTextUnitAttempt();
    attempt.setRequestPayloadBlobName("42/request-group/request.json");

    when(repository.findByIdAndTmTextUnit_IdAndLocale_Id(7L, 1L, 2L))
        .thenReturn(Optional.of(attempt));
    when(structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.AI_TRANSLATE_LINEAGE, "42/request-group/request.json"))
        .thenReturn(Optional.of("{\"ok\":true}"));

    AiTranslateTextUnitAttemptService service =
        new AiTranslateTextUnitAttemptService(
            repository,
            mock(AiTranslateRunRepository.class),
            structuredBlobStorage,
            new ObjectMapper());

    assertEquals(Optional.of("{\"ok\":true}"), service.getRequestPayload(1L, 2L, 7L));
  }
}
