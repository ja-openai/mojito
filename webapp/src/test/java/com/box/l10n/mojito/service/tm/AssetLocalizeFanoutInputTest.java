package com.box.l10n.mojito.service.tm;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.POLLABLE_TASK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

public class AssetLocalizeFanoutInputTest {
  private final StructuredBlobStorage blobs = mock(StructuredBlobStorage.class);
  private final AssetLocalizeFanoutInput inputs =
      new AssetLocalizeFanoutInput(mock(JdbcTemplate.class), blobs, new ObjectMapper());

  @Test
  public void retainedManifestRejectsInvalidOutputTagsDespiteMatchingChecksum() throws Exception {
    for (String tagJson :
        List.of(
            "null",
            "\" \"",
            "\"" + "a".repeat(256) + "\"",
            "\"tag\\u0000\"",
            "\"tag\\ud800\"",
            "\"tag\\udc00\"",
            "\"tag\\ud800x\"")) {
      AssetLocalizeFanoutInput.Reference reference = retainedManifest(tagJson);
      assertThatThrownBy(() -> inputs.read(reference))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Durable asset fanout input is missing or invalid")
          .hasNoCause();
    }
  }

  @Test
  public void retainedManifestPreservesValidUnicodeOutputTag() throws Exception {
    AssetLocalizeFanoutInput.Reference reference =
        retainedManifest("\"custom-\\u65e5\\u672c-\\ud83d\\ude80\"");
    assertThat(inputs.read(reference).slots())
        .containsExactly(
            new AssetLocalizeFanoutInput.Slot(3L, "custom-\u65E5\u672C-\uD83D\uDE80", null));
  }

  private AssetLocalizeFanoutInput.Reference retainedManifest(String tagJson) throws Exception {
    // Keep JSON escapes intact so malformed UTF-16 is not replaced during UTF-8 encoding.
    byte[] bytes =
        ("{\"version\":1,\"input\":{\"assetId\":1},\"slots\":[{\"localeId\":3,\"outputTag\":"
                + tagJson
                + "}]}")
            .getBytes(StandardCharsets.UTF_8);
    when(blobs.getBytes(POLLABLE_TASK, "fixture/input")).thenReturn(Optional.of(bytes));
    return new AssetLocalizeFanoutInput.Reference(
        "fixture/input",
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        1);
  }
}
