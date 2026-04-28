package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
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
}
