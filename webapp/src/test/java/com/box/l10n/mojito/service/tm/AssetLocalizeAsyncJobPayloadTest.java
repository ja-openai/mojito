package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.json.ObjectMapper;
import java.util.Arrays;
import org.junit.Test;

public class AssetLocalizeAsyncJobPayloadTest {

  private static final String OUTPUT_ID = "af45fca0-32e0-4b91-9d66-01db2ec6f159";

  @Test
  public void readsLegacyAndAttemptOutputPayloadsWrittenByCurrentMapper() {
    ObjectMapper mapper = new ObjectMapper();
    for (long taskId : new long[] {1, 42, Long.MAX_VALUE}) {
      for (String outputId : new String[] {null, OUTPUT_ID}) {
        var payload = new AssetLocalizeAsyncJobPayload(taskId, outputId);
        String json = mapper.writeValueAsStringUnchecked(payload);
        assertThat(AssetLocalizeAsyncJobPayload.fromJson(json)).isEqualTo(payload);
      }
    }
    assertThat(mapper.writeValueAsStringUnchecked(new AssetLocalizeAsyncJobPayload(42L)))
        .isEqualTo("{\"pollableTaskId\":42}");
  }

  @Test
  public void acceptsWhitespaceFieldOrderEscapesAndExplicitNullOutput() {
    assertThat(
            AssetLocalizeAsyncJobPayload.fromJson(
                " \t\r\n{\"outputId\":null,\"pollableTask\\u0049d\":42}\n"))
        .isEqualTo(new AssetLocalizeAsyncJobPayload(42L));
    assertThat(
            AssetLocalizeAsyncJobPayload.fromJson(
                "{\"outputId\":\"\\u0061" + OUTPUT_ID.substring(1) + "\",\"pollableTaskId\":42}"))
        .isEqualTo(new AssetLocalizeAsyncJobPayload(42L, OUTPUT_ID));
  }

  @Test
  public void taskIdentityRequiresPositiveIntegralLongWithoutCoercion() {
    for (String value :
        new String[] {
          "null",
          "0",
          "-0",
          "-1",
          "42.0",
          "42.9",
          "42e0",
          "4.2e1",
          "\"42\"",
          "true",
          "false",
          "[42]",
          "{}",
          "9223372036854775808",
          "-9223372036854775809"
        }) {
      assertInvalid("{\"pollableTaskId\":" + value + "}");
    }
    assertInvalid("{}");
    assertInvalid("{\"outputId\":null}");
  }

  @Test
  public void rejectsDuplicateFieldsIncludingEscapedNamesAndEqualValues() {
    for (String json :
        new String[] {
          "{\"pollableTaskId\":41,\"pollableTaskId\":42}",
          "{\"pollableTaskId\":42,\"pollableTaskId\":42}",
          "{\"pollableTaskId\":42,\"pollableTask\\u0049d\":43}",
          "{\"pollableTaskId\":42,\"outputId\":null,\"outputId\":null}",
          "{\"pollableTaskId\":42,\"outputId\":null,\"output\\u0049d\":\"" + OUTPUT_ID + "\"}"
        }) {
      assertInvalid(json);
    }
  }

  @Test
  public void requiresOneCompleteObjectWithOnlyKnownFields() {
    for (String json :
        new String[] {
          "null",
          "42",
          "\"42\"",
          "[]",
          "[{\"pollableTaskId\":42}]",
          "",
          " \n",
          "{\"pollableTaskId\":42",
          "{\"pollableTaskId\":",
          "{\"pollableTaskId\":42,}",
          "{\"pollableTaskId\":42}{}",
          "{\"pollableTaskId\":42}null",
          "{\"pollableTaskId\":42}secret",
          "{\"pollableTaskId\":42,\"futureSecretField\":null}",
          "{\"pollableTaskId\":42,\"nested\":{\"secret\":42}}",
          "\ufeff{\"pollableTaskId\":42}",
          "/*secret*/{\"pollableTaskId\":42}",
          "{'pollableTaskId':42}",
          "{pollableTaskId:42}",
          "{\"pollableTaskId\":+42}",
          "{\"pollableTaskId\":042}"
        }) {
      assertInvalid(json);
    }
    assertInvalid(null);
  }

  @Test
  public void outputIdentityRequiresCanonicalUuidStringOrNull() {
    for (String value :
        new String[] {
          "42",
          "true",
          "[]",
          "{}",
          "\"\"",
          "\"1-1-1-1-1\"",
          "\"" + OUTPUT_ID.toUpperCase(java.util.Locale.ROOT) + "\"",
          "\" " + OUTPUT_ID + "\"",
          "\"" + OUTPUT_ID + " \"",
          "\"\\u0000\"",
          "\"\\uD800\"",
          "\"" + "x".repeat(100_000) + "\""
        }) {
      assertInvalid("{\"pollableTaskId\":42,\"outputId\":" + value + "}");
    }
  }

  @Test
  public void boundsInputAtExistingQueueLimitWithoutRejectingValidPadding() {
    String json = "{\"pollableTaskId\":42}";
    String padded = json + " ".repeat(1_000_000 - json.length());
    assertThat(AssetLocalizeAsyncJobPayload.fromJson(padded))
        .isEqualTo(new AssetLocalizeAsyncJobPayload(42L));
    assertInvalid(padded + " ");
  }

  @Test
  public void newPayloadFieldsRequireAnExplicitDecoderCompatibilityDecision() {
    assertThat(
            Arrays.stream(AssetLocalizeAsyncJobPayload.class.getRecordComponents())
                .map(component -> component.getName())
                .toList())
        .containsExactly("pollableTaskId", "outputId");
  }

  private void assertInvalid(String json) {
    assertThatThrownBy(() -> AssetLocalizeAsyncJobPayload.fromJson(json))
        .isExactlyInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid asset localize async job payload")
        .hasNoCause();
  }
}
