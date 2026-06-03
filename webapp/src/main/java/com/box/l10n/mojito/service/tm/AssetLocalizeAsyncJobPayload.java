package com.box.l10n.mojito.service.tm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssetLocalizeAsyncJobPayload(Long pollableTaskId, String outputId) {

  private static final int MAX_JSON_LENGTH = 1_000_000;
  private static final JsonFactory JSON_FACTORY =
      new JsonFactoryBuilder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxDocumentLength(MAX_JSON_LENGTH)
                  .maxNameLength(32)
                  .maxNumberLength(20)
                  .maxStringLength(36)
                  .maxNestingDepth(1)
                  .build())
          .build();

  public AssetLocalizeAsyncJobPayload(Long pollableTaskId) {
    this(pollableTaskId, null);
  }

  public AssetLocalizeAsyncJobPayload {
    Objects.requireNonNull(pollableTaskId);
    if (pollableTaskId <= 0) {
      throw new IllegalArgumentException("pollableTaskId must be positive");
    }
    if (outputId != null && !UUID.fromString(outputId).toString().equals(outputId)) {
      throw new IllegalArgumentException("outputId must be a canonical UUID");
    }
  }

  /** Decode persisted identity without application-mapper coercions or ambiguous JSON. */
  static AssetLocalizeAsyncJobPayload fromJson(String json) {
    if (json == null || json.isEmpty() || json.length() > MAX_JSON_LENGTH) {
      throw invalidPayload();
    }
    try (JsonParser parser = JSON_FACTORY.createParser(json)) {
      requireToken(parser.nextToken(), JsonToken.START_OBJECT);
      long pollableTaskId = 0;
      String outputId = null;
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        requireToken(parser.currentToken(), JsonToken.FIELD_NAME);
        String name = parser.currentName();
        JsonToken value = parser.nextToken();
        switch (name) {
          case "pollableTaskId" -> {
            requireToken(value, JsonToken.VALUE_NUMBER_INT);
            pollableTaskId = parser.getLongValue();
          }
          case "outputId" -> {
            if (value != JsonToken.VALUE_NULL) {
              requireToken(value, JsonToken.VALUE_STRING);
              outputId = parser.getText();
            }
          }
          default -> throw invalidPayload();
        }
      }
      if (parser.nextToken() != null) {
        throw invalidPayload();
      }
      return new AssetLocalizeAsyncJobPayload(pollableTaskId, outputId);
    } catch (IOException | IllegalArgumentException failure) {
      // Parser/UUID exceptions can expose supplied names, values or source excerpts.
      throw invalidPayload();
    }
  }

  private static void requireToken(JsonToken actual, JsonToken expected) {
    if (actual != expected) {
      throw invalidPayload();
    }
  }

  private static IllegalArgumentException invalidPayload() {
    return new IllegalArgumentException("Invalid asset localize async job payload");
  }
}
